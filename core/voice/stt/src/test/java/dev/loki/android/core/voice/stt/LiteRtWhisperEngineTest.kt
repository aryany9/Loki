package dev.loki.android.core.voice.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtWhisperEngineTest {

    @Test
    fun `LiteRtWhisperEngine initializes successfully in default mode`() {
        val engine = LiteRtWhisperEngine()
        val initialized = engine.initialize()
        assertTrue(initialized)
        assertFalse(engine.isListening)
        engine.release()
    }

    @Test
    fun `LiteRtWhisperEngine cancel and release contract`() {
        val engine = LiteRtWhisperEngine()
        engine.initialize()
        engine.cancel()
        assertFalse(engine.isListening)
        engine.release()
        assertFalse(engine.isListening)
    }
}
