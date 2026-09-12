package dev.loki.android.core.voice.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEngineTest {

    class MockTtsEngine : TtsEngine {
        override var isSpeaking: Boolean = false
        override var isReady: Boolean = true
        var lastSpokenText: String? = null

        override fun speak(
            text: String,
            utteranceId: String,
            onStart: (() -> Unit)?,
            onDone: (() -> Unit)?,
            onError: ((String) -> Unit)?
        ) {
            lastSpokenText = text
            isSpeaking = true
            onStart?.invoke()
            isSpeaking = false
            onDone?.invoke()
        }

        override fun stop() {
            isSpeaking = false
        }

        override fun release() {
            isReady = false
            isSpeaking = false
        }
    }

    @Test
    fun `MockTtsEngine adheres to TtsEngine contract`() {
        val engine: TtsEngine = MockTtsEngine()
        assertTrue(engine.isReady)
        assertFalse(engine.isSpeaking)

        var started = false
        var completed = false
        engine.speak("Hello world", onStart = { started = true }, onDone = { completed = true })

        assertTrue(started)
        assertTrue(completed)
        assertFalse(engine.isSpeaking)

        engine.release()
        assertFalse(engine.isReady)
    }

    @Test
    fun `resolveLocale handles auto and null as default locale`() {
        val defaultLocale = java.util.Locale.getDefault()
        org.junit.Assert.assertEquals(defaultLocale, AndroidTtsEngine.resolveLocale(null))
        org.junit.Assert.assertEquals(defaultLocale, AndroidTtsEngine.resolveLocale("auto"))
        org.junit.Assert.assertEquals(defaultLocale, AndroidTtsEngine.resolveLocale(""))
        org.junit.Assert.assertEquals(defaultLocale, AndroidTtsEngine.resolveLocale("   "))
    }

    @Test
    fun `resolveLocale resolves BCP-47 tags accurately`() {
        org.junit.Assert.assertEquals(java.util.Locale.forLanguageTag("hi"), AndroidTtsEngine.resolveLocale("hi"))
        org.junit.Assert.assertEquals(java.util.Locale.forLanguageTag("es"), AndroidTtsEngine.resolveLocale("es"))
        org.junit.Assert.assertEquals(java.util.Locale.forLanguageTag("fr"), AndroidTtsEngine.resolveLocale("fr"))
        org.junit.Assert.assertEquals(java.util.Locale.forLanguageTag("en-US"), AndroidTtsEngine.resolveLocale("en-US"))
    }

    @Test
    fun `detectScriptLocale accurately detects non-Latin scripts`() {
        // Devanagari (Hindi)
        org.junit.Assert.assertEquals(java.util.Locale("hi", "IN"), AndroidTtsEngine.detectScriptLocale("नमस्ते, आप कैसे हैं?"))
        // Arabic
        org.junit.Assert.assertEquals(java.util.Locale("ar"), AndroidTtsEngine.detectScriptLocale("مرحبا كيف حالك"))
        // Cyrillic (Russian)
        org.junit.Assert.assertEquals(java.util.Locale("ru", "RU"), AndroidTtsEngine.detectScriptLocale("Привет, как дела?"))
        // Japanese
        org.junit.Assert.assertEquals(java.util.Locale.JAPANESE, AndroidTtsEngine.detectScriptLocale("こんにちは"))
        // Korean
        org.junit.Assert.assertEquals(java.util.Locale.KOREAN, AndroidTtsEngine.detectScriptLocale("안녕하세요"))
        // Chinese
        org.junit.Assert.assertEquals(java.util.Locale.SIMPLIFIED_CHINESE, AndroidTtsEngine.detectScriptLocale("打开设置"))
    }

    @Test
    fun `detectScriptLocale detects Latin languages with distinctive markers`() {
        org.junit.Assert.assertEquals(java.util.Locale("es", "ES"), AndroidTtsEngine.detectScriptLocale("¿Cómo estás?"))
        org.junit.Assert.assertEquals(java.util.Locale("es", "ES"), AndroidTtsEngine.detectScriptLocale("Abriendo YouTube, señor"))
        org.junit.Assert.assertEquals(java.util.Locale("de", "DE"), AndroidTtsEngine.detectScriptLocale("Bitte schön"))
        org.junit.Assert.assertEquals(java.util.Locale("fr", "FR"), AndroidTtsEngine.detectScriptLocale("C'est ça"))
        // Plain English text returns null (so caller falls back to resolveLocale/default)
        org.junit.Assert.assertNull(AndroidTtsEngine.detectScriptLocale("Opening YouTube now."))
        org.junit.Assert.assertNull(AndroidTtsEngine.detectScriptLocale(""))
    }
}

