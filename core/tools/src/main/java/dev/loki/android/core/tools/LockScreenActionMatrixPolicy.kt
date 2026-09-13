package dev.loki.android.core.tools

/**
 * Access policy that evaluates tool execution against the device lock state.
 *
 * When [DeviceLockState.UNLOCKED], all tools are permitted unconditionally.
 * When [DeviceLockState.LOCKED], only tools in [allowedToolsOnLockScreen] are permitted.
 * All other tools are denied with [DenialReason.DEVICE_LOCKED] and a contextual explanation.
 */
class LockScreenActionMatrixPolicy(
    val allowedToolsOnLockScreen: Set<String> = DEFAULT_ALLOWED_ON_LOCK_SCREEN,
    private val denialMessageProvider: ((Tool, Map<String, Any?>) -> String)? = null
) : ToolAccessPolicy {

    override fun evaluate(
        tool: Tool,
        arguments: Map<String, Any?>,
        deviceLockState: DeviceLockState
    ): AccessDecision {
        if (deviceLockState == DeviceLockState.UNLOCKED) {
            return AccessDecision.Allow
        }

        if (tool.name in allowedToolsOnLockScreen) {
            return AccessDecision.Allow
        }

        val message = denialMessageProvider?.invoke(tool, arguments) ?: defaultDenialMessage(tool, arguments)
        return AccessDecision.Deny(
            reason = DenialReason.DEVICE_LOCKED,
            message = message
        )
    }

    companion object {
        val DEFAULT_ALLOWED_ON_LOCK_SCREEN: Set<String> = setOf(
            // Phone & Calling
            "call_contact",
            "dial_number",
            "lookup_contact",
            "select_contact",

            // Media & Hardware Toggles
            "media_control",
            "toggle_flashlight",

            // Alarms & Timers
            "set_timer",
            "set_alarm",

            // Public Device Status
            "get_current_time",
            "get_battery_status",
            "get_wifi_state",
            "get_bluetooth_state",
            "get_ram_usage",

            // Interaction & Disambiguation
            "ask_user"
        )

        fun defaultDenialMessage(tool: Tool, arguments: Map<String, Any?>): String {
            return when (tool.name) {
                "open_app" -> {
                    val appName = arguments["app_name"]?.toString()?.trim()
                        ?: arguments["package_name"]?.toString()?.trim()
                        ?: arguments["name"]?.toString()?.trim()

                    if (!appName.isNullOrBlank()) {
                        "Please unlock your phone to open $appName."
                    } else {
                        "Please unlock your phone to open apps."
                    }
                }
                "open_wifi_settings", "open_bluetooth_settings" -> {
                    "Please unlock your device to change settings."
                }
                "search_chat_history", "remember_fact" -> {
                    "Please unlock your device to access personal memory and history."
                }
                else -> {
                    "Please unlock your device to use this feature."
                }
            }
        }
    }
}
