package dev.cinematv.ui.tv.design

import dev.cinematv.Page

/** Render backends, deliberately separate from the persisted [GlassQuality] preference. */
enum class GlassRenderMode { BASIC, BLUR, FULL }

/** The semantic surface a material is drawn on. The level is a cap, never a mandate. */
enum class TvGlassRole { NAVIGATION, ACTION, PANEL, DIALOG, PLAYER_OVERLAY }

/**
 * The most a surface may render. Dialogs and player overlays are Basic by contract:
 * they have no protected still scene to sample. Panels may use the full area; no
 * per-screen area/count limit belongs here until G6 measurement supports one.
 */
fun glassLevelFor(page: Page, role: TvGlassRole): GlassRenderMode = when (role) {
    TvGlassRole.NAVIGATION, TvGlassRole.ACTION, TvGlassRole.PANEL -> GlassRenderMode.FULL
    TvGlassRole.DIALOG, TvGlassRole.PLAYER_OVERLAY -> GlassRenderMode.BASIC
}

/** Both the platform/user decision and the surface cap apply; the lower tier wins. */
fun effectiveGlassLevel(platform: GlassRenderMode, cap: GlassRenderMode): GlassRenderMode =
    if (platform.ordinal <= cap.ordinal) platform else cap

/** Platform thermal hints, not a GPU benchmark or a temperature measurement. */
enum class GlassThermalState { UNKNOWN, NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

data class GlassEnvironment(
    val api: Int,
    val lowRam: Boolean,
    val memoryClassMb: Int,
    val hardwareAccelerated: Boolean,
    val windowReady: Boolean = false,
    val powerSave: Boolean = false,
    val thermal: GlassThermalState = GlassThermalState.UNKNOWN,
    val userReducedMotion: Boolean = false,
    val systemAnimationsEnabled: Boolean = true,
) {
    val lowMemory: Boolean get() = lowRam || memoryClassMb <= 128
}

enum class GlassDecisionReason(val description: String) {
    REQUESTED_BASIC("已选择基础材质"),
    UNSUPPORTED_ANDROID("当前系统使用基础材质；普通模糊需要 Android 12 及以上"),
    LOW_MEMORY("当前设备内存较低，使用基础材质"),
    SOFTWARE_RENDERING("当前窗口未启用硬件绘制，使用基础材质"),
    WAITING_FOR_WINDOW("界面正在就绪，暂用基础材质"),
    POWER_SAVING("系统省电模式已开启，暂用基础材质"),
    THERMAL_PRESSURE("系统报告温度压力，暂时降低材质开销"),
    AUTO_CONSERVATIVE("自动模式使用普通模糊"),
    REQUESTED_BLUR("已选择普通模糊"),
    FULL_REQUIRES_ANDROID_13("完整光学需要 Android 13 及以上，当前使用普通模糊"),
    REQUESTED_FULL("已选择完整光学"),
}

data class GlassDecision(
    val mode: GlassRenderMode,
    val reason: GlassDecisionReason,
    val animationsEnabled: Boolean,
)

/**
 * Conservative admission policy. Android version and heap size are capability hints only:
 * AUTO never opts into full optics, and no model name or guessed GPU score is consulted.
 * A temporary fallback never changes the saved preference. Full retains the approved live,
 * full-resolution optical stack; focus, area and still-art gates do not belong here.
 * Reduced motion controls animation independently of the static optical material.
 */
fun resolveProductionGlass(requested: GlassQuality, environment: GlassEnvironment): GlassDecision {
    val severeThermal = environment.thermal in setOf(
        GlassThermalState.SEVERE, GlassThermalState.CRITICAL,
        GlassThermalState.EMERGENCY, GlassThermalState.SHUTDOWN,
    )
    val thermalPressure = severeThermal || environment.thermal == GlassThermalState.MODERATE
    val animate = !environment.userReducedMotion && environment.systemAnimationsEnabled &&
        !environment.lowMemory && !environment.powerSave && !thermalPressure
    fun decision(mode: GlassRenderMode, reason: GlassDecisionReason) = GlassDecision(mode, reason, animate)
    return when {
        requested == GlassQuality.BASIC -> decision(GlassRenderMode.BASIC, GlassDecisionReason.REQUESTED_BASIC)
        environment.api < 31 -> decision(GlassRenderMode.BASIC, GlassDecisionReason.UNSUPPORTED_ANDROID)
        environment.lowMemory -> decision(GlassRenderMode.BASIC, GlassDecisionReason.LOW_MEMORY)
        !environment.hardwareAccelerated -> decision(GlassRenderMode.BASIC, GlassDecisionReason.SOFTWARE_RENDERING)
        !environment.windowReady -> decision(GlassRenderMode.BASIC, GlassDecisionReason.WAITING_FOR_WINDOW)
        environment.powerSave -> decision(GlassRenderMode.BASIC, GlassDecisionReason.POWER_SAVING)
        severeThermal -> decision(GlassRenderMode.BASIC, GlassDecisionReason.THERMAL_PRESSURE)
        requested == GlassQuality.ENHANCED && environment.api < 33 ->
            decision(GlassRenderMode.BLUR, GlassDecisionReason.FULL_REQUIRES_ANDROID_13)
        requested == GlassQuality.ENHANCED && thermalPressure ->
            decision(GlassRenderMode.BLUR, GlassDecisionReason.THERMAL_PRESSURE)
        requested == GlassQuality.ENHANCED -> decision(GlassRenderMode.FULL, GlassDecisionReason.REQUESTED_FULL)
        requested == GlassQuality.BACKDROP -> decision(GlassRenderMode.BLUR, GlassDecisionReason.REQUESTED_BLUR)
        else -> decision(GlassRenderMode.BLUR, GlassDecisionReason.AUTO_CONSERVATIVE)
    }
}

/** Android's documented THERMAL_STATUS_* values; unknown vendor values stay unknown. */
internal fun glassThermalState(status: Int?): GlassThermalState = when (status) {
    0 -> GlassThermalState.NONE
    1 -> GlassThermalState.LIGHT
    2 -> GlassThermalState.MODERATE
    3 -> GlassThermalState.SEVERE
    4 -> GlassThermalState.CRITICAL
    5 -> GlassThermalState.EMERGENCY
    6 -> GlassThermalState.SHUTDOWN
    else -> GlassThermalState.UNKNOWN
}
