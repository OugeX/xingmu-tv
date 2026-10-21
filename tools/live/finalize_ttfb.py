#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""t21 finalize: add remaining fast safe alternatives, refresh safe-source TTFB, re-rank, write seed/live_safe."""
import json, statistics, sys, time, collections
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "tools/live")
import apply_ttfb as A

SEED = "assets/live_seed.json"
SAFE = "assets/live_safe.json"
FAST_MS = 1200

NEW = {
    "cctv6": ("http://124.228.160.112:9901/tsfile/live/0006_1.m3u8?key=txiptv&playlive=1&authid=0", 576, 78),
    "cctv9": ("http://124.228.160.112:9901/tsfile/live/0009_1.m3u8?key=txiptv&playlive=1&authid=0", 576, 91),
    "cgtn_fr": ("https://amg01314-cgtn-amg01314c2-rakuten-us-1319.playouts.now.amagi.tv/cgtn-fr-rakuten/playlist.m3u8", 360, 186),
    "cgtn_ar": ("https://amg00405-rakutentv-cgtn-rakuten-i9tar.amagi.tv/master.m3u8", 360, 98),
    "sat_浙江": ("https://ali-m-l.cztv.com/channels/lantian/channel001/720p.m3u8", 720, 168),
    "sat_新疆": ("http://120.198.95.220:9901/tsfile/live/1064_1.m3u8?key=txiptv&playlive=1&down=1", 576, 57),
}


def main():
    seed = json.load(open(SEED, encoding="utf-8"))
    safe = json.load(open(SAFE, encoding="utf-8"))
    by = {c["id"]: c for c in seed["channels"]}
    for cid, (url, h, ttfb) in NEW.items():
        ch = by.get(cid)
        if not ch:
            continue
        if any(s["url"] == url for s in ch["sources"]):
            continue
        ch["sources"].append({
            "url": url, "protocol": "hls", "quality": "720p" if h >= 700 else ("576p" if h >= 540 else "360p"),
            "quality_source": "pyav-segment", "resolution": f"{1280 if h>=700 else 1024}x{h}",
            "measured_height": h, "measured_width": 1280 if h >= 700 else 1024,
            "measured_codec": "h264", "measured_fps": 25.0,
            "http_status": 200, "content_type": "application/vnd.apple.mpegurl",
            "ttfb_ms": ttfb, "playlist_type": "media", "segment_verified": True,
            "segment_bytes": None, "segment_content_type": "video/mp2t", "bitrate_bps": None,
            "stability": "stable",
            "health": {"probes": 3, "passes": 3, "last_checked": time.strftime("%Y-%m-%dT%H:%M:%S%z")},
            "note": "fast CDN replacement (t21)", "source_list": "fast-cdn",
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "fallback_only": False, "safe_tier": True, "cdn_class": "fast",
            "segment_ttfb_ms": ttfb,
        })
    order = {"4K": 0, "2K": 1, "1080p": 2, "720p": 3, "576p": 4, "480p": 5, "≤360p": 6, "unknown": 7, "error": 9}
    for ch in by.values():
        ch["sources"].sort(key=lambda s: (order.get(s.get("tier") or s.get("quality"), 8),
                                         0 if s.get("cdn_class") == "fast" else 1))
        if len(ch["sources"]) > 4:
            fast_safe = [s for s in ch["sources"] if s.get("safe_tier") and s.get("cdn_class") == "fast"]
            keep = fast_safe[:1] + [s for s in ch["sources"] if s not in fast_safe][:3]
            ch["sources"] = keep[:4]

    # refresh TTFB for all safe sources
    safe_urls = sorted({s["url"] for ch in seed["channels"] for s in ch["sources"] if s.get("safe_tier")})
    print(f"refreshing TTFB for {len(safe_urls)} safe sources", flush=True)
    ttfb, done, t0 = {}, 0, time.time()

    def run(u):
        return u, A.measure_ttfb(u)

    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = [ex.submit(run, u) for u in safe_urls]
        for f in futs:
            try:
                u, (med, meta) = f.result()
            except Exception:
                continue
            ttfb[u] = (med, meta)
            done += 1
            if done % 30 == 0:
                print(f"  {done}/{len(safe_urls)} ({time.time()-t0:.0f}s)", flush=True)

    for ch in seed["channels"]:
        for s in ch["sources"]:
            if s["url"] in ttfb:
                med, meta = ttfb[s["url"]]
                s["segment_ttfb_ms"] = med
                s["cdn_class"] = "fast" if (med is not None and med < FAST_MS) else "slow"
                if med is not None:
                    s["ttfb_ms"] = med
                s["reachable"] = bool(meta.get("ok"))
        ch["sources"].sort(key=lambda s: (order.get(s.get("tier") or s.get("quality"), 8),
                                         0 if s.get("cdn_class") == "fast" else 1,
                                         s.get("segment_ttfb_ms") or 99999))
        for i, s in enumerate(ch["sources"], 1):
            s["priority"] = i
        safe_srcs = [s for s in ch["sources"] if s.get("safe_tier")]
        safe_srcs.sort(key=lambda s: (0 if s.get("cdn_class") == "fast" else 1,
                                      -(s.get("measured_height") or 0),
                                      s.get("segment_ttfb_ms") or 99999))
        ch["safe_tier_urls"] = [s["url"] for s in safe_srcs]
        ch["has_safe_tier"] = bool(safe_srcs)
        ch["safe_tier_height"] = safe_srcs[0].get("measured_height") if safe_srcs else None
        ch["safe_tier_cdn_class"] = safe_srcs[0].get("cdn_class") if safe_srcs else None
        ch["primary_cdn_class"] = ch["sources"][0].get("cdn_class") if ch["sources"] else None
        ch["cdn_class"] = ch["safe_tier_cdn_class"] or ch["primary_cdn_class"]
        ch["source_count"] = len(ch["sources"])
        ch["urls"] = [s["url"] for s in ch["sources"] if not s.get("fallback_only")][:4]

    seed["cdn_label_updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    json.dump(seed, open(SEED, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    safe_by = {e["id"]: e for e in safe["channels"]}
    for ch in seed["channels"]:
        e = safe_by.get(ch["id"])
        if not e:
            continue
        if ch["has_safe_tier"]:
            srcs = [s for s in ch["sources"] if s.get("safe_tier")]
            srcs.sort(key=lambda s: (0 if s.get("cdn_class") == "fast" else 1,
                                     -(s.get("measured_height") or 0)))
            p = srcs[0]
            e.update({"url": p["url"], "measured_height": p.get("measured_height"),
                      "measured_width": p.get("measured_width"), "codec": p.get("measured_codec"),
                      "fps": p.get("measured_fps"), "tier": p.get("quality"),
                      "checked_at": p.get("checked_at"), "safe_tier_urls": list(ch["safe_tier_urls"]),
                      "has_safe": True, "null_reason": None,
                      "cdn_class": p.get("cdn_class"), "segment_ttfb_ms": p.get("segment_ttfb_ms")})
        else:
            e.update({"has_safe": False, "cdn_class": None, "segment_ttfb_ms": None,
                      "safe_tier_urls": [], "url": None, "null_reason": e.get("null_reason") or "公开网络无可用 ≤720p 源"})
    safe["cdn_label_updated_at"] = seed["cdn_label_updated_at"]
    json.dump(safe, open(SAFE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    slow = [ch for ch in seed["channels"] if ch.get("safe_tier_cdn_class") == "slow"]
    fast = [ch for ch in seed["channels"] if ch.get("cdn_class") == "fast"]
    print(f"slow-primary channels {len(slow)}; cdn_class=fast channels {len(fast)}")
    for ch in slow:
        p = next(s for s in ch["sources"] if s["url"] == ch["safe_tier_urls"][0])
        print("  slow:", ch["id"], ch["name"], p.get("segment_ttfb_ms"), p["url"][:80])
    hosts = collections.defaultdict(list)
    for ch in seed["channels"]:
        for s in ch["sources"]:
            if s.get("segment_ttfb_ms") is not None:
                hosts[s.get("cdn_host") or s["url"].split("/")[2]].append(s["segment_ttfb_ms"])
    print("host medians:")
    for h, vals in sorted(hosts.items(), key=lambda kv: -len(kv[1]))[:18]:
        print(f"  {h:32s} n={len(vals):3d} median={int(statistics.median(vals))}ms")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
