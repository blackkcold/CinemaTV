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
}
