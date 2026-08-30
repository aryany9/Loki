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
