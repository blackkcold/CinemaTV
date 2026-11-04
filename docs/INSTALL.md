# CinemaTV 安装与测试说明

## APK 获取
- GitHub Actions 每次 `main` 推送均构建 Android TV Debug APK。
- `v0.2.1-preview` 的 GitHub Release 提供可直接下载的 Debug 签名测试 APK。
- 安装要求：Android TV / Google TV，Android 8.0 (API 26) 及以上；设备需要支持第三方 APK 安装。
- 应用包名：`dev.cinematv`，版本：`0.2.0-preview`，签名：Android Debug（不适合正式商店发行）。

## 首次安装
1. 通过 U 盘或 `adb install -r app-debug.apk` 将 APK 安装到 Android TV。
2. 打开「CinemaTV 设置」，输入你自行部署的 LibreTV 服务器地址，如 `https://example.com`。
3. 如果服务器要求密码，输入你的 LibreTV 访问密码。
4. 配置你有合法访问权限的 Apple CMS JSON API 片源，或者在服务器端设置 `DEFAULT_SOURCES`。
5. 首页通过 LibreTV 推荐接口获取内容，点击任意影片后通过配置的点播源搜索实际播放地址。
6. 直播需添加 M3U 订阅或在 LibreTV 服务器端预配置直播源。节目单可选配置 XMLTV URL。

## 功能
- 原生遥控器导航、首页推荐、横向媒体轨道、探索海报视廊、电影剧集列表
- LibreTV 登录、聚合搜索、媒体详情与分集
- Media3 HLS / MP4 等适配播放，进度记忆，播放器 UI
- 备用同名片源查找、有限次失败自动换源、手工换源
- 256 MB 的 VOD LRU 分片缓存；直播旁路
- 直播源和频道列表、XMLTV 当前节目、收藏与最近观看
- 可选独立 TV BFF，服务端与客户端物理解耦

## 已知限制与风险
1. **不是商业级完整发行版。** 暂未完成真实电视硬件的端到端操作、播放格式矩阵和长期稳定性验证。
2. 目前 UI 为深色半透明玻璃 + 焦点/动画效果，**非经过 GPU 测试的实时光学折射 Liquid Glass**。
3. 媒体背景依赖当前 LibreTV 推荐接口返回的图片；不保证每部影片都有 16:9 高清剧照，未实现独立元数据聚合。
4. 未实现跨设备同步、完善的字幕/音轨专用 TV 面板、画质选择、DRM、多解码内核切换或手机扫码配对。
5. 离线安装包不包含片源，只有配置且可访问的 LibreTV 实例与合法媒体流才能播放。
6. 有管理员密码的 LibreTV 需要认证；应用会话 Cookie 位于应用进程内存，进程重启后可能需要重新登录。
7. 自建局域网可使用 HTTP，但公网必须使用 HTTPS；不在仓库中提交任何凭证。
8. Debug 签名仅用于自用测试；未来正式发布必须设置私有签名密钥，不能公开提交 keystore。

## 自检
```bash
gradle :app:testDebugUnitTest :app:assembleDebug
npm --prefix backend/tv-bff test
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 建议的真机验收矩阵
- 设备：2GB RAM Android TV 盒子、主流 Google TV / Android TV、至少一款 4K 电视
- 基本使用：冷启动、首次输入服务器、认证成功失败、断网重连、查询、详情、收藏、退出返回
- 遥控器：上下左右/OK/Back，横向轨道、Cover Flow、PlayerView、输入法焦点
- 播放：MP4 / HLS，解码不支持、服务端 404/403、断点续播、备用源匹配与失败恢复
- 直播：M3U、XMLTV、频道切换、时区正确性、网络抖动
- 性能：页面 FPS、缓存稳定性、内存压力、持续两小时播放

**注意：本文件是测试指南而非测试通过报告。**

## Main branch: optional high-resolution backdrops

Deploy the independent Node TV-BFF (see `backend/tv-bff/README.md`) with:
- `LIBRETV_URL` and optional `LIBRETV_PASSWORD`
- `BFF_ACCESS_TOKEN` (16+ characters)
- `TMDB_READ_ACCESS_TOKEN` (operator-managed TMDB read token)

In CinemaTV Settings, enter the BFF URL and its access token under the
optional metadata section. The BFF uses only work title, year and movie/TV
type to perform a conservative TMDB match; no source URLs are sent to TMDB.
The BFF token is kept in process memory and must be re-entered after restart.

Android 13+ devices may optionally turn on optical specular highlights in
Settings. This is not a full backdrop-refraction system and must be benchmarked
on the intended television hardware before being a default.

## Main branch: development build and stable-release restrictions

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
npm --prefix backend/tv-bff test
```

For production release, complete `docs/RELEASE_CHECKLIST.md`, enter private
signing secrets in GitHub Actions, and use the manual certified release workflow.
The workflow refuses to publish while certification evidence is incomplete.
A debug-signed APK from one CI runner may not upgrade over an APK signed by
another debug keystore. Back up local state before reinstalling previews.

## 手机安装与测试（主分支 Android 双端构建）

- 支持 Android 8.0（API 26）及以上手机；主屏启动器将显示 CinemaTV。
- 不再强制横屏，手机默认跟随系统方向；TV 继续使用沉浸式横屏界面。
- 首次打开填写自建 LibreTV 服务器，完成认证后使用手机触控首页、搜索、影视详情、选集、播放、直播与片库。
- 手机使用 Media3 原生触摸播放器控制器；电视继续使用 D-pad 专用控制层。
- 手机电视共用后端、播放历史和本地设置（仅同一应用安装空间内）。
- 由旧版 Debug APK 升级如果出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，通常是测试构建的调试签名不同。卸载会清空本地数据，请先备份配置后再重装。

### 品牌资源
- `design/branding/cinematv-player-mark.svg`: 透明白色播放器标识
- `design/branding/cinematv-app-icon.svg`: 黑底横向动态模糊主图标
- `design/branding/cinematv-tv-banner.svg`: 居中播放器标识与 CinemaTV 字标
- `app/src/main/res/drawable-nodpi/`: 从 SVG 导出的实际 Android PNG
- 更改 SVG 后通过 `bash scripts/render-branding.sh` 生成对应 PNG（需安装 `rsvg-convert`）。
