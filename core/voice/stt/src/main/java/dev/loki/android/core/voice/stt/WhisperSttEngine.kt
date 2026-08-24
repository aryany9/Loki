package dev.loki.android.core.voice.stt

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * WhisperSttEngine provides on-device speech-to-text transcription
 * using Whisper.cpp and energy-based VAD audio capture.
 */
class WhisperSttEngine(
    private val modelManager: WhisperModelManager,
    private val nThreads: Int = 4
) : SttEngine {

    @Volatile private var nativeHandle: Long = 0
    private var audioRecorder: AudioRecorder? = null
    @Volatile override var isListening: Boolean = false
        private set

    fun initialize(modelPath: String? = null): Boolean {
        if (nativeHandle != 0L) return true

        val path = modelPath ?: modelManager.getDefaultModelFile()?.absolutePath
        if (path == null) {
            Log.e(TAG, "No Whisper model file found")
            return false
        }

        Log.i(TAG, "Initializing Whisper engine from $path")
        nativeHandle = WhisperBridge.nativeInitWhisper(path, nThreads)
        return nativeHandle != 0L
    }

    override fun startListening(): Flow<SttEvent> = flow {
        if (nativeHandle == 0L && !initialize()) {
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
                WhisperBridge.nativeTranscribe(
                    handle = nativeHandle,
                    pcmFloats = audioFloats,
                    nSamples = audioFloats.size,
                    language = "en"
                )
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

    override fun stopListening() {
        audioRecorder?.stop()
        isListening = false
    }

    override fun cancel() {
        stopListening()
    }

    override fun release() {
        cancel()
        if (nativeHandle != 0L) {
            WhisperBridge.nativeFreeWhisper(nativeHandle)
            nativeHandle = 0L
            Log.i(TAG, "Whisper engine released")
        }
    }

    companion object {
        private const val TAG = "WhisperSttEngine"
    }
}
