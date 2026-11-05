package dev.cinematv.data.recommendation

import kotlin.math.sqrt

/** Established content-based methods, implemented locally in Kotlin, not a pretrained ML model. */
data class RecommendationPolicy(
    val relevanceWeight: Double = 0.8,
    val maxCandidates: Int = 2_000,
    val maxPositiveWorks: Int = 1_000,
    val maxFeaturesPerWork: Int = 64,
    val maxResults: Int = 200
) {
    init {
        require(relevanceWeight.isFinite() && relevanceWeight in 0.0..1.0)
        require(maxCandidates in 1..10_000 && maxPositiveWorks in 1..5_000)
        require(maxFeaturesPerWork in 1..256 && maxResults in 1..500)
    }
    val version: String get() = "rocchio-cosine-mmr-v1"
}

/** Keys are typed metadata tags (genre: / actor:), never title guesses or latent embeddings. */
data class ContentCandidate(val key: String, val features: Map<String, Double>, val seenTier: Int = 0)
data class ContentEvidence(val key: String, val features: Map<String, Double>, val weight: Double)
data class FeatureContribution(val tag: String, val profileWeight: Double, val candidateWeight: Double) {
    val contribution: Double get() = profileWeight * candidateWeight
}
data class CoreDecisionTrace(val relevanceZ: Double, val meanDistance: Double, val diversityZ: Double,
    val diversityContribution: Double)
data class CoreScoredCandidate(val key: String, val vector: Map<String, Double>, val similarity: Double)
data class CoreDecision(val key: String, val relevanceContribution: Double, val selectionScore: Double,
    val detail: CoreDecisionTrace)
interface ContentRankingBackend {
    val name: String
    val version: String
    val maxResults: Int
    fun rank(profile: Map<String, Double>, candidates: List<CoreScoredCandidate>, diversityWeight: Double,
        limit: Int, checkCancelled: () -> Unit): List<CoreDecision>
}
data class ContentTrace(
    val contributions: List<FeatureContribution>,
    val similarity: Double,
    val relevanceContribution: Double,
    val redundancy: Double,
    val diversityPenalty: Double,
    val selectionScore: Double,
    val seenTier: Int,
    val rankBeforeDiversity: Int,
    val finalRank: Int,
    val selectionPolicy: String,
    val coreDecision: CoreDecisionTrace? = null
)
data class ContentRankedItem(val key: String, val trace: ContentTrace)
data class ContentRanking(
    val policy: RecommendationPolicy,
    val profile: Map<String, Double>,
    val positiveWorkCount: Int,
    val inputCandidateCount: Int,
    val eligibleCandidateCount: Int,
    val excludedCandidateCount: Int,
    val truncatedCandidateCount: Int,
    val truncatedPositiveWorkCount: Int,
    val coldStart: Boolean,
    val items: List<ContentRankedItem>,
    val backendName: String = "Kotlin 基线实现",
    val backendVersion: String = "rocchio-cosine-mmr-v1",
    val backendFallbackReason: String? = null
)

/**
 * Positive Rocchio centroid -> cosine similarity -> greedy maximal marginal relevance.
 * Negative feedback is a hard filter, not a negative vector that can accidentally promote a title.
 * No network, random exploration, native ABI, model download or Android APIs. Run off the UI thread.
 */
object ContentRecommendationCore {
    fun rank(
        candidates: List<ContentCandidate>,
        evidence: List<ContentEvidence>,
        excludedKeys: Set<String> = emptySet(),
        limit: Int = 20,
        policy: RecommendationPolicy = RecommendationPolicy(),
        checkCancelled: () -> Unit = {},
        backend: ContentRankingBackend? = null
    ): ContentRanking {
        checkCancelled()
        val unique = candidates.onEach { checkCancelled() }.distinctBy { it.key }
        val eligible = unique.filterNot { it.key in excludedKeys }
        val bounded = eligible.take(policy.maxCandidates)
        val validEvidence = evidence.filter { checkCancelled(); it.key !in excludedKeys && it.weight.isFinite() && it.weight > 0 }
            .groupBy { it.key }.values.map { group -> group.maxBy { it.weight } }
        val positive = validEvidence.take(policy.maxPositiveWorks)
        val sum = sortedMapOf<String, Double>()
        var usefulWorks = 0
        positive.forEach { signal ->
            checkCancelled()
            val vector = normalize(signal.features, policy.maxFeaturesPerWork)
            if (vector.isNotEmpty()) usefulWorks++
            vector.forEach { (tag, value) -> sum[tag] = (sum[tag] ?: 0.0) + value * signal.weight.coerceAtMost(100.0) }
        }
        val profile = normalize(sum, Int.MAX_VALUE)
        val cold = profile.isEmpty()
        val vectors = bounded.associate { checkCancelled(); it.key to normalize(it.features, policy.maxFeaturesPerWork) }
        val contributions = bounded.associate { candidate -> candidate.key to vectors.getValue(candidate.key)
            .mapNotNull { (tag, value) -> profile[tag]?.let { FeatureContribution(tag, it, value) } }
            .sortedWith(compareByDescending<FeatureContribution> { it.contribution }.thenBy { it.tag }) }
        val similarities = contributions.mapValues { (_, terms) -> terms.sumOf { it.contribution }.coerceIn(0.0, 1.0) }
        val baseOrder = bounded.sortedWith(compareBy<ContentCandidate> { it.seenTier }
            .thenByDescending { similarities.getValue(it.key) })
        val baseRanks = baseOrder.mapIndexed { index, candidate -> candidate.key to index + 1 }.toMap()
        val remaining = bounded.toMutableList()
        val redundancy = bounded.associate { it.key to 0.0 }.toMutableMap()
        val output = mutableListOf<ContentRankedItem>()
        var usedBackend = false
        var fallbackReason: String? = null
        if (!cold && backend != null && policy.relevanceWeight < 1.0) {
            try {
                val result = mutableListOf<ContentRankedItem>()
                val cap = limit.coerceIn(0, minOf(policy.maxResults, backend.maxResults))
                for ((tier, group) in baseOrder.groupBy { it.seenTier }) {
                    checkCancelled()
                    if (result.size >= cap) break
                    val known = group.filter { vectors.getValue(it.key).isNotEmpty() }
                    val decisions = backend.rank(profile, known.map { CoreScoredCandidate(it.key,
                        vectors.getValue(it.key), similarities.getValue(it.key)) }, 1 - policy.relevanceWeight,
                        cap - result.size, checkCancelled)
                    require(decisions.size == minOf(known.size, cap - result.size))
                    require(decisions.map { it.key }.distinct().size == decisions.size)
                    require(decisions.all { decision -> known.any { it.key == decision.key } })
                    for (decision in decisions) {
                        checkCancelled()
                        require(listOf(decision.selectionScore, decision.relevanceContribution,
                            decision.detail.relevanceZ, decision.detail.meanDistance, decision.detail.diversityZ,
                            decision.detail.diversityContribution).all(Double::isFinite))
                        result += ContentRankedItem(decision.key, ContentTrace(contributions.getValue(decision.key),
                            similarities.getValue(decision.key), decision.relevanceContribution, 0.0,
                            -decision.detail.diversityContribution, decision.selectionScore, tier,
                            baseRanks.getValue(decision.key), result.size + 1, "seen-tier-then-ranksys-average-distance", decision.detail))
                    }
                    // Missing metadata is not maximum novelty. Keep such works as explicit fillers
                    // within their viewing tier; they never enter a cosine/variance computation.
                    for (candidate in group.filter { vectors.getValue(it.key).isEmpty() }) {
                        if (result.size >= cap) break
                        result += ContentRankedItem(candidate.key, ContentTrace(emptyList(), 0.0, 0.0, 0.0,
                            0.0, 0.0, tier, baseRanks.getValue(candidate.key), result.size + 1, "missing-features-catalog-order"))
                    }
                }
                output.addAll(result)
                usedBackend = true
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: RuntimeException) {
                // An unavailable/broken optional backend must not blank the catalog. No partial
                // output is published; the dashboard explicitly identifies this baseline fallback.
                fallbackReason = "推荐 core 计算失败，本次使用 Kotlin 基线；未发布部分结果。"
            }
        }
        while (!usedBackend && output.size < limit.coerceIn(0, policy.maxResults) && remaining.isNotEmpty()) {
            checkCancelled()
            // Empty/sparse metadata is not evidence of dislike. Cold start preserves input order.
            val tier = remaining.minOf { it.seenTier }
            val next = if (cold) remaining.first() else remaining.filter { it.seenTier == tier }.maxBy { candidate ->
                policy.relevanceWeight * similarities.getValue(candidate.key) -
                    (1.0 - policy.relevanceWeight) * redundancy.getValue(candidate.key)
            }
            val similarity = similarities.getValue(next.key)
            val repeated = if (cold) 0.0 else redundancy.getValue(next.key)
            val relevance = if (cold) 0.0 else policy.relevanceWeight * similarity
            val penalty = if (cold) 0.0 else (1.0 - policy.relevanceWeight) * repeated
            output += ContentRankedItem(next.key, ContentTrace(
                contributions.getValue(next.key), similarity, relevance, repeated, penalty,
                relevance - penalty, next.seenTier, if (cold) output.size + 1 else baseRanks.getValue(next.key),
                output.size + 1, if (cold) "catalog-order" else "seen-tier-then-mmr"
            ))
            remaining.remove(next)
            if (!cold) remaining.forEachIndexed { index, candidate ->
                if (index % 32 == 0) checkCancelled()
                val overlap = cosine(vectors.getValue(next.key), vectors.getValue(candidate.key))
                redundancy[candidate.key] = maxOf(redundancy.getValue(candidate.key), overlap)
            }
        }
        return ContentRanking(policy, profile.toMap(), usefulWorks, unique.size, bounded.size,
            unique.size - eligible.size, eligible.size - bounded.size,
            validEvidence.size - positive.size, cold, output.toList(),
            if (usedBackend) backend!!.name else if (cold) "目录冷启动" else "Kotlin 基线实现",
            if (usedBackend) backend!!.version else policy.version, fallbackReason)
    }

    private fun normalize(input: Map<String, Double>, limit: Int): Map<String, Double> {
        val values = input.entries.filter { it.key.isNotBlank() && it.value.isFinite() && it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
        val peak = values.maxOfOrNull { it.value } ?: return emptyMap()
        // Scale before squaring so even finite caller-supplied large values cannot overflow the norm.
        val norm = sqrt(values.sumOf { val scaled = it.value / peak; scaled * scaled })
        return values.associate { it.key to ((it.value / peak) / norm) }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double =
        (if (a.size <= b.size) a.entries.sumOf { (tag, weight) -> weight * (b[tag] ?: 0.0) }
        else b.entries.sumOf { (tag, weight) -> weight * (a[tag] ?: 0.0) }).coerceIn(0.0, 1.0)
}
