package dev.cinematv.data.recommendation

import dev.cinematv.data.*
import org.junit.Assert.*
import org.junit.Test

class LocalRecommendationPipelineTest {
    private fun film(id: String, genre: String = "科幻") = MediaItem(id, "Title $id", year = "2020", category = genre)
    private val sourceA = TvSource("a", "A", "https://a.example")
    private val sourceB = TvSource("b", "B", "https://b.example")

    @Test fun favoritesStatusesAndProgressUseExistingSignals() {
        val seed = film("seed")
        for (signal in listOf(RecommendationSignal(seed, favorite = true),
            RecommendationSignal(seed, LibraryStatus.WANT_TO_WATCH), RecommendationSignal(seed, LibraryStatus.WATCHED),
            RecommendationSignal(seed.copy(positionMs = 10)))) {
            val result = LocalRecommendationPipeline.rank(listOf(film("other", "喜剧"), film("match")), listOf(signal))
            assertEquals("match", result.rows.first().item.id)
            assertEquals("genre:科幻", result.rows.first().trace.contributions.single().tag)
        }
    }
    @Test fun actorTagsNormalizeWithoutGuessingMissingGenresOrType() {
        val seed = film("seed", "电影").copy(actors = listOf("ＡＣＴＯＲ ＯＮＥ"))
        val match = film("match", "").copy(actors = listOf("actor one"))
        val result = LocalRecommendationPipeline.rank(listOf(film("unknown", "电影"), match), listOf(RecommendationSignal(seed, favorite = true)))
        assertEquals("match", result.rows.first().item.id)
        assertEquals(setOf("actor:actorone"), result.ranking.profile.keys)
    }
    @Test fun droppedAndNotInterestedOverrideFavoritesAcrossKnownSources() {
        val rejected = film("a").copy(source = sourceA)
        val duplicate = rejected.copy(id = "b", source = sourceB)
        for (negative in listOf(RecommendationSignal(rejected, notInterested = true), RecommendationSignal(rejected, LibraryStatus.DROPPED))) {
            val result = LocalRecommendationPipeline.rank(listOf(duplicate), listOf(RecommendationSignal(rejected, favorite = true), negative))
            assertTrue(result.rows.isEmpty())
            assertTrue(result.ranking.coldStart)
        }
    }
    @Test fun unknownIdentityAndConflictingIdsNeverSpreadRejection() {
        val seed = film("seed").copy(source = sourceA, year = "")
        val unknown = seed.copy(id = "other", source = sourceB)
        assertEquals(1, LocalRecommendationPipeline.rank(listOf(unknown), listOf(RecommendationSignal(seed, notInterested = true))).rows.size)
        val explicit = film("seed").copy(metadataIds = mapOf("tmdb" to "1"))
        val conflicting = explicit.copy(id = "other", metadataIds = mapOf("tmdb" to "2"))
        assertEquals(1, LocalRecommendationPipeline.rank(listOf(conflicting), listOf(RecommendationSignal(explicit, notInterested = true))).rows.size)
    }
    @Test fun expiredMetadataCannotTrainOrAppearButRejectionSurvivesExpiry() {
        val expired = film("expired").copy(catalogLicenseExpiresAt = 999)
        val result = LocalRecommendationPipeline.rank(listOf(expired, film("safe")), listOf(RecommendationSignal(expired, favorite = true)), nowMs = 1_000)
        assertTrue(result.ranking.coldStart)
        assertEquals(1, result.expiredCandidateCount)
        assertEquals("safe", result.rows.single().item.id)
        assertTrue(LocalRecommendationPipeline.rank(listOf(expired.copy(catalogLicenseExpiresAt = 2_000)), listOf(RecommendationSignal(expired, notInterested = true)), nowMs = 1_000).rows.isEmpty())
    }
    @Test fun watchedStateIsNotLostWhenSameWorkIsAlsoFavorite() {
        val seen = film("seen")
        val result = LocalRecommendationPipeline.rank(listOf(seen, film("unseen", "喜剧")), listOf(
            RecommendationSignal(seen, favorite = true), RecommendationSignal(seen, LibraryStatus.WATCHED)))
        assertEquals("unseen", result.rows.first().item.id)
        assertEquals(2, result.rows.last().trace.seenTier)
        assertEquals(1, result.ranking.positiveWorkCount)
    }
    @Test fun unknownDurationDoesNotInventCompletion() {
        val current = film("playing").copy(positionMs = 100, durationMs = 0)
        assertEquals(1, LocalRecommendationPipeline.rank(listOf(current), listOf(RecommendationSignal(current))).rows.single().trace.seenTier)
    }
    @Test fun publicRatingsAndUnprovenPopularityAreNotPersonalTaste() {
        val seed = RecommendationSignal(film("seed"), favorite = true)
        val items = listOf(film("a"), film("b", "喜剧"))
        val baseline = LocalRecommendationPipeline.rank(items, listOf(seed), nowMs = 1)
        val altered = LocalRecommendationPipeline.rank(items.map { it.copy(rating = "10", ratingSource = "douban", doubanTopRank = 1) }, listOf(seed), nowMs = 1)
        assertEquals(baseline.ranking, altered.ranking)
    }
    @Test fun sparseAndMalformedIdentityIsSafeWithoutInventedFeatures() {
        val unknown = film("").copy(title = "", category = "")
        val result = LocalRecommendationPipeline.rank(listOf(unknown), listOf(RecommendationSignal(unknown, favorite = true)))
        assertTrue(result.ranking.coldStart)
        assertEquals(1, result.rows.size)
    }
    @Test fun tagVocabularyNeverContainsSourceUrlsOrPlaybackLinks() {
        val private = film("x").copy(source = sourceA, episodes = listOf("https://secret.example/play"))
        assertEquals(setOf("genre:科幻"), LocalRecommendationPipeline.features(private).keys)
    }
}
