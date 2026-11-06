package dev.cinematv.ui.tv.pages

import dev.cinematv.ui.icons.CinemaSymbol
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import dev.cinematv.ui.recommendation.RecommendationDashboardDialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.BuildConfig
import androidx.compose.ui.platform.LocalUriHandler
import dev.cinematv.ui.shared.TmdbLogo
import dev.cinematv.ui.shared.NativeSettingsSection
import dev.cinematv.ui.shared.LibraryProgressReviews
import dev.cinematv.CinemaViewModel
import dev.cinematv.Page
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.TvInput
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun SettingsScreen(model: CinemaViewModel, initialSection: Int = 0) {
    val uriHandler = LocalUriHandler.current
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    var recommendationOpen by remember { mutableStateOf(false) }
    var recommendationWasOpened by remember { mutableStateOf(false) }
    val recommendationEntry = remember { FocusRequester() }
    LaunchedEffect(recommendationOpen) {
        if (!recommendationOpen && recommendationWasOpened) { withFrameNanos { }; runCatching { recommendationEntry.requestFocus() } }
    }
    if (recommendationOpen) RecommendationDashboardDialog(model.recommendationSnapshots, tv = true,
        onDismiss = { recommendationOpen = false }, onOpenItem = { recommendationOpen = false; model.open(it) })
    val reviews = remember(model.libraryVersion) { LibraryProgressReviews.project(model.repo.library.snapshot) }
    var section by rememberSaveable(initialSection) { mutableIntStateOf(NativeSettingsSection.fromIndex(initialSection).index) }
    var pendingSectionFocus by remember { mutableStateOf<Int?>(null) }
    fun openSection(destination: NativeSettingsSection) {
        section = destination.index
        model.focusCoordinator.remember(model.focusRoute, "settings-menu", section.toString())
        pendingSectionFocus = section
    }
    LaunchedEffect(pendingSectionFocus) {
        val requested = pendingSectionFocus ?: return@LaunchedEffect
        withFrameNanos { }
        val bookmark = model.focusCoordinator.last(model.focusRoute)
        if (model.page == Page.SETTINGS && section == requested && bookmark?.region == "settings-menu" && bookmark.itemKey == requested.toString())
            runCatching { entry.requestFocus() }
        pendingSectionFocus = null
    }
    RestorePageFocus(model)
    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("CinemaTV 设置", color = TvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            GlassAction("← 返回首页", Modifier.width(125.dp).height(38.dp), icon = CinemaSymbol.Back) { model.navigate(Page.HOME) }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(25.dp)) {
            Column(Modifier.weight(.24f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NativeSettingsSection.entries.forEach { destination ->
                    val index = destination.index
                    val label = destination.title
                    val target = rememberFocusTarget(model, "settings-menu", index.toString())
                    GlassAction(label, Modifier.fillMaxWidth().height(48.dp).testTag("settings-section:${destination.wireName}").focusRequester(target)
                        .then(if (index == section) Modifier.focusRequester(entry) else Modifier), selected = index == section,
                        onFocus = { model.focusCoordinator.remember(model.focusRoute, "settings-menu", index.toString()) }) { section = index }
                }
            }
            CinemaGlassSurface(Modifier.weight(.76f).fillMaxHeight(), radius = 22.dp) {
                key(section) {
                    val state = rememberTvListState(model, "settings:$section")
                    if (section == NativeSettingsSection.SUBSCRIPTIONS.index) {
                        dev.cinematv.ui.shared.SourceSubscriptionsScreen(model, tv = true, modifier = Modifier.fillMaxSize(),
                            onOpenConnection = { openSection(NativeSettingsSection.CONNECTION) })
                    } else LazyColumn(state = state, contentPadding = PaddingValues(25.dp), verticalArrangement = Arrangement.spacedBy(15.dp), modifier = Modifier.fillMaxSize()) {
                        when (section) {
                            0 -> {
                                item { dev.cinematv.ui.douban.DoubanDeveloperOptionsEntry(model, tv = true) }
                                item { SettingTitle("LibreTV 服务器", "连接你的媒体服务，登录后开始浏览。") }
                                item { TvInput(model.serverAddress, { model.serverAddress = it }, "https://your-libretv.example", Modifier.fillMaxWidth().height(52.dp), onSubmit = model::connect) }
                                item { GlassAction("连接 / 刷新", Modifier.width(150.dp).height(43.dp), prominent = true) { model.connect() } }
                                if (model.passwordRequired && !model.authorized) {
                                    item { TvInput(model.password, { model.password = it }, "LibreTV 登录密码", Modifier.fillMaxWidth().height(52.dp), isPassword = true, onSubmit = model::login) }
                                    item { GlassAction("登录", Modifier.width(130.dp).height(43.dp), prominent = true) { model.login() } }
                                }
                                item { Text(if (model.authorized) "已连接 · 点播源 ${model.sources.size} 个 · 直播订阅 ${model.liveSources.size} 个" else if (model.busy) "正在连接…" else "未认证或未连接", color = TvColors.Secondary, fontSize = 13.sp) }
                                item { SettingTitle("高清剧照（可选）", "配置独立元数据服务后，获取匹配作品的横版背景。") }
                                item { TvInput(model.metadataAddress, { model.metadataAddress = it }, "BFF 服务地址", Modifier.fillMaxWidth().height(52.dp)) }
                                item { TvInput(model.metadataAccessToken, { model.metadataAccessToken = it }, "BFF 访问令牌（本次会话有效）", Modifier.fillMaxWidth().height(52.dp), isPassword = true) }
                                item { GlassAction("保存元数据连接", Modifier.width(170.dp).height(43.dp)) { model.configureMetadata() } }
                            }
                            1 -> {
                                item { GlassAction("管理源列表订阅", Modifier.width(185.dp).height(43.dp)) { openSection(NativeSettingsSection.SUBSCRIPTIONS) } }
                                item { SettingTitle("添加点播源", "Apple CMS JSON API") }
                                item { TvInput(model.sourceName, { model.sourceName = it }, "来源名称", Modifier.fillMaxWidth().height(49.dp)) }
                                item { TvInput(model.sourceAddress, { model.sourceAddress = it }, "https://…/api.php/provide/vod", Modifier.fillMaxWidth().height(49.dp)) }
                                item { GlassAction("添加点播源", Modifier.width(150.dp).height(41.dp)) { model.addSource(false) } }
                                items(model.sources, key = { it.url }) { source ->
                                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                        val health = model.sourceProbes[source.url]
                                        Text(source.name + if (!source.enabled) " · 已停用" else "", color = TvColors.Text, fontSize = 15.sp)
                                        Text(if (health == null) "未检测" else if (health.reachable) "连接正常 · ${health.latencyMs}ms" else health.message, color = TvColors.Secondary, fontSize = 12.sp, maxLines = 2)
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            GlassAction("检测", Modifier.width(75.dp).height(35.dp)) { model.probeSource(source) }
                                            GlassAction(if (source.enabled) "停用" else "启用", Modifier.width(75.dp).height(35.dp)) { model.toggleSource(source) }
                                            if (model.canRemoveManualSource(source.url)) GlassAction(model.sourceRemovalLabel(source.url), Modifier.width(115.dp).height(35.dp)) { model.removeSource(source.url) }
                                        }
                                    }
                                }
                                item { SettingTitle("添加直播订阅", "M3U 频道列表 · XMLTV 节目单") }
                                item { TvInput(model.liveName, { model.liveName = it }, "订阅名称", Modifier.fillMaxWidth().height(49.dp)) }
                                item { TvInput(model.liveAddress, { model.liveAddress = it }, "M3U 订阅 URL", Modifier.fillMaxWidth().height(49.dp)) }
                                item { TvInput(model.liveEpg, { model.liveEpg = it }, "XMLTV URL（可选）", Modifier.fillMaxWidth().height(49.dp)) }
                                item { GlassAction("添加直播订阅", Modifier.width(150.dp).height(41.dp)) { model.addSource(true) } }
                                items(model.liveSources, key = { "live:${it.url}" }) { source ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(source.name, color = TvColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        if (model.canRemoveManualSource(source.url, live = true)) GlassAction(model.sourceRemovalLabel(source.url, live = true), Modifier.width(115.dp).height(35.dp)) { model.removeSource(source.url, true) }
                                    }
                                }
                            }
                            2 -> {
                                item { SettingTitle("画面与性能", "按设备能力调整材质和动效。") }
                                item { GlassAction(if (model.reducedMotion) "减少动态效果：开启" else "减少动态效果：关闭", Modifier.width(230.dp).height(44.dp), selected = model.reducedMotion) { model.updateReducedMotion(!model.reducedMotion) } }
                                item { Text("玻璃材质", color = TvColors.Text, fontSize = 18.sp) }
                                item { Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    GlassQuality.entries.forEach { quality ->
                                        GlassAction(quality.title, Modifier.width(102.dp).height(40.dp), selected = model.glassQuality == quality) { model.updateGlassQuality(quality) }
                                    }
                                } }
                                item { Text("当前生效：${LocalTvEffects.current.glass.title}。${dev.cinematv.ui.tv.components.LocalGlassStatus.current}", color = TvColors.Secondary, fontSize = 14.sp) }
                                item { Text("自动优先普通模糊，完整光学需 Android 13 及以上并手动选择；Android 12 支持普通模糊，更低版本使用基础材质。省电、低内存、热压或绘制失败可临时回落，不改保存偏好。减少动态只停用动效。仅采样实际 Compose 背景；视频与独立弹窗不参与。", color = TvColors.Secondary, fontSize = 13.sp, lineHeight = 21.sp) }
                            }
                            3 -> {
                                item { SettingTitle("CinemaTV", "版本 ${BuildConfig.VERSION_NAME}") }
                                item { dev.cinematv.ui.update.UpdateSettings(tv = true) }
                                item { GlassAction("本地推荐看板", Modifier.testTag("recommendation-dashboard-open").focusRequester(recommendationEntry).width(180.dp).height(42.dp)) {
                                    recommendationWasOpened = true; recommendationOpen = true
                                } }
                                item { Text("为大屏与故事而生。", color = TvColors.Text, fontSize = 22.sp) }
                                if (model.metadataAddress.isNotBlank()) item { TmdbLogo() }
                                item { GlassAction("TMDB 官网", Modifier.width(140.dp).height(36.dp)) { uriHandler.openUri("https://www.themoviedb.org") } }
                                item { Text("元数据与图像：TMDB（配置高清剧照服务后）。\nThis product uses the TMDB API but is not endorsed or certified by TMDB.\nhttps://www.themoviedb.org", color = TvColors.Secondary, fontSize = 13.sp, lineHeight = 22.sp) }
                                item { Text("开源组件：Kotlin、AndroidX Compose、WebKit、Media3、Coil、Kyant Backdrop（Apache-2.0），React、React DOM 与 Scheduler（MIT）。CinemaTV 原创代码采用 MIT 许可；第三方许可与修改说明随 APK 提供。\n仅添加你有权访问的服务器和内容源。", color = TvColors.Secondary, fontSize = 13.sp, lineHeight = 22.sp) }
                            }
                            4 -> {
                                item { SettingTitle("开发者选项", "实验功能与本机账号设置。") }
                                if (BuildConfig.UPDATE_CHANNEL == "dev") item { dev.cinematv.ui.douban.DoubanDeveloperOptionsEntry(model, tv = true) }
                                else item { Text("实验选项仅在 Dev 版本提供。", color = TvColors.Secondary, fontSize = 14.sp) }
                            }
                            5 -> {
                                item { SettingTitle("本地推荐", "查看推荐理由、已使用的本地信号与推荐偏好。") }
                                item { GlassAction("打开推荐看板", Modifier.testTag("recommendations-open").focusRequester(recommendationEntry).width(180.dp).height(42.dp)) {
                                    recommendationWasOpened = true; recommendationOpen = true
                                } }
                            }
                            6 -> {
                                libraryProgressReviewItems(reviews, model::open)
                                item { GlassAction("查看资料库", Modifier.width(155.dp).height(42.dp)) { model.navigate(Page.LIBRARY) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = TvColors.Text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = TvColors.Secondary, fontSize = 13.sp, lineHeight = 20.sp)
    }
}
