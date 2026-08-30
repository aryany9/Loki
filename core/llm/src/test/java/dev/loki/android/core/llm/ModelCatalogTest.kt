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
