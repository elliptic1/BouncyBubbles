"""Generate Play Store icon + feature graphic for BouncyBubbles.

Outputs into the same directory:
  - icon-512.png       (512x512, Play Store hi-res icon)
  - feature-graphic.png (1024x500, Play Store feature graphic)

Colors are drawn from the launcher icon: a navy background with three
gradient bubbles (pink, teal, yellow).
"""
from __future__ import annotations

import math
import os
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).parent
BG = (26, 27, 46)  # #1A1B2E

# BubblePalette top/bottom pairs sampled to feel similar to BubblePalette.kt.
PALETTE = [
    ((255, 107, 157), (255, 158, 200)),  # pink
    ((78, 205, 196), (130, 235, 220)),   # teal
    ((255, 230, 109), (255, 245, 180)),  # yellow
    ((155, 89, 255), (200, 150, 255)),   # purple
    ((255, 140, 90), (255, 195, 150)),   # peach
    ((90, 200, 255), (160, 230, 255)),   # sky
]


def draw_bubble(
    img: Image.Image,
    cx: float,
    cy: float,
    radius: float,
    color_top: tuple[int, int, int],
    color_bottom: tuple[int, int, int],
) -> None:
    """Draw a single glossy gradient bubble onto img at (cx,cy) with radius."""
    size = int(radius * 2.4)  # leave room for shadow
    sprite = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sx, sy = size / 2, size / 2

    # Shadow.
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.ellipse(
        (sx - radius + 2, sy - radius + radius * 0.18, sx + radius + 2, sy + radius + radius * 0.18),
        fill=(0, 0, 0, 90),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(radius * 0.18))
    sprite.alpha_composite(shadow)

    # Pixel-by-pixel vertical gradient body. Slower than strip drawing but
    # has no banding artifacts.
    side = max(1, int(radius * 2))
    top_img = Image.new("RGBA", (side, side), color_top + (255,))
    bot_img = Image.new("RGBA", (side, side), color_bottom + (255,))
    grad_mask = Image.new("L", (side, side))
    px = grad_mask.load()
    for y in range(side):
        v = int(round(255 * (y / max(1, side - 1))))
        for x in range(side):
            px[x, y] = v
    body = Image.composite(bot_img, top_img, grad_mask).convert("RGBA")

    # Mask body to a circle.
    mask = Image.new("L", body.size, 0)
    md = ImageDraw.Draw(mask)
    md.ellipse((0, 0, side - 1, side - 1), fill=255)
    body.putalpha(mask)

    # Rim darkening (subtle inner shadow).
    rim = Image.new("RGBA", body.size, (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim)
    rd.ellipse((0, 0, radius * 2 - 1, radius * 2 - 1), outline=(0, 0, 0, 60), width=max(1, int(radius * 0.08)))
    rim_blur = rim.filter(ImageFilter.GaussianBlur(radius * 0.04))
    body.alpha_composite(rim_blur)

    sprite.alpha_composite(body, (int(sx - radius), int(sy - radius)))

    # Specular highlight (top-left).
    hl = Image.new("RGBA", sprite.size, (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hr = radius * 0.55
    hcx = sx - radius * 0.32
    hcy = sy - radius * 0.42
    hd.ellipse((hcx - hr, hcy - hr * 0.6, hcx + hr, hcy + hr * 0.6), fill=(255, 255, 255, 180))
    hl = hl.filter(ImageFilter.GaussianBlur(radius * 0.18))
    sprite.alpha_composite(hl)

    # Pin highlight dot.
    hl2 = Image.new("RGBA", sprite.size, (0, 0, 0, 0))
    h2d = ImageDraw.Draw(hl2)
    pr = radius * 0.12
    h2d.ellipse((hcx - pr, hcy - pr, hcx + pr, hcy + pr), fill=(255, 255, 255, 230))
    hl2 = hl2.filter(ImageFilter.GaussianBlur(radius * 0.04))
    sprite.alpha_composite(hl2)

    img.alpha_composite(sprite, (int(cx - sx), int(cy - sy)))


def background_field(w: int, h: int) -> Image.Image:
    """Navy background with a faint vignette."""
    img = Image.new("RGBA", (w, h), BG + (255,))
    # Vignette: subtle radial darkening at the corners.
    vg = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    vd = ImageDraw.Draw(vg)
    for i in range(20):
        a = int(8 * (i / 20))
        vd.ellipse((-w * 0.2 + i, -h * 0.2 + i, w * 1.2 - i, h * 1.2 - i), outline=(0, 0, 0, a))
    img.alpha_composite(vg.filter(ImageFilter.GaussianBlur(40)))
    return img


def make_icon() -> None:
    """Match the adaptive icon visually but render as a flat 512x512 PNG."""
    size = 512
    img = background_field(size, size)
    # Three bubbles in roughly the same positions as ic_launcher_foreground.xml.
    # Foreground uses viewport 108 — scale center coords proportionally.
    def vp(x: float, y: float, r: float):
        return (x / 108 * size, y / 108 * size, r / 108 * size)

    cfg = [
        (vp(40, 46, 18), PALETTE[0]),  # big pink
        (vp(68, 68, 14), PALETTE[1]),  # mid teal
        (vp(70, 38, 10), PALETTE[2]),  # small yellow
    ]
    for (cx, cy, r), (top, bottom) in cfg:
        draw_bubble(img, cx, cy, r, top, bottom)
    img.convert("RGB").save(ROOT / "icon-512.png", "PNG")
    print("icon-512.png")


def make_feature_graphic() -> None:
    """1024x500 banner: scatter of bubbles + product wordmark."""
    w, h = 1024, 500
    img = background_field(w, h)

    random.seed(7)
    # Left side: cluster of bubbles tumbling.
    placements = [
        (140, 350, 78, 0),
        (280, 200, 64, 1),
        (220, 380, 46, 2),
        (340, 320, 38, 3),
        (90, 200, 36, 4),
        (380, 130, 28, 5),
        (60, 320, 30, 1),
        (340, 420, 24, 2),
    ]
    for cx, cy, r, pi in placements:
        top, bottom = PALETTE[pi % len(PALETTE)]
        draw_bubble(img, cx, cy, r, top, bottom)

    # Right side: title.
    d = ImageDraw.Draw(img)
    title_font_paths = [
        "/System/Library/Fonts/SFNS.ttf",
        "/System/Library/Fonts/Supplemental/Avenir Next.ttc",
        "/System/Library/Fonts/Helvetica.ttc",
    ]
    title_font = None
    sub_font = None
    for p in title_font_paths:
        try:
            title_font = ImageFont.truetype(p, 72)
            sub_font = ImageFont.truetype(p, 26)
            break
        except OSError:
            continue
    if title_font is None:
        title_font = ImageFont.load_default()
        sub_font = ImageFont.load_default()

    title = "BouncyBubbles"
    sub_line1 = "A pocket fidget toy that lives"
    sub_line2 = "on top of every app."
    # Right-align the text block within the right half of the banner.
    tx = 470
    d.text((tx, 175), title, font=title_font, fill=(255, 255, 255))
    d.text((tx, 270), sub_line1, font=sub_font, fill=(200, 210, 230))
    d.text((tx, 305), sub_line2, font=sub_font, fill=(200, 210, 230))

    img.convert("RGB").save(ROOT / "feature-graphic.png", "PNG")
    print("feature-graphic.png")


if __name__ == "__main__":
    make_icon()
    make_feature_graphic()
    print("Done. Output in", ROOT)
