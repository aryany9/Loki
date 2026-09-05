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
                // Turn 2: user says "c2" -> model calls call_contact with candidate_id c2 -> resolved from registry!
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

        // Turn 2: user responds with "c2" (VOICE_FOLLOW_UP so verbal confirmation check passes)
        session.processUtterance("c2", source = "VOICE_FOLLOW_UP").collect {}

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
        assertTrue(compactPrompt.contains("- call_contact(phone_number, candidate_id, name)"))
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
        assertTrue("Compact system prompt should be concise (<= 350 chars)", compactSysPrompt.length <= 350)

        val standardSysPrompt = session.buildCoreSystemPrompt(isCompact = false)
        assertTrue(standardSysPrompt.contains(directive))
        assertTrue(standardSysPrompt.contains("You are Loki, a private offline Android assistant"))
    }
}
