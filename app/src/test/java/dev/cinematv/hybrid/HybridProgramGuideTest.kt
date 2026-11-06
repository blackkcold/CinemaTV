package dev.cinematv.hybrid

import dev.cinematv.data.TvProgram
import org.junit.Assert.*
import org.junit.Test

class HybridProgramGuideTest {
    @Test fun expiredCachedShowsCannotHideTheCurrentShowBeyondTheBound() {
        val now = 1_000_000L
        val ended = (0 until 60).map { TvProgram("Ended $it", it * 1_000L, (it + 1) * 1_000L) }
        val current = TvProgram("Current", now - 1_000, now + 1_000)
        val next = (1..60).map { TvProgram("Upcoming $it", now + it * 1_000, now + (it + 1) * 1_000) }
        val result = HybridProgramGuide.window((ended + current + next).reversed(), now)
        assertEquals(48, result.size)
        assertEquals(current, result.first())
        assertTrue(result.all { it.stop > now })
    }

    @Test fun exactStopBoundaryInvalidIntervalsAndDuplicatesAreExcluded() {
        val current = TvProgram("Now", 100, 200)
        val next = TvProgram("Next", 200, 300)
        assertEquals(listOf(next), HybridProgramGuide.window(listOf(next, current, next,
            TvProgram("Invalid", 400, 350)), 200))
    }
}
