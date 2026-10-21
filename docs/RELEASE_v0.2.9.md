## 星幕 TV v0.2.9（Konka 828 / Android 5.0.1 专项修复）

目标机型：**Konka Android TV 828**，Android 5.0.1（API 21），MStar `muji` 平台，1920×1080 @240dpi，userdebug 固件。

### 修了什么

**1. 直播画面左侧黑边 / 未铺满 / 被裁切**
`TvTextureView` 的缩放矩阵原来是「像素比统一缩放」：
- 16:9 内容进 16:9 视图时 scale=1，看似正常；
- 一旦内容/视图比例不一致（4:3 直播、带黑边的源、视图尺寸与视频尺寸不同），就会退化成拉伸或居中错位，表现为「左边一条黑、画面被裁」。

现在改成按 **视频宽高比 vs 视图宽高比** 计算 `sx/sy` 再居中平移，FIT / FILL / ZOOM 三种模式各自正确；同时把 `video= / view= / sx= / sy=` 打进 `TvLog`，方便真机排障。

**2. 无法选择画面比例**
HUD 新增「画面 FIT / FILL / ZOOM」按钮，遥控器可直接切换，立即生效。

**3. 顺手带上的两处（v0.2.8 已有）**
- MP3 音轨直播源崩溃：`SafeCodecSelector` 剔除 `OMX.MS.MP3*` / `OMX.MS.AMR*`，回落平台软解
- 播放页按键：←→ 快退快进 10s、↑↓ 唤 HUD、HUD 隐藏时 OK 播放/暂停、「返回」移到行尾（不再一按 OK 就退出）

### 请在该机型上复测这四项
1. 直播：画面是否铺满、左侧黑边是否消失；按 HUD「画面」切 FILL/ZOOM 是否正常
2. 播放页：←→ 快进快退、OK 播放/暂停、MENU 选集、INFO 换源
3. 详情页：封面是否显示
4. 带 MP3 音轨的直播源：是否仍闪退

如果还有问题，请把 `adb logcat -s TVBox/TextureView` 的输出发我（里面会有 `video= / view= / sx= / sy=`），我能直接算出是矩阵还是源的问题。

### 下载
| 平台 | 文件 |
| --- | --- |
| 安卓电视/盒子（主包） | `xingmu-tv-v0.2.9-87ec44c7.apk`（12.3MB） |
| 安卓电视/盒子（R8 精简） | `xingmu-tv-v0.2.9-r8-0be25ac0.apk`（2.86MB） |
| Windows | `XingmuTV-0.1.0.exe` / `.msi` |
| 数据包 | `detail-seed-v1.zip`（378KB） |
