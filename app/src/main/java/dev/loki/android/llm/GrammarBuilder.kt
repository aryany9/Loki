package dev.loki.android.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * ToolSchema defines a minimal tool definition for grammar construction.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ParamType> = emptyMap()
)

enum class ParamType {
    STRING,
    NUMBER,
    BOOLEAN
}

/**
 * GrammarBuilder converts tool definitions into GBNF grammars using llama.cpp's native JSON schema compiler.
 */
object GrammarBuilder {

    /**
     * Build GBNF grammar from tools using llama.cpp's native json_schema_to_grammar.
     */
    fun buildFromTools(tools: List<ToolDefinition>): String {
        val schema = JSONObject()
        schema.put("type", "object")

        val oneOf = JSONArray()

        // 1. Tool-calling schema option
        val toolBranch = JSONObject()
        toolBranch.put("type", "object")

        val toolProps = JSONObject()

        // tool name enum
        val toolEnum = JSONObject()
        toolEnum.put("type", "string")
        val toolNames = JSONArray()
        for (tool in tools) {
            toolNames.put(tool.name)
        }
        toolEnum.put("enum", toolNames)
        toolProps.put("tool", toolEnum)

        // arguments schema
        val argsObj = JSONObject()
        argsObj.put("type", "object")
        val argsProps = JSONObject()
        for (tool in tools) {
            for ((paramName, paramType) in tool.parameters) {
                val pObj = JSONObject()
                pObj.put(
                    "type", when (paramType) {
                        ParamType.STRING -> "string"
                        ParamType.NUMBER -> "number"
                        ParamType.BOOLEAN -> "boolean"
                    }
                )
                argsProps.put(paramName, pObj)
            }
        }
        argsObj.put("properties", argsProps)
        toolProps.put("arguments", argsObj)

        toolBranch.put("properties", toolProps)
        val toolRequired = JSONArray().apply {
            put("tool")
            put("arguments")
        }
        toolBranch.put("required", toolRequired)
        toolBranch.put("additionalProperties", false)

        oneOf.put(toolBranch)

        // 2. Direct response option
        val responseBranch = JSONObject()
        responseBranch.put("type", "object")
        val respProps = JSONObject()
        val respString = JSONObject().apply { put("type", "string") }
        respProps.put("response", respString)
        responseBranch.put("properties", respProps)
        val respRequired = JSONArray().apply { put("response") }
        responseBranch.put("required", respRequired)
        responseBranch.put("additionalProperties", false)

        oneOf.put(responseBranch)

        schema.put("oneOf", oneOf)

        val schemaJson = schema.toString()
        return LlamaBridge.nativeJsonSchemaToGrammar(schemaJson)
    }
}
