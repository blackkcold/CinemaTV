package dev.cinematv.ui.shared

import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import dev.cinematv.data.library.LibraryRecord
import dev.cinematv.data.library.LibrarySnapshot
import dev.cinematv.data.library.QuarantineReason
import dev.cinematv.domain.EpisodeProgress
import org.junit.Assert.*
import org.junit.Test

class LibraryProgressReviewTest {
    private val original = MediaItem("same-id", "Retained series", source = TvSource("a", "Source A", "https://a.invalid/api"), isTv = true)

    @Test fun reviewRetainsSourceIdentityPositionsAndReasonWithoutChangingLibrary() {
        val progress = EpisodeProgress("episode:legacy", MediaDomainMapper.entryId(original), 3, 90_000, 120_000, 400)
        val snapshot = LibrarySnapshot(true, listOf(LibraryRecord(original, LibraryStatus.WATCHING, true, 400, 400)),
            mapOf(progress.episodeId to progress))
        val review = LibraryProgressReviews.project(snapshot).single()
        assertEquals(original, review.item)
        assertEquals(3, review.oldEpisodeIndex)
        assertEquals(90_000L, review.positionMs)
        assertEquals(QuarantineReason.LEGACY_POSITION_ONLY, review.reason)
        assertTrue(review.reasonText.contains("无法确认"))
        assertEquals(progress, snapshot.episodeProgress[progress.episodeId])
        assertTrue(snapshot.isFavorite(original))
        assertEquals(LibraryStatus.WATCHING, snapshot.status(original))
    }

    @Test fun matchingTitleAndProviderIdNeverAttachReviewToAnotherSource() {
        val progress = EpisodeProgress("episode-unresolved:old", MediaDomainMapper.entryId(original), 1, 60_000, 90_000, 400)
        val other = original.copy(source = original.source!!.copy(url = "https://b.invalid/api"))
        val snapshot = LibrarySnapshot(true, listOf(LibraryRecord(other, null, false, 400, null)), mapOf(progress.episodeId to progress))
        val review = LibraryProgressReviews.project(snapshot).single()
        assertNull(review.item)
        assertEquals(QuarantineReason.MISSING_OR_AMBIGUOUS_IDENTITY, review.reason)
        assertEquals(60_000L, snapshot.episodeProgress[progress.episodeId]?.positionMs)
    }

    @Test fun destinationsHaveDistinctStableSections() {
        assertEquals(3, NativeSettingsSection.fromWireName("updates")?.index)
        assertEquals(4, NativeSettingsSection.fromWireName("developer")?.index)
        assertEquals(NativeSettingsSection.DEVELOPER, NativeSettingsSection.fromWireName("advanced"))
        assertEquals(6, NativeSettingsSection.fromWireName("library")?.index)
        assertEquals(NativeSettingsSection.entries.size, NativeSettingsSection.entries.map { it.index }.toSet().size)
        assertNull(NativeSettingsSection.fromWireName("arbitrary-page"))
    }
}
