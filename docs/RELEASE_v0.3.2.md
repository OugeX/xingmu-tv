## 星幕 TV v0.3.2（按 issue 修复）

对应仓库三个 issue：

### #1 Konka 828 带 MP3 音轨的直播源仍 abort —— 已修
`SafeCodecSelector` 原来只挡 `OMX.MS.MP3` / `OMX.MS.AMR`，漏了 MStar 伪装成软解的 **`OMX.google.mstmp3.decoder`**。
现在对 `audio/mpeg` 走**精确放行**：只允许 `OMX.google.mp3.decoder` / `c2.android.mp3.decoder`（AOSP 软解），其余固件解码器一律不选；同时新增 `pick mime=... -> codec` 日志，真机一条 logcat 就能看出选中了谁。

### #3 播放页遥控器切换源/清晰度不可达 —— 已修
- **OK 键死代码**：原来 `onPreviewKeyEvent` 第一行无条件 `hudVisible = true`，导致 `if (!hudVisible)` 永远不成立。现在 HUD 隐藏时按 OK = 唤出 HUD 并把焦点送进「播放/暂停」按钮；HUD 已显示则交给按钮处理。
- **新增「线路/换源」按钮**（与画质并排），不再依赖 `KEYCODE_INFO`（康佳 KW-Y006 无此键）。
- **菜单键上下文相关**：有「集」→ 选集面板；直播/单片 → 线路/画质面板。
- **↑↓ 唤出 HUD 后会 requestFocus**，不再"看得见选不中"。

### #2 新增直播源建议（91kds / 翡翠 TV）—— 已验证接口，暂不并入
我用 issue 给的地址实测：
- `http://198.204.249.162:8092/hls/818.m3u8?t=<ts>` → **200**，标准 HLS（`#EXTM3U` / `TARGETDURATION:10`）
- `http://hd1.91kds.cn/cdnar/818.m3u8` → 200，同一套 playlist，可互为备份

结论：**接口可用，但频道 ID 与频道名的映射没有公开来源**（`canghaitv` 的 EPG 接口要 32 位 md5 签名）。盲目把 `818` 塞进某个频道会张冠李戴，所以暂不并入内置 seed。已在 issue 里回复：如果能在设备上导出该 App 的频道列表（或提供 ID→台名对照），我下一版按组并入并做可用性探测。

### 下载
| 平台 | 文件 |
| --- | --- |
| 安卓电视/盒子（主包） | `xingmu-tv-v0.3.2-3b63be54.apk`（12.8MB） |
| 安卓电视/盒子（R8 精简） | `xingmu-tv-v0.3.2-r8-03d978b8.apk`（3.0MB） |
| Windows | `XingmuTV-0.1.0.exe` / `.msi` |
| 数据包 | `detail-seed-v1.zip`（378KB） |

### 验证
- 单测全通过、`lintDebug` 0 Error、debug/release 编译通过
- 日志已加 `TVBox/Codec: pick mime=audio/mpeg -> ...`，请在该机型复测时抓这一行确认选中的是 `OMX.google.mp3.decoder`
