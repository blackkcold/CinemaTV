package dev.cinematv.playback

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Provider episode IDs and names are scoped to their listing, never portable between sources. */
object PlaybackRecoveryPolicy {
    /** Check cancellation even if an upstream fetch returns after a non-cancellable operation. */
    suspend fun refreshCurrent(
        session: PlaybackSession,
        guard: PlaybackSessionGuard,
        fetch: suspend (MediaItem) -> MediaItem
    ): MediaItem? {
        currentCoroutineContext().ensureActive()
        if (!guard.isCurrent(session)) return null
        val original = session.item ?: return null
        val refreshed = fetch(original)
        currentCoroutineContext().ensureActive()
        return refreshed.takeIf { guard.isCurrent(session) }
    }

    fun canRetainPosition(
        original: MediaItem,
        originalIndex: Int,
        refreshed: MediaItem,
        refreshedIndex: Int
    ): Boolean {
        if (original.source == null || refreshed.source == null ||
            MediaDomainMapper.entryId(original) != MediaDomainMapper.entryId(refreshed)) return false
        if (!original.mediaTypeKnown || !refreshed.mediaTypeKnown || original.isTv != refreshed.isTv) return false
        if (original.seasonNumber != refreshed.seasonNumber ||
            (original.isTv && original.seasonNumber == null)) return false
        val identity = MediaDomainMapper.episodeIdentity(original, originalIndex) ?: return false
        return identity == MediaDomainMapper.episodeIdentity(refreshed, refreshedIndex)
    }
}
