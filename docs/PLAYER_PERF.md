# 播放器性能规格（起播 / 切台 / 切集 / 换源）

目标设备：LeTV 超4 X50（MStar 6A938，2.87GB，Android 6 / API 23，1080p 屏，**单硬件解码器**）。
一切优化围绕一个前提：**不重建播放器、不重建 Surface、不重复解码**。

## 1. 性能预算（不达标算未交付）

| 场景 | 目标 | 上限 |
| --- | --- | --- |
| 冷启动 → 首帧（点播） | ≤2.0s | 3.0s |
| 直播首次起播 | ≤1.5s | 2.5s |
| 切台 · 热切（焦点停留 ≥250ms，命中预热） | ≤1.0s | 1.5s |
| 切台 · 冷切（无停留直接 CH+ 连切） | ≤1.5s | 2.5s |
| 切台（跨 CDN / 4K→1080p 降档） | ≤2.0s | 3.0s |
| 切集（同源下一集） | ≤0.8s | 1.2s |
| 换源（同片不同站） | ≤1.5s | 2.5s |
| 二次缓冲率（30 分钟直播） | ≤1 次 | 3 次 |
| 播放态 PSS | ≤340MB | 420MB |
| 播放态掉帧 | ≤10% | 20% |


### 1.1 CDN 分档（2026-10-21 实测补充）

切台首帧的瓶颈在**源的分片 TTFB**，不在播放器。按实测把频道分两档：

| 档 | 判据（实测 ttfb / 首帧） | 切台目标 | 上限 |
| --- | --- | --- | --- |
| 快 CDN | 120.198.x / 124.228.x / cgtn 等，约 1.0–1.1s | 热切 ≤1.0s | 1.5s |
| 慢 CDN | `74.91.26.218:82` 等，分片 TTFB 2–3s | 热切 ≤1.5s | **2.5s** |

- 数据侧已按实测 `ttfb_ms` 给安全源排序（快的在前），但 CCTV-1 等 12 个频道的 ≤720 源只有慢 CDN，无法靠排序解决。
- 因此验收数字必须**按 host 分组报**，禁止把慢 CDN 的 2.3s 混进整体 P90 当播放器缺陷。
- rebuffer 统计必须**按频道/媒体项重置**后单频道跑 10 分钟，跨频道的自动换源失败不能计入。

## 2. 单实例 + 复用（最关键的架构约束）

1. **全应用只有一个 `ExoPlayer` 实例**，贯穿直播/点播/听歌/短视频；`release()` 只在 Activity `onDestroy` 调用。
2. **SurfaceView 只创建一次**：切台/切集/换源只 `setMediaItem` + `prepare()`，绝不重建 `PlayerView` 或 `Surface`。
3. 切换前先 `stop()` 再 `setMediaItem`，避免旧时间轴残留（这条同时是音画同步的要求）。
4. **禁止 dual-player 方案**：MStar 6A938 只有一个硬解实例，开双解码会导致第二个直接软解 → 掉帧。切台快靠"预热 + 快速 flush"，不靠"预开第二个播放器"。
5. `MediaCodec` 复用：切换时不要 `release` renderer；只在分辨率/编码跨越档位（如 4K→1080p）时才允许重配 codec。

## 3. 切台/切集怎么做到 1 秒内

| 手段 | 做法 |
| --- | --- |
| **列表预热** | 频道列表焦点停留 ≥300ms → 后台 `OkHttp` GET 目标频道 m3u8（只取 playlist 头，不 prepare、不解码），塞进 OkHttp 缓存 |
| **DNS/TLS 预热** | 首次进入直播页，对当前分组前 10 个频道 host 做 `InetAddress.getAllByName` 预热 + 复用同一 `OkHttpClient`（连接池 keep-alive） |
| **防抖 + 抢先** | DPAD 移动 800ms 防抖；一旦确认落点，立刻 `stop()→setMediaItem→prepare()`，不等 UI 焦点动画结束 |
| **低缓冲快起** | 直播：`minBuffer 3s / maxBuffer 15s / bufferForPlayback 1.0s / afterRebuffer 2.5s`；点播：`15s/30s/2.0s/4.0s`（比原参数更激进，起播更快） |
| **首片优先** | `setLoadControl` 下让首个分片抢占带宽；切换瞬间暂停列表分页请求、暂停封面加载（`Coil` 请求延后 500ms） |
| **降档兜底** | 起播 5s 未出画面 → 并行走 `tier` 次优源（1080p→720p），谁先出画面用谁，另一个 `cancel` |
| **失败快退** | 单源 4s 无数据即判失败，不等到 30s 超时 |

## 4. UI 与线程纪律（低端机最容易被这些拖死）

1. **播放页不跑任何列表**：进入播放即释放/暂停列表的 Coil 请求（`ImageLoader.memoryCache` 不动，但 `pauseRequests()`）。
2. **切台遮罩用静态文字**（"正在切换…"），不用动画、不用模糊、不用渐变 —— 老 GPU 合成一次就是 30ms。
3. **HUD 显隐不做全屏重绘**：只改 alpha，不加 `blur`/`shadow`。
4. 解析（m3u8 / JSON）全部在 IO 线程；主线程只接收 `StateFlow` 的状态。
5. 切台期间禁止刷新 EPG/节目单，等首帧渲染后再异步补。
6. 掉帧监测用 `AnalyticsListener.onDroppedVideoFrames`，只在超过阈值时回调 UI，不做每帧回调。

## 5. 自适应降级（按实测，不靠猜）

| 触发条件 | 动作 |
| --- | --- |
| 起播 > 5s | 并行试次优源（保留清晰度记录） |
| 30s 内 rebuffer ≥2 次 | 降到 720p 并提示「网络不稳，已切 720p」 |
| 掉帧 > 20%（30s 窗口） | 降一档分辨率，提示原因 |
| `onAudioUnderrun` 30s ≥3 次 | 提高音频缓冲一档，仍失败则降档 |
| 连续 3 次切换失败 | 该源标记 `degraded`，冷却 10 分钟不再首选 |
| 播放态 PSS > 400MB | 清 Coil 磁盘/内存缓存 + 释放 backBuffer |

## 6. 埋点（必须落，否则没法验收）

每个动作打一行结构化日志（`TvLog`）：

```
PLAYER_EVT action=zap from=CCTV1 to=CCTV5 t_prepare_ms=812 t_first_frame_ms=1103 src=1080p rebuffer=0
PLAYER_EVT action=episode from=S01E04 to=S01E05 t_prepare_ms=520 t_first_frame_ms=744
PLAYER_EVT action=switchsrc from=bfzy1 to=ffzy t_prepare_ms=1310 t_first_frame_ms=1680 pos_ms=612000
```

验收脚本（qa 用）：
```bash
adb logcat -c && adb shell am start -n com.xingmu.tv/.MainActivity
# 用遥控器/输入事件连切 10 个台，再 grep
adb logcat -d | grep PLAYER_EVT
```
统计 `t_first_frame_ms` 的 P50/P90，必须满足 §1 预算。

## 7. 明确不做（避免过度设计）

1. 不做双播放器抢流（硬件单解码，必炸）。
2. 不做播放中预解码下一集（内存不够，且 MStar 单解码器冲突）。
3. 不做动态码率自适应（源基本是单码率媒体列表，master 多档极少）。
4. 不在播放页做任何"漂亮"的转场动画。
