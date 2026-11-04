package dev.cinematv.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.cinematv.playback.PlaybackFactory
import dev.cinematv.Page
import dev.cinematv.CinemaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class PlayerPanel { QUALITY, AUDIO, SUBTITLE, SPEED }
private data class TrackChoice(val title: String, val group: Tracks.Group, val trackIndex: Int)

private fun trackChoices(player: Player, type: Int): List<TrackChoice> =
    player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
            val format = group.getTrackFormat(index)
            val text = when (type) {
                C.TRACK_TYPE_VIDEO -> when {
                    format.height > 0 -> "${format.height}p"
                    format.bitrate > 0 -> "${format.bitrate / 1000} kb/s"
                    else -> "视频轨道 ${index + 1}"
                }
                C.TRACK_TYPE_AUDIO -> listOfNotNull(
                    format.label?.takeIf(String::isNotBlank),
                    format.language?.takeIf { it.isNotBlank() && it != "und" },
                    format.channelCount.takeIf { it > 0 }?.let { "${it}ch" }
                ).joinToString(" · ").ifBlank { "音轨 ${index + 1}" }
                else -> listOfNotNull(
                    format.label?.takeIf(String::isNotBlank),
                    format.language?.takeIf { it.isNotBlank() && it != "und" }
                ).joinToString(" · ").ifBlank { "字幕 ${index + 1}" }
            }
            TrackChoice(text, group, index)
        }
    }

private fun applyChoice(player: Player, type: Int, choice: TrackChoice?) {
    val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && choice == null)
    if (choice != null) {
        builder.setOverrideForType(
            TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.trackIndex))
        )
    }
    player.trackSelectionParameters = builder.build()
}

/**
 * Compose is the sole owner of the remote-driven playback controls. PlayerView
 * provides the video surface, but its touch-oriented controller stays disabled.
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
    var isPlaying by remember(uri) { mutableStateOf(true) }
    var controlsVisible by remember(uri) { mutableStateOf(true) }
    var panel by remember(uri) { mutableStateOf<PlayerPanel?>(null) }
    var tracksVersion by remember(uri) { mutableIntStateOf(0) }
    var interaction by remember(uri) { mutableIntStateOf(0) }
    val rootFocus = remember(uri) { FocusRequester() }
    val playFocus = remember(uri) { FocusRequester() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                error = exception.message ?: "视频解码或网络错误"
                model.recoverPlayback(error, player.currentPosition)
                controlsVisible = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) model.playerEnded()
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }
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
    LaunchedEffect(uri) { rootFocus.requestFocus() }
    LaunchedEffect(controlsVisible, panel, interaction, isPlaying) {
        if (controlsVisible && panel == null && isPlaying) {
            delay(6500)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible, panel) {
        if (controlsVisible && panel == null) playFocus.requestFocus()
    }

    val seek: (Long) -> Unit = { delta ->
        if (player.isCurrentMediaItemSeekable) {
            val next = (player.currentPosition + delta).coerceAtLeast(0L)
            player.seekTo(if (duration > 0L) next.coerceAtMost(duration) else next)
        }
        controlsVisible = true
        interaction++
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || panel != null || controlsVisible) false
                else when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        controlsVisible = true; true
                    }
                    Key.DirectionLeft -> { seek(-10000L); true }
                    Key.DirectionRight -> { seek(10000L); true }
                    Key.DirectionDown, Key.DirectionUp -> { controlsVisible = true; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { it.player = player }
        )
        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color(0xA8000000), Color.Transparent, Color.Transparent, Color(0xE6000000))
            )))
            Row(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .padding(start = 42.dp, end = 42.dp, top = 27.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassAction("← 返回", Modifier.width(115.dp).height(48.dp), onFocus = { interaction++ }) {
                    model.back()
                }
                Spacer(Modifier.width(24.dp))
                Text(model.playbackTitle, color = Ivory, fontSize = 21.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (duration > 0L) "${formatTime(position)} / ${formatTime(duration)}"
                    else formatTime(position),
                    color = Muted, fontSize = 16.sp
                )
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().padding(horizontal = 52.dp, vertical = 34.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = .26f))) {
                    if (duration > 0L) Box(
                        Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                            .fillMaxHeight().background(Ivory)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassAction("−10s", Modifier.width(92.dp).height(48.dp), onFocus = { interaction++ }) { seek(-10000L) }
                    GlassAction(
                        if (isPlaying) "暂停" else "播放",
                        Modifier.focusRequester(playFocus).width(100.dp).height(48.dp),
                        prominent = true, onFocus = { interaction++ }
                    ) {
                        if (player.isPlaying) player.pause() else player.play()
                        interaction++
                    }
                    GlassAction("+10s", Modifier.width(92.dp).height(48.dp), onFocus = { interaction++ }) { seek(10000L) }
                    Spacer(Modifier.weight(1f))
                    GlassAction("画质", Modifier.width(92.dp).height(48.dp), onFocus = { interaction++ }) { panel = PlayerPanel.QUALITY }
                    GlassAction("音轨", Modifier.width(92.dp).height(48.dp), onFocus = { interaction++ }) { panel = PlayerPanel.AUDIO }
                    GlassAction("字幕", Modifier.width(92.dp).height(48.dp), onFocus = { interaction++ }) { panel = PlayerPanel.SUBTITLE }
                    GlassAction("倍速", Modifier.width(92.dp).height(48.dp), onFocus = { interaction++ }) { panel = PlayerPanel.SPEED }
                    if (model.playerReturnPage == Page.DETAIL) {
                        GlassAction("选集/换源", Modifier.width(126.dp).height(48.dp), onFocus = { interaction++ }) {
                            model.back()
                        }
                    }
                }
            }
        }
        if (panel != null) {
            val active = panel!!
            val type = when (active) {
                PlayerPanel.QUALITY -> C.TRACK_TYPE_VIDEO
                PlayerPanel.AUDIO -> C.TRACK_TYPE_AUDIO
                PlayerPanel.SUBTITLE -> C.TRACK_TYPE_TEXT
                PlayerPanel.SPEED -> C.TRACK_TYPE_UNKNOWN
            }
            val choices = remember(panel, tracksVersion) {
                if (active == PlayerPanel.SPEED) emptyList() else trackChoices(player, type)
            }
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)
                    .width(355.dp).heightIn(max = 470.dp)
                    .background(Color(0xF02A2931), androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(when (active) {
                    PlayerPanel.QUALITY -> "画质"
                    PlayerPanel.AUDIO -> "音轨"
                    PlayerPanel.SUBTITLE -> "字幕"
                    PlayerPanel.SPEED -> "播放速度"
                }, color = Ivory, fontSize = 23.sp)
                GlassAction("← 返回播放", Modifier.fillMaxWidth().height(45.dp)) { panel = null; interaction++ }
                if (active == PlayerPanel.SPEED) {
                    listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { speed ->
                        GlassAction("${speed}×", Modifier.fillMaxWidth().height(43.dp),
                            selected = player.playbackParameters.speed == speed) {
                            player.setPlaybackSpeed(speed); panel = null
                        }
                    }
                } else {
                    GlassAction(if (active == PlayerPanel.SUBTITLE) "关闭字幕" else "自动选择",
                        Modifier.fillMaxWidth().height(43.dp)) {
                        applyChoice(player, type, null); panel = null
                    }
                    if (choices.isEmpty()) Text("当前流没有可选轨道", color = Muted, fontSize = 15.sp)
                    choices.forEach { choice ->
                        GlassAction(choice.title, Modifier.fillMaxWidth().height(43.dp),
                            selected = choice.group.isTrackSelected(choice.trackIndex)) {
                            applyChoice(player, type, choice); panel = null
                        }
                    }
                }
            }
        }
        if (error.isNotBlank() || model.failoverBusy) {
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xF0292027), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .padding(25.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Text(model.failoverMessage.ifBlank { error }, color = Ivory, fontSize = 18.sp)
                if (!model.failoverBusy) GlassAction("重试播放", Modifier.width(145.dp).height(47.dp)) {
                    error = ""; player.prepare(); player.play()
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
