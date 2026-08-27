package dev.loki.android.core.llm

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModelCatalog(
    val schemaVersion: Int = 1,
    val models: List<ModelCatalogEntry> = emptyList()
)

class ModelCatalogRepository(
    private val json: Json = Json { ignoreUnknownKeys = false }
) {
    suspend fun load(remoteUrl: String?, bundled: ModelCatalog): ModelCatalog = withContext(Dispatchers.IO) {
        if (remoteUrl == null) return@withContext bundled
        try {
            val connection = URL(remoteUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.inputStream.use { input ->
                val remote = json.decodeFromString<ModelCatalog>(input.bufferedReader().readText())
                require(remote.schemaVersion == bundled.schemaVersion) { "Unsupported catalog schema" }
                remote
            }
        } catch (_: Exception) {
            bundled
        }
    }
}

class ModelDownloader(
    private val storage: ModelStorage,
    private val transfer: ModelTransfer = ModelTransfer(),
    private val validatorFor: ((ModelCatalogEntry) -> ModelValidator)? = null
) {
    suspend fun download(
        entry: ModelCatalogEntry,
        input: java.io.InputStream,
        onProgress: (suspend (bytesCopied: Long, totalBytes: Long?) -> Unit)? = null
    ): DownloadResult {
        val part = storage.partialArtifactFile(entry.id, entry.fileName())
        val final = storage.artifactFile(entry.id, entry.fileName())
        return try {
            when (val transferResult = transfer.copyToPart(
                input = input,
                destination = part,
                expectedSizeBytes = entry.expectedSizeBytes,
                expectedSha256 = entry.sha256,
                onProgress = onProgress
            )) {
                is TransferResult.Rejected -> DownloadResult.Failed(transferResult.reason)
                is TransferResult.Completed -> {
                    transfer.finalizePart(part, final)
                    val validator = validatorFor?.invoke(entry)
                    if (validator != null && validator.validate(final) !is ValidationResult.Valid) {
                        final.delete()
                        final.parentFile?.deleteRecursively()
                        DownloadResult.Failed("Downloaded model failed runtime validation")
                    } else {
                        DownloadResult.Completed(final, transferResult.sha256)
                    }
                }
            }
        } catch (error: Exception) {
            part.delete()
            DownloadResult.Failed(error.message ?: "Download failed")
        }
    }

    private fun ModelCatalogEntry.fileName(): String = artifactUrl.substringAfterLast('/').substringBefore('?')
        .ifBlank { "$id.model" }
}

sealed interface DownloadResult {
    data class Completed(val file: java.io.File, val sha256: String) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}
