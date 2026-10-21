# 自定义播放源导入协议与多站聚合（PLAN_SOURCES）

| 项 | 值 |
| --- | --- |
| 版本 | `tvapp.source.v1` 基线 + `tvapp.source.v1.1` 增量（本文 v1.1，向后兼容） |
| 日期 | 2026-10-21 |
| 作者 | importer |
| 下游 | `engineer`（t4 实现）、`qa`（t5 验收）、`architect`（t1 性能预算对齐） |
| 交付物 | 本文 + `assets/sources.schema.json`（JSON Schema 2020-12，含 v1.1）+ `assets/sources.example.json`（可直接导入的示例合集，混合 v1/v1.1）+ `tools/validate_sources.py`（L1+L2 校验与负例自检） |
| 兼容性 | v1.1 新增字段**全部可选**；v1 老配置不改一行仍通过校验；缺省行为与 v1 完全一致（不拦截广告、不跳片头、画质自动、无频道元数据） |
| 实测依据 | 2026-10-21 对 `macapi1.com`（bfzy1.tv 的 MacCMS 网关）与 `bfzyapi.com`（站点标准 MacCMS API）的抓包实测，见 §8.2 |

## 0. TL;DR（给实现者的 13 条硬约束）

1. 导入文件只有一个顶层格式：`schema ∈ {"tvapp.source.v1", "tvapp.source.v1.1"}`，`kind ∈ {maccms, m3u, bundle}`。**JSON 对象**；`bundle` 内含 `sources[]`，是"单文件订阅"。
2. 一切 URL 只允许 `https://` / `http://`（`http` 仅限用户显式导入并给出警告）；禁止 `file://`、`content://`、`javascript:`、`intent://`。
3. `id` 为稳定主键（`^[a-z0-9][a-z0-9._-]{1,63}$`），重复导入按 `id` + 端点指纹去重，**自然幂等**。
4. 校验分 4 层：L0 文件 → L1 结构 → L2 语义 → L3 网络探测（异步、不阻塞导入）；L4 内容探测只在"首次使用"时做。
5. 导入返回 `ImportReport`（`accepted / rejected[{index,code,message}] / warnings / probes`），UI 必须能逐条显示拒绝原因。
6. bfzy1.tv 的主源是 `https://macapi1.com/maccms/json/baofeng/`（**结尾斜杠必须保留**，否则 307 降级到 http）。
7. 列表页**只准**用 `ac=list`；`ac=detail` 仅用于详情/搜索/批量 ids。实测每 20 条：list `45.4 KB`（gzip `11.6 KB`）vs detail `164.4 KB`（gzip `29.0 KB`）。
8. 所有请求必须 `Accept-Encoding: gzip` + 支持 `If-None-Match`（实测返回 304）。播放请求带 `User-Agent` 与 `Referer: https://bfzy1.tv/`。
9. 多站聚合搜索是 `Flow<AggregatedPage>` 增量流：并发度 3、单源 3.5 s 截止、首批结果 400 ms 即可发射、单源失败不阻塞整体；游标推进而非全量重查。
10. 去重键：源级 = 规范化端点指纹；频道级 = `tvg-id` 或归一化台名；作品级 = `doubanId` 或 `归一化标题 + 年份 ±1 + 分类`；剧集级 = 归一化 URL。
11. 播放失败必须自动回退：同作品其它站源 → 同集其它 URL → 主源重试（§7.6），回退预算 2 s，超时直接报错不要转圈。
12. 引入任何新依赖需保持 `docs/TOOLCHAIN.md` 的基线（minSdk 21 / OkHttp 4.12 / Media3 1.4.1）；图片库以 t1 为准（见 §15 待冻结项）；本协议解析器用 `org.json` 即可零依赖实现。
13. **v1.1 新增字段全部 optional**：`epgUrls`、`adBlock`、`skipIntroSeconds`/`skipIntroCue`、`maxQuality`/`preferredCodec`、m3u 源的 `channels[]`（含 `desc`/`tvgId`/`logo`/`group`）。老 `tvapp.source.v1` 配置无需改动；实现缺省 = v1 行为。字段语义与消费方见 §3.5。

---

## 1. 范围与边界

### 1.1 本文定义

- 可导入的源配置 JSON：MacCMS API 站源、m3u 直播源、单文件订阅（合集）。
- 字段语义、校验规则、错误码、去重与合并策略。
- 失败重试、熔断、降级与缓存契约。
- 多站聚合搜索 / 详情 / 播放回退的接口契约（Kotlin 签名级）。
- bfzy1.tv 站（macapi1 MacCMS 网关）的字段映射与解析器伪代码。
- 与本协议相关的低端盒子性能预算（HTTP/解析/内存），详细渲染优化见 `docs/PLAN_ARCHITECTURE.md`。

### 1.2 本文不定义

- UI/焦点/D-pad 交互（t1）。
- 内置直播源的采集结果与可用性名单（t2 `docs/PLAN_LIVE.md` + `assets/live_seed.json`）——本协议只定义"如何导入外部 m3u"，内置种子走内部 `Channel` 导入器（§9.6 对齐）。
- 播放器内部解码/缓冲参数（Media3 调优见 t1）。

### 1.3 术语

| 术语 | 含义 |
| --- | --- |
| 源（Source） | 一个可聚合的数据提供方：MacCMS 站、m3u 直播列表、或订阅合集 |
| 适配器（SourceAdapter） | 把某类源翻译成统一模型的代码单元，每个源一个实例 |
| 作品（Work） | 一部电影/剧/动漫/综艺，跨站去重后的逻辑实体，键为 `workKey` |
| 站内条目（RemoteItem） | 某源站内的一条 `vod`，键为 `(sourceId, remoteId)` |
| 线路（PlayGroup） | 源站 `vod_play_from` 的一个分组，如 `bfzym3u8` |
| 剧集（Episode） | 线路下的一集/一段，`name + url` |
| 频道（Channel） | 直播台，跨源去重后含多个 `url` 备选 |

---

## 2. 统一领域模型

实现建议放在 `core/model`，与网络层解耦（纯 Kotlin data class，无 Android 依赖，便于单测）。

```kotlin
enum class MediaCategory { MOVIE, TV, ANIME, VARIETY, SHORT, SPORTS, OTHER }

data class MediaSummary(
    val workKey: String,          // 跨站去重键，见 §5.3
    val sourceId: String,
    val remoteId: String,         // MacCMS vod_id
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val category: MediaCategory = MediaCategory.OTHER,
    val remoteTypeId: Int? = null, // 源站 type_id（映射到 t1 VodSummary.typeId）
    val genre: String? = null,    // 源站 type_name，如“欧美综艺”
    val remarks: String? = null,  // “更新至第10集”“HD”
    val score: Float? = null,
    val updatedAt: Long? = null,  // epoch millis
    val playGroups: Int = 0,      // 已知线路数（详情后填）
    val doubanId: Long? = null
)

data class MediaDetail(
    val summary: MediaSummary,
    val synopsis: String? = null,
    val actors: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val area: String? = null,
    val language: String? = null,
    val totalEpisodes: Int? = null,
    val completed: Boolean = false,
    val groups: List<PlayGroup> = emptyList()
)

data class PlayGroup(
    val name: String,             // bfzym3u8 → 展示名“暴风线路”
    val episodes: List<Episode>
)

data class Episode(
    val index: Int,               // 1-based
    val name: String,             // “第1集”“HD”
    val url: String,              // 绝对 http(s) 地址
    val headers: Map<String, String> = emptyMap()
)

data class Channel(
    val key: String,              // 去重键，见 §5.2
    val name: String,
    val group: String,            // 归一化分组：央视频道/卫视频道/地方频道/其他
    val logo: String? = null,
    val tvgId: String? = null,
    val epgId: String? = null,
    val desc: String? = null,     // v1.1：频道简介（详情面板），来自 channels[].desc
    val urls: List<ChannelUrl> = emptyList()  // 主用在前，≤ 4 条
)

data class ChannelUrl(
    val url: String,
    val quality: String? = null,  // 4K/1080P/HD/SD
    val sourceId: String,
    val verifiedAt: Long? = null,
    val latencyMs: Int? = null
)

data class SearchCursor(
    val sourcePages: Map<String, Int> = emptyMap(), // 每个源下次要取的远端页码
    val seenWorkKeys: Set<String> = emptySet(),
    val delivered: Int = 0
)
```

**序列化要求**：网络 JSON 的字段名与模型解耦，解析器必须**忽略未知字段**（MacCMS 站字段漂移很常见，实测 bfzy1 详情 92 个字段、标准 MacCMS 83 个字段，实现只取 ~30 个）。

### 2.1 `SourceSpec`（导入后的内存表示，v1.1 已扩展）

```kotlin
enum class SourceKind { MACCMS, M3U, BUNDLE }
enum class Flavor { MACJSON, MACCMS }
enum class QualityCap { AUTO, P1080, P720, P480 }   // 与导入字段 auto/1080p/720p/480p 对应
enum class CodecPref { AUTO, H264, HEVC }

data class SourceSpec(
    val schema: String,                 // "tvapp.source.v1" | "tvapp.source.v1.1"
    val kind: SourceKind,
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Int = 6000,
    val maxConcurrent: Int = 2,
    // maccms
    val baseUrl: String? = null,
    val flavor: Flavor = Flavor.MACJSON,
    val fallbackBaseUrls: List<String> = emptyList(),
    val pageSize: Int = 20,
    val categories: Map<MediaCategory, List<Int>> = emptyMap(),
    // m3u
    val url: String? = null,
    val inline: String? = null,
    val refreshIntervalMin: Int = 360,
    val filters: M3uFilters = M3uFilters(),
    val dedupe: M3uDedupe = M3uDedupe(),
    val maxBytes: Int = 2 * 1024 * 1024,
    // ---- v1.1：全部有默认值，缺省 = v1 行为 ----
    val epgUrls: List<String> = emptyList(),
    val adBlock: AdBlockSpec = AdBlockSpec(),
    val skipIntroSeconds: Int = 0,
    val skipIntroCue: Boolean = false,
    val maxQuality: QualityCap = QualityCap.AUTO,
    val preferredCodec: CodecPref = CodecPref.AUTO,
    val channels: List<ChannelOverride> = emptyList()
)

data class AdBlockSpec(
    val enabled: Boolean = true,
    val domains: List<String> = emptyList(),      // 精确域或 "*.example.com"
    val urlKeywords: List<String> = emptyList()   // path+query 子串，大小写不敏感
)

data class ChannelOverride(
    val name: String,
    val urls: List<String>,                       // url/urls 解析后统一成列表
    val group: String? = null,
    val logo: String? = null,
    val tvgId: String? = null,
    val epgId: String? = null,
    val desc: String? = null
)
```

> `SourceSpec` 是导入产物的唯一内存形态；`bundle` 在导入时被展开为多条 `SourceSpec`，运行时不再有 bundle 实例（bundle 的 `id` 只作为 `bundleId` 记录在子源上）。

---

## 3. 导入文件规范 `tvapp.source.v1` / `v1.1`

### 3.1 三种形态

| `kind` | 用途 | 典型来源 | 关键字段 |
| --- | --- | --- | --- |
| `maccms` | 点播站源（MacCMS JSON API） | 站方 API 地址、源分享文本 | `baseUrl` / `flavor` / `categories` |
| `m3u` | 直播源（M3U/M3U8 播放列表） | IPTV 订阅链接、本地文件、粘贴文本 | `url` / `inline` / `filters` / `epgUrls` / `channels[]`（v1.1） |
| `bundle` | 单文件订阅：一次导入多个源 | 自用合集、团队分发、频道商 | `version` / `sources[]` / 合并策略 |

**三种形态都是 JSON 对象**，共用顶层字段：

| 字段 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `schema` | string | 是 | `tvapp.source.v1` 或 `tvapp.source.v1.1` | 版本闸门；未知值整体拒绝（v1.1 向后兼容，见 §3.5.6） |
| `kind` | string | 是 | `maccms`/`m3u`/`bundle` | 适配器选择 |
| `id` | string | 是 | `^[a-z0-9][a-z0-9._-]{1,63}$` | 稳定主键；bundle 内的源亦唯一 |
| `name` | string | 是 | 1..40 列 | 展示名，允许中文 |
| `enabled` | bool | 否（默认 true） | — | 用户可停用而不删除 |
| `priority` | int | 否（默认 100） | 0..1000，**数值小者优先**（0 最高，与 `SourceConfig` 一致） | 聚合排序与回退顺序 |
| `headers` | object | 否 | ≤ 8 项，键值均 ≤ 512B | 建议 `User-Agent`、`Referer` |
| `timeoutMs` | int | 否 | 1000..15000，默认 6000 | 单请求硬超时 |
| `maxConcurrent` | int | 否 | 1..4，默认 2 | 该源并发上限（低端盒子别调大） |
| `note` | string | 否 | ≤ 200 列 | 备注，UI 显示 |
| `epgUrls` | string[] | 否（v1.1） | ≤ 3 项，http(s)，`uniqueItems` | XMLTV 节目单（支持 `.xml.gz`），任何 kind 均可用，见 §3.5.1 |
| `adBlock` | object | 否（v1.1） | `{enabled, domains[≤50], urlKeywords[≤100]}` | 源级广告拦截规则，供 OkHttp 拦截器使用，见 §3.5.2 |
| `skipIntroSeconds` | int | 否（v1.1） | 0..600，默认 0 | 片头固定跳过秒数（仅点播生效），见 §3.5.3 |
| `skipIntroCue` | bool | 否（v1.1） | 默认 false | 是否按 `#EXT-X-CUE-OUT` 自动跳过，见 §3.5.3 |
| `maxQuality` | string | 否（v1.1） | `auto`/`1080p`/`720p`/`480p`，默认 `auto` | 该源画质上限，见 §3.5.4 |
| `preferredCodec` | string | 否（v1.1） | `h264`/`hevc`/`auto`，默认 `auto` | 编码偏好（不硬过滤，见 §3.5.4） |

### 3.2 `kind = "maccms"` 字段

```json
{
  "schema": "tvapp.source.v1",
  "kind": "maccms",
  "id": "bfzy1",
  "name": "暴风影音",
  "enabled": true,
  "priority": 10,
  "baseUrl": "https://macapi1.com/maccms/json/baofeng/",
  "flavor": "macjson",
  "fallbackBaseUrls": ["https://bfzyapi.com/api.php/provide/vod/"],
  "headers": { "User-Agent": "okhttp/4.12.0", "Referer": "https://bfzy1.tv/" },
  "timeoutMs": 6000,
  "maxConcurrent": 2,
  "pageSize": 20,
  "categories": {
    "MOVIE": [20], "TV": [30], "ANIME": [39], "VARIETY": [45], "SHORT": [58], "SPORTS": [53]
  },
  "classTtlMin": 1440,
  "adBlock": {
    "enabled": true,
    "domains": ["doubleclick.net", "googlesyndication.com", "*.admaster.com.cn"],
    "urlKeywords": ["/ad/", "/ads/", "/adv/", "promo="]
  },
  "skipIntroSeconds": 90,
  "skipIntroCue": true,
  "maxQuality": "1080p",
  "preferredCodec": "auto",
  "note": "官方网关；备用站 bfzyapi.com（v1.1 示例）"
}
```

| 字段 | 类型 | 必填 | 约束/默认 | 说明 |
| --- | --- | --- | --- | --- |
| `baseUrl` | string | 是 | http(s)，长度 ≤ 512，**保留原样（含结尾斜杠）** | API 根；拼接参数时用 `baseUrl + "?" + query`，不自动补/删斜杠 |
| `flavor` | string | 否 | `macjson`（默认）/`maccms` | `macjson` = 扩展网关（含 `work_id`/`class` 树，`ac=list&t=0` 取分类）；`maccms` = 标准 MacCMS JSON |
| `fallbackBaseUrls` | string[] | 否 | ≤ 3 项，http(s) | 主端点失败时的降级端点（顺序探测），见 §6.3 |
| `pageSize` | int | 否 | 10..50，默认 20 | 与远端 `limit` 对齐，聚合分页按此推算 |
| `categories` | object | 否 | 键 ∈ MediaCategory，值为 type_id 数组 | 缺省用 `class` 树一级映射（§8.4）；显式配置优先 |
| `classTtlMin` | int | 否 | 30..10080，默认 1440 | 分类树缓存 TTL |
| `apiPathMode` | string | 否 | `query`（默认）/`path` | 少数站用 `/api.php/provide/vod/at/json/` 形式，保留扩展位 |

> **兼容性**：`flavor=maccms` 时，若 `?ac=list&t=0` 不返回 `class`（标准站常见），分类树回退为内置 9 大类映射，不视为错误（仅 `warning: CLASS_FALLBACK`）。

### 3.3 `kind = "m3u"` 字段

```json
{
  "schema": "tvapp.source.v1",
  "kind": "m3u",
  "id": "live-iptv",
  "name": "IPTV 直播源",
  "enabled": true,
  "priority": 50,
  "url": "https://example.com/iptv.m3u",
  "inline": null,
  "refreshIntervalMin": 360,
  "epgUrls": ["https://example.com/epg.xml.gz"],
  "headers": { "User-Agent": "okhttp/4.12.0" },
  "filters": {
    "includeGroups": ["央视", "卫视"],
    "excludeGroups": ["成人", "测试"],
    "excludeNameRegex": "(?i)test|备用"
  },
  "dedupe": { "byTvgId": true, "byName": true, "mergeUrls": true },
  "channels": [
    {
      "name": "CCTV-1 综合",
      "urls": ["https://example.com/live/cctv1.m3u8", "https://example.com/live/cctv1-b.m3u8"],
      "group": "央视频道",
      "tvgId": "cctv1",
      "epgId": "cctv1",
      "logo": "https://example.com/logo/cctv1.png",
      "desc": "中央电视台综合频道，新闻、专题与大型晚会主频道。"
    }
  ],
  "maxQuality": "720p",
  "maxBytes": 2097152,
  "timeoutMs": 8000
}
```

| 字段 | 类型 | 必填 | 约束/默认 | 说明 |
| --- | --- | --- | --- | --- |
| `url` | string | 与 `inline` 二选一 | http(s)，≤ 512 | 远端订阅；支持 `.m3u`/`.m3u8`/纯文本 |
| `inline` | string | 与 `url` 二选一 | ≤ 512 KB | 直接粘贴的 m3u 文本（用户"粘贴导入"） |
| `refreshIntervalMin` | int | 否 | `0` 或 `30..10080`，默认 360 | 自动刷新周期；0 = 不自动刷新 |
| `epgUrls` | string[] | 否 | 公共字段（任意 kind），≤ 3 项 | XMLTV EPG，支持 `.xml.gz`，失败不影响频道导入（§3.5.1） |
| `filters.includeGroups` | string[] | 否 | ≤ 20 项 | 命中即保留（分组名包含匹配） |
| `filters.excludeGroups` | string[] | 否 | ≤ 20 项 | 命中即丢弃（优先于 include） |
| `filters.excludeNameRegex` | string | 否 | ≤ 128 字符，非法正则 → 拒绝 | 台名过滤 |
| `dedupe.byTvgId` | bool | 否（默认 true） | — | 有 `tvg-id` 时以它为主键 |
| `dedupe.byName` | bool | 否（默认 true） | — | 无 `tvg-id` 时按归一化台名合并 |
| `dedupe.mergeUrls` | bool | 否（默认 true） | — | 同频道多 URL 合并为备选 |
| `maxBytes` | int | 否 | 64 KB..8 MB，默认 2 MB | 下载/读取上限（防 OOM） |
| `channels` | object[] | 否（v1.1） | ≤ 5,000 项，见下表 | 手工频道定义/覆盖，与 m3u 解析结果按 §5.2 键合并（§3.5.5） |

**`channels[]` 字段（v1.1，命名与 §2 `Channel` 模型一致，不另起名字）**：

| 字段 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `name` | string | 是 | 1..60 | 频道名 |
| `url` / `urls` | string / string[] | 二选一（至少一个） | 各 http(s)，`urls` ≤ 8 条 | 播放地址；多地址按序回退 |
| `group` | string | 否 | 1..20 | 分组；缺省按 §9.3 归一（其他） |
| `logo` | string | 否 | http(s)，≤ 512 | 台标 |
| `tvgId` | string | 否 | 1..128 | 频道 ID（EPG/去重键用，优先于台名） |
| `epgId` | string | 否 | 1..128 | EPG 匹配 ID；缺省回退 `tvgId` |
| `desc` | string | 否 | ≤ 200 字 | 频道简介（详情面板显示，见 `PLAYBACK_SPEC.md` §3） |

**编码**：优先 UTF-8；检测到 BOM/非法序列时按 GB18030 重试一次（很多国内源是 GBK）；仍失败 → `E_ENCODING`。

### 3.4 `kind = "bundle"`（单文件订阅）

```json
{
  "schema": "tvapp.source.v1",
  "kind": "bundle",
  "id": "team-bundle",
  "name": "团队源合集",
  "version": 3,
  "updatedAt": "2026-10-21T10:00:00Z",
  "refreshIntervalHour": 24,
  "signature": "sha256:3f2a…",
  "merge": "byId",
  "sources": [
    { "schema": "tvapp.source.v1", "kind": "maccms", "id": "bfzy1", "name": "暴风影音", "baseUrl": "https://macapi1.com/maccms/json/baofeng/" },
    { "schema": "tvapp.source.v1", "kind": "m3u", "id": "live-iptv", "name": "IPTV", "url": "https://example.com/iptv.m3u" }
  ]
}
```

| 字段 | 类型 | 必填 | 约束/默认 | 说明 |
| --- | --- | --- | --- | --- |
| `version` | int | 否 | ≥ 0 | 订阅版本，仅用于展示与冲突提示 |
| `updatedAt` | string | 否 | ISO-8601 | 发布时间 |
| `refreshIntervalHour` | int | 否 | 0..168，默认 24 | 自动拉取新版周期；0 = 手动 |
| `signature` | string | 否 | `sha256:<hex>` | 对 `sources` 规范化 JSON 取哈希；有则校验，不符 → 拒绝（`E_SIGNATURE`） |
| `merge` | string | 否 | `byId`（默认）/`replace` | 合并策略，见 §5.1 |
| `sources` | object[] | 是 | 1..50 项，每项须为合法 maccms/m3u（**不允许嵌套 bundle**） | 子源共用父级缺省值 |

**父子缺省**：子源未写 `priority`/`timeoutMs`/`headers` 时继承 bundle 顶层同名字段（bundle 顶层允许出现 `priority`/`headers` 作为批量默认值）。

**单文件订阅的三种投递方式**（同一格式）：

1. 本地文件（SAF 选择，`content://` 经 `ContentResolver` 读取，≤ 8 MB）；
2. 远端 URL（`url` 字段不在顶层——用户以"订阅链接"方式添加时，把下载内容解析为本格式）；
3. 粘贴文本（与本地文件同解析路径）。

> 可选兼容层（P1，不阻塞 t4）：识别 TVBox/猫影视 `{"sites":[...],"lives":[...]}` 旧格式，转换为 `bundle`，转换失败给 `warning: LEGACY_CONVERTED`，不作为 `v1` 契约。

### 3.5 v1.1 扩展字段语义（全部可选）

v1.1 是**向后兼容的小版本**：`schema` 可为 `tvapp.source.v1` 或 `tvapp.source.v1.1`；所有新字段都有缺省值，缺省时行为与 v1 完全一致。新增字段不会被 bundle 子源隐式继承（避免"父级开广告拦截、子源莫名丢请求"），需要逐源声明。

#### 3.5.1 EPG（`epgUrls`）

| 项 | 规定 |
| --- | --- |
| 值 | XMLTV（`<tv><channel/><programme/>`）地址数组，≤ 3 项，http(s)，去重 |
| 压缩 | 支持 `.xml.gz` 与 `Content-Encoding: gzip`；按**魔数 `1f 8b`** 判定而非只看扩展名，再包 `GZIPInputStream` |
| 大小 | 解压后 ≤ 8 MB（超限截断并按错误处理，不 OOM）；流式 `XmlPullParser`，禁止 DOM 全量加载 |
| 刷新 | 进入直播页拉一次 + 每 30 min 后台刷一次；结果落本地缓存（离线可看），失败静默保留旧数据 |
| 匹配 | `channel@id` == 频道 `epgId ?: tvgId`；匹配不到显示"暂无节目单"，不显示空白 |
| 失败 | EPG 失败**不影响**源导入、频道列表与播放（仅 `probe.epg=FAILED`） |

任何 `kind`（maccms/m3u/bundle 子源）都可以声明 `epgUrls`；m3u 源最常用，点播源声明后可用于"正在播"类运营推荐。

#### 3.5.2 广告拦截（`adBlock`）

```json
"adBlock": {
  "enabled": true,
  "domains": ["doubleclick.net", "googlesyndication.com", "*.admaster.com.cn"],
  "urlKeywords": ["/ad/", "/ads/", "/adv/", "promo="]
}
```

| 字段 | 类型 | 默认 | 约束 | 语义 |
| --- | --- | --- | --- | --- |
| `enabled` | bool | `true` | — | `false` 时整组规则失效（保留规则便于临时关闭） |
| `domains` | string[] | `[]` | ≤ 50，小写，精确域或 `*.` 通配域，禁带 scheme/端口/路径 | 命中 host 或其子域即拦截 |
| `urlKeywords` | string[] | `[]` | ≤ 100，1..64 字符，非空白，禁止 `*` | 对 URL 的 **path + query** 做大小写不敏感子串匹配 |

匹配算法（O(domains + keywords)，对每个请求一次，弱机无感）：

```
host = url.host.lowercase()
if host in allowlist: return false                     // 见下"自伤保护"
if domains.any { ruleMatches(host, it) }: return true  // 精确: host==rule || host.endsWith("."+rule)
if urlKeywords.any { "${url.path}?${url.query}".lowercase().contains(it.lowercase()) }: return true
return false
```

**自伤保护（P0，必须实现）**：

1. 源自身的 `baseUrl`、`fallbackBaseUrls`、`url`（m3u 订阅）、`epgUrls` 的 host 自动加入 allowlist；导入校验阶段即检测冲突并拒绝（`E_ADBLOCK`）。
2. 当前播放的 `Episode.url` 及其**实际播放成功的 CDN host** 加入会话级 allowlist（广告域与 CDN 混用时不至于把正片拦了）。
3. 只拦**媒体/资源请求**，不拦 API JSON；命中时抛 `AdBlockedException : IOException`（不用空 200，避免 ExoPlayer 把空分片当损坏循环重试）。
4. 拦截日志只进 debug（host + 命中规则），release 不落盘；命中计数按源汇总，供"高级"面板展示。

与 `PLAYBACK_SPEC.md` §7 的三层一致：L1 无网页（天然无广告）、L2 即本字段、L3 见 §3.5.3。默认值为空规则 = 不拦截（与 v1 行为一致）。

#### 3.5.3 片头跳过（`skipIntroSeconds` / `skipIntroCue`）

| 项 | 规定 |
| --- | --- |
| 适用 | **仅点播**（MacCMS 剧集、VOD HLS）；直播忽略（HLS live 无从"片头"） |
| `skipIntroSeconds` | 0..600，默认 0；起播后自动 `seekTo(min(seconds, duration × 0.5))`；用户手动 seek 后不再自动跳 |
| `skipIntroCue` | 默认 false；true 时解析 master/媒体播放列表中的 `#EXT-X-CUE-OUT:<duration>` |
| cue 语义 | 若 cue 出现在播放列表起点且 `duration ≤ 600`，视为前贴片广告，自动跳到 cue 结束；cue 出现在中段则不在起播时处理（避免误跳正片） |
| 叠加规则 | 两者同时开启时取**较大**跳过量，且只跳一次；无 cue 的源只按秒数盲跳 |
| 可观测 | 日志记录 `skipped=intro, seconds=N, by=cue 或 seconds`；UI 不提示（静默），设置页可关 |

> 保守原则：`PLAYBACK_SPEC.md` §7 明确"不承诺 100% 去广告"。默认 0 秒 = 不跳；只有用户/源作者显式配置才跳，避免误伤正片开头。

#### 3.5.4 画质与编码（`maxQuality` / `preferredCodec`）

| 字段 | 值 | 映射（Media3 `TrackSelectionParameters`） |
| --- | --- | --- |
| `maxQuality` | `auto` | 不额外限制（仍受 t1 `PerfProfile` 上限约束） |
| | `1080p` | `maxVideoSize = 1920×1080`，`maxVideoBitrate = 6 Mbps` |
| | `720p` | `maxVideoSize = 1280×720`，`maxVideoBitrate = 3 Mbps` |
| | `480p` | `maxVideoSize = 854×480`，`maxVideoBitrate = 1.5 Mbps` |
| `preferredCodec` | `auto` | 不干预，按设备能力排序 |
| | `h264` | 优先 `video/avc`；无 H.264 轨道时回退 auto |
| | `hevc` | 优先 `video/hevc`；**设备无 HEVC 硬解时回退 auto**（不硬过滤，避免黑屏） |

**生效规则**：

1. 有效上限 = `min(源 maxQuality, 设备档位上限)`；设备档位来自 t1 `PerfProfile`（LOW 档 1080p30/6Mbps）。
2. 只改 `TrackSelectionParameters`，**不重建播放器**（`PLAYBACK_SPEC.md` §6 要求切档无黑屏）。
3. 手动选清晰度时，UI 只展示 ≤ 上限的档位；用户选低不选高。
4. 掉帧自救（t1/`PLAYBACK_SPEC.md` §1.3）可临时再降一档，但不改源配置。

#### 3.5.5 直播频道元数据（m3u 的 `channels[]`）

`channels[]` 是**手工补充/覆盖**，不替代 m3u 解析：

```
1) 解析 m3u（§9.4）→ channelsFromM3u
2) 对每个 override:
     key = tvgId ? "tvg:"+tvgId : "name:"+groupKey+"|"+normalizeChannelName(name)
     命中 → 用 override 的非空字段覆盖/补齐（urls 合并去重，desc/logo/group 仅填空，除非 override 显式给值）
     未命中 → 作为新频道加入（sourceId = 本源）
3) 与其它源频道按 §5.2 合并
```

- 字段名与 §2 `Channel` 对齐（`name/urls/group/logo/tvgId/epgId` + v1.1 新增 `desc`），**不复用 `channelName` 之类别名**。
- 同一源内 `(group, 归一化 name)` 或 `tvgId` 重复 → 导入拒绝（`E_CHANNEL`），避免频道列表出现双份。
- `desc` 只用于详情面板（`PLAYBACK_SPEC.md` §3），≤ 200 字，不参与搜索排序。
- 单源 `channels` ≤ 5,000；单频道 URL ≤ 8（播放回退顺序 = 数组顺序）。

#### 3.5.6 版本兼容矩阵与消费方

| 场景 | 行为 |
| --- | --- |
| v1 配置（无新字段） | 通过校验；行为 = v1（无拦截/无跳片头/画质 auto/无频道元数据） |
| v1.1 配置，老 App（只认 v1） | `schema` 不在白名单 → `E_SCHEMA` 拒绝并提示"请升级 App"；**不得静默降级丢弃新字段** |
| v1.1 配置，新 App | 正常导入；未知的**未来**字段仍按严格模式拒绝（`E_FIELD`），需升 minor 版本 |
| bundle 子源混合 v1/v1.1 | 允许；各自独立生效，父级不继承 v1.1 字段 |
| 新字段消费方 | `epgUrls`→LiveRepository/EPG 解析；`adBlock`→OkHttp 拦截器；`skipIntro*`/`maxQuality`/`preferredCodec`→Player；`channels`→LiveRepository 合并 |

> 工具链：`assets/sources.schema.json` 是唯一契约；`tools/validate_sources.py --selftest` 覆盖 v1 老负例 9 条 + v1.1 新负例 8 条 + 正例 3 条。

---

## 4. 校验（4 层）与错误码

### 4.1 分层

| 层 | 时机 | 内容 | 失败影响 |
| --- | --- | --- | --- |
| L0 文件 | 解析前 | 大小、UTF-8/GB18030、JSON 合法性、顶层类型 | 整体拒绝 |
| L1 结构 | 解析时 | 必填、类型、枚举、正则、数组长度上限 | 整体/逐条拒绝（bundle 内逐条） |
| L2 语义 | 解析后 | id 重复、URL 形状、范围、分类键、正则编译、嵌套/自引用 | 整体/逐条拒绝 |
| L3 网络探测 | 导入后异步 | `ac=list&t=0` 或 m3u HEAD/GET 首 4 KB | **不拒绝**，仅标记 `unverified` |
| L4 内容探测 | 首次进入该源 | 一页 detail + 播放列表首行 | 失败 → 该源自动降级/提示，不删除 |

**关键原则**：导入永不因网络失败而失败（离线可用），只把源标记为 `probe=pending/failed` 并在 UI 显示。用户手动"测试连接"走同一探测函数。

**严格/宽松边界（易错点）**：

- **导入文件（本地 JSON）必须严格**：未知顶层/子级字段 → `E_FIELD`（schema `unevaluatedProperties:false`），避免用户以为配置生效但拼错键名。
- **远端响应（MacCMS/m3u）必须宽松**：未知字段忽略（实测 bfzy1 详情 92 字段、标准站 83 字段，漂移是常态），缺可选字段用默认值，**不因字段缺失/类型漂移整体失败**。

### 4.2 上限（防 DoS/OOM）

| 对象 | 上限 |
| --- | --- |
| 导入文件 | 8 MB（本地）/ 4 MB（远端下载） |
| bundle.sources | 50 |
| headers | 8 项，单值 ≤ 512 B，禁 CR/LF（防头注入） |
| m3u 行数 | 20,000 行 / 2 MB（先按 `maxBytes` 截断再解析） |
| 单源频道数 | 5,000 |
| 广告规则 | `domains` ≤ 50 条/源；`urlKeywords` ≤ 100 条/源，单条 1..64 字符 |
| 频道元数据 `channels[]` | ≤ 5,000 条/源；单频道 URL ≤ 8 条；`desc` ≤ 200 字 |
| EPG | ≤ 3 条/源；解压后 ≤ 8 MB；节目条目只保留当前/下 2 小时（≤ 2,000） |
| 片头跳过 | `skipIntroSeconds` 0..600 秒；每次播放只跳一次 |
| 单作品剧集数 | 2,000 |
| 文本字段 | name ≤ 40 列、note ≤ 200 列、synopsis ≤ 2,000 字 |
| 重定向 | ≤ 3 跳，且最终 scheme ∈ {http, https} |

### 4.3 错误码（`ImportReport.rejected[].code`）

| code | 触发 | 文案示例 |
| --- | --- | --- |
| `E_JSON` | JSON 语法错误 | 第 12 行附近解析失败 |
| `E_SIZE` | 超过大小上限 | 文件超过 8 MB |
| `E_ENCODING` | UTF-8/GB18030 均失败 | 无法识别文件编码 |
| `E_SCHEMA` | `schema` 缺失/未知 | 不支持的协议版本 |
| `E_KIND` | `kind` 非法 | 未知源类型 "tvbox" |
| `E_ID` | id 缺失/不匹配正则/重复 | 源 id 重复：bfzy1 |
| `E_NAME` | 名称缺失/超长 | 源名称过长 |
| `E_URL` | URL 非法/协议不允许/含凭据 | 仅支持 http(s) 地址 |
| `E_FIELD` | 其它字段类型/范围错误 | `timeoutMs` 需在 1000..15000 |
| `E_REGEX` | 过滤正则编译失败 | 正则语法错误 |
| `E_LIMIT` | 超出数组/数量上限 | 合集内源超过 50 个 |
| `E_NESTED` | bundle 嵌套 bundle | 合集不能包含合集 |
| `E_SIGNATURE` | 签名不符 | 订阅签名校验失败 |
| `E_ADBLOCK` | 广告规则格式非法，或会拦截源自身 API/降级端点/EPG | 广告规则会拦截源自身接口 |
| `E_CHANNEL` | `channels[]` 非法：重复、缺 URL、协议不允许 | 频道 "CCTV-1" 重复 |
| `E_EPG` | EPG 地址超过 3 条或非 http(s) | EPG 地址必须是 http(s) |
| `W_CLASS_FALLBACK` | 分类树不可用，回退内置映射 | （warning） |
| `W_HTTP` | 使用明文 http | （warning） |
| `W_UNVERIFIED` | 探测失败/离线 | （warning） |

### 4.4 导入报告契约

```kotlin
data class ImportReport(
    val accepted: List<SourceSpec>,
    val rejected: List<Rejection>,        // index, code, message, field?
    val warnings: List<Warning>,          // code, message, sourceId?
    val probes: Map<String, ProbeResult>, // L3 结果，可能为空（异步补）
    val durationMs: Long
)

data class Rejection(val index: Int, val code: String, val message: String, val field: String? = null)

data class ProbeResult(
    val sourceId: String,
    val state: ProbeState,                // OK / DEGRADED / FAILED / UNVERIFIED
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,
    val classes: Int? = null,             // maccms: 分类数
    val channels: Int? = null,            // m3u: 频道数
    val checkedAt: Long
)
```

**探测判据**：

- `maccms`：`GET baseUrl?ac=list&t=0`，HTTP 200 且 `code==1` 且 `class` 数组非空 → OK；能连通但分类为空 → DEGRADED；超时/非 2xx → FAILED。
- `m3u`：`GET url` 首 4 KB 含 `#EXTM3U` → OK；可下载但无 `#EXTM3U` → DEGRADED（按纯文本 URL 列表尝试）；失败 → FAILED。
- 探测超时 4 s，**不重试**（避免导入卡顿），失败后按 §6 退避重探。

---

## 5. 去重与合并

### 5.1 源级（重复导入 / 订阅更新）

**端点指纹**：

```
fingerprint = sha256(
  kind + "|" + normalizeUrl(baseUrl 或 url)
)
normalizeUrl: scheme 小写、host 小写、去默认端口、路径保留（含结尾斜杠）、query 参数按键排序、去 fragment
```

规则：

1. 同 `id` 已存在 → **更新**（保留用户字段：`enabled`、`priority` 若用户改过、`headers` 用户覆盖项），不新建。
2. 不同 `id` 但指纹相同 → 视为同一源，提示"已存在相同地址的源"，`accepted` 复用旧 id（`W_DUPLICATE_ENDPOINT`）。
3. bundle 更新：`merge=byId` 按 id 合并新增/更新/删除；`merge=replace` 先删该 bundle 上次导入且未被用户修改的源。
4. 删除保护：用户手动改过的源（`userTouched=true`）不被订阅更新删除，只标记 `orphaned`。

### 5.2 频道级（m3u 合并）

```
if tvgId != null && dedupe.byTvgId        -> key = "tvg:" + tvgId.lowercase()
else if dedupe.byName                     -> key = "name:" + groupKey + "|" + normalizeChannelName(name)
else                                      -> key = "url:" + normalizeUrl(url)
```

`normalizeChannelName`：全角→半角、去空格与 `【】[]()（）`、去尾部画质词（`高清/超清/蓝光/4K/1080P/720P/HD/FHD`）、去 `-`/`_`/`.` 分隔符、`CCTV5+` 保号 → `cctv5+`、大小写统一。

合并结果：同 key 的多个 URL **全部保留**为备选（按 `verifiedAt` 降序、画质权重降序），上限 4 条；超出提示 `W_TOO_MANY_URLS` 并丢弃最旧。分组取首次出现的 `group-title` 归一值。

**v1.1 `channels[]` 参与同一套键**：手工频道先并入 m3u 解析结果（同 key 覆盖/补齐非空字段、URL 合并去重，§3.5.5），再做跨源合并。导入期已拒绝同源内 `(group, 归一化 name)` 与 `tvgId` 重复（`E_CHANNEL`），运行期只处理跨源去重；`desc` 不参与任何键。

### 5.3 作品级（多站聚合搜索）

```
if doubanId != null && doubanId > 0      -> workKey = "db:" + doubanId
else                                     -> workKey = "t:" + normalizeTitle(title) + "|" + (year ?: "") + "|" + category
```

`normalizeTitle`：去空白/标点、全角转半角、小写、去季标（`第二季`/`Season 2`/`S02`）、去常见后缀（`国语版`/`粤语`/`HD`/`4K`）。年份比较容差 ±1（不同站发布年不一致）。**分类必须相同**，否则不合并（防止"电影/剧集同名"误并）。

合并结构（内存态，不落库）：

```kotlin
data class MergedWork(
    val workKey: String,
    val primary: MediaSummary,             // priority 数值最小者
    val alternatives: Map<String, MediaSummary>, // sourceId -> 摘要
    val sourceCount: Int
)
```

### 5.4 剧集级

同一线路内：`normalizeUrl`（scheme 统一为 https 若两者都支持、去 query 中无意义参数）去重；名称相同且 URL 不同 → 保留 URL 不同者（重发版），名称追加 `(2)`。

### 5.5 冲突策略总表

| 场景 | 决策 |
| --- | --- |
| 同 id 重复导入 | 更新配置，保留用户开关/优先级 |
| 同端点不同 id | 提示并复用旧 id |
| 频道同名不同台（如"新闻频道"） | 分组参与 key，跨组不合并 |
| 作品同名不同分类 | 不合并 |
| 作品同名同年不同片（翻拍） | 合并但 UI 标注"可能不是同一部"（`ambiguous=true`），详情页并列展示各站简介 |
| 播放源优先级 | `priority` 数值小者主用；同 priority 按 `latencyMs` 升序 |

---

## 6. 失败重试、熔断与降级

### 6.1 重试矩阵

| 错误 | 重试 | 策略 |
| --- | --- | --- |
| 连接超时 / 读超时 / 连接重置 | 是 | 最多 2 次，300 ms → 900 ms（±20% 抖动） |
| HTTP 5xx | 是 | 同上 |
| HTTP 429 | 是 | 优先 `Retry-After`（秒），否则 1 s，最多 1 次 |
| HTTP 4xx（除 429） | 否 | 立即失败（配置/权限问题，重试无意义） |
| JSON 解析失败 | 是（1 次） | 可能是截断响应；再失败 → `E_PARSE` |
| 业务码 `code != 1` | 否 | 映射为 `SourceError.Business(code,msg)` |
| 用户取消 | 否 | 立即抛 `CancellationException`，清理连接 |

总预算：列表/搜索 6 s、详情 6 s、探测 4 s、播放地址校验 2 s。**重试不突破总预算**。

### 6.2 熔断状态机（每源独立）

```
CLOSED --连续 3 次失败--> OPEN(冷却 60s) --冷却结束--> HALF_OPEN
HALF_OPEN --探测成功--> CLOSED
HALF_OPEN --失败--> OPEN(冷却 ×2，上限 10min)
```

- 失败计数只统计网络/5xx/解析类；`E_BUSINESS`（站内 404）不计入。
- OPEN 期间该源在聚合搜索中直接跳过并计入 `partial`，UI 灰显"暂不可用"。
- 冷却时间持久化，进程重启不重置（避免冷启动连续打爆弱站）。

### 6.3 多端点降级（bfzy1 实例）

```
1) 主端点   https://macapi1.com/maccms/json/baofeng/   (flavor=macjson)
2) 备用端点 https://bfzyapi.com/api.php/provide/vod/    (flavor=maccms)
3) 本地缓存（stale）
```

- 降级触发：主端点 2 次连续失败（网络/5xx）→ 切备用；备用成功则**在当前会话内保持**，并在 `sourceState` 记录 `activeBaseUrl`；30 min 后试探回主。
- 端点能力差异：备用端点 `ac=list` 只返回 7 个字段（无封面/简介），且**无分类树**；切换后列表卡片降级为"标题+备注+占位图"，详情仍取 `ac=detail&ids=`。UI 不得因缺封面崩坏。
- 降级/回切必须写日志（不含用户数据），便于 QA 复现。

**已知端点坑（实测）**：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `GET .../baofeng?ac=list` 返回 307 且 `Location` 为 `http://` | 网关对缺尾斜杠的路径做 scheme 降级重定向 | 配置里**总带尾斜杠**；请求不做 URL 规范化去斜杠；若仍 307 → 不跟随跨 scheme 重定向，直接切备用端点 |
| `GET https://macapi1.com/api.php/provide/vod/` 返回 `{"code":400,"message":"无法从主域名识别数据库"}` | 这是多站网关，必须用站点专属前缀 `/maccms/json/baofeng/` | 仅使用 `baseUrl` 中的完整前缀，不自行拼 `/api.php` |
| 播放链接 302/403 | CDN 校验 Referer/UA | 播放器注入 `headers`（默认 `Referer: https://bfzy1.tv/`、`User-Agent`） |
| API 403（`python-urllib` 默认 UA） | Cloudflare 拦非浏览器/无 UA 客户端 | `Http` 层强制 `User-Agent: okhttp/4.12.0` + `Referer`（实测 403→200） |

### 6.4 缓存与 stale-while-revalidate

| 数据 | TTL | 说明 |
| --- | --- | --- |
| 分类树 `class` | 24 h | 冷启动优先读缓存，后台刷新 |
| 列表页（分类/首页） | 10 min | 翻页缓存按 `(sourceId,type,page)` |
| 搜索页 | 30 min | 键 = `sha1(keyword|category|page)` |
| 详情（含线路/剧集） | 12 h | 播放地址单独 TTL 1 h（链接可能换 host） |
| m3u 频道表 | 6 h（或 `refreshIntervalMin`） | 刷新失败保留旧表并提示"使用缓存" |
| EPG | 12 h | 失败静默 |

策略：先返回缓存（标记 `stale=true`）→ 后台刷新 → 成功后静默替换并 `notifyDataSetChanged`；刷新失败不覆盖缓存。所有缓存单条 ≤ 512 KB，超出不缓存（防 detail 页 8 KB×N 膨胀）。

### 6.5 离线

- 无网络：点播列表显示缓存 + "离线"横幅；直播显示上次频道表，点击播放才报错。
- 导入：允许离线导入、探测标 `UNVERIFIED`，联网后首个使用时机补探（每次最多 1 个源，避免启动抖动）。

---

## 7. 多站聚合搜索接口契约

### 7.1 接口定义（`core/source`）

```kotlin
interface SourceAdapter {
    val spec: SourceSpec
    fun supports(category: MediaCategory): Boolean

    /** 分类列表（m3u 源返回空） */
    suspend fun classes(): Result<List<ClassNode>>

    /** 分类浏览，page 从 1 开始 */
    suspend fun browse(category: MediaCategory, page: Int): Result<SourcePage>

    /** 搜索，page 从 1 开始；实现内部完成重试/熔断判定 */
    suspend fun search(keyword: String, page: Int): Result<SourcePage>

    /** 详情（含线路/剧集） */
    suspend fun detail(remoteId: String): Result<MediaDetail>

    /** 轻量探活，供熔断 HALF_OPEN */
    suspend fun probe(): ProbeResult
}

data class SourcePage(
    val sourceId: String,
    val page: Int,
    val items: List<MediaSummary>,
    val hasMore: Boolean,
    val total: Int?,
    val elapsedMs: Long
)

sealed interface SourceError {
    data class Timeout(val sourceId: String, val budgetMs: Long) : SourceError
    data class Network(val sourceId: String, val cause: String) : SourceError
    data class Http(val sourceId: String, val status: Int) : SourceError
    data class Business(val sourceId: String, val code: Int, val message: String) : SourceError
    data class Parse(val sourceId: String, val message: String) : SourceError
    data class CircuitOpen(val sourceId: String, val until: Long) : SourceError
    data class Disabled(val sourceId: String) : SourceError
}

interface SearchAggregator {
    /**
     * 增量流：先到的源立即发射 partial 页；全部结束或超预算发射最终页（done=true）。
     * 取消协程即取消所有在途请求。
     */
    fun search(query: SearchQuery): Flow<AggregatedPage>

    /** 分类聚合浏览：语义同 search（bfzy1 等站支持 type_id 浏览） */
    fun browse(category: MediaCategory, cursor: SearchCursor): Flow<AggregatedPage>

    /** 合并详情：并发取各站 detail，返回主源详情 + 备选源线路 */
    suspend fun resolve(work: MergedWork, preferredSourceId: String? = null): Result<MergedDetail>
}

data class SearchQuery(
    val keyword: String,
    val category: MediaCategory? = null,
    val cursor: SearchCursor = SearchCursor(),
    val pageSize: Int = 20,          // 展示条数，非远端条数
    val sourceFilter: Set<String>? = null
)

data class AggregatedPage(
    val items: List<MediaSummary>,   // 已去重合并，按 §7.4 排序
    val cursor: SearchCursor,        // 必须原样回传才能"加载更多"
    val partial: List<SourceError>,  // 本次未成功的源
    val done: Boolean,
    val elapsedMs: Long
)
```

**v1.1 播放策略与广告拦截契约（实现放 `core/playback`）**：

```kotlin
data class PlaybackPolicy(
    val maxQuality: QualityCap = QualityCap.AUTO,
    val preferredCodec: CodecPref = CodecPref.AUTO,
    val skipIntroSeconds: Int = 0,      // 仅点播
    val skipIntroCue: Boolean = false
)

/** 有效策略 = 源配置 ∩ 设备能力；设备不支持的能力自动降级，不抛错 */
fun SourceSpec.effectivePolicy(device: DeviceCapability): PlaybackPolicy

/** OkHttp 拦截器：按源挂载，只作用于该源请求 */
interface AdBlockInterceptor {
    fun shouldBlock(url: HttpUrl, source: SourceSpec, sessionAllowHosts: Set<String>): Boolean
    fun onBlocked(sourceId: String, host: String, rule: String)   // 仅 debug 日志
}
```

- 拦截器按源挂载（`OkHttpClient.newBuilder().addInterceptor(...)`，共享连接池/Dispatcher），**禁止全局单例规则**，避免多源互相污染。
- 命中即抛 `IOException`（不返回假响应），由播放器错误路径走 §7.6 回退。
- 起播与切档只能通过 `effectivePolicy()` 读策略，禁止各 UI 直接读 `spec.maxQuality`，保证设备降级只有一处。
- `sessionAllowHosts` 由播放器维护：当前剧集 URL 的 host + 成功出过流量的 CDN host（§3.5.2 自伤保护第 2 条）。

### 7.2 并发与预算

| 项 | 值 | 理由 |
| --- | --- | --- |
| 并发源数 | 3（低端盒子 2） | 弱 CPU + 单频 WiFi，超过 3 无收益 |
| 单源搜索超时 | 3.5 s | 覆盖实测 TTFB 0.8–1.9 s（列表）/ 最高 3.8 s（大详情页，t1 §2.2）+ 抖动 |
| 首批发射 | 第一个源成功即发射，或 400 ms 宽限 | 首屏 ≤ 1 s 可交互 |
| 聚合硬截止 | 4.5 s | 到点发射已完成部分，`partial` 标明超时源 |
| 每源首页条数 | 20（与其 `pageSize` 一致） | 分页对齐 |
| 结果展示上限 | 60 条/页 | 低端机列表与内存 |

实现用 `coroutineScope` + `async` + `withTimeoutOrNull`，**禁止**用线程池轮询；OkHttp `Call.cancel()` 由协程取消触发。

### 7.3 分页游标（无状态、可再生）

- 聚合页 N 不对应任何单源页 N，而是"每个源各取第 N 页"再合并——要求各源 `pageSize` 一致（默认 20）；不一致时按 `ceil(N * 20 / pageSize)` 换算并在实现内记录。
- `cursor.sourcePages` 记录每源下一待取页；某源 `hasMore=false` 后从 map 移除，不再请求。
- `cursor.seenWorkKeys` 只保留最近 3 页的 key（约 180 个），防止跨页重复；完整去重由展示层 ListAdapter 的 `DiffUtil` 兜底。
- 用户改关键词 → 新 cursor（空）；"加载更多" → 原样回传上一页 cursor。

### 7.4 去重合并与排序

```kotlin
fun rank(item: MediaSummary, q: SearchQuery): Int {
    val title = normalizeTitle(item.title)
    val kw = normalizeTitle(q.keyword)
    val relevance = when {
        title == kw -> 100
        title.startsWith(kw) -> 80
        kw in title -> 60
        else -> 40
    }
    val priorityScore = (1000 - item.sourceId.priority) / 50  // 0..20（priority 小=分高）
    val scoreBonus = ((item.score ?: 0f) * 2).toInt()        // 0..20
    val fresh = if ((now - (item.updatedAt ?: 0)) < 24h) 5 else 0
    val catBonus = if (item.category == q.category) 10 else 0
    return relevance + priorityScore + scoreBonus + fresh + catBonus
}
```

排序键：`rank desc → priority asc（数值小优先）→ hasPoster desc → title`。合并后 `primary` 取 rank 最高者的展示字段，但 `alternatives` 必须保留全部站源（供详情/回退）。**同一 workKey 只出一条**，卡片角标显示"N 个源"。

### 7.5 部分失败与取消

- 单源失败：不抛异常，写入 `partial`，其余结果照常发射；`partial` 非空时 UI 显示"部分源不可用"入口（点开看每个源的错误码）。
- 全部失败：发射空 `items` + `partial`，`done=true`；UI 显示可重试错误页（区分"网络不可用"与"源全部熔断"）。
- 取消：`Flow` 收集取消 → `coroutineScope` 取消子任务 → OkHttp `Call.cancel()`；不得向 UI 抛 `CancellationException` 以外的异常。
- 重入：同一关键词重复搜索合并为一次（`Mutex`/`shareIn`），避免快速点按发起多轮。

### 7.6 详情合并与播放回退

```kotlin
data class MergedDetail(
    val work: MergedWork,
    val groups: List<SourcePlayGroup>,   // 带 sourceId，按 priority 升序（小优先）
    val synopsis: String?,
    val error: SourceError? = null
)

data class SourcePlayGroup(val sourceId: String, val group: PlayGroup, val priority: Int)
```

播放解析顺序（`PlaybackResolver`）：

1. 当前选中源的线路/剧集（用户显式选择优先）；
2. 同作品其它源（`priority` 升序 = 数值小优先）中**同名剧集**（`第N集`/`HD` 匹配）；
3. 同集其它 URL（m3u 频道的多 URL）；
4. 以上全失败 → 报错并给"换源"按钮（已解析出的备选直接展示）。

起播后（v1.1）：按 `effectivePolicy()` 执行——先按 §3.5.3 跳片头（只点播、只跳一次），再按 §3.5.4 应用 `TrackSelectionParameters` 画质/编码上限；两者都**不重建播放器**。

**播放前校验**（仅当没有播放成功记录时）：对 m3u8 发 `GET Range: bytes=0-1023`，2 s 超时，状态 2xx 且首行含 `#EXTM3U` 才交给 Media3；校验失败立即回退，避免 ExoPlayer 卡 10 s 后报错。若 CDN 不支持 Range 而返回 200，接受（读 1 KB 后关闭）。同一 URL 24 h 内只校验一次（本地 `verifiedAt`）。

**Headers 注入**：Media3 用 `DefaultHttpDataSource.Factory().setDefaultRequestProperties(spec.headers + episode.headers)`；HLS 子请求（分片/密钥）继承同一工厂。

### 7.7 与 t1 冻结契约的映射（P0，t4 必读）

`docs/PLAN_ARCHITECTURE.md` §3.4 冻结了 `SourceConfig / VodSummary / VodDetail / PlaySource / Episode / VodRepository / SourceStore`。本协议的模型是其**超集**，适配层必须提供以下转换，App 其余代码只依赖 t1 的类型：

```kotlin
fun SourceSpec.toSourceConfig(): SourceConfig? = if (kind != "maccms") null else SourceConfig(
    id = id,
    name = name,
    apiBase = baseUrl,
    headers = headers,
    enabled = enabled,
    priority = priority          // 双方一致：数值小者优先
)

// SourceStore.load() 只承载 maccms 源；m3u 源由 LiveRepository 读同一份存储（SourceConfig 无 m3u 字段）
```

| 本协议类型 | t1 冻结类型 | 转换规则 |
| --- | --- | --- |
| `MediaSummary` | `VodSummary` | `id=remoteId`、`name=title`、`pic=poster.orEmpty()`、`remarks=remarks.orEmpty()`、`typeId=remoteTypeId ?: 0`、`year=year?.toString().orEmpty()`、`score=score?.toString().orEmpty()` |
| `MediaDetail` | `VodDetail` | `content=synopsis.orEmpty()`、`area=area.orEmpty()`、`sources=groups.map { PlaySource(from=it.name, episodes=it.episodes.map { e -> Episode(e.name, e.url) }) }` |
| `Channel` / `ChannelUrl` | `LiveChannel(name, urls, logo)` / `LiveGroup(name, channels)` | `urls=channel.urls.map { it.url }`；本协议 `group` 即 `LiveGroup.name` |
| `SourceAdapter` | `VodRepository` | `list(typeId,page)=browse(categoryOf(typeId), page).map{ it.items.map(::toVodSummary) }`；`detail(id)=detail(id)` |
| 聚合搜索 `SearchAggregator` | （t1 未定义） | 仅本协议使用；t4 可先只接单源 `VodRepository`，再开聚合 |

**差异声明（避免 t4 误读）**：

- t1 `VodSummary` 不携带分类与简介，聚合卡片需要分类时用本协议 `MediaSummary`，或在 `VodSummary` 旁挂 `category` 字段（建议后者，改动最小）。
- m3u 源**不进入** `SourceStore`（`SourceConfig` 只有 `apiBase`），直播模块通过 `LiveRepository` 读取本协议存储中的 `kind=m3u` 记录。
- 播放前 2 s 校验（§7.6）发生在拿到 `Episode.url` 之后，不改变 t1 的 `Episode` 类型。

---

## 8. bfzy1.tv（macapi1 MacCMS 网关）接入

### 8.1 端点与参数（实测 2026-10-21）

主端点：`https://macapi1.com/maccms/json/baofeng/`（**必须带结尾斜杠**）
备用端点：`https://bfzyapi.com/api.php/provide/vod/`（标准 MacCMS JSON）

**请求头（P0）**：必须带 `User-Agent`（`okhttp/4.12.0`）与 `Referer: https://bfzy1.tv/`。实测裸 `python-urllib` 默认 UA 被 Cloudflare 拦为 `403`，而 `curl`/`okhttp` 默认 UA 可通；统一在 `Http` 层注入，不依赖系统默认。

| 用途 | 请求 | 说明 |
| --- | --- | --- |
| 分类树 | `?ac=list&t=0` | 返回 `class[]`（`type_id/type_pid/type_name`），`list` 为空 |
| 分类列表 | `?ac=list&t={typeId}&pg={n}` | 轻量列表（81 字段，含封面/备注/线路名 `vod_play_from`，**不含** `vod_play_url`/`vod_content`/`interaction`） |
| 搜索 | `?ac=detail&wd={kw}&pg={n}` | 实测 `ac=list&wd=` 也会返回全字段，统一用 `ac=detail` 保证字段完整 |
| 详情 | `?ac=detail&ids={id1,id2}` | 支持逗号批量；单条仅 ~3 KB |
| 首页/最新 | `?ac=detail&pg={n}` 或 `?ac=detail&h=24` | `h` = 最近 N 小时更新（实测 24 h 共 511 条） |
| 分类+最新 | `?ac=detail&t={typeId}&pg={n}` | 实测 `t=20`（电影片）共 43,326 条 |

通用参数：`ac`、`t`（type_id）、`pg`（页码，从 1 开始）、`wd`（关键词，URL 编码 UTF-8）、`ids`（逗号分隔）、`h`（小时）、`limit`（默认 20，可调但**建议保持 20** 与分页对齐）。

### 8.2 实测响应规模（决定"哪里用 list / 哪里用 detail"）

| 请求 | 原始字节 | gzip 字节 | 条数 | 备注 |
| --- | --- | --- | --- | --- |
| `ac=list&t=0`（分类树） | 2,730 | 725 | 49 类 | 仅冷启动一次 |
| `ac=list`（20 条） | 45,400 | 11,586 | 20 | ≈ 2.3 KB/条，81 字段；无 `vod_play_url`/`vod_content` |
| `ac=list&t=30` | 45,879 | 12,057 | 20 | 连续剧 |
| `ac=detail`（20 条） | 164,382 | 29,007 | 20 | ≈ 8.2 KB/条，92 字段 |
| `ac=detail&pg=2` | 352,361 | — | 20 | 长剧集作品显著变大（剧集列表） |
| `ac=detail&t=20&pg=2` | 60,000 | — | 20 | 电影类体积小 |
| `ac=detail&wd=爱情&pg=2` | 113,140 | — | 20 | 搜索命中 950 条 / 48 页 |
| `ac=detail&ids=164711,169023` | 6,159 | — | 2 | 详情页按需取最省 |

**结论（硬指标）**：列表页必须 `ac=list`；详情/搜索才 `ac=detail`；`limit` 不要放大到 50（单页可能 > 400 KB，弱机解析卡顿）；必须 gzip（4–5.7× 压缩）；`ETag` 可用（实测 304）。

响应头（实测）：`Content-Type: application/json`、`Server: cloudflare`、`cache-control: public, no-cache, must-revalidate`、`etag`、`r-cache: HIT`。=> 允许本地 ETag 缓存；Cloudflare 会拦截异常 UA，默认 UA 建议 `okhttp/4.12.0` + `Referer: https://bfzy1.tv/`。

### 8.3 字段映射表（bfzy1 → 统一模型）

| 源字段 | 目标 | 处理 |
| --- | --- | --- |
| `vod_id` | `MediaSummary.remoteId` | 字符串化；bfzy1 为纯数字 |
| `work_id` | `MediaSummary.stableRemoteKey` | 站内稳定哈希；仅用于本地缓存键，**不跨站去重** |
| `vod_name` | `title` | 保留原名；另有 `vod_sub` 作副标题 |
| `vod_sub` | `subtitle` | 别名/英文名，取首个 ` / ` 前段 |
| `vod_pic` | `poster` | 绝对 URL；实测为 `.webp`（见 §10 图片策略） |
| `vod_remarks` | `remarks` | “更新至第10集”“HD”；截断 20 字 |
| `type_id` / `type_name` | `category` / `genre` | 见 §8.4；`type_name` 作 genre |
| `type_id_1` | `category` | 一级分类 id（20/30/39/45/53/58/51/52/73） |
| `vod_year` | `year` | `toIntOrNull()` |
| `vod_area` | `area` | “荷兰”“大陆” |
| `vod_lang` | `language` | — |
| `vod_actor` | `actors` | 按 `,`/`/` 切分、去空白、上限 20 |
| `vod_director` | `directors` | 同上，上限 10 |
| `vod_content` | `synopsis` | 去 HTML 标签与 `&nbsp;`，压缩空白，截断 2,000 字 |
| `vod_blurb` | `shortDesc` | 优先于 content 作卡片副标题（有则用） |
| `vod_score` | `score` | `toFloatOrNull()`；`"0.0"` → null |
| `vod_score_all` / `vod_score_num` | （可选）`ratingCount` | 仅详情展示 |
| `vod_time` | `updatedAt` | `yyyy-MM-dd HH:mm:ss`，按 **UTC+8** 解析（站方时区），转 epoch |
| `vod_isend` | `completed` | 1 → 完结 |
| `vod_total` / `vod_serial` | `totalEpisodes` | 0 视为未知 |
| `vod_play_from` | `PlayGroup.name` | `$$$` 分隔多线路 |
| `vod_play_url` | `PlayGroup.episodes` | `$$$` 线路 / `#` 剧集 / `$` 名称与地址，见 §8.5 |
| `vod_play_note` | `PlayGroup.note` | 通常为空 |
| `vod_douban_id` | `doubanId` | > 0 才用；跨站去重首选键 |
| `vod_hits` | （可选）`heat` | 排序辅助 |
| `vod_down_*` | 忽略 | 不做下载 |
| `interaction` / `group_key` / `user_*` | 忽略 | 站方 APP 互动数据，与本客户端无关（可省 0.4 KB/条） |
| `vod_en` | （可选）拼音 | 搜索兜底 |

`workKey` 生成优先级：`doubanId` → `group_key` 的 hash → 标题+年份+分类（§5.3）。**不**直接用 `work_id`（各站独立）。

### 8.4 分类映射（实测分类树 49 项、9 个一级）

| 一级 `type_id` | 名称 | MediaCategory |
| --- | --- | --- |
| 20 | 电影片 | MOVIE |
| 30 | 连续剧 | TV |
| 39 | 动漫片 | ANIME |
| 45 | 综艺片 | VARIETY |
| 58 | 短剧大全 | SHORT |
| 53 | 体育赛事 | SPORTS |
| 51 | 电影解说 | OTHER |
| 52 | 预告片 | OTHER |
| 73 | 福利 | OTHER（默认隐藏，不参与聚合搜索） |

二级（实测，示例）：电影片 → 动作/喜剧/爱情/科幻/恐怖/剧情/战争/纪录片/动画/理论；连续剧 → 国产/欧美/韩国/日本/泰国/海外/香港/台湾；动漫片 → 国产/日韩/欧美/港台/海外；综艺片 → 大陆/日韩/欧美/港台；短剧大全 → 穿越年代/现代言情/古装仙侠/AI漫剧 等 9 类；体育赛事 → 足球/篮球/网球/斯诺克。实现**不硬编码全部二级**，从 `class` 树动态渲染（缓存 24 h），一级只用于五大 Tab 归类。

### 8.5 解析器伪代码（bfzy1 / MacCMS 通用）

```kotlin
// ===== 适配器入口 =====
class MacCmsAdapter(
    override val spec: SourceSpec,
    private val http: Http,          // OkHttp 封装：gzip、ETag、超时、重试、熔断
    private val clock: Clock
) : SourceAdapter {

    private val base get() = spec.baseUrl          // 原样使用，含结尾 "/"
    private val flavor get() = spec.flavor          // "macjson" | "maccms"

    // ---- L1：分类树 ----
    override suspend fun classes(): Result<List<ClassNode>> = runCatching {
        val url = buildUrl(base, mapOf("ac" to "list", "t" to "0"))
        val json = http.getJson(url, spec.headers, spec.timeoutMs)   // 304 时返回缓存
        requireEnvelope(json)                                        // code == 1
        val arr = json.optJSONArray("class")
        if (arr == null) {                                           // 标准 MacCMS 可能无 class
            Log.w(TAG, "class missing, fallback to builtin mapping") // W_CLASS_FALLBACK
            return@runCatching builtinClasses()
        }
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ClassNode(
                id = o.optString("type_id").toIntOrNull() ?: return@map null,
                parentId = o.optInt("type_pid", 0),
                name = o.optString("type_name").trim()
            )
        }.filterNotNull()
    }

    // ---- L2：分类列表（轻量字段，绝不用 detail）----
    override suspend fun browse(category: MediaCategory, page: Int): Result<SourcePage> = runCatching {
        val types = spec.categories[category.name] ?: defaultTypes(category)
        val typeId = types.firstOrNull() ?: error("no typeId for $category")
        val url = buildUrl(base, mapOf("ac" to "list", "t" to "$typeId", "pg" to "$page"))
        val started = clock.elapsedRealtime()
        val json = http.getJson(url, spec.headers, spec.timeoutMs)
        requireEnvelope(json)
        val list = json.optJSONArray("list") ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { i ->
            mapSummary(list.optJSONObject(i) ?: return@mapNotNull null)
        }
        SourcePage(
            sourceId = spec.id, page = page, items = items,
            hasMore = page < json.optInt("pagecount", 1),
            total = json.optInt("total", -1).takeIf { it >= 0 },
            elapsedMs = clock.elapsedRealtime() - started
        )
    }

    // ---- L3：搜索（detail 保证字段完整）----
    override suspend fun search(keyword: String, page: Int): Result<SourcePage> = runCatching {
        val url = buildUrl(base, mapOf(
            "ac" to "detail", "wd" to keyword, "pg" to "$page", "enc" to "utf-8"
        ))
        val json = http.getJson(url, spec.headers, spec.timeoutMs)
        requireEnvelope(json)
        val list = json.optJSONArray("list") ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { i ->
            mapSummary(list.optJSONObject(i) ?: return@mapNotNull null)
        }
        SourcePage(spec.id, page, items, page < json.optInt("pagecount", 1),
                   json.optInt("total", -1).takeIf { it >= 0 }, 0)
    }

    // ---- L4：详情（批量 ids，单条也走此接口）----
    override suspend fun detail(remoteId: String): Result<MediaDetail> = runCatching {
        val url = buildUrl(base, mapOf("ac" to "detail", "ids" to remoteId))
        val json = http.getJson(url, spec.headers, spec.timeoutMs)
        requireEnvelope(json)
        val o = json.optJSONArray("list")?.optJSONObject(0) ?: error("empty detail")
        mapDetail(o)
    }
}

// ===== 统一映射 =====
// 注：以下辅助函数为适配器作用域（可访问 spec/base）；实现时把 spec 作为参数传入即可。
fun mapSummary(o: JSONObject): MediaSummary {
    val typeId1 = o.optInt("type_id_1", -1)
    val typeId  = o.optString("type_id").toIntOrNull() ?: -1
    val category = categoryOf(typeId1, typeId)
    val title = o.optString("vod_name").trim().ifEmpty { return null } // 无标题条目丢弃
    val year = o.optString("vod_year").toIntOrNull()?.takeIf { it in 1900..2100 }
    val douban = o.optString("vod_douban_id").toLongOrNull()?.takeIf { it > 0 }
    return MediaSummary(
        workKey = workKeyOf(title, year, category, douban, o.optString("group_key")),
        sourceId = spec.id,
        remoteId = o.optString("vod_id"),
        title = title,
        subtitle = o.optString("vod_sub").substringBefore(" / ").trim().ifBlank { null },
        poster = absUrl(o.optString("vod_pic")),
        year = year,
        category = category,
        genre = o.optString("type_name").ifBlank { o.optString("vod_class") }.trim().ifBlank { null },
        remarks = o.optString("vod_remarks").trim().take(20).ifBlank { null },
        score = o.optString("vod_score").toFloatOrNull()?.takeIf { it > 0f },
        updatedAt = parseSiteTime(o.optString("vod_time")),   // UTC+8
        doubanId = douban
    )
}

fun mapDetail(o: JSONObject): MediaDetail {
    val summary = mapSummary(o) ?: error("invalid item")
    val groups = parsePlayGroups(o.optString("vod_play_from"), o.optString("vod_play_url"))
    return MediaDetail(
        summary = summary,
        synopsis = stripHtml(o.optString("vod_content")).take(2000).ifBlank { null },
        actors = splitPeople(o.optString("vod_actor"), 20),
        directors = splitPeople(o.optString("vod_director"), 10),
        area = o.optString("vod_area").trim().ifBlank { null },
        language = o.optString("vod_lang").trim().ifBlank { null },
        totalEpisodes = o.optInt("vod_total", 0).takeIf { it > 0 },
        completed = o.optInt("vod_isend", 0) == 1,
        groups = groups
    )
}

// ===== 播放地址解析（MacCMS 三件套：$$$ / # / $）=====
fun parsePlayGroups(fromRaw: String?, urlRaw: String?): List<PlayGroup> {
    if (urlRaw.isNullOrBlank()) return emptyList()
    val froms = fromRaw.orEmpty().split("$$$")
    val groupSegs = urlRaw.split("$$$")
    return groupSegs.mapIndexedNotNull { gi, seg ->
        val eps = seg.split('#').mapNotNull { raw ->
            val cell = raw.trim()
            if (cell.isEmpty()) return@mapNotNull null
            val sep = cell.indexOf('$')                  // 只认第一个 '$'，URL 里可能有 '$'
            if (sep <= 0 || sep == cell.lastIndex) return@mapNotNull null
            val name = cell.substring(0, sep).trim().ifBlank { "第${it + 1}集" }
            val url = normalizePlayUrl(cell.substring(sep + 1).trim())
                ?: return@mapNotNull null                 // 丢弃非 http(s)/相对非法项
            Episode(name = name, url = url, headers = emptyMap())
        }.distinctBy { it.url }                           // 剧集级去重
        if (eps.isEmpty()) return@mapNotNull null
        val rawName = froms.getOrNull(gi)?.trim().orEmpty()
        PlayGroup(
            name = displayGroupName(rawName, gi),         // bfzym3u8 → "暴风线路"
            episodes = eps.mapIndexed { i, e -> e.copy(index = i + 1) }
        )
    }
}

fun normalizePlayUrl(raw: String): String? {
    if (raw.isEmpty()) return null
    val abs = when {
        raw.startsWith("//") -> "https:$raw"              // 协议相对
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("/") -> resolveAgainst(base, raw)  // 相对路径
        else -> null                                      // 丢弃 javascript:/magnet: 等
    }
    // 常见坑：URL 尾部粘了 "$$$" 或空白
    return abs.trim().removeSuffix("$$$")
}

fun displayGroupName(raw: String, idx: Int): String = when {
    raw.isBlank() -> "线路${idx + 1}"
    raw.contains("m3u8", ignoreCase = true) -> "暴风线路"   // bfzym3u8 / bfzy2m3u8…
    raw.contains("m3u", ignoreCase = true) -> "M3U8 线路"
    else -> raw.take(12)
}

// ===== 请求构造 =====
fun buildUrl(base: String, params: Map<String, String>): String {
    require(base.startsWith("http://") || base.startsWith("https://"))
    val query = params.entries.joinToString("&") { (k, v) ->
        "$k=${URLEncoder.encode(v, "UTF-8")}"
    }
    return if (base.contains('?')) "$base&$query" else "$base?$query"
}

fun requireEnvelope(json: JSONObject) {
    val code = json.optInt("code", -1)
    if (code != 1) throw SourceError.Business(spec.id, code, json.optString("msg"))
}
```

**边界条件（必须单测）**：

| 输入 | 期望 |
| --- | --- |
| `vod_play_url=""` | `groups = []`，详情页显示"暂无播放地址" |
| `vod_play_from="bfzym3u8"`，url 三个 `$$$` 段 | 前两组解析，第三组空则跳过 |
| 单元格 `"第1集"`（无 `$`） | 跳过该集 |
| 单元格 `"$https://…"`（名称为空） | 跳过（`sep<=0`） |
| URL 含 `?a=1&b=2` | 完整保留（不要按 `&` 切） |
| `type_id="49"`（字符串） | `toIntOrNull` 正常 |
| `vod_score="0.0"` | `score=null` |
| `vod_year=""` | `year=null` |
| 响应 `code=0` | 抛 `Business`，UI 走该源错误态 |
| 响应被截断（非合法 JSON） | 重试 1 次后 `Parse` 错误 |

---

## 9. m3u 直播源解析规范

### 9.1 支持的语法子集

```
#EXTM3U
#EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV-1 综合" tvg-logo="https://…" group-title="央视频道",CCTV-1 综合
#EXTVLCOPT:http-user-agent=Mozilla/5.0
#EXTVLCOPT:http-referrer=https://…
#KODIPROP:inputstream.adaptive.license_type=…
http://example.com/cctv1.m3u8
```

- 只处理 `#EXTINF` + 紧随的 URL 行；`#EXTGRP:` 作为组名兜底；`#EXTVLCOPT`/`#KODIPROP` 记录为该频道的请求头（仅白名单键：`http-user-agent`、`http-referrer`、`http-origin`）。
- 忽略 `#EXT-X-*`（那是播放列表，不是频道表）、注释、空行。
- 相对 URL 用列表 URL 解析；`udp://`/`rtmp://`/`rtsp://` 默认丢弃（Media3 不支持或低端盒子不可用），计数进 `warning: DROPPED_SCHEMES`。

### 9.2 属性映射

| m3u 属性 | 目标 | 说明 |
| --- | --- | --- |
| `tvg-id` | `Channel.tvgId` | 去空白、小写 |
| `tvg-name` | 名称候选 | 优先级低于逗号后的显示名 |
| `tvg-logo` | `logo` | http(s) 才接受 |
| `group-title` | `group`（归一化） | 见 §9.3 |
| `#EXTINF` 时长 | — | 直播固定 -1，忽略 |
| 逗号后文本 | `name` | 去首尾空白；空则用 `tvg-name`；仍空 → 丢弃 |
| 频道级 `desc` | `Channel.desc` | 仅来自 v1.1 `channels[]` 覆盖；m3u 本身无该属性 |

### 9.3 分组归一化

| 命中（包含匹配，忽略大小写） | 归一化分组 |
| --- | --- |
| `央视`/`CCTV`/`中央` | 央视频道 |
| `卫视` | 卫视频道 |
| `地方`/`城市`/`省内` | 地方频道 |
| `港澳台` | 港澳台 |
| `体育` | 体育 |
| `影视`/`电影`/`剧场` | 影视 |
| 其它/空 | 其他 |

用户 `filters` 在归一化**之后**应用（对原始 `group-title` 与归一化名都匹配，任一命中即可）。

### 9.4 解析伪代码

```kotlin
fun parseM3u(text: String, spec: SourceSpec, sourceId: String): List<Channel> {
    val channels = LinkedHashMap<String, Channel>()   // key -> merged
    var pending: ExtInf? = null
    var pendingHeaders = mutableMapOf<String, String>()
    var pendingGroup: String? = null

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim().removePrefix("\uFEFF")
        when {
            line.isEmpty() -> continue
            line.startsWith("#EXTINF:", ignoreCase = true) -> {
                pending = parseExtInf(line)                       // attrs + displayName + duration
                pendingHeaders = mutableMapOf(); pendingGroup = null
            }
            line.startsWith("#EXTGRP:", ignoreCase = true) ->
                pendingGroup = line.substringAfter(':').trim()
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) ||
            line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                val kv = line.substringAfter(':')
                val k = kv.substringBefore('=').trim().lowercase()
                val v = kv.substringAfter('=', "").trim()
                headerKeyOf(k)?.let { pendingHeaders[it] = v }    // 白名单映射
            }
            line.startsWith("#") -> continue                       // 其它指令忽略
            else -> {
                val info = pending ?: continue                     // 无 EXTINF 的裸 URL：丢弃
                val url = normalizeUrl(line) ?: continue
                if (!url.startsWith("http")) { droppedSchemes++; continue }
                val name = info.displayName.ifBlank { info.tvgName }.trim()
                if (name.isEmpty()) continue
                val rawGroup = info.groupTitle ?: pendingGroup.orEmpty()
                if (!passesFilters(name, rawGroup, spec.filters)) { filtered++; continue }
                val group = normalizeGroup(rawGroup)
                val key = channelKey(name, info.tvgId, group, spec.dedupe)
                val cu = ChannelUrl(url, qualityOf(name), sourceId)
                channels[key] = channels[key]?.let { it.copy(urls = (it.urls + cu).take(4)) }
                    ?: Channel(key, name, group, info.logo, info.tvgId, info.tvgId, listOf(cu))
                pending = null; pendingHeaders = mutableMapOf(); pendingGroup = null
            }
        }
    }
    return channels.values.toList()
}

data class ExtInf(
    val duration: Int, val tvgId: String?, val tvgName: String?,
    val logo: String?, val groupTitle: String?, val displayName: String
)

fun parseExtInf(line: String): ExtInf {
    val attrsPart = line.substringAfter(':').substringBefore(',')
    val display = line.substringAfter(',', "").trim()
    val attrs = ATTR_REGEX.findAll(attrsPart).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
    return ExtInf(
        duration = attrsPart.substringBefore(' ').trim().toIntOrNull() ?: -1,
        tvgId = attrs["tvg-id"]?.trim()?.ifBlank { null },
        tvgName = attrs["tvg-name"]?.trim()?.ifBlank { null },
        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") },
        groupTitle = attrs["group-title"]?.trim()?.ifBlank { null },
        displayName = display
    )
}
```

`ATTR_REGEX = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")`。

**v1.1 `channels[]` 合并**：解析完成后按 §3.5.5 合入 `spec.channels`——`tvgId`（缺省 `group + 归一化台名`）命中则覆盖/补齐非空字段并合并 URL；未命中作为新频道加入。`desc` 原样带入 `Channel.desc`，只用于详情面板，不参与搜索排序。

### 9.5 播放与 EPG

- 播放：Media3 `HlsMediaSource`，`DefaultHttpDataSource.Factory` 注入频道 headers；`setAllowChunklessPreparation(true)` 降内存；直播不启用缓存（`CacheDataSource` 只给点播）。
- 失败：同频道下一 URL（≤ 4 个）→ 2 s 内切换；全部失败给"源失效"标记并建议刷新订阅。
- EPG：`epgUrls`（v1.1，任意 kind）流式解析（`XmlPullParser`，按 `1f 8b` 魔数判定 gzip），按 `epgId ?: tvgId` 匹配；只保留当前/下 2 小时节目（内存上限 ~200 条），失败静默。细节见 §3.5.1。

### 9.6 与 t2 内置源对齐

`assets/live_seed.json` 是**内置种子**，不走 m3u 解析；导入器提供 `ChannelSeedImporter` 把种子映射为 `Channel`：

- 接受字段别名：`name|title`、`url|play_url|stream`、`group|group-name|category`、`logo|icon|tvg-logo`、`tvg_id|tvg-id`。
- 种子内每个频道可含 `urls[]`（多备用）；`verified_at`/`latency_ms`/`quality` 原样带入。
- 种子与用户 m3u 频道的合并：同 §5.2 键合并，用户源优先（`sourceId` 前缀 `seed:` 优先级最低），用户可覆盖内置 URL。

---

## 10. 低端盒子性能预算（协议相关部分）

| 指标 | 预算 | 依据/措施 |
| --- | --- | --- |
| 列表首页流量 | ≤ 30 KB（gzip） | `ac=list` 11.6 KB + 封面按需；绝不加载 detail |
| 详情页流量 | ≤ 40 KB（gzip） | `ac=detail&ids=` 单条 3–8 KB + 无预取 |
| 搜索首屏 | ≤ 4.5 s 出结果、≤ 1 s 出首条 | §7.2 预算 |
| JSON 解析（45 KB） | ≤ 40 ms（4 核 1.2 GHz 类 CPU） | `org.json` 逐字段取值；禁止反射式通用反序列化 |
| JSON 解析（detail 29 KB gzip/164 KB 明文） | ≤ 120 ms | 同上；**后台线程 + 限并发 2** |
| 单次响应内存 | ≤ 2 MB | OkHttp 响应上限 + `maxBytes` |
| 图片 | 按 t1 §5.1 的卡片尺寸（270–290px 宽），请求 2× 解码上限约 540px；`webp` 原图直出，禁止整图解码 | bfzy1 封面本身就是 `.webp`（实测 270×270/270×405，8–43KB）；minSdk 21 原生支持 |
| 详情/列表缓存 | ≤ 6 MB 磁盘，单条 ≤ 512 KB | Room + LruCache |
| 冷启动 | 不探测源、不拉分类树 | 首屏用缓存；`Application` 内零网络 |
| 并发 | 全局 ≤ 3 个源请求 | 弱 WiFi + 单核解压 |
| 直播切台 | ≤ 1.5 s 起播（同分组） | 预连接下一频道（可选，t1 决定） |
| 广告拦截 | ≤ 0.05 ms/请求 | 50 域名 + 100 关键字预编译匹配；命中直接抛异常不建连接 |
| 画质上限 | 720p 源峰值 ≤ 3 Mbps（较自动档省 30–50%） | 降低分片解码与带宽压力，1 GB 盒子收益最大 |
| 片头跳过 | 每集省 30–120 s（约 5–20 MB） | 起播一次 seek，不额外请求 |
| EPG 解析 | ≤ 8 MB、≤ 300 ms、常驻 ≤ 200 条 | 后台线程解析 + 30 min 刷新，不阻塞首帧 |

- 所有 JSON/HTTP 在 `Dispatchers.IO`（限 4 线程），解析后切主线程只做 `submitList`。
- 禁止在 `onBindViewHolder` 里发起网络；封面 URL 缺失直接占位（不要 fallback 请求详情）。
- `/maccms/json/baofeng/` 首页 `ac=list` 45 KB 原文含大量空字段（92 字段中 ~60 个为空），实现只取所需字段，**不构造完整 Map**。

---

## 11. 安全与隐私

| 风险 | 措施 |
| --- | --- |
| SSRF/内网探测 | 拒绝 `localhost`、`127.0.0.0/8`、`10/8`、`172.16/12`、`192.168/16`、`169.254/16`、`::1`、`fe80::/10`（除非用户显式勾选"允许局域网源"） |
| 任意 scheme | 只允许 http/https；`file://` 只能经 SAF 选择器读取本地文件，不进入 URL 字段 |
| 明文 HTTP | 允许（国内源现实），但首次导入警告 `W_HTTP`；API 优先 https；播放地址 http 不阻断 |
| 头注入 | headers 键值禁 `\r\n`；键名白名单（`User-Agent`/`Referer`/`Origin`/`Cookie`/`Authorization`/`X-*`） |
| 凭据泄漏 | URL 中含 `user:pass@` 时导入允许但日志/导出脱敏为 `***` |
| 大响应/压缩炸弹 | 响应 ≤ 2 MB（解压后），`Content-Length` 超限直接断开；gzip 解压上限同值 |
| 恶意 m3u | `maxBytes`、20k 行上限、正则超时（编译失败即拒）、URL 长度 ≤ 2 KB |
| 订阅投毒 | 可选 `signature` 校验；`sources` 数量上限；嵌套拒绝；`kind` 白名单 |
| 播放地址钓鱼 | 播放前校验 m3u8 首行 `#EXTM3U`，非 HLS 不给 ExoPlayer；不自动打开浏览器 |
| 隐私 | 不采集/不上报用户导入的源；崩溃日志不含完整 URL（仅 host + path 前 20 字符） |

---

## 12. 存储（Room）与生命周期

```sql
-- 源
CREATE TABLE source (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 100,
  config_json TEXT NOT NULL,            -- 规范化后的 SourceSpec（不含瞬时状态）
  fingerprint TEXT NOT NULL,            -- §5.1 端点指纹
  user_touched INTEGER NOT NULL DEFAULT 0,
  bundle_id TEXT,                       -- 来自哪个订阅
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  probe_state TEXT NOT NULL DEFAULT 'UNVERIFIED',
  probe_at INTEGER, fail_streak INTEGER NOT NULL DEFAULT 0,
  active_base_url TEXT                  -- 降级后当前端点
);
CREATE UNIQUE INDEX idx_source_fp ON source(kind, fingerprint);

-- 频道（直播）
CREATE TABLE channel (
  key TEXT PRIMARY KEY, name TEXT NOT NULL, group_name TEXT NOT NULL,
  logo TEXT, tvg_id TEXT, epg_id TEXT, source_id TEXT NOT NULL,
  quality TEXT, verified_at INTEGER, latency_ms INTEGER,
  desc TEXT,                            -- v1.1：频道简介（详情面板）
  sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE channel_url (
  channel_key TEXT NOT NULL, url TEXT NOT NULL, quality TEXT,
  source_id TEXT NOT NULL, verified_at INTEGER, latency_ms INTEGER,
  PRIMARY KEY(channel_key, url)
);

-- 点播缓存
CREATE TABLE media_cache (
  cache_key TEXT PRIMARY KEY,           -- (sourceId|remoteId|kind)
  payload TEXT NOT NULL, etag TEXT, fetched_at INTEGER NOT NULL, ttl_ms INTEGER NOT NULL
);
CREATE TABLE search_cache (
  key_hash TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched_at INTEGER NOT NULL
);
```

- 迁移：`source.config_json` 版本化，升级时按 `schema` 迁移；未知字段保留（`user_touched` 的源不自动改）。
- v1.1：`adBlock`/`skipIntro*`/`maxQuality`/`preferredCodec`/`epgUrls`/`channels` 全部落在 `source.config_json`（**无新增列**）；唯一 DDL 变更是 `ALTER TABLE channel ADD COLUMN desc TEXT`（v1.0→v1.1）。
- 清理：`media_cache` 超 6 MB 按 `fetched_at` LRU 淘汰；`search_cache` 保留 7 天。
- 备份/导出：导出为 `bundle`（`signature` 重算），不导出缓存与探测状态。

---

## 13. 验收清单（qa 可直接照做）

| # | 检查项 | 通过标准 |
| --- | --- | --- |
| 1 | 导入 `assets/sources.example.json` | `accepted` 含 4 个源，0 拒绝；`python tools/validate_sources.py` 通过 |
| 2 | 重复导入同一文件 | 源数量不变（幂等），无重复频道 |
| 3 | 缺 `schema` / `kind:"evil"` / url `file:///etc/passwd` | 分别 `E_SCHEMA` / `E_KIND` / `E_URL`，UI 显示原因 |
| 4 | 离线导入 | 成功且 `probe.state=UNVERIFIED`；联网后首次使用自动补探 |
| 5 | bfzy1 搜索"老江湖" | 返回 1 条，详情含 1 集（HD），`playGroups ≥ 1` |
| 6 | 列表页抓包 | 仅 `ac=list`，无 `ac=detail`；首页 gzip ≤ 30 KB |
| 7 | 三源聚合搜索 | 首条结果 ≤ 1 s；单源 3.5 s 超时不阻塞；`partial` 正确 |
| 8 | 同名作品跨站 | 合并为 1 条，角标"N 个源"，详情可切换线路 |
| 9 | 主端点故障（断网/DNS 污染） | 60 s 内切 `bfzyapi.com` 备用端点，列表可用（卡片降级） |
| 10 | 播放地址失效 | 2 s 内回退下一线路/URL，不卡死；全失败给"换源" |
| 11 | m3u 去重 | 两路同频道合并，URL 保留 ≤ 4，分组归一正确 |
| 12 | GBK 编码 m3u | 正常解析（或明确 `E_ENCODING`），不出现乱码频道 |
| 13 | 熔断 | 连续 3 次失败后聚合搜索跳过该源，冷却 60 s 后 HALF_OPEN 恢复 |
| 14 | 内存 | 列表滚动 + 详情进入，PSS 增量 ≤ 30 MB（1 GB 盒子） |
| 15 | 导入报告 | 20 条混合非法输入，全部逐条给出 code+message，不崩溃 |
| 16 | v1 老配置（无 v1.1 字段） | 通过校验，行为不变（`adBlock` 空、`skipIntroSeconds=0`、`maxQuality=auto`、无频道元数据） |
| 17 | v1.1 校验 | `python tools/validate_sources.py assets/sources.example.json` 0 错误；`--selftest` 旧 9 + 新 8 负例全拦截、正例 3 条通过 |
| 18 | 广告拦截 + 自伤保护 | 命中广告域名的请求被拦并记 debug 日志；源 `baseUrl`/`fallbackBaseUrls`/`epgUrls`/当前播放 CDN **不被拦**（冲突在导入期即 `E_ADBLOCK`） |
| 19 | 画质/编码上限 | 源 `maxQuality=720p` 时 HLS 不选 1080p 档；`preferredCodec=hevc` 但设备无 HEVC 硬解时回退 h264/auto，不黑屏、不报错 |
| 20 | 片头跳过 + 频道元数据 | `skipIntroSeconds=90` 起播后跳到 90 s 且只跳一次、直播不跳；频道详情显示 `desc`，`tvgId/logo/group` 生效；同源重复频道导入被拒（`E_CHANNEL`） |

自检脚本（本机可跑，需 `jsonschema`）：

```bash
python tools/validate_sources.py assets/sources.example.json   # exit 0
python tools/validate_sources.py --selftest                   # exit 0（旧 9 + 新 8 负例 + 3 正例）
```

---

## 14. 示例

完整可导入示例见 `assets/sources.example.json`（bundle 含 4 个源：bfzy1 主源 v1.1、bfzyapi 备用端点 v1、m3u 直播源 v1.1（含 `channels[]`）、内联直播源 v1）——**同一文件里混用 v1/v1.1 也能通过**，这就是向后兼容的活样本。片段：

```json
{
  "schema": "tvapp.source.v1",
  "kind": "maccms",
  "id": "bfzy1",
  "name": "暴风影音",
  "priority": 10,
  "baseUrl": "https://macapi1.com/maccms/json/baofeng/",
  "flavor": "macjson",
  "fallbackBaseUrls": ["https://bfzyapi.com/api.php/provide/vod/"],
  "headers": { "User-Agent": "okhttp/4.12.0", "Referer": "https://bfzy1.tv/" },
  "categories": { "MOVIE": [20], "TV": [30], "ANIME": [39], "VARIETY": [45], "SHORT": [58] }
}
```

v1.1 片段（广告拦截 + 片头跳过 + 画质上限）：

```json
{
  "schema": "tvapp.source.v1.1",
  "kind": "maccms",
  "id": "bfzy1",
  "name": "暴风影音",
  "baseUrl": "https://macapi1.com/maccms/json/baofeng/",
  "adBlock": {
    "enabled": true,
    "domains": ["doubleclick.net", "*.admaster.com.cn"],
    "urlKeywords": ["/ad/", "/ads/", "promo="]
  },
  "skipIntroSeconds": 90,
  "skipIntroCue": true,
  "maxQuality": "1080p",
  "preferredCodec": "auto"
}
```

v1.1 片段（直播源频道元数据；`url` 与 `urls` 二选一）：

```json
{
  "schema": "tvapp.source.v1.1",
  "kind": "m3u",
  "id": "live-iptv",
  "name": "IPTV 直播源",
  "url": "https://example.com/iptv.m3u",
  "epgUrls": ["https://example.com/epg.xml.gz"],
  "channels": [
    {
      "name": "CCTV-1 综合",
      "urls": ["https://example.com/live/cctv1.m3u8", "https://example.com/live/cctv1-b.m3u8"],
      "group": "央视频道",
      "tvgId": "cctv1",
      "logo": "https://example.com/logo/cctv1.png",
      "desc": "中央电视台综合频道。"
    }
  ],
  "maxQuality": "720p"
}
```

最小 m3u 源（v1 兼容写法，仍有效）：

```json
{
  "schema": "tvapp.source.v1",
  "kind": "m3u",
  "id": "live-min",
  "name": "我的直播",
  "url": "https://example.com/live.m3u",
  "filters": { "includeGroups": ["央视", "卫视"] }
}
```

---

## 15. 与其他计划的边界 / 待确认

| 事项 | 归属 | 状态 |
| --- | --- | --- |
| 列表/图片/内存细节参数 | t1 `PLAN_ARCHITECTURE.md` | 本文只给协议相关预算，冲突以 t1 为准 |
| 内置直播种子（央卫视频道名单、可用性） | t2 `PLAN_LIVE.md` + `assets/live_seed.json` | §9.6 定义导入适配，字段别名兼容 |
| 播放器具体缓冲/硬解参数 | t1 | 本文只定义 headers 注入与 2 s 校验 |
| TVBox 旧格式兼容 | 本文 §3.4 P1 | 不阻塞 t4；t4 可先不做 |
| 聚合分页在源 `pageSize ≠ 20` 时的换算 | 本文 §7.3 | t4 实现时按公式处理，若某源强制 24 条，记录为已知偏差 |
| `ac=list` 是否含 `vod_play_url` | 实测：网关不含、标准站也不含 | 实现**不得依赖** list 的 play_url；详情统一走 detail |
| 多站聚合搜索 / 去重 / 回退 | 本文 §5/§7 | t1 未定义；t4 可先单源后开聚合 |
| 广告拦截三层策略（L1 无网页 / L2 拦截器 / L3 贴片跳过） | `PLAYBACK_SPEC.md` §7 | 本文 §3.5.2/§3.5.3 定义字段、匹配算法与自伤保护；播放器实现归 t4 |
| 画质/编码选择与切档 | `PLAYBACK_SPEC.md` §6 + t1 `PerfProfile` | 本文给上限映射与 `effectivePolicy()` 唯一入口 |
| 扫码/配对码/U 盘导入通道 | `SOURCE_IMPORT_SPEC.md` | 本文只定义文件格式与 L0–L4 校验，通道实现归 t4 |

**开放问题（需 captain/architect 决策）**：

1. 是否允许用户导入明文 http 的 MacCMS 源？（本文：允许 + 警告）
2. 聚合页大小 20 还是 30（低端机内存 vs 翻页次数）？本文取 20，与远端一致。
3. 详情页是否预取"备选源"的线路？本文默认**不预取**（省流量），仅在用户点"换源"时拉取。
4. **图片库冲突**：`docs/TOOLCHAIN.md` 写 `Coil 2.7.0`，`docs/PLAN_ARCHITECTURE.md` §5 用 Glide + `AppGlideModule`（含 `GlideExecutor` 调参、`allowHardware` 等）。两者不能并存，需 captain 冻结；本文的图片尺寸/WebP 结论与库无关。
5. **priority 语义已对齐**：t1 `SourceConfig` 为"数值小优先"（默认 100），本文示例已按此调整（bfzy1=10、备用=20、直播=30/40）。若 captain 更希望"大优先"，只需改适配器一处映射，不改导入格式。
6. `epgUrls` 对点播源开放是否有用（"正在播"推荐）？本文允许（任意 kind），t4 可先只给直播用，不影响校验。
7. `skipIntroSeconds` 是否对直播时移回看生效？本文明确**不生效**（直播无"片头"语义，避免误跳）。
8. 广告拦截默认值：本文默认 `enabled=true` 但规则为空 = 不拦任何请求；若产品希望内置通用黑名单，需另立内置规则文件（不写进导入格式，避免用户配置被覆盖）。

---

## 16. 变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1.0 | 2026-10-21 | 初版：maccms / m3u / bundle 三种形态、L0–L4 校验、四级去重、重试熔断降级、多站聚合契约、bfzy1 字段映射与解析器伪代码 |
| v1.1 | 2026-10-21 | **向后兼容扩展**：`epgUrls` 提升为公共字段（任意 kind，支持 gzip）；新增 `adBlock`（域名/关键字，含自伤保护与拦截器契约）、`skipIntroSeconds`/`skipIntroCue`、`maxQuality`/`preferredCodec`、m3u `channels[]`（含 `desc`/`tvgId`/`logo`/`group`/`epgId`）；`Channel` 增加 `desc`；同步 schema、示例（同一文件混用 v1/v1.1）、校验器（旧 9 + 新 8 负例）与 `PLAYBACK_SPEC`/`SOURCE_IMPORT_SPEC` 对齐 |
