package dev.cinematv.ui.tv.focus

/**
 * Stable semantic regions for the HOME page.
 *
 * These are logical focus keys, not display copy: never build one from a title or a
 * search query. [FocusRequester]s stay in live composition; only these strings persist
 * in [dev.cinematv.FocusCoordinator], which is in-memory and needs no migration.
 */
internal object HomeRegions {
    const val HERO = "hero"
    const val CONTINUE = "rail:continue"
    const val FEATURED = "rail:featured"
    const val SERIES = "rail:series"
    const val DISCOVERY = "rail:discovery"
    const val EMPTY = "home:empty"
}

/**
 * One interactive HOME row in vertical order, as the page composes it.
 *
 * [key] is the parent `LazyColumn` item key, so the page can prepare the row before
 * asking for focus. [itemKeys] are the row's stable card keys; hero and empty state
 * carry a single synthetic `primary` target.
 */
internal data class HomeRow(
    val key: String,
    val region: String,
    val itemKeys: List<String>,
    val entryKey: String,
)

/** A resolved vertical destination: which row, which stable item key inside it. */
internal data class HomeTarget(
    val rowKey: String,
    val region: String,
    val itemKey: String,
    val fallbackIndex: Int,
)

/**
 * Pure vertical traversal for HOME. No Compose state, no focus requests, no logging:
 * the page owns preparation, cancellation and the actual request.
 */
internal object HomeFocusTraversal {
    /**
     * Rows in fixed vertical order: hero, continue, featured, series, discovery, empty.
     * A row is included only when it is interactive; status/loading text never is.
     */
    fun rows(
        historyKeys: List<String>,
        featuredKeys: List<String>,
        seriesKeys: List<String>,
        discoveryKeys: List<String>,
        heroInteractive: Boolean,
        emptyInteractive: Boolean,
    ): List<HomeRow> = buildList {
        if (heroInteractive) add(HomeRow("hero", HomeRegions.HERO, listOf("primary"), "primary"))
        if (historyKeys.isNotEmpty()) {
            add(HomeRow("continue", HomeRegions.CONTINUE, historyKeys, historyKeys.first()))
        }
        if (featuredKeys.isNotEmpty()) {
            add(HomeRow("movies", HomeRegions.FEATURED, featuredKeys, featuredKeys.first()))
        }
        if (seriesKeys.isNotEmpty()) {
            add(HomeRow("series", HomeRegions.SERIES, seriesKeys, seriesKeys.first()))
        }
        if (discoveryKeys.isNotEmpty()) {
            add(HomeRow("discover", HomeRegions.DISCOVERY, discoveryKeys, discoveryKeys.first()))
        }
        if (emptyInteractive) add(HomeRow("home-empty", HomeRegions.EMPTY, listOf("primary"), "primary"))
    }

    /**
     * Target one row up or down from [currentRegion].
     *
     * Returns null at either boundary or when the source is not a known interactive row;
     * the caller must then leave the key to its existing owner instead of swallowing it.
     * The destination keeps the row's last focused key, otherwise the row entry.
     */
    fun verticalTarget(
        rows: List<HomeRow>,
        currentRegion: String,
        direction: Int,
        savedKeys: Map<String, String>,
    ): HomeTarget? {
        if (direction != -1 && direction != 1) return null
        val index = rows.indexOfFirst { it.region == currentRegion }
        if (index < 0) return null
        val targetIndex = index + direction
        if (targetIndex !in rows.indices) return null
        val row = rows[targetIndex]
        if (row.itemKeys.isEmpty()) return null
        val saved = savedKeys[row.region]?.takeIf { it in row.itemKeys }
        val itemKey = saved ?: row.entryKey
        return HomeTarget(row.key, row.region, itemKey, row.itemKeys.indexOf(itemKey).coerceAtLeast(0))
    }
}
