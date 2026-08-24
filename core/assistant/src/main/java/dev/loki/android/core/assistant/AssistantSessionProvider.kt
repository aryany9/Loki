package dev.loki.android.core.assistant

import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.voice.stt.SttEngine

interface AssistantSessionProvider {
    fun getConversationManager(): ConversationManager
    fun getSttEngine(): SttEngine

    companion object {
        var instance: AssistantSessionProvider? = null
    }
}
