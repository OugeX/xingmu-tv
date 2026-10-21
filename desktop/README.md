# 星幕 TV · 桌面端（Windows / macOS）

Compose Multiplatform Desktop（Kotlin/JVM）+ libVLC（vlcj）实现的 PC 客户端。与 Android 端共用
`assets/` 内置数据与 `tvapp.source.v1.1` 源协议，但 UI 按桌面习惯重做为三栏式（左导航 / 中内容 / 右信息），
不复用 TV 焦点模型。

![直播播放](../screenshots/desktop_t15_live.png)

| 点播列表 | 点播播放 |
| --- | --- |
| ![点播](../screenshots/desktop_t15_vod_list.png) | ![点播播放](../screenshots/desktop_t15_vod_play.png) |

| 源管理 | 全屏直播 |
| --- | --- |
| ![源管理](../screenshots/desktop_t15_sources.png) | ![全屏](../screenshots/desktop_t15_fullscreen.png) |

## 快速开始

环境要求：**JDK 17**、**Gradle 8.7**（工程自带 wrapper）、**VLC 3.x**（libVLC）。

```bash
cd desktop

# 开发运行（自动向上查找仓库 assets/）
./gradlew run

# 指定 VLC 位置（不设置时自动检测常见安装目录）
TVAPP_VLC_HOME=/Applications/VLC.app/Contents/MacOS ./gradlew run

# 非交互自检：内置源 → 直播种子 → MacCMS list/detail → libVLC 实播直播 + 点播
./gradlew run --args="--smoke"

# 打 Windows 安装包（Compose 插件会自动下载 WiX 3；也可用本机 WiX 放到 PATH）
./gradlew packageMsi
# 产物：build/compose/binaries/main/msi/XingmuTV-0.1.0.msi
./gradlew packageExe
# 产物：build/compose/binaries/main/exe/XingmuTV-0.1.0.exe

# macOS（必须在 macOS 上执行）
./gradlew packageDmg
```

Windows 上可以用辅助脚本（自动定位本机 JDK / Gradle / WiX / VLC）：

```powershell
pwsh -NoProfile -File tools/run-dev.ps1
pwsh -NoProfile -File tools/package-windows.ps1
```

## 已实现（v0.1）

### 工程
- 独立 Gradle 工程 `desktop/`：Gradle 8.7 + Kotlin 2.0.21 + Compose Multiplatform 1.7.3 + vlcj 4.8.2
- 产物配置：Windows `.msi` / `.exe`，macOS `.dmg`（jpackage；mac 包需在 macOS 或 CI matrix 上打）
- 不修改仓库根构建脚本、`app/` 或任何 Android 代码

### 数据层（纯 JVM，不依赖 Android）
- `SourceSpecParser`：解析/校验 `tvapp.source.v1 / v1.1`，错误码与 `tools/validate_sources.py` 对齐
  （`E_JSON / E_SCHEMA / E_ID / E_URL / E_FIELD / E_EPG / E_ADBLOCK / E_CHANNEL / E_REGEX / E_NESTED`，
  下载失败额外用 `E_NETWORK` 提示）
- MacCMS `ac=list` 分类树、`ac=detail&t=` 列表、`ac=detail&ids=` 详情、`wd=` 搜索；多端点降级（`baseUrl → fallbackBaseUrls`）；
  源未配置 `categories` 时用 `ac=list` 返回的中文分类名自动映射 MOVIE/TV/ANIME/VARIETY
- SWR 文件缓存：列表 5 分钟 / 详情 30 分钟；网络失败回退过期缓存（stale）
- 多源聚合搜索：并发查询所有启用的 MacCMS 源，同名 + 年份合并，记录可用源数
- 直播种子：解析 `assets/live_seed.json`（266 频道 / 3 分组），线路按实测清晰度排序，`fallback_only` 置底
- UA / Referer 与 Android 端一致（`Http.DEFAULT_UA` / `Http.DEFAULT_REFERER`）
- 内置数据相对引用仓库 `assets/sources.builtin.json` 与 `assets/live_seed.json`，源码树不复制；
  构建时仅把这两份文件复制进 jar 作为**打包后的兜底**（`stageBuiltinAssets`）

### 播放
- libVLC 播 HLS 直播与 mp4/m3u8 点播，支持暂停/seek/音量/倍速（0.5×–2×）/全屏
- **1080p 黑屏降级**：每条线路按「硬解 → 软解（`:avcodec-hw=none`）→ 更低清晰度线路」自动重试；
  轮询 `state/time/videoOutputs` 判定解码错误、连接超时、无视频输出（黑屏）
- 右侧面板显示当前尝试的完整降级阶梯，失败切换时给出可读提示
- 画中画（PiP）列为 v0.2 计划

### 界面
- 三栏式：左侧导航 / 中部列表或播放器 / 右侧详情与线路、剧集
- 暗色 A 影院主题，与 Android 一致：`#0A0A0C` 背景 + `#E8B872` 金色强调
- 键盘快捷键（AWT 级分发，避免 AWT Canvas 抢焦点）：
  `空格` 暂停/继续、`←/→` 快退/快进 10s、`↑/↓` 音量 ±5%、`F` 全屏、`Esc` 退出全屏/停止播放
- 源管理：本地 JSON 文件 / 粘贴文本 / URL 导入；导入失败展示错误码；源可启停、测试连通、删除用户源
- 设置：libVLC 路径与检测、画质上限、硬件解码开关、默认音量、缓存清理、配置/缓存目录

## PC 客户端依赖 libVLC

播放依赖系统安装的 libVLC 3.x（vlcj 4.8.2 只是 JVM 绑定，**不包含原生库**）。

| 平台 | 获取方式 | 默认检测位置 |
| --- | --- | --- |
| Windows | 安装 [VLC 3.0.x 64 位](https://www.videolan.org/vlc/) 桌面版；或解压 portable zip | `%ProgramFiles%\VideoLAN\VLC`、`%ProgramFiles(x86)%\VideoLAN\VLC`、`%LOCALAPPDATA%\Programs\VideoLAN\VLC`、`PATH` |
| macOS | 把 `VLC.app` 放入 `/Applications`（[下载](https://www.videolan.org/vlc/)） | `/Applications/VLC.app/Contents/MacOS/lib`、Homebrew `/opt/homebrew/lib`、`/usr/local/lib` |
| Linux（开发） | `sudo apt install libvlc-dev vlc-plugin-base` | `/usr/lib/x86_64-linux-gnu`、`/usr/lib`、`/usr/local/lib` |

找不到原生库时：

1. 应用不会崩溃，窗口与列表仍可用；播放按钮会提示「未找到 libVLC」并跳到设置。
2. 在 **设置 → libVLC 播放器** 里填写 VLC 安装目录（或 `libvlc.dll` / `VLC.app` 路径），点「保存并检测」。
3. 或设置环境变量 `TVAPP_VLC_HOME`（也支持 `VLC_HOME`、`-Dtvapp.vlc.home`）。
4. 已安装 VLC 的机器通常无需任何配置。

> v0.1 的 MSI/DMG **不内置** libVLC 原生库（体积 + 许可证考虑）。如果要随包分发，把 VLC 安装目录
> 整个复制到应用可执行文件旁的 `vlc/` 子目录即可命中检测（`user.dir/vlc`）；或者引导用户安装 VLC。

## 目录

```
desktop/
├── build.gradle.kts / settings.gradle.kts   # 独立工程，不污染 Android 构建
├── gradle/wrapper + gradlew(.bat)
├── src/main/kotlin/com/tvbox/desktop/
│   ├── Main.kt               # 入口 / CLI（--smoke、--autoplay-live、--vlc、--assets）
│   ├── data/                 # 源协议、MacCMS、直播种子、SWR 缓存、聚合、路径
│   ├── player/               # libVLC 定位、播放控制器、降级阶梯
│   ├── ui/                   # 三栏界面、播放器、源管理、设置
│   └── smoke/SmokeRunner.kt  # 非交互验收
└── src/test/kotlin/...       # 28 个单元测试（解析/校验/聚合/降级顺序）
```

## 验证

```bash
cd desktop
./gradlew test
./gradlew run --args="--smoke --vlc <VLC目录>"
```

自检结果写到仓库根 `.artifacts/desktop/smoke.json`，包含内置源数量、频道数量、
MacCMS 列表/详情、VLC 版本以及直播/点播实播状态（时间轴前进即视为播放成功）。

本机实测（Windows 10 + VLC 3.0.21 + NVIDIA D3D11VA）：

- `./gradlew test`：28/28 通过
- `./gradlew run --args="--smoke"`（仅设 `TVAPP_VLC_HOME`，验证自动检测）：全部检查通过；
  `ac=list` 返回 49 个分类，直播 CCTV-1 综合 1080p 实播（时间轴前进到 39.8s），点播《魅惑之人》HD 实播
- `./gradlew run --autoplay-live`：窗口正常；直播 CCTV-1（1080p 硬解 1920×1080）播放；
  点播《企鹅妈妈 第二季》第 1 集（bfzym3u8，1080p）播放，seekbar 正常
- 键盘：`F` 进出全屏、`空格` 暂停均实测生效（AWT KeyEventDispatcher 兜底，Canvas 抢焦点也不影响）
- `./gradlew packageMsi`：`build/compose/binaries/main/msi/XingmuTV-0.1.0.msi`
  （57,760,138 bytes；SHA256 `11de3e945f343203d7f8d413f51aac200138a0a9b29cdce2744c89b442542758`）
- `./gradlew packageExe`：`build/compose/binaries/main/exe/XingmuTV-0.1.0.exe`（58,420,736 bytes）
- `./gradlew createDistributable` 的 app-image 也实测启动：`jar:/live_seed.json` 兜底生效，
  `TVAPP_VLC_HOME` 检测到 libVLC 并播放直播

运行日志：`.artifacts/desktop/gui-run*.log`、`smoke-final.log`、`package-final.log`；
自检 JSON：`.artifacts/desktop/smoke.json`。

## 已知限制

- macOS 产物（`packageDmg`）需要在 macOS 上构建，本机仅验证了 Windows。
- 画中画、字幕选择、播放进度记忆为 v0.2 候选。
- m3u 源目前只做协议校验与展示（v0.1 直播使用 `live_seed.json`）；m3u 频道播放排在 v0.2。
- 直播时移取决于源是否支持，UI 对直播只提供 ±10s。
