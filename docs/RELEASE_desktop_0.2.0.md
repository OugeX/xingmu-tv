## 星幕 TV · 桌面端 v0.2.0（Windows）

PC 端从「能看」升级到「能追」：记住看到哪、收藏点与台、直播分组跟安卓一致，设置里还能一键检查更新。

### 新增
| 能力 | 说明 |
| --- | --- |
| 续播 / 继续观看 | 进度 15s 落盘（暂停/切集/退出也落），浏览页顶部横排 8 条、详情「继续观看 · 第N集 12:34」、起播后自动 seek；`<60s 且 <5%` 不记，`≥95%` 记为该集看完并推进集数 |
| 收藏 | 点播（`sourceId+vodId`）与直播频道（`channelId`）都能收藏，去重、可取消 |
| 最近 | 直播最近观看 20 条，去重、时间倒序 |
| 直播分组 | 高清专区 **114** → 央视 **28** → 卫视 **26** → 地方 **212**（每页 60，共 4 页）→ 收藏 → 最近 |
| 应用内检查更新 | 查 GitHub Releases，按**资产版本**挑 `.msi`/`.exe`，进度/取消，Content-Length + 文件头校验；下载完点「打开安装包」。连不上只写日志，不弹窗、不自动重试 |
| 版本不再硬编码 | 构建注入 `XingmuTV-version.properties`，关于页读真实版本 |

### 为什么不用 release tag 比版本
安卓和桌面共用一个 release 流：tag 是 `v0.3.4`（安卓线），桌面是 `0.2.0`。
拿 tag 比会永远误报「有新版本」，还会去找不存在的 `XingmuTV-0.3.4.msi`。
所以更新器只在 release 资产里找 `XingmuTV-<x.y.z>.(msi|exe)`，解析**资产自己的版本号**再比较，tag 只用来拼 Release 链接。
`.apk` / `.zip` / `*.sha256` 一律忽略；`XingmuTV-0.2.0.exe.sha256` 这种名字不会被误判成安装包。

### 下载
| 文件 | 大小 | SHA256 |
| --- | --- | --- |
| `XingmuTV-0.2.0.msi`（推荐） | 57,964,937 B | `066162ad71f42f0a5eb32abc6ed182a4ba5b86aaf14fcb7b622df4c8f8c02821` |
| `XingmuTV-0.2.0.exe` | 58,621,440 B | `bb9ccf9e774570637aaf9e3c497eaf405c84b4ec9c245fac94de2d63f3bd75a2` |

**前置依赖**：播放需要系统装 **VLC 3.x**（vlcj 只是 JVM 绑定，不含原生库）。没装也能开窗浏览，播放会提示并跳到设置。

### 验证（本机 Windows）
- `./gradlew cleanTest test --no-build-cache` → **78/78 通过**（含新增更新器 26 项：断网/超时回 Idle 且无 UI 事件、资产选择反例、版本边界）。
- `./gradlew packageMsi packageExe` → 两个安装包；对 fresh app-image 校验：app jar 为
  `xingmu-tv-desktop-0.2.0-*.jar`，含 `com/tvbox/desktop/update/{AssetPicker,UpdateCenter,…}.class` 与 `XingmuTV-version.properties`。
- 更新器对真实 API 冒烟：`releases/latest` 当时 tag `v0.3.4`，桌面资产最高 0.1.0 < 当前 0.2.0 → 判定「已是最新」，行为正确。

### 已知限制（不粉饰）
- **下载 → 打开安装包 → 实际安装**这条链路没有真实更高版本资产可测，标 PENDING_DEVICE。
- **macOS `.dmg` 未构建**：jpackage 需在 macOS 上打；本机只验证 Windows。
- 续播/点播收藏的 GUI 点击交互未逐一实测（数据层由 24 项单测覆盖）。
- 画中画、字幕选择、m3u 频道播放仍在后续候选。
