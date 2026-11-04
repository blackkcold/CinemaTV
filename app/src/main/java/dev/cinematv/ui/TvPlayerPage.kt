package dev.cinematv.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.cinematv.playback.PlaybackFactory
import dev.cinematv.Page
import dev.cinematv.CinemaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Media3 is the sole owner of the video Surface, audio tracks and stream lifecycle.
 * Compose draws only the independent TV overlay; it never blurs the video Surface.
 */
@Composable
fun TvPlayerPage(model: CinemaViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uri = model.playbackUrl
    val player = remember(uri) {
        PlaybackFactory.create(context, cacheVod = model.playerReturnPage == Page.DETAIL).apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            if (model.playbackPosition > 0) seekTo(model.playbackPosition)
        }
    }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    var error by remember(uri) { mutableStateOf("") }
    var playWhenReady by remember(uri) { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                error = exception.message ?: "视频解码或网络错误"
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) model.playerEnded()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playWhenReady = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            model.savePlaybackPosition(player.currentPosition.coerceAtLeast(0L))
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        var ticks = 0
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            if (++ticks % 5 == 0) model.savePlaybackPosition(position)
            delay(1000)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { it.player = player }
        )
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Color(0x660A0A0C)).padding(start = 28.dp, end = 28.dp, top = 17.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassAction("← 返回", Modifier.width(112.dp).height(44.dp)) { model.back() }
            Spacer(Modifier.width(22.dp))
            Text(model.playbackTitle, color = Ivory, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (duration > 0L) "${formatTime(position)} / ${formatTime(duration)}" else formatTime(position),
                color = Muted, fontSize = 15.sp
            )
        }
        if (error.isNotBlank()) {
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xE9292027), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(error, color = Ivory, fontSize = 18.sp)
                GlassAction("重试播放", Modifier.width(145.dp).height(47.dp)) {
                    error = ""
                    player.prepare()
                    player.play()
                }
                GlassAction("返回换源", Modifier.width(145.dp).height(47.dp)) { model.back() }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    return "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
}
