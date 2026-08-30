package dev.loki.android.core.voice.stt

import android.content.Context
import android.content.res.AssetManager
import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelSource
import dev.loki.android.core.models.ModelStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [LiteRtWhisperEngine].
 *
 * The TFLite Interpreter and asset loading require a real device or instrumented test
 * for end-to-end validation. These unit tests exercise the contract boundary conditions:
 * file-existence guards, artifact extension filtering, and release safety — all without
 * loading the native interpreter or assets.
 *
 * Full "initialize → transcribe" path is validated via device tests (tasks 4.1–4.2).
 */
class LiteRtWhisperEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun makeMockContext(): Context {
        val assetManager = mock<AssetManager>()
        return mock<Context>().also { ctx ->
            whenever(ctx.assets).thenReturn(assetManager)
        }
    }

    private fun makeStorage() = ModelStorage(tempFolder.root)

    private fun makeEngine(
        storage: ModelStorage = makeStorage(),
        context: Context = makeMockContext()
    ) = LiteRtWhisperEngine(storage, context)

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

    // -------------------------------------------------------------------------
    // initialize() contract — file-existence guard (no TFLite native on JVM)
    // -------------------------------------------------------------------------

    @Test
    fun `initialize with nonexistent path returns false and engine is not ready`() {
        val engine = makeEngine()
        val result = engine.initialize("/nonexistent/path/model.tflite")
        assertFalse("initialize() should return false for missing file", result)
        assertFalse("isReady() should be false after failed initialize()", engine.isReady())
    }

    @Test
    fun `engine is not ready before any load or initialize call`() {
        val engine = makeEngine()
        assertFalse("Engine should not be ready before any load/initialize", engine.isReady())
    }

    // -------------------------------------------------------------------------
    // load() contract — file-existence and extension filtering
    // -------------------------------------------------------------------------

    @Test
    fun `load returns false when artifact file is missing on disk`() = runTest {
        val engine = makeEngine()
        val model = makeModelRecord("missing-model", "whisper_tiny_30s_f32.tflite")
        assertFalse(engine.load(model))
        assertFalse("Engine must not report ready after failed load", engine.isReady())
    }

    @Test
    fun `load returns false when record has no tflite artifact`() = runTest {
        val engine = makeEngine()
        val model = makeModelRecord("model-id", "some_other_file.bin")
        // .bin is not .tflite — should return false immediately (extension filter)
        assertFalse(engine.load(model))
    }

    @Test
    fun `load returns false for missing tflite file — not ready for isRuntimeReady`() = runTest {
        val storage = makeStorage()
        val engine = makeEngine(storage)
        val model = makeModelRecord("test-model", "whisper_tiny_30s_f32.tflite")
        // File not created on disk → load fails
        assertFalse(engine.load(model))
        assertFalse(
            "isReady() must be false so isRuntimeReady(LITERT_ASR) stays false until real init",
            engine.isReady()
        )
    }

    // -------------------------------------------------------------------------
    // release / cancel contract
    // -------------------------------------------------------------------------

    @Test
    fun `cancel and release are safe on uninitialized engine`() {
        val engine = makeEngine()
        engine.cancel()
        assertFalse(engine.isListening)
        engine.release()
        assertFalse(engine.isListening)
        assertFalse(engine.isReady())
    }

    @Test
    fun `double release is safe`() {
        val engine = makeEngine()
        engine.release()
        engine.release()
        assertFalse(engine.isReady())
    }
}
