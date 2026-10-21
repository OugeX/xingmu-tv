#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量探测公开 MacCMS 资源站可用性 + 是否有韩剧/美剧等海外分类。
用法: python tools/probe_sources.py [--play] [--out assets/probe_report.json]
"""
import json, sys, time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

CANDIDATES = [
    "https://caiji.dyttzyapi.com/api.php/provide/vod/",
    "https://cj.lziapi.com/api.php/provide/vod/",
    "https://json.heimuer.xyz/api.php/provide/vod/",
    "https://api.ffzyapi.com/api.php/provide/vod/",
    "https://cj.ffzyapi.com/api.php/provide/vod/",
    "https://api.wujinapi.me/api.php/provide/vod/",
    "https://collect.wolongzyw.com/api.php/provide/vod/",
    "https://api.yzzy-api.com/inc/apijson.php",
    "https://api.ukuapi.com/api.php/provide/vod/",
    "https://api.guangsuapi.com/api.php/provide/vod/",
    "https://api.maotaizy.cc/api.php/provide/vod/",
    "https://api.tiankongapi.com/api.php/provide/vod/",
    "https://api.douubi.com/api.php/provide/vod/",
    "https://api.suyanw.cn/api.php/provide/vod/",
    "https://api.zuidapi.com/api.php/provide/vod/",
    "https://api.hongniuzy2.com/api.php/provide/vod/",
    "https://p2100.net/api.php/provide/vod/",
    "https://api.1080zyku.com/inc/api_mac10.php",
    "https://zy.xiaomaomi.cc/api.php/provide/vod/",
    "https://api.apibdzy.com/api.php/provide/vod/",
    "https://api.phpzy.com/api.php/provide/vod/",
    "https://api.dbzy.me/api.php/provide/vod/",
    "https://api.qiqidys.com/api.php/provide/vod/",
    "https://api.langhuaapi.com/api.php/provide/vod/",
    "https://api.xinlangapi.com/api.php/provide/vod/",
    "https://cj.ukuapi.com/api.php/provide/vod/",
    "https://api.aafvod.com/api.php/provide/vod/",
    "https://api.kuaikan-api.com/api.php/provide/vod/",
    "https://api.mitangzy.com/api.php/provide/vod/",
    "https://api.huyaapi.com/api.php/provide/vod/",
    "https://api.gaoqingzy.com/api.php/provide/vod/",
    "https://bfzyapi.com/api.php/provide/vod/",
]

OVERSEAS_KEYS = ["韩国剧", "美剧", "欧美剧", "日韩剧", "海外剧", "日本剧", "港台剧", "泰国剧", "韩剧", "美剧专区"]


def fetch(url, timeout=12):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Referer": "https://www.google.com/",
        "Accept": "application/json,text/plain,*/*",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


def probe(base):
    rec = {"baseUrl": base, "ok": False}
    t0 = time.time()
    try:
        st, body = fetch(base + "?ac=list")
        rec["http"] = st
        rec["bytes"] = len(body)
        data = json.loads(body.decode("utf-8", "replace"))
        rec["code"] = data.get("code")
        rec["total"] = data.get("total")
        rec["pagecount"] = data.get("pagecount")
        classes = data.get("class") or []
        rec["classCount"] = len(classes)
        names = [c.get("type_name", "") for c in classes]
        rec["categories"] = names
        rec["overseas"] = [n for n in names if any(k in n for k in OVERSEAS_KEYS)]
        first = (data.get("list") or [{}])[0]
        rec["sample"] = first.get("vod_name")
        rec["ok"] = int(data.get("code") or 0) == 1 and rec["total"]
    except Exception as e:
        rec["err"] = f"{type(e).__name__}: {e}"
    rec["ms"] = int((time.time() - t0) * 1000)
    return rec


def probe_play(rec):
    """对可用源再取一条详情，确认播放串存在且 m3u8 可拉。"""
    try:
        st, body = fetch(rec["baseUrl"] + "?ac=detail&pg=1")
        data = json.loads(body.decode("utf-8", "replace"))
        item = (data.get("list") or [{}])[0]
        play_url = item.get("vod_play_url") or ""
        rec["playFrom"] = item.get("vod_play_from")
        pieces = [p for p in play_url.split("#") if "$" in p]
        if not pieces:
            rec["playErr"] = "no play url"
            return rec
        url = pieces[0].split("$", 1)[1]
        rec["playSample"] = url[:120]
        if url.startswith("http"):
            try:
                st2, b2 = fetch(url, timeout=12)
                rec["playHttp"] = st2
                rec["playBytes"] = len(b2)
                rec["playOk"] = st2 == 200
            except Exception as e:
                rec["playErr"] = f"{type(e).__name__}: {e}"
    except Exception as e:
        rec["playErr"] = f"{type(e).__name__}: {e}"
    return rec


def main():
    with_play = "--play" in sys.argv
    out = "assets/probe_report.json"
    if "--out" in sys.argv:
        out = sys.argv[sys.argv.index("--out") + 1]
    with ThreadPoolExecutor(max_workers=10) as ex:
        recs = list(ex.map(probe, CANDIDATES))
    ok = [r for r in recs if r.get("ok")]
    if with_play:
        with ThreadPoolExecutor(max_workers=6) as ex:
            ok = list(ex.map(probe_play, ok))
        recs = ok + [r for r in recs if not r.get("ok")]
    recs.sort(key=lambda r: (not r.get("ok"), -int(bool(r.get("overseas"))), r.get("ms", 9999)))
    json.dump(recs, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"{'OK':<3}{'ms':>6}  {'tot':>7} {'cls':>4} 源 / 海外分类数")
    for r in recs:
        flag = "Y" if r.get("ok") else "N"
        print(f"{flag:<3}{r.get('ms',0):>6}  {str(r.get('total','-')):>7} {str(r.get('classCount','-')):>4} "
              f"{r['baseUrl']}  海外={len(r.get('overseas') or [])} {r.get('err','') or r.get('playErr','')}"[:160])
    print(f"\n可用 {len(ok)}/{len(CANDIDATES)}，报告写入 {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
