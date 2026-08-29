package dev.loki.android.core.models

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelRegistryTest {
    private lateinit var root: File
    private lateinit var storage: ModelStorage
    private lateinit var registry: ModelRegistry

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loki-model-registry").toFile()
        storage = ModelStorage(root)
        registry = ModelRegistry(storage)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `manifest round trips and restores active models`() {
        val model = modelRecord()
        storage.artifactFile(model.id, model.artifacts.first().relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val manifest = ModelManifest(
            activeModels = mapOf(ModelRuntime.LITERT_LM to model.id),
            models = listOf(model)
        )

        registry.save(manifest)

        assertEquals(manifest, registry.load())
        assertEquals(model.id, registry.reconcile().activeModels[ModelRuntime.LITERT_LM])
    }

    @Test
    fun `duplicate model IDs are rejected`() {
        val first = modelRecord()
        val second = first.copy(displayName = "Other")

        assertThrows(IllegalArgumentException::class.java) {
            registry.save(ModelManifest(models = listOf(first, second)))
        }
    }

    @Test
    fun `missing artifacts are reconciled as not downloaded and clear active IDs`() {
        val model = modelRecord(availability = ModelAvailability.LOADED)
        registry.save(ModelManifest(
            activeModels = mapOf(ModelRuntime.LITERT_LM to model.id),
            models = listOf(model)
        ))

        val reconciled = registry.reconcile()

        assertEquals(ModelAvailability.NOT_DOWNLOADED, reconciled.models.single().availability)
        assertTrue(reconciled.activeModels.isEmpty())
    }

    private fun modelRecord(
        availability: ModelAvailability = ModelAvailability.DOWNLOADED
    ) = ModelRecord(
        id = "model-1",
        displayName = "Test model",
        family = ModelMetadataField("Test family", MetadataConfidence.USER_CONFIRMED),
        runtime = ModelRuntime.LITERT_LM,
        format = ModelFormat.LITERT_MODEL,
        artifacts = listOf(
            ModelArtifact(
                fileName = "model.bin",
                relativePath = "model.bin",
                sizeBytes = 3,
                url = "http://example.com"
            )
        ),
        source = ModelSource.LOCAL_IMPORT,
        availability = availability,
        importedAtEpochMs = 1L
    )
}
