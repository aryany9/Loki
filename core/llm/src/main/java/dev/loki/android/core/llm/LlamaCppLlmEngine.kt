package dev.loki.android.core.llm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LlamaCppLlmEngine runs on-device inference using quantized GGUF models.
 */
class LlamaCppLlmEngine(
    private val modelManager: ModelManager,
    private val nCtx: Int = 2048,
    private val threads: Int = calculateDefaultThreads()
) : LlmEngine {

    private val mutex = Mutex()
    @Volatile private var nativeHandle: Long = 0

    private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.NotLoaded)
    override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

    override fun isReady(): Boolean = nativeHandle != 0L

    override suspend fun initializeAsync(modelPath: String?): Boolean = withContext(Dispatchers.IO) {
        if (nativeHandle != 0L) {
            _modelState.value = LlmModelState.Ready()
            return@withContext true
        }

        mutex.withLock {
            if (nativeHandle != 0L) {
                _modelState.value = LlmModelState.Ready()
                return@withContext true
            }

            val path = modelPath ?: modelManager.getDefaultModelFile()?.absolutePath
            if (path == null) {
                val err = "No model file found (expected model.gguf)"
                Log.e(TAG, err)
                _modelState.value = LlmModelState.Error(err)
                return@withContext false
            }

            val fileName = java.io.File(path).name
            _modelState.value = LlmModelState.Loading(fileName)
            Log.i(TAG, "Loading model asynchronously from $path (nCtx=$nCtx, threads=$threads)")

            val handle = LlamaBridge.nativeInitModel(path, nCtx, threads)
            if (handle != 0L) {
                nativeHandle = handle
                _modelState.value = LlmModelState.Ready(fileName)
                Log.i(TAG, "Model loaded successfully: $fileName (handle=$handle)")
                true
            } else {
                val err = "Failed to load model from $path"
                Log.e(TAG, err)
                _modelState.value = LlmModelState.Error(err)
                false
            }
        }
    }

    @Volatile private var isGenerating = false

    override suspend fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.Default) {
        if (nativeHandle == 0L) {
            val initialized = initializeAsync()
            if (!initialized || nativeHandle == 0L) {
                return@withContext Result.failure(IllegalStateException("LLM model not initialized"))
            }
        }

        isGenerating = true
        try {
            val callback = onToken?.let { cb ->
                LlamaBridge.TokenCallback { token -> cb(token) }
            }

            val result = LlamaBridge.nativeGenerate(
                handle = nativeHandle,
                prompt = prompt,
                grammarStr = grammar,
                maxTokens = maxTokens,
                callback = callback
            )

            isGenerating = false
            Result.success(result)
        } catch (e: CancellationException) {
            cancel()
            throw e
        } catch (e: Throwable) {
            isGenerating = false
            Log.e(TAG, "LLM generation failed", e)
            Result.failure(e)
        } finally {
            isGenerating = false
        }
    }

    override fun cancel() {
        if (nativeHandle != 0L && isGenerating) {
            isGenerating = false
            LlamaBridge.nativeCancel(nativeHandle)
            Log.i(TAG, "LLM native cancellation requested")
        }
    }

    override fun release() {
        if (nativeHandle != 0L) {
            LlamaBridge.nativeFreeModel(nativeHandle)
            nativeHandle = 0L
            _modelState.value = LlmModelState.NotLoaded
            Log.i(TAG, "Llama model released")
        }
    }

    companion object {
        private const val TAG = "LlamaCppLlmEngine"

        fun calculateDefaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                cores >= 8 -> 6
                cores >= 4 -> cores - 1
                else -> 2
            }
        }
    }
}
