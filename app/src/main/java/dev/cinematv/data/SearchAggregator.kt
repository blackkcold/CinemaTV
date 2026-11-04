package dev.cinematv.data

/** A displayed work plus all original, playable source records. */
data class SearchResultGroup(val item: MediaItem, val alternatives: List<MediaItem>)

/**
 * Conservative, deterministic grouping, never an alias-based identity guess.
 * A film and a series are different works, as are conflicting or ambiguous years.
 * Unknown-year records may only coalesce when the complete record is repeated or
 * an explicit source/id and the title/type agree. Original source IDs are retained.
 */
object SearchAggregator {
    fun group(input: List<MediaItem>): List<MediaItem> = groupWithAlternatives(input).map { it.item }

    fun groupWithAlternatives(input: List<MediaItem>): List<SearchResultGroup> {
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        input.distinct().forEachIndexed { index, item ->
            val key = workKey(item) ?: sourceKey(item) ?: "unknown:$index"
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.flatMap(::splitConflictingIds).map { entries ->
            val preferred = entries.firstOrNull { it.poster.isNotBlank() } ?: entries.first()
            SearchResultGroup(withSourceCount(preferred, entries), entries.toList())
        }
    }

    internal fun withSourceCount(item: MediaItem, entries: List<MediaItem>): MediaItem =
        item.copy(sourceCount = entries.mapNotNull { it.source?.url?.trim()?.takeIf(String::isNotBlank) }
            .distinct().size.coerceAtLeast(1))

    internal fun workKey(item: MediaItem): String? {
        val title = MediaIdentity.normalizedTitle(item.title)
        val year = item.year.trim()
        if (!item.mediaTypeKnown || title.isBlank() || !Regex("[12][0-9]{3}").matches(year)) return null
        val season = if (item.isTv) item.seasonNumber?.toString() ?: "unknown" else "movie"
        return "work:${item.isTv}:$season:$year:$title"
    }

    private fun sourceKey(item: MediaItem): String? {
        val url = item.source?.url?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (item.id.isBlank() || MediaIdentity.normalizedTitle(item.title).isBlank()) return null
        // Length-prefixed components prevent delimiter collisions in provider IDs/URLs.
        return "source:" + listOf(url, item.id, MediaIdentity.normalizedTitle(item.title),
            item.year.trim(), item.isTv.toString(), if (item.isTv) item.seasonNumber?.toString() ?: "" else "")
            .joinToString("") { "${it.length}:$it" }
    }

    internal fun sameWork(a: MediaItem, b: MediaItem): Boolean {
        if (a.mediaTypeKnown && b.mediaTypeKnown && a.isTv != b.isTv) return false
        if ((a.isTv || b.isTv) && a.seasonNumber != b.seasonNumber) return false
        val idsA = explicitIds(a)
        val idsB = explicitIds(b)
        if (idsA.any { (provider, id) -> idsB[provider]?.let { it != id } == true }) return false
        val yearA = a.year.trim()
        val yearB = b.year.trim()
        if (yearA.isNotBlank() && yearB.isNotBlank() && yearA != yearB) return false
        val titleA = MediaIdentity.normalizedTitle(a.title)
        val titleB = MediaIdentity.normalizedTitle(b.title)
        val sourceA = a.source?.url?.takeIf(String::isNotBlank)
        val sourceB = b.source?.url?.takeIf(String::isNotBlank)
        val sameSource = (a.source == null && b.source == null) || (sourceA != null && sourceA == sourceB)
        if (a.id.isNotBlank() && a.id == b.id && sameSource && titleA.isNotBlank() && titleA == titleB) return true
        if (yearA != yearB) return false
        val aWork = workKey(a)
        val bWork = workKey(b)
        if (aWork != null && bWork != null) return aWork == bWork
        val aSource = sourceKey(a)
        val bSource = sourceKey(b)
        if (aSource != null && bSource != null) return aSource == bSource
        // Stable metadata IDs can connect a saved record with refreshed artwork/progress,
        // but never connect a metadata namespace to an arbitrary CMS namespace.
        return a.source == null && b.source == null && a.id.isNotBlank() && a.id == b.id &&
            titleA.isNotBlank() && titleA == titleB
    }

    private fun explicitIds(item: MediaItem): Map<String, String> = item.metadataIds.entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .associate { it.key.trim().lowercase(java.util.Locale.ROOT) to it.value.trim() }

    private fun splitConflictingIds(entries: List<MediaItem>): List<List<MediaItem>> {
        val allIds = entries.flatMap { explicitIds(it).entries }
        val conflictingProviders = allIds.groupBy { it.key }
            .filterValues { ids -> ids.map { it.value }.distinct().size > 1 }.keys.sorted()
        if (conflictingProviders.isEmpty()) return listOf(entries)
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        entries.forEachIndexed { index, item ->
            val ids = explicitIds(item)
            // Missing any conflicting namespace leaves this record ambiguous. Do not
            // attach it arbitrarily to whichever candidate happened to arrive first.
            val key = if (conflictingProviders.any { ids[it] == null }) "ambiguous:$index" else
                "ids:" + conflictingProviders.joinToString("") { provider ->
                    val id = ids.getValue(provider)
                    "${provider.length}:$provider${id.length}:$id"
                }
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.toList()
    }
}
