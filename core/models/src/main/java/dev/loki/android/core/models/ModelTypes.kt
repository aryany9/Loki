package dev.loki.android.core.models

import kotlinx.serialization.Serializable

@Serializable
enum class ModelRuntime {
    LITERT_LM,
    LITERT_ASR,
    CUSTOM_TTS
}

@Serializable
enum class ModelFormat {
    LITERT_MODEL,
    TFLITE,
    UNKNOWN
}

@Serializable
enum class ModelSource {
    BUNDLED_CATALOG,
    HUGGING_FACE,
    LOCAL_IMPORT,
    LEGACY_MIGRATION
}

@Serializable
enum class ModelAvailability {
    NOT_DOWNLOADED,
    DOWNLOADED,
    LOADED
}

@Serializable
enum class MetadataConfidence {
    UNKNOWN,
    HINT,
    VERIFIED,
    USER_CONFIRMED
}

@Serializable
data class ModelMetadataField<T>(
    val value: T? = null,
    val confidence: MetadataConfidence = MetadataConfidence.UNKNOWN
)

@Serializable
data class ModelArtifact(
    val fileName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val url: String
)

@Serializable
data class ModelRecordCapabilities(
    val audioInput: ModelMetadataField<Boolean> = ModelMetadataField(value = false, confidence = MetadataConfidence.UNKNOWN)
) {
    val isAudioInputSupported: Boolean
        get() = audioInput.value == true && (
            audioInput.confidence == MetadataConfidence.VERIFIED ||
            audioInput.confidence == MetadataConfidence.USER_CONFIRMED
        )
}

@Serializable
data class ModelRecord(
    val id: String,
    val displayName: String,
    val family: ModelMetadataField<String> = ModelMetadataField(),
    val runtime: ModelRuntime,
    val format: ModelFormat,
    val artifacts: List<ModelArtifact>,
    val source: ModelSource,
    val availability: ModelAvailability = ModelAvailability.DOWNLOADED,
    val importedAtEpochMs: Long,
    val lastUsedAtEpochMs: Long? = null,
    val capabilities: ModelRecordCapabilities = ModelRecordCapabilities()
)

@Serializable
data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val family: String? = null,
    val runtime: ModelRuntime,
    val format: ModelFormat,
    val artifacts: List<ModelArtifact>,
    val capabilities: List<String> = emptyList()
)

@Serializable
data class ModelCatalog(
    val schemaVersion: Int = 1,
    val models: List<ModelCatalogEntry> = emptyList()
)

@Serializable
data class ModelManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val activeModels: Map<ModelRuntime, String> = emptyMap(),
    val models: List<ModelRecord> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

fun ModelRecord.withAvailability(availability: ModelAvailability): ModelRecord =
    copy(availability = availability)

@Serializable
enum class ExecutionBackend {
    AUTOMATIC,
    GPU,
    CPU
}

@Serializable
data class GenerationConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val seed: Int? = null,
    val maxOutputTokens: Int? = null
)

@Serializable
data class RuntimeConfig(
    val backend: ExecutionBackend = ExecutionBackend.AUTOMATIC,
    val contextKvCapacity: Int? = null
)

@Serializable
data class AgentConfig(
    val systemInstruction: String = DEFAULT_SYSTEM_PROMPT,
    val generationConfig: GenerationConfig = GenerationConfig(),
    val runtimeConfig: RuntimeConfig = RuntimeConfig()
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are Loki, a private offline Android assistant running on the user's device."
    }
}

@Serializable
data class ModelCapabilities(
    val supportsText: Boolean = true,
    val supportsToolCalling: Boolean = true,
    val supportsAudioInput: Boolean = false,
    val supportsVisionInput: Boolean = false
)
