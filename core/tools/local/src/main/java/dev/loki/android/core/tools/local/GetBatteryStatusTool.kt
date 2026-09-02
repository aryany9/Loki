package dev.loki.android.core.tools.local

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolResult

class GetBatteryStatusTool : LocalTool {
    override val name: String = "get_battery_status"
    override val capability: String = "general"
    override val description: String = "Get the current device battery percentage and charging state."
    override val parameters: Map<String, ToolParam> = emptyMap()

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else level

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return ToolResult.success(
            mapOf(
                "percentage" to "$batteryPct%",
                "level" to "$batteryPct",
                "is_charging" to "$isCharging"
            )
        )
    }
}
