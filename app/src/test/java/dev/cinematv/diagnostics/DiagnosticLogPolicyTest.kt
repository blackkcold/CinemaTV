package dev.cinematv.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy at the queue boundary. These are the branches that decide whether a record exists
 * at all; the file format itself is covered by DiagnosticWriterTest.
 */
class DiagnosticLogPolicyTest {

    @Test fun offLevelOrBlankNameNeverRecords() {
        assertFalse(recordable(enabled = true, level = DiagnosticLevel.OFF, name = "event"))
        assertFalse(recordable(enabled = true, level = DiagnosticLevel.BASIC, name = " "))
        assertFalse(recordable(enabled = false, level = DiagnosticLevel.VERBOSE, name = "event"))
        assertTrue(recordable(enabled = true, level = DiagnosticLevel.BASIC, name = "event"))
        assertTrue(recordable(enabled = true, level = DiagnosticLevel.VERBOSE, name = "event"))
    }

    @Test fun fullQueueRejectsAndCountsInsteadOfBlocking() {
        val queue = DiagnosticQueue<Int>(capacity = 2)
        assertTrue(queue.offer(1))
        assertTrue(queue.offer(2))
        assertFalse(queue.offer(3))
        assertEquals(1L, queue.rejected())
        assertFalse(queue.offer(4))
        assertEquals(2L, queue.rejected())
        assertEquals(1, runBlocking { queue.receive() })
        // Capacity is free again; a later record is accepted rather than dropped forever.
        assertTrue(queue.offer(5))
    }

    @Test fun acceptedCommandsKeepFifoOrder() = runBlocking {
        val queue = DiagnosticQueue<Int>(capacity = 4)
        listOf(1, 2, 3, 4).forEach { assertTrue(queue.offer(it)) }
        assertEquals(listOf(1, 2, 3, 4), listOf(queue.receive(), queue.receive(), queue.receive(), queue.receive()))
    }
}
