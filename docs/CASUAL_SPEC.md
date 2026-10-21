# 「随便听听看看」规格（音乐电台 + 短视频）

一级入口：导航 **随便**（副标题「随便听听看看」），两个分段：**听**（电台/音乐）/ **看**（短视频）。
目标：打开就能出声/出画面，不用挑片、不用登录、不用选源。

## 1. 数据源（已实测，2026-10-21）

### 1.1 听：电台 / 音乐流

| 源 | 接口 | 实测结果 |
| --- | --- | --- |
| Radio Browser | `https://de1.api.radio-browser.info/json/stations/search?countrycode=CN&limit=50&hidebroken=true&order=clickcount&reverse=true` | ✅ 返回 CN 电台，含 `CNR-1 中国之声`（MP3 64k）、`CCTV-13 新闻伴音`（m3u8）、`凤凰卫视资讯台`（m3u8） |
| Radio Browser（国际音乐） | `.../json/stations/search?tag=chillout&limit=50` 等 tag 检索 | ✅ 同接口，按 `tag=jazz/chillout/pop` 拉音乐台 |
| 内置精选（离线可用） | `assets/casual_radio.json` | SomaFM 系列直链实测可用：`https://ice6.somafm.com/groovesalad-256-mp3`（ambient/downtempo）等 |

- 播放：直接复用直播播放器（音频流，`MediaItem` 无视频轨 → 显示电台封面 + 台名 + 码率）。
- 电台列表字段：名称 / 国家 / tag / codec / bitrate / 主 URL + 备用 URL。
- 需带 `User-Agent`（部分台拒默认 UA）；m3u8 音频流与 MP3 直链都要支持（ExoPlayer 原生支持，无需额外依赖）。

### 1.2 看：短视频

| 源 | 接口 | 实测结果 |
| --- | --- | --- |
| B站热门 | `https://api.bilibili.com/x/web-interface/popular?ps=20&pn=1` | ✅ code=0，返回 title/pic/bvid/duration/stat |
| B站排行榜 | `https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all` | ✅ 100 条；`rid=3` 为音乐区、`rid=1` 动画、`rid=4` 游戏、`rid=36` 知识 |
| 取播放地址 | `x/player/pagelist?bvid=` 拿 cid → `x/player/playurl?bvid=&cid=&qn=64&fnval=16&platform=html5&high_quality=1` | ✅ code=0，返回 `durl[0].url`（mp4 直链，实测 192 清晰度可播），带 `Referer: https://www.bilibili.com/` |

- 未登录只能拿到 360/480 档，**不诱导登录**，能播就行；需要更高画质由用户在设置里填自己的 Cookie（可选，默认不做）。
- 请求头必须带 `Referer: https://www.bilibili.com/` 与浏览器 UA，否则 403。

## 2. 交互

### 2.1 听

- 进入即播上次的电台（无则播内置精选第一个），全屏暗场 + 大号台名 + 码率 + 简易电平条（C 模式关闭动效）。
- `CH+ / CH-` 或 `UP/DOWN` 切台，`OK` 暂停/继续，`LEFT/RIGHT` 调音量（走系统音量）。
- 自动连播：一个台断流 → 600ms 后自动试备用 URL → 再失败切下一个台，**不弹错、不停**。
- 后台音频：切到别的页面继续出声（做前台服务，通知栏可控，API 23 用 `Notification` + `MediaSession` 兼容实现）。
- 显示「正在播放」的曲目：若流带 ICY 元数据（`Icy-MetaData: 1`）就解析显示，拿不到就显示台名（不编造）。

### 2.2 看

- 分段 Tab：推荐 / 音乐 / 动画 / 游戏 / 知识 / 生活（对应 B站 rid）。
- 卡片：16:9 封面 + 时长角标 + UP 主 + 播放量 + 标题 2 行；网格 4 列（大字号档 3 列）。
- `OK` 直接全屏播放，进入即从头播；`DOWN` 出「下一条」条，播完自动下一条（连播开关默认开）。
- 播放器 HUD 与点播一致（倍速/进度/音画校准），但不显示「切源/选集」（短视频没有多集）。
- 失败处理：`playurl` 403/超时 → 降一档 qn 重试一次 → 仍失败则跳到下一条并记录。

## 3. 工程契约

```kotlin
interface CasualRepository {
    suspend fun radios(page: Int = 1): Result<List<RadioStation>>
    suspend fun clips(tab: CasualTab, page: Int = 1): Result<List<ShortClip>>
    suspend fun resolveClip(bvid: String): Result<MediaUrl>   // 内部完成 cid→playurl
}

data class RadioStation(val id: String, val name: String, val tag: String,
                        val codec: String, val bitrateKbps: Int,
                        val urls: List<String>, val logo: String?)

data class ShortClip(val bvid: String, val title: String, val pic: String,
                     val durationSec: Int, val owner: String, val views: Long)

enum class CasualTab(val rid: Int, val label: String) {
    RECOMMEND(0, "推荐"), MUSIC(3, "音乐"), ANIME(1, "动画"),
    GAME(4, "游戏"), KNOWLEDGE(36, "知识"), LIFE(160, "生活")
}
```

- 缓存：电台列表 6 小时、短视频榜单 10 分钟（文件缓存，离线可看上次结果）。
- 限流保护：同一接口连续 3 次失败 → 熔断 10 分钟，界面提示「稍后再试」而不是空白。
- 性能：列表懒加载 20 条/页；封面按 320×180 请求；音频播放缓冲 8MB、视频沿用点播参数。

## 4. 明确边界（写进设置页说明，不糊弄）

1. 内容来自公开接口（Radio Browser / B站公开榜单），**仅个人观看收听，不做下载/缓存到本地**。
2. 未登录 B站只能 360/480 档，画质有限；不内嵌登录、不存 Cookie（除非用户手动填）。
3. 电台可用性由第三方决定，随时可能失效；失效自动跳过，不保证每个台都能听。
4. 不接抖音/快手（无公开接口，抓私有接口属违规且极易失效）。

## 5. 验收

| 项 | 标准 |
| --- | --- |
| 听 | 进入 3 秒内出声；切台 20 次不出错；断流自动切台 |
| 看 | 热门/音乐/知识三个 Tab 各拉到 ≥20 条；点开 3 条不同视频均能播 |
| 连播 | 听 30 分钟不断；看 5 条自动连播 |
| 离线 | 断网进入显示上次缓存列表 + 明确提示，不白屏 |
| 性能 | 1GB 盒子音频播放 PSS ≤200MB；切台 ≤1.5s |
