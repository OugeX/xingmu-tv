#!/usr/bin/env python
"""Build candidate list from iptv-org API + zho.m3u playlist."""
import json, os, re

BASE = os.path.dirname(os.path.abspath(__file__))

channels = {c["id"]: c for c in json.load(open(os.path.join(BASE, "channels.json"), encoding="utf-8"))}
streams = json.load(open(os.path.join(BASE, "streams.json"), encoding="utf-8"))

cands, seen_urls = [], set()

def add(cid, name, url, quality="", referrer=None, ua=None, source="", country="", group=""):
    if not url or url in seen_urls:
        return
    if any(d in url for d in ("youtube.com", "youtu.be", "twitch.tv", "vk.com/video")):
        return
    seen_urls.add(url)
    cands.append({
        "channel": cid or "", "name": name or "", "url": url, "declared_quality": quality or "",
        "referrer": referrer or None, "user_agent": ua or None, "source": source,
        "country": country, "group_hint": group,
    })

# 1) iptv-org API: all streams whose channel is CN
cn_ids = {cid for cid, c in channels.items() if c.get("country") == "CN"}
for s in streams:
    cid = s.get("channel")
    if cid in cn_ids:
        c = channels[cid]
        add(cid, c["name"], s.get("url"), s.get("quality", ""), s.get("referrer"),
            s.get("user_agent"), "iptv-org/api", "CN", ",".join(c.get("categories") or []))

# 2) zho.m3u playlist entries not already present
def parse_m3u(path):
    txt = open(path, encoding="utf-8-sig", errors="replace").read()
    entries, pending = [], None
    for raw in txt.splitlines():
        ln = raw.strip()
        if ln.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([A-Za-z0-9-]+)="([^"]*)"', ln))
            name = ln.split(",", 1)[1].strip() if "," in ln else ""
            pending = (attrs, name)
        elif ln and not ln.startswith("#") and pending:
            attrs, name = pending
            entries.append((attrs, name, ln))
            pending = None
    return entries

for attrs, name, url in parse_m3u(os.path.join(BASE, "zho.m3u")):
    tid = attrs.get("tvg-id", "")
    suffix = tid.split("@")[0].rsplit(".", 1)[-1].lower() if "." in tid else ""
    add(tid, name, url, "", None, None, "iptv-org/zho.m3u", suffix, attrs.get("group-title", ""))

with open(os.path.join(BASE, "candidates.json"), "w", encoding="utf-8") as f:
    json.dump(cands, f, ensure_ascii=False, indent=1)
print("candidates:", len(cands))
from collections import Counter
print(Counter(c["source"] for c in cands))
print(Counter(c["country"] for c in cands).most_common(10))
