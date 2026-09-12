package dev.loki.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.conversation.ConversationStore
import dev.loki.android.core.conversation.MemoryStore
import dev.loki.android.core.llm.LiteRtLlmEngine
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.ModelManager
import dev.loki.android.core.models.ModelCatalog
import dev.loki.android.core.models.ModelCatalogRepository
import dev.loki.android.core.models.ModelLibraryManager
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelRuntimeController
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.local.DefaultLocalTools
import dev.loki.android.core.theme.ThemeRepository
import dev.loki.android.core.voice.stt.LiteRtWhisperEngine
import dev.loki.android.core.voice.stt.SttEngine
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
    fun provideConversationStore(@ApplicationContext context: Context): dev.loki.android.core.conversation.ConversationStore {
        return dev.loki.android.core.conversation.ConversationStore(context)
    }

    @Provides
    @Singleton
    fun provideMemoryStore(@ApplicationContext context: Context): dev.loki.android.core.conversation.MemoryStore {
        return dev.loki.android.core.conversation.MemoryStore(context)
    }

    @Provides
    @Singleton
    fun provideToolRegistry(
        memoryStore: dev.loki.android.core.conversation.MemoryStore,
        conversationStore: dev.loki.android.core.conversation.ConversationStore
    ): ToolRegistry {
        val registry = ToolRegistry()
        DefaultLocalTools.registerAll(registry, memoryStore = memoryStore, conversationStore = conversationStore)
        return registry
    }

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager {
        return ModelManager(context)
    }

    @Provides
    @Singleton
    fun provideLlmEngine(
        @ApplicationContext context: Context,
        modelManager: ModelManager
    ): LlmEngine {
        return LiteRtLlmEngine(context, modelManager)
    }

    @Provides
    @Singleton
    fun provideSttEngine(
        @ApplicationContext context: Context,
        modelManager: ModelManager
    ): SttEngine {
        return LiteRtWhisperEngine(storage = modelManager.modelStorage, context = context)
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
        permissionManager: PermissionManager,
        conversationStore: dev.loki.android.core.conversation.ConversationStore,
        memoryStore: dev.loki.android.core.conversation.MemoryStore
    ): ConversationManager {
        return ConversationManager(
            context = context,
            llmEngine = llmEngine,
            toolRegistry = toolRegistry,
            ttsEngine = ttsEngine,
            permissionManager = permissionManager,
            conversationStore = conversationStore,
            memoryStore = memoryStore
        )
    }

    @Provides
    @Singleton
    fun provideModelLibraryManager(
        modelManager: ModelManager,
        llmEngine: LlmEngine,
        sttEngine: SttEngine
    ): ModelLibraryManager {
        val manager = ModelLibraryManager(
            storage = modelManager.modelStorage,
            registry = modelManager.modelRegistry
        )
        
        // Register runtimes
        (llmEngine as? ModelRuntimeController)?.let { 
            manager.registerRuntime(ModelRuntime.LITERT_LM, it) 
        }
        (sttEngine as? ModelRuntimeController)?.let { 
            manager.registerRuntime(ModelRuntime.LITERT_ASR, it) 
        }

        // Register live readiness provider for ASR so isRuntimeReady reflects actual interpreter state
        (sttEngine as? LiteRtWhisperEngine)?.let { engine ->
            manager.registerReadinessProvider(ModelRuntime.LITERT_ASR, engine::isReady)
        }
        
        return manager
    }

    @Provides
    @Singleton
    fun provideBundledCatalog(@ApplicationContext context: Context): ModelCatalog {
        return ModelCatalogRepository().loadBundled(context.assets)
    }

    @Provides
    @Singleton
    fun provideAgentConfigRepository(@ApplicationContext context: Context): dev.loki.android.core.ui.AgentConfigRepository {
        return dev.loki.android.core.ui.AgentConfigRepository(context)
    }
}
