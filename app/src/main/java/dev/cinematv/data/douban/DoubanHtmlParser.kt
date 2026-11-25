package dev.cinematv.data.douban

import org.jsoup.Jsoup
import java.net.URI

/** Strict DOM parsing, bounded by the transport. Unknown markup is never a successful empty sync. */
object DoubanHtmlParser {
    fun checkAccess(html: String) {
        val doc = Jsoup.parse(html)
        if (doc.select("form[action*=login], input[type=password], .account-form").isNotEmpty())
            throw DoubanAccessException(DoubanPhase.NEEDS_LOGIN, "登录已过期，请重新扫码")
        if (doc.select("#captcha, .captcha, input[name=captcha-solution], iframe[src*=captcha]").isNotEmpty() ||
            doc.title().contains("安全验证") || doc.title().contains("访问受限") || doc.text().contains("检测到有异常请求"))
            throw DoubanAccessException(DoubanPhase.CHALLENGE, "豆瓣需要安全验证，请在官方登录页完成后再继续")
    }
    /**
     * The redirect target of /mine/ is the authoritative account id; the navigation
     * block only supplies a display name. A renamed DOM therefore cannot strand a
     * freshly authenticated session.
     */
    fun identity(html: String, expectedOwner: String? = null): DoubanIdentity {
        checkAccess(html)
        val doc = Jsoup.parse(html)
        val nav = doc.selectFirst(".nav-user-account")
        val profile = nav?.select("a[href]")?.firstOrNull { personId(it.attr("href")) != null }
        val id = personId(profile?.attr("href").orEmpty()) ?: expectedOwner
            ?: throw DoubanAccessException(DoubanPhase.NEEDS_LOGIN, "尚未验证登录账户")
        val name = nav?.selectFirst(".bn-more span")?.text()?.removeSuffix("的帐号")?.removeSuffix("的账号")?.trim()
            ?.takeIf { it.isNotBlank() } ?: doc.selectFirst("h1")?.text()?.substringBefore("的")?.trim().orEmpty()
        return DoubanIdentity(id, name.ifBlank { "豆瓣用户" })
    }
    fun personId(url: String): String? = runCatching {
        val u = URI(url); if (u.host != "www.douban.com" && u.host != "movie.douban.com") return null
        Regex("^/people/([A-Za-z0-9_-]{1,80})/?$").matchEntire(u.path)?.groupValues?.get(1)
    }.getOrNull()
    fun collection(html: String, ownerId: String, shelf: DoubanShelf, start: Int): DoubanPage {
        checkAccess(html)
        val doc = Jsoup.parse(html)
        if(identity(html, ownerId).id != ownerId) throw DoubanAccessException(DoubanPhase.ERROR, "账户不一致，未导入片单")
        val heading = doc.selectFirst("h1")?.text().orEmpty()
        if(!heading.contains(shelf.label)) throw DoubanAccessException(DoubanPhase.ERROR, "片单类型无法确认，保留上次数据")
        val totalText = doc.selectFirst(".subject-num")?.text().orEmpty()
        val total = Regex("/\\s*([0-9]+)").find(totalText)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("[（(]([0-9]+)[)）]").find(heading)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw DoubanAccessException(DoubanPhase.ERROR, "片单总数无法确认，保留上次数据")
        require(total in 0..30_000 && start <= total)
        val grid = doc.selectFirst(".grid-view") ?: throw DoubanAccessException(DoubanPhase.ERROR, "片单页面结构变化，保留上次数据")
        val items = grid.select(".item")
        val entries = items.map { item ->
            val link = item.selectFirst(".title a[href]") ?: error("Missing subject link")
            val uri = URI(link.absUrl("href").ifBlank { link.attr("href") })
            require(uri.scheme == "https" && uri.host == "movie.douban.com") { "Unexpected subject origin" }
            val id = Regex("^/subject/([0-9]{1,16})/?$").matchEntire(uri.path)?.groupValues?.get(1) ?: error("Invalid subject ID")
            val stars = item.select("[class]").flatMap { it.classNames() }.firstNotNullOfOrNull { Regex("rating([1-5])-t").matchEntire(it)?.groupValues?.get(1)?.toInt() }
            val date = item.selectFirst(".date")?.text().orEmpty()
            DoubanEntry(id, link.text().trim(), shelf, stars, date)
        }
        require(entries.map { it.subjectId }.distinct().size == entries.size) { "Duplicate subject on page" }
        val next = doc.selectFirst(".paginator .next a[href]")?.attr("href")?.let { raw ->
            val base = URI("https://movie.douban.com/people/$ownerId/${shelf.path}")
            val u = if(raw.startsWith("?")) URI(base.toString() + raw) else base.resolve(raw)
            require(u.scheme == "https" && u.host == base.host && u.path.trimEnd('/') == base.path) { "Unexpected pagination destination" }
            val values = u.rawQuery.orEmpty().split('&').filter { it.startsWith("start=") }
            require(values.size == 1); val n = values.single().substringAfter('=').toInt(); require(n > start && n <= 30_000); n
        }
        require(start + entries.size <= total) { "Collection count inconsistent" }
        if(total == 0) require(entries.isEmpty() && next == null && start == 0)
        else require(entries.isNotEmpty()) { "Unexpected empty collection" }
        if(start + entries.size < total) require(next == start + entries.size) { "Incomplete or unknown pagination" }
        else require(next == null) { "Pagination exceeds total" }
        return DoubanPage(entries, next, total)
    }
    fun communityScore(html: String, expectedId: String): Double? {
        checkAccess(html)
        val doc = Jsoup.parse(html)
        val canonical = doc.selectFirst("meta[property=og:url]")?.attr("content") ?: doc.selectFirst("link[rel=canonical]")?.attr("href")
        require(canonical == "https://movie.douban.com/subject/$expectedId/") { "Unexpected detail identity" }
        require(doc.selectFirst("#content h1") != null) { "Unknown detail document" }
        return doc.selectFirst("strong[property=v:average]")?.text()?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
    }
}
