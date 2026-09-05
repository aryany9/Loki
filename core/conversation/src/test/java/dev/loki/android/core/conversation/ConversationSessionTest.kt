package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.TaskStateGate
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
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

    private class SequentialLlmEngine(
        private val responses: List<String>,
        activeBackend: dev.loki.android.core.models.ExecutionBackend = dev.loki.android.core.models.ExecutionBackend.GPU
    ) : LlmEngine {
        private var index = 0
        val prompts = mutableListOf<String>()
        val grammars = mutableListOf<String?>()
        val maxTokensList = mutableListOf<Int>()
        private val _state = MutableStateFlow<LlmModelState>(LlmModelState.Ready(activeBackend = activeBackend))
        override val modelState: StateFlow<LlmModelState> = _state.asStateFlow()
        override var onContextCompacted: ((String) -> Unit)? = null
        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?): Boolean = true
        override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
            prompts.add(prompt)
            grammars.add(grammar)
            maxTokensList.add(maxTokens)
            val resp = if (index < responses.size) responses[index++] else "{}"
            return Result.success(resp)
        }
        fun triggerCompaction(reason: String = "KV capacity reached") {
            onContextCompacted?.invoke(reason)
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
            "candidate_id" to ToolParam(ToolParamType.STRING, "candidate ID", required = false),
            "name" to ToolParam(ToolParamType.STRING, "name", required = false)
        )
        override val requiresConfirmation: Boolean = true
        override fun describeAction(arguments: Map<String, Any?>): String = "Call ${arguments["phone_number"]}?"
        var executed = false
        var executedCount = 0
        var executedArgs: Map<String, Any?>? = null
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            executed = true
            executedCount++
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

    /** Lightweight mock for state-scoped grammar gating tests. */
    private class MockScopedTool(
        override val name: String,
        override val capability: String,
        override val isInternal: Boolean = false
    ) : LocalTool {
        override val description: String = "mock"
        override val parameters: Map<String, ToolParam> = emptyMap()
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult = ToolResult.success()
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
        assertFalse(core.contains("Lookup contacts"))
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

        assertTrue(perTurnActivation.contains("Calling capability active"))
        assertTrue(perTurnActivation.contains("Current Task: Contact Disambiguation"))
        assertTrue(perTurnActivation.contains("- [c1] Mom"))
        assertTrue(perTurnActivation.contains("- [c2] Mom Mobile"))
        // Strict invariant: phone numbers NEVER in prompt context
        assertFalse(perTurnActivation.contains("1234567890"))
        assertFalse(perTurnActivation.contains("9876543210"))

        // Subsequent turn: still uses the same "Calling capability active" heading (no longer separate reminder text)
        val perTurnSubsequent = session.buildPerTurnPrompt(
            availableTools = listOf(lookupTool),
            activeCapability = "calling",
            taskState = state,
            isActivationTurn = false
        )
        assertTrue(perTurnSubsequent.contains("Calling capability active"))
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
        assertTrue(unselectedRendered.contains("- [c1] Alice"))
        assertTrue(unselectedRendered.contains("- [c2] Bob"))
        assertFalse(unselectedRendered.contains("+10000000001"))
        assertFalse(unselectedRendered.contains("+10000000002"))

        val selectedState = ContactResolution(candidates = candidates, selectedId = "c1")
        val selectedRendered = session.renderTaskState(selectedState)
        assertTrue(selectedRendered.contains("Selected contact: Alice"))
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
        // Use "Mom Home" and "Mom Mobile" so query "Mom" doesn't trigger exact-match auto-selection
        val lookupTool = DummyLookupTool(contactsJson = """[{"id":"c1","name":"Mom Home","number":"1234567890"},{"id":"c2","name":"Mom Mobile","number":"9876543210"}]""")
        val callTool = DummyCallTool()
        registry.register(lookupTool)
        registry.register(callTool)

        val llmResponses = listOf(
            // Turn 1: model executes lookup_contact
            "{\"tool\": \"lookup_contact\", \"arguments\": {\"query\": \"Mom\"}}",
            // Turn 1 iteration 2: model asks disambiguation question (no exact match, so disambiguation happens)
            "{\"response\": \"I found Mom Home and Mom Mobile. Which one would you like to call?\"}",

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
        assertEquals("I found Mom Home and Mom Mobile. Which one would you like to call?", completed1?.finalResponse)

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
        assertEquals("Mom Home", callTool.executedArgs?.get("name"))

        // Fast-path announcement uses contact name
        val completed3 = eventsRound3.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertEquals("Calling Mom Home", completed3?.finalResponse)

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

        val candidate = ContactCandidate(id = "c1", name = "Mom", phoneNumber = "+91 79001 96495")
        val llmResponses = mutableListOf(
            "{\"tool\": \"call_contact\", \"arguments\": {\"name\": \"Mom\"}}",
            "{\"response\": \"Do you want me to call Mom?\"}",
            "{\"tool\": \"call_contact\", \"arguments\": {\"name\": \"Mom\"}}"
        )
        val engine = SequentialLlmEngine(llmResponses)
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            contactCandidateRegistry = mutableMapOf("c1" to candidate, "mom" to candidate)
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

    @Test
    fun `backend-aware default output budget selects 256 on NPU and 512 on GPU`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()

        // 1. NPU backend -> 256 default
        val npuEngine = SequentialLlmEngine(
            responses = listOf("{\"response\": \"Hi from NPU\"}"),
            activeBackend = dev.loki.android.core.models.ExecutionBackend.NPU
        )
        val npuSession = ConversationSession(
            context = dummyContext,
            llmEngine = npuEngine,
            toolRegistry = registry
        )
        npuSession.processUtterance("what is the task", source = "TEXT").collect {}
        assertEquals(1, npuEngine.maxTokensList.size)
        assertEquals(256, npuEngine.maxTokensList[0])

        // 2. GPU backend -> 512 default
        val gpuEngine = SequentialLlmEngine(
            responses = listOf("{\"response\": \"Hi from GPU\"}"),
            activeBackend = dev.loki.android.core.models.ExecutionBackend.GPU
        )
        val gpuSession = ConversationSession(
            context = dummyContext,
            llmEngine = gpuEngine,
            toolRegistry = registry
        )
        gpuSession.processUtterance("what is the task", source = "TEXT").collect {}
        assertEquals(1, gpuEngine.maxTokensList.size)
        assertEquals(512, gpuEngine.maxTokensList[0])

        // 3. Explicit maxOutputTokens in agentConfig overrides backend default
        val customEngine = SequentialLlmEngine(
            responses = listOf("{\"response\": \"Hi from custom\"}"),
            activeBackend = dev.loki.android.core.models.ExecutionBackend.NPU
        )
        val customConfig = dev.loki.android.core.models.AgentConfig(
            generationConfig = dev.loki.android.core.models.GenerationConfig(maxOutputTokens = 128)
        )
        val customSession = ConversationSession(
            context = dummyContext,
            llmEngine = customEngine,
            toolRegistry = registry,
            agentConfig = customConfig
        )
        customSession.processUtterance("what is the task", source = "TEXT").collect {}
        assertEquals(1, customEngine.maxTokensList.size)
        assertEquals(128, customEngine.maxTokensList[0])
    }

    @Test
    fun `compact tool prompting injects schemas on first turn and omits on follow-up turns while preserving grammar`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()
        registry.register(DummyLookupTool())

        val engine = SequentialLlmEngine(
            responses = listOf(
                "{\"response\": \"I can help lookup contacts.\"}",
                "{\"response\": \"Found mom's contact.\"}"
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        // Turn 1: First turn in session segment
        session.processUtterance("help me with contacts", source = "TEXT").collect {}
        assertEquals(1, engine.prompts.size)
        val turn1Prompt = engine.prompts[0]
        assertTrue("Turn 1 prompt should contain tool schema", turn1Prompt.contains("Available tools (respond with JSON"))
        assertTrue("Turn 1 prompt should list lookup_contact", turn1Prompt.contains("lookup_contact"))
        assertNotNull("Turn 1 grammar must be present", engine.grammars[0])

        // Turn 2: Follow-up turn
        session.processUtterance("lookup mom", source = "TEXT").collect {}
        assertEquals(2, engine.prompts.size)
        val turn2Prompt = engine.prompts[1]
        assertFalse("Turn 2 prompt should NOT contain full tool schema", turn2Prompt.contains("Available tools (respond with JSON"))
        assertFalse("Turn 2 prompt should NOT list tool definition line", turn2Prompt.contains("lookup_contact(query: string)"))
        assertNotNull("Turn 2 grammar must still be present for constrained decoding", engine.grammars[1])
    }

    @Test
    fun `context compaction re-injects tool schemas on next turn`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val registry = ToolRegistry()
        registry.register(DummyLookupTool())

        val engine = SequentialLlmEngine(
            responses = listOf(
                "{\"response\": \"First turn response\"}",
                "{\"response\": \"Second turn response\"}",
                "{\"response\": \"Third turn response after compaction\"}"
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        // Turn 1
        session.processUtterance("turn 1", source = "TEXT").collect {}
        assertTrue(engine.prompts[0].contains("Available tools (respond with JSON"))

        // Turn 2 (no compaction yet -> compact prompt)
        session.processUtterance("turn 2", source = "TEXT").collect {}
        assertFalse(engine.prompts[1].contains("Available tools (respond with JSON"))

        // Simulate context compaction event from engine
        val events = mutableListOf<ConversationEvent>()
        engine.triggerCompaction("KV capacity reached")

        // Turn 3: should re-inject tool schemas because compaction occurred
        session.processUtterance("turn 3", source = "TEXT").collect { events.add(it) }
        assertTrue("Turn 3 prompt should re-inject tool schema after compaction", engine.prompts[2].contains("Available tools (respond with JSON"))
    }

    @Test
    fun `placeholder phone number in call_contact triggers lookup`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val lookupTool = DummyLookupTool()
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"phone_number": "...", "name": "Mom"}}""",
                """{"response": "Which Mom would you like to call?"}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        val events = mutableListOf<ConversationEvent>()
        session.processUtterance("call mom", source = "VOICE").collect { events.add(it) }

        assertTrue("lookup_contact should have executed because phone was placeholder '...'", lookupTool.executed)
        assertFalse("call_contact should not have executed without resolving contact first", callTool.executed)
        assertTrue(session.taskState is ContactResolution)
        assertEquals(2, (session.taskState as ContactResolution).candidates.size)
    }

    @Test
    fun `candidate id resolves from retained registry across conversational turns`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val lookupTool = DummyLookupTool()
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        val engine = SequentialLlmEngine(
            responses = listOf(
                // Turn 1: model calls call_contact with placeholder -> lookup runs -> returns 2 candidates -> model asks user
                """{"tool": "call_contact", "arguments": {"phone_number": "...", "name": "Mom"}}""",
                """{"response": "Which Mom would you like to call, c1 or c2?"}""",
                // Turn 2: user says "c2" -> model calls call_contact -> gate coaches confirmation -> model asks user
                """{"tool": "call_contact", "arguments": {"candidate_id": "c2"}}""",
                """{"response": "Shall I call Mom Mobile?"}""",
                // Turn 3: user confirms -> model calls call_contact with candidate_id c2 -> resolved from registry!
                """{"tool": "call_contact", "arguments": {"candidate_id": "c2"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        // Turn 1
        session.processUtterance("call mom", source = "VOICE").collect {}
        assertTrue("taskState should be retained after DirectResponse asking user to pick", session.taskState is ContactResolution)

        // Turn 2: user responds with "c2" -> gate triggers verbal confirmation ask
        session.processUtterance("c2", source = "VOICE_FOLLOW_UP").collect {}
        assertFalse("call_contact should not execute until verbally confirmed", callTool.executed)

        // Turn 3: user responds with "yes" -> executes
        session.processUtterance("yes, go ahead", source = "VOICE_FOLLOW_UP").collect {}

        assertTrue("call_contact should execute with resolved phone number", callTool.executed)
        assertEquals("9876543210", callTool.executedArgs?.get("phone_number"))
        assertEquals("Mom Mobile", callTool.executedArgs?.get("name"))
        assertEquals("c2", callTool.executedArgs?.get("candidate_id"))
    }

    @Test
    fun `disabled tools block renders scripted guidance template`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val callTool = DummyCallTool() // requires CALL_PHONE
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = SequentialLlmEngine(emptyList()),
            toolRegistry = ToolRegistry()
        )

        val prompt = session.buildPerTurnPrompt(
            availableTools = emptyList(),
            disabledTools = listOf(callTool to "android.permission.CALL_PHONE"),
            includeToolSchemas = true
        )

        assertTrue(prompt.contains("Disabled tools (permission not yet granted):"))
        assertTrue(prompt.contains("call_contact: This tool needs the CALL_PHONE permission — ask the user to grant it in Settings."))
    }

    @Test
    fun `compact schema mode outputs names and args only within 150 token budget`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = SequentialLlmEngine(emptyList()),
            toolRegistry = ToolRegistry()
        )

        val lookupTool = DummyLookupTool()
        val callTool = DummyCallTool()
        val tools = listOf(lookupTool, callTool)

        val compactPrompt = session.buildPerTurnPrompt(
            availableTools = tools,
            includeToolSchemas = true,
            compactToolSchemas = true
        )

        // Compact format: - name(arg1, arg2)
        assertTrue(compactPrompt.contains("- lookup_contact(query)"))
        assertTrue(compactPrompt.contains("- call_contact(candidate_id, name)"))
        // Descriptions should be omitted in compact mode
        assertFalse(compactPrompt.contains(lookupTool.description))
        assertFalse(compactPrompt.contains(callTool.description))
        // Target <= ~150 tokens (~600 chars)
        assertTrue("Compact tool prompt length (${compactPrompt.length}) must be <= 600 chars", compactPrompt.length <= 600)

        val fullPrompt = session.buildPerTurnPrompt(
            availableTools = tools,
            includeToolSchemas = true,
            compactToolSchemas = false
        )
        assertTrue(fullPrompt.contains(lookupTool.description))
        assertTrue(fullPrompt.contains(callTool.description))
    }

    @Test
    fun `auto-lookup directive is present in both compact and standard system prompts`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = SequentialLlmEngine(emptyList()),
            toolRegistry = ToolRegistry()
        )

        val directive = "When the user asks to call or message someone, immediately call lookup_contact with their name — do not ask for contact information. Only ask which contact when a lookup returns multiple matches."

        val compactSysPrompt = session.buildCoreSystemPrompt(isCompact = true)
        assertTrue(compactSysPrompt.contains(directive))
        assertTrue("Compact system prompt should be concise (<= 1000 chars)", compactSysPrompt.length <= 1000)

        val standardSysPrompt = session.buildCoreSystemPrompt(isCompact = false)
        assertTrue(standardSysPrompt.contains(directive))
        assertTrue(standardSysPrompt.contains("You are Loki, a private offline Android assistant"))
    }

    @Test
    fun `unresolvable candidate_id yields stale-selection coach error and no name re-query`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val lookupTool = DummyLookupTool()
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c99", "name": "Mom"}}""",
                """{"response": "Here are the available contacts again."}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        val events = session.processUtterance("call c99", source = "VOICE").toList()
        assertFalse("lookupTool should NOT be executed when candidate_id is invalid", lookupTool.executed)
        assertFalse("callTool should NOT be executed", callTool.executed)
        val toolExecuted = events.filterIsInstance<ConversationEvent.ToolExecuted>().firstOrNull()
        assertNotNull(toolExecuted)
        assertTrue(toolExecuted!!.result.error!!.contains("stale or invalid"))
    }

    @Test
    fun `duplicate-name candidates render masked suffixes in the task-state block and prompt contains no full phone numbers`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = SequentialLlmEngine(emptyList()),
            toolRegistry = ToolRegistry()
        )

        val candidates = listOf(
            ContactCandidate(id = "c1", name = "Mom", phoneNumber = "+1-555-012-3421"),
            ContactCandidate(id = "c2", name = "Mom", phoneNumber = "+1-555-987-6574"),
            ContactCandidate(id = "c3", name = "Dad", phoneNumber = "+1-555-111-2233")
        )

        val stateBlock = session.renderTaskState(ContactResolution(candidates = candidates))
        assertTrue(stateBlock.contains("- [c1] Mom — ending in 21"))
        assertTrue(stateBlock.contains("- [c2] Mom — ending in 74"))
        assertTrue(stateBlock.contains("- [c3] Dad"))
        assertFalse(stateBlock.contains("+1-555-012-3421"))
        assertFalse(stateBlock.contains("+1-555-987-6574"))
        assertFalse(stateBlock.contains("+1-555-111-2233"))

        val coachMsg = ConversationSession.buildDuplicateDisambiguationCoachMessage(candidates)
        assertTrue(coachMsg.contains("[c1] Mom — ending in 21; [c2] Mom — ending in 74; [c3] Dad"))
        assertFalse(coachMsg.contains("+1-555-012-3421"))
        assertFalse(coachMsg.contains("+1-555-987-6574"))
        assertFalse(coachMsg.contains("+1-555-111-2233"))

        val speechLabel1 = ConversationSession.formatCandidateSpeechLabel(candidates[0], isDuplicateName = true)
        val speechLabel2 = ConversationSession.formatCandidateSpeechLabel(candidates[1], isDuplicateName = true)
        val speechLabel3 = ConversationSession.formatCandidateSpeechLabel(candidates[2], isDuplicateName = false)
        assertEquals("Mom — number ending in 21", speechLabel1)
        assertEquals("Mom — number ending in 74", speechLabel2)
        assertEquals("Dad", speechLabel3)
        assertFalse(speechLabel1.contains("c1"))
        assertFalse(speechLabel2.contains("c2"))
    }

    @Test
    fun `completed call_contact clears contactCandidateRegistry`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val lookupTool = DummyLookupTool()
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        val sharedRegistry = mutableMapOf<String, ContactCandidate>()
        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"phone_number": "...", "name": "Mom"}}""",
                """{"response": "Which Mom?"}""",
                """{"tool": "call_contact", "arguments": {"candidate_id": "c1"}}""",
                """{"response": "Shall I call Mom?"}""",
                """{"tool": "call_contact", "arguments": {"candidate_id": "c1"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            contactCandidateRegistry = sharedRegistry
        )

        session.processUtterance("call mom", source = "VOICE").collect {}
        assertEquals(4, sharedRegistry.size)

        session.processUtterance("the first one", source = "VOICE_FOLLOW_UP").collect {}
        assertFalse(callTool.executed)

        session.processUtterance("yes", source = "VOICE_FOLLOW_UP").collect {}
        assertTrue(callTool.executed)
        assertEquals(0, sharedRegistry.size)
    }

    @Test
    fun `output-sanity filter intercepts protocol artifacts and substitutes recovery message before TTS`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val engine = SequentialLlmEngine(
            responses = listOf(
                """<|tool_call>call: "call_contact(c3, null, \"Mom\")""""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = ToolRegistry()
        )

        val events = session.processUtterance("call mom", enableTts = true, source = "VOICE").toList()
        val speakingEvent = events.filterIsInstance<ConversationEvent.Speaking>().firstOrNull()
        val completedEvent = events.filterIsInstance<ConversationEvent.Completed>().firstOrNull()

        assertNotNull(completedEvent)
        assertEquals(ConversationSession.RECOVERY_RESPONSE_TEXT, completedEvent?.finalResponse)
        if (speakingEvent != null) {
            assertEquals(ConversationSession.RECOVERY_RESPONSE_TEXT, speakingEvent.text)
            assertFalse(speakingEvent.text.contains("<|"))
        }
    }

    @Test
    fun `output-sanity filter does NOT reject legitimate prose containing call or quoted tokens`() {
        val legitimate1 = "I can call Mom or Suraj for you."
        val legitimate2 = "I found contact c3 in your records."
        val legitimate3 = "Please confirm if you'd like to call this number."

        assertFalse(ConversationSession.containsProtocolArtifacts(legitimate1))
        assertFalse(ConversationSession.containsProtocolArtifacts(legitimate2))
        assertFalse(ConversationSession.containsProtocolArtifacts(legitimate3))

        val artifact1 = """<|tool_call>call: "call_contact(c3)""""
        val artifact2 = """<|im_start|>system"""
        val artifact3 = "```json\n{\"tool\": \"call_contact\"}\n```"
        val artifact4 = "{\"tool\": \"call_contact\", \"arguments\": {}}"

        assertTrue(ConversationSession.containsProtocolArtifacts(artifact1))
        assertTrue(ConversationSession.containsProtocolArtifacts(artifact2))
        assertTrue(ConversationSession.containsProtocolArtifacts(artifact3))
        assertTrue(ConversationSession.containsProtocolArtifacts(artifact4))
    }

    @Test
    fun `ask_user tool call emits AskUser event and completes turn without round-trip`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        var pendingUpdated: PendingAsk? = null
        val candidates = listOf(
            ContactCandidate("c1", "Mom", "1234567890"),
            ContactCandidate("c2", "Mom Mobile", "9876543210")
        )
        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "ask_user", "arguments": {"text": "Which Mom would you like to call?"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = ToolRegistry(),
            pendingAsk = PendingAsk("initial", candidates),
            onPendingAskUpdated = { pendingUpdated = it }
        )

        val events = session.processUtterance("Call Mom", enableTts = false).toList()
        val askUserEvent = events.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNotNull("Should emit AskUser event", askUserEvent)
        assertEquals("Which Mom would you like to call?", askUserEvent!!.question)

        val completedEvent = events.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertNotNull("Should emit Completed event", completedEvent)
        assertEquals("Which Mom would you like to call?", completedEvent!!.finalResponse)

        assertNotNull("PendingAsk should be updated", pendingUpdated)
        assertEquals("Which Mom would you like to call?", pendingUpdated!!.question)
        assertEquals(2, pendingUpdated!!.candidates.size)
    }

    @Test
    fun `malformed ask_user tool call sanitizes text before emitting AskUser`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "ask_user", "arguments": {"text": "<|im_start|>Which Mom?"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = ToolRegistry()
        )

        val events = session.processUtterance("Call Mom", enableTts = false).toList()
        val askUserEvent = events.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNotNull(askUserEvent)
        assertEquals(ConversationSession.RECOVERY_RESPONSE_TEXT, askUserEvent!!.question)
    }

    @Test
    fun `taskState renders disambiguation state context with matching contacts`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidates = listOf(
            ContactCandidate("c1", "Mom", "1234567890"),
            ContactCandidate("c2", "Mom Mobile", "9876543210")
        )
        val pending = PendingAsk(
            question = "Which Mom would you like to call?",
            candidates = candidates
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = ToolRegistry(),
            pendingAsk = pending
        )

        val rendered = session.renderTaskState(session.taskState!!)
        // Disambiguation state: shows matching contacts and select_contact instruction
        assertTrue(rendered.contains("Current Task: Contact Disambiguation"))
        assertTrue(rendered.contains("Emit select_contact with the matching candidate_id"))
        assertTrue(rendered.contains("- [c1] Mom"))
        assertTrue(rendered.contains("- [c2] Mom Mobile"))
    }

    @Test
    fun `disambiguation reply on TEXT source requires Confirm-Cancel card before execution`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(callTool)
        }
        val candidates = listOf(
            ContactCandidate("c1", "Mom Home", "1234567890"),
            ContactCandidate("c3", "Mom", "9876543210")
        )
        val pending = PendingAsk(
            question = "Which Mom would you like to call?",
            candidates = candidates
        )
        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c3", "name": "Mom"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            pendingAsk = pending
        )

        var confirmationRequiredEmitted = false
        var repeatBackReceived = ""

        val job = launch {
            session.processUtterance("only mom", source = "TEXT").collect { event ->
                if (event is ConversationEvent.ConfirmationRequired) {
                    confirmationRequiredEmitted = true
                    repeatBackReceived = event.repeatBack
                    session.respondToConfirmation(true)
                }
            }
        }
        job.join()

        assertTrue("ConfirmationRequired card event must be emitted on TEXT path", confirmationRequiredEmitted)
        assertTrue("call_contact should execute once confirmed", callTool.executed)
        assertEquals("9876543210", callTool.executedArgs?.get("phone_number"))
        assertEquals("Mom", callTool.executedArgs?.get("name"))
    }

    @Test
    fun `disambiguation reply on VOICE source blocks first-attempt call without prior ask_user confirmation`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(callTool)
        }
        val candidates = listOf(
            ContactCandidate("c1", "Mom Home", "1234567890"),
            ContactCandidate("c3", "Mom", "9876543210")
        )
        val pending = PendingAsk(
            question = "Which Mom would you like to call?",
            candidates = candidates
        )
        // Disambiguation reply "only mom" -> model emits call_contact directly in iteration 1 -> coached to ask confirmation -> model emits ask_user in iteration 2
        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c3", "name": "Mom"}}""",
                """{"tool": "ask_user", "arguments": {"text": "Shall I call Mom, the number ending in 10?"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            pendingAsk = pending
        )

        val events = session.processUtterance("only mom", source = "VOICE_FOLLOW_UP").toList()

        assertFalse("call_contact must NOT execute on first-attempt without prior verbal confirmation", callTool.executed)

        val coachedTurn = session.conversationContext.getTurns().find {
            it is ConversationTurn.ToolExecutionResult && it.result.error?.contains("Action requires verbal confirmation") == true
        }
        assertNotNull("App must coach model to ask for confirmation via ask_user", coachedTurn)
        assertTrue(coachedTurn!!.let { (it as ConversationTurn.ToolExecutionResult).result.error!!.contains("ask_user") })

        val askUserEvent = events.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNotNull("Turn must end in AskUser confirmation question", askUserEvent)
        assertEquals("Shall I call Mom, the number ending in 10?", askUserEvent!!.question)
    }

    @Test
    fun `bare ask_user model output is repaired and emits recovery question AskUser turn`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val engine = SequentialLlmEngine(
            responses = listOf(
                "ask_user"
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = ToolRegistry()
        )

        val events = session.processUtterance("call mom", enableTts = false).toList()
        val askUserEvent = events.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNotNull("Bare ask_user must repair and emit AskUser event", askUserEvent)
        assertEquals(ConversationSession.RECOVERY_RESPONSE_TEXT, askUserEvent!!.question)
    }

    @Test
    fun `sanitizer replaces standalone tool name in DirectResponse with recovery text`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"response": "call_contact"}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = ToolRegistry()
        )

        val events = session.processUtterance("call mom", enableTts = false).toList()
        val completedEvent = events.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertNotNull(completedEvent)
        assertEquals(ConversationSession.RECOVERY_RESPONSE_TEXT, completedEvent!!.finalResponse)
    }

    @Test
    fun `sanitizer allows prose containing word ask or call`() {
        assertFalse(ConversationSession.containsProtocolArtifacts("I will call Mom for you."))
        assertFalse(ConversationSession.containsProtocolArtifacts("Please ask which contact to call."))
        assertTrue(ConversationSession.containsProtocolArtifacts("call_contact"))
        assertTrue(ConversationSession.containsProtocolArtifacts("ask_user"))
        assertTrue(ConversationSession.containsProtocolArtifacts("lookup_contact"))
    }

    @Test
    fun `tool result phone numbers are masked in turn history while registry retains real number for dialing`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val realNumber = "+91 788 740 8491"
        val lookupTool = DummyLookupTool(
            contactsJson = """[{"id":"c1","name":"Suraj's Mom","number":"$realNumber"}]"""
        )
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "lookup_contact", "arguments": {"query": "Suraj"}}""",
                """{"tool": "ask_user", "arguments": {"text": "Shall I call Suraj's Mom?"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        session.processUtterance("call suraj", source = "VOICE", enableTts = false).toList()

        // Verify model-visible turn contains NO full phone numbers
        val toolTurn = session.conversationContext.getTurns().filterIsInstance<ConversationTurn.ToolExecutionResult>().firstOrNull()
        assertNotNull(toolTurn)
        val turnDataStr = toolTurn!!.result.data.toString()
        assertFalse("Model-visible tool turn must not contain full phone number", turnDataStr.contains(realNumber))
        assertTrue("Model-visible tool turn should contain masked suffix", turnDataStr.contains("ending in 91"))

        // Verify prompt built from context contains no full phone numbers
        val builtPrompt = session.conversationContext.buildPrompt("system")
        assertFalse("Prompt built from context must not contain full phone number", builtPrompt.contains(realNumber))

        // Verify app-side registry retains real unmasked number for actual dialing
        val registeredCandidate = session.contactCandidateRegistry["c1"]
        assertNotNull("Registry must retain candidate", registeredCandidate)
        assertEquals(realNumber, registeredCandidate!!.phoneNumber)
    }

    @Test
    fun `voice confirmation gate blocks call_contact when no confirmation question was spoken`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidate = ContactCandidate(id = "c3", name = "Mom", phoneNumber = "+919876543214")
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c3"}}"""
            )
        )
        val candidatesMap = mutableMapOf("c3" to candidate, "mom" to candidate)
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            contactCandidateRegistry = candidatesMap,
            pendingVoiceConfirmation = null
        )

        val events = session.processUtterance("call c3", source = "VOICE", enableTts = false).toList()
        val toolExecuted = events.filterIsInstance<ConversationEvent.ToolExecuted>().firstOrNull()
        assertNotNull("Tool execution event must be emitted", toolExecuted)
        assertFalse("Tool execution should have failed confirmation check", toolExecuted!!.result.success)
        assertTrue(toolExecuted.result.error!!.contains("Action requires verbal confirmation"))
        assertNotNull("Pending confirmation state must be created", session.pendingVoiceConfirmation)
        assertFalse("isAsked must be false before question is spoken", session.pendingVoiceConfirmation!!.isAsked)
        assertEquals(0, callTool.executedCount)
    }

    @Test
    fun `voice confirmation full valid flow allows call execution after question spoken`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidate = ContactCandidate(id = "c3", name = "Mom", phoneNumber = "+919876543214")
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        var managerPendingConfirm: PendingVoiceConfirmation? = null
        val candidatesMap = mutableMapOf("c3" to candidate, "mom" to candidate)

        // Turn 1: model tries call_contact -> gate blocks and coaches -> model asks user
        val engineTurn1 = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c3"}}""",
                """{"tool": "ask_user", "arguments": {"text": "Shall I call Mom, the number ending in 14?"}}"""
            )
        )
        val session1 = ConversationSession(
            context = dummyContext,
            llmEngine = engineTurn1,
            toolRegistry = registry,
            contactCandidateRegistry = candidatesMap,
            pendingVoiceConfirmation = managerPendingConfirm,
            onPendingVoiceConfirmationUpdated = { managerPendingConfirm = it }
        )
        val eventsTurn1 = session1.processUtterance("call mom", source = "VOICE", enableTts = false).toList()
        val askUserEvent1 = eventsTurn1.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNotNull("AskUser event must be emitted", askUserEvent1)
        assertEquals("Shall I call Mom, the number ending in 14?", askUserEvent1!!.question)
        assertNotNull("Manager pending confirmation must be set", managerPendingConfirm)
        assertTrue("isAsked must be true after ask_user turn", managerPendingConfirm!!.isAsked)
        assertEquals(0, callTool.executedCount)

        // Turn 2: user says affirmative -> model calls contact -> execution succeeds!
        val engineTurn2 = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c3"}}"""
            )
        )
        val session2 = ConversationSession(
            context = dummyContext,
            llmEngine = engineTurn2,
            toolRegistry = registry,
            contactCandidateRegistry = candidatesMap,
            pendingVoiceConfirmation = managerPendingConfirm,
            onPendingVoiceConfirmationUpdated = { managerPendingConfirm = it }
        )
        val eventsTurn2 = session2.processUtterance("yes, please call", source = "VOICE_FOLLOW_UP", enableTts = false).toList()
        val toolExecuted2 = eventsTurn2.filterIsInstance<ConversationEvent.ToolExecuted>().firstOrNull()
        assertNotNull(toolExecuted2)
        assertTrue("Execution must succeed after confirmation", toolExecuted2!!.result.success)
        assertEquals(1, callTool.executedCount)
        assertNull("Pending confirmation must be cleared on execution", managerPendingConfirm)
    }

    @Test
    fun `empty-args ask_user during pending confirmation speaks app repeat-back and marks as asked`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidate = ContactCandidate(id = "c3", name = "Mom", phoneNumber = "+919876543214")
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        var managerPendingConfirm: PendingVoiceConfirmation? = null
        val candidatesMap = mutableMapOf("c3" to candidate, "mom" to candidate)

        // Turn 1: model tries call_contact -> gate blocks -> model emits empty ask_user
        val engineTurn1 = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c3"}}""",
                """ask_user"""
            )
        )
        val session1 = ConversationSession(
            context = dummyContext,
            llmEngine = engineTurn1,
            toolRegistry = registry,
            contactCandidateRegistry = candidatesMap,
            pendingVoiceConfirmation = managerPendingConfirm,
            onPendingVoiceConfirmationUpdated = { managerPendingConfirm = it }
        )
        val eventsTurn1 = session1.processUtterance("call mom", source = "VOICE", enableTts = false).toList()
        val askUserEvent1 = eventsTurn1.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNotNull("AskUser event must be emitted", askUserEvent1)
        assertEquals("Shall I call Mom, the number ending in 14?", askUserEvent1!!.question)
        assertNotNull("Manager pending confirmation must exist", managerPendingConfirm)
        assertTrue("isAsked must be true via app-rendered repeat-back", managerPendingConfirm!!.isAsked)

        // Turn 2: next turn with name-only call_contact -> resolves to c3 -> allowed because isAsked is true
        val engineTurn2 = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"name": "Mom"}}"""
            )
        )
        val session2 = ConversationSession(
            context = dummyContext,
            llmEngine = engineTurn2,
            toolRegistry = registry,
            contactCandidateRegistry = candidatesMap,
            pendingVoiceConfirmation = managerPendingConfirm,
            onPendingVoiceConfirmationUpdated = { managerPendingConfirm = it }
        )
        val eventsTurn2 = session2.processUtterance("yes call", source = "VOICE_FOLLOW_UP", enableTts = false).toList()
        val toolExecuted2 = eventsTurn2.filterIsInstance<ConversationEvent.ToolExecuted>().firstOrNull()
        assertNotNull(toolExecuted2)
        assertTrue("Execution must succeed after app-rendered confirmation ask", toolExecuted2!!.result.success)
        assertEquals(1, callTool.executedCount)
    }

    @Test
    fun `empty-args ask_user outside pending confirmation speaks generic recovery and sets no confirmation marker`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val engine = SequentialLlmEngine(
            responses = listOf(
                """ask_user"""
            )
        )
        var managerPendingConfirm: PendingVoiceConfirmation? = null
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = ToolRegistry(),
            pendingVoiceConfirmation = managerPendingConfirm,
            onPendingVoiceConfirmationUpdated = { managerPendingConfirm = it }
        )
        val events = session.processUtterance("check status", source = "VOICE", enableTts = false).toList()
        val askUserEvent = events.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNotNull(askUserEvent)
        assertEquals(ConversationSession.RECOVERY_RESPONSE_TEXT, askUserEvent!!.question)
        assertNull("No pending confirmation marker should be set outside confirmation", managerPendingConfirm)
    }

    @Test
    fun `both name-only and candidate_id resolution to same contact require confirmation marker`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidate = ContactCandidate(id = "c3", name = "Mom", phoneNumber = "+919876543214")
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        // Test name-only resolution without confirmation -> blocks
        val engineNameOnly = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"name": "Mom"}}"""
            )
        )
        val sessionNameOnly = ConversationSession(
            context = dummyContext,
            llmEngine = engineNameOnly,
            toolRegistry = registry,
            contactCandidateRegistry = mutableMapOf("c3" to candidate, "mom" to candidate),
            pendingVoiceConfirmation = null
        )
        val events = sessionNameOnly.processUtterance("call mom", source = "VOICE", enableTts = false).toList()
        val toolExecuted = events.filterIsInstance<ConversationEvent.ToolExecuted>().firstOrNull()
        assertNotNull(toolExecuted)
        assertFalse("Name-only call_contact without confirmation must block", toolExecuted!!.result.success)
        assertEquals(0, callTool.executedCount)
    }

    @Test
    fun `renderTaskState contains declarative state description instead of negative enforcement rules`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidate = ContactCandidate(id = "c3", name = "Mom", phoneNumber = "+919876543214")
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = SequentialLlmEngine(emptyList()),
            toolRegistry = ToolRegistry(),
            pendingAsk = PendingAsk("Shall I call Mom, the number ending in 14?", listOf(candidate), selectedId = "c3")
        )

        // Unselected state: CONTACT_DISAMBIGUATION
        val unselectedState = ContactResolution(candidates = listOf(candidate), selectedId = null)
        val renderedUnselected = session.renderTaskState(unselectedState)
        assertTrue(renderedUnselected.contains("Current Task: Contact Disambiguation"))
        assertTrue(renderedUnselected.contains("Emit select_contact with the matching candidate_id"))
        // Negative enforcement removed — grammar gating handles this now
        assertFalse(renderedUnselected.contains("do NOT invoke call_contact"))
        assertFalse(renderedUnselected.contains("Invoke call_contact ONLY"))

        // Selected state: CALL_CONFIRMATION (before question is asked)
        val selectedState = ContactResolution(candidates = listOf(candidate), selectedId = "c3", isAsked = false)
        val renderedSelected = session.renderTaskState(selectedState)
        assertTrue(renderedSelected.contains("Current Task: Pending Confirmation"))
        assertTrue(renderedSelected.contains("Selected contact: Mom"))
        assertTrue(renderedSelected.contains("Ask the user for verbal confirmation"))
        // Negative enforcement removed — grammar gating handles this now
        assertFalse(renderedSelected.contains("do NOT invoke call_contact"))
        assertFalse(renderedSelected.contains("Invoke call_contact ONLY"))

        // Awaiting state: AWAITING_CONFIRMATION (after question is asked)
        val awaitingState = ContactResolution(candidates = listOf(candidate), selectedId = "c3", isAsked = true)
        val renderedAwaiting = session.renderTaskState(awaitingState)
        assertTrue(renderedAwaiting.contains("Current Task: Awaiting Confirmation"))
        assertTrue(renderedAwaiting.contains("Selected contact: Mom"))
        assertTrue(renderedAwaiting.contains("If affirmed, invoke call_contact"))
        assertFalse(renderedAwaiting.contains("do NOT invoke call_contact"))
    }

    @Test
    fun `call_contact executor ignores model-supplied phone_number and dials registry number`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidate = ContactCandidate(id = "c3", name = "Badi mummy", phoneNumber = "+919876543272")
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        val confirmState = PendingVoiceConfirmation(
            candidate = candidate,
            repeatBack = "Shall I call Badi mummy, the number ending in 72?",
            isAsked = true
        )
        val candidatesMap = mutableMapOf("c3" to candidate, "badi mummy" to candidate)

        // Model supplies a bogus phone_number "72" (masked suffix) and candidate_id "c3"
        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c3", "phone_number": "72"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            contactCandidateRegistry = candidatesMap,
            pendingVoiceConfirmation = confirmState
        )
        val events = session.processUtterance("yes please", source = "VOICE_FOLLOW_UP", enableTts = false).toList()
        val toolExecuted = events.filterIsInstance<ConversationEvent.ToolExecuted>().firstOrNull()
        assertNotNull(toolExecuted)
        assertTrue("Call must execute successfully", toolExecuted!!.result.success)
        assertEquals(1, callTool.executedCount)
        assertEquals("+919876543272", callTool.executedArgs?.get("phone_number"))
    }

    @Test
    fun `call_contact with unresolvable candidate fails to coach and never dials`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c99", "phone_number": "+1234567890"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            contactCandidateRegistry = mutableMapOf(),
            pendingVoiceConfirmation = null
        )
        val events = session.processUtterance("call c99", source = "VOICE", enableTts = false).toList()
        val toolExecuted = events.filterIsInstance<ConversationEvent.ToolExecuted>().firstOrNull()
        assertNotNull(toolExecuted)
        assertFalse("Unresolvable candidate must fail execution", toolExecuted!!.result.success)
        assertTrue(toolExecuted.result.error!!.contains("stale or invalid"))
        assertEquals(0, callTool.executedCount)
    }

    @Test
    fun `call_contact with string arguments fails to coach and never dials`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": "Badi mummy"}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            contactCandidateRegistry = mutableMapOf(),
            pendingVoiceConfirmation = null
        )
        val events = session.processUtterance("call badi mummy", source = "VOICE", enableTts = false).toList()
        val toolExecuted = events.filterIsInstance<ConversationEvent.ToolExecuted>().firstOrNull()
        assertNotNull(toolExecuted)
        assertFalse("String-arguments call_contact must fail execution", toolExecuted!!.result.success)
        assertEquals(0, callTool.executedCount)
    }

    @Test
    fun `repeated ask_user after confirmation question was already asked breaks loop and cancels`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidate = ContactCandidate(id = "c3", name = "Mom", phoneNumber = "+919876543214")
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        var managerPendingConfirm: PendingVoiceConfirmation? = PendingVoiceConfirmation(
            candidate = candidate,
            repeatBack = "Shall I call Mom, the number ending in 14?",
            isAsked = true
        )
        val candidatesMap = mutableMapOf("c3" to candidate, "mom" to candidate)

        // Model gets user saying "No" and emits ask_user again (the loop condition)
        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "ask_user", "arguments": {"text": "Shall I call Mom?"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            contactCandidateRegistry = candidatesMap,
            pendingVoiceConfirmation = managerPendingConfirm,
            onPendingVoiceConfirmationUpdated = { managerPendingConfirm = it }
        )

        val events = session.processUtterance("No", source = "VOICE_FOLLOW_UP", enableTts = false).toList()
        
        // Loop breaker must NOT emit AskUser event (mic should not re-arm)
        val askUserEvent = events.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNull("Loop breaker must not re-arm mic via AskUser event", askUserEvent)

        val completedEvent = events.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertNotNull(completedEvent)
        assertEquals("Okay, cancelled.", completedEvent!!.finalResponse)
        assertNull("Pending confirmation must be cleared", managerPendingConfirm)
        assertEquals(0, callTool.executedCount)
    }

    @Test
    fun `direct response after confirmation asked clears confirmation and task state`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val candidate = ContactCandidate(id = "c3", name = "Mom", phoneNumber = "+919876543214")
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply { register(callTool) }

        var managerPendingConfirm: PendingVoiceConfirmation? = PendingVoiceConfirmation(
            candidate = candidate,
            repeatBack = "Shall I call Mom, the number ending in 14?",
            isAsked = true
        )
        val candidatesMap = mutableMapOf("c3" to candidate, "mom" to candidate)

        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"response": "Okay, cancelled."}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry,
            contactCandidateRegistry = candidatesMap,
            pendingVoiceConfirmation = managerPendingConfirm,
            onPendingVoiceConfirmationUpdated = { managerPendingConfirm = it }
        )

        val events = session.processUtterance("No don't call", source = "VOICE_FOLLOW_UP", enableTts = false).toList()
        val askUserEvent = events.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNull("Direct cancellation must not emit AskUser", askUserEvent)

        val completedEvent = events.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertNotNull(completedEvent)
        assertEquals("Okay, cancelled.", completedEvent!!.finalResponse)
        assertNull("Pending confirmation must be cleared", managerPendingConfirm)
    }

    @Test
    fun `compact system prompt contains multilingual language matching directive`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = SequentialLlmEngine(emptyList()),
            toolRegistry = ToolRegistry()
        )

        val compactSysPrompt = session.buildCoreSystemPrompt(isCompact = true)
        assertTrue(compactSysPrompt.contains("Always respond in the same language the user writes or speaks in"))
    }

    // ── Task 1.3: Unique exact-match pre-selection unit tests ────────────────

    @Test
    fun `lookup_contact with unique exact name match auto-selects and skips disambiguation`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}

        // "Call Mom" — contacts: "Mom", "Suraj's Mom", "Prashik's Mom"
        val contactsJson = """[
            {"id":"c1","name":"Mom","number":"1234567890"},
            {"id":"c2","name":"Suraj's Mom","number":"9876543210"},
            {"id":"c3","name":"Prashik's Mom","number":"1122334455"}
        ]"""
        val lookupTool = DummyLookupTool(contactsJson = contactsJson)
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        val engine = SequentialLlmEngine(
            responses = listOf(
                // Turn 1: lookup returns multiple contacts, "Mom" is exact match → auto-select
                """{"tool": "lookup_contact", "arguments": {"query": "Mom"}}""",
                // Turn 1 iteration 2: model should ask for confirmation (not disambiguation)
                """{"tool": "ask_user", "arguments": {"text": "Shall I call Mom, the number ending in 90?"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        val events = mutableListOf<ConversationEvent>()
        session.processUtterance("Call Mom", source = "VOICE").collect { events.add(it) }

        assertTrue("lookup_contact should have executed", lookupTool.executed)
        // Key assertion: state should be CALL_CONFIRMATION (selectedId != null), not CONTACT_DISAMBIGUATION
        val resolution = session.taskState as? ContactResolution
        assertNotNull("taskState must be ContactResolution", resolution)
        assertEquals("c1", resolution!!.selectedId)  // "Mom" was auto-selected
        assertFalse("confirmed must be false (pending confirmation)", resolution.confirmed)

        // Disambiguation was SKIPPED — model should be asking for confirmation, not picking a contact
        assertNotNull("pendingVoiceConfirmation must be set", session.pendingVoiceConfirmation)
        assertTrue("isAsked must be true after confirmation question is emitted", resolution.isAsked)
        assertTrue("pendingVoiceConfirmation.isAsked must be true", session.pendingVoiceConfirmation!!.isAsked)
        assertEquals("calling", session.activeCapability)
    }

    @Test
    fun `two-turn voice flow - exact-match to confirmation question to Yes to call_contact`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}

        val contactsJson = """[
            {"id":"c1","name":"Mom","number":"1234567890"},
            {"id":"c2","name":"Suraj's Mom","number":"9876543210"},
            {"id":"c3","name":"Prashik's Mom","number":"1122334455"}
        ]"""
        val lookupTool = DummyLookupTool(contactsJson = contactsJson)
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        var sharedConfirm: PendingVoiceConfirmation? = null
        var sharedTaskState: TaskState? = null
        val sharedCandidates = mutableMapOf<String, ContactCandidate>()

        // ── Turn 1: "Call Mom" ────────────────────────────────────────────────
        val engineTurn1 = SequentialLlmEngine(
            responses = listOf(
                // Iteration 1: lookup_contact
                """{"tool": "lookup_contact", "arguments": {"query": "Mom"}}""",
                // Iteration 2: model generates confirmation question via ask_user
                """{"tool": "ask_user", "arguments": {"text": "Shall I call Mom, the number ending in 90?"}}"""
            )
        )
        val session1 = ConversationSession(
            context = dummyContext,
            llmEngine = engineTurn1,
            toolRegistry = registry,
            contactCandidateRegistry = sharedCandidates,
            pendingVoiceConfirmation = sharedConfirm,
            onPendingVoiceConfirmationUpdated = { sharedConfirm = it }
        )

        val eventsTurn1 = session1.processUtterance("Call Mom", source = "VOICE").toList()
        assertTrue("lookup_contact must have executed", lookupTool.executed)

        val askUserEvent = eventsTurn1.filterIsInstance<ConversationEvent.AskUser>().firstOrNull()
        assertNotNull("Must emit AskUser confirmation question", askUserEvent)
        assertEquals("Shall I call Mom, the number ending in 90?", askUserEvent!!.question)

        // Verify taskState and pendingVoiceConfirmation transitioned to isAsked = true
        val res1 = session1.taskState as? ContactResolution
        assertNotNull(res1)
        assertEquals("c1", res1!!.selectedId)
        assertTrue("isAsked must be true after question is asked", res1.isAsked)
        assertFalse("confirmed must still be false", res1.confirmed)
        assertNotNull("pendingVoiceConfirmation must exist", sharedConfirm)
        assertTrue("pendingVoiceConfirmation.isAsked must be true", sharedConfirm!!.isAsked)
        sharedTaskState = res1

        // ── Turn 2: User responds "Yes" ───────────────────────────────────────
        // In Turn 2, verify that call_contact is AVAILABLE in the tools / grammar
        val availableToolsTurn2 = registry.getAvailableTools(
            context = dummyContext,
            activeCapability = "calling",
            advancingTool = sharedTaskState?.advancingTool,
            taskState = sharedTaskState as? TaskStateGate
        )
        assertTrue("call_contact MUST be available during awaiting confirmation", availableToolsTurn2.any { it.name == "call_contact" })
        assertFalse("ask_user must NOT be available during awaiting confirmation", availableToolsTurn2.any { it.name == "ask_user" })

        val engineTurn2 = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "call_contact", "arguments": {"candidate_id": "c1", "name": "Mom"}}"""
            )
        )
        val session2 = ConversationSession(
            context = dummyContext,
            llmEngine = engineTurn2,
            toolRegistry = registry,
            contactCandidateRegistry = sharedCandidates,
            pendingVoiceConfirmation = sharedConfirm,
            onPendingVoiceConfirmationUpdated = { sharedConfirm = it }
        )

        val eventsTurn2 = session2.processUtterance("Yes", source = "VOICE_FOLLOW_UP").toList()

        // Verify call was executed
        assertTrue("call_contact MUST execute on Yes affirmation", callTool.executed)
        assertEquals(1, callTool.executedCount)
        assertEquals("1234567890", callTool.executedArgs?.get("phone_number"))
        assertEquals("Mom", callTool.executedArgs?.get("name"))

        val completedTurn2 = eventsTurn2.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertNotNull("Turn 2 must complete", completedTurn2)
        assertEquals("Calling Mom", completedTurn2!!.finalResponse)

        // Verify state is cleared on successful completion
        assertNull("activeCapability must be cleared", session2.activeCapability)
        assertNull("taskState must be cleared", session2.taskState)
        assertNull("pendingVoiceConfirmation must be cleared", sharedConfirm)
    }

    @Test
    fun `lookup_contact with duplicate exact names does NOT auto-select, enters disambiguation`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}

        // "Call Mom" — two contacts both named "Mom"
        val contactsJson = """[
            {"id":"c1","name":"Mom","number":"1234567890"},
            {"id":"c2","name":"Mom","number":"9876543210"}
        ]"""
        val lookupTool = DummyLookupTool(contactsJson = contactsJson)
        val registry = ToolRegistry().apply { register(lookupTool) }

        val engine = SequentialLlmEngine(
            responses = listOf(
                """{"tool": "lookup_contact", "arguments": {"query": "Mom"}}""",
                """{"response": "Which Mom would you like to call?"}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        session.processUtterance("Call Mom", source = "VOICE").collect {}

        val resolution = session.taskState as? ContactResolution
        assertNotNull(resolution)
        // Two exact matches → selectedId stays null → stays in CONTACT_DISAMBIGUATION
        assertNull("selectedId must be null when multiple exact matches exist", resolution!!.selectedId)
    }

    // ── Task 2.4: State-scoped grammar gating tests (TaskState-based) ────────

    @Test
    fun `ContactResolution in disambiguation state restricts grammar to select_contact`() {
        val registry = ToolRegistry()
        registry.register(MockScopedTool("general_tool", "general"))
        registry.register(MockScopedTool("lookup_contact", "calling"))
        registry.register(MockScopedTool("call_contact", "calling"))
        registry.register(MockScopedTool("ask_user", "general"))
        registry.register(MockScopedTool("select_contact", "calling", isInternal = true))

        val disambiguationState = ContactResolution(
            candidates = listOf(ContactCandidate("c1", "Mom", "123")),
            selectedId = null,  // CONTACT_DISAMBIGUATION
            confirmed = false
        )

        val grammar = GrammarBuilder.buildFrom(
            toolRegistry = registry,
            activeCapability = "calling",
            advancingTool = "select_contact",
            taskState = disambiguationState
        )

        // Only select_contact (and general tools like general_tool, ask_user) should be in grammar
        assertTrue("select_contact must be in disambiguation grammar", grammar.contains("select_contact"))
        assertTrue("general tools must still be available", grammar.contains("general_tool"))
        // Restricted tools must NOT appear
        assertFalse("call_contact must NOT be in disambiguation grammar", grammar.contains("call_contact"))
        assertFalse("lookup_contact must NOT be in disambiguation grammar", grammar.contains("lookup_contact"))
    }

    @Test
    fun `ContactResolution in confirmation state hides call_contact from grammar`() {
        val registry = ToolRegistry()
        registry.register(MockScopedTool("general_tool", "general"))
        registry.register(MockScopedTool("ask_user", "general"))
        registry.register(MockScopedTool("call_contact", "calling"))
        registry.register(MockScopedTool("select_contact", "calling", isInternal = true))

        val confirmationState = ContactResolution(
            candidates = listOf(ContactCandidate("c1", "Mom", "123")),
            selectedId = "c1",   // CALL_CONFIRMATION
            confirmed = false
        )

        val grammar = GrammarBuilder.buildFrom(
            toolRegistry = registry,
            activeCapability = "calling",
            advancingTool = "call_contact",
            taskState = confirmationState
        )

        // call_contact must be HIDDEN during confirmation
        assertFalse("call_contact must NOT be in confirmation grammar", grammar.contains("call_contact"))
        // General tools still available
        assertTrue("general_tool must be in grammar", grammar.contains("general_tool"))
    }

    @Test
    fun `ContactResolution confirmed state exposes call_contact in grammar`() {
        val registry = ToolRegistry()
        registry.register(MockScopedTool("call_contact", "calling"))
        registry.register(MockScopedTool("general_tool", "general"))

        val confirmedState = ContactResolution(
            candidates = listOf(ContactCandidate("c1", "Mom", "123")),
            selectedId = "c1",
            confirmed = true  // CONFIRMED
        )

        val grammar = GrammarBuilder.buildFrom(
            toolRegistry = registry,
            activeCapability = "calling",
            advancingTool = null,
            taskState = confirmedState
        )

        // No gating restrictions in confirmed state — call_contact is available
        assertTrue("call_contact must be in confirmed grammar", grammar.contains("call_contact"))
    }

    // ── Task 3.3: Full multi-turn state machine flow ──────────────────────────

    @Test
    fun `full multi-turn flow - lookup to disambiguation to select_contact to affirmation to call`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        // Use "Mom Home" and "Mom Mobile" so query "Mom" doesn't trigger exact-match auto-selection
        val lookupTool = DummyLookupTool(
            contactsJson = """[{"id":"c1","name":"Mom Home","number":"1234567890"},{"id":"c2","name":"Mom Mobile","number":"9876543210"}]"""
        )
        val callTool = DummyCallTool()
        val registry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        val engine = SequentialLlmEngine(
            responses = listOf(
                // Turn 1: lookup → multiple results (no exact match) → disambiguation
                """{"tool": "lookup_contact", "arguments": {"query": "Mom"}}""",
                """{"response": "I found Mom Home and Mom Mobile. Which one would you like to call?"}""",

                // Turn 2: user says "the first one" → model emits select_contact
                """{"tool": "select_contact", "arguments": {"candidate_id": "c1"}}""",
                """{"response": "Shall I call Mom Home?"}""",

                // Turn 3: user says "yes" → model invokes call_contact
                """{"tool": "call_contact", "arguments": {"candidate_id": "c1"}}"""
            )
        )
        val session = ConversationSession(
            context = dummyContext,
            llmEngine = engine,
            toolRegistry = registry
        )

        // ── Turn 1: "Call Mom" ──────────────────────────────────────────────────
        val events1 = mutableListOf<ConversationEvent>()
        session.processUtterance("Call Mom", source = "VOICE").collect { events1.add(it) }

        assertTrue(lookupTool.executed)
        assertEquals("calling", session.activeCapability)
        val res1 = session.taskState as? ContactResolution
        assertNotNull(res1)
        assertNull("selectedId must be null (disambiguation)", res1!!.selectedId)
        assertEquals("select_contact", res1.advancingTool)

        // ── Turn 2: "the first one" → select_contact(c1) ───────────────────────
        val events2 = mutableListOf<ConversationEvent>()
        session.processUtterance("the first one", source = "VOICE_FOLLOW_UP").collect { events2.add(it) }

        val res2 = session.taskState as? ContactResolution
        assertNotNull(res2)
        assertEquals("c1", res2!!.selectedId)
        assertFalse("confirmed must still be false", res2.confirmed)
        assertEquals("call_contact", res2.advancingTool)

        // ── Turn 3: "yes" → call_contact(c1) ────────────────────────────────────
        val events3 = mutableListOf<ConversationEvent>()
        session.processUtterance("yes, go ahead", source = "VOICE_FOLLOW_UP").collect { events3.add(it) }

        assertTrue("call_contact must have executed", callTool.executed)
        assertEquals("1234567890", callTool.executedArgs?.get("phone_number"))
        assertEquals("Mom Home", callTool.executedArgs?.get("name"))

        val completed3 = events3.filterIsInstance<ConversationEvent.Completed>().firstOrNull()
        assertEquals("Calling Mom Home", completed3?.finalResponse)

        assertNull("activeCapability must be cleared on call completion", session.activeCapability)
        assertNull("taskState must be cleared on call completion", session.taskState)
    }
}
