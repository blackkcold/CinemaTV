package dev.cinematv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A personal local build compiles an endpoint default; a public build compiles an empty one.
 * The device's own setting must always win, or a user could never move away from the default.
 */
class LibreTvServerDefaultTest {

    @Test fun explicitDeviceSettingAlwaysWins() {
        assertEquals(
            "http://192.168.0.14:8899",
            resolveServer("http://192.168.0.14:8899", "http://default.example:1")
        )
    }

    @Test fun blankOrMissingSettingFallsBackToTheBuildDefault() {
        assertEquals("http://default.example:1", resolveServer(null, "http://default.example:1"))
        assertEquals("http://default.example:1", resolveServer("", "http://default.example:1"))
        assertEquals("http://default.example:1", resolveServer("   ", "http://default.example:1"))
    }

    @Test fun publicBuildWithoutAnySettingStaysDisconnected() {
        assertEquals("", resolveServer(null, ""))
        assertEquals("", resolveServer("", ""))
    }

    @Test fun storedValueIsTrimmedBeforeUse() {
        assertEquals("http://lan:8899", resolveServer("  http://lan:8899  ", "http://default.example:1"))
    }
}
