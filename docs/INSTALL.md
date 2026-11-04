# CinemaTV 安装与测试

## 下载与兼容性

- [v0.4.0-preview 下载页](https://github.com/blackkcold/CinemaTV/releases/tag/v0.4.0-preview)：APK、界面截图、自动化验收证据及 SHA-256。
- 同一 APK 支持 Android TV / Google TV 和 Android 手机；最低 Android 8.0（API 26）。包名 `dev.cinematv`，版本 `0.4.0-preview`，versionCode `5`。
- 本版使用 Android Debug 签名。不同 CI 构建的调试密钥可能不同；如出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先备份配置再卸载重装。卸载会清空本地历史和设置。

## 首次使用

1. 通过 U 盘、设备文件管理器，或 `adb install -r CinemaTV-v0.4.0-preview.apk` 安装。
2. 打开设置 →「连接与账户」，输入自建 LibreTV 地址；如服务需要密码，完成登录。
3. 使用服务器已配置的片源，或在「片源与订阅」添加有权访问的 Apple CMS JSON API。
4. 直播添加 M3U 订阅；XMLTV 节目单地址可选。只有可访问的订阅和媒体流才可播放。
5. 电视使用方向键、OK 和返回键；手机使用独立触控页面，播放器随系统方向切换。

APK 不提供默认公共片源、账号或媒体内容。公网连接请使用 HTTPS；会话 Cookie 和 BFF 令牌保存在进程内存，应用重启后可能需要重新认证。

## 高清剧照与玻璃效果

首页优先使用来源提供的横版剧照。配置可选的独立 [TV BFF](../backend/tv-bff/README.md)，可按片名、年份和电影/剧集类型保守匹配 TMDB 元数据；歧义结果会被拒绝。

在服务端设置 `LIBRETV_URL`、可选 `LIBRETV_PASSWORD`、`BFF_ACCESS_TOKEN`（至少16字符）和 `TMDB_READ_ACCESS_TOKEN`，再在客户端「连接与账户」填写 BFF 地址和令牌。客户端不会向 TMDB 发送播放地址。

「画面与性能」提供自动、基础、背景磨砂和增强高光，以及减少动态效果。低内存和旧系统会降级；Android 13 以下不能启用增强高光。磨砂采样仅来自静态剧照，视频 Surface 不参与采样。缺少合格横图时显示原创氛围背景。

## 本版功能

| 范围 | 已实现 |
|---|---|
| 电视浏览 | 全屏场景、悬浮导航、Hero、横向内容轨道、居中探索视廊 |
| 电影/剧集 | 独立海报墙和横图布局；按已有类型、年份、评分筛选 |
| 遥控器 | 按路由、轨道、影片恢复焦点和滚动；跨屏移动、弹窗和播放器返回 |
| 搜索与片库 | 渐进搜索、历史、收藏、五种片库状态、真实时长续播 |
| 播放 | Media3、选集/换源、有限次故障恢复、音轨/字幕/画质/倍速面板、后台暂停 |
| 直播 | 订阅、分组频道、XMLTV 当前/接续/后续节目 |
| 手机 | 五个底部入口、独立触控布局、横竖屏播放和旋转连续性 |

## 自动化验收与真机边界

发布工作流要求同一提交通过编译、Lint、13项 JVM 测试、7项 BFF 测试，以及手机和两个 TV 视口的仪器测试。TV 场景覆盖14个界面状态，并对6个已审阅界面执行截图差异检查。每种 TV 视口保留焦点轨迹和软件模拟器帧耗时；这些数据不代表电视 GPU 性能。

| 自动化环境 | 配置 |
|---|---|
| 手机 | API 30，1080×1920，含横屏播放和旋转 |
| TV 界面 | API 33，1920×1080 / density 320 |
| TV 界面 | API 33，3840×2160 / density 640 |

TV 界面测试使用模拟器中的专用原生测试 Activity，不等同于真实 Android TV 系统或硬件认证。测试中的虚构片名、原创矢量画面及合成 H.264/AAC 片段均只用于测试；片段不进入用户 APK。

仍需真机完成：2GB 盒子和实体4K电视、四小时播放、HDR/H.265/其他格式能力、网络和解码故障矩阵、玻璃性能及私有签名升级验证。参见 [稳定版验收门禁](RELEASE_CHECKLIST.md)。本版不提供跨设备同步、扫码配对、DRM 或额外播放内核。

## 开发与复验

JDK 17、Gradle 8.13、Android SDK 36 / Build Tools 36.0.0。

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
npm --prefix backend/tv-bff test
```

`[ui-qa]` 提交触发完整设备矩阵，`[preview-release]` 在同一提交全部通过后发布预览包。发布附件包含截图对比结果、JUnit 报告、焦点轨迹和帧数据。

原创播放器 SVG 和居中的宽版 TV Banner 位于 `design/branding/`。如需重新导出 Android PNG，安装 `rsvg-convert` 后运行 `bash scripts/render-branding.sh`。
