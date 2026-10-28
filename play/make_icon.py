"""Renders the 512x512 Google Play icon from the same geometry as the adaptive
launcher icon (app/src/main/res/drawable/ic_launcher_*.xml).

    py play/make_icon.py

Needs Pillow and numpy. Output: play/icon-512.png
"""
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SIZE = 512
SS = 4  # supersampling
W = SIZE * SS
# The launcher icon's 108-unit viewport, cropped to its central 80 units so
# the mark fills the store tile the way the launcher's mask frames it.
CROP = 14
UNIT = W / (108 - 2 * CROP)


def p(x, y):
    return ((x - CROP) * UNIT, (y - CROP) * UNIT)


def layer():
    return Image.new("RGBA", (W, W), (0, 0, 0, 0))


# Background: the same diagonal gradient as ic_launcher_background.xml.
yy, xx = np.mgrid[0:W, 0:W].astype(np.float32)
t = np.clip((xx + yy) / (2 * W), 0, 1)[..., None]
top, bottom = np.array([0x14, 0xC8, 0x8E]), np.array([0x06, 0x4A, 0x36])
img = Image.fromarray((top + (bottom - top) * t).astype(np.uint8), "RGB").convert("RGBA")

# Radar sweep: a 60-degree wedge that brightens towards its leading edge.
cx, cy = p(54, 54)
r = 30 * UNIT
ang = np.degrees(np.arctan2(yy - cy, xx - cx)) % 360
inside = ((xx - cx) ** 2 + (yy - cy) ** 2 <= r * r) & (ang >= 300)
alpha = np.where(inside, (ang - 300) / 60 * 0x66, 0).astype(np.uint8)
sweep = np.zeros((W, W, 4), np.uint8)
sweep[..., :3] = 255
sweep[..., 3] = alpha
img.alpha_composite(Image.fromarray(sweep, "RGBA"))

rings = layer()
d = ImageDraw.Draw(rings)
for radius, a in ((30, 0x40), (20, 0x4D), (10, 0x59)):
    rr = radius * UNIT
    d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=(255, 255, 255, a), width=round(2.2 * UNIT))
img.alpha_composite(rings)

# The rising rate line and its blip, with a soft glow underneath.
line = [p(28, 70), p(42, 60), p(53, 65), p(76, 40)]
glow = layer()
ImageDraw.Draw(glow).line(line, fill=(255, 255, 255, 120), width=round(9 * UNIT), joint="curve")
img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(5 * UNIT)))

bx, by = line[-1]
halo = layer()
rr = 8 * UNIT
ImageDraw.Draw(halo).ellipse([bx - rr, by - rr, bx + rr, by + rr], fill=(255, 255, 255, 0x4D))
img.alpha_composite(halo)

d = ImageDraw.Draw(img)
d.line(line, fill="white", width=round(5.5 * UNIT), joint="curve")
for x, y in line[:1]:
    rr = 2.75 * UNIT
    d.ellipse([x - rr, y - rr, x + rr, y + rr], fill="white")
rr = 4.5 * UNIT
d.ellipse([bx - rr, by - rr, bx + rr, by + rr], fill="white")

img.resize((SIZE, SIZE), Image.LANCZOS).convert("RGB").save(os.path.join(HERE, "icon-512.png"))
print("wrote icon-512.png")
