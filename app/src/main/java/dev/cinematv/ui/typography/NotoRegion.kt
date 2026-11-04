package dev.cinematv.ui.typography

import java.util.Locale

/** Indexes refer to the pinned, unmodified Noto Sans CJK 2.004 collection. */
enum class NotoRegion(val ttcIndex: Int, val languageTag: String) {
    SC(2, "zh-Hans-CN"), TC(3, "zh-Hant-TW"), HK(4, "zh-Hant-HK"),
    JP(0, "ja-JP"), KR(1, "ko-KR")
}

object NotoRegionPolicy {
    /** Explicit script beats country; never infer a person's nationality from text. */
    fun fromLanguageTag(tag: String?): NotoRegion? {
        if (tag.isNullOrBlank()) return null
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        return when (locale.language.lowercase(Locale.ROOT)) {
            "ja" -> NotoRegion.JP
            "ko" -> NotoRegion.KR
            "zh", "yue" -> when {
                locale.script.equals("Hans", true) -> NotoRegion.SC
                locale.country in setOf("HK", "MO") -> NotoRegion.HK
                locale.script.equals("Hant", true) -> NotoRegion.TC
                locale.country == "TW" -> NotoRegion.TC
                locale.language == "yue" -> NotoRegion.HK
                else -> NotoRegion.SC
            }
            else -> null
        }
    }

    /** Metadata first, then ordered user/app locales. Untagged Han defaults to SC. */
    fun resolve(contentLanguageTag: String?, uiLanguageTags: List<String>): NotoRegion =
        fromLanguageTag(contentLanguageTag)
            ?: uiLanguageTags.firstNotNullOfOrNull(::fromLanguageTag)
            ?: NotoRegion.SC

    /** Keep combining marks / variation selectors with their preceding script run. */
    fun isContinuation(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    fun isCjk(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO -> true
        else -> codePoint in 0x3000..0x30ff || codePoint in 0xff00..0xffef
    }
}
