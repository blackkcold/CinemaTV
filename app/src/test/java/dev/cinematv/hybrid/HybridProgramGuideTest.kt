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

    @Test fun aVisibleClockAdvancesBeyondTheFirstBoundWithoutReplacingTheCachedGuide() {
        val cached = (0 until 120).map { TvProgram("Short program $it", it * 10_000L, (it + 1) * 10_000L) }
        val initial = HybridProgramGuide.window(cached, 0)
        val afterTenMinutes = HybridProgramGuide.window(cached, 600_000)
        assertEquals(48, initial.size)
        assertEquals(cached[47], initial.last())
        assertEquals(48, afterTenMinutes.size)
        assertEquals(cached[60], afterTenMinutes.first())
        assertEquals(cached[107], afterTenMinutes.last())
        assertTrue(initial.intersect(afterTenMinutes.toSet()).isEmpty())
    }
}
