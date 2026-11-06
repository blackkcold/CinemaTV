package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class CatalogBrowsePagingTest {
    private fun page(ids: List<String>, next: Int? = null, provider: String = "fixture", snapshot: String = "one", expiry: Long = 0) =
        MetadataCatalog(items = ids.map { MediaItem(it, it, catalogProvider = provider, catalogLicenseExpiresAt = expiry) },
            provider = provider, available = true, nextOffset = next,
            acquisition = CatalogAcquisition(snapshotId = snapshot, complete = true), licenseExpiresAt = expiry)

    @Test fun appendsOnePageAndDeduplicatesWithoutLosingCursor() {
        val first = CatalogBrowsePaging.append(CatalogBrowseState(), page(listOf("a", "b"), 50), 0)
        assertTrue(first.canLoadMore)
        val second = CatalogBrowsePaging.append(first, page(listOf("b", "c")), 50)
        assertEquals(listOf("a", "b", "c"), second.items.map { it.id })
        assertFalse(second.canLoadMore)
        assertTrue(second.description().contains("已载完"))
    }
    @Test fun unavailablePagePreservesItemsAndRetryCursor() {
        val first = CatalogBrowsePaging.append(CatalogBrowseState(), page(listOf("a"), 50), 0)
        val failed = CatalogBrowsePaging.append(first, MetadataCatalog(), 50)
        assertEquals(first.items, failed.items)
        assertEquals(50, failed.nextOffset)
        assertTrue(failed.notice.contains("重试"))
        assertTrue(failed.retryableFailure)
        assertTrue(failed.canLoadMore)
        val recovered = CatalogBrowsePaging.append(failed, page(listOf("b")), 50)
        assertFalse(recovered.retryableFailure)
        assertEquals(listOf("a", "b"), recovered.items.map { it.id })
    }
    @Test fun changedSnapshotProviderOrLicenseNeverMixesPages() {
        val first = CatalogBrowsePaging.append(CatalogBrowseState(), page(listOf("a"), 50), 0)
        listOf(page(listOf("b"), snapshot = "two"), page(listOf("b"), provider = "other"),
            page(listOf("b"), expiry = 10000)).forEach {
            val result = CatalogBrowsePaging.append(first, it, 50, nowMs = 1)
            assertEquals(first.items, result.items)
            assertTrue(result.restartRequired)
            assertFalse(result.retryableFailure)
            assertFalse(result.canLoadMore)
        }
    }
    @Test fun expiredAuthorizationClearsCatalogMetadata() {
        val first = CatalogBrowsePaging.append(CatalogBrowseState(), page(listOf("a"), 50, expiry = 100), 0, nowMs = 1)
        val expired = CatalogBrowsePaging.append(first, page(listOf("b"), expiry = 100), 50, nowMs = 101)
        assertTrue(expired.items.isEmpty())
        assertTrue(expired.restartRequired)
    }
    @Test fun repeatedOrEmptyPageCursorStopsExplicitly() {
        val first = CatalogBrowsePaging.append(CatalogBrowseState(), page(listOf("a"), 50), 0)
        listOf(page(listOf("b"), 50), page(emptyList(), 100)).forEach {
            val result = CatalogBrowsePaging.append(first, it, 50)
            assertFalse(result.canLoadMore)
            assertTrue(result.notice.contains("游标"))
        }
    }
    @Test fun boundedPagingExplainsPartialResults() {
        val state = CatalogBrowseState(catalog = page(emptyList()), pages = 99, nextOffset = 4950)
        val result = CatalogBrowsePaging.append(state, page(listOf("last"), 5000), 4950)
        assertFalse(result.canLoadMore)
        assertTrue(result.notice.contains("上限"))
    }
    @Test fun maximumPageStillDisclosesLimitWhenUpstreamClipsCursor() {
        val state = CatalogBrowseState(catalog = page(emptyList()), pages = 99, nextOffset = 4950)
        val result = CatalogBrowsePaging.append(state, page(listOf("last")), 4950)
        assertTrue(result.notice.contains("上限"))
        assertFalse(result.description().contains("已载完"))
    }
    @Test fun partialSnapshotDoesNotClaimFullProviderCoverage() {
        val result = CatalogBrowsePaging.append(CatalogBrowseState(), page(listOf("a")).copy(
            acquisition = CatalogAcquisition(snapshotId = "one", complete = false)), 0)
        assertTrue(result.description().contains("尚未完整"))
    }
    @Test fun filtersApplyToEveryIncrementalPage() {
        val first = CatalogBrowsePaging.append(CatalogBrowseState(), page(listOf("a"), 50).copy(
            items = listOf(MediaItem("a", "Film", year = "2020"))), 0)
        val result = CatalogBrowsePaging.append(first, page(listOf("b", "c")).copy(items = listOf(
            MediaItem("b", "Film", year = "2024"), MediaItem("c", "Film", year = "2024", isTv = true))), 50)
        val hits = SearchIndex.search("Film", library = emptyList(), catalog = result.items, remote = emptyList(),
            filters = SearchFilters(isTv = false, year = "2024"))
        assertEquals(listOf("b"), hits.map { it.item.id })
    }
    @Test(expected = IllegalArgumentException::class) fun staleCursorIsRejected() {
        CatalogBrowsePaging.append(CatalogBrowseState(), page(listOf("a")), 50)
    }
}
