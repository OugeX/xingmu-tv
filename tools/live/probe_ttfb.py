#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Measure first-segment TTFB (median of 3) and decoded height for an HLS URL.

Usage:
  python tools/live/probe_ttfb.py --url URL [--want 720] [--times 3]
  python tools/live/probe_ttfb.py --file urls.txt [--json out.json]

Judgement used by t21:
  - playlist starts with #EXTM3U
  - first media segment readable (HTTP 200/206, non-empty, TS/fMP4 magic)
  - PyAV decoded height (<=720 for safe-tier, ==1080 for HD)
  - TTFB median < 1200 ms => cdn_class "fast", else "slow"
"""
import argparse, json, statistics, sys, time
from urllib.parse import urljoin

sys.path.insert(0, "tools/live")
import verify as V
import remeasure_segments as R

UA = "Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
FAST_MS = 1200


def _playlist(url, referer=None, ua=None):
    rec, body = V.curl(url, referer=referer, ua=ua or UA, max_time=8, max_filesize=524288)
    return rec, V.parse_playlist(body)


def _segment_for(url, want=None, referer=None, ua=None):
    """Return (segment_url, playlist_type, variant_resolution, meta) or None."""
    rec, pl = _playlist(url, referer, ua)
    if not pl["extm3u"]:
        return None, {"reason": f"playlist http={rec['http']} ct={rec['ct']}"}, None
    if pl["master"]:
        variants = sorted(pl["variants"], key=lambda v: (
            int(v["resolution"].split("x")[1]) if v.get("resolution") and "x" in v["resolution"] else 9999,
            v.get("bandwidth") or 0))
        for v in variants:
            vurl = urljoin(rec["effective"] or url, v["url"])
            vrec, vpl = _playlist(vurl, referer, ua)
            if vpl["extm3u"] and vpl["segments"] and vpl["first_seg"]:
                h = None
                if v.get("resolution") and "x" in v["resolution"]:
                    try:
                        h = int(v["resolution"].split("x")[1])
                    except ValueError:
                        pass
                if want and h and h > want and len(variants) > 1:
                    continue
                return urljoin(vrec["effective"] or vurl, vpl["first_seg"]), {
                    "playlist_type": "master", "variant_url": vurl,
                    "variant_resolution": v.get("resolution")}, vrec
        return None, {"reason": "master: no usable variant"}, None
    if not pl["segments"] or not pl["first_seg"]:
        return None, {"reason": "media playlist without segments"}, None
    return urljoin(rec["effective"] or url, pl["first_seg"]), {
        "playlist_type": "media"}, rec


def _ttfb_once(segurl, referer=None, ua=None):
    cmd = ["curl", "-sS", "-L", "--max-time", "8", "--connect-timeout", "5",
           "-A", ua or UA, "-r", "0-1023", "-o", V.os.devnull,
           "-w", "%{time_starttransfer}|%{http_code}"]
    if referer:
        cmd += ["-e", referer]
    cmd.append(segurl)
    import subprocess
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=16, errors="replace")
    try:
        t, code = p.stdout.strip().split("|")
        return round(float(t) * 1000), int(code)
    except ValueError:
        return None, 0


def _measure_height_any(url, want=720, referer=None, ua=None):
    """Try up to 3 segments (and master variants) until a decodable video height is found."""
    rec, pl = _playlist(url, referer, ua)
    if not pl["extm3u"]:
        return None
    targets = []
    if pl["master"]:
        for v in pl["variants"][:3]:
            vurl = urljoin(rec["effective"] or url, v["url"])
            vrec, vpl = _playlist(vurl, referer, ua)
            if vpl["extm3u"] and vpl["first_seg"]:
                targets.append((vurl, vpl))
    else:
        targets.append((url, pl))
    for base, p in targets:
        segs = [l.strip() for l in p.get("segments_urls", [])]
        if not segs:
            segs = []
            rec2, body2 = V.curl(base, referer=referer, ua=ua or UA, max_time=8, max_filesize=524288)
            p2 = V.parse_playlist(body2)
            # parse_playlist only exposes first_seg; read the raw playlist lines ourselves
            lines = [l.strip() for l in body2.decode("utf-8", "replace").splitlines()]
            pending = None
            for ln in lines:
                if ln.startswith("#EXTINF"):
                    pending = True
                elif ln and not ln.startswith("#") and pending:
                    segs.append(urljoin(rec2["effective"] or base, ln))
                    pending = None
        for seg in segs[:3]:
            ok, data, srec = R.fetch_seg(seg, referer, ua)
            if not ok:
                continue
            m = R.measure_file(data)
            if m.get("video") and (m.get("height") or 0) > 0:
                return m
    return None


def probe(url, want=720, times=3, referer=None, ua=None):
    seg, meta, rec = _segment_for(url, want, referer, ua)
    out = {"url": url, "want": want, "playlist_ok": seg is not None,
           "segment_url": seg, "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    out.update(meta)
    if not seg:
        out.update({"ok": False, "cdn_class": "slow", "ttfb_ms": None})
        return out
    samples = []
    for _ in range(times):
        ms, code = _ttfb_once(seg, referer, ua)
        samples.append({"ttfb_ms": ms, "http": code})
        time.sleep(0.15)
    vals = [s["ttfb_ms"] for s in samples if s["ttfb_ms"] is not None and s["http"] in (200, 206)]
    median = int(statistics.median(vals)) if vals else None
    # height from first segment (local decode)
    ok_seg, data, srec = R.fetch_seg(seg, referer, ua)
    height = width = codec = fps = None
    if ok_seg:
        m = R.measure_file(data)
        if m.get("video") and (m.get("height") or 0) > 0:
            height, width, codec, fps = m.get("height"), m.get("width"), m.get("codec"), m.get("fps")
    if not height:
        m2 = _measure_height_any(url, want, referer, ua)
        if m2:
            height, width, codec, fps = m2.get("height"), m2.get("width"), m2.get("codec"), m2.get("fps")
    out.update({"samples": samples, "ttfb_ms": median, "segment_ok": ok_seg,
                "measured_height": height, "measured_width": width,
                "measured_codec": codec, "measured_fps": fps,
                "ok": bool(median is not None and height),
                "cdn_class": "fast" if (median is not None and median < FAST_MS) else "slow"})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url")
    ap.add_argument("--file")
    ap.add_argument("--want", type=int, default=720)
    ap.add_argument("--times", type=int, default=3)
    ap.add_argument("--json")
    ap.add_argument("--concurrency", type=int, default=8)
    args = ap.parse_args()
    if args.url:
        print(json.dumps(probe(args.url, args.want, args.times), ensure_ascii=False, indent=1))
        return 0
    if not args.file:
        ap.error("--url or --file required")
    urls = [l.strip() for l in open(args.file, encoding="utf-8") if l.strip() and not l.startswith("#")]
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        res = list(ex.map(lambda u: probe(u, args.want, args.times), urls))
    out = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "results": res}
    if args.json:
        json.dump(out, open(args.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for r in sorted(res, key=lambda r: (r.get("ttfb_ms") is None, r.get("ttfb_ms") or 99999)):
        print(f"{r.get('cdn_class','?'):4s} {str(r.get('ttfb_ms')):>6s}ms h={r.get('measured_height')} {r['url'][:100]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
