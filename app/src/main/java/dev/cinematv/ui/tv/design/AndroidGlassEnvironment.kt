package dev.cinematv.ui.tv.design

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor

/**
 * Passive platform hints, refreshed on foreground entry and platform callbacks. No polling,
 * GPU probing, model blacklist, persistent capability score or preference write is involved.
 * Renderer code must still check its actual hardware Canvas before using platform effects.
 */
@Composable
fun rememberGlassEnvironment(userReducedMotion: Boolean): GlassEnvironment {
    val context = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    var environment by remember(context, view) { mutableStateOf(readGlassEnvironment(context, view)) }
    var windowReady by remember(view) { mutableStateOf(false) }
    DisposableEffect(context, view, owner) {
        val handler = Handler(Looper.getMainLooper())
        var listening = false
        var receiverRegistered = false
        var animatorObserverRegistered = false
        var removeThermalListener: (() -> Unit)? = null
        var removeFrameCommit: (() -> Unit)? = null
        var lightweightFrameCommitted = false
        var readyFramePosted = false
        fun refresh() { environment = readGlassEnvironment(context, view) }
        val enableLive = Runnable {
            readyFramePosted = false
            if (listening && lightweightFrameCommitted && view.isAttachedToWindow && view.hasWindowFocus()) {
                windowReady = true
            }
        }
        fun considerReady() {
            if (listening && lightweightFrameCommitted && !windowReady && !readyFramePosted && view.hasWindowFocus()) {
                readyFramePosted = true
                // Keep shader construction out of the frame which first presents the UI.
                view.postOnAnimation(enableLive)
            }
        }
        fun armLightweightFrame() {
            // API26–30 cannot use either live backend, so no frame listener is needed there.
            if (Build.VERSION.SDK_INT >= 31 && listening && !lightweightFrameCommitted &&
                removeFrameCommit == null && view.isAttachedToWindow && view.isHardwareAccelerated) {
                removeFrameCommit = awaitGlassFrameCommit(view, handler) {
                    lightweightFrameCommitted = true
                    considerReady()
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }
        val animatorObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }
        fun start() {
            if (listening) return
            listening = true
            windowReady = false
            refresh()
            receiverRegistered = try {
                val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(receiver, filter)
                true
            } catch (_: RuntimeException) { false }
            animatorObserverRegistered = try {
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animatorObserver,
                )
                true
            } catch (_: RuntimeException) { false }
            if (Build.VERSION.SDK_INT >= 29) {
                removeThermalListener = observeGlassThermal(context, handler) { refresh() }
            }
            armLightweightFrame()
        }
        fun stop() {
            if (!listening) return
            listening = false
            windowReady = false
            lightweightFrameCommitted = false
            readyFramePosted = false
            view.removeCallbacks(enableLive)
            removeFrameCommit?.invoke()
            removeFrameCommit = null
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            if (animatorObserverRegistered) runCatching { context.contentResolver.unregisterContentObserver(animatorObserver) }
            removeThermalListener?.invoke()
            receiverRegistered = false
            animatorObserverRegistered = false
            removeThermalListener = null
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> { refresh(); armLightweightFrame(); considerReady() }
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
                else -> Unit
            }
        }
        val attachmentObserver = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { refresh(); armLightweightFrame() }
            override fun onViewDetachedFromWindow(v: View) {
                windowReady = false
                lightweightFrameCommitted = false
                readyFramePosted = false
                view.removeCallbacks(enableLive)
                removeFrameCommit?.invoke()
                removeFrameCommit = null
                refresh()
            }
        }
        val treeObserver = view.viewTreeObserver
        val focusObserver = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) considerReady() else {
                windowReady = false
                readyFramePosted = false
                view.removeCallbacks(enableLive)
            }
        }
        treeObserver.addOnWindowFocusChangeListener(focusObserver)
        view.addOnAttachStateChangeListener(attachmentObserver)
        owner.lifecycle.addObserver(lifecycleObserver)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            owner.lifecycle.removeObserver(lifecycleObserver)
            if (treeObserver.isAlive) treeObserver.removeOnWindowFocusChangeListener(focusObserver)
            view.removeOnAttachStateChangeListener(attachmentObserver)
            stop()
        }
    }
    return environment.copy(userReducedMotion = userReducedMotion, windowReady = windowReady)
}

/** A submitted hardware frame, not a layout/attach/draw callback or an arbitrary delay. */
@RequiresApi(29)
private fun awaitGlassFrameCommit(view: View, handler: Handler, committed: () -> Unit): (() -> Unit)? {
    val observer = view.viewTreeObserver
    var active = true
    val callback = Runnable { handler.post { if (active) committed() } }
    return try {
        observer.registerFrameCommitCallback(callback)
        // The first composition deliberately draws BASIC. Request one frame if that initial
        // draw happened before listener registration; this is not a recurring render loop.
        view.postInvalidateOnAnimation()
        val dispose: () -> Unit = {
            active = false
            if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
            Unit
        }
        dispose
    } catch (_: RuntimeException) { null }
}

private fun readGlassEnvironment(context: Context, view: View): GlassEnvironment {
    val activity = context.getSystemService(ActivityManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    return GlassEnvironment(
        api = Build.VERSION.SDK_INT,
        lowRam = activity?.isLowRamDevice ?: true,
        memoryClassMb = activity?.memoryClass ?: 0,
        hardwareAccelerated = view.isAttachedToWindow && view.isHardwareAccelerated,
        powerSave = runCatching { power?.isPowerSaveMode ?: false }.getOrDefault(false),
        thermal = if (Build.VERSION.SDK_INT >= 29) readGlassThermal(power) else GlassThermalState.UNKNOWN,
        systemAnimationsEnabled = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(false),
    )
}

@RequiresApi(29)
private fun readGlassThermal(power: PowerManager?): GlassThermalState =
    glassThermalState(runCatching { power?.currentThermalStatus }.getOrNull())

@RequiresApi(29)
private fun observeGlassThermal(context: Context, handler: Handler, changed: () -> Unit): (() -> Unit)? {
    val power = context.getSystemService(PowerManager::class.java) ?: return null
    var active = true
    val listener = PowerManager.OnThermalStatusChangedListener { if (active) changed() }
    return try {
        power.addThermalStatusListener(Executor { command -> handler.post(command) }, listener)
        val dispose: () -> Unit = {
            active = false
            runCatching { power.removeThermalStatusListener(listener) }
            Unit
        }
        dispose
    } catch (_: RuntimeException) { null }
}
