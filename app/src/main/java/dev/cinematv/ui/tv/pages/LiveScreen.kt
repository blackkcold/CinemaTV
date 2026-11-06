package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.cinematv.ui.typography.CinemaTvText as Text
import coil3.compose.AsyncImage
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.data.TvProgram
import dev.cinematv.data.ChannelIdentity
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveScreen(model: CinemaViewModel) {
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    val nav = LocalNavigationFocus.current
    var group by remember(model.activeLiveSource?.url) { mutableStateOf<String?>(null) }
    val channels = model.channels.filter { group == null || it.group == group }
    val list = rememberTvListState(model, "channels")
    val lifecycle = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(model, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                model.refreshEpgIfStale(now)
                kotlinx.coroutines.delay(30_000)
            }
        }
    }
    val programs = model.epgPrograms.asSequence().filter { it.start < it.stop && it.stop > now }
        .distinctBy { Triple(it.start, it.stop, it.title) }
        .sortedWith(compareBy<TvProgram> { it.start }.thenBy { it.stop }.thenBy { it.title })
        .take(48).toList()
    val current = programs.firstOrNull { now in it.start until it.stop }
    RestorePageFocus(model)
    Column(Modifier.fillMaxSize().padding(top = layout.contentTop)) {
        Row(Modifier.padding(horizontal = layout.gutter), verticalAlignment = Alignment.CenterVertically) {
            Text("此刻，正在发生。", color = TvColors.Text, fontSize = 32.sp)
            Spacer(Modifier.weight(1f))
            Text(clock(now), color = TvColors.Secondary, fontSize = 18.sp)
        }
        if (model.liveSources.isEmpty()) {
            Column(Modifier.padding(layout.gutter), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("添加直播订阅，开启频道与节目导览。", color = TvColors.Secondary, fontSize = 17.sp)
                GlassAction("配置直播", Modifier.focusRequester(entry).width(145.dp).height(45.dp)) { model.navigate(Page.SETTINGS) }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(model.liveSources, key = { it.url }) { source ->
                    GlassAction(source.name, Modifier.width(155.dp).height(35.dp)
                        .then(if (source == model.liveSources.first()) Modifier.focusRequester(entry) else Modifier).focusProperties { up = nav },
                        selected = model.activeLiveSource?.url == source.url) { model.loadChannels(source) }
                }
                item { GlassAction("全部频道", Modifier.width(98.dp).height(35.dp), selected = group == null) { group = null } }
                items(model.channels.map { it.group }.filter(String::isNotBlank).distinct(), key = { "group:$it" }) { value ->
                    GlassAction(value, Modifier.width(100.dp).height(35.dp), selected = group == value) { group = value }
                }
            }
            Row(Modifier.fillMaxSize().padding(start = layout.gutter, end = layout.gutter, bottom = 25.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LazyColumn(state = list, modifier = Modifier.weight(.36f).fillMaxHeight().focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                    if (channels.isEmpty()) item { Text(if (model.busy) "正在载入频道…" else "暂无可用频道", color = TvColors.Secondary) }
                    items(channels, key = { ChannelIdentity.key(model.activeLiveSource, it) }) { channel ->
                        val key = ChannelIdentity.key(model.activeLiveSource, channel)
                        val target = rememberFocusTarget(model, "channels", key)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            if (channel.logo.isNotBlank()) AsyncImage(channel.logo, null, modifier = Modifier.size(30.dp))
                            GlassAction(channel.name, Modifier.weight(1f).height(47.dp).focusRequester(target), selected = model.activeChannel?.let { ChannelIdentity.key(model.activeLiveSource, it) == key } == true,
                                onFocus = { model.focusCoordinator.remember(model.focusRoute, "channels", key); model.focusChannel(channel) }) { model.playChannel(channel) }
                        }
                    }
                }
                CinemaGlassSurface(Modifier.weight(.64f).fillMaxHeight(), radius = 22.dp) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(23.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Text(model.activeChannel?.name ?: "节目导览", color = TvColors.Text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            Text(if (model.activeChannel == null) "选择左侧频道查看节目。" else if (programs.isEmpty()) "当前频道暂无节目单。" else "当前节目与后续播出安排", color = TvColors.Secondary, fontSize = 13.sp)
                        }
                        if (current != null) item {
                            Text("正在播出", color = TvColors.Accent, fontSize = 12.sp)
                            Text(current.title, color = TvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                            Text("${clock(current.start)} — ${clock(current.stop)}", color = TvColors.Secondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp).background(TvColors.Text.copy(alpha = .15f), RoundedCornerShape(2.dp))) {
                                val progress = ((now - current.start).toFloat() / (current.stop - current.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(TvColors.Text, RoundedCornerShape(2.dp)))
                            }
                        }
                        items(programs, key = { "${it.start}:${it.stop}:${it.title}" }) { program ->
                            GuideProgram(program, program == current)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideProgram(program: TvProgram, current: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().trackTvContentFocus().onFocusChanged { focused = it.isFocused }.focusable()
        .background(if (focused) TvColors.Text.copy(alpha = .13f) else TvColors.Background.copy(alpha = .15f), RoundedCornerShape(10.dp))
        .border(1.dp, TvColors.Text.copy(alpha = if (focused) .6f else 0f), RoundedCornerShape(10.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Text(clock(program.start), color = TvColors.Secondary, fontSize = 13.sp)
        Text(program.title, color = if (current) TvColors.Accent else TvColors.Text, fontSize = 14.sp, maxLines = 2)
    }
}

private fun clock(time: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
