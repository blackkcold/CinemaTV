package dev.cinematv.hybrid

import dev.cinematv.data.CatalogBrowseState

/** Remote cursors describe data availability independently of the filtered, local page size. */
object HybridCatalogPaging {
    fun status(available: Boolean, sources: List<CatalogBrowseState>): String = when {
        !available || sources.isEmpty() -> "unavailable"
        sources.any { it.loading } -> "loading"
        sources.any { it.nextOffset != null && !it.restartRequired && it.retryableFailure } -> "retry"
        sources.any { it.nextOffset != null && !it.restartRequired } -> "ready"
        sources.any { it.restartRequired } -> "restart"
        else -> "exhausted"
    }
}
