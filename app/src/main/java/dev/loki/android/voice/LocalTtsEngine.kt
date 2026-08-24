package dev.loki.android.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * LocalTtsEngine wraps Android TextToSpeech for offline voice synthesis.
 */
class LocalTtsEngine(
    context: Context,
    private val onInitComplete: ((Boolean) -> Unit)? = null
) : AutoCloseable {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isInitialized = true
                Log.i(TAG, "Local TextToSpeech initialized successfully")
                onInitComplete?.invoke(true)
            } else {
                Log.e(TAG, "Failed to initialize TextToSpeech (status: $status)")
                onInitComplete?.invoke(false)
            }
        }
    }

    fun isReady(): Boolean = isInitialized

    fun speak(
        text: String,
        utteranceId: String = "loki_${System.currentTimeMillis()}",
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (!isInitialized || tts == null) {
            Log.e(TAG, "TTS not ready to speak")
            onError?.invoke("TTS not initialized")
            return
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.i(TAG, "TTS playback started for utterance $id")
                onStart?.invoke()
            }

            override fun onDone(id: String?) {
                Log.i(TAG, "TTS playback finished for utterance $id")
                onDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                Log.e(TAG, "TTS playback error for utterance $id")
                onError?.invoke("TTS error")
            }
        })

        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    override fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "LocalTtsEngine"
    }
}
