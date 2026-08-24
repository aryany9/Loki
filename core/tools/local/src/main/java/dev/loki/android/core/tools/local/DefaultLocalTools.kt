package dev.loki.android.core.tools.local

import dev.loki.android.core.tools.ToolRegistry

object DefaultLocalTools {

    fun registerAll(registry: ToolRegistry) {
        registry.register(GetCurrentTimeTool())
        registry.register(GetBatteryStatusTool())
        registry.register(OpenAppTool())
        registry.register(LookupContactTool())
        registry.register(CallContactTool())
        registry.register(DialNumberTool())
        registry.register(SetTimerTool())
        registry.register(SetAlarmTool())
        registry.register(MediaControlTool())
    }
}
