package dev.loki.android.core.tools.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class DialNumberTool : LocalTool {
    override val name: String = "dial_number"
    override val description: String = "Open the phone dialer with a pre-filled phone number."
    override val parameters: Map<String, ToolParam> = mapOf(
        "phone_number" to ToolParam(ToolParamType.STRING, "The phone number to dial", required = true)
    )

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val phoneNumber = arguments["phone_number"]?.toString()?.trim()
            ?: return ToolResult.error("Missing phone_number", ToolErrorCode.VALIDATION_ERROR)

        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        return ToolResult.success(
            mapOf(
                "dialed" to phoneNumber,
                "status" to "dialer_opened"
            )
        )
    }
}
