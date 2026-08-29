package dev.loki.android.core.voice.stt

import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelSource
import dev.loki.android.core.models.ModelStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteRtWhisperEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun makeStorage() = ModelStorage(tempFolder.root)

    private fun makeEngine(storage: ModelStorage = makeStorage()) = LiteRtWhisperEngine(storage)

    private fun makeModelRecord(modelId: String, artifactName: String): ModelRecord = ModelRecord(
        id = modelId,
        displayName = "Test Whisper",
        runtime = ModelRuntime.LITERT_ASR,
        format = ModelFormat.TFLITE,
        artifacts = listOf(
            ModelArtifact(
                fileName = artifactName,
                relativePath = artifactName,
                sizeBytes = 0L,
                sha256 = null,
                url = ""
            )
        ),
        source = ModelSource.LOCAL_IMPORT,
        availability = ModelAvailability.DOWNLOADED,
        importedAtEpochMs = 0L
    )

    @Test
    fun `initialize with valid path succeeds`() {
        val storage = makeStorage()
        val engine = makeEngine(storage)
        val file = tempFolder.newFile("test_model.tflite")
        assertTrue(engine.initialize(file.absolutePath))
        assertFalse(engine.isListening)
        engine.release()
    }

    @Test
    fun `initialize with nonexistent path returns false`() {
        val engine = makeEngine()
        assertFalse(engine.initialize("/nonexistent/path/model.tflite"))
    }

    @Test
    fun `load returns true when artifact file exists on disk`() = runTest {
        val storage = makeStorage()
        val engine = makeEngine(storage)
        val modelId = "test-model"
        val artifactName = "whisper_tiny_30s_f32.tflite"

        // Create the file at the storage-managed path
        val modelDir = storage.modelDirectory(modelId)
        modelDir.mkdirs()
        val artifactFile = storage.artifactFile(modelId, artifactName)
        artifactFile.createNewFile()

        val model = makeModelRecord(modelId, artifactName)
        assertTrue(engine.load(model))
        engine.release()
    }

    @Test
    fun `load returns false when artifact file is missing on disk`() = runTest {
        val storage = makeStorage()
        val engine = makeEngine(storage)
        val model = makeModelRecord("missing-model", "whisper_tiny_30s_f32.tflite")
        // No file created — storage path will not exist
        assertFalse(engine.load(model))
    }

    @Test
    fun `load returns false when record has no tflite artifact`() = runTest {
        val storage = makeStorage()
        val engine = makeEngine(storage)
        val model = makeModelRecord("model-id", "some_other_file.bin")
        // .bin is not .tflite — should return false immediately
        assertFalse(engine.load(model))
    }

    @Test
    fun `cancel and release contract`() {
        val engine = makeEngine()
        engine.cancel()
        assertFalse(engine.isListening)
        engine.release()
        assertFalse(engine.isListening)
    }
}
