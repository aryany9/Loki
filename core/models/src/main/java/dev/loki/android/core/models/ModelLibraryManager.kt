package dev.loki.android.core.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ModelRuntimeController {
    /**
     * Whether this runtime controller currently has a live, usable runtime context.
     * Implementations must return true only when the underlying runtime is ready to serve
     * requests (e.g. a native handle is valid). Defaults to true for controllers that do not
     * manage a stateful runtime context.
     *
     * Named [isEngineReady] (not `isReady`) to avoid a JVM signature clash with any
     * co-implemented interface that declares `fun isReady(): Boolean` (e.g. [LlmEngine]).
     */
    val isEngineReady: Boolean get() = true
    suspend fun load(model: ModelRecord): Boolean
    suspend fun unload(model: ModelRecord)
}

class ModelLibraryManager(
    private val storage: ModelStorage,
    private val registry: ModelRegistry
) {
    val managedStorage: ModelStorage = storage
    private val mutex = Mutex()
    private val controllers = mutableMapOf<ModelRuntime, ModelRuntimeController>()
    
    private val _manifest = MutableStateFlow(registry.reconcile())
    val manifest: StateFlow<ModelManifest> = _manifest.asStateFlow()

    fun registerRuntime(runtime: ModelRuntime, controller: ModelRuntimeController) {
        controllers[runtime] = controller
    }

    /**
     * Returns true only when both conditions hold:
     * 1. The manifest has a LOADED model active for [runtime] (load was called and succeeded).
     * 2. The registered [ModelRuntimeController] for [runtime] reports [ModelRuntimeController.isReady]
     *    — i.e. its native/runtime context is currently live.
     *
     * This makes the existing [AssistantSession] precheck accurate without any session changes:
     * once the STT engine's native handle is valid, this returns true; if the handle was never
     * set or was freed, this returns false and the precheck surfaces an Error state.
     */
    fun isRuntimeReady(runtime: ModelRuntime): Boolean {
        val current = manifest.value
        val activeId = current.activeModels[runtime] ?: return false
        val manifestReady = current.models.any { it.id == activeId && it.availability == ModelAvailability.LOADED }
        if (!manifestReady) return false
        // Also check the controller's live readiness — catches cases where the runtime handle
        // was freed after a successful load (e.g. OOM, release() called externally).
        val controller = controllers[runtime] ?: return false
        return controller.isEngineReady
    }

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
        
        val controller = controllers[selected.runtime] ?: return@withLock false
        if (selected.availability == ModelAvailability.NOT_DOWNLOADED) return@withLock false

        val previousActiveId = current.activeModels[selected.runtime]
        val previousActiveModel = current.models.firstOrNull { it.id == previousActiveId }

        if (previousActiveModel != null && previousActiveId != selected.id) {
            controller.unload(previousActiveModel)
        }

        if (!controller.load(selected)) {
            // Revert state if failed
            return@withLock false
        }

        val updatedActiveModels = current.activeModels.toMutableMap()
        updatedActiveModels[selected.runtime] = selected.id

        val updatedModels = current.models.map {
            when {
                it.id == selected.id -> it.copy(
                    availability = ModelAvailability.LOADED,
                    lastUsedAtEpochMs = System.currentTimeMillis()
                )
                it.id == previousActiveId -> it.withAvailability(ModelAvailability.DOWNLOADED)
                else -> it
            }
        }

        publish(current.copy(activeModels = updatedActiveModels, models = updatedModels))
        true
    }

    suspend fun eject(runtime: ModelRuntime): Boolean = mutex.withLock {
        val current = registry.reconcile()
        val activeId = current.activeModels[runtime] ?: return@withLock false
        val activeModel = current.models.firstOrNull { it.id == activeId } ?: return@withLock false
        
        controllers[runtime]?.unload(activeModel)
        
        val updatedActiveModels = current.activeModels.toMutableMap()
        updatedActiveModels.remove(runtime)
        
        val updatedModels = current.models.map {
            if (it.id == activeId) it.withAvailability(ModelAvailability.DOWNLOADED) else it
        }
        
        publish(current.copy(activeModels = updatedActiveModels, models = updatedModels))
        true
    }

    suspend fun delete(modelId: String): Boolean = mutex.withLock {
        val current = registry.reconcile()
        val selected = current.models.firstOrNull { it.id == modelId }
            ?: return@withLock false
            
        if (selected.availability == ModelAvailability.LOADED) {
            controllers[selected.runtime]?.unload(selected)
        }
        
        storage.modelDirectory(selected.id).deleteRecursively()
        
        val updatedActiveModels = current.activeModels.toMutableMap()
        if (updatedActiveModels[selected.runtime] == modelId) {
            updatedActiveModels.remove(selected.runtime)
        }
        
        publish(current.copy(
            activeModels = updatedActiveModels,
            models = current.models.filterNot { it.id == modelId }
        ))
        true
    }

    private fun publish(manifest: ModelManifest) {
        registry.save(manifest)
        _manifest.value = manifest
    }
}
