#!/usr/bin/env python
"""Build assets/live_seed.json from final_verify.json.

Cleans playlist-line junk from URLs, drops mislabeled/out-of-scope entries,
canonicalises channels, ranks sources (stable > signed, quality, latency) and
emits the seed consumed by the Android TV client.
"""
import json, os, re, subprocess, sys, time
from collections import Counter, defaultdict
from urllib.parse import urlparse

import verify as V

BASE = os.path.dirname(os.path.abspath(__file__))
OUT_SEED = os.path.join(BASE, "live_seed.json")
OUT_STATS = os.path.join(BASE, "seed_stats.json")
NOW = time.strftime("%Y-%m-%dT%H:%M:%S%z")

CCTV_NAMES = {
    1: "CCTV-1 综合", 2: "CCTV-2 财经", 3: "CCTV-3 综艺", 4: "CCTV-4 中文国际",
    5: "CCTV-5 体育", 6: "CCTV-6 电影", 7: "CCTV-7 国防军事", 8: "CCTV-8 电视剧",
    9: "CCTV-9 纪录", 10: "CCTV-10 科教", 11: "CCTV-11 戏曲", 12: "CCTV-12 社会与法",
    13: "CCTV-13 新闻", 14: "CCTV-14 少儿", 15: "CCTV-15 音乐", 16: "CCTV-16 奥林匹克",
    17: "CCTV-17 农业农村",
}
SATELLITE_CN = {
    "anhui tv": "安徽卫视", "dragon tv": "东方卫视", "dragon tv international": "东方卫视国际",
    "guangdong satellite tv": "广东卫视", "hebei tv": "河北卫视", "heilongjiang tv": "黑龙江卫视",
    "henan tv": "河南卫视", "hubei tv": "湖北卫视", "hunan tv": "湖南卫视",
    "jiangsu satellite tv": "江苏卫视", "jiangxi tv": "江西卫视", "liaoning tv": "辽宁卫视",
    "nei monggol tv": "内蒙古卫视", "ningxia satellite channel": "宁夏卫视", "qinghai tv": "青海卫视",
    "shaanxi tv": "陕西卫视", "shandong tv": "山东卫视", "shenzhen satellite tv": "深圳卫视",
    "sichuan satellite tv": "四川卫视", "tianjin tv": "天津卫视", "yanbian satellite tv": "延边卫视",
    "yunnan satellite tv": "云南卫视", "zhejiang satellite tv": "浙江卫视",
    "beijing satellite tv": "北京卫视", "bingtuan satellite tv": "兵团卫视",
    "chongqing tv": "重庆卫视", "guizhou tv": "贵州卫视", "gansu tv": "甘肃卫视",
    "xinjiang tv": "新疆卫视", "xizang tv": "西藏卫视", "guangxi tv": "广西卫视",
    "shanxi tv": "山西卫视", "jilin tv": "吉林卫视", "fujian tv": "东南卫视",
}
LOCAL_CN = {
    "baicheng tv": ("白城电视台", "吉林"), "siping tv": ("四平电视台", "吉林"),
    "chifeng comprehensive news chanel": ("赤峰新闻综合", "内蒙古"),
    "nei monggol tv 2 mongolian culture channel": ("内蒙古蒙语文化频道", "内蒙古"),
    "chuxiong news channel": ("楚雄新闻综合", "云南"),
    "dongguan comprehensive news channel": ("东莞新闻综合", "广东"),
    "harbin comprehensive news channel": ("哈尔滨新闻综合", "黑龙江"),
    "harbin movie channel": ("哈尔滨影视", "黑龙江"),
    "lanzhou comprehensive news channel": ("兰州新闻综合", "甘肃"),
    "lanzhou culture & tourism channel": ("兰州文旅", "甘肃"),
    "jiangsu public & news channel": ("江苏公共·新闻", "江苏"),
    "hunan economy channel": ("湖南经视", "湖南"),
    "xinjiang tv 2": ("新疆电视台二套", "新疆"), "xinjiang tv 3": ("新疆电视台三套", "新疆"),
    "xinjiang tv 5": ("新疆电视台五套", "新疆"), "xinjiang tv 8": ("新疆电视台八套", "新疆"),
    "cnd film middle school channel": ("中国教育电视台三套", "全国"),
}
PROVINCES = ["北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏",
             "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南",
             "重庆", "四川", "贵州", "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆"]
CITY_PROVINCE = {
    # 浙江
    "杭州": "浙江", "宁波": "浙江", "温州": "浙江", "湖州": "浙江", "绍兴": "浙江", "金华": "浙江",
    "衢州": "浙江", "舟山": "浙江", "台州": "浙江", "丽水": "浙江", "萧山": "浙江", "余杭": "浙江",
    "余姚": "浙江", "慈溪": "浙江", "象山": "浙江", "云和": "浙江", "文成": "浙江", "青田": "浙江",
    "龙泉": "浙江", "新昌": "浙江", "嵊州": "浙江", "嵊泗": "浙江", "上虞": "浙江", "兰溪": "浙江",
    "平湖": "浙江", "庆元": "浙江", "洞头": "浙江", "缙云": "浙江", "开化": "浙江", "松阳": "浙江",
    "遂昌": "浙江", "武义": "浙江", "永嘉": "浙江", "衢江": "浙江", "普陀": "浙江", "之江": "浙江",
    # 河北
    "石家庄": "河北", "唐山": "河北", "保定": "河北", "邯郸": "河北", "邢台": "河北", "沧州": "河北",
    "廊坊": "河北", "衡水": "河北", "秦皇岛": "河北", "张家口": "河北", "承德": "河北", "滦平": "河北",
    "昌黎": "河北", "任丘": "河北", "平泉": "河北", "兴隆": "河北",
    # 辽宁
    "沈阳": "辽宁", "大连": "辽宁", "鞍山": "辽宁", "抚顺": "辽宁", "本溪": "辽宁", "丹东": "辽宁",
    "锦州": "辽宁", "营口": "辽宁", "阜新": "辽宁", "辽阳": "辽宁", "盘锦": "辽宁", "铁岭": "辽宁",
    "朝阳": "辽宁", "葫芦岛": "辽宁",
    # 吉林
    "长春": "吉林", "吉林": "吉林", "四平": "吉林", "辽源": "吉林", "通化": "吉林", "白山": "吉林",
    "松原": "吉林", "白城": "吉林", "延边": "吉林", "长白": "吉林", "九台": "吉林", "德惠": "吉林",
    "柳河": "吉林", "东丰": "吉林", "磐石": "吉林", "双辽": "吉林", "梅河口": "吉林", "辉南": "吉林",
    "汪清": "吉林", "敦化": "吉林", "舒兰": "吉林", "靖宇": "吉林", "龙井": "吉林", "桦甸": "吉林",
    "长白山": "吉林", "长影": "吉林",
    # 黑龙江
    "哈尔滨": "黑龙江", "齐齐哈尔": "黑龙江", "牡丹江": "黑龙江", "佳木斯": "黑龙江", "大庆": "黑龙江",
    "伊春": "黑龙江", "鸡西": "黑龙江", "鹤岗": "黑龙江", "双鸭山": "黑龙江", "七台河": "黑龙江",
    "绥化": "黑龙江", "黑河": "黑龙江", "大兴安岭": "黑龙江",
    # 广东
    "广州": "广东", "深圳": "广东", "珠海": "广东", "汕头": "广东", "佛山": "广东", "韶关": "广东",
    "湛江": "广东", "肇庆": "广东", "江门": "广东", "茂名": "广东", "惠州": "广东", "梅州": "广东",
    "汕尾": "广东", "河源": "广东", "阳江": "广东", "清远": "广东", "东莞": "广东", "中山": "广东",
    "潮州": "广东", "揭阳": "广东", "云浮": "广东", "南国都市": "广东", "大湾区": "广东", "广东": "广东",
    # 江苏
    "南京": "江苏", "无锡": "江苏", "徐州": "江苏", "常州": "江苏", "苏州": "江苏", "南通": "江苏",
    "连云港": "江苏", "淮安": "江苏", "盐城": "江苏", "扬州": "江苏", "镇江": "江苏", "泰州": "江苏",
    "宿迁": "江苏", "常熟": "江苏", "句容": "江苏", "新沂": "江苏", "江阴": "江苏", "宜兴": "江苏",
    # 四川
    "成都": "四川", "自贡": "四川", "攀枝花": "四川", "泸州": "四川", "德阳": "四川", "绵阳": "四川",
    "广元": "四川", "遂宁": "四川", "内江": "四川", "乐山": "四川", "南充": "四川", "眉山": "四川",
    "宜宾": "四川", "广安": "四川", "达州": "四川", "雅安": "四川", "巴中": "四川", "资阳": "四川",
    "乐至": "四川", "营山": "四川", "沐川": "四川", "泸县": "四川", "松潘": "四川", "夹江": "四川",
    "汶川": "四川", "仁寿": "四川", "名山": "四川", "剑阁": "四川", "龙泉": "四川", "金川": "四川",
    # 海南
    "海南": "海南", "海口": "海南", "三亚": "海南", "三沙": "海南",
    # 其他省份
    "烟台": "山东", "山东": "山东", "定襄": "山西", "古县": "山西", "太谷": "山西", "怀仁": "山西",
    "山西": "山西", "渭源": "甘肃", "嘉峪关": "甘肃", "白银": "甘肃", "天祝": "甘肃", "甘肃": "甘肃",
    "晋江": "福建", "三明": "福建", "福建": "福建", "固镇": "安徽", "祁门": "安徽", "亳州": "安徽",
    "安徽": "安徽", "武汉": "湖北", "湖北": "湖北", "荥阳": "河南", "新野": "河南", "永城": "河南",
    "河南": "河南", "伊犁": "新疆", "奎屯": "新疆", "玛纳斯": "新疆", "可克达拉": "新疆",
    "石河子": "新疆", "双河": "新疆", "新疆": "新疆", "南宁": "广西", "来宾": "广西", "广西": "广西",
    "万州": "重庆", "铜梁": "重庆", "璧山": "重庆", "重庆": "重庆", "津南": "天津", "滨海": "天津",
    "天津": "天津", "第一财经": "上海", "上海": "上海", "北京": "北京", "辽宁": "辽宁", "河北": "河北",
    "江苏": "江苏", "四川": "四川", "浙江": "浙江", "黑龙江": "黑龙江", "陕西": "陕西", "云南": "云南",
    "楚雄": "云南", "赤峰": "内蒙古", "内蒙古": "内蒙古", "全国": "全国",
}
SINGLE_DROP = re.compile(r"中华特产|四海钓鱼|环球旅游|新视觉|纪实科教|重温经典|熊猫直播|直播中国|"
                         r"人间卫视|香港卫视|凤凰卫视|澳亚卫视|莲花卫视|澳门卫视|星空卫视|东森亚洲卫视|"
                         r"CCTV\+(?! 1| 2)")
MISLABELED = [
    # (channel-name regex, url substring) -> drop source (identity mismatch / clip, not live channel)
    (r"^内蒙古卫视$", "cztv.com/channels/lantian/channel007"),
    (r"^延边卫视$", "SXxinchang2"),
    (r"^湖南卫视$", "kwimgs.com"),
    (r"^海南卫视$", "nn.7x9d.cn"),
    (r"^CGTN ?法语$", "espanol-liveali"),
    (r"^CCTV-?13$", "cztv.com/channels/lantian/channel21"),
]
SIGNED_RE = re.compile(r"txSecret=|txTime=|_upt=|auth=|\$LR|playseek=", re.I)
QUAL_RANK = {"2160p": 5, "1080p": 4, "720p": 3, "576p": 2, "480p": 1, "360p": 0, "未知": 0}


def clean_url(url):
    u = url.strip()
    u = re.split(r";http", u)[0]           # concatenated duplicate URLs
    u = re.split(r"\$LR", u)[0]            # app-specific line chips
    u = u.rstrip("#").strip()
    u = re.sub(r"&?\?key=txiptv.*$", "", u)  # stray "&?key=..." on query-less URLs
    u = u.rstrip("?&")
    return u


def norm_name(raw):
    n = (raw or "").strip()
    m = re.search(r'tvg-name="([^"]*)"', n)
    if m:
        n = m.group(1)
    n = re.split(r"\$LR", n)[0].rstrip("# ").strip()
    n = re.sub(r"\s*\((?:1080p|720p|576i|540p|480p|2160p|4K)\)\s*$", "", n, flags=re.I)
    n = re.sub(r"\s*\[.*?\]\s*$", "", n).strip()
    return n


def quality_of(res, declared, url, name, bandwidth=None):
    """Return (quality, quality_source)."""
    if res and "x" in res:
        try:
            h = int(res.split("x")[1])
            q = ("2160p" if h >= 2000 else "1080p" if h >= 1000 else
                 "720p" if h >= 700 else "576p" if h >= 540 else "480p" if h >= 460 else "360p")
            return q, "playlist-resolution"
        except ValueError:
            pass
    for s in (declared or "", name or "", url or ""):
        if re.search(r"2160|4K(?!\w)", s, re.I):
            return "2160p", "declared"
        if re.search(r"1080", s):
            return "1080p", "declared"
        if re.search(r"720", s):
            return "720p", "declared"
        if re.search(r"576", s):
            return "576p", "declared"
        if re.search(r"高清|HD", s, re.I) and s is not url:
            return "1080p", "declared"
    if bandwidth:
        if bandwidth >= 4000000:
            return "1080p", "bandwidth"
        if bandwidth >= 2200000:
            return "720p", "bandwidth"
        if bandwidth >= 1200000:
            return "576p", "bandwidth"
        if bandwidth >= 600000:
            return "480p", "bandwidth"
        return "360p", "bandwidth"
    return "未知", "unknown"


def region_of(name, group_hint):
    if group_hint.startswith("☘️") and group_hint.endswith("频道"):
        p = group_hint[2:-2]
        if p in PROVINCES or p == "全国":
            return p
    for city, prov in sorted(CITY_PROVINCE.items(), key=lambda kv: -len(kv[0])):
        if name.startswith(city) or city in name[:3]:
            return prov
    for p in PROVINCES:
        if name.startswith(p):
            return p
    return "其他"


def canonical(name, url, group_hint):
    """Return (id, display_name, group, region) or None."""
    key = name.lower().strip()
    # CCTV main + feed variants
    m = re.match(r"^CCTV[-—\s]?(1[0-7]|[1-9]|5\+|4K|8K)(?:\s+(?:Asia|Europe|America|Asia Pacific))?$",
                 name, re.I)
    if m:
        tag = m.group(1).upper()
        if tag == "5+":
            return "cctv5plus", "CCTV-5+ 体育赛事", "cctv", ""
        if tag == "4K":
            return "cctv4k", "CCTV-4K 超高清", "cctv", ""
        if tag == "8K":
            return "cctv8k", "CCTV-8K 超高清", "cctv", ""
        n = int(tag)
        if n in CCTV_NAMES:
            return f"cctv{n}", CCTV_NAMES[n], "cctv", ""
    if re.match(r"^CCTV\+ ?1$", name):
        return "cctvplus1", "CCTV+ 1", "cctv", ""
    if re.match(r"^CCTV\+ ?2$", name):
        return "cctvplus2", "CCTV+ 2", "cctv", ""
    if key.startswith("cgtn"):
        table = [
            (r"documentary|纪录|记录", "cgtn_doc", "CGTN 纪录"),
            (r"french|法语", "cgtn_fr", "CGTN 法语"),
            (r"spanish|西语", "cgtn_es", "CGTN 西语"),
            (r"arabic|阿语", "cgtn_ar", "CGTN 阿语"),
            (r"russian|俄语", "cgtn_ru", "CGTN 俄语"),
            (r"global biz", "cgtn_biz", "CGTN 财经"),
            (r"英语|^cgtn\s*$", "cgtn", "CGTN 英语"),
        ]
        for pat, cid, cname in table:
            if re.search(pat, key):
                return cid, cname, "cctv", ""
        return "cgtn_other", name, "cctv", ""
    # Satellite: strip resolution suffix first
    sat = re.sub(r"\s*(4K|高清|HD)$", "", name, flags=re.I).strip()
    if sat.endswith("卫视") and len(sat) <= 8:
        base = sat[:-2].strip()
        if base:
            return "sat_" + base, base + "卫视", "satellite", ""
    if key in SATELLITE_CN:
        cn = SATELLITE_CN[key]
        if cn.endswith("卫视"):
            base = cn[:-2]
            return "sat_" + base, cn, "satellite", ""
    if key in LOCAL_CN:
        cn, region = LOCAL_CN[key]
        return "loc_" + re.sub(r"\W", "", key), cn, "local", region
    if group_hint == "地方频道" or group_hint.startswith("☘️"):
        cid = "loc_" + re.sub(r"\W", "", name)
        return cid, name, "local", region_of(name, group_hint)
    if re.search(r"(频道|综合|新闻|公共|影视|都市|生活|经济|科教|文旅|少儿|体育|纪实|国际|文化|"
                 r"资讯|电视台|一台|二套|一套)", name):
        region = region_of(name, group_hint)
        if region != "其他":
            return "loc_" + re.sub(r"\W", "", name), name, "local", region
    return None


def consider(c):
    """Return entry dict or None if out of scope/mislabeled."""
    name = norm_name(c.get("name") or "")
    url = clean_url(c.get("url") or "")
    if not name or not url:
        return None
    if SINGLE_DROP.search(name):
        return None
    for pat, sub in MISLABELED:
        if re.search(pat, name) and sub in url:
            return None
    canon = canonical(name, url, c.get("group_hint") or "")
    if not canon:
        return None
    cid, disp, group, region = canon
    return {"id": cid, "name": disp, "group": group, "region": region, "url": url, "src": c}


def main():
    playable = [r for r in json.load(open(os.path.join(BASE, "final_verify.json"),
                                          encoding="utf-8")) if r.get("playable")]
    stability = {r["url"]: r for r in json.load(open(os.path.join(BASE, "stability.json"),
                                                     encoding="utf-8")) if r.get("url")}
    stab_of = lambda url: stability.get(url, {}).get("passes")
    # re-verify cleaned URLs that changed
    rechecked = {}
    changed = [r for r in playable if clean_url(r["url"]) != r["url"]]
    print(f"re-checking {len(changed)} cleaned URLs", flush=True)
    for r in changed:
        c2 = dict(r, url=clean_url(r["url"]))
        res = None
        for _ in range(2):
            a = V.stage_a(c2)
            if a.get("extm3u"):
                res = V.stage_b(a)
                res["checked_at"] = NOW
                if res.get("playable"):
                    break
            time.sleep(0.5)
        rechecked[r["url"]] = res

    channels = {}
    stats_skipped = Counter()
    used = 0
    for r in playable:
        cleaned = clean_url(r["url"])
        src = rechecked.get(r["url"])
        if cleaned != r["url"] and not (src and src.get("playable")):
            cleaned = r["url"]  # fall back to the URL that actually worked
        ent = consider(dict(r, url=cleaned))
        if not ent:
            stats_skipped[r.get("group_hint") or "-"] += 1
            continue
        ch = channels.setdefault(ent["id"], {"id": ent["id"], "name": ent["name"],
                                             "group": ent["group"], "region": ent["region"],
                                             "sources": []})
        if any(s["url"] == cleaned for s in ch["sources"]):
            continue
        res = (src or {}).get("chosen") if src and src.get("playable") else (r.get("chosen") or {})
        seg = (src or {}).get("seg") if src and src.get("playable") else (r.get("seg") or {})
        a = (src or {}).get("a") if src and src.get("playable") else (r.get("a") or {})
        q, qsrc = quality_of(res.get("resolution"), r.get("declared_quality"),
                             r.get("name"), r.get("name"), res.get("bandwidth"))
        signed = bool(SIGNED_RE.search(r["url"]))
        passes = stab_of(cleaned)
        if passes is None:
            passes = stab_of(r["url"])
        if passes == 0:
            health = "unstable"
        else:
            health = "stable" if passes == 2 else "intermittent"
            if signed and health == "stable":
                health = "signed"
        ch["sources"].append({
            "url": cleaned,
            "protocol": "hls",
            "quality": q,
            "quality_source": qsrc,
            "resolution": res.get("resolution") or None,
            "http_status": a.get("http"),
            "content_type": a.get("ct"),
            "ttfb_ms": int((a.get("ttfb") or 0) * 1000),
            "playlist_type": "master" if r.get("kind") == "master" else "media",
            "segment_verified": True,
            "segment_bytes": seg.get("bytes"),
            "segment_content_type": seg.get("ct"),
            "bitrate_bps": res.get("bandwidth") if r.get("kind") == "master" else None,
            "stability": health,
            "health": {"probes": 2, "passes": passes,
                       "last_checked": (stability.get(cleaned) or stability.get(r["url"]) or {}).get("checked_at", NOW)},
            "note": "4K 源" if re.search(r"4K", r.get("name") or "", re.I) else None,
            "source_list": r.get("source") or "unknown",
            "checked_at": (src or {}).get("checked_at") or r.get("checked_at") or NOW,
        })
        used += 1

    channels = {k: v for k, v in channels.items() if v["sources"]}
    for ch in channels.values():
        rank = {"stable": 0, "signed": 1, "intermittent": 2, "unstable": 3}
        ch["sources"].sort(key=lambda s: (rank.get(s["stability"], 4),
                                          -QUAL_RANK.get(s["quality"], 0),
                                          s["ttfb_ms"] if s["ttfb_ms"] else 99999))
        ch["sources"] = ch["sources"][:4]  # 1 primary + at most 3 backups (captain rule)
        for i, s in enumerate(ch["sources"], 1):
            s["priority"] = i
            s["fallback_only"] = s["stability"] == "unstable"
        ch["quality"] = ch["sources"][0]["quality"]
        ch["quality_source"] = ch["sources"][0]["quality_source"]
        ch["source_count"] = len(ch["sources"])
        healthy = [s for s in ch["sources"] if not s["fallback_only"]]
        if any(s["stability"] == "stable" for s in healthy):
            ch["status"] = "healthy"
        elif healthy:
            ch["status"] = "flaky"
        else:
            ch["status"] = "degraded"

    order = {"cctv": 0, "satellite": 1, "local": 2}
    chans = sorted(channels.values(), key=lambda c: (order.get(c["group"], 9), c["region"] or "", c["name"]))
    GROUP_CN = {"cctv": "央视频道", "satellite": "卫视频道", "local": "地方频道"}
    for i, c in enumerate(chans, 1):
        c["order"] = i
        c["group_id"] = c["group"]
        c["tvg_id"] = c["id"]
        c["logo"] = None
        c["group"] = GROUP_CN[c["group"]]
        urls = [s["url"] for s in c["sources"] if not s["fallback_only"]]
        c["urls"] = (urls or [c["sources"][0]["url"]])[:4]
        c["verified_at"] = c["sources"][0]["checked_at"]
        c["latency_ms"] = c["sources"][0]["ttfb_ms"]
        if c["group_id"] == "cctv":
            c["desc"] = f"中央广播电视总台 {c['name']}"
        elif c["group_id"] == "satellite":
            p = c["name"].replace("卫视", "")
            c["desc"] = f"{p}广播电视台卫星频道" if p else "卫星电视频道"
        else:
            c["desc"] = None

    total_candidates = 0
    for fn in ("candidates_all.json", "candidates_extra4.json"):
        p = os.path.join(BASE, fn)
        if os.path.exists(p):
            total_candidates += len(json.load(open(p, encoding="utf-8")))
    m3u8_ever = len({r["url"] for fn in ("probe_results.json", "verify_all.json", "verify_extra.json")
                     for r in json.load(open(os.path.join(BASE, fn), encoding="utf-8"))
                     if r.get("extm3u") or r.get("playable")})
    stats = {
        "probed_initial": total_candidates,
        "ipv6_only_excluded": 134,
        "playlist_m3u8_ok": m3u8_ever,
        "fully_verified_sources": sum(len(c["sources"]) for c in chans),
        "channels": len(chans),
        "by_group": dict(Counter(c["group_id"] for c in chans)),
        "by_stability": dict(Counter(s["stability"] for c in chans for s in c["sources"])),
        "by_quality": dict(Counter(s["quality"] for c in chans for s in c["sources"])),
    }
    seed = {
        "schema_version": 1,
        "name": "live_seed_cn",
        "generated_at": NOW,
        "groups": [
            {"id": "cctv", "name": "央视频道", "order": 1},
            {"id": "satellite", "name": "卫视频道", "order": 2},
            {"id": "local", "name": "地方频道", "order": 3},
        ],
        "probe": {
            "method": "curl 8.22 两阶段探测：拉取播放列表要求返回 #EXTM3U，再取首个媒体分片要求 200/206 且为 TS/fMP4",
            "checked_window": "2026-10-21T18:47:00+08:00 ~ 2026-10-21T18:58:00+08:00",
            "stats": stats,
            "network_note": ("探测主机位于中国大陆以外网络出口；IPv6-only 源与部分仅限大陆 ISP 的地址不可达，"
                             "因此可用性结果偏保守，客户端实际网络下可用率可能更高。"),
            "caveat": ("频道身份取自上游播放列表标注，未做画面级核验；个别聚合源存在张冠李戴，"
                       "已剔除明显错配（如用浙江地方频道冒充延边卫视）。"),
        },
        "policy": {
            "preferred_protocol": "hls",
            "playlist_timeout_ms": 8000,
            "connect_timeout_ms": 5000,
            "first_frame_timeout_ms": 12000,
            "same_source_retries": 1,
            "switch_delay_ms": 600,
            "quality_ladder": ["2160p", "1080p", "720p", "576p", "480p"],
            "low_end_default_max_quality": "1080p",
            "health": {
                "probes_per_source": 2,
                "failure_backoff_seconds": [60, 300, 1800, 21600],
                "success_resets_backoff": True,
                "on_demand_check": True,
                "background_refresh_hours": 24,
                "max_concurrent_checks": 4,
                "use_unstable_only_as_last_resort": True,
            },
            "refresh": {
                "bundled_seed_always_available": True,
                "remote_manifest_check_hours": 24,
                "wifi_only": True,
                "keep_last_good": True,
            },
            "fallback_semantics": {
                "healthy": "至少一个 stable 源通过两轮探测",
                "flaky": "只有 intermittent 源通过，播放前先做一次快速探测",
                "degraded": "只有 unstable 源，进入频道时提示可能不可用并自动换源",
            },
        },
        "channels": chans,
    }
    json.dump(seed, open(OUT_SEED, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(stats, open(OUT_STATS, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps(stats, ensure_ascii=False, indent=1))
    print("skipped:", dict(stats_skipped.most_common()))
    print("seed written:", OUT_SEED)
    for c in chans:
        if c["group"] in ("cctv", "satellite"):
            print(f"{c['group'][:3]:3s} {c['id']:12s} {c['name']:16s} q={c['quality']:5s} n={c['source_count']}")


if __name__ == "__main__":
    main()
