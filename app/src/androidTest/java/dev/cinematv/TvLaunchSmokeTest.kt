package dev.cinematv

import android.view.KeyEvent
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic launch and remote smoke on emulator. Real TV and HDMI/SoC certification
 * remains mandatory and cannot be inferred from passing this test.
 */
@RunWith(AndroidJUnit4::class)
class TvLaunchSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun initialSettingsIsNavigableWithoutTouch() {
        rule.onNodeWithText("CinemaTV 设置").assertExists()
        rule.onNodeWithText("LibreTV 服务器").assertExists()
        rule.onNodeWithText("连接 / 刷新").assertExists()
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.waitForIdle()
        rule.onNodeWithText("CinemaTV 设置").assertExists()
    }
}
