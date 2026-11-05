package dev.cinematv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.lifecycle.ViewModelProvider
import dev.cinematv.data.update.UpdateChannel
import dev.cinematv.ui.update.UpdateViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.content.Context

class MobileUpdateSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun switchesSubscriptionPersistsAndNeverOffersDebugInstall() {
        val navigation = ViewModelProvider(rule.activity)[CinemaViewModel::class.java]
        val updates = ViewModelProvider(rule.activity)[UpdateViewModel::class.java]
        rule.runOnIdle { navigation.navigate(Page.SETTINGS); updates.select(UpdateChannel.DEV) }
        rule.onNodeWithTag("update-channel-stable").performClick()
        rule.onNodeWithTag("update-status").assertTextContains("不会降级", substring = true)
        rule.onNodeWithTag("update-check").assertDoesNotExist()
        rule.onNodeWithTag("update-install").assertDoesNotExist()
        rule.onNodeWithTag("update-unavailable").assertIsDisplayed()
        rule.runOnIdle { navigation.navigate(Page.HOME) }
        rule.onNodeWithTag("update-settings").assertDoesNotExist()
        rule.runOnIdle { navigation.navigate(Page.SETTINGS) }
        // The mobile glass wrapper owns the test tag; its child button owns text semantics.
        rule.onNodeWithText("✓ 稳定版", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("stable", rule.activity.getSharedPreferences("cinematv_updates", 0).getString("channel", null))
            assertFalse(updates.enabled)
            assertFalse(updates.ready)
            assertEquals(UpdateChannel.STABLE, UpdateViewModel(rule.activity.application).channel)
        }
        rule.onNodeWithTag("update-channel-dev").performClick()
        rule.onNodeWithTag("update-channel-dev").performClick()
        rule.runOnIdle { assertEquals(UpdateChannel.DEV, updates.channel); assertFalse(updates.busy) }
        captureUpdateSettings(rule, rule.activity, "phone")
    }
}

@OptIn(ExperimentalTestApi::class)
class TvUpdateSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    @Test fun channelControlsAcceptDpadAndPreserveFocus() {
        val navigation = ViewModelProvider(rule.activity)[CinemaViewModel::class.java]
        val updates = ViewModelProvider(rule.activity)[UpdateViewModel::class.java]
        rule.runOnIdle { navigation.navigate(Page.SETTINGS); updates.select(UpdateChannel.DEV) }
        rule.onNodeWithText("关于 CinemaTV").performClick()
        val stable = rule.onNodeWithTag("update-channel-stable")
        stable.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        stable.performKeyInput { pressKey(Key.DirectionCenter) }
        stable.assertIsFocused()
        rule.runOnIdle { assertEquals(UpdateChannel.STABLE, updates.channel) }
        stable.performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("update-channel-dev").assertIsFocused()
        rule.runOnIdle { assertEquals(UpdateChannel.DEV, updates.channel) }
        rule.onNodeWithTag("update-install").assertDoesNotExist()
        captureUpdateSettings(rule, rule.activity, "tv")
    }
}

private fun captureUpdateSettings(rule: ComposeContentTestRule, context: Context, name: String) {
    rule.waitForIdle()
    val image = rule.onRoot().captureToImage().asAndroidBitmap()
    // CI uses Android's supported run-as debug interface, not access to scoped external storage.
    val directory = File(context.filesDir, "update-evidence").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use {
        check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
    }
}
