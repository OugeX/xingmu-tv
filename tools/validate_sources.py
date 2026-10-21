#!/usr/bin/env python3
"""tvapp.source.v1 / v1.1 导入文件校验器（开发/QA 自检用，不参与 APK 构建）。

用法:
    python tools/validate_sources.py assets/sources.example.json [...]
    python tools/validate_sources.py --selftest

校验分两层：
  L1 JSON Schema（assets/sources.schema.json，2020-12）
  L2 语义检查（id/端点唯一、url/inline 二选一、分类键、v1.1 的广告拦截/片头跳过/
     画质上限/EPG/频道定义等）
退出码 0 = 全部通过；1 = 有失败。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

try:
    from jsonschema import Draft202012Validator
except ImportError:  # pragma: no cover
    print("需要 jsonschema：python -m pip install jsonschema", file=sys.stderr)
    sys.exit(2)

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "assets" / "sources.schema.json"
CATEGORY_KEYS = {"MOVIE", "TV", "ANIME", "VARIETY", "SHORT", "SPORTS", "OTHER"}
ALLOWED_SCHEMES = {"http", "https"}
QUALITY_VALUES = {"auto", "1080p", "720p", "480p"}
CODEC_VALUES = {"h264", "hevc", "auto"}
MAX_EPG_URLS = 3
MAX_AD_DOMAINS = 50
MAX_AD_KEYWORDS = 100
MAX_CHANNELS = 5000
MAX_CHANNEL_URLS = 8
MAX_DESC_LEN = 200
MAX_SKIP_INTRO = 600


def load_schema() -> Draft202012Validator:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def fingerprint(src: dict) -> str | None:
    endpoint = src.get("baseUrl") or src.get("url")
    if not endpoint:
        return None
    parts = urlsplit(endpoint)
    host = (parts.hostname or "").lower()
    port = parts.port
    if (parts.scheme == "https" and port == 443) or (parts.scheme == "http" and port == 80):
        port = None
    netloc = host if port is None else f"{host}:{port}"
    query = "&".join(sorted(p for p in parts.query.split("&") if p))
    return f"{src.get('kind')}|{parts.scheme.lower()}://{netloc}{parts.path}" + (f"?{query}" if query else "")


def hostname_of(url: str) -> str:
    try:
        return (urlsplit(url).hostname or "").lower()
    except ValueError:
        return ""


def domain_rule_matches(host: str, rule: str) -> bool:
    """广告域名规则：精确域命中自身与子域；`*.example.com` 只命中子域（也包含自身）。"""
    host = host.lower().rstrip(".")
    rule = rule.lower().rstrip(".")
    if not host or not rule:
        return False
    if rule.startswith("*."):
        base = rule[2:]
        return host == base or host.endswith("." + base)
    return host == rule or host.endswith("." + rule)


def channel_name_key(name: str) -> str:
    return re.sub(r"\s+", "", name).lower()


def check_v11(src: dict, where: str) -> list[str]:
    """v1.1 新增字段的语义校验（schema 已保证类型/枚举/长度，这里补跨字段与业务规则）。"""
    errs: list[str] = []

    skip = src.get("skipIntroSeconds")
    if isinstance(skip, int) and not isinstance(skip, bool) and not 0 <= skip <= MAX_SKIP_INTRO:
        errs.append(f"{where}: E_FIELD skipIntroSeconds 必须在 0..{MAX_SKIP_INTRO}")
    cue = src.get("skipIntroCue")
    if cue is not None and not isinstance(cue, bool):
        errs.append(f"{where}: E_FIELD skipIntroCue 必须是布尔值")
    if src.get("maxQuality") is not None and src["maxQuality"] not in QUALITY_VALUES:
        errs.append(f"{where}: E_FIELD maxQuality 非法: {src['maxQuality']}")
    if src.get("preferredCodec") is not None and src["preferredCodec"] not in CODEC_VALUES:
        errs.append(f"{where}: E_FIELD preferredCodec 非法: {src['preferredCodec']}")

    # ---- EPG ----
    epg = src.get("epgUrls")
    if epg is not None:
        if not isinstance(epg, list):
            errs.append(f"{where}: E_EPG epgUrls 必须是数组")
            epg = []
        if len(epg) > MAX_EPG_URLS:
            errs.append(f"{where}: E_EPG epgUrls 最多 {MAX_EPG_URLS} 项")
        for u in epg:
            if not isinstance(u, str) or urlsplit(u).scheme.lower() not in ALLOWED_SCHEMES:
                errs.append(f"{where}: E_EPG EPG 地址必须是 http(s): {u!r}")

    # ---- 广告拦截 ----
    ab = src.get("adBlock")
    if ab is not None:
        if not isinstance(ab, dict):
            errs.append(f"{where}: E_ADBLOCK adBlock 必须是对象")
        else:
            domains = ab.get("domains") or []
            keywords = ab.get("urlKeywords") or []
            if not isinstance(domains, list):
                errs.append(f"{where}: E_ADBLOCK adBlock.domains 必须是数组")
                domains = []
            if not isinstance(keywords, list):
                errs.append(f"{where}: E_ADBLOCK adBlock.urlKeywords 必须是数组")
                keywords = []
            if len(domains) > MAX_AD_DOMAINS:
                errs.append(f"{where}: E_ADBLOCK domains 最多 {MAX_AD_DOMAINS} 条")
            for d in domains:
                if not isinstance(d, str):
                    errs.append(f"{where}: E_ADBLOCK 域名必须是字符串: {d!r}")
                    continue
                base = d[2:] if d.startswith("*.") else d
                if not base or "/" in base or ":" in base or " " in base:
                    errs.append(f"{where}: E_ADBLOCK 域名格式非法（不要带路径/端口/scheme）: {d}")
                if d != d.lower():
                    errs.append(f"{where}: E_ADBLOCK 域名必须小写: {d}")
            if len(keywords) > MAX_AD_KEYWORDS:
                errs.append(f"{where}: E_ADBLOCK urlKeywords 最多 {MAX_AD_KEYWORDS} 条")
            for k in keywords:
                if not isinstance(k, str):
                    errs.append(f"{where}: E_ADBLOCK urlKeywords 必须是字符串: {k!r}")
                elif not k.strip():
                    errs.append(f"{where}: E_ADBLOCK urlKeywords 不能为空白")
                elif k.strip() == "*":
                    errs.append(f"{where}: E_ADBLOCK urlKeywords 不允许 '*'（会拦截全部请求）")

            # 跨字段：API / 降级端点 / EPG 不能被自身规则拦截，否则必然自伤
            protected = [src.get("baseUrl"), src.get("url"), *(src.get("fallbackBaseUrls") or [])]
            for u in protected:
                if isinstance(u, str):
                    h = hostname_of(u)
                    hit = next((d for d in domains if isinstance(d, str) and domain_rule_matches(h, d)), None)
                    if hit:
                        errs.append(f"{where}: E_ADBLOCK API 主机 {h} 被自身规则 {hit} 拦截")
            for u in epg or []:
                if isinstance(u, str):
                    h = hostname_of(u)
                    hit = next((d for d in domains if isinstance(d, str) and domain_rule_matches(h, d)), None)
                    if hit:
                        errs.append(f"{where}: E_ADBLOCK EPG 主机 {h} 被自身规则 {hit} 拦截")

    # ---- 频道级定义（m3u） ----
    chans = src.get("channels")
    if chans is not None:
        if not isinstance(chans, list):
            errs.append(f"{where}: E_CHANNEL channels 必须是数组")
            chans = []
        if len(chans) > MAX_CHANNELS:
            errs.append(f"{where}: E_CHANNEL channels 最多 {MAX_CHANNELS} 条")
        seen_keys: set[tuple[str, str]] = set()
        seen_tvg: set[str] = set()
        for i, ch in enumerate(chans):
            cw = f"{where}.channels[{i}]"
            if not isinstance(ch, dict):
                errs.append(f"{cw}: E_CHANNEL 频道必须是对象")
                continue
            name = ch.get("name")
            if not isinstance(name, str) or not name.strip():
                errs.append(f"{cw}: E_CHANNEL name 不能为空")
                name = ""
            group = ch.get("group") or "其他"
            key = (str(group).lower(), channel_name_key(name))
            if key in seen_keys:
                errs.append(f"{cw}: E_CHANNEL 频道重复: {group}/{name}")
            seen_keys.add(key)
            tvg = ch.get("tvgId")
            if tvg is not None:
                if not isinstance(tvg, str) or not tvg.strip():
                    errs.append(f"{cw}: E_CHANNEL tvgId 不能为空")
                else:
                    t = tvg.strip().lower()
                    if t in seen_tvg:
                        errs.append(f"{cw}: E_CHANNEL tvgId 重复: {tvg}")
                    seen_tvg.add(t)
            has_url, has_urls = "url" in ch, "urls" in ch
            if has_url == has_urls:
                errs.append(f"{cw}: E_CHANNEL url 与 urls 必须二选一")
            urls = [ch.get("url")] if has_url else (ch.get("urls") or [])
            if not isinstance(urls, list):
                errs.append(f"{cw}: E_CHANNEL urls 必须是数组")
                urls = []
            if not urls:
                errs.append(f"{cw}: E_CHANNEL 至少需要一个播放地址")
            if len(urls) > MAX_CHANNEL_URLS:
                errs.append(f"{cw}: E_CHANNEL urls 最多 {MAX_CHANNEL_URLS} 条")
            for u in urls:
                if not isinstance(u, str) or urlsplit(u).scheme.lower() not in ALLOWED_SCHEMES:
                    errs.append(f"{cw}: E_CHANNEL 播放地址必须是 http(s): {u!r}")
            logo = ch.get("logo")
            if logo is not None and (not isinstance(logo, str) or urlsplit(logo).scheme.lower() not in ALLOWED_SCHEMES):
                errs.append(f"{cw}: E_CHANNEL logo 必须是 http(s): {logo!r}")
            desc = ch.get("desc")
            if desc is not None:
                if not isinstance(desc, str):
                    errs.append(f"{cw}: E_CHANNEL desc 必须是字符串")
                elif len(desc) > MAX_DESC_LEN:
                    errs.append(f"{cw}: E_CHANNEL desc 超过 {MAX_DESC_LEN} 字")
                elif "\r" in desc or "\x00" in desc:
                    errs.append(f"{cw}: E_CHANNEL desc 含非法控制字符")

    return errs


def semantic_errors(doc: dict) -> list[str]:
    errs: list[str] = []
    sources = doc["sources"] if doc.get("kind") == "bundle" else [doc]
    ids: set[str] = set()
    fps: set[str] = set()

    for idx, src in enumerate(sources):
        where = f"#{idx}({src.get('id', '?')})"
        sid = src.get("id", "")
        if sid in ids:
            errs.append(f"{where}: E_ID 源 id 重复: {sid}")
        ids.add(sid)

        endpoint = src.get("baseUrl") or src.get("url")
        if endpoint:
            scheme = urlsplit(endpoint).scheme.lower()
            if scheme not in ALLOWED_SCHEMES:
                errs.append(f"{where}: E_URL 协议不允许: {scheme or '(空)'}")
            fp = fingerprint(src)
            if fp in fps:
                errs.append(f"{where}: E_ID 端点指纹重复: {fp}")
            fps.add(fp)

        if src.get("kind") == "m3u":
            has_url, has_inline = "url" in src, "inline" in src
            if has_url == has_inline:
                errs.append(f"{where}: E_FIELD url 与 inline 必须二选一")
            regex = (src.get("filters") or {}).get("excludeNameRegex")
            if regex:
                try:
                    re.compile(regex)
                except re.error as exc:
                    errs.append(f"{where}: E_REGEX 正则无效: {exc}")

        if src.get("kind") == "maccms":
            cats = src.get("categories") or {}
            unknown = set(cats) - CATEGORY_KEYS
            if unknown:
                errs.append(f"{where}: E_FIELD 未知分类键: {sorted(unknown)}")
            for fb in src.get("fallbackBaseUrls") or []:
                if urlsplit(fb).scheme.lower() not in ALLOWED_SCHEMES:
                    errs.append(f"{where}: E_URL fallback 协议不允许")

        if src.get("kind") == "bundle":
            errs.append(f"{where}: E_NESTED 不允许嵌套 bundle")

        headers = src.get("headers") or {}
        for k, v in headers.items():
            if "\r" in v or "\n" in v:
                errs.append(f"{where}: E_FIELD header 含换行: {k}")

        errs.extend(check_v11(src, where))

    return errs


def validate_file(path: Path, validator: Draft202012Validator) -> list[str]:
    problems: list[str] = []
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return [f"E_JSON: {exc}"]

    for err in sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path)):
        loc = "/".join(str(p) for p in err.absolute_path) or "<root>"
        problems.append(f"E_SCHEMA: {loc}: {err.message}")

    problems.extend(semantic_errors(doc) if isinstance(doc, dict) else ["E_SCHEMA: 顶层必须是对象"])
    return problems


def _errors(validator: Draft202012Validator, doc: dict) -> list[str]:
    return [e.message for e in validator.iter_errors(doc)] + semantic_errors(doc)


def selftest(validator: Draft202012Validator) -> int:
    """负例：确认校验器能拦住坏输入；同时确认 v1 老配置与 v1.1 新配置仍能通过。"""
    v1 = {
        "schema": "tvapp.source.v1", "kind": "maccms", "id": "ok1", "name": "ok",
        "baseUrl": "https://macapi1.com/maccms/json/baofeng/",
    }
    v11 = {
        **v1, "schema": "tvapp.source.v1.1", "adBlock": {"enabled": True, "domains": ["doubleclick.net"],
                                                         "urlKeywords": ["/ads/"]},
        "skipIntroSeconds": 90, "skipIntroCue": True, "maxQuality": "1080p", "preferredCodec": "auto",
    }
    m3u = {
        "schema": "tvapp.source.v1.1", "kind": "m3u", "id": "m1", "name": "m",
        "url": "https://e.com/a.m3u",
        "epgUrls": ["https://e.com/epg.xml.gz"],
        "channels": [{"name": "CCTV-1 综合", "urls": ["https://e.com/c1.m3u8"], "group": "央视频道",
                      "tvgId": "cctv1", "logo": "https://e.com/c1.png", "desc": "综合频道"}],
    }

    old_cases = {
        "缺 schema": {k: v for k, v in v1.items() if k != "schema"},
        "未知 kind": {**v1, "kind": "tvbox"},
        "非法 id": {**v1, "id": "Bad ID!"},
        "file 协议": {**v1, "baseUrl": "file:///etc/passwd"},
        "缺 baseUrl": {k: v for k, v in v1.items() if k != "baseUrl"},
        "超时越界": {**v1, "timeoutMs": 999},
        "未知字段": {**v1, "evil": 1},
        "m3u 双端点": {"schema": "tvapp.source.v1", "kind": "m3u", "id": "m1", "name": "m",
                       "url": "https://e.com/a.m3u", "inline": "#EXTM3U"},
        "内嵌 bundle": {"schema": "tvapp.source.v1", "kind": "bundle", "id": "b1", "name": "b",
                        "sources": [{"schema": "tvapp.source.v1", "kind": "bundle", "id": "b2",
                                     "name": "b2", "sources": [v1]}]},
    }
    new_cases = {
        "skipIntroSeconds=601": {**v11, "skipIntroSeconds": 601},
        "adBlock.domains 非字符串": {**v11, "adBlock": {"domains": [123]}},
        "maxQuality 非法枚举": {**v11, "maxQuality": "4k"},
        "preferredCodec 非法枚举": {**v11, "preferredCodec": "av1"},
        "adBlock 非对象": {**v11, "adBlock": "ads"},
        "频道缺 URL": {**m3u, "channels": [{"name": "CCTV-1"}]},
        "频道重复（同名同组）": {**m3u, "channels": [{"name": "CCTV-1", "url": "https://e.com/a.m3u8"},
                                              {"name": "cctv-1 ", "url": "https://e.com/b.m3u8"}]},
        "EPG 被自身广告规则拦截": {**m3u, "epgUrls": ["https://ads.example.com/e.xml"],
                            "adBlock": {"domains": ["ads.example.com"]}},
    }
    required_new = {"skipIntroSeconds=601", "adBlock.domains 非字符串", "maxQuality 非法枚举", "preferredCodec 非法枚举"}

    failures = 0
    print("== 正例（必须通过） ==")
    for label, doc in {"v1 老配置": v1, "v1.1 新字段配置": v11, "v1.1 m3u 频道定义": m3u}.items():
        errs = _errors(validator, doc)
        if errs:
            print(f"  [FAIL] {label} 应通过却报错: {errs[0][:100]}")
            failures += 1
        else:
            print(f"  [ok]   {label} 通过")

    def run_group(title: str, cases: dict[str, dict]) -> None:
        nonlocal failures
        print(f"== {title}（{len(cases)} 个，必须全部拦截） ==")
        for label, doc in cases.items():
            errs = _errors(validator, doc)
            if not errs:
                print(f"  [FAIL] 未被拦截: {label}")
                failures += 1
            else:
                print(f"  [ok]   已拦截 {label}: {errs[0][:88]}")

    run_group("旧负例 v1", old_cases)
    run_group("新负例 v1.1", new_cases)

    # 广告域名匹配规则的单元检查（纯函数，防止规则实现回退）
    print("== 域名规则单元检查 ==")
    rule_cases = [
        ("ads.doubleclick.net", "doubleclick.net", True),
        ("doubleclick.net", "doubleclick.net", True),
        ("x.admaster.com.cn", "*.admaster.com.cn", True),
        ("notadmaster.com.cn", "*.admaster.com.cn", False),
        ("doubleclick.net.evil.com", "doubleclick.net", False),
    ]
    for host, rule, want in rule_cases:
        got = domain_rule_matches(host, rule)
        if got != want:
            print(f"  [FAIL] {host} vs {rule}: got {got} want {want}")
            failures += 1
        else:
            print(f"  [ok]   {host} vs {rule} -> {got}")
    if failures:
        print(f"selftest FAILED: {failures} 项")
        return 1
    print(
        f"selftest: 旧负例 {len(old_cases)}/{len(old_cases)} 拦截，"
        f"新负例 {len(new_cases)}/{len(new_cases)} 拦截，正例 3/3 通过 ✅"
    )
    print("  （任务要求的 4 个新负例均已覆盖：" + "、".join(sorted(required_new)) + "）")
    return 0


def main(argv: list[str]) -> int:
    # Windows 控制台默认 GBK，强制 UTF-8 以正常输出中文与符号
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    if not argv:
        print(__doc__)
        return 2
    validator = load_schema()
    if argv == ["--selftest"]:
        return selftest(validator)

    failed = 0
    for arg in argv:
        path = Path(arg)
        problems = validate_file(path, validator)
        doc = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        count = len(doc.get("sources", [])) if doc.get("kind") == "bundle" else 1
        if problems:
            failed += 1
            print(f"✗ {path} ({count} 源): {len(problems)} 个问题")
            for p in problems:
                print(f"  - {p}")
        else:
            print(f"✓ {path} ({count} 源): 通过 L1 schema + L2 语义校验")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
