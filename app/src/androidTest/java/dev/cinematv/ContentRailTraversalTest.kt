package dev.cinematv

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.components.ContentRail
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production rail/cards, native keys, and the real lazy animation on a controlled frame clock. */
@RunWith(AndroidJUnit4::class)
class ContentRailTraversalTest {
    @get:Rule val rule = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var model: CinemaViewModel
    private lateinit var nativeView: View
    @Volatile private var windowFocused = false
    private val owner = TvNavigationFocusOwner()
    private val registry = TvFocusRegistry()
    private val entry = FocusRequester()
    private val top = FocusRequester()
    private val bottom = FocusRequester()
    private val destination = FocusRequester()
    private val original = (0..23).map { index ->
        MediaItem("rail-$index", "Rail movie $index", poster = "android.resource://dev.cinematv/${R.drawable.qa_poster}",
            backdrop = "android.resource://dev.cinematv/${R.drawable.qa_scene_warm}", year = "2026")
    }
    private val items = mutableStateOf(original)

    @After fun cleanUp() {
        rule.mainClock.autoAdvance = true
        rule.runOnUiThread { store.clear() }
    }

    private fun mount() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        application.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
        rule.runOnUiThread {
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[CinemaViewModel::class.java]
            model.navigate(Page.HOME)
        }
        rule.setContent {
            val view = LocalView.current
            val focusedWindow = LocalWindowInfo.current.isWindowFocused
            val inputMode = LocalInputModeManager.current
            SideEffect { nativeView = view; windowFocused = focusedWindow }
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            LaunchedEffect(model.page) { if (model.page != Page.HOME) destination.requestFocus() }
            var navFocused by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalTvFocusRegistry provides registry,
                LocalNavigationFocusOwner provides owner, LocalTvContentFocusOwner provides owner,
                LocalNavigationHasFocus provides navFocused, LocalNavigationFocus provides top,
                LocalContentFocus provides entry, LocalTvEffects provides TvEffects(reducedMotion = false, glass = GlassQuality.BASIC),
                LocalTvLayout provides TvLayout(600.dp, 540.dp)) {
                Column(Modifier.width(600.dp).fillMaxHeight().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Back) {
                        model.navigate(Page.MOVIES)
                        true
                    } else false
                }) {
                    CompositionLocalProvider(LocalTvContentFocusOwner provides null) {
                        GlassAction("Navigation", Modifier.fillMaxWidth().height(44.dp).testTag("top")
                            .focusRequester(top).focusProperties { down = entry }
                            .onFocusChanged { owner.update(it.isFocused); navFocused = it.isFocused }, onClick = {})
                    }
                    if (model.page == Page.HOME) {
                        ContentRail("Traversal", items.value, model, portrait = true, entryRequester = entry)
                        GlassAction("Other control", Modifier.fillMaxWidth().height(44.dp).testTag("bottom")
                            .focusRequester(bottom).focusProperties { up = entry }, onClick = {})
                    } else {
                        GlassAction("Destination", Modifier.fillMaxWidth().height(44.dp).testTag("destination")
                            .focusRequester(destination), onClick = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(10_000) { windowFocused }
        rule.runOnUiThread { assertTrue(entry.requestFocus()) }
        card(0).assertIsFocused()
        rule.mainClock.autoAdvance = false
    }

    private fun card(index: Int) = rule.onNodeWithContentDescription(original[index].title)
    private fun key(code: Int) = rule.runOnUiThread {
        nativeView.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code))
        nativeView.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, code))
    }
    private fun focusedKey() = rule.runOnUiThread { model.focusCoordinator.last("HOME")?.itemKey }
    private fun step(millis: Long = 1_200) { rule.mainClock.advanceTimeBy(millis); rule.waitForIdle() }
    private fun scrollPosition(): Float = rule.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
    ).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
    private fun queueOffscreenTarget() {
        repeat(10) { key(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) }
        assertNotEquals("The far target must still require lazy preparation", original[10].presentationKey, focusedKey())
    }

    @Test fun visibleNativeTargetsTransferSynchronouslyWithoutAdvancingAFrame() {
        mount()
        val time = rule.mainClock.currentTime
        repeat(3) { index ->
            key(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
            card(index + 1).assertIsFocused()
            assertEquals(original[index + 1].presentationKey, focusedKey())
            assertEquals("Visible focus must not wait for a Compose frame", time, rule.mainClock.currentTime)
        }
    }

    @Test fun crossingViewportKeepsSmoothScrollAndFocusesTheExactStableKey() {
        mount()
        val start = scrollPosition()
        queueOffscreenTarget()
        val positions = mutableListOf<Float>()
        repeat(60) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            positions += scrollPosition()
        }
        step()
        card(10).assertIsFocused().assertIsDisplayed()
        assertEquals(original[10].presentationKey, focusedKey())
        val end = scrollPosition()
        assertTrue("The rail must actually cross its original viewport", end > start + 1f)
        assertTrue("Keep real intermediate lazy-scroll positions; do not snap every move",
            positions.any { it > start + .01f && it < end - .01f })
    }

    @Test fun rapidNativeReversalCancelsOldPendingDestination() {
        mount()
        queueOffscreenTarget()
        repeat(10) { key(AndroidKeyEvent.KEYCODE_DPAD_LEFT) }
        card(0).assertIsFocused()
        step(2_400)
        card(0).assertIsFocused()
        assertEquals(original[0].presentationKey, focusedKey())
    }

    @Test fun nativeUpInterruptsPendingLazyMoveWithoutStealingNavigation() {
        mount()
        queueOffscreenTarget()
        key(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("top").assertIsFocused()
        step(2_400)
        rule.onNodeWithTag("top").assertIsFocused()
        assertTrue(rule.runOnUiThread { owner.hasFocus })
    }

    @Test fun nativeDownInterruptsPendingLazyMoveWithoutStealingOtherControl() {
        mount()
        queueOffscreenTarget()
        key(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("bottom").assertIsFocused()
        step(2_400)
        rule.onNodeWithTag("bottom").assertIsFocused()
    }

    @Test fun nativeBackRetainsItsRouteOwnerWhileLazyMoveIsPending() {
        mount()
        queueOffscreenTarget()
        key(AndroidKeyEvent.KEYCODE_BACK)
        assertEquals(Page.MOVIES, rule.runOnUiThread { model.page })
        step(2_400)
        rule.onNodeWithTag("destination").assertIsFocused()
        assertEquals(Page.MOVIES, rule.runOnUiThread { model.page })
    }

    @Test fun reorderedDataCannotCompletePendingMoveUsingItsOldIndex() {
        mount()
        queueOffscreenTarget()
        val active = focusedKey()
        rule.runOnUiThread {
            items.value = original.toMutableList().apply { this[10] = original[20]; this[20] = original[10] }
        }
        step(2_400)
        assertEquals("Neither the old target nor the replacement at its index may steal focus", active, focusedKey())
        val activeIndex = original.indexOfFirst { it.presentationKey == active }
        assertTrue(activeIndex in 0..8)
        key(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        step()
        card(activeIndex + 1).assertIsFocused()
    }
}
