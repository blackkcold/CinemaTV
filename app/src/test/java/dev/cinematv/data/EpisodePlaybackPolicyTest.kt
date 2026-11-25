package dev.cinematv.data

import dev.cinematv.domain.EpisodeProgress
import org.junit.Assert.*
import org.junit.Test

class EpisodePlaybackPolicyTest {
    private fun progress(position: Long, duration: Long = 100_000L) =
        EpisodeProgress("episode", "source", 0, position, duration, 1)

    @Test fun completedFilmAndEpisodeStartAtZeroWithoutDeletingCheckpoint() {
        val ended = progress(100_000L)
        assertEquals(0L, EpisodePlaybackPolicy.startPosition(ended))
        assertEquals(0L, EpisodePlaybackPolicy.startPosition(progress(95_000L)))
        assertEquals(100_000L, ended.positionMs)
    }

    @Test fun incompleteResumeAndExplicitRestartHaveDifferentIntent() {
        assertEquals(42_000L, EpisodePlaybackPolicy.startPosition(progress(42_000L)))
        assertEquals(0L, EpisodePlaybackPolicy.startPosition(progress(42_000L), EpisodePlaybackAction.RESTART))
        assertEquals(42_000L, EpisodePlaybackPolicy.startPosition(progress(42_000L, 0L)))
        assertEquals(0L, EpisodePlaybackPolicy.startPosition(null))
    }
}
