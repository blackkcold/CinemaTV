package dev.cinematv.data.recommendation

import dev.cinematv.data.*
import java.util.Locale

/** Weights are product policy, not fitted parameters or a probability of liking a work. */
object LocalRecommendationWeights {
    const val FAVORITE = 4.0
    const val WATCHING = 2.5
    const val WANT_TO_WATCH = 1.5
    const val WATCHED = 1.0
    const val MEASURED_PROGRESS = 1.0
    const val GENRE = 1.0
    const val ACTOR = 1.25

    fun signal(signal: RecommendationSignal): Double = when {
        signal.notInterested || signal.status == LibraryStatus.DROPPED -> 0.0
        signal.favorite || signal.status == LibraryStatus.FAVORITE -> FAVORITE
        signal.status == LibraryStatus.WATCHING -> WATCHING
        signal.status == LibraryStatus.WANT_TO_WATCH -> WANT_TO_WATCH
        signal.status == LibraryStatus.WATCHED -> WATCHED
        signal.item.positionMs > 0 -> MEASURED_PROGRESS
        else -> 0.0
    }
}

data class LocalRecommendationRow(val item: MediaItem, val reason: String, val trace: ContentTrace)
data class LocalRecommendationSnapshot(
    val asOfMs: Long,
    val ranking: ContentRanking,
    val rows: List<LocalRecommendationRow>,
    val expiredCandidateCount: Int,
    val negativeSignalCount: Int
)

/** Adapts the existing local library. No new profile database or migration is required. */
object LocalRecommendationPipeline {
    private val genericCategories = setOf("电影", "电视剧", "剧集", "movie", "movies", "tv", "series", "未知")

    fun features(item: MediaItem): Map<String, Double> = buildMap {
        SearchText.tokens(item.category).filterNot { it in genericCategories }.distinct().take(32)
            .forEach { put("genre:$it", LocalRecommendationWeights.GENRE) }
        item.actors.map(SearchText::compact).filter(String::isNotBlank).distinct().take(32)
            .forEach { put("actor:$it", LocalRecommendationWeights.ACTOR) }
    }

    /** Buckets narrow comparisons; sameWork still decides identity, including conflicting IDs. */
    private class IdentityContext(items: List<MediaItem>) {
        private fun ids(item: MediaItem) = item.metadataIds.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .associate { it.key.trim().lowercase(Locale.ROOT) to it.value.trim() }
        private val conflicts = items.mapNotNull { item -> SearchAggregator.workKey(item)?.let { it to item } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, group) ->
                group.flatMap { ids(it).entries }.groupBy { it.key }
                    .filterValues { values -> values.map { it.value }.distinct().size > 1 }.keys
            }
        fun matches(a: MediaItem, b: MediaItem): Boolean {
            if (a == b) return true
            if (!SearchAggregator.sameWork(a, b)) return false
            // Stable source identity can survive metadata enrichment, but a missing ID cannot
            // bridge several conflicting works merely through matching title/year.
            val sameSource = (a.source == null && b.source == null) ||
                (a.source?.url?.takeIf(String::isNotBlank) != null && a.source.url == b.source?.url)
            if (sameSource && a.id.isNotBlank() && a.id == b.id) return true
            val namespaces = conflicts[SearchAggregator.workKey(a)].orEmpty() + conflicts[SearchAggregator.workKey(b)].orEmpty()
            val aIds = ids(a)
            val bIds = ids(b)
            return namespaces.all { namespace -> aIds[namespace] != null && aIds[namespace] == bIds[namespace] }
        }

        fun group(items: List<MediaItem>, checkCancelled: () -> Unit): List<SearchResultGroup> =
            SearchAggregator.groupWithAlternatives(items).flatMap { preliminary ->
                // A conflict may exist only in saved feedback, not in today's catalog. Refine
                // catalog and evidence groups using the SAME complete identity context.
                val partitions = mutableListOf<MutableList<MediaItem>>()
                for (item in preliminary.alternatives) {
                    checkCancelled()
                    val existing = partitions.firstOrNull { entries -> entries.all { other ->
                        checkCancelled(); matches(item, other)
                    } }
                    if (existing != null) existing.add(item) else partitions.add(mutableListOf(item))
                }
                partitions.map { entries ->
                    val preferred = entries.firstOrNull { it.poster.isNotBlank() } ?: entries.first()
                    SearchResultGroup(SearchAggregator.withSourceCount(preferred, entries), entries.toList())
                }
            }
    }

    private class SignalIndex(signals: List<RecommendationSignal>, private val identity: IdentityContext) {
        private data class SourceKey(val id: String, val sourceUrl: String?, val hasSource: Boolean)
        private fun source(item: MediaItem) = SourceKey(item.id, item.source?.url?.trim(), item.source != null)
        private val bySource = signals.groupBy { source(it.item) }
        private val byWork = signals.mapNotNull { signal -> SearchAggregator.workKey(signal.item)?.let { it to signal } }
            .groupBy({ it.first }, { it.second })
        fun match(item: MediaItem): List<RecommendationSignal> =
            (bySource[source(item)].orEmpty() + SearchAggregator.workKey(item)?.let { byWork[it] }.orEmpty())
                .distinct().filter { identity.matches(item, it.item) }
    }

    fun rank(
        catalog: List<MediaItem>,
        signals: List<RecommendationSignal>,
        limit: Int = 50,
        nowMs: Long = System.currentTimeMillis(),
        policy: RecommendationPolicy = RecommendationPolicy(),
        checkCancelled: () -> Unit = {}
    ): LocalRecommendationSnapshot {
        checkCancelled()
        val available = catalog.filter { checkCancelled(); CatalogLicensePolicy.isAvailable(it, nowMs) }
        val identity = IdentityContext(available + signals.map { it.item })
        val groups = identity.group(available, checkCancelled)
        val negative = signals.filter { it.notInterested || it.status == LibraryStatus.DROPPED }
        val negatives = SignalIndex(negative, identity)
        val rejected = groups.indices.filter { index -> groups[index].alternatives.any { item ->
            negatives.match(item).isNotEmpty()
        } }.map { "candidate:$it" }.toSet()
        val positive = signals.filter { checkCancelled(); LocalRecommendationWeights.signal(it) > 0 &&
            CatalogLicensePolicy.isAvailable(it.item, nowMs) &&
            negatives.match(it.item).isEmpty() }
        val positiveByItem = positive.groupBy { it.item }
        // Reuse the catalog's conservative identity grouping, including explicit-ID conflict splits.
        val seeds = identity.group(positive.map { it.item }, checkCancelled).mapIndexed { index, group ->
            checkCancelled()
            val matching = group.alternatives.flatMap { positiveByItem[it].orEmpty() }.distinct()
            val strongest = matching.maxBy { LocalRecommendationWeights.signal(it) }
            ContentEvidence("signal:$index", features(strongest.item), LocalRecommendationWeights.signal(strongest))
        }
        val allSignals = SignalIndex(signals, identity)
        val own = groups.map { group -> group.alternatives.flatMap(allSignals::match).distinct() }
        val candidates = groups.mapIndexed { index, group -> ContentCandidate("candidate:$index", features(group.item),
            when {
                own[index].any { it.status == LibraryStatus.WATCHED || (it.item.durationMs > 0 &&
                    it.item.positionMs.coerceAtLeast(0).toDouble() / it.item.durationMs >= 0.7) } -> 2
                own[index].any { it.status == LibraryStatus.WATCHING || it.item.positionMs > 0 } -> 1
                else -> 0
            }) }
        val ranking = ContentRecommendationCore.rank(candidates, seeds, rejected, limit, policy, checkCancelled)
        val rows = ranking.items.map { row ->
            val index = row.key.substringAfter(':').toInt()
            val item = groups[index].item
            val reason = when {
                ranking.coldStart -> "目录原顺序 · 本地类型或演员偏好不足"
                row.trace.seenTier == 2 -> "目录补充 · 你已看过或接近看完"
                row.trace.seenTier == 1 -> "继续发现 · 你正在观看"
                row.trace.contributions.isNotEmpty() -> "因为你的本地偏好 · " + row.trace.contributions.take(2)
                    .joinToString("、") { it.tag.substringAfter(':') }
                own[index].any { it.status == LibraryStatus.WANT_TO_WATCH } -> "来自你的想看片单"
                else -> "目录补充 · 暂无匹配的类型或演员"
            }
            LocalRecommendationRow(item, reason, row.trace)
        }
        return LocalRecommendationSnapshot(nowMs, ranking, rows, catalog.size - available.size, negative.size)
    }
}
