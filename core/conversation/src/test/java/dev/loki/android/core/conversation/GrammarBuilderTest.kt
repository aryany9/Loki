package dev.loki.android.core.conversation

import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrammarBuilderTest {

    private class MockTool(
        override val name: String,
        override val capability: String,
        override val isInternal: Boolean = false
    ) : LocalTool {
        override val description: String = "mock"
        override val parameters: Map<String, ToolParam> = emptyMap()
        override suspend fun execute(context: android.content.Context, arguments: Map<String, Any?>): ToolResult =
            ToolResult.success()
    }

    @Before
    fun setUp() {
        GrammarBuilder.clearCache()
    }

    @Test
    fun `grammar encodes only scoped tool names when capability is active`() {
        val registry = ToolRegistry()
        registry.register(MockTool("get_current_time", capability = "general"))
        registry.register(MockTool("lookup_contact", capability = "calling"))
        registry.register(MockTool("call_contact", capability = "calling"))
        registry.register(MockTool("open_app", capability = "apps"))

        val grammar = GrammarBuilder.buildFrom(
            toolRegistry = registry,
            activeCapability = "calling"
        )

        assertTrue(grammar.contains("\"\\\"get_current_time\\\"\""))
        assertTrue(grammar.contains("\"\\\"lookup_contact\\\"\""))
        assertTrue(grammar.contains("\"\\\"call_contact\\\"\""))
        assertFalse(grammar.contains("open_app"))
    }

    @Test
    fun `grammar regenerates when scoped visible tool set changes`() {
        val registry = ToolRegistry()
        registry.register(MockTool("get_current_time", capability = "general"))
        registry.register(MockTool("lookup_contact", capability = "calling"))
        registry.register(MockTool("select_contact", capability = "calling", isInternal = true))
        registry.register(MockTool("open_app", capability = "apps"))

        // Phase 1: Null capability (session start) - all non-internal tools
        val grammar1 = GrammarBuilder.buildFrom(registry, activeCapability = null, advancingTool = null)
        assertTrue(grammar1.contains("open_app"))
        assertTrue(grammar1.contains("lookup_contact"))
        assertFalse(grammar1.contains("select_contact"))

        // Phase 2: Calling active, advancing tool select_contact
        val grammar2 = GrammarBuilder.buildFrom(registry, activeCapability = "calling", advancingTool = "select_contact")
        assertFalse(grammar2.contains("open_app"))
        assertTrue(grammar2.contains("lookup_contact"))
        assertTrue(grammar2.contains("select_contact"))
        assertTrue(grammar2.contains("get_current_time"))

        // Phase 3: Capability resets to null
        val grammar3 = GrammarBuilder.buildFrom(registry, activeCapability = null, advancingTool = null)
        assertTrue(grammar3.contains("open_app"))
        assertFalse(grammar3.contains("select_contact"))
    }
}
