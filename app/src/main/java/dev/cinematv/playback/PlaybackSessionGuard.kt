package dev.cinematv.playback

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem

/** Immutable identity captured by a player and by every asynchronous playback operation. */
class PlaybackSession internal constructor(
    val generation: Long,
    val item: MediaItem?,
    val url: String,
    val episodeIndex: Int
) {
    val mediaKey: String? = item?.let(MediaDomainMapper::entryId)
}

/** Main-thread owner of playback generations and the two-alternative failover budget. */
class PlaybackSessionGuard {
    private var generation = 0L
    private var current: PlaybackSession? = null
    private var returnedDetail: PlaybackSession? = null
    private val checkpointOwners = mutableMapOf<Pair<String, Int>, Long>()
    private val attemptedSources = mutableSetOf<String>()
    private var remainingAlternatives = 0

    fun start(item: MediaItem?, url: String, episodeIndex: Int = 0): PlaybackSession {
        returnedDetail = null
        attemptedSources.clear()
        item?.source?.url?.let(attemptedSources::add)
        remainingAlternatives = if (item == null) 0 else 2
        return activate(item, url, episodeIndex)
    }

    fun invalidate(returningToDetail: Boolean = false) {
        generation++
        returnedDetail = if (returningToDetail) current else null
        current = null
        attemptedSources.clear()
        remainingAlternatives = 0
    }

    fun isCurrent(session: PlaybackSession): Boolean = current === session

    /** Only the player just dismissed with Back may refresh its returned detail checkpoint. */
    fun canUpdateReturnedDetail(session: PlaybackSession): Boolean =
        current == null && returnedDetail === session && session.item != null

    /** Final saves survive navigation, unless that exact entry/episode has since restarted. */
    fun canPersistCheckpoint(session: PlaybackSession): Boolean = session.mediaKey?.let {
        checkpointOwners[it to session.episodeIndex] == session.generation
    } == true

    fun canRecover(session: PlaybackSession): Boolean =
        isCurrent(session) && session.item != null && remainingAlternatives > 0

    fun canTrySource(session: PlaybackSession, sourceUrl: String): Boolean =
        canRecover(session) && sourceUrl !in attemptedSources

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
        return activate(item, url, session.episodeIndex)
    }

    private fun activate(item: MediaItem?, url: String, episodeIndex: Int): PlaybackSession =
        PlaybackSession(++generation, item, url, episodeIndex).also { session ->
            current = session
            session.mediaKey?.let { checkpointOwners[it to episodeIndex] = session.generation }
        }
}
