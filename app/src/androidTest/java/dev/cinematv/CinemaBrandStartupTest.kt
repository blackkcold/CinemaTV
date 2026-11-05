package dev.cinematv

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.shared.BrandMotion
import dev.cinematv.ui.shared.BrandMotionCapabilities
import dev.cinematv.ui.shared.CinemaBrandSession
import dev.cinematv.ui.shared.CinemaBrandStartup
import dev.cinematv.ui.shared.LocalBrandMotion
import dev.cinematv.ui.shared.LocalBrandMotionCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runtime behavior, not screenshot inference: inspect the actual session Animatable. */
@OptIn(ExperimentalTestApi::class)
class CinemaBrandStartupTest {
    // Explicit capability and duration-scale overrides make motion and fallback
    // deterministic regardless of the host animator configuration.
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })
    private lateinit var motion: BrandMotion
    private lateinit var hostView: View
    private lateinit var inputModeManager: InputModeManager
    private var clicks = 0
    private val showHeader = mutableStateOf(true)
    private val reduced = mutableStateOf(false)

    private fun mount(lowCost: Boolean = false, capabilities: BrandMotionCapabilities = BrandMotionCapabilities(false, 256, true)) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val view = LocalView.current
            val inputMode = LocalInputModeManager.current
            SideEffect {
                hostView = view
                inputModeManager = inputMode
            }
            CompositionLocalProvider(LocalBrandMotionCapabilities provides capabilities) {
                CinemaBrandSession(reducedMotion = reduced.value, lowCost = lowCost) {
                    val current = checkNotNull(LocalBrandMotion.current)
                    SideEffect { motion = current }
                    MaterialTheme {
                        Column {
                            if (showHeader.value) CinemaBrandStartup(Modifier.width(140.dp).height(34.dp))
                            Button(onClick = { clicks++ }) { Text("Continue") }
                            Button(onClick = { clicks++ }) { Text("Settings") }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // This isolated Material3 host does not run CinemaTvTheme's keyboard-mode
        // setup. Inherited touch mode makes its buttons deliberately unfocusable,
        // and RequestFocus alone does not change Android's input mode (API 26 too).
        compose.waitUntil(10_000) {
            compose.runOnIdle { hostView.isAttachedToWindow && hostView.isShown && hostView.hasWindowFocus() }
        }
        compose.runOnIdle {
            assertTrue("Could not establish keyboard input mode", inputModeManager.requestInputMode(InputMode.Keyboard))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputModeManager.inputMode) }
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        // The animation clock is intentionally paused; flush focus/press semantics
        // without relying on autoAdvance or changing the motion assertions.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun interactWithBothControls() {
        compose.onNodeWithText("Continue").assertIsEnabled().assertHasClickAction()
            .performSemanticsAction(SemanticsActions.RequestFocus) {
                assertTrue("Continue rejected keyboard focus", it())
            }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Continue").assertIsFocused()
        key(Key.DirectionCenter)
        key(Key.DirectionDown)
        compose.onNodeWithText("Settings").assertIsEnabled().assertHasClickAction().assertIsFocused()
        key(Key.DirectionCenter)
    }

    @Test fun originalSweepDoesNotBlockControlsAndStopsWithoutNavigationReplay() {
        mount()
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle {
            assertTrue(motion.enabled)
            assertTrue(motion.elapsed.isRunning)
            assertTrue(motion.elapsed.value > 0f && motion.elapsed.value < 6000f)
        }
        interactWithBothControls()
        compose.mainClock.advanceTimeBy(6000)
        compose.runOnIdle {
            assertEquals(2, clicks)
            assertEquals(6000f, motion.elapsed.value, 0.1f)
            assertFalse(motion.elapsed.isRunning)
            assertTrue(motion.finished)
            showHeader.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { showHeader.value = true }
        compose.mainClock.advanceTimeByFrame()
        interactWithBothControls()
        // An infinite/restarted transition would remain running or change elapsed.
        compose.mainClock.advanceTimeBy(12000)
        compose.runOnIdle {
            assertEquals(4, clicks)
            assertEquals(6000f, motion.elapsed.value, 0.1f)
            assertFalse(motion.elapsed.isRunning)
            assertTrue(motion.finished)
        }
    }

    private fun assertStaticAndInteractive() {
        compose.mainClock.advanceTimeBy(8000)
        interactWithBothControls()
        compose.runOnIdle {
            assertFalse(motion.enabled)
            assertFalse(motion.elapsed.isRunning)
            assertEquals(0f, motion.elapsed.value, 0f)
            assertEquals(2, clicks)
        }
    }

    @Test fun reducedMotionIsStaticAndInteractive() {
        reduced.value = true
        mount()
        assertStaticAndInteractive()
    }

    @Test fun explicitBasicQualityIsStaticAndInteractive() {
        mount(lowCost = true)
        assertStaticAndInteractive()
    }

    @Test fun autoDeviceWith128MbMemoryClassIsStaticEvenWithoutLowRamFlag() {
        mount(capabilities = BrandMotionCapabilities(false, 128, true))
        assertStaticAndInteractive()
    }

    @Test fun lowRamDeviceIsStaticAndInteractive() {
        mount(capabilities = BrandMotionCapabilities(true, 256, true))
        assertStaticAndInteractive()
    }

    @Test fun systemAnimatorOffIsStaticAndInteractive() {
        mount(capabilities = BrandMotionCapabilities(false, 256, false))
        assertStaticAndInteractive()
    }

    @Test fun enablingReducedMotionCancelsAndCannotRestartTheSession() {
        mount()
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { reduced.value = true }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertFalse(motion.enabled)
            assertFalse(motion.elapsed.isRunning)
            assertTrue(motion.finished)
            reduced.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        val stopped = compose.runOnIdle { motion.elapsed.value }
        compose.mainClock.advanceTimeBy(8000)
        interactWithBothControls()
        compose.runOnIdle {
            assertEquals(stopped, motion.elapsed.value, 0f)
            assertFalse(motion.elapsed.isRunning)
            assertTrue(motion.finished)
        }
    }
}
