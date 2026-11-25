# 诊断日志（Dev）

诊断日志用于开发与验收期的现场排查。它默认关闭，只在 `dev` 渠道可见，且必须由用户在「设置 → 开发者选项 · Dev → 诊断日志」显式开启。

## 记录什么

- 应用启动：版本、渠道、设备型号、API 级别。
- 匿名豆瓣请求：逻辑端点（top250 / browse / hot / doulist）、HTTP 状态、字节数（成功时）、停止原因、耗时；门禁暂停时另记等待与可重试时间。
- 公共门禁：可重试时间、连续命中次数、预算剩余；账号门禁：等待与可重试时间。
- 账号流程：初始化、登录验证阶段、身份来源（redirect / dom）、片单页进度、评分条数、失败阶段与可重试时间、断开、取消。
- 仅「详细」级别追加页面路由、返回（含返回目标）与每条评分的进度。

主要事件：`app.start`、`diagnostics.*`、`douban.public.request/failed/gate/gate.stop/cache/chart.partial`、`douban.account.initialize/login/sync/score/gate/failed/request/disconnect`、`ui.route/ui.back`。

## 绝不记录

Cookie、Token、密码、Authorization、账号 ID、账号页 URL 查询串、原始 HTML、片名、剧集名、播放地址。

字段键由 `DiagnosticFields.allowed` 这一封闭集合强制：不在名单内的键在写入前直接丢弃，包括 `ts`、`lvl`、`ev`、`trace` 这类结构名，因此调用方无法在运行时扩大 schema，也无法伪造信封字段。名单内的值仍统一过一次脱敏（`cookie=`/`token:`/`Bearer`/`dbcl2`/`bid`，超长截断 160 字符）。新增字段必须同时加入名单，否则不会写入。导出包不自动上传。

## 存储与清理

- 文件：`filesDir/diagnostics/app-YYYYMMDD[-2..-n].jsonl`。
- 单文件 2 MB 轮转，总计 20 MB、保留 7 天；这是目标上限，单行超过上限时该行仍会完整写入；超限删最旧，绝不删当前写入文件。
- 修改时间早于 2000 年的文件不以时间戳判断年龄，改用文件名中的日期：恢复备份或时钟缺失不会误删当天文件，也不会让真正过期的文件永久留存。
- 每行立即 flush；退出时发送一次非阻塞 flush；进程被立刻杀死时最多丢失队列中尚未出队的少量记录。
- 队列有界（256）：满载时丢弃最新一条而不是阻塞调用方，丢弃条数由 `stats().dropped` 暴露，大于 0 时面板显示「丢弃 N 条」。部分日志不会被误认为完整日志。
- 写入失败（如存储写满）暂停 10 秒后自动重试；用户关闭再开启会立即重试，不必重启应用。失败的半行在下次打开文件时被截断，不会与下一条记录合并成坏行。
- 「清空日志」通过写队列串行删除全部日志文件；「清理缓存」删除 `cacheDir/douban-public` 并重置请求冷却，不碰账号会话与片库。
- 导出串行执行：连续点击只会生成一个有效压缩包，也不会在写包时删掉另一个包。导出 zip 只保留最近 5 个、写入后裁剪、总量不超过 50 MB。

## 导出

「导出日志」生成 `cinematv-diag-YYYYMMDD-HHmmss.zip`，包含：

- `manifest.json`：版本、渠道、设备、时间、级别、文件数、字节数、隐私说明。
- `logs/*.jsonl`：当前保留的日志。

位置优先 `getExternalFilesDir("diagnostics")`，否则 `filesDir/diagnostics`。手机可再点「分享」通过系统分享面板发出；TV 显示路径。取回方式二选一：

- `scripts/diagnostics/pull-logs.sh`：优先 `adb pull /sdcard/Android/data/<包名>/files/diagnostics/`，失败时回退 `adb exec-out run-as <包名> cat ...`（仅 debuggable 包），最后在本地生成 zip 并打印路径。
- 手工：`adb pull /sdcard/Android/data/dev.cinematv/files/diagnostics/`，或 `adb exec-out run-as dev.cinematv cat files/diagnostics/<文件>`。

## 渠道边界

`stable` 不显示开发者选项；日志开关即使被写入也会被强制关闭。诊断代码与 `${applicationId}.diagnostics` provider 会随所有渠道编译，仅在 `UPDATE_CHANNEL=dev` 且用户显式开启时生效；导出目录仅暴露 `diagnostics/`，`exported=false`。日志代码不读取账号 vault，也不改变任何网络行为。

## 验收边界

自动覆盖（JVM）：JSON 转义、脱敏、字段键白名单与结构名遮蔽、轮转、保留期、1970 时间戳回退到文件名日期、清空后继续写、大行完整写入、半行修复、写入失败冷却恢复与手动重试、队列拒绝计数与顺序、`level=OFF` 不写。

自动覆盖（仪器，`app/src/androidTest/.../DiagnosticsUiTest.kt`）：手机端开启/关闭/级别切换并确认字节真正落盘；TV 端 D-pad 打开面板、OK 切换、Back 关闭后焦点回到入口；清空确认默认焦点在「取消」且取消不删除日志；360dp 宽度 + 1.3 字体缩放下三个主按钮仍可见且宽度不小于 58dp。

2026-11-06 的实测边界：上述 4 个仪器用例在 API 34 模拟器（1080x2400、420dpi、fontScale 1.0）上 4/4 通过，并与既有 `DoubanAccountUiTest`、`TvDoubanAccountUiTest` 合计 8/8 一起通过；`pull-logs.sh` 的 `adb pull` 主路径与 `run-as` 回退路径各实测一次。`douban-account-safety.yml` 在 API 33 与 API 26 上执行同一组类，并要求方法集合完全执行、无失败无跳过——这是 CI 配置事实，具体结果以当次运行记录为准。

仍需人工：真实设备上的导出、系统分享 intent、`adb pull`/`run-as` 回退、实体 TV 上逐键遍历与长列表滚动、真实账号流程的 trace 核对。日志只能证明应用观察到的状态，不能替代豆瓣官方错误码或服务端日志。清单见 `docs/QA_DIAGNOSTICS.md`。
