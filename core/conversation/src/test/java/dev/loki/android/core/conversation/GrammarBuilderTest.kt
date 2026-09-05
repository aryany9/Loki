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

    // ── Task 2.4: State-scoped grammar gating tests ─────────────────────────

    @Test
    fun `CONTACT_DISAMBIGUATION state restricts grammar to select_contact and general tools only`() {
        GrammarBuilder.clearCache()
        val registry = ToolRegistry()
        registry.register(MockTool("get_current_time", capability = "general"))
        registry.register(MockTool("ask_user", capability = "general"))
        registry.register(MockTool("lookup_contact", capability = "calling"))
        registry.register(MockTool("call_contact", capability = "calling"))
        registry.register(MockTool("select_contact", capability = "calling", isInternal = true))

        val disambiguationState = dev.loki.android.core.conversation.ContactResolution(
            candidates = listOf(dev.loki.android.core.conversation.ContactCandidate("c1", "Mom", "123")),
            selectedId = null,   // CONTACT_DISAMBIGUATION
            confirmed = false
        )

        val grammar = GrammarBuilder.buildFrom(
            toolRegistry = registry,
            activeCapability = "calling",
            advancingTool = "select_contact",
            taskState = disambiguationState
        )

        // Only select_contact and general tools (except ask_user) should appear
        assertTrue("select_contact must be in disambiguation grammar", grammar.contains("select_contact"))
        assertTrue("get_current_time (general) must be in grammar", grammar.contains("get_current_time"))
        // ask_user, call_contact, and lookup_contact must NOT appear
        assertFalse("ask_user must NOT appear in disambiguation grammar", grammar.contains("ask_user"))
        assertFalse("call_contact must NOT appear in disambiguation grammar", grammar.contains("call_contact"))
        assertFalse("lookup_contact must NOT appear in disambiguation grammar", grammar.contains("lookup_contact"))
    }

    @Test
    fun `CALL_CONFIRMATION state before question is asked hides call_contact and allows ask_user`() {
        GrammarBuilder.clearCache()
        val registry = ToolRegistry()
        registry.register(MockTool("get_current_time", capability = "general"))
        registry.register(MockTool("ask_user", capability = "general"))
        registry.register(MockTool("call_contact", capability = "calling"))
        registry.register(MockTool("select_contact", capability = "calling", isInternal = true))

        val confirmationState = dev.loki.android.core.conversation.ContactResolution(
            candidates = listOf(dev.loki.android.core.conversation.ContactCandidate("c1", "Mom", "123")),
            selectedId = "c1",   // CALL_CONFIRMATION
            isAsked = false,     // Question not yet asked
            confirmed = false
        )

        val grammar = GrammarBuilder.buildFrom(
            toolRegistry = registry,
            activeCapability = "calling",
            advancingTool = "call_contact",
            taskState = confirmationState
        )

        // call_contact must be hidden before question is asked
        assertFalse("call_contact must NOT appear before confirmation question", grammar.contains("call_contact"))
        // ask_user must be available to generate the confirmation question
        assertTrue("ask_user must be in grammar to ask confirmation", grammar.contains("ask_user"))
        assertTrue("get_current_time must still be in grammar", grammar.contains("get_current_time"))
    }

    @Test
    fun `AWAITING_CONFIRMATION state exposes call_contact and hides ask_user`() {
        GrammarBuilder.clearCache()
        val registry = ToolRegistry()
        registry.register(MockTool("get_current_time", capability = "general"))
        registry.register(MockTool("ask_user", capability = "general"))
        registry.register(MockTool("call_contact", capability = "calling"))
        registry.register(MockTool("select_contact", capability = "calling", isInternal = true))

        val awaitingState = dev.loki.android.core.conversation.ContactResolution(
            candidates = listOf(dev.loki.android.core.conversation.ContactCandidate("c1", "Mom", "123")),
            selectedId = "c1",
            isAsked = true,     // Confirmation question already asked
            confirmed = false
        )

        val grammar = GrammarBuilder.buildFrom(
            toolRegistry = registry,
            activeCapability = "calling",
            advancingTool = "call_contact",
            taskState = awaitingState
        )

        // call_contact must be EXPOSED so affirmative user reply can invoke it
        assertTrue("call_contact must appear in awaiting confirmation grammar", grammar.contains("call_contact"))
        // ask_user must be HIDDEN to prevent confirmation loops
        assertFalse("ask_user must NOT appear in awaiting confirmation grammar", grammar.contains("ask_user"))
        assertTrue("get_current_time must still be in grammar", grammar.contains("get_current_time"))
    }

    @Test
    fun `CONFIRMED state has no grammar restrictions - call_contact is exposed`() {
        GrammarBuilder.clearCache()
        val registry = ToolRegistry()
        registry.register(MockTool("get_current_time", capability = "general"))
        registry.register(MockTool("call_contact", capability = "calling"))

        val confirmedState = dev.loki.android.core.conversation.ContactResolution(
            candidates = listOf(dev.loki.android.core.conversation.ContactCandidate("c1", "Mom", "123")),
            selectedId = "c1",
            confirmed = true   // CONFIRMED
        )

        val grammar = GrammarBuilder.buildFrom(
            toolRegistry = registry,
            activeCapability = "calling",
            advancingTool = null,
            taskState = confirmedState
        )

        // In CONFIRMED state, no restrictions — call_contact must be available
        assertTrue("call_contact must appear in confirmed grammar", grammar.contains("call_contact"))
        assertTrue("get_current_time must appear in confirmed grammar", grammar.contains("get_current_time"))
    }
}
