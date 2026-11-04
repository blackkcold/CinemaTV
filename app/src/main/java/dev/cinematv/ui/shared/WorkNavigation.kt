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
        val overlay = snapshot.associations
        val workId = overlay.workId(current)
        fun seasonId(item: MediaItem) = if (MediaDomainMapper.entryId(item) in overlay.entries)
            "$workId:season:${overlay.seasonLabel(item) ?: "unknown"}" else MediaDomainMapper.seasonId(item)
        val seasons = entries.filter { overlay.workId(it.item) == workId }
            .groupBy { overlay.seasonLabel(it.item) }.toList()
            .sortedWith(compareBy<Pair<Int?, List<LibraryRecord>>> { it.first == null }.thenBy { it.first })
            .map { (number, records) ->
                val manual = records.any { MediaDomainMapper.entryId(it.item) in overlay.entries }
                WorkSeasonOption(seasonId(records.first().item),
                    (number?.let { if (it == 0) "特别篇 · 第0季" else "第${it}季" } ?: "季数未知") +
                        if (manual) " · 手动关联" else "",
                    records.sortedWith(compareBy({ it.item.source?.name }, { MediaDomainMapper.entryId(it.item) })).map { record ->
                        val item = snapshot.applyProgress(record.item)
                        val flags = listOfNotNull(record.status?.title, "已收藏".takeIf { record.favorite }).distinct()
                        WorkSourceOption(MediaDomainMapper.entryId(item),
                            listOf(item.source?.name?.ifBlank { "片源" } ?: "元数据目录", "ID ${item.id}", flags.joinToString(" · "))
                                .filter(String::isNotBlank).joinToString(" · "), item)
                    })
            }
        return WorkNavigation(workId, seasons, seasonId(current), selectedId)
    }

    /** Filter shelves first, then retain the first exact source as representative; never union flags. */
    fun deduplicate(items: List<MediaItem>, now: Long = System.currentTimeMillis(),
        associations: dev.cinematv.data.library.LocalWorkAssociations = dev.cinematv.data.library.LocalWorkAssociations()): List<MediaItem> =
        items.mapNotNull { it.usableMetadata(now) }.distinctBy(associations::workId)
}
