package dev.loki.android.core.llm

import android.content.Context
import java.io.File

/**
 * ModelManager handles local GGUF model discovery, storage paths, and availability.
 */
class ModelManager(private val context: Context) {

    val modelStorage: ModelStorage by lazy {
        ModelStorage(File(context.getExternalFilesDir(null), "models"))
    }

    val modelRegistry: ModelRegistry by lazy { ModelRegistry(modelStorage) }

    fun getActiveModel(): ModelRecord? {
        val manifest = modelRegistry.reconcile()
        return manifest.models.firstOrNull { it.id == manifest.activeModelId }
    }

    fun getDefaultModelFile(): File? {
        val active = getActiveModel()
        if (active != null) {
            val managed = File(modelStorage.rootDirectory, active.artifactPath)
            if (managed.isFile) return managed
        }
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
        val active = getActiveModel()
        if (active?.runtime == ModelRuntime.LITERT_LM) {
            val managed = File(modelStorage.rootDirectory, active.artifactPath)
            if (managed.isFile) return managed
        }

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
