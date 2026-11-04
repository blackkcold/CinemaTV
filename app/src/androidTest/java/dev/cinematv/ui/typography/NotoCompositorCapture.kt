package dev.cinematv.ui.typography

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.PixelCopy
import android.view.WindowManager
import android.view.View
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.NotoTestActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
                check(activity !is NotoTestActivity || !activity.hasStopped) {
                    "Noto specimen session must use one uninterrupted Activity launch"
                }
                check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
                    "Refusing font evidence capture of a FLAG_SECURE window"
                }
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

    /**
     * API26 emulator compositor rejects landscape/physical-display size mismatch.
     * Copy only our non-secure Activity's small specimen union, not the display.
     * Android 8.0 PixelCopy.request performs the copy before posting its callback,
     * so both request and callback wait live off-main inside the bounded worker.
     */
    fun captureWindowRegionApi26(activity: Activity, host: View, expected: Location, windowBounds: Rect): Bitmap {
        check(Build.VERSION.SDK_INT == 26) { "Window-region compatibility capture is API26-only" }
        foreground(activity)
        check(location(host) == expected) { "Compose host moved before API26 capture" }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(!activity.window.isFloating) { "API26 specimen capture requires a non-floating test window" }
            val decor = activity.window.decorView
            val position = IntArray(2)
            decor.getLocationInWindow(position)
            check(Rect(position[0], position[1], position[0] + decor.width,
                position[1] + decor.height).contains(windowBounds)) { "Specimen union is outside the Activity window" }
        }
        check(windowBounds.width() > 0 && windowBounds.height() > 0 &&
            windowBounds.width().toLong() * windowBounds.height() <= 2_000_000L) {
            "API26 specimen union exceeds bounded readback size"
        }
        val executor = Executors.newSingleThreadExecutor { work ->
            Thread(work, "Noto-api26-window-copy").apply { isDaemon = true }
        }
        val lock = Any()
        var abandoned = false
        var captured: Bitmap? = null
        var transferred = false
        val future = executor.submit<Bitmap> {
            val bitmap = Bitmap.createBitmap(windowBounds.width(), windowBounds.height(), Bitmap.Config.ARGB_8888)
            val callbackThread = HandlerThread("Noto-api26-copy-result").apply { isDaemon = true; start() }
            var published = false
            try {
                val done = CountDownLatch(1)
                val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
                PixelCopy.request(activity.window, Rect(windowBounds), bitmap, { status ->
                    result.set(status)
                    done.countDown()
                }, Handler(callbackThread.looper))
                check(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "API26 PixelCopy callback deadline exceeded" }
                check(result.get() == PixelCopy.SUCCESS) { "API26 PixelCopy failed with status ${result.get()}" }
                synchronized(lock) {
                    check(!abandoned) { "API26 PixelCopy completed after capture deadline" }
                    captured = bitmap
                    published = true
                }
                bitmap
            } finally {
                // Never recycle while a native copy is still writing: this block only
                // runs after request returns (including a late, abandoned result).
                if (!published) bitmap.recycle()
                callbackThread.quitSafely()
            }
        }
        try {
            val bitmap = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            foreground(activity)
            check(location(host) == expected) { "Compose host moved during API26 capture" }
            check(bitmap.width == windowBounds.width() && bitmap.height == windowBounds.height())
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
