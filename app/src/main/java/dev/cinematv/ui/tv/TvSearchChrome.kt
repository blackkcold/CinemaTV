package dev.cinematv.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The SEARCH page's display intent, owned by the root so it survives only as long as the
 * route that published it. `TvRootApp` remains the single navigation owner: it reads this
 * state to decide whether the floating navigation may exist at all, and the page only
 * reports whether results are being browsed.
 *
 * [browsing] is deliberately the only root-visible flag: it hides the navigation and
 * compacts the header. The filter panel is a page-local dialog and is not persisted here.
 */
class TvSearchChromeState {
    /** Results are being browsed: navigation is absent (not merely transparent) and the header is one summary line. */
    var browsing by mutableStateOf(false)

    /** Leaving SEARCH for a non-child route. Never animate here; the route owns the frame. */
    fun reset() {
        browsing = false
    }
}

val LocalTvSearchChrome = staticCompositionLocalOf { TvSearchChromeState() }

/**
 * Stable focus regions for the browsing chrome. Like every other bookmark key these are
 * logical strings, never built from a query, a title or a filter value.
 */
internal object SearchChromeRegions {
    const val CHROME = "search-chrome"
    const val SUMMARY = "summary"
}
