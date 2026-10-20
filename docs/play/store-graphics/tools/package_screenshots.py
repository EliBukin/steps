"""Turns raw emulator captures into Play-ready phone screenshots.

    python3 -I package_screenshots.py RAW_DIR OUT_DIR raw-name=out-name ...

Each capture must be 1080 x 1920 (9:16) and fully opaque. The output is the same image as a
24-bit RGB PNG (Play doesn't accept alpha in screenshots). The script checks that no pixel
changed: nothing is cropped, retouched or resized.
"""
import os
import sys

from PIL import Image

raw_dir, out_dir = sys.argv[1], sys.argv[2]
os.makedirs(out_dir, exist_ok=True)
for pair in sys.argv[3:]:
    raw_name, out_name = pair.split("=", 1)
    src = Image.open(os.path.join(raw_dir, raw_name + ".png"))
    if src.size != (1080, 1920):
        raise SystemExit(f"{raw_name}: {src.size}, expected 1080 x 1920")
    rgba = src.convert("RGBA")
    if rgba.getchannel("A").getextrema() != (255, 255):
        raise SystemExit(f"{raw_name}: has transparent pixels")
    rgb = rgba.convert("RGB")
    out = os.path.join(out_dir, out_name + ".png")
    rgb.save(out, optimize=True)
    check = Image.open(out)
    if check.mode != "RGB" or check.tobytes() != rgb.tobytes():
        raise SystemExit(f"{out}: pixels changed")
    print(f"{out}: 1080 x 1920 RGB, {os.path.getsize(out) // 1024} KB")
