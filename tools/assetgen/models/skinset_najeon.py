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
from economy_machines import MModel, Mat, bands, cyl
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
    L = 0.80 + 0.04 * math.sin(x * 1.7 + y * 2.3) + lift
    c = 0.095 if lift <= 0 else 0.045
    return (*oklch_to_rgb(min(0.97, L), c, hue % 360), 255)


def _nacre():
    """The nacre band texture: the sheen, a few white flecks and darker ripples."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            k = (x * 7 + y * 13) % 17
            px[x, y] = _sheen(x, y, 0.12 if k == 0 else -0.06 if k == 9 else 0.0)
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
                    px[X, Y] = _sheen(X, Y, 0.12)
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

# ---- the redesign's pictures ------------------------------------------------------------------

BRANCH_TALL = ("..ww....",
               ".nnnn...",
               ".ngnn...",
               ".nnnn.b.",
               "..nn.b..",
               "....b...",
               "...b..ww",
               "..b..nnn",
               "..b..ngn",
               "...b.nnn",
               "....b.n.",
               ".ww..b..",
               "nnnn..b.",
               "ngnn..b.",
               ".nn..b..")


def _line(px, a, b, col):
    (x0, y0), (x1, y1) = a, b
    n = max(abs(x1 - x0), abs(y1 - y0))
    for i in range(n + 1):
        px[round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n)] = col


def _sain():
    """사인검's blade face: polished steel, a darker fuller down the middle (texels 6 .. 9 = the blade, x 6 .. 10) and in it
    a constellation inlaid in gold: stars joined by fine lines, stretched over the whole blade."""
    im = Image.new("RGBA", (16, 16), ramp("nj_steel", 3))
    px = im.load()
    for y in range(16):
        for x in range(6, 10):
            px[x, y] = ramp("nj_steel", 2)
        px[6, y] = ramp("nj_steel", 3)
    stars = [(7, 1), (8, 3), (7, 5), (8, 7), (8, 9), (7, 11), (8, 13), (7, 15)]
    for a, b in zip(stars, stars[1:]):
        _line(px, a, b, ramp("nj_gold", 1))
    for s in stars:
        px[s] = ramp("nj_gold", 4)
    return im


def _rosette(bg):
    """A round medallion: lacquer of ramp bg, a gold rim, a nacre plum blossom (the reel's face, the hairpins' boss)."""
    def make():
        name, i = bg
        im = Image.new("RGBA", (16, 16), ramp(name, i))
        px = im.load()
        for y in range(16):
            for x in range(16):
                if math.hypot(x - 7.5, y - 7.5) > 6.3:
                    px[x, y] = ramp("nj_gold", 3)
        big = _inlay(PLUM, bg)()
        for y in range(3, 13):
            for x in range(3, 13):
                if math.hypot(x - 7.5, y - 7.5) < 5.6:
                    px[x, y] = big.getpixel((x, y))
        return im
    return make


# the fan: a half disc of radius FAN_R left of its pivot (x <= FAN_C[0]); its picture covers that box
FAN_C = (6.25, 17.5)
FAN_R = 7.0
FAN_BOX = (FAN_C[0] - FAN_R, FAN_C[1] - FAN_R, FAN_C[0], FAN_C[1] + FAN_R)    # x0, y0, x1, y1
BLOSSOMS = ((1.9, 21.2), (2.6, 14.2), (3.9, 23.0))
TWIG = ((4.6, 18.6), (1.9, 21.2), (3.0, 20.1), (3.9, 23.0), (3.0, 20.1), (4.6, 18.6), (2.6, 14.2))


def _seg_dist(p, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(p[0] - ax - t * dx, p[1] - ay - t * dy)


def _fan():
    """The opened 합죽선: hanji folds alternating light and shade between the ribs, a gold rim, the ribs' lacquer at the
    pivot with nacre, a red plum branch painted across it. Texel (u, v) <- model (x, y) over FAN_BOX."""
    x0, y0, x1, y1 = FAN_BOX
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for v in range(16):
        for u in range(16):
            x = x0 + (u + 0.5) / 16 * (x1 - x0)
            y = y1 - (v + 0.5) / 16 * (y1 - y0)
            r = math.hypot(x - FAN_C[0], y - FAN_C[1])
            ang = math.degrees(math.atan2(y - FAN_C[1], FAN_C[0] - x))      # -90 .. 90
            fold = int((ang + 90) // 15) % 2
            col = ramp("paper", 2) if fold else ramp("nj_jade", 3)
            if (ang + 90) % 15 < 2.2 and r > 2.4:
                col = ramp("paper", 0)
            if r > FAN_R - 0.9:
                col = ramp("nj_gold", 3)
            elif r < 2.4:
                col = _sheen(u, v) if int((ang + 90) // 15) % 2 else ramp("nj_black", 1)
            else:
                if min(_seg_dist((x, y), a, b) for a, b in zip(TWIG, TWIG[1:])) < 0.3:
                    col = ramp("nj_black", 0)
                for bx, by in BLOSSOMS:
                    d = math.hypot(x - bx, y - by)
                    if d < 1.0:
                        col = ramp("nj_gold", 4) if d < 0.35 else ramp("nj_knot_red", 3 if d < 0.65 else 2)
            px[u, v] = col
    return im


def _flute():
    """퉁소's front: black lacquer, a column of finger holes ringed in gold in its upper part (texels 7 .. 8 = x 7 .. 9)."""
    im = Image.new("RGBA", (16, 16), ramp("nj_black", 1))
    px = im.load()
    for y in range(16):
        px[6, y] = ramp("nj_black", 2)
    for y in (1, 4, 7, 10):
        for x in (7, 8):
            px[x, y] = ramp("nj_gold", 3)
            px[x, y + 1] = ramp("roe", 0)
    return im


T.SURFACES["nj_branch_tall_red"] = _inlay(BRANCH_TALL, ("nj_shu", 2))
T.SURFACES["nj_sain"] = _sain
T.SURFACES["nj_rosette_black"] = _rosette(("nj_black", 1))
T.SURFACES["nj_fan"] = _fan
T.SURFACES["nj_flute"] = _flute

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
    """A gold pommel with its tassel, a black lacquer grip with two nacre bands, a gold collar (y -8 .. 6.5)."""
    m.group("handle")
    _pommel(m)
    m.group("handle")
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.25, 5.25, BLACK_FLAT, 0.5)
    for y in (0.5, 3.0):
        m.box("grip_band", (6.5, y, 6.5), (9.5, y + 0.75, 9.5), NACRE)
    round_y(m, "collar", 6.25, 9.75, 6.25, 9.75, 5.25, 6.5, GOLD, 0.75)


def shaft(m, top, bands=()):
    """Black lacquer above the collar up to y = top, nacre bands (0.75 high) round it at the given heights."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, BLACK, 0.5)
    for b in bands:
        m.box("shaft_band", (6.625, b, 6.625), (9.375, b + 0.75, 9.375), NACRE)


# ================================================================================================
# The tools
# ================================================================================================

def najeon_sword():
    """사인검 (자개 장검): a long straight double-edged Joseon ceremonial sword. Polished steel with a gold constellation
    inlaid down its fuller (the 28 lunar mansions of a real 사인검), a small round gold guard on a lacquer plate, a gold
    habaki; the series' lacquer grip with nacre bands, the gold pommel, the blue knot and the red tassel. The blade is
    1.3 times the toy sword's (23.75 units from the habaki to the tip)."""
    m = MModel("najeon_sword")
    handle(m)
    m.group("guard")
    round_y(m, "guard", 4.75, 11.25, 5.5, 10.5, 6.5, 7.25, GOLD, 1)
    round_y(m, "guard_plate", 5.5, 10.5, 6.25, 9.75, 7.25, 7.75, BLACK_FLAT, 0.75)
    m.box("habaki", (6.25, 7.75, 7), (9.75, 8.75, 9), GOLD)
    m.group("blade")
    m.box("blade", (6, 8.75, 7.25), (10, 30, 8.75), STEEL, faces={"north": "nj_sain", "south": "nj_sain"})
    m.box("blade_edge", (5.375, 9.25, 7.625), (6, 29.5, 8.375), STEEL_EDGE)
    m.box("blade_edge", (10, 9.25, 7.625), (10.625, 29.5, 8.375), STEEL_EDGE)
    m.box("blade_tip", (6.5, 30, 7.375), (9.5, 31, 8.625), STEEL)
    m.box("blade_tip", (7.25, 31, 7.5), (8.75, 32, 8.5), STEEL_EDGE)
    return _held(m, "sword")


def najeon_pickaxe():
    """비녀 곡괭이: two jade hairpins (비녀) for arms, drooping in two bends to gold points, their gold heads meeting in a round
    lacquer boss with a nacre plum blossom and a gold rim."""
    m = MModel("najeon_pickaxe")
    handle(m)
    shaft(m, 16, bands=(10.5,))
    m.group("head")
    for side in (1, -1):
        x0 = C + side * 2.5
        m.box("pin_head", (min(x0, x0 + side * 2), 16.75, 6.875), (max(x0, x0 + side * 2), 20.25, 9.125), GOLD)
        m.box("pin_bead", (min(x0 + side * 2, x0 + side * 2.75), 17.5, 7.25), (max(x0 + side * 2, x0 + side * 2.75), 19.5, 8.75),
              NACRE)
        p = (C + side * 5.25, 18.5)
        p = _arm(m, "pin", side, p, 4.5, 1.0, 7.25, 8.75, 22.5, JADE)
        p = _arm(m, "pin", side, p, 4.0, 0.75, 7.375, 8.625, 45, JADE)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 0.5, 7.5), (px + 2.25, py + 0.5, 8.5)
        else:
            frm, to = (px - 2.25, py - 0.5, 7.5), (px - 0.5, py + 0.5, 8.5)
        m.box("pin_point", frm, to, GOLD, rotation=("z", -45 * side, (px, py, C)))
    cyl(m, "boss", "z", (C, 18.5), 6, 6.5, 9.5, GOLD_DARK, cap="nj_rosette_black")
    return _held(m, "pickaxe")


def najeon_shovel():
    """노 삽: a lacquered boat oar: a long slim red (주칠) paddle with a nacre plum branch on both faces, a gold rim down its
    front, a gold-capped tip, a gold binding where the loom meets the blade."""
    m = MModel("najeon_shovel")
    handle(m)
    shaft(m, 14.5, bands=(10.5,))
    m.group("blade")
    round_y(m, "binding", 6.5, 9.5, 6.5, 9.5, 14.5, 15.75, GOLD, 0.75)
    rows = ((15.75, 16.75, 1.75), (16.75, 17.75, 2.5), (17.75, 28.25, 3), (28.25, 29.5, 2.5))
    for y0, y1, hw in rows:
        if hw == 3:
            inlaid(m, "blade", (C - hw, y0, 7.125), (C + hw, y1, 8.875), SHU, "nj_branch_tall_red")
        else:
            m.box("blade", (C - hw, y0, 7.125), (C + hw, y1, 8.875), SHU_FLAT)
        if hw > 1.75:
            m.box("rim", (C - hw, y0, 8.875), (C - hw + 0.625, y1, 9.125), GOLD_DARK)
            m.box("rim", (C + hw - 0.625, y0, 8.875), (C + hw, y1, 9.125), GOLD_DARK)
    m.box("tip", (6, 29.5, 7.25), (10, 30.25, 8.75), GOLD)
    m.box("tip", (7, 30.25, 7.375), (9, 30.75, 8.625), GOLD_DARK)
    return _held(m, "shovel")


def najeon_axe():
    """합죽선 도끼: an opened folding fan for a blade: a half disc of hanji folds with a red plum branch painted on it and
    a gold rim, its lacquered guard stick along the shaft, a gold rivet at the pivot."""
    m = MModel("najeon_axe")
    handle(m)
    shaft(m, 25.5, bands=(10.5,))
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 25.5, 26.25, GOLD, 0.75)
    m.group("fan")
    x0, y0, x1, y1 = FAN_BOX
    U = lambda x: round(min(16, max(0, (x - x0) / (x1 - x0) * 16)), 4)   # the leaves run under the guard stick: last column there
    V = lambda y: round((y1 - y) / (y1 - y0) * 16, 4)
    cx, cy = FAN_C
    for a, b, hw in bands(2 * FAN_R):
        fx, ty, by = cx - hw, cy + b, cy + a
        uv = {"south": [U(fx), V(ty), U(cx + 0.375), V(by)], "north": [U(cx + 0.375), V(ty), U(fx), V(by)]}
        m.box("leaf", (fx, by, 7.625), (cx + 0.375, ty, 8.375), Mat("paper", "plain", 1, bevel=None, noise=0),
              faces={"south": "nj_fan", "north": "nj_fan"}, uv=uv)
    m.box("guard_stick", (5.875, cy - FAN_R - 0.25, 7.25), (6.875, cy + FAN_R + 0.25, 8.75), "nj_dots_black")
    cyl(m, "rivet", "z", (6.375, cy), 2, 6.75, 9.25, GOLD, cap=GOLD)
    return _held(m, "axe")


def najeon_hoe():
    """붓 괭이: a calligraphy brush laid sideways on top of the shaft (the vanilla hoe's straight ㄱ): a black lacquer barrel
    with nacre bands, a gold mount on the shaft, a gold ferrule, a cream hair tuft dipped in ink, ending flat.
    The barrel is red lacquer (주칠): black would vanish against the shaft."""
    m = MModel("najeon_hoe")
    handle(m)
    shaft(m, 20.25, bands=(10.5, 15.5))
    m.group("brush")
    cy = (21.25, C)
    for x0, x1, mat in ((2.5, 3.75, SHU_FLAT), (3.75, 4.25, NACRE), (4.25, 5.5, SHU_FLAT), (5.5, 6, NACRE), (6, 7, SHU_FLAT),
                        (9, 9.75, SHU_FLAT)):
        cyl(m, "barrel", "x", cy, 2.5, x0, x1, mat)
    cyl(m, "mount", "x", cy, 3, 7, 9, GOLD, cap=GOLD)
    cyl(m, "end_cap", "x", cy, 2, 9.75, 10.5, GOLD, cap=GOLD)
    cyl(m, "ferrule", "x", cy, 3, 1.75, 2.5, GOLD, cap=GOLD)
    hair = Mat("rice", "plain", 1, bevel=(0, -1), noise=0)
    cyl(m, "hair", "x", cy, 3.5, -0.25, 1.75, hair, cap=hair)
    cyl(m, "hair", "x", cy, 3, -1.25, -0.25, hair, cap=hair)
    cyl(m, "ink", "x", cy, 2.5, -2.25, -1.25, BLACK_FLAT, cap=BLACK_FLAT)
    cyl(m, "ink", "x", cy, 1.5, -2.75, -2.25, BLACK_FLAT, cap=BLACK_FLAT)
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
    m.box("reel_foot", (9.375, 7.5, 7.25), (10, 9.5, 8.75), GOLD_DARK)
    cyl(m, "reel", "z", (12.75, 8.5), 6, 6.25, 9.75, SHU_FLAT, cap="nj_reel_cap")
    m.box("reel_hub", (12, 7.75, 6), (13.5, 9.25, 10), GOLD)
    m.box("crank", (12.25, 8.5, 9.75), (13.25, 11.25, 10.5), GOLD)
    m.box("crank_knob", (12, 10.5, 10.5), (13.5, 12, 11.75), JADE)
    m.group("flute")
    # 퉁소: a fat black lacquered bamboo flute is the rod, finger holes ringed in gold down its front, nacre nodes, a
    # gold mouthpiece with its notch; a thin brass tip carries the line on
    m.box("flute", (6.625, 6.5, 6.625), (9.375, 24.5, 9.375), BLACK_FLAT, faces={"north": "nj_flute", "south": "nj_flute"})
    for y in (9.5, 13.75):
        m.box("node", (6.375, y, 6.375), (9.625, y + 0.75, 9.625), NACRE)
    m.box("mouth", (6.375, 24.5, 6.375), (9.625, 25.5, 9.625), GOLD, faces={"up": GOLD_DARK})
    m.box("notch", (7.5, 25.5, 6.375), (8.5, 25.75, 7.25), GOLD_DARK)
    m.box("tip", (7.375, 25.5, 7.375), (8.625, 30.75, 8.625), GOLD)
    m.box("tip", (7.25, 30.75, 7.25), (8.75, 31.5, 8.75), GOLD_DARK)
    m.box("guide", (9.375, 18.5, 7.5), (10.5, 19.5, 8.5), GOLD_DARK)
    m.box("guide", (8.625, 27.5, 7.625), (9.75, 28.5, 8.375), GOLD_DARK)
    m.group("tassel")
    m.box("flute_knot", (9.375, 22.5, 7.5), (10.375, 23.5, 8.5), KNOT_BLUE)
    m.box("flute_tassel", (9.625, 20.5, 7.625), (10.375, 22.5, 8.375), KNOT_RED)
    if not cast:
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 20, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        _gourd(m, rot, 17.25)
    return _held(m, "rod")


def najeon_fishing_rod():
    """퉁소 낚싯대: a black lacquered bamboo flute (퉁소) for a rod, with gold-ringed finger holes, nacre nodes, a little
    tassel, a red lacquer reel and a gourd float on the line."""
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

ROWS = [("najeon_sword", "자개 사인검"), ("najeon_pickaxe", "자개 비녀 곡괭이"), ("najeon_shovel", "자개 노 삽"), ("najeon_axe", "자개 합죽선 도끼"),
        ("najeon_hoe", "자개 붓 괭이"), ("najeon_fishing_rod", "자개 퉁소 낚싯대"), ("najeon_fishing_rod_cast", "낚싯대 (던진 뒤)"),
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
