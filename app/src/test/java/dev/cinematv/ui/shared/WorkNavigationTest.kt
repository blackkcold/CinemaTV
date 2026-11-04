package dev.cinematv.ui.shared

import dev.cinematv.data.*
import dev.cinematv.data.library.*
import dev.cinematv.domain.EpisodeProgress
import org.junit.Assert.*
import org.junit.Test

class WorkNavigationTest {
    private fun item(provider: String, season: Int? = 1, work: String = "42") = MediaItem(
        "listing-$season", "Same title", isTv = true, seasonNumber = season,
        metadataIds = if (work.isEmpty()) emptyMap() else mapOf("tmdb" to work),
        source = TvSource(provider, provider, "https://$provider.example"),
        episodes = listOf("https://$provider.example/episode"), episodeIds = listOf("pilot"))
    private fun snapshot(vararg items: MediaItem) = LibrarySnapshot(records = items.map {
        LibraryRecord(it, null, false, 0, null)
    })

    @Test fun `two seasons two sources and unknown season remain separate`() {
        val a = item("a"); val b = item("b"); val second = item("a", 2); val unknown = item("c", null)
        val nav = WorkNavigationProjection.build(snapshot(a, b, second, unknown), a)!!
        assertEquals(listOf("第1季", "第2季", "季数未知"), nav.seasons.map { it.label })
        assertEquals(listOf(2, 1, 1), nav.seasons.map { it.sources.size })
        assertEquals(b, nav.source(nav.selectedSeasonId, MediaDomainMapper.entryId(b)))
    }
    @Test fun `same title does not group absent or conflicting IDs`() {
        val a = item("a", work = ""); val b = item("b", work = "")
        assertEquals(2, WorkNavigationProjection.deduplicate(listOf(a, b)).size)
        assertFalse(WorkNavigationProjection.build(snapshot(a, b), a)!!.visible)
        assertEquals(2, WorkNavigationProjection.deduplicate(listOf(item("a"), item("b", work = "99"))).size)
    }
    @Test fun `shelf dedup preserves exact first representative without merging flags`() {
        val a = item("a"); val b = item("b", 2)
        assertEquals(listOf(b), WorkNavigationProjection.deduplicate(listOf(b, a)))
        val snapshot = LibrarySnapshot(records = listOf(LibraryRecord(a, LibraryStatus.WATCHED, false, 0, null),
            LibraryRecord(b, LibraryStatus.WATCHING, true, 0, null)))
        assertEquals(listOf(a), WorkNavigationProjection.deduplicate(snapshot.byStatus(LibraryStatus.WATCHED)))
        assertEquals(listOf(b), WorkNavigationProjection.deduplicate(snapshot.favorites()))
    }
    @Test fun `stale source or season cannot select anything and latest mapping wins`() {
        val old = item("a"); val changed = old.copy(seasonNumber = 2, metadataIds = mapOf("tmdb" to "99"))
        val nav = WorkNavigationProjection.build(snapshot(changed), old)!!
        assertEquals(MediaDomainMapper.workId(changed), nav.workId)
        assertNull(nav.source(MediaDomainMapper.seasonId(old)))
        assertNull(nav.source(nav.selectedSeasonId, "removed"))
    }
    @Test fun `source progress metadata and attribution never migrate`() {
        val a = item("a").copy(catalogAttribution = "A license"); val b = item("b").copy(catalogAttribution = "B license")
        val progress = EpisodeProgress.record(MediaDomainMapper.episodeId(a, 0), MediaDomainMapper.entryId(a), 0, 12000, 90000, 100)
        val snapshot = LibrarySnapshot(records = listOf(LibraryRecord(a, LibraryStatus.WATCHING, true, 100, 100),
            LibraryRecord(b, LibraryStatus.WATCHED, false, 100, null)), episodeProgress = mapOf(progress.episodeId to progress))
        val nav = WorkNavigationProjection.build(snapshot, a)!!
        val options = nav.currentSeason!!.sources
        assertEquals(12000L, options[0].item.positionMs)
        assertEquals(0L, options[1].item.positionMs)
        assertEquals("B license", options[1].item.catalogAttribution)
        assertFalse(options[1].label.contains("已收藏"))
    }
    @Test fun `expired metadata cannot keep joining sources or leak provenance`() {
        val a = item("a").copy(catalogLicenseExpiresAt = 10, catalogAttribution = "expired")
        val b = item("b")
        val nav = WorkNavigationProjection.build(snapshot(a, b), a, 11)!!
        assertFalse(nav.visible)
        assertEquals("", nav.currentSeason!!.sources.single().item.catalogAttribution)
        assertEquals(2, WorkNavigationProjection.deduplicate(listOf(a, b), 11).size)
    }
}
