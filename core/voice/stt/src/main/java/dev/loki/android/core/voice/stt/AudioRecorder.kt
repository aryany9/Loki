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

internal interface AudioSourceReader {
    fun startRecording()
    fun read(buffer: ShortArray, offset: Int, size: Int): Int
    fun stop()
    fun release()
    val isInitialized: Boolean
}

internal class AndroidAudioRecordReader(
    private val sampleRate: Int,
    private val bufferSize: Int
) : AudioSourceReader {
    private var record: AudioRecord? = null

    override val isInitialized: Boolean
        get() = record?.state == AudioRecord.STATE_INITIALIZED

    @SuppressLint("MissingPermission")
    override fun startRecording() {
        if (record == null) {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )
        }
        record?.startRecording()
    }

    override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
        return record?.read(buffer, offset, size) ?: -1
    }

    override fun stop() {
        try {
            record?.stop()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping AudioRecord", e)
        }
    }

    override fun release() {
        try {
            record?.release()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error releasing AudioRecord", e)
        }
        record = null
    }
}

/**
 * AudioRecorder captures 16kHz mono 16-bit PCM audio from the microphone
 * with integrated energy-based Voice Activity Detection (VAD).
 * Supports continuous armed capture with a TTS-gated commit window.
 */
open class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val silenceDurationMs: Long = 700L,
    private val initialSilenceTimeoutMs: Long = 4500L,
    private val maxRecordingMs: Long = 30000L,
    private val lookbackMs: Long = 150L,
    val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) {
    internal var customSourceReader: AudioSourceReader? = null
    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    internal constructor(
        sampleRate: Int = 16000,
        silenceDurationMs: Long = 700L,
        initialSilenceTimeoutMs: Long = 4500L,
        maxRecordingMs: Long = 30000L,
        lookbackMs: Long = 150L,
        customSourceReader: AudioSourceReader?,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    ) : this(sampleRate, silenceDurationMs, initialSilenceTimeoutMs, maxRecordingMs, lookbackMs, ioDispatcher) {
        this.customSourceReader = customSourceReader
    }

    private var sourceReader: AudioSourceReader? = null
    @Volatile private var isRecording = false
    @Volatile private var isArmed = false

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = try {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    } catch (e: Throwable) {
        sampleRate / 10
    }
    private val bufferSize = maxOf(minBufferSize, sampleRate / 10) // 100ms chunks

    @Synchronized
    open fun arm(): Boolean {
        if (isArmed) return true
        val reader = customSourceReader ?: AndroidAudioRecordReader(sampleRate, bufferSize)
        sourceReader = reader
        reader.startRecording()
        if (!reader.isInitialized && customSourceReader == null) {
            Log.e(TAG, "AudioRecord initialization failed")
            return false
        }
        isArmed = true
        isRecording = true
        Log.i(TAG, "Audio recorder armed")
        return true
    }

    open fun release() {
        isRecording = false
        isArmed = false
        sourceReader?.stop()
        sourceReader?.release()
        sourceReader = null
        Log.i(TAG, "Audio recorder released")
    }

    open fun stop() {
        isRecording = false
    }

    open suspend fun recordUtterance(
        onRmsUpdate: ((Float) -> Unit)? = null
    ): FloatArray = recordGatedUtterance(isCommitGated = { false }, onRmsUpdate = onRmsUpdate)

    open suspend fun recordGatedUtterance(
        isCommitGated: () -> Boolean,
        onRmsUpdate: ((Float) -> Unit)? = null
    ): FloatArray = withContext(ioDispatcher) {
        val wasArmed = isArmed
        if (!wasArmed) {
            if (!arm()) {
                return@withContext FloatArray(0)
            }
        }

        val reader = sourceReader ?: return@withContext FloatArray(0)
        isRecording = true

        val outputStream = ByteArrayOutputStream()
        val buffer = ShortArray(bufferSize / 2)

        // Rolling lookback buffer (default 150 ms, between 120 and 180 ms)
        val lookbackSamples = ((sampleRate * lookbackMs) / 1000).toInt()
        val lookbackBuffer = ShortArray(lookbackSamples)
        var lookbackWritePos = 0
        var lookbackCount = 0

        var commitWindowOpen = !isCommitGated()
        var startTime = timeProvider()
        var speechDetected = false
        var lastSpeechTime = startTime
        var peakRms = 0f
        var noiseFloor = 100f
        var chunksProcessed = 0
        var lastLogTime = 0L

        try {
            while (isActive && isRecording) {
                val readCount = reader.read(buffer, 0, buffer.size)
                if (readCount <= 0) {
                    break
                }

                // Transition from gated to commit mode
                val currentlyGated = isCommitGated()
                if (!commitWindowOpen && !currentlyGated) {
                    commitWindowOpen = true
                    // Dump the rolling lookback buffer
                    if (lookbackCount > 0) {
                        val startIdx = if (lookbackCount == lookbackSamples) lookbackWritePos else 0
                        for (i in 0 until lookbackCount) {
                            val idx = (startIdx + i) % lookbackSamples
                            val s = lookbackBuffer[idx]
                            outputStream.write(s.toInt() and 0xFF)
                            outputStream.write((s.toInt() shr 8) and 0xFF)
                        }
                        lookbackCount = 0
                        lookbackWritePos = 0
                    }
                    startTime = timeProvider()
                    lastSpeechTime = startTime
                    speechDetected = false
                    peakRms = 0f
                    noiseFloor = 100f
                    chunksProcessed = 0
                    lastLogTime = 0L
                }

                if (!commitWindowOpen) {
                    // While gated, do not commit to outputStream. Keep rolling lookback.
                    for (i in 0 until readCount) {
                        lookbackBuffer[lookbackWritePos] = buffer[i]
                        lookbackWritePos = (lookbackWritePos + 1) % lookbackSamples
                        if (lookbackCount < lookbackSamples) lookbackCount++
                    }
                    continue
                }

                // Commit mode: write to outputStream and run VAD
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

                val now = timeProvider()

                val speechThreshold = maxOf(noiseFloor * 2.2f, 500f)
                val silenceThreshold = maxOf(peakRms * 0.20f, noiseFloor * 1.5f, 250f)

                if (!speechDetected) {
                    if (rms > speechThreshold) {
                        speechDetected = true
                        peakRms = rms
                        lastSpeechTime = now
                    }
                } else {
                    peakRms = maxOf(peakRms, rms)
                    if (rms >= silenceThreshold) {
                        lastSpeechTime = now
                    }
                }

                if (rms < silenceThreshold || (!speechDetected && rms <= speechThreshold)) {
                    if (chunksProcessed <= 3) {
                        noiseFloor = maxOf(noiseFloor, rms)
                    } else {
                        noiseFloor = noiseFloor * 0.9f + rms * 0.1f
                    }
                    if (peakRms > 0f) {
                        noiseFloor = minOf(noiseFloor, peakRms * 0.5f)
                    }
                }

                val silentFor = if (speechDetected) now - lastSpeechTime else 0L

                if (now - lastLogTime >= 500L) {
                    lastLogTime = now
                    Log.d(
                        TAG,
                        String.format(
                            java.util.Locale.US,
                            "VAD rms=%.0f floor=%.0f speechTh=%.0f silenceTh=%.0f speechDetected=%b silentFor=%dms",
                            rms, noiseFloor, speechThreshold, silenceThreshold, speechDetected, silentFor
                        )
                    )
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
        } finally {
            if (!wasArmed) {
                release()
            }
            Log.i(TAG, "Audio capture finished, captured ${outputStream.size()} bytes (speechDetected=$speechDetected)")
        }

        if (!speechDetected) {
            Log.i(TAG, "No speech detected during utterance recording, returning empty audio")
            return@withContext FloatArray(0)
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

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
