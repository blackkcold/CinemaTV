package dev.cinematv.data.library

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.usableMetadata
import java.util.UUID

/** User-owned presentation overlay. Never feed these IDs or season labels into playback mapping. */
data class LocalWorkAssociation(
    val sourceEntryId: String,
    val groupId: String,
    val season: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val origin: String = "user-confirmed"
)

data class LocalWorkAssociations(
    val revision: String = "initial",
    val entries: Map<String, LocalWorkAssociation> = emptyMap(),
    val undoEntries: Map<String, LocalWorkAssociation>? = null,
    val writable: Boolean = true
) {
    fun workId(item: MediaItem) = entries[MediaDomainMapper.entryId(item)]?.groupId ?: MediaDomainMapper.workId(item)
    // A deliberately unknown manual season must not fall back to provider numbering.
    fun seasonLabel(item: MediaItem): Int? = if (entries.containsKey(MediaDomainMapper.entryId(item)))
        entries.getValue(MediaDomainMapper.entryId(item)).season else item.seasonNumber

    /** Complete target group, excluding the source being moved. Never infer native siblings. */
    fun targetEntryIds(sourceId: String, targetId: String): Set<String> {
        val group = entries[targetId]?.groupId ?: return setOf(targetId)
        return entries.values.filter { it.groupId == group && it.sourceEntryId != sourceId }
            .map { it.sourceEntryId }.toSet()
    }

    fun associate(source: MediaItem, target: MediaItem, season: Int?, timestamp: Long): LocalWorkAssociations {
        require(writable && (season == null || season >= 0))
        val from = MediaDomainMapper.entryId(source)
        val to = MediaDomainMapper.entryId(target)
        require(from != to)
        val confirmedAt = maxOf(0, timestamp, entries[from]?.updatedAt ?: 0, entries[to]?.updatedAt ?: 0)
        val group = entries[to]?.groupId ?: "manual-work:${UUID.randomUUID()}"
        val next = entries.toMutableMap()
        if (to !in next) next[to] = LocalWorkAssociation(to, group, target.seasonNumber, confirmedAt, confirmedAt)
        next[from] = LocalWorkAssociation(from, group, season, entries[from]?.createdAt ?: confirmedAt, confirmedAt)
        val groups = next.values.groupingBy { it.groupId }.eachCount()
        return changed(next.filterValues { groups[it.groupId]!! > 1 })
    }

    fun unlink(sourceId: String): LocalWorkAssociations {
        require(writable && sourceId in entries)
        val next = entries - sourceId
        // Remove singleton overlays too: unlink restores both original work identities.
        val groups = next.values.groupingBy { it.groupId }.eachCount()
        return changed(next.filterValues { groups[it.groupId]!! > 1 })
    }

    fun undo(): LocalWorkAssociations {
        require(writable && undoEntries != null)
        return LocalWorkAssociations(UUID.randomUUID().toString(), undoEntries, null)
    }
    private fun changed(next: Map<String, LocalWorkAssociation>) =
        LocalWorkAssociations(UUID.randomUUID().toString(), next, entries)
}

/** Only safe/current metadata is passed here. Display exact IDs, never match on this text. */
fun associationEvidence(item: MediaItem): String = listOf(
    item.title, "来源 ${item.source?.name ?: item.catalogProvider.ifBlank { "元数据目录" }}",
    "服务 ${item.source?.url ?: "目录"}", "条目 ID ${item.id}", "年份 ${item.year.ifBlank { "未知" }}",
    "类型 ${if (!item.mediaTypeKnown) "未知" else if (item.isTv) "剧集" else "电影"}",
    "原始季数 ${item.seasonNumber?.toString() ?: "未知"}",
    "外部 ID ${item.metadataIds.toSortedMap().entries.joinToString { "${it.key}:${it.value}" }.ifBlank { "无" }}"
).joinToString(" · ")

fun associationConflicts(source: MediaItem, target: MediaItem): List<String> = buildList {
    if (source.mediaTypeKnown && target.mediaTypeKnown && source.isTv != target.isTv) add("媒体类型不同")
    if (source.year.isNotBlank() && target.year.isNotBlank() && source.year != target.year) add("年份不同")
    source.metadataIds.forEach { (key, value) ->
        if (value.isNotBlank() && target.metadataIds[key]?.let { it.isNotBlank() && it != value } == true)
            add("$key 外部 ID 冲突")
    }
}

/** Render from frozen evidence only after current-time policy. Null never falls back to originals. */
fun visibleAssociationItems(evidence: List<MediaItem>, now: Long): List<MediaItem> =
    evidence.mapNotNull { it.usableMetadata(now) }
