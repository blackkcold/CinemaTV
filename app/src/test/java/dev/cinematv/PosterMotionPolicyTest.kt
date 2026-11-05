package dev.cinematv

import dev.cinematv.ui.tv.motion.*
import org.junit.Assert.*
import org.junit.Test

class PosterMotionPolicyTest {
    @Test fun rawKeyEventsReverseDirectionImmediately() {
        val idle = PosterMotionInputState()
        assertEquals(PosterDirection.None, idle.directionAt(1000L))
        val right = idle.keyDown(22, 1000L)
        assertEquals(PosterDirection(1f, 0f), right.directionAt(1000L))
        val left = right.keyDown(21, 1010L)
        assertEquals(PosterDirection(-1f, 0f), left.directionAt(1010L))
        assertEquals(right.generation + 1L, left.generation)
    }

    @Test fun repeatedDirectionRefreshesTimestampAndGeneration() {
        val first = PosterMotionInputState().keyDown(22, 1000L)
        val repeated = first.keyDown(22, 1100L)
        assertEquals(first.direction, repeated.direction)
        assertEquals(first.generation + 1L, repeated.generation)
        assertEquals(1100L, repeated.issuedAtMillis)
        assertEquals(PosterDirection.None, first.directionAt(1110L))
        assertEquals(PosterDirection(1f, 0f), repeated.directionAt(1110L))
        assertEquals(PosterDirection.None, repeated.directionAt(1210L))
    }

    @Test fun oldExpiryCannotClearNewOrReversedHint() {
        val first = PosterMotionInputState().keyDown(22, 1000L)
        for (nextCode in listOf(22, 21)) {
            val next = first.keyDown(nextCode, 1050L)
            assertEquals(next, next.expire(first.generation))
            assertEquals(posterDirection(nextCode), next.expire(first.generation).directionAt(1050L))
            assertEquals(PosterDirection.None, next.expire(next.generation).directionAt(1050L))
        }
    }

    @Test fun backCenterAndEnterClearDirectionalHint() {
        val right = PosterMotionInputState().keyDown(22, 1000L)
        for (code in listOf(4, 23, 66)) {
            assertEquals(PosterDirection.None, right.keyDown(code, 1001L).directionAt(1001L))
        }
    }

    @Test fun explicitClearCancelsHintAndLaterKeyRestartsIt() {
        val first = PosterMotionInputState().keyDown(22, 1000L)
        val cleared = first.clear()
        assertEquals(PosterDirection.None, cleared.directionAt(1001L))
        val restarted = cleared.keyDown(19, 1002L)
        assertEquals(PosterDirection(0f, -1f), restarted.directionAt(1002L))
        assertEquals(restarted, restarted.expire(first.generation))
    }

    @Test fun eventDirectionExpiresAt110MillisecondsWithoutTimeoutDelivery() {
        val state = PosterMotionInputState().keyDown(20, 1000L)
        assertEquals(PosterDirection.None, state.directionAt(999L))
        assertEquals(PosterDirection(0f, 1f), state.directionAt(1000L))
        assertEquals(PosterDirection(0f, 1f), state.directionAt(1109L))
        assertEquals(PosterDirection.None, state.directionAt(1110L))
        assertEquals(PosterDirection.None, state.directionAt(10000L))
    }

    @Test fun approvedFocusAndPressScalesApplyAtEveryQuality() {
        for (quality in PosterMotionQuality.values()) {
            val policy = PosterMotionPolicy(false, quality)
            assertTrue(policy.enabled)
            assertEquals(1f, policy.scale(false, false), 0f)
            assertEquals(1.06f, policy.scale(true, false), 0f)
            assertEquals(1.015f, policy.scale(true, true), 0f)
            assertEquals(.97f, policy.scale(false, true), 0f)
        }
    }

    @Test fun focusedLiftIsPreservedAtEveryQuality() {
        assertEquals(-3f, PosterMotionTokens.FOCUS_LIFT_DP, 0f)
        for (quality in PosterMotionQuality.values()) {
            val policy = PosterMotionPolicy(false, quality)
            assertEquals(-3f, policy.lift(true), 0f)
            assertEquals(0f, policy.lift(false), 0f)
        }
    }

    @Test fun reducedMotionSuppressesLiftAtEveryQuality() {
        for (quality in PosterMotionQuality.values()) {
            val policy = PosterMotionPolicy(true, quality)
            assertEquals(0f, policy.lift(true), 0f)
            assertEquals(0f, policy.lift(false), 0f)
        }
    }

    @Test fun reducedMotionMakesAnimatedGeometryIdentityIncludingPressedStates() {
        for (quality in PosterMotionQuality.values()) {
            val policy = PosterMotionPolicy(true, quality)
            assertFalse(policy.enabled)
            assertFalse(policy.depthEnabled)
            for (focused in listOf(false, true)) for (pressed in listOf(false, true)) {
                assertEquals(1f, policy.scale(focused, pressed), 0f)
                for (input in listOf(-2f, -.5f, 0f, .5f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
                    assertEquals(0f, policy.depth(input, focused), 0f)
                }
            }
        }
    }

    @Test fun focusOnlyPreservesFullFocusWithoutParallax() {
        val policy = PosterMotionPolicy(false, PosterMotionQuality.FOCUS_ONLY)
        assertFalse(policy.depthEnabled)
        assertEquals(PosterMotionTokens.FOCUS_SCALE, policy.scale(true, false), 0f)
        for (focused in listOf(false, true)) for (input in listOf(-1f, .5f, 1f, Float.NaN)) {
            assertEquals(0f, policy.depth(input, focused), 0f)
        }
    }

    @Test fun layeredDepthRequiresFocusAndClampsFiniteInput() {
        val policy = PosterMotionPolicy(false)
        assertTrue(policy.depthEnabled)
        for (input in listOf(-Float.MAX_VALUE, -2f, -1f, -.5f, 0f, .5f, 1f, 2f, Float.MAX_VALUE)) {
            assertEquals(input.coerceIn(-1f, 1f), policy.depth(input, true), 0f)
            assertEquals(0f, policy.depth(input, false), 0f)
        }
    }

    @Test fun nonFiniteDirectionalInputNeverPropagates() {
        for (quality in PosterMotionQuality.values()) for (reduced in listOf(false, true)) {
            val policy = PosterMotionPolicy(reduced, quality)
            for (input in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
                assertEquals(0f, policy.depth(input, true), 0f)
            }
        }
    }

    @Test fun nativeDpadCodesMapToUnitDirections() {
        assertEquals(PosterDirection(-1f, 0f), posterDirection(21))
        assertEquals(PosterDirection(1f, 0f), posterDirection(22))
        assertEquals(PosterDirection(0f, -1f), posterDirection(19))
        assertEquals(PosterDirection(0f, 1f), posterDirection(20))
    }

    @Test fun centerBackEnterAndUnknownKeysDoNotAddDirection() {
        for (code in listOf(23, 4, 66, 0, -1, 85, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(PosterDirection.None, posterDirection(code))
        }
    }

    @Test fun directionExpiresExactlyAt110Milliseconds() {
        assertEquals(110L, PosterMotionTokens.DIRECTION_HINT_MILLIS)
        assertTrue(posterHintIsFresh(1000L, 1000L))
        assertTrue(posterHintIsFresh(1000L, 1109L))
        assertFalse(posterHintIsFresh(1000L, 1110L))
        assertFalse(posterHintIsFresh(1000L, 1111L))
        assertFalse(posterHintIsFresh(1000L, 999L))
    }

    @Test fun directionFreshnessHandlesLongBoundaryWithoutOverflow() {
        assertTrue(posterHintIsFresh(Long.MAX_VALUE - 109L, Long.MAX_VALUE))
        assertFalse(posterHintIsFresh(Long.MAX_VALUE - 110L, Long.MAX_VALUE))
        assertFalse(posterHintIsFresh(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun artworkOverscanCoversBothEdgesForTinyPortraitAndLandscapeExtents() {
        val sizes = listOf(.01f to .02f, 1f to 1f, 120f to 180f, 320f to 180f, 1920f to 1080f)
        for ((width, height) in sizes) for (extent in listOf(width, height)) {
            for (translation in listOf(.25f, 4f, 12f, 64f)) {
                val scale = posterArtworkScale(extent, translation)
                assertTrue(scale.isFinite())
                assertTrue(scale >= 1f)
                for (offset in listOf(-translation, 0f, translation)) {
                    val expandedHalfExtent = extent.toDouble() * scale / 2.0
                    val tolerance = maxOf(extent, translation) * .000001
                    assertTrue("Left edge exposed: extent=$extent shift=$offset", offset - expandedHalfExtent <= -extent / 2.0 + tolerance)
                    assertTrue("Right edge exposed: extent=$extent shift=$offset", offset + expandedHalfExtent >= extent / 2.0 - tolerance)
                }
            }
        }
    }

    @Test fun uniformOverscanCoversAllFourCornersWithoutDistortingArtwork() {
        for ((width, height) in listOf(.01f to .02f, 1f to 1f, 120f to 180f, 320f to 180f, 1920f to 1080f)) {
            for (distance in listOf(.25f, 4f, 12f, 64f)) {
                val scale = posterArtworkScale(width, height, distance)
                assertTrue(scale.isFinite())
                assertEquals(maxOf(posterArtworkScale(width, distance), posterArtworkScale(height, distance)), scale, 0f)
                val tolerance = maxOf(width, height, distance) * .000001
                for (dx in listOf(-distance, distance)) for (dy in listOf(-distance, distance)) {
                    val halfWidth = width.toDouble() * scale / 2.0
                    val halfHeight = height.toDouble() * scale / 2.0
                    assertTrue(dx - halfWidth <= -width / 2.0 + tolerance)
                    assertTrue(dx + halfWidth >= width / 2.0 - tolerance)
                    assertTrue(dy - halfHeight <= -height / 2.0 + tolerance)
                    assertTrue(dy + halfHeight >= height / 2.0 - tolerance)
                }
            }
        }
    }

    @Test fun uniformOverscanIsSymmetricWhenWidthAndHeightSwap() {
        for ((width, height) in listOf(.01f to 1f, 120f to 180f, 320f to 180f)) {
            assertEquals(posterArtworkScale(width, height, 4f), posterArtworkScale(height, width, 4f), 0f)
        }
    }

    @Test fun zeroTranslationRequiresNoUniformOverscan() {
        for ((width, height) in listOf(.01f to .02f, 120f to 180f, 320f to 180f, 0f to 0f)) {
            assertEquals(1f, posterArtworkScale(width, height, 0f), 0f)
        }
    }

    @Test fun layeredStaticCropRemainsStableAcrossReducedMotionChanges() {
        val animated = PosterMotionPolicy(false, PosterMotionQuality.LAYERED)
        val reduced = PosterMotionPolicy(true, PosterMotionQuality.LAYERED)
        assertEquals(4f, animated.artworkOverscanDp, 0f)
        assertEquals(animated.artworkOverscanDp, reduced.artworkOverscanDp, 0f)
        for ((width, height) in listOf(120f to 180f, 320f to 180f)) {
            assertEquals(posterArtworkScale(width, height, animated.artworkOverscanDp),
                posterArtworkScale(width, height, reduced.artworkOverscanDp), 0f)
        }
        assertEquals(0f, reduced.depth(1f, true), 0f)
    }

    @Test fun focusOnlyNeverRequiresArtworkCrop() {
        for (reduced in listOf(false, true)) {
            val policy = PosterMotionPolicy(reduced, PosterMotionQuality.FOCUS_ONLY)
            assertEquals(0f, policy.artworkOverscanDp, 0f)
            assertEquals(1f, posterArtworkScale(120f, 180f, policy.artworkOverscanDp), 0f)
        }
    }

    @Test fun invalidArtworkInputsReturnIdentity() {
        val invalid = listOf(0f, -1f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
        for (extent in invalid) assertEquals(1f, posterArtworkScale(extent, 4f), 0f)
        for (translation in invalid) assertEquals(1f, posterArtworkScale(180f, translation), 0f)
        assertEquals(1f, posterArtworkScale(Float.NaN, Float.NaN), 0f)
    }

    @Test fun extremeFiniteArtworkInputsNeverProduceNonFiniteScale() {
        for ((extent, translation) in listOf(Float.MIN_VALUE to 4f, 1f to Float.MAX_VALUE, Float.MAX_VALUE to Float.MAX_VALUE)) {
            val scale = posterArtworkScale(extent, translation)
            assertTrue("Nonfinite scale for extent=$extent translation=$translation", scale.isFinite())
            assertTrue(scale >= 1f)
        }
    }
}
