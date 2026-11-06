package dev.cinematv

/**
 * Test-only WMS geometry; an app's Display may already carry a fixed-rotation override.
 * AOSP DisplayContent.dump (Android 11+) reports these fields from WMS itself:
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/wm/DisplayContent.java
 */
internal data class WindowManagerDisplaySnapshot(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val layoutPending: Boolean,
    val fixedRotationPending: Boolean,
    val rotationAnimationPending: Boolean,
) {
    fun matchesWindow(displayId: Int, rotation: Int, width: Int, height: Int): Boolean =
        this.displayId == displayId && this.rotation == rotation && this.width == width &&
            this.height == height && width > height && height > 0 &&
            !layoutPending && !fixedRotationPending && !rotationAnimationPending

    companion object {
        /** API30+ `dumpsys window -a displays`; missing/ambiguous required fields fail closed. */
        fun parse(dump: String, displayId: Int): WindowManagerDisplaySnapshot? {
            if (dump.contains("DUMP TIMEOUT", ignoreCase = true)) return null
            val headings = Regex("(?m)^\\s*Display: mDisplayId=(\\d+)(?:[ \\t].*)?$").findAll(dump).toList()
            val heading = headings.singleOrNull { it.groupValues[1].toIntOrNull() == displayId } ?: return null
            val next = headings.firstOrNull { it.range.first > heading.range.first }?.range?.first ?: dump.length
            val block = dump.substring(heading.range.first, next)
            val frames = Regex("(?m)^[ \\t]*DisplayFrames w=(\\d+) h=(\\d+) r=([0-3])[ \\t]*$")
                .findAll(block).singleOrNull() ?: return null
            val current = Regex("(?m)^[ \\t]*init=[^\\r\\n]*?\\bcur=(\\d+)x(\\d+)\\b")
                .findAll(block).singleOrNull() ?: return null
            val layout = Regex("(?m)^[ \\t]*deferred=(true|false) mLayoutNeeded=(true|false)(?:[ \\t].*)?$")
                .findAll(block).singleOrNull() ?: return null
            val width = frames.groupValues[1].toIntOrNull() ?: return null
            val height = frames.groupValues[2].toIntOrNull() ?: return null
            if (width <= 0 || height <= 0 || current.groupValues[1].toIntOrNull() != width ||
                current.groupValues[2].toIntOrNull() != height) return null
            val noAnimation = Regex("(?m)^[ \\t]*no ScreenRotationAnimation[ \\t]*$")
                .findAll(block).count()
            val animation = Regex("(?m)^[ \\t]*mScreenRotationAnimation:[ \\t]*$")
                .findAll(block).count()
            if (noAnimation + animation != 1) return null
            val fixed = Regex("(?m)^[ \\t]*mFixedRotationLaunchingApp=(.*)$").findAll(block).toList()
            if (fixed.size > 1 || fixed.any { it.groupValues[1].isBlank() } ||
                block.lineSequence().count { it.trimStart().startsWith("mFixedRotationLaunchingApp") } != fixed.size) return null
            return WindowManagerDisplaySnapshot(
                displayId, width, height, frames.groupValues[3].toInt(),
                layout.groupValues[1] != "false" || layout.groupValues[2] != "false",
                fixed.any { it.groupValues[1].trim() != "null" }, animation == 1,
            )
        }
    }
}

/** Two matching WMS samples are required both before and after the host's committed frame. */
internal class WindowManagerDisplayBarrier(private val displayId: Int, private val rotation: Int) {
    enum class Step { WAIT, COMMIT_FRAME, READY }
    private var previous: WindowManagerDisplaySnapshot? = null
    private var matchingSamples = 0
    private var frameCommitted = false

    fun observe(snapshot: WindowManagerDisplaySnapshot?, width: Int, height: Int, hostReady: Boolean): Step {
        if (!hostReady || snapshot?.matchesWindow(displayId, rotation, width, height) != true) {
            previous = null
            matchingSamples = 0
            frameCommitted = false
            return Step.WAIT
        }
        if (snapshot != previous) {
            previous = snapshot
            matchingSamples = 0
            frameCommitted = false
        }
        matchingSamples++
        if (matchingSamples < 2) return Step.WAIT
        return if (frameCommitted) Step.READY else Step.COMMIT_FRAME
    }

    fun committedFrame() {
        check(previous != null && matchingSamples >= 2)
        frameCommitted = true
        matchingSamples = 0
    }
}
