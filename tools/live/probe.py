#!/usr/bin/env python
"""Probe IPTV candidates: fetch playlist, confirm #EXTM3U, inspect variants/segments.

Uses curl (trusted media UA) with per-request timeouts. Writes JSON results.
"""
import json, os, re, subprocess, sys, time, tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urljoin

BASE = os.path.dirname(os.path.abspath(__file__))
UA = "Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
MAXBODY = 512 * 1024
TIMEOUT = 12

def curl(url, referer=None, ua=None, max_time=TIMEOUT, rng=None):
    fd, path = tempfile.mkstemp(dir=BASE, suffix=".body")
    os.close(fd)
    fmt = "%{http_code}|%{content_type}|%{time_starttransfer}|%{time_total}|%{size_download}|%{url_effective}|%{num_redirects}"
    cmd = ["curl", "-sS", "-L", "--max-time", str(max_time), "--connect-timeout", "6",
           "--max-filesize", str(MAXBODY), "-A", ua or UA,
           "-o", path, "-w", fmt]
    if referer:
        cmd += ["-e", referer]
    if rng:
        cmd += ["-r", rng]
    cmd.append(url)
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=max_time + 8,
                           errors="replace")
        meta = (p.stdout or "").strip().split("|")
        body = b""
        try:
            with open(path, "rb") as f:
                body = f.read(MAXBODY)
        except OSError:
            pass
        if len(meta) >= 6:
            rec = {
                "http": int(meta[0] or 0), "ct": meta[1], "ttfb": round(float(meta[2] or 0), 3),
                "total": round(float(meta[3] or 0), 3), "size": int(meta[4] or 0),
                "effective": meta[5], "redirects": int(meta[6] or 0) if len(meta) > 6 else 0,
                "curl_exit": p.returncode, "err": (p.stderr or "")[:300].strip(),
            }
        else:
            rec = {"http": 0, "ct": "", "ttfb": 0, "total": 0, "size": 0, "effective": url,
                   "curl_exit": p.returncode, "err": ((p.stderr or "") + (p.stdout or ""))[:300].strip()}
    except subprocess.TimeoutExpired:
        rec = {"http": 0, "ct": "", "ttfb": 0, "total": max_time, "size": 0, "effective": url,
               "curl_exit": -1, "err": "subprocess timeout"}
        body = b""
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    return rec, body

def parse_playlist(body):
    """Return dict: is_master, variants[{bandwidth,resolution,url,codecs}], media lines count, key info."""
    text = body.decode("utf-8", "replace").lstrip("\ufeff \r\n\t")
    info = {"extm3u": text.startswith("#EXTM3U"), "master": False, "variants": [],
            "segments": 0, "target_duration": None, "has_key": False,
            "first_seg": None}
    if not info["extm3u"]:
        info["head"] = text[:120]
        return info
    lines = [l.strip() for l in text.splitlines()]
    pending = None
    for ln in lines:
        if ln.startswith("#EXT-X-STREAM-INF"):
            pending = ln
            info["master"] = True
        elif ln and not ln.startswith("#") and pending:
            attrs = dict(re.findall(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)', pending))
            res = attrs.get("RESOLUTION", "").strip('"')
            bw = attrs.get("BANDWIDTH", "").strip('"')
            info["variants"].append({"bandwidth": int(bw) if bw.isdigit() else None,
                                     "resolution": res, "url": ln, "attrs": {k: v.strip('"') for k, v in attrs.items()}})
            pending = None
        elif ln.startswith("#EXT-X-TARGETDURATION"):
            try:
                info["target_duration"] = float(ln.split(":")[1])
            except ValueError:
                pass
        elif ln.startswith("#EXT-X-KEY"):
            info["has_key"] = True
        elif ln.startswith("#EXTINF"):
            info["segments"] += 1
            if info["first_seg"] is None:
                # next non-comment line is the segment
                idx = lines.index(ln)
                for nx in lines[idx + 1:]:
                    if nx and not nx.startswith("#"):
                        info["first_seg"] = nx
                        break
    if info["master"] and not info["variants"]:
        info["master"] = False
    return info

def probe_playlist(url, referer=None, ua=None):
    rec, body = curl(url, referer=referer, ua=ua)
    pl = parse_playlist(body)
    rec["playlist"] = pl
    rec["url"] = url
    return rec, pl

def probe_one(cand):
    """cand: dict(channel, name, url, quality, referrer, user_agent, group)"""
    t0 = time.time()
    rec, pl = probe_playlist(cand["url"], cand.get("referrer"), cand.get("user_agent"))
    out = dict(cand)
    out["check"] = {k: rec[k] for k in ("http", "ct", "ttfb", "total", "size", "effective",
                                        "redirects", "curl_exit", "err")}
    out["extm3u"] = pl.get("extm3u", False)
    out["kind"] = "master" if pl.get("master") else ("media" if pl.get("segments") else "other")
    out["variants"] = []
    out["media"] = None
    if pl.get("master"):
        for v in pl["variants"][:3]:
            vurl = urljoin(rec["effective"] or cand["url"], v["url"])
            vrec, vpl = probe_playlist(vurl, cand.get("referrer"), cand.get("user_agent"))
            item = {"resolution": v["resolution"], "bandwidth": v["bandwidth"],
                    "url": vurl, "http": vrec["http"], "ct": vrec["ct"],
                    "ttfb": vrec["ttfb"], "extm3u": vpl.get("extm3u", False),
                    "segments": vpl.get("segments"), "first_seg": None, "seg_check": None}
            if vpl.get("segments") and vpl.get("first_seg"):
                seg = urljoin(vrec["effective"] or vurl, vpl["first_seg"])
                item["first_seg"] = seg
                srec, sbody = curl(seg, referer=cand.get("referrer"), max_time=8, rng="0-2047")
                item["seg_check"] = {"http": srec["http"], "ct": srec["ct"], "bytes": len(sbody),
                                     "ttfb": srec["ttfb"], "total": srec["total"]}
            out["variants"].append(item)
        # choose working variant with best resolution later
    elif pl.get("segments"):
        media = {"http": rec["http"], "ct": rec["ct"], "extm3u": True,
                 "target_duration": pl.get("target_duration"), "segments": pl["segments"],
                 "has_key": pl["has_key"], "first_seg": None, "seg_check": None}
        if pl.get("first_seg"):
            seg = urljoin(rec["effective"] or cand["url"], pl["first_seg"])
            media["first_seg"] = seg
            srec, sbody = curl(seg, referer=cand.get("referrer"), max_time=8, rng="0-2047")
            media["seg_check"] = {"http": srec["http"], "ct": srec["ct"], "bytes": len(sbody),
                                  "ttfb": srec["ttfb"], "total": srec["total"]}
        out["media"] = media
    out["elapsed"] = round(time.time() - t0, 2)
    return out

def main():
    cands = json.load(open(os.path.join(BASE, "candidates.json"), encoding="utf-8"))
    if len(sys.argv) > 1:
        cands = cands[:int(sys.argv[1])]
    print(f"probing {len(cands)} candidates", flush=True)
    results = []
    done = 0
    with ThreadPoolExecutor(max_workers=24) as ex:
        futs = {ex.submit(probe_one, c): c for c in cands}
        for fut in as_completed(futs):
            try:
                results.append(fut.result())
            except Exception as e:  # noqa: BLE001
                c = futs[fut]
                results.append(dict(c, error=repr(e)))
            done += 1
            if done % 25 == 0:
                print(f"  {done}/{len(cands)}", flush=True)
    results.sort(key=lambda r: (r.get("channel") or "", r.get("url") or ""))
    with open(os.path.join(BASE, "probe_results.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    ok = sum(1 for r in results if r.get("extm3u"))
    print(f"done: {len(results)} probed, {ok} returned #EXTM3U")

if __name__ == "__main__":
    main()
