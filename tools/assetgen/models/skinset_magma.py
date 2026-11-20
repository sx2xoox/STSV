"""
용암 시리즈 (magma series): a premium tool skin set, every tool a volcanic stand-in for the real one.

    magma_sword          화산 대검        a broad basalt greatsword with molten serrated edges and a white-hot fissure
    magma_pickaxe        분화구 곡괭이    a basalt pick whose crown is a little smoking volcano, molten points on both arms
    magma_shovel         마그마 국자 삽   a basalt spade dished like a ladle, brimming with magma that spills off its shoulders
    magma_axe            불새 날개 도끼   a blade of firebird feathers burning from red to white-hot, a firebird head behind
    magma_hoe            화염 괭이        a straight flat ㄱ head of basalt with a lava vein, small flames on its top edge
    magma_fishing_rod    불꽃 채찍 낚싯대 a charred whip with glowing knots and a flame at its tip, a basalt reel (+ _cast)
    magma_bobber         용암 찌          a little magma rock float

Same frame, grip and display transforms as the toy series (models/toys.py: built upright on the axis x = z = 8, the hand
on GRIP_Y, _held() puts the sprite's 45 degree tilt into the transforms). The greatsword is longer than a model may be
(elements end at y = 32): it is built at full size and every box shrunk about the grip point by k as it is added (the
faces painted at their full size first, so the texels stay one a unit); its held views are enlarged about the grip by
1 / k, so in the hand it is full size again.

Looks: the rock is painted in WORLD space (Rock): one 3D cell pattern runs across every box of a piece, so the plates of
cooled basalt and the glowing cracks between them (yellow-hot core, a red crust round it) continue from box to box and
round the corners like one cast stone. On that rock the lava itself is separate glowing boxes (light_emission 15,
unshaded): molten edges, fissures, flames, magma pools. Dark steel fittings, charred leather grips.
"""

import math
import zlib

from PIL import Image

from economy_machines import MModel, Mat, _face_size, cyl
from stsv_assets import presets
from stsv_assets import textures as T

from models.toys import C, GRIP, GRIP_Y, SIZES, _held, round_y  # noqa: F401  (SIZES: the frame this set shares)

S = 16

# cooled basalt: near black with a violet-brown cast, a light step for the lit top/left edge of each face
BASALT = [(18, 14, 22, 255), (30, 24, 36, 255), (44, 36, 52, 255), (62, 52, 70, 255), (92, 80, 98, 255)]
CRACK_CORE = (255, 214, 92, 255)       # yellow-hot middle of a crack
CRACK = (244, 132, 22, 255)            # orange
CRUST = (122, 34, 18, 255)             # the red crust either side of a crack
CRUST_DARK = (78, 22, 20, 255)


def _h(*a):
    n = zlib.crc32(("|".join(str(x) for x in a)).encode())
    return (n & 0xffff) / 65536.0


# face -> (u axis, u sign, v axis, v sign, normal axis, normal sign): how Minecraft lays a face's uv on the box
_UV = {
    "north": (0, -1, 1, -1, 2, -1), "south": (0, 1, 1, -1, 2, 1),
    "east": (2, -1, 1, -1, 0, 1), "west": (2, 1, 1, -1, 0, -1),
    "up": (0, 1, 2, 1, 1, 1), "down": (0, 1, 2, -1, 1, -1),
}


class Rock(Mat):
    """Basalt painted at each texel's world position: Voronoi plates (cell size `cell`) in 3D, so neighbouring boxes
    share one pattern. hot: the plate borders are lava cracks (yellow core, orange, a red crust beside them);
    cold: they are thin dark seams. Each face gets a 1 px light edge top/left and a dark one bottom/right."""

    def __init__(self, base=1, hot=True, cell=4.0, seed=0, light=1):
        super().__init__("iron", "plain", base, bevel=(1, -1), noise=0)
        self.hot, self.cell, self.seed, self.light = hot, cell, seed, light

    def __call__(self, **changes):
        r = Rock(self.base, self.hot, self.cell, self.seed, self.light)
        r.bevel = changes.get("bevel", self.bevel)
        return r

    def _seed_pt(self, c):
        g = self.cell
        return tuple((c[i] + 0.15 + 0.7 * _h("rk", self.seed, c, i)) * g for i in range(3))

    def _d12(self, p):
        g = self.cell
        base = [math.floor(p[i] / g) for i in range(3)]
        ds = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    c = (base[0] + dx, base[1] + dy, base[2] + dz)
                    s = self._seed_pt(c)
                    ds.append((math.dist(p, s), c))
        ds.sort()
        return ds[0][0], ds[1][0], ds[0][1]

    def face_world(self, f, frm, to):
        lo = [min(frm[i], to[i]) for i in range(3)]
        hi = [max(frm[i], to[i]) for i in range(3)]
        w, h = _face_size(f, lo, hi)
        pw, ph = max(1, math.ceil(w - 1e-6)), max(1, math.ceil(h - 1e-6))
        if pw > S or ph > S:
            return self.face(w, h, f, 0)
        ua, us, va, vs, na, ns = _UV[f]
        im = Image.new("RGBA", (S, S), BASALT[self.base])
        px = im.load()
        for j in range(ph):
            for i in range(pw):
                p = [0.0, 0.0, 0.0]
                cu = min(i + 0.5, max(0.0, (hi[ua] - lo[ua]) - 0.05))
                cv = min(j + 0.5, max(0.0, (hi[va] - lo[va]) - 0.05))
                p[ua] = lo[ua] + cu if us > 0 else hi[ua] - cu
                p[va] = lo[va] + cv if vs > 0 else hi[va] - cv
                p[na] = hi[na] - 0.3 if ns > 0 else lo[na] + 0.3
                d1, d2, cell = self._d12(p)
                gap = d2 - d1
                shade = self.base + (1 if _h("pl", self.seed, cell) < 0.3 else 0)
                if self.bevel:
                    if (j == 0 and ph > 1) or (i == 0 and pw > 1):
                        shade += self.light
                    elif (j == ph - 1 and ph > 1) or (i == pw - 1 and pw > 1):
                        shade -= 1
                col = BASALT[max(0, min(len(BASALT) - 1, shade))]
                if self.hot:
                    if gap < 0.12:
                        col = CRACK_CORE
                    elif gap < 0.42:
                        col = CRACK
                    elif gap < 0.8:
                        col = CRUST
                elif gap < 0.45:
                    col = BASALT[0]
                px[i, j] = col
        key = "magma_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, [0, 0, round(w, 4), round(h, 4)]


class MagmaModel(MModel):
    """An MModel that paints Rock faces at their world position, and (k < 1) shrinks every box by k about the grip
    point (8, GRIP_Y, 8) after painting it at full size."""

    def __init__(self, name, k=1.0):
        super().__init__(name)
        self.k = k

    def _p(self, p):
        o = (C, GRIP_Y, C)
        return tuple(round(o[i] + self.k * (p[i] - o[i]), 4) for i in range(3))

    def box(self, name, frm, to, tex, faces=None, full_uv=(), glow=False, rotation=None, uv=None, side_v=None, shade=True):
        fs = {f: tex for f in ("north", "east", "south", "west", "up", "down")}
        fs.update(faces or {})
        uv = dict(uv or {})
        for f, t in list(fs.items()):
            if isinstance(t, Rock):
                fs[f], region = t.face_world(f, frm, to)
            elif isinstance(t, Mat):
                w, h = _face_size(f, frm, to)
                fs[f], region = t.face(w, h, f, zlib.crc32(f"{round(w, 2)}x{round(h, 2)}/{f}".encode()))
                glow = glow or t.glow
            else:
                continue
            if region and f not in uv and f not in full_uv:
                uv[f] = region
        if self.k != 1.0:
            frm, to = self._p(frm), self._p(to)
            if rotation:
                rotation = (rotation[0], rotation[1], self._p(rotation[2]))
        first = next((t for t in fs.values() if t is not None), None)
        return super().box(name, frm, to, first, faces=fs, full_uv=full_uv, glow=glow,
                           rotation=rotation, uv=uv, side_v=side_v, shade=shade)


def _long(m, kind):
    _held(m, kind)
    held = ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand")
    m.display.update(presets.enlarged({c: m.display[c] for c in held}, 1 / m.k, GRIP))
    return m


# ================================================================================================
# Materials
# ================================================================================================

ROCK = Rock(1, hot=False, cell=9.0, seed=2)                  # the cracked basalt of blades and heads
ROCK_FINE = Rock(1, hot=True, cell=5.5, seed=7)     # smaller heads: finer cracks
ROCK_COLD = Rock(2, hot=False, cell=3.0, seed=3)
ROCK_RIM = Rock(3, hot=False, cell=3.0, seed=4)    # cooled crust: rims, horns, small parts
ROCK_DARK = Rock(1, hot=False, cell=3.0, seed=5)
LAVA = Mat("fire", "plain", 4, bevel=(1, -1), noise=0, glow=True)        # yellow-hot
LAVA_DEEP = Mat("fire", "plain", 3, bevel=(1, -1), noise=0, glow=True)   # flowing orange
LAVA_RED = Mat("fire", "plain", 2, bevel=(1, -1), noise=0, glow=True)    # cooling orange-red
EMBER = Mat("tomato", "plain", 2, bevel=(1, -1), noise=0, glow=True)     # red-hot
WHITE_HOT = Mat("fire", "plain", 5, bevel=None, noise=0, glow=True)
STEEL = Mat("blacksteel", "metal", 2, bevel=(1, -1), noise=0)
STEEL_LIGHT = Mat("blacksteel", "metal", 3, bevel=(1, -1), noise=0)
LEATHER = Mat("soot", "weave", 0, bevel=(1, -1), noise=0.04)              # charred leather wrap
WHIP = Mat("soot", "weave", 1, bevel=(1, -1), noise=0.04)
LINE = Mat("glow_amber", "plain", 1, bevel=None, noise=0)
SMOKE = Mat("stone", "plain", 2, bevel=(1, -1), noise=0.06)
SMOKE_DARK = Mat("stone", "plain", 1, bevel=(1, -1), noise=0.06)


# ================================================================================================
# Shared parts
# ================================================================================================

def handle(m, low=-1.25, bands=(0.5,)):
    """Basalt pommel with a glowing ring and a molten tip, a charred leather grip from `low` with steel bands, a steel
    collar with a red-hot rim (up to y 6.75)."""
    m.group("handle")
    p = low - 2
    m.box("pommel_tip", (7.375, p - 1, 7.375), (8.625, p, 8.625), LAVA)
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, p, low, ROCK_COLD, 0.75)
    m.box("pommel_ring", (6.0, p + 0.75, 7.125), (10.0, p + 1.25, 8.875), EMBER)
    m.box("pommel_ring", (7.125, p + 0.75, 6.0), (8.875, p + 1.25, 10.0), EMBER)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, low, 5.25, LEATHER, 0.5)
    for y in bands:
        m.box("grip_band", (6.5, y, 6.5), (9.5, y + 0.75, 9.5), STEEL_LIGHT)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, STEEL, 1)
    m.box("collar_rim", (6.5, 6.5, 6.5), (9.5, 6.75, 9.5), EMBER)


def shaft(m, top, mat=None):
    """The basalt haft above the collar, up to y = top: cold, with a red-hot band part way up."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.75, top, mat or ROCK_DARK, 0.5)
    mid = 6.75 + (top - 6.75) * 0.45
    m.box("shaft_band", (6.625, mid, 6.625), (9.375, mid + 0.75, 9.375), STEEL_LIGHT)


def drip(m, x, y, z=C, big=True):
    """A molten drip hanging from y: a bead under a thin neck."""
    m.box("drip", (x - 0.375, y - 1, z - 0.375), (x + 0.375, y, z + 0.375), LAVA_DEEP)
    if big:
        m.box("drip", (x - 0.5, y - 1.75, z - 0.5), (x + 0.5, y - 1, z + 0.5), LAVA)


def _arm(m, name, side, pivot, length, half_h, z0, z1, angle, mat, back=0.5):
    """A piece of the pickaxe's arm along x from its pivot, drooping by angle; returns the next piece's pivot."""
    px, py = pivot
    if side > 0:
        frm, to = (px - back, py - half_h, z0), (px + length, py + half_h, z1)
    else:
        frm, to = (px - length, py - half_h, z0), (px + back, py + half_h, z1)
    m.box(name, frm, to, mat, rotation=("z", -angle * side, (px, py, C)))
    a = math.radians(angle)
    return px + side * (length - 0.5) * math.cos(a), py - (length - 0.5) * math.sin(a)


def _lr(side, a, b):
    """x range from C + side * a to C + side * b, sorted."""
    return tuple(sorted((C + side * a, C + side * b)))


# ================================================================================================
# The tools
# ================================================================================================

SWORD_K = 0.75      # the greatsword is built at full size (tip at y 41.75) and shrunk by this about the grip


def magma_sword():
    """화산 대검: a broad basalt greatsword cracked with lava all over, narrowing in steps to a molten point; both edges
    molten orange with serrations biting out of them, a white-hot zigzag fissure up the middle; a wide basalt crossguard
    sweeping up into horns with red-hot tips round a lava gem, lava dripping off it; a long two-hand grip."""
    m = MagmaModel("magma_sword", SWORD_K)
    handle(m, low=-4.25, bands=(-2.5, 0.75))
    m.group("guard")
    m.box("guard", (3.75, 6.75, 6.5), (12.25, 9.5, 9.5), ROCK_FINE)
    m.box("guard_gem", (6.75, 7.0, 6.25), (9.25, 9.25, 9.75), LAVA_DEEP)
    m.box("guard_gem", (7.375, 7.375, 6.0), (8.625, 8.625, 10.0), WHITE_HOT)
    for side in (1, -1):
        x0, x1 = _lr(side, 4.25, 6.5)
        m.box("guard_wing", (x0, 7.0, 6.75), (x1, 10.0, 9.25), ROCK_FINE)
        x0, x1 = _lr(side, 5.75, 7.25)
        m.box("horn", (x0, 10.0, 7.0), (x1, 12.0, 9.0), ROCK_COLD)
        x0, x1 = _lr(side, 6.25, 7.25)
        m.box("horn_tip", (x0, 12.0, 7.25), (x1, 13.25, 8.75), EMBER)
        x0, x1 = _lr(side, 6.5, 7.25)
        m.box("horn_tip", (x0, 13.25, 7.5), (x1, 14.0, 8.5), LAVA)
        drip(m, C + side * 5.25, 7.0, big=side > 0)
    m.group("blade")
    # stepped rows to the point: (y0, y1, half width, half depth)
    rows = ((9.5, 17, 4.0, 1.25), (17, 25, 3.75, 1.25), (25, 32, 3.5, 1.125), (32, 35.5, 3.0, 1.0),
            (35.5, 38, 2.25, 0.875), (38, 40, 1.5, 0.75))
    for y0, y1, hw, hz in rows:
        m.box("blade", (C - hw, y0, C - hz), (C + hw, y1, C + hz), ROCK)
        for side in (1, -1):        # the molten edge along both sides
            x0, x1 = _lr(side, hw, hw + 0.75)
            m.box("edge", (x0, y0 + 0.25 if y0 > 9.5 else y0, C - 0.5), (x1, y1, C + 0.5), LAVA_DEEP)
    m.box("point", (C - 1.0, 40, C - 0.5), (C + 1.0, 41.0, C + 0.5), LAVA_DEEP)
    m.box("point", (C - 0.5, 41.0, C - 0.375), (C + 0.5, 41.75, C + 0.375), LAVA)
    # serrations: hooked teeth leaning up out of both edges, a yellow-hot tip
    for y in (12, 17.5, 23, 28.5):
        hw = next(r[2] for r in rows if r[0] <= y < r[1])
        for side in (1, -1):
            yy = y + (2.5 if side < 0 else 0)
            x0, x1 = _lr(side, hw + 0.75, hw + 1.5)
            m.box("tooth", (x0, yy, C - 0.375), (x1, yy + 1.75, C + 0.375), LAVA_RED)
            x0, x1 = _lr(side, hw + 1.5, hw + 2.0)
            m.box("tooth_tip", (x0, yy + 1.0, C - 0.25), (x1, yy + 1.75, C + 0.25), LAVA)
    # the fissure: a white-hot zigzag up the middle, proud of both faces
    segs = ((7.5, 8.5, 9.5, 14, 1.375), (7.0, 8.0, 14, 18.5, 1.375), (7.75, 8.75, 18.5, 23, 1.375),
            (7.25, 8.25, 23, 27.5, 1.375), (7.75, 8.75, 27.5, 31.75, 1.375), (7.375, 8.375, 31.75, 36.5, 1.25))
    for x0, x1, y0, y1, hz in segs:
        m.box("fissure", (x0, y0, C - hz), (x1, y1, C + hz), LAVA)
    m.box("fissure_glow", (7.625, 10, C - 1.5), (8.375, 13, C + 1.5), WHITE_HOT)
    return _long(m, "sword")


def magma_pickaxe():
    """분화구 곡괭이: a basalt pick head cracked with lava, its arms drooping in two bends to molten points; on its crown a
    little volcano: a stepped cone with a lava pool in its crater running down its front, a puff of smoke above;
    steel straps where the arms leave the head."""
    m = MagmaModel("magma_pickaxe")
    handle(m)
    shaft(m, 15.25)
    m.group("head")
    m.box("head", (4.75, 14.75, 6.25), (11.25, 19.25, 9.75), ROCK_FINE)
    for side in (1, -1):
        x0, x1 = _lr(side, 3.0, 3.5)
        m.box("strap", (x0, 14.5, 6.0), (x1, 19.5, 10.0), STEEL_LIGHT)
        p = (C + side * 3.25, 17.25)
        p = _arm(m, "arm_a", side, p, 4.5, 1.75, 6.625, 9.375, 22.5, ROCK_FINE)
        p = _arm(m, "arm_b", side, p, 3.75, 1.375, 6.875, 9.125, 45, ROCK_FINE)
        px, py = p
        rot = ("z", -45 * side, (px, py, C))
        if side > 0:
            m.box("arm_hot", (px + 0.25, py - 1.125, 7.125), (px + 1.75, py + 1.125, 8.875), LAVA_DEEP, rotation=rot)
            m.box("arm_tip", (px + 1.75, py - 0.625, 7.375), (px + 2.75, py + 0.625, 8.625), LAVA, rotation=rot)
        else:
            m.box("arm_hot", (px - 1.75, py - 1.125, 7.125), (px - 0.25, py + 1.125, 8.875), LAVA_DEEP, rotation=rot)
            m.box("arm_tip", (px - 2.75, py - 0.625, 7.375), (px - 1.75, py + 0.625, 8.625), LAVA, rotation=rot)
    m.group("volcano")
    m.box("cone", (C - 2.75, 19.25, C - 1.5), (C + 2.75, 20.75, C + 1.5), ROCK_COLD)
    m.box("cone", (C - 2.0, 20.75, C - 1.125), (C + 2.0, 22.0, C + 1.125), ROCK_COLD)
    m.box("crater", (C - 1.25, 22.0, C - 0.75), (C + 1.25, 22.5, C + 0.75), LAVA)
    m.box("crater_rim", (C - 2.0, 22.0, C - 1.125), (C - 1.25, 22.75, C + 1.125), ROCK_COLD)
    m.box("crater_rim", (C + 1.25, 22.0, C - 1.125), (C + 2.0, 22.75, C + 1.125), ROCK_COLD)
    for s in (1, -1):        # the lava running over the lip, down the front and the back
        z0, z1 = sorted((C + s * 1.125, C + s * 1.375))
        m.box("flow", (C - 0.5, 20.75, z0), (C + 0.5, 22.25, z1), LAVA_DEEP)
        z0, z1 = sorted((C + s * 1.5, C + s * 1.75))
        m.box("flow", (C - 0.25, 19.5, z0), (C + 0.75, 21.0, z1), LAVA_DEEP)
        z0, z1 = sorted((C + s * 1.75, C + s * 2.0))
        m.box("flow", (C + 0.25, 18.0, z0), (C + 1.0, 19.75, z1), LAVA_RED)
    m.group("smoke")
    m.box("smoke", (C - 1.0, 22.75, C - 0.75), (C + 0.75, 24.0, C + 0.75), SMOKE_DARK)
    m.box("smoke", (C - 0.25, 24.0, C - 0.625), (C + 1.5, 25.25, C + 0.625), SMOKE)
    m.box("spark", (C - 1.75, 24.25, C - 0.25), (C - 1.25, 24.75, C + 0.25), LAVA)
    return _held(m, "pickaxe")


def magma_shovel():
    """마그마 국자 삽: a basalt spade dished like a ladle: a raised crust rim round its face holds a pool of glowing magma,
    a cooled floe and white-hot bubbles on it, magma spilling over both shoulders in drips; a molten point, a basalt
    spine on the back, a steel socket."""
    m = MagmaModel("magma_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.25, 17.0, STEEL, 0.75)
    m.box("socket_rim", (6.5, 16.375, 6.0), (9.5, 16.875, 10.0), STEEL_LIGHT)
    rows = ((17, 18.25, 3.5), (18.25, 20, 4.25), (20, 25, 4.75), (25, 26.5, 4.25), (26.5, 28, 3.5), (28, 29.25, 2.5),
            (29.25, 30.25, 1.5))
    for y0, y1, hw in rows:
        m.box("blade", (C - hw, y0, 7.0), (C + hw, y1, 9.0), ROCK)
    m.box("point", (C - 0.75, 30.25, 7.25), (C + 0.75, 31.25, 8.75), LAVA_DEEP)
    m.box("point", (C - 0.375, 31.25, 7.5), (C + 0.375, 31.875, 8.5), LAVA)
    # the rim standing proud of the front (+z) all round, the dish inside it, magma pooled in its lower half
    z0, z1 = 9.0, 9.75
    m.box("rim", (C - 3.5, 17.0, z0), (C + 3.5, 18.25, z1), ROCK_RIM)
    m.box("rim", (C - 4.25, 18.25, z0), (C + 4.25, 19.25, z1), ROCK_RIM)
    for y0, y1, hw in rows[1:6]:
        lo = max(y0, 19.25)
        for side in (1, -1):
            x0, x1 = _lr(side, hw - 1, hw)
            m.box("rim", (x0, lo, z0), (x1, y1, z1), ROCK_RIM)
    m.box("rim", (C - 1.5, 28.25, z0), (C + 1.5, 30.25, z1), ROCK_RIM)
    m.box("pool", (C - 3.25, 19.25, 9.0), (C + 3.25, 20.0, 9.4), LAVA_RED)
    m.box("pool", (C - 3.75, 20.0, 9.0), (C + 3.75, 22.0, 9.4), LAVA_DEEP)
    m.box("pool_top", (C - 3.75, 22.0, 9.0), (C + 0.25, 22.75, 9.3), LAVA)
    m.box("pool_top", (C + 0.25, 22.0, 9.0), (C + 3.75, 23.5, 9.3), LAVA)
    m.box("bubble", (C - 1.75, 20.5, 9.4), (C - 1.0, 21.25, 9.75), WHITE_HOT)
    # spilling over the shoulders and dripping
    for side, big in ((1, True), (-1, False)):
        x0, x1 = _lr(side, 3.75, 4.5)
        m.box("spill", (x0, 18.0, 9.75), (x1, 19.75, 10.0), LAVA_DEEP)
        x0, x1 = _lr(side, 4.25, 4.75)
        m.box("spill", (x0, 16.5, 9.0), (x1, 18.25, 9.875), LAVA_DEEP)
        if big:
            m.box("spill", (x0 - 0.125, 15.75, 9.0), (x1 + 0.125, 16.5, 10.0), LAVA)
    m.box("spine", (C - 0.75, 17.0, 6.6), (C + 0.75, 27.5, 7.0), ROCK_COLD)
    return _held(m, "shovel")


def magma_axe():
    """불새 날개 도끼: a phoenix wing for a blade: a basalt head on the haft, and fanned out from it to one side (-x) a row
    of feathers that burn from red-hot through orange to a white-hot cutting edge, the top feather sweeping up like a
    wing tip; behind the haft a firebird head with a molten beak, glowing eyes and a flame crest; a steel cap."""
    m = MagmaModel("magma_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    m.box("head", (5.25, 14.0, 6.25), (10.75, 21.0, 9.75), ROCK_FINE)
    m.box("head_band", (4.75, 13.75, 6.0), (5.25, 21.25, 10.0), STEEL_LIGHT)
    round_y(m, "cap", 6.5, 9.5, 6.5, 9.5, 21.0, 22.0, STEEL, 0.75)
    m.box("cap_gem", (7.375, 22.0, 7.375), (8.625, 22.75, 8.625), LAVA)
    m.group("wing")
    # the blade: columns out from the head (-x), each taller than the last (the edge flares like a wing), burning
    # from basalt through red-hot and orange to a white-hot cutting edge; feather tips hang from the bottom
    cols = ((2.75, 5.0, 13.5, 21.5, ROCK), (1.25, 2.75, 12.75, 22.75, EMBER), (-0.25, 1.25, 12.0, 24.0, LAVA_RED),
            (-1.25, -0.25, 11.5, 25.0, LAVA_DEEP))
    for k, (x0, x1, y0, y1, mat) in enumerate(cols):
        dz = 0.25 * k
        m.box("blade", (x0, y0, 6.75 + dz), (x1, y1, 9.25 - dz), mat)
    m.box("edge", (-1.875, 12.0, 7.75), (-1.25, 24.5, 8.25), LAVA)
    m.box("edge_tip", (-1.75, 25.0, 7.625), (-0.75, 26.0, 8.375), LAVA)
    m.box("edge_tip", (-1.75, 10.75, 7.625), (-0.75, 11.5, 8.375), LAVA)
    for x0, x1, y, mat in ((3.0, 4.25, 13.5, EMBER), (1.5, 2.5, 12.75, LAVA_RED), (0.0, 1.0, 12.0, LAVA_DEEP)):
        m.box("feather_tip", (x0, y - 1.0, 7.375), (x1, y, 8.625), mat)
    for x0, x1, y, mat in ((3.25, 4.5, 21.5, EMBER), (1.5, 2.5, 22.75, LAVA_RED)):
        m.box("feather_crest", (x0, y, 7.375), (x1, y + 0.75, 8.625), mat)
    # feather lines on both faces
    for x0, x1, y0, y1 in ((2.875, 4.75, 15.25, 15.75), (2.875, 4.75, 18.0, 18.5), (1.375, 2.625, 16.5, 17.0), (1.375, 2.625, 19.75, 20.25)):
        m.box("feather_line", (x0, y0, 6.5), (x1, y1, 9.5), LAVA_DEEP)
    m.group("firebird")
    m.box("bird", (10.75, 15.25, 6.75), (12.75, 19.75, 9.25), ROCK_COLD)
    m.box("beak", (12.75, 16.0, 7.25), (14.0, 17.5, 8.75), LAVA_DEEP)
    m.box("beak_tip", (14.0, 16.0, 7.5), (14.75, 16.75, 8.5), LAVA)
    m.box("eye", (11.5, 18.0, 6.5), (12.25, 18.75, 9.5), LAVA)
    m.box("crest", (10.75, 19.75, 7.25), (12.25, 21.0, 8.75), EMBER)
    m.box("crest", (11.5, 21.0, 7.5), (12.75, 22.25, 8.5), LAVA_DEEP)
    m.box("crest", (12.25, 22.25, 7.75), (13.0, 23.0, 8.25), LAVA)
    return _held(m, "axe")


def magma_hoe():
    """화염 괭이: the vanilla hoe's L: a straight flat basalt bar standing out to one side (-x) from the haft's top,
    a lava vein along it, a steel band, then the blade block ending flat in a molten edge; three small flames rising
    from the bar's top edge; a steel cap with a lava gem on the haft. Nothing droops or hooks."""
    m = MagmaModel("magma_hoe")
    handle(m)
    shaft(m, 20.5)
    m.group("head")
    m.box("bar", (1.25, 19.75, 6.75), (9.75, 22.5, 9.25), ROCK_FINE)
    m.box("vein", (1.5, 20.75, 6.5), (9.25, 21.25, 9.5), LAVA_DEEP)
    round_y(m, "cap", 6.5, 9.5, 6.5, 9.5, 22.5, 23.25, STEEL, 0.75)
    m.box("cap_gem", (7.375, 23.25, 7.375), (8.625, 24.0, 8.625), LAVA)
    m.box("bar_end", (9.75, 20.0, 7.0), (10.25, 22.25, 9.0), STEEL_LIGHT)
    m.box("band", (0.75, 19.5, 6.5), (1.25, 22.75, 9.5), STEEL_LIGHT)
    m.box("blade", (-1.25, 19.5, 6.0), (0.75, 22.75, 10.0), ROCK)
    m.box("blade_edge", (-2.0, 19.75, 6.25), (-1.25, 22.5, 9.75), LAVA_DEEP)
    m.box("blade_edge", (-2.375, 20.25, 6.75), (-2.0, 22.0, 9.25), LAVA)
    m.group("flames")
    for x0, h in ((1.75, 1.75), (3.375, 2.5), (5.0, 1.5)):
        m.box("flame", (x0, 22.5, 7.25), (x0 + 1.25, 22.5 + h * 0.6, 8.75), LAVA_DEEP)
        m.box("flame_tip", (x0 + 0.25, 22.5 + h * 0.6, 7.5), (x0 + 1.0, 22.5 + h, 8.5), LAVA)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MagmaModel(name)
    handle(m)
    m.group("reel")
    # a basalt reel beside the rod's foot (+x): a red-hot ring round a molten hub, a steel crank with an ember knob
    m.box("reel_foot", (9.0, 7.5, 7.25), (10.0, 9.5, 8.75), STEEL)
    cyl(m, "reel", "z", (12.75, 8.5), 6, 6.5, 9.5, ROCK_COLD)
    cyl(m, "reel_ring", "z", (12.75, 8.5), 4, 6.25, 9.75, EMBER, cap=EMBER)
    m.box("reel_hub", (12.0, 7.75, 6.0), (13.5, 9.25, 10.0), LAVA)
    m.box("crank", (12.25, 8.0, 10.0), (13.25, 11.25, 10.75), STEEL_LIGHT)
    m.box("crank_knob", (12.0, 10.5, 10.75), (13.5, 12.0, 12.0), LAVA_DEEP)
    m.group("whip")
    # the lash: charred braided segments thinning and swaying a little upwards, red-hot knots between them
    segs = ((6.75, 11.5, 0.0, 1.25), (11.5, 16, 0.25, 1.125), (16, 20.5, 0.5, 1.0), (20.5, 24.5, 0.25, 0.875),
            (24.5, 27.25, 0.0, 0.75), (27.25, 29.25, -0.25, 0.625))
    for y0, y1, dx, hw in segs:
        m.box("lash", (C + dx - hw, y0, C - hw), (C + dx + hw, y1, C + hw), WHIP)
    for i, (y0, y1, dx, hw) in enumerate(segs[1:]):
        m.box("knot", (C + dx - hw - 0.25, y0 - 0.25, C - hw - 0.25), (C + dx + hw + 0.25, y0 + 0.5, C + hw + 0.25),
              LAVA_DEEP if i % 2 else EMBER)
    m.box("guide", (9.25, 17.75, 7.5), (10.5, 19.0, 8.5), STEEL_LIGHT)
    m.box("guide", (8.75, 25.75, 7.625), (9.75, 26.75, 8.375), STEEL_LIGHT)
    m.group("flame")
    m.box("flame_base", (6.5, 29.25, 7.0), (8.75, 30.25, 9.0), EMBER)
    m.box("flame", (6.75, 30.25, 7.25), (8.5, 31.25, 8.75), LAVA_DEEP)
    m.box("flame", (7.25, 31.25, 7.5), (8.25, 32.0, 8.5), LAVA)
    m.box("flame_heart", (7.25, 29.5, 6.75), (8.0, 30.75, 9.25), WHITE_HOT)
    m.box("flame_lick", (6.0, 29.75, 7.625), (6.5, 31.0, 8.375), LAVA_DEEP)
    m.box("flame_lick", (8.75, 30.0, 7.625), (9.25, 31.5, 8.375), LAVA)
    if not cast:
        m.group("line")
        top = (7.75, 30.5, C)
        rot = ("z", 45, top)
        m.box("line", (7.5, 19.75, 7.75), (8.0, 30.5, 8.25), LINE, rotation=rot)
        # a magma rock float on the line: a steel eye, cracked rock, a glowing band, an ember drop below
        m.box("float_eye", (7.0, 19.0, 7.5), (8.5, 19.75, 8.5), STEEL_LIGHT, rotation=rot)
        m.box("float_top", (6.0, 17.5, 6.75), (9.5, 19.0, 9.25), ROCK_COLD, rotation=rot)
        m.box("float_band", (5.75, 16.75, 6.5), (9.75, 17.5, 9.5), LAVA, rotation=rot)
        m.box("float_bottom", (6.0, 15.0, 6.75), (9.5, 16.75, 9.25), ROCK_COLD, rotation=rot)
        m.box("float_drop", (7.0, 14.0, 7.75), (8.5, 15.0, 8.25), EMBER, rotation=rot)
    return _held(m, "rod")


def magma_fishing_rod():
    """불꽃 채찍 낚싯대: a charred braided whip for a rod: the lash thinning and swaying a little upwards with red-hot
    knots, a flame at its tip, a basalt reel with a molten hub; a magma rock float on the line."""
    return _rod("magma_fishing_rod", False)


def magma_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("magma_fishing_rod_cast", True)
    m.display = magma_fishing_rod().display
    return m


BOBBER_WATERLINE = 4.0        # the glowing band: where the water's surface is (model y)


def magma_bobber():
    """용암 찌: a small magma rock float for an item display on the hook. A cracked basalt lump, a glowing lava band at
    the waterline, a molten crown with a steel eye on top. Stands on y = 0 about x = z = 8."""
    m = MagmaModel("magma_bobber")
    m.group("float")
    cyl(m, "keel", "y", (C, C), 2, 0, 1, EMBER, cap=EMBER)
    cyl(m, "belly", "y", (C, C), 4, 1, 2, ROCK_COLD)
    cyl(m, "belly", "y", (C, C), 6, 2, 3.5, ROCK_COLD)
    cyl(m, "band", "y", (C, C), 6, 3.5, 4.5, LAVA, cap=LAVA)
    cyl(m, "top", "y", (C, C), 6, 4.5, 7, ROCK_COLD)
    cyl(m, "top", "y", (C, C), 4, 7, 8.25, ROCK_COLD)
    m.box("crown", (7, 8.25, 7), (9, 9, 9), LAVA)
    m.box("eye", (7.25, 9, 7.625), (8.75, 10, 8.375), STEEL_LIGHT)
    m.display = presets.furniture(gui_scale=1.3, gui_translation=(0, 3.25, 0))
    m.kind = "bobber"
    return m


ALL = {f.__name__: f for f in (magma_sword, magma_pickaxe, magma_shovel, magma_axe, magma_hoe, magma_fishing_rod,
                               magma_fishing_rod_cast, magma_bobber)}

ROWS = [
    ("magma_sword", "화산 대검"), ("magma_pickaxe", "분화구 곡괭이"), ("magma_shovel", "마그마 국자 삽"),
    ("magma_axe", "불새 날개 도끼"), ("magma_hoe", "화염 괭이"), ("magma_fishing_rod", "불꽃 채찍 낚싯대"),
    ("magma_fishing_rod_cast", "낚싯대 (던진 뒤)"), ("magma_bobber", "용암 찌 (찌에 태우는 모델)"),
]

TITLE = "용암 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("magma_sword", "magma_pickaxe", "magma_shovel", "magma_axe", "magma_hoe", "magma_bobber")}
    out["magma_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/magma_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/magma_fishing_rod_cast"},
    }}
    return out
