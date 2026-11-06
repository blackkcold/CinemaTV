package dev.cinematv.hybrid

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.cinematv.ui.tv.design.GlassQuality

/** A temporary rendering ceiling. The user's saved material preference is never rewritten. */
class HybridDeviceEffects(private val context: Context, private val trimImages: () -> Unit) : AutoCloseable {
    private val memory = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    val lowRam: Boolean = memory?.isLowRamDevice == true
    var constrained by mutableStateOf(lowRam)
        private set
    private var pressure = false
    private var started = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    private val thermalListener = if (Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener { refresh() } else null
    private val callbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() { pressure = true; trimImages(); refresh() }
        override fun onTrimMemory(level: Int) {
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                pressure = true; trimImages(); refresh()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        context.registerComponentCallbacks(callbacks)
        runCatching { ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) }
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let { listener ->
            runCatching { power?.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener) }
        }
        refresh()
    }

    fun resumed() {
        val info = ActivityManager.MemoryInfo()
        memory?.getMemoryInfo(info)
        pressure = info.lowMemory
        refresh()
    }

    private fun refresh() {
        val thermal = Build.VERSION.SDK_INT >= 29 && (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE
        constrained = lowRam || pressure || power?.isPowerSaveMode == true || thermal
    }

    fun effective(requested: GlassQuality): String = when {
        constrained -> "BASIC"
        requested == GlassQuality.AUTO -> "BACKDROP"
        else -> requested.name
    }

    override fun close() {
        if (!started) return
        started = false
        context.unregisterComponentCallbacks(callbacks)
        runCatching { context.unregisterReceiver(receiver) }
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let { listener -> runCatching { power?.removeThermalStatusListener(listener) } }
    }
}
