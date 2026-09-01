package dev.loki.android.core.tools.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class CallContactTool : LocalTool {
    override val name: String = "call_contact"
    override val description: String = "Initiate a phone call to a named contact or phone number."
    override val parameters: Map<String, ToolParam> = mapOf(
        "phone_number" to ToolParam(ToolParamType.STRING, "Phone number or contact URI to call", required = true),
        "name" to ToolParam(ToolParamType.STRING, "Contact name if known", required = false)
    )
    override val requiredPermissions: List<String> = listOf(Manifest.permission.CALL_PHONE)
    override val requiresConfirmation: Boolean = true

    override fun describeAction(arguments: Map<String, Any?>): String {
        val name = arguments["name"]?.toString()?.trim()
            ?: arguments["contact_name"]?.toString()?.trim()
        val number = arguments["phone_number"]?.toString()?.trim()
            ?: arguments["number"]?.toString()?.trim()

        return when {
            !name.isNullOrBlank() && !number.isNullOrBlank() && name != number -> "Call $name at $number?"
            !name.isNullOrBlank() -> "Call $name?"
            !number.isNullOrBlank() -> "Call $number?"
            else -> "Place phone call?"
        }
    }

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val phoneNumber = arguments["phone_number"]?.toString()?.trim()
            ?: return ToolResult.error("Missing phone_number", ToolErrorCode.VALIDATION_ERROR)

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        return ToolResult.success(
            mapOf(
                "calling" to phoneNumber,
                "status" to "initiated"
            )
        )
    }
}
