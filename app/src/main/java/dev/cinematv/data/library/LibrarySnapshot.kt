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

    fun progress(item: MediaItem, episodeIndex: Int): EpisodeProgress? {
        val stored = byEntry[MediaDomainMapper.entryId(item)]?.item
        val scoped = if (item.seasonNumber == null && stored?.seasonNumber != null)
            item.copy(seasonNumber = stored.seasonNumber) else item
        return episodeProgress[MediaDomainMapper.episodeId(scoped, episodeIndex)]
    }

    fun applyProgress(item: MediaItem): MediaItem {
        val stored = byEntry[MediaDomainMapper.entryId(item)] ?: return item
        if (stored.historyAt == null) return item.copy(positionMs = 0L, durationMs = 0L, lastEpisode = 0)
        val last = stored.item.lastEpisode
        val progress = progress(item, last) ?: return item.copy(positionMs = 0L, durationMs = 0L, lastEpisode = 0)
        return item.copy(positionMs = progress.positionMs, durationMs = progress.durationMs, lastEpisode = last)
    }
}
