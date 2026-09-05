package dev.loki.android.core.ui

import dev.loki.android.core.tools.ToolResult
import java.util.UUID

/**
 * Represents a single tool execution associated with an assistant message.
 * [id] is the execution correlation ID matching [ConversationEvent.ToolCallStarted.callId],
 * used as the stable Compose key for [ToolResultCard].
 */
data class ToolInvocation(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val arguments: Map<String, Any?>,
    val result: ToolResult? = null,
    val isExecuting: Boolean = false
)
