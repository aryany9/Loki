package dev.loki.android.core.tools.local

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolResult

class GetBluetoothStateTool : LocalTool {
    override val name: String = "get_bluetooth_state"
    override val description: String = "Check whether Bluetooth is currently enabled on the device."
    override val parameters: Map<String, ToolParam> = emptyMap()
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val permissionToCheck = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            permissionToCheck
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return ToolResult.success(
                mapOf(
                    "enabled" to "unknown",
                    "reason" to "permission_not_granted"
                )
            )
        }

        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
                ?: @Suppress("DEPRECATION") android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return ToolResult.error("Bluetooth adapter unavailable on this device", ToolErrorCode.NOT_FOUND)

            val isEnabled = adapter.isEnabled
            ToolResult.success(
                mapOf(
                    "enabled" to isEnabled.toString(),
                    "status" to if (isEnabled) "enabled" else "disabled"
                )
            )
        } catch (e: SecurityException) {
            ToolResult.success(
                mapOf(
                    "enabled" to "unknown",
                    "reason" to "permission_not_granted"
                )
            )
        } catch (e: Exception) {
            ToolResult.error(
                message = e.message ?: "Failed to read Bluetooth state",
                code = ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
