"""
Job machines (economy server): six multi-block workstations, each in four looks by upgrade tier.

    tier 0  기본   wood and stone
    tier 1  1강    the same machine with extra parts attached (racks, tools, a second vessel...)
    tier 2  2강    the machine grown in its own trade: a jeweller's brass desk, a dancheong-painted mill
                   with a tiled roof, a fish market stall with floats, a smithy with a crest and a weapon
                   rack, a big kitchen with a lattice window and dried chillies, a copper column still
    tier 3  3강    glowing parts (gems, liquids, lanterns, runes)

Every tier keeps everything of the tier before it, so an upgrade always reads as "more".

Coordinates: model units (16 = 1 block), front = north (-Z, z = 0 is the front face), the anchor block is
x 0..16 / z 0..16 and the machine grows to +x (the player's left when facing it) and +z (away from the
player). Machines three blocks tall are built on a floor at y = -16 and placed with a furniture translation
of one block up (item models may only use -16..32 on every axis).

Spinning parts (grinding wheel, millstone) are separate models centred on (8, 8, 8) around their spin
axis; PARTS gives where their centre sits in the machine, in floor coordinates (anchor block floor = y 0).
"""

import math
import random
import zlib

from PIL import Image

from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.model import Model
from stsv_assets.palette import ramp, size

S = 16
TIERS = ("기본", "1강", "2강", "3강")


# ================================================================================================
# Surfaces
# ================================================================================================

def _surface(fn):
    T.SURFACES["mc_" + fn.__name__] = fn
    return fn


def _canvas():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def _fill(name, seed, base, noise=((0.10, -1), (0.08, 1))):
    """Plain surface of one ramp step with a sprinkle of the neighbouring steps."""
    rnd = random.Random(seed)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = base(x, y) if callable(base) else base
            r = rnd.random()
            acc = 0.0
            for chance, d in noise:
                acc += chance
                if r < acc:
                    i += d
                    break
            px[x, y] = ramp(name, i)
    return im, px, rnd


def _disc(px, name, draw):
    """Calls draw(x, y, d, a) for every pixel: d = distance from the centre (0..~11), a = angle."""
    for y in range(S):
        for x in range(S):
            dx, dy = x - 7.5, y - 7.5
            v = draw(x, y, math.hypot(dx, dy), math.atan2(dy, dx))
            if v is not None:
                px[x, y] = v if isinstance(v, tuple) and len(v) == 4 else ramp(name, v)


@_surface
def millstone():
    """Granite sides: speckled, faint chisel rows."""
    im, px, rnd = _fill("millstone", 401, 2, ((0.16, -1), (0.12, 1)))
    for y in (4, 11):
        for x in range(S):
            if rnd.random() < 0.55:
                px[x, y] = ramp("millstone", 1)
    return im


@_surface
def millstone_face():
    """Millstone seen from above: the dressed furrows (slanted, so turning is visible) and the dark eye."""
    im, px, rnd = _fill("millstone", 402, 2, ((0.14, -1), (0.1, 1)))

    def draw(x, y, d, a):
        if d < 2.0:
            return 0
        if d < 2.9:
            return 1 if x + y > 15 else 3
        if d > 7.6:
            return 3 if x + y < 15 else 1
        # eight sectors of slanted furrows
        k = ((a + math.pi) / (2 * math.pi) * 8 + d * 0.11) % 1.0
        if k < 0.16:
            return 1
        if k < 0.26:
            return 3
        return None
    _disc(px, "millstone", draw)
    return im


@_surface
def whetstone():
    """Grinding wheel rim: gritty sandstone, worn bands."""
    im, px, rnd = _fill("whetstone", 403, 2, ((0.18, -1), (0.14, 1)))
    for x in range(S):
        if rnd.random() < 0.5:
            px[x, 3] = ramp("whetstone", 1)
        if rnd.random() < 0.5:
            px[x, 12] = ramp("whetstone", 3)
    return im


@_surface
def whetstone_face():
    """Grinding wheel face: rings, iron hub and one dark notch so the spin reads."""
    im, px, rnd = _fill("whetstone", 404, 2, ((0.14, -1), (0.12, 1)))

    def draw(x, y, d, a):
        if d < 1.6:
            return ramp("iron", 3)
        if d < 2.6:
            return ramp("iron", 1) if x + y > 15 else ramp("iron", 4)
        if 5.0 < d < 5.9:
            return 1
        if d > 7.4:
            return 3 if x + y < 15 else 0
        if abs(a - 2.3) < 0.18 and d < 7.4:        # the notch
            return 0
        if abs(a - 2.3) < 0.36 and d < 7.4:
            return 3
        return None
    _disc(px, "whetstone", draw)
    return im


@_surface
def bark():
    rnd = random.Random(405)
    im = _canvas()
    px = im.load()
    cols = [rnd.choice((1, 2, 2, 3)) for _ in range(S)]
    for x in range(S):
        if rnd.random() < 0.3:
            cols[x] = 0
    for y in range(S):
        for x in range(S):
            i = cols[x]
            if rnd.random() < 0.1:
                i = max(0, i - 1)
            px[x, y] = ramp("bark", i)
    return im


@_surface
def log_top():
    """Sawn log end: growth rings inside a bark ring."""
    im, px, rnd = _fill("wood", 406, 2, ((0.06, -1),))

    def draw(x, y, d, a):
        if d > 7.0:
            return ramp("bark", 1 if x + y > 15 else 2)
        r = d + 0.4 * math.sin(a * 3)
        return 1 if int(r) % 3 == 0 and d > 0.8 else (3 if d < 0.8 else None)
    _disc(px, "wood", draw)
    return im


@_surface
def burlap():
    rnd = random.Random(407)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if x % 2 == 0 and y % 2 == 0:
                i = 3
            elif x % 2 == 1 and y % 2 == 1:
                i = 1
            if rnd.random() < 0.06:
                i = 1
            px[x, y] = ramp("burlap", i)
    return im


@_surface
def burlap_tie():
    """Sack top: gathered cloth with a twine band."""
    im = burlap()
    px = im.load()
    for y in range(S):
        for x in range(S):
            if (x + 2 * y) % 5 == 0:
                px[x, y] = ramp("burlap", 1)
    for x in range(S):
        px[x, 6] = ramp("straw", 1)
        px[x, 7] = ramp("straw", 3 if x % 3 else 2)
        px[x, 8] = ramp("straw", 1)
    return im


@_surface
def flour():
    im, px, rnd = _fill("flour", 408, 1, ((0.2, -1), (0.2, 1)))
    return im


@_surface
def grain():
    """Rice / wheat grains heaped in a hopper."""
    rnd = random.Random(409)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("straw", 1 + (x * 3 + y * 5) % 3)
    for _ in range(26):
        x, y = rnd.randrange(S), rnd.randrange(S - 1)
        px[x, y] = ramp("rice", 2)
        px[x, y + 1] = ramp("rice", 1)
    return im


@_surface
def ice():
    rnd = random.Random(410)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if x % 8 == 7 or y % 8 == 7:
                i = 0
            elif x % 8 == 0 or y % 8 == 0:
                i = 3
            elif (x - y) % 8 == 3 and rnd.random() < 0.8:
                i = 3
            elif rnd.random() < 0.08:
                i = 1
            px[x, y] = ramp("ice", i)
    return im


@_surface
def tile():
    """White wall/counter tiles, 4 px, grey grout."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            if x % 4 == 3 or y % 4 == 3:
                px[x, y] = ramp("millstone", 3)
            elif x % 4 == 0 and y % 4 == 0:
                px[x, y] = ramp("porcelain", 2)
            elif x % 4 == 2 or y % 4 == 2:
                px[x, y] = ramp("porcelain", 1)
            else:
                px[x, y] = ramp("porcelain", 2)
    return im


@_surface
def board():
    """Pale cutting board with knife marks."""
    rnd = random.Random(411)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 3 if (y % 5) else 2
            if rnd.random() < 0.06:
                i = 2
            px[x, y] = ramp("wood", i)
    for (x0, y0, n) in ((3, 4, 5), (8, 9, 6), (5, 12, 4)):
        for k in range(n):
            px[x0 + k, y0 - k // 2] = ramp("wood", 1)
    return im


def _fish(ramp_name, seed, belly="rice"):
    """Fish flank: dark back, bright belly, a few scale rows."""
    rnd = random.Random(seed)
    im = _canvas()
    px = im.load()
    n = size(ramp_name)
    for y in range(S):
        for x in range(S):
            if y < 5:
                i = 1
            elif y < 10:
                i = n - 2
            else:
                i = n - 1
            if 5 <= y < 10 and (x + 2 * y) % 4 == 0:
                i -= 1
            px[x, y] = ramp(ramp_name, i)
            if y >= 12:
                px[x, y] = ramp(belly, 2 if rnd.random() < 0.85 else 1)
    return im


@_surface
def fish_silver():
    return _fish("fish_silver", 412)


@_surface
def fish_red():
    return _fish("seabream", 413)


@_surface
def fish_dried():
    return _fish("doenjang", 414, belly="bean")


@_surface
def fish_flesh():
    """Salmon flesh: orange with pale fat lines."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("salmon", 3 if (x + y) % 4 else 1)
            if (x + y) % 4 == 2:
                px[x, y] = ramp("rice", 1)
    return im


@_surface
def iron_band():
    """Iron strap: a lit top row, dark bottom row, rivets."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if y % 8 == 0:
                i = 4
            elif y % 8 == 7:
                i = 0
            px[x, y] = ramp("iron", i)
    for x in (3, 11):
        for y in (3, 11):
            px[x, y] = ramp("steel", 5)
            px[x + 1, y + 1] = ramp("iron", 0)
    return im


@_surface
def embers():
    """Glowing coal bed (rendered with glow)."""
    rnd = random.Random(416)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            h = math.sin(x * 1.3) + math.sin(y * 1.7 + x * 0.4) + rnd.random() * 1.2
            if h > 1.4:
                px[x, y] = ramp("fire", 5)
            elif h > 0.7:
                px[x, y] = ramp("fire", 3)
            elif h > 0.1:
                px[x, y] = ramp("fire", 1)
            else:
                px[x, y] = ramp("soot", 0)
    return im


@_surface
def coal():
    rnd = random.Random(417)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            cx, cy = x % 5, (y + 2 * (x // 5)) % 5
            i = 0 if cx == 4 or cy == 4 else (3 if cx + cy < 2 else 1)
            px[x, y] = ramp("rubber", i if rnd.random() > 0.1 else 2)
    return im


@_surface
def clay():
    """Yellow-ochre clay plaster with set stones (traditional stove)."""
    im, px, rnd = _fill("clay", 418, 2, ((0.16, -1), (0.12, 1)))
    for (cx, cy) in ((3, 3), (11, 2), (7, 8), (2, 12), (13, 11)):
        for dx in range(3):
            for dy in range(2):
                px[(cx + dx) % S, (cy + dy) % S] = ramp("stone", 3 if dy == 0 else 1)
    return im


@_surface
def onggi():
    """Brown glazed earthenware with drip streaks."""
    rnd = random.Random(419)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if x in (3, 4) and y < 10:
                i = 3
            if (x * 7) % 11 == 0 and y > 4:
                i = 1
            px[x, y] = ramp("onggi", i if rnd.random() > 0.06 else i - 1)
    return im


@_surface
def copper():
    """Hammered copper (kettles): staggered dents, each lit top-left."""
    im, px, rnd = _fill("copper", 420, 2, ((0.06, -1),))
    for y in range(0, S, 4):
        for x in range((y // 4) % 2 * 2, S, 4):
            px[x, y] = ramp("copper", 4)
            px[(x + 1) % S, (y + 1) % S] = ramp("copper", 1)
    return im


@_surface
def steel_brushed():
    """Stainless counter: light steel, horizontal brushing."""
    rnd = random.Random(421)
    im = _canvas()
    px = im.load()
    rows = [rnd.choice((3, 4, 4, 4)) for _ in range(S)]
    for y in range(S):
        for x in range(S):
            i = rows[y]
            if rnd.random() < 0.08:
                i -= 1
            px[x, y] = ramp("steel", min(5, i))
    return im


@_surface
def glass():
    """Clear glass (opaque in game: thick boxes cannot be see-through): pale with a lit streak."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if x in (2, 3):
                i = 3
            elif x in (12, 13):
                i = 1
            px[x, y] = ramp("glass", i)
    return im


@_surface
def liquor():
    """Pale amber spirit seen through glass."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2 if x not in (2, 3) else 3
            if x in (12, 13):
                i = 1
            px[x, y] = ramp("straw", i)
    return im


@_surface
def glow_amber():
    rnd = random.Random(422)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("glow_amber", 1 if (x + y) % 5 else 2)
    for _ in range(9):
        x, y = rnd.randrange(1, S - 1), rnd.randrange(1, S - 1)
        px[x, y] = ramp("glow_amber", 3)
    return im


@_surface
def glow_cyan():
    rnd = random.Random(423)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("glow_cyan", 1 if (x * 2 + y) % 6 else 2)
    for _ in range(8):
        x, y = rnd.randrange(1, S - 1), rnd.randrange(1, S - 1)
        px[x, y] = ramp("glow_cyan", 3)
    return im


@_surface
def glow_trim():
    """3강 trim: gold band with glowing studs."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 3 if y % 4 == 0 else 2 if y % 4 != 3 else 0
            px[x, y] = ramp("gold", i)
            if y % 4 == 1 and x % 4 == 1:
                px[x, y] = ramp("glow_amber", 3)
                px[x + 1, y] = ramp("glow_amber", 2)
    return im


def _gem(ramp_name, seed):
    im = _canvas()
    px = im.load()
    n = size(ramp_name)
    for y in range(S):
        for x in range(S):
            band = (x - y) % 8
            i = n - 1 if band == 0 else n - 2 if band in (1, 7) else 1 + (x + y) % 2
            if band == 4:
                i = 0
            px[x, y] = ramp(ramp_name, i)
    return im


@_surface
def ruby():
    return _gem("ruby", 424)


@_surface
def sapphire():
    return _gem("sapphire", 425)


@_surface
def ore():
    """Raw ore lump: grey rock with red and blue crystal flecks."""
    rnd = random.Random(426)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("stone", rnd.choice((1, 2, 2, 3)))
    for (x, y, r) in ((3, 3, "ruby"), (10, 5, "sapphire"), (6, 11, "ruby"), (13, 12, "sapphire")):
        px[x, y] = ramp(r, 3)
        px[x + 1, y] = ramp(r, 2)
        px[x, y + 1] = ramp(r, 1)
    return im


@_surface
def leather():
    """Bellows leather with fold lines."""
    im, px, rnd = _fill("leather", 427, 2, ((0.1, -1),))
    for y in range(S):
        if y % 4 == 0:
            for x in range(S):
                px[x, y] = ramp("leather", 3)
        elif y % 4 == 3:
            for x in range(S):
                px[x, y] = ramp("leather", 0)
    return im


@_surface
def rope():
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("straw", 3 if (x + y) % 3 == 0 else 1 if (x + y) % 3 == 2 else 2)
    return im


@_surface
def porcelain():
    """White glazed bowls with a blue rim line."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("porcelain", 2 if x not in (11, 12) else 1)
            if y in (1, 2):
                px[x, y] = ramp("sapphire", 1)
    return im


def _veg(ramp_name, seed, lines=0):
    rnd = random.Random(seed)
    im = _canvas()
    px = im.load()
    n = size(ramp_name)
    for y in range(S):
        for x in range(S):
            i = n - 2 if x + y > 8 else n - 1
            if x + y > 22:
                i = 1
            if lines and x % lines == 0:
                i = max(0, i - 1)
            if rnd.random() < 0.05:
                i -= 1
            px[x, y] = ramp(ramp_name, i)
    return im


@_surface
def cabbage():
    return _veg("cabbage", 428, lines=4)


@_surface
def leaf():
    return _veg("leaf", 429, lines=5)


@_surface
def chili():
    return _veg("pepper", 430)


@_surface
def radish():
    return _veg("garlic", 431)


@_surface
def dough():
    return _veg("bean", 432)


@_surface
def soup():
    """Red stew surface."""
    rnd = random.Random(433)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("pepper", 2 if rnd.random() > 0.15 else 3)
    for (x, y) in ((4, 4), (11, 6), (7, 11)):
        px[x, y] = ramp("cabbage", 2)
        px[x + 1, y] = ramp("rice", 2)
    return im


@_surface
def water():
    im, px, rnd = _fill("water", 434, lambda x, y: 2 if (x + 2 * y) % 9 else 3, ((0.08, -1),))
    return im


@_surface
def molten():
    """3강 forge: molten metal in the crucible (glow)."""
    rnd = random.Random(435)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            v = math.sin(x * 0.9 + y * 0.4) + math.sin(y * 1.1 - x * 0.3)
            px[x, y] = ramp("fire", 5 if v > 1.0 else 4 if v > -0.2 else 3)
    return im


@_surface
def glass_gold():
    """Lantern pane: warm glowing glass with a cross bar."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - 7.5, y - 7.5)
            px[x, y] = ramp("lamp", 3 if d < 3.5 else 2 if d < 6 else 1)
            if x in (7, 8) or y in (7, 8):
                px[x, y] = ramp("iron", 2)
    return im


def _flame_frame(k, seed=440):
    """Cut-out flame for crossed planes: three tongues that rise and sway, 8 frames."""
    im = _canvas()
    px = im.load()
    for x in range(S):
        h = 8 + 4 * math.sin(x * 0.9 + k * 0.8) + 3 * math.sin(x * 0.37 - k * 0.55)
        h = max(2.0, min(15.0, h * (1 - abs(x - 7.5) / 10)))
        for y in range(S):
            up = S - 1 - y
            if up < h:
                t = up / h
                px[x, y] = ramp("fire", 5 if t < 0.35 else 4 if t < 0.6 else 3 if t < 0.85 else 2)
    return im


T.SURFACES["mc_flame"] = lambda: _flame_frame(0)
T.ANIMATED["mc_flame"] = (lambda: [_flame_frame(k) for k in range(8)], 2)


@_surface
def hearth_mouth():
    """Stove mouth (아궁이): dark arch, glowing coals at the bottom."""
    rnd = random.Random(441)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            if y >= 11:
                px[x, y] = ramp("fire", rnd.choice((2, 3, 4, 5)) if rnd.random() < 0.7 else 1)
            elif y >= 8:
                px[x, y] = ramp("fire", 1) if rnd.random() < (y - 7) * 0.25 else ramp("soot", 0)
            else:
                px[x, y] = ramp("soot", 0)
    return im


def _vertical(src):
    """The fish pictures turned for fish hanging head down."""
    return lambda: T.SURFACES[src]().transpose(Image.Transpose.ROTATE_90)


T.SURFACES["mc_fish_dried_v"] = _vertical("mc_fish_dried")
T.SURFACES["mc_fish_silver_v"] = _vertical("mc_fish_silver")


@_surface
def sight_glass():
    """Round window on the still: brass ring, amber liquid with bubbles."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - 7.5, y - 7.5)
            if d > 6.8:
                px[x, y] = ramp("brass", 1 if x + y > 15 else 3)
            elif y < 6:
                px[x, y] = ramp("glass", 2 if x > 4 else 3)
            else:
                px[x, y] = ramp("straw", 2 if (x * 3 + y) % 7 else 3)
    for (x, y) in ((5, 9), (9, 11), (7, 7), (10, 8)):
        px[x, y] = ramp("rice", 2)
    return im


@_surface
def pot_lid():
    """Cast iron cauldron lid seen from above: concentric ridges."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - 7.5, y - 7.5)
            i = 2
            if int(d) in (3, 6):
                i = 3 if x + y < 15 else 1
            if d > 7.3:
                i = 1
            px[x, y] = ramp("iron", i)
    return im


@_surface
def barrel_top():
    """Barrel lid: planks inside an iron hoop."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - 7.5, y - 7.5)
            if d > 6.8:
                px[x, y] = ramp("iron", 2 if x + y < 15 else 1)
            else:
                px[x, y] = ramp("wood_dark", 1 if x % 4 == 3 else 2)
    return im


@_surface
def jug_glass():
    """Glass jug side: amber liquid below a clear top."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            if y < 6:
                px[x, y] = ramp("glass", 3 if x in (2, 3) else 2)
            else:
                px[x, y] = ramp("straw", 3 if x in (2, 3) else 2 if y > 7 else 3)
    return im


@_surface
def shell():
    """Opened oyster shell, inside: pearly."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - 7.5, y - 7.5)
            px[x, y] = ramp("pearl", 2 if d < 5 else 1) if d < 7 else ramp("stone", 1)
    return im


# ================================================================================================
# Building helpers
# ================================================================================================

class Mat:
    """
    A material painted separately on every face, at the face's exact pixel size, the way hand-made block
    models are: the pattern (planks, grain, speckle, metal...) plus a 1 px bevel on the face's edges, light
    on the top and left, dark on the bottom and right, so each part reads on its own like vanilla blocks do.
    Faces wider than 16 px fall back to a tiling texture without the bevel.
    """

    def __init__(self, ramp_name, kind="plain", base=None, bevel=(1, -1), noise=0.10, glow=False, top=None, **opt):
        self.ramp, self.kind = ramp_name, kind
        self.base = base if base is not None else (size(ramp_name) - 1) // 2
        self.bevel, self.noise, self.glow, self.top, self.opt = bevel, noise, glow, top, opt

    def __call__(self, **changes):
        m = Mat(self.ramp, self.kind, self.base, self.bevel, self.noise, self.glow, self.top, **self.opt)
        for k, v in changes.items():
            if hasattr(m, k) and k != "opt":
                setattr(m, k, v)
            else:
                m.opt[k] = v
        return m

    # pattern: ramp index per pixel of a w x h face ----------------------------------------------
    def _index(self, w, h, rnd):
        b, n = self.base, self.noise
        grid = [[b] * S for _ in range(S)]
        k = self.kind
        if k == "planks" and h > 3:
            period = self.opt.get("period", 4)
            shades = [b + rnd.choice((0, 0, 0, 1, -1)) for _ in range(S)]
            joints = {p: rnd.randrange(3, max(4, w - 3)) if w > 9 else -1 for p in range(S)}
            for y in range(S):
                p = y // period
                for x in range(S):
                    i = shades[p]
                    if y % period == period - 1:
                        i = b - 1
                    elif x == joints[p]:
                        i = b - 1
                    grid[y][x] = i
        elif k in ("grain", "planks"):
            cols = [b + rnd.choice((0, 0, 0, 1, -1)) for _ in range(S)]
            for y in range(S):
                for x in range(S):
                    grid[y][x] = cols[x] if k == "grain" else cols[y]
        elif k == "speckle":
            for y in range(S):
                for x in range(S):
                    r = rnd.random()
                    grid[y][x] = b - 1 if r < 0.18 else b + 1 if r < 0.30 else b
            n = 0
        elif k == "weave":
            for y in range(S):
                for x in range(S):
                    grid[y][x] = b + 1 if (x % 2 == 0 and y % 2 == 0) else b - 1 if (x % 2 and y % 2) else b
        elif k == "metal":
            for y in range(S):
                for x in range(S):
                    grid[y][x] = b + 1 if x + y < min(w, h) * 0.4 else b
            if self.opt.get("rivets") and w >= 5 and h >= 5:
                for (x, y) in ((1, 1), (w - 2, 1), (1, h - 2), (w - 2, h - 2)):
                    grid[y][x] = size(self.ramp) - 1
            n = n * 0.5
        elif k == "band":
            for y in range(S):
                for x in range(S):
                    grid[y][x] = b
            if h >= 3:
                for x in range(2, w - 1, 4):
                    grid[h // 2][x] = size(self.ramp) - 1
        elif k == "panel":
            # recessed panel: 1 px frame, the inset lit from the top-left (its top/left inner edge in shadow)
            for y in range(S):
                for x in range(S):
                    i = b
                    if 1 <= x < w - 1 and 1 <= y < h - 1:
                        i = b - 1 if (x == 1 or y == 1) else b + 1 if (x == w - 2 or y == h - 2) else b
                    grid[y][x] = i
            n = n * 0.5
        elif k == "fish":
            # flank of a fish filling the face: dark back, belly of another ramp, gill line, eye, scale rows
            vertical, head_end = self.opt.get("vertical", False), self.opt.get("head", "start")
            L, A = (h, w) if vertical else (w, h)
            for y in range(S):
                for x in range(S):
                    along, across = (y, x) if vertical else (x, y)
                    u = along / max(1, L - 1)
                    if head_end == "end":
                        u = 1 - u
                    v = across / max(1, A - 1)
                    i = b
                    if v < 0.3:
                        i = b - 1
                    elif v > 0.72:
                        i = ("belly", 2)
                    elif (along + 2 * across) % 4 == 0 and u > 0.25:
                        i = b + 1
                    if 0.2 <= u < 0.27 and 0.2 < v < 0.85:
                        i = b - 2
                    grid[y][x] = i
            ex = round(0.1 * (L - 1)) if head_end == "start" else round(0.9 * (L - 1))
            ey = max(0, round(0.38 * (A - 1)))
            if vertical:
                grid[ex][ey] = ("eye", 0)
            else:
                grid[ey][ex] = ("eye", 0)
            n = 0
        if n:
            for y in range(S):
                for x in range(S):
                    r = rnd.random()
                    if r < n / 2:
                        grid[y][x] -= 1
                    elif r < n:
                        grid[y][x] += 1
        return grid

    def face(self, w, h, face, seed):
        """(texture name, uv) for a face of w x h model units."""
        if self.top is not None and face in ("up", "down"):
            return self.top.face(w, h, face, seed)
        pw, ph = max(1, math.ceil(w - 1e-6)), max(1, math.ceil(h - 1e-6))
        tile = pw > S or ph > S
        # crc32, not hash(): Python salts string hashes per run, which made the tiled faces change every build
        rnd = random.Random(seed if not tile else zlib.crc32(f"{self.ramp}|{self.kind}|{face}".encode()) & 0xffff)
        if tile:
            pw = ph = S
        grid = self._index(pw, ph, rnd)
        if self.bevel and not tile:
            lt, dk = self.bevel
            for y in range(ph):
                for x in range(pw):
                    d = 0
                    if ph > 1 and y == 0:
                        d += lt
                    if pw > 1 and x == 0:
                        d += lt
                    if ph > 1 and y == ph - 1:
                        d += dk
                    if pw > 1 and x == pw - 1:
                        d += dk
                    if d and not isinstance(grid[y][x], tuple):
                        grid[y][x] += max(-1, min(1, d))
        im = _canvas()
        px = im.load()
        top_i = size(self.ramp) - 1
        for y in range(S):
            for x in range(S):
                g = grid[y][x]
                if isinstance(g, tuple):
                    which, i = g
                    px[x, y] = ramp(self.opt.get("belly_ramp", "rice"), i) if which == "belly" else ramp("iron", 0)
                else:
                    px[x, y] = ramp(self.ramp, max(0, min(top_i, g)))
        key = "mx_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, (None if tile else [0, 0, round(w, 4), round(h, 4)])


def _face_size(f, frm, to):
    dx, dy, dz = (to[i] - frm[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[f]


class MModel(Model):
    """Model with a floor offset (tall machines are built on y = 0 and shifted to -16), auto-unique element
    names, and Mat materials painted per face."""

    def __init__(self, name, dy=0):
        super().__init__(name)
        self.dy = dy
        self._names = {}

    def box(self, name, frm, to, tex, faces=None, full_uv=(), glow=False, rotation=None, uv=None, side_v=None, shade=True):
        k = self._names.get(name, 0)
        self._names[name] = k + 1
        if k:
            name = f"{name}#{k}"
        fs = {f: tex for f in ("north", "east", "south", "west", "up", "down")}
        fs.update(faces or {})
        uv = dict(uv or {})
        for f, t in list(fs.items()):
            if isinstance(t, Mat):
                w, h = _face_size(f, frm, to)
                # faces of the same size, side and material look the same (one texture), which keeps the
                # resource pack small; the bevel and pattern still follow each face's own size
                name_t, region = t.face(w, h, f, zlib.crc32(f"{round(w, 2)}x{round(h, 2)}/{f}".encode()))
                fs[f] = name_t
                if region and f not in uv and f not in full_uv:
                    uv[f] = region
                glow = glow or t.glow
        first = next((t for t in fs.values() if t is not None), None)
        frm = (frm[0], frm[1] + self.dy, frm[2])
        to = (to[0], to[1] + self.dy, to[2])
        if rotation:
            axis, angle, o = rotation
            rotation = (axis, angle, (o[0], o[1] + self.dy, o[2]))
        return super().box(name, frm, to, first, faces=fs, full_uv=full_uv, glow=glow, rotation=rotation,
                           uv=uv, side_v=side_v, shade=shade)


# common materials (ramp, pattern, base step)
OAK = Mat("wood", "planks", 2)
OAK_GRAIN = Mat("wood", "grain", 2)
SPRUCE = Mat("wood_dark", "planks", 1)
SPRUCE_GRAIN = Mat("wood_dark", "grain", 1)
LOG = Mat("bark", "grain", 2)
IRON = Mat("iron", "metal", 2)
STEEL = Mat("steel", "metal", 3)
STEEL_DARK = Mat("steel", "metal", 2)
BRASS = Mat("brass", "metal", 2)
GOLD = Mat("gold", "metal", 2)
COPPER = Mat("copper", "metal", 2)
STONE = Mat("stone", "speckle", 2)
GRANITE = Mat("millstone", "speckle", 2)
SANDSTONE = Mat("whetstone", "speckle", 2)
CLAY = Mat("clay", "speckle", 2)
BURLAP = Mat("burlap", "weave", 2, bevel=(0, -1))
LEATHER = Mat("leather", "plain", 2)
RUBY = Mat("ruby", "plain", 2, bevel=(1, -1), noise=0)
SAPPHIRE = Mat("sapphire", "plain", 2, bevel=(1, -1), noise=0)
GLASS = Mat("glass", "plain", 2, noise=0)
WATER = Mat("water", "plain", 2, bevel=None)
CLOTH_RED = Mat("crimson", "weave", 2, bevel=(0, -1))


def bands(diameter):
    """Stepped circle of a given diameter: [(offset0, offset1, half_width)] from the centre, rows merged."""
    r = diameter / 2
    rows = []
    for i in range(int(round(diameter))):
        v = (i + 0.5) - r
        hw = math.sqrt(max(0.0, r * r - v * v))
        w = 2 * hw
        # keep the width on the same pixel parity as the diameter, so edges land on the pixel grid
        w = round((w - diameter % 2) / 2) * 2 + diameter % 2
        rows.append(max(1, w) / 2)
    out = []
    for i, hw in enumerate(rows):
        a, b = i - r, i + 1 - r
        if out and out[-1][2] == hw:
            out[-1] = (out[-1][0], b, hw)
        else:
            out.append((a, b, hw))
    return out


def cyl(m, name, axis, c, d, lo, hi, side, cap=None, cap2=None, glow=False, faces=None, clip=None):
    """
    Upright (axis y) or lying (axis x / z) cylinder of diameter d made of stepped boxes.
    c: centre on the two other axes, (x, z) for y, (y, z) for x, (x, y) for z
    cap: picture drawn over the whole round end (full circle mapped on the bounding square), cap2 the other end
    clip: (min, max) kept along the band direction (z for axis y, y for axes x / z), in absolute coordinates
    """
    cap2 = cap2 if cap2 is not None else cap
    if isinstance(side, Mat):
        side = side(bevel=None)
    for (a, b, hw) in bands(d):
        if clip:
            base = c[1]
            a, b = max(a, clip[0] - base), min(b, clip[1] - base)
            if b - a <= 1e-6:
                continue
        if axis == "y":
            frm, to = (c[0] - hw, lo, c[1] + a), (c[0] + hw, hi, c[1] + b)
        elif axis == "z":
            frm, to = (c[0] - hw, c[1] + a, lo), (c[0] + hw, c[1] + b, hi)
        else:
            frm, to = (lo, c[0] + a, c[1] - hw), (hi, c[0] + b, c[1] + hw)
        f = dict(faces or {})
        uv = {}
        r = d / 2

        def mp(v, cc):
            return round((v - (cc - r)) / d * 16, 4)
        if isinstance(cap, Mat) or isinstance(cap2, Mat):
            ends = {"y": ("up", "down"), "z": ("north", "south"), "x": ("west", "east")}[axis]
            for e, cp in zip(ends, (cap, cap2)):
                if cp is not None and e not in f:
                    f[e] = cp(bevel=None) if isinstance(cp, Mat) else cp
            m.box(name, frm, to, side, faces=f, glow=glow)
            continue
        if axis == "y":
            u0, u1, v0, v1 = mp(frm[0], c[0]), mp(to[0], c[0]), mp(frm[2], c[1]), mp(to[2], c[1])
            if cap and "up" not in f:
                f["up"] = cap
                uv["up"] = [u0, v0, u1, v1]
            if cap2 and "down" not in f:
                f["down"] = cap2
                uv["down"] = [u0, 16 - v1, u1, 16 - v0]
        elif axis == "z":
            u0, u1, v0, v1 = mp(frm[0], c[0]), mp(to[0], c[0]), mp(frm[1], c[1]), mp(to[1], c[1])
            if cap and "north" not in f:
                f["north"] = cap
                uv["north"] = [16 - u1, 16 - v1, 16 - u0, 16 - v0]
            if cap2 and "south" not in f:
                f["south"] = cap2
                uv["south"] = [u0, 16 - v1, u1, 16 - v0]
        else:
            u0, u1, v0, v1 = mp(frm[2], c[1]), mp(to[2], c[1]), mp(frm[1], c[0]), mp(to[1], c[0])
            if cap and "west" not in f:
                f["west"] = cap
                uv["west"] = [u0, 16 - v1, u1, 16 - v0]
            if cap2 and "east" not in f:
                f["east"] = cap2
                uv["east"] = [16 - u1, 16 - v1, 16 - u0, 16 - v0]
        m.box(name, frm, to, side, faces=f, uv=uv, glow=glow)


def ring(m, name, c, d_out, d_in, lo, hi, mat, skip=None):
    """Flat ring around the y axis, rasterised on the pixel grid and merged into as few boxes as possible.
    skip(x, z): leave out the pixel centred there (where the ring runs inside another part)."""
    n = int(round(d_out))
    x0, z0 = c[0] - d_out / 2, c[1] - d_out / 2
    cells = [[d_in / 2 <= math.hypot(x0 + i + 0.5 - c[0], z0 + j + 0.5 - c[1]) < d_out / 2
              and not (skip and skip(x0 + i + 0.5, z0 + j + 0.5)) for i in range(n)]
             for j in range(n)]
    used = [[False] * n for _ in range(n)]
    for j in range(n):
        i = 0
        while i < n:
            if not cells[j][i] or used[j][i]:
                i += 1
                continue
            i1 = i
            while i1 < n and cells[j][i1] and not used[j][i1]:
                i1 += 1
            j1 = j + 1
            while j1 < n and all(cells[j1][k] and not used[j1][k] for k in range(i, i1)) \
                    and (i == 0 or not cells[j1][i - 1] or used[j1][i - 1]) and (i1 == n or not cells[j1][i1]):
                j1 += 1
            for jj in range(j, j1):
                for k in range(i, i1):
                    used[jj][k] = True
            m.box(name, (x0 + i, lo, z0 + j), (x0 + i1, hi, z0 + j1), mat)
            i = i1


def flames(m, name, cx, cz, y0, w, h):
    """Two crossed cut-out planes of the animated flame (like vanilla fire), glowing, unshaded."""
    m.box(name, (cx - w / 2, y0, cz), (cx + w / 2, y0 + h, cz), "mc_flame", glow=True, shade=False,
          faces={"east": None, "west": None, "up": None, "down": None},
          uv={"north": [0, 0, 16, 16], "south": [16, 0, 0, 16]}, rotation=("y", 45, (cx, y0, cz)))
    m.box(name, (cx, y0, cz - w / 2), (cx, y0 + h, cz + w / 2), "mc_flame", glow=True, shade=False,
          faces={"north": None, "south": None, "up": None, "down": None},
          uv={"east": [0, 0, 16, 16], "west": [16, 0, 0, 16]}, rotation=("y", 45, (cx, y0, cz)))


def sack(m, x0, z0, w, h, tied=True):
    """Burlap sack standing on the floor (or a shelf at y = its floor)."""
    m.box("sack", (x0, 0, z0), (x0 + w, h, z0 + w), BURLAP)
    m.box("sack_top", (x0 + 1, h, z0 + 1), (x0 + w - 1, h + 1.5, z0 + w - 1), BURLAP)
    if tied:
        c = w / 2
        m.box("sack_neck", (x0 + c - 1, h + 1.5, z0 + c - 1), (x0 + c + 1, h + 3, z0 + c + 1), "mc_burlap_tie")
        m.box("sack_tuft", (x0 + c - 1.5, h + 3, z0 + c - 1.5), (x0 + c + 1.5, h + 4, z0 + c + 1.5), BURLAP)


def lantern(m, x, z, y_top, glow, chain=3):
    """Small hanging lantern: chain, iron cap, glass body (glowing at 3강)."""
    m.box("chain", (x - 0.25, y_top - chain, z - 0.25), (x + 0.25, y_top, z + 0.25), IRON)
    y = y_top - chain
    m.box("lantern_cap", (x - 2, y - 1, z - 2), (x + 2, y, z + 2), IRON)
    m.box("lantern_glass", (x - 1.75, y - 5, z - 1.75), (x + 1.75, y - 1, z + 1.75),
          "mc_glass_gold" if glow else "mc_glass", glow=glow, full_uv=["north", "south", "east", "west"],
          faces={"up": None})
    m.box("lantern_base", (x - 2, y - 6, z - 2), (x + 2, y - 5, z + 2), IRON)


def frame(m, name, x0, x1, y0, y1, z0, z1, t, tex, faces=None):
    """Open rectangular frame (four bars) in the plane of the thinnest extent."""
    m.box(name, (x0, y0, z0), (x1, y0 + t, z1), tex, faces=faces)
    m.box(name, (x0, y1 - t, z0), (x1, y1, z1), tex, faces=faces)
    m.box(name, (x0, y0 + t, z0), (x0 + t, y1 - t, z1), tex, faces=faces)
    m.box(name, (x1 - t, y0 + t, z0), (x1, y1 - t, z1), tex, faces=faces)


def display(m, size_blocks, floor):
    """Furniture display: GUI icon scaled to fit, centred on the machine's middle."""
    w, h, d = size_blocks
    big = max(w, h, d)
    scale = round(0.62 / big, 3)
    centre = (w * 8, floor + h * 8, d * 8)      # model units
    off = [centre[i] - 8 for i in range(3)]
    rot = presets._rot_xyz([30, 225, 0])
    t = [-sum(rot[i][j] * off[j] * scale for j in range(3)) for i in range(3)]
    m.display = presets.furniture(gui_scale=scale, gui_translation=tuple(round(v, 3) for v in t))
    for ctx in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        m.display[ctx] = dict(m.display[ctx])
        m.display[ctx]["scale"] = [round(v / big, 3) for v in m.display[ctx]["scale"]]
    return m


# ================================================================================================
# 세공대 (gem bench, 광부) 2 x 1 x 2: bench, grinding wheel on a pedestal over a water trough, gem tray
# ================================================================================================

def gem_bench(tier):
    m = MModel(f"gem_bench_{tier}")
    fine, glow = tier >= 2, tier >= 3
    m.group("bench")
    m.box("top", (0.5, 13, 0), (16, 16, 16), OAK)
    m.box("top", (16, 13, 0), (31.5, 16, 16), OAK)
    leg = SPRUCE_GRAIN
    for z in (1, 13):
        m.box("leg", (1, 0, z), (3, 13, z + 2), leg)
    m.box("apron", (3, 11, 1), (15, 13, 2), SPRUCE)
    m.box("shelf", (3, 3, 2), (15, 4, 14), SPRUCE)
    m.box("cabinet", (15, 0, 1), (31, 13, 15), SPRUCE)
    for (y0, y1) in ((1, 6.5), (7, 12)):
        m.box("drawer", (16, y0, 0.5), (30, y1, 1), Mat("wood_dark" if fine else "wood", "panel", 2), faces={"south": None})
        m.box("knob", (22, y0 + 2, 0), (24, y0 + 3.5, 0.5), BRASS, faces={"south": None})
        if glow:
            m.box("inlay", (26.5, y0 + 2, 0.25), (28, y0 + 3.5, 0.5), SAPPHIRE(glow=True), faces={"south": None})
    m.box("ore_crate", (4, 4, 2.5), (10, 8, 8.5), SPRUCE, faces={"up": "mc_ore"})

    m.group("trough")
    m.box("trough", (3, 16, 2), (13, 17, 11), SPRUCE)
    m.box("trough_wall", (3, 17, 2), (13, 19.5, 3), SPRUCE, faces={"down": None})
    m.box("trough_wall", (3, 17, 10), (13, 19.5, 11), SPRUCE, faces={"down": None})
    m.box("trough_wall", (3, 17, 3), (4, 19.5, 10), SPRUCE, faces={"down": None})
    m.box("trough_wall", (12, 17, 3), (13, 19.5, 10), SPRUCE, faces={"down": None})
    m.box("water", (4, 17, 3), (12, 18.5, 10), "mc_water", faces={f: None for f in ("north", "south", "east", "west", "down")})

    m.group("stand")
    body = BRASS(rivets=True) if fine else IRON(rivets=True)
    m.box("stand_foot", (4, 16, 11), (12, 17, 15), IRON)
    m.box("stand_column", (6, 17, 11.5), (10, 21, 14.5), IRON)
    m.box("bearing", (5, 21, 11), (11, 27, 15), body)
    m.box("axle", (7.5, 23.5, 9.5), (8.5, 24.5, 11), IRON, faces={"south": None})

    m.group("tray")
    m.box("tray", (18, 16, 3), (27, 17.5, 9), SPRUCE, faces={"up": CLOTH_RED})
    for (x0, z0, x1, z1) in ((18, 3, 27, 3.5), (18, 8.5, 27, 9), (18, 3.5, 18.5, 8.5), (26.5, 3.5, 27, 8.5)):
        m.box("tray_rim", (x0, 17.5, z0), (x1, 18, z1), BRASS if fine else SPRUCE, faces={"down": None})
    gems = ((19.5, 4.5, RUBY), (22, 6, SAPPHIRE), (24.5, 4.5, RUBY), (20, 6.8, SAPPHIRE))
    for (x, z, g) in gems:
        m.box("gem", (x, 17.5, z), (x + 1.5, 19, z + 1.5), g(glow=glow))
    m.box("big_gem", (23.5, 17.5, 6.5), (25.5, 19.5, 8.5), RUBY(glow=glow), rotation=("y", 45, (24.5, 18.5, 7.5)))
    if not glow:
        m.box("ore", (28, 16, 3), (31, 18.5, 6), "mc_ore")
        m.box("ore", (28.5, 16, 7), (30.5, 17.5, 9), "mc_ore")
    m.box("tweezers", (19, 16, 10.5), (25, 16.5, 11), STEEL, faces={"down": None})
    m.box("loupe", (26, 16, 10), (28, 17.5, 12), IRON, faces={"up": GLASS})

    if tier >= 1:
        m.group("rack")
        m.box("pegboard", (16, 16, 15), (31.5, 30, 16), OAK, faces={"down": None})
        m.box("rail", (16.5, 26, 14), (31.5, 27, 15), SPRUCE)
        # hammer, pliers, file, chisel
        m.box("hammer_handle", (18, 19, 14.5), (19, 26, 15), OAK_GRAIN, faces={"south": None})
        m.box("hammer_head", (16.5, 24, 14), (20.5, 25.5, 14.5), IRON)
        m.box("pliers", (21.5, 19, 14.5), (22.5, 26, 15), STEEL, faces={"south": None})
        m.box("pliers", (23, 19, 14.5), (24, 24.5, 15), STEEL, faces={"south": None})
        m.box("pliers_grip", (21.5, 19, 14), (22.5, 22, 14.5), Mat("crimson", "plain", 2))
        m.box("pliers_grip", (23, 19, 14), (24, 22, 14.5), Mat("crimson", "plain", 2))
        m.box("file", (26, 18, 14.5), (27, 26, 15), STEEL_DARK, faces={"south": None})
        m.box("file_grip", (25.75, 18, 14), (27.25, 21, 14.5), OAK_GRAIN)
        m.box("chisel", (29, 20, 14.5), (30, 26, 15), STEEL, faces={"south": None})
        m.box("chisel_grip", (28.75, 22, 14), (30.25, 26, 14.5), SPRUCE_GRAIN)
        m.group("magnifier")
        m.box("post", (30, 16, 11.75), (30.75, 27, 12.5), IRON)
        m.box("arm", (22.25, 27, 11.75), (30.75, 27.75, 12.5), IRON)
        m.box("arm2", (22.25, 27, 7.5), (23, 27.75, 11.75), IRON)
        m.box("stem", (22.25, 24, 7.5), (23, 27, 8.25), IRON)
        for (x0, z0, x1, z1) in ((20, 4.5, 25, 5.5), (20, 9.5, 25, 10.5), (20, 5.5, 21, 9.5), (24, 5.5, 25, 9.5)):
            m.box("lens_ring", (x0, 23, z0), (x1, 24, z1), BRASS)
        m.box("lens", (21, 23.25, 5.5), (24, 23.75, 9.5), Mat("lamp", "plain", 3, glow=True) if glow else GLASS)
        m.group("extra")
        m.box("bucket", (10.5, 4, 4), (14, 8.5, 8), SPRUCE, faces={"up": "mc_water"})
        m.box("bucket_band", (10.25, 6, 3.75), (14.25, 6.75, 8.25), IRON)

    if fine:
        # 2강 장인 공방: a brass edge and corner caps, dark walnut drawers, a brass-rimmed tray (above), a desk lamp
        m.group("craft")
        m.box("edge", (0.5, 15.25, -0.5), (31.5, 16, 0), BRASS, faces={"south": None})
        for x in (15, 30):
            m.box("cap", (x, 0, 0.25), (x + 1, 13, 1), BRASS, faces={"south": None})
        # a brass desk lamp in the back corner
        m.box("lamp_foot", (0.75, 16, 12.5), (3.25, 16.5, 15), BRASS)
        m.box("lamp_post", (1.75, 16.5, 13.5), (2.25, 23, 14), BRASS)
        m.box("lamp_arm", (1.75, 22.5, 9), (2.25, 23, 13.5), BRASS)
        m.box("lamp_shade", (0.75, 20.5, 7.5), (3.25, 22.5, 10), BRASS, faces={"down": Mat("lamp", "plain", 3, glow=glow, bevel=None)})
        # a polished ring on a little velvet stand by the magnifier
        m.box("stand", (27.25, 16, 9.25), (29.75, 18, 11.5), CLOTH_RED)
        m.box("ring", (27.75, 18, 9.85), (29.25, 19.5, 10.85), GOLD)
        m.box("ring_gem", (28.1, 19.5, 10), (28.9, 20.25, 10.7), SAPPHIRE(glow=glow))

    if glow:
        # 3강 보석 명장: gold trim, a jewelled crown on a cushion, glowing crystals and inlays
        m.group("glow")
        m.box("trim", (2, 12, -0.75), (30, 12.75, 0), "mc_glow_trim", glow=True)
        # the crown on a velvet cushion where the raw ore lay
        m.box("cushion", (27.5, 16, 2.5), (31.5, 17.5, 8), CLOTH_RED)
        m.box("crown_band", (28.25, 17.5, 3.25), (30.75, 18.75, 7.25), GOLD)
        for (x, z) in ((28.25, 3.25), (30, 3.25), (28.25, 6.5), (30, 6.5)):
            m.box("crown_point", (x, 18.75, z), (x + 0.75, 20, z + 0.75), GOLD)
        m.box("crown_gem", (29.1, 17.75, 3), (29.9, 18.5, 3.25), RUBY(glow=True), faces={"south": None})
        m.box("crown_top", (29.1, 18.75, 4.85), (29.9, 20.5, 5.65), SAPPHIRE(glow=True))
        m.box("crystal", (28, 16, 12.5), (30, 22, 14.5), Mat("crystal", "plain", 3, glow=True),
              rotation=("z", -22.5, (29, 16, 13.5)))
        m.box("crystal", (26.5, 16, 12), (28, 19.5, 13.5), Mat("crystal", "plain", 3, glow=True),
              rotation=("z", 22.5, (27.25, 16, 12.75)))
        m.box("crystal", (30, 16, 10), (31.5, 18.5, 11.5), Mat("crystal", "plain", 3, glow=True))
    return display(m, (2, 2, 1), 0)


def gem_bench_wheel(tier):
    """Grinding wheel, face to the front, spins around z. Centre (8, 8, 8)."""
    m = MModel(f"gem_bench_wheel_{tier}")
    m.group("wheel")
    cyl(m, "wheel", "z", (8, 8), 12, 6.5, 9.5, "mc_whetstone", cap="mc_whetstone_face")
    if tier >= 2:
        cyl(m, "collar", "z", (8, 8), 4, 6, 6.5, BRASS, cap=BRASS(bevel=None), faces={"south": None})
    if tier >= 3:
        cyl(m, "glow_ring", "z", (8, 8), 4, 5.5, 6, "crystal", cap="crystal", glow=True, faces={"south": None})
    m.display = presets.furniture(gui_scale=0.5)
    return m


# ================================================================================================
# 가공대 (mill, 농부) 2 x 2 x 2: a big millstone on a round stone plinth, hopper hung from a beam
# ================================================================================================

FLOUR = Mat("flour", "speckle", 1, bevel=None)
GRAIN = Mat("straw", "speckle", 2, bevel=None)


def mill(tier):
    m = MModel(f"mill_{tier}")
    fine, glow = tier >= 2, tier >= 3
    C = (16, 16)
    m.group("plinth")
    cyl(m, "plinth", "y", C, 22, 0, 6, "stone_bricks", cap=FLOUR)
    ring(m, "rim", C, 22, 19, 6, 7.5, STONE)
    cyl(m, "bed_stone", "y", C, 16, 6, 9.5, GRANITE, cap=GRANITE)
    # chute at the front and the flour bin under it
    m.box("chute", (14.5, 5, 3), (17.5, 6, 6), OAK, faces={"up": FLOUR})
    m.box("chute_side", (14, 5, 3), (14.5, 7, 6), OAK)
    m.box("chute_side", (17.5, 5, 3), (18, 7, 6), OAK)
    m.box("bin", (11, 0, 0.5), (21, 4, 5), SPRUCE, faces={"up": FLOUR})
    m.box("bin_rim", (11, 4, 0.5), (21, 4.5, 1), SPRUCE, faces={"down": None})
    m.box("bin_rim", (11, 4, 4.5), (21, 4.5, 5), SPRUCE, faces={"down": None})

    m.group("frame")
    post = Mat("crimson", "grain", 2) if fine else SPRUCE_GRAIN
    for x in (1, 28):
        m.box("brace", (x - 0.5, 0, 12), (x + 3.5, 1.5, 20), SPRUCE)
        m.box("post", (x, 1.5, 14), (x + 3, 14, 18), post)
        m.box("post", (x, 14, 14), (x + 3, 27, 18), post)
    m.box("beam", (0.5, 27, 14), (16, 30, 18), OAK)
    m.box("beam", (16, 27, 14), (31.5, 30, 18), OAK)
    m.group("hopper")
    m.box("hopper", (10, 26, 10), (22, 29, 22), OAK, faces={"up": GRAIN})
    m.box("hopper", (11, 24, 11), (21, 26, 21), OAK)
    m.box("hopper", (12, 22, 12), (20, 24, 20), OAK)
    m.box("hopper", (13, 20, 13), (19, 22, 19), OAK)
    m.box("spout", (15, 17, 15), (17, 20, 17), SPRUCE)

    m.group("sacks")
    sack(m, 24, 24, 7, 8)
    m.box("scoop", (17, 4.5, 2), (19, 5.5, 3.5), OAK_GRAIN)

    if tier >= 1:
        m.group("extra")
        sack(m, 1.5, 24.5, 6, 7)
        m.box("sack_lying", (21, 0, 1), (30, 4, 6), BURLAP, faces={"east": GRAIN})
        m.box("shelf", (4, 9, 14.5), (7, 9.75, 17.5), SPRUCE)
        m.box("jar", (4.5, 9.75, 15), (6.5, 12.5, 17), "mc_onggi")
        m.box("jar_lid", (4.25, 12.5, 14.75), (6.75, 13, 17.25), Mat("onggi", "plain", 1))
        m.box("sieve", (24, 0, 18.5), (25, 8, 26), SPRUCE, rotation=("z", -22.5, (24, 0, 22)))
        m.box("sieve_mesh", (24.25, 1, 19.5), (24.75, 7, 25), "eco_grate", rotation=("z", -22.5, (24, 0, 22)))

    if fine:
        # 2강 방앗간: posts in 단청 red with green and gold bands, a tiled roof over the beam, a straw rope round the plinth
        m.group("dancheong")
        green, gold_band = Mat("leaf_dark", "plain", 2), Mat("lamp", "plain", 1)
        for x in (1, 28):
            m.box("band", (x - 0.25, 24, 13.75), (x + 3.25, 26, 18.25), green)
            m.box("band", (x - 0.25, 23.5, 13.75), (x + 3.25, 24, 18.25), gold_band)
            m.box("foot", (x - 0.25, 1.5, 13.75), (x + 3.25, 3, 18.25), Mat("stone", "speckle", 3))
        for (x0, x1) in ((0.5, 10), (22, 31.5)):          # the hopper hides the middle of the beam
            m.box("beam_band", (x0, 28, 13.75), (x1, 29, 14), green, faces={"south": None})
            m.box("beam_band", (x0, 28, 18), (x1, 29, 18.25), green, faces={"north": None})
        roof, ridge = Mat("blacksteel", "planks", 2, period=2), Mat("blacksteel", "plain", 1)
        m.box("roof", (0, 30, 12), (16, 31, 20), roof)
        m.box("roof", (16, 30, 12), (32, 31, 20), roof)
        m.box("eave", (0, 29.5, 11), (16, 30.25, 12), roof, faces={"up": roof})
        m.box("eave", (16, 29.5, 11), (32, 30.25, 12), roof)
        m.box("eave", (0, 29.5, 20), (16, 30.25, 21), roof)
        m.box("eave", (16, 29.5, 20), (32, 30.25, 21), roof)
        end = 1.5 if glow else 0                          # 3강: gold caps take the ridge's ends
        m.box("ridge", (end, 31, 15), (16, 32, 17), ridge)
        m.box("ridge", (16, 31, 15), (32 - end, 32, 17), ridge)
        ring(m, "rope", C, 23, 21.5, 2, 3.5, Mat("straw", "weave", 2, bevel=None))
        m.box("basket", (1.5, 0, 1.5), (7.5, 4, 7.5), Mat("straw", "weave", 1), faces={"up": GRAIN})

    if glow:
        # 3강 풍년 방앗간: gold on the roof ends, lanterns, the grain in the bin and the hopper shining
        m.group("glow")
        lantern(m, 7, 16, 27, True)
        lantern(m, 25, 16, 27, True)
        for x in (0, 30.5):
            m.box("ridge_end", (x, 31, 14.5), (x + 1.5, 32, 17.5), GOLD)
        m.box("gold_grain", (11.5, 4, 1), (20.5, 4.75, 4.5), Mat("lamp", "speckle", 2, glow=True, bevel=None),
              faces={"down": None})
        m.box("gold_grain", (10.5, 29, 10.5), (21.5, 29.25, 21.5), Mat("lamp", "speckle", 2, glow=True, bevel=None),
              faces={"down": None})
    return display(m, (2, 2, 2), 0)


def mill_stone(tier):
    """Runner stone with its handle, spins around y. Centre (8, 8, 8)."""
    m = MModel(f"mill_stone_{tier}")
    m.group("stone")
    cyl(m, "runner", "y", (8, 8), 14, 5.5, 10.5, GRANITE, cap="mc_millstone_face", cap2=GRANITE)
    m.box("handle", (12, 10.5, 7), (14, 17.5, 9), OAK_GRAIN)
    m.box("handle_cap", (11.75, 17.5, 6.75), (14.25, 18, 9.25), SPRUCE)
    if tier >= 2:
        # a red cloth wound round the handle (단청 colours, like the frame)
        m.box("handle_band", (11.75, 13, 6.75), (14.25, 15, 9.25), Mat("crimson", "weave", 2, bevel=None))
    if tier >= 3:
        ring(m, "glow_ring", (8, 8), 8, 6, 10.5, 10.75, Mat("glow_amber", "plain", 2, glow=True, bevel=None))
    m.display = presets.furniture(gui_scale=0.5)
    return m


# ================================================================================================
# 손질대 (fish bench, 어부) 2 x 1 x 2: counter with a cutting board, an ice tray, fish hung on a rail
# ================================================================================================

def fish_flat(m, name, x0, x1, z0, z1, y0, skin, belly="rice", head="west", thick=1.5):
    """Fish lying on its side (flank up), head to the west or east, forked tail plate at the other end."""
    flank = Mat(skin, "fish", 2, belly_ramp=belly, head="start" if head == "west" else "end", bevel=None)
    back, under = Mat(skin, "plain", 1), Mat(belly, "plain", 2)
    m.box(name, (x0, y0, z0), (x1, y0 + thick, z1), back, faces={"up": flank, "south": under, "down": None})
    tx0, tx1 = (x1, x1 + 2) if head == "west" else (x0 - 2, x0)
    w = z1 - z0
    m.box(name + "_tail", (tx0, y0, z0 - 0.5), (tx1, y0 + 0.5, z0 + w / 2 - 0.25), Mat(skin, "plain", 1), faces={"down": None})
    m.box(name + "_tail", (tx0, y0, z0 + w / 2 + 0.25), (tx1, y0 + 0.5, z1 + 0.5), Mat(skin, "plain", 1), faces={"down": None})


def fish_hanging(m, name, x0, y0, y1, z0, skin, belly="rice", w=3, thick=1.5):
    """Fish hung by the tail from a hook: head down, flank to the front, forked tail at the top."""
    flank = Mat(skin, "fish", 2, belly_ramp=belly, vertical=True, head="end", bevel=None)
    edge = Mat(skin, "plain", 1)
    m.box(name, (x0, y0, z0), (x0 + w, y1, z0 + thick), edge, faces={"north": flank, "south": flank})
    m.box(name + "_waist", (x0 + w / 2 - 0.75, y1, z0 + 0.25), (x0 + w / 2 + 0.75, y1 + 1, z0 + thick - 0.25), edge)
    m.box(name + "_tail", (x0 - 0.5, y1 + 1, z0 + 0.5), (x0 + w / 2 - 0.25, y1 + 2.5, z0 + 1), edge)
    m.box(name + "_tail", (x0 + w / 2 + 0.25, y1 + 1, z0 + 0.5), (x0 + w + 0.5, y1 + 2.5, z0 + 1), edge)
    m.box(name + "_hook", (x0 + w / 2 - 0.25, y1 + 1, z0 + 0.5), (x0 + w / 2 + 0.25, y1 + 4, z0 + 1), IRON)


def fish_bench(tier):
    m = MModel(f"fish_bench_{tier}")
    fine, glow = tier >= 2, tier >= 3
    m.group("counter")
    m.box("body", (1, 0, 1), (16, 13, 15), SPRUCE)
    m.box("body", (16, 0, 1), (31, 13, 15), SPRUCE)
    for (x0, x1) in ((2, 15), (17, 30)):
        # 2강: doors painted sea blue
        m.box("door", (x0, 1, 0.5), (x1, 12, 1), Mat("sapphire" if fine else "wood", "panel", 1 if fine else 2),
              faces={"south": None})
        hx = x1 - 3 if x0 < 16 else x0 + 2
        m.box("handle", (hx, 5, 0), (hx + 1, 9, 0.5), BRASS, faces={"south": None})
    # 2강: a white tiled counter, like a fish market's
    top = OAK
    m.box("top", (0.5, 13, 0), (16, 15, 16), top, faces={"up": "mc_tile", "north": "mc_tile"} if fine else None,
          full_uv=["up", "north"] if fine else ())
    m.box("top", (16, 13, 0), (31.5, 15, 16), top, faces={"up": "mc_tile", "north": "mc_tile"} if fine else None,
          full_uv=["up", "north"] if fine else ())

    m.group("board")
    m.box("board", (2, 15, 2.5), (14, 16.5, 11.5), Mat("wood", "plain", 3))
    fish_flat(m, "salmon", 3, 10, 3.5, 7, 16.5, "fish_silver", belly="salmon", head="west")
    m.box("fillet", (3.5, 16.5, 8), (8, 17.25, 10), "mc_fish_flesh")
    m.box("fillet", (8.5, 16.5, 8.25), (11.5, 17.25, 10), "mc_fish_flesh")
    m.box("knife_blade", (3, 16.5, 10.5), (8.5, 17, 11.25), Mat("blade", "metal", 2), faces={"down": None})
    m.box("knife_grip", (8.5, 16.5, 10.25), (12, 17.5, 11.25), SPRUCE_GRAIN)

    m.group("ice")
    m.box("tray", (17, 15, 2), (30, 16, 12), SPRUCE)
    for (x0, z0, x1, z1) in ((17, 2, 30, 3), (17, 11, 30, 12), (17, 3, 18, 11), (29, 3, 30, 11)):
        m.box("tray_wall", (x0, 16, z0), (x1, 17.5, z1), SPRUCE, faces={"down": None})
    m.box("ice", (18, 16, 3), (29, 17, 11), "mc_ice", faces={f: None for f in ("north", "south", "east", "west", "down")})
    fish_flat(m, "seabream", 21, 28, 3.75, 6.75, 17, "seabream", head="east")
    fish_flat(m, "cod", 21, 28.5, 7.75, 10.25, 17, "fish_silver", head="east")
    for (x, z) in ((18.05, 5.25), (18.05, 9.5)):
        m.box("cube", (x, 17, z), (x + 0.9, 17.9, z + 0.9), "mc_ice")

    if tier >= 1:
        m.group("rail")
        pole = SPRUCE_GRAIN
        for x in (0.5, 30):
            m.box("pole", (x, 15, 13.5), (x + 1.5, 30, 15), pole)
        m.box("bar", (0.5, 30, 13.25), (16, 31.5, 15.25), pole)
        m.box("bar", (16, 30, 13.25), (31.5, 31.5, 15.25), pole)
        for (x, skin, belly) in ((4, "doenjang", "bean"), (8.5, "doenjang", "bean"), (21.5, "fish_silver", "rice"),
                                 (26, "doenjang", "bean")):
            fish_hanging(m, "fish", x, 18, 26, 13.25, skin, belly)
        m.box("knife_rack", (14, 24, 14.5), (19, 25, 15.25), IRON)
        for x in (14.5, 16.5):
            m.box("rack_knife", (x, 19, 14.25), (x + 1, 24, 14.5), Mat("blade", "metal", 2))
            m.box("rack_grip", (x, 25, 14.25), (x + 1, 27.5, 14.75), SPRUCE_GRAIN)

    if fine:
        # 2강 수산 시장: rope round the ice tray, red and white floats on the rail, a white tiled sink
        m.group("market")
        rope = Mat("straw", "weave", 2, bevel=None)
        for (x0, z0, x1, z1) in ((16.75, 1.75, 30.25, 2.25), (16.75, 11.75, 30.25, 12.25)):
            m.box("tray_rope", (x0, 16.5, z0), (x1, 17.25, z1), rope)
        for (x, mat) in ((12.5, Mat("crimson", "plain", 3)), (18, Mat("porcelain", "plain", 2))):
            m.box("float_line", (x + 0.75, 28.5, 14), (x + 1.25, 30, 14.5), rope)
            m.box("float", (x, 26, 13.25), (x + 2, 28.5, 15.25), mat)
            m.box("float_band", (x - 0.1, 27, 13.15), (x + 2.1, 27.5, 15.35), Mat("porcelain" if x < 16 else "crimson", "plain", 2))
        if tier < 3:
            m.box("sink", (2, 15, 11.5), (13.5, 15.75, 14.5), Mat("porcelain", "plain", 2), faces={"up": "mc_water"})

    if glow:
        # 3강 용궁 어시장: lanterns, a glowing live-fish basin where the sink was, a pearl in its shell
        m.group("glow")
        lantern(m, 3.5, 11, 30, True)
        lantern(m, 28, 11, 30, True)
        m.box("tank_floor", (2, 15, 11.5), (13.5, 15.5, 14.75), Mat("straw", "speckle", 3))
        for (x0, x1) in ((2, 2.5), (13, 13.5)):
            m.box("tank_frame", (x0, 15.5, 11.5), (x1, 18, 14.75), BRASS)
        for (z0, z1) in ((11.5, 12), (14.25, 14.75)):
            m.box("tank_rim", (2.5, 17.5, z0), (13, 18, z1), BRASS)
        m.box("tank_water", (2.5, 15.5, 11.75), (13, 17.5, 14.5), "mc_glow_cyan", glow=True)
        m.box("tank_fish", (5, 15.75, 11.6), (8.5, 17, 11.75), Mat("seabream", "plain", 3), faces={"south": None})
        m.box("tank_fish", (9, 16, 11.6), (11.5, 16.75, 11.75), Mat("lamp", "plain", 2), faces={"south": None})
        m.box("shell", (13.5, 15, 12), (16.5, 15.75, 15), "mc_shell", faces={"up": "mc_shell"})
        m.box("pearl", (14.5, 15.75, 13), (15.5, 16.75, 14), Mat("pearl", "plain", 2, glow=True))
    return display(m, (2, 2, 1), 0)


# ================================================================================================
# 대장간 (forge, 대장장이) 2 x 2 x 3: brick hearth under a hood and chimney, bellows, anvil, quench trough
# ================================================================================================

def forge(tier):
    m = MModel(f"forge_{tier}", dy=-16)
    fine, glow = tier >= 2, tier >= 3
    m.group("hearth")
    # hearth base (x 14..32, z 12..32), coal pit in the middle
    m.box("base", (13, 0, 12), (31, 11, 32), "stone_bricks")
    for (x0, z0, x1, z1) in ((13, 12, 31, 14.5), (13, 28.5, 31, 32), (13, 14.5, 15.5, 28.5), (28.5, 14.5, 31, 28.5)):
        m.box("pit_rim", (x0, 11, z0), (x1, 13, z1), STONE)
    m.box("coals", (15.5, 11, 14.5), (28.5, 11.5, 28.5), "mc_embers", glow=True,
          faces={f: None for f in ("north", "south", "east", "west", "down")})
    flames(m, "fire", 22, 21.5, 11.5, 8, 8)
    # back wall and the hood
    m.box("back", (13, 13, 29), (31, 24, 32), "brick")
    hood = "brick_soot"
    for x in (13, 29):
        m.box("pillar", (x, 13, 12), (x + 2, 24, 14.5), STONE)
    m.box("hood", (12.5, 24, 11.5), (31.5, 28, 32), hood)
    m.box("hood", (15, 28, 14), (30, 32, 31), hood)
    m.box("hood", (17.5, 32, 17), (28, 36, 28), hood)
    chim = "brick_soot"
    m.box("chimney", (19.5, 36, 19), (26, 47.5 if glow else 48, 26), chim)

    m.group("bellows")
    m.box("stand", (4, 0, 21), (12, 5, 30), SPRUCE)
    m.box("bellows_board", (3.5, 5, 21.5), (12.5, 6, 30.5), OAK)
    m.box("bellows_leather", (4.5, 6, 22.5), (11.5, 9, 29.5), "mc_leather")
    m.box("bellows_board", (3.5, 9, 21.5), (12.5, 10, 30.5), OAK)
    m.box("nozzle", (12.5, 6.5, 25), (13, 8.5, 27), BRASS)
    m.box("lever", (6, 10, 25.5), (7, 18, 26.5), SPRUCE_GRAIN)
    m.box("lever_grip", (5.5, 18, 24), (7.5, 19, 28), SPRUCE_GRAIN)

    m.group("anvil")
    cyl(m, "stump", "y", (7, 7), 10, 0, 8, "mc_bark", cap="mc_log_top")
    anvil = IRON
    m.box("anvil_foot", (3.5, 8, 4.5), (10.5, 10, 9.5), anvil)
    m.box("anvil_waist", (4.5, 10, 5.5), (9.5, 13, 8.5), anvil)
    m.box("anvil_face", (2.5, 13, 4), (11.5, 16, 10), anvil, faces={"up": Mat("steel", "metal", 4)})
    m.box("anvil_horn", (0, 13.5, 5.5), (2.5, 15.5, 8.5), anvil)
    m.box("anvil_horn_tip", (-1.5, 14, 6.25), (0, 15, 7.75), anvil)
    m.box("anvil_heel", (11.5, 14, 5), (13, 16, 9), anvil)
    m.box("hammer_head", (5, 16, 5), (8, 17.5, 6.5), IRON)
    m.box("hammer_handle", (5.75, 16, 6.5), (7.25, 17, 11.5), OAK_GRAIN)

    m.group("trough")
    trough = Mat("stone", "speckle", 2) if fine else SPRUCE
    m.box("trough", (17, 0, 1), (31, 1, 9), trough)
    for (x0, z0, x1, z1) in ((17, 1, 31, 2), (17, 8, 31, 9), (17, 2, 18, 8), (30, 2, 31, 8)):
        m.box("trough_wall", (x0, 1, z0), (x1, 7, z1), trough, faces={"down": None})
    m.box("water", (18, 1, 2), (30, 6, 8), "mc_water", faces={f: None for f in ("north", "south", "east", "west", "down")})
    m.box("tongs", (20, 5, 4), (21, 10, 5), IRON, rotation=("z", -22.5, (20.5, 5, 4.5)))

    if tier >= 1:
        m.group("tools")
        # tools hung on the hearth's outer side: tongs, hammer, poker
        m.box("tool_rail", (31, 20, 14), (32, 21, 30), SPRUCE)
        m.box("tongs", (31.25, 12, 16), (31.75, 20, 16.75), IRON)
        m.box("tongs", (31.25, 12, 17.5), (31.75, 20, 18.25), IRON)
        m.box("tongs_jaw", (31.25, 11, 16.5), (31.75, 12, 17.75), IRON)
        m.box("hammer", (31.25, 14, 21.5), (31.75, 20, 22.25), OAK_GRAIN)
        m.box("hammer_head", (31, 13, 20.5), (32, 14.5, 23.25), IRON)
        m.box("poker", (31.25, 10, 26), (31.75, 20, 26.75), IRON)
        m.box("poker_hook", (31.25, 10, 26.75), (31.75, 10.75, 28), IRON)
        m.box("coal_bin", (4, 0, 13), (12, 4, 19), SPRUCE, faces={"up": "mc_coal"})
        ingot = Mat("fire", "metal", 4, glow=True) if glow else Mat("steel", "metal", 4)
        for k, y in enumerate((13, 13.75)):
            m.box("ingot", (23 + k * 0.5, y, 12.25), (27 + k * 0.5, y + 0.75, 14.25), ingot)

    if fine:
        # 2강 대장장이 공방: a carved stone lintel and a brass crossed-hammers crest on the hood, a stone cap on the
        # chimney, a rack of finished swords and an axe at the back
        m.group("smithy")
        light = Mat("stone", "speckle", 3)
        m.box("lintel", (12.25, 23, 11.25), (31.75, 24.5, 12), light)
        for (a, rot) in ((-45, 1), (45, -1)):
            m.box("crest_handle", (21.5, 24.5, 11.1), (22.5, 28, 11.4), BRASS, rotation=("z", a, (22, 26.25, 11.25)))
        for (x0, x1) in ((19.75, 21.75), (22.25, 24.25)):
            m.box("crest_head", (x0, 27, 11.05), (x1, 28.25, 11.45), BRASS)
        m.box("chimney_cap", (18.5, 46.5, 18), (27, 47.5, 27), light)
        m.box("rack", (1, 4, 31), (12.5, 25, 32), OAK)
        m.box("rack_rail", (0.5, 22, 30.25), (13, 23, 31), SPRUCE)
        blade = Mat("blade", "metal", 2)
        for x in (2.5, 5.5):
            m.box("sword_blade", (x, 10.5, 30.4), (x + 1, 22, 30.9), blade)
            m.box("sword_guard", (x - 1, 9.5, 30.25), (x + 2, 10.5, 31), BRASS)
            m.box("sword_grip", (x, 6.5, 30.4), (x + 1, 9.5, 30.9), LEATHER)
            m.box("sword_pommel", (x - 0.25, 5.75, 30.3), (x + 1.25, 6.5, 31), BRASS)
        m.box("axe_handle", (9.5, 6, 30.4), (10.5, 21, 30.9), OAK_GRAIN)
        m.box("axe_head", (8, 17, 30.3), (11.5, 20.5, 31), IRON)
        m.box("axe_edge", (7.5, 16.5, 30.3), (8, 21, 31), blade)

    if glow:
        m.group("glow")
        m.box("crucible", (19.5, 11.5, 18.5), (24.5, 15.5, 23.5), Mat("millstone", "speckle", 1))
        m.box("molten", (20, 15.5, 19), (24, 15.75, 23), "mc_molten", glow=True, faces={"down": None})
        m.box("trim", (12.5, 24.5, 11), (31.5, 25.5, 11.5), "mc_glow_trim", glow=True)
        m.box("rune", (4, 14, 3.75), (10, 15, 4), Mat("glow_amber", "plain", 2, glow=True), faces={"south": None})
        # the masterwork: the first sword on the rack glows, a gold crest
        m.box("sword_glow", (2.5, 10.5, 30.3), (3.5, 22, 30.4), Mat("magic", "plain", 2, glow=True), faces={"south": None})
        for (x0, x1) in ((19.75, 21.75), (22.25, 24.25)):
            m.box("crest_gold", (x0, 27, 10.95), (x1, 28.25, 11.05), GOLD, faces={"south": None})
        m.box("hot_blade", (4, 16, 6.5), (10.5, 16.75, 8), Mat("fire", "metal", 4, glow=True))
        m.box("hot_tang", (10.5, 16, 7), (12.5, 16.5, 7.5), Mat("fire", "metal", 2, glow=True))
        m.box("embers_top", (20, 47.5, 19.5), (25.5, 47.75, 25.5), "mc_embers", glow=True, faces={"down": None})
        for (x0, z0, x1, z1) in ((19.5, 19, 26, 19.5), (19.5, 25.5, 26, 26), (19.5, 19.5, 20, 25.5), (25.5, 19.5, 26, 25.5)):
            m.box("chimney_rim", (x0, 47.5, z0), (x1, 48, z1), chim, faces={"down": None})
    return display(m, (2, 3, 2), -16)


# ================================================================================================
# 조리대 (kitchen, 요리사) 3 x 1 x 2: clay stove with an iron cauldron, prep counter, jar shelf
# ================================================================================================

ONGGI = Mat("onggi", "plain", 2)


def _jar(m, cx, cz, d, h, y0=0):
    """장독: narrow foot, wide belly, shoulder, short neck and a flat lid."""
    lid = Mat("onggi", "plain", 1)
    body = Mat("onggi", "metal", 2, noise=0.05)
    prof = ((d - 2, 0, 0.15), (d, 0.15, 0.7), (d - 2, 0.7, 0.85), (d - 4, 0.85, 1.0))
    for (dd, a, b) in prof:
        cyl(m, "jar", "y", (cx, cz), dd, y0 + round(h * a * 2) / 2, y0 + round(h * b * 2) / 2, body(base=3 if a == 0.15 else 2),
            cap=body(base=3))
    cyl(m, "jar_lid", "y", (cx, cz), d - 3, y0 + h, y0 + h + 1, lid, cap=lid)
    m.box("jar_knob", (cx - 0.75, y0 + h + 1, cz - 0.75), (cx + 0.75, y0 + h + 1.75, cz + 0.75), lid)


def kitchen(tier):
    m = MModel(f"kitchen_{tier}")
    fine, glow = tier >= 2, tier >= 3
    m.group("stove")
    # clay stove (x 16..32)
    m.box("stove", (16.5, 0, 0.5), (31.5, 13, 15.5), CLAY)
    m.box("stove_top", (16, 13, 0), (32, 14, 16), Mat("clay", "speckle", 3))
    m.box("mouth", (20, 1, 0.25), (28, 8, 0.5), "mc_hearth_mouth", full_uv=["north"], glow=True, faces={"south": None})
    m.box("arch", (19, 8, 0), (29, 9.5, 0.5), Mat("stone", "speckle", 3), faces={"south": None})
    for x in (19, 28):
        m.box("jamb", (x, 0, 0), (x + 1, 8, 0.5), Mat("stone", "speckle", 3), faces={"south": None})
    cyl(m, "cauldron", "y", (24, 7.5), 12, 14, 17, IRON, cap=IRON)
    ring(m, "brim", (24, 7.5), 14, 12, 16, 17, IRON)
    cyl(m, "lid", "y", (24, 7.5), 11, 17, 18.5, IRON, cap="mc_pot_lid")
    m.box("lid_knob", (23, 18.5, 6.5), (25, 20, 8.5), GOLD if glow else SPRUCE_GRAIN)
    m.box("chimney", (27, 14, 11), (31, 31, 15), CLAY)
    m.box("chimney_cap", (26.5, 31, 10.5), (31.5, 32, 15.5), Mat("stone", "speckle", 2))

    m.group("counter")
    m.box("cabinet", (0, 0, 1), (16, 13, 15), SPRUCE)
    # 3강 수라간: the door in red lacquer with a gold handle
    m.box("door", (1, 1, 0.5), (15, 12, 1), Mat("crimson" if glow else "wood", "panel", 2), faces={"south": None})
    m.box("handle", (12, 5, 0), (13, 8, 0.5), GOLD if glow else BRASS, faces={"south": None})
    ctop = Mat("wood_dark", "planks", 2) if fine else OAK
    m.box("top", (0, 13, 0), (16, 15, 16), ctop)
    m.box("board", (2, 15, 2), (13, 16, 10), Mat("wood", "plain", 3))
    m.box("radish", (3, 16, 3), (8, 18, 5), "mc_radish")
    m.box("radish_leaf", (8, 16.5, 3.25), (10.5, 17.5, 4.75), "mc_leaf")
    m.box("cabbage", (8.5, 16, 5.5), (12, 19, 9), "mc_cabbage")
    m.box("chili", (3.5, 16, 6.5), (7.5, 17, 7.5), "mc_chili")
    m.box("chili", (4, 16, 8), (7, 17, 9), "mc_chili")
    m.box("knife_blade", (2.5, 15, 10.75), (8, 15.5, 12), Mat("blade", "metal", 2), faces={"down": None})
    m.box("knife_grip", (8, 15, 11), (11, 16, 11.75), SPRUCE_GRAIN)
    cyl(m, "bowl", "y", (13.5, 12.5), 4, 15, 17, "mc_porcelain", cap=Mat("pepper", "plain", 2, bevel=None))

    m.group("jars")
    m.box("shelf", (-16, 0, 1), (0, 6, 15), SPRUCE)
    m.box("shelf_front", (-15, 1, 0.5), (-1, 5, 1), Mat("wood", "panel", 2), faces={"south": None})
    _jar(m, -11, 8, 9, 9, 6)
    _jar(m, -3.5, 7, 6, 6, 6)

    if tier >= 1:
        m.group("extra")
        m.box("wall_shelf", (-16, 23, 11), (0, 24, 16), OAK)
        m.box("wall_shelf", (0, 23, 11), (16, 24, 16), OAK)
        for x in (-14, 14):
            m.box("bracket", (x, 20, 14.5), (x + 1, 23, 16), SPRUCE)
        for (x, d) in ((-13, 3), (-9, 3), (-5, 3)):
            cyl(m, "bowl", "y", (x, 13.5), d, 24, 26, "mc_porcelain", cap="mc_porcelain")
        for (x, r) in ((2, "bottle_green"), (4.5, "bottle_green"), (8, "wine")):
            m.box("bottle", (x, 24, 12.5), (x + 2, 28, 14.5), Mat(r, "plain", 2))
            m.box("bottle_neck", (x + 0.5, 28, 13), (x + 1.5, 29.5, 14), Mat(r, "plain", 2))
        m.box("ladle_bar", (10, 19.5, 15), (15.5, 20, 15.5), IRON)
        m.box("ttukbaegi", (16.5, 14, 13.25), (20, 16.5, 15.75), Mat("onggi", "plain", 1), faces={"up": "mc_soup"})

    if fine:
        # 2강 큰 부엌: a lattice window of 한지 behind the counter, strings of dried chillies and a garlic braid
        # under the wall shelf, a straw rope round the jar stand
        m.group("buok")
        lattice = Mat("wood_dark", "plain", 1)
        m.box("hanji", (2, 16.5, 15.6), (13, 22.5, 15.9), Mat("paper", "plain", 3, bevel=None))
        frame(m, "window", 1.5, 13.5, 16, 23, 15.25, 15.6, 0.75, lattice)
        for x in (4.9, 7.5, 10.1):
            m.box("window_bar", (x - 0.25, 16.75, 15.4), (x + 0.25, 22.25, 15.6), lattice, faces={"south": None})
        for y in (18.6, 20.4):
            m.box("window_bar", (2.25, y - 0.25, 15.3), (12.75, y + 0.25, 15.55), lattice)
        rope = Mat("straw", "weave", 2, bevel=None)
        for x in (-12.5, -9):
            m.box("chili_string", (x + 0.85, 15, 14.85), (x + 1.15, 23, 15.15), rope)
            for k, y in enumerate((15.5, 17, 18.5, 20, 21.5)):
                m.box("dried_chili", (x + (0.25 if k % 2 else 0.75), y, 14.5), (x + (1.25 if k % 2 else 1.75), y + 1.5, 15.5),
                      Mat("crimson", "plain", 2 + k % 2))
        for k, y in enumerate((16, 17.75, 19.5, 21.25)):
            m.box("garlic", (-5.5 + (k % 2) * 0.5, y, 14.25), (-3.75 + (k % 2) * 0.5, y + 1.75, 15.75), Mat("garlic", "plain", 2))
        m.box("garlic_braid", (-4.9, 15.5, 14.75), (-4.4, 23, 15.25), rope)
        m.box("jar_band", (-15.75, 5, 0.75), (-0.25, 6, 1), rope, faces={"south": None})

    if glow:
        # 3강 수라간: lantern, red lacquer door (above), gold pot brim and lid knob, glowing spice jars
        m.group("glow")
        lantern(m, -8, 4, 32, True, chain=4)
        m.box("trim", (0.5, 12.25, -0.5), (15.5, 13, 0), "mc_glow_trim", glow=True)
        ring(m, "gold_brim", (24, 7.5), 14, 12.5, 17, 17.25, GOLD, skip=lambda x, z: 27 < x < 31 and 11 < z < 15)  # chimney
        m.box("spice", (10.5, 24, 12.5), (12.5, 27, 14.5), Mat("glow_amber", "plain", 2, glow=True))
        m.box("spice", (13, 24, 12.5), (15, 26, 14.5), Mat("glow_cyan", "plain", 2, glow=True))
    return display(m, (3, 2, 1), 0)


# ================================================================================================
# 증류기 (still, 연금술사) 2 x 2 x 3: copper pot still on a brick firebox, swan neck to a condenser barrel
# ================================================================================================

def still(tier):
    m = MModel(f"still_{tier}", dy=-16)
    fine, glow = tier >= 2, tier >= 3
    K = (10, 21)                  # kettle centre (x, z)
    trim = GOLD if glow else BRASS
    m.group("firebox")
    m.box("firebox", (2, 0, 13), (18, 10, 28), "brick")
    m.box("mouth", (6, 1, 12.75), (14, 7, 13), "mc_hearth_mouth", full_uv=["north"], glow=True, faces={"south": None})
    m.group("kettle")
    pot = Mat("copper", "metal", 2, noise=0.04)
    for (d, y0, y1, i) in ((12, 10, 11, 1), (15, 11, 13, 2), (16, 13, 19, 2), (15, 19, 21, 3), (12, 21, 22.5, 3),
                           (6, 22.5, 24, 2), (9, 24, 27, 2), (7, 27, 29, 3), (4, 29, 31, 3)):
        cyl(m, "kettle", "y", K, d, y0, y1, pot(base=i), cap=pot(base=i + 1))
    m.box("sight", (8, 14, 12.75), (12, 18, 13), "mc_sight_glass", full_uv=["north"], faces={"south": None}, glow=glow)
    pipe = Mat("copper", "metal", 3)
    if not fine:
        # swan neck to the condenser
        m.box("neck", (9, 31, 20), (11, 33, 22), pipe)
        m.box("arm", (11, 31, 20.25), (16, 32.5, 21.75), pipe)
        m.box("arm", (16, 30, 20.25), (20, 31.5, 21.75), pipe)
        m.box("arm", (20, 29, 20.25), (24, 30.5, 21.75), pipe)
        m.box("drop", (24, 22, 20.25), (25.5, 30.5, 21.75), pipe)
    else:
        # 2강 증류탑: a copper column on the kettle, brass plate rings and round windows (amber-lit at 3강),
        # the lyne arm from its head down to the condenser
        m.group("column")
        col = Mat("copper", "metal", 2, noise=0.04)
        cyl(m, "column_foot", "y", K, 6, 31, 32, trim, cap=trim)
        cyl(m, "column", "y", K, 5, 32, 44, col, cap=col)
        for y in (35, 39):
            cyl(m, "plate_ring", "y", K, 6, y, y + 1, trim, cap=trim)
        for y in (32.75, 36.5, 40.5):
            m.box("port", (9, y, 18.25), (11, y + 2, 18.5), "mc_glow_amber" if glow else "mc_sight_glass", full_uv=["north"],
                  faces={"south": None}, glow=glow)
        cyl(m, "column_head", "y", K, 6, 44, 45, trim, cap=trim)
        cyl(m, "column_dome", "y", K, 4, 45, 46.5, col, cap=col)
        cyl(m, "column_knob", "y", K, 2, 46.5, 47.5, trim, cap=trim)
        m.box("arm", (12, 42.5, 20.25), (16, 44, 21.75), pipe)
        m.box("arm", (16, 41, 20.25), (20, 42.5, 21.75), pipe)
        m.box("arm", (20, 39.5, 20.25), (24, 41, 21.75), pipe)
        m.box("drop", (24, 22, 20.25), (25.5, 41, 21.75), pipe)
        m.box("drop_collar", (23.75, 30, 20), (25.75, 31, 22), trim)

    m.group("condenser")
    B = (25, 22)
    cyl(m, "barrel", "y", B, 12, 0, 22, Mat("wood_dark", "grain", 2), cap="mc_barrel_top")
    hoop = Mat("brass" if glow else "copper", "band", 2) if fine else IRON
    for y in ((1.5, 9.5, 18) if fine else (2, 10, 19)):
        cyl(m, "hoop", "y", B, 12.5, y, y + (2 if fine else 1), hoop, cap=hoop)
    m.box("tap", (24.25, 4, 14.5), (25.75, 5.5, 16.5), BRASS)
    m.box("tap_valve", (24.5, 5.5, 15), (25.5, 7, 16), BRASS)
    cyl(m, "jug", "y", (25, 10.5), 6, 0, 4, Mat("glow_amber" if glow else "straw", "plain", 1 if glow else 2, bevel=None, glow=glow),
        cap=Mat("straw", "plain", 3, bevel=None))
    cyl(m, "jug_top", "y", (25, 10.5), 6, 4, 6, GLASS(bevel=None), cap=GLASS(bevel=None))
    cyl(m, "jug_neck", "y", (25, 10.5), 2, 6, 8, GLASS, cap=Mat("wood", "plain", 2, bevel=None))

    if tier >= 1:
        m.group("extra")
        m.box("table", (1, 0, 1), (13, 9, 9), SPRUCE)
        m.box("table_top", (0.5, 9, 0.5), (13.5, 10, 9.5), OAK)
        # 3강: the front bottle is the house spirit, amber-lit with a gold stopper
        for (x, r) in ((2, "glow_amber" if glow else "bottle_green"), (5, "bottle_green"), (8.5, "wine")):
            lit = r == "glow_amber"
            m.box("bottle", (x, 10, 3), (x + 2.5, 15, 5.5), Mat(r, "plain", 1 if lit else 2, glow=lit))
            m.box("bottle_neck", (x + 0.75, 15, 3.75), (x + 1.75, 17, 4.75), Mat(r, "plain", 2, glow=lit))
            m.box("cork", (x + 0.75, 17, 3.75), (x + 1.75, 17.5, 4.75), GOLD if lit else Mat("wood", "plain", 2))
        m.box("funnel", (10, 10, 6), (13, 11, 9), GLASS)
        cyl(m, "wood_log", "x", (1.5, 30.5), 3, 19, 31, "mc_bark", cap="mc_log_top")

    if glow:
        # 3강 명주: amber-lit windows on the column, a glowing bottle, gold rings and trim
        m.group("glow")
        m.box("trim", (2, 9.25, 12.5), (18, 10, 13), "mc_glow_trim", glow=True)
    return display(m, (2, 3, 2), -16)


# ================================================================================================
# registry, preview
# ================================================================================================

# ================================================================================================
# 압착기 (press, 공용) 2 x 2 x 1: a screw press over a stone basin on the left, the oil running out of
# a spout into a tub on the right, pressed blocks and a compost sack beside it
# ================================================================================================

OIL = Mat("corn", "plain", 1, bevel=None, noise=0.04)


def press(tier):
    m = MModel(f"press_{tier}")
    fine, glow = tier >= 2, tier >= 3
    oil = Mat("lamp", "plain", 2, bevel=None, glow=True) if glow else OIL
    m.group("basin")
    m.box("basin", (1, 0, 2), (15, 6, 14), STONE)
    for (x0, z0, x1, z1) in ((1, 2, 15, 3), (1, 13, 15, 14), (1, 3, 2, 13), (14, 3, 15, 13)):
        m.box("basin_rim", (x0, 6, z0), (x1, 8, z1), STONE, faces={"down": None})
    m.box("basin_oil", (2, 6, 3), (14, 7, 13), oil, faces={f: None for f in ("north", "south", "east", "west", "down")})
    m.group("frame")
    post = Mat("crimson", "grain", 2) if fine else LOG
    for x in (1, 13):
        m.box("post", (x, 8, 6.5), (x + 2, 27, 9.5), post)
        m.box("post_foot", (x - 0.5, 6, 6), (x + 2.5, 8.5, 10), STONE)
    m.box("beam", (0.5, 27, 5.5), (15.5, 31, 10.5), OAK)
    m.box("nut", (5.5, 25.5, 5.5), (10.5, 27, 10.5), IRON)
    m.group("cake")
    m.box("cake", (4, 7, 4), (12, 10.5, 12), BURLAP)
    m.box("plate", (3.5, 10.5, 3.5), (12.5, 12.5, 12.5), OAK)
    m.group("spout")
    m.box("spout", (15, 4.5, 7), (19, 5.5, 9), IRON)
    m.box("drip", (18.25, 3, 7.75), (18.75, 4.5, 8.25), oil)
    m.group("tub")
    m.box("tub_floor", (17, 0, 3), (27, 1, 13), SPRUCE)
    frame_t = 1
    for (x0, z0, x1, z1) in ((17, 3, 27, 4), (17, 12, 27, 13), (17, 4, 18, 12), (26, 4, 27, 12)):
        m.box("tub_wall", (x0, 1, z0), (x1, 5, z1), SPRUCE)
    m.box("tub_oil", (18, 1, 4), (26, 3.5, 12), oil, faces={f: None for f in ("north", "south", "east", "west", "down")})
    m.box("tub_band", (16.75, 2.5, 2.75), (27.25, 3.25, 13.25), BRASS if fine else IRON)
    m.group("blocks")
    pressed = Mat("stone", "speckle", 1)
    m.box("pressed", (27.5, 0, 1), (31.5, 4, 5), pressed)
    m.box("pressed", (28, 4, 1.5), (31, 7, 4.5), pressed)
    m.box("pressed_dirt", (27.5, 0, 5.5), (31.5, 4, 9.5), Mat("soil", "speckle", 2))
    sack(m, 27.5, 9.75, 4.25, 5)

    if tier >= 1:
        # 1강: iron bands round the posts and beam ends, a shelf of oil bottles at the back
        m.group("extra")
        for x in (1, 13):
            for y in (11, 22):
                m.box("band", (x - 0.25, y, 6.25), (x + 2.25, y + 1, 9.75), IRON)
        for x in (0.25, 14.25):
            m.box("beam_cap", (x, 26.75, 5.25), (x + 1.5, 31.25, 10.75), IRON)
        m.box("shelf", (17, 13, 13.5), (31, 14, 16), SPRUCE)
        for x in (17.5, 29.5):
            m.box("shelf_leg", (x, 0, 14), (x + 1, 13, 15.5), SPRUCE_GRAIN)
        for i, x in enumerate((18, 21, 24)):
            m.box("bottle", (x, 14, 14), (x + 2, 17.5, 15.5), GLASS)
            m.box("bottle_oil", (x + 0.25, 14, 14.25), (x + 1.75, 16.5 if i % 2 else 17, 15.25), oil, faces={"down": None})
            m.box("bottle_cork", (x + 0.5, 17.5, 14.5), (x + 1.5, 18.5, 15), OAK_GRAIN)

    if fine:
        # 2강 기름집: posts painted red, brass bands and a brass funnel over the press, a pressure gauge
        m.group("craft")
        m.box("funnel", (6, 31, 6), (10, 32, 10), BRASS)
        m.box("gauge", (6.5, 2, 1.5), (9.5, 5, 2), BRASS, faces={"south": None})
        m.box("gauge_face", (7, 2.5, 1.25), (9, 4.5, 1.5), Mat("dial", "plain", 1, bevel=None), faces={"south": None})
        m.box("gauge_needle", (7.75, 3.5, 1.15), (8.25, 4.25, 1.25), Mat("crimson", "plain", 2, bevel=None), faces={"south": None})

    if glow:
        # 3강 황금 기름: gold caps on the beam, a lantern on the shelf, the oil itself shining
        m.group("glow")
        for x in (0, 14.5):
            m.box("gold_cap", (x, 31.25, 5.5), (x + 1.5, 32, 10.5), GOLD)
        # a lantern standing on the shelf
        m.box("lantern_base", (27, 14, 13.25), (31, 15, 16), IRON)
        m.box("lantern_glass", (27.25, 15, 13.5), (30.75, 19, 15.75), "mc_glass_gold", glow=True,
              full_uv=["north", "south", "east", "west"], faces={"up": None, "down": None})
        m.box("lantern_cap", (27, 19, 13.25), (31, 20, 16), IRON)
        m.box("lantern_handle", (28.75, 20, 14.25), (29.25, 21.5, 15), IRON)
    return display(m, (2, 2, 1), 0)


def press_screw(tier):
    """The press screw with its capstan bar, turns around y. Centre (8, 8, 8)."""
    m = MModel(f"press_screw_{tier}")
    m.group("screw")
    cyl(m, "rod", "y", (8, 8), 3, 0.5, 15.5, IRON, cap=IRON)
    for y in range(2, 15, 2):
        cyl(m, "thread", "y", (8, 8), 4, y, y + 0.75, STEEL_DARK, cap=STEEL_DARK)
    m.box("bar", (2.5, 5, 7.5), (13.5, 6, 8.5), OAK_GRAIN)
    m.box("bar_end", (2, 4.75, 7.25), (3, 6.25, 8.75), BRASS if tier >= 2 else IRON)
    m.box("bar_end", (13, 4.75, 7.25), (14, 6.25, 8.75), BRASS if tier >= 2 else IRON)
    if tier >= 3:
        cyl(m, "glow_ring", "y", (8, 8), 5, 0.75, 1.5, Mat("glow_amber", "plain", 2, glow=True, bevel=None))
    m.display = presets.furniture(gui_scale=0.5)
    return m


# machine id -> (builder, size in blocks (w, h, d), floor y, [(part builder, centre in floor coords, axis, rpm)])
MACHINES = {
    "gem_bench": (gem_bench, (2, 2, 1), 0, [(gem_bench_wheel, (8, 24, 8), "z", 40)]),
    "mill": (mill, (2, 2, 2), 0, [(mill_stone, (16, 12, 16), "y", 12)]),
    "fish_bench": (fish_bench, (2, 2, 1), 0, []),
    "forge": (forge, (2, 3, 2), -16, []),
    "kitchen": (kitchen, (3, 2, 1), 0, []),
    "still": (still, (2, 3, 2), -16, []),
    "press": (press, (2, 2, 1), 0, [(press_screw, (8, 20, 8), "y", 30)]),
}


def models():
    out = {}
    for mid, (fn, _size, _floor, parts) in MACHINES.items():
        for t in range(4):
            out[f"{mid}_{t}"] = (lambda f=fn, k=t: f(k))
            for (pf, _pos, _axis, _rpm) in parts:
                out[pf(t).name] = (lambda f=pf, k=t: f(k))
    return out


def preview(mid, tier):
    """Machine + its parts in one preview scene, as placed (floor at y 0)."""
    fn, size_b, floor, parts = MACHINES[mid]
    data = fn(tier).to_preview()
    lift = -floor
    for e in data["elements"]:
        e["from"][1] += lift
        e["to"][1] += lift
        if e.get("rotation"):
            e["rotation"][2][1] += lift
    for (pf, pos, _axis, _rpm) in parts:
        pd = pf(tier).to_preview()
        data["textures"].update(pd["textures"])
        for e in pd["elements"]:
            e["from"] = [e["from"][i] + pos[i] - 8 for i in range(3)]
            e["to"] = [e["to"][i] + pos[i] - 8 for i in range(3)]
            if e.get("rotation"):
                e["rotation"][2] = [e["rotation"][2][i] + pos[i] - 8 for i in range(3)]
            data["elements"].append(e)
    return data
