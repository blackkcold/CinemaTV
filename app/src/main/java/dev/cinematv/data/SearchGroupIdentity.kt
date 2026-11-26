package dev.cinematv.data

/**
 * How much evidence a display group rests on. It is a description of the grouping key, not a
 * playability or availability claim.
 */
internal enum class SearchGroupStrength {
    /** A trusted external ID (TMDB/IMDb/Douban) plus a non-conflicting type and season. */
    STRONG_ID,

    /** Normalized title + known type + four-digit year (+ known season for a series). */
    COMPOSITE,

    /** A single provider listing that proves nothing about another source. */
    SOURCE,
}

internal data class SearchGroupKey(val value: String, val strength: SearchGroupStrength)

/**
 * Stable cross-source identity for **search display groups only**.
 *
 * A display group may merge records; playback identity, progress and source preference keep using
 * [MediaDomainMapper]. Unknown information therefore stays split:
 *
 * - a blank title never forms a group;
 * - a series without a known season never gets a cross-source key (a series-level ID is not a
 *   season ID, and "season unknown" on both sides is still no proof of the same season);
 * - a missing type never joins a movie or a series;
 * - a film and a series with the same title and year stay apart;
 * - the key never contains poster, source order or first-seen order.
 *
 * Two evidence paths exist and they are combined, not ranked:
 *
 * 1. [resolve] gives the record's own key — title+type+year(+season) when a year is known, else a
 *    trusted ID. This is what lets an ID-less provider listing join an ID-bearing catalog record.
 * 2. Records sharing one trusted ID are the same work even when the titles are different
 *    translations, so the aggregator unions those buckets and [canonicalKey] then reports the
 *    translation-independent ID key for the merged group.
 */
internal object SearchGroupIdentity {
    private val YEAR = Regex("[12][0-9]{3}")

    /** The record's own evidence key. Null means "nothing proves another source is the same". */
    fun resolve(item: MediaItem): SearchGroupKey? {
        val title = MediaIdentity.normalizedTitle(item.title)
        if (title.isBlank()) return null
        if (!item.mediaTypeKnown) return null
        // A season-level work cannot be proven equal from a work-level ID or from two unknown
        // seasons. Until a season-specific trusted ID exists, stay separate.
        if (item.isTv && item.seasonNumber == null) return null
        val season = if (item.isTv) item.seasonNumber else null
        return compositeKey(item, title, season) ?: strongIdKey(item, season)
    }

    /** A stable per-listing key for records that prove nothing about another source. */
    fun orphanKey(item: MediaItem): SearchGroupKey =
        SearchGroupKey("orphan:" + MediaDomainMapper.entryId(item), SearchGroupStrength.SOURCE)

    /** The trusted ID of one record, namespaced; used to union translations of one work. */
    internal data class TrustedIdentity(val namespace: String, val id: String, val type: String)

    internal fun trustedIdentity(item: MediaItem): TrustedIdentity? {
        if (!item.mediaTypeKnown) return null
        if (item.isTv && item.seasonNumber == null) return null
        val trusted = MediaIdentity.trustedId(item) ?: return null
        val type = if (item.isTv) "tv:${item.seasonNumber}" else "movie"
        return TrustedIdentity(trusted.first, trusted.second, type)
    }

    internal fun trustedKey(item: MediaItem): String? = trustedIdentity(item)?.let {
        "id:${it.namespace}:${it.type}:${it.id}"
    }

    /**
     * The key a merged group publishes.
     *
     * A group that contains trusted IDs always reports the ID key: it is independent of the
     * language a provider happened to use, so a later batch that adds a translated title cannot
     * change the key. Namespace order and the ID value decide which ID wins, never arrival order.
     * Without an ID the smallest composite key wins, which is likewise order-independent.
     */
    fun canonicalKey(members: List<MediaItem>): SearchGroupKey? {
        val order = MediaIdentity.TRUSTED_ID_NAMESPACES.withIndex().associate { it.value to it.index }
        val chosen = members.mapNotNull { trustedIdentity(it) }.minWithOrNull(
            compareBy({ order[it.namespace] ?: Int.MAX_VALUE }, { it.id }, { it.type }))
        if (chosen != null) {
            return SearchGroupKey("id:${chosen.namespace}:${chosen.type}:${chosen.id}",
                SearchGroupStrength.STRONG_ID)
        }
        val own = members.mapNotNull { resolve(it) }
        val composite = own.filter { it.strength == SearchGroupStrength.COMPOSITE }
            .minByOrNull { it.value }
        if (composite != null) return composite
        return own.minByOrNull { it.value }
    }

    private fun compositeKey(item: MediaItem, title: String, season: Int?): SearchGroupKey? {
        val year = item.year.trim()
        if (!YEAR.matches(year)) return null
        val type = if (item.isTv) "tv:$season" else "movie"
        return SearchGroupKey("work:$type:$year:$title", SearchGroupStrength.COMPOSITE)
    }

    private fun strongIdKey(item: MediaItem, season: Int?): SearchGroupKey? {
        val trusted = MediaIdentity.trustedId(item) ?: return null
        val type = if (item.isTv) "tv:$season" else "movie"
        return SearchGroupKey("id:${trusted.first}:$type:${trusted.second}", SearchGroupStrength.STRONG_ID)
    }
}
