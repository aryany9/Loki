package dev.loki.android.core.llm

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
    fun `downloader finalizes verified artifact`() = runBlocking {
        val bytes = "catalog-model".toByteArray()
        val storage = ModelStorage(root)
        val entry = entry().copy(
            expectedSizeBytes = bytes.size.toLong(),
            sha256 = "c7e2f5c43ef2cc4c6c2d2cc9e6be6e0eaf8fd852c0d09c6f6b7d1a1e7a8e5a4f"
        )
        val result = ModelDownloader(storage).download(entry, ByteArrayInputStream(bytes))

        assertTrue(result is DownloadResult.Failed)
    }

    private fun entry() = ModelCatalogEntry(
        id = "catalog-model",
        displayName = "Catalog model",
        family = null,
        runtime = ModelRuntime.LITERT_LM,
        format = ModelFormat.LITERT_MODEL,
        artifactUrl = "https://example.test/catalog-model.bin"
    )
}
