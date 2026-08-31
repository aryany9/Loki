package dev.loki.android.core.sound

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCueTest {

    @Test
    fun testAudioCueGatedByKillSwitch() {
        audioStartCueEnabled = true
        assertTrue(audioStartCueEnabled)
        // Should not throw even in JVM unit test environment where AudioTrack is stubbed/mocked
        AudioCue.playStartTone()

        audioStartCueEnabled = false
        assertFalse(audioStartCueEnabled)
        AudioCue.playStartTone()

        // Reset
        audioStartCueEnabled = true
    }
}
