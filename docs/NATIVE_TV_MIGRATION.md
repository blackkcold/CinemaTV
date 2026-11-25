# TV 原生迁移落版方案（dev-compose）

> 状态：**历史迁移输入，已被复核计划取代**。基于 `dev-compose` @ `5c24360` 的三份独立只读核查（逐页迁移、删除 web 影响面、性能协议）。原生入口已切换，余下工作以 `docs/superpowers/plans/2026-11-07-native-tv-consolidation-reviewed.md` 为准。
> 范围：TV 展示层从 hybrid WebView 全量迁移到原生 Compose，删除 Web 端，并完成一轮性能验收。手机 UI 不在范围内。

## 0. 基线与约束

| 项 | 值 |
|---|---|
| 分支 | `dev-compose` 是唯一开发主线；`dev` 只作为 Dev 通道从主线快进，不破坏 dev 上的既有测试与成果 |
| 工作区 | 执行前必须干净；每步一个可回滚提交 |
| 已落地 | W0 CI 修复、W1 连接状态机、W2 发现 rail、W3 token / `GlassAction(enabled)` / `TvEmptyState`、W6 豆瓣隔离、HOME 与 DETAIL 局部重建 |
| 测试基线 | JVM 496/496；androidTest 编译通过 |
| 模拟器 | `cinematv-tv`：Google TV API34 arm64、2 GB（实测 1.93 GiB）、1920×1080@320、`-gpu host`（Metal）、无头后台运行 |

## 1. 架构决定（锁定，不再讨论）

1. **TV 展示层 100% 原生 Compose**，删除 WebView / hybrid 路径。
2. **不引入 Haze 依赖。** `Haze2` 在本仓库只是外部参考（`docs/GLASS_V4.md:59-62` 明确未复制源码、未引入依赖）。玻璃继续使用 `backdrop` + `ProductionGlassScene` + `cinemaGlassMaterial` + `ProductionGlassPolicy`。
3. 材质三档语义不变：`AUTO/BASIC/BACKDROP/ENHANCED` → 渲染 `BASIC/BLUR/FULL`；API 34 下 `AUTO → BLUR`；Dialog 与 Player 强制 Basic；**视频 Surface 永不被采样**。
4. 必须保留的原生能力：APK 安装器、豆瓣官方登录 WebView、局域网二维码、SAF 导出、LibreTV 凭据输入、Media3 播放面、手机 UI。
5. 布局锁定：LIVE 频道/导览 **1:2**；DETAIL 剧集 **4 列**；Catalog/Search 保持 `GridCells.Adaptive`；SETTINGS 的 8 个 `index/wireName` 不变，只调整内容归位。

## 2. Wave 0 —— 基础组件（先做）

| # | 组件 | 交付 | 关键约束 |
|---|---|---|---|
| 0.1 | `TvTokens` 补全 | `TvHeight.row = 48.dp`、`TvSize`、`TvWidth`、`TvLayout` 扩展、`TvColors` Panel/Scrim 角色、`TvMotion` 统一（micro 110ms） | 收敛 `TvMotion` / `TvEffects` / `PosterMotionPolicy` 三套数值 |
| 0.2 | `TvText` | `TvTextStyle.toTextStyle()`；先接 `TvEmptyState` 验证 | 页面不再写 `.size.sp` |
| 0.3 | `GlassAction` 下沉 | 移入 `ui/tv/components/GlassAction.kt`，token 化，保留 `enabled/selected/prominent/dense` | 同步更新 `GlassActionLayoutTest` |
| 0.4 | `TvField` | 由 `TvInput` 派生，默认 `TvHeight.field`、错误/禁用态 | Search/Settings 删除显式高度 |
| 0.5 | `TvChip` / `TvChipRow` | 筛选、历史、标签统一 36dp + 12dp 间距 | 保留 `catalog-more:*` testTag |
| 0.6 | `TvPageHeader` / `TvSectionHeader` | 标题、副标题、右侧动作 | 6 个页面共用 |
| 0.7 | `TvStatus` | Empty / Loading / Error / StatusLine 四态 | 页面不再手写状态文案 |
| 0.8 | `TvDialogFrame` / `TvConfirmDialog` | 26dp 圆角、480/600dp 宽、Cancel 默认焦点 | Dialog 内玻璃为 Basic |
| 0.9 | `ContentRail(action)` + 卡片/导航 token 化 | header action slot；不改 `rail:$title` region key | 书签不能失效 |
| 0.10 | `TvHeroModel` + `TvPager` + `TvPagerIndicator` | 纯数据模型 + 手动翻页（不自动轮播） | `featured.size > 1` 才启用 |
| 0.11 | `TvAboutSection` | 简介、演员、季集、片源、评分来源；空则省略 | 使用已有 `actors`，不编造 |
| 0.12 | `TvProgressBar` | LIVE/PLAYER 共用 4dp 进度条 | — |

## 3. Wave 1–3 —— 逐页迁移

每页统一 DoD：

- **D1** 页面文件不再出现 raw 字号/间距/圆角/控件高度（光学与艺术常数除外）。
- **D2** 空、加载、错误三态齐全。
- **D3** 可禁用动作真的 `enabled`（D-pad 跳过、40% 透明）。
- **D4** 该路由的 focus / Back / 返回恢复回归通过。
- **D5** 受影响 golden 重新捕获并人工评审。
- **D6** 不新增依赖、不采样视频、不跨 Dialog 采样。

| 页 | 主要改动 | 测试 / 金标 |
|---|---|---|
| **HOME** | Hero 用 `TvHeroModel` + pager；「为你精选」加「查看全部」，删掉重复的「更多电影」；`换一批` 加载中禁用；显示 `discovery.message` 错误态 | 01/02 golden、`ContentRail*` |
| **DETAIL** | `selected == null` 拆成加载/不可用两态；新增 About（演员/季集）；actions 用 `TvActionRow`；picker 用 `TvDialogFrame` | 03/04 golden、`ManualWorkAssociation*`、`WorkNavigation*` |
| **MOVIES/SERIES** | `TvPageHeader`；`全部` 同时清 category/year/rated（现有 bug）；loading/error 三态；paging token 化 | 05/06 golden、`LibraryCatalogControlsUiTest` |
| **SEARCH** | `TvField`；空 query 禁用搜索；历史/筛选/tag 用 `TvChipRow`；无结果/错误用 `TvStatus` | 08 设备截图、paging 用例 |
| **EXPLORE** | `TvPageHeader`；空/加载态；coverflow 常数进 token；pager indicator；dialog token | 07 golden |
| **LIVE** | 分栏 1:2；当前节目去重（现在会渲染两次）；无源/无频道/无节目三态；`TvProgressBar` | 09 设备截图 |
| **LIBRARY** | `TvPageHeader`；统一空态；清空确认用 `TvConfirmDialog`；复核面板 token 化 | 10 设备截图、清理用例 |
| **SETTINGS** | 推荐看板只保留 section 5（现在 3/5 各一个）；section 7 纳入统一 `when`；连接/登录/保存/添加源加 disabled；子面板 token 化 | 11/12 golden、`SourceSubscriptions*`、`UpdateSettings*`、`DiagnosticsUiTest` |
| **PLAYER** | 抽 `TvPlayerOverlay`；4 个 raw hex → `TvColors`；新增 buffering 态；track panel 用 `CinemaGlassSurface(sampleBackdrop = false)` | 13/14 设备截图、`NativePlaybackSessionTest` |

## 4. Wave 4 —— 切入口 + 删除 Web

7 步，每步一个可回滚提交，每步以「可编译 + 指定测试绿」为检查点。

| 步 | 动作 | 规模 | 风险 |
|---|---|---|---|
| 1 | `MainActivity` 直接 `TvRootApp(model)`，**同时把 BackHandler 改为 `model.page != Page.HOME`** | ±4 行 | **高**：不改 Back，电视上 HOME 以外返回会直接退出应用 |
| 2 | 删 4 个 WebView 仪器测试，重写 `TvLaunchSmokeTest` 为纯原生（保留类名，phone 矩阵仍在用） | −1,178 / +30 | 中 |
| 3 | 删 `hybrid/` 11 个文件 + 3 个单测；先把 `ProgramGuideWindow` 迁到 `ui/tv/pages` | −1,662 | 低 |
| 4 | 删 `app/tv-web.gradle`、`preBuild` 接线、`assets srcDir`、`androidx.webkit` 依赖 | −48 | 中 |
| 5 | 先把 `PRIVATE_BUILD = false` 产物闸门迁入 `scripts/updates/build-unsigned-candidate.sh`，再删 `frontend/tv-web/**` 与 `scripts/hybrid/**` | −5,881 | 中 |
| 6 | 删 `hybrid-web.yml`、`hybrid-android.yml`；修 **8 个文件 10 处** `setup-node` 的 `cache-dependency-path` | −267 / +20 | 中：漏一处 CI 立刻失败 |
| 7 | 删 `docs/HYBRID_TV.md`，重写 README / ARCHITECTURE / AGENTS / LOCAL_BUILD_DEFAULTS 等 | −208 / +90 | 低 |

合计约 **9,240 行 / 56 个文件删除，150–200 行改写**。

不能删除：`ui/tv/**`、`TvPlayerPage`、`TvTestActivity`、`design/ui/goldens`、`backdrop`、`ui/douban` 登录 WebView、更新/诊断 provider、`UiFixtureServer`。

删除前必须迁移的覆盖：

- `NativePlaybackSessionTest`、`SourceSubscriptionsUiTest#nativeTv` 从 `scripts/hybrid/tv_regressions.py` 迁入 `scripts/qa-device-matrix.py` 的 TV 类列表。
- `PRIVATE_BUILD` 产物断言从 `scripts/hybrid/artifacts.py` 迁入 `scripts/updates/build-unsigned-candidate.sh`。

## 5. 性能验收

### 5.1 环境（已就绪）

```sh
emulator -avd cinematv-tv -port 5554 \
  -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu host -memory 2048 -cores 4 -vsync-rate 60 -no-metrics
# serial: emulator-5554
```

- 2 GB 已在 guest 内验证（`MemTotal: 2025912 kB`）。
- GPU 为 host Metal：`GLES: Google (Apple), Android Emulator OpenGL ES Translator (Apple M4), OpenGL ES 3.0 (4.1 Metal - 90.5)`。
- 不加 `-lowram`：它会触发 `isLowRamDevice`，把玻璃强制降为 Basic，改变被测对象。
- 本机缺口：Gradle 8.13 与 `timeout`。执行前安装 `gradle-8.13-bin`，脚本内用自带的 `bounded()` 包装，不依赖 GNU coreutils。

### 5.2 测量设计

- **主指标**：`Window.FrameMetrics` 全量帧（`TOTAL_DURATION`、`INPUT/ANIMATION/UNKNOWN`、`SYNC/COMMAND/SWAP`、`GPU/DEADLINE`@31+、回调丢失）。
- **交叉校验**：`dumpsys gfxinfo` 聚合 + `framestats` 最近约 120 帧（**不能**当作 60 秒分布）。
- **宿主**：仅测试代码新增 `TvFrameTrace.kt` 与 `TvNativePerformanceAcceptanceTest.kt`，运行在 `TvTestActivity` + `TvRootApp`。
- **协议**：3 次重复；每次 HOME 连续 60 秒 D-pad 遍历（8 键循环）→ 进 DETAIL → Back → 播放器 20 秒 → 控制条 8 秒 → 逐层返回；记录 PSS、thermal、SurfaceFlinger、APK/源码/设备哈希。测量之间**不调** `waitForIdle()`。
- **证据目录**：`qa/native-tv-perf/<timestamp>/`，含 `run.json`、`frames.csv`、`inputs.csv`、`gfxinfo-*.txt`、`meminfo-*.txt`、`summary.json`、`SHA256SUMS`、每个 rep 的 `DONE`/`FAILED`。

### 5.3 门槛

| 指标 | 模拟器（E-gate，debug） | 真机 MiTV（T-gate，release） |
|---|---|---|
| HOME P50 | ≤ 12 ms | ≤ 8 ms |
| HOME P90 | **≤ 25 ms** | **≤ 16.67 ms** |
| HOME P99 | ≤ 50 ms | ≤ 33 ms |
| Jank（>16.67ms） | ≤ 20% | ≤ 10% |
| 焦点 → 首帧 P90 | **≤ 80 ms** | **≤ 100 ms** |
| DETAIL 进入 P90 | ≤ 300 ms（含 210ms 设计延迟） | 同 |
| DETAIL 返回 P90 | **≤ 200 ms** | **≤ 250 ms** |
| 播放器返回 P90 | **≤ 250 ms** | **≤ 300 ms** |
| PSS | ≤ 500 MB | ≤ 350 MB |
| 回调丢失 | 0 | 0 |

> 阈值口径已被复核计划取代：jank 按设备真实刷新率的 frame deadline 计算，不使用固定 16.67 ms；性能声明必须同时记录真实绘制档位与 `sourceReady`。

**不可由模拟器宣称**：真实 60fps、GPU/blur 成本、热降频、4K/HDR、视频解码流畅度、真实遥控/IR 延迟、2 GB LMK 行为、armeabi-v7a 表现。

## 6. 测试与金标

- 6 张 golden（HOME / DETAIL / MOVIES / SERIES / DISCOVERY / SETTINGS）× 2 viewport 全部会变：重新捕获、人工评审、更新 manifest 与 `REVIEW.md`；**禁止**放宽 0.020 / 0.08 阈值或只改 hash。
- 预期失败的既有断言：`GlassActionLayoutTest`（40dp / 35 / 36 / 44 / 45）、`TvUiRegressionTest` 的中文文案与 12 张截图、`FocusMediaCardMotionTest` 的 1.06 / .97。
- 保留而非替换：`ProgramGuideWindow` 单测、`CatalogBrowsePagingTest`、`NativePlaybackSessionTest`、`LibreTvProtocolTest`、`DoubanDeviceSecurityTest`。

## 7. 提交粒度与工期

| 阶段 | 内容 | 工期 |
|---|---|---|
| Wave 0 | 12 个基础组件，按 0.1–0.12 分 commit | 3–4 天 |
| Wave 1 | HOME → DETAIL → MOVIES/SERIES → SEARCH | 6–7 天 |
| Wave 2 | EXPLORE / LIVE / LIBRARY | 3 天 |
| Wave 3 | SETTINGS | 2.5 天 |
| Wave 4 | PLAYER → 切入口 → 删 web（7 步） | 4.5 天 |
| 收尾 | golden 重捕 1 天 + 性能验收 1.5 天 + 文档 1 天 | 3.5 天 |
| **合计** | | **约 23–25 人日** |

每个 commit 的检查点：

1. `:app:testDebugUnitTest` 全绿。
2. `:app:assembleDebug :app:assembleDebugAndroidTest` 通过。
3. 变更页对应的设备用例（golden 或截图）通过。
4. 净化后 `:app:lintDebug` 无新增 error。

## 8. 主要风险与处置

| 风险 | 处置 |
|---|---|
| cutover 后 Back 退出应用 | 第 1 步同时改 BackHandler；`TvTestActivity` 无法替代生产验证 |
| golden 全面变化 | 预期内，逐张评审；不放宽阈值 |
| `GlassActionLayoutTest` 必挂 | 随 token 化同步更新断言（36/44/48） |
| Hero 大字号裁切 | 不固定硬高度，保留现有缓解 |
| 播放器无法测视频流畅度 | 只测返回与控制条；视频证据留真机 |
| 删除工具链丢覆盖 | `NativePlaybackSessionTest`、`SourceSubscriptionsUiTest#nativeTv` 迁入 `qa-device-matrix.py` |
| CI 缓存路径漏改 | 8 文件 10 处逐条核对；`git grep frontend/tv-web .github` 必须为空 |
| 书签失效 | `ContentRail` 标题与 `rail:$title` region key 保持不变，或显式迁移 |
| Dialog 采样 | 保持 Basic；`currentProductionGlassScene()` 的跨 owner 拒绝不得绕过 |

## 9. 执行顺序（确认后从 0.1 开始）

```
Wave0.1 … 0.12
  → HOME → DETAIL → MOVIES/SERIES → SEARCH
  → EXPLORE → LIVE → LIBRARY
  → SETTINGS
  → PLAYER
  → 切入口（+ TV BackHandler）
  → 删除 web（7 步）
  → golden 重捕
  → 性能验收（E-gate → 真机 T-gate）
  → 文档收口
```

## 10. 不在本方案内

- `dev` 只从 `dev-compose` 快进；发布集成由用户确认后单独审批。
- 不引入 Haze 库、不复制其源码。
- 不改手机 UI。
- 不修改历史发布记录（`docs/releases/v0.9.0-dev.1.md`）。
- 不宣称模拟器结果等于真机 60fps 或物理 TV 认证。
