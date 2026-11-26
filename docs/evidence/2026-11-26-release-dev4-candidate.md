# Dev 0.9.0-dev.4 / code 17 — 候选与门禁证据（签名待完成）

**状态：候选已构建并通过全部自动化门禁；生产签名尚未完成。** 本文不是发布回执，也不构成签名产物验收。

## 源码身份

| 项 | 值 |
|---|---|
| 分支 | `dev-compose` |
| 源码提交 | `bbb3061abf8dbe228810edb229cac9a024072862` |
| 源码 tree | `0816b65d4997aa0b45b9f30f1ceff9ac24707305` |
| `app/src` tree | `619914a45ecc5605caa201ca506bb61455afa4da` |
| `backdrop/src` tree | `81744a6ca61015a4890492ab34153b6809cc19f5` |
| `app/build.gradle.kts` blob | `3e3e356b098e2253391222eae8b391e33b4639c5` |
| 产品载荷提交 | `0fe0f52`（PR #62，`d1eb6e1` 合并） |
| 版本预留提交 | `bbb3061`（仅脚本、契约测试、发布说明；未动产品代码） |

## 未签名候选

由 `scripts/updates/build-unsigned-candidate.sh dev` 在干净工作树（`cinematv-cand17`，detached at `bbb3061`）构建：

- `CinemaTV-0.9.0-dev.4-code17-dev-UNSIGNED.apk`，**43,719,599 bytes**
- SHA-256 `0bac8b8952ba260926daa6456b82cc477ff25f55921e6d208345f6b0f9ac1818`
- 收据 `candidate-dev/unsigned-receipt.json`：`channel dev`、`versionCode 17`、`versionName 0.9.0-dev.4`、`minSdk 26`、`updatesEnabled true`、`status unsigned-not-installable-not-certified`
- **NOT FOR INSTALLATION**

独立核验（不是复述收据）：

| 检查 | 结果 |
|---|---|
| `apkanalyzer manifest print` → `package` | `dev.cinematv` |
| `android:versionCode` | `17` |
| `android:versionName` | `0.9.0-dev.4` |
| `android:minSdkVersion` | `26` |
| `apksigner verify` | `DOES NOT VERIFY` / `Missing META-INF/MANIFEST.MF`（确认未签名） |

## 自动化门禁

### 候选源码上的宿主门禁（构建脚本内部执行）

`build-unsigned-candidate.sh` 在构建前拒绝任何签名凭证，并运行：

| 任务 | 结果 |
|---|---|
| `:app:testReleaseUnitTest` | 通过 |
| `:app:assembleRelease` | 通过 |
| `:app:lintRelease` | 通过（无新增错误） |

`BUILD SUCCESSFUL in 2m 9s`，`108 actionable tasks: 76 executed, 32 from cache`。

### PR #62 合并前（`0fe0f52`）

| 检查 | 结果 |
|---|---|
| `apk` | pass |
| `no-node` | pass |
| `protocol (2.21.1)` / `(2.21.2)` | pass |
| `dashboard (phone)` / `(tv1080)` / `(compat26)` | pass |
| preview / promote-preview / ui-qa | skipping（路径未触发） |

宿主本地另跑过完整门禁：`:app:testDebugUnitTest` 474/474、`:backdrop:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 全部通过。

### 候选提交上的 CI（`bbb3061`）

| 运行 | 结果 |
|---|---|
| `Android TV APK`（`dev-compose` push） | success，9m57s |
| `Dev audit / official LibreTV compatibility` | 首次 failure，重跑后 **success（30s）** |

`Dev audit` 首次失败在 `protocol (2.21.1)` 的 *official subscription parsing* 步骤。同一工作流在 PR #62 分支上同一源码通过，且本地按 `d1eb6e1` 逐一核对四个 fixture 的远端字节与本地 SHA-256 **全部一致**（4/4 match）。判定为上游/网络抖动，重跑即通过；不是代码缺陷。

`scripts/tests` 契约套件（`test_unsigned_candidates` + `test_release_docs`）**20/20 通过**，其中 Dev 预留断言已随账本改为 `code 17` / `0.9.0-dev.4`。

### 后端与 Python 门禁（本轮补跑）

| 套件 | 命令 | 结果 |
|---|---|---|
| `backend/tv-bff` | `npm --prefix backend/tv-bff test` | **78/78 通过**，0 fail |
| `scripts/tests` 全量 | `python3 -m unittest discover -s scripts/tests` | `203 tests`，`4 failures + 42 errors` — **本机既有环境问题，非本版引入** |

Python 全量套件的失败全部归于两个与本版无关的原因，均已取证：

1. `test_merge_ui_evidence` 等：`ValueError: Invalid evidence parent: /var`。构建工作树位于 `$TMPDIR`（`/private/var/...`），`scripts/merge-ui-evidence.py` 的证据路径校验拒绝了该前缀。属工作树位置导致，与源码无关。
2. `test_device_qa_harness` 系列（42 errors）：依赖 `timeout`、AVD 与设备工具链的本机运行环境，本机未提供。

**对照证据：** 在**未改动的基线提交 `9bd1a4b`**（主检出目录，非临时工作树）上运行同一条命令，得到**完全相同**的 `Ran 203 tests ... FAILED (failures=4, errors=42)` 与相同报错。因此这些失败不是本版引入的回归，也不构成本版的通过或失败证据。

本版相关且可在本机生效的 Python 契约（`test_unsigned_candidates` + `test_release_docs`）20/20 通过；CI 的 `no-node` 与 `Dev audit` 工作流包含其余 Python 门禁且已通过。

## 生产签名（未完成）

固定身份证书 SHA-256 应为
`0798f1686a28ae4a386a376efcabba3a4c4bb547de760210d944a58455092e8c`
（别名 `cinematv-release-20261105-v2`，密钥在 1Password `AgentKey Vault`，从不进仓库、Actions、产物或日志）。

**当前阻塞：1Password CLI 授权超时。** 探测链：

```
Session delegation enabled
NM request: NmRequestAuthorization
[ERROR] authorization timeout
```

1Password 应用在运行、账户 `blackkcold@outlook.com` 已配置；`op vault list`、`op item list` 均返回 `authorization timeout`，即授权请求已发出但未在应用侧获得批准。该步骤只能由人在 GUI 完成，不允许把主密码管道进 `op signin`。

签名完成后需追加：签名 APK 字节数、SHA-256、`apksigner verify` v2/v3 结果、`zipalign -c -P 16`、ZIP 条目保留情况，以及 Release 资产。

## 未验证（不得写成通过）

- D-pad / OK / Back / IME 的真实按键时序；三态切换帧序；导航隐藏时不可聚焦的实机行为。
- 跨源聚合在真实数据上的漏合并/误合并率；换源后的实际播放与会话续接。
- 手机端实机行为；API 26/29/31/33/36 兼容矩阵。
- 金标采集与人工评审；E-gate / T-gate；任何帧时间、内存或功耗数据。
- 同签名覆盖升级与更新 feed 的端到端验收。
- 物理电视验收，包括所有者可选的 T-gate：**未运行**。

编译、单元测试与 lint 都不构成 UX 验收，也不构成性能结论。
