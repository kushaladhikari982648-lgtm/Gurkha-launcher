#!/usr/bin/env python3
"""
Generate all Android launcher icons, notification icons, and title banner from branding/orbit-logo.png.
"""

from pathlib import Path
from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "branding" / "orbit-logo.png"
RES = ROOT / "ZalithLauncher" / "src" / "main" / "res"

BG = (30, 31, 35, 255)  # #1E1F23

MIPMAPS = {
    "mdpi":    (48,  108),
    "hdpi":    (72,  162),
    "xhdpi":   (96,  216),
    "xxhdpi":  (144, 324),
    "xxxhdpi": (192, 432),
}

NOTIF = {
    "mdpi":    24,
    "hdpi":    36,
    "xhdpi":   48,
    "xxhdpi":  72,
    "xxxhdpi": 96,
}


def extract_foreground_alpha(img: Image.Image) -> Image.Image:
    """Separate the Orbit Launcher artwork from the uniform #1E1F23 background."""
    rgba = img.convert("RGBA")
    bg_img = Image.new("RGB", rgba.size, BG[:3])
    diff = ImageChops.difference(rgba.convert("RGB"), bg_img)
    r, g, b = diff.split()
    max_diff = ImageChops.lighter(ImageChops.lighter(r, g), b)
    alpha = max_diff.point(lambda d: 0 if d <= 10 else min(255, int((d - 10) * 10)))
    out = rgba.copy()
    out.putalpha(alpha)
    return out


def place_centered(artwork: Image.Image, canvas_size: int, target_box: int, background) -> Image.Image:
    scale = min(target_box / artwork.width, target_box / artwork.height)
    w = max(1, round(artwork.width * scale))
    h = max(1, round(artwork.height * scale))
    scaled = artwork.resize((w, h), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), background)
    canvas.alpha_composite(scaled, ((canvas_size - w) // 2, (canvas_size - h) // 2))
    return canvas


def rounded_mask(size: int, radius: float) -> Image.Image:
    supersample = 4
    m = Image.new("L", (size * supersample, size * supersample), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        (0, 0, size * supersample - 1, size * supersample - 1),
        radius=radius * supersample,
        fill=255,
    )
    return m.resize((size, size), Image.Resampling.LANCZOS)


def main() -> None:
    raw = Image.open(SRC).convert("RGBA")
    fg_rgba = extract_foreground_alpha(raw)
    bbox = fg_rgba.getbbox()
    cropped_fg = fg_rgba.crop(bbox) if bbox else raw

    # Also crop just the top graphic emblem (grass block + cyan ring, above the text) for small/notif icons
    emblem_fg = fg_rgba.crop((140, 80, 884, 568))
    emblem_bbox = emblem_fg.getbbox()
    if emblem_bbox:
        emblem_fg = emblem_fg.crop(emblem_bbox)

    # Preview icons in branding/
    preview = place_centered(cropped_fg, 512, round(512 * 0.88), BG)
    preview.putalpha(rounded_mask(512, 512 * 0.22))
    preview.save(ROOT / "branding" / "gurkha-icon.png")
    raw.save(ROOT / "branding" / "gurkha-logo.png")

    for density, (legacy_px, fg_px) in MIPMAPS.items():
        d = RES / f"mipmap-{density}"

        # Adaptive icon foreground (transparent 108dp canvas, artwork centered in 68dp safe zone)
        fg = place_centered(cropped_fg, fg_px, round(fg_px * (68 / 108)), (0, 0, 0, 0))
        fg.save(d / "ic_launcher_foreground.png")

        # Legacy square icon
        sq = place_centered(cropped_fg, legacy_px, round(legacy_px * 0.88), BG)
        sq.putalpha(rounded_mask(legacy_px, legacy_px * 0.20))
        sq.save(d / "ic_launcher.png")

        # Legacy round icon
        rd = place_centered(cropped_fg, legacy_px, round(legacy_px * 0.86), BG)
        rd.putalpha(rounded_mask(legacy_px, legacy_px / 2))
        rd.save(d / "ic_launcher_round.png")

    # Notification icons (white silhouette on transparent background)
    emblem_alpha = emblem_fg.getchannel("A")
    white_emblem = Image.new("RGBA", emblem_fg.size, (255, 255, 255, 0))
    white_emblem.putalpha(emblem_alpha)
    for density, px in NOTIF.items():
        d = RES / f"drawable-{density}"
        notif = place_centered(white_emblem, px, round(px * 0.86), (0, 0, 0, 0))
        notif.save(d / "ic_notification.png")

    # Splash / About title banner (app_name_title.png, 1000x208 RGBA)
    banner_w, banner_h = 1000, 208
    banner = Image.new("RGBA", (banner_w, banner_h), (0, 0, 0, 0))
    # Left: Orbit emblem (184x184)
    emblem_scaled = place_centered(emblem_fg, 188, 180, (0, 0, 0, 0))
    banner.alpha_composite(emblem_scaled, (36, (banner_h - 188) // 2))
    # Right: "ORBIT LAUNCHER" pixel-art title from the bottom of the logo (y=568..945)
    text_crop = fg_rgba.crop((80, 568, 944, 945))
    text_bbox = text_crop.getbbox()
    if text_bbox:
        text_crop = text_crop.crop(text_bbox)
    scale = min(700 / text_crop.width, 184 / text_crop.height)
    tw = max(1, round(text_crop.width * scale))
    th = max(1, round(text_crop.height * scale))
    text_scaled = text_crop.resize((tw, th), Image.Resampling.LANCZOS)
    banner.alpha_composite(text_scaled, (250, (banner_h - th) // 2))
    banner.save(RES / "drawable" / "app_name_title.png")


if __name__ == "__main__":
    main()
