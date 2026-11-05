package dev.cinematv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.cinematv.data.douban.DoubanDeveloperOptions
import dev.cinematv.ui.douban.DoubanLoginActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.settings() {
    runOnIdle { androidx.lifecycle.ViewModelProvider(activity)[CinemaViewModel::class.java].navigate(Page.SETTINGS) }
    waitUntil(10_000) { !DoubanDeveloperOptions.get(activity).busy.value }
}

/** Always use both UI confirmations. No login or session authorization is granted. */
private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.setExperiment(enabled: Boolean) {
    settings()
    val options = DoubanDeveloperOptions.get(activity)
    if (options.access.isEnabled == enabled) return
    onNodeWithTag("developer-options-entry").performClick()
    onNodeWithTag("douban-experiment-toggle").performClick()
    assertEquals(!enabled, options.access.isEnabled)
    onNodeWithTag("douban-experiment-confirm").performClick()
    waitUntil(10_000) { options.access.isEnabled == enabled && !options.busy.value }
    onNodeWithText("返回").performClick()
    waitForIdle()
}

private fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.resetExperiment() {
    // Recover UI state even if a test failed while a native account/options dialog was open.
    repeat(3) {
        if (listOf("douban-experiment-confirm", "douban-experiment-toggle", "douban-account-status")
                .any { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }) {
            Espresso.pressBack()
            waitForIdle()
        }
    }
    setExperiment(false)
}

/** Controlled test installation only. No live login, QR/session capture, or remote request. */
@RunWith(AndroidJUnit4::class)
class DoubanAccountUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Before fun startDisabled() { rule.resetExperiment() }
    @After fun leaveDisabled() { rule.resetExperiment() }

    @Test fun accountEntryHiddenUntilExplicitDeveloperConfirmation() {
        rule.onNodeWithTag("douban-account-entry").assertDoesNotExist()
        rule.onNodeWithTag("developer-options-entry").performClick()
        rule.onNodeWithTag("douban-experiment-state").assertExists()
        rule.onNodeWithTag("douban-experiment-toggle").performClick()
        assertFalse(DoubanDeveloperOptions.get(rule.activity).access.isEnabled)
        rule.onNodeWithTag("douban-account-entry").assertDoesNotExist()
        // Dismissing the second confirmation must not grant access.
        rule.onNodeWithText("取消").performClick()
        assertFalse(DoubanDeveloperOptions.get(rule.activity).access.isEnabled)
        rule.onNodeWithTag("douban-experiment-toggle").performClick()
        rule.onNodeWithTag("douban-experiment-confirm").performClick()
        rule.waitUntil(10_000) { DoubanDeveloperOptions.get(rule.activity).access.isEnabled }
        rule.onNodeWithText("返回").performClick()
        rule.onNodeWithTag("douban-account-entry").assertExists()
    }

    @Test fun accountEntryAndCancelledOfficialLoginReturnToNativePanel() {
        rule.setExperiment(true)
        rule.onNodeWithTag("douban-account-entry").performClick()
        rule.onNodeWithTag("douban-account-status").assertExists()
        rule.onNodeWithText("官方扫码登录").performClick()
        onView(withText("在这台设备登录豆瓣")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
        onView(withText("取消")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        rule.waitForIdle()
        rule.onNodeWithTag("douban-account-status").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        rule.onNodeWithTag("douban-account-entry").assertExists()
    }

    @Test fun directLoginActivityWhileDisabledFinishesWithoutWebView() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        var observedCreation = false
        var finishedAtCreation = false
        var containedWebView = false
        fun hasWebView(view: View): Boolean = view is WebView ||
            (view is ViewGroup && (0 until view.childCount).any { hasWebView(view.getChildAt(it)) })
        val observer = ActivityLifecycleCallback { activity, stage ->
            if (activity is DoubanLoginActivity && stage == Stage.CREATED) {
                observedCreation = true
                finishedAtCreation = activity.isFinishing
                containedWebView = hasWebView(activity.window.decorView)
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { monitor.addLifecycleCallback(observer) }
        try {
            ActivityScenario.launch(DoubanLoginActivity::class.java).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
                assertTrue(observedCreation)
                assertTrue(finishedAtCreation)
                assertFalse(containedWebView)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { monitor.removeLifecycleCallback(observer) }
        }
    }
}

/** TV presentation host on a controlled emulator; not physical Android TV certification. */
@RunWith(AndroidJUnit4::class)
class TvDoubanAccountUiTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    @Before fun startDisabled() { rule.resetExperiment() }
    @After fun leaveDisabled() { rule.resetExperiment() }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun nativeTvPanelRestoresRemoteFocusAfterBack() {
        rule.setExperiment(true)
        val entry = rule.onNodeWithTag("douban-account-entry")
        entry.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        entry.assertIsFocused()
        entry.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        rule.onNodeWithTag("douban-account-status").assertExists()
        rule.onNodeWithText("官方扫码登录").assertIsFocused()
        onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.pressBack())
        rule.onNodeWithTag("douban-account-entry").assertIsFocused()
    }

    @Test fun disablingHidesAccountEntryAndRestoresDeveloperOptionsFocus() {
        rule.onNodeWithTag("douban-account-entry").assertDoesNotExist()
        rule.setExperiment(true)
        rule.onNodeWithTag("douban-account-entry").assertExists()
        rule.setExperiment(false)
        rule.onNodeWithTag("douban-account-entry").assertDoesNotExist()
        rule.onNodeWithTag("developer-options-entry").assertIsFocused()
    }
}
