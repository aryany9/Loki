package dev.loki.android.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LlamaCppLlmEngine implements LlmEngine using llama.cpp via JNI.
 */
class LlamaCppLlmEngine(
    private val modelPath: String,
    private val nCtx: Int = 2048,
    private val nThreads: Int = minOf(6, maxOf(2, Runtime.getRuntime().availableProcessors() - 2))
) : LlmEngine, AutoCloseable {

    private var handle: Long = 0L

    init {
        load()
    }

    private fun load() {
        if (handle == 0L) {
            handle = LlamaBridge.nativeInitModel(modelPath, nCtx, nThreads)
            if (handle == 0L) {
                Log.e(TAG, "Failed to initialize native llama model from $modelPath")
            } else {
                Log.i(TAG, "Native llama model loaded successfully with handle: $handle (threads=$nThreads)")
            }
        }
    }

    override fun isReady(): Boolean = handle != 0L

    override suspend fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.Default) {
        if (!isReady()) {
            return@withContext Result.failure(IllegalStateException("Model not loaded"))
        }

        try {
            val callback = onToken?.let { cb ->
                LlamaBridge.TokenCallback { token -> cb(token) }
            }

            val result = LlamaBridge.nativeGenerate(
                handle = handle,
                prompt = prompt,
                grammarStr = grammar,
                maxTokens = maxTokens,
                callback = callback
            )

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            Result.failure(e)
        }
    }

    override fun cancel() {
        if (handle != 0L) {
            LlamaBridge.nativeCancel(handle)
        }
    }

    override fun close() {
        if (handle != 0L) {
            LlamaBridge.nativeFreeModel(handle)
            handle = 0L
            Log.i(TAG, "Model freed")
        }
    }

    companion object {
        private const val TAG = "LlamaCppLlmEngine"
    }
}
