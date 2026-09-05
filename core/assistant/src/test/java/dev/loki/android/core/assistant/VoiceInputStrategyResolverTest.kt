package dev.loki.android.core.assistant

import dev.loki.android.core.models.MetadataConfidence
import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelLibraryManager
import dev.loki.android.core.models.ModelManifest
import dev.loki.android.core.models.ModelMetadataField
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRecordCapabilities
import dev.loki.android.core.models.ModelRegistry
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelSource
import dev.loki.android.core.models.ModelStorage
import dev.loki.android.core.voice.stt.SttEngine
import dev.loki.android.core.voice.stt.SttEvent
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputStrategyResolverTest {

    private val resolver = VoiceInputStrategyResolver()

    private class FakeSttEngine : SttEngine {
        override val isListening: Boolean = false
        override fun startListening(language: String): Flow<SttEvent> = emptyFlow()
        override fun stopListening() {}
        override fun cancel() {}
        override fun release() {}
        override suspend fun transcribeAudio(pcmAudio: FloatArray, language: String): String = ""
    }

    @Test
    fun `audio-capable record resolves to DirectAudio`() {
        val tempDir = Files.createTempDirectory("resolver_test_1").toFile()
        val storage = ModelStorage(tempDir)
        val registry = ModelRegistry(storage)

        storage.artifactFile("audio-model", "model.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val audioModel = ModelRecord(
            id = "audio-model",
            displayName = "Audio Model",
            runtime = ModelRuntime.LITERT_LM,
            format = ModelFormat.LITERT_MODEL,
            availability = ModelAvailability.LOADED,
            artifacts = listOf(ModelArtifact("model.bin", "model.bin", 100L, url = "")),
            source = ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = ModelRecordCapabilities(
                audioInput = ModelMetadataField(value = true, confidence = MetadataConfidence.VERIFIED)
            )
        )

        registry.save(
            ModelManifest(
                activeModels = mapOf(ModelRuntime.LITERT_LM to audioModel.id),
                models = listOf(audioModel)
            )
        )

        val manager = ModelLibraryManager(storage, registry)
        manager.registerReadinessProvider(ModelRuntime.LITERT_LM) { true }

        val result = resolver.resolve(manager, sttEngine = null)
        assertTrue(result is VoiceInputStrategyResult.DirectAudio)

        tempDir.deleteRecursively()
    }

    @Test
    fun `text-only model with STT ready resolves to SttTranscribe`() {
        val tempDir = Files.createTempDirectory("resolver_test_2").toFile()
        val storage = ModelStorage(tempDir)
        val registry = ModelRegistry(storage)

        storage.artifactFile("text-model", "model.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        storage.artifactFile("whisper-asr", "whisper.tflite").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }

        val textModel = ModelRecord(
            id = "text-model",
            displayName = "Text Model",
            runtime = ModelRuntime.LITERT_LM,
            format = ModelFormat.LITERT_MODEL,
            availability = ModelAvailability.LOADED,
            artifacts = listOf(ModelArtifact("model.bin", "model.bin", 100L, url = "")),
            source = ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = ModelRecordCapabilities()
        )
        val asrModel = ModelRecord(
            id = "whisper-asr",
            displayName = "Whisper ASR",
            runtime = ModelRuntime.LITERT_ASR,
            format = ModelFormat.TFLITE,
            availability = ModelAvailability.LOADED,
            artifacts = listOf(ModelArtifact("whisper.tflite", "whisper.tflite", 200L, url = "")),
            source = ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L
        )

        registry.save(
            ModelManifest(
                activeModels = mapOf(
                    ModelRuntime.LITERT_LM to textModel.id,
                    ModelRuntime.LITERT_ASR to asrModel.id
                ),
                models = listOf(textModel, asrModel)
            )
        )

        val manager = ModelLibraryManager(storage, registry)
        manager.registerReadinessProvider(ModelRuntime.LITERT_LM) { true }
        manager.registerReadinessProvider(ModelRuntime.LITERT_ASR) { true }

        val fakeStt = FakeSttEngine()
        val result = resolver.resolve(manager, sttEngine = fakeStt)
        assertTrue(result is VoiceInputStrategyResult.SttTranscribe)

        tempDir.deleteRecursively()
    }

    @Test
    fun `text-only model with STT not ready resolves to Unavailable STT_NOT_READY`() {
        val tempDir = Files.createTempDirectory("resolver_test_3").toFile()
        val storage = ModelStorage(tempDir)
        val registry = ModelRegistry(storage)

        storage.artifactFile("text-model", "model.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val textModel = ModelRecord(
            id = "text-model",
            displayName = "Text Model",
            runtime = ModelRuntime.LITERT_LM,
            format = ModelFormat.LITERT_MODEL,
            availability = ModelAvailability.LOADED,
            artifacts = listOf(ModelArtifact("model.bin", "model.bin", 100L, url = "")),
            source = ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = ModelRecordCapabilities()
        )

        registry.save(
            ModelManifest(
                activeModels = mapOf(ModelRuntime.LITERT_LM to textModel.id),
                models = listOf(textModel)
            )
        )

        val manager = ModelLibraryManager(storage, registry)
        manager.registerReadinessProvider(ModelRuntime.LITERT_LM) { true }
        // LITERT_ASR is not in active models or not loaded

        val result = resolver.resolve(manager, sttEngine = null)
        assertTrue(result is VoiceInputStrategyResult.Unavailable)
        val unavailable = result as VoiceInputStrategyResult.Unavailable
        assertEquals(VoiceUnavailableReason.STT_NOT_READY, unavailable.reason)

        tempDir.deleteRecursively()
    }

    @Test
    fun `no active model resolves to Unavailable NO_ACTIVE_MODEL`() {
        val result = resolver.resolve(modelManager = null, sttEngine = null)
        assertTrue(result is VoiceInputStrategyResult.Unavailable)
        val unavailable = result as VoiceInputStrategyResult.Unavailable
        assertEquals(VoiceUnavailableReason.NO_ACTIVE_MODEL, unavailable.reason)
    }

    @Test
    fun `ungranted microphone permission resolves to Unavailable AUDIO_PERMISSION_DENIED`() {
        val result = resolver.resolve(modelManager = null, sttEngine = null, isRecordAudioGranted = false)
        assertTrue(result is VoiceInputStrategyResult.Unavailable)
        val unavailable = result as VoiceInputStrategyResult.Unavailable
        assertEquals(VoiceUnavailableReason.AUDIO_PERMISSION_DENIED, unavailable.reason)
    }
}
