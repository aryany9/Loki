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
    private var pendingLanguageTag: String? = null
    var currentLocale: Locale? = null
        private set

    override var isReady: Boolean = false
        private set
    override var isSpeaking: Boolean = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                configureLanguage(pendingLanguageTag)
                Log.i(TAG, "Android TextToSpeech initialized successfully with locale $currentLocale")
                onInitComplete?.invoke(true)
            } else {
                Log.e(TAG, "Failed to initialize Android TextToSpeech (status: $status)")
                onInitComplete?.invoke(false)
            }
        }
    }

    override fun configureLanguage(bcp47Tag: String?) {
        pendingLanguageTag = bcp47Tag
        val targetLocale = resolveLocale(bcp47Tag)
        currentLocale = targetLocale

        tts?.let { engine ->
            try {
                val result = engine.setLanguage(targetLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS language '$targetLocale' not supported or missing data (code $result), falling back to default voice")
                } else {
                    Log.i(TAG, "TTS configured language: $targetLocale")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Exception configuring TTS language '$targetLocale'", e)
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

        val isAutoLanguage = pendingLanguageTag == null || pendingLanguageTag.equals("auto", ignoreCase = true)
        val targetLocale = if (isAutoLanguage) {
            detectScriptLocale(text) ?: resolveLocale(pendingLanguageTag)
        } else {
            resolveLocale(pendingLanguageTag)
        }
        try {
            tts?.setLanguage(targetLocale)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to set locale $targetLocale", e)
        }

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

        fun resolveLocale(bcp47Tag: String?): Locale {
            return if (bcp47Tag.isNullOrBlank() || bcp47Tag.equals("auto", ignoreCase = true)) {
                Locale.getDefault()
            } else {
                Locale.forLanguageTag(bcp47Tag)
            }
        }

        fun detectScriptLocale(text: String): Locale? {
            if (text.isBlank()) return null

            // 1. Devanagari (Hindi)
            if (text.any { it in '\u0900'..'\u097F' }) {
                return Locale("hi", "IN")
            }
            // 2. Arabic
            if (text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }) {
                return Locale("ar")
            }
            // 3. Cyrillic (Russian)
            if (text.any { it in '\u0400'..'\u04FF' }) {
                return Locale("ru", "RU")
            }
            // 4. Japanese (Hiragana & Katakana)
            if (text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }) {
                return Locale.JAPANESE
            }
            // 5. Korean (Hangul)
            if (text.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }) {
                return Locale.KOREAN
            }
            // 6. CJK Unified Ideographs (Chinese)
            if (text.any { it in '\u4E00'..'\u9FFF' }) {
                return Locale.SIMPLIFIED_CHINESE
            }
            // 7. Other Indic scripts
            if (text.any { it in '\u0980'..'\u09FF' }) return Locale("bn", "BD") // Bengali
            if (text.any { it in '\u0B80'..'\u0BFF' }) return Locale("ta", "IN") // Tamil
            if (text.any { it in '\u0C00'..'\u0C7F' }) return Locale("te", "IN") // Telugu
            if (text.any { it in '\u0A80'..'\u0AFF' }) return Locale("gu", "IN") // Gujarati
            if (text.any { it in '\u0C80'..'\u0CFF' }) return Locale("kn", "IN") // Kannada
            if (text.any { it in '\u0D00'..'\u0D7F' }) return Locale("ml", "IN") // Malayalam

            // 8. Latin with distinctive language markers
            if (text.any { it in "¡¿ñÑ" }) {
                return Locale("es", "ES")
            }
            if (text.any { it in "äöüßÄÖÜ" }) {
                return Locale("de", "DE")
            }
            if (text.any { it in "çœæ" }) {
                return Locale("fr", "FR")
            }

            return null
        }
    }
}
