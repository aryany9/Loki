package dev.loki.android.core.voice.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * AudioRecorder captures 16kHz mono 16-bit PCM audio from the microphone
 * with integrated energy-based Voice Activity Detection (VAD).
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val silenceThresholdRms: Float = 300f,
    private val silenceDurationMs: Long = 400L,
    private val maxRecordingMs: Long = 8000L
) {
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false

    @SuppressLint("MissingPermission")
    suspend fun recordUtterance(
        onRmsUpdate: ((Float) -> Unit)? = null
    ): FloatArray = withContext(Dispatchers.IO) {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, sampleRate / 10) // 100ms chunks

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return@withContext FloatArray(0)
        }

        val outputStream = ByteArrayOutputStream()
        val buffer = ShortArray(bufferSize / 2)

        record.startRecording()
        isRecording = true
        Log.i(TAG, "Audio recording started")

        val startTime = System.currentTimeMillis()
        var speechDetected = false
        var lastSpeechTime = startTime

        try {
            while (isActive && isRecording) {
                val readCount = record.read(buffer, 0, buffer.size)
                if (readCount > 0) {
                    for (i in 0 until readCount) {
                        val s = buffer[i]
                        outputStream.write(s.toInt() and 0xFF)
                        outputStream.write((s.toInt() shr 8) and 0xFF)
                    }

                    var sum = 0.0
                    for (i in 0 until readCount) {
                        sum += buffer[i].toDouble() * buffer[i].toDouble()
                    }
                    val rms = sqrt(sum / readCount).toFloat()
                    onRmsUpdate?.invoke(rms)

                    val now = System.currentTimeMillis()
                    if (rms > silenceThresholdRms) {
                        speechDetected = true
                        lastSpeechTime = now
                    }

                    if (speechDetected && (now - lastSpeechTime > silenceDurationMs)) {
                        Log.i(TAG, "VAD silence threshold reached after ${now - startTime}ms")
                        break
                    }

                    if (now - startTime > maxRecordingMs) {
                        Log.i(TAG, "Max recording duration reached (${maxRecordingMs}ms)")
                        break
                    }
                }
            }
        } finally {
            try {
                record.stop()
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            }
            audioRecord = null
            isRecording = false
            Log.i(TAG, "Audio recording stopped, captured ${outputStream.size()} bytes")
        }

        val rawBytes = outputStream.toByteArray()
        val numShorts = rawBytes.size / 2
        val floatArray = FloatArray(numShorts)

        for (i in 0 until numShorts) {
            val low = rawBytes[i * 2].toInt() and 0xFF
            val high = rawBytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            floatArray[i] = sample / 32768.0f
        }

        return@withContext floatArray
    }

    fun stop() {
        isRecording = false
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
