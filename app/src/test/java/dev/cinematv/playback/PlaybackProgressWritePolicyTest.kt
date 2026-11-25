package dev.cinematv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressWritePolicyTest {
    @Test
    fun periodicWriteRequiresPlayingAndOneSecond() {
        assertTrue(PlaybackProgressWritePolicy.shouldPersistWhilePlaying(10_000, 9_000, true))
        assertFalse(PlaybackProgressWritePolicy.shouldPersistWhilePlaying(9_999, 9_000, true))
        assertFalse(PlaybackProgressWritePolicy.shouldPersistWhilePlaying(10_000, 9_000, false))
    }

    @Test
    fun finalWriteCoversPauseAndSeekBackButNotTinyDelta() {
        assertTrue(PlaybackProgressWritePolicy.shouldPersistFinal(10_000, 9_000))
        assertTrue(PlaybackProgressWritePolicy.shouldPersistFinal(9_000, 10_000))
        assertFalse(PlaybackProgressWritePolicy.shouldPersistFinal(9_999, 9_000))
        // 0 is a valid seek target; the caller filters C.TIME_UNSET before calling.
        assertTrue(PlaybackProgressWritePolicy.shouldPersistFinal(0, 5_000))
    }
}
