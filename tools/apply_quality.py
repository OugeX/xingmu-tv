#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 tools/probe_live_quality.py 实测的分辨率合并进 assets/live_seed.json：
- 每个源写入 measured_height / measured_codec / measured_fps / tier
- 频道内源按清晰度排序（4K > 1080p > 720p > …），health/best_tier 一并落
- 生成 assets/live_hd.json：只含 ≥1080p 源的频道清单（高清专区/4K 专区数据）
用法: python tools/apply_quality.py
"""
import json, shutil, time

SEED = "assets/live_seed.json"
QUAL = "assets/live_quality.json"
HD_OUT = "assets/live_hd.json"
ORDER = {"4K": 0, "2K": 1, "1080p": 2, "720p": 3, "576p": 4, "480p": 5, "≤360p": 6, "unknown": 7, "error": 9}


def main():
    seed = json.load(open(SEED, encoding="utf-8"))
    q = json.load(open(QUAL, encoding="utf-8"))
    idx = {}
    for r in q["results"]:
        idx[(r["channel"], r["url"])] = r

    shutil.copy(SEED, SEED + ".bak")
    changed = 0
    for ch in seed["channels"]:
        for s in ch.get("sources", []):
            m = idx.get((ch["name"], s["url"]))
            if not m:
                continue
            if m.get("video"):
                s["measured_height"] = m["height"]
                s["measured_width"] = m["width"]
                s["measured_codec"] = m.get("codec")
                s["measured_fps"] = m.get("fps")
                s["tier"] = m["tier"]
                changed += 1
            else:
                s["tier"] = "error"
        srcs = ch.get("sources", [])
        srcs.sort(key=lambda s: ORDER.get(s.get("tier", "unknown"), 8))
        ch["sources"] = srcs
        ch["best_tier"] = srcs[0].get("tier") if srcs else "unknown"
        ch["best_height"] = srcs[0].get("measured_height") if srcs else None

    seed["quality_measured_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    json.dump(seed, open(SEED, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    hd = {"generated_at": seed["quality_measured_at"], "note": "只列实测 ≥1080p 的频道，供「高清专区/4K 专区」使用", "channels": []}
    for ch in seed["channels"]:
        good = [s for s in ch.get("sources", []) if s.get("measured_height", 0) >= 1080]
        if good:
            hd["channels"].append({
                "name": ch["name"], "group": ch.get("group"), "best_tier": good[0].get("tier"),
                "best_height": good[0].get("measured_height"), "sources": good,
            })
    json.dump(hd, open(HD_OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    tiers = {}
    for ch in seed["channels"]:
        tiers[ch["best_tier"]] = tiers.get(ch["best_tier"], 0) + 1
    print(f"合并 {changed} 条实测结果；频道最佳清晰度分布：{tiers}")
    print(f"高清专区频道数：{len(hd['channels'])}（写入 {HD_OUT}）")
    print("\n4K 频道：")
    for c in hd["channels"]:
        if c["best_tier"] == "4K":
            print(f"  {c['name']}  {c['best_height']}p  ({len(c['sources'])} 条源)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
