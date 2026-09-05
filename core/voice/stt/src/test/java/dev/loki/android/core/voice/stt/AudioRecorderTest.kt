package dev.loki.android.core.voice.stt

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioRecorderTest {

    private class FakeAudioSourceReader(
        private val chunks: List<ShortArray>,
        private val onChunkRead: (() -> Unit)? = null
    ) : AudioSourceReader {
        var chunkIndex = 0
        override val isInitialized: Boolean = true

        override fun startRecording() {}

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            if (chunkIndex >= chunks.size) {
                return -1
            }
            val currentChunk = chunks[chunkIndex++]
            val toCopy = minOf(size, currentChunk.size)
            System.arraycopy(currentChunk, 0, buffer, offset, toCopy)
            onChunkRead?.invoke()
            return toCopy
        }

        override fun stop() {}
        override fun release() {}
    }

    @Test
    fun `TTS-tail audio captured while gated is not committed`() = runTest {
        val ttsChunk = ShortArray(800) { 20000 }
        val speechChunk = ShortArray(800) { 15000 }
        val silenceChunk = ShortArray(800) { 0 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(3) { add(ttsChunk) }
            repeat(8) { add(speechChunk) }
            repeat(15) { add(silenceChunk) }
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        var isGated = true
        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 200L,
            lookbackMs = 50L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        var readCount = 0
        val audioFloats = recorder.recordGatedUtterance(
            isCommitGated = {
                readCount++
                if (readCount > 3) {
                    isGated = false
                }
                isGated
            }
        )

        assertTrue(audioFloats.isNotEmpty())
        assertTrue(audioFloats.size < 800 * chunks.size)
    }

    @Test
    fun `promptly spoken reply after TTS onDone is captured`() = runTest {
        val speechChunk = ShortArray(800) { 12000 }
        val silenceChunk = ShortArray(800) { 0 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(speechChunk) }
            repeat(15) { add(silenceChunk) }
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 200L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(
            isCommitGated = { false }
        )

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        val maxAmp = audioFloats.maxOfOrNull { abs(it) } ?: 0f
        assertTrue("Speech amplitude should be preserved (> 0.2)", maxAmp > 0.2f)
    }

    @Test
    fun `arm and release maintains single active reader`() {
        var startCount = 0
        var releaseCount = 0
        val fakeReader = object : AudioSourceReader {
            override val isInitialized: Boolean = true
            override fun startRecording() { startCount++ }
            override fun read(buffer: ShortArray, offset: Int, size: Int): Int = size
            override fun stop() {}
            override fun release() { releaseCount++ }
        }

        val recorder = AudioRecorder(customSourceReader = fakeReader)
        assertTrue(recorder.arm())
        // Second arm is a no-op
        assertTrue(recorder.arm())
        assertEquals(1, startCount)

        recorder.release()
        assertEquals(1, releaseCount)
    }

    @Test
    fun `sustained speech then silence ends capture after silenceDurationMs`() = runTest {
        val speechChunk = ShortArray(800) { 3000 }
        val silenceChunk = ShortArray(800) { 0 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(speechChunk) } // 800ms speech
            repeat(20) { add(silenceChunk) }
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 700L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 speech chunks (800ms) + 8 silence chunks (800ms > 700ms silenceDurationMs) = 16 chunks read
        assertEquals(16, fakeReader.chunkIndex)
    }

    @Test
    fun `intermittent noise bursts below end-of-speech level do not reset quiet window`() = runTest {
        val speechChunk = ShortArray(800) { 3000 }
        val lowNoiseChunk = ShortArray(800) { 50 }
        val noiseBurst1 = ShortArray(800) { 200 }
        val noiseBurst2 = ShortArray(800) { 200 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(speechChunk) } // 800ms speech
            add(lowNoiseChunk)
            add(lowNoiseChunk)
            add(noiseBurst1)
            add(lowNoiseChunk)
            add(noiseBurst2)
            add(lowNoiseChunk)
            add(lowNoiseChunk)
            add(lowNoiseChunk)
            repeat(10) { add(lowNoiseChunk) } // unread tail
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 700L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 speech + 8 quiet/noise bursts = 16 chunks read (800ms silence > 700ms)
        assertEquals(16, fakeReader.chunkIndex)
    }

    @Test
    fun `speech resuming after brief pause does not end capture`() = runTest {
        val speechChunk1 = ShortArray(800) { 3000 }
        val pauseChunk = ShortArray(800) { 50 } // 300ms pause < 700ms
        val speechChunk2 = ShortArray(800) { 3500 }
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(speechChunk1) } // 800ms speech
            repeat(3) { add(pauseChunk) }    // 300ms pause
            repeat(4) { add(speechChunk2) }  // 400ms speech
            repeat(8) { add(silenceChunk) }  // 800ms silence > 700ms
            repeat(10) { add(silenceChunk) } // unread tail
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 700L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 speech + 3 pause + 4 speech + 8 silence = 23 chunks read
        assertEquals(23, fakeReader.chunkIndex)
    }

    @Test
    fun `quiet speech terminates after silenceDurationMs`() = runTest {
        val quietSpeechChunk = ShortArray(800) { 1000 } // rms = 1000 > 800 onset threshold
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(quietSpeechChunk) }
            repeat(8) { add(silenceChunk) }
            repeat(10) { add(silenceChunk) } // unread tail
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 700L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 quiet speech + 8 silence = 16 chunks read
        assertEquals(16, fakeReader.chunkIndex)
    }

    @Test
    fun `high-ambient device with 800 RMS silence and speech bursts ends capture 700-900 ms after speech`() = runTest {
        val ambientSilenceChunk = ShortArray(800) { 800 } // RMS = 800 on high-gain device
        val speechChunk = ShortArray(800) { 5000 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(4) { add(ambientSilenceChunk) } // ambient before speech (calibrates noise floor)
            repeat(8) { add(speechChunk) }         // 800ms speech
            repeat(8) { add(ambientSilenceChunk) } // 800ms silence (> 700ms silenceDurationMs)
            repeat(10) { add(ambientSilenceChunk) } // unread tail
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 700L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 4 ambient + 8 speech + 8 ambient silence = 20 chunks read
        assertEquals(20, fakeReader.chunkIndex)
    }

    @Test
    fun `soft trailing word after a loud word does NOT end the utterance`() = runTest {
        val loudSpeechChunk = ShortArray(800) { 5000 }   // RMS 5000 loud speech
        val softSpeechChunk = ShortArray(800) { 700 }    // RMS 700 soft speech continuation
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(loudSpeechChunk) }  // 800ms loud speech
            repeat(4) { add(softSpeechChunk) }  // 400ms soft speech continuation
            repeat(15) { add(silenceChunk) }    // 1500ms silence
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 1200L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 loud + 4 soft + 13 silence (1300ms > 1200ms) = 25 chunks read
        assertEquals(25, fakeReader.chunkIndex)
    }

    @Test
    fun `700ms pause inside a sentence does NOT end it with default 1200ms window`() = runTest {
        val speechChunk1 = ShortArray(800) { 3000 }
        val pauseChunk = ShortArray(800) { 50 } // 700ms pause
        val speechChunk2 = ShortArray(800) { 3000 }
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(speechChunk1) } // 800ms speech
            repeat(7) { add(pauseChunk) }   // 700ms pause (< 1200ms)
            repeat(8) { add(speechChunk2) } // 800ms speech 2
            repeat(15) { add(silenceChunk) } // silence to close
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            // default silenceDurationMs = 1200L
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 speech + 7 pause + 8 speech + 13 silence = 36 chunks read
        assertEquals(36, fakeReader.chunkIndex)
    }

    @Test
    fun `1200ms pause ends capture with default silenceDurationMs`() = runTest {
        val speechChunk = ShortArray(800) { 3000 }
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(speechChunk) } // 800ms speech
            repeat(20) { add(silenceChunk) }
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 speech + 13 silence (1300ms > 1200ms) = 21 chunks read
        assertEquals(21, fakeReader.chunkIndex)
    }

    @Test
    fun `single noise burst does not trigger onset`() = runTest {
        val ambientChunk = ShortArray(800) { 100 }
        val noiseBurst = ShortArray(800) { 3000 } // 1 chunk noise spike (100ms < 250ms sustained onset requirement)

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(4) { add(ambientChunk) } // 400ms ambient
            add(noiseBurst)                 // 100ms noise burst
            repeat(50) { add(ambientChunk) } // ambient continuation until initial silence timeout
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            initialSilenceTimeoutMs = 3000L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Single noise burst must not trigger speech onset; should return empty audio", audioFloats.isEmpty())
    }

    @Test
    fun `sub-minimum utterance returns empty audio`() = runTest {
        val speechChunk = ShortArray(800) { 3000 }
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(2) { add(speechChunk) }   // 200ms speech (< 350ms min utterance threshold)
            repeat(15) { add(silenceChunk) }
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 700L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Sub-minimum utterance (< 350ms) must return empty audio", audioFloats.isEmpty())
    }

    @Test
    fun `400ms in-band utterance passes minimum utterance filter`() = runTest {
        val speechChunk = ShortArray(800) { 3000 }
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(4) { add(speechChunk) }   // 400ms speech (>= 350ms min utterance threshold)
            repeat(15) { add(silenceChunk) }
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 700L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("400ms utterance (>= 350ms) must return non-empty audio", audioFloats.isNotEmpty())
    }

    @Test
    fun `1-2 ambient bursts above continuation gate do NOT reset silence countdown`() = runTest {
        val speechChunk = ShortArray(800) { 3000 }
        val silenceChunk = ShortArray(800) { 50 }
        val noiseSpike = ShortArray(800) { 2000 } // high amplitude spike (above noiseFloor * 1.6f)

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(speechChunk) }   // 800ms speech
            repeat(3) { add(silenceChunk) }  // 300ms silence
            add(noiseSpike)                  // 100ms noise spike (1 chunk < 3 chunks / 250ms continuation gate)
            add(noiseSpike)                  // 100ms noise spike (2 chunks < 3 chunks continuation gate)
            repeat(10) { add(silenceChunk) } // 1000ms silence (total silence = 300 + 1000 = 1300ms > 1200ms)
            repeat(20) { add(silenceChunk) } // unread tail
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 1200L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 speech + 3 silence + 2 noise + 8 silence = 21 chunks read (1300ms > 1200ms silenceDurationMs, stopped promptly)
        assertEquals(21, fakeReader.chunkIndex)
    }

    @Test
    fun `3 consecutive in-band speech chunks DO reset silence countdown and sustain capture`() = runTest {
        val speechChunk = ShortArray(800) { 3000 }
        val pauseChunk = ShortArray(800) { 50 }
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(8) { add(speechChunk) }   // 800ms initial speech
            repeat(4) { add(pauseChunk) }    // 400ms pause (< 1200ms)
            repeat(3) { add(speechChunk) }   // 300ms speech continuation (3 consecutive chunks satisfy continuation gate)
            repeat(13) { add(silenceChunk) } // 1300ms silence (> 1200ms)
            repeat(20) { add(silenceChunk) } // unread tail
        }

        var currentTime = 1_000_000L
        val fakeReader = FakeAudioSourceReader(
            chunks = chunks,
            onChunkRead = { currentTime += 100L }
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 1200L,
            customSourceReader = fakeReader
        ).apply {
            timeProvider = { currentTime }
        }

        val audioFloats = recorder.recordGatedUtterance(isCommitGated = { false })

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 8 speech + 4 pause + 3 speech + 13 silence = 28 chunks read
        assertEquals(28, fakeReader.chunkIndex)
    }

    @Test
    fun `uninitialized audio record reader throws typed MicUnavailableException`() = runTest {
        val uninitReader = object : AudioSourceReader {
            override val isInitialized: Boolean = false
            override fun startRecording() {
                throw MicUnavailableException(MicUnavailableReason.INIT_FAILED, "AudioRecord uninitialized")
            }
            override fun read(buffer: ShortArray, offset: Int, size: Int): Int = -1
            override fun stop() {}
            override fun release() {}
        }

        val recorder = AudioRecorder(customSourceReader = uninitReader)
        try {
            recorder.arm()
            org.junit.Assert.fail("Expected MicUnavailableException")
        } catch (e: MicUnavailableException) {
            assertEquals(MicUnavailableReason.INIT_FAILED, e.reason)
        }
    }

    @Test
    fun `recordGatedUtterance returns empty array on MicUnavailableException without throwing`() = runTest {
        val permissionDeniedReader = object : AudioSourceReader {
            override val isInitialized: Boolean = false
            override fun startRecording() {
                throw MicUnavailableException(MicUnavailableReason.PERMISSION_DENIED, "Permission denied")
            }
            override fun read(buffer: ShortArray, offset: Int, size: Int): Int = -1
            override fun stop() {}
            override fun release() {}
        }

        val recorder = AudioRecorder(customSourceReader = permissionDeniedReader)
        val result = recorder.recordGatedUtterance(isCommitGated = { false })
        assertEquals(0, result.size)
    }
}
