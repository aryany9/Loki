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

    @Test
    fun `TurnLogger creates valid correlation IDs`() {
        val id1 = TurnLogger.newTurnId()
        val id2 = TurnLogger.newTurnId()
        assertTrue(id1.isNotBlank())
        assertTrue(id2.isNotBlank())
        assertTrue(id1 != id2)
    }
}
