package dev.loki.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.loki.android.core.assistant.AssistantSessionProvider
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.voice.stt.SttEngine
import javax.inject.Inject

@HiltAndroidApp
class LokiApplication : Application() {

    @Inject
    lateinit var conversationManager: ConversationManager

    @Inject
    lateinit var sttEngine: SttEngine

    override fun onCreate() {
        super.onCreate()
        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): ConversationManager = conversationManager
            override fun getSttEngine(): SttEngine = sttEngine
        }
    }
}
