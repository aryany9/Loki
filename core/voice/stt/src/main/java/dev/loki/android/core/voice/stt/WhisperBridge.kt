package dev.loki.android.core.voice.stt

import android.util.Log

/**
 * JNI wrapper for Whisper.cpp speech recognition engine in core:voice:stt.
 */
object WhisperBridge {

    init {
        try {
            System.loadLibrary("lokiwhisper")
            Log.i("WhisperBridge", "Native lokiwhisper library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("lokillama")
                Log.i("WhisperBridge", "Fallback loaded lokillama library successfully")
            } catch (e2: UnsatisfiedLinkError) {
                Log.e("WhisperBridge", "Failed to load lokiwhisper/lokillama library", e2)
            }
        }
    }

    external fun nativeInitWhisper(modelPath: String, nThreads: Int): Long

    external fun nativeFreeWhisper(handle: Long)

    external fun nativeTranscribe(
        handle: Long,
        pcmFloats: FloatArray,
        nSamples: Int,
        language: String
    ): String
}
