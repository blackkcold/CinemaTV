package dev.cinematv

import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import dev.cinematv.ui.shared.*
import dev.cinematv.ui.tv.design.*
import org.junit.Assert.*
import org.junit.Test

class PresentationPolicyTest {
    @Test fun progressRequiresRealDurationAndClampsBadPositions() {
        val item = MediaItem("1", "A", positionMs = 30000)
        assertNull(item.watchProgress)
        assertEquals(.5f, item.copy(durationMs = 60000).watchProgress!!, .001f)
        assertEquals(1f, item.copy(durationMs = 15000).watchProgress!!, .001f)
        assertEquals(0f, item.copy(positionMs = -1, durationMs = 15000).watchProgress!!, .001f)
    }
    @Test fun protectsLowEndDevicesAndHonorsReducedMotion() {
        assertEquals(GlassQuality.BASIC, resolveGlassQuality(GlassQuality.ENHANCED, true, 36, false))
        assertEquals(GlassQuality.BASIC, resolveGlassQuality(GlassQuality.AUTO, false, 30, false))
        assertEquals(GlassQuality.BACKDROP, resolveGlassQuality(GlassQuality.AUTO, false, 36, false))
        assertEquals(GlassQuality.BACKDROP, resolveGlassQuality(GlassQuality.ENHANCED, false, 36, true))
        assertEquals(GlassQuality.ENHANCED, resolveGlassQuality(GlassQuality.ENHANCED, false, 36, false))
        assertEquals(0, TvEffects(reducedMotion = true).sceneMillis)
    }
    @Test fun neverUsesPortraitArtAsLandscape() {
        val movie = MediaItem("1", "A", poster = "https://example.org/poster.jpg")
        assertNull(landscapeArt(movie))
        assertNull(landscapeArt(movie.copy(backdrop = "javascript:bad")))
        assertEquals("https://example.org/still.jpg", landscapeArt(movie.copy(backdrop = "https://example.org/still.jpg")))
    }
    @Test fun filtersOnlySuppliedMetadataAndKeepsSourceIdentity() {
        val a = MediaItem("1", "A", year = "2025", category = "剧情", rating = "8.1")
        val b = MediaItem("2", "B", year = "2026", category = "科幻", rating = "9.0")
        val c = MediaItem("3", "C", year = "2025", category = "剧情", rating = "暂无")
        assertEquals(listOf(a, c), CatalogFilter(category = "剧情", year = "2025", ratedFirst = true).apply(listOf(c, b, a)))
        assertNotEquals(a.presentationKey, a.copy(source = TvSource("a", "source", "https://example.org")).presentationKey)
    }
}
