#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fourth pass: aggregated TV-list m3u files (local channels) for remaining safe-source gaps."""
import json, re, sys, time, collections
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "tools/live")
import remeasure_segments as R

OUT = "D:/tmp/live/safe_find4_results.json"
FILES = ["D:/tmp/live/tvlist/1163078.m3u", "D:/tmp/live/tvlist/336471.m3u",
         "D:/tmp/live/tvlist/257082.m3u", "D:/tmp/live/tvlist/231500.m3u"]


def parse(path):
    txt = open(path, encoding="utf-8-sig", errors="replace").read()
    out, pend = [], None
    for ln in txt.splitlines():
        ln = ln.strip()
        if ln.startswith("#EXTINF"):
            name = ln.split(",", 1)[1].strip() if "," in ln else ""
            out.append(name)
            pend = name
        elif ln and not ln.startswith("#") and pend:
            out[-1] = (pend, ln)
            pend = None
    return [x for x in out if isinstance(x, tuple)]


def norm(s):
    s = re.sub(r"\[[^\]]*\]", "", s or "")
    s = re.sub(r"\([^)]*\)", "", s)
    s = re.sub(r"[\s\-_·・ⅠⅡⅢⅣⅤ]+", "", s)
    s = re.sub(r"(频道|电视台|台)$", "", s)
    return s.lower()


GENERIC = {"新闻综合", "综合", "公共", "影视", "生活", "经济", "科教", "文旅", "少儿",
           "体育", "纪实", "国际", "文化", "资讯", "导视", "一套", "二套", "新闻", "都市"}


def cctvkey(name):
    m = re.search(r"cctv[-\s]?(1[0-7]|[1-9]|5\+|4k|8k)", name or "", re.I)
    return "cctv" + m.group(1).upper() if m else None


def has_safe_map(seed):
    rem = json.load(open("D:/tmp/live/remeasure_segments.json", encoding="utf-8"))
    by = collections.defaultdict(list)
    for r in rem["results"]:
        by[r.get("channel")].append(r)
    safe = set()
    for cid, rs in by.items():
        if any(r.get("safe_url") and 180 <= (r.get("measured_height") or 0) <= 720 for r in rs):
            safe.add(cid)
    for fn in ("safe_find_results.json", "safe_find2_results.json", "safe_find3_results.json"):
        for r in json.load(open("D:/tmp/live/" + fn, encoding="utf-8"))["results"]:
            h = r.get("measured_height") or (r.get("measure") or {}).get("height") or 0
            if r.get("safe_url") and 180 <= h <= 720 and "cctv1hd" not in r["safe_url"]:
                safe.add(r.get("channel"))
    safe |= {"sat_安徽", "sat_广东", "sat_陕西", "sat_黑龙江"}
    return safe


def main():
    seed = json.load(open("assets/live_seed.json", encoding="utf-8"))
    safe = has_safe_map(seed)
    lack = [c for c in seed["channels"] if c["id"] not in safe]
    print("lacking", len(lack), flush=True)
    entries = []
    for f in FILES:
        try:
            entries += parse(f)
        except OSError:
            pass
    seen = set()
    ents = [e for e in entries if not (e[1] in seen or seen.add(e[1]))]
    print("entries", len(ents), flush=True)
    byname = collections.defaultdict(list)
    for name, url in ents:
        byname[norm(name)].append((name, url))
    jobs, seenj = [], set()
    for ch in lack:
        alias = norm(ch["name"])
        keys = {alias}
        ck = cctvkey(ch["name"])
        if ck:
            keys.add(ck)
        cands = []
        for n, lst in byname.items():
            if n in keys or (ck and cctvkey(n) == ck):
                cands += lst
            elif (alias not in GENERIC and len(alias) >= 4 and n != alias
                  and (alias in n or (len(n) >= 4 and n in alias and n not in GENERIC))):
                cands += lst
        # unique urls, prefer known working IPTV hosts and 720/576 hints
        uniq = []
        for name, url in cands:
            if url in {s["url"] for s in ch["sources"]} or (ch["id"], url) in seenj:
                continue
            seenj.add((ch["id"], url))
            uniq.append((name, url))
        uniq.sort(key=lambda x: (0 if re.search(r"(720|576|480|sd|标清)", x[1], re.I) else 1,
                                 0 if re.search(r"(198\.95\.220|228\.160\.112|136\.38\.87|25\.252\.226)", x[1]) else 1))
        for name, url in uniq[:8]:
            jobs.append((ch["id"], name, url))
    print("jobs", len(jobs), flush=True)
    res, safe_map = [], {}
    done, t0 = 0, time.time()

    def run(j):
        cid, name, url = j
        if cid in safe_map:
            return None
        try:
            r = R.measure_source(url)
        except Exception as e:  # noqa: BLE001
            r = {"reason": f"exc {type(e).__name__}: {e}"}
        r.update({"channel": cid, "name": name, "url": url,
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
        print("  ", cid, r.get("name")[:18], r.get("measured_height"), (r.get("safe_url") or "")[:80])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
