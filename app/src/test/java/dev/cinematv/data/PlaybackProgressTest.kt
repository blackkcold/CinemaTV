package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressTest {
    @Test fun nextEpisodeWaitsForItsOwnDurationAcrossRepeatedSaves() {
        val previous = MediaItem("series", "Series", lastEpisode = 3, positionMs = 50000, durationMs = 60000)
        val starting = previous.withPlaybackProgress(0, 4, 0)
        assertEquals(4, starting.lastEpisode)
        assertEquals(0L, starting.durationMs)
        assertEquals(0L, starting.withPlaybackProgress(2000, 4, 0).durationMs)
        val measured = starting.withPlaybackProgress(3000, 4, 90000)
        assertEquals(90000L, measured.withPlaybackProgress(4000, 4, 0).durationMs)
    }
    @Test fun bufferingKeepsSameEpisodeDurationWithoutNegativePositions() {
        val item = MediaItem("film", "Film", durationMs = 90000)
        val saved = item.withPlaybackProgress(-500, 0, -1)
        assertEquals(0L, saved.positionMs)
        assertEquals(90000L, saved.durationMs)
        assertEquals(120000L, saved.withPlaybackProgress(1000, 0, 120000).durationMs)
    }
}
