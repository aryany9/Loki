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
}
