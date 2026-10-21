#!/usr/bin/env python
"""Final low-concurrency verification pass with one retry.

Collects every URL that ever returned #EXTM3U (or was playable) in earlier
passes, re-probes it with 10-way concurrency, retries failures once, and writes
final_verify.json for seed generation.
"""
import json, os, sys, time
from concurrent.futures import ThreadPoolExecutor, as_completed

import verify as V

BASE = os.path.dirname(os.path.abspath(__file__))
CHECKED_AT = time.strftime("%Y-%m-%dT%H:%M:%S%z")


def load_union():
    meta = {}
    for fn in ("candidates.json", "candidates_myiptv.json", "candidates_extra.json"):
        p = os.path.join(BASE, fn)
        if not os.path.exists(p):
            continue
        for c in json.load(open(p, encoding="utf-8")):
            meta.setdefault(c["url"], c)
    urls = set()
    for fn in ("probe_results.json", "verify_all.json", "verify_extra.json"):
        p = os.path.join(BASE, fn)
        if not os.path.exists(p):
            continue
        for r in json.load(open(p, encoding="utf-8")):
            if r.get("extm3u") or r.get("playable"):
                urls.add(r["url"])
    cands = []
    for u in sorted(urls):
        c = dict(meta.get(u) or {"channel": "", "name": "", "url": u, "declared_quality": "",
                                 "referrer": None, "user_agent": None, "source": "unknown",
                                 "country": "", "group_hint": ""})
        cands.append(c)
    return cands


def probe_with_retry(c):
    for attempt in (1, 2):
        r = V.stage_a(c)
        if r.get("extm3u"):
            r = V.stage_b(r)
        r["attempt"] = attempt
        r["checked_at"] = CHECKED_AT
        if r.get("playable"):
            r["attempts_used"] = attempt
            return r
        if attempt == 1:
            time.sleep(0.8)
    r["attempts_used"] = 2
    return r


def main():
    cands = load_union()
    print(f"final pass: {len(cands)} unique URLs", flush=True)
    results, done, t0 = [], 0, time.time()
    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = [ex.submit(probe_with_retry, c) for c in cands]
        for f in as_completed(futs):
            try:
                results.append(f.result())
            except Exception as e:  # noqa: BLE001
                results.append({"url": "?", "error": repr(e), "extm3u": False, "playable": False})
            done += 1
            if done % 50 == 0:
                print(f"  {done}/{len(cands)} ({time.time()-t0:.0f}s)", flush=True)
    json.dump(results, open(os.path.join(BASE, "final_verify.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    playable = [r for r in results if r.get("playable")]
    recovered = [r for r in playable if r.get("attempts_used") == 2]
    print(f"done {time.time()-t0:.0f}s: playable {len(playable)}/{len(results)}, "
          f"recovered on retry: {len(recovered)}")


if __name__ == "__main__":
    main()
