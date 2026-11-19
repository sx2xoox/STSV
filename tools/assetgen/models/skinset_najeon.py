"""
자개 시리즈 (najeon: Korean lacquerware with mother-of-pearl inlay): a premium tool skin set.

    najeon_sword  najeon_pickaxe  najeon_shovel  najeon_axe  najeon_hoe  najeon_fishing_rod (+ najeon_fishing_rod_cast)
    najeon_bobber

The frame, the grip point, the sizes and the display transforms are the toy series' (models/toys.py: built upright on
the axis x = z = 8, the hand on y = GRIP_Y, _held() puts the sprite's 45 degree tilt into the transforms).

Looks: glossy deep black lacquer (and deep red 주칠 on the shovel's spade and the reel) with iridescent mother-of-pearl:
nacre bands on every grip and shaft, plum blossoms and a plum branch inlaid in the larger lacquer faces (printed twice
as fine as the model, like the toy sticker), thin gold trims, a gold pommel with a blue knot and a red tassel (매듭),
heads of pale celadon jade or bright steel set in gold. The nacre is one texture with a pastel pink-cream-mint-sky-lilac
sheen; the inlays take their pixel colours from the same sheen, so every blossom shimmers across its petals.
"""

import math

from PIL import Image

import economy_models  # noqa: F401  (registers the shared surfaces)
from economy_machines import MModel, Mat, cyl
from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.color import oklch_to_rgb
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp, ramp
from models.toys import C, GRIP_Y, _arm, _held, round_y, corners  # noqa: F401


# ================================================================================================
# Ramps and materials
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("nj_black", 275, 0.016, 0.33, 0.26, 5, False)    # black lacquer: blue-grey reflections
_ramp("nj_shu", 24, 0.150, 0.40, 0.28, 5, False)       # 주칠: deep vermilion-red lacquer
_ramp("nj_jade", 162, 0.060, 0.74, 0.30, 5, False)     # pale polished celadon jade
_ramp("nj_gold", 85, 0.125, 0.70, 0.36, 5, True)       # brass / gold trims
_ramp("nj_steel", 230, 0.014, 0.76, 0.34, 5, False)    # bright polished steel
_ramp("nj_knot_red", 22, 0.170, 0.50, 0.26, 4, False)  # the tassel
_ramp("nj_knot_blue", 262, 0.130, 0.44, 0.26, 4, False)


def _gloss(name, base):
    return Mat(name, "metal", base, bevel=(0, -1), noise=0)


def _flat(name, base):
    return Mat(name, "plain", base, bevel=(0, -1), noise=0)


BLACK = _gloss("nj_black", 1)
BLACK_FLAT = _flat("nj_black", 1)
SHU = _gloss("nj_shu", 2)
SHU_FLAT = _flat("nj_shu", 2)
GOLD = _gloss("nj_gold", 3)
GOLD_DARK = _flat("nj_gold", 2)
JADE = _gloss("nj_jade", 2)
JADE_LIGHT = _flat("nj_jade", 3)
STEEL = _gloss("nj_steel", 2)
STEEL_EDGE = _flat("nj_steel", 4)
KNOT_RED = _flat("nj_knot_red", 2)
KNOT_RED_DARK = _flat("nj_knot_red", 1)
KNOT_BLUE = _flat("nj_knot_blue", 2)
LINE = Mat("feather", "plain", 2, bevel=None, noise=0)


# ================================================================================================
# Surfaces: the nacre sheen and the inlays
# ================================================================================================

def _sheen(x, y, lift=0.0):
    """Mother-of-pearl at texel (x, y): a pastel hue that drifts pink -> cream -> mint -> sky -> lilac along a wavy diagonal."""
    t = ((x * 0.55 + y * 0.8) / 16 + 0.12 * math.sin(x * 0.9 + y * 0.4)) % 1.0
    hue = 350 + 310 * t
    L = 0.86 + 0.035 * math.sin(x * 1.7 + y * 2.3) + lift
    c = 0.060 if lift <= 0 else 0.025
    return (*oklch_to_rgb(min(0.97, L), c, hue % 360), 255)


def _nacre():
    """The nacre band texture: the sheen, a few white flecks and darker ripples."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            k = (x * 7 + y * 13) % 17
            px[x, y] = _sheen(x, y, 0.09 if k == 0 else -0.07 if k == 9 else 0.0)
    return im


# inlay pictures: '.' lacquer, 'n' nacre, 'w' bright nacre, 'g' gold, 'b' the plum branch (dark gold)
PLUM = ("...ww....",
        "...nnn...",
        "ww.nnn.ww",
        "nnnngnnnn",
        ".nnnnnnn.",
        "..nn.nn..",
        ".nnn.nnn.",
        ".nn...nn.",
        ".........")

BRANCH = ("............",
          "........ww..",
          ".......nnnn.",
          "...b..nngnn.",
          "....b..nnnn.",
          ".....b..nn..",
          ".ww...b.....",
          "nnnn...bbb..",
          "ngnn...b..w.",
          "nnnn..b..nnn",
          ".nn..b...ngn",
          "....b.....n.",
          "...b........",
          "..b.........")

STRIP = ("................",
         ".g.g.g.g.g.g.g.g",
         "...n...n...n...n",
         "..nwn.nwn.nwn.nw",
         "...n...n...n...n",
         ".g.g.g.g.g.g.g.g",
         "................")


def _inlay(rows, bg):
    """A picture of rows centred on a 16 x 16 lacquer of ramp bg (glossy: a lighter corner at the top left)."""
    def make():
        name, i = bg
        im = Image.new("RGBA", (16, 16), ramp(name, i))
        px = im.load()
        for y in range(16):
            for x in range(16):
                if x + y < 5:
                    px[x, y] = ramp(name, i + 1)
        h, w = len(rows), len(rows[0])
        oy, ox = (16 - h) // 2, (16 - w) // 2
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                X, Y = ox + x, oy + y
                if ch == "n":
                    px[X, Y] = _sheen(X, Y)
                elif ch == "w":
                    px[X, Y] = _sheen(X, Y, 0.09)
                elif ch == "g":
                    px[X, Y] = ramp("nj_gold", 3)
                elif ch == "b":
                    px[X, Y] = ramp("nj_gold", 1)
        return im
    return make


def _dots(bg):
    """Lacquer strewn with nacre flecks: the gourd float."""
    def make():
        name, i = bg
        im = Image.new("RGBA", (16, 16), ramp(name, i))
        px = im.load()
        for y in range(16):
            for x in range(16):
                if (x + 2 * y) % 5 == 0 and (x - y) % 3 == 0:
                    px[x, y] = _sheen(x, y, 0.05)
        return im
    return make


def _reel_cap():
    """The reel's round face: red lacquer, a gold rim, a plum blossom in nacre."""
    im = Image.new("RGBA", (16, 16), ramp("nj_shu", 2))
    px = im.load()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r > 6.3:
                px[x, y] = ramp("nj_gold", 3)
    big = _inlay(PLUM, ("nj_shu", 2))()
    for y in range(3, 13):
        for x in range(3, 13):
            if math.hypot(x - 7.5, y - 7.5) < 5.6:
                px[x, y] = big.getpixel((x, y))
    return im


T.SURFACES["nj_nacre"] = _nacre
T.SURFACES["nj_plum_black"] = _inlay(PLUM, ("nj_black", 1))
T.SURFACES["nj_plum_red"] = _inlay(PLUM, ("nj_shu", 2))
T.SURFACES["nj_branch_black"] = _inlay(BRANCH, ("nj_black", 1))
T.SURFACES["nj_strip_black"] = _inlay(STRIP, ("nj_black", 1))
T.SURFACES["nj_dots_black"] = _dots(("nj_black", 1))
T.SURFACES["nj_reel_cap"] = _reel_cap

NACRE = "nj_nacre"


def _fine_uv(w, h, k=2.0):
    """The texture's middle, k texels a model unit (at most the whole texture)."""
    k = min(k, 16 / w, 16 / h)
    u0, v0 = 8 - w * k / 2, 8 - h * k / 2
    return [round(u0, 4), round(v0, 4), round(u0 + w * k, 4), round(v0 + h * k, 4)]


def inlaid(m, name, frm, to, mat, tex, faces=("north", "south"), k=2.0):
    """A lacquer box with an inlay picture on the given faces (north / south: x by y; east / west: z by y)."""
    w = to[0] - frm[0]
    d = to[2] - frm[2]
    h = to[1] - frm[1]
    f, uv = {}, {}
    for face in faces:
        f[face] = tex
        uv[face] = _fine_uv(w if face in ("north", "south") else d, h, k)
    m.box(name, frm, to, mat, faces=f, uv=uv)


# ================================================================================================
# What every piece shares
# ================================================================================================

def _pommel(m):
    """A gold cap with a ring under it, a blue knot (매듭) and a red tassel hanging from it."""
    round_y(m, "pommel", 6.5, 9.5, 6.5, 9.5, -2.25, -1.25, GOLD, 0.75)
    m.box("pommel_ring", (7.25, -3, 7.25), (8.75, -2.25, 8.75), GOLD_DARK)
    m.group("tassel")
    m.box("knot", (6.75, -4.25, 7.25), (9.25, -3, 8.75), KNOT_BLUE)
    m.box("knot", (7.25, -4.75, 7.5), (8.75, -4.25, 8.5), KNOT_BLUE)
    m.box("tassel_cap", (7.25, -5.25, 7.25), (8.75, -4.75, 8.75), GOLD_DARK)
    m.box("tassel", (7.125, -7.25, 7.125), (8.875, -5.25, 8.875), KNOT_RED)
    m.box("tassel", (6.875, -8, 6.875), (9.125, -7.25, 9.125), KNOT_RED_DARK)


def handle(m):
    """A gold pommel with its tassel, a black lacquer grip with three nacre bands, a gold collar (y -8 .. 6.5)."""
    m.group("handle")
    _pommel(m)
    m.group("handle")
    ys = (-1.25, -0.25, 0.25, 2.0, 2.5, 4.25, 4.75, 5.25)
    for i in range(len(ys) - 1):
        y0, y1 = ys[i], ys[i + 1]
        if i % 2:
            round_y(m, "grip_band", 6.625, 9.375, 6.625, 9.375, y0, y1, NACRE, 0.5)
        else:
            round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, y0, y1, BLACK_FLAT, 0.5)
    round_y(m, "collar", 6.25, 9.75, 6.25, 9.75, 5.25, 6.5, GOLD, 0.75)


def shaft(m, top, bands=()):
    """Black lacquer above the collar up to y = top, nacre bands (0.5 high) at the given heights."""
    m.group("shaft")
    y = 6.5
    for b in sorted(bands) + [top]:
        if b > y:
            round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, y, b, BLACK, 0.5)
        if b < top:
            round_y(m, "shaft_band", 6.75, 9.25, 6.75, 9.25, b, b + 0.5, NACRE, 0.5)
            y = b + 0.5


# ================================================================================================
# The tools
# ================================================================================================

def najeon_sword():
    """자개 검: a bright steel blade with a jade ridge, a gold habaki, a lacquer guard with nacre and gold ends, a plum
    blossom inlaid in the guard's middle."""
    m = MModel("najeon_sword")
    handle(m)
    m.group("guard")
    inlaid(m, "guard", (3.25, 6.5, 6.75), (12.75, 8.5, 9.25), BLACK, "nj_strip_black")
    for x0, x1 in ((1.75, 3.25), (12.75, 14.25)):
        m.box("guard_end", (x0, 6.25, 6.5), (x1, 8.75, 9.5), GOLD)
    inlaid(m, "guard_plate", (5.75, 5.75, 6.25), (10.25, 10.25, 9.75), BLACK, "nj_plum_black")
    for x0, x1 in ((5.5, 5.75), (10.25, 10.5)):
        m.box("guard_plate_rim", (x0, 5.75, 6.25), (x1, 10.25, 9.75), GOLD_DARK)
    m.group("blade")
    m.box("habaki", (6, 10.25, 7), (10, 11.25, 9), GOLD)
    m.box("blade", (5.5, 11.25, 7.25), (10.5, 23.5, 8.75), STEEL)
    m.box("blade_edge", (4.75, 11.75, 7.625), (5.5, 22.75, 8.375), STEEL_EDGE)
    m.box("blade_edge", (10.5, 11.75, 7.625), (11.25, 22.75, 8.375), STEEL_EDGE)
    m.box("blade_ridge", (7.25, 11.25, 7), (8.75, 22.25, 9), JADE)
    m.box("blade_tip", (6.25, 23.5, 7.375), (9.75, 25, 8.625), STEEL)
    m.box("blade_tip", (7, 25, 7.5), (9, 26.25, 8.5), STEEL_EDGE)
    m.box("blade_tip", (7.5, 26.25, 7.625), (8.5, 27, 8.375), STEEL_EDGE)
    return _held(m, "sword")


def najeon_pickaxe():
    """자개 곡괭이: a lacquer head with a plum blossom and gold caps, steel arms drooping in two bends from gold sockets."""
    m = MModel("najeon_pickaxe")
    handle(m)
    shaft(m, 15.25, bands=(10.5,))
    m.group("head")
    m.box("head_band", (4.75, 15.25, 6.75), (11.25, 16, 9.25), GOLD)
    inlaid(m, "head", (4.5, 16, 6.5), (11.5, 20.75, 9.5), BLACK, "nj_plum_black")
    m.box("head_cap", (5.25, 20.75, 6.75), (10.75, 21.5, 9.25), GOLD)
    for side in (1, -1):
        x0 = C + side * 3.5
        m.box("socket", (min(x0, x0 + side * 0.75), 16.5, 6.75), (max(x0, x0 + side * 0.75), 20.25, 9.25), GOLD)
        p = (C + side * 4.25, 18.25)
        p = _arm(m, "arm_a", side, p, 4.0, 1.625, 7, 9, 22.5, STEEL)
        p = _arm(m, "arm_b", side, p, 4.0, 1.25, 7.25, 8.75, 45, STEEL)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 0.875, 7.5), (px + 2.25, py + 0.875, 8.5)
        else:
            frm, to = (px - 2.25, py - 0.875, 7.5), (px - 0.5, py + 0.875, 8.5)
        m.box("arm_tip", frm, to, STEEL_EDGE, rotation=("z", -45 * side, (px, py, C)))
    return _held(m, "pickaxe")


def najeon_shovel():
    """자개 삽: a red lacquer (주칠) spade with a gold rim and a nacre plum blossom on both faces, a steel point."""
    m = MModel("najeon_shovel")
    handle(m)
    shaft(m, 15.5, bands=(10.5,))
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17, GOLD, 0.75)
    rows = ((17, 18, 3.5), (18, 23.5, 4.5), (23.5, 25.5, 3.75), (25.5, 26.75, 2.75))
    for y0, y1, hw in rows:
        if hw == 4.5:
            inlaid(m, "blade", (C - hw, y0, 7), (C + hw, y1, 9), SHU, "nj_plum_red")
        else:
            m.box("blade", (C - hw, y0, 7), (C + hw, y1, 9), SHU_FLAT)
        for z0, z1 in ((9, 9.3), (6.7, 7)):
            m.box("rim", (C - hw, y0, z0), (C - hw + 0.75, y1, z1), GOLD_DARK)
            m.box("rim", (C + hw - 0.75, y0, z0), (C + hw, y1, z1), GOLD_DARK)
    m.box("point", (6.25, 26.75, 7.2), (9.75, 28, 8.8), STEEL)
    m.box("point", (7.25, 28, 7.4), (8.75, 28.75, 8.6), STEEL_EDGE)
    return _held(m, "shovel")


def najeon_axe():
    """자개 도끼: a lacquer head with a nacre plum branch on both cheeks, a gold seam, a steel blade, a gold poll."""
    m = MModel("najeon_axe")
    handle(m)
    shaft(m, 21.5, bands=(10.5,))
    m.group("head")
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 21.5, 22.5, GOLD, 0.75)
    inlaid(m, "head", (4.5, 14, 6.5), (10.5, 20.75, 9.5), BLACK, "nj_branch_black")
    m.box("seam", (3.75, 13.5, 6.75), (4.5, 21.25, 9.25), GOLD)
    m.box("blade", (2, 13, 7), (3.75, 21.75, 9), STEEL)
    m.box("blade", (0, 12, 7.25), (2, 22.75, 8.75), STEEL)
    m.box("blade_edge", (-1.25, 12.5, 7.5), (0, 22.25, 8.5), STEEL_EDGE)
    m.box("blade_edge", (-0.75, 11.5, 7.5), (0, 12.5, 8.5), STEEL_EDGE)
    m.box("blade_edge", (-0.75, 22.25, 7.5), (0, 23.25, 8.5), STEEL_EDGE)
    m.box("poll", (10.5, 15, 6.75), (12, 19.75, 9.25), GOLD)
    return _held(m, "axe")


def najeon_hoe():
    """자개 괭이: a lacquer bar with a nacre strip, a pale jade plate hanging from its end on a gold socket, a gold edge."""
    m = MModel("najeon_hoe")
    handle(m)
    shaft(m, 19.75, bands=(10.5, 15))
    m.group("head")
    inlaid(m, "bar", (2.75, 19.75, 6.75), (9.75, 22.25, 9.25), BLACK, "nj_strip_black")
    round_y(m, "bar_cap", 6.5, 9.5, 6.5, 9.5, 22.25, 23, GOLD, 0.75)
    m.box("bar_end", (2, 19.5, 6.5), (2.75, 22.5, 9.5), GOLD)
    m.box("plate", (0.25, 14.75, 5), (2, 19.5, 11), JADE)
    m.box("plate_top", (0, 19.5, 5.25), (2, 22.75, 10.75), JADE)
    m.box("plate_edge", (0.5, 13.75, 5.25), (1.75, 14.75, 10.75), GOLD_DARK)
    return _held(m, "hoe")


def _gourd(m, rot, cy):
    """The little lacquered gourd float on the line: a black bulb strewn with nacre, a gold waist, a small top, a red knot."""
    m.box("gourd", (6.75, cy - 3, 6.75), (9.25, cy - 2.5, 9.25), "nj_dots_black", rotation=rot)
    m.box("gourd", (6.25, cy - 2.5, 6.25), (9.75, cy, 9.75), "nj_dots_black", rotation=rot)
    m.box("gourd_waist", (7.25, cy, 7.25), (8.75, cy + 0.5, 8.75), GOLD, rotation=rot)
    m.box("gourd_top", (6.875, cy + 0.5, 6.875), (9.125, cy + 2, 9.125), "nj_dots_black", rotation=rot)
    m.box("gourd_knot", (7.5, cy + 2, 7.5), (8.5, cy + 2.75, 8.5), KNOT_RED, rotation=rot)


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9, 7.5, 7.25), (10, 9.5, 8.75), GOLD_DARK)
    cyl(m, "reel", "z", (12.75, 8.5), 6, 6.25, 9.75, SHU_FLAT, cap="nj_reel_cap")
    m.box("reel_hub", (12, 7.75, 6), (13.5, 9.25, 10), GOLD)
    m.box("crank", (12.25, 8.5, 9.75), (13.25, 11.25, 10.5), GOLD)
    m.box("crank_knob", (12, 10.5, 10.5), (13.5, 12, 11.75), JADE)
    m.group("rod")
    # black lacquered bamboo: three lengths, a node (nacre or gold) at each joint
    segs = ((6.5, 10, 2.75, BLACK), (10, 10.5, 3.0, NACRE), (10.5, 14, 2.75, BLACK), (14, 14.75, 2.75, GOLD),
            (14.75, 18.25, 2.25, BLACK), (18.25, 18.75, 2.5, NACRE), (18.75, 23, 2.25, BLACK), (23, 23.75, 2.25, GOLD),
            (23.75, 26.75, 1.75, BLACK), (26.75, 27.25, 2.0, NACRE), (27.25, 30.25, 1.75, BLACK))
    for y0, y1, w, mat in segs:
        h = w / 2
        if w >= 2.5:
            round_y(m, "rod", C - h, C + h, C - h, C + h, y0, y1, mat, 0.5)
        else:
            m.box("rod", (C - h, y0, C - h), (C + h, y1, C + h), mat)
    m.box("rod_tip", (7, 30.25, 7), (9, 31.5, 9), GOLD)
    m.box("rod_tip", (7.375, 31.5, 7.375), (8.625, 32, 8.625), GOLD_DARK)
    m.box("guide", (9.125, 16, 7.375), (10.5, 17.25, 8.625), GOLD_DARK)
    m.box("guide", (8.875, 25, 7.375), (10.25, 26.25, 8.625), GOLD_DARK)
    if not cast:
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 20, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        _gourd(m, rot, 17.25)
    return _held(m, "rod")


def najeon_fishing_rod():
    """자개 낚싯대: black lacquered bamboo with nacre and gold nodes, a gold tip, a red lacquer reel, a gourd float."""
    return _rod("najeon_fishing_rod", False)


def najeon_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("najeon_fishing_rod_cast", True)
    m.display = najeon_fishing_rod().display
    return m


BOBBER_WATERLINE = 4.0        # the float's gold band: where the water's surface is (model y)


def najeon_bobber():
    """자개 찌: a lacquered gourd float for the item display that rides the fishing hook. Red lacquer under its gold
    waterline band, black lacquer strewn with nacre above, a gold waist, a small top bulb, a gold stem and a red knot.
    Stands on y = 0 about the axis x = 8, z = 8; the water's surface is at BOBBER_WATERLINE."""
    m = MModel("najeon_bobber")
    m.group("float")
    dots = "nj_dots_black"
    cyl(m, "weight", "y", (C, C), 2, 0, 1, GOLD_DARK, cap=GOLD_DARK)
    cyl(m, "belly", "y", (C, C), 4, 1, 2, SHU_FLAT, cap=SHU_FLAT)
    cyl(m, "belly", "y", (C, C), 6, 2, 3.5, SHU_FLAT, cap=SHU_FLAT)
    cyl(m, "band", "y", (C, C), 6, 3.5, 4.5, GOLD, cap=GOLD)
    cyl(m, "bulb", "y", (C, C), 6, 4.5, 6.5, dots, cap=dots)
    cyl(m, "bulb", "y", (C, C), 4, 6.5, 7.25, dots, cap=dots)
    cyl(m, "waist", "y", (C, C), 2, 7.25, 8, GOLD, cap=GOLD)
    cyl(m, "top", "y", (C, C), 4, 8, 10, dots, cap=dots)
    m.box("stem", (7.25, 10, 7.25), (8.75, 10.75, 8.75), GOLD)
    m.box("knot", (7.5, 10.75, 7.625), (8.5, 11.75, 8.375), KNOT_RED)
    m.display = presets.furniture(gui_scale=1.3, gui_translation=(0, 3.25, 0))
    m.kind = "bobber"
    return m


ALL = {f.__name__: f for f in (najeon_sword, najeon_pickaxe, najeon_shovel, najeon_axe, najeon_hoe, najeon_fishing_rod,
                               najeon_fishing_rod_cast, najeon_bobber)}

ROWS = [("najeon_sword", "자개 검"), ("najeon_pickaxe", "자개 곡괭이"), ("najeon_shovel", "자개 삽"), ("najeon_axe", "자개 도끼"),
        ("najeon_hoe", "자개 괭이"), ("najeon_fishing_rod", "자개 낚싯대"), ("najeon_fishing_rod_cast", "낚싯대 (던진 뒤)"),
        ("najeon_bobber", "자개 찌 (찌에 태우는 모델)")]

TITLE = "자개 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("najeon_sword", "najeon_pickaxe", "najeon_shovel", "najeon_axe", "najeon_hoe",
                                 "najeon_bobber")}
    out["najeon_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/najeon_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/najeon_fishing_rod_cast"},
    }}
    return out
