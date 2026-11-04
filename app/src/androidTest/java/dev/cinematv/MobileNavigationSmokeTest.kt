package dev.cinematv

import android.content.res.Configuration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a phone emulator; true Android TV QA is a separate device matrix. */
@RunWith(AndroidJUnit4::class)
class MobileNavigationSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun portraitPhoneOpensAndNavigatesViaTouch() {
        assertEquals(Configuration.ORIENTATION_PORTRAIT,
            rule.activity.resources.configuration.orientation)
        rule.onNodeWithContentDescription("打开搜索").performClick()
        rule.onNodeWithText("搜索影视").assertExists()
        rule.onNodeWithContentDescription("打开设置").performClick()
        rule.onNodeWithText("CinemaTV 设置").assertExists()
        rule.onNodeWithText("LibreTV 服务器").assertExists()
    }
}
