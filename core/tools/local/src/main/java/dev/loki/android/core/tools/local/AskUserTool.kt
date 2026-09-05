package dev.loki.android.core.tools.local

import android.content.Context
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

/**
 * Structured turn-intent protocol tool.
 * When the model needs information, clarification, or a decision from the user,
 * it MUST end its turn by calling this tool with the speech-facing text.
 */
class AskUserTool : LocalTool {
    override val name: String = "ask_user"
    override val capability: String = "general"
    override val description: String = "The ONLY way to hand the turn back to the user; a plain-text question ends the conversation. Invoke this tool with your question whenever you need information, a choice, or confirmation from the user."
    override val parameters: Map<String, ToolParam> = mapOf(
        "text" to ToolParam(
            type = ToolParamType.STRING,
            description = "The question or prompt to ask the user. Must NOT contain internal candidate IDs or full phone numbers.",
            required = true
        )
    )
    override val requiresConfirmation: Boolean = false

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val text = arguments["text"]?.toString()?.trim() ?: ""
        return ToolResult.success(mapOf("text" to text, "status" to "asked"))
    }
}
