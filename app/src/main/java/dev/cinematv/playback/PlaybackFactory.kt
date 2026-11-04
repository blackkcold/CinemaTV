package dev.cinematv.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * A process-singleton LRU cache. SimpleCache MUST NOT be opened twice for the same directory.
 * No offline downloads: the cache only retains bytes used during playback.
 * Live streams bypass it to avoid evicting useful VOD segments.
 */
@OptIn(UnstableApi::class)
object PlaybackFactory {
    private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null

    @Synchronized
    private fun getCache(context: Context): SimpleCache {
        cache?.let { return it }
        val app = context.applicationContext
        return SimpleCache(
            File(app.cacheDir, "media3-playback"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(app)
        ).also { cache = it }
    }

    fun create(context: Context, cacheVod: Boolean): ExoPlayer {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (!cacheVod) return ExoPlayer.Builder(context, renderers).build()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("CinemaTV/0.1 (Android TV)")
        val source = CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSource = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(source)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(mediaSource)
            .build()
    }
}
