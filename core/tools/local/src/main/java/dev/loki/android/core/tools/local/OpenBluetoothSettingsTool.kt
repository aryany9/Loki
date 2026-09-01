package dev.loki.android.core.tools.local

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolResult

class OpenBluetoothSettingsTool : LocalTool {
    override val name: String = "open_bluetooth_settings"
    override val description: String = "Open the system Bluetooth settings screen so the user can pair or toggle Bluetooth."
    override val parameters: Map<String, ToolParam> = emptyMap()
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success(
                mapOf(
                    "status" to "settings_opened",
                    "target" to "bluetooth"
                )
            )
        } catch (e: Exception) {
            ToolResult.error(
                message = e.message ?: "Failed to open Bluetooth settings",
                code = ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
