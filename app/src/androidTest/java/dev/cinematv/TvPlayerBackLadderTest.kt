package dev.cinematv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's Back ladder is local to the player: its panel, then its controls, then
 * the route it came from. Driven through the real system Back so the preview handler and
 * the activity dispatcher stay in the same order production uses.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TvPlayerBackLadderTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    private lateinit var server: UiFixtureServer
    private val model get() = rule.activity.model
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun connectFixture() {
        rule.waitUntil(10_000) { rule.activity.hasWindowFocus() }
        server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context).apply { homeCatalog = true }
        rule.runOnIdle {
            model.updateReducedMotion(true)
            model.repo.configureMetadata(server.base, "offline-fixture-token")
            model.serverAddress = server.base
            model.connect()
            model.navigate(Page.HOME)
        }
        rule.waitUntil(20_000) { model.authorized && model.featured.size == 24 }
        rule.waitForIdle()
    }

    @After fun closeFixture() {
        if (::server.isInitialized) server.close()
        // Quality and motion are persisted app settings; a later lane must start clean.
        rule.activity.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
    }

    @Test fun backClosesThePanelThenHidesTheControlsThenLeavesThePlayer() {
        openPlayer()

        // Playing hides the controls after a pause of inactivity; pause so each Back step
        // is observable instead of racing the auto-hide timer.
        rule.onNodeWithText("暂停").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("音轨").assertExists().performClick()
        rule.onNodeWithText("自动选择").assertExists()

        back()
        rule.onNodeWithText("自动选择").assertDoesNotExist()
        rule.onNode(hasText("音轨") and hasClickAction()).assertIsFocused()
        assertEquals("Closing a panel must not leave the player", Page.PLAYER, model.page)

        back()
        rule.onNodeWithText("音轨").assertDoesNotExist()
        assertEquals("Hiding the controls must not leave the player", Page.PLAYER, model.page)

        back()
        rule.waitUntil(5_000) { model.page == Page.DETAIL }
    }

    @Test fun aHeldBackMovesTheLadderExactlyOneStep() {
        openPlayer()
        rule.onNodeWithText("暂停").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("音轨").assertExists()

        // One physical press with repeats: the preview handler owns the whole gesture and
        // must not treat each repeat as a new Back.
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1))
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 2))
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        rule.waitForIdle()

        assertEquals("A held Back hides the controls once", Page.PLAYER, model.page)
        rule.onNodeWithText("音轨").assertDoesNotExist()
    }

    private fun openPlayer() {
        rule.runOnIdle { model.open(model.featured.first()) }
        rule.waitUntil(15_000) { model.page == Page.DETAIL && !model.busy }
        rule.onNodeWithText("▶ 立即播放").performClick()
        rule.waitUntil(20_000) { model.page == Page.PLAYER && (model.selected?.durationMs ?: 0) > 0 }
        rule.waitForIdle()
    }

    private fun back() {
        // sendKeyDownUpSync builds the up event with its own downTime, which the player's
        // RemoteBackPress ownership check must reject. A physical press shares one downTime.
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        rule.waitForIdle()
    }
}
