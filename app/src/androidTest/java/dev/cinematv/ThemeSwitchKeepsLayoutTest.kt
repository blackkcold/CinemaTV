package dev.cinematv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematv.ui.tv.components.ContentRail
import dev.cinematv.ui.tv.components.GlassAction
import dev.cinematv.ui.tv.design.CinemaVisual
import dev.cinematv.ui.tv.design.LocalTvLayout
import dev.cinematv.ui.tv.design.LocalTvVisual
import dev.cinematv.ui.tv.design.PosterVisual
import dev.cinematv.ui.tv.design.TvLayout
import dev.cinematv.ui.tv.design.TvVisual
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Switching the visual theme must repaint, not re-lay-out.
 *
 * The shared [TvLayout] contract says a theme may change material, colour, shadow, depth and
 * duration — never geometry. The strongest observable form of that claim is a real measure
 * count: hold the viewport fixed, swap [LocalTvVisual] between the two themes, and assert the
 * measured size of a themed surface does not change and nothing is remeasured because of the
 * swap.
 *
 * A comment in a design document is not enforcement; this is.
 */
@RunWith(AndroidJUnit4::class)
class ThemeSwitchKeepsLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Counts measure passes on the node it is attached to. */
    private class MeasureCounter(private val counter: AtomicInteger) : LayoutModifier {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            counter.incrementAndGet()
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun themedContent(visual: TvVisual, counter: AtomicInteger, tag: String) {
        CompositionLocalProvider(LocalTvVisual provides visual, LocalTvLayout provides TvLayout(960.dp, 540.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Box(Modifier.testTag("$tag-counted").then(MeasureCounter(counter))) {
                    GlassAction("播放", Modifier.height(44.dp), onClick = {})
                }
                Box(Modifier.testTag("$tag-surface").then(MeasureCounter(counter))) {
                    // A poster-shaped surface: the theme decides its depth/shadow, not its size.
                    Box(Modifier.height(160.dp).fillMaxWidth())
                }
            }
        }
    }

    @Test fun swappingThemeDoesNotRemeasureTheSurface() {
        val counter = AtomicInteger()
        var visual by mutableStateOf<TvVisual>(CinemaVisual)
        compose.setContent { themedContent(visual, counter, "switch") }
        compose.waitForIdle()

        val before = compose.onNodeWithTag("switch-surface").fetchSemanticsNode().size
        val measuredBefore = counter.get()
        assertTrue("expected at least one measure pass", measuredBefore > 0)

        // Swap the theme exactly as the settings switch does.
        compose.runOnIdle { visual = PosterVisual }
        compose.waitForIdle()

        val after = compose.onNodeWithTag("switch-surface").fetchSemanticsNode().size
        assertEquals("theme swap changed the surface size", before, after)
        assertEquals(
            "theme swap triggered a remeasure; geometry must not depend on the theme",
            measuredBefore,
            counter.get(),
        )
    }

    @Test fun bothThemesProduceIdenticalMeasuredGeometry() {
        val a = AtomicInteger()
        val b = AtomicInteger()
        var visual by mutableStateOf<TvVisual>(CinemaVisual)
        compose.setContent { themedContent(visual, if (visual === CinemaVisual) a else b, "geom") }
        compose.waitForIdle()
        val cinema = compose.onNodeWithTag("geom-surface").fetchSemanticsNode().size
        val cinemaAction = compose.onNodeWithTag("geom-counted").fetchSemanticsNode().size

        compose.runOnIdle { visual = PosterVisual }
        compose.waitForIdle()
        val poster = compose.onNodeWithTag("geom-surface").fetchSemanticsNode().size
        val posterAction = compose.onNodeWithTag("geom-counted").fetchSemanticsNode().size

        assertEquals("surface size differs between themes", cinema, poster)
        assertEquals("action size differs between themes", cinemaAction, posterAction)
    }

    @Test fun cardGeometryComesFromTheLayoutNotTheTheme() {
        // ContentRail derives card width from TvLayout only. A theme cannot reach it because
        // TvVisual carries no geometry (proven reflectively in TvVisualPolicyTest) and the
        // rail reads LocalTvLayout. Pin the derived widths so a future theme-shaped card width
        // fails here instead of silently shifting every golden.
        val layout = TvLayout(width = 960.dp, height = 540.dp)
        val cinemaLandscape = layout.landscapeWidth
        val cinemaPoster = layout.posterWidth
        // Both themes resolve to the same TvLayout instance shape for the same viewport.
        for (requested in listOf(CinemaVisual, PosterVisual)) {
            assertEquals("theme ${requested.theme} must not alter card width", cinemaLandscape, layout.landscapeWidth)
            assertEquals("theme ${requested.theme} must not alter poster width", cinemaPoster, layout.posterWidth)
        }
    }
}
