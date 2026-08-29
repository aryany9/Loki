package dev.loki.android.core.conversation

import android.util.Log
import java.util.UUID

/**
 * Structured logger for assistant turns across the entire pipeline.
 * Attaches a unique turn ID to correlate events from input capture to TTS.
 */
object TurnLogger {
    private const val TAG = "LokiTurn"

    var isDebugOverride: Boolean? = null

    private val isDebug: Boolean
        get() = isDebugOverride ?: try {
            BuildConfig.DEBUG
        } catch (_: Throwable) {
            false
        }

    fun newTurnId(): String {
        return UUID.randomUUID().toString().substring(0, 8)
    }

    fun logTurnStart(turnId: String, source: String) {
        Log.i(TAG, "[$turnId] Turn started -> source=$source")
    }

    fun logTranscript(turnId: String, transcript: String) {
        Log.i(TAG, "[$turnId] Transcript received: \"$transcript\"")
    }

    fun logTools(turnId: String, availableCount: Int, disabledCount: Int) {
        Log.i(TAG, "[$turnId] Tools configured: $availableCount available, $disabledCount disabled")
    }

    fun logPrompt(turnId: String, prompt: String) {
        if (isDebug) {
            Log.d(TAG, "[$turnId] LLM Prompt:\n$prompt")
        } else {
            Log.i(TAG, "[$turnId] LLM Prompt prepared (length=${prompt.length} chars)")
        }
    }

    fun logLlmOutput(turnId: String, rawOutput: String) {
        Log.i(TAG, "[$turnId] LLM raw output: $rawOutput")
    }

    fun logParse(turnId: String, parseResult: ParsedLlmResponse) {
        when (parseResult) {
            is ParsedLlmResponse.ToolCall -> {
                Log.i(TAG, "[$turnId] Parsed -> ToolCall: tool=${parseResult.tool}, args=${parseResult.arguments}")
            }
            is ParsedLlmResponse.DirectResponse -> {
                Log.i(TAG, "[$turnId] Parsed -> DirectResponse: text=\"${parseResult.text}\"")
            }
            is ParsedLlmResponse.Malformed -> {
                Log.w(TAG, "[$turnId] Parsed -> Malformed JSON: raw=\"${parseResult.raw}\", error=${parseResult.error}")
            }
        }
    }

    fun logPermissionCheck(turnId: String, permission: String, state: String) {
        Log.i(TAG, "[$turnId] Permission check: $permission -> $state")
    }

    fun logToolExecution(turnId: String, tool: String, success: Boolean, details: String) {
        Log.i(TAG, "[$turnId] Tool execution: $tool (success=$success) -> $details")
    }

    fun logStrategy(turnId: String, strategy: String, durationMs: Long? = null) {
        if (durationMs != null) {
            Log.i(TAG, "[$turnId] Strategy: $strategy (recordingDuration=${durationMs}ms)")
        } else {
            Log.i(TAG, "[$turnId] Strategy: $strategy")
        }
    }

    fun logDemotion(turnId: String, reason: String) {
        Log.w(TAG, "[$turnId] Strategy demoted to STT fallback: $reason")
    }

    fun logFinalResponse(turnId: String, response: String) {
        Log.i(TAG, "[$turnId] Final response: \"$response\"")
    }

    fun logError(turnId: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, "[$turnId] Error: $message", throwable)
        } else {
            Log.e(TAG, "[$turnId] Error: $message")
        }
    }

    fun logCancel(turnId: String, reason: String = "User cancelled or session ended") {
        Log.i(TAG, "[$turnId] Turn cancelled: $reason")
    }
}
