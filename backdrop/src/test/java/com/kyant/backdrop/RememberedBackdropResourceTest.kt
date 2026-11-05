package com.kyant.backdrop

import org.junit.Assert.*
import org.junit.Test

class RememberedBackdropResourceTest {
    @Test fun forgottenResourceIsReleasedExactlyOnce() {
        val expected = Any()
        var releases = 0
        val resource = RememberedBackdropResource(BackdropFailureState(), { expected }) {
            assertSame(expected, it)
            releases++
        }
        resource.onRemembered()
        assertSame(expected, resource.value)
        resource.onForgotten()
        resource.onForgotten()
        resource.onAbandoned()
        assertNull(resource.value)
        assertEquals(1, releases)
    }

    @Test fun abandonedCompositionReleasesBeforeEverBeingRemembered() {
        var releases = 0
        val resource = RememberedBackdropResource(BackdropFailureState(), { Any() }) { releases++ }
        resource.onAbandoned()
        resource.onForgotten()
        assertNull(resource.value)
        assertEquals(1, releases)
    }

    @Test fun creationFailureLatchesAndNeverReleasesOrRetries() {
        val failure = BackdropFailureState()
        var creates = 0
        var releases = 0
        fun allocate() = RememberedBackdropResource<Any>(failure, {
            creates++
            throw IllegalStateException("private implementation detail")
        }, { releases++ })
        val resource = allocate()
        assertNull(resource.value)
        resource.onAbandoned()
        allocate().onForgotten()
        assertEquals(1, creates)
        assertEquals(0, releases)
        assertEquals("source.create: IllegalStateException", failure.reason)
    }

    @Test fun cleanupStillRunsAfterAnotherRendererFailed() {
        val failure = BackdropFailureState()
        var releases = 0
        val resource = RememberedBackdropResource(failure, { Any() }) { releases++ }
        failure.fail("highlight.draw", IllegalArgumentException())
        resource.onForgotten()
        assertEquals(1, releases)
        assertEquals("highlight.draw", failure.failure!!.stage)
    }

    @Test fun releaseFailureIsLatchedWithoutASecondReleaseAttempt() {
        val failure = BackdropFailureState()
        var releases = 0
        val resource = RememberedBackdropResource(failure, { Any() }) {
            releases++
            throw IllegalArgumentException("private implementation detail")
        }
        resource.onForgotten()
        resource.onAbandoned()
        assertEquals(1, releases)
        assertNull(resource.value)
        assertEquals("source.release: IllegalArgumentException", failure.reason)
    }
}
