package dev.loki.android.core.tools.local

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolResult

class GetWifiStateTool : LocalTool {
    override val name: String = "get_wifi_state"
    override val capability: String = "device"
    override val description: String = "Check whether Wi-Fi is currently enabled on the device."
    override val parameters: Map<String, ToolParam> = emptyMap()
    override val requiredPermissions: List<String> = listOf(Manifest.permission.ACCESS_WIFI_STATE)

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ToolResult.error("Wi-Fi service unavailable", ToolErrorCode.NOT_FOUND)

            val isEnabled = wifiManager.isWifiEnabled
            ToolResult.success(
                mapOf(
                    "enabled" to isEnabled.toString(),
                    "status" to if (isEnabled) "enabled" else "disabled"
                )
            )
        } catch (e: Exception) {
            ToolResult.error(
                message = e.message ?: "Failed to read Wi-Fi state",
                code = ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
