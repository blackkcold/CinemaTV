package dev.cinematv

import android.os.Build
import android.view.View
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.data.MediaItem
import dev.cinematv.data.TvSource
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.components.FocusMediaCard
import dev.cinematv.ui.tv.components.ProductionGlassProvider
import dev.cinematv.ui.tv.components.rememberProductionGlassScene
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.design.LocalTvEffects
import dev.cinematv.ui.tv.design.TvEffects
import dev.cinematv.ui.tv.motion.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual production card and passive root input, not the procedural motion lab. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FocusMediaCardMotionTest {
    @get:Rule val rule = createComposeRule()
    private val reduced = mutableStateOf(false)
    private val glass = mutableStateOf(GlassQuality.BASIC)
    private val route = mutableStateOf("home")
    private val selected = mutableStateOf(false)
    private val first = mutableStateOf(MediaItem("same-id", "Motion A", year = "2026", category = "Drama",
        source = TvSource("a", "Source A", "https://unused.invalid/a"), positionMs = 60_000, durationMs = 120_000))
    private val second = MediaItem("same-id", "Motion B", source = TvSource("b", "Source B", "https://unused.invalid/b"))
    private val frames = ConcurrentHashMap<String, PosterMotionObservation>()
    private val compositions = ConcurrentHashMap<String, AtomicInteger>()
    private val clicks = AtomicInteger()
    private val backs = AtomicInteger()
    private val rootCompositions = AtomicInteger()
    private val seenLoading = mutableSetOf<String>()
    private var sourceAbsentOnApi26 = false
    @Volatile private var windowFocused = false
    private lateinit var nativeView: View
    private lateinit var motionInput: PosterMotionInput

    private fun card(index: Int = 0) = rule.onNodeWithTag("card-$index")
    private fun frame(index: Int = 0): PosterMotionObservation = checkNotNull(frames[if (index == 0) first.value.presentationKey else second.presentationKey])
    private fun step(millis: Long = 1_000) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }
    private fun focus(index: Int = 0) {
        card(index).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.mainClock.advanceTimeByFrame()
        card(index).assertIsFocused()
    }
    private fun key(key: Key) {
        // Native input still traverses the real provider/card. This component
        // harness explicitly uses mainClock for both hint time and animation;
        // these assertions are deterministic geometry tests, not device timing.
        rule.runOnUiThread {
            nativeView.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, key.nativeKeyCode))
            nativeView.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, key.nativeKeyCode))
        }
    }

    private fun mount(realReducedProvider: Boolean = false, portrait: Boolean = false, caption: Boolean = true) {
        rule.setContent {
            val view = LocalView.current
            val focusedWindow = LocalWindowInfo.current.isWindowFocused
            SideEffect { nativeView = view; windowFocused = focusedWindow }
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            val observer = remember {
                object : PosterMotionObserver {
                    override fun onFrame(observation: PosterMotionObservation) {
                        frames[observation.presentationKey] = observation
                        if (observation.loading) synchronized(seenLoading) { seenLoading += observation.presentationKey }
                    }
                    override fun onComposition(presentationKey: String) {
                        compositions.getOrPut(presentationKey) { AtomicInteger() }.incrementAndGet()
                    }
                }
            }
            val motionClock = remember {
                object : PosterMotionClock {
                    override fun nowMillis(): Long = rule.mainClock.currentTime
                    override suspend fun awaitHintExpiry(issuedAtMillis: Long) {
                        while (posterHintRemainingMillis(issuedAtMillis, nowMillis()) > 0L) {
                            withFrameNanos { /* Same mainClock that advances the springs. */ }
                        }
                    }
                }
            }
            val content: @Composable () -> Unit = {
                CompositionLocalProvider(
                    LocalPosterMotionObserver provides observer,
                    LocalPosterMotionClock provides motionClock,
                ) {
                    if (realReducedProvider) {
                        val scene = rememberProductionGlassScene()
                        SideEffect { sourceAbsentOnApi26 = Build.VERSION.SDK_INT >= 31 || scene == null }
                    }
                    PosterMotionProvider(route.value) { posterInput ->
                        val currentInput = checkNotNull(LocalPosterMotionInput.current)
                        SideEffect { motionInput = currentInput; rootCompositions.incrementAndGet() }
                        val requesters = remember { List(2) { FocusRequester() } }
                        Row(Modifier.padding(20.dp).then(posterInput).onKeyEvent {
                            if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                                if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) backs.incrementAndGet()
                                true
                            } else false
                        }, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            listOf(first.value, second).forEachIndexed { index, item ->
                                FocusMediaCard(item, onFocus = {}, onClick = { clicks.incrementAndGet() },
                                    modifier = Modifier.width(128.dp).testTag("card-$index")
                                        .focusRequester(requesters[index]).focusProperties {
                                            left = if (index == 1) requesters[0] else FocusRequester.Cancel
                                            right = if (index == 0) requesters[1] else FocusRequester.Cancel
                                            up = FocusRequester.Cancel
                                            down = FocusRequester.Cancel
                                        },
                                    portrait = portrait, selected = selected.value,
                                    continueWatching = index == 0, showCaption = caption)
                            }
                        }
                    }
                }
            }
            if (realReducedProvider) ProductionGlassProvider(GlassQuality.ENHANCED, true, content)
            else CompositionLocalProvider(LocalTvEffects provides TvEffects(reduced.value, glass.value), content = content)
        }
        rule.waitForIdle()
        rule.waitUntil(10_000) { windowFocused }
        rule.mainClock.autoAdvance = false
    }

    @Test fun focusPressAndReleaseKeepDistinctScalesAndSingleActivation() {
        mount()
        focus()
        step()
        assertEquals(1.06f, frame().scale, .001f)
        card().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertHasClickAction()
        card().performKeyInput { keyDown(Key.DirectionCenter) }
        step()
        assertTrue(frame().focused && frame().pressed)
        assertEquals(1.015f, frame().scale, .001f)
        card().performKeyInput { keyUp(Key.DirectionCenter) }
        step()
        assertEquals(1, clicks.get())
        assertEquals(1.06f, frame().scale, .001f)
        key(Key.DirectionRight)
        step()
        assertEquals(1f, frame().scale, .001f)
        card(1).assertIsFocused()
    }

    @Test fun touchPressWithoutFocusReturnsToIdentityOnCancel() {
        mount()
        // No focus was requested. A pointer press is not remote-focused press.
        card().performTouchInput { down(center) }
        step()
        assertFalse(frame().focused)
        assertTrue(frame().pressed)
        assertEquals(.97f, frame().scale, .001f)
        card().performTouchInput { cancel() }
        step()
        assertEquals(0, clicks.get())
        assertFalse(frame().pressed)
        assertEquals(1f, frame().scale, .001f)
    }

    @Test fun reducedMotionDisablesGeometryEvenMidSpringAndWhilePressed() {
        mount()
        focus()
        step(48)
        rule.runOnIdle { reduced.value = true }
        step(32)
        assertIdentity(frame())
        card().performKeyInput { keyDown(Key.DirectionCenter) }
        step()
        assertTrue(frame().pressed)
        assertIdentity(frame())
        card().performKeyInput { keyUp(Key.DirectionCenter) }
        key(Key.DirectionRight)
        step()
        card(1).assertIsFocused()
        assertIdentity(frame(1))
        assertEquals(1, clicks.get())
    }

    @Test fun rootHintTransfersToNewFocusReversesAndSettlesWithoutLoop() {
        mount()
        focus()
        step()
        val rootCount = rootCompositions.get()
        key(Key.DirectionRight)
        step(64)
        assertEquals("Hints must not recompose the provider root", rootCount, rootCompositions.get())
        card(1).assertIsFocused()
        assertTrue("Root must deliver the old card's key to the new focus", frame(1).rotationY > 0f)
        key(Key.DirectionLeft)
        step(64)
        card().assertIsFocused()
        assertTrue(frame().rotationY < 0f)
        assertTrue("Blur keeps the departing spring tail", frame(1).rotationY > 0f)
        // A boundary repeat reverses the same focused card before the first spring settles.
        key(Key.DirectionUp)
        step(48)
        assertTrue(frame().rotationX > 0f)
        key(Key.DirectionDown)
        step(80)
        assertTrue(frame().rotationX < 0f)
        step(1_000)
        assertEquals(0f, frame().rotationX, .001f)
        assertEquals(0f, frame().rotationY, .001f)
        assertEquals(0f, frame(1).rotationY, .001f)
        val settledCompositions = compositions.getValue(first.value.presentationKey).get()
        step(2_000)
        assertEquals("No perpetual motion/composition loop", settledCompositions, compositions.getValue(first.value.presentationKey).get())
    }

    @Test fun oneTestClockOwnsBothExactFreshnessAndScheduledExpiry() {
        mount()
        focus()
        step()
        key(Key.DirectionRight)
        val issuedAt = rule.runOnUiThread { motionInput.hintState.issuedAtMillis }
        assertEquals(rule.mainClock.currentTime, issuedAt)
        // Ignore frame rounding only for the exact policy boundary. Drawing and
        // the scheduled expiry still use the very same mainClock frame source.
        rule.mainClock.advanceTimeBy(109, ignoreFrameDuration = true)
        rule.runOnUiThread { assertEquals(PosterDirection(x = 1f), motionInput.direction()) }
        rule.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
        rule.runOnUiThread { assertEquals(PosterDirection.None, motionInput.direction()) }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.runOnUiThread { assertEquals(PosterDirection.None, motionInput.hintState.direction) }
    }

    @Test fun backAndRouteResetCancelHintWithoutConsumingNavigation() {
        mount()
        focus()
        step()
        key(Key.DirectionUp)
        step(48)
        assertTrue(frame().rotationX > 0f)
        key(Key.Back)
        step()
        assertEquals("Back still reaches its existing owner", 1, backs.get())
        assertEquals(0f, frame().rotationX, .001f)
        key(Key.DirectionDown)
        step(48)
        rule.runOnIdle { route.value = "restored-detail" }
        step()
        assertEquals(0f, frame().rotationX, .001f)
        card().assertIsFocused()
        assertEquals(0, clicks.get())
    }

    @Test fun animationFramesDoNotRecomposeTheWholeCardAndGlassTierDoesNotChangeMotion() {
        mount()
        focus()
        step(32)
        val count = compositions.getValue(first.value.presentationKey).get()
        val scale = frame().scale
        step(48)
        assertEquals(count, compositions.getValue(first.value.presentationKey).get())
        assertTrue("Layer must animate even while card composition stays still", frame().scale != scale)
        step()
        for (quality in GlassQuality.entries) {
            rule.runOnIdle { glass.value = quality }
            step()
            assertEquals("Motion must not silently downgrade with $quality glass", 1.06f, frame().scale, .001f)
        }
    }

    @Test fun identityCaptionsSelectionProgressAndLoadingSurviveImageFailure() {
        DelayedPosterFailure().use { server ->
            first.value = first.value.copy(poster = server.url)
            mount(portrait = true)
            rule.waitUntil(10_000) { synchronized(seenLoading) { first.value.presentationKey in seenLoading } }
            focus()
            step()
            val identity = first.value.presentationKey
            val bounds = card().getUnclippedBoundsInRoot()
            assertNotEquals("Same source id in another provider is still another card", identity, second.presentationKey)
            assertTrue(frame().loading)
            rule.runOnIdle { selected.value = true }
            step()
            assertTrue(frame().selected)
            server.release.countDown()
            rule.mainClock.autoAdvance = true
            rule.waitUntil(10_000) { frames[identity]?.failed == true }
            card().assertIsFocused().assertContentDescriptionEquals("Motion A").assertHasClickAction()
            assertEquals(bounds, card().getUnclippedBoundsInRoot())
            rule.onNodeWithText("已观看 1 分钟", useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithText("2026 · Drama", useUnmergedTree = true).assertIsDisplayed()
            assertFalse(frame().loading)
            rule.runOnIdle { first.value = first.value.copy(poster = "android.resource://dev.cinematv/${R.drawable.qa_poster}") }
            rule.waitUntil(10_000) { frames[identity]?.let { !it.failed && !it.loading } == true }
            assertEquals(identity, first.value.presentationKey)
            card().assertIsFocused()
        }
    }

    @Test fun productionReducedMotionProviderKeepsApi26SafeAndVisibleFocus() {
        mount(realReducedProvider = true, caption = false)
        focus()
        step()
        card().assertIsFocused().assertIsDisplayed()
        assertTrue(sourceAbsentOnApi26)
        card().performKeyInput { keyDown(Key.DirectionCenter) }
        step()
        assertIdentity(frame())
        assertTrue(frame().pressed)
        card().performKeyInput { keyUp(Key.DirectionCenter) }
        step()
        assertEquals(1, clicks.get())
    }

    private fun assertIdentity(value: PosterMotionObservation) {
        assertEquals(1f, value.scale, 0f)
        assertEquals(0f, value.liftDp, 0f)
        assertEquals(0f, value.rotationX, 0f)
        assertEquals(0f, value.rotationY, 0f)
        assertEquals(0f, value.artworkX, 0f)
        assertEquals(0f, value.artworkY, 0f)
    }
}

/** Explicit loopback-only image fixture. No external source, image or account is contacted. */
private class DelayedPosterFailure : AutoCloseable {
    private val socket = ServerSocket(0)
    private val worker = Executors.newSingleThreadExecutor()
    val release = CountDownLatch(1)
    val url = "http://127.0.0.1:${socket.localPort}/poster.jpg"
    init {
        worker.execute {
            runCatching {
                socket.accept().use { connection ->
                    connection.soTimeout = 10_000
                    val reader = connection.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* Read the local request headers. */ }
                    check(release.await(10, TimeUnit.SECONDS))
                    connection.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
    }
    override fun close() {
        release.countDown()
        socket.close()
        worker.shutdownNow()
    }
}
