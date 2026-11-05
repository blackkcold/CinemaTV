package dev.cinematv.ui.tv.focus

import org.junit.Assert.*
import org.junit.Test

class FocusRequestLifetimeTest {
    @Test fun blurCancelsEveryEnteredRequestOnlyOnce() {
        val lifetime = FocusRequestLifetime()
        val calls = mutableListOf<Int>()
        lifetime.updateFocus(true)
        lifetime.register { calls += 1 }
        lifetime.register { calls += 2 }
        lifetime.updateFocus(false)
        lifetime.updateFocus(false)
        assertEquals(listOf(1, 2), calls)
    }

    @Test fun completedRequestIsNotCancelledLater() {
        val lifetime = FocusRequestLifetime()
        var cancelled = false
        lifetime.updateFocus(true)
        val token = lifetime.register { cancelled = true }
        lifetime.unregister(token)
        lifetime.updateFocus(false)
        assertFalse(cancelled)
    }

    @Test fun oneCardsBlurDoesNotCancelNewCardOrHeroRequests() {
        val oldCard = FocusRequestLifetime()
        val nextCard = FocusRequestLifetime()
        val hero = FocusRequestLifetime()
        val cancelled = mutableListOf<String>()
        oldCard.updateFocus(true)
        oldCard.register { cancelled += "old" }
        nextCard.updateFocus(true)
        nextCard.register { cancelled += "next" }
        hero.register { cancelled += "hero" }
        oldCard.updateFocus(false)
        assertEquals(listOf("old"), cancelled)
    }

    @Test fun unfocusedAccessibilityRequestIsNotRejected() {
        val lifetime = FocusRequestLifetime()
        var cancelled = false
        lifetime.register { cancelled = true }
        lifetime.updateFocus(false)
        assertFalse(cancelled)
    }

    @Test fun lateDefaultLaunchAfterBlurExplicitlyRemainsOutsideCancellationBoundary() {
        val lifetime = FocusRequestLifetime()
        var oldCancelled = false
        var lateCancelled = false
        lifetime.updateFocus(true)
        lifetime.register { oldCancelled = true }
        lifetime.updateFocus(false)
        lifetime.register { lateCancelled = true }
        lifetime.updateFocus(false)
        assertTrue(oldCancelled)
        assertFalse("The public API cannot distinguish a late default request from an unfocused explicit request", lateCancelled)
    }

    @Test fun repeatedFocusedEventsDoNotInterruptAnActiveRequest() {
        val lifetime = FocusRequestLifetime()
        var cancelled = false
        lifetime.updateFocus(true)
        lifetime.register { cancelled = true }
        lifetime.updateFocus(true)
        assertFalse(cancelled)
    }

    @Test fun detachOrResetCancelsUnfocusedRequestsAndAllowsReuse() {
        val lifetime = FocusRequestLifetime()
        var cancellations = 0
        lifetime.register { cancellations++ }
        lifetime.reset()
        lifetime.reset()
        assertEquals(1, cancellations)
        lifetime.updateFocus(true)
        lifetime.register { cancellations++ }
        lifetime.updateFocus(false)
        assertEquals(2, cancellations)
    }

    @Test fun synchronousCancellationCanRegisterNewWorkWithoutItBeingCancelled() {
        val lifetime = FocusRequestLifetime()
        var newCancelled = false
        lifetime.updateFocus(true)
        lateinit var token: Any
        token = lifetime.register {
            lifetime.unregister(token)
            lifetime.register { newCancelled = true }
        }
        lifetime.updateFocus(false)
        assertFalse(newCancelled)
        lifetime.reset()
        assertTrue(newCancelled)
    }
}
