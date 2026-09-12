package dev.loki.android.core.tools

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenActionMatrixPolicyTest {

    private class TestTool(
        override val name: String,
        override val capability: String = "general"
    ) : LocalTool {
        override val description: String = "Test tool"
        override val parameters: Map<String, ToolParam> = emptyMap()
        override val requiredPermissions: List<String> = emptyList()
        override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
            return ToolResult.success(emptyMap())
        }
    }

    private val policy = LockScreenActionMatrixPolicy()

    @Test
    fun `all tools are allowed unconditionally when device is UNLOCKED`() {
        val toolsToTest = listOf(
            TestTool("open_app"),
            TestTool("search_chat_history"),
            TestTool("open_wifi_settings"),
            TestTool("call_contact"),
            TestTool("unknown_custom_tool")
        )

        for (tool in toolsToTest) {
            val decision = policy.evaluate(
                tool = tool,
                arguments = mapOf("app_name" to "YouTube"),
                deviceLockState = DeviceLockState.UNLOCKED
            )
            assertTrue("Expected tool ${tool.name} to be allowed when UNLOCKED", decision is AccessDecision.Allow)
        }
    }

    @Test
    fun `allowlisted tools are permitted when device is LOCKED`() {
        val allowedTools = listOf(
            TestTool("call_contact"),
            TestTool("dial_number"),
            TestTool("lookup_contact"),
            TestTool("select_contact"),
            TestTool("toggle_flashlight"),
            TestTool("media_control"),
            TestTool("set_timer"),
            TestTool("set_alarm"),
            TestTool("get_current_time"),
            TestTool("get_battery_status"),
            TestTool("get_wifi_state"),
            TestTool("get_bluetooth_state"),
            TestTool("get_ram_usage"),
            TestTool("ask_user")
        )

        for (tool in allowedTools) {
            val decision = policy.evaluate(
                tool = tool,
                arguments = emptyMap(),
                deviceLockState = DeviceLockState.LOCKED
            )
            assertTrue("Expected allowlisted tool ${tool.name} to be allowed when LOCKED", decision is AccessDecision.Allow)
        }
    }

    @Test
    fun `open_app is denied when device is LOCKED with app-specific message`() {
        val tool = TestTool("open_app")
        val decision = policy.evaluate(
            tool = tool,
            arguments = mapOf("app_name" to "YouTube"),
            deviceLockState = DeviceLockState.LOCKED
        )

        assertTrue(decision is AccessDecision.Deny)
        val deny = decision as AccessDecision.Deny
        assertEquals(DenialReason.DEVICE_LOCKED, deny.reason)
        assertEquals("Please unlock your phone to open YouTube.", deny.message)
    }

    @Test
    fun `open_app is denied with generic apps message when app_name is missing`() {
        val tool = TestTool("open_app")
        val decision = policy.evaluate(
            tool = tool,
            arguments = emptyMap(),
            deviceLockState = DeviceLockState.LOCKED
        )

        assertTrue(decision is AccessDecision.Deny)
        val deny = decision as AccessDecision.Deny
        assertEquals(DenialReason.DEVICE_LOCKED, deny.reason)
        assertEquals("Please unlock your phone to open apps.", deny.message)
    }

    @Test
    fun `open_app falls back to package_name or name when app_name is absent`() {
        val tool = TestTool("open_app")
        val decision = policy.evaluate(
            tool = tool,
            arguments = mapOf("package_name" to "com.google.android.youtube"),
            deviceLockState = DeviceLockState.LOCKED
        )

        assertTrue(decision is AccessDecision.Deny)
        val deny = decision as AccessDecision.Deny
        assertEquals("Please unlock your phone to open com.google.android.youtube.", deny.message)
    }

    @Test
    fun `settings tools are denied when device is LOCKED with settings guidance`() {
        val wifiTool = TestTool("open_wifi_settings")
        val btTool = TestTool("open_bluetooth_settings")

        for (tool in listOf(wifiTool, btTool)) {
            val decision = policy.evaluate(tool, emptyMap(), DeviceLockState.LOCKED)
            assertTrue(decision is AccessDecision.Deny)
            val deny = decision as AccessDecision.Deny
            assertEquals(DenialReason.DEVICE_LOCKED, deny.reason)
            assertEquals("Please unlock your device to change settings.", deny.message)
        }
    }

    @Test
    fun `memory and chat history tools are denied when device is LOCKED`() {
        val searchTool = TestTool("search_chat_history")
        val rememberTool = TestTool("remember_fact")

        for (tool in listOf(searchTool, rememberTool)) {
            val decision = policy.evaluate(tool, emptyMap(), DeviceLockState.LOCKED)
            assertTrue(decision is AccessDecision.Deny)
            val deny = decision as AccessDecision.Deny
            assertEquals(DenialReason.DEVICE_LOCKED, deny.reason)
            assertEquals("Please unlock your device to access personal memory and history.", deny.message)
        }
    }

    @Test
    fun `unlisted tools are denied when device is LOCKED with generic fallback`() {
        val unlistedTool = TestTool("some_unlisted_tool")
        val decision = policy.evaluate(unlistedTool, emptyMap(), DeviceLockState.LOCKED)

        assertTrue(decision is AccessDecision.Deny)
        val deny = decision as AccessDecision.Deny
        assertEquals(DenialReason.DEVICE_LOCKED, deny.reason)
        assertEquals("Please unlock your device to use this feature.", deny.message)
    }

    @Test
    fun `custom allowed tools set overrides defaults`() {
        val customPolicy = LockScreenActionMatrixPolicy(
            allowedToolsOnLockScreen = setOf("custom_safe_tool")
        )

        val customTool = TestTool("custom_safe_tool")
        val callTool = TestTool("call_contact")

        val customDecision = customPolicy.evaluate(customTool, emptyMap(), DeviceLockState.LOCKED)
        val callDecision = customPolicy.evaluate(callTool, emptyMap(), DeviceLockState.LOCKED)

        assertTrue(customDecision is AccessDecision.Allow)
        assertTrue(callDecision is AccessDecision.Deny)
    }
}
