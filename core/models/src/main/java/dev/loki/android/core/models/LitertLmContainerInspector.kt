package dev.loki.android.core.models

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Structural inspector for `.litertlm` (LiteRT-LM container) artifacts.
 *
 * A `.litertlm` file starts with the ASCII magic `LITERTLM` followed by a versioned
 * metadata table that enumerates the model components embedded in the container, e.g.:
 *
 *  - `tf_lite_prefill_decode`  — the core text LLM (always present)
 *  - `tf_lite_vision_encoder`, `tf_lite_vision_adapter`, `end_of_vision`
 *  - `tf_lite_audio_encoder_hw`, `tf_lite_audio_adapter`, `end_of_audio`
 *  - `tf_lite_per_layer_embedder`, `tf_lite_embedder`
 *
 * The table sits entirely within the first few kilobytes of the file, so audio/vision
 * support can be detected by reading a small prefix of the artifact — no model-name
 * heuristics, no full-file scan. Presence of the audio encoder/adapter sections means
 * the model accepts audio input (this is how `gemma-4-E4B-it` and other multimodal
 * LiteRT-LM exports declare their modalities).
 */
object LitertLmContainerInspector {

    private const val MAGIC = "LITERTLM"
    private const val HEADER_SCAN_BYTES = 64 * 1024

    /** Component section names that indicate native audio input support. */
    private val AUDIO_SECTION_MARKERS = listOf(
        "tf_lite_audio_encoder_hw",
        "tf_lite_audio_adapter"
    )

    /** Component section names that indicate native vision input support. */
    private val VISION_SECTION_MARKERS = listOf(
        "tf_lite_vision_encoder",
        "tf_lite_vision_adapter"
    )

    /** Structural facts about a `.litertlm` container. */
    data class Info(
        val isLitertLmContainer: Boolean,
        val supportsAudioInput: Boolean,
        val supportsVisionInput: Boolean
    )

    fun unknown() = Info(
        isLitertLmContainer = false,
        supportsAudioInput = false,
        supportsVisionInput = false
    )

    /**
     * Inspects [file] and reports which modalities the container declares.
     * Reads at most [HEADER_SCAN_BYTES] from the start of the file.
     */
    fun inspect(file: File): Info {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val scanLength = minOf(raf.length(), HEADER_SCAN_BYTES.toLong()).toInt()
                if (scanLength < MAGIC.length) return unknown()

                val buf = ByteArray(scanLength)
                raf.readFully(buf, 0, scanLength)
                inspectBytes(buf)
            }
        } catch (e: IOException) {
            unknown()
        }
    }

    /** Pure-bytes variant used by unit tests. */
    fun inspectBytes(bytes: ByteArray): Info {
        if (bytes.size < MAGIC.length || !bytes.startsWith(MAGIC.toByteArray(Charsets.US_ASCII))) {
            return unknown()
        }
        val header = bytes.toString(Charsets.US_ASCII)
        return Info(
            isLitertLmContainer = true,
            supportsAudioInput = AUDIO_SECTION_MARKERS.any { header.contains(it) },
            supportsVisionInput = VISION_SECTION_MARKERS.any { header.contains(it) }
        )
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
