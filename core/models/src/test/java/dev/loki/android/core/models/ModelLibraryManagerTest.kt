package dev.loki.android.core.models

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
    private lateinit var manager: ModelLibraryManager
    private lateinit var lmRuntime: FakeRuntime
    private lateinit var asrRuntime: FakeRuntime

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loki-model-library").toFile()
        storage = ModelStorage(root)
        registry = ModelRegistry(storage)
        manager = ModelLibraryManager(storage, registry)
        lmRuntime = FakeRuntime()
        asrRuntime = FakeRuntime()
        manager.registerRuntime(ModelRuntime.LITERT_LM, lmRuntime)
        manager.registerRuntime(ModelRuntime.LITERT_ASR, asrRuntime)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `eject unloads but retains model`() = runBlocking {
        val model = addModel("one", ModelAvailability.LOADED)
        assertTrue(manager.eject(ModelRuntime.LITERT_LM))

        val result = registry.load()
        assertTrue(result.activeModels.isEmpty())
        assertEquals(ModelAvailability.DOWNLOADED, result.models.single().availability)
        assertTrue(storage.artifactFile(model.id, model.artifacts.first().relativePath).exists())
        assertEquals(listOf("unload:one"), lmRuntime.events)
    }

    @Test
    fun `switch unloads old model of same runtime and loads new`() = runBlocking {
        addModel("one", ModelAvailability.LOADED)
        val second = addModel("two")

        assertTrue(manager.load(second.id))

        val result = registry.load()
        assertEquals(second.id, result.activeModels[ModelRuntime.LITERT_LM])
        assertEquals(ModelAvailability.DOWNLOADED, result.models.first { it.id == "one" }.availability)
        assertEquals(ModelAvailability.LOADED, result.models.first { it.id == "two" }.availability)
        assertEquals(listOf("unload:one", "load:two"), lmRuntime.events)
    }

    @Test
    fun `simultaneous loading of different runtimes`() = runBlocking {
        val lm = addModel("lm-1", runtime = ModelRuntime.LITERT_LM)
        val asr = addModel("asr-1", runtime = ModelRuntime.LITERT_ASR)

        assertTrue(manager.load(lm.id))
        assertTrue(manager.load(asr.id))

        val result = registry.load()
        assertEquals("lm-1", result.activeModels[ModelRuntime.LITERT_LM])
        assertEquals("asr-1", result.activeModels[ModelRuntime.LITERT_ASR])
        assertEquals(ModelAvailability.LOADED, result.models.first { it.id == "lm-1" }.availability)
        assertEquals(ModelAvailability.LOADED, result.models.first { it.id == "asr-1" }.availability)
        assertEquals(listOf("load:lm-1"), lmRuntime.events)
        assertEquals(listOf("load:asr-1"), asrRuntime.events)
    }

    private fun addModel(
        id: String, 
        availability: ModelAvailability = ModelAvailability.DOWNLOADED,
        runtime: ModelRuntime = ModelRuntime.LITERT_LM
    ): ModelRecord {
        val model = ModelRecord(
            id = id,
            displayName = id,
            runtime = runtime,
            format = ModelFormat.LITERT_MODEL,
            artifacts = listOf(
                ModelArtifact(
                    fileName = "model.bin",
                    relativePath = "model.bin",
                    sizeBytes = 4,
                    url = "http://example.com"
                )
            ),
            source = ModelSource.LOCAL_IMPORT,
            availability = availability,
            importedAtEpochMs = 1L
        )
        storage.artifactFile(id, "model.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val existing = if (storage.manifestFile.exists()) registry.load() else ModelManifest()
        val activeModels = existing.activeModels.toMutableMap()
        if (availability == ModelAvailability.LOADED) {
            activeModels[runtime] = id
        }
        registry.save(
            existing.copy(
                activeModels = activeModels,
                models = existing.models + model
            )
        )
        manager = ModelLibraryManager(storage, registry)
        manager.registerRuntime(ModelRuntime.LITERT_LM, lmRuntime)
        manager.registerRuntime(ModelRuntime.LITERT_ASR, asrRuntime)
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
