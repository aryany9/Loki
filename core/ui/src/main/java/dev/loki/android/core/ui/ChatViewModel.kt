package dev.loki.android.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.core.conversation.ConversationEvent
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.voice.stt.SttEngine
import dev.loki.android.core.voice.stt.SttEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    val conversationManager: ConversationManager,
    private val sttEngine: SttEngine? = null
) : ViewModel() {

    private val chatSession = conversationManager.newChatSession()
    val modelState: StateFlow<LlmModelState> = conversationManager.llmEngine.modelState

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = MessageSender.ASSISTANT,
                text = "Hi, I'm Loki. How can I help you today?"
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    init {
        // Asynchronously preload LLM model in background
        viewModelScope.launch {
            conversationManager.llmEngine.initializeAsync()
        }
    }

    fun retryLoadModel() {
        viewModelScope.launch {
            conversationManager.llmEngine.initializeAsync()
        }
    }

    fun clearChat() {
        chatSession.clear()
        _messages.value = listOf(
            ChatMessage(
                sender = MessageSender.ASSISTANT,
                text = "Chat cleared. What can I do for you?"
            )
        )
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(sender = MessageSender.USER, text = text)
        val thinkingMessage = ChatMessage(sender = MessageSender.ASSISTANT, text = "Thinking...", isThinking = true)
        _messages.value = _messages.value + userMessage + thinkingMessage

        viewModelScope.launch {
            chatSession.processUtterance(text, enableTts = false, source = "TEXT").collect { event ->
                when (event) {
                    is ConversationEvent.ToolExecuting -> {
                        _messages.value = _messages.value.map { msg ->
                            if (msg.id == thinkingMessage.id) msg.copy(text = "Executing ${event.toolName}...") else msg
                        }
                    }
                    is ConversationEvent.Completed -> {
                        val updatedList = _messages.value.filterNot { it.id == thinkingMessage.id }
                        val assistantMessage = ChatMessage(
                            sender = MessageSender.ASSISTANT,
                            text = event.finalResponse,
                            toolResult = event.toolResult
                        )
                        _messages.value = updatedList + assistantMessage
                    }
                    is ConversationEvent.Error -> {
                        val updatedList = _messages.value.filterNot { it.id == thinkingMessage.id }
                        val errorMessage = ChatMessage(
                            sender = MessageSender.ASSISTANT,
                            text = "Error: ${event.message}"
                        )
                        _messages.value = updatedList + errorMessage
                    }
                    else -> {}
                }
            }
        }
    }

    fun startVoiceInput() {
        if (sttEngine == null) return
        _isRecording.value = true
        viewModelScope.launch {
            sttEngine.startListening().collect { event ->
                when (event) {
                    is SttEvent.FinalResult -> {
                        _isRecording.value = false
                        if (event.text.isNotBlank()) {
                            sendMessage(event.text)
                        }
                    }
                    is SttEvent.ListeningStopped -> {
                        _isRecording.value = false
                    }
                    is SttEvent.Error -> {
                        _isRecording.value = false
                    }
                    else -> {}
                }
            }
        }
    }

    fun stopVoiceInput() {
        sttEngine?.stopListening()
        _isRecording.value = false
    }
}
