package dev.loki.android.core.voice.stt

import kotlinx.coroutines.flow.Flow

sealed interface SttEvent {
    data object ListeningStarted : SttEvent
    data class Amplitude(val rms: Float) : SttEvent
    data class PartialResult(val text: String) : SttEvent
    data class FinalResult(val text: String) : SttEvent
    data class Error(val error: Throwable) : SttEvent
    data object ListeningStopped : SttEvent
}

interface SttEngine {
    val isListening: Boolean
    fun startListening(): Flow<SttEvent>
    suspend fun transcribeAudio(pcmAudio: FloatArray): String = ""
    fun stopListening()
    fun cancel()
    fun release()
}
