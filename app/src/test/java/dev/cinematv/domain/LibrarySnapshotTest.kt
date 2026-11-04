package dev.cinematv.domain

import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import dev.cinematv.data.library.LibraryRecord
import dev.cinematv.data.library.LibrarySnapshot
import org.junit.Assert.*
import org.junit.Test

class LibrarySnapshotTest {
    private val item = MediaItem("42", "Series", isTv = true,
        source = TvSource("source", "Source", "https://example.com"), lastEpisode = 1)
    private fun progress(index: Int) = EpisodeProgress.record(MediaDomainMapper.episodeId(item, index),
        MediaDomainMapper.entryId(item), index, (index + 1L) * 1000, 10_000, 100)

    @Test fun `each episode keeps its own resume position`() {
        val first = progress(0)
        val second = progress(1)
        val snapshot = LibrarySnapshot(true, listOf(LibraryRecord(item, LibraryStatus.WATCHING, true, 100, 100)),
            mapOf(first.episodeId to first, second.episodeId to second))
        assertEquals(1000L, snapshot.progress(item, 0)?.positionMs)
        assertEquals(2000L, snapshot.progress(item, 1)?.positionMs)
        assertNull(snapshot.progress(item, 2))
        assertEquals(2000L, snapshot.applyProgress(item).positionMs)
        assertEquals(1, snapshot.applyProgress(item).lastEpisode)
        assertEquals(LibraryStatus.WATCHING, snapshot.status(item))
        assertEquals(listOf(item), snapshot.favorites())
        assertEquals(listOf(item), snapshot.byStatus(LibraryStatus.WATCHING))
    }

    @Test fun `same title and provider ID from another source never resume`() {
        val first = progress(0)
        val snapshot = LibrarySnapshot(true, episodeProgress = mapOf(first.episodeId to first))
        val other = item.copy(source = item.source!!.copy(url = "https://other.example"))
        assertNull(snapshot.progress(other, 0))
    }

    @Test fun `history clearing removes stale item snapshot position`() {
        val stored = item.copy(positionMs = 9999, durationMs = 10000)
        val snapshot = LibrarySnapshot(true, listOf(LibraryRecord(stored, null, true, 100, null)))
        assertEquals(0L, snapshot.applyProgress(stored).positionMs)
        assertTrue(snapshot.history().isEmpty())
        assertEquals(1, snapshot.favorites().size)
    }
}
