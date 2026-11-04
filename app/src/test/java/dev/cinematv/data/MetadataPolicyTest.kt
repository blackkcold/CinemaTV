package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class MetadataPolicyTest {
    private val licensed = MediaItem("42", "Original source title", poster = "licensed poster", backdrop = "licensed art",
        description = "licensed description", aliases = listOf("licensed alias"), actors = listOf("licensed actor"),
        rating = "9.0", ratingSource = "Douban", doubanTopRank = 12, ratingLicenseExpiresAt = 1000,
        catalogProvider = "Synthetic provider", catalogAttribution = "Synthetic notice", catalogLicenseExpiresAt = 1000,
        positionMs = 400, durationMs = 1000)

    @Test fun expiryDropsCatalogButPreservesSourceOwnedHistory() {
        assertNull(licensed.usableMetadata(1000))
        val source = TvSource("s", "source", "https://example.test")
        val safe = licensed.copy(source = source).usableMetadata(1000)!!
        assertEquals(licensed.title, safe.title)
        assertEquals(source, safe.source)
        assertEquals(400L, safe.positionMs)
        assertEquals(1000L, safe.durationMs)
        assertEquals("", safe.poster)
        assertEquals("", safe.backdrop)
        assertEquals("", safe.description)
        assertEquals("", safe.rating)
        assertTrue(safe.actors.isEmpty())
        assertTrue(safe.aliases.isEmpty())
        assertNull(safe.doubanTopRank)
    }

    @Test fun futureAndOrdinarySourceDataRemainUsable() {
        assertEquals(licensed, licensed.usableMetadata(999))
        val ordinary = MediaItem("1", "Ordinary source")
        assertEquals(ordinary, ordinary.usableMetadata(5000))
        assertNull(licensed.copy(catalogLicenseExpiresAt = -1).usableMetadata(5000))
    }
}
