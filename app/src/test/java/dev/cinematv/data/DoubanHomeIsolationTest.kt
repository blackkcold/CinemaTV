package dev.cinematv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Douban stays a separate chain: its public score and rank must never decorate a home card,
 * while the same item keeps its identity, source and playback state under the strip.
 */
class DoubanHomeIsolationTest {

    @Test fun publicDoubanBadgesLeaveTheHomeCard() {
        val chart = MediaItem(
            id = "chart-1", title = "某部电影", rating = "8.8", ratingSource = "豆瓣",
            doubanTopRank = 12, ratingPublic = true,
            catalogProvider = "douban-public", catalogAttribution = "豆瓣 Top 250"
        )
        val home = chart.withoutDoubanHomeBadges()
        assertEquals("", home.rating)
        assertEquals("", home.ratingSource)
        assertFalse(home.ratingPublic)
        assertNull(home.doubanTopRank)
        assertEquals("", home.catalogProvider)
        assertEquals("", home.catalogAttribution)
        assertEquals("chart-1", home.id)
        assertEquals("某部电影", home.title)
    }

    @Test fun doubanScoreIsStrippedEvenWithoutARank() {
        val item = MediaItem(id = "2", title = "t", rating = "9.1", ratingSource = "豆瓣")
        assertEquals("", item.withoutDoubanHomeBadges().rating)
    }

    @Test fun otherProvidersAreUntouched() {
        val tmdb = MediaItem(id = "3", title = "t", rating = "7.9", ratingSource = "TMDB")
        assertEquals(tmdb, tmdb.withoutDoubanHomeBadges())
        val licensed = MediaItem(id = "4", title = "t", rating = "8.2", ratingSource = "豆瓣",
            catalogProvider = "licensed-douban", ratingPublic = false)
        // Only a public Douban catalogue is stripped; a licensed provider keeps its provenance.
        assertEquals("licensed-douban", licensed.withoutDoubanHomeBadges().catalogProvider)
    }

    @Test fun plainLibraryItemsAreReturnedUnchanged() {
        val item = MediaItem(id = "5", title = "本地", category = "电影", positionMs = 60_000)
        assertEquals(item, item.withoutDoubanHomeBadges())
    }
}
