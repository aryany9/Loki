package dev.loki.android.voice

import android.util.Log

/**
 * JNI wrapper for Whisper.cpp speech recognition engine.
 */
object WhisperBridge {

    init {
        try {
            System.loadLibrary("lokillama")
            Log.i("WhisperBridge", "Native whisper bridge loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("WhisperBridge", "Failed to load lokillama library with whisper", e)
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
