package dev.loki.android.core.voice.stt

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Minimal NPZ (NumPy zip) loader for the Whisper mel filter bank.
 *
 * The `mel_filters.npz` asset contains a single array `mel_80` of shape [80, 201],
 * stored as a raw numpy `.npy` file inside the zip archive. Each element is a
 * little-endian float32.
 */
object NpzLoader {

    /**
     * Parses [npzBytes] as a numpy `.npz` archive and returns the mel filter matrix
     * as `[melBins][halfFftBins]` (float32). Expects a `mel_80.npy` or `mel_80` entry.
     */
    fun loadMelFilters(npzBytes: ByteArray, melBins: Int): Array<FloatArray> {
        ZipInputStream(ByteArrayInputStream(npzBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("mel_$melBins")) {
                    val data = zip.readBytes()
                    return parseNpy(data, melBins)
                }
                entry = zip.nextEntry
            }
        }
        error("mel_$melBins array not found in mel_filters.npz")
    }

    /**
     * Parses a raw numpy `.npy` v1.0 buffer and returns the float32 data as
     * a [rows][cols] 2-D array. Skips the npy header (magic + version + header_len + header).
     */
    private fun parseNpy(data: ByteArray, rows: Int): Array<FloatArray> {
        // NPY format: 6-byte magic (\x93NUMPY), 2-byte version (1.0), 2-byte header_len, then header
        val magicLen = 6
        val versionLen = 2
        val headerLenOffset = magicLen + versionLen
        val headerLen = ByteBuffer.wrap(data, headerLenOffset, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val dataOffset = headerLenOffset + 2 + headerLen

        val floatBuf = ByteBuffer.wrap(data, dataOffset, data.size - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        val cols = floatBuf.remaining() / rows
        return Array(rows) { r ->
            FloatArray(cols) { floatBuf.get(r * cols + it) }
        }
    }
}
