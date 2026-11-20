"""
용암 시리즈 (magma series): a premium tool skin set, every tool a volcanic stand-in for the real one.

    magma_sword          화산 대검        a broad serrated obsidian greatsword split by a lava fissure (blade 1.6x the toys')
    magma_pickaxe        분화구 곡괭이    a smoking volcano cone, its base spreading into two glowing prongs
    magma_shovel         마그마 국자 삽   a deep iron ladle brimming with molten magma
    magma_axe            불새 날개 도끼   a phoenix wing of obsidian feathers burning to flame at their tips, a firebird head behind
    magma_hoe            화염 괭이        a straight flat ㄱ head of black rock with flames rising along its top edge
    magma_fishing_rod    불꽃 채찍 낚싯대 a braided whip with a coiled lash at its foot and a flame at its tip (+ _cast)
    magma_bobber         용암 찌          a little magma rock float

Same frame, grip and display transforms as the toy series (models/toys.py: built upright on the axis x = z = 8, the hand
on GRIP_Y, _held() puts the sprite's 45 degree tilt into the transforms). The greatsword is longer than a model may be
(elements end at y = 32): it is built at full size through LongModel, which shrinks every box about the grip point by
k as it is added (the faces painted at their full size first, so the texels stay one a unit), and its held views are
then enlarged about the grip by 1 / k, so in the hand it is full size again.

Looks: black-violet obsidian rock (its faces painted with orange crack lines, a texel a model unit), and on it the
lava itself as separate glowing boxes (light_emission 15, unshaded): fissures, ember-red serrations, flames, magma.
Dark iron fittings, a charred leather grip bound with iron bands, an obsidian pommel with a glowing core.
"""

import random
import zlib

from PIL import Image

from economy_machines import MModel, Mat, _face_size, bands, cyl
from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.palette import ramp

from models.toys import C, GRIP, GRIP_Y, SIZES, _held, round_y  # noqa: F401  (SIZES: the frame this set shares)

S = 16


# volcanic glass: near black with a violet sheen (darker and less blue than the palette's "obsidian" metal)
OBSIDIAN = [(16, 11, 24, 255), (27, 18, 38, 255), (41, 27, 57, 255), (61, 41, 82, 255), (88, 62, 112, 255)]


class LavaRock(Mat):
    """Obsidian painted per face at the face's size: a dark violet speckle with a 1 px bevel, split by one or two
    jagged crack lines of bright lava (orange with yellow-hot pixels) along the face's longer side."""

    def __init__(self, base=1, cracks=True):
        super().__init__("obsidian", "plain", base, bevel=(1, -1), noise=0)
        self.cracks = cracks

    def __call__(self, **changes):
        m = LavaRock(self.base, self.cracks)
        m.bevel = changes.get("bevel", self.bevel)
        return m

    def face(self, w, h, face, seed):
        pw, ph = max(1, round(w + 0.49)), max(1, round(h + 0.49))
        tile = pw > S or ph > S
        if tile:
            pw = ph = S
        rnd = random.Random(zlib.crc32(f"lava|{self.base}|{self.cracks}|{bool(self.bevel)}|{pw}x{ph}|{face}|{seed}".encode()))
        b = self.base
        grid = [[b] * S for _ in range(S)]
        for y in range(ph):
            for x in range(pw):
                r = rnd.random()
                g = b - 1 if r < 0.16 else b + 1 if r < 0.24 else b
                if not tile:
                    if self.bevel and ((y == 0 and ph > 1) or (x == 0 and pw > 1)):
                        g = b + 1
                    if self.bevel and ((y == ph - 1 and ph > 1) or (x == pw - 1 and pw > 1)):
                        g = b - 1
                grid[y][x] = max(0, min(len(OBSIDIAN) - 1, g))
        hot = {}
        if self.cracks and min(pw, ph) >= 4 and max(pw, ph) >= 6:
            vertical = ph >= pw or face not in ("up", "down")      # on the sides a crack runs down the height
            L, A = (ph, pw) if vertical else (pw, ph)
            n = 1
            for k in range(n):
                a = rnd.randrange(1, A - 1) if n == 1 else (rnd.randrange(1, max(2, A // 2)) if k == 0 else rnd.randrange(A // 2, A - 1))
                start = rnd.randrange(0, max(1, L // 4))
                end = L - rnd.randrange(0, max(1, L // 4))
                for t in range(start, end):
                    if t > start and rnd.random() < 0.45:
                        a = max(1, min(A - 2, a + rnd.choice((-1, 1))))
                    p = (a, t) if vertical else (t, a)
                    hot[p] = 3 if rnd.random() < 0.25 else 2
                    # a short branch now and then
                    if rnd.random() < 0.12:
                        bx = max(1, min(A - 2, a + rnd.choice((-1, 1))))
                        q = (bx, t) if vertical else (t, bx)
                        hot.setdefault(q, 1)
        im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        px = im.load()
        for y in range(S):
            for x in range(S):
                px[x, y] = ramp("fire", hot[(x, y)]) if (x, y) in hot else OBSIDIAN[grid[y][x]]
        key = "lava_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, (None if tile else [0, 0, round(w, 4), round(h, 4)])


ROCK = LavaRock(1)                                   # the cracked obsidian of the blades and heads
ROCK_PLAIN = LavaRock(1, cracks=False)               # small rock parts (tips, horns)
ROCK_LIGHT = LavaRock(2, cracks=False)
LAVA = Mat("fire", "plain", 4, bevel=(1, -1), noise=0, glow=True)        # the molten core: yellow-hot
LAVA_DEEP = Mat("fire", "plain", 3, bevel=(1, -1), noise=0, glow=True)   # flowing lava: orange
EMBER = Mat("signal_red", "plain", 1, bevel=None, noise=0, glow=True)    # hot edges: ember orange-red
EMBER_RED = Mat("signal_red", "plain", 0, bevel=None, noise=0, glow=True)   # the cooling red rim
IRON = Mat("iron", "metal", 2, bevel=(1, -1), noise=0)
IRON_DARK = Mat("iron", "metal", 1, bevel=(1, -1), noise=0)
IRON_BAND = Mat("blacksteel", "metal", 3, bevel=(1, -1), noise=0)
LEATHER = Mat("soot", "weave", 1, bevel=(0, -1), noise=0.05)              # charred leather wrap
ROD = Mat("iron", "metal", 1, bevel=(1, -1), noise=0)
LINE = Mat("glow_amber", "plain", 1, bevel=None, noise=0)
WHIP = Mat("leather", "weave", 1, bevel=(1, -1), noise=0.05)              # the braided lash
WHIP_DARK = Mat("soot", "plain", 0, bevel=None, noise=0)
SMOKE = Mat("stone", "plain", 3, bevel=(1, -1), noise=0.06)
SMOKE_DARK = Mat("stone", "plain", 2, bevel=(1, -1), noise=0.06)
WHITE_HOT = Mat("fire", "plain", 5, bevel=None, noise=0, glow=True)      # a bubble bursting, a flame's heart


# ================================================================================================
# A model built bigger than the frame allows
# ================================================================================================

class LongModel(MModel):
    """An MModel whose boxes shrink by k about the grip point (8, GRIP_Y, 8) as they are added, painted first at their
    full size. _long() then enlarges the held views by 1 / k, so in the hand the model is its full size."""

    def __init__(self, name, k):
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
            if isinstance(t, Mat):
                w, h = _face_size(f, frm, to)
                fs[f], region = t.face(w, h, f, zlib.crc32(f"{round(w, 2)}x{round(h, 2)}/{f}".encode()))
                if region and f not in uv and f not in full_uv:
                    uv[f] = region
                glow = glow or t.glow
        if rotation:
            rotation = (rotation[0], rotation[1], self._p(rotation[2]))
        first = next((t for t in fs.values() if t is not None), None)
        return super().box(name, self._p(frm), self._p(to), first, faces=fs, full_uv=full_uv, glow=glow,
                           rotation=rotation, uv=uv, side_v=side_v, shade=shade)


def _long(m, kind):
    _held(m, kind)
    held = ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand")
    m.display.update(presets.enlarged({c: m.display[c] for c in held}, 1 / m.k, GRIP))
    return m


# ================================================================================================
# Shared parts
# ================================================================================================

def handle(m, low=-1.25, rings=(0.25, 3.0)):
    """Obsidian pommel with a glowing ring and core, charred leather grip from low with iron bands, an iron collar
    with a hot rim (up to y 6.75)."""
    m.group("handle")
    p = low - 2
    m.box("pommel_spike", (7.25, p - 0.75, 7.25), (8.75, p, 8.75), IRON_DARK)
    m.box("pommel_core", (7.5, p - 1.5, 7.5), (8.5, p - 0.75, 8.5), LAVA)
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, p, low, ROCK, 0.75)
    m.box("pommel_glow", (6.2, p + 0.5, 6.2), (9.8, p + 1.5, 9.8), EMBER)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, low, 5.25, LEATHER, 0.5)
    for y in rings:
        round_y(m, "grip_band", 6.5, 9.5, 6.5, 9.5, y, y + 0.75, IRON_BAND, 0.5)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, IRON, 1)
    m.box("collar_rim", (6.5, 6.25, 6.5), (9.5, 6.75, 9.5), EMBER_RED)


def shaft(m, top, ring=True):
    """The iron haft above the collar, up to y = top, with a glowing crack ring part way up."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, IRON_DARK, 0.5)
    if ring:
        mid = 6.5 + (top - 6.5) * 0.42
        m.box("shaft_ring", (6.625, mid, 6.625), (9.375, mid + 0.75, 9.375), ROCK_PLAIN)
        m.box("shaft_ring_glow", (6.45, mid + 0.75, 6.45), (9.55, mid + 1.0, 9.55), EMBER)


def drip(m, x, y, z=C, big=True):
    """A molten drip hanging from y: a bead under a thin neck."""
    m.box("drip", (x - 0.5, y - 1, z - 0.5), (x + 0.5, y, z + 0.5), LAVA_DEEP)
    if big:
        m.box("drip", (x - 0.375, y - 1.75, z - 0.375), (x + 0.375, y - 1, z + 0.375), LAVA)


def fissure(m, segs, z0, z1, mat=None):
    """A zigzag lava crack: segments (x0, x1, y0, y1), each a little off the last, glowing through the rock."""
    for x0, x1, y0, y1 in segs:
        m.box("fissure", (x0, y0, z0), (x1, y1, z1), mat or LAVA_DEEP)


def _rows(d):
    """A stepped circle of even diameter d a unit row at a time: {row: half width}, rows -d/2 .. d/2 - 1."""
    out = {}
    for a, b, hw in bands(d):
        for r in range(round(a), round(b)):
            out[r] = hw
    return out


def disc(m, name, cx, cy, d, z0, z1, mat, inset=0.0):
    """A round plate facing z (even diameter), its rows merged; inset shrinks it a little all round."""
    r = d // 2
    for a, b, hw in bands(d):
        a2 = a + inset if round(a) == -r else a
        b2 = b - inset if round(b) == r else b
        m.box(name, (cx - hw + inset, cy + a2, z0), (cx + hw - inset, cy + b2, z1), mat)


def ring(m, name, cx, cy, d_out, d_in, z0, z1, mat):
    """A round ring facing z: the stepped circle d_out with the circle d_in cut out of its middle."""
    o, i = _rows(d_out), _rows(d_in)
    rows = []
    for r in sorted(o):
        key = (o[r], i.get(r))
        if rows and rows[-1][2] == key:
            rows[-1][1] = r + 1
        else:
            rows.append([r, r + 1, key])
    for a, b, (ho, hi) in rows:
        if hi is None:
            m.box(name, (cx - ho, cy + a, z0), (cx + ho, cy + b, z1), mat)
        else:
            m.box(name, (cx - ho, cy + a, z0), (cx - hi, cy + b, z1), mat)
            m.box(name, (cx + hi, cy + a, z0), (cx + ho, cy + b, z1), mat)


# ================================================================================================
# The tools
# ================================================================================================

SWORD_K = 0.75      # the greatsword is built at full size (tip at y 41.75) and shrunk by this about the grip


def magma_sword():
    """화산 대검: a broad obsidian greatsword (its blade 1.6 times the toy sword's, from the grip) narrowing in steps to
    its point, ember-red serrations biting out of both edges, a zigzag lava fissure from the guard to near the tip
    with side cracks; a wide rock crossguard whose ends sweep up into glowing horns round a molten gem; a long two-hand
    grip."""
    m = LongModel("magma_sword", SWORD_K)
    handle(m, low=-4.25, rings=(-2.75, 0.25, 3.0))
    m.group("guard")
    m.box("guard", (1.5, 6.5, 6.5), (14.5, 9, 9.5), ROCK)
    m.box("guard_core", (4.5, 6.2, 6.25), (11.5, 9.25, 9.75), IRON)
    m.box("guard_gem", (6.75, 6.75, 6), (9.25, 8.75, 10), LAVA)
    for side in (1, -1):
        lo, hi = sorted((C + side * 6.5, C + side * 8))
        m.box("horn", (lo, 9, 6.875), (hi, 11, 9.125), ROCK_PLAIN)
        lo, hi = sorted((C + side * 7, C + side * 8))
        m.box("horn", (lo, 11, 7.25), (hi, 12.5, 8.75), ROCK_PLAIN)
        lo, hi = sorted((C + side * 7.25, C + side * 8))
        m.box("horn_tip", (lo, 12.5, 7.5), (hi, 13.5, 8.5), EMBER)
    drip(m, 3, 6.5)
    drip(m, 13, 6.5, big=False)
    m.group("blade")
    # stepped rows to the point: (y0, y1, half width, half depth)
    rows = ((9, 15, 4.25, 1.25), (15, 21, 4.0, 1.25), (21, 27, 3.5, 1.0), (27, 32, 3.0, 1.0), (32, 36, 2.5, 1.0),
            (36, 39, 1.75, 0.875), (39, 40.75, 1.0, 0.75), (40.75, 41.75, 0.5, 0.5))
    for y0, y1, hw, hz in rows:
        m.box("blade", (C - hw, y0, C - hz), (C + hw, y1, C + hz), ROCK)
    # serrations: teeth leaning up out of both edges, hot
    for y in (10, 14, 18, 22, 26, 30, 34):
        hw = next(r[2] for r in rows if r[0] <= y < r[1])
        for side in (1, -1):
            lo, hi = sorted((C + side * hw, C + side * (hw + 1)))
            m.box("tooth", (lo, y, 7.5), (hi, y + 1.5, 8.5), EMBER)
            lo, hi = sorted((C + side * (hw + 1), C + side * (hw + 1.5)))
            m.box("tooth_tip", (lo, y + 0.75, 7.625), (hi, y + 1.75, 8.375), EMBER_RED)
    fissure(m, ((7.5, 8.5, 9.5, 13.5), (7, 8, 13.5, 17.5), (7.75, 8.75, 17.5, 22), (7.25, 8.25, 22, 26.5),
                (7.75, 8.75, 26.5, 30.5), (7.25, 8.25, 30.5, 34.5), (7.625, 8.375, 34.5, 38.5)), 6.6, 9.4)
    # side cracks branching off the fissure
    fissure(m, ((8.75, 10.75, 19.75, 20.5), (5.25, 7.25, 24.25, 25), (8.75, 10.25, 32, 32.75)), 6.65, 9.35, EMBER)
    m.box("fissure_hot", (7.7, 9.55, 6.55), (8.3, 12, 9.45), LAVA)
    return _long(m, "sword")


def magma_pickaxe():
    """분화구 곡괭이: a smoking volcano for a head: a stepped obsidian cone whose wide base runs out into two prongs with
    glowing points (the pick's arms), lava welling from the crater and running down its front in two streams, a
    column of grey smoke puffs above."""
    m = MModel("magma_pickaxe")
    handle(m)
    shaft(m, 15)
    m.group("cone")
    rows = ((14.5, 16.5, 7.0, 2.5), (16.5, 18.5, 5.5, 2.25), (18.5, 20.5, 4.25, 2.0), (20.5, 22, 3.25, 1.75))
    for y0, y1, hw, hz in rows:
        m.box("cone", (C - hw, y0, C - hz), (C + hw, y1, C + hz), ROCK)
    for side in (1, -1):        # the base's ends: prongs pointing down, glowing at the point
        lo, hi = sorted((C + side * 7, C + side * 4.75))
        m.box("prong", (lo, 12.75, 6.25), (hi, 14.5, 9.75), ROCK_PLAIN)
        lo, hi = sorted((C + side * 7, C + side * 5.5))
        m.box("prong_tip", (lo, 11.25, 7), (hi, 12.75, 9), LAVA_DEEP)
        lo, hi = sorted((C + side * 6.75, C + side * 5.75))
        m.box("prong_tip", (lo, 10.25, 7.5), (hi, 11.25, 8.5), LAVA)
    # the crater: a rim round a pool of lava
    m.box("rim", (C - 3.25, 22, C + 1.25), (C + 3.25, 22.75, C + 1.75), ROCK_PLAIN)
    m.box("rim", (C - 3.25, 22, C - 1.75), (C + 3.25, 23.25, C - 1.25), ROCK_PLAIN)
    m.box("rim", (C - 3.25, 22, C - 1.25), (C - 2.25, 23.25, C + 1.25), ROCK_PLAIN)
    m.box("rim", (C + 2.25, 22, C - 1.25), (C + 3.25, 23.25, C + 1.25), ROCK_PLAIN)
    m.box("crater", (C - 2.25, 22, C - 1.25), (C + 2.25, 23, C + 1.25), LAVA)
    # lava running down the front (and the back)
    for z0, z1 in ((C + 1.65, C + 1.9), (C - 1.9, C - 1.65)):
        m.box("flow", (C - 1, 20.5, z0), (C + 0.5, 22.75, z1), LAVA_DEEP)
    m.box("flow", (C - 1.5, 18.5, C + 1.9), (C - 0.5, 20.5, C + 2.15), LAVA_DEEP)
    m.box("flow", (C - 2.25, 16.5, C + 2.15), (C - 1.25, 18.5, C + 2.4), EMBER)
    m.box("flow", (C + 0.5, 18.5, C + 1.9), (C + 1.5, 20, C + 2.15), LAVA_DEEP)
    m.box("flow", (C + 1.25, 16.5, C + 2.15), (C + 2.25, 18.5, C + 2.4), EMBER)
    m.group("smoke")
    m.box("smoke", (C - 1.75, 23.25, C - 1.5), (C + 1.25, 25.75, C + 1.5), SMOKE_DARK)
    m.box("smoke", (C - 0.75, 25.75, C - 1.25), (C + 2.25, 28, C + 1.25), SMOKE)
    m.box("smoke", (C - 2.25, 28, C - 1), (C + 0.25, 30, C + 1), SMOKE)
    m.box("smoke", (C - 1.5, 30, C - 0.75), (C + 0.0, 31.25, C + 0.75), SMOKE)
    m.box("ember", (C + 1.75, 24.25, C - 0.25), (C + 2.25, 24.75, C + 0.25), LAVA)
    m.box("ember", (C - 2.75, 26.75, C - 0.25), (C - 2.25, 27.25, C + 0.25), LAVA_DEEP)
    return _held(m, "pickaxe")


def magma_shovel():
    """마그마 국자 삽: a deep iron ladle on the haft, full to just under its rim with molten magma (a bubble bursting on
    it), a pouring lip, magma spilling over the rim and dripping."""
    m = MModel("magma_shovel")
    handle(m)
    shaft(m, 17.75)
    m.group("ladle")
    cx, cy = C, 22.5
    disc(m, "bowl_back", cx, cy, 8, 5, 6.5, IRON_DARK)
    disc(m, "bowl", cx, cy, 10, 6.5, 9.25, IRON)
    disc(m, "magma", cx, cy, 8, 9.25, 9.9, LAVA, inset=0.05)
    ring(m, "rim", cx, cy, 10, 8, 9.25, 10.75, IRON)
    m.box("rim_hot", (cx - 3, cy + 4.75, 10.75), (cx + 3, cy + 5, 11), EMBER_RED)
    m.box("lip", (cx - 6.25, cy + 1, 6.75), (cx - 5, cy + 3.5, 10.5), IRON)
    m.box("lip_spill", (cx - 6.5, cy + 0.5, 7.5), (cx - 5.75, cy + 1, 9.75), LAVA_DEEP)
    m.box("bubble", (cx + 0.75, cy + 0.5, 9.9), (cx + 2, cy + 1.75, 10.2), WHITE_HOT)
    m.box("bubble", (cx - 2.25, cy - 1.75, 9.9), (cx - 1.5, cy - 1, 10.1), LAVA)
    m.box("spill", (cx + 2, cy - 4.5, 10.75), (cx + 3, cy - 2.5, 11), LAVA_DEEP)
    drip(m, cx + 2.5, cy - 4.75, z=10.25)
    drip(m, cx - 3.25, cy - 3.75, z=8, big=False)
    return _held(m, "shovel")


def magma_axe():
    """불새 날개 도끼: a phoenix wing for a blade: rows of obsidian feathers fanning out to one side (-x), longest at the
    top, each burning to ember and then yellow flame at its tip; behind the haft a small firebird head with a beak,
    a glowing eye and a flame crest."""
    m = MModel("magma_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 13.5, 21, IRON, 0.75)
    round_y(m, "shaft_cap", 6.75, 9.25, 6.75, 9.25, 21, 22.25, ROCK_PLAIN, 0.5)
    m.box("cap_flame", (7.5, 22.25, 7.5), (8.5, 23.5, 8.5), LAVA)
    m.group("wing")
    # feathers from the top: (y0, y1, x where the feather ends)
    feathers = ((21, 22.75, -2.25), (19.25, 21, -3.0), (17.5, 19.25, -2.5), (15.75, 17.5, -1.25), (14, 15.75, 0.5),
                (12.25, 14, 2.5))
    for k, (y0, y1, xe) in enumerate(feathers):
        z0, z1 = (7, 9) if k % 2 == 0 else (7.25, 8.75)
        m.box("feather", (xe + 3.25, y0, z0), (6.25, y1, z1), ROCK)
        m.box("feather_burn", (xe + 1.5, y0, z0 + 0.125), (xe + 3.25, y1, z1 - 0.125), EMBER)
        m.box("feather_tip", (xe, y0 + 0.25, z0 + 0.25), (xe + 1.5, y1 - 0.25, z1 - 0.25), LAVA)
    m.box("leading_edge", (-1.25, 22.75, 7.25), (6.25, 23.5, 8.75), ROCK_PLAIN)
    m.box("leading_tip", (-2.75, 22.75, 7.5), (-1.25, 24.75, 8.5), LAVA_DEEP)
    m.box("leading_tip", (-3.5, 24.75, 7.625), (-2.25, 25.75, 8.375), LAVA)
    m.group("firebird")
    m.box("bird_head", (9.75, 16, 6.75), (12.25, 19, 9.25), ROCK)
    m.box("beak", (12.25, 16.5, 7.5), (13.75, 17.75, 8.5), EMBER)
    m.box("beak_tip", (13.75, 16.5, 7.75), (14.5, 17.25, 8.25), LAVA_DEEP)
    m.box("eye", (11, 17.75, 9.25), (11.75, 18.5, 9.5), LAVA)
    m.box("eye", (11, 17.75, 6.5), (11.75, 18.5, 6.75), LAVA)
    m.box("crest", (10.25, 19, 7.5), (11.25, 20.5, 8.5), LAVA_DEEP)
    m.box("crest", (11.25, 19, 7.625), (12, 20, 8.375), EMBER)
    m.box("crest_tip", (10.5, 20.5, 7.75), (11, 21.5, 8.25), LAVA)
    return _held(m, "axe")


def magma_hoe():
    """화염 괭이: the vanilla hoe's L: a straight flat head of black rock standing out to one side (-x) from an iron
    socket on the haft's top, ending flat in a glowing edge; flames rising along its top edge, a lava vein along it.
    Nothing droops or hooks: the head is one straight bar, the flames stand up from it."""
    m = MModel("magma_hoe")
    handle(m)
    shaft(m, 19.5)
    m.group("head")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 19, 23, IRON, 0.75)
    m.box("socket_rim", (6, 21.25, 6), (10, 21.75, 10), EMBER_RED)
    m.box("head", (-1, 19.75, 6.25), (6.25, 22.75, 9.75), ROCK)
    fissure(m, ((3.25, 6.2, 20.75, 21.5), (0, 3.25, 21.25, 22)), 6.1, 9.9)
    m.box("rim", (-1, 19.5, 6.5), (6.25, 19.75, 9.5), EMBER_RED)
    m.box("edge", (-1.75, 19.75, 6.5), (-1, 22.75, 9.5), LAVA)
    m.group("flames")
    # tongues along the top edge, uneven: (x0, height)
    for x0, h in ((-0.75, 2.75), (0.5, 1.5), (1.75, 3.5), (3.0, 2.0), (4.25, 3.0), (5.5, 1.75), (6.75, 2.5)):
        m.box("flame", (x0, 22.75 if x0 < 6.25 else 23, 7.25), (x0 + 1.25, 22.75 + h * 0.6, 8.75), LAVA_DEEP)
        m.box("flame_tip", (x0 + 0.25, 22.75 + h * 0.6, 7.5), (x0 + 1.0, 22.75 + h, 8.5), LAVA)
    m.box("flame_root", (-0.6, 22.75, 7), (6.2, 23.25, 9), EMBER)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("coil")
    # the rest of the lash coiled beside the rod's foot (+x, where a reel would be), an ember glow inside the loop
    m.box("coil_foot", (9, 7.5, 7.25), (10, 9.5, 8.75), IRON_DARK)
    ring(m, "coil", 13, 8.5, 6, 4, 7, 9, WHIP)
    disc(m, "coil_glow", 13, 8.5, 4, 7.6, 8.4, EMBER_RED, inset=0.05)
    m.box("coil_end", (15.5, 5.25, 7.25), (16.5, 6.25, 8.75), WHIP)
    m.box("coil_end", (16, 4.25, 7.5), (16.75, 5.25, 8.5), EMBER)
    m.group("whip")
    # the lash: braided segments thinning upwards, each a little off the last (a whip's sway), ember knots between
    segs = ((6.5, 11, 0.0, 1.25), (11, 15.5, 0.25, 1.125), (15.5, 20, 0.5, 1.0), (20, 24, 0.25, 0.875),
            (24, 27.5, 0.0, 0.75), (27.5, 30, -0.25, 0.625))
    for y0, y1, dx, hw in segs:
        m.box("lash", (C + dx - hw, y0, C - hw), (C + dx + hw, y1, C + hw), WHIP)
    for y0, y1, dx, hw in segs[1:]:
        m.box("knot", (C + dx - hw - 0.25, y0 - 0.25, C - hw - 0.25), (C + dx + hw + 0.25, y0 + 0.5, C + hw + 0.25), EMBER)
    m.box("guide", (9.25, 17.5, 7.5), (10.25, 18.5, 8.5), IRON)
    m.box("guide", (8.75, 25.5, 7.625), (9.75, 26.5, 8.375), IRON)
    m.group("flame")
    m.box("flame", (7.0, 30, 7.25), (8.5, 31, 8.75), LAVA_DEEP)
    m.box("flame", (7.25, 31, 7.5), (8.25, 32, 8.5), LAVA)
    m.box("flame_lick", (6.5, 30.25, 7.625), (7.0, 31.25, 8.375), EMBER)
    m.box("flame_lick", (8.5, 30.5, 7.625), (9.0, 31.75, 8.375), EMBER)
    if not cast:
        m.group("line")
        top = (8, 30.75, C)
        rot = ("z", 45, top)
        m.box("line", (7.75, 19.75, 7.75), (8.25, 30.75, 8.25), LINE, rotation=rot)
        # a magma rock float on the line: an iron eye, cracked rock, a glowing band, an ember drop below
        m.box("float_eye", (7.25, 19, 7.5), (8.75, 19.75, 8.5), IRON, rotation=rot)
        m.box("float_top", (6.25, 17.5, 6.75), (9.75, 19, 9.25), ROCK_PLAIN, rotation=rot)
        m.box("float_band", (6, 16.75, 6.5), (10, 17.5, 9.5), LAVA, rotation=rot)
        m.box("float_bottom", (6.25, 15, 6.75), (9.75, 16.75, 9.25), ROCK_PLAIN, rotation=rot)
        m.box("float_drop", (7.25, 14, 7.75), (8.75, 15, 8.25), EMBER, rotation=rot)
    return _held(m, "rod")


def magma_fishing_rod():
    """불꽃 채찍 낚싯대: a braided fire whip for a rod: the lash thinning and swaying a little upwards with ember knots,
    a flame at its tip, the rest of it coiled at the foot; a magma rock float on the line."""
    return _rod("magma_fishing_rod", False)


def magma_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("magma_fishing_rod_cast", True)
    m.display = magma_fishing_rod().display
    return m


BOBBER_WATERLINE = 4.0        # the glowing band: where the water's surface is (model y)


def magma_bobber():
    """용암 찌: a small magma rock float for an item display on the hook. A rough cracked obsidian lump, a glowing lava
    band at the waterline, glowing cracks on four sides, a molten crown with an iron eye on top. Stands on y = 0
    about x = z = 8."""
    m = MModel("magma_bobber")
    m.group("float")
    cyl(m, "keel", "y", (C, C), 2, 0, 1, EMBER_RED, cap=EMBER_RED)
    cyl(m, "belly", "y", (C, C), 4, 1, 2, ROCK, cap=ROCK)
    cyl(m, "belly", "y", (C, C), 6, 2, 3.5, ROCK, cap=ROCK)
    cyl(m, "band", "y", (C, C), 6, 3.5, 4.5, LAVA, cap=LAVA)
    cyl(m, "top", "y", (C, C), 6, 4.5, 7, ROCK, cap=ROCK)
    cyl(m, "top", "y", (C, C), 4, 7, 8.25, ROCK, cap=ROCK)
    for face, frm, to in (("south", (7.5, 4.5, 11), (8.5, 7, 11.2)), ("north", (7.5, 4.5, 4.8), (8.5, 7, 5)),
                          ("east", (11, 4.5, 7.5), (11.2, 7, 8.5)), ("west", (4.8, 4.5, 7.5), (5, 7, 8.5))):
        m.box("crack", frm, to, LAVA_DEEP)
    m.box("crown", (7, 8.25, 7), (9, 9, 9), LAVA)
    m.box("eye", (7.25, 9, 7.625), (8.75, 10, 8.375), IRON)
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
