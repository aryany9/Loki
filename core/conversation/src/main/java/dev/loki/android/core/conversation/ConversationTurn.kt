package dev.loki.android.core.conversation

import dev.loki.android.core.tools.ToolResult
import kotlinx.serialization.Serializable

@Serializable
sealed interface ConversationTurn {
    val timestamp: Long

    data class User(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationTurn

    data class ToolCall(
        val tool: String,
        val arguments: Map<String, String>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationTurn

    data class ToolExecutionResult(
        val tool: String,
        val result: ToolResult,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationTurn

    data class Assistant(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationTurn
}
