#!/usr/bin/env python
"""Normalize verified streams into canonical channels, classify groups, rank sources."""
import json, os, re, datetime
from collections import defaultdict

BASE = os.path.dirname(os.path.abspath(__file__))
NOW = "2026-10-21T18:54:00+08:00"

CCTV_NAMES = {
    1: "CCTV-1 综合", 2: "CCTV-2 财经", 3: "CCTV-3 综艺", 4: "CCTV-4 中文国际",
    5: "CCTV-5 体育", 6: "CCTV-6 电影", 7: "CCTV-7 国防军事", 8: "CCTV-8 电视剧",
    9: "CCTV-9 纪录", 10: "CCTV-10 科教", 11: "CCTV-11 戏曲", 12: "CCTV-12 社会与法",
    13: "CCTV-13 新闻", 14: "CCTV-14 少儿", 15: "CCTV-15 音乐", 16: "CCTV-16 奥林匹克",
    17: "CCTV-17 农业农村",
}

SATELLITE_CN = {
    "anhui tv": "安徽卫视", "dongfang": "东方卫视", "dragon tv": "东方卫视",
    "dragon tv international": "东方卫视国际", "guangdong satellite tv": "广东卫视",
    "hebei tv": "河北卫视", "heilongjiang tv": "黑龙江卫视", "henan tv": "河南卫视",
    "hubei tv": "湖北卫视", "hunan tv": "湖南卫视", "jiangsu satellite tv": "江苏卫视",
    "jiangxi tv": "江西卫视", "liaoning tv": "辽宁卫视", "nei monggol tv": "内蒙古卫视",
    "ningxia satellite channel": "宁夏卫视", "qinghai tv": "青海卫视",
    "shaanxi tv": "陕西卫视", "shandong tv": "山东卫视", "shenzhen satellite tv": "深圳卫视",
    "sichuan satellite tv": "四川卫视", "tianjin tv": "天津卫视", "yanbian satellite tv": "延边卫视",
    "yunnan satellite tv": "云南卫视", "zhejiang satellite tv": "浙江卫视",
    "beijing satellite tv": "北京卫视", "bingtuan satellite tv": "兵团卫视",
    "chongqing tv": "重庆卫视", "guizhou tv": "贵州卫视", "gansu tv": "甘肃卫视",
    "xinjiang tv": "新疆卫视", "xizang tv": "西藏卫视", "guangxi tv": "广西卫视",
    "shanxi tv": "山西卫视", "jilin tv": "吉林卫视", "fujian tv": "东南卫视",
}

LOCAL_CN = {
    "baicheng tv": ("白城电视台", "吉林"),
    "siping tv": ("四平电视台", "吉林"),
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
    "xinjiang tv 2": ("新疆电视台二套", "新疆"),
    "xinjiang tv 3": ("新疆电视台三套", "新疆"),
    "xinjiang tv 5": ("新疆电视台五套", "新疆"),
    "xinjiang tv 8": ("新疆电视台八套", "新疆"),
    "cnd film middle school channel": ("中国教育电视台三套", "全国"),
    "east southeast": ("东南卫视", "福建"),
}
PROVINCES = ["北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏",
             "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南",
             "重庆", "四川", "贵州", "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆"]
CITY_PROVINCE = {"哈尔滨": "黑龙江", "长春": "吉林", "沈阳": "辽宁", "大连": "辽宁", "石家庄": "河北",
                 "太原": "山西", "济南": "山东", "青岛": "山东", "郑州": "河南", "武汉": "湖北",
                 "长沙": "湖南", "广州": "广东", "深圳": "广东", "东莞": "广东", "佛山": "广东",
                 "成都": "四川", "贵阳": "贵州", "昆明": "云南", "西安": "陕西", "兰州": "甘肃",
                 "西宁": "青海", "银川": "宁夏", "乌鲁木齐": "新疆", "呼和浩特": "内蒙古",
                 "赤峰": "内蒙古", "苏州": "江苏", "南京": "江苏", "无锡": "江苏", "杭州": "浙江",
                 "宁波": "浙江", "温州": "浙江", "合肥": "安徽", "福州": "福建", "厦门": "福建",
                 "南昌": "江西", "南宁": "广西", "海口": "海南", "三亚": "海南", "拉萨": "西藏",
                 "白城": "吉林", "四平": "吉林", "楚雄": "云南", "邢台": "河北", "邯郸": "河北",
                 "河源": "广东", "广元": "四川", "昌黎": "河北", "七台河": "黑龙江", "三沙": "海南",
                 "萧山": "浙江", "象山": "浙江", "云和": "浙江", "文成": "浙江", "青田": "浙江",
                 "龙泉": "浙江", "新昌": "浙江", "苏州": "江苏"}

SCOPE_GROUPS = {"央视频道", "卫视频道", "地方频道", "4K频道", "APTV专享"}
SKIP_GROUP_KEYS = ("点播电影", "春晚频道", "电视剧频道", "埋堆堆频道", "动画频道", "国际频道",
                   "数字频道", "Religious", "Entertainment", "General", "News", "Movies", "Kids",
                   "Outdoor", "business", "culture", "documentary", "movies", "news", "general",
                   "港澳台频道", "港澳代理", "拳击", "电影频道", "纪录频道", "儿童频道", "音乐频道")
SIGNED_RE = re.compile(r"txSecret=|txTime=|_upt=|auth=|wsv|token=|playseek=|\$LR", re.I)


def norm_name(raw, tvg_name=""):
    n = (raw or "").strip()
    m = re.search(r'tvg-name="([^"]*)"', n)
    if m:
        n = m.group(1)
    n = re.sub(r"\$LR.*$", "", n).rstrip("# ").strip()
    n = re.sub(r"\s*\((?:1080p|720p|576i|540p|480p|2160p|4K)\)\s*$", "", n, flags=re.I)
    n = re.sub(r"\[.*?\]$", "", n).strip()
    return n


def quality_of(res, declared, url, name):
    def q_from_height(h):
        if h >= 2000: return "2160p"
        if h >= 1000: return "1080p"
        if h >= 700: return "720p"
        if h >= 540: return "576p"
        if h >= 460: return "480p"
        return "360p"
    if res and "x" in res:
        try:
            h = int(res.split("x")[1])
            return q_from_height(h)
        except ValueError:
            pass
    for cand in (declared, name, url):
        if not cand:
            continue
        s = str(cand)
        if re.search(r"2160|4K", s, re.I): return "2160p"
        if re.search(r"1080", s): return "1080p"
        if re.search(r"720", s): return "720p"
        if re.search(r"576", s): return "576p"
        if re.search(r"高清|HD", s, re.I): return "1080p"
    return "未知"


def canonical(entry):
    """Return (id, name, group, region) or None if out of scope."""
    raw = entry.get("name") or ""
    name = norm_name(raw, entry.get("tvg_name") or "")
    gh = entry.get("group_hint") or ""
    tvg = entry.get("tvg_name") or ""
    key = name.lower().strip()
    # CCTV family
    m = re.match(r"^(?:CCTV[-\s]?)(\d{1,2}|5\+|4K|8K)$", name, re.I)
    if not m:
        m2 = re.search(r"^(CCTV|CGTN)", name, re.I)
        m = None
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
    lower = name.lower()
    if lower.startswith("cgtn"):
        mapping = [
            (r"cgtn documentary|cgtn纪录", "cgtn_doc", "CGTN 纪录"),
            (r"cgtn french|cgtn法语", "cgtn_fr", "CGTN 法语"),
            (r"cgtn spanish|cgtn西语", "cgtn_es", "CGTN 西语"),
            (r"cgtn arabic|cgtn阿语", "cgtn_ar", "CGTN 阿语"),
            (r"cgtn russian|cgtn俄语", "cgtn_ru", "CGTN 俄语"),
            (r"cgtn global biz", "cgtn_biz", "CGTN 财经"),
            (r"^cgtn\s*$", "cgtn", "CGTN 英语"),
        ]
        for pat, cid, cname in mapping:
            if re.search(pat, lower):
                return cid, cname, "cctv", ""
        return "cgtn_other", name, "cctv", ""
    if "熊猫直播" in name:
        return "cctv_panda", "熊猫直播", "cctv", ""
    if "直播中国" in name:
        return "cctv_livechina", "直播中国", "cctv", ""
    # Satellite (explicit list)
    if key in SATELLITE_CN:
        cn = SATELLITE_CN[key]
        if cn.endswith("卫视"):
            return "sat_" + cn.replace("卫视", ""), cn, "satellite", ""
        return "sat_other_" + re.sub(r"\W", "", key), cn, "satellite", ""
    if name.endswith("卫视") and len(name) <= 8:
        base = re.sub(r"(4K|高清|HD)$", "", name.replace("卫视", "")).strip()
        if base:
            return "sat_" + base, base + "卫视", "satellite", ""
    # local explicit
    if key in LOCAL_CN:
        cn, region = LOCAL_CN[key]
        return "loc_" + re.sub(r"\W", "", key), cn, "local", region
    # local Chinese names with province/city prefix
    if re.search(r"(频道|综合|新闻|公共|影视|都市|生活|经济|科教|文旅|少儿|体育|纪实|国际|文化|影视剧|资讯|电视台)", name):
        region = ""
        for p in PROVINCES:
            if name.startswith(p) or name.startswith(p[:2]):
                region = p
                break
        if not region:
            for city, prov in CITY_PROVINCE.items():
                if name.startswith(city):
                    region = prov
                    break
        if region or gh in ("地方频道",) or gh.startswith("☘️"):
            cid = "loc_" + re.sub(r"\W", "", name)
            return cid, name, "local", region or "其他"
    return None


def source_record(entry, checked_at):
    url = entry["url"]
    # strip playlist-line junk
    url = re.split(r"\$LR", url)[0].rstrip("#").strip()
    res = (entry.get("chosen") or {}).get("resolution") or ""
    q = quality_of(res, entry.get("declared_quality"), url, entry.get("name"))
    seg = entry.get("seg") or {}
    signed = bool(SIGNED_RE.search(entry["url"]))
    return {
        "url": url,
        "protocol": "hls",
        "quality": q,
        "resolution": res or None,
        "http_status": entry["a"]["http"],
        "content_type": entry["a"]["ct"] or None,
        "ttfb_ms": int((entry["a"]["ttfb"] or 0) * 1000),
        "playlist": entry["kind"],
        "segments": (entry.get("chosen") or {}).get("segments"),
        "segment_verified": bool(seg.get("ok")),
        "segment_bytes": seg.get("bytes"),
        "segment_ct": seg.get("ct"),
        "stability": "signed" if signed else "stable",
        "source_list": entry.get("source"),
        "checked_at": checked_at,
    }


def main():
    entries = []
    for fn, ts in (("verify_all.json", "2026-10-21T18:50:00+08:00"),
                   ("verify_extra.json", "2026-10-21T18:54:00+08:00")):
        for e in json.load(open(os.path.join(BASE, fn), encoding="utf-8")):
            if e.get("playable"):
                e["_checked_at"] = ts
                entries.append(e)
    channels, skipped = {}, defaultdict(int)
    for e in entries:
        canon = canonical(e)
        if not canon:
            skipped[(e.get("group_hint") or "-")] += 1
            continue
        cid, cname, group, region = canon
        ch = channels.setdefault(cid, {"id": cid, "name": cname, "group": group, "region": region,
                                       "sources": []})
        src = source_record(e, e["_checked_at"])
        # dedupe identical url
        if any(s["url"] == src["url"] for s in ch["sources"]):
            continue
        ch["sources"].append(src)
    # rank sources: stable first, higher quality, lower ttfb
    qrank = {"2160p": 5, "1080p": 4, "720p": 3, "576p": 2, "480p": 1, "360p": 0, "未知": 1}
    for ch in channels.values():
        ch["sources"].sort(key=lambda s: (s["stability"] != "stable", -qrank.get(s["quality"], 0),
                                          s["ttfb_ms"] if s["ttfb_ms"] else 99999))
        for i, s in enumerate(ch["sources"], 1):
            s["priority"] = i
        ch["primary_quality"] = ch["sources"][0]["quality"]
    out = sorted(channels.values(), key=lambda c: ({"cctv": 0, "satellite": 1, "local": 2}.get(c["group"], 9),
                                                   c["name"]))
    json.dump(out, open(os.path.join(BASE, "channels_merged.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    from collections import Counter
    print("channels:", len(out), Counter(c["group"] for c in out))
    print("sources:", sum(len(c["sources"]) for c in out),
          Counter(s["stability"] for c in out for s in c["sources"]))
    print("skipped out-of-scope:", dict(Counter(skipped).most_common()))
    print("\n-- cctv --")
    for c in out:
        if c["group"] == "cctv":
            print(f"{c['id']:12s} {c['name']:16s} q={c['primary_quality']:5s} sources={len(c['sources'])}")
    print("\n-- satellite --")
    for c in out:
        if c["group"] == "satellite":
            print(f"{c['id']:22s} {c['name']:10s} q={c['primary_quality']:5s} sources={len(c['sources'])}")
    print("\n-- local regions --")
    print(Counter(c["region"] for c in out if c["group"] == "local").most_common())


if __name__ == "__main__":
    main()
