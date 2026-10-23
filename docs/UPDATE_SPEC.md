# 应用内热更（v0.3.4）

需求：App 自己检查 GitHub Release 有没有新版，有就提示更新；**访问不了就完全静默，不弹任何窗**。

## 1. 检查逻辑

```
GET https://api.github.com/repos/OugeX/xingmu-tv/releases/latest
Header: Accept: application/vnd.github+json, User-Agent: XingmuTV/<version>
超时: 连接 3s / 读取 4s；任何异常/非 200/JSON 解析失败 → 静默退出（不弹窗、不 toast）
```

- 版本比较：把 `tag_name`（如 `v0.3.4`）与 `PackageManager` 读到的 `versionName` 做 **语义化比较**（去 `v`，按 `.` 拆数字，逐段比），不比较字符串。
- 触发时机：
  - 冷启动后 **8 秒**（避开首屏与播放）；
  - 之后每 **24 小时**最多一次（SharedPreferences 记 `lastCheckAt`）；
  - 设置页「检查更新」手动触发无视频率限制。
- 只在 **Wi-Fi** 下自动检查（`ConnectivityManager` 判断），移动网络/无网直接跳过。
- 预发布过滤：`prerelease == true` 的版本不提示（除非设置里打开「包含测试版」）。

## 2. 资产选择

Release 里现在同时有标准包与 R8 精简包，命名约定：

| 文件 | 用途 |
| --- | --- |
| `xingmu-tv-v<ver>-<sha8>.apk` | **标准包**（推荐给排障/大存储） |
| `xingmu-tv-v<ver>-r8-<sha8>.apk` | R8 精简包（小存储机型） |

更新器选择规则：
1. `/proc/meminfo` 或 `StatFs(/data)` 判断：**/data 可用空间 < 300MB → 选 R8 包**，否则标准包；
2. 只接受 `/\.apk$/i` 且文件名包含所检测版本号的资产；找不到就回退 GitHub 的 release 页面提示（弹窗里给 URL 文本，可由用户自己复制）。

## 3. 更新弹窗（仅在检测到新版时出现）

- 内容：新版本号、更新说明前 500 字（Markdown 去掉代码块）、包体积、两个按钮 `立即更新` / `稍后`。
- `立即更新`：下载到 `cacheDir/update/<name>.apk`，显示进度（百分比 + 已下载 MB）；下载中可取消。
- 下载完成：校验 `Content-Length` 与实际字节一致 → 走 `FileProvider` + `ACTION_VIEW`(`application/vnd.android.package-archive`) 安装。
- API ≥ 26 若用户未授予「安装未知来源」：提示「请允许星幕安装应用后重试」，并跳到系统设置页（`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`）。
- `稍后`：本次会话不再提示，24 小时后再检查。
- **下载失败/安装包校验失败**：提示一次「更新包下载失败，可稍后重试」，不循环弹窗。

## 4. 设置页

「关于」区新增：

| 项 | 说明 |
| --- | --- |
| 当前版本 | `0.3.4 (30)` |
| 检查更新 | 手动按钮，点击后显示「已是最新 / 发现 vX.Y.Z」 |
| 自动检查 | 开关，默认开 |
| 包含测试版 | 开关，默认关 |
| 上次检查 | 时间文本，未检查过显示「从未」 |

## 5. 已知限制（写进设置页脚注）

- 走 GitHub API，**国内网络可能访问不了**：访问失败时静默跳过，不影响使用；需要更新可到 Release 页手动下载。
- 不做静默安装（需要系统签名），只做「下载 + 调起安装器」。
- 不做增量更新（省事且包体只有 3MB）。

## 6. 验收

| 项 | 标准 |
| --- | --- |
| 网络不可达 | 断网/超时 → 无任何弹窗与 toast，日志一行 `update check failed: <原因>` |
| 有新版本 | 弹窗显示版本号与体积；`立即更新` 能下载并调起安装 |
| 已是最新 | 手动检查提示「已是最新」 |
| 频率 | 24 小时内冷启动多次只检查一次 |
| 存储判断 | /data <300MB 时选择 R8 包 |

## 7. 桌面端（v0.2.0，Compose Desktop）

桌面端复用同一套 GitHub Release 流程与「访问不了就静默」的硬约束，差异只在安装包与安装动作：

| 项 | 桌面端实现 |
| --- | --- |
| API | `GET https://api.github.com/repos/OugeX/xingmu-tv/releases/latest`；可选 `GET /releases?per_page=10` 取测试版 |
| Header | `Accept: application/vnd.github+json`、`User-Agent: XingmuTV-desktop/0.2.0` |
| 超时 | 连接 3s / 请求（读）4s；非 200、空响应、JSON 解析失败都按失败处理 |
| 触发 | 启动后 8s 一次，之后每 24h 一次；仅在设置 `autoCheckUpdate`（默认 true）开启时执行；手动检查不受频率限制 |
| 失败 | 只写一行 `update check failed: <原因>`，状态回 `Idle`，不弹窗、不 toast、不自动重试 |
| 安装 | 只下载 + 用户点「打开安装包」交给系统 `Desktop.open`；**不自动/静默执行安装包** |

### 7.1 版本比较（重要：用资产版本，不是 tag）

安卓和桌面共用同一个 Release 流：tag 属于安卓版本线（例如 `v0.3.4`），桌面版本独立（例如 `0.2.0`）。
因此桌面端**不是**用 `tag_name` 与当前版本比较，而是：

1. 扫描 latest release 的全部 assets，找 `XingmuTV-<x.y.z>.(msi|exe)`；
2. 从**文件名**解析资产版本（`XingmuTV-0.2.0.msi` → `0.2.0`），取最高版本；
3. 用「资产版本 vs 当前运行版本」判断 `Available / UpToDate`；
4. `tag` 只用于 Release 页面链接与「发现 vX」展示。

找不到任何桌面安装包时：
- `tag` 版本 > 当前版本 → `Available`，但只提供「打开 Release 页面」（没有可下载安装包）；
- 否则 `Idle`（没有更新，也不打扰）。

### 7.2 资产选择规则

- 只认 `XingmuTV-<x.y.z>.msi` / `XingmuTV-<x.y.z>.exe`；忽略 `.apk` / `.zip` / `*.sha256` 等；
- 文件名必须严格匹配，版本段是数字边界：`XingmuTV-0.2.0.exe.sha256` 不会命中；
- 取资产版本最高的一个；同版本同时有 msi/exe 时：
  - Windows → `.msi`（系统集成好）；
  - 其它平台 → `.exe`（缺失时回退另一种）；
- 无匹配 → 退化为 Release 页面（见 7.1）。

### 7.3 下载与校验

- 下载目录：`AppPaths.update`（`<cacheDir>/update`），流式写入 `.part`，带进度与取消；
- 校验 `Content-Length`（服务端提供时）与实际字节一致；
- 校验文件头：msi 为 OLE `D0 CF 11 E0`，exe 为 `MZ`（`4D 5A`）；
- 失败删除半成品并**只重试一次**；第二次失败回 `Idle` 并写日志，不循环重试；
- 下载完成后状态为 `Ready(file)`，UI 显示「打开安装包」，由用户点击后调用系统安装器。

### 7.4 设置页「关于」区文案

| 状态 | 文案 |
| --- | --- |
| Idle | 手动检查不受频率限制 |
| Checking | 检查中… |
| UpToDate | 已是最新 |
| Available | 发现 vX.Y.Z（无桌面包时为「发现 tag（仅 Release 页面）」） |
| Downloading | 正在下载 N% |
| Ready | 安装包已就绪 → 打开安装包 |
| Failed | 下载失败文案（检查失败不会进入此状态，直接静默 Idle） |

同时显示：当前版本 `v0.2.0`、上次检查时间（未检查为「从未」）、自动检查开关、包含测试版开关。

### 7.5 与 Android 的差异

- 桌面端不判断 Wi-Fi（桌面通常有线/共享网络），只受 `autoCheckUpdate` 与 24h 节流控制；
- 桌面端不做系统「未知来源」权限引导，直接 `Desktop.open` 交给系统安装器；
- macOS 安装包（`.dmg`）目前未纳入资产选择；本机只验证 Windows 产物，macOS 包需在 macOS/CI 构建后再扩展 Picker。
