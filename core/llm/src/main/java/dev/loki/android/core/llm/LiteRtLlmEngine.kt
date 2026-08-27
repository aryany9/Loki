package dev.loki.android.core.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LiteRtLlmEngine uses MediaPipe LLM Inference API for on-device inference.
 * Supports models in LiteRT (.bin) format.
 */
class LiteRtLlmEngine(
    private val context: Context,
    private val modelManager: ModelManager
) : LlmEngine {

    override val promptFormat: ModelPromptFormat = ModelPromptFormat.GEMMA

    private val mutex = Mutex()
    private var llmInference: LlmInference? = null

    private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.NotLoaded)
    override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

    override fun isReady(): Boolean = llmInference != null

    override suspend fun initializeAsync(modelPath: String?): Boolean = withContext(Dispatchers.IO) {
        if (llmInference != null) {
            _modelState.value = LlmModelState.Ready()
            return@withContext true
        }

        mutex.withLock {
            if (llmInference != null) {
                _modelState.value = LlmModelState.Ready()
                return@withContext true
            }

            val path = modelPath ?: modelManager.getLiteRtModelFile()?.absolutePath
            if (path == null || !File(path).exists()) {
                val err = "No LiteRT model file found"
                Log.e(TAG, err)
                _modelState.value = LlmModelState.Error(err)
                return@withContext false
            }

            val fileName = File(path).name
            _modelState.value = LlmModelState.Loading(fileName)
            Log.i(TAG, "Initializing LiteRT engine with model: $path")

            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    // TODO: Add more options like temperature, topK, etc.
                    .build()
                
                llmInference = LlmInference.createFromOptions(context, options)
                _modelState.value = LlmModelState.Ready(fileName)
                Log.i(TAG, "LiteRT model loaded successfully")
                true
            } catch (e: Exception) {
                val err = "Failed to initialize LiteRT: ${e.message}"
                Log.e(TAG, err, e)
                _modelState.value = LlmModelState.Error(err)
                false
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.Default) {
        val engine = llmInference ?: run {
            if (initializeAsync()) llmInference else null
        } ?: return@withContext Result.failure(IllegalStateException("LiteRT engine not initialized"))

        try {
            if (onToken != null) {
                // Streaming mode
                val fullResponse = StringBuilder()
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    engine.generateResponseAsync(prompt) { partialResult, done ->
                        onToken(partialResult)
                        fullResponse.append(partialResult)
                        if (done) {
                            if (continuation.isActive) continuation.resume(Unit) {}
                        }
                    }
                }
                Result.success(fullResponse.toString())
            } else {
                // Blocking mode
                val result = engine.generateResponse(prompt)
                Result.success(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT generation failed", e)
            Result.failure(e)
        }
    }

    override fun cancel() {
        // MediaPipe LlmInference doesn't have a direct "cancel" per request in some versions,
        // but we can handle it by not emitting further tokens or closing the engine if needed.
    }

    override fun release() {
        llmInference?.close()
        llmInference = null
        _modelState.value = LlmModelState.NotLoaded
        Log.i(TAG, "LiteRT engine released")
    }

    companion object {
        private const val TAG = "LiteRtLlmEngine"
    }
}
