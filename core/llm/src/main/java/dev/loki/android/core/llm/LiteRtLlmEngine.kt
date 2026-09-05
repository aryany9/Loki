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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    override var onContextCompacted: ((String) -> Unit)? = null
    private var lastAgentConfig: AgentConfig? = null
    private var npuConsecutiveFailures: Int = 0

    data class TurnEntry(
        val userMessage: Message,
        val promptText: String,
        val assistantResponse: String,
        val estimatedTokens: Int,
        val executedAction: Boolean = false,
        val source: String = "VOICE"
    )

    internal val recentTurns = mutableListOf<TurnEntry>()

    override suspend fun load(model: ModelRecord): Boolean {
        val artifact = model.artifacts.firstOrNull { it.fileName.endsWith(".litertlm") }
            ?: return false

        val probe = NpuCapabilityProbe.probe(context)
        val targetSoc = model.capabilities.npuTargetSoc.value
        if (model.capabilities.isNpuTargeted && targetSoc != null) {
            val isCompatible = (probe.socModel != null && probe.socModel.contains(targetSoc, ignoreCase = true)) ||
                    (probe.htpGeneration != null && probe.htpGeneration.equals(NpuCapabilityProbe.lookupHtpGeneration(targetSoc), ignoreCase = true))
            if (!isCompatible) {
                Log.w(TAG, "[Loki] Model ${model.id} targets $targetSoc which is incompatible with device (${probe.socModel ?: "Unknown"}, HTP=${probe.htpGeneration}). Refusing load.")
                return false
            }
        }

        npuConsecutiveFailures = 0
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
    private var activeExecutionBackend: ExecutionBackend? = null
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
        if (force) {
            npuConsecutiveFailures = 0
        }
        if (isReady() && !force) {
            _modelState.value = LlmModelState.Ready(
                modelName = File(currentModelPath ?: "").name,
                activeBackend = activeExecutionBackend ?: ExecutionBackend.GPU
            )
            return@withContext true
        }

        mutex.withLock {
            if (isReady() && !force) {
                _modelState.value = LlmModelState.Ready(
                    modelName = File(currentModelPath ?: "").name,
                    activeBackend = activeExecutionBackend ?: ExecutionBackend.GPU
                )
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

            val probe = NpuCapabilityProbe.probe(context)
            Log.i(TAG, "[Loki] NPU Capability probe: vendor=${probe.npuVendor}, htp=${probe.htpGeneration}, usable=${probe.npuUsable}")

            val candidates: List<ExecutionBackend> = when (runtimeConfig.backend) {
                ExecutionBackend.NPU -> listOf(ExecutionBackend.NPU)
                ExecutionBackend.GPU -> listOf(ExecutionBackend.GPU)
                ExecutionBackend.CPU -> listOf(ExecutionBackend.CPU)
                ExecutionBackend.AUTOMATIC -> {
                    val record = loadedModelRecord ?: modelManager.getActiveModel()
                    val targetSoc = record?.capabilities?.npuTargetSoc?.value
                    val isModelCompatible = targetSoc == null ||
                            (probe.socModel != null && probe.socModel.contains(targetSoc, ignoreCase = true)) ||
                            (probe.htpGeneration != null && probe.htpGeneration.equals(NpuCapabilityProbe.lookupHtpGeneration(targetSoc), ignoreCase = true))

                    val includeNpu = probe.npuUsable && isModelCompatible && npuConsecutiveFailures < MAX_NPU_CONSECUTIVE_FAILURES
                    if (includeNpu) {
                        listOf(ExecutionBackend.NPU, ExecutionBackend.GPU, ExecutionBackend.CPU)
                    } else {
                        if (npuConsecutiveFailures >= MAX_NPU_CONSECUTIVE_FAILURES) {
                            Log.w(TAG, "[Loki] NPU candidate skipped due to repeated-failure backoff ($npuConsecutiveFailures consecutive failures)")
                        }
                        listOf(ExecutionBackend.GPU, ExecutionBackend.CPU)
                    }
                }
            }

            val attempts = mutableListOf<BackendAttempt>()

            for (candidate in candidates) {
                val startTime = System.currentTimeMillis()
                val backendObj = when (candidate) {
                    ExecutionBackend.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
                    ExecutionBackend.GPU -> Backend.GPU()
                    ExecutionBackend.CPU -> Backend.CPU()
                    ExecutionBackend.AUTOMATIC -> Backend.GPU()
                }

                val candidateKvCapacity = if (candidate == ExecutionBackend.NPU) {
                    // TODO: Derive NPU KV capacity dynamically from container metadata once available (add-npu-backend-support task 8.4)
                    minOf(activeKvCapacity, NPU_DEFAULT_KV_CAPACITY)
                } else {
                    activeKvCapacity
                }

                Log.i(TAG, "[Loki] Attempting backend candidate: $candidate with KV capacity: $candidateKvCapacity")
                val (success, error) = tryInitEngine(path, backendObj, candidateKvCapacity)
                val duration = System.currentTimeMillis() - startTime

                if (success) {
                    if (candidate == ExecutionBackend.NPU) {
                        npuConsecutiveFailures = 0
                    }
                    activeBackend = backendObj
                    activeExecutionBackend = candidate
                    activeKvCapacity = candidateKvCapacity
                    attempts.add(BackendAttempt(candidate, duration, AttemptOutcome.SUCCESS))
                    val report = EngineInitReport(attempts, candidate)
                    _modelState.value = LlmModelState.Ready(fileName, candidate, report)
                    Log.i(TAG, "[Loki] LiteRT-LM engine initialized successfully on backend: $candidate in ${duration}ms (KV=$activeKvCapacity)")
                    return@withContext true
                } else {
                    if (candidate == ExecutionBackend.NPU) {
                        npuConsecutiveFailures++
                    }
                    Log.w(TAG, "[Loki] Backend candidate $candidate failed after ${duration}ms: ${error?.message}")
                    attempts.add(BackendAttempt(candidate, duration, AttemptOutcome.FAILED, error?.message))
                    releaseNativeResources()
                }
            }

            val report = EngineInitReport(attempts, null)
            val finalErr = "LiteRT-LM initialization failed on attempted backends: ${attempts.map { "${it.backend}: ${it.failureReason}" }}"
            Log.e(TAG, "[Loki] $finalErr")
            _modelState.value = LlmModelState.Error(finalErr, report)
            return@withContext false
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

    internal fun computeReplayTurns(recent: List<TurnEntry>, budget: Int): List<TurnEntry> {
        if (budget <= 0 || recent.isEmpty()) return emptyList()
        val selected = mutableListOf<TurnEntry>()
        var tokensSum = 0
        for (turn in recent.takeLast(3).reversed()) {
            if (tokensSum + turn.estimatedTokens <= budget) {
                selected.add(0, turn)
                tokensSum += turn.estimatedTokens
            } else {
                break
            }
        }
        return selected
    }

    internal fun buildConversationConfig(
        agentConfig: AgentConfig,
        replayMessages: List<Message> = emptyList(),
        backend: ExecutionBackend? = activeExecutionBackend
    ): ConversationConfig {
        val systemContents = Contents.of(agentConfig.systemInstruction)
        return if (backend == ExecutionBackend.NPU) {
            Log.i(TAG, "[Loki] Active backend is NPU: skipping custom SamplerConfig")
            ConversationConfig(
                systemInstruction = systemContents,
                initialMessages = replayMessages
            )
        } else {
            val genConfig = agentConfig.generationConfig
            val samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(
                topK = genConfig.topK,
                topP = genConfig.topP.toDouble(),
                temperature = genConfig.temperature.toDouble(),
                seed = genConfig.seed ?: 0
            )
            ConversationConfig(
                systemInstruction = systemContents,
                initialMessages = replayMessages,
                samplerConfig = samplerConfig
            )
        }
    }

    override suspend fun startConversation(agentConfig: AgentConfig): Boolean =
        withContext(Dispatchers.IO) {
            lastAgentConfig = agentConfig

            val currentEngine = engine ?: run {
                Log.w(TAG, "[Loki] startConversation called but engine not initialized; initializing now")
                if (!initializeAsync(modelPath = null, runtimeConfig = agentConfig.runtimeConfig, force = false)) return@withContext false
                engine
            } ?: return@withContext false

            closeConversationInternal()

            return@withContext try {
                Log.i(TAG, "[Loki] before createConversation() with AgentConfig")
                val systemPromptEst = (agentConfig.systemInstruction.length / 4) + 32
                val replayBudget = activeKvCapacity - (systemPromptEst + 512 + 128)
                val replayableTurns = recentTurns.filter { !it.executedAction && it.source != "TEXT" }
                val selectedReplayTurns = computeReplayTurns(replayableTurns, replayBudget)
                val replayMessages = mutableListOf<Message>()
                for (turn in selectedReplayTurns) {
                    replayMessages.add(turn.userMessage)
                    if (turn.assistantResponse.isNotBlank()) {
                        replayMessages.add(Message.model(turn.assistantResponse))
                    }
                }

                if (selectedReplayTurns.isNotEmpty()) {
                    Log.i(TAG, "[Loki] startConversation: replaying ${selectedReplayTurns.size} turns into new conversation")
                }

                val convConfig = buildConversationConfig(agentConfig, replayMessages, activeExecutionBackend)
                activeConversation = currentEngine.createConversation(convConfig)
                Log.i(TAG, "[Loki] after createConversation() with AgentConfig (replayTurns=${selectedReplayTurns.size})")

                val tokenCount = try { activeConversation!!.getTokenCount() } catch (e: Exception) { -1 }
                Log.i(TAG, "[Loki/Diagnostic] Conversation created:")
                Log.i(TAG, "[Loki/Diagnostic]   systemInstruction chars = ${agentConfig.systemInstruction.length}")
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
        recentTurns.clear()
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
    ): Result<String> = generate(prompt, audioBytes, grammar, maxTokens, onToken, "VOICE")

    override suspend fun generate(
        prompt: String,
        audioBytes: ByteArray?,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
        source: String
    ): Result<String> = withContext(Dispatchers.Default) {
        val currentEngine = engine ?: run {
            Log.i(TAG, "[Loki] Engine not initialized yet, calling initializeAsync()")
            if (initializeAsync()) engine else null
        } ?: return@withContext Result.failure(IllegalStateException("LiteRT-LM engine not initialized"))

        try {
            if (activeConversation == null) {
                Log.w(TAG, "[Loki] generate() called without prior startConversation(); creating Conversation with retained/default AgentConfig")
                val convConfig = buildConversationConfig(lastAgentConfig ?: AgentConfig(), emptyList(), activeExecutionBackend)
                activeConversation = currentEngine.createConversation(convConfig)
            }

            var conversation = activeConversation!!
            val conversationTokensUsed = try { conversation.getTokenCount() } catch (e: Exception) { -1 }
            val newPromptTokensEst = (prompt.length / 4) + 16

            if (conversationTokensUsed >= 0 && (conversationTokensUsed + maxTokens + newPromptTokensEst + 128 > activeKvCapacity)) {
                Log.w(TAG, "[Loki] KV cache nearing capacity ($conversationTokensUsed / $activeKvCapacity); resetting conversation context-preservingly")
                val currentConfig = lastAgentConfig ?: AgentConfig()
                val systemPromptEst = (currentConfig.systemInstruction.length / 4) + 32
                val tokensReserved = newPromptTokensEst + maxTokens + 128
                val replayBudget = activeKvCapacity - (systemPromptEst + tokensReserved)

                val selectedReplayTurns = computeReplayTurns(recentTurns, replayBudget)
                val replayMessages = mutableListOf<Message>()
                for (turn in selectedReplayTurns) {
                    replayMessages.add(turn.userMessage)
                    if (turn.assistantResponse.isNotBlank()) {
                        replayMessages.add(Message.model(turn.assistantResponse))
                    }
                }

                if (selectedReplayTurns.isNotEmpty()) {
                    Log.i(TAG, "[Loki] Context compacted: replaying ${selectedReplayTurns.size} turns with preserved AgentConfig")
                } else if (recentTurns.isNotEmpty()) {
                    Log.w(TAG, "[Loki] Context compacted: replay did not fit in budget ($replayBudget tokens); context dropped, reset with AgentConfig only")
                } else {
                    Log.i(TAG, "[Loki] Context compacted: resetting with preserved AgentConfig")
                }

                closeConversationInternal()
                val convConfig = buildConversationConfig(currentConfig, replayMessages, activeExecutionBackend)
                activeConversation = currentEngine.createConversation(convConfig)
                conversation = activeConversation!!
                recentTurns.clear()
                recentTurns.addAll(selectedReplayTurns)

                onContextCompacted?.invoke("Context compacted: KV cache nearing capacity ($conversationTokensUsed / $activeKvCapacity)")
            }
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

            val resultText = fullResponse.toString()
            val turnEstTokens = ((prompt.length + resultText.length) / 4) + 16
            val executedAction = isActionExecution(resultText)
            recentTurns.add(TurnEntry(userMessage, prompt, resultText, turnEstTokens, executedAction = executedAction, source = source))
            if (recentTurns.size > 10) {
                recentTurns.removeAt(0)
            }

            Result.success(resultText)
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
        recentTurns.clear()

        try {
            Log.i(TAG, "[Loki] before engine.close()")
            engine?.close()
            Log.i(TAG, "[Loki] after engine.close()")
        } catch (e: Exception) {
            Log.w(TAG, "[Loki] Error closing engine", e)
        } finally {
            engine = null
            activeBackend = null
            activeExecutionBackend = null
        }
    }

    private fun validateKvCapacity(requested: Int?): Int {
        if (requested == null || requested <= 0) return DEFAULT_KV_CAPACITY
        return requested.coerceIn(MIN_KV_CAPACITY, MAX_KV_CAPACITY)
    }

    companion object {
        private const val TAG = "LiteRtLlmEngine"
        const val MIN_KV_CAPACITY = 1024
        const val NPU_DEFAULT_KV_CAPACITY = 4096
        const val DEFAULT_KV_CAPACITY = 8192
        const val MAX_KV_CAPACITY = 16384
        const val MAX_NPU_CONSECUTIVE_FAILURES = 3

        private val READ_ONLY_TOOLS = setOf(
            "lookup_contact",
            "select_contact",
            "search_chat_history",
            "get_battery_status",
            "get_bluetooth_state",
            "get_current_time",
            "get_ram_usage",
            "get_wifi_state"
        )

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun isActionExecution(response: String): Boolean {
            val trimmed = response.trim()
            val jsonText = when {
                trimmed.startsWith("```") && trimmed.endsWith("```") -> {
                    trimmed.removePrefix("```").removeSuffix("```")
                        .removePrefix("json").trim()
                }
                trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
                else -> null
            }

            if (jsonText != null) {
                try {
                    val element = json.parseToJsonElement(jsonText) as? JsonObject
                    if (element != null && element.containsKey("tool")) {
                        val toolName = element["tool"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: ""
                        if (toolName.isNotEmpty() && toolName !in READ_ONLY_TOOLS) {
                            return true
                        }
                    }
                } catch (_: Exception) {}
            }

            val toolRegex = """"tool"\s*:\s*"([^"]+)"""".toRegex()
            val match = toolRegex.find(trimmed)
            if (match != null) {
                val toolName = match.groupValues[1].trim().lowercase()
                if (toolName.isNotEmpty() && toolName !in READ_ONLY_TOOLS) {
                    return true
                }
            }

            return false
        }
    }
}



