package dev.cinematv

import dev.cinematv.ui.shared.brandDeviceNeedsStaticMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandMotionPolicyTest {
    @Test fun matchesConservativeGlassDeviceTier() {
        for (memoryMb in listOf(0, 64, 127, 128)) {
            assertTrue(brandDeviceNeedsStaticMotion(false, memoryMb))
        }
        for (memoryMb in listOf(129, 256, 512)) {
            assertFalse(brandDeviceNeedsStaticMotion(false, memoryMb))
            assertTrue(brandDeviceNeedsStaticMotion(true, memoryMb))
        }
    }
}
