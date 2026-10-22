# 真机目标设备（已实测，优先级高于所有推断）

## 设备事实（adb 读取，2026-10-21 19:21）

| 项 | 值 |
| --- | --- |
| 型号 | LeTV 超4 X50（`X4_50`），硬件版本 H5000，EUI 8.0.080S |
| ADB | `192.168.1.9:5555`（已连接成功，可直接安装/截图/录屏） |
| Android | **6.0 / SDK 23**（不是 8/9，更不是 11+） |
| 屏幕 | 1920×1080，density 240dpi → **widthDp = 1280** |
| 内存 | MemTotal 2.87GB（非 1GB 盒子），dalvik heap 512m |
| SoC 平台 | `maserati`（MStar 6A938 系列，支持 4K/HEVC 硬解） |
| APK 安装 | `am start com.xingmu.tv/.MainActivity` 成功，Compose 首页正常渲染 |

## 对实现的直接影响

1. **widthDp=1280 → 落在「6 列」档**（UI_SPEC §0.5：1200–1599 → 6 列）。所有布局按 1280dp 宽验证，不要按 960dp 想象。
2. **API 23 是硬下限的现实**：Compose 1.7 + Media3 1.5.1 在 API 23 上已实测可渲染；但注意以下 API 在 23 上不可用，禁止使用：
   - `Context.createDeviceProtectedStorageContext`（24+）
   - `MediaCodecInfo.CodecCapabilities.isHardwareAccelerated`（29+）
   - `AudioTrack.Builder.setOffloadedPlayback`（26+）
   - `Window.setDecorFitsSystemWindows`（30+）、`WindowInsetsController`（30+）
   - `StorageManager.getAllocatableBytes`（26+）
3. **内存档位**：2.87GB → `PerfProfile = TIER_MID`，走 A 影院模式；C 保命模式仍要保留（用户可能在更差盒子上装）。
4. **硬解能力**：MStar 6A938 支持 H.264/HEVC 1080p60、4K30 解码；但按 AVSYNC_SPEC 仍默认 1080p30 / 6Mbps / H.264 优先，HEVC 仅在探测通过时可选。
5. **真机验证通道已打通**（给 t5/qa）：
   ```
   adb connect 192.168.1.9:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.xingmu.tv/.MainActivity
   adb exec-out screencap -p > shot.png
   adb shell dumpsys meminfo com.xingmu.tv      # PSS
   adb shell am start -n com.xingmu.tv/.MainActivity && adb shell dumpsys gfxinfo com.xingmu.tv
   ```
6. **过扫描**：该机型实测四边无裁切（EUI 默认无 overscan），overscan 设置默认 0%，仍保留三档可调。
7. **摇控器**：该机为蓝牙语音遥控器，`KEYCODE_CHANNEL_UP/DOWN`、`KEYCODE_MENU`、数字键均可用；语音键走系统搜索 Intent（对应 SEARCH_SPEC P0 层，需实测系统是否响应 `RecognizerIntent`）。


## Konka Android TV 828（issue #1 反馈机型，2026-10-22）

| 项 | 值 |
| --- | --- |
| 型号 | Konka Android TV 828（`ro.product.device=konka828`，`aosp_konka828`） |
| 系统 | **Android 5.0.1 / API 21**，`ro.board.platform=muji`（MStar），userdebug + dev-keys |
| 屏幕 | 1920×1080 @240dpi（无覆盖值） |
| ADB | `persist.adb.tcp.port=5555` 预设常开（但该机型当前不在我方局域网内，无法直连） |
| **screencap** | **输出恒 0 字节**（muji 固件问题）→ 验证只能靠 `logcat` + `dumpsys window windows | grep SurfaceView` |
| 存储 | `/data` 总 1.4GB：标准包装后剩 ~17MB；**R8 包剩 130MB** → 低存储机型默认推 R8 |
| 解码器 | 注册了 3 个 `audio/mpeg` 解码器：`OMX.MS.MP3.Decoder`、**`OMX.google.mstmp3.decoder`（伪装成软解，会踩 ACodec.cpp:3533 abort）**、`OMX.google.mp3.decoder`（安全） |
| 遥控器 | 康佳 KW-Y006，**无 INFO 键** → 换源/画质入口不能只绑 INFO |

**踩坑记录**：`pm disable-user` 被该固件 PMS 拒绝（SecurityException），`pm hide` 可用（用户已用它清掉 16 个预装广告/商城包）。
