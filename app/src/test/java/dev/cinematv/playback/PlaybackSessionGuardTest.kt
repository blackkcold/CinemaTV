package dev.cinematv.playback

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionGuardTest {
    private fun media(id: String = "1", origin: String = "original", urls: List<String> = listOf("https://stream/1", "https://stream/2")) =
        MediaItem(id, "Series", source = TvSource(origin, origin, "https://$origin"), episodes = urls,
            isTv = true, year = "2025", episodeNames = listOf("Episode 1", "Episode 2"))

    @Test fun leavingPlayerRejectsLateSearchAndDetailResults() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val session = guard.start(item, item.episodes[1], 1)
        assertTrue(guard.trySource(session, "https://alternate"))

        guard.invalidate() // Back, navigation, opening a detail, or resetting the connection.

        assertFalse(guard.isCurrent(session))
        assertFalse(guard.canRecover(session))
        assertFalse(guard.trySource(session, "https://late-search-result"))
        assertNull(guard.switchSource(session, media(origin = "alternate"), "https://late-detail-result"))
    }

    @Test fun restartingIdenticalUrlAndEpisodeStillCreatesNewIdentity() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val old = guard.start(item, item.episodes[0])
        val restarted = guard.start(item, item.episodes[0])

        assertTrue(restarted.generation > old.generation)
        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(restarted))
        assertNull(guard.switchSource(old, media(origin = "alternate"), "https://alternate-stream"))
    }

    @Test fun newerEpisodeCannotUseOldRecoveryOrEndCallback() {
        val guard = PlaybackSessionGuard()
        val item = media(urls = listOf("https://shared-stream", "https://shared-stream"))
        val first = guard.start(item, item.episodes[0], 0)
        val second = guard.start(item, item.episodes[1], 1)

        assertEquals(first.url, second.url)
        assertEquals(0, first.episodeIndex)
        assertEquals(1, second.episodeIndex)
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test fun sameProviderIdAndStreamAcrossDifferentWorksDoesNotShareSession() {
        val guard = PlaybackSessionGuard()
        val first = media(origin = "first")
        val next = media(origin = "second")
        val old = guard.start(first, first.episodes[0])
        val current = guard.start(next, next.episodes[0])

        assertNotEquals(old.mediaKey, current.mediaKey)
        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(current))
    }

    @Test fun liveStartRejectsVodRecoveryAndHasNoVodPersistenceTarget() {
        val guard = PlaybackSessionGuard()
        val old = guard.start(media(), "https://vod-stream")
        val live = guard.start(null, "https://live-stream")

        assertFalse(guard.isCurrent(old))
        assertNull(live.item)
        assertNull(live.mediaKey)
        assertTrue(guard.isCurrent(live))
        assertFalse(guard.canPersistCheckpoint(live))
        assertFalse(guard.canRecover(live))
        assertFalse(guard.trySource(old, "https://late-vod-origin"))
    }

    @Test fun staleAttemptCannotConsumeNewSessionsRetryBudget() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val old = guard.start(item, item.episodes[0])
        val current = guard.start(item, item.episodes[0])

        assertFalse(guard.trySource(old, "https://a"))
        assertTrue(guard.trySource(current, "https://a"))
        assertTrue(guard.trySource(current, "https://b"))
        assertFalse(guard.trySource(current, "https://c"))
    }

    @Test fun recoveryChangesPlayerIdentityWithoutResettingRetryBudget() {
        val guard = PlaybackSessionGuard()
        val original = media()
        val old = guard.start(original, original.episodes[1], 1)
        assertTrue(guard.trySource(old, "https://alternate"))
        val alternate = media(origin = "alternate")
        val replacement = guard.switchSource(old, alternate, "https://new-stream")!!

        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(replacement))
        assertEquals(1, replacement.episodeIndex)
        assertEquals("https://new-stream", replacement.url)
        assertEquals(MediaDomainMapper.entryId(alternate), replacement.mediaKey)
        assertFalse(guard.trySource(replacement, "https://original"))
        assertFalse(guard.trySource(replacement, "https://alternate"))
        assertTrue(guard.trySource(replacement, "https://last-alternative"))
        assertFalse(guard.canRecover(replacement))
        assertFalse(guard.trySource(replacement, "https://third-alternative"))
    }

    @Test fun repeatedOriginDoesNotConsumeExtraAttempts() {
        val guard = PlaybackSessionGuard()
        val session = guard.start(media(), "https://stream")

        assertFalse(guard.trySource(session, "https://original"))
        assertTrue(guard.trySource(session, "https://alternate"))
        assertFalse(guard.trySource(session, "https://alternate"))
        assertTrue(guard.trySource(session, "https://second-alternative"))
        assertFalse(guard.canRecover(session))
    }

    @Test fun pauseDuringAsyncRecoveryDoesNotAutoplayTheReplacement() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val old = guard.start(item, item.episodes[0])
        assertTrue(guard.tryRefresh(old))
        // The user pauses after refresh started, before its response is published.
        old.recordPlayIntent(playWhenReady = false, outputSuppressed = false)
        val replacement = guard.switchSource(old, item, "https://refreshed-stream")!!
        assertFalse(replacement.autoPlay)
        old.recordPlayIntent(playWhenReady = true, outputSuppressed = false)
        assertFalse("A late old-player event cannot change new playback intent", replacement.resumeOnReplacement)
    }

    @Test fun systemSuppressionCarriesAcrossRecoveryButExplicitPlaybackCanStartAgain() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val interrupted = guard.start(item, item.episodes[0])
        interrupted.recordPlayIntent(playWhenReady = true, outputSuppressed = true)
        val replacement = guard.switchSource(interrupted, item, "https://refreshed-stream")!!
        assertFalse(replacement.autoPlay)
        val explicit = guard.start(item, item.episodes[0])
        assertTrue(explicit.autoPlay)
    }

    @Test fun activePlaybackStillAutoplaysVerifiedRecovery() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val active = guard.start(item, item.episodes[0])
        active.recordPlayIntent(playWhenReady = true, outputSuppressed = false)
        assertTrue(guard.switchSource(active, item, "https://refreshed-stream")!!.autoPlay)
    }

    @Test fun sourcelessVodStillHasOnlyTwoAlternativeAttempts() {
        val guard = PlaybackSessionGuard()
        val session = guard.start(media().copy(source = null), "https://stream")

        assertTrue(guard.trySource(session, "https://first"))
        assertTrue(guard.trySource(session, "https://second"))
        assertFalse(guard.trySource(session, "https://third"))
    }

    @Test fun disposedPlayerRetainsOriginalProgressTargetAfterAnotherWorkOpens() {
        val guard = PlaybackSessionGuard()
        val first = media(id = "first")
        val old = guard.start(first, first.episodes[1], 1)
        guard.invalidate()
        val next = media(id = "next")
        guard.start(next, next.episodes[0])

        // Final progress persists this snapshot even though it can no longer update active UI.
        assertSame(first, old.item)
        assertEquals(MediaDomainMapper.entryId(first), old.mediaKey)
        assertEquals(1, old.episodeIndex)
        assertFalse(guard.isCurrent(old))
        assertTrue(guard.canPersistCheckpoint(old))
    }

    @Test fun backAllowsFinalCheckpointToRefreshReturnedDetailOnly() {
        val guard = PlaybackSessionGuard()
        val session = guard.start(media(), "https://stream")

        guard.invalidate(returningToDetail = true)

        assertFalse(guard.isCurrent(session))
        assertTrue(guard.canUpdateReturnedDetail(session))
        assertTrue(guard.canPersistCheckpoint(session))
        guard.invalidate() // Another detail opens, navigation changes, or the server resets.
        assertFalse(guard.canUpdateReturnedDetail(session))
    }

    @Test fun oldCheckpointCannotRefreshNewPlaybackOrItsReturnedDetail() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val old = guard.start(item, item.episodes[0])
        guard.invalidate(returningToDetail = true)
        val restarted = guard.start(item, item.episodes[0])

        assertFalse(guard.canUpdateReturnedDetail(old))
        guard.invalidate(returningToDetail = true)
        assertFalse(guard.canUpdateReturnedDetail(old))
        assertTrue(guard.canUpdateReturnedDetail(restarted))
    }

    @Test fun sameEntryAndEpisodeRestartRejectsLateOldCheckpointEvenAfterBack() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val old = guard.start(item, item.episodes[0])
        val restarted = guard.start(item, item.episodes[0])

        assertFalse(guard.canPersistCheckpoint(old))
        assertTrue(guard.canPersistCheckpoint(restarted))
        guard.invalidate(returningToDetail = true)
        assertFalse(guard.canPersistCheckpoint(old))
        assertTrue(guard.canPersistCheckpoint(restarted))
    }

    @Test fun differentEpisodeMayStillPersistItsDisposalCheckpoint() {
        val guard = PlaybackSessionGuard()
        val item = media(urls = listOf("https://shared", "https://shared"))
            .copy(episodeIds = listOf("episode-a", "episode-b"))
        val first = guard.start(item, item.episodes[0], 0)
        val second = guard.start(item, item.episodes[1], 1)

        assertTrue(guard.canPersistCheckpoint(first))
        assertTrue(guard.canPersistCheckpoint(second))
    }

    @Test fun replacedCheckpointOwnerStaysRejectedAfterOpeningAnotherWork() {
        val guard = PlaybackSessionGuard()
        val item = media()
        val old = guard.start(item, item.episodes[0])
        val restarted = guard.start(item, item.episodes[0])
        guard.start(media(id = "other"), "https://other")

        assertFalse(guard.canPersistCheckpoint(old))
        assertTrue(guard.canPersistCheckpoint(restarted))
    }
    @Test fun reorderedSameEpisodeRestartRejectsOldCheckpointAtDifferentIndex() {
        val guard = PlaybackSessionGuard()
        val original = media().copy(episodeIds = listOf("a", "b"))
        val oldB = guard.start(original, original.episodes[1], 1)
        val reordered = original.copy(episodes = original.episodes.reversed(), episodeIds = listOf("b", "a"))
        val newB = guard.start(reordered, reordered.episodes[0], 0)
        assertEquals(oldB.checkpointKey, newB.checkpointKey)
        assertFalse(guard.canPersistCheckpoint(oldB))
        assertTrue(guard.canPersistCheckpoint(newB))
        guard.invalidate(returningToDetail = true)
        assertFalse(guard.canPersistCheckpoint(oldB))
        assertTrue(guard.canPersistCheckpoint(newB))
    }

    @Test fun distinctEpisodeAtSameIndexDoesNotInvalidateOtherEpisodesSave() {
        val guard = PlaybackSessionGuard()
        val original = media().copy(episodeIds = listOf("a", "b"))
        val oldA = guard.start(original, original.episodes[0], 0)
        val reordered = original.copy(episodes = original.episodes.reversed(), episodeIds = listOf("b", "a"))
        val newB = guard.start(reordered, reordered.episodes[0], 0)
        assertNotEquals(oldA.checkpointKey, newB.checkpointKey)
        assertTrue(guard.canPersistCheckpoint(oldA))
        assertTrue(guard.canPersistCheckpoint(newB))
    }

    @Test fun ambiguousEpisodeIdentityNeverAcquiresCheckpointOwnership() {
        val guard = PlaybackSessionGuard()
        val ambiguous = media(urls = listOf("https://shared", "https://shared"))
        val session = guard.start(ambiguous, ambiguous.episodes[0], 0)
        assertNull(session.checkpointKey)
        assertFalse(guard.canPersistCheckpoint(session))
        guard.invalidate(returningToDetail = true)
        assertFalse(guard.canPersistCheckpoint(session))
    }

}
