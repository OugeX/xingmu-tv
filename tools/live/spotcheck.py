#!/usr/bin/env python
"""Spot-check a stratified sample of seed sources with the same two-stage probe."""
import json, os, random, sys, time
from concurrent.futures import ThreadPoolExecutor, as_completed
import verify as V

BASE = os.path.dirname(os.path.abspath(__file__))
random.seed(7)

seed = json.load(open(os.path.join(BASE, "live_seed.json"), encoding="utf-8"))
pool = []
for ch in seed["channels"]:
    pool.append((ch, ch["sources"][0]))
    if len(ch["sources"]) > 1:
        pool.append((ch, ch["sources"][-1]))
sample = random.sample(pool, min(16, len(pool)))


def check(item):
    ch, src = item
    c = {"channel": ch["id"], "name": ch["name"], "url": src["url"],
         "declared_quality": src["quality"], "referrer": None, "user_agent": None,
         "source": "seed", "country": "CN", "group_hint": ch["group"]}
    a = V.stage_a(c)
    ok = False
    detail = {}
    if a.get("extm3u"):
        r = V.stage_b(a)
        ok = bool(r.get("playable"))
        detail = {"kind": r.get("kind"), "chosen": r.get("chosen"),
                  "seg": r.get("seg")}
    return {"channel": ch["name"], "group": ch["group"], "url": src["url"], "ok": ok,
            "http": a.get("a", {}).get("http"), "ct": a.get("a", {}).get("ct"),
            "ttfb": a.get("a", {}).get("ttfb"), "detail": detail}


res, t0 = [], time.time()
with ThreadPoolExecutor(max_workers=8) as ex:
    futs = [ex.submit(check, it) for it in sample]
    for f in as_completed(futs):
        res.append(f.result())
res.sort(key=lambda r: (not r["ok"], r["channel"]))
print(f"spot-check {len(res)} sources in {time.time()-t0:.0f}s: {sum(1 for r in res if r['ok'])} pass")
for r in res:
    d = r["detail"]
    print(f"  {'PASS' if r['ok'] else 'FAIL'} {r['group'][:3]:3s} {r['channel'][:14]:14s} "
          f"http={r['http']} kind={d.get('kind','-')} seg={(d.get('seg') or {}).get('bytes')} {r['url'][:66]}")
json.dump(res, open(os.path.join(BASE, "spotcheck.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
