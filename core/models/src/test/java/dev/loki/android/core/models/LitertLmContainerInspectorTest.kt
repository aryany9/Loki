package dev.loki.android.core.models

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LitertLmContainerInspectorTest {

    @Test
    fun `detects audio and vision capabilities from litertlm header sections`() {
        val headerText = "LITERTLM\u0001\u0000\u0000\u0000tf_lite_prefill_decode\u0000tf_lite_audio_encoder_hw\u0000tf_lite_audio_adapter\u0000tf_lite_vision_encoder\u0000"
        val bytes = headerText.toByteArray(Charsets.US_ASCII)

        val info = LitertLmContainerInspector.inspectBytes(bytes)

        assertTrue(info.isLitertLmContainer)
        assertTrue(info.supportsAudioInput)
        assertTrue(info.supportsVisionInput)
    }

    @Test
    fun `detects text-only model when audio and vision sections are absent`() {
        val headerText = "LITERTLM\u0001\u0000\u0000\u0000tf_lite_prefill_decode\u0000tf_lite_per_layer_embedder\u0000"
        val bytes = headerText.toByteArray(Charsets.US_ASCII)

        val info = LitertLmContainerInspector.inspectBytes(bytes)

        assertTrue(info.isLitertLmContainer)
        assertFalse(info.supportsAudioInput)
        assertFalse(info.supportsVisionInput)
    }

    @Test
    fun `returns unknown for non-litertlm files or invalid magic`() {
        val fakeTflite = "TFL3\u0000\u0000\u0000tf_lite_audio_encoder_hw".toByteArray(Charsets.US_ASCII)
        val info = LitertLmContainerInspector.inspectBytes(fakeTflite)

        assertFalse(info.isLitertLmContainer)
        assertFalse(info.supportsAudioInput)
        assertFalse(info.supportsVisionInput)

        val emptyInfo = LitertLmContainerInspector.inspectBytes(ByteArray(0))
        assertFalse(emptyInfo.isLitertLmContainer)
    }

    @Test
    fun `inspects file directly from disk`() {
        val tempFile = Files.createTempFile("test-model", ".litertlm").toFile()
        try {
            val content = "LITERTLM\u0000\u0000tf_lite_audio_adapter\u0000end_of_audio".toByteArray(Charsets.US_ASCII)
            tempFile.writeBytes(content)

            val info = LitertLmContainerInspector.inspect(tempFile)

            assertTrue(info.isLitertLmContainer)
            assertTrue(info.supportsAudioInput)
            assertFalse(info.supportsVisionInput)
        } finally {
            tempFile.delete()
        }
    }
}
