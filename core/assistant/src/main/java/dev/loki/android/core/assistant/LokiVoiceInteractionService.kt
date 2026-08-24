package dev.loki.android.core.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * LokiVoiceInteractionService is the top-level Android system service that registers
 * Loki as an Android Assistant.
 *
 * Declared in AndroidManifest.xml with action "android.service.voice.VoiceInteractionService".
 * When selected in Settings -> Default apps -> Digital assistant, Android binds to this service.
 */
class LokiVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "LokiVoiceInteractionService ready — Loki is active Android assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "LokiVoiceInteractionService shutdown")
    }

    companion object {
        private const val TAG = "LokiVoiceInteractionService"
    }
}
