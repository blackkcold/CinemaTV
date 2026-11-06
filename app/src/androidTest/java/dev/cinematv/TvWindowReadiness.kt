package dev.cinematv

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A loaded CI emulator can need more than ten seconds to publish a settled display record, and
 * can leave WMS in layoutPending while the host window is already landscape. The deadline is
 * explicit and the same user rotation is re-issued periodically to nudge WindowManager; the
 * settled-state requirement itself is unchanged.
 */
private const val SETTLE_DEADLINE_MS = 20_000L
private const val SETTLE_KICK_INTERVAL_MS = 3_000L

/** API30 introduced fixed-rotation launching; API26/29 retain their existing preparation. */
@androidx.annotation.RequiresApi(30)
internal fun waitForSettledTvWindow(activity: ComponentActivity) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val deadline = SystemClock.uptimeMillis() + SETTLE_DEADLINE_MS
    var nextKickAt = SystemClock.uptimeMillis() + SETTLE_KICK_INTERVAL_MS
    fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
    fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        activity.runOnUiThread(task)
        return try { task.get(remaining(), TimeUnit.MILLISECONDS) }
        catch (failure: Throwable) { task.cancel(false); throw failure }
    }
    val rotation = Settings.System.getInt(activity.contentResolver, Settings.System.USER_ROTATION)
    check(rotation in 0..3) { "Unknown test rotation policy: $rotation" }
    val barrier = WindowManagerDisplayBarrier(Display.DEFAULT_DISPLAY, rotation)
    val reader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tv-test-window-dump").apply { isDaemon = true }
    }
    val descriptor = AtomicReference<ParcelFileDescriptor?>()
    val closed = AtomicBoolean(false)
    var lastDump = "No WindowManager sample captured"
    var lastState = "No host sample captured"
    try {
        while (remaining() > 0) {
            val dump = reader.submit<String> {
                val fd = automation.executeShellCommand("dumpsys -t 1 window -a displays")
                descriptor.set(fd)
                try {
                    check(!closed.get()) { "WMS read completed after its deadline" }
                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 512 * 1024) { "WMS display dump exceeded 512 KiB" }
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                } finally {
                    descriptor.compareAndSet(fd, null)
                    runCatching { fd.close() }
                }
            }
            lastDump = try {
                dump.get(minOf(1_500, remaining()), TimeUnit.MILLISECONDS)
            } catch (failure: Throwable) {
                closed.set(true)
                dump.cancel(true)
                runCatching { descriptor.getAndSet(null)?.close() }
                throw failure
            }
            val snapshot = WindowManagerDisplaySnapshot.parse(lastDump, Display.DEFAULT_DISPLAY)
            val step = main {
                val decor = activity.window.decorView
                val ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    decor.isAttachedToWindow && decor.hasWindowFocus() && decor.isShown &&
                    decor.display?.displayId == Display.DEFAULT_DISPLAY &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                lastState = "host=${decor.width}x${decor.height} ready=$ready targetRotation=$rotation WMS=$snapshot"
                barrier.observe(snapshot, decor.width, decor.height, ready)
            }
            when (step) {
                WindowManagerDisplayBarrier.Step.READY -> {
                    check(remaining() > 0) { "TV display settled after the $SETTLE_DEADLINE_MS ms deadline" }
                    return
                }
                WindowManagerDisplayBarrier.Step.COMMIT_FRAME -> {
                    val committed = CountDownLatch(1)
                    val callback = Runnable { committed.countDown() }
                    val cleanup = main {
                        val decor = activity.window.decorView
                        if (decor.isHardwareAccelerated) {
                            val observer = decor.viewTreeObserver
                            observer.registerFrameCommitCallback(callback)
                            decor.postInvalidateOnAnimation()
                            ({ if (observer.isAlive) observer.unregisterFrameCommitCallback(callback); Unit })
                        } else {
                            decor.postOnAnimation { decor.postOnAnimation(callback) }
                            ({ decor.removeCallbacks(callback); Unit })
                        }
                    }
                    try {
                        check(committed.await(remaining(), TimeUnit.MILLISECONDS)) { "TV host frame did not commit" }
                    } finally {
                        // Cleanup remains queued even if the deadline has already expired.
                        activity.runOnUiThread { cleanup() }
                    }
                    barrier.committedFrame()
                }
                WindowManagerDisplayBarrier.Step.WAIT -> Unit
            }
            if (SystemClock.uptimeMillis() >= nextKickAt) {
                runCatching { automation.setRotation(rotation) }
                nextKickAt = SystemClock.uptimeMillis() + SETTLE_KICK_INTERVAL_MS
            }
            SystemClock.sleep(minOf(50, remaining()))
        }
        throw AssertionError("TV display did not settle before the $SETTLE_DEADLINE_MS ms deadline: $lastState")
    } catch (failure: Throwable) {
        // Keep the sampled state, before teardown closes the window or restores rotation.
        runCatching {
            val folder = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: activity.getExternalFilesDir(null)!!.absolutePath, "ui-evidence").apply { mkdirs() }
            val label = "douban-tv-window-${SystemClock.uptimeMillis()}"
            File(folder, "$label-windows.txt").writeText(lastDump)
            File(folder, "$label-state.txt").writeText(lastState)
            val capture = reader.submit {
                automation.takeScreenshot()?.let { bitmap ->
                    try { File(folder, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            }
            try { capture.get(1, TimeUnit.SECONDS) } finally { capture.cancel(true) }
        }
        throw AssertionError("TV WMS readiness failed within $SETTLE_DEADLINE_MS ms: $lastState", failure)
    } finally {
        closed.set(true)
        runCatching { descriptor.getAndSet(null)?.close() }
        reader.shutdownNow()
    }
}
