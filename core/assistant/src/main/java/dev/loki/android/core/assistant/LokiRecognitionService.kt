package dev.loki.android.core.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

/**
 * LokiRecognitionService satisfies the framework requirement for VoiceInteractionServiceInfo
 * metadata resolution.
 */
class LokiRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(TAG, "onStartListening()")
    }

    override fun onCancel(listener: Callback?) {
        Log.i(TAG, "onCancel()")
    }

    override fun onStopListening(listener: Callback?) {
        Log.i(TAG, "onStopListening()")
    }

    companion object {
        private const val TAG = "LokiRecognitionService"
    }
}
