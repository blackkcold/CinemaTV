package dev.cinematv

import dev.cinematv.ui.shared.*
import org.junit.Assert.*
import org.junit.Test

class DeviceProfileTest {
    @Test fun capacityHintsNeverOverrideLowRam() {
        assertEquals(DeviceTier.BASIC, resolveDeviceProfile(true, 512, 36).tier)
        assertEquals(DeviceTier.BASIC, resolveDeviceProfile(false, 128, 36).tier)
        assertEquals(DeviceTier.BASIC, resolveDeviceProfile(false, 512, 30).tier)
        assertEquals(DeviceTier.BALANCED, resolveDeviceProfile(false, 192, 33).tier)
        assertEquals(DeviceTier.ENHANCED, resolveDeviceProfile(false, 256, 33).tier)
    }
    @Test fun lowEndHasBoundedMemoryAndNoPrefetch() {
        val basic = DeviceProfile(DeviceTier.BASIC)
        val high = DeviceProfile(DeviceTier.ENHANCED)
        assertEquals(0, basic.artworkPrefetchCount)
        assertTrue(basic.playbackBufferBytes < high.playbackBufferBytes)
        assertEquals(64L * 1024 * 1024, basic.diskCacheBytes)
    }
}
