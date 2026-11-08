# CinemaTV 原生 TV 整合实施方案（复核优化版）

> 执行方式：使用 `executing-plans`，逐项实施、验证和记录。提交、安装、签名、发布分别取得授权，不由本方案自动触发。

**Goal：** 将 TV 展示层统一为原生 Compose，完成页面、焦点和完整玻璃效果，再按实测优化性能；原生切换通过短真机冒烟后立即删除 TV Web。

**Architecture：** `MainActivity → TvRootApp` 是唯一 TV 入口；页面位于 `ui/tv`，播放器继续使用 Media3。玻璃沿用 `ProductionGlassScene + cinemaGlassMaterial + ProductionGlassPolicy`，只记录背景层。

**Tech Stack：** Kotlin 2.2.21、AGP 8.13.2、Compose BOM 2026.03.01、Media3 1.11.1、minSdk 26 / targetSdk 35 / compileSdk 36、Gradle 8.13；`backdrop` 固定上游 `896a94a` 及仓库本地修改。

**依据：** 原方案 `docs/superpowers/plans/2026-11-07-native-tv-consolidation.md`、`docs/NATIVE_TV_MIGRATION.md`、实际代码与 CI。玻璃现状以 `docs/GLASS_PRODUCTION.md` 为准。

**复核基线：** `dev-compose @ 5c24360493ac5486d9cedd7c890f2877a018b695`，2026-11-07。本文件单独保存，原方案不改。

## 1. 本次修正

以下问题均对照实现和第二处调用方、测试或官方定义复核。

| 原方案问题 | 交叉证据 | 修正 |
|---|---|---|
| G2 要求根 Back 测试通过，G3 才实现根 Back | `TvRootApp.kt` 无 handler；三个测试宿主自行处理返回 | G2 验收页面与局部交互；G3 才验收共享根 Back 和生产入口 |
| 页面改版后直到 G6 才更新金标，期间仍要求 CI 全绿 | `run-device-qa.sh` 强制比较；`compare-ui-goldens.py` 超阈值退出 1 | 视觉变更随阶段评审、更新受影响金标；G6 最终复跑 |
| 新增 lane 只列五处修改，遗漏预览晋升契约 | `preview-release.py:validate` 写死四个设备；`test_preview_release.py` 同样固定 | 同步矩阵、分发、晋升与对应测试；未知 target 仍拒绝 |
| “删 Web 后 CI 不再装 Node”不成立 | `android.yml` 调用 BFF npm 测试；`tv-smoke.yml` 保留官方 LibreTV Node 服务 | 只移除 Android Gradle 对 Node 的依赖，保留 BFF/官方服务所需 Node |
| G6 才增加采集器，却要求在 G4 原提交上执行新协议 | `FrameTimingRecorder` 仅在 `glassBenchmark`；旧 analyzer 固定宿主、尺寸、窗口 | G1 先交付采集协议；G4 删除提交当场复跑，不补改旧提交 |
| 材质成本对比被当成代码回归，且两项同时恶化才失败 | 原 §6.2 比 Basic/Blur；旧 analyzer 的三档比较只是成本差 | 同档、同场景比较两个代码版本；任一主指标确认恶化即回归 |
| `FRAME_TIMELINE_TYPE` 被列为 FrameMetrics 指标；下一帧被当作输入反馈 | Android 官方 FrameMetrics 文档与 Android 16 源码均无此常量；旧 analyzer 已提示边界帧不能证明因果 | 删除不存在的字段；输入与实际响应状态/绘制关联 |
| `GlassSurfaceLevel` 与现有全局材质档关系不明确 | 已有 `GlassRenderMode`；`cinemaGlassMaterial` 读取全局 `LocalTvEffects` | 复用现有枚举，新增“表面上限”，不得绕过用户偏好和平台策略 |
| “最近邻恢复”没有足够的保存信息 | `Bookmark` 只有 region/key；`resolveLazyFocusTarget` 缺失 key 时返回首项 | 稳定 key 优先，另存位置作为缺失项回退；扩展到 grid/list |
| 删除脚本前只迁一个 manifest 断言，发布路径仍不可用 | 通用 candidate 脚本固定旧版本并拒绝豆瓣目录；hybrid 工具另有 Dev13、签名块和来源检查 | 完整迁移 Dev 候选链；不削弱现有 stable/main 隔离 |
| 重复创建 archive tag、G4/G5 删除时间混用，V4 整段沿用会恢复旧限制 | 本地无相关 tag；`AGENTS.md` 同时写 G4/G5；V4 的 bitmap/focus-only 与生产 live scene 不同 | archive 只建一次；统一 G4 删除；V4 只合并仍适用的契约 |

## 2. 固定边界

- 保留 API26 安装、独立手机 UI、Media3 生命周期、缓存、播放身份与进度持久化。
- 不改 LibreTV 上游，不将 provider JSON、凭据或整份 ViewModel 引入新展示 DTO。
- 不升级 Compose/Kotlin/compileSdk；本轮生产继续使用 backdrop。Haze 仅隔离预检/PoC。
- 玻璃功能先完成，再在 G6 根据测量设置面积、数量或材质限制；现有 API、低内存、省电、热压、窗口就绪和失败回退始终保留。
- Dialog 和播放器覆盖层保持 Basic；不采样视频 Surface，不绕过跨 Compose owner 检查。
- `TvRootApp` 拥有 TV 路由返回；Dialog、IME、播放器处理各自局部状态，不重复执行路由返回。
- `AUTO → BLUR`；Full 仅显式 ENHANCED 且 API33+、环境允许时生效。Reduced Motion 不强制改变静态材质档。
- 金标阈值保持 mean ≤ 0.020、changed ≤ 0.08；不扩大遮罩来隐藏布局变化。
- `dev-compose` 的豆瓣账号代码保持 dev-only；不因迁移放宽 stable/main 源码闸门。
- 不复制 GPL 实现；保留 MIT/Apache-2.0 及现有其他第三方许可、源码交付要求。
- T-gate 通过且用户另行确认后，才讨论合并到 `dev`；不自动合并或发布。

## 3. 执行顺序

```text
G0 治理与基线
 → G1 组件、玻璃接口、采集协议
 → G2 页面与焦点
 → G3 真 TV lane、根 Back、切入口、短真机冒烟
 → G4 迁移覆盖与候选工具、立即删 Web、删后验证
 → G5 完整玻璃与视觉定稿、Haze 隔离评估
 → G6 实测降级、真机验收、最终金标与交付
```

每项遵循：先补针对性失败测试 → 最小实现 → 对应测试通过 → 精确提交 CI。视觉变更按 §8 更新金标，不能把预期失败长期留到最后。

### G0 — 治理、环境与基线

**涉及：** `AGENTS.md`、本方案、`docs/evidence/native-tv-baseline-2026-11-07.md`。

- [ ] **G0.1 整理治理输入。** 逐个确认当前 modified/untracked 文件归属，只纳入认可的文件。采用本复核稿后，将 `AGENTS.md` 的 governing plan 指向本文件，并统一为“G3 冒烟后 G4 删除”；不要求清空或提交无关工作。
- [ ] **G0.2 核对本机工具链。** Android Studio 已安装是输入条件；实际 SDK 路径、JDK、Gradle 8.13、platform/build-tools 36、adb、emulator、cmdline-tools 和可用 TV 镜像逐项记录。安装另行确认；CI 使用现有 Java17/Gradle8.13 配置。
- [ ] **G0.3 跑精确源码基线。** 治理内容形成可检出的提交后，分别触发 `android.yml` 和 `tv-smoke.yml`。记录两者实际 checkout SHA、run URL、APK hash 和失败项；`android.yml` 的普通 dispatch 不会自动执行完整 UI job。
- [ ] **G0.4 固定归档。** `archive/tv-web-5c24360` 指向原始 `5c24360`，只创建一次；原始 hybrid APK 与 hash 同时归档。基线 tag `baseline/native-tv-<实际短SHA>` 指向含新治理且完成验证的提交。已存在 tag 先核对，不移动覆盖。
- [ ] **G0.5 记录实际基线。** JVM 数来自 `TEST-*.xml`，device lane 来自实际执行结果；本地 ref 不当作最新远端或 Actions 状态。记录已有失败，先处理阻断本轮的回归。

**Gate 0：** 基线提交、归档、CI 结果和工具链缺口明确；不再沿用旧文档的测试数或“环境已就绪”结论。

### G1 — 基础组件、表面接口与采集协议

**目录：** `app/src/main/java/dev/cinematv/ui/tv/{design,components,focus}/`；测试对应 `app/src/test` / `app/src/androidTest`。

| 任务 | 文件/接口 | 必须验证 |
|---|---|---|
| G1.1 设计令牌与文字 | 扩展 `TvTokens.kt`；新增 `TvText.kt`，`TvTextStyle.toTextStyle()` 委托现有 `CinemaTvText` | 保留 Noto 字体路径；`TvHeight` 使用 chip36/button44/row48/field52；micro110ms 只接新组件；先接 `TvEmptyState` |
| G1.2 按钮与动作组 | `GlassAction.kt`、`TvActionRow.kt`；迁出 `CinemaWidgets.kt` 的函数并更新调用/测试 import | 原签名、图标、selected/disabled 语义与完整 spoken label 保留；默认按钮44dp，chip36dp、row48dp；显式紧凑尺寸继续受测 |
| G1.3 输入与筛选 | `TvField.kt`、`TvChip.kt`；`TvField` 保留 value/onValueChange/isPassword/onSubmit，增加 enabled/error；TvChipRow采用36dp高度/12dp间距 | 遥控聚焦不自动弹 IME；OK 编辑；实体键盘可输入；空提交拦截；`catalog-more:*` tag 保留 |
| G1.4 标题与状态 | `TvHeaders.kt`、`TvStatus.kt`；标题、区块标题、Empty/Loading/Error/StatusLine | 无数据与请求失败可区分；错误可重试；静态页面不人为制造加载态 |
| G1.5 弹窗 | `TvDialogs.kt`；`TvDialogFrame` / `TvConfirmDialog`，480/600dp 为最大宽度并受可用空间约束 | Cancel 默认焦点；一次 Back 只关弹窗；下一次 Back 按所在页规则；D-pad 不逃逸；关闭后回触发器 |
| G1.6 内容与简介 | `ContentRail.kt` 增加可空 action slot；新增 `TvHero.kt`、`TvAboutSection.kt` | `rail:$title` key 不变；Hero 手动翻页、单条不显示指示器；actors/季集/评分来源仅展示已有数据 |
| G1.7 进度与运动 | `TvProgressBar.kt`；保留 `TvMotion.kt`、`PosterMotionPolicy` 既有行为 | 进度视觉4dp；只读进度不伪造触摸目标或可调语义；若可调则遥控操作单独测试；海报1.06/.97不随 token 整理改变 |
| G1.8 表面材质接口 | `CinemaGlassSurface.kt`、`GlassControlMaterial.kt`、`ProductionGlassPolicy.kt`，契约见 §4 | 单一材质枚举；表面只可降档；Dialog/Player Basic；无功能期面积/数量限制 |
| G1.9 原生采集协议 | 下述 source set、采集器、分析脚本和测试 | 后续 G4/G5 可直接执行；同一事件/窗口不重复计帧，保留失败与空样本 |

**G1.2 注意：** Kotlin `typealias` 不能给顶层函数做兼容别名。优先更新 import；确需过渡时使用同签名转发函数，不改变手机分支。连同私有padding modifier一起迁移；同步 `scripts/tests/test_glass_action_selection.py` 中固定文件路径和函数切片，保留其行为断言。`dense` 当前不是 `GlassAction` 的公开参数，不凭旧文档新增它。保留 `GlassActionLayoutTest` 对 35/36/40/44/45dp 等显式尺寸、文本裁切、命中框稳定的覆盖，而非把所有断言直接改成新高度。

**G1.9 具体交付：**

- [ ] 将 `FrameTimingRecorder.kt` 移到 `app/src/perf/java/dev/cinematv/`，作为共享源码分别编入 **debug 目标 APK** 与 `glassBenchmark`，不是只编入测试 APK。`finish` 接收输出位置；普通 release 不携带测试宿主/fixture。
- [ ] 新增 `app/src/androidTest/java/dev/cinematv/TvFrameTrace.kt`、`TvNativePerformanceAcceptanceTest.kt`，用原生测试宿主驱动受控场景；生产入口验证仍由 G3 的 `MainActivity` 测试承担。
- [ ] 为T-gate新增隔离测试根 `app/src/nativeTvAcceptance/`。Gradle参数 `cinematvNativeTvAcceptance=true` 选择release测试，与现有updateAcceptance互斥；只改变测试源码选择，不更改目标app行为、版本或签名策略。该测试APK仅启动 `MainActivity`、驱动合成服务与采集，不引用debug宿主。共享recorder在此编入测试APK并接收目标APK元数据，普通release不增加采集代码。CI增加该模式的 `:app:assembleReleaseAndroidTest` 编译检查。
- [ ] 新建 `scripts/perf_common/` 与 `scripts/native-tv-perf/` 的采集、分析和协议测试，以及 `.github/workflows/native-tv-perf.yml` 的手动入口。路径/字段协议按 §7；保留原 glass-performance profile 与测试，不直接套用旧 analyzer 的固定限制。
- [ ] 新协议覆盖无帧、重复帧、callback 丢失、容量截断、场景未就绪、真实生效档不符和响应超时。先有有效协议基线，不在此阶段设置玻璃面积限制或产品 FPS 门槛。

**Gate 1：** 新组件有针对性测试；玻璃与采集接口可编译；原采集 profile 未退化；涉及共享代码的 phone lane 通过。

### G2 — 页面与稳定焦点

每页统一采用TvText/TvTokens替换字号、间距、圆角和控件高度，光学/艺术常数除外；完成状态、disabled、D-pad/OK、局部弹窗和返回焦点测试，受影响截图当阶段评审。根路由 Back 与系统 IME Back 的完整验收放在 G3，不依赖尚未实现的入口切换。

| 页面 | 实施内容 | 回归重点 |
|---|---|---|
| HOME | Hero 接手动 pager；精选加“查看全部”；移除重复“更多电影”；换一批在加载中禁用；展示 discovery 错误 | `ContentRailVisibilityTest` / `ContentRailTraversalTest`；单/多条 Hero；离屏卡片返回 |
| DETAIL | 加载/不可用分开；使用 About 和动作组；剧集维持4列；状态 picker 统一弹窗 | `TvManualWorkAssociationTest`、`TvWorkNavigationTest`；取消回实际触发器；空剧集不允许播放 |
| MOVIES/SERIES | “全部”同时清 category/year/rated，selected 条件也包含 rated；保留 Adaptive grid | `TvLibraryCatalogControlsTest`；分类、评分、加载更多、过期页与返回焦点 |
| SEARCH | TvField；空白 query 在按钮和提交回调双重拦截；入口优先筛选/已有结果，空结果时也有可达输入；显示无结果/错误 | 搜索 paging；进入页不自动弹 IME；OK 可编辑；历史筛选保持 |
| EXPLORE | 页头、空/加载/失败态；指示器与 coverflow 令牌 | 空推荐；海报焦点与弹窗恢复 |
| LIVE | 两列权重1:2；当前节目只呈现一次；无源/频道/节目分开；统一进度条 | 时钟推进、EPG界限、频道稳定 key；G4.2抽取ProgramGuideWindow时迁移对应断言 |
| LIBRARY | 标题、空态、清空确认；继续使用 `LibraryProgressReviewDialog` | 取消不删除；确认只删目标数据；收藏与状态保留；回到管理入口/原卡片 |
| SETTINGS | 8个 section 的 index/wireName 不变；推荐看板只留 section5；订阅区统一分支；连接、登录、保存、添加源按各自请求状态禁用 | `SourceSubscriptionsUiTest`、`UpdateSettingsUiTest`、`DiagnosticsUiTest`；订阅长预览；敏感字段不进日志 |
| PLAYER | 整理覆盖层与颜色令牌；新增 buffering 态；track panel 保持 Basic | `dev.cinematv.playback.NativePlaybackSessionTest`；保留缓存、进度、切源/集身份、释放；缓冲/错误也可返回 |

- [ ] **G2.1 焦点扩展。** 修改 `FocusCoordinator.kt`、`TvFocusRegistry.kt`、`LazyFocusTarget.kt` 及 Catalog/Search/Live/Detail 调用：保存稳定 key，并补充同 region 的旧位置作为缺失 key 的回退。存在 key 时按新顺序定位；删除时选 `min(旧位置, 新末项)`；空列表回可用页内动作或导航。
- [ ] 为 LazyGrid 与频道/剧集列表提供离屏准备，保留现有 ownership/cancellation guard。新增 `TvFocusRestorationTest`：重排、删除、离屏、列表清空、快速反向输入、新导航打断旧恢复。
- [ ] **G2.2 面板玻璃。** Settings/LIVE/DETAIL/LIBRARY 的实际面板按 §4 接入背景采样。大面积玻璃正常实现，不提前加入面积或每屏单块限制；不存在的面板不为“敷玻璃”额外造容器。

**Gate 2：** 九类页面的状态与局部交互通过；稳定焦点覆盖 list/grid；本阶段金标评审落地；不声称生产 TV 入口已完成。

### G3 — 真 TV lane、根 Back、入口切换与删前冒烟

- [ ] **G3.1 先建 lane。** 新增 `tvNative`，优先可获得的 API33 Android TV 镜像；同时补 API29/31/36 组件/兼容 lane。镜像坐标以 SDK 实际列表为准。`tvNative` 必须证明 characteristics 含 tv、UI mode 为 television，且测试中 `isTv=true`。
- [ ] 迁移 `scripts/hybrid/run-tv-emulator.sh` 的 TV 启动与就绪屏障到非 hybrid 路径。检查启动器 target/profile/arch、超时、日志、退出清理与 matrix 一致；普通 default/pixel 手机镜像仅作组件 lane。
- [ ] 同步 `qa-device-matrix.py`、`run-device-qa-target.sh`、`tv-smoke.yml`、`preview-release.py`、`test_device_qa_harness.py`、`test_device_qa_target.py`、`test_preview_release.py`。完整矩阵须包含新增目标；缺任一要求的 lane/receipt/class 都拒绝晋升，未知 target 继续失败。
- [ ] 将 `dev.cinematv.playback.NativePlaybackSessionTest` 与 `SourceSubscriptionsUiTest#nativeTvRemoteCanReadLongPreviewAndFailedRefreshKeepsImportedSources` 注册进原生 lane。类存在、编译或 assumption skip 均不算执行通过。
- [ ] **G3.2 根 Back 与切入口同批交付。** `MainActivity` 的 TV 分支改 `TvRootApp(model)`，手机分支原样。根 handler 放在页面组合之前，仅处理 IME/路由；移除 `TvTestActivity`、`ProductionGlassTestActivity`、`GlassBenchmarkActivity` 的重复路由 handler。
- [ ] `TvPlayerPage` 保留面板 → 控制条 → returnPage 的局部阶梯；IME 可见时不得被根路由或播放器 handler 抢先导航。测试实际系统 Back，包括 KEY_DOWN/UP 和按住重复，不只调用 `model.back()`。保留 `TvImePolicy` 对跨路由迟到 IME 的处理。
- [ ] 增加 `tv-root` 标记；重写 `TvLaunchSmokeTest` 去掉 HybridDom/WebKit，进入 `tvNative`；phone lane 用现有 `MobileNavigationSmokeTest` 承接原生手机启动检查，不新增重复 smoke 类。
- [ ] **G3.3 生产入口回归。** 用 `MainActivityPathSmokeTest` 在真实 TV 镜像启动 MainActivity，覆盖下表。未配置时允许起于 SETTINGS；先测试初始页，再用合成内容进入其他路由，最后验证 HOME 退出。
- [ ] 切换前迁走仍需的 hybrid 覆盖；切换时将过时 hybrid 设备断言退出自动主线，仅保留手动归档入口。编译和候选安全检查继续运行；不采用 expected-red、全局 continue-on-error 或失败伪装绿色。
- [ ] **G3.4 真机短冒烟。** 真实 TV/盒子运行本阶段精确 APK，15–30分钟完成启动、D-pad、Dialog、SEARCH IME、一次播放及逐层返回。记录型号、系统、源码、APK hash、签名和结果。已有安装若签名不匹配则停止，不卸载/清数据替代升级。

| 情况 | Back 期望 | 新增测试 |
|---|---|---|
| HOME，无局部状态 | 系统退出 Activity | `MainActivityPathSmokeTest` |
| MOVIES/SERIES/SEARCH/EXPLORE/LIVE/LIBRARY/SETTINGS | 回 HOME | `TvRouteBackTest` 参数化7路由 |
| DETAIL | 回打开详情的 section | `TvDetailBackTest` |
| PLAYER | 面板 → 控制条 → returnPage；错误态按现有行为返回 | `TvPlayerBackLadderTest` |
| SEARCH/SETTINGS，IME可见 | 第一次只隐藏键盘；第二次才按页面规则返回 | `TvImeBackTest` |
| Dialog | 只关自己并恢复触发器；再按一次执行页面返回 | `TvDialogFocusTest` |

**Gate 3：** `tvNative` 生产入口和上述回归通过；真机短冒烟通过；归档 tag/APK 可取回。随后直接进入 G4。

### G4 — 覆盖与候选工具迁移，立即删除 TV Web

先迁移、跑绿，再删除对应旧文件；删除后在新 SHA 上重新构建。

- [ ] **G4.1 Dev 候选链迁移。** 将 `scripts/hybrid/build-dev-candidate.sh`、`release.json` 和 `artifacts.py` 中仍有用的逻辑迁到 `scripts/updates/`。目标为 `build-native-dev-candidate.sh`、`native-dev-release.json`、`native_artifacts.py`；不把当前 Dev 分支塞入只接受 account-free main 的通用候选脚本。
- [ ] 迁移测试到 `scripts/tests/test_native_artifacts.py`：PRIVATE_BUILD 必须 false；签名凭据/个人默认值拒绝；损坏签名不能冒充 unsigned；版本递增、固定包名/minSdk/channel/certificate、干净源码、构建前后 source/tree 不变、来源与 APK hash、输出不覆盖。删除 Web inventory 检查，增加 APK 无 `assets/tv-web/` 检查。
- [ ] 迁移前核对全局 versionCode 账本与公开产物；原 `release.json` 的 code13 只是 reserved 状态，不能直接当作新版本可复用。保留现有 stable/main 源码隔离，不降低闸门来让当前分支通过。
- [ ] 将原 hybrid workflow 的 Dev unsigned 构建接入非 hybrid CI，验证精确源码；之后才删除旧 workflow，确保候选检查没有消失。
- [ ] **G4.2 EPG 与仍需覆盖。** 新建 `ui/tv/pages/ProgramGuideWindow.kt`，接口 `window(programs: List<TvProgram>, nowMs: Long, limit: Int = 48): List<TvProgram>`；迁移现有 filter/distinct/sort/limit逻辑与三个测试，补 limit负值/0/超48。LiveScreen 改用它，删除重复实现。
- [ ] 对 `HybridHostUiTest` / `HybridAuditUiTest` 逐项列映射：页面遥控、搜索/设置返回、收藏/进度/播放返回、故障恢复中仍适用的功能须有原生覆盖；bridge/React/WebView 专属断言随功能退役。保留 `UiFixtureServer` 与原生播放测试。
- [ ] **G4.3 删源码与死管道。** 删除 `app/src/main/java/dev/cinematv/hybrid/`、四个 hybrid androidTest 辅助/测试文件、迁移后的 hybrid 单测；删除 `LibraryProgressReviewScreen` 和仅 hybrid 使用的 `settingsInitialSection/libraryManagement/onCloseLibraryManagement` 根参数。保留 LibraryDialog 和真正仍被调用的观测接口。
- [ ] **G4.4 删构建接线。** 删除 `app/tv-web.gradle`、`stageTvWeb/preBuild` 依赖、generated tvWebAssets srcDir、`frontend/tv-web/`。确认全仓调用后移除 `androidx.webkit`；豆瓣 `android.webkit.WebView` 保留。
- [ ] **G4.5 删脚本与 CI 引用。** 删除已迁完的 `scripts/hybrid/`、`hybrid-web.yml`、`hybrid-android.yml`。当前共10个 workflow/15处前端缓存路径，删两文件后尚有8文件/13处，执行时重新检索，不依赖固定计数。
- [ ] Android-only job 移除仅服务 TV Web 的 setup-node/cache；`android.yml` 的 BFF 测试保留 Node，或拆成独立 BFF job。官方 LibreTV 下载、固定 Node runtime、契约服务及测试继续保留。
- [ ] **G4.6 文档与删后验证。** 更新 README、ARCHITECTURE、LOCAL_BUILD_DEFAULTS、DEV_AUDIT、LIBRETV_PROTOCOL、设置中的开源组件文案；移除在当前主线已无用途的 HYBRID_TV 文档，历史 release notes 和归档不改。
- [ ] 精确删除 SHA 跑完整构建、phone/tv1080/tv4k/compat26/新增lane、原生 E-gate。另用无 Node 的 Android构建job验证 Gradle已解耦，不能用仍含BFF测试的整个workflow证明“不需要Node”。

**Gate 4：** 生产/构建/活动CI无旧TV Web引用；原生覆盖、Dev候选闸门、E-gate均执行；手机与非TV WebView保留。提交历史可恢复旧Web，不移动归档tag或重写历史。

### G5 — 完整玻璃与视觉定稿

- [ ] **G5.1 检查全表面。** 导航、动作、Settings/LIVE/DETAIL/LIBRARY面板按 §4 呈现；检查大面积Blur/Full、无图、切背景、滚动、白海报、Reduce Motion。修光学/可读性，不提前设每屏一块等性能限制。
- [ ] **G5.2 复用玻璃测试宿主。** 扩展 `ProductionGlassTestActivity` 的受控场景，增加同背景三档及大面板样本；复用 G1 采集协议。已有宿主能承载时不另建 GlassLabActivity；视图排版比较与性能采集分开运行。
- [ ] **G5.3 Haze预检。** 独立实验目录/获准worktree中先确认官方模块坐标、版本、AAR metadata 和依赖解析。强制 compileSdk>36 或升级现有Compose/Kotlin/AGP即结束预检。通过才做相同导航/面板PoC，沿用 §7 测量；半天为预检时间盒。
- [ ] PoC 比较 vendored `896a94a`+本地修改与候选库的视觉、p95、jank、峰值PSS。非劣预算为 p95+1ms、jank+0.3pp、PSS+20MB；噪声不足以判定时不下迁库结论。PoC通过不自动授权生产换库；本轮完成条件仍是backdrop方案。
- [ ] **G5.4 视觉定稿。** 活跃文字4.5:1、焦点指示3:1；黑白背景均可辨识焦点与selected；弹窗/播放器不采样；检查三档交互目标、Back和切源/收藏行为相同。更新本阶段受影响金标。

**Gate 5：** 全表面功能与视觉完成，评审截图可追溯，Haze评估有结论；需要优化的场景明确进入G6。

### G6 — 实测优化、真机验收与交付

- [ ] **G6.1 先测完整效果。** 在已完成 G5 的精确源码上采集 Basic/Blur/Full，记录实际生效档。按 §7 做同档版本比较和材质成本比较，两者分开报告。
- [ ] **G6.2 定位后降级。** 仅对实测失败的表面调整面积/高度/并发数/材质，参数和分辨率写进 `ProductionGlassPolicy`；保持全功能配置可供复验。旧 `GlassRenderPolicy` 的240,000px等常数不是当前生产预算，不直接迁回。先前景可读性与焦点正确，再比较优化前后。
- [ ] **G6.3 API和环境矩阵。** API26/29验证Basic，31验证Blur上限，33/36验证ENHANCED Full及AUTO Blur；补32边界的policy测试。低RAM、省电、moderate/severe热压、软件绘制、失败latch、Reduced Motion分别验证；实际设备与测试注入的证据分开。
- [ ] **G6.4 T-gate。** 按 §7 在点名的基础/主流真机上验收，至少两台；每台使用同一份该轮候选APK。30分钟热稳定、20轮路由/播放返回内存循环；使用可重复的合法本地媒体，网络故障场景另测。
- [ ] 使用G1的release instrumentation APK附着到已安装候选包；目标和测试APK在获准的外部环境完成同证书签名，私钥不进入CI。测试前后复核已安装目标APK hash，测试APK单独留hash；不得为接入采集器重建或换掉已经验收的目标APK。
- [ ] **G6.5 最终复跑。** 完整CI与全部要求lane通过，6张金标×2 viewport复跑、全部捕获图评审；只有G6降级实际改变视觉时再重捕受影响图。
- [ ] **G6.6 文档和交付。** `GLASS_PRODUCTION.md` 合并V4仍有效的前景分离、平台/失败回退、手机静态材质说明；删除或明确标注旧bitmap/focus-only路径，不继承旧数值限制。V4完成合并后标历史并指向生产文档。
- [ ] 生成迁移完成清单、实测参数、已通过设备与最终APK来源。若要发布，使用G4迁移后的Dev候选链、固定外部签名，并对最终签名字节完成安装/升级与真机验收；普通debug/benchmark APK不是可升级Dev发布包。
- [ ] 对 `dev-compose` 的原生TV、公开数据、诊断、账号实验分组列出可吸收提交。T-gate后由用户单独决定合并范围；不整支自动合并。

**Gate 6：** 最终源码CI、真机、金标通过；每项性能结论可回到其原始采集数据；交付APK与验收hash一致。

## 4. 玻璃表面契约

复用 `GlassRenderMode { BASIC, BLUR, FULL }`，不新增平行的 `GlassSurfaceLevel` 枚举。表面指定的是**允许上限**，不是强制渲染档。

- 在 `ProductionGlassPolicy.kt` 定义 `TvGlassRole { NAVIGATION, ACTION, PANEL, DIALOG, PLAYER_OVERLAY }`；`glassLevelFor(page: Page, role: TvGlassRole): GlassRenderMode` 为纯表面映射。
- `CinemaGlassSurface(level: GlassRenderMode = BASIC, ...)` 与 `cinemaGlassMaterial(..., level: GlassRenderMode = FULL)` 共同执行上限；默认面板仍安全，现有小控件行为保持。
- 最终材质 = 平台/用户/环境决策与表面上限两者较低档；缺采样源、跨owner或失败时Basic。Full上限不能把AUTO升为Full，也不能让Dialog/Player获得scene。
- `sampleBackdrop` 不再作为页面公开参数；Basic不建立效果节点。通过同一生产policy处理，不能只把level改名却仍让全局ENHANCED渲染所有Blur面板为Full。

| 表面 | 功能期上限 | 输入源 |
|---|---|---|
| FloatingNavigation、GlassAction、Hero按钮 | FULL | 现有背景scene；导航继续保留其受控glass-only子录制 |
| Settings/LIVE/DETAIL/LIBRARY的实际面板 | FULL，支持大面积 | 根BackdropLayer，不包含面板自己或前景 |
| Dialog、播放器overlay/track panel | BASIC | 无 |
| 海报卡片、Hero图像本体 | 不新增玻璃 | 焦点环单独绘制 |
| 手机 | 保持现状 | 不在本轮扩展 |

`GlassSurfacePolicyTest` 覆盖表面映射×用户档×API/环境；`GlassMaterialSmokeTest`确认实际绘制分支，不能只断言枚举。焦点环沿用前景Ink4dp+Ivory2dp，不改动已锁定上游shader。

## 5. 焦点、IME与返回验收重点

1. 稳定key优先于索引；返回恢复不得抢走用户新输入或新导航。
2. Dialog关闭恢复打开它的真实按钮；连续两次Back不能都被描述成“只关Dialog”。
3. IME隐藏由实际系统行为验证。根handler注册顺序只是默认优先级，不替代IME、Dialog独立window和播放器key路径测试。
4. TV的路由Back只在 `TvRootApp`；不建立全仓“只能出现两个BackHandler”的grep门槛，手机、弹窗和过渡期legacy各有合法局部handler。
5. 一条相同交互主链覆盖API回退与三档材质：导航 → 内容 → DETAIL → Dialog → SEARCH编辑/Back → PLAYER面板/控制条/返回；每步断言焦点、页面和局部状态，不以“8次按键无崩溃”代替行为验证。

## 6. 删除清单与保留清单

**删除：** TV hybrid源码、TV Web前端和Gradle接线、仅服务bridge/React/WebView的测试/脚本/workflow、已无入口的Library管理Screen和根参数。

**先迁再删：** ProgramGuideWindow；原生播放/订阅与仍适用的生产交互覆盖；TV镜像启动器；Dev候选构建、PRIVATE_BUILD、unsigned签名块、版本和来源校验。

**保留：** `ui/tv`、Media3/`TvPlayerPage`、`TvTestActivity`、`ProductionGlassTestActivity`、`backdrop`、手机UI、豆瓣官方登录WebView、更新/安装器、QR/SAF、诊断provider、`UiFixtureServer`、BFF与官方LibreTV契约测试。

当前目录用 `git grep` 核对 `dev.cinematv.hybrid`、`frontend/tv-web`、`scripts/hybrid`、`stageTvWeb`、`generated/tvWebAssets`；历史文档/归档引用不要求机械清零，活动代码、构建和workflow引用必须清零。

## 7. 测量与验收协议

### 7.1 指标和有效性

- `FrameMetrics.TOTAL_DURATION` 是应用帧渲染/提交时长，不是显示呈现时间；INPUT_HANDLING/ANIMATION也是时长。API31+读取GPU/DEADLINE，API36可附加FRAME_TIMELINE_VSYNC_ID作关联ID；不存在FRAME_TIMELINE_TYPE。
- `B=1e9/实际refreshHz`；有效DEADLINE优先，缺失时使用B并标注回退。jank为TOTAL_DURATION>deadline；overrun保留有符号差值，不提前clamp成0。
- 每次输入记录receipt时间、目标路由/焦点状态生效时间、关联的首个响应draw；统一单调时钟，携带input/response ID。不能拿“输入后的任意下一帧”当作反馈，也不能以首次composition等同可见。
- 连续遍历全程采集；独立转场保留完整1500ms观察段，记录真实完成标记。无帧两vsync不证明动画结束；异步工作、播放持续更新和超时要分别识别，超时不作完成。
- 帧按时间/ID去重，转场和稳定遍历分组；报告n、first-draw、空窗口、未就绪、callback drops、容量截断与排除原因。容量截断/丢失/缺响应的run不进入性能比较。
- 播放20秒与控制条8秒分段；UI帧与Media3 rendered/dropped、underrun/rebuffer分别报告。记录实际视频尺寸、帧率、codec与码率。

### 7.2 E-gate：模拟器协议与相对回归

场景：HOME遥控遍历60秒 → DETAIL → 返回 → 播放20秒 → 控制条8秒 → 逐层返回。记录真实renderer、viewport、刷新率与材质；debug结果不外推release。

**必过项：** 源码/APK身份一致、场景和输入响应完整、实际档位与声明一致、有效采样、无丢失/截断。因环境策略自然回退时记为该档未覆盖，不绕过策略强制Full。

**两类比较分开：**

- 材质成本：同APK的Basic/Blur/Full差值，允许Blur/Full比Basic贵。
- 代码回归：控制版A与候选版B，同材质、同设备、同renderer/构建类型、同场景与预热，ABBA交错。两个APK都包含同版采集协议；协议变更需重测控制版，不给原始旧SHA拼装新采集器。

先同配置做6次独立试验估计run间噪声；正式比较以完整run/配对区块为统计单位，不能把帧当独立重复。报告配对差的95%区间。p95回归容差为 `max(1ms, 2σ_run)`，jank为 `max(0.3pp, 2σ_run)`；任一指标区间整体超过对应容差即可判回归，跨界为待复测而非通过。噪声实验不是六次就必然有统计能力的保证。

模拟器的毫秒/FPS不设产品硬门槛；相对结果用于定位，必过check仍是协议/路由/样本有效性。

### 7.3 T-gate：真机验收预算

点名基础/主流两台设备的型号、SoC、内存、系统、刷新模式、renderer与实际材质。普通release保留 `isMinifyEnabled=false` 的事实；debug/benchmark宿主的诊断不能替代同轮release `MainActivity`验收。

| 指标 | 主流 | 基础 |
|---|---|---|
| deadline jank | ≤2% | ≤2% |
| 帧TOTAL_DURATION P99 | ≤B | ≤B |
| 输入receipt→关联响应draw P95/P99 | ≤100/150ms | ≤200/300ms |
| DETAIL返回完成 P90 | ≤250ms | ≤300ms |
| PLAYER返回完成 P90 | ≤300ms | ≤350ms |
| 峰值PSS | ≤350MB | ≤400MB |
| Media3 dropped/(rendered+dropped) | ≤0.1% | ≤0.1% |
| 合法本地媒体的音频underrun/非预期rebuffer | 0 | 0 |
| 0–5分钟对25–30分钟同场景p95恶化 | ≤10%且≤2ms | 同 |
| 预热后20轮PSS趋势 | ≤0.5MB/轮；后5轮均值≤前5轮+10MB | 同 |

返回完成指目标页与恢复焦点均已绘制；不等外网海报下载。PSS以1Hz记录，Java/native/graphics分列。完整材质测试与低RAM/热压fallback测试分开，热压造成的降档不能当作原档性能通过。

应用FrameMetrics和响应标记由G1的隔离release instrumentation采集，实际呈现另用Perfetto FrameTimeline或连续SurfaceFlinger present时间戳，记录layer、采样覆盖与去重。应用draw延迟和显示present延迟分列；不把vsync频率、TOTAL_DURATION倒数或gfxinfo最近一小段当作60秒FPS。采集缺失时修复测试链后重测同一目标APK，不能借用debug分布填表。

### 7.4 证据

`qa/native-tv-perf/<timestamp>/` 保存 `run.json`、`frames.csv`、`inputs.csv`、原始gfxinfo/meminfo/thermal/呈现追踪、`summary.json/md`、`SHA256SUMS`、每run的DONE/FAILED。run记录源码/tree、APK hash、build type、设备与镜像、renderer、材质、协议版本及有效样本。每项结论引用对应run，不混合不同APK。

官方字段复核来源：[FrameMetrics参考](https://developer.android.com/reference/android/view/FrameMetrics)；[Android16 FrameMetrics.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/view/FrameMetrics.java)。

## 8. 测试、金标与交付检查

**基础构建：**

```sh
gradle --no-daemon :app:testDebugUnitTest :backdrop:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:compileGlassBenchmarkKotlin \
  :app:lintDebug :backdrop:lintDebug
npm --prefix backend/tv-bff test
python3 -m unittest discover -s scripts/tests -v
python3 -m unittest discover -s scripts/glass-production -p 'test_*.py' -v
python3 -m unittest discover -s scripts/glass-performance -p 'test_*.py' -v
```

G1新增perf模块的host测试接入 `android.yml`，不要只写在文档中。设备用例必须注册在matrix/workflow，并核对JUnit结果无skip/no-source。

**金标流程：**

1. 视觉变更代码形成可构建SHA，捕获该SHA在1080/4K上的图片。
2. 人工评审受影响图；更新 `design/ui/goldens/{tv1080,tv4k}`、manifest与 `REVIEW.md`，保留真实捕获SHA/run/hash，不把来源伪写成后来提交。
3. 金标更新后在新提交复跑比较；功能/构建/采样失败不得以“视觉预期变化”豁免。
4. 保持6张正式对比；检查 `TvUiRegressionTest` 的14个命名场景与99-final-state结束态。G6汇总评审，不强制重复重捕全部图。

**最终检查：**

- [ ] 精确候选源码的Actions构建、unit/lint、BFF、host测试和完整设备矩阵通过。
- [ ] 真TV镜像通过MainActivity，真机通过D-pad/OK/Back、IME、Dialog与播放器返回。
- [ ] API26/29/31/33/36与环境回退有对应证据；三档交互结果一致。
- [ ] 大面积玻璃先完成，G6限制各有测量理由；金标阈值未改。
- [ ] TV Web删除后，覆盖、候选安全链、官方LibreTV与手机均保留。
- [ ] 交付源码、APK、签名、设备记录对应同一轮；发布/合并只在另行确认后执行。

## 9. 契约审计与工作量

LibreTV审计沿用现有 `data/LibreTvRepository.kt`、`LibreTvProtocolTest`、`SourceResponseIdentityTest`，只检查本轮路径涉及的同名跨源、部分失败、取消/stale、会话过期、认证失败。先补能复现的测试，证实缺陷才修；不在TV迁移中另造完整版本化适配架构。

手机回归嵌入每次共享组件变更，不另起手机改版。Haze隔离评估不阻塞backdrop主线。

原估算G0–G6为37–45人日，含完整设备/视觉/性能验收；如另有已证实的LibreTV缺陷修复，再计3–4人日。按Gate交付，不将CI排队、设备窗口或PoC收益折算成已节省工期。

## 10. 回退

- 页面/组件失败：回退该最小变更，保留已通过的功能与证据；连续三次修复失败即停止处理。
- G3真机冒烟失败：不进入G4，先修原生入口/交互。
- G4删除失败：先修迁移遗漏，必要时revert删除提交；归档tag不移动，不reset或重写共享历史。
- G6性能失败：对实测失败表面降档/限面积后重测，不取消焦点、Back、收藏、切源或播放功能。
