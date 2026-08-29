package dev.loki.android.core.voice.stt

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavEncoderTest {

    @Test
    fun `pcmFloatsToWav produces valid 44-byte RIFF header`() {
        val sampleCount = 1600 // 100ms at 16kHz
        val samples = FloatArray(sampleCount) { 0.5f }

        val wav = WavEncoder.pcmFloatsToWav(samples)

        assertEquals(44 + sampleCount * 2, wav.size)

        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        val riff = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        assertEquals("RIFF", riff)
        val riffSize = buffer.getInt()
        assertEquals(wav.size - 8, riffSize)
        val wave = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        assertEquals("WAVE", wave)

        // fmt chunk
        val fmt = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        assertEquals("fmt ", fmt)
        val subchunk1Size = buffer.getInt()
        assertEquals(16, subchunk1Size)
        val audioFormat = buffer.getShort()
        assertEquals(1.toShort(), audioFormat) // PCM
        val numChannels = buffer.getShort()
        assertEquals(1.toShort(), numChannels) // Mono
        val sampleRate = buffer.getInt()
        assertEquals(16000, sampleRate)
        val byteRate = buffer.getInt()
        assertEquals(32000, byteRate)
        val blockAlign = buffer.getShort()
        assertEquals(2.toShort(), blockAlign)
        val bitsPerSample = buffer.getShort()
        assertEquals(16.toShort(), bitsPerSample)

        // data chunk
        val data = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        assertEquals("data", data)
        val dataSize = buffer.getInt()
        assertEquals(sampleCount * 2, dataSize)

        // Verify first sample conversion: 0.5f * 32767 ≈ 16383
        val firstSample = buffer.getShort()
        assertTrue(firstSample in 16380..16385)
    }

    @Test
    fun `pcmFloatsToWav caps recording at 30 seconds`() {
        val overLimitSamples = FloatArray(16000 * 35) // 35 seconds
        val wav = WavEncoder.pcmFloatsToWav(overLimitSamples)

        val maxAllowedSamples = 16000 * 30 // 480,000 samples
        val expectedSize = 44 + maxAllowedSamples * 2
        assertEquals(expectedSize, wav.size)

        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(40)
        val dataSize = buffer.getInt()
        assertEquals(maxAllowedSamples * 2, dataSize)
    }

    @Test
    fun `pcmBytesToWav packages raw PCM bytes correctly`() {
        val rawBytes = ByteArray(3200) { 1 } // 1600 samples
        val wav = WavEncoder.pcmBytesToWav(rawBytes)

        assertEquals(44 + 3200, wav.size)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        assertEquals("RIFF", riff)
    }
}
