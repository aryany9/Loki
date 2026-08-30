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
}
