<div align="center">

<img src="app/src/main/res/drawable-nodpi/ic_launcher_legacy.png" width="112" alt="CinemaTV A1 标志">

# CinemaTV

**为遥控器与触屏分别设计的 Android 影视客户端**

Android TV · Google TV · Android 手机

Native Compose TV, native playback and a separate touch interface.

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](docs/INSTALL.md)
[![Release](https://img.shields.io/github/v/release/blackkcold/CinemaTV?include_prereleases&label=release)](https://github.com/blackkcold/CinemaTV/releases)
[![Build](https://github.com/blackkcold/CinemaTV/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/blackkcold/CinemaTV/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/project%20license-MIT-blue.svg)](LICENSE)

[下载 APK](https://github.com/blackkcold/CinemaTV/releases) · [安装指南](docs/INSTALL.md) · [使用与隐私](docs/LOCAL_LIBRARY.md) · [反馈问题](https://github.com/blackkcold/CinemaTV/issues)

</div>

CinemaTV 连接你自建的 [LibreTV](https://github.com/LibreSpark/LibreTV)，把浏览、搜索、选集、播放和本地片库放在同一个应用里。电视与手机各自使用原生 Kotlin 与 Jetpack Compose 界面，Media3 负责播放；两者共享同一套业务状态、片源身份与本地片库。

**已发布 Stable：[v0.8.0](https://github.com/blackkcold/CinemaTV/releases/tag/v0.8.0)；已发布 Dev：[v0.8.0-dev.2](https://github.com/blackkcold/CinemaTV/releases/tag/v0.8.0-dev.2)。** 本分支 `dev-compose` 是唯一长期开发主线，电视界面为原生 Jetpack Compose；旧版 Hybrid TV Web 层已冻结并计划删除。下方主画廊是 v0.7.0-preview 的旧原生界面截图，不代表当前电视界面。

应用不附带公共片源、账号或影视内容。使用前需要配置自己的服务和有权访问的内容来源。本项目与 LibreTV、DpadTV 及下列第三方服务均无隶属关系。

## 界面

<table>
<tr>
<td width="50%"><img src="docs/images/tv-home.png" alt="CinemaTV 电视首页：全屏场景、悬浮导航和内容轨道"><br><sub>电视首页</sub></td>
<td width="50%"><img src="docs/images/tv-detail.png" alt="CinemaTV 电视详情：选集、收藏、来源和作品关联"><br><sub>详情与选集</sub></td>
</tr>
<tr>
<td><img src="docs/images/tv-library.png" alt="CinemaTV 本地片库与观看记录"><br><sub>本地片库</sub></td>
<td><img src="docs/images/tv-recommendations.png" alt="CinemaTV 本地推荐看板：实际引擎、规则和排序依据"><br><sub>本地推荐看板</sub></td>
</tr>
</table>

以上为 **v0.7.0-preview** 的旧原生界面在 Android 模拟器中实际运行的照片，非设计稿，也不代表当前 `dev-compose` 的电视界面。展示使用虚构片名、原创测试画面和合成媒体，不代表内置影视资源。拍摄版本、运行环境与原始证据见 [截图说明](docs/images/README.md)。

<details>
<summary>查看手机推荐看板</summary>

<img src="docs/images/phone-recommendations.png" width="280" alt="v0.7.0-preview 手机端实际推荐看板">

</details>

## 可以做什么

### 浏览与搜索

- 电视使用全屏场景、横向内容轨道和独立电影/剧集布局；方向键移动后保留焦点与滚动位置。
- 搜索先呈现本地记录，再补充已配置目录和 LibreTV 片源；支持来源提供的别名、演员、类型和年份信息。
- 有完整数据时可按类型、年份、评分筛选。缺失字段保持未知，评分保留真实来源。
- 横版图片进行保守匹配与尺寸检查；没有合适图片时使用原创背景。

### 片库与续播

- 收藏、想看、在看、看过、弃剧和逐集进度保存在本机。收藏与观看状态可以同时存在。
- 按已知作品、季和来源浏览详情；支持手动关联未识别条目，并提供冲突检查、取消关联和一步撤销。
- 播放进度遵循来源与剧集身份。身份不确定时保留记录，不把进度猜测性地转到另一集或另一来源。

### 播放与直播

- Media3 播放、选集、来源切换、倍速及可用音轨/字幕选择，支持后台暂停与逐集续播。
- 手机使用独立触控播放器，适配横竖屏和旋转时的播放连续性。
- M3U 订阅、频道分组，以及 XMLTV 当前、接续与后续节目单。

### 本地推荐

- 根据本机收藏、观看状态与进度，为已配置的目录候选排序。没有偏好记录时保留来源顺序，负反馈会排除匹配作品。
- 设置中的「本地推荐看板」展示实际引擎、权重、偏好标签和排序依据。使用 RankSys 余弦相似度与 MMR，并加入 CinemaTV 数值保护。
- 偏好与排序留在设备上，不使用推荐网络服务。搜索、图片和播放仍需要相应网络连接。

推荐分数不是喜欢概率，也不是作品评分。更多实现与限制见 [推荐说明](docs/RANKSYS_INTEGRATION.md)。

### 适配与显示

- 电视遥控器与手机触屏分别设计，提供分层玻璃控件、减少动态效果和性能降级。
- 电视与手机界面共用离线打包的 Noto 字体，按内容语言与地区选择中日韩字形，不转换简繁内容。
- 原创 A1 品牌与静态启动图标；应用内简短品牌动态不阻塞操作。

## 电视界面架构

电视与手机是两套独立原生界面，共享同一条业务与数据链路：`CinemaViewModel` 负责路由与页面状态，`data/` 负责 LibreTV 协议、片源身份与本地片库，`playback/` 与 `ui/TvPlayerPage.kt` 负责 Media3 会话、缓存与播放器生命周期。

- `ui/tv/` 是电视唯一入口。`TvRootApp` 拥有路由、焦点与返回处理；页面在 `ui/tv/pages/`，语义控件在 `ui/tv/components/`，设计 Token 在 `ui/tv/design/`。
- 安装下限保持 API26；电视运行时不依赖 WebView 内核或消息接口版本。构建期的 TV 前端接线仍在，会在 G4 与冻结代码一起移除。
- 玻璃材质只采样 `BackdropLayer` 提供的静态画作场景，不读取视频 Surface、Dialog 窗口或玻璃消费者自身；Full / Blur / Basic 三档与减少动态按设备能力在运行中回退。
- 焦点按稳定 key 记录；离屏目标先准备 Lazy 视口再请求焦点，被删除的条目回落到同区域最近的有效项。D-pad、OK 与 Back 覆盖每个页面。
- 旧 Hybrid TV Web 层（`hybrid/`、`frontend/tv-web`）已冻结，只作历史 A/B 证据保留，将在门禁通过后删除；见 [Hybrid TV（历史）](docs/HYBRID_TV.md)。

玻璃、动效与性能分档的实现边界见 [玻璃实现](docs/GLASS_PRODUCTION.md) 与 [动效实现](docs/MOTION_PRODUCTION.md)。模拟器验收不代表真实电视帧率认证。

## 安装与首次使用

最低要求为 **Android 8.0 / API 26**。同一应用支持电视与手机，包名为 `dev.cinematv`。

1. 从 [Releases](https://github.com/blackkcold/CinemaTV/releases) 下载 APK，并阅读该版本的签名与升级说明。
2. 安装后打开「设置 → 连接与账户」，填写自建 LibreTV 地址，按需登录。
3. 使用服务端已配置的来源，或添加自己有权访问的 Apple CMS JSON API / M3U 订阅。
4. 搜索一部作品，打开详情选择来源和剧集。收藏与观看进度会进入本地片库。

例如，服务地址可以是 `https://libretv.example.com`。这是占位示例，需要替换为自己的地址。公网连接建议使用 HTTPS。

可选的 [TV BFF](backend/tv-bff/README.md) 提供授权目录与 TMDB 元数据匹配。没有配置目录时，首页可使用本地记录；首次使用没有记录时，可先通过 LibreTV 搜索添加内容。

**升级提醒：** 现有调试预览包的签名可能不同。出现签名冲突时不要直接卸载；卸载会删除本地设置、片库和观看进度，目前没有通用导出恢复或跨设备同步。

完整配置、遥控器操作和设备测试范围见 [安装指南](docs/INSTALL.md)。

**更新通道：** Stable 与 Dev 沿用同一个包名和固定签名，版本码全局递增。`dev-compose` 是唯一开发主线，并直接产出 Dev 通道；`dev` 是过渡镜像，后续弃用；`main` 是 Stable 发布线。Dev 的豆瓣个人账户实验默认关闭，只存在于开发线，不进入 Stable 源码范围，其验收与电视界面验收分开记录。手动下载版本和自动更新清单分别发布，是否可安装以实际 Release 和签名说明为准，详见 [更新通道](docs/UPDATE_CHANNELS.md)。

## 数据与隐私

- 不附带服务器凭据、公共片源、豆瓣目录数据或商业影视海报。
- 本地片库、设置和播放链接不进入 Android 云备份或设备迁移；不上传偏好与推荐历史。
- 播放链接不会发送给 TMDB。可选元数据服务仍须由运营者遵守其使用条件。
- 豆瓣获取默认关闭；BFF 采集需要适用授权与获准端点验证，不能把配置声明当成授权证明。客户端另有个人使用的公开列表通道，授权口径与边界见 [豆瓣公开页面访问](docs/DOUBAN_PUBLIC_ACCESS.md)。

[本地数据说明](docs/LOCAL_LIBRARY.md) · [来源与授权边界](docs/DOUBAN_ACQUISITION.md)

## 开发

环境：JDK 17、Gradle 8.13、Android SDK 36、Build Tools 36.0.0、Node 22.12+。测试媒体已随源码提供，普通构建无需 FFmpeg。Gradle 目前仍会构建已冻结的 TV 前端（G4 移除该接线）；Node 不进入 APK，但它生成的 Web 资源在 G4 前仍会打进 APK，并用于可选的 TV BFF 测试。

```bash
bash scripts/generate-test-media.sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
npm --prefix backend/tv-bff test
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

单独迭代电视界面：页面在 `app/src/main/java/dev/cinematv/ui/tv/pages/`，控件在 `ui/tv/components/`，设计 Token 在 `ui/tv/design/`。设备 QA 使用 [tv-smoke](.github/workflows/tv-smoke.yml) 与 `scripts/qa-device-matrix.py`；其中只有 `tvNative` lane 使用 Android TV 系统镜像，宽屏手机镜像只能证明几何与渲染，不是电视设备。

- [架构](docs/ARCHITECTURE.md)：共享数据与播放逻辑，独立 TV / 手机原生界面。
- [构建与测试](https://github.com/blackkcold/CinemaTV/actions/workflows/android.yml)：编译、Lint、单元与协议测试。
- [验收与发布边界](docs/RELEASE_CHECKLIST.md)：模拟器 UI、真实播放协议、硬件覆盖与签名要求。

欢迎提交可复现的问题或改进建议。反馈时请附上版本、设备型号、Android 版本与操作步骤，并移除密码、令牌、私有地址和播放链接。

## 当前边界

模拟器测试不能替代实体电视认证。低内存盒子、长时间播放、HDR/编解码器覆盖与硬件图形性能仍需真机验证。当前不提供跨设备同步、扫码配对或额外播放内核。

功能与验收范围集中维护在本文及对应文档中；逐版变更请查看 [发布记录](https://github.com/blackkcold/CinemaTV/releases)。

## 许可与致谢

CinemaTV 原创代码和测试画面采用 [MIT License](LICENSE)。没有复制 DpadTV、FongMi 或 LibreTV 的实现代码，原创品牌素材位于 [design/branding](design/branding/)。

- [LibreTV](https://github.com/LibreSpark/LibreTV)：连接的上游服务。
- [AndroidX Media3](https://github.com/androidx/media)、Jetpack Compose：播放与原生界面。
- React / React DOM / Scheduler：MIT；仅由已冻结、待删除的 TV Web 层使用。
- RankSys：MPL-2.0；fastutil / jOOL：Apache-2.0，保留对应版本的附加声明。APK 提供完整许可及匹配版本的 RankSys 源码包，详见 [第三方推荐组件](docs/RANKSYS_INTEGRATION.md)。
- 主分支的 AndroidLiquidGlass / Backdrop 复用部分采用 Apache-2.0，保留 [许可证](backdrop/LICENSE)、[上游与修改声明](backdrop/NOTICE.md)，不归入 CinemaTV 原创 MIT 代码。
- Noto Sans / Noto Sans CJK：SIL Open Font License，详见 [字体说明](docs/NOTO_TYPOGRAPHY.md)。
- TMDB：可选元数据来源；官方品牌素材与使用说明见 [第三方资产](docs/THIRD_PARTY_ASSETS.md)。

This product uses the TMDB API but is not endorsed or certified by TMDB.
