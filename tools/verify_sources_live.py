#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""并发探测直播种子里的频道 URL，输出可用率报告。

数据源：assets/live_seed.json（不存在时使用脚本内置示例，保证离线也能跑通）
判定：HTTP 200（跟随重定向）且响应体前 2KB 去掉 BOM/空白后以 #EXTM3U 开头。
输出：assets/live_probe_report.json

用法：
    python tools/verify_sources_live.py                 # 全量探测
    python tools/verify_sources_live.py --limit 10      # 只探测前 10 个频道
    python tools/verify_sources_live.py --dry-run       # 只解析种子，不发请求
    python tools/verify_sources_live.py --workers 32 --timeout 6
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlsplit, urlunsplit

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SEED = os.path.join(ROOT, "assets", "live_seed.json")
DEFAULT_OUT = os.path.join(ROOT, "assets", "live_probe_report.json")
READ_BYTES = 2048
TZ = timezone(timedelta(hours=8))

UA = (
    "Mozilla/5.0 (Linux; Android 9; TV Box) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
)
HEADERS = {
    "User-Agent": UA,
    "Accept": "application/vnd.apple.mpegurl,application/x-mpegURL,text/plain,*/*",
    "Referer": "https://bfzy1.tv/",
}

# 种子缺失时的内置示例（仅用于让脚本在空工程里也能跑通；不代表真实可用性）
BUILTIN_SEED = {
    "schema_version": 1,
    "name": "builtin_fallback",
    "channels": [
        {
            "id": "cctv1",
            "name": "CCTV-1 综合",
            "group": "央视频道",
            "group_id": "cctv",
            "urls": [
                "https://cd-live-stream.news.cctvplus.com/live/smil:CHANNEL1.smil/playlist.m3u8"
            ],
        },
        {
            "id": "cctvplus2",
            "name": "CCTV+ 2",
            "group": "央视频道",
            "group_id": "cctv",
            "urls": [
                "https://cd-live-stream.news.cctvplus.com/live/smil:CHANNEL2.smil/playlist.m3u8"
            ],
        },
        {
            "id": "hls-test",
            "name": "HLS 公共测试流",
            "group": "测试",
            "group_id": "test",
            "urls": ["https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"],
        },
    ],
}


def now_iso() -> str:
    return datetime.now(TZ).isoformat(timespec="seconds")


def load_seed(path: str) -> tuple[dict, bool, str]:
    """返回 (seed, used_fallback, note)。"""
    if not os.path.isfile(path):
        return BUILTIN_SEED, True, f"种子不存在，使用内置示例: {path}"
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        raise SystemExit(f"[FAIL] 读取种子失败 {path}: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("channels"), list):
        raise SystemExit(f"[FAIL] 种子格式非法（缺少 channels 数组）: {path}")
    return data, False, ""


def collect_candidates(channel: dict) -> list[dict]:
    """一个频道可能有多个源；保留 url/quality/priority 元数据并去重。"""
    out: list[dict] = []
    seen: set[str] = set()

    def add(url, meta=None):
        if not isinstance(url, str):
            return
        url = url.strip()
        if not url or url in seen:
            return
        seen.add(url)
        item = {"url": url}
        if isinstance(meta, dict):
            for key in ("protocol", "quality", "priority", "fallback_only", "stability"):
                if meta.get(key) is not None:
                    item[key] = meta[key]
        out.append(item)

    for source in channel.get("sources") or []:
        if isinstance(source, dict):
            add(source.get("url"), source)
        elif isinstance(source, str):
            add(source)
    for url in channel.get("urls") or []:
        add(url)
    add(channel.get("url"))
    return out


def classify_error(exc: BaseException) -> str:
    if isinstance(exc, urllib.error.URLError):
        reason = exc.reason
        if isinstance(reason, (socket.timeout, TimeoutError)):
            return "timeout"
        if isinstance(reason, socket.gaierror):
            return "dns_error"
        if isinstance(reason, ConnectionRefusedError):
            return "connection_refused"
        if isinstance(reason, ssl.SSLError):
            return "tls_error"
        return f"url_error:{reason}"
    if isinstance(exc, (socket.timeout, TimeoutError)):
        return "timeout"
    if isinstance(exc, ssl.SSLError):
        return "tls_error"
    if isinstance(exc, ConnectionRefusedError):
        return "connection_refused"
    return f"error:{type(exc).__name__}"


def normalize_url(url: str) -> str:
    """把中文路径/域名转成可发送的 ASCII URL（种子里有 `地方台8563/海南.php` 这类地址）。"""
    try:
        parts = urlsplit(url)
    except ValueError:
        return url
    if not parts.scheme or not parts.netloc:
        return url
    netloc = parts.netloc
    host = parts.hostname
    if host and any(ord(ch) > 127 for ch in host):
        try:
            ascii_host = host.encode("idna").decode("ascii")
            netloc = netloc.replace(host, ascii_host)
        except UnicodeError:
            pass
    path = quote(parts.path, safe="/%:@!$&'()*+,;=-._~")
    query = quote(parts.query, safe="=&?/%:@!$'()*+,;.-_~")
    fragment = quote(parts.fragment, safe="%")
    return urlunsplit((parts.scheme, netloc, path, query, fragment))


def probe_url(url: str, timeout: float) -> dict:
    request_url = normalize_url(url)
    rec = {
        "url": url,
        "request_url": request_url,
        "ok": False,
        "http": None,
        "content_type": "",
        "bytes_read": 0,
        "latency_ms": None,
        "reason": "",
        "first_line": "",
        "final_url": "",
    }
    started = time.perf_counter()
    ctx = ssl.create_default_context()
    # 大量 IPTV 源使用自签证书/裸 IP；探测只关心能否拿到播放列表，不做信任判定
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        req = urllib.request.Request(request_url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            rec["http"] = getattr(resp, "status", None)
            rec["content_type"] = resp.headers.get("Content-Type", "") or ""
            rec["final_url"] = resp.geturl()
            body = resp.read(READ_BYTES)
            rec["bytes_read"] = len(body)
    except urllib.error.HTTPError as exc:
        rec["http"] = exc.code
        rec["latency_ms"] = int((time.perf_counter() - started) * 1000)
        rec["reason"] = f"http_{exc.code}"
        try:
            snippet = exc.read(256)
            line = snippet.decode("utf-8", "replace").splitlines()
            rec["first_line"] = line[0][:120] if line else ""
        except Exception:  # noqa: BLE001 - 错误体仅用于诊断
            pass
        return rec
    except BaseException as exc:  # noqa: BLE001 - 逐 URL 记录，不中断整批
        rec["latency_ms"] = int((time.perf_counter() - started) * 1000)
        rec["reason"] = classify_error(exc)
        return rec

    rec["latency_ms"] = int((time.perf_counter() - started) * 1000)
    stripped = body.lstrip(b"\xef\xbb\xbf \t\r\n")
    playlist_ok = stripped.startswith(b"#EXTM3U")
    http_ok = rec["http"] == 200
    if http_ok and playlist_ok:
        rec["ok"] = True
        rec["reason"] = "ok"
    elif not http_ok and playlist_ok:
        rec["reason"] = f"http_{rec['http']}"
    elif http_ok and not playlist_ok:
        rec["reason"] = "not_m3u8"
    else:
        rec["reason"] = f"http_{rec['http']}_not_m3u8"

    text = stripped[:300].decode("utf-8", "replace")
    lines = text.splitlines()
    rec["first_line"] = lines[0][:120] if lines else ""
    return rec


def rate(part: int, total: int) -> float:
    return round(part / total, 4) if total else 0.0


def main() -> int:
    parser = argparse.ArgumentParser(description="并发探测直播种子频道 URL 可用率")
    parser.add_argument("--seed", default=DEFAULT_SEED, help="种子文件（默认 assets/live_seed.json）")
    parser.add_argument("--out", default=DEFAULT_OUT, help="报告输出路径（默认 assets/live_probe_report.json）")
    parser.add_argument("--workers", type=int, default=24, help="并发数（默认 24）")
    parser.add_argument("--timeout", type=float, default=8.0, help="单 URL 超时秒数（默认 8）")
    parser.add_argument("--limit", type=int, default=0, help="只探测前 N 个频道（0=全部）")
    parser.add_argument("--channel", default="", help="只探测 id/name 包含该子串的频道")
    parser.add_argument("--dry-run", action="store_true", help="只解析种子并打印计划，不发请求")
    parser.add_argument("--min-rate", type=float, default=0.0, help="频道可用率低于该值(0-1)时退出码 1")
    args = parser.parse_args()

    seed_path = os.path.abspath(args.seed)
    seed, used_fallback, note = load_seed(seed_path)
    channels = seed.get("channels") or []
    if args.channel:
        needle = args.channel.lower()
        channels = [
            ch
            for ch in channels
            if needle in str(ch.get("id", "")).lower() or needle in str(ch.get("name", "")).lower()
        ]
    if args.limit and args.limit > 0:
        channels = channels[: args.limit]

    prepared = []
    for ch in channels:
        prepared.append((ch, collect_candidates(ch)))
    total_urls = sum(len(urls) for _, urls in prepared)

    print(f"种子: {seed_path}")
    print(f"名称: {seed.get('name', '?')}  频道: {len(prepared)}  URL: {total_urls}")
    if note:
        print(f"[WARN] {note}")
    if args.dry_run:
        for ch, urls in prepared[:10]:
            print(f"  - {ch.get('id')} / {ch.get('name')} / {ch.get('group', '')} / {len(urls)} URL")
        if len(prepared) > 10:
            print(f"  ... 其余 {len(prepared) - 10} 个频道省略")
        print("[DRY-RUN] 未发送网络请求。")
        return 0
    if not prepared:
        print("[FAIL] 没有可探测的频道（检查 --channel/--limit 或种子内容）", file=sys.stderr)
        return 2

    tasks = []
    for ch_index, (ch, urls) in enumerate(prepared):
        for url_meta in urls:
            tasks.append((ch_index, url_meta))

    print(f"并发探测: workers={args.workers}, timeout={args.timeout}s, 共 {len(tasks)} 个 URL ...")
    results: list[list[dict]] = [[] for _ in prepared]
    started = time.time()
    done = 0
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        future_map = {
            pool.submit(probe_url, meta["url"], args.timeout): (ch_index, meta)
            for ch_index, meta in tasks
        }
        for future in as_completed(future_map):
            ch_index, meta = future_map[future]
            try:
                rec = future.result()
            except BaseException as exc:  # noqa: BLE001 - 兜底，单任务异常不影响整批
                rec = {
                    "url": meta["url"],
                    "ok": False,
                    "http": None,
                    "reason": f"error:{type(exc).__name__}",
                    "latency_ms": None,
                }
            for key in ("protocol", "quality", "priority"):
                if meta.get(key) is not None:
                    rec[key] = meta[key]
            results[ch_index].append(rec)
            done += 1
            if done % 20 == 0 or done == len(tasks):
                sys.stdout.write(f"\r  进度 {done}/{len(tasks)}")
                sys.stdout.flush()
    print()
    elapsed = round(time.time() - started, 1)

    channel_rows = []
    source_total = 0
    source_ok = 0
    http_ok_count = 0
    prefix_ok_count = 0
    failures = []
    reason_counts: dict[str, int] = {}
    group_stats: dict[str, dict] = {}

    for ch_index, (ch, urls) in enumerate(prepared):
        probes = sorted(results[ch_index], key=lambda r: (not r.get("ok"), r.get("latency_ms") or 0))
        ch_ok = any(p.get("ok") for p in probes)
        ch_group = str(ch.get("group") or ch.get("group_id") or "未分组")
        ch_group_id = str(ch.get("group_id") or ch_group)
        source_total += len(probes)
        ok_count = sum(1 for p in probes if p.get("ok"))
        source_ok += ok_count
        http_ok_count += sum(1 for p in probes if p.get("http") == 200)
        prefix_ok_count += sum(1 for p in probes if str(p.get("first_line", "")).startswith("#EXTM3U"))

        gs = group_stats.setdefault(
            ch_group_id, {"group": ch_group, "channels": 0, "channels_usable": 0, "sources": 0, "sources_ok": 0}
        )
        gs["channels"] += 1
        gs["channels_usable"] += 1 if ch_ok else 0
        gs["sources"] += len(probes)
        gs["sources_ok"] += ok_count

        for p in probes:
            reason = str(p.get("reason", "unknown"))
            reason_counts[reason] = reason_counts.get(reason, 0) + 1
            if not p.get("ok"):
                failures.append(
                    {
                        "channel_id": ch.get("id"),
                        "channel_name": ch.get("name"),
                        "group": ch_group,
                        "url": p.get("url"),
                        "http": p.get("http"),
                        "reason": reason,
                        "latency_ms": p.get("latency_ms"),
                    }
                )

        channel_rows.append(
            {
                "id": ch.get("id"),
                "name": ch.get("name"),
                "group": ch_group,
                "group_id": ch_group_id,
                "usable": ch_ok,
                "sources_total": len(probes),
                "sources_ok": ok_count,
                "sources": probes,
            }
        )

    channels_total = len(channel_rows)
    channels_usable = sum(1 for c in channel_rows if c["usable"])
    ch_rate = rate(channels_usable, channels_total)
    src_rate = rate(source_ok, source_total)

    for gs in group_stats.values():
        gs["channel_rate"] = rate(gs["channels_usable"], gs["channels"])
        gs["source_rate"] = rate(gs["sources_ok"], gs["sources"])

    report = {
        "schema_version": 1,
        "generated_at": now_iso(),
        "seed": {
            "path": os.path.relpath(seed_path, ROOT) if seed_path.startswith(ROOT) else seed_path,
            "name": seed.get("name"),
            "schema_version": seed.get("schema_version"),
            "generated_at": seed.get("generated_at"),
            "used_builtin_fallback": used_fallback,
        },
        "probe": {
            "method": "HTTP GET（跟随重定向，读前 2KB），要求 status=200 且 body 去 BOM/空白后以 #EXTM3U 开头",
            "timeout_s": args.timeout,
            "workers": args.workers,
            "elapsed_s": elapsed,
            "user_agent": UA,
            "tls_verify": False,
            "note": "探测结果受本机网络出口影响；失败不等于源永久失效。",
        },
        "summary": {
            "channels_total": channels_total,
            "channels_usable": channels_usable,
            "channel_available_rate": ch_rate,
            "sources_total": source_total,
            "sources_ok": source_ok,
            "source_available_rate": src_rate,
            "http_200": http_ok_count,
            "playlist_extm3u": prefix_ok_count,
            "failed_sources": source_total - source_ok,
            "by_group": group_stats,
            "by_reason": dict(sorted(reason_counts.items(), key=lambda kv: -kv[1])),
        },
        "channels": channel_rows,
        "failures": failures,
    }

    out_path = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    tmp_path = out_path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    os.replace(tmp_path, out_path)

    print()
    print("========== LIVE PROBE RESULT ==========")
    print(f"耗时        : {elapsed}s")
    print(f"频道可用    : {channels_usable}/{channels_total} ({ch_rate * 100:.1f}%)")
    print(f"源可用      : {source_ok}/{source_total} ({src_rate * 100:.1f}%)")
    print(f"HTTP 200    : {http_ok_count}/{source_total}")
    print(f"含 #EXTM3U  : {prefix_ok_count}/{source_total}")
    print("分组:")
    for gid, gs in sorted(group_stats.items(), key=lambda kv: -kv[1]["channels"]):
        print(
            f"  - {gid}: 频道 {gs['channels_usable']}/{gs['channels']} "
            f"({gs['channel_rate'] * 100:.1f}%), 源 {gs['sources_ok']}/{gs['sources']}"
        )
    if failures:
        print(f"失败源示例（最多 10/{len(failures)}）:")
        for f in failures[:10]:
            print(f"  - [{f['reason']}] {f['channel_name']} {f['url']}")
    print(f"报告        : {out_path}")
    print("=======================================")

    if ch_rate < args.min_rate:
        print(f"[FAIL] 频道可用率 {ch_rate:.4f} 低于 --min-rate {args.min_rate}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
