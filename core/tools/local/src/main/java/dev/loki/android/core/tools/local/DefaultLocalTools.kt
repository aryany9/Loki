package dev.loki.android.core.tools.local

import dev.loki.android.core.conversation.ConversationStore
import dev.loki.android.core.conversation.MemoryStore
import dev.loki.android.core.tools.ToolRegistry

object DefaultLocalTools {

    fun registerAll(
        registry: ToolRegistry,
        memoryStore: MemoryStore? = null,
        conversationStore: ConversationStore? = null
    ) {
        registry.register(GetCurrentTimeTool())
        registry.register(GetBatteryStatusTool())
        registry.register(OpenAppTool())
        registry.register(LookupContactTool())
        registry.register(CallContactTool())
        registry.register(DialNumberTool())
        registry.register(SetTimerTool())
        registry.register(SetAlarmTool())
        registry.register(MediaControlTool())
        registry.register(ToggleFlashlightTool())
        registry.register(OpenWifiSettingsTool())
        registry.register(OpenBluetoothSettingsTool())
        registry.register(GetWifiStateTool())
        registry.register(GetBluetoothStateTool())
        registry.register(GetRamUsageTool())
        registry.register(RememberFactTool(memoryStore))
        registry.register(SearchChatHistoryTool(conversationStore))
    }
}
