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

class RailFocusTraversalTest {
    private val keys = (0..23).map { "movie-$it" }
    private class Gate {
        var continuation: Continuation<Unit>? = null
        suspend fun await() = suspendCoroutine<Unit> { check(continuation == null); continuation = it }
        fun advance() { checkNotNull(continuation).also { continuation = null }.resume(Unit) }
    }

    @Test fun attachedTargetTransfersBeforeAnyFrame() = runBlocking {
        var frames = 0
        var requests = 0
        assertTrue(awaitRailFocusTarget({ true }, { requests++; true }, { frames++ }))
        assertEquals(1, requests)
        assertEquals(0, frames)
    }

    @Test fun rapidRightRightLeftKeepsOnlyLatestLogicalKey() {
        val traversal = RailFocusTraversal(keys[0])
        val first = traversal.move(keys, keys[0], 1)!!
        val second = traversal.move(keys, keys[0], 1)!!
        val reverse = traversal.move(keys, keys[0], -1)!!
        assertEquals(keys[1], reverse.targetKey)
        assertFalse(traversal.isCurrent(first, keys))
        assertFalse(traversal.isCurrent(second, keys))
        assertTrue(traversal.isCurrent(reverse, keys))
        traversal.finish(first, keys[0])
        assertEquals(keys[2], traversal.move(keys, keys[0], 1)!!.targetKey)
    }

    @Test fun reverseBackToActualFocusCancelsTheUnattachedDestination() {
        val traversal = RailFocusTraversal(keys[3])
        val pending = traversal.move(keys, keys[3], 1)!!
        val reverse = traversal.move(keys, keys[3], -1)!!
        assertEquals(keys[3], reverse.targetKey)
        assertFalse(traversal.isCurrent(pending, keys))
        traversal.finish(reverse, keys[3])
        assertEquals(keys[4], traversal.move(keys, keys[3], 1)!!.targetKey)
    }

    @Test fun reorderedDataInvalidatesPendingIndexAndResolvesFromActualKey() {
        val traversal = RailFocusTraversal(keys[2])
        val old = traversal.move(keys, keys[2], 1)!!
        val reordered = keys.reversed()
        assertFalse(traversal.isCurrent(old, reordered))
        assertEquals(keys[1], traversal.move(reordered, keys[2], 1)!!.targetKey)
        assertFalse(traversal.isCurrent(old, keys))
    }

    @Test fun removalAndExplicitInterruptionInvalidatePendingMoves() {
        val traversal = RailFocusTraversal(keys[0])
        val old = traversal.move(keys, keys[0], 1)!!
        assertFalse(traversal.isCurrent(old, keys.filterNot { it == old.targetKey }))
        traversal.cancel(keys[0])
        assertFalse(traversal.isCurrent(old, keys))
        assertEquals(keys[1], traversal.move(keys, keys[0], 1)!!.targetKey)
    }

    @Test fun boundaryRepeatsDoNotReplaceAnAlreadyPendingTarget() {
        val traversal = RailFocusTraversal(keys[22])
        val last = traversal.move(keys, keys[22], 1)!!
        assertNull(traversal.move(keys, keys[22], 1))
        assertTrue(traversal.isCurrent(last, keys))
        assertEquals(keys[21], last.anchorKey)
    }

    @Test fun lazyAttachmentRequestsAsSoonAsAvailableWithoutTwoExtraFrames() {
        val gate = Gate()
        var attached = false
        var requests = 0
        var result: Boolean? = null
        val operation: suspend () -> Boolean = {
            awaitRailFocusTarget({ true }, { requests++; attached }, gate::await)
        }
        operation.startCoroutine(object : Continuation<Boolean> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<Boolean>) { result = value.getOrThrow() }
        })
        assertNull(result)
        attached = true
        gate.advance()
        assertEquals(true, result)
        assertEquals(2, requests)
        assertNull(gate.continuation)
    }

    @Test fun routeNavigationAndContentInterruptionsAllStopBeforeAnotherRequest() {
        repeat(3) { interruption ->
            val gate = Gate()
            val owner = TvNavigationFocusOwner()
            val bookmark = FocusCoordinator.Bookmark("rail", keys[0])
            val guard = FocusRestorationGuard("HOME", bookmark, owner, false)
            var route = "HOME"
            var requests = 0
            var result: Boolean? = null
            val operation: suspend () -> Boolean = {
                awaitRailFocusTarget({ guard.isCurrent(route, bookmark, owner.hasFocus) },
                    { requests++; false }, gate::await)
            }
            operation.startCoroutine(object : Continuation<Boolean> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<Boolean>) { result = value.getOrThrow() }
            })
            when (interruption) {
                0 -> route = "MOVIES"
                1 -> owner.update(true)
                else -> owner.acquireContentFocus()
            }
            gate.advance()
            assertEquals(false, result)
            assertEquals(1, requests)
        }
    }

    @Test fun cancellationDuringAttachmentWaitCannotRequestFocusAgain() = runBlocking {
        val gate = Gate()
        var requests = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitRailFocusTarget({ true }, { requests++; false }, gate::await)
        }
        job.cancel()
        gate.advance()
        job.join()
        assertEquals(1, requests)
        assertTrue(job.isCancelled)
    }

    @Test fun ownFocusCallbackIsMarkedOnlyDuringActualRequest() {
        val traversal = RailFocusTraversal(keys[0])
        assertFalse(traversal.requestingFocus)
        assertTrue(traversal.requestFocus { assertTrue(traversal.requestingFocus); true })
        assertFalse(traversal.requestingFocus)
        runCatching { traversal.requestFocus { error("Detached") } }
        assertFalse(traversal.requestingFocus)
    }
}
