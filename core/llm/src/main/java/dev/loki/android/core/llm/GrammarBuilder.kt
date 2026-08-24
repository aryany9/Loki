package dev.loki.android.core.llm

import android.content.Context
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.tools.Tool
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolRegistry
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

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
 * Caches compiled GBNF grammars based on the active toolset to avoid re-compiling across turns.
 */
object GrammarBuilder {

    private val grammarCache = ConcurrentHashMap<String, String>()

    fun buildFrom(
        toolRegistry: ToolRegistry,
        context: Context? = null,
        permissionManager: PermissionManager = PermissionManager()
    ): String {
        val tools = if (context != null) {
            toolRegistry.getAvailableTools(context, permissionManager)
        } else {
            toolRegistry.getAllTools()
        }
        return buildFromToolsList(tools)
    }

    fun buildFromToolsList(tools: List<Tool>): String {
        val cacheKey = tools.map { it.name }.sorted().joinToString(separator = ",")
        val cached = grammarCache[cacheKey]
        if (cached != null) {
            return cached
        }

        val toolDefs = tools.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters.mapValues { (_, param) ->
                    when (param.type) {
                        ToolParamType.STRING -> ParamType.STRING
                        ToolParamType.NUMBER -> ParamType.NUMBER
                        ToolParamType.BOOLEAN -> ParamType.BOOLEAN
                    }
                }
            )
        }
        val grammar = buildFromTools(toolDefs)
        if (grammar.isNotEmpty()) {
            grammarCache[cacheKey] = grammar
        }
        return grammar
    }

    fun clearCache() {
        grammarCache.clear()
    }

    fun buildFromTools(tools: List<ToolDefinition>): String {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonArray("oneOf") {
                // 1. Tool branch
                add(buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("tool") {
                            put("type", "string")
                            putJsonArray("enum") {
                                for (tool in tools) {
                                    add(kotlinx.serialization.json.JsonPrimitive(tool.name))
                                }
                            }
                        }
                        putJsonObject("arguments") {
                            put("type", "object")
                            putJsonObject("properties") {
                                for (tool in tools) {
                                    for ((pName, pType) in tool.parameters) {
                                        putJsonObject(pName) {
                                            put(
                                                "type", when (pType) {
                                                    ParamType.STRING -> "string"
                                                    ParamType.NUMBER -> "number"
                                                    ParamType.BOOLEAN -> "boolean"
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    putJsonArray("required") {
                        add(kotlinx.serialization.json.JsonPrimitive("tool"))
                        add(kotlinx.serialization.json.JsonPrimitive("arguments"))
                    }
                    put("additionalProperties", false)
                })

                // 2. Response branch
                add(buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("response") {
                            put("type", "string")
                        }
                    }
                    putJsonArray("required") {
                        add(kotlinx.serialization.json.JsonPrimitive("response"))
                    }
                    put("additionalProperties", false)
                })
            }
        }

        val schemaJson = schema.toString()
        return try {
            LlamaBridge.nativeJsonSchemaToGrammar(schemaJson)
        } catch (e: Throwable) {
            ""
        }
    }
}
