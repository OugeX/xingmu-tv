## 星幕 TV v0.3.0

### ⚠️ 重要：包名已变更
- 旧：`com.tvbox.app` → 新：**`com.xingmu.tv`**
- **这是两个不同的应用**：安装新版前请先卸载旧版（`adb uninstall com.tvbox.app` 或在电视上卸载），否则桌面会出现两个图标。
- 旧版的本地源、追剧进度、缓存不会迁移（新包独立数据目录）。

### 本版包含
- 包名 / namespace 全量迁移到 `com.xingmu.tv`，baselineprofile 模块与脚本同步
- v0.2.9 的 Konka 828 专项修复：TextureView 画面比例矩阵重写（左侧黑边/裁切）、HUD 新增「画面 FIT/FILL/ZOOM」
- v0.2.8 的真机修复：`OMX.MS.MP3` 解码器黑名单（不再因 MP3 音轨崩溃）、封面暂停守卫复位、播放页遥控器按键（←→ 快退快进 / ↑↓ 唤 HUD / OK 播放暂停 / 返回按钮移到行尾）
- R8 精简包：12.7MB → **3.0MB**

### 验证范围
- 单元测试全通过、lint 0 Error、debug/release 均编译通过
- 包名已用 `aapt2 dump badging` 核实为 `com.xingmu.tv`（versionName 0.3.0）
- 真机（LeTV / Konka）复测仍待你在设备上执行

### 下载
| 平台 | 文件 |
| --- | --- |
| 安卓电视/盒子（主包） | `xingmu-tv-v0.3.0-b1970c1c.apk`（12.7MB） |
| 安卓电视/盒子（R8 精简） | `xingmu-tv-v0.3.0-r8-cff32933.apk`（3.0MB） |
| Windows | `XingmuTV-0.1.0.exe` / `.msi` |
| 数据包 | `detail-seed-v1.zip`（378KB） |
