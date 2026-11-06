package dev.cinematv.hybrid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceResponse
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Registry-backed, resized artwork only. Web callers cannot supply URLs, cookies, headers or filesystem paths. */
class HybridImages(private val context: Context, private val ids: HybridOpaqueIds) : AutoCloseable {
    private data class Artwork(val url: String, val width: Int, val height: Int, val expiresAt: Long)
    private val registered = LinkedHashMap<String, Artwork>(512, .75f, true)
    private val encoded = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val workers = Semaphore(2)
    private val sessions = Collections.newSetFromMap(ConcurrentHashMap<Session, Boolean>())

    @Synchronized
    fun register(url: String, landscape: Boolean = false, expiresAt: Long = 0): String {
        if (url.length !in 1..8192 || (expiresAt > 0 && expiresAt <= System.currentTimeMillis())) return ""
        val resource = url.startsWith("android.resource://")
        if (resource) {
            val uri = Uri.parse(url)
            if (uri.authority != context.packageName || uri.pathSegments.size != 1 || uri.lastPathSegment?.toIntOrNull() == null) return ""
        } else {
            val parsed = url.toHttpUrlOrNull() ?: return ""
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) return ""
        }
        val width = if (landscape) 1280 else 320
        val height = if (landscape) 720 else 480
        val key = ids.key("art", "$width:$height:$url")
        registered[key] = Artwork(url, width, height, expiresAt)
        while (registered.size > 512) {
            val oldest = registered.keys.first()
            registered.remove(oldest); encoded.remove(oldest)
        }
        return "${HybridPolicy.ORIGIN}/images/$key"
    }

    fun openSession(): Session = Session().also { sessions.add(it) }

    fun trim() { encoded.evictAll() }

    override fun close() {
        sessions.toList().forEach(Session::close)
        trim()
        synchronized(this) { registered.clear() }
        client.connectionPool.evictAll()
    }

    inner class Session : WebViewAssetLoader.PathHandler, AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val calls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

        override fun handle(path: String): WebResourceResponse {
            if (closed.get() || !path.matches(Regex("art_[0-9a-f]{32}"))) return denied()
            val art = synchronized(this@HybridImages) { registered[path] } ?: return denied()
            if (art.expiresAt > 0 && art.expiresAt <= System.currentTimeMillis()) { encoded.remove(path); return denied() }
            encoded.get(path)?.let { return response(it) }
            if (!workers.tryAcquire(12, TimeUnit.SECONDS)) return denied()
            try {
                if (closed.get()) return denied()
                encoded.get(path)?.let { return response(it) }
                val bitmap = if (art.url.startsWith("android.resource://")) resourceBitmap(art) else fetchBitmap(art)
                bitmap ?: return denied()
                val bytes = try {
                    ByteArrayOutputStream().use { stream ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)) return denied()
                        stream.toByteArray().takeIf { it.size <= 1536 * 1024 }
                    }
                } finally { bitmap.recycle() }
                if (closed.get() || bytes == null || (art.expiresAt > 0 && art.expiresAt <= System.currentTimeMillis())) return denied()
                encoded.put(path, bytes)
                return response(bytes)
            } catch (_: Exception) { return denied() }
            finally { workers.release() }
        }

        private fun fetchBitmap(art: Artwork): Bitmap? {
            val bytes = fetchBytes(art.url) ?: return null
            if (closed.get()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 16384 || bounds.outHeight > 16384 ||
                bounds.outWidth.toLong() * bounds.outHeight > 32_000_000L) return null
            var sample = 1
            while (bounds.outWidth / sample > art.width * 2 || bounds.outHeight / sample > art.height * 2) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: return null
            val scale = minOf(1.0, art.width.toDouble() / bitmap.width, art.height.toDouble() / bitmap.height)
            return if (scale >= 1.0) bitmap else Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }

        private fun fetchBytes(initial: String): ByteArray? {
            var address = initial.toHttpUrlOrNull() ?: return null
            val visited = mutableSetOf<HttpUrl>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            repeat(4) {
                if (closed.get() || !visited.add(address)) return null
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                val call = client.newCall(Request.Builder().url(address).header("Accept", "image/webp,image/jpeg,image/png,image/*").build())
                call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
                calls.add(call)
                var redirect: HttpUrl? = null
                val bytes = try {
                    call.execute().use { reply ->
                        if (reply.code in setOf(301, 302, 303, 307, 308)) {
                            val next = reply.header("Location")?.let(address::resolve) ?: return null
                            if (next.username.isNotEmpty() || next.password.isNotEmpty() || next.fragment != null ||
                                (address.isHttps && !next.isHttps)) return null
                            redirect = next
                            null
                        } else {
                            val body = reply.body ?: return null
                            if (!reply.isSuccessful || body.contentLength() > MAX_ENCODED_BYTES) return null
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(16 * 1024)
                                while (!closed.get()) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_ENCODED_BYTES) return null
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                    }
                } finally { calls.remove(call) }
                if (redirect == null) return bytes
                address = redirect!!
            }
            return null
        }

        private fun resourceBitmap(art: Artwork): Bitmap? {
            val id = Uri.parse(art.url).lastPathSegment?.toIntOrNull() ?: return null
            if (context.resources.getResourceTypeName(id) !in setOf("drawable", "mipmap")) return null
            val drawable = ContextCompat.getDrawable(context, id) ?: return null
            val originalWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: art.width
            val originalHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: art.height
            val ratio = minOf(1.0, art.width.toDouble() / originalWidth, art.height.toDouble() / originalHeight)
            return Bitmap.createBitmap((originalWidth * ratio).toInt().coerceAtLeast(1),
                (originalHeight * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, it.width, it.height); drawable.draw(Canvas(it))
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            calls.toList().forEach(Call::cancel)
            calls.clear(); sessions.remove(this)
        }
    }

    companion object {
        private const val MAX_ENCODED_BYTES = 4 * 1024 * 1024
        private fun response(bytes: ByteArray) = WebResourceResponse("image/jpeg", null, 200, "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
        fun denied() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }
}
