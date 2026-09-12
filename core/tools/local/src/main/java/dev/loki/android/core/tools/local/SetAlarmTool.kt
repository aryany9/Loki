package dev.loki.android.core.tools.local

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class SetAlarmTool : LocalTool {
    override val name: String = "set_alarm"
    override val capability: String = "clock"
    override val description: String = "Set an alarm for a specific hour and minute."
    override val parameters: Map<String, ToolParam> = mapOf(
        "hour" to ToolParam(ToolParamType.NUMBER, "Hour in 24-hour format (0-23)", required = true),
        "minute" to ToolParam(ToolParamType.NUMBER, "Minute (0-59)", required = true),
        "message" to ToolParam(ToolParamType.STRING, "Optional alarm label", required = false)
    )

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val hour = (arguments["hour"] as? Number)?.toInt()
            ?: arguments["hour"]?.toString()?.toIntOrNull()
            ?: return ToolResult.error("Missing or invalid hour", ToolErrorCode.VALIDATION_ERROR)

        val minute = (arguments["minute"] as? Number)?.toInt()
            ?: arguments["minute"]?.toString()?.toIntOrNull()
            ?: return ToolResult.error("Missing or invalid minute", ToolErrorCode.VALIDATION_ERROR)

        val message = arguments["message"]?.toString() ?: "Alarm"

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ToolResult.success(
                mapOf(
                    "hour" to hour.toString(),
                    "minute" to minute.toString(),
                    "message" to message,
                    "status" to "alarm_set"
                )
            )
        } catch (e: Exception) {
            ToolResult.error("Failed to set alarm: ${e.message}", ToolErrorCode.EXECUTION_ERROR)
        }
    }
}
