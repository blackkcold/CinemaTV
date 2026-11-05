package dev.cinematv.ui.tv.focus

import dev.cinematv.FocusCoordinator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

class FocusRestorationTest {
    private class Gate {
        private var continuation: Continuation<Unit>? = null
        suspend fun await() = suspendCoroutine<Unit> { check(continuation == null); continuation = it }
        fun advance() { checkNotNull(continuation).also { continuation = null }.resume(Unit) }
    }

    private class Harness(saved: Boolean = true) {
        val memory = FocusCoordinator().apply { if (saved) remember("HOME", "rail", "movie-9") }
        val owner = TvNavigationFocusOwner()
        var route = "HOME"
        val bookmark = memory.last(route)
        val guard = FocusRestorationGuard(route, bookmark, owner, owner.hasFocus)
        val frames = Gate()
        val lazy = Gate()
        var prepare: (suspend (String) -> String?)? = null
        var requestSucceeds = true
        var fallbackSucceeds = true
        val requests = mutableListOf<Pair<String, String>>()
        var fallbacks = 0
        var result: FocusRestoreResult? = null
        val current = { guard.isCurrent(route, memory.last("HOME"), owner.hasFocus) }
        fun start() {
            val operation: suspend () -> FocusRestoreResult = {
                restorePageFocusWhenCurrent(frames::await, current, restore = {
                    restoreFocusTarget(bookmark, prepare, frames::await, current) { region, key ->
                        requests += region to key
                        requestSucceeds
                    }
                }, fallback = { fallbacks++; fallbackSucceeds })
            }
            operation.startCoroutine(object : Continuation<FocusRestoreResult> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<FocusRestoreResult>) { result = value.getOrThrow() }
            })
        }
        fun assertAborted() {
            assertEquals(FocusRestoreResult.Aborted, result)
            assertTrue(requests.isEmpty())
            assertEquals(0, fallbacks)
        }
    }

    @Test fun upDuringEitherEntryFramePreservesNavigationWithoutFallback() {
        repeat(2) { interruptedFrame ->
            val h = Harness().apply { start() }
            repeat(interruptedFrame) { h.frames.advance() }
            h.owner.update(true)
            h.frames.advance()
            h.assertAborted()
        }
    }

    @Test fun navigationRoundTripBetweenFramesStillInvalidatesOldRestoration() {
        val h = Harness().apply { start() }
        h.owner.update(true)
        h.owner.update(false)
        h.frames.advance()
        h.assertAborted()
    }

    @Test fun newContentTargetEvenSameLogicalKeyCancelsPendingRestore() {
        for (key in listOf("movie-15", "movie-9")) {
            val h = Harness().apply { start() }
            h.memory.remember("HOME", "rail", key)
            h.frames.advance()
            h.assertAborted()
        }
    }

    @Test fun changedRouteCancelsBeforeRequestOrFallback() {
        val h = Harness().apply { start() }
        h.route = "DETAIL:another-title"
        h.frames.advance()
        h.assertAborted()
    }

    @Test fun upDuringLazyPreparationCannotRestoreOrFallBackEvenWhenItemDisappears() {
        for (preparedKey in listOf("movie-9", null)) {
            val h = Harness()
            h.prepare = { h.lazy.await(); preparedKey }
            h.start()
            repeat(2) { h.frames.advance() }
            h.owner.update(true)
            h.lazy.advance()
            h.assertAborted()
        }
    }

    @Test fun interruptionDuringEitherPostScrollFrameCancelsFocus() {
        repeat(2) { interruptedFrame ->
            val h = Harness()
            h.prepare = { "movie-9" }
            h.start()
            repeat(2 + interruptedFrame) { h.frames.advance() }
            h.owner.update(true)
            h.frames.advance()
            h.assertAborted()
        }
    }

    @Test fun changingRouteOrContentWhileLazyPrepareSuspendsCancelsFocus() {
        repeat(2) { change ->
            val h = Harness()
            h.prepare = { h.lazy.await(); it }
            h.start()
            repeat(2) { h.frames.advance() }
            if (change == 0) h.route = "MOVIES" else h.memory.remember("HOME", "hero", "primary")
            h.lazy.advance()
            h.assertAborted()
        }
    }

    @Test fun uninterruptedOffscreenItemRestoresItsResolvedExactKey() {
        val h = Harness()
        h.prepare = { h.lazy.await(); "movie-9-reordered" }
        h.start()
        repeat(2) { h.frames.advance() }
        h.lazy.advance()
        repeat(2) { h.frames.advance() }
        assertEquals(FocusRestoreResult.Restored, h.result)
        assertEquals(listOf("rail" to "movie-9-reordered"), h.requests)
        assertEquals(0, h.fallbacks)
    }

    @Test fun genuinelyMissingBookmarkUsesFallbackOnce() {
        val h = Harness(saved = false).apply { start() }
        repeat(2) { h.frames.advance() }
        assertEquals(FocusRestoreResult.Fallback, h.result)
        assertEquals(1, h.fallbacks)
        assertTrue(h.requests.isEmpty())
    }

    @Test fun failedTargetRequestRechecksOwnershipBeforeFallback() {
        val owner = TvNavigationFocusOwner()
        var result: FocusRestoreResult? = null
        var fallbacks = 0
        val operation: suspend () -> FocusRestoreResult = {
            restorePageFocusWhenCurrent(awaitFrame = {}, isCurrent = { !owner.hasFocus }, restore = {
                owner.update(true)
                FocusRestoreResult.Missing
            }, fallback = { fallbacks++; true })
        }
        operation.startCoroutine(object : Continuation<FocusRestoreResult> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<FocusRestoreResult>) { result = value.getOrThrow() }
        })
        assertEquals(FocusRestoreResult.Aborted, result)
        assertEquals(0, fallbacks)
    }

    @Test fun enteringFromNavigationDoesNotAutofocusContentAfterDown() {
        val owner = TvNavigationFocusOwner().apply { update(true) }
        val guard = FocusRestorationGuard("HOME", null, owner, owner.hasFocus)
        owner.update(false)
        assertFalse(guard.isCurrent("HOME", null, owner.hasFocus))
    }

    @Test fun hiddenNavigationDoesNotBlockDetailAndDiagnosticsIgnoreOlderCompletion() {
        var visible = true
        val owner = TvNavigationFocusOwner { visible }.apply { update(true) }
        visible = false
        assertFalse(owner.hasFocus)
        val guard = FocusRestorationGuard("DETAIL:title", null, owner, owner.hasFocus)
        owner.update(false)
        assertTrue(guard.isCurrent("DETAIL:title", null, owner.hasFocus))
        val older = owner.beginRestore("HOME")
        val latest = owner.beginRestore("DETAIL:title")
        owner.finishRestore(older, "Cancelled")
        assertEquals("Pending", owner.snapshot().restoreState)
        owner.finishRestore(latest, "Restored")
        val snapshot = owner.snapshot()
        assertFalse(snapshot.navigationHasFocus)
        assertEquals(1L, snapshot.navigationAcquisition)
        assertEquals("DETAIL:title", snapshot.restoreRoute)
        assertEquals("Restored", snapshot.restoreState)
        assertEquals(latest, snapshot.restoreGeneration)
        assertNotNull(snapshot.restoreStartedAtNanos)
        assertNull(snapshot.restoreRequestedAtNanos)
        assertTrue(snapshot.restoreFinishedAtNanos!! >= snapshot.restoreStartedAtNanos!!)
    }

    @Test fun coroutineCancellationDuringUncooperativePrepareCannotFallBack() = runBlocking {
        val gate = Gate()
        var requests = 0
        var fallbacks = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            restorePageFocusWhenCurrent(awaitFrame = {}, isCurrent = { true }, restore = {
                restoreFocusTarget(FocusCoordinator.Bookmark("rail", "saved"),
                    prepare = { gate.await(); null }, awaitFrame = {}, isCurrent = { true },
                    request = { _, _ -> requests++; true })
            }, fallback = { fallbacks++; true })
        }
        job.cancel()
        gate.advance()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, requests)
        assertEquals(0, fallbacks)
    }
}
