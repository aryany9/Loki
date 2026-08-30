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
}
