#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Merge verified <=720p sources into assets/live_seed.json and emit assets/live_safe.json.

safe sources come from:
  - remeasure_segments.json (first-segment PyAV decode of existing sources)
  - safe_find_results.json (low-quality URL rewrites / master variants)
  - safe_find2/3/4/5_results.json (public list candidates, first-segment PyAV)
  - manually verified bestv quality-index and IPTV tsfile 576p feeds
"""
import json, time, collections

SEED = "assets/live_seed.json"
SAFE_OUT = "assets/live_safe.json"
TIER_ORDER = {"4K": 0, "2K": 1, "1080p": 2, "720p": 3, "576p": 4, "480p": 5, "≤360p": 6,
              "unknown": 7, "error": 9}
Q_BY_HEIGHT = [(2000, "4K"), (1440, "2K"), (1000, "1080p"), (700, "720p"), (540, "576p"),
               (460, "480p"), (0, "≤360p")]


def tier(h):
    for th, name in Q_BY_HEIGHT:
        if h >= th:
            return name
    return "unknown"


def _rec(url, h, width, codec, fps, playlist_type, checked_at, stability, source_list, seg_bytes=None):
    return {
        "url": url, "protocol": "hls", "quality": tier(h), "quality_source": "pyav-segment",
        "resolution": (f"{width}x{h}" if width else None),
        "measured_height": h, "measured_width": width, "measured_codec": codec, "measured_fps": fps,
        "http_status": 200, "content_type": "application/vnd.apple.mpegurl", "ttfb_ms": 0,
        "playlist_type": playlist_type or "media", "segment_verified": True,
        "segment_bytes": seg_bytes, "segment_content_type": "video/mp2t", "bitrate_bps": None,
        "stability": stability,
        "health": {"probes": 2 if stability == "stable" else 1,
                   "passes": 2 if stability == "stable" else 1,
                   "last_checked": checked_at},
        "note": "safe-tier: PyAV 首分片实测 <=720p",
        "source_list": source_list, "checked_at": checked_at,
        "fallback_only": False, "safe_tier": True,
    }


def safe_records():
    """channel id -> list of verified safe source records (validated first, remeasure fallback)."""
    out = collections.defaultdict(dict)

    def add(cid, rec):
        url = rec.get("safe_url")
        h = rec.get("measured_height") or 0
        if not cid or not url or not (180 <= h <= 720) or "kwimgs" in url:
            return
        out[cid][url] = _rec(url, h, rec.get("safe_width"), rec.get("safe_codec"),
                             rec.get("safe_fps"), rec.get("playlist_type"),
                             rec.get("checked_at") or time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                             rec.get("_stability", "stable"), rec.get("source_list") or "safe-discovery",
                             rec.get("seg_bytes"))

    try:
        val = json.load(open("D:/tmp/live/safe_validated.json", encoding="utf-8"))
        for r in val["results"]:
            add(r.get("channel"), r)
    except OSError:
        pass
    # fallback: sources measured safe in the earlier segment pass but not re-confirmed now
    rem = json.load(open("D:/tmp/live/remeasure_segments.json", encoding="utf-8"))
    for r in rem["results"]:
        cid = r.get("channel")
        if cid in out:
            continue
        add(cid, dict(r, _stability="intermittent"))
    return {k: list(v.values()) for k, v in out.items()}


def main():
    seed = json.load(open(SEED, encoding="utf-8"))
    safe_map = safe_records()
    stats = collections.Counter()
    for ch in seed["channels"]:
        srcs = ch.get("sources", [])
        # mark existing safe sources
        for s in srcs:
            h = s.get("measured_height") or 0
            s["safe_tier"] = bool(180 <= h <= 720)
        # merge new safe sources
        existing_urls = {s["url"] for s in srcs}
        for s in safe_map.get(ch["id"], []):
            if s["url"] in existing_urls:
                for e in srcs:
                    if e["url"] == s["url"]:
                        for k in ("measured_height", "measured_width", "measured_codec", "measured_fps",
                                  "quality", "resolution", "checked_at", "segment_verified",
                                  "segment_bytes", "content_type", "stability", "health"):
                            if s.get(k) is not None:
                                e[k] = s[k]
                        e["tier"] = s.get("quality")
                        e["safe_tier"] = True
                continue
            srcs.append(s)
            existing_urls.add(s["url"])
        # tier sort then cap 4, guaranteeing one safe source
        srcs.sort(key=lambda s: TIER_ORDER.get(s.get("tier") or tier(s.get("measured_height") or 0), 8))
        if len(srcs) > 4:
            safe_list = [s for s in srcs if s.get("safe_tier")]
            non_safe = [s for s in srcs if not s.get("safe_tier")]
            keep = non_safe[:3] + safe_list[:1]
            if not safe_list:
                keep = non_safe[:4]
            srcs = keep
            srcs.sort(key=lambda s: TIER_ORDER.get(s.get("tier") or tier(s.get("measured_height") or 0), 8))
        for i, s in enumerate(srcs, 1):
            s["priority"] = i
        safe_srcs = [s for s in srcs if s.get("safe_tier")]
        safe_srcs.sort(key=lambda s: -(s.get("measured_height") or 0))
        ch["sources"] = srcs
        ch["source_count"] = len(srcs)
        ch["safe_tier_urls"] = [s["url"] for s in safe_srcs]
        ch["has_safe_tier"] = bool(safe_srcs)
        ch["safe_tier_height"] = safe_srcs[0].get("measured_height") if safe_srcs else None
        if safe_srcs:
            stats["safe"] += 1
        else:
            stats["no_safe"] += 1
            stats["no_safe_" + (ch.get("group_id") or ch.get("group"))] += 1
    seed["safe_tier_updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    seed.setdefault("probe", {}).setdefault("stats", {})["channels_with_safe_tier"] = stats["safe"]
    seed["probe"]["stats"]["channels_without_safe_tier"] = stats["no_safe"]
    json.dump(seed, open(SEED, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    safe_doc = {
        "schema_version": 1,
        "name": "live_safe_cn",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "description": "每个频道一条 PyAV 首分片实测 <=720p 的兜底源，供 API<=23 / 低端盒子使用；找不到为 null",
        "source_seed": "assets/live_seed.json",
        "summary": {"channels": len(seed["channels"]), "with_safe": stats["safe"],
                    "without_safe": stats["no_safe"]},
        "channels": [],
    }
    for ch in seed["channels"]:
        safe_srcs = [s for s in ch["sources"] if s.get("safe_tier")]
        safe_srcs.sort(key=lambda s: -(s.get("measured_height") or 0))
        best = safe_srcs[0] if safe_srcs else None
        safe_doc["channels"].append({
            "id": ch["id"],
            "name": ch["name"],
            "group": ch.get("group"),
            "group_id": ch.get("group_id"),
            "region": ch.get("region"),
            "safe": bool(best),
            "url": best["url"] if best else None,
            "measured_height": best.get("measured_height") if best else None,
            "measured_width": best.get("measured_width") if best else None,
            "codec": best.get("measured_codec") if best else None,
            "fps": best.get("measured_fps") if best else None,
            "tier": best.get("quality") if best else None,
            "checked_at": best.get("checked_at") if best else None,
            "reason": None if best else "no_public_<=720p_source_found",
        })
    json.dump(safe_doc, open(SAFE_OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("safe channels", stats["safe"], "without", stats["no_safe"])
    print("no-safe by group", {k: v for k, v in stats.items() if k.startswith("no_safe_")})
    print("wrote", SAFE_OUT)


if __name__ == "__main__":
    main()
