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

from economy_machines import MModel, Mat, _face_size, cyl
from models.toys import C, GRIP, GRIP_Y, STICKER, STICKER_UV, _arm, _held, round_y, round_z  # noqa: F401
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
        for y in range(S):          # opaque everywhere: outside the face's own region, the nearest edge colour
            for x in range(S):
                px[x, y] = rows[min(y, ph - 1)][min(x, pw - 1)]
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
            if d == 0 and min(w, h) <= 2:      # a thin face is all rim: keep its colour, not just its light
                i = 4 if (x == 0 or y == 0) else 2
            elif d == 0:
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
# A model built bigger than the -16..32 box allows: shrunk about the grip as it is built, the held views enlarged back
# ================================================================================================

G = (C, GRIP_Y, C)


class Shrunk(MModel):
    """Every box is placed k times as far from the grip point (8, GRIP_Y, 8) as given, so a greatsword fits the model
    box; its faces keep the texel size of the size given (one texel a given unit, like every other piece). _big() then
    enlarges the held views by 1 / k about the grip, so in the hand it is the size it was drawn."""

    def __init__(self, name, k):
        super().__init__(name)
        self.k = k

    def _p(self, p):
        return tuple(G[i] + self.k * (p[i] - G[i]) for i in range(3))

    def box(self, name, frm, to, tex, faces=None, full_uv=(), glow=False, rotation=None, uv=None, side_v=None, shade=True):
        fs = {f: tex for f in ("north", "east", "south", "west", "up", "down")}
        fs.update(faces or {})
        uv = dict(uv or {})
        for f, t in list(fs.items()):
            if isinstance(t, Mat):
                w, h = _face_size(f, frm, to)
                key, region = t.face(w, h, f, zlib.crc32(f"{round(w, 2)}x{round(h, 2)}/{f}".encode()))
                fs[f] = key
                if region and f not in uv:
                    uv[f] = region
        if rotation:
            axis, angle, o = rotation
            rotation = (axis, angle, self._p(o))
        return super().box(name, self._p(frm), self._p(to), tex, faces=fs, full_uv=full_uv, glow=glow, rotation=rotation,
                           uv=uv, side_v=side_v, shade=shade)


def _big(m, kind):
    _held(m, kind)
    held = ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand")
    m.display = presets.enlarged(m.display, 1 / m.k, GRIP, held)
    for ctx in held:
        m.display[ctx]["translation"] = [v + 0.0 for v in m.display[ctx]["translation"]]
    return m


# ================================================================================================
# The tools: each one an icy thing standing in for the tool
# ================================================================================================

SWORD_K = 0.75      # 고드름 대검 is drawn 1.6 times the toy sword's blade above the grip, then shrunk to fit the box


def frost_sword():
    """고드름 대검: a huge icicle for a blade (1.6 times the toy sword's above the grip), tapering in drips, each drip a pale
    ring, a white point; a snow crust at its root, smaller icicles growing beside it, a frost crossguard with the emblem."""
    m = Shrunk("frost_sword", SWORD_K)
    handle(m)
    m.group("guard")
    m.box("guard", (1.75, 6.75, 7), (14.25, 8.5, 9), FROST)
    m.box("guard_hub", (5.25, 6.5, 6.5), (10.75, 11.25, 9.5), FROST)
    emblem(m, 5.75, 6.625, 6.25, 9.75)
    for x in (1.5, 14.5):
        m.box("guard_gem", (x - 1.125, 6.5, 7.25), (x + 1.125, 8.75, 8.75), ICE, rotation=("z", 45, (x, 7.625, C)))
    for x, ln in ((2.75, 2.5), (3.75, 1.5), (11.5, 1.5), (12.5, 2.5)):
        icicle(m, x, 6.75, ln)
    m.group("blade")
    round_y(m, "snow", 4.5, 11.5, 6.25, 9.75, 11.25, 12.5, FUR, 1)
    # the icicle: (y0, y1, half width, material); its depth is two thirds of its width (a blade from the side)
    rows = ((12.5, 19, 3.25, ICE), (19, 20, 3.5, ICE_LIGHT), (20, 27, 2.75, ICE), (27, 33, 2.25, ICE),
            (33, 34, 2.5, ICE_LIGHT), (34, 37.5, 1.75, ICE), (37.5, 39.5, 1.25, ICE_LIGHT), (39.5, 41, 0.75, ICE_LIGHT))
    for y0, y1, hw, mat in rows:
        hz = max(0.5, round(hw * 0.65 * 4) / 4)
        cut = 0.5 if hw >= 2 else 0.25 if hw > 1 else 0
        if cut:
            round_y(m, "icicle_blade", C - hw, C + hw, C - hz, C + hz, y0, y1, mat, cut)
        else:
            m.box("icicle_blade", (C - hw, y0, C - hz), (C + hw, y1, C + hz), mat)
    m.box("blade_point", (7.625, 41, 7.625), (8.375, 41.75, 8.375), WHITE)
    m.group("shards")
    shard(m, "side_icicle", 3.5, 11.75, 5.0, 17.5, 7.25, 8.75, 22.5)
    shard(m, "side_icicle", 3.875, 17.5, 4.625, 19.5, 7.5, 8.5, 22.5, ICE_LIGHT, pivot=(4.25, 11.75, C))
    shard(m, "side_icicle", 11.0, 11.75, 12.5, 15.75, 7.25, 8.75, -22.5)
    shard(m, "side_icicle", 11.375, 15.75, 12.125, 17.25, 7.5, 8.5, -22.5, ICE_LIGHT, pivot=(11.75, 11.75, C))
    return _big(m, "sword")


def frost_pickaxe():
    """눈송이 곡괭이: a snowflake on the shaft: a frost hub with the emblem, two long crystal branches out to the sides (their
    outer halves bending down into points: the pick's arms), each with a V of side branches, three short branches above."""
    m = MModel("frost_pickaxe")
    handle(m)
    shaft(m, 15.5)
    m.group("hub")
    round_z(m, "hub", 5, 11, 15, 21, 6.5, 9.5, FROST, 1.25)
    emblem(m, 5.75, 15.75, 6.25, 9.75)
    m.group("branches")
    for side in (1, -1):
        x0 = C + side * 3
        lo, hi = (x0, x0 + side * 4.5) if side > 0 else (x0 + side * 4.5, x0)
        m.box("branch", (lo, 17.25, 7), (hi, 18.75, 9), ICE)
        # the V: two twigs from the branch's middle, out and up, out and down
        tx = x0 + side * 2.5
        tlo, thi = (tx, tx + 3) if side > 0 else (tx - 3, tx)
        m.box("twig", (tlo, 17.5, 7.25), (thi, 18.5, 8.75), ICE_LIGHT, rotation=("z", 45 * side, (tx, 18, C)))
        m.box("twig", (tlo, 17.5, 7.25), (thi, 18.5, 8.75), ICE_LIGHT, rotation=("z", -45 * side, (tx, 18, C)))
        p = _arm(m, "arm", side, (x0 + side * 4.5, 18), 4.5, 0.875, 7.125, 8.875, 22.5, ICE)
        px, py = p
        frm, to = ((px + 0.5, py - 0.5, 7.375), (px + 2.25, py + 0.5, 8.625)) if side > 0 else \
            ((px - 2.25, py - 0.5, 7.375), (px - 0.5, py + 0.5, 8.625))
        m.box("arm_point", frm, to, WHITE, rotation=("z", -22.5 * side, (px, py, C)))
        # a short branch up and out from the hub's shoulder
        sx = C + side * 2
        slo, shi = (sx, sx + 3.5) if side > 0 else (sx - 3.5, sx)
        m.box("crown", (slo, 19.5, 7.25), (shi, 20.75, 8.75), ICE, rotation=("z", 45 * side, (sx, 20.125, C)))
    m.box("crown", (7.25, 21, 7.25), (8.75, 24, 8.75), ICE)
    m.box("crown_point", (7.625, 24, 7.625), (8.375, 25, 8.375), WHITE)
    m.box("crown_twig", (6, 22.25, 7.5), (10, 23, 8.5), ICE_LIGHT)
    return _held(m, "pickaxe")


def frost_shovel():
    """눈썰매 삽: a little sled for a scoop: cyan slats over a deep blue bed, silver runners standing proud down both sides
    and curling over at the top, a pull cord between them, the emblem on the slats."""
    m = MModel("frost_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("sled")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17, FROST, 0.75)
    m.box("bed", (4.5, 17, 7), (11.5, 27, 7.75), ICE_DEEP)
    for y0, y1 in ((17.25, 19.75), (20.5, 23), (23.75, 26.25)):
        m.box("slat", (4.75, y0, 7.75), (11.25, y1, 8.75), ICE)
    m.box("lip", (4, 16.25, 7.25), (12, 17.25, 9.25), FROST)
    for x0, x1 in ((3.25, 4.5), (11.5, 12.75)):
        m.box("runner", (x0, 16.25, 9.25), (x1, 27, 10.25), FROST)
        m.box("runner_post", (x0 + 0.25, 18, 7.25), (x1 - 0.25, 19, 9.25), FROST_DIM)
        m.box("runner_post", (x0 + 0.25, 24, 7.25), (x1 - 0.25, 25, 9.25), FROST_DIM)
        # the curl: up, over towards the back, down a little
        m.box("runner_curl", (x0, 27, 8.75), (x1, 28.25, 10), FROST)
        m.box("runner_curl", (x0, 28.25, 7.5), (x1, 29.25, 9.5), FROST)
        m.box("runner_curl", (x0, 27.5, 6.5), (x1, 28.75, 7.5), FROST)
    m.box("cord", (4.5, 28.25, 8.25), (11.5, 28.75, 8.75), ICE_DEEP)
    m.box("snow", (5.5, 26.25, 7.75), (10, 27, 8.75), FUR)
    emblem(m, 5.75, 20, 6.75, 9)
    return _held(m, "shovel")


def frost_axe():
    """빙하 도끼: a rough chunk of glacier for a head: blocky ice stacked out of line, deep blue cracks, snow lying on its top
    ledges, a jagged pale edge towards -x, icicles under it, a small chunk behind; the emblem where it meets the shaft."""
    m = MModel("frost_axe")
    handle(m)
    shaft(m, 21)
    m.group("glacier")
    m.box("chunk", (1, 13.5, 6.25), (9.75, 21, 9.75), ICE)
    m.box("chunk", (-1.25, 15, 6.75), (1, 22.25, 9.25), ICE)
    m.box("chunk", (-0.5, 12, 7), (3.75, 13.5, 9), ICE_DEEP)
    m.box("edge", (-2.75, 14, 7.25), (-1.25, 21, 8.75), ICE_LIGHT)
    m.box("edge", (-3.5, 16, 7.5), (-2.75, 19.25, 8.5), WHITE)
    m.box("edge", (-2.25, 12.75, 7.5), (-0.5, 14, 8.5), ICE_LIGHT)
    m.box("crack", (2.75, 14.75, 9.75), (3.5, 19.5, 10), ICE_DEEP)
    m.box("crack", (3.5, 17, 9.75), (4.5, 17.75, 10), ICE_DEEP)
    m.box("snow", (1.25, 21, 6.5), (9.5, 21.875, 9.5), FUR)
    m.box("snow", (-1.5, 22.25, 7), (1.5, 23, 9), FUR)
    round_y(m, "cap", 6.5, 9.5, 6.5, 9.5, 21.875, 23, FROST, 0.75)
    emblem(m, 5, 15, 6, 10)
    m.box("chunk_back", (9.75, 15, 7), (11.75, 19.25, 9), ICE_LIGHT)
    m.box("chunk_back", (11.75, 16, 7.25), (12.75, 18, 8.75), ICE)
    for x, y, ln in ((-0.25, 12, 2), (1.75, 12, 1.25), (5.5, 13.5, 1.5)):
        icicle(m, x, y, ln)
    return _held(m, "axe")


def frost_hoe():
    """스케이트날 괭이: an ice skate's runner for the straight head: a white sole out to one side of a frost hub (the emblem
    on it), two silver posts under it holding a long steel blade with a bright edge, every end cut flat."""
    m = MModel("frost_hoe")
    handle(m)
    shaft(m, 21)
    m.group("skate")
    m.box("hub", (5.5, 21, 6.5), (10.5, 25.75, 9.5), FROST)
    emblem(m, 5.75, 21.125, 6.25, 9.75)
    m.box("sole", (-2.25, 22, 6.75), (5.5, 24.25, 9.25), WHITE)
    m.box("sole_rim", (-2.5, 21.5, 7), (5.5, 22, 9), FROST_DIM)
    for x in (-1.25, 2.5):
        m.box("post", (x, 20.25, 7.25), (x + 1.25, 21.5, 8.75), FROST)
    m.box("runner", (-2.75, 18.75, 7.625), (5.25, 20.25, 8.375), ICE_DEEP)
    m.box("runner_edge", (-2.75, 18.25, 7.75), (5.25, 18.75, 8.25), WHITE)
    for x in (-1, 1.25, 3.5):     # laces across the sole's top
        m.box("lace", (x, 24.25, 7.25), (x + 0.75, 24.75, 8.75), ICE_LIGHT)
    return _held(m, "hoe")


def _rod(name, cast):
    """얼음낚시 대: a short ice-fishing rod: the series' grip, a small frost reel, a short ice rod with frost rings and a
    crystal bead at its tip, a little ice-hole charm (a white ice disc round a dark blue hole) hanging from the collar."""
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9, 7.5, 7.5), (10, 9.5, 8.5), FROST_DIM)
    cyl(m, "reel", "z", (12, 8.75), 5, 6.5, 9.5, FROST, cap=FROST)
    m.box("reel_hub", (11.25, 8, 6), (12.75, 9.5, 10), ICE)
    m.box("crank", (11.5, 8.5, 10), (12.25, 11.5, 10.5), FROST)
    m.box("crank_knob", (11.25, 11, 10.5), (12.5, 12.25, 11.5), ICE_LIGHT)
    m.group("charm")
    m.box("charm_chain", (11.5, 3.5, 7.75), (12, 5.5, 8.25), FROST_DIM)
    cyl(m, "charm", "z", (11.75, 1.5), 4, 7.25, 8.25, WHITE, cap=WHITE)
    cyl(m, "charm_hole", "z", (11.75, 1.5), 2, 8.25, 8.5, ICE_DEEP, cap=ICE_DEEP)
    m.group("rod")
    round_y(m, "rod", 6.875, 9.125, 6.875, 9.125, 6.5, 10.5, ICE, 0.5)
    m.box("rod", (7.25, 10.5, 7.25), (8.75, 19.5, 8.75), ICE)
    m.box("rod", (7.5, 19.5, 7.5), (8.5, 24.5, 8.5), ICE_LIGHT)
    for y, r in ((12, 1.125), (17, 1.0), (21.5, 0.875)):
        m.box("rod_ring", (C - r, y, C - r), (C + r, y + 0.75, C + r), FROST)
    m.box("guide", (8.75, 14.5, 7.625), (9.75, 15.5, 8.375), FROST_DIM)
    m.box("guide", (8.5, 22.5, 7.625), (9.25, 23.25, 8.375), FROST_DIM)
    m.box("rod_tip", (7.25, 24.5, 7.25), (8.75, 25.75, 8.75), ICE, rotation=("y", 45, (C, 25, C)))
    m.box("rod_tip", (7.625, 25.75, 7.625), (8.375, 26.5, 8.375), WHITE)
    if not cast:
        m.group("line")
        top = (8.25, 26, C)
        rot = ("z", 45, top)
        m.box("line", (7.875, 16.5, 7.625), (8.625, 26, 8.375), LINE, rotation=rot)
        # a small ice jig: a pale top, a frost band, a deep point
        m.box("jig_top", (7.5, 15.75, 7.5), (9, 16.5, 9), ICE_LIGHT, rotation=rot)
        m.box("jig", (6.75, 13.75, 6.75), (9.75, 15.75, 9.75), ICE, rotation=rot)
        m.box("jig_band", (6.5, 13, 6.5), (10, 13.75, 10), FROST, rotation=rot)
        m.box("jig_low", (7.25, 11.75, 7.25), (9.25, 13, 9.25), ICE_DEEP, rotation=rot)
        m.box("jig_point", (7.75, 10.75, 7.75), (8.75, 11.75, 8.75), ICE_DEEP, rotation=rot)
    return _held(m, "rod")


def frost_fishing_rod():
    """얼음낚시 대 (with its jig on the line)."""
    return _rod("frost_fishing_rod", False)


def frost_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the jig."""
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

ROWS = [("frost_sword", "고드름 대검"), ("frost_pickaxe", "눈송이 곡괭이"), ("frost_shovel", "눈썰매 삽"),
        ("frost_axe", "빙하 도끼"), ("frost_hoe", "스케이트날 괭이"), ("frost_fishing_rod", "얼음낚시 대"),
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
