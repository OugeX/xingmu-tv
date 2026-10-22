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
