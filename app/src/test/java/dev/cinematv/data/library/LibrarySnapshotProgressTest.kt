package dev.cinematv.data.library

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.domain.EpisodeProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySnapshotProgressTest {
    private val first = MediaItem(id = "a", title = "A")
    private val second = MediaItem(id = "b", title = "B")

    @Test
    fun targetedProgressReplacesOneRecordAndMarksTheSnapshot() {
        val firstId = MediaDomainMapper.entryId(first)
        val snapshot = LibrarySnapshot(
            ready = true,
            records = listOf(
                LibraryRecord(first, null, favorite = false, updatedAt = 10, historyAt = 20),
                LibraryRecord(second, null, favorite = false, updatedAt = 11, historyAt = 21),
            ),
        )
        val progress = EpisodeProgress(
            episodeId = "episode:a",
            sourceMappingId = firstId,
            episodeIndex = 0,
            positionMs = 5_000,
            durationMs = 10_000,
            updatedAt = 30,
        )
        val updatedItem = first.copy(positionMs = 5_000, durationMs = 10_000, lastEpisode = 0)
        val next = snapshot.withProgress(
            firstId,
            LibraryRecord(updatedItem, null, favorite = false, updatedAt = 30, historyAt = 30),
            progress,
        )

        assertNotNull(next)
        assertEquals(LibraryChange.PROGRESS, next!!.change)
        assertEquals(5_000L, next.records.first { MediaDomainMapper.entryId(it.item) == firstId }.item.positionMs)
        assertEquals(11L, next.records.first {
            MediaDomainMapper.entryId(it.item) == MediaDomainMapper.entryId(second)
        }.updatedAt)
        assertEquals(progress, next.episodeProgress["episode:a"])
    }

    @Test
    fun unknownEntryNeedsFullRefresh() {
        val progress = EpisodeProgress("episode:a", "missing", 0, 1_000, 2_000, 30)
        assertNull(LibrarySnapshot(ready = true).withProgress(
            "missing",
            LibraryRecord(first, null, favorite = false, updatedAt = 30, historyAt = 30),
            progress,
        ))
    }
}
