package dev.cinematv.ui.shared

/** Capacity hints, not benchmark results or a claim about decoder/HDR support. */
enum class DeviceTier { BASIC, BALANCED, ENHANCED }

data class DeviceProfile(val tier: DeviceTier) {
    val artworkPrefetchCount get() = when (tier) { DeviceTier.BASIC -> 0; DeviceTier.BALANCED -> 1; DeviceTier.ENHANCED -> 2 }
    val playbackBufferBytes get() = when (tier) { DeviceTier.BASIC -> 16; DeviceTier.BALANCED -> 32; DeviceTier.ENHANCED -> 64 } * 1024 * 1024
    val diskCacheBytes get() = (if (tier == DeviceTier.BASIC) 64L else 256L) * 1024 * 1024
}

fun resolveDeviceProfile(lowRam: Boolean, memoryClassMb: Int, api: Int): DeviceProfile = DeviceProfile(when {
    lowRam || memoryClassMb <= 128 || api < 31 -> DeviceTier.BASIC
    memoryClassMb >= 256 && api >= 33 -> DeviceTier.ENHANCED
    else -> DeviceTier.BALANCED
})
