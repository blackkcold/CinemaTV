# CinemaTV 图标 V3 已确认开发包

2026-11-04。用户已确认图标方向并授权开始开发。本目录保存已确认开发包的 SVG 与 manifest 子集；原生集成已写入应用，尚待合并后的 CI 编译和设备验收。

## 文件

- `svg/compact/default`：24/32px 工具栏首选。去掉细描边、整体投影和不必要小细节。
- `svg/compact/selected`：同一几何的微蓝选中态。选中不等于 D-pad 焦点。
- `svg/standard/default`、`svg/standard/selected`：保留细微层次与阴影的 48px 及更大展示版本。
- `svg/monochrome`：白色轮廓，便于重着色、可访问性与最低成本回退。
- `manifest.json`：逐枚路径、统一参数、语义和校验记录。
- `path-bounds.json`：转换时计算并核对的精确路径边界，供无第三方依赖的原生渐变坐标转换使用。

原始交付中的 `preview/`、`approved-review.png` 和 `source-paths.json` 不在此仓库子集中。开发时已查看评审图；图层、镂空与样式以保留的完整 SVG 为准。原始 ZIP SHA-256：`cc7547493bee4a4037d9e9711374de8e16a8667aa6bbf7503d4c3e6858101d21`。

## 实现约定

1. 全部采用 48×48 viewBox。不要以外接框重新居中或逐枚拉伸，播放的光学偏移已经写入几何。
2. 前景使用纵向线性渐变，默认冷白，选中微蓝。只有少数语义需要独立叠层，例如片库；不要加统一 App 图标底板。
3. 本包不要求为每枚图标运行模糊或折射 shader。Android 可将 path 和 gradient 转成原生 Compose Path / Brush；紧凑版本无运行时 SVG filter。
4. 大尺寸 SVG 的投影可由简单偏移层或预渲染资产表达，不建议在 TV 上为每枚图标建立独立滤镜层。
5. D-pad 焦点由外层按钮/导航容器表达。不要把蓝色选中态直接等同焦点。
6. 只对 back 使用 RTL 镜像。无文本的装饰图标不重复朗读；可交互图标必须由项目本地化字符串提供语义标签。
7. 每个 SVG 内的渐变 ID 是局部通用名字。如果网页把多个 SVG 合并到同一 DOM，应给 ID 加图标名前缀。
8. compact 在 24/32px 已检查轮廓、间隙与裁切；仍需在应用实际 dp、TV 缩放、背景及观看距离下验收。

## 改动记录

- 播放：微调光学重心。
- 首页：扩大门洞，避免小尺寸粘连。
- 信息：缩小相对光学尺寸，减轻圆盘重量。
- 设置：齿角做短曲线过渡，移除紧凑版本的细描边与投影。
- 全套：统一光源、冷白层次、24/32px 简化规则。

## 使用与来源

这是为 CinemaTV 原创绘制的图形，不包含 Apple、SF Symbols 或 Smartisan 的原始图标资产。方向参考 Apple 的图标/材料设计语言；不是 Apple 官方资源。

## Android integration

`app/src/main/res/drawable/cinema_v3_*` contains native Android vectors generated from
these approved compact/default, compact/selected and monochrome SVGs. Regenerate with
`python scripts/icons/generate_vectors.py` (Python standard library only). The
checked-in `path-bounds.json` records exact Bézier/arc bounds calculated with
`svgpathtools` during initial conversion; paths must match before reuse. The
viewport, approved geometry (within the precision bound below), fill rules, independent library layers, object-bounding-box
gradients and Play's +1.4 optical offset are retained. No runtime SVG, bitmap, blur,
shadow filter or per-icon shader is used. Standard SVGs are retained as source only:
current toolbar/action consumers are 20–24dp, so standard-size detail is not useful.

`CinemaIcon` separates selection from focus. Light/focused TV surfaces and light phone
primary buttons use approved monochrome geometry with dark ink; actual selected state
uses the approved blue endpoint. A `lowCost` monochrome option is available independently
of glass quality (compact vectors are already suitable for baseline TVs). Only Back
vectors auto-mirror in RTL. Decorative children have null descriptions; existing parent
labels, text semantics, click handlers, target sizes and focus requesters are preserved.

Consumer mapping:
- TV toolbar: Search / Library / Settings.
- TV detail: Back / Play / Bookmark; settings and player Back; player Play / Pause.
- Phone navigation: Home / Search / Library and Settings; detail Back / Play / Bookmark;
  player Back / Play / Pause.
- Media3 built-in phone controller: documented Play / Pause drawable aliases, preserving
  Media3 playback state, descriptions and control behavior in portrait and landscape.
- More / Info: native assets ready, no equivalent existing icon consumer to replace.
- Movie / Live TV, logo, banner, poster artwork and rating stars stay unchanged.

Validation: `python -m unittest discover -s scripts/tests -p test_approved_icons.py -v`
checks all 30 source conversions, selection geometry, apertures, clipping, layers,
optical translation and RTL. Kotlin `CinemaIconTest` covers tier/selection policy.
Android compilation, native raster inspection at actual density, focus contrast,
TalkBack and device D-pad/touch acceptance still require the combined CI/device gate.

### Generated numeric precision and Media3 overrides

The generated Settings contour rounds only absolute M/L/Q point coordinates to four
decimal places. The approved SVGs and exact bounds metadata remain unchanged. Tests
verify that only this outer contour changes, each coordinate moves by at most 0.00005
viewport units, and the inner aperture/arc data stays exact. Lines and quadratic Bézier
curves are convex combinations of their endpoint/control coordinates, so the continuous
Euclidean error is bounded by √2 × 0.00005 (< 0.000071 viewport units), comfortably below
0.001. Relative and arc parameters are never rounded; all other icon paths are unchanged.
This reduces redundant source precision; no runtime performance gain is claimed.

Media3 explicitly documents global `exo_styled_controls_play` and
`exo_styled_controls_pause` replacements in the
[PlayerControlView reference](https://developer.android.com/reference/androidx/media3/ui/PlayerControlView#overriding-drawables-globally).
Those two alias declarations carry `tools:override="true"` to acknowledge the deliberate
replacement. No broad lint ignore is used. Lint clearance on the new candidate still
requires the combined Android gate.
