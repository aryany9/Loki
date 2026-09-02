package dev.loki.android.core.tools.local

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolResult

class OpenWifiSettingsTool : LocalTool {
    override val name: String = "open_wifi_settings"
    override val capability: String = "device"
    override val description: String = "Open the system Wi-Fi settings screen so the user can connect or toggle Wi-Fi."
    override val parameters: Map<String, ToolParam> = emptyMap()
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(
                mapOf(
                    "status" to "settings_opened",
                    "target" to "wifi"
                )
            )
        } catch (e: Exception) {
            ToolResult.error(
                message = e.message ?: "Failed to open Wi-Fi settings",
                code = ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
