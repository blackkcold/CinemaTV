package dev.cinematv.data.update

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CancellableUpdateHttpTest {
    @Test fun cancellationClosesStalledHeadersWithoutWaitingForTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(server.url("/headers")).build())
            val task = launch(Dispatchers.IO) { consumeUpdateResponse(call) { it.body!!.string() } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(3_000) { task.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test fun cancellationClosesStalledBodyAndAllowsCleanRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("first-and-then-stalled").throttleBody(1, 2, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody("fresh"))
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            val call = client.newCall(Request.Builder().url(server.url("/body")).build())
            val started = CompletableDeferred<Unit>()
            val task = launch(Dispatchers.IO) {
                consumeUpdateResponse(call) { response ->
                    response.body!!.byteStream().use { input ->
                        assertTrue(input.read() >= 0)
                        started.complete(Unit)
                        input.readBytes()
                    }
                }
            }
            withTimeout(3_000) { started.await(); task.cancelAndJoin() }
            assertTrue(call.isCanceled())
            val retried = withContext(Dispatchers.IO) {
                consumeUpdateResponse(client.newCall(Request.Builder().url(server.url("/retry")).build())) { it.body!!.string() }
            }
            assertEquals("fresh", retried)
        }
    }
}
