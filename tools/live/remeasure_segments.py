#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Re-measure seed sources by decoding the first media segment locally with PyAV.

Why: probing the stream URL makes PyAV pick one variant / follow CDN quirks and
produced false 1080p and false 'error'. Fetching the first segment ourselves and
decoding it locally is deterministic. For master playlists every variant is
measured so a <=720p variant can be surfaced as a safe source.
"""
import json, os, re, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

sys.path.insert(0, "tools/live")
import av
import verify as V

SEED = "assets/live_seed.json"
OUT = "D:/tmp/live/remeasure_segments.json"
SAFE_H = 720
UA = "Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"


def measure_file(data):
    fd, path = tempfile.mkstemp(suffix=".seg")
    os.write(fd, data)
    os.close(fd)
    try:
        with av.open(path) as c:
            vs = [s for s in c.streams if s.type == "video"]
            if not vs:
                return {"video": False}
            s = vs[0]
            fps = None
            try:
                fps = float(s.average_rate) if s.average_rate else None
            except Exception:  # noqa: BLE001
                pass
            return {"video": True, "width": s.codec_context.width, "height": s.codec_context.height,
                    "codec": s.codec_context.name, "fps": round(fps, 2) if fps else None}
    except Exception as e:  # noqa: BLE001
        return {"error": f"{type(e).__name__}: {str(e)[:70]}"}
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def fetch_seg(segurl, referer=None, ua=None):
    for rng in ("0-262143", None):
        rec, body = V.curl(segurl, referer=referer, ua=ua or UA, max_time=10, rng=rng)
        if len(body) > 1000 and V.is_media_head(body):
            return True, body, rec
    return False, b"", rec if 'rec' in dir() else {}


def measure_variant(vurl, referer=None, ua=None):
    vrec, vbody = V.curl(vurl, referer=referer, ua=ua or UA, max_time=8, max_filesize=524288)
    vpl = V.parse_playlist(vbody)
    if not vpl["extm3u"] or not vpl["segments"] or not vpl["first_seg"]:
        return None
    segurl = urljoin(vrec["effective"] or vurl, vpl["first_seg"])
    ok, data, srec = fetch_seg(segurl, referer, ua)
    if not ok:
        return None
    m = measure_file(data)
    if not m.get("video"):
        return None
    return {"variant_url": vurl, "seg_url": segurl, "seg_http": srec["http"], "seg_bytes": len(data),
            "width": m["width"], "height": m["height"], "codec": m["codec"], "fps": m["fps"]}


def measure_source(url, referer=None, ua=None):
    rec, body = V.curl(url, referer=referer, ua=ua or UA, max_time=8, max_filesize=524288)
    pl = V.parse_playlist(body)
    out = {"url": url, "http": rec["http"], "ct": rec["ct"], "playlist_type": None,
           "variants": [], "measured_height": None, "safe_url": None, "safe_height": None,
           "reason": None}
    if not pl["extm3u"]:
        out["reason"] = f"playlist http={rec['http']} ct={rec['ct']}"
        return out
    if pl["master"]:
        out["playlist_type"] = "master"
        seen = set()
        for v in pl["variants"]:
            vurl = urljoin(rec["effective"] or url, v["url"])
            if vurl in seen:
                continue
            seen.add(vurl)
            m = measure_variant(vurl, referer, ua)
            if m:
                m["resolution"] = v.get("resolution")
                m["bandwidth"] = v.get("bandwidth")
                out["variants"].append(m)
        if out["variants"]:
            best = max(out["variants"], key=lambda v: v["height"])
            out["measured_height"] = best["height"]
            safe = sorted([v for v in out["variants"] if v["height"] <= SAFE_H], key=lambda v: -v["height"])
            if safe:
                out["safe_url"] = safe[0]["variant_url"]
                out["safe_height"] = safe[0]["height"]
                out["safe_width"] = safe[0]["width"]
                out["safe_codec"] = safe[0]["codec"]
                out["safe_fps"] = safe[0]["fps"]
        else:
            out["reason"] = "master: variants not decodable"
        return out
    out["playlist_type"] = "media"
    if not pl["segments"] or not pl["first_seg"]:
        out["reason"] = "media playlist without segments"
        return out
    segurl = urljoin(rec["effective"] or url, pl["first_seg"])
    ok, data, srec = fetch_seg(segurl, referer, ua)
    if not ok:
        out["reason"] = f"segment fetch failed http={srec.get('http')}"
        return out
    m = measure_file(data)
    if not m.get("video"):
        out["reason"] = f"segment not decodable {m.get('error','')}"
        return out
    out.update({"measured_height": m["height"], "measured_width": m["width"],
                "measured_codec": m["codec"], "measured_fps": m["fps"],
                "seg_url": segurl, "seg_http": srec["http"], "seg_bytes": len(data)})
    if m["height"] <= SAFE_H:
        out["safe_url"] = url
        out["safe_height"] = m["height"]
        out["safe_width"] = m["width"]
        out["safe_codec"] = m["codec"]
        out["safe_fps"] = m["fps"]
    return out


def main():
    seed = json.load(open(SEED, encoding="utf-8"))
    jobs = []
    for ch in seed["channels"]:
        for s in ch["sources"]:
            jobs.append((ch["id"], ch["name"], s))
    print(f"re-measuring {len(jobs)} sources by first-segment decode", flush=True)
    res, done, t0 = [], 0, time.time()

    def run(j):
        cid, name, s = j
        r = measure_source(s["url"])
        r.update({"channel": cid, "channel_name": name, "source_url": s["url"]})
        return r

    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = [ex.submit(run, j) for j in jobs]
        for f in futs:
            try:
                res.append(f.result())
            except Exception as e:  # noqa: BLE001
                res.append({"source_url": "?", "error": repr(e)})
            done += 1
            if done % 40 == 0:
                print(f"  {done}/{len(jobs)} ({time.time()-t0:.0f}s)", flush=True)
    json.dump({"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "results": res},
              open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    byid = {}
    for r in res:
        byid.setdefault(r.get("channel"), []).append(r)
    safe_ch = sum(1 for cid, rs in byid.items() if any(r.get("safe_url") for r in rs))
    print(f"done {time.time()-t0:.0f}s; channels with safe variant/source: {safe_ch}/{len(byid)}")
    heights = {}
    for r in res:
        h = r.get("measured_height")
        if h:
            heights[h] = heights.get(h, 0) + 1
    print("measured heights:", dict(sorted(heights.items())))
    print("errors:", sum(1 for r in res if not r.get("measured_height") and not r.get("variants")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
