# CinemaTV 原生 TV 整合实施方案 (v3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `dev-compose` 上把 TV 展示层收口为原生 Compose，保留并优化现有液态玻璃，建立可复现的性能与设备验收证据，然后删除不再可达的 TV WebView 层；手机 UI、LibreTV 上游和发布签名边界不动。

**Architecture:** 原生 `TvRootApp` 作为唯一 TV 入口；`ui/tv` 页面、共享设计令牌、`ProductionGlassScene` / `cinemaGlassMaterial` 三档材质；Media3 原生播放器独立于 Compose 场景；WebView 仅保留豆瓣登录等非 TV 主界面用途。TV Web 代码在完成 delta 对照、覆盖迁移与删前真机冒烟后归档并从主线删除。

**Tech Stack:** Kotlin 2.2.21、AGP 8.13.2、Compose BOM 2026.03.01、Media3 1.11.1、minSdk 26 / target 35 / compile 36、vendored `backdrop`（上游 `896a94a`，标注 1.0.6）、GitHub Actions emulator lanes。

**Spec:** `docs/NATIVE_TV_MIGRATION.md`（分支落版方案）+ `CinemaTV-技术路线与液态玻璃性能研究.md`（研究文档，外部输入）。

---

## 0. 复核结论摘要

v3 已吸收 5 路只读核查与 3 路交叉复核。三路复核的主要 blocker 全部纳入，未拒绝任何一条；少数 findings 通过调整顺序解决。

| 复核 | 关键 blocker / should-fix | v3 处置 |
|---|---|---|
| 红队 | 治理变更未提交；API lane 顺序反了；G4.1 编辑点漏三处；G3.5 的短 delta 不是框架对比；`ProgramGuideWindow` 不存在；删 Web 前无真机证据；选择性吸收未排期；`TvRootApp` 两个参数在切入口后变死；`GLASS_V4` 不是整体退役；JVM 数不可复现；金标重捕重复排期；`GlassSurfaceLevel` 与 `sampleBackdrop` 冲突；工期漏算 Track B/C | G0.0、G3.0、G4.1 五处编辑、G3.5 可选 delta、G4.3 改 `HybridProgramGuide`、G3.5/G6.3、G6.8、G2/G3 清理、G6.8 合并 V4 有效章节、G0.2 用 `TEST-*.xml`、G2 D5 只评审、G1.13/G6.4 统一 API、§9 重算 |
| 性能 | E-gate 绝对毫秒门槛与软件渲染器不相容，且被用来授权 Blur；jank 固定 16.67ms 不随刷新率；3 次重复撑不起 10%/1ms；`truncated` 误用；输入→首帧定义会被一个刷新周期绕过；`FrameTimingRecorder`/`analyze.py` 不能直接复用；A/B 内存少算 WebView renderer；Haze 可能在 AAR metadata 阶段就要求 compileSdk 37；Blur 面积无数字；T-gate 基础设备行不可失败；热/内存不可证伪；60fps 无呈现时证据 | E-gate 改协议 + 配对相对差；jank 改 `DEADLINE`/`1e9/refreshHz`；6 次噪声 + ABBA + bootstrap；tail settle；三时间戳；`perf_common` + `app/src/perf`；按 UID 汇总内存；Haze 0.5 天预检；`MAX_PANEL_AREA_PX=300_000` 等；T-gate 数字与硬件命名；热/内存阈值；Perfetto/SurfaceFlinger 证据 |
| TV UX | 根 `BackHandler` 注册顺序会让播放器面板被整层跳过；IME 未建模；没有任何 lane 真正跑 Android TV 镜像，`isTv` 永远 false；G2 D4 没有逐路由测试；`TvLaunchSmokeTest` 在 phone lane 盲测；G3 后 hybrid CI 必红；焦点恢复只有 rail 是 key 级；Dialog 焦点陷阱无测试；`ProgramGuideWindow` 名称错；卡片焦点环对比度不足；每屏一块 Blur 不可表达；API 回退矩阵只测“能画” | BackHandler 放在 `when` 之前、IME 第一层；`tvNative` lane + `run-tv-emulator.sh` 迁移；G2 路由×用例表；拆分 `TvLaunchSmokeTest`/`MobileLaunchSmokeTest`；hybrid workflow 标记 expected-red；`RegisterLazyFocusRegion` 扩展；Dialog 参数化测试；`ProgramGuideWindow`；双色焦点环 + 对比度断言；`glassLevelFor` 静态表；每档同一交互脚本 |

---

## Global Constraints

- minSdk 26；API 26 安装与兼容路径必须继续可用。
- **G0.0 先提交治理**：`AGENTS.md` + 本方案必须在打基线 tag 前进入 git；tag 必须包含新方向。
- 本方案不引入 Haze / Liquid 生产依赖；不升级 Compose / Kotlin / compileSdk 作为迁移前提。Haze 只做独立 worktree 预检 + PoC。
- 永不采样 Media3 视频 Surface；播放器覆盖层与 Dialog 保持 Basic。
- TV 生产入口只允许 `TvRootApp`；`TvTestActivity` 只用于受控组件/金标测试，不能替代生产入口。
- `TvRootApp` 是 Back 的唯一 owner；IME → Dialog/窗口 → 播放器面板 → 播放器控制条 → 路由的顺序不可交换。
- E-gate 不使用绝对毫秒门槛；只做协议完整性 + 同 run 配对相对差。T-gate 只在真机、release-like 构建上做产品验收。
- jank 以 `DEADLINE`（API31+）或 `1e9/refreshHz`（低版本回退）为准；禁止再用固定 16.67ms。
- 金标阈值保持 mean ≤ 0.020、changed ≤ 0.08；G2 只评审截图，G6.1 做唯一一次权威重捕。
- 不改手机 UI；共享组件移动必须让 phone lane 保持绿色。
- T-gate 通过前不合并到 `dev`；不重写历史；不覆盖他人提交。
- 每条性能结论必须带源码 SHA、APK SHA-256、设备/AVD、实际 renderer、材质档、有效样本数。
- 凭据、签名材料、私有端点不得进入日志、产物或 Web bundle。
- Apache-2.0 / MIT 合规；不复制 GPL 的 DpadTV / Jellyfin 实现代码。

## Review Focus

1. **TV Back**：HOME 与非 HOME、IME 可见、Dialog 打开、播放器面板/控制条打开时的返回行为。期望：IME → 隐藏；Dialog → 只关自己；播放器面板 → 关面板；控制条 → 隐藏；路由 → 回 HOME/section；HOME 才退出。
2. **焦点恢复**：DETAIL / PLAYER / LIBRARY / Dialog 返回后回到打开它的精确卡片；grid/list 在条目被删除或重排后回到最近邻，而不是错误索引。
3. **玻璃降级**：API 26/29/31/33、低 RAM、省电、热压下 Basic 仍可读、可聚焦、无黑块、无交互丢失；三档材质不改变焦点目标集合与 Back 结果。
4. **LibreTV 部分失败 / 取消 / 过期会话**：部分来源失败要带来源级错误；取消不得交付过期结果；同名作品切源后不得续播错集。
5. **删 Web 的副作用**：所有 Android workflow 不再依赖 Node；迁移后的测试在原生 lane 真跑；产物闸门仍断言 `PRIVATE_BUILD=false`；金标 provenance 仍有效；真 TV lane 仍存在。

对应测试：1 → G3.1/G3.4 + `TvImeBackTest`/`TvPlayerBackLadderTest`；2 → G2 路由×用例表 + `TvFocusRestorationTest`；3 → G6.5 + G6.7；4 → B2；5 → G4.1/G4.2/G6.3。

---

## 1. 事实基线（2026-11-07 核查）

### 1.1 分支与工作区

| 事实 | 证据 | 影响 |
|---|---|---|
| `HEAD = origin/dev-compose = 5c24360` | `git status` / `git log` | 基线 commit 明确 |
| 工作区**不干净**：`M AGENTS.md`、`?? docs/NATIVE_TV_MIGRATION.md`、`?? docs/superpowers/` | `git status --short` | 直接打 tag 会把旧 hybrid 方向留在 tag 里；必须先 G0.0 |
| dev-compose 相对 dev 为 10 个提交、82 文件、+4,489/−135 | `git diff --stat origin/dev...HEAD` | 不是纯 UI 分支，含豆瓣公开数据、诊断、连接状态；必须选择性吸收 |
| `MainActivity.kt:76` 恒调用 `HybridTvRootApp(model)` | 代码 | 原生入口尚未接 |
| `TvRootApp` 只在 hybrid 不满足条件或 `nativeFallback` 时出现 | `HybridTvRootApp.kt:80-83` | 原生页面已存在，但生产不可达 |
| `TvRootApp` 自身 0 个 `BackHandler`；`TvPlayerPage.kt:123-131` 有自己的三段式 Back | grep | 切入口必须补根 Back，且顺序不能抢播放器 |
| `MainActivity.kt:71` 的 BackHandler 只对手机生效 | 代码 | TV Back 目前由 hybrid host / gateway 承担 |
| `HybridPolicy`：API ≥ 29、WebView major ≥ 111、WebMessageListener | `HybridPolicy.kt:8-20` | API 26 本来就回落原生 |
| 无持久化 native/hybrid 开关 | prefs / BuildConfig / 设置均无 | 切入口是编译期决定，不是运行时开关 |

### 1.2 页面与组件

| 事实 | 证据 | 影响 |
|---|---|---|
| 10 个 TV 页面全部已有原生 Compose 实现，且 `ui/tv/**` 与 `TvPlayerPage.kt` 不 import `hybrid` / `android.webkit` | 页面清单 | 这是“收口 + 打磨”，不是从零移植 |
| Wave 0 的 12 个基础组件全部未实现；`TvActionRow` 也未实现 | grep 0 命中 | G1 是真实工作量 |
| `GlassAction` 仍在 `ui/CinemaWidgets.kt:94`，21 个文件约 98 个调用点 | 代码 | 0.3 是真实工作量 |
| `TvTokens.kt` 已有 90 行，但 `TvMotion` 0 使用、`TvType` 仅 `TvEmptyState` 使用 | 代码 | 令牌存在 ≠ 已采纳；页面仍有 66 处显式 `fontSize`、254 处 dp |
| LIVE 当前是 `.36/.64`，不是 1:2；当前节目渲染两次 | `LiveScreen.kt:87,100,107-118` | 页面任务含真实 bug |
| DETAIL 4 列已实现 | `DetailScreen.kt:116` | 5c24360 的唯一实质改动 |
| Catalog “全部”只清 category/year，不清 rated | `CatalogScreen.kt:47` | 真实 bug |
| SEARCH 提交按钮始终 enabled | `SearchScreen.kt:50` | 真实 bug |
| SEARCH 入口第一焦点是输入框，`TvInput` 聚焦即可能启动 IME | `SearchScreen.kt:37-50`；`CinemaWidgets.kt:201-213` | 需要 IME/Back 设计 |
| SETTINGS 有两个推荐看板入口，section 7 特殊分支 | `SettingsScreen.kt:150,166,85-87` | 页面任务要收口 |
| PLAYER 无 buffering 态，4 处 raw hex | `TvPlayerPage.kt:146-148,250,320,360` | 页面任务要补 |
| `libraryManagement` / `settingsInitialSection` 只由 `HybridTvRootApp.kt:111-112` 传入 | 代码 | 切入口后 `LibraryProgressReviewScreen` 变死代码；G2 应只做 `LibraryProgressReviewDialog` |

### 1.3 玻璃

| 事实 | 证据 | 影响 |
|---|---|---|
| 四个核心玻璃文件在 dev / main / dev-compose 完全一致 | git blob `072cf61c` / `4e9a793e` / `7aaea649` / `fc51eacb` | 玻璃观感问题不是分支差异 |
| `backdrop` 是本地 vendored 模块，不是 Maven 2.0.1 | `settings.gradle.kts:7`；`upstream-lock.json:2-4`；`NOTICE.md:3-5` | 研究文档的 Backdrop 2.0.1 对比不能直接套用 |
| 上游固定 `896a94a`，标注 1.0.6，有本地修改 | `NOTICE.md` | Haze PoC 必须按本仓库实际版本做 |
| 无 Haze / Liquid 依赖 | gradle / kt / toml 0 命中 | PoC 不是“替换一个已有依赖” |
| `AUTO` 永远只到 BLUR，不会升 FULL | `ProductionGlassPolicy.kt:59-74` | 用户选“自动”看不到完整光学；文档不能写“导航默认 Full” |
| `sampleBackdrop=true` 生产 0 调用；所有 `CinemaGlassSurface` 面板都是 Basic | `CinemaGlassSurface.kt:15-23`；9 个调用点 | “玻璃不像玻璃”主要是使用策略问题 |
| 播放器页不创建 scene，SurfaceView 不被采样 | `TvRootApp.kt:48,60`；`TvPlayerPage.kt:231-247` | 必须保持 |
| Dialog 跨 owner 被拒绝，且 `CinemaGlassSurface` 默认 null provider | `ProductionGlassScene.kt:74-76`；`CinemaGlassSurface.kt:21-23` | Dialog Basic 是硬约束，不是性能妥协 |
| 生产死代码：`GlassSheen`、`GlassRenderPolicy`、`GlassFailureLatch`、`resolveGlassQuality` | grep 0 生产调用 | 优化前先收口生产路径 |
| `GlassRenderPolicy.kt:6-8` 仍有 `MAX_WIDTH_DP=600`、`MAX_HEIGHT_DP=80`、`MAX_AREA_PX=240_000` | 代码 | 不要直接删；把面积常数迁进 `ProductionGlassPolicy` |
| `GLASS_V4.md` 描述 V4 depth/lens 契约，含“实际折射什么 / 成本与回退 / 手机控件”等仍有效章节；`GLASS_PRODUCTION.md` 描述当前生产图 | 两文档 | 不能把 V4 整篇标成“已退役”；G6.8 合并有效章节 |
| 无 frame budget / FPS cap / GPU 时间控制 | 代码 | 性能治理只能靠材质档、面积、失效范围 |

### 1.4 Web 资产与 CI

| 事实 | 证据 | 影响 |
|---|---|---|
| Web 删除集合 = 56 文件 / 9,240 行 | 逐组计数 | 迁移文档估算准确 |
| CI 引用是 **10 个 workflow / 15 处** `cache-dependency-path`，不是 8/10 | grep | 删两个 hybrid workflow 后仍有 **8 文件 13 处**必须改 |
| `preBuild` 对所有 variant 跑 `npm ci` | `app/build.gradle.kts:17`；`tv-web.gradle:9-19` | 删 Web 后 Android 构建不再依赖 Node，是净收益 |
| 豆瓣登录、更新器、二维码、SAF、LibreTV 凭据均不依赖 `hybrid/` / `frontend/tv-web/` | 逐项定位 | 删除只影响 TV 浏览展示层 |
| `PRIVATE_BUILD=false` 只在 `scripts/hybrid/artifacts.py:143-145` | 代码 | 删除前必须迁移 |
| `NativePlaybackSessionTest` / `SourceSubscriptionsUiTest#nativeTv...` 只在 hybrid lane | `scripts/hybrid/tv_regressions.py:17-24` | G3.0 就要迁入原生 TV lane，不能等 G5 |
| `TvLaunchSmokeTest` import `HybridPolicy` / `HybridDom`，且在 phone lane | `TvLaunchSmokeTest.kt:12`；`HybridHostUiTest.kt:678`；`qa-device-matrix.py:8` | 必须拆成 TV/phone 两条 |
| 唯一真正的 Android TV AVD 启动逻辑在 `scripts/hybrid/run-tv-emulator.sh:144-165` | 代码 | 删除 hybrid 前先把它迁成 `tvNative` lane |
| `tv-smoke.yml` 的 tv1080/tv4k 用 `target: default` + `profile: pixel_2`，是手机镜像改窗口 | `tv-smoke.yml:119-121` | 这些 lane 的 `isTv` 为 false，不能证明生产 TV 入口 |

### 1.5 测试、性能与设备

| 事实 | 证据 | 影响 |
|---|---|---|
| HEAD `5c24360` 在 GitHub 零 workflow、零 check-run | `gh api .../check-runs`；`gh run list` | 基线未验证，G0 必做 |
| JVM 静态计数：app 490、backdrop 9 = 499；文档写 496/497/506 均不可复现 | grep `@Test` | 以 G0.2 的 `TEST-*.xml` 汇总为唯一真值 |
| E-gate / T-gate / `TvFrameTrace` / `TvNativePerformanceAcceptanceTest` / `qa/native-tv-perf/` 全部只存在于文档 | grep 0 命中 | 性能验收是待实现工作 |
| 现有 `scripts/glass-performance` 有卫生检查与 9 run 三档，但只 gate 协议完整性 | `analyze.py` | 复用采集器与证据格式，但要新建 `perf_common`，不能直接 import |
| `FrameTimingRecorder` 在 `app/src/glassBenchmark`，`internal`，输出路径写死 | `FrameTimingRecorder.kt` | 需迁到 `app/src/perf` 并参数化输出 |
| `analyze.py` 硬断言 `glassBenchmark` / 非 debug / 1920×1080@320 / 22/24 窗口 | `analyze.py:32-41` | 不能直接用于 debug E-gate 或 4K |
| 设备矩阵只有 API 26/30/33；API 29/36 只在 hybrid workflow；API 31 无 lane；API 34 从未出现 | `qa-device-matrix.py:7-16`；workflows | G3.0 必须先建 lane |
| 金标实际捕获 15 张，只对比 6 张；阈值 0.020/0.08 | `TvUiRegressionTest.kt`；`compare-ui-goldens.py` | G2 只评审截图；G6.7 权威重捕 6 张 |
| `isMinifyEnabled = false` | `app/build.gradle.kts:122` | “release-like”不是 R8 优化包；R8/Baseline Profile 另立项目 |
| 当前 worktree 无 Gradle、Android SDK、emulator/adb，Docker daemon 不可用 | 本机检查 | 本地不能构建/跑模拟器；G0 必须先解决执行环境 |

### 1.6 两份输入文档与仓库旧治理的主要偏差

| 来源 | 声明 | 实际 | 处理 |
|---|---|---|---|
| 迁移文档 | W0/W1/W2/W3/W6 已落地 | 大多属实，但 Wave 0 组件 0.1–0.12 全未做；HOME 只 +29/−2，DETAIL 只 +3/−1 | 本方案以代码为准重排 |
| 迁移文档 | 测试基线 496/496 | 静态 490+9=499，且 HEAD 无 CI | G0.2 用 `TEST-*.xml` 实测 |
| 迁移文档 | CI 8 文件 10 处 | 10 文件 15 处；删除后 8 文件 13 处 | 删除步骤按实际清单 |
| 迁移文档 | 本地 API34 AVD 已就绪 | 本机无 SDK/AVD；API34 不在任何 workflow | G0 解决；否则明确降级 |
| 迁移文档 | `ProgramGuideWindow` 单测保留 | 该符号不存在；真实是 `HybridProgramGuide.window(...)` | G5.3 迁成 `ui/tv/pages/ProgramGuideWindow.kt`，让 `LiveScreen` 使用并保留测试 |
| 研究文档 | Backdrop 2.0.1 | 仓库是 vendored `896a94a`/1.0.6 | 版本对比按仓库实际 |
| 研究文档 | 保留 HTML 作实验/管理 WebUI | 当前无管理 WebUI；保留会产生死代码与 npm 构建税 | 归档后删除，实验走独立分支 |
| 研究文档 | 60fps 目标 | 模拟器不能证明真机 60fps | 保留为目标，验收分 E/T 两档 |
| 旧 `AGENTS.md` | 2026-11-06 hybrid 是已批准方向 | 2026-11-07 用户要求刷新为原生主线 | G0.0 提交新 `AGENTS.md`；旧方向不再有效 |
| 两份文档 | “6 张 golden” | 6 张对比、15 张捕获 | 重捕 6 张，评审 15 张 |

### 1.7 方向治理

仓库根 `AGENTS.md` 已在 2026-11-07 按本方案刷新：**原生 Compose TV 主线，hybrid TV 冻结并在 G5 删除**。但该修改目前仍是工作区 modified，**尚未提交**。`5c24360` 这个 commit 里仍是旧 hybrid 方向。因此：

- **G0.0 必须先提交** `AGENTS.md` + 本方案；否则 `baseline/native-tv-5c24360` 会 tag 到旧治理。
- 打 tag 后必须验证 `git show <tag>:AGENTS.md` 含 “native Compose”，且 tag tree 含本方案。
- 若后续用户改回 hybrid，必须先回滚 `AGENTS.md` 与本方案 D1，不得双主线并行。

---

## 2. 决策记录（冲突裁决）

| ID | 冲突 | 裁决 | 理由 | 被放弃的方案 |
|---|---|---|---|---|
| D0 | 旧 `AGENTS.md` hybrid 方向 vs 新原生方案 | **G0.0 提交新 `AGENTS.md` + 本方案，再打 tag** | 治理必须先于基线；否则 tag 记录旧方向 | 直接打 tag；双主线并行 |
| D1 | 迁移文档“立即全删 Web” vs 研究文档“保留 Web 做实验” | **G3 切入口 → 15–30 分钟真机冒烟 + archive tag → G4 立即删除 Web** | 用户已选“G3 切入口后立即删除”；原生页面完整；archive 保留回滚；完整 A/B 因 UI 差异不做排名 | 先做长周期 A/B；永久保留 |
| D2 | 迁移文档“不引入 Haze” vs 研究文档“Haze PoC 后再决定” | **生产继续 backdrop；先 0.5 天 AAR 预检，再决定是否做隔离 PoC；未达标不迁移** | 仓库 backdrop 是 vendored 1.0.6；Haze 2.1 可能要求 compileSdk 37；不能拿库新旧当理由 | 直接换 Haze；完全不评估 |
| D3 | 迁移文档“面板保持 Basic” vs 研究文档“玻璃要有真实折射” | **功能优先玻璃**：G1/G2/G5 先实现完整玻璃（大面积 Blur/Full 是设计目标）；G6 才按测量引入面积/每屏上限与降级；Dialog/Player/video/API 是硬约束 | 用户明确要“大面积敷玻璃先做好，再考虑性能降级”；不能一开始就砍成 Basic | 一开始每屏只一块 Blur；所有面板 Full 且永不降级 |
| D4 | 迁移文档 E/T 门槛 vs 研究文档更严格的产品目标 | **E-gate = 协议 + 同 run 配对相对差；T-gate = 真机 deadline 驱动验收** | 模拟器只能证明回归趋势；绝对毫秒在软件渲染器上不可达 | 用模拟器宣布 60fps；固定 16.67ms jank |
| D5 | 迁移文档“只做 TV” vs 研究文档“LibreTV 契约/设计系统” | **TV 主线 + LibreTV 契约审计并行；G6.8 做选择性落地清单** | 契约问题影响播放可靠性；dev-compose 不是纯 UI 分支 | 全量合并 dev-compose；把 LibreTV 重写进主线 |
| D6 | 迁移文档“切入口一行” vs 研究文档“入口/测量要真实” | **切入口同 commit 补根 Back + IME 层 + 移除测试宿主 handler；先建真 TV lane** | `TvRootApp` 0 BackHandler；手机镜像的 `isTv=false` | 只改一行；用 TvTestActivity 代替生产验证 |
| D7 | 迁移文档“先删 Web 再性能” vs 研究文档“先测量再删” | **G3 切入口 + 15–30 分钟真机冒烟 + archive → G4 删除 Web → G5 功能玻璃 → G6 性能/降级/T-gate** | 用户选择立即删除；功能优先；性能降级后置；archive 保留回滚 | 先做长周期测量再删 |
| D8 | 迁移文档“本地 API34 AVD” vs 实际需补齐工具链 | **本机补齐 Android 工具链（Appendix A）+ G3.0 建 `tvNative`/`tv29`/`tv31`/`tv36` lane；API34 可选** | 用户已装 Android Studio；CLI Gradle/AVD/timeout 仍需逐项确认；API34 不是必需 | 继续按文档假设执行 |
| D9 | 研究文档 Backdrop 2.0.1 vs 仓库 1.0.6 | **按仓库 vendored 版本评估；Haze 预检/ PoC 独立 worktree** | 版本差异会影响 API/参数/性能结论 | 直接按 2.0.1 文档改代码 |
| D10 | 现有 TV lane 都是手机镜像 | **新增 `tvNative`：Android TV 镜像、`isTv=true`、`MainActivity` 生产入口** | `TvTestActivity` 只能测组件，不能证明生产分支 | 继续用 tv1080 手机镜像冒充 TV |

---

## 3. 目标架构与不变量

```text
遥控器 / 键盘 / 手柄
        ↓
MainActivity（TV 判定，isTv=true）
        ↓
TvRootApp（唯一 TV 入口）
  Back 优先级：IME → Dialog/窗口 → Player 面板 → Player 控制条 → 路由
        ↓
ui/tv/pages + ui/tv/components + ui/tv/design tokens
        ↓                                   ↘
ProductionGlassScene / cinemaGlassMaterial     Media3 TvPlayerPage
  Basic / Blur / Full                          SurfaceView，独立生命周期
        ↓
CinemaViewModel / Repository
        ↓
LibreTV Adapter（版本化） → 独立 LibreTV 服务

手机：MobileRootApp（不改）
WebView：仅豆瓣官方登录等非 TV 主界面用途
TV Web：归档后删除，不再参与构建
```

**不变量：**

1. TV 只有一条入口：`MainActivity` → `TvRootApp`；hybrid 不再可达。
2. Back 只有一个 owner：`TvRootApp`，且根 handler 必须注册在 `when (model.page)` **之前**，页面/播放器后注册的 handler 优先。顺序：IME → Dialog → 播放器面板 → 控制条 → 路由 → HOME 退出。
3. 玻璃只采样 `BackdropLayer` 记录的静态背景；不采样页面 UI、不采样 Dialog、不采样视频 Surface。
4. 材质档只改变光学成本，不改变焦点目标集合、返回、收藏、切源、播放规则。
5. 任何性能数字都必须绑定 SHA/APK/设备/renderer/档位/有效样本；E-gate 不产出产品 60fps 结论。
6. 删除 Web 前，所有仍需要的测试与产物闸门必须先在新位置跑绿；删除后必须在精确删除 SHA 上复跑 E-gate + `tvNative`。

---

## 4. 执行路线

### G0 — 治理、环境与基线（1–2 人日）

- [ ] **G0.0 提交治理**：把 `AGENTS.md` + `docs/superpowers/plans/2026-11-07-native-tv-consolidation.md` 作为一个 commit 提交。验收：`git status --porcelain` 为空；`git log -1 --stat` 含两文件。
- [ ] **G0.1 决定执行环境**：三选一并在 `docs/evidence/native-tv-environment.md` 记录。
  - A（推荐）：CI 负责 build/unit/lint/device QA，真机负责 T-gate；本机只做代码与文档。
  - B：本机补齐 Gradle 8.13、Android SDK（platform 36、build-tools、cmdline-tools）、emulator + Android TV system image；需用户批准磁盘与安装。
  - C：另一台已装 Android Studio 的机器；记录其 SDK/AVD/renderer。
  - 验收：`gradle --version`、`adb devices`、或一条 GH Actions run URL。
- [ ] **G0.2 在精确 SHA 上跑基线**：`android.yml` 已声明 `workflow_dispatch`。用 `gh workflow run android.yml --ref dev-compose`（或当前分支）触发；记录 run URL。
  - 验收：build + `:app:testDebugUnitTest` + `:backdrop:testDebugUnitTest` + lint + `assembleDebugAndroidTest` 全绿；以 `TEST-*.xml` 汇总实际 JVM 数，**不引用 496/497/506**。
- [ ] **G0.3 固定基线**：G0.0 之后打 tag `baseline/native-tv-5c24360`（tag 指向 G0.0 commit = 5c24360 + 治理提交）。验收：`git show <tag>:AGENTS.md | grep 'native Compose'` 命中；tag tree 含本方案；`git status --porcelain` 为空。
- [ ] **G0.4 归档对照**：打 tag `archive/tv-web-5c24360`；`dev-hybird` 远端 ref 若不存在，记录“以 tag 为准”，不阻塞。
- [ ] **G0.5 写事实纠正**：新增 `docs/evidence/native-tv-baseline-2026-11-07.md`，记录 1.6 的偏差与本次实测值；不修改历史 release notes。
- **Gate 0 退出**：治理 commit 存在；基线 run 有 URL；tag 验证通过；执行环境已确定。

### G1 — Wave 0 组件层（6–7 人日）

每个任务以“可编译 + 对应测试绿 + 一个可回滚 commit”为检查点。页面文件在 G2 才迁移，G1 只交付组件与最小接入。

| ID | 交付 | 主要文件 | 验收 |
|---|---|---|---|
| G1.1 | 令牌补全：`TvHeight.row=48.dp`、`TvSize`、`TvWidth`、`TvLayout` 扩展、`TvColors.Panel/Scrim`、`TvMotion.microMs=110` | `ui/tv/design/TvTokens.kt` | `TvEmptyState` 仍绿；新增 `TvTokensTest`；不全局改动画 |
| G1.2 | `TvText`：`TvTextStyle.toTextStyle()` + `TvText`，内部委托 `CinemaTvText`；先接 `TvEmptyState` | 新建 `ui/tv/components/TvText.kt` | Compose UI 测试：三种 `TvType`；golden 不变 |
| G1.3 | `GlassAction` 下沉到 `ui/tv/components/GlassAction.kt`；token 化高度/字号/圆角；保留 `enabled/selected/prominent`；旧位置可留 deprecated typealias | 移动 `ui/CinemaWidgets.kt:94-180`；更新 21 个 import | `GlassActionLayoutTest` 同步到 36/44/48；phone lane 绿；disabled 不可聚焦 |
| G1.4 | `TvField`：由 `TvInput` 派生；D-pad 聚焦只高亮，OK 才进入编辑并显示 IME；错误/禁用态；Search/Settings/SourceSubscriptions 删除显式高度 | 新建 `ui/tv/components/TvField.kt`；改 3 个页面/面板 | `TvFieldTest`：focus/edit/IME/error；`SourceSubscriptionsUiTest` 绿 |
| G1.5 | `TvChip` / `TvChipRow`：36dp、12dp 间距、selected/disabled；保留 `catalog-more:*` testTag | 新建 `ui/tv/components/TvChip.kt` | `CatalogBrowsePagingTest`；chip 焦点顺序测试 |
| G1.6 | `TvPageHeader` / `TvSectionHeader`：标题/副标题/右侧动作；6 页共用 | 新建 `ui/tv/components/TvHeaders.kt` | 页面截图评审；标题文本不变 |
| G1.7 | `TvStatus`：Empty / Loading / Error / StatusLine 四态；替换 17 处手写状态 | 新建 `ui/tv/components/TvStatus.kt` | `TvStatusTest`；错误文案可读；无布局跳动 |
| G1.8 | `TvDialogFrame` / `TvConfirmDialog`：26dp 圆角、480/600dp 宽、Cancel 默认焦点、玻璃 Basic；参数化焦点陷阱测试覆盖 Detail/Explore/Library/Settings 四处 | 新建 `ui/tv/components/TvDialogs.kt`；4 处 Dialog | `TvDialogFocusTest`：D-pad 不逃逸、两次 Back 只关 Dialog、取消回触发器 |
| G1.9 | `ContentRail(action: @Composable (() -> Unit)? = null)` + 卡片/导航 token 化；保留 `rail:$title` region key | `ui/tv/components/ContentRail.kt` | `ContentRailVisibilityTest`、`ContentRailTraversalTest`；书签 region 不变 |
| G1.10 | `TvHeroModel` + `TvPager` + `TvPagerIndicator`：纯数据、手动翻页、`featured.size > 1` 才启用 | 新建 `ui/tv/components/TvHero.kt` | `TvHeroPagerTest`：单条不显示 indicator；D-pad 左右翻页；无自动轮播 |
| G1.11 | `TvAboutSection`：简介、演员、季集、片源、评分来源；空则省略；使用已有 `actors` | 新建 `ui/tv/components/TvAboutSection.kt` | `TvAboutSectionTest`；无 actors 不渲染空标题 |
| G1.12 | `TvProgressBar`：LIVE/PLAYER 共用 4dp 视觉、10dp 触摸目标 | 新建 `ui/tv/components/TvProgressBar.kt` | 视觉截图；LIVE 当前节目只渲染一次 |
| G1.13 | 玻璃表面策略：`GlassSurfaceLevel { Basic, Blur, Full }`；`glassLevelFor(screen, role)` 静态表；`CinemaGlassSurface(level=...)`；`sampleBackdrop` 收为内部参数；**G1/G2/G5 只定义/使用 level，不设每屏一块或面积上限**；面积常数留到 G6.4 降级阶段 | `CinemaGlassSurface.kt`；`GlassControlMaterial.kt`；`ProductionGlassPolicy.kt` | `GlassSurfacePolicyTest`：Dialog/Player Basic；全表面映射可用；不预降级 |
| G1.14 | `TvActionRow`：把 Detail 的内联 actions 收成组件，统一 45dp、焦点顺序、disabled 语义 | 新建 `ui/tv/components/TvActionRow.kt`；`DetailScreen.kt:67-83` | `TvActionRowTest`：焦点顺序、disabled 跳过、Back 不误触 |
| G1.15 | 运动令牌收口：给出 `TvMotion` / `TvEffects` / `PosterMotionPolicy` 的单一来源说明；G1 只把 `TvMotion` 接入新组件，不改视觉参数；spring 合并留到 G4 视觉评审后 | `TvTokens.kt`、`TvMotion.kt`、`GlassControlMaterial.kt`、`FloatingNavigation.kt` | `FocusMediaCardMotionTest` 仍断言 1.06/.97；无未评审的视觉变化 |

**Gate 1 退出：** G1.1–G1.12 十二个基础组件 + G1.13 玻璃策略 + G1.14 `TvActionRow` + G1.15 运动令牌全部有测试；`GlassActionLayoutTest` 已按新 token 更新；phone lane 绿；没有页面级大改。

### G2 — 页面层（9–10 人日）

逐页统一 DoD：

- **D1** 页面不再出现 raw 字号/间距/圆角/控件高度（光学与艺术常数除外）。
- **D2** 空、加载、错误三态齐全。
- **D3** 可禁用动作真的 `enabled`（D-pad 跳过、40% 透明）。
- **D4** 该路由的 focus / Back / 返回恢复回归通过；G2 只评审截图，**不提交 golden**。
- **D5** 受影响截图重新捕获并人工评审；G6.1 做唯一一次权威 golden 重捕。
- **D6** 不新增依赖、不采样视频、不跨 Dialog 采样。

| 页面 | 主要改动 | 测试 |
|---|---|---|
| **HOME** | Hero 用 `TvHeroModel` + pager；「为你精选」加「查看全部」；删掉重复的「更多电影」；`换一批` 加载中禁用；显示 `discovery.message` 错误态 | 01 golden 评审 + 02 截图；`ContentRail*`；HERO 单条/多条 |
| **DETAIL** | `selected == null` 拆成加载/不可用两态；About（演员/季集）；actions 用 `TvActionRow`；picker 用 `TvDialogFrame`；picker 取消回触发器而不是固定 shelf；4 列保持 | 03 golden 评审 + 04 截图；`ManualWorkAssociation*`、`WorkNavigation*`、`TvDetailBackTest` |
| **MOVIES/SERIES** | `TvPageHeader`；`全部` 同时清 category/year/rated（修 bug）；loading/error 三态；paging token 化 | 05/06 golden 评审；`LibraryCatalogControlsUiTest`；`TvRouteBackTest` |
| **SEARCH** | `TvField`；入口焦点改为筛选 chip/结果区，输入框用 OK 进入；空 query 禁用搜索（修 bug）；历史/筛选/tag 用 `TvChipRow`；无结果/错误用 `TvStatus` | 08 截图；paging 用例；`TvImeBackTest`；空 query 不可提交 |
| **EXPLORE** | `TvPageHeader`；空/加载态；coverflow 常数进 token；pager indicator；dialog token | 07 golden 评审；空推荐不显示空白；`TvRouteBackTest` |
| **LIVE** | 分栏改 1:2（`.333/.667`）；当前节目去重；无源/无频道/无节目三态；`TvProgressBar` | 09 截图；当前节目只出现一次；`TvRouteBackTest` |
| **LIBRARY** | `TvPageHeader`；统一空态；清空确认用 `TvConfirmDialog`；复核面板用 `LibraryProgressReviewDialog`（不是 `LibraryProgressReviewScreen`）；返回恢复书签 | 10 截图；清理用例；`TvFocusRestorationTest` |
| **SETTINGS** | 推荐看板只保留 section 5 的入口，删除 section 3 的重复入口；section 7 纳入统一 `when`；连接/登录/保存/添加源加 disabled；子面板 token 化；IME Back 先隐藏键盘 | 11 golden 评审 + 12 截图；`SourceSubscriptions*`、`UpdateSettings*`、`DiagnosticsUiTest`、`TvImeBackTest` |
| **PLAYER** | 抽 `TvPlayerOverlay`；4 个 raw hex → `TvColors`；新增 buffering 态；track panel 用 `CinemaGlassSurface(level = Basic)`；保三段式 Back | 13/14 截图；`NativePlaybackSessionTest`；`TvPlayerBackLadderTest`；缓冲中 Back/焦点可用 |

**路由 × Back/焦点用例表（G2 D4 的可执行清单）：**

| 路由 | Back 期望 | 用例 |
|---|---|---|
| HOME | 无 handler，系统 finish | `MainActivityPathSmokeTest` |
| MOVIES/SERIES/SEARCH/EXPLORE/LIVE/LIBRARY/SETTINGS | 回 HOME | `TvRouteBackTest`（参数化 7 路由） |
| DETAIL | 回 section | `TvDetailBackTest` |
| PLAYER | 面板 → 控制条 → returnPage | `TvPlayerBackLadderTest` |
| SEARCH/SETTINGS + IME | 第一次 Back 隐藏 IME，页面不变；第二次才回 HOME | `TvImeBackTest` |
| Dialog（4 处） | 只关 Dialog；D-pad 不逃逸；取消回触发器 | `TvDialogFocusTest` |
| Grid/List 返回 | 回到原卡片；删除/重排后回最近邻 | `TvFocusRestorationTest` |

**G2.10 全表面玻璃功能**：Settings/LIVE/DETAIL/LIBRARY 的指定面板接 `GlassSurfaceLevel.Blur/Full`；大面积玻璃允许；Dialog/Player/video/API 硬约束不变；只做截图评审，不做性能降级。

**Gate 2 退出：** 9 页 DoD 全过；路由×用例表全绿；全表面玻璃功能接上；截图评审完成但未提交 golden；无新增 raw 大块。

### G3 — 真 TV lane、切入口与删前冒烟（4–5 人日）

> 重点：先建 lane，再切入口。否则 `MainActivityPathSmokeTest` 会在手机镜像上自证成功。

- [ ] **G3.0 建原生 TV lane（必须先于 G3.1）**：
  - 新增 `tvNative`：Android TV 系统镜像（优先 API33），1080p，`MainActivity` 生产入口；断言 `ro.build.characteristics` 含 `tv`、`dumpsys uimode` 为 television。
  - 新增 `tv29`、`tv31`、`tv36`（可用 Android TV 镜像则用；否则用 AOSP TV target 并记录 `isTv` 是否成立；不成立则该 lane 只做组件回归，不冒充生产入口）。
  - 五处编辑点必须一起改：`scripts/qa-device-matrix.py`、`scripts/run-device-qa-target.sh:11-24` 的 `case`（默认分支改为 `exec bash scripts/run-device-qa.sh "$@"`）、`.github/workflows/tv-smoke.yml:11` 的 `options`、`scripts/tests/test_device_qa_harness.py:102` 的固定字符串、`tv-smoke.yml:33-35` 的 `select-devices` 执行顺序。
  - 把 `scripts/hybrid/run-tv-emulator.sh:144-165` 的 Android TV AVD/启动屏障迁成独立脚本；在删除 hybrid 前先让 `tvNative` 跑通。
  - 把 `NativePlaybackSessionTest`、`SourceSubscriptionsUiTest#nativeTvRemoteCanReadLongPreviewAndFailedRefreshKeepsImportedSources` 迁入原生 TV lane。
  - `hybrid-android.yml` 从 G3 起标记 expected-red（或只留 `fallback-api26`）；在 `docs/evidence` 写明“自 G3 起 hybrid 用例失效，仅作归档”，其红不是回滚信号。
- [ ] **G3.1 切入口 + Back/IME**：`MainActivity.kt:76` 改为 `TvRootApp(model)`；在 `TvRootApp` 的 `CinemaTvTheme` 内、`when (model.page)` **之前**注册根 `BackHandler`：
  ```kotlin
  BackHandler(enabled = model.page != Page.HOME || imeVisible) {
      if (imeVisible) { keyboard?.hide(); return@BackHandler }
      model.back()
  }
  ```
  页面/播放器后注册的 handler 优先；`TvPlayerPage` 保持面板 → 控制条 → 路由。同 commit：
  - 删除 `TvTestActivity.kt:49`、`ProductionGlassTestActivity.kt:63`、`GlassBenchmarkActivity` 的重复 `BackHandler`；
  - 加 `testTag("tv-root")`；
  - `BackHandler` grep 闸门：只允许 `TvRootApp.kt`、`TvPlayerPage.kt`、`HybridTvRootApp.kt`（G5 前）。
- [ ] **G3.2 拆分启动冒烟**：`TvLaunchSmokeTest` 只进 `tvNative` lane，断言 `tv-root` + `tv-main-navigation` + 一次 D-pad + 一次 Back；phone lane 新建 `MobileLaunchSmokeTest` 断言手机 root。不要用“保留类名”掩盖 lane 语义变化。
- [ ] **G3.3 生产入口冒烟**：`MainActivityPathSmokeTest` 用 `ActivityScenario` 启动 `MainActivity`，在 `tvNative` 上验证 TV 分支进入 `TvRootApp`、非 HOME Back 回 HOME、HOME Back 使 Activity finishing；记录 route=native。若未连接，初始页可能是 SETTINGS；测试接受 SETTINGS/HOME，只断言 TV root 与 Back 语义。
- [ ] **G3.4 路由/焦点/IME/Dialog 回归**：跑 G2 路由×用例表；API26/29/31/33/36 各跑同一段固定交互脚本（8 键遍历 → DETAIL → 开/关 Dialog → SEARCH 触发 IME → Back → 播放器三段返回）；断言无意外退出、焦点始终可见。
- [ ] **G3.5 删前快速真机冒烟 + 归档**：
  - 从 `5c24360` 构建并保存 hybrid APK（SHA-256），作为归档对照；可选做一次短 delta，但不做框架排名。
  - 在真实 Android TV / 盒子上安装 G3 native APK，15–30 分钟冒烟：启动、D-pad、Back、一次播放、一次返回、SEARCH IME。
  - 打 tag `archive/tv-web-5c24360`；证据写入 `docs/evidence/native-tv-pre-delete-smoke.md`。
  - 若 native 冒烟失败，**不进入 G4**；修复后重跑。通过后 G4 立即删除 Web（用户已选“G3 切入口后立即删除”）。
- **Gate 3 退出：** `tvNative` 绿且 `isTv=true`；生产入口是原生；IME/播放器 Back 阶梯有测试；15–30 分钟真机冒烟通过；archive tag 已推；hybrid 代码仍在树中但不可达。

### G4 — 删除 Web（5–6 人日）

> 前置：G3.5 快速真机冒烟通过；`archive/tv-web-5c24360` 已推；G0.0 治理已提交。用户已选“G3 切入口后立即删除”，所以 G4 紧随 G3，不再单独做性能 gate。

- [ ] **G4.1 lane 收口**：G3.0 已建 `tvNative`/`tv29`/`tv31`/`tv36` 并改五处编辑点；G4.1 验证 `tv-smoke.yml` 的 dispatch enum、`run-device-qa-target.sh` 的 `case`、`test_device_qa_harness.py` 的字符串、`select-devices` 的执行顺序全部一致；删除 hybrid 专用 lane 前先确认 `tvNative` 绿。
- [ ] **G4.2 迁移产物闸门**：把 `PRIVATE_BUILD=false` 断言从 `scripts/hybrid/artifacts.py:143-145` 移入 `scripts/updates/build-unsigned-candidate.sh`；把仍需保留的 release/version 校验从 `scripts/hybrid/release.json` 迁到 `scripts/updates`；保留签名身份与 unsigned archive 检查。验收：`scripts/tests` 全绿；candidate 脚本对 personal build 失败。
- [ ] **G4.3 迁移/删除 hybrid 测试与 EPG**：
  - 新建 `ui/tv/pages/ProgramGuideWindow.kt`，承接 `HybridProgramGuide.window(...)` 的 filter/distinct/sort/take(48) 逻辑；`LiveScreen.kt:57-61` 改为调用它；把 `HybridProgramGuideTest` 迁成 `ProgramGuideWindowTest`，补 `limit`/`ended` 断言。
  - 删除 4 个 hybrid androidTest 文件（`HybridHostUiTest`、`HybridAuditUiTest`、`HybridNativeDiagnostics`、`HybridWindowInputTrace`）与 3 个 hybrid 单测（含迁移后的 `HybridProgramGuideTest`）。
  - 确认 `LibraryProgressReviewScreen` 在切入口后无入口；G2 只使用 `LibraryProgressReviewDialog`；G4 删除 `LibraryProgressReviewScreen` 及 `libraryManagement`/`settingsInitialSection` 死管道。
- [ ] **G4.4 删除 `hybrid/` 包**：11 文件 / 1,492 行；删除 `MainActivity` 的 hybrid import；移除 `androidx.webkit`（确认豆瓣登录只用 `android.webkit`）；`git grep dev.cinematv.hybrid app/src/main` 为空。
- [ ] **G4.5 删除 Gradle 接线**：`app/tv-web.gradle`、`app/build.gradle.kts:13,17,145`、assets `srcDir`；验收：无 Node 环境下 `assembleDebug` 成功。
- [ ] **G4.6 删除前端与脚本**：`frontend/tv-web/**`（21 文件 / 3,980 行）、`scripts/hybrid/**`（13 文件 / 1,901 行）；更新 **8 个 workflow 的 13 处** `cache-dependency-path`，删除仅因 `npm ci` 存在的 `setup-node`；`git grep frontend/tv-web .github` 必须为空。
- [ ] **G4.7 删除 hybrid workflow**：`hybrid-web.yml`、`hybrid-android.yml`；确认 `tvNative` 已替代 Android TV 启动逻辑；G3–G4.7 的 expected-red 窗口在证据中记录。
- [ ] **G4.8 文档收口**：删除 `docs/HYBRID_TV.md`；`AGENTS.md` 已刷新，这里只做一致性核对；重写 README / ARCHITECTURE / LOCAL_BUILD_DEFAULTS / DEV_AUDIT 的相关段落；`docs/LIBRETV_PROTOCOL.md:88` 的 hybrid 语境改写；历史 release notes 不动。
- [ ] **G4.9 删除后验证**：在精确删除 SHA 上跑 `android.yml` + `tv-smoke.yml` + `tvNative`；确认无 Node 构建成功；记录 run URL。性能 E-gate 留到 G6.3，不在删除 gate 宣称 60fps。
- **Gate 4 退出：** TV 主线无 WebView 依赖；所有需要的测试在原生 lane 真跑；CI 不再装 Node；`tvNative` 绿；删除 commit 可回滚到归档 tag。

### G5 — 功能完整的玻璃与视觉打磨（5–6 人日）

> 用户已确认：先把最完整功能层做好，大面积敷玻璃，再考虑性能降级。G5 不设每屏一块/面积上限；只保留 Dialog/Player/video/API 硬约束。

- [ ] **G5.1 全表面玻璃功能**：按视觉目标把 `GlassSurfaceLevel.Blur/Full` 接到 Settings/LIVE/DETAIL/LIBRARY 的指定面板、导航、Hero 按钮、GlassAction；大面积 Blur/Full 允许；Dialog/Player/video 仍 Basic/不采样。
- [ ] **G5.2 Glass Lab**：debug-only `GlassLabActivity`，同背景并排 Basic/Blur/Full，固定字体/海报/焦点序列；输出三档截图与帧时间；只做视觉/成本记录，不做降级决策。
- [ ] **G5.3 Haze 2.1 预检 + PoC**：0.5 天 AAR metadata 预检；`minCompileSdk>36` 即停；通过才做导航+面板 PoC；非劣性 p95 +1.0ms、jank +0.3pp、PSS +20MB；未达标保持 backdrop。
- [ ] **G5.4 视觉与对比度评审**：6 张 golden 截图评审（不提交；G6.7 权威重捕）、15 张捕获；焦点环 3:1、标签 4.5:1；三档材质下焦点目标集合与 Back 结果一致。
- [ ] **G5.5 功能验收**：D-pad/Back/IME/Dialog/播放器三段返回；全表面玻璃下无不可读/不可聚焦；记录已知成本与待降级项，不宣称 60fps。
- **Gate 5 退出：** 全表面玻璃功能完成；视觉/对比度评审通过；Haze 决策有记录；没有性能降级（降级留到 G6）。

### G6 — 性能、降级与真机验收（6–7 人日）

- [ ] **G6.1 性能采集基础设施**：新建 `scripts/perf_common/__init__.py`；把 `FrameTimingRecorder` 从 `app/src/glassBenchmark` 迁到 `app/src/perf`，`glassBenchmark` 与 `androidTest` 同时 `srcDir` 引入；输出路径参数化；新增 `TvFrameTrace.kt`、`TvNativePerformanceAcceptanceTest.kt`、`scripts/native-tv-perf/analyze.py`。指标分类：`INPUT_HANDLING_DURATION`/`ANIMATION_DURATION` 是时长，`FRAME_TIMELINE_TYPE` 是分类；viewport 从 run 元数据读取。
- [ ] **G6.2 噪声实验**：同配置 6 次，得 `σ_run(p95)`、`σ_run(jank)`；ABBA 交错 + run 级 cluster bootstrap 95% CI；`Δp95 > max(1.0ms, 2σ)` 且 `Δjank > max(0.3pp, 2σ_jank)` 才判回归；若 `σ(p95) > 2ms`，判“该环境不具备回归判定能力”。
- [ ] **G6.3 E-gate 基线 + 删后复跑**：在 G4 删除 SHA 上跑协议/配对相对 E-gate；只允许“协议完整性 / 路由 / 无效样本”作为 required check；不设绝对毫秒，不用 E-gate 宣称 60fps。
- [ ] **G6.4 性能降级**：测量全表面玻璃；按数据引入 `MAX_PANEL_AREA_PX`、`MAX_PANEL_HEIGHT_DP`、`panelBlurCount`、Basic 回退；只降失败表面，不整体砍掉玻璃；guard 用 G6.2 的配对相对门槛。
- [ ] **G6.5 API/低 RAM/热压矩阵**：`compat26`/`tv29`/`tv31`/`tvNative`/`tv36`；同一交互脚本（8 键遍历 → DETAIL → Dialog → SEARCH IME → Back → 播放器三段返回）；断言无意外退出、焦点可见、IME/播放器可返回。
- [ ] **G6.6 T-gate**：真实 Android TV / 盒子，release-like；至少两台硬件（例：2GB Amlogic S905X4 / API30-31 / 60Hz / 1080p；4GB / API33 / 60Hz）；deadline 驱动 jank；30 分钟热稳定；20 轮内存；Media3 视频指标；Perfetto/SurfaceFlinger 呈现证据。`isMinifyEnabled=false` 写明；R8 另立项目。
- [ ] **G6.7 权威 golden 重捕**：6 张 golden × 2 viewport + 15 张捕获评审；对比度 3:1/4.5:1；不调 0.020/0.08。
- [ ] **G6.8 文档/发布/落地清单**：`GLASS_PRODUCTION.md` 合并 V4 有效章节；README/ARCHITECTURE/LOCAL_BUILD_DEFAULTS/DEV_AUDIT；unsigned candidate + signing；本节的选择性落地清单；最终报告。
- **Gate 6 退出：** T-gate 有证据；降级有数据；文档/发布边界清楚；落地清单经用户确认。

### 并行 Track B — LibreTV 契约审计（3–4 人日，不阻塞 UI）

- [ ] **B1** 审计 `data/LibreTvRepository`、`data/Models.kt`、`LibreTvProtocol`、`SourceResponseIdentityTest`：作品 ID / 来源 ID / 剧集 ID 是否分离；部分来源失败是否有来源级错误；取消是否隔离；NDJSON 流式搜索是否覆盖；认证请求是否真正受保护。
- [ ] **B2** 为已证实缺陷补契约测试：同名跨源不续播错集；部分失败返回部分结果 + 错误；取消不交付 stale；过期会话不污染新结果；认证失败不伪装成功。
- [ ] **B3** 只在测试证明必要时做版本化适配层；不重写 LibreTV 上游，不把豆瓣账号产品化。
- **退出：** 新增测试全绿；未证实的问题只记录，不“顺手重构”。

### 并行 Track C — 手机回归（随 G1/G2 执行）

- [ ] **C1** 每次移动共享组件（`GlassAction`/`TvInput`）后跑 phone lane；手机 UI 不改；`MobileGlassButton`/`MobileScreens` 不受影响。
- **退出：** phone lane 绿；无手机视觉变化。

---

## 5. 玻璃专题

### 5.1 现有能力与真实缺口

| 档位 | 现有机制 | 缺口 |
|---|---|---|
| Basic | 渐变、边缘高光、`drawSafeGlass` | 不记录真实背景，不折射 |
| Blur | API31+ 背景场景重放，约 12dp 模糊 | 只有导航/小控件享受；大面板默认 Basic |
| Full | API33+ RuntimeShader 透镜 + 色散 | 仅 `GlassAction` 与导航；`AUTO` 永不升级 |
| Dialog | Basic（跨 owner 拒绝） | 正确，不应改 |
| Player | Basic（无 scene） | 正确，不应改 |

### 5.2 表面使用策略

| 表面 | 功能阶段（G2/G5） | 性能阶段（G6） | 采样 | 硬约束 |
|---|---|---|---|---|
| FloatingNavigation | Full（ENHANCED）/ Blur（AUTO） | 可按测量封顶 | 根 scene + 导航子 scene | 现有实现保留 |
| GlassAction / 小控件 | Full/Blur | 通常不降 | 根 scene | 面积小；焦点描边独立 |
| `settings.pane` | **Blur/Full，允许大面积** | 按测量决定面积/是否单块 | 根 scene | 不采样视频 |
| `live.guide` | **Blur/Full，允许大面积** | 按测量决定面积/是否单块 | 根 scene | 不采样视频 |
| DETAIL 面板 | **Blur/Full，允许大面积** | 按测量决定面积 | 根 scene | 不采样视频 |
| Dialog | Basic | Basic | 无 | 跨 owner 禁止绕过 |
| Player overlay / track panel | Basic | Basic | 无 | 永不采样视频 Surface |
| Poster / card | 无玻璃 | 无玻璃 | 无 | 焦点双色环 |
| Hero | 按钮 Full/Blur | 按钮可按测量封顶 | 根 scene | Hero 本身不做玻璃 |
| 手机 | Basic | Basic | 仅 Home hero | 不在本方案范围 |

**实现契约：**

- `GlassSurfaceLevel { Basic, Blur, Full }`；`glassLevelFor(screen, role)` 返回静态映射；页面不直接传 `sampleBackdrop`。
- `AUTO` = Blur；Full 仅在用户手动选择 ENHANCED 且 API33+ 时出现。文档不能写“自动就是 Full”。
- **功能阶段不设面积/每屏上限**；G2/G5 先把大面积玻璃做出来。G6.4 才根据测量引入 `MAX_PANEL_AREA_PX`、`MAX_PANEL_HEIGHT_DP`、`panelBlurCount` 与 Basic 回退；只降失败表面。
- 焦点环：卡片复用 `GlassAction` 的 `Ink 4dp + Ivory 2dp` 双色环；白海报上仍可见；G6.7 做对比度断言。

### 5.3 生产路径与文档收口

- 以 `ProductionGlassPolicy` + `ProductionGlassScene` + `cinemaGlassMaterial` 为唯一生产路径。
- `GlassRenderPolicy` / `GlassFailureLatch` / `resolveGlassQuality` / `GlassSheen` 是历史/未使用；但 **`GlassRenderPolicy` 的面积常数不要直接丢**，G6.4 迁入 `ProductionGlassPolicy` 后再退役。
- `docs/GLASS_PRODUCTION.md` 是当前生产说明；`docs/GLASS_V4.md` 仍含有效的 V4 depth/lens 契约（实际折射什么、成本与回退、手机控件），不是整篇过期。G6.8 合并有效章节后再把 V4 标为历史。
- `sampleBackdrop` 只作为 G1.13 的内部实现参数；页面 API 用 `GlassSurfaceLevel`。

### 5.4 Haze 2.1 预检与决策门槛

**预检（0.5 天，先于 PoC）：**

- 独立 worktree，不加入本仓库 `settings.gradle.kts`；解析 `haze-glass:2.1.0`，读 AAR metadata 的 `minCompileSdk` / `minAgpVersion`，编译一屏。
- 若 `minCompileSdk > 36` 或强制升级 Compose/Kotlin/compileSdk → 立即停止，产出 `docs/evidence/glass-library-decision.md`，记为“需独立工具链升级项目”，不进入测量。

**PoC 门槛（预检通过后）：**

1. 同场景视觉不劣于现有 Full/Blur（人工评审 + 截图）。
2. 非劣性：`Haze p95 ≤ backdrop p95 + 1.0ms` 且 `Δjank ≤ +0.3pp`；同 API 档、同场景、同 run 交错。
3. 峰值 PSS 增量 ≤ +20MB，总量 ≤ 350MB。
4. API 31/32 用 `haze-blur` vs 现有 Blur；33+ 用 `haze-glass` vs 现有 lens；26–30 只验 fallback 不崩、不黑块。
5. 对比对象写明 vendored `896a94a` + 本地修改；迁移方案必须包含该修改的移植。
6. Apache-2.0 notices 可合规落地；不引入 GPL；不长期 fork。

未达标保持 backdrop；不在 `:app` 增加依赖，除非决策为“迁移”。

---

## 6. 性能与验收

### 6.1 指标定义

- **刷新预算**：`B = 1e9 / refreshHz`，取自 `FrameTimingRecorder` 已记录的 `refreshHz`。
- **deadline**：API31+ 用 `DEADLINE`；`DEADLINE` 为 0/负时回退 `B`。低版本用 `B`。
- **jank**：`TOTAL_DURATION > deadline` 的帧占比；不再用固定 16.67ms。按场景池化后报 P99，并报 `n`；不按 450ms 窗口算 P99。
- **frameOverrun**：`max(0, TOTAL_DURATION - deadline)`，单位 ms；P95 ≤ 0 表示 95% 帧不超时。
- **输入三时间戳**：
  - `keyNs`（native receipt）→ `firstFeedbackNs`（第一条非 `FIRST_DRAW` 帧）：P95 ≤ `2×B`（60Hz 约 33ms）。
  - `routeCommittedNs`（UI 线程新路由首次 composition 完成）→ `firstRenderedFrameNs`：P95 ≤ 100ms 主流 / ≤150ms 基础。
  - 同时报告前一个刷新周期的 boundary 候选（沿用 `analyze.py:106-112` 口径），不得默认归因给输入。
- **tail settle**：每个输入观察“连续 2 个 vsync 间隔无新帧”或 1500ms 上限；`tailTruncatedWindows = 0`；DETAIL/PLAYER 返回的 `settleMs` P90 ≤ 600ms。`truncated` 仅作采集器 20,000 帧容量标志，不再作为动画尾帧的证明。
- **指标分类**：`INPUT_HANDLING_DURATION`/`ANIMATION_DURATION` 是时长；`FRAME_TIMELINE_TYPE` 是分类；不能混列。
- **视频**：Media3 `dropped/(rendered+dropped)`、音频 underrun、非预期 rebuffer；与 UI 帧分开。
- **内存**：PSS/Java/native/graphics 分列；1Hz 采样，报稳态 + 峰值；不混用 Google TV 的 Anon+Swap+Graphics+File 口径。
- **热**：30 分钟混合操作后的帧时间与 thermal status；记录环境温度与起始温度。

### 6.2 E-gate（模拟器，debug，协议/回归闸门）

**原则：不设绝对毫秒门槛。** 唯一可跑的渲染器是软件/主机 GPU 转换层；历史同规格模拟器 render p95 约 1,340ms。绝对门槛只会长期红或被人调松。

| 项 | 要求 |
|---|---|
| 协议完整性 | 精确 viewport；实际 renderer 非空；callback drops = 0；`tailTruncatedWindows = 0`；路由/场景齐全 |
| 场景 | HOME 60s D-pad 遍历 → DETAIL → Back → 播放器 20s → 控制条 8s → 逐层返回 |
| 配对相对差 | 同 run 交错 Basic/Blur/Basic/Blur…；`Δp95 ≤ +1.0ms`、`Δjank ≤ +0.3pp`；run 级 cluster bootstrap 95% CI；CI 重叠即“无结论” |
| 回归判定 | 同设备、同 APK 变体、ABBA 交错；`Δp95 > max(1.0ms, 2σ)` 且 `Δjank > max(0.3pp, 2σ_jank)`；σ 来自 G6.2 的 6 次噪声实验 |
| required check | 只允许“协议完整性 / 路由 / 无效样本”三项；绝对毫秒与产品 FPS 不得作为 required check |
| 不可宣称 | 真机 60fps、GPU/blur 成本、热降频、4K/HDR、视频解码、真实遥控/IR 延迟、2GB LMK |

### 6.3 T-gate（真机，release-like，产品验收）

**必须点名硬件**；无设备可用时记为 blocked，不得记 pass。

| 指标 | 主流设备 | 基础设备 |
|---|---|---|
| jank（> deadline） | ≤ 2%（收敛 1%） | ≤ 2%（收敛 1%） |
| frameOverrun P95 | ≤ 0 | ≤ 0 |
| P99 | ≤ 1×B | ≤ 1×B |
| 输入→可见 P95 / P99 | ≤ 100 / ≤ 150 ms | ≤ 200 / ≤ 300 ms |
| HOME P90 | ≤ 1×B | ≤ 1×B |
| DETAIL 返回 P90 | ≤ 250 ms | ≤ 300 ms |
| 播放器返回 P90 | ≤ 300 ms | ≤ 350 ms |
| PSS | ≤ 350 MB | ≤ 400 MB |
| 视频 dropped/(rendered+dropped) | ≤ 0.1% | ≤ 0.1% |
| 音频 underrun / 非预期 rebuffer | 0 | 0 |
| 热稳定 | 0–5 分钟 vs 25–30 分钟 p95：Δ ≤ +10% 且 ≤ +2ms；thermalservice ≤ SEVERE | 同 |
| 内存 | 20 轮斜率 ≤ 0.5MB/轮；后 5 轮均值 ≤ 前 5 轮 +10MB；Java/native/graphics 分列；`TRIM_MEMORY_RUNNING_CRITICAL` 即失败 | 同 |

**呈现时证据（60fps 唯一可接受证据）：**

- Perfetto `actual_frame_timeline_slice`（API31+，含 `jank_type`）覆盖完整测量窗口；或
- `dumpsys SurfaceFlinger --latency <layer>` 每 ≤200ms 采样一次，按 present 时间戳去重，并记录 `dumpsys display` 的实际刷新模式；
- 两者都未采集 → 报告必须写“无呈现时证据”，不得写 60fps。

### 6.4 测量卫生

1. 固定源码 SHA、APK SHA-256、构建类型、设备/AVD、系统、刷新率、实际 renderer、材质档。
2. 不用 vsync 频率冒充 FPS；不把 `gfxinfo framestats` 的最近约 120 帧当成 60 秒分布。
3. 不用 debugger 下的性能外推 release；不用模拟器外推真机。
4. 不在测量时录屏；不在测量之间调 `waitForIdle()`。
5. 每条结论标注样本数、窗口数、丢弃窗口、空窗口、not-ready。
6. SurfaceView 视频不计入 Compose 帧统计；用 Media3 Analytics 单列。
7. 报告实际解码宽高、codec、帧率、码率；不把 320×180/426×240 fixture 当 4K。
8. WebView 内存按 UID 汇总所有进程，单列 `webview_renderer`；hybrid 的输入→可见不参与 A/B 排名。
9. `isMinifyEnabled=false` 要写明；R8/Baseline Profile 是后续项目，不在本轮冒充“完整发布优化”。
10. 4 小时长时播放列为后续/未验证项；本轮 30 分钟热稳定 + 20 轮内存。

### 6.5 证据目录

```text
qa/native-tv-perf/<timestamp>/
  run.json
  frames.csv
  inputs.csv
  gfxinfo-*.txt
  meminfo-*.txt
  thermal-*.txt
  summary.json
  summary.md
  SHA256SUMS
  DONE | FAILED
```

---

## 7. 测试、金标与 CI

### 7.1 测试分层

| 层 | 内容 | 命令/入口 |
|---|---|---|
| JVM | 以 G0.2 的 `TEST-*.xml` 为唯一计数；新增 token/status/policy 测试 | `gradle --no-daemon :app:testDebugUnitTest :backdrop:testDebugUnitTest` |
| androidTest 功能 | 页面焦点/Back/恢复/IME/Dialog；`TvUiRegressionTest`；`GlassActionLayoutTest`；`SourceSubscriptionsUiTest` | `tv-smoke.yml` lanes + `tvNative` |
| 性能 | `TvNativePerformanceAcceptanceTest` + analyzer | G6.1 |
| 金标 | 6 张对比 / 15 张捕获；G6.7 权威重捕 | `scripts/compare-ui-goldens.py` |
| Host 契约 | `scripts/tests`、`scripts/glass-performance/test_contract.py`、`scripts/perf_common` 契约 | CI |

### 7.2 预期变化

- `GlassActionLayoutTest`：40/35/36/44/45dp 断言随 token 化更新到 36/44/48；不是放宽，是对齐设计令牌。
- `TvUiRegressionTest`：中文文案与 15 张截图会变；G2 只评审，G6.7 统一更新 manifest/REVIEW。
- `FocusMediaCardMotionTest`：1.06/.97 保持不变；若变，必须有视觉评审。
- 保留不替换：`ProgramGuideWindowTest`（迁移后）、`CatalogBrowsePagingTest`、`NativePlaybackSessionTest`、`LibreTvProtocolTest`、`DoubanDeviceSecurityTest`。

### 7.3 CI 变更

- G3.0：新增 `tvNative`/`tv29`/`tv31`/`tv36`；五处编辑点一起改；`hybrid-android.yml` expected-red 到 G4.7。
- G6.1/G6.3：新增 `native-tv-perf` workflow，路径触发 + 每周定时；required check 只做协议/路由/无效样本。
- G4.6：删除 `frontend/tv-web` 后移除 8 个 workflow 的 13 处 `cache-dependency-path` 与仅因 `npm ci` 存在的 `setup-node`。
- `android.yml` 不再需要 Node；构建时间应下降。
- 所有 device lane 记录实际 renderer 与 viewport；无有效样本不得标绿。

---

## 8. 风险与回滚

| 风险 | 影响 | 控制 |
|---|---|---|
| 治理未提交 | tag 记录旧方向 | G0.0 先提交；G0.3 验证 tag 内容 |
| 本地无 Gradle/SDK/emulator | 无法本地验证 | G0.1 决策：CI + 真机，或补齐工具链；不假装已就绪 |
| HEAD 无 CI | 基线未知 | G0.2 精确 SHA 跑基线；结论带 run URL |
| lane 顺序反了 | G3.4/G6.5 在手机镜像上自证 | G3.0 先建 `tvNative`/29/31/36；`isTv=true` 断言 |
| G3 后 hybrid CI 红 | 误判为回滚信号 | expected-red 记录；只留 fallback-api26 或 continue-on-error |
| 根 Back 抢播放器面板 | 返回键行为错乱 | 根 handler 在 `when` 之前；`TvPlayerBackLadderTest` |
| IME 吃掉 Back | 用户以为死机 | 根 Back 第一层隐藏 IME；`TvImeBackTest`；SEARCH 入口焦点改 chip |
| 测试宿主自带 BackHandler | 生产缺口被掩盖 | 删除三处 host handler；grep 闸门；`tv-root` testTag |
| 焦点恢复只到索引 | 删/重排后回错卡片 | `RegisterLazyFocusRegion` 扩展；`TvFocusRestorationTest` |
| 卡片焦点环对比度不足 | 白海报上看不见焦点 | 双色环；G6.7 3:1/4.5:1 断言 |
| `ProgramGuideWindow` 不存在 | 删除步骤找不到文件 | G4.3 用 `HybridProgramGuide` 迁移；LiveScreen 调用；测试迁移 |
| `libraryManagement` 死管道 | 复核面板不可达 | G2 用 Dialog；G5 删除 Screen 与参数 |
| E-gate 绝对门槛不可达 | 长期红/调松 | 6.2 只做协议 + 配对相对差 |
| jank 固定 16.67 | 120Hz 假通过/50Hz 错判 | 6.1 用 deadline/refreshHz |
| 3 次重复不足 | 10%/1ms 回归测不出 | G6.2 6 次噪声 + ABBA + bootstrap |
| tail 截断 | 返回长尾被切掉 | 6.1 settle 规则；`tailTruncatedWindows=0` |
| 输入→首帧被一个周期绕过 | 假通过 | 6.1 三时间戳 + boundary 候选 |
| FrameTimingRecorder 不可复用 | analyzer 漂移 | `perf_common` + `app/src/perf` + 契约测试 |
| A/B 不公平 | 错误框架排名 | G3.5 可选 delta；Glass Lab 做材料对比 |
| WebView 内存少算 | 偏袒 hybrid | 按 UID 汇总；单列 renderer |
| Haze 强制升级工具链 | PoC 白做 | 0.5 天 AAR 预检；`minCompileSdk>36` 即停 |
| Blur 面板掉帧 | 观感提升但交互变差 | 面积常数 + 每屏一块 + 配对相对门槛 |
| 删后无回退 | 真机 T-gate 失败无路可走 | G3.5 删前真机冒烟 + G6.3 删后复跑 + archive tag |
| 删 Web 破坏 CI | 全 lane 失败 | G4.1 五处编辑点；`git grep` 闸门；13 处 cache 引用 |
| 覆盖丢失 | 播放/订阅回归无测试 | G3.0 先迁移；host 测试锁列表 |
| `PRIVATE_BUILD` 闸门丢失 | personal build 混入候选 | G4.2 先迁移断言 |
| 热/内存不可证伪 | 长时稳定性未知 | 6.3 阈值 + 1Hz PSS + TRIM_MEMORY_CRITICAL |
| 60fps 无呈现证据 | 验证剧场 | 6.3 Perfetto/SurfaceFlinger；无证据写“无” |
| LibreTV 协议漂移 | 搜索/播放失败 | Track B 契约审计；版本化适配 |
| 凭据/签名泄漏 | 安全/合规 | 不入库、不入日志、不入 Web bundle；外部签名 |
| 豆瓣实验进 stable | 账号风险 | 保持 dev-only；G6.8 stable-source gate |
| 分支分叉 | 合并冲突 | 不合并 dev 直到 T-gate；小 commit；tag 回滚 |

**回滚：**

- 每个 Gate 前打 tag；失败回滚到上一个 tag。
- 删除 Web 前打 `archive/tv-web-5c24360`；删除 commit 可 revert。
- 切入口失败回滚 G3 commit；hybrid 代码在 G5 前仍在树中。
- 玻璃策略失败回退 `GlassSurfaceLevel.Basic`；不动页面功能。
- 性能不达标先降材质/面积，不牺牲焦点与返回。

---

## 9. 工期与并行

| Gate | 内容 | 人日 |
|---|---|---|
| G0 | 治理提交、本机工具链、精确 SHA 基线、tag、事实纠正 | 1–2 |
| G1 | 15 个组件/策略任务 | 6–7 |
| G2 | 9 页迁移、bug 修复、路由×用例、全表面玻璃接入 | 10–12 |
| G3 | 真 TV lane、切入口、IME/Back、15–30 分钟真机冒烟、archive | 4–5 |
| G4 | 覆盖迁移、删 Web、CI/文档、删后验证 | 5–6 |
| G5 | 全表面玻璃功能、Glass Lab、Haze 预检、视觉/对比度 | 5–6 |
| G6 | 性能采集/噪声、E-gate、降级、API 矩阵、T-gate、golden、发布 | 6–7 |
| **合计** | | **37–45 人日** |

- Track B（3–4 人日）与 Track C 不包含在上表；若纳入，约 40–49 人日。
- 单工程师：约 9–10 周（含 CI 排队与真机窗口）。
- 两工程师并行：约 6–7 周；G1/G2 可拆页面，G5 的 Haze 预检与 G6 的 E-gate 可并行，Track B 全程并行。
- 比迁移文档的 23–25 人日多出的部分：G0 治理/工具链/基线、G3.0 真 TV lane、G6 性能采集/噪声/降级、G5 Haze 预检、G4 覆盖迁移、G6 真机与金标评审。

---

## 10. 不在本方案内

- 不改手机 UI，不重做手机玻璃。
- 不引入 Haze/Liquid 生产依赖（除独立 PoC 通过决策）。
- 不重写 LibreTV 上游，不做管理 WebUI。
- 不产品化豆瓣账号；保持 dev 隔离；G6.8 明确 stable-source gate。
- 不做 R8/Baseline Profile 优化；`isMinifyEnabled=false` 在报告中写明，另立项目。
- 不做 4 小时长时播放；本轮 30 分钟热稳定 + 20 轮内存，4h 列为后续未验证项。
- 不承诺模拟器等于真机 60fps；不做 4K/HDR 认证。
- 不修改历史 release notes；不覆盖已有签名与发布记录。
- 不合并 `dev-compose` 到 `dev`，直到 T-gate 通过并单独确认。

---

## 11. 已确认执行参数

用户在 2026-11-07 确认：

1. **执行环境**：本机补齐 Android 工具链；Android Studio 已装；CLI 组件见 Appendix A。
2. **Web 时机**：G3 切入口 → 15–30 分钟真机冒烟 + `archive/tv-web-5c24360` → G4 立即删除 Web。
3. **玻璃策略**：功能优先——G1/G2/G5 先做全表面/大面积玻璃；G6 才按测量降级。
4. **Haze**：先 0.5 天 AAR 预检；`minCompileSdk>36` 即停；预检通过才做 PoC。
5. **真机**：已有 Android TV / 盒子，`tvNative` 与 T-gate 可执行。

下一步：按 Appendix A 核对本机工具链；确认后再从 G0.0 开始。用户当前要求“继续完善方案，不执行”，所以本方案尚未提交、未构建、未安装。

---

## Appendix A — 本机 Android 工具链补齐清单

用户已安装 Android Studio。以下 CLI/SDK 组件逐项确认；Android Studio 自带 SDK/Emulator，但不一定自带 CLI Gradle 8.13、Android TV system image、GNU timeout。

### A.1 组件核对

| 组件 | 检查命令 | 补齐方式 / 备注 |
|---|---|---|
| JDK 21 | `java -version` | 已有 Temurin 21；Android Studio 也可 |
| Android SDK Platform 36 | `sdkmanager --list_installed` | Android Studio SDK Manager |
| Build-Tools 36.x | `sdkmanager --list_installed` | SDK Manager |
| Platform-Tools (`adb`) | `adb version` | SDK Manager |
| cmdline-tools (`sdkmanager`/`avdmanager`) | `sdkmanager --version` | SDK Manager → Command-line Tools |
| Emulator | `emulator -version` | SDK Manager |
| Android TV system image | `sdkmanager --list \| grep android-tv` | 优先 API33 arm64-v8a（Apple Silicon）；记录实际 API |
| AVD `cinematv-tv` | `avdmanager list avd` | 见 A.2 |
| Gradle 8.13 | `gradle --version` | 仓库无 `gradlew`；用 SDKMAN/Homebrew/下载 `gradle-8.13-bin` |
| GNU `timeout` | `timeout --version` / `gtimeout --version` | macOS 可能没有；装 `coreutils` 或用脚本的 `bounded()` 包装（若提供） |
| Python 3.10+ | `python3 --version` | 系统 |
| Node 20/22 + npm | `node -v && npm -v` | `backend/tv-bff` 与 G4 删除前的 Web 构建 |
| `gh` CLI | `gh --version` | G0.2 `workflow_dispatch` |
| `local.properties` | `cat local.properties` | `sdk.dir=<实际 SDK 路径>`；不提交 |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `echo` | 可选；`local.properties` 优先 |

### A.2 AVD 创建（Apple Silicon 示例）

```sh
sdkmanager "system-images;android-33;android-tv;arm64-v8a"
avdmanager create avd -n cinematv-tv \
  -k "system-images;android-33;android-tv;arm64-v8a" \
  -d tv_1080p
# 在 ~/.android/avd/cinematv-tv.avd/config.ini 确认：
# hw.lcd.width=1920, hw.lcd.height=1080, hw.lcd.density=320, hw.ramSize=2048
emulator -avd cinematv-tv -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu host -memory 2048 -cores 4 -vsync-rate 60
adb devices
adb shell getprop ro.build.characteristics   # 期望包含 tv
adb shell dumpsys uimode | grep -i television
```

### A.3 一键核对

```sh
java -version
gradle --version
sdkmanager --list_installed
avdmanager list avd
adb devices
emulator -list-avds
python3 --version
node --version && npm --version
timeout --version || gtimeout --version
```

### A.4 注意事项

- 当前 worktree 仍未检测到 `~/Library/Android/sdk`、`gradle`、`emulator`；以 Android Studio 的实际 SDK 路径为准，不假设默认路径。
- 不提交 `local.properties`、keystore、token 或私有端点。
- 如果 `timeout` 缺失，必须用脚本的 `bounded()` 包装（若提供）或 `gtimeout`；不能让脚本无超时运行。
- 如果 API33 Android TV arm64 镜像不可用，选可用的最高 Android TV arm64 镜像并记录实际 API；不要拿手机镜像改 1080p 冒充 TV。
- 本清单只用于核对，不代表已安装/已通过；G0.1 记录实际结果。
