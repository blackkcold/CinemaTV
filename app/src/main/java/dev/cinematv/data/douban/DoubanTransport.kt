package dev.cinematv.data.douban

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

interface DoubanTransport {
    suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit): String
    suspend fun get(path: String, cookie: String, access: DoubanFeatureAccess, ticket: Long, beforeRequest: suspend () -> Unit): String {
        access.check(ticket)
        return get(path, cookie) {
            access.check(ticket)
            beforeRequest()
            access.check(ticket)
        }.also { access.check(ticket) }
    }
    /** Adds the final same-origin URL without changing any existing transport contract. */
    suspend fun fetch(path: String, cookie: String, access: DoubanFeatureAccess, ticket: Long,
                      beforeRequest: suspend () -> Unit): DoubanFetch =
        DoubanFetch(get(path, cookie, access, ticket, beforeRequest))
}
/** Fixed origin, explicit redirect handling, no proxy fallback, no retries, no HTTP cache or logs. */
class OfficialDoubanTransport internal constructor(private val client: Call.Factory = defaultClient()) : DoubanTransport {
    // The ungated entry point deliberately cannot contact Douban.
    override suspend fun get(path: String, cookie: String, beforeRequest: suspend () -> Unit): String =
        throw DoubanFeatureDisabledException()
    override suspend fun get(path: String, cookie: String, access: DoubanFeatureAccess, ticket: Long,
                             beforeRequest: suspend () -> Unit): String =
        fetch(path, cookie, access, ticket, beforeRequest).body
    override suspend fun fetch(path: String, cookie: String, access: DoubanFeatureAccess, ticket: Long,
                               beforeRequest: suspend () -> Unit): DoubanFetch = withContext(Dispatchers.IO) {
        access.check(ticket)
        require(path == "/mine/" || path.matches(Regex("/people/[A-Za-z0-9_-]{1,80}/(collect|wish|do)\\?start=[0-9]+&sort=time&rating=all&filter=all&mode=grid")) || path.matches(Regex("/subject/[0-9]{1,16}/")))
        require(cookie.isNotBlank() && cookie.length <= 16_384 && !cookie.contains('\r') && !cookie.contains('\n'))
        val started = System.currentTimeMillis()
        var url = ("https://movie.douban.com$path").toHttpUrl()
        for (redirect in 0..2) {
            access.check(ticket)
            beforeRequest()
            access.check(ticket)
            val request = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", "CinemaTV/0.1 (personal library; user initiated)").build()
            val call = access.guard(ticket) { client.newCall(request) }
            val registration = access.onRevoked { call.cancel() }
            try {
                access.check(ticket)
                val result = suspendCancellableCoroutine<Response> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    access.guard(ticket) {
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                            override fun onResponse(call: Call, response: Response) {
                                continuation.resume(response) { _, value, _ -> value.close() }
                            }
                        })
                    }
                }
                result.use { response ->
                    try {
                        currentCoroutineContext().ensureActive(); access.check(ticket)
                        if (response.code in listOf(401, 403, 418)) throw DoubanAccessException(if(response.code == 401) DoubanPhase.NEEDS_LOGIN else DoubanPhase.CHALLENGE, "豆瓣拒绝访问（${response.code}），请在官方页面处理后继续")
                        if (response.code == 429) throw DoubanAccessException(DoubanPhase.RATE_LIMITED, "豆瓣要求稍后重试", retryAfter(response.header("Retry-After"), System.currentTimeMillis()))
                        if (response.code in 300..399) {
                            val destination = response.header("Location")?.let { url.resolve(it) }
                            if (destination?.host == "sec.douban.com") throw DoubanAccessException(DoubanPhase.CHALLENGE, "豆瓣需要安全验证")
                            if (destination?.host == "accounts.douban.com") throw DoubanAccessException(DoubanPhase.NEEDS_LOGIN, "登录已过期，请重新扫码")
                            if (destination == null || destination.scheme != "https" || destination.host != "movie.douban.com" || destination.port != 443 || destination.username.isNotEmpty() || destination.password.isNotEmpty() || !destination.encodedPath.matches(Regex("/people/[A-Za-z0-9_-]{1,80}/?")) || path != "/mine/" || redirect >= 2)
                                throw DoubanAccessException(DoubanPhase.ERROR, "未预期的跳转，已停止以保护登录态")
                            url = destination
                        } else {
                            if (!response.isSuccessful) throw DoubanAccessException(DoubanPhase.ERROR, "豆瓣暂时不可用（${response.code}）")
                            val body = response.body ?: throw DoubanAccessException(DoubanPhase.ERROR, "豆瓣返回空响应")
                            if (!body.contentType().toString().contains("text/html")) throw DoubanAccessException(DoubanPhase.ERROR, "豆瓣返回了未知内容类型")
                            val source = body.source(); source.request(2_000_001)
                            if (source.buffer.size > 2_000_000) throw DoubanAccessException(DoubanPhase.ERROR, "页面过大，已停止")
                            val byteCount = source.buffer.size
                            val text = source.readUtf8().also { currentCoroutineContext().ensureActive(); access.check(ticket); DoubanHtmlParser.checkAccess(it) }
                            dev.cinematv.diagnostics.DiagnosticLog.event("douban.account.request", fields = mapOf(
                                "endpoint" to dev.cinematv.diagnostics.DiagnosticRedaction.endpoint(path),
                                "status" to response.code.toString(),
                                "bytes" to byteCount.toString(),
                                "ms" to (System.currentTimeMillis() - started).toString()
                            ))
                            return@withContext DoubanFetch(text, url.toString())
                        }
                    } catch (error: DoubanAccessException) {
                        dev.cinematv.diagnostics.DiagnosticLog.event("douban.account.request.failed", fields = mapOf(
                            "endpoint" to dev.cinematv.diagnostics.DiagnosticRedaction.endpoint(path),
                            "status" to response.code.toString(),
                            "stop" to error.phase.name.lowercase(),
                            "ms" to (System.currentTimeMillis() - started).toString()
                        ))
                        throw error
                    }
                }
            } finally { registration.close() }
        }
        throw DoubanAccessException(DoubanPhase.ERROR, "跳转次数过多")
    }
    companion object {
        private fun defaultClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).build()
        fun retryAfter(value: String?, now: Long): Long {
            val seconds = value?.toLongOrNull()?.takeIf { it >= 0 }
            if (seconds != null) return if(seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
            return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(now) }.getOrDefault(now + 15 * 60_000)
        }
    }
}
