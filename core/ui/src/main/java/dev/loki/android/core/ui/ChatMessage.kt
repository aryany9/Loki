package dev.loki.android.core.ui

import dev.loki.android.core.tools.ToolResult
import java.util.UUID

enum class MessageSender {
    USER,
    ASSISTANT
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolResult: ToolResult? = null,
    val isThinking: Boolean = false
)
