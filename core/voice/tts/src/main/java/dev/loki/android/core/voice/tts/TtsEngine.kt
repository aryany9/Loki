package dev.loki.android.core.voice.tts

interface TtsEngine {
    val isSpeaking: Boolean
    val isReady: Boolean

    fun speak(
        text: String,
        utteranceId: String = "loki_${System.currentTimeMillis()}",
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    )

    fun configureLanguage(bcp47Tag: String? = null) {}
    fun stop()
    fun release()
}

/**
 * Speaks the provided [text] and suspends until playback completes (or errors/is cancelled).
 */
suspend fun TtsEngine.speakAndAwait(
    text: String,
    utteranceId: String = "loki_${System.currentTimeMillis()}"
) {
    if (!isReady || text.isBlank()) return
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        speak(
            text = text,
            utteranceId = utteranceId,
            onDone = {
                if (continuation.isActive) {
                    continuation.resume(Unit) {}
                }
            },
            onError = { _ ->
                if (continuation.isActive) {
                    continuation.resume(Unit) {}
                }
            }
        )
        continuation.invokeOnCancellation {
            stop()
        }
    }
}
