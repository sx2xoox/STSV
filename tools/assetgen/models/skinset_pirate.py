"""
해적 시리즈 (pirate series, a gold-shop tool skin set): old sea tools. Simple in concept, rich in detail; lighter than the
toys (about three quarters of their boxes).

    pirate_sword  pirate_pickaxe  pirate_shovel  pirate_axe  pirate_hoe  pirate_fishing_rod (+ pirate_fishing_rod_cast)

Same frame, sizes and display transforms as the toy series (models/toys.py: upright, the grip at GRIP_Y on x = z = 8,
_held(m, kind)). Every piece shares a light handle: a brass cap, a tarred-wood grip with two rope bands, a brass collar,
and (all but the cutlass, which carries a bone skull there) a knotted rope tail.

The detail is in the surfaces, painted per face at one texel a model unit like every Mat (Paint below): weathered iron
with pitting and rust patches, worn brass with a glint and verdigris in its crevices (the bottom and right edges and the
corners), tarred wood with dark grain streaks and a gloss line, twisted rope with light fibres, a pale cutlass blade with
nicks along its edge and scratches. The skull's face is printed twice as fine (like the toys' sticker).
No bobber (the anchor is the rod's look; the float stays vanilla).
"""

import math
import random
import zlib

from PIL import Image

from economy_machines import MModel, Mat
from models.toys import C, GRIP_Y, SIZES, _arm, _held, corners  # noqa: F401
from stsv_assets import textures as T
from stsv_assets.palette import ramp, size

TITLE = "해적 시리즈 (도구 스킨)"


# ================================================================================================
# Painted surfaces
# ================================================================================================

def _r(name, i):
    return ramp(name, max(0, min(size(name) - 1, i)))


class Paint(Mat):
    """A material whose faces are painted by fn(pw, ph, rnd, face) -> {(x, y): colour} (pw x ph texels, one a model unit)."""

    def __init__(self, tag, fn, ramp_name, base):
        super().__init__(ramp_name, "plain", base, bevel=None, noise=0)
        self.tag, self.fn = tag, fn

    def face(self, w, h, face, seed):
        pw, ph = min(16, max(1, math.ceil(w - 1e-6))), min(16, max(1, math.ceil(h - 1e-6)))
        rnd = random.Random(zlib.crc32(f"{self.tag}|{seed}".encode()))
        px_map = self.fn(pw, ph, rnd, face)
        im = Image.new("RGBA", (16, 16), _r(self.ramp, self.base))
        px = im.load()
        for (x, y), c in px_map.items():
            px[x, y] = c
        key = "pirate_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, [0, 0, round(min(w, 16), 4), round(min(h, 16), 4)]


def _bevel(i, x, y, pw, ph, light=0, dark=-1):
    d = 0
    if ph > 1 and y == 0:
        d += light
    if pw > 1 and x == 0:
        d += light
    if ph > 1 and y == ph - 1:
        d += dark
    if pw > 1 and x == pw - 1:
        d += dark
    return i + max(-1, min(1, d))


def _iron(base, rust=1.0):
    """Weathered iron: pitted (dark dots, a few deep), a light fleck here and there, rust patches nearer the edges."""
    def fn(pw, ph, rnd, face):
        out = {}
        idx = {}
        for y in range(ph):
            for x in range(pw):
                r = rnd.random()
                i = base - 2 if r < 0.04 else base - 1 if r < 0.15 else base + 1 if r < 0.22 else base
                idx[x, y] = _bevel(i, x, y, pw, ph, light=1 if x + y < 2 else 0)
                out[x, y] = _r("steel", idx[x, y])
        # rust: a patch or two of orange-brown, darker at its heart, mostly along the bottom and the edges
        n = int(rust * (0.6 + pw * ph / 30) * (0.4 + rnd.random()))
        for _ in range(n):
            cx = rnd.choice((0, pw - 1, rnd.randrange(pw)))
            cy = rnd.choice((ph - 1, rnd.randrange(ph), rnd.randrange(ph)))
            rad = rnd.choice((0.6, 1.0, 1.4))
            for y in range(ph):
                for x in range(pw):
                    d = math.hypot(x - cx, y - cy)
                    if d <= rad and rnd.random() < 0.85:
                        out[x, y] = _r("terracotta", 0 if d < 0.5 and rad > 1 else rnd.choice((1, 1, 2)))
                    elif d <= rad + 0.8 and rnd.random() < 0.3:
                        out[x, y] = _r("soil", 1)
        return out
    return fn


def _ground(pw, ph, rnd, face):
    """A ground steel edge: bright, a glint, a few dark nicks and a scratch."""
    out = {}
    for y in range(ph):
        for x in range(pw):
            i = 3 if x + y < 2 else 2
            out[x, y] = _r("blade", _bevel(i, x, y, pw, ph))
    for _ in range(max(1, ph // 4)):
        y = rnd.randrange(ph)
        out[0, y] = _r("steel", 2)
        if pw > 1 and rnd.random() < 0.5:
            out[1, y] = _r("blade", 0)
    return out


def _cutlass(base):
    """The cutlass: pale steel, a long sheen down its middle, nicks along both edges (dark notches with a bright burr),
    a couple of fine scratches and a fleck of rust low on the blade."""
    def fn(pw, ph, rnd, face):
        out = {}
        for y in range(ph):
            for x in range(pw):
                i = base + 1 if pw > 2 and x == pw // 2 - 1 else base
                out[x, y] = _r("blade", _bevel(i, x, y, pw, ph))
        if pw >= 3:
            for _ in range(max(1, ph // 3)):
                y = rnd.randrange(ph)
                x = 0 if face == "south" else pw - 1     # the edge's side (-x)
                out[x, y] = _r("steel", 4)
                if 0 <= y - 1:
                    out[x, y - 1] = _r("blade", 3)
            # a scratch: three texels on a diagonal
            if ph >= 4:
                sx, sy = rnd.randrange(1, max(2, pw - 1)), rnd.randrange(ph - 2)
                for k in range(3):
                    if 0 < sx + k < pw - 1 and sy + k < ph:
                        out[sx + k, sy + k] = _r("blade", 3 if k != 1 else 1)
            if rnd.random() < 0.4:
                out[rnd.randrange(1, pw - 1), ph - 1] = _r("copper", 2)
        return out
    return fn


def _brass(base, green=1.0):
    """Worn brass: a glint in the top left, darker bottom and right, verdigris gathered in the crevices (bottom and right
    edges, the corners) and a spot or two on the face."""
    def fn(pw, ph, rnd, face):
        out = {}
        for y in range(ph):
            for x in range(pw):
                i = base + 1 if x + y < min(pw, ph) * 0.45 else base
                if rnd.random() < 0.08:
                    i -= 1
                out[x, y] = _r("brass", _bevel(i, x, y, pw, ph))
                edge = (y == ph - 1 and ph > 1) or (x == pw - 1 and pw > 1)
                corner = (x in (0, pw - 1)) and (y in (0, ph - 1)) and pw > 1 and ph > 1
                p = 0.5 if corner else 0.22 if edge else 0.02
                if rnd.random() < p * green:
                    out[x, y] = _r("celadon", rnd.choice((0, 1, 1, 2)))
        return out
    return fn


def _tar(base):
    """Tarred wood: dark streaks of grain along y, a light gloss line where the tar shines, a knot now and then."""
    def fn(pw, ph, rnd, face):
        out = {}
        cols = [base + rnd.choice((0, 0, 0, -1, 1)) for _ in range(pw)]
        gloss = 1 if pw > 2 else -1
        for y in range(ph):
            for x in range(pw):
                i = cols[x]
                if x == gloss and rnd.random() < 0.75:
                    i = base + 1
                if face in ("up", "down"):
                    i = base - 1 if (x + y) % 3 == 0 else base     # end grain
                if rnd.random() < 0.05:
                    i -= 1
                out[x, y] = _r("wood_dark", _bevel(i, x, y, pw, ph))
        if ph > 6 and pw > 1 and face not in ("up", "down") and rnd.random() < 0.6:
            kx, ky = rnd.randrange(pw), rnd.randrange(1, ph - 1)
            out[kx, ky] = _r("wood_dark", 0)
            out[kx, ky - 1] = _r("wood_dark", base + 1)
        return out
    return fn


def _rope(base):
    """Twisted rope: the strands' grooves run on a diagonal, light fibres stick out."""
    def fn(pw, ph, rnd, face):
        out = {}
        for y in range(ph):
            for x in range(pw):
                k = (x + y * (1 if face in ("north", "east") else -1)) % 3
                i = base - 1 if k == 0 else base + 1 if k == 1 else base
                if rnd.random() < 0.1:
                    i = base + 2
                out[x, y] = _r("straw", _bevel(i, x, y, pw, ph, dark=-1))
        return out
    return fn


def _frayed(pw, ph, rnd, face):
    """The rope tail: twisted above, the bottom two rows frayed into pale loose fibres."""
    out = _rope(2)(pw, ph, rnd, face)
    for y in range(max(0, ph - 2), ph):
        for x in range(pw):
            out[x, y] = _r("straw", 4 if (x + y) % 2 == 0 else 3)
    return out


def _bone(pw, ph, rnd, face):
    out = {}
    for y in range(ph):
        for x in range(pw):
            out[x, y] = _r("cream", _bevel(3 if rnd.random() > 0.12 else 2, x, y, pw, ph))
    return out


def _plain(name, base):
    def fn(pw, ph, rnd, face):
        return {(x, y): _r(name, _bevel(base, x, y, pw, ph)) for y in range(ph) for x in range(pw)}
    return fn


TAR = Paint("tar", _tar(1), "wood_dark", 1)
TAR_DARK = Paint("tar_dark", _tar(0), "wood_dark", 0)
ROPE = Paint("rope", _rope(2), "straw", 2)
ROPE_TAIL = Paint("rope_tail", _frayed, "straw", 2)
BRASS = Paint("brass", _brass(2), "brass", 2)
BRASS_DARK = Paint("brass_dark", _brass(1, 1.3), "brass", 1)
IRON = Paint("iron", _iron(3), "steel", 3)
IRON_DARK = Paint("iron_dark", _iron(2, 1.3), "steel", 2)
EDGE = Paint("edge", _ground, "blade", 2)
STEEL = Paint("cutlass", _cutlass(2), "blade", 2)
STEEL_BACK = Paint("cutlass_back", _cutlass(1), "blade", 1)
BONE = Paint("bone", _bone, "cream", 3)
RIVET = Paint("rivet", _plain("brass", 3), "brass", 3)
LINE = Mat("feather", "plain", 1, bevel=None, noise=0)


# the skull's face, printed twice as fine as the rest (2 texels a unit): a 7 x 5 skull, a 5 x 2 jaw with teeth
SKULL = (".#####.",
         "#######",
         "#oo#oo#",
         "#oo#oo#",
         "###.###")
JAW = ("#.#.#",
       "#####")


def _print(rows, x0, y0, px):
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            light = x <= 1 or y == 0
            px[x0 + x, y0 + y] = (_r("cream", 3 if light else 2) if ch == "#" else _r("wood_dark", 0) if ch == "o"
                                  else _r("rubber", 1))


def _skull_face():
    im = Image.new("RGBA", (16, 16), _r("cream", 2))
    px = im.load()
    _print(SKULL, 0, 0, px)
    _print(JAW, 0, 8, px)
    return im


T.SURFACES["pirate_skull_face"] = _skull_face
SKULL_UV = [0, 0, 7, 5]
JAW_UV = [0, 8, 5, 10]


# ================================================================================================
# The shared handle
# ================================================================================================

def handle(m, tail=True):
    """A brass cap, a tarred grip with two rope bands, a brass collar (y -2.75 .. 6.5), and a knotted rope tail hanging
    from the lower band on the +x side (the side that shows in the hand), flaring outwards."""
    m.group("handle")
    m.box("cap", (6.5, -2.75, 6.5), (9.5, -1.25, 9.5), BRASS)
    m.box("grip", (6.875, -1.25, 6.875), (9.125, 5.25, 9.125), TAR)
    for y in (0.0, 2.75):
        m.box("rope", (6.625, y, 6.625), (9.375, y + 1.25, 9.375), ROPE)
    m.box("collar", (6.375, 5.25, 6.375), (9.625, 6.5, 9.625), BRASS_DARK)
    if tail:
        m.box("knot", (9.375, -0.25, 7.25), (10.5, 1.5, 8.75), ROPE)
        m.box("rope_tail", (9.625, -4, 7.5), (10.375, -0.25, 8.5), ROPE_TAIL, rotation=("z", 22.5, (10, -0.25, C)))


def shaft(m, top):
    m.group("shaft")
    m.box("shaft", (7, 6.5, 7), (9, top, 9), TAR)


def rivet(m, x, y, z, face=1):
    """A rivet head proud of a +z (face 1) or -z (face -1) surface at z."""
    z0, z1 = (z, z + 0.375) if face > 0 else (z - 0.375, z)
    m.box("rivet", (x - 0.5, y - 0.5, z0), (x + 0.5, y + 0.5, z1), RIVET)


# ================================================================================================
# The tools
# ================================================================================================

def pirate_sword():
    """해적 커틀러스: a short broad blade curving back in steps, nicked and scratched; a brass basket guard with a knuckle
    bow and a rivet; a bone skull charm under the cap."""
    m = MModel("pirate_sword")
    handle(m, tail=False)
    m.group("guard")
    m.box("shell", (5, 6.5, 6), (11, 8, 10), BRASS)                       # the basket's dish
    m.box("knuckle_bow", (4.25, -1.75, 7.25), (5.5, 8, 8.75), BRASS_DARK)  # down to the cap, on the edge's side
    m.box("knuckle_foot", (5.5, -2.5, 7.25), (6.5, -1.25, 8.75), BRASS_DARK)
    m.box("quillon", (11, 7.5, 7.25), (12.25, 9.5, 8.75), BRASS_DARK)     # curls up on the back's side
    rivet(m, 8, 7.25, 10)
    m.group("blade")
    # (y0, y1, x0, x1): the blade leans towards +x as it rises; the spine (+x) stays straight longer, the edge curves
    for y0, y1, x0, x1 in ((8, 13, 5.75, 10), (13, 18, 6.25, 10.5), (18, 21.5, 7, 11), (21.5, 24, 8.25, 11.25),
                           (24, 25.5, 9.5, 11.25)):
        m.box("blade", (x0, y0, 7.25), (x1, y1, 8.75), STEEL)
    m.box("spine", (9.25, 8, 7), (10, 18, 9), STEEL_BACK)
    m.group("charm")
    sk = {"south": "pirate_skull_face", "north": "pirate_skull_face"}
    m.box("skull", (6.25, -5.25, 6.75), (9.75, -2.75, 9.25), BONE, faces=sk, uv={"south": SKULL_UV, "north": SKULL_UV})
    m.box("jaw", (6.75, -6.25, 7), (9.25, -5.25, 9), BONE, faces=sk, uv={"south": JAW_UV, "north": JAW_UV})
    return _held(m, "sword")


def pirate_pickaxe():
    """해적 곡괭이: a pitted, rusting iron head on a tarred shaft, arms drooping in two bends, ground tips, rivets."""
    m = MModel("pirate_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    m.box("eye", (5.5, 14.75, 6.5), (10.5, 19.75, 9.5), IRON_DARK)
    rivet(m, 8, 17.25, 9.5)
    rivet(m, 8, 17.25, 6.5, -1)
    m.box("wedge", (7.25, 19.75, 7.25), (8.75, 20.5, 8.75), TAR_DARK)
    for side in (1, -1):
        p = (C + side * 2.5, 17.5)
        p = _arm(m, "arm", side, p, 4.5, 1.5, 7, 9, 22.5, IRON)
        p = _arm(m, "arm", side, p, 3.75, 1.125, 7.25, 8.75, 45, IRON)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 0.75, 7.5), (px + 2, py + 0.75, 8.5)
        else:
            frm, to = (px - 2, py - 0.75, 7.5), (px - 0.5, py + 0.75, 8.5)
        m.box("tip", frm, to, EDGE, rotation=("z", -45 * side, (px, py, C)))
    return _held(m, "pickaxe")


def pirate_shovel():
    """해적 삽: a ship's coal shovel: a square-shouldered iron blade (rusting at its edges), a brass socket with its tongue
    riveted down the face, a worn bright lip."""
    m = MModel("pirate_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    m.box("socket", (6.5, 15.5, 6.5), (9.5, 18, 9.5), BRASS)
    m.box("blade", (3.75, 18, 7.25), (12.25, 25.5, 8.75), IRON)
    m.box("blade", (4.5, 25.5, 7.5), (11.5, 27, 8.5), IRON)
    m.box("lip", (5, 27, 7.5), (11, 27.75, 8.5), EDGE)
    m.box("strap", (6.75, 18, 8.75), (9.25, 23.5, 9.25), BRASS_DARK)      # the socket's tongue on the face
    for x, y in ((8, 19.5), (8, 22.25), (4.75, 19), (11.25, 19)):
        rivet(m, x, y, 9.25 if x == 8 else 8.75)
    return _held(m, "shovel")


def pirate_axe():
    """해적 도끼: a boarding axe: a bearded iron blade (towards -x) with a nicked ground edge, a back spike, a riveted eye,
    a brass cap on the shaft."""
    m = MModel("pirate_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    m.box("shaft_cap", (6.5, 21.5, 6.5), (9.5, 22.75, 9.5), BRASS)
    m.box("eye", (6.25, 16.5, 6.5), (9.75, 20.75, 9.5), IRON_DARK)
    rivet(m, 8, 18.625, 9.5)
    m.box("blade", (3.5, 17, 7.125), (6.25, 20.5, 8.875), IRON)
    m.box("blade", (1.25, 13.5, 7.25), (3.5, 21, 8.75), IRON)              # the beard hangs below
    m.box("edge", (0.25, 13, 7.5), (1.25, 21.75, 8.5), EDGE)
    m.box("spike", (9.75, 17.5, 7.25), (12, 19.75, 8.75), IRON)
    m.box("spike_tip", (12, 18, 7.5), (13.75, 19.25, 8.5), EDGE)
    return _held(m, "axe")


def pirate_hoe():
    """해적 괭이: the vanilla hoe's L: a straight iron bar out to -x from the top of the tarred shaft, ending flat in a
    short blade a little deeper than the bar (across the handle) with a ground edge; a brass strap and rivet where the
    bar meets the shaft, a brass cap. The anchor is a charm: a little brass anchor tied to the rope tail."""
    m = MModel("pirate_hoe")
    handle(m, tail=False)
    m.group("charm")
    rot = ("z", 22.5, (10, -0.25, C))
    m.box("knot", (9.375, -0.25, 7.25), (10.5, 1.5, 8.75), ROPE)
    m.box("rope_tail", (9.625, -2.75, 7.625), (10.375, -0.25, 8.375), ROPE, rotation=rot)
    m.box("anchor_stock", (9, -3.25, 7.5), (11, -2.75, 8.5), BRASS_DARK, rotation=rot)
    m.box("anchor_shank", (9.5, -5.75, 7.5), (10.5, -3.25, 8.5), BRASS, rotation=rot)
    m.box("anchor_arms", (8.25, -6.5, 7.5), (11.75, -5.75, 8.5), BRASS, rotation=rot)
    m.box("anchor_fluke", (8.25, -5.75, 7.5), (9, -4.75, 8.5), BRASS, rotation=rot)
    m.box("anchor_fluke", (11, -5.75, 7.5), (11.75, -4.75, 8.5), BRASS, rotation=rot)
    shaft(m, 20)
    m.group("head")
    m.box("bar", (2.75, 19.5, 6.875), (9.75, 22.25, 9.125), IRON)
    m.box("strap", (6.625, 19, 6.625), (9.375, 22.75, 9.375), BRASS_DARK)
    rivet(m, 8, 20.875, 9.375)
    m.box("cap", (7, 22.75, 7), (9, 23.5, 9), BRASS)
    m.box("blade", (0.75, 18.75, 5.75), (2.75, 23, 10.25), IRON_DARK)
    m.box("edge", (0, 18.75, 6), (0.75, 23, 10), EDGE)
    return _held(m, "hoe")


def _anchor(m, rot):
    """A small brass anchor on the line's end (built hanging straight down, then turned with the line)."""
    m.box("anchor_ring", (7.75, 18.5, 7.75), (8.75, 19.25, 8.25), BRASS_DARK, rotation=rot)
    m.box("anchor_stock", (6.5, 17.75, 7.5), (10, 18.5, 8.5), TAR_DARK, rotation=rot)
    m.box("anchor_shank", (7.75, 14.25, 7.5), (8.75, 17.75, 8.5), BRASS, rotation=rot)
    m.box("anchor_arms", (6, 13.5, 7.5), (10.5, 14.25, 8.5), BRASS, rotation=rot)
    m.box("anchor_fluke", (6, 14.25, 7.5), (7, 15.25, 8.5), BRASS, rotation=rot)
    m.box("anchor_fluke", (9.5, 14.25, 7.5), (10.5, 15.25, 8.5), BRASS, rotation=rot)


def _rod(name, cast):
    m = MModel(name)
    handle(m, tail=False)
    m.group("reel")
    m.box("reel_foot", (9, 7.5, 7.5), (10, 9.5, 8.5), BRASS_DARK)
    m.box("reel", (10, 6.75, 6.5), (12.5, 10.25, 9.5), BRASS)
    m.box("reel_hub", (10.5, 7.75, 9.5), (12, 9.25, 10.25), TAR_DARK)
    m.group("rod")
    m.box("rod", (6.875, 6.5, 6.875), (9.125, 15, 9.125), TAR)
    m.box("rod", (7.125, 15, 7.125), (8.875, 23.5, 8.875), TAR)
    m.box("rod", (7.375, 23.5, 7.375), (8.625, 30.75, 8.625), TAR)
    for y, h in ((14.5, 6.625), (23, 6.875), (30.25, 7.125)):
        m.box("ring", (h, y, h), (16 - h, y + 1, 16 - h), BRASS)
    m.box("guide", (8.875, 19, 7.5), (10, 20, 8.5), BRASS_DARK)
    if not cast:
        m.group("line")
        top = (8.25, 31, C)
        rot = ("z", 45, top)
        m.box("line", (7.875, 19.25, 7.75), (8.625, 31, 8.25), LINE, rotation=rot)
        _anchor(m, rot)
    return _held(m, "rod")


def pirate_fishing_rod():
    """해적 낚싯대: a tarred wooden rod with brass rings, a small brass reel, a little anchor on the line."""
    return _rod("pirate_fishing_rod", False)


def pirate_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the anchor."""
    m = _rod("pirate_fishing_rod_cast", True)
    m.display = pirate_fishing_rod().display
    return m


ALL = {f.__name__: f for f in (pirate_sword, pirate_pickaxe, pirate_shovel, pirate_axe, pirate_hoe, pirate_fishing_rod,
                               pirate_fishing_rod_cast)}

ROWS = [
    ("pirate_sword", "해적 커틀러스"), ("pirate_pickaxe", "해적 곡괭이"), ("pirate_shovel", "해적 삽"),
    ("pirate_axe", "해적 도끼"), ("pirate_hoe", "해적 괭이"), ("pirate_fishing_rod", "해적 낚싯대"),
    ("pirate_fishing_rod_cast", "낚싯대 (던진 뒤)"),
]


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("pirate_sword", "pirate_pickaxe", "pirate_shovel", "pirate_axe", "pirate_hoe")}
    out["pirate_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/pirate_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/pirate_fishing_rod_cast"},
    }}
    return out
