package dev.loki.android.core.tools.local

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class LookupContactTool : LocalTool {
    override val name: String = "lookup_contact"
    override val description: String = "Search contacts by name and retrieve phone numbers."
    override val parameters: Map<String, ToolParam> = mapOf(
        "query" to ToolParam(ToolParamType.STRING, "Contact name or prefix to search for", required = true)
    )
    override val requiredPermissions: List<String> = listOf(Manifest.permission.READ_CONTACTS)

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val query = arguments["query"]?.toString()?.trim()
            ?: return ToolResult.error("Missing query parameter", ToolErrorCode.VALIDATION_ERROR)

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        val matches = mutableListOf<Pair<String, String>>()
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx)
                val number = it.getString(numIdx)
                matches.add(name to number)
            }
        }

        return if (matches.isEmpty()) {
            ToolResult.error("No contacts found matching '$query'", ToolErrorCode.NOT_FOUND)
        } else {
            val first = matches.first()
            ToolResult.success(
                mapOf(
                    "name" to first.first,
                    "number" to first.second,
                    "count" to matches.size.toString()
                )
            )
        }
    }
}
