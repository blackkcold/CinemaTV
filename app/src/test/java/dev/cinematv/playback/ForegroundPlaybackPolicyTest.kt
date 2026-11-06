package dev.cinematv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPlaybackPolicyTest {
    @Test fun explicitPlaybackWaitsForForegroundAndStartsOnce() {
        val policy = ForegroundPlaybackPolicy()
        assertTrue(policy.onStart())
        assertFalse(policy.onStart())
    }

    @Test fun onlyActivePlaybackResumesAfterReturning() {
        val policy = ForegroundPlaybackPolicy()
        policy.onStart()
        policy.onStop(playRequested = true, suppressed = false, ended = false)
        assertTrue(policy.onStart())
        policy.onStop(playRequested = false, suppressed = false, ended = false)
        assertFalse(policy.onStart())
    }

    @Test fun transientAudioFocusLossCannotBecomeForegroundAutoplay() {
        val policy = ForegroundPlaybackPolicy()
        policy.onStart()
        policy.onStop(playRequested = true, suppressed = true, ended = false)
        assertFalse(policy.onStart())
    }

    @Test fun controllerPauseOrHeadphoneDisconnectCancelsPendingResume() {
        val policy = ForegroundPlaybackPolicy()
        policy.onStart()
        policy.onStop(playRequested = true, suppressed = false, ended = false)
        policy.cancelResume()
        assertFalse(policy.onStart())
    }

    @Test fun offScreenPlayIsRejectedAndCannotQueueAResume() {
        val policy = ForegroundPlaybackPolicy()
        policy.onStart()
        policy.onStop(playRequested = true, suppressed = false, ended = false)
        assertFalse(policy.requestPlay())
        assertFalse(policy.onStart())
        assertTrue(policy.requestPlay())
    }

    @Test fun duplicateStopDoesNotLoseAnIntendedResume() {
        val policy = ForegroundPlaybackPolicy()
        policy.onStart()
        policy.onStop(playRequested = true, suppressed = false, ended = false)
        policy.onStop(playRequested = false, suppressed = false, ended = false)
        assertTrue(policy.onStart())
    }

    @Test fun endedOrFailedPlaybackDoesNotResume() {
        val policy = ForegroundPlaybackPolicy()
        policy.onStart()
        policy.onStop(playRequested = true, suppressed = false, ended = true)
        assertFalse(policy.onStart())
    }

    @Test fun releaseRejectsLateLifecycleAndMediaCommands() {
        val policy = ForegroundPlaybackPolicy()
        policy.onStart()
        policy.onStop(playRequested = true, suppressed = false, ended = false)
        policy.release()
        assertFalse(policy.onStart())
        assertFalse(policy.requestPlay())
    }

    @Test fun pausedRecoveryCanOptOutOfAutoplay() {
        val policy = ForegroundPlaybackPolicy(autoPlay = false)
        assertFalse(policy.onStart())
        assertTrue(policy.requestPlay())
    }
}
