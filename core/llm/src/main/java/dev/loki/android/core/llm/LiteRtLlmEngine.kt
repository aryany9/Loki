package dev.loki.android.core.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LiteRtLlmEngine provides on-device LLM inference using the LiteRT-LM SDK.
 * Supports .litertlm model format with GPU-preferred execution and CPU fallback.
 */
class LiteRtLlmEngine(
    private val context: Context,
    private val modelManager: ModelManager
) : LlmEngine {

    override val promptFormat: ModelPromptFormat = ModelPromptFormat.GEMMA

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var activeConversation: Conversation? = null

    private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.NotLoaded)
    override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

    override fun isReady(): Boolean = engine != null && engine?.isInitialized() == true

    override suspend fun initializeAsync(modelPath: String?): Boolean = withContext(Dispatchers.IO) {
        if (isReady()) {
            _modelState.value = LlmModelState.Ready()
            return@withContext true
        }

        mutex.withLock {
            if (isReady()) {
                _modelState.value = LlmModelState.Ready()
                return@withContext true
            }

            val path = modelPath ?: modelManager.getLiteRtModelFile()?.absolutePath
            if (path == null || !File(path).exists()) {
                val err = "No valid .litertlm model file found"
                Log.e(TAG, err)
                _modelState.value = LlmModelState.Error(err)
                return@withContext false
            }

            val fileName = File(path).name
            _modelState.value = LlmModelState.Loading(fileName)
            Log.i(TAG, "Initializing LiteRT-LM engine with model: $path")

            // Release any partial native state before initializing
            releaseNativeResources()

            // 1. Try GPU backend first
            val (gpuSuccess, gpuError) = tryInitEngine(path, Backend.GPU())
            if (gpuSuccess) {
                _modelState.value = LlmModelState.Ready(fileName)
                Log.i(TAG, "LiteRT-LM model initialized successfully on GPU backend")
                return@withContext true
            }

            Log.w(TAG, "GPU backend initialization failed: ${gpuError?.message}")

            // Check if failure is a genuine backend error suitable for CPU retry, vs a model artifact failure
            if (isModelArtifactError(gpuError)) {
                val err = "Model artifact initialization error (GPU/CPU retry aborted): ${gpuError?.message}"
                Log.e(TAG, err, gpuError)
                _modelState.value = LlmModelState.Error(err)
                return@withContext false
            }

            // 2. Retry on CPU backend if genuine GPU hardware/driver failure occurred
            Log.i(TAG, "Attempting CPU backend fallback for LiteRT-LM engine")
            val (cpuSuccess, cpuError) = tryInitEngine(path, Backend.CPU())
            if (cpuSuccess) {
                _modelState.value = LlmModelState.Ready(fileName)
                Log.i(TAG, "LiteRT-LM model initialized successfully on CPU backend fallback")
                return@withContext true
            }

            val finalErr = "LiteRT-LM initialization failed on both GPU and CPU: ${cpuError?.message}"
            Log.e(TAG, finalErr, cpuError)
            _modelState.value = LlmModelState.Error(finalErr)
            false
        }
    }

    private fun tryInitEngine(modelPath: String, backend: Backend): Pair<Boolean, Throwable?> {
        return try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            Pair(true, null)
        } catch (t: Throwable) {
            releaseNativeResources()
            Pair(false, t)
        }
    }

    /**
     * Determines whether an error is caused by model/artifact corruption or missing sections,
     * which must fail fast rather than triggering a CPU retry.
     */
    private fun isModelArtifactError(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        val msg = throwable.message?.lowercase() ?: ""
        return msg.contains("tokenizer") ||
                msg.contains("section not found") ||
                msg.contains("invalid model") ||
                msg.contains("corrupt") ||
                msg.contains("unsupported format")
    }

    override suspend fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.Default) {
        val currentEngine = engine ?: run {
            if (initializeAsync()) engine else null
        } ?: return@withContext Result.failure(IllegalStateException("LiteRT-LM engine not initialized"))

        try {
            val conversation = activeConversation ?: currentEngine.createConversation().also {
                activeConversation = it
            }

            val fullResponse = StringBuilder()
            conversation.sendMessageAsync(Message.user(prompt)).collect { partialMessage ->
                val textContent = partialMessage.contents.contents
                    .filterIsInstance<Content.Text>()
                    .firstOrNull()?.text ?: ""
                if (textContent.isNotEmpty()) {
                    onToken?.invoke(textContent)
                    fullResponse.append(textContent)
                }
            }

            Result.success(fullResponse.toString())
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT-LM generation failed", e)
            Result.failure(e)
        }
    }

    override fun cancel() {
        try {
            activeConversation?.cancelProcess()
            Log.i(TAG, "Signaled cancellation to LiteRT-LM conversation")
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling LiteRT-LM conversation", e)
        }
    }

    override fun release() {
        releaseNativeResources()
        _modelState.value = LlmModelState.NotLoaded
        Log.i(TAG, "LiteRT-LM engine released")
    }

    private fun releaseNativeResources() {
        try {
            activeConversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing active conversation", e)
        } finally {
            activeConversation = null
        }

        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine", e)
        } finally {
            engine = null
        }
    }

    companion object {
        private const val TAG = "LiteRtLlmEngine"
    }
}
