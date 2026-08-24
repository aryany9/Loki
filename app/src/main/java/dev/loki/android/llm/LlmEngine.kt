package dev.loki.android.llm

interface LlmEngine {
    fun isReady(): Boolean

    suspend fun generate(
        prompt: String,
        grammar: String? = null,
        maxTokens: Int = 256,
        onToken: ((String) -> Unit)? = null
    ): Result<String>

    fun cancel()
}
