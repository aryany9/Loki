package dev.loki.android.core.tools.local

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

class MediaControlTool : LocalTool {
    override val name: String = "media_control"
    override val capability: String = "media"
    override val description: String = "Control media playback (play, pause, next, previous, toggle)."
    override val parameters: Map<String, ToolParam> = mapOf(
        "action" to ToolParam(ToolParamType.STRING, "Action to perform: play, pause, next, previous, toggle", required = true)
    )

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val action = arguments["action"]?.toString()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing media action", ToolErrorCode.VALIDATION_ERROR)

        val keycode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle", "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "next_track", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "previous_track", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ToolResult.error("Unsupported media action: $action", ToolErrorCode.VALIDATION_ERROR)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult.error("AudioManager unavailable", ToolErrorCode.EXECUTION_ERROR)

        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))

        return ToolResult.success(
            mapOf(
                "action" to action,
                "status" to "dispatched"
            )
        )
    }
}
