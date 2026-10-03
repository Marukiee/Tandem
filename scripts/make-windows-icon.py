#!/usr/bin/env python3
"""Draws the Tandem icon (the plate with the two pills, as in scripts/make-icon.swift and PillMark.swift) and writes
the sizes the Windows app needs. Run it again after changing the geometry:

    python3 scripts/make-windows-icon.py
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = Path(__file__).resolve().parent.parent / "windows" / "src-tauri" / "icons"
CANVAS = 1024
SCALE = 2  # drawn twice as large and scaled down, for clean edges

# The geometry of the icon, in the units of its 1024 point canvas (PillArt in PillMark.swift).
PILL_LENGTH, PILL_WIDTH, LEAN, SPREAD = 470, 170, 32, 78
PLATE, RADIUS = 824, 186


def vertical_gradient(w, h, top, bottom):
    t = np.linspace(0, 1, h)[:, None, None]
    top, bottom = np.array(top, float), np.array(bottom, float)
    row = (top * (1 - t) + bottom * t).astype(np.uint8)
    return Image.fromarray(np.repeat(row, w, axis=1))


def plate(size):
    """The rounded square, lighter at the top left and dark indigo at the bottom right."""
    ys, xs = np.mgrid[0:size, 0:size]
    t = ((xs + ys) / (2.0 * size))[..., None]
    stops = [(0.0, (0x7A, 0x79, 0xEE)), (0.5, (0x4A, 0x49, 0xC4)), (1.0, (0x2E, 0x2C, 0x6B))]
    out = np.zeros((size, size, 3))
    for i, ((t0, c0), (t1, c1)) in enumerate(zip(stops, stops[1:])):
        # Half open, or the pixels exactly on a stop would be counted twice and show as a line.
        last = i == len(stops) - 2
        m = ((t >= t0) & ((t <= t1) if last else (t < t1))).astype(float)
        k = np.clip((t - t0) / (t1 - t0), 0, 1)
        out += m * (np.array(c0) * (1 - k) + np.array(c1) * k)
    return Image.fromarray(out.astype(np.uint8))


def pill(top, bottom, scale):
    w, h = int(PILL_WIDTH * scale), int(PILL_LENGTH * scale)
    body = vertical_gradient(w, h, top, bottom).convert("RGBA")
    shine = Image.new("RGBA", (w, h // 2), (255, 255, 255, 0))
    px = np.zeros((h // 2, w, 4), np.uint8)
    px[..., :3] = 255
    px[..., 3] = (np.linspace(0.35, 0, h // 2)[:, None] * 255).astype(np.uint8)
    shine = Image.fromarray(px)
    body.alpha_composite(shine)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=w // 2, fill=255)
    body.putalpha(mask)
    return body


def draw(with_plate=True):
    s = CANVAS * SCALE
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    unit = s / CANVAS
    if with_plate:
        side = int(PLATE * unit)
        base = plate(side).convert("RGBA")
        mask = Image.new("L", (side, side), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, side - 1, side - 1), radius=int(RADIUS * unit), fill=255)
        base.putalpha(mask)
        shadow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        shadow.paste((0, 0, 0, 90), ((s - side) // 2, (s - side) // 2 + int(8 * unit)), mask)
        img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14 * unit)))
        img.alpha_composite(base, ((s - side) // 2, (s - side) // 2))

    scale = unit * (1.0 if with_plate else 1.45)
    for dx, top, bottom in (
        (SPREAD, (0xE8, 0x77, 0x9F), (0xFF, 0xB0, 0xCB)),
        (-SPREAD, (0xC9, 0xC8, 0xFA), (0xFF, 0xFF, 0xFF)),
    ):
        p = pill(top, bottom, scale).rotate(LEAN, expand=True, resample=Image.BICUBIC)
        layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        layer.alpha_composite(p, (int(s / 2 + dx * scale - p.width / 2), int(s / 2 - p.height / 2)))
        glow = layer.filter(ImageFilter.GaussianBlur(10 * unit))
        shade = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        shade.paste((0, 0, 0, 60), (0, int(10 * unit)), glow.getchannel("A"))
        img.alpha_composite(shade)
        img.alpha_composite(layer)
    return img.resize((CANVAS, CANVAS), Image.LANCZOS)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    icon = draw(True)
    icon.save(OUT / "icon.png")
    for name, size in (("32x32.png", 32), ("128x128.png", 128), ("128x128@2x.png", 256)):
        icon.resize((size, size), Image.LANCZOS).save(OUT / name)
    icon.save(OUT / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    # The tray gets the pills alone: the plate is too heavy at 16 points.
    draw(False).resize((64, 64), Image.LANCZOS).save(OUT / "tray.png")
    print("wrote", sorted(p.name for p in OUT.iterdir()))


main()
