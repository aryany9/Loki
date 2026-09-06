package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.models.ConversationMode
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
    /**
     * Emitted when a tool execution begins. [callId] correlates with [ToolCallCompleted] and [ToolCallFailed].
     */
    data class ToolCallStarted(val callId: String, val toolName: String, val arguments: Map<String, Any?>) : ConversationEvent
    /**
     * Emitted when a tool execution completes successfully. [callId] matches the originating [ToolCallStarted].
     */
    data class ToolCallCompleted(val callId: String, val toolName: String, val result: dev.loki.android.core.tools.ToolResult) : ConversationEvent
    /**
     * Emitted when a tool execution fails. [callId] matches the originating [ToolCallStarted].
     */
    data class ToolCallFailed(val callId: String, val toolName: String, val error: String) : ConversationEvent
    /**
     * Emitted when a tool with [requiresConfirmation] = true is about to execute.
     * The session will suspend until [ConversationSession.respondToConfirmation] is called,
     * or the timeout elapses.
     *
     * @param toolName   The name of the tool awaiting confirmation.
     * @param repeatBack Human-readable description of the action (e.g. "Call Rahul at +91 …").
     */
    data class ConfirmationRequired(
        val toolName: String,
        val repeatBack: String
    ) : ConversationEvent
    data class Speaking(val text: String) : ConversationEvent
    data class AskUser(val question: String) : ConversationEvent
    data class Completed(val finalResponse: String, val toolResult: ToolResult? = null) : ConversationEvent
    data class ContextCompacted(val message: String = "Context compacted") : ConversationEvent
    data class Error(val message: String) : ConversationEvent
}

/**
 * Structured in-activation pending state carrying question and candidate options across turns.
 */
data class PendingAsk(
    val question: String,
    val candidates: List<ContactCandidate> = emptyList(),
    val selectedId: String? = null
)

/**
 * Structured in-activation pending confirmation state carrying resolved candidate, repeat-back string, and asked status.
 */
data class PendingVoiceConfirmation(
    val candidate: ContactCandidate,
    val repeatBack: String,
    val isAsked: Boolean = false
)

/**
 * ConversationManager manages LLM & tool coordination, providing scoped ConversationSessions
 * for persistent chat and ephemeral voice interactions.
 */
open class ConversationManager(
    private val context: Context,
    val llmEngine: LlmEngine,
    val toolRegistry: ToolRegistry,
    val ttsEngine: TtsEngine? = null,
    val permissionManager: dev.loki.android.core.tools.PermissionManager = dev.loki.android.core.tools.PermissionManager(),
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val conversationStore: ConversationStore = ConversationStore(context, ioDispatcher),
    val memoryStore: MemoryStore = MemoryStore(context, ioDispatcher),
    private val maxIterations: Int = 5
) {
    private var persistentChatContext = ConversationContext(maxTurns = 10)
    private var activeAgentConfig: AgentConfig = AgentConfig()
    private var activeConversationId: String? = null
    private val sharedVoiceCandidates = mutableMapOf<String, ContactCandidate>()
    private val chatContactCandidates = mutableMapOf<String, ContactCandidate>()
    var pendingVoiceAsk: PendingAsk? = null
    var pendingVoiceConfirmation: PendingVoiceConfirmation? = null

    val currentConversationId: String?
        get() = activeConversationId

    fun getVoiceCandidates(): Map<String, ContactCandidate> = sharedVoiceCandidates.toMap()

    fun clearVoiceCandidates() {
        sharedVoiceCandidates.clear()
        pendingVoiceAsk = null
        pendingVoiceConfirmation = null
        confirmedResolution = null
    }

    fun clearContactCandidates() {
        sharedVoiceCandidates.clear()
        chatContactCandidates.clear()
        pendingVoiceAsk = null
        pendingVoiceConfirmation = null
    }

    /** Backing store for confirmed resolution — set by [confirmContactResolution], cleared by [clearVoiceTask]. */
    private var confirmedResolution: ContactResolution? = null

    /**
     * Derives the current [TaskState] from the in-activation pending voice state.
     * When [confirmContactResolution] has been called, returns a [ContactResolution] with
     * `confirmed = true` so the next [ConversationSession] sees `call_contact` unblocked.
     * Mirrors the synthesis logic in [ConversationSession.init] so that [AssistantSession]
     * can read [TaskState.expectedSemantics] without constructing a full session.
     */
    val taskState: TaskState?
        get() {
            // Confirmed override takes precedence — next session will execute call_contact directly.
            confirmedResolution?.let { return it }
            val vc = pendingVoiceConfirmation
            if (vc != null) {
                return ContactResolution(
                    candidates = listOf(vc.candidate),
                    selectedId = vc.candidate.id,
                    isAsked = vc.isAsked
                )
            }
            val pa = pendingVoiceAsk
            if (pa != null && pa.candidates.isNotEmpty()) {
                return ContactResolution(
                    candidates = pa.candidates,
                    selectedId = pa.selectedId,
                    isAsked = false
                )
            }
            return null
        }

    /**
     * Advances [ContactResolution] state to `confirmed = true` so that the next
     * [ConversationSession] will see `call_contact` unblocked in the tool grammar.
     *
     * No-op if the current state is not a [ContactResolution] with [ContactResolution.selectedId]
     * non-null and [ContactResolution.confirmed] == false.
     */
    open fun confirmContactResolution() {
        val current = taskState as? ContactResolution ?: return
        if (current.selectedId == null || current.confirmed) return
        confirmedResolution = current.copy(confirmed = true)
    }

    /**
     * Clears all active voice task state: [taskState], [pendingVoiceAsk], and
     * [pendingVoiceConfirmation].  No-op if all are already null.
     */
    open fun clearVoiceTask() {
        pendingVoiceAsk = null
        pendingVoiceConfirmation = null
        confirmedResolution = null
        sharedVoiceCandidates.clear()
    }

    fun getAgentConfig(): AgentConfig = activeAgentConfig

    fun setAgentConfig(config: AgentConfig) {
        activeAgentConfig = config
        ttsEngine?.configureLanguage(config.conversationLanguage)
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
        ttsEngine?.configureLanguage(config.conversationLanguage)
        reset()
        return llmEngine.startConversation(config)
    }

    suspend fun createConversation(title: String = "New Chat"): ConversationRecord {
        val record = conversationStore.createConversation(title = title)
        activeConversationId = record.id
        persistentChatContext.clear()
        chatContactCandidates.clear()
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
        chatContactCandidates.clear()
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

    open fun newChatSession(): ConversationSession {
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
            memoryStore = memoryStore,
            conversationId = convId,
            mode = ConversationMode.CHAT,
            ioDispatcher = ioDispatcher,
            contactCandidateRegistry = chatContactCandidates
        )
    }

    fun newVoiceSession(): ConversationSession {
        val session = ConversationSession(
            context = context,
            llmEngine = llmEngine,
            toolRegistry = toolRegistry,
            ttsEngine = ttsEngine,
            conversationContext = ConversationContext(maxTurns = 1),
            permissionManager = permissionManager,
            agentConfig = activeAgentConfig,
            maxIterations = maxIterations,
            conversationStore = null,
            memoryStore = memoryStore,
            conversationId = null,
            mode = ConversationMode.VOICE,
            ioDispatcher = ioDispatcher,
            contactCandidateRegistry = sharedVoiceCandidates,
            pendingAsk = pendingVoiceAsk,
            onPendingAskUpdated = { updated -> pendingVoiceAsk = updated },
            pendingVoiceConfirmation = pendingVoiceConfirmation,
            onPendingVoiceConfirmationUpdated = { updated -> pendingVoiceConfirmation = updated }
        )
        // If confirmContactResolution() was called before this session, apply the confirmed
        // state now. ConversationSession.init{} always re-synthesizes taskState from
        // pendingVoiceConfirmation (which has confirmed=false), so we overwrite it here
        // from the confirmedResolution backing field.
        // Use it once then clear so it does not leak into subsequent sessions.
        confirmedResolution?.let {
            session.taskState = it
            confirmedResolution = null
        }
        return session
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
        activeConversationId = null
        persistentChatContext.clear()
        sharedVoiceCandidates.clear()
        chatContactCandidates.clear()
        pendingVoiceAsk = null
        pendingVoiceConfirmation = null
        confirmedResolution = null
        llmEngine.resetConversation()
    }

    companion object {
        private const val TAG = "ConversationManager"
    }
}
