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

    // -------------------------------------------------------------------------
    // Change A: KV-cache capacity raised to match Gemma-4-E4B's 8192-token context.
    //
    // Root cause of previous FAILED_PRECONDITION errors: 1024 was exhausted by turn 3
    // because the same Conversation accumulates ALL turns cumulatively in the KV cache.
    //   available_state_entries = maxNumTokens - conversation.getTokenCount()
    // With 1024, by turn 3: 1024 - 910 = 114, and no prefill runner fits 114 < 128.
    //
    // 8192 matches the Gemma-4-E4B model's maximum context window. To support other
    // models, expose this as a constructor parameter when the model library is multi-model.
    // -------------------------------------------------------------------------
    private fun tryInitEngine(modelPath: String, backend: Backend): Pair<Boolean, Throwable?> {
        return try {
            val cacheDirFile = File(context.cacheDir, "litertlm").apply { mkdirs() }

            Log.i(TAG, "[Loki/Diagnostic] EngineConfig parameters:")
            Log.i(TAG, "[Loki/Diagnostic]   modelPath      = $modelPath")
            Log.i(TAG, "[Loki/Diagnostic]   backend        = $backend")
            Log.i(TAG, "[Loki/Diagnostic]   maxNumTokens   = $MAX_NUM_TOKENS" +
                "  (total KV-cache capacity; was 1024 — too small for multi-turn use)")
            Log.i(TAG, "[Loki/Diagnostic]   maxNumImages   = (not set -> -1 / model default)")
            Log.i(TAG, "[Loki/Diagnostic]   cacheDir       = ${cacheDirFile.absolutePath}")

            Log.i(TAG, "[Loki] before EngineConfig creation ($backend)")
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                // Change A: 8192 matches Gemma-4-E4B's full context window.
                // To support future models, pass this as a constructor parameter.
                maxNumTokens = MAX_NUM_TOKENS,
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

    // -------------------------------------------------------------------------
    // Change B: startConversation injects the system prompt ONCE into the native
    // ConversationConfig.systemInstruction, so it is prefilled into the KV cache at
    // conversation creation time and is never re-sent on subsequent generate() calls.
    // -------------------------------------------------------------------------
    override suspend fun startConversation(systemPrompt: String): Boolean =
        withContext(Dispatchers.IO) {
            val currentEngine = engine ?: run {
                Log.w(TAG, "[Loki] startConversation called but engine not initialized; initializing now")
                if (!initializeAsync()) return@withContext false
                engine
            } ?: return@withContext false

            // Close any existing conversation first (resets KV cache for new logical conversation)
            closeConversationInternal()

            return@withContext try {
                Log.i(TAG, "[Loki] before createConversation() with systemInstruction")
                // Contents.Companion.of(String) is confirmed available in LiteRT-LM 0.16.1 javap output.
                // ConversationConfig(systemInstruction: Contents) single-arg constructor is also confirmed.
                // prefillPrefaceOnInit defaults to true in the generated $default method, so the system
                // instruction is immediately prefilled into the KV cache on Conversation creation.
                val systemContents = Contents.of(systemPrompt)
                val convConfig = ConversationConfig(systemInstruction = systemContents)
                activeConversation = currentEngine.createConversation(convConfig)
                Log.i(TAG, "[Loki] after createConversation() with systemInstruction")

                val tokenCount = try { activeConversation!!.getTokenCount() } catch (e: Exception) { -1 }
                Log.i(TAG, "[Loki/Diagnostic] Conversation created:")
                Log.i(TAG, "[Loki/Diagnostic]   system prompt chars  = ${systemPrompt.length}")
                Log.i(TAG, "[Loki/Diagnostic]   system prompt tokens (est) = ${systemPrompt.length / 4}")
                Log.i(TAG, "[Loki/Diagnostic]   native getTokenCount() after init = $tokenCount")
                Log.i(TAG, "[Loki/Diagnostic]   KV capacity remaining  = ${if (tokenCount >= 0) MAX_NUM_TOKENS - tokenCount else "unknown"}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "[Loki] startConversation failed: ${t.javaClass.simpleName} - ${t.message}", t)
                false
            }
        }

    override fun resetConversation() {
        Log.i(TAG, "[Loki] resetConversation() called — closing native Conversation, Engine stays alive")
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

    // -------------------------------------------------------------------------
    // Change B: generate() now receives ONLY the new user message, not full history.
    //
    // The native Conversation already maintains all prior turns in its KV cache.
    // Re-injecting the full serialized history on every call was causing:
    //   - KV cache growing N× faster than necessary (proportional to history size)
    //   - "available state entries (114)" exhaustion by turn 3 with maxNumTokens=1024
    //
    // ConversationSession is responsible for only passing userInput (not buildPrompt()) here.
    // -------------------------------------------------------------------------
    override suspend fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.Default) {
        var currentEngine = engine ?: run {
            Log.i(TAG, "[Loki] Engine not initialized yet, calling initializeAsync()")
            if (initializeAsync()) engine else null
        } ?: return@withContext Result.failure(IllegalStateException("LiteRT-LM engine not initialized"))

        try {
            // If startConversation() was not called before generate(), create a plain Conversation
            // as fallback. This matches prior behavior for any callers that skip startConversation.
            if (activeConversation == null) {
                Log.w(TAG, "[Loki] generate() called without prior startConversation(); " +
                    "creating plain Conversation (system prompt will not be prefilled separately)")
                Log.i(TAG, "[Loki] before createConversation() (fallback, no system instruction)")
                activeConversation = currentEngine.createConversation()
                Log.i(TAG, "[Loki] after createConversation() (fallback)")
            }
            val conversation = activeConversation!!

            // Diagnostic: log native token count and remaining KV capacity before every generation.
            val conversationTokensUsed = try { conversation.getTokenCount() } catch (e: Exception) { -1 }
            val available = if (conversationTokensUsed >= 0) MAX_NUM_TOKENS - conversationTokensUsed else -1
            Log.i(TAG, "[Loki/Diagnostic] Before generation:")
            Log.i(TAG, "[Loki/Diagnostic]   new user message (chars)         = ${prompt.length}")
            Log.i(TAG, "[Loki/Diagnostic]   new user message tokens (est)    = ${prompt.length / 4}")
            Log.i(TAG, "[Loki/Diagnostic]   conversation.getTokenCount()     = $conversationTokensUsed  (KV cache fill)")
            Log.i(TAG, "[Loki/Diagnostic]   maxNumTokens                     = $MAX_NUM_TOKENS")
            Log.i(TAG, "[Loki/Diagnostic]   available state entries           = $available")
            if (available in 0..511 && conversationTokensUsed >= 0) {
                Log.w(TAG, "[Loki/Diagnostic] WARNING: Only $available state entries remain. " +
                    "If user message tokens (${prompt.length / 4} est) exceed this, " +
                    "GetOptimizedPrefillWorkGroups() will throw FAILED_PRECONDITION. " +
                    "Consider calling resetConversation() to free KV cache.")
            }

            val fullResponse = StringBuilder()
            Log.i(TAG, "[Loki] before sendMessageAsync() — sending new user message only")
            conversation.sendMessageAsync(Message.user(prompt)).collect { partialMessage ->
                val textContent = partialMessage.contents.contents
                    .filterIsInstance<Content.Text>()
                    .firstOrNull()?.text ?: ""
                if (textContent.isNotEmpty()) {
                    onToken?.invoke(textContent)
                    fullResponse.append(textContent)
                }
            }
            Log.i(TAG, "[Loki] after Flow collection completed")

            val tokensAfter = try { conversation.getTokenCount() } catch (e: Exception) { -1 }
            Log.i(TAG, "[Loki/Diagnostic] After generation: getTokenCount() = $tokensAfter " +
                "(delta = ${if (tokensAfter >= 0 && conversationTokensUsed >= 0) tokensAfter - conversationTokensUsed else "unknown"})")

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

    /** Closes and nulls the active Conversation, but leaves the Engine alive. */
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

    /** Closes both the Conversation and the Engine, resetting all native state. */
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

    companion object {
        private const val TAG = "LiteRtLlmEngine"

        /**
         * Total KV-cache capacity (state entries) for the LiteRT-LM Engine.
         *
         * Set to match Gemma-4-E4B's 8192-token context window (Change A).
         * Previously 1024, which was exhausted by turn 3 in multi-turn conversations:
         *   available = 1024 - kv_fill ≈ 1024 - 910 = 114 → FAILED_PRECONDITION.
         *
         * When the model library supports multiple models, expose this as a constructor
         * parameter and pass the model-specific context size from ModelCatalog.
         */
        const val MAX_NUM_TOKENS = 8192
    }
}


