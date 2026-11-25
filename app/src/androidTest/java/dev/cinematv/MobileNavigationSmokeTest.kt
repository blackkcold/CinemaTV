package dev.cinematv

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a phone emulator; true Android TV QA is a separate device matrix. */
@RunWith(AndroidJUnit4::class)
class MobileNavigationSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun launcherAndCenteredTvBannerArePackaged() {
        assertNotNull(rule.activity.packageManager.getLaunchIntentForPackage(rule.activity.packageName))
        val bitmap = BitmapFactory.decodeResource(rule.activity.resources, R.drawable.tv_banner)
        assertNotNull(bitmap)
        assertEquals(320, bitmap.width)
        assertEquals(180, bitmap.height)
    }

    @Test fun portraitPhoneOpensAndNavigatesViaTouch() {
        assertEquals(Configuration.ORIENTATION_PORTRAIT,
            rule.activity.resources.configuration.orientation)
        rule.onNodeWithContentDescription("打开搜索").performClick()
        rule.onNodeWithText("搜索影视").assertExists()
        rule.onNodeWithContentDescription("打开设置").performClick()
        rule.onNodeWithText("CinemaTV 设置").assertExists()
        rule.onNodeWithText("LibreTV 服务器").assertExists()
    }

    @Test fun sourceSubscriptionsOpenAndReturnToSettingsByButtonOrBack() {
        rule.onNodeWithContentDescription("打开设置").performClick()
        rule.onNodeWithTag("source-subscriptions-open").performScrollTo().performClick()
        rule.onNodeWithTag("source-subscriptions-dialog").assertIsDisplayed()
        rule.onNodeWithTag("source-subscriptions-close").performClick()
        rule.onNodeWithTag("source-subscriptions-dialog").assertDoesNotExist()
        rule.onNodeWithTag("source-subscriptions-open").performScrollTo().performClick()
        rule.onNodeWithTag("source-subscriptions-dialog").assertIsDisplayed()
        Espresso.pressBack()
        rule.onNodeWithTag("source-subscriptions-dialog").assertDoesNotExist()
        rule.onNodeWithText("CinemaTV 设置").assertExists()
    }
}
