package dev.loki.android.core.conversation

import dev.loki.android.core.tools.ToolResult

/**
 * Manages the multi-turn history and token budget for on-device LLM reasoning.
 */
class ConversationContext(
    private val maxTurns: Int = 10,
    private val maxTokenBudget: Int = 1500
) {
    private val turns = mutableListOf<ConversationTurn>()

    fun append(turn: ConversationTurn) {
        turns.add(turn)
        trimIfNeeded()
    }

    fun getTurns(): List<ConversationTurn> = turns.toList()

    fun clear() {
        turns.clear()
    }

    fun estimateTokenCount(): Int {
        // Rough heuristic: ~4 chars per token
        val totalChars = turns.sumOf { turn ->
            when (turn) {
                is ConversationTurn.User -> turn.text.length
                is ConversationTurn.Assistant -> turn.text.length
                is ConversationTurn.ToolCall -> turn.tool.length + turn.arguments.toString().length
                is ConversationTurn.ToolExecutionResult -> turn.result.toString().length
            }
        }
        return totalChars / 4
    }

    private fun trimIfNeeded() {
        while (turns.size > maxTurns || estimateTokenCount() > maxTokenBudget) {
            if (turns.size <= 2) break // Keep at least the latest user prompt and response
            turns.removeAt(0)
        }
    }

    fun buildPrompt(systemInstructions: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(systemInstructions)
        sb.append("<|im_end|>\n")

        for (turn in turns) {
            when (turn) {
                is ConversationTurn.User -> {
                    sb.append("<|im_start|>user\n").append(turn.text).append("<|im_end|>\n")
                }
                is ConversationTurn.ToolCall -> {
                    sb.append("<|im_start|>assistant\n")
                    sb.append("{\"tool\": \"").append(turn.tool).append("\", \"arguments\": ")
                    sb.append(turn.arguments.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\": \"${it.value}\"" })
                    sb.append("}<|im_end|>\n")
                }
                is ConversationTurn.ToolExecutionResult -> {
                    sb.append("<|im_start|>user\n")
                    sb.append("Tool result for ").append(turn.tool).append(": ")
                    sb.append(if (turn.result.success) turn.result.data.toString() else turn.result.error)
                    sb.append("<|im_end|>\n")
                }
                is ConversationTurn.Assistant -> {
                    sb.append("<|im_start|>assistant\n").append(turn.text).append("<|im_end|>\n")
                }
            }
        }

        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
