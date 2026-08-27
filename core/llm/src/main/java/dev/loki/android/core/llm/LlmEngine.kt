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

    suspend fun generate(
        prompt: String,
        grammar: String? = null,
        maxTokens: Int = 256,
        onToken: ((String) -> Unit)? = null
    ): Result<String>

    fun cancel()
    fun release()
}
