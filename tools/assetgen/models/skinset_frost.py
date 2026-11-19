"""
서리 수정 시리즈 (frost crystal series): a premium tool skin set, built on the toy series' frame (models/toys.py).

    frost_sword  frost_pickaxe  frost_shovel  frost_axe  frost_hoe  frost_fishing_rod (+ frost_fishing_rod_cast)  frost_bobber

Same frame, grip point, sizes and display transforms as the toys (toys._held): every model upright on the axis x = z = 8,
the hand closing at GRIP_Y. Only the looks are this set's own:

  ice crystal   blades and heads: pale cyan rims on the top / left, deeper blue rims on the bottom / right, a darker blue
                core inside each face and white glint streaks across it: ice that reads as see-through without being so
                (FrostPaint, painted per face at one texel a unit). Small shards jut out at 22.5 degree angles, icicles
                hang under the heads and guards.
  frost silver  the fittings: collars, sockets, caps, rings (a white glint in each face's top left corner).
  pale leather  the grip, wrapped in diagonal bands, under a white fur cuff and a snowflake collar (four points).
  emblem        a white snowflake on a deep blue plate, on every piece (the toys' sticker size and place).
"""

import math
import zlib

from PIL import Image

from economy_machines import MModel, Mat, cyl
from models.toys import C, GRIP_Y, STICKER, STICKER_UV, _arm, _held, round_y  # noqa: F401
from stsv_assets import presets
from stsv_assets import textures as T

S = 16


def _hex(h):
    return (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), 255)


ICE_RAMP = [_hex(h) for h in ("#173a80", "#2259ad", "#367fd2", "#5eaee9", "#90d5f6", "#c6effc", "#ffffff")]
FROST_RAMP = [_hex(h) for h in ("#6c83a2", "#8fa6c0", "#b6c9da", "#d8e5ef", "#f2f8fc", "#ffffff")]
LEATHER_RAMP = [_hex(h) for h in ("#93a9c1", "#b3c8da", "#d2e2ee", "#eef6fb")]
FUR_RAMP = [_hex(h) for h in ("#b7c5d3", "#d2dde8", "#eaf2f8", "#ffffff")]

SIDE_SHADE = {"north": 0, "south": 0, "up": 1, "down": -1, "east": -1, "west": 0}


class FrostPaint(Mat):
    """A material that paints each face with a function of its pixel size: fn(w, h, face, rnd) -> rows of colours."""

    def __init__(self, tag, fn, **opt):
        super().__init__("ice", "plain", 1, bevel=None, noise=0)
        self.tag, self.fn, self.popt = tag, fn, opt

    def __call__(self, **changes):     # cyl() asks for a copy without the bevel: there is none to take off
        return self

    def face(self, w, h, face, seed):
        pw, ph = max(1, min(S, math.ceil(w - 1e-6))), max(1, min(S, math.ceil(h - 1e-6)))
        import random
        rows = self.fn(pw, ph, face, random.Random(seed), **self.popt)
        im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        px = im.load()
        for y in range(ph):
            for x in range(pw):
                px[x, y] = rows[y][x]
        key = "fz_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, [0, 0, round(min(w, S), 4), round(min(h, S), 4)]


def _ice(w, h, face, rnd, lift=0, streaks=True):
    """Ice crystal: a bright rim on the top / left, a mid rim on the bottom / right, a deep core, white glint streaks."""
    s = SIDE_SHADE[face] + lift
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            d = min(x, y, w - 1 - x, h - 1 - y)
            if d == 0:
                i = 5 if (x == 0 or y == 0) else 3
            elif d == 1:
                i = 4
            else:
                i = 2
            if streaks and d >= 1 and min(w, h) >= 3:
                k = (x + y) % 9
                if k == 3:
                    i = 6
                elif k == 4:
                    i = max(i, 5)
            row.append(ICE_RAMP[max(0, min(6, i + s))])
        out.append(row)
    if w >= 4 and h >= 4:
        out[1][1] = ICE_RAMP[6]
    return out


def _frost(w, h, face, rnd, lift=0):
    """Frosted silver: a white glint in the top left corner, a light top / left edge and a darker bottom / right one."""
    s = SIDE_SHADE[face] + lift
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            i = 2
            if x + y < min(w, h) * 0.45:
                i = 4
            if (y == 0 and h > 1) or (x == 0 and w > 1):
                i += 1
            if (y == h - 1 and h > 1) or (x == w - 1 and w > 1):
                i -= 1
            row.append(FROST_RAMP[max(0, min(5, i + s))])
        out.append(row)
    return out


def _leather(w, h, face, rnd):
    """Pale leather wrapped round the grip: diagonal bands."""
    s = SIDE_SHADE[face]
    return [[LEATHER_RAMP[max(0, min(3, (1 if (x + y) % 3 == 0 else 2) + s))] for x in range(w)] for y in range(h)]


def _fur(w, h, face, rnd):
    """White fur: soft speckles, the top a shade lighter."""
    s = SIDE_SHADE[face]
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            r = rnd.random()
            i = 1 if r < 0.25 else 3 if r < 0.45 else 2
            if y == 0:
                i = min(3, i + 1)
            row.append(FUR_RAMP[max(0, min(3, i + s))])
        out.append(row)
    return out


ICE = FrostPaint("ice", _ice)
ICE_LIGHT = FrostPaint("ice_light", _ice, lift=1, streaks=False)
ICE_DEEP = FrostPaint("ice_deep", _ice, lift=-1, streaks=False)
FROST = FrostPaint("frost", _frost)
FROST_DIM = FrostPaint("frost_dim", _frost, lift=-1)
LEATHER = FrostPaint("leather", _leather)
FUR = FrostPaint("fur", _fur)
WHITE = FrostPaint("white", _frost, lift=2)
LINE = Mat("feather", "plain", 2, bevel=None, noise=0)


# ================================================================================================
# The emblem: a white snowflake on a deep blue plate
# ================================================================================================

FLAKE = ("....#....",
         ".#..#..#.",
         "..#.#.#..",
         "...###...",
         "#########",
         "...###...",
         "..#.#.#..",
         ".#..#..#.",
         "....#....")


def _emblem():
    """A deep blue snowflake on a pale ice plate, a darker line along its bottom and right."""
    im = Image.new("RGBA", (16, 16), ICE_RAMP[5])
    px = im.load()
    for y, row in enumerate(FLAKE):
        for x, ch in enumerate(row):
            if x == 8 or y == 8:
                px[x, y] = ICE_RAMP[3]
            elif ch == "#":
                px[x, y] = ICE_RAMP[0] if (x == 4 and y == 4) else ICE_RAMP[1] if (x == 4 or y == 4) else ICE_RAMP[2]
            else:
                px[x, y] = ICE_RAMP[6] if (x == 0 or y == 0) else ICE_RAMP[5]
    return im


T.SURFACES["frost_emblem"] = _emblem


def emblem(m, x0, y0, z0, z1):
    m.box("emblem", (x0, y0, z0), (x0 + STICKER, y0 + STICKER, z1), FROST,
          faces={"north": "frost_emblem", "south": "frost_emblem"}, uv={"north": STICKER_UV, "south": STICKER_UV})


# ================================================================================================
# Shared parts
# ================================================================================================

def shard(m, name, x0, y0, x1, y1, z0, z1, angle, mat=ICE, pivot=None):
    """A crystal shard: a box tilted about z by angle (about its bottom middle unless pivot)."""
    p = pivot or ((x0 + x1) / 2, y0, C)
    m.box(name, (x0, y0, z0), (x1, y1, z1), mat, rotation=("z", angle, p))


def icicle(m, x, y, length, z=C):
    """An icicle hanging from y: two steps, thinner towards its point."""
    m.box("icicle", (x, y - length * 0.6, z - 0.5), (x + 0.75, y, z + 0.5), ICE_LIGHT)
    m.box("icicle", (x + 0.125, y - length, z - 0.25), (x + 0.625, y - length * 0.6, z + 0.25), ICE_LIGHT)


def handle(m):
    """A crystal pommel under a frost cap, a pale leather grip with two frost bands, a fur cuff, a snowflake collar."""
    m.group("handle")
    m.box("pommel_tip", (7.375, -4.75, 7.375), (8.625, -4, 8.625), ICE_LIGHT)
    round_y(m, "pommel", 6.75, 9.25, 6.75, 9.25, -4, -2.5, ICE, 0.5)
    round_y(m, "pommel_cap", 6.25, 9.75, 6.25, 9.75, -2.5, -1.25, FROST, 0.75)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.25, 4.5, LEATHER, 0.5)
    m.box("grip_band", (6.5, 1.5, 6.5), (9.5, 2, 9.5), FROST)
    round_y(m, "cuff", 6.25, 9.75, 6.25, 9.75, 4.5, 5.5, FUR, 0.75)
    round_y(m, "collar", 6.25, 9.75, 6.25, 9.75, 5.5, 6.5, FROST, 0.75)
    # the snowflake's points: out along x and z, an ice bead at each end
    m.box("collar_point", (4.75, 5.75, 7.5), (6.25, 6.25, 8.5), FROST)
    m.box("collar_point", (9.75, 5.75, 7.5), (11.25, 6.25, 8.5), FROST)
    m.box("collar_point", (7.5, 5.75, 4.75), (8.5, 6.25, 6.25), FROST)
    m.box("collar_point", (7.5, 5.75, 9.75), (8.5, 6.25, 11.25), FROST)
    m.box("collar_bead", (4, 5.5, 7.25), (4.75, 6.5, 8.75), ICE_LIGHT)
    m.box("collar_bead", (11.25, 5.5, 7.25), (12, 6.5, 8.75), ICE_LIGHT)


def shaft(m, top, mat=FROST):
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, mat, 0.5)


# ================================================================================================
# The tools
# ================================================================================================

def frost_sword():
    """서리 수정 검: a faceted ice blade with a deep core and pale edges, shards at its foot, a snowflake guard, icicles."""
    m = MModel("frost_sword")
    handle(m)
    m.group("guard")
    m.box("guard", (2.5, 6.75, 7), (13.5, 8.25, 9), FROST)
    m.box("guard_hub", (5.25, 5.75, 6.5), (10.75, 10.75, 9.5), FROST)
    emblem(m, 5.75, 6, 6.25, 9.75)
    for x in (2.25, 13.75):     # ice diamonds at the ends: boxes turned 45 degrees about their middle
        m.box("guard_gem", (x - 1, 6.5, 7.25), (x + 1, 8.5, 8.75), ICE, rotation=("z", 45, (x, 7.5, C)))
    for x, ln in ((3.25, 2.5), (4.5, 1.5), (11, 1.5), (12, 2.5)):
        icicle(m, x, 6.75, ln)
    m.group("blade")
    m.box("blade", (5.5, 10.75, 7), (10.5, 22.5, 9), ICE)
    m.box("blade_edge", (4.75, 11.5, 7.5), (5.5, 21.75, 8.5), ICE_LIGHT)
    m.box("blade_edge", (10.5, 11.5, 7.5), (11.25, 21.75, 8.5), ICE_LIGHT)
    m.box("blade_core", (7.5, 11.5, 6.75), (8.5, 21, 9.25), ICE_DEEP)
    m.box("blade_tip", (6.25, 22.5, 7.125), (9.75, 24, 8.875), ICE)
    m.box("blade_tip", (7, 24, 7.25), (9, 25.5, 8.75), ICE_LIGHT)
    m.box("blade_tip", (7.5, 25.5, 7.5), (8.5, 26.75, 8.5), WHITE)
    m.group("shards")
    shard(m, "shard", 3.75, 10.25, 5.5, 14.5, 7.25, 8.75, 22.5)
    shard(m, "shard", 4.25, 13.75, 5.5, 16, 7.375, 8.625, 22.5, ICE_LIGHT)
    shard(m, "shard", 10.5, 10.25, 12.25, 13.5, 7.25, 8.75, -22.5)
    shard(m, "shard", 10.5, 16.5, 11.75, 18.75, 7.375, 8.625, -22.5, ICE_LIGHT)
    return _held(m, "sword")


def frost_pickaxe():
    """서리 수정 곡괭이: a frost head with the emblem, two curved ice arms with pale points, a crystal crown, icicles."""
    m = MModel("frost_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    m.box("head", (5, 15.25, 6.5), (11, 20.75, 9.5), FROST)
    emblem(m, 5.75, 15.75, 6.25, 9.75)
    m.box("crown", (7.125, 20.75, 7.125), (8.875, 23, 8.875), ICE)
    m.box("crown", (7.5, 23, 7.5), (8.5, 24, 8.5), ICE_LIGHT)
    shard(m, "crown_shard", 5.5, 20.25, 6.75, 22.5, 7.25, 8.75, 22.5)
    shard(m, "crown_shard", 9.25, 20.25, 10.5, 22.5, 7.25, 8.75, -22.5)
    for side in (1, -1):
        p = (C + side * 3, 18.25)
        p = _arm(m, "arm_a", side, p, 4.5, 1.75, 6.875, 9.125, 22.5, ICE)
        p = _arm(m, "arm_b", side, p, 4.0, 1.375, 7.125, 8.875, 45, ICE)
        a = math.radians(45)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 0.75, 7.375), (px + 2.5, py + 0.75, 8.625)
        else:
            frm, to = (px - 2.5, py - 0.75, 7.375), (px - 0.5, py + 0.75, 8.625)
        m.box("arm_tip", frm, to, ICE_LIGHT, rotation=("z", -45 * side, (px, py, C)))
    for x, ln in ((5.25, 2), (10, 1.5)):
        icicle(m, x, 15.25, ln)
    return _held(m, "pickaxe")


def frost_shovel():
    """서리 수정 삽: an ice spade with pale rims and a deep spine, shards at its shoulders, on a frost socket."""
    m = MModel("frost_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17, FROST, 0.75)
    rows = ((17, 18, 3.75, 7, 9), (18, 23.5, 4.5, 7, 9), (23.5, 25.5, 3.75, 7, 9), (25.5, 27, 2.75, 7.2, 8.8))
    for y0, y1, hw, z0, z1 in rows:
        m.box("blade", (C - hw, y0, z0), (C + hw, y1, z1), ICE)
        m.box("rim", (C - hw, y0, z1), (C - hw + 1, y1, z1 + 0.4), ICE_LIGHT)
        m.box("rim", (C + hw - 1, y0, z1), (C + hw, y1, z1 + 0.4), ICE_LIGHT)
    m.box("point", (6.5, 27, 7.2), (9.5, 28.25, 8.8), ICE_LIGHT)
    m.box("point", (7.25, 28.25, 7.4), (8.75, 29, 8.6), WHITE)
    m.box("spine", (7.25, 17, 6.6), (8.75, 25, 7), ICE_DEEP)
    emblem(m, 5.75, 19.25, 6.75, 9.5)
    m.group("shards")
    shard(m, "shard", 2.5, 17.5, 3.75, 20, 7.25, 8.75, 22.5, ICE_LIGHT)
    shard(m, "shard", 12.25, 17.5, 13.5, 20, 7.25, 8.75, -22.5, ICE_LIGHT)
    for x, ln in ((4.75, 1.5), (10.5, 1.5)):
        icicle(m, x, 17, ln)
    return _held(m, "shovel")


def frost_axe():
    """서리 수정 도끼: a wide faceted ice blade (towards -x) on a frost head with the emblem, a crystal spike behind."""
    m = MModel("frost_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 21.5, 22.5, FROST, 0.75)
    m.box("shaft_point", (7.25, 22.5, 7.25), (8.75, 24, 8.75), ICE_LIGHT)
    m.box("head", (4.5, 14, 6.5), (10, 20.5, 9.5), FROST)
    emblem(m, 5, 15, 6.25, 9.75)
    m.box("blade", (2, 13, 6.875), (4.5, 21.5, 9.125), ICE)
    m.box("blade", (0, 12, 7.25), (2, 22.5, 8.75), ICE)
    m.box("blade_core", (1.25, 14, 7), (3.25, 20.5, 9), ICE_DEEP)
    m.box("blade_edge", (-1.25, 12.5, 7.5), (0, 22, 8.5), ICE_LIGHT)
    m.box("blade_edge", (-0.75, 11.25, 7.5), (0, 12.5, 8.5), WHITE)
    m.box("blade_edge", (-0.75, 22, 7.5), (0, 23.25, 8.5), WHITE)
    shard(m, "shard", 1, 22.25, 2.5, 24.5, 7.375, 8.625, 22.5, ICE_LIGHT)
    m.box("poll", (10, 15.25, 7), (11.75, 19.25, 9), ICE)
    m.box("poll", (11.75, 16.25, 7.25), (13.25, 18.25, 8.75), ICE_LIGHT)
    for x, y, ln in ((2.75, 13, 2), (0.5, 12, 1.25), (5.25, 14, 1.25)):
        icicle(m, x, y, ln)
    return _held(m, "axe")


def frost_hoe():
    """서리 수정 괭이: a frost bar across the shaft's top and a broad ice plate hanging from its end, icicles under the bar."""
    m = MModel("frost_hoe")
    handle(m)
    shaft(m, 20.5)
    m.group("head")
    m.box("bar", (2.5, 19.75, 6.75), (9.75, 22.25, 9.25), FROST)
    round_y(m, "bar_cap", 6.5, 9.5, 6.5, 9.5, 22.25, 23, FROST, 0.75)
    m.box("bar_point", (7.25, 23, 7.25), (8.75, 24.25, 8.75), ICE_LIGHT)
    m.box("plate", (0.25, 14.75, 5), (2.5, 22.75, 11), ICE)
    m.box("plate_edge", (0.5, 13.5, 5.25), (2.25, 14.75, 10.75), ICE_LIGHT)
    m.box("plate_core", (0, 16, 6.5), (0.25, 21.5, 9.5), ICE_DEEP)
    shard(m, "shard", 0.5, 22.75, 1.75, 24.75, 5.5, 7, 22.5, ICE_LIGHT)
    shard(m, "shard", 0.75, 22.75, 2, 24, 9, 10.5, -22.5, ICE_LIGHT)
    m.box("emblem", (2.5, 19.5, 5.75), (2.75, 24, 10.25), FROST, faces={"east": "frost_emblem"}, uv={"east": STICKER_UV})
    m.box("emblem_back", (-0.25, 16.5, 5.75), (0, 21, 10.25), FROST, faces={"west": "frost_emblem"}, uv={"west": STICKER_UV})
    for x, ln in ((4, 2), (5.5, 1.25)):
        icicle(m, x, 19.75, ln)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9, 7.5, 7.5), (10, 9.5, 8.5), FROST_DIM)
    cyl(m, "reel", "z", (12, 8.5), 4, 6.75, 9.25, FROST, cap=FROST)
    m.box("reel_hub", (11.25, 7.75, 6.25), (12.75, 9.25, 9.75), ICE)
    m.box("crank", (11.5, 8.25, 9.75), (12.25, 11, 10.25), FROST)
    m.box("crank_knob", (11.25, 10.5, 10.25), (12.5, 11.75, 11.25), ICE_LIGHT)
    m.group("rod")
    round_y(m, "rod", 6.875, 9.125, 6.875, 9.125, 6.5, 13.5, ICE, 0.5)
    m.box("rod", (7.125, 13.5, 7.125), (8.875, 23, 8.875), ICE)
    m.box("rod", (7.375, 23, 7.375), (8.625, 29.25, 8.625), ICE_LIGHT)
    for y, r in ((13, 1.375), (18, 1.125), (23, 1.125), (26.5, 0.875)):
        m.box("rod_ring", (C - r, y, C - r), (C + r, y + 0.75, C + r), FROST)
    m.box("guide", (8.875, 20, 7.5), (10, 21, 8.5), FROST_DIM)
    m.box("guide", (8.625, 27.5, 7.5), (9.5, 28.5, 8.5), FROST_DIM)
    # the crystal tip: a diamond (turned 45 degrees about y) and a white point
    m.box("rod_tip", (7.125, 29.25, 7.125), (8.875, 30.75, 8.875), ICE, rotation=("y", 45, (C, 30, C)))
    m.box("rod_tip", (7.5, 30.75, 7.5), (8.5, 31.75, 8.5), WHITE)
    if not cast:
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 19.5, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        # a little ice crystal float: a pale top, a frost band, a deep point under it
        m.box("float_top", (7.75, 18.75, 7.75), (9.25, 19.5, 9.25), ICE_LIGHT, rotation=rot)
        m.box("float", (6.75, 16.5, 6.75), (10.25, 18.75, 10.25), ICE, rotation=rot)
        m.box("float_band", (6.5, 15.75, 6.5), (10.5, 16.5, 10.5), FROST, rotation=rot)
        m.box("float_low", (7.25, 14.5, 7.25), (9.75, 15.75, 9.75), ICE_DEEP, rotation=rot)
        m.box("float_point", (8, 13.5, 8), (9, 14.5, 9), ICE_DEEP, rotation=rot)
    return _held(m, "rod")


def frost_fishing_rod():
    """서리 수정 낚싯대: a slender ice rod with frost rings and a crystal tip, a small frost reel, an ice crystal float."""
    return _rod("frost_fishing_rod", False)


def frost_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("frost_fishing_rod_cast", True)
    m.display = frost_fishing_rod().display
    return m


BOBBER_WATERLINE = 5.0        # the float's frost band: where the water's surface is (model y)


def frost_bobber():
    """서리 수정 찌: a small ice crystal float, a deep point below its frost band (the waterline), a pale point above,
    the emblem on four sides. Stands on y = 0 about the axis x = 8, z = 8, like the toy float."""
    m = MModel("frost_bobber")
    m.group("float")
    cyl(m, "point", "y", (C, C), 2, 0, 1.25, ICE_DEEP, cap=ICE_DEEP)
    cyl(m, "low", "y", (C, C), 4, 1.25, 2.75, ICE_DEEP, cap=ICE_DEEP)
    cyl(m, "belly", "y", (C, C), 6, 2.75, 4.5, ICE, cap=ICE)
    cyl(m, "band", "y", (C, C), 7, 4.5, 5.5, FROST, cap=FROST)
    cyl(m, "top", "y", (C, C), 6, 5.5, 8, ICE, cap=ICE)
    cyl(m, "top", "y", (C, C), 4, 8, 9.5, ICE_LIGHT, cap=ICE_LIGHT)
    cyl(m, "top", "y", (C, C), 2, 9.5, 11, WHITE, cap=WHITE)
    m.group("emblems")
    for face, frm, to in (("south", (6.75, 5.5, 11), (9.25, 8, 11.25)), ("north", (6.75, 5.5, 4.75), (9.25, 8, 5)),
                          ("east", (11, 5.5, 6.75), (11.25, 8, 9.25)), ("west", (4.75, 5.5, 6.75), (5, 8, 9.25))):
        m.box("emblem", frm, to, FROST, faces={face: "frost_emblem"}, uv={face: STICKER_UV})
    m.display = presets.furniture(gui_scale=1.3, gui_translation=(0, 3.25, 0))
    m.kind = "bobber"
    return m


ALL = {f.__name__: f for f in (frost_sword, frost_pickaxe, frost_shovel, frost_axe, frost_hoe, frost_fishing_rod,
                               frost_fishing_rod_cast, frost_bobber)}

ROWS = [("frost_sword", "서리 수정 검"), ("frost_pickaxe", "서리 수정 곡괭이"), ("frost_shovel", "서리 수정 삽"),
        ("frost_axe", "서리 수정 도끼"), ("frost_hoe", "서리 수정 괭이"), ("frost_fishing_rod", "서리 수정 낚싯대"),
        ("frost_fishing_rod_cast", "낚싯대 (던진 뒤)"), ("frost_bobber", "서리 수정 찌 (찌에 태우는 모델)")]

TITLE = "서리 수정 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("frost_sword", "frost_pickaxe", "frost_shovel", "frost_axe", "frost_hoe", "frost_bobber")}
    out["frost_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/frost_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/frost_fishing_rod_cast"},
    }}
    return out
