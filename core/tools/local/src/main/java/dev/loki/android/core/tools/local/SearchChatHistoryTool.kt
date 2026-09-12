package dev.loki.android.core.tools.local

import android.content.Context
import dev.loki.android.core.conversation.ConversationStore
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SearchChatHistoryTool(
    private val conversationStore: ConversationStore? = null
) : LocalTool {
    override val name: String = "search_chat_history"
    override val capability: String = "general"
    override val description: String = "Search previous conversation turns and chat history by keyword."
    override val parameters: Map<String, ToolParam> = mapOf(
        "query" to ToolParam(
            type = ToolParamType.STRING,
            description = "Keyword or phrase to search for in past chat turns",
            required = true
        )
    )
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val query = arguments["query"] as? String
        if (query.isNullOrBlank()) {
            return ToolResult.error("Missing query parameter", ToolErrorCode.VALIDATION_ERROR)
        }

        return try {
            val store = conversationStore ?: ConversationStore(context)
            val results = store.searchTurns(query.trim(), limit = 5)

            if (results.isEmpty()) {
                ToolResult.success(
                    mapOf(
                        "results" to "No matching chat history found.",
                        "count" to "0"
                    )
                )
            } else {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val formatted = results.joinToString("\n") { result ->
                    val dateStr = dateFormat.format(Date(result.dateEpochMs))
                    "${result.conversationTitle} ($dateStr): ${result.snippet}"
                }
                ToolResult.success(
                    mapOf(
                        "results" to formatted,
                        "count" to results.size.toString()
                    )
                )
            }
        } catch (e: Exception) {
            ToolResult.error(
                message = e.message ?: "Failed to search chat history",
                code = ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
