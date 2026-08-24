package dev.loki.android.core.voice.stt

import android.content.Context
import java.io.File

/**
 * Manages discovery, storage, and loading of Whisper STT model files.
 */
class WhisperModelManager(private val context: Context) {

    fun getDefaultModelFile(): File? {
        val appFilesDir = context.getExternalFilesDir(null)
        val defaultModel = File(appFilesDir, "whisper.bin")
        if (defaultModel.exists()) return defaultModel

        val ggmlModel = File(appFilesDir, "ggml-tiny.en.bin")
        if (ggmlModel.exists()) return ggmlModel

        val internalModel = File(context.filesDir, "whisper.bin")
        if (internalModel.exists()) return internalModel

        return null
    }

    fun isModelAvailable(): Boolean = getDefaultModelFile()?.exists() == true
}
