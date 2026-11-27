package dev.cinematv.ui.tv.design

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One layout, one type scale, every theme.
 *
 * A theme that could change geometry would turn a theme switch into a full measure/layout
 * pass, shift every golden's structure, and make the two languages incomparable. The
 * contract is therefore structural, and this class proves it structurally: by the layout's
 * signature, by exact geometry values, by scanning where the layout is provided, and — once
 * Poster goldens exist — by the goldens sharing identical masks.
 */
class TvLayoutThemeIndependenceTest {

    // ---- The layout cannot depend on a theme -----------------------------------------

    @Test fun layoutTakesViewportDimensionsOnly() {
        val parameterTypes = TvLayout::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.simpleName }
        assertTrue("TvLayout must keep a constructor", parameterTypes.isNotEmpty())
        for (type in parameterTypes) {
            assertFalse(
                "TvLayout constructor must not accept $type; geometry is theme-independent",
                type.contains("Visual") || type.contains("Theme") || type.contains("Glass"),
            )
        }
        // The derived geometry is a function of width/height alone.
        val fields = TvLayout::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("expected width/height storage, found $fields", fields.containsAll(setOf("width", "height")))
    }

    @Test fun derivedGeometryIsAFixedFunctionOfTheViewport() {
        // Pinned values: if the formula changes, this fails and someone must re-review the
        // goldens instead of discovering the shift as a diff.
        val hd = TvLayout(width = 960.dp, height = 540.dp)
        assertEquals(52.8f, hd.gutter.value, .01f)             // 960 * 5.5%
        assertEquals(318.6f, hd.heroHeight.value, .01f)        // 540 * 59%
        assertEquals(224.84f, hd.landscapeWidth.value, .01f)   // 854.4 / 3.8, inside 180..300
        assertEquals(140.07f, hd.posterWidth.value, .01f)      // 854.4 / 6.1
        assertEquals(18.9f, hd.navigationTop.value, .01f)      // 540 * 3.5%
        assertEquals(90.9f, hd.contentTop.value, .01f)         // navigationTop + 72

        val uhd = TvLayout(width = 1920.dp, height = 1080.dp)
        assertEquals(105.6f, uhd.gutter.value, .01f)
        assertEquals(637.2f, uhd.heroHeight.value, .01f)
        assertEquals(300f, uhd.landscapeWidth.value, .01f)     // 449.7 clamped to the 300dp ceiling
        assertEquals(190f, uhd.posterWidth.value, .01f)        // 280.1 clamped to the 190dp ceiling
        assertEquals(28f, uhd.navigationTop.value, .01f)       // 37.8 clamped to the 28dp ceiling
        assertEquals(100f, uhd.contentTop.value, .01f)         // clamped navigationTop + 72
    }

    @Test fun layoutIsAPureFunctionOfTheViewport() {
        // Derived geometry must depend only on width/height: no theme, no visual, no state.
        // Two independently constructed layouts with equal dimensions must agree on every
        // derived value, and repeated reads must not vary.
        val a = TvLayout(width = 960.dp, height = 540.dp)
        val b = TvLayout(width = 960.dp, height = 540.dp)
        assertEquals(a.gutter, b.gutter)
        assertEquals(a.heroHeight, b.heroHeight)
        assertEquals(a.landscapeWidth, b.landscapeWidth)
        assertEquals(a.posterWidth, b.posterWidth)
        assertEquals(a.navigationTop, b.navigationTop)
        assertEquals(a.contentTop, b.contentTop)
        assertEquals(a.gutter, a.gutter)
        assertEquals(a, b)
        // And the only inputs are still the viewport.
        assertFalse(a.toString().contains("Visual"))
        assertFalse(a.toString().contains("Theme"))
    }

    // ---- No provider may feed a theme-dependent value into the layout -----------------

    @Test fun noSourceProvidesAThemeDependentLayout() {
        val tvRoot = findDirectory("app/src/main/java/dev/cinematv")
        assumeTrue("source tree not reachable from the test working dir", tvRoot != null)
        val offenders = mutableListOf<String>()
        tvRoot!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val providesLayout = line.contains("LocalTvLayout") && line.contains("provides")
                if (providesLayout && (line.contains("VisualTheme") || line.contains("TvVisual"))) {
                    offenders += "${file.relativeTo(tvRoot)}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue("LocalTvLayout must never be provided from a theme value:\n$offenders", offenders.isEmpty())
    }

    @Test fun pagesNeverBranchOnTheTheme() {
        // The plan's hard rule: theme branching lives in the four drawing primitives only.
        //
        // A page may still *offer* the choice — the settings screen writes the preference and
        // displays the resolved title. What no page may do is read the theme to decide how
        // something is drawn, because that is how a second, page-local theme vocabulary starts.
        // Reading `LocalTvVisual.current.theme` or comparing against `VisualTheme.*` is flagged;
        // passing a `VisualTheme` value through (settings) and calling `.title` is allowed.
        val pages = findDirectory("app/src/main/java/dev/cinematv/ui/tv/pages")
        assumeTrue("pages directory not reachable from the test working dir", pages != null)
        val offenders = mutableListOf<String>()
        pages!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//").trim()
                // Displaying the resolved theme's own title is not branching: it reports state.
                val displayOnly = code.contains(".theme.title")
                val readsTheme = code.contains("LocalTvVisual.current") &&
                    (code.contains(".theme") || code.contains("==") || code.contains("when")) && !displayOnly
                val comparesTheme = Regex("""VisualTheme\.[A-Z]+\s*(==|!=)""").containsMatchIn(code) ||
                    Regex("""(==|!=)\s*VisualTheme\.[A-Z]+""").containsMatchIn(code)
                val branchesOnTheme = code.contains("TvVisual") && Regex("""\bif\s*\(""").containsMatchIn(code)
                if (readsTheme || comparesTheme || branchesOnTheme) {
                    offenders += "${file.name}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue("ui/tv/pages must not branch on the theme:\n$offenders", offenders.isEmpty())
    }

    // ---- Golden masks must be identical once Poster goldens exist ---------------------

    @Test fun bothThemesShareTheSameGoldenMasks() {
        val goldenRoot = findDirectory("design/ui/goldens")
        assumeTrue("golden directory not reachable from the test working dir", goldenRoot != null)
        val cinema = File(goldenRoot, "tv1080/manifest.json")
        val poster = File(goldenRoot, "poster/tv1080/manifest.json")
        assumeTrue("Cinema golden manifest missing", cinema.isFile)
        // Poster goldens land with P2. Skipping is explicit, never a silent pass.
        assumeTrue("Poster goldens not captured yet (P2)", poster.isFile)

        val cinemaMasks = masksByFile(cinema.readText())
        val posterMasks = masksByFile(poster.readText())
        assertTrue("Poster manifest lists no screens", posterMasks.isNotEmpty())
        assertEquals(
            "Both themes must obscure the same regions; identical layout means identical masks",
            cinemaMasks.keys,
            posterMasks.keys,
        )
        for ((screen, masks) in cinemaMasks) {
            assertEquals("mask mismatch on $screen", masks, posterMasks[screen])
        }
    }

    /** Minimal, allocation-light reading of the `masks` arrays: `[[a,b,c,d],[...]]` per file. */
    private fun masksByFile(json: String): Map<String, List<List<Int>>> {
        val result = linkedMapOf<String, List<List<Int>>>()
        val filePattern = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
        val maskPattern = Regex("\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]")
        var currentFile: String? = null
        for (line in json.lineSequence()) {
            filePattern.find(line)?.let { currentFile = it.groupValues[1] }
            val masks = maskPattern.findAll(line)
                .map { m -> m.groupValues.drop(1).map(String::toInt) }
                .toList()
            val file = currentFile
            if (file != null && line.contains("\"masks\"")) {
                result[file] = masks
            }
        }
        return result
    }

    /** Walk up from the test working directory until [relative] exists. */
    private fun findDirectory(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
