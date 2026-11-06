package dev.cinematv

import dev.cinematv.ui.shared.RemoteBackPress
import org.junit.Assert.*
import org.junit.Test

class RemoteBackPressTest {
    @Test fun aNewRouteCannotHandleThePreviousRoutesRelease() {
        val player = RemoteBackPress()
        player.down(100, 0)
        assertTrue(player.up(100, false))
        val restoredBrowser = RemoteBackPress()
        assertFalse(restoredBrowser.up(100, false))
        restoredBrowser.down(200, 0)
        assertTrue(restoredBrowser.up(200, false))
        assertFalse(restoredBrowser.up(200, false))
    }

    @Test fun repeatCancellationAndFocusLossCannotInventANewPress() {
        val owner = RemoteBackPress()
        owner.down(100, 1)
        assertFalse(owner.up(100, false))
        owner.down(200, 0)
        owner.down(200, 1)
        owner.down(200, 2)
        assertTrue(owner.up(200, false))
        owner.down(300, 0)
        assertFalse(owner.up(300, true))
        owner.down(400, 0)
        owner.reset()
        assertFalse(owner.up(400, false))
        owner.down(500, 0)
        assertFalse(owner.up(499, false))
        assertFalse(owner.up(500, false))
    }
}
