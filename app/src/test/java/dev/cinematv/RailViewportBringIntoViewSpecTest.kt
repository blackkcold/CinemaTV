package dev.cinematv

import dev.cinematv.ui.tv.focus.RailViewportBringIntoViewSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class RailViewportBringIntoViewSpecTest {
    private val viewport = 898f // Actual 1080p TV viewport below Home's navigation inset.
    private val rowHeight = 413f
    private val cardInset = 30f // Existing 15dp padding at density 2.
    private val cardHeight = rowHeight - 2 * cardInset

    private fun distance(offset: Float, size: Float = rowHeight) =
        RailViewportBringIntoViewSpec.calculateScrollDistance(offset, size, viewport)

    @Test fun integerPlacementNearFractionalTvPivotSatisfiesBothNestedRequests() {
        val pivot = viewport * .3f
        assertEquals(269.4f, pivot, .001f)
        // The previous TV pivot left a 0.4px residual: inside ContentInViewNode's
        // 0.5px visibility tolerance, but outside animateToZero's 0.01px finish.
        val nearestPixel = pivot.roundToInt().toFloat()
        assertTrue(abs(nearestPixel - pivot) in .01f.. .5f)
        for (rowOffset in listOf(nearestPixel, nearestPixel + 1)) {
            // The smallest card request is checked before the containing row.
            // Both must finish at the same integer placement, independently of
            // which request is the animator's selected target.
            assertEquals(0f, distance(rowOffset + cardInset, cardHeight), 0f)
            assertEquals(0f, distance(rowOffset), 0f)
        }
    }

    @Test fun revealingWholePaddedRowAlsoFinishesEveryContainedCardRequest() {
        // Exercise integer lazy placement from above, inside and below the
        // viewport. The production policy must fit the entire padded row and
        // leave no fractional target that its contained card could block.
        for (offset in -413..1311) {
            val delta = distance(offset.toFloat())
            assertEquals("Lazy placement must be able to reach the target", delta.roundToInt().toFloat(), delta, 0f)
            val visibleRow = offset - delta
            assertTrue(visibleRow >= 0 && visibleRow + rowHeight <= viewport)
            assertEquals(0f, distance(visibleRow), 0f)
            for (inset in listOf(0f, cardInset, rowHeight - 1)) {
                val size = if (inset == cardInset) cardHeight else 1f
                assertEquals("Contained child must not block the visible row at $visibleRow", 0f,
                    distance(visibleRow + inset, size), 0f)
            }
        }
    }

    @Test fun oversizedRowsSettleAtMaximumVisibilityWithoutAnImpossibleFit() {
        val oversized = viewport + 200f
        for (offset in listOf(-200f, -80f, 0f)) {
            assertEquals(0f, distance(offset, oversized), 0f)
        }
        val delta = distance(120f, oversized)
        assertEquals(120f, delta, 0f)
        assertEquals(0f, distance(120f - delta, oversized), 0f)
    }
}
