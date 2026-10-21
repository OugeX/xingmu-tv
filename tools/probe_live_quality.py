#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 PyAV 实测每个直播源的真实分辨率/编码/帧率（不看 manifest 自报，直接读流头）。
输出 assets/live_quality.json + 控制台分层统计。
用法: python tools/probe_live_quality.py [--limit N] [--timeout 8]
"""
import json, sys, time
from concurrent.futures import ThreadPoolExecutor

import av

SEED = "assets/live_seed.json"
OUT = "assets/live_quality.json"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"


def probe(url, timeout=8):
    t0 = time.time()
    opts = {
        "user_agent": UA,
        "rw_timeout": str(timeout * 1_000_000),
        "timeout": str(timeout * 1_000_000),
        "stimeout": str(timeout * 1_000_000),
        "analyzeduration": "3000000",
        "probesize": "2000000",
    }
    try:
        with av.open(url, options=opts, timeout=timeout) as c:
            vs = [s for s in c.streams if s.type == "video"]
            if not vs:
                return {"ok": True, "video": False, "ms": int((time.time() - t0) * 1000)}
            s = vs[0]
            fps = None
            try:
                fps = float(s.average_rate) if s.average_rate else None
            except Exception:
                pass
            return {
                "ok": True, "video": True,
                "width": s.codec_context.width, "height": s.codec_context.height,
                "codec": s.codec_context.name, "fps": round(fps, 2) if fps else None,
                "bitrate": s.bit_rate,
                "ms": int((time.time() - t0) * 1000),
            }
    except Exception as e:
        return {"ok": False, "err": f"{type(e).__name__}: {str(e)[:80]}", "ms": int((time.time() - t0) * 1000)}


def tier(h):
    if not h:
        return "unknown"
    if h >= 2160:
        return "4K"
    if h >= 1440:
        return "2K"
    if h >= 1080:
        return "1080p"
    if h >= 720:
        return "720p"
    if h >= 576:
        return "576p"
    if h >= 480:
        return "480p"
    return "≤360p"


def main():
    limit = None
    timeout = 8
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])
    if "--timeout" in sys.argv:
        timeout = int(sys.argv[sys.argv.index("--timeout") + 1])

    seed = json.load(open(SEED, encoding="utf-8"))
    jobs = []
    for ch in seed["channels"]:
        for i, s in enumerate(ch.get("sources", [])):
            jobs.append((ch["name"], ch.get("group", ""), i, s["url"]))
    if limit:
        jobs = jobs[:limit]
    print(f"探测 {len(jobs)} 条源（并发 10，单条 {timeout}s）…")

    def run(j):
        name, grp, idx, url = j
        r = probe(url, timeout)
        r.update({"channel": name, "group": grp, "index": idx, "url": url})
        return r

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=10) as ex:
        res = list(ex.map(run, jobs))

    tiers = {}
    for r in res:
        t = tier(r.get("height")) if r.get("video") else ("error" if not r.get("ok") else "no-video")
        r["tier"] = t
        tiers[t] = tiers.get(t, 0) + 1

    json.dump({"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"), "results": res},
              open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"\n耗时 {int(time.time()-t0)}s，分层统计：")
    for k in ["4K", "2K", "1080p", "720p", "576p", "480p", "≤360p", "no-video", "unknown", "error"]:
        if tiers.get(k):
            print(f"  {k:<9}{tiers[k]:>4}")
    top = sorted([r for r in res if r.get("video")], key=lambda r: -(r.get("height") or 0))
    print("\n最高清晰度（前 15）：")
    for r in top[:15]:
        br = f"{(r.get('bitrate') or 0)/1e6:.1f}Mbps" if r.get("bitrate") else "?"
        print(f"  {r['channel'][:16]:<18}{r['tier']:<7}{r['width']}x{r['height']:<6}{str(r['codec']):<7}{str(r['fps']):<6}{br}")
    print(f"\n明细写入 {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
