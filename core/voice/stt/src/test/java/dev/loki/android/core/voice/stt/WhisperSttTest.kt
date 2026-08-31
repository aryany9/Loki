package dev.loki.android.core.voice.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.sqrt

class WhisperSttTest {

    @Test
    fun `energy RMS calculation is correct for synthetic sine wave`() {
        val sampleRate = 16000
        val freq = 440.0 // A4 tone
        val amplitude = 10000.0 // ~30% full scale for 16-bit
        val numSamples = 1600 // 100ms chunk
        val samples = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            samples[i] = (amplitude * kotlin.math.sin(2.0 * Math.PI * freq * t)).toInt().toShort()
        }

        var sum = 0.0
        for (s in samples) {
            sum += s.toDouble() * s.toDouble()
        }
        val rms = sqrt(sum / numSamples).toFloat()

        // RMS of sine wave = Peak / sqrt(2) ~ 10000 / 1.4142 ~ 7071
        org.junit.Assert.assertTrue("RMS $rms should be > 5000", rms > 5000f)
    }

    @Test
    fun `energy RMS of pure silence is near zero`() {
        val samples = ShortArray(1600) { 0 }
        var sum = 0.0
        for (s in samples) {
            sum += s.toDouble() * s.toDouble()
        }
        val rms = sqrt(sum / samples.size).toFloat()
        org.junit.Assert.assertEquals(0.0f, rms, 0.001f)
    }

    @Test
    fun `SttEvent sealed interface contracts are defined`() {
        val start: SttEvent = SttEvent.ListeningStarted
        val amp: SttEvent = SttEvent.Amplitude(1234.5f)
        val partial: SttEvent = SttEvent.PartialResult("hello")
        val finalRes: SttEvent = SttEvent.FinalResult("hello world")
        val stopped: SttEvent = SttEvent.ListeningStopped
        val error: SttEvent = SttEvent.Error(RuntimeException("test error"))

        assertNotNull(start)
        assertNotNull(amp)
        org.junit.Assert.assertEquals(1234.5f, (amp as SttEvent.Amplitude).rms, 0.001f)
        assertNotNull(partial)
        assertNotNull(finalRes)
        assertNotNull(stopped)
        assertNotNull(error)
    }
}
