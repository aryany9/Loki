package dev.loki.android.core.tools

import android.content.Context

enum class ToolParamType {
    STRING,
    NUMBER,
    BOOLEAN
}

data class ToolParam(
    val type: ToolParamType,
    val description: String,
    val required: Boolean = true
)

interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, ToolParam>
    val requiredPermissions: List<String>
        get() = emptyList()

    /** Whether this tool requires explicit user confirmation before execution. */
    val requiresConfirmation: Boolean
        get() = false

    /**
     * Returns a human-readable description of the action about to be taken, used as the
     * repeat-back text shown/spoken to the user during the confirmation gate.
     * Default is the tool name; gated tools MUST override this with a concrete description.
     */
    fun describeAction(arguments: Map<String, Any?>): String = name

    suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult
}

interface LocalTool : Tool
interface OnlineTool : Tool
