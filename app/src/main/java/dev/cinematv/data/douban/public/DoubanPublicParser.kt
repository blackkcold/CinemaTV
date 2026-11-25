package dev.cinematv.data.douban.public

import org.json.JSONObject

/**
 * Clean-room structural parser. It depends on published page shapes, keeps unknown
 * values empty and never invents a year, a score or a rank.
 */
object DoubanPublicParser {

    fun top250(html: String, start: Int): List<DoubanPublicItem> {
        if (start < 0 || start > 225) throw DoubanPublicException(DoubanPublicStop.PARSING, "榜单起点无效")
        val blocks = split(html, "<div[^>]*\\sclass=\"item\"[^>]*>".toRegex())
        if (blocks.isEmpty() && html.contains("class=\"item\"")) {
            throw DoubanPublicException(DoubanPublicStop.PARSING, "榜单页面结构无法识别")
        }
        val seen = mutableSetOf<String>()
        return blocks.mapIndexedNotNull { index, block ->
            val id = Regex("/subject/([0-9]{1,16})/").find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            if (!seen.add(id)) return@mapIndexedNotNull null
            val title = Regex("<span\\s+class=\"title\">([^<]+)</span>")
                .find(block)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapIndexedNotNull null
            val rating = Regex("<span\\s+class=\"rating_num\"[^>]*>\\s*([0-9]+(?:\\.[0-9]+)?)\\s*</span>")
                .find(block)?.groupValues?.get(1).orEmpty()
            val cover = Regex("<img[^>]+src=\"(https://[^\"]+)\"").find(block)?.groupValues?.get(1).orEmpty()
            DoubanPublicItem(
                subjectId = id, title = decode(title), rating = rating, cover = cover,
                rank = (start + index + 1).takeIf { it in 1..250 }, kind = DoubanPublicKind.MOVIE
            )
        }
    }

    fun newSearch(json: String, start: Int, kind: DoubanPublicKind): List<DoubanPublicItem> =
        parseArray(json, "data", start, kind)

    fun hot(json: String, kind: DoubanPublicKind): List<DoubanPublicItem> =
        parseArray(json, "subjects", 0, kind)

    fun doulist(html: String): List<DoubanPublicItem> {
        val blocks = split(html, "<div[^>]*\\sclass=\"[^\"]*\\bdoulist-item\\b[^\"]*\"[^>]*>".toRegex())
        val seen = mutableSetOf<String>()
        return blocks.mapNotNull { block ->
            val id = Regex("/subject/([0-9]{1,16})/").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = Regex("<div\\s+class=\"title\">\\s*<a[^>]*>\\s*(.*?)\\s*</a>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let(::stripTags)?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val rating = Regex("<span\\s+class=\"rating_nums\">\\s*([0-9]+(?:\\.[0-9]+)?)\\s*</span>")
                .find(block)?.groupValues?.get(1).orEmpty()
            // The first image on a doulist item can be a streaming-service icon; only
            // a Douban photo path is a poster.
            val post = Regex("<div\\s+class=\"post\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
            val cover = (post?.let { Regex("<img[^>]+src=\"(https://[^\"]+)\"").find(it) }
                ?: Regex("<img[^>]+src=\"(https://[^\"]*doubanio\\.com/view/photo/[^\"]+)\"").find(block))
                ?.groupValues?.get(1).orEmpty()
            DoubanPublicItem(id, decode(title), rating = rating, cover = cover)
        }
    }

    /** Detects a complete page. A missing total is reported, never guessed. */
    fun hasNextTop250(html: String, start: Int, count: Int): Boolean {
        if (count < 25) return false
        val next = Regex("<span\\s+class=\"next\"[^>]*>\\s*<a[^>]*href=\"[^\"]*start=([0-9]+)").find(html)
            ?.groupValues?.get(1)?.toIntOrNull()
        return next != null && next > start && next < 250
    }

    private fun parseArray(json: String, key: String, start: Int, kind: DoubanPublicKind): List<DoubanPublicItem> {
        if (start < 0) throw DoubanPublicException(DoubanPublicStop.PARSING, "起点无效")
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw DoubanPublicException(DoubanPublicStop.PARSING, "豆瓣返回了无法解析的内容") }
        val array = root.optJSONArray(key)
            ?: throw DoubanPublicException(DoubanPublicStop.PARSING, "豆瓣返回了未预期的结构")
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (!id.matches(Regex("[0-9]{1,16}")) || title.isBlank()) return@mapNotNull null
            DoubanPublicItem(
                subjectId = id, title = title,
                rating = item.optString("rate").trim()
                    .takeIf { it.toDoubleOrNull()?.let { value -> value in 0.0..10.0 } == true }.orEmpty(),
                cover = item.optString("cover").takeIf { it.startsWith("https://") }.orEmpty(),
                kind = kind
            )
        }
    }

    private fun split(html: String, marker: Regex): List<String> {
        val starts = marker.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { index, from ->
            html.substring(from, starts.getOrElse(index + 1) { html.length })
        }
    }

    private fun stripTags(value: String): String = decode(value.replace(Regex("<[^>]+>"), ""))
    private fun decode(value: String): String = value
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#x([0-9a-fA-F]{1,6});")) { match ->
            runCatching { String(Character.toChars(match.groupValues[1].toInt(16))) }.getOrDefault("")
        }
}
