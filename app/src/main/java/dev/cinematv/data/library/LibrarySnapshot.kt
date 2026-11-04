package dev.cinematv.data.library

import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.domain.EpisodeProgress

data class LibraryRecord(
    val item: MediaItem,
    val status: LibraryStatus?,
    val favorite: Boolean,
    val updatedAt: Long,
    val historyAt: Long?
)

/** Immutable, memory-only read model. Safe for Compose and synchronous repository accessors. */
data class LibrarySnapshot(
    val ready: Boolean = false,
    val records: List<LibraryRecord> = emptyList(),
    val episodeProgress: Map<String, EpisodeProgress> = emptyMap()
) {
    private val byEntry by lazy { records.associateBy { MediaDomainMapper.entryId(it.item) } }

    fun status(item: MediaItem): LibraryStatus? = byEntry[MediaDomainMapper.entryId(item)]?.let {
        it.status ?: if (it.favorite) LibraryStatus.FAVORITE else null
    }
    fun isFavorite(item: MediaItem): Boolean = byEntry[MediaDomainMapper.entryId(item)]?.favorite == true
    fun favorites(): List<MediaItem> = records.filter { it.favorite }.map { it.item }
    fun byStatus(status: LibraryStatus): List<MediaItem> = if (status == LibraryStatus.FAVORITE) favorites()
        else records.filter { it.status == status }.map { it.item }
    fun history(limit: Int = 60): List<MediaItem> = records.filter { it.historyAt != null }
        .sortedByDescending { it.historyAt }.take(limit.coerceAtLeast(0)).map { it.item }

    /** All retained checkpoints that cannot safely attach to the current playlist. */
    val quarantinedProgress: List<QuarantinedProgress> get() {
        val active = records.flatMap { record -> record.item.episodes.indices.mapNotNull { index ->
            MediaDomainMapper.episodeIdentity(record.item, index)?.let { MediaDomainMapper.episodeId(record.item, index) }
        } }.toSet()
        return episodeProgress.values.filter { it.episodeId !in active }.map { progress ->
            QuarantinedProgress(progress, when {
                progress.episodeId.startsWith("episode:") -> QuarantineReason.LEGACY_POSITION_ONLY
                progress.episodeId.startsWith("episode-unresolved:") -> QuarantineReason.MISSING_OR_AMBIGUOUS_IDENTITY
                else -> QuarantineReason.NOT_IN_CURRENT_PLAYLIST
            })
        }
    }

    /** Sources are kept separate under a work and an explicit (possibly unknown) season. */
    val works: List<LibraryWork> get() = records.map { MediaDomainMapper.map(it.item) }
        .groupBy { it.work.id }.values.map { graphs ->
            LibraryWork(graphs.first().work, graphs.groupBy { it.season.id }.values.map { seasons ->
                LibrarySeason(seasons.first().season, seasons.map { LibrarySource(it.source, it.episodes.toList(), it.work) })
            }.sortedWith(compareBy<LibrarySeason> { it.season.number == null }.thenBy { it.season.number }))
        }

    fun progress(item: MediaItem, episodeIndex: Int): EpisodeProgress? {
        if (MediaDomainMapper.episodeIdentity(item, episodeIndex) == null) return null
        return episodeProgress[MediaDomainMapper.episodeId(item, episodeIndex)]?.takeIf {
            it.sourceMappingId == MediaDomainMapper.entryId(item)
        }?.copy(episodeIndex = episodeIndex)
    }

    fun applyProgress(item: MediaItem): MediaItem {
        val stored = byEntry[MediaDomainMapper.entryId(item)] ?: return item
        if (stored.historyAt == null) return item.copy(positionMs = 0L, durationMs = 0L, lastEpisode = 0)
        // Resolve the last watched identity, never the old numeric position in a new playlist.
        val oldIndex = stored.item.lastEpisode
        val oldId = MediaDomainMapper.episodeIdentity(stored.item, oldIndex)
            ?.let { MediaDomainMapper.episodeId(stored.item, oldIndex) }
        val index = item.episodes.indices.firstOrNull { MediaDomainMapper.episodeIdentity(item, it) != null &&
            MediaDomainMapper.episodeId(item, it) == oldId }
        val progress = index?.let { progress(item, it) }
            ?: return item.copy(positionMs = 0L, durationMs = 0L, lastEpisode = 0)
        return item.copy(positionMs = progress.positionMs, durationMs = progress.durationMs, lastEpisode = index)
    }
}

enum class QuarantineReason { LEGACY_POSITION_ONLY, MISSING_OR_AMBIGUOUS_IDENTITY, NOT_IN_CURRENT_PLAYLIST }
data class QuarantinedProgress(val progress: EpisodeProgress, val reason: QuarantineReason)
data class LibraryWork(val work: dev.cinematv.domain.Work, val seasons: List<LibrarySeason>)
data class LibrarySeason(val season: dev.cinematv.domain.Season, val sources: List<LibrarySource>)
/** Metadata stays source-specific, with its original attribution and expiry. */
data class LibrarySource(val source: dev.cinematv.domain.SourceMapping,
    val episodes: List<dev.cinematv.domain.Episode>, val metadata: dev.cinematv.domain.Work)
