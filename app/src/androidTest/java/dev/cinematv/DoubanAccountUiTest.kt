package dev.cinematv

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsFocused
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No live account, no live login, no QR/session capture. User consent is cancelled before navigation. */
@RunWith(AndroidJUnit4::class)
class DoubanAccountUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun accountEntryAndCancelledOfficialLoginReturnToNativePanel() {
        rule.runOnIdle { androidx.lifecycle.ViewModelProvider(rule.activity)[CinemaViewModel::class.java].navigate(Page.SETTINGS) }
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
}

/** TV presentation host on a controlled emulator; not a claim of physical Android TV certification. */
@RunWith(AndroidJUnit4::class)
class TvDoubanAccountUiTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun nativeTvPanelRestoresRemoteFocusAfterBack() {
        rule.runOnIdle { androidx.lifecycle.ViewModelProvider(rule.activity)[CinemaViewModel::class.java].navigate(Page.SETTINGS) }
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
}
