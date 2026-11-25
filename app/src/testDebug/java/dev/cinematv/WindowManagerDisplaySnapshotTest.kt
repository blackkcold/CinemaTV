package dev.cinematv

import org.junit.Assert.*
import org.junit.Test

class WindowManagerDisplaySnapshotTest {
    private fun display(id: Int = 0, width: Int = 2400, height: Int = 1080, rotation: Int = 1,
                        pending: String = "", animation: String = "no ScreenRotationAnimation") = """
        Display: mDisplayId=$id rootTasks=3
          init=1080x1920 420dpi base=1080x2400 420dpi cur=${width}x$height app=2274x1080 rng=1080x1017-2274x2211
          deferred=false mLayoutNeeded=false
          $pending
          $animation
          DisplayFrames w=$width h=$height r=$rotation
    """.trimIndent()

    @Test fun acceptsSettledDefaultDisplayWithExplicitGeometry() {
        val state = WindowManagerDisplaySnapshot.parse(display(), 0)!!
        assertTrue(state.matchesWindow(0, 1, 2400, 1080))
        assertFalse(state.matchesWindow(1, 1, 2400, 1080))
        assertFalse(state.matchesWindow(0, 0, 2400, 1080))
        assertFalse(state.matchesWindow(0, 1, 1920, 1080))
    }

    @Test fun rejectsObservedPhone33FixedRotationWindow() {
        // Reduced from artifact13491006089: app reports landscape while WMS still has portrait frames.
        val observed = display(width = 1080, height = 2400, rotation = 0,
            pending = "mFixedRotationLaunchingApp=ActivityRecord{8010b50 u0 dev.cinematv/.TvTestActivity} t16}")
            .replace("mLayoutNeeded=false", "mLayoutNeeded=true")
        assertFalse(WindowManagerDisplaySnapshot.parse(observed, 0)!!.matchesWindow(0, 1, 2400, 1080))
    }

    @Test fun matchingLandscapeStillWaitsForFixedRotationAndAnimationToFinish() {
        assertFalse(WindowManagerDisplaySnapshot.parse(display(pending = "mFixedRotationLaunchingApp=ActivityRecord{test}"), 0)!!
            .matchesWindow(0, 1, 2400, 1080))
        assertFalse(WindowManagerDisplaySnapshot.parse(display(animation = "mScreenRotationAnimation:"), 0)!!
            .matchesWindow(0, 1, 2400, 1080))
        assertFalse(WindowManagerDisplaySnapshot.parse(display().replace("deferred=false", "deferred=true"), 0)!!
            .matchesWindow(0, 1, 2400, 1080))
    }

    @Test fun scopesGeometryAndTransitionsToRequestedDisplay() {
        val dump = display(id = 9, pending = "mFixedRotationLaunchingApp=ActivityRecord{other}") + "\n" +
            display() + "\n" + display(id = 12, width = 800, height = 1200, rotation = 0)
        assertTrue(WindowManagerDisplaySnapshot.parse(dump, 0)!!.matchesWindow(0, 1, 2400, 1080))
        assertNull(WindowManagerDisplaySnapshot.parse(dump, 2))
        assertNull(WindowManagerDisplaySnapshot.parse(display() + "\n" + display(), 0))
    }

    @Test fun missingUnknownAndInconsistentCriticalFieldsFailClosed() {
        listOf(
            display().replace("DisplayFrames", "UnknownFrames"),
            display().replace("r=1", "r=4"),
            display().replace("cur=2400x1080", "cur=1080x2400"),
            display().replace("deferred=false mLayoutNeeded=false", "unrecognized layout state"),
            display().replace("no ScreenRotationAnimation", "rotation state unavailable"),
            display().replace("no ScreenRotationAnimation", "no ScreenRotationAnimation\n mScreenRotationAnimation:"),
            display(pending = "mFixedRotationLaunchingApp="),
            display(pending = "mFixedRotationLaunchingApp: unknown format"),
            display() + "\nDUMP TIMEOUT",
        ).forEach { assertNull(it, WindowManagerDisplaySnapshot.parse(it, 0)) }
    }

    @Test fun requiresFreshStableSamplesAfterACommittedFrame() {
        val ready = WindowManagerDisplaySnapshot.parse(display(), 0)!!
        val pending = ready.copy(fixedRotationPending = true)
        val gate = WindowManagerDisplayBarrier(0, 1)
        fun sample(state: WindowManagerDisplaySnapshot?) = gate.observe(state, 2400, 1080, true)
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, sample(ready))
        assertEquals(WindowManagerDisplayBarrier.Step.COMMIT_FRAME, sample(ready))
        gate.committedFrame()
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, sample(ready))
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, sample(pending))
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, sample(ready))
        assertEquals(WindowManagerDisplayBarrier.Step.COMMIT_FRAME, sample(ready))
        gate.committedFrame()
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, sample(ready))
        assertEquals(WindowManagerDisplayBarrier.Step.READY, sample(ready))
    }

    @Test fun malformedSampleOrLostWindowFocusResetsTheBarrier() {
        val ready = WindowManagerDisplaySnapshot.parse(display(), 0)!!
        val gate = WindowManagerDisplayBarrier(0, 1)
        gate.observe(ready, 2400, 1080, true)
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, gate.observe(null, 2400, 1080, true))
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, gate.observe(ready, 2400, 1080, true))
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, gate.observe(ready, 2400, 1080, false))
        assertEquals(WindowManagerDisplayBarrier.Step.WAIT, gate.observe(ready, 2400, 1080, true))
    }
}
