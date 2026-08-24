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

    fun getAvailableTools(
        context: Context,
        permissionManager: PermissionManager = PermissionManager()
    ): List<Tool> {
        return tools.values.filter { tool ->
            tool.requiredPermissions.isEmpty() || permissionManager.arePermissionsGranted(context, tool.requiredPermissions)
        }
    }

    fun getDisabledTools(
        context: Context,
        permissionManager: PermissionManager = PermissionManager()
    ): List<Pair<Tool, String>> {
        val disabled = mutableListOf<Pair<Tool, String>>()
        for (tool in tools.values) {
            for (perm in tool.requiredPermissions) {
                if (!permissionManager.isPermissionGranted(context, perm)) {
                    disabled.add(tool to perm)
                    break
                }
            }
        }
        return disabled
    }

    suspend fun execute(
        context: Context,
        name: String,
        arguments: Map<String, Any?>,
        permissionManager: PermissionManager = PermissionManager()
    ): ToolResult {
        return when (val result = executeDetailed(context, name, arguments, permissionManager)) {
            is ToolExecutionResult.Success -> result.toolResult
            is ToolExecutionResult.Error -> result.toolResult
            is ToolExecutionResult.PermissionRequired -> ToolResult.error(
                "Missing permission: ${result.permission}",
                ToolErrorCode.PERMISSION_DENIED
            )
        }
    }

    suspend fun executeDetailed(
        context: Context,
        name: String,
        arguments: Map<String, Any?>,
        permissionManager: PermissionManager = PermissionManager()
    ): ToolExecutionResult {
        val tool = tools[name] ?: return ToolExecutionResult.Error(
            ToolResult.error(
                "Tool '$name' not found",
                ToolErrorCode.NOT_FOUND
            )
        )

        // Check required permissions
        for (perm in tool.requiredPermissions) {
            val state = permissionManager.checkPermission(context, perm)
            if (state != PermissionState.GRANTED) {
                return ToolExecutionResult.PermissionRequired(perm, state)
            }
        }

        // Validate required arguments
        for ((paramName, paramDef) in tool.parameters) {
            if (paramDef.required) {
                val value = arguments[paramName]
                if (value == null || (value is String && value.isBlank())) {
                    return ToolExecutionResult.Error(
                        ToolResult.error(
                            "Missing required argument: $paramName",
                            ToolErrorCode.VALIDATION_ERROR
                        )
                    )
                }
            }
        }

        return try {
            val result = tool.execute(context, arguments)
            if (result.success) {
                ToolExecutionResult.Success(result)
            } else {
                ToolExecutionResult.Error(result)
            }
        } catch (e: Exception) {
            ToolExecutionResult.Error(
                ToolResult.error(
                    "Execution failed: ${e.message}",
                    ToolErrorCode.EXECUTION_ERROR
                )
            )
        }
    }
}
