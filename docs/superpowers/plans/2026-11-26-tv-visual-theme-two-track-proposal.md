# CinemaTV 电视端界面统一与双主题方案（v2）

> 状态：**方案提案，未实施**。本文件只定义目标、证据、规格、架构接缝、测试策略与分阶段步骤；不代表任何产品代码、构建、金标、设备或性能结果。
>
> 源码基线：`dev-compose @ 9bd1a4b6d0bf74b78b48200a62df074165cc803d`。工作树 `feature/tv-ui-theme-refresh`，未提交、未推送。
>
> 并行分支：`feature/tv-ux-performance` 正在改首页焦点遍历、搜索三态/筛选语义、跨源聚合。本方案不承担这些范围，边界见 §9。
>
> 本轮边界：不修改产品代码、不运行构建/测试/AVD/真机、不采金标、不提交。文中“贵/中/便宜”仅来自代码路径结构与既有 E-gate 文档；凡未实测一律写**未知**。

---

## 0. 相对 v1 的修订

用户对 v1 提出五条修正，其中两条改变了方案结构：

| 修正 | v1 的做法 | v2 的做法 | 影响 |
|---|---|---|---|
| **布局必须一致** | Poster 给了更矮 hero（.52 vs .59）、更宽可视卡片（/4.6 vs /3.8） | **两套主题共用同一个 `TvLayout`，一个 dp 都不分叉**。差异只允许落在：表面材质、颜色、阴影/抬升、焦点环、景深、动效时长、背景处理 | 主题切换从「重排整页」降为「重绘」；金标只需一套结构基线 |
| **默认落点按推荐 + 首页要有最新热门** | 只做了 AUTO 判据 | AUTO 判据保留（§4.3），新增首页热门数据链与降级（§7） | 新增数据层工作，且与并行分支有交集 |
| **Poster 留极轻背景 tint** | 纯不透明面板 | 保留单张 backdrop + 一层极低频取色 tint（§5.2.4） | 成本为一次全屏填充，不是模糊 |
| **用大屏壁纸做设计 + 找图源 API** | 只提到「高清壁纸」 | 完整分层壁纸链、许可边界、尺寸预算（§6） | 新增 DTO 字段与 BFF 尺寸位 |
| **统一字体 + 规划字重规则** | 只提到字号偏小 | 完整字重方案：可变字体决策、真实数字、字重规则表（§5.3） | 新增字体资产决策与加载改造 |

---

## 1. 目标与非目标

### 1.1 目标

1. **界面统一**：一套布局锚点、一套字阶、一套字重规则、一套间距与圆角。主题不改变任何几何。
2. **两套视觉语言共存**：Cinema（现有，电影感/玻璃）与 Poster（新增，图片主导/不透明面板/强焦点）。两者共用布局与字阶，只在材质、色彩、景深与动效上不同。
3. **让低能力设备得到为它设计的界面**，而不是「影院构图 + 平板材质」。默认解析见 §4.3。
4. **真正的高清壁纸链**：把当前 1280 宽的上限打开，并守住许可与到期清理。
5. **中文真字重**：消除 500/600 上的合成粗体，并给出可执行的字重规则。
6. **首页有「最新热门」**：按「TMDB 趋势 → 豆瓣公开热门 → 搜索发现」三级降级，只用已授权的通道。

### 1.2 非目标

- 不重写 Compose TV 架构、不替换 Media3、不引入 Haze/Liquid 或任何新依赖。
- 不升级 Compose / Kotlin / compileSdk / **minSdk**（API26 必须可安装启动）。
- 不改手机 UI 的信息架构。
- **不因性能理由把 Cinema 预先降级**。Cinema 的玻璃意图一项不减（约束 8）。
- 不抓取 IMDb 图片（理由见 §6.2）。
- 不声称任何未测量的帧率、内存或功耗收益。

---

## 2. 证据基础

### 2.1 本仓库代码事实

| # | 事实 | 位置 |
|---|---|---|
| F1 | **默认路径是最贵的一档。** `AUTO` 解析为 `BLUR`；`GlassAction` 把 `level` 硬编码为 `FULL`，所以每个按钮在 AUTO 下实际走 BLUR，半径 `9dp`；而 FULL 非 dense 只有 `1.6dp`——BLUR 的核半径是 FULL 的 5.6 倍。 | `GlassControlMaterial.kt:50-55,78`；`GlassAction.kt:107-109`；`ProductionGlassPolicy.kt:92` |
| F2 | **API < 31 上不存在玻璃。** `resolveProductionGlass` 对 `api < 31` 直接返回 `BASIC`；`Modifier.blur` 在 API < 31 被平台静默忽略。 | `ProductionGlassPolicy.kt:80`；Android 官方 `Modifier.blur` |
| F3 | 每张卡片在真实图覆盖下仍先画 `AmbientArtwork`（径向 + 斜向渐变 + 7 条 drawLine）。 | `FocusMediaCard.kt:95` |
| F4 | 背景图发**两次** `ImageRequest`：先 `size(1.dp).alpha(0f)` 校验，再显示。 | `BackdropLayer.kt:91-109` |
| F5 | `PosterMotionQuality.FOCUS_ONLY` 已定义、全仓库唯一调用点写死 `LAYERED`；`TvEffects.focusScale` 零调用方。 | `PosterMotionPolicy.kt:20,32`；`FocusPressMotion.kt:33`；`TvMotion.kt:12` |
| F6 | **焦点缩放有三个值**：卡片 1.06、按钮 1.045、令牌 1.065（未使用）。 | `PosterMotionPolicy.kt:5`；`GlassAction.kt:92`；`TvMotion.kt:12` |
| F7 | **令牌化不完整**：`ui/tv` 内 **65 处**内联 `fontSize`、**17 个不同字号值**（10/11/12/13/14/15/17/18/19/20/21/22/23/25/32/34/44）；只有 **16 处**走 `TvType`。 | `ui/tv` 全量 grep |
| F8 | `TvColors` 是 `object` 常量，无 CompositionLocal，**158 处**引用。 | `TvTokens.kt:11-22` |
| F9 | **当前最大横图 = TMDB `w1280` → 解码 1600×900。** BFF `tmdbImage` 默认 `w1280`，`original` 从未使用。 | `backend/tv-bff/server.mjs:132-135,154`；`catalog.mjs:178`；`ArtworkPrefetch.kt:13-14` |
| F10 | **预取尺寸与消费尺寸不匹配**：`ArtworkPrefetch` 硬编码 `960×540`，消费是 `1600×900`。Coil 3 内存缓存键含尺寸 → 预取只暖磁盘。 | `ArtworkPrefetch.kt:29` vs `BackdropLayer.kt:51-52` |
| F11 | **LibreTV 从不给横图。** 搜索只解 `pic`，详情只解 `cover`；`vod_pic_slide` 等字段全仓库零引用。 | `LibreTvRepository.kt:531,722`；`docs/LIBRETV_PROTOCOL.md:49,68` |
| F12 | **豆瓣横图被明令禁止。** BFF 侧 `poster: '', backdrop: ''`，测试强制 8 种图片 URL 全为空。 | `backend/tv-bff/douban-parser.mjs:99-101` |
| F13 | **授权到期唯一清图处**：`usableMetadata()` 清空 `poster/backdrop`。新增图片字段若不加进这个列表，过期片照会继续显示。 | `MetadataPolicy.kt:13` |
| F14 | **匿名热门通道已建好但从未调用。** `DoubanPublicRepository.hot()` 无任何调用方。 | `DoubanPublicRepository.kt:52-61` |
| F15 | 首页卡片必须剥离豆瓣评分/排名与 provider 署名（不清 poster/backdrop）。 | `MediaItemHomeProjection.kt:10-24`；`CinemaViewModel.kt:699` |
| F16 | 绘制入口只有 2 个（`cinemaGlassMaterial`、`cinemaNavigationLens`），容器 1 个（`CinemaGlassSurface`），准入 1 个（`resolveProductionGlass`），角色上限 1 个（`glassLevelFor`）。 | `GlassControlMaterial.kt:30,142`；`CinemaGlassSurface.kt:17`；`ProductionGlassPolicy.kt:16,69` |
| F17 | Basic 路径已很便宜：2 个圆角矩形 + 2 个渐变，不建 effect 节点；BASIC 下不预取，背景解码降 960×540。 | `GlassControlMaterial.kt:116-126`；`ArtworkPrefetch.kt:23`；`BackdropLayer.kt:51-52` |
| F18 | `TvLayout` 被 14 处以上消费，是全部几何的唯一来源。 | `TvTokens.kt:82-91`；§3.1 |
| F19 | **系统 CJK 只有 400 一个字面。** AOSP `fonts.xml`（8.0 与 14.0 均核对）CJK 段只有 `weight="400"`；`Typeface.create(family, 500, false)` 拿到 400，差 1 档 < 2 → 不合成。 | AOSP `fonts.xml`；minikin `FontFamily.cpp` |
| F20 | **500 不合成，静默等于 400；600 才合成。** Compose 门限硬编码 `requestedWeight >= 600 && font.weight < 600`。 | Compose ui-text 1.10.6 `FontSynthesis.android.kt`；`FontSynthesisTest.kt` |
| F21 | `PreloadedNotoFont` 硬编码 `weight = FontWeight.Normal`；`FontFamily` 内多个 Font 若都自称 400，matcher 永远选第一个。 | `PreloadedNotoFont.kt` |
| F22 | 字体 20,629,732 字节，`noCompress += listOf("ttc","ttf")`，`Typeface.Builder(assets, path).setTtcIndex(n)` 加载。 | `docs/NOTO_TYPOGRAPHY.md:35-44`；`app/build.gradle.kts:145`；`CinemaNoto.kt:56-66` |
| F23 | 当前 Noto 只有 400；请求 600+ 走合成粗体。 | `docs/NOTO_TYPOGRAPHY.md:46-49` |

### 2.2 外部研究结论

| # | 结论 | 性质 |
|---|---|---|
| E1 | Netflix 的高级感来自单一焦点 + 大图 + 极少文字 + 暗底高对比，不是玻璃或模糊。 | 产品观察 |
| E2 | Netflix 自己做过多层背景视差与全屏 tint 叠加，**都因 surface cache 超预算被砍**。720p 全屏纹理 3.5MB，老设备总预算 28MB，一次全屏 tint 占 23%。 | Netflix Tech Blog 公开复盘 |
| E3 | API31 以下 `Modifier.blur` 被忽略；`RenderEffect` 强制离屏（4K 全屏 ≈ 33MB）。 | Android 官方 |
| E4 | 模糊成本 ∝ 面积 × 半径；砍面积优先于砍半径。 | 机制 + 官方留边要求 |
| E5 | Netflix 2025 版把焦点卡片放大到接近全屏，被批评「一屏只见 3–4 个」，2026 已回退。**焦点放大有上限。** | 产品观察 |
| E6 | 焦点缩放官方建议区间 1.025–1.1；焦点态应改 `graphicsLayer` 而非尺寸。 | Material for TV |
| E7 | **合成粗体是「描边」不是「设计」**：Skia `useStrokeForFakeBold` 施加居中描边，量 = `size/32`（大字）～`size/24`（小字），且 **advance width 完全不变**。 | Skia `SkScalerContext.cpp`、`SkTextFormatParams.h` |
| E8 | 真 Medium 的步进比 Regular 宽 1.8%–7.2%（Noto SC Latin 实测）。合成粗体不调宽度 → 紧排时笔画压 sidebearing。 | 字体 hmtx 实测 |
| E9 | **DEFLATED 字体资产会在 native heap 里按 Typeface 各解一份。** `noCompress` 的首要理由是内存，其次才是加载延迟。 | AOSP `Asset.cpp` `getBuffer` |
| E10 | TMDB 缓存硬上限 **6 个月**；禁止把其图当自家装饰性背景；hero 必须绑定具体片目。 | TMDB API ToS |
| E11 | **Fanart.tv 的 `CC Attribution 3.0` 指的是 OpenAPI 文档，不是图片。** 站点条款明示版权归各自所有者。 | Fanart.tv 条款 |
| E12 | IMDb 无公开 API，条款禁止 screen scraping 且不得下载；图片版权链止于 IMDb。 | IMDb ToS |
| E13 | Pexels 与 Unsplash 的 API 指南**点名禁止 wallpaper application**。 | 两家官方指南 |

### 2.3 明确未证实的

- 物理电视卡顿的主因未知。全部帧数据来自 API34 TV AVD、旧 SHA `6768ab4`。
- BLUR 与 FULL 在物理电视上的相对成本未知。E-gate 的排序不外推。
- 删除 F3/F4/F10 的收益量级未知。机制上确定是净减少，毫秒数须实测。
- Poster 主题的 10 英尺接受度未知，须人眼评审。
- **CFF2 可变字体在 API26 上的行为未知**，见 §5.3。

---

## 3. 统一布局（硬约束）

### 3.1 唯一几何来源

全部几何来自 `TvLayout`（`TvTokens.kt:82-91`）：

```
gutter         = width × 5.5%
heroHeight     = height × 59%
landscapeWidth = (width − gutter×2) / 3.8   clamp 180–300dp
posterWidth    = (width − gutter×2) / 6.1   clamp 112–190dp
navigationTop  = height × 3.5%              clamp 16–28dp
contentTop     = navigationTop + 72dp
```

消费点 14 处以上：`TvRootApp`、`FloatingNavigation`、`CinemaHero`、`ContentRail`、`HomeScreen`、`LiveScreen`、`LibraryScreen`、`SettingsScreen`、`TvPlayerPage`、`TvChip`。

### 3.2 规则

> **`LocalTvLayout` 与 `TvLayout` 不得有任何主题分支。** 主题不提供、不覆盖、不调整 `TvLayout`。

推论（必须守住）：
- Poster 与 Cinema 的 hero 高度相同、卡片宽度相同、gutter 相同、每屏可见卡片数相同。
- 主题切换只触发**重组 + 重绘**，不触发 measure/layout。
- 卡片尺寸变化不在本方案内；如果将来要做，那是独立的一次「密度」变更，不是主题。

### 3.3 字阶也共享

`TvType`（9 级）是唯一字阶，两套主题共用同一份数值。字重规则（§5.4）也共用。

**例外仅一处**：主题可以改变**颜色**（`TvColors` 之外的表面色、描边、文本透明度），但不改变字号、行高、字距、字重。

### 3.4 可自动化的守卫

```kotlin
// 纯函数断言，不依赖截图
@Test fun themesShareIdenticalLayout() {
    val layout = TvLayout(width = 960.dp, height = 540.dp)
    VisualTheme.entries.forEach { /* TvVisual.maxGlass 可不同，其余几何字段必须相等 */ }
    assertEquals(layout, layoutFor(VisualTheme.CINEMA))
    assertEquals(layout, layoutFor(VisualTheme.POSTER))
}
```

以及源码锁：`ui/tv/**` 中 `LocalTvLayout` 的读取点，其**上游 3 层**内不得出现 `VisualTheme`/`TvVisual` 判断。

---

## 4. 双主题：只换材质与色彩

### 4.1 两条正交轴

```
┌──────────────────────────────────────────────┐
│ VisualTheme（视觉语言）                       │
│ CINEMA / POSTER                              │
│ 决定：表面材质、色彩、阴影、景深、动效时长      │
│ 决策周期：一个前台会话，稳定                   │
└────────────────┬─────────────────────────────┘
                 │ maxGlass（只压低，不抬高）
                 ▼
┌──────────────────────────────────────────────┐
│ GlassRenderMode（渲染后端）                   │
│ FULL / BLUR / BASIC                          │
│ 决策周期：可随省电/热压/窗口就绪动态变化        │
└────────────────┬─────────────────────────────┘
                 ▼
     effective = min(platformMode, roleCap, themeCap)
```

**不变量（必须落成单元测试）：**

1. `effective ≤ themeCap` 恒成立；主题只能压低材质，不能抬高。
2. `VisualTheme` **永不依赖瞬时信号**（`windowReady`/`powerSave`/`thermal`）。否则启动首帧会从 POSTER 翻到 CINEMA，白付一次全树重组 + 可见跳变。
3. 同一输入永远同一输出（纯函数、无 I/O、无时间）。
4. `reducedMotion` 与 `VisualTheme` 相互独立。
5. 主题不进返回栈；Back 不撤销主题切换。
6. **`TvLayout` 与 `TvType` 不受主题影响**（§3.2、§3.3）。

### 4.2 两套主题的差异清单（全部是绘制层，无一是布局层）

| 维度 | Cinema（现有，减负后） | Poster（新增） |
|---|---|---|
| 面板填充 | 玻璃：离屏 + 卷积 + lens（FULL）/ 模糊 9dp（BLUR）/ 不透明（BASIC） | **不透明** `#141417`，1dp `White 8%` 描边 |
| 模糊 | 有（FULL/BLUR） | **无** |
| 阴影 | 面板 Shadow 20dp + InnerShadow；卡片 16dp elevation | **无**；边界靠 1dp 描边 |
| 高光 | Highlight 角度随 press 旋转 | **无** |
| 卡片景深 | 3D 旋转 3° + 视差 4dp | **无**；仅 scale |
| 焦点环 | `Ink` 4dp + `Ivory` 2dp（双环，已有对比度证明） | **同一对双环**（复用，不改配色） |
| 焦点缩放 | 1.06（卡片）/ 1.045（按钮） | **1.04** / 1.03 |
| 焦点抬升 | −3dp | **−2dp** |
| 场景过渡 | 380ms | **180ms** |
| 背景 | AmbientArtwork + 单图 + 双 scrim（横 + 竖）+ 全屏录制层 | AmbientArtwork + 单图 + **一层取色 tint + 一层竖向 scrim** |
| 导航 | 玻璃 pill + 纱（`NavigationGlassFace` 4 常量） | 不透明 `#141417` 薄条 + 1dp 描边 |

**关键点：这张表里没有任何一行改变尺寸、位置或字号。**

### 4.3 默认解析

```kotlin
fun resolveVisualTheme(requested: VisualTheme, environment: GlassEnvironment): VisualTheme =
    when (requested) {
        VisualTheme.CINEMA -> VisualTheme.CINEMA
        VisualTheme.POSTER -> VisualTheme.POSTER
        VisualTheme.AUTO -> when {
            // 只用「平台已经自己在降级」的稳定判据，与现有 resolveProductionGlass 一致，
            // 不新增阈值（不读 4K/刷新率/掉帧）。
            !environment.hardwareAccelerated -> VisualTheme.POSTER
            environment.api < 31 -> VisualTheme.POSTER
            environment.lowMemory -> VisualTheme.POSTER
            else -> VisualTheme.CINEMA
        }
    }
```

**顺带修一个现存矛盾**：`GlassAction` 把 `level` 硬编码为 `FULL`（F1）。改为按 role 取 `glassLevelFor`，让 BLUR 只服务面板、不服务每个按钮。这是**行为修正**，单独立项，见 §11 P1。

### 4.4 持久化

| 项 | 决策 |
|---|---|
| 键 | `visual_theme`，String ∈ `{AUTO, CINEMA, POSTER}`，默认 `AUTO` |
| 存什么 | 只存用户偏好，不存解析结果 |
| 读取失败 | fail-closed 到 `POSTER`，不回写 |
| 动态降级 | 省电/热压只影响材质与动效，**不改主题**，不覆盖偏好 |
| 切换代价 | 不重建 Activity、不进返回栈；一次全树重组 |

---

## 5. 字体与字重

这是本方案工作量最大、也最容易被做错的一节。先纠正四个误解。

### 5.1 四个必须先纠正的事实

**① Noto 早就打包好了。** `assets/fonts/` 下是 `NotoSansCJK-Regular.ttc`（19,484,784 字节，5 个地区 face）+ NotoSans 拉丁 Regular/Bold，合计 20,629,732 字节，`noCompress` 打包。缺的不是「引入 Noto」，是**中文只有 400 一个字面**。

**② 500 不会加粗，600 才会。** Compose ui-text 1.10.6 的门限是硬编码的 `requestedWeight >= 600 && font.weight < 600`（F20）。所以：
- 请求 **500** → 拿到 **400**，像素级完全相同（不是 bug，是避免 500 被误加粗成 700 的既定策略）
- 请求 **600+** → 走 Skia 的 fake bold

**③ 合成粗体不是「加粗」，是「把填充改成描边+填充」。** Skia 的 `useStrokeForFakeBold` 施加**居中**描边，量 = `size/32`（≥36pt）～`size/24`（≤9pt）线性插值。这有三个可直接推导的后果：

- **描边一半向内**，直接吃掉字怀。「口/日/目/鬱」这类字最先糊，且不可逆。
- **量随字号变化**：同一套 style 在 12sp 徽章上比 48sp 标题上「相对更粗」，**层级关系在小字号处失真**。
- **advance width 完全不变**：真 Medium 的步进比 Regular 宽 1.8%–7.2%（实测 Noto SC Latin：`i` +4.7%、`.` +7.2%）。合成粗体不调宽度，紧排时笔画压到 sidebearing 上，「气口」消失。

**④ 系统 CJK 拿不到真字重。** AOSP `fonts.xml`（8.0 与 14.0 均核对）CJK 段只有 `weight="400"`。`Typeface.create("sans-serif", 500, false)` 得到 400；700 才合成。`Typeface.CustomFallbackBuilder` 是 API29+，minSdk 26 用不了。

### 5.2 字体资产决策

全部字节数为上游 release 实测 `Content-Length`，STORED 打包。

| 方案 | 字体总字节 | 相对现状 | 真 500 | 真 600 | 覆盖码点 | API26 风险 |
|---|---|---|---|---|---|---|
| **现状**：CJK-Regular.ttc | 20,629,732 | — | ❌ | ❌ | 44,810 | 无 |
| **A**：`NotoSansCJK-VF.ttf.ttc`（可变，glyf/gvar） | 39,801,292 | **+18.28 MiB** | ✅ | ✅ | 44,810 | 低 |
| **B**：`NotoSansCJK-VF.otf.ttc`（可变，CFF2） | 34,393,956 | **+13.13 MiB** | ✅ | ✅ | 44,810 | **中**（见下） |
| **C**：加 `NotoSansCJK-Medium.ttc`（静态） | 39,550,520 | **+18.04 MiB** | ✅ | ❌ | 44,810 | 无 |
| **D**：只加 `NotoSansSC-Medium.otf` | 29,541,596 | **+8.50 MiB** | 仅 SC | ❌ | 仅 SC 30,890 | 无 |

> 上表四行的「字体总字节」均含拉丁 Regular/Bold/Medium 三个文件（569,208 + 575,740 + 566,428 = 1,711,376 字节）。若只算 CJK 单个文件的差值：A +17.74 MiB、B +12.59 MiB、C +17.50 MiB、D +7.96 MiB。

三个关键判断：

**A 严格优于 C。** 代价几乎相同（+18.28 vs +18.04 MiB），但 A 拿到 100–900 全轴（600 是**真插值**），C 只多一个 500 且 600 仍合成。**没有理由选 C。**
**B 省 5 MiB 但有实打实的风险。** API26 自带 FreeType **2.7.1**，CFF2 在该版本只是「初步支持」，真正改进要到 FreeType **2.13.2**（2023）。Android 用自带 FreeType，不是可升级组件。对一个 minSdk 26 的项目，**不建议为省 5 MiB 承担未收敛的风险**。

**D 是「覆盖降级」不是「省钱」。** SC 子集只有 30,890 码点、ExtB 仅 54、谚文 0。用它渲染韩文片名会全部落到系统 fallback，渲染繁体则是日式写法。**片名是不可预知文本，这种降级不可接受。**

**另加一项无论如何都该做**：`NotoSans-Medium.ttf`（566,428 字节，+0.54 MiB），让拉丁真 500。这已计入上表 A/B。

> **推荐：方案 A。** 代价 +18.28 MiB，换取 5 地区 × 100–900 全轴、零覆盖损失、API26 无风险。
> **若后续在 API26 设备上证明 CFF2 可用**，可切到 B 省 5 MiB，但那需要真机证据，不是文档推断。

### 5.3 落地改造（三个会静默毁掉界面的坑）

**坑 1：`fvar` 默认值是 100，不是 400。** `NotoSansCJK-VF` 的 face 0 PostScript 名甚至是 `NotoSansCJKjp-Thin`。**任何忘记显式设 `'wght'` 的加载路径都会得到 Thin**，表现为「标题突然极细」。必须写成测试：

```kotlin
@Test fun missingWeightAxisYieldsThin() {
    // 故意不设 wght，断言 getWeight() == 100。
    // 这条测试的作用是防止后人「简化」掉 setFontVariationSettings。
}
```

**坑 2：Compose 的 `Font(path, assets, weight, variationSettings)` 不支持 TTC 索引。** `TypefaceBuilderCompat.createFromAssets` 只调 `setFontVariationSettings`，**没有 `setTtcIndex`**。用它加载 5-face TTC 永远拿到 face 0（JP）——这是一个会静默把中文字幕渲染成日文字形的陷阱。**必须继续走现有的 `PreloadedNotoFont : AndroidFont` + `Typeface.Builder(assets, path).setTtcIndex(n)` 路径。**

**坑 3：`PreloadedNotoFont` 硬编码 `weight = FontWeight.Normal`（F21）。** 如果 `FontFamily` 里三个 Font 都自称 400，matcher 永远选第一个，字重完全失效。必须参数化：

```kotlin
class PreloadedNotoFont(
    private val typeface: Typeface,
    override val weight: FontWeight,        // 必须各不相同
    override val style: FontStyle = FontStyle.Normal,
) : AndroidFont(...)
```

加载结构：

```kotlin
// 一个地区 face × 三个权重的 Typeface，共用同一份 mmap
val family = FontFamily(
    PreloadedNotoFont(build(ttcIndex, wght = 400), FontWeight.Normal),
    PreloadedNotoFont(build(ttcIndex, wght = 500), FontWeight.Medium),
    PreloadedNotoFont(build(ttcIndex, wght = 600), FontWeight.SemiBold),
)
```

**坑 4（性能）：不要启动期建全部地区 × 全部字重。** 现在 `NotoFontCache.loadUncached` 同步构建全部 5 个地区（`CinemaNoto.kt:56-66`）。加到 3 个字重就是 15 次 `Typeface.Builder.build()`，每次解析约 20MB 的字体表。1 GB 设备上这会同时抬高启动耗时与峰值内存。

改为：**先用 `NotoRegionPolicy` 解析出当前地区，只建该地区的 400；500/600 懒建。** 地区切换时按需补建。

**坑 5：`noCompress` 必须补 `otf`（若选 B）**。现在只列了 `ttc`/`ttf`。选 A 则无需改构建。

**另一个必须记住的机制**：DEFLATED 的字体资产，每次 `Typeface.Builder.build()` 都会在 native heap 里**整份解压一份**（E9）。19.4MB × N 个 Typeface 是 1 GB 设备上的真实 OOM 路径。`noCompress` 的首要理由是内存，不是 APK 体积。建议把 `asset offset % 4 == 0` 加成硬断言（未对齐会触发整份拷贝）。

### 5.4 字重规则（10-foot UI）

前提：**层级由「字号 + 颜色/不透明度 + 位置」承担，字重只做微调。** 这也是 Apple TV / Netflix 的实际做法——它们的标题很少用 Black。

| 位置 | 字重 | 理由 |
|---|---|---|
| Hero 片名（大字，单行） | **500** | 大字本身已有视觉重量；600 开始压字怀。纯拉丁片名可用 600。 |
| 页面标题（首页/搜索/我的） | **500** | 与 Hero 同级但不抢戏；靠**字号**差区分，不靠字重差。 |
| 区块标题（"继续观看"/"热门"） | **500** | 需要从海报墙里抬头，但只抬一档。 |
| 卡片标题（海报下方 1–2 行） | **400**，焦点态 500 | 字数不定、常换行，400 更耐读。焦点态升 500 **不改变布局**（行高固定）。 |
| 正文 / 简介 | **400** | 长文本唯一正确选择。≥500 的中文长文会变成灰块。 |
| 元数据（年份·时长·分级） | **400** + 降不透明度 | 用**颜色**退到背景，不用 300（Noto CJK 无 300）。 |
| 按钮 / 主要操作 | **500** | 短、需要可点击感；500 是 CJK 上「明确但不笨重」的上限。 |
| 徽章（4K/HDR/分级） | **500** + 正字距 | 字号小，合成粗体会糊。**徽章绝不用 600+。** |
| 焦点态文本 | **不加字重** | 加字重会引起文本重排 → 焦点框抖动。改用 scale/描边/位移。 |
| 播放器控制层 | 400 / 500 | 对比度靠 scrim，不靠字重。 |

**一条硬规则**：

```kotlin
// 所有 CJK TextStyle 显式关闭合成，让「缺字重」在评审时立刻暴露，而不是静默变形
TextStyle(fontFamily = cjk, fontWeight = FontWeight.Medium, fontSynthesis = FontSynthesis.None)
```

**为什么不在中文上滥用 600+**：合成 600 是描边不是设计（E7）；advance width 不变导致字距挤压（E8）；加粗量随字号变化导致层级失真；TV 的 4:2:0 色度子采样 + 视频叠加会进一步软化边缘，加粗带来的是「更脏」不是「更醒目」。

### 5.5 字号收敛（真正的工作量）

65 处内联 `fontSize`、17 个不同值 → 收敛到 `TvType` 的 9 级。

这不是机械替换，因为要把 10/11/12/13/14/15/17/18/19/20/21/22/23/25/32/34/44 映射到 9 级必须做取舍。建议映射（**会改变观感，须单独评审**）：

| 现值 | 出现次数 | 归入 | 说明 |
|---|---|---|---|
| 10, 11 | 8 | `meta` (11→**12**) | 首页署名、卡片元数据。12sp 是电视可读下限。 |
| 12, 13 | 22 | `caption` (13→**14**) | 出现最多的一组 |
| 14, 15 | 13 | `body` (15→**16**) | 简介、设置页正文 |
| 17, 18, 19 | 7 | `h3` (**19**) | 区块内小标题 |
| 20, 21, 22, 23 | 10 | `h2` (**22**) | 卡片大标题、设置分组 |
| 25 | 1 | `h1` (**28**) | 收进 h1 |
| 32, 34 | 2 | `h1`→`display` 之间 | 见下 |
| 44 | 1 | `display` (**44**) | hero |

`h1` 从 32 → 28 是为了在 9 级里留出更清晰的间隔（22 / 28 / 44）。CinemaHero 的 42sp 与 compact 34sp 统一到 `display` 的 44 或新增一级。

**这一步会改全部 6 张金标，必须单独一批完成、单独评审，不与主题改动混在同一批。**

---

## 6. 壁纸链路

### 6.1 现状：最大 1280 宽

| 来源 | 竖版 poster | 横版 backdrop |
|---|---|---|
| LibreTV 搜索 | ✅ `pic` | ❌ 从无 |
| LibreTV 详情 | ✅ `cover` | ❌ 从无（`vod_pic_slide` 零引用） |
| BFF 授权目录 | ✅ 可选 | ✅ 可选 |
| BFF TMDB 目录/元数据 | ✅ `w500` | ✅ **`w1280`** |
| 豆瓣（授权 + 匿名） | ✅ 海报 | ❌ 明令禁止 |
| 本地推断 | ❌（`landscapeArt` 明确拒绝 poster 冒充横图） | ❌ |

客户端解码上限 `1600×900`（BASIC 档 `960×540`）。**4K 电视（3840×2160 @320dpi → dp 1920×1080）上 1600px 被放大 2.4 倍**——这是「壁纸不够高级」的物理原因。

### 6.2 图源许可（结论先行）

| 图源 | 判断 | 关键依据 |
|---|---|---|
| **TMDB** | ✅ 首选，用户自备 key | 缓存 **≤6 个月**；禁止当装饰性背景；hero 必须绑定具体片目；署名 = logo + 声明 + About 页三件套 |
| **Fanart.tv** | ✅ logo 与 4K 背景补强，用户自备 key | **`CC Attribution 3.0` 指文档不指图片**；版权归各自所有者；每条带 `copyright` 字段应透出 |
| TheTVDB | ⚠️ 需 PIN/合同，横图上限 1080p | 门槛在合同不在技术 |
| **IMDb** | ❌ **明确不可** | 无公开 API；条款禁 screen scraping 且不得下载；版权链止于 IMDb |
| OMDb | ❌ 明确不可 | 条款禁止 wallpaper 类衍生；第三方非官方镜像 |
| JustWatch / ThePosterDB | ❌ 明确不可 | 无公开 API / 禁 scrape |
| **Pexels / Unsplash** | ❌ **对壁纸功能明确红灯** | 两家 API 指南点名禁止 wallpaper application |
| Wikimedia Commons | ⚠️ 仅公版老片 | 只收自由许可；现代剧照基本没有 |

**关于「刮削器直接走 IMDb 拿高清壁纸」**：风险等级高，四个面同时踩——条款违约、版权侵权（片方授权给 IMDb，不给你）、工程不稳定（CDN 签名 URL 无契约）、开源放大（把规避方法公开分发）。MIT 只覆盖代码，不覆盖抓来的图，也不免除违约。

**关于「Courtesy of TMDB」**：不是官方要求。官方要的是 TMDB 官方 logo（取自指定素材页、不得改色改比例、不得比自家标识更醒目）+ 指定声明 + 放在 About/Credits。只写「Courtesy of」而不写 logo + 声明是**不合规**的。

### 6.3 分层降级链

```
第 0 层  LibreTV 源自带 cover/poster
         → 直连，不转存、不进我们自己的缓存目录
         ↓ 缺失/失效
第 1 层  TMDB（BFF 或用户 key）
         hero:      backdrop original（解码钳制 ≤1920 宽）
         卡片:      poster w342
         详情:      backdrop w1280
         logo:      images.logos（按 zh → en → null + vote_average）
         ↓ 无 key / 429 / 无该片
第 2 层  Fanart.tv
         hdmovielogo → movie4kbackground → moviebackground
         ↓ 无 key / 无资源
第 3 层  纯代码生成：main-color 渐变 + AmbientArtwork
         （零权利风险，已在用）
```

**降级链按「资产类型」而不是「按服务」**：backdrop 从 TMDB 拿不到时，Fanart 的 `movie4kbackground` 可能更好；但 logo 应优先 Fanart（`hdmovielogo` 平均质量高于 TMDB `logos`）。

### 6.4 尺寸与内存预算

ARGB_8888 = 4 B/px，16:9：

| 档位 | 像素 | 内存 | 用途 |
|---|---|---|---|
| TMDB `w780` | 780×439 | 1.37 MB | 卡片 / 次屏 hero |
| TMDB `w1280` | 1280×720 | 3.69 MB | 详情页背景 |
| `original`@1080p | 1920×1080 | 8.29 MB | 1080p 首页全屏 |
| `original`@4K | 3840×2160 | **33.18 MB** | ⚠️ 不要盲解 |

**硬规则：**

1. **`original` 必须钳制。** TMDB `original` 宽度在 1409–3840 之间浮动，不可预测。用目标 View 的像素宽决定，不是看设备是不是 4K。
2. **判断依据是目标 View 像素宽，不是面板分辨率。** Android TV 在 4K 面板上通常仍以 1920×1080 逻辑分辨率渲染。
3. **同时驻留上限**：hero ≤ 2 张 + 卡片 ≤ 24 张（约 40 MB 峰值）。
4. **预取只在 WiFi/以太网**；热点下降到 w780。
5. **玻璃只采样已解码的 hero bitmap**，不为模糊再拉一张更大图。

### 6.5 需要改的位置（一次列全）

| 文件:行 | 变更 | 风险 |
|---|---|---|
| `data/Models.kt:18-22` | 新增 `backdropHiRes: String = ""` | 唯一 DTO 源 |
| `data/library/MediaItemCodec.kt:12-31 / 45-70` | encode + decode | JSON 向后兼容，**无 DB 迁移** |
| `data/library/MediaSnapshotMerger.kt:19-20` | 合并回退 | 低 |
| `data/MediaDomainMapper.kt:89`、`domain/MediaDomain.kt:22-23` | 映射 | 低 |
| **`data/MetadataPolicy.kt:13`** | **过期清空列表必须加 `backdropHiRes = ""`** | **安全关键**：漏了会让授权过期片照继续显示 |
| `data/LibreTvRepository.kt:168, 226, 235-239, 720` | 三个解码点 | provider 解码留在 `data/` |
| `ui/shared/MediaPresentation.kt:37-40` | 新增 `heroArt()`，**不改 `landscapeArt()`** | 卡片与预取不受影响 |
| `ui/tv/components/BackdropLayer.kt:51-53` | 改用 `heroArt` + 抬尺寸 | 触 `docs/GLASS_PRODUCTION.md:77` 的解码独立约束 |
| `ui/tv/components/ArtworkPrefetch.kt:13-14, 29` | 抬尺寸 + **修 960×540 不匹配（F10）** | 低 |
| `backend/tv-bff/server.mjs:154`、`catalog.mjs:71, 178` | 新增尺寸位（不改老字段） | 老客户端不受影响 |
| `app/src/test/.../PresentationPolicyTest.kt:27-30` | 补 `heroArt` 的拒绝用例 | — |

**完全不动**：`FocusMediaCard.kt:91-117` 的降级分支、玻璃链路、`data/doubanchart/*`、`backend/tv-bff/douban-*.mjs`、`CatalogLicensePolicy`。

**logo 是独立工作量**：TMDB 片名 logo 需 `append_to_response=images` 或 `/movie/{id}/images`，当前完全没有这条链路。新增 BFF 路由 + DTO 字段 + 第二种署名。建议作为独立阶段，不混在尺寸改造里。

---

## 7. 首页「最新热门」

### 7.1 数据源现状

| 通道 | 状态 | 有横图？ |
|---|---|---|
| **豆瓣公开热门** `DoubanPublicRepository.hot()` | **代码存在，零调用方（F14）** | ❌ 只有 poster |
| 豆瓣公开标签浏览 `browse(tag)` | 已接入搜索页筛选 | ❌ |
| TMDB trending | **BFF 无该路由** | ✅ |
| LibreTV 搜索发现 | 已用于 hero 兜底 | 视 provider |
| 本地推荐 `LocalRecommendationPipeline` | 已接入首页 | ❌ 取决于库 |

### 7.2 三级降级

```
第 1 层  TMDB 趋势（BFF 已配置 key 时）
        GET /trending/movie/week  +  /trending/tv/week
        → 有真实 backdrop，可驱动 hero
        → 署名沿用现有 TMDB 三件套
        ↓ 无 BFF / 无 key / 429
第 2 层  豆瓣公开热门（匿名通道健康时）
        hot(MOVIE) + hot(TV)，单页 40 条，一次请求
        → 只有 poster，**不能驱动 hero**
        → 只做一条横向货架
        → **不显示豆瓣评分/排名**（沿用 F15 的既有策略）
        ↓ 熔断 / 限流 / 空
第 3 层  现有搜索发现（已在用）
```

### 7.3 三条硬约束

1. **豆瓣热门不能驱动 hero。** `toMediaItem()` 只写 `poster`（F14），而 `landscapeArt()` 明确拒绝 poster 冒充横图。让豆瓣图做全屏背景会破坏既有的比例校验（`BackdropLayer.kt:97` 要求宽 ≥640 且比例 1.3–2.5）。

2. **豆瓣行必须走门禁。** `DoubanPublicGate` 是 3 秒间隔 / 10 分钟 20 次（`docs/DOUBAN_PUBLIC_ACCESS.md`）。首页热门必须是**懒加载 + 缓存优先**，不能每次回首页都请求。建议：一次会话最多一次；缓存命中直接显示；熔断时静默降级到第 3 层，不显示错误。

3. **不打包豆瓣图，不导出数据。** 只显示运行时加载的 URL；失败退化为无图。这是既有边界，热门行不例外。

### 7.4 与并行分支的冲突

首页热门必然要改 `HomeScreen.kt`（新增一条 `item`）与 `CinemaViewModel.kt`（新增状态与加载）。这两个文件都在 `feature/tv-ux-performance` 的改动集里。

**结论：首页热门排到并行分支合并之后，作为独立阶段（§11 P5）。** 本方案只定义契约，不提前动文件。

---

## 8. 架构

### 8.1 唯一开关

```kotlin
enum class VisualTheme(val title: String) { AUTO("自动"), CINEMA("影院"), POSTER("画报") }

@Immutable
data class TvVisual(
    val theme: VisualTheme,
    val maxGlass: GlassRenderMode,      // CINEMA→FULL, POSTER→BASIC
    val panelFill: Color,               // 面板底（Poster 不透明）
    val panelBorder: Color,             // 1dp 描边
    val navigationFill: Color,
    val focusScale: Float,              // 1.06 / 1.04
    val actionFocusScale: Float,        // 1.045 / 1.03
    val focusLiftDp: Float,             // -3 / -2
    val sceneMillis: Int,               // 380 / 180
    val cardDepth: Boolean,             // 3D 视差
    val cardShadow: Boolean,            // 16dp elevation
    val backdropTint: Float,            // 0f / 极低
)

val LocalTvVisual = staticCompositionLocalOf { CinemaVisual }
```

**为什么 `staticCompositionLocalOf`**：主题是树级、极少变；`static` 读取零追踪开销，代价是改值时整个 content lambda 重组——而主题切换本来就需要一次全树重组。`compositionLocalOf` 会给每帧上百次读取加追踪开销。

**注意 `TvVisual` 里没有 `TvLayout`、没有字号、没有 `TvType`。** 这是 §3 的直接体现。

### 8.2 单一仲裁

```kotlin
val themeCap = LocalTvVisual.current.maxGlass
val renderLevel = effectiveGlassLevel(effectiveGlassLevel(platformMode, level), themeCap)
```

这两行是全方案**唯一**改到渲染决策的地方，且是纯函数式的双向收窄。

### 8.3 组件分支必须收敛在 4 个原语内

**硬规则：`ui/tv/pages/` 里不允许出现 `VisualTheme` / `TvVisual` 引用。**

主题差异只允许出现在：
1. `Modifier.cinemaGlassMaterial`（`GlassControlMaterial.kt:30`）
2. `Modifier.cinemaNavigationLens`（`GlassControlMaterial.kt:142`）
3. `CinemaGlassSurface`（`CinemaGlassSurface.kt:17`）
4. `BackdropLayer`（`BackdropLayer.kt:43`）

卡片、按钮、导航只读**语义值**（`panelFill`、`focusScale`），不读 `theme`。

**源码锁测试**：扫描 `ui/tv/pages/**` 是否出现 `VisualTheme|TvVisual`，命中即失败。

### 8.4 为什么不动 `TvColors`

`TvColors` 158 处引用，且被 3 个单元测试直接断言（`NavigationGlassFaceTest` 依赖 `TvColors.Accent` 与面板面的对比关系）。改成 `@Composable` 访问器会同时破坏非 Composable 上下文与这些测试。

**`TvColors` 保持静态常量不变。** 主题差异放进新的 `TvVisual`（表面色、描边、缩放、时长）。

这样：零风险（158 处一行不改）、零冲突（与并行分支无交集）、够用（Poster 与 Cinema 的差异主体是材质与构图，不是调色板）。

---

## 9. 与并行分支的边界

`feature/tv-ux-performance` 已改（未提交）：`TvRootApp.kt`、`ContentRail.kt`、`HomeScreen.kt`、`SearchScreen.kt`、`CinemaViewModel.kt`、`SearchIndex.kt`、`LibreTvRepository.kt`，新增 `HomeFocusTraversal.kt`、`TvNavigationDown.kt`、`TvSearchChrome.kt`、`SearchChrome.kt`。

| 文件 | 本次是否触及 | 冲突处理 |
|---|---|---|
| `TvTokens.kt` | 是（尾部追加 `TvVisual`，不改既有对象） | 低；纯追加 |
| `TvTheme.kt` | 是（新增 `LocalTvVisual` provider） | 低；单行插入 |
| `ProductionGlassPolicy.kt` | 是（调用侧二次收窄） | **不改签名、不加分支** |
| `GlassControlMaterial.kt` | 是（读 `TvVisual`、BLUR 分面积半径） | 无交集 |
| `FocusMediaCard.kt` | 是（F3 减负、语义值） | **有交集**：对方会加 `metadataOverride`。→ F3 先做（纯删除，不碰签名） |
| `BackdropLayer.kt` | 是（F4、取色 tint、`heroArt`） | 无交集 |
| `ArtworkPrefetch.kt` | 是（F10 + 尺寸） | 无交集 |
| `data/Models.kt`、`MetadataPolicy.kt` | 是（`backdropHiRes`） | **有交集**：`LibreTvRepository` 是对方文件。→ DTO 改动等对方合并 |
| `pages/*.kt` | **否**（硬规则） | 零交集 |
| `HomeScreen.kt`、`CinemaViewModel.kt` | 首页热门要改 | **全部有交集** → 排到对方面后 |
| `SettingsScreen.kt` | 是（主题三选） | 低；对方不改设置页 |

**执行顺序**：P0/P1/P2 无交集，可直接做。P4 起以对方合并后的 `dev-compose` 为新基线。P5 完全等对方。

---

## 10. 测试与金标

### 10.1 会被影响的既有测试

| 测试 | 影响 | 处理 |
|---|---|---|
| `TvTokensTest` | 断言 `TvHeight` 36/44/48/52、`TvMotion.microMs=110/controlMs=180`、9 级 type 逐字段 | **5.5 的字号收敛会改这些断言**，须同批更新 |
| `PosterMotionPolicyTest` | 精确断言 1.06 / 1.015 / 0.97 / 110ms / −3dp | Cinema 的 `PosterMotionTokens` 一律不动；Poster 用 `TvVisual` 的另一组值 |
| `FocusRingContrastTest` | `Ink`/`Ivory` ≥ 3:1 | 两套主题复用同一对，测试不变 |
| `NavigationGlassFaceTest` | 4 常量 + 3 条对比度 | Cinema 常量不动；Poster 导航不经过它 |
| `GlassSurfacePolicyTest` | 所有 Page 的 NAVIGATION/ACTION/PANEL 上限 = FULL | `glassLevelFor` 不改；主题上限是新的一层，单独测试 |
| `ProductionGlassPolicyTest`（12 个） | AUTO 永不 FULL 等 | 不改 `resolveProductionGlass` |
| `ProductionGlassUiTest` | 断言 FULL 必须实际绘制 | Poster 下 FULL 不再绘制 → 需分主题断言 |
| `GlassActionLayoutTest` | 内边距 16/8dp、高度 35–45dp | **不改布局** |
| `FocusMediaCardMotionTest` | 1.06 / 1.015 / 0.97 精确 | Cinema 值不动 |
| `NotoFontsDeviceTest` | 地区 face 加载、真实 bold flags | 字重方案会改它，须扩展为「三个权重的 resolved weight 各不相同」 |

### 10.2 新增测试

| 类型 | 内容 |
|---|---|
| 纯函数 | `resolveVisualTheme` 单调性、纯函数性、不读瞬时信号 |
| 不变量 | `effective ≤ themeCap`；Poster 下任何角色不得产出 BLUR/FULL |
| **布局一致** | 两套主题的 `TvLayout` 逐字段相等；卡片测量尺寸相等（§3.4） |
| 源码锁 | `ui/tv/pages/**` 不得引用 `VisualTheme`/`TvVisual` |
| **字体** | 缺 `wght` 必须得到 Thin（防回归）；三权重 resolved weight 必须不同；`FontSynthesis.None` 下请求 600 不得静默变形 |
| **到期清理** | `usableMetadata` 过期后 `backdropHiRes` 必须为空 |
| 语义 | Poster 下焦点环满足现有对比度；选中/禁用/焦点在 semantics 中可辨 |

### 10.3 金标

| 项 | 处理 |
|---|---|
| Cinema（6 张 × 2 viewport） | 每个视觉批次后重采 + 人工评审；阈值 **mean ≤ 0.020 / changed ≤ 0.08 不变** |
| Poster | **独立目录** `design/ui/goldens/poster/{tv1080,tv4k}/`，自带 manifest 与 `REVIEW.md`，阈值同上 |
| **布局一致** | 两套主题的 mask 必须完全相同（因为布局相同）。这是 §3 的可验证证据 |
| 溯源守卫 | 扩充 `scripts/tests/test_golden_provenance.py` 的 `BASELINES`/`CAPTURES`/`EXPECTED_MASKS`；**不得**放宽阈值或扩大 mask 让测试变绿 |
| 字体批次 | 5.5 的字号收敛会改全部 6 张，**单独一批**，单独评审 |

### 10.4 性能验证（实施后）

- 同设备、同场景、同输入脚本，Cinema / Poster 交错对照；记录源码 SHA、APK SHA-256、构建类型、renderer、实际材质档。
- 场景：冷启动、首页上下/左右、海报切换、搜索输入、流式多源、详情返回、播放器返回、主题切换 3 秒 trace。
- 指标：`frameDurationCpuMs` p50/p95/p99、deadline 相对 jank（按实际刷新率，**不写死 16.67ms**）、内存、bitmap 数量、PSS。
- **主题切换必须是一次尖峰**，之后无持续重组；且**不得产生 measure/layout**（§3.2 的直接验证）。
- 字体：冷启动静止首页 vs 浏览 50 个详情后的 PSS 差值；懒加载改造前后启动耗时。
- E-gate 只说明协议与同环境相对变化；物理 TV 的 T-gate 为 owner 可选运行，未运行须明确写出。

---

## 11. 分阶段

> 每阶段独立可验收、可回滚。不合并成一次大改。

### P0 — 开关与仲裁（零视觉变化）

1. `VisualTheme`、`TvVisual`、`CinemaVisual`、`LocalTvVisual`；默认值等于现值。
2. `resolveVisualTheme` + 不变量测试。
3. `TvTheme` 提供 `LocalTvVisual`。
4. 材质决策插入 `themeCap`（CINEMA 的 `maxGlass = FULL`，行为等价）。
5. `visual_theme` 偏好键，默认 `AUTO`。

**退出条件**：编译通过；6 张金标**逐字节不变**（证明架构无副作用）。

### P1 — Cinema 减负（不动意图，只删浪费）

按风险从低到高，逐项单独提交：

1. F3：卡片有真实图时不再画 `AmbientArtwork`（**预期零像素变化**）
2. F4：背景合并为单次请求
3. F10：修 `ArtworkPrefetch` 的 `960×540` 不匹配（**预期零像素变化，但预取终于有效**）
4. `LiveScreen` 30s 时钟只驱动时钟文本，不整页重组
5. F1：`GlassAction` 的 `level` 改按 role 取，不再硬编码 `FULL`
6. F5：`PosterMotionQuality.FOCUS_ONLY` 在已落 BASIC 的平台启用（**只在低能力平台**）
7. F6：三处焦点缩放收敛为一个权威来源；`TvEffects.focusScale` 启用或删除
8. BLUR 按面积分半径（小控件用更小半径，大面积面板保持）

**退出条件**：编译通过；Cinema 金标重采 + 人工评审；阈值未放宽。

### P2 — Poster 主题

1. `PosterVisual` 值对象（含极轻 `backdropTint`）。
2. 4 个原语内实现不透明面板、双环焦点、无 3D、无阴影、无模糊。
3. `BackdropLayer` 的单图 + 取色 tint + 单竖向 scrim 变体。
4. **布局零改动**（`TvLayout` 与 `TvType` 一行不碰）。

**退出条件**：编译通过；`tvNative` 上 D-pad/OK/Back 全通；Poster 金标首次采集 + 评审；布局一致测试通过。

### P3 — 字体（独立大批）

1. `NotoSans-Medium.ttf` 加入（拉丁真 500）。
2. 字体资产替换为 `NotoSansCJK-VF.ttf.ttc`；`PreloadedNotoFont.weight` 参数化；三权重 FontFamily。
3. 缺 `wght` 得到 Thin 的回归测试。
4. 地区懒加载改造（只建当前地区，500/600 懒建）。
5. `FontSynthesis.None` 硬规则。
6. 字重规则落地（§5.4）。
7. 字号收敛（§5.5）。

**退出条件**：编译通过；API26 与 API33 上三权重的 resolved weight 各不相同；PSS 无回归；全部金标重采 + 人工评审。**7 独立提交、独立评审。**

### P4 — 壁纸

1. `backdropHiRes` DTO + codec + merger + mapper。
2. **`MetadataPolicy.kt:13` 加进过期清理**（安全关键）。
3. `heroArt()`；`BackdropLayer` 改用；解码尺寸钳制。
4. BFF 新增尺寸位（不改老字段）。
5. `ArtworkPrefetch` 尺寸对齐。

**退出条件**：编译通过；到期清理测试；`PresentationPolicyTest` 补 `heroArt` 拒绝用例；金标重采。

### P5 — 首页热门（等并行分支合并后）

1. TMDB trending 路由（BFF）+ 客户端降级链。
2. 豆瓣公开热门接线（懒加载 + 缓存优先 + 熔断静默降级）。
3. 首页新增一条货架（**不改 hero 逻辑**）。

**退出条件**：编译通过；三级降级各有一条测试；豆瓣行不显示评分/排名；门禁不被击穿。

### P6 — 设置与验收

1. 设置页「画面与性能」新增主题三选（自动/影院/画报），显示当前生效与原因。
2. 手机端同步（只读同一偏好）。
3. 完整门禁：`:app:testDebugUnitTest`、`:backdrop:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug`、`backend/tv-bff` 测试。
4. `tvNative` 生产入口 + IME Back + 播放器 Back 阶梯。
5. API 26/29/31/33/36、低内存、省电、软件渲染、Reduced Motion 矩阵。
6. 性能对照 + 证据清单。

### 11.1 依赖

```
P0 ──► P1 ──┐
            ├──► P2 ──► P6
P0 ─────────┤
            ├──► P3 ──┘
            └──► P4 ──┘
                  P5（等并行分支）
```

P3 与 P4 可在 P2 之外并行（不同文件集），但两者都会改金标，**必须串行重采**。

---

## 12. 风险与红线

### 12.1 红线

1. 不新增 Haze/Liquid 或任何依赖；不升级 Compose/Kotlin/compileSdk/minSdk。
2. 不改 `glassLevelFor` 的 `DIALOG`/`PLAYER_OVERLAY = BASIC`；不采样视频 Surface。
3. 不改路由与 Back 阶梯；主题不进返回栈。
4. 不放宽金标阈值；不用 mask 掩盖主题差异。
5. 不把主题判断写进 `pages/`；**不把 `TvLayout`/`TvType` 做成可主题化**。
6. 不让主题依赖瞬时信号。
7. 不为性能关闭 Reduced Motion 下的焦点可见性。
8. 不抓取 IMDb 图片；不打包任何第三方图片；不把 API key 打进 APK。
9. 不为省 5 MiB 承担未收敛的 CFF2 风险；不写「API26 已验证可变字体」除非真有 26 的证据。
10. 不把合成粗体当「有字重」。

### 12.2 主要风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 主题切换触发全树重组，落在动画中 | 单帧超标 | 只在用户操作后切换；不叠加页面转场；必要时一个全屏 alpha 层淡入 |
| **Poster 在 10 英尺上「太素」** | 产品感下降 | P2 前先做 `TvTestActivity` 受控金标 + 人眼评审；**极轻 backdrop tint 就是为此保留的备选** |
| **字体 VF 在 API26 上异常** | 标题变细或字形错乱 | 选 glyf/gvar（方案 A）而非 CFF2；缺 `wght` 得 Thin 的回归测试；API26 真机优先验证 |
| **`PreloadedNotoFont.weight` 忘记参数化** | 三权重全失效，matcher 永远选第一个 | 单独立测试断言三个 resolved weight 各不相同 |
| **字体懒加载改造引入地区切换 bug** | 繁体用户看到简体字形 | 地区切换时按需补建；`NotoRegionPolicyTest` 扩展 |
| 字号收敛（5.5）改全部金标 | 一次改太多，无法定位回归 | 单独大批、单独评审、7 独立提交 |
| **新增 `backdropHiRes` 忘记加进 `MetadataPolicy`** | 授权过期片照继续显示 | 专项测试；这是全链路唯一会出安全问题的点 |
| 首页热门击穿豆瓣门禁 | 通道熔断，搜索也失效 | 懒加载 + 缓存优先 + 一次会话最多一次 + 熔断静默降级 |
| `original` 盲解 4K | 单张 33MB，可能 OOM | 按目标 View 像素宽钳制，永不透传 `original` |
| 与并行分支在 `FocusMediaCard`/`LibreTvRepository`/`HomeScreen` 冲突 | 合并成本 | 按 §9 顺序；F3 先行；P5 完全等对方 |

---

## 13. 待决策

1. **字体走方案 A（+18.28 MiB，API26 无风险）还是 B（+13.13 MiB，CFF2）？** 我推荐 A，因为省 5 MiB 不值得对 minSdk 26 承担未收敛风险。若你有 API26 真机能先验证 CFF2，B 也可以。
2. **字号收敛（5.5）是否随本方案做？** 它会改全部 6 张金标，且叠在并行分支的搜索页改版上要二次重采。可以推迟到对方合并之后。
3. **`h1` 从 32 降到 28 是否可接受？** 这是为了让 9 级留有清晰间隔（22/28/44）。若不可接受，需新增第 10 级。
4. **片名 logo 是否进本方案？** 它是对「电影感」单点收益最大的一项，但需要新 BFF 路由 + 第二种署名，建议独立阶段。
5. **`GlassAction` 的 `level` 改按 role 取（P1-5）是否算行为修正？** 它会改变每个按钮的实际材质（从 BLUR 降到 role 上限），是可见变化，需要单独评审。
6. **首页热门的第 1 层（TMDB trending）是否可接受新增一个 BFF 路由？** 或者只做第 2 层（豆瓣公开）以保持零配置可用。
7. **Poster 的 `backdropTint` 强度**：需要 10 英尺评审确定，可能 3–6% 之间。

---

## 14. 未做

- 未修改任何产品代码、配置或测试。
- 未运行 gradle / 构建 / lint / 单元测试 / 仪器测试。
- 未启动 AVD、模拟器或连接真机；未截图。
- 未采集、修改或评审任何金标。
- 未测量任何帧时间、内存或功耗；文中的外部数值均来自公开文档、官方源码或上游文件实测 `Content-Length`，不是本项目实测。
- 未请求任何需要 key 的外部 API；未下载任何字体或图片。
- 未提交、未推送、未发布。
