package dev.cinematv.ui.typography

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematv.NotoTestActivity
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Tests the real Compose descriptor/wrapper path, not manually fake-bolded Canvas text. */
internal object NotoComposeWeightEvidence {
    fun capture(compose: ComposeContentTestRule, activity: Activity, fonts: CinemaNotoFonts) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = createFontFamilyResolver(context)
        val region = mutableStateOf(NotoRegion.SC)
        val tv = mutableStateOf(false)
        val epoch = mutableIntStateOf(0)
        val drawTracker = NotoCompositorCapture.DrawEpochTracker()
        val host = AtomicReference<View>()
        compose.setContent {
            val view = LocalView.current
            SideEffect { host.set(view) }
            CompositionLocalProvider(LocalCinemaNoto provides fonts,
                LocalCinemaLanguageTags provides listOf(region.value.languageTag)) {
                MaterialTheme {
                    Column(Modifier.background(Color.Black).drawWithContent {
                        val currentEpoch = epoch.intValue
                        drawContent()
                        drawTracker.recordDraw(currentEpoch)
                    }) {
                        for ((tag, weight, synthesis) in listOf(
                            Triple("regular", FontWeight.Normal, FontSynthesis.All),
                            Triple("bold", FontWeight.Bold, FontSynthesis.All),
                            Triple("no-synthesis", FontWeight.Bold, FontSynthesis.None),
                        )) {
                            val modifier = Modifier.width(280.dp).height(64.dp)
                                .background(Color.Black).testTag(tag)
                            val style = TextStyle(fontSynthesis = synthesis)
                            if (tv.value) CinemaTvText("漢骨直令", modifier = modifier,
                                color = Color.White, fontSize = 36.sp, fontWeight = weight,
                                style = style, contentLanguageTag = region.value.languageTag)
                            else CinemaText("漢骨直令", modifier = modifier,
                                color = Color.White, fontSize = 36.sp, fontWeight = weight,
                                style = style, contentLanguageTag = region.value.languageTag)
                        }
                    }
                }
            }
        }
        val folder = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: requireNotNull(context.getExternalFilesDir(null)).absolutePath,
            "ui-evidence/noto/compose-weight").apply { check(isDirectory || mkdirs()) }
        val evidence = JSONArray()
        for (useTv in listOf(false, true)) for (selected in NotoRegion.entries) {
            val family = fonts.cjk(selected)
            val regular = resolver.resolve(family, FontWeight.Normal, FontStyle.Normal, FontSynthesis.All).value as Typeface
            val bold = resolver.resolve(family, FontWeight.Bold, FontStyle.Normal, FontSynthesis.All).value as Typeface
            val noSynthesis = resolver.resolve(family, FontWeight.Bold, FontStyle.Normal, FontSynthesis.None).value as Typeface
            assertFalse("${selected.name} regular is unexpectedly bold", regular.isBold)
            assertTrue("${selected.name} Compose resolver ignored Bold", bold.isBold)
            assertFalse("${selected.name} ignored FontSynthesis.None", noSynthesis.isBold)
            compose.waitForIdle()
            val nextEpoch = epoch.intValue + 1
            val frameEvidence = NotoCompositorCapture.settleFreshFrame(activity, drawTracker, nextEpoch) {
                region.value = selected; tv.value = useTv; epoch.intValue = nextEpoch
            }
            compose.waitForIdle()
            val label = "${if (useTv) "tv" else "phone"}-${selected.name.lowercase()}"
            val ink = mutableMapOf<String, Long>()
            val hashes = mutableMapOf<String, String>()
            val sizes = mutableMapOf<String, Pair<Int, Int>>()
            val backgroundGuards = mutableMapOf<String, List<Int>>()
            val tags = listOf("regular", "bold", "no-synthesis")
            val bounds = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInWindow }
            val location = NotoCompositorCapture.location(checkNotNull(host.get()))
            val windowUnion = Rect(
                floor(bounds.values.minOf { it.left }).toInt(), floor(bounds.values.minOf { it.top }).toInt(),
                ceil(bounds.values.maxOf { it.right }).toInt(), ceil(bounds.values.maxOf { it.bottom }).toInt(),
            )
            val windowRegionCapture = Build.VERSION.SDK_INT == 26
            val display = if (windowRegionCapture)
                NotoCompositorCapture.captureWindowRegionApi26(activity, host.get(), location, windowUnion)
            else NotoCompositorCapture.capture(activity, host.get(), location)
            val originScreenX = if (windowRegionCapture) windowUnion.left + location.windowOriginX else 0
            val originScreenY = if (windowRegionCapture) windowUnion.top + location.windowOriginY else 0
            val rectangles = JSONObject()
            val captureWidth = display.width
            val captureHeight = display.height
            val captureKind = if (windowRegionCapture) "api26-own-window-region-pixelcopy" else "display-compositor"
            try {
                val captureFile = if (windowRegionCapture) "$label-window-region.png" else "$label-display.png"
                File(folder, captureFile).outputStream().use {
                    check(display.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                for (tag in tags) {
                    val windowBounds = bounds.getValue(tag)
                    val left = floor(windowBounds.left + location.windowOriginX).toInt()
                    val top = floor(windowBounds.top + location.windowOriginY).toInt()
                    val right = ceil(windowBounds.right + location.windowOriginX).toInt()
                    val bottom = ceil(windowBounds.bottom + location.windowOriginY).toInt()
                    val captureLeft = left - originScreenX
                    val captureTop = top - originScreenY
                    val captureRight = right - originScreenX
                    val captureBottom = bottom - originScreenY
                    check(captureLeft >= 0 && captureTop >= 0 && captureRight <= display.width &&
                        captureBottom <= display.height && captureRight > captureLeft && captureBottom > captureTop) {
                        "Specimen $tag is clipped/outside capture: $windowBounds"
                    }
                    val bitmap = Bitmap.createBitmap(display, captureLeft, captureTop, right - left, bottom - top)
                    try {
                        ink[tag] = luminanceInk(bitmap)
                        hashes[tag] = pixelHash(bitmap)
                        sizes[tag] = bitmap.width to bitmap.height
                        backgroundGuards[tag] = backgroundGuardPixels(bitmap)
                        File(folder, "$label-$tag.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        rectangles.put(tag, JSONObject()
                            .put("windowLeft", windowBounds.left).put("windowTop", windowBounds.top)
                            .put("windowRight", windowBounds.right).put("windowBottom", windowBounds.bottom)
                            .put("screenLeft", left).put("screenTop", top)
                            .put("screenRight", right).put("screenBottom", bottom)
                            .put("captureLeft", captureLeft).put("captureTop", captureTop)
                            .put("captureRight", captureRight).put("captureBottom", captureBottom))
                    } finally { if (bitmap !== display) bitmap.recycle() }
                }
            } finally { display.recycle() }
            val normalInk = ink.getValue("regular")
            val boldInk = ink.getValue("bold")
            val disabledInk = ink.getValue("no-synthesis")
            val sampleEvidence = JSONObject().put("surface", if (useTv) "tv" else "phone")
                .put("region", selected.name).put("ttcIndex", selected.ttcIndex)
                .put("captureKind", captureKind).put("api", Build.VERSION.SDK_INT)
                .put("captureWidth", captureWidth).put("captureHeight", captureHeight)
                .put("displayWidth", if (windowRegionCapture) JSONObject.NULL else captureWidth)
                .put("displayHeight", if (windowRegionCapture) JSONObject.NULL else captureHeight)
                .put("captureOriginScreenX", originScreenX).put("captureOriginScreenY", originScreenY)
                .put("captureWindowUnion", JSONObject().put("left", windowUnion.left).put("top", windowUnion.top)
                    .put("right", windowUnion.right).put("bottom", windowUnion.bottom))
                .put("hostScreenX", location.screenX).put("hostScreenY", location.screenY)
                .put("hostWindowX", location.windowX).put("hostWindowY", location.windowY)
                .put("windowOriginX", location.windowOriginX).put("windowOriginY", location.windowOriginY)
                .put("freshDrawEpoch", nextEpoch)
                .put("firstDrawFrameTimeNanos", frameEvidence.draw.frameTimeNanos)
                .put("matchedActualVsyncNanos", frameEvidence.matchedVsyncNanos)
                .put("frameFenceScope", "app frame metrics; not a physical presentation fence")
                .put("specimenLaunch", "font-only theme disables starting preview; product startup unchanged")
                .put("splashExitRemovals", (activity as? NotoTestActivity)?.splashExitRemovals ?: JSONObject.NULL)
                .put("specimenBounds", rectangles)
                .put("backgroundGuardArgb", JSONObject(backgroundGuards.mapValues { (_, pixels) ->
                    JSONArray(pixels.map { "%08x".format(it) })
                } as Map<*, *>))
                .put("argbPixelSha256", JSONObject(hashes as Map<*, *>))
                .put("regularInk", normalInk).put("boldInk", boldInk).put("synthesisNoneInk", disabledInk)
            // Keep the single capture and its provenance even when a pixel assertion fails.
            File(folder, "$label-capture.json").writeText(sampleEvidence.toString(2))
            for (tag in tags) assertTrue(
                "$label $tag background is not opaque black; window compositing contaminated the specimen: " +
                    backgroundGuards.getValue(tag).joinToString { "%08x".format(it) },
                backgroundGuards.getValue(tag).all { it == android.graphics.Color.BLACK },
            )
            assertTrue("$label regular specimen is blank", normalInk > 25_500L)
            assertTrue("$label Bold did not add stroke coverage: $boldInk <= $normalInk", boldInk > normalInk)
            assertEquals("$label disabling synthesis must render the regular face", normalInk, disabledInk)
            assertEquals("$label regular/no-synthesis dimensions differ", sizes.getValue("regular"), sizes.getValue("no-synthesis"))
            assertEquals("$label regular/no-synthesis pixels differ", hashes.getValue("regular"), hashes.getValue("no-synthesis"))
            evidence.put(sampleEvidence)
        }
        File(folder, "compose-weight.json").writeText(JSONObject()
            .put("sample", "漢骨直令").put("fontSizeSp", 36)
            .put("capture", if (Build.VERSION.SDK_INT == 26)
                "API26 own non-secure Activity window specimen union via bounded PixelCopy; not a display screenshot; no scaling"
                else "UiAutomation display compositor; measured window-to-screen offsets; no scaling")
            .put("captureCount", NotoRegion.entries.size * 2)
            .put("scope", "Actual CinemaText/CinemaTvText Compose output; within-face weight comparison only")
            .put("samples", evidence).toString(2))
    }

    /** The right margin is outside the four glyphs; use inset pixels, not a clipped crop edge. */
    private fun backgroundGuardPixels(bitmap: Bitmap): List<Int> {
        check(bitmap.width > 16 && bitmap.height > 16)
        return listOf(8, bitmap.height / 2, bitmap.height - 9).map { y ->
            bitmap.getPixel(bitmap.width - 9, y)
        }
    }

    private fun pixelHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(bitmap.width)
        val bytes = ByteArray(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in pixels.indices) {
                val pixel = pixels[x]
                bytes[x * 4] = (pixel ushr 24).toByte()
                bytes[x * 4 + 1] = (pixel ushr 16).toByte()
                bytes[x * 4 + 2] = (pixel ushr 8).toByte()
                bytes[x * 4 + 3] = pixel.toByte()
            }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun luminanceInk(bitmap: Bitmap): Long {
        val pixels = IntArray(bitmap.width)
        var total = 0L
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (pixel in pixels) total += android.graphics.Color.red(pixel)
        }
        return total
    }
}
