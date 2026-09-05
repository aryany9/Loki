package dev.loki.android.core.conversation

import dev.loki.android.core.tools.Tool
import dev.loki.android.core.tools.ToolPolicy
import dev.loki.android.core.models.ConversationMode

/**
 * Tool policy for [ConversationMode.CHAT] sessions.
 *
 * Excludes [ask_user] completely — its schema is never injected into the Chat prompt,
 * making it impossible for the model to invoke or hallucinate it.
 */
object ChatToolPolicy : ToolPolicy {
    override fun isAllowed(tool: Tool): Boolean = tool.name != "ask_user"
}

/**
 * Tool policy for [ConversationMode.VOICE] sessions.
 *
 * Permits all tools, including [ask_user] which is the turn-yielding signal used by
 * [AssistantSession] to re-arm the microphone for the next spoken round.
 */
object VoiceToolPolicy : ToolPolicy {
    override fun isAllowed(tool: Tool): Boolean = true
}

/**
 * Returns the [ToolPolicy] appropriate for the given [ConversationMode].
 */
fun ConversationMode.toToolPolicy(): ToolPolicy = when (this) {
    ConversationMode.CHAT -> ChatToolPolicy
    ConversationMode.VOICE -> VoiceToolPolicy
}
