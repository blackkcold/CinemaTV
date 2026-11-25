package dev.cinematv

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** First Back leaves the editor/IME; the second Back follows the route rule. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TvImeBackTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()

    @Test fun twoSystemBacksLeaveSearchAfterEditing() {
        rule.runOnIdle { rule.activity.model.navigate(Page.SEARCH) }
        rule.waitForIdle()
        rule.onNodeWithTag("search-field-shell").requestFocus()
        rule.onNodeWithTag("search-field-shell").performKeyInput { pressKey(Key.DirectionCenter) }
        val view = rule.activity.window.decorView
        fun imeVisible() = ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        rule.waitUntil(10_000) { imeVisible() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { !imeVisible() }
        // Let the IME window finish dismissing before the next system Back.
        Thread.sleep(500)
        rule.waitForIdle()
        assertEquals("First Back must not leave SEARCH", Page.SEARCH, rule.activity.model.page)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        assertEquals("Second Back must return HOME", Page.HOME, rule.activity.model.page)
    }
}
