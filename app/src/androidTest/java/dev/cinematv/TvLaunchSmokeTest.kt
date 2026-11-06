package dev.cinematv

import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.cinematv.hybrid.HybridPolicy
import org.junit.Assert.assertTrue
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
        val tv = (rule.activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val policy = rule.runOnUiThread {
            HybridPolicy.evaluate(Build.VERSION.SDK_INT, WebViewCompat.getCurrentWebViewPackage(rule.activity)?.versionName,
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        }
        if (tv && policy.enabled) {
            rule.waitUntil(25_000) {
                rule.runOnUiThread { HybridDom.find(rule.activity.window.decorView) != null } &&
                    HybridDom.evaluate(rule.activity, "Boolean(document.querySelector('.route-settings'))") == "true"
            }
            assertTrue(HybridDom.evaluate(rule.activity, "document.activeElement?.dataset.focus || ''").isNotBlank())
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            rule.waitForIdle()
            assertTrue(HybridDom.evaluate(rule.activity, "document.activeElement?.dataset.focus || ''").isNotBlank())
        } else {
            rule.onNodeWithText("CinemaTV 设置").assertExists()
            rule.onNodeWithText("LibreTV 服务器").assertExists()
            rule.onNodeWithText("连接 / 刷新").assertExists()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            rule.waitForIdle()
            rule.onNodeWithText("CinemaTV 设置").assertExists()
        }
    }
}
