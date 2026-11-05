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
    fun identity(html: String): DoubanIdentity {
        checkAccess(html)
        val doc = Jsoup.parse(html)
        val nav = doc.selectFirst(".nav-user-account") ?: throw DoubanAccessException(DoubanPhase.NEEDS_LOGIN, "尚未验证登录账户")
        val profile = nav.select("a[href]").firstOrNull { personId(it.attr("href")) != null }
            ?: throw DoubanAccessException(DoubanPhase.ERROR, "豆瓣账户页面结构变化，已停止")
        val name = nav.selectFirst(".bn-more span")?.text()?.removeSuffix("的帐号")?.removeSuffix("的账号")?.trim()
            ?.takeIf { it.isNotBlank() } ?: doc.selectFirst("h1")?.text()?.substringBefore("的")?.trim().orEmpty()
        return DoubanIdentity(personId(profile.attr("href"))!!, name.ifBlank { "豆瓣用户" })
    }
    fun personId(url: String): String? = runCatching {
        val u = URI(url); if (u.host != "www.douban.com" && u.host != "movie.douban.com") return null
        Regex("^/people/([A-Za-z0-9_-]{1,80})/?$").matchEntire(u.path)?.groupValues?.get(1)
    }.getOrNull()
    fun collection(html: String, ownerId: String, shelf: DoubanShelf, start: Int): DoubanPage {
        checkAccess(html)
        val doc = Jsoup.parse(html)
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
            val u = base.resolve(raw)
            require(u.scheme == "https" && u.host == base.host && u.path.trimEnd('/') == base.path) { "Unexpected pagination destination" }
            val values = u.rawQuery.orEmpty().split('&').filter { it.startsWith("start=") }
            require(values.size == 1); val n = values.single().substringAfter('=').toInt(); require(n > start && n <= 30_000); n
        }
        val count = doc.selectFirst(".subject-num")?.text()?.let { Regex("(?:/|共\\s*)([0-9]+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return DoubanPage(entries, next, count)
    }
    fun communityScore(html: String): Double? {
        checkAccess(html)
        val doc = Jsoup.parse(html)
        require(doc.selectFirst("#content h1") != null) { "Unknown detail document" }
        return doc.selectFirst("strong[property=v:average]")?.text()?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
    }
}
