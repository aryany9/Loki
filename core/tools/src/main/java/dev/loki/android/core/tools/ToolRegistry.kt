package dev.loki.android.core.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Marker interface used by ToolRegistry to apply state-scoped grammar gating.
 * Imported via type alias to avoid a circular dependency on the conversation module.
 */
interface TaskStateGate {
    /** Non-null when only this specific tool should be exposed (e.g. select_contact during disambiguation). */
    val restrictToTool: String?
        get() = null
    /** Non-null when this single tool should be hidden from the grammar. */
    val hiddenTool: String?
        get() = null
    /** Set of tools that should be hidden from the grammar. Defaults to setOfNotNull(hiddenTool). */
    val hiddenTools: Set<String>
        get() = setOfNotNull(hiddenTool)
}

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

    /**
     * Returns whether the named tool requires user confirmation before execution.
     * Returns false for unknown tool names (fail-open on unknown tools).
     */
    fun requiresConfirmation(name: String): Boolean = tools[name]?.requiresConfirmation ?: false

    /**
     * Returns the human-readable repeat-back string for the named tool given the parsed arguments.
     * Returns the tool name if the tool is not found.
     */
    fun describeAction(name: String, arguments: Map<String, Any?>): String =
        tools[name]?.describeAction(arguments) ?: name

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getLocalTools(): List<LocalTool> = tools.values.filterIsInstance<LocalTool>()

    fun getOnlineTools(): List<OnlineTool> = tools.values.filterIsInstance<OnlineTool>()

    fun getAvailableTools(
        context: Context,
        permissionManager: PermissionManager = PermissionManager(),
        activeCapability: String? = null,
        advancingTool: String? = null,
        offline: Boolean = false,
        taskState: TaskStateGate? = null
    ): List<Tool> {
        return tools.values.filter { tool ->
            val envAvailable = !offline || tool !is OnlineTool
            val permissionGranted = tool.requiredPermissions.isEmpty() || permissionManager.arePermissionsGranted(context, tool.requiredPermissions)
            val capabilityMatches = activeCapability == null || tool.capability == "general" || tool.capability == activeCapability
            val internalMatches = !tool.isInternal || tool.name == advancingTool

            // State-scoped gating: CONTACT_DISAMBIGUATION restricts to select_contact + general tools only.
            val restrictTo = taskState?.restrictToTool
            val stateRestrictionPasses = restrictTo == null ||
                tool.name == restrictTo ||
                tool.capability == "general"

            // State-scoped gating: hides tools specified by the task state gate.
            val hideTools = taskState?.hiddenTools ?: setOfNotNull(taskState?.hiddenTool)
            val stateHidePasses = tool.name !in hideTools

            envAvailable && permissionGranted && capabilityMatches && internalMatches &&
                stateRestrictionPasses && stateHidePasses
        }
    }

    fun getDisabledTools(
        context: Context,
        permissionManager: PermissionManager = PermissionManager(),
        activeCapability: String? = null,
        advancingTool: String? = null
    ): List<Pair<Tool, String>> {
        val disabled = mutableListOf<Pair<Tool, String>>()
        for (tool in tools.values) {
            val capabilityMatches = activeCapability == null || tool.capability == "general" || tool.capability == activeCapability
            val internalMatches = !tool.isInternal || tool.name == advancingTool
            if (!capabilityMatches || !internalMatches) continue
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
