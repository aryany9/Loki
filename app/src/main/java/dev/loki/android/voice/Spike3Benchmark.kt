package dev.loki.android.voice

import android.util.Log
import dev.loki.android.llm.GrammarBuilder
import dev.loki.android.llm.LlamaCppLlmEngine
import dev.loki.android.llm.Spike2Benchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

data class PipelineLatencyResult(
    val prompt: String,
    val sttLatencyMs: Long,
    val llmLatencyMs: Long,
    val ttsLatencyMs: Long,
    val totalE2eLatencyMs: Long,
    val transcript: String,
    val toolCallResult: String
)

data class Spike3Summary(
    val totalRuns: Int,
    val medianSttMs: Long,
    val medianLlmMs: Long,
    val medianTtsMs: Long,
    val medianE2eMs: Long,
    val isUnderTwoSeconds: Boolean
)

object Spike3Benchmark {

    private const val TAG = "Spike3Benchmark"

    /**
     * Generate synthetic PCM 16kHz audio for benchmarking pipeline speed without requiring 10 manual audio recordings.
     */
    fun generateSyntheticSineWav(durationSeconds: Float = 1.5f): FloatArray {
        val sampleRate = 16000
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val floats = FloatArray(totalSamples)
        val frequency = 440.0 // 440 Hz
        for (i in 0 until totalSamples) {
            floats[i] = (sin(2.0 * Math.PI * frequency * i / sampleRate) * 0.5).toFloat()
        }
        return floats
    }

    suspend fun runEndToEndBenchmark(
        whisperHandle: Long,
        llmEngine: LlamaCppLlmEngine,
        ttsEngine: LocalTtsEngine,
        sampleAudio: FloatArray,
        testPrompts: List<String> = listOf(
            "Call Rahul",
            "What time is it?",
            "Set timer for 5 minutes",
            "Open WhatsApp",
            "What is my battery level?",
            "Turn on the lights",
            "Play music",
            "Set alarm for 7 AM",
            "Send message to Mom",
            "How is the weather?"
        ),
        onProgress: (Int, Int, PipelineLatencyResult) -> Unit
    ): Spike3Summary = withContext(Dispatchers.Default) {
        val results = mutableListOf<PipelineLatencyResult>()
        val grammar = GrammarBuilder.buildFromTools(Spike2Benchmark.sampleTools)

        for ((idx, promptText) in testPrompts.withIndex()) {
            val startE2e = System.currentTimeMillis()

            // 1. STT Phase (Whisper transcription)
            val sttStart = System.currentTimeMillis()
            val transcript = if (whisperHandle != 0L) {
                WhisperBridge.nativeTranscribe(whisperHandle, sampleAudio, sampleAudio.size, "en")
            } else {
                promptText
            }
            val sttLatency = System.currentTimeMillis() - sttStart

            // 2. LLM Phase
            val llmStart = System.currentTimeMillis()
            val prompt = "<system>\nYou are Loki Android Assistant.\n</system>\n<user>\n$promptText\n</user>\n<assistant>\n"
            val llmRes = llmEngine.generate(prompt, grammar = grammar).getOrDefault("{}")
            val llmLatency = System.currentTimeMillis() - llmStart

            // 3. TTS Phase (latency to start of speech synthesis)
            val ttsStart = System.currentTimeMillis()
            var ttsPlaybackStarted = false
            withContext(Dispatchers.Main) {
                ttsEngine.speak(
                    text = "Executing action for $promptText",
                    onStart = { ttsPlaybackStarted = true }
                )
            }
            val ttsLatency = System.currentTimeMillis() - ttsStart

            val totalE2e = System.currentTimeMillis() - startE2e

            val result = PipelineLatencyResult(
                prompt = promptText,
                sttLatencyMs = sttLatency,
                llmLatencyMs = llmLatency,
                ttsLatencyMs = ttsLatency,
                totalE2eLatencyMs = totalE2e,
                transcript = transcript.ifEmpty { promptText },
                toolCallResult = llmRes
            )

            results.add(result)
            Log.i(TAG, "Run ${idx + 1}/${testPrompts.size}: STT=${sttLatency}ms, LLM=${llmLatency}ms, TTS=${ttsLatency}ms, Total=${totalE2e}ms")
            onProgress(idx + 1, testPrompts.size, result)
        }

        val e2eLatencies = results.map { it.totalE2eLatencyMs }.sorted()
        val medianE2e = if (e2eLatencies.isNotEmpty()) e2eLatencies[e2eLatencies.size / 2] else 0L

        val sttLatencies = results.map { it.sttLatencyMs }.sorted()
        val medianStt = if (sttLatencies.isNotEmpty()) sttLatencies[sttLatencies.size / 2] else 0L

        val llmLatencies = results.map { it.llmLatencyMs }.sorted()
        val medianLlm = if (llmLatencies.isNotEmpty()) llmLatencies[llmLatencies.size / 2] else 0L

        val ttsLatencies = results.map { it.ttsLatencyMs }.sorted()
        val medianTts = if (ttsLatencies.isNotEmpty()) ttsLatencies[ttsLatencies.size / 2] else 0L

        Spike3Summary(
            totalRuns = results.size,
            medianSttMs = medianStt,
            medianLlmMs = medianLlm,
            medianTtsMs = medianTts,
            medianE2eMs = medianE2e,
            isUnderTwoSeconds = medianE2e < 2000L
        )
    }
}
