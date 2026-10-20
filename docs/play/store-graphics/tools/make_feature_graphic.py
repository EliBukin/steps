"""Draws the Play feature graphic (1024 x 500, 24-bit PNG, no alpha).

    python3 -I make_feature_graphic.py OUT.png [FONT.ttf]

An illustration in the app's colors, not app UI: the launcher icon's navy (#0F213E) fading into
the app's green (#1B6D5A), a route drawn in the trip map's speed colors (red, yellow, green) with
a green start, a red finish and two numbered spots in the map's marker style, and a partial ring
like the Today screen's. The only text is the app name, large. Drawn at 4x and scaled down for
smooth edges. Default font: Noto Sans Bold.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

out = sys.argv[1]
font_path = sys.argv[2] if len(sys.argv) > 2 else "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"
W, H, K = 1024, 500, 4  # final size and supersampling factor


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


NAVY = (0x0F, 0x21, 0x3E)
GREEN = (0x1B, 0x6D, 0x5A)
MINT = (0xA0, 0xF2, 0xD7)  # primaryContainer
RING_TRACK = (0x2C, 0x5F, 0x63)
SLOW, MID, FAST = (0xD7, 0x30, 0x27), (0xF6, 0xC3, 0x1B), (0x2E, 0xA0, 0x43)
WHITE = (0xFF, 0xFF, 0xFF)

img = Image.new("RGB", (W * K, H * K))
px = img.load()
# Diagonal gradient, navy at the top left to green at the bottom right.
for y in range(H * K):
    for x in range(0, W * K):
        t = min(1.0, max(0.0, (x / (W * K)) * 0.75 + (y / (H * K)) * 0.45 - 0.1))
        px[x, y] = mix(NAVY, GREEN, t)
d = ImageDraw.Draw(img)


def s(v):
    return round(v * K)


# A partial ring, like Today's step ring, behind the route.
cx, cy, r, ring_w = 790, 250, 178, 26
box = [s(cx - r), s(cy - r), s(cx + r), s(cy + r)]
d.ellipse(box, outline=RING_TRACK, width=s(ring_w))
d.arc(box, start=-90, end=-90 + 0.78 * 360, fill=MINT, width=s(ring_w))
for angle in (-90, -90 + 0.78 * 360):  # round caps
    a = math.radians(angle)
    ex, ey = cx + r * math.cos(a) - 0, cy + r * math.sin(a)
    rr = ring_w / 2
    d.ellipse([s(ex - rr), s(ey - rr), s(ex + rr), s(ey + rr)], fill=MINT)
    if angle == -90:
        continue


def bezier(p0, p1, p2, p3, n):
    pts = []
    for i in range(n):
        t = i / (n - 1)
        u = 1 - t
        pts.append(tuple(u ** 3 * p0[j] + 3 * u * u * t * p1[j] + 3 * u * t * t * p2[j] + t ** 3 * p3[j]
                         for j in range(2)))
    return pts


# A winding route through the ring (inside the safe center area).
route = (bezier((660, 380), (640, 300), (720, 300), (760, 250), 120)
         + bezier((760, 250), (800, 200), (700, 150), (770, 115), 120)[1:]
         + bezier((770, 115), (840, 80), (900, 170), (860, 215), 120)[1:]
         + bezier((860, 215), (820, 260), (930, 300), (905, 360), 120)[1:])
# Speed along the route, 0 = slow (red) to 1 = fast (green), varying like a real walk.
speeds = [0.5 + 0.5 * math.sin(i / len(route) * 11.0 + 0.6) * math.cos(i / len(route) * 4.0) for i in range(len(route))]


def speed_color(v):
    v = min(1.0, max(0.0, v))
    return mix(SLOW, MID, v * 2) if v < 0.5 else mix(MID, FAST, (v - 0.5) * 2)


line_w = 15
# A dark casing under the colored line, as on the map.
for (x0, y0), (x1, y1) in zip(route, route[1:]):
    d.line([s(x0), s(y0), s(x1), s(y1)], fill=(0x0B, 0x2A, 0x2A), width=s(line_w + 7))
for x, y in route:
    rr = (line_w + 7) / 2
    d.ellipse([s(x - rr), s(y - rr), s(x + rr), s(y + rr)], fill=(0x0B, 0x2A, 0x2A))
for i, ((x0, y0), (x1, y1)) in enumerate(zip(route, route[1:])):
    c = speed_color(speeds[i])
    d.line([s(x0), s(y0), s(x1), s(y1)], fill=c, width=s(line_w))
    rr = line_w / 2
    d.ellipse([s(x1 - rr), s(y1 - rr), s(x1 + rr), s(y1 + rr)], fill=c)


def dot(x, y, radius, fill, ring=WHITE, ring_w=5):
    d.ellipse([s(x - radius), s(y - radius), s(x + radius), s(y + radius)], fill=ring)
    radius -= ring_w
    d.ellipse([s(x - radius), s(y - radius), s(x + radius), s(y + radius)], fill=fill)


dot(*route[0], 16, (0x2E, 0x8B, 0x3E))   # start
dot(*route[-1], 16, (0xC6, 0x28, 0x28))  # finish

font_spot = ImageFont.truetype(font_path, s(30))
for number, index in ((1, 150), (2, 330)):
    x, y = route[index]
    dot(x, y, 27, WHITE, ring=(0x1B, 0x6D, 0x5A), ring_w=6)
    d.text((s(x), s(y + 1)), str(number), font=font_spot, fill=(0x1B, 0x6D, 0x5A), anchor="mm")

# The app name, large, on the left (inside Play's safe center area).
font_title = ImageFont.truetype(font_path, s(74))
for i, word in enumerate(("Steps", "GPS Route")):
    d.text((s(84), s(178 + i * 96)), word, font=font_title, fill=WHITE, anchor="ls")
# A thin speed-color bar under the name.
bar_x, bar_y, bar_w, bar_h = 88, 300, 330, 12
for i in range(s(bar_w)):
    d.line([s(bar_x) + i, s(bar_y), s(bar_x) + i, s(bar_y + bar_h)], fill=speed_color(i / s(bar_w)))

img = img.resize((W, H), Image.LANCZOS)
img.save(out, optimize=True)
check = Image.open(out)
assert check.size == (W, H) and check.mode == "RGB", (check.size, check.mode)
print(f"{out}: {W} x {H} RGB, {os.path.getsize(out) // 1024} KB")
