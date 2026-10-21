#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Third pass: best-fan/iptv-sources low-resolution playlists (576/720) for remaining channels."""
import json, re, sys, time, collections
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "tools/live")
import remeasure_segments as R

OUT = "D:/tmp/live/safe_find3_results.json"
FILES = ["bf_cctv.m3u8", "bf_cn_cctv_status.m3u8", "bf_cn_province_status.m3u8",
         "bf_cn_all_status.m3u8", "bf_cn_cctv.m3u8", "bf_cn_province.m3u8", "bf_cn_all.m3u8"]


def parse(path):
    txt = open(path, encoding="utf-8-sig", errors="replace").read()
    out, pend = [], None
    for ln in txt.splitlines():
        ln = ln.strip()
        if ln.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([A-Za-z0-9-]+)="([^"]*)"', ln))
            name = ln.split(",", 1)[1].strip() if "," in ln else ""
            pend = (attrs, name)
        elif ln and not ln.startswith("#") and pend:
            attrs, name = pend
            pend = None
            tvg = attrs.get("tvg-name", "")
            q = None
            m = re.search(r"\[(\d{3,4})\]", tvg) or re.search(r"\((\d{3,4})p\)", name, re.I)
            if m:
                q = int(m.group(1))
            out.append({"name": name, "tvg": tvg, "group": attrs.get("group-title", ""),
                        "url": ln, "quality": q})
    return out


def norm(s):
    s = re.sub(r"\[[^\]]*\]", "", s or "")
    s = re.sub(r"\([^)]*\)", "", s)
    s = re.sub(r"[\s\-_·・ⅠⅡⅢⅣⅤ]+", "", s)
    s = re.sub(r"(频道|电视台|台)$", "", s)
    return s.lower()


def cctvkey(name):
    m = re.search(r"cctv[-\s]?(1[0-7]|[1-9]|5\+|4k|8k)", name or "", re.I)
    return "cctv" + m.group(1).upper() if m else None


def main():
    seed = json.load(open("assets/live_seed.json", encoding="utf-8"))
    rem = json.load(open("D:/tmp/live/remeasure_segments.json", encoding="utf-8"))
    by = collections.defaultdict(list)
    for r in rem["results"]:
        by[r.get("channel")].append(r)
    sf = json.load(open("D:/tmp/live/safe_find_results.json", encoding="utf-8"))
    safe_new = {r["channel"] for r in sf["results"] if r.get("safe")}

    def has_safe(cid):
        return cid in safe_new or any(r.get("safe_url") and 180 <= (r.get("measured_height") or 0) <= 720
                                       for r in by.get(cid, []))

    lack = [c for c in seed["channels"] if not has_safe(c["id"])]
    entries = []
    for f in FILES:
        try:
            entries += parse("D:/tmp/live/" + f)
        except OSError:
            pass
    seen = set()
    entries = [e for e in entries if not (e["url"] in seen or seen.add(e["url"]))]
    idx = collections.defaultdict(list)
    for e in entries:
        k = cctvkey(e["tvg"] or e["name"])
        if k:
            idx[k].append(e)
        idx[norm(e["name"])].append(e)
        n2 = norm(e["tvg"])
        if n2:
            idx[n2].append(e)
    jobs = []
    for ch in lack:
        key = cctvkey(ch["name"]) or ("cctv" + ch["id"][4:] if ch["id"].startswith("cctv") else None)
        cands = idx.get(key, []) if key else []
        if not cands:
            cands = idx.get(norm(ch["name"]), [])
        for e in cands:
            if e["url"] in {s["url"] for s in ch["sources"]}:
                continue
            jobs.append((ch["id"], e))
    # prefer <=720 entries
    jobs.sort(key=lambda j: (0 if (j[1]["quality"] or 9999) <= 720 else 1, j[1]["quality"] or 9999))
    print(f"lacking={len(lack)} jobs={len(jobs)} entries={len(entries)}", flush=True)

    res, safe_map = [], {}
    done, t0 = 0, time.time()

    def run(j):
        cid, e = j
        if cid in safe_map:
            return None
        try:
            r = R.measure_source(e["url"])
        except Exception as ex:  # noqa: BLE001
            r = {"reason": f"exc {type(ex).__name__}: {ex}"}
        r.update({"channel": cid, "url": e["url"], "declared_quality": e["quality"],
                  "name": e["name"], "tvg": e["tvg"], "group": e["group"],
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
            if done % 25 == 0:
                print(f"  {done}/{len(jobs)} ({time.time()-t0:.0f}s) safe={len(safe_map)}", flush=True)
            if not r:
                continue
            res.append(r)
            h = r.get("measured_height") or 0
            if r.get("safe_url") and 180 <= h <= 720:
                safe_map.setdefault(r["channel"], r)
    json.dump({"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "lacking": len(lack),
               "jobs": len(jobs), "results": res}, open(OUT, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"done {time.time()-t0:.0f}s; new safe channels {len(safe_map)}")
    for cid, r in safe_map.items():
        print("  ", cid, r.get("measured_height"), (r.get("safe_url") or "")[:95])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
