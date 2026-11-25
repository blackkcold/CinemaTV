package dev.cinematv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.tv.design.LocalTvEffects
import dev.cinematv.ui.tv.design.TvEffects
import dev.cinematv.ui.tv.focus.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production RestorePageFocus/registry, native D-pad, and explicitly paused frame/lazy work. */
@RunWith(AndroidJUnit4::class)
class InterruptedFocusRestorationTest {
    @get:Rule val rule = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var model: CinemaViewModel
    private lateinit var nativeView: View
    private val registry = TvFocusRegistry()
    private val owner = TvNavigationFocusOwner()
    private val navigation = FocusRequester()
    private val movies = FocusRequester()
    private val first = FocusRequester()
    private val newer = FocusRequester()
    private val otherDefault = FocusRequester()
    private val defaultActionLabel = mutableStateOf("Search")
    private var defaultActionClicks = 0
    private val mountedRestore = mutableStateOf(false)
    @Volatile private var windowFocused = false
    @Volatile private var prepareStarted = false

    @After fun cleanUp() {
        rule.mainClock.autoAdvance = true
        rule.runOnUiThread { store.clear() }
    }

    private fun mount(defaultActions: Boolean = false) {
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
            var navigationHasFocus by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalTvFocusRegistry provides registry,
                LocalContentFocus provides first, LocalNavigationFocus provides navigation,
                LocalNavigationHasFocus provides navigationHasFocus,
                LocalNavigationFocusOwner provides owner,
                LocalTvContentFocusOwner provides owner,
                LocalTvEffects provides TvEffects(reducedMotion = true)) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Row(Modifier.onFocusChanged {
                        owner.update(it.hasFocus)
                        navigationHasFocus = it.hasFocus
                    }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(80.dp).testTag("nav-home").focusRequester(navigation)
                            .focusProperties { canFocus = true; right = movies; down = first }
                            .clickable { model.navigate(Page.HOME) })
                        Box(Modifier.size(80.dp).testTag("nav-movies").focusRequester(movies)
                            .focusProperties { canFocus = true; left = navigation; down = first }
                            .clickable { model.navigate(Page.MOVIES) })
                    }
                    Spacer(Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Target("saved", first, newer)
                        if (defaultActions) {
                            // Intentionally no onFocus callback or bookmark writes:
                            // this is the production control used by filters/search.
                            GlassAction(defaultActionLabel.value, Modifier.size(100.dp).testTag("default-action")
                                .focusRequester(newer).focusProperties { up = navigation; left = first; right = otherDefault },
                                onClick = { defaultActionClicks++ })
                            GlassAction("Other", Modifier.size(100.dp).testTag("other-default")
                                .focusRequester(otherDefault).focusProperties { up = navigation; left = newer }, onClick = {})
                        } else Target("newer", newer, first)
                    }
                }
                if (mountedRestore.value) RestorePageFocus(model)
            }
        }
        rule.waitForIdle()
        rule.waitUntil(10_000) { windowFocused }
        rule.runOnUiThread { assertTrue(first.requestFocus()) }
        rule.onNodeWithTag("saved").assertIsFocused()
        rule.mainClock.autoAdvance = false
    }

    @Composable private fun Target(key: String, entry: FocusRequester, other: FocusRequester) {
        val target = rememberFocusTarget(model, "rail", key)
        Box(Modifier.size(100.dp).testTag(key).focusRequester(entry).focusRequester(target)
            .trackTvContentFocus()
            .focusProperties { canFocus = true; up = navigation; right = other; left = other }
            .onFocusChanged { if (it.isFocused) model.focusCoordinator.remember(model.focusRoute, "rail", key) }
            .focusable())
    }

    private fun nativeKey(code: Int) = rule.runOnUiThread {
        nativeView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        nativeView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun startRestore() {
        rule.runOnUiThread { mountedRestore.value = true }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals("Pending", rule.runOnUiThread { owner.snapshot().restoreState })
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(160)
        rule.waitForIdle()
    }

    private fun suspendLazyRestore(): CompletableDeferred<String?> {
        val release = CompletableDeferred<String?>()
        rule.runOnUiThread {
            registry.attachLazyRegion("HOME", "rail") { _, _ -> prepareStarted = true; release.await() }
        }
        startRestore()
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        assertTrue("The production registry must actually be suspended in prepare", prepareStarted)
        assertEquals("Pending", rule.runOnUiThread { owner.snapshot().restoreState })
        return release
    }

    private fun assertAbortedWithoutRequest() {
        val snapshot = rule.runOnUiThread { owner.snapshot() }
        assertEquals("Aborted", snapshot.restoreState)
        assertNull("Neither stale target nor hero fallback may request focus", snapshot.restoreRequestedAtNanos)
        assertNotNull(snapshot.restoreFinishedAtNanos)
    }

    @Test fun nativeUpDuringEntryFramesKeepsNavigationAndRightCenterActivatesMovies() {
        mount()
        startRestore()
        nativeKey(KeyEvent.KEYCODE_DPAD_UP)
        // No Compose frame between input and the live-owner assertion.
        assertTrue(rule.runOnUiThread { owner.hasFocus })
        rule.onNodeWithTag("nav-home").assertIsFocused()
        settle()
        rule.onNodeWithTag("nav-home").assertIsFocused()
        assertAbortedWithoutRequest()
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        nativeKey(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(Page.MOVIES, rule.runOnUiThread { model.page })
    }

    @Test fun nativeUpDuringLazyPrepareIsObservedBeforeNavigationRecomposition() {
        mount()
        val release = suspendLazyRestore()
        rule.runOnUiThread {
            nativeView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            nativeView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
            assertTrue(owner.hasFocus)
            release.complete("saved")
        }
        settle()
        rule.onNodeWithTag("nav-home").assertIsFocused()
        assertAbortedWithoutRequest()
    }

    @Test fun interruptedMissingLazyItemMustNotFallBackToHero() {
        mount()
        val release = suspendLazyRestore()
        nativeKey(KeyEvent.KEYCODE_DPAD_UP)
        rule.runOnUiThread { release.complete(null) }
        settle()
        rule.onNodeWithTag("nav-home").assertIsFocused()
        assertAbortedWithoutRequest()
    }

    @Test fun nativeUpBetweenLazyAttachmentFramesKeepsNavigation() {
        mount()
        val release = suspendLazyRestore()
        rule.runOnUiThread { release.complete("saved") }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        assertEquals("Pending", rule.runOnUiThread { owner.snapshot().restoreState })
        nativeKey(KeyEvent.KEYCODE_DPAD_UP)
        settle()
        rule.onNodeWithTag("nav-home").assertIsFocused()
        assertAbortedWithoutRequest()
    }

    @Test fun nativeRightWhileLazyPrepareSuspendsPreservesNewContentTarget() {
        mount()
        val release = suspendLazyRestore()
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("newer").assertIsFocused()
        rule.runOnUiThread { release.complete("saved") }
        settle()
        rule.onNodeWithTag("newer").assertIsFocused()
        assertAbortedWithoutRequest()
    }

    @Test fun routeChangeDuringPrepareRejectsOldRequestBeforeRecomposition() {
        mount()
        val release = suspendLazyRestore()
        rule.runOnUiThread {
            model.navigate(Page.MOVIES)
            release.complete("saved")
        }
        // Resume lazy continuation before allowing a recomposition to cancel the effect.
        rule.waitForIdle()
        assertAbortedWithoutRequest()
        assertEquals(Page.MOVIES, rule.runOnUiThread { model.page })
    }

    @Test fun uninterruptedLazyRestorationKeepsExactSavedTarget() {
        mount()
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("newer").assertIsFocused()
        rule.runOnUiThread { model.focusCoordinator.remember("HOME", "rail", "saved") }
        val release = suspendLazyRestore()
        rule.runOnUiThread { release.complete("saved") }
        settle()
        rule.onNodeWithTag("saved").assertIsFocused()
        val snapshot = rule.runOnUiThread { owner.snapshot() }
        assertEquals("Restored", snapshot.restoreState)
        assertNotNull(snapshot.restoreRequestedAtNanos)
    }

    @Test fun missingBookmarkStillUsesNormalEntryFallback() {
        mount()
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.runOnUiThread { model.focusCoordinator.clear() }
        startRestore()
        settle()
        rule.onNodeWithTag("saved").assertIsFocused()
        assertEquals("Fallback", rule.runOnUiThread { owner.snapshot().restoreState })
    }

    @Test fun defaultGlassActionNativeFocusInterruptsLazyRestoreWithoutChangingBookmark() {
        mount(defaultActions = true)
        val release = suspendLazyRestore()
        val savedBookmark = rule.runOnUiThread { model.focusCoordinator.last("HOME") }
        val generation = rule.runOnUiThread { owner.snapshot().contentAcquisition }
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("default-action").assertIsFocused()
        rule.runOnUiThread {
            assertSame(savedBookmark, model.focusCoordinator.last("HOME"))
            assertEquals(generation + 1, owner.snapshot().contentAcquisition)
            release.complete("saved")
        }
        settle()
        rule.onNodeWithTag("default-action").assertIsFocused()
        assertAbortedWithoutRequest()
        nativeKey(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("The passive hook must not consume activation", 1, rule.runOnUiThread { defaultActionClicks })
    }

    @Test fun defaultGlassActionRoundTripInvalidatesRestoreEvenWithSameFinalFocusAndBookmark() {
        mount(defaultActions = true)
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("default-action").assertIsFocused()
        val release = suspendLazyRestore()
        val savedBookmark = rule.runOnUiThread { model.focusCoordinator.last("HOME") }
        val generation = rule.runOnUiThread { owner.snapshot().contentAcquisition }
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("other-default").assertIsFocused()
        nativeKey(KeyEvent.KEYCODE_DPAD_LEFT)
        rule.onNodeWithTag("default-action").assertIsFocused()
        rule.runOnUiThread {
            assertSame(savedBookmark, model.focusCoordinator.last("HOME"))
            assertEquals(generation + 2, owner.snapshot().contentAcquisition)
            release.complete("saved")
        }
        settle()
        rule.onNodeWithTag("default-action").assertIsFocused()
        assertAbortedWithoutRequest()
    }

    @Test fun defaultGlassActionRecompositionDoesNotCancelUninterruptedExactRestore() {
        mount(defaultActions = true)
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("default-action").assertIsFocused()
        val release = suspendLazyRestore()
        val generation = rule.runOnUiThread { owner.snapshot().contentAcquisition }
        rule.runOnUiThread { defaultActionLabel.value = "Updated" }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("default-action").assertIsFocused().assertTextEquals("Updated")
        rule.runOnUiThread {
            assertEquals("Recomposition is not a focus acquisition", generation, owner.snapshot().contentAcquisition)
            release.complete("saved")
        }
        settle()
        rule.onNodeWithTag("saved").assertIsFocused()
        rule.runOnUiThread {
            assertEquals("Restored", owner.snapshot().restoreState)
            assertEquals(generation + 1, owner.snapshot().contentAcquisition)
        }
    }
}
