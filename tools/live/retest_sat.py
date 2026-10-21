#!/usr/bin/env python
"""Retest untested satellite URLs (never returned #EXTM3U) once, then verify winners."""
import json, os, time
from concurrent.futures import ThreadPoolExecutor, as_completed
import verify as V

BASE = os.path.dirname(os.path.abspath(__file__))


def one(c):
    c = dict(c)
    c["user_agent"] = c.get("user_agent") or None
    a = V.stage_a(c)
    a["checked_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    if a.get("extm3u"):
        a = V.stage_b(a)
        a["attempts_used"] = 1
    return a


def main():
    cands = json.load(open(os.path.join(BASE, "missing_sat.json"), encoding="utf-8"))
    print("retesting", len(cands), flush=True)
    res, t0 = [], time.time()
    with ThreadPoolExecutor(max_workers=16) as ex:
        futs = [ex.submit(one, c) for c in cands]
        for f in as_completed(futs):
            try:
                res.append(f.result())
            except Exception as e:  # noqa: BLE001
                res.append({"url": "?", "error": repr(e), "extm3u": False, "playable": False})
    json.dump(res, open(os.path.join(BASE, "retest_sat.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    ok = [r for r in res if r.get("playable")]
    print(f"done {time.time()-t0:.0f}s: m3u8={sum(1 for r in res if r.get('extm3u'))}, playable={len(ok)}")
    for r in ok:
        print(f"  {r['name'][:24]:24s} {r['chosen']['resolution']!r:9s} {r['url'][:90]}")


if __name__ == "__main__":
    main()
