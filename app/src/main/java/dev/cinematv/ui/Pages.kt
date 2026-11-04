package dev.cinematv.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.data.MediaItem

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
            .background(Color(0xDD1D1B21))
            .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(35.dp))
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
private fun HeroBackground(item: MediaItem?) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(
        listOf(Color(0xFF412B25), Color(0xFF50333A), Color(0xFF11141D))
    ))) {
        val img = item?.backdrop?.ifBlank { item.poster } ?: ""
        if (img.isNotBlank()) AsyncImage(
            model = img, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(570.dp)
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            listOf(Ink.copy(alpha = .93f), Ink.copy(alpha = .52f), Ink.copy(alpha = .12f))
        )))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.Transparent, Color.Transparent, Ink.copy(alpha = .7f), Ink)
        )))
    }
}

@Composable
fun MediaRail(title: String, items: List<MediaItem>, model: CinemaViewModel) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(title, color = Ivory, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = margin, bottom = 20.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = margin),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items, key = { it.id + (it.source?.key ?: "") }) { item ->
                MediaTile(item, onFocus = { model.setFocused(item) },
                    onClick = { model.open(item) }, modifier = Modifier.width(255.dp))
            }
        }
    }
}

@Composable
fun ExplorePage(model: CinemaViewModel) {
    val items = model.featured + model.tvShows
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, DeepRose, Ink))),
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.padding(start = margin, bottom = 24.dp)) {
            PageHeading("发现好故事", "中心聚焦的海报视廊 · 左右选择影片")
        }
        if (items.isEmpty()) EmptyMessage("暂无推荐内容，请检查服务器连接", Modifier.fillMaxWidth().height(340.dp))
        else LazyRow(
            contentPadding = PaddingValues(horizontal = 230.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items, key = { it.id + it.title }) { item ->
                MediaTile(item, onFocus = { model.setFocused(item) }, onClick = { model.open(item) },
                    portrait = true, modifier = Modifier.width(215.dp))
            }
        }
        Spacer(Modifier.height(70.dp))
    }
}

@Composable
fun CatalogPage(model: CinemaViewModel, isTv: Boolean) {
    val items = if (isTv) model.tvShows else model.featured
    Column(Modifier.fillMaxSize().padding(top = 116.dp, start = margin, end = margin)) {
        PageHeading(if (isTv) "剧集" else "电影", "探索 LibreTV 推荐内容；点击作品进入片源搜索与详情")
        Spacer(Modifier.height(28.dp))
        if (items.isEmpty()) EmptyMessage("暂无内容，请检查服务器连接", Modifier.fillMaxSize())
        else LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            contentPadding = PaddingValues(bottom = 50.dp)
        ) {
            items(items, key = { it.id }) { item ->
                MediaTile(item, onFocus = { model.setFocused(item) }, onClick = { model.open(item) })
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
                MediaTile(item, onFocus = { model.setFocused(item) }, onClick = { model.open(item) })
            }
        }
    }
}

@Composable
fun DetailPage(model: CinemaViewModel) {
    val item = model.selected ?: return
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
                GlassAction("▶ 立即播放", Modifier.width(168.dp).height(52.dp), prominent = true) { model.playEpisode(0) }
                GlassAction("加入 / 取消收藏", Modifier.width(196.dp).height(52.dp)) { model.toggleFavorite() }
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
    Column(Modifier.fillMaxSize().padding(top = 116.dp, start = margin, end = margin)) {
        PageHeading("直播电视", "通过你的 LibreTV M3U 订阅加载频道")
        Spacer(Modifier.height(18.dp))
        if (model.liveSources.isEmpty()) {
            EmptyMessage("尚未配置直播源，请进入设置添加 M3U 订阅", Modifier.fillMaxWidth().height(130.dp))
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(model.liveSources) { source ->
                    GlassAction(source.name, Modifier.width(185.dp).height(46.dp),
                        selected = model.activeLiveSource?.url == source.url) { model.loadChannels(source) }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        if (model.busy) CircularProgressIndicator(color = Accent)
        else if (model.channels.isEmpty()) EmptyMessage("暂无频道", Modifier.fillMaxWidth().height(140.dp))
        else LazyVerticalGrid(
            columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 45.dp)
        ) {
            items(model.channels, key = { it.id }) { channel ->
                GlassAction(channel.name, Modifier.fillMaxWidth().height(62.dp)) { model.playChannel(channel) }
            }
        }
    }
}

@Composable
fun LibraryPage(model: CinemaViewModel) {
    val tick = model.libraryVersion
    Column(Modifier.fillMaxSize().padding(top = 116.dp)) {
        PageHeading("我的片库", "最近观看与收藏",).let { }
        Spacer(Modifier.height(18.dp))
        MediaRail("继续观看", model.history(), model)
        MediaRail("我的收藏", model.favorites(), model)
        if (model.history().isEmpty() && model.favorites().isEmpty()) EmptyMessage(
            "播放影视作品或添加收藏后将显示在这里", Modifier.fillMaxWidth().height(180.dp)
        )
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
        Text("添加点播源（Apple CMS JSON API）", fontSize = 21.sp, color = Ivory, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvInput(model.sourceName, { model.sourceName = it }, "来源名称", Modifier.weight(.7f).height(59.dp))
            TvInput(model.sourceAddress, { model.sourceAddress = it }, "https://.../api.php/provide/vod",
                Modifier.weight(1.7f).height(59.dp))
            GlassAction("添加", Modifier.width(120.dp).height(59.dp)) { model.addSource(false) }
        }
        Text("已有点播源：${model.sources.joinToString(" / ") { it.name }.ifBlank { "无" }}", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Text("添加直播源（M3U）", fontSize = 21.sp, color = Ivory, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvInput(model.liveName, { model.liveName = it }, "直播订阅名称", Modifier.weight(.7f).height(59.dp))
            TvInput(model.liveAddress, { model.liveAddress = it }, "M3U 订阅 URL", Modifier.weight(1.7f).height(59.dp))
            GlassAction("添加", Modifier.width(120.dp).height(59.dp)) { model.addSource(true) }
        }
        TvInput(model.liveEpg, { model.liveEpg = it }, "可选：XMLTV 节目单 URL", Modifier.fillMaxWidth().height(59.dp))
        Text("已有直播源：${model.liveSources.joinToString(" / ") { it.name }.ifBlank { "无" }}", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Text("注意：HTTP 连接不加密，请在公网部署时使用 HTTPS。仅添加你有权访问的内容源。",
            color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(40.dp))
    }
}
