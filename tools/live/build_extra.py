#!/usr/bin/env python
"""Parse extra public playlists into candidates; dedupe against candidates_all.json."""
import json, os, re

BASE = os.path.dirname(os.path.abspath(__file__))
FILES = [
    ("vbskycn4.m3u", "vbskycn/iptv"),
    ("wwb521.m3u", "wwb521/live"),
    ("myiptv6.m3u", "suxuang/myIPTV-ipv6"),
    ("fm6.m3u", "fanmingming/live-ipv6"),
    ("iptvorg_cn_streams.m3u", "iptv-org/streams/cn"),
]

def parse(path):
    txt = open(path, encoding="utf-8-sig", errors="replace").read()
    out, pending = [], None
    for raw in txt.splitlines():
        ln = raw.strip()
        if not ln or (ln.startswith("#") and not ln.startswith("#EXTINF")):
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

def infer_quality(name, group):
    s = f"{name} {group}"
    for q, pat in (("2160p", r"4K|2160"), ("1080p", r"1080|高清"), ("720p", r"720")):
        if re.search(pat, s, re.I):
            return q
    return ""

existing = json.load(open(os.path.join(BASE, "candidates_all.json"), encoding="utf-8"))
seen = {c["url"] for c in existing}
cands = []
for fn, src in FILES:
    p = os.path.join(BASE, fn)
    if not os.path.exists(p):
        continue
    for attrs, name, url in parse(p):
        if url in seen:
            continue
        seen.add(url)
        cands.append({
            "channel": "", "name": name, "url": url,
            "declared_quality": infer_quality(name, attrs.get("group-title", "")),
            "referrer": attrs.get("http-referer") or None,
            "user_agent": attrs.get("http-user-agent") or None,
            "source": src, "country": "CN",
            "group_hint": attrs.get("group-title", ""), "tvg_name": attrs.get("tvg-name", ""),
        })
with open(os.path.join(BASE, "candidates_extra.json"), "w", encoding="utf-8") as f:
    json.dump(cands, f, ensure_ascii=False, indent=1)
print("extra candidates:", len(cands))
from collections import Counter
print(Counter(c["source"] for c in cands))
print("ipv6:", sum(1 for c in cands if c["url"].startswith("http://[") or c["url"].startswith("https://[")))
