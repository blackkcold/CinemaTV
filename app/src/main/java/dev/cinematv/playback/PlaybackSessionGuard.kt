package dev.cinematv.playback

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem

/** Immutable identity captured by a player and by every asynchronous playback operation. */
class PlaybackSession internal constructor(
    val generation: Long,
    val item: MediaItem?,
    val url: String,
    val episodeIndex: Int,
    val autoPlay: Boolean = true
) {
    internal var resumeOnReplacement: Boolean = autoPlay
        private set

    /** Mutable output intent belongs to this identity, never to a newer player's callbacks. */
    internal fun recordPlayIntent(playWhenReady: Boolean, outputSuppressed: Boolean) {
        resumeOnReplacement = playWhenReady && !outputSuppressed
    }
    val mediaKey: String? = item?.let(MediaDomainMapper::entryId)
    // Use the same source/season/episode identity as persistence, never the mutable playlist ordinal.
    val checkpointKey: String? = item?.takeIf {
        MediaDomainMapper.episodeIdentity(it, episodeIndex) != null
    }?.let { MediaDomainMapper.episodeId(it, episodeIndex) }
}

/** Main-thread owner of playback generations and the two-alternative failover budget. */
class PlaybackSessionGuard {
    private var generation = 0L
    private var current: PlaybackSession? = null
    private var returnedDetail: PlaybackSession? = null
    private val checkpointOwners = mutableMapOf<String, Long>()
    private val attemptedSources = mutableSetOf<String>()
    private var remainingAlternatives = 0
    private var refreshAttempted = false

    fun start(item: MediaItem?, url: String, episodeIndex: Int = 0): PlaybackSession {
        returnedDetail = null
        attemptedSources.clear()
        refreshAttempted = false
        item?.source?.url?.let(attemptedSources::add)
        remainingAlternatives = if (item == null) 0 else 2
        return activate(item, url, episodeIndex)
    }

    fun invalidate(returningToDetail: Boolean = false) {
        generation++
        returnedDetail = if (returningToDetail) current else null
        current = null
        attemptedSources.clear()
        refreshAttempted = false
        remainingAlternatives = 0
    }

    fun isCurrent(session: PlaybackSession): Boolean = current === session

    /** Only the player just dismissed with Back may refresh its returned detail checkpoint. */
    fun canUpdateReturnedDetail(session: PlaybackSession): Boolean =
        current == null && returnedDetail === session && session.item != null

    /** Final saves survive navigation unless that source episode restarted. Unknown identity cannot save. */
    fun canPersistCheckpoint(session: PlaybackSession): Boolean = session.checkpointKey?.let {
        checkpointOwners[it] == session.generation
    } == true

    fun canRecover(session: PlaybackSession): Boolean =
        isCurrent(session) && session.item != null && remainingAlternatives > 0

    fun canTrySource(session: PlaybackSession, sourceUrl: String): Boolean =
        canRecover(session) && sourceUrl !in attemptedSources

    /** At most one own-source refresh across replacement generations; stale jobs cannot consume it. */
    fun tryRefresh(session: PlaybackSession): Boolean {
        if (!isCurrent(session) || session.item?.source == null || refreshAttempted) return false
        refreshAttempted = true
        return true
    }

    /** A suspended older recovery cannot consume the next player's retry budget. */
    fun trySource(session: PlaybackSession, sourceUrl: String): Boolean {
        if (!canTrySource(session, sourceUrl)) return false
        attemptedSources.add(sourceUrl)
        remainingAlternatives--
        return true
    }

    /** Invalidate callbacks from the failed player while retaining this session's retry budget. */
    fun switchSource(session: PlaybackSession, item: MediaItem, url: String): PlaybackSession? {
        if (!isCurrent(session)) return null
        return activate(item, url, session.episodeIndex, autoPlay = session.resumeOnReplacement)
    }

    private fun activate(item: MediaItem?, url: String, episodeIndex: Int, autoPlay: Boolean = true): PlaybackSession =
        PlaybackSession(++generation, item, url, episodeIndex, autoPlay).also { session ->
            current = session
            session.checkpointKey?.let { checkpointOwners[it] = session.generation }
        }
}
