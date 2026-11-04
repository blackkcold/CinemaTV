package dev.cinematv.ui.mobile

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import androidx.media3.ui.PlayerView
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.playback.PlaybackFactory
import dev.cinematv.ui.Ink
import dev.cinematv.ui.Ivory
import dev.cinematv.ui.Muted
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Touch-first playback. Media3 owns video/track selection and controller UI;
 * shared playback lifecycle and resume policy remain consistent with TV.
 */
@Composable
fun MobilePlayerPage(model: CinemaViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uri = model.playbackUrl
    val episodeAtCreation = remember(uri) { model.playingEpisode }
    val player = remember(uri) {
        PlaybackFactory.create(context, cacheVod = model.playerReturnPage == Page.DETAIL).apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            if (model.playbackPosition > 0L) seekTo(model.playbackPosition)
        }
    }
    var error by remember(uri) { mutableStateOf("") }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var playing by remember(uri) { mutableStateOf(true) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                error = exception.message ?: "播放失败"
                model.recoverPlayback(error, player.currentPosition)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) model.playerEnded()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose {
            if (model.playbackUrl == uri) {
                model.savePlaybackPosition(player.currentPosition.coerceAtLeast(0L), episodeAtCreation)
            }
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        var seconds = 0
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            if (++seconds % 5 == 0) model.savePlaybackPosition(position, episodeAtCreation)
            delay(1000)
        }
    }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { model.back() }) { Text("← 返回") }
            Text(model.playbackTitle, color = Ivory, fontSize = 18.sp,
                modifier = Modifier.weight(1f), maxLines = 2)
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    isFocusable = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .background(Color.Black, RoundedCornerShape(12.dp)),
            update = { it.player = player }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                if (player.isCurrentMediaItemSeekable) player.seekTo((player.currentPosition - 10000L).coerceAtLeast(0L))
            }) { Text("−10秒") }
            Button(onClick = {
                if (player.isPlaying) player.pause() else player.play()
            }) { Text(if (playing) "暂停" else "播放") }
            OutlinedButton(onClick = {
                if (player.isCurrentMediaItemSeekable) player.seekTo(player.currentPosition + 10000L)
            }) { Text("+10秒") }
        }
        Text(
            "播放进度：" + (position / 1000L) + " 秒 · Media3 触控控制器",
            color = Muted, fontSize = 13.sp
        )
        if (model.playerReturnPage == Page.DETAIL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.back() }) { Text("返回选集 / 换源") }
                val episodes = model.selected?.episodes.orEmpty()
                if (episodeAtCreation + 1 < episodes.size) {
                    Button(onClick = { model.playEpisode(episodeAtCreation + 1) }) { Text("下一集") }
                }
            }
        }
        if (error.isNotBlank() || model.failoverBusy) {
            Surface(color = Color(0xFF382934), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.failoverMessage.ifBlank { error }, color = Ivory, fontSize = 14.sp)
                    if (!model.failoverBusy) {
                        Button(onClick = {
                            error = ""; player.prepare(); player.play()
                        }) { Text("重试") }
                    }
                }
            }
        }
    }
}
