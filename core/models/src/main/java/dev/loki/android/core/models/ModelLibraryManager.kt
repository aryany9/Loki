package dev.loki.android.core.models

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
    private val registry: ModelRegistry
) {
    val managedStorage: ModelStorage = storage
    private val mutex = Mutex()
    private val controllers = mutableMapOf<ModelRuntime, ModelRuntimeController>()
    
    private val _manifest = MutableStateFlow(registry.reconcile())
    val manifest: StateFlow<ModelManifest> = _manifest.asStateFlow()

    private val readinessProviders = mutableMapOf<ModelRuntime, () -> Boolean>()

    fun registerRuntime(runtime: ModelRuntime, controller: ModelRuntimeController) {
        controllers[runtime] = controller
    }

    /**
     * Registers a live readiness provider for [runtime]. The provider is called by
     * [isRuntimeReady] in addition to the manifest check to confirm the runtime context
     * is currently live (e.g. a TFLite Interpreter is actually constructed).
     *
     * Call this alongside [registerRuntime] for engines that perform real initialization
     * work (e.g. [LiteRtWhisperEngine] building a TFLite Interpreter in `initialize()`).
     */
    fun registerReadinessProvider(runtime: ModelRuntime, provider: () -> Boolean) {
        readinessProviders[runtime] = provider
    }

    /**
     * Returns true when the manifest shows a LOADED model for [runtime] AND the registered
     * readiness provider (if any) confirms the runtime context is live. This makes the
     * existing [AssistantSession] precheck accurate: it fails fast with a clean Error if
     * the engine isn't actually initialized, rather than crashing mid-turn.
     */
    fun isRuntimeReady(runtime: ModelRuntime): Boolean {
        val current = manifest.value
        val activeId = current.activeModels[runtime] ?: return false
        val manifestReady = current.models.any { it.id == activeId && it.availability == ModelAvailability.LOADED }
        if (!manifestReady) return false
        return readinessProviders[runtime]?.invoke() ?: true
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
