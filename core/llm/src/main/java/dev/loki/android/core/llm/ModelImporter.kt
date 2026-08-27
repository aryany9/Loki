package dev.loki.android.core.llm

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelImporter(
    private val context: Context,
    private val storage: ModelStorage,
    private val transfer: ModelTransfer = ModelTransfer()
) {
    suspend fun copyFromUri(
        modelId: String,
        uri: Uri,
        fileName: String,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        onProgress: (suspend (Long, Long?) -> Unit)? = null
    ): TransferResult = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext TransferResult.Rejected("Unable to open selected model")
        input.use {
            transfer.copyToPart(
                input = it,
                destination = storage.partialArtifactFile(modelId, fileName),
                expectedSizeBytes = expectedSizeBytes,
                expectedSha256 = expectedSha256,
                onProgress = onProgress
            )
        }
    }

    fun finalize(modelId: String, fileName: String): File {
        val part = storage.partialArtifactFile(modelId, fileName)
        val target = storage.artifactFile(modelId, fileName)
        transfer.finalizePart(part, target)
        return target
    }
}
