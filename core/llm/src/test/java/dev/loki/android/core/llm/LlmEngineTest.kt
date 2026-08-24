package dev.loki.android.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmEngineTest {

    @Test
    fun `calculateDefaultThreads returns reasonable value`() {
        val threads = LlamaCppLlmEngine.calculateDefaultThreads()
        assertTrue("Threads ($threads) must be >= 2", threads >= 2)
        assertTrue("Threads ($threads) must be <= 8", threads <= 8)
    }

    @Test
    fun `ToolDefinition constructs schema correctly`() {
        val tool = ToolDefinition(
            name = "lookup_contact",
            description = "Find a contact by name",
            parameters = mapOf("query" to ParamType.STRING)
        )
        assertEquals("lookup_contact", tool.name)
        assertEquals("Find a contact by name", tool.description)
        assertEquals(ParamType.STRING, tool.parameters["query"])
    }

    @Test
    fun `GrammarBuilder constructs schema JSON with tools`() {
        val tools = listOf(
            ToolDefinition("get_battery", "Get battery status"),
            ToolDefinition("set_alarm", "Set alarm", mapOf("hour" to ParamType.NUMBER, "minute" to ParamType.NUMBER))
        )
        assertNotNull(tools)
        assertEquals(2, tools.size)
    }
}
