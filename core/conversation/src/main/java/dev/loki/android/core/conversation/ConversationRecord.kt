package dev.loki.android.core.conversation

import kotlinx.serialization.Serializable

@Serializable
data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val turns: List<ConversationTurn> = emptyList()
)

@Serializable
data class TurnSearchResult(
    val conversationId: String,
    val conversationTitle: String,
    val snippet: String,
    val dateEpochMs: Long
)
