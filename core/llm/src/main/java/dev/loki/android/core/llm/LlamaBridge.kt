package dev.loki.android.core.llm

import android.util.Log

/**
 * JNI wrapper for native llama.cpp engine in core:llm.
 */
object LlamaBridge {

    init {
        try {
            System.loadLibrary("lokillama")
            Log.i("LlamaBridge", "Native lokillama library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("LlamaBridge", "Failed to load lokillama library", e)
        }
    }

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    external fun nativeInitModel(
        modelPath: String,
        nCtx: Int,
        nThreads: Int
    ): Long

    external fun nativeFreeModel(handle: Long)

    external fun nativeCancel(handle: Long)

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        grammarStr: String?,
        maxTokens: Int,
        callback: TokenCallback?
    ): String

    external fun nativeJsonSchemaToGrammar(jsonSchema: String): String
}
