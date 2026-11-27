package dev.cinematv

import org.junit.Assert.*
import org.junit.Test

class FocusCoordinatorTest {
    @Test fun preservesEveryRouteAndRegionWhileTrackingLastTarget() {
        val memory = FocusCoordinator()
        memory.remember("HOME", "movies", "movie-6")
        assertEquals("movie-6", memory.restore("HOME", "movies"))
        assertNull(memory.restore("SEARCH", "movies"))
        assertNull(memory.restore("HOME", "series"))
        memory.remember("HOME", "series", "series-2")
        assertEquals("movie-6", memory.restore("HOME", "movies"))
        assertEquals("series-2", memory.restore("HOME", "series"))
        assertEquals("series", memory.last("HOME")?.region)
        memory.remember("SEARCH", "results", "result-22")
        assertEquals("series-2", memory.last("HOME")?.itemKey)
        memory.saveScroll("HOME", "page", 3, 25)
        assertEquals(FocusCoordinator.ScrollPosition(3, 25), memory.scroll("HOME", "page"))
        memory.clear()
        assertEquals(FocusCoordinator.ScrollPosition(), memory.scroll("HOME", "page"))
        assertNull(memory.last("HOME"))
    }

    @Test fun rejectsInvalidKeysAndCanClear() {
        val memory = FocusCoordinator()
        memory.remember("HOME", "rail", "item")
        memory.remember("", "rail", "ignored")
        assertEquals("item", memory.restore("HOME", "rail"))
        memory.clear()
        assertNull(memory.restore("HOME", "rail"))
    }

    /** A bookmark describes one visit; a finished route must not hand it to the next one. */
    @Test fun forgetDropsOnlyTheFinishedRoute() {
        val memory = FocusCoordinator()
        memory.remember("SEARCH", "search-grid", "work:movie:2025:旷野之声", 4)
        memory.remember("SEARCH", "search", "input")
        memory.saveScroll("SEARCH", "results", 3, 120)
        memory.remember("HOME", "rail:featured", "movie-6")

        memory.forget("SEARCH")

        // Every key of the finished route is gone: the bookmark, the per-region target and
        // the scroll position. Restoring any of them would focus a result the user never
        // opened in this visit.
        assertNull(memory.last("SEARCH"))
        assertNull(memory.restore("SEARCH", "search-grid"))
        assertNull(memory.restore("SEARCH", "search"))
        assertEquals(FocusCoordinator.ScrollPosition(), memory.scroll("SEARCH", "results"))

        // Other routes are untouched: forgetting one visit must not clear the session.
        assertEquals("movie-6", memory.restore("HOME", "rail:featured"))
        assertEquals("rail:featured", memory.last("HOME")?.region)
    }
}
