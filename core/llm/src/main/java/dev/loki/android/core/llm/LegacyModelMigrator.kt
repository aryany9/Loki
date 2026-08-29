package dev.loki.android.core.llm

import android.content.Context
import dev.loki.android.core.models.MetadataConfidence
import dev.loki.android.core.models.LiteRtModelDetector
import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelDetection
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelMetadataField
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRegistry
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelSource
import dev.loki.android.core.models.ModelStorage
import dev.loki.android.core.models.ModelTransfer
import dev.loki.android.core.models.TransferResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LegacyModelMigrator(
    private val context: Context?,
    private val storage: ModelStorage,
    private val registry: ModelRegistry,
    private val transfer: ModelTransfer = ModelTransfer(),
    private val legacyLocations: () -> List<File> = {
        context?.let {
            listOfNotNull(
                File(it.filesDir, "model.litertlm").takeIf { file -> file.isFile },
                it.getExternalFilesDir(null)?.let { dir -> File(dir, "model.litertlm") }?.takeIf { file -> file.isFile }
            )
        } ?: emptyList()
    }
) {
    suspend fun migrate(): List<ModelRecord> = withContext(Dispatchers.IO) {
        val candidates = legacyLocations()
        val existing = registry.load()
        val migrated = candidates.filterNot { candidate ->
            existing.models.any { model -> 
                model.artifacts.any { artifact ->
                    File(storage.rootDirectory, "models/${model.id}/${artifact.relativePath}").canonicalPath == candidate.canonicalPath 
                }
            }
        }.mapNotNull { candidate -> adopt(candidate, existing.models) }
        if (migrated.isNotEmpty()) {
            registry.save(existing.copy(models = existing.models + migrated))
        }
        migrated
    }

    private suspend fun adopt(source: File, existing: List<ModelRecord>): ModelRecord? {
        val detection = LiteRtModelDetector().detect(source)
        if (detection !is ModelDetection.Detected) return null
        val id = "legacy-${source.nameWithoutExtension.lowercase()}-${source.length()}"
        if (existing.any { it.id == id }) return null
        val target = storage.artifactFile(id, source.name)
        val part = storage.partialArtifactFile(id, source.name)
        val result = source.inputStream().use { input -> transfer.copyToPart(input, part, source.length()) }
        if (result !is TransferResult.Completed) return null
        transfer.finalizePart(part, target)
        
        val artifact = ModelArtifact(
            fileName = source.name,
            relativePath = source.name,
            sizeBytes = target.length(),
            sha256 = result.sha256,
            url = ""
        )
        
        return ModelRecord(
            id = id,
            displayName = source.nameWithoutExtension,
            family = ModelMetadataField(confidence = MetadataConfidence.UNKNOWN),
            runtime = ModelRuntime.LITERT_LM,
            format = ModelFormat.LITERT_MODEL,
            artifacts = listOf(artifact),
            source = ModelSource.LEGACY_MIGRATION,
            importedAtEpochMs = System.currentTimeMillis()
        )
    }
}
