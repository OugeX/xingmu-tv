#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""导出最终品牌资源：mipmap 各密度图标 + TV banner（320×180 / 640×360）。
品牌：星幕（方案 A）——金色渐变 + 深色屏幕 + 白色播放三角 + 星芒。
用法: python design/export_brand.py
"""
import os
from PIL import Image, ImageDraw, ImageFont
import make_icons as mi

OUT = "app/src/main/res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
FONT = "C:/Windows/Fonts/msyh.ttc"


def banner_640(icon):
    im = Image.new("RGB", (640, 360), (10, 10, 12))
    d = ImageDraw.Draw(im)
    # 背景微光
    glow = Image.new("RGB", (640, 360), (10, 10, 12))
    gd = ImageDraw.Draw(glow)
    for r in range(300, 0, -12):
        a = int(26 * (1 - r / 300))
        gd.ellipse([560 - r, 40 - r, 560 + r, 40 + r], fill=(10 + a, 8 + int(a * .7), 6))
    im.paste(glow, (0, 0))
    ic = icon.resize((200, 200), Image.LANCZOS)
    im.paste(ic, (44, 80), ic)
    f1 = ImageFont.truetype(FONT_B, 76)
    f2 = ImageFont.truetype(FONT, 26)
    d.text((276, 108), "星幕", font=f1, fill=(232, 184, 114))
    d.text((280, 202), "直播 · 电影 · 剧集 · 动漫", font=f2, fill=(160, 151, 141))
    return im


def main():
    icon = mi.icon_a()
    for dens, size in DENSITIES.items():
        d = f"{OUT}/mipmap-{dens}"
        os.makedirs(d, exist_ok=True)
        icon.resize((size, size), Image.LANCZOS).save(f"{d}/ic_launcher.png")
        # 圆角方形图标（部分启动器用 round）
        icon.resize((size, size), Image.LANCZOS).save(f"{d}/ic_launcher_round.png")
    b = banner_640(icon)
    os.makedirs(f"{OUT}/drawable-xhdpi", exist_ok=True)
    os.makedirs(f"{OUT}/drawable-xxhdpi", exist_ok=True)
    b.resize((320, 180), Image.LANCZOS).save(f"{OUT}/drawable-xhdpi/tv_banner.png")
    b.save(f"{OUT}/drawable-xxhdpi/tv_banner.png")
    # 应用内 logo（圆角 96dp 用）
    os.makedirs(f"{OUT}/drawable-xxhdpi", exist_ok=True)
    icon.resize((192, 192), Image.LANCZOS).save(f"{OUT}/drawable-xxhdpi/ic_logo.png")
    print("brand assets exported")
    for root, _, files in os.walk(OUT):
        for f in files:
            if "ic_launcher" in f or "tv_banner" in f or "ic_logo" in f:
                print(" ", os.path.join(root, f).replace("\\", "/"))


if __name__ == "__main__":
    main()
