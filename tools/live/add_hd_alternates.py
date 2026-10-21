#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Add 1080p alternate-CDN backup sources for safe-gap channels (for HD devices only)."""
import glob, json, re, sys, time, collections
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "tools/live")
import remeasure_segments as R
import verify as V

OUT = "D:/tmp/live/hd_alternates_result.json"
GENERIC = {"新闻综合", "综合", "公共", "影视", "生活", "经济", "科教", "文旅", "少儿",
           "体育", "纪实", "国际", "文化", "资讯", "导视", "一套", "二套", "新闻", "都市"}


def norm(s):
    s = re.sub(r"\[[^\]]*\]", "", s or "")
    s = re.sub(r"\([^)]*\)", "", s)
    s = re.sub(r"[\s\-_·・ⅠⅡⅢⅣⅤ]+", "", s)
    s = re.sub(r"(频道|电视台|台)$", "", s)
    return s.lower()


def cctvkey(name):
    m = re.search(r"cctv[-\s]?(1[0-7]|[1-9]|5\+|4k|8k)", name or "", re.I)
    return "cctv" + m.group(1).upper() if m else None


def parse(path):
    try:
        txt = open(path, encoding="utf-8-sig", errors="replace").read()
    except OSError:
        return []
    out, pend = [], None
    for ln in txt.splitlines():
        ln = ln.strip()
        if ln.startswith("#EXTINF"):
            pend = ln.split(",", 1)[1].strip() if "," in ln else ""
        elif ln and not ln.startswith("#") and pend:
            out.append((pend, ln))
            pend = None
    return out


def main():
    seed = json.load(open("assets/live_seed.json", encoding="utf-8"))
    lack = [c for c in seed["channels"] if not c.get("has_safe_tier") and len(c["sources"]) < 4]
    # prioritize main channels, then well-known locals
    pri = {"央视频道": 0, "卫视频道": 1, "地方频道": 2}
    lack.sort(key=lambda c: (pri.get(c.get("group"), 3), c.get("name") or ""))
    lack = lack[:90]
    pool = []
    for f in ["D:/tmp/live/bf_cctv.m3u8", "D:/tmp/live/bf_cn_province_status.m3u8",
              "D:/tmp/live/bf_cn_all_status.m3u8", "D:/tmp/live/bf_cn_all.m3u8",
              "D:/tmp/live/bf_cn_province.m3u8", "D:/tmp/live/bf_cn_cctv.m3u8"]:
        pool += parse(f)
    for f in glob.glob("D:/tmp/live/tvlist2/*.m3u"):
        pool += parse(f)
    for fn in ("candidates_all.json", "candidates_extra4.json"):
        p = "D:/tmp/live/" + fn
        try:
            for c in json.load(open(p, encoding="utf-8")):
                pool.append((c.get("name") or "", c.get("url") or ""))
        except OSError:
            pass
    seen = set()
    pool = [e for e in pool if not (e[1] in seen or seen.add(e[1]))]
    byname = collections.defaultdict(list)
    for name, url in pool:
        byname[norm(name)].append((name, url))
    jobs = []
    for ch in lack:
        alias = norm(ch["name"])
        ck = cctvkey(ch["name"])
        cands = []
        for n, lst in byname.items():
            if n == alias or (ck and cctvkey(n) == ck):
                cands += lst
            elif (alias not in GENERIC and len(alias) >= 4 and n != alias
                  and (alias in n or (len(n) >= 4 and n in alias and n not in GENERIC))):
                cands += lst
        def normu(u):
            from urllib.parse import urlparse
            q = urlparse(u); host = q.netloc.lower()
            if host.endswith(":80") or host.endswith(":443"):
                host = host.rsplit(":", 1)[0]
            return (host, q.path.rstrip("/"))
        existing = {s["url"] for s in ch["sources"]}
        existing_n = {normu(u) for u in existing}
        cands = [(n, u) for n, u in cands
                 if u not in existing and normu(u) not in existing_n and u][:4]
        for n, u in cands:
            jobs.append((ch["id"], ch["name"], n, u))
    print("candidate jobs", len(jobs), "channels", len({j[0] for j in jobs}), flush=True)
    res, done, t0 = [], 0, time.time()

    def run(j):
        cid, cname, srcname, url = j
        rec, body = V.curl(url, max_time=8, max_filesize=524288)
        pl = V.parse_playlist(body)
        if not pl["extm3u"]:
            return None
        try:
            r = R.measure_source(url)
        except Exception:
            return None
        h = r.get("measured_height") or 0
        if h < 1000:
            return None
        return {"channel": cid, "channel_name": cname, "source_name": srcname, "url": url,
                "ttfb_ms": int((rec.get("ttfb") or 0) * 1000), "http": rec.get("http"),
                "ct": rec.get("ct"), "playlist_type": r.get("playlist_type"),
                "measured_height": h, "measured_width": r.get("measured_width"),
                "measured_codec": r.get("measured_codec"), "measured_fps": r.get("measured_fps"),
                "seg_url": r.get("seg_url"), "seg_bytes": r.get("seg_bytes"),
                "seg_http": r.get("seg_http"), "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}

    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = [ex.submit(run, j) for j in jobs]
        for f in futs:
            try:
                r = f.result()
            except Exception:
                r = None
            done += 1
            if done % 30 == 0:
                print(f"  {done}/{len(jobs)} accepted={len(res)} ({time.time()-t0:.0f}s)", flush=True)
            if r:
                res.append(r)
    # best (lowest ttfb) per channel
    best = {}
    for r in res:
        if r["channel"] not in best or r["ttfb_ms"] < best[r["channel"]]["ttfb_ms"]:
            best[r["channel"]] = r
    picked = sorted(best.values(), key=lambda r: r["ttfb_ms"])[:18]
    json.dump({"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "raw": res, "picked": picked},
              open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"done {time.time()-t0:.0f}s; accepted {len(res)}, channels {len(best)}, picked {len(picked)}")
    for r in picked:
        print(f"  {r['channel_name'][:16]:16s} {r['measured_height']} {r['ttfb_ms']}ms {r['url'][:80]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
