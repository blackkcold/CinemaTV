package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class LegacyScoreProvenanceTest {
    private val cached = MediaItem("source-id", "Saved title", rating = "9.2", ratingSource = " LibreTV ",
        poster = "saved poster", positionMs = 400, durationMs = 1000)

    @Test fun transportScoresCannotRenderOrSatisfyRatingFilters() {
        assertNull(RatingPolicy.score(cached))
        assertFalse(SearchFilters(minRating = 8.0).matches(cached))
    }

    @Test fun localSnapshotsAndDetailInputStripLegacyScoreWithoutLosingProgress() {
        val safe = cached.usableMetadata()!!
        assertEquals("", safe.rating)
        assertEquals("", safe.ratingSource)
        assertNull(safe.doubanTopRank)
        assertEquals(cached.title, safe.title)
        assertEquals(cached.poster, safe.poster)
        assertEquals(400L, safe.positionMs)
        assertEquals(1000L, safe.durationMs)
    }

    @Test fun explicitCatalogProvenanceRemainsUnchanged() {
        for (provider in listOf("TMDB", "Synthetic")) {
            val item = cached.copy(ratingSource = provider)
            assertEquals(item, item.usableMetadata())
            assertEquals(9.2, RatingPolicy.score(item)!!, 0.0)
        }
    }
}
