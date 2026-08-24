package dev.loki.android.core.tools.local

import android.content.Context
import dev.loki.android.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolsTest {

    @Test
    fun `DefaultLocalTools registers all 9 tools`() {
        val registry = ToolRegistry()
        DefaultLocalTools.registerAll(registry)

        assertEquals(9, registry.getAllTools().size)
        assertNotNull(registry.get("get_current_time"))
        assertNotNull(registry.get("get_battery_status"))
        assertNotNull(registry.get("open_app"))
        assertNotNull(registry.get("lookup_contact"))
        assertNotNull(registry.get("call_contact"))
        assertNotNull(registry.get("dial_number"))
        assertNotNull(registry.get("set_timer"))
        assertNotNull(registry.get("set_alarm"))
        assertNotNull(registry.get("media_control"))
    }

    @Test
    fun `GetCurrentTimeTool returns valid time and date strings`() = runTest {
        val tool = GetCurrentTimeTool()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = tool.execute(dummyContext, emptyMap())

        assertTrue(result.success)
        assertNotNull(result.data?.get("time"))
        assertNotNull(result.data?.get("date"))
    }

    @Test
    fun `OpenAppTool validates missing app_name`() = runTest {
        val tool = OpenAppTool()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = tool.execute(dummyContext, emptyMap())

        assertTrue(!result.success)
        assertTrue(result.error?.contains("Missing app_name") == true)
    }

    @Test
    fun `SetTimerTool validates missing seconds`() = runTest {
        val tool = SetTimerTool()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = tool.execute(dummyContext, emptyMap())

        assertTrue(!result.success)
    }

    @Test
    fun `MediaControlTool validates unsupported action`() = runTest {
        val tool = MediaControlTool()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = tool.execute(dummyContext, mapOf("action" to "fly_to_moon"))

        assertTrue(!result.success)
        assertTrue(result.error?.contains("Unsupported media action") == true)
    }
}
