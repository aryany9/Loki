package dev.loki.android.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * LokiVoiceInteractionService is the top-level Android system service that registers
 * Loki as an Android Assistant.
 *
 * This service is declared in AndroidManifest.xml with the action
 * "android.service.voice.VoiceInteractionService". When the user selects Loki as
 * their default assistant in Settings → Default apps → Digital assistant, Android
 * binds to this service.
 *
 * Responsibilities:
 * - Serve as the system-visible entry point for the Android Assistant role.
 * - Keep this class thin: all session logic lives in LokiVoiceInteractionSession.
 *
 * Spike 1 note: This is intentionally minimal. No STT, LLM, or tool logic here.
 * The goal is to validate that the service is discovered and bound by Android.
 */
class LokiVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "LokiVoiceInteractionService ready — Loki is the active Android assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "LokiVoiceInteractionService shutdown")
    }

    companion object {
        private const val TAG = "LokiVIS"
    }
}
