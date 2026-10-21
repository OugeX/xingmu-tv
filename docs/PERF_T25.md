# t25 · 播放性能 P0/P1（切台预连接 / 播放态让位 / 解码器会话复用）

> 状态：**代码完成，本地 `assembleDebug` + `lintDebug`（0 Error）通过；真机数字 PENDING_DEVICE（电视已关机，adb/WoL 均唤不醒）。**
> 本文件不写任何未经真机验证的加速数字；下表「预期」来自 `docs/PERF_ROADMAP.md` 的预算，不是实测。
>
> 本地已验证：`assembleDebug` 通过（APK 13.0MB）、`lintDebug` 0 Error / 35 Warning、`tools/codec_report.ps1` 用合成日志自测通过、`tools/measure_player.ps1 -DryRun` 通过。
> `tools/measure_player.ps1` 已增加 `decoder_init_ms` / `decoder_inits` / `codec_reuse` 三个字段的解析（旧包缺字段时为 $null，不影响原有统计）。

## 1. 切台预连接（PERF_ROADMAP P0-2）

### 改了什么

| 文件 | 改动 |
| --- | --- |
| `player/PlayerNetwork.kt` | 新增 `PlayerHttp.DnsCache`（10 分钟进程级 DNS 缓存，实现 `okhttp3.Dns`）；新增 `PlayerHttp.preconnect(context, url)`：先解析 DNS，再对目标 URL 发 `HEAD` 完成 TCP+TLS 握手并归还连接池（CDN 拒绝 HEAD 时退回 `Range: bytes=0-0` 的 GET 并读完 1 字节，保证连接真的回到池里）。 |
| `player/PlayerNetwork.kt` | **修了一个让预连接失效的真 bug**：`playbackClient(context)` 以前每次都 `AppImages.networkClient(context).newBuilder()`，而 `AppImages.networkClient` 每次都会重建 OkHttpClient → 每次都是**新的 ConnectionPool**，预热出来的连接根本不会被播放器复用。现在 `playbackClient` 进程内单例 + `.dns(DnsCache)`。 |
| `live/LiveController.kt` | 焦点停留 250ms 的 warm 任务里，除原有 playlist 预取外并行做 `preconnect`；新增 `preconnectNeighbors()` 预连接 CH+/CH-（只握手、不拉 playlist，避免抢当前台带宽）；`prefetchNextPlaylist()` 顺带给下一个频道 host 预握手；`preconnectedHosts` 去重，避免同 host 反复握手。 |

### 预期收益

- 省掉每次切台的 DNS 查询（Android 6 的 `InetAddress` 缓存很小）+ 1–2 个 RTT 的 TLS 握手。
- 目标：慢 CDN 2300ms → ≤1900ms；快 CDN 584ms → ≤450ms（真机待测）。

### 为什么只预连接「首片目标 host」而不是整条 m3u8

playlist 预取走的是 Media3 `SimpleCache` 数据源（另一套 OkHttp 实例语义），起播首片才用 `playbackClient`。所以**必须**用 `playbackClient` 做一次真实握手，池子里才有可复用的连接。

## 2. 播放态资源让位（PERF_ROADMAP P1-7）

### 改了什么

| 文件 | 改动 |
| --- | --- |
| `data/BudgetedMemoryCache.kt`（新增） | Coil 2.7 的 `MemoryCache.maxSize` 只能在构建时定，这里包一层委托缓存，用 `capBytes` 做**运行期软上限**：写入时按 LRU 头淘汰、支持 `trimToCap()`。 |
| `data/AppImages.kt` | ImageLoader 的内存缓存改用 `BudgetedMemoryCache`（默认仍 24MB），新增 `setMemoryBudget(capBytes)`。 |
| `player/PlaybackMemoryGuard.kt`（新增） | `enter()`：上限压到 **8MB** 并立即裁掉超出部分（详情页 1080p 大图最先被释放），同时 `pauseImages = true`；`exit()`：恢复 24MB + 放开图片请求。计数用 depth，直播页/播放页切换时重叠 enter/exit 不会误恢复。 |
| `ui/player/PlayerScreen.kt` | `DisposableEffect` 里 `PlaybackMemoryGuard.enter(context)` / `onDispose { exit() }`。 |
| `live/LiveController.kt` | `start()` → `enter()`，`release()` → `exit()`（直播页同样算播放态）。 |
| `ui/components/PosterComponents.kt` | 海报请求条件加 `!PlaybackMemoryGuard.pauseImages`（Coil 2.7 没有全局 `pauseRequests`，只能在请求入口读开关，和现有 `ContentGate.zapping` 同一套做法）。 |

### 预期收益

- 播放 PSS 74.8MB → **≤65MB**（省掉 8–16MB 位图）。内存越紧，MStar 上硬解码器 + AudioTrack 的余量越大，减少「播一会儿被系统杀」。
- 图片请求在播放态直接不发，省流量也省 CPU（低端盒子图片解码是主线程外的真实开销）。

## 3. 解码器会话复用（PERF_ROADMAP P2-9）

### 结论先行：Media3 1.5.1 没有「跨 MediaItem 保留 MediaCodec 会话」的公共 API

- 每次切台走 `stop() → setMediaItem() → prepare()`，`MediaCodecRenderer.onDisabled()` 必然 `releaseCodec()`，新 prepare 再 `createByCodecName` + `configure` + `start`，这就是真机 `decoder=OMX.MS.AVC.Decoder init=55–68ms` 的来源。
- `MediaCodecAdapter.Factory` 只能替换创建方式，不能拦截 `release()`；`SynchronousMediaCodecAdapter` 是 final 且不暴露 `MediaCodec` 实例，做不了池化。
- 硬上（反射改 renderer / 自研 adapter 池）在没有真机的情况下风险远大于收益，按任务要求「不要硬上」，**不落这种代码**。

### 已落地的部分（为真机 A/B 准备）

| 文件 | 改动 |
| --- | --- |
| `player/PlayerTuning.kt` | 新增开关 `codecReuseEnabled`（**默认关**）+ `toggleCodecReuse()`，文档写明真正的会话复用要等自研 adapter 池或 Media3 升级。 |
| `player/PlayerAudioPipeline.kt` | `TvRenderersFactory` 的 decoder fallback 改为 `!PlayerTuning.codecReuseEnabled`（开关打开时不做失败探测，省一次无谓 codec init）。 |
| `player/PlayerControllerImpl.kt` | `CODEC_EVT` 埋点：`action=init`（decoder / init_ms / count / total_ms）与 `action=format`（`DecoderReuseEvaluation.result` + `discardReasons`）；`PLAYER_EVT` 追加 `decoder_init_ms=` / `decoder_inits=` / `codec_reuse=`。切 20 次台后看 `decoder_inits` 是 20 还是更小，就能判定复用到底有没有发生。 |

开关语义：**默认关 = 与改动前完全一致**，等真机能测时再决定是否默认开。

## 4. 如何验证（真机，需独占窗口）

```powershell
# 1) 切台 20 次（前/后各一次，同一冻结包对比）
pwsh tools/measure_player.ps1 -Zaps 20
# 2) 播放 PSS 10 分钟
pwsh tools/measure_pss.ps1
# 3) 解码器 init / 复用证据
pwsh tools/codec_report.ps1 -LogPath tools/player_live_events.log
# 4) 边界
adb logcat -d | Select-String "FATAL|BLACK_SCREEN|rebuffer="
```

验收口径：

| 指标 | 基线（v0.2.5 前） | 目标 |
| --- | --- | --- |
| 切台（慢 CDN）P90 | ~2300ms | ≤1900ms |
| 切台（快 CDN）P90 | 584ms | ≤450ms |
| 播放 PSS 均值 | 74.8MB | ≤65MB |
| 20 次连切 | — | 无黑屏、无 FATAL、rebuffer 不增加 |

## 5. 待真机确认的风险点

1. **preconnect 的 HEAD 是否被 CDN 接受**：被拒时会走 GET 回退（多一次小请求），日志 `preconnect <host> <ms>` 能看到耗时；若某 host 一直 `-1`，说明该 CDN 两条路都拒。
2. **`playbackClient` 单例化**是否影响 4G/弱网重连行为：连接池复用只在同 host 生效，跨 host 不受影响。
3. **图片暂停**：播放页返回列表后 5s 内可能出现海报短暂空白（`pauseImages` 已恢复，请求会重发），需要目视确认一次。
4. **Coil 8MB 上限**：视频页不再需要海报，理论上无感；若出现「返回详情页海报重新加载」属预期（缓存被裁）。
