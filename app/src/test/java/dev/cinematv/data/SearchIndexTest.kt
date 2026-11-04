package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class SearchIndexTest {
    private val now = 1_000L
    private val source = TvSource("a", "Source A", "https://example.org/a")
    private val film = MediaItem("movie", "星际穿越", year = "2014", aliases = listOf("Interstellar"),
        actors = listOf("Matthew McConaughey", "安妮·海瑟薇"), category = "科幻", rating = "9.4",
        ratingSource = "douban", doubanTopRank = 10, ratingLicenseExpiresAt = 2_000L)

    @Test fun searchesAliasCaseWidthAndActorTokens() {
        assertEquals(film.id, SearchIndex.search("ＩＮＴＥＲＳＴＥＬＬＡＲ", catalog = listOf(film)).single().item.id)
        assertEquals(film.id, SearchIndex.search("matthew mcconaughey", catalog = listOf(film)).single().item.id)
        assertEquals(film.id, SearchIndex.search("安妮 海瑟薇", catalog = listOf(film)).single().item.id)
    }

    @Test fun requiresEveryQueryTokenButAllowsDifferentFields() {
        assertEquals(1, SearchIndex.search("interstellar 海瑟薇", catalog = listOf(film)).size)
        assertTrue(SearchIndex.search("interstellar 不存在", catalog = listOf(film)).isEmpty())
    }

    @Test fun combinesIncrementalSourcesAndPreservesAllOriginsAndSourceIds() {
        val playable = film.copy(id = "vod1", source = source, aliases = emptyList(), actors = emptyList(), rating = "")
        val result = SearchIndex.search("interstellar", library = listOf(film), catalog = listOf(film), remote = listOf(playable))
        assertEquals(1, result.size)
        assertEquals(setOf(SearchOrigin.LIBRARY, SearchOrigin.CATALOG, SearchOrigin.LIBRE_TV), result.single().origins)
        assertEquals(listOf("movie", "vod1"), result.single().alternatives.map { it.id })
        assertEquals(source, result.single().alternatives.last().source)
    }

    @Test fun exactTitleOutranksAliasAndActorMatchesDespiteLibraryBoost() {
        val actor = MediaItem("actor", "Another work", actors = listOf("Arrival"))
        val alias = MediaItem("alias", "降临", aliases = listOf("Arrival"))
        val exact = MediaItem("exact", "Arrival")
        assertEquals(listOf("exact", "alias", "actor"), SearchIndex.search("arrival",
            library = listOf(actor), remote = listOf(alias, exact)).map { it.item.id })
    }

    @Test fun appliesEveryFilterWithoutRelaxingOnNoMatches() {
        val filters = SearchFilters(isTv = false, year = "2014", minRating = 9.0, ratingSource = "douban", doubanTopOnly = true)
        assertTrue(filters.matches(film, now))
        assertFalse(filters.matches(film.copy(isTv = true), now))
        assertFalse(filters.matches(film.copy(year = "2015"), now))
        assertFalse(filters.matches(film.copy(rating = "8.9"), now))
        assertFalse(filters.matches(film.copy(ratingSource = "tmdb"), now))
        assertTrue(SearchIndex.search("", catalog = listOf(film), filters = filters.copy(year = "1999"), nowMs = now).isEmpty())
    }

    @Test fun unknownAndTmdbScoresNeverBecomeDoubanScores() {
        for (provider in listOf("", "tmdb", "CMS", "douban-tmdb")) {
            val unverified = film.copy(ratingSource = provider)
            assertFalse(SearchFilters(ratingSource = "douban").matches(unverified, now))
            assertFalse(SearchFilters(doubanTopOnly = true).matches(unverified, now))
        }
        assertTrue(SearchFilters(minRating = 9.0, ratingSource = "tmdb").matches(film.copy(ratingSource = "tmdb", ratingLicenseExpiresAt = 0), now))
    }

    @Test fun doubanExpiryIsStrictAndNeverImplicit() {
        for (expiry in listOf(-1L, 0L, 999L, now)) {
            val expired = film.copy(ratingLicenseExpiresAt = expiry)
            assertFalse(SearchFilters(ratingSource = "douban").matches(expired, now))
            assertFalse(SearchFilters(minRating = 0.0).matches(expired, now))
            assertFalse(SearchFilters(doubanTopOnly = true).matches(expired, now))
            assertNull(RatingPolicy.score(expired, now))
        }
        assertTrue(SearchFilters(doubanTopOnly = true).matches(film.copy(ratingLicenseExpiresAt = now + 1), now))
    }

    @Test fun top250RequiresAnAuthorizedValidRankNotAHighScore() {
        for (rank in listOf(null, -1, 0, 251, Int.MAX_VALUE)) {
            assertFalse(SearchFilters(doubanTopOnly = true).matches(film.copy(doubanTopRank = rank), now))
        }
        assertTrue(SearchFilters(doubanTopOnly = true).matches(film.copy(doubanTopRank = 250), now))
        // A genuine Douban score outside Top 250 is still a score, not a ranking.
        assertTrue(SearchFilters(minRating = 9.0, ratingSource = "douban").matches(film.copy(doubanTopRank = null), now))
    }

    @Test fun rejectsInvalidScoreAndThresholdValues() {
        for (score in listOf("NaN", "Infinity", "-1", "10.1", "9/10", "", "N/A")) {
            assertFalse(SearchFilters(minRating = 0.0).matches(film.copy(rating = score), now))
        }
        for (threshold in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 10.1)) {
            assertFalse(SearchFilters(minRating = threshold).matches(film, now))
        }
    }

    @Test fun filtersReturnQualifiedMetadataWhileRetainingPlayableAlternatives() {
        val playable = film.copy(id = "vod", source = source, rating = "", ratingSource = "", doubanTopRank = null,
            ratingLicenseExpiresAt = 0, poster = "https://example.org/poster")
        val result = SearchIndex.search("星际", catalog = listOf(playable, film), filters = SearchFilters(doubanTopOnly = true), nowMs = now).single()
        assertEquals("douban", result.item.ratingSource)
        assertEquals(2, result.alternatives.size)
        assertEquals("vod", result.alternatives.first().id)
    }

    @Test fun combinesLibraryAliasAndRemoteActorsWithCatalogRatingWithoutForgingProvenance() {
        val local = film.copy(id = "local", actors = emptyList(), rating = "", ratingSource = "", ratingLicenseExpiresAt = 0)
        val rated = film.copy(id = "rated", aliases = emptyList(), actors = emptyList())
        val playable = film.copy(id = "vod", source = source, aliases = emptyList(), rating = "", ratingSource = "", ratingLicenseExpiresAt = 0)
        val result = SearchIndex.search("interstellar 海瑟薇", library = listOf(local), catalog = listOf(rated),
            remote = listOf(playable), filters = SearchFilters(minRating = 9.0, ratingSource = "douban"), nowMs = now).single()
        assertEquals("rated", result.item.id)
        assertEquals("douban", result.item.ratingSource)
        assertEquals(3, result.alternatives.size)
        assertEquals("", result.alternatives.first().ratingSource)
    }

    @Test fun unsupportedProviderIsNotSubstitutedAndProviderNormalizationIsLocaleSafe() {
        assertFalse(SearchFilters(ratingSource = "imdb").matches(film, now))
        assertTrue(SearchFilters(ratingSource = " 豆瓣 ").matches(film.copy(ratingSource = " DOUBAN "), now))
        assertTrue(SearchFilters(ratingSource = "TMDB").matches(film.copy(ratingSource = "TheMovieDB"), now))
    }

    @Test fun blankQueryAllowsFilteredBrowseAndLimitIsBounded() {
        assertEquals(1, SearchIndex.search("   ", catalog = listOf(film)).size)
        assertEquals(1, SearchIndex.search("：，", catalog = listOf(film)).size)
        assertTrue(SearchIndex.search("", catalog = listOf(film), limit = 0).isEmpty())
        assertTrue(SearchIndex.search("", catalog = listOf(film), limit = -1).isEmpty())
    }

    @Test fun noInputAndRepeatedCallsAreStable() {
        assertTrue(SearchIndex.search("film").isEmpty())
        val input = listOf(film, film.copy(id = "series", isTv = true))
        assertEquals(SearchIndex.search("星际", catalog = input), SearchIndex.search("星际", catalog = input))
        assertEquals(2, SearchIndex.search("星际", catalog = input).size)
    }

    @Test fun unknownTypeIsSearchableButNeverSatisfiesAMovieOrSeriesFilter() {
        val unknown = film.copy(mediaTypeKnown = false)
        assertEquals(1, SearchIndex.search("星际", remote = listOf(unknown)).size)
        assertFalse(SearchFilters(isTv = false).matches(unknown, now))
        assertFalse(SearchFilters(isTv = true).matches(unknown.copy(isTv = true), now))
    }

    @Test fun allProvidersRequireCurrentCatalogLicenseWhenOneIsAttached() {
        for (provider in listOf("tmdb", "douban", "", "licensed-provider")) {
            for (expiry in listOf(-1L, 999L, now)) {
                val expired = film.copy(ratingSource = provider, catalogLicenseExpiresAt = expiry)
                assertFalse(SearchFilters().matches(expired, now))
                assertNull(RatingPolicy.score(expired, now))
                assertFalse(RatingPolicy.isDoubanTop(expired, now))
                assertTrue(SearchIndex.search("", library = listOf(expired), catalog = listOf(expired),
                    remote = listOf(expired), nowMs = now).isEmpty())
            }
        }
        assertTrue(SearchFilters().matches(film.copy(catalogLicenseExpiresAt = 0), now))
        assertTrue(SearchFilters().matches(film.copy(catalogLicenseExpiresAt = now + 1), now))
    }

    @Test fun expiredCatalogMetadataCannotContributeAliasesOrAlternativesToLiveSource() {
        val expired = film.copy(catalogLicenseExpiresAt = now)
        val playable = film.copy(id = "vod", source = source, aliases = emptyList(), actors = emptyList(),
            rating = "", ratingSource = "", ratingLicenseExpiresAt = 0)
        assertTrue(SearchIndex.search("interstellar", catalog = listOf(expired), remote = listOf(playable), nowMs = now).isEmpty())
        val result = SearchIndex.search("星际", catalog = listOf(expired), remote = listOf(playable), nowMs = now).single()
        assertEquals(listOf("vod"), result.alternatives.map { it.id })
        assertEquals(setOf(SearchOrigin.LIBRE_TV), result.origins)
    }

    @Test fun refreshedSourceYearReplacesSnapshotWithoutDuplicatePresentationKeys() {
        val saved = MediaItem("42", "Film", year = "2024", source = source, aliases = listOf("Old alias"),
            positionMs = 500, durationMs = 1_000)
        val fresh = saved.copy(year = "2025", aliases = emptyList(), positionMs = 0, durationMs = 0)
        val result = SearchIndex.search("Film", library = listOf(saved), remote = listOf(fresh))
        assertEquals(1, result.size)
        assertEquals("2025", result.single().item.year)
        assertEquals(0L, result.single().item.positionMs)
        assertEquals(listOf(fresh), result.single().alternatives)
        assertEquals(setOf(SearchOrigin.LIBRARY, SearchOrigin.LIBRE_TV), result.single().origins)
        assertEquals(result.size, result.map { it.item.source?.url.orEmpty() to it.item.id }.distinct().size)
        assertTrue(SearchIndex.search("Old alias", library = listOf(saved), remote = listOf(fresh)).isEmpty())
    }

    @Test fun compatibleRefreshPreservesLocalAliasesAndProgressButNotStaleRating() {
        val saved = MediaItem("42", "Film", year = "2024", source = source, aliases = listOf("Local alias"),
            positionMs = 500, durationMs = 1_000, rating = "9.0", ratingSource = "tmdb")
        val fresh = saved.copy(aliases = listOf("Fresh alias"), positionMs = 0, durationMs = 0,
            rating = "", ratingSource = "", poster = "fresh")
        val result = SearchIndex.search("Local alias", library = listOf(saved), remote = listOf(fresh)).single()
        assertEquals("fresh", result.item.poster)
        assertEquals(setOf("Local alias", "Fresh alias"), result.item.aliases.toSet())
        assertEquals(500L, result.item.positionMs)
        assertEquals(1_000L, result.item.durationMs)
        assertEquals("", result.item.ratingSource)
        assertEquals(1, result.alternatives.size)
    }

    @Test fun refreshedSeasonCannotInheritAnotherSeasonsProgressOrAliases() {
        val saved = MediaItem("42", "Series", year = "2024", source = source, isTv = true, seasonNumber = 1,
            aliases = listOf("Season one alias"), positionMs = 500, durationMs = 1_000, lastEpisode = 5)
        val fresh = saved.copy(seasonNumber = 2, aliases = emptyList(), positionMs = 0, durationMs = 0, lastEpisode = 0)
        val result = SearchIndex.search("Series", library = listOf(saved), remote = listOf(fresh)).single()
        assertEquals(2, result.item.seasonNumber)
        assertEquals(0L, result.item.positionMs)
        assertEquals(0, result.item.lastEpisode)
        assertTrue(result.item.aliases.isEmpty())
    }

    @Test fun refreshedSourceKeepsDistinctOtherOriginAndDoesNotDetachLicensedAliases() {
        val saved = MediaItem("42", "Film", year = "2024", source = source, aliases = listOf("Licensed alias"),
            catalogLicenseExpiresAt = now + 1, positionMs = 500)
        val fresh = saved.copy(aliases = emptyList(), catalogLicenseExpiresAt = 0, positionMs = 0, year = "2024")
        assertTrue(SearchIndex.search("Licensed alias", library = listOf(saved), remote = listOf(fresh), nowMs = now).isEmpty())
        val other = fresh.copy(source = source.copy(url = "https://example.org/b"), year = "2025")
        val result = SearchIndex.search("Film", library = listOf(saved), remote = listOf(fresh, other), nowMs = now)
        assertEquals(2, result.size)
        assertEquals(2, result.map { it.item.source?.url.orEmpty() to it.item.id }.distinct().size)
        assertEquals(500L, result.first().item.positionMs)
    }

    @Test fun missingRemoteTypeDoesNotEraseKnownCompatibleLocalType() {
        val saved = MediaItem("42", "Series", year = "2024", source = source, isTv = true)
        val fresh = saved.copy(mediaTypeKnown = false, isTv = false)
        val result = SearchIndex.search("Series", library = listOf(saved), remote = listOf(fresh),
            filters = SearchFilters(isTv = true)).single()
        assertTrue(result.item.mediaTypeKnown)
        assertTrue(result.item.isTv)
    }
}
