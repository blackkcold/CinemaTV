package dev.cinematv.ui.shared

import dev.cinematv.data.*
import dev.cinematv.data.library.*
import dev.cinematv.domain.EpisodeProgress
import org.junit.Assert.*
import org.junit.Test

class LocalWorkAssociationTest {
    private fun item(id: String, season: Int? = null) = MediaItem(id, "Same title", isTv = true,
        source = TvSource(id, id, "https://$id.example"), seasonNumber = season,
        episodes = listOf("https://$id.example/play"), episodeIds = listOf("episode"))
    private fun snapshot(overlay: LocalWorkAssociations, vararg items: MediaItem) = LibrarySnapshot(
        records = items.map { LibraryRecord(it, null, false, 0, null) }, associations = overlay)

    @Test fun `explicit association alone joins titles and labels unknown separately from specials`() {
        val a = item("a", 3); val b = item("b", 0)
        val empty = LocalWorkAssociations()
        assertNotEquals(empty.workId(a), empty.workId(b))
        val overlay = empty.associate(a, b, null, 10)
        assertEquals(overlay.workId(a), overlay.workId(b))
        assertNull(overlay.seasonLabel(a))
        val nav = WorkNavigationProjection.build(snapshot(overlay, a, b), a)!!
        assertEquals(listOf("特别篇 · 第0季 · 手动关联", "季数未知 · 手动关联"), nav.seasons.map { it.label })
        assertEquals(3, nav.currentSeason!!.sources.single().item.seasonNumber)
    }
    @Test fun `unlink and durable-state undo restore original grouping`() {
        val a = item("a"); val b = item("b")
        val linked = LocalWorkAssociations().associate(a, b, 1, 10)
        val unlinked = linked.unlink(MediaDomainMapper.entryId(a))
        assertTrue(unlinked.entries.isEmpty())
        val restored = unlinked.undo()
        assertEquals(linked.entries, restored.entries)
        assertNotEquals(linked.revision, restored.revision)
        assertNull(restored.undoEntries)
        assertEquals(emptyMap<String, LocalWorkAssociation>(), linked.undo().entries)
    }
    @Test fun `changing manual season leaves checkpoints external IDs and flags intact`() {
        val a = item("a", 1).copy(metadataIds = mapOf("tmdb" to "1"))
        val b = item("b", 2).copy(metadataIds = mapOf("tmdb" to "2"))
        val progress = EpisodeProgress.record(MediaDomainMapper.episodeId(a, 0), MediaDomainMapper.entryId(a), 0, 12000, 80000, 10)
        val overlay = LocalWorkAssociations().associate(a, b, 0, 10).associate(a, b, null, 11)
        val snapshot = LibrarySnapshot(records = listOf(LibraryRecord(a, LibraryStatus.WATCHING, true, 10, 10),
            LibraryRecord(b, LibraryStatus.WATCHED, false, 10, null)), episodeProgress = mapOf(progress.episodeId to progress), associations = overlay)
        val all = WorkNavigationProjection.build(snapshot, a)!!.seasons.flatMap { it.sources }
        val selected = all.single { it.id == MediaDomainMapper.entryId(a) }.item
        assertEquals(12000L, selected.positionMs)
        assertEquals(MediaDomainMapper.episodeId(a, 0), MediaDomainMapper.episodeId(selected, 0))
        assertEquals(a.metadataIds, selected.metadataIds)
        assertEquals(0L, all.single { it.id == MediaDomainMapper.entryId(b) }.item.positionMs)
        assertTrue(snapshot.isFavorite(a)); assertFalse(snapshot.isFavorite(b))
        assertEquals(listOf("tmdb 外部 ID 冲突"), associationConflicts(a, b))
    }
    @Test fun `association survives expiry without retaining expired catalog fields`() {
        val a = item("a").copy(metadataIds = mapOf("tmdb" to "1"), catalogLicenseExpiresAt = 10,
            catalogAttribution = "expired", poster = "licensed", description = "licensed")
        val b = item("b")
        val overlay = LocalWorkAssociations().associate(a, b, null, 9)
        val nav = WorkNavigationProjection.build(snapshot(overlay, a, b), a, 11)!!
        assertTrue(nav.visible)
        val safe = nav.seasons.flatMap { it.sources }.single { it.id == MediaDomainMapper.entryId(a) }.item
        assertEquals("", safe.poster); assertEquals("", safe.catalogAttribution); assertEquals("", safe.description)
        assertTrue(safe.metadataIds.isEmpty())
    }
    @Test fun `moving only one source does not drag its old group or leave singleton overlays`() {
        val a = item("a"); val b = item("b"); val c = item("c")
        val overlay = LocalWorkAssociations().associate(a, b, null, 1).associate(a, c, 0, 2)
        assertEquals(overlay.workId(a), overlay.workId(c))
        assertEquals(MediaDomainMapper.workId(b), overlay.workId(b))
    }
    @Test fun `preview membership is exact and excludes only the moving source`() {
        val a = item("a"); val b = item("b"); val c = item("c")
        val ids = listOf(a, b, c).map(MediaDomainMapper::entryId)
        val empty = LocalWorkAssociations()
        assertEquals(setOf(ids[1]), empty.targetEntryIds(ids[0], ids[1]))
        val pair = empty.associate(b, c, null, 1)
        assertEquals(setOf(ids[1], ids[2]), pair.targetEntryIds(ids[0], ids[1]))
        val triple = pair.associate(a, b, null, 2)
        assertEquals(setOf(ids[1], ids[2]), triple.targetEntryIds(ids[0], ids[1]))
        assertEquals(setOf(ids[0], ids[2]), triple.targetEntryIds(ids[1], ids[2]))
    }

    @Test fun `open editor evidence is filtered at controlled expiry without original fallbacks`() {
        val catalog = MediaItem("catalog", "Licensed catalog title", year = "2026", isTv = true,
            catalogProvider = "licensed", catalogLicenseExpiresAt = 100,
            metadataIds = mapOf("tmdb" to "1"), poster = "licensed", description = "licensed")
        val provider = item("provider").copy(catalogLicenseExpiresAt = 100,
            metadataIds = mapOf("tmdb" to "1"), catalogAttribution = "licensed", poster = "licensed")
        val frozenEvidence = listOf(catalog, provider)
        assertEquals(2, visibleAssociationItems(frozenEvidence, 99).size)
        val visible = visibleAssociationItems(frozenEvidence, 100)
        assertEquals(listOf("provider"), visible.map { it.id })
        assertFalse(visible.any { it.title == catalog.title || it.year == catalog.year })
        assertTrue(visible.single().metadataIds.isEmpty())
        assertEquals("", visible.single().poster)
        assertEquals("", visible.single().catalogAttribution)
        assertTrue(visibleAssociationItems(listOf(catalog), 100).isEmpty())
        // Frozen transaction evidence remains intact and is never itself rendered after expiry.
        assertEquals(mapOf("tmdb" to "1"), frozenEvidence.first().metadataIds)
    }

    @Test fun `invalid season and unsupported state fail closed`() {
        assertTrue(runCatching { LocalWorkAssociations().associate(item("a"), item("b"), -1, 1) }.isFailure)
        assertTrue(runCatching { LocalWorkAssociations(writable = false).associate(item("a"), item("b"), null, 1) }.isFailure)
    }
}
