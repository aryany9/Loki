package dev.loki.android.core.tools.local

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class SetTimerTool : LocalTool {
    override val name: String = "set_timer"
    override val description: String = "Set a countdown timer on the device."
    override val parameters: Map<String, ToolParam> = mapOf(
        "seconds" to ToolParam(ToolParamType.NUMBER, "Length of the timer in seconds", required = true),
        "message" to ToolParam(ToolParamType.STRING, "Optional label for the timer", required = false)
    )

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val seconds = (arguments["seconds"] as? Number)?.toInt()
            ?: arguments["seconds"]?.toString()?.toIntOrNull()
            ?: return ToolResult.error("Missing valid seconds duration", ToolErrorCode.VALIDATION_ERROR)

        val message = arguments["message"]?.toString() ?: "Timer"

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ToolResult.success(
                mapOf(
                    "seconds" to seconds.toString(),
                    "message" to message,
                    "status" to "timer_set"
                )
            )
        } catch (e: Exception) {
            ToolResult.error("Failed to set timer: ${e.message}", ToolErrorCode.EXECUTION_ERROR)
        }
    }
}
