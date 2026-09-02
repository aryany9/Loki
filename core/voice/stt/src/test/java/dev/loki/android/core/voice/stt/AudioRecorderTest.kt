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
        // Sample rate 16000, 100ms chunk = 1600 samples
        // Create 5 chunks:
        // Chunk 0, 1, 2: High amplitude TTS tail (e.g. 20000) while gated
        // Chunk 3: Speech reply (10000) after un-gated
        // Chunk 4: Silence (0) to trigger VAD
        val ttsChunk = ShortArray(800) { 20000 }
        val speechChunk = ShortArray(800) { 15000 }
        val silenceChunk = ShortArray(800) { 0 }

        val fakeReader = FakeAudioSourceReader(
            listOf(
                ttsChunk,
                ttsChunk,
                ttsChunk,
                speechChunk,
                silenceChunk,
                silenceChunk,
                silenceChunk,
                silenceChunk,
                silenceChunk
            )
        )

        var isGated = true
        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 200L,
            lookbackMs = 50L, // small lookback for clear separation
            customSourceReader = fakeReader
        )

        // Gate will open after chunk 2 has been read
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
        // The first chunk (read while gated, outside lookback) should NOT be in the committed audio
        // The total number of committed samples should not include the early TTS chunks
        assertTrue(audioFloats.size < 800 * 9)
    }

    @Test
    fun `promptly spoken reply after TTS onDone is captured`() = runTest {
        val speechChunk = ShortArray(800) { 12000 }
        val silenceChunk = ShortArray(800) { 0 }

        val fakeReader = FakeAudioSourceReader(
            listOf(
                speechChunk,
                speechChunk,
                silenceChunk,
                silenceChunk,
                silenceChunk,
                silenceChunk
            )
        )

        val recorder = AudioRecorder(
            sampleRate = 16000,
            silenceDurationMs = 200L,
            customSourceReader = fakeReader
        )

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
            repeat(4) { add(speechChunk) }
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

        assertTrue("Expected non-empty audio capture", audioFloats.isNotEmpty())
        // 4 speech chunks (400ms) + 8 silence chunks (800ms > 700ms threshold) = 12 chunks read
        assertEquals(12, fakeReader.chunkIndex)
    }

    @Test
    fun `intermittent noise bursts below end-of-speech level do not reset quiet window`() = runTest {
        val speechChunk = ShortArray(800) { 3000 } // peakRms = 3000, silenceThreshold = 600
        val lowNoiseChunk = ShortArray(800) { 50 }
        val noiseBurst1 = ShortArray(800) { 400 } // below 600 end-of-speech level
        val noiseBurst2 = ShortArray(800) { 350 } // below 600 end-of-speech level

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(3) { add(speechChunk) }
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
        // 3 speech + 8 quiet/noise bursts = 11 chunks read (800ms silence > 700ms)
        assertEquals(11, fakeReader.chunkIndex)
    }

    @Test
    fun `speech resuming after brief pause does not end capture`() = runTest {
        val speechChunk1 = ShortArray(800) { 3000 }
        val pauseChunk = ShortArray(800) { 50 } // 300ms pause < 700ms
        val speechChunk2 = ShortArray(800) { 3500 }
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(2) { add(speechChunk1) }
            repeat(3) { add(pauseChunk) }
            repeat(2) { add(speechChunk2) }
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
        // 2 speech + 3 pause + 2 speech + 8 silence = 15 chunks read
        assertEquals(15, fakeReader.chunkIndex)
    }

    @Test
    fun `quiet speech terminates after silenceDurationMs`() = runTest {
        val quietSpeechChunk = ShortArray(800) { 550 } // rms = 550 > 500 threshold
        val silenceChunk = ShortArray(800) { 50 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(3) { add(quietSpeechChunk) }
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
        // 3 quiet speech + 8 silence = 11 chunks read
        assertEquals(11, fakeReader.chunkIndex)
    }

    @Test
    fun `high-ambient device with 800 RMS silence and speech bursts ends capture 700-900 ms after speech`() = runTest {
        val ambientSilenceChunk = ShortArray(800) { 800 } // RMS = 800 on high-gain device
        val speechChunk1 = ShortArray(800) { 4000 }
        val speechChunk2 = ShortArray(800) { 7000 }
        val speechChunk3 = ShortArray(800) { 5000 }

        val chunks = mutableListOf<ShortArray>().apply {
            repeat(2) { add(ambientSilenceChunk) } // ambient before speech
            add(speechChunk1)
            add(speechChunk2)
            add(speechChunk3)
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
        // 2 ambient + 3 speech + 8 ambient silence (800ms after speech) = 13 chunks read
        assertEquals(13, fakeReader.chunkIndex)
    }
}
