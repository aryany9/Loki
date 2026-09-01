package dev.loki.android.core.tools.local

import android.content.Context
import dev.loki.android.core.conversation.MemorySource
import dev.loki.android.core.conversation.MemoryStore
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class RememberFactTool(
    private val memoryStore: MemoryStore? = null
) : LocalTool {
    override val name: String = "remember_fact"
    override val description: String = "Store a durable fact, preference, or identity detail about the user for future conversations."
    override val parameters: Map<String, ToolParam> = mapOf(
        "content" to ToolParam(
            type = ToolParamType.STRING,
            description = "The fact or detail to remember about the user",
            required = true
        )
    )
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val content = arguments["content"] as? String
        if (content.isNullOrBlank()) {
            return ToolResult.error("Missing content to remember", ToolErrorCode.VALIDATION_ERROR)
        }

        return try {
            val store = memoryStore ?: MemoryStore(context)
            val entry = store.add(content.trim(), MemorySource.MODEL_TOOL)
            ToolResult.success(
                mapOf(
                    "status" to "remembered",
                    "content" to entry.text
                )
            )
        } catch (e: Exception) {
            ToolResult.error(
                message = e.message ?: "Failed to remember fact",
                code = ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
