"""Exports the Play Store icon (512 x 512, 32-bit PNG) from the app's launcher icon.

    python3 -I make_icon.py REPO_ROOT OUT.png

The launcher icon is adaptive: a flat #0F213E background layer and a raster foreground
(mipmap-xxxhdpi/ic_launcher_foreground.png, 432 x 432 = 108 dp). Launchers show its central
72 dp, which here holds the whole artwork. The script composites both layers, keeps that central
72 dp (288 x 288 px), and scales it to 512 x 512. No redesign; Play adds its own rounded mask.
The xxxhdpi PNG is the largest copy of the artwork in the repo, so the result is upscaled 1.78x.
"""
import os
import re
import sys

from PIL import Image

repo, out = sys.argv[1], sys.argv[2]
res = os.path.join(repo, "app/src/main/res")
colors = open(os.path.join(res, "values/colors.xml")).read()
hex_color = re.search(r'name="ic_launcher_background">#([0-9A-Fa-f]{6})<', colors).group(1)
background = tuple(int(hex_color[i:i + 2], 16) for i in (0, 2, 4)) + (255,)

foreground = Image.open(os.path.join(res, "mipmap-xxxhdpi/ic_launcher_foreground.png")).convert("RGBA")
size = foreground.width
icon = Image.alpha_composite(Image.new("RGBA", foreground.size, background), foreground)
inset = size // 6  # (108 - 72) / 2 dp of 108
icon = icon.crop((inset, inset, size - inset, size - inset)).resize((512, 512), Image.LANCZOS)
icon.save(out, optimize=True)

check = Image.open(out)
assert check.size == (512, 512) and check.mode == "RGBA", (check.size, check.mode)
assert os.path.getsize(out) <= 1024 * 1024
print(f"{out}: 512 x 512 RGBA, {os.path.getsize(out) // 1024} KB, background #{hex_color}")
