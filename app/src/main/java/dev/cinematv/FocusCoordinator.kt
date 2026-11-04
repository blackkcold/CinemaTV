package dev.cinematv

/**
 * A small navigation-only focus memory that survives composable disposal.
 * No view references are retained. Every key must identify a stable logical item.
 */
class FocusCoordinator {
    private data class Bookmark(val route: String, val region: String, val itemKey: String)
    private var last: Bookmark? = null

    fun remember(route: String, region: String, itemKey: String) {
        if (route.isNotBlank() && region.isNotBlank() && itemKey.isNotBlank()) {
            last = Bookmark(route, region, itemKey)
        }
    }

    fun restore(route: String, region: String): String? =
        last?.takeIf { it.route == route && it.region == region }?.itemKey

    fun clear() { last = null }
}
