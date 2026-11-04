package dev.cinematv.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.data.MediaItem
import dev.cinematv.data.LibraryStatus

private val margin = 66.dp

@Composable
fun TopNavigation(model: CinemaViewModel, modifier: Modifier = Modifier) {
    val entries = listOf(
        "精选" to Page.HOME, "探索" to Page.EXPLORE,
        "电影" to Page.MOVIES, "剧集" to Page.SERIES,
        "搜索" to Page.SEARCH, "直播" to Page.LIVE,
        "片库" to Page.LIBRARY, "设置" to Page.SETTINGS
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(35.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE6414149), Color(0xD923242B))))
            .border(1.dp, Color.White.copy(alpha = .23f), RoundedCornerShape(35.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        entries.forEach { (text, page) ->
            GlassAction(text, Modifier.width(88.dp).height(43.dp), selected = model.section == page) {
                model.navigate(page)
            }
        }
    }
}

@Composable
fun HomePage(model: CinemaViewModel) {
    val hero = model.focused ?: model.featured.firstOrNull()
    Box(Modifier.fillMaxSize().background(Ink)) {
        HeroBackground(hero)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(145.dp))
            Column(
                Modifier.fillMaxWidth().height(315.dp).padding(horizontal = margin),
                verticalArrangement = Arrangement.Center
            ) {
                Text("CINEMA TV", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 5.sp)
                Spacer(Modifier.height(16.dp))
                Text(hero?.title ?: "Your cinema, on your TV", color = Ivory, fontSize = 48.sp,
                    lineHeight = 54.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.fillMaxWidth(.58f))
                Spacer(Modifier.height(12.dp))
                Text(
                    if (hero == null) "连接 LibreTV，在大屏开启沉浸式观影体验。" else
                        listOf(hero.year, hero.category, hero.rating.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: "")
                            .filter(String::isNotBlank).joinToString("  ·  ").ifBlank { "精选推荐" },
                    color = Ivory.copy(alpha = .82f), fontSize = 17.sp
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassAction("观看详情", Modifier.width(160.dp).height(48.dp), prominent = true) {
                        if (hero != null) model.open(hero) else model.navigate(Page.SETTINGS)
                    }
                    GlassAction("搜索影片", Modifier.width(144.dp).height(48.dp)) { model.navigate(Page.SEARCH) }
                }
            }
            MediaRail("继续观看", model.history(), model)
            MediaRail("精选电影", model.featured, model)
            MediaRail("热门剧集", model.tvShows, model)
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun HeroBackground(item: MediaItem?) = CinemaBackdrop(item)

@Composable
fun MediaRail(title: String, items: List<MediaItem>, model: CinemaViewModel) {
    if (items.isEmpty()) return
    val region = "rail:$title"
    val listState = rememberLazyListState()
    val restore = remember(model.page, title) { model.focusCoordinator.restore(model.page.name, region) }
    LaunchedEffect(restore) {
        val index = items.indexOfFirst { it.id + ":" + (it.source?.key ?: "") == restore }
        if (index >= 0) listState.scrollToItem(index)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(title, color = Ivory, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = margin, bottom = 20.dp))
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = margin),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items, key = { it.id + (it.source?.key ?: "") }) { item ->
                val key = item.id + ":" + (item.source?.key ?: "")
                val focusRequester = remember(key) { FocusRequester() }
                if (key == restore) LaunchedEffect(key) { focusRequester.requestFocus() }
                MediaTile(item, onFocus = { model.onMediaFocus(item, region) },
                    onClick = { model.open(item) },
                    modifier = Modifier.focusRequester(focusRequester).width(255.dp))
            }
        }
    }
}

@Composable
fun ExplorePage(model: CinemaViewModel) {
    val entries = (model.featured + model.tvShows).distinctBy { it.title + it.year }
    var activeIndex by remember { mutableIntStateOf(
        entries.indexOfFirst { item ->
            item.id + ":" + (item.source?.key ?: "") ==
                model.focusCoordinator.restore(Page.EXPLORE.name, "coverflow")
        }.coerceAtLeast(0)
    ) }
    val railState = rememberLazyListState()
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(Color(0xFF54232B), Color(0xFF1B1018), Ink),
                radius = 1150f
            )
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 126.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PageHeading("探索电影世界", "左右移动，透过海报发现值得观看的故事")
            Spacer(Modifier.height(34.dp))
            if (entries.isEmpty()) {
                EmptyMessage("暂无推荐内容，请检查服务器连接", Modifier.fillMaxWidth().height(350.dp))
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val cardWidth = 232.dp
                    val sideInset = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
                    LaunchedEffect(activeIndex) {
                        railState.animateScrollToItem(activeIndex)
                    }
                    LazyRow(
                        state = railState,
                        contentPadding = PaddingValues(horizontal = sideInset),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(410.dp)
                    ) {
                        itemsIndexed(entries, key = { _, item -> item.id + item.title }) { index, item ->
                            val center = index == activeIndex
                            val fade = when (kotlin.math.abs(index - activeIndex)) {
                                0 -> 1f
                                1 -> .74f
                                else -> .50f
                            }
                            Box(
                                Modifier.width(cardWidth).graphicsLayer {
                                    scaleX = if (center) 1.13f else .87f
                                    scaleY = if (center) 1.13f else .87f
                                    alpha = fade
                                    rotationY = when {
                                        index < activeIndex -> 19f
                                        index > activeIndex -> -19f
                                        else -> 0f
                                    }
                                    translationY = if (center) -12.dp.toPx() else 15.dp.toPx()
                                    cameraDistance = 18f * density
                                    shadowElevation = if (center) 22.dp.toPx() else 0f
                                }
                            ) {
                                MediaTile(
                                    item = item,
                                    portrait = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onFocus = {
                                        activeIndex = index
                                        model.onMediaFocus(item, "coverflow")
                                    },
                                    onClick = { model.open(item) }
                                )
                            }
                        }
                    }
                }
                val current = entries.getOrNull(activeIndex)
                Text(
                    current?.title ?: "", color = Ivory, fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    listOf(current?.year.orEmpty(), current?.rating?.takeIf(String::isNotBlank)?.let { "★ $it" } ?: "")
                        .filter(String::isNotBlank).joinToString("  ·  "),
                    color = Muted, fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 48.dp)
                )
            }
        }
    }
}

@Composable
fun CatalogPage(model: CinemaViewModel, isTv: Boolean) {
    val items = if (isTv) model.tvShows else model.featured
    val region = if (isTv) "series-grid" else "movie-grid"
    val restore = remember(model.page) { model.focusCoordinator.restore(model.page.name, region) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(restore) {
        val index = items.indexOfFirst { it.id + ":" + (it.source?.key ?: "") == restore }
        if (index >= 0) gridState.scrollToItem(index)
    }
    Column(Modifier.fillMaxSize().padding(top = 116.dp, start = margin, end = margin)) {
        PageHeading(if (isTv) "剧集" else "电影", "探索 LibreTV 推荐内容；点击作品进入片源搜索与详情")
        Spacer(Modifier.height(28.dp))
        if (items.isEmpty()) EmptyMessage("暂无内容，请检查服务器连接", Modifier.fillMaxSize())
        else LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            contentPadding = PaddingValues(bottom = 50.dp)
        ) {
            items(items, key = { it.id }) { item ->
                val key = item.id + ":" + (item.source?.key ?: "")
                val requester = remember(key) { FocusRequester() }
                if (key == restore) LaunchedEffect(key) { requester.requestFocus() }
                MediaTile(item, onFocus = { model.onMediaFocus(item, region) },
                    onClick = { model.open(item) }, modifier = Modifier.focusRequester(requester))
            }
        }
    }
}

@Composable
fun SearchPage(model: CinemaViewModel) {
    Column(Modifier.fillMaxSize().padding(top = 123.dp, start = margin, end = margin)) {
        PageHeading("全局搜索", "在所有已配置的 LibreTV 点播源中搜索")
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(15.dp), verticalAlignment = Alignment.CenterVertically) {
            TvInput(
                value = model.query, onValueChange = { model.query = it },
                hint = "输入电影、剧集、演员或关键词",
                modifier = Modifier.weight(1f).height(60.dp),
                onSubmit = model::search
            )
            GlassAction("搜索", Modifier.width(150.dp).height(58.dp), prominent = true, onClick = model::search)
        }
        Spacer(Modifier.height(22.dp))
        if (model.busy) CircularProgressIndicator(color = Accent)
        else if (model.results.isEmpty()) EmptyMessage("输入关键词开始搜索", Modifier.fillMaxWidth().height(200.dp))
        else LazyVerticalGrid(
            columns = GridCells.Fixed(5), verticalArrangement = Arrangement.spacedBy(22.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 55.dp)
        ) {
            items(model.results, key = { it.source?.key.orEmpty() + it.id }) { item ->
                MediaTile(item, onFocus = { model.onMediaFocus(item) }, onClick = { model.open(item) })
            }
        }
    }
}

@Composable
fun DetailPage(model: CinemaViewModel) {
    val item = model.selected ?: return
    var showShelfPicker by remember(item.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        HeroBackground(item)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = margin, end = margin, top = 90.dp)) {
            GlassAction("← 返回", Modifier.width(130.dp).height(43.dp)) { model.back() }
            Spacer(Modifier.height(42.dp))
            Text(item.title, fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, color = Ivory,
                modifier = Modifier.fillMaxWidth(.72f))
            Spacer(Modifier.height(12.dp))
            Text(listOf(item.year, item.category, item.rating.takeIf(String::isNotBlank)?.let { "★ $it" } ?: "")
                .filter(String::isNotBlank).joinToString("  ·  ").ifBlank { "作品详情" }, color = Accent, fontSize = 17.sp)
            Spacer(Modifier.height(18.dp))
            Text(item.description.ifBlank { if (model.busy) "正在从片源读取详情..." else "暂无简介" },
                color = Ivory.copy(alpha = .82f), fontSize = 17.sp, lineHeight = 26.sp,
                maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.7f))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                GlassAction("▶ 立即播放", Modifier.width(168.dp).height(52.dp), prominent = true) { model.playEpisode(item.lastEpisode.coerceIn(0, (item.episodes.size - 1).coerceAtLeast(0))) }
                GlassAction("加入 / 取消收藏", Modifier.width(196.dp).height(52.dp)) { model.toggleFavorite() }
                GlassAction("更换片源", Modifier.width(165.dp).height(52.dp)) { model.findAlternatives() }
                GlassAction("片库状态", Modifier.width(145.dp).height(52.dp)) {
                    showShelfPicker = !showShelfPicker
                }
            }
            if (showShelfPicker) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    LibraryStatus.entries.forEach { status ->
                        GlassAction(status.title, Modifier.width(110.dp).height(44.dp)) {
                            model.setLibraryStatus(status); showShelfPicker = false
                        }
                    }
                }
            }
            if (model.alternatives.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("可用来源", color = Ivory, fontSize = 20.sp)
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(model.alternatives) { option ->
                        GlassAction(option.source?.name ?: "片源", Modifier.width(170.dp).height(48.dp)) {
                            model.open(option)
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            Text("剧集 / 播放列表  ·  ${item.episodes.size}", color = Ivory, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (item.episodes.isEmpty()) Text(
                if (model.busy) "正在获取剧集..." else "当前片源没有有效的播放地址",
                color = Muted, fontSize = 16.sp
            )
            else item.episodes.chunked(6).forEachIndexed { block, entries ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    entries.forEachIndexed { i, _ ->
                        GlassAction("第${block * 6 + i + 1}集", Modifier.width(120.dp).height(48.dp)) {
                            model.playEpisode(block * 6 + i)
                        }
                    }
                }
            }
            Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
fun LivePage(model: CinemaViewModel) {
    val active = model.activeChannel
    val now = System.currentTimeMillis()
    val programs = model.epgPrograms.sortedBy { it.start }
    val current = programs.firstOrNull { now in it.start until it.stop }
    val next = programs.firstOrNull { it.start > now }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(top = 112.dp, start = margin, end = margin)) {
        PageHeading("直播电视", "频道与 24 小时 XMLTV 节目导览")
        Spacer(Modifier.height(18.dp))
        if (model.liveSources.isEmpty()) {
            EmptyMessage("尚未配置直播订阅。请在设置中添加 M3U / XMLTV。",
                Modifier.fillMaxWidth().height(130.dp))
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(model.liveSources, key = { it.url }) { source ->
                GlassAction(source.name, Modifier.width(185.dp).height(46.dp),
                    selected = model.activeLiveSource?.url == source.url) {
                    model.loadChannels(source)
                }
            }
        }
        Spacer(Modifier.height(21.dp))
        if (model.busy) {
            CircularProgressIndicator(color = Accent)
        } else if (model.channels.isEmpty()) {
            EmptyMessage("直播订阅没有返回可用频道", Modifier.fillMaxWidth().height(145.dp))
        } else Row(
            Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(Modifier.weight(.49f)) {
                Text("频道列表 · ${model.channels.size}", color = Ivory, fontSize = 20.sp)
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 52.dp)
                ) {
                    items(model.channels, key = { it.id }) { channel ->
                        val focused = active?.id == channel.id
                        Row(Modifier.fillMaxWidth().height(65.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (channel.logo.isNotBlank()) {
                                AsyncImage(
                                    model = channel.logo, contentDescription = null,
                                    modifier = Modifier.width(64.dp).height(46.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            GlassAction(channel.name,
                                Modifier.weight(1f).height(54.dp),
                                selected = focused,
                                onFocus = { model.focusChannel(channel) }
                            ) { model.playChannel(channel) }
                        }
                    }
                }
            }
            Column(
                Modifier.weight(.51f).fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xBB22232B))
                    .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(24.dp))
                    .padding(25.dp)
            ) {
                Text(active?.name ?: "节目单", color = Ivory, fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (active == null) "使用遥控器选择左侧频道"
                    else if (programs.isEmpty()) "当前频道暂无可用 XMLTV 节目单"
                    else "当前节目与后续播出安排",
                    color = Muted, fontSize = 15.sp
                )
                if (current != null) {
                    Spacer(Modifier.height(18.dp))
                    Text("正在播放", color = Accent, fontSize = 15.sp)
                    Text(current.title, color = Ivory, fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Spacer(Modifier.height(9.dp))
                    Text("${epgTime(current.start)} — ${epgTime(current.stop)}",
                        color = Muted, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp)
                        .background(Color.White.copy(alpha = .2f), RoundedCornerShape(3.dp))) {
                        val progress = ((now - current.start).toFloat() /
                            (current.stop - current.start).coerceAtLeast(1L)).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent))
                    }
                }
                if (next != null) {
                    Spacer(Modifier.height(22.dp))
                    Text("即将播出", color = Accent, fontSize = 15.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("${epgTime(next.start)}  ${next.title}", color = Ivory,
                        fontSize = 17.sp, maxLines = 2)
                }
                Spacer(Modifier.height(22.dp))
                Text("后续节目", color = Ivory, fontSize = 19.sp)
                Spacer(Modifier.height(9.dp))
                val future = programs.filter { it.start > now }.take(36)
                if (future.isEmpty()) {
                    Text("暂无更多节目安排", color = Muted, fontSize = 15.sp)
                } else LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                    contentPadding = PaddingValues(bottom = 25.dp)
                ) {
                    items(future, key = { "${it.start}:${it.title}" }) { program ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(epgTime(program.start), color = Accent, fontSize = 15.sp)
                            Text(program.title, color = Ivory, fontSize = 16.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

private fun epgTime(timeMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timeMs))

@Composable
fun LibraryPage(model: CinemaViewModel) {
    val tick = model.libraryVersion
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(top = 116.dp)) {
        PageHeading("我的片库", "继续观看 · 想看 · 在看 · 看过 · 收藏 · 弃剧")
        Spacer(Modifier.height(18.dp))
        MediaRail("继续观看", model.history(), model)
        LibraryStatus.entries.forEach { status ->
            val items = if (status == LibraryStatus.FAVORITE) model.favorites()
                else model.libraryByStatus(status)
            MediaRail(status.title, items, model)
        }
        if (model.history().isEmpty() && LibraryStatus.entries.all {
            model.libraryByStatus(it).isEmpty()
        } && model.favorites().isEmpty()) {
            EmptyMessage("尚无片库内容，请从影视详情页添加", Modifier.fillMaxWidth().height(180.dp))
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
fun SettingsPage(model: CinemaViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = margin, vertical = 42.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PageHeading("CinemaTV 设置", "LibreTV 连接 · 片源管理 · 直播订阅")
            Spacer(Modifier.weight(1f))
            GlassAction("← 返回首页", Modifier.width(155.dp).height(47.dp)) { model.navigate(Page.HOME) }
        }
        Spacer(Modifier.height(14.dp))
        Text("LibreTV 服务器", fontSize = 21.sp, color = Ivory, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvInput(model.serverAddress, { model.serverAddress = it }, "https://your-libretv.example",
                modifier = Modifier.weight(1f).height(59.dp), onSubmit = model::connect)
            GlassAction("连接 / 刷新", Modifier.width(180.dp).height(59.dp), prominent = true) { model.connect() }
        }
        if (model.passwordRequired && !model.authorized) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvInput(model.password, { model.password = it }, "LibreTV 登录密码",
                    modifier = Modifier.weight(1f).height(59.dp), isPassword = true, onSubmit = model::login)
                GlassAction("登录", Modifier.width(160.dp).height(59.dp), prominent = true) { model.login() }
            }
        }
        Text(if (model.authorized) "已连接服务器  ·  点播源 ${model.sources.size} 个 · 直播订阅 ${model.liveSources.size} 个"
            else "未认证或未连接", color = if (model.authorized) Accent else Muted, fontSize = 15.sp)
        Spacer(Modifier.height(18.dp))
        GlassAction(
            if (model.opticalGlassEnabled) "高级光学高光：开启" else "高级光学高光：关闭",
            Modifier.width(235.dp).height(45.dp)
        ) { model.setOpticalGlass(!model.opticalGlassEnabled) }
        Text("光学高光仅在设备性能允许时开启，低版本 Android 自动使用渐变降级。", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Text("高清剧照元数据服务（可选 TV BFF）", fontSize = 21.sp, color = Ivory,
            fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvInput(model.metadataAddress, { model.metadataAddress = it },
                "https://your-bff.example", Modifier.weight(1f).height(59.dp))
            TvInput(model.metadataAccessToken, { model.metadataAccessToken = it },
                "BFF 访问令牌（仅保存在内存）", Modifier.weight(1f).height(59.dp), isPassword = true)
            GlassAction("保存", Modifier.width(120.dp).height(59.dp)) { model.configureMetadata() }
        }
        Text("需要在自建 BFF 配置 TMDB_READ_ACCESS_TOKEN，BFF 令牌不会写入 APK 或本地明文文件。仅向元数据服务发送片名、年份和类型。",
            color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        Text("添加点播源（Apple CMS JSON API）", fontSize = 21.sp, color = Ivory, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvInput(model.sourceName, { model.sourceName = it }, "来源名称", Modifier.weight(.7f).height(59.dp))
            TvInput(model.sourceAddress, { model.sourceAddress = it }, "https://.../api.php/provide/vod",
                Modifier.weight(1.7f).height(59.dp))
            GlassAction("添加", Modifier.width(120.dp).height(59.dp)) { model.addSource(false) }
        }
        Text("已配置点播源（自定义来源可移除）：", color = Muted, fontSize = 14.sp)
        model.sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(source.name, color = if (source.enabled) Ivory else Muted,
                    fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                val health = model.sourceProbes[source.url]
                Text(
                    when {
                        !source.enabled -> "已停用"
                        health == null -> "未检测"
                        health.reachable -> "正常 · ${health.latencyMs}ms"
                        else -> health.message.take(24)
                    },
                    color = if (health?.reachable == true && source.enabled) Accent else Muted,
                    fontSize = 13.sp, modifier = Modifier.width(178.dp), maxLines = 1
                )
                GlassAction("测速", Modifier.width(82.dp).height(40.dp)) { model.probeSource(source) }
                GlassAction(if (source.enabled) "停用" else "启用", Modifier.width(82.dp).height(40.dp)) {
                    model.toggleSource(source)
                }
                if (source.key.startsWith("custom_")) GlassAction("移除", Modifier.width(82.dp).height(40.dp)) {
                    model.removeSource(source.url)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("添加直播源（M3U）", fontSize = 21.sp, color = Ivory, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvInput(model.liveName, { model.liveName = it }, "直播订阅名称", Modifier.weight(.7f).height(59.dp))
            TvInput(model.liveAddress, { model.liveAddress = it }, "M3U 订阅 URL", Modifier.weight(1.7f).height(59.dp))
            GlassAction("添加", Modifier.width(120.dp).height(59.dp)) { model.addSource(true) }
        }
        TvInput(model.liveEpg, { model.liveEpg = it }, "可选：XMLTV 节目单 URL", Modifier.fillMaxWidth().height(59.dp))
        Text("已配置直播源（自定义来源可移除）：", color = Muted, fontSize = 14.sp)
        model.liveSources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(source.name, color = Ivory, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (source.key.startsWith("custom_")) GlassAction("移除", Modifier.width(96.dp).height(36.dp)) {
                    model.removeSource(source.url, live = true)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("注意：HTTP 连接不加密，请在公网部署时使用 HTTPS。仅添加你有权访问的内容源。",
            color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(40.dp))
    }
}
