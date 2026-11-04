package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class SearchAggregatorTest {
    private val a = TvSource("a", "Source A", "https://example.org/a")
    private val b = TvSource("b", "Source B", "https://example.org/b")

    @Test fun groupsSameTitleYearWithoutCollapsingDifferentVersions() {
        val input = listOf(
            MediaItem("1", "ABC", year = "1999", source = a),
            MediaItem("2", "ＡＢＣ", year = "1999", source = b),
            MediaItem("3", "ABC", year = "2025", source = a)
        )
        val result = SearchAggregator.group(input)
        assertEquals(2, result.size)
        assertEquals(2, result[0].sourceCount)
        assertEquals(1, result[1].sourceCount)
    }

    @Test fun unknownYearNeverCollapsesAmbiguousWorks() {
        val input = listOf(
            MediaItem("1", "Film", source = a),
            MediaItem("2", "Film", source = b)
        )
        assertEquals(2, SearchAggregator.group(input).size)
    }

    @Test fun filmAndSeriesWithSameTitleAndYearRemainDistinct() {
        val movie = MediaItem("1", "Film", year = "2025", source = a)
        assertEquals(2, SearchAggregator.group(listOf(movie, movie.copy(id = "2", isTv = true))).size)
    }

    @Test fun malformedYearAndBlankTitleCannotCreateFalseIdentity() {
        for (year in listOf("", "未知", "2024/2025", "2024-05-01")) {
            val movie = MediaItem("1", "Film", year = year, source = a)
            assertEquals(2, SearchAggregator.group(listOf(movie, movie.copy(id = "2", source = b))).size)
        }
        val blank = MediaItem("1", "", year = "2025", source = a)
        assertEquals(2, SearchAggregator.group(listOf(blank, blank.copy(id = "2", source = b))).size)
    }

    @Test fun alternativesRetainProviderIdsAndCountUniqueUrls() {
        val first = MediaItem("1", "Film", year = "2025", source = a)
        val second = first.copy(id = "2", source = b, poster = "https://example.org/poster")
        val duplicateProvider = first.copy(id = "3")
        val result = SearchAggregator.groupWithAlternatives(listOf(first, second, duplicateProvider)).single()
        assertEquals(listOf("1", "2", "3"), result.alternatives.map { it.id })
        assertEquals(2, result.item.sourceCount)
        assertEquals(second.poster, result.item.poster)
    }

    @Test fun unknownYearSameProviderIdCanCoalesceButAliasesAreNotIdentity() {
        val first = MediaItem("1", "Film", source = a)
        assertEquals(1, SearchAggregator.group(listOf(first, first.copy(poster = "new"))).size)
        val alias = first.copy(id = "2", title = "Another film", aliases = listOf("Film"))
        assertEquals(2, SearchAggregator.group(listOf(first, alias)).size)
    }

    @Test fun repeatedIdenticalSnapshotsAreOnlyOneAlternative() {
        val first = MediaItem("1", "Film", source = a)
        val result = SearchAggregator.groupWithAlternatives(listOf(first, first)).single()
        assertEquals(1, result.alternatives.size)
    }

    @Test fun knownSeasonsAndUnknownSeasonStaySeparate() {
        val series = MediaItem("1", "Show", year = "2025", isTv = true, source = a)
        assertEquals(3, SearchAggregator.group(listOf(series, series.copy(id = "2", seasonNumber = 1),
            series.copy(id = "3", seasonNumber = 2))).size)
    }

    @Test fun explicitIdentityConflictsPreventFalseMergeAndLeaveMissingIdsAmbiguous() {
        val first = MediaItem("1", "Film", year = "2025", source = a, metadataIds = mapOf("tmdb" to "101"))
        val second = first.copy(id = "2", source = b, metadataIds = mapOf("tmdb" to "202"))
        val ambiguous = first.copy(id = "3", metadataIds = emptyMap())
        val firstAlternate = first.copy(id = "4", source = b)
        val result = SearchAggregator.groupWithAlternatives(listOf(first, second, ambiguous, firstAlternate))
        assertEquals(3, result.size)
        assertEquals(listOf("1", "4"), result.first().alternatives.map { it.id })
    }

    @Test fun unknownMediaTypeDoesNotMergeAcrossSourcesOrIntoKnownMovies() {
        val unknown = MediaItem("1", "Film", year = "2025", source = a, mediaTypeKnown = false)
        val second = unknown.copy(id = "2", source = b)
        val known = unknown.copy(id = "3", mediaTypeKnown = true)
        assertEquals(3, SearchAggregator.group(listOf(unknown, second, known)).size)
    }

    @Test fun unknownTypeSameProviderIdentityCanRetainItsAlternatives() {
        val unknown = MediaItem("1", "Film", year = "2025", source = a, mediaTypeKnown = false)
        val result = SearchAggregator.groupWithAlternatives(listOf(unknown, unknown.copy(poster = "new"))).single()
        assertEquals(2, result.alternatives.size)
    }
}
