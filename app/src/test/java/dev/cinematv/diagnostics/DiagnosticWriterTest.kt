package dev.cinematv.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticWriterTest {

    @Test fun recordMasksSecretsAndEscapesJson() {
        val line = DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "sync",
            "trace-1", mapOf("type" to "cookie=abc123 token: nope", "result" to "a\"b\nc")).render()
        assertTrue(line.contains("cookie=[已隐藏]"))
        assertTrue(line.contains("a\\\"b"))
        assertFalse(line.contains("abc123"))
        assertTrue(line.startsWith("{\"ts\":1000"))
    }

    @Test fun bearerTokenIsMasked() {
        assertEquals("Bearer [已隐藏]", DiagnosticRedaction.value("Bearer abc.def-123"))
    }

    @Test fun endpointUsesLogicalKeys() {
        assertEquals("top250", DiagnosticRedaction.endpoint("/top250?start=25&filter="))
        assertEquals("browse", DiagnosticRedaction.endpoint("/j/new_search_subjects?tags=x&start=0"))
        assertEquals("account.collect", DiagnosticRedaction.endpoint("/people/alice/collect?start=0"))
        assertEquals("other", DiagnosticRedaction.endpoint("/unknown?secret=1"))
    }

    @Test fun writerRotatesAtTheConfiguredFileSize() {
        val dir = File.createTempFile("diag", "").apply { delete(); mkdirs() }
        val writer = DiagnosticWriter(dir, { 1_700_000_000_000L }, maxFileBytes = 220, maxTotalBytes = 10_000)
        repeat(12) { index ->
            writer.append(DiagnosticRecord(1_000L + index, DiagnosticLevel.BASIC, "e$index", null,
                mapOf("result" to "x".repeat(40))))
        }
        writer.flush()
        assertTrue(writer.files().size > 1)
        assertTrue(writer.files().all { it.length() <= 220 })
        assertTrue(writer.stats().bytes > 0)
        writer.close()
    }

    @Test fun retentionDropsOldFilesButKeepsTheCurrentOne() {
        val dir = File.createTempFile("diag", "").apply { delete(); mkdirs() }
        val old = File(dir, "app-20200101.jsonl").apply { writeText("old\n") }
        old.setLastModified(1_000L)
        val writer = DiagnosticWriter(dir, { 1_700_000_000_000L }, maxFileBytes = 10_000,
            maxTotalBytes = 10_000, maxAgeMs = 7L * 24 * 60 * 60_000)
        writer.append(DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "fresh", null, emptyMap()))
        writer.flush()
        assertFalse(old.exists())
        assertEquals(1, writer.files().size)
        writer.close()
    }

    @Test fun clearRemovesEveryLogFile() {
        val dir = File.createTempFile("diag", "").apply { delete(); mkdirs() }
        val writer = DiagnosticWriter(dir, { 1_700_000_000_000L })
        writer.append(DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "event", null, emptyMap()))
        writer.flush()
        assertTrue(writer.files().isNotEmpty())
        writer.clear()
        assertTrue(writer.files().isEmpty())
    }

    @Test fun recordMasksColonAndEqualsFormsWithoutOverMatching() {
        val line = DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "sync", "trace",
            mapOf("type" to "cookie=abc123 token: nope forbid=1")).render()
        assertTrue(line.contains("cookie=[已隐藏]"))
        assertTrue(line.contains("token=[已隐藏]"))
        assertTrue(line.contains("forbid=1"))
        assertFalse(line.contains("abc123"))
        assertFalse(line.contains("nope"))
    }

    @Test fun clearThenAppendReopensAVisibleFile() {
        val dir = File.createTempFile("diag", "").apply { delete(); mkdirs() }
        val writer = DiagnosticWriter(dir, { 1_700_000_000_000L })
        writer.append(DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "before", null, emptyMap()))
        writer.flush()
        writer.clear()
        writer.append(DiagnosticRecord(1_001L, DiagnosticLevel.BASIC, "after", null, emptyMap()))
        writer.flush()
        assertEquals(1, writer.files().size)
        assertTrue(writer.files().single().readText().contains("after"))
        writer.close()
    }

    @Test fun aLineLargerThanTheFileCapStillWritesCompletely() {
        val dir = File.createTempFile("diag", "").apply { delete(); mkdirs() }
        val writer = DiagnosticWriter(dir, { 1_700_000_000_000L }, maxFileBytes = 80)
        writer.append(DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "long", null,
            mapOf("result" to "x".repeat(200))))
        writer.flush()
        val file = writer.files().single()
        assertTrue(file.length() > 80)
        assertTrue(file.readText().trimEnd().endsWith("}"))
        writer.close()
    }

    @Test fun longValuesAreTruncated() {
        val value = DiagnosticRedaction.value("x".repeat(400))
        assertEquals(160, value.length)
        assertTrue(value.endsWith("..."))
    }

    @Test fun unknownAndReservedFieldKeysNeverReachTheFile() {
        val line = DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "sync", null, mapOf(
            "endpoint" to "top250",
            "cookie" to "dbcl2=abc123",
            "title" to "某部片子",
            "ts" to "1",
            "lvl" to "V",
            "ev" to "forged",
            "trace" to "forged"
        )).render()
        assertTrue(line.contains("\"endpoint\":\"top250\""))
        assertFalse(line.contains("cookie"))
        assertFalse(line.contains("某部片子"))
        assertEquals(1, Regex("\"ts\":").findAll(line).count())
        assertEquals(1, Regex("\"ev\":").findAll(line).count())
        assertEquals(1, Regex("\"lvl\":").findAll(line).count())
        assertFalse(line.contains("\"trace\":\"forged\""))
    }

    @Test fun interruptedTailIsTruncatedBeforeAppending() {
        val dir = File.createTempFile("diag", "").apply { delete(); mkdirs() }
        val clock = 1_700_000_000_000L
        val first = DiagnosticWriter(dir, { clock })
        first.append(DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "first", null, emptyMap()))
        first.close()
        // A killed process left a line without its newline; appending to it would merge both.
        first.files().single().appendText("{\"ts\":1700000000000,\"lvl\":\"B")
        val second = DiagnosticWriter(dir, { clock })
        second.append(DiagnosticRecord(2_000L, DiagnosticLevel.BASIC, "second", null, emptyMap()))
        second.close()
        val lines = second.files().single().readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        lines.forEach { org.json.JSONObject(it) }
        assertTrue(lines.last().contains("\"second\""))
    }

    @Test fun failedWriterResumesAfterCooldownWithoutRestart() {
        val path = File.createTempFile("diag", "")
        var clock = 1_700_000_000_000L
        val writer = DiagnosticWriter(path, { clock })
        writer.append(DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "blocked", null, emptyMap()))
        assertTrue(writer.stats().failed)
        writer.append(DiagnosticRecord(1_001L, DiagnosticLevel.BASIC, "still-paused", null, emptyMap()))
        assertTrue(writer.stats().failed)
        assertTrue(path.delete())
        assertTrue(path.mkdirs())
        clock += DiagnosticWriter.RETRY_COOLDOWN_MS + 1
        writer.append(DiagnosticRecord(1_002L, DiagnosticLevel.BASIC, "recovered", null, emptyMap()))
        assertFalse(writer.stats().failed)
        assertTrue(writer.files().single().readText().contains("recovered"))
        writer.close()
    }

    @Test fun explicitRetrySkipsTheCooldown() {
        val path = File.createTempFile("diag", "")
        val writer = DiagnosticWriter(path, { 1_700_000_000_000L })
        writer.append(DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "blocked", null, emptyMap()))
        assertTrue(writer.stats().failed)
        assertTrue(path.delete())
        assertTrue(path.mkdirs())
        writer.retry()
        writer.append(DiagnosticRecord(1_001L, DiagnosticLevel.BASIC, "after-retry", null, emptyMap()))
        assertFalse(writer.stats().failed)
        writer.close()
    }

    @Test fun bogusModificationTimeFallsBackToTheDateInTheName() {
        val dir = File.createTempFile("diag", "").apply { delete(); mkdirs() }
        val clock = 1_700_000_000_000L
        val today = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd", java.util.Locale.US)
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(clock))
        val current = File(dir, "app-$today.jsonl").apply {
            writeText("{\"ts\":1}\n")
            assertTrue("file system refused an epoch timestamp", setLastModified(1L))
        }
        val dated = File(dir, "app-20200101.jsonl").apply {
            writeText("{\"ts\":1}\n")
            assertTrue("file system refused an epoch timestamp", setLastModified(1L))
        }
        val writer = DiagnosticWriter(dir, { clock })
        writer.append(DiagnosticRecord(1_000L, DiagnosticLevel.BASIC, "event", null, emptyMap()))
        writer.close()
        assertTrue("a bogus timestamp must not delete the file opened today", current.exists())
        assertFalse("the date in the name still expires an old file", dated.exists())
    }
}
