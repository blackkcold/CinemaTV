package dev.cinematv.ui

import dev.cinematv.ui.icons.CinemaSymbol
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.cinematv.ui.typography.CinemaTvText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.cinematv.playback.ObservePlaybackLifecycle
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import dev.cinematv.ui.tv.design.LocalTvLayout
import dev.cinematv.ui.tv.focus.trackTvContentFocus
import dev.cinematv.ui.shared.RemoteBackPress
import dev.cinematv.Page
import dev.cinematv.CinemaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class PlayerPanel { QUALITY, AUDIO, SUBTITLE, SPEED }
private data class TrackChoice(val title: String, val group: Tracks.Group, val trackIndex: Int)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    val session = model.playbackSession ?: return
    val uri = session.url
    val episodeAtCreation = session.episodeIndex
    val player = remember(session) {
        PlaybackFactory.create(context, cacheVod = session.item != null).apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            if (model.playbackPosition > 0) seekTo(model.playbackPosition)
        }
    }
    var position by remember(session) { mutableLongStateOf(0L) }
    var duration by remember(session) { mutableLongStateOf(0L) }
    var error by remember(session) { mutableStateOf("") }
    var isPlaying by remember(session) { mutableStateOf(true) }
    var controlsVisible by remember(session) { mutableStateOf(true) }
    var panel by remember(session) { mutableStateOf<PlayerPanel?>(null) }
    var tracksVersion by remember(session) { mutableIntStateOf(0) }
    var interaction by remember(session) { mutableIntStateOf(0) }
    val rootFocus = remember(session) { FocusRequester() }
    val playFocus = remember(session) { FocusRequester() }
    val panelFocus = remember(session) { FocusRequester() }
    val qualityFocus = remember(session) { FocusRequester() }
    val audioFocus = remember(session) { FocusRequester() }
    val subtitleFocus = remember(session) { FocusRequester() }
    val speedFocus = remember(session) { FocusRequester() }
    var returnFocus by remember(session) { mutableStateOf(playFocus) }
    val backPress = remember(session) { RemoteBackPress() }
    val layout = LocalTvLayout.current
    ObservePlaybackLifecycle(player)
    val handleBack: () -> Unit = {
        when {
            panel != null -> { panel = null; controlsVisible = true; interaction++ }
            error.isNotBlank() -> model.back()
            controlsVisible -> controlsVisible = false
            else -> model.back()
        }
    }
    BackHandler { backPress.reset(); handleBack() }
    val openPanel: (PlayerPanel, FocusRequester) -> Unit = { next, trigger ->
        controlsVisible = true
        returnFocus = trigger
        panel = next
        interaction++
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                error = exception.message ?: "视频解码或网络错误"
                model.recoverPlayback(error, player.currentPosition, session)
                controlsVisible = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) model.playerEnded(player.currentPosition, player.duration.coerceAtLeast(0L), episodeAtCreation, session)
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }
        }
        player.addListener(listener)
        onDispose {
            model.savePlaybackPosition(player.currentPosition.coerceAtLeast(0L), episodeAtCreation, player.duration.coerceAtLeast(0L), session)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        var ticks = 0
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            if (++ticks % 5 == 0) model.savePlaybackPosition(position, episodeAtCreation, player.duration.coerceAtLeast(0L), session)
            delay(1000)
        }
    }
    LaunchedEffect(session) { rootFocus.requestFocus() }
    LaunchedEffect(controlsVisible, panel, interaction, isPlaying) {
        if (controlsVisible && panel == null && isPlaying) {
            delay(6500)
            if (panel == null && isPlaying) controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible, panel) {
        when {
            panel != null -> panelFocus.requestFocus()
            !controlsVisible -> rootFocus.requestFocus()
            else -> runCatching { returnFocus.requestFocus() }
        }
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
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    val native = event.nativeKeyEvent
                    when (native.action) {
                        android.view.KeyEvent.ACTION_DOWN -> backPress.down(native.downTime, native.repeatCount)
                        android.view.KeyEvent.ACTION_UP -> if (backPress.up(native.downTime, native.isCanceled)) handleBack()
                    }
                    // Consume the entire remote press. Changing routes on key-up
                    // keeps its release out of the newly created browsing view.
                    true
                } else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    if (player.isPlaying) player.pause() else player.play()
                    controlsVisible = true; returnFocus = playFocus
                    interaction++; true
                } else if (event.type != KeyEventType.KeyDown || panel != null || controlsVisible) false
                else when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        controlsVisible = true; true
                    }
                    Key.DirectionLeft -> { seek(-10000L); true }
                    Key.DirectionRight -> { seek(10000L); true }
                    Key.DirectionDown, Key.DirectionUp -> { controlsVisible = true; true }
                    else -> false
                }
            }.trackTvContentFocus().focusable()
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
                GlassAction("← 返回", Modifier.width(115.dp).height(48.dp), icon = CinemaSymbol.Back, onFocus = { interaction++ }) {
                    model.back()
                }
                Spacer(Modifier.width(24.dp))
                Text(model.playbackTitle, color = Ivory, fontSize = 21.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                Text(
                    if (duration > 0L) "${formatTime(position)} / ${formatTime(duration)}"
                    else formatTime(position),
                    color = Muted, fontSize = 16.sp
                )
            }
            CinemaGlassSurface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = layout.gutter, vertical = 25.dp), radius = 22.dp
            ) {
                Column(Modifier.padding(horizontal = 21.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Box(Modifier.fillMaxWidth().height(10.dp)
                        .semantics { contentDescription = "播放进度"; stateDescription = "${formatTime(position)} / ${formatTime(duration)}" }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionLeft -> { seek(-10000); true }
                                Key.DirectionRight -> { seek(10000); true }
                                else -> false
                            }
                        }.trackTvContentFocus().focusable(), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .23f)))
                        if (duration > 0) Box(Modifier.fillMaxWidth((position.toDouble() / duration).toFloat().coerceIn(0f, 1f))
                            .height(4.dp).background(Ivory))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassAction("−10s", Modifier.width(62.dp).height(41.dp), onFocus = { interaction++ }) { seek(-10000) }
                        GlassAction(if (isPlaying) "暂停" else "播放", Modifier.focusRequester(playFocus).width(80.dp).height(41.dp),
                            prominent = true, icon = if (isPlaying) CinemaSymbol.Pause else CinemaSymbol.Play, onFocus = { interaction++ }) {
                            if (player.isPlaying) player.pause() else player.play()
                            interaction++
                        }
                        GlassAction("+10s", Modifier.width(62.dp).height(41.dp), onFocus = { interaction++ }) { seek(10000) }
                        Spacer(Modifier.weight(1f))
                        GlassAction("画质", Modifier.focusRequester(qualityFocus).width(60.dp).height(41.dp), onFocus = { interaction++ }) { openPanel(PlayerPanel.QUALITY, qualityFocus) }
                        GlassAction("音轨", Modifier.focusRequester(audioFocus).width(60.dp).height(41.dp), onFocus = { interaction++ }) { openPanel(PlayerPanel.AUDIO, audioFocus) }
                        GlassAction("字幕", Modifier.focusRequester(subtitleFocus).width(60.dp).height(41.dp), onFocus = { interaction++ }) { openPanel(PlayerPanel.SUBTITLE, subtitleFocus) }
                        GlassAction("倍速", Modifier.focusRequester(speedFocus).width(60.dp).height(41.dp), onFocus = { interaction++ }) { openPanel(PlayerPanel.SPEED, speedFocus) }
                        if (model.playerReturnPage == Page.DETAIL) GlassAction("选集 / 换源", Modifier.width(110.dp).height(41.dp), onFocus = { interaction++ }) { model.back() }
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
                    .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionLeft, Key.DirectionRight) }
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(when (active) {
                    PlayerPanel.QUALITY -> "画质"
                    PlayerPanel.AUDIO -> "音轨"
                    PlayerPanel.SUBTITLE -> "字幕"
                    PlayerPanel.SPEED -> "播放速度"
                }, color = Ivory, fontSize = 23.sp)
                GlassAction("← 返回播放",
                    Modifier.focusRequester(panelFocus).focusProperties { up = FocusRequester.Cancel }.fillMaxWidth().height(45.dp), icon = CinemaSymbol.Back) {
                    panel = null; interaction++
                }
                if (active == PlayerPanel.SPEED) {
                    listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEachIndexed { index, speed ->
                        GlassAction("${speed}×", Modifier.focusProperties { if (index == 5) down = FocusRequester.Cancel }.fillMaxWidth().height(43.dp),
                            selected = player.playbackParameters.speed == speed) {
                            player.setPlaybackSpeed(speed); panel = null
                        }
                    }
                } else {
                    GlassAction(if (active == PlayerPanel.SUBTITLE) "关闭字幕" else "自动选择",
                        Modifier.focusProperties { if (choices.isEmpty()) down = FocusRequester.Cancel }.fillMaxWidth().height(43.dp)) {
                        applyChoice(player, type, null); panel = null
                    }
                    if (choices.isEmpty()) Text("当前流没有可选轨道", color = Muted, fontSize = 15.sp)
                    choices.forEachIndexed { index, choice ->
                        GlassAction(choice.title, Modifier.focusProperties { if (index == choices.lastIndex) down = FocusRequester.Cancel }.fillMaxWidth().height(43.dp),
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
