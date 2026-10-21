# 低端电视盒子性能架构方案

> 任务：t1 / t-arch　作者：architect　日期：2026-10-21　修订：按 captain 冻结决策改为 Compose + Coil 2.7.0
> 适用：1GB RAM、老 SoC（Amlogic S905W/S905X、Rockchip RK3229、Allwinner H3 级别，Android 5.1–9）的 Android TV 影视客户端
> 关联：t2 直播源、t3 自定义源导入、t4 客户端实现、t5 验收。本文接口契约为 t3/t4 的集成边界，参数为 t4 的实现基线、t5 的验收基线。

---

## 0. 一句话结论

**UI 冻结为 Compose（Foundation，不用 Material3），图片冻结为 Coil 2.7.0（共享 OkHttp、硬件位图 + RGB565、固定 320×450、无 crossfade），列表用单个 `LazyVerticalGrid`，数据用文件缓存 + 流式解析，播放用单实例 Media3 ExoPlayer，所有参数集中在 `PerfProfile` 按内存档位降级。** 目标：1GB 盒子冷启动首帧 ≤1.5s、缓存内容 ≤2.0s、浏览 PSS ≤260MB、APK ≤14MB、滚动掉帧 ≤10%。

取舍总原则：

1. **启动优先于功能**：首帧之前不做磁盘、网络、图片解码。
2. **确定性优先于炫技**：固定尺寸、固定线程数、固定缓存上限；不做无限动画与嵌套滚动。
3. **缓存优先于等待**：接入源 CDN 首次字节实测 0.6–3.8s，必须先渲染上次缓存，再静默刷新。
4. **单实例优先于并行**：1GB 盒子只允许一个 ExoPlayer、一个 Coil ImageLoader（共享 OkHttp）。

---

## 1. 决策摘要（ADR）

| # | 决策 | 选择 | 取舍理由 | 被放弃方案的问题 |
|---|---|---|---|---|
| A1 | UI 框架 | **Compose（Foundation + ui，不用 Material3）** | 团队冻结决策，Kotlin 原生、声明式列表与焦点 API 完整；只引 Foundation 控制包体与组合开销 | Views：代码量大、焦点手写多；Material3：额外包体与主题/动画开销 |
| A2 | TV 组件 | **不用 Leanback**，Compose 自写焦点与网格 | Leanback 已停止维护、Presenter/RowList 重；Compose `FocusRequester` 可控 | Leanback：焦点黑盒、依赖旧、启动成本高 |
| A3 | 页面容器 | **单 Activity + Compose 屏幕状态机**（`when(screen)` + `BackHandler`） | 无 Activity/Fragment 转场，返回播放页可复用 Compose 状态；内存省 8–15MB | 多 Activity：转场 150–300ms；navigation-compose：额外依赖与返回栈状态开销 |
| A4 | 首页结构 | **一个 `LazyVerticalGrid` + 整行 Header** | 单滚动容器、复用组合、焦点线性可预测；避免嵌套横滑列表 | 嵌套 `LazyRow`：每行独立滚动/焦点，低端机上测量与组合成本高 |
| A5 | 图片库 | **Coil 2.7.0**（冻结）：共享 OkHttp、硬件位图 + RGB565、固定 320×450、无 crossfade | 团队冻结；Compose 原生、无注解处理器、包体小、参数够用 | 其他图片库：无共享 client/无 Compose 原生集成/缓存调参弱 |
| A6 | 本地存储 | **文件缓存 + `JsonReader`**，不用 Room/kotlinx.serialization | 少 400–700KB 依赖、零建库启动成本；列表只需 7 个字段，流式解析跳过 80% 无用字段 | Room：首次 open 100–300ms、+APK；序列化库：分配多、反射/生成代码 |
| A7 | 播放器 | **Media3 ExoPlayer 1.5.1，仅 HLS** | 硬解、HLS、错误重试成熟；只引 hls 模块控制包体 | 自研 MediaPlayer 封装：HLS 支持差；引入 dash/rtmp：无用代码 |
| A8 | 播放 UI | **`AndroidView` 承载 `PlayerView`（关控制器）+ Compose 覆盖层** | SurfaceView、字幕、宽高比、错误处理开箱即用；比自研 SurfaceView 少 300–500 行风险代码 | 纯自研：字幕/宽高比/音频焦点易错；media3-ui 成本约 500–700KB，可接受 |
| A9 | 网络 | **API、playlist、Coil 图片共用同一个 OkHttpClient** | 一个连接池、一套 TLS/UA/超时；图片与 API 同 CDN/同 host 时复用连接 | 各自独立客户端：连接池翻倍、TLS 握手重复、内存与耗时上升 |
| A10 | 数据策略 | **缓存优先、stale-while-revalidate** | API/CDN TTFB 0.6–3.8s，必须让用户 2s 内看到内容 | 纯网络加载：首屏空白 2–4s |
| A11 | 清晰度 | **默认 1080p30 / 6Mbps 上限，优先 H.264** | 老 SoC 硬解 H.264 1080p 稳定；HEVC/60fps 易失败或掉帧；提供手动 720p/480p | 默认最高画质：解码失败、发热、缓冲 |
| A12 | minSdk | **21（Android 5.0）** | Media3/Compose 下限、TLS1.2 默认开启、WebP 完整支持、原生 multidex | minSdk 19：需降级 ExoPlayer 2.x + 旧 AndroidX，维护成本高、占比低 |
| A13 | DI | **手写 AppGraph** | 零启动成本、零反射、R8 友好 | Hilt：启动 +100–300ms、生成代码 +APK |
| A14 | 后台播放 | **不做**（离开播放页立即释放） | 1GB 盒子无多任务价值；常驻播放器占 30–60MB 原生内存 | MediaSessionService：内存与功耗上升 |
| A15 | 搜索 | P0 不做软键盘搜索，走分类树浏览 | TV 遥控输入体验差、输入法占内存 | 全量搜索：输入法、索引、额外内存 |
| A16 | priority 语义 | **数值小优先**（`priority: Int = 100`，越小越优先） | 与 t3 导入格式一致，排序确定 | 数值大优先：易与常见配置语义冲突 |

---

## 2. 目标设备、实测基线与会话预算

### 2.1 设备档位（启动时自动判定）

`PerfProfile` 在 `Application.onCreate()` 用两个信号判定档位，不读文件、不发网络：

```kotlin
// minSdk 21：不能用 getSystemService(ActivityManager::class.java)（API 23）
val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
val lowRam = am.isLowRamDevice || am.memoryClass <= 192   // 多数 1GB 盒子 memoryClass=128~192
```

| 档位 | 判定 | 代表设备 | 关键参数 |
|---|---|---|---|
| LOW | `isLowRamDevice` 或 `memoryClass ≤ 192` | S905W 1GB / RK3229 1GB | 图片缓存 ≤16MB、6 列(1080p)/5 列(720p)、无焦点缩放、无背景大图、首屏只预取 6 张 |
| MID | `memoryClass ≤ 384` | S905X2 2GB | 图片缓存 24MB、焦点缩放 1.04、预取 1 行 |
| HIGH | 其他 | 4GB 盒子/开发机 | 图片缓存 24MB（上限）、允许预取 2 行 |

**取舍**：不按 `Build.MODEL` 白名单做适配（型号无穷、易误判），只按内存与系统信号；白名单仅用于已知解码缺陷的兜底（见 §14）。

### 2.2 接入源实测特征（2026-10-21，影响架构的事实）

| 项目 | 实测 | 架构含义 |
|---|---|---|
| 列表接口 20 条 | 约 45KB JSON，单条 60–90 个字段（列表**不含** `interaction`，该字段仅 detail 有） | 流式解析只取 7 个字段；解析放后台；页面缓存落文件 |
| 详情接口 20 条 | 约 164KB JSON（约 8KB/条），含 `interaction`、`vod_play_url` 长串 | 不允许主线程解析；详情单独缓存；只解析播放串与简介 |
| 主 API 端点 | 必须带结尾斜杠：`https://macapi1.com/maccms/json/baofeng/`；缺斜杠会 307 降级到 http | API 基址运行时可配置；请求前规范化结尾斜杠，避免明文降级 |
| 海报图 | WebP，宽 270px（270×270 ~ 270×405），8–43KB/张，TTFB 0.6–1.8s | 卡片按 320×450 请求（封版值）；禁止全屏大图/模糊背景；必须预取与占位 |
| 点播 m3u8 | 目标时长 1s、单集 4256 段 TS | 起播只需 2–3 段，缓冲 15s 足够；seek 用 CLOSEST_SYNC 几乎瞬时 |
| 主域直连 | `macapi1.com` 根路径直连返回 400（"无法从主域名识别数据库"），TTFB 0.85–3.8s | 必须使用 t2/t3 验证过的完整 API 路径，禁止硬编码根域 |

> 说明：以上只用于确定参数与预算，不作为网络质量承诺；t5 需在真实盒子上复测。

### 2.3 性能预算总表（LOW 档，硬门槛）

| 指标 | 目标（D1：S905W 1GB / Android 7.1 / 1080p） | 测量方式 |
|---|---|---|
| 冷启动首帧 | ≤ 1.5s（验收门槛 2.5s） | `am start -W` + Perfetto 首帧 |
| 冷启动可交互（缓存内容） | ≤ 2.0s | 录像/帧时间戳 |
| 冷启动网络新内容 | ≤ 3.5s（源站 TTFB 决定，允许骨架先出） | 帧时间戳 + 日志 |
| 浏览 PSS | ≤ 260MB（Compose + 硬件位图比 View 方案高 30–40MB，已计入） | `dumpsys meminfo` |
| 播放 PSS | ≤ 340MB | `dumpsys meminfo` |
| Java 堆 | ≤ 120MB（不启用 largeHeap） | `dumpsys meminfo` |
| 滚动掉帧率 | ≤ 10%，无 >250ms 单帧 | `dumpsys gfxinfo` |
| APK | ≤ 14MB（硬上限 16MB） | `apkanalyzer apk file-size` |
| 点播起播到首帧 | ≤ 2.5s（20Mbps） | 日志打点 |
| 直播换台到首帧 | ≤ 2.5s | 日志打点 |
| 30 分钟循环 | 无 OOM/崩溃，内存增长 ≤20MB | soak 脚本 |

---

## 3. 总体结构

### 3.1 模块与包结构（单 module，package-by-feature）

避免多 module 的构建复杂度；R8 对单 module 同样有效。

```
app/
├── src/main/java/tv/bfzy/
│   ├── App.kt                     // Application：只做 PerfProfile + AppGraph
│   ├── AppGraph.kt                // 手写依赖图（OkHttp/Coil/FileCache/Api/Live）
│   ├── core/
│   │   ├── perf/PerfProfile.kt    // 档位与所有参数（唯一参数源）
│   │   ├── perf/JankGuard.kt      // 帧时间监控与降级开关
│   │   ├── net/Http.kt            // 共享 OkHttpClient、超时、UA、TLS、图片缓存头
│   │   ├── cache/FileCache.kt     // LRU 文件缓存 + 后台清理
│   │   ├── image/Images.kt        // Coil ImageLoader 单例 + 请求模板
│   │   ├── ui/TvTheme.kt          // 颜色/字号（无 Material3）
│   │   └── ui/Keys.kt             // D-pad 连发合并、按键映射
│   ├── data/
│   │   ├── maccms/MacCmsApi.kt    // list/detail 流式解析
│   │   ├── maccms/Models.kt       // VodSummary/VodDetail/PlaySource/Episode
│   │   ├── live/LiveRepository.kt // assets 种子 + 远端刷新
│   │   ├── source/SourceStore.kt  // 源配置读写（t3 契约）
│   │   └── cache/HomeCache.kt     // 首页/详情 JSON 的 stale-while-revalidate
│   ├── feature/
│   │   ├── home/HomeScreen.kt     // LazyVerticalGrid + Header
│   │   ├── detail/DetailScreen.kt
│   │   ├── player/PlayerScreen.kt // AndroidView(PlayerView) + Compose 覆盖层
│   │   ├── live/LiveScreen.kt
│   │   └── settings/SettingsScreen.kt
│   └── ui/                        // 轻量 Compose 组件（PosterCard 等）
├── src/main/res/                  // 无自定义字体、无大图
└── src/test/                      // 解析器/缓存纯 JVM 测试
```

### 3.2 运行时结构

```
MainActivity (singleTask, landscape, 无转场)
 └─ setContent { Root() }        // Compose，无 Fragment/navigation-compose
     ├─ HomeScreen              // LazyVerticalGrid(6 列) + 整行 Header
     ├─ DetailScreen            // 头部 item + 剧集 LazyVerticalGrid
     ├─ PlayerScreen            // AndroidView(PlayerView) + Compose 控制层
     └─ LiveScreen              // 分组 Header + 频道 Grid

AppGraph (application context only)
 ├─ OkHttpClient (共享连接池；API/playlist/Coil 图片)
 ├─ Coil ImageLoader (单例，独立 image_cache 目录)
 ├─ FileCache (cacheDir/api_cache, LRU 96MB)
 ├─ MacCmsApi (baseUrl/headers 来自 SourceStore)
 └─ LiveRepository
```

**不使用 Fragment、navigation-compose、Material3、Leanback、CoordinatorLayout。**

### 3.3 线程模型与优先级

| 工作 | 执行位置 | 并发上限 | 线程优先级 | 说明 |
|---|---|---|---|---|
| 输入/焦点/组合/绘制 | 主线程 | 1 | 默认（显示） | 单帧主线程工作 ≤8ms；避免在 composition 里做 IO |
| 播放器内部 | Media3 playback thread + codec | 1 播放器 | 系统 | 不在主线程碰 Player |
| API/playlist 网络 | OkHttp Dispatcher（共享） | 全局 6，同 host 4 | 默认 | 交互请求优先 |
| 图片网络 | 同上（Coil 使用同一 client） | 受 OkHttp 上限约束 | 默认 | 与 API 共享连接池 |
| 图片解码 | Coil `dispatcher` | 2 | `THREAD_PRIORITY_BACKGROUND` | `Dispatchers.IO.limitedParallelism(2)` 或专用 2 线程池 |
| JSON 解析/文件缓存 | `Dispatchers.Default.limitedParallelism(2)` + 单线程文件 executor | 2 / 1 | `THREAD_PRIORITY_BACKGROUND` | 45–164KB 解析 20–60ms，绝不主线程 |
| 缓存清理/日志 | 单线程后台 | 1 | `THREAD_PRIORITY_BACKGROUND` | 首帧后启动 |

```kotlin
private fun backgroundThreadFactory(name: String) = ThreadFactory { r ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, name).apply { isDaemon = true }
}
```

共享 OkHttp 的基线配置（API/playlist/Coil 共用）：

```kotlin
val okHttp = OkHttpClient.Builder()
    .dispatcher(Dispatcher().apply {
        maxRequests = 6
        maxRequestsPerHost = 4
    })
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .addInterceptor(imageCacheHeaders)   // 见 §5.2
    .build()
```

**取舍**：图片解码固定 2 路（Coil dispatcher），避免快速滑动时 10+ 并发解码把 4 核 CPU 打满导致焦点/滚动掉帧；代价是大批量预取变慢，而预取只允许在滚动空闲时进行。

### 3.4 给 t3/t4 的集成契约（P0 冻结）

t3 的导入格式可以有自己的外壳，但必须能映射为下面的 `SourceConfig`；App 侧只依赖这些类型。**priority 数值越小越优先。**

```kotlin
data class SourceConfig(
    val id: String,                 // 稳定唯一 id，如 "bfzy"
    val name: String,               // 展示名
    val apiBase: String,            // 必须以 / 结尾，如 https://macapi1.com/maccms/json/baofeng/
    val headers: Map<String, String> = emptyMap(), // UA/Referer 等，t2/t3 验证后给出
    val enabled: Boolean = true,
    val priority: Int = 100         // 数值小优先
)

data class VodSummary(
    val id: String, val name: String, val pic: String,
    val remarks: String, val typeId: Int,
    val year: String, val score: String
)

data class Episode(val name: String, val url: String)
data class PlaySource(val from: String, val episodes: List<Episode>)

data class VodDetail(
    val id: String, val name: String, val pic: String,
    val content: String, val year: String, val area: String,
    val remarks: String, val sources: List<PlaySource>
)

data class LiveChannel(val name: String, val urls: List<String>, val logo: String? = null)
data class LiveGroup(val name: String, val channels: List<LiveChannel>)

interface VodRepository {
    suspend fun list(typeId: Int, page: Int): Result<Page<VodSummary>>
    suspend fun detail(id: String): Result<VodDetail>
}
interface LiveRepository { suspend fun groups(): List<LiveGroup> }
interface SourceStore {
    fun load(): List<SourceConfig>
    fun save(list: List<SourceConfig>)
}
```

解析约定：

- `vod_play_from` 与 `vod_play_url` 都是 `$$$` 分隔的多组，按下标配对；组内 `#` 分集，`$` 分名称与 URL。名称缺失时用"第N集"。
- `type_id` 在列表/详情中可能是字符串或数字，解析器两种都接受。
- URL 只允许 `http/https`；导入源中的 `file://`、`content://`、`javascript:` 一律拒绝（防止读取本地文件）。
- `apiBase` 进入 `MacCmsApi` 前统一补结尾斜杠；缺斜杠的 307 可能把 https 降级为 http，必须避免。
- 所有网络错误转成 `Result.failure(TypedError)`，UI 只展示"重试/换源"，不得崩溃。
- 详情缓存按 `sourceId + vodId` 建 key；首页缓存按 `sourceId + typeId + page`。

### 3.5 自定义源导入的运行时策略

- 导入是独立流程：解析 JSON → 校验必填字段 → URL 只允许 http/https → 用 5s 超时拉一次 `ac=list&pg=1` 做可用性验证 → 保存。单个源失败不影响内置源。
- `apiBase` 规范化结尾斜杠后再验证，防止 307 明文降级。
- 源配置（<32KB）放 SharedPreferences，首帧后懒加载；切换源时清空列表/详情内存缓存，文件缓存按 `sourceId` 命名隔离，避免串源。
- 坏源自动标记禁用并提示，不自动删除用户配置；UI 顶部提供源切换入口（P1）。
- 导入过程不下载图片、不预取内容、不创建播放器。

### 3.6 TV 清单与窗口（t4 直接照抄）

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-feature android:name="android.software.leanback" android:required="false"/>
<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>

<application
    android:name=".App"
    android:allowBackup="false"
    android:banner="@drawable/tv_banner"
    android:largeHeap="false"
    android:usesCleartextTraffic="true"
    android:theme="@style/Theme.BfzyTv">   <!-- 平台 Theme.Material.NoActionBar 派生 -->
    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:screenOrientation="landscape"
        android:configChanges="keyboard|keyboardHidden|navigation"
        android:launchMode="singleTask">
        <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
            <category android:name="android.intent.category.LAUNCHER"/>
        </intent-filter>
    </activity>
</application>
```

- `LEANBACK_LAUNCHER` 才会出现在 TV 桌面；`android:banner` 用 320×180 WebP（P0 提供）。
- `usesCleartextTraffic=true` 是导入源/直播源混用 http 的现实妥协；只在播放数据源和导入源上放行，不用于 API 之外的敏感请求。
- `screenOrientation="landscape"` 固定横屏，避免旋转重建；不声明 `resizeableActivity`。
- 窗口主题用平台 `Theme.Material.NoActionBar`（API 21+），不引 AppCompat；`windowBackground` 只放纯色 + logo。

---

## 4. 列表复用与渲染（Compose）

### 4.1 布局结构：单层 LazyVerticalGrid，禁止嵌套滚动

首页 = 一个 `LazyVerticalGrid`：

```kotlin
LazyVerticalGrid(
    columns = GridCells.Fixed(columns),          // 1080p 6，720p 5
    state = gridState,
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    userScrollEnabled = true,
) {
    item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        CategoryHeader(...)
    }
    items(
        items = items,
        key = { it.id },
        contentType = { "poster" },              // 所有卡片同类型，复用组合
    ) { vod ->
        PosterCard(vod = vod, ...)
    }
}
```

- 分类 Header、分区标题都作为 `span = maxLineSpan` 的整行 item；
- 卡片 item 占 1 span；
- 不做"每行一个 `LazyRow`"，避免嵌套滚动容器、重复组合和焦点跨行丢失。

详情页剧集：同样是单个 `LazyVerticalGrid`（8 列/720p 5 列，纯文字格），头部简介作为整行 item，禁止 `Column + verticalScroll` 里再放 `LazyVerticalGrid`。

**取舍**：放弃 Netflix 式横滑行，视觉表现力下降，但换来组合次数减半、焦点可预测、滚动稳定；1GB 盒子上这是正确取舍。

### 4.2 列数与尺寸：按像素算，再转 dp

```kotlin
// 必须用物理像素算列数：1080p box 常见 density=2（screenWidthDp≈960），
// 720p box 常见 density=1（screenWidthDp≈1280），用 dp 判断会得到相反结果。
val pxW = LocalContext.current.resources.displayMetrics.widthPixels
val columns = when {
    pxW >= 1800 -> 6       // 1080p
    pxW >= 1100 -> 5       // 720p
    else -> 4
}
val density = LocalDensity.current.density
val hPaddingPx = 24 * density
val gapPx = 12 * density
val cardWpx = (pxW - hPaddingPx * 2 - gapPx * (columns - 1)) / columns
val cardW = with(LocalDensity.current) { cardWpx.toDp() }     // 1080p ≈142dp=284px；720p ≈236dp=236px
val cardH = cardW * 1.5f                                  // 2:3 海报
val textH = 40.dp
```

- 1080p：6 列，卡宽约 284px，与源图 270px 宽基本 1:1（允许 5% 上采样）；
- 720p：5 列，卡宽约 236px，约为源图的 0.87 倍（缩小更清晰，且一屏能放 2 行）；
- 卡片固定 `Modifier.size(cardW, cardH)`，图片区 `weight(1f)`，标题固定 `textH`；**禁止 `wrapContentHeight` 与测量后再改尺寸**；
- 标题单行 `maxLines=1`、`overflow=Ellipsis`；`remarks` 单行；不显示简介。

### 4.3 Compose 列表参数（固定值）

| 参数 | 值 | 理由 |
|---|---|---|
| `key` | `vod.id` | 稳定身份，增删/刷新不重建全部组合 |
| `contentType` | `"poster"` / `"header"` | 同类型复用，减少组合差异 |
| 卡片尺寸 | 固定 dp（§4.2） | 避免测量抖动与二次布局 |
| `animateItem` | 关闭 | 低端机增删动画是额外布局/绘制 |
| `crossfade` | 关闭 | 省一次合成与双图驻留 |
| `clip` | `RoundedCornerShape(8.dp)` | 单层裁剪，不做阴影 |
| 焦点视觉 | 2dp 白边（`Modifier.border`） | 只重绘该 item |
| 图标 | vector 资源 | 无 PNG 多密度、无 Material Icons 依赖 |
| 列表项层级 | 图片 + 两条 `BasicText` | 组合节点少，测量快 |

**禁止**：`notifyDataSetChanged` 等价物（直接换 List 触发整树重组）、`animateItem`、`Modifier.shadow/elevation`、`graphicsLayer` 大图、`Brush` 渐变遮罩铺满列表、在 composition 中 new 对象/格式化日期/拼字符串。

### 4.4 分页与数据更新

| 参数 | 值 | 说明 |
|---|---|---|
| 页大小 | 24（6×4） | 一屏多一点，首屏不浪费流量 |
| 触底预取 | 最后可见 index ≥ 已加载数 − 8 | 提前约 1.3 屏发起下一页 |
| 最大保留 | 3 页（72 条）；更早的 drop | 模型内存约 30–50KB，图片由缓存控制 |
| 更新方式 | 新 `List` + 稳定 key，依赖 Compose diff | 不整表重建；配合 `@Immutable` 模型 |
| 焦点保护 | 刷新后若焦点 item 仍在，保持；被删则聚焦相邻项 | 见 §10.4 |
| 触发方式 | `snapshotFlow { lastVisibleIndex }` + `distinctUntilChanged` | 不在 item composition 里读滚动状态 |

首页冷启动顺序（关键）：

1. `setContent` 先画骨架（12 个灰色占位卡，静态，无动画）；
2. 首帧后读 `FileCache` 的上次首页 JSON（45KB，解析 ~20–40ms，IO/后台），立即替换骨架；
3. 同时发网络请求；成功后更新 List + 写缓存；
4. 网络失败保留缓存，顶部显示"离线内容 · 点击重试"，不弹全屏错误。

### 4.5 组合与绑定开销控制

- 模型 `@Immutable data class VodSummary`，列表用稳定 `key`；开启 Compose Strong Skipping（Kotlin 2.0 编译器默认，见 §9）。
- 卡片只接收 `VodSummary` 与稳定 lambda；`onClick` 用 `remember(vod.id) { { onClick(vod.id) } }`，避免每次重组新建闭包。
- 文字样式用常量 `TextStyle`，`BasicText` 不在 composition 里创建 `Paragraph` 以外的对象；颜色/字号固定。
- `AsyncImage` 随组合销毁自动取消请求，不需要手动 `clear`；离开屏幕的 item 由 `LazyVerticalGrid` 回收组合。
- 不做 `SubcomposeAsyncImage`（子组合开销大），用 `AsyncImage`。

---

## 5. 图片管线（Coil 2.7.0，冻结版）

### 5.1 目标尺寸与内存数学

| 用途 | 请求尺寸 | 配置 | 单张内存 | 说明 |
|---|---|---|---|---|
| 列表海报 | **320×450**（固定） | 硬件位图 / RGB565 | 硬件位图按 GPU 计；RGB565 288KB | 封版尺寸；源图仅 270px 宽，允许轻微上采样 |
| 详情海报 | 320×480 | 硬件位图 | 同上 | 只 1 张 |
| 全屏背景 | **LOW 档禁用**；MID 960×540 | ARGB_8888 | 2.07MB | 源图仅 270px 宽，放大必糊；用纯色渐变替代 |
| 频道 logo | 96×96 | RGB565 | 18KB | 直播列表 |
| 占位/失败 | `ColorPainter` | — | 0 | 不用 bitmap 占位 |

预算（LOW，heap 128MB）：

```
Java 堆上限                128MB（largeHeap=false）
├─ Coil 内存缓存           maxMemory/8，且 ≤24MB（1GB 机器约 12–16MB）
├─ 硬件位图                GPU 内存（不占 Java 堆，但 1GB 盒子的 GPU 内存同样紧张）
├─ Compose 组合/状态       20–30MB
├─ 代码/资源/其他          20MB
└─ 安全余量                ~50MB
原生内存（MediaCodec/播放器）另计 30–60MB；浏览 PSS 目标 ≤260MB。
```

**取舍**：`allowHardware(true)` 把位图放到 GPU 内存，避免 Java 堆抖动与上传，但 Mali-450 等老 GPU 的可用显存小；若实测出现黑图/花屏/OOM，LOW 档回退 `allowHardware(false)` + `allowRgb565(true)`（Java 堆 288KB/张，16MB 缓存约 55 张）。`allowRgb565(true)` 在硬件位图开启时不生效，关闭硬件位图后才省 50%。

> 源图宽只有 270px：列表固定请求 320×450 意味着约 1.2–1.5 倍放大。这是封版参数与源图的折中；解码更大尺寸只会更糊且更占内存。若视觉要求更高，需要 t2 提供更大尺寸图源（P1）。
> 带透明通道的 WebP 不会走 RGB565；列表封面通常不透明。若 PSS 超预算，先抽查 `vod_pic` 的 alpha。

### 5.2 Coil ImageLoader 单例（AppGraph 创建）

```kotlin
val imageLoader = ImageLoader.Builder(context)
    .okHttpClient(AppGraph.okHttp)                 // 与 API/播放共用连接池
    .respectCacheHeaders(false)                    // 源站 no-store/无缓存头也写入磁盘
    .memoryCache {
        MemoryCache.Builder(context)
            .maxSizeBytes(
                minOf(Runtime.getRuntime().maxMemory() / 8, 24L * 1024 * 1024).toInt()
            )
            .build()
    }
    .diskCache {
        DiskCache.Builder()
            .directory(File(context.cacheDir, "image_cache"))   // 与播放缓存分离
            .maxSizeBytes(128L * 1024 * 1024)
            .build()
    }
    .allowHardware(true)
    .allowRgb565(true)
    .crossfade(false)
    .dispatcher(Dispatchers.IO.limitedParallelism(2))           // 解码并发上限
    .build()
```

> `limitedParallelism` 在新版编译器可能要求 `@OptIn(ExperimentalCoroutinesApi::class)`；也可用 2 线程的 `Executor.asCoroutineDispatcher()`。首屏先发 API 请求，图片请求在数据到达后才启动；快速滚动暂停预取，避免占满共享 OkHttp 的 6 个连接槽位。

上面是 `AppGraph` 里的完整构建代码；**不要**在 `Application.onCreate` 里调用它。让 `Application` 实现 `coil.ImageLoaderFactory`，由 Coil 在第一次图片请求时懒调用：

```kotlin
class App : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = AppGraph.imageLoader
}
```

这样 `AppGraph.imageLoader` 只在首帧后第一次 `AsyncImage` 请求时创建，避免启动期建磁盘缓存。

**7 天缓存头**：共享 OkHttp 增加响应拦截器，把图片响应的 `Cache-Control` 重写为 7 天：

```kotlin
private val imageCacheHeaders = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (isImageRequest(chain.request())) {
        response.newBuilder()
            .header("Cache-Control", "public, max-age=604800, immutable")
            .removeHeader("Pragma")
            .build()
    } else {
        response
    }
}

// 图片 CDN/路径来自 t2/t3；不要只匹配单一域名
private fun isImageRequest(req: Request): Boolean =
    req.url.host in AppGraph.imageHosts || req.url.encodedPath.contains("/upload/")
```

说明：`respectCacheHeaders(false)` 让 Coil 不因源站 `no-store` 拒绝落盘，真正容量上限由 128MB LRU 控制；7 天头用于共享 OkHttp/中间层缓存一致性。两者不冲突。

### 5.3 请求模板

```kotlin
ImageRequest.Builder(context)
    .data(url)
    .size(320, 450)                 // px，固定；禁止按原图解码
    .precision(Precision.INEXACT)   // 滚动中；首屏 6 张用 EXACT
    .crossfade(false)
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.ENABLED)
    .build()
```

在 Composable 中：

```kotlin
AsyncImage(
    model = request,                 // remember(vod.id, url) 缓存请求对象
    contentDescription = vod.name,
    contentScale = ContentScale.Crop,
    placeholder = ColorPainter(Color(0xFF1C1C1C)),
    error = ColorPainter(Color(0xFF1C1C1C)),
    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)),
)
```

| 参数 | 值 | 理由 |
|---|---|---|
| 内存缓存 | `maxMemory/8`，上限 24MB | 1GB 机器 12–16MB，留足 Compose 与播放内存 |
| 磁盘缓存 | 128MB，独立 `image_cache` | 8GB 盒子可承受；与播放缓存互不挤占 |
| 并发解码 | 2 | 与 §3.3 一致 |
| 请求尺寸 | 320×450 | 封版值；卡片实际约 284×426px |
| 精度 | 首屏 6 张 `EXACT`；滚动 `INEXACT` | 首屏清晰；滚动优先速度 |
| 缓存策略 | `CachePolicy.ENABLED` | 内存+磁盘都启用 |
| crossfade | false | 老 SoC 省一次合成与双图驻留 |
| 变换 | 无（仅 `clip`） | 不产生额外 bitmap |
| 超时 | OkHttp 连接/读取 8s | 源站 TTFB 0.6–1.8s，留余量 |

### 5.4 预取与取消

| 场景 | 策略 |
|---|---|
| 首屏 6 张 | `precision(EXACT)` + `imageLoader.enqueue(request)` 预取（进入组合前/首帧后） |
| 当前可见其余项 | 立即加载，`INEXACT` + `CachePolicy.ENABLED` |
| 下一行 | 滚动空闲 120ms 后按 `INEXACT` 预取最多 6 张，进内存缓存 |
| 快速滚动中 | 取消预取 job；`AsyncImage` 随 item 销毁自动取消；只加载可见项 |
| 屏幕退出 | 组合销毁自动取消；不需要手动 clear |
| 播放页缓冲时 | 暂停图片预取，把 IO/CPU 让给播放器 |
| 失败 | 占位色 + 标题文字；同一 URL 本会话失败 1 次后不再重试（避免 24 张同时重试） |

预取实现：`LaunchedEffect` 监听滚动空闲，逐个 `imageLoader.enqueue(request)`；`Disposable` 收集到列表，`SCROLL_STATE` 变化时 cancel。不要用 `LazyVerticalGrid` 的 `prefetch` 机制做图片预取（会和 item 组合抢线程）。

### 5.5 缓存回收与内存压力

```kotlin
override fun onTrimMemory(level: Int) {
    when (level) {
        TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL -> {
            AppGraph.imageLoader.memoryCache?.clear()
            JankGuard.enterLowMemoryMode()
        }
        TRIM_MEMORY_UI_HIDDEN -> AppGraph.imageLoader.memoryCache?.clear()
        TRIM_MEMORY_COMPLETE -> {
            AppGraph.imageLoader.memoryCache?.clear()
            AppGraph.imageLoader.diskCache?.clear()
        }
    }
}
```

- 不使用 `largeHeap`；`android:largeHeap="false"` 显式声明。
- 图片磁盘缓存清理在首帧后后台执行：LRU 128MB，独立目录，不与 `SimpleCache` 共用。
- 失败/损坏图片：显示占位色 + 片名，不重试、不崩溃；记录一次日志。

---

## 6. 预加载窗口（三类资源分开管）

### 6.1 列表数据窗口

| 资源 | 预取触发 | 窗口 | 上限 |
|---|---|---|---|
| 首页/分类列表 | 最后可见 ≥ 已加载−8 | 当前页 + 后 1 页 | 3 页 / 72 条 |
| 详情 | 点击卡片即请求 | 仅当前详情 | 内存 LRU 8 条 + 文件缓存 |
| 剧集列表 | 详情返回即解析 `vod_play_url` | 全部剧集（纯文本，几百条 <10KB） | 全部保留，LazyGrid 按需组合 |
| 直播分组 | 进入直播页 | 全部种子（首次解析后缓存内存） | 频道文本 <300KB |

### 6.2 图片窗口

- 首屏 6 张 `Precision.EXACT` 预取；其余可见项按 `INEXACT` 加载。
- 滚动中只用 `INEXACT + CachePolicy.ENABLED`；预取最多 6 张（1 行），滚动停止 120ms 后才继续。
- 不再扩大窗口：1GB 盒子上，预取超过 1 行会把可见图片挤出 12–16MB 内存缓存，形成"越滑越白"。

### 6.3 播放预加载

| 场景 | 做什么 | 不做什么 |
|---|---|---|
| 点播详情 | 仅预取海报 | 不预建播放器、不预下载视频 |
| 点播播放页 | 进入即 `prepare()`；用 `SimpleCache` 缓存已看片段 | 不预加载下一集正片（浪费流量与闪存） |
| 剧集 hover/焦点停留 300ms | 用 OkHttp 预取该集 m3u8 文本（约 1–5KB）暖 DNS/TLS | 不下载 ts 分片 |
| 直播换台 | 先 `stop` 再 `setMediaItem` 复用同一个播放器；快速连按 250ms 防抖，只加载最后一次 | 不创建第二个 ExoPlayer（1GB 上会 codec 初始化失败） |
| 直播频道焦点停留 400ms | （P1）预取该频道 m3u8 文本，暖连接 | P0 不做分片预取，避免与当前流抢带宽 |

---

## 7. 播放：Media3 ExoPlayer 配置

### 7.1 依赖与渲染选择

```kotlin
implementation("androidx.media3:media3-exoplayer:1.5.1")
implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
implementation("androidx.media3:media3-ui:1.5.1")
implementation("androidx.media3:media3-datasource-okhttp:1.5.1")  // 传递 OkHttp 4.12.0
implementation("com.squareup.okhttp3:okhttp:4.12.0")             // 与上者对齐，API/播放/Coil 共用
// 不引入：media3-session、dash、rtmp、smoothstreaming、ffmpeg、cast
```

- 输出用 **SurfaceView**（`PlayerView` 默认），不用 TextureView：少一次 GPU 纹理拷贝，老 SoC 上功耗与掉帧更好。
- Compose 侧用 `AndroidView` 承载 `PlayerView`，关闭默认控制器（`setUseController(false)`），自绘 Compose 覆盖层；`setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)`，缓冲提示用静态文字。
- `PlayerView` 设 `isFocusable = false`，D-pad 由 Activity/Compose 根处理，避免 View 焦点与 Compose 焦点打架。
- 音频：`setHandleAudioBecomingNoisy(true)`；不做音频 offload（Media3 1.5.1 没有公开开关，保持默认 PCM 路径）；`.setEnableAudioFloatOutput(false)` 固定 16-bit 输出。

```kotlin
@OptIn(UnstableApi::class)   // media3-ui 的 PlayerView 标注 @UnstableApi
@Composable
fun VideoSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val playerView = remember {
        PlayerView(context).apply {
            setUseController(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            isFocusable = false
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
    }
    AndroidView(factory = { playerView }, update = { it.player = player }, modifier = modifier)
    DisposableEffect(player) {
        onDispose { playerView.player = null }
    }
}
```

`AndroidView` 的 `factory` 只执行一次；`update` 只做幂等的 `player` 绑定，避免每次重组重建 Surface。

### 7.2 缓冲参数（按内容类型）

```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        /* minBufferMs = */ if (isLive) 6_000 else 15_000,
        /* maxBufferMs = */ if (isLive) 15_000 else 30_000,
        /* bufferForPlaybackMs = */ if (isLive) 1_500 else 2_500,
        /* bufferForPlaybackAfterRebufferMs = */ if (isLive) 3_000 else 5_000
    )
    .setTargetBufferBytes(if (isLive) 8 * 1024 * 1024 else 12 * 1024 * 1024)
    .setPrioritizeTimeOverSizeThresholds(false)   // 1GB 机器以内存上限为准，不因时长突破 12MB
    .setBackBuffer(if (isLive) 0 else 10_000, true)
    .build()
```

| 参数 | VOD（1s 分片） | 直播 | 取舍 |
|---|---|---|---|
| minBuffer | 15s | 6s | 点播抗抖动；直播少缓冲降低延迟 |
| maxBuffer | 30s | 15s | 1GB 内存与闪存写放大限制 |
| bufferForPlayback | 2.5s（≈3 片） | 1.5s | 起播速度 vs 二次缓冲 |
| afterRebuffer | 5s | 3s | 弱网稳定优先 |
| targetBufferBytes | 12MB | 8MB | 内存确定性优先；12MB@6Mbps≈16s，仍高于 minBuffer 15s |
| backBuffer | 10s | 0 | 点播回看瞬时；直播不缓存 |
| 起播目标 | ≤2.5s | ≤2.5s | 见 §12 |

### 7.3 轨道与硬解

```kotlin
val renderers = DefaultRenderersFactory(context)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    .setEnableDecoderFallback(true)          // 硬解初始化失败时允许软解/其他解码器
    .setEnableAudioFloatOutput(false)        // 固定 16-bit PCM，避免老设备 float 路径

val trackSelector = DefaultTrackSelector(context).apply {
    setParameters(buildUponParameters()
        .setMaxVideoSize(1920, 1080)
        .setMaxVideoBitrate(6_000_000)
        .setMaxVideoFrameRate(30)            // 参数是 Int（不是 Float）
        .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
        .setAllowVideoMixedMimeTypeAdaptiveness(false)
        .setAllowVideoNonSeamlessAdaptiveness(false))
}

val player = ExoPlayer.Builder(context)
    .setRenderersFactory(renderers)
    .setTrackSelector(trackSelector)
    .setLoadControl(loadControl)
    .setSeekParameters(SeekParameters.CLOSEST_SYNC)   // 1s 分片下几乎瞬时
    .setMediaSourceFactory(sourceFactory)
    .build()
```

| 参数 | 值 | 理由 |
|---|---|---|
| 最大分辨率 | 1920×1080 | 老 SoC 硬解 1080p H.264 是上限；UI 也是 1080p |
| 最大帧率 | 30fps | 部分 1GB 盒子 1080p60 解码溢出，掉帧/花屏 |
| 最大码率 | 6Mbps | 2.4G WiFi 实际 20–40Mbps 下的安全值 |
| 编码偏好 | H.264 | HEVC 硬解在 RK3229 等设备缺失或不稳定 |
| adaptive | 关闭混合 mime / 非无缝 | 避免 codec 在 H.264/HEVC 间切换导致黑屏、音画不同步 |
| Seek | CLOSEST_SYNC | 1s 关键帧，精确 seek 反而慢且易卡 |
| 解码回退 | 开 | 个别机器默认解码器初始化失败，回退可救 |

清晰度菜单（自动/1080p/720p/480p）：只改 `trackSelector.parameters` 的 `maxVideoSize/maxVideoBitrate`，不重建播放器；选择持久化到 SharedPreferences。

**设备能力探测**（首次启动后台一次，结果缓存）：

```kotlin
fun supportsHevc1080p(): Boolean {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    return list.codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
            info.getCapabilitiesForType("video/hevc").videoCapabilities
                .isSizeSupported(1920, 1080)
    }
}
```

HEVC 不支持时：优先选 H.264 变体；若源只有 HEVC，明确提示"当前设备不支持该编码，请切换线路/清晰度"，不要盲目软解 1080p（A53 上必然卡死）。

### 7.4 错误与降级策略

```kotlin
player.addListener(object : Player.Listener {
    override fun onPlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                // 1) 降到 720p 重试一次；2) 换下一线路；3) 提示
                retryAtLowerQualityOrNextSource()
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                // 同一 URL 先换 http/https 一次，再换下一个 URL/线路
                retryWithSchemeSwapOrNextSource()
            }
            else -> showPlaybackErrorPanel()
        }
    }
})
```

| 情况 | 动作 | 用户感知 |
|---|---|---|
| m3u8/分片 404、超时 | 同源换 scheme 1 次 → 下一线路（t3 多源） | 2s 内自动恢复；失败给"换源"面板 |
| 解码器初始化失败 | 720p → 下一线路 → 提示 | 一次自动降级，避免黑屏 |
| 直播 8s 未 READY | 下一个 URL；3 个都失败则提示 | 换台失败可见可操作 |
| 播放中连续 rebuffer | 降码率（720p）+ 减少 buffer 目标 | 减少卡顿 |
| 所有线路失败 | 保留页面，展示"刷新/换线路/返回" | 不退出、不崩溃 |

重试必须带指数退避（1s、2s、4s，上限 15s）和次数上限（每线路 2 次），禁止无上限循环。

### 7.5 起播优化与缓存

- HLS 用 `HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true)`（TS 带 `#EXT-X-INDEPENDENT-SEGMENTS` 时省去逐段解析），失败自动回退。
- 点播使用 `SimpleCache`（128MB，`LeastRecentlyUsedCacheEvictor`），通过 `CacheDataSource.Factory` 接入 HLS 的 `DataSource.Factory`：
  - 只缓存 VOD，**直播不写缓存**（避免闪存写放大与卡顿）；
  - 可用空间 <1.5GB 时不启用或降为 32MB；
  - `SimpleCache` 在首次进入播放页时后台创建，不在 Application 初始化。
- 起播时 `setMediaItem` + `prepare()` + `playWhenReady=true` 一次完成；不要先 `prepare` 再等回调再 `play`。
- 起播前显示静态"正在起播…"覆盖层；首帧后移除（`player.addAnalyticsListener` 监听 `AnalyticsListener.onRenderedFirstFrame`）。
- 预取 m3u8 文本（详情页焦点停留 300ms）：用共享 OkHttp 请求 playlist，暖 DNS/TLS；播放器随后请求可复用连接。P0 可选，P1 必做。

### 7.6 播放器生命周期（内存关键）

| 时机 | 动作 |
|---|---|
| 进入播放页 | 创建 1 个 `ExoPlayer`；`AndroidView` 中绑定 `PlayerView.player` |
| 切换剧集/清晰度 | 复用同一 player，`stop()` + `setMediaItem` + `prepare()`；不重建 |
| 解码错误需重建 | 先 `player.release()`，再 `ExoPlayer.Builder().build()`（1GB 设备同时只能有 1 个 codec 实例） |
| 退到后台/离开播放页 | 立即释放：`DisposableEffect` 把 `playerView.player = null`，屏幕 owner `player.release()`；不做 5s 保留（1GB 内存优先） |
| 快速返回重进 | 重新创建播放器（接受 300–800ms 起播）；如后续做保留实例，必须严格单例并限制 5s 内复用（P1） |
| 应用退出 | 释放 player、`SimpleCache.release()` |

**绝对禁止**：两个 ExoPlayer 同时存在（预加载下一集、画中画、小窗预览）；在 1GB 盒子上极易 `MediaCodec` 初始化失败。

### 7.7 音频与字幕

- 音频焦点：`AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MOVIE)`，`setHandleAudioBecomingNoisy(true)`；音量键交给系统 `setVolumeControlStream(STREAM_MUSIC)`。
- 多音轨：`DefaultTrackSelector` 默认选第一个可播；提供"音轨"菜单（P1）。
- 字幕：HLS 内嵌 WebVTT 由 `PlayerView` 的 `SubtitleView` 渲染；字号用固定 `setFractionalTextSize(0.0533f)`，不加载自定义字体。
- 无音频轨道/设备不支持 AC3：提示换线路，不尝试软解多声道。

### 7.8 直播专项

| 项 | 方案 | 理由 |
|---|---|---|
| 数据 | `assets/live_seed.json`（t2 产出）：分组 → 频道 → 多个 URL；首次进直播页在 IO 解析，内存只保留 name/urls/group | 几百个频道 <300KB，不建 DB、不占启动 |
| 列表 | 纯文字 5 列网格，logo 96×96（可选，失败显示首字），不自动播放 | 直播页打开即用；自动播放会抢解码器与带宽 |
| 换台 | 单播放器 `stop()` → `setMediaItem` → `prepare()`；焦点去抖 250ms | 复用 codec，换台 1.5–2.5s；避免双播放器 |
| 降级 | 当前频道 URL 失败按备用 URL 依次重试（最多 3 个）→ 下一个频道 → 提示 | 直播源失效频繁，必须无感切换 |
| 缓存 | 直播不写 `SimpleCache` | 闪存写放大、无回看价值 |
| 延迟 | 用 HLS 默认 target offset（约 3 个目标时长，通常 6–10s），不强行追帧 | 老设备变速追帧易卡顿、音画不同步 |
| 弱网 | 首次 8s 未 READY 判失败；连续 rebuffer 只降码率不换源；2.4G WiFi 默认 720p 上限 | 避免换台风暴与无限缓冲 |
| 音频 | 无音轨/不支持编码时提示换源，不软解多声道 | 与点播一致 |

---

## 8. 启动耗时

### 8.1 分阶段预算（LOW，冷启动）

| 阶段 | 预算 | 手段 |
|---|---|---|
| 进程创建 → Application.onCreate 开始 | ≤120ms | 系统行为，无法优化 |
| Application.onCreate | ≤80ms | 只做 PerfProfile + AppGraph 引用 + StrictMode(debug)，零 I/O、零网络、零 DB |
| Activity onCreate→onResume | ≤250ms | `setContent` + 简单骨架；主题不含大图；不在此发起网络 |
| 首帧（骨架） | ≤1.5s（累计） | 静态骨架，不启动动画、不等数据 |
| 缓存内容可见 | ≤2.0s | 首帧后后台读缓存并解析 |
| 网络新内容 | ≤3.5s（源站决定） | 缓存先出，网络后到 |
| 温启动（后台返回） | ≤400ms | 不重建 Screen，恢复 LazyGrid 位置与焦点 |

Compose 比 View 多约 100–300ms 首次组合成本（低端机），预算已计入；若实测超 1.5s，优先砍 Material3/动画/首屏图片预取，而不是把逻辑塞回 View。

### 8.2 初始化纪律

```kotlin
class App : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = AppGraph.imageLoader

    override fun onCreate() {
        super.onCreate()
        Trace.beginSection("app_init")
        PerfProfile.init(this)          // 读 memoryClass/isLowRamDevice，纯内存
        AppGraph.install(this)          // 只 new 对象；OkHttp/Coil 懒创建
        Trace.endSection()
        if (BuildConfig.DEBUG) StrictMode.enable()
        // 不做：Coil 预热、SimpleCache 创建、FileCache 清理、源列表加载、预取
    }
}
```

禁止在 `Application.onCreate` / `Activity.onCreate` 出现：

- SharedPreferences 全量读取（首次会同步读文件；确需读取的放到首帧后）；
- 读取 assets 大 JSON（直播种子延后到进入直播页）；
- 创建 `SimpleCache`、OkHttp 连接、Coil ImageLoader（首次图片请求时懒加载）；
- 第三方 SDK 初始化（统计/崩溃上报如有，必须异步且延迟）。

### 8.3 首帧与缓存优先

1. `Theme` 的 `windowBackground` 用纯色 + logo 的 `layer-list`（WebP，<20KB），避免黑屏；`setContent` 用同色背景，视觉连续。
2. 首帧后 `window.setBackgroundDrawable(null)`，消除 DecorView 每帧重复绘制（overdraw）。
3. 骨架：12 个灰色卡片（静态，无 shimmer 动画）；低端机上持续动画 = 持续掉帧与耗电。
4. 首帧后再启动缓存读取与网络请求：

```kotlin
LaunchedEffect(Unit) {
    withFrameNanos { }                 // 等首帧
    renderCachedHome()                 // IO 解析
    refreshFromNetwork()               // 网络
}
```

5. 首页缓存 JSON 直接复用接口原始响应（45KB），不额外序列化；解析后台完成，主线程只替换 List 状态。

### 8.4 测量方法（t5 直接用）

```bash
adb shell am force-stop tv.bfzy
adb shell am start -W -n tv.bfzy/.MainActivity          # TotalTime/WaitTime
adb logcat -b events | grep -E "am_activity_launch_time|Displayed"
adb shell dumpsys gfxinfo tv.bfzy framestats            # 帧时间
adb shell dumpsys meminfo tv.bfzy                        # PSS / Java heap
```

要求：冷启动连续 5 次取中位数；每次前 `force-stop`；设备已开机 10 分钟以上（避开开机风暴）；30 分钟温启动另测。

### 8.5 Baseline Profile（P1，可选收益）

- 仅 Android 7.0+（API 24+）生效；1GB 盒子多为 5.1–7.1，收益覆盖有限。
- 若做：加 `androidx.profileinstaller`（~50KB），在 D3 上用 Macrobenchmark 生成，验证 D1 冷启动是否真降 10% 再保留；不达标就移除。

---

## 9. 包体控制

### 9.1 预算表（release，R8 后，目标 ≤14MB）

| 组成 | 估算 | 控制手段 |
|---|---|---|
| Compose runtime/ui/foundation | 3.5MB | 只引 Foundation + ui；不引 Material3、material-icons、tooling |
| Kotlin stdlib + coroutines | 1.5MB | 不引 kotlin-reflect |
| AndroidX（core/activity/lifecycle） | 1.2MB | 只引必要模块；不引 appcompat/fragment |
| Media3（exoplayer/hls/ui/datasource-okhttp） | 2.3MB | 不引 session/dash/rtmp/ffmpeg/cast |
| OkHttp + Okio | 0.9MB | 共享一个 client；Coil 复用 |
| Coil（base + compose） | 0.5MB | 不引 gif/svg 解码（如可安全排除） |
| 自有代码 + 资源 | 2.5MB | 资源只保留 zh/en、无大图、无自定义字体 |
| **合计** | **≈12.4MB** | 硬上限 16MB |

### 9.2 Gradle 配置

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")     // Kotlin 2.0，无需 composeOptions
}

android {
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        targetSdk = 34
        resourceConfigurations += listOf("zh-rCN", "en")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false   // 不用 java.time/stream，省依赖
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*",
            "DebugProbesKt.bin", "kotlin-tooling-metadata.json"
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Media3 / compose 其余模块见 §7.1 与 lockfile
}
```

- R8 full mode（AGP 8 默认）开启；Compose/Media3/OkHttp/Coil 自带 consumer rules，不写多余 keep。
- Compose Strong Skipping 默认随 Kotlin 2.0 编译器开启；如无，显式 `composeCompiler { enableStrongSkippingMode = true }`。
- 不用 `androidx.appcompat`（平台 `Theme.Material.NoActionBar`）、不用 Fragment、不用 Material3。
- 图标用 vector（API 21 原生支持），启动 banner 320×180 WebP 一张；无多 density PNG。
- 不打包演示视频/图片；直播种子 JSON 控制在 <300KB。
- 无 native lib、无 `largeHeap`；`buildConfig` 保留用于 `BuildConfig.DEBUG`。

### 9.3 包体验证命令

```bash
./gradlew :app:assembleRelease
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer apk file-size app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer files list app-release.apk | sort -k2 -nr | head -30
unzip -l app-release.apk | tail -5
```

超预算处理顺序：先砍 Compose tooling/preview 与 material-icons（若有）→ 再砍 media3-ui（改自研 SurfaceView，省 ~600KB）→ 最后才考虑拆 ABI/动态下发（TV 侧载场景收益低）。

---

## 10. D-pad 焦点与响应优先级（Compose）

### 10.1 焦点模型

- 卡片 `Modifier.clickable(interactionSource, indication = null, onClick)`；`Modifier.onFocusChanged` 必须放在 `clickable` **之前**（Compose 焦点事件节点要求），否则收不到焦点变化。
- 焦点视觉：条件 `Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))`；**不用阴影/elevation**；LOW 档不做缩放，MID/HIGH 可 1.04（90ms）。
- 网格用 `Modifier.focusGroup()` 包住卡片容器，减少跨组焦点搜索；不要给 Header 加 focusable。
- 焦点态禁止无限动画、波纹、粒子；`clickable` 的 indication 设为 null。
- **懒网格越界焦点**：Compose 1.7 的 `LazyVerticalGrid` 对边缘方向键的越界焦点搜索在低端机/特定版本上可能卡住。D1/D2 必须专门回归；若卡住，用 `onPreviewKeyEvent` 在边缘手动 `gridState.scrollToItem(nextIndex)` + `FocusRequester.requestFocus()` 兜底（不要依赖默认 focus search 猜）。

### 10.2 按键映射

| 按键 | 浏览页 | 详情页 | 播放页 | 直播页 |
|---|---|---|---|---|
| DPAD_CENTER / ENTER | 打开详情 | 播放/选集 | 显示/隐藏控制层；控制层内为确认 | 播放选中频道 |
| DPAD_LEFT/RIGHT | 焦点移动 | 焦点移动 | 快退/快进 10s；连按加速 30/60s | 焦点移动 |
| DPAD_UP/DOWN | 焦点移动（首行上移分类栏） | 焦点移动 | 上=剧集面板；下=清晰度/线路 | 焦点移动（跨分组） |
| BACK | 返回/二次退出 | 返回首页 | 先隐藏面板/控制层，再退出播放 | 返回 |
| MENU | （P1）筛选 | 线路/收藏 | 清晰度/音轨/线路 | 分组切换 |
| MEDIA_PLAY_PAUSE | — | — | 播放/暂停 | 播放/暂停 |
| CHANNEL_UP/DOWN | — | — | — | 上/下一个频道 |
| 长按 OK | （P1）快捷操作 | （P1）收藏 | — | — |

### 10.3 连发与合并（低端机必做）

```kotlin
private var lastMoveAt = 0L
override fun dispatchKeyEvent(e: KeyEvent): Boolean {
    if (e.action == KeyEvent.ACTION_DOWN && e.isDpadDirection()) {   // 扩展函数：UP/DOWN/LEFT/RIGHT
        val now = SystemClock.uptimeMillis()
        val minGap = if (JankGuard.isJanky) 90L else 55L
        if (e.repeatCount > 0 && now - lastMoveAt < minGap) return true   // 丢掉积压的重复键
        lastMoveAt = now
    }
    return super.dispatchKeyEvent(e)
}
```

- 方向键重复：最小间隔 55ms（正常）/90ms（掉帧）；只处理最后一次，避免"按 10 次只移动 3 格"的积压感。
- OK/BACK 的 `repeatCount>0` 直接忽略，防连开两页/连退两层。
- 播放 seek：按 `repeatCount` 加速（0–2 次 10s，3–5 次 30s，≥6 次 60s），去抖 400ms 后提交 seek；按 BACK 取消。
- 直播换台：焦点变化去抖 250ms，只加载最后一次选中的频道。
- Compose 内 `onPreviewKeyEvent` 只用于播放覆盖层，不拦截音量键。

### 10.4 焦点记忆与恢复

- 每个 Screen 维护 `FocusStore`：`screenKey -> focusedItemId`（如 `vodId`）+ `LazyGridState` 位置。
- 返回时：数据就绪后 `gridState.scrollToItem(index)`（立即滚动，不要 `animateScrollToItem`），再 `withFrameNanos {}` 后 `FocusRequester.requestFocus()`；找不到则聚焦最近项。
- 恢复实现建议：只保留一个 `restoreFocusRequester`，在 `index == restoreIndex` 的卡片上挂 `Modifier.focusRequester(...)`，避免为每个 item 建 `FocusRequester`。
- 焦点卡片额外挂 `Modifier.bringIntoViewRequester(...)`，`onFocusChanged { if (it.isFocused) scope.launch { requester.bringIntoView() } }`，保证滚动后可见。
- 播放页返回浏览页：恢复"正在播放"的剧集卡片焦点，而不是回到顶部。
- 数据刷新时：若焦点 item 仍在，保持不动；被删除则聚焦其相邻项。
- 初始焦点：首页第一张卡片，首帧后请求；不要在 composition 阶段直接 `requestFocus()`。

### 10.5 视觉反馈与动效

| 元素 | 方案 | 理由 |
|---|---|---|
| 焦点框 | 2dp 白描边（`Modifier.border`） | 只重绘该 item，无额外图层 |
| 缩放 | LOW 关闭；MID/HIGH 1.04 / 90ms | 缩放触发额外合成，低端 GPU 掉帧 |
| 页面切换 | 无转场（直接换 `screen` 状态） | 省 150–300ms 与双视图同时绘制 |
| 骨架 | 静态灰色块 | 不做 shimmer |
| 加载指示 | 静态文字"加载中" | 不做无限旋转动画 |
| 进度条 | 播放页 seek 时才显示，静止时隐藏 | 减少持续 draw |

### 10.6 响应优先级与卡顿保护

优先级从高到低：

1. **输入与焦点**（主线程，单帧 ≤8ms）；
2. **播放渲染/解码**（Media3 线程与 codec）；
3. **交互网络请求**（列表/详情/playlist）；
4. **可见图片加载/解码**；
5. **图片预取**（仅滚动空闲）；
6. **缓存写入、日志、清理**。

`JankGuard`：用 `Choreographer.postFrameCallback` 采样帧间隔，连续 3 帧 >32ms 或单帧 >100ms 时进入 500ms 降级：

- 暂停所有图片预取；
- 关闭焦点缩放；
- 降低图片请求优先级（不再 `EXACT` 抢占）；
- 暂停非必要 UI 更新（如"更新时间"标签）。

滚动中（`LazyGridState.isScrollInProgress`）同样暂停预取；播放器 `STATE_BUFFERING` 时暂停预取。

---

## 11. 内存与稳定性

| 项 | 策略 |
|---|---|
| Heap | 不启用 largeHeap；`memoryClass` 驱动 PerfProfile |
| 图片 | Coil 内存缓存 `maxMemory/8` ≤24MB；`onTrimMemory` 立即清；硬件位图计入 GPU 内存 |
| 播放器 | 单实例；离开播放页立即释放；`PlayerView.player = null` 再 release |
| 列表 | LazyGrid 回收组合；模型只留 7 个字段；`@Immutable` + 稳定 key |
| 缓存 | 图片 128MB 独立目录；API 文件缓存 96MB；`SimpleCache` 128MB，三者不共用 |
| 泄漏点 | 无静态 Activity/View 引用；Compose 组合随屏幕销毁；Coil 随组合取消；Player 在 onStop 释放 |
| 大对象 | 详情 JSON 流式解析，不保留原始 164KB 字符串 |
| OOM 兜底 | 捕获解码失败显示占位；低内存模式降缓存、关硬件位图 |

Soak 测试（t5）：首页滚动 10 分钟 → 详情 20 次 → 点播 10 集切换 → 直播换台 50 次 → 返回，循环 30 分钟；每 5 分钟 `dumpsys meminfo`，Java heap 增长 ≤20MB，无 OOM/ANR。

---

## 12. 验收标准（t5 直接执行）

设备矩阵：

| 编号 | 设备 | 用途 |
|---|---|---|
| D1 | Amlogic S905W / 1GB / Android 7.1 / 1080p | 主门槛 |
| D2 | RK3229 / 1GB / Android 5.1 / 720p | 兼容与下限 |
| D3 | S905X2 / 2GB / Android 9 | 回归对照（不作为主门槛） |

| ID | 验收项 | 通过标准 | 方法 |
|---|---|---|---|
| A1 | 冷启动首帧 | D1 TotalTime 中位数 ≤2.5s（含首帧） | `am start -W` ×5 |
| A2 | 缓存首屏 | force-stop 后二次启动 ≤2.0s 看到上次内容 | 录屏/帧时间 |
| A3 | 滚动流畅 | 60s 连续滚动掉帧 ≤10%，无 >250ms 帧 | `dumpsys gfxinfo` |
| A4 | 浏览内存 | PSS ≤260MB，Java heap ≤120MB | `dumpsys meminfo` |
| A5 | 播放内存 | PSS ≤340MB，30 分钟无增长 >20MB | soak + meminfo |
| A6 | APK | ≤14MB（硬上限 16MB） | `apkanalyzer` |
| A7 | 点播起播 | ≤2.5s 首帧（20Mbps） | 日志/录屏 |
| A8 | 直播换台 | ≤2.5s 首帧，连按 10 次不卡死 | 手工/脚本 |
| A9 | 弱网/坏源 | 单线路失败自动换源 ≤3s；全部失败有可操作面板，不崩溃 | 断网/改 hosts |
| A10 | 图片失败 | 显示占位与片名，列表可继续滚动 | 断图床 |
| A11 | D-pad | 连按 50 次方向键无焦点丢失/积压；播放返回后焦点还原 | 手工 |
| A12 | 稳定性 | 30 分钟循环无 OOM/ANR/崩溃 | soak |
| A13 | 主线程纪律 | debug StrictMode 无 disk/network violation | StrictMode 日志 |
| A14 | 依赖白名单 | 无 Material3/Leanback/Glide/Room/kotlinx-serialization；Compose/Coil 版本锁定 | `gradle dependencies` |
| A15 | 图片参数 | 列表请求 320×450、crossfade 关、硬件位图/RGB565 开、缓存上限符合 §5 | 代码检查 + 日志 |

---

## 13. 实施顺序（t4 按此推进）

| 阶段 | 内容 | 完成门槛 |
|---|---|---|
| P0-1 | 工程骨架、Compose 主题、PerfProfile、AppGraph、共享 OkHttp、Coil 单例 | 冷启动 ≤2.0s 空壳；APK ≤10MB |
| P0-2 | MacCms 列表流式解析 + FileCache + Home LazyGrid + Coil 卡片 | 缓存首屏 ≤2.0s；滚动掉帧 ≤10% |
| P0-3 | 详情页（流式解析 play_url、剧集网格） | 详情打开 ≤800ms（缓存）/≤2.5s（网络） |
| P0-4 | 播放页（AndroidView + HLS、硬解、错误降级、覆盖层、D-pad） | 起播 ≤2.5s；切换集 ≤1.5s |
| P0-5 | 直播页（种子、分组、换台复用播放器） | 换台 ≤2.5s |
| P0-6 | 焦点记忆、返回恢复、连发合并、JankGuard | A11 通过 |
| P0-7 | 接入 t3 导入源（SourceStore 适配、结尾斜杠规范化） | 导入/切换源可用，坏源不崩溃 |
| P1 | 播放器 SimpleCache、playlist 预取、Baseline Profile、音轨菜单 | 按收益决定保留 |

每个阶段结束跑 A1–A4；不达标不进入下一阶段（性能债后补成本最高）。

---

## 14. 风险与回退

| 风险 | 影响 | 预案 |
|---|---|---|
| Compose 首帧比 View 慢 100–300ms | 启动超预算 | 骨架优先、无 Material3、首屏只预取 6 张；最后手段：首屏只渲染静态骨架，延迟真实列表组合 |
| 硬件位图占 GPU 内存（Mali-450） | 黑图/花屏/OOM | LOW 档回退 `allowHardware(false)` + `allowRgb565(true)`；保留开关在 PerfProfile |
| Coil 磁盘缓存过大/损坏 | 存储不足/加载失败 | 128MB LRU + 独立目录；损坏时删除 `image_cache` 重建；可用空间 <800MB 降为 32MB |
| 源站 API 直连 400 / 缺斜杠 307 降级 | 无法取数/明文 | API 基址与 headers 运行时可配置（t2/t3 提供）；强制补结尾斜杠；启动时校验 |
| CDN TTFB 0.6–3.8s、海报 270px 小图 | 首屏慢、详情背景糊 | 缓存优先 + 骨架；卡片 320×450 固定；禁用全屏背景/模糊 |
| 老设备 WebP 解码异常 | 图片空白 | 占位色 + 片名；失败不重试；必要时 t2 提供 JPEG 备用地址（P1） |
| HEVC/1080p60 解码失败 | 黑屏/花屏 | 偏好 H.264、1080p30、6Mbps；解码失败降 720p→换源 |
| 1GB 内存 OOM | 崩溃 | LOW 档参数；清缓存；禁背景大图；largeHeap=false |
| 多 codec 实例冲突 | 初始化失败 | 全局单播放器；重建前必 release；禁止预加载第二播放器 |
| Compose 焦点与 PlayerView 冲突 | 遥控不可用 | PlayerView 不 focusable；D-pad 由 Activity/Compose 根处理；D1/D2 手工回归 |
| 明文 HTTP 源 | 播放失败 | `usesCleartextTraffic=true`；仅允许 http/https，拒绝 file/content |
| Android 4.4 设备 | 无法安装 | 明确 minSdk 21；如业务必须覆盖，另行评估 ExoPlayer 2.19 方案 |

---

## 15. 附录

### 15.1 参数速查

```
图片：Coil 2.7；列表 320×450；memory=maxMemory/8 且≤24MB；disk=128MB 独立 image_cache
      allowHardware=true + allowRgb565=true；crossfade=false；首屏 6 张 EXACT，滚动 INEXACT
列表：LazyVerticalGrid；6 列(1080p)/5 列(720p)；页 24；预取阈值 剩余 8；保留 3 页；无 item 动画
视频：1080p30 / 6Mbps / H.264；VOD buffer 15/30/2.5/5s，12MB；直播 6/15/1.5/3s，8MB
播放器：单实例；Seek=CLOSEST_SYNC；SurfaceView；解码回退开；音频 float 关、offload 不启用
启动：App.onCreate≤80ms；首帧≤1.5s；缓存内容≤2.0s；网络≤3.5s
包体：≤14MB；minSdk21/target34；只 zh/en；无 Material3/Leanback/Glide/Room/serialization
D-pad：重复键 55ms（卡顿时 90ms）；焦点框 2dp；LOW 无缩放；返回恢复焦点
线程：图片解码 2、API/网络 6/4、JSON 解析 2、文件 1，全部后台优先级
```

### 15.2 禁止事项（代码评审红线）

- Material3、Leanback、Fragment、navigation-compose、Glide、Room、Hilt、Paging3；
- 嵌套 `LazyRow`/`LazyColumn`/`verticalScroll`、`animateItem`、`Modifier.shadow/elevation`；
- 在 composition 中做网络/磁盘/JSON/图片解码；`runBlocking`；`largeHeap`；
- 同一时刻两个 ExoPlayer；不 release 就重建播放器；
- 全屏大图/模糊/圆角 BitmapTransformation、GIF、自定义字体；
- 无限重试、无超时请求、硬编码 API 根域；缺结尾斜杠的 API 基址；
- 在 `Application.onCreate` 读文件、建 DB、建缓存、创建 ImageLoader。

### 15.3 交给 t4 的最小文件清单

```
docs/PLAN_ARCHITECTURE.md          // 本文（参数与契约）
app/.../core/perf/PerfProfile.kt   // 参数唯一来源
app/.../core/net/Http.kt           // OkHttp + 超时 + UA + TLS + 图片缓存头
app/.../core/image/Images.kt       // Coil ImageLoader 单例 + 请求模板
app/.../data/maccms/MacCmsApi.kt   // JsonReader 流式解析 + 结尾斜杠规范化
app/.../data/cache/HomeCache.kt    // stale-while-revalidate
app/.../feature/home/HomeScreen.kt // LazyVerticalGrid + 焦点
app/.../feature/player/PlayerScreen.kt // AndroidView(PlayerView) + 单播放器 + 降级
docs/PERF_ACCEPTANCE.md            // t5 验收记录（由 t5 产出）
```

> 本文所有"目标值"是设计预算，不是实测结论；t5 必须按 §12 在 D1/D2 上复测并记录偏差，偏差超过 20% 需回到本文修订参数。
