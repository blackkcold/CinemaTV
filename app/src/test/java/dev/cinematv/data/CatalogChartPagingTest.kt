package dev.cinematv.data

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Test

class CatalogChartPagingTest {
    private fun page(offset: Int, total: Int = 250, snapshot: String? = "a", provider: String = "Douban") = MetadataCatalog(
        items = (offset until minOf(offset + 50, total)).map { MediaItem("$snapshot:$it", "Synthetic $it") },
        provider = provider, available = true,
        nextOffset = (offset + 50).takeIf { it < total },
        acquisition = snapshot?.let { CatalogAcquisition(snapshotId = it, availableTotal = total, complete = total == 250) })

    // Fetch fixtures complete synchronously; this helper needs no network or coroutine scheduler.
    private fun load(fetch: suspend (Int) -> MetadataCatalog, snapshots: MutableList<CatalogChartSnapshot> = mutableListOf()): CatalogChartSnapshot {
        var outcome: Result<CatalogChartSnapshot>? = null
        val task: suspend () -> CatalogChartSnapshot = { CatalogChartPaging.load(fetch) { snapshots += it } }
        task.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return requireNotNull(outcome).getOrThrow()
    }

    @Test fun allFivePagesAreLoadedAndFirstPageIsPublishedImmediately() {
        val seen = mutableListOf<CatalogChartSnapshot>()
        val requests = mutableListOf<Int>()
        val result = load({ offset ->
            if (offset > 0) assertEquals(offset, seen.last().items.size)
            requests += offset
            page(offset)
        }, seen)
        assertEquals(listOf(0, 50, 100, 150, 200), requests)
        assertEquals(listOf(50, 100, 150, 200, 250), seen.map { it.items.size })
        assertTrue(result.pagingComplete)
    }

    @Test fun partialCacheAppearsWithoutWaitingForFullSnapshot() {
        val result = load({ page(it, total = 50).copy(acquisition = CatalogAcquisition(
            snapshotId = "partial", availableTotal = 50, refreshing = true, state = CatalogAcquisitionState.REFRESHING)) })
        assertEquals(50, result.items.size)
        assertFalse(requireNotNull(result.catalog.acquisition).complete)
        assertTrue(result.pagingComplete)
    }

    @Test fun changedSnapshotRestartsOnceWithoutCombiningRankings() {
        val seen = mutableListOf<CatalogChartSnapshot>()
        val requests = mutableListOf<Int>()
        val result = load({ offset ->
            requests += offset
            page(offset, total = 100, snapshot = if (requests.size == 1) "a" else "b")
        }, seen)
        assertEquals(listOf(0, 50, 0, 50), requests)
        assertEquals(100, result.items.size)
        assertTrue(result.items.all { it.id.startsWith("b:") })
        assertTrue(seen.any { it.changedDuringPaging && it.items.isEmpty() })
        assertTrue(seen.all { it.items.map { item -> item.id.substringBefore(':') }.distinct().size <= 1 })
    }

    @Test fun repeatedSnapshotChangesDiscardStageAndStop() {
        var requests = 0
        val result = load({ offset -> page(offset, total = 100, snapshot = "snapshot-${++requests}") })
        assertEquals(4, requests)
        assertTrue(result.changedDuringPaging)
        assertTrue(result.items.isEmpty())
    }

    @Test fun providerChangeNeverMixesPages() {
        var requests = 0
        val result = load({ offset -> page(offset, total = 100,
            provider = if (++requests == 1) "Douban" else "Another provider") })
        assertEquals(4, requests)
        assertEquals("Another provider", result.catalog.provider)
        assertEquals(100, result.items.size)
    }

    @Test fun legacyCatalogWithoutSnapshotIdRetainsFivePageSupport() {
        val result = load({ page(it, snapshot = null) })
        assertEquals(250, result.items.size)
        assertNull(result.catalog.acquisition)
    }

    @Test fun invalidPaginationIsBoundedAndNeverClaimsAllMatchesLoaded() {
        var requests = 0
        val result = load({ offset -> requests++; page(offset).copy(nextOffset = 0) })
        assertEquals(1, requests)
        assertEquals(50, result.items.size)
        assertFalse(result.pagingComplete)
    }

    @Test fun duplicatePagesDoNotInflateLoadedCount() {
        val result = load({ offset -> page(0, total = 100).copy(nextOffset = if (offset == 0) 50 else null) })
        assertEquals(50, result.items.size)
    }

    @Test fun expiredLaterPageDropsPreviouslyStagedMetadata() {
        val result = load({ offset -> if (offset == 0) page(0, total = 100)
            else page(50, total = 100).copy(items = emptyList(), licenseRejected = true) })
        assertTrue(result.items.isEmpty())
        assertTrue(result.catalog.licenseRejected)
    }

    @Test fun staticTvFeedUsesIndependentPagination() {
        val movies = load({ page(it, total = 50) })
        val series = load({ page(it, total = 100, snapshot = null).copy(items = page(it, total = 100, snapshot = null)
            .items.map { item -> item.copy(isTv = true) }) })
        assertEquals(50, movies.items.size)
        assertEquals(100, series.items.size)
        assertTrue(series.items.all { it.isTv })
        assertNull(series.catalog.acquisition)
    }
}
