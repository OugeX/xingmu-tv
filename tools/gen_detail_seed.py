#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the prefetched detail seed for bfzy1 (MacCMS) hot works.

Pipeline:
  1. collect up to N unique vod ids: ac=detail&h=24 (first 300), then latest
     pages and category pages (t=20/30/39/45) as the "homepage/hot" supplement;
  2. fetch each detail with ac=detail&ids=<vod_id> (single-item response),
     concurrency 4, 2 retries, progress every 50;
  3. write release/detail_seed.json + manifest + zip, and a 20-entry sample
     into assets/detail_seed.sample.json.

Usage:
  python tools/gen_detail_seed.py                 # full 500
  python tools/gen_detail_seed.py --limit 30      # smoke test
  python tools/gen_detail_seed.py --version v2
"""
import argparse, hashlib, json, os, random, sys, time, zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone, timedelta

import requests

BASE = "https://macapi1.com/maccms/json/baofeng/"
UA = "Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
CST = timezone(timedelta(hours=8))
CATEGORIES = [20, 30, 39, 45]  # 电影片 / 连续剧 / 动漫片 / 综艺片


def now_iso():
    return datetime.now(CST).isoformat(timespec="seconds")


def fetch_json(session, params, timeout=15, retries=2):
    last = None
    for attempt in range(retries + 1):
        try:
            r = session.get(BASE, params=params, timeout=timeout,
                            headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
            r.raise_for_status()
            return r.json()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(0.4 * (attempt + 1))
    raise last


def collect_ids(session, limit, log=print):
    """Return ordered list of (vodId, origin) unique ids."""
    out, seen = [], set()

    def add(items, origin):
        for v in items:
            vid = str(v.get("vod_id") or "").strip()
            if vid and vid not in seen:
                seen.add(vid)
                out.append((vid, origin))

    # 1) h=24: up to 300
    page = 1
    while len(out) < min(300, limit):
        d = fetch_json(session, {"ac": "detail", "h": 24, "pg": page})
        items = d.get("list") or []
        if not items:
            break
        add(items[: max(0, min(300, limit) - len(out))], "h24")
        log(f"collect h24 pg={page}: +{len(items)} => {len(out)}")
        if page >= int(d.get("pagecount") or 1):
            break
        page += 1

    # 2) latest pages (homepage feed) as hot supplement
    page = 1
    while len(out) < limit:
        d = fetch_json(session, {"ac": "detail", "pg": page})
        items = d.get("list") or []
        if not items:
            break
        add(items, "latest")
        if page % 5 == 0:
            log(f"collect latest pg={page}: => {len(out)}")
        pc = int(d.get("pagecount") or 1)
        if page >= pc:
            break
        page += 1

    # 3) category top pages if still short
    if len(out) < limit:
        for t in CATEGORIES:
            for page in (1, 2):
                d = fetch_json(session, {"ac": "detail", "t": t, "pg": page})
                add(d.get("list") or [], f"category:t{t}")
                if len(out) >= limit:
                    break
            if len(out) >= limit:
                break
    return out[:limit]


def fetch_detail(session, vid, timeout=15, retries=2):
    last = None
    for attempt in range(retries + 1):
        try:
            r = session.get(BASE, params={"ac": "detail", "ids": vid}, timeout=timeout,
                            headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
            r.raise_for_status()
            d = r.json()
            items = d.get("list") or []
            if d.get("code") == 1 and items:
                return items[0]
            last = RuntimeError(f"empty list for {vid}: code={d.get('code')}")
        except Exception as e:  # noqa: BLE001
            last = e
        time.sleep(0.4 * (attempt + 1))
    raise last


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=500)
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--version", default="v1")
    ap.add_argument("--out-dir", default="release")
    ap.add_argument("--sample", default="assets/detail_seed.sample.json")
    ap.add_argument("--sample-size", type=int, default=20)
    ap.add_argument("--base-url", default=BASE)
    ap.add_argument("--timeout", type=int, default=15)
    ap.add_argument("--no-zip", action="store_true")
    args = ap.parse_args()
    BASE = args.base_url

    os.makedirs(args.out_dir, exist_ok=True)
    os.makedirs(os.path.dirname(args.sample) or ".", exist_ok=True)
    session = requests.Session()
    t0 = time.time()
    ids = collect_ids(session, args.limit)
    print(f"[collect] {len(ids)} unique ids in {time.time()-t0:.1f}s", flush=True)
    if not ids:
        print("no ids collected", file=sys.stderr)
        return 2

    entries, failed = [], []
    done, t1 = 0, time.time()

    def work(item):
        vid, origin = item
        try:
            raw = fetch_detail(session, vid, timeout=args.timeout, retries=2)
            return vid, origin, raw, None
        except Exception as e:  # noqa: BLE001
            return vid, origin, None, str(e)[:200]

    with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        futs = [ex.submit(work, it) for it in ids]
        for f in as_completed(futs):
            vid, origin, raw, err = f.result()
            done += 1
            if raw:
                entries.append({
                    "vodId": str(raw.get("vod_id") or vid),
                    "sourceId": "bfzy1",
                    "name": raw.get("vod_name") or "",
                    "year": raw.get("vod_year") or "",
                    "fetchedAt": now_iso(),
                    "collectedFrom": origin,
                    "detailJson": raw,
                })
            else:
                failed.append({"vodId": vid, "error": err})
            if done % 50 == 0:
                print(f"[detail] {done}/{len(ids)} ok={len(entries)} failed={len(failed)} "
                      f"({time.time()-t1:.1f}s)", flush=True)

    # keep deterministic order by collection order
    order = {vid: i for i, (vid, _) in enumerate(ids)}
    entries.sort(key=lambda e: order.get(e["vodId"], 999999))

    generated_at = now_iso()
    envelope = {
        "schema_version": 1,
        "source_id": "bfzy1",
        "base_url": BASE,
        "generated_at": generated_at,
        "count": len(entries),
        "entries": entries,
    }
    full_path = os.path.join(args.out_dir, "detail_seed.json")
    with open(full_path, "w", encoding="utf-8") as f:
        json.dump(envelope, f, ensure_ascii=False, separators=(",", ":"))
    full_bytes = os.path.getsize(full_path)
    sha = hashlib.sha256(open(full_path, "rb").read()).hexdigest()

    manifest = {
        "version": args.version,
        "generated_at": generated_at,
        "source": "bfzy1",
        "base_url": BASE,
        "count": len(entries),
        "requested": len(ids),
        "failed": len(failed),
        "detail_seed_bytes": full_bytes,
        "detail_seed_sha256": sha,
        "sample_count": min(args.sample_size, len(entries)),
        "duration_seconds": round(time.time() - t0, 1),
    }
    manifest_path = os.path.join(args.out_dir, "manifest.json")
    json.dump(manifest, open(manifest_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    sample = {
        "schema_version": 1,
        "source_id": "bfzy1",
        "generated_at": generated_at,
        "count": min(args.sample_size, len(entries)),
        "note": "仓库内仅保留前 20 条样例；完整包见 release/detail-seed-%s.zip" % args.version,
        "entries": entries[: args.sample_size],
    }
    with open(args.sample, "w", encoding="utf-8") as f:
        json.dump(sample, f, ensure_ascii=False, indent=1)
    sample_bytes = os.path.getsize(args.sample)

    zip_path = os.path.join(args.out_dir, f"detail-seed-{args.version}.zip")
    if not args.no_zip:
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            z.write(full_path, "detail_seed.json")
            z.write(manifest_path, "manifest.json")
            z.writestr("README.txt",
                       "Xingmu TV detail seed %s\nsource: bfzy1 (macapi1 MacCMS)\n"
                       "generated_at: %s\ncount: %d\n"
                       "usage: read detail_seed.json, index entries by vodId; "
                       "serve entries[*].detailJson as the detail payload.\n" % (
                           args.version, generated_at, len(entries)))
        zip_bytes = os.path.getsize(zip_path)
    else:
        zip_bytes = 0

    print(json.dumps({
        "count": len(entries), "failed": len(failed),
        "detail_seed_bytes": full_bytes, "detail_seed_mb": round(full_bytes / 1024 / 1024, 2),
        "sample_bytes": sample_bytes, "zip_bytes": zip_bytes,
        "zip": zip_path if not args.no_zip else None,
        "avg_bytes_per_entry": round(full_bytes / max(1, len(entries))),
        "duration_seconds": round(time.time() - t0, 1),
    }, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
