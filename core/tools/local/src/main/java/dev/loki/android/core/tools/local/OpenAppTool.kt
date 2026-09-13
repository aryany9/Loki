package dev.loki.android.core.tools.local

import android.content.Context
import android.content.Intent
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class OpenAppTool : LocalTool {
    override val name: String = "open_app"
    override val capability: String = "apps"
    override val description: String = "Open an installed application by its name."
    override val parameters: Map<String, ToolParam> = mapOf(
        "app_name" to ToolParam(ToolParamType.STRING, "The common or display name of the application to open", required = true)
    )

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val appName = arguments["app_name"]?.toString()?.trim()
            ?: return ToolResult.error("Missing app_name", ToolErrorCode.VALIDATION_ERROR)

        val pm = context.packageManager

        // Query launcher activities first to prioritize user-visible launcher apps
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherApps = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (_: Throwable) {
            emptyList()
        }

        var targetPackage: String? = launcherApps.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(pm).toString()
            label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)
        }?.activityInfo?.packageName

        var targetLabel: String? = null

        if (targetPackage == null) {
            // Fallback to installed applications list
            val packages = try {
                pm.getInstalledApplications(0)
            } catch (_: Throwable) {
                emptyList()
            }
            val matchingApp = packages.firstOrNull { appInfo ->
                val label = pm.getApplicationLabel(appInfo).toString()
                label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)
            }
            targetPackage = matchingApp?.packageName
            targetLabel = matchingApp?.let { pm.getApplicationLabel(it).toString() }
        } else {
            val appInfo = try {
                pm.getApplicationInfo(targetPackage, 0)
            } catch (_: Throwable) {
                null
            }
            targetLabel = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: appName
        }

        if (targetPackage == null) {
            return ToolResult.error("App '$appName' not found", ToolErrorCode.NOT_FOUND)
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            ?: return ToolResult.error("App '$targetPackage' cannot be launched", ToolErrorCode.EXECUTION_ERROR)

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        return ToolResult.success(
            mapOf(
                "app_name" to (targetLabel ?: appName),
                "package_name" to targetPackage,
                "status" to "opened"
            )
        )
    }
}
