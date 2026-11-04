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
