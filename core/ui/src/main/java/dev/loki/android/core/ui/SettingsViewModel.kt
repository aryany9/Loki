package dev.loki.android.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.conversation.MemoryEntry
import dev.loki.android.core.conversation.MemorySource
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.theme.ThemeMode
import dev.loki.android.core.theme.ThemeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val themeRepository: ThemeRepository,
    val conversationManager: ConversationManager
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themeRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val modelState: StateFlow<LlmModelState> = conversationManager.llmEngine.modelState

    private val _memories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val memories: StateFlow<List<MemoryEntry>> = _memories.asStateFlow()

    init {
        loadMemories()
    }

    fun loadMemories() {
        viewModelScope.launch {
            _memories.value = conversationManager.memoryStore.getAll()
        }
    }

    fun addMemory(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            conversationManager.memoryStore.add(trimmed, MemorySource.USER_MANUAL)
            loadMemories()
        }
    }

    fun updateMemory(id: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            conversationManager.memoryStore.update(id, trimmed)
            loadMemories()
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            conversationManager.memoryStore.delete(id)
            loadMemories()
        }
    }

    fun clearMemories() {
        viewModelScope.launch {
            conversationManager.memoryStore.clear()
            loadMemories()
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
