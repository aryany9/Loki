package dev.loki.android.voice

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
 * AudioRecorder captures 16kHz 16-bit mono audio with energy-based VAD.
 */
class AudioRecorder {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
        sampleRate * 2 // 1 second buffer
    )

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    interface VadListener {
        fun onSpeechStart()
        fun onSpeechEnd(audioSamples: FloatArray)
        fun onRmsChanged(rms: Float)
    }

    @SuppressLint("MissingPermission")
    suspend fun startListeningWithVad(
        silenceThresholdRms: Float = 0.015f,
        silenceDurationMs: Long = 400L,
        maxRecordingDurationMs: Long = 8000L,
        listener: VadListener
    ) = withContext(Dispatchers.IO) {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return@withContext
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "Audio recording started with VAD (silenceThreshold=$silenceThresholdRms, silenceDuration=${silenceDurationMs}ms)")

            val shortBuffer = ShortArray(1600) // 100ms chunks at 16kHz
            val recordedBytes = ByteArrayOutputStream()

            var speechDetected = false
            var silenceStartTime = 0L
            val recordingStartTime = System.currentTimeMillis()

            while (isActive && isRecording) {
                val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                if (readCount <= 0) continue

                // Append bytes to stream
                for (i in 0 until readCount) {
                    val s = shortBuffer[i].toInt()
                    recordedBytes.write(s and 0xFF)
                    recordedBytes.write((s shr 8) and 0xFF)
                }

                // Compute RMS energy of chunk
                var sumSquares = 0.0
                for (i in 0 until readCount) {
                    val normalized = shortBuffer[i].toFloat() / 32768.0f
                    sumSquares += (normalized * normalized)
                }
                val rms = sqrt(sumSquares / readCount).toFloat()
                listener.onRmsChanged(rms)

                val currentTime = System.currentTimeMillis()

                if (rms > silenceThresholdRms) {
                    if (!speechDetected) {
                        speechDetected = true
                        Log.i(TAG, "VAD: Speech start detected (RMS: $rms)")
                        listener.onSpeechStart()
                    }
                    silenceStartTime = 0L
                } else {
                    if (speechDetected) {
                        if (silenceStartTime == 0L) {
                            silenceStartTime = currentTime
                        } else if (currentTime - silenceStartTime >= silenceDurationMs) {
                            Log.i(TAG, "VAD: Speech end detected after ${silenceDurationMs}ms silence")
                            break
                        }
                    }
                }

                if (currentTime - recordingStartTime >= maxRecordingDurationMs) {
                    Log.i(TAG, "Max recording duration (${maxRecordingDurationMs}ms) reached")
                    break
                }
            }

            stop()

            // Convert recorded bytes to FloatArray
            val pcmBytes = recordedBytes.toByteArray()
            val numSamples = pcmBytes.size / 2
            val floatSamples = FloatArray(numSamples)
            for (i in 0 until numSamples) {
                val low = pcmBytes[i * 2].toInt() and 0xFF
                val high = pcmBytes[i * 2 + 1].toInt()
                val sampleShort = (high shl 8) or low
                floatSamples[i] = sampleShort.toFloat() / 32768.0f
            }

            Log.i(TAG, "Audio capture finished with ${floatSamples.size} samples (%.2f s)".format(floatSamples.size / 16000.0f))
            listener.onSpeechEnd(floatSamples)

        } catch (e: Exception) {
            Log.e(TAG, "Audio recording failed", e)
            stop()
        }
    }

    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio recorder", e)
        } finally {
            audioRecord = null
        }
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
