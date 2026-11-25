package dev.cinematv

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One shared root Back owns every browsing route; HOME falls through to the system. */
@RunWith(AndroidJUnit4::class)
class TvRouteBackTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()

    @Test fun everyBrowseRouteReturnsToHomeOnSystemBack() {
        val routes = listOf(Page.MOVIES, Page.SERIES, Page.SEARCH, Page.EXPLORE,
            Page.LIVE, Page.LIBRARY, Page.SETTINGS)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (route in routes) {
            rule.runOnUiThread { rule.activity.model.navigate(route) }
            rule.waitForIdle()
            assertEquals(route, rule.activity.model.page)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            assertEquals("Back from $route must return HOME", Page.HOME, rule.activity.model.page)
        }
    }

    @Test fun detailBackReturnsToTheSectionThatOpenedIt() {
        rule.runOnUiThread {
            rule.activity.model.navigate(Page.SERIES)
            rule.activity.model.open(dev.cinematv.data.MediaItem(id = "back-test", title = "Back test"))
        }
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        assertEquals(Page.SERIES, rule.activity.model.page)
    }
}
