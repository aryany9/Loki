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

    suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult
}

interface LocalTool : Tool
interface OnlineTool : Tool
