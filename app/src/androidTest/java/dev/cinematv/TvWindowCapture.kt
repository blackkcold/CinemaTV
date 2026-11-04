package dev.cinematv

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        // API 24+ frame metrics are delivered after this window renders a frame.
        // Unlike Compose captureToImage, this does not perform a GPU readback to
        // establish draw readiness. The overall suite retains its 12-minute cap.
        val thread = HandlerThread("CinemaTV-capture-frame").apply { start() }
        val drawn = CountDownLatch(1)
        val requestedAt = System.nanoTime()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= requestedAt) drawn.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                // setContent installs ComposeView under android.R.id.content;
                // its Android host owns the Compose draw commands. Invalidating
                // only DecorView after idle may reuse that child's display list.
                val content = checkNotNull(activity.findViewById<ViewGroup>(android.R.id.content))
                check(content.childCount == 1) { "Expected one full-screen Compose content view" }
                val composeView = content.getChildAt(0) as? ComposeView
                    ?: error("Expected ComposeView in the TV test activity")
                check(composeView.childCount == 1) { "Expected one Android Compose draw host" }
                val drawHost = composeView.getChildAt(0)
                check(drawHost.isAttachedToWindow && drawHost.isShown && drawHost.isHardwareAccelerated)
                drawHost.postInvalidateOnAnimation()
            }
            check(drawn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "No rendered frame before capture deadline" }
        } finally {
            instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
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
