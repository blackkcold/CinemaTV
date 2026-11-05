package dev.cinematv

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.icons.CinemaSymbol
import dev.cinematv.ui.tv.design.LocalTvEffects
import dev.cinematv.ui.tv.design.TvEffects
import dev.cinematv.ui.typography.CinemaNotoFonts
import dev.cinematv.ui.typography.LocalCinemaLanguageTags
import dev.cinematv.ui.typography.LocalCinemaNoto
import dev.cinematv.ui.typography.NotoFontCache
import dev.cinematv.ui.typography.NotoRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real production controls and native D-pad input. No screenshots or video surfaces. */
@RunWith(AndroidJUnit4::class)
class GlassActionLayoutTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fonts: CinemaNotoFonts

    @Before fun preloadBundledNotoBeforeMeasuring() {
        // JUnit's instrumentation thread waits for the cache's IO load before
        // composition. No system-font frame or async font swap enters the baseline.
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        fonts = runBlocking { NotoFontCache.load(assets) }
        assertTrue("Failed bundled fonts: ${fonts.failedAssets}", fonts.failedAssets.isEmpty())
        assertEquals(NotoRegion.entries.toSet(), fonts.regional.keys)
    }

    private fun button(tag: String) = compose.onNodeWithTag(tag)
    private fun label(tag: String) = compose.onNode(
        hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
        useUnmergedTree = true,
    )
    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        label(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }
    private fun assertInside(outer: Rect, inner: Rect) {
        assertTrue("Left text edge must remain inside the control", inner.left >= outer.left)
        assertTrue("Right text edge must remain inside the control", inner.right <= outer.right)
        assertTrue("Top text edge must remain inside the control", inner.top >= outer.top)
        assertTrue("Bottom text edge must remain inside the control", inner.bottom <= outer.bottom)
    }

    @Test fun unconstrainedLabelsKeepDefaultPaddingAndFullSemantics() {
        val labels = listOf("关闭", "电影", "剧集", "开源 core 许可与源码", "OK")
        compose.setContent {
            CompositionLocalProvider(
                LocalCinemaNoto provides fonts,
                LocalCinemaLanguageTags provides listOf("zh-Hans-CN"),
                LocalTvEffects provides TvEffects(reducedMotion = true),
            ) {
                MaterialTheme {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.forEachIndexed { index, value ->
                            GlassAction(value, Modifier.testTag("default-$index"), onClick = {})
                        }
                    }
                }
            }
        }
        labels.forEachIndexed { index, value ->
            val tag = "default-$index"
            button(tag).assertTextEquals(value).assertHasClickAction().assertHeightIsAtLeast(40.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            val outer = button(tag).fetchSemanticsNode().boundsInRoot
            val inner = label(tag).assertTextEquals(value).fetchSemanticsNode().boundsInRoot
            val horizontal = with(compose.density) { 16.dp.toPx() }
            val vertical = with(compose.density) { 8.dp.toPx() }
            assertEquals(horizontal, inner.left - outer.left, 1f)
            assertEquals(horizontal, outer.right - inner.right, 1f)
            assertTrue(inner.top - outer.top >= vertical - 1f)
            assertTrue(outer.bottom - inner.bottom >= vertical - 1f)
            assertFalse("Natural-width labels must not be truncated", textLayout(tag).isLineEllipsized(0))
        }
    }

    @Test fun fixedSizesPreserveIconsAndEllipsizeOnlyTheVisibleLabel() {
        val fullLabel = "▶ 继续播放这部很长很长的中文电影与剧集标题"
        val heights = listOf(35, 36, 40, 44, 45)
        compose.setContent {
            CompositionLocalProvider(
                LocalCinemaNoto provides fonts,
                LocalCinemaLanguageTags provides listOf("zh-Hans-CN"),
                LocalTvEffects provides TvEffects(reducedMotion = true),
            ) {
                MaterialTheme {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        heights.forEach { height ->
                            GlassAction(fullLabel, Modifier.testTag("long-$height").width(128.dp).height(height.dp),
                                icon = CinemaSymbol.Play, onClick = {})
                        }
                        GlassAction("播放", Modifier.testTag("compact-play").width(80.dp).height(41.dp),
                            icon = CinemaSymbol.Play, onClick = {})
                        GlassAction("12", Modifier.testTag("compact-label").width(48.dp).height(36.dp), onClick = {})
                        GlassAction("", Modifier.testTag("icon-only").size(39.dp)
                            .semantics { contentDescription = "打开搜索" }, icon = CinemaSymbol.Search, onClick = {})
                    }
                }
            }
        }
        heights.forEach { height ->
            val tag = "long-$height"
            button(tag).assertWidthIsEqualTo(128.dp).assertHeightIsEqualTo(height.dp).assertTextEquals(fullLabel)
            label(tag).assertTextEquals(fullLabel)
            val outer = button(tag).fetchSemanticsNode().boundsInRoot
            val inner = label(tag).fetchSemanticsNode().boundsInRoot
            assertInside(outer, inner)
            // 8dp constrained inset + the unchanged 20dp icon + 6dp icon/label gap.
            assertEquals(with(compose.density) { 34.dp.toPx() }, inner.left - outer.left, 1f)
            assertTrue(outer.right - inner.right >= with(compose.density) { 8.dp.toPx() } - 1f)
            val layout = textLayout(tag)
            assertEquals(1, layout.lineCount)
            assertTrue("Long fixed-width labels need a visible ellipsis", layout.isLineEllipsized(0))
            assertEquals(fullLabel.removePrefix("▶ "), layout.layoutInput.text.text)
        }
        button("compact-play").assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(41.dp)
        button("compact-label").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(36.dp)
        for (tag in listOf("compact-play", "compact-label")) {
            assertFalse("Short labels must survive compact callers", textLayout(tag).isLineEllipsized(0))
            assertInside(button(tag).fetchSemanticsNode().boundsInRoot, label(tag).fetchSemanticsNode().boundsInRoot)
        }
        button("icon-only").assertWidthIsEqualTo(39.dp).assertHeightIsEqualTo(39.dp)
            .assertContentDescriptionEquals("打开搜索").assertHasClickAction()
    }

    @Test fun nativeFocusAndPressKeepHitTargetAndTextLayoutStable() {
        lateinit var nativeView: View
        val first = FocusRequester()
        val target = FocusRequester()
        val clicks = AtomicInteger()
        val focusEntries = AtomicInteger()
        val measurements = AtomicInteger()
        val measured = Modifier.layout { measurable, constraints ->
            measurements.incrementAndGet()
            val content = measurable.measure(constraints)
            layout(content.width, content.height) { content.placeRelative(0, 0) }
        }
        compose.setContent {
            val view = LocalView.current
            val inputMode = LocalInputModeManager.current
            SideEffect { nativeView = view }
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard); first.requestFocus() }
            CompositionLocalProvider(
                LocalCinemaNoto provides fonts,
                LocalCinemaLanguageTags provides listOf("zh-Hans-CN"),
                LocalTvEffects provides TvEffects(reducedMotion = false),
            ) {
                MaterialTheme {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlassAction("其他", Modifier.testTag("first").focusRequester(first), onClick = {})
                        GlassAction("▶ 继续播放", Modifier.testTag("target").focusRequester(target)
                            .width(160.dp).height(44.dp).then(measured), icon = CinemaSymbol.Play,
                            onFocus = { focusEntries.incrementAndGet() }, onClick = { clicks.incrementAndGet() })
                    }
                }
            }
        }
        compose.waitUntil(10_000) { nativeView.hasWindowFocus() }
        button("first").assertIsFocused()
        val bounds = button("target").fetchSemanticsNode().boundsInRoot
        val originalLayout = textLayout("target")
        val originalMeasures = measurements.get()
        compose.mainClock.autoAdvance = false
        fun key(action: Int, code: Int) = compose.runOnUiThread {
            // Directional key-up may be unconsumed after focus moves; assert the
            // actual focus/click outcome rather than the View dispatch return.
            nativeView.dispatchKeyEvent(KeyEvent(action, code))
        }
        fun assertStable() {
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            assertEquals(bounds, button("target").fetchSemanticsNode().boundsInRoot)
            // The native semantics getter copies TextLayoutResult to expose its
            // current paint style. The shaped paragraph must still be reused.
            assertSame("Focus/press must reuse the shaped paragraph",
                originalLayout.multiParagraph, textLayout("target").multiParagraph)
            assertEquals("Draw transforms must not remeasure the control", originalMeasures, measurements.get())
        }
        try {
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)
            button("target").assertIsFocused().assertHasClickAction()
            assertStable()
            assertEquals(1, focusEntries.get())
            assertEquals(0, clicks.get())
            repeat(2) { index ->
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
                assertStable()
                assertEquals("Holding the native key must not click", index, clicks.get())
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
                assertStable()
                assertEquals("Exactly one click per native release", index + 1, clicks.get())
            }
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
            key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP)
            button("first").assertIsFocused()
            assertStable()
            assertEquals(2, clicks.get())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
