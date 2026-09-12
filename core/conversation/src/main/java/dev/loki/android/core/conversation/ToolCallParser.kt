package dev.loki.android.core.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface ParsedLlmResponse {
    data class ToolCall(val tool: String, val arguments: Map<String, Any?>, val language: String? = null) : ParsedLlmResponse
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
                    val parsed = parseJsonObject(element)
                    if (parsed != null) return parsed
                }
            } catch (_: Exception) {
                // Try fallback recovery below
            }
        }

        // If text contains markdown code blocks with extra text outside, reject as malformed ONLY if it contains a tool call
        if (trimmed.contains("```") && trimmed.contains("\"tool\":") && (!trimmed.startsWith("```") || !trimmed.endsWith("```"))) {
            return ParsedLlmResponse.Malformed(raw, "Expected one JSON object")
        }

        // If text contains markdown code blocks without any tool markers, treat as a direct prose/markdown response
        if (trimmed.contains("```") && !trimmed.contains("\"tool\":")) {
            return ParsedLlmResponse.DirectResponse(trimmed)
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

        // Fallback 2: ask_user format-repair fallback (bare tool-name near-miss for ask_user ONLY)
        val askUserRepair = tryRepairAskUser(trimmed)
        if (askUserRepair != null) {
            return askUserRepair
        }

        // Fallback 2.5: Embedded JSON block in natural language (e.g. conversational response with trailing tool call)
        val embeddedResult = tryParseEmbeddedJson(trimmed)
        if (embeddedResult != null) {
            return embeddedResult
        }

        // Fallback 3: Natural language response (not JSON format or code block)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("```") && !trimmed.contains("\"tool\":") && !trimmed.contains("\"response\":")) {
            var unquoted = trimmed
            while (unquoted.startsWith("\"") && unquoted.endsWith("\"") && unquoted.length >= 2) {
                unquoted = unquoted.substring(1, unquoted.length - 1).trim()
            }
            try {
                android.util.Log.i("ToolCallParser", "ToolCallParser fallback fired (natural language): rawLength=${raw.length}")
            } catch (_: Throwable) {}
            return ParsedLlmResponse.DirectResponse(unquoted)
        }

        return ParsedLlmResponse.Malformed(raw, "Expected one JSON object")
    }

    internal fun parseJsonObject(element: JsonObject): ParsedLlmResponse? {
        if (element.containsKey("tool")) {
            val toolName = element["tool"]?.jsonPrimitive?.content ?: ""
            val argsMap = mutableMapOf<String, Any?>()
            val argsElement = element["arguments"]
            if (argsElement is JsonObject) {
                argsElement.forEach { (key, value) ->
                    argsMap[key] = value.jsonPrimitive.content
                }
            } else if (toolName == "ask_user" && argsElement != null) {
                val textContent = try {
                    argsElement.jsonPrimitive.content
                } catch (_: Throwable) {
                    argsElement.toString()
                }
                argsMap["text"] = textContent
                try {
                    android.util.Log.i("ToolCallParser", "[ToolCallParser] ask_user arguments-as-string repair applied")
                } catch (_: Throwable) {}
            }

            if (toolName == "ask_user" && !argsMap.containsKey("text") && element.containsKey("text")) {
                val topLevelText = element["text"]?.jsonPrimitive?.content ?: ""
                argsMap["text"] = topLevelText
                try {
                    android.util.Log.i("ToolCallParser", "[ToolCallParser] ask_user arguments-as-string repair applied")
                } catch (_: Throwable) {}
            }

            val lang = element["language"]?.jsonPrimitive?.contentOrNull
                ?: (argsElement as? JsonObject)?.get("language")?.jsonPrimitive?.contentOrNull

            return ParsedLlmResponse.ToolCall(toolName, argsMap, language = lang)
        } else if (element.containsKey("response")) {
            val resp = element["response"]?.jsonPrimitive?.content ?: ""
            return ParsedLlmResponse.DirectResponse(resp)
        }
        return null
    }

    private val JSON_MARKER_REGEX = Regex("""\{\s*"((?:tool)|(?:response))"\s*:""")

    internal fun tryParseEmbeddedJson(trimmed: String): ParsedLlmResponse? {
        val match = JSON_MARKER_REGEX.find(trimmed) ?: return null
        val braceStart = match.range.first
        val textBefore = trimmed.substring(0, braceStart).trim()

        var depth = 0
        var inString = false
        var escape = false
        var braceEnd = -1
        for (i in braceStart until trimmed.length) {
            val c = trimmed[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        braceEnd = i
                        break
                    }
                }
            }
        }

        if (braceEnd <= braceStart) {
            braceEnd = trimmed.lastIndexOf('}')
        }

        if (braceEnd > braceStart) {
            val jsonCandidate = trimmed.substring(braceStart, braceEnd + 1)
            try {
                val element = json.parseToJsonElement(jsonCandidate) as? JsonObject
                if (element != null) {
                    val parsed = parseJsonObject(element)
                    if (parsed != null) {
                        try {
                            android.util.Log.i("ToolCallParser", "ToolCallParser fallback 2.5 fired (embedded JSON): rawLength=${trimmed.length}, textBeforeLength=${textBefore.length}")
                        } catch (_: Throwable) {}
                        return when (parsed) {
                            is ParsedLlmResponse.ToolCall -> {
                                if (parsed.tool == "ask_user") {
                                    val question = parsed.arguments["text"]?.toString()?.trim() ?: ""
                                    val combinedText = when {
                                        textBefore.isBlank() -> question
                                        question.isBlank() -> textBefore
                                        textBefore.endsWith(question, ignoreCase = true) -> textBefore
                                        else -> "$textBefore\n\n$question"
                                    }
                                    ParsedLlmResponse.ToolCall("ask_user", mapOf("text" to combinedText))
                                } else {
                                    parsed
                                }
                            }
                            is ParsedLlmResponse.DirectResponse -> {
                                val resp = parsed.text.trim()
                                val combinedText = when {
                                    textBefore.isBlank() -> resp
                                    resp.isBlank() -> textBefore
                                    resp.startsWith(textBefore, ignoreCase = true) -> resp
                                    textBefore.endsWith(resp, ignoreCase = true) -> textBefore
                                    else -> "$textBefore\n\n$resp"
                                }
                                ParsedLlmResponse.DirectResponse(combinedText)
                            }
                            is ParsedLlmResponse.Malformed -> null
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore and fall through to textBefore check below
            }
        }

        if (textBefore.isNotBlank()) {
            try {
                android.util.Log.i("ToolCallParser", "ToolCallParser fallback 2.5 fired (recovering pre-JSON text): textLength=${textBefore.length}")
            } catch (_: Throwable) {}
            return ParsedLlmResponse.DirectResponse(textBefore)
        }

        return null
    }

    internal fun tryRepairAskUser(trimmed: String): ParsedLlmResponse.ToolCall? {
        if (!trimmed.startsWith("ask_user", ignoreCase = true)) return null
        if (trimmed.length > 8 && !trimmed[8].isWhitespace() && trimmed[8] != ':' && trimmed[8] != '(') {
            return null
        }

        var question = if (trimmed.length > 8) trimmed.substring(8).trim() else ""
        if (question.startsWith(":")) {
            question = question.substring(1).trim()
        }
        if (question.startsWith("(") && question.endsWith(")")) {
            question = question.substring(1, question.length - 1).trim()
        }
        while ((question.startsWith("\"") && question.endsWith("\"")) || (question.startsWith("'") && question.endsWith("'"))) {
            if (question.length < 2) break
            question = question.substring(1, question.length - 1).trim()
        }
        try {
            android.util.Log.i("ToolCallParser", "[ToolCallParser] ask_user format-repair applied (bare tool-name near-miss)")
        } catch (_: Throwable) {}
        return ParsedLlmResponse.ToolCall("ask_user", mapOf("text" to question))
    }

    /**
     * Cleans streaming text tokens for real-time UI display.
     * - Returns null if the partial string represents a tool call (starts with tool JSON),
     *   so raw JSON does not leak into the conversational text bubble while streaming.
     * - Unwraps {"response": "..."} JSON envelopes on-the-fly to stream natural text
     *   without flashing raw JSON or spurious Markdown code fences.
     * - Preserves genuine Markdown responses and code blocks unmodified.
     */
    fun cleanStreamingPartial(raw: String): String? {
        val trimmed = raw.trimStart()
        // 1. Tool calls -> hide raw JSON from message text
        if (trimmed.startsWith("{\"tool\"") ||
            trimmed.startsWith("{\n  \"tool\"") ||
            trimmed.startsWith("```json\n{\"tool\"") ||
            trimmed.startsWith("```json\n{\n  \"tool\"")
        ) {
            return null
        }

        // 2. Embedded {"response": "..."} JSON envelope -> unwrap for streaming
        val responsePrefixRegex = Regex("""^(?:```json\s*)?\{\s*"response"\s*:\s*"""")
        val match = responsePrefixRegex.find(trimmed)
        if (match != null) {
            var content = trimmed.substring(match.range.last + 1)
            // Clean trailing JSON closing tokens if present at the end of streaming
            if (content.endsWith("```")) content = content.removeSuffix("```").trimEnd()
            if (content.endsWith("}")) content = content.removeSuffix("}").trimEnd()
            if (content.endsWith("\"")) content = content.removeSuffix("\"")
            return content.replace("\\n", "\n").replace("\\\"", "\"")
        }

        return raw
    }
}
