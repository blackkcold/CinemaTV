package dev.cinematv.data.recommendation

import org.junit.Assert.*
import org.junit.Test

class ContentRecommendationCoreTest {
    private val a = mapOf("genre:a" to 1.0)
    private val b = mapOf("genre:b" to 1.0)
    private fun item(id: String, vector: Map<String, Double> = a, tier: Int = 0) = ContentCandidate(id, vector, tier)
    private fun seed(id: String = "seed", vector: Map<String, Double> = a, weight: Double = 4.0) = ContentEvidence(id, vector, weight)

    @Test fun coldStartKeepsCatalogOrderIncludingZeroMetadataAndSeenTier() {
        val candidates = listOf(item("b", b, 2), item("empty", emptyMap()), item("a"))
        val result = ContentRecommendationCore.rank(candidates, emptyList())
        assertTrue(result.coldStart)
        assertEquals(candidates.map { it.key }, result.items.map { it.key })
        assertTrue(result.items.all { it.trace.selectionPolicy == "catalog-order" && it.trace.selectionScore == 0.0 })
    }
    @Test fun negativeExclusionsWinAtColdStartAndDoNotContributeToProfile() {
        val result = ContentRecommendationCore.rank(listOf(item("bad"), item("good", b)), listOf(seed("bad")), setOf("bad"))
        assertTrue(result.coldStart)
        assertEquals(listOf("good"), result.items.map { it.key })
        assertEquals(1, result.excludedCandidateCount)
    }
    @Test fun centroidHasUnitNormAndExactTagContributions() {
        val result = ContentRecommendationCore.rank(listOf(item("mix", a + b)), listOf(seed("a", a, 4.0), seed("b", b, 1.0)))
        assertEquals(1.0, result.profile.values.sumOf { it * it }, 1e-12)
        assertEquals(4.0, result.profile.getValue("genre:a") / result.profile.getValue("genre:b"), 1e-12)
        val trace = result.items.single().trace
        assertEquals(trace.similarity, trace.contributions.sumOf { it.contribution }, 1e-12)
        assertEquals(trace.relevanceContribution - trace.diversityPenalty, trace.selectionScore, 1e-12)
    }
    @Test fun duplicateEvidenceUsesStrongestWeightNotRepeatedClicks() {
        val once = ContentRecommendationCore.rank(listOf(item("a")), listOf(seed()))
        val repeated = ContentRecommendationCore.rank(listOf(item("a")), List(30) { seed() } + seed(weight = 1.0))
        assertEquals(once.profile, repeated.profile)
        assertEquals(1, repeated.positiveWorkCount)
    }
    @Test fun mmrDiversifiesBalancedInterestsAndReportsActualSelectionPenalty() {
        val result = ContentRecommendationCore.rank(listOf(item("a1"), item("a2"), item("b", b)), listOf(seed("a"), seed("b", b)))
        assertEquals(listOf("a1", "b", "a2"), result.items.map { it.key })
        assertEquals(0.0, result.items[1].trace.diversityPenalty, 1e-12)
        assertEquals(0.2, result.items[2].trace.diversityPenalty, 1e-12)
        assertEquals(2, result.items[2].trace.rankBeforeDiversity)
        assertEquals(3, result.items[2].trace.finalRank)
    }
    @Test fun seenTiersCannotOvertakeUnseenEvenWithStrongerSimilarity() {
        val result = ContentRecommendationCore.rank(listOf(item("watched", a, 2), item("progress", a, 1), item("unseen", b)), listOf(seed()))
        assertEquals(listOf("unseen", "progress", "watched"), result.items.map { it.key })
    }
    @Test fun absentAndMalformedMetadataCannotCreateATasteVector() {
        val bad = mapOf("nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY, "zero" to 0.0, "negative" to -1.0, "" to 2.0)
        val result = ContentRecommendationCore.rank(listOf(item("x", bad)), listOf(seed(vector = bad)))
        assertTrue(result.coldStart)
        assertTrue(result.profile.isEmpty())
        assertTrue(result.items.single().trace.selectionScore.isFinite())
    }
    @Test fun largeFiniteValuesNormalizeWithoutOverflow() {
        val large = mapOf("genre:a" to Double.MAX_VALUE, "genre:b" to Double.MAX_VALUE)
        val result = ContentRecommendationCore.rank(listOf(item("x", large)), listOf(seed(vector = large, weight = Double.MAX_VALUE)))
        assertFalse(result.coldStart)
        assertEquals(1.0, result.items.single().trace.similarity, 1e-12)
        assertTrue(result.profile.values.all { it.isFinite() })
    }
    @Test fun invalidEvidenceWeightsAreIgnored() {
        for (weight in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertTrue(ContentRecommendationCore.rank(listOf(item("x")), listOf(seed(weight = weight))).coldStart)
        }
    }
    @Test fun boundedWorkAndTruncationAreVisible() {
        val policy = RecommendationPolicy(maxCandidates = 2, maxPositiveWorks = 1, maxFeaturesPerWork = 1, maxResults = 1)
        val result = ContentRecommendationCore.rank(listOf(item("a"), item("b"), item("c")), listOf(seed("a", a + b), seed("b", b)), limit = 99, policy = policy)
        assertEquals(1, result.items.size)
        assertEquals(1, result.truncatedCandidateCount)
        assertEquals(1, result.truncatedPositiveWorkCount)
        assertEquals(1, result.profile.size)
    }
    @Test fun exclusionsApplyBeforeCandidateCap() {
        val result = ContentRecommendationCore.rank(listOf(item("blocked"), item("safe")), emptyList(), setOf("blocked"), policy = RecommendationPolicy(maxCandidates = 1))
        assertEquals("safe", result.items.single().key)
        assertEquals(0, result.truncatedCandidateCount)
    }
    @Test fun outputIsDeterministicAndDoesNotRepeatKeys() {
        val input = listOf(item("a"), item("a"), item("b", b))
        val result = ContentRecommendationCore.rank(input, listOf(seed()))
        assertEquals(result, ContentRecommendationCore.rank(input, listOf(seed())))
        assertEquals(2, result.items.size)
    }
    @Test fun emptyAndNonPositiveLimitsAreSafe() {
        assertTrue(ContentRecommendationCore.rank(emptyList(), emptyList()).items.isEmpty())
        for (limit in listOf(0, -1)) assertTrue(ContentRecommendationCore.rank(listOf(item("a")), listOf(seed()), limit = limit).items.isEmpty())
    }
    @Test fun cooperativeCancellationStopsWorkInsteadOfPublishingAStaleResult() {
        var checks = 0
        val candidates = (0 until 2000).map { item("candidate:$it") }
        try {
            ContentRecommendationCore.rank(candidates, listOf(seed()), limit = 50, checkCancelled = {
                if (++checks == 50) throw java.util.concurrent.CancellationException("newer snapshot")
            })
            fail("Cancelled work must not return a snapshot")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals(50, checks)
        }
    }
    @Test fun onlyProvidedMetadataCanContribute() {
        val result = ContentRecommendationCore.rank(listOf(item("missing", b)), listOf(seed()))
        assertEquals(0.0, result.items.single().trace.similarity, 0.0)
        assertTrue(result.items.single().trace.contributions.isEmpty())
    }
}
