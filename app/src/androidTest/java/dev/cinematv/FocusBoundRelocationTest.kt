package dev.cinematv

import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.tv.components.FocusMediaCard
import dev.cinematv.ui.tv.design.CinemaTvTheme
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.focus.cancelRelocationOnFocusLoss
import dev.cinematv.ui.typography.CinemaNotoProvider
import dev.cinematv.ui.typography.LocalCinemaNoto
import dev.cinematv.ui.typography.NotoRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque

/** Native focus and accessibility input, production controls, and a real vertical scroller. */
@RunWith(AndroidJUnit4::class)
class FocusBoundRelocationTest {
    @get:Rule val rule = createComposeRule()
    private val probe = RelocationProbe()
    private val hero = FocusRequester()
    private val cards = List(2) { FocusRequester() }
    private val destination = FocusRequester()
    private val innerRequesters = List(2) { BringIntoViewRequester() }
    private val outerRequesters = List(2) { BringIntoViewRequester() }
    private val home = mutableStateOf(true)
    private val cardWidth = mutableStateOf(180.dp)
    private lateinit var view: View
    private lateinit var scope: CoroutineScope
    private lateinit var homeScroll: androidx.compose.foundation.ScrollState
    private lateinit var destinationScroll: androidx.compose.foundation.ScrollState
    @Volatile private var windowFocused = false
    @Volatile private var fontsReady = false
    @Volatile private var fontFailures: Set<String> = emptySet()

    @After fun cleanUp() {
        rule.mainClock.autoAdvance = true
        rule.runOnUiThread { probe.requests.forEach { it.release.complete(Unit) } }
    }

    private fun mount(useProxy: Boolean = true, holdRequests: Boolean = true) {
        probe.holdRequests = holdRequests
        rule.setContent {
            val nativeView = LocalView.current
            val hasWindowFocus = LocalWindowInfo.current.isWindowFocused
            val compositionScope = rememberCoroutineScope()
            val homeState = rememberScrollState()
            val destinationState = rememberScrollState()
            SideEffect {
                view = nativeView
                scope = compositionScope
                homeScroll = homeState
                destinationScroll = destinationState
                windowFocused = hasWindowFocus
            }
            // Only route entry chooses initial focus. Every tested transfer below
            // is an Android D-pad/Back event through the production controls.
            LaunchedEffect(home.value, hasWindowFocus) {
                if (hasWindowFocus) {
                    repeat(2) { withFrameNanos { } }
                    if (home.value) hero.requestFocus() else destination.requestFocus()
                }
            }
            CinemaNotoProvider {
                val fonts = LocalCinemaNoto.current
                SideEffect {
                    fontFailures = fonts.failedAssets
                    fontsReady = fonts.regional.size == NotoRegion.entries.size && fonts.failedAssets.isEmpty()
                }
                CinemaTvTheme(reducedMotion = true, quality = GlassQuality.BASIC) {
                    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
                        if (home.value && it.type == KeyEventType.KeyDown && it.key == Key.Back) {
                            home.value = false
                            true
                        } else false
                    }) {
                        Box(Modifier.size(600.dp, 400.dp).testTag("relocation-viewport")) {
                            if (home.value) {
                                Column(Modifier.fillMaxSize().verticalScroll(homeState)) {
                                    GlassAction("Hero", Modifier.width(180.dp).height(44.dp)
                                        .recordRelocation(probe, "hero")
                                        .focusRequester(hero).focusProperties { down = cards[0] }, onClick = {})
                                    Spacer(Modifier.height(200.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        repeat(2) { index ->
                                            FocusMediaCard(
                                                MediaItem("relocation-$index", "Relocation card $index", year = "2026",
                                                    poster = "android.resource://dev.cinematv/${R.drawable.qa_poster}"),
                                                onFocus = {}, onClick = {}, portrait = true,
                                                modifier = Modifier.width(cardWidth.value).testTag("relocation-card-$index")
                                                    .recordRelocation(probe, "card-$index")
                                                    .bringIntoViewRequester(outerRequesters[index])
                                                    .focusRequester(cards[index])
                                                    .then(if (useProxy) Modifier.cancelRelocationOnFocusLoss() else Modifier)
                                                    .bringIntoViewRequester(innerRequesters[index])
                                                    .focusProperties {
                                                        up = hero
                                                        if (index == 0) right = cards[1] else left = cards[0]
                                                    },
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(80.dp))
                                }
                            } else {
                                Column(Modifier.fillMaxSize().verticalScroll(destinationState)) {
                                    GlassAction("Destination", Modifier.width(180.dp).height(44.dp)
                                        .recordRelocation(probe, "destination")
                                        .focusRequester(destination), onClick = {})
                                    Spacer(Modifier.height(500.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitUntil(10_000) { windowFocused }
        rule.waitUntil(15_000) { fontsReady || fontFailures.isNotEmpty() }
        assertTrue("Bundled Noto failed: $fontFailures", fontsReady)
        rule.waitForIdle()
        rule.onNodeWithText("Hero").assertIsFocused()
        // An idle frame is not proof that Foundation's DEFAULT focus job ended.
        // Keep its actual enter/end evidence separate from measured requests.
        rule.waitUntil(5_000) {
            rule.runOnUiThread {
                probe.initialization.any { it.owner == "hero" && it.finished } &&
                    probe.initialization.all { it.finished }
            }
        }
        rule.runOnUiThread {
            assertTrue("The initial Hero relocation must complete before measurement",
                probe.initialization.any { it.owner == "hero" && it.completed && !it.cancelled })
            assertTrue("No initialization request may still be running or cancelled",
                probe.initialization.all { it.finished && it.completed && !it.cancelled })
            probe.enabled = true
        }
    }

    private fun nativeKey(code: Int) {
        rule.runOnUiThread {
            view.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code))
            view.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, code))
        }
        rule.waitForIdle()
    }

    private fun card(index: Int) = rule.onNodeWithTag("relocation-card-$index")

    private fun awaitEntered(owner: String, after: Int = 0): PendingRelocation {
        rule.waitUntil(5_000) { rule.runOnUiThread { probe.requests.drop(after).any { it.owner == owner } } }
        return rule.runOnUiThread { probe.requests.drop(after).first { it.owner == owner } }
    }

    private fun assertCancelled(request: PendingRelocation) {
        rule.waitUntil(5_000) { rule.runOnUiThread { request.finished } }
        rule.runOnUiThread {
            assertTrue("${request.owner}'s already-entered request must be cancelled", request.cancelled)
            assertFalse(request.completed)
        }
    }

    private fun assertPending(request: PendingRelocation) = rule.runOnUiThread {
        assertFalse("Another target must not cancel ${request.owner}'s request", request.cancelled)
        assertFalse("The controlled parent is still servicing ${request.owner}", request.finished)
    }

    private fun finish(request: PendingRelocation) {
        rule.runOnUiThread { request.release.complete(Unit) }
        rule.waitUntil(5_000) { rule.runOnUiThread { request.finished } }
        rule.waitForIdle()
        rule.runOnUiThread { assertTrue(request.completed); assertFalse(request.cancelled) }
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect {
        val root = rule.onRoot().fetchSemanticsNode()
        val child = node.fetchSemanticsNode()
        return rule.runOnUiThread {
            root.layoutInfo.coordinates.localBoundingBoxOf(child.layoutInfo.coordinates, clipBounds = false)
        }
    }

    private fun assertCardInsideViewport(index: Int) {
        val target = bounds(card(index).assertIsDisplayed())
        val viewport = bounds(rule.onNodeWithTag("relocation-viewport"))
        assertTrue("Unfocused card was not actually revealed: $target outside $viewport",
            target.top >= viewport.top - .5f && target.bottom <= viewport.bottom + .5f)
    }

    private fun assertPendingBoundsMatchCard(request: PendingRelocation, index: Int): Rect {
        val root = rule.onRoot().fetchSemanticsNode()
        val target = card(index).fetchSemanticsNode()
        return rule.runOnUiThread {
            val coordinates = root.layoutInfo.coordinates
            val local = requireNotNull(request.boundsProvider()) { "The attached request must provide bounds" }
            assertTrue("Parent must receive a positive rectangle", local.width > 0f && local.height > 0f)
            val corners = listOf(local.topLeft, local.topRight, local.bottomRight, local.bottomLeft)
                .map { coordinates.localPositionOf(request.childCoordinates, it) }
            val received = Rect(corners.minOf { it.x }, corners.minOf { it.y },
                corners.maxOf { it.x }, corners.maxOf { it.y })
            val actual = coordinates.localBoundingBoxOf(target.layoutInfo.coordinates, clipBounds = false)
            assertEquals("Forwarded left corner", actual.left, received.left, .5f)
            assertEquals("Forwarded top corner", actual.top, received.top, .5f)
            assertEquals("Forwarded right corner", actual.right, received.right, .5f)
            assertEquals("Forwarded bottom corner", actual.bottom, received.bottom, .5f)
            received
        }
    }

    @Test fun nativeFocusTransferCancelsOnlyEnteredOldCardRequests() {
        mount()
        nativeKey(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        card(0).assertIsFocused()
        val old = awaitEntered("card-0")
        assertPending(old)
        val originalBounds = assertPendingBoundsMatchCard(old, 0)
        // Re-measure the real card while this SAME request is suspended. The
        // supplier must track new geometry rather than freeze its first Rect.
        rule.runOnUiThread { cardWidth.value = 192.dp }
        rule.waitForIdle()
        assertPending(old)
        val resizedBounds = assertPendingBoundsMatchCard(old, 0)
        assertTrue("Pending bounds must follow the card's new size", resizedBounds.width > originalBounds.width)
        card(0).assertIsFocused()
        nativeKey(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        card(1).assertIsFocused()
        val next = awaitEntered("card-1")
        assertCancelled(old)
        assertPending(next)
        nativeKey(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithText("Hero").assertIsFocused()
        val heroRequest = awaitEntered("hero")
        assertCancelled(next)
        assertPending(heroRequest)
        finish(heroRequest)
        rule.onNodeWithText("Hero").assertIsFocused()
    }

    @Test fun nativeBackDisposalKeepsDestinationRelocationIndependent() {
        mount()
        nativeKey(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        val old = awaitEntered("card-0")
        assertPending(old)
        nativeKey(AndroidKeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("Destination").assertIsFocused()
        val current = awaitEntered("destination")
        assertCancelled(old)
        assertPending(current)
        finish(current)
        val settled = rule.runOnUiThread { destinationScroll.value }
        // Completing the obsolete parent's latch must not revive its cancelled
        // child or move the new route. This is not a claim about late new jobs.
        rule.runOnUiThread { old.release.complete(Unit) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_200)
        rule.waitForIdle()
        rule.onNodeWithText("Destination").assertIsFocused()
        card(0).assertDoesNotExist()
        assertEquals(settled, rule.runOnUiThread { destinationScroll.value })
    }

    @Test fun unfocusedInnerAndOuterRequestersStillReachParent() {
        mount()
        val start = rule.runOnUiThread { homeScroll.value }
        assertTrue("Fixture must require a real scroll", bounds(card(0)).bottom > bounds(rule.onNodeWithTag("relocation-viewport")).bottom)
        // This requester is INSIDE the proxy, proving the unfocused request is
        // forwarded rather than accidentally testing only a bypass path.
        lateinit var inner: Job
        rule.runOnUiThread { inner = scope.launch { innerRequesters[0].bringIntoView() } }
        val entered = awaitEntered("card-0")
        assertPending(entered)
        assertPendingBoundsMatchCard(entered, 0)
        card(0).assertIsNotFocused()
        rule.onNodeWithText("Hero").assertIsFocused()
        finish(entered)
        rule.runOnUiThread { assertTrue(inner.isCompleted); assertFalse(inner.isCancelled) }
        assertTrue(rule.runOnUiThread { homeScroll.value } > start)
        assertCardInsideViewport(0)
        rule.onNodeWithText("Hero").assertIsFocused()

        val count = rule.runOnUiThread { probe.requests.size }
        lateinit var outer: Job
        rule.runOnUiThread { outer = scope.launch { outerRequesters[1].bringIntoView() } }
        val outside = awaitEntered("card-1", after = count)
        assertPending(outside)
        finish(outside)
        rule.runOnUiThread { assertTrue(outer.isCompleted); assertFalse(outer.isCancelled) }
        card(1).assertIsNotFocused()
        rule.onNodeWithText("Hero").assertIsFocused()
    }

    @Test fun nativeAccessibilityShowOnScreenRevealsUnfocusedCard() {
        mount()
        val start = rule.runOnUiThread { homeScroll.value }
        assertTrue("Accessibility target must start partially outside the viewport",
            bounds(card(0)).bottom > bounds(rule.onNodeWithTag("relocation-viewport")).bottom)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var target: AccessibilityNodeInfo? = null
        rule.waitUntil(10_000) {
            target = findAccessibilityCard(automation.rootInActiveWindow, "Relocation card 0")
            target != null
        }
        try {
            assertTrue("Invoke the real Android accessibility action, not a semantics substitute",
                requireNotNull(target).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id))
        } finally {
            @Suppress("DEPRECATION")
            target?.recycle()
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.runOnUiThread { homeScroll.value > start } }
        rule.waitForIdle()
        assertCardInsideViewport(0)
        card(0).assertIsNotFocused()
        rule.onNodeWithText("Hero").assertIsFocused()
        // Compose 1.10.6's Android delegate drives the nearest ancestor's ScrollBy
        // semantics directly. This host deliberately has only one scroll ancestor.
        // A genuine ACTION_SHOW_ON_SCREEN therefore does not enter this BIV proxy.
        rule.runOnUiThread { assertTrue("Accessibility ScrollBy must not be mistaken for focus relocation", probe.requests.isEmpty()) }
    }

    @Test fun withoutProxyNativeBlurKeepsOldRelocationMoving() {
        assertNativeBlurTail(useProxy = false)
    }

    @Test fun withProxyNativeBlurStopsAfterOneCommittedFrame() {
        assertNativeBlurTail(useProxy = true)
    }

    private fun assertNativeBlurTail(useProxy: Boolean) {
        // This pair observes the real scroller. Unlike the lifetime tests above,
        // the parent probe must never await a latch or delay a request.
        mount(useProxy = useProxy, holdRequests = false)
        rule.onNodeWithText("Hero").assertIsFocused()
        assertTrue("The starting card must need real vertical scrolling",
            bounds(card(0)).bottom > bounds(rule.onNodeWithTag("relocation-viewport")).bottom)
        val observations = mutableListOf<String>()
        fun sample(stage: String): Int {
            val value = rule.runOnUiThread { homeScroll.value }
            val requests = rule.runOnUiThread {
                probe.requests.joinToString { "${it.owner}:finished=${it.finished},cancelled=${it.cancelled}" }
            }
            val observation = "proxy=$useProxy stage=$stage clock=${rule.mainClock.currentTime} " +
                "uptime=${android.os.SystemClock.uptimeMillis()} scrollPx=$value requests=[$requests]"
            observations += observation
            android.util.Log.i("CinemaTVRelocationPair", observation)
            return value
        }
        rule.mainClock.autoAdvance = false
        sample("initial")
        nativeKey(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        card(0).assertIsFocused()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        card(0).assertIsFocused()
        // Verify a real, still-pending default request before blur. Waiting for
        // coroutine entry does not advance the frozen clock or add a frame.
        rule.waitUntil(5_000) {
            rule.runOnUiThread { probe.requests.any { it.owner == "card-0" && !it.finished } }
        }
        val enteredCardRequests = rule.runOnUiThread {
            probe.requests.filter { it.owner == "card-0" && !it.finished }.also {
                assertTrue("The old card must have an entered request before native blur", it.isNotEmpty())
            }
        }
        val start = sample("start-after-down-frame")
        nativeKey(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithText("Hero").assertIsFocused()
        if (useProxy) {
            rule.waitUntil(5_000) {
                rule.runOnUiThread { enteredCardRequests.all { it.cancelled && it.finished } }
            }
            rule.runOnUiThread {
                assertTrue("Native blur must cancel the exact previously entered card requests",
                    enteredCardRequests.all { it.cancelled && it.finished && !it.completed })
            }
        }
        // Exactly one commit frame for native blur/cancellation. No second
        // buffer frame, arbitrary sleep, forced focus, or semantics scrolling.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithText("Hero").assertIsFocused()
        val tail = sample("tail-after-up-frame")
        rule.mainClock.advanceTimeBy(2_400)
        rule.waitForIdle()
        rule.onNodeWithText("Hero").assertIsFocused()
        val end = sample("end-after-2400ms")
        val diagnostic = "start=$start tail=$tail end=$end observations=${observations.joinToString("; ")}"
        if (!useProxy) {
            assertTrue("Negative control must detect continued old-card downward scrolling: $diagnostic", end > tail)
        } else {
            assertTrue("The cancelled old request must not keep pulling downward after one commit frame: $diagnostic", end <= tail)
            rule.mainClock.advanceTimeBy(2_400)
            rule.waitForIdle()
            rule.onNodeWithText("Hero").assertIsFocused()
            val settled = sample("settled-after-next-2400ms")
            assertEquals("The proxy-on route must remain exactly stable: ${observations.joinToString("; ")}", end, settled)
        }
    }

    @Suppress("DEPRECATION")
    private fun findAccessibilityCard(root: AccessibilityNodeInfo?, title: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val seen = mutableListOf(root)
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var found: AccessibilityNodeInfo? = null
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.packageName?.toString() == "dev.cinematv" && node.contentDescription?.toString() == title) {
                    found = node
                    break
                }
                repeat(node.childCount) { index ->
                    node.getChild(index)?.let { seen += it; queue.add(it) }
                }
            }
            return found
        } finally {
            seen.filter { it !== found }.forEach { it.recycle() }
        }
    }
}

private class PendingRelocation(
    val owner: String,
    val childCoordinates: LayoutCoordinates,
    val boundsProvider: () -> Rect?,
) {
    val release = CompletableDeferred<Unit>()
    var cancelled = false
    var completed = false
    var finished = false
}

private class RelocationProbe {
    var enabled = false
    var holdRequests = true
    val initialization = mutableListOf<PendingRelocation>()
    val requests = mutableListOf<PendingRelocation>()
}

private fun Modifier.recordRelocation(probe: RelocationProbe, owner: String): Modifier =
    then(RelocationProbeElement(probe, owner))

private data class RelocationProbeElement(val probe: RelocationProbe, val owner: String) : ModifierNodeElement<RelocationProbeNode>() {
    override fun create() = RelocationProbeNode(probe, owner)
    override fun update(node: RelocationProbeNode) { node.probe = probe; node.owner = owner }
    override fun InspectorInfo.inspectableProperties() { name = "recordRelocation" }
}

/** Delays only parent acknowledgement; after release the request reaches the real scroller. */
private class RelocationProbeNode(var probe: RelocationProbe, var owner: String) : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        val measured = probe.enabled
        val request = PendingRelocation(owner, childCoordinates, boundsProvider).also {
            if (measured) probe.requests += it else probe.initialization += it
        }
        try {
            if (measured && probe.holdRequests) request.release.await()
            if (isAttached && childCoordinates.isAttached) {
                val coordinates = requireLayoutCoordinates()
                this@RelocationProbeNode.bringIntoView {
                    if (!isAttached || !childCoordinates.isAttached || !coordinates.isAttached) null
                    else boundsProvider()?.let { bounds ->
                        val corners = listOf(bounds.topLeft, bounds.topRight, bounds.bottomRight, bounds.bottomLeft)
                            .map { coordinates.localPositionOf(childCoordinates, it) }
                        Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
                    }
                }
            }
            request.completed = true
        } catch (cancelled: CancellationException) {
            request.cancelled = true
            throw cancelled
        } finally {
            request.finished = true
        }
    }
}
