package dev.cinematv.data.douban.public

/** Public Douban pages are read without an account. They never carry personal data. */
enum class DoubanPublicKind { MOVIE, TV }

enum class DoubanPublicSort(val code: String, val label: String) {
    RATING("T", "评分"), HOT("S", "热度"), LATEST("U", "最新")
}

/** One list entry. Missing fields stay missing; nothing is inferred from position alone. */
data class DoubanPublicItem(
    val subjectId: String,
    val title: String,
    val year: String = "",
    val rating: String = "",
    val cover: String = "",
    val rank: Int? = null,
    val kind: DoubanPublicKind = DoubanPublicKind.MOVIE
) {
    init {
        require(subjectId.matches(Regex("[0-9]{1,16}")))
        require(title.isNotBlank())
        require(rank == null || rank in 1..250)
    }
}

data class DoubanPublicPage(
    val items: List<DoubanPublicItem>,
    val nextStart: Int?,
    val fetchedAt: Long,
    val stale: Boolean,
    val source: String
)

/** Stop reasons are distinct: a user must be able to tell throttling from a broken page. */
enum class DoubanPublicStop { THROTTLED, CHALLENGE, OFFLINE, STALLED, UPSTREAM, PARSING, GATED }

class DoubanPublicException(
    val stop: DoubanPublicStop,
    override val message: String,
    val retryAt: Long = 0
) : Exception(message)

/** Curated public lists. A tag never becomes a user's personal record. */
object DoubanPublicCatalog {
    const val TOP250_PATH = "/top250"
    const val HOT_TAG = "热门"
    val tags: List<String> = listOf(
        "热门", "悬疑", "科幻", "喜剧", "爱情", "动作", "犯罪", "动画", "纪录片",
        "国产剧", "美剧", "韩剧", "日剧"
    )
    /** A single, scannable row; the full list stays available to data code. */
    val uiTags: List<String> = listOf("热门", "悬疑", "科幻", "动画", "国产剧", "美剧")
    fun isTvTag(tag: String): Boolean = tag in setOf("国产剧", "美剧", "韩剧", "日剧", "电视剧")
}

/** True when a body is Douban's verification or anti-scraping page, not a list. */
internal fun isDoubanChallenge(body: String): Boolean =
    "检测到有异常请求" in body ||
        "sec.douban.com" in body ||
        "请开启JavaScript并刷新该页" in body ||
        ("<title>禁止访问" in body && "豆瓣" in body)
