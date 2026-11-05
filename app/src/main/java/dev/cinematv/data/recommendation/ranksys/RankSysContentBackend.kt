package dev.cinematv.data.recommendation.ranksys

import dev.cinematv.data.recommendation.*
import es.uam.eps.ir.ranksys.core.feature.SimpleFeatureData
import es.uam.eps.ir.ranksys.novdiv.distance.CosineFeatureItemDistanceModel
import org.jooq.lambda.tuple.Tuple
import kotlin.math.abs

/** Real published RankSys cosine + MMR, with an explicitly versioned numeric policy. */
object RankSysContentBackend : ContentRankingBackend {
    override val name = "RankSys 0.4.3 + CinemaTV 数值保护"
    override val version: String = GuardedRankSys.VERSION
    override val maxResults = 50
    override fun rank(profile: Map<String, Double>, candidates: List<CoreScoredCandidate>, diversityWeight: Double,
        limit: Int, checkCancelled: () -> Unit): List<CoreDecision> {
        if (candidates.isEmpty() || limit == 0) return emptyList()
        // Profile is a synthetic internal feature vector, never a provider identity or outbound data.
        val profileKey = "profile"
        require(candidates.none { it.key == profileKey })
        val triples = profile.map { (tag, value) -> Tuple.tuple(profileKey, tag, value) } + candidates.flatMap { item ->
            checkCancelled()
            item.vector.map { (tag, value) -> Tuple.tuple(item.key, tag, value) }
        }
        val distance = CosineFeatureItemDistanceModel<String, String>(SimpleFeatureData.load(triples.stream()))
        val fromProfile = distance.dist(profileKey)
        val input = candidates.map { item ->
            checkCancelled()
            val actual = (1 - fromProfile.applyAsDouble(item.key)).coerceIn(0.0, 1.0)
            // Sparse contribution terms shown in the UI must agree with the actual core cosine.
            require(actual.isFinite() && abs(actual - item.similarity) < 1e-9)
            GuardedRankSys.Candidate(item.key, actual, item.vector)
        }
        return GuardedRankSys.rank(input, diversityWeight, limit, Runnable(checkCancelled)).map { row ->
            CoreDecision(row.key, row.relevanceContribution, row.selectionScore,
                CoreDecisionTrace(row.relevanceZ, row.meanDistance, row.diversityZ, row.diversityContribution))
        }
    }
}
