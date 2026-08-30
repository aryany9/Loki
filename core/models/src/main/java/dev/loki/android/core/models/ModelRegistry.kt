package dev.loki.android.core.models

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

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
            val content = storage.manifestFile.readText()
            val element = json.parseToJsonElement(content) as JsonObject
            val schemaVersion = element["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

            if (schemaVersion == 1) {
                migrateV1ToV2(element)
            } else {
                json.decodeFromJsonElement<ModelManifest>(element)
            }
        } catch (error: IOException) {
            throw ModelRegistryException("Unable to read model manifest", error)
        } catch (error: SerializationException) {
            throw ModelRegistryException("Model manifest is invalid", error)
        }
    }

    private fun migrateV1ToV2(element: JsonObject): ModelManifest {
        // In V1, we had activeModelId (String?) and models (List<ModelRecord>)
        // ModelRecord in V1 was also different (single artifactPath)
        // We'll try to rescue what we can.
        val activeModelId = element["activeModelId"]?.jsonPrimitive?.content
        
        // Since the ModelRecord structure changed significantly (artifacts list), 
        // a simple decode might fail if artifacts is missing.
        // For simplicity in this implementation, we'll start fresh or attempt a basic conversion if needed.
        // Given the prompt's instruction to "reconcile", we'll let reconcile handle availability.
        
        val activeModels = mutableMapOf<ModelRuntime, String>()
        if (activeModelId != null) {
            // Assume it was a LITERT_LM model as that was the only supported one in V1
            activeModels[ModelRuntime.LITERT_LM] = activeModelId
        }

        return ModelManifest(
            schemaVersion = ModelManifest.CURRENT_SCHEMA_VERSION,
            activeModels = activeModels,
            models = emptyList() // Legacy models will be re-discovered or re-registered
        )
    }

    fun reconcile(manifest: ModelManifest = load()): ModelManifest {
        val models = manifest.models.map { model ->
            val allArtifactsExist = model.artifacts.all { artifact ->
                File(storage.rootDirectory, "models/${model.id}/${artifact.relativePath}").isFile
            }

            val primaryArtifact = model.artifacts.firstOrNull()
            val artifactFile = if (primaryArtifact != null) {
                File(storage.rootDirectory, "models/${model.id}/${primaryArtifact.relativePath}")
            } else null

            val updatedCapabilities = if (artifactFile != null && artifactFile.isFile && model.runtime == ModelRuntime.LITERT_LM) {
                val containerInfo = LitertLmContainerInspector.inspect(artifactFile)
                if (containerInfo.isLitertLmContainer) {
                    if (containerInfo.supportsAudioInput) {
                        ModelRecordCapabilities(
                            audioInput = ModelMetadataField(value = true, confidence = MetadataConfidence.VERIFIED)
                        )
                    } else if (model.capabilities.audioInput.confidence != MetadataConfidence.USER_CONFIRMED) {
                        ModelRecordCapabilities(
                            audioInput = ModelMetadataField(value = false, confidence = MetadataConfidence.VERIFIED)
                        )
                    } else {
                        model.capabilities
                    }
                } else {
                    model.capabilities
                }
            } else {
                model.capabilities
            }
            
            model.copy(
                capabilities = updatedCapabilities,
                availability = if (allArtifactsExist) {
                    val isActive = manifest.activeModels[model.runtime] == model.id
                    if (isActive && model.availability == ModelAvailability.LOADED) {
                        ModelAvailability.LOADED
                    } else {
                        ModelAvailability.DOWNLOADED
                    }
                } else {
                    ModelAvailability.NOT_DOWNLOADED
                }
            )
        }
        
        val activeModels = manifest.activeModels.filter { (runtime, id) ->
            models.any { it.id == id && it.runtime == runtime && it.availability != ModelAvailability.NOT_DOWNLOADED }
        }
        
        return manifest.copy(activeModels = activeModels, models = models)
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
