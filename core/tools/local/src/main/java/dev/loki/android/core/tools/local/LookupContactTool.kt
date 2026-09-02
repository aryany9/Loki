package dev.loki.android.core.tools.local

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ContactMatch(val id: String = "", val name: String, val number: String)

class LookupContactTool(
    private val queryOverride: ((String) -> List<Pair<String, String>>)? = null
) : LocalTool {
    override val name: String = "lookup_contact"
    override val capability: String = "calling"
    override val description: String = "Search contacts by name and retrieve phone numbers."
    override val parameters: Map<String, ToolParam> = mapOf(
        "query" to ToolParam(ToolParamType.STRING, "Contact name or prefix to search for", required = true)
    )
    override val requiredPermissions: List<String> = listOf(Manifest.permission.READ_CONTACTS)

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val query = arguments["query"]?.toString()?.trim()
            ?: return ToolResult.error("Missing query parameter", ToolErrorCode.VALIDATION_ERROR)

        val rawPairs = if (queryOverride != null) {
            queryOverride.invoke(query)
        } else {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            val results = mutableListOf<Pair<String, String>>()
            try {
                val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
                cursor?.use {
                    val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (it.moveToNext()) {
                        val name = it.getString(nameIdx)?.trim().orEmpty()
                        val number = it.getString(numIdx)?.trim().orEmpty()
                        results.add(name to number)
                    }
                }
            } catch (e: Throwable) {
                // If content resolver fails or throws in test environment
            }
            results
        }

        val matches = mutableListOf<ContactMatch>()
        val seen = mutableSetOf<Pair<String, String>>()
        var idCounter = 1

        for ((name, number) in rawPairs) {
            if (matches.size >= 10) break
            val trimmedName = name.trim()
            val trimmedNumber = number.trim()
            val normName = trimmedName.lowercase()
            val normNumber = trimmedNumber.replace(Regex("[^0-9]"), "")
            if (normName.isNotEmpty() && normNumber.isNotEmpty()) {
                if (seen.add(normName to normNumber)) {
                    matches.add(ContactMatch(id = "c$idCounter", name = trimmedName, number = trimmedNumber))
                    idCounter++
                }
            }
        }

        return if (matches.isEmpty()) {
            ToolResult.error("No contacts found matching '$query'", ToolErrorCode.NOT_FOUND)
        } else {
            val contactsJson = Json.encodeToString(matches)
            val dataMap = mutableMapOf(
                "contacts" to contactsJson,
                "count" to matches.size.toString()
            )
            if (matches.size == 1) {
                dataMap["id"] = matches[0].id
                dataMap["name"] = matches[0].name
                dataMap["number"] = matches[0].number
            }
            ToolResult.success(dataMap)
        }
    }
}
