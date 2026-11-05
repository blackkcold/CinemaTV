package dev.cinematv

import dev.cinematv.ui.tv.design.*
import org.junit.Assert.*
import org.junit.Test

class ProductionGlassPolicyTest {
    private val capable = GlassEnvironment(api = 33, lowRam = false, memoryClassMb = 256,
        hardwareAccelerated = true, windowReady = true, thermal = GlassThermalState.NONE)

    @Test fun autoNeverUsesFullOpticsEvenWithNewAndroidAndLargeHeap() {
        for (api in listOf(26, 30, 31, 32, 33, 36)) {
            for (heap in listOf(129, 192, 256, 1024)) {
                val result = resolveProductionGlass(GlassQuality.AUTO, capable.copy(api = api, memoryClassMb = heap))
                assertEquals(if (api < 31) GlassRenderMode.BASIC else GlassRenderMode.BLUR, result.mode)
                assertEquals(if (api < 31) GlassDecisionReason.UNSUPPORTED_ANDROID else GlassDecisionReason.AUTO_CONSERVATIVE,
                    result.reason)
            }
        }
    }

    @Test fun explicitFullUsesCorrectPlatformBackendAndNeverPretendsBasicIsBlur() {
        for (api in listOf(26, 30, 31, 32, 33, 36)) {
            val result = resolveProductionGlass(GlassQuality.ENHANCED, capable.copy(api = api))
            assertEquals(when {
                api < 31 -> GlassRenderMode.BASIC
                api < 33 -> GlassRenderMode.BLUR
                else -> GlassRenderMode.FULL
            }, result.mode)
            if (api in 31..32) assertEquals(GlassDecisionReason.FULL_REQUIRES_ANDROID_13, result.reason)
        }
    }

    @Test fun explicitBasicAndBlurRemainUserChoicesOnCapableHardware() {
        assertEquals(GlassDecision(GlassRenderMode.BASIC, GlassDecisionReason.REQUESTED_BASIC, true),
            resolveProductionGlass(GlassQuality.BASIC, capable))
        assertEquals(GlassDecision(GlassRenderMode.BLUR, GlassDecisionReason.REQUESTED_BLUR, true),
            resolveProductionGlass(GlassQuality.BACKDROP, capable))
        assertEquals(GlassRenderMode.BASIC,
            resolveProductionGlass(GlassQuality.BACKDROP, capable.copy(api = 26)).mode)
    }

    @Test fun lowRamAndHeapBoundaryCannotBeOverriddenByExplicitFull() {
        for (requested in GlassQuality.entries) {
            for (env in listOf(capable.copy(lowRam = true), capable.copy(memoryClassMb = 128),
                capable.copy(memoryClassMb = 0), capable.copy(memoryClassMb = -1))) {
                val result = resolveProductionGlass(requested, env)
                assertEquals(GlassRenderMode.BASIC, result.mode)
                assertFalse(result.animationsEnabled)
                if (requested != GlassQuality.BASIC) assertEquals(GlassDecisionReason.LOW_MEMORY, result.reason)
            }
        }
        assertEquals(GlassRenderMode.FULL,
            resolveProductionGlass(GlassQuality.ENHANCED, capable.copy(memoryClassMb = 129)).mode)
    }

    @Test fun softwareRenderingCannotAcquireEffects() {
        for (requested in listOf(GlassQuality.AUTO, GlassQuality.BACKDROP, GlassQuality.ENHANCED)) {
            val result = resolveProductionGlass(requested, capable.copy(hardwareAccelerated = false))
            assertEquals(GlassRenderMode.BASIC, result.mode)
            assertEquals(GlassDecisionReason.SOFTWARE_RENDERING, result.reason)
        }
    }

    @Test fun hardwareAttachmentAloneCannotStartTheLiveRenderer() {
        for (requested in listOf(GlassQuality.AUTO, GlassQuality.BACKDROP, GlassQuality.ENHANCED)) {
            val result = resolveProductionGlass(requested, capable.copy(windowReady = false))
            assertEquals(GlassRenderMode.BASIC, result.mode)
            assertEquals(GlassDecisionReason.WAITING_FOR_WINDOW, result.reason)
        }
        assertFalse(GlassEnvironment(api = 33, lowRam = false, memoryClassMb = 256,
            hardwareAccelerated = true).windowReady)
        assertEquals(GlassRenderMode.FULL, resolveProductionGlass(GlassQuality.ENHANCED, capable).mode)
    }

    @Test fun reducedMotionDisablesAnimationsWithoutChangingStaticOptics() {
        for (requested in GlassQuality.entries) {
            val ordinary = resolveProductionGlass(requested, capable)
            for (env in listOf(capable.copy(userReducedMotion = true),
                capable.copy(systemAnimationsEnabled = false),
                capable.copy(userReducedMotion = true, systemAnimationsEnabled = false))) {
                val result = resolveProductionGlass(requested, env)
                assertEquals(ordinary.mode, result.mode)
                assertEquals(ordinary.reason, result.reason)
                assertFalse(result.animationsEnabled)
            }
        }
        assertEquals(GlassRenderMode.FULL,
            resolveProductionGlass(GlassQuality.ENHANCED, capable.copy(userReducedMotion = true)).mode)
    }

    @Test fun powerSavingTemporarilyUsesBasicWithoutChangingTheRequestedMode() {
        val requested = GlassQuality.ENHANCED
        val result = resolveProductionGlass(requested, capable.copy(powerSave = true))
        assertEquals(GlassRenderMode.BASIC, result.mode)
        assertEquals(GlassDecisionReason.POWER_SAVING, result.reason)
        assertFalse(result.animationsEnabled)
        assertEquals(GlassRenderMode.FULL, resolveProductionGlass(requested, capable).mode)
    }

    @Test fun moderateThermalPressureRemovesFullAndSeverePressureRemovesBlurToo() {
        for (thermal in GlassThermalState.entries) {
            val result = resolveProductionGlass(GlassQuality.ENHANCED, capable.copy(thermal = thermal))
            val expected = when (thermal) {
                GlassThermalState.UNKNOWN, GlassThermalState.NONE, GlassThermalState.LIGHT -> GlassRenderMode.FULL
                GlassThermalState.MODERATE -> GlassRenderMode.BLUR
                else -> GlassRenderMode.BASIC
            }
            assertEquals("thermal=$thermal", expected, result.mode)
            assertEquals("thermal=$thermal", expected == GlassRenderMode.FULL, result.animationsEnabled)
            if (expected != GlassRenderMode.FULL) assertEquals(GlassDecisionReason.THERMAL_PRESSURE, result.reason)
        }
        for (requested in listOf(GlassQuality.AUTO, GlassQuality.BACKDROP)) {
            assertEquals(GlassRenderMode.BLUR,
                resolveProductionGlass(requested, capable.copy(thermal = GlassThermalState.MODERATE)).mode)
            assertEquals(GlassRenderMode.BASIC,
                resolveProductionGlass(requested, capable.copy(thermal = GlassThermalState.SEVERE)).mode)
        }
    }

    @Test fun thermalAndPowerRecoveryReevaluatesTheOriginalPreference() {
        val requested = GlassQuality.ENHANCED
        val sequence = listOf(capable, capable.copy(thermal = GlassThermalState.MODERATE),
            capable.copy(thermal = GlassThermalState.SEVERE), capable.copy(powerSave = true), capable)
        assertEquals(listOf(GlassRenderMode.FULL, GlassRenderMode.BLUR, GlassRenderMode.BASIC,
            GlassRenderMode.BASIC, GlassRenderMode.FULL), sequence.map { resolveProductionGlass(requested, it).mode })
    }

    @Test fun platformThermalMappingPreservesUnknownRatherThanInventingAScore() {
        val expected = listOf(GlassThermalState.NONE, GlassThermalState.LIGHT, GlassThermalState.MODERATE,
            GlassThermalState.SEVERE, GlassThermalState.CRITICAL, GlassThermalState.EMERGENCY, GlassThermalState.SHUTDOWN)
        expected.forEachIndexed { status, value -> assertEquals(value, glassThermalState(status)) }
        for (unknown in listOf(null, -1, 7, Int.MAX_VALUE)) assertEquals(GlassThermalState.UNKNOWN, glassThermalState(unknown))
    }
}
