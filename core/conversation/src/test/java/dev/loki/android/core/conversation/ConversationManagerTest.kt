package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationManagerTest {

    class MockLlmEngine(private val responses: List<String>) : LlmEngine {
        private var callIndex = 0
        override fun isReady(): Boolean = true
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
    fun `ConversationContext manages and trims history within budget`() {
        val context = ConversationContext(maxTurns = 4, maxTokenBudget = 1000)
        context.append(ConversationTurn.User("Turn 1"))
        context.append(ConversationTurn.Assistant("Resp 1"))
        context.append(ConversationTurn.User("Turn 2"))
        context.append(ConversationTurn.Assistant("Resp 2"))
        context.append(ConversationTurn.User("Turn 3"))

        assertTrue(context.getTurns().size <= 4)
        context.clear()
        assertEquals(0, context.getTurns().size)
    }
}
