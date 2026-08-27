package dev.loki.android.core.llm

import android.content.Context
import java.io.File

/**
 * ModelManager handles .litertlm model artifact discovery, managed storage paths, and registry persistence.
 * Holds NO live runtime/Engine handles.
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

    fun getLiteRtModelFile(): File? {
        val active = getActiveModel()
        if (active != null) {
            val managed = File(modelStorage.rootDirectory, active.artifactPath)
            if (managed.isFile) return managed
        }

        val appFilesDir = context.getExternalFilesDir(null)
        val defaultModel = File(appFilesDir, "model.litertlm")
        if (defaultModel.exists()) return defaultModel

        val internalModel = File(context.filesDir, "model.litertlm")
        if (internalModel.exists()) return internalModel

        // Look for any .litertlm file in app storage
        val litertFiles = appFilesDir?.listFiles { _, name -> name.endsWith(".litertlm", ignoreCase = true) }
        if (!litertFiles.isNullOrEmpty()) return litertFiles.first()

        return null
    }

    fun isModelAvailable(): Boolean = getLiteRtModelFile()?.exists() == true
}
