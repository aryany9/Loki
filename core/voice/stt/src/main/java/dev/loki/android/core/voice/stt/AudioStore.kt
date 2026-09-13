package dev.loki.android.core.voice.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AudioStore(private val context: Context) {
    val audioDir: File
        get() = File(context.filesDir, "audio_records")

    suspend fun saveAudio(fileName: String, wavBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            if (!audioDir.exists()) audioDir.mkdirs()
            val file = File(audioDir, fileName)
            file.writeBytes(wavBytes)
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
