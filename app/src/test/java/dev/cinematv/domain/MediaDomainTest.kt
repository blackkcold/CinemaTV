package dev.cinematv.domain

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import org.junit.Assert.*
import org.junit.Test

class MediaDomainTest {
    private val source = TvSource("key", "Source", "https://one.example/api")
    private val item = MediaItem("42", "A title", source = source,
        isTv = true, episodeNames = listOf("Pilot", "Arrival"), episodes = listOf("https://one.example/1?token=old", "https://one.example/2"))

    @Test fun `source collisions and similar titles never merge local records`() {
        val other = item.copy(source = source.copy(url = "https://two.example/api"))
        assertNotEquals(MediaDomainMapper.entryId(item), MediaDomainMapper.entryId(other))
        assertNotEquals(MediaDomainMapper.workId(item), MediaDomainMapper.workId(other))
        assertNotEquals(MediaDomainMapper.episodeId(item, 0), MediaDomainMapper.episodeId(other, 0))
    }

    @Test fun `metadata and signed URL changes do not change local record IDs`() {
        val enriched = item.copy(title = "Corrected title", year = "2026", poster = "art",
            metadataIds = mapOf("tmdb" to "128"), episodes = listOf("https://cdn.example/1?token=new"))
        assertEquals(MediaDomainMapper.entryId(item), MediaDomainMapper.entryId(enriched))
        assertEquals(MediaDomainMapper.episodeId(item, 0), MediaDomainMapper.episodeId(enriched, 0))
        assertNotEquals(MediaDomainMapper.workId(item), MediaDomainMapper.workId(enriched))
    }

    @Test fun `trusted external ID can associate works but not episode progress`() {
        val a = item.copy(metadataIds = mapOf("imdb" to "tt1234567"))
        val b = a.copy(source = source.copy(url = "https://two.example/api"), id = "other")
        assertEquals(MediaDomainMapper.workId(a), MediaDomainMapper.workId(b))
        assertNotEquals(MediaDomainMapper.episodeId(a, 0), MediaDomainMapper.episodeId(b, 0))
        assertNotEquals(MediaDomainMapper.workId(a), MediaDomainMapper.workId(a.copy(isTv = false)))
    }

    @Test fun `unknown IDs do not join works and missing season stays unknown`() {
        val a = item.copy(metadataIds = mapOf("untrusted" to "42"))
        val b = a.copy(source = source.copy(url = "https://two.example/api"))
        assertNotEquals(MediaDomainMapper.workId(a), MediaDomainMapper.workId(b))
        val graph = MediaDomainMapper.map(a)
        assertNull(graph.season.number)
        assertEquals("未分季", graph.season.title)
        assertEquals(listOf(0, 1), graph.episodes.map { it.index })
        assertEquals(graph.source.id, graph.episodes.first().sourceMappingId)
    }

    @Test fun `length-prefixed identity cannot collide through delimiters`() {
        val a = item.copy(id = "a|b", source = source.copy(url = "c"))
        val b = item.copy(id = "a", source = source.copy(url = "b|c"))
        assertNotEquals(MediaDomainMapper.entryId(a), MediaDomainMapper.entryId(b))
    }

    @Test fun `explicit seasons have distinct episodes even under same source listing`() {
        assertNotEquals(MediaDomainMapper.episodeId(item.copy(seasonNumber = 1), 0),
            MediaDomainMapper.episodeId(item.copy(seasonNumber = 2), 0))
    }
    @Test fun `unknown media type is explicit and keeps catalog provenance`() {
        val unknown = item.copy(mediaTypeKnown = false, catalogProvider = "licensed-provider",
            catalogAttribution = "Attribution", catalogLicenseExpiresAt = 1000)
        val work = MediaDomainMapper.map(unknown).work
        assertEquals(WorkKind.UNKNOWN, work.kind)
        assertFalse(work.mediaTypeKnown)
        assertEquals("licensed-provider", work.catalogProvider)
        assertEquals("Attribution", work.catalogAttribution)
        assertEquals(1000L, work.catalogLicenseExpiresAt)
        val withId = unknown.copy(metadataIds = mapOf("tmdb" to "123"))
        assertNotEquals(MediaDomainMapper.workId(withId), MediaDomainMapper.workId(withId.copy(mediaTypeKnown = true)))
    }

    @Test fun `source-less numeric metadata IDs are scoped by movie series and unknown kind`() {
        val movie = item.copy(source = null, isTv = false)
        val series = movie.copy(isTv = true)
        val unknown = movie.copy(mediaTypeKnown = false)
        val keys = listOf(movie, series, unknown).map(MediaDomainMapper::entryId)
        assertEquals(3, keys.toSet().size)
        assertEquals(MediaDomainMapper.entryId(unknown), MediaDomainMapper.entryId(unknown.copy(isTv = true)))
        assertNotEquals(MediaDomainMapper.entryId(movie), MediaDomainMapper.entryId(movie.copy(source = source)))
    }

    @Test fun `provider listing keys remain unchanged across type enrichment`() {
        val unknown = item.copy(mediaTypeKnown = false)
        val expected = "entry:f2725a9c81b62c10455359d78cf9d5d63282d10237241b0bb3331b3a9cdb1b15"
        assertEquals(expected, MediaDomainMapper.entryId(unknown))
        assertEquals(expected, MediaDomainMapper.entryId(item.copy(isTv = false)))
        assertEquals(expected, MediaDomainMapper.entryId(item.copy(isTv = true)))
    }

}
