#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 3 套 APP 图标（512×512）+ 每套的 TV banner（320×180），输出拼版预览图。
仅用于设计评审；最终实现时再导出 mipmap/webp。
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W = 512
GOLD = (232, 184, 114)
GOLD_DARK = (138, 90, 30)
BG = (10, 10, 12)
WHITE = (255, 255, 255)
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
FONT = "C:/Windows/Fonts/msyh.ttc"


def rounded(size, radius, color):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=color)
    return im


def gradient(size, c1, c2, horizontal=False):
    base = Image.new("RGB", (size, size), c1)
    top = Image.new("RGB", (size, size), c2)
    mask = Image.new("L", (size, size))
    md = ImageDraw.Draw(mask)
    for i in range(size):
        v = int(255 * i / (size - 1))
        if horizontal:
            md.line([(i, 0), (i, size)], fill=v)
        else:
            md.line([(0, i), (size, i)], fill=v)
    base.paste(top, (0, 0), mask)
    return base


def icon_a():
    """A 星幕：金色渐变圆角方 + 深色屏幕 + 白色播放三角 + 星芒"""
    im = gradient(W, GOLD, GOLD_DARK, horizontal=True).convert("RGBA")
    im.putalpha(rounded(W, 116, (255, 255, 255, 255)).split()[3])
    d = ImageDraw.Draw(im)
    # 深色屏幕
    d.rounded_rectangle([84, 132, 428, 344], radius=26, fill=(18, 14, 10, 255))
    # 底座
    d.rounded_rectangle([196, 356, 316, 378], radius=11, fill=(18, 14, 10, 255))
    # 白色播放三角
    d.polygon([(232, 190), (232, 292), (320, 241)], fill=(255, 255, 255, 250))
    # 星芒
    cx, cy, r = 430, 116, 40
    d.polygon([(cx, cy - r), (cx + r * .3, cy - r * .3), (cx + r, cy), (cx + r * .3, cy + r * .3),
               (cx, cy + r), (cx - r * .3, cy + r * .3), (cx - r, cy), (cx - r * .3, cy - r * .3)],
              fill=(255, 255, 255, 245))
    return im


def icon_b():
    """B 影匣：深色底 + 金色胶片齿孔 + 金色播放三角"""
    im = rounded(W, 116, (16, 16, 20, 255))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([8, 8, W - 9, W - 9], radius=110, outline=(232, 184, 114, 190), width=6)
    # 胶片齿孔
    for y in range(70, 460, 52):
        d.rounded_rectangle([40, y, 78, y + 30], radius=8, fill=(232, 184, 114, 210))
        d.rounded_rectangle([434, y, 472, y + 30], radius=8, fill=(232, 184, 114, 210))
    # 中心播放
    d.polygon([(206, 168), (206, 344), (356, 256)], fill=(232, 184, 114, 255))
    return im


def icon_c():
    """C 幕光：纯黑底 + 金色屏幕弧光 + 白色三角"""
    im = rounded(W, 116, (0, 0, 0, 255))
    d = ImageDraw.Draw(im)
    # 幕布弧
    for i, r in enumerate((190, 158, 126)):
        box = [W / 2 - r, W / 2 - r + 10, W / 2 + r, W / 2 + r + 10]
        d.arc(box, start=200, end=340, fill=(232, 184, 114, 60 + i * 55), width=14)
    # 屏幕线
    d.rounded_rectangle([96, 300, 416, 312], radius=6, fill=(232, 184, 114, 230))
    d.polygon([(230, 196), (230, 288), (312, 242)], fill=(255, 255, 255, 245))
    return im


def banner(icon, name, sub):
    im = Image.new("RGB", (640, 360), BG)
    d = ImageDraw.Draw(im)
    im.paste(icon.resize((190, 190), Image.LANCZOS), (40, 85), icon.resize((190, 190), Image.LANCZOS))
    f1 = ImageFont.truetype(FONT_B, 62)
    f2 = ImageFont.truetype(FONT, 24)
    d.text((258, 118), name, font=f1, fill=GOLD)
    d.text((262, 200), sub, font=f2, fill=(168, 159, 149))
    return im


def main():
    icons = [("A·星幕", icon_a(), "星幕", "金色幕布 + 播放"), ("B·影匣", icon_b(), "影匣", "胶片齿孔 + 播放"),
             ("C·幕光", icon_c(), "幕光", "暗场弧光 + 播放")]
    pad, label_h = 56, 90
    sheet = Image.new("RGB", (pad * 4 + W * 3, 60 + W + label_h + 400), (10, 10, 12))
    d = ImageDraw.Draw(sheet)
    d.text((pad, 14), "APP 图标方案（512×512）+ TV banner 预览（640×360，实机为 320×180）", font=ImageFont.truetype(FONT_B, 30), fill=GOLD)
    f_name = ImageFont.truetype(FONT_B, 28)
    f_desc = ImageFont.truetype(FONT, 20)
    for i, (label, ic, name, desc) in enumerate(icons):
        x = pad + i * (W + pad)
        sheet.paste(ic, (x, 70), ic)
        d.text((x, 70 + W + 16), label, font=f_name, fill=(240, 232, 220))
        d.text((x, 70 + W + 52), desc, font=f_desc, fill=(140, 132, 122))
        b = banner(ic, name, "直播 · 电影 · 剧集 · 动漫")
        sheet.paste(b.resize((W - 100, (W - 100) // 2), Image.LANCZOS), (x + 50, 70 + W + label_h + 10))
    sheet.save("design/mockups/app_icon_options.png")
    print("saved design/mockups/app_icon_options.png", sheet.size)


if __name__ == "__main__":
    main()
