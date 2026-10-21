# tools/live — 直播源探测与种子生成工具（t2 原始证据）

本目录保留 t2 采集/验证全部脚本与原始结果，便于复现、复核与下一次刷新。
运行环境：Windows + Python 3.14 + curl 8.22；网络出口为**非中国大陆**（见 `docs/PLAN_LIVE.md` §3.1）。
原始工作目录为临时目录，脚本内 `BASE = os.path.dirname(__file__)` 假设数据文件与脚本同级。

## 文件说明

| 文件 | 作用 |
| --- | --- |
| `build_candidates.py` | 从 iptv-org API（`channels.json`/`streams.json`）与 `zho.m3u` 生成候选 `candidates.json` |
| `build_myiptv.py` | 解析 `myiptv.m3u` → `candidates_myiptv.json` |
| `build_extra.py` | 解析 vbskycn / wwb521 / myIPTV-ipv6 / fanmingming-ipv6 / iptv-org streams → `candidates_extra.json` |
| `probe.py` | 第一版两阶段探测（40 并发）；保留作对照，**生产请用 `verify.py`** |
| `verify.py` | 两阶段验证核心：阶段 A 播放列表、阶段 B 首分片；被其他脚本 import |
| `final_verify.py` | 低并发（10）+ 重试终验，输入"曾返回 m3u8"的 URL 并集 → `final_verify.json` |
| `retest_sat.py` | 对从未返回 m3u8 的 349 条卫视 URL 补测 → `retest_sat.json` |
| `stability_pass.py` | 对种子全部源做两轮独立探测 → `stability.json`（`passes=0/1/2`） |
| `classify.py` | 频道归一化/分组/排序中间产物（`channels_merged.json`） |
| `build_seed.py` | 读取 `final_verify.json` + `stability.json`，清洗 URL、归一名、分组、排序、裁剪为每频道 1 主 + 最多 3 备 → `live_seed.json` |
| `spotcheck.py` | 从种子随机抽 16 条做独立复测 → `spotcheck.json` |
| `final_verify.json` | **原始证据**：538 条候选的终验结果（含每条的 http/content-type/ttfb/variant/segment 校验） |
| `stability.json` | **原始证据**：340 条待发布源 × 2 轮独立探测的通过情况 |
| `spotcheck.json` | **原始证据**：16 条随机抽样复测明细 |

## 推荐复现顺序

```bash
# 0) 准备数据（本目录未内置上游 m3u/json，需按 docs/PLAN_LIVE.md §2 下载）
#    channels.json / streams.json / zho.m3u / myiptv.m3u / vbskycn4.m3u /
#    wwb521.m3u / myiptv6.m3u / fm6.m3u / iptvorg_cn_streams.m3u

python build_candidates.py
python build_myiptv.py
python build_extra.py

# 1) 终验（低并发 + 重试；需先把 candidates_all.json / candidates_extra4.json 合成）
python - <<'PY'
import json
a=json.load(open('candidates_all.json')); b=json.load(open('candidates_extra4.json'))
seen=set(); out=[]
for c in a+b:
    if c['url'] not in seen: seen.add(c['url']); out.append(c)
json.dump(out, open('candidates_all_merged.json','w'), ensure_ascii=False)
PY
python final_verify.py            # -> final_verify.json

# 2) 双轮稳定性（依赖 seed；先由 build_seed 生成一版）
python build_seed.py              # -> live_seed.json
python stability_pass.py          # -> stability.json
python build_seed.py              # 应用 stability 后再生成最终 seed

# 3) 抽样复测
python spotcheck.py               # -> spotcheck.json
```

## 关键约束（脚本已固化，改脚本勿破坏）

- 阶段 A：`curl -L --max-time 8 --max-filesize 524288`，要求 body 以 `#EXTM3U` 开头。
- 阶段 B：**不要用 `--max-filesize`**（≥上限时 curl 会丢弃整个响应并返回 63），用 `-r 0-262143` + `--max-time 8`；要求 200/206、字节 >0、TS/fMP4 魔数。
- 并发：终验 10、稳定性 10、抽样 8；生产健康检查建议 ≤4。
- 每频道最多保留 1 主 + 3 备（共 4 条），`fallback_only` 不计入 `urls[]`（degraded 频道除外）。
- 输出种子字段与 `docs/PLAN_LIVE.md` §5 以及 `docs/PLAN_SOURCES.md` §9.6 对齐。

## 已知限制

- 出口非大陆，部分大陆 ISP 源在本目录结果中为超时；不能据此判定源永久失效。
- 身份以来源标注为准，未做画面级核验；明显错配已在 `build_seed.py` 的 `MISLABELED` 列表中剔除。
- `probe.py` 是修复前的对照版本，结果 `probe_results.json` 未随目录保留（如需可比对请按上述流程重跑）。

## t16 追加：≤720p 兜底源流水线

| 文件 | 说明 |
| --- | --- |
| `remeasure_segments.py` | 对现有源取首个媒体分片、PyAV 本地解码，修正 `av.open(url)` 选最高 variant 的误判；master 逐 variant 测量 |
| `find_safe.py` / `find_safe2.py` | 低码率 URL 重写（1080p→720p/576p、hd→sd、bestv 码率位）与原候选池探测 |
| `find_safe3.py` | best-fan/iptv-sources 每日 `[576]/[720]` 低清晰度列表 |
| `find_safe4.py` / `find_safe5.py` | imDazui/Tvlist 聚合列表（4 个大文件 / 全部 224 个文件） |
| `validate_safe.py` | 发布前终验：所有候选 safe URL 重新取首分片测量两次，取并集 |
| `apply_safe.py` | 合并 safe 源进 `assets/live_seed.json`，生成 `assets/live_safe.json`，保证 ≤4 源/频道且 safe 在列 |

判定：播放列表 `#EXTM3U` + 首分片 200/206 且可读 + **PyAV 实测 180 ≤ height ≤ 720**；不采用 manifest 自报。
