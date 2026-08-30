package dev.loki.android.core.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.models.ExecutionBackend
import dev.loki.android.core.models.ModelCapabilities
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntimeController
import dev.loki.android.core.models.RuntimeConfig
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
 *
 * ## Conversation lifecycle
 *
 * This engine maintains a persistent [Conversation] that accumulates KV-cache state across turns.
 * The correct usage pattern is:
 *
 * 1. Call [startConversation] once per logical conversation, passing the system prompt.
 *    This creates a [ConversationConfig] with [ConversationConfig.systemInstruction] so the system
 *    prompt is prefilled ONCE into the KV cache via the native `prefillPrefaceOnInit` path.
 * 2. For each user turn, call [generate] with ONLY the new user message text. The native
 *    [Conversation] already maintains prior turns in its KV cache; re-injecting full history
 *    would fill the KV cache much faster and cause FAILED_PRECONDITION errors.
 * 3. Call [resetConversation] when the logical conversation ends (e.g. user explicitly clears chat).
 *    This closes and replaces the Conversation without touching the Engine.
 *
 * ## KV cache capacity
 *
 * [EngineConfig.maxNumTokens] = [MAX_NUM_TOKENS] sets the TOTAL KV-cache capacity (state entries)
 * for the lifetime of the Engine. It is NOT per-turn. The native runtime computes:
 *   available_state_entries = maxNumTokens − conversation.getTokenCount()
 * When available < next-prompt tokens, GetOptimizedPrefillWorkGroups() throws FAILED_PRECONDITION.
 *
 * Gemma-4-E4B supports up to 8192 tokens. A value of 1024 was too small for multi-turn use.
 */
class LiteRtLlmEngine(
    private val context: Context,
    private val modelManager: ModelManager
) : LlmEngine, ModelRuntimeController {

    private var loadedModelRecord: ModelRecord? = null

    override suspend fun load(model: ModelRecord): Boolean {
        val artifact = model.artifacts.firstOrNull { it.fileName.endsWith(".litertlm") }
            ?: return false
        val path = File(modelManager.getStorageRoot(), "models/${model.id}/${artifact.relativePath}").absolutePath
        val success = initializeAsync(path)
        if (success) {
            loadedModelRecord = model
        }
        return success
    }

    override suspend fun unload(model: ModelRecord) {
        loadedModelRecord = null
        release()
    }

    override val promptFormat: ModelPromptFormat = ModelPromptFormat.GEMMA

    override val capabilities: ModelCapabilities
        get() {
            val record = loadedModelRecord ?: modelManager.getActiveModel()
            return ModelCapabilities(
                supportsText = true,
                supportsToolCalling = true,
                supportsAudioInput = record?.capabilities?.isAudioInputSupported ?: false,
                supportsVisionInput = false
            )
        }

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var activeConversation: Conversation? = null
    private var activeBackend: Backend? = null
    private var currentModelPath: String? = null
    private var activeKvCapacity: Int = DEFAULT_KV_CAPACITY

    private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.NotLoaded)
    override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

    override fun isReady(): Boolean = engine != null && engine?.isInitialized() == true

    override suspend fun initializeAsync(
        modelPath: String?,
        runtimeConfig: RuntimeConfig,
        force: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (isReady() && !force) {
            _modelState.value = LlmModelState.Ready()
            return@withContext true
        }

        mutex.withLock {
            if (isReady() && !force) {
                _modelState.value = LlmModelState.Ready()
                return@withContext true
            }

            val path = modelPath ?: currentModelPath ?: modelManager.getLiteRtModelFile()?.absolutePath
            if (path == null || !File(path).exists()) {
                val err = "No valid .litertlm model file found"
                Log.e(TAG, "[Loki] Error: $err")
                _modelState.value = LlmModelState.Error(err)
                return@withContext false
            }

            if (loadedModelRecord == null) {
                loadedModelRecord = modelManager.getActiveModel()
            }

            currentModelPath = path
            val fileName = File(path).name
            _modelState.value = LlmModelState.Loading(fileName)
            Log.i(TAG, "[Loki] Initializing LiteRT-LM engine with model: $path (force=$force)")

            releaseNativeResources()

            val requestedCapacity = runtimeConfig.contextKvCapacity
            activeKvCapacity = validateKvCapacity(requestedCapacity)
            Log.i(TAG, "[Loki] Dynamic KV cache capacity validated: $activeKvCapacity (requested=$requestedCapacity)")

            when (runtimeConfig.backend) {
                ExecutionBackend.GPU -> {
                    val (success, error) = tryInitEngine(path, Backend.GPU(), activeKvCapacity)
                    if (success) {
                        activeBackend = Backend.GPU()
                        _modelState.value = LlmModelState.Ready(fileName)
                        Log.i(TAG, "[Loki] LiteRT-LM engine initialized successfully on explicit GPU backend")
                        return@withContext true
                    }
                    val err = "Explicit GPU backend initialization failed: ${error?.message}"
                    Log.e(TAG, "[Loki] $err", error)
                    _modelState.value = LlmModelState.Error(err)
                    return@withContext false
                }
                ExecutionBackend.CPU -> {
                    val (success, error) = tryInitEngine(path, Backend.CPU(), activeKvCapacity)
                    if (success) {
                        activeBackend = Backend.CPU()
                        _modelState.value = LlmModelState.Ready(fileName)
                        Log.i(TAG, "[Loki] LiteRT-LM engine initialized successfully on explicit CPU backend")
                        return@withContext true
                    }
                    val err = "Explicit CPU backend initialization failed: ${error?.message}"
                    Log.e(TAG, "[Loki] $err", error)
                    _modelState.value = LlmModelState.Error(err)
                    return@withContext false
                }
                ExecutionBackend.AUTOMATIC -> {
                    val (gpuSuccess, gpuError) = tryInitEngine(path, Backend.GPU(), activeKvCapacity)
                    if (gpuSuccess) {
                        activeBackend = Backend.GPU()
                        _modelState.value = LlmModelState.Ready(fileName)
                        Log.i(TAG, "[Loki] LiteRT-LM model initialized successfully on GPU backend (AUTOMATIC)")
                        return@withContext true
                    }

                    Log.w(TAG, "[Loki] GPU backend initialization failed under AUTOMATIC mode: ${gpuError?.message}")

                    if (isModelArtifactError(gpuError)) {
                        val err = "Model artifact initialization error (GPU/CPU retry aborted): ${gpuError?.message}"
                        Log.e(TAG, "[Loki] $err", gpuError)
                        _modelState.value = LlmModelState.Error(err)
                        return@withContext false
                    }

                    Log.i(TAG, "[Loki] Attempting CPU backend fallback for LiteRT-LM engine (AUTOMATIC)")
                    val (cpuSuccess, cpuError) = tryInitEngine(path, Backend.CPU(), activeKvCapacity)
                    if (cpuSuccess) {
                        activeBackend = Backend.CPU()
                        _modelState.value = LlmModelState.Ready(fileName)
                        Log.i(TAG, "[Loki] LiteRT-LM model initialized successfully on CPU backend fallback")
                        return@withContext true
                    }

                    val finalErr = "LiteRT-LM initialization failed on both GPU and CPU: ${cpuError?.message}"
                    Log.e(TAG, "[Loki] $finalErr", cpuError)
                    _modelState.value = LlmModelState.Error(finalErr)
                    return@withContext false
                }
            }
        }
    }

    override suspend fun initializeAsync(modelPath: String?): Boolean =
        initializeAsync(modelPath, RuntimeConfig(), force = false)

    private fun tryInitEngine(modelPath: String, backend: Backend, kvCapacity: Int): Pair<Boolean, Throwable?> {
        return try {
            val cacheDirFile = File(context.cacheDir, "litertlm").apply { mkdirs() }

            Log.i(TAG, "[Loki/Diagnostic] EngineConfig parameters:")
            Log.i(TAG, "[Loki/Diagnostic]   modelPath      = $modelPath")
            Log.i(TAG, "[Loki/Diagnostic]   backend        = $backend")
            Log.i(TAG, "[Loki/Diagnostic]   maxNumTokens   = $kvCapacity (KV-cache capacity)")
            Log.i(TAG, "[Loki/Diagnostic]   cacheDir       = ${cacheDirFile.absolutePath}")

            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                audioBackend = Backend.CPU(),
                maxNumTokens = kvCapacity,
                cacheDir = cacheDirFile.absolutePath
            )

            val newEngine = Engine(config)
            newEngine.initialize()

            engine = newEngine
            Pair(true, null)
        } catch (t: Throwable) {
            Log.e(TAG, "[Loki] tryInitEngine exception: ${t.javaClass.simpleName} - ${t.message}", t)
            releaseNativeResources()
            Pair(false, t)
        }
    }

    override suspend fun startConversation(agentConfig: AgentConfig): Boolean =
        withContext(Dispatchers.IO) {
            val currentEngine = engine ?: run {
                Log.w(TAG, "[Loki] startConversation called but engine not initialized; initializing now")
                if (!initializeAsync(modelPath = null, runtimeConfig = agentConfig.runtimeConfig, force = false)) return@withContext false
                engine
            } ?: return@withContext false

            closeConversationInternal()

            return@withContext try {
                Log.i(TAG, "[Loki] before createConversation() with AgentConfig (SamplerConfig + systemInstruction)")
                val systemContents = Contents.of(agentConfig.systemInstruction)
                val genConfig = agentConfig.generationConfig
                val samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(
                    topK = genConfig.topK,
                    topP = genConfig.topP.toDouble(),
                    temperature = genConfig.temperature.toDouble(),
                    seed = genConfig.seed ?: 0
                )
                val convConfig = ConversationConfig(
                    systemInstruction = systemContents,
                    samplerConfig = samplerConfig
                )
                activeConversation = currentEngine.createConversation(convConfig)
                Log.i(TAG, "[Loki] after createConversation() with AgentConfig")

                val tokenCount = try { activeConversation!!.getTokenCount() } catch (e: Exception) { -1 }
                Log.i(TAG, "[Loki/Diagnostic] Conversation created with AgentConfig:")
                Log.i(TAG, "[Loki/Diagnostic]   systemInstruction chars = ${agentConfig.systemInstruction.length}")
                Log.i(TAG, "[Loki/Diagnostic]   samplerConfig (temp=${genConfig.temperature}, topK=${genConfig.topK}, topP=${genConfig.topP}, seed=${genConfig.seed ?: 0})")
                Log.i(TAG, "[Loki/Diagnostic]   prefilled token count   = $tokenCount")
                true
            } catch (e: Exception) {
                Log.e(TAG, "[Loki] Failed to create Conversation with AgentConfig: ${e.message}", e)
                false
            }
        }

    override suspend fun startConversation(systemPrompt: String): Boolean =
        startConversation(AgentConfig(systemInstruction = systemPrompt))

    override fun resetConversation() {
        Log.i(TAG, "[Loki] resetConversation() called")
        closeConversationInternal()
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
        audioBytes: ByteArray?,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.Default) {
        val currentEngine = engine ?: run {
            Log.i(TAG, "[Loki] Engine not initialized yet, calling initializeAsync()")
            if (initializeAsync()) engine else null
        } ?: return@withContext Result.failure(IllegalStateException("LiteRT-LM engine not initialized"))

        try {
            if (activeConversation == null) {
                Log.w(TAG, "[Loki] generate() called without prior startConversation(); creating plain Conversation")
                activeConversation = currentEngine.createConversation()
            }
            val conversation = activeConversation!!

            val conversationTokensUsed = try { conversation.getTokenCount() } catch (e: Exception) { -1 }
            val available = if (conversationTokensUsed >= 0) activeKvCapacity - conversationTokensUsed else -1
            Log.i(TAG, "[Loki/Diagnostic] Before generation:")
            Log.i(TAG, "[Loki/Diagnostic]   prompt chars = ${prompt.length}")
            Log.i(TAG, "[Loki/Diagnostic]   audio bytes  = ${audioBytes?.size ?: 0}")
            Log.i(TAG, "[Loki/Diagnostic]   tokens used  = $conversationTokensUsed / $activeKvCapacity")

            val userMessage = if (audioBytes != null && audioBytes.isNotEmpty()) {
                Message.user(Contents.of(Content.AudioBytes(audioBytes), Content.Text(prompt)))
            } else {
                Message.user(prompt)
            }

            val fullResponse = StringBuilder()
            conversation.sendMessageAsync(userMessage, maxOutputToken = maxTokens).collect { partialMessage ->
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
            Log.e(TAG, "[Loki] LiteRT-LM generation failed with exception", e)

            if (activeBackend is Backend.GPU && isGpuSamplerError(e) && !isModelArtifactError(e)) {
                Log.w(TAG, "[Loki] GPU generation/sampler failure detected. Triggering fallback to CPU backend.", e)
                releaseNativeResources()
                val path = currentModelPath ?: modelManager.getLiteRtModelFile()?.absolutePath
                if (path != null) {
                    val (cpuSuccess, cpuError) = tryInitEngine(path, Backend.CPU(), activeKvCapacity)
                    if (cpuSuccess) {
                        activeBackend = Backend.CPU()
                        Log.i(TAG, "[Loki] Engine successfully re-initialized on CPU backend. Retrying generation...")
                        return@withContext generate(prompt, audioBytes, grammar, maxTokens, onToken)
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
            Log.i(TAG, "[Loki] cancel() called — invoking native activeConversation.cancelProcess()")
            activeConversation?.cancelProcess()
            Log.i(TAG, "[Loki] activeConversation.cancelProcess() completed")
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

    private fun closeConversationInternal() {
        try {
            Log.i(TAG, "[Loki] before conversation.close()")
            activeConversation?.close()
            Log.i(TAG, "[Loki] after conversation.close()")
        } catch (e: Exception) {
            Log.w(TAG, "[Loki] Error closing active conversation", e)
        } finally {
            activeConversation = null
        }
    }

    private fun releaseNativeResources() {
        closeConversationInternal()

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

    private fun validateKvCapacity(requested: Int?): Int {
        if (requested == null || requested <= 0) return DEFAULT_KV_CAPACITY
        return requested.coerceIn(MIN_KV_CAPACITY, MAX_KV_CAPACITY)
    }

    companion object {
        private const val TAG = "LiteRtLlmEngine"
        const val MIN_KV_CAPACITY = 1024
        const val DEFAULT_KV_CAPACITY = 8192
        const val MAX_KV_CAPACITY = 16384
    }
}



