package dev.loki.android.core.llm

import dev.loki.android.core.models.DownloadResult
import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelCatalog
import dev.loki.android.core.models.ModelCatalogEntry
import dev.loki.android.core.models.ModelDownloader
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelStorage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelCatalogTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loki-catalog").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `catalog round trips with runtime and format`() {
        val catalog = ModelCatalog(models = listOf(entry()))
        val encoded = Json.encodeToString(ModelCatalog.serializer(), catalog)

        val decoded = Json.decodeFromString<ModelCatalog>(encoded)

        assertEquals(ModelRuntime.LITERT_LM, decoded.models.single().runtime)
        assertEquals(ModelFormat.LITERT_MODEL, decoded.models.single().format)
    }
    @Test
    fun `catalog parsing accepts records with multiple variants and preserves integrity metadata`() {
        val json = """
            {
              "schemaVersion": 1,
              "models": [
                {
                  "id": "whisper-variant-test",
                  "displayName": "Whisper Tiny (ASR)",
                  "runtime": "LITERT_ASR",
                  "format": "TFLITE",
                  "artifacts": [
                    {
                      "fileName": "whisper_tiny_30s_i8.tflite",
                      "relativePath": "whisper_tiny_30s_i8.tflite",
                      "sizeBytes": 41116288,
                      "sha256": "6748ac565a228c4a00b18d11ea1e2fd7cead3db6fba94e3f0bf35756b13ba4a9",
                      "url": "https://example.com/i8.tflite",
                      "variant": "quantized"
                    },
                    {
                      "fileName": "whisper_tiny_30s_f32.tflite",
                      "relativePath": "whisper_tiny_30s_f32.tflite",
                      "sizeBytes": 150979184,
                      "sha256": "0c8f0e2a1855909a0c027b4ac3c586fdd299e2b47bf1a4fdab51191bca1e0e89",
                      "url": "https://example.com/f32.tflite",
                      "variant": "full-precision"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = Json.decodeFromString<ModelCatalog>(json)
        val model = decoded.models.single()
        assertEquals("whisper-variant-test", model.id)
        assertEquals(2, model.artifacts.size)

        val i8 = model.artifacts.find { it.variant == "quantized" }!!
        assertEquals("6748ac565a228c4a00b18d11ea1e2fd7cead3db6fba94e3f0bf35756b13ba4a9", i8.sha256)

        val f32 = model.artifacts.find { it.variant == "full-precision" }!!
        assertEquals("0c8f0e2a1855909a0c027b4ac3c586fdd299e2b47bf1a4fdab51191bca1e0e89", f32.sha256)
    }
    @Test
    fun `downloader finalizes verified artifact`() = runBlocking {
        val bytes = "catalog-model".toByteArray()
        val storage = ModelStorage(root)
        val artifact = ModelArtifact(
            fileName = "catalog-model.bin",
            relativePath = "catalog-model.bin",
            sizeBytes = bytes.size.toLong(),
            sha256 = "842a3f32416c236b918668f2bb3713115373c8a2181f32f29941d7aab3051e83",
            url = "https://example.test/catalog-model.bin"
        )
        val result = ModelDownloader(storage).downloadArtifact("catalog-model", artifact, ByteArrayInputStream(bytes))

        assertTrue(result is DownloadResult.Completed)
    }

    private fun entry() = ModelCatalogEntry(
        id = "catalog-model",
        displayName = "Catalog model",
        family = null,
        runtime = ModelRuntime.LITERT_LM,
        format = ModelFormat.LITERT_MODEL,
        artifacts = listOf(
            ModelArtifact(
                fileName = "catalog-model.bin",
                relativePath = "catalog-model.bin",
                sizeBytes = 100L,
                url = "https://example.test/catalog-model.bin"
            )
        )
    )
}
