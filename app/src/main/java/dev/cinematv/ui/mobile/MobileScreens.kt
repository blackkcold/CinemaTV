package dev.cinematv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.LiveTv
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.design.TvEffects
import dev.cinematv.ui.tv.design.LocalTvEffects
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import coil3.compose.AsyncImage
import dev.cinematv.ui.icons.CinemaIcon
import dev.cinematv.ui.icons.CinemaSymbol
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.MediaItem
import dev.cinematv.data.SearchFilters
import dev.cinematv.ui.shared.metadataLine
import dev.cinematv.ui.shared.TmdbLogo
import androidx.compose.ui.platform.LocalUriHandler
import dev.cinematv.ui.CinemaBackdrop
import dev.cinematv.ui.Ink
import dev.cinematv.ui.Ivory
import dev.cinematv.ui.Muted
import dev.cinematv.ui.Accent

private val surface = Color(0xFF191B21)
private val stroke = Color(0xFF424650)

@Composable
fun MobileRootApp(model: CinemaViewModel) {
    val page = model.page
    CompositionLocalProvider(LocalTvEffects provides TvEffects(model.reducedMotion, GlassQuality.BASIC)) {
    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = surface, primary = Ivory, onPrimary = Ink,
        onBackground = Ivory, onSurface = Ivory, secondary = Accent
    )) {
        Scaffold(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            containerColor = Ink,
            topBar = {
                if (page != Page.PLAYER && page != Page.DETAIL) Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CinemaTV", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { model.navigate(Page.SETTINGS) }, modifier = Modifier.semantics { contentDescription = "打开设置" }) {
                        CinemaIcon(CinemaSymbol.Settings, selected = page == Page.SETTINGS)
                    }
                }
            },
            bottomBar = {
                if (page != Page.DETAIL && page != Page.PLAYER) {
                    NavigationBar(containerColor = surface) {
                        listOf(
                            "精选" to Page.HOME,
                            "浏览" to Page.MOVIES,
                            "搜索" to Page.SEARCH,
                            "直播" to Page.LIVE,
                            "片库" to Page.LIBRARY
                        ).forEach { (label, destination) ->
                            NavigationBarItem(
                                modifier = Modifier.semantics { contentDescription = "打开" + label },
                                selected = page == destination || (destination == Page.MOVIES && page in listOf(Page.SERIES, Page.EXPLORE)),
                                onClick = { model.navigate(destination) },
                                icon = {
                                    val approved = when (destination) {
                                        Page.HOME -> CinemaSymbol.Home
                                        Page.SEARCH -> CinemaSymbol.Search
                                        Page.LIBRARY -> CinemaSymbol.Library
                                        else -> null
                                    }
                                    if (approved != null) CinemaIcon(approved, Modifier.size(24.dp), selected = page == destination)
                                    else Icon(if (destination == Page.LIVE) Icons.Outlined.LiveTv else Icons.Outlined.Movie,
                                        contentDescription = null, modifier = Modifier.size(22.dp))
                                },
                                label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (page) {
                    Page.HOME -> MobileHome(model)
                    Page.EXPLORE -> MobileCatalog(model, null)
                    Page.MOVIES -> MobileCatalog(model, false)
                    Page.SERIES -> MobileCatalog(model, true)
                    Page.SEARCH -> MobileSearch(model)
                    Page.DETAIL -> MobileDetail(model)
                    Page.PLAYER -> MobilePlayerPage(model)
                    Page.LIVE -> MobileLive(model)
                    Page.LIBRARY -> MobileLibrary(model)
                    Page.SETTINGS -> MobileSettings(model)
                }
                if (model.message.isNotBlank() && page != Page.PLAYER) {
                    Surface(
                        color = Color(0xFF303039),
                        shape = RoundedCornerShape(15.dp),
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(14.dp)
                    ) {
                        Text(model.message, Modifier.padding(13.dp), fontSize = 13.sp)
                    }
                    LaunchedEffect(model.message) {
                        kotlinx.coroutines.delay(5000)
                        model.dismissMessage()
                    }
                }
            }
        }
    }
}
}

@Composable
private fun MobileHeading(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, color = Ivory, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun MobileMediaCard(item: MediaItem, model: CinemaViewModel, modifier: Modifier = Modifier) {
    Column(modifier.clickable { model.open(item) }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(.72f).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF343945))
        ) {
            if (item.poster.isNotBlank()) AsyncImage(
                model = item.poster, contentDescription = item.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            ) else Text(item.title, Modifier.align(Alignment.Center).padding(12.dp),
                color = Ivory, fontSize = 15.sp, maxLines = 3)
        }
        Text(item.title, color = Ivory, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(listOf(item.year, item.category).filter(String::isNotBlank).joinToString(" · "),
            color = Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun MobileRail(title: String, items: List<MediaItem>, model: CinemaViewModel) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Ivory, fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp, modifier = Modifier.padding(horizontal = 20.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            items(items, key = { it.id + (it.source?.url ?: "") }) { item ->
                MobileMediaCard(item, model, Modifier.width(127.dp))
            }
        }
    }
}

@Composable
private fun MobileHome(model: CinemaViewModel) {
    val hero = model.featured.firstOrNull()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 26.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(390.dp)) {
                CinemaBackdrop(hero)
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("CINEMA TV", color = Accent, fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp, fontSize = 12.sp)
                    Text(hero?.title ?: "CinemaTV", color = Ivory,
                        fontSize = 35.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(listOfNotNull(hero?.year, hero?.category)
                        .filter(String::isNotBlank).joinToString(" · "), color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MobileGlassButton(onClick = {
                            if (hero != null) model.open(hero) else model.navigate(Page.SETTINGS)
                        }) { Text("查看影片") }
                        MobileGlassOutlinedButton(onClick = { model.navigate(Page.SEARCH) }) {
                            Text("搜索")
                        }
                    }
                }
            }
        }
        item { MobileRail("继续观看", model.history(), model) }
        item { MobileRail("精选电影", model.featured, model) }
        item { MobileRail("热门剧集", model.tvShows, model) }
        if (model.catalogAttribution.isNotBlank()) item {
            Text(model.catalogAttribution, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        item {
            TextButton(onClick = { model.navigate(Page.EXPLORE) },
                modifier = Modifier.padding(start = 16.dp)) {
                Text("探索更多影片")
            }
        }
    }
}

@Composable
private fun MobileCatalog(model: CinemaViewModel, tvOnly: Boolean?) {
    val contents = when (tvOnly) {
        true -> model.browseSeries
        false -> model.browseMovies
        null -> (model.browseMovies + model.browseSeries).distinctBy { it.title + it.year }
    }
    Column(Modifier.fillMaxSize()) {
        MobileHeading(when (tvOnly) {
            true -> "剧集"
            false -> "电影"
            null -> "探索"
        }, "点击封面查看详情")
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { model.navigate(Page.MOVIES) }) { Text("电影") }
            TextButton(onClick = { model.navigate(Page.SERIES) }) { Text("剧集") }
            TextButton(onClick = { model.navigate(Page.EXPLORE) }) { Text("发现") }
        }
        if (contents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无推荐内容，请检查服务器连接", color = Muted)
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(142.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 25.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            items(contents, key = { it.id + it.title }) { item ->
                MobileMediaCard(item, model)
            }
        }
    }
}

@Composable
private fun MobileSearch(model: CinemaViewModel) {
    Column(Modifier.fillMaxSize()) {
        MobileHeading("搜索影视", "本地片库、元数据目录与 LibreTV 片源")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = model.query, onValueChange = { model.query = it },
                label = { Text("片名、别名或演员") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.search() })
            )
            MobileGlassButton(onClick = { model.search() }) { Text("搜索") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = model.searchFilters.isTv != null,
                onClick = { model.updateSearchFilters(model.searchFilters.copy(isTv = when (model.searchFilters.isTv) { null -> false; false -> true; true -> null })) },
                label = { Text(when (model.searchFilters.isTv) { true -> "剧集"; false -> "电影"; null -> "全部类型" }) }) }
            item { FilterChip(selected = model.searchFilters.year != null, onClick = model::cycleSearchYear,
                label = { Text(model.searchFilters.year ?: "全部年份") }) }
            item { FilterChip(selected = model.searchFilters.ratingSource == "TMDB",
                onClick = { model.updateSearchFilters(model.searchFilters.copy(minRating = 8.0, ratingSource = "TMDB", doubanTopOnly = false)) }, label = { Text("TMDB ≥ 8") }) }
            item { FilterChip(selected = model.searchFilters.ratingSource == "Douban" && !model.searchFilters.doubanTopOnly,
                onClick = { model.updateSearchFilters(model.searchFilters.copy(minRating = 8.0, ratingSource = "Douban", doubanTopOnly = false)) }, label = { Text("豆瓣 ≥ 8") }) }
            item { FilterChip(selected = model.searchFilters.doubanTopOnly,
                onClick = { model.updateSearchFilters(model.searchFilters.copy(minRating = null, ratingSource = "Douban", doubanTopOnly = true)) }, label = { Text("豆瓣 TOP250") }) }
            item { TextButton(onClick = { model.updateSearchFilters(SearchFilters()) }) { Text("重置") } }
        }
        if (model.searchFilters.ratingSource == "Douban") Text("仅使用有效授权目录；未配置时无豆瓣结果。", color = Muted,
            fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp))
        if (model.catalogStatus.isNotBlank()) Text(model.catalogStatus, color = Muted,
            fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (model.catalogAttribution.isNotBlank()) Text(model.catalogAttribution, color = Muted,
            fontSize = 10.sp, modifier = Modifier.padding(horizontal = 20.dp))
        if (model.searchHistory.isNotEmpty()) {
            Text("最近搜索", color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(model.searchHistory, key = { it }) { text ->
                    TextButton(onClick = { model.query = text; model.search() }) {
                        Text(text, maxLines = 1)
                    }
                }
            }
        }
        if (model.busy || model.chartLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            if (model.busy || model.chartLoading) "正在逐源搜索 · 已找到 " + model.results.size + " 部"
            else "搜索结果 · " + model.results.size,
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            color = Muted, fontSize = 13.sp
        )
        if (model.results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (model.busy || model.chartLoading) "正在获取结果..." else "请输入关键词搜索",
                    color = Muted)
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(142.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(model.results, key = { (it.source?.key ?: "") + ":" + it.id }) { item ->
                MobileMediaCard(item, model)
            }
        }
    }
}

@Composable
private fun MobileDetail(model: CinemaViewModel) {
    val libraryVersion = model.libraryVersion
    val item = model.selected ?: return
    var showAlternates by remember(item.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(bottom = 28.dp)) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            CinemaBackdrop(item)
            TextButton(
                onClick = { model.back() },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) { MobileIconLabel(CinemaSymbol.Back, "← 返回") }
        }
        Column(Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(item.metadataLine(), color = Muted)
            Text(item.description.ifBlank { if (model.busy) "正在加载详情..." else "暂无简介" },
                color = Ivory.copy(alpha = .86f), fontSize = 14.sp, lineHeight = 22.sp)
            if (item.catalogAttribution.isNotBlank()) Text(item.catalogAttribution, color = Muted, fontSize = 11.sp)
            if (model.selectedRecommendationReason.startsWith("因为") || model.selectedRecommendationReason.startsWith("来自"))
                Text(model.selectedRecommendationReason, color = Accent, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MobileGlassButton(onClick = {
                    model.playEpisode(item.lastEpisode.coerceIn(0, (item.episodes.size - 1).coerceAtLeast(0)))
                }) { MobileIconLabel(CinemaSymbol.Play, "播放 / 续播") }
                MobileGlassOutlinedButton(onClick = { model.toggleFavorite() }) { MobileIconLabel(CinemaSymbol.Bookmark, if (model.repo.isFavorite(item)) "已收藏" else "收藏", selected = model.repo.isFavorite(item)) }
            }
            MobileGlassOutlinedButton(onClick = { showAlternates = !showAlternates; model.findAlternatives() }) {
                Text("选择片源")
            }
            if (showAlternates) {
                model.alternatives.forEach { alternate ->
                    TextButton(onClick = { model.open(alternate) }) {
                        Text(alternate.source?.name ?: alternate.title)
                    }
                }
            }
            Text("加入片库", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(LibraryStatus.entries) { status ->
                    TextButton(onClick = { model.setLibraryStatus(status) }) {
                        Text(status.title)
                    }
                }
            }
            Text("选集 · " + item.episodes.size, fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold)
            if (item.episodes.isEmpty()) Text("当前没有可播放的剧集", color = Muted)
            item.episodes.indices.chunked(4).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { index ->
                        MobileGlassOutlinedButton(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 8.dp),
                            onClick = { model.playEpisode(index) }
                        ) { Text(model.episodeLabel(index), fontSize = 12.sp, maxLines = 2) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileLibrary(model: CinemaViewModel) {
    val version = model.libraryVersion
    LazyColumn(verticalArrangement = Arrangement.spacedBy(19.dp),
        contentPadding = PaddingValues(bottom = 35.dp)) {
        item { MobileHeading("我的片库", "最近观看与收藏") }
        item { MobileRail("继续观看", model.history(), model) }
        LibraryStatus.entries.forEach { status ->
            item { MobileRail(status.title, model.libraryByStatus(status), model) }
        }
        if (model.history().isEmpty() && LibraryStatus.entries.all {
            model.libraryByStatus(it).isEmpty()
        }) item {
            Text("暂无片库内容，请先播放或收藏作品", color = Muted,
                modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun MobileLive(model: CinemaViewModel) {
    Column(Modifier.fillMaxSize()) {
        MobileHeading("直播电视", "M3U 频道 · XMLTV 节目单")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(model.liveSources, key = { it.url }) { source ->
                MobileGlassOutlinedButton(onClick = { model.loadChannels(source) }) {
                    Text(source.name)
                }
            }
        }
        if (model.activeChannel != null) {
            val now = System.currentTimeMillis()
            val current = model.epgPrograms.firstOrNull { now >= it.start && now < it.stop }
            Text("当前节目：" + (current?.title ?: "暂无节目单"),
                modifier = Modifier.padding(18.dp), color = Accent)
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(model.channels, key = { it.id }) { channel ->
                Surface(color = surface, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { model.focusChannel(channel) }) {
                            Text(channel.name, fontWeight = FontWeight.SemiBold)
                            Text(channel.group, color = Muted, fontSize = 12.sp)
                        }
                        MobileGlassButton(onClick = { model.playChannel(channel) }) { Text("播放") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    secret: Boolean = false,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        singleLine = true, modifier = modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
    )
}

@Composable
private fun MobileSettings(model: CinemaViewModel) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CinemaTV 设置", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("LibreTV 服务器", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        MobileField("服务器 URL", model.serverAddress, { model.serverAddress = it })
        MobileGlassButton(onClick = { model.connect() }, modifier = Modifier.fillMaxWidth()) {
            Text("连接 / 刷新")
        }
        if (model.passwordRequired && !model.authorized) {
            MobileField("服务器密码", model.password, { model.password = it }, secret = true)
            MobileGlassButton(onClick = { model.login() }) { Text("登录 LibreTV") }
        }
        Text(if (model.authorized) "连接正常" else "未认证或未连接", color = Accent)
        HorizontalDivider(color = stroke)
        Text("添加点播源", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        MobileField("名称", model.sourceName, { model.sourceName = it })
        MobileField("Apple CMS API URL", model.sourceAddress, { model.sourceAddress = it })
        MobileGlassOutlinedButton(onClick = { model.addSource(false) }) { Text("添加点播源") }
        model.sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { model.probeSource(source) }) { Text("检测") }
                TextButton(onClick = { model.toggleSource(source) }) {
                    Text(if (source.enabled) "停用" else "启用")
                }
            }
        }
        HorizontalDivider(color = stroke)
        Text("直播订阅", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        MobileField("直播源名称", model.liveName, { model.liveName = it })
        MobileField("M3U URL", model.liveAddress, { model.liveAddress = it })
        MobileField("XMLTV URL（可选）", model.liveEpg, { model.liveEpg = it })
        MobileGlassOutlinedButton(onClick = { model.addSource(true) }) { Text("添加直播订阅") }
        HorizontalDivider(color = stroke)
        Text("高清剧照 BFF（可选）", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        MobileField("BFF 地址", model.metadataAddress, { model.metadataAddress = it })
        MobileField("BFF Token（不持久保存）", model.metadataAccessToken,
            { model.metadataAccessToken = it }, secret = true)
        MobileGlassOutlinedButton(onClick = { model.configureMetadata() }) { Text("保存元数据连接") }
        HorizontalDivider(color = stroke)
        Text("画面与动效", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("减少动态效果", Modifier.weight(1f))
            Switch(checked = model.reducedMotion, onCheckedChange = model::updateReducedMotion)
        }
        if (model.metadataAddress.isNotBlank()) TmdbLogo()
        TextButton(onClick = { uriHandler.openUri("https://www.themoviedb.org") }) { Text("TMDB 官网") }
        Text("TMDB 元数据与图像（配置后）。This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Muted, fontSize = 12.sp)
        Text("仅连接自行部署且有合法访问权限的服务器和片源。",
            color = Muted, fontSize = 12.sp)
    }
}
