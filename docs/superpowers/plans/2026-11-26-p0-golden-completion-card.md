# P0 金标补齐 — 实施卡

> 阶段：P0 收尾（补金标）。不进入 P2。
> 前置：`feature/tv-ui-theme-refresh` @ `9bd1a4b`，工作树含 P0/P1 改动（8 改 + 6 新增）。
> 工具链：`/private/var/folders/ks/flqyrh0j5wvf2t7v4s7lcnz0b5tfzv/T/opencode/gradle813-tv-ux/gradle-8.13/bin/gradle`，`ANDROID_HOME=/Users/11169285/android-sdk`，`--no-daemon`。

---

## 实施卡

```
阶段：P0-金标补齐
日期：2026-11-26
worker：主代理
目标基线：bbb3061（release: reserve Dev 0.9.0-dev.4 / code 17）

允许修改：无产品代码改动。
  本阶段要么是纯 rebase（冲突处按「S1 行为优先、主题值只追加」解决），
  要么是补采金标证据；两者都不应产生新的产品行为改动。

禁止：
  - 修改 HomeScreen.kt / SearchScreen.kt / TvRootApp.kt / SearchIndex.kt /
    SearchAggregator.kt / SearchGroupIdentity.kt 的行为语义（发现缺陷须上报）
  - 放宽金标阈值（mean ≤ 0.020 / changed ≤ 0.08）
  - 扩大 manifest mask
  - 以「应该没变」代替实测
  - 提交 / 推送

步骤：
  1. rebase 到 bbb3061；记录冲突文件与解法
  2. 编译三源集 + :app:testDebugUnitTest
  3. 起 cinematv-tv AVD（-gpu host，记录 renderer 字符串）
  4. 跑 TvUiRegressionTest，实测它是否能跑完全部 15 张
     - 通过 → 记录「S1 焦点改动解决了该失败，本次实测确认」+ 命令与输出
     - 失败 → 停，报失败位置/断言/当前聚焦节点/日志；不改 S1 代码
  5. 6 张金标 A/B：同一 AVD、同一 fixture、同一 renderer，
     基线构建 vs 本次构建，逐张报 raw max diff / meanAbsError / changedRatio
  6. 逐 role 档位复核（rebase 后，含搜索页路径）
  7. 记录：默认 TvVisual 逐字段等于合并前实际行为

退出条件：
  - rebase 后 git rev-parse HEAD 已记录
  - TvUiRegressionTest 结论为「实测」而非推断
  - 6 张金标逐张数值，全部 ≤ 阈值
  - 单测通过
  - 任一金标超阈值或出现布局位移 → P0 失败，停止上报

回滚点：rebase 前的 9bd1a4b + 工作树（本阶段开始前已确认 git status）。
```

---

## 已知偏差声明（本阶段不修，须保留在记录里）

- `resolveVisualTheme` 恒返回 CINEMA（AUTO 分支留空），PosterVisual 绘制在 P2。
- `visual_theme` 偏好键未加。
- `ProductionGlassProvider` 签名未改，`LocalTvVisual` 由 `CinemaTvTheme` 提供。

## 待核实（本阶段的核心任务）

上一轮记录中写了「S1 已修」，依据仅为基线复现失败 + S1 合并通知。**该说法目前是推断**：
S1 的 CI 只覆盖单测、assemble、lint 与 dashboard lanes，`TvUiRegressionTest` 是仪器测试，
S1 未在设备上运行过它。本阶段必须以实测替换该推断，无论结论是肯定还是否定。
