package com.kyant.backdrop

import org.junit.Assert.*
import org.junit.Test

class BackdropFailureStateTest {
    @Test fun succeedsWithoutLatching() {
        val state = BackdropFailureState()
        assertEquals(7, state.attempt("test") { 7 })
        assertFalse(state.failed)
        assertNull(state.reason)
    }

    @Test fun catchesRuntimeFailureOnceAcrossSharedRenderers() {
        val state = BackdropFailureState()
        var calls = 0
        assertNull(state.attempt<Unit>("material.effects") {
            calls++
            throw IllegalArgumentException("must not appear in diagnostics")
        })
        assertNull(state.attempt("highlight.draw") { calls++; "never" })
        assertTrue(state.failed)
        assertEquals(1, calls)
        assertEquals("material.effects: IllegalArgumentException", state.reason)
        assertFalse(state.reason!!.contains("must not appear"))
    }

    @Test fun preservesFirstFailureAndRequiresANewSessionToRetry() {
        val state = BackdropFailureState()
        val first = IllegalStateException("first")
        state.fail("source.record", first)
        state.fail("shadow.draw", IllegalArgumentException("second"))
        assertSame(first, state.failure!!.cause)
        assertEquals("source.record", state.failure!!.stage)
        assertNull(state.attempt("new-mode") { "must not retry" })
        assertEquals("new session", BackdropFailureState().attempt("test") { "new session" })
    }

    @Test fun doesNotClaimToCatchVmOrNativeFailures() {
        val state = BackdropFailureState()
        val error = LinkageError("not a recoverable renderer RuntimeException")
        try {
            state.attempt<Unit>("test") { throw error }
            fail("Errors must propagate")
        } catch (actual: LinkageError) {
            assertSame(error, actual)
        }
        assertFalse(state.failed)
    }
}
