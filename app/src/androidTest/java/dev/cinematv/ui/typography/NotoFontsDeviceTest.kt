package dev.cinematv.ui.typography

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.NotoTestActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createAndroidComposeRule

/** Run on API26 and current TV/phone devices: proves native TTC loading, not merely file existence. */
class NotoFontsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<NotoTestActivity>()

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
