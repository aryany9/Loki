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
    private var activeBackend: Backend? = null
    private var currentModelPath: String? = null

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
                Log.e(TAG, "[Loki] Error: $err")
                _modelState.value = LlmModelState.Error(err)
                return@withContext false
            }

            currentModelPath = path
            val fileName = File(path).name
            _modelState.value = LlmModelState.Loading(fileName)
            Log.i(TAG, "[Loki] Initializing LiteRT-LM engine with model: $path")

            // Release any partial native state before initializing
            releaseNativeResources()

            // 1. Try GPU backend first
            val (gpuSuccess, gpuError) = tryInitEngine(path, Backend.GPU())
            if (gpuSuccess) {
                activeBackend = Backend.GPU()
                _modelState.value = LlmModelState.Ready(fileName)
                Log.i(TAG, "[Loki] LiteRT-LM model initialized successfully on GPU backend")
                return@withContext true
            }

            Log.w(TAG, "[Loki] GPU backend initialization failed: ${gpuError?.message}")

            // Check if failure is a genuine backend error suitable for CPU retry, vs a model artifact failure
            if (isModelArtifactError(gpuError)) {
                val err = "Model artifact initialization error (GPU/CPU retry aborted): ${gpuError?.message}"
                Log.e(TAG, "[Loki] $err", gpuError)
                _modelState.value = LlmModelState.Error(err)
                return@withContext false
            }

            // 2. Retry on CPU backend if genuine GPU hardware/driver failure occurred
            Log.i(TAG, "[Loki] Attempting CPU backend fallback for LiteRT-LM engine")
            val (cpuSuccess, cpuError) = tryInitEngine(path, Backend.CPU())
            if (cpuSuccess) {
                activeBackend = Backend.CPU()
                _modelState.value = LlmModelState.Ready(fileName)
                Log.i(TAG, "[Loki] LiteRT-LM model initialized successfully on CPU backend fallback")
                return@withContext true
            }

            val finalErr = "LiteRT-LM initialization failed on both GPU and CPU: ${cpuError?.message}"
            Log.e(TAG, "[Loki] $finalErr", cpuError)
            _modelState.value = LlmModelState.Error(finalErr)
            false
        }
    }

    private fun tryInitEngine(modelPath: String, backend: Backend): Pair<Boolean, Throwable?> {
        return try {
            val cacheDirFile = File(context.cacheDir, "litertlm").apply { mkdirs() }
            Log.i(TAG, "[Loki] before EngineConfig creation ($backend)")
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 1024,
                cacheDir = cacheDirFile.absolutePath
            )
            Log.i(TAG, "[Loki] after EngineConfig creation")

            Log.i(TAG, "[Loki] before Engine construction")
            val newEngine = Engine(config)
            Log.i(TAG, "[Loki] after Engine construction")

            Log.i(TAG, "[Loki] before Engine.initialize()")
            newEngine.initialize()
            Log.i(TAG, "[Loki] after Engine.initialize()")

            engine = newEngine
            Pair(true, null)
        } catch (t: Throwable) {
            Log.e(TAG, "[Loki] tryInitEngine exception: ${t.javaClass.simpleName} - ${t.message}", t)
            releaseNativeResources()
            Pair(false, t)
        }
    }

    private fun isModelArtifactError(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        val msg = throwable.message?.lowercase() ?: ""
        return msg.contains("tokenizer") ||
                msg.contains("section not found") ||
                msg.contains("invalid model") ||
                msg.contains("corrupt") ||
                msg.contains("unsupported format")
    }

    private fun isGpuSamplerError(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        val msg = throwable.message?.lowercase() ?: ""
        return msg.contains("opencl") ||
                msg.contains("top-k") ||
                msg.contains("topk") ||
                msg.contains("sampler") ||
                msg.contains("webgpu") ||
                msg.contains("vulkan") ||
                msg.contains("cannot find opencl library")
    }

    override suspend fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.Default) {
        Log.i(TAG, "[Loki] generate called with prompt length ${prompt.length}")
        var currentEngine = engine ?: run {
            Log.i(TAG, "[Loki] Engine not initialized yet, calling initializeAsync()")
            if (initializeAsync()) engine else null
        } ?: return@withContext Result.failure(IllegalStateException("LiteRT-LM engine not initialized"))

        try {
            if (activeConversation == null) {
                Log.i(TAG, "[Loki] before createConversation()")
                activeConversation = currentEngine.createConversation()
                Log.i(TAG, "[Loki] after createConversation()")
            }
            val conversation = activeConversation!!

            val fullResponse = StringBuilder()
            Log.i(TAG, "[Loki] before sendMessageAsync()")
            conversation.sendMessageAsync(Message.user(prompt)).collect { partialMessage ->
                val textContent = partialMessage.contents.contents
                    .filterIsInstance<Content.Text>()
                    .firstOrNull()?.text ?: ""
                Log.i(TAG, "[Loki] during Flow collection: received token length ${textContent.length}")
                if (textContent.isNotEmpty()) {
                    onToken?.invoke(textContent)
                    fullResponse.append(textContent)
                }
            }
            Log.i(TAG, "[Loki] after Flow collection completed")

            Result.success(fullResponse.toString())
        } catch (e: Exception) {
            Log.e(TAG, "[Loki] LiteRT-LM generation failed with exception", e)

            // If generation failed due to a GPU sampler/backend error after Engine.initialize() succeeded,
            // fall back to CPU backend seamlessly if available.
            if (activeBackend is Backend.GPU && isGpuSamplerError(e) && !isModelArtifactError(e)) {
                Log.w(TAG, "[Loki] GPU generation/sampler failure detected. Triggering fallback to CPU backend.", e)
                releaseNativeResources()
                val path = currentModelPath ?: modelManager.getLiteRtModelFile()?.absolutePath
                if (path != null) {
                    val (cpuSuccess, cpuError) = tryInitEngine(path, Backend.CPU())
                    if (cpuSuccess) {
                        activeBackend = Backend.CPU()
                        Log.i(TAG, "[Loki] Engine successfully re-initialized on CPU backend. Retrying generation...")
                        return@withContext generate(prompt, grammar, maxTokens, onToken)
                    } else {
                        Log.e(TAG, "[Loki] CPU fallback re-initialization failed", cpuError)
                    }
                }
            }

            Result.failure(e)
        }
    }

    override fun cancel() {
        try {
            Log.i(TAG, "[Loki] before cancelProcess()")
            activeConversation?.cancelProcess()
            Log.i(TAG, "[Loki] after cancelProcess()")
        } catch (e: Exception) {
            Log.w(TAG, "[Loki] Error cancelling LiteRT-LM conversation", e)
        }
    }

    override fun release() {
        Log.i(TAG, "[Loki] releasing native resources")
        releaseNativeResources()
        _modelState.value = LlmModelState.NotLoaded
        Log.i(TAG, "[Loki] LiteRT-LM engine released")
    }

    private fun releaseNativeResources() {
        try {
            Log.i(TAG, "[Loki] before conversation.close()")
            activeConversation?.close()
            Log.i(TAG, "[Loki] after conversation.close()")
        } catch (e: Exception) {
            Log.w(TAG, "[Loki] Error closing active conversation", e)
        } finally {
            activeConversation = null
        }

        try {
            Log.i(TAG, "[Loki] before engine.close()")
            engine?.close()
            Log.i(TAG, "[Loki] after engine.close()")
        } catch (e: Exception) {
            Log.w(TAG, "[Loki] Error closing engine", e)
        } finally {
            engine = null
            activeBackend = null
        }
    }

    companion object {
        private const val TAG = "LiteRtLlmEngine"
    }
}
