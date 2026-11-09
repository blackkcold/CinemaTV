package dev.cinematv

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Adapted from tools/motion-lab at d1382350ac1d9dc6c976bf8ec9e1e6ad9e90efdd.
 * Window renderer timings, never display-present timestamps or encoder FPS.
 *
 * Pairing runs once in [finish], after every callback has drained. An input is paired only
 * with a frame whose vsync lies inside that input's window **and** at or after the state it
 * caused was applied. A missing state or a missing frame is reported as an outcome, never
 * back-filled with a later frame.
 */
internal class FrameTimingRecorder(private val activity: Activity) {
    private val thread = HandlerThread("ProductionGlass-frame-metrics").apply { start() }
    private val handler = Handler(thread.looper)
    private val frames = mutableListOf<JSONObject>()
    private val windows = JSONArray()
    private val inputs = JSONArray()
    private var callbackDrops = 0
    private var truncated = false
    private var closed = false
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        callbackDrops += dropped // Lost METRIC callbacks, NOT dropped rendered frames.
        if (frames.size >= 20000) truncated = true else frames.add(JSONObject().apply {
            put("vsyncNs", metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
            put("intendedVsyncNs", metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
            put("totalNs", metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            put("drawNs", metrics.getMetric(FrameMetrics.DRAW_DURATION))
            put("layoutNs", metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
            put("syncNs", metrics.getMetric(FrameMetrics.SYNC_DURATION))
            put("commandNs", metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
            put("swapNs", metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
            put("firstDraw", metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L)
            if (Build.VERSION.SDK_INT >= 31) {
                put("gpuNs", metrics.getMetric(FrameMetrics.GPU_DURATION))
                put("deadlineNs", metrics.getMetric(FrameMetrics.DEADLINE))
            }
        })
    }
    init { activity.window.addOnFrameMetricsAvailableListener(listener, handler) }

    /** Registers an input whose receipt the caller already stamped. Safe from the UI thread. */
    fun beginInput(keyCode: Int, route: String, receivedNs: Long): Long = synchronized(inputs) {
        val id = inputs.length() + 1L
        register(JSONObject().put("id", id).put("key", keyCode).put("route", route), receivedNs)
        id
    }

    /** Stamps the state caused by [inputId]; before this call the input is unpaired. */
    fun attachState(inputId: Long, appliedNs: Long, page: String, focusKey: String?) {
        synchronized(inputs) {
            for (index in 0 until inputs.length()) {
                val input = inputs.getJSONObject(index)
                if (input.optLong("id", -1L) != inputId) continue
                input.put("stateAppliedNs", appliedNs).put("statePage", page)
                if (focusKey != null) input.put("stateFocusKey", focusKey)
                return
            }
        }
    }

    /** Benchmark compatibility: the caller supplies a received timestamp and no state gate. */
    fun mark(input: JSONObject, startNs: Long) {
        synchronized(inputs) {
            register(JSONObject(input.toString()), startNs)
            inputs.getJSONObject(inputs.length() - 1).put("stateAppliedNs", startNs)
        }
    }

    private fun register(input: JSONObject, startNs: Long) {
        windows.put(JSONObject().put("startNs", startNs).put("endNs", startNs + ACTIVE_NS))
        inputs.put(input.put("receivedNs", startNs))
    }

    fun cancel() {
        if (closed) return
        closed = true
        activity.window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }

    /** [outputFile] lets a release acceptance test choose an owned path; the benchmark keeps its default. */
    fun finish(runId: String, metadata: JSONObject, outputFile: File? = null) {
        check(!closed)
        closed = true
        activity.window.removeOnFrameMetricsAvailableListener(listener)
        val latch = CountDownLatch(1)
        var writeFailure: Throwable? = null
        handler.post {
            try {
                pairResponses()
                @Suppress("DEPRECATION") val refresh = activity.windowManager.defaultDisplay.refreshRate
                if (!metadata.has("device")) metadata.put("device", Build.MODEL)
                if (!metadata.has("schema")) metadata.put("schema", 3)
                metadata.put("protocolVersion", metadata.optInt("schema", 3)).put("runId", runId)
                    .put("buildType", BuildConfig.BUILD_TYPE)
                    .put("debuggable", 0 != (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE))
                    .put("deviceModel", Build.MODEL).put("hardware", Build.HARDWARE).put("sdk", Build.VERSION.SDK_INT)
                    .put("width", activity.window.decorView.width).put("height", activity.window.decorView.height)
                    .put("densityDpi", activity.resources.displayMetrics.densityDpi)
                    .put("screenWidthDp", activity.resources.configuration.screenWidthDp)
                    .put("screenHeightDp", activity.resources.configuration.screenHeightDp)
                    .put("refreshHz", refresh).put("metricCallbackDrops", callbackDrops).put("truncated", truncated)
                    .put("activeWindowNs", ACTIVE_NS).put("windows", windows)
                    .put("inputs", JSONArray(inputs.toString())).put("frames", JSONArray(frames))
                    .put("scope", "Window FrameMetrics. VSYNC_TIMESTAMP is app render cadence, not display presentation. TOTAL_DURATION inverse is not FPS. Inputs pair only with a frame inside their own 450 ms window and at or after the applied state.")
                val file = outputFile ?: File(activity.getExternalFilesDir(null), "glass-performance/$runId.json")
                file.parentFile?.mkdirs()
                file.writeText(metadata.toString())
            } catch (error: Throwable) { writeFailure = error } finally { latch.countDown() }
        }
        // Every renderer callback drains on the handler thread before this wait returns.
        check(latch.await(10, TimeUnit.SECONDS)) { "Frame metric flush timed out" }
        thread.quitSafely()
        writeFailure?.let { throw it }
    }

    private fun pairResponses() {
        val ordered = frames.sortedBy { it.optLong("vsyncNs") }
        for (index in 0 until inputs.length()) {
            val input = inputs.getJSONObject(index)
            val start = input.optLong("receivedNs", -1L)
            if (start < 0) { input.put("responseOutcome", "no-receipt"); continue }
            val end = start + ACTIVE_NS
            if (!input.has("stateAppliedNs")) { input.put("responseOutcome", "no-state-change"); continue }
            val stateNs = input.getLong("stateAppliedNs")
            val response = ordered.firstOrNull {
                val vsync = it.getLong("vsyncNs")
                vsync in start..end && vsync >= stateNs
            }
            if (response == null) { input.put("responseOutcome", "no-frame-in-window"); continue }
            val vsync = response.getLong("vsyncNs")
            input.put("responseDrawNs", vsync)
                .put("latencyNs", vsync - start)
                .put("responseOutcome", "paired")
        }
    }

    companion object { const val ACTIVE_NS = 450_000_000L }
}
