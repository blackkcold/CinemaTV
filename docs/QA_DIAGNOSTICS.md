# 诊断日志人工验收清单

本清单只覆盖自动化无法证明的部分。自动化范围见 `docs/DIAGNOSTICS.md` 的「验收边界」。通过本清单不等于通过真机认证：实体 TV、真实账号与真实网络仍需现场结论。

## 0. 前置

- 安装 **dev** 渠道包（`UPDATE_CHANNEL=dev`，开发者选项可见）。
- 设备已登录同一个 adb 序列号，`adb devices` 显示 `device`。
- 记录：应用版本、`versionCode`、设备型号、Android 版本、字体缩放、显示尺寸。

## 1. 自动化（先跑，失败即停）

```sh
# JVM：诊断相关单测（18 项）
gradle --no-daemon :app:testDebugUnitTest --tests "dev.cinematv.diagnostics.*"

# 编译仪器测试（不执行）
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest

# 执行仪器测试（模拟器或真机均可）
adb shell am instrument -w \
  -e class dev.cinematv.DiagnosticsUiTest,dev.cinematv.TvDiagnosticsUiTest,dev.cinematv.TvDiagnosticsConfirmFocusTest,dev.cinematv.DiagnosticsLayoutTest \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner
```

CI 侧由 `douban-account-safety.yml` 在 API 33 / API 26 两个模拟器上执行同一组类，并校验方法集合完全执行、无失败无跳过。

## 2. 手机逐项（360dp 与 1.3 字体）

- [ ] 设置 → 开发者选项 · Dev → 诊断日志：默认「已关闭」。
- [ ] 点「开启」：入口摘要变为「记录中 · 基础」，约 2 秒内出现非 0 字节。
- [ ] 点「详细」：摘要变为「记录中 · 详细」；再点「基础」可回退。
- [ ] 返回设置再进入：开关状态保持（持久化生效）。
- [ ] 关闭再开启：不崩溃，摘要恢复「记录中」，磁盘字节继续增长。
- [ ] 清空日志：弹出确认框；默认焦点/默认按钮为「取消」；取消后日志仍在。
- [ ] 再次清空并确认：文件列表清空，摘要回到 0 KB；之后仍能继续记录。
- [ ] 清理缓存：公开榜单缓存字节归零，账号会话与片库不受影响（重进榜单能重新加载）。
- [ ] 导出日志：生成 zip，状态行显示绝对路径；文件存在且可解压，`manifest.json` 与 `logs/*.jsonl` 齐全。
- [ ] 分享：系统分享面板能收到 `application/zip` 附件（不需要真的发送）。
- [ ] 连点两次导出：只产生一个有效包，另一个请求不覆盖、不产生半包。
- [ ] 系统字体 1.3 / 1.5：三个主按钮、导出/清空两行不重叠、不截断；对话框内容可滚动到最底部。
- [ ] 关闭系统字体放大后布局回位。

## 3. TV 逐键（实体设备或 TV 模拟器）

- [ ] 遥控器方向键可把焦点移到「开发者选项 · Dev」，OK 打开。
- [ ] 方向键可移到「诊断日志」入口，OK 打开面板，焦点进入面板（不是掉到面板后面）。
- [ ] OK 切换开关；左右/上下能在三个主按钮间移动；焦点框始终可见。
- [ ] 「清空日志」OK 后，确认框默认焦点在「取消」，不会误触删除。
- [ ] 确认框内 Back 只关闭确认框，面板与焦点保留。
- [ ] 面板内 Back 只关闭面板，焦点回到「诊断日志」入口。
- [ ] 再按 Back 关闭开发者选项，焦点回到设置页对应项。
- [ ] 面板内容超出一屏时，方向键滚动可到达「清理缓存」与「导出日志」，焦点不丢失。
- [ ] 遥控器长按/连按不产生重复导出或重复清空。

## 4. 取回日志

```sh
# 一条命令：优先 adb pull，失败回退 run-as，最后打印本地 zip 路径
scripts/diagnostics/pull-logs.sh

# 指定设备与输出目录
scripts/diagnostics/pull-logs.sh --serial emulator-5554 --out ./qa --keep
```

- [ ] 未导出过、只有 `.jsonl` 时脚本仍能取回（走 `run-as` 回退）。
- [ ] 取回的 zip 内文件数与设备端一致，末行是完整 JSON。
- [ ] `stable` 包执行时脚本给出明确失败信息，不静默产出空包。

## 5. 真实账号与真实豆瓣（可选，需用户授权）

- [ ] 开启「详细」→ 扫码登录 → 保存本机会话 → 同步一轮：`douban.account.login`、`sync.start`、`sync.page` 的 `trace` 可串联。
- [ ] 杀进程再进入 → 继续同步：`sync.start` 的 `resume`/`doneShelves` 与界面一致。
- [ ] 触发限流或安全验证：`douban.*.gate*` / `failed` 的 `retryAt`、`stop` 与界面提示一致。
- [ ] 导出后本地搜索：不存在 `dbcl2`、`bid`、`Bearer`、账号 ID、片名、原始 HTML。
- [ ] 核对 `manifest.json` 的版本/渠道/设备与手记一致。

## 6. 异常与判读

| 现象 | 判读 |
|---|---|
| 摘要「写入失败」 | 目录不可写（存储满/权限）。释放空间后 10 秒内自动恢复，或关闭再开启立即重试。 |
| 面板出现「丢弃 N 条」 | 记录速率超过写盘速度，日志不完整。降低级别或缩短复现步骤后重测。 |
| 日志末行不完整 | 进程被强杀。下次写入会截断半行，属预期；不影响后续行。 |
| 文件时间显示 1970 | 恢复/时钟异常。保留期按文件名日期判断，当天文件不会被误删。 |
| `level` 字段与面板不一致 | 以 `fields.level` 为准；级别切换记录在切换之后写入。 |

## 7. 证据

- 截图：手机（1.0/1.3 字体）、TV（焦点在入口/面板/确认框三态）。
- 文本：`qa/stages.tsv` 或等效执行记录、仪器测试 XML、取回 zip 的 SHA-256。
- 结论只写实际观测到的；未执行的项目保持未勾选。

## 8. 本机执行记录（2026-11-06）

- JVM：`dev.cinematv.diagnostics.*` 18/18；全量 `:app:testDebugUnitTest` 424/424，0 失败 0 跳过。
- 仪器：`DiagnosticsUiTest`、`TvDiagnosticsUiTest`、`TvDiagnosticsConfirmFocusTest`、`DiagnosticsLayoutTest` 在 API 34 模拟器（1080x2400、420dpi）4/4；与既有 `DoubanAccountUiTest`、`TvDoubanAccountUiTest` 合计 8/8。
- `pull-logs.sh`：`run-as` 回退与 `adb pull` 主路径各执行一次，zip 内容与设备端一致。
- lint：0 error（73 warning / 4 hint 均为既有项）。
- 未执行：API 26 / API 33 的 CI 矩阵、实体 TV、真实账号与真实豆瓣。
