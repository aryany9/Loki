package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.tools.Tool
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates GBNF grammars constraining LLM output to valid tool calls from the scoped visible set
 * or a conversational response.
 *
 * Regenerates when the scoped visible tool set changes.
 */
object GrammarBuilder {

    private val grammarCache = ConcurrentHashMap<String, String>()

    fun buildFrom(
        toolRegistry: ToolRegistry,
        context: Context? = null,
        permissionManager: PermissionManager = PermissionManager(),
        activeCapability: String? = null,
        advancingTool: String? = null,
        taskState: dev.loki.android.core.tools.TaskStateGate? = null
    ): String {
        val tools = if (context != null) {
            toolRegistry.getAvailableTools(
                context = context,
                permissionManager = permissionManager,
                activeCapability = activeCapability,
                advancingTool = advancingTool,
                taskState = taskState
            )
        } else {
            toolRegistry.getAllTools().filter { tool ->
                val capMatches = activeCapability == null || tool.capability == "general" || tool.capability == activeCapability
                val internalMatches = !tool.isInternal || tool.name == advancingTool

                // State-scoped gating for context-less path (tests)
                val restrictTo = taskState?.restrictToTool
                val stateRestrictionPasses = restrictTo == null || tool.name == restrictTo || tool.capability == "general"
                val hideTools = taskState?.hiddenTools ?: setOfNotNull(taskState?.hiddenTool)
                val stateHidePasses = tool.name !in hideTools

                capMatches && internalMatches && stateRestrictionPasses && stateHidePasses
            }
        }
        return buildFrom(tools)
    }

    fun buildFrom(tools: List<Tool>): String {
        val cacheKey = tools.map { it.name }.sorted().joinToString(separator = ",")
        val cached = grammarCache[cacheKey]
        if (cached != null) {
            return cached
        }

        val grammar = generateGbnf(tools)
        if (grammar.isNotEmpty()) {
            grammarCache[cacheKey] = grammar
        }
        return grammar
    }

    fun clearCache() {
        grammarCache.clear()
    }

    private fun generateGbnf(tools: List<Tool>): String {
        if (tools.isEmpty()) {
            return """
                root ::= response
                response ::= "{\"response\":" space string "}"
                string ::= "\"" [^\"]* "\""
                space ::= [ \t\n\r]*
            """.trimIndent()
        }

        val toolNameLiterals = tools.joinToString(" | ") { "\"\\\"${it.name}\\\"\"" }

        val sb = StringBuilder()
        sb.append("root ::= tool_call | response\n")
        sb.append("response ::= \"{\" space \"\\\"response\\\"\" space \":\" space string space \"}\"\n")
        sb.append("tool_call ::= \"{\" space \"\\\"tool\\\"\" space \":\" space tool_name space \",\" space \"\\\"arguments\\\"\" space \":\" space \"{\" space tool_args space \"}\" space \"}\"\n")
        sb.append("tool_name ::= $toolNameLiterals\n")
        sb.append("tool_args ::= (arg_pair (\",\" space arg_pair)*)?\n")
        sb.append("arg_pair ::= string space \":\" space value\n")
        sb.append("value ::= string | number | boolean\n")
        sb.append("string ::= \"\\\"\" ([^\"\\\\] | \"\\\\\" .)* \"\\\"\"\n")
        sb.append("number ::= [0-9]+ (\".\" [0-9]+)?\n")
        sb.append("boolean ::= \"true\" | \"false\"\n")
        sb.append("space ::= [ \\t\\n\\r]*\n")

        return sb.toString()
    }
}
