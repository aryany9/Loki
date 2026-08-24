package dev.loki.android.core.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * LokiVoiceInteractionSessionService creates LokiVoiceInteractionSession instances
 * when the Android system triggers the assistant.
 */
class LokiVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "onNewSession() args=$args")
        return LokiVoiceInteractionSession(this)
    }

    companion object {
        private const val TAG = "LokiVoiceSessionService"
    }
}
