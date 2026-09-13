package dev.loki.android.core.tools

/**
 * Categorizes the root reason why access to a tool was denied.
 */
enum class DenialReason {
    DEVICE_LOCKED,
    USER_NOT_AUTHORIZED,
    CAPABILITY_UNSUPPORTED
}

/**
 * Result of evaluating a [ToolAccessPolicy].
 */
sealed interface AccessDecision {
    /** Access is permitted under the current device conditions. */
    data object Allow : AccessDecision

    /**
     * Access is denied.
     *
     * @property reason The security or capability reason for denial.
     * @property message Optional user-facing or logging explanation.
     */
    data class Deny(
        val reason: DenialReason,
        val message: String? = null
    ) : AccessDecision
}

/**
 * Access evaluation gate that determines whether a tool execution is permitted
 * based on current device conditions (such as keyguard lock state).
 */
interface ToolAccessPolicy {
    fun evaluate(
        tool: Tool,
        arguments: Map<String, Any?> = emptyMap(),
        deviceLockState: DeviceLockState
    ): AccessDecision

    companion object {
        inline operator fun invoke(
            crossinline block: (tool: Tool, arguments: Map<String, Any?>, deviceLockState: DeviceLockState) -> AccessDecision
        ): ToolAccessPolicy = object : ToolAccessPolicy {
            override fun evaluate(
                tool: Tool,
                arguments: Map<String, Any?>,
                deviceLockState: DeviceLockState
            ): AccessDecision = block(tool, arguments, deviceLockState)
        }
    }
}

/**
 * Permissive default access policy that allows all tool executions unconditionally.
 */
object AllowAllToolAccessPolicy : ToolAccessPolicy {
    override fun evaluate(
        tool: Tool,
        arguments: Map<String, Any?>,
        deviceLockState: DeviceLockState
    ): AccessDecision = AccessDecision.Allow
}

