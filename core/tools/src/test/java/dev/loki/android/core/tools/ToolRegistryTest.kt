package dev.loki.android.core.tools

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    class DummyTool(
        override val name: String = "dummy_tool",
        override val description: String = "A dummy tool",
        override val parameters: Map<String, ToolParam> = mapOf("param1" to ToolParam(ToolParamType.STRING, "test")),
        override val requiredPermissions: List<String> = emptyList(),
        private val shouldSucceed: Boolean = true
    ) : LocalTool {
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            return if (shouldSucceed) {
                ToolResult.success(mapOf("result" to (arguments["param1"]?.toString() ?: "ok")))
            } else {
                ToolResult.error("Failed dummy execution", ToolErrorCode.EXECUTION_ERROR)
            }
        }
    }

    @Test
    fun `register and retrieve tools from registry`() {
        val registry = ToolRegistry()
        val tool = DummyTool()
        registry.register(tool)

        val retrieved = registry.get("dummy_tool")
        assertNotNull(retrieved)
        assertEquals("dummy_tool", retrieved?.name)
        assertEquals(1, registry.getAllTools().size)
        assertEquals(1, registry.getLocalTools().size)
        assertEquals(0, registry.getOnlineTools().size)

        registry.unregister("dummy_tool")
        assertEquals(0, registry.getAllTools().size)
    }

    @Test
    fun `execute returns error when tool is not registered`() = runTest {
        val registry = ToolRegistry()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = registry.execute(dummyContext, "non_existent", emptyMap())
        assertFalse(result.success)
        assertEquals(ToolErrorCode.NOT_FOUND.name, result.errorCode)
    }

    @Test
    fun `execute returns validation error when required param is missing`() = runTest {
        val registry = ToolRegistry()
        registry.register(DummyTool())
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = registry.execute(dummyContext, "dummy_tool", emptyMap())
        assertFalse(result.success)
        assertEquals(ToolErrorCode.VALIDATION_ERROR.name, result.errorCode)
    }

    @Test
    fun `ToolResult serializes and deserializes cleanly with kotlinx serialization`() {
        val original = ToolResult.success(mapOf("battery" to "85", "charging" to "true"))
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(ToolResult.serializer(), original)
        assertTrue(jsonStr.contains("85"))

        val parsed = kotlinx.serialization.json.Json.decodeFromString(ToolResult.serializer(), jsonStr)
        assertTrue(parsed.success)
        assertEquals("85", parsed.data?.get("battery"))
    }

    // ── Task 1.2 — Confirmation metadata exposure ────────────────────────────

    class GatedDummyTool(
        override val name: String = "gated_tool"
    ) : LocalTool {
        override val description: String = "A gated dummy tool"
        override val parameters: Map<String, ToolParam> = emptyMap()
        override val requiresConfirmation: Boolean = true
        override fun describeAction(arguments: Map<String, Any?>): String =
            "Performing ${arguments["action"] ?: "gated action"}"
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult =
            ToolResult.success()
    }

    @Test
    fun `registry reports requiresConfirmation true for gated tool`() {
        val registry = ToolRegistry()
        registry.register(GatedDummyTool())
        assertTrue(registry.requiresConfirmation("gated_tool"))
    }

    @Test
    fun `registry reports requiresConfirmation false for ungated tool`() {
        val registry = ToolRegistry()
        registry.register(DummyTool())
        assertFalse(registry.requiresConfirmation("dummy_tool"))
    }

    @Test
    fun `registry returns false for requiresConfirmation on unknown tool`() {
        val registry = ToolRegistry()
        assertFalse(registry.requiresConfirmation("no_such_tool"))
    }

    @Test
    fun `registry describeAction returns tool-provided string for gated tool`() {
        val registry = ToolRegistry()
        registry.register(GatedDummyTool())
        val description = registry.describeAction("gated_tool", mapOf("action" to "calling Rahul"))
        assertEquals("Performing calling Rahul", description)
    }

    @Test
    fun `registry describeAction returns tool name for ungated tool (default)`() {
        val registry = ToolRegistry()
        registry.register(DummyTool())
        val description = registry.describeAction("dummy_tool", emptyMap())
        assertEquals("dummy_tool", description)
    }

    @Test
    fun `registry describeAction returns tool name string for unknown tool`() {
        val registry = ToolRegistry()
        val description = registry.describeAction("no_such_tool", emptyMap())
        assertEquals("no_such_tool", description)
    }

    @Test
    fun `getAvailableTools and getDisabledTools filter according to permission manager`() {
        val registry = ToolRegistry()
        val ungated = DummyTool("ungated", "ungated tool", emptyMap(), emptyList())
        val permitted = DummyTool("permitted", "permitted tool", emptyMap(), listOf("android.permission.CAMERA"))
        val denied = DummyTool("denied", "denied tool", emptyMap(), listOf("android.permission.RECORD_AUDIO"))
        registry.register(ungated)
        registry.register(permitted)
        registry.register(denied)

        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                return if (permission == "android.permission.CAMERA") {
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    android.content.pm.PackageManager.PERMISSION_DENIED
                }
            }
        }
        val permissionManager = PermissionManager()

        val available = registry.getAvailableTools(dummyContext, permissionManager)
        assertEquals(2, available.size)
        assertTrue(available.any { it.name == "ungated" })
        assertTrue(available.any { it.name == "permitted" })

        val disabled = registry.getDisabledTools(dummyContext, permissionManager)
        assertEquals(1, disabled.size)
        assertEquals("denied", disabled[0].first.name)
        assertEquals("android.permission.RECORD_AUDIO", disabled[0].second)
    }

    class ScopedDummyTool(
        override val name: String,
        override val capability: String,
        override val isInternal: Boolean = false,
        override val requiredPermissions: List<String> = emptyList()
    ) : LocalTool {
        override val description: String = "scoped tool"
        override val parameters: Map<String, ToolParam> = emptyMap()
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult = ToolResult.success()
    }

    @Test
    fun `getAvailableTools with activeCapability filters out other domain capabilities and includes general and active`() {
        val registry = ToolRegistry()
        registry.register(ScopedDummyTool("general_tool", capability = "general"))
        registry.register(ScopedDummyTool("calling_tool", capability = "calling"))
        registry.register(ScopedDummyTool("device_tool", capability = "device"))

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val available = registry.getAvailableTools(dummyContext, activeCapability = "calling")

        assertEquals(2, available.size)
        assertTrue(available.any { it.name == "general_tool" })
        assertTrue(available.any { it.name == "calling_tool" })
        assertFalse(available.any { it.name == "device_tool" })
    }

    @Test
    fun `getAvailableTools with null activeCapability returns all tools`() {
        val registry = ToolRegistry()
        registry.register(ScopedDummyTool("general_tool", capability = "general"))
        registry.register(ScopedDummyTool("calling_tool", capability = "calling"))
        registry.register(ScopedDummyTool("device_tool", capability = "device"))

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val available = registry.getAvailableTools(dummyContext, activeCapability = null)

        assertEquals(3, available.size)
    }

    @Test
    fun `advancing tool visibility invariant - internal tools visible only when advancingTool matches`() {
        val registry = ToolRegistry()
        registry.register(ScopedDummyTool("general_tool", capability = "general"))
        registry.register(ScopedDummyTool("select_contact", capability = "calling", isInternal = true))
        registry.register(ScopedDummyTool("call_contact", capability = "calling", isInternal = false))

        val dummyContext = object : android.content.ContextWrapper(null) {}

        // When activeCapability is calling and advancingTool is select_contact
        val availableWithSelect = registry.getAvailableTools(
            dummyContext,
            activeCapability = "calling",
            advancingTool = "select_contact"
        )
        assertTrue(availableWithSelect.any { it.name == "select_contact" })
        assertTrue(availableWithSelect.any { it.name == "call_contact" })

        // When activeCapability is calling but advancingTool is call_contact
        val availableWithCall = registry.getAvailableTools(
            dummyContext,
            activeCapability = "calling",
            advancingTool = "call_contact"
        )
        assertFalse(availableWithCall.any { it.name == "select_contact" })
        assertTrue(availableWithCall.any { it.name == "call_contact" })

        // When activeCapability is null and advancingTool is null (session start)
        val availableStart = registry.getAvailableTools(dummyContext, activeCapability = null, advancingTool = null)
        assertFalse(availableStart.any { it.name == "select_contact" })
        assertTrue(availableStart.any { it.name == "call_contact" })
    }

    // ── Task 2.4: State-scoped TaskStateGate tests ────────────────────────────

    private class MockTaskStateGate(
        override val restrictToTool: String? = null,
        override val hiddenTool: String? = null,
        override val hiddenTools: Set<String> = setOfNotNull(hiddenTool)
    ) : TaskStateGate

    @Test
    fun `getAvailableTools with DISAMBIGUATION gate restricts to select_contact and excludes ask_user and call_contact`() {
        val registry = ToolRegistry()
        registry.register(ScopedDummyTool("general_tool", capability = "general"))
        registry.register(ScopedDummyTool("ask_user", capability = "general"))
        registry.register(ScopedDummyTool("lookup_contact", capability = "calling"))
        registry.register(ScopedDummyTool("call_contact", capability = "calling"))
        registry.register(ScopedDummyTool("select_contact", capability = "calling", isInternal = true))

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val disambiguationGate = MockTaskStateGate(
            restrictToTool = "select_contact",
            hiddenTools = setOf("ask_user", "call_contact")
        )

        val available = registry.getAvailableTools(
            context = dummyContext,
            activeCapability = "calling",
            advancingTool = "select_contact",
            taskState = disambiguationGate
        )

        // select_contact and general tools (except ask_user) only
        assertTrue("select_contact must be available", available.any { it.name == "select_contact" })
        assertTrue("general_tool must be available", available.any { it.name == "general_tool" })
        // ask_user, call_contact, and lookup_contact must be excluded
        assertFalse("ask_user must NOT be available in disambiguation", available.any { it.name == "ask_user" })
        assertFalse("call_contact must NOT be available in disambiguation", available.any { it.name == "call_contact" })
        assertFalse("lookup_contact must NOT be available in disambiguation", available.any { it.name == "lookup_contact" })
    }

    @Test
    fun `getAvailableTools with CONFIRMATION gate before question hides call_contact and allows ask_user`() {
        val registry = ToolRegistry()
        registry.register(ScopedDummyTool("general_tool", capability = "general"))
        registry.register(ScopedDummyTool("ask_user", capability = "general"))
        registry.register(ScopedDummyTool("call_contact", capability = "calling"))
        registry.register(ScopedDummyTool("select_contact", capability = "calling", isInternal = true))

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val confirmationGate = MockTaskStateGate(
            restrictToTool = null,
            hiddenTools = setOf("call_contact")
        )

        val available = registry.getAvailableTools(
            context = dummyContext,
            activeCapability = "calling",
            advancingTool = "call_contact",
            taskState = confirmationGate
        )

        // call_contact must be excluded before question is asked
        assertFalse("call_contact must NOT be available before question", available.any { it.name == "call_contact" })
        // General tools and ask_user remain available
        assertTrue("ask_user must be available to ask confirmation", available.any { it.name == "ask_user" })
        assertTrue("general_tool must still be available", available.any { it.name == "general_tool" })
    }

    @Test
    fun `getAvailableTools with AWAITING_CONFIRMATION gate exposes call_contact and hides ask_user`() {
        val registry = ToolRegistry()
        registry.register(ScopedDummyTool("general_tool", capability = "general"))
        registry.register(ScopedDummyTool("ask_user", capability = "general"))
        registry.register(ScopedDummyTool("call_contact", capability = "calling"))
        registry.register(ScopedDummyTool("select_contact", capability = "calling", isInternal = true))

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val awaitingGate = MockTaskStateGate(
            restrictToTool = null,
            hiddenTools = setOf("ask_user")
        )

        val available = registry.getAvailableTools(
            context = dummyContext,
            activeCapability = "calling",
            advancingTool = "call_contact",
            taskState = awaitingGate
        )

        // call_contact must be available for confirmation answer
        assertTrue("call_contact must be available in awaiting confirmation", available.any { it.name == "call_contact" })
        // ask_user must be hidden to prevent loop
        assertFalse("ask_user must NOT be available in awaiting confirmation", available.any { it.name == "ask_user" })
        assertTrue("general_tool must still be available", available.any { it.name == "general_tool" })
    }

    @Test
    fun `getAvailableTools with null gate applies no additional restrictions`() {
        val registry = ToolRegistry()
        registry.register(ScopedDummyTool("general_tool", capability = "general"))
        registry.register(ScopedDummyTool("call_contact", capability = "calling"))
        registry.register(ScopedDummyTool("lookup_contact", capability = "calling"))

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val available = registry.getAvailableTools(
            context = dummyContext,
            activeCapability = "calling",
            taskState = null
        )

        // All calling + general tools available (no state restrictions)
        assertEquals(3, available.size)
        assertTrue(available.any { it.name == "general_tool" })
        assertTrue(available.any { it.name == "call_contact" })
        assertTrue(available.any { it.name == "lookup_contact" })
    }
}
