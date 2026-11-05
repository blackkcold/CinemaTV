package dev.cinematv.data.douban

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class DoubanLanServerTest {
    @Test fun lanControlRequiresOriginTokenAndCannotReplayOrOutliveExpiry() {
        var clock = 1_000L; val opened = java.util.concurrent.atomic.AtomicInteger(0)
        val server = DoubanLanServer("127.0.0.1", { opened.incrementAndGet(); Unit }, { clock })
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
}
