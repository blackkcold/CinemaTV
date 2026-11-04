package dev.cinematv.data

import kotlin.math.max

/** Explicit local signals only; no server/user-tracking profile is needed. */
data class RecommendationSignal(
    val item: MediaItem,
    val status: LibraryStatus? = null,
    val favorite: Boolean = false,
    val notInterested: Boolean = false
)

data class Recommendation(val item: MediaItem, val reason: String, val score: Double)

/** Local, deterministic and explainable. Negative feedback is never a cold-start fallback. */
object RecommendationEngine {
    fun recommend(
        catalog: List<MediaItem>,
        signals: List<RecommendationSignal>,
        limit: Int = 20,
        nowMs: Long = System.currentTimeMillis()
    ): List<Recommendation> {
        if (limit <= 0) return emptyList()
        val negative = signals.filter { it.notInterested || it.status == LibraryStatus.DROPPED }
        val positive = signals.filter { signal ->
            CatalogLicensePolicy.isAvailable(signal.item, nowMs) &&
                weight(signal) > 0 && negative.none { SearchAggregator.sameWork(it.item, signal.item) }
        }.groupBy { signal -> SearchAggregator.workKey(signal.item)
            ?: "${signal.item.isTv}|${LibraryIdentity.key(signal.item)}|${SearchText.compact(signal.item.title)}" }
            .values.map { group -> group.maxBy { weight(it) } }
        val candidates = SearchAggregator.groupWithAlternatives(catalog.filter { CatalogLicensePolicy.isAvailable(it, nowMs) })
            .filter { group -> negative.none { signal -> group.alternatives.any { SearchAggregator.sameWork(it, signal.item) } } }
            .map { group -> candidate(group.item, positive, signals) }.toMutableList()
        // The catalog's editorial/fallback order is intentional. Without a local
        // positive profile, filtering must not invent a personalized ranking.
        if (positive.isEmpty()) return candidates.take(limit.coerceAtMost(200))
        val output = mutableListOf<Recommendation>()
        val genreCounts = mutableMapOf<String, Int>()
        val typeCounts = mutableMapOf<Boolean?, Int>()
        while (output.size < limit.coerceAtMost(200) && candidates.isNotEmpty()) {
            // Stable maxBy tie-breaking preserves catalog order for an empty profile.
            val next = candidates.maxBy { candidate ->
                candidate.score - categories(candidate.item).sumOf { (genreCounts[it] ?: 0) * 2.0 } -
                    (typeCounts[candidate.item.isTv.takeIf { candidate.item.mediaTypeKnown }] ?: 0) * 1.5
            }
            candidates.remove(next)
            output += next
            categories(next.item).forEach { genreCounts[it] = (genreCounts[it] ?: 0) + 1 }
            val type = next.item.isTv.takeIf { next.item.mediaTypeKnown }
            typeCounts[type] = (typeCounts[type] ?: 0) + 1
        }
        return output
    }

    private fun candidate(
        item: MediaItem,
        signals: List<RecommendationSignal>,
        allSignals: List<RecommendationSignal>
    ): Recommendation {
        // Deduplicating preference weights must not discard a separate WATCHED
        // status or a newer progress record for a favorite of the same work.
        val ownSignals = allSignals.filter { SearchAggregator.sameWork(item, it.item) }
        val heavilyWatched = ownSignals.any { signal ->
            signal.status == LibraryStatus.WATCHED ||
                (signal.item.durationMs > 0 && signal.item.positionMs.coerceAtLeast(0).toDouble() / signal.item.durationMs >= 0.7)
        }
        val genres = categories(item)
        val actors = item.actors.map(SearchText::compact).filter(String::isNotBlank).toSet()
        val affinities = signals.mapNotNull { signal ->
            if (SearchAggregator.sameWork(item, signal.item)) return@mapNotNull null
            val sharedGenres = genres.intersect(categories(signal.item))
            val sharedActors = actors.intersect(signal.item.actors.map(SearchText::compact).toSet())
            val sameType = item.mediaTypeKnown && signal.item.mediaTypeKnown && item.isTv == signal.item.isTv
            val affinity = weight(signal) * (sharedGenres.size.coerceAtMost(2) * 4.0 +
                sharedActors.size.coerceAtMost(2) * 5.0 + if (sameType) 0.25 else 0.0)
            if (affinity <= 0) return@mapNotNull null
            val signalLabel = when {
                signal.favorite || signal.status == LibraryStatus.FAVORITE -> "收藏"
                signal.status == LibraryStatus.WANT_TO_WATCH -> "想看"
                signal.status == LibraryStatus.WATCHED -> "看过"
                else -> "在看"
            }
            val connection = when {
                sharedActors.isNotEmpty() -> "相同演员"
                sharedGenres.isNotEmpty() -> "相同类型"
                else -> if (item.isTv) "同为剧集" else "同为电影"
            }
            affinity to "因为你${signalLabel}了《${signal.item.title}》 · $connection"
        }.sortedByDescending { it.first }
        val reason = when {
            heavilyWatched -> "目录补充 · 你已看过或接近看完"
            affinities.isNotEmpty() -> affinities.first().second
            ownSignals.any { it.status == LibraryStatus.WANT_TO_WATCH } -> "来自你的想看片单"
            else -> "从当前目录发现 · 本地偏好不足"
        }
        // Completed/heavily watched titles are used only after unseen candidates. A
        // bounded affinity prevents large profiles from defeating the seen penalty.
        val affinity = affinities.take(3).sumOf { it.first }.coerceAtMost(100.0)
        val penalty = when {
            heavilyWatched -> 10_000.0
            ownSignals.any { it.status == LibraryStatus.WATCHING || it.item.positionMs > 0 } -> 200.0
            ownSignals.isNotEmpty() -> 10.0
            else -> 0.0
        }
        return Recommendation(item, reason, affinity - penalty)
    }

    private fun weight(signal: RecommendationSignal): Double {
        if (signal.notInterested || signal.status == LibraryStatus.DROPPED) return 0.0
        val statusWeight = when (signal.status) {
            LibraryStatus.FAVORITE -> 4.0
            LibraryStatus.WATCHING -> 2.5
            LibraryStatus.WANT_TO_WATCH -> 1.5
            LibraryStatus.WATCHED -> 1.0
            else -> 0.0
        }
        return max(if (signal.favorite) 4.0 else statusWeight, if (signal.item.positionMs > 0) 1.0 else 0.0)
    }

    private fun categories(item: MediaItem): Set<String> = SearchText.tokens(item.category)
        .filterNot { it in setOf("电影", "电视剧", "剧集", "movie", "movies", "tv", "series", "未知") }
        .toSet()
}
