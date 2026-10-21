# 音画同步（A/V Sync）专项规格

**核心原则：音频是主时钟，视频去追音频。** 宁可视频丢帧，也不让音频断——人耳对声音断续的敏感度远高于画面掉帧。

## 1. 第一层：不选解不动的流（从源头避免）

| 手段 | 参数 | 作用 |
| --- | --- | --- |
| 分辨率/帧率/码率上限 | `setMaxVideoSize(1920,1080)` / `setMaxVideoFrameRate(30)` / `setMaxVideoBitrate(6_000_000)` | 防解码超时导致视频帧迟到 |
| 编码偏好 | `setPreferredVideoMimeType(H264)` | HEVC 在 RK3229 等设备软/硬解不稳，直接不选 |
| 关闭自适应切换 | `setAllowVideoMixedMimeTypeAdaptiveness(false)`、`setAllowVideoNonSeamlessAdaptiveness(false)` | 码率/编码切换瞬间最容易音画错位，直接禁用 |
| 设备能力预检 | 启动时 `MediaCodecList` 探测 1080p H.264/HEVC | 不支持就不给该档位，而不是"先播了再卡" |

## 2. 第二层：时钟与解码器配置（关键开关）

| 开关 | 取值 | 为什么 |
| --- | --- | --- |
| 主时钟 | 默认 `DefaultMediaClock`，音频 renderer 提供 position | ExoPlayer 原生以音频为主时钟，视频按音频 PTS 丢帧追赶；**不要自己接管时钟** |
| 音频 offload | **关闭**（`DefaultAudioSink` 不用 offload 模式） | 老 SoC 的 offload 时钟由 DSP 走，长播会累积漂移 |
| 音调/倍速 | 走 Sonic 拉伸（默认），**不开** `setEnableAudioTrackPlaybackParams(true)` | AudioTrack 层的 playbackParams 在部分设备会与视频时钟脱钩 |
| 浮点输出 | `setEnableAudioFloatOutput(false)`（16-bit PCM） | 老设备 float 路径重采样会引入额外延迟 |
| 跳静音 | `setSkipSilenceEnabled(false)` | 跳静音会改时间轴节奏，倍速 + 跳静音组合最容易漂 |
| 解码回落 | `setEnableDecoderFallback(true)` | 硬解初始化失败时换解码器，而不是卡住不动 |
| 渲染输出 | `SurfaceView` 直出，无 `VideoFrameProcessor`/滤镜 | 任何帧处理都会给视频链加延迟 |
| 音频直通 | 默认 **PCM**，直通（passthrough）作为用户可选项 | HDMI 直通由功放解码，功放延迟不同步时用户可一键关掉 |

## 3. 第三层：缓冲与直播偏移

- **音频缓冲加大**：`DefaultAudioSink.Builder().setAudioTrackBufferSizeProvider(...)`，低端盒子上给到 ≥ 400ms 的 AudioTrack 缓冲，减少 underrun（欠载后追赶就是典型的"卡一下然后音画错位"）。
- 播放缓冲：点播 min 15s / max 30s / 12MB；直播 min 6s / max 15s / 8MB（固定上限，防内存抖动导致解码阻塞）。
- 直播延迟目标：`setTargetOffsetMs(3000)`、`setFallbackTargetOffsetMs(5000)`；追赶时走 `setSpeed` 微调而非暴力 seek（ExoPlayer 直播追赶默认行为）。
- 切换/切集一律 `stop() → setMediaItem → prepare()`，**禁止在旧时间轴上直接换源**（旧 timestamp 残留是最常见的错位来源）。

## 4. 第四层：运行期检测与自愈

监听 `AnalyticsListener`：

| 事件 | 阈值 | 动作 |
| --- | --- | --- |
| `onAudioUnderrun` | 30s 内 ≥3 次 | 提升音频缓冲一档 → 仍失败则降一档清晰度 |
| `onDroppedVideoFrames` | 30s 内掉帧 >20% | 自动降档 720p 并提示「性能不足，已切 720p」 |
| `onVideoInputFormatChanged` 异常 | 分辨率/帧率突变 | 记录并触发一次 `seekTo(currentPosition)` 重同步 |
| 播放错误 / 连续 8s 无数据 | — | 按 priority 切下一个源的同集同位置（保留进度） |
| 用户反馈音画不同步 | 手动 | 播放器 HUD 提供「音画校准」：音频延迟 −500…+500ms 滑块（`setVideoOffsetMs` 走 renderer offset，不动流） |

> 说明：`MediaCodecVideoRenderer` 自带"帧迟到就丢"的策略，所以**正常路径下不会出现持续的音画漂移**；真正会出问题的是三类：offload 时钟漂移、AudioTrack 欠载后追赶、以及切换源时残留旧时间轴。上面的开关正是逐条堵这三类。

## 5. 验收（写入 t5 / t6）

| 项 | 标准 |
| --- | --- |
| 长播稳定性 | 连续播放 60 分钟，抽样 5 次，音画偏差 ≤80ms（用拍手/口型画面人工比对 + 日志时间戳） |
| 弱网 | 限速到 2Mbps 播放 10 分钟，无持续错位；出现 underrun 能自动恢复 |
| 切集/换源 | 连续切 20 次，无残留错位、无黑屏 >1s |
| 倍速 | 0.75×–2× 各播 3 分钟，音调正常、无累积漂移 |
| 直播 | 切台 20 次，每次起播 ≤2.5s，无音画错位 |
| 老设备 | 1GB 盒子 1080p 播放 30 分钟，掉帧 ≤10%、无 underrun 连锁 |
