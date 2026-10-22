## 星幕 TV v0.3.4（应用内热更 + 首页真实热度 + 分类重做）

装一次，以后自己更新。这个版本把「怎么升级」这件事从「去 GitHub 找包、拷 U 盘」变成「设置里点一下」。

### 1. 应用内热更（可关）
- 冷启动 8 秒后在后台检查 GitHub Releases（**仅 Wi-Fi、24 小时内只查一次**）。
- **访问不了就完全静默**：超时 3s / 读 4s，异常只写一条日志回 Idle，不弹窗、不打扰、不重试轰炸。
- 有新版本才出弹窗：版本号、更新说明（前 500 字）、包体积、`立即更新` / `稍后`。
- 自动选包：优先 `<300MB` 的 R8 精简包（≈3MB），只认 `.apk` 且文件名含版本号。
- 下载到 `cacheDir/update/`，有进度、可取消，校验 Content-Length + PK 头。
- 装包走 FileProvider（`com.xingmu.tv.fileprovider`）；Android 8+ 未开「未知来源」会直接跳到该设置页。
- 设置 → 关于：当前版本 / 检查更新 / 自动检查（默认开）/ 包含测试版（默认关）/ 上次检查时间。

### 2. 首页推荐改成真实热度
以前是「接口给什么排什么」。现在按**跨源重合度 → 命中数 → 有效评分 → 更新时间**排序，
新增「大家都在看 · 跨源热播」——同一部剧在多个源里都出现，才叫真的热。

### 3. 直播频道面板
分组 Tab（高清专区 / 央视 / 卫视 / 地方 / 收藏 / 最近）+ 两列网格 + 数字直达 + 搜台；
遥控器 UP 回 Tab、LEFT 回行首、数字键直跳频道号。

### 4. 分类页重做
- **竖排二级**：顶部一级 Tab → 左侧竖排二级（1080p 一屏 ≥10 项）→ 右侧筛选 + 榜单 + 网格。
- **数量角标**：二级项右侧显示该类真实总数（服务端 total，24h 缓存，≥10000 显示 `7.4k`）。
- **榜单首屏** + 筛选记忆（返回不丢选择）+ 空结果自动放宽。

### 5. 版本号
`versionName=0.3.4` / `versionCode=31`。不升这个号，更新器会把已发布的 0.3.3 当成新版提示。

### 已知限制（不粉饰）
- **更新链路的「下载 → 安装 → 授权」没有真机联调**：本次开发期四台盒子 ADB 全部 offline，只做到离线验证
  （状态机、资产选择、版本比较 75 项单测全过；用真实 GitHub API 冒烟过 tag/assets/选包）。
  真机首次跑请留意：下载完成后是否弹出系统安装器。
- 低端机（Android 5/6 + MStar）直播仍是 720p 安全档，103 个只有 1080p 源的频道默认被过滤（可按 MENU 显示全部）。
- 签名仍用本机 debug keystore（v1+v2），可覆盖安装，但上架商店需要换正式签名。

### 下载
| 平台 | 文件 | 体积 |
| --- | --- | --- |
| 安卓电视/盒子（主包） | `xingmu-tv-v0.3.4-e6f5d3dd.apk` | 12.32 MB |
| 安卓电视/盒子（R8 精简，更新器优先选它） | `xingmu-tv-v0.3.4-r8-170c539d.apk` | 2.94 MB |
| Windows | `XingmuTV-0.1.0.exe` / `.msi` | 55.7 / 55.1 MB |
| 预置详情数据包 | `detail-seed-v1.zip` | 378 KB |

### 验证
- `assembleDebug` / `assembleRelease` exit 0
- `testDebugUnitTest` 75/75 通过（update 包新增 23 项，含断网静默、非 Wi-Fi 不请求、24h 节流）
- `lintDebug` 0 Error
- aapt2：`com.xingmu.tv` / versionCode 31 / versionName 0.3.4 / minSdk 21 / targetSdk 34 /
  LEANBACK_LAUNCHER + LAUNCHER / `REQUEST_INSTALL_PACKAGES` / FileProvider `com.xingmu.tv.fileprovider`
