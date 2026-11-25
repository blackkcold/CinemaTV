package dev.cinematv.ui.tv.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LazyFocusTargetTest {
    @Test fun findsSavedTitleAfterPersonalizationMovesItBeyondTheOldViewport() {
        val original = (0 until 24).map { "movie-$it" }
        val saved = original[9]
        val personalized = original.filterNot { it == saved } + saved

        assertEquals(LazyFocusTarget(23, saved), resolveLazyFocusTarget(personalized, saved))
        assertEquals(LazyFocusTarget(0, saved), resolveLazyFocusTarget(personalized.reversed(), saved))
    }

    @Test fun preservesAnUnchangedTargetAndResolvesAnInsertedContinueRail() {
        assertEquals(LazyFocusTarget(1, "movie-9"), resolveLazyFocusTarget(listOf("movie-8", "movie-9"), "movie-9"))
        assertEquals(LazyFocusTarget(2, "movies"), resolveLazyFocusTarget(listOf("hero", "continue", "movies", "series"), "movies"))
    }

    @Test fun removedTitleFallsBackToItsOldPositionWithinTheNewTail() {
        assertEquals(LazyFocusTarget(1, "movie-2"),
            resolveLazyFocusTarget(listOf("movie-1", "movie-2"), "dropped-movie", fallbackIndex = 1))
        assertEquals(LazyFocusTarget(1, "movie-2"),
            resolveLazyFocusTarget(listOf("movie-1", "movie-2"), "dropped-movie", fallbackIndex = 9))
        assertEquals(LazyFocusTarget(0, "movie-1"),
            resolveLazyFocusTarget(listOf("movie-1", "movie-2"), "dropped-movie"))
    }

    @Test fun emptyRailHasNoTarget() {
        assertNull(resolveLazyFocusTarget(emptyList(), "movie-9"))
    }
}
