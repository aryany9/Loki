package dev.loki.android.core.tools.local

import android.app.ActivityManager
import android.content.Context
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolResult

class GetRamUsageTool : LocalTool {
    override val name: String = "get_ram_usage"
    override val capability: String = "device"
    override val description: String = "Get current system RAM memory usage statistics."
    override val parameters: Map<String, ToolParam> = emptyMap()
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return ToolResult.error("Activity service unavailable", ToolErrorCode.NOT_FOUND)

            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val totalMb = memInfo.totalMem / (1024 * 1024)
            val availableMb = memInfo.availMem / (1024 * 1024)
            val usedMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
            val usedPercent = if (memInfo.totalMem > 0) {
                ((memInfo.totalMem - memInfo.availMem).toDouble() / memInfo.totalMem.toDouble() * 100.0).toInt()
            } else {
                0
            }
            val thresholdMb = memInfo.threshold / (1024 * 1024)

            ToolResult.success(
                mapOf(
                    "total_mb" to totalMb.toString(),
                    "available_mb" to availableMb.toString(),
                    "used_mb" to usedMb.toString(),
                    "used_percent" to "$usedPercent%",
                    "low_memory" to memInfo.lowMemory.toString(),
                    "threshold_mb" to thresholdMb.toString()
                )
            )
        } catch (e: Exception) {
            ToolResult.error(
                message = e.message ?: "Failed to read RAM statistics",
                code = ToolErrorCode.EXECUTION_ERROR
            )
        }
    }
}
