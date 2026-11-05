package dev.cinematv

import android.view.KeyEvent
import android.view.View
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import kotlin.math.ceil
import kotlin.math.floor

/** Real production controls, native input, and compositor pixels. No optical readback or video surfaces. */
@RunWith(AndroidJUnit4::class)
class GlassActionLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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
    private fun selectionIndicator(tag: String) = compose.onNode(
        hasTestTag("glass-action-selection-indicator") and hasAnyAncestor(hasTestTag(tag)),
        useUnmergedTree = true,
    )
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

    @Test fun selectedStatePreservesCompactBoundsLabelsAndIconSemantics() {
        val selected = mutableStateOf(false)
        val tags = listOf("natural", "short-height", "compact-play", "compact-label", "icon-only")
        compose.setContent {
            CompositionLocalProvider(
                LocalCinemaNoto provides fonts,
                LocalCinemaLanguageTags provides listOf("zh-Hans-CN"),
                LocalTvEffects provides TvEffects(reducedMotion = true),
            ) {
                MaterialTheme {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassAction("全部", Modifier.testTag("natural"), selected = selected.value, onClick = {})
                        GlassAction("继续播放这部很长的电影", Modifier.testTag("short-height").width(128.dp).height(35.dp),
                            selected = selected.value, onClick = {})
                        GlassAction("播放", Modifier.testTag("compact-play").width(80.dp).height(41.dp),
                            selected = selected.value, icon = CinemaSymbol.Play, onClick = {})
                        GlassAction("12", Modifier.testTag("compact-label").width(48.dp).height(36.dp),
                            selected = selected.value, onClick = {})
                        GlassAction("", Modifier.testTag("icon-only").size(39.dp)
                            .semantics { contentDescription = "打开搜索" }, selected = selected.value,
                            icon = CinemaSymbol.Search, onClick = {})
                    }
                }
            }
        }
        val bounds = tags.associateWith { button(it).fetchSemanticsNode().boundsInRoot }
        val labelBounds = tags.dropLast(1).associateWith { label(it).fetchSemanticsNode().boundsInRoot }
        val paragraphs = tags.dropLast(1).associateWith { textLayout(it).multiParagraph }
        for (isSelected in listOf(false, true, false)) {
            compose.runOnIdle { selected.value = isSelected }
            tags.forEach { tag ->
                button(tag).assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, isSelected))
                assertEquals("Selection must not resize the native target", bounds.getValue(tag),
                    button(tag).fetchSemanticsNode().boundsInRoot)
                if (isSelected) {
                    selectionIndicator(tag).assertIsDisplayed().assertWidthIsEqualTo(16.dp).assertHeightIsEqualTo(4.dp)
                        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
                        .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
                    val marker = selectionIndicator(tag).fetchSemanticsNode().boundsInRoot
                    assertInside(bounds.getValue(tag), marker)
                    assertEquals(bounds.getValue(tag).center.x, marker.center.x, 1f)
                    assertEquals(with(compose.density) { 1.dp.toPx() }, bounds.getValue(tag).bottom - marker.bottom, 1f)
                    labelBounds[tag]?.let { assertTrue("Selection must not cover label pixels", marker.top >= it.bottom) }
                } else selectionIndicator(tag).assertDoesNotExist()
            }
            labelBounds.forEach { (tag, original) ->
                assertEquals("Selection must not move the label", original, label(tag).fetchSemanticsNode().boundsInRoot)
                assertSame("Selection must preserve the shaped paragraph", paragraphs.getValue(tag), textLayout(tag).multiParagraph)
            }
            button("natural").assertTextEquals("全部")
            button("short-height").assertTextEquals("继续播放这部很长的电影")
            assertTrue(textLayout("short-height").isLineEllipsized(0))
            assertFalse(textLayout("compact-play").isLineEllipsized(0))
            assertFalse(textLayout("compact-label").isLineEllipsized(0))
            button("icon-only").assertContentDescriptionEquals("打开搜索")
        }
    }

    @Test fun selectionRemainsIndependentOfNativeFocusPressAndReducedMotion() {
        lateinit var nativeView: View
        val first = FocusRequester()
        val selected = mutableStateOf(true)
        val reducedMotion = mutableStateOf(false)
        val clicks = AtomicInteger()
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
                LocalTvEffects provides TvEffects(reducedMotion = reducedMotion.value),
            ) {
                MaterialTheme {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlassAction("其他", Modifier.testTag("first").focusRequester(first), onClick = {})
                        GlassAction("全部", Modifier.testTag("target").width(80.dp).height(40.dp).then(measured),
                            selected = selected.value, onClick = { clicks.incrementAndGet() })
                    }
                }
            }
        }
        compose.waitUntil(10_000) { nativeView.hasWindowFocus() }
        button("first").assertIsFocused().assertIsNotSelected()
        val bounds = button("target").fetchSemanticsNode().boundsInRoot
        val paragraph = textLayout("target").multiParagraph
        val marker = selectionIndicator("target").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        fun key(action: Int, code: Int) = compose.runOnUiThread {
            nativeView.dispatchKeyEvent(KeyEvent(action, code))
        }
        fun assertState(isSelected: Boolean) {
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            button("target").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, isSelected))
            assertEquals(bounds, button("target").fetchSemanticsNode().boundsInRoot)
            assertSame(paragraph, textLayout("target").multiParagraph)
            if (isSelected) {
                selectionIndicator("target").assertIsDisplayed()
                assertEquals("The persistent marker must not animate with focus/press", marker,
                    selectionIndicator("target").fetchSemanticsNode().boundsInRoot)
            } else selectionIndicator("target").assertDoesNotExist()
            button("first").assertIsNotSelected()
            selectionIndicator("first").assertDoesNotExist()
        }
        try {
            listOf(false, true).forEachIndexed { index, reduced ->
                compose.runOnIdle { reducedMotion.value = reduced }
                assertState(true)
                val originalMeasures = measurements.get()
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)
                assertState(true)
                button("target").assertIsFocused()
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
                assertState(true)
                assertEquals("Holding the native key must not activate selection", index, clicks.get())
                assertEquals("Focus/press must not remeasure", originalMeasures, measurements.get())
                // Clearing selection while the button is focused and held must
                // remove both the marker and Selected semantics immediately.
                compose.runOnIdle { selected.value = false }
                assertState(false)
                button("target").assertIsFocused()
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
                assertState(false)
                assertEquals("Exactly one click per native release", index + 1, clicks.get())
                compose.runOnIdle { selected.value = true }
                assertState(true)
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP)
                assertState(true)
                button("first").assertIsFocused()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun nativeFocusRingRemainsVisibleWithReducedMotionAndKeepsTouchBounds() {
        lateinit var nativeView: View
        val first = FocusRequester()
        val reducedMotion = mutableStateOf(true)
        val backdrop = mutableStateOf(Color.Black)
        val selected = mutableStateOf(false)
        val clicks = AtomicInteger()
        compose.setContent {
            val view = LocalView.current
            val inputMode = LocalInputModeManager.current
            SideEffect { nativeView = view }
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard); first.requestFocus() }
            CompositionLocalProvider(
                LocalCinemaNoto provides fonts,
                LocalCinemaLanguageTags provides listOf("zh-Hans-CN"),
                LocalTvEffects provides TvEffects(reducedMotion = reducedMotion.value),
            ) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().background(backdrop.value).padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        GlassAction("其他", Modifier.testTag("ring-first").focusRequester(first)
                            .width(160.dp).height(44.dp), onClick = {})
                        GlassAction("立即播放", Modifier.testTag("ring-target").width(160.dp).height(44.dp),
                            selected = selected.value, icon = CinemaSymbol.Play,
                            onClick = { clicks.incrementAndGet() })
                    }
                }
            }
        }
        compose.waitUntil(10_000) { nativeView.hasWindowFocus() }
        button("ring-first").assertIsFocused()
        val targetBounds = button("ring-target").fetchSemanticsNode().boundsInRoot
        val originalParagraph = textLayout("ring-target").multiParagraph
        val density = compose.density.density
        compose.mainClock.autoAdvance = false
        fun settle() { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
        fun key(action: Int, code: Int) = compose.runOnUiThread {
            nativeView.dispatchKeyEvent(KeyEvent(action, code))
        }
        fun move(code: Int) { key(KeyEvent.ACTION_DOWN, code); key(KeyEvent.ACTION_UP, code); settle() }
        fun capture(): Bitmap {
            compose.waitForIdle()
            val size = compose.runOnIdle { compose.activity.window.decorView.let { it.width to it.height } }
            // Actual display pixels fenced after a hardware frame. No semantics
            // flag, synthetic bitmap, or Compose/GPU redraw stands in for a ring.
            return TvWindowCapture.capture(compose.activity, size.first, size.second)
        }
        fun assertPixels(bitmap: Bitmap, tag: String, focused: Boolean, lightScene: Boolean) {
            val bounds = button(tag).fetchSemanticsNode().boundsInRoot
            val origin = IntArray(2)
            compose.runOnIdle { nativeView.getLocationOnScreen(origin) }
            val left = ceil(origin[0] + bounds.left + bounds.width * .4f).toInt()
            val right = floor(origin[0] + bounds.right - bounds.width * .4f).toInt()
            val top = origin[1] + bounds.top
            fun count(fromY: Float, toY: Float, predicate: (Int) -> Boolean): Int {
                var total = 0
                for (y in floor(fromY).toInt() until ceil(toY).toInt()) {
                    for (x in left until right) if (predicate(bitmap.getPixel(x, y))) total++
                }
                return total
            }
            fun bright(pixel: Int) = android.graphics.Color.red(pixel) >= 225 &&
                android.graphics.Color.green(pixel) >= 225 && android.graphics.Color.blue(pixel) >= 225
            fun dark(pixel: Int) = android.graphics.Color.red(pixel) < 40 &&
                android.graphics.Color.green(pixel) < 40 && android.graphics.Color.blue(pixel) < 40
            // Top-center is clear of label/icon/selection pixels and stays on
            // the straight outline in both settled focus and held-press scales.
            val core = count(top, top + 4 * density, ::bright)
            if (focused) assertTrue("$tag needs a real bright focus contour, found $core pixels",
                core >= (right - left) * density * .5f)
            else assertEquals("An unfocused button must not retain a focus contour", 0, core)
            if (lightScene) {
                val rim = count(top - 2 * density, top, ::dark)
                if (focused) assertTrue("A light scene needs a real dark outer rim, found $rim pixels",
                    rim >= (right - left) * density * .3f)
                else assertEquals("An unfocused button must not retain the outer rim", 0, rim)
            }
            assertEquals("Focus must not replace the glass with a white fill", 0,
                count(top + 6 * density, top + 7 * density, ::bright))
        }
        fun assertStable() {
            assertEquals("Focus/press must keep the native touch bounds", targetBounds,
                button("ring-target").fetchSemanticsNode().boundsInRoot)
            assertSame("Focus/press must keep the shaped paragraph", originalParagraph,
                textLayout("ring-target").multiParagraph)
        }
        try {
            var expectedClicks = 0
            for (reduced in listOf(true, false)) for (lightScene in listOf(false, true)) {
                compose.runOnIdle {
                    reducedMotion.value = reduced
                    backdrop.value = if (lightScene) Color.White else Color.Black
                    selected.value = false
                }
                settle()
                button("ring-first").assertIsFocused()
                capture().let { bitmap ->
                    try { assertPixels(bitmap, "ring-target", focused = false, lightScene) }
                    finally { bitmap.recycle() }
                }
                move(KeyEvent.KEYCODE_DPAD_DOWN)
                button("ring-target").assertIsFocused().assertIsNotSelected()
                selectionIndicator("ring-target").assertDoesNotExist()
                assertStable()
                capture().let { bitmap ->
                    try { assertPixels(bitmap, "ring-target", focused = true, lightScene) }
                    finally { bitmap.recycle() }
                }
                compose.runOnIdle { selected.value = true }
                key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
                settle()
                assertStable()
                button("ring-target").assertIsFocused().assertIsSelected()
                selectionIndicator("ring-target").assertIsDisplayed()
                assertEquals("A held key must not activate", expectedClicks, clicks.get())
                capture().let { bitmap ->
                    try { assertPixels(bitmap, "ring-target", focused = true, lightScene) }
                    finally { bitmap.recycle() }
                }
                key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
                settle()
                assertEquals(++expectedClicks, clicks.get())
                move(KeyEvent.KEYCODE_DPAD_UP)
                button("ring-first").assertIsFocused()
                button("ring-target").assertIsNotFocused().assertIsSelected()
                selectionIndicator("ring-target").assertIsDisplayed()
                assertStable()
                capture().let { bitmap ->
                    try {
                        assertPixels(bitmap, "ring-target", focused = false, lightScene)
                        assertPixels(bitmap, "ring-first", focused = true, lightScene)
                    } finally { bitmap.recycle() }
                }
            }
            // Keep the existing rectangular touch target, including its edges;
            // the decorative ring must neither intercept nor enlarge clicks.
            button("ring-target").performTouchInput { click(Offset(1f, center.y)) }
            settle()
            assertEquals(++expectedClicks, clicks.get())
            button("ring-target").performTouchInput { click(Offset(center.x * 2 - 1f, center.y)) }
            settle()
            assertEquals(++expectedClicks, clicks.get())
            assertStable()
            button("ring-target").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            settle()
            button("ring-target").assertIsFocused()
            compose.onRoot().performTouchInput {
                click(Offset(targetBounds.right + density, targetBounds.center.y))
            }
            settle()
            assertEquals("The focus contour must not expand the native hit target", expectedClicks, clicks.get())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
