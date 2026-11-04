package dev.cinematv.ui.typography

import org.junit.Assert.*
import org.junit.Test

class NotoRegionPolicyTest {
    @Test fun mapsExplicitRegions() {
        mapOf("zh-Hans" to NotoRegion.SC, "zh-Hant" to NotoRegion.TC, "zh-CN" to NotoRegion.SC, "zh-SG" to NotoRegion.SC,
            "zh-TW" to NotoRegion.TC, "zh-HK" to NotoRegion.HK,
            "zh-MO" to NotoRegion.HK, "ja-JP" to NotoRegion.JP,
            "ko-KR" to NotoRegion.KR, "yue" to NotoRegion.HK,
            "zh_HK" to NotoRegion.HK).forEach { (tag, expected) ->
            assertEquals(tag, expected, NotoRegionPolicy.fromLanguageTag(tag))
        }
    }
    @Test fun explicitScriptWinsOverCountry() {
        assertEquals(NotoRegion.SC, NotoRegionPolicy.fromLanguageTag("zh-Hans-HK"))
        assertEquals(NotoRegion.TC, NotoRegionPolicy.fromLanguageTag("zh-Hant-CN"))
        assertEquals(NotoRegion.HK, NotoRegionPolicy.fromLanguageTag("zh-Hant-HK"))
    }
    @Test fun metadataWinsThenOrderedUserLocalesThenDocumentedDefault() {
        assertEquals(NotoRegion.JP, NotoRegionPolicy.resolve("ja", listOf("zh-TW")))
        assertEquals(NotoRegion.TC, NotoRegionPolicy.resolve(null, listOf("en", "zh-TW", "ja")))
        assertEquals(NotoRegion.HK, NotoRegionPolicy.resolve("und", listOf("zh-HK")))
        assertEquals(NotoRegion.SC, NotoRegionPolicy.resolve("en", listOf("en")))
        assertNull(NotoRegionPolicy.fromLanguageTag(""))
        assertNull(NotoRegionPolicy.fromLanguageTag("en-US"))
    }
    @Test fun detectsScriptsWithoutRewritingOrSplittingSurrogates() {
        for (text in listOf("汉", "漢", "あ", "ア", "ー", "한", "ㄅ", "。", "𠀀")) {
            assertTrue(text, NotoRegionPolicy.isCjk(text.codePointAt(0)))
        }
        for (text in listOf("A", "é", "م", "😀")) assertFalse(text, NotoRegionPolicy.isCjk(text.codePointAt(0)))
    }
    @Test fun combiningMarksAndVariationSelectorsStayInTheirRun() {
        assertTrue(NotoRegionPolicy.isContinuation(0x3099))
        assertTrue(NotoRegionPolicy.isContinuation(0xe0100))
        assertTrue(NotoRegionPolicy.isContinuation(0x0301))
        assertFalse(NotoRegionPolicy.isContinuation('A'.code))
    }
    @Test fun indexesMatchPinnedCollection() {
        assertEquals(listOf(2, 3, 4, 0, 1), NotoRegion.entries.map { it.ttcIndex })
    }
}
