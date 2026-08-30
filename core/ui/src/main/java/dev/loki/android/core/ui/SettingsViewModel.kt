package dev.loki.android.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.ui.theme.ThemeMode
import dev.loki.android.core.ui.theme.ThemeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val themeRepository: ThemeRepository,
    val conversationManager: ConversationManager
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themeRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val modelState: StateFlow<LlmModelState> = conversationManager.llmEngine.modelState

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
