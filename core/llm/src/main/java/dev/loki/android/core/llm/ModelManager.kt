package dev.loki.android.core.llm

import android.content.Context
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRegistry
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelStorage
import java.io.File

/**
 * ModelManager handles .litertlm model artifact discovery, managed storage paths, and registry persistence.
 * Holds NO live runtime/Engine handles.
 */
class ModelManager(private val context: Context) {

    val modelStorage: ModelStorage by lazy {
        ModelStorage(File(context.filesDir, "models"))
    }

    val modelRegistry: ModelRegistry by lazy { ModelRegistry(modelStorage) }

    fun getStorageRoot(): File = modelStorage.rootDirectory

    fun getActiveModel(): ModelRecord? {
        val manifest = modelRegistry.reconcile()
        val activeId = manifest.activeModels[ModelRuntime.LITERT_LM] ?: return null
        return manifest.models.firstOrNull { it.id == activeId }
    }

    fun getLiteRtModelFile(): File? {
        val active = getActiveModel()
        if (active != null) {
            // Find the .litertlm artifact
            val artifact = active.artifacts.firstOrNull { it.fileName.endsWith(".litertlm") }
            if (artifact != null) {
                val managed = File(modelStorage.rootDirectory, "models/${active.id}/${artifact.relativePath}")
                if (managed.isFile) return managed
            }
        }

        // Fallback search
        val appFilesDir = context.getExternalFilesDir(null)
        val internalModel = File(context.filesDir, "model.litertlm")
        if (internalModel.exists()) return internalModel

        // Look for any .litertlm file in app storage
        val litertFiles = appFilesDir?.listFiles { _, name -> name.endsWith(".litertlm", ignoreCase = true) }
        if (!litertFiles.isNullOrEmpty()) return litertFiles.first()

        return null
    }

    fun isModelAvailable(): Boolean = getLiteRtModelFile()?.exists() == true
}
