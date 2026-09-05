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
        val jsonText = when {
            trimmed.startsWith("```") && trimmed.endsWith("```") -> {
                trimmed.removePrefix("```").removeSuffix("```")
                    .removePrefix("json").trim()
            }
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
            else -> null
        }

        if (jsonText != null) {
            try {
                val element = json.parseToJsonElement(jsonText) as? JsonObject
                if (element != null) {
                    if (element.containsKey("tool")) {
                        val toolName = element["tool"]?.jsonPrimitive?.content ?: ""
                        val argsMap = mutableMapOf<String, Any?>()
                        val argsObj = element["arguments"] as? JsonObject
                        argsObj?.forEach { (key, value) ->
                            argsMap[key] = value.jsonPrimitive.content
                        }
                        return ParsedLlmResponse.ToolCall(toolName, argsMap)
                    } else if (element.containsKey("response")) {
                        val resp = element["response"]?.jsonPrimitive?.content ?: ""
                        return ParsedLlmResponse.DirectResponse(resp)
                    }
                }
            } catch (_: Exception) {
                // Try fallback recovery below
            }
        }

        // If text contains markdown code blocks with extra text outside, reject as malformed
        if (trimmed.contains("```") && (!trimmed.startsWith("```") || !trimmed.endsWith("```"))) {
            return ParsedLlmResponse.Malformed(raw, "Expected one JSON object")
        }

        // Fallback 1: Truncated JSON response string {"response": "..."
        val responsePrefix = """{"response":""""
        if (trimmed.startsWith(responsePrefix) && !trimmed.contains("```")) {
            val content = trimmed.removePrefix(responsePrefix)
                .removeSuffix("\"}")
                .removeSuffix("\"")
                .removeSuffix("}")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
            if (content.isNotBlank()) {
                try {
                    android.util.Log.i("ToolCallParser", "ToolCallParser fallback 1 fired (truncated response): rawLength=${raw.length}")
                } catch (_: Throwable) {}
                return ParsedLlmResponse.DirectResponse(content)
            }
        }

        // Fallback 2: Natural language response (not JSON format or code block)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("```") && !trimmed.contains("\"tool\":") && !trimmed.contains("\"response\":")) {
            var unquoted = trimmed
            while (unquoted.startsWith("\"") && unquoted.endsWith("\"") && unquoted.length >= 2) {
                unquoted = unquoted.substring(1, unquoted.length - 1).trim()
            }
            try {
                android.util.Log.i("ToolCallParser", "ToolCallParser fallback 2 fired (natural language): rawLength=${raw.length}")
            } catch (_: Throwable) {}
            return ParsedLlmResponse.DirectResponse(unquoted)
        }

        return ParsedLlmResponse.Malformed(raw, "Expected one JSON object")
    }
}
