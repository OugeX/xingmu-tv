#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Find verified <=720p sources for seed channels that lack one.

For every channel without a measured height <=720 source:
  1) try low-quality URL rewrites of existing sources (1080p->720p, hd->sd, ...)
  2) try <=720 variants of existing HLS master playlists
  3) try public candidate URLs matched to the channel name (exact first, then substring)

Each candidate must pass: playlist #EXTM3U, first segment readable, and PyAV measured
height <= 720. Results are written to D:/tmp/live/safe_find_results.json.
"""
import json, os, re, sys, time, collections
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

sys.path.insert(0, "tools/live")
import av
import verify as V

SEED = "assets/live_seed.json"
OUT = "D:/tmp/live/safe_find_results.json"
UA = "Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
SAFE_H = 720


def measure(url, timeout=8):
    opts = {
        "user_agent": UA,
        "rw_timeout": str(timeout * 1_000_000),
        "timeout": str(timeout * 1_000_000),
        "stimeout": str(timeout * 1_000_000),
        "analyzeduration": "3000000",
        "probesize": "2000000",
    }
    t0 = time.time()
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
            return {"ok": True, "video": True, "width": s.codec_context.width,
                    "height": s.codec_context.height, "codec": s.codec_context.name,
                    "fps": round(fps, 2) if fps else None,
                    "ms": int((time.time() - t0) * 1000)}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "err": f"{type(e).__name__}: {str(e)[:70]}", "ms": int((time.time() - t0) * 1000)}


def get_playlist(url, referer=None, ua=None):
    rec, body = V.curl(url, referer=referer, ua=ua or UA, max_time=8, max_filesize=524288)
    pl = V.parse_playlist(body)
    return rec, pl


def seg_ok(seg_url, referer=None, ua=None):
    rec, body = V.curl(seg_url, referer=referer, ua=ua or UA, max_time=8, rng="0-262143")
    ok = rec["http"] in (200, 206) and len(body) > 0 and V.is_media_head(body)
    return ok, {"http": rec["http"], "bytes": len(body), "ct": rec["ct"]}


def h_of(res):
    if res and "x" in res:
        try:
            return int(res.split("x")[1])
        except ValueError:
            return None
    return None


def check(url, referer=None, ua=None):
    """Return dict with safe=True and evidence, or safe=False with reason."""
    rec, pl = get_playlist(url, referer, ua)
    if not pl["extm3u"]:
        return {"safe": False, "reason": f"playlist http={rec['http']} ct={rec['ct']}"}
    if pl["master"]:
        variants = sorted(pl["variants"], key=lambda v: (h_of(v.get("resolution")) or 9999,
                                                        v.get("bandwidth") or 0))
        tried = 0
        for v in variants:
            vh = h_of(v.get("resolution"))
            if vh and vh > SAFE_H:
                continue
            vurl = urljoin(rec["effective"] or url, v["url"])
            vrec, vpl = get_playlist(vurl, referer, ua)
            if not vpl["extm3u"] or not vpl["segments"] or not vpl["first_seg"]:
                continue
            segu = urljoin(vrec["effective"] or vurl, vpl["first_seg"])
            sok, sinfo = seg_ok(segu, referer, ua)
            if not sok:
                continue
            m = measure(vurl)
            tried += 1
            if m.get("video") and (m.get("height") or 9999) <= SAFE_H:
                return {"safe": True, "url": vurl, "playlist_type": "master-variant",
                        "variant_resolution": v.get("resolution"), "variant_bandwidth": v.get("bandwidth"),
                        "seg": sinfo, "measure": m, "source_playlist": url}
            if tried >= 4:
                break
        return {"safe": False, "reason": "master: no <=720 variant verified"}
    # media playlist
    if not pl["segments"] or not pl["first_seg"]:
        return {"safe": False, "reason": "media playlist without segments"}
    segu = urljoin(rec["effective"] or url, pl["first_seg"])
    sok, sinfo = seg_ok(segu, referer, ua)
    if not sok:
        return {"safe": False, "reason": f"segment failed {sinfo}"}
    m = measure(url)
    if m.get("video") and (m.get("height") or 9999) <= SAFE_H:
        return {"safe": True, "url": url, "playlist_type": "media", "seg": sinfo,
                "measure": m, "source_playlist": url}
    return {"safe": False, "reason": f"measured height={m.get('height')} {m.get('err','')}"}


GENERIC = {"新闻综合", "综合", "公共", "影视", "生活", "经济", "科教", "文旅", "少儿",
           "体育", "纪实", "国际", "文化", "资讯", "导视", "一套", "二套", "新闻", "都市"}


def norm(s):
    s = (s or "").strip()
    s = re.split(r"\$LR", s)[0]
    s = re.sub(r'tvg-id=.*?tvg-name="([^"]*)"', r"\1", s)
    s = re.sub(r"\((?:1080p|720p|576p|480p|4K|2160p|高清|HD)\)", "", s, flags=re.I)
    s = re.sub(r"(1080p|720p|576p|480p|4K|高清|HD)$", "", s, flags=re.I)
    s = re.sub(r"[\s\-_·・ⅠⅡⅢⅣⅤ]+", "", s)
    s = re.sub(r"(频道|电视台|台)$", "", s)
    return s.lower()


QUAL_RANK = {"≤360p": 0, "480p": 1, "576p": 2, "720p": 3, "高清": 4, "": 5, "1080p": 6, "4K": 7, "2160p": 7}


def url_quality_hint(u):
    ul = u.lower()
    for k, r in (("360", 0), ("480", 1), ("576", 2), ("720", 3), ("1080", 6), ("4k", 7), ("2160", 7)):
        if k in ul:
            return r
    return 5


def main():
    seed = json.load(open(SEED, encoding="utf-8"))
    cands = []
    for fn in ("candidates_all.json", "candidates_extra4.json"):
        p = "D:/tmp/live/" + fn
        if os.path.exists(p):
            cands += json.load(open(p, encoding="utf-8"))

    def has_safe(ch):
        return any(0 < (x.get("measured_height") or 0) <= SAFE_H for x in ch["sources"])

    lack = [ch for ch in seed["channels"] if not has_safe(ch)]
    print(f"channels lacking <=720p: {len(lack)}; candidates: {len(cands)}", flush=True)

    # name index
    byname = collections.defaultdict(list)
    for c in cands:
        n = norm(c.get("name"))
        if n:
            byname[n].append(c)

    # jobs: (priority, channel_id, url, referer, ua, kind)
    jobs = []
    seen = set()
    for ch in lack:
        existing_urls = {x["url"] for x in ch["sources"]}
        # 1) pattern rewrites of existing sources
        for x in ch["sources"]:
            u = x["url"]
            variants = set()
            if "1080p.m3u8" in u:
                variants |= {u.replace("1080p.m3u8", "720p.m3u8"), u.replace("1080p.m3u8", "576p.m3u8")}
            if "/1080p/" in u:
                variants |= {u.replace("/1080p/", "/720p/"), u.replace("/1080p/", "/576p/")}
            if "hd.m3u8" in u:
                variants.add(u.replace("hd.m3u8", "sd.m3u8"))
            if re.search(r"(?<!\d)1080(?!\d)", u):
                variants.add(re.sub(r"(?<!\d)1080(?!\d)", "720", u))
            for v in variants:
                if v != u and v not in existing_urls and (ch["id"], v) not in seen:
                    seen.add((ch["id"], v))
                    jobs.append((0, ch["id"], v, x.get("referrer") and None, None, "rewrite"))
        # 2) master variant jobs: handled at check time via source URL itself (check(url) walks variants)
        for x in ch["sources"]:
            if x.get("playlist_type") == "master" and (ch["id"], x["url"]) not in seen:
                seen.add((ch["id"], x["url"]))
                jobs.append((1, ch["id"], x["url"], None, None, "existing-master"))
        # 3) candidate URLs by name
        alias = norm(ch["name"])
        matches = []
        if alias:
            for n, lst in byname.items():
                if n == alias:
                    matches += [(3, c) for c in lst]
                elif (alias not in GENERIC and len(alias) >= 4
                      and (alias in n or (len(n) >= 4 and n in alias and n not in GENERIC))):
                    matches += [(4, c) for c in lst]
        for pri, c in matches:
            u = c["url"]
            if u in existing_urls or (ch["id"], u) in seen:
                continue
            seen.add((ch["id"], u))
            q = url_quality_hint(u)
            jobs.append((pri, ch["id"], u, c.get("referrer"), c.get("user_agent"),
                         f"cand:{c.get('source')}"))

    # sort by channel then priority then low quality hint first
    jobs.sort(key=lambda j: (j[0], url_quality_hint(j[2])))
    print(f"jobs: {len(jobs)}", flush=True)
    byid = {c["id"]: c for c in seed["channels"]}
    results = []
    safe_map = {}
    lock = None
    done = 0
    t0 = time.time()

    def run(job):
        pri, cid, url, referer, ua, kind = job
        if cid in safe_map:
            return None
        try:
            r = check(url, referer, ua)
        except Exception as e:  # noqa: BLE001
            r = {"safe": False, "reason": f"exception {type(e).__name__}: {e}"}
        r.update({"channel": cid, "url": url, "kind": kind, "priority": pri,
                  "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
        return r

    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = [ex.submit(run, j) for j in jobs]
        for f in futs:
            try:
                r = f.result()
            except Exception:
                continue
            done += 1
            if done % 50 == 0:
                print(f"  {done}/{len(jobs)} ({time.time()-t0:.0f}s) safe channels={len(safe_map)}", flush=True)
            if not r:
                continue
            results.append(r)
            if r.get("safe"):
                safe_map.setdefault(r["channel"], r)

    json.dump({"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
               "channels_lacking": len(lack), "jobs": len(jobs),
               "results": results}, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"done {time.time()-t0:.0f}s; safe found for {len(safe_map)}/{len(lack)} channels", flush=True)
    still = [c for c in lack if c["id"] not in safe_map]
    print("still lacking:", len(still))
    for c in still:
        print("  ", c["id"], c["name"], c.get("best_tier"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
