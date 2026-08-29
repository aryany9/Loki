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
    private val silenceDurationMs: Long = 700L,
    private val initialSilenceTimeoutMs: Long = 4500L,
    private val maxRecordingMs: Long = 30000L
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
        var noiseFloor = 100f
        var chunksProcessed = 0

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
                    chunksProcessed++

                    val now = System.currentTimeMillis()

                    if (chunksProcessed <= 3) {
                        noiseFloor = maxOf(noiseFloor, rms)
                    } else if (!speechDetected) {
                        // Slowly adapt background noise floor
                        noiseFloor = noiseFloor * 0.9f + rms * 0.1f
                    }

                    val speechThreshold = maxOf(noiseFloor * 2.2f, 500f)
                    val silenceThreshold = maxOf(noiseFloor * 1.3f, 300f)

                    if (rms > speechThreshold) {
                        speechDetected = true
                        lastSpeechTime = now
                    } else if (speechDetected && rms > silenceThreshold) {
                        lastSpeechTime = now
                    }

                    if (speechDetected && (now - lastSpeechTime > silenceDurationMs)) {
                        Log.i(TAG, "Speech completion detected (silence duration reached: ${now - lastSpeechTime}ms, total: ${now - startTime}ms)")
                        break
                    }

                    if (!speechDetected && (now - startTime > initialSilenceTimeoutMs)) {
                        Log.i(TAG, "Initial silence timeout reached (${initialSilenceTimeoutMs}ms)")
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
