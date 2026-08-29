package dev.loki.android.core.voice.stt

import android.content.Context
import android.util.Log
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntimeController
import dev.loki.android.core.models.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LiteRtWhisperEngine provides on-device speech-to-text transcription using the LiteRT
 * TFLite Interpreter API against the multi-subgraph `whisper_tiny_30s_f32.tflite` artifact.
 *
 * Model structure (from artifact inspection):
 *   - Subgraph 0: encoder  — input `[1,80,3000]` f32 mel-spectrogram → output `[1,1500,384]` encoder states
 *   - Subgraph 1: decoder  — inputs: encoder states `[1,1500,384]`, token ids `[1,128]` int32,
 *                            attention mask `[1,1,128,128]` f32 → logits `[1,128,51865]` f32
 *   - Subgraphs 2–35: odml.* stablehlo composite implementations (group_norm, SDPA)
 *
 * Mel-spectrogram frontend: 16 kHz PCM floats → 80-band log-mel with 25 ms frames / 10 ms hop.
 * Tokenizer: multilingual BPE loaded from `assets/whisper/multilingual.tiktoken` at runtime.
 * Mel filter bank: loaded from `assets/whisper/mel_filters.npz` at runtime.
 *
 * [storage] resolves the model artifact path. [context] is needed to open bundled assets.
 */
class LiteRtWhisperEngine(
    private val storage: ModelStorage,
    private val context: Context
) : SttEngine, ModelRuntimeController {

    private var audioRecorder: AudioRecorder? = null
    @Volatile override var isListening: Boolean = false
        private set

    @Volatile private var isInitialized: Boolean = false
    private var interpreter: Interpreter? = null
    private var melFilters: Array<FloatArray>? = null        // [80][201] half-spectrum filter bank
    private var tokenEncoder: WhisperTokenizer? = null

    /**
     * True only when the TFLite interpreter has been successfully constructed and the
     * mel filter bank and tokenizer are loaded. Used by [ModelLibraryManager.isRuntimeReady].
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Resolves the `.tflite` artifact through [storage] and initializes the TFLite interpreter.
     * Returns false if no `.tflite` artifact is present, the file does not exist on disk,
     * or interpreter construction fails.
     */
    override suspend fun load(model: ModelRecord): Boolean {
        val artifact = model.artifacts.firstOrNull { it.fileName.endsWith(".tflite") }
            ?: return false

        val resolvedFile = storage.artifactFile(model.id, artifact.relativePath)
        if (!resolvedFile.exists()) {
            Log.w(TAG, "Artifact not found on disk: ${resolvedFile.absolutePath}")
            return false
        }

        Log.i(TAG, "Loading LiteRT Whisper model from: ${resolvedFile.absolutePath}")
        return initialize(resolvedFile.absolutePath)
    }

    override suspend fun unload(model: ModelRecord) {
        release()
    }

    /**
     * Constructs the TFLite Interpreter from the model file at [path], loads the mel filter
     * bank and tokenizer assets. [isInitialized] is set true only on complete success.
     */
    fun initialize(path: String): Boolean {
        val modelFile = File(path)
        if (!modelFile.exists()) {
            Log.w(TAG, "Model file does not exist: $path")
            return false
        }

        return try {
            val options = Interpreter.Options().apply {
                numThreads = NUM_THREADS
                setUseNNAPI(false)
                setUseXNNPACK(false)
            }
            val interp = Interpreter(modelFile, options)
            val sigKeys = interp.signatureKeys.joinToString(", ")
            Log.i(TAG, "TFLite interpreter created — signatures: [$sigKeys]")

            val filters = loadMelFilters()
            val tokenizer = WhisperTokenizer(context)

            interpreter = interp
            melFilters = filters
            tokenEncoder = tokenizer
            isInitialized = true
            Log.i(TAG, "LiteRtWhisperEngine initialized successfully from: $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LiteRtWhisperEngine: ${e.message}", e)
            false
        }
    }

    override fun startListening(): Flow<SttEvent> = flow {
        if (!isInitialized) {
            emit(SttEvent.Error(IllegalStateException("Whisper model not initialized")))
            return@flow
        }

        isListening = true
        emit(SttEvent.ListeningStarted)
        val recorder = AudioRecorder()
        audioRecorder = recorder

        try {
            val audioFloats = recorder.recordUtterance()
            emit(SttEvent.ListeningStopped)
            isListening = false

            if (audioFloats.isEmpty()) {
                emit(SttEvent.FinalResult(""))
                return@flow
            }

            val transcript = withContext(Dispatchers.Default) {
                transcribePcmAudio(audioFloats)
            }.trim()

            emit(SttEvent.FinalResult(transcript))
        } catch (e: CancellationException) {
            isListening = false
            emit(SttEvent.ListeningStopped)
            throw e
        } catch (e: Throwable) {
            isListening = false
            emit(SttEvent.Error(e))
        } finally {
            audioRecorder = null
            isListening = false
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Full Whisper inference pipeline:
     * 1. Pad/crop PCM to 30 s window.
     * 2. Compute 80-band log-mel spectrogram -> `[1,80,3000]`.
     * 3. Run encoder signature "encode": `args_0` -> `output_0` `[1,1500,384]`.
     * 4. Autoregressive decoder loop (signature "decode"):
     *    - Inputs: `args_0` (encoder output), `args_1` (token IDs `[1,128]`), `args_2` (mask `[1,1,128,128]`).
     *    - Output: `output_0` (logits `[1,128,51865]`).
     *    - Prefill with `<|startoftranscript|><|en|><|transcribe|>`.
     *    - Greedy decode: argmax over the last generated position logits.
     *    - Stop on `<|endoftext|>`.
     * 5. Detokenize and return the transcript string.
     * 6. Log duration; return "" for empty/near-silence.
     */
    private fun transcribePcmAudio(pcmFloats: FloatArray): String {
        val interp = interpreter ?: return ""
        val filters = melFilters ?: return ""
        val tokenizer = tokenEncoder ?: return ""

        // --- Step 1: pad/crop to 30 s ---
        val targetSamples = SAMPLE_RATE * WINDOW_SECONDS
        val padded = FloatArray(targetSamples)
        System.arraycopy(pcmFloats, 0, padded, 0, minOf(pcmFloats.size, targetSamples))

        Log.i(TAG, "Running Whisper TFLite inference on ${pcmFloats.size} PCM samples (${WINDOW_SECONDS}s window)")
        val startMs = System.currentTimeMillis()

        // --- Step 2: log-mel spectrogram [1,80,3000] ---
        val melStart = System.currentTimeMillis()
        val mel = computeLogMel(padded, filters)  // [80][3000]
        val melMs = System.currentTimeMillis() - melStart

        // Pack into [1,80,3000] float array
        val encoderInput = Array(1) { Array(MEL_BINS) { FloatArray(MEL_FRAMES) } }
        for (b in 0 until MEL_BINS) {
            for (t in 0 until MEL_FRAMES) {
                encoderInput[0][b][t] = mel[b][t]
            }
        }

        // --- Step 3: encoder signature "encode" ---
        // Output: [1,1500,384]
        val encStart = System.currentTimeMillis()
        val encoderOutput = Array(1) { Array(ENCODER_SEQ_LEN) { FloatArray(ENCODER_DIM) } }
        interp.runSignature(
            mapOf("args_0" to encoderInput),
            mapOf("output_0" to encoderOutput),
            "encode"
        )
        val encMs = System.currentTimeMillis() - encStart

        // --- Step 4: autoregressive decoder signature "decode" ---
        val decStart = System.currentTimeMillis()
        val prefixTokens = tokenizer.getPrefixTokens()   // [<|startoftranscript|>, <|en|>, <|transcribe|>, <|notimestamps|>]
        val tokenIds = Array(1) { IntArray(MAX_TOKENS) }
        var seqLen = prefixTokens.size
        for (i in prefixTokens.indices) tokenIds[0][i] = prefixTokens[i]

        val generatedTokens = mutableListOf<Int>()

        // Pre-allocate mask and logits buffers to avoid re-allocations in the loop
        val maskInput = Array(1) { Array(1) { Array(MAX_TOKENS) { FloatArray(MAX_TOKENS) } } }
        val logits = Array(1) { Array(MAX_TOKENS) { FloatArray(VOCAB_SIZE) } }

        for (step in seqLen until MAX_TOKENS) {
            // Build causal attention mask [1,1,MAX_TOKENS,MAX_TOKENS]: 0 for attending, -inf for masked
            for (i in 0 until MAX_TOKENS) {
                for (j in 0 until MAX_TOKENS) {
                    maskInput[0][0][i][j] = if (j <= i && j < step) 0f else -10000.0f
                }
            }

            interp.runSignature(
                mapOf(
                    "args_0" to encoderOutput,
                    "args_1" to tokenIds,
                    "args_2" to maskInput
                ),
                mapOf("output_0" to logits),
                "decode"
            )

            // Greedy argmax on the position [step-1] (last generated position)
            val stepLogits = logits[0][step - 1]
            var bestToken = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (v in stepLogits.indices) {
                if (stepLogits[v] > bestScore) {
                    bestScore = stepLogits[v]
                    bestToken = v
                }
            }

            // Stop if model emits end-of-text or any special/timestamp token (>= 50257)
            if (bestToken >= tokenizer.endOfTextToken) break

            tokenIds[0][step] = bestToken
            seqLen = step + 1
            generatedTokens.add(bestToken)

            // Safeguard against repetitive generation loops (e.g. repeating phrases):
            val nGen = generatedTokens.size
            if (nGen >= 4 && generatedTokens[nGen - 1] == generatedTokens[nGen - 2] &&
                generatedTokens[nGen - 2] == generatedTokens[nGen - 3] &&
                generatedTokens[nGen - 3] == generatedTokens[nGen - 4]) {
                break
            }
            if (nGen >= 6 &&
                generatedTokens[nGen - 1] == generatedTokens[nGen - 3] &&
                generatedTokens[nGen - 3] == generatedTokens[nGen - 5] &&
                generatedTokens[nGen - 2] == generatedTokens[nGen - 4] &&
                generatedTokens[nGen - 4] == generatedTokens[nGen - 6]) {
                break
            }
        }

        val decMs = System.currentTimeMillis() - decStart
        val durationMs = System.currentTimeMillis() - startMs
        val transcript = tokenizer.decode(generatedTokens).trim()
        Log.i(TAG, "Whisper TFLite transcription completed in ${durationMs}ms [mel=${melMs}ms, encoder=${encMs}ms, decoder=${decMs}ms (${generatedTokens.size} tokens)]: \"$transcript\"")
        return transcript
    }

    /**
     * Computes the 80-band log-mel spectrogram from raw 16 kHz PCM matching OpenAI Whisper spec:
     * - Frame size: 400 samples (25 ms)
     * - Hop size: 160 samples (10 ms)
     * - Hann window: 400 points
     * - 400-point FFT -> 201 frequency bins (|X[k]|^2 for k = 0..200)
     * - 80-channel mel filter bank: [80, 201] from mel_filters.npz
     * - log10(max(sum, 1e-10))
     * - Normalised: clamp to [max - 8.0, max] and scale to [-1, 1] via (x + 4.0) / 4.0
     */
    private fun computeLogMel(pcm: FloatArray, filters: Array<FloatArray>): Array<FloatArray> {
        val frameSize = 400
        val hopSize = 160
        val nFrames = MEL_FRAMES // 3000
        val nFft = 201 // 1 + frameSize / 2 (matches filters[b].size == 201)

        val hann = FloatArray(frameSize) { j ->
            0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * j / frameSize).toFloat())
        }

        val fftIn = FloatArray(frameSize)
        val fftOut = FloatArray(frameSize * 2)
        val mel = Array(MEL_BINS) { FloatArray(nFrames) }

        var maxVal = Float.NEGATIVE_INFINITY

        for (frame in 0 until nFrames) {
            val offset = frame * hopSize

            for (j in 0 until frameSize) {
                val sampleIdx = offset + j
                val sample = if (sampleIdx < pcm.size) pcm[sampleIdx] else 0f
                fftIn[j] = sample * hann[j]
            }

            fft400(fftIn, 0, frameSize, fftOut, 0)

            for (b in 0 until MEL_BINS) {
                var sum = 0.0
                for (k in 0 until nFft) {
                    val re = fftOut[2 * k]
                    val im = fftOut[2 * k + 1]
                    val power = (re * re + im * im).toDouble()
                    sum += power * filters[b][k]
                }
                val logSum = kotlin.math.log10(kotlin.math.max(sum, 1e-10)).toFloat()
                mel[b][frame] = logSum
                if (logSum > maxVal) {
                    maxVal = logSum
                }
            }
        }

        val floor = maxVal - 8.0f
        for (b in 0 until MEL_BINS) {
            for (frame in 0 until nFrames) {
                val clamped = kotlin.math.max(mel[b][frame], floor)
                mel[b][frame] = (clamped + 4.0f) / 4.0f
            }
        }

        return mel
    }

    private fun dft(inArr: FloatArray, inOffset: Int, n: Int, outArr: FloatArray, outOffset: Int) {
        for (k in 0 until n) {
            var re = 0.0
            var im = 0.0
            for (j in 0 until n) {
                val angle = 2.0 * Math.PI * k * j / n
                val sample = inArr[inOffset + j].toDouble()
                re += sample * kotlin.math.cos(angle)
                im -= sample * kotlin.math.sin(angle)
            }
            outArr[outOffset + 2 * k] = re.toFloat()
            outArr[outOffset + 2 * k + 1] = im.toFloat()
        }
    }

    private fun fft400(inArr: FloatArray, inOffset: Int, n: Int, outArr: FloatArray, outOffset: Int) {
        if (n == 1) {
            outArr[outOffset] = inArr[inOffset]
            outArr[outOffset + 1] = 0f
            return
        }

        val halfN = n / 2
        if (n % 2 != 0) {
            dft(inArr, inOffset, n, outArr, outOffset)
            return
        }

        val even = FloatArray(halfN)
        val odd = FloatArray(halfN)
        for (i in 0 until halfN) {
            even[i] = inArr[inOffset + 2 * i]
            odd[i] = inArr[inOffset + 2 * i + 1]
        }

        val evenFft = FloatArray(halfN * 2)
        val oddFft = FloatArray(halfN * 2)

        fft400(even, 0, halfN, evenFft, 0)
        fft400(odd, 0, halfN, oddFft, 0)

        for (k in 0 until halfN) {
            val angle = 2.0 * Math.PI * k / n
            val wRe = kotlin.math.cos(angle).toFloat()
            val wIm = (-kotlin.math.sin(angle)).toFloat()

            val oddRe = oddFft[2 * k]
            val oddIm = oddFft[2 * k + 1]

            val tRe = wRe * oddRe - wIm * oddIm
            val tIm = wRe * oddIm + wIm * oddRe

            val evenRe = evenFft[2 * k]
            val evenIm = evenFft[2 * k + 1]

            outArr[outOffset + 2 * k] = evenRe + tRe
            outArr[outOffset + 2 * k + 1] = evenIm + tIm

            outArr[outOffset + 2 * (k + halfN)] = evenRe - tRe
            outArr[outOffset + 2 * (k + halfN) + 1] = evenIm - tIm
        }
    }

    /**
     * Loads the 80-band mel filter bank from `assets/whisper/mel_filters.npz`.
     * The npz file contains a single array `mel_80` of shape [80, 201] (half-spectrum
     * filters for a 512-point FFT at 16 kHz).
     */
    private fun loadMelFilters(): Array<FloatArray> {
        context.assets.open("whisper/mel_filters.npz").use { stream ->
            val bytes = stream.readBytes()
            return NpzLoader.loadMelFilters(bytes, MEL_BINS)
        }
    }

    override fun stopListening() {
        audioRecorder?.stop()
        isListening = false
    }

    override fun cancel() {
        stopListening()
    }

    override fun release() {
        cancel()
        interpreter?.close()
        interpreter = null
        melFilters = null
        tokenEncoder = null
        isInitialized = false
        Log.i(TAG, "LiteRtWhisperEngine released")
    }

    companion object {
        private const val TAG = "LiteRtWhisperEngine"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SECONDS = 30
        private const val MEL_BINS = 80
        private const val MEL_FRAMES = 3000
        private const val ENCODER_SEQ_LEN = 1500
        private const val ENCODER_DIM = 384
        private const val MAX_TOKENS = 128
        private const val VOCAB_SIZE = 51865
        private const val NUM_THREADS = 4
    }
}
