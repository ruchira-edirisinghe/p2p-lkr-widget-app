"""Renders the 1024x500 Google Play feature graphic.

    py android/play/make_feature_graphic.py

Needs Pillow. Output: android/play/feature-graphic.png
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1024, 500
HERE = os.path.dirname(os.path.abspath(__file__))


def font(size, bold=False):
    names = ["segoeuib.ttf", "arialbd.ttf"] if bold else ["segoeui.ttf", "arial.ttf"]
    for name in names:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


# Near-black ground with the emerald glow of the widget, top-left of centre.
img = Image.new("RGB", (W, H))
px = img.load()
cx, cy, r = W * 0.30, -H * 0.1, W * 0.75
for y in range(H):
    for x in range(W):
        t = min(1.0, math.hypot(x - cx, y - cy) / r)
        a = (1 - t) ** 1.6
        px[x, y] = (19, int(19 + 109 * a), int(19 + 55 * a))

draw = ImageDraw.Draw(img)
draw.text((64, 92), "LKR P2P Rate", font=font(76, bold=True), fill="white")
draw.text((68, 190), "Live USDT/LKR P2P rate on your home screen", font=font(30), fill=(220, 220, 220))
draw.text((68, 236), "Fillable price · trend chart · rate alerts", font=font(26), fill=(160, 160, 160))

# A rising line with a soft glow, along the bottom.
points = [(60, 430), (170, 400), (280, 418), (390, 370), (500, 382), (610, 340),
          (720, 352), (830, 300), (964, 268)]
line = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ld = ImageDraw.Draw(line)
ld.line(points, fill=(59, 226, 154, 255), width=14, joint="curve")
img.paste(line.filter(ImageFilter.GaussianBlur(12)), (0, 0), line.filter(ImageFilter.GaussianBlur(12)))
ld2 = ImageDraw.Draw(img)
ld2.line(points, fill=(59, 226, 154), width=6, joint="curve")
for x in range(60, 965, 74):
    for y in range(280, 450, 12):
        ld2.line([(x, y), (x, y + 5)], fill=(52, 52, 52), width=1)
ld2.line(points, fill=(59, 226, 154), width=6, joint="curve")
ex, ey = points[-1]
ld2.ellipse([ex - 9, ey - 9, ex + 9, ey + 9], fill=(59, 226, 154))

img.save(os.path.join(HERE, "feature-graphic.png"))
print("wrote feature-graphic.png")
