package dev.loki.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.loki.android.core.assistant.AssistantSessionProvider
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.models.ModelLibraryManager
import dev.loki.android.core.voice.stt.SttEngine
import javax.inject.Inject

@HiltAndroidApp
class LokiApplication : Application() {

    @Inject
    lateinit var conversationManager: ConversationManager

    @Inject
    lateinit var sttEngine: SttEngine

    @Inject
    lateinit var modelLibraryManager: ModelLibraryManager

    override fun onCreate() {
        super.onCreate()
        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): ConversationManager = conversationManager
            override fun getSttEngine(): SttEngine = sttEngine
            override fun getModelLibraryManager(): ModelLibraryManager = modelLibraryManager
        }

        // Task 6.3: 48-hour TTL WorkManager Job
        val cleanupWork = androidx.work.PeriodicWorkRequestBuilder<AudioCleanupWorker>(1, java.util.concurrent.TimeUnit.DAYS)
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "AudioCleanupWork",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            cleanupWork
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            android.util.Log.w("LokiApplication", "TRIM_MEMORY_RUNNING_CRITICAL received, forcefully unloading Whisper model to protect LLM engine")
            sttEngine.release()
        }
    }
}
