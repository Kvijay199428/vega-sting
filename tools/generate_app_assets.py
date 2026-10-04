#!/usr/bin/env python3
"""
VEGA STING — app asset generator

Reads the redesigned artwork in `.assets/dark/` and emits everything the
Android app needs under `app/src/main/res/`.

Operations (all mechanical, re-runnable):
  copy    — new dark-themed, transparent-background launcher icons and
            notification / shortcut buckets
  delete  — stale adaptive-icon layers from any earlier artwork
            (mipmap-anydpi-v26/, ic_launcher_{foreground,background,monochrome})
  crop    — the circular ring+monogram mark out of the dark splash artwork
            -> drawable-*/splash_logo.png (circle centred in a 108 dp canvas)
"""

import os
import shutil
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DARK = os.path.join(ROOT, ".assets", "dark")
SRC = os.path.join(DARK, "app-icons", "android")
DARK_NOTIF = os.path.join(DARK, "notification-shortcut-icon")
DARK_SPLASH = os.path.join(DARK, "splash-screens", "splash", "android")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 108 dp adaptive-icon canvas, px per density bucket
ICON_PX = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
DENSITY_FOLDER = {
    "mdpi": "mdpi",
    "hdpi": "hdpi",
    "xhdpi": "xhdpi",
    "xxhdpi": "xxhdpi",
    "xxxhdpi": "xxxhdpi",
}
# Legacy launcher mipmaps are 48 dp, px per density bucket
LEGACY_PX = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Target ring diameter (dp) on the 108 dp adaptive/splash canvas.
# 64 dp leaves a 2 dp safety margin inside Android's 66 dp guaranteed-visible
# mask, so the ring and the VOS monogram are never clipped on any launcher.
RING_DP = 64


def mipmap_bucket(b):
    return "mipmap-" + DENSITY_FOLDER[b]


def drawable_bucket(b):
    return "drawable-" + DENSITY_FOLDER[b]


def find_red_circle(im, thresh=18):
    """BBox of red pixels and the circle's centre as ((x0,y0,x1,y1), (cx,cy), d)."""
    px = im.convert("RGB").load()
    w, h = im.size
    xs, ys = [], []
    step = 2
    for y in range(0, h, step):
        for x in range(0, w, step):
            r, g, b = px[x, y]
            if (r - g) > thresh and (r - b) > thresh:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    x0, y0, x1, y1 = min(xs), min(ys), max(xs), max(ys)
    return (x0, y0, x1, y1), ((x0 + x1) / 2.0, (y0 + y1) / 2.0), max(x1 - x0 + 1, y1 - y0 + 1)


def find_circle_margin_crop(im, margin=1.06):
    """Square crop centred on the red circle, diameter * margin, all black outside."""
    hit = find_red_circle(im)
    if hit is None:
        return None, None
    (x0, y0, x1, y1), (cx, cy), d = hit
    side = int(round(d * margin))
    half = side / 2.0
    left = int(round(cx - half))
    top = int(round(cy - half))
    crop = im.crop((left, top, left + side, top + side))
    return crop, d


def ensure(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)


def copy(src, dst):
    ensure(dst)
    shutil.copy2(src, dst)
    print("copy   ->", os.path.relpath(dst, ROOT))


def foreground_fit(src_png, out_path, icon_px, ring_dp):
    """Scale the provided transparent foreground so its ring lands at
    `ring_dp` on the 108 dp canvas, recentered. Pure resample + composite."""
    fg = Image.open(src_png).convert("RGBA")
    hit = find_red_circle(fg)
    if hit is None:
        raise RuntimeError("no red ring found in " + src_png)
    d = hit[2]
    src_w = fg.size[0]                       # source image is square
    factor = ring_dp * icon_px / (108.0 * d)
    new_size = max(1, int(round(src_w * factor)))
    fg_small = fg.resize((new_size, new_size), Image.LANCZOS)
    if new_size >= icon_px:                  # crop centred to canvas
        off = (new_size - icon_px) // 2
        fg_small = fg_small.crop((off, off, off + icon_px, off + icon_px))
        paste_xy = (0, 0)
    else:                                    # paste centred on canvas
        paste_xy = ((icon_px - new_size) // 2, (icon_px - new_size) // 2)
    canvas = Image.new("RGBA", (icon_px, icon_px), (0, 0, 0, 0))
    canvas.paste(fg_small, paste_xy, fg_small)
    ensure(out_path)
    canvas.save(out_path)
    print("fit    ->", os.path.relpath(out_path, ROOT),
          "ring {}dp on {}px canvas".format(ring_dp, icon_px))


def stale_adaptive():
    # Re-add the proper adaptive-icon layers this artwork ships with
    # (foreground over opaque-black background, no monochrome).
    for bucket in ICON_PX:
        src_dir = os.path.join(SRC, mipmap_bucket(bucket))
        icon_px = ICON_PX[bucket]
        # background: opaque black, unchanged
        p = os.path.join(src_dir, "ic_launcher_background.png")
        if os.path.isfile(p):
            copy(p, os.path.join(RES, mipmap_bucket(bucket), "ic_launcher_background.png"))
        # foreground: downscale so the ring fits the 66 dp safe zone
        fsrc = os.path.join(src_dir, "ic_launcher_foreground.png")
        if os.path.isfile(fsrc):
            foreground_fit(fsrc, os.path.join(RES, mipmap_bucket(bucket), "ic_launcher_foreground.png"),
                           icon_px, RING_DP)
    ad_src = os.path.join(SRC, "mipmap-anydpi-v26")
    if os.path.isdir(ad_src):
        for name in os.listdir(ad_src):
            copy(os.path.join(ad_src, name), os.path.join(RES, "mipmap-anydpi-v26", name))
    # remove legacy monochrome layers from any earlier artwork
    for bucket in ICON_PX:
        p = os.path.join(RES, mipmap_bucket(bucket), "ic_launcher_monochrome.png")
        if os.path.isfile(p):
            os.remove(p)
            print("delete ->", os.path.relpath(p, ROOT))


def main():
    # ---- 1. New dark, transparent launcher icons (legacy mipmaps) ----
    for bucket in ICON_PX:
        src_dir = os.path.join(SRC, mipmap_bucket(bucket))
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            copy(os.path.join(src_dir, name), os.path.join(RES, mipmap_bucket(bucket), name))

    # ---- 2. Notification + shortcut icons: transparent silhouette ----
    # Notification small icons are alpha-tinted by Android, so they must be a
    # silhouette (ring + VOS only, everything else transparent). The provided
    # filled-disc source would render as a solid tinted circle. Derive both the
    # notification and shortcut icons from the transparent launcher foreground,
    # keeping the ring at 75% of the 48 dp canvas (their current proportion).
    NOTIF_PX = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    fore_src = os.path.join(SRC, mipmap_bucket("xxxhdpi"), "ic_launcher_foreground.png")
    for bucket in ICON_PX:
        canvas = NOTIF_PX[bucket]
        out_n = os.path.join(RES, drawable_bucket(bucket), "ic_notification.png")
        out_s = os.path.join(RES, drawable_bucket(bucket), "ic_shortcut_record.png")
        # ring target = 75% of the notification canvas
        foreground_fit(fore_src, out_n, canvas, int(round(0.75 * 108)))
        foreground_fit(fore_src, out_s, canvas, int(round(0.75 * 108)))

    # ---- 3. Remove stale adaptive layers ----
    stale_adaptive()

    # ---- 4. Splash logo: the circular mark, cropped from the dark splash ----
    splash_src = os.path.join(DARK_SPLASH, "splash-xxxhdpi.png")
    splash = Image.open(splash_src).convert("RGBA")
    crop, d = find_circle_margin_crop(splash, margin=108.0 / RING_DP)
    if crop is None:
        print("ERROR: no red circle found in splash", file=sys.stderr)
        sys.exit(1)
    print("crop   -> circular mark, crop size {} from splash".format(crop.size))
    for bucket, icon_px in ICON_PX.items():
        out = os.path.join(RES, drawable_bucket(bucket), "splash_logo.png")
        res = crop.resize((icon_px, icon_px), Image.LANCZOS)
        ensure(out)
        res.save(out)
        print("crop   ->", os.path.relpath(out, ROOT), "{}x{}".format(icon_px, icon_px))

    print("\nDone.")


if __name__ == "__main__":
    main()
