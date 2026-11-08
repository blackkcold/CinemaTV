package dev.cinematv

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-entry smoke on a real Android TV image. It proves the native root is the
 * only TV entry and still accepts system D-pad; it is not a physical-TV certification.
 */
@RunWith(AndroidJUnit4::class)
class TvLaunchSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun nativeTvRootLaunchesAndAcceptsSystemDpad() {
        val tv = (rule.activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        assumeTrue("Native TV production entry is only asserted on a television UI", tv)
        rule.waitUntil(25_000) {
            rule.onAllNodesWithTag("tv-root").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("tv-root").assertExists()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.waitForIdle()
        rule.onNodeWithTag("tv-root").assertExists()
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun systemBackLeavesTheSettingsRouteWithoutFinishingTheActivity() {
        val tv = (rule.activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        assumeTrue("Native TV production entry is only asserted on a television UI", tv)
        rule.waitUntil(25_000) {
            rule.onAllNodesWithTag("tv-root").fetchSemanticsNodes().isNotEmpty()
        }
        val atSettings = rule.onAllNodesWithText("CinemaTV 设置").fetchSemanticsNodes().isNotEmpty()
        if (atSettings) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            rule.onNodeWithText("CinemaTV 设置").assertDoesNotExist()
        }
        rule.onNodeWithTag("tv-root").assertExists()
        rule.onNodeWithTag("tv-main-navigation").assertExists()
        assertFalse(rule.activity.isFinishing)
    }
}
