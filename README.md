# 星幕 TV · Xingmu TV

> 一个跑在安卓电视 / 电视盒子上的开源影视客户端：直播频道、电影、剧集、动漫、综艺、海外剧，外加「随便听听看看」。
> **非商业、公益性质**，代码全部开源，不托管、不分发任何视频内容。

![home](docs/screenshots/home.png)

## 这是什么

星幕 TV 是一个**壳**，不是内容站：

- 它只做三件事：**把公开的影视接口聚合起来**、**把播放体验做好**、**把不可靠的网络源兜住**。
- 播放源由用户自己导入（MacCMS 接口 / m3u 直播源 / 订阅合集），也可以直接用内置的演示源。
- 针对**低配电视盒子**（1GB 内存、老 SoC、Android 6）做了专门的取舍，而不是把手机 App 塞进电视。

## 功能

### 点播
- 多源聚合：一个作品跨站合并，显示「N 个源可用」，缺集自动置灰
- 分类：电影 / 剧集 / 动漫 / 综艺 / **海外（韩剧·美剧·日剧·泰剧·港台）** / 短剧
- 选集：60 集以上自动分页、倒序、已看标记、播放中不退出切集
- 搜索：**首拼搜索**（`lldq` → 流浪地球）、分段结果、命中高亮、同片合并

### 直播
- 内置 **266 个频道 / 332 条源**，实测可用 244 个
- **高清专区 114 个 ≥1080p 频道**，含 3 个真 4K 频道（3840×2160，实测）
- 全屏播放 + 半透明频道浮层、`CH+/CH-` 切台、数字键直达、断流自动切备用线路

### 播放器
- Media3 / ExoPlayer，硬解优先 + 解码回落，SurfaceView 直出
- 音画同步：音频主时钟、关 offload、Sonic 倍速、AudioTrack 缓冲 ≥400ms
- 倍速 5 档、±10s / 长按连续 seek、画质与线路三合一面板、音画校准 ±500ms
- 广告治理：只走 JSON 接口不加载网页 + 域名黑名单 + 片头跳过

### 体验
- 三档显示模式：**A 影院**（深色 + 金色）/ **C 保命**（纯黑高对比，低端机）/ **大字号**（1.3×，近视友好）
- 屏幕适配：960dp 基线，按 widthDp 分 4/5/6 列，Hero 高度按屏高比例，过扫描可调
- 源导入：**手机扫码**（局域网网页粘贴/传文件）/ 配对码 / U 盘 / 遥控器键盘

## 截图

| 首页 | 详情 |
| --- | --- |
| ![home](docs/screenshots/home.png) | ![detail](docs/screenshots/detail.png) |

| 直播 | 搜索 |
| --- | --- |
| ![live](docs/screenshots/live.png) | ![search](docs/screenshots/search.png) |

| 大字号模式 |
| --- |
| ![large](docs/screenshots/large-font.png) |

## 技术架构

```
app/
├── data/            多源聚合、MacCMS 客户端、SWR 缓存、拼音索引
├── ui/              Compose 页面（首页/分类/详情/搜索/直播/播放/源管理/设置）
├── player/          Media3 封装：单实例、硬解回落、音画同步、降码降档
├── live/            频道列表、切台状态机、EPG、备用源
├── source/          源仓库（SQLite）、校验管线、聚合搜索
└── import/          局域网导入服务、二维码、U 盘/SAF、键盘契约
```

- Kotlin 2.0 + Jetpack Compose（只用 foundation/ui，不引 Material3，控包体）
- Media3 1.5.1（ExoPlayer + HLS）、OkHttp 4.12、Coil 2.7
- minSdk 21 / targetSdk 34，真机验证：LeTV 超4 X50（Android 6 / 1080p / MStar 6A938）

## 快速开始

**环境**：JDK 17、Android SDK 34+、Gradle 8.7

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 构建
gradle assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3. 安装到电视（需开启 ADB 调试）
adb connect 192.168.1.9:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 数据与源

| 文件 | 说明 |
| --- | --- |
| `assets/sources.builtin.json` | 内置演示源（7 个，均经实测可用） |
| `assets/sources.schema.json` | 源配置协议 `tvapp.source.v1.1` |
| `assets/live_seed.json` | 直播频道种子（266 频道，每条源带实测分辨率） |
| `assets/live_hd.json` | 高清专区（实测 ≥1080p 的 114 个频道） |
| `assets/pinyin_initials.json` | 首拼索引用的汉字→首字母表（6763 字，含多音字） |
| `assets/casual_radio.json` | 「随便听听」电台（实测可用 39 个） |

源探测工具：`tools/probe_sources.py`、`tools/probe_live_quality.py`、`tools/validate_sources.py`。

## 路线图

- [x] Android TV 客户端（直播 + 点播 + 多源导入）
- [ ] 播放器 1080p 黑屏修复（MStar GL 纹理路径）、首页冷启动骨架
- [ ] 随便听听看看（电台 + B 站短视频）
- [ ] 设置中心（过扫描 / 字幕字号 / 4K 优先 / 音画校准）
- [ ] **PC 客户端：Windows + macOS**（Compose Multiplatform Desktop，共用数据层与源协议）
- [ ] Release 签名 + 自动构建（GitHub Actions）

## 参与贡献

- 新增源：提交 `assets/` 下的源 JSON，或提 issue 附接口地址，跑一遍 `tools/validate_sources.py`。
- 直播源失效：提 issue 附频道名 + 失效 URL，我会用 `tools/probe_live_quality.py` 复测替换。
- 代码：PR 请保持「低端盒子优先」的取舍，不接受引入 Material3、双播放器等会拖垮老设备的依赖。

## 免责声明

1. 本项目**不提供、不存储、不分发任何视频内容**，只是一个播放器与源管理工具。
2. 内置源仅为**技术演示**，接口来自公开网络；使用者需自行确认所在地区的法律法规与内容授权情况。
3. 请勿将本项目用于商业用途或二次分发内容。因使用产生的任何法律责任由使用者自行承担。
4. 若权利人认为某个源侵犯其权益，请提交 issue，我们会移除对应演示配置。

## 许可证

代码采用 **MIT License**。第三方组件遵循各自许可证（Media3 / OkHttp / Coil / zxing 等）。

## 致谢

- [Media3 / ExoPlayer](https://github.com/androidx/media)、[Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Radio Browser](https://www.radio-browser.info/)（电台目录）、[SomaFM](https://somafm.com/)（演示电台）
- 所有提供公开接口的站点与开源工具的作者
