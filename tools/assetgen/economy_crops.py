"""
Economy farming art: growth stages (crossed planes, like vanilla crops), produce icons and seed packets
for the 17 crops of contents/economy/crops/crops.yml.

Hand-drawn pixel art. Every pixel names a palette ramp and a step (docs/ART_STYLE.md: colours only from
ramps, light from the top-left). Plants have no outline (like vanilla crops); icons get the usual
outline from the darkest step of the part's own ramp.

A picture is a 16-wide character grid. Pictures are composed from hand-drawn sprites (leaves, fruit,
stakes) placed by hand, so one leaf drawing is reused instead of redrawn in every stage.

Legend shared by every drawing (a drawing may add its own letters):
  1-5  leaf (dark -> light)         a-e  sprout, yellow-green new growth
  v-y  leaf_dark (deep glossy)      h-l  straw (ripe grain, dry leaves)
  m-o  wood (stakes, vines)         W V  white petals    Y Z  yellow petals    U  purple petals
"""

import math
import random

from PIL import Image

from stsv_assets import icons as I
from stsv_assets.palette import ramp
from stsv_assets.palette import size as _size

BASE = {
    "1": ("leaf", 0), "2": ("leaf", 1), "3": ("leaf", 2), "4": ("leaf", 3), "5": ("leaf", 4),
    "a": ("sprout", 0), "b": ("sprout", 1), "c": ("sprout", 2), "d": ("sprout", 3), "e": ("sprout", 4),
    "v": ("leaf_dark", 0), "w": ("leaf_dark", 1), "x": ("leaf_dark", 2), "y": ("leaf_dark", 3),
    "h": ("straw", 0), "i": ("straw", 1), "j": ("straw", 2), "k": ("straw", 3), "l": ("straw", 4),
    "m": ("wood", 0), "n": ("wood", 1), "o": ("wood", 2),
    "W": ("snow", 1), "V": ("snow", 2), "Y": ("petal_yellow", 1), "Z": ("petal_yellow", 2), "U": ("petal_purple", 1),
}

# ------------------------------------------------------------------------------------------------
# Composing
# ------------------------------------------------------------------------------------------------


class Grid:
    """A character picture. Sprites are pasted with their top-left corner at (x, y)."""

    def __init__(self, h=16):
        self.h = h
        self.c = [["."] * 16 for _ in range(h)]

    def put(self, x, y, ch):
        if 0 <= x < 16 and 0 <= y < self.h and ch != ".":
            self.c[y][x] = ch
        return self

    def sprite(self, rows, x, y, recolor=None):
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch != ".":
                    self.put(x + dx, y + dy, (recolor or {}).get(ch, ch))
        return self

    def line(self, pts, ch):
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            n = max(abs(x1 - x0), abs(y1 - y0), 1)
            for k in range(n + 1):
                self.put(round(x0 + (x1 - x0) * k / n), round(y0 + (y1 - y0) * k / n), ch)
        return self

    def col(self, x, y0, y1, chars):
        """A vertical run from y0 to y1; chars = top, middle, bottom letters."""
        for y in range(y0, y1 + 1):
            self.put(x, y, chars[0] if y == y0 else chars[2] if y == y1 else chars[1])
        return self

    def rows(self):
        return ["".join(r) for r in self.c]


# ramps that are not shaded again (flowers, the white of petals): they are drawn as they should look
FLAT = {"petal_yellow", "petal_purple", "snow"}


def relight(cells, ground):
    """Light and shadow on top of the drawing, the same way for every plant: an edge open to the
    top-left catches the light (+1), an edge open to the bottom-right falls into shade (-1), a pixel
    closed in on all sides sits inside the foliage (-1), and the rows near the ground are darker (-1)."""
    out = {}
    for (x, y), (r, i) in cells.items():
        if r in FLAT:
            out[(x, y)] = (r, i)
            continue
        have = lambda dx, dy: (x + dx, y + dy) in cells      # noqa: E731
        d = 0
        if not have(-1, 0) or not have(0, -1):
            d += 1
        if not have(1, 0) or not have(0, 1):
            d -= 1
        if have(-1, 0) and have(1, 0) and have(0, -1) and have(0, 1) and have(1, 1):
            d -= 1
        if y >= ground - 2:
            d -= 1
        out[(x, y)] = (r, max(0, min(_size(r) - 1, i + d)))
    return out


def image(rows, extra=None, light=True):
    legend = dict(BASE, **(extra or {}))
    cells = {}
    for y, row in enumerate(rows):
        assert len(row) == 16, f"row of {len(row)}: {row!r}"
        for x, ch in enumerate(row):
            if ch != ".":
                cells[(x, y)] = legend[ch]
    if light:
        cells = relight(cells, len(rows) - 1)
    im = Image.new("RGBA", (16, len(rows)), (0, 0, 0, 0))
    for (x, y), (r, i) in cells.items():
        im.putpixel((x, y), ramp(r, i))
    return im


def icon(rows, extra=None):
    """A 16x16 item icon: the drawing plus the outline of the darkest step of each part's ramp."""
    legend = dict(BASE, **(extra or {}))
    ic = I.Icon()
    for y, row in enumerate(rows):
        assert len(row) == 16, f"row of {len(row)}: {row!r}"
        for x, ch in enumerate(row):
            if ch != ".":
                r, i = legend[ch]
                ic.put(x, y, r, i)
    return ic.outline().image()


def pad(rows, top=0):
    rows = ["." * 16] * top + list(rows)
    return [r.ljust(16, ".") for r in rows] + ["." * 16] * (16 - len(rows))


def picture(rows, extra=None):
    """Any-size picture with no relighting (for the faces of solid parts; the box itself is shaded in game)."""
    legend = dict(BASE, **(extra or {}))
    im = Image.new("RGBA", (len(rows[0]), len(rows)), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == len(rows[0]), f"ragged picture: {rows!r}"
        for x, ch in enumerate(row):
            if ch != ".":
                im.putpixel((x, y), ramp(*legend[ch]))
    return im


def melon_net(w, h, seed, spacing=2.6, width=0.5, inside=lambda x, y: True, origin=(-0.6, 0.6), reach=1):
    """Cells of a muskmelon's raised net: the pixels on the border between two random cells.
    Seeds sit on a jittered grid, so the net is even but never regular like a checkerboard."""
    rnd = random.Random(seed)
    seeds, y, row = [], origin[1], 0
    while y < h + reach:
        x = origin[0] + (spacing / 2 if row % 2 else 0)
        while x < w + reach:
            seeds.append((x + rnd.uniform(-0.6, 0.6), y + rnd.uniform(-0.5, 0.5)))
            x += spacing
        y += spacing * 0.87
        row += 1
    on = set()
    for yy in range(h):
        for xx in range(w):
            if not inside(xx, yy):
                continue
            d = sorted(math.hypot(xx + 0.5 - sx, yy + 0.5 - sy) for sx, sy in seeds)
            if d[1] - d[0] < width:
                on.add((xx, yy))
    return on


def net_rows(w, h, seed, shade_bottom=True):
    """An opaque w x h rind picture with the net on it (N net, R rind, S the shaded bottom row)."""
    on = melon_net(w, h, seed)
    return ["".join("N" if (x, y) in on else ("S" if shade_bottom and y == h - 1 else "R") for x in range(w)) for y in range(h)]


class Part:
    """
    A solid piece of a plant (bulb, root, head): a real box with its own side and top pictures.

    Painted on the crossed planes instead, one bulb shows up once on every plane, as a hollow square
    or a flat cross. frm/to are model pixels: x and z 0..16, y from -1 (the soil surface) up.
    """

    def __init__(self, frm, to, side, top, extra=None, end=None):
        """side: w x h (north/south; also east/west when the part is square), top: w x d,
        end: d x h for the east/west faces of a long part (a courgette lying down)."""
        self.frm, self.to = tuple(frm), tuple(to)
        w, h, d = (self.to[i] - self.frm[i] for i in range(3))
        assert len(side) == h and all(len(r) == w for r in side), f"side picture is not {w}x{h}"
        assert len(top) == d and all(len(r) == w for r in top), f"top picture is not {w}x{d}"
        if end is None:
            assert w == d, "a long part needs an end picture"
            end = side
        assert len(end) == h and all(len(r) == d for r in end), f"end picture is not {d}x{h}"
        assert w <= 16 and d <= 16 and h <= 16
        self.side, self.top, self.end = picture(side, extra), picture(top, extra), picture(end, extra)
        for im in (self.side, self.top, self.end):
            assert im.getextrema()[3][0] == 255, "solid parts are opaque"
        self.w, self.h, self.d = w, h, d

    def moved(self, dx, dz):
        p = object.__new__(Part)
        p.__dict__.update(self.__dict__)
        p.frm = (self.frm[0] + dx, self.frm[1], self.frm[2] + dz)
        p.to = (self.to[0] + dx, self.to[1], self.to[2] + dz)
        return p

    def lifted(self, dy):
        p = self.moved(0, 0)
        p.frm = (p.frm[0], p.frm[1] + dy, p.frm[2])
        p.to = (p.to[0], p.to[1] + dy, p.to[2])
        return p

    def textures(self):
        """Side, top and end, each as an opaque 16x16 texture (the picture at the top-left)."""
        out = []
        for pic in (self.side, self.top, self.end):
            im = Image.new("RGBA", (16, 16), pic.getpixel((0, 0)))
            im.paste(pic, (0, 0))
            out.append(im)
        return out

    def uv(self):
        side, top, end = [0, 0, self.w, self.h], [0, 0, self.w, self.d], [0, 0, self.d, self.h]
        return {"north": side, "south": side, "east": end, "west": end, "up": top, "down": top}


# where the crossed planes of the vanilla-style layout meet: four small plants (bulbs) per block sit here
CORNERS = ((0, 0), (8, 0), (0, 8), (8, 8))


def four(part):
    """The part (drawn around x=z=4) at all four crossings of the planes."""
    return [part.moved(dx, dz) for dx, dz in CORNERS]


class Stage:
    """
    One growth stage: the plane picture(s) and how they stand, plus solid parts.

    layout "hash": four planes at 4 and 12 (vanilla crops). Things drawn at columns 4 and 12 meet where
                   the planes cross, so a block holds four small plants in a 2x2; rows of crops join up.
    layout "cross": two diagonal planes through the middle (vanilla flowers): one plant per block,
                   drawn at the middle (column 8). For plants that stand alone (radish, cabbage, corn).
    """

    def __init__(self, planes, layout="hash", parts=()):
        assert layout in ("hash", "cross")
        self.planes, self.layout, self.parts = planes, layout, list(parts)

    def flat(self):
        """The front view for the preview sheet: the planes with the solid parts drawn in front."""
        lo = self.planes[0] if isinstance(self.planes, tuple) else self.planes
        im = lo.copy()
        for p in sorted(self.parts, key=lambda p: p.frm[2]):
            row = 15 - p.to[1]                      # plane rows: row 15 is y -1..0, row 0 is y 14..15
            im.alpha_composite(p.side, (int(p.frm[0]), int(row)))
        return im if not isinstance(self.planes, tuple) else (im, self.planes[1])


def stage(st):
    """Older crops are a bare picture (hash layout, no parts)."""
    return st if isinstance(st, Stage) else Stage(st)


STAGES = {}   # crop -> [Stage or picture per stage]  (corn: tall pictures are (bottom, top) pairs)
ICONS = {}    # item -> callable returning the 16x16 icon

# ------------------------------------------------------------------------------------------------
# Sprites shared by many plants
# ------------------------------------------------------------------------------------------------

# two seed leaves on a short stem: the first stage of every broad-leaved crop
COTYLEDON = [
    ".ed.ed.",
    "edc.dcb",
    "..cbc..",
    "...b...",
    "...a...",
]

# leaf clusters (light from the top-left; mostly mid and dark steps, like vanilla foliage)
LEAF = {
    "s": ["43", "21"],
    "m": [".43.", "4321", ".21."],
    "w": [".443.", "43321", ".221."],
    "r": ["443.", "3321", ".21."],
    "t": [".4.", "432", "21."],
    "top": [".54.", "5432", ".32."],
}
DARKER = {"5": "4", "4": "3", "3": "2", "2": "1", "1": "1"}
GLOSSY = {"5": "y", "4": "y", "3": "x", "2": "w", "1": "v"}     # leaf -> leaf_dark
YOUNG = {"5": "e", "4": "d", "3": "c", "2": "b", "1": "a"}      # leaf -> sprout
DRY = {"5": "l", "4": "k", "3": "j", "2": "i", "1": "h"}        # leaf -> straw

# thin blades: the first stage of onions, garlic, green onions, rice and corn
BLADES = [
    "d...e",
    "c..d.",
    ".c.d.",
    ".cc..",
    "..b..",
]

FLOWER_Y = [".Z.", "ZYZ", ".Z."]
FLOWER_W = [".V.", "VYV", ".V."]


def seedlings(sprite=COTYLEDON, xs=(1, 9), bottom=15, g=None):
    g = g or Grid()
    for k, x in enumerate(xs):
        g.sprite(sprite, x, bottom - len(sprite) + 1 + (k % 2))
    return g


def seedling(sprite=COTYLEDON, bottom=15):
    """One seedling in the middle (column 8), for plants that grow alone (cross layout)."""
    return Grid().sprite(sprite, 8 - len(sprite[0]) // 2, bottom - len(sprite) + 1)


def leaves(g, spots, recolor=None):
    for name, x, y in spots:
        g.sprite(LEAF[name], x, y, recolor)
    return g


# ================================================================================================
# 토마토: a staked bush, yellow flowers, green then red fruit
# ================================================================================================

TOMATO = {"R": ("tomato", 0), "S": ("tomato", 1), "T": ("tomato", 2), "Q": ("tomato", 3), "P": ("tomato", 4),
          "J": ("cabbage", 0), "K": ("cabbage", 1), "L": ("cabbage", 2), "M": ("cabbage", 3)}
TOMATO_RIPE = ["PQ.", "QTS", ".SR"]
TOMATO_GREEN = ["ML.", "LKJ", ".JJ"]


def _tomato_bush(fruit=None, flowers=False):
    g = Grid()
    g.col(8, 1, 15, "onm")                                   # the stake
    g.line([(8, 15), (7, 11), (8, 7), (7, 3)], "2")          # stem winding up it
    g.line([(7, 11), (3, 9)], "2").line([(8, 7), (12, 5)], "2").line([(7, 12), (12, 11)], "2").line([(8, 8), (3, 5)], "2")
    leaves(g, [("top", 5, 1), ("w", 9, 2), ("m", 1, 3), ("r", 12, 4), ("m", 5, 5), ("w", 0, 7), ("w", 9, 6),
               ("m", 4, 8), ("r", 12, 8), ("w", 1, 11), ("m", 9, 10), ("t", 5, 12), ("s", 12, 12)])
    if flowers:
        for x, y in ((2, 5), (12, 1), (13, 6)):
            g.sprite(FLOWER_Y, x, y)
    if fruit:
        for x, y in ((3, 6), (10, 9), (5, 11), (11, 4), (1, 10)):
            g.sprite(fruit, x, y)
    return g


def _young(g, x0, top, recolor=None):
    g.line([(x0 + 3, 15), (x0 + 3, top + 2)], (recolor or {}).get("2", "2"))
    leaves(g, [("t", x0 + 2, top), ("m", x0, top + 3), ("r", x0 + 3, top + 4)], recolor)
    return g


STAGES["tomato"] = [
    image(seedlings().rows()),
    image(_young(_young(Grid(), 1, 7), 9, 8).rows()),
    image(_tomato_bush(flowers=True).rows(), TOMATO),
    image(_tomato_bush(fruit=TOMATO_GREEN, flowers=True).rows(), TOMATO),
    image(_tomato_bush(fruit=TOMATO_RIPE).rows(), TOMATO),
]

# ================================================================================================
# 고추: an upright branching bush of slender leaves, white flowers, hanging pods (green, then red)
# ================================================================================================

CHILI = {"F": ("pepper", 4), "G": ("pepper", 3), "H": ("pepper", 2), "J": ("pepper", 1), "K": ("pepper", 0)}
POD_RED = ["2.", "GH", "GJ", ".H", ".K"]
POD_GREEN = ["2.", "dc", "db", ".c", ".a"]
LANCE_R = ["..44", ".432", "321."]       # slender leaf pointing up and right
LANCE_L = ["44..", "234.", ".123"]


def _chili_bush(pods=None, flowers=False):
    g = Grid()
    g.line([(7, 15), (7, 10), (4, 6), (3, 3)], "2").line([(7, 11), (10, 7), (11, 3)], "2")
    g.line([(7, 13), (3, 11)], "2").line([(7, 13), (12, 11)], "2").line([(5, 8), (8, 5)], "2")
    for spr, x, y in ((LANCE_L, 0, 2), (LANCE_R, 3, 1), (LANCE_L, 7, 1), (LANCE_R, 11, 1), (LANCE_L, 0, 6), (LANCE_R, 4, 4),
                      (LANCE_R, 10, 5), (LANCE_L, 7, 6), (LANCE_L, 0, 9), (LANCE_R, 11, 9), (LANCE_R, 5, 9), (LANCE_L, 8, 10),
                      (LANCE_R, 12, 6)):
        g.sprite(spr, x, y)
    if flowers:
        for x, y in ((2, 4), (11, 4), (6, 7)):
            g.sprite(FLOWER_W, x, y)
    if pods:
        for x, y in ((3, 6), (11, 7), (2, 11), (12, 11), (7, 9), (5, 3)):
            g.sprite(pods, x, y)
    return g


def _chili_young(g, x0, top):
    g.line([(x0 + 3, 15), (x0 + 3, top + 2)], "2")
    for spr, x, y in ((LANCE_L, x0, top), (LANCE_R, x0 + 3, top), (LANCE_L, x0, top + 3), (LANCE_R, x0 + 4, top + 3)):
        g.sprite(spr, x, y)
    return g


STAGES["chili"] = [
    image(seedlings().rows()),
    image(_chili_young(_chili_young(Grid(), 0, 8), 8, 9).rows()),
    image(_chili_bush(flowers=True).rows()),
    image(_chili_bush(pods=POD_GREEN, flowers=True).rows()),
    image(_chili_bush(pods=POD_RED).rows(), CHILI),
]

# ================================================================================================
# 딸기: low rosettes, white flowers, red berries near the ground
# ================================================================================================

STRAW = {"P": ("toadstool", 4), "Q": ("toadstool", 3), "S": ("toadstool", 2), "R": ("toadstool", 1), "T": ("toadstool", 0)}
TRIO = [".4.4.", "43234", ".212.", "..1.."]
BERRY = [".3.", "QPS", "SZR", ".R."]
BERRY_WHITE = [".3.", "VVW", "WVW", ".W."]


def _strawberry(flowers=False, berries=None, big=True):
    """Two plants per plane, at columns 4 and 12: four low plants per block where the planes cross."""
    g = Grid()
    for cx in (4, 12):
        for x, y in ([(cx - 4, 11), (cx - 1, 11), (cx - 2, 9)] if big else [(cx - 3, 12), (cx - 1, 11)]):
            g.sprite(TRIO, x, y)
        if flowers:
            g.sprite(FLOWER_W, cx - 4 if cx == 4 else cx + 1, 7)
    return g


# ripe berries: solid, two by each of the four plants, lying on the soil
BERRY_SOLID = Part((0, -1, 0), (2, 1, 2), ["QZ", "SR"], ["3P", "PQ"], STRAW)
BERRIES = [BERRY_SOLID.moved(x, z) for x, z in ((5, 1), (1, 5))]


STAGES["strawberry"] = [
    image(seedlings(TRIO, xs=(2, 10)).rows()),
    image(_strawberry(big=False).rows()),
    image(_strawberry(flowers=True).rows()),
    Stage(image(_strawberry().rows()), parts=[b for p in BERRIES for b in four(p)]),
]

# ================================================================================================
# 양파: hollow leaves, a bulb swelling at the ground, leaves falling over when ripe
# ================================================================================================

ONION = {"A": ("onion", 3), "B": ("onion", 2), "C": ("onion", 1), "D": ("onion", 0), "E": ("garlic", 3), "F": ("garlic", 2)}


def _tubes(g, x, top, chars="432", lean=0):
    """A clump of three hollow leaves from (x, 15) up to top."""
    g.line([(x, 15), (x - 2 + lean, top + 2), (x - 3 + lean, top)], chars[1])
    g.line([(x + 1, 15), (x + 1 + lean, top - 1)], chars[0])
    g.line([(x + 2, 15), (x + 4 + lean, top + 3), (x + 5 + lean, top + 2)], chars[2])
    return g


ONION_YOUNG = Part((3, -1, 3), (5, 1, 5), ["EE", "FF"], ["cd", "dc"], ONION)
ONION_BULB = Part((2, -1, 2), (6, 2, 6), ["BAAB", "ABBC", "BCCD"], ["CBBC", "BAAB", "BAAB", "CBBC"], ONION)
ONION_NECK = Part((3, 2, 3), (5, 3, 5), ["kj"], ["jk", "kj"])


def _flopped(g, cx, base):
    """Dry leaves fallen over from a neck at column cx."""
    g.line([(cx - 1, base), (cx - 3, base - 3), (cx - 4, base - 4)], "i")
    g.line([(cx, base), (cx + 2, base - 2), (cx + 3, base - 2)], "j")
    g.line([(cx, base), (cx + 1, base - 4), (cx, base - 6)], "k")
    return g


STAGES["onion"] = [
    Stage(image(seedlings(BLADES, xs=(2, 10)).rows())),
    Stage(image(_tubes(_tubes(Grid(), 3, 9), 11, 10).rows())),
    Stage(image(_tubes(_tubes(Grid(), 3, 4), 11, 5).rows()), parts=four(ONION_YOUNG)),
    Stage(image(_flopped(_flopped(Grid(), 4, 12), 12, 12).rows()), parts=four(ONION_BULB) + four(ONION_NECK)),
]

# ================================================================================================
# 마늘: flat leaves in a fan, a curled scape, white bulbs when the leaves dry
# ================================================================================================

GARLIC = {"A": ("garlic", 3), "B": ("garlic", 2), "C": ("garlic", 1), "D": ("garlic", 0), "U": ("petal_purple", 0),
          "G": ("petal_purple", 2)}


def _fan(g, x, top, recolor=None):
    c = recolor or {}
    g.line([(x, 15), (x - 3, top + 3), (x - 4, top + 2)], c.get("3", "3"))
    g.line([(x, 15), (x - 1, top)], c.get("4", "4"))
    g.line([(x + 1, 15), (x + 2, top + 1)], c.get("2", "2"))
    g.line([(x + 1, 15), (x + 4, top + 4), (x + 5, top + 4)], c.get("3", "3"))
    return g


GARLIC_BULB = Part((2, -1, 2), (6, 2, 6), ["BAAB", "AGBC", "BCGD"], ["CBBC", "BAAB", "BAAB", "CBBC"], GARLIC)
GARLIC_NECK = Part((3, 2, 3), (5, 3, 5), ["kj"], ["jk", "kj"])

STAGES["garlic"] = [
    Stage(image(seedlings(BLADES, xs=(2, 10)).rows())),
    Stage(image(_fan(_fan(Grid(), 3, 9), 11, 10).rows())),
    Stage(image(_fan(_fan(Grid(), 3, 4), 11, 5).line([(4, 15), (5, 3), (7, 1), (8, 2)], "2").sprite(["W"], 8, 2).rows())),
    Stage(image(_fan(_fan(Grid(), 3, 7, DRY), 11, 8, DRY).rows()), parts=four(GARLIC_BULB) + four(GARLIC_NECK)),
]

# ================================================================================================
# 배추: broad leaves with pale ribs, closing into a tall head
# ================================================================================================

CABBAGE = {"A": ("cabbage", 3), "B": ("cabbage", 2), "C": ("cabbage", 1), "D": ("cabbage", 0), "V": ("garlic", 3), "W": ("garlic", 2)}
UPRIGHT = [".44.", "4V43", "4V32", "3W32", "3W21", ".W1."]    # an upright leaf, white midrib
LOW = [".443.", "44V32", "3W321", ".W1.."]                    # a leaf lying out


def _cabbage_leaves(g, big):
    if big:
        for spr, x, y in ((LOW, 0, 11), (LOW, 11, 11), (UPRIGHT, 1, 8), (UPRIGHT, 11, 8)):
            g.sprite(spr, x, y)
    else:
        for spr, x, y in ((LOW, 0, 12), (UPRIGHT, 4, 10), (UPRIGHT, 8, 10), (LOW, 11, 12)):
            g.sprite(spr, x, y)
    return g


CABBAGE_HEART = Part((6, -1, 6), (10, 4, 10), ["CBBC", "3CB2", "3VC2", "4VC2", "3W21"], ["CBBC", "BAAB", "BAAB", "CBBC"], CABBAGE)
CABBAGE_HEAD = Part((5, -1, 5), (11, 10, 11), [
    "CBAABC",
    "3BAAB2",
    "3CBAC2",
    "4CVBC2",
    "43VC32",
    "43VC32",
    "43W332",
    "33W321",
    "43W321",
    "33W221",
    "322221",
], ["CBBBBC", "BAABAB", "BABAAB", "BAABAB", "BABAAB", "CBBBBC"], CABBAGE)

STAGES["cabbage"] = [
    Stage(image(seedling().rows()), "cross"),
    Stage(image(_cabbage_leaves(Grid(), False).rows(), CABBAGE), "cross"),
    Stage(image(_cabbage_leaves(Grid(), True).sprite(UPRIGHT, 4, 7).sprite(UPRIGHT, 8, 7).rows(), CABBAGE), "cross",
          [CABBAGE_HEART]),
    Stage(image(_cabbage_leaves(Grid(), True).rows(), CABBAGE), "cross", [CABBAGE_HEAD]),
]

# ================================================================================================
# 벼: tufts of blades, then drooping panicles, green then golden
# ================================================================================================


def _tuft(g, x, top, c="dcb", lean=1):
    """A clump of thin blades from (x, 15): the middle ones upright, the outer ones arching out."""
    g.line([(x, 15), (x - 2, top + 3), (x - 4, top + 4)], c[2])
    g.line([(x, 15), (x - 1, top + 1)], c[1])
    g.line([(x + 1, 15), (x + 1, top)], c[0])
    g.line([(x + 1, 15), (x + 3, top + 2), (x + 4 + lean, top + 3)], c[1])
    return g


def _panicle(g, x, top, grain):
    """A head of grain on its stem, arching over to the right and hanging down."""
    g.line([(x, top + 6), (x, top + 1), (x + 1, top)], grain[2])
    for dx, dy, k in ((1, 0, 0), (2, 0, 1), (3, 1, 0), (4, 2, 1), (4, 3, 0), (5, 4, 1), (3, 3, 1), (2, 1, 0)):
        g.put(x + dx, top + dy, grain[k])
    return g




def _rice(top_shift, blades, heads=None):
    g = Grid()
    for x, top in ((3, 4), (8, 3), (12, 5)):
        _tuft(g, x, top + top_shift, blades)
    if heads:
        for x, top, order in ((1, 1, 0), (7, 0, 1), (11, 2, 0)):
            _panicle(g, x, top + top_shift, heads if order == 0 else heads[1] + heads[0] + heads[2])
    return g


STAGES["rice"] = [
    image(seedlings(BLADES, xs=(2, 10)).rows()),
    image(_tuft(_tuft(_tuft(Grid(), 3, 9, "432"), 8, 8, "432"), 12, 10, "432").rows()),
    image(_rice(0, "432").rows()),
    image(_rice(0, "432", "ed4").rows()),
    image(_rice(1, "dcb", "lkj").rows()),
]

# ================================================================================================
# 콩: trifoliate leaves, little purple flowers, fuzzy pods as the leaves turn yellow
# ================================================================================================

SOY = {"A": ("bean", 3), "B": ("bean", 2), "C": ("bean", 1), "D": ("bean", 0), "P": ("petal_purple", 0)}
YELLOWED = {"4": "k", "3": "j", "2": "c", "1": "b"}


def _soy_plant(g, cx, top, spots, stem="1", recolor=None):
    """One plant at column cx (4 or 12: where the planes cross): trifoliate leaves up a single stem."""
    g.line([(cx, 15), (cx, top + 2)], stem)
    for dx, y in spots:
        g.sprite(TRIO, cx + dx, y, recolor)
    return g


SOY_YOUNG = [(-2, 9), (-4, 11), (-1, 12)]
SOY_FULL = [(-2, 1), (-4, 4), (-1, 5), (-4, 8), (-1, 9), (-3, 11)]
SOY_BARE = [(-2, 1), (-1, 5), (-4, 7)]           # ripe: most leaves have dropped


POD_L = ["l.", "ki", "jh", "ih", ".h"]        # a ripe pod hanging left of the stem: fuzzy, light on its upper edge
POD_R = [".l", "ik", "hj", "hi", "h."]


def _soy(stage):
    g = Grid()
    for cx in (4, 12):
        if stage == 1:
            _soy_plant(g, cx, 9, SOY_YOUNG)
        elif stage == 2:
            _soy_plant(g, cx, 1, SOY_FULL)
            for dx, y in ((-1, 7), (1, 10), (-1, 12), (1, 4)):      # small purple flowers where leaves meet the stem
                g.put(cx + dx, y, "P")
        else:
            # ripe: the leaves yellow and drop, the stems are hung with flat brown pods (flat, so drawn on the planes)
            _soy_plant(g, cx, 2, SOY_BARE, stem="i", recolor=YELLOWED)
            for pod, dx, y in ((POD_L, -2, 4), (POD_R, 1, 6), (POD_L, -2, 8), (POD_R, 1, 10), (POD_L, -3, 11), (POD_R, 1, 2)):
                g.sprite(pod, cx + dx, y)
    return g


STAGES["soybean"] = [
    Stage(image(seedlings().rows())),
    Stage(image(_soy(1).rows())),
    Stage(image(_soy(2).rows(), SOY)),
    Stage(image(_soy(3).rows(), SOY)),
]

# ================================================================================================
# 옥수수: a tall stalk (two blocks when grown), tassel on top, cobs in husks with silk
# ================================================================================================

CORN = {"A": ("corn", 3), "B": ("corn", 2), "C": ("corn", 1), "D": ("corn", 0), "E": ("soil", 2), "F": ("soil", 3)}


def _stalk(g, x, top, bottom=15, c="432"):
    g.col(x, top, bottom, c[0] + c[1] + c[1])
    g.col(x + 1, top + 1, bottom, c[2] + c[2] + c[2])
    return g


def _blade(g, x, y, right=True, length=6, light="4", dark="2"):
    """A long corn leaf, two pixels wide: rising from the stalk, arching out and drooping at the tip."""
    d = 1 if right else -1
    for k in range(length):
        rise = 0 if k == 0 else 1 if k < 3 else 2 if k < length - 1 else 1
        g.put(x + d * k, y - rise, light)
        if k < length - 1:
            g.put(x + d * k, y - rise + 1, dark)
    return g


STAGES["corn"] = [
    image(seedling(BLADES).rows()),
    image(_blade(_blade(_blade(_stalk(Grid(), 7, 10), 7, 13, False, 5), 8, 11, True, 6), 7, 10, False, 4, "4", "3").rows()),
    image(_blade(_blade(_blade(_blade(_blade(_stalk(Grid(), 7, 2), 7, 13, False, 7), 8, 10, True, 7), 7, 7, False, 6), 8, 4, True, 6),
                 7, 3, False, 4, "4", "3").line([(7, 2), (7, 0)], "4").rows()),
]


def _corn_tall(ripe):
    """(bottom block, top block) of a full-grown plant: 32 rows, drawn as one."""
    g = Grid(32)
    _stalk(g, 7, 6, 31, "432")
    low_l, low_d = ("k", "i") if ripe else ("4", "2")      # the lowest leaves dry first
    for x, y, right, n, li, da in ((7, 29, False, 7, low_l, low_d), (8, 26, True, 7, low_l, low_d), (7, 22, False, 7, "4", "2"),
                                   (8, 18, True, 7, "4", "2"), (7, 14, False, 6, "4", "2"), (8, 11, True, 5, "4", "3"),
                                   (7, 8, False, 4, "4", "3")):
        _blade(g, x, y, right, n, li, da)
    # tassel
    t1, t2, t3 = ("k", "j", "i") if ripe else ("d", "c", "b")
    g.line([(7, 6), (7, 0)], t1).line([(7, 3), (5, 1), (4, 2)], t2).line([(8, 3), (10, 1), (11, 2)], t3).put(6, 4, t3).put(9, 5, t2)
    # cobs in husks with silk on top; ripe ones open a little at the tip
    cob = ["E.", "FE", "kA", "kB", "jA", "jB", "ji", ".i"] if ripe else ["c.", "dc", "dc", "dc", "cb", "cb", "cb", ".b"]
    g.sprite(cob, 9, 15).sprite(cob, 5, 20)
    whole = image(g.rows(), CORN)            # lit as one plant, then cut into the two blocks
    return whole.crop((0, 16, 16, 32)), whole.crop((0, 0, 16, 16))


STAGES["corn"] += [_corn_tall(False), _corn_tall(True)]
STAGES["corn"] = [Stage(st, "cross") for st in STAGES["corn"]]      # one stalk per block, not four around it

# ================================================================================================
# 파: dense clumps of dark hollow leaves on white stems
# ================================================================================================


def _scallion(g, x, top, base):
    """Four hollow leaves fanning up from a white stem: columns x..x+3, so x=2 and x=10 meet the crossings."""
    for dx, t, ch in ((0, top + 2, "x"), (1, top, "y"), (2, top + 1, "x"), (3, top + 3, "w")):
        g.line([(x + dx, base), (x + dx + (dx - 1.5) * 0.6, t)], ch)
    return g


STEM_YOUNG = Part((3, -1, 3), (5, 1, 5), ["VV", "WW"], ["xy", "yx"])
STEM = Part((3, -1, 3), (5, 3, 5), ["xV", "VV", "VW", "WW"], ["xy", "yx"])

STAGES["green_onion"] = [
    Stage(image(seedlings(BLADES, xs=(2, 10)).rows())),
    Stage(image(Grid().line([(3, 15), (3, 10)], "x").line([(4, 15), (5, 9)], "y").line([(11, 15), (11, 9)], "x")
                .line([(12, 15), (13, 10)], "w").rows())),
    Stage(image(_scallion(_scallion(Grid(), 2, 6, 14), 10, 7, 14).rows()), parts=four(STEM_YOUNG)),
    Stage(image(_scallion(_scallion(Grid(), 2, 1, 12), 10, 2, 12).rows()), parts=four(STEM)),
]

# ================================================================================================
# 무: a rosette of rough leaves; the white root with a green shoulder pushes out of the ground
# ================================================================================================

RADISH = {"A": ("garlic", 3), "B": ("garlic", 2), "C": ("garlic", 1), "D": ("garlic", 0)}
ROUGH = [".44.", "4432", "4321", ".21."]



def _rosette(g, spots, crown=(7, 15)):
    for x, y in spots:
        g.line([crown, (x + 1, y + 3)], "2")
    for x, y in spots:
        g.sprite(ROUGH, x, y)
    return g


RADISH_TOP = Part((7, -1, 7), (9, 1, 9), ["dc", "AB"], ["dc", "cd"], RADISH)
RADISH_ROOT = Part((5, -1, 5), (11, 5, 11), [
    "ccddcb",
    "bcddcb",
    "AcAdBc",
    "AAAABC",
    "BAAABC",
    "BBAACD",
], ["bccccb", "cddddc", "cd22dc", "cd22dc", "cddddc", "bccccb"], RADISH)

RADISH_CROWN = Part((6, 5, 6), (10, 6, 10), ["cddc"], ["cddc", "d22d", "d22d", "cddc"], RADISH)

STAGES["radish"] = [
    Stage(image(seedling().rows()), "cross"),
    Stage(image(_rosette(Grid(), [(1, 11), (5, 10), (9, 10), (12, 11)]).rows()), "cross"),
    Stage(image(_rosette(Grid(), [(0, 8), (3, 6), (8, 6), (12, 8), (2, 10), (10, 10)]).rows()), "cross", [RADISH_TOP]),
    Stage(image(_rosette(Grid(), [(0, 4), (3, 2), (8, 2), (12, 4), (1, 7), (11, 7)], crown=(7, 9)).rows()), "cross",
          [RADISH_ROOT, RADISH_CROWN]),
]

# ================================================================================================
# 상추: a low rosette of ruffled light leaves
# ================================================================================================

RUFFLE = [".e.d.", "edddc", "dcccb", ".bcb."]

LETTUCE_HEART = [
    Part((5, -1, 5), (11, 1, 11), ["dcedcd", "bcbcab"], ["cdedcd", "dcddec", "edcbdc", "cdbcde", "ceddcd", "dcdecd"]),
    Part((6, 1, 6), (10, 2, 10), ["edde"], ["edde", "dccd", "dccd", "edde"]),
]

STAGES["lettuce"] = [
    Stage(image(seedling().rows()), "cross"),
    Stage(image(Grid().sprite(RUFFLE, 3, 12).sprite(RUFFLE, 8, 12).sprite(RUFFLE, 6, 11).rows()), "cross"),
    Stage(image(Grid().sprite(RUFFLE, 0, 11).sprite(RUFFLE, 5, 10).sprite(RUFFLE, 10, 11).sprite(RUFFLE, 3, 12)
                .sprite(RUFFLE, 8, 12).rows()), "cross"),
    Stage(image(Grid().sprite(RUFFLE, 0, 10).sprite(RUFFLE, 4, 8).sprite(RUFFLE, 8, 8).sprite(RUFFLE, 11, 10).sprite(RUFFLE, 2, 11)
                .sprite(RUFFLE, 6, 11).sprite(RUFFLE, 10, 12).rows()), "cross", LETTUCE_HEART),
]

# ================================================================================================
# 오이: a vine climbing a stake, yellow flowers, long bumpy fruit hanging down
# ================================================================================================

CUKE = {"A": ("cucumber", 3), "B": ("cucumber", 2), "C": ("cucumber", 1), "D": ("cucumber", 0), "E": ("sprout", 3)}
BIG = ["44.44", "45444", "34543", ".343.", "..2.."]     # broad heart-shaped vine leaf with a light vein


def _vine(flowers=False):
    g = Grid()
    g.col(7, 0, 15, "onm").col(8, 1, 15, "nnm")                       # the stake
    g.line([(7, 15), (6, 12), (9, 9), (6, 6), (9, 3), (8, 1)], "2")   # the vine winding up it
    for x, y in ((0, 6), (10, 4), (2, 1), (11, 0), (1, 10), (11, 9), (4, 4), (9, 12)):
        g.sprite(BIG, x, y)
    for x, y in ((5, 10), (4, 11), (3, 11), (12, 13), (13, 12), (6, 3), (5, 2)):    # curly tendrils
        g.put(x, y, "c")
    if flowers:
        for x, y in ((4, 2), (12, 6), (2, 8), (13, 11)):
            g.sprite(FLOWER_Y, x, y)
    return g


# fruit: solid, dark and bumpy, hanging round the stake; young ones still carry their flower
CUKE_YOUNG = Part((0, 0, 0), (1, 3, 1), ["B", "A", "Y"], ["B"], dict(CUKE, Y=("petal_yellow", 1)))
CUKE_RIPE = Part((0, 0, 0), (2, 6, 2), ["2B", "BA", "EB", "BC", "CE", "DC"], ["2B", "BC"], CUKE)
CUKE_SHORT = Part((0, 0, 0), (2, 4, 2), ["BA", "BE", "EC", "DC"], ["2B", "BC"], CUKE)

STAGES["cucumber"] = [Stage(st, "cross", parts) for st, parts in (
    (image(seedling().rows()), []),
    (image(Grid().sprite(BIG, 0, 10).sprite(BIG, 10, 9).line([(4, 15), (5, 12), (9, 11)], "2").sprite(BIG, 5, 11).rows()), []),
    (image(_vine().rows()), []),
    (image(_vine(flowers=True).rows()), [CUKE_YOUNG.moved(4, 9).lifted(6), CUKE_YOUNG.moved(11, 6).lifted(8),
                                        CUKE_YOUNG.moved(6, 3).lifted(3)]),
    (image(_vine().rows()), [CUKE_RIPE.moved(2, 9).lifted(2), CUKE_RIPE.moved(12, 5).lifted(5), CUKE_SHORT.moved(6, 1).lifted(1),
                             CUKE_SHORT.moved(9, 12).lifted(4)]),
)]

# ================================================================================================
# 포도: a woody vine on a post with a crossbar, lobed leaves, bunches (green, then purple)
# ================================================================================================

GRAPE = {"A": ("grape", 4), "B": ("grape", 3), "C": ("grape", 2), "D": ("grape", 1), "E": ("grape", 0),
         "J": ("cabbage", 3), "K": ("cabbage", 2), "L": ("cabbage", 1), "M": ("cabbage", 0)}
BUNCH_PURPLE = ["ABAB", "BCBC", ".CD.", ".DE.", "..E."]
BUNCH_GREEN = ["JKJK", "KLKL", ".LM.", ".KM.", "..M."]
LOBED = [".4.4.", "44432", "43332", ".321."]


def _trellis(bunches=None):
    g = Grid()
    g.col(7, 1, 15, "onm").col(1, 1, 1, "o").line([(0, 1), (15, 1)], "n")          # post and crossbar
    g.line([(7, 15), (7, 4), (4, 2), (1, 2)], "m").line([(7, 4), (11, 2), (15, 2)], "m")   # the vine along the bar
    for x, y in ((0, 2), (4, 3), (9, 2), (12, 4), (2, 6), (10, 7), (5, 8)):
        g.sprite(LOBED, x, y)
    if bunches:
        for x, y in ((2, 5), (10, 5), (6, 7)):
            g.sprite(bunches, x, y)
    return g


STAGES["grape"] = [
    image(Grid().col(5, 10, 15, "onm").col(11, 11, 15, "onm").sprite(["4.", "32"], 6, 10).sprite([".4", "32"], 9, 11).rows()),
    image(Grid().line([(5, 15), (5, 8), (3, 6)], "m").line([(5, 10), (8, 8)], "m").sprite(LOBED, 1, 5).sprite(LOBED, 6, 6)
          .line([(11, 15), (11, 9)], "m").sprite(LOBED, 9, 8).rows()),
    image(_trellis().rows()),
    image(_trellis(BUNCH_GREEN).rows(), GRAPE),
    image(_trellis(BUNCH_PURPLE).rows(), GRAPE),
]

# ================================================================================================
# 가지: a branching bush on purple-tinged stems, star-shaped purple flowers, glossy fruit hanging down
# ================================================================================================

EGG = {"A": ("aubergine", 4), "B": ("aubergine", 3), "C": ("aubergine", 2), "D": ("aubergine", 1), "E": ("aubergine", 0),
       "S": ("aubergine", 1), "P": ("petal_purple", 1), "Q": ("petal_purple", 0), "Y": ("petal_yellow", 1)}
EGG_LEAF = [".443.", "44332", "43321", ".321."]        # broad, soft, slightly grey leaf
EGG_FLOWER = [".P.", "QYP", ".Q."]


def _egg_bush(top, flowers=False):
    g = Grid()
    g.line([(8, 15), (8, top + 3)], "S")                                          # main stem
    g.line([(8, 12), (4, top + 6)], "S").line([(8, 10), (12, top + 4)], "S").line([(8, 7), (5, top + 1)], "S")
    for x, y in ((5, top), (9, top + 1), (1, top + 3), (11, top + 4), (3, top + 7), (10, top + 7), (6, top + 4), (0, top + 9),
                 (12, top + 9)):
        if y + 3 <= 15:
            g.sprite(EGG_LEAF, x, y)
    if flowers:
        for x, y in ((3, top + 5), (11, top + 2), (7, top + 8)):
            g.sprite(EGG_FLOWER, x, y)
    return g


# fruit: green star of a calyx on top, then glossy near-black purple with a highlight down one side
EGG_YOUNG = Part((0, 0, 0), (1, 3, 1), ["3", "C", "D"], ["3"], EGG)
EGG_TOP = Part((0, 0, 0), (2, 3, 2), ["32", "BA", "CA"], ["23", "32"], EGG)
EGG_BOTTOM = Part((0, 0, 0), (2, 4, 2), ["CB", "CB", "DC", "ED"], ["BA", "CB"], EGG)


def _eggplant(x, z, y, dx=1, dz=0):
    """A long fruit with a gentle bend: the lower half hangs a pixel further out."""
    return [EGG_TOP.moved(x, z).lifted(y + 4), EGG_BOTTOM.moved(x + dx, z + dz).lifted(y)]


STAGES["eggplant"] = [
    Stage(image(seedling().rows()), "cross"),
    Stage(image(Grid().line([(8, 15), (8, 10)], "S").sprite(EGG_LEAF, 3, 9).sprite(EGG_LEAF, 8, 8).sprite(EGG_LEAF, 5, 11).rows(), EGG),
          "cross"),
    Stage(image(_egg_bush(2).rows(), EGG), "cross"),
    Stage(image(_egg_bush(2, flowers=True).rows(), EGG), "cross",
          [EGG_YOUNG.moved(5, 7).lifted(5), EGG_YOUNG.moved(10, 9).lifted(6), EGG_YOUNG.moved(7, 4).lifted(4)]),
    Stage(image(_egg_bush(2).rows(), EGG), "cross",
          _eggplant(4, 7, 1, dx=-1) + _eggplant(10, 9, 2) + _eggplant(7, 3, 0, dx=0, dz=-1)),
]

# ================================================================================================
# 애호박: big lobed leaves with silvery patches on long stalks, yellow trumpet flowers, pale fruit lying on the soil
# ================================================================================================

ZUC = {"A": ("sprout", 4), "B": ("sprout", 3), "C": ("sprout", 2), "D": ("sprout", 1), "M": ("cabbage", 3),
       "Y": ("petal_yellow", 1), "Z": ("petal_yellow", 2), "O": ("petal_yellow", 0)}
SQ_LEAF = ["44.444", "4M54M4", "345M43", ".34432", "...2.."]
BLOSSOM = [".ZZ.", "ZYYZ", ".YO.", "..2."]


def _squash(top, flowers=False):
    g = Grid()
    spots = [(0, top + 4), (10, top + 3), (5, top), (2, top + 8), (9, top + 8), (6, top + 5)]
    for x, y in spots:                                                     # long hollow stalks from the crown
        g.line([(8, 15), (x + 2, y + 4)], "3")
    for x, y in spots:
        if y + 4 <= 15:
            g.sprite(SQ_LEAF, x, y)
    if flowers:
        for x, y in ((4, top + 9), (11, top + 10)):
            g.sprite(BLOSSOM, x, y)
    return g


# fruit lying on the soil: pale green with darker speckles, a dark stalk end and a paler tip
ZUC_YOUNG = Part((0, -1, 0), (3, 0, 1), ["2BY"], ["2AY"], ZUC, end=["B"])
ZUC_X = Part((0, -1, 0), (6, 2, 3), ["3AMABA", "2BABMB", "2CBCBC"], ["3ABMAB", "3AABAA", "2BABAB"], ZUC, end=["ABA", "BMB", "CBC"])
ZUC_Z = Part((0, -1, 0), (3, 2, 6), ["ABA", "BMB", "CBC"], ["323", "ABA", "BAM", "AMB", "BAB", "ABA"], ZUC, end=["ABAMA3", "BMBAB2", "CBCBC2"])

STAGES["zucchini"] = [
    Stage(image(seedling().rows()), "cross"),
    Stage(image(_squash(8).rows(), ZUC), "cross"),
    Stage(image(_squash(3).rows(), ZUC), "cross"),
    Stage(image(_squash(3, flowers=True).rows(), ZUC), "cross", [ZUC_YOUNG.moved(3, 10), ZUC_YOUNG.moved(10, 4)]),
    Stage(image(_squash(3, flowers=True).rows(), ZUC), "cross", [ZUC_X.moved(1, 10), ZUC_Z.moved(11, 1)]),
]

# ================================================================================================
# 멜론: a low vine with round leaves and tendrils; a green melon swells and ripens into a netted one
# ================================================================================================

MEL = {"A": ("melon_rind", 4), "B": ("melon_rind", 3), "C": ("melon_rind", 2), "D": ("melon_rind", 1), "E": ("melon_rind", 0),
       "G": ("cucumber", 2), "H": ("cucumber", 1), "J": ("cucumber", 0), "K": ("cucumber", 3), "Y": ("petal_yellow", 1),
       "O": ("petal_yellow", 0), "N": ("garlic", 3), "R": ("musk", 2), "S": ("musk", 1)}
MEL_LEAF = [".44.4", "44544", "34543", ".332."]


def _melon_vine(spread, flowers=False):
    g = Grid()
    g.line([(8, 15), (3, 14), (0, 15)], "2").line([(8, 15), (13, 14), (15, 13)], "2")      # runners along the ground
    spots = [(0, 11), (11, 11), (4, 12), (8, 12)] if spread == 1 else \
            [(0, 9), (11, 8), (3, 11), (9, 11), (0, 12), (12, 12), (5, 9), (7, 12)]
    for x, y in spots:
        g.sprite(MEL_LEAF, x, y)
    for x, y in ((3, 10), (2, 9), (13, 10), (14, 9), (6, 8)):                            # curling tendrils
        if spread > 1:
            g.put(x, y, "c")
    if flowers:
        for x, y in ((2, 8), (12, 7), (7, 10)):
            g.sprite(["Y.Y", ".O.", "Y.Y"], x, y)
    return g


MELON_YOUNG = Part((6, -1, 6), (10, 2, 10), ["HGKG", "GHGH", "JHJH"], ["GKGH", "KGHG", "GHGK", "HGKG"], MEL)
# the ripe melon: a soft grey-green rind under a raised white net, built round from a core, a cross of
# bulges and a cap, with the T-shaped stalk muskmelons are sold with
MELON = [
    Part((5, -1, 5), (11, 4, 11), net_rows(6, 5, 1), net_rows(6, 6, 2, False), MEL),
    Part((4, 0, 6), (12, 3, 10), net_rows(8, 3, 3), net_rows(8, 4, 4, False), MEL, end=net_rows(4, 3, 5)),
    Part((6, 0, 4), (10, 3, 12), net_rows(4, 3, 6), net_rows(4, 8, 7, False), MEL, end=net_rows(8, 3, 8)),
    Part((6, 4, 6), (10, 5, 10), net_rows(4, 1, 9, False), net_rows(4, 4, 10, False), MEL),
    Part((7, 5, 7), (8, 6, 8), ["j"], ["k"]),                                    # the stalk
    Part((6, 6, 7), (9, 7, 8), ["ikj"], ["jkl"], end=["j"]),                     # the bit of vine across it
]

STAGES["muskmelon"] = [
    Stage(image(seedling().rows()), "cross"),
    Stage(image(_melon_vine(1).rows()), "cross"),
    Stage(image(_melon_vine(2, flowers=True).rows(), MEL), "cross"),
    Stage(image(_melon_vine(2).rows(), MEL), "cross", [MELON_YOUNG]),
    Stage(image(_melon_vine(2).rows(), MEL), "cross", MELON),
]

# ================================================================================================
# Icons
# ================================================================================================
#
# Every icon is drawn on a larger sheet, outlined, then centred on the 16x16 grid with even margins
# (one pixel more on the right/bottom when the space is odd), so all crop icons sit the same way in a slot.

PAD = 3


# light materials whose own darkest step is too pale to read as an outline
OUTLINE = {"garlic": ("stone", 0), "snow": ("stone", 0), "paper": ("bean", 0), "cabbage": ("leaf", 0), "petal_yellow": ("straw", 0)}


def _fit(ic):
    drawn = set(ic.px)
    ic.outline()
    for k in set(ic.px) - drawn:
        r, _ = ic.px[k]
        if r in OUTLINE:
            ic.px[k] = OUTLINE[r]
    im = ic.image()
    box = im.getbbox()
    w, h = box[2] - box[0], box[3] - box[1]
    if w > 16 or h > 16:
        raise ValueError(f"icon too big: {w}x{h}")
    out = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    out.paste(im.crop(box), ((16 - w) // 2, (16 - h) // 2))
    return out


def _cells(rows):
    return {(x, y): ch for y, r in enumerate(rows) for x, ch in enumerate(r) if ch != "."}


def icon(rows, extra=None):
    """An icon drawn pixel by pixel (every letter in the legend), outlined and centred."""
    legend = dict(BASE, **(extra or {}))
    ic = I.Icon(16 + 2 * PAD, 16 + 2 * PAD)
    for (x, y), ch in _cells(rows).items():
        r, i = legend[ch]
        ic.put(x + PAD, y + PAD, r, i)
    return _fit(ic)


def _dist(cells, x, y, dx, dy, body):
    n = 0
    while cells.get((x + dx * (n + 1), y + dy * (n + 1)), ".") in body:
        n += 1
    return n


def shade(rows, body, extra=None, lo=1, hi=None, parts="#"):
    """An icon whose `parts` letters are one rounded body in ramp `body`, lit from the top-left (each
    cell's step comes from how far it is from the edges on each side); other letters are details."""
    legend = dict(BASE, **(extra or {}))
    n = _size(body)
    hi = n - 1 if hi is None else hi
    cells = _cells(rows)
    solid = set(parts) | set(extra or {})          # details painted on the body still count as body
    ic = I.Icon(16 + 2 * PAD, 16 + 2 * PAD)
    for (x, y), ch in cells.items():
        if ch not in parts:
            r, i = legend[ch]
            ic.put(x + PAD, y + PAD, r, i)
            continue
        le, ri = _dist(cells, x, y, -1, 0, solid), _dist(cells, x, y, 1, 0, solid)
        up, dn = _dist(cells, x, y, 0, -1, solid), _dist(cells, x, y, 0, 1, solid)
        nx = (le - ri) / max(1, le + ri)
        ny = (up - dn) / max(1, up + dn)
        lit = (-nx - ny) / 2                    # +1 facing the top-left light, -1 facing away
        i = round((lo + hi) / 2 + lit * (hi - lo) / 2 * 1.25)
        ic.put(x + PAD, y + PAD, body, max(lo, min(hi, i)))
    return _fit(ic)


GLINT = ("snow", 2)

ICONS["tomato"] = lambda: shade([
    "....2.3.....",
    "...434234...",
    "..#4#3#4#...",
    ".##VQ##S###.",
    "#QQ########S",
    "#Q#########S",
    "###########S",
    "###########S",
    ".#########S.",
    "..#######S..",
    "....###.....",
], "tomato", {"V": GLINT, "Q": ("tomato", 4), "S": ("tomato", 1)}, lo=0)

ICONS["chili_pepper"] = lambda: shade([
    "..2...........",
    ".32...........",
    "4343..........",
    "####..........",
    "#VF##.........",
    "#F###.........",
    "#F####........",
    ".#F###........",
    ".#F####.......",
    "..#F####......",
    "...##F###....#",
    "....#######.#.",
    ".....######S..",
    ".......SSS....",
], "pepper", {"V": GLINT, "F": ("pepper", 4), "S": ("pepper", 1)}, lo=0)

ICONS["strawberry"] = lambda: shade([
    "..d.4.d.4.d",
    ".edc434cde4",
    "..d3c4c3dc.",
    ".##4#34#4##.",
    "############",
    "#VQ#Z###Z###",
    "#Q#Z###Z###S",
    "###########S",
    "#Z###Z###Z#S",
    ".#########S.",
    ".##Z###Z##S.",
    "..#######S..",
    "...##Z##S...",
    ".....##.....",
], "toadstool", {"V": GLINT, "Q": ("toadstool", 4), "S": ("toadstool", 1), "Z": ("petal_yellow", 2)}, lo=1)

ICONS["onion"] = lambda: shade([
    "......3.",
    ".....43.",
    "......4.",
    ".....###",
    "...#L#D##",
    "..#L##D###",
    ".#VL##D###D#",
    ".#L###D###D#",
    "#L####D###D##",
    "#L####D###D##",
    ".####D###D##",
    "..###D##D##",
    "....#####",
    "....W.W.W",
], "onion", {"L": ("onion", 3), "D": ("onion", 1), "V": ("garlic", 3), "W": ("straw", 3)}, lo=0)

ICONS["garlic"] = lambda: shade([
    "......k......",
    ".....kj......",
    "......ji.....",
    ".....#V#.....",
    "....#V#D#....",
    "...#V#D#V#...",
    ".##V##D##V##.",
    "#V#D##D##D#D#",
    "#V#D#VD#V#D##",
    "#V#D#VD#V#D##",
    ".#D##V#U#D#D.",
    "..##D#U#D##..",
    "....#####....",
    "...k.j.k.j...",
], "garlic", {"V": ("garlic", 3), "D": ("garlic", 0), "U": ("petal_purple", 0)}, lo=0)

ICONS["napa_cabbage"] = lambda: icon([
    ".....5445",
    "....54MLL4",
    "...54MLMLK3",
    "...4MLKLKK3",
    "..44LMLKLK32",
    "..4MLKVLKK32",
    "..4LMKVLKJ32",
    "..4MLKVKKJ32",
    "..44LKVKJ321",
    "...4LKVKJ32",
    "...44KVKJ32",
    "....4KVJ321",
    "....4WVW31",
    ".....WWW",
], {"M": ("cabbage", 3), "L": ("cabbage", 2), "K": ("cabbage", 1), "J": ("cabbage", 0), "V": ("garlic", 3), "W": ("garlic", 2)})

ICONS["paddy"] = lambda: icon([
    "......hiijj",
    ".....hi...kj",
    "....hi....lkj",
    "...hi....lklk",
    "...i.....kljk",
    "..hi.....lklj",
    "..i......kljk",
    ".hi......jklj",
    ".i........kjk",
    "hi........lkj",
    "i..........kj",
    "i..........j",
], {})

ICONS["soybean"] = lambda: icon([
    "...........2.",
    "..........cb.",
    ".........cedb",
    "........ceedb",
    "........bddc.",
    ".......cbcb..",
    "......ceedb..",
    ".....ceedcb..",
    ".....bddcb...",
    "....cbcb.....",
    "...ceedb..AAB",
    "..ceedcb.ABBC",
    "..bddcb..ABDC",
    "...bbb....BC.",
], {"A": ("bean", 3), "B": ("bean", 2), "C": ("bean", 1), "D": ("bean", 0)})

ICONS["corn"] = lambda: icon([
    "...........kj.",
    "..........BCB.",
    "........ABCBCB",
    ".......ABCBCBD",
    "......ABCBCBD.",
    ".....ABCBCBD..",
    "...eABCBCBD...",
    "..edBCBCBd....",
    ".edcCBCBdc....",
    "edcbBCBdcb....",
    "dcbBCBdcb.....",
    "cbcCdcba......",
    "bcbccba.......",
    ".ba...........",
], {"A": ("corn", 3), "B": ("corn", 2), "C": ("corn", 1), "D": ("corn", 0)})

ICONS["green_onion"] = lambda: icon([
    "...........yxw",
    ".........y.xw",
    "........yxyxw",
    ".......yxyxw",
    "......yxdxw",
    ".....yddc",
    "....VVdc",
    "...VVW",
    "..VVW",
    ".VVW",
    "VVW",
    "VWW",
    "WWkj",
    "j.k.j",
], {"V": ("garlic", 3), "W": ("garlic", 2)})

ICONS["radish"] = lambda: shade([
    ".........4.4.4",
    ".........d4343",
    ".......edcdc3.",
    "......edcdcb..",
    ".....#edcdcb..",
    "....#V#edcbb..",
    "...#V###dcbb..",
    "...#V######...",
    "..#V#######...",
    "..#V######....",
    ".#########....",
    "..######......",
    "..#####.......",
    ".#............",
], "garlic", {"V": ("snow", 2)}, lo=0)

ICONS["lettuce"] = lambda: icon([
    "..e.ed.ed.e...",
    ".edcdedcdedcd.",
    "edcbcdcdcdcbce",
    ".dcbcdedcbcbd.",
    "edcecdVdcecbce",
    "dcbcecVcecbcbd",
    ".dcbceVecbcbd.",
    "edcbcdVdcbcbce",
    "dcbcecVcecbcbd",
    "edcbcbVbcbcbcd",
    "dcbcbcVcbcbcbc",
    ".bcbcbVbcbcbb.",
    "..bbcWVWcbb...",
    "....bWWWb.....",
], {"V": ("cabbage", 3), "W": ("cabbage", 2)})

ICONS["cucumber"] = lambda: icon([
    ".2............",
    "2AB...........",
    "ABWC..........",
    "AWBC..........",
    "ABBCD.........",
    ".AWBCD........",
    ".ABBWCD.......",
    "..AWBBCD......",
    "...ABBWCD.....",
    "....AWBBCDD...",
    ".....AABWBCDD.",
    ".......AABBCDY",
    ".........BCDD.",
], {"A": ("cucumber", 3), "B": ("cucumber", 2), "C": ("cucumber", 1), "D": ("cucumber", 0), "W": ("sprout", 4), "Y": ("petal_yellow", 1)})

ICONS["grapes"] = lambda: icon([
    ".....d...edd",
    ".....cdedcdc",
    "....ABAcbcb.",
    "...AVCA.bb..",
    "..ABAEBABA..",
    ".AVCADAVCA..",
    ".BCDBABCDB..",
    "..AVCAVCAVC.",
    "..BCDBCDBCD.",
    "...ABAVCAB..",
    "...BAVCBCD..",
    "....BCDAVC..",
    ".....AVCCD..",
    "......CD....",
], {"A": ("grape", 4), "B": ("grape", 3), "C": ("grape", 2), "D": ("grape", 1), "E": ("grape", 0), "V": ("petal_purple", 2)})

ICONS["eggplant"] = lambda: shade([
    ".2............",
    ".32...........",
    "4323..........",
    ".3#43.........",
    "..#V#3........",
    "..#VB##.......",
    "..#B####......",
    "...#B####.....",
    "...##B#####...",
    "....##B#####..",
    ".....##B######",
    "......#######S",
    "........#####S",
    "...........#SS",
], "aubergine", {"V": ("petal_purple", 2), "B": ("aubergine", 4), "S": ("aubergine", 0)}, lo=0)

ICONS["zucchini"] = lambda: shade([
    "..........xw..",
    ".........wx#..",
    "........#####.",
    ".......###M##.",
    ".....########.",
    "....##M#####..",
    "...########...",
    "..####M###....",
    ".#M######.....",
    ".#######......",
    ".#####M#......",
    ".######.......",
    "..#O##........",
], "sprout", {"M": ("cabbage", 3), "O": ("petal_yellow", 0), "x": ("leaf_dark", 2), "w": ("leaf_dark", 1)}, lo=1)

def _melon_icon():
    """A whole muskmelon: grey-green ball under a fine white net, both lit like a sphere from the top-left
    (so the net wraps round it instead of lying flat), with the T-shaped stalk on top."""
    cx, cy, r = 6.5, 8.1, 6.0
    inside = lambda x, y: ((x + 0.5 - cx) / r) ** 2 + ((y + 0.5 - cy) / (r * 0.96)) ** 2 <= 1   # noqa: E731
    mesh = melon_net(14, 15, 8, spacing=2.6, width=0.5, inside=inside, origin=(0.4, 1.6), reach=0)
    light = (-0.55, -0.62, 0.56)
    norm = math.sqrt(sum(c * c for c in light))
    lx, ly, lz = (c / norm for c in light)
    ic = I.Icon(16 + 2 * PAD, 16 + 2 * PAD)
    for y in range(15):
        for x in range(14):
            if not inside(x, y):
                continue
            u, v = (x + 0.5 - cx) / r, (y + 0.5 - cy) / (r * 0.96)
            b = u * lx + v * ly + math.sqrt(max(0.0, 1 - u * u - v * v)) * lz
            if (x, y) in mesh:
                ic.put(x + PAD, y + PAD, "garlic", 1 if b < 0.15 else 2 if b < 0.55 else 3)
            else:
                ic.put(x + PAD, y + PAD, "musk", 0 if b < -0.1 else 1 if b < 0.3 else 2 if b < 0.65 else 3)
    for x, i in ((4, 1), (5, 2), (6, 3), (7, 2), (8, 1)):      # the T: a bit of vine across the top
        ic.put(x + PAD, PAD, "straw", i)
    ic.put(6 + PAD, 1 + PAD, "straw", 2)
    ic.put(6 + PAD, 2 + PAD, "straw", 0)
    ic.put(3 + PAD, 5 + PAD, "snow", 2)                        # glint
    return _fit(ic)


ICONS["muskmelon"] = _melon_icon


# ------------------------------------------------------------------------------------------------
# Seed packets: a paper packet showing the crop, a band in the crop's colour
# ------------------------------------------------------------------------------------------------

MINI = {   # 6 x 6 pictures for the packet window (legend: BASE + the crop's letters)
    "tomato": ([".343..", "TQTTS.", "QVTTSR", "TTTTSR", "TTTSSR", ".SSRR."], {"T": ("tomato", 2), "Q": ("tomato", 3), "V": ("tomato", 4), "S": ("tomato", 1), "R": ("tomato", 0)}),
    "chili": (["3.....", "43....", "GH....", "GHH...", ".GHJ.K", "..JJK."], {"G": ("pepper", 3), "H": ("pepper", 2), "J": ("pepper", 1), "K": ("pepper", 0)}),
    "strawberry": ([".4343.", "QPQSS.", "QZQSZ.", ".QSZR.", ".SSR..", "..R..."], {"P": ("toadstool", 4), "Q": ("toadstool", 3), "S": ("toadstool", 2), "R": ("toadstool", 1), "Z": ("petal_yellow", 2)}),
    "onion": (["..3...", ".ABB..", "ABBCC.", "ABBCD.", ".BCD..", "..W..."], {"A": ("onion", 3), "B": ("onion", 2), "C": ("onion", 1), "D": ("onion", 0), "W": ("garlic", 1)}),
    "cabbage": ([".454..", "4AAB3.", "4ABB3.", "4AVB3.", ".4V32.", "..W..."], {"A": ("cabbage", 3), "B": ("cabbage", 2), "V": ("garlic", 3), "W": ("garlic", 2)}),
    "rice": (["...lk.", "..lk.k", ".kj.l.", ".ij...", "ji....", "h....."], {}),
    "corn": (["...AB.", "..ABC.", ".ABCD.", ".cBD..", "cdc...", "cb...."], {"A": ("corn", 3), "B": ("corn", 2), "C": ("corn", 1), "D": ("corn", 0)}),
    "green_onion": (["....yx", "...yxw", "..yxw.", ".VW...", "VW....", "W....."], {"V": ("garlic", 3), "W": ("garlic", 2)}),
    "radish": (["...434", "..cdc3", ".VVcd.", "VVWWD.", "VWWD..", ".VD..."], {"V": ("garlic", 3), "W": ("garlic", 2), "D": ("garlic", 1)}),
    "lettuce": ([".e.e..", "edcdd.", "dcVcd.", "dcVcb.", ".bVb..", "..V..."], {"V": ("cabbage", 3)}),
    "cucumber": (["2.....", "AB....", "AEC...", ".AEC..", "..ABCY", "...CC."], {"A": ("cucumber", 3), "B": ("cucumber", 2), "C": ("cucumber", 1), "E": ("sprout", 3)}),
    "grape": (["..m43.", "ABAB2.", "BCBCD.", ".ABCD.", ".BCD..", "..D..."], {"A": ("grape", 4), "B": ("grape", 3), "C": ("grape", 2), "D": ("grape", 1)}),
    "eggplant": (["3.....", "323...", ".BC...", ".BCD..", "..CDDE", "...DEE"], {"B": ("aubergine", 4), "C": ("aubergine", 2), "D": ("aubergine", 1), "E": ("aubergine", 0)}),
    "zucchini": (["....32", "...ABB", "..ABMC", ".AMBC.", "ABBC..", "OBC..."], {"A": ("sprout", 4), "B": ("sprout", 3), "C": ("sprout", 2), "M": ("cabbage", 3), "O": ("petal_yellow", 0)}),
    "muskmelon": ([".jjj..", "..j...", ".RNRN.", "NRRNRR", "RNRRNS", ".SNRS."], {"R": ("musk", 2), "S": ("musk", 1), "N": ("garlic", 3)}),
}
BAND = {"tomato": ("tomato", 2), "chili": ("pepper", 2), "strawberry": ("toadstool", 2), "onion": ("onion", 2), "cabbage": ("leaf", 3),
        "rice": ("straw", 2), "corn": ("corn", 2), "green_onion": ("leaf_dark", 2), "radish": ("leaf", 3), "lettuce": ("sprout", 3),
        "cucumber": ("cucumber", 2), "grape": ("grape", 2), "eggplant": ("aubergine", 2),
        "zucchini": ("sprout", 2), "muskmelon": ("musk", 2)}


def packet(crop):
    """A paper seed packet 12 x 15: crimped top, a fold shadow, the crop in a window, a coloured band."""
    pic, letters = MINI[crop]
    legend = dict(BASE, **letters)
    ic = I.Icon(16 + 2 * PAD, 16 + 2 * PAD)
    put = lambda x, y, r, i: ic.put(x + PAD, y + PAD, r, i)   # noqa: E731
    x0, x1, y0, y1 = 2, 13, 0, 13
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if y == y0:
                put(x, y, "paper", 0 if x % 2 else 2)              # crimped top
            elif y == y0 + 1:
                put(x, y, "paper", 1)                               # the fold under the crimp
            elif x == x0 or y == y0 + 2:
                put(x, y, "paper", 2)                               # lit left and top edges
            elif x == x1 or y == y1:
                put(x, y, "paper", 0)                               # shaded right and bottom edges
            else:
                put(x, y, "paper", 2 if x < x0 + 3 else 1)          # light falls across the front
    for y in range(3, 11):                                          # the window's frame
        for x in range(4, 12):
            if y in (3, 10) or x in (4, 11):
                put(x, y, "paper", 0 if x == 11 or y == 10 else 2)
    for (dx, dy), ch in _cells(pic).items():
        r, i = legend[ch]
        put(5 + dx, 4 + dy, r, i)
    band, step = BAND[crop]
    for x in range(x0 + 1, x1):
        put(x, 11, band, min(_size(band) - 1, step + 1))
        put(x, 12, band, step)
    return _fit(ic)


SEEDS = {"tomato_seeds": "tomato", "chili_seeds": "chili", "strawberry_seeds": "strawberry", "onion_seeds": "onion",
         "cabbage_seeds": "cabbage", "rice_seeds": "rice", "corn_seeds": "corn", "green_onion_seeds": "green_onion",
         "radish_seeds": "radish", "lettuce_seeds": "lettuce", "cucumber_seeds": "cucumber", "grape_seeds": "grape",
         "eggplant_seeds": "eggplant", "zucchini_seeds": "zucchini", "muskmelon_seeds": "muskmelon"}
for _item, _crop in SEEDS.items():
    ICONS[_item] = (lambda c=_crop: packet(c))


# ------------------------------------------------------------------------------------------------
# Preview sheet (docs/previews/crops.png): each crop's harvest, seed and growth stages on farmland
# ------------------------------------------------------------------------------------------------

NAMES = {"tomato": ("토마토", "tomato", "tomato_seeds"), "chili": ("고추", "chili_pepper", "chili_seeds"),
         "strawberry": ("딸기", "strawberry", "strawberry_seeds"), "onion": ("양파", "onion", "onion_seeds"),
         "garlic": ("마늘", "garlic", "garlic"), "cabbage": ("배추", "napa_cabbage", "cabbage_seeds"),
         "rice": ("벼", "paddy", "rice_seeds"), "soybean": ("콩", "soybean", "soybean"), "corn": ("옥수수", "corn", "corn_seeds"),
         "green_onion": ("파", "green_onion", "green_onion_seeds"), "radish": ("무", "radish", "radish_seeds"),
         "lettuce": ("상추", "lettuce", "lettuce_seeds"), "cucumber": ("오이", "cucumber", "cucumber_seeds"),
         "grape": ("포도", "grapes", "grape_seeds"), "eggplant": ("가지", "eggplant", "eggplant_seeds"),
         "zucchini": ("애호박", "zucchini", "zucchini_seeds"), "muskmelon": ("멜론", "muskmelon", "muskmelon_seeds")}


def sheet(path, scale=5):
    from PIL import ImageDraw
    s = scale
    cell = 16 * s
    gap = 10
    left = 2 * cell + 3 * gap
    width = left + 5 * (cell + gap) + gap
    row_h = 2 * cell + 2 * gap
    im = Image.new("RGBA", (width, len(STAGES) * row_h), (34, 38, 46, 255))
    d = ImageDraw.Draw(im)
    farmland = (110, 72, 44, 255)
    for r, (crop, stages) in enumerate(STAGES.items()):
        y0 = r * row_h
        d.rectangle([0, y0 + 1, width, y0 + row_h - 2], fill=(150, 192, 232, 255))
        _, produce, seed = NAMES[crop]
        for k, item in enumerate((produce, seed)):
            x = gap + k * (cell + gap)
            d.rectangle([x - 2, y0 + cell // 2 - 2, x + cell + 1, y0 + cell // 2 + cell + 1], fill=(198, 198, 198, 255))
            im.alpha_composite(ICONS[item]().resize((cell, cell), Image.NEAREST), (x, y0 + cell // 2))
        for k, st in enumerate(stages):
            x = left + k * (cell + gap)
            base = y0 + row_h - gap - s
            d.rectangle([x, base, x + cell - 1, base + s], fill=farmland)
            st = stage(st).flat()
            if isinstance(st, tuple):
                im.alpha_composite(st[0].resize((cell, cell), Image.NEAREST), (x, base - cell))
                im.alpha_composite(st[1].resize((cell, cell), Image.NEAREST), (x, base - 2 * cell))
            else:
                im.alpha_composite(st.resize((cell, cell), Image.NEAREST), (x, base - cell))
    im.save(path)
