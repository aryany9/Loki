package dev.loki.android.core.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * DelegatingLlmEngine manages multiple underlying LlmEngines (e.g. llama.cpp and LiteRT-LM)
 * and switches between them based on the model being initialized.
 */
class DelegatingLlmEngine(
    private val llamaCppEngine: LlamaCppLlmEngine,
    private val liteRtEngine: LiteRtLlmEngine
) : LlmEngine {

    private var activeEngine: LlmEngine? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.NotLoaded)
    override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

    init {
        scope.launch {
            llamaCppEngine.modelState.collectLatest { state ->
                if (activeEngine == llamaCppEngine) {
                    _modelState.value = state
                }
            }
        }
        scope.launch {
            liteRtEngine.modelState.collectLatest { state ->
                if (activeEngine == liteRtEngine) {
                    _modelState.value = state
                }
            }
        }
    }

    override val promptFormat: ModelPromptFormat
        get() = activeEngine?.promptFormat ?: ModelPromptFormat.CHATML

    override fun isReady(): Boolean = activeEngine?.isReady() ?: false

    override suspend fun initializeAsync(modelPath: String?, runtime: ModelRuntime?): Boolean {
        if (runtime != null) {
            val nextEngine = when (runtime) {
                ModelRuntime.LLAMA_CPP -> llamaCppEngine
                ModelRuntime.LITERT_LM -> liteRtEngine
            }
            if (activeEngine != nextEngine) {
                activeEngine?.release()
                activeEngine = nextEngine
            }
        } else if (activeEngine == null) {
            // Default to llama.cpp if no engine is active and no runtime specified
            activeEngine = llamaCppEngine
        }

        return activeEngine?.initializeAsync(modelPath) ?: false
    }

    override suspend fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Result<String> {
        return activeEngine?.generate(prompt, grammar, maxTokens, onToken)
            ?: Result.failure(IllegalStateException("No active LLM engine"))
    }

    override fun cancel() {
        activeEngine?.cancel()
    }

    override fun release() {
        activeEngine?.release()
        activeEngine = null
        _modelState.value = LlmModelState.NotLoaded
    }
}
