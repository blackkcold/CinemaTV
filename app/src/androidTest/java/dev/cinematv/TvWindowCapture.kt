package dev.cinematv

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Test-only compositor capture. No Compose forceRedraw/PixelCopy readback or scaled raster. */
internal object TvWindowCapture {
    private const val TIMEOUT_SECONDS = 10L

    fun capture(activity: Activity, rootWidth: Int, rootHeight: Int): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun checkForeground(waitForRoot: Boolean = false) {
            val deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)
            var lastPackage: String? = null
            do {
                instrumentation.runOnMainSync {
                    check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
                        "Capture requires the live, focused test activity"
                    }
                    val decor = activity.window.decorView
                    check(decor.isAttachedToWindow && decor.isShown && decor.isHardwareAccelerated)
                    check(decor.width == rootWidth && decor.height == rootHeight) {
                        "Compose root must cover the complete activity window"
                    }
                    val location = IntArray(2)
                    decor.getLocationOnScreen(location)
                    check(location.contentEquals(intArrayOf(0, 0))) {
                        "Unexpected window offset ${location.contentToString()}; " +
                            "imeVisible=${ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime())}; " +
                            "softInputMode=${activity.window.attributes.softInputMode}"
                    }
                }
                val activeRoot = automation.rootInActiveWindow
                try {
                    lastPackage = activeRoot?.packageName?.toString()
                    if (activeRoot != null && activeRoot.packageName?.toString() == activity.packageName) return
                } finally {
                    @Suppress("DEPRECATION")
                    activeRoot?.recycle()
                }
                if (!waitForRoot || SystemClock.uptimeMillis() >= deadline) break
                SystemClock.sleep(100)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Expected active accessibility window ${activity.packageName}; " +
                "last package=${lastPackage ?: "<missing>"}; initialWait=$waitForRoot")
        }
        // UiAutomation connection can finish before its initial active-root
        // snapshot arrives. Only initial readiness may wait; both checks around
        // the actual capture below still reject missing or wrong-package roots.
        checkForeground(waitForRoot = true)
        // Fence an actual UI frame, not wall time: a delayed/coalesced frame's
        // intended vsync can predate System.nanoTime() even when it draws after
        // the request. FrameCallback and VSYNC_TIMESTAMP share the actual frame
        // time. A draw traversal alone never proves renderer completion.
        val thread = HandlerThread("CinemaTV-capture-frame").apply { start() }
        val drawn = CountDownLatch(1)
        val requestedFrame = AtomicLong(-1)
        val lastVsync = AtomicLong(-1)
        val lastIntendedVsync = AtomicLong(-1)
        val callbacks = AtomicInteger()
        val droppedCallbacks = AtomicInteger()
        val drawPasses = AtomicInteger()
        val drawRequests = AtomicInteger()
        lateinit var drawHost: View
        var choreographer: Choreographer? = null
        var observer: ViewTreeObserver? = null
        var registered = false
        val requestFrame = Choreographer.FrameCallback { frameTimeNanos ->
            // Preserve the first fence on retry, so a delayed valid completion
            // remains eligible instead of moving the goalpost ahead of the GPU.
            requestedFrame.compareAndSet(-1, frameTimeNanos)
            drawRequests.incrementAndGet()
            // Animation callbacks run before traversal. Invalidate directly here
            // rather than posting another invalidation that can be coalesced.
            drawHost.invalidate()
        }
        val drawListener = ViewTreeObserver.OnDrawListener {
            if (requestedFrame.get() > 0) drawPasses.incrementAndGet()
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            callbacks.incrementAndGet()
            droppedCallbacks.addAndGet(dropped)
            val actualVsync = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
            lastVsync.set(actualVsync)
            lastIntendedVsync.set(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
            val fence = requestedFrame.get()
            // Ignore unarmed and stale queued callbacks, even if they arrive
            // after registration. Require both traversal and renderer evidence.
            if (fence > 0 && drawPasses.get() > 0 && actualVsync >= fence) drawn.countDown()
        }
        fun frameState() = "requestedFrame=${requestedFrame.get()}, lastVsync=${lastVsync.get()}, " +
            "lastIntendedVsync=${lastIntendedVsync.get()}, callbacks=${callbacks.get()}, " +
            "droppedCallbacks=${droppedCallbacks.get()}, drawPasses=${drawPasses.get()}, requests=${drawRequests.get()}"
        val frameDeadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)
        try {
            instrumentation.runOnMainSync {
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                registered = true
                // setContent installs ComposeView under android.R.id.content;
                // its Android host owns the Compose draw commands. Invalidating
                // only DecorView after idle may reuse that child's display list.
                val content = checkNotNull(activity.findViewById<ViewGroup>(android.R.id.content))
                check(content.childCount == 1) { "Expected one full-screen Compose content view" }
                val composeView = content.getChildAt(0) as? ComposeView
                    ?: error("Expected ComposeView in the TV test activity")
                check(composeView.childCount == 1) { "Expected one Android Compose draw host" }
                drawHost = composeView.getChildAt(0)
                check(drawHost.isAttachedToWindow && drawHost.isShown && drawHost.isHardwareAccelerated)
                observer = drawHost.viewTreeObserver.also { it.addOnDrawListener(drawListener) }
                choreographer = Choreographer.getInstance().also { it.postFrameCallback(requestFrame) }
            }
            // One bounded re-invalidation if the first request was consumed or
            // its metrics were dropped. Both waits share the original 10 s budget.
            val firstWait = minOf(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS) / 2,
                (frameDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            if (!drawn.await(firstWait, TimeUnit.MILLISECONDS) && SystemClock.uptimeMillis() < frameDeadline) {
                android.util.Log.i("CinemaTVQa", "capture-frame-retry ${frameState()}")
                checkNotNull(choreographer).postFrameCallback(requestFrame)
            }
            val remaining = (frameDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            check(drawn.await(remaining, TimeUnit.MILLISECONDS)) {
                "No rendered frame before capture deadline: ${frameState()}"
            }
            android.util.Log.i("CinemaTVQa", "capture-frame-ready ${frameState()}")
        } finally {
            try {
                instrumentation.runOnMainSync {
                    choreographer?.removeFrameCallback(requestFrame)
                    observer?.takeIf { it.isAlive }?.removeOnDrawListener(drawListener)
                    if (registered) activity.window.removeOnFrameMetricsAvailableListener(listener)
                }
            } finally {
                thread.quitSafely()
                thread.join(2000)
            }
        }
        checkForeground()
        // takeScreenshot returns actual display pixels, including video surfaces.
        // Bound the binder call and recycle even a late result after a timeout.
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CinemaTV-compositor-capture").apply { isDaemon = true }
        }
        val lock = Any()
        var abandoned = false
        var captured: Bitmap? = null
        var transferred = false
        val future = executor.submit<Bitmap> {
            val bitmap = checkNotNull(automation.takeScreenshot()) { "Compositor returned no screenshot" }
            synchronized(lock) {
                if (abandoned) {
                    bitmap.recycle()
                    error("Compositor capture completed after deadline")
                }
                captured = bitmap
            }
            bitmap
        }
        try {
            val bitmap = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            checkForeground()
            check(bitmap.width == rootWidth && bitmap.height == rootHeight) {
                "Display ${bitmap.width}x${bitmap.height} differs from root ${rootWidth}x${rootHeight}; refusing scaling/cropping"
            }
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
