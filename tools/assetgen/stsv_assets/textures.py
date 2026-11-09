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


NOISE = 0.5  # v3: half the pixel noise of v2 -> cleaner surfaces


def _j(rnd, i, chance=0.2):
    # the random draw always happens so seeds stay stable when NOISE changes
    roll, step = rnd.random(), rnd.choice((-1, 1))
    return i + step if roll < chance * NOISE else i


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
    """Clumped leaves. Opaque: gaps are painted with the darkest step (depth) instead of holes,
    because a leaf crown is a hollow box and real holes would show straight through it."""
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
                px[x, y] = ramp("leaf", 0)
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
    """Woven cushion fabric. The quilting seams sit on pixels 7-8 (mirror-symmetric) so they
    line up across the top and side faces of a cushion."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2 if (x + y) % 2 else 1
            if x in (7, 8) or y in (7, 8):
                i = 0  # quilting seams (the only pixels allowed to use the darkest step)
            else:
                i = max(1, _j(rnd, i, 0.08))
            px[x, y] = ramp("fabric_red", i)
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
# TEST-ONLY "dev textures": instantly recognisable as placeholders (grid + border)
# ------------------------------------------------------------------------------------------------

def _dev_grid(ramp_name, seed):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if x % 8 == 0 or y % 8 == 0:
                i = 1                     # grid lines every half block
            if x % 8 == 4 and y % 8 == 4:
                i = 3                     # centre dots
            px[x, y] = ramp(ramp_name, _j(rnd, i, 0.05))
    return im


def dev_orange(seed=201):
    return _dev_grid("test_orange", seed)


def dev_grey(seed=202):
    return _dev_grid("test_grey", seed)


def dev_purple(seed=203):
    return _dev_grid("test_purple", seed)


def dev_stripe():
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("test_purple", 2) if ((x + y) // 4) % 2 else ramp("test_grey", 3)
    return im


def _dev_screen_frame(k, ramp_name="test_teal"):
    """Teal screen with a bright scan line moving down (frame k of 8)."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = (y - 2 * k) % 16
            i = 3 if d == 0 else 2 if d in (1, 15) else 1
            if x in (0, 15) or y in (0, 15):
                i = 0
            px[x, y] = ramp(ramp_name, i)
    return im


def dev_screen():
    return _dev_screen_frame(0)


def dev_fan():
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("test_grey", 3 if y < 8 else 2)
    return im


def tomato_skin(seed=210):
    """Glossy tomato: one soft highlight, otherwise clean."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if 2 <= x <= 5 and 2 <= y <= 4:
                i = 3
            if x in (3, 4) and y == 3:
                i = 4                     # shine
            if y >= 13:
                i = 1
            px[x, y] = ramp("tomato", _j(rnd, i, 0.05))
    return im


def _shade(ramp_name, i_main, i_edge):
    """Plain inner / rim surfaces derived from a material ramp (keeps masks on-palette)."""
    def gen():
        im = _new()
        px = im.load()
        for y in range(S):
            for x in range(S):
                px[x, y] = ramp(ramp_name, i_edge if y in (0, 15) or x in (0, 15) else i_main)
        return im
    return gen


def tomato_body(seed=214):
    """Tomato with soft vertical ribs and one clean highlight."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if x in (4, 11):
                i = 1                     # ribs
            if 2 <= x <= 3 and 3 <= y <= 6:
                i = 3
            if x == 2 and y in (3, 4):
                i = 4                     # shine
            if y >= 14:
                i = 1
            px[x, y] = ramp("tomato", _j(rnd, i, 0.03))
    return im


def eggplant_skin(seed=212):
    """Glossy dark purple with a long soft highlight."""
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if 2 <= x <= 3 and 2 <= y <= 12:
                i = 3
            if x == 3 and 3 <= y <= 8:
                i = 4                     # shine
            if y >= 13 or x >= 14:
                i = 1
            px[x, y] = ramp("eggplant", _j(rnd, i, 0.04))
    return im


# fixed spot positions: clean and identical on every face (no random speckles)
_SPOTS = ((2, 2, 2), (10, 3, 2), (6, 8, 3), (12, 11, 2), (3, 12, 2))


def toadstool_cap():
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("toadstool", 2 if y < 12 else 1)
    for (sx, sy, r) in _SPOTS:
        for y in range(sy, sy + r):
            for x in range(sx, sx + r):
                corner = (x in (sx, sx + r - 1)) and (y in (sy, sy + r - 1)) and r > 2
                if not corner:
                    px[x % S, y % S] = ramp("cream", 3 if y == sy else 2)
    return im


def mushroom_stalk(seed=213):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("cream", _j(rnd, 2 if x % 5 else 1, 0.05))
    return im


def gills():
    """Underside of the cap: fine radial-looking stripes."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("cream", 1 if x % 2 else 0)
    return im


def calyx(seed=211):
    rnd = random.Random(seed)
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("leaf", _j(rnd, 3 if (x + y) % 5 else 2, 0.05))
    return im


# ------------------------------------------------------------------------------------------------
# Premium sword ("cash item" look)
# ------------------------------------------------------------------------------------------------

def blade_steel():
    """Polished blade: bright, a clean highlight line down the middle, almost no noise."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if x in (7, 8):
                i = 3                       # mirror-polished centre line
            elif x in (0, 15):
                i = 1
            px[x, y] = ramp("blade", i)
    return im


def _energy_frame(k):
    """Energy flowing up the blade core: a bright pulse travelling upwards (frame k of 8)."""
    im = _new()
    px = im.load()
    n = size("aurora")
    for y in range(S):
        for x in range(S):
            d = (y + 2 * k) % 16          # pulse moves up as k increases
            core = 2 if 5 <= x <= 10 else 1   # brighter in the middle: a smooth beam, no checkering
            i = n - 1 if d < 2 else n - 2 if d < 4 else core
            if x in (0, 15):
                i = max(0, i - 1)
            px[x, y] = ramp("aurora", i)
    return im


def energy():
    return _energy_frame(0)


def obsidian_wrap():
    """Grip: dark leather wrap with diagonal bands."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("obsidian", 2 if (x + y) % 4 < 2 else 1)
    return im


def gem_ruby():
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = abs(x - 7.5) + abs(y - 7.5)
            px[x, y] = ramp("ruby", 3 if d < 4 else 2 if d < 8 else 1 if d < 11 else 0)
    return im


def premium_gold():
    """Gold trim with an engraved line."""
    im = _new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if y % 8 == 0:
                i = 3
            if y % 8 == 4:
                i = 1                       # engraving
            px[x, y] = ramp("gold", i)
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
    "dev_orange": dev_orange,
    "dev_grey": dev_grey,
    "dev_purple": dev_purple,
    "dev_stripe": dev_stripe,
    "dev_screen": dev_screen,
    "dev_fan": dev_fan,
    "tomato_skin": tomato_skin,
    "eggplant_skin": eggplant_skin,
    "tomato_body": tomato_body,
    "tomato_inner": _shade("tomato", 0, 0),
    "tomato_rim": _shade("tomato", 1, 0),
    "eggplant_inner": _shade("eggplant", 0, 0),
    "eggplant_rim": _shade("eggplant", 1, 0),
    "stalk_inner": _shade("cream", 0, 0),
    "stalk_rim": _shade("toadstool", 1, 0),
    "toadstool_cap": toadstool_cap,
    "mushroom_stalk": mushroom_stalk,
    "gills": gills,
    "blade_steel": blade_steel,
    "energy": energy,
    "obsidian_wrap": obsidian_wrap,
    "gem_ruby": gem_ruby,
    "premium_gold": premium_gold,
    "calyx": calyx,
}

# Animated textures: name -> (function returning a list of 16x16 frames, ticks per frame).
# SURFACES[name] must return the first frame (used for previews and validation).
ANIMATED = {
    "dev_screen": (lambda: [_dev_screen_frame(k) for k in range(8)], 2),
    "energy": (lambda: [_energy_frame(k) for k in range(8)], 2),
}


def animation_strip(name):
    frames = ANIMATED[name][0]()
    w, h = frames[0].size
    strip = Image.new("RGBA", (w, h * len(frames)))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * h))
    return strip


_SIZE_CACHE = {}


def size_of(name):
    """(width, height) of a surface (of one frame if animated): 16 x 16 except baked atlases (bake.py)."""
    if name not in _SIZE_CACHE:
        _SIZE_CACHE[name] = SURFACES[name]().size
    return _SIZE_CACHE[name]


# Textures with lines that must continue from one face to the next (seams, bands).
# The model validator requires these lines to be mirror-symmetric.
ALIGNED = {"fabric"}


_ALPHA_CACHE = {}


def has_transparency(name):
    if name not in _ALPHA_CACHE:
        im = SURFACES[name]()
        _ALPHA_CACHE[name] = im.mode == "RGBA" and im.getchannel("A").getextrema()[0] < 255
    return _ALPHA_CACHE[name]


def line_mask(name, im, x, y):
    """True where the texture has its 'continuing' line (the darkest ramp step)."""
    if name == "fabric":
        return im.getpixel((x, y))[:3] == ramp("fabric_red", 0)[:3]
    return False


def icon_coke():
    """Coke: porous grey-black fuel lumps."""
    im = _blob([
        "  ####   ",
        " ####### ",
        "#########",
        "######## ",
        " ######  ",
    ], "steel", 104)
    px = im.load()
    for (x, y) in ((5, 6), (8, 7), (6, 9), (10, 8), (4, 8)):
        px[x, y] = ramp("soot", 0)  # pores
    return im


ICONS = {}  # real-content 2D icons; the test set's icons live in tools/assetgen/special.py
