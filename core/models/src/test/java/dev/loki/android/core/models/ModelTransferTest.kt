package dev.loki.android.core.models

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelTransferTest {
    private lateinit var root: File
    private lateinit var transfer: ModelTransfer

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loki-model-transfer").toFile()
        transfer = ModelTransfer()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `copy verifies checksum and finalizes atomically`() = runBlocking {
        val part = File(root, "model.litertlm.part")
        val final = File(root, "model.litertlm")
        val result = transfer.copyToPart(
            ByteArrayInputStream("model-data".toByteArray()),
            part,
            expectedSizeBytes = 10,
            expectedSha256 = "a7f0d2f7c7f8f1e0b0e1e2e1b8c59c6c5b9f88f4c2b3d4e5f6a7b8c9d0e1f2a3"
        )

        assertTrue(result is TransferResult.Rejected)
        assertFalse(part.exists())
        assertFalse(final.exists())
    }

    @Test
    fun `successful copy reports bytes and final file`() = runBlocking {
        val bytes = "model-data".toByteArray()
        val part = File(root, "model.litertlm.part")
        val final = File(root, "model.litertlm")
        val result = transfer.copyToPart(ByteArrayInputStream(bytes), part, expectedSizeBytes = bytes.size.toLong())

        assertTrue(result is TransferResult.Completed)
        assertEquals(bytes.size.toLong(), (result as TransferResult.Completed).bytesCopied)
        transfer.finalizePart(part, final)
        assertTrue(final.isFile)
        assertFalse(part.exists())
    }

    @Test
    fun `litert detector detects litertlm extension`() {
        val file = File(root, "qwen3.litertlm")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        val result = LiteRtModelDetector().detect(file)

        assertEquals(ModelFormat.LITERT_MODEL, (result as ModelDetection.Detected).format)
    }
}
