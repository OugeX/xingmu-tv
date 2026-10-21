#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从已探测可用的 MacCMS 源生成内置源包 assets/sources.builtin.json（tvapp.source.v1.1 bundle），
并把「海外剧场」（韩剧/美剧/日剧/海外剧/港台剧/泰剧）子分类 id 写进 assets/overseas_map.json。
用法: python tools/gen_builtin.py
"""
import json, sys, time
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")

SOURCES = [
    # (id, 名称, baseUrl, 优先级, fallback)
    ("bfzy1", "暴风资源(主)", "https://macapi1.com/maccms/json/baofeng/", 10,
     ["https://bfzyapi.com/api.php/provide/vod/"]),
    ("ffzy", "非凡资源", "https://api.ffzyapi.com/api.php/provide/vod/", 20,
     ["https://cj.ffzyapi.com/api.php/provide/vod/"]),
    ("dytt", "电影天堂资源", "https://caiji.dyttzyapi.com/api.php/provide/vod/", 30, []),
    ("lziapi", "量子资源", "https://cj.lziapi.com/api.php/provide/vod/", 40, []),
    ("apibdzy", "百度资源", "https://api.apibdzy.com/api.php/provide/vod/", 50, []),
    ("guangsu", "光速资源", "https://api.guangsuapi.com/api.php/provide/vod/", 60, []),
    ("zuidapi", "最大资源", "https://api.zuidapi.com/api.php/provide/vod/", 80, []),
]

CAT_TOP = {
    "MOVIE": ["电影", "电影片"],
    "TV": ["电视剧", "连续剧", "剧集"],
    "ANIME": ["动漫", "动漫片"],
    "VARIETY": ["综艺", "综艺片"],
}
OVERSEAS_KEYS = ["韩国剧", "韩剧", "欧美剧", "美剧", "美国剧", "日本剧", "日剧",
                 "海外剧", "泰国剧", "泰剧", "香港剧", "港澳剧", "台湾剧", "港台剧"]


def fetch_json(url, timeout=15):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Referer": "https://www.google.com/",
        "Accept": "application/json,text/plain,*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def class_tree(base):
    url = base if base.endswith("json/baofeng/") else base
    data = fetch_json(url + ("?ac=list" if "?" not in url else "&ac=list"))
    return data.get("class") or []


def build():
    bundle_sources, overseas_map, report = [], {}, []
    for sid, name, base, prio, fb in SOURCES:
        rec = {"id": sid, "name": name, "baseUrl": base}
        try:
            cls = class_tree(base)
        except Exception as e:
            rec["error"] = f"{type(e).__name__}: {e}"
            report.append(rec)
            continue
        by_pid = {}
        for c in cls:
            by_pid.setdefault(c.get("type_pid"), []).append(c)

        cats, cats_names = {}, {}
        for key, names in CAT_TOP.items():
            # 一级分类：名字命中且 pid==0
            hit = [c for c in cls if c.get("type_pid") == 0 and c.get("type_name") in names]
            ids = [c["type_id"] for c in hit]
            # 没有一级命中时，用子分类（其父名命中）
            if not ids:
                parents = [c for c in cls if c.get("type_pid") == 0 and
                           any(k in c.get("type_name", "") for k in names)]
                ids = [c["type_id"] for c in parents]
            if ids:
                cats[key] = ids[:10]
                cats_names[key] = [c.get("type_name") for c in cls if c.get("type_id") in ids]

        overseas = [c for c in cls if c.get("type_pid") != 0 and
                    any(k in c.get("type_name", "") for k in OVERSEAS_KEYS)]
        overseas_map[sid] = {
            "typeIds": [c["type_id"] for c in overseas],
            "names": [c.get("type_name") for c in overseas],
        }

        src = {
            "schema": "tvapp.source.v1.1",
            "kind": "maccms",
            "id": sid,
            "name": name,
            "enabled": True,
            "priority": prio,
            "baseUrl": base,
            "flavor": "macjson",
            "pageSize": 20,
            "timeoutMs": 6000,
            "maxQuality": "1080p",
            "preferredCodec": "h264",
            "headers": {"User-Agent": UA, "Referer": "https://bfzy1.tv/"},
            "adBlock": {"enabled": True, "domains": [], "urlKeywords": ["/ad/", "/ads/", "/adv/", "/gg/"]},
        }
        if fb:
            src["fallbackBaseUrls"] = fb
        if cats:
            src["categories"] = cats
        bundle_sources.append(src)
        rec.update({"classCount": len(cls), "categories": cats_names,
                    "overseas": overseas_map[sid]["names"]})
        report.append(rec)

    bundle = {
        "schema": "tvapp.source.v1.1",
        "kind": "bundle",
        "id": "builtin-v1",
        "name": "内置源合集 v1",
        "version": 1,
        "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "refreshIntervalHour": 24,
        "merge": "byId",
        "priority": 0,
        "note": "首次启动自动装载；全部经 tools/probe_sources.py 实测可用（2026-10-21）",
        "sources": bundle_sources,
    }
    json.dump(bundle, open("assets/sources.builtin.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    json.dump(overseas_map, open("assets/overseas_map.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    json.dump(report, open("assets/builtin_report.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    print(f"{'id':<10}{'cls':>4}  海外子分类")
    for r in report:
        print(f"{r['id']:<10}{r.get('classCount','-'):>4}  {', '.join(r.get('overseas') or [])}"
              f"  {r.get('error','')}"[:150])
    print(f"\n内置源 {len(bundle_sources)} 个 -> assets/sources.builtin.json")
    return 0


if __name__ == "__main__":
    sys.exit(build())
