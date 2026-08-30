package dev.loki.android.core.conversation

import dev.loki.android.core.tools.ToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ConversationTurn {
    val timestamp: Long

    @Serializable
    @SerialName("user")
    data class User(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationTurn

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val tool: String,
        val arguments: Map<String, String>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationTurn

    @Serializable
    @SerialName("tool_result")
    data class ToolExecutionResult(
        val tool: String,
        val result: ToolResult,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationTurn

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationTurn
}
