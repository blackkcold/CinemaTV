package dev.cinematv.ui.mobile

import android.view.ViewGroup
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.cinematv.ui.icons.CinemaSymbol
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.playback.PlaybackFactory
import dev.cinematv.playback.ObservePlaybackLifecycle
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
    val session = model.playbackSession ?: return
    val uri = session.url
    val episodeAtCreation = session.episodeIndex
    val player = remember(session) {
        PlaybackFactory.create(context, cacheVod = session.item != null).apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            if (model.playbackPosition > 0L) seekTo(model.playbackPosition)
        }
    }
    ObservePlaybackLifecycle(player)
    var error by remember(session) { mutableStateOf("") }
    var position by remember(session) { mutableLongStateOf(0L) }
    var playing by remember(session) { mutableStateOf(true) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                error = exception.message ?: "播放失败"
                model.recoverPlayback(error, player.currentPosition, session)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) model.playerEnded(player.currentPosition, player.duration.coerceAtLeast(0L), episodeAtCreation, session)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose {
            model.savePlaybackPosition(player.currentPosition.coerceAtLeast(0L), episodeAtCreation, player.duration.coerceAtLeast(0L), session)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        var seconds = 0
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            if (++seconds % 5 == 0) model.savePlaybackPosition(position, episodeAtCreation, player.duration.coerceAtLeast(0L), session)
            delay(1000)
        }
    }
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            MobileVideoSurface(player, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent)))
                .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.back() }) { MobileIconLabel(CinemaSymbol.Back, "← 返回") }
                Text(model.playbackTitle, color = Ivory, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (model.playerReturnPage == Page.DETAIL) TextButton(onClick = { model.back() }) { Text("选集", color = Ivory) }
            }
            if (error.isNotBlank() || model.failoverBusy) Surface(
                color = Ink.copy(alpha = .95f), shape = RoundedCornerShape(15.dp),
                modifier = Modifier.align(Alignment.Center).padding(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(model.failoverMessage.ifBlank { error }, color = Ivory)
                    if (!model.failoverBusy) MobileGlassButton(onClick = { error = ""; player.prepare(); player.play() }) { Text("重试") }
                }
            }
        }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Ink).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { model.back() }) { MobileIconLabel(CinemaSymbol.Back, "← 返回") }
            Text(model.playbackTitle, color = Ivory, fontSize = 18.sp,
                modifier = Modifier.weight(1f), maxLines = 2)
        }
        MobileVideoSurface(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .background(Color.Black, RoundedCornerShape(12.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MobileGlassOutlinedButton(onClick = {
                if (player.isCurrentMediaItemSeekable) player.seekTo((player.currentPosition - 10000L).coerceAtLeast(0L))
            }) { Text("−10秒") }
            MobileGlassButton(onClick = {
                if (player.isPlaying) player.pause() else player.play()
            }) { MobileIconLabel(if (playing) CinemaSymbol.Pause else CinemaSymbol.Play, if (playing) "暂停" else "播放") }
            MobileGlassOutlinedButton(onClick = {
                if (player.isCurrentMediaItemSeekable) player.seekTo(player.currentPosition + 10000L)
            }) { Text("+10秒") }
        }
        Text(
            "播放进度：" + (position / 1000L) + " 秒",
            color = Muted, fontSize = 13.sp
        )
        if (model.playerReturnPage == Page.DETAIL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobileGlassOutlinedButton(onClick = { model.back() }) { Text("返回选集 / 换源") }
                val episodes = model.selected?.episodes.orEmpty()
                if (episodeAtCreation + 1 < episodes.size) {
                    MobileGlassButton(onClick = { model.playEpisode(episodeAtCreation + 1) }) { Text("下一集") }
                }
            }
        }
        if (error.isNotBlank() || model.failoverBusy) {
            Surface(color = Color(0xFF382934), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.failoverMessage.ifBlank { error }, color = Ivory, fontSize = 14.sp)
                    if (!model.failoverBusy) {
                        MobileGlassButton(onClick = {
                            error = ""; player.prepare(); player.play()
                        }) { Text("重试") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileVideoSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context -> PlayerView(context).apply {
            this.player = player
            useController = true
            keepScreenOn = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        } },
        modifier = modifier.semantics { contentDescription = "视频画面" },
        onRelease = { it.player = null },
        update = { it.player = player }
    )
}
