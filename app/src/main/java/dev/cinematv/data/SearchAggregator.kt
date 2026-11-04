package dev.cinematv.data

/** Pure, deterministic search-result grouping. Unknown-year titles stay separate. */
object SearchAggregator {
    fun group(input: List<MediaItem>): List<MediaItem> {
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        input.forEachIndexed { index, item ->
            val key = if (item.year.isBlank()) "unknown:$index" else
                MediaIdentity.normalizedTitle(item.title) + ":" + item.year.trim()
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.map { entries ->
            val preferred = entries.firstOrNull { it.poster.isNotBlank() } ?: entries.first()
            preferred.copy(
                sourceCount = entries.mapNotNull { it.source?.url?.takeIf(String::isNotBlank) }
                    .distinct().size.coerceAtLeast(1)
            )
        }
    }
}
