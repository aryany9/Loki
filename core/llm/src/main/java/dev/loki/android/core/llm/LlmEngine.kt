package dev.loki.android.core.llm

import kotlinx.coroutines.flow.StateFlow

sealed interface LlmModelState {
    data object NotLoaded : LlmModelState
    data class Loading(val modelName: String = "Qwen3.8-4B") : LlmModelState
    data class Ready(val modelName: String = "Qwen3.8-4B") : LlmModelState
    data class Error(val message: String) : LlmModelState
}

enum class ModelPromptFormat {
    CHATML,
    GEMMA
}

interface LlmEngine {
    val modelState: StateFlow<LlmModelState>
    val promptFormat: ModelPromptFormat
        get() = ModelPromptFormat.CHATML

    fun isReady(): Boolean

    suspend fun initializeAsync(modelPath: String? = null): Boolean

    /**
     * Initializes (or re-initializes) the persistent native Conversation with a system prompt.
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
    suspend fun startConversation(systemPrompt: String): Boolean = true

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
    ): Result<String>

    fun cancel()
    fun release()
}

