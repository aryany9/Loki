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
