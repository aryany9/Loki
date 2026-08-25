package dev.loki.android.core.llm

import android.content.Context
import java.io.File

/**
 * ModelManager handles local GGUF model discovery, storage paths, and availability.
 */
class ModelManager(private val context: Context) {

    fun getDefaultModelFile(): File? {
        return getGgufModelFile() ?: getLiteRtModelFile()
    }

    fun getGgufModelFile(): File? {
        val appFilesDir = context.getExternalFilesDir(null)
        val defaultModel = File(appFilesDir, "model.gguf")
        if (defaultModel.exists()) return defaultModel

        val internalModel = File(context.filesDir, "model.gguf")
        if (internalModel.exists()) return internalModel

        // Look for any .gguf file in the external files dir
        val ggufFiles = appFilesDir?.listFiles { _, name -> name.endsWith(".gguf") }
        if (!ggufFiles.isNullOrEmpty()) return ggufFiles.first()

        return null
    }

    fun getLiteRtModelFile(): File? {
        val appFilesDir = context.getExternalFilesDir(null)
        val defaultModel = File(appFilesDir, "model.bin")
        if (defaultModel.exists()) return defaultModel

        val internalModel = File(context.filesDir, "model.bin")
        if (internalModel.exists()) return internalModel

        // Look for any .bin file in the external files dir
        val binFiles = appFilesDir?.listFiles { _, name -> name.endsWith(".bin") }
        if (!binFiles.isNullOrEmpty()) return binFiles.first()

        return null
    }

    fun isModelAvailable(): Boolean = getGgufModelFile()?.exists() == true || getLiteRtModelFile()?.exists() == true
}
