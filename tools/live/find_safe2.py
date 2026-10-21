#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Second pass: segment-decode measurement of candidate URLs for channels still lacking safe sources."""
import json, os, re, sys, time, collections
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "tools/live")
import remeasure_segments as R

SEED = "assets/live_seed.json"
OUT = "D:/tmp/live/safe_find2_results.json"


def norm(s):
    s = (s or "").strip()
    s = re.split(r"\$LR", s)[0]
    s = re.sub(r'tvg-id=.*?tvg-name="([^"]*)"', r"\1", s)
    s = re.sub(r"\((?:1080p|720p|576p|480p|4K|2160p|高清|HD)\)", "", s, flags=re.I)
    s = re.sub(r"(1080p|720p|576p|480p|4K|高清|HD)$", "", s, flags=re.I)
    s = re.sub(r"[\s\-_·・ⅠⅡⅢⅣⅤ]+", "", s)
    s = re.sub(r"(频道|电视台|台)$", "", s)
    return s.lower()


GENERIC = {"新闻综合", "综合", "公共", "影视", "生活", "经济", "科教", "文旅", "少儿",
           "体育", "纪实", "国际", "文化", "资讯", "导视", "一套", "二套", "新闻", "都市"}


def main():
    seed = json.load(open(SEED, encoding="utf-8"))
    rem = json.load(open("D:/tmp/live/remeasure_segments.json", encoding="utf-8"))
    by = collections.defaultdict(list)
    for r in rem["results"]:
        by[r.get("channel")].append(r)
    sf = json.load(open("D:/tmp/live/safe_find_results.json", encoding="utf-8"))
    safe_new = {r["channel"] for r in sf["results"] if r.get("safe")}

    def has_safe(cid):
        return cid in safe_new or any(r.get("safe_url") for r in by.get(cid, []))

    lack = [c for c in seed["channels"] if not has_safe(c["id"])]
    cands = []
    for fn in ("candidates_all.json", "candidates_extra4.json"):
        p = "D:/tmp/live/" + fn
        if os.path.exists(p):
            cands += json.load(open(p, encoding="utf-8"))
    byname = collections.defaultdict(list)
    for c in cands:
        n = norm(c.get("name"))
        if n:
            byname[n].append(c)

    jobs, seen = [], set()
    for ch in lack:
        existing = {s["url"] for s in ch["sources"]}
        alias = norm(ch["name"])
        matched = []
        if alias:
            for n, lst in byname.items():
                if n == alias:
                    matched += [(0, c) for c in lst]
                elif (alias not in GENERIC and len(alias) >= 4 and n != alias
                      and (alias in n or (len(n) >= 4 and n in alias and n not in GENERIC))):
                    matched += [(1, c) for c in lst]
        # pattern rewrites of candidate URLs are not useful; only real candidates
        for pri, c in matched:
            u = c["url"]
            if u in existing or (ch["id"], u) in seen:
                continue
            seen.add((ch["id"], u))
            jobs.append((ch["id"], u, c.get("referrer"), c.get("user_agent"), pri, c.get("source")))
    jobs.sort(key=lambda j: (j[4], len(j[1])))
    print(f"lacking={len(lack)} jobs={len(jobs)}", flush=True)

    results, safe_map = [], {}
    done, t0 = 0, time.time()

    def run(j):
        cid, url, ref, ua, pri, src = j
        if cid in safe_map:
            return None
        try:
            r = R.measure_source(url, referer=ref, ua=ua)
        except Exception as e:  # noqa: BLE001
            r = {"reason": f"exc {type(e).__name__}: {e}"}
        r.update({"channel": cid, "url": url, "priority": pri, "source_list": src,
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
            results.append(r)
            if r.get("safe_url"):
                safe_map.setdefault(r["channel"], r)
    json.dump({"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "lacking": len(lack),
               "jobs": len(jobs), "results": results}, open(OUT, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"done {time.time()-t0:.0f}s; new safe channels {len(safe_map)}")
    for cid, r in safe_map.items():
        print("  ", cid, r.get("safe_height"), r.get("safe_url")[:90])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
