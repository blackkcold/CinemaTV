package dev.cinematv.domain

import org.junit.Assert.*
import org.junit.Test

class EpisodeProgressTest {
    @Test fun `unknown duration has no invented completion percentage`() {
        val progress = EpisodeProgress.record("e0", "source", 0, 90_000, 0, 1)
        assertNull(progress.fraction)
        assertFalse(progress.completed)
        assertEquals(90_000L, progress.positionMs)
    }

    @Test fun `only same episode can retain a measured duration`() {
        val first = EpisodeProgress.record("e0", "source", 0, 200, 1000, 1)
        assertEquals(1000L, EpisodeProgress.record("e0", "source", 0, 300, 0, 2, first).durationMs)
        assertEquals(0L, EpisodeProgress.record("e1", "source", 1, 300, 0, 2, first).durationMs)
        assertEquals(0L, EpisodeProgress.record("e0", "other", 0, 300, 0, 2, first).durationMs)
    }

    @Test fun `invalid positions are clamped without negative time or overflow`() {
        assertEquals(0L, EpisodeProgress.record("e", "s", -1, -5, -9, -1).positionMs)
        val progress = EpisodeProgress.record("e", "s", 0, Long.MAX_VALUE, 1000, 1)
        assertEquals(1000L, progress.positionMs)
        assertEquals(1f, progress.fraction!!, 0f)
        assertTrue(progress.completed)
    }

    @Test fun `normal progress uses actual measured milliseconds`() {
        val progress = EpisodeProgress.record("e", "s", 0, 2500, 10_000, 1)
        assertEquals(0.25f, progress.fraction!!, 0.0001f)
        assertFalse(progress.completed)
    }
}
