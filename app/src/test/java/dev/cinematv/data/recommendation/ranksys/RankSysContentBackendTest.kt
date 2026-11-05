package dev.cinematv.data.recommendation.ranksys

import dev.cinematv.data.recommendation.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class RankSysContentBackendTest {
    private val profile = listOf(ContentEvidence("seed", mapOf("genre:x" to 1.0, "genre:y" to 1.0), 4.0))
    private fun candidate(key: String, tag: String, tier: Int = 0) = ContentCandidate(key, mapOf("genre:$tag" to 1.0), tier)
    @Test fun actualRankSysDiversifiesEqualRelevanceAndPublishesItsDecisionTrace() {
        val result = ContentRecommendationCore.rank(listOf(candidate("a", "x"), candidate("b", "x"), candidate("c", "y")),
            profile, limit = 3, backend = RankSysContentBackend)
        assertEquals(listOf("a", "c", "b"), result.items.map { it.key })
        assertTrue(result.backendVersion.startsWith("RankSys-0.4.3"))
        assertNull(result.backendFallbackReason)
        for (item in result.items) {
            val trace = item.trace
            val core = requireNotNull(trace.coreDecision)
            assertTrue(trace.selectionScore.isFinite())
            assertEquals(trace.relevanceContribution + core.diversityContribution, trace.selectionScore, 1e-12)
            assertEquals(trace.contributions.sumOf { it.contribution }, trace.similarity, 1e-12)
        }
    }
    @Test fun coldStartAndMissingFeaturesHaveExplicitFallbackPolicies() {
        val cold = ContentRecommendationCore.rank(listOf(candidate("b", "x"), candidate("a", "y")), emptyList(), backend = RankSysContentBackend)
        assertEquals(listOf("b", "a"), cold.items.map { it.key })
        assertTrue(cold.items.all { it.trace.coreDecision == null && it.trace.selectionPolicy == "catalog-order" })
        val mixed = ContentRecommendationCore.rank(listOf(ContentCandidate("missing", emptyMap()), candidate("known", "x")), profile,
            backend = RankSysContentBackend)
        assertEquals(listOf("known", "missing"), mixed.items.map { it.key })
        assertEquals("missing-features-catalog-order", mixed.items.last().trace.selectionPolicy)
    }
    @Test fun viewingTiersAndHardExclusionsRemainApplicationPolicy() {
        val result = ContentRecommendationCore.rank(listOf(candidate("seen", "x", 2), candidate("watching", "y", 1),
            candidate("unseen", "y"), candidate("dropped", "x")), profile, setOf("dropped"), backend = RankSysContentBackend)
        assertEquals(listOf("unseen", "watching", "seen"), result.items.map { it.key })
        assertEquals(1, result.excludedCandidateCount)
        assertTrue(result.items.all { it.trace.coreDecision != null })
    }
    @Test fun backendFailureDiscardsPartialOutputAndIsVisible() {
        val broken = object : ContentRankingBackend by RankSysContentBackend {
            override fun rank(profile: Map<String, Double>, candidates: List<CoreScoredCandidate>, diversityWeight: Double,
                limit: Int, checkCancelled: () -> Unit): List<CoreDecision> = error("synthetic backend failure")
        }
        val input = listOf(candidate("a", "x"), candidate("b", "y"))
        val result = ContentRecommendationCore.rank(input, profile, backend = broken)
        val baseline = ContentRecommendationCore.rank(input, profile)
        assertEquals(baseline.items, result.items)
        assertNotNull(result.backendFallbackReason)
        assertEquals("Kotlin 基线实现", result.backendName)
    }
    @Test fun cancellationNeverBecomesAQuietFallback() {
        val cancelled = CancellationException("synthetic cancellation")
        val backend = object : ContentRankingBackend by RankSysContentBackend {
            override fun rank(profile: Map<String, Double>, candidates: List<CoreScoredCandidate>, diversityWeight: Double,
                limit: Int, checkCancelled: () -> Unit): List<CoreDecision> = throw cancelled
        }
        try { ContentRecommendationCore.rank(listOf(candidate("a", "x")), profile, backend = backend); fail("Cancellation swallowed") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }
}
