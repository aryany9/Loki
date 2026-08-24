package dev.loki.android.core.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for all assistant tools.
 * Handles tool registration, discovery, permission checking, and dispatch.
 */
class ToolRegistry {

    private val tools = ConcurrentHashMap<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun get(name: String): Tool? = tools[name]

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getLocalTools(): List<LocalTool> = tools.values.filterIsInstance<LocalTool>()

    fun getOnlineTools(): List<OnlineTool> = tools.values.filterIsInstance<OnlineTool>()

    suspend fun execute(
        context: Context,
        name: String,
        arguments: Map<String, Any?>
    ): ToolResult {
        val tool = tools[name] ?: return ToolResult.error(
            "Tool '$name' not found",
            ToolErrorCode.NOT_FOUND
        )

        // Check required permissions
        for (perm in tool.requiredPermissions) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                perm
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return ToolResult.error(
                    "Missing permission: $perm",
                    ToolErrorCode.PERMISSION_DENIED
                )
            }
        }

        // Validate required arguments
        for ((paramName, paramDef) in tool.parameters) {
            if (paramDef.required) {
                val value = arguments[paramName]
                if (value == null || (value is String && value.isBlank())) {
                    return ToolResult.error(
                        "Missing required argument: $paramName",
                        ToolErrorCode.VALIDATION_ERROR
                    )
                }
            }
        }

        return try {
            tool.execute(context, arguments)
        } catch (e: Exception) {
            ToolResult.error(
                "Execution failed: ${e.message}",
                ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
