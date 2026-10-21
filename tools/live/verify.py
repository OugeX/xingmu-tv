#!/usr/bin/env python
"""Two-stage IPTV verification.

Stage A: fetch each playlist, require #EXTM3U.
Stage B: for master playlists fetch variants; fetch first media segment and
         require real bytes (200/206, >0 bytes) that look like media.

Segment probe deliberately avoids --max-filesize (curl discards partial bodies
with exit 63); it uses an HTTP Range request instead and tolerates servers that
ignore Range (bounded by --max-time).
"""
import json, os, re, subprocess, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urljoin

BASE = os.path.dirname(os.path.abspath(__file__))
UA = ("Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")

STAGE_A_TIMEOUT = 8
STAGE_B_TIMEOUT = 8
SEG_TIMEOUT = 8
SEG_BYTES = "0-262143"


def curl(url, referer=None, ua=None, max_time=8, max_filesize=None, rng=None, save_body=True):
    fd, path = tempfile.mkstemp(dir=BASE, suffix=".body")
    os.close(fd)
    fmt = "%{http_code}|%{content_type}|%{time_starttransfer}|%{time_total}|%{size_download}|%{url_effective}"
    cmd = ["curl", "-sS", "-L", "--max-time", str(max_time), "--connect-timeout", "5",
           "-A", ua or UA, "-o", path, "-w", fmt]
    if referer:
        cmd += ["-e", referer]
    if max_filesize:
        cmd += ["--max-filesize", str(max_filesize)]
    if rng:
        cmd += ["-r", rng]
    cmd.append(url)
    body = b""
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=max_time + 6,
                           errors="replace")
        meta = (p.stdout or "").strip().split("|")
        try:
            with open(path, "rb") as f:
                body = f.read(524288)
        except OSError:
            pass
        if len(meta) >= 6:
            rec = {"http": int(meta[0] or 0), "ct": meta[1], "ttfb": round(float(meta[2] or 0), 3),
                   "total": round(float(meta[3] or 0), 3), "size": int(meta[4] or 0),
                   "effective": meta[5], "curl_exit": p.returncode,
                   "err": (p.stderr or "").strip()[:240]}
        else:
            rec = {"http": 0, "ct": "", "ttfb": 0, "total": 0, "size": 0, "effective": url,
                   "curl_exit": p.returncode, "err": ((p.stderr or "") + (p.stdout or "")).strip()[:240]}
    except subprocess.TimeoutExpired:
        rec = {"http": 0, "ct": "", "ttfb": 0, "total": max_time, "size": 0,
               "effective": url, "curl_exit": -1, "err": "timeout"}
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    return rec, body


def parse_playlist(body):
    text = body.decode("utf-8", "replace").lstrip("\ufeff \r\n\t")
    info = {"extm3u": text.startswith("#EXTM3U"), "master": False, "variants": [],
            "segments": 0, "target_duration": None, "has_key": False, "first_seg": None,
            "head": text[:100]}
    if not info["extm3u"]:
        return info
    lines = [l.strip() for l in text.splitlines()]
    pending = None
    for i, ln in enumerate(lines):
        if ln.startswith("#EXT-X-STREAM-INF"):
            pending = ln
        elif ln and not ln.startswith("#") and pending:
            attrs = dict(re.findall(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)', pending))
            res = attrs.get("RESOLUTION", "").strip('"')
            bw = attrs.get("BANDWIDTH", "").strip('"')
            info["variants"].append({
                "bandwidth": int(bw) if bw.isdigit() else None,
                "resolution": res, "url": ln,
                "codecs": attrs.get("CODECS", "").strip('"')})
            info["master"] = True
            pending = None
        elif ln.startswith("#EXT-X-TARGETDURATION"):
            try:
                info["target_duration"] = float(ln.split(":", 1)[1])
            except ValueError:
                pass
        elif ln.startswith("#EXT-X-KEY"):
            info["has_key"] = True
        elif ln.startswith("#EXTINF"):
            info["segments"] += 1
            if info["first_seg"] is None:
                for nx in lines[i + 1:]:
                    if nx and not nx.startswith("#"):
                        info["first_seg"] = nx
                        break
    if info["master"] and not info["variants"]:
        info["master"] = False
    return info


def is_media_head(b):
    if not b:
        return False
    if b[0] == 0x47:
        return True
    if b[:3] == b"ID3" or b[:4] in (b"ftyp", b"moov", b"styp", b"moof", b"mdat"):
        return True
    if b[:7] == b"#EXTM3U":
        return True
    if b[:1] in (b"\x00",) and len(b) > 188:
        return False
    return b[:4] in (b"\x00\x00\x00\x18", b"\x00\x00\x00\x20")


def seg_probe(seg_url, referer, ua):
    rec, body = curl(seg_url, referer=referer, ua=ua, max_time=SEG_TIMEOUT, rng=SEG_BYTES)
    ok = rec["http"] in (200, 206) and len(body) > 0 and is_media_head(body)
    return {"http": rec["http"], "ct": rec["ct"], "bytes": len(body),
            "ttfb": rec["ttfb"], "total": rec["total"], "ok": ok, "magic": body[:4].hex()}


def stage_a(c):
    rec, body = curl(c["url"], referer=c.get("referrer"), ua=c.get("user_agent"),
                     max_time=STAGE_A_TIMEOUT, max_filesize=524288)
    pl = parse_playlist(body)
    return {**c, "a": {k: rec[k] for k in ("http", "ct", "ttfb", "total", "size", "effective", "curl_exit", "err")},
            "extm3u": pl["extm3u"], "kind": "master" if pl["master"] else ("media" if pl["segments"] else "other"),
            "playlist": {k: pl[k] for k in ("segments", "target_duration", "has_key", "variants", "first_seg", "head")}}


def stage_b(r):
    if not r["extm3u"]:
        return r
    pl = r["playlist"]
    r["playable"] = False
    r["chosen"] = None
    r["seg"] = None
    r["variants_checked"] = []
    if r["kind"] == "master":
        variants = sorted(pl["variants"], key=lambda v: (
            int(v["resolution"].split("x")[1]) if "x" in (v["resolution"] or "") else 0,
            v.get("bandwidth") or 0), reverse=True)
        for v in variants[:3]:
            vurl = urljoin(r["a"]["effective"] or r["url"], v["url"])
            vrec, vbody = curl(vurl, referer=r.get("referrer"), ua=r.get("user_agent"),
                               max_time=STAGE_B_TIMEOUT, max_filesize=524288)
            vpl = parse_playlist(vbody)
            item = {"resolution": v["resolution"], "bandwidth": v["bandwidth"], "url": vurl,
                    "http": vrec["http"], "ct": vrec["ct"], "extm3u": vpl["extm3u"],
                    "segments": vpl["segments"], "seg": None}
            if vpl["extm3u"] and vpl["segments"] and vpl["first_seg"]:
                seg = urljoin(vrec["effective"] or vurl, vpl["first_seg"])
                item["seg"] = seg_probe(seg, r.get("referrer"), r.get("user_agent"))
                if item["seg"]["ok"]:
                    r["playable"] = True
                    r["chosen"] = {"url": vurl, "resolution": v["resolution"],
                                   "bandwidth": v["bandwidth"], "segments": vpl["segments"]}
                    r["seg"] = item["seg"]
                    r["variants_checked"].append(item)
                    break
            r["variants_checked"].append(item)
    elif r["kind"] == "media":
        if pl["first_seg"]:
            seg = urljoin(r["a"]["effective"] or r["url"], pl["first_seg"])
            r["seg"] = seg_probe(seg, r.get("referrer"), r.get("user_agent"))
            if r["seg"]["ok"]:
                r["playable"] = True
                r["chosen"] = {"url": r["url"], "resolution": "", "bandwidth": None,
                               "segments": pl["segments"]}
    return r


def main():
    inp = sys.argv[1] if len(sys.argv) > 1 else "candidates_all.json"
    out = sys.argv[2] if len(sys.argv) > 2 else "verify_results.json"
    cands = json.load(open(os.path.join(BASE, inp), encoding="utf-8"))
    print(f"stage A: {len(cands)} candidates", flush=True)
    res, done = [], 0
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=40) as ex:
        futs = [ex.submit(stage_a, c) for c in cands]
        for f in as_completed(futs):
            try:
                res.append(f.result())
            except Exception as e:  # noqa: BLE001
                res.append({"url": "?", "error": repr(e), "extm3u": False, "kind": "error"})
            done += 1
            if done % 100 == 0:
                print(f"  A {done}/{len(cands)} ({time.time()-t0:.0f}s)", flush=True)
    alive = [r for r in res if r.get("extm3u")]
    print(f"stage A done: {len(alive)}/{len(res)} returned #EXTM3U; stage B on those", flush=True)
    out_res, done = [], 0
    with ThreadPoolExecutor(max_workers=16) as ex:
        futs = [ex.submit(stage_b, r) for r in alive]
        for f in as_completed(futs):
            try:
                out_res.append(f.result())
            except Exception as e:  # noqa: BLE001
                out_res.append({"url": "?", "error": repr(e)})
            done += 1
            if done % 25 == 0:
                print(f"  B {done}/{len(alive)}", flush=True)
    finals = [r for r in res if not r.get("extm3u")] + out_res
    finals.sort(key=lambda r: (not r.get("playable"), r.get("name") or "", r.get("url") or ""))
    json.dump(finals, open(os.path.join(BASE, out), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    playable = [r for r in finals if r.get("playable")]
    print(f"done in {time.time()-t0:.0f}s: {len(playable)} playable / {len(cands)} probed")
    print("playable channels:", len({r.get('name') for r in playable}))


if __name__ == "__main__":
    main()
