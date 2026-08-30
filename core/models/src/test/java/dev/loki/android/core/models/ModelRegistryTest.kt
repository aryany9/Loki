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

    @Test
    fun `manifest round trips model capabilities with confidence tags`() {
        val audioModel = modelRecord().copy(
            id = "audio-model",
            capabilities = ModelRecordCapabilities(
                audioInput = ModelMetadataField(value = true, confidence = MetadataConfidence.USER_CONFIRMED)
            )
        )
        val textModel = modelRecord().copy(
            id = "text-model",
            capabilities = ModelRecordCapabilities()
        )

        val manifest = ModelManifest(models = listOf(audioModel, textModel))
        registry.save(manifest)

        val loaded = registry.load()
        val loadedAudio = loaded.models.first { it.id == "audio-model" }
        val loadedText = loaded.models.first { it.id == "text-model" }

        assertTrue(loadedAudio.capabilities.isAudioInputSupported)
        assertEquals(MetadataConfidence.USER_CONFIRMED, loadedAudio.capabilities.audioInput.confidence)
        assertEquals(true, loadedAudio.capabilities.audioInput.value)

        assertEquals(false, loadedText.capabilities.isAudioInputSupported)
        assertEquals(MetadataConfidence.UNKNOWN, loadedText.capabilities.audioInput.confidence)
    }

    @Test
    fun `default capabilities decode as text-only when audio is false or unknown`() {
        val defaultCaps = ModelRecordCapabilities()
        assertEquals(false, defaultCaps.isAudioInputSupported)

        val unconfirmedAudio = ModelRecordCapabilities(
            audioInput = ModelMetadataField(value = true, confidence = MetadataConfidence.UNKNOWN)
        )
        assertEquals(false, unconfirmedAudio.isAudioInputSupported)

        val hintAudio = ModelRecordCapabilities(
            audioInput = ModelMetadataField(value = true, confidence = MetadataConfidence.HINT)
        )
        assertEquals(false, hintAudio.isAudioInputSupported)

        val verifiedAudio = ModelRecordCapabilities(
            audioInput = ModelMetadataField(value = true, confidence = MetadataConfidence.VERIFIED)
        )
        assertTrue(verifiedAudio.isAudioInputSupported)
    }

    @Test
    fun `reconcile structurally detects audio capability from container header`() {
        val model = modelRecord(availability = ModelAvailability.LOADED).copy(
            id = "arbitrary-name-123",
            displayName = "Custom-LLM",
            artifacts = listOf(
                ModelArtifact(
                    fileName = "custom.litertlm",
                    relativePath = "custom.litertlm",
                    sizeBytes = 100,
                    url = ""
                )
            ),
            capabilities = ModelRecordCapabilities() // defaults to false / UNKNOWN
        )

        // Write a valid .litertlm header with audio section markers
        storage.artifactFile(model.id, "custom.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes("LITERTLM\u0000\u0000tf_lite_audio_encoder_hw\u0000tf_lite_audio_adapter".toByteArray(Charsets.US_ASCII))
        }

        registry.save(ModelManifest(
            activeModels = mapOf(ModelRuntime.LITERT_LM to model.id),
            models = listOf(model)
        ))

        val reconciled = registry.reconcile()
        val reconciledModel = reconciled.models.single()

        assertTrue(reconciledModel.capabilities.isAudioInputSupported)
        assertEquals(MetadataConfidence.VERIFIED, reconciledModel.capabilities.audioInput.confidence)
        assertEquals(true, reconciledModel.capabilities.audioInput.value)
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
