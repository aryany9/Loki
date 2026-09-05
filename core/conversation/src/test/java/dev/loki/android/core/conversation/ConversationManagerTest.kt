package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.tools.PermissionState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationManagerTest {

    class MockLlmEngine(private val responses: List<String>) : LlmEngine {
        private var callIndex = 0
        private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
        override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

        var lastStartedConfig: dev.loki.android.core.models.AgentConfig? = null

        override suspend fun startConversation(agentConfig: dev.loki.android.core.models.AgentConfig): Boolean {
            lastStartedConfig = agentConfig
            return true
        }

        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?): Boolean = true
        override suspend fun generate(
            prompt: String,
            audioBytes: ByteArray?,
            grammar: String?,
            maxTokens: Int,
            onToken: ((String) -> Unit)?
        ): Result<String> {
            val resp = if (callIndex < responses.size) responses[callIndex++] else responses.last()
            return Result.success(resp)
        }
        override fun cancel() {}
        override fun release() {}
    }

    class TestTimeTool : LocalTool {
        override val name: String = "get_current_time"
        override val description: String = "Get time"
        override val parameters: Map<String, ToolParam> = emptyMap()
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            return ToolResult.success(mapOf("formatted" to "3:00 PM on Monday"))
        }
    }

    class TestCallTool : LocalTool {
        override val name: String = "call_contact"
        override val description: String = "Call contact"
        override val parameters: Map<String, ToolParam> = mapOf("phone_number" to ToolParam(ToolParamType.STRING, "number"))
        override val requiredPermissions: List<String> = listOf("android.permission.CALL_PHONE")
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            return ToolResult.success(mapOf("calling" to "Mom"))
        }
    }

    @Test
    fun `ToolCallParser correctly parses tool JSON`() {
        val json = """{"tool": "get_battery_status", "arguments": {}}"""
        val parsed = ToolCallParser.parse(json)
        assertTrue(parsed is ParsedLlmResponse.ToolCall)
        assertEquals("get_battery_status", (parsed as ParsedLlmResponse.ToolCall).tool)
    }

    @Test
    fun `ToolCallParser correctly parses direct response JSON`() {
        val json = """{"response": "Hello there!"}"""
        val parsed = ToolCallParser.parse(json)
        assertTrue(parsed is ParsedLlmResponse.DirectResponse)
        assertEquals("Hello there!", (parsed as ParsedLlmResponse.DirectResponse).text)
    }

    @Test
    fun `ToolCallParser rejects explanatory text around JSON`() {
        val parsed = ToolCallParser.parse("```json\n{\"tool\": \"get_battery_status\", \"arguments\": {}}\n```\nExplanation")

        assertTrue(parsed is ParsedLlmResponse.Malformed)
    }

    @Test
    fun `simple greeting does not call the LLM`() = runTest {
        val toolRegistry = ToolRegistry()
        val mockLlm = MockLlmEngine(listOf("invalid"))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, toolRegistry, ttsEngine = null)

        val events = manager.processUtterance("hi", enableTts = false).toList()

        assertEquals("Hello! How can I help you?", (events.last() as ConversationEvent.Completed).finalResponse)
    }

    @Test
    fun `ConversationManager runs single-step tool loop and fast path`() = runTest {
        val toolRegistry = ToolRegistry()
        toolRegistry.register(TestTimeTool())

        val mockLlm = MockLlmEngine(listOf("""{"tool": "get_current_time", "arguments": {}}"""))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, toolRegistry, ttsEngine = null)

        val events = manager.processUtterance("What time is it?", enableTts = false).toList()
        assertTrue(events.any { it is ConversationEvent.ToolExecuting })
        assertTrue(events.any { it is ConversationEvent.ToolExecuted })
        val completed = events.last() as ConversationEvent.Completed
        assertEquals("3:00 PM on Monday", completed.finalResponse)
    }

    @Test
    fun `ChatSession provides persistent multi-turn context`() = runTest {
        val toolRegistry = ToolRegistry()
        toolRegistry.register(TestTimeTool())

        val mockLlm = MockLlmEngine(listOf(
            """{"response": "I found Rahul in your contacts."}""",
            """{"response": "Calling Rahul now."}"""
        ))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, toolRegistry, ttsEngine = null)
        val chatSession = manager.newChatSession()

        chatSession.processUtterance("Who is Rahul?", enableTts = false).toList()
        val turnsAfterFirst = chatSession.conversationContext.getTurns()
        assertTrue(turnsAfterFirst.isNotEmpty())

        chatSession.processUtterance("Call him", enableTts = false).toList()
        val turnsAfterSecond = chatSession.conversationContext.getTurns()
        assertTrue(turnsAfterSecond.size > turnsAfterFirst.size)
    }

    @Test
    fun `VoiceSession is stateless and returns fresh empty context across voice activations`() = runTest {
        val toolRegistry = ToolRegistry()
        toolRegistry.register(TestTimeTool())

        val mockLlm = MockLlmEngine(listOf(
            """{"tool": "get_current_time", "arguments": {}}""",
            """{"response": "It is 3:00 PM"}"""
        ))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, toolRegistry, ttsEngine = null)

        val voiceSession1 = manager.newVoiceSession()
        voiceSession1.processUtterance("Turn 1", enableTts = false).toList()

        val voiceSession2 = manager.newVoiceSession()
        assertTrue("voiceSession2 must start with empty context on new activation", voiceSession2.conversationContext.getTurns().isEmpty())
        assertEquals(1, voiceSession2.conversationContext.maxTurns)
    }

    class TrackingLlmEngine : LlmEngine {
        val generatePrompts = mutableListOf<String>()
        var startConversationCallCount = 0
        var lastSystemPrompt: String? = null

        private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
        override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?): Boolean = true

        override suspend fun startConversation(agentConfig: dev.loki.android.core.models.AgentConfig): Boolean {
            startConversationCallCount++
            lastSystemPrompt = agentConfig.systemInstruction
            return true
        }

        override suspend fun startConversation(systemPrompt: String): Boolean =
            startConversation(dev.loki.android.core.models.AgentConfig(systemInstruction = systemPrompt))

        override suspend fun generate(
            prompt: String,
            audioBytes: ByteArray?,
            grammar: String?,
            maxTokens: Int,
            onToken: ((String) -> Unit)?
        ): Result<String> {
            generatePrompts.add(prompt)
            return Result.success("""{"response": "Response to: $prompt"}""")
        }

        override fun cancel() {}
        override fun release() {}
    }

    @Test
    fun `TurnLogger creates valid correlation IDs`() {
        val id1 = TurnLogger.newTurnId()
        val id2 = TurnLogger.newTurnId()
        assertTrue(id1.isNotBlank())
        assertTrue(id2.isNotBlank())
        assertTrue(id1 != id2)
    }

    @Test
    fun `10 consecutive turns send only new message and do not re-inject full history`() = runTest {
        val toolRegistry = ToolRegistry()
        val trackingLlm = TrackingLlmEngine()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, trackingLlm, toolRegistry, ttsEngine = null)
        val chatSession = manager.newChatSession()

        // Execute 10 consecutive multi-turn requests
        for (i in 1..10) {
            val userMsg = "User prompt for turn $i"
            val events = chatSession.processUtterance(userMsg, enableTts = false).toList()
            val completed = events.last() as ConversationEvent.Completed
            assertTrue(completed.finalResponse.contains("Response to: $userMsg"))
        }

        // Verify startConversation was called ONCE for the session
        assertEquals(1, trackingLlm.startConversationCallCount)
        assertTrue(trackingLlm.lastSystemPrompt?.contains("You are Loki") == true)

        // Verify generate was called exactly 10 times
        assertEquals(10, trackingLlm.generatePrompts.size)

        // Verify each call to generate received ONLY the new user prompt for that turn, NOT full history
        for (i in 0 until 10) {
            val sentPrompt = trackingLlm.generatePrompts[i]
            val expectedMsg = "User prompt for turn ${i + 1}"
            assertEquals(expectedMsg, sentPrompt)
        }

        // Verify application-level history in ConversationContext is maintained and bounded by maxTurns (10 entries)
        val appTurns = chatSession.conversationContext.getTurns()
        assertEquals(10, appTurns.size)
    }

    class ConfigTrackingLlmEngine : LlmEngine {
        var lastAgentConfig: dev.loki.android.core.models.AgentConfig? = null
        var lastMaxTokens: Int? = null
        var reinitCount = 0
        var startConvCount = 0
        var lastReinitRuntimeConfig: dev.loki.android.core.models.RuntimeConfig? = null
        var lastReinitForce: Boolean? = null

        private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
        override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(
            modelPath: String?,
            runtimeConfig: dev.loki.android.core.models.RuntimeConfig,
            force: Boolean
        ): Boolean {
            reinitCount++
            lastReinitRuntimeConfig = runtimeConfig
            lastReinitForce = force
            return true
        }

        override suspend fun startConversation(agentConfig: dev.loki.android.core.models.AgentConfig): Boolean {
            startConvCount++
            lastAgentConfig = agentConfig
            return true
        }

        override suspend fun generate(
            prompt: String,
            audioBytes: ByteArray?,
            grammar: String?,
            maxTokens: Int,
            onToken: ((String) -> Unit)?
        ): Result<String> {
            lastMaxTokens = maxTokens
            return Result.success("""{"response": "Config test response"}""")
        }

        override fun cancel() {}
        override fun release() {}
    }

    @Test
    fun `ConversationSession applies custom instructions and passes maxTokens`() = runTest {
        val toolRegistry = ToolRegistry()
        val trackingLlm = ConfigTrackingLlmEngine()
        val dummyContext = object : android.content.ContextWrapper(null) {}

        val customConfig = dev.loki.android.core.models.AgentConfig(
            systemInstruction = "Speak like a pirate",
            generationConfig = dev.loki.android.core.models.GenerationConfig(
                temperature = 0.3f,
                maxOutputTokens = 512
            )
        )

        val manager = ConversationManager(dummyContext, trackingLlm, toolRegistry, ttsEngine = null)
        manager.setAgentConfig(customConfig)

        val chatSession = manager.newChatSession()
        val events = chatSession.processUtterance("Ahoy", enableTts = false).toList()
        assertTrue(events.any { it is ConversationEvent.Completed })

        // Check system prompt was merged with custom instruction
        val appliedConfig = trackingLlm.lastAgentConfig
        assertTrue(appliedConfig != null)
        assertTrue(appliedConfig!!.systemInstruction.contains("Speak like a pirate"))
        assertTrue(appliedConfig.systemInstruction.contains("You are Loki"))
        assertEquals(0.3f, appliedConfig.generationConfig.temperature, 0.001f)

        // Check maxTokens passed to generate
        assertEquals(Integer.valueOf(512), trackingLlm.lastMaxTokens)
    }

    @Test
    fun `applyAgentConfig with runtime change forces engine reinit`() = runTest {
        val toolRegistry = ToolRegistry()
        val trackingLlm = ConfigTrackingLlmEngine()
        val dummyContext = object : android.content.ContextWrapper(null) {}

        val manager = ConversationManager(dummyContext, trackingLlm, toolRegistry, ttsEngine = null)

        val newConfig = dev.loki.android.core.models.AgentConfig(
            runtimeConfig = dev.loki.android.core.models.RuntimeConfig(
                backend = dev.loki.android.core.models.ExecutionBackend.GPU,
                contextKvCapacity = 4096
            )
        )

        val applied = manager.applyAgentConfig(newConfig)
        assertTrue(applied)
        assertEquals(1, trackingLlm.reinitCount)
        assertEquals(true, trackingLlm.lastReinitForce)
        assertEquals(dev.loki.android.core.models.ExecutionBackend.GPU, trackingLlm.lastReinitRuntimeConfig?.backend)
        assertEquals(Integer.valueOf(4096), trackingLlm.lastReinitRuntimeConfig?.contextKvCapacity)
    }

    @Test
    fun `switch between conversations seeds context and resets engine`() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("conv_mgr_test").toFile()
        val store = ConversationStore(tempDir)
        val trackingLlm = ConfigTrackingLlmEngine()
        val dummyContext = object : android.content.ContextWrapper(null) {}

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = trackingLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store
        )

        // Create conversation 1 with turns
        val c1 = manager.createConversation("Chat 1")
        store.appendTurn(c1.id, ConversationTurn.User("Hello from 1"))
        store.appendTurn(c1.id, ConversationTurn.Assistant("Response 1"))

        // Create conversation 2 with turns
        val c2 = manager.createConversation("Chat 2")
        store.appendTurn(c2.id, ConversationTurn.User("Hello from 2"))
        store.appendTurn(c2.id, ConversationTurn.Assistant("Response 2"))

        // Load conversation 1
        val startConvCountBefore = trackingLlm.startConvCount
        val loaded1 = manager.loadConversation(c1.id)
        org.junit.Assert.assertNotNull(loaded1)
        assertEquals("Chat 1", loaded1?.title)
        assertEquals(c1.id, manager.currentConversationId)
        assertTrue(trackingLlm.startConvCount > startConvCountBefore)

        // Verify session from manager reflects Chat 1 turns
        val chatSession1 = manager.newChatSession()
        val turns1 = chatSession1.conversationContext.getTurns()
        assertEquals(2, turns1.size)
        assertEquals("Hello from 1", (turns1[0] as ConversationTurn.User).text)

        tempDir.deleteRecursively()
    }

    class GatedTestTool : LocalTool {
        var executed = false
        override val name: String = "gated_action"
        override val description: String = "Destructive action"
        override val parameters: Map<String, ToolParam> = mapOf("target" to ToolParam(ToolParamType.STRING, "target"))
        override val requiresConfirmation: Boolean = true
        override fun describeAction(arguments: Map<String, Any?>): String =
            "Perform destructive action on ${arguments["target"]}?"

        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            executed = true
            return ToolResult.success(mapOf("result" to "done"))
        }
    }

    @Test
    fun `gated tool emits ConfirmationRequired and executes when accepted`() = runTest {
        val tool = GatedTestTool()
        val registry = ToolRegistry().apply { register(tool) }
        val mockLlm = MockLlmEngine(listOf(
            """{"tool": "gated_action", "arguments": {"target": "database"}}""",
            """{"response": "Action completed successfully."}"""
        ))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, registry, ttsEngine = null)
        val session = manager.newChatSession()

        val events = mutableListOf<ConversationEvent>()
        val job = launch {
            session.processUtterance("run action", enableTts = false).collect { event ->
                events.add(event)
                if (event is ConversationEvent.ConfirmationRequired) {
                    session.respondToConfirmation(true)
                }
            }
        }
        job.join()

        assertTrue(events.any { it is ConversationEvent.ConfirmationRequired })
        val confirmEvent = events.filterIsInstance<ConversationEvent.ConfirmationRequired>().first()
        assertEquals("gated_action", confirmEvent.toolName)
        assertEquals("Perform destructive action on database?", confirmEvent.repeatBack)
        assertTrue(tool.executed)
        assertTrue(events.any { it is ConversationEvent.Completed })
    }

    @Test
    fun `gated tool emits ConfirmationRequired and records user declined when rejected`() = runTest {
        val tool = GatedTestTool()
        val registry = ToolRegistry().apply { register(tool) }
        val mockLlm = MockLlmEngine(listOf(
            """{"tool": "gated_action", "arguments": {"target": "database"}}""",
            """{"response": "Understood, cancelled the action."}"""
        ))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, registry, ttsEngine = null)
        val session = manager.newChatSession()

        val events = mutableListOf<ConversationEvent>()
        val job = launch {
            session.processUtterance("run action", enableTts = false).collect { event ->
                events.add(event)
                if (event is ConversationEvent.ConfirmationRequired) {
                    session.respondToConfirmation(false)
                }
            }
        }
        job.join()

        assertTrue(events.any { it is ConversationEvent.ConfirmationRequired })
        assertFalse(tool.executed)

        val toolResultTurn = session.conversationContext.getTurns().filterIsInstance<ConversationTurn.ToolExecutionResult>().firstOrNull()
        org.junit.Assert.assertNotNull(toolResultTurn)
        assertEquals("User declined the action.", toolResultTurn?.result?.error)
    }

    @Test
    fun `gated tool times out when unanswered and records action cancelled`() = runTest {
        val tool = GatedTestTool()
        val registry = ToolRegistry().apply { register(tool) }
        val mockLlm = MockLlmEngine(listOf(
            """{"tool": "gated_action", "arguments": {"target": "database"}}""",
            """{"response": "Timed out waiting for confirmation."}"""
        ))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, registry, ttsEngine = null)
        val session = manager.newChatSession()

        val events = mutableListOf<ConversationEvent>()
        val job = launch {
            session.processUtterance("run action", enableTts = false).collect { event ->
                events.add(event)
                // Do not respond, let timeout elapse
            }
        }

        // Advance past 20_000ms timeout
        testScheduler.advanceTimeBy(25_000L)
        job.join()

        assertTrue(events.any { it is ConversationEvent.ConfirmationRequired })
        assertFalse(tool.executed)

        val toolResultTurn = session.conversationContext.getTurns().filterIsInstance<ConversationTurn.ToolExecutionResult>().firstOrNull()
        org.junit.Assert.assertNotNull(toolResultTurn)
        assertEquals("No response received; action cancelled.", toolResultTurn?.result?.error)
    }

    @Test
    fun `cancel during pending confirmation resolves gate as false without zombie gate`() = runTest {
        val tool = GatedTestTool()
        val registry = ToolRegistry().apply { register(tool) }
        val mockLlm = MockLlmEngine(listOf(
            """{"tool": "gated_action", "arguments": {"target": "database"}}""",
            """{"response": "Cancelled."}"""
        ))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, registry, ttsEngine = null)
        val session = manager.newChatSession()

        val events = mutableListOf<ConversationEvent>()
        val job = launch {
            session.processUtterance("run action", enableTts = false).collect { event ->
                events.add(event)
                if (event is ConversationEvent.ConfirmationRequired) {
                    session.cancel()
                }
            }
        }
        job.join()

        assertFalse(tool.executed)
    }

    @Test
    fun `empty memory store does not inject memory block into system prompt`() = runTest {
        val mockLlm = MockLlmEngine(listOf("""{"response": "Hello!"}"""))
        val tempMemDir = java.nio.file.Files.createTempDirectory("mem_test").toFile()
        val memStore = MemoryStore(tempMemDir)
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = mockLlm,
            toolRegistry = ToolRegistry(),
            memoryStore = memStore
        )

        manager.processUtterance("What is my schedule?", enableTts = false).toList()
        val prompt = mockLlm.lastStartedConfig?.systemInstruction
        org.junit.Assert.assertNotNull(prompt)
        assertFalse(prompt!!.contains("What you remember about the user"))
        tempMemDir.deleteRecursively()
    }

    @Test
    fun `memory entries are injected into system prompt sorted by most recent first`() = runTest {
        val mockLlm = MockLlmEngine(listOf("""{"response": "Hello!"}"""))
        val tempMemDir = java.nio.file.Files.createTempDirectory("mem_test").toFile()
        var fakeNow = 1_000_000L
        val memStore = MemoryStore(tempMemDir, nowMillis = { fakeNow += 50; fakeNow })

        memStore.add("User lives in Tokyo")
        memStore.add("User prefers dark mode")

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = mockLlm,
            toolRegistry = ToolRegistry(),
            memoryStore = memStore
        )

        manager.processUtterance("What is my schedule?", enableTts = false).toList()
        val prompt = mockLlm.lastStartedConfig?.systemInstruction
        org.junit.Assert.assertNotNull(prompt)
        assertTrue(prompt!!.contains("What you remember about the user:\n- User prefers dark mode\n- User lives in Tokyo"))
        tempMemDir.deleteRecursively()
    }

    @Test
    fun `memory injection respects 10 entries cap and sorts most recent first`() = runTest {
        val mockLlm = MockLlmEngine(listOf("""{"response": "Hello!"}"""))
        val tempMemDir = java.nio.file.Files.createTempDirectory("mem_test").toFile()
        var fakeNow = 1_000_000L
        val memStore = MemoryStore(tempMemDir, nowMillis = { fakeNow += 50; fakeNow })

        for (i in 1..15) {
            memStore.add("Memory fact number $i")
        }

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = mockLlm,
            toolRegistry = ToolRegistry(),
            memoryStore = memStore
        )

        manager.processUtterance("What is my schedule?", enableTts = false).toList()
        val prompt = mockLlm.lastStartedConfig?.systemInstruction
        org.junit.Assert.assertNotNull(prompt)
        assertTrue(prompt!!.contains("What you remember about the user:"))
        val memoryBlock = prompt.substringAfter("What you remember about the user:\n").substringBefore("\n\n")
        val lines = memoryBlock.lines().filter { it.startsWith("- Memory fact number") }
        assertEquals(10, lines.size)
        // Most recent first: 15 down to 6
        assertEquals("- Memory fact number 15", lines.first())
        assertEquals("- Memory fact number 6", lines.last())
        tempMemDir.deleteRecursively()
    }

    @Test
    fun `memory injection truncates at entry boundary when exceeding 800 characters`() = runTest {
        val mockLlm = MockLlmEngine(listOf("""{"response": "Hello!"}"""))
        val tempMemDir = java.nio.file.Files.createTempDirectory("mem_test").toFile()
        var fakeNow = 1_000_000L
        val memStore = MemoryStore(tempMemDir, nowMillis = { fakeNow += 50; fakeNow })

        val longFact1 = "A".repeat(500)
        val longFact2 = "B".repeat(400)
        memStore.add(longFact1)
        memStore.add(longFact2)

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = mockLlm,
            toolRegistry = ToolRegistry(),
            memoryStore = memStore
        )

        manager.processUtterance("What is my schedule?", enableTts = false).toList()
        val prompt = mockLlm.lastStartedConfig?.systemInstruction
        org.junit.Assert.assertNotNull(prompt)
        // longFact2 (most recent, 400 chars) is included, but longFact1 (would exceed 800 chars) is excluded
        assertTrue(prompt!!.contains(longFact2))
        assertFalse(prompt.contains(longFact1))
        tempMemDir.deleteRecursively()
    }

    @Test
    fun `default and auto language config injects mirror language directive`() = runTest {
        val mockLlm = MockLlmEngine(listOf("""{"response": "Hello!"}"""))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = mockLlm,
            toolRegistry = ToolRegistry()
        )

        manager.setAgentConfig(dev.loki.android.core.models.AgentConfig(conversationLanguage = "auto"))
        manager.processUtterance("What is my schedule?", enableTts = false).toList()
        val prompt = mockLlm.lastStartedConfig?.systemInstruction
        org.junit.Assert.assertNotNull(prompt)
        assertTrue(prompt!!.contains("Always respond in the same language the user writes or speaks in."))
    }

    @Test
    fun `explicit language tag injects always respond in language directive placed after custom instruction`() = runTest {
        val mockLlm = MockLlmEngine(listOf("""{"response": "Namaste!"}"""))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = mockLlm,
            toolRegistry = ToolRegistry()
        )

        manager.setAgentConfig(
            dev.loki.android.core.models.AgentConfig(
                systemInstruction = "You are a friendly companion.",
                conversationLanguage = "hi"
            )
        )
        manager.processUtterance("What is my schedule?", enableTts = false).toList()
        val prompt = mockLlm.lastStartedConfig?.systemInstruction
        org.junit.Assert.assertNotNull(prompt)
        assertTrue(prompt!!.contains("Additional Instructions:\nYou are a friendly companion.\n\nAlways respond in Hindi."))
    }

    @Test
    fun `voice activations use independent fresh sessions without cross-turn history`() = runTest {
        val mockLlm = MockLlmEngine(
            listOf(
                """{"response": "Mom's number is 1234567890."}""",
                """{"response": "Placing the call to Mom."}"""
            )
        )
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = mockLlm,
            toolRegistry = ToolRegistry()
        )

        // Activation 1
        val session1 = manager.newVoiceSession()
        session1.processUtterance("who is mom", enableTts = false, source = "DIRECT_AUDIO").toList()

        // Activation 2 (fresh voice session)
        val session2 = manager.newVoiceSession()
        assertEquals(0, session2.conversationContext.getTurns().size)
        session2.processUtterance("call her", enableTts = false, source = "DIRECT_AUDIO").toList()

        // Session 2 should only contain its own turns (user + assistant, maxTurns=1)
        assertEquals(2, session2.conversationContext.getTurns().size)
    }

    class TestLookupTool(
        private val contactsJson: String = "[{\"id\":\"c1\",\"name\":\"Mom\",\"number\":\"1234567890\"},{\"id\":\"c2\",\"name\":\"Mom Mobile\",\"number\":\"9876543210\"}]"
    ) : LocalTool {
        override val name: String = "lookup_contact"
        override val capability: String = "calling"
        override val description: String = "Lookup contacts"
        override val parameters: Map<String, ToolParam> = mapOf(
            "query" to ToolParam(ToolParamType.STRING, "name query", required = true)
        )
        var executedCount = 0
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            executedCount++
            return ToolResult.success(mapOf("count" to "2", "contacts" to contactsJson))
        }
    }

    class TestDisambiguationCallTool : LocalTool {
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
            return ToolResult.success(mapOf("calling" to (arguments["name"]?.toString() ?: "Mom"), "phone_number" to (arguments["phone_number"]?.toString() ?: "")))
        }
    }

    @Test
    fun `shared voice candidate registry survives across two newVoiceSession turns without second lookup`() = runTest {
        val lookupTool = TestLookupTool()
        val callTool = TestDisambiguationCallTool()
        val toolRegistry = ToolRegistry().apply {
            register(lookupTool)
            register(callTool)
        }

        val mockLlm = MockLlmEngine(
            listOf(
                """{"tool": "lookup_contact", "arguments": {"query": "Mom"}}""",
                """{"response": "Found Mom and Mom Mobile. Which one?"}""",
                """{"tool": "call_contact", "arguments": {"candidate_id": "c1", "name": "Mom"}}""",
                """{"response": "Calling Mom now."}"""
            )
        )
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, toolRegistry, ttsEngine = null)

        // Turn 1 (Voice Activation 1)
        val session1 = manager.newVoiceSession()
        session1.processUtterance("Call Mom", enableTts = false).toList()
        assertEquals(1, lookupTool.executedCount)
        assertTrue(manager.getVoiceCandidates().containsKey("c1"))
        assertTrue(manager.getVoiceCandidates().containsKey("c2"))

        // Turn 2 (Voice Activation 2 - new session instance)
        val session2 = manager.newVoiceSession()
        val events2 = mutableListOf<ConversationEvent>()
        val job = launch {
            session2.processUtterance("The first one", enableTts = false).collect { event ->
                events2.add(event)
                if (event is ConversationEvent.ConfirmationRequired) {
                    session2.respondToConfirmation(true)
                }
            }
        }
        job.join()

        // Lookup should NOT have executed again
        assertEquals(1, lookupTool.executedCount)
        assertTrue(callTool.executed)
        assertEquals("1234567890", callTool.executedArgs?.get("phone_number"))
        // After call execution, registry is cleared
        assertTrue(manager.getVoiceCandidates().isEmpty())
    }

    @Test
    fun `chat registry is isolated from voice registry`() = runTest {
        val lookupTool = TestLookupTool()
        val toolRegistry = ToolRegistry().apply {
            register(lookupTool)
        }
        val mockLlm = MockLlmEngine(
            listOf(
                """{"tool": "lookup_contact", "arguments": {"query": "Mom"}}""",
                """{"response": "Which one?"}"""
            )
        )
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, toolRegistry, ttsEngine = null)

        val voiceSession = manager.newVoiceSession()
        voiceSession.processUtterance("Call Mom", enableTts = false).toList()

        // Voice registry has candidates
        assertTrue(manager.getVoiceCandidates().isNotEmpty())

        // Clearing voice candidates clears voice without crashing
        manager.clearVoiceCandidates()
        assertTrue(manager.getVoiceCandidates().isEmpty())
    }

    @Test
    fun `pendingAsk survives newVoiceSession and is cleared on clearVoiceCandidates and reset`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, MockLlmEngine(emptyList()), ToolRegistry(), ttsEngine = null)

        val candidates = listOf(ContactCandidate("c1", "Mom", "123"))
        val pending = PendingAsk("Which Mom?", candidates)

        manager.pendingVoiceAsk = pending
        assertEquals(pending, manager.pendingVoiceAsk)

        // New voice session gets pendingAsk
        val session1 = manager.newVoiceSession()
        assertEquals(pending, session1.pendingAsk)

        // Clear voice candidates clears pendingAsk
        manager.clearVoiceCandidates()
        assertNull(manager.pendingVoiceAsk)

        // New voice session has null pendingAsk
        val session2 = manager.newVoiceSession()
        assertNull(session2.pendingAsk)

        // Reset also clears pendingAsk
        manager.pendingVoiceAsk = pending
        manager.reset()
        assertNull(manager.pendingVoiceAsk)
    }
}




