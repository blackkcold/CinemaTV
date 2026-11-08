package dev.cinematv

/**
 * A small navigation-only focus memory that survives composable disposal.
 * No view references are retained. Every key must identify a stable logical item.
 */
class FocusCoordinator {
    data class Bookmark(val region: String, val itemKey: String, val position: Int = 0)
    data class ScrollPosition(val index: Int = 0, val offset: Int = 0)
    private val routes = mutableMapOf<String, Bookmark>()
    private val regions = mutableMapOf<Pair<String, String>, String>()
    private val scrollPositions = mutableMapOf<Pair<String, String>, ScrollPosition>()

    fun remember(route: String, region: String, itemKey: String, position: Int = 0) {
        if (route.isNotBlank() && region.isNotBlank() && itemKey.isNotBlank()) {
            routes[route] = Bookmark(region, itemKey, position.coerceAtLeast(0))
            regions[route to region] = itemKey
        }
    }

    fun restore(route: String, region: String): String? = regions[route to region]

    fun last(route: String): Bookmark? = routes[route]

    fun saveScroll(route: String, region: String, index: Int, offset: Int) {
        if (route.isNotBlank() && region.isNotBlank()) {
            scrollPositions[route to region] = ScrollPosition(index.coerceAtLeast(0), offset.coerceAtLeast(0))
        }
    }

    fun scroll(route: String, region: String): ScrollPosition =
        scrollPositions[route to region] ?: ScrollPosition()

    fun clear() { routes.clear(); regions.clear(); scrollPositions.clear() }
}
