# 直播源采集与可用性验证方案

| 项 | 内容 |
| --- | --- |
| 任务 | t2 [t-src] 直播源采集与可用性验证 |
| 产出 | `docs/PLAN_LIVE.md`、`assets/live_seed.json`（406 KB，紧凑序列化 303 KB） |
| 采集/探测时间 | 2026-10-21 18:39–19:11（Asia/Shanghai） |
| 探测批次 | 18:47–18:50（API 批次）、18:52–18:54（扩展批次）、18:56–18:58（终验）、19:00–19:01（卫视补测）、19:03–19:05（双轮稳定性） |
| 覆盖 | 266 个频道、332 个源：央视 28、卫视 26、地方 212（每频道最多 1 主 + 3 备） |
| 结论 | 可直接作为 v1 内置种子；13 个卫视缺口 + 232 个"未知清晰度"由运行期健康检查、换源与远端刷新兜底 |

---

## 0. 结论摘要（TL;DR）

1. **采集**：从 6 个公开索引/列表聚合出 **2,940** 条候选（其中 134 条为 IPv6-only，本探测网络无 IPv6 出口，未计入结论），IPv4 实际探测 **2,806** 条。
2. **验证**：**538** 条 URL 返回合法 `#EXTM3U`；其中 **340** 条通过"播放列表 ≠ 404/403 + 首个媒体分片真实可下载"双重校验；按"每频道 1 主 + 最多 3 备"裁剪后 **332** 条写入种子。终验后又对全部 340 条做两轮独立复测：**321 条至少通过 1 轮（302 stable / 11 intermittent / 8 signed）**，19 条两轮均失败但此前通过；裁剪后发布 332 条（300 stable / 7 intermittent / 8 signed / 17 fallback_only）。
3. **频道**：种子含 **266** 个频道；**42 个频道有 ≥2 个源，34 个频道有 ≥2 个"健康"源**；224 个单源频道是主要风险面。
4. **清晰度**：`2160p 8 / 1080p 28 / 720p 26 / 576p 17 / 480p 4 / 360p 17 / 未知 232`。未知并非缺陷：大量地方台是直出 media playlist，既无 `RESOLUTION` 也无来源声明；客户端起播后以 `TracksInfo` 实测覆盖展示。
5. **覆盖缺口**：北京、天津、河南、重庆、四川、云南、贵州、广西、甘肃、吉林、兵团、康巴、厦门 13 个卫视在本探测网络无可用公开源（详见 §4.3）。
6. **关键工程结论**（已写进 §6）：生产环境**禁止启动时全量探测**。实测 40 并发会触发中转站限流（`101.35.240.114` 18 个源在末轮 0/36 全挂），客户端必须"**按需 + 低并发 + 主机级熔断**"。

---

## 1. 范围与合规

### 1.1 范围

- 央视：CCTV-1 ~ CCTV-17、CCTV-4K/8K、CCTV+、CGTN 多语种。
- 卫视：省级卫星频道（含三沙、延边、农林、海峡等）。
- 地方台：省/市/县级新闻综合、公共、影视、生活等地面频道。
- 协议：仅 HLS（`application/vnd.apple.mpegurl` / `application/x-mpegURL`）。

### 1.2 不在范围

- 付费/加密/DRM 频道、需要账号鉴权的运营商专网源（如 IPTV 组播、带 `authid` 之外还需机顶盒认证的源）。
- 港澳台与海外频道：本轮探测到少量可用源，但不属于 t2 验收范围，未写入种子；客户端可通过用户 m3u 导入补充。
- 画面级频道身份核验（OCR/人工看台）：本轮只做"URL 可播"验证，身份以来源列表标注为准（§8.2 风险）。

### 1.3 合规与免责

- 种子只保存**公开可访问的 URL 索引**，不转存、不转码、不分发任何音视频内容；版权归各电视台/内容方所有。
- 上线前需法务评估；产品必须提供"关闭内置源/仅用自定义源"的开关，并把内置源标记为"聚合自公开网络，可用性不保证"。
- 源站可随时改路径、加签名、封 UA/Referer；种子是"快照"，不是 SLA。

---

## 2. 采集来源

| 来源 | 入口 | 采集量 | 说明 |
| --- | --- | --- | --- |
| iptv-org API | `https://iptv-org.github.io/api/streams.json`、`channels.json` | 323 条 CN 流 / 154 个 CN 频道 | 结构化最好，含 `quality`、`referrer`、`user_agent` |
| iptv-org 播放列表 | `.../iptv/countries/cn.m3u`、`.../languages/zho.m3u`、`.../streams/cn.m3u` | 补充 73 条 | 提供 logo、group-title |
| suxuang/myIPTV | `ipv4.m3u`（1,201 条）、`ipv6.m3u`（605 条，未测） | 1,201 条 IPv4 | 国内聚合，含 `http-user-agent`/`http-referer` 属性，地方台覆盖最好 |
| vbskycn/iptv | `tv/iptv4.m3u` | 359 条 | 电信 `tsfile` 类源，可用率较高 |
| wwb521/live | `tv.m3u` | 108 条 | 补充央视/卫视线路 |
| fanmingming/live | `tv/m3u/ipv6.m3u`（55 条，未测） | — | 纯 IPv6，本机不可测 |

> **网络限制与镜像**：本机 `raw.githubusercontent.com`、`live.fanmingming.com` 超时；改用 `fastly.jsdelivr.net` / `ghproxy.net` 镜像拉取。所有来源均在 2026-10-21 当时可下载，文件 SHA 未固定，重新采集时内容可能变化。

去重规则：**URL 级去重**（同 URL 只测一次），频道级在种子生成阶段按 `group + 归一化台名` 合并。

---

## 3. 探测方法与判定

### 3.1 环境

- `curl 8.22.0 (x86_64-w64-mingw32) libcurl/8.22.0 Schannel`，Windows。
- UA：`Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36`。
- 出口：非中国大陆（`baidu.com` 200，但 `raw.githubusercontent.com`、`ivi.bupt.edu.cn`、大量国内 ISP IP 超时；IPv6 无路由）。**因此结果对大陆家庭宽带是保守下限**：本机不可达 ≠ 用户不可达。

### 3.2 两阶段判定（缺一不可）

**阶段 A：播放列表**

```bash
curl -sS -L --max-time 8 --connect-timeout 5 \
  --max-filesize 524288 \
  -A "$UA" [-e "$REFERER"] \
  -o body.bin -w '%{http_code}|%{content_type}|%{time_starttransfer}|%{url_effective}' \
  "$URL"
```

判定：HTTP 200（含跟随 301/302 后）且响应体以 `#EXTM3U` 开头。
若为 master playlist（含 `#EXT-X-STREAM-INF`），取最多 3 个 variant 继续；若为 media playlist（含 `#EXTINF`），取第一个分片。

**阶段 B：首个媒体分片**

```bash
curl -sS -L --max-time 8 -r 0-262143 -A "$UA" -o seg.bin "$SEG_URL"
```

判定：HTTP 200/206、`bytes > 0`、首字节为 TS 同步字节 `0x47` 或 `ID3` / `ftyp` / `moov` / `styp` 魔数。仅"播放列表 200"不算可用——实测大量 200 的 m3u8 里分片 404/403。

### 3.3 关键踩坑（工程上必须复用）

1. **`curl --max-filesize` 会丢弃部分响应**：超过上限时 curl 以 `exit 63` 结束且 `size_download=0`、输出文件不存在。第一版用它做分片校验，把大量正常源误判为失败。**分片校验必须去掉 `--max-filesize`，改用 HTTP Range + `--max-time`**。
2. **高并发触发限流**：40 并发下 `74.91.26.218`、`101.35.240.114` 等中转站开始 302 到失效地址或直接 404。降低到 10 并发并重试后，`74.91.26.218` 的 CCTV-1/2/3/4/7/8/10~17 全部恢复。**生产健康检查并发必须 ≤4，且只测当前频道**。
3. **302 中转签名**：`74.91.26.218:82/live/cctv1hd.m3u8` 会 302 到 `192.187.115.106:81/...?tm=...&key=...`，入口 URL 稳定、跳转目标带时效签名。种子保存**入口 URL**，客户端跟随重定向即可；不要缓存跳转后的地址。
4. **IPv6-only 源不可测**：`[2409:...]` 类 URL 在本机 100% 超时，不能据此判定源失效；客户端应按 `NetworkCapabilities` 是否有 IPv6 决定是否启用。

### 3.4 批次统计

| 批次 | 时间 | 候选 | 返回 m3u8 | 完全通过 |
| --- | --- | --- | --- | --- |
| iptv-org + myIPTV | 18:47–18:50 | 1,593 | 272 | 196 |
| 扩展列表（vbskycn/wwb521/…） | 18:52–18:54 | 1,213 | 251 | 177 |
| 终验（低并发 + 重试） | 18:56–18:58 | 538 | 481 playable | 481 |
| 卫视补测（此前从未返回 m3u8 的 349 条） | 19:00–19:01 | 349 | 9 | 7 |
| 双轮稳定性（对 340 条待发布源） | 19:03–19:05 | 340 | — | 310 两轮全过 / 11 过一轮 / 19 全挂 |

> "完全通过"按阶段 A+B 计；批次间有重叠，最终按 URL 去重后为 332 个发布源（每频道裁剪至 1 主 + 最多 3 备）。

---

## 4. 结果总览

### 4.1 规模

| 指标 | 数值 |
| --- | --- |
| 频道 | **266**（央视 28 / 卫视 26 / 地方 212） |
| 源（URL） | **332**（每频道 ≤4） |
| 有 ≥2 源 | 42 个频道 |
| 有 ≥2 个健康源 | 34 个频道 |
| 健康状态 | healthy 250 / flaky 12 / degraded 4 |
| 源稳定性 | stable 300 / intermittent 7 / signed 8 / unstable 17（fallback_only） |
| 宿主 | 127 个不同 host |
| 种子大小 | 406 KB（UTF-8 美化）/ 303 KB（紧凑） |

### 4.2 央视频道（28）

| 频道 | 清晰度 | 清晰度来源 | 源数 | 状态 |
| --- | --- | --- | --- | --- |
| CCTV-1 综合 | 720p | declared | 3 | healthy |
| CCTV-2 财经 | 720p | declared | 1 | healthy |
| CCTV-3 综艺 | 720p | declared | 2 | healthy |
| CCTV-4 中文国际 | 720p | declared | 2 | healthy |
| CCTV-5 体育 | 未知 | unknown | 2 | healthy |
| CCTV-5+ 体育赛事 | 未知 | unknown | 1 | healthy |
| CCTV-6 电影 | 576p | bandwidth | 1 | **degraded** |
| CCTV-7 国防军事 | 720p | declared | 1 | healthy |
| CCTV-8 电视剧 | 720p | declared | 4 | healthy |
| CCTV-8K 超高清 | 1080p | declared | 1 | healthy |
| CCTV-9 纪录 | 1080p | playlist-resolution | 1 | healthy |
| CCTV-10 科教 | 720p | declared | 2 | healthy |
| CCTV-11 戏曲 | 720p | declared | 1 | healthy |
| CCTV-12 社会与法 | 720p | declared | 3 | healthy |
| CCTV-13 新闻 | 720p | declared | 2 | healthy |
| CCTV-14 少儿 | 1080p | declared | 3 | healthy |
| CCTV-15 音乐 | 720p | declared | 3 | healthy |
| CCTV-16 奥林匹克 | 1080p | declared | 2 | healthy |
| CCTV-17 农业农村 | 1080p | declared | 2 | healthy |
| CCTV+ 1 | 360p | playlist-resolution | 1 | flaky |
| CCTV+ 2 | 576p | playlist-resolution | 1 | healthy |
| CGTN 英语 | 1080p | playlist-resolution | 6 | healthy |
| CGTN 纪录 | 1080p | playlist-resolution | 6 | healthy |
| CGTN 法语 | 1080p | playlist-resolution | 3 | healthy |
| CGTN 西语 | 1080p | playlist-resolution | 4 | healthy |
| CGTN 阿语 | 1080p | bandwidth | 2 | healthy |
| CGTN 俄语 | 未知 | unknown | 1 | healthy |
| CGTN 财经 | 1080p | playlist-resolution | 2 | healthy |

> CCTV-4K 未验证通过（唯一候选源不可达），未写入种子；CCTV-8K 的"8K"是频道名，当前实际码流 1080p。

### 4.3 卫视频道（26）与缺口

| 频道 | 清晰度 | 源数 | 状态 | 频道 | 清晰度 | 源数 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 东方卫视 | 2160p | 3 | healthy | 浙江卫视 | 未知 | 7 | healthy |
| 湖南卫视 | 2160p | 3 | healthy | 江苏卫视 | 2160p | 2 | healthy |
| 河北卫视 | 2160p | 4 | healthy | 黑龙江卫视 | 2160p | 2 | healthy |
| 内蒙古卫视 | 1080p | 3 | healthy | 安徽卫视 | 1080p | 2 | healthy |
| 广东卫视 | 576p | 2 | **degraded** | 湖北卫视 | 576p | 1 | **degraded** |
| 陕西卫视 | 576p | 1 | **degraded** | 江西卫视 | 未知 | 2 | healthy |
| 山东卫视 | 未知 | 1 | healthy | 深圳卫视 | 未知 | 2 | flaky |
| 山西卫视 | 未知 | 1 | healthy | 辽宁卫视 | 未知 | 1 | healthy |
| 青海卫视 | 未知 | 1 | healthy | 宁夏卫视 | 未知 | 1 | healthy |
| 新疆卫视 | 未知 | 1 | healthy | 西藏卫视 | 未知 | 2 | healthy |
| 海南卫视 | 未知 | 1 | healthy | 三沙卫视 | 未知 | 1 | healthy |
| 东南卫视 | 未知 | 1 | healthy | 海峡卫视 | 未知 | 1 | healthy |
| 延边卫视 | 未知 | 2 | healthy | 农林卫视 | 未知 | 1 | healthy |

**本轮未采到可用源的卫视（13）**：北京、天津、河南、重庆、四川、云南、贵州、广西、甘肃、吉林、兵团、康巴、厦门。
原因：这些台的公开源大多绑定大陆 ISP/CDN 或需要 Referer/地区授权，在本探测出口全部超时；不是"不存在源"。处理方式见 §6.7（远端刷新）与 §3.3（IPv6/地域）。

### 4.4 地方台（212）

按 `region` 分布（种子字段）：

| 地区 | 频道数 | 地区 | 频道数 | 地区 | 频道数 |
| --- | --- | --- | --- | --- | --- |
| 浙江 | 54 | 吉林 | 28 | 四川 | 16 |
| 河北 | 15 | 江苏 | 13 | 广东 | 10 |
| 黑龙江 | 9 | 新疆 | 8 | 其他 | 7 |
| 甘肃 | 6 | 海南 | 5 | 辽宁 | 5 |
| 安徽 | 4 | 山西 | 4 | 广西 | 4 |
| 福建 | 4 | 山东 | 3 | 河南 | 3 |
| 重庆 | 3 | 内蒙古 | 2 | 天津 | 2 |
| 湖北 | 2 | 上海/云南/湖南/陕西/全国 | 各 1 | | |

典型可用源宿主：`l.cztvcloud.com`（浙江县市台 38 条，两轮 76/76 全过）、`120.76.248.139`（bfgd 中转 20 条，40/40 全过）、`stream2/3.jlntv.cn`（吉林 16 条）、`jwcdnqx.hebyun.com.cn`（河北 7 条）、`stream.hrbtv.net`（哈尔滨 7 条）。

### 4.5 清晰度与质量来源

| 清晰度 | 源数 | 占比 |
| --- | --- | --- |
| 2160p | 8 | 2.4% |
| 1080p | 28 | 8.4% |
| 720p | 26 | 7.8% |
| 576p | 17 | 5.1% |
| 480p | 4 | 1.2% |
| 360p | 17 | 5.1% |
| 未知 | 232 | 69.9% |

| `quality_source` | 源数 | 含义 |
| --- | --- | --- |
| `playlist-resolution` | 20 | master playlist `#EXT-X-STREAM-INF:RESOLUTION=` 实测 |
| `declared` | 34 | 上游列表声明（如 iptv-org `quality`、名称里的 1080p/4K） |
| `bandwidth` | 46 | 无 RESOLUTION 时按 `BANDWIDTH` 估算（≥4 Mbps→1080p、≥2.2 Mbps→720p、≥1.2 Mbps→576p） |
| `unknown` | 232 | 直出 media playlist，无任何码率/分辨率元数据 |

**客户端约定**：`quality_source=unknown` 时 UI 显示"高清/标清未知"，起播后读取 `TracksInfo` 的 `VideoSize` 与 `bitrate` 回填，并持久化到本地源画像；不要用猜测值做质量排序的唯一依据。

### 4.6 稳定性分层

| stability | 含义 | 源数 | 客户端策略 |
| --- | --- | --- | --- |
| `stable` | 终验通过，且末次两轮独立探测均通过 | 300 | 可做首选，按 priority 排序 |
| `signed` | 同 stable，但 URL 自带 `txSecret/txTime/_upt` 等签名 | 8 | 可用；过期由 §6.6 处理，优先级低于 stable |
| `intermittent` | 两轮只过一轮 | 7 | 进频道先快速探测一次，成功再播 |
| `unstable` | 两轮均失败（此前通过过） | 17 | `fallback_only=true`，只在前三源都失败时尝试 |

**主机级教训**：`101.35.240.114` 的 18 个源在 18:56 终验全部通过，19:03 双轮复测 **0/36** 全挂——典型的"高并发后被限流/封禁"。这些源全部降级为 `fallback_only`（裁剪后保留 17 条）；客户端应实现"同一 host 连续失败 2 次 → 整域熔断 30 分钟"，不要逐条重试。

---

## 5. `assets/live_seed.json` 数据契约

### 5.1 顶层结构

```jsonc
{
  "schema_version": 1,
  "name": "live_seed_cn",
  "generated_at": "2026-10-21T19:11:00+08:00",
  "groups": [
    {"id": "cctv",      "name": "央视频道", "order": 1},
    {"id": "satellite", "name": "卫视频道", "order": 2},
    {"id": "local",     "name": "地方频道", "order": 3}
  ],
  "probe": {
    "method": "curl 两阶段探测…",
    "checked_window": "2026-10-21T18:47:00+08:00 ~ 2026-10-21T19:05:00+08:00",
    "stats": {"channels": 266, "fully_verified_sources": 332, "...": "..."},
    "network_note": "探测出口位于中国大陆以外…",
    "caveat": "频道身份未做画面级核验…"
  },
  "policy": {
    "playlist_timeout_ms": 8000,
    "quality_ladder": ["2160p", "1080p", "720p", "576p", "480p"],
    "low_end_default_max_quality": "1080p",
    "health": {"failure_backoff_seconds": [60, 300, 1800, 21600], "max_concurrent_checks": 4},
    "refresh": {"bundled_seed_always_available": true, "remote_manifest_check_hours": 24}
  },
  "channels": [ /* 见 5.2 */ ]
}
```

### 5.2 频道对象（与 `PLAN_SOURCES.md` §9.6 的 `ChannelSeedImporter` 对齐）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 稳定频道 ID，如 `cctv1`、`sat_浙江`、`loc_浙江新闻` |
| `name` | string | 展示名（中文），如 `CCTV-1 综合`、`浙江卫视` |
| `group` | string | **中文分组**：`央视频道/卫视频道/地方频道`（导入器可直接用） |
| `group_id` | string | 机器分组：`cctv/satellite/local` |
| `region` | string | 地方台省份；央视/卫视为空；无法识别为 `其他` |
| `quality` | string | 首选源清晰度（枚举见 5.3） |
| `quality_source` | string | 首选源清晰度来源 |
| `status` | string | `healthy / flaky / degraded`（见 5.4） |
| `source_count` | int | 源总数（含 fallback_only） |
| `urls` | string[] | **主用在前、最多 4 条**（供 `ChannelSeedImporter`/m3u 兼容层直接消费）。健康频道只列非 fallback_only 源；`degraded` 频道没有健康源，回退为仅有的 unstable 源 |
| `verified_at` | ISO8601 | 首选源最近一次完整验证时间 |
| `latency_ms` | int | 首选源播放列表 TTFB |
| `tvg_id` | string | 同 `id`，供 EPG/Logo 匹配 |
| `logo` | string? | 本轮未采集，固定 `null`；客户端用台标占位图 |
| `desc` | string? | 央视/卫视一句话简介；地方台为 `null` |
| `sources` | object[] | 完整源列表（含证据与健康度），见 5.3 |

### 5.3 源对象

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `url` | string | 播放地址（已清洗：去掉 `$LR…`、拼接残留、`&?key=…`） |
| `protocol` | string | 固定 `hls` |
| `quality` | string | `2160p/1080p/720p/576p/480p/360p/未知` |
| `quality_source` | string | `playlist-resolution/declared/bandwidth/unknown` |
| `resolution` | string? | master playlist 的 `RESOLUTION`（如 `1920x1080`） |
| `http_status` | int | 播放列表 HTTP 状态 |
| `content_type` | string | 播放列表 Content-Type |
| `ttfb_ms` | int | 播放列表首字节耗时 |
| `playlist_type` | string | `master` / `media` |
| `segment_verified` | bool | 首分片是否真实可下载 |
| `segment_bytes` | int | 首分片读取字节数（Range 262144 上限） |
| `segment_content_type` | string | 分片 Content-Type（`video/mp2t` 等） |
| `bitrate_bps` | int? | master playlist 的 `BANDWIDTH`；媒体直出源为 `null`（起播后由 `TracksInfo` 实测回填） |
| `stability` | string | `stable/signed/intermittent/unstable` |
| `health` | object | `{probes: 2, passes: 0..2, last_checked}` |
| `fallback_only` | bool | 仅末位备用（UI 不应展示为主线路） |
| `priority` | int | 1 起，越小越优先；已按"稳定度 → 清晰度 → TTFB"排序 |
| `note` | string? | 例如 `4K 源` |
| `source_list` | string | 来源列表标识，便于回溯 |
| `checked_at` | ISO8601 | 该源通过完整校验的时间 |

### 5.4 `status` 语义（客户端直接用）

| status | 条件 | 播放策略 |
| --- | --- | --- |
| `healthy` | 至少 1 个 `stable/signed` 源通过两轮探测 | 正常播放，按 priority 尝试 |
| `flaky` | 健康源只有 `intermittent` | 起播前先做一次 3 s 快速探测，失败直接换源 |
| `degraded` | 只剩 `unstable`（fallback_only） | 进入频道即提示"线路可能不稳定"，起播超时后自动换台/提示 |

### 5.5 示例（CCTV-1，节选）

```jsonc
{
  "id": "cctv1", "name": "CCTV-1 综合",
  "group": "央视频道", "group_id": "cctv", "region": "",
  "quality": "720p", "quality_source": "declared", "status": "healthy",
  "source_count": 3,
  "urls": [
    "http://74.91.26.218:82/live/cctv1hd.m3u8",
    "https://liveplay-srs.voc.com.cn/hls/tv/134_180adf.m3u8"
  ],
  "verified_at": "2026-10-21T18:56:01+0800", "latency_ms": 937,
  "tvg_id": "cctv1", "logo": null, "desc": "中央广播电视总台 CCTV-1 综合",
  "sources": [
    {
      "url": "http://74.91.26.218:82/live/cctv1hd.m3u8",
      "protocol": "hls", "quality": "720p", "quality_source": "declared",
      "http_status": 200, "content_type": "application/vnd.apple.mpegurl",
      "ttfb_ms": 937, "playlist_type": "media",
      "segment_verified": true, "segment_bytes": 262144,
      "segment_content_type": "video/mp2t",
      "stability": "stable", "health": {"probes": 2, "passes": 2, "last_checked": "2026-10-21T19:03:09+0800"},
      "fallback_only": false, "priority": 1, "source_list": "iptv-org/api"
    }
    // ……其余源省略
  ]
}
```

### 5.6 客户端消费建议

- 反序列化：`kotlinx.serialization` + `ignoreUnknownKeys = true`；`assets` 读取放到 `Dispatchers.IO`，首次解析（303 KB 紧凑 / 406 KB 美化）在 4 核 1.2 GHz 上约 20–40 ms（`org.json` 流式或 `Json.decodeFromString` 均可）；不要在主线程做。低端盒子建议打包紧凑版（`json.dumps(separators=(',',':'))`，303 KB），APK 压缩后约 60–80 KB。
- 导入器映射：`Channel(id, name, group, logo, tvgId, desc, urls)` + `ChannelUrl(url, quality, sourceId="seed:<id>", verifiedAt, latencyMs)`；种子源优先级最低（用户 m3u 覆盖内置），与 `PLAN_SOURCES.md` §9.6 一致。
- 播放前过滤：`fallback_only == true` 的源不要放进"线路选择"菜单，只作为自动兜底；`status=degraded` 的频道在列表上标记"线路不稳"。
- 频道搜索/归一化：`tvg_id` 与 `name` 都可用于 EPG 匹配；同名频道合并时保留种子源作为最后备选。

---

## 6. 失效自动降级与备用源策略

> 核心原则：**播放失败不是异常，是常态**。策略必须让 90% 的失效在用户无感知下完成切换；剩余 10% 给出明确、可操作的提示。

### 6.1 源健康状态机（按 URL 持久化）

```
                 success
   ┌──────────► HEALTHY ──────────┐
   │               │ fail          │ fail×2（同 host）
   │               ▼               ▼
 COOLDOWN ◄── SUSPECT ◄──────── HOST_BROKEN
   ▲               │
   │  backoff 到期  │ fail
   └───────────────┘
```

- **持久化**：Room 表 `source_health(url TEXT PRIMARY KEY, fails INT, cooldown_until INT, last_ok INT, last_error TEXT, host TEXT)`；冷启动先读本地健康度，再决定是否探测。
- **冷却档位**（秒）：`60 → 300 → 1800 → 21600`，成功一次立即清零（`success_resets_backoff=true`）。
- **主机级熔断**：同一 host 在 10 分钟内连续 2 条 URL 失败 → 该 host 全部源进入 30 分钟冷却（对应本轮 `101.35.240.114` 的教训）。
- **不做全量启动探测**：启动只加载种子与本地健康度；仅当用户进入频道时探测该频道的 1–2 个候选，避免 332 个 URL 并发把源站打挂。

### 6.2 播放失败处理链路（ExoPlayer）

```
用户选台
  └─ 选源：priority 升序，跳过 cooldown / fallback_only（除非无其他源）
      └─ prepare + 首帧超时 8s（弱网 2.4G 时 10s）
          ├─ READY → 播放；成功回写 HEALTHY，清空 fails
          └─ 失败（ERROR_CODE_IO_BAD_HTTP_STATUS / TIMEOUT / PARSING / DECODER_INIT）
              ├─ 同源重试 1 次（延迟 600ms）
              ├─ 仍失败 → 标记 SUSPECT / cooldown，切下一源（间隔 600ms，最多 3 条）
              └─ 3 条全失败
                  ├─ 该频道 status=degraded → 提示"线路暂不可用，正在尝试备用…"（再试 fallback_only，最多 2 条）
                  └─ 仍失败 → "该频道暂不可用" + 自动跳下一个可用频道（PLAYBACK_SPEC §2）
```

- 切源时 **`player.stop()` + 新 `MediaItem`**，复用同一个 ExoPlayer（1 GB 盒子只允许一个 codec 实例）；切换 250 ms 防抖，只处理最后一次按键。
- 解码类错误（`ERROR_CODE_DECODER_INIT_FAILED` / `UNSUPPORTED_FORMAT`）**不要在同源重试**，直接换源并记录"编码不兼容"；若设备不支持 HEVC，优先选 H.264 变体。
- 断流但网络正常（`ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`、播放列表 404）→ 重新拉取播放列表（HLS 可刷新），仍失败再换源。

### 6.3 选源打分（伪代码）

```kotlin
fun pickSources(ch: LiveChannel, health: SourceHealthStore, profile: PerfProfile): List<LiveSource> =
  ch.sources
    .filter { !it.fallbackOnly || ch.status == "degraded" }
    .filter { health.cooldownUntil(it.url) <= now }
    .sortedWith(compareBy<LiveSource>(
        { if (it.stability == "stable") 0 else if (it.stability == "signed") 1 else 2 },
        { -qualityRank(min(it.qualityRank, profile.maxQualityRank)) },
        { it.ttfbMs }
    ))
    .take(3)   // 主源 + 2 条备用；degraded 时额外允许 2 条 fallback_only
```

- `qualityRank`：2160 > 1080 > 720 > 576 > 480 > 360；低端档位由 `PerfProfile` 压到 1080p。
- 不用"最近一次成功"单一排序：旧的成功记录可能过时，`last_ok` 仅作同分时的 tie-breaker。
- 用户手动选线路时，把用户选择置顶并持久化（`SharedPreferences`），但冷却中的源仍标灰。

### 6.4 清晰度降级（与 t1/t3 参数一致）

1. **档位上限**：`min(源 maxQuality, PerfProfile 上限)`；LOW 档（1 GB / `isLowRamDevice`）默认 **1080p30 / 6 Mbps / H.264**，不选 4K/HEVC/60fps（`PLAYBACK_SPEC.md` §1、`PLAN_ARCHITECTURE.md` A11）。
2. **降档不重建播放器**：只改 `TrackSelectionParameters` 的 `maxVideoSize/maxVideoBitrate`（`PLAYBACK_SPEC.md` §6）。
3. **自动降档触发**：
   - 30 s 窗口掉帧率 >20% → 降一档并 toast「网络/性能不足，已切 720p」。
   - 连续 rebuffer ≥2 次/2 min → 降一档；仍卡 → 换源。
   - 弱网（2.4G WiFi / 测速 <4 Mbps）→ 直接以 720p 起播。
4. **升档**：连续 5 min 无 rebuffer 且当前码率 < 上限 → 试探升一档，失败回退并冷却 30 min。
5. **源本身只有 4K**：设备不支持时不要软解，明确提示"当前设备不支持该编码，请切换线路/清晰度"（`PLAYBACK_SPEC.md` §1.2）。

### 6.5 缓冲与低端性能参数（引用 t1，不重复造数）

- 直播：`minBuffer 6s / maxBuffer 15s / 8 MB`，`bufferForPlayback 1.5s`，`setBackBuffer(0)`（内存不随播放时长增长）。
- `HlsMediaSource`：`setAllowChunklessPreparation(true)`；直播不启用 `CacheDataSource`。
- 目标延迟：`targetOffsetMs = 3 × EXT-X-TARGETDURATION`（一般 15–30 s），不追求低延迟，优先稳定。
- 切台：`stop()` 后复用播放器；250 ms 防抖；**不预加载下一频道**（1 GB 设备双 codec 易失败，`PLAN_ARCHITECTURE.md` §7 明确禁止）。

### 6.6 签名源/短链源处理

- 种子标记 `stability=signed` 的 8 条源：`txSecret/txTime/_upt` 等签名可能过期。本轮实测 `txTime` 已过期（2024 年）仍可播放，说明部分源站"签名宽松"——**不能依赖**。
- 处理：
  1. 优先选 `stable` 源；`signed` 仅作备用。
  2. 播放前 GET 播放列表；若 403/404 且 URL 含签名参数 → 直接判失效，冷却 6 h，不发重试请求。
  3. 若源是"入口 URL 302 → 带签名地址"（如 `74.91.26.218`），入口不签名，可长期使用；客户端每次跟随重定向，**不缓存跳转后的地址**。
  4. 无法刷新的签名源（如 `tencentplay.gztv.com/...?txSecret=...`）在远端刷新时淘汰；本轮保留仅因当前仍可用。

### 6.7 种子刷新与远端更新

```
启动 / 每 24h（WorkManager，仅 WiFi，带抖动）
  └─ GET https://<cdn>/live_seed/manifest.json（ETag/If-None-Match）
      ├─ 304 或校验失败 → 用本地种子（bundled / last_good）
      └─ 200 且 schema_version 兼容
          ├─ 下载 live_seed.json（≤1 MB，超时 10s，2 次重试）
          ├─ 校验：JSON 合法、schema_version==1、channels 非空、channel.id 唯一
          ├─ 原子替换：写 tmp → fsync → rename；保留 last_good 一份
          └─ 下次进入直播页生效（不打断当前播放）
```

- **内置种子永不删除**：远端更新失败、文件损坏、schema 不兼容时回退到 bundled。
- 刷新只做"换名单"，不做"全量探活"；探活由播放时按需触发。
- 服务端建议：manifest 带 `generated_at`、`etag`、`min_app_version`；每次重新采集后 bump `generated_at`。
- 与用户导入源的合并：`seed:` 源优先级最低；用户 m3u 同 `tvgId`/归一化台名可覆盖内置 URL（`PLAN_SOURCES.md` §9.6）。

### 6.8 地域、IPv6 与网络类型

- **IPv6**：`NetworkCapabilities.TRANSPORT_*` + `hasCapability(NET_CAPABILITY_INTERNET)` 之外，用 `LinkProperties` 判断是否有全局 IPv6 地址；无 IPv6 时过滤 `[` 开头的 URL（本轮 134 条 IPv6-only 未验证）。有 IPv6 的盒子优先 IPv6（移动/联通 IPTV 常走 IPv6 组播单播化地址）。
- **地域**：种子 `network_note` 已声明探测出口；客户端无法可靠判断"是否大陆"，按失败冷却处理即可。若某源在国内可用、海外不可用，用户实际网络会自然恢复。
- **运营商**：电信/联通/移动的 `tsfile` 类源常限本网；同一频道多源时按"上次成功网络类型"加权（Wi-Fi SSID/BSSID 粗粒度，不采集隐私）。

### 6.9 用户可见的降级 UI

| 场景 | UI |
| --- | --- |
| 自动换源成功 | 右上角轻提示"已切换备用线路"（2 s 消失），不打断播放 |
| 单频道全部失败 | 居中卡片"该频道暂不可用" + 按钮【重试】【换台】【刷新源】 |
| 频道 degraded | 列表项角标"线路不稳"；进入时提示一次 |
| 清晰度自动降档 | toast"网络/性能不足，已切 720p"（可关闭） |
| 种子刷新失败 | 静默；设置页"源更新时间"显示上次成功时间 |
| 无网络 | 显示上次频道表；点击播放才报错，并提示检查网络（`PLAN_SOURCES.md` §7） |

### 6.10 可观测指标（本地，匿名可选上报）

- `channel_open_total` / `channel_play_success_total`（按 group、source_list）。
- `source_fail_total{url_host, error_code}`、`failover_total{from_priority,to_priority}`。
- `time_to_first_frame_ms`（P50/P90，按设备档位）。
- `rebuffer_ratio`、`auto_downgrade_total`。
- 本地环形日志保留 7 天、≤512 KB；不上传 URL 全量，仅 host 与错误码。

---

## 7. 复现与维护

### 7.1 复现探测（不依赖本仓库脚本）

```bash
UA='Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36'

# 1) 播放列表
code=$(curl -sS -L --max-time 8 --connect-timeout 5 --max-filesize 524288 \
  -A "$UA" -o /tmp/p.m3u8 -w '%{http_code}' "$URL")
grep -q '^#EXTM3U' /tmp/p.m3u8 || echo "not m3u8"

# 2) 首个分片（master 先取 variant）
SEG=$(grep -v '^#' /tmp/p.m3u8 | head -1)
curl -sS -L --max-time 8 -r 0-262143 -A "$UA" -o /tmp/seg.bin "$SEG_URL"
# 校验：HTTP 200/206、字节数 >0、首字节 0x47/ID3
```

流程：采集 → URL 去重 → 阶段 A（并发 ≤10）→ 阶段 B（仅对 A 通过者）→ 双轮稳定性 → 生成种子（清洗 URL/归一名/分组/排序）→ 抽样复测。

### 7.2 维护节奏

| 频率 | 动作 |
| --- | --- |
| 每周 | 抽样 20 个高频频道（CCTV-1/5/6/13、湖南/浙江/东方/江苏）跑两阶段探测，更新 `verified_at` |
| 每月 | 全量重跑一次，替换 `assets/live_seed.json`，bump `generated_at` |
| 源站变更/大面积失效 | 立即重跑；保留上一版 `last_good` |
| 新增频道 | 优先补央视/卫视缺口（§4.3），确认来源合法、双轮通过后再进种子 |

### 7.3 种子质量门（下次更新必须满足）

1. `schema_version` 不变；`channels` 非空且 `id` 唯一。
2. 每个发布的源必须 `segment_verified=true`；`fallback_only` 源可例外但需 `health.passes=0`。
3. `stable` 源占比 ≥85%（本轮 300/332=90.4%）；单源频道占比 ≤85%（本轮 224/266=84.2%）。
4. 央视 1–17 覆盖率 ≥90%；卫视覆盖率 ≥60%（本轮 26/39≈67%，下轮目标 ≥70%，缺口见 §4.3）。
5. 抽样 16 条源复测通过率 ≥85%（本轮 14/16=87.5%）。

---

## 8. 已知风险与限制

| # | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| 1 | **探测出口非大陆** | 部分大陆 ISP 源被判死；大陆可用率可能更高 | 客户端运行期健康检查会自然恢复；下一轮建议在大陆网络复测 |
| 2 | **频道身份未画面核验** | 聚合列表可能张冠李戴（已剔除明显错配：浙江地方频道冒充延边卫视、快手剪辑冒充湖南卫视等） | 上线前人工抽查央视/卫视；长期用 EPG 节目名交叉校验 |
| 3 | **签名/短链过期** | 8 条 signed 源可能失效 | §6.6：冷却、淘汰、远端刷新 |
| 4 | **源站限流** | 高并发探测触发封禁（101.35 教训） | 按需、≤4 并发、host 熔断 |
| 5 | **版权/合规** | 公开源也可能侵权 | 法务评估；提供关闭内置源；不转存内容 |
| 6 | **IPv6 源未覆盖** | 移动/联通 IPv6 优质源缺失 | 客户端支持 IPv6；后续在双栈环境补采 |
| 7 | **清晰度元数据缺失 70%** | 无法按清晰度精确排序 | 起播后 `TracksInfo` 回填；排序只用已知项 |
| 8 | **地方台长尾多、单源多** | 224 个单源频道，失效即不可看 | 用户可导入 m3u 补充；种子远端刷新；UI 标"线路不稳" |
| 9 | **港澳台/海外未纳入** | 产品若需要需另采 | 用户导入 m3u；后续单独任务 |

---

## 9. 验收证据（t2）

| 验收项 | 证据 |
| --- | --- |
| 采集央视频道 | 种子 28 个央视条目（CCTV-1~17 全覆盖、8K、CCTV+×2、CGTN×7）；CCTV-4K 唯一候选源不可达，未写入 |
| 采集卫视 | 26 个省级卫视；缺口 13 个已列明原因（§4.3） |
| 采集地方台 | 212 个，覆盖 25 个省级区域（§4.4） |
| 逐个 curl 探测 | 2,806 条 IPv4 候选全部经 curl 两阶段探测；538 条返回 m3u8；340 条分片级通过并复测两轮；按每频道 1 主 + 3 备裁剪后发布 332 条（§3、§4.6） |
| 确认返回 m3u8 | 每条发布源记录 `http_status=200`、`content_type=application/(vnd.apple.)mpegurl`、`segment_verified=true`、`segment_bytes>0` |
| 标注分组 | 种子 `group/group_id/region`；央视 28 / 卫视 26 / 地方 212 |
| 标注清晰度 | 每条源 `quality + quality_source`；频道级 `quality`；未知项明确标记（§4.5） |
| 失效自动降级与备用源 | §6 完整策略：状态机、退避、主机熔断、质量阶梯、签名处理、远端刷新、UI 降级 |
| 抽样复测 | 16 条随机源复测 14 通过（87.5%）；2 条失败均为 `101.35.240.114`，已全量降级为 fallback_only |
| 可消费性 | 种子含 `urls[]`、`tvg_id`、`desc`、`verified_at`、`latency_ms`，与 `PLAN_SOURCES.md` §9.6 的 `ChannelSeedImporter` 字段别名对齐 |

---

## 10. 附：种子关键统计（机器可读，`probe.stats`）

```json
{
  "probed_initial": 2806,
  "ipv6_only_excluded": 134,
  "playlist_m3u8_ok": 538,
  "fully_verified_sources": 332,
  "channels": 266,
  "by_group": {"cctv": 28, "satellite": 26, "local": 212},
  "by_stability": {"stable": 300, "signed": 8, "intermittent": 7, "unstable": 17},
  "by_quality": {"2160p": 8, "1080p": 28, "720p": 26, "576p": 17, "480p": 4, "360p": 17, "未知": 232}
}
```

> 本文件与 `assets/live_seed.json` 同时生成；种子内 `probe` 字段记录同样的统计与免责声明，客户端可直接展示"源更新于 …，共 266 个频道"。

---

## 11. 待续 / 未覆盖与死链记录（时间盒说明）

> 队长时间盒：本任务于 19:14 交付**可用的部分成品**（266 频道 / 332 源已全部完成双校验），以下为未完成项与已丢弃死链的原因，供下一轮迭代直接接手。

### 11.1 待续（下一轮优先级从高到低）

1. **13 个卫视缺口**：北京、天津、河南、重庆、四川、云南、贵州、广西、甘肃、吉林、兵团、康巴、厦门。需在大陆网络 / 对应运营商环境复测，或接入官方 HLS/App 公开流；本轮候选全部超时，不能据此判定永久失效。
2. **IPv6-only 源 134 条**：`suxuang/myIPTV ipv6.m3u` 605 条、`fanmingming/live ipv6.m3u` 55 条等，本探测机无 IPv6 路由，未验证；有双栈环境时补测（移动/联通 IPTV 优质源多在此）。
3. **CCTV-4K**：唯一候选 `198.204.240.250:82` 返回 301 无有效跳转，未写入种子；CCTV-8K 目前是 1080p 码流。
4. **频道身份人工抽验**：央视/卫视优先看台标+EPG 节目名，剔除"聚合源张冠李戴"（§11.3 已剔除明显错配，但不等于全部正确）。
5. **232 条未知清晰度**：起播后 `TracksInfo` 回填；离线补采可用 `ffprobe` 对首分片取 `width/height/bit_rate`。
6. **港澳台/海外频道**：范围外，未纳入；客户端可由用户 m3u 导入。

### 11.2 死链与丢弃记录（按"死链直接丢弃"规则）

| 源/主机 | 现象 | 处理 |
| --- | --- | --- |
| `101.35.240.114:88`（18 条，央视/卫视中转） | 18:56 终验全过；19:03 双轮 0/36，疑似高并发限流/封禁 | 降级 `fallback_only`（裁剪后 17 条），主机熔断 30 min |
| `222.169.85.8:9901` 广东卫视 | 两轮只过一轮 | `intermittent` → 排到末位备用 |
| `69.30.245.50`、`198.204.240.250:82`、`175.8.28.207:9999`、`218.13.14.6:60050`、`148.135.93.213:81`（CCTV-6 备选） | 超时 / 301 无跳转 / 500 | 丢弃；CCTV-6 仅剩 1 条，`degraded` |
| `ivi.bupt.edu.cn`、`112.25.48.68`、`live.fanmingming.com`、`raw.githubusercontent.com` | 本机出口不可达 | 未计入候选，不作为源 |
| `play.kankanlive.com` → `livehwc4.com`、`hplayer1.juyun.tv` → `livehwc4.com` | master 的 variant 域名不可达 | 丢弃 |
| `38.75.136.137:98`（CCTV-Storm/Golf/Health 等付费频道） | 可播放但属付费数字频道 | 超范围，不写入 |
| NTD/ABN/Angel/PeaceTV/人间卫视 等 | 可播放但属海外/宗教频道 | 超范围，不写入 |

### 11.3 已剔除的"张冠李戴"（身份核验）

| 错误标注 | 实际 | 处理 |
| --- | --- | --- |
| 内蒙古卫视 ← `cztv.com/.../channel007/1080p.m3u8` | 浙江新闻 | 丢弃该源 |
| 延边卫视 ← `l.cztvcloud.com/.../SXxinchang2/720p.m3u8` | 浙江新昌 | 丢弃该源 |
| 湖南卫视 ← `txmov2.a.kwimgs.com/...` | 快手剪辑（非直播） | 丢弃该源 |
| 海南卫视 ← `nn.7x9d.cn/...?id=hnws` | 疑为河南卫视（hnws） | 丢弃该源 |
| CGTN 法语 ← `espanol-liveali.cgtn.com/...` | CGTN 西语 | 丢弃该源 |
| CCTV-13 ← `cztv.com/.../channel21/1080p.m3u8` | 身份不明的地方 relay | 丢弃该源 |

> 工具与原始结果已放在 `tools/live/`（见该目录 `README.md`）：脚本可复现采集→两阶段探测→双轮稳定性→种子生成的全流程；`final_verify.json` / `stability.json` / `spotcheck.json` 为原始证据。
