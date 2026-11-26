package dev.cinematv.ui.tv.focus

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Asks the current page where Down should land when the floating navigation owns focus.
 *
 * The navigator stays the single owner: it only asks, and consumes the key only when the
 * page answers `true`. A page that cannot decide (not composed, no content) leaves the
 * existing `focusProperties { down = content }` behaviour untouched.
 *
 * All calls happen on the UI thread; no long-lived references are retained — the page
 * registers in a `DisposableEffect` and must clear its handler on dispose.
 */
class TvNavigationDownRelay {
    private var owner: Any? = null
    private var handler: (() -> Boolean)? = null

    fun setHandler(owner: Any, handler: () -> Boolean) {
        this.owner = owner
        this.handler = handler
    }

    fun clearHandler(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        this.handler = null
    }

    fun handle(): Boolean = handler?.invoke() ?: false
}

val LocalTvNavigationDown = staticCompositionLocalOf { TvNavigationDownRelay() }
