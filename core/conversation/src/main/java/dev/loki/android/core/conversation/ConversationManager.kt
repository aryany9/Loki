package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import dev.loki.android.core.voice.tts.TtsEngine
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

sealed interface ConversationEvent {
    data class Thinking(val query: String) : ConversationEvent
    data class GeneratingToken(val partial: String) : ConversationEvent
    data class ToolExecuting(val toolName: String, val args: Map<String, Any?>) : ConversationEvent
    data class ToolExecuted(val toolName: String, val result: ToolResult) : ConversationEvent
    data class Speaking(val text: String) : ConversationEvent
    data class Completed(val finalResponse: String, val toolResult: ToolResult? = null) : ConversationEvent
    data class Error(val message: String) : ConversationEvent
}

/**
 * ConversationManager manages LLM & tool coordination, providing scoped ConversationSessions
 * for persistent chat and ephemeral voice interactions.
 */
class ConversationManager(
    private val context: Context,
    val llmEngine: LlmEngine,
    val toolRegistry: ToolRegistry,
    val ttsEngine: TtsEngine? = null,
    val permissionManager: dev.loki.android.core.tools.PermissionManager = dev.loki.android.core.tools.PermissionManager(),
    val conversationStore: ConversationStore = ConversationStore(context),
    private val maxIterations: Int = 5,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var persistentChatContext = ConversationContext(maxTurns = 10)
    private var activeAgentConfig: AgentConfig = AgentConfig()
    private var activeConversationId: String? = null

    val currentConversationId: String?
        get() = activeConversationId

    fun getAgentConfig(): AgentConfig = activeAgentConfig

    fun setAgentConfig(config: AgentConfig) {
        activeAgentConfig = config
    }

    /**
     * Applies a new [AgentConfig]:
     * - If [AgentConfig.runtimeConfig] changed, forces engine re-initialization before updating conversation.
     * - Resets the persistent chat session so prior KV context is cleanly cleared.
     * - Restarts conversation with the new config.
     */
    suspend fun applyAgentConfig(config: AgentConfig): Boolean {
        val runtimeChanged = config.runtimeConfig != activeAgentConfig.runtimeConfig
        if (runtimeChanged) {
            llmEngine.initializeAsync(modelPath = null, runtimeConfig = config.runtimeConfig, force = true)
        }
        activeAgentConfig = config
        reset()
        return llmEngine.startConversation(config)
    }

    suspend fun createConversation(title: String = "New Chat"): ConversationRecord {
        val record = conversationStore.createConversation(title = title)
        activeConversationId = record.id
        persistentChatContext.clear()
        llmEngine.startConversation(activeAgentConfig)
        return record
    }

    suspend fun listConversations(): List<ConversationRecord> {
        return conversationStore.listConversations()
    }

    suspend fun loadConversation(id: String): ConversationRecord? {
        val record = conversationStore.loadConversation(id) ?: return null
        activeConversationId = record.id
        persistentChatContext.clear()
        for (turn in record.turns) {
            persistentChatContext.append(turn)
        }
        llmEngine.startConversation(activeAgentConfig)
        return record
    }

    suspend fun deleteConversation(id: String): Boolean {
        val deleted = conversationStore.deleteConversation(id)
        if (deleted && activeConversationId == id) {
            val remaining = conversationStore.listConversations()
            if (remaining.isNotEmpty()) {
                loadConversation(remaining.first().id)
            } else {
                createConversation()
            }
        }
        return deleted
    }

    suspend fun renameConversation(id: String, title: String): Boolean {
        return conversationStore.renameConversation(id, title)
    }

    fun newChatSession(): ConversationSession {
        val convId = activeConversationId ?: run {
            val newId = UUID.randomUUID().toString()
            activeConversationId = newId
            newId
        }
        return ConversationSession(
            context = context,
            llmEngine = llmEngine,
            toolRegistry = toolRegistry,
            ttsEngine = ttsEngine,
            conversationContext = persistentChatContext,
            permissionManager = permissionManager,
            agentConfig = activeAgentConfig,
            maxIterations = maxIterations,
            conversationStore = conversationStore,
            conversationId = convId,
            ioDispatcher = ioDispatcher
        )
    }

    fun newVoiceSession(): ConversationSession {
        return ConversationSession(
            context = context,
            llmEngine = llmEngine,
            toolRegistry = toolRegistry,
            ttsEngine = ttsEngine,
            conversationContext = ConversationContext(maxTurns = 1),
            permissionManager = permissionManager,
            agentConfig = activeAgentConfig,
            maxIterations = maxIterations,
            conversationStore = null,
            conversationId = null,
            ioDispatcher = ioDispatcher
        )
    }

    fun processUtterance(
        userInput: String,
        enableTts: Boolean = true
    ): Flow<ConversationEvent> {
        return newChatSession().processUtterance(userInput, enableTts = enableTts, source = "TEXT")
    }

    fun cancel() {
        llmEngine.cancel()
        ttsEngine?.stop()
    }

    fun reset() {
        cancel()
        persistentChatContext.clear()
    }

    companion object {
        private const val TAG = "ConversationManager"
    }
}
