package dev.loki.android.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.theme.ThemeMode
import dev.loki.android.core.theme.ThemeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val themeRepository: ThemeRepository,
    val conversationManager: ConversationManager,
    private val agentConfigRepository: AgentConfigRepository? = null
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themeRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val modelState: StateFlow<LlmModelState> = conversationManager.llmEngine.modelState

    val conversationLanguage: StateFlow<String> = (agentConfigRepository?.getAgentConfigFlow()
        ?.map { it.conversationLanguage }
        ?: MutableStateFlow(conversationManager.getAgentConfig().conversationLanguage))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), conversationManager.getAgentConfig().conversationLanguage)

    fun setConversationLanguage(language: String) {
        viewModelScope.launch {
            val current = agentConfigRepository?.getAgentConfig() ?: conversationManager.getAgentConfig()
            val updated = current.copy(conversationLanguage = language)
            agentConfigRepository?.saveAgentConfig(updated)
            conversationManager.setAgentConfig(updated)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themeRepository.setThemeMode(mode)
        }
    }

    fun retryLoadModel() {
        viewModelScope.launch {
            conversationManager.llmEngine.initializeAsync()
        }
    }
}
