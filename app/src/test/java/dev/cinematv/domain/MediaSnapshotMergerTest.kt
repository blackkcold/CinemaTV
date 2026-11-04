package dev.cinematv.domain

import dev.cinematv.data.MediaItem
import dev.cinematv.data.library.MediaSnapshotMerger
import org.junit.Assert.*
import org.junit.Test

class MediaSnapshotMergerTest {
    private val licensed = MediaItem("1", "Licensed title", poster = "licensed-art", description = "Licensed synopsis",
        aliases = listOf("Licensed alias"), actors = listOf("Licensed cast"), isTv = true,
        rating = "9.0", ratingSource = "Douban", doubanTopRank = 12, ratingLicenseExpiresAt = 1000,
        catalogProvider = "licensed-provider", catalogAttribution = "Required attribution", catalogLicenseExpiresAt = 1000,
        metadataIds = mapOf("douban" to "123"))

    @Test fun `new catalog bundle never launders old licensed metadata`() {
        val fresh = MediaItem("1", "New title", catalogProvider = "TMDB", catalogAttribution = "TMDB attribution")
        val merged = MediaSnapshotMerger.merge(licensed, fresh)
        assertEquals("TMDB", merged.catalogProvider)
        assertEquals("TMDB attribution", merged.catalogAttribution)
        assertEquals(0L, merged.catalogLicenseExpiresAt)
        assertEquals("", merged.poster)
        assertEquals("", merged.description)
        assertTrue(merged.aliases.isEmpty())
        assertTrue(merged.actors.isEmpty())
        assertTrue(merged.metadataIds.isEmpty())
        assertEquals("", merged.rating)
        assertNull(merged.doubanTopRank)
        assertEquals(0L, merged.ratingLicenseExpiresAt)
    }

    @Test fun `partial source refresh retains full existing catalog provenance`() {
        val merged = MediaSnapshotMerger.merge(licensed, MediaItem("1", "Source title", mediaTypeKnown = false))
        assertEquals(licensed.poster, merged.poster)
        assertEquals(licensed.actors, merged.actors)
        assertEquals(licensed.catalogProvider, merged.catalogProvider)
        assertEquals(licensed.catalogAttribution, merged.catalogAttribution)
        assertEquals(licensed.catalogLicenseExpiresAt, merged.catalogLicenseExpiresAt)
        assertTrue(merged.mediaTypeKnown)
        assertTrue(merged.isTv)
    }

    @Test fun `known detail can resolve or correct unknown media type`() {
        val unknown = MediaItem("1", "Title", mediaTypeKnown = false)
        val merged = MediaSnapshotMerger.merge(unknown, unknown.copy(mediaTypeKnown = true, isTv = true))
        assertTrue(merged.mediaTypeKnown)
        assertTrue(merged.isTv)
        val corrected = MediaSnapshotMerger.merge(merged, merged.copy(isTv = false))
        assertFalse(corrected.isTv)
    }

    @Test fun `score provenance updates atomically even when new score is absent`() {
        val merged = MediaSnapshotMerger.merge(licensed, MediaItem("1", "Title", ratingSource = "TMDB"))
        assertEquals("", merged.rating)
        assertEquals("TMDB", merged.ratingSource)
        assertNull(merged.doubanTopRank)
        assertEquals(0L, merged.ratingLicenseExpiresAt)
    }
}
