package dev.loki.android.core.voice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * AndroidTtsEngine wraps Android's native TextToSpeech engine.
 */
class AndroidTtsEngine(
    context: Context,
    private val onInitComplete: ((Boolean) -> Unit)? = null
) : TtsEngine, AutoCloseable {

    private var tts: TextToSpeech? = null
    override var isReady: Boolean = false
        private set
    override var isSpeaking: Boolean = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isReady = true
                Log.i(TAG, "Android TextToSpeech initialized successfully")
                onInitComplete?.invoke(true)
            } else {
                Log.e(TAG, "Failed to initialize Android TextToSpeech (status: $status)")
                onInitComplete?.invoke(false)
            }
        }
    }

    override fun speak(
        text: String,
        utteranceId: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        if (!isReady || tts == null) {
            Log.e(TAG, "TTS not ready to speak")
            onError?.invoke("TTS not initialized")
            return
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                isSpeaking = true
                Log.i(TAG, "TTS started utterance: $id")
                onStart?.invoke()
            }

            override fun onDone(id: String?) {
                isSpeaking = false
                Log.i(TAG, "TTS completed utterance: $id")
                onDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                isSpeaking = false
                Log.e(TAG, "TTS error on utterance: $id")
                onError?.invoke("TTS error")
            }
        })

        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    override fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    override fun release() {
        close()
    }

    override fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        isSpeaking = false
    }

    companion object {
        private const val TAG = "AndroidTtsEngine"
    }
}
