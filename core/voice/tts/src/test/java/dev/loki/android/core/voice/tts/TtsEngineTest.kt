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
}
