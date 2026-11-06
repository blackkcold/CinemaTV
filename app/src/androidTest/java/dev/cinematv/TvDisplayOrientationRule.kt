package dev.cinematv

import android.app.UiAutomation
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal data class TestDisplayGeometry(val rotation: Int, val width: Int, val height: Int) {
    val landscape get() = width > height && height > 0
}

/** Read the actual display through an application context, not an Activity's fixed-rotation override. */
@Suppress("DEPRECATION")
internal fun actualTestDisplay(): TestDisplayGeometry {
    val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        ?: throw AssertionError("Default test display is unavailable")
    val size = Point()
    display.getRealSize(size)
    return TestDisplayGeometry(display.rotation, size.x, size.y)
}

/** Runs outside the Compose/Activity rule, so no TV window is launched during display rotation. */
internal class TvDisplayOrientationRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val resolver = instrumentation.targetContext.contentResolver
            val before = actualTestDisplay()
            val previousAuto = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION)
            val previousRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, before.rotation)
            require(previousAuto in 0..1 && previousRotation in Surface.ROTATION_0..Surface.ROTATION_270)
            val naturalLandscape = if (before.rotation == Surface.ROTATION_0 || before.rotation == Surface.ROTATION_180)
                before.width > before.height else before.height > before.width
            val target = if (naturalLandscape) Surface.ROTATION_0 else Surface.ROTATION_90
            var failure: Throwable? = null
            try {
                check(automation.setRotation(target)) { "Unable to prepare the test display rotation" }
                awaitCondition("Actual display did not settle in landscape before TV Activity launch") {
                    actualTestDisplay().let { it.rotation == target && it.landscape }
                }
                base.evaluate()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                // Also runs when display preparation fails, and after the inner Activity rule disposes its window.
                try {
                    val restore = if (previousAuto == 0) previousRotation else UiAutomation.ROTATION_UNFREEZE
                    check(automation.setRotation(restore)) { "Unable to restore the test display rotation policy" }
                    awaitCondition("Test display rotation policy was not restored") {
                        Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION) == previousAuto &&
                            (previousAuto != 0 || (Settings.System.getInt(resolver, Settings.System.USER_ROTATION) == previousRotation &&
                                actualTestDisplay().rotation == previousRotation))
                    }
                } catch (restoreFailure: Throwable) {
                    if (failure != null) failure.addSuppressed(restoreFailure) else throw restoreFailure
                }
            }
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var stable = 0
        while (SystemClock.uptimeMillis() < deadline) {
            stable = if (condition()) stable + 1 else 0
            if (stable >= 2) return
            SystemClock.sleep(50)
        }
        throw AssertionError("$message: ${actualTestDisplay()}")
    }
}
