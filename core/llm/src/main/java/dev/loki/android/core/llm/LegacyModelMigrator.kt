package dev.loki.android.core.llm

import android.content.Context
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
                File(it.filesDir, "model.gguf").takeIf { file -> file.isFile },
                File(it.filesDir, "model.bin").takeIf { file -> file.isFile },
                it.getExternalFilesDir(null)?.let { dir -> File(dir, "model.gguf") }?.takeIf { file -> file.isFile },
                it.getExternalFilesDir(null)?.let { dir -> File(dir, "model.bin") }?.takeIf { file -> file.isFile }
            )
        } ?: emptyList()
    }
) {
    suspend fun migrate(): List<ModelRecord> = withContext(Dispatchers.IO) {
        val candidates = legacyLocations()
        val existing = registry.load()
        val migrated = candidates.filterNot { candidate ->
            existing.models.any { model -> File(storage.rootDirectory, model.artifactPath).canonicalPath == candidate.canonicalPath }
        }.mapNotNull { candidate -> adopt(candidate, existing.models) }
        if (migrated.isNotEmpty()) {
            registry.save(existing.copy(models = existing.models + migrated))
        }
        migrated
    }

    private suspend fun adopt(source: File, existing: List<ModelRecord>): ModelRecord? {
        val detection = GgufModelDetector().detect(source)
        if (detection !is ModelDetection.Detected) return null
        val id = "legacy-${source.nameWithoutExtension.lowercase()}-${source.length()}"
        if (existing.any { it.id == id }) return null
        val target = storage.artifactFile(id, source.name)
        val part = storage.partialArtifactFile(id, source.name)
        val result = source.inputStream().use { input -> transfer.copyToPart(input, part, source.length()) }
        if (result !is TransferResult.Completed) return null
        transfer.finalizePart(part, target)
        return ModelRecord(
            id = id,
            displayName = source.nameWithoutExtension,
            family = ModelMetadataField(confidence = MetadataConfidence.UNKNOWN),
            runtime = ModelRuntime.LLAMA_CPP,
            format = ModelFormat.GGUF,
            artifactPath = target.relativeTo(storage.rootDirectory).path,
            artifactFileName = target.name,
            sizeBytes = target.length(),
            source = ModelSource.LEGACY_MIGRATION,
            sha256 = result.sha256,
            importedAtEpochMs = System.currentTimeMillis()
        )
    }
}
