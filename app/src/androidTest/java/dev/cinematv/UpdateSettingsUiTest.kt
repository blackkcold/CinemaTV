package dev.cinematv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.cinematv.data.update.UpdateChannel
import dev.cinematv.ui.update.UpdateViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

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
        rule.runOnIdle { navigation.navigate(Page.HOME); navigation.navigate(Page.SETTINGS) }
        rule.onNodeWithTag("update-channel-stable").assertTextContains("✓ 稳定版")
        rule.runOnIdle {
            assertEquals("stable", rule.activity.getSharedPreferences("cinematv_updates", 0).getString("channel", null))
            assertFalse(updates.enabled)
            assertFalse(updates.ready)
        }
        rule.onNodeWithTag("update-channel-dev").performClick()
        rule.onNodeWithTag("update-channel-dev").performClick()
        rule.runOnIdle { assertEquals(UpdateChannel.DEV, updates.channel); assertFalse(updates.busy) }
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
    }
}
