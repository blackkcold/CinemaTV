package dev.cinematv.ui.typography

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded display compositor readback for a non-fullscreen Compose test activity. */
internal object NotoCompositorCapture {
    private const val TIMEOUT_SECONDS = 10L

    data class Location(val screenX: Int, val screenY: Int, val windowX: Int, val windowY: Int) {
        val windowOriginX get() = screenX - windowX
        val windowOriginY get() = screenY - windowY
    }

    fun location(view: View): Location {
        var result: Location? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(view.isAttachedToWindow && view.isShown && view.isHardwareAccelerated && view.width > 0 && view.height > 0)
            val screen = IntArray(2)
            val window = IntArray(2)
            view.getLocationOnScreen(screen)
            view.getLocationInWindow(window)
            result = Location(screen[0], screen[1], window[0], window[1])
        }
        return checkNotNull(result)
    }

    private fun foreground(activity: Activity, waitForRoot: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_SECONDS * 1000
        var windowReady = false
        var lastPackage: String? = null
        do {
            instrumentation.runOnMainSync {
                val decor = activity.window.decorView
                windowReady = !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() &&
                    decor.isAttachedToWindow && decor.isShown && decor.isHardwareAccelerated
            }
            // Initial setup may still be attaching/focusing the generic test activity.
            // Once capture starts, any loss of readiness is an immediate failure.
            if (!waitForRoot) check(windowReady) { "Noto capture requires a live focused hardware window" }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            try {
                lastPackage = root?.packageName?.toString()
                if (windowReady && lastPackage == activity.packageName) return
                if (!waitForRoot) error("Noto capture foreground package mismatch: $lastPackage")
            } finally {
                @Suppress("DEPRECATION")
                root?.recycle()
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Noto foreground did not become ready: windowReady=$windowReady package=$lastPackage")
    }

    /** Arm before changing the draw-observed state; do not force Compose redraw/readback. */
    fun settleFreshFrame(activity: Activity, rendered: () -> Boolean, requestFrame: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        foreground(activity, waitForRoot = true)
        val thread = HandlerThread("Noto-evidence-frame").apply { start() }
        val latch = CountDownLatch(1)
        val requestedAt = System.nanoTime()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (rendered() && metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= requestedAt) {
                latch.countDown()
            }
        }
        try {
            instrumentation.runOnMainSync {
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                requestFrame()
            }
            check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "No fresh Noto specimen frame before deadline" }
        } finally {
            instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
    }

    /** Ownership transfers to caller on success; abandoned/late native bitmaps are recycled. */
    fun capture(activity: Activity, host: View, expected: Location): Bitmap {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        foreground(activity)
        check(location(host) == expected) { "Compose host moved before capture" }
        val executor = Executors.newSingleThreadExecutor { work ->
            Thread(work, "Noto-display-capture").apply { isDaemon = true }
        }
        val lock = Any()
        var abandoned = false
        var captured: Bitmap? = null
        var transferred = false
        val future = executor.submit<Bitmap> {
            val bitmap = checkNotNull(automation.takeScreenshot()) { "No compositor screenshot" }
            synchronized(lock) {
                if (abandoned) {
                    bitmap.recycle()
                    error("Noto compositor result arrived after deadline")
                }
                captured = bitmap
            }
            bitmap
        }
        try {
            val bitmap = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            foreground(activity)
            check(location(host) == expected) { "Compose host moved during capture" }
            check(bitmap.width > 0 && bitmap.height > 0)
            transferred = true
            return bitmap
        } finally {
            synchronized(lock) {
                abandoned = true
                if (!transferred) captured?.recycle()
            }
            future.cancel(true)
            executor.shutdownNow()
        }
    }
}
