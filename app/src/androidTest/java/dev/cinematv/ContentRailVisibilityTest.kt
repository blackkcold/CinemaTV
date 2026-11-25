package dev.cinematv

import android.view.KeyEvent
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.ui.shared.metadataLine
import dev.cinematv.ui.shared.presentationKey
import dev.cinematv.ui.tv.motion.PosterMotionTokens
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Real Home/rail/card layout, loopback catalog, and Android remote input. No screenshot or player. */
@RunWith(AndroidJUnit4::class)
class ContentRailVisibilityTest {
    val rule = createAndroidComposeRule<TvTestActivity>()
    private val cleanSettingsBeforeActivity = object : ExternalResource() {
        override fun before() {
            // Other classes can leave a now-closed fixture server in preferences.
            // @Before runs too late: Activity creation has already constructed
            // the ViewModel and selected its initial route/connection work.
            val settings = InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("cinema_settings", 0)
            val hadServer = !settings.getString("server", "").isNullOrBlank()
            assertTrue("Clear fixture settings before Activity launch", settings.edit().clear().commit())
            android.util.Log.i("CinemaTVRailFixture", "pre-activity hadConfiguredServer=$hadServer settingsEmpty=${settings.all.isEmpty()}")
        }
    }
    @get:Rule val activityRules: RuleChain = RuleChain.outerRule(cleanSettingsBeforeActivity).around(rule)
    private lateinit var server: UiFixtureServer
    private lateinit var featuredAtEntry: List<FeaturedIdentity>
    private val model get() = rule.activity.model
    private val horizontal = SemanticsProperties.HorizontalScrollAxisRange
    private val vertical = SemanticsProperties.VerticalScrollAxisRange

    private data class FeaturedIdentity(
        val catalogId: String,
        val sourceId: String,
        val title: String,
        val presentationKey: String,
        val metadata: String,
    )

    @Before fun connectFixture() {
        rule.runOnUiThread {
            android.util.Log.i("CinemaTVRailFixture", "initial connected=${model.repo.isConnected} page=${model.page} " +
                "windowFocus=${rule.activity.hasWindowFocus()} viewFocus=${rule.activity.window.decorView.hasFocus()}")
            assertFalse("Every fixture must launch without a previous class's connection", model.repo.isConnected)
            assertEquals("Every fixture must start on the same clean Settings route", Page.SETTINGS, model.page)
        }
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
            // Earlier fixture activity can legitimately affect recommendations.
            // Freeze actual positions, not the server's original catalog order.
            featuredAtEntry = model.featured.take(10).mapIndexed { position, item ->
                val match = Regex("catalog:synthetic:movie-([0-9]+)").matchEntire(item.id)
                    ?: throw AssertionError("Unexpected featured fixture ID at position $position: ${item.id}")
                val sourceIndex = match.groupValues[1].toIntOrNull()
                assertTrue("Fixture ID outside movie-0..23 at position $position: ${item.id}",
                    sourceIndex != null && sourceIndex in 0..23)
                val number = requireNotNull(sourceIndex)
                assertEquals("Catalog identity at position $position", "catalog:synthetic:movie-$number", item.id)
                assertEquals("Fixture title at position $position", server.title(number), item.title)
                FeaturedIdentity(item.id, "movie-$number", item.title, item.presentationKey, item.metadataLine())
            }
            assertEquals("Freeze all ten actual remote destinations", 10, featuredAtEntry.size)
            assertEquals("Featured catalog IDs must be unique", featuredAtEntry.size,
                featuredAtEntry.map { it.catalogId }.toSet().size)
            assertEquals("Featured presentation keys must be unique", featuredAtEntry.size,
                featuredAtEntry.map { it.presentationKey }.toSet().size)
            model.dismissMessage()
        }
        try {
            rule.onNodeWithText("观看详情").assertIsFocused()
        } catch (failure: AssertionError) {
            // Capture the failing state once; do not retry, refocus, or change
            // timing to turn a failed entry into a successful test precondition.
            val focused = runCatching {
                rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                    .joinToString { "id=${it.id},bounds=${it.boundsInRoot}" }
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
            rule.runOnUiThread {
                val bookmark = model.focusCoordinator.last("HOME")
                android.util.Log.e("CinemaTVRailFixture", "hero-entry-failed page=${model.page} " +
                    "windowFocus=${rule.activity.hasWindowFocus()} viewFocus=${rule.activity.window.decorView.hasFocus()} " +
                    "bookmark=$bookmark focused=[$focused]")
            }
            throw failure
        }
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

    private fun focusedCardMatcher(index: Int) = hasContentDescription(featuredAtEntry[index].title) and isFocused()
    private fun focusedCard(index: Int) = rule.onNode(focusedCardMatcher(index))

    private fun assertFeaturedFocus(index: Int) {
        focusedCard(index).assertIsFocused()
        rule.runOnIdle {
            assertEquals(Page.HOME, model.page)
            assertEquals("Featured position changed after the remote traversal began",
                featuredAtEntry[index].catalogId, model.featured[index].id)
            assertEquals("rail:为你精选", model.focusCoordinator.last("HOME")?.region)
            assertEquals(featuredAtEntry[index].presentationKey, model.focusCoordinator.last("HOME")?.itemKey)
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

    private fun observeExistingDraws(row: SemanticsNode, card: SemanticsNode): java.io.Closeable {
        val root = rule.onRoot().fetchSemanticsNode().layoutInfo.coordinates
        val hero = rule.onNodeWithText("观看详情").fetchSemanticsNode().layoutInfo.coordinates
        val rowCoordinates = row.layoutInfo.coordinates
        val cardCoordinates = card.layoutInfo.coordinates
        val page = scrollAncestor(row, vertical)
        val pageCoordinates = page.layoutInfo.coordinates
        val axis = page.config[vertical]
        val view = rule.activity.window.decorView
        var observations = 0
        // Observe existing native draws only. Do not invalidate, schedule frames,
        // or use this callback count as a rendered-frame/performance measurement.
        val listener = ViewTreeObserver.OnDrawListener {
            if (observations++ < 256 && root.isAttached && hero.isAttached && rowCoordinates.isAttached &&
                cardCoordinates.isAttached && pageCoordinates.isAttached) {
                android.util.Log.d("CinemaTVRailVisibilityFrame",
                    "uptime=${android.os.SystemClock.uptimeMillis()} route=${model.focusRoute} scroll=${axis.value()} " +
                        "row=${root.localBoundingBoxOf(rowCoordinates, false)} " +
                        "card=${root.localBoundingBoxOf(cardCoordinates, false)} " +
                        "hero=${root.localBoundingBoxOf(hero, false)} " +
                        "viewport=${root.localBoundingBoxOf(pageCoordinates, false)} " +
                        "bookmark=${model.focusCoordinator.last(model.focusRoute)}")
            }
        }
        rule.runOnUiThread { view.viewTreeObserver.addOnDrawListener(listener) }
        return java.io.Closeable { rule.runOnUiThread { view.viewTreeObserver.removeOnDrawListener(listener) } }
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
        val metadata = featuredAtEntry[index].metadata
        assertTrue("Fixture metadata must be present", metadata.isNotBlank())
        // Match within the focused card: the hero and the later '更多电影' rail
        // legitimately repeat titles and metadata in the same semantics tree.
        listOf("Caption" to featuredAtEntry[index].title, "Metadata" to metadata).forEach { (label, text) ->
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
        val card = focusedCard(0).fetchSemanticsNode()
        val row = scrollAncestor(card, horizontal)
        val drawObservation = observeExistingDraws(row, card)
        try {
            val observations = mutableListOf<String>()
            fun observe(stage: String) {
                val hero = rule.onNodeWithText("观看详情").fetchSemanticsNode()
                val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.id }
                val observation = "$stage uptime=${android.os.SystemClock.uptimeMillis()} clock=${rule.mainClock.currentTime} " +
                    "page=${model.page} scroll=${pageScroll()} row=${renderedBounds(row)} " +
                    "viewport=${viewportFor(row)} hero=${renderedBounds(hero)} focused=$focused"
                observations += observation
                android.util.Log.i("CinemaTVRailVisibilityTest", observation)
            }
            observe("after-down")
            assertTrue("The initial Home row must still need vertical relocation on the paused clock",
                renderedBounds(row).bottom > viewportFor(row).bottom)
            // Start the production request, then interrupt it before its animation
            // can finish. These are deterministic ownership checks, not timing claims.
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            observe("before-up")
            nativeKey(KeyEvent.KEYCODE_DPAD_UP, settle = false)
            rule.onNodeWithText("观看详情").assertIsFocused()
            observe("hero-focused")
            nativeKey(KeyEvent.KEYCODE_DPAD_UP, settle = false)
            rule.onNodeWithText("精选").assertIsFocused()
            observe("navigation-focused")
            val atNavigation = pageScroll()
            // Foundation 1.10.6 removes cancelled requests immediately, but its
            // shared animator checks the queue in afterFrame, after consuming
            // the already-cached beforeFrame delta. Observe exactly that one
            // commit boundary, then reject all further downward movement.
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            rule.onNodeWithText("精选").assertIsFocused()
            val afterCommitFrame = pageScroll()
            observe("navigation-after-one-commit-frame")
            rule.mainClock.advanceTimeBy(2_400)
            rule.waitForIdle()
            rule.onNodeWithText("精选").assertIsFocused()
            val settledNavigation = pageScroll()
            observe("navigation-settled")
            assertEquals(atNavigation.nodeId, afterCommitFrame.nodeId)
            assertEquals(afterCommitFrame.nodeId, settledNavigation.nodeId)
            // Passing through the hero may legitimately finish its own upward
            // relocation. The blurred rail must never pull the page back downward.
            assertTrue("A blurred rail must not keep pulling the Home viewport toward the rail: " +
                "before=$atNavigation afterOneCommitFrame=$afterCommitFrame after=$settledNavigation " +
                "observations=${observations.joinToString("; ")}",
                settledNavigation.value <= afterCommitFrame.value)
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
        } finally {
            drawObservation.close()
        }
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
            assertEquals(featuredAtEntry[9].sourceId, model.selected?.id)
            assertEquals(featuredAtEntry[9].title, model.selected?.title)
            assertEquals("fixture", model.selected?.source?.key)
        }
        rule.onNodeWithText("▶ 立即播放").assertIsFocused()
        Espresso.pressBack()
        val restored = assertWholeFocusedCardVisible(9)
        assertEquals("Detail/Back must not retune card width", original.width, restored.width, 1f)
        assertEquals("Detail/Back must not retune caption layout", original.height, restored.height, 1f)
    }

    @Test fun oversizedFontScaleRailSettlesWithoutRepeatedVerticalScroll() {
        // Change configuration on the same real root whose normal Home entry
        // was already verified. No additional Home remount or automatic focus
        // restoration precondition; this does not fix general root recreation.
        rule.onNodeWithText("观看详情").assertIsFocused()
        nativeKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFeaturedFocus(0)
        val bookmarkBeforeResize = rule.runOnIdle { model.focusCoordinator.last("HOME") }
        rule.runOnIdle { rule.activity.testFontScaleOverride = 12f }
        rule.waitForIdle()
        assertFeaturedFocus(0)
        rule.runOnIdle {
            assertSame("Changing font scale must retain the same focus acquisition",
                bookmarkBeforeResize, model.focusCoordinator.last("HOME"))
        }
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
        val exit = isFocused() and (hasText("观看详情") or hasText("查看全部")
            or hasTestTag("hero-page-prev") or hasTestTag("hero-page-next")
            or hasAnyAncestor(hasTestTag("tv-main-navigation")))
        val destination = rule.onNode(exit).assertIsFocused().fetchSemanticsNode().id
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(2_400)
        rule.waitForIdle()
        assertEquals("A tall rail must not reclaim focus after native Up", destination,
            rule.onNode(exit).assertIsFocused().fetchSemanticsNode().id)
    }
}
