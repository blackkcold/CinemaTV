package dev.cinematv.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bounded append-only log. One writer thread owns the file; every line is flushed so a
 * process kill loses at most the line in flight. Rotation and retention are enforced
 * on open and periodically, never by deleting the file currently being written.
 *
 * Failure is recoverable: a transient I/O error pauses writes for [RETRY_COOLDOWN_MS] and
 * then resumes on the next append, and [retry] lets the user force it immediately from the
 * panel. A file left with a half-written tail by a killed process is repaired on open, so
 * the next line never merges into a corrupt one.
 */
class DiagnosticWriter(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private val lock = Any()
    private var stream: BufferedWriter? = null
    private var current: File? = null
    private var appends = 0
    private var retryAt = 0L
    @Volatile private var failed = false

    fun append(record: DiagnosticRecord) {
        synchronized(lock) {
            if (failed && now() < retryAt) return
            try {
                directory.mkdirs()
                if (!directory.isDirectory) { markFailed(); return }
                val line = record.render()
                val target = ensureFile(line.toByteArray(Charsets.UTF_8).size + 1)
                val writer = stream ?: open(target)
                writer.write(line)
                writer.newLine()
                writer.flush()
                failed = false
                appends++
                if (appends % 32 == 0) enforceRetention(target)
            } catch (_: Exception) {
                markFailed()
            }
        }
    }

    fun flush() {
        synchronized(lock) { runCatching { stream?.flush() } }
    }

    fun close() {
        synchronized(lock) {
            runCatching { stream?.flush(); stream?.close() }
            stream = null
        }
    }

    /** Clears a paused state so the next append tries the directory again immediately. */
    fun retry() {
        synchronized(lock) { failed = false; retryAt = 0 }
    }

    fun stats(): Stats {
        val files = files()
        return Stats(
            files = files.size,
            bytes = files.sumOf { it.length() },
            lastWriteAt = files.maxOfOrNull { it.lastModified() } ?: 0L,
            failed = failed
        )
    }

    fun files(): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
        ?.sortedWith(compareBy({ effectiveTime(it) }, { it.name }))
        .orEmpty()

    fun clear() {
        close()
        files().forEach { runCatching { it.delete() } }
        failed = false
        retryAt = 0
    }

    private fun markFailed() {
        failed = true
        retryAt = now() + RETRY_COOLDOWN_MS
        runCatching { stream?.close() }
        stream = null
    }

    private fun open(target: File): BufferedWriter {
        truncatePartialTail(target)
        return BufferedWriter(OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8))
            .also { stream = it; current = target }
    }

    /**
     * A process killed mid-write can leave a line without its trailing newline. Appending to
     * it would produce one unparseable JSONL line, so the incomplete tail is dropped instead.
     */
    private fun truncatePartialTail(file: File) {
        if (!file.isFile || file.length() == 0L) return
        runCatching {
            RandomAccessFile(file, "rw").use { handle ->
                val length = handle.length()
                if (length == 0L) return@use
                handle.seek(length - 1)
                if (handle.read() == NEWLINE) return@use
                val buffer = ByteArray(8 * 1024)
                var keep = 0L
                var end = length
                search@ while (end > 0) {
                    val start = (end - buffer.size).coerceAtLeast(0L)
                    val size = (end - start).toInt()
                    handle.seek(start)
                    handle.readFully(buffer, 0, size)
                    for (index in size - 1 downTo 0) {
                        if (buffer[index] == NEWLINE.toByte()) { keep = start + index + 1; break@search }
                    }
                    end = start
                }
                if (keep < length) handle.setLength(keep)
            }
        }
    }

    private fun ensureFile(nextLineBytes: Int): File {
        val open = stream
        val existing = current
        if (open != null && existing != null && existing.length() + nextLineBytes <= maxFileBytes) return existing
        open?.let { runCatching { it.flush(); it.close() } }
        stream = null
        val base = "app-" + DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(zone)
            .format(Instant.ofEpochMilli(now()))
        var index = 1
        var candidate = File(directory, "$base$SUFFIX")
        while (candidate.exists() && candidate.length() + nextLineBytes > maxFileBytes) {
            index++
            candidate = File(directory, "$base-$index$SUFFIX")
        }
        current = candidate
        enforceRetention(candidate)
        return candidate
    }

    private fun enforceRetention(keep: File) {
        val files = files()
        val cutoff = now() - maxAgeMs
        files.filter { it != keep && expired(it, cutoff) }
            .forEach { runCatching { it.delete() } }
        var remaining = files.filter { it.exists() }
        var total = remaining.sumOf { it.length() }
        remaining.sortedBy { effectiveTime(it) }.forEach { file ->
            if (total <= maxTotalBytes) return@forEach
            if (file == keep) return@forEach
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= length
        }
    }

    /**
     * Age must survive a restored or clock-less file system. A modification time before
     * [SANE_TIME_FLOOR] is not evidence that the file is old — the date in its own name is.
     */
    private fun expired(file: File, cutoff: Long): Boolean = effectiveTime(file) in 1 until cutoff

    private fun effectiveTime(file: File): Long {
        val modified = file.lastModified()
        if (modified >= SANE_TIME_FLOOR) return modified
        return filenameTime(file) ?: modified
    }

    /** `app-YYYYMMDD[-n].jsonl` carries the day it was opened; anything else has no fallback. */
    private fun filenameTime(file: File): Long? = runCatching {
        val name = file.name
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return@runCatching null
        val day = name.removePrefix(PREFIX).removeSuffix(SUFFIX).substringBefore('-')
        LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE)
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()

    data class Stats(
        val files: Int,
        val bytes: Long,
        val lastWriteAt: Long,
        val failed: Boolean,
        /** Records the queue could not accept, counted process-wide by [DiagnosticLog]. */
        val dropped: Long = 0L
    )

    companion object {
        const val PREFIX = "app-"
        const val SUFFIX = ".jsonl"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
        const val MAX_AGE_MS = 7L * 24 * 60 * 60_000
        /** 2000-01-01T00:00Z; below this a modification time is treated as unknown. */
        const val SANE_TIME_FLOOR = 946_684_800_000L
        const val RETRY_COOLDOWN_MS = 10_000L
        private const val NEWLINE = '\n'.code
    }
}
