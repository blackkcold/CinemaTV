package dev.cinematv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.ui.shared.metadataLine
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.TvRootApp
import dev.cinematv.ui.tv.motion.PosterMotionTokens
import dev.cinematv.ui.typography.CinemaNotoProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Home/rail/card layout, loopback catalog, and Android remote input. No screenshot or player. */
@RunWith(AndroidJUnit4::class)
class ContentRailVisibilityTest {
    @get:Rule val rule = createAndroidComposeRule<TvTestActivity>()
    private lateinit var server: UiFixtureServer
    private val model get() = rule.activity.model
    private val horizontal = SemanticsProperties.HorizontalScrollAxisRange
    private val vertical = SemanticsProperties.VerticalScrollAxisRange

    @Before fun connectFixture() {
        rule.waitUntil(15_000) { rule.activity.bundledFontsReady || rule.activity.fontLoadFailures.isNotEmpty() }
        assertTrue("Bundled Noto failed: ${rule.activity.fontLoadFailures}", rule.activity.bundledFontsReady)
        rule.waitUntil(10_000) { rule.activity.hasWindowFocus() }
        // The canonical screenshot class may have played its synthetic stream
        // earlier in this instrumentation invocation. Reset that test-library
        // history so the exact one-Down featured-rail regression stays isolated.
        runBlocking {
            model.repo.library.initialize()
            model.repo.library.clearHistory()
        }
        server = UiFixtureServer(InstrumentationRegistry.getInstrumentation().context).apply { homeCatalog = true }
        rule.runOnIdle {
            model.updateReducedMotion(true)
            model.repo.configureMetadata(server.base, "offline-fixture-token")
            model.serverAddress = server.base
            model.connect()
            model.navigate(Page.HOME)
        }
        rule.waitUntil(20_000) { model.authorized && model.featured.size == 24 && model.tvShows.size == 24 }
        rule.runOnIdle {
            assertTrue("This regression must enter the featured rail, not a persisted history rail", model.history().isEmpty())
            assertEquals("catalog:synthetic:movie-9", model.featured[9].id)
            assertEquals("深海来信 · 10", model.featured[9].title)
            model.dismissMessage()
        }
        rule.onNodeWithText("观看详情").assertIsFocused()
    }

    @After fun cleanUp() {
        rule.mainClock.autoAdvance = true
        if (::server.isInitialized) server.close()
        rule.activity.getSharedPreferences("cinema_settings", 0).edit().clear().commit()
    }

    private fun nativeKey(code: Int, settle: Boolean = true) {
        // Dispatch through the actual Activity/Compose focus tree. Never request
        // focus, perform a semantics scroll, or call bringIntoView from the test.
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        if (settle) rule.waitForIdle()
    }

    private fun focusedCardMatcher(index: Int) = hasContentDescription(server.title(index)) and isFocused()
    private fun focusedCard(index: Int) = rule.onNode(focusedCardMatcher(index))

    private fun assertFeaturedFocus(index: Int) {
        focusedCard(index).assertIsFocused()
        rule.runOnIdle {
            assertEquals(Page.HOME, model.page)
            assertEquals("rail:为你精选", model.focusCoordinator.last("HOME")?.region)
            assertEquals(model.featured[index].presentationKey, model.focusCoordinator.last("HOME")?.itemKey)
        }
    }

    private fun enterTenthFeaturedCard() {
        nativeKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFeaturedFocus(0)
        val initial = scrollSnapshot(focusedCard(0).fetchSemanticsNode(), horizontal)
        repeat(9) { index ->
            nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertFeaturedFocus(index + 1)
        }
        val tenth = scrollSnapshot(focusedCard(9).fetchSemanticsNode(), horizontal)
        assertEquals("Remote traversal must stay in the same LazyRow", initial.nodeId, tenth.nodeId)
        assertTrue("The tenth item must actually cross the initial horizontal viewport", tenth.value > initial.value + 1f)
    }

    private fun scrollAncestor(node: SemanticsNode, axis: SemanticsPropertyKey<ScrollAxisRange>): SemanticsNode {
        var ancestor = node.parent
        while (ancestor != null) {
            if (ancestor.config.contains(axis)) return ancestor
            ancestor = ancestor.parent
        }
        throw AssertionError("No $axis ancestor for semantics node ${node.id}")
    }

    private data class ScrollSnapshot(val nodeId: Int, val value: Float)

    private fun scrollSnapshot(node: SemanticsNode, axis: SemanticsPropertyKey<ScrollAxisRange>): ScrollSnapshot {
        val ancestor = scrollAncestor(node, axis)
        return rule.runOnUiThread { ScrollSnapshot(ancestor.id, ancestor.config[axis].value()) }
    }

    private fun pageScroll(): ScrollSnapshot {
        val node = rule.onNode(SemanticsMatcher.keyIsDefined(vertical)).fetchSemanticsNode()
        return rule.runOnUiThread { ScrollSnapshot(node.id, node.config[vertical].value()) }
    }

    private fun assertSameScroll(message: String, expected: ScrollSnapshot, actual: ScrollSnapshot) {
        assertEquals("$message (scroll owner)", expected.nodeId, actual.nodeId)
        assertEquals("$message (index/offset)", expected.value, actual.value, 0f)
    }

    private fun renderedBounds(node: SemanticsNode): Rect {
        val root = rule.onRoot().fetchSemanticsNode()
        return rule.runOnUiThread {
            // getUnclippedBoundsInRoot uses the measured size with a transformed
            // origin. That misses part of a scaled card. Map all four corners
            // without clipping, including the production graphicsLayer instead.
            root.layoutInfo.coordinates.localBoundingBoxOf(node.layoutInfo.coordinates, clipBounds = false)
        }
    }

    private fun viewportFor(node: SemanticsNode): Rect {
        val root = renderedBounds(rule.onRoot().fetchSemanticsNode())
        val parent = renderedBounds(scrollAncestor(node, vertical))
        return Rect(maxOf(root.left, parent.left), maxOf(root.top, parent.top),
            minOf(root.right, parent.right), minOf(root.bottom, parent.bottom))
    }

    private fun assertInside(label: String, bounds: Rect, viewport: Rect, bottomSpace: Float) {
        assertTrue("$label is clipped: $bounds outside $viewport", bounds.width > 0f && bounds.height > 0f &&
            bounds.left >= viewport.left - .5f && bounds.top >= viewport.top - .5f &&
            bounds.right <= viewport.right + .5f && bounds.bottom <= viewport.bottom + .5f)
        assertTrue("$label needs at least 4dp below it: $bounds inside $viewport",
            viewport.bottom - bounds.bottom >= bottomSpace - .5f)
    }

    private fun assertWholeFocusedCardVisible(index: Int): Rect {
        assertFeaturedFocus(index)
        val card = focusedCard(index).assertIsDisplayed().fetchSemanticsNode()
        val viewport = viewportFor(card)
        val bottomSpace = 4f * card.layoutInfo.density.density
        val bounds = renderedBounds(card)
        assertInside("Whole focused card", bounds, viewport, bottomSpace)
        val metadata = rule.runOnIdle { model.featured[index].metadataLine() }
        assertTrue("Fixture metadata must be present", metadata.isNotBlank())
        // Match within the focused card: the hero and the later '更多电影' rail
        // legitimately repeat titles and metadata in the same semantics tree.
        listOf("Caption" to server.title(index), "Metadata" to metadata).forEach { (label, text) ->
            val child = rule.onNode(hasText(text) and hasAnyAncestor(focusedCardMatcher(index)), useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode()
            assertInside(label, renderedBounds(child), viewport, bottomSpace)
        }
        return bounds
    }

    @Test fun reducedMotionToggleKeepsFocusedCaptionVisibleAndHorizontalAnchor() {
        // Preserve the exact screenshot02 path: Down, nine Rights, then change
        // Reduce Motion while the tenth card still owns focus. No new focus event.
        enterTenthFeaturedCard()
        val reducedBounds = assertWholeFocusedCardVisible(9)
        val before = scrollSnapshot(focusedCard(9).fetchSemanticsNode(), horizontal)
        val bookmark = rule.runOnIdle { model.focusCoordinator.last("HOME") }
        rule.runOnIdle { model.updateReducedMotion(false) }
        rule.waitForIdle()
        val animatedBounds = assertWholeFocusedCardVisible(9)
        assertSameScroll("A vertical visibility request must not move the LazyRow anchor", before,
            scrollSnapshot(focusedCard(9).fetchSemanticsNode(), horizontal))
        rule.runOnIdle { assertSame("The toggle must not manufacture a new focus acquisition", bookmark, model.focusCoordinator.last("HOME")) }
        assertEquals("Normal motion must retain the approved whole-card scale", reducedBounds.width * PosterMotionTokens.FOCUS_SCALE,
            animatedBounds.width, 1f)
        assertEquals("Caption and metadata participate in the whole-card scale", reducedBounds.height * PosterMotionTokens.FOCUS_SCALE,
            animatedBounds.height, 1f)
        rule.runOnIdle { model.updateReducedMotion(true) }
        assertWholeFocusedCardVisible(9)
        assertSameScroll("Returning to Reduce Motion must preserve the horizontal anchor", before,
            scrollSnapshot(focusedCard(9).fetchSemanticsNode(), horizontal))
    }

    @Test fun nativeNavigationCancelsPendingVerticalVisibilityRequest() {
        rule.runOnIdle { model.updateReducedMotion(false) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        nativeKey(KeyEvent.KEYCODE_DPAD_DOWN, settle = false)
        focusedCard(0).assertIsFocused()
        val row = scrollAncestor(focusedCard(0).fetchSemanticsNode(), horizontal)
        assertTrue("The initial Home row must still need vertical relocation on the paused clock",
            renderedBounds(row).bottom > viewportFor(row).bottom)
        // Start the production request, then interrupt it before its animation
        // can finish. These are deterministic ownership checks, not timing claims.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        nativeKey(KeyEvent.KEYCODE_DPAD_UP, settle = false)
        rule.onNodeWithText("观看详情").assertIsFocused()
        nativeKey(KeyEvent.KEYCODE_DPAD_UP, settle = false)
        rule.onNodeWithText("精选").assertIsFocused()
        val atNavigation = pageScroll()
        rule.mainClock.advanceTimeBy(2_400)
        rule.waitForIdle()
        rule.onNodeWithText("精选").assertIsFocused()
        val settledNavigation = pageScroll()
        assertEquals(atNavigation.nodeId, settledNavigation.nodeId)
        // Passing through the hero may legitimately finish its own upward
        // relocation. The blurred rail must never pull the page back downward.
        assertTrue("A blurred rail must not keep pulling the Home viewport toward the rail",
            settledNavigation.value <= atNavigation.value)
        rule.mainClock.advanceTimeBy(2_400)
        rule.waitForIdle()
        rule.onNodeWithText("精选").assertIsFocused()
        assertSameScroll("Navigation must remain stable after the hero settles", settledNavigation, pageScroll())
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT, settle = false)
        rule.onNodeWithText("电影").assertIsFocused()
        nativeKey(KeyEvent.KEYCODE_DPAD_CENTER, settle = false)
        assertEquals(Page.MOVIES, rule.runOnUiThread { model.page })
        rule.mainClock.advanceTimeBy(2_400)
        rule.waitForIdle()
        rule.onNodeWithText("电影").assertIsFocused()
        val destination = pageScroll()
        rule.mainClock.advanceTimeBy(2_400)
        rule.waitForIdle()
        assertEquals(Page.MOVIES, rule.runOnUiThread { model.page })
        rule.onNodeWithText("电影").assertIsFocused()
        assertSameScroll("The disposed Home rail must not scroll the destination", destination, pageScroll())
    }

    @Test fun normalMotionTraversalAndDetailBackKeepWholeCardVisible() {
        rule.runOnIdle { model.updateReducedMotion(false) }
        enterTenthFeaturedCard()
        val original = assertWholeFocusedCardVisible(9)
        nativeKey(KeyEvent.KEYCODE_DPAD_LEFT)
        assertWholeFocusedCardVisible(8)
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertWholeFocusedCardVisible(9)
        nativeKey(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(15_000) { model.page == Page.DETAIL && !model.busy && model.selected?.episodes?.size == 36 }
        rule.runOnIdle {
            // Resolving metadata to a concrete source deliberately changes the
            // presentation key. Check the exact synthetic identity at that source.
            assertEquals("movie-9", model.selected?.id)
            assertEquals(server.title(9), model.selected?.title)
            assertEquals("fixture", model.selected?.source?.key)
        }
        rule.onNodeWithText("▶ 立即播放").assertIsFocused()
        Espresso.pressBack()
        val restored = assertWholeFocusedCardVisible(9)
        assertEquals("Detail/Back must not retune card width", original.width, restored.width, 1f)
        assertEquals("Detail/Back must not retune caption layout", original.height, restored.height, 1f)
    }

    @Test fun oversizedFontScaleRailSettlesWithoutRepeatedVerticalScroll() {
        // Replace only the test Activity's composition so the real root receives
        // an extreme density configuration. No fake rail, viewport, or focus path.
        val density = rule.activity.resources.displayMetrics.density
        rule.runOnUiThread {
            rule.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 12f)) {
                    CinemaNotoProvider {
                        BackHandler(enabled = model.page != Page.HOME) { model.back() }
                        TvRootApp(model)
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("观看详情").assertIsFocused()
        nativeKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFeaturedFocus(0)
        val card = focusedCard(0).fetchSemanticsNode()
        val row = scrollAncestor(card, horizontal)
        assertTrue("Exercise the oversized-row fallback, not the normal fit path",
            renderedBounds(row).height > viewportFor(card).height)
        rule.runOnIdle { model.updateReducedMotion(false) }
        rule.waitForIdle()
        val settled = pageScroll()
        rule.mainClock.autoAdvance = false
        repeat(3) {
            rule.mainClock.advanceTimeBy(1_200)
            rule.waitForIdle()
            assertFeaturedFocus(0)
            assertSameScroll("An oversized row must converge rather than repeatedly scroll", settled, pageScroll())
        }
        rule.mainClock.autoAdvance = true
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFeaturedFocus(1)
        nativeKey(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFeaturedFocus(0)
        nativeKey(KeyEvent.KEYCODE_DPAD_UP)
        // At this extreme size the hero can be outside lazy composition. Native
        // spatial focus may instead choose the always-attached top navigation.
        val exit = isFocused() and (hasText("观看详情") or hasAnyAncestor(hasTestTag("tv-main-navigation")))
        val destination = rule.onNode(exit).assertIsFocused().fetchSemanticsNode().id
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(2_400)
        rule.waitForIdle()
        assertEquals("A tall rail must not reclaim focus after native Up", destination,
            rule.onNode(exit).assertIsFocused().fetchSemanticsNode().id)
    }
}
