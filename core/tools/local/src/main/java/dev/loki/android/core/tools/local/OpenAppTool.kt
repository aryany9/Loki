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
        val packages = pm.getInstalledApplications(0)

        val matchingApp = packages.firstOrNull { appInfo ->
            val label = pm.getApplicationLabel(appInfo).toString()
            label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)
        }

        if (matchingApp == null) {
            return ToolResult.error("App '$appName' not found", ToolErrorCode.NOT_FOUND)
        }

        val launchIntent = pm.getLaunchIntentForPackage(matchingApp.packageName)
            ?: return ToolResult.error("App '${matchingApp.packageName}' cannot be launched", ToolErrorCode.EXECUTION_ERROR)

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        val label = pm.getApplicationLabel(matchingApp).toString()
        return ToolResult.success(
            mapOf(
                "app_name" to label,
                "package_name" to matchingApp.packageName,
                "status" to "opened"
            )
        )
    }
}
