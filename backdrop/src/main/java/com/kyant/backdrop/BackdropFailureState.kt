package com.kyant.backdrop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

/**
 * CinemaTV's synchronous renderer circuit breaker. Remember one per scene/session and
 * pass it to every source/material in that scene. A failure remains latched until a
 * new state is deliberately created; switching quality does not retry a broken driver.
 *
 * Only RuntimeException is recoverable here. Native GPU crashes, VM errors and process
 * termination cannot be caught or certified safe by this boundary.
 */
@Stable
class BackdropFailureState {
    var failure: BackdropFailure? by mutableStateOf(null)
        private set

    val failed: Boolean get() = failure != null

    /** Diagnostics deliberately omit exception messages, which may contain app data. */
    val reason: String? get() = failure?.let {
        "${it.stage}: ${it.cause.javaClass.simpleName.ifEmpty { "RuntimeException" }}"
    }

    /** Also allows a host to retire a scene after its own synchronous rendering failure. */
    fun fail(stage: String, cause: RuntimeException) {
        if (!failed) failure = BackdropFailure(stage, cause)
    }

    internal fun <T> attempt(stage: String, block: () -> T): T? {
        if (failed) return null
        return try {
            block()
        } catch (cause: RuntimeException) {
            fail(stage, cause)
            null
        }
    }
}

data class BackdropFailure(val stage: String, val cause: RuntimeException)

/** Restore clipping/transforms before the caller paints its static fallback. */
internal fun DrawScope.attemptBackdropDraw(
    state: BackdropFailureState,
    stage: String,
    block: DrawScope.() -> Unit
) {
    if (state.failed) return
    if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
        state.fail("software.canvas", IllegalStateException())
        return
    }
    state.attempt(stage) {
        val canvas = drawContext.canvas
        canvas.save()
        try {
            block()
        } finally {
            canvas.restore()
        }
    }
}
