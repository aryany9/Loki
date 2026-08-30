package dev.loki.android.core.models

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class ModelTransfer {
    suspend fun copyToPart(
        input: InputStream,
        destination: File,
        expectedSizeBytes: Long? = null,
        expectedSha256: String? = null,
        onProgress: (suspend (bytesCopied: Long, totalBytes: Long?) -> Unit)? = null
    ): TransferResult {
        destination.parentFile?.mkdirs()
        var copied = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            destination.outputStream().use { output ->
                copy(input, output) { buffer, count ->
                    digest.update(buffer, 0, count)
                    copied += count
                    coroutineContext.ensureActive()
                    onProgress?.invoke(copied, expectedSizeBytes)
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }

        val actualSha256 = digest.digest().toHex()
        if (expectedSizeBytes != null && expectedSizeBytes != copied) {
            destination.delete()
            return TransferResult.Rejected("Expected $expectedSizeBytes bytes but copied $copied bytes")
        }
        if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
            destination.delete()
            return TransferResult.Rejected("SHA-256 checksum does not match")
        }
        return TransferResult.Completed(copied, actualSha256)
    }

    fun finalizePart(partFile: File, finalFile: File) {
        require(partFile.isFile) { "Temporary model artifact does not exist: ${partFile.absolutePath}" }
        finalFile.parentFile?.mkdirs()
        if (finalFile.exists() && !finalFile.delete()) {
            throw IllegalStateException("Unable to replace model artifact: ${finalFile.absolutePath}")
        }
        if (!partFile.renameTo(finalFile)) {
            throw IllegalStateException("Unable to finalize model artifact: ${finalFile.absolutePath}")
        }
    }

    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        onChunk: suspend (ByteArray, Int) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            onChunk(buffer, count)
        }
        output.flush()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024

        fun calculateSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

sealed interface TransferResult {
    data class Completed(val bytesCopied: Long, val sha256: String) : TransferResult
    data class Rejected(val reason: String) : TransferResult
}
