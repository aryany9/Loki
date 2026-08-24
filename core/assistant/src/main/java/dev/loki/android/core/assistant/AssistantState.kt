package dev.loki.android.core.assistant

/**
 * State machine representing the lifecycle of an active assistant turn.
 */
sealed interface AssistantState {
    data object Idle : AssistantState
    data class Listening(val partialTranscript: String = "") : AssistantState
    data class Processing(val query: String = "") : AssistantState
    data class Speaking(val responseText: String) : AssistantState
    data class Error(val message: String) : AssistantState
}
