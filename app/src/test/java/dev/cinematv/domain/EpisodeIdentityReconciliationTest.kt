package dev.cinematv.domain

import dev.cinematv.data.MediaDomainMapper as Mapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import dev.cinematv.data.library.*
import org.junit.Assert.*
import org.junit.Test

class EpisodeIdentityReconciliationTest {
    private val item = MediaItem("42", "Series", source = TvSource("s", "Source", "https://source.test"),
        episodes = listOf("https://cdn.test/a?token=old", "https://cdn.test/b?token=old"),
        episodeNames = listOf("Pilot", "Return"), isTv = true, lastEpisode = 1)
    private fun snapshot(value: MediaItem = item): LibrarySnapshot {
        val progress = EpisodeProgress.record(Mapper.episodeId(value, 1), Mapper.entryId(value), 1, 4000, 9000, 1)
        return LibrarySnapshot(true, listOf(LibraryRecord(value, null, false, 1, 1)), mapOf(progress.episodeId to progress))
    }
    @Test fun `named episode follows reorder insertion removal and signed URL rotation`() {
        val changed = item.copy(episodes = listOf("new", "https://new/b?token=new", "https://new/a?token=new"),
            episodeNames = listOf("Special", "Return", "Pilot"))
        assertEquals(4000L, snapshot().progress(changed, 1)?.positionMs)
        val reordered = changed.copy(episodes = changed.episodes.reversed(), episodeNames = changed.episodeNames.reversed())
        assertEquals(4000L, snapshot().applyProgress(reordered).positionMs)
        val removed = item.copy(episodes = listOf("a"), episodeNames = listOf("Pilot"))
        assertNull(snapshot().progress(removed, 0))
        assertEquals(0L, snapshot().applyProgress(removed).positionMs)
    }
    @Test fun `provider ID survives renamed titles and moved index`() {
        val old = item.copy(episodeIds = listOf("a", "b"))
        val changed = old.copy(episodes = listOf("new-b", "new-a"), episodeIds = listOf("b", "a"), episodeNames = listOf("Changed", "Changed"))
        assertEquals(4000L, snapshot(old).progress(changed, 0)?.positionMs)
        assertEquals(0, snapshot(old).applyProgress(changed).lastEpisode)
    }
    @Test fun `URL only identity never strips arbitrary query parameters`() {
        val old = item.copy(episodeNames = emptyList())
        val reordered = old.copy(episodes = old.episodes.reversed())
        assertEquals(4000L, snapshot(old).progress(reordered, 0)?.positionMs)
        assertNull(snapshot(old).progress(old.copy(episodes = old.episodes.map { it.replace("old", "new") }), 1))
        assertNotEquals(Mapper.episodeId(old.copy(episodes = listOf("https://test/play?id=1")), 0),
            Mapper.episodeId(old.copy(episodes = listOf("https://test/play?id=2")), 0))
    }
    @Test fun `generated labels duplicates and absent playlists cannot prove identity`() {
        val old = item.copy(episodeNames = listOf("第1集", "第2集"))
        assertNull(snapshot(old).progress(old.copy(episodes = listOf("new-a", "new-b")), 1))
        val duplicate = old.copy(episodes = listOf("same", "same"), episodeNames = listOf("Repeat", "Repeat"), episodeIds = listOf("x", "x"))
        assertNull(Mapper.episodeIdentity(duplicate, 1))
        assertNull(snapshot(duplicate).progress(duplicate, 1))
        assertEquals(QuarantineReason.MISSING_OR_AMBIGUOUS_IDENTITY, snapshot(duplicate).quarantinedProgress.single().reason)
        assertNull(Mapper.episodeIdentity(item.copy(episodes = emptyList()), 1))
    }
    @Test fun `snapshot merge does not borrow stale names or known season for fresh playlist`() {
        val old = item.copy(episodeIds = listOf("a", "b"), seasonNumber = 1)
        val merged = MediaSnapshotMerger.merge(old, item.copy(episodeNames = emptyList(), episodes = listOf("b", "a")))
        assertTrue(merged.episodeNames.isEmpty())
        assertTrue(merged.episodeIds.isEmpty())
        assertNull(merged.seasonNumber)
        val partial = MediaSnapshotMerger.merge(old, item.copy(episodes = emptyList()))
        assertEquals(old.episodeNames, partial.episodeNames)
        assertEquals(old.episodeIds, partial.episodeIds)
    }
    @Test fun `authoritative empty clears playlist but absent detail preserves it`() {
        val absent = item.copy(episodes = emptyList(), episodeNames = emptyList())
        assertEquals(item.episodes, MediaSnapshotMerger.merge(item, absent).episodes)
        val empty = MediaSnapshotMerger.merge(item, absent.copy(playlistKnown = true))
        assertTrue(empty.episodes.isEmpty())
        assertTrue(empty.episodeNames.isEmpty())
        assertTrue(empty.playlistKnown)
        assertNull(snapshot().progress(empty, 1))
    }
    @Test fun `unknown season and alternate sources never inherit checkpoints`() {
        assertNull(snapshot().progress(item.copy(seasonNumber = 1), 1))
        assertNull(snapshot().progress(item.copy(source = item.source!!.copy(url = "https://other.test")), 1))
    }
    @Test fun `legacy opaque checkpoint is retained and visibly quarantined`() {
        val old = EpisodeProgress("episode:old-v1", Mapper.entryId(item), 1, 4000, 9000, 1)
        val view = LibrarySnapshot(true, listOf(LibraryRecord(item, null, false, 1, 1)), mapOf(old.episodeId to old))
        assertNull(view.progress(item, 1))
        assertEquals(old, view.episodeProgress[old.episodeId])
        assertEquals(QuarantineReason.LEGACY_POSITION_ONLY, view.quarantinedProgress.single().reason)
    }
    @Test fun `checkpoint identity never borrows cached playlist for metadata only history`() {
        val history = item.copy(episodes = emptyList(), episodeNames = emptyList())
        val identity = CheckpointIdentity.resolve(history, 1, item)
        assertTrue(identity.episodeId.startsWith("episode-unresolved:"))
        assertNull(identity.currentIndex)
        assertNotEquals(Mapper.episodeId(item, 1), identity.episodeId)
    }
    @Test fun `late callback resolves only actual identity against the current playlist`() {
        assertNull(CheckpointIdentity.resolve(item, -1, item).currentIndex)
        assertTrue(CheckpointIdentity.resolve(item, -1, item).episodeId.startsWith("episode-unresolved:"))
        val rotated = item.copy(episodes = listOf("new-b", "new-a"), episodeNames = listOf("Return", "Pilot"))
        assertEquals(0, CheckpointIdentity.resolve(item, 1, rotated).currentIndex)
        assertNull(CheckpointIdentity.resolve(item, 1, rotated.copy(episodes = listOf("new-a"), episodeNames = listOf("Pilot"))).currentIndex)
        assertNull(CheckpointIdentity.resolve(item, 1, rotated.copy(episodes = emptyList(), playlistKnown = true)).currentIndex)
        assertNull(CheckpointIdentity.resolve(item, 1, rotated.copy(seasonNumber = 1)).currentIndex)
        val urls = item.copy(episodeNames = emptyList())
        assertNull(CheckpointIdentity.resolve(urls, 1, urls.copy(episodes = listOf("new-a", "new-b"))).currentIndex)
        assertEquals(Mapper.episodeId(urls, 1), CheckpointIdentity.resolve(urls, 1, rotated).episodeId)
    }
    @Test fun `work season read model keeps source metadata and unknown seasons explicit`() {
        val a = item.copy(metadataIds = mapOf("tmdb" to "1"), catalogProvider = "A", catalogLicenseExpiresAt = 100)
        val b = a.copy(id = "43", seasonNumber = 2, catalogProvider = "B", catalogLicenseExpiresAt = 200)
        val view = LibrarySnapshot(true, listOf(a, b).map { LibraryRecord(it, null, false, 1, null) })
        val work = view.works.single()
        assertEquals(listOf(2, null), work.seasons.map { it.season.number })
        assertEquals(listOf("B", "A"), work.seasons.map { it.sources.single().metadata.catalogProvider })
        assertEquals(listOf(200L, 100L), work.seasons.map { it.sources.single().metadata.catalogLicenseExpiresAt })
    }
}
