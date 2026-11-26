package dev.cinematv.ui.shared

import dev.cinematv.data.MediaItem
import dev.cinematv.data.RatingPolicy
import dev.cinematv.data.MediaDomainMapper

/** Provider + id is stable across progressive search batches and route returns. */
val MediaItem.presentationKey: String get() = MediaDomainMapper.entryId(this)

val MediaItem.watchProgress: Float? get() = if (durationMs > 0L) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else null

fun MediaItem.metadataLine(): String = listOf(
    year, category,
    RatingPolicy.score(this)?.let { "★ ${ratingSource.ifBlank { "来源评分" }} $rating" }.orEmpty(),
    doubanRankLabel().orEmpty(),
    if (sourceCount > 1) "$sourceCount 个片源" else ""
).filter(String::isNotBlank).joinToString(" · ")

/** A chart position is shown only when the record actually carries one. */
fun MediaItem.doubanRankLabel(): String? =
    doubanTopRank?.takeIf { it in 1..250 }?.let { "TOP $it" }

/**
 * Search-only caption. It names the number of **grouped sources**, which is not a count of
 * verified-playable sources: no liveness probe backs this line.
 */
fun MediaItem.searchMetadataLine(): String = listOf(
    RatingPolicy.score(this)?.let { "★ ${rating.trim()} ${ratingSource.ifBlank { "来源评分" }}" }.orEmpty(),
    year, category,
    if (sourceCount > 1) "$sourceCount 个来源" else ""
).filter(String::isNotBlank).joinToString(" · ")

/**
 * A readable label for one selectable source/version. It never contains a URL or a credential.
 * [MediaDomainMapper.versionId] stays the identity; this is display text only.
 */
fun MediaItem.sourceOptionLabel(): String = buildString {
    append(source?.name?.takeIf(String::isNotBlank) ?: if (source == null) "元数据目录" else "片源")
    if (isTv) seasonNumber?.let { append(" · 第 $it 季") }
}

/**
 * Labels for a source row. A repeated label gets a "版本 N" suffix so two versions of one
 * provider are distinguishable; a single occurrence keeps the plain name.
 */
fun sourceOptionLabels(items: List<MediaItem>): List<Pair<MediaItem, String>> {
    val base = items.map { it.sourceOptionLabel() }
    val counts = base.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return items.mapIndexed { index, item ->
        val label = base[index]
        if ((counts[label] ?: 0) > 1) {
            val ordinal = (seen[label] ?: 0) + 1
            seen[label] = ordinal
            item to "$label · 版本 $ordinal"
        } else item to label
    }
}

/** Narrow cards keep a valid score and its actual source ahead of optional detail. */
fun MediaItem.cardMetadataLine(): String = listOf(
    RatingPolicy.score(this)?.let { "★ ${rating.trim()} ${ratingSource.ifBlank { "来源评分" }}" }.orEmpty(),
    year, category,
    if (sourceCount > 1) "$sourceCount 个片源" else ""
).filter(String::isNotBlank).joinToString(" · ")

data class CatalogFilter(val category: String? = null, val year: String? = null, val ratedFirst: Boolean = false) {
    fun apply(items: List<MediaItem>): List<MediaItem> {
        val filtered = items.filter { (category == null || it.category == category) && (year == null || it.year == year) }
        return if (ratedFirst) filtered.sortedByDescending { RatingPolicy.score(it) ?: -1.0 } else filtered
    }
}

/** A poster is never a valid full-screen landscape fallback. */
fun landscapeArt(item: MediaItem?): String? = item?.backdrop?.takeIf {
    it.startsWith("https://") || it.startsWith("http://") || it.startsWith("android.resource://")
}
