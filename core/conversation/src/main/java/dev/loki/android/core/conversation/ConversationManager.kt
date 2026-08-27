package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import dev.loki.android.core.voice.tts.TtsEngine
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
    private val maxIterations: Int = 5
) {
    private val persistentChatContext = ConversationContext(maxTurns = 10)

    fun newChatSession(): ConversationSession {
        return ConversationSession(
            context = context,
            llmEngine = llmEngine,
            toolRegistry = toolRegistry,
            ttsEngine = ttsEngine,
            conversationContext = persistentChatContext,
            permissionManager = permissionManager,
            maxIterations = maxIterations
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
            maxIterations = maxIterations
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
