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

/**
 * The exclusive rating/ranking choice a search exposes.
 *
 * [Custom] only describes a filter state that this UI cannot produce (a source-only or
 * non-standard threshold filter written by an older path). It exists so an active source
 * filter is never displayed as "all": [Any] means no rating restriction at all.
 */
enum class SearchRatingMode {
    Any,
    TmdbAtLeast8,
    DoubanAtLeast8,
    DoubanTop250,
    DoubanTag,
    Custom,
}

/**
 * Read the actual filter fields instead of guessing from [SearchFilters.ratingSource]:
 * a Douban tag is not "≥ 8", and a selected source without a threshold is not "all".
 */
val SearchFilters.ratingMode: SearchRatingMode
    get() {
        val provider = ratingSource?.takeIf(String::isNotBlank)?.let(RatingPolicy::normalizeProvider)
        if (doubanTag != null) return if (provider == null || provider == "douban")
            SearchRatingMode.DoubanTag else SearchRatingMode.Custom
        if (doubanTopOnly) return if (provider == null || provider == "douban")
            SearchRatingMode.DoubanTop250 else SearchRatingMode.Custom
        if (minRating != null) return when {
            provider == "tmdb" && minRating == 8.0 -> SearchRatingMode.TmdbAtLeast8
            provider == "douban" && minRating == 8.0 -> SearchRatingMode.DoubanAtLeast8
            else -> SearchRatingMode.Custom
        }
        // A provider with no threshold still restricts the result set. Show it as such.
        return if (provider == null) SearchRatingMode.Any else SearchRatingMode.Custom
    }

/** One count per dimension: type, year, rating/ranking/tag. */
val SearchFilters.activeFilterCount: Int
    get() = (if (isTv != null) 1 else 0) +
        (if (!year.isNullOrBlank()) 1 else 0) +
        (if (ratingMode != SearchRatingMode.Any) 1 else 0)

/** One accessible line listing every active dimension; empty when nothing is filtered. */
fun SearchFilters.summary(): String = buildList {
    when (isTv) {
        true -> add("剧集")
        false -> add("电影")
        null -> Unit
    }
    year?.takeIf(String::isNotBlank)?.let { add("年份 $it") }
    when (ratingMode) {
        SearchRatingMode.Any -> Unit
        SearchRatingMode.TmdbAtLeast8 -> add("TMDB ≥ 8")
        SearchRatingMode.DoubanAtLeast8 -> add("豆瓣 ≥ 8")
        SearchRatingMode.DoubanTop250 -> add("豆瓣 TOP250")
        SearchRatingMode.DoubanTag -> add(doubanTag?.let { "豆瓣标签：$it" } ?: "豆瓣标签")
        SearchRatingMode.Custom -> add(customRatingSummary())
    }
}.joinToString(" · ")

private fun SearchFilters.customRatingSummary(): String {
    val provider = ratingSource?.takeIf(String::isNotBlank)?.let(RatingPolicy::normalizeProvider)
    val name = when (provider) {
        "douban" -> "豆瓣"
        "tmdb" -> "TMDB"
        null -> "评分"
        else -> ratingSource?.trim().orEmpty().ifBlank { "评分" }
    }
    val threshold = minRating?.let { " ≥ " + if (it % 1.0 == 0.0) it.toInt().toString()
        else String.format(Locale.ROOT, "%.1f", it) }.orEmpty()
    val top = if (doubanTopOnly) " TOP250" else ""
    return "$name 来源$threshold$top"
}

/** Only this dimension changes; query text and the other dimensions are untouched. */
fun SearchFilters.withType(isTv: Boolean?): SearchFilters = copy(isTv = isTv)

/** Only this dimension changes; query text and the other dimensions are untouched. */
fun SearchFilters.withYear(year: String?): SearchFilters =
    copy(year = year?.takeIf(String::isNotBlank))

/**
 * Set one exclusive rating/ranking choice. [Any] clears the source, threshold, chart and
 * tag together, so no hidden source restriction survives a "全部" selection.
 */
fun SearchFilters.withRatingMode(mode: SearchRatingMode, doubanTag: String? = null): SearchFilters =
    when (mode) {
        SearchRatingMode.Any -> copy(minRating = null, ratingSource = null,
            doubanTopOnly = false, doubanTag = null)
        SearchRatingMode.TmdbAtLeast8 -> copy(minRating = 8.0, ratingSource = "TMDB",
            doubanTopOnly = false, doubanTag = null)
        SearchRatingMode.DoubanAtLeast8 -> copy(minRating = 8.0, ratingSource = "Douban",
            doubanTopOnly = false, doubanTag = null)
        SearchRatingMode.DoubanTop250 -> copy(minRating = null, ratingSource = "Douban",
            doubanTopOnly = true, doubanTag = null)
        SearchRatingMode.DoubanTag -> doubanTag?.takeIf(String::isNotBlank)?.let { tag ->
            copy(minRating = null, ratingSource = "Douban", doubanTopOnly = false, doubanTag = tag)
        } ?: copy(minRating = null, ratingSource = null, doubanTopOnly = false, doubanTag = null)
        // Not a UI choice: keep the current legacy state rather than inventing a filter.
        SearchRatingMode.Custom -> this
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

/**
 * True when the user has not asked for Douban ratings, a Douban chart or a Douban tag.
 *
 * Such a search must not present a Douban score or Top 250 rank as if it were neutral product
 * data, and must not let a Douban record stand in as the neutral representative.
 */
val SearchFilters.isNeutralPresentation: Boolean
    get() = ratingSource.isNullOrBlank() && minRating == null && !doubanTopOnly && doubanTag == null

/**
 * One displayed group. [groupKey] is the stable display/focus identity; empty only outside search.
 *
 * [item] is the **authoritative** record for this group and is never altered for presentation
 * reasons: it is what DETAIL opens, what the library persists and what carries licensing. A
 * display-only restriction (for example hiding a Douban score in a neutral search) belongs in
 * [presentation] alone. Stripping fields from [item] would flow into
 * `LibreTvRepository.cacheItem` → `MediaSnapshotMerger`, where a retained catalog attribution
 * makes the stripped record look like an authoritative metadata update and permanently erases
 * the stored rating, rank and rating licence.
 */
data class SearchHit(
    val item: MediaItem,
    val alternatives: List<MediaItem>,
    val origins: Set<SearchOrigin>,
    val matchScore: Int,
    val groupKey: String = "",
    /** Display-only copy of [item]; equals [item] unless a display rule must hide a field. */
    val presentation: MediaItem = item,
) {
    /** The key UI may use; a legacy/blank group key falls back to the member identity. */
    val displayKey: String get() = groupKey.ifBlank { MediaDomainMapper.entryId(item) }
}

/**
 * Drop the badge fields a neutral search may not show, and nothing else.
 *
 * Attribution, aliases, cast, artwork, description and the catalog licence that govern them stay
 * intact: they are required credit and still-valid licensed content, not a rating badge. This is
 * deliberately narrower than [withoutDoubanHomeBadges], which also strips catalog attribution.
 */
internal fun MediaItem.withoutDoubanBadges(): MediaItem {
    if (RatingPolicy.normalizeProvider(ratingSource) != "douban" && doubanTopRank == null) return this
    return copy(rating = "", ratingSource = "", doubanTopRank = null,
        ratingLicenseExpiresAt = 0L, ratingPublic = false)
}

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
        val neutral = filters.isNeutralPresentation
        return SearchAggregator.searchGroups(origins.keys.toList()).mapNotNull { group ->
            // A library alias and a catalog rating may describe the same safely
            // grouped work. Combine searchable text, never the rating/provenance.
            //
            // A trusted-ID group can hold two translations of one work: the representative's title
            // may be "A" while a member's title is "B". Searching "B" must still find the group, so
            // each member title joins the match text as an alias. This copy is only passed to
            // [relevance] and then discarded — the displayed record and its provenance are
            // untouched, and only members that already passed the licence filter are present here.
            val searchable = group.item.copy(
                aliases = (group.alternatives.flatMap { it.aliases } +
                    group.alternatives.map { it.title })
                    .map(String::trim).filter(String::isNotBlank).distinct(),
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
            // A neutral search must not let a Douban member stand in as the neutral
            // representative: that would show a public Douban score/rank as ordinary product
            // data. Source rule outranks artwork, so a clean member is chosen even when only a
            // Douban member happens to have a poster; a missing poster is preferable to a
            // misattributed score. Only when no clean member exists at all is a Douban record
            // kept, with its badges stripped.
            fun hasDoubanBadge(item: MediaItem) =
                RatingPolicy.provider(item) == "douban" || item.doubanTopRank != null
            fun pick(candidates: List<Pair<MediaItem, Int>>) =
                candidates.filter { it.first.poster.isNotBlank() }.maxByOrNull { it.second }
                    ?: candidates.maxByOrNull { it.second }
            val preferred = (if (neutral) {
                val clean = matches.filterNot { hasDoubanBadge(it.first) }
                pick(clean) ?: pick(matches)
            } else pick(matches)) ?: return@mapNotNull null
            // Two records, on purpose: `item` is authoritative and keeps every field it arrived
            // with, so opening it or caching it cannot rewrite stored licensing or a rating.
            // `presentation` is the same record minus the badge a neutral search may not show.
            val authoritative = SearchAggregator.withSourceCount(preferred.first, group.alternatives)
            SearchHit(
                authoritative,
                group.alternatives,
                group.alternatives.flatMap { origins[it].orEmpty() }.toCollection(linkedSetOf()),
                // The group's relevance, not the chosen member's: picking a different
                // representative must not reorder the result list.
                matches.maxOf { it.second },
                group.groupKey,
                if (neutral) authoritative.withoutDoubanBadges() else authoritative
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
