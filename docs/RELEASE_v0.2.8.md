## 星幕 TV v0.2.8（真机 BUG 修复）

针对真机实测反馈的三处必修：

### 1. 带 MP3 音轨的直播源导致闪退
MStar 固件解码器 `OMX.MS.MP3.Decoder` 会触发 `ACodec.cpp:3533` 断言并直接 abort 进程（无法用 try/catch 接住）。
新增 `SafeCodecSelector`，把 `OMX.MS.MP3*` / `OMX.MS.AMR*` 从解码器候选里剔除，回落到平台软件解码器（`c2.android.mp3.decoder`）——宁可多花一点 CPU，也不能崩。

### 2. 详情页封面不显示
根因：播放态资源守卫 enter/exit 不配对，导致 `pauseImages` 永久为 true，图片请求被永久暂停。
新增 `PlaybackMemoryGuard.ensureIdle()`：导航离开播放/直播/短视频页时强制恢复图片请求与缓存上限。

### 3. 播放页遥控器按键无效 / 按确认直接退出
- ←/→：快退 / 快进 10 秒（长按连续 seek）
- ↑/↓：唤出 HUD
- HUD 隐藏时 OK：播放 / 暂停
- 「返回」按钮从行首移到行尾 —— 之前它是第一个可聚焦按钮，HUD 一出现按 OK 就直接退出播放
- MENU 选集、INFO 换源/画质

### 仍在排查
- 直播画面左侧黑边 / 裁切：疑似该机型 aspect 参数与 TextureView/SurfaceView 渲染路径差异，需要该设备的品牌型号 + Android 版本 + 屏幕密度/分辨率才能定位

### 下载
| 平台 | 文件 |
| --- | --- |
| 安卓电视/盒子（主包） | `xingmu-tv-v0.2.8-710573b8.apk`（12.2MB） |
| 安卓电视/盒子（R8 精简） | `xingmu-tv-v0.2.8-r8-69d901ef.apk`（2.86MB） |
| Windows | `XingmuTV-0.1.0.exe` / `.msi` |
| 数据包 | `detail-seed-v1.zip`（378KB，500 部预置详情）
