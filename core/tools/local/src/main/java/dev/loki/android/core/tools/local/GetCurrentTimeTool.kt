package dev.loki.android.core.tools.local

import android.content.Context
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GetCurrentTimeTool : LocalTool {
    override val name: String = "get_current_time"
    override val description: String = "Get the current time, day, and date from the device clock."
    override val parameters: Map<String, ToolParam> = emptyMap()

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val now = Date()
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

        val currentTime = timeFormat.format(now)
        val currentDate = dateFormat.format(now)

        return ToolResult.success(
            mapOf(
                "time" to currentTime,
                "date" to currentDate,
                "formatted" to "$currentTime on $currentDate"
            )
        )
    }
}
