package dev.cinematv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
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

    /**
     * Poll for the app leaving the foreground **without** Compose idling.
     *
     * `rule.waitUntil` delegates every condition to the Compose test clock, and once the app
     * is backgrounded that clock is no longer idle — polling a platform state through it
     * throws `ComposeNotIdleException` instead of answering the question. Read the platform
     * state directly on the main thread instead.
     *
     * The condition is a lifecycle transition, not window focus: a dialog or a transient
     * system overlay may take focus without the route ever leaving the screen, while
     * leaving `RESUMED` is what puts the app off the foreground.
     */
    private fun waitUntilAppLeavesForeground(activity: ComponentActivity, timeoutMs: Long): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var left = false
            instrumentation.runOnMainSync {
                left = activity.isFinishing || activity.isDestroyed ||
                    activity.lifecycle.currentState != Lifecycle.State.RESUMED
            }
            if (left) return true
            Thread.sleep(100)
        }
        return false
    }

    @Test fun firstRunSettingsBackReturnsToHomeThenHomeBackLeavesTheApp() {
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
        // HOME owns no Back. The system exit has two legitimate implementations and the
        // platform picks per build: finish the activity, or move its task to the background.
        // The API33 Google TV image used by the tvNative lane takes the second —
        // `ActivityTaskManager: moveTaskToBack` in the device log, with the activity PAUSED
        // and then STOPPED and `isFinishing` never true. Asserting `isFinishing` therefore
        // asserted an implementation detail, not the contract, and stayed red on that lane
        // from 2026-11-23 onward. The contract is that this Back needs no second press: the
        // app is off the screen and no longer focused.
        assertTrue(
            "HOME with no local state must leave the app: neither finished nor backgrounded",
            waitUntilAppLeavesForeground(rule.activity, 8_000),
        )
    }
}
