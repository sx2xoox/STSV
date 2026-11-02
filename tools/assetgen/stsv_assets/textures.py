"""
Procedural 16x16 textures built only from palette ramps, with fixed seeds (always identical output).

Shading rules shared by every generator:
  - light from the top-left: top/left edges +1 step, bottom/right edges -1 step
  - jitter: at most one ramp step, on a minority of pixels
  - outlines (2D items only): darkest step of the item's main ramp
"""

import math
import random

from PIL import Image

from .palette import ramp, size

S = 16


def _new(fill=None):
    return Image.new("RGBA", (S, S), fill or (0, 0, 0, 0))


def _j(rnd, i, chance=0.2):
    return i + rnd.choice((-1, 1)) if rnd.random() < chance else i


# ------------------------------------------------------------------------------------------------
# Surface (tileable) textures for 3D models
# ------------------------------------------------------------------------------------------------

def brick(seed=11, ramp_name="brick", mortar="mortar"):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    bases = {}
    for y in range(S):
        row = y // 4
        off = 4 * (row % 2)
        for x in range(S):
            ry, bx = y % 4, (x + off) % 8
            if ry == 3 or bx == 7:
                px[x, y] = ramp(mortar, 0 if ry == 3 else 1)
                continue
            base = bases.setdefault((row, ((x + off) // 8) % 2), rnd.choice((1, 2, 2, 3)))
            i = base + (1 if ry == 0 else -1 if ry == 2 else 0)
            px[x, y] = ramp(ramp_name, _j(rnd, i))
    return im


def brick_soot(seed=12):
    """Bricks blackened by smoke: same layout as brick() so they line up when stacked."""
    clean, dirty = brick(seed=11), brick(seed=11, ramp_name="soot")
    rnd = random.Random(seed)
    out = _new()
    for y in range(S):
        for x in range(S):
            # soot gets heavier towards the top (texture top = model top)
            out.putpixel((x, y), dirty.getpixel((x, y)) if rnd.random() < 0.85 - y * 0.04 else clean.getpixel((x, y)))
    return out


def steel_plate(seed=21, rivets=True):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if y in (5, 10) and rnd.random() < 0.6:
                i = 1
            if x == 0 or y == 0:
                i = 3
            elif x == S - 1 or y == S - 1:
                i = 0
            else:
                i = _j(rnd, i, 0.12)
            px[x, y] = ramp("steel", i)
    if rivets:
        for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
            px[x, y] = ramp("steel", 5)
            px[x + 1, y] = ramp("steel", 3)
            px[x + 1, y + 1] = ramp("steel", 0)
    return im


def steel_dark(seed=22):
    """Unriveted dark steel for thin parts (rails, legs, frames)."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("steel", _j(rnd, 1 if (x + y) % 7 else 2, 0.15))
    return im


def copper_pipe(seed=31):
    """Horizontal bands: works on any face of a square pipe."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    band = [1, 2, 3, 4, 3, 3, 2, 2, 2, 1, 1, 1, 2, 3, 2, 0]
    for y in range(S):
        for x in range(S):
            i = band[x]
            if y % 8 == 0:
                i = 0  # flange seams
            px[x, y] = ramp("copper", _j(rnd, i, 0.08))
    return im


def brass(seed=35):
    """Polished brass for small fittings (handles, valves)."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 3 if x + y < 6 else 2 if x + y < 20 else 1
            px[x, y] = ramp("brass", _j(rnd, i, 0.1))
    return im


def fire(seed=41):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    ph = [rnd.random() * math.tau for _ in range(3)]
    n = size("fire")
    for y in range(S):
        for x in range(S):
            t = x / S * math.tau
            tongue = math.sin(2 * t + ph[0]) * 0.5 + math.sin(3 * t + ph[1]) * 0.3 + math.sin(5 * t + ph[2]) * 0.2
            heat = (y / (S - 1)) * 1.25 + tongue * 0.28 - 0.1
            px[x, y] = ramp("fire", _j(rnd, int(heat * (n - 1)), 0.18))
    return im


def gold_ore(seed=51):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("stone", _j(rnd, rnd.choice((1, 2, 2)), 0.3))
    nug = [(0, 0, 2), (1, 0, 3), (0, 1, 1), (1, 1, 2), (2, 1, 1), (1, 2, 0)]
    for (cx, cy) in ((2, 3), (9, 1), (11, 9), (4, 11), (8, 6)):
        for dx, dy, i in nug:
            px[(cx + dx) % S, (cy + dy) % S] = ramp("gold", i)
    return im


def belt(seed=61):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("rubber", _j(rnd, (3, 2, 1, 0)[y % 4], 0.1))
    return im


def hazard(seed=71):
    """Diagonal yellow/black warning stripes."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            stripe = ((x + y) // 3) % 2 == 0
            px[x, y] = ramp("hazard", 1 if y < 8 else 0) if stripe else ramp("rubber", 1)
    return im


def opening(seed=81):
    """A dark hole with a faint ember glow at the bottom."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            if y >= 12 and rnd.random() < (y - 11) * 0.18:
                px[x, y] = ramp("fire", rnd.choice((0, 0, 1)))
            else:
                px[x, y] = ramp("rubber", _j(rnd, 0, 0.3))
    return im


def gauge():
    im = _new(ramp("steel", 1))
    px = im.load()
    c = 7.5
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - c, y - c)
            if d < 5.6:
                px[x, y] = ramp("dial", 1 if x + y < 15 else 0)
            elif d < 7.3:
                px[x, y] = ramp("brass", 2 if x + y < 15 else 0)
    for (x, y) in ((4, 10), (3, 7), (4, 4), (7, 3), (11, 4), (12, 7), (11, 10)):
        px[x, y] = ramp("steel", 1)
    for (x, y) in ((8, 8), (9, 7), (10, 6), (11, 5)):
        px[x, y] = ramp("signal", 1)
    px[7, 8] = px[8, 8] = ramp("steel", 0)
    return im


def lamp():
    """Caged work lamp, bright center."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - 7.5, y - 7.5)
            i = 3 if d < 3 else 2 if d < 5.5 else 1 if d < 7 else 0
            px[x, y] = ramp("lamp", i)
            if x % 5 == 0 or y % 5 == 0:
                px[x, y] = ramp("steel", 1)  # cage bars
    return im


def wood_plank(seed=91, ramp_name="wood"):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        seam = 5 + 6 * ((y // 4) % 2)          # staggered plank ends
        for x in range(S):
            i = 2 if (y // 4) % 2 else 1
            if y % 4 == 3:
                i = 0
            elif y % 4 == 0:
                i += 1
            if x == seam and y % 4 != 3:
                i = 0
            px[x, y] = ramp(ramp_name, _j(rnd, i, 0.15))
    return im


def wood_grain(seed=89, ramp_name="wood"):
    """Seamless lengthwise grain for thin parts (legs, posts, rails): plank seams look like noise there."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    lines = {x: rnd.choice((1, 1, 2, 2, 2, 0)) for x in range(S)}
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp(ramp_name, _j(rnd, lines[x], 0.06))
    return im


def wood_dark_grain(seed=90):
    return wood_grain(seed, "wood_dark")


def wood_dark(seed=92):
    return wood_plank(seed, "wood_dark")


def leaves(seed=93):
    """Clumped leaves with see-through gaps (cutout)."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    n = size("leaf")
    for y in range(S):
        for x in range(S):
            # clumps: brighter at the top-left of each 4x4 clump
            cx, cy = x % 4, y % 4
            i = n - 2 - (cx + cy) // 2
            if rnd.random() < 0.12:
                px[x, y] = (0, 0, 0, 0)
                continue
            px[x, y] = ramp("leaf", _j(rnd, i, 0.25))
    return im


def soil(seed=94):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = rnd.choice((1, 1, 2, 2, 0))
            px[x, y] = ramp("soil", i)
            if rnd.random() < 0.05:
                px[x, y] = ramp("stone", 2)  # pebbles
    return im


def terracotta(seed=95):
    """Clay pot: plain clay with one painted band."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if y in (4, 5):
                i = 3 if y == 4 else 1
            elif y == 6 and x % 4 == 1:
                i = 0  # painted dots under the band
            px[x, y] = ramp("terracotta", _j(rnd, i, 0.12))
    return im


def fabric(seed=96):
    """Woven cushion fabric."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2 if (x + y) % 2 else 1
            if x % 8 == 0 or y % 8 == 0:
                i = 0  # quilting seams
            px[x, y] = ramp("fabric_red", _j(rnd, i, 0.08))
    return im


def stone_bricks(seed=97):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        off = 4 * ((y // 8) % 2)
        for x in range(S):
            bx, by = (x + off) % 8, y % 8
            if by == 7 or bx == 7:
                px[x, y] = ramp("stone", 0)
                continue
            i = 2 + (1 if by == 0 or bx == 0 else 0) - (1 if by == 6 or bx == 6 else 0)
            px[x, y] = ramp("stone", _j(rnd, i, 0.2))
    return im


def gold_trim(seed=98):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2 if y % 4 else 3
            if y % 4 == 3:
                i = 0
            px[x, y] = ramp("gold", _j(rnd, i, 0.08))
    return im


def crystal(seed=99):
    """Faceted crystal: diagonal light bands."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    n = size("crystal")
    for y in range(S):
        for x in range(S):
            band = ((x - y) % 8)
            i = n - 1 if band == 0 else n - 2 if band in (1, 7) else 1 + (x + y) % 2
            px[x, y] = ramp("crystal", _j(rnd, i, 0.1))
    return im


def magic(seed=100):
    """Glowing core: bright center, sparkles."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - 7.5, y - 7.5)
            i = 3 if d < 3 else 2 if d < 5.5 else 1 if d < 7.5 else 0
            if rnd.random() < 0.06:
                i = 3
            px[x, y] = ramp("magic", i)
    return im


# ------------------------------------------------------------------------------------------------
# 2D item icons (transparent background, outlined)
# ------------------------------------------------------------------------------------------------

def _outline(im, ramp_name):
    px = im.load()
    filled = {(x, y) for y in range(S) for x in range(S) if px[x, y][3]}
    for (x, y) in list(filled):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < S and 0 <= ny < S and (nx, ny) not in filled:
                px[nx, ny] = ramp(ramp_name, 0)
    return im


def _blob(mask_rows, ramp_name, seed):
    """mask_rows: list of strings, '#' filled. Shaded by distance to the top-left light."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    h = len(mask_rows)
    oy = (S - h) // 2
    for y, row in enumerate(mask_rows):
        ox = (S - len(row)) // 2
        for x, ch in enumerate(row):
            if ch != "#":
                continue
            up = y == 0 or x >= len(mask_rows[y - 1]) or mask_rows[y - 1][x] != "#"
            left = x == 0 or row[x - 1] != "#"
            down = y == h - 1 or x >= len(mask_rows[y + 1]) or mask_rows[y + 1][x] != "#"
            right = x == len(row) - 1 or row[x + 1] != "#"
            mid = size(ramp_name) // 2
            i = mid + (1 if up or left else 0) - (1 if down or right else 0)
            if up and left:
                i = size(ramp_name) - 1
            px[ox + x, oy + y] = ramp(ramp_name, _j(rnd, i, 0.12))
    return _outline(im, ramp_name)


def icon_gold_chunk():
    return _blob([
        "   ###   ",
        "  ###### ",
        " ########",
        "#########",
        "######## ",
        " #####   ",
        "  ###    ",
    ], "gold", 101)


def icon_steel_ingot():
    rows = [
        "   ########  ",
        "  ########## ",
        " ############",
        "#############",
        "############ ",
        " ##########  ",
    ]
    return _blob(rows, "steel", 102)


def icon_copper_plate():
    rows = ["##########"] * 9
    im = _blob(rows, "copper", 103)
    px = im.load()
    for (x, y) in ((4, 5), (11, 5), (4, 11), (11, 11)):
        px[x, y] = ramp("copper", 4)
    return im


SURFACES = {
    "brick": brick,
    "brick_soot": brick_soot,
    "steel": steel_plate,
    "steel_dark": steel_dark,
    "copper": copper_pipe,
    "brass": brass,
    "fire": fire,
    "gold_ore": gold_ore,
    "belt": belt,
    "hazard": hazard,
    "opening": opening,
    "gauge": gauge,
    "lamp": lamp,
    "wood": wood_plank,
    "wood_dark": wood_dark,
    "wood_grain": wood_grain,
    "wood_dark_grain": wood_dark_grain,
    "leaves": leaves,
    "soil": soil,
    "terracotta": terracotta,
    "fabric": fabric,
    "stone_bricks": stone_bricks,
    "gold_trim": gold_trim,
    "crystal": crystal,
    "magic": magic,
}

ICONS = {
    "gold_chunk": icon_gold_chunk,
    "steel_ingot": icon_steel_ingot,
    "copper_plate": icon_copper_plate,
}
