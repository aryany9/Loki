package dev.loki.android.core.ui

import dev.loki.android.core.tools.ToolResult
import java.util.UUID

enum class MessageSender {
    USER,
    ASSISTANT
}

enum class TranscriptStatus {
    PENDING,
    COMPLETED,
    FAILED
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** All tool executions for this assistant turn, in chronological order. */
    val toolInvocations: List<ToolInvocation> = emptyList(),
    val isThinking: Boolean = false,
    val isStreaming: Boolean = false,
    val transcriptStatus: TranscriptStatus = TranscriptStatus.COMPLETED,
    val audioFilePath: String? = null,
    val waveformData: ByteArray? = null
) {
    constructor(
        id: String = UUID.randomUUID().toString(),
        sender: MessageSender,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        toolResult: ToolResult? = null,
        toolName: String? = null,
        isThinking: Boolean = false,
        isStreaming: Boolean = false
    ) : this(
        id = id,
        sender = sender,
        text = text,
        timestamp = timestamp,
        toolInvocations = if (toolName != null || toolResult != null) {
            listOf(ToolInvocation(toolName = toolName ?: "", arguments = emptyMap(), result = toolResult))
        } else {
            emptyList()
        },
        isThinking = isThinking,
        isStreaming = isStreaming
    )

    @Deprecated("Use toolInvocations instead", ReplaceWith("toolInvocations.lastOrNull()?.result"), DeprecationLevel.WARNING)
    val toolResult: ToolResult? get() = toolInvocations.lastOrNull()?.result

    @Deprecated("Use toolInvocations instead", ReplaceWith("toolInvocations.lastOrNull()?.toolName"), DeprecationLevel.WARNING)
    val toolName: String? get() = toolInvocations.lastOrNull()?.toolName
}
