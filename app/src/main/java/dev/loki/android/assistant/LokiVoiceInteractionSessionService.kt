package dev.loki.android.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * LokiVoiceInteractionSessionService acts as a factory for LokiVoiceInteractionSession.
 *
 * When Android invokes the assistant (via long-press home, power button shortcut,
 * or lock-screen gesture), it asks this service to create a new VoiceInteractionSession.
 */
class LokiVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "Creating new LokiVoiceInteractionSession with args: $args")
        return LokiVoiceInteractionSession(this)
    }

    companion object {
        private const val TAG = "LokiVISS"
    }
}
