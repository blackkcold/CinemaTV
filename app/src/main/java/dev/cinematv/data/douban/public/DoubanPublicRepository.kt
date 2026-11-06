package dev.cinematv.data.douban.public

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.cinematv.diagnostics.DiagnosticLog
import dev.cinematv.diagnostics.DiagnosticRedaction
import java.net.URLEncoder

/**
 * Anonymous list reads with one shared gate, one cache and at most one request in flight.
 * A cache hit is never described as an online refresh.
 */
class DoubanPublicRepository(
    private val transport: DoubanPublicTransport,
    val gate: DoubanPublicGate,
    private val cache: DoubanPublicCache,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()

    suspend fun top250(start: Int): DoubanPublicPage {
        if (start !in 0..225 || start % 25 != 0) {
            throw DoubanPublicException(DoubanPublicStop.PARSING, "榜单起点无效")
        }
        return io {
            load("top250:$start", "/top250?start=$start&filter=") { html ->
                val items = DoubanPublicParser.top250(html, start)
                val next = if (DoubanPublicParser.hasNextTop250(html, start, items.size)) start + 25 else null
                DoubanPublicPage(items, next, 0L, false, "network")
            }
        }
    }

    suspend fun browse(tag: String, start: Int = 0, sort: DoubanPublicSort = DoubanPublicSort.RATING,
                       kind: DoubanPublicKind = DoubanPublicKind.MOVIE): DoubanPublicPage {
        if (tag.isBlank() || tag.length > 40 || start !in 0..500 || start % 20 != 0) {
            throw DoubanPublicException(DoubanPublicStop.PARSING, "标签或起点无效")
        }
        val encoded = URLEncoder.encode(tag, "UTF-8")
        val path = "/j/new_search_subjects?sort=${sort.code}&range=0,10&tags=$encoded&start=$start"
        return io {
            load("browse:${kind.name}:${sort.code}:$tag:$start", path) { json ->
                val items = DoubanPublicParser.newSearch(json, start, kind)
                val next = if (items.size >= 20 && start < 480) start + 20 else null
                DoubanPublicPage(items, next, 0L, false, "network")
            }
        }
    }

    suspend fun hot(kind: DoubanPublicKind = DoubanPublicKind.MOVIE): DoubanPublicPage {
        val type = if (kind == DoubanPublicKind.TV) "tv" else "movie"
        val path = "/j/search_subjects?type=$type&tag=${URLEncoder.encode(DoubanPublicCatalog.HOT_TAG, "UTF-8")}" +
            "&sort=recommend&page_limit=40&page_start=0"
        return io {
            load("hot:$type", path) { json ->
                DoubanPublicPage(DoubanPublicParser.hot(json, kind), null, 0L, false, "network")
            }
        }
    }

    suspend fun doulist(id: String): DoubanPublicPage {
        if (!id.matches(Regex("[0-9]{1,16}"))) throw DoubanPublicException(DoubanPublicStop.PARSING, "豆列编号无效")
        return io {
            load("doulist:$id", "/doulist/$id/?sort=seq&playable=0") { html ->
                DoubanPublicPage(DoubanPublicParser.doulist(html), null, 0L, false, "network")
            }
        }
    }

    private suspend fun load(key: String, path: String, parse: (String) -> DoubanPublicPage): DoubanPublicPage {
        val endpoint = DiagnosticRedaction.endpoint(path)
        cache.read(key)?.let { cached ->
            // A fresh cache hit needs no gate slot at all.
            if (!cached.stale) {
                DiagnosticLog.verbose("douban.public.cache", fields = mapOf("endpoint" to endpoint, "result" to "fresh"))
                return cached
            }
        }
        return mutex.withLock {
            cache.read(key)?.let {
                if (!it.stale) {
                    DiagnosticLog.verbose("douban.public.cache", fields = mapOf("endpoint" to endpoint, "result" to "fresh"))
                    return@withLock it
                }
            }
            val before = gate.state()
            before.pausedUntil?.let { until ->
                DiagnosticLog.event("douban.public.gate", fields = mapOf(
                    "endpoint" to endpoint, "waitMs" to (until - now()).coerceAtLeast(0).toString(),
                    "budgetLeft" to before.budgetLeft.toString(), "strikes" to before.strikes.toString()
                ))
            }
            try {
                gate.awaitReady(pause)
            } catch (error: DoubanPublicException) {
                // A gate stop is the most common reason the user sees an empty list.
                DiagnosticLog.event("douban.public.gate.stop", fields = mapOf(
                    "endpoint" to endpoint, "stop" to error.stop.name.lowercase(),
                    "retryAt" to error.retryAt.toString()
                ))
                cache.read(key, staleMaxMs = DoubanPublicCache.STALE_MAX_MS)?.let { stale ->
                    return@withLock stale.copy(stale = true, source = "stale-cache")
                }
                throw error
            }
            gate.attempted()
            try {
                val body = transport.get(if (path.startsWith("http")) path else originFor(path) + path)
                val page = parse(body).copy(fetchedAt = now(), source = "network")
                gate.onSuccess()
                val written = cache.write(key, page)
                DiagnosticLog.verbose("douban.public.cache", fields = mapOf(
                    "endpoint" to endpoint, "result" to if (written) "write" else "write_failed",
                    "items" to page.items.size.toString()
                ))
                page
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: DoubanPublicException) {
                when (error.stop) {
                    DoubanPublicStop.THROTTLED -> gate.onThrottled(error.retryAt, now())
                    DoubanPublicStop.CHALLENGE -> gate.onChallenge(now())
                    DoubanPublicStop.UPSTREAM -> gate.onServerError(now())
                    else -> Unit
                }
                cache.read(key, staleMaxMs = DoubanPublicCache.STALE_MAX_MS)?.let { stale ->
                    DiagnosticLog.event("douban.public.cache", fields = mapOf(
                        "endpoint" to endpoint, "result" to "stale",
                        "stop" to error.stop.name.lowercase()
                    ))
                    return@withLock stale.copy(stale = true, source = "stale-cache")
                }
                throw error
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    private fun originFor(path: String): String =
        if (path.startsWith("/doulist/")) DoubanPublicUrls.LIST_ORIGIN else DoubanPublicUrls.MOVIE_ORIGIN
}
