package dev.loki.android.core.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Global kill-switch for voice start audio cues.
 */
var audioStartCueEnabled: Boolean = true

/**
 * Synthesizes and plays a short (~250 ms) rising frequency glide (440 Hz -> 880 Hz)
 * as an attention cue when voice recording/listening begins.
 */
object AudioCue {
    private const val TAG = "AudioCue"
    private const val DURATION_MS = 250
    private const val START_FREQ = 440.0
    private const val END_FREQ = 880.0

    fun playStartTone() {
        if (!audioStartCueEnabled) return

        try {
            thread(name = "AudioCue-PlayStartTone", isDaemon = true) {
                try {
                    val sampleRate = try {
                        val nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                        if (nativeRate > 0) nativeRate else 44100
                    } catch (_: Throwable) {
                        44100
                    }

                    val numSamples = (sampleRate * DURATION_MS) / 1000
                    val audioData = ShortArray(numSamples)

                    val attackSamples = (sampleRate * 0.02).toInt().coerceAtLeast(1)
                    val releaseSamples = (sampleRate * 0.04).toInt().coerceAtLeast(1)

                    var phase = 0.0
                    for (i in 0 until numSamples) {
                        val progress = i.toDouble() / numSamples
                        val freq = START_FREQ + progress * (END_FREQ - START_FREQ)
                        val deltaPhase = 2.0 * PI * freq / sampleRate
                        phase += deltaPhase
                        if (phase >= 2.0 * PI) {
                            phase -= 2.0 * PI
                        }

                        val envelope = when {
                            i < attackSamples -> i.toDouble() / attackSamples
                            i > numSamples - releaseSamples -> (numSamples - i).toDouble() / releaseSamples
                            else -> 1.0
                        }

                        val sample = sin(phase) * envelope * Short.MAX_VALUE * 0.7
                        audioData[i] = sample.toInt().toShort()
                    }

                    val bufferSize = audioData.size * 2
                    val audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    audioTrack.write(audioData, 0, audioData.size)
                    audioTrack.play()

                    Thread.sleep(DURATION_MS.toLong() + 50L)
                    audioTrack.stop()
                    audioTrack.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to synthesize/play audio start cue", e)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to spawn audio cue thread", e)
        }
    }
}
