## 星幕 TV v0.3.3（issue #1 真因修复）

### 真因：mime 是 `audio/mpeg-L2`，不是 `audio/mpeg`
v0.3.2 的放行判断是**精确匹配** `audio/mpeg`，而 IPTV 的 TS 音轨常声明成 `audio/mpeg-L2`（MP2），
落到 `else` 分支后只按前缀挡 `OMX.MS.MP3` / `OMX.MS.AMR`，于是 **`OMX.google.mstmp3.decoder` 又被选回来**，
继续踩 `ACodec.cpp:3533` 断言 → SIGABRT。

### 修法（两层）
1. **选择器按族匹配**：`mimeType.startsWith("audio/mpeg")` 覆盖 `-L1` / `-L2`；该族只放行 AOSP 软解
   （`OMX.google.mp3.decoder` / `c2.android.mp3.decoder`）。
2. **无安全解码器时返回空列表**（不再回退到全部候选），由播放器降级：
   禁用音轨 → 继续播视频 → HUD 提示「该源音轨不兼容，已静音播放」。
   宁可静音，也不能让整台电视掉出去。

### 复测方式
```bash
adb install -r xingmu-tv-v0.3.3-ad916cbc.apk
adb logcat -c
# 播放 issue 里的源：124.228.160.112:9901/tsfile/live/0009_1.m3u8?key=txiptv&playlive=1&authid=0
adb logcat -d | grep -E "TVBox/Codec|AndroidRuntime|SIGABRT"
```
预期：
- `TVBox/Codec: pick mime=audio/mpeg-L2 -> `（空，说明没有安全解码器）
- 随后 `audio decoder unavailable, retry muted` 与 HUD「已静音播放」
- **不再出现 SIGABRT**

### 下载
| 平台 | 文件 |
| --- | --- |
| 安卓电视/盒子（主包） | `xingmu-tv-v0.3.3-ad916cbc.apk` |
| 安卓电视/盒子（R8 精简） | `xingmu-tv-v0.3.3-r8-23e77dfc.apk`（3.0MB） |
| Windows | `XingmuTV-0.1.0.exe` / `.msi` |
| 数据包 | `detail-seed-v1.zip` |

### 验证
单测全通过、`lintDebug` 0 Error、debug/release 编译通过。
