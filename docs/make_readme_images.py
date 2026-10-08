"""Builds the README images in docs/images/ from the render tests' output.

    ./gradlew testDebugUnitTest      # renders app/build/{widget,app}-shots/
    py docs/make_readme_images.py

Every screen and widget pixel comes from the real app code rendered by
Robolectric; this script only frames, labels and arranges them. Needs Pillow.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WIDGETS = os.path.join(ROOT, "app", "build", "widget-shots")
SCREENS = os.path.join(ROOT, "app", "build", "app-shots")
OUT = os.path.join(ROOT, "docs", "images")

# The shots are rendered at xxhdpi: 3 px per dp.
DP = 3
APP_BG = (16, 16, 16)
ACCENT = (59, 226, 154)
LINE = (140, 148, 158)


def font(size, bold=False):
    names = ["segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"] if bold else \
        ["segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"]
    for name in names:
        for folder in ("C:/Windows/Fonts", "/usr/share/fonts/truetype/dejavu", "/Library/Fonts"):
            path = os.path.join(folder, name)
            if os.path.exists(path):
                return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def load(folder, name):
    path = os.path.join(folder, name + ".png")
    if not os.path.exists(path):
        sys.exit(f"missing {path}: run ./gradlew testDebugUnitTest first")
    return Image.open(path).convert("RGBA")


def widget(name):
    """A widget render with its transparent margin trimmed."""
    img = load(WIDGETS, name)
    return img.crop(img.getchannel("A").getbbox())


def scaled(img, width):
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def wallpaper(size):
    """Dark teal wallpaper with soft light blobs, like a typical phone background."""
    w, h = size
    base = Image.new("RGB", size, (10, 22, 30))
    grad = Image.linear_gradient("L").resize(size)
    base = Image.composite(Image.new("RGB", size, (6, 12, 24)), Image.new("RGB", size, (14, 48, 52)), grad)
    blobs = Image.new("RGB", size, (0, 0, 0))
    d = ImageDraw.Draw(blobs)
    d.ellipse((-w * 0.3, -h * 0.1, w * 0.7, h * 0.45), fill=(18, 110, 90))
    d.ellipse((w * 0.4, h * 0.55, w * 1.4, h * 1.1), fill=(30, 50, 110))
    blobs = blobs.filter(ImageFilter.GaussianBlur(w * 0.18))
    return Image.blend(base, blobs, 0.55).convert("RGBA")


def status_bar(img, height, color=(255, 255, 255)):
    d = ImageDraw.Draw(img)
    f = font(round(height * 0.42), bold=True)
    d.text((height * 0.75, height / 2), "9:41", font=f, fill=color, anchor="lm")
    # Signal, wifi and battery, drawn as simple shapes.
    x = img.width - height * 0.75
    bw, bh = height * 0.62, height * 0.32
    d.rounded_rectangle((x - bw, height / 2 - bh / 2, x, height / 2 + bh / 2), radius=bh * 0.25, outline=color, width=max(2, height // 24))
    d.rectangle((x - bw + 4, height / 2 - bh / 2 + 4, x - bw * 0.25, height / 2 + bh / 2 - 4), fill=color)
    x -= bw + height * 0.3
    for i in range(4):
        bar = bh * (0.35 + 0.22 * i)
        d.rectangle((x - (4 - i) * height * 0.13, height / 2 + bh / 2 - bar, x - (4 - i) * height * 0.13 + height * 0.08, height / 2 + bh / 2), fill=color)


def phone(screen, width, status_color=APP_BG):
    """Puts a screen into a simple phone frame with a status bar on top."""
    bar = round(screen.width * 0.06)
    canvas = Image.new("RGBA", (screen.width, screen.height + bar), status_color + (255,))
    canvas.paste(screen, (0, bar))
    status_bar(canvas, bar)
    bezel = round(canvas.width * 0.028)
    radius = round(canvas.width * 0.085)
    frame = Image.new("RGBA", (canvas.width + bezel * 2, canvas.height + bezel * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle((0, 0, frame.width - 1, frame.height - 1), radius + bezel, fill=(8, 8, 8), outline=(70, 74, 80), width=max(3, bezel // 4))
    frame.paste(canvas, (bezel, bezel), rounded_mask(canvas.size, radius))
    # Punch-hole camera.
    cx, cy, r = frame.width / 2, bezel + bar / 2, bar * 0.16
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(0, 0, 0))
    return scaled(frame, width)


def home_screen():
    """The widgets as they sit on a 412x915dp phone home screen."""
    w, h = 412 * DP, 915 * DP
    screen = wallpaper((w, h))
    y = 40 * DP
    for name, gap in (("full-4x4", 18), ("compact-4x2", 18), ("tiny-4x1", 0)):
        img = widget(name)
        screen.alpha_composite(img, ((w - img.width) // 2, y))
        y += img.height + gap * DP
    # Dock: this app's icon among neutral placeholders, above a search pill.
    icon = scaled(Image.open(os.path.join(ROOT, "play", "icon-512.png")).convert("RGBA"), 58 * DP)
    icon.putalpha(Image.composite(icon.getchannel("A"), Image.new("L", icon.size, 0), rounded_mask(icon.size, 29 * DP)))
    # Translucent shapes go on their own layer so they blend with the wallpaper.
    glass = Image.new("RGBA", screen.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(glass)
    dock_y = h - 150 * DP
    xs = [w * (i + 1) / 6 for i in range(5)]
    for i, x in enumerate(xs):
        if i == 2:
            glass.alpha_composite(icon, (round(x - icon.width / 2), dock_y))
        else:
            d.ellipse((x - 29 * DP, dock_y, x + 29 * DP, dock_y + 58 * DP), fill=(255, 255, 255, 38))
    d.rounded_rectangle((24 * DP, h - 70 * DP, w - 24 * DP, h - 22 * DP), radius=24 * DP, fill=(255, 255, 255, 46))
    d.text((48 * DP, h - 46 * DP), "Search", font=font(16 * DP), fill=(255, 255, 255, 170), anchor="lm")
    screen.alpha_composite(glass)
    return screen


def hero():
    home = phone(home_screen(), 560, status_color=(10, 22, 30))
    app = phone(load(SCREENS, "rate"), 560)
    gap = 70
    img = Image.new("RGBA", (home.width + app.width + gap, max(home.height, app.height)), (0, 0, 0, 0))
    img.alpha_composite(home, (0, 0))
    img.alpha_composite(app, (home.width + gap, 0))
    return img


def anatomy():
    """The full widget with numbered callouts; the README table explains each number."""
    src = load(WIDGETS, "full-4x4")
    margin = 140
    img = Image.new("RGBA", (src.width + margin * 2, src.height), (0, 0, 0, 0))
    img.alpha_composite(src, (margin, 0))
    d = ImageDraw.Draw(img)
    # (number, side, target x, target y) in full-4x4.png pixels.
    callouts = [
        (1, "L", 128, 178), (2, "R", 1010, 176),
        (3, "L", 128, 330), (4, "R", 1010, 328),
        (5, "L", 128, 438), (6, "R", 1010, 437),
        (7, "L", 128, 555), (8, "R", 1010, 548),
        (9, "L", 128, 706), (10, "R", 1010, 870),
        (11, "L", 128, 1066),
    ]
    f = font(34, bold=True)
    r = 30
    for n, side, tx, ty in callouts:
        tx += margin
        bx = 50 if side == "L" else img.width - 50
        d.line((bx, ty, tx, ty), fill=LINE, width=4)
        d.ellipse((tx - 8, ty - 8, tx + 8, ty + 8), fill=LINE)
        d.ellipse((bx - r, ty - r, bx + r, ty + r), fill=(20, 140, 92))
        d.text((bx, ty), str(n), font=f, fill=(255, 255, 255), anchor="mm")
    return scaled(img, 760)


def labelled_row(names_labels, height, title_size=40):
    """Widgets on a wallpaper strip, each with a caption underneath."""
    imgs = [(widget(n), label) for n, label in names_labels]
    pad, gap, cap = 70, 60, 90
    scale = height / max(i.height for i, _ in imgs)
    imgs = [(i.resize((round(i.width * scale), round(i.height * scale)), Image.LANCZOS), l) for i, l in imgs]
    width = pad * 2 + sum(i.width for i, _ in imgs) + gap * (len(imgs) - 1)
    canvas = wallpaper((width, pad * 2 + height + cap))
    d = ImageDraw.Draw(canvas)
    x = pad
    f = font(title_size, bold=True)
    for i, label in imgs:
        canvas.alpha_composite(i, (x, pad + height - i.height))
        d.text((x + i.width / 2, pad + height + cap / 2 + 10), label, font=f, fill=(255, 255, 255, 230), anchor="mm")
        x += i.width + gap
    return canvas.convert("RGB")


def sizes():
    """Every layout at true relative scale, bottom-aligned like a home screen grid."""
    rows = [
        [("full-4x4", "4 x 4 · full card"), ("full-narrow-tall", "3 x 5 · tall")],
        [("compact-4x2", "4 x 2 · compact"), ("compact-3x2", "3 x 2 · compact")],
        [("tiny-4x1", "4 x 1 · strip"), ("tiny-2x1", "2 x 1 · small")],
    ]
    pad, gap, cap = 80, 70, 100
    f = font(46, bold=True)
    built = []
    for row in rows:
        imgs = [(widget(n), l) for n, l in row]
        built.append((imgs, max(i.height for i, _ in imgs)))
    width = pad * 2 + max(sum(i.width for i, _ in r) + gap * (len(r) - 1) for r, _ in built)
    height = pad * 2 + sum(h + cap for _, h in built) + gap * (len(built) - 1)
    canvas = wallpaper((width, height))
    d = ImageDraw.Draw(canvas)
    y = pad
    for imgs, row_h in built:
        x = pad
        for img, label in imgs:
            canvas.alpha_composite(img, (x, y + row_h - img.height))
            d.text((x + img.width / 2, y + row_h + cap / 2 + 10), label, font=f, fill=(255, 255, 255, 230), anchor="mm")
            x += img.width + gap
        y += row_h + cap + gap
    return scaled(canvas, 1100).convert("RGB")


def themes():
    """The same compact widget in every colour theme, three to a row."""
    names = [("emerald", "Emerald"), ("ocean", "Ocean"), ("amethyst", "Amethyst"),
             ("sunset", "Sunset"), ("rose", "Rose"), ("gold", "Gold"),
             ("graphite", "Graphite"), ("midnight", "Midnight (OLED)"), ("ocean-50pct", "Ocean, 50% background")]
    imgs = [(widget(f"theme-{n}"), label) for n, label in names]
    pad, gap, cap, cols = 70, 50, 90, 3
    w, h = imgs[0][0].size
    rows = (len(imgs) + cols - 1) // cols
    canvas = wallpaper((pad * 2 + cols * w + (cols - 1) * gap, pad * 2 + rows * (h + cap) + (rows - 1) * gap))
    d = ImageDraw.Draw(canvas)
    f = font(42, bold=True)
    for i, (img, label) in enumerate(imgs):
        x = pad + (i % cols) * (w + gap)
        y = pad + (i // cols) * (h + cap + gap)
        canvas.alpha_composite(img, (x, y))
        d.text((x + w / 2, y + h + cap / 2 + 10), label, font=f, fill=(255, 255, 255, 230), anchor="mm")
    return scaled(canvas, 1200).convert("RGB")


def save(img, name):
    path = os.path.join(OUT, name)
    img.save(path, optimize=True)
    print(f"{name:28} {img.width}x{img.height}  {os.path.getsize(path) // 1024} KB")


def main():
    os.makedirs(OUT, exist_ok=True)
    save(hero(), "hero.png")
    save(anatomy(), "widget-anatomy.png")
    save(sizes(), "widget-sizes.png")
    save(scaled(labelled_row([("full-4x4", "Day"), ("full-4x4-week", "Week"), ("full-4x4-month", "Month"),
                              ("full-4x4-buy", "Buy rate")], 1000), 1400), "widget-views.png")
    save(scaled(labelled_row([("compact-4x2", "Up to date"), ("compact-4x2-stale", "Stale: no update for 3 hours")], 500), 1100),
         "widget-stale.png")

    save(scaled(labelled_row([("fx-full-4x4", "Full card"), ("fx-compact-4x2", "Compact"),
                              ("fx-tiny-4x1", "Strip")], 1000), 1400), "widget-fx.png")
    save(themes(), "widget-themes.png")

    for name in ("rate", "currencies", "ads", "alerts", "settings", "rate-ocean"):
        save(phone(load(SCREENS, name), 400), f"screen-{name}.png")
    # The converter working from rupees: just the top of the screen.
    lkr = load(SCREENS, "rate-lkr")
    save(phone(lkr.crop((0, 0, lkr.width, 1690)), 400), "screen-converter.png")
    # The whole Rate page, trimmed where the content ends.
    full = load(SCREENS, "rate-full").convert("RGB")
    bg = full.getpixel((5, full.height - 5))
    rows = [y for y in range(full.height) if any(abs(c - b) > 6 for px in [full.getpixel((x, y)) for x in range(0, full.width, 12)] for c, b in zip(px, bg))]
    full = full.crop((0, 0, full.width, min(full.height, rows[-1] + 60)))
    full = full.convert("RGBA")
    full.putalpha(rounded_mask(full.size, 60))
    save(scaled(full, 420), "screen-rate-full.png")


if __name__ == "__main__":
    main()
