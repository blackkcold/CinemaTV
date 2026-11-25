package dev.cinematv.diagnostics

import android.content.Context
import dev.cinematv.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide diagnostic log. Off unless the dev channel has it explicitly enabled.
 * Enqueueing never touches the disk; one writer coroutine owns the files.
 *
 * The queue is bounded and lossy by design: under a burst it rejects the newest record
 * instead of blocking a caller, and [stats] reports how many were rejected so a partial
 * trace can never be mistaken for a complete one.
 */
object DiagnosticLog {
    private const val QUEUE_CAPACITY = 256
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = DiagnosticQueue<Command>(QUEUE_CAPACITY)
    private val traceCounter = java.util.concurrent.atomic.AtomicInteger()
    /** Serializes install and the pre-ready setting handshake; never held during disk I/O. */
    private val stateLock = Any()
    private sealed interface Command {
        class Record(val record: DiagnosticRecord) : Command
        object Flush : Command
        class Clear(val done: CompletableDeferred<Unit>) : Command
    }
    @Volatile private var writer: DiagnosticWriter? = null
    @Volatile private var consumer: Job? = null
    @Volatile private var settings: DiagnosticSettings? = null
    @Volatile private var installed = false
    @Volatile private var ready = false
    private var pendingEnabled: Boolean? = null
    private var pendingLevel: DiagnosticLevel? = null

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var level: DiagnosticLevel = DiagnosticLevel.OFF
        private set

    fun install(context: Context) {
        synchronized(stateLock) {
            if (installed) return
            installed = true
            val app = context.applicationContext
            writer = DiagnosticWriter(File(app.filesDir, "diagnostics"))
            consumer = scope.launch {
                val loaded = DiagnosticSettings.load(app)
                settings = loaded
                val dev = BuildConfig.UPDATE_CHANNEL == "dev"
                val pending = synchronized(stateLock) {
                    // A toggle made before the persisted settings finished loading is applied
                    // once, here, instead of being lost between the check and the assignment.
                    val pendingOn = pendingEnabled
                    val pendingLvl = pendingLevel
                    enabled = dev && (pendingOn ?: loaded.enabled)
                    level = if (dev) (pendingLvl ?: loaded.level) else DiagnosticLevel.OFF
                    ready = true
                    pendingEnabled = null
                    pendingLevel = null
                    pendingOn to pendingLvl
                }
                if (pending.first != null && pending.first != loaded.enabled) settings?.setEnabled(enabled)
                if (pending.second != null && pending.second != loaded.level) settings?.setLevel(level)
                if (enabled) event("app.start", fields = mapOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "channel" to BuildConfig.UPDATE_CHANNEL,
                    "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
                    "model" to android.os.Build.MODEL
                ))
                while (true) {
                    when (val command = queue.receive()) {
                        is Command.Record -> writer?.append(command.record)
                        Command.Flush -> writer?.flush()
                        is Command.Clear -> {
                            // Always release the caller, even if a future writer command throws.
                            try { writer?.clear() } finally { command.done.complete(Unit) }
                        }
                    }
                }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        val next: Boolean
        synchronized(stateLock) {
            if (!installed) return
            if (!ready) { pendingEnabled = value; return }
            val candidate = BuildConfig.UPDATE_CHANNEL == "dev" && value
            if (candidate == enabled) {
                // Re-enabling an already-enabled log is the user's "try again" gesture.
                if (candidate) writer?.retry()
                return
            }
            if (!candidate) event("diagnostics.disabled")
            enabled = candidate
            next = candidate
        }
        scope.launch(Dispatchers.IO) { settings?.setEnabled(next) }
        if (next) {
            writer?.retry()
            event("diagnostics.enabled", fields = mapOf("level" to DiagnosticRedaction.levelName(level)))
        } else {
            flush()
        }
    }

    fun setLevel(value: DiagnosticLevel) {
        val next: DiagnosticLevel
        synchronized(stateLock) {
            if (!installed) return
            if (!ready) { pendingLevel = value; return }
            next = if (BuildConfig.UPDATE_CHANNEL == "dev") value else DiagnosticLevel.OFF
            if (next == level) return
            level = next
        }
        scope.launch(Dispatchers.IO) { settings?.setLevel(next) }
        event("diagnostics.level", fields = mapOf("level" to DiagnosticRedaction.levelName(next)))
    }

    fun event(name: String, trace: String? = null, fields: Map<String, String> = emptyMap()) {
        if (!recordable(enabled, level, name)) return
        // Never block a caller on disk: one bounded queue, the newest record is the one lost.
        queue.offer(Command.Record(
            DiagnosticRecord(System.currentTimeMillis(), level, name, trace, fields)
        ))
    }

    fun verbose(name: String, trace: String? = null, fields: Map<String, String> = emptyMap()) {
        if (!enabled || level != DiagnosticLevel.VERBOSE) return
        event(name, trace, fields)
    }

    /** Short, non-identifying correlation id for one user action. */
    fun newTrace(): String = String.format(
        java.util.Locale.US, "%08x", traceCounter.incrementAndGet()
    )

    fun flush() {
        // Non-blocking: the writer owns the file, and every line is already flushed.
        if (installed) queue.offer(Command.Flush)
    }

    suspend fun stats(): DiagnosticWriter.Stats = withContext(Dispatchers.IO) {
        (writer?.stats() ?: DiagnosticWriter.Stats(0, 0L, 0L, false)).copy(dropped = queue.rejected())
    }

    suspend fun clear() {
        if (!installed) return
        val done = CompletableDeferred<Unit>()
        queue.send(Command.Clear(done))
        done.await()
    }

    fun export(context: Context): File? = DiagnosticExport.create(context, writer?.files().orEmpty())

    fun shareIntent(context: Context, file: File) = DiagnosticExport.shareIntent(context, file)
}

/** A blank name or the OFF level must never reach the queue, even if a caller asks for it. */
internal fun recordable(enabled: Boolean, level: DiagnosticLevel, name: String): Boolean =
    enabled && level != DiagnosticLevel.OFF && name.isNotBlank()

/**
 * Bounded, non-blocking hand-off for writer commands. A caller is never suspended on disk;
 * when the queue is full the new command is rejected and counted, so [DiagnosticLog.stats]
 * can say the trace is partial instead of silently implying completeness.
 */
internal class DiagnosticQueue<T>(capacity: Int) {
    private val channel = Channel<T>(capacity)
    private val rejected = AtomicLong()

    fun offer(value: T): Boolean =
        if (channel.trySend(value).isSuccess) true else { rejected.incrementAndGet(); false }

    suspend fun send(value: T) = channel.send(value)

    suspend fun receive(): T = channel.receive()

    fun rejected(): Long = rejected.get()
}
