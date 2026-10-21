#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成首拼/全拼检索用的汉字→拼音映射，供客户端本地建索引。
产出:
  assets/pinyin_initials.json  {"汉":"h", ...}      单字首字母（GB2312 一级+二级，约 6763 字）
  assets/pinyin_full.json      {"汉":"han", ...}    全拼（用于全拼检索与候选词）
用法: python tools/gen_pinyin.py
"""
import json, sys
from pypinyin import pinyin, Style


def gb2312_chars():
    out = []
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                ch = bytes([hi, lo]).decode("gb2312")
            except UnicodeDecodeError:
                continue
            out.append(ch)
    return out


def main():
    chars = sorted(set(gb2312_chars()))
    initials, full = {}, {}
    for ch in chars:
        try:
            polys = pinyin(ch, style=Style.NORMAL, heteronym=True)[0]
        except Exception:
            continue
        polys = [p.lower() for p in polys if p and p.isalpha()]
        if not polys:
            continue
        # 多音字存全部候选首字母（去重、按拼音顺序），如 长 -> "cz"
        initials[ch] = "".join(sorted({p[0] for p in polys}))
        full[ch] = "|".join(sorted(set(polys)))

    json.dump(initials, open("assets/pinyin_initials.json", "w", encoding="utf-8"),
              ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    json.dump(full, open("assets/pinyin_full.json", "w", encoding="utf-8"),
              ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    # 自检：多音字必须两种首拼都能命中
    def variants(word):
        seqs = [initials.get(c, "?") for c in word]
        out = [""]
        for s in seqs:
            out = [a + ch for a in out for ch in s][:64]
        return out
    for word, expects in [("流浪地球", ["lldq"]), ("漫长的季节", ["mcdjj", "mzdjj"]),
                          ("繁花", ["fh"]), ("鬼灭之刃", ["gmzr"]), ("庆余年", ["qyn"]),
                          ("长安三万里", ["canswl", "zanswl"])]:
        got = variants(word)
        ok = all(e in got for e in expects)
        print(f"{word} -> {got[:4]} (期望含 {expects}) {'OK' if ok else 'MISMATCH'}")
    print(f"\n字符数 {len(initials)}，输出 assets/pinyin_initials.json / assets/pinyin_full.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
