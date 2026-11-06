# 本地构建默认值（个人 LibreTV 端点）

个人设备上的本地构建可以把 LibreTV 端点编进 APK，使应用首次启动就指向它并在需要时自动登录一次。**公开产物永远不包含这些值**：本文件描述的三层闸门会拒绝，或在校验阶段证伪。

## 行为

- `CINEMATV_LIBRETV_SERVER` / `CINEMATV_LIBRETV_PASSWORD` 在构建时写入 `BuildConfig.LIBRETV_DEFAULT_*`；未设置时两者都是空字符串，公开构建的行为与之前完全一致。
- 运行时只有**设备上没有显式设置**服务器地址时才会使用默认值（`resolveServer`）；设置页保存过的地址永远优先，因此用户不会被默认值困住。
- 连接后若服务器要求密码且尚未授权，会用默认密码自动登录**一次**；失败即停在"请在设置中登录"，不会循环重试。用户手动登录仍然有效。
- 密码不落盘、不写日志、不进诊断包；只在进程内存里用于换取会话 Cookie。

## 三层闸门

| 层 | 位置 | 行为 |
|---|---|---|
| 构建配置 | `app/build.gradle.kts` | `GITHUB_ACTIONS=true` 或 `cinematvUnsignedCandidate=true` 时，只要设置了任一 `CINEMATV_LIBRETV_*` 就配置失败 |
| 候选脚本 | `scripts/hybrid/build-dev-candidate.sh` | 预检 `declare -p`，发现任一变量即退出，不进入构建 |
| 产物校验 | `scripts/hybrid/artifacts.py` | 解码候选 manifest，要求 `dev.cinematv.PRIVATE_BUILD` 为 `false` |

`PRIVATE_BUILD` 由 Gradle 按是否注入默认值写入 manifest，是产物层面唯一可自动检查的"这是私人包"的证据。它不能阻止有人手工上传一个私人包，所以私人包必须改名保存、永不放进 Release。

## 构建示例

从 1Password 读取，变量只在当前 shell 生命周期内存在：

```sh
# 读取条目里的 hostname / credential（不打印）
export CINEMATV_LIBRETV_SERVER="$(op item get <item> --fields label=hostname --reveal --format json | python3 -c 'import json,sys;print(json.load(sys.stdin)["value"])')"
export CINEMATV_LIBRETV_PASSWORD="$(op item get <item> --fields label=credential --reveal --format json | python3 -c 'import json,sys;print(json.load(sys.stdin)["value"])')"

gradle --no-daemon -PcinematvChannel=dev -PcinematvVersionCode=13 -PcinematvVersionName=0.9.0-dev.1 \
  -PcinematvUpdatesEnabled=true :app:assembleDebug
```

- 签名变量（`CINEMATV_KEYSTORE_*`）与默认值变量互相独立；两者同时存在只影响本地构建，候选脚本会拒绝。
- 已知代价：`BuildConfig` 字符串可从 APK 提取，`isMinifyEnabled=false` 时尤其直接。这是私人设备便利性与"公开产物不得泄露"之间的取舍，只应在自己的设备上使用。
- 版本纪律：私人包与公开发布包即使 versionCode 相同，字节也不同；不要把私人包当成发布包验收，也不要用它更新公开设备。将来若要换回正式包，同签名可直接覆盖安装，不同签名需卸载。
