package dev.loki.android.core.llm

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ModelRegistry(
    private val storage: ModelStorage,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    fun load(): ModelManifest {
        storage.ensureDirectories()
        if (!storage.manifestFile.exists()) return ModelManifest()

        return try {
            json.decodeFromString<ModelManifest>(storage.manifestFile.readText())
        } catch (error: IOException) {
            throw ModelRegistryException("Unable to read model manifest", error)
        } catch (error: SerializationException) {
            throw ModelRegistryException("Model manifest is invalid", error)
        }
    }

    fun reconcile(manifest: ModelManifest = load()): ModelManifest {
        val models = manifest.models.map { model ->
            val exists = File(storage.rootDirectory, model.artifactPath).isFile
            model.withAvailability(
                if (exists) {
                    if (model.id == manifest.activeModelId && model.availability == ModelAvailability.LOADED) {
                        ModelAvailability.LOADED
                    } else {
                        ModelAvailability.DOWNLOADED
                    }
                } else {
                    ModelAvailability.NOT_DOWNLOADED
                }
            )
        }
        val activeId = manifest.activeModelId?.takeIf { id ->
            models.any { it.id == id && it.availability != ModelAvailability.NOT_DOWNLOADED }
        }
        return manifest.copy(activeModelId = activeId, models = models)
    }

    @Synchronized
    fun save(manifest: ModelManifest) {
        storage.ensureDirectories()
        require(manifest.models.map { it.id }.toSet().size == manifest.models.size) {
            "Model IDs must be unique"
        }
        val temporary = File(storage.manifestFile.parentFile, "${storage.manifestFile.name}.part")
        temporary.writeText(json.encodeToString(ModelManifest.serializer(), manifest))
        if (storage.manifestFile.exists() && !storage.manifestFile.delete()) {
            temporary.delete()
            throw ModelRegistryException("Unable to replace model manifest")
        }
        if (!temporary.renameTo(storage.manifestFile)) {
            temporary.delete()
            throw ModelRegistryException("Unable to finalize model manifest")
        }
    }

    @Synchronized
    fun update(transform: (ModelManifest) -> ModelManifest): ModelManifest {
        val updated = transform(load())
        save(updated)
        return updated
    }
}

class ModelRegistryException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
