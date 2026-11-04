package dev.cinematv

import org.junit.Assert.*
import org.junit.Test

class FocusCoordinatorTest {
    @Test fun returnsOnlyTheLastFocusedRegion() {
        val memory = FocusCoordinator()
        memory.remember("HOME", "movies", "movie-6")
        assertEquals("movie-6", memory.restore("HOME", "movies"))
        assertNull(memory.restore("SEARCH", "movies"))
        assertNull(memory.restore("HOME", "series"))
        memory.remember("HOME", "series", "series-2")
        assertNull(memory.restore("HOME", "movies"))
        assertEquals("series-2", memory.restore("HOME", "series"))
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
