package dev.cinematv

import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Passive and bounded: observes existing work; never advances a clock, focus, scroll or snapshot. */
internal class HybridNativeDiagnostics(
    private val instrumentation: Instrumentation,
    private val activity: MainActivity,
    private val model: CinemaViewModel,
    private val output: File,
    private val label: String,
) : AutoCloseable {
    private data class StateRecord(
        val identity: String,
        var writes: Int = 0,
        var applies: Int = 0,
        var firstAppWriteStack: List<String> = emptyList(),
        var firstFrameworkWriteStack: List<String> = emptyList(),
    )

    private val started = SystemClock.uptimeMillis()
    private val closed = AtomicBoolean()
    private val globalWrites = AtomicLong()
    private val applyNotifications = AtomicLong()
    private val observedStates = AtomicLong()
    private val draws = AtomicLong()
    private val records = LinkedHashMap<String, StateRecord>()
    private val samples = mutableListOf<JSONObject>()
    private val handler = Handler(Looper.getMainLooper())
    private var tree: ViewTreeObserver? = null
    private val onDraw = ViewTreeObserver.OnDrawListener { draws.incrementAndGet(); Unit }
    private val writeObserver = Snapshot.registerGlobalWriteObserver { value ->
        globalWrites.incrementAndGet()
        record(value, write = true)
    }
    private val applyObserver = Snapshot.registerApplyObserver { changed, _ ->
        applyNotifications.incrementAndGet()
        changed.take(128).forEach { record(it, write = false) }
    }
    private val sampler = object : Runnable {
        override fun run() {
            if (closed.get()) return
            sample("periodic")
            if (samples.size < 64) handler.postDelayed(this, 500)
        }
    }

    init {
        onMain {
            tree = activity.window.decorView.viewTreeObserver.also { it.addOnDrawListener(onDraw) }
            sample("before-key")
            handler.postDelayed(sampler, 500)
        }
    }

    private fun record(value: Any, write: Boolean) {
        if (closed.get() || observedStates.incrementAndGet() > 20_000) return
        val identity = "${value.javaClass.name}@${System.identityHashCode(value)}"
        synchronized(records) {
            val record = records[identity] ?: if (records.size < 128) {
                StateRecord(identity).also { records[identity] = it }
            } else return
            if (write) {
                record.writes++
                if (record.firstAppWriteStack.isEmpty()) {
                    val stack = Throwable().stackTrace
                    record.firstAppWriteStack = stack.asSequence()
                        .filter { it.className.startsWith("dev.cinematv.") && !it.className.startsWith(javaClass.name) }
                        .take(8).map { it.toString() }.toList()
                    if (record.firstAppWriteStack.isEmpty()) {
                        record.firstAppWriteStack = listOf("<no app frame at global write>")
                        record.firstFrameworkWriteStack = stack.asSequence()
                            .filter { it.className.startsWith("androidx.compose.") || it.className.startsWith("coil3.") }
                            .take(16).map { it.toString() }.toList()
                    }
                }
            } else record.applies++
        }
    }

    private fun sample(stage: String) {
        val recomposers = JSONArray()
        Recomposer.runningRecomposers.value.forEach { info ->
            recomposers.put(JSONObject().put("id", System.identityHashCode(info))
                .put("pending", info.hasPendingWork).put("changes", info.changeCount)
                .put("state", (info.state as? StateFlow<*>)?.value?.toString() ?: "flow"))
        }
        val bookmark = model.focusCoordinator.last(model.focusRoute)
        samples += JSONObject().put("stage", stage).put("elapsedMs", SystemClock.uptimeMillis() - started)
            .put("draws", draws.get()).put("globalWrites", globalWrites.get())
            .put("applyNotifications", applyNotifications.get()).put("recomposers", recomposers)
            .put("route", model.page.name).put("reducedMotion", model.reducedMotion)
            .put("windowFocus", activity.hasWindowFocus())
            .put("bookmarkIdentity", bookmark?.let(System::identityHashCode))
            .put("bookmarkRegion", bookmark?.region)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else instrumentation.runOnMainSync(action)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        writeObserver.dispose()
        applyObserver.dispose()
        onMain {
            handler.removeCallbacks(sampler)
            tree?.takeIf { it.isAlive }?.removeOnDrawListener(onDraw)
            sample("after-assertion")
        }
        val states = JSONArray()
        synchronized(records) {
            records.values.sortedByDescending { it.writes + it.applies }.forEach {
                states.put(JSONObject().put("identity", it.identity).put("writes", it.writes).put("applies", it.applies)
                    .put("firstAppWriteStack", JSONArray(it.firstAppWriteStack))
                    .put("firstFrameworkWriteStack", JSONArray(it.firstFrameworkWriteStack)))
            }
        }
        File(output, "$label-native-idle.json").writeText(JSONObject()
            .put("passiveDiagnostic", true).put("stateLimit", 128).put("observationLimit", 20_000)
            .put("observedStates", observedStates.get()).put("states", states).put("samples", JSONArray(samples)).toString(2))
    }
}
