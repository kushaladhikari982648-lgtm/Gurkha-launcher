#!/usr/bin/env python3
"""
Builds the launcher icons of Gurkha Launcher from branding/gurkha-logo.png (white line-art crossed
khukuris on crimson). Run from the repository root:  pip install pillow numpy && python3 branding/make_icons.py

Writes: the adaptive icon foreground (also the Android 13 themed-icon layer), the legacy icons, the
notification icon (all densities) and branding/gurkha-icon.png. The strokes are extracted from the
crimson background, centred on their smallest enclosing circle (so that round icon masks don't cut the
blade tips) and thickened so that they stay readable at launcher and status bar sizes.
"""

import os
from PIL import Image, ImageDraw
import numpy as np
SRC = "branding/gurkha-logo.png"
BG = (196, 36, 37)                     # #C42425, sampled from the logo
CENTRE, RADIUS = (800, 810), 783       # minimal enclosing circle of the strokes

def stroke_alpha():
    """Alpha of the white strokes (0..1), keeping their anti-aliased edges."""
    a = np.asarray(Image.open(SRC).convert("RGB")).astype(np.float32)
    bg = np.array(BG, np.float32); d = np.array([255, 255, 255], np.float32) - bg
    return np.clip(((a - bg) @ d) / (d @ d), 0, 1)

def dilate(alpha, r):
    """Round (disk) dilation by r pixels: thickens the strokes evenly in every direction."""
    if r <= 0: return alpha
    h, w = alpha.shape
    pad = np.pad(alpha, r)
    out = alpha.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx * dx + dy * dy <= r * r:
                np.maximum(out, pad[r + dy:r + dy + h, r + dx:r + dx + w], out=out)
    return out

def emblem(alpha, size, radius_px):
    """White emblem on transparent, minimal enclosing circle centred and scaled to radius_px."""
    s = radius_px / RADIUS
    big = Image.fromarray((alpha * 255).astype(np.uint8), "L")
    # crop a square around the enclosing circle, then scale it
    cx, cy = CENTRE
    box = (cx - RADIUS, cy - RADIUS, cx + RADIUS, cy + RADIUS)
    crop = big.crop(box)
    target = max(1, round(2 * RADIUS * s))
    mask = crop.resize((target, target), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    layer = Image.new("RGBA", (target, target), (255, 255, 255, 255))
    layer.putalpha(mask)
    off = ((size - target) // 2, (size - target) // 2)
    out.alpha_composite(layer, off)
    return out


RES = "ZalithLauncher/src/main/res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
base = stroke_alpha()
icon_strokes = dilate(base, 7)        # readable at launcher sizes, details kept
small_strokes = dilate(base, 18)      # notification icon (24dp): bolder

def rounded_mask(size, radius_frac):
    m = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=int(size * 4 * radius_frac), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def circle_mask(size):
    m = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def full_icon(size, radius_frac_of_size, mask):
    out = Image.new("RGBA", (size, size), BG + (255,))
    out.alpha_composite(emblem(icon_strokes, size, size * radius_frac_of_size))
    shaped = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    shaped.paste(out, (0, 0), mask)
    return shaped

for name, d in DENSITIES.items():
    folder = f"{RES}/mipmap-{name}"
    # Adaptive icon foreground (108dp), emblem inside the 33dp safe circle; also used as the monochrome layer
    fg_size = round(108 * d)
    emblem(icon_strokes, fg_size, 32 * d).save(f"{folder}/ic_launcher_foreground.png", optimize=True)
    # Legacy icons (48dp), used where adaptive icons are not supported
    size = round(48 * d)
    full_icon(size, 0.42, rounded_mask(size, 0.22)).save(f"{folder}/ic_launcher.png", optimize=True)
    full_icon(size, 0.40, circle_mask(size)).save(f"{folder}/ic_launcher_round.png", optimize=True)
    # Notification icon (24dp): white on transparent, drawn by the system as a silhouette
    nfolder = f"{RES}/drawable-{name}"
    os.makedirs(nfolder, exist_ok=True)
    emblem(small_strokes, round(24 * d), 11.5 * d).save(f"{nfolder}/ic_notification.png", optimize=True)

# Branding: 1024px app icon (README, release page)
full_icon(1024, 0.40, rounded_mask(1024, 0.22)).save("branding/gurkha-icon.png", optimize=True)
print("done")
