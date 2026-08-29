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

/**
 * Unit tests for [LiteRtWhisperEngine].
 *
 * Note: [WhisperBridge.nativeInitWhisper] requires the native lokiwhisper .so to be present.
 * On the JVM (unit-test host) the native library is unavailable, so the engine will fail to
 * initialize via the native call — which is exactly the failure path exercised here.
 *
 * For a full "transcription succeeds" path, an instrumented test with a real device/emulator
 * and the GGUF model artifact is required (see 4.1–4.4 device validation tasks).
 */
class LiteRtWhisperEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun makeStorage() = ModelStorage(tempFolder.root)

    private fun makeEngine(storage: ModelStorage = makeStorage()) = LiteRtWhisperEngine(storage)

    private fun makeModelRecord(
        modelId: String,
        artifactName: String,
        availability: ModelAvailability = ModelAvailability.DOWNLOADED
    ): ModelRecord = ModelRecord(
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
        availability = availability,
        importedAtEpochMs = 0L
    )

    // -------------------------------------------------------------------------
    // initialize() contract
    // -------------------------------------------------------------------------

    @Test
    fun `initialize with nonexistent path returns false and engine is not initialized`() {
        val engine = makeEngine()
        val result = engine.initialize("/nonexistent/path/model.tflite")
        assertFalse("initialize() should return false for missing file", result)
        assertFalse("isInitialized should be false after failed initialize()", engine.isInitialized)
    }

    @Test
    fun `engine is not initialized before any load or initialize call`() {
        val engine = makeEngine()
        assertFalse("Engine should not be initialized before any load/initialize", engine.isInitialized)
    }

    @Test
    fun `release clears initialized state`() {
        val engine = makeEngine()
        // Even if initialize fails (no native lib on host), release should be safe
        engine.release()
        assertFalse(engine.isInitialized)
        assertFalse(engine.isListening)
    }

    // -------------------------------------------------------------------------
    // load() contract — file-existence guards (no native lib on host JVM)
    // -------------------------------------------------------------------------

    @Test
    fun `load returns false when artifact file is missing on disk`() = runTest {
        val engine = makeEngine()
        val model = makeModelRecord("missing-model", "whisper_tiny.tflite")
        // No file created — storage path will not exist
        assertFalse(engine.load(model))
        assertFalse("Engine must not report initialized after a failed load", engine.isInitialized)
    }

    @Test
    fun `load returns false when record has no supported artifact`() = runTest {
        val engine = makeEngine()
        // .mp3 is not a supported artifact extension
        val model = makeModelRecord("model-id", "some_audio.mp3")
        assertFalse(engine.load(model))
        assertFalse(engine.isInitialized)
    }

    @Test
    fun `load accepts tflite artifact extension`() = runTest {
        val storage = makeStorage()
        val engine = makeEngine(storage)
        val modelId = "test-model-tflite"
        val artifactName = "whisper_tiny_30s_f32.tflite"

        // Create the file at the storage-managed path so the file-existence guard passes.
        // The native whisper context creation will fail on the JVM (no .so), so load() returns
        // false — but the important thing is it tries the right code path (no early artifact reject).
        val modelDir = storage.modelDirectory(modelId)
        modelDir.mkdirs()
        storage.artifactFile(modelId, artifactName).createNewFile()

        val model = makeModelRecord(modelId, artifactName)
        // load() returns false on JVM (native .so absent), but must NOT throw or reject .tflite ext
        engine.load(model)
        // On JVM without native lib: nativeInitWhisper is caught → isInitialized false
        assertFalse("isInitialized must be false when native init fails", engine.isInitialized)
        engine.release()
    }

    @Test
    fun `load accepts gguf artifact extension`() = runTest {
        val storage = makeStorage()
        val engine = makeEngine(storage)
        val modelId = "test-model-gguf"
        val artifactName = "whisper_tiny.gguf"

        val modelDir = storage.modelDirectory(modelId)
        modelDir.mkdirs()
        storage.artifactFile(modelId, artifactName).createNewFile()

        val model = makeModelRecord(modelId, artifactName)
        engine.load(model) // must not throw; file exists, native fails on JVM → false
        assertFalse("isInitialized must be false when native init fails on JVM", engine.isInitialized)
        engine.release()
    }

    @Test
    fun `load accepts bin artifact extension`() = runTest {
        val storage = makeStorage()
        val engine = makeEngine(storage)
        val modelId = "test-model-bin"
        val artifactName = "ggml-tiny.bin"

        val modelDir = storage.modelDirectory(modelId)
        modelDir.mkdirs()
        storage.artifactFile(modelId, artifactName).createNewFile()

        val model = makeModelRecord(modelId, artifactName)
        engine.load(model) // must not throw; file exists, native fails on JVM → false
        assertFalse("isInitialized must be false when native init fails on JVM", engine.isInitialized)
        engine.release()
    }

    // -------------------------------------------------------------------------
    // Readiness contract
    // -------------------------------------------------------------------------

    @Test
    fun `engine reports not initialized after failed load — used by isRuntimeReady`() = runTest {
        val engine = makeEngine()
        val model = makeModelRecord("bad-model", "model.tflite")
        // No file on disk → load fails
        engine.load(model)
        assertFalse(
            "After failed load, isInitialized must be false so isRuntimeReady stays false",
            engine.isInitialized
        )
    }

    // -------------------------------------------------------------------------
    // cancel / release contract
    // -------------------------------------------------------------------------

    @Test
    fun `cancel and release are safe to call on uninitialized engine`() {
        val engine = makeEngine()
        engine.cancel()
        assertFalse(engine.isListening)
        engine.release()
        assertFalse(engine.isListening)
        assertFalse(engine.isInitialized)
    }

    @Test
    fun `double release is safe`() {
        val engine = makeEngine()
        engine.release()
        engine.release() // must not throw
        assertFalse(engine.isInitialized)
    }
}
