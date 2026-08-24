package dev.loki.android.core.conversation

import android.content.Context
import android.util.Log
import dev.loki.android.core.llm.GrammarBuilder
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import dev.loki.android.core.voice.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed interface ConversationEvent {
    data class Thinking(val query: String) : ConversationEvent
    data class GeneratingToken(val partial: String) : ConversationEvent
    data class ToolExecuting(val toolName: String, val args: Map<String, Any?>) : ConversationEvent
    data class ToolExecuted(val toolName: String, val result: ToolResult) : ConversationEvent
    data class Speaking(val text: String) : ConversationEvent
    data class Completed(val finalResponse: String, val toolResult: ToolResult? = null) : ConversationEvent
    data class Error(val message: String) : ConversationEvent
}

/**
 * ConversationManager executes a bounded ReAct-style agent loop:
 * user input -> LLM reasoning -> tool execution -> tool result feedback -> final response -> (optional TTS).
 */
class ConversationManager(
    private val context: Context,
    val llmEngine: LlmEngine,
    val toolRegistry: ToolRegistry,
    val ttsEngine: TtsEngine? = null,
    private val maxIterations: Int = 5
) {
    val conversationContext = ConversationContext()

    fun processUtterance(
        userInput: String,
        enableTts: Boolean = true
    ): Flow<ConversationEvent> = flow {
        if (userInput.isBlank()) {
            emit(ConversationEvent.Error("Empty user input"))
            return@flow
        }

        conversationContext.append(ConversationTurn.User(userInput))
        emit(ConversationEvent.Thinking(userInput))

        var iterations = 0
        var lastToolResult: ToolResult? = null
        var finalResponseText = ""

        val systemPrompt = "You are Loki, an offline Android assistant. Choose the best tool or answer directly in JSON format."
        val grammar = GrammarBuilder.buildFrom(toolRegistry)

        while (iterations < maxIterations) {
            iterations++
            val prompt = conversationContext.buildPrompt(systemPrompt)

            val generatedSb = StringBuilder()
            val llmResult = llmEngine.generate(
                prompt = prompt,
                grammar = grammar,
                onToken = { token ->
                    generatedSb.append(token)
                }
            )
            if (llmResult.isFailure) {
                val errorMsg = llmResult.exceptionOrNull()?.message ?: "LLM inference failed"
                Log.e(TAG, errorMsg)
                emit(ConversationEvent.Error(errorMsg))
                return@flow
            }

            val rawOutput = llmResult.getOrNull() ?: ""
            when (val parsed = ToolCallParser.parse(rawOutput)) {
                is ParsedLlmResponse.ToolCall -> {
                    emit(ConversationEvent.ToolExecuting(parsed.tool, parsed.arguments))
                    conversationContext.append(
                        ConversationTurn.ToolCall(
                            tool = parsed.tool,
                            arguments = parsed.arguments.mapValues { it.value?.toString() ?: "" }
                        )
                    )

                    val toolResult = toolRegistry.execute(context, parsed.tool, parsed.arguments)
                    lastToolResult = toolResult
                    emit(ConversationEvent.ToolExecuted(parsed.tool, toolResult))

                    conversationContext.append(
                        ConversationTurn.ToolExecutionResult(
                            tool = parsed.tool,
                            result = toolResult
                        )
                    )

                    if (!toolResult.success) {
                        finalResponseText = formatErrorResponse(parsed.tool, toolResult)
                        conversationContext.append(ConversationTurn.Assistant(finalResponseText))
                        break
                    }

                    // Fast path for simple successful single-turn tools
                    val fastResponse = formatFastPathResponse(parsed.tool, toolResult)
                    if (fastResponse != null) {
                        finalResponseText = fastResponse
                        conversationContext.append(ConversationTurn.Assistant(finalResponseText))
                        break
                    }
                }

                is ParsedLlmResponse.DirectResponse -> {
                    finalResponseText = parsed.text
                    conversationContext.append(ConversationTurn.Assistant(finalResponseText))
                    break
                }

                is ParsedLlmResponse.Malformed -> {
                    Log.w(TAG, "Malformed LLM output: ${parsed.raw} (error: ${parsed.error})")
                    finalResponseText = parsed.raw
                    conversationContext.append(ConversationTurn.Assistant(finalResponseText))
                    break
                }
            }
        }

        if (finalResponseText.isEmpty()) {
            finalResponseText = "Task completed."
        }

        if (enableTts && ttsEngine != null) {
            emit(ConversationEvent.Speaking(finalResponseText))
            ttsEngine.speak(finalResponseText)
        }

        emit(ConversationEvent.Completed(finalResponseText, lastToolResult))
    }.flowOn(Dispatchers.IO)

    private fun formatErrorResponse(toolName: String, result: ToolResult): String {
        val err = result.error ?: "Action failed."
        return when {
            err.contains("permission", ignoreCase = true) ->
                "I need permission to do that. Please grant the required permission in Settings."
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

    fun cancel() {
        llmEngine.cancel()
        ttsEngine?.stop()
    }

    fun reset() {
        cancel()
        conversationContext.clear()
    }

    companion object {
        private const val TAG = "ConversationManager"
    }
}
