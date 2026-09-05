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
    override val capability: String = "calling"
    override val description: String = "Initiate a phone call using a confirmed candidate_id or direct phone number. To call or find contacts by name, use lookup_contact first."
    override val parameters: Map<String, ToolParam> = mapOf(
        "candidate_id" to ToolParam(ToolParamType.STRING, "Candidate ID from contact lookup (e.g. 'c1')", required = false),
        "phone_number" to ToolParam(ToolParamType.STRING, "Phone number or contact URI to call", required = false),
        "name" to ToolParam(ToolParamType.STRING, "Contact name if known", required = false)
    )
    override val requiredPermissions: List<String> = listOf(Manifest.permission.CALL_PHONE)
    override val requiresConfirmation: Boolean = true

    override fun describeAction(arguments: Map<String, Any?>): String {
        val rawName = arguments["name"]?.toString()?.trim()
            ?: arguments["contact_name"]?.toString()?.trim()
        val rawNumber = arguments["phone_number"]?.toString()?.trim()
            ?: arguments["number"]?.toString()?.trim()

        val name = if (rawName.isNullOrBlank() || rawName.equals("null", ignoreCase = true) || rawName.equals("N/A", ignoreCase = true)) null else rawName
        val number = if (rawNumber.isNullOrBlank() || rawNumber.equals("null", ignoreCase = true) || rawNumber.equals("N/A", ignoreCase = true)) null else rawNumber

        return when {
            name != null && number != null && name != number -> "Calling $name at $number?"
            name != null -> "Calling $name?"
            number != null -> "Calling $number?"
            else -> "Place phone call?"
        }
    }

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val rawNumber = arguments["phone_number"]?.toString()?.trim()
        val phoneNumber = if (rawNumber.isNullOrBlank() || rawNumber.equals("null", ignoreCase = true) || rawNumber.equals("N/A", ignoreCase = true)) {
            return ToolResult.error("Cannot place call: missing or invalid phone number", ToolErrorCode.VALIDATION_ERROR)
        } else rawNumber

        val rawName = arguments["name"]?.toString()?.trim()
            ?: arguments["contact_name"]?.toString()?.trim()
        val displayName = if (!rawName.isNullOrBlank() && !rawName.equals("null", ignoreCase = true) && !rawName.equals("N/A", ignoreCase = true)) {
            rawName
        } else {
            phoneNumber
        }

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        return ToolResult.success(
            mapOf(
                "calling" to displayName,
                "phone_number" to phoneNumber,
                "name" to displayName,
                "status" to "initiated"
            )
        )
    }
}
