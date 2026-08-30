package dev.loki.android.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.core.conversation.ConversationEvent
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.conversation.ConversationRecord
import dev.loki.android.core.conversation.ConversationTurn
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.tools.ToolResult
import dev.loki.android.core.voice.stt.SttEngine
import dev.loki.android.core.voice.stt.SttEvent
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    val conversationManager: ConversationManager,
    private val sttEngine: SttEngine? = null
) : ViewModel() {

    val modelState: StateFlow<LlmModelState> = conversationManager.llmEngine.modelState

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationRecord>>(emptyList())
    val conversations: StateFlow<List<ConversationRecord>> = _conversations.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var generationJob: Job? = null
    private var inFlightAssistantMessageId: String? = null

    init {
        viewModelScope.launch {
            conversationManager.llmEngine.initializeAsync()
            loadInitialConversation()
        }
    }

    private suspend fun refreshConversations() {
        _conversations.value = conversationManager.listConversations()
    }

    suspend fun loadInitialConversation() {
        val convList = conversationManager.listConversations()
        _conversations.value = convList
        val mostRecent = convList.firstOrNull()
        if (mostRecent != null) {
            val loaded = conversationManager.loadConversation(mostRecent.id)
            if (loaded != null && loaded.turns.isNotEmpty()) {
                _messages.value = mapTurnsToMessages(loaded.turns)
            } else if (loaded != null) {
                _messages.value = mapTurnsToMessages(emptyList())
            }
        } else {
            val newConv = conversationManager.createConversation()
            _messages.value = mapTurnsToMessages(newConv.turns)
            refreshConversations()
        }
    }

    fun selectConversation(id: String) {
        cancelGeneration()
        viewModelScope.launch {
            val loaded = conversationManager.loadConversation(id)
            if (loaded != null) {
                _messages.value = mapTurnsToMessages(loaded.turns)
            }
            refreshConversations()
        }
    }

    fun newConversation() {
        cancelGeneration()
        viewModelScope.launch {
            val newConv = conversationManager.createConversation()
            _messages.value = mapTurnsToMessages(newConv.turns)
            refreshConversations()
        }
    }

    fun clearChat() = newConversation()

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationManager.deleteConversation(id)
            loadInitialConversation()
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            conversationManager.renameConversation(id, title)
            refreshConversations()
        }
    }

    fun retryLoadModel() {
        viewModelScope.launch {
            conversationManager.llmEngine.initializeAsync()
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        conversationManager.llmEngine.cancel()

        val targetId = inFlightAssistantMessageId
        _messages.value = _messages.value.map { msg ->
            if ((targetId != null && msg.id == targetId) || msg.isThinking || msg.isStreaming) {
                msg.copy(
                    isThinking = false,
                    isStreaming = false
                )
            } else {
                msg
            }
        }
        inFlightAssistantMessageId = null
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(sender = MessageSender.USER, text = text)
        val inFlightMessageId = UUID.randomUUID().toString()
        inFlightAssistantMessageId = inFlightMessageId
        val initialAssistantMessage = ChatMessage(
            id = inFlightMessageId,
            sender = MessageSender.ASSISTANT,
            text = "Thinking...",
            isThinking = true,
            isStreaming = false
        )
        _messages.value = _messages.value + userMessage + initialAssistantMessage

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            var lastUpdateTime = 0L
            var latestToolResult: ToolResult? = null
            var latestToolName: String? = null

            try {
                val chatSession = conversationManager.newChatSession()
                chatSession.processUtterance(text, enableTts = false, source = "TEXT").collect { event ->
                    when (event) {
                        is ConversationEvent.Thinking -> {
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) msg.copy(isThinking = true, isStreaming = false, text = "Thinking...") else msg
                            }
                        }
                        is ConversationEvent.ToolExecuting -> {
                            latestToolName = event.toolName
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        isThinking = true,
                                        isStreaming = false,
                                        text = "Executing ${event.toolName}...",
                                        toolName = event.toolName
                                    )
                                } else msg
                            }
                        }
                        is ConversationEvent.ToolExecuted -> {
                            latestToolResult = event.result
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        toolResult = event.result,
                                        toolName = latestToolName ?: msg.toolName
                                    )
                                } else msg
                            }
                        }
                        is ConversationEvent.GeneratingToken -> {
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime >= 50L) {
                                lastUpdateTime = now
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == inFlightMessageId) {
                                        msg.copy(
                                            text = event.partial,
                                            isThinking = false,
                                            isStreaming = true,
                                            toolResult = latestToolResult ?: msg.toolResult,
                                            toolName = latestToolName ?: msg.toolName
                                        )
                                    } else msg
                                }
                            }
                        }
                        is ConversationEvent.Completed -> {
                            val finalToolResult = event.toolResult ?: latestToolResult
                            val finalToolName = latestToolName
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        text = event.finalResponse,
                                        isThinking = false,
                                        isStreaming = false,
                                        toolResult = finalToolResult,
                                        toolName = finalToolName ?: msg.toolName
                                    )
                                } else msg
                            }
                            refreshConversations()
                        }
                        is ConversationEvent.Error -> {
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        text = "Error: ${event.message}",
                                        isThinking = false,
                                        isStreaming = false
                                    )
                                } else msg
                            }
                            refreshConversations()
                        }
                        else -> {}
                    }
                }
            } finally {
                if (inFlightAssistantMessageId == inFlightMessageId) {
                    inFlightAssistantMessageId = null
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

    companion object {
        fun mapTurnsToMessages(turns: List<ConversationTurn>): List<ChatMessage> {
            if (turns.isEmpty()) {
                return emptyList()
            }

            val result = mutableListOf<ChatMessage>()
            var pendingToolResult: ToolResult? = null
            var pendingToolName: String? = null

            for (turn in turns) {
                when (turn) {
                    is ConversationTurn.User -> {
                        if (pendingToolResult != null || pendingToolName != null) {
                            result.add(
                                ChatMessage(
                                    sender = MessageSender.ASSISTANT,
                                    text = "",
                                    toolResult = pendingToolResult,
                                    toolName = pendingToolName
                                )
                            )
                            pendingToolResult = null
                            pendingToolName = null
                        }
                        result.add(
                            ChatMessage(
                                sender = MessageSender.USER,
                                text = turn.text,
                                timestamp = turn.timestamp
                            )
                        )
                    }
                    is ConversationTurn.ToolCall -> {
                        pendingToolName = turn.tool
                    }
                    is ConversationTurn.ToolExecutionResult -> {
                        pendingToolName = turn.tool
                        pendingToolResult = turn.result
                    }
                    is ConversationTurn.Assistant -> {
                        result.add(
                            ChatMessage(
                                sender = MessageSender.ASSISTANT,
                                text = turn.text,
                                timestamp = turn.timestamp,
                                toolResult = pendingToolResult,
                                toolName = pendingToolName
                            )
                        )
                        pendingToolResult = null
                        pendingToolName = null
                    }
                }
            }

            if (pendingToolResult != null || pendingToolName != null) {
                result.add(
                    ChatMessage(
                        sender = MessageSender.ASSISTANT,
                        text = "",
                        toolResult = pendingToolResult,
                        toolName = pendingToolName
                    )
                )
            }

            return result
        }
    }
}
