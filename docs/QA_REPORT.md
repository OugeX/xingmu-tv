# QA 验收报告 — 星幕 Android TV

> 执行：qa · 2026-10-21 20:17–20:57（Asia/Shanghai）
> 设备：LeTV X4-50（X4_50，Android 6.0 / API 23，物理 1920×1080 / density 240，1GB 档）
> 构建：JDK 17.0.2 / Gradle 8.7 / Android SDK build-tools 34.0.0
> 原则：只写实测；无法验证的项明确写「未验证」+ 原因，禁止推测。

## 0. 结论

| 项 | 结论 | 说明 |
| --- | --- | --- |
| `assembleDebug` | **PASS** | exit 0；APK 12,620,999 B = **12.04 MB**（≤14MB） |
| 静态冒烟 | **PASS** | C01–C13 PASS、C14 INFO；TV 清单/assets/dex 全部通过 |
| lint | **PASS（冻结包 v0.2.0）** | 冻结候选复测：`BUILD SUCCESSFUL`，**0 Error / 34 Warning / 0 Fatal**。t5 旧包曾 11 Error，已由后续修复合入 |
| 四条基本链路 | **FAIL / 未完成** | 扫码/配对码导入 UI 仍是占位；首拼本地命中通过；选集切换、跨源换源未取得播放态证据 |
| 音画同步（AVSYNC §5） | **未验证（部分）** | 只完成直播切台的部分实测；60 分钟长播、弱网、倍速、20 次切集/换源、1GB 30 分钟均未执行 |
| 性能 | **部分 PASS / 待补** | 冻结包 10 分钟播放 PSS 78.1MB、掉帧 4.71%（PASS）；A 模式 30s 滚动掉帧未取得样本；旧包 82.18% 是 C 误判样本 |
| 屏幕适配（§0.5 四档） | **FAIL** | 1080p 分类 6 列通过；720p 与 4K 被错误强制 C 模式（2 列，应 5/6 列）；960×540 C 模式侧栏裁剪；4K 截图受物理 1080p 面板限制 |
| 播放器性能（t13 冻结包） | **FAIL / 未完成** | 旧包数字：点播首帧 2.941s、直播首启 3.825s、切台 P90 2.856s、切集 1.335s、PSS 72.8MB、掉帧最大 36%；冻结包 c90a 仅完成 R2/R4/R5 抽查，R2 720 搜索 6 列、960 侧栏裁剪，R3/R6/R1 未完成，未做同 SHA 性能复测 |

**总体：v0.2.1 的 R2/R3/深链/lint 复测已 PASS；VOD 首帧 4.711s 超 3.0s 上限，仍不可签字。** v0.2.0 的 720p 列数/960 侧栏问题已在 v0.2.1 修复；仍需处理 VOD 取流慢（v.fengbao14.com 冷启 4711ms）与上一轮切台 3.499s、rebuffer=6/10min。

## 1. 环境与产物

| 项 | 值 |
| --- | --- |
| 工程 | `D:\Go Web\tv` |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 体积 | 12,620,999 B（12.04 MB） |
| SHA256 | `37d7fe0eec55f1b167c019e4e47e1feaf6f2a15c3b02025acc997aa783c0c151` |
| 构建时间 | 2026-10-21 20:49（qa 构建）/ 20:52（工作区被并发重建，现文件 SHA 如上） |
| package | `com.tvbox.app`，versionName 0.1.0，minSdk 21 / targetSdk 34 |
| 安装 | `adb install -r` 成功，`lastUpdateTime=2026-10-21 20:57` |

构建命令与结果：

```powershell
pwsh -NoProfile -File tools/build_apk.ps1 -LogFile tools/build_apk_t5.log
# BUILD SUCCESSFUL in 6.7s
# gradle 退出码 : 0
# APK           : D:\Go Web\tv\app\build\outputs\apk\debug\app-debug.apk
# 体积          : 12620999 bytes (12.04 MB)
# 体积判定      : PASS（≤14MB）
```

静态冒烟：

```powershell
pwsh -NoProfile -File tools/smoke_check.ps1 -JsonOut tools/smoke_report_t5.json
# C01-C13 PASS, C14 INFO
# C11 assets/live_seed.json 459782 bytes
# C12 classes4.dex 命中 Lcom/tvbox/app/MainActivity;
# RESULT: PASS（all required checks passed, 0 warning(s)）
# EXIT=0
```

> 说明：当前工作区在 QA 期间仍被其它实现线并发修改/重建（20:52 的 APK SHA 与 20:49 构建不同）。**构建/静态冒烟**来自 20:57 安装的当前 APK（SHA 37d7…）；**UI/性能/屏幕数据**来自 20:50 安装的 12.04MB 相邻构建（SHA 026a…，源码 20:47 版）。两者差异未触及本报告结论，但严格签字前应在同一冻结 APK 上复测。

## 2. 构建与 lint

### 2.1 构建

- `assembleDebug --no-daemon` exit 0，36/36 tasks 成功。
- APK 12.04MB ≤14MB，PASS。
- `aapt2 dump badging`：`leanback-launchable-activity=com.tvbox.app.MainActivity`；`uses-feature-not-required`：leanback / touchscreen；`sdkVersion=21`、`targetSdkVersion=34`。

### 2.2 lint（FAIL）

```powershell
$env:JAVA_HOME='D:\tvbuild\jdk-17.0.2'
gradle.bat lintDebug --no-daemon
# Lint found 11 errors, 34 warnings. First failure:
# search/VoiceAsr.kt:56 MissingPermission
# BUILD FAILED in 31s
# EXIT=1
```

| 严重度 | 数量 | 结论 |
| --- | --- | --- |
| Fatal | **0** | 满足「致命项 0」的字面要求 |
| Error | **11** | `lintDebug` 失败；不满足干净构建门槛 |
| Warning | 34 | 含 DefaultLocale / OldTargetApi / GradleDependency 等 |

11 个 Error（逐条，见 `app/build/reports/lint-results-debug.txt`）：

| 文件:行 | 问题 |
| --- | --- |
| `search/VoiceAsr.kt:56` | MissingPermission（录音权限未检查） |
| `search/VoiceAsr.kt:42` | NewApi：`checkSelfPermission` API 23（min 21） |
| `casual/CasualRadioService.kt:108` | NewApi：`Notification.Action.Builder()` API 23 |
| `casual/CasualRadioService.kt:109` | NewApi：`Icon#createWithResource` API 23 |
| `player/PlayerAudioPipeline.kt:98` | NewApi：`setPreferredDevice` API 23 |
| `player/PlayerAudioPipeline.kt:107` | NewApi：`setOffloadMode` API 29 |
| `player/PlayerAudioPipeline.kt:110` | NewApi：`setOffloadDelayPadding` API 29 |
| `import/UsbImportScanner.kt:130` | NewApi：`StorageVolume` API 24 |
| `player/PlayerTuning.kt:70/71/72` | UnsafeOptInUsageError：Media3 `AspectRatioFrameLayout.RESIZE_MODE_*` 未标注 `@UnstableApi` |

风险：minSdk=21 而多处直接调用 API 23/24/29。API 21/22 设备存在运行时崩溃风险；当前验证机为 API 23，无法覆盖 21/22。

## 3. 四条基本链路

| ID | 链路 | 结果 | 证据 | 说明 / 未验证原因 |
| --- | --- | --- | --- | --- |
| L1 | 扫码 / 配对码导入 | **FAIL** | `.artifacts/t5_deeplink_sources.png`；`ui/source/SourceManageScreen.kt:207-214` | 源管理页底部「二维码/配对码/本地文件/U 盘/遥控器键盘」仍是占位按钮，点击只提示「等 t10 的导入服务注册后自动可用」。t10 的 `ImportService` 未接入 UI，用户无法完成扫码/配对码导入。t10 的 `ImportSelfCheck 55/55` 是后端自检，不等于端到端链路。 |
| L2 | 首拼搜索（lldq / mcdjj） | **部分 PASS** | `.artifacts/t5_search_lldq_final.png`、`.artifacts/t5_search_mcdjj.png` | `lldq` 命中「流浪地球 / 流浪地球2」，`mcdjj` 命中「漫长的季节」；本地首拼索引工作正常。但「已加载 0」：MacCMS 聚合搜索未返回结果（服务端 `wd=` 实测 total=0），搜索链路只有本地候选，未验证在线结果/分页/超时不阻塞。首拼耗时未打点，无法证明 ≤50ms。 |
| L3 | 选集切换 | **未验证（UI 部分）** | `.artifacts/t5_detail_episodes.png`、`.artifacts/t5_detail_89664b.png` | 详情页已展示「共 523 集」「1–30/31–60/…」「倒序」，分页/倒序 UI 可见；但未能进入播放态完成实际切集，未测得 ≤800ms、黑屏 <1s。 |
| L4 | 跨源换源 | **未验证** | `.artifacts/t5_detail_89664b.png` 顶部「切换源（bfzy1）」 | 未取得同片多源样本；详情页只显示 bfzy1 一个源，未观察到「N 源可用」与换源面板。无法验证保持集号/进度/倍速、缺集置灰、失败回旧线路。 |

## 4. 音画同步（AVSYNC_SPEC §5）

| ID | 项 | 门槛 | 设备/方法 | 结果 | 证据 / 未验证原因 |
| --- | --- | --- | --- | --- | --- |
| S1 | 长播稳定性 | 60 分钟抽 5 次偏差 ≤80ms | D1，拍手/口型 + 时间戳 | **未验证** | 未执行 60 分钟长播；无音频延迟采集工具，人工比对需现场 |
| S2 | 弱网 | 2Mbps 10 分钟无持续错位，underrun 自恢复 | D1，限速 | **未验证** | 未搭建 2Mbps 限速；本次未触发 underrun |
| S3 | 切集/换源 | 连续 20 次无残留错位、黑屏 <1s | D1 | **未验证** | 播放态切集/换源未打通；UI 自动化在详情页多次退出到系统桌面 |
| S4 | 倍速 | 0.75×–2× 各 3 分钟无累积漂移 | D1 | **未验证** | 未进入可控播放态完成 5 档倍速 |
| S5 | 直播切台 | 20 次，每次起播 ≤2.5s，无音画错位 | D1，CH+ 轮切 | **部分 FAIL** | 两轮 CH+：第一轮记录 15 次，首帧 505/664/1958/450/656/1250/596/452/1938/1268/1231/689/797/158/1561ms，全部 ≤1958ms、rebuffer=0；第二轮仅记录 7 次，含 **3589ms** 一次超 2.5s。按键中有丢失/应用被带离直播页，未取得 20 次连续可信样本。证据 `.artifacts/t5_live_zap.log`、`.artifacts/t5_live_zap2.log` |
| S6 | 老设备 | 1GB 盒子 1080p 30 分钟，掉帧 ≤10%、无 underrun 连锁 | D1/D2 | **未验证** | 未做 30 分钟 soak；本机 PSS/掉帧另见 §5，但不能替代该 soak |

## 5. 性能

| ID | 项 | 门槛 | 结果 | 证据 / 说明 |
| --- | --- | --- | --- | --- |
| P1 | 冷启动 TotalTime | ≤1.5s | **PASS（median）** | 20:50 安装包 4 个有效样本：872 / 992 / 1000 / 998ms，median 995ms。一个样本 `TotalTime=0`（Activity 已在栈顶，判定无效）。另在 20:18 包测得 862/972/3276/1225/1151ms，出现过 3276ms 离群值。 |
| P2 | 缓存首屏 | ≤2.0s | **未验证** | 只测 `am start -W` 的 Activity TotalTime；无「看到上次内容」的帧级测量 |
| P3 | 浏览 PSS | ≤260MB | **PASS** | 20:50 安装包首页加载后 69,920 kB ≈ **68.3MB**；另一次 71,077 kB ≈69.4MB。远低于 260MB |
| P4 | 播放 PSS | ≤340MB，30 分钟无增长 | **未验证** | 未做 30 分钟播放 soak |
| P5 | 滚动掉帧 | 60s 连续滚动 ≤10% | **FAIL（样本）** | 20:18 安装包（11.87MB）首页 80 次 DPAD_DOWN：101 帧 / 83 janky = **82.18%**，90th 57ms、95th 89ms、99th 105ms。20:50 包重复 20 次 DOWN 即退出到系统桌面，未能取得 60s 干净样本；因此这是「明确存在严重掉帧」的失败证据，而非完整 60s 结论 |
| P6 | APK | ≤14MB | **PASS** | 12.04MB |
| P7 | lint Fatal | 0 | **PASS（字面）** | 0 Fatal；但 11 Error 使 `lintDebug` 失败（见 §2.2） |
| P8 | D-pad 焦点 | 连按 50 次无丢失/积压；返回还原 | **FAIL** | 分类页连续 20–80 次 DOWN 后前台从 `com.tvbox.app` 变为 `com.dsh.tvlauncher` / `com.stv.deskplatform`，应用退出；详情页 RIGHT/UP 亦多次退到系统桌面。焦点边界行为阻断自动化与可用性 |
| P9 | 其它 A7–A15 | 见 PLAN_ARCHITECTURE §12 | **未验证** | 起播/弱网/图片失败/StrictMode/依赖白名单/图片参数未逐项执行 |

## 6. 屏幕适配（UI_SPEC §0.5 四档）

测试方法：`wm size` / `wm density` 覆盖物理面板，force-stop 后冷启动同名页面，截图 + 列数人工核对。物理面板仍为 1920×1080，`wm size` 只改变逻辑分辨率，`screencap` 输出仍是 1920×1080，因此 4K 档不是真实 4K 输出。

| ID | 分辨率 / density | 期望 | 实测列数/模式 | 结果 | 截图 |
| --- | --- | --- | --- | --- | --- |
| U1 | 1920×1080 / 240 | widthDp=1280 → **6 列 A 模式** | 分类网格 **6 列**、A 模式 | **PASS** | `.artifacts/t5_category_1920.png`、`.artifacts/t5_final_1080_home.png` |
| U2 | 1280×720 / 213 | widthDp≈961 → **5 列 A 模式** | **C 保命模式、2 列**；侧栏底项裁剪 | **FAIL** | `.artifacts/t5_final_720_category.png` |
| U3 | 3840×2160 / 640 | widthDp=960 → 应与 1080p 同布局 **6 列 A** | **C 保命模式、2 列**；侧栏/内容裁剪；截图仍 1920×1080 | **FAIL / 4K 未真实验证** | `.artifacts/t5_final_4k_category.png` |
| U4 | 960×540 / 160 | 540p 及以下 → **强制 C**；§0.5 小屏档 4 列 | **C 模式、2 列**；侧栏底项（播放源/搜索）裁剪 | **部分 PASS（C 强制）/ FAIL（列数与裁剪）** | `.artifacts/t5_final_540_category.png`、`.artifacts/t5_960x540_160_home.png` |

根因定位：`app/src/main/java/com/tvbox/app/ui/UiUtils.kt:89`

```kotlin
return config.smallestScreenWidthDp < 600 || config.screenHeightDp < 600
```

- 720p 在 TV density 213 下 `heightDp=540`，被误判为 C；4K 在 density 640 下同样 `heightDp=540`，被误判为 C。
- UI_SPEC §0.5 明确 4K 应与 1080p 相同布局（6 列），720p 为 5 列 A 模式；当前实现违反。
- 960×540 强制 C 符合「540p 及以下」条款，但 §0.5 的列数表与 C 模式「双列」存在冲突，需要规格统一。
- 720p/540p/4K C 模式侧栏 10 个导航项超出可视高度，底部「播放源/搜索/设置」被裁，未见滚动。

## 7. 播放器性能（t13 真机实测）

版本说明（重要）：
- **最新验收对象：`release/xingmu-tv-v0.2.1-09fad209.apk`**，SHA `09fad209e8882ee60e2f70cad0531354da51d85938df6f3202989e68d2ccaea9`，12,885,704 B，commit `029dd6e`，qa 于 22:52 安装；本轮 R2/R3/深链/lint/VOD 首帧见 §7.5。
- 上一冻结件：`release/xingmu-tv-v0.2.0-c90af3a3.apk`，SHA `c90af3a34aee…`，12,951,505 B，commit `e2d3f48`，21:33 安装；其 PSS/掉帧/切台数据保留在 §7.1–7.3。
- 早期候选 223a 与 12951505 已被 captain 判定作废；
- **7.1–7.3 的性能数字仍来自 21:18–21:20 的旧包（12,620,999 B，SHA 37d7…）**；冻结包 c90a 完成了 PSS/掉帧/切台，未完成 VOD/切集；v0.2.1 只补 R2/R3/深链/lint/VOD 首帧。
- 设备：LeTV X4-50 / API 23 / 1920×1080 / 单硬解；日志来源 `tools/player_live_events_t13.log`、`tools/player_vod_events_t13.log`、`tools/player_episode_events_t13.log`、`tools/v021_dlink_play.log`。

### 7.1 汇总（PLAYER_PERF §1）

| 场景 | n | P50 | P90 | Max | 预算 target / upper | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 点播首帧（旧包，参考） | 1 | 2941ms | 2941ms | **2941ms** | 2000 / 3000 | **FAIL(target，未超 upper)**；冻结包 c90a 未取得 VOD 事件 |
| 直播首次起播（旧包，参考） | 1 | 3825ms | 3825ms | **3825ms** | 1500 / 2500 | **FAIL(over upper)**；冻结包未取得首次起播事件 |
| 切台（CH+，冻结包 c90a） | 5 | 2515ms | 3499ms | **3499ms** | 1000 / 1500 | **FAIL(over upper)** |
| 切集（旧包 index=2，参考） | 1 | 1335ms | 1335ms | **1335ms** | 800 / 1200 | **FAIL(over upper)**；冻结包未取得切集事件 |
| 换源 | 0 | — | — | — | 1500 / 2500 | **N/A：PLAYER_EVT 无 action=switchsrc** |
| 二次缓冲（冻结包 10 分钟直播） | 6 次 | — | — | — | ≤1 / 3（30min） | **FAIL**：10 分钟已 6 次，远超 1 次/30min |
| 播放态 PSS（冻结包 10 分钟） | 11/11 | 74.8MB 均值 | — | **78.1MB** | ≤340 / 420 | **PASS** |
| 播放态掉帧（冻结包 10 分钟） | 11/11 | 0.61% 均值 | — | **4.71%** | ≤10% / 20% | **PASS**（此前 36% 是旧包/C 误判） |

### 7.2 明细

**点播首帧**：
```
PLAYER_EVT action=play label=20240621 url=...20240621/index.m3u8
  t_prepare_ms=2941 t_first_frame_ms=2941 src=720p rebuffer=0
```
- 首帧 2.941s，超 2.0s 目标、逼近 3.0s 上限；prepare 与首帧同值，说明时间全部花在 `prepare`（建连/取 playlist/解码器初始化），不是 UI 渲染。
- 同源另两次点播样本为 2.142s、0.583s（网络/缓存波动大），本次冻结包取 2.941s 作为保守值。

**直播首次起播**：
```
PLAYER_EVT action=play label=CCTV-1 综合 线路4 · 720p
  t_prepare_ms=3825 t_first_frame_ms=3825 src=1080p rebuffer=0
PLAYER_EVT action=zap from=- to=CCTV-1 综合
  t_first_frame_ms=3854 src=auto
```
- 3.825s，超 2.5s 上限；首次起播包含探测/线路选择/解码器首配，是当前最大瓶颈。

**切台（CH+ 10 次，日志记录 7 次）**：
`699 / 304 / 51 / 655 / 2522 / 1889 / 2856 ms`
- P50 699ms、P90 2856ms、max 2856ms；7 次中 3 次超 1.5s 上限（2522/1889/2856）。
- 3 次按键未产生事件（切换防抖/按键丢失），未取得完整 10 次连续样本；`rebuffer=0`，`degraded=0`。

**切集**：
```
PLAYER_EVT action=episode index=2 from=bfzym3u8 source=bfzy1
PLAYER_EVT action=play label=20240622 ... t_prepare_ms=1335 t_first_frame_ms=1335 src=720p
```
- 1.335s，超 1.2s 上限 135ms；属于「prepare 慢 + 缓冲重建」而非解码降级（无 degraded）。
- 连续 5 集自动化只稳定拿到 1 集事件（MENU 面板在切集后退出播放页），样本不足。

**换源**：
- `PLAYER_EVT` 只有 `action=play`/`action=zap`/`action=episode`，**没有 `action=switchsrc`**；无法分离换源耗时。t9 的 `PLAYER_IMPL.md` 也说明换源由 `action=play` 输出，无法区分普通起播。结论：**埋点缺失，换源无法测**。

**二次缓冲（冻结包 c90a，10 分钟直播）**：
- `PLAYER_EVT` 每条 `rebuffer=6`（截止采样前累计），10 分钟窗口已 6 次，远超「≤1 次/30 分钟」；且这 6 次发生在未持续换台的播放期，属于**真实二次缓冲超标**，不是仅换台抖动。
- 旧包同一字段 rebuffer=1～6；两包都未达到 ≤1 次/30min 预算。

**切台（冻结包 c90a，CH+ 10 次，日志记录 5 次）**：
`2651 / 3499 / 2515 / 2054 / 2675 ms`；prepare 2646/3496/2512/2053/2670ms。
- P50 2515ms、P90 3499ms、max 3499ms；全部超 1.5s 上限；`degraded=0`，`rebuffer=6`。
- 5 次按键未产生事件（防抖/按键丢失）；切台耗时主要是 prepare（与首帧同值），属于取流/首片慢，不是解码降级。

**PSS / 掉帧（冻结包 c90a，10 分钟，`tools/measure_pss.ps1`）**：

| t_sec | PSS MB | frames | janky | jank % | 前台 |
| --- | --- | --- | --- | --- | --- |
| 0 | 66.2 | 1 | 0 | 0.00% | yes |
| 61 | 64.5 | 85 | 4 | 4.71% | yes |
| 121 | 70.8 | 925 | 5 | 0.54% | yes |
| 182 | 76.8 | 2125 | 5 | 0.24% | yes |
| 242 | 77.3 | 3604 | 8 | 0.22% | yes |
| 303 | 77.7 | 5120 | 9 | 0.18% | yes |
| 363 | 77.8 | 6635 | 11 | 0.17% | yes |
| 424 | 78.0 | 8152 | 13 | 0.16% | yes |
| 485 | 78.0 | 9667 | 14 | 0.14% | yes |
| 545 | 78.1 | 11183 | 17 | 0.15% | yes |
| 606 | 78.1 | 12698 | 19 | 0.15% | yes |

- PSS 均值 74.8MB、最大 78.1MB，**PASS**（≤340MB）。
- 播放掉帧均值 0.61%、最大 4.71%，**PASS**（≤10%）；此前 36% 是旧包 + C 模式误判样本，冻结包已明显改善。
- 11/11 采样点 app 在前台，10 分钟连续；数据可信。
- 注意：这是**播放态** gfxinfo；A 模式 **滚动列表**掉帧尚未在冻结包单独重测（旧 82.18% 是 C 误判导致，需补 30s 滚动样本）。

### 7.3 超预算环节与建议

| 超预算项 | 环节 | 证据 | 可执行建议 |
| --- | --- | --- | --- |
| 直播首次起播 3.825s（旧包） | **prepare 慢 / 首次建连+首片** | `t_prepare_ms=3825`，与首帧同值；CCTV-1 线路4 1080p | 首屏预热当前分组前 10 个 host（已有 DNS 预热则检查是否在首帧前完成）；首片超时从 4s 降至 2.5s 并行次优源；把 `bufferForPlayback` 在首次起播降到 0.5s |
| 切台 P90 3.499s（冻结包） | **prepare 慢 / 取流+首片** | 2651/3499/2515/2054/2675ms，`degraded=0` | 切台锁定预取线路的 playlist 缓存；对连续 3 次慢源标记冷却；CH+ 800ms 防抖会吞按键；把首片超时从 4s 降至 2.5s 并行次优源 |
| 点播首帧 2.941s（旧包） | **prepare 慢** | prepare=first=2941ms | 详情页点击播放时并行预取首片；把 `DefaultLoadControl` 首次 `bufferForPlayback` 从 2.0s 降到 1.0s；确认硬解初始化为 36ms 不是瓶颈（日志） |
| 切集 1.335s（旧包） | **缓冲重建** | prepare=first=1335ms，无 degraded | 切集保留旧播放器、只 `stop→setMediaItem→prepare`，不要重建 Surface；预取下一集 playlist 头；目标 0.8s 先在 `onPrepare` 前下载首片 |
| 二次缓冲 6 次/10min（冻结包） | **取流不稳 / 缓冲重建** | 每条 PLAYER_EVT `rebuffer=6` | 查 6 次 rebuffer 是否集中在换台后的首片；对同 host 增加连接池/DNS 预热；把直播 `bufferForPlayback` 从 1.0s 提到 1.5s 观察 underrun 与起播的折中 |
| 换源无法测 | **埋点缺失** | 无 `action=switchsrc` | t9 在 `PlayerControllerImpl.switchSource` 输出 `action=switchsrc from/to/t_prepare_ms/t_first_frame_ms/pos_ms`；否则该预算项无法验收 |
| A 模式滚动掉帧未测 | **待复测** | 旧 82.18% 为 C 误判样本；冻结包只测播放态 0.61%/4.71% | 冻结包 A 模式下 30s 连续 DOWN，`dumpsys gfxinfo reset` 后统计 jank；目标 ≤10% |

### 7.4 冻结包回归（captain 追加 R2–R6）

| 项 | 结论 | 证据 |
| --- | --- | --- |
| R2 1280×720 A 模式 / 5 列 | **部分 FAIL**：冻结包 A 模式已生效（不再误判 C），但搜索页实测 **6 列卡片**（应为 5）；720 侧栏底项（播放源/搜索/设置）被裁剪，分类页因源离线空态未能复核列数 | `.artifacts/t13_frozen_720_dpi240_q.png`、`.artifacts/t13_frozen_720_search2.png` |
| R2 1920×1080 6 列 | **PASS**：冻结包搜索页 6 列、侧栏 10 项可见 | `.artifacts/t13_frozen_1080_search_q.png` |
| R2 960×540 C + 侧栏不裁剪 | **FAIL**：冻结包 960×540(dpi240) 出现 C 风格红色高亮但侧栏仍裁剪（播放源/搜索/设置不可见）；dpi160 时反而进入 A 模式（C 判定仅看 widthDp<800，未覆盖 540p） | `.artifacts/t13_frozen_540_dpi240.png`、`.artifacts/t13_frozen_540_search.png` |
| R3 各页 25–30 次 DOWN 不退出 | **部分 PASS**：搜索 30 次、首页 30 次、分类 30 次、源管理 25 次、详情 30 次后均在 `com.tvbox.app`；设置页未取得独立样本 | `.artifacts/t13_r3_search_30down.png`、`t13_r3_home.png`、`t13_r3_category.png`、`t13_r3_sources.png`、`t13_r3_detail_30down.png` |
| R4 lintDebug 0 Error | **PASS（21:22 源码）**：`BUILD SUCCESSFUL`，0 Error / 34 Warning（0 Fatal）；冻结件 commit e2d3f48 与 lint 源码是否完全一致未能复核 | `tools/lint_t13_final.log`、`app/build/reports/lint-results-debug.xml` |
| R5 lldq 只走本地 + 本地命中 N 条 | **PASS（搜索页文案）**：输入 `q` 显示「本地首拼命中 6 条 · 中文关键词走服务端」，不再出现「已加载 0」；lldq 未单独截图 | `.artifacts/t13_frozen_720_dpi240_q.png` |
| R6 选集长列表选到 index≈300 | **PASS（实测 index=309）**：20×DOWN+2×RIGHT 到 index=183；34×DOWN+2×RIGHT+OK 到 **index=309**，画面切到该集（播放器截图），`action=episode index=309 from=bfzym3u8 source=bfzy1` | `.artifacts/t5_r6_episode3.png`、`tools/t13_r6_episode3.log` |
| R1 源导入回归（t17） | **部分 PASS**：扫码/配对码入口实测成功——TV 显示 `http://192.168.1.9:8088` + 配对码 647870，host 调用 `/api/pair` 后用 `/api/import` 提交 `assets/sources.example.json`，TV 显示「✓ 已导入 4 个源」、写入 4 条；本地文件/遥控器键盘入口未完成实测 | `.artifacts/t13_r1_qr.png`、`.artifacts/t13_r1_qr_success.png` |

### 7.5 v0.2.1 冻结包复测（本轮 5 项）

被测：`release/xingmu-tv-v0.2.1-09fad209.apk` / `09fad209…` / 12,885,704 B / commit `029dd6e`；`adb install -r` 成功。

| 项 | 结果 | 证据 |
| --- | --- | --- |
| R2 1280×720@240 搜索 | **PASS：5 列**（`--es screen search --es q 爱`，33 结果，5 卡片/行） | `design/mockups/r2_720_search.png` |
| R2 960×540 | **PASS：C 保命模式，侧栏「推荐…设置/搜索」全部可见不裁剪** | `design/mockups/r2_540_sidebar.png` |
| R2 1920×1080 | **PASS：6 列**（搜索 25 结果，6 卡片/行） | `design/mockups/r2_1080_6col.png` |
| R3 首页 30×DOWN / 设置 25×DOWN | **PASS**：`mResumedActivity` 均为 `com.tvbox.app/.MainActivity` | `design/mockups/r3_settings_25down.png` |
| 深链 detail 169023 → ENTER | **PASS**：《老江湖》详情加载，播放按钮焦点；ENTER 起播，`PLAYER_EVT action=play label=HD`，播放页画面已出 | `design/mockups/dlink_play.png`，`tools/v021_dlink_play.log` |
| lint | **PASS：0 Error / 34 Warning / 0 Fatal**，`BUILD SUCCESSFUL`，exit 0 | `tools/lint_v021.log`、`app/build/reports/lint-results-debug.xml` |
| VOD 首帧 | **FAIL(over upper)**：`t_prepare_ms=4711 t_first_frame_ms=4711`，src=804p，host=`v.fengbao14.com`，cached=0；预算 2.0/3.0s，4.711s 超 3.0s 上限 | `tools/v021_dlink_play.log` |

- 深链日志命中 `TVBox/DetailDeepLink ... name=老江湖`；播放事件 `action=play label=HD url=https://v.fengbao14.com/video/laojianghu/... t_prepare_ms=4711 t_first_frame_ms=4711 src=804p rebuffer=0 host=v.fengbao14.com cached=0`。
- 本项 VOD 首帧与 §7.1 的旧包 2.941s 不同：v0.2.1 该源 `v.fengbao14.com` 冷取首片耗时 4.711s，属 **prepare/取流慢**，不是解码降级（`degraded` 未出现，src=804p）。
- 未在本轮重跑：切台 host 分组、换源、切集、10 分钟 PSS/掉帧、AVSYNC；沿用上一轮与 §7.1–7.3 的结论/待测状态。
- 设备日志未见 `Force stopping com.tvbox.app` 外部抢占；本轮 5 项均可复现。

## 8. 风险与未验证清单

| ID | 严重度 | 问题 | 影响 | 建议 |
| --- | --- | --- | --- | --- |
| R1 | **blocker** | 扫码/配对码导入 UI 未接线（仍占位） | 核心功能「自定义播放源导入」用户不可用 | t4 接入 `ImportService` 的 QR/配对码/文件/U 盘入口并做端到端链路 |
| R2 | **blocker** | 720p/4K 被错误强制 C 模式 | §0.5 硬要求不满足；低端 720p 与大屏 4K 体验错误 | 修正 `shouldForceLiteMode`：按 `smallestScreenWidthDp<600` 或实际 540p 判定，不能只看 `screenHeightDp<600` |
| R3 | **high** | 连续 DPAD_DOWN 退出应用 | 遥控器可用性/焦点边界失败，阻塞自动化 | 修 `FocusRequester`/`focusProperties` 边界，列表末项不越界到系统 |
| R4 | **high** | lint 11 Error（API 21/22 风险） | 构建门槛不干净；API 21/22 可能崩溃 | 加 `@RequiresApi`/`SDK_INT` 守卫；Media3 `@OptIn(UnstableApi::class)`；录音权限检查 |
| R5 | **high** | 掉帧样本 82.18%（101 帧） | 低端盒子滚动体验不达标 | 用最终包复测 60s gfxinfo；检查图片解码/组合/焦点重绘 |
| R6 | medium | 4K 截图受物理 1080p 面板限制 | 不能声明 4K 实机截图 | 用 4K AVD/真机复测并输出 3840×2160 截图 |
| R7 | medium | 搜索聚合返回 0 | 在线搜索链路未真正验证 | 修 MacCMS `wd` 参数/端点；复测首屏 ≤300ms、超时源不阻塞 |
| R8 | medium | 60 分钟长播、2Mbps 弱网、倍速、20 次切集/换源、1GB 30 分钟 soak 未执行 | AVSYNC §5 不能签字 | 在 D1/D2 上按 §4 逐项执行 |
| R9 | low | 并发构建导致 APK SHA 变化（20:49 026a… → 20:52 37d7…） | 验收对象可能漂移 | 冻结 commit/构建产物后再签字 |

## 9. 复现命令

```powershell
# 构建
pwsh -NoProfile -File tools/build_apk.ps1 -LogFile tools/build_apk_t5.log

# 静态冒烟
pwsh -NoProfile -File tools/smoke_check.ps1 -JsonOut tools/smoke_report_t5.json

# lint
$env:JAVA_HOME='D:\tvbuild\jdk-17.0.2'
D:\tvbuild\gradle-8.7\bin\gradle.bat lintDebug --no-daemon

# 安装与冷启动
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.tvbox.app
adb shell am start -W -n com.tvbox.app/.MainActivity

# 直达页面（QA 用）
adb shell am start -S -W --activity-clear-task -n com.tvbox.app/.MainActivity --es screen search
adb shell am start -S -W --activity-clear-task -n com.tvbox.app/.MainActivity --es screen sources
adb shell am start -S -W --activity-clear-task -n com.tvbox.app/.MainActivity --es screen live
adb shell am start -S -W --activity-clear-task -n com.tvbox.app/.MainActivity --es screen category --es typeId 20 --es title 电影

# 屏幕适配
adb shell wm size 1280x720; adb shell wm density 213
adb shell wm size 960x540;  adb shell wm density 160
adb shell wm size 3840x2160; adb shell wm density 640
adb shell wm size reset;    adb shell wm density reset

# 掉帧/内存
adb shell dumpsys gfxinfo com.tvbox.app reset
adb shell dumpsys gfxinfo com.tvbox.app
adb shell dumpsys meminfo com.tvbox.app
```

## 10. 证据索引

| 文件 | 内容 |
| --- | --- |
| `.artifacts/t5_final_1080_home.png` / `t5_category_1920.png` | 1080p 首页 / 分类 6 列 |
| `.artifacts/t5_final_720_category.png` | 720p 被强制 C 模式（2 列） |
| `.artifacts/t5_final_540_category.png` / `t5_960x540_160_home.png` | 960×540 C 模式与侧栏裁剪 |
| `.artifacts/t5_final_4k_category.png` | 4K 逻辑分辨率下被强制 C 模式 |
| `.artifacts/t5_deeplink_sources.png` | 源管理页导入按钮占位 |
| `.artifacts/t5_search_lldq_final.png` / `t5_search_mcdjj.png` | 首拼搜索结果 |
| `.artifacts/t5_detail_89664b.png` / `t5_detail_episodes.png` | 523 集详情与分页 |
| `.artifacts/t5_live_zap.log` / `t5_live_zap2.log` | 直播切台 PLAYER_EVT |
| `tools/measure_player.ps1` / `tools/player_metrics.json` | 播放器 PLAYER_EVT 解析、P50/P90/max 与预算比对 |
| `tools/measure_pss.ps1` / `tools/player_pss_trend.csv` / `tools/player_pss_trend.json` | 10 分钟 PSS/掉帧趋势（旧包） |
| `tools/player_pss_frozen.csv` / `tools/player_pss_frozen.json` | 冻结包 c90a 10 分钟 PSS/掉帧（PSS 78.1MB、jank 4.71%） |
| `tools/player_live_events_t13.log` / `player_vod_events_t13.log` / `player_episode_events_t13.log` | t13 原始 PLAYER_EVT |
| `.artifacts/t13_frozen_720_dpi240_q.png` / `t13_frozen_1080_search_q.png` / `t13_frozen_540_dpi240.png` | 冻结包 R2 三档截图 |
| `tools/smoke_report_t5.json` | 静态冒烟 C01–C14 |
| `tools/lint_t5_final.log` / `app/build/reports/lint-results-debug.txt` | lint 11 Error 明细 |
| `assets/live_probe_report.json` | 直播种子可用率快照（91.7% 频道 / 89.5% 源，t11） |

## 11. 签署

| 角色 | 成员 | 日期 | 结论 |
| --- | --- | --- | --- |
| QA | qa | 2026-10-21 | **不通过**：导入 UI 未接线；720p/4K 模式错误；lint 11 Error；DPAD 退出；AVSYNC 未完成 |
| Captain |  |  |  |
