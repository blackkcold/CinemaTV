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
}
