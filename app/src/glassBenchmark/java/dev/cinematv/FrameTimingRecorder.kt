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
 * Recording starts after preload and warmup. Analysis includes only native-key windows.
 */
internal class FrameTimingRecorder(private val activity: Activity) {
    private val thread = HandlerThread("ProductionGlass-frame-metrics").apply { start() }
    private val handler = Handler(thread.looper)
    private val frames = mutableListOf<JSONObject>()
    private val windows = JSONArray()
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
    fun mark(input: JSONObject, startNs: Long) {
        windows.put(JSONObject(input.toString()).put("startNs", startNs).put("endNs", startNs + ACTIVE_NS))
    }
    fun cancel() {
        if (closed) return
        closed = true
        activity.window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }
    fun finish(runId: String, metadata: JSONObject) {
        check(!closed)
        closed = true
        activity.window.removeOnFrameMetricsAvailableListener(listener)
        val latch = CountDownLatch(1)
        var writeFailure: Throwable? = null
        handler.post {
            try {
                @Suppress("DEPRECATION") val refresh = activity.windowManager.defaultDisplay.refreshRate
                metadata.put("schema", 2).put("runId", runId)
                    .put("buildType", BuildConfig.BUILD_TYPE)
                    .put("debuggable", 0 != (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE))
                    .put("deviceModel", Build.MODEL).put("hardware", Build.HARDWARE).put("sdk", Build.VERSION.SDK_INT)
                    .put("width", activity.window.decorView.width).put("height", activity.window.decorView.height)
                    .put("densityDpi", activity.resources.displayMetrics.densityDpi)
                    .put("screenWidthDp", activity.resources.configuration.screenWidthDp)
                    .put("screenHeightDp", activity.resources.configuration.screenHeightDp)
                    .put("refreshHz", refresh).put("metricCallbackDrops", callbackDrops).put("truncated", truncated)
                    .put("activeWindowNs", ACTIVE_NS).put("windows", windows).put("frames", JSONArray(frames))
                    .put("scope", "Window FrameMetrics. VSYNC_TIMESTAMP is app render cadence, not actual display presentation. TOTAL_DURATION inverse is not FPS. Native-key activity windows exclude launch, warmup and idle gaps.")
                val file = File(activity.getExternalFilesDir(null), "glass-performance/$runId.json")
                file.parentFile!!.mkdirs()
                file.writeText(metadata.toString())
            } catch (error: Throwable) { writeFailure = error } finally { latch.countDown() }
        }
        // All measured windows and renderer callbacks drain before this main-thread wait.
        check(latch.await(10, TimeUnit.SECONDS)) { "Frame metric flush timed out" }
        thread.quitSafely()
        writeFailure?.let { throw it }
    }
    companion object { const val ACTIVE_NS = 450_000_000L }
}
