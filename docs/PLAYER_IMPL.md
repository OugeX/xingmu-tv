# 播放器 / 直播调用路径（t9 实现）

`PlayerControllerImpl` 是唯一 ExoPlayer 单实例（点播/直播共用）；`LiveController` 只做频道/线路/EPG 调度；UI 用 `VideoSurface` + `PlaybackHud` / `LiveScreen`（t4）消费。行号对应当前源码。

## 1. 播放器创建（单实例、SurfaceView 直出、全屏）

| 步骤 | 落点 |
| --- | --- |
| 创建/注册 | `player/PlayerBootstrap.kt:26` → `PlayerControllerImpl(context)`，懒创建 ExoPlayer |
| ExoPlayer.Builder | `PlayerControllerImpl.kt:332 ensurePlayer()`：TvRenderersFactory / SwitchingLoadControl / HlsFirstMediaSourceFactory / DefaultTrackSelector |
| 渲染与 seek | 同上 `:353` `SeekParameters.CLOSEST_SYNC`；`:365` `setSkipSilenceEnabled(false)`；`:367` 关帧率匹配 |
| 音画硬开关 | `PlayerAudioPipeline.kt:152 buildAudioSink()`：`setMinPcmBufferDurationUs(400_000)` `:158`、`setEnableFloatOutput(false)` `:165`、`setEnableAudioTrackPlaybackParams(false)` `:167`、无 offload provider（=关） |
| 缓冲参数 | `PlayerTuning.kt:56` VOD 15/30/12MB（起播 2.0s/重缓 4.0s）、`:62` LIVE 3/15/8MB（1.0s/2.5s，PLAYER_PERF §3）；`SwitchingLoadControl.kt:57` 运行期切档不重建 |
| 输出/全屏 | `VideoSurface.kt:32`：`PlayerView` 进程内只创建一次（`PlayerSurfaceCache`），`setUseController(false)`、SurfaceView；`FullscreenEffect()` 只改 Window（API23 flags / API30 InsetsController）。`PlayerBootstrap` 页面离开只 pause，Activity onDestroy 才 `release()`（单实例硬约束） |
| 轨道上限 | `PlayerControllerImpl.kt:388 buildTrackParameters()`：`:391` 1080p、`:392` 30fps、`:393` 6Mbps、`:394` H.264、`:395-396` 禁混合 mime/非无缝自适应 |

## 2. 切集 / 换源（点播）

1. `PlayerController.play(MediaUrl, startPositionMs)` → `PlayerControllerImpl.kt:131`。
2. `:141` 倍速回 1.0 → `:143` 注入该集 headers → `:145-148` **stop → setMediaItem → prepare → playWhenReady**（禁止旧时间轴换源；直播 start=0 时走 default position = live edge）。
3. 首帧由 `AnalyticsListener.onRenderedFirstFrame` 驱动（`:609`），耗时进 `stats.firstFrameMs`。
4. 换源：错误/8s 无数据发出 `PlayerEvent.NeedsFallback`；调用方 `play(alternateUrl, positionMs)` 续播。解码器失败自动重建一次（`:755` → `:777`）。
5. 事件/参数源：`PlayerEvents.kt` 的 `PlayerEventSource` / `PlaybackInfoSource` / `PlayerProfileControl` / `PlayerLiveOps`（冻结契约未改）。

## 3. 直播切台（LiveController）

| 行为 | 落点 |
| --- | --- |
| 加载频道 | `LiveController.kt:99 start()` → `LiveSeedRepository.load()`（assets/live_seed.json，IO 一次解析并缓存；266 频道/332 源） |
| DPAD 预览（800ms 防抖） | `:173 preview()`；`:635 SWITCH_DEBOUNCE_MS=800`；列表焦点变化由 UI 调用 |
| 立即切台 | `:264 playChannel()` → `:289 playLine()`；CH+/CH- 在 `:535 handleKey()` 消费 |
| 数字键直达 | `:535 handleKey()` → `:608 resolveNumber()`（1.5s 自动确认，台号=分组顺序全量编号） |
| 失败降级 | `:370 handleFailure()`：按 priority 最多 3 条（间隔 600ms）；全失败 `:389 onChannelFailed()` → `:414 findNextPlayable()` 最多跳 3 台，不黑屏 |
| 浮层 | `LiveOverlayPanel.kt`：LEFT/MENU 呼出半透明频道浮层（视频不暂停/不缩放），右侧 `ChannelInfoCard`（台标占位/分组/实时分辨率码率编码/备用线路数/延迟/简介/EPG） |
| 切台延迟/记忆 | `onFirstFrame()` 记录 `now-switchingStartedAt`，成功持久化 `last_channel_id` |

## 3.5 清晰度 / 4K 选源（LIVE_QUALITY）

- 排序：`LiveSeedParser.parseLines()` 按 `tier`（4K>1080p>720p>576p>480p>≤360p）→ `priority` → `fallback_only` 最后；`tier=error/无视频轨` 永不自动选。
- 4K：`DeviceCapabilities` 启动探测 H.264/HEVC 3840×2160@25；`LiveController.candidates()` 默认跳过 4K，`setPrefer4K(true)` 且设备支持才放开，轨道上限随之 2160（`PlayerControllerImpl.setQuality`）。
- 高清专区：`live_hd.json` 114 频道标记 `hdZone`，`buildGroups()` 在最前插入「高清专区」分组。
- 起播兜底：高清源 5s 无首帧 → `startSlowStartFallback()` 预热下一档并切 720p；同源连续失败 3 次冷却 10 分钟；HUD 显示 `measuredText`（1080p · 5.9Mbps · 硬解 H.264）。

## 4. 倍速 / Seek / DVR

- 5 档 `PlayerTuning.SPEEDS`；`PlayerControllerImpl.kt:189 setSpeed()` 走 `PlaybackParameters(speed, pitch=1f)`（Sonic），直播直接拒绝 + 提示；AudioTrack playbackParams 已关。
- 点播 `seekTo` `:178`，`CLOSEST_SYNC` `:353`；HUD/长按：`PlaybackHud.kt:48 SeekKeyRepeater`（单击 ±10s，按住 400ms 后每 200ms 累加 10s）。
- 直播 DVR：`seekBack()` `:304` / `seekToLiveEdge()` `:293`；窗口=HLS duration，<5s 视为不可回看，超窗提示「该频道不支持回看」。

## 5. EPG（XMLTV / gzip，离线缓存）

- 入口：`LiveController.kt:431 setEpgUrls()` → `:442 refreshEpg()`（进入拉一次 + 每 30 分钟；不阻塞起播）。
- 拉取：`EpgRepository.kt:52 refresh()`：≤3 URL、失败静默保留旧数据、落 `files/epg/epg_cache.txt`（API21 无 `putIfAbsent`，已手动判断）。
- 解析：`EpgParser.kt:33 parse()`：按 `1f 8b` 判 gzip、解压 ≤8MB、XmlPullParser 流式、只留 now±2h。
- 展示：`EpgIndex.nowNext()` `EpgParser.kt:167` → `EpgNowNext.text`；匹配 `tvg_id → id → 台名`，无匹配固定「暂无节目单」。

## 6. 自愈与校准（AVSYNC_SPEC §4）

| 触发 | 动作 | 落点 |
| --- | --- | --- |
| 30s 内 `onAudioUnderrun` ≥3 | 提缓冲一档；再触发降 720p 并提示 | `PlayerControllerImpl.kt:696` |
| 30s 掉帧 >20% | 自动 720p + `AutoDowngrade` | `:730` |
| 错误 / 8s 无数据 | `NeedsFallback` 请求换源；看门狗绑定当前源，首帧即 cancel 重启；首帧后只用“位置 6s 无进度”，不再用 bufferingSince 判定（避免真机误降级） | `:508`、`:760` |
| 视频格式突变 | seek 重同步一次（5s 冷却） | `:668` |
| 音画校准 ±500ms | `setAudioDelayMs` `:217` → `CalibrationAudioSink.getCurrentPositionUs()` 时钟偏移（不动流） | `PlayerAudioPipeline.kt:43` |
| 30s rebuffer ≥2 | 降 720p + 提示「网络不稳」 | `PlayerControllerImpl.kt` BUFFERING 分支 |

## 7. TLS / 性能埋点

- `PlayerHttp.playbackClient(context)`（`PlayerNetwork.kt`）复用 `data/AppImages.networkClient` 的“系统根 + 随包 ISRG Root X1/X2”组合 TrustManager；播放 HLS 与 EPG 都走它，否则 Android 6 播 Let's Encrypt CDN 会 `SSLHandshakeException → Source error`。
- 预热：频道焦点 300ms → `warmClient` GET playlist（8MB Cache）；进直播页对首 10 频道 host 做 DNS 预热；`BandwidthGate.zapping` 供 t4 暂停 Coil/分页。
- 埋点：`PLAYER_EVT action=zap from=… to=… t_prepare_ms=… t_first_frame_ms=… src=1080p rebuffer=0`（`LiveController.onFirstFrame`）；切集/换源由 `PlayerControllerImpl` 输出 `action=play`。

## 8. 接口与边界

- 新增公开入口：`PlayerBootstrap`、`VideoSurface`、`PlaybackHud`、`PlaybackFullscreen`、`SeekKeyRepeater`、`LiveController`、`LiveChannelOverlay/ChannelInfoCard`、`EpgRepository`。
- t4 接入：`PlayerBootstrap.createAndRegister` → `VideoSurface`（自动全屏）+ HUD/浮层 → `PlayerBootstrap.release`；直播 `LiveController(context, controller).start()`。
- 未接 VOD `SimpleCache`（任务必做项未要求）；`AudioTrack` 运行时提缓冲=loadControl 档位，彻底改 AudioSink 需重建（仅解码器错误重建）。
- 真机证据（LeTV X4-50/API23）：`screenshots/t9_live10.png`、`.artifacts/shot_live2.png`（直播全屏实画面，首帧 1.45–2.4s，720p 硬解 OMX.MS.AVC）、`screenshots/t9_now.png`（播放页全屏 HUD）。`screencap` 对硬件视频层常返回黑屏，以首帧日志 + 实拍截图为准。
