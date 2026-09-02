package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionTest {

    private val dummyEngine = object : LlmEngine {
        private val _state = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
        override val modelState: StateFlow<LlmModelState> = _state.asStateFlow()
        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?): Boolean = true
        override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
            return Result.success("{}")
        }
        override fun cancel() {}
        override fun release() {}
    }

    private class SequentialLlmEngine(private val responses: List<String>) : LlmEngine {
        private var index = 0
        val prompts = mutableListOf<String>()
        private val _state = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
        override val modelState: StateFlow<LlmModelState> = _state.asStateFlow()
        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?): Boolean = true
        override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
            prompts.add(prompt)
            val resp = if (index < responses.size) responses[index++] else "{}"
            return Result.success(resp)
        }
        override fun cancel() {}
        override fun release() {}
    }

    private class DummyLookupTool(
        private val contactsJson: String = "[{\"id\":\"c1\",\"name\":\"Mom\",\"number\":\"1234567890\"},{\"id\":\"c2\",\"name\":\"Mom Mobile\",\"number\":\"9876543210\"}]"
    ) : LocalTool {
        override val name: String = "lookup_contact"
        override val capability: String = "calling"
        override val description: String = "Lookup contacts"
        override val parameters: Map<String, ToolParam> = mapOf(
            "query" to ToolParam(ToolParamType.STRING, "name query", required = true)
        )
        var executed = false
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            executed = true
            return ToolResult.success(mapOf("count" to "2", "contacts" to contactsJson))
        }
    }

    private class DummyCallTool : LocalTool {
        override val name: String = "call_contact"
        override val capability: String = "calling"
        override val description: String = "Place phone call"
        override val parameters: Map<String, ToolParam> = mapOf(
            "phone_number" to ToolParam(ToolParamType.STRING, "phone", required = false),
            "candidate_id" to ToolParam(ToolParamType.STRING, "candidate ID", required = false),
            "name" to ToolParam(ToolParamType.STRING, "name", required = false)
        )
        override val requiresConfirmation: Boolean = true
        override fun describeAction(arguments: Map<String, Any?>): String = "Call ${arguments["phone_number"]}?"
        var executed = false
        var executedArgs: Map<String, Any?>? = null
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            executed = true
            executedArgs = arguments
            val callingName = arguments["name"]?.toString() ?: "Mom"
            return ToolResult.success(mapOf("calling" to callingName, "phone_number" to (arguments["phone_number"]?.toString() ?: "")))
        }
    }

    private class DummyDeviceTool : LocalTool {
        override val name: String = "toggle_flashlight"
        override val capability: String = "device"
        override val description: String = "Toggle flashlight"
        override val parameters: Map<String, ToolParam> = emptyMap()
        var executed = false
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            executed = true
            return ToolResult.success(mapOf("status" to "on"))
        }
    }

    @Test
    fun `buildCoreSystemPrompt contains persona and JSON protocol without tool schemas`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()
        registry.register(DummyLookupTool())

        val session = ConversationSession(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = registry
        )

        val core = session.buildCoreSystemPrompt()
        assertTrue(core.contains("You are Loki"))
        assertTrue(core.contains("Always output JSON"))
        assertFalse(core.contains("Available tools"))
        assertFalse(core.contains("lookup_contact"))
    }

    @Test
    fun `buildPerTurnPrompt provides capability instructions and task state without numbers`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()
        val lookupTool = DummyLookupTool()
        registry.register(lookupTool)

        val session = ConversationSession(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = registry
        )

        val candidates = listOf(
            ContactCandidate("c1", "Mom", "1234567890"),
            ContactCandidate("c2", "Mom Mobile", "9876543210")
        )
        val state = ContactResolution(candidates = candidates, selectedId = null, confirmed = false)

        val perTurnActivation = session.buildPerTurnPrompt(
            availableTools = listOf(lookupTool),
            activeCapability = "calling",
            taskState = state,
            isActivationTurn = true
        )

        assertTrue(perTurnActivation.contains("Calling guidance: When looking up contacts to call"))
        assertTrue(perTurnActivation.contains("Current Task: Contact Resolution"))
        assertTrue(perTurnActivation.contains("- [c1] Mom"))
        assertTrue(perTurnActivation.contains("- [c2] Mom Mobile"))
        // Strict invariant: phone numbers NEVER in prompt context
        assertFalse(perTurnActivation.contains("1234567890"))
        assertFalse(perTurnActivation.contains("9876543210"))

        // Subsequent turn: 1-line reminder
        val perTurnSubsequent = session.buildPerTurnPrompt(
            availableTools = listOf(lookupTool),
            activeCapability = "calling",
            taskState = state,
            isActivationTurn = false
        )
        assertTrue(perTurnSubsequent.contains("Calling reminder:"))
        assertFalse(perTurnSubsequent.contains("Calling guidance:"))
    }

    @Test
    fun `renderTaskState outputs candidate names and IDs but never phone numbers`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = ToolRegistry()
        )

        val candidates = listOf(
            ContactCandidate("c1", "Alice", "+10000000001"),
            ContactCandidate("c2", "Bob", "+10000000002")
        )

        val unselectedState = ContactResolution(candidates = candidates, selectedId = null)
        val unselectedRendered = session.renderTaskState(unselectedState)
        assertTrue(unselectedRendered.contains("[c1] Alice"))
        assertTrue(unselectedRendered.contains("[c2] Bob"))
        assertFalse(unselectedRendered.contains("+10000000001"))
        assertFalse(unselectedRendered.contains("+10000000002"))

        val selectedState = ContactResolution(candidates = candidates, selectedId = "c1")
        val selectedRendered = session.renderTaskState(selectedState)
        assertTrue(selectedRendered.contains("Selected candidate: [c1] Alice"))
        assertFalse(selectedRendered.contains("+10000000001"))
    }

    @Test
    fun `out of scope tool call during unresolved task produces coached deferral`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()
        val lookupTool = DummyLookupTool()
        val deviceTool = DummyDeviceTool()
        registry.register(lookupTool)
        registry.register(deviceTool)

        val llmResponses = listOf(
            // Tries to execute out-of-scope tool while calling capability is active
            "{\"tool\": \"toggle_flashlight\", \"arguments\": {}}",
            "{\"response\": \"Please pick a contact first.\"}"
        )
        val engine = SequentialLlmEngine(llmResponses)
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        // Seed state: calling capability active with unresolved contact state
        session.activeCapability = "calling"
        session.taskState = ContactResolution(
            candidates = listOf(ContactCandidate("c1", "Mom", "123")),
            selectedId = null
        )

        session.processUtterance("turn on flashlight").collect {}

        // Flashlight tool was NOT executed
        assertFalse(deviceTool.executed)
        // Engine received coached prompt
        assertTrue(engine.prompts.any { it.contains("Tool 'toggle_flashlight' is unavailable. Please resolve the current task first.") })
    }

    @Test
    fun `contact resolution end-to-end lookup to select to confirm to call with app-resolved number`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()
        val lookupTool = DummyLookupTool()
        val callTool = DummyCallTool()
        registry.register(lookupTool)
        registry.register(callTool)

        val llmResponses = listOf(
            // Turn 1: model executes lookup_contact
            "{\"tool\": \"lookup_contact\", \"arguments\": {\"query\": \"Mom\"}}",
            // Turn 1 iteration 2: model asks disambiguation question
            "{\"response\": \"I found Mom and Mom Mobile. Which one would you like to call?\"}",

            // Turn 2: user says "the first one", model selects candidate c1
            "{\"tool\": \"select_contact\", \"arguments\": {\"candidate_id\": \"c1\"}}",
            // Turn 2 iteration 2: model asks confirmation
            "{\"response\": \"Do you want me to call Mom?\"}",

            // Turn 3: user says "yes", model invokes call_contact with candidate_id
            "{\"tool\": \"call_contact\", \"arguments\": {\"candidate_id\": \"c1\"}}"
        )
        val engine = SequentialLlmEngine(llmResponses)
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        // ── Round 1: "Call Mom" ──────────────────────────────────────────────
        val eventsRound1 = mutableListOf<ConversationEvent>()
        session.processUtterance("Call Mom", source = "VOICE").collect { eventsRound1.add(it) }

        assertTrue(lookupTool.executed)
        assertEquals("calling", session.activeCapability)
        assertNotNull(session.taskState)
        assertEquals("select_contact", session.taskState?.advancingTool)

        val completed1 = eventsRound1.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertEquals("I found Mom and Mom Mobile. Which one would you like to call?", completed1?.finalResponse)

        // ── Round 2: "the first one" ─────────────────────────────────────────
        val eventsRound2 = mutableListOf<ConversationEvent>()
        session.processUtterance("the first one", source = "VOICE_FOLLOW_UP").collect { eventsRound2.add(it) }

        assertEquals("calling", session.activeCapability)
        val resolution2 = session.taskState as? ContactResolution
        assertNotNull(resolution2)
        assertEquals("c1", resolution2?.selectedId)
        assertEquals("call_contact", session.taskState?.advancingTool)

        val completed2 = eventsRound2.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertEquals("Do you want me to call Mom?", completed2?.finalResponse)

        // ── Round 3: "yes" (Follow-up confirmation) ──────────────────────────
        val eventsRound3 = mutableListOf<ConversationEvent>()
        session.processUtterance("yes, go ahead", source = "VOICE_FOLLOW_UP").collect { eventsRound3.add(it) }

        assertTrue(callTool.executed)
        // Crucial check: phone number resolved by app, not model context!
        assertEquals("1234567890", callTool.executedArgs?.get("phone_number"))
        assertEquals("Mom", callTool.executedArgs?.get("name"))

        // Fast-path announcement uses contact name
        val completed3 = eventsRound3.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertEquals("Calling Mom", completed3?.finalResponse)

        // Capability and task state cleared upon successful completion
        assertNull(session.activeCapability)
        assertNull(session.taskState)
    }

    @Test
    fun `voice source with gated tool blocks direct execution, appends coached deferral, and executes on follow-up`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()
        val tool = DummyCallTool()
        registry.register(tool)

        val llmResponses = mutableListOf(
            "{\"tool\": \"call_contact\", \"arguments\": {\"phone_number\": \"+91 79001 96495\"}}",
            "{\"response\": \"Do you want me to call Mom?\"}",
            "{\"tool\": \"call_contact\", \"arguments\": {\"phone_number\": \"+91 79001 96495\"}}"
        )
        val engine = SequentialLlmEngine(llmResponses)
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        // Round 1 (VOICE)
        val eventsRound1 = mutableListOf<ConversationEvent>()
        session.processUtterance("call Mom", source = "VOICE").collect {
            eventsRound1.add(it)
        }

        assertFalse(tool.executed)
        assertFalse(eventsRound1.any { it is ConversationEvent.ConfirmationRequired })

        val coachedTurn = session.conversationContext.getTurns().find {
            it is ConversationTurn.ToolExecutionResult && it.result.error?.contains("Action requires verbal confirmation") == true
        }
        assertNotNull(coachedTurn)

        val completed1 = eventsRound1.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertEquals("Do you want me to call Mom?", completed1?.finalResponse)

        // Round 2 (VOICE_FOLLOW_UP with user confirmation)
        val eventsRound2 = mutableListOf<ConversationEvent>()
        session.processUtterance("yes", source = "VOICE_FOLLOW_UP").collect {
            eventsRound2.add(it)
        }

        assertTrue(tool.executed)
        val completed2 = eventsRound2.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertEquals("Calling Mom", completed2?.finalResponse)
    }

    @Test
    fun `chat source with gated tool suspends on confirmation channel and respects verdict`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()
        val tool = DummyCallTool()
        registry.register(tool)

        val llmResponses = mutableListOf(
            "{\"tool\": \"call_contact\", \"arguments\": {\"phone_number\": \"+12345\"}}",
            "{\"response\": \"Call connected.\"}"
        )
        val engine = SequentialLlmEngine(llmResponses)
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        var confirmationRequiredEmitted = false
        val job = backgroundScope.launch {
            session.processUtterance("call +12345", source = "TEXT").collect { event ->
                if (event is ConversationEvent.ConfirmationRequired) {
                    confirmationRequiredEmitted = true
                    session.respondToConfirmation(true)
                }
            }
        }
        job.join()

        assertTrue(confirmationRequiredEmitted)
        assertTrue(tool.executed)
    }

    @Test
    fun `confirmation timeout constant is 20 seconds`() {
        assertEquals(20_000L, ConversationSession.CONFIRMATION_TIMEOUT_MS)
    }
}
