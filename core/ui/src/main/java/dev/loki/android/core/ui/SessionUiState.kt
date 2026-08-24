package dev.loki.android.core.ui

sealed interface SessionUiState {
    data object Idle : SessionUiState
    data class Listening(val partialTranscript: String = "") : SessionUiState
    data class Processing(val query: String = "") : SessionUiState
    data class Speaking(val responseText: String) : SessionUiState
    data class Error(val message: String) : SessionUiState
}
