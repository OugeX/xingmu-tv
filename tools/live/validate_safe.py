#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Final validation: re-measure every candidate safe URL (segment decode) before publishing."""
import json, sys, time, collections
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "tools/live")
import remeasure_segments as R

OUT = "D:/tmp/live/safe_validated.json"


def candidate_safe_urls():
    """channel -> set of candidate safe URLs gathered by earlier passes."""
    cand = collections.defaultdict(set)
    rem = json.load(open("D:/tmp/live/remeasure_segments.json", encoding="utf-8"))
    for r in rem["results"]:
        if r.get("safe_url") and 180 <= (r.get("measured_height") or 0) <= 720:
            cand[r["channel"]].add(r["safe_url"])
    for fn in ("safe_find_results.json", "safe_find2_results.json", "safe_find3_results.json",
               "safe_find4_results.json", "safe_find5_results.json"):
        try:
            d = json.load(open("D:/tmp/live/" + fn, encoding="utf-8"))
        except OSError:
            continue
        for r in d["results"]:
            u = r.get("safe_url") or (r.get("url") if r.get("safe") else None)
            h = r.get("measured_height") or (r.get("measure") or {}).get("height") or 0
            if u and 180 <= h <= 720 and "cctv1hd" not in u:
                cand[r["channel"]].add(u)
    manual = {
        "sat_安徽": "http://124.228.160.112:9901/tsfile/live/0130_1.m3u8?key=txiptv&playlive=1&authid=0",
        "sat_广东": "http://124.228.160.112:9901/tsfile/live/0125_1.m3u8?key=txiptv&playlive=1&authid=0",
        "sat_陕西": "http://120.198.95.220:9901/tsfile/live/1068_1.m3u8?key=txiptv&playlive=1&down=1",
        "sat_黑龙江": "http://36.136.38.87:9901/tsfile/live/0143_1.m3u8?key=txiptv&playlive=1&authid=0",
        "sat_东方": "http://bp-resource-dfl.bestv.cn/155/2/video.m3u8",
    }
    for cid, u in manual.items():
        cand[cid].add(u)
    return cand


def main():
    cand = candidate_safe_urls()
    jobs = [(cid, u) for cid, us in cand.items() for u in us]
    print("validating", len(jobs), "safe candidates for", len(cand), "channels", flush=True)
    out = []
    done, t0 = 0, time.time()

    def run(j):
        cid, url = j
        try:
            r = R.measure_source(url)
        except Exception as e:  # noqa: BLE001
            r = {"reason": f"exc {type(e).__name__}: {e}"}
        r.update({"channel": cid, "url": url,
                  "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
        return r

    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = [ex.submit(run, j) for j in jobs]
        for f in futs:
            try:
                out.append(f.result())
            except Exception:
                pass
            done += 1
            if done % 40 == 0:
                print(f"  {done}/{len(jobs)} ({time.time()-t0:.0f}s)", flush=True)
    json.dump({"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "results": out},
              open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    ok = collections.defaultdict(list)
    for r in out:
        h = r.get("measured_height") or 0
        if r.get("safe_url") and 180 <= h <= 720:
            ok[r["channel"]].append(r)
    print(f"validated {sum(len(v) for v in ok.values())} safe URLs across {len(ok)} channels")
    for cid in sorted(ok):
        r = sorted(ok[cid], key=lambda x: -(x.get("measured_height") or 0))[0]
        print("  ", cid, r.get("measured_height"), (r.get("safe_url") or "")[:90])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
