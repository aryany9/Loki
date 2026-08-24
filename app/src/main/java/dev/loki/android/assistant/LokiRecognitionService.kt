package dev.loki.android.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

/**
 * LokiRecognitionService provides the companion RecognitionService required by Android's
 * VoiceInteractionService framework.
 *
 * The Android framework's VoiceInteractionServiceInfo parser strictly checks for a valid
 * android:recognitionService attribute in interaction_service.xml. Without this declared
 * service, Android flags a metadata parse error and omits the app from the Digital Assistant
 * selection list.
 */
class LokiRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(TAG, "onStartListening")
    }

    override fun onCancel(listener: Callback?) {
        Log.i(TAG, "onCancel")
    }

    override fun onStopListening(listener: Callback?) {
        Log.i(TAG, "onStopListening")
    }

    companion object {
        private const val TAG = "LokiRecService"
    }
}
