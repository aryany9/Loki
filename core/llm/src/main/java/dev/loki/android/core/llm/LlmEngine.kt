package dev.loki.android.core.llm

import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.models.ExecutionBackend
import dev.loki.android.core.models.ModelCapabilities
import kotlinx.coroutines.flow.StateFlow

enum class AttemptOutcome {
    SUCCESS,
    FAILED,
    SKIPPED
}

data class BackendAttempt(
    val backend: ExecutionBackend,
    val durationMs: Long,
    val outcome: AttemptOutcome,
    val failureReason: String? = null
)

data class EngineInitReport(
    val attempts: List<BackendAttempt> = emptyList(),
    val finalBackend: ExecutionBackend? = null
)

sealed interface LlmModelState {
    data object NotLoaded : LlmModelState
    data class Loading(val modelName: String = "Qwen3.8-4B") : LlmModelState
    data class Ready(
        val modelName: String = "Qwen3.8-4B",
        val activeBackend: ExecutionBackend = ExecutionBackend.GPU,
        val initReport: EngineInitReport? = null
    ) : LlmModelState
    data class Error(
        val message: String,
        val initReport: EngineInitReport? = null
    ) : LlmModelState
}

enum class ModelPromptFormat {
    CHATML,
    GEMMA
}

interface LlmEngine {
    val modelState: StateFlow<LlmModelState>
    val promptFormat: ModelPromptFormat
        get() = ModelPromptFormat.CHATML
    val capabilities: ModelCapabilities
        get() = ModelCapabilities()
    var onContextCompacted: ((String) -> Unit)?
        get() = null
        set(_) {}
    fun isReady(): Boolean

    suspend fun initializeAsync(modelPath: String? = null): Boolean =
        initializeAsync(modelPath, dev.loki.android.core.models.RuntimeConfig(), false)

    suspend fun initializeAsync(
        modelPath: String? = null,
        runtimeConfig: dev.loki.android.core.models.RuntimeConfig = dev.loki.android.core.models.RuntimeConfig(),
        force: Boolean = false
    ): Boolean = true

    /**
     * Initializes (or re-initializes) the persistent native Conversation with an AgentConfig.
     *
     * For LiteRT-LM, this creates a [com.google.ai.edge.litertlm.Conversation] via
     * [com.google.ai.edge.litertlm.ConversationConfig.systemInstruction] so the system prompt is
     * prefilled ONCE into the KV cache and NOT re-injected on every [generate] call.
     *
     * Call once per logical conversation start or reset. After this, [generate] receives ONLY the
     * new user message — not the full reconstructed history string.
     *
     * Non-LiteRT implementations may keep the default no-op if they handle system prompts differently.
     *
     * @return true if the conversation was created/reset successfully; false on error.
     */
    suspend fun startConversation(systemPrompt: String): Boolean =
        startConversation(AgentConfig(systemInstruction = systemPrompt))

    suspend fun startConversation(agentConfig: AgentConfig): Boolean = true


    /**
     * Closes and resets the persistent native Conversation, clearing all KV-cache state.
     * The Engine itself stays initialized. Caller must invoke [startConversation] again before the
     * next [generate] call.
     */
    fun resetConversation() {}

    /**
     * Generates a model response.
     *
     * **For LiteRT-LM**: after [startConversation] has been called, [prompt] MUST be only the new
     * user message text, NOT the full conversation history string. The native Conversation already
     * maintains prior turns in its KV cache; re-injecting the full history on every call causes the
     * KV cache to grow far faster than necessary, exhausting [EngineConfig.maxNumTokens] prematurely
     * and triggering: FAILED_PRECONDITION: Chosen prefill work group size exceeds available state entries.
     */
    suspend fun generate(
        prompt: String,
        grammar: String? = null,
        maxTokens: Int = 256,
        onToken: ((String) -> Unit)? = null
    ): Result<String> = generate(prompt, null, grammar, maxTokens, onToken)

    /**
     * Generates a model response with optional direct audio input (WAV byte array).
     * When [audioBytes] is non-null and the engine/model supports multimodal audio,
     * the audio is sent as part of the turn (e.g. `Content.AudioBytes`).
     */
    suspend fun generate(
        prompt: String,
        audioBytes: ByteArray?,
        grammar: String? = null,
        maxTokens: Int = 256,
        onToken: ((String) -> Unit)? = null
    ): Result<String>

    fun cancel()
    fun release()
}

