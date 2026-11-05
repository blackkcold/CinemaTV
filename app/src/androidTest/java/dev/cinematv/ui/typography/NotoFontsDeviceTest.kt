package dev.cinematv.ui.typography

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.NotoTestActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule

/** Run on API26 and current TV/phone devices: proves native TTC loading, not merely file existence. */
class NotoFontsDeviceTest {
    // The font specimen is not an Activity-transition test. Disable only this
    // test host's launch animation; never change the device or product settings.
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<NotoTestActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().targetContext, NotoTestActivity::class.java
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    ) { rule ->
        var activity: NotoTestActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun drawEpochFenceRejectsUnarmedAndStaleFrameMetrics() {
        val tracker = NotoCompositorCapture.DrawEpochTracker()
        tracker.recordDraw(7) // Unarmed draws cannot become evidence.
        val epoch = tracker.arm(7)
        assertNull(epoch.firstDraw.get())
        tracker.recordDraw(7) // No public frame callback yet.
        assertNull(epoch.firstDraw.get())
        epoch.frameTime.set(100)
        tracker.recordDraw(6)
        assertNull(epoch.firstDraw.get())
        tracker.recordDraw(7)
        val first = epoch.firstDraw.get()
        assertEquals(NotoCompositorCapture.DrawStamp(7, 100), first)
        epoch.frameTime.set(200)
        tracker.recordDraw(7)
        assertEquals("Redraws must not move the first-draw fence", first, epoch.firstDraw.get())
        assertFalse(NotoCompositorCapture.isFrameAtOrAfterDraw(null, 7, 200))
        assertFalse(NotoCompositorCapture.isFrameAtOrAfterDraw(first, 8, 200))
        assertFalse(NotoCompositorCapture.isFrameAtOrAfterDraw(first, 7, 99))
        assertFalse(NotoCompositorCapture.isFrameAtOrAfterDraw(first, 7, -1))
        assertTrue(NotoCompositorCapture.isFrameAtOrAfterDraw(first, 7, 100))
        assertTrue(NotoCompositorCapture.isFrameAtOrAfterDraw(first, 7, 101))
        tracker.disarm(epoch)
        val next = tracker.arm(8)
        tracker.recordDraw(7)
        assertNull(next.firstDraw.get())
        tracker.disarm(next)
    }

    @Test fun composeCjkBoldHonorsWeightAndSynthesisOnBothSurfaces() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val fonts = NotoFontCache.load(assets)
        assertTrue("Failed fonts: ${fonts.failedAssets}", fonts.failedAssets.isEmpty())
        assertTrue("Noto Activity enter transition did not finish before the 10-second deadline",
            compose.activity.awaitEnterAnimationComplete(10_000))
        NotoComposeWeightEvidence.capture(compose, compose.activity, fonts)
    }

    @Test fun noAnimationLaunchStillReportsEnterTransitionComplete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, NotoTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        ActivityScenario.launch<NotoTestActivity>(intent).use { scenario ->
            lateinit var activity: NotoTestActivity
            scenario.onActivity { activity = it }
            assertTrue("WindowManager must report completion even for a no-animation launch",
                activity.awaitEnterAnimationComplete(10_000))
        }
    }

    @Test fun regionalFontsRenderWithOfflineEvidence() {
        NotoRenderingEvidence.capture(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test fun bundledFacesLoadOfflineAndAreCached() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val fonts = NotoFontCache.load(assets)
        assertTrue("Failed fonts: ${fonts.failedAssets}", fonts.failedAssets.isEmpty())
        assertEquals(NotoRegion.entries.toSet(), fonts.regional.keys)
        assertSame(fonts, NotoFontCache.load(assets))
    }

    @Test fun unavailableAssetsFallBackWithoutCrashing() {
        // Test APK assets do not contain the application's generated fonts.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fonts = NotoFontCache.loadUncached(assets)
        assertTrue(fonts.failedAssets.isNotEmpty())
        assertNotNull(fonts.cjk(NotoRegion.HK))
    }

    @Test fun mixedTextPreservesExactUnicodeAndSelectsRegionalSpans() {
        val fonts = CinemaNotoFonts()
        val text = "Cinema 漢字 𠀀 日本語 か\u3099 葛\uDB40\uDD00 😀 e\u0301"
        val annotated = annotateNotoText(text, fonts, NotoRegion.JP)
        assertEquals(text, annotated.text)
        val cjkSpans = annotated.spanStyles.filter { it.item.localeList != null }
        assertTrue(cjkSpans.isNotEmpty())
        assertTrue(cjkSpans.all { it.item.localeList!!.toString().contains("ja") })
        cjkSpans.forEach { span ->
            assertFalse(Character.isLowSurrogate(text[span.start]))
            if (span.end < text.length) assertFalse(Character.isLowSurrogate(text[span.end]))
        }
    }
}
