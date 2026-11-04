package dev.cinematv.ui.shared

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.usableMetadata
import dev.cinematv.data.library.LibraryRecord
import dev.cinematv.data.library.LibrarySnapshot

/** A presentation projection, never a title matcher or a cross-provider progress merger. */
data class WorkSourceOption(val id: String, val label: String, val item: MediaItem)
data class WorkSeasonOption(val id: String, val label: String, val sources: List<WorkSourceOption>)
data class WorkNavigation(
    val workId: String,
    val seasons: List<WorkSeasonOption>,
    val selectedSeasonId: String,
    val selectedSourceId: String
) {
    val currentSeason get() = seasons.firstOrNull { it.id == selectedSeasonId }
    val visible get() = seasons.size > 1 || seasons.any { it.sources.size > 1 }
    fun source(seasonId: String, sourceId: String? = null): MediaItem? =
        seasons.firstOrNull { it.id == seasonId }?.sources?.let { sources ->
            if (sourceId == null) sources.firstOrNull()?.item else sources.firstOrNull { it.id == sourceId }?.item
        }
}

object WorkNavigationProjection {
    fun build(snapshot: LibrarySnapshot, selected: MediaItem, now: Long = System.currentTimeMillis()): WorkNavigation? {
        val safeSelected = selected.usableMetadata(now) ?: return null
        val selectedId = MediaDomainMapper.entryId(safeSelected)
        // Resolve the current record first. A stale UI callback must not resurrect a superseded mapping.
        val records = snapshot.records.mapNotNull { record -> record.item.usableMetadata(now)?.let { record.copy(item = it) } }
        val current = records.firstOrNull { MediaDomainMapper.entryId(it.item) == selectedId }?.item ?: safeSelected
        val entries = if (records.any { MediaDomainMapper.entryId(it.item) == selectedId }) records
            else records + LibraryRecord(current, null, false, 0, null)
        val byId = entries.associateBy { MediaDomainMapper.entryId(it.item) }
        val work = LibrarySnapshot(records = entries).works.firstOrNull { it.work.id == MediaDomainMapper.workId(current) } ?: return null
        val seasons = work.seasons.map { season ->
            WorkSeasonOption(season.season.id, if (season.season.number == null && current.isTv) "季数未知" else season.season.title,
                season.sources.sortedWith(compareBy({ it.source.sourceName }, { it.source.id })).mapNotNull { source -> byId[source.source.id]?.let { record ->
                    val item = snapshot.applyProgress(record.item)
                    val flags = listOfNotNull(record.status?.title, "已收藏".takeIf { record.favorite }).distinct()
                    WorkSourceOption(source.source.id,
                        listOf(item.source?.name?.ifBlank { "片源" } ?: "元数据目录", "ID ${item.id}", flags.joinToString(" · "))
                            .filter(String::isNotBlank).joinToString(" · "), item)
                } })
        }
        return WorkNavigation(work.work.id, seasons, MediaDomainMapper.seasonId(current), selectedId)
    }

    /** Filter shelves first, then retain the first exact source as representative; never union flags. */
    fun deduplicate(items: List<MediaItem>, now: Long = System.currentTimeMillis()): List<MediaItem> =
        items.mapNotNull { it.usableMetadata(now) }.distinctBy(MediaDomainMapper::workId)
}
