package dev.loki.android.core.voice.stt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for packaging raw 16 kHz PCM audio (floats in [-1.0, 1.0] or 16-bit PCM bytes)
 * into a standard 44-byte RIFF WAV byte array expected by multimodal models.
 *
 * Implements the 30-second recording cap (480,000 samples @ 16 kHz).
 */
object WavEncoder {

    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val MAX_RECORDING_SECONDS = 30
    const val MAX_SAMPLES = SAMPLE_RATE * MAX_RECORDING_SECONDS // 480,000

    /**
     * Converts a float array of 16 kHz mono PCM samples (values [-1.0, 1.0]) to a
     * standard 44-byte RIFF WAV byte array, capped at [MAX_SAMPLES] (30 s).
     */
    fun pcmFloatsToWav(pcmFloats: FloatArray): ByteArray {
        val sampleCount = minOf(pcmFloats.size, MAX_SAMPLES)
        val dataSize = sampleCount * 2
        val totalSize = 44 + dataSize

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize - 8)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt subchunk
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat 1 = PCM
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)) // ByteRate = 32000
        buffer.putShort((CHANNELS * (BITS_PER_SAMPLE / 8)).toShort()) // BlockAlign = 2
        buffer.putShort(BITS_PER_SAMPLE.toShort())

        // data subchunk
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        for (i in 0 until sampleCount) {
            val sample = (pcmFloats[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            buffer.putShort(sample)
        }

        return buffer.array()
    }

    /**
     * Packages raw 16-bit PCM bytes (already little-endian short samples) with a 44-byte WAV header.
     */
    fun pcmBytesToWav(pcmBytes: ByteArray): ByteArray {
        val maxBytes = MAX_SAMPLES * 2
        val dataSize = minOf(pcmBytes.size, maxBytes)
        val totalSize = 44 + dataSize

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize - 8)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8))
        buffer.putShort((CHANNELS * (BITS_PER_SAMPLE / 8)).toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())

        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        buffer.put(pcmBytes, 0, dataSize)

        return buffer.array()
    }
}
