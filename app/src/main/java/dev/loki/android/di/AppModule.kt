package dev.loki.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.llm.LlamaCppLlmEngine
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.ModelManager
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.local.DefaultLocalTools
import dev.loki.android.core.ui.theme.ThemeRepository
import dev.loki.android.core.voice.stt.SttEngine
import dev.loki.android.core.voice.stt.WhisperModelManager
import dev.loki.android.core.voice.stt.WhisperSttEngine
import dev.loki.android.core.voice.tts.AndroidTtsEngine
import dev.loki.android.core.voice.tts.TtsEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePermissionManager(): PermissionManager {
        return PermissionManager()
    }

    @Provides
    @Singleton
    fun provideThemeRepository(@ApplicationContext context: Context): ThemeRepository {
        return ThemeRepository(context)
    }

    @Provides
    @Singleton
    fun provideToolRegistry(): ToolRegistry {
        val registry = ToolRegistry()
        DefaultLocalTools.registerAll(registry)
        return registry
    }

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager {
        return ModelManager(context)
    }

    @Provides
    @Singleton
    fun provideLlmEngine(modelManager: ModelManager): LlmEngine {
        return LlamaCppLlmEngine(modelManager)
    }

    @Provides
    @Singleton
    fun provideWhisperModelManager(@ApplicationContext context: Context): WhisperModelManager {
        return WhisperModelManager(context)
    }

    @Provides
    @Singleton
    fun provideSttEngine(whisperModelManager: WhisperModelManager): SttEngine {
        return WhisperSttEngine(whisperModelManager)
    }

    @Provides
    @Singleton
    fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine {
        return AndroidTtsEngine(context)
    }

    @Provides
    @Singleton
    fun provideConversationManager(
        @ApplicationContext context: Context,
        llmEngine: LlmEngine,
        toolRegistry: ToolRegistry,
        ttsEngine: TtsEngine,
        permissionManager: PermissionManager
    ): ConversationManager {
        return ConversationManager(
            context = context,
            llmEngine = llmEngine,
            toolRegistry = toolRegistry,
            ttsEngine = ttsEngine,
            permissionManager = permissionManager
        )
    }
}
