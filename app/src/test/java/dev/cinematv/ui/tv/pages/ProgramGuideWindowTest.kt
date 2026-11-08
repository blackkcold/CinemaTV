package dev.cinematv.ui.tv.pages

import dev.cinematv.data.TvProgram
import org.junit.Assert.*
import org.junit.Test

class ProgramGuideWindowTest {
    @Test fun expiredCachedShowsCannotHideTheCurrentShowBeyondTheBound() {
        val now = 1_000_000L
        val ended = (0 until 60).map { TvProgram("Ended $it", it * 1_000L, (it + 1) * 1_000L) }
        val current = TvProgram("Current", now - 1_000, now + 1_000)
        val next = (1..60).map { TvProgram("Upcoming $it", now + it * 1_000, now + (it + 1) * 1_000) }
        val result = ProgramGuideWindow.window((ended + current + next).reversed(), now)
        assertEquals(48, result.size)
        assertEquals(current, result.first())
        assertTrue(result.all { it.stop > now })
    }

    @Test fun exactStopBoundaryInvalidIntervalsAndDuplicatesAreExcluded() {
        val current = TvProgram("Now", 100, 200)
        val next = TvProgram("Next", 200, 300)
        assertEquals(listOf(next), ProgramGuideWindow.window(listOf(next, current, next,
            TvProgram("Invalid", 400, 350)), 200))
    }

    @Test fun aVisibleClockAdvancesBeyondTheFirstBoundWithoutReplacingTheCachedGuide() {
        val cached = (0 until 120).map { TvProgram("Short program $it", it * 10_000L, (it + 1) * 10_000L) }
        val initial = ProgramGuideWindow.window(cached, 0)
        val afterTenMinutes = ProgramGuideWindow.window(cached, 600_000)
        assertEquals(48, initial.size)
        assertEquals(cached[47], initial.last())
        assertEquals(48, afterTenMinutes.size)
        assertEquals(cached[60], afterTenMinutes.first())
        assertEquals(cached[107], afterTenMinutes.last())
        assertTrue(initial.intersect(afterTenMinutes.toSet()).isEmpty())
    }

    @Test fun limitIsBoundedToZeroThroughFortyEight() {
        val programs = (0 until 80).map { TvProgram("P$it", it * 10L, (it + 1) * 10L) }
        assertTrue(ProgramGuideWindow.window(programs, 0, limit = -1).isEmpty())
        assertTrue(ProgramGuideWindow.window(programs, 0, limit = 0).isEmpty())
        assertEquals(1, ProgramGuideWindow.window(programs, 0, limit = 1).size)
        assertEquals(48, ProgramGuideWindow.window(programs, 0, limit = 99).size)
    }
}
