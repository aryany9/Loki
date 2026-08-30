package dev.loki.android.core.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.core.conversation.ConversationEvent
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.conversation.ConversationSession
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.models.ExecutionBackend
import dev.loki.android.core.models.GenerationConfig
import dev.loki.android.core.models.ModelCapabilities
import dev.loki.android.core.models.ModelLibraryManager
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.RuntimeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

enum class ResponseBehaviorPreset {
    FAST,
    BALANCED,
    PRECISE,
    CUSTOM
}

data class AgentPlaygroundUiState(
    val selectedModel: ModelRecord? = null,
    val modelCapabilities: ModelCapabilities = ModelCapabilities(),
    val modelState: LlmModelState = LlmModelState.NotLoaded,
    val systemPrompt: String = AgentConfig.DEFAULT_SYSTEM_PROMPT,
    val preset: ResponseBehaviorPreset = ResponseBehaviorPreset.BALANCED,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val seed: Int? = null,
    val maxOutputTokens: Int? = 256,
    val backend: ExecutionBackend = ExecutionBackend.AUTOMATIC,
    val contextKvCapacity: Int? = 8192,
    val isAdvancedExpanded: Boolean = false,
    val isSaving: Boolean = false,
    val showContextResetDialog: Boolean = false,
    val validationError: String? = null,
    val statusMessage: String? = null,
    // Test prompt state
    val testPromptInput: String = "",
    val isTestRunning: Boolean = false,
    val testPromptOutput: String? = null,
    val testToolDiagnostics: List<String> = emptyList(),
    val testError: String? = null
)

class AgentPlaygroundViewModel(
    private val context: Context,
    val conversationManager: ConversationManager,
    val agentConfigRepository: AgentConfigRepository,
    val modelLibraryManager: ModelLibraryManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentPlaygroundUiState())
    val uiState: StateFlow<AgentPlaygroundUiState> = _uiState.asStateFlow()

    init {
        loadInitialState()
        observeModelState()
        observeManifest()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val loadedConfig = agentConfigRepository.getAgentConfig()
            val manifest = modelLibraryManager?.manifest?.value
            val activeId = manifest?.activeModels?.get(ModelRuntime.LITERT_LM)
            val activeModel = manifest?.models?.firstOrNull { it.id == activeId }
            val engineCaps = conversationManager.llmEngine.capabilities
            val isAudio = activeModel?.capabilities?.isAudioInputSupported == true || engineCaps.supportsAudioInput

            val preset = inferPreset(loadedConfig.generationConfig)

            _uiState.value = _uiState.value.copy(
                selectedModel = activeModel,
                modelCapabilities = ModelCapabilities(
                    supportsText = true,
                    supportsToolCalling = true,
                    supportsAudioInput = isAudio,
                    supportsVisionInput = false
                ),
                systemPrompt = loadedConfig.systemInstruction,
                preset = preset,
                temperature = loadedConfig.generationConfig.temperature,
                topK = loadedConfig.generationConfig.topK,
                topP = loadedConfig.generationConfig.topP,
                seed = loadedConfig.generationConfig.seed,
                maxOutputTokens = loadedConfig.generationConfig.maxOutputTokens ?: 256,
                backend = loadedConfig.runtimeConfig.backend,
                contextKvCapacity = loadedConfig.runtimeConfig.contextKvCapacity ?: 8192
            )
        }
    }

    private fun observeModelState() {
        viewModelScope.launch {
            conversationManager.llmEngine.modelState.collect { state ->
                val activeModel = _uiState.value.selectedModel
                val engineCaps = conversationManager.llmEngine.capabilities
                val isAudio = activeModel?.capabilities?.isAudioInputSupported == true || engineCaps.supportsAudioInput
                _uiState.value = _uiState.value.copy(
                    modelState = state,
                    modelCapabilities = ModelCapabilities(
                        supportsText = true,
                        supportsToolCalling = true,
                        supportsAudioInput = isAudio,
                        supportsVisionInput = false
                    )
                )
            }
        }
    }

    private fun observeManifest() {
        val manager = modelLibraryManager ?: return
        viewModelScope.launch {
            manager.manifest.collect { manifest ->
                val activeId = manifest.activeModels[ModelRuntime.LITERT_LM]
                val activeModel = manifest.models.firstOrNull { it.id == activeId }
                val engineCaps = conversationManager.llmEngine.capabilities
                val isAudio = activeModel?.capabilities?.isAudioInputSupported == true || engineCaps.supportsAudioInput
                _uiState.value = _uiState.value.copy(
                    selectedModel = activeModel,
                    modelCapabilities = ModelCapabilities(
                        supportsText = true,
                        supportsToolCalling = true,
                        supportsAudioInput = isAudio,
                        supportsVisionInput = false
                    )
                )
            }
        }
    }

    fun updateSystemPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(systemPrompt = prompt)
    }

    fun selectPreset(preset: ResponseBehaviorPreset) {
        when (preset) {
            ResponseBehaviorPreset.FAST -> {
                _uiState.value = _uiState.value.copy(
                    preset = ResponseBehaviorPreset.FAST,
                    temperature = 0.8f,
                    topK = 40,
                    topP = 0.9f,
                    maxOutputTokens = 128
                )
            }
            ResponseBehaviorPreset.BALANCED -> {
                _uiState.value = _uiState.value.copy(
                    preset = ResponseBehaviorPreset.BALANCED,
                    temperature = 0.7f,
                    topK = 40,
                    topP = 0.95f,
                    maxOutputTokens = 256
                )
            }
            ResponseBehaviorPreset.PRECISE -> {
                _uiState.value = _uiState.value.copy(
                    preset = ResponseBehaviorPreset.PRECISE,
                    temperature = 0.2f,
                    topK = 10,
                    topP = 0.8f,
                    maxOutputTokens = 256
                )
            }
            ResponseBehaviorPreset.CUSTOM -> {
                _uiState.value = _uiState.value.copy(preset = ResponseBehaviorPreset.CUSTOM)
            }
        }
    }

    fun updateTemperature(temp: Float) {
        _uiState.value = _uiState.value.copy(
            temperature = temp.coerceIn(0.0f, 2.0f),
            preset = ResponseBehaviorPreset.CUSTOM
        )
    }

    fun updateTopK(topK: Int) {
        _uiState.value = _uiState.value.copy(
            topK = topK.coerceIn(1, 100),
            preset = ResponseBehaviorPreset.CUSTOM
        )
    }

    fun updateTopP(topP: Float) {
        _uiState.value = _uiState.value.copy(
            topP = topP.coerceIn(0.0f, 1.0f),
            preset = ResponseBehaviorPreset.CUSTOM
        )
    }

    fun updateSeed(seed: Int?) {
        _uiState.value = _uiState.value.copy(
            seed = seed,
            preset = ResponseBehaviorPreset.CUSTOM
        )
    }

    fun updateMaxOutputTokens(maxTokens: Int?) {
        _uiState.value = _uiState.value.copy(
            maxOutputTokens = maxTokens,
            preset = ResponseBehaviorPreset.CUSTOM
        )
    }

    fun updateBackend(backend: ExecutionBackend) {
        _uiState.value = _uiState.value.copy(backend = backend)
    }

    fun updateKvCapacity(capacity: Int?) {
        _uiState.value = _uiState.value.copy(contextKvCapacity = capacity)
    }

    fun toggleAdvancedExpanded() {
        _uiState.value = _uiState.value.copy(isAdvancedExpanded = !_uiState.value.isAdvancedExpanded)
    }

    fun updateTestPromptInput(input: String) {
        _uiState.value = _uiState.value.copy(testPromptInput = input)
    }

    fun requestSave() {
        _uiState.value = _uiState.value.copy(showContextResetDialog = true)
    }

    fun dismissContextResetDialog() {
        _uiState.value = _uiState.value.copy(showContextResetDialog = false)
    }

    fun confirmSave() {
        _uiState.value = _uiState.value.copy(showContextResetDialog = false, isSaving = true, validationError = null)
        saveConfigurationInternal()
    }

    fun directSave() {
        _uiState.value = _uiState.value.copy(isSaving = true, validationError = null)
        saveConfigurationInternal()
    }

    private fun saveConfigurationInternal() {
        val state = _uiState.value

        // Validate bounds
        if (state.temperature !in 0.0f..2.0f) {
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                validationError = "Temperature must be between 0.0 and 2.0"
            )
            return
        }

        if (state.topK !in 1..100) {
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                validationError = "Top-K must be between 1 and 100"
            )
            return
        }

        if (state.topP !in 0.0f..1.0f) {
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                validationError = "Top-P must be between 0.0 and 1.0"
            )
            return
        }

        val kv = state.contextKvCapacity
        if (kv != null && kv !in 1024..16384) {
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                validationError = "Context capacity must be between 1024 and 16384"
            )
            return
        }

        val newConfig = AgentConfig(
            systemInstruction = state.systemPrompt,
            generationConfig = GenerationConfig(
                temperature = state.temperature,
                topK = state.topK,
                topP = state.topP,
                seed = state.seed,
                maxOutputTokens = state.maxOutputTokens
            ),
            runtimeConfig = RuntimeConfig(
                backend = state.backend,
                contextKvCapacity = state.contextKvCapacity
            )
        )

        viewModelScope.launch {
            try {
                agentConfigRepository.saveAgentConfig(newConfig)
                conversationManager.applyAgentConfig(newConfig)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = "Configuration saved successfully"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    validationError = "Failed to save configuration: ${e.message}"
                )
            }
        }
    }

    fun resetDefaults() {
        viewModelScope.launch {
            val defaultConfig = AgentConfig()
            agentConfigRepository.resetDefaults()
            conversationManager.applyAgentConfig(defaultConfig)

            _uiState.value = _uiState.value.copy(
                systemPrompt = defaultConfig.systemInstruction,
                preset = ResponseBehaviorPreset.BALANCED,
                temperature = 0.7f,
                topK = 40,
                topP = 0.95f,
                seed = null,
                maxOutputTokens = 256,
                backend = ExecutionBackend.AUTOMATIC,
                contextKvCapacity = 8192,
                statusMessage = "Reset to default configuration",
                validationError = null
            )
        }
    }

    fun runTestPrompt() {
        val prompt = _uiState.value.testPromptInput.trim()
        if (prompt.isBlank() || _uiState.value.isTestRunning) return

        _uiState.value = _uiState.value.copy(
            isTestRunning = true,
            testPromptOutput = null,
            testToolDiagnostics = emptyList(),
            testError = null
        )

        val currentConfig = buildCurrentAgentConfig()
        val testSession = ConversationSession(
            context = context,
            llmEngine = conversationManager.llmEngine,
            toolRegistry = conversationManager.toolRegistry,
            ttsEngine = null,
            permissionManager = conversationManager.permissionManager,
            agentConfig = currentConfig,
            maxIterations = 5
        )

        viewModelScope.launch {
            val diagnostics = mutableListOf<String>()
            var finalResp: String? = null
            var err: String? = null

            try {
                testSession.processUtterance(prompt, enableTts = false, source = "PLAYGROUND_TEST").collect { event ->
                    when (event) {
                        is ConversationEvent.Thinking -> {
                            diagnostics.add("Thinking on: ${event.query}")
                        }
                        is ConversationEvent.ToolExecuting -> {
                            diagnostics.add("Tool executing: ${event.toolName} args=${event.args}")
                        }
                        is ConversationEvent.ToolExecuted -> {
                            diagnostics.add("Tool executed: ${event.toolName} success=${event.result.success} data=${event.result.data ?: event.result.error}")
                        }
                        is ConversationEvent.Completed -> {
                            finalResp = event.finalResponse
                        }
                        is ConversationEvent.Error -> {
                            err = event.message
                        }
                        else -> {}
                    }
                    _uiState.value = _uiState.value.copy(testToolDiagnostics = diagnostics.toList())
                }
            } catch (t: Throwable) {
                err = t.message ?: "Execution failed"
            } finally {
                _uiState.value = _uiState.value.copy(
                    isTestRunning = false,
                    testPromptOutput = finalResp,
                    testToolDiagnostics = diagnostics,
                    testError = err
                )
            }
        }
    }

    fun buildCurrentAgentConfig(): AgentConfig {
        val state = _uiState.value
        return AgentConfig(
            systemInstruction = state.systemPrompt,
            generationConfig = GenerationConfig(
                temperature = state.temperature,
                topK = state.topK,
                topP = state.topP,
                seed = state.seed,
                maxOutputTokens = state.maxOutputTokens
            ),
            runtimeConfig = RuntimeConfig(
                backend = state.backend,
                contextKvCapacity = state.contextKvCapacity
            )
        )
    }

    private fun inferPreset(gen: GenerationConfig): ResponseBehaviorPreset {
        return when {
            gen.temperature == 0.8f && gen.topK == 40 && gen.topP == 0.9f && gen.maxOutputTokens == 128 -> ResponseBehaviorPreset.FAST
            gen.temperature == 0.7f && gen.topK == 40 && gen.topP == 0.95f && (gen.maxOutputTokens == null || gen.maxOutputTokens == 256) -> ResponseBehaviorPreset.BALANCED
            gen.temperature == 0.2f && gen.topK == 10 && gen.topP == 0.8f && (gen.maxOutputTokens == null || gen.maxOutputTokens == 256) -> ResponseBehaviorPreset.PRECISE
            else -> ResponseBehaviorPreset.CUSTOM
        }
    }
}
