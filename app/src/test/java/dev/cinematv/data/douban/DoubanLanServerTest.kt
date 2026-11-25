package dev.cinematv.data.douban

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class DoubanLanServerTest {
    @Test fun lanControlRequiresOriginTokenAndCannotReplayOrOutliveExpiry() {
        var clock = 1_000L; val opened = java.util.concurrent.atomic.AtomicInteger(0)
        val server = DoubanLanServer("127.0.0.1", { opened.incrementAndGet(); Unit }, { clock }, DoubanFeatureAccess("dev", true))
        server.start(2_000, true)
        try {
            val client = OkHttpClient()
            val url = server.url
            val origin = "http://${java.net.URI(url).authority}"
            val actualToken = java.net.URI(url).path.trim('/')
            fun code(originHeader: String, tokenHeader: String): Int = client.newCall(Request.Builder().url(url + "login")
                .header("Origin", originHeader).header("X-CinemaTV-Token", tokenHeader).post(ByteArray(0).toRequestBody()).build()).execute().use { it.code }
            assertEquals(403, code("https://evil.example", actualToken)); assertEquals(0, opened.get())
            assertEquals(403, code(origin, "wrong")); assertEquals(0, opened.get())
            assertEquals(200, code(origin, actualToken)); assertEquals(1, opened.get())
            assertEquals(409, code(origin, actualToken)); assertEquals(1, opened.get())
            clock += 600_001
            assertEquals(410, code(origin, actualToken)); assertEquals(1, opened.get())
        } finally { server.stop() }
    }
    @Test fun revokedLanTicketCannotOpenLoginAfterReenable() {
        val access = DoubanFeatureAccess("dev", true)
        var opened = 0
        val server = DoubanLanServer("127.0.0.1", { opened++ }, access = access)
        server.start(2_000, true)
        val url = java.net.URI(server.url)
        val session = syntheticLoginSession(url)
        access.revoke(); access.enable()
        try {
            assertEquals(410, server.serve(session).status.requestStatus)
            assertEquals(0, opened)
        } finally { server.stop() }
    }
    @Test fun revocationBetweenRequestCheckAndCallbackPreventsLogin() {
        val access = DoubanFeatureAccess("dev", true)
        var opened = 0; var revokeOnRequest = false
        val server = DoubanLanServer("127.0.0.1", { opened++ }, now = {
            if (revokeOnRequest) { access.revoke(); access.enable() }
            1_000L
        }, access = access)
        server.start(2_000, true)
        val session = syntheticLoginSession(java.net.URI(server.url))
        revokeOnRequest = true
        try {
            assertEquals(410, server.serve(session).status.requestStatus)
            assertEquals(0, opened)
        } finally { server.stop() }
    }
    private fun syntheticLoginSession(url: java.net.URI): fi.iki.elonen.NanoHTTPD.IHTTPSession {
        val headers = mapOf("host" to url.authority, "origin" to "http://${url.authority}",
            "x-cinematv-token" to url.path.trim('/'))
        return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(fi.iki.elonen.NanoHTTPD.IHTTPSession::class.java)) { _, method, _ ->
            when (method.name) {
                "getHeaders" -> headers
                "getUri" -> url.path + "login"
                "getMethod" -> fi.iki.elonen.NanoHTTPD.Method.POST
                else -> error("Unexpected synthetic session call: ${method.name}")
            }
        } as fi.iki.elonen.NanoHTTPD.IHTTPSession
    }
}
