package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class RecommendationEngineTest {
    private val sourceA = TvSource("a", "A", "https://example.org/a")
    private val sourceB = TvSource("b", "B", "https://example.org/b")
    private fun film(id: String, genre: String = "剧情", isTv: Boolean = false) =
        MediaItem(id, "Title $id", year = "2020", category = genre, isTv = isTv)

    @Test fun coldStartReturnsDeterministicCatalogOrderFallback() {
        val catalog = listOf(film("drama1"), film("drama2"), film("science", "科幻"), film("series", "剧情", true))
        val first = RecommendationEngine.recommend(catalog, emptyList())
        assertEquals(first, RecommendationEngine.recommend(catalog, emptyList()))
        assertEquals(catalog.size, first.size)
        assertEquals(catalog.map { it.id }, first.map { it.item.id })
        assertTrue(first.all { "本地偏好不足" in it.reason })
    }

    @Test fun personalizedRankingDiversifiesSimilarGenresAndTypes() {
        val seed = film("seed", "剧情 科幻")
        val catalog = listOf(film("drama1"), film("drama2"), film("science", "科幻"), film("series", "剧情", true))
        val result = RecommendationEngine.recommend(catalog, listOf(RecommendationSignal(seed, favorite = true)))
        assertEquals("drama1", result[0].item.id)
        assertEquals("science", result[1].item.id)
        assertTrue(result.take(3).any { it.item.isTv })
    }

    @Test fun favoriteGenresAndActorsProduceExplainableSuggestions() {
        val favorite = film("favorite", "科幻").copy(actors = listOf("Actor One"))
        val generic = film("generic", "喜剧")
        val similar = film("similar", "科幻").copy(actors = listOf("ＡＣＴＯＲ ＯＮＥ"))
        val result = RecommendationEngine.recommend(listOf(generic, similar), listOf(RecommendationSignal(favorite, favorite = true)))
        assertEquals("similar", result.first().item.id)
        assertTrue(result.first().reason.contains("收藏"))
        assertTrue(result.first().reason.contains(favorite.title))
        assertTrue(result.first().reason.contains("相同演员"))
        assertTrue(result.first().score > result.last().score)
    }

    @Test fun statusesAndProgressAreExplicitPositiveSignals() {
        val seed = film("seed", "科幻")
        val similar = film("similar", "科幻")
        val other = film("other", "喜剧")
        for (status in listOf(LibraryStatus.WATCHING, LibraryStatus.WATCHED, LibraryStatus.WANT_TO_WATCH)) {
            val result = RecommendationEngine.recommend(listOf(other, similar), listOf(RecommendationSignal(seed, status)))
            assertEquals("similar", result.first().item.id)
        }
        assertEquals("similar", RecommendationEngine.recommend(listOf(other, similar), listOf(
            RecommendationSignal(seed.copy(positionMs = 10_000)))).first().item.id)
    }

    @Test fun negativeFeedbackAlwaysWinsIncludingOverFavoriteAndColdStart() {
        val rejected = film("rejected", "科幻")
        val result = RecommendationEngine.recommend(listOf(rejected), listOf(
            RecommendationSignal(rejected, favorite = true), RecommendationSignal(rejected, notInterested = true)))
        assertTrue(result.isEmpty())
        assertTrue(RecommendationEngine.recommend(listOf(rejected), listOf(
            RecommendationSignal(rejected, LibraryStatus.DROPPED))).isEmpty())
    }

    @Test fun notInterestedHidesEveryKnownSourceVersionButNotMovieTvNamesakes() {
        val rejected = film("vod1").copy(source = sourceA)
        val alternative = rejected.copy(id = "vod2", source = sourceB)
        val series = alternative.copy(isTv = true)
        val result = RecommendationEngine.recommend(listOf(alternative, series), listOf(RecommendationSignal(rejected, notInterested = true)))
        assertEquals(listOf(true), result.map { it.item.isTv })
    }

    @Test fun unknownIdentityDoesNotHideAnUnrelatedSourceOrConflictingYear() {
        val rejected = film("vod1").copy(year = "", source = sourceA)
        val unknown = rejected.copy(id = "vod2", source = sourceB)
        val otherYear = rejected.copy(year = "2025", id = "vod3")
        val result = RecommendationEngine.recommend(listOf(unknown, otherYear), listOf(RecommendationSignal(rejected, notInterested = true)))
        assertEquals(2, result.size)
    }

    @Test fun sameSourceIdentityStaysHiddenAfterMissingYearIsEnriched() {
        val rejected = film("vod1").copy(year = "", source = sourceA)
        val enriched = rejected.copy(year = "2020", poster = "new")
        assertTrue(RecommendationEngine.recommend(listOf(enriched), listOf(RecommendationSignal(rejected, notInterested = true))).isEmpty())
    }

    @Test fun explicitConflictingIdsDoNotSpreadNegativeFeedbackToDifferentWorks() {
        val rejected = film("one").copy(metadataIds = mapOf("tmdb" to "101"))
        val other = rejected.copy(id = "two", metadataIds = mapOf("tmdb" to "202"))
        assertEquals(listOf("two"), RecommendationEngine.recommend(listOf(other), listOf(
            RecommendationSignal(rejected, notInterested = true))).map { it.item.id })
    }

    @Test fun malformedSourceIsNotTheSameAsMetadataWithoutASource() {
        val rejected = film("same").copy(year = "", source = sourceA.copy(url = ""))
        val metadata = rejected.copy(source = null)
        assertEquals(1, RecommendationEngine.recommend(listOf(metadata), listOf(
            RecommendationSignal(rejected, notInterested = true))).size)
    }

    @Test fun separateFavoriteAndWatchSignalsDoNotLoseTheWatchedPenalty() {
        val watched = film("watched", "科幻")
        val unseen = film("unseen", "喜剧")
        val result = RecommendationEngine.recommend(listOf(watched, unseen), listOf(
            RecommendationSignal(watched, favorite = true), RecommendationSignal(watched, LibraryStatus.WATCHED)))
        assertEquals("unseen", result.first().item.id)
        assertTrue(result.last().reason.contains("已看过"))
    }

    @Test fun watchedAndMostlyFinishedItemsYieldToUnseenEvenWithStrongAffinity() {
        val watched = film("watched", "科幻")
        val partial = film("partial", "科幻").copy(positionMs = 70, durationMs = 100)
        val unseen = film("unseen", "喜剧")
        val favorite = film("favorite", "科幻")
        val result = RecommendationEngine.recommend(listOf(watched, partial, unseen), listOf(
            RecommendationSignal(watched, LibraryStatus.WATCHED), RecommendationSignal(partial), RecommendationSignal(favorite, favorite = true)))
        assertEquals("unseen", result.first().item.id)
        assertTrue(result.drop(1).all { "接近看完" in it.reason })
    }

    @Test fun finishedOnlyCatalogRemainsAvailableAsClearlyExplainedFallback() {
        val watched = film("watched")
        val result = RecommendationEngine.recommend(listOf(watched), listOf(RecommendationSignal(watched, LibraryStatus.WATCHED)))
        assertEquals(1, result.size)
        assertTrue(result.single().reason.contains("已看过"))
    }

    @Test fun blankOrNegativeDurationDoesNotPretendAWorkIsFinished() {
        for (duration in listOf(0L, -100L)) {
            val playing = film("playing").copy(positionMs = Long.MAX_VALUE, durationMs = duration)
            val result = RecommendationEngine.recommend(listOf(playing), listOf(RecommendationSignal(playing)))
            assertFalse(result.single().reason.contains("接近看完"))
        }
    }

    @Test fun ratingsDoNotCreatePersonalTasteOrChangeRecommendations() {
        val catalog = listOf(film("one"), film("two"), film("three"))
        val altered = catalog.mapIndexed { index, item -> item.copy(rating = (10 - index).toString(), ratingSource = "douban", doubanTopRank = index + 1) }
        assertEquals(RecommendationEngine.recommend(catalog, emptyList()).map { it.item.id },
            RecommendationEngine.recommend(altered, emptyList()).map { it.item.id })
        assertTrue(RecommendationEngine.recommend(altered, emptyList()).all { it.score == 0.0 })
    }

    @Test fun negativeTitlesDoNotTeachPositivePreferences() {
        val rejected = film("rejected", "科幻")
        val generic = film("generic", "剧情")
        val related = film("related", "科幻")
        assertEquals("generic", RecommendationEngine.recommend(listOf(generic, related), listOf(
            RecommendationSignal(rejected, favorite = true, notInterested = true))).first().item.id)
    }

    @Test fun duplicatesDoNotRepeatRecommendationsOrInflateSignals() {
        val seed = film("seed", "科幻")
        val candidate = film("candidate", "科幻")
        val signal = RecommendationSignal(seed, favorite = true)
        val once = RecommendationEngine.recommend(listOf(candidate), listOf(signal)).single()
        val repeated = RecommendationEngine.recommend(listOf(candidate, candidate), List(50) { signal }).single()
        assertEquals(once.score, repeated.score, 0.0)
    }

    @Test fun emptyInputAndNonPositiveLimitsAreSafe() {
        assertTrue(RecommendationEngine.recommend(emptyList(), emptyList()).isEmpty())
        assertTrue(RecommendationEngine.recommend(listOf(film("one")), emptyList(), 0).isEmpty())
        assertTrue(RecommendationEngine.recommend(listOf(film("one")), emptyList(), -1).isEmpty())
        assertEquals(1, RecommendationEngine.recommend(listOf(film("one"), film("two")), emptyList(), 1).size)
    }

    @Test fun expiredCatalogItemsAreNeverColdStartOrPersonalizedFallbacks() {
        val now = 1_000L
        val expired = film("expired").copy(catalogLicenseExpiresAt = now)
        val invalid = film("invalid").copy(catalogLicenseExpiresAt = -1)
        val active = film("active").copy(catalogLicenseExpiresAt = now + 1)
        val unlicensedSource = film("source")
        for (signals in listOf(emptyList(), listOf(RecommendationSignal(film("seed"), favorite = true)))) {
            assertEquals(setOf("active", "source"), RecommendationEngine.recommend(
                listOf(expired, invalid, active, unlicensedSource), signals, nowMs = now).map { it.item.id }.toSet())
        }
    }

    @Test fun expiredPositiveMetadataDoesNotTeachTasteButExplicitRejectionPersists() {
        val expired = film("seed", "科幻").copy(catalogLicenseExpiresAt = 999)
        val unrelated = film("unrelated", "喜剧")
        val related = film("related", "科幻")
        val result = RecommendationEngine.recommend(listOf(unrelated, related),
            listOf(RecommendationSignal(expired, favorite = true)), nowMs = 1_000)
        assertEquals(listOf("unrelated", "related"), result.map { it.item.id })
        assertTrue(result.all { it.score == 0.0 })
        assertTrue(RecommendationEngine.recommend(listOf(expired.copy(catalogLicenseExpiresAt = 2_000)),
            listOf(RecommendationSignal(expired, notInterested = true)), nowMs = 1_000).isEmpty())
    }

    @Test fun unknownTypeDoesNotInventAMoviePreferenceOrHideAnotherSource() {
        val unknown = film("seed", "").copy(mediaTypeKnown = false, source = sourceA)
        val candidate = film("candidate", "")
        assertEquals(0.0, RecommendationEngine.recommend(listOf(candidate), listOf(
            RecommendationSignal(unknown, favorite = true))).single().score, 0.0)
        val otherSource = unknown.copy(id = "other", source = sourceB)
        assertEquals(1, RecommendationEngine.recommend(listOf(otherSource), listOf(
            RecommendationSignal(unknown, notInterested = true))).size)
    }
}
