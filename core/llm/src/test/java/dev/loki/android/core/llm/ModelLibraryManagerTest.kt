package dev.loki.android.core.llm

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelLibraryManagerTest {
    private lateinit var root: File
    private lateinit var storage: ModelStorage
    private lateinit var registry: ModelRegistry
    private lateinit var runtime: FakeRuntime
    private lateinit var manager: ModelLibraryManager

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loki-model-library").toFile()
        storage = ModelStorage(root)
        registry = ModelRegistry(storage)
        runtime = FakeRuntime()
        manager = ModelLibraryManager(storage, registry, runtime)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `eject unloads but retains model`() = runBlocking {
        val model = addModel("one", ModelAvailability.LOADED)
        assertTrue(manager.eject())

        val result = registry.load()
        assertEquals(null, result.activeModelId)
        assertEquals(ModelAvailability.DOWNLOADED, result.models.single().availability)
        assertTrue(storage.artifactFile(model.id, model.artifactFileName).exists())
        assertEquals(listOf("unload:one"), runtime.events)
    }

    @Test
    fun `switch unloads old model and loads only selected model`() = runBlocking {
        addModel("one", ModelAvailability.LOADED)
        val second = addModel("two")

        assertTrue(manager.load(second.id))

        val result = registry.load()
        assertEquals(second.id, result.activeModelId)
        assertEquals(ModelAvailability.DOWNLOADED, result.models.first { it.id == "one" }.availability)
        assertEquals(ModelAvailability.LOADED, result.models.first { it.id == "two" }.availability)
        assertEquals(listOf("unload:one", "load:two"), runtime.events)
    }

    @Test
    fun `delete unloads loaded model and removes artifact`() = runBlocking {
        val model = addModel("one", ModelAvailability.LOADED)

        assertTrue(manager.delete(model.id))

        assertTrue(registry.load().models.isEmpty())
        assertFalse(storage.modelDirectory(model.id).exists())
        assertEquals(listOf("unload:one"), runtime.events)
    }

    private fun addModel(id: String, availability: ModelAvailability = ModelAvailability.DOWNLOADED): ModelRecord {
        val model = ModelRecord(
            id = id,
            displayName = id,
            runtime = ModelRuntime.LLAMA_CPP,
            format = ModelFormat.GGUF,
            artifactPath = "models/$id/model.gguf",
            artifactFileName = "model.gguf",
            sizeBytes = 4,
            source = ModelSource.LOCAL_IMPORT,
            availability = availability,
            importedAtEpochMs = 1L
        )
        storage.artifactFile(id, model.artifactFileName).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val existing = if (storage.manifestFile.exists()) registry.load() else ModelManifest()
        registry.save(
            existing.copy(
                activeModelId = if (availability == ModelAvailability.LOADED) id else existing.activeModelId,
                models = existing.models + model
            )
        )
        manager = ModelLibraryManager(storage, registry, runtime)
        return model
    }

    private class FakeRuntime : ModelRuntimeController {
        val events = mutableListOf<String>()
        override suspend fun load(model: ModelRecord): Boolean {
            events += "load:${model.id}"
            return true
        }
        override suspend fun unload(model: ModelRecord) {
            events += "unload:${model.id}"
        }
    }
}
