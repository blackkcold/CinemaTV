package dev.cinematv.hybrid

import dev.cinematv.data.CatalogBrowseState
import org.junit.Assert.assertEquals
import org.junit.Test

class HybridCatalogPagingTest {
    @Test fun emptyLocalResultsStillExposeRemoteCursor() {
        assertEquals("ready", HybridCatalogPaging.status(true, listOf(CatalogBrowseState(nextOffset = 48))))
        assertEquals("unavailable", HybridCatalogPaging.status(false, listOf(CatalogBrowseState())))
    }

    @Test fun loadingRetryAndExhaustionRemainDistinct() {
        assertEquals("loading", HybridCatalogPaging.status(true, listOf(CatalogBrowseState(loading = true))))
        assertEquals("retry", HybridCatalogPaging.status(true, listOf(CatalogBrowseState(retryableFailure = true))))
        assertEquals("restart", HybridCatalogPaging.status(true, listOf(CatalogBrowseState(nextOffset = null, restartRequired = true))))
        assertEquals("exhausted", HybridCatalogPaging.status(true, listOf(CatalogBrowseState(nextOffset = null))))
    }

    @Test fun oneExhaustedCatalogDoesNotHideTheOtherCursor() {
        assertEquals("ready", HybridCatalogPaging.status(true, listOf(CatalogBrowseState(nextOffset = null), CatalogBrowseState(nextOffset = 48))))
    }
}
