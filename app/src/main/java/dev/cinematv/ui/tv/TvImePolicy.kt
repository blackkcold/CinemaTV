package dev.cinematv.ui.tv

import dev.cinematv.Page

/** Only these TV routes own text editors; late IME shows must not leak elsewhere. */
internal fun shouldHideTvIme(page: Page, imeVisible: Boolean): Boolean =
    imeVisible && page != Page.SEARCH && page != Page.SETTINGS
