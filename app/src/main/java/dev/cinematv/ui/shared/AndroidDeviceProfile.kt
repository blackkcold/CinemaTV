package dev.cinematv.ui.shared

import android.app.ActivityManager
import android.content.Context
import android.os.Build

fun deviceProfile(context: Context): DeviceProfile {
    val manager = context.getSystemService(ActivityManager::class.java)
    return resolveDeviceProfile(manager?.isLowRamDevice ?: true, manager?.memoryClass ?: 0, Build.VERSION.SDK_INT)
}
