package dev.loki.android.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.conversation.MemoryEntry
import dev.loki.android.core.conversation.MemorySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MemoryViewModel(
    val conversationManager: ConversationManager
) : ViewModel() {

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
}
