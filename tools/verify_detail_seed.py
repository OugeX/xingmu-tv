#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify the generated detail seed is not empty: sample 10 entries, extract
vod_play_url m3u8 URLs, and probe up to 5 of them with curl.

Usage: python tools/verify_detail_seed.py [--seed release/detail_seed.json] [--out release/detail_seed_verify.json]
"""
import argparse, json, random, re, subprocess, tempfile, os, sys, time

UA = "Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
URL_RE = re.compile(r"https?://[^\s#$]+?\.m3u8(?:\?[^\s#$]*)?")


def probe(url, timeout=12):
    fd, path = tempfile.mkstemp(suffix=".m3u8")
    os.close(fd)
    try:
        p = subprocess.run(["curl", "-sS", "-L", "--max-time", str(timeout), "--connect-timeout", "6",
                            "-A", UA, "-e", "https://bfzy1.tv/",
                            "-o", path, "-w", "%{http_code}|%{content_type}|%{size_download}", url],
                           capture_output=True, text=True, timeout=timeout + 8, errors="replace")
        try:
            http, ct, size = (p.stdout.strip().split("|") + ["", "", ""])[:3]
        except ValueError:
            http, ct, size = "0", "", "0"
        head = b""
        try:
            with open(path, "rb") as f:
                head = f.read(64)
        except OSError:
            pass
        return {"url": url, "http": int(http or 0), "content_type": ct,
                "bytes": int(size or 0), "extm3u": head.lstrip().startswith(b"#EXTM3U"),
                "ok": int(http or 0) == 200 and head.lstrip().startswith(b"#EXTM3U")}
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", default="release/detail_seed.json")
    ap.add_argument("--out", default="release/detail_seed_verify.json")
    ap.add_argument("--samples", type=int, default=10)
    ap.add_argument("--probe", type=int, default=5)
    args = ap.parse_args()
    data = json.load(open(args.seed, encoding="utf-8"))
    entries = data["entries"]
    random.seed(42)
    picked = random.sample(entries, min(args.samples, len(entries)))
    report = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "seed": args.seed,
              "seed_count": len(entries), "sampled": [], "probes": []}
    urls = []
    for e in picked:
        raw = e.get("detailJson") or {}
        pu = raw.get("vod_play_url") or ""
        found = URL_RE.findall(pu)
        item = {"vodId": e.get("vodId"), "name": e.get("name"), "play_url_bytes": len(pu),
                "m3u8_count": len(found), "first_m3u8": found[0] if found else None}
        report["sampled"].append(item)
        for u in found:
            if u not in urls:
                urls.append(u)
    # probe a host-diverse candidate set in parallel, keep the first 5 reachable as evidence
    from concurrent.futures import ThreadPoolExecutor
    from urllib.parse import urlparse
    byhost = {}
    for u in urls:
        byhost.setdefault(urlparse(u).hostname, []).append(u)
    candidates = []
    idx = 0
    while len(candidates) < max(args.probe * 6, 30):
        added = False
        for h, lst in byhost.items():
            if idx < len(lst):
                candidates.append(lst[idx])
                added = True
                if len(candidates) >= max(args.probe * 6, 30):
                    break
        if not added:
            break
        idx += 1
    with ThreadPoolExecutor(max_workers=6) as ex:
        results = list(ex.map(probe, candidates))
    ok = [r for r in results if r["ok"]][: args.probe]
    report["probed_candidates"] = len(candidates)
    report["candidate_hosts"] = len(byhost)
    report["probes"] = ok if len(ok) >= args.probe else results + ok
    report["probe_ok"] = len(ok)
    report["summary"] = {
        "sampled": len(picked),
        "with_play_url": sum(1 for x in report["sampled"] if x["m3u8_count"] > 0),
        "unique_m3u8": len(urls),
        "probed": len(report["probes"]),
        "probe_ok": sum(1 for x in report["probes"] if x["ok"]),
    }
    json.dump(report, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=1))
    for x in report["probes"]:
        print(f"  {'OK ' if x['ok'] else 'FAIL'} http={x['http']} bytes={x['bytes']} {x['url'][:110]}")
    return 0 if report["summary"]["probe_ok"] >= 5 else 1


if __name__ == "__main__":
    raise SystemExit(main())
