package dev.cinematv.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import dev.cinematv.BuildConfig
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import dev.cinematv.ui.shared.deviceProfile
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * A process-singleton LRU cache. SimpleCache MUST NOT be opened twice for the same directory.
 * No offline downloads: the cache only retains bytes used during playback.
 * Live streams bypass it to avoid evicting useful VOD segments.
 */
@OptIn(UnstableApi::class)
object PlaybackFactory {
    @Volatile private var cache: SimpleCache? = null

    @Synchronized
    private fun getCache(context: Context): SimpleCache {
        cache?.let { return it }
        val app = context.applicationContext
        return SimpleCache(
            File(app.cacheDir, "media3-playback"),
            LeastRecentlyUsedCacheEvictor(deviceProfile(context).diskCacheBytes),
            StandaloneDatabaseProvider(app)
        ).also { cache = it }
    }

    fun create(context: Context, cacheVod: Boolean): ExoPlayer {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(deviceProfile(context).playbackBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val builder = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
        if (!cacheVod) return builder.build()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("CinemaTV/${BuildConfig.VERSION_NAME} (Android)")
        val source = CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSource = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(source)
        return builder.setMediaSourceFactory(mediaSource).build()
    }
}
