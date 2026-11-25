package dev.cinematv

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production MainActivity path: native TV root, first-run Settings, one Back to the
 * browser, and HOME falling through to the system exit. Route-level assertions live in
 * TvRouteBackTest on the controlled host; this class never exposes the ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityPathSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun back() = InstrumentationRegistry.getInstrumentation()
        .sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    @Test fun firstRunSettingsBackReturnsToHomeThenHomeBackFinishes() {
        val tv = (rule.activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        org.junit.Assume.assumeTrue("Production TV entry is only asserted on a television UI", tv)
        rule.waitUntil(25_000) {
            rule.onAllNodesWithTag("tv-root").fetchSemanticsNodes().isNotEmpty()
        }
        val atSettings = rule.onAllNodesWithText("CinemaTV 设置").fetchSemanticsNodes().isNotEmpty()
        if (atSettings) {
            back()
            rule.waitForIdle()
            rule.onNodeWithText("CinemaTV 设置").assertDoesNotExist()
            rule.onNodeWithTag("tv-root").assertExists()
        }
        back()
        rule.waitUntil(8_000) { rule.activity.isFinishing }
        assertTrue("HOME with no local state must leave the Activity", rule.activity.isFinishing)
    }
}
