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
    fun `DefaultLocalTools registers all 17 tools`() {
        val registry = ToolRegistry()
        DefaultLocalTools.registerAll(registry)

        assertEquals(17, registry.getAllTools().size)
        assertNotNull(registry.get("get_current_time"))
        assertNotNull(registry.get("get_battery_status"))
        assertNotNull(registry.get("open_app"))
        assertNotNull(registry.get("lookup_contact"))
        assertNotNull(registry.get("call_contact"))
        assertNotNull(registry.get("dial_number"))
        assertNotNull(registry.get("set_timer"))
        assertNotNull(registry.get("set_alarm"))
        assertNotNull(registry.get("media_control"))
        assertNotNull(registry.get("toggle_flashlight"))
        assertNotNull(registry.get("open_wifi_settings"))
        assertNotNull(registry.get("open_bluetooth_settings"))
        assertNotNull(registry.get("get_wifi_state"))
        assertNotNull(registry.get("get_bluetooth_state"))
        assertNotNull(registry.get("get_ram_usage"))
        assertNotNull(registry.get("remember_fact"))
        assertNotNull(registry.get("search_chat_history"))
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

    @Test
    fun `CallContactTool requires confirmation and formats describeAction correctly`() {
        val tool = CallContactTool()
        assertTrue(tool.requiresConfirmation)

        assertEquals("Call Rahul Sharma at +91 98765 43210?", tool.describeAction(mapOf("name" to "Rahul Sharma", "phone_number" to "+91 98765 43210")))
        assertEquals("Call Mom?", tool.describeAction(mapOf("name" to "Mom")))
        assertEquals("Call +1234567890?", tool.describeAction(mapOf("phone_number" to "+1234567890")))
        assertEquals("Place phone call?", tool.describeAction(emptyMap()))
    }

    @Test
    fun `DialNumberTool does not require confirmation and remains ungated`() {
        val tool = DialNumberTool()
        org.junit.Assert.assertFalse(tool.requiresConfirmation)
        assertEquals("dial_number", tool.describeAction(mapOf("phone_number" to "12345")))
    }

    @Test
    fun `device control and memory tools do not require confirmation`() {
        org.junit.Assert.assertFalse(ToggleFlashlightTool().requiresConfirmation)
        org.junit.Assert.assertFalse(OpenWifiSettingsTool().requiresConfirmation)
        org.junit.Assert.assertFalse(OpenBluetoothSettingsTool().requiresConfirmation)
        org.junit.Assert.assertFalse(GetWifiStateTool().requiresConfirmation)
        org.junit.Assert.assertFalse(GetBluetoothStateTool().requiresConfirmation)
        org.junit.Assert.assertFalse(GetRamUsageTool().requiresConfirmation)
        org.junit.Assert.assertFalse(RememberFactTool().requiresConfirmation)
        org.junit.Assert.assertFalse(SearchChatHistoryTool().requiresConfirmation)
    }

    @Test
    fun `RememberFactTool validates missing content argument`() = runTest {
        val tool = RememberFactTool()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = tool.execute(dummyContext, emptyMap())

        org.junit.Assert.assertFalse(result.success)
        assertTrue(result.error?.contains("Missing content") == true)
    }

    @Test
    fun `RememberFactTool saves fact to memory store and returns success`() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("rem_tool_test").toFile()
        val memStore = dev.loki.android.core.conversation.MemoryStore(tempDir)
        val tool = RememberFactTool(memStore)
        val dummyContext = object : android.content.ContextWrapper(null) {}

        val result = tool.execute(dummyContext, mapOf("content" to "User's favorite color is blue"))
        assertTrue(result.success)
        assertEquals("remembered", result.data?.get("status"))
        assertEquals("User's favorite color is blue", result.data?.get("content"))

        val stored = memStore.getAll()
        assertEquals(1, stored.size)
        assertEquals("User's favorite color is blue", stored[0].text)
        assertEquals(dev.loki.android.core.conversation.MemorySource.MODEL_TOOL, stored[0].source)
        tempDir.deleteRecursively()
    }

    @Test
    fun `SearchChatHistoryTool validates missing query argument`() = runTest {
        val tool = SearchChatHistoryTool()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = tool.execute(dummyContext, emptyMap())

        org.junit.Assert.assertFalse(result.success)
        assertTrue(result.error?.contains("Missing query") == true)
    }

    @Test
    fun `SearchChatHistoryTool formats matching chat history results`() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("search_tool_test").toFile()
        val convStore = dev.loki.android.core.conversation.ConversationStore(tempDir)
        val conv = convStore.createConversation(title = "Cooking Chat")
        convStore.appendTurn(conv.id, dev.loki.android.core.conversation.ConversationTurn.User("How do I make chocolate cake?"), autoTitle = false)

        val tool = SearchChatHistoryTool(convStore)
        val dummyContext = object : android.content.ContextWrapper(null) {}

        val result = tool.execute(dummyContext, mapOf("query" to "cake"))
        assertTrue(result.success)
        assertEquals("1", result.data?.get("count"))
        assertTrue(result.data?.get("results")?.contains("Cooking Chat") == true)
        assertTrue(result.data?.get("results")?.contains("chocolate cake") == true)

        val emptyResult = tool.execute(dummyContext, mapOf("query" to "lasagna"))
        assertTrue(emptyResult.success)
        assertEquals("0", emptyResult.data?.get("count"))
        assertEquals("No matching chat history found.", emptyResult.data?.get("results"))

        tempDir.deleteRecursively()
    }

    @Test
    fun `ToggleFlashlightTool validates missing enabled argument`() = runTest {
        val tool = ToggleFlashlightTool()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = tool.execute(dummyContext, emptyMap())

        org.junit.Assert.assertFalse(result.success)
        assertTrue(result.error?.contains("Missing enabled") == true)
    }

    @Test
    fun `ToggleFlashlightTool returns error when CameraManager unavailable or probe fails`() = runTest {
        val tool = ToggleFlashlightTool()
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val result = tool.execute(dummyContext, mapOf("enabled" to true))

        org.junit.Assert.assertFalse(result.success)
    }

    @Test
    fun `OpenWifiSettingsTool launches wifi settings intent`() = runTest {
        val tool = OpenWifiSettingsTool()
        var launched = false
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun startActivity(intent: android.content.Intent?) {
                launched = true
            }
        }

        val result = tool.execute(dummyContext, emptyMap())
        assertTrue(result.success)
        assertEquals("settings_opened", result.data?.get("status"))
        assertEquals("wifi", result.data?.get("target"))
        assertTrue(launched)
    }

    @Test
    fun `OpenBluetoothSettingsTool launches bluetooth settings intent`() = runTest {
        val tool = OpenBluetoothSettingsTool()
        var launched = false
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun startActivity(intent: android.content.Intent?) {
                launched = true
            }
        }

        val result = tool.execute(dummyContext, emptyMap())
        assertTrue(result.success)
        assertEquals("settings_opened", result.data?.get("status"))
        assertEquals("bluetooth", result.data?.get("target"))
        assertTrue(launched)
    }

    @Test
    fun `GetWifiStateTool handles unavailable service gracefully`() = runTest {
        val tool = GetWifiStateTool()
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }

        val result = tool.execute(dummyContext, emptyMap())
        org.junit.Assert.assertFalse(result.success)
        assertTrue(result.error?.contains("unavailable") == true)
    }

    @Test
    fun `GetBluetoothStateTool returns unknown or boolean state without prompting`() = runTest {
        val tool = GetBluetoothStateTool()
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                android.content.pm.PackageManager.PERMISSION_DENIED
        }

        val result = tool.execute(dummyContext, emptyMap())
        // Should succeed without error, returning unknown or state
        assertTrue(result.success)
        assertNotNull(result.data?.get("enabled"))
    }

    @Test
    fun `GetRamUsageTool handles unavailable activity manager gracefully`() = runTest {
        val tool = GetRamUsageTool()
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
        }

        val result = tool.execute(dummyContext, emptyMap())
        org.junit.Assert.assertFalse(result.success)
        assertTrue(result.error?.contains("unavailable") == true)
    }
}
