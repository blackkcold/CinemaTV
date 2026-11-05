# CinemaTV 安装与测试

## 下载与兼容性

- 从 [v0.8.0 stable](https://github.com/blackkcold/CinemaTV/releases/tag/v0.8.0) 下载本次正式版，核对该Release的APK SHA-256与生产证书指纹。本次不提供dev11下载，开发通道等待后续候选。历史v0.7预览和截图仍保留原版本归属。
- 同一 APK 支持 Android TV / Google TV 和 Android 手机；最低 Android 8.0（API 26），包名 `dev.cinematv`。
- 本次stable APK使用固定生产签名。旧调试预览的签名可能不同，不能保证从旧预览覆盖安装到正式包。
- 出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 时请停止安装，不要为了消除报错直接卸载。卸载会删除本地设置、收藏、片库和观看进度；当前没有通用导出恢复或跨设备同步。

### 发布通道与升级边界

本次公开stable `0.8.0 / code10`及stable签名更新清单。dev11是未公开的同证书升级验收目标，不是可下载发行版；channel-dev冻结，等待后续开发候选。正式包包含订阅设置与显式检查/下载更新功能，但不能从APK或feed发布推断线上安装全链路已通过。stable清单有效期至2026年11月12日14:40:12 UTC；过期仅阻止获取更新，不影响已安装APK使用。豆瓣个人账户与片单同步不在本次stable中。

两通道同包名、同证书，不能并排安装。订阅选择不等于已安装通道，只有实际升级后才改变安装版本及图标。只接受更大的 versionCode：例如验收用dev11不能降级覆盖为stable10；这不表示dev11已公开提供下载。

只有实际发布说明确认的版本与签名才构成可用升级路径。调试签名不能无损转换为另一把生产签名；不要假设切换通道可以降级或恢复被卸载的数据。相关开发：[更新通道 PR](https://github.com/blackkcold/CinemaTV/pull/27)。

## 首次使用

1. 通过 U 盘、设备文件管理器，或 `adb install -r CinemaTV-0.8.0-code10-stable.apk` 安装。
2. 打开设置 →「连接与账户」，输入自建 LibreTV 地址；如服务需要密码，完成登录。
3. 使用服务器已配置的片源，或在「片源与订阅」添加有权访问的 Apple CMS JSON API。
4. 直播添加 M3U 订阅；XMLTV 节目单地址可选。只有可访问的订阅和媒体流才可播放。
5. 电视使用方向键、OK 和返回键；手机使用独立触控页面，播放器随系统方向切换。

APK 不提供默认公共片源、账号或媒体内容。公网连接请使用 HTTPS；会话 Cookie 和 BFF 令牌保存在进程内存，应用重启后可能需要重新认证。

## 高清剧照与玻璃效果（v0.8）

首页优先使用来源提供的横版剧照。配置可选的独立 [TV BFF](../backend/tv-bff/README.md)，可按片名、年份和电影/剧集类型保守匹配 TMDB 元数据；歧义结果会被拒绝。

在服务端设置 `LIBRETV_URL`、可选 `LIBRETV_PASSWORD`、`BFF_ACCESS_TOKEN`（至少16字符）和 `TMDB_READ_ACCESS_TOKEN`，再在客户端「连接与账户」填写 BFF 地址和令牌。客户端不会向 TMDB 发送播放地址。

v0.8 使用同一 Compose 场景的 Basic / Blur / Full 分档玻璃和电视卡片动效。Blur 需要 API31+，Full 为 API33+ 的显式选择；自动模式遵循窗口、硬件绘制、内存、电量/温度和渲染失败保护。API26–30、视频 Surface 与独立弹窗使用 Basic，不采样视频画面。缺少合格横图时使用原创氛围背景。减少动态控制动画，与材质选择分别设置。

详见 [玻璃实现与降级](GLASS_PRODUCTION.md) 和 [电视动效](MOTION_PRODUCTION.md)。这些是实现与适配边界，不是设备帧率承诺。旧 [V4 说明](GLASS_V4.md) 与 README 画廊对应 v0.7 预览，不能标作 v0.8 的图像或性能证据。

## 已发布功能

| 范围 | 已实现 |
|---|---|
| 电视浏览 | 全屏场景、悬浮导航、Hero、横向内容轨道、居中探索视廊 |
| 电影/剧集 | 独立海报墙和横图布局；按已有类型、年份、评分筛选 |
| 遥控器 | 按路由、轨道、影片恢复焦点和滚动；跨屏移动、弹窗和播放器返回 |
| 搜索与片库 | 渐进搜索、历史、收藏、五种片库状态、真实时长续播 |
| 播放 | Media3、选集/换源、有限次故障恢复、音轨/字幕/画质/倍速面板、后台暂停 |
| 直播 | 订阅、分组频道、XMLTV 当前/接续/后续节目 |
| 本地推荐 | 本机显式偏好、RankSys 余弦/MMR 与数值保护，手机/TV 推荐看板展示实际权重和排序依据 |
| 手机 | 五个底部入口、独立触控布局、横竖屏播放和旋转连续性 |

## 自动化验收与真机边界

本版分别记录源码/测试包自动化与最终生产签名包验收。源码侧包含编译、Lint、JVM / BFF 测试以及手机、API26、TV1080/TV4K 的模拟器测试；这些测试使用专用测试入口或测试签名，不等于最终生产 APK 已在每种设备完成全流程。TV 截图、焦点轨迹与软件模拟器帧耗时不代表电视 GPU 性能。

最终stable→未公开dev11验收目标已在API26 隔离模拟器通过 `adb install -r` 升级，安装后取回的APK哈希与各自记录的签名文件哈希一致；仅stable公开发布。真实应用界面建立的一条收藏/想看记录、服务器地址与订阅选择在升级和重启后保留。范围不包括所有 Room 表、旧数据库版本迁移、观看进度、真实片库或线上 feed→下载→系统安装器全链路。API33 独立模拟器在安装阶段被SIGKILL，设备离线，未取得安装后哈希或应用启动结果；这是基础设施结果不确定，不计为应用通过或失败，API33上的最终生产包安装仍未验证。另一个原无安装包的独立API26模拟器已完成最终stable空数据安装，取回APK哈希一致，首开实际渲染成功，默认stable/code10、服务器和片库为空；未知来源权限保持OFF，返回应用与重启后状态正确。冷启动超过`am start`等待阈值，ActivityManager记录约10.746秒显示；不据此宣称性能通过。

| 自动化环境 | 配置 |
|---|---|
| 手机 | API 30，1080×1920，含横屏播放和旋转 |
| TV 界面 | API 33，1920×1080 / density 320 |
| TV 界面 | API 33，3840×2160 / density 640 |
| 最低 API 兼容性 | API 26，离线字体、静态按钮、品牌降级与推荐看板；不代替完整导航/播放矩阵 |

TV 界面测试使用模拟器中的专用原生测试 Activity，不等同于真实 Android TV 系统或硬件认证。测试中的虚构片名、原创矢量画面及合成 H.264/AAC 片段均只用于测试；片段不进入用户 APK。

本次发布明确保留未验证项：2GB 盒子和实体4K电视、四小时播放、HDR/H.265/其他格式能力、完整网络/解码故障矩阵、真实电视安装升级与玻璃帧率。软件模拟器启动耗时不能换算成真机性能；系统组件 ANR 与应用错误分别记录，不隐藏环境失败。参见 [发布验收范围与后续验证](RELEASE_CHECKLIST.md)。本版不提供跨设备同步、扫码配对、DRM 或额外播放内核。

## 开发与复验

JDK 17、Gradle 8.13、Android SDK 36 / Build Tools 36.0.0。

```bash
bash scripts/generate-test-media.sh
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
npm --prefix backend/tv-bff test
```

`[ui-qa]` 提交触发完整设备矩阵，`[preview-release]` 在同一提交全部通过后发布预览包。发布附件包含截图对比结果、JUnit 报告、焦点轨迹和帧数据。

经确认的 A1 Logo、字标和静态 TV Banner 位于 `design/branding/`。品牌 CI 只校验已审阅素材，不自动重写。`scripts/render-branding.sh` 仅输出到显式指定的审阅目录，需比对确认后才能替换打包素材。应用内保留简短且不阻塞交互的动态；低档与减少动态使用静态。

字体离线打包 Noto Sans 与 Noto Sans CJK，并保留 OFL 许可。按内容语言或系统区域选择 SC/TC/HK/JP/KR 字形，不转换简繁文字。首次构建须下载固定哈希的官方文件；安装后无需字体网络访问。字体数据约 20.63 MB，尚不能将此数值当成 APK 总大小或运行内存。详见 [字体实现和许可](NOTO_TYPOGRAPHY.md)。

## 本地片库与目录

首次启动会将旧收藏、观看历史和片库状态事务性迁入 Room；保留原始记录，不使用破坏性迁移。每一集独立保存位置与实测时长，收藏与观看状态可以同时存在。若迁移失败，应用会提示，仍可加载服务器播放列表。详见 [数据与隐私说明](LOCAL_LIBRARY.md)。

搜索先显示本地结果，再补充可选元数据目录和 LibreTV 片源。支持已有别名、演员、类型、年份和来源评分；未知数据不会冒充满足筛选。首页推荐使用已配置的 BFF 目录候选，按本机收藏、片库状态及观看进度排序，不上传偏好。未配置目录或目录不可用时回退到本地记录；没有本地记录则保持空列表，可通过 LibreTV 搜索添加内容。客户端不调用 LibreTV 的 `/api/douban` 推荐代理，也不会把代理返回的评分改标为 LibreTV。

豆瓣 TOP250 / 评分筛选要求明确来源、有效许可到期时间与实际排名。可以接入运营者有权使用的目录，或在取得适用书面授权后启用独立的 BFF 获取模块。获取模块默认关闭，不附带抓取数据，且尚未进行获准的真实端点验证；不能把配置声明当作授权证明。客户端区分完整、部分、旧缓存及访问限制，不会将 TMDB 或 LibreTV 评分标为豆瓣。配置契约见 [BFF 文档](../backend/tv-bff/README.md)，来源审查及边界见[豆瓣获取说明](DOUBAN_ACQUISITION.md)。

本地数据库、设置和播放链接排除于 Android 云备份及设备迁移；当前没有跨设备同步或导出恢复功能。卸载仍会删除本地数据。

设置 → 本地推荐看板可查看实际偏好、手动权重与排序依据。推荐只在本机计算，不是机器学习训练，也不会把分数当成喜欢概率；应用其他联网功能照常。看板还可导出固定的第三方开源许可与源码包，这不是用户片库备份。详见 [推荐实现与许可](RANKSYS_INTEGRATION.md)。


### Device QA system-image provenance

TV1080 and TV4K acceptance use the official AOSP/default Android 33 x86_64
system image (`system-images;android-33;default;x86_64`). Phone API30 and
compatibility API26 retain `google_apis`. API levels, viewport sizes, density,
2048 MB emulator memory, Lavapipe configuration, UI assertions and golden
thresholds are unchanged. AOSP uses its own launcher and IME; the tests still
require actual IME visibility, submission, dismissal and restored window geometry.

Earlier Google-image TV evidence is a different environment and does not establish
acceptance for this AOSP matrix. This change avoids the observed NexusLauncher
cold-boot ANR and Google background-startup workload; it does not suppress ANRs or
guarantee a pass. A recorded ANR in a fresh emulator boot fails setup before tests.
The official Google image index lists Android 33 default x86_64 revision 2
(`x86_64-33_r02.zip`, 694005732 bytes, SHA1
`d4698590184a21fe1e8754284130ad3006b5fc79`), verified on 2026-11-04:
https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml
