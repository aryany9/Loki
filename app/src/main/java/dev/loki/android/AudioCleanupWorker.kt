package dev.loki.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

class AudioCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val audioDir = File(applicationContext.filesDir, "loki_conversations/audio_records")
            if (audioDir.exists()) {
                val ttlMillis = TimeUnit.HOURS.toMillis(48)
                val cutoffTime = System.currentTimeMillis() - ttlMillis
                
                audioDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoffTime) {
                        file.delete()
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
