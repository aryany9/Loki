package dev.loki.android.core.tools.local

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class ToggleFlashlightTool : LocalTool {
    override val name: String = "toggle_flashlight"
    override val description: String = "Turn the device flashlight (torch) on or off."
    override val parameters: Map<String, ToolParam> = mapOf(
        "enabled" to ToolParam(
            type = ToolParamType.BOOLEAN,
            description = "Whether to turn the flashlight on (true) or off (false)",
            required = true
        )
    )
    override val requiredPermissions: List<String> = listOf(Manifest.permission.CAMERA)

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val enabledRaw = arguments["enabled"]
            ?: return ToolResult.error("Missing enabled parameter", ToolErrorCode.VALIDATION_ERROR)
        val enabled = when (enabledRaw) {
            is Boolean -> enabledRaw
            is String -> enabledRaw.equals("true", ignoreCase = true)
            else -> return ToolResult.error("Invalid enabled parameter type", ToolErrorCode.VALIDATION_ERROR)
        }

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ToolResult.error("Camera service unavailable", ToolErrorCode.NOT_FOUND)

            val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.error("No flash unit available on this device", ToolErrorCode.NOT_FOUND)

            cameraManager.setTorchMode(flashCameraId, enabled)
            ToolResult.success(
                mapOf(
                    "enabled" to enabled.toString(),
                    "status" to if (enabled) "on" else "off"
                )
            )
        } catch (e: Exception) {
            ToolResult.error(
                message = e.message ?: "Failed to set torch mode",
                code = ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
