package dev.loki.android.core.llm

import kotlinx.serialization.Serializable

@Serializable
enum class ModelRuntime {
    LITERT_LM
}

@Serializable
enum class ModelFormat {
    LITERT_MODEL,
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
data class ModelRecord(
    val id: String,
    val displayName: String,
    val family: ModelMetadataField<String> = ModelMetadataField(),
    val runtime: ModelRuntime = ModelRuntime.LITERT_LM,
    val format: ModelFormat = ModelFormat.LITERT_MODEL,
    val artifactPath: String,
    val artifactFileName: String,
    val sizeBytes: Long,
    val source: ModelSource,
    val sourceUrl: String? = null,
    val sha256: String? = null,
    val capabilities: List<String> = emptyList(),
    val availability: ModelAvailability = ModelAvailability.DOWNLOADED,
    val importedAtEpochMs: Long,
    val lastUsedAtEpochMs: Long? = null
)

@Serializable
data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val family: String? = null,
    val runtime: ModelRuntime = ModelRuntime.LITERT_LM,
    val format: ModelFormat = ModelFormat.LITERT_MODEL,
    val artifactUrl: String,
    val expectedSizeBytes: Long? = null,
    val sha256: String? = null,
    val capabilities: List<String> = emptyList()
)

@Serializable
data class ModelManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val activeModelId: String? = null,
    val models: List<ModelRecord> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

fun ModelRecord.withAvailability(availability: ModelAvailability): ModelRecord =
    copy(availability = availability)
