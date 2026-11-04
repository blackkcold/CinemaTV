package dev.cinematv.ui.shared

/** Match AUTO glass's conservative device tier, including devices not flagged low-RAM. */
internal fun brandDeviceNeedsStaticMotion(lowRam: Boolean, memoryClassMb: Int): Boolean =
    lowRam || memoryClassMb <= 128
