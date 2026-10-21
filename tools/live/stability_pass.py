#!/usr/bin/env python
"""Two independent probes per seed source -> pass count for reliability ranking."""
import json, os, time
from concurrent.futures import ThreadPoolExecutor, as_completed
import verify as V

BASE = os.path.dirname(os.path.abspath(__file__))


def probe_once(ch, src, attempt):
    c = {"channel": ch["id"], "name": ch["name"], "url": src["url"],
         "declared_quality": src["quality"], "referrer": None, "user_agent": None,
         "source": src.get("source_list"), "country": "CN", "group_hint": ch["group"]}
    a = V.stage_a(c)
    ok, detail = False, {}
    if a.get("extm3u"):
        r = V.stage_b(a)
        ok = bool(r.get("playable"))
        detail = {"kind": r.get("kind"), "seg_bytes": (r.get("seg") or {}).get("bytes"),
                  "ttfb": a.get("a", {}).get("ttfb"), "http": a.get("a", {}).get("http")}
    else:
        detail = {"http": a.get("a", {}).get("http"), "err": a.get("a", {}).get("err")}
    return ok, attempt, detail


def check(item):
    ch, src = item
    ok1, _, d1 = probe_once(ch, src, 1)
    time.sleep(0.5)
    ok2, _, d2 = probe_once(ch, src, 2)
    return {"url": src["url"], "channel": ch["name"], "group": ch["group"],
            "ok1": ok1, "ok2": ok2, "passes": int(ok1) + int(ok2),
            "last_ok": ok2 or ok1, "detail2": d2, "detail1": d1,
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}


seed = json.load(open(os.path.join(BASE, "live_seed.json"), encoding="utf-8"))
items = [(ch, s) for ch in seed["channels"] for s in ch["sources"]]
print("stability pass:", len(items), flush=True)
res, done, t0 = [], 0, time.time()
with ThreadPoolExecutor(max_workers=10) as ex:
    futs = [ex.submit(check, it) for it in items]
    for f in as_completed(futs):
        try:
            res.append(f.result())
        except Exception as e:  # noqa: BLE001
            res.append({"url": "?", "error": repr(e), "passes": 0})
        done += 1
        if done % 50 == 0:
            print(f"  {done}/{len(items)} ({time.time()-t0:.0f}s)", flush=True)
json.dump(res, open(os.path.join(BASE, "stability.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
from collections import Counter
print("done", time.time() - t0, Counter(r.get("passes") for r in res))
