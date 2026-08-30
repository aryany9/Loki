package dev.loki.android.core.models

import java.io.File
import java.io.InputStream

class ModelDownloader(
    private val storage: ModelStorage,
    private val transfer: ModelTransfer = ModelTransfer()
) {
    suspend fun downloadArtifact(
        modelId: String,
        artifact: ModelArtifact,
        input: InputStream,
        onProgress: (suspend (bytesCopied: Long, totalBytes: Long?) -> Unit)? = null
    ): DownloadResult {
        val part = storage.partialArtifactFile(modelId, artifact.relativePath)
        val final = storage.artifactFile(modelId, artifact.relativePath)

        return try {
            when (val result = transfer.copyToPart(
                input = input,
                destination = part,
                expectedSizeBytes = artifact.sizeBytes,
                expectedSha256 = artifact.sha256,
                onProgress = onProgress
            )) {
                is TransferResult.Rejected -> DownloadResult.Failed(result.reason)
                is TransferResult.Completed -> {
                    transfer.finalizePart(part, final)
                    DownloadResult.Completed(final, result.sha256)
                }
            }
        } catch (e: Exception) {
            part.delete()
            DownloadResult.Failed(e.message ?: "Download failed")
        }
    }
}

sealed interface DownloadResult {
    data class Completed(val file: File, val sha256: String) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}
