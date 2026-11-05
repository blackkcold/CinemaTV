# 界面截图来源

本目录展示 **v0.7.0-preview 已发布预览版**的实际 Android 界面，不是设计稿，也不包含未发布玻璃/动效候选的截图。

测试使用虚构片名、原创矢量画面和合成媒体。`Synthetic` 为测试评分标识，不是真实电影评分；截图不代表 APK 内置这些影视资源。所有 PNG 保留发布附件中的原始字节，没有补画、替换文字或后期移除界面元素。

| 图片 | 页面 | 运行环境 |
|---|---|---|
| [tv-home.png](tv-home.png) | 电视首页 | AOSP API 33，1920×1080，density 320 |
| [tv-detail.png](tv-detail.png) | 电视详情与选集 | 同上 |
| [tv-library.png](tv-library.png) | 本地片库 | 同上 |
| [tv-recommendations.png](tv-recommendations.png) | 电视推荐看板 | 同上，按真实 Dialog 边界捕获 |
| [phone-recommendations.png](phone-recommendations.png) | 手机推荐看板 | API 30，1080×1920，按真实 Dialog 边界捕获 |

- [公开版本与原始验收附件](https://github.com/blackkcold/CinemaTV/releases/tag/v0.7.0-preview)
- [对应完整构建与 UI 验收](https://github.com/blackkcold/CinemaTV/actions/runs/41974230917)
- 测试提交：`5d119a4c0b32a94ed6099e54895ccec253511ace`
- 同树发布提交：`298e2a0c4f4065d994dafe27a10d0433c459c994`
- Git tree：`8f5bf237239cbc11d6047dd5d1b2a8400473938a`
- 原始附件：`CinemaTV-v0.7.0-preview-ui-evidence.zip`
- 附件 SHA-256：`94c34346f4bb6dfd195462f7a0b1a0e84b375000ea3d07b937eef694e96b92a2`

[manifest.json](manifest.json) 保存各图的归档路径、提交与 SHA-256；文档测试核验图片字节、尺寸和相对链接。要更新画廊，请使用新的已发布验收附件，并同时更新来源说明和清单，不能仅改哈希让校验通过。

模拟器界面截图不等于物理电视、HDR/编解码器覆盖或硬件图形性能认证。
