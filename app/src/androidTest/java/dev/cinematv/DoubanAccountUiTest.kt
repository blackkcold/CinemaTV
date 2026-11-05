package dev.cinematv

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        onView(withText("在这台设备登录豆瓣")).check(matches(isDisplayed()))
        onView(withText("取消")).perform(click())
        rule.waitForIdle()
        rule.onNodeWithTag("douban-account-status").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        rule.onNodeWithTag("douban-account-entry").assertExists()
    }
}
