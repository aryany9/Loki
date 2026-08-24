package dev.loki.android.core.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ParsedLlmResponse {
    data class ToolCall(val tool: String, val arguments: Map<String, Any?>) : ParsedLlmResponse
    data class DirectResponse(val text: String) : ParsedLlmResponse
    data class Malformed(val raw: String, val error: String) : ParsedLlmResponse
}

object ToolCallParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): ParsedLlmResponse {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            return ParsedLlmResponse.DirectResponse(trimmed)
        }

        return try {
            val element = json.parseToJsonElement(trimmed) as? JsonObject
                ?: return ParsedLlmResponse.DirectResponse(trimmed)

            if (element.containsKey("tool")) {
                val toolName = element["tool"]?.jsonPrimitive?.content ?: ""
                val argsMap = mutableMapOf<String, Any?>()
                val argsObj = element["arguments"] as? JsonObject
                argsObj?.forEach { (key, value) ->
                    argsMap[key] = value.jsonPrimitive.content
                }
                ParsedLlmResponse.ToolCall(toolName, argsMap)
            } else if (element.containsKey("response")) {
                val resp = element["response"]?.jsonPrimitive?.content ?: ""
                ParsedLlmResponse.DirectResponse(resp)
            } else {
                ParsedLlmResponse.Malformed(raw, "JSON contains neither 'tool' nor 'response'")
            }
        } catch (e: Exception) {
            ParsedLlmResponse.Malformed(raw, e.message ?: "Invalid JSON")
        }
    }
}
