# QA 验收报告（骨架）

> 状态：**工具链就绪，业务验收未开始**（2026-10-21）
> 范围：构建/静态冒烟/直播源探测已实测；四条基本链路、音画同步、四档屏幕、性能项仍待实现完成后验收。
> 原则：本文件只写实测结果；未验证项必须写清原因，**禁止用推测填表**。
> 关联规格：`docs/AVSYNC_SPEC.md §5`、`docs/UI_SPEC.md §0.5`、`docs/PLAN_ARCHITECTURE.md §12`、`docs/EPISODE_SPEC.md §4`、`docs/SEARCH_SPEC.md §6`、`docs/SOURCE_IMPORT_SPEC.md §6`

## 0. 当前结论

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 构建 `assembleDebug` | **PASS（已实测）** | gradle 退出码 0；`app-debug.apk` 10.72 MB；SHA256 见 §1 |
| 静态冒烟（TV 清单/assets/dex/体积） | **PASS（已实测）** | 14 项检查全过，见 §2.2；JSON 证据 `tools/smoke_report.json` |
| 直播种子可用率 | **快照（已实测）** | 266 频道/332 源；频道可用 244（91.7%），源可用 297（89.5%），受本机网络出口影响，见 §2.3 |
| 四条基本链路 | **未验证** | 需要 APK 安装到 TV/模拟器并完成扫码、搜索、选集、换源操作 |
| 音画同步（AVSYNC §5） | **未验证** | 需要 60 分钟长播、弱网、20 次切集/换源、倍速、直播切台、1GB 盒子实测 |
| 四档屏幕适配（UI_SPEC §0.5） | **未验证** | 需要 1280×720 / 1920×1080 / 3840×2160 / 960×540 截图与列数核对 |
| 性能 A1–A15 | **部分未验证** | 仅 A6（APK 体积）已有实测；其余需设备 `am start -W` / `dumpsys` / soak |

## 1. 环境与产物

| 项 | 值 |
| --- | --- |
| 工程根目录 | `D:\Go Web\tv` |
| JDK | `D:\tvbuild\jdk-17.0.2`（OpenJDK 17.0.2） |
| Gradle | `D:\tvbuild\gradle-8.7\bin\gradle.bat`（8.7） |
| Android SDK | `D:\tvbuild\android-sdk`（build-tools 34.0.0、platform 34/35） |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK 体积 | 11,239,018 bytes = **10.72 MB**（预算 ≤14MB / 硬上限 16MB） |
| APK SHA256 | `740cf980de3b31931f0dc00285938dc13cd5a3075860aba8f96a466b8304ba45` |
| 构建耗时 | 8.5s（36 tasks up-to-date，增量构建） |
| 冒烟结论 | C01–C13 required 全部 PASS；C14 为 INFO |
| 直播探测快照 | `2026-10-21T19:18:57+08:00`，266 频道 / 332 源 |

实测 APK 关键项：

- `package: name='com.tvbox.app'`；`sdkVersion:'21'`；`targetSdkVersion:'34'`
- `leanback-launchable-activity: name='com.tvbox.app.MainActivity'`
- `uses-feature-not-required: name='android.software.leanback'`
- `uses-feature-not-required: name='android.hardware.touchscreen'`
- assets 均打入 APK：`sources.builtin.json` 6187B、`overseas_map.json` 1505B、`pinyin_initials.json` 69331B、`live_seed.json` 406146B
- `classes6.dex` 命中 `Lcom/tvbox/app/MainActivity;`

## 2. 工具链自检（已执行）

### 2.1 `tools/build_apk.ps1`

设计行为：自动定位/设置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`，执行 `gradle assembleDebug --no-daemon`；打印退出码、APK 绝对路径、字节数/MB、SHA256；构建成功但找不到 APK 时以退出码 3 明确报错。

骨架未完成阶段先跑参数校验（当时 `MainActivity.kt` 尚未落盘）：

```powershell
pwsh -NoProfile -File tools/build_apk.ps1 -DryRun
# [OK] 工程根目录: D:\Go Web\tv
# [OK] JAVA_HOME: D:\tvbuild\jdk-17.0.2
# [OK] ANDROID_HOME: D:\tvbuild\android-sdk
# [OK] Gradle: D:\tvbuild\gradle-8.7\bin\gradle.bat
# [WARN] DryRun 模式：仅校验参数，不执行 gradle。
# [INFO] 将执行: "D:\tvbuild\gradle-8.7\bin\gradle.bat" assembleDebug --no-daemon（工作目录: D:\Go Web\tv）
# [INFO] 预期产物: D:\Go Web\tv\app\build\outputs\apk\debug\app-debug.apk
# [OK] 参数校验通过。
# EXIT=0
```

t8 骨架补齐后实际构建：

```powershell
pwsh -NoProfile -File tools/build_apk.ps1 -LogFile tools/build_apk.log
# ...
# BUILD SUCCESSFUL in 8s
# 36 actionable tasks: 36 up-to-date
# [INFO] gradle 退出码: 0（耗时 8.5s）
# ========== BUILD RESULT ==========
# gradle 退出码 : 0
# APK           : D:\Go Web\tv\app\build\outputs\apk\debug\app-debug.apk
# 体积          : 11239018 bytes (10.72 MB)
# SHA256        : 740cf980de3b31931f0dc00285938dc13cd5a3075860aba8f96a466b8304ba45
# 体积判定      : PASS（≤14MB）
# EXIT=0
```

> **19:20–19:21 复测记录（重要）**：t10 正在并行编辑 `app/src/main/java/com/tvbox/app/source/**`，`compileDebugKotlin` 连续报 `SourceSpecParser.kt` / `SourceProbe.kt` / `UrlRules.kt` 的 `Unresolved reference 'SourceKind'`、`when` 非穷尽、`MacCmsClient.kt` 表达式体 return 等错误；构建返回 1，`build_apk.ps1` 按设计打印 `[FAIL] 构建失败：gradle 退出码 1` 并以退出码 2 结束。这属于**源码编辑中途的临时失败，不是工具链问题**，同时验证了脚本的失败路径；待 t10 完成后必须复跑并更新本节。此前 19:18 的成功构建证据仍然有效（当时 t8 骨架的源码可编译）。

### 2.2 `tools/smoke_check.ps1`

设计行为：`aapt2 dump badging` 校验 TV 必需项；检查 APK 体积；用 Zip 条目检查 4 个 assets；扫 `classes*.dex` 字符串表确认 `com.tvbox.app.MainActivity`；缺任何 required 项输出 FAIL 列表并以退出码 1 结束。

```powershell
pwsh -NoProfile -File tools/smoke_check.ps1 -JsonOut tools/smoke_report.json
# C01 PASS APK 存在 11239018 bytes / 10.72 MB
# C02 PASS APK zip 可读 entries=402
# C03 PASS aapt2 dump badging exit=0, lines=26
# C04 PASS applicationId = com.tvbox.app
# C05 PASS LEANBACK_LAUNCHER Activity name='com.tvbox.app.MainActivity'
# C06 PASS uses-feature leanback required=false
# C07 PASS uses-feature touchscreen required=false
# C08 PASS assets/sources.builtin.json 6187 bytes
# C09 PASS assets/overseas_map.json 1505 bytes
# C10 PASS assets/pinyin_initials.json 69331 bytes
# C11 PASS assets/live_seed.json 406146 bytes
# C12 PASS dex 包含 MainActivity classes6.dex 命中 Lcom/tvbox/app/MainActivity;
# C13 PASS APK 体积 10.72 MB <= 14 MB
# C14 INFO SDK 版本信息 sdkVersion:'21'; targetSdkVersion:'34'
# RESULT: PASS（all required checks passed, 0 warning(s)）
# JSON 报告: D:\Go Web\tv\tools\smoke_report.json
# EXIT=0
```

> 说明：首次运行曾在 C06/C07 误报，原因是 `aapt2 badging` 的 feature 行带缩进（`  uses-feature-not-required:`）而正则未允许前导空白；已修正并回归通过。该回归证明脚本能区分 `uses-feature` 与 `uses-feature-not-required`，不会把 required=true 误判为通过。

### 2.3 `tools/verify_sources_live.py`

设计行为：读 `assets/live_seed.json`（缺失时用脚本内置示例），按频道并发探测全部源 URL；判定 HTTP 200 且响应前 2KB 去 BOM/空白后以 `#EXTM3U` 开头；输出源级 + 频道级可用率到 `assets/live_probe_report.json`。支持 `--limit/--workers/--timeout/--channel/--dry-run/--min-rate`。

```bash
PYTHONIOENCODING=utf-8 python tools/verify_sources_live.py --workers 24 --timeout 8
# 种子: D:\Go Web\tv\assets\live_seed.json
# 名称: live_seed_cn  频道: 266  URL: 332
# 并发探测: workers=24, timeout=8.0s, 共 332 个 URL ...
# ========== LIVE PROBE RESULT ==========
# 耗时        : 23.2s
# 频道可用    : 244/266 (91.7%)
# 源可用      : 297/332 (89.5%)
# HTTP 200    : 297/332
# 含 #EXTM3U  : 297/332
# 分组:
#   - local: 频道 196/212 (92.5%), 源 211/227
#   - cctv: 频道 26/28 (92.9%), 源 50/59
#   - satellite: 频道 22/26 (84.6%), 源 36/46
# 失败源示例: http_404 / http_403 / http_567 / timeout
# 报告        : D:\Go Web\tv\assets\live_probe_report.json
# EXIT=0
```

报告摘要（`assets/live_probe_report.json`）：

| 指标 | 值 |
| --- | --- |
| 频道总数 / 可用 | 266 / 244（91.73%） |
| 源总数 / 可用 | 332 / 297（89.46%） |
| 失败源数 | 35 |
| 失败原因分布 | `http_404` 15、`http_567` 9、`http_403` 7、`timeout` 4 |
| 分组频道可用率 | cctv 92.9%、local 92.5%、satellite 84.6% |

> 注意：
> 1. 探测结果受本机网络出口影响，**不代表源永久失效**；复测会上下浮动（首次 90.2% → 修正 URL 编码后 91.7%）。
> 2. 种子里含中文路径（如 `地方台8563/海南.php`），脚本会先做 IDNA/percent-encoding 再请求；未修正前有 14 个 `UnicodeEncodeError` 假失败，现已为 0。
> 3. 只验证「播放列表可取且以 #EXTM3U 开头」，未验证首个分片可播；分片级健康度由 `assets/live_seed.json` 的 `segment_verified` 字段与后续播放验收覆盖。

## 3. 四条基本链路验收

| ID | 链路 | 通过标准 | 验收方法 | 结果 | 证据 | 未验证原因 |
| --- | --- | --- | --- | --- | --- | --- |
| L1 | 扫码 / 配对码导入 | 手机扫码 → 粘贴 JSON → TV 3 秒内收到并导入成功；6 位配对码可完成导入；错误码提示明确；导入页退出后端口关闭、token 一次性失效 | TV 源管理页显示二维码（`http://<ip>:8088/i/<token>`）→ 手机同 WiFi 打开 → 粘贴 `assets/sources.example.json` → 记录 ImportReport；再用配对码重复一次 |  |  |  |
| L2 | 首拼搜索 | `lldq` → 流浪地球、`mcdjj` → 漫长的季节；多音字 `mzdjj` 也命中；首拼本地结果 ≤50ms、首屏 ≤300ms；超时源不阻塞 | 搜索页输入 `lldq` / `mcdjj` / `mzdjj`；录屏 + logcat 时间戳；断一个源验证不阻塞 |  |  |  |
| L3 | 选集切换 | 60 集以上分页、倒序、已看标记；播放中不退出切集 ≤800ms；无黑屏 >1s、无残留音画错位 | 打开 ≥60 集剧集 → 切到第 2 页 → 连切 20 次（含首/末集）；录屏 + 计时 |  |  |  |
| L4 | 跨源换源 | 同片跨源合并；缺集置灰；换源保持集号+位置+倍速；失败自动回旧线路 | 详情页确认「N 源可用」→ 播放中 `MENU` → 线路页签换源；记录集号/进度/倍速；断当前源验证回退 |  |  |  |

> 链路的实现前置：`app/src/main/java/com/tvbox/app/source/**`（t10）、`ui/**`（t4）、`player/**`（t9）。当前 APK 已可安装，但上述交互尚未逐项验收。

## 4. 音画同步验收（`docs/AVSYNC_SPEC.md §5`）

设备矩阵：D1 = Amlogic S905W / 1GB / Android 7.1 / 1080p（主门槛）；D2 = RK3229 / 1GB / Android 5.1 / 720p（下限）；D3 = S905X2 / 2GB / Android 9（回归对照）。

| ID | 验收项 | 通过标准 | 设备/方法 | 结果 | 证据 | 未验证原因 |
| --- | --- | --- | --- | --- | --- | --- |
| S1 | 长播稳定性 | 连续播放 60 分钟，抽样 5 次，音画偏差 ≤80ms | D1；拍手/口型画面人工比对 + 日志时间戳；抽 5 次 |  |  |  |
| S2 | 弱网 | 限速 2Mbps 播放 10 分钟，无持续错位；underrun 能自动恢复 | D1；`tc`/路由器限速；记录 `onAudioUnderrun` 与恢复日志 |  |  |  |
| S3 | 切集/换源 | 连续切 20 次，无残留错位、无黑屏 >1s | D1；连切 20 次（切集 + 换源混合）；录屏逐帧核对 |  |  |  |
| S4 | 倍速 | 0.75×–2× 各播 3 分钟，音调正常、无累积漂移 | D1；5 档逐一播放 3 分钟；人耳 + 口型 |  |  |  |
| S5 | 直播 | 切台 20 次，每次起播 ≤2.5s，无音画错位 | D1；20 个频道轮切；秒表/录屏 + 日志 |  |  |  |
| S6 | 老设备 | 1GB 盒子 1080p 播放 30 分钟，掉帧 ≤10%、无 underrun 连锁 | D2；`dumpsys gfxinfo` + `onDroppedVideoFrames` + 内存 |  |  |  |

> 播放器参数落地后，另需在 `docs/PLAYER_IMPL.md` 标注四项硬要求：音频主时钟、offload 关闭、倍速走 Sonic、切集/换源 `stop→setMediaItem→prepare`。

## 5. 屏幕适配验收（`docs/UI_SPEC.md §0.5` 四档）

| ID | 分辨率 | 期望 widthDp / 列数 | 模式 | 结果 | 证据（截图路径） | 未验证原因 |
| --- | --- | --- | --- | --- | --- | --- |
| U1 | 1280×720 | widthDp 按 density 实测；目标 **5 列** | A（低配可 C） |  |  |  |
| U2 | 1920×1080 | widthDp 按 density 实测；目标 **6 列** | A |  |  |  |
| U3 | 3840×2160 | widthDp 按 density 实测；目标 **6 列**；图片请求尺寸 ×density、`allowHardware` | A |  |  |  |
| U4 | 960×540 | widthDp 按 density 实测；**必须进 C 保命模式**，小屏档 **4 列**（若实测 widthDp≥800 需记录实际档位与理由） | C |  |  |  |

补充硬要求（逐项核对，不单独算四档截图）：

| 编号 | 检查项 | 标准 | 结果 | 证据 | 未验证原因 |
| --- | --- | --- | --- | --- | --- |
| U5 | Hero 高度 | `screenHeightDp × 0.59`，clamp 240–640dp，不写死 640dp |  |  |  |
| U6 | 字号下限 | 辅助 ≥13sp、正文 ≥15sp、列表主标题 ≥17sp；`baseScale` clamp 0.85–1.15、fontScale clamp 0.85–1.3 |  |  |  |
| U7 | 安全区/过扫描 | 默认左右 48dp/上下 27dp；设置页 0% / 2.5% / 5% 三档可调 |  |  |  |
| U8 | 非 16:9 | 行数由可用高度派生；直播页右侧播放器自适应不裁画面 |  |  |  |
| U9 | 540p 以下/小屏 | `smallestScreenWidthDp < 600` 或 540p 及以下直接 C 模式 |  |  |  |

> 截图命名约定建议：`docs/qa/shots/<resolution>-<mode>-home.png`、`...-detail.png`、`...-live.png`；每档至少首页 + 详情 + 直播三张。

## 6. 性能与稳定性（`docs/PLAN_ARCHITECTURE.md §12` A1–A15）

| ID | 验收项 | 通过标准 | 方法 | 结果 | 证据 | 未验证原因 |
| --- | --- | --- | --- | --- | --- | --- |
| A1 | 冷启动首帧 | D1 TotalTime 中位数 ≤2.5s（含首帧） | `am start -W` ×5 |  |  |  |
| A2 | 缓存首屏 | force-stop 后二次启动 ≤2.0s 看到上次内容 | 录屏/帧时间 |  |  |  |
| A3 | 滚动流畅 | 60s 连续滚动掉帧 ≤10%，无 >250ms 帧 | `dumpsys gfxinfo` |  |  |  |
| A4 | 浏览内存 | PSS ≤260MB，Java heap ≤120MB | `dumpsys meminfo` |  |  |  |
| A5 | 播放内存 | PSS ≤340MB，30 分钟无增长 >20MB | soak + `meminfo` |  |  |  |
| A6 | APK | ≤14MB（硬上限 16MB） | `smoke_check.ps1` / `apkanalyzer` | **PASS（10.72MB）** | `tools/smoke_report.json` C13 |  |
| A7 | 点播起播 | ≤2.5s 首帧（20Mbps） | 日志/录屏 |  |  |  |
| A8 | 直播换台 | ≤2.5s 首帧，连按 10 次不卡死 | 手工/脚本 |  |  |  |
| A9 | 弱网/坏源 | 单线路失败自动换源 ≤3s；全部失败有可操作面板，不崩溃 | 断网/改 hosts |  |  |  |
| A10 | 图片失败 | 显示占位与片名，列表可继续滚动 | 断图床 |  |  |  |
| A11 | D-pad | 连按 50 次方向键无焦点丢失/积压；播放返回后焦点还原 | 手工 |  |  |  |
| A12 | 稳定性 | 30 分钟循环无 OOM/ANR/崩溃 | soak |  |  |  |
| A13 | 主线程纪律 | debug StrictMode 无 disk/network violation | StrictMode 日志 |  |  |  |
| A14 | 依赖白名单 | 无 Material3/Leanback/Glide/Room/kotlinx-serialization；Compose/Coil 版本锁定 | `gradle dependencies` |  |  |  |
| A15 | 图片参数 | 列表 320×450、crossfade 关、硬件位图/RGB565 开、缓存上限符合 §5 | 代码检查 + 日志 |  |  |  |

## 7. 未验证项与风险清单

| 编号 | 项 | 原因 / 影响 | 计划 |
| --- | --- | --- | --- |
| R1 | 四条链路全部未验证 | 当前仅有可安装 APK，尚未在设备/模拟器上执行导入、搜索、选集、换源 | t4/t9/t10 完成后按 §3 执行 |
| R2 | 无 1280×720 / 1920×1080 / 3840×2160 / 960×540 截图 | 需要模拟器/实机逐档改分辨率并截图 | 用 AVD 四档 + 真机补 1080p |
| R3 | 无 60 分钟长播 / 弱网 / 倍速 / 切台数据 | 需要 D1/D2 设备与可播源 | 按 §4 逐项执行，记录日志时间戳 |
| R4 | 直播源可用率是单次快照 | 受出口网络、源站限流影响；本次 91.7% 不代表长期 SLA | 验收前用同一脚本复测并保留 JSON；失败源按 priority 准备备源 |
| R5 | 探测只到播放列表层 | 未验证首个分片是否可播（`#EXTINF` 后的 TS/fMP4） | 播放链路用实际起播验证；必要时给脚本加 `--deep` |
| R6 | 性能 A1–A5、A7–A15 未测 | 依赖设备与完整 UI/播放器 | 按 `PLAN_ARCHITECTURE §12` 执行 |
| R7 | 19:20 构建因 t10 编辑中途失败 | `SourceSpecParser.kt`/`UrlRules.kt` 缺 `com.tvbox.app.data.SourceKind` import；非工具链问题 | t10 完成后立即复跑 `build_apk.ps1` 并更新 §2.1 |

## 8. 复现命令

```powershell
# 1) 构建（参数校验 / 实际构建）
pwsh -NoProfile -File tools/build_apk.ps1 -DryRun
pwsh -NoProfile -File tools/build_apk.ps1 -LogFile tools/build_apk.log

# 2) 静态冒烟（可指定 APK；缺项会列出 FAIL 并以退出码 1 结束）
pwsh -NoProfile -File tools/smoke_check.ps1 -JsonOut tools/smoke_report.json
pwsh -NoProfile -File tools/smoke_check.ps1 -ApkPath app/build/outputs/apk/debug/app-debug.apk -DryRun

# 3) 直播源探测（全量 / 抽样 / 只看某分组）
$env:PYTHONIOENCODING='utf-8'
python tools/verify_sources_live.py --workers 24 --timeout 8
python tools/verify_sources_live.py --limit 10 --dry-run
python tools/verify_sources_live.py --channel cctv --workers 16
```

## 9. 产物与证据索引

| 文件 | 内容 |
| --- | --- |
| `tools/build_apk.ps1` | 一键构建脚本（dry-run / 实际构建 / 日志 / 体积 / SHA256） |
| `tools/smoke_check.ps1` | 静态冒烟脚本（TV 清单 / assets / dex / 体积 / JSON 报告） |
| `tools/verify_sources_live.py` | 直播源并发探测脚本（内置示例兜底） |
| `tools/smoke_report.json` | 本次冒烟结构化结果（C01–C14） |
| `tools/build_apk.log` | 最近一次 gradle 构建日志（19:21 复测：t10 编辑中途编译失败；19:18 成功构建输出见 §2.1） |
| `assets/live_probe_report.json` | 本次 266 频道 / 332 源探测明细与汇总 |
| `docs/QA_REPORT.md` | 本报告（后续每轮验收在此追加结果与证据） |

## 10. 签署

| 角色 | 姓名/成员 | 日期 | 结论 |
| --- | --- | --- | --- |
| QA | qa | 2026-10-21 | 工具链就绪；业务验收待执行 |
| Captain |  |  |  |
| Engineer |  |  |  |
