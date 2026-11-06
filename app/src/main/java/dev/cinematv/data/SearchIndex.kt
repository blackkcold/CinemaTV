package dev.cinematv.data

import java.text.Normalizer
import java.util.Locale

enum class SearchOrigin(val title: String) {
    LIBRARY("本地片库"), CATALOG("推荐目录"), LIBRE_TV("LibreTV 片源")
}

/** Nullable/blank fields mean no restriction. Scores use a 0–10 scale. */
data class SearchFilters(
    val isTv: Boolean? = null,
    val year: String? = null,
    val minRating: Double? = null,
    val ratingSource: String? = null,
    val doubanTopOnly: Boolean = false,
    /** Curated public tag list; null means no tag list is active. */
    val doubanTag: String? = null
) {
    fun matches(item: MediaItem, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!CatalogLicensePolicy.isAvailable(item, nowMs)) return false
        if (isTv != null && (!item.mediaTypeKnown || item.isTv != isTv)) return false
        if (!year.isNullOrBlank() && item.year.trim() != year.trim()) return false
        val provider = ratingSource?.takeIf(String::isNotBlank)?.let(RatingPolicy::normalizeProvider)
        if (provider != null && RatingPolicy.provider(item) != provider) return false
        if (provider == "douban" && !RatingPolicy.hasDoubanMetadata(item, nowMs)) return false
        if (minRating != null) {
            if (!minRating.isFinite() || minRating !in 0.0..10.0) return false
            val score = RatingPolicy.score(item, nowMs) ?: return false
            if (score < minRating) return false
        }
        return !doubanTopOnly || RatingPolicy.isDoubanTop(item, nowMs)
    }
}

/** Zero means ordinary non-licensed source data; an attached license must be current. */
object CatalogLicensePolicy {
    fun isAvailable(item: MediaItem, nowMs: Long = System.currentTimeMillis()): Boolean =
        item.catalogLicenseExpiresAt == 0L ||
            (item.catalogLicenseExpiresAt > 0L && item.catalogLicenseExpiresAt > nowMs)
}

/**
 * Provenance is supplied by the repository's trusted metadata decoder, never inferred
 * from a /douban endpoint, a title, a score's magnitude or the order of a result list.
 * A rank is valid only for an explicitly licensed and unexpired Douban Top 250 record.
 */
object RatingPolicy {
    fun normalizeProvider(value: String): String = when (val normalized = value.trim().lowercase(Locale.ROOT)) {
        "豆瓣" -> "douban"
        "themoviedb", "the movie database" -> "tmdb"
        else -> normalized
    }

    fun provider(item: MediaItem): String = normalizeProvider(item.ratingSource)

    fun hasLicensedDoubanMetadata(item: MediaItem, nowMs: Long = System.currentTimeMillis()): Boolean =
        CatalogLicensePolicy.isAvailable(item, nowMs) && provider(item) == "douban" &&
            item.ratingLicenseExpiresAt > 0 && item.ratingLicenseExpiresAt > nowMs

    /**
     * A public-page score carries explicit provenance instead of a licence declaration.
     * It is still a Douban score, so it must never be presented as another provider's.
     */
    fun hasDoubanMetadata(item: MediaItem, nowMs: Long = System.currentTimeMillis()): Boolean =
        CatalogLicensePolicy.isAvailable(item, nowMs) && provider(item) == "douban" &&
            (item.ratingPublic || (item.ratingLicenseExpiresAt > 0 && item.ratingLicenseExpiresAt > nowMs))

    fun score(item: MediaItem, nowMs: Long = System.currentTimeMillis()): Double? {
        if (!CatalogLicensePolicy.isAvailable(item, nowMs)) return null
        if (provider(item) == "libretv") return null // A transport is not a rating authority.
        if (provider(item) == "douban" && !hasDoubanMetadata(item, nowMs)) return null
        return item.rating.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 }
    }

    fun isDoubanTop(item: MediaItem, nowMs: Long = System.currentTimeMillis()): Boolean =
        hasDoubanMetadata(item, nowMs) && item.doubanTopRank?.let { it in 1..250 } == true
}

data class SearchHit(
    val item: MediaItem,
    val alternatives: List<MediaItem>,
    val origins: Set<SearchOrigin>,
    val matchScore: Int
)

/**
 * Pure in-process search across staged library, catalog and LibreTV snapshots.
 * Call once with local data, then again when remote results arrive. No network,
 * history upload, shared state or fallback that loosens the requested filters.
 */
object SearchIndex {
    fun search(
        query: String,
        library: List<MediaItem> = emptyList(),
        catalog: List<MediaItem> = emptyList(),
        remote: List<MediaItem> = emptyList(),
        filters: SearchFilters = SearchFilters(),
        limit: Int = if (filters.doubanTopOnly) 500 else 200,
        nowMs: Long = System.currentTimeMillis()
    ): List<SearchHit> {
        if (limit <= 0) return emptyList()
        data class Snapshot(val item: MediaItem, val origins: Set<SearchOrigin>)
        val snapshots = linkedMapOf<Any, Snapshot>()
        fun add(items: List<MediaItem>, origin: SearchOrigin) {
            items.filter { CatalogLicensePolicy.isAvailable(it, nowMs) }
                .forEach { item ->
                    // Provider IDs belong to their exact source origin. A refreshed
                    // year/season is a new snapshot, not a second clickable listing.
                    val sourceUrl = item.source?.url?.takeIf(String::isNotBlank)
                    val key: Any = when {
                        sourceUrl != null && item.id.isNotBlank() -> sourceUrl to item.id
                        item.source == null && item.id.isNotBlank() ->
                            // Home and query responses can refresh the same catalog entry's
                            // title/year. Replace the snapshot without mixing metadata bundles.
                            // Provider-qualified entry IDs, not providerId alone, scope works;
                            // unknown type must not silently become a movie or a series.
                            MediaDomainMapper.entryId(item)
                        else -> item
                    }
                    val previous = snapshots[key]
                    val localSnapshot = previous != null && SearchOrigin.LIBRARY in previous.origins && origin != SearchOrigin.LIBRARY
                    val compatible = previous != null && SearchAggregator.sameWork(previous.item, item)
                    val old = previous?.item
                    val merged = if (localSnapshot && compatible && old != null) item.copy(
                        // Never detach aliases from a licensed catalog's expiry.
                        aliases = if (old.catalogLicenseExpiresAt == 0L && old.catalogProvider.isBlank())
                            (item.aliases + old.aliases).distinct() else item.aliases,
                        isTv = if (!item.mediaTypeKnown && old.catalogLicenseExpiresAt == 0L && old.catalogProvider.isBlank())
                            old.isTv else item.isTv,
                        mediaTypeKnown = item.mediaTypeKnown ||
                            (old.mediaTypeKnown && old.catalogLicenseExpiresAt == 0L && old.catalogProvider.isBlank()),
                        positionMs = old.positionMs,
                        durationMs = old.durationMs,
                        lastEpisode = old.lastEpisode
                    ) else item
                    snapshots[key] = Snapshot(merged, previous?.origins.orEmpty() + origin)
                }
        }
        add(library, SearchOrigin.LIBRARY)
        add(catalog, SearchOrigin.CATALOG)
        add(remote, SearchOrigin.LIBRE_TV)
        val origins = snapshots.values.associate { it.item to it.origins }
        val tokens = SearchText.tokens(query)
        // Punctuation alone is an empty query, rather than an accidental match-all token.
        val phrase = SearchText.compact(query)
        return SearchAggregator.groupWithAlternatives(origins.keys.toList()).mapNotNull { group ->
            // A library alias and a catalog rating may describe the same safely
            // grouped work. Combine searchable text, never the rating/provenance.
            val searchable = group.item.copy(
                aliases = group.alternatives.flatMap { it.aliases }.distinct(),
                actors = group.alternatives.flatMap { it.actors }.distinct()
            )
            val groupRelevance = relevance(searchable, phrase, tokens) ?: return@mapNotNull null
            val matches = group.alternatives.mapNotNull candidate@ { item ->
                if (!filters.matches(item, nowMs)) return@candidate null
                val originBoost = when {
                    origins[item]?.contains(SearchOrigin.LIBRARY) == true -> 30
                    origins[item]?.contains(SearchOrigin.CATALOG) == true -> 10
                    else -> 0
                }
                item to groupRelevance + originBoost
            }
            val best = matches.maxByOrNull { it.second } ?: return@mapNotNull null
            SearchHit(
                SearchAggregator.withSourceCount(best.first, group.alternatives),
                group.alternatives,
                group.alternatives.flatMap { origins[it].orEmpty() }.toCollection(linkedSetOf()),
                best.second
            )
        }.let { hits ->
            if (filters.doubanTopOnly) hits.sortedWith(
                compareBy<SearchHit> { it.item.doubanTopRank ?: Int.MAX_VALUE }.thenByDescending { it.matchScore }
            ) else hits.sortedByDescending { it.matchScore }
        }.take(limit.coerceAtMost(1_000))
    }

    private fun relevance(item: MediaItem, phrase: String, tokens: List<String>): Int? {
        if (tokens.isEmpty()) return 0
        val title = SearchText.compact(item.title)
        val aliases = item.aliases.map(SearchText::compact).filter(String::isNotBlank)
        val actors = item.actors.map(SearchText::compact).filter(String::isNotBlank)
        val fields = listOf(title) + aliases + actors
        if (!tokens.all { token -> fields.any { token in it } }) return null
        val phraseScore = when {
            title == phrase -> 1_000
            phrase in aliases -> 900
            title.startsWith(phrase) -> 800
            aliases.any { it.startsWith(phrase) } -> 700
            phrase in title -> 600
            aliases.any { phrase in it } -> 500
            phrase in actors -> 450
            actors.any { phrase in it } -> 400
            else -> 200
        }
        return tokens.fold(phraseScore) { score, token ->
            score + when {
                token in title -> 30
                aliases.any { token in it } -> 20
                else -> 10
            }
        }
    }
}

internal object SearchText {
    fun compact(value: String): String = MediaIdentity.normalizedTitle(value)

    fun tokens(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).split(Regex("[\\p{P}\\p{Z}\\s]+"))
        .map(::compact).filter(String::isNotBlank).distinct()
}
