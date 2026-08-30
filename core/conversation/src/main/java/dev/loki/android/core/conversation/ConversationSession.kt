package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.tools.Tool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolExecutionResult
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import dev.loki.android.core.voice.tts.TtsEngine
import dev.loki.android.core.models.AgentConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * ConversationSession executes a ReAct-style agent loop for a specific conversation context.
 * Can be persistent (for multi-turn Chat UI) or ephemeral (for hands-free Voice turns).
 */
class ConversationSession(
    private val context: Context,
    val llmEngine: LlmEngine,
    val toolRegistry: ToolRegistry,
    val ttsEngine: TtsEngine? = null,
    val conversationContext: ConversationContext = ConversationContext(),
    val permissionManager: PermissionManager = PermissionManager(),
    val agentConfig: AgentConfig = AgentConfig(),
    private val maxIterations: Int = 5,
    val conversationStore: ConversationStore? = null,
    val conversationId: String? = null,
    val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {

    private suspend fun recordTurn(turn: ConversationTurn) {
        conversationContext.append(turn)
        if (conversationStore != null && conversationId != null) {
            try {
                conversationStore.appendTurn(conversationId, turn)
            } catch (e: Throwable) {
                android.util.Log.w("ConversationSession", "Non-fatal: Failed to persist turn", e)
            }
        }
    }

    fun processUtterance(
        userInput: String,
        audioBytes: ByteArray? = null,
        enableTts: Boolean = true,
        source: String = "TEXT"
    ): Flow<ConversationEvent> = channelFlow {
        val turnId = TurnLogger.newTurnId()
        TurnLogger.logTurnStart(turnId, source)

        val hasAudio = audioBytes != null && audioBytes.isNotEmpty()
        if (userInput.isBlank() && !hasAudio) {
            TurnLogger.logError(turnId, "Empty user input received")
            send(ConversationEvent.Error("Empty user input"))
            return@channelFlow
        }

        if (isSimpleGreeting(userInput) && !hasAudio) {
            val response = "Hello! How can I help you?"
            recordTurn(ConversationTurn.User(userInput))
            recordTurn(ConversationTurn.Assistant(response))
            send(ConversationEvent.Completed(response))
            return@channelFlow
        }

        if (source == "VOICE" && userInput.isNotBlank()) {
            TurnLogger.logTranscript(turnId, userInput)
        }

        val displayInput = if (userInput.isNotBlank()) userInput else if (hasAudio) "[Voice Audio]" else ""
        recordTurn(ConversationTurn.User(displayInput))
        send(ConversationEvent.Thinking(displayInput))

        var iterations = 0
        var lastToolResult: ToolResult? = null
        var finalResponseText = ""
        var correctiveRetryUsed = false

        val availableTools = toolRegistry.getAvailableTools(context, permissionManager)
        val disabledTools = toolRegistry.getDisabledTools(context, permissionManager)
        TurnLogger.logTools(turnId, availableTools.size, disabledTools.size)

        val systemPrompt = buildSystemPrompt(availableTools, disabledTools)

        // Initialize persistent native conversation with system prompt ONCE per logical conversation session
        if (conversationContext.getTurns().size <= 1) {
            val effectiveConfig = agentConfig.copy(systemInstruction = systemPrompt)
            val started = llmEngine.startConversation(effectiveConfig)
            TurnLogger.logTurnStart(turnId, "STATEFUL_INIT (success=$started)")
        }

        try {
            var currentTurnPrompt = userInput

            while (iterations < maxIterations) {
                iterations++

                val promptToSend = if (correctiveRetryUsed) {
                    "$currentTurnPrompt\nReturn exactly one JSON object and nothing else. Do not use Markdown, explanations, or additional turns."
                } else {
                    currentTurnPrompt
                }

                // DIAGNOSTIC (Requirement 8): Log application history and prompt stats before generation
                val appTurnsCount = conversationContext.getTurns().size
                val appTokenEst = conversationContext.estimateTokenCount()
                android.util.Log.i("ConversationSession", "[Loki/Diagnostic] Generation iteration $iterations:")
                android.util.Log.i("ConversationSession", "[Loki/Diagnostic]   app history turns count   = $appTurnsCount")
                android.util.Log.i("ConversationSession", "[Loki/Diagnostic]   app token count (est)     = $appTokenEst")
                android.util.Log.i("ConversationSession", "[Loki/Diagnostic]   new prompt char length    = ${promptToSend.length}")

                TurnLogger.logPrompt(turnId, promptToSend)

                val maxTokens = agentConfig.generationConfig.maxOutputTokens ?: 256
                val cumulativePartial = StringBuilder()
                val llmResult = llmEngine.generate(
                    prompt = promptToSend,
                    audioBytes = if (iterations == 1) audioBytes else null,
                    maxTokens = maxTokens,
                    onToken = { token ->
                        cumulativePartial.append(token)
                        trySend(ConversationEvent.GeneratingToken(cumulativePartial.toString()))
                    }
                )

                if (llmResult.isFailure) {
                    val errorMsg = llmResult.exceptionOrNull()?.message ?: "LLM inference failed"
                    TurnLogger.logError(turnId, errorMsg, llmResult.exceptionOrNull())
                    send(ConversationEvent.Error(errorMsg))
                    return@channelFlow
                }

                val rawOutput = llmResult.getOrNull() ?: ""
                TurnLogger.logLlmOutput(turnId, rawOutput)

                val parsed = ToolCallParser.parse(rawOutput)
                TurnLogger.logParse(turnId, parsed)

                when (parsed) {
                    is ParsedLlmResponse.ToolCall -> {
                        send(ConversationEvent.ToolExecuting(parsed.tool, parsed.arguments))
                        recordTurn(
                            ConversationTurn.ToolCall(
                                tool = parsed.tool,
                                arguments = parsed.arguments.mapValues { it.value?.toString() ?: "" }
                            )
                        )

                        val execResult = toolRegistry.executeDetailed(
                            context = context,
                            name = parsed.tool,
                            arguments = parsed.arguments,
                            permissionManager = permissionManager
                        )

                        when (execResult) {
                            is ToolExecutionResult.Success -> {
                                val result = execResult.toolResult
                                lastToolResult = result
                                TurnLogger.logToolExecution(turnId, parsed.tool, true, result.data.toString())
                                send(ConversationEvent.ToolExecuted(parsed.tool, result))

                                recordTurn(
                                    ConversationTurn.ToolExecutionResult(
                                        tool = parsed.tool,
                                        result = result
                                    )
                                )

                                val fastResponse = formatFastPathResponse(parsed.tool, result)
                                if (fastResponse != null) {
                                    finalResponseText = fastResponse
                                    recordTurn(ConversationTurn.Assistant(finalResponseText))
                                    break
                                }

                                // For next ReAct iteration, send ONLY the tool execution result message
                                currentTurnPrompt = "Tool result for ${parsed.tool}: ${result.data}"
                            }
                            is ToolExecutionResult.PermissionRequired -> {
                                TurnLogger.logPermissionCheck(turnId, execResult.permission, execResult.state.name)
                                val toolError = ToolResult.error(
                                    "Missing permission: ${execResult.permission}",
                                    ToolErrorCode.PERMISSION_DENIED
                                )
                                lastToolResult = toolError
                                send(ConversationEvent.ToolExecuted(parsed.tool, toolError))

                                recordTurn(
                                    ConversationTurn.ToolExecutionResult(
                                        tool = parsed.tool,
                                        result = toolError
                                    )
                                )

                                finalResponseText = "I need the ${execResult.permission.substringAfterLast('.')} permission to do that. Please enable it in the permissions screen."
                                recordTurn(ConversationTurn.Assistant(finalResponseText))
                                break
                            }
                            is ToolExecutionResult.Error -> {
                                val result = execResult.toolResult
                                lastToolResult = result
                                TurnLogger.logToolExecution(turnId, parsed.tool, false, result.error ?: "Unknown error")
                                send(ConversationEvent.ToolExecuted(parsed.tool, result))

                                recordTurn(
                                    ConversationTurn.ToolExecutionResult(
                                        tool = parsed.tool,
                                        result = result
                                    )
                                )

                                finalResponseText = formatErrorResponse(parsed.tool, result)
                                recordTurn(ConversationTurn.Assistant(finalResponseText))
                                break
                            }
                        }
                    }

                    is ParsedLlmResponse.DirectResponse -> {
                        finalResponseText = parsed.text
                        recordTurn(ConversationTurn.Assistant(finalResponseText))
                        break
                    }

                    is ParsedLlmResponse.Malformed -> {
                        TurnLogger.logError(turnId, "Malformed LLM response: ${parsed.raw} (${parsed.error})")
                        if (!correctiveRetryUsed) {
                            correctiveRetryUsed = true
                            continue
                        }
                        finalResponseText = "I couldn't determine that request. Please try again."
                        recordTurn(ConversationTurn.Assistant(finalResponseText))
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            TurnLogger.logCancel(turnId, "Session cancelled")
            throw e
        } catch (e: Throwable) {
            TurnLogger.logError(turnId, "Session execution failed", e)
            send(ConversationEvent.Error(e.message ?: "Execution error"))
            return@channelFlow
        }

        if (finalResponseText.isEmpty()) {
            finalResponseText = "Task completed."
        }

        TurnLogger.logFinalResponse(turnId, finalResponseText)

        if (enableTts && ttsEngine != null) {
            send(ConversationEvent.Speaking(finalResponseText))
            ttsEngine.speak(finalResponseText)
        }

        send(ConversationEvent.Completed(finalResponseText, lastToolResult))
    }.flowOn(ioDispatcher)

    private fun buildSystemPrompt(
        availableTools: List<Tool>,
        disabledTools: List<Pair<Tool, String>>
    ): String {
        val sb = StringBuilder()
        sb.append("You are Loki, a private offline Android assistant running on the user's device. You operate entirely on-device with privacy and safety as highest priority.\n\n")

        val customInstruction = agentConfig.systemInstruction.trim()
        if (customInstruction.isNotBlank() && customInstruction != AgentConfig.DEFAULT_SYSTEM_PROMPT.trim()) {
            sb.append("Additional Instructions:\n")
            sb.append(customInstruction)
            sb.append("\n\n")
        }

        if (availableTools.isNotEmpty()) {
            sb.append("Available tools (respond with JSON {\"tool\": \"name\", \"arguments\": {...}}):\n")
            for (tool in availableTools) {
                val params = if (tool.parameters.isNotEmpty()) {
                    tool.parameters.entries.joinToString(prefix = "(", postfix = ")") { "${it.key}: ${it.value.type.name.lowercase()}" }
                } else "()"
                sb.append("- ${tool.name}$params: ${tool.description}\n")
            }
            sb.append("\n")
        }

        if (disabledTools.isNotEmpty()) {
            sb.append("Disabled tools (permission not yet granted — explain to user if requested):\n")
            for ((tool, perm) in disabledTools) {
                val permName = perm.substringAfterLast('.')
                sb.append("- ${tool.name}: requires $permName permission\n")
            }
            sb.append("\n")
        }

        sb.append("Always output JSON: {\"tool\": \"tool_name\", \"arguments\": {...}} or {\"response\": \"conversational answer\"}.")
        return sb.toString()
    }

    private fun formatErrorResponse(toolName: String, result: ToolResult): String {
        val err = result.error ?: "Action failed."
        return when {
            err.contains("permission", ignoreCase = true) ->
                "I need permission to do that. Please grant it in the permissions setup."
            err.contains("not found", ignoreCase = true) ->
                "I couldn't find the requested item."
            else -> err
        }
    }

    private fun formatFastPathResponse(toolName: String, result: ToolResult): String? {
        if (!result.success) return null
        val data = result.data ?: return null

        return when (toolName) {
            "get_current_time" -> data["formatted"] ?: data["time"]?.let { "The time is $it" }
            "get_battery_status" -> data["percentage"]?.let { "Battery is at $it" }
            "open_app" -> data["app_name"]?.let { "Opening $it" }
            "set_timer" -> data["seconds"]?.let { "Timer set for $it seconds" }
            "set_alarm" -> "Alarm set for ${data["hour"]}:${data["minute"]}"
            "media_control" -> "Media command sent"
            "call_contact" -> "Calling ${data["calling"]}"
            "dial_number" -> "Opening dialer for ${data["dialed"]}"
            else -> null
        }
    }

    fun clear() {
        conversationContext.clear()
    }

    fun cancel() {
        llmEngine.cancel()
        ttsEngine?.stop()
    }

    private fun isSimpleGreeting(input: String): Boolean {
        return input.trim().lowercase() in setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening")
    }
}
