package dev.cinematv.playback

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import dev.cinematv.data.library.LibrarySnapshot
import dev.cinematv.domain.EpisodeProgress
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PlaybackRecoveryPolicyTest {
    private fun item() = MediaItem("1", "Same title", year = "2025", isTv = true, seasonNumber = 1,
        source = TvSource("a", "A", "https://a.test"), episodes = listOf("https://stream.test/old"),
        episodeIds = listOf("provider-episode-1"), episodeNames = listOf("第1集"))
    private fun refreshed() = item().copy(episodes = listOf("https://stream.test/refreshed"))

    @Test fun verifiedSameSourceEpisodeCanRetainPositionAcrossUrlRotation() {
        assertTrue(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, refreshed(), 0))
    }
    @Test fun identicalTitleLabelAndProviderEpisodeIdAcrossProvidersNeverTransferProgress() {
        val other = refreshed().copy(source = TvSource("b", "B", "https://b.test"))
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, other, 0))
    }
    @Test fun sameProviderDifferentListingIsNotSameEpisode() {
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, refreshed().copy(id = "2"), 0))
    }
    @Test fun conflictingOrUnknownSeasonCannotAutoResume() {
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, refreshed().copy(seasonNumber = 2), 0))
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, refreshed().copy(seasonNumber = null), 0))
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item().copy(seasonNumber = null), 0,
            refreshed().copy(seasonNumber = null), 0))
    }
    @Test fun generatedLabelOrOrdinalCannotIdentifyRotatedEpisode() {
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item().copy(episodeIds = emptyList()), 0,
            refreshed().copy(episodeIds = emptyList()), 0))
    }
    @Test fun changedMissingOrAmbiguousEpisodeIsRejected() {
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, refreshed().copy(episodeIds = listOf("other")), 0))
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, refreshed().copy(episodes = emptyList()), 0))
        val duplicate = refreshed().copy(episodes = listOf("https://a", "https://b"),
            episodeIds = listOf("provider-episode-1", "provider-episode-1"), episodeNames = listOf("第1集", "第1集"))
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, duplicate, 0))
    }
    @Test fun unknownOrConflictingTypeIsRejected() {
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, refreshed().copy(mediaTypeKnown = false), 0))
        assertFalse(PlaybackRecoveryPolicy.canRetainPosition(item(), 0, refreshed().copy(isTv = false), 0))
    }
    @Test fun explicitSourceChoiceUsesOnlyChosenSourcesCheckpointOrZero() {
        val a = item()
        val b = refreshed().copy(source = TvSource("b", "B", "https://b.test"))
        fun checkpoint(item: MediaItem, position: Long) = EpisodeProgress(MediaDomainMapper.episodeId(item, 0),
            MediaDomainMapper.entryId(item), 0, position, 100_000, 1)
        val ap = checkpoint(a, 40_000)
        val bp = checkpoint(b, 12_000)
        val snapshot = LibrarySnapshot(episodeProgress = mapOf(ap.episodeId to ap))
        assertEquals(0L, snapshot.progress(b, 0)?.positionMs ?: 0L)
        assertEquals(12_000L, snapshot.copy(episodeProgress = mapOf(ap.episodeId to ap, bp.episodeId to bp))
            .progress(b, 0)?.positionMs)
    }
    @Test fun refreshBudgetSurvivesReplacementAndResetsOnlyOnExplicitPlayback() {
        val guard = PlaybackSessionGuard()
        val session = guard.start(item(), item().episodes[0])
        assertTrue(guard.tryRefresh(session))
        val replacement = guard.switchSource(session, refreshed(), refreshed().episodes[0])!!
        assertFalse(guard.tryRefresh(replacement))
        assertFalse(guard.tryRefresh(session))
        assertTrue(guard.tryRefresh(guard.start(item(), item().episodes[0])))
    }
    @Test fun cancelledNavigationAndStaleSessionsCannotPublishOrConsumeRefresh() {
        val guard = PlaybackSessionGuard()
        val old = guard.start(item(), item().episodes[0])
        assertTrue(guard.tryRefresh(old))
        guard.invalidate() // Navigation also cancels the recovery coroutine in the ViewModel.
        assertNull(guard.switchSource(old, refreshed(), refreshed().episodes[0]))
        val current = guard.start(item(), item().episodes[0])
        assertFalse(guard.tryRefresh(old))
        assertTrue(guard.tryRefresh(current))
        assertTrue(guard.isCurrent(current))
    }
    @Test fun cancellationRejectsEvenLateNonCancellableDetailResponse() = runBlocking {
        val guard = PlaybackSessionGuard()
        val session = guard.start(item(), item().episodes[0])
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<MediaItem>()
        var published = false
        val job = launch {
            PlaybackRecoveryPolicy.refreshCurrent(session, guard) {
                started.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }
            published = true
        }
        started.await()
        job.cancel()
        response.complete(refreshed())
        job.join()
        assertFalse(published)
        assertTrue(job.isCancelled)
    }
    @Test fun lateDetailCannotReplaceNewlyStartedSession() = runBlocking {
        val guard = PlaybackSessionGuard()
        val old = guard.start(item(), item().episodes[0])
        val response = PlaybackRecoveryPolicy.refreshCurrent(old, guard) {
            guard.start(item(), item().episodes[0])
            refreshed()
        }
        assertNull(response)
    }

}
