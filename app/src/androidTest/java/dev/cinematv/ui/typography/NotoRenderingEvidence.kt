package dev.cinematv.ui.typography

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import org.json.JSONArray
import org.json.JSONObject

/** Native font rasterization evidence, separate from app screenshots and pixel goldens. */
internal object NotoRenderingEvidence {
    // Must agree with scripts/fonts/noto-fonts.lock.json; evidence rejects a substituted asset.
    private val expectedHashes = mapOf(
        "NotoSansCJK-Regular.ttc" to "b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a",
        "NotoSans-Regular.ttf" to "b85c38ecea8a7cfb39c24e395a4007474fa5a4fc864f6ee33309eb4948d232d5",
        "NotoSans-Bold.ttf" to "c976e4b1b99edc88775377fcc21692ca4bfa46b6d6ca6522bfda505b28ff9d6a",
    )
    private data class Sample(val label: String, val asset: String, val index: Int,
                              val language: String, val text: String, val weight: Int = 400)

    fun capture(context: Context) {
        val samples = listOf(
            Sample("SC", "NotoSansCJK-Regular.ttc", 2, "zh-Hans-CN", "骨直令 漢汉 「中文」，。"),
            Sample("TC", "NotoSansCJK-Regular.ttc", 3, "zh-Hant-TW", "骨直令 漢汉 「中文」，。"),
            Sample("HK", "NotoSansCJK-Regular.ttc", 4, "zh-Hant-HK", "骨直令 漢汉 「中文」，。"),
            Sample("JP", "NotoSansCJK-Regular.ttc", 0, "ja-JP", "骨直令 漢字 かな カナ「」。"),
            Sample("KR", "NotoSansCJK-Regular.ttc", 1, "ko-KR", "骨直令 漢字 한글 「」。"),
            Sample("Latin regular", "NotoSans-Regular.ttf", 0, "en", "CinemaTV Aa09 Éé Ω Ж ()!"),
            Sample("Latin bold", "NotoSans-Bold.ttf", 0, "en", "CinemaTV Aa09 Éé Ω Ж ()!", 700),
        )
        val folder = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: requireNotNull(context.getExternalFilesDir(null)).absolutePath,
            "ui-evidence/noto").apply { check(isDirectory || mkdirs()) }
        val bitmap = Bitmap.createBitmap(1200, 1640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Color.rgb(18, 20, 25)
        canvas.drawColor(background)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(162, 204, 236); textSize = 20f; typeface = Typeface.MONOSPACE
        }
        val evidence = JSONArray()
        val hashes = mutableMapOf<String, String>()
        try {
            canvas.drawText("Pinned Noto native rasterization | API ${Build.VERSION.SDK_INT}", 24f, 28f, labelPaint)
            samples.forEachIndexed { row, sample ->
                val assetPath = "fonts/${sample.asset}"
                val zipInfo = ZipFile(context.applicationInfo.sourceDir).use { apk ->
                    val entry = checkNotNull(apk.getEntry("assets/$assetPath"))
                    check(entry.method == ZipEntry.STORED) { "Font must be stored, not deflated: $assetPath" }
                    entry.size to entry.compressedSize
                }
                val assetOffset = context.assets.openFd(assetPath).use { descriptor ->
                    check(descriptor.length == zipInfo.first) { "Font descriptor length mismatch" }
                    descriptor.startOffset
                }
                val typeface = checkNotNull(Typeface.Builder(context.assets, "fonts/${sample.asset}")
                    .setTtcIndex(sample.index).setWeight(sample.weight).setItalic(false).build()) {
                    "Native load failed: ${sample.asset} index=${sample.index}"
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    color = Color.WHITE; textSize = 40f; this.typeface = typeface
                    textLocale = Locale.forLanguageTag(sample.language)
                }
                val checked = JSONArray()
                var offset = 0
                while (offset < sample.text.length) {
                    val codePoint = sample.text.codePointAt(offset)
                    val glyph = String(Character.toChars(codePoint))
                    if (!Character.isWhitespace(codePoint)) {
                        check(paint.hasGlyph(glyph)) { "Missing glyph U+${codePoint.toString(16)} in ${sample.label}" }
                        checked.put("U+${codePoint.toString(16).uppercase(Locale.ROOT)}")
                    }
                    offset += Character.charCount(codePoint)
                }
                val top = 48 + row * 224
                canvas.drawText("${sample.label} | ${sample.language} | TTC index ${sample.index} | weight ${sample.weight}",
                    24f, top + 22f, labelPaint)
                canvas.drawText(sample.asset, 24f, top + 46f, labelPaint)
                check(paint.measureText(sample.text) <= bitmap.width - 48f) { "Regular sample clips: ${sample.label}" }
                canvas.drawText(sample.text, 24f, top + 98f, paint)
                val regularInk = inkPixels(bitmap, background, top + 56, top + 104)
                check(regularInk > 100) { "Empty regular raster: ${sample.label}" }
                paint.textSize = 64f
                // Explicit native synthetic-bold specimen, not a claim of a real CJK bold master.
                paint.isFakeBoldText = sample.weight == 400
                check(paint.measureText(sample.text) <= bitmap.width - 48f) { "Large sample clips: ${sample.label}" }
                canvas.drawText(sample.text, 24f, top + 180f, paint)
                val largeInk = inkPixels(bitmap, background, top + 114, top + 194)
                check(largeInk > 100) { "Empty large/bold raster: ${sample.label}" }
                canvas.drawText(if (paint.isFakeBoldText) "40px regular / 64px synthetic bold"
                    else "40px real bold / 64px real bold", 24f, top + 214f, labelPaint)
                val hash = hashes.getOrPut(sample.asset) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    context.assets.open("fonts/${sample.asset}").use { input ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            digest.update(bytes, 0, count)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                check(hash == expectedHashes[sample.asset]) { "Unexpected packaged font bytes: ${sample.asset}" }
                evidence.put(JSONObject().put("label", sample.label).put("language", sample.language)
                    .put("asset", sample.asset).put("sha256", hash).put("ttcIndex", sample.index)
                    .put("zipMethod", "STORED").put("zipBytes", zipInfo.second)
                    .put("assetBytes", zipInfo.first).put("openFdSucceeded", true)
                    .put("assetOffset", assetOffset).put("assetOffsetMod4", assetOffset % 4)
                    .put("weight", sample.weight).put("sample", sample.text).put("hasGlyph", checked)
                    .put("regularInkPixels", regularInk).put("largeInkPixels", largeInk)
                    .put("largeSyntheticBold", paint.isFakeBoldText))
            }
            val png = File(folder, "noto-regional-native.png")
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(png.length() > 1024) { "Empty font evidence PNG" }
            File(folder, "noto-regional-native.json").writeText(JSONObject()
                .put("renderer", "Android Canvas software bitmap; not a window screenshot")
                .put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL)
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("samples", evidence).toString(2))
            android.util.Log.i("CinemaTVQa", "Noto font evidence: ${png.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun inkPixels(bitmap: Bitmap, background: Int, top: Int, bottom: Int): Int {
        val line = IntArray(bitmap.width)
        var count = 0
        for (y in top until bottom) {
            bitmap.getPixels(line, 0, bitmap.width, 0, y, bitmap.width, 1)
            count += line.count { it != background }
        }
        return count
    }
}
