# 直播 ≤720p 兜底源报告（X4-50 安全档）

| 项 | 内容 |
| --- | --- |
| 任务 | t16 补齐每频道 ≤720p 兜底源（API≤23 设备） |
| 触发 | LeTV X4-50（MStar 6A938 / API 23）实测 **>720p 解码/纹理路径整机不可用**；1080p 直播、1024p B站短视频黑屏，720p/240p 正常 |
| 产物 | `assets/live_seed.json`（新增 `safe_tier_urls` / `has_safe_tier` / `safe_tier_height`，每个源 `safe_tier`）<br>`assets/live_safe.json`（每频道一条 ≤720p 兜底源，与 seed 266 频道对齐）<br>`docs/LIVE_SAFE.md`（本文件） |
| 统计口径 | 以 **PyAV 首分片实测高度 180–720** 且播放列表 + 首分片可读为准；不采用 manifest 自报清晰度 |
| 结果 | **低端档可用 163/266**；缺口 103 已由 UI 过滤并给出原因，不阻塞发布。原「<10」指标标注为**公开网络不可达，已用 UX 过滤替代**（详见 §5）；发布门槛改为「safeMode 默认过滤 + has_safe 正确」。**v0.2.2 CDN 分档**：safe 首位 slow 频道 **2**（≤3），`cdn_class=fast` 频道 **249**（详见 §3.7） |
| 时间 | 2026-10-21 20:31–21:01（Asia/Shanghai） |

---

## 1. 结论摘要

1. **已覆盖 163/266 频道（61.3%）**，按分组：央视 **24/28**、卫视 **17/26**、地方 **122/212**。
2. 共验证 **198 条 ≤720p 源**（720p 98、576p 55、540p 11、480p 15、360p 4、240p 11、600p 3、528p 1），写入种子；每频道最多 4 条源，且**必含至少一条 safe 源**（有 safe 时）。
3. `assets/live_safe.json` 与 seed **266 个频道一一对齐**，163 条带 URL、103 条为 `safe:false` + 原因。
4. **低端档口径**：`has_safe=true` 的频道 163 个；103 个仅 1080p 的频道由客户端在 safeMode 下**过滤并在 UI 给出原因**，不阻塞发布。原「缺口 <10」指标在公开网络上不可达（这 103 个频道只有 1080p 单源），已用 UX 过滤替代，详见 §5。
5. 相对基线（本任务开始前实测 140 个频道无 safe 源）**减少 37 个**；央视/卫视缺口从 32 个（10+22）降到 13 个（4+9）。

---

## 2. 方法与数据来源

### 2.1 判定标准（严格）

每条候选必须同时满足：

1. `curl` 拉取播放列表：HTTP 200 且 body 以 `#EXTM3U` 开头；
2. 首分片：HTTP 200/206、字节 > 0、TS/fMP4 魔数；
3. **PyAV 解码首分片**（本地文件，避免 PyAV 选错 variant / CDN 跟随差异），`video` 流高度 **180 ≤ h ≤ 720**。

> 关键修正：此前 `tools/probe_live_quality.py` 直接 `av.open(url)` 会选最高 variant，产生"源是 1080p"的误判（例如 `74.91.26.218` 的 CCTV-3、CCTV-15 实际为 720p）。本任务改为**先取首分片再本地解码**，并对 master playlist 逐 variant 测量，见 `tools/live/remeasure_segments.py`。

### 2.2 数据来源

| 来源 | 用途 | 规模 |
| --- | --- | --- |
| 现有 seed 391 条源 | 首分片复测、YouTube 式误判修正 | 266 频道 |
| 原候选池（iptv-org / myIPTV / vbskycn / wwb521） | 已探测的替代 URL | 2,806 条 |
| best-fan/iptv-sources（每日检测）`cn_cctv_status / cn_province_status / cn_all_status` | **低清晰度（[576]/[720]）IPTV 源**，含可达的 `tsfile` 平台 | 91 条去重 URL |
| imDazui/Tvlist-awesome-m3u-m3u8（224 个 m3u） | 全国/地方台聚合源，补充吉林、浙江等地方台 sd 源 | 39,637 条去重 URL |
| 手工验证 | bestv 多码率路径（`/155/2`=720、`/155/1`=478）、IPTV 576p 卫视源 | 5 条 |

### 2.3 处理流程

```
现有 seed 391 源
  └─ 首分片 PyAV 复测（remeasure_segments.py）→ 直接标记 safe / 修正误判
      └─ 对仍无 safe 的频道：
          1) 低清晰度 URL 重写（1080p→720p/576p、hd→sd、bestv 码率位）
          2) master playlist 逐 variant 测量
          3) 公开列表候选（best-fan / imDazui）按名称匹配，限 6–8 条/频道
          4) 所有候选再过一遍「播放列表+首分片+PyAV ≤720」严格校验
              └─ 通过 → 合并进 seed（最多 4 源/频道，保证 safe 在列）
                  └─ live_safe.json 取每频道最高档的 safe 源（720 优先）
```

工具（均在 `tools/live/`，可复现）：

| 文件 | 说明 |
| --- | --- |
| `remeasure_segments.py` | 现有源首分片 PyAV 复测（master 逐 variant） |
| `find_safe.py` / `find_safe2.py` | 低码率 URL 重写 + 原候选池探测 |
| `find_safe3.py` | best-fan 每日低清晰度列表 |
| `find_safe4.py` / `find_safe5.py` | imDazui 聚合列表（4 个主文件 / 全部 224 文件） |
| `validate_safe.py` | **发布前终验**：所有候选 safe URL 重新测量两次，取并集 |
| `apply_safe.py` | 合并进 seed、生成 live_safe.json、计算统计 |

---

## 3. 结果

### 3.1 覆盖

| 分组 | 频道数 | 有 safe 源 | 缺口 |
| --- | --- | --- | --- |
| 央视频道 | 28 | **24** | 4 |
| 卫视频道 | 26 | **17** | 9 |
| 地方频道 | 212 | **122** | 90 |
| **合计** | **266** | **163（61.3%）** | **103** |

### 3.2 已覆盖的央视/卫视（节选）

- **央视（24/28）**：CCTV-1~15、CCTV-17、CCTV+ 1/2、CGTN 英语/纪录/法语/西语/阿语/俄语。
  - 典型源：CCTV-3 576p `120.198.95.220:9901/tsfile/live/1017_1.m3u8`；CCTV-5 720p `124.228.160.112:9901/tsfile/live/0016_1.m3u8`；CCTV-15 576p `124.228.160.112:9901/tsfile/live/0015_1.m3u8`。
- **卫视（17/26）**：东方（720 via bestv `/155/2`）、浙江（720 via cztv channel01）、江苏、安徽、山东、山西、广东、深圳、新疆、西藏、黑龙江、内蒙古、河北、三沙、东南、海峡、陕西。
  - 典型源：东方卫视 720p `bp-resource-dfl.bestv.cn/155/2/video.m3u8`；安徽/广东/江苏/深圳/新疆 576p（`tsfile` 平台）；浙江卫视 720p `ali-m-l.cztv.com/channels/lantian/channel01/720p.m3u8`。
- **地方台**：122 个，覆盖浙江（cztv 720p）、吉林（jlntv `sd` 576p/240p）、四川、河北、黑龙江、甘肃、广东等。

### 3.3 safe 源清晰度分布

| 高度 | 源数 | 高度 | 源数 |
| --- | --- | --- | --- |
| 720 | 98 | 480 | 15 |
| 576 | 55 | 360 | 4 |
| 540 | 11 | 240 | 11 |
| 600 | 3 | 528 | 1 |

> 严格 ≤720：没有把 768p（1 个频道）算作 safe；该频道仍计入缺口。

### 3.4 种子改动

- 每源新增：`safe_tier`（是否 ≤720p）、`quality_source: "pyav-segment"`（safe 源）。
- 每频道新增：`safe_tier_urls[]`（最多 4 条，720 优先）、`has_safe_tier`、`safe_tier_height`。
- 频道源仍**按 4K→1080p→720p→… 排序**，不破坏高清设备体验；低端设备/API≤23 直接取 `safe_tier_urls[0]`。
- 每频道最多 4 条源；有 safe 源时**保证 safe 源在列**（必要时用 safe 顶替第 4 条）。
- `assets/live_hd.json` **未改动**（高清专区保持不变）。
- 顶层 `probe.stats` 增加 `channels_with_safe_tier=163` / `channels_without_safe_tier=103`。

### 3.5 HD 备用 CDN（给高清设备，非 safe）

对 103 个缺口频道中仍有「同频道不同 CDN」的，挑最快且实测可播的 1080p 源补进 seed 备用位（每频道 ≤4 源，未标记 safe）：

| 频道 | 备用 CDN | 实测 |
| --- | --- | --- |
| CCTV-16 奥林匹克 | `113.57.140.161:10081/newlive/live/hls/17/live.m3u8` | 1080p |
| 湖北卫视 | `221.226.51.220:50081/newlive/live/hls/36/live.m3u8` | 1080p |
| 辽宁卫视 | `120.198.95.220:9901/tsfile/live/1051_1.m3u8` | 1080p |
| 可克达拉综合 | `test5.btzx.com.cn/live/kkdl.stream/playlist.m3u8` | 1080p |
| 南国都市4K | `r.jdshipin.com/nWaZO` | 1080p |

其余候选要么与现有源同 URL（只是不同 query），要么当前不可达；真正不同 CDN 的仅 5 条，已全部写入。高清设备会按 4K→1080p 排序自动使用。

### 3.6 `assets/live_safe.json` 结构

```jsonc
{
  "schema_version": 1,
  "name": "live_safe_cn",
  "generated_at": "2026-10-21T21:01:00+08:00",
  "summary": {"channels": 266, "with_safe": 163, "without_safe": 103},
  "channels": [
    {
      "id": "sat_东方", "name": "东方卫视", "group": "卫视频道", "group_id": "satellite",
      "region": "", "safe": true, "has_safe": true,
      "url": "http://bp-resource-dfl.bestv.cn/155/2/video.m3u8",
      "measured_height": 720, "measured_width": 1280,
      "codec": "h264", "fps": 25.0, "tier": "720p",
      "safe_tier_urls": ["http://bp-resource-dfl.bestv.cn/155/2/video.m3u8"],
      "checked_at": "2026-10-21T21:00:20+08:00", "null_reason": null
    },
    {
      "id": "cctv16", "name": "CCTV-16 奥林匹克", "group": "央视频道", "group_id": "cctv",
      "region": "", "safe": false, "has_safe": false, "url": null,
      "measured_height": null, "measured_width": null,
      "codec": null, "fps": null, "tier": null, "checked_at": null,
      "safe_tier_urls": [], "null_reason": "仅 1080p/4K 源，公开网络无 ≤720p 变体"
    }
  ]
}
```

客户端（API≤23）建议：**只看 `safe:true` 的频道**；`safe:false` 的频道在低端设备上隐藏或提示"该频道需要高清设备"。

---

### 3.7 CDN 分档（v0.2.2）

切台慢的主因是 `74.91.26.218:82` 分片 TTFB 2–3s（真机 warm_zap P90 2317ms 全部来自该 host）。t21 对 **safe 首位落在 74.91 的 12 个 CCTV 频道**逐个替换，并给所有源打 `cdn_class`。

**判定**：`tools/live/probe_ttfb.py` 连续 3 次测首分片 TTFB 取中位数（抖动时重测 5 次）；`<1200ms` 为 `fast`，否则 `slow`；同时要求 playlist `#EXTM3U` + 首分片可读 + PyAV 实测高度（safe 位 ≤720，HD 位 1080）。

**12 个慢源频道替换结果（safe 首位）**：

| 频道 | 新 safe 首位 | 实测高度 | 分片 TTFB 中位数 |
| --- | --- | --- | --- |
| CCTV-1 综合 | `36.136.38.87:9901/tsfile/live/0001_1.m3u8` | 576p | 86ms |
| CCTV-2 财经 | `36.136.38.87:9901/tsfile/live/0002_1.m3u8` | 576p | 1085ms |
| CCTV-4 中文国际 | `36.136.38.87:9901/tsfile/live/0004_1.m3u8` | 576p | 89ms |
| CCTV-7 国防军事 | `36.136.38.87:9901/tsfile/live/0007_1.m3u8` | 576p | 1071ms |
| CCTV-8 电视剧 | `124.228.160.112:9901/tsfile/live/0008_1.m3u8` | 576p | 85ms |
| CCTV-10 科教 | `36.136.38.87:9901/tsfile/live/0010_1.m3u8` | 576p | 72ms |
| CCTV-11 戏曲 | `124.228.160.112:9901/tsfile/live/0011_1.m3u8` | 576p | 92ms |
| CCTV-12 社会与法 | `124.228.160.112:9901/tsfile/live/0012_1.m3u8` | 576p | 96ms |
| CCTV-13 新闻 | `36.136.38.87:9901/tsfile/live/0013_1.m3u8` | 576p | 70ms |
| CCTV-14 少儿 | `36.136.38.87:9901/tsfile/live/0014_1.m3u8` | 576p | 78ms |
| CCTV-15 音乐 | `36.136.38.87:9901/tsfile/live/0015_1.m3u8` | 576p | 80ms |
| CCTV-17 农业农村 | `124.228.160.112:9901/tsfile/live/0017_1.m3u8` | 576p | 90ms |

同时修复：CGTN 西语 → `amag...cgtn-es-rakuten`（360p，154ms）；浙江卫视 → `ali-m-l.cztv.com/.../channel001/720p.m3u8`（720p，168ms）；新疆卫视 → `120.198.95.220:9901/tsfile/live/1064_1.m3u8`（576p，57ms）。

**host 分档（按本次全量 TTFB 中位数；n=源数）**：

| 分档 | host | n | 中位 TTFB |
| --- | --- | --- | --- |
| fast | `l.cztvcloud.com` | 39 | 80ms |
| fast | `ali-m-l.cztv.com` | 25 | 163ms |
| fast | `stream5.jlntv.cn` | 13 | 195ms |
| fast | `124.228.160.112:9901` | 12 | 92ms |
| fast | `36.136.38.87:9901` | 10 | 84ms |
| fast | `120.198.95.220:9901` | 4 | 304ms |
| fast | `english-livetx.cgtn.com` / `english-livebkali.cgtn.com` | — | 实测 <1200ms |
| **slow（策略）** | `74.91.26.218:82` | 14 | 暖缓存中位 617ms，但真机 2–3s、波动大，**统一标 slow** |
| slow | `live.yantaitv.cn` | 2 | 2394ms（当前 404） |
| slow | `dash4.antik.sk` | 2 | 2015ms |
| slow | `dash3.antik.sk` | 1 | 1937ms |
| slow | `192.151.150.154` | 1 | 2052ms |
| slow | `112.27.5.218:9901` | 1 | 3093ms |
| slow | `45.186.106.147:8880` | 1 | 1536ms |

> 74.91 仍保留为后备源（14 条，`cdn_class=slow`），但不再占任何频道的 safe 首位。

**数据字段**（seed / live_safe）：
- 每源：`cdn_class`（fast/slow）、`segment_ttfb_ms`（中位数）、`cdn_host`。
- 每频道：`safe_tier_cdn_class`、`primary_cdn_class`、`cdn_class`（safe 首位优先）。
- `live_safe.json` 每条：`cdn_class` + `segment_ttfb_ms`；无 safe 的频道为 null。

**v0.2.2 验收统计**（可复现）：

| 指标 | 结果 |
| --- | --- |
| safe 首位为 slow CDN 的频道数 | **2**（目标 ≤3）——烟台公共、烟台经济科技（`live.yantaitv.cn` 当前 404/2.4s，无更快的 ≤720 替代） |
| safe 首位为 74.91 的频道数 | **0**（原 12） |
| `cdn_class=fast` 的频道数 | **249** / 266（safe 频道 161/163；另 2 个 safe 频道 slow） |
| `cdn_class=slow` 的频道数 | 17 |
| 数据文件 | seed 412 源；live_safe 163 has_safe |

## 4. 仍缺 ≤720p 的 103 个频道

### 4.1 央视/卫视（13）

| 频道 | 现状 | 已知候选为何不可用 |
| --- | --- | --- |
| CCTV-16 奥林匹克 | 1 条 1080p media | 无 master 变体；备选 `198.204.240.250` 302 失效、`69.30` 1080p |
| CCTV-5+ 体育赛事 | 仅 bfgd 中转（实测 error） | `120.76.248.139` bfgd 段主机空响应；best-fan 576p 入口指向不可达 IP |
| CCTV-8K | 1 条 1080p | 全网只找到 1080p 伪 8K 源 |
| CGTN 财经 | master 1080p | 低码率 variant 为 1080p；amagi 源 1080p |
| 农林卫视 | 仅 bfgd 中转（段主机 502/空响应） | 无公开 ≤720 源 |
| 宁夏卫视 | 仅 bfgd 中转 | 同上 |
| 延边卫视 | srs.iyb983.cn 1080p | 无低码率变体 |
| 江西卫视 | 112.27.5.218 tsfile 1080p | 同平台 576p ID 不可达 |
| 海南卫视 | 仅 bfgd 中转 | 同上 |
| 湖北卫视 | 原 101.35 已限流、221.226 1080p | 无公开 ≤720 |
| 湖南卫视 | mgtv 4K/1080p + kwimgs 剪辑（身份错误已剔除） | 无公开 ≤720 |
| 辽宁卫视 | 120.198 1080p / 36.136 576p 段高 0（音频/异常） | 576p 入口无有效视频轨 |
| 青海卫视 | 113.25.252.226 576p 段高 0 | 同上 |

### 4.2 地方频道（90）

主要是县级/市级单源频道，例如：万荣综合、易门综合、大宁综合、乐至综合、仁寿综合、名山综合、夹江新闻综合、松潘新闻综合、泸县新闻综合、金川新闻综合、龙泉新闻综合、滨海新闻综合、亳州农村、固镇新闻、安徽导视、祁门综合、太谷新闻综合、怀仁综合、南国都市、广东大湾区、广东民生、广西国际、来宾综合、伊犁经济法制、可克达拉综合、奎屯哈萨克语、玛纳斯综合、苏州新闻综合、镇江新闻综合、邯郸公共/科技教育、任丘综合、兴隆综合、滦平新闻、新野综合、永城综合、荥阳综合、上虞系列、余姚/余杭/庆元/松阳/永嘉/青田/象山/遂昌等浙江县市台，以及甘肃、福建、辽宁、黑龙江部分频道。

**原因分类**（按出现频次）：

1. **单源 1080p，无低码率变体**：种子中该频道只有一条 media playlist（如 `l.cztvcloud.com/.../720p.m3u8` 实际输出 1920×1080）；master 只有一个 1080p variant。占 ~55%。
2. **可达的替代源不存在**：公开列表中的 720p/576p 候选大多落在本探测出口不可达的 ISP 地址（`101.35.240.114`、`120.76.248.139` 的段主机、`112.27.*`、`148.135.93.213`、`218.13.14.6` 等），播放列表本身常 200 但分片 0 字节/超时。占 ~25%。
3. **低码率变体是音频**：`jlntv` 的 `sd` master 只有 `BANDWIDTH=66000` 的 AAC 轨；`satellitepull.cnr.cn` 的 231 kbps variant 只有音频。占 ~10%。
4. **身份不匹配被主动剔除**：`kwimgs` 快手剪辑冒充湖南卫视、`cztv channel007` 冒充内蒙古卫视、`nn.7x9d id=hnws` 冒充海南卫视等，宁可缺失不写错源。占 ~5%。
5. **无任何公开源**：少数县级台（如部分甘肃/吉林县台）全网只有一条 1080p 源。占 ~5%。

---

## 5. 「<10」不可达的原因与 UX 过滤替代

**公开网络的数据现实**：这 103 个频道在可达网络上**只有 1080p 单源**，不存在可验证的 ≤720p 公开流。继续扩大抓取（已扫 224 个列表、39,637 条 URL）没有新增；把不可达的 720/576 候选写入 seed 会重演"看似有源、实际黑屏"的问题，违背本次"实测 ≤720 才收录"的要求。

**收口口径（队长决策 B）**：保留 `live_safe.json` 全部 266 条（null + 原因是 UI 所需的信息），由客户端在 safeMode 下过滤；低端档实际可用 163/266，缺口 103 **已过滤并给出原因，不阻塞发布**。原「<10」指标不再作为发布门槛，仅作为公开网络数据完整度记录。

### 5.1 低端档交互契约（engineer 按此实现）

1. **safeMode == true**（API≤23，或 `PerfProfile.maxQuality <= 720p`）：直播列表**默认只渲染 `has_safe=true` 的 163 个频道**；列表顶部固定提示「本机已过滤 103 个仅 1080p 的频道（按 MENU 查看）」。
2. **显示全部**：用户按 MENU/设置切换「显示全部频道」；`has_safe=false` 的频道正常列出，但带角标「本机可能黑屏」，点击播放前弹二次确认（「该频道仅有 1080p 源，本机可能无法播放，仍要尝试？」）。
3. **自动切台/连播**：`CH+/CH-`、自动跳下一个、收藏连播等路径**跳过 `has_safe=false`** 的频道；手动选择不受此限制。
4. **数据读取**：以 `assets/live_safe.json` 的 `has_safe` / `safe_tier_urls` / `null_reason` 为准；`null_reason` 直接用于「已过滤」列表的原因文案。


可选方案（需产品/队长决策）：

| 方案 | 效果 | 代价 |
| --- | --- | --- |
| **A. 接受 163/266 + UX 过滤（已采纳，队长决策 B）** | X4-50 默认只显示 163 个 safe 频道；缺口 103 在「显示全部」中给出原因与风险提示 | 低端档少 103 个地方台；高清档完整 |
| **B. 低端包只保留 163 个 safe 频道** | 指标归零，低端体验完整 | 低端设备少 103 个地方台（高清设备仍可用完整 seed） |
| **C. 服务端 720p 转码/代理** | 全部频道可看 | 需要服务器与带宽，超出纯客户端范围 |
| **D. 从大陆家宽/运营商网络复测** | 可能找回 20–40 个（本出口非大陆，许多 ISP 源不可达） | 需要真实大陆网络环境 |
| **E. 接入运营商 IPTV（组播/单播鉴权）** | 画质与稳定性质变 | 需要运营商授权与专网，非公开源 |

**最终收口**：采纳 A+B 组合——`live_safe.json` 保留全部 266 条（含 103 条 null+原因，供 UI 做「已过滤」列表与原因提示），客户端 safeMode 按 `has_safe` 过滤；seed 保留 266 个频道供高清设备。**不再以「缺口 <10」作为发布门槛**，该数字仅作为公开网络数据完整度记录。

---

## 6. 复现与验收命令

```bash
cd "/d/Go Web/tv"

# 1) seed / live_safe JSON 合法性 + 频道对齐 + 无坏 safe 源
python - <<'PY'
import json
s=json.load(open('assets/live_seed.json',encoding='utf-8'))
d=json.load(open('assets/live_safe.json',encoding='utf-8'))
assert len(s['channels'])==266
assert len(d['channels'])==266
assert {c['id'] for c in s['channels']}=={c['id'] for c in d['channels']}
safes=[c for c in s['channels'] if c.get('has_safe_tier')]
lack=[c for c in s['channels'] if not c.get('has_safe_tier')]
bad=[]
for c in s['channels']:
    assert len(c['sources'])<=4
    for u in c.get('safe_tier_urls',[]):
        src=[x for x in c['sources'] if x['url']==u]
        if not src or not (180<=(src[0].get('measured_height') or 0)<=720): bad.append((c['id'],u))
print('channels',len(s['channels']))
print('with_safe',len(safes))
print('without_safe',len(lack))  # 原目标 <10；现由 safeMode UI 过滤替代
print('live_safe with_safe',sum(1 for x in d['channels'] if x['safe']))
print('bad_safe_urls',len(bad))
PY
```

本机实测输出：

```
channels 266
with_safe 163
without_safe 103
live_safe with_safe 163
bad_safe_urls 0
```

复现探测（需要网络 + PyAV）：

```bash
python tools/live/remeasure_segments.py   # 现有源首分片复测
python tools/live/find_safe3.py           # best-fan 低清晰度列表
python tools/live/find_safe4.py           # imDazui 主列表
python tools/live/validate_safe.py        # 发布前终验（两次并集）
python tools/live/apply_safe.py           # 合并 seed + 生成 live_safe
```

---

## 7. 已知风险

1. **源时效性**：safe 源多为 IPTV `tsfile` 平台，可能按省份/运营商限流；已写入 `checked_at`，建议客户端按 `live_seed` 的 24h 刷新策略更新。
2. **主机限流**：`101.35.240.114` 曾因高并发探测整域失效；safe 候选中未使用该主机。
3. **清晰度抖动**：个别 CDN 的 `720p` 路径实测 1080p（如 `l.cztvcloud.com`），这类 URL 未被标记 safe；后续若 CDN 修正，可自动受益。
4. **身份风险**：safe 源均来自该频道名称匹配的列表；已剔除明显错配，但未做画面 OCR 核验。
5. **设备差异**：X4-50 的 ≤720 结论来自真机；其他 API≤23 设备可能能解 1080p，本策略偏保守但安全。

---

## 8. 交付文件

| 文件 | 变化 |
| --- | --- |
| `assets/live_seed.json` | 新增 safe 源与字段；396 条源（198 条 safe + 5 条 HD 备用 CDN）；266 频道；≤4 源/频道 |
| `assets/live_safe.json` | **新增**；266 频道对齐；163 条 URL，103 条 null+原因 |
| `docs/LIVE_SAFE.md` | 本文件 |
| `assets/live_hd.json` | **未改动** |
| `tools/live/*.py` | t16：`remeasure_segments.py`、`find_safe*.py`、`validate_safe.py`、`apply_safe.py`；t21：`probe_ttfb.py`、`apply_ttfb.py`、`finalize_ttfb.py` |
