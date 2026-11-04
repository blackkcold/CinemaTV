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
    val selectionPolicy: String
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
    val items: List<ContentRankedItem>
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
        policy: RecommendationPolicy = RecommendationPolicy()
    ): ContentRanking {
        val unique = candidates.distinctBy { it.key }
        val eligible = unique.filterNot { it.key in excludedKeys }
        val bounded = eligible.take(policy.maxCandidates)
        val validEvidence = evidence.filter { it.key !in excludedKeys && it.weight.isFinite() && it.weight > 0 }
            .groupBy { it.key }.values.map { group -> group.maxBy { it.weight } }
        val positive = validEvidence.take(policy.maxPositiveWorks)
        val sum = sortedMapOf<String, Double>()
        var usefulWorks = 0
        positive.forEach { signal ->
            val vector = normalize(signal.features, policy.maxFeaturesPerWork)
            if (vector.isNotEmpty()) usefulWorks++
            vector.forEach { (tag, value) -> sum[tag] = (sum[tag] ?: 0.0) + value * signal.weight.coerceAtMost(100.0) }
        }
        val profile = normalize(sum, Int.MAX_VALUE)
        val cold = profile.isEmpty()
        val vectors = bounded.associate { it.key to normalize(it.features, policy.maxFeaturesPerWork) }
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
        while (output.size < limit.coerceIn(0, policy.maxResults) && remaining.isNotEmpty()) {
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
            if (!cold) remaining.forEach { candidate ->
                val overlap = cosine(vectors.getValue(next.key), vectors.getValue(candidate.key))
                redundancy[candidate.key] = maxOf(redundancy.getValue(candidate.key), overlap)
            }
        }
        return ContentRanking(policy, profile.toMap(), usefulWorks, unique.size, bounded.size,
            unique.size - eligible.size, eligible.size - bounded.size,
            validEvidence.size - positive.size, cold, output.toList())
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
