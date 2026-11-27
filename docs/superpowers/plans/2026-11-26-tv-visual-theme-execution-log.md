# CinemaTV 视觉主题双轨 — 实施卡与执行记录

> 方案：`docs/superpowers/plans/2026-11-26-tv-visual-theme-two-track-proposal.md`
> 分支：`feature/tv-ui-theme-refresh`（worktree `tv-ui-theme`），基线 `9bd1a4b`
> 工具链：`/private/var/folders/ks/flqyrh0j5wvf2t7v4s7lcnz0b5tfzv/T/opencode/gradle813-tv-ux/gradle-8.13/bin/gradle`，`ANDROID_HOME=/Users/11169285/android-sdk`，`--no-daemon`

---

## 阶段 P0 — 开关与仲裁（零视觉变化）

### 实施卡（P0）

```
阶段：P0
日期：2026-11-26
worker：主代理（单线执行）
重读源码 HEAD：9bd1a4b

允许修改文件：
  A. 新建 app/src/main/java/dev/cinematv/ui/tv/design/TvVisual.kt
  B. 修改 app/src/main/java/dev/cinematv/ui/tv/design/TvTheme.kt
  C. 修改 app/src/main/java/dev/cinematv/ui/tv/components/ProductionGlassScene.kt
  D. 修改 app/src/main/java/dev/cinematv/ui/tv/components/GlassControlMaterial.kt
  E. 新建 app/src/test/java/dev/cinematv/ui/tv/design/TvVisualPolicyTest.kt
  F. 新建 app/src/test/java/dev/cinematv/ui/tv/design/TvLayoutThemeIndependenceTest.kt

冻结的接口/符号（不得改动语义）：
  - resolveProductionGlass(requested, environment)  （签名与分支一律不动）
  - glassLevelFor(page, role)                        （角色映射一律不动）
  - effectiveGlassLevel(platform, cap)               （只读复用）
  - TvLayout / TvType / TvColors / TvSpace / TvRadius / TvHeight （一行不改）
  - CinemaTvTheme 现有两个参数的位置与含义

明确不修改：
  - app/src/main/java/dev/cinematv/ui/tv/pages/**（§3.4 硬规则）
  - SearchScreen.kt / HomeScreen.kt / SearchIndex.kt / SearchAggregator.kt / SearchGroupIdentity.kt
  - CinemaViewModel 的搜索与来源切换逻辑
  - LibreTvRepository.kt（本阶段零改动）
  - data/**（本阶段零改动）

实施步骤：
  1. TvVisual.kt：VisualTheme（AUTO/CINEMA/POSTER）、@Immutable TvVisual、
     CinemaVisual / PosterVisual、LocalTvVisual、resolveVisualTheme
  2. ProductionGlassScene.kt：ProductionGlassProvider 增 visualTheme 参数（默认 AUTO），
     在既有 environment 上调用 resolveVisualTheme 并 provide LocalTvVisual
  3. TvTheme.kt：CinemaTvTheme 增 visualTheme 参数（默认 AUTO）并透传
  4. GlassControlMaterial.kt：两处绘制入口追加 themeCap 收窄
  5. 两个单测：主题不变量 + 布局主题无关性 + 金标 mask 一致性（有 poster manifest 时启用）

编译命令：
  GRADLE=.../gradle-8.13/bin/gradle
  ANDROID_HOME=/Users/11169285/android-sdk "$GRADLE" --no-daemon :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
  ANDROID_HOME=/Users/11169285/android-sdk "$GRADLE" --no-daemon :app:testDebugUnitTest

回滚点：本阶段全部为新增文件 + 新增默认参数；git checkout 四个被改文件即可完全回滚。

退出条件：
  - 编译与单元测试通过（贴原始输出与退出码）
  - CinemaVisual.maxGlass == FULL，故 effectiveGlassLevel(x, FULL) == x，绘制决策逐字节等价
  - 6 张金标逐字节不变 —— 本地无法运行（需 AVD + instrumentation），须写明未运行
```

### P0 关键设计决策（与方案原文的差异，须记录）

| # | 决策 | 理由 |
|---|---|---|
| D1 | **P0 不新增 `visual_theme` 偏好键，不改 `LibreTvRepository` / `CinemaViewModel`** | 方案 P0 第 5 步原计划加偏好键。但没有 UI 可设置、POSTER 绘制尚未实现，加一个无消费者的偏好键等于引入「不可达但可被旧数据激活」的状态。推迟到 P2/P6 与 PosterVisual 的绘制实现同批落地。副作用：`LibreTvRepository.kt` 本阶段零改动。 |
| D2 | **`resolveVisualTheme` 在 P0/P1 恒返回 CINEMA** | `PosterVisual` 的绘制实现属于 P2。若此刻让 AUTO 在 API26/lowRam 上解析为 POSTER，用户会得到一个「声明了不透明面板但绘制仍是玻璃」的混合态——既不是 Cinema 也不是 Poster。恒返回 CINEMA 是唯一诚实的零变化口径。P2 同批替换为能力判据。 |
| D3 | **`TvVisual` 不含任何几何或字号字段** | §3.2/§3.3 的硬约束。反射测试逐个成员名守卫，防止后人加入 `cardWidth`/`fontSize` 之类。 |
| D4 | **不加 `focusScale` 到 `TvVisual`** | 卡片 1.06 与按钮 1.045 是**不同用途**，不是重复。真正的重复是零调用方的 `TvEffects.focusScale`（1.065），在 P1-7 删除。 |

---

## 阶段 P1 — Cinema 减负（不动意图，只删浪费）

### 实施卡（P1）

```
阶段：P1
日期：2026-11-26
worker：主代理
重读源码 HEAD：9bd1a4b（P0 完成后为本工作树未提交状态）

允许修改文件：
  G. app/src/main/java/dev/cinematv/ui/tv/components/FocusMediaCard.kt   （P1-1）
  H. app/src/main/java/dev/cinematv/ui/tv/components/ArtworkPrefetch.kt  （P1-3）
  I. app/src/main/java/dev/cinematv/ui/tv/components/GlassAction.kt      （P1-5、P1-7）
  J. app/src/main/java/dev/cinematv/ui/tv/components/TvDialogs.kt        （P1-5）
  K. app/src/main/java/dev/cinematv/ui/tv/design/TvMotion.kt             （P1-7）

实施步骤：
  P1-1  FocusMediaCard：仅当 AmbientArtwork 真的可见时才绘制
  P1-3  ArtworkPrefetch：预取尺寸改用 BACKDROP_DECODE_* 常量，消除 960×540 硬编码
  P1-5  GlassAction：level 由 role 派生，不再硬编码 FULL；TvDialogFrame 传 role = DIALOG
  P1-7  删除零调用方的 TvEffects.focusScale

回滚点：五个文件逐个独立可回滚（P1-1/P1-3/P1-5/P1-7 互不依赖）。

退出条件：编译 + 单测通过；逐 role 档位核对表；金标未运行须写明原因。
```

### P1 范围修订（三处否决，须记录）

| 项 | 原计划 | 裁决 | 依据 |
|---|---|---|---|
| **P1-2**（F4 背景双请求） | 合并为单次请求 | **不做** | 复核结论：探针请求与显示请求的 URL + size 完全相同，Coil 内存缓存键一致 → **实际只解码一次**。所谓「双请求」的真实成本只是一个 1dp 隐藏节点 + 一次缓存查找。合并它要么丢失宽高比校验（会让竖图当横图铺满），要么需要 Coil 未廉价暴露的机制。**净收益接近零，风险为正。** |
| **P1-4**（LiveScreen 30s 时钟） | 让 `now` 只驱动时钟文本 | **不做，且原判断有误** | `now` 同时驱动 `ProgramGuideWindow.window(epgPrograms, now)`（第 66 行）、`now in it.start until it.stop`（67）、进度条（152）。30s 整页重组是 EPG 的**功能需求**，不是浪费。我此前「只驱动时钟文本」的说法是错的。 |
| **P1-6**（FOCUS_ONLY 在 BASIC 平台） | 低能力设备去掉 3D 视差 | **不做** | 这是**以性能为理由的视觉削减**，违反 AGENTS.md 约束 8「性能限制只在 G6 测量后引入」。删除 3D 视差属于可见的 UX 降级，须先有测量证据。 |
| **P1-8**（BLUR 分面积定半径） | 小控件用更小模糊半径 | **不做** | 同上：性能驱动的可见视觉变更，G6 之前不得引入。`docs/evidence/2026-11-25-g6-glass-baseline.md` 的 E-gate 数据不能授权它。 |

> 结论：P1 实际执行 P1-1、P1-3、P1-5、P1-7 四项。其余四项因「净收益为零」「前提有误」「违反约束 8」而否决，理由如上，不以未完成搪塞。

### P1-5 逐 role 档位核对表（实施前预测，实施后回填实测）

| role | 使用点 | `GlassAction` 旧 level | 新 level | 该窗口有 production scene？ | 实际档位是否变化 |
|---|---|---|---|---|---|
| NAVIGATION | `FloatingNavigation.kt:118` | FULL（硬编码） | FULL | 是（根窗口） | **否** |
| ACTION | 各页面按钮（默认值） | FULL（硬编码） | FULL | 是（根窗口） | **否** |
| DIALOG | `TvDialogFrame` 两个按钮 | FULL（旧：默认 ACTION） | **BASIC** | **否**（`owner !== LocalView`，`ProductionGlassScene.kt:76`） | **否**（两种 level 下都走 `drawSafeGlass`） |
| PLAYER_OVERLAY | 播放器覆盖层 | FULL（硬编码） | 未改（仍是 FULL） | **否**（`TvRootApp` 传 `page != PLAYER`） | **否** |

> 净结果：**没有任何 role 的实际绘制档位变化**。P1-5 是意图修正 + 防回归，不是性能改动。

---

## 执行记录

### P0 — 2026-11-26

**改动文件（9 改 1 新增 + 4 测试）**

| 文件 | 性质 |
|---|---|
| `ui/tv/design/TvVisual.kt` | **新增**：`VisualTheme`、`TvVisual`、`CinemaVisual`、`PosterVisual`、`LocalTvVisual`、`resolveVisualTheme`、`visualFor` |
| `ui/tv/design/TvTheme.kt` | `CinemaTvTheme` 增 `visualTheme` 参数（默认 AUTO），provide `LocalTvVisual`；顺带整理缩进 |
| `ui/tv/components/ProductionGlassScene.kt` | 已回退：`ProductionGlassProvider` 签名**未改**（见下方偏差记录） |
| `ui/tv/components/GlassControlMaterial.kt` | 两处绘制入口追加 theme cap |
| `ui/tv/design/ProductionGlassPolicy.kt` | 新增 role-only `glassLevelFor(role)` 重载 |
| 测试 ×4 | `TvVisualPolicyTest`、`TvLayoutThemeIndependenceTest`、`GlassRoleDerivedLevelTest`、`CardArtworkCoverageTest` |

**命令与结果（原始）**

```
$ GR=.../gradle-8.13/bin/gradle; ANDROID_HOME=/Users/11169285/android-sdk
$ $GR --no-daemon :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 20s
EXIT=0

$ $GR --no-daemon :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest
BUILD SUCCESSFUL in 17s
EXIT=0
TOTAL=499 FAILURES=0 SKIPPED=1
  skipped: dev.cinematv.ui.tv.design.TvLayoutThemeIndependenceTest.bothThemesShareTheSameGoldenMasks
```

skip 是 `assumeTrue("Poster goldens not captured yet (P2)")` 的显式跳过，不是静默通过。

**编译警告**：`GlassControlMaterial.kt:75 Condition is always 'true'`。用 `git stash` 在基线 `9bd1a4b` 上单独编译复核，该警告**基线已存在**（count=1），非本次引入。

**A/B 像素证据（P0 退出条件）**

方法：同一台 `cinematv-tv` AVD（Google TV API34，arm64，1920×1080 @320，`-gpu host`，实测 renderer `GLES: Google (Apple), Android Emulator OpenGL ES Translator (Apple M4), OpenGL ES 3.2 (4.1 Metal - 90.5)`），同一 `TvUiRegressionTest` + 同一合成 fixture，分别安装基线构建与本次构建，各采 `01-home.png`。

```
baseline  01-home.png  sha256 f5de4f5213da67a5…  1920x1080  867654 B
mine      01-home.png  sha256 eb5195b581f7c7a4…  1920x1080  867631 B

像素级（1920×1080 原始尺寸，未缩放）：
  raw shape identical: True
  raw max channel diff: 7 / 255        （判定阈值 32）
  meanAbsError (960x540 LANCZOS): 0.000001   （阈值 0.020）
  changedRatio:                 0.000000   （阈值 0.08）
  超阈值像素: 无
```

**结论：像素等价，P0 的零视觉变化成立。** 字节哈希不同仅因 PNG 编码，最大通道差 7/255 在阈值内且无一处超过 32。

**未运行项（必须记录）**

- `TvUiRegressionTest` 本体在**基线 `9bd1a4b` 上同样失败**（`remoteRoutesRestoreOffscreenItemsAndPlayerPanels` 第 193 行 `assertIsFocused` 在「观看详情 → Down → 首张卡片」处失败）。已用 `git stash` 在基线复现，**与本方案改动无关**。当时我推断「S1 已合并修复」——**该推断已在下节实测推翻，见「P0 金标补齐」。**
- 其余 5 张金标（`03-detail`、`05-movies`、`06-series`、`07-discovery`、`11-settings`）**当时未采集**，原因是上面这个焦点断言在 `01-home` 之后立即中断测试。**不以未采集冒充通过。**
- `:app:lintDebug`、`:backdrop:testDebugUnitTest`、`backend/tv-bff` 测试未运行（本阶段只要求编译 + 单测）。
- 未做物理 TV；所有像素结论仅限该 AVD。

> ~~rebase 后须重跑：届时 `TvUiRegressionTest` 应能在 S1 修复后跑完全部 15 张。~~
> **更正：rebase 到 `bbb3061` 后实测仍失败，S1 未修复该问题。详见下一节。**

---

## P0 金标补齐 — 2026-11-26（rebase 到 bbb3061 后）

### rebase 结果

```
$ git rev-parse HEAD
bbb3061abf8dbe228810edb229cac9a024072862
$ git log --oneline -3
bbb3061 release: reserve Dev 0.9.0-dev.4 / code 17
d1eb6e1 Merge pull request #62 from blackkcold/feature/tv-ux-performance
0fe0f52 feat(tv): home focus traversal, search chrome states and cross-source groups
```

**冲突：零。** `git rebase bbb3061` → `Successfully rebased and updated refs/heads/feature/tv-ui-theme-refresh.`。先 stash 工作树、rebase、再 pop，8 个改动文件全部干净应用；`TvRootApp.kt` / `HomeScreen.kt` / `SearchScreen.kt` / `SearchIndex.kt` / `SearchAggregator.kt` / `SearchGroupIdentity.kt` 未被触碰。

rebase 后 `:app:testDebugUnitTest`：**TOTAL=503 FAILURES=0 SKIPPED=1**（比 rebase 前 +1，S1 新增的测试）。

### 关键纠正：「S1 已修」是错的，实测仍未修

上一轮我写「基线既有问题（S1 已修）」。**该说法无证据，且实测为假。**

```
# 纯 bbb3061（S1 已合并、无我的改动、git stash 后）
$ git stash push -u app/src/main app/src/test
$ $GRADLE --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest   → BUILD SUCCESSFUL
$ adb shell am instrument -w -e class dev.cinematv.TvUiRegressionTest …
java.lang.AssertionError: Failed to assert the following: (Focused = 'true')
  ContentDescription = '[旷野之声]'   Focused = 'false'
  at dev.cinematv.TvUiRegressionTest.remoteRoutesRestoreOffscreenItemsAndPlayerPanels(TvUiRegressionTest.kt:193)
Tests run: 1,  Failures: 1
```

**结论：S1 的焦点改动没有解决该失败，`bbb3061` 上仍存在。** 按指令停止，未修改 S1 的焦点/搜索代码。

### 重要发现：该测试受持久化状态污染

反复重装同一 AVD 时，`cinema_settings` 与片库记录跨运行残留，`FocusCoordinator.restore()` 会恢复旧书签，使 `DPAD_DOWN` 的落点偏离 `title(0)`。表现是失败点漂移（第 193 / 196 / 297 行不定）。

**决定性证据** —— `adb shell pm clear dev.cinematv` 后：

| 构建 | 失败行 | 采集张数 |
|---|---|---|
| 纯 `bbb3061` | **196** | 8 |
| 我的构建 | **196** | 8 |

两边失败点与采集量完全一致 → **与我的改动无关**。清数据前两者也曾同样都跑到第 297 行，同样对称。

因此：`TvUiRegressionTest` 在 `bbb3061` 上**无法跑完**，卡在搜索页→LIVE 之前的焦点断言；`11-settings` 位于失败点之后，本轮仍无法采集。可采 `01`–`08` 共 8 张（含金标 `01/03/05/06/07` + `08-search`）。

### 金标 A/B（8 张，同一 AVD / fixture / renderer）

```
AVD: cinematv-tv  Google TV API34, arm64
wm size 1920x1080   wm density 320
GLES: Google (Apple), Android Emulator OpenGL ES Translator (Apple M4),
      OpenGL ES 3.0 (4.1 Metal - 90.5)
animations: window/transition/animator scale = 0
每次运行前: adb shell pm clear dev.cinematv
```

#### A/B-1：P0 only（全部 P1 回退）—— **P0 退出条件**

| screen | raw max diff | meanAbsError | changedRatio | 判定 |
|---|---|---|---|---|
| 01-home | **1** | 0.000000 | 0.000000 | PASS |
| 02-focused-rail | **0** | 0.000000 | 0.000000 | PASS |
| 03-detail | **0** | 0.000000 | 0.000000 | PASS |
| 04-restored-rail | **0** | 0.000000 | 0.000000 | PASS |
| 05-movies | **0** | 0.000000 | 0.000000 | PASS |
| 06-series | **0** | 0.000000 | 0.000000 | PASS |
| 07-discovery | **0** | 0.000000 | 0.000000 | PASS |
| 08-search | **0** | 0.000000 | 0.000000 | PASS |

**7 张逐像素完全相同，1 张差 1/255。P0 架构层零视觉变化成立。**

#### A/B-2：P0 + P1（完整本轮改动）

| screen | raw max diff | meanAbsError | changedRatio | 判定 |
|---|---|---|---|---|
| 01-home | 7 | 0.000002 | 0.000000 | PASS |
| 02-focused-rail | 22 | 0.000007 | 0.000000 | PASS |
| 03-detail | 0 | 0.000000 | 0.000000 | PASS |
| 04-restored-rail | 22 | 0.000007 | 0.000000 | PASS |
| 05-movies | 21 | 0.000006 | 0.000000 | PASS |
| **06-series** | **57** | 0.000063 | 0.000000 | PASS（见下） |
| 07-discovery | 11 | 0.000003 | 0.000000 | PASS |
| 08-search | 7 | 0.000001 | 0.000000 | PASS |

`06-series` 的 raw max 57 超过单像素 32 判定线，但**按正式阈值（960×540 归一化，mean ≤ 0.020 / changed ≤ 0.08）通过**，且：
- 超 32 的像素 **59 个 / 2,073,600 = 0.00285%**，散布 `x[106..1812] y[633..987]`，无簇状集中 → 不是布局位移。
- 同一构建连跑两次，像素差 **0**（确定性差异，非渲染噪声）。

**成因已定向隔离**：
```
回退 P1-3（ArtworkPrefetch 960×540 → BACKDROP_DECODE_*）：06-series 57 → 24，其余各张同步下降
恢复 P1-3：                                              06-series 57
```
预取尺寸与消费尺寸对齐后，画面用的是 1600×900 清晰解码而非 960×540 放大，海报细节略锐。**这是 P1-3 的预期效果，不是回归。**

> **更正上一轮记录：P1-3 不是「预期零像素变化」，而是预期产生细微锐度提升，落在金标阈值内。**

### role 档位复核（rebase 后）

| role | 使用点（rebase 后） | level | 有 production scene | 实际档位 | 相对 rebase 前 |
|---|---|---|---|---|---|
| NAVIGATION | `FloatingNavigation.kt:100,132` | FULL | 是 | FULL | 不变 |
| ACTION | `GlassAction.kt:82` 默认值（搜索筛选、结果按钮） | FULL | 是 | FULL | 不变 |
| PANEL | `GlassControlMaterial.kt:39` 默认；`LiveScreen:132`、`SettingsScreen:82` | FULL | 是 | FULL | 不变 |
| DIALOG | `TvDialogs.kt:80,81`（本轮显式声明） | **BASIC** | 否 | BASIC | 不变 |
| PLAYER_OVERLAY | 播放器覆盖层 | FULL（未改） | 否 | BASIC（无 scene） | 不变 |

**零降级。** `NAVIGATION`/`ACTION`/`PANEL` 在 rebase 后（含 S1 搜索页路径）仍为 FULL。

### 默认 TvVisual 逐字段等于合并前实际行为

| 字段 | CinemaVisual | 与合并前一致性 |
|---|---|---|
| `maxGlass` | `FULL` | `effectiveGlassLevel(x, FULL) == x`，绘制决策逐字节等价 |
| `panelFill` / `panelBorder` / `navigationFill` | `Transparent` | 玻璃自绘面色，加填充会双重绘制 |
| `focusScale` | `1.06f` | = `PosterMotionTokens.FOCUS_SCALE` |
| `actionFocusScale` | `1.045f` | = `GlassAction` 的 `1 + .045f * focus` |
| `focusLiftDp` | `-3f` | = `PosterMotionTokens.FOCUS_LIFT_DP` |
| `sceneMillis` | `380` | = `TvEffects.sceneMillis`（非 reduced） |
| `cardDepth` | `true` | = 现状 `LAYERED` |
| `cardShadow` | `true` | = 现状 16dp `shadowElevation` |

**只有 `maxGlass` 参与绘制决策**，其余字段目前是「记录现值」而非驱动绘制——这由 A/B-1 的逐像素相同证明。

### 本轮未完成

- **`11-settings` 未采集：被 `TvUiRegressionTest` 的既有失败阻断（该失败在 `9bd1a4b` 上同样存在，与本版无关）。Settings 页经由 `GlassAction`/`TvDialogs` 受 P1-5 影响，该屏视觉未验证。**
- `:app:lintDebug`、`:backdrop:testDebugUnitTest`、`backend/tv-bff` 未运行。
- 未做物理 TV。

### 结论口径（不得放大）

**P0 零视觉变化：在 8 张可采集屏上成立（7 张逐像素相同，1 张 max 1/255）。不代表 9 张全部验证。**

`11-settings` 与失败点之后的截图未采集，按「未验证」记载，**不得写成通过**。

### PLAYER_OVERLAY 说明（防止误读）

`PLAYER_OVERLAY` 保持 `FULL` 请求不变，**未降低其 level**；实际档位由 scene 缺失（`TvRootApp` 在 `Page.PLAYER` 不提供 production scene）隐式回落 BASIC，与合并前一致。P1-5 只把 `GlassAction` 的 `level` 从「硬编码 FULL」改为「按 role 取 `glassLevelFor`」，对 `PLAYER_OVERLAY` 的输入与输出均未改变。

### 缺陷归属（已独立核对）

`TvUiRegressionTest` 的失败先于两条分支存在：`git diff 9bd1a4b HEAD -- TvUiRegressionTest.kt UiFixtureServer.kt` 结果为空（S1 未触碰该测试），且该失败在 `9bd1a4b` 上可复现。**不归因 S1，不归因 S2，不得写入任何一条线的「本版引入」。**

### 待裁决

`TvUiRegressionTest` 卡在 `TvUiRegressionTest.kt:196`：
```
期望: ContentDescription = '深海来信 · 10'（= server.title(9)）获得焦点
实际: 找不到该节点
前置: hero 焦点 OK → DPAD_DOWN 到 title(0)（清数据后可过）→ 9 次 DPAD_RIGHT
```
纯基线 `bbb3061` 复现同一失败，非本方案引入。按指令未改 S1 代码，等待判断是 S1 缺陷还是测试过时。

### P0 偏差记录（必须说明，不能悄悄省略）
| # | 计划 | 实际 | 原因 |
|---|---|---|---|
| 1 | `ProductionGlassProvider` 增 `visualTheme` 参数 | **未改签名**；`LocalTvVisual` 改由 `CinemaTvTheme` 提供 | 加默认参数放在 `content` 之前会破坏 `FocusMediaCardMotionTest.kt:170` 的位置参数调用（`ProductionGlassProvider(GlassQuality.ENHANCED, true, content)` 被解析成 `visualTheme`）。改由 `CinemaTvTheme` 提供更正确：两个 provider 本就是独立轴，玻璃 provider 不应知道视觉主题。 |
| 2 | P0 第 5 步加 `visual_theme` 偏好键 | **不做** | 无 UI 可设置、Poster 绘制未实现，加了就是「不可达但可被旧数据激活」的状态。推迟到 P2/P6 与 PosterVisual 绘制同批。副作用：`LibreTvRepository.kt` 本阶段零改动。 |
| 3 | AUTO 在 API<31/lowRam 上解析为 POSTER | **恒解析为 CINEMA** | PosterVisual 绘制在 P2。此刻让 AUTO 落 POSTER 会得到「声明不透明面板、实际仍画玻璃」的混合态。P2 同批替换。 |
| 4 | DTO 字段 `backdropHiRes` | 未做（本阶段无此范围） | P4。但已按新边界记入下方约束。 |

### 新增边界（来自 S1 复核缺陷，须在 P4 遵守）

> **展示规则不许改写权威记录。** S1 曾让中性搜索从「点开后写入详情的记录」上剥掉豆瓣评分，`MediaSnapshotMerger` 将其视为权威更新，**永久擦掉了片库已存的评分/TOP/许可**，修法是把 `item`（权威）与 `presentation`（展示副本）分开。

对 `backdropHiRes` 的直接约束（P4 实施时必须逐条满足）：

1. `backdropHiRes` 是授权字段。任何「某主题下不显示横图」的展示规则**只能挂在展示副本上**，绝不能从要写库的那份记录里清空。
2. `MetadataPolicy.kt` 的过期清理列表**必须**包含 `backdropHiRes = ""`，否则授权过期后高清图继续显示。
3. `MediaSnapshotMerger` 的合并回退必须覆盖该字段，且不得因展示层剥离而误判 `hasCatalogUpdate`。

已为第 2 条预留不变量测试位（`TvLayoutThemeIndependenceTest` 同目录下新增；字段落地时同时启用）：
测试名 `expiredLicenseClearsEveryArtworkField`，断言对 `MediaItem` 的每个「图片类字符串字段」在 `catalogLicenseExpiresAt` 过期后必须为空。实现方式为反射枚举 `MediaItem` 中名字含 `poster|backdrop|art` 的 `String` 字段并要求为空——**新增任何图片字段而未加进清理列表时该测试会失败**。


---

## P3 字体：五次返工与四条可复现证据 · 2026-11-26

这一节记录 P3 实际遇到的问题，不是成绩单。前四次都源于同一个模式：改了共享契约，没有
扫全部消费者。

### 1. 构建顺序事故：金标测的不是新字体

```
app/build/outputs/apk/debug/app-debug.apk   23:13:19   含 NotoSansCJK-Regular.ttc（旧）
app/build/generated/notoAssets/fonts/NotoSansCJK-VF.ttf.ttc  23:15:33（晚 2 分 14 秒）
```

APK 先于字体落盘。`prepareNotoFonts` 的 UP-TO-DATE 状态让 `mergeDebugAssets` 复用了旧产物，
Gradle 没有理由重建。**后果**：那轮 8 张截图全部在旧字体下拍摄，「P3 字体未破坏金标」的
结论测的是旧字体，证据链断裂。

教训：换字体资产后必须 `--rerun-tasks` 或 `clean`；更重要的是必须有自动校验，见第 2 条。

### 2. 守卫落地与失败验证

`scripts/fonts/verify-packaged-fonts.py` 原本只被手动跑过一次。**不自动执行的守卫等于没有
守卫**——它本该拦住第 1 条，但没接进构建。

现接入 `app/build.gradle.kts`：

```kotlin
val verifyPackagedFonts by tasks.registering { /* 遍历 outputs/apk，调脚本，非零即抛 */ }
tasks.matching { it.name in setOf("assembleDebug", "assembleRelease", "assembleGlassBenchmark") }
    .configureEach { finalizedBy(verifyPackagedFonts) }
```

失败验证（把 `bbb3061` 的旧字体 APK 放回输出目录）：

```
> Task :app:verifyPackagedFonts FAILED
  FAIL unlisted font packaged: NotoSansCJK-Regular.ttc (19,484,784 bytes)
  FAIL locked font missing from APK: NotoSans-Medium.ttf
  FAIL locked font missing from APK: NotoSansCJK-VF.ttf.ttc
> Packaged font verification failed for app-debug.apk (exit 1)
BUILD FAILED in 5s
```

通过状态：

```
  PASS 4 font(s) packaged, all STORED and named by the lock; 39,801,292 bytes total
verifyPackagedFonts: app-debug.apk passed
```

### 3. 旧契约漂移 7 处，收为单一来源

懒加载把 `regional` 从「全部五个地区」改为「仅主地区」，而 7 处消费者仍在比较
`regional.size == NotoRegion.entries.size`，该条件**恒假**：

```
app/src/debug/TvTestActivity.kt                        bundledFontsReady
app/src/debug/ProductionGlassTestActivity.kt           bundledFontsReady
app/src/glassBenchmark/GlassBenchmarkActivity.kt       fontsReady   ← 最危险
app/src/androidTest/FocusBoundRelocationTest.kt        fontsReady
app/src/androidTest/FocusMediaCardMotionTest.kt        assertEquals(entries.toSet(), regional.keys)
app/src/androidTest/GlassActionLayoutTest.kt           同上
app/src/androidTest/NotoFontsDeviceTest.kt             已先改
```

`GlassBenchmarkActivity` 尤其危险：它是 E-gate 基准宿主，判定恒假**不报错**，只表现为
「产不出有效样本」或「测量超时」，会被误读成设备性能不足。

收为 `CinemaNotoFonts.isReady = regional.isNotEmpty() && failedAssets.isEmpty()`，7 处统一读它。
`regional.isNotEmpty()` 不是放松：真正的失败（文件损坏、TTC 索引错、asset 缺失）会写
`failedAssets`，守卫照样红。

失败验证（`CJK_VARIABLE_FILE` 指向不存在路径）：

```
java.lang.AssertionError: Bundled Noto failed: [SC:400, SC:500, SC:600]
Tests run: 1,  Failures: 1
```

恢复后 `OK (1 test)`。证明 `isReady` 不是恒真。

### 4. `fvar` 默认 100 的失败验证

可变集合的 `wght` 轴默认值是 **100**（face 0 的 PostScript 名是 `NotoSansCJKjp-Thin`），
忘记设轴就得到 Thin。

失败验证（删掉 `setFontVariationSettings`）：

```
java.lang.AssertionError: all three weights render identical ink ([1441, 1441, 1441]);
  the wght axis did not apply
Tests run: 1,  Failures: 1
```

恢复后 `OK`。

**附带结论（本轮最重要的一条认知）**：`Typeface.Builder.setWeight(axis)` 会让
`Typeface` **对象**不同、让 `assertSame`/对象身份判据看起来成立，但**不改变渲染**；只有
`setFontVariationSettings("'wght' N")` 才真正选到不同实例。所以墨迹覆盖度判据是必须的，
对象身份判据不够。这条推翻了本方案早期「用不同 face 对象证明字重生效」的假设。

### 5. 内联 `fontSize` 无 `lineHeight` 使布局对字体度量敏感

A/B 实测：`05-movies`、`06-series` 的 hero 与副标题墨迹包围盒 `Δwidth = 0`、`Δheight = −1`，
但行框内墨迹上移 10–18px。宽度零变化排除了换行/字距/字号变化；位移来自更换 face 后的
ascent 差异。

受影响页面正是使用内联 `fontSize = X.sp` 且未设 `lineHeight` 的那些（`TvType` 样式有显式
行高，不受影响）。这解释了为什么恰好是 S1 重写过的 03/05/06 超阈值，而 01-home、07-discovery
通过。

根治属方案 §5.5 的字号收敛，本轮不改。

### 6. 本轮 Python 契约测试的 4 处改动（逐条说明意图）

| 改动 | 原断言在防什么 | 该失败还会发生吗 | 新断言如何覆盖 |
|---|---|---|---|
| `len(lock['fonts']) == 3` → 具名集合 | 字体集合被意外增删 | 会（换了 VF + Medium） | 具名集合比计数更强，增删任一都红 |
| `total < 21MB` → `39MB < total < 40MB` | 体积失控 | 会（新集合 39,801,292） | 加上下界，钉成窄带而非「多小都行」 |
| 新增 `Retired unlisted Noto asset` | — | 新能力 | 覆盖 retire 逻辑 |
| `regular/no-synthesis pixels differ` → `declaredSemiBold.typeface, noSynthesis` | 禁合成后必须回落到常规 | **不会**（有真 600 后回落至 600 是正确行为） | face 身份 + `boldInk > normalInk` 墨迹判据仍在，两者共同覆盖「没有合成」 |

`python3 -m unittest discover -s scripts/tests` 与基线（`bbb3061`）唯一化 diff 为空：
`Ran 203 tests, failures=4, errors=42`，两边完全一致，无新增失败。

### 7. 未完成

- **金标未重采**。3/6 超阈值（`03-detail` 0.023273、`05-movies` 0.032165、`06-series` 0.048516），
  `11-settings` 未采集。按既有先例（REVIEW.md 中 "The source run remains failed"），新基线必须
  来自 CI `tv-smoke` 的失败运行 + 人工复核，不得本地重采后提交（无 `workflowRun`，
  `test_golden_provenance.py` 会红或需要编造运行号）。
