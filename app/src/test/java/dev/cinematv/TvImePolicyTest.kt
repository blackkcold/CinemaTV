package dev.cinematv

import dev.cinematv.ui.tv.shouldHideTvIme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvImePolicyTest {
    @Test fun searchAndSettingsRetainTheirEditors() {
        for (page in listOf(Page.SEARCH, Page.SETTINGS)) {
            assertFalse(shouldHideTvIme(page, imeVisible = true))
            assertFalse(shouldHideTvIme(page, imeVisible = false))
        }
    }

    @Test fun nonEditorRoutesRejectLateKeyboardShowsWithoutActingWhenHidden() {
        for (page in Page.entries.filter { it != Page.SEARCH && it != Page.SETTINGS }) {
            assertTrue("Late IME must close on $page", shouldHideTvIme(page, imeVisible = true))
            assertFalse(shouldHideTvIme(page, imeVisible = false))
        }
    }
}
