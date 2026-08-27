package dev.loki.android.core.llm

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ModelRuntimeController {
    suspend fun load(model: ModelRecord): Boolean
    suspend fun unload(model: ModelRecord)
}

class ModelLibraryManager(
    private val storage: ModelStorage,
    private val registry: ModelRegistry,
    private val runtime: ModelRuntimeController
) {
    val managedStorage: ModelStorage = storage
    private val mutex = Mutex()
    private val _manifest = MutableStateFlow(registry.reconcile())
    val manifest: StateFlow<ModelManifest> = _manifest.asStateFlow()

    suspend fun register(model: ModelRecord): Boolean = mutex.withLock {
        val current = registry.reconcile()
        if (current.models.any { it.id == model.id }) return@withLock false
        publish(current.copy(models = current.models + model))
        true
    }

    suspend fun load(modelId: String): Boolean = mutex.withLock {
        val current = registry.reconcile()
        val selected = current.models.firstOrNull { it.id == modelId }
            ?: return@withLock false
        if (selected.availability == ModelAvailability.NOT_DOWNLOADED) return@withLock false

        val currentLoaded = current.models.firstOrNull {
            it.availability == ModelAvailability.LOADED && it.id != selected.id
        }
        if (currentLoaded != null) runtime.unload(currentLoaded)

        if (!runtime.load(selected)) {
            publish(current.copy(models = current.models.map { it.withAvailability(if (it.id == currentLoaded?.id) ModelAvailability.LOADED else it.availability) }))
            return@withLock false
        }

        val updated = current.copy(
            activeModelId = selected.id,
            models = current.models.map {
                when (it.id) {
                    selected.id -> it.copy(
                        availability = ModelAvailability.LOADED,
                        lastUsedAtEpochMs = System.currentTimeMillis()
                    )
                    currentLoaded?.id -> it.withAvailability(ModelAvailability.DOWNLOADED)
                    else -> it.withAvailability(if (it.availability == ModelAvailability.LOADED) ModelAvailability.DOWNLOADED else it.availability)
                }
            }
        )
        publish(updated)
        true
    }

    suspend fun eject(): Boolean = mutex.withLock {
        val current = registry.reconcile()
        val loaded = current.models.firstOrNull { it.availability == ModelAvailability.LOADED }
            ?: return@withLock false
        runtime.unload(loaded)
        publish(current.copy(activeModelId = null, models = current.models.map {
            if (it.id == loaded.id) it.withAvailability(ModelAvailability.DOWNLOADED) else it
        }))
        true
    }

    suspend fun delete(modelId: String): Boolean = mutex.withLock {
        val current = registry.reconcile()
        val selected = current.models.firstOrNull { it.id == modelId }
            ?: return@withLock false
        if (selected.availability == ModelAvailability.LOADED) runtime.unload(selected)
        File(storage.rootDirectory, selected.artifactPath).delete()
        storage.modelDirectory(selected.id).deleteRecursively()
        publish(current.copy(
            activeModelId = current.activeModelId?.takeUnless { it == modelId },
            models = current.models.filterNot { it.id == modelId }
        ))
        true
    }

    private fun publish(manifest: ModelManifest) {
        registry.save(manifest)
        _manifest.value = manifest
    }
}
