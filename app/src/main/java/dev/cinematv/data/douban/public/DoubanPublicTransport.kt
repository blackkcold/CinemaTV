package dev.cinematv.data.douban.public

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Anonymous public reads only. Fixed origins, an explicit path allowlist, no cookies,
 * no proxy rotation, no automatic redirect and one hard deadline per request.
 */
interface DoubanPublicTransport {
    suspend fun get(url: String): String
}

internal object DoubanPublicUrls {
    const val MOVIE_ORIGIN = "https://movie.douban.com"
    const val LIST_ORIGIN = "https://www.douban.com"
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    const val REFERER = "https://movie.douban.com/"

    private val top250 = Regex("^/top250\\?start=(0|[1-9][0-9]{0,2})&filter=$")
    private val browse = Regex("^/j/new_search_subjects\\?sort=[TSU]&range=[0-9],10&tags=[^\\s#]{1,600}&start=(0|[1-9][0-9]{0,3})$")
    private val hot = Regex("^/j/search_subjects\\?type=(movie|tv)&tag=%E7%83%AD%E9%97%A8&sort=recommend&page_limit=40&page_start=0$")
    private val doulist = Regex("^/doulist/[0-9]{1,16}/\\?sort=seq&playable=0$")

    fun allowed(url: HttpUrl): Boolean {
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        return when (url.host) {
            "movie.douban.com" -> top250.matches(path) || browse.matches(path) || hot.matches(path)
            "www.douban.com" -> doulist.matches(path)
            else -> false
        }
    }
}

class OfficialDoubanPublicTransport(
    private val client: OkHttpClient = defaultClient()
) : DoubanPublicTransport {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val target = url.toHttpUrlOrNull()
            ?: throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "未预期的豆瓣地址")
        if (target.scheme != "https" || target.username.isNotEmpty() || target.password.isNotEmpty() ||
            target.port != 443 || !DoubanPublicUrls.allowed(target)
        ) throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "未预期的豆瓣地址")
        val started = System.currentTimeMillis()
        val endpoint = dev.cinematv.diagnostics.DiagnosticRedaction.endpoint(target.encodedPath)
        val request = Request.Builder().url(target)
            .header("User-Agent", DoubanPublicUrls.USER_AGENT)
            .header("Referer", DoubanPublicUrls.REFERER)
            .header("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        val call = client.newCall(request)
        var observedStatus = 0
        try {
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            response.use {
                currentCoroutineContext().ensureActive()
                observedStatus = response.code
                if (response.code in 300..399) {
                    val host = response.header("Location")?.let { location -> target.resolve(location)?.host }
                    if (host == "sec.douban.com") {
                        throw DoubanPublicException(DoubanPublicStop.CHALLENGE, "豆瓣要求安全验证")
                    }
                    throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "豆瓣返回了未预期的跳转")
                }
                if (response.code == 429) {
                    val retryAt = DoubanPublicRetry.time(response.header("Retry-After"), System.currentTimeMillis())
                    throw DoubanPublicException(DoubanPublicStop.THROTTLED, "豆瓣要求稍后重试", retryAt)
                }
                if (response.code in listOf(401, 403, 418)) {
                    throw DoubanPublicException(DoubanPublicStop.CHALLENGE, "豆瓣拒绝了本次读取（${response.code}）")
                }
                if (response.code in 400..499) {
                    // A missing doulist or a malformed request must not become a persistent stop.
                    throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "豆瓣返回了未预期的状态（${response.code}）")
                }
                if (response.code in 500..599) {
                    throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "豆瓣暂时不可用（${response.code}）")
                }
                if (!response.isSuccessful) {
                    throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "豆瓣返回了未预期的状态")
                }
                val body = response.body ?: throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "豆瓣返回空响应")
                val type = body.contentType()?.toString().orEmpty()
                if (!type.contains("text/html") && !type.contains("application/json")) {
                    throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "豆瓣返回了未知内容类型")
                }
                val source = body.source()
                source.request(MAX_BYTES + 1)
                if (source.buffer.size > MAX_BYTES) {
                    throw DoubanPublicException(DoubanPublicStop.UPSTREAM, "页面过大，已停止")
                }
                val byteCount = source.buffer.size
                val text = source.readUtf8()
                currentCoroutineContext().ensureActive()
                if (isDoubanChallenge(text)) {
                    throw DoubanPublicException(DoubanPublicStop.CHALLENGE, "豆瓣要求安全验证")
                }
                dev.cinematv.diagnostics.DiagnosticLog.event("douban.public.request", fields = mapOf(
                    "endpoint" to endpoint, "status" to response.code.toString(),
                    "bytes" to byteCount.toString(),
                    "type" to type.substringBefore(';').take(40),
                    "ms" to (System.currentTimeMillis() - started).toString()
                ))
                text
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: DoubanPublicException) {
            dev.cinematv.diagnostics.DiagnosticLog.event("douban.public.failed", fields = mapOf(
                "endpoint" to endpoint, "stop" to error.stop.name.lowercase(),
                "status" to observedStatus.toString(),
                "retryAt" to error.retryAt.toString(),
                "ms" to (System.currentTimeMillis() - started).toString()
            ))
            throw error
        } catch (timeout: InterruptedIOException) {
            dev.cinematv.diagnostics.DiagnosticLog.event("douban.public.failed", fields = mapOf(
                "endpoint" to endpoint, "stop" to "stalled",
                "ms" to (System.currentTimeMillis() - started).toString()
            ))
            throw DoubanPublicException(DoubanPublicStop.STALLED, "豆瓣读取超时，已停止等待")
        } catch (io: IOException) {
            dev.cinematv.diagnostics.DiagnosticLog.event("douban.public.failed", fields = mapOf(
                "endpoint" to endpoint, "stop" to "offline",
                "ms" to (System.currentTimeMillis() - started).toString()
            ))
            throw DoubanPublicException(DoubanPublicStop.OFFLINE, "网络不可用或连接被中断")
        }
    }

    companion object {
        const val MAX_BYTES = 1_048_576L
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

internal object DoubanPublicRetry {
    fun time(value: String?, now: Long): Long {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return now + 15 * 60_000L
        raw.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
            return if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
        }
        return runCatching {
            java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli().coerceAtLeast(now)
        }.getOrDefault(now + 15 * 60_000L)
    }
}
