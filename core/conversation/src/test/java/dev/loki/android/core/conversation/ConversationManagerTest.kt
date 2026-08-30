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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationManagerTest {

    class MockLlmEngine(private val responses: List<String>) : LlmEngine {
        private var callIndex = 0
        private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
        override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

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
    fun `VoiceSession starts fresh without leaking previous turns`() = runTest {
        val toolRegistry = ToolRegistry()
        toolRegistry.register(TestTimeTool())

        val mockLlm = MockLlmEngine(listOf("""{"tool": "get_current_time", "arguments": {}}"""))
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val manager = ConversationManager(dummyContext, mockLlm, toolRegistry, ttsEngine = null)

        val voiceSession1 = manager.newVoiceSession()
        voiceSession1.processUtterance("Turn 1", enableTts = false).toList()

        val voiceSession2 = manager.newVoiceSession()
        assertEquals(0, voiceSession2.conversationContext.getTurns().size)
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
}


