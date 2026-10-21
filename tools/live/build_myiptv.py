#!/usr/bin/env python
"""Parse myIPTV m3u into candidates.json format (name/url/UA/referer/group/quality)."""
import json, os, re

BASE = os.path.dirname(os.path.abspath(__file__))

def parse(path):
    txt = open(path, encoding="utf-8-sig", errors="replace").read()
    out, pending = [], None
    for raw in txt.splitlines():
        ln = raw.strip()
        if not ln or ln.startswith("#") and not ln.startswith("#EXTINF"):
            continue
        if ln.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([A-Za-z0-9-]+)="([^"]*)"', ln))
            name = ln.split(",", 1)[1].strip() if "," in ln else ""
            pending = (attrs, name)
        elif pending:
            attrs, name = pending
            pending = None
            out.append((attrs, name, ln))
    return out

def infer_quality(name, group=""):
    s = (name or "") + " " + (group or "")
    if "4K" in s or "2160" in s:
        return "2160p"
    if "1080" in s or "高清" in s or "HD" in s:
        return "1080p"
    if "720" in s:
        return "720p"
    return ""

entries = parse(os.path.join(BASE, "myiptv.m3u"))
cands, seen = [], set()
for attrs, name, url in entries:
    if url in seen:
        continue
    seen.add(url)
    cands.append({
        "channel": "", "name": name, "url": url,
        "declared_quality": infer_quality(name, attrs.get("group-title", "")),
        "referrer": attrs.get("http-referer") or None,
        "user_agent": attrs.get("http-user-agent") or None,
        "source": "suxuang/myIPTV", "country": "CN",
        "group_hint": attrs.get("group-title", ""), "tvg_name": attrs.get("tvg-name", ""),
    })
with open(os.path.join(BASE, "candidates_myiptv.json"), "w", encoding="utf-8") as f:
    json.dump(cands, f, ensure_ascii=False, indent=1)
print("myiptv candidates:", len(cands))
from collections import Counter
print(Counter(c["group_hint"] for c in cands).most_common(20))
