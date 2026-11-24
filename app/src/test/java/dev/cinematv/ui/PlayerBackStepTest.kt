package dev.cinematv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The player's Back ladder is order-sensitive: panel, then controls, then the route. */
class PlayerBackStepTest {
    @Test fun anOpenPanelClosesBeforeAnythingElse() {
        assertEquals(
            PlayerBackStep.CLOSE_PANEL,
            playerBackStep(panelOpen = true, controlsVisible = true, hasError = false),
        )
    }

    @Test fun visibleControlsHideBeforeTheRouteChanges() {
        assertEquals(
            PlayerBackStep.HIDE_CONTROLS,
            playerBackStep(panelOpen = false, controlsVisible = true, hasError = false),
        )
        assertEquals(
            PlayerBackStep.EXIT,
            playerBackStep(panelOpen = false, controlsVisible = false, hasError = false),
        )
    }

    @Test fun anErrorLeavesTheRouteButNeverSkipsAnOpenPanel() {
        assertEquals(
            PlayerBackStep.EXIT,
            playerBackStep(panelOpen = false, controlsVisible = true, hasError = true),
        )
        // The panel check stays first: an error arriving while the panel is open must not
        // turn the next Back into a route exit that strands the panel state.
        assertEquals(
            PlayerBackStep.CLOSE_PANEL,
            playerBackStep(panelOpen = true, controlsVisible = true, hasError = true),
        )
    }
}
