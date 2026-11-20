"""
자개 시리즈 (najeon: Korean lacquerware with mother-of-pearl inlay): a premium tool skin set.

    najeon_sword  najeon_pickaxe  najeon_shovel  najeon_axe  najeon_hoe  najeon_fishing_rod (+ najeon_fishing_rod_cast)
    najeon_bobber

The frame, the grip point, the sizes and the display transforms are the toy series' (models/toys.py: built upright on
the axis x = z = 8, the hand on y = GRIP_Y, _held() puts the sprite's 45 degree tilt into the transforms).

Looks: glossy deep black lacquer and deep red 주칠 with iridescent mother-of-pearl (pink, cream, mint, sky, lilac:
one sheen function every inlay takes its pixel colours from, so each piece shimmers across itself), gold fittings.
Every textured face is painted for its own size, two texels a model unit (finer than the toy series' one, like the
toy sticker), so the inlays stay crisp:

    grips         끊음질: a diamond lattice of thin nacre strips with gold studs at the crossings
    shafts        black lacquer with nacre plum blossoms scattered up it and a lacquer glint
    big faces     inset pictures: a crane among clouds (the shovel's oar), plum branches (the brush, the reel)
    the blade     polished steel, a lit ridge, the 사인검's constellation inlaid in gold wire

A handful of small parts glow (pearl cabochons, the sword's gold stars): the set's "glints".
"""

import math
import zlib

from PIL import Image

import economy_models  # noqa: F401  (registers the shared surfaces)
from economy_machines import MModel, Mat, bands, cyl, _face_size
from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.color import oklch_to_rgb
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp, ramp
from models.toys import C, GRIP_Y, Gloss, _arm, _held, round_y, corners  # noqa: F401


# ================================================================================================
# Ramps and materials
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("nj_black", 275, 0.020, 0.30, 0.26, 5, False)    # black lacquer: blue-grey reflections
_ramp("nj_shu", 24, 0.150, 0.40, 0.28, 5, False)       # 주칠: deep vermilion-red lacquer
_ramp("nj_jade", 162, 0.075, 0.72, 0.30, 5, False)     # polished celadon jade
_ramp("nj_gold", 85, 0.125, 0.70, 0.36, 5, True)       # gold fittings
_ramp("nj_steel", 230, 0.014, 0.76, 0.34, 5, False)    # bright polished steel
_ramp("nj_knot_red", 22, 0.170, 0.50, 0.26, 4, False)  # cords and tassels
_ramp("nj_hair", 80, 0.040, 0.86, 0.22, 4, False)      # the brush's goat hair
_ramp("nj_pearl", 330, 0.050, 0.90, 0.10, 3, False)    # pearl cabochons

GOLD = Gloss("nj_gold", 2, bevel=(1, -1))
GOLD_FLAT = Mat("nj_gold", "plain", 2, bevel=(1, -1), noise=0)
GOLD_DARK = Mat("nj_gold", "plain", 1, bevel=(0, -1), noise=0)
BLACK = Gloss("nj_black", 1)
BLACK_FLAT = Mat("nj_black", "plain", 1, bevel=(0, -1), noise=0)
SHU = Gloss("nj_shu", 2)
SHU_FLAT = Mat("nj_shu", "plain", 2, bevel=(0, -1), noise=0)
JADE = Gloss("nj_jade", 2)
STEEL_EDGE = Mat("nj_steel", "plain", 4, bevel=(0, -1), noise=0)
STEEL = Gloss("nj_steel", 2, bevel=(1, -1))
CORD = Mat("nj_knot_red", "plain", 2, bevel=(0, -1), noise=0)
CORD_DARK = Mat("nj_knot_red", "plain", 1, bevel=(0, -1), noise=0)
PEARL = Mat("nj_pearl", "metal", 1, bevel=(1, -1), noise=0, glow=True)
LINE = Mat("feather", "plain", 2, bevel=None, noise=0)
SIDES = ("north", "east", "south", "west")


# ================================================================================================
# Painting: every textured face gets its own picture at two texels a model unit
# ================================================================================================

def sheen(x, y, lift=0.0, seed=0.0):
    """Mother-of-pearl at texel (x, y): a pastel hue drifting pink -> cream -> mint -> sky -> lilac along a wavy diagonal."""
    t = ((x * 0.6 + y * 0.85 + seed) / 11 + 0.12 * math.sin(x * 1.1 + y * 0.5 + seed)) % 1.0
    hue = 345 + 300 * t
    L = 0.82 + 0.05 * math.sin(x * 1.9 + y * 2.3 + seed) + lift
    c = 0.105 if lift <= 0.08 else 0.05
    return (*oklch_to_rgb(min(0.97, L), c, hue % 360), 255)


def _sheen_tex():
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            k = (x * 7 + y * 13) % 17
            px[x, y] = sheen(x, y, 0.13 if k == 0 else -0.05 if k == 9 else 0.0)
    return im


T.SURFACES["nj_nacre"] = _sheen_tex
NACRE = "nj_nacre"


class Canvas:
    """A w x h texel picture (w, h at most 16) drawn into a 16 x 16 texture's top left."""

    def __init__(self, W, H, fill):
        self.W, self.H = W, H
        self.im = Image.new("RGBA", (16, 16), fill)
        self.px = self.im.load()

    def put(self, x, y, c):
        if 0 <= x < self.W and 0 <= y < self.H:
            self.px[x, y] = c

    def get(self, x, y):
        return self.px[x, y]

    def stamp(self, rows, ox, oy, pal, seed=0.0):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch == ".":
                    continue
                X, Y = ox + x, oy + y
                c = pal[ch] if ch in pal else (sheen(X, Y, 0.14, seed) if ch == "w" else sheen(X, Y, 0.0, seed))
                self.put(X, Y, c)


def lacquer(cv, name, base, glint=True):
    """A glossy lacquer ground: the base step, a lit corner at the top left, a long thin reflection across it, a darker
    line along the bottom and the right."""
    W, H = cv.W, cv.H
    for y in range(H):
        for x in range(W):
            c = base
            if glint and x + y < max(1, min(W, H) * 0.4):
                c = base + 1
            if y == H - 1 or x == W - 1:
                c = base - 1
            cv.px[x, y] = ramp(name, max(0, c))
    if glint and min(W, H) >= 6:
        k = round(min(W, H) * 0.45) + 3
        for y in range(1, H - 1):
            x = k - y
            if 1 <= x < W - 1:
                cv.px[x, y] = ramp(name, base + 1)


def painted(m, name, frm, to, mat, painters, rotation=None, k=2.0, glow=False, **kw):
    """A box whose faces in painters ({face: fn(canvas, face)}) are painted at k texels a unit (less if a face is longer
    than 16 / k); the other faces take mat."""
    faces, uv = dict(kw.pop("faces", {}) or {}), {}
    for f, fn in painters.items():
        w, h = _face_size(f, frm, to)
        kk = min(k, 16 / w, 16 / h)
        W, H = max(1, math.ceil(w * kk - 1e-6)), max(1, math.ceil(h * kk - 1e-6))
        cv = Canvas(W, H, (0, 0, 0, 255))
        fn(cv, f)
        key = "nj_" + format(zlib.crc32(cv.im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=cv.im: img.copy())
        faces[f] = key
        uv[f] = [0, 0, round(min(16, w * kk), 4), round(min(16, h * kk), 4)]
    m.box(name, frm, to, mat, faces=faces, uv=uv, rotation=rotation, glow=glow, **kw)


# ---- the pictures ------------------------------------------------------------------------------



def P_lattice(cv, face):
    """끊음질 on black lacquer: a diamond lattice of nacre strips, gold studs where they cross."""
    lacquer(cv, "nj_black", 1, glint=False)
    off = {"north": 0, "east": 1, "south": 2, "west": 3}.get(face, 0)
    for y in range(cv.H):
        for x in range(cv.W):
            a, b = (x + y + off) % 6 == 0, (x - y + off + 60) % 6 == 0
            if a and b:
                cv.px[x, y] = ramp("nj_gold", 3)
            elif a or b:
                cv.px[x, y] = sheen(x, y, 0.0, off * 2.3)


BLOSSOM = ("..n..",
           "nnwnn",
           ".ngn.",
           ".n.n.")
SPRIG = ("n.", ".n")


def P_shaft(cv, face, y0=0.0):
    """Black lacquer with small nacre plum blossoms scattered up it (left, right, left ...) and nacre leaves between.
    y0: the face's top in model units (so the pattern runs on across stacked boxes)."""
    lacquer(cv, "nj_black", 1)
    off = {"north": 0, "east": 5, "south": 3, "west": 8}.get(face, 0)
    top = int(round(y0 * 2))
    for y in range(-4, cv.H + 4):
        g = top - y + off * 3                     # texel row counted from the bottom of the shaft
        if g % 13 == 0:
            side = (g // 13) % 2
            ox = 0 if side == 0 else cv.W - 5
            cv.stamp(BLOSSOM, ox, y, {"g": ramp("nj_gold", 3)}, seed=off)
        elif g % 13 == 7:
            cv.stamp(SPRIG, cv.W // 2 - 1, y, {}, seed=off)


def P_shaft_at(y0):
    return lambda cv, face: P_shaft(cv, face, y0)


def lacquer_post(m, name, x0, x1, z0, z1, y0, y1, seg=8.0, ends=GOLD_DARK):
    """A square black lacquer post along y with the scattered blossoms, in stacked boxes no longer than seg (so each
    face's picture keeps two texels a unit)."""
    n = max(1, math.ceil((y1 - y0) / seg - 1e-6))
    step = (y1 - y0) / n
    for i in range(n):
        a, b = y0 + i * step, y0 + (i + 1) * step
        f = {}
        if i > 0:
            f["down"] = None
        if i < n - 1:
            f["up"] = None
        painted(m, name, (x0, a, z0), (x1, b, z1), BLACK, {s: P_shaft_at(b) for s in SIDES}, faces=f)


# a crane among clouds: 'n' nacre, 'w' bright nacre, 'r' red crown, 'g' gold (beak, legs)
CRANE = (".......w....",
         "......wn...w",
         ".....wnn..wn",
         ".r..wnnn.wnn",
         "gwnnnnnnnnn.",
         "....nnnnnn..",
         ".....nnnn...",
         "......g.g...",
         ".....g...g..")
CLOUD = (".nn.nn.",
         "nwwnwwn",
         ".nnnnn.",
         "..n.n..")
CLOUD_S = (".nn..",
           "nwwn.",
           ".nnnn")


PLUM = ("...ww....",
        "...nnn...",
        "ww.nnn.ww",
        "nnnngnnnn",
        ".nnnnnnn.",
        "..nn.nn..",
        ".nnn.nnn.",
        ".nn...nn.")


def P_oar(cv, face):
    """The oar's face: a red 주칠 border, a gold line, a black lacquer field with a crane flying over clouds and a blossom."""
    lacquer(cv, "nj_shu", 2)
    W, H = cv.W, cv.H
    for y in range(1, H - 1):
        for x in range(1, W - 1):
            edge = x in (1, W - 2) or y in (1, H - 2)
            cv.px[x, y] = ramp("nj_gold", 3 if (x + y) % 4 else 4) if edge else ramp("nj_black", 1)
    for y in range(2, H - 2):            # a glint in the field's corner
        for x in range(2, W - 2):
            if x + y < 6:
                cv.px[x, y] = ramp("nj_black", 2)
    s = 1.7 if face == "south" else 0.0
    gold = {"g": ramp("nj_gold", 4)}
    cv.stamp(PLUM, (W - 9) // 2, 3, gold, seed=s)
    cv.stamp(BLOSSOM, 2 if face != "north" else W - 7, H - 6, gold, seed=s + 3)
    cv.stamp(SPRIG, W - 6 if face != "north" else 4, H - 5, {}, seed=s)
    cv.stamp(SPRIG, W - 4 if face != "north" else 2, H - 7, {}, seed=s)


def P_red_blossoms(cv, face):
    """Red lacquer with nacre blossoms in a gold-edged band (the brush's barrel)."""
    lacquer(cv, "nj_shu", 2)
    for x in range(cv.W):
        cv.px[x, 0] = ramp("nj_gold", 3)
        cv.px[x, cv.H - 1] = ramp("nj_gold", 1)
    off = {"north": 0, "east": 2, "south": 4, "west": 6, "up": 1, "down": 3}.get(face, 0)
    for i, x in enumerate(range(1 + off % 3, cv.W - 4, 6)):
        cv.stamp(BLOSSOM, x, 1 if i % 2 == 0 else cv.H - 5, {"g": ramp("nj_gold", 3)}, seed=off + i)


def P_steel(stars):
    """사인검's blade, one piece of it: steel lit on the left of a bright ridge, shaded on the right; the constellation in
    gold wire (stars: (x, y) in texels, joined in order), each star a small glinting cross."""
    def fn(cv, face):
        W, H = cv.W, cv.H
        mid = W // 2
        for y in range(H):
            for x in range(W):
                c = 3 if x < mid else 2
                if x == mid:
                    c = 4
                if x == 0:
                    c = 4
                if x == W - 1:
                    c = 1
                cv.px[x, y] = ramp("nj_steel", c)
        pts = stars if face == "south" else [(W - 1 - x, y) for x, y in stars]
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            n = max(abs(bx - ax), abs(by - ay))
            for i in range(1, n, 2):
                cv.put(round(ax + (bx - ax) * i / n), round(ay + (by - ay) * i / n), ramp("nj_gold", 1))
        for x, y in pts:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                cv.put(x + dx, y + dy, ramp("nj_gold", 3))
            cv.put(x, y, ramp("nj_gold", 4))
    return fn


def _rosette(bg, motif="plum"):
    """A round medallion: lacquer of ramp bg, a gold rim with a bead row, a nacre plum blossom or a crane in it."""
    def make():
        name, i = bg
        im = Image.new("RGBA", (16, 16), ramp(name, i))
        px = im.load()
        for y in range(16):
            for x in range(16):
                r = math.hypot(x - 7.5, y - 7.5)
                if r > 6.4:
                    px[x, y] = ramp("nj_gold", 3 if (x + y) % 3 else 4)
                elif r > 5.6:
                    px[x, y] = ramp("nj_gold", 1)
                elif x + y < 9:
                    px[x, y] = ramp(name, i + 1)
        big = ("...ww...",
               "..wnnw..",
               "ww.nn.ww",
               "nnnggnnn",
               ".nnggnn.",
               "..n..n..",
               ".nn..nn.",
               ".n....n.")
        for y, row in enumerate(big):
            for x, ch in enumerate(row):
                if ch == "n":
                    px[4 + x, 4 + y] = sheen(4 + x, 4 + y)
                elif ch == "w":
                    px[4 + x, 4 + y] = sheen(4 + x, 4 + y, 0.13)
                elif ch == "g":
                    px[4 + x, 4 + y] = ramp("nj_gold", 4)
        return im
    return make


T.SURFACES["nj_rosette_black"] = _rosette(("nj_black", 1))
T.SURFACES["nj_rosette_red"] = _rosette(("nj_shu", 2))


def _pearl_cap():
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = sheen(x, y, 0.1 - 0.012 * math.hypot(x - 5, y - 5))
    return im


T.SURFACES["nj_pearl_cap"] = _pearl_cap


# ================================================================================================
# What every piece shares
# ================================================================================================

def pearl(m, name, x, y, z0, z1, size=1.0, rim=0.25):
    """A glowing pearl cabochon in a gold bezel, set through a part (shows on the front and the back)."""
    h = size / 2
    m.box(f"{name}_bezel", (x - h - rim, y - h - rim, z0 - 0.125), (x + h + rim, y + h + rim, z1 + 0.125), GOLD_FLAT)
    m.box(name, (x - h, y - h, z0 - 0.375), (x + h, y + h, z1 + 0.375), "nj_pearl_cap", glow=True)


def handle(m, tassel=False):
    """A gold pommel with a pearl, a 끊음질 lattice grip between gold ferrules, a gold collar (y -3.5 .. 6.5).
    tassel: a red 매듭 knot and tassel hanging from the pommel (the sword)."""
    m.group("handle")
    m.box("pommel_nub", (7.25, -3.5, 7.25), (8.75, -2.75, 8.75), GOLD_DARK)
    round_y(m, "pommel", 6.375, 9.625, 6.375, 9.625, -2.75, -1.25, GOLD, 0.75)
    m.box("pommel_pearl", (7.5, -2.375, 6.125), (8.5, -1.625, 9.875), "nj_pearl_cap", glow=True)
    painted(m, "grip", (6.75, -1.25, 6.75), (9.25, 5.0, 9.25), BLACK_FLAT, {s: P_lattice for s in SIDES})
    round_y(m, "ferrule", 6.5, 9.5, 6.5, 9.5, -1.25, -0.75, GOLD_FLAT, 0.5)
    round_y(m, "collar", 6.25, 9.75, 6.25, 9.75, 5.0, 6.5, GOLD, 0.75)
    if tassel:
        m.group("tassel")
        m.box("cord", (7.75, -4.5, 7.75), (8.25, -3.5, 8.25), CORD_DARK)
        m.box("knot", (7.125, -5.875, 7.5), (8.875, -4.125, 8.5), CORD, rotation=("z", 45, (C, -5.0, C)))
        m.box("tassel_cap", (7.25, -6.75, 7.25), (8.75, -6.0, 8.75), GOLD_FLAT)
        m.box("tassel", (7.0, -9.25, 7.0), (9.0, -6.75, 9.0), CORD)
        m.box("tassel_end", (6.875, -10.0, 6.875), (9.125, -9.25, 9.125), CORD_DARK)


def shaft(m, top, ring=None):
    """The black lacquer shaft with its scattered blossoms above the collar up to y = top, a thin gold ring at y = ring."""
    m.group("shaft")
    lacquer_post(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top)
    if ring is not None:
        round_y(m, "shaft_ring", 6.625, 9.375, 6.625, 9.375, ring, ring + 0.5, GOLD_FLAT, 0.5)


# ================================================================================================
# The tools
# ================================================================================================

def najeon_sword():
    """사인검 (자개 장검): a long straight double-edged Joseon ceremonial sword. Polished steel with a lit ridge and the
    constellations of a real 사인검 inlaid in gold wire down it, bright edges, a stepped point; a gold guard with a black
    lacquer plate set with nacre and a pearl at each end, a gold habaki; the 끊음질 grip, the gold pommel with a
    pearl, a red 매듭 tassel."""
    m = MModel("najeon_sword")
    handle(m, tassel=True)
    m.group("guard")
    round_y(m, "guard", 4.25, 11.75, 6.0, 10.0, 6.5, 7.25, GOLD, 0.75)
    painted(m, "guard_plate", (4.75, 7.25, 6.25), (11.25, 8.25, 9.75), BLACK_FLAT,
            {"north": P_lattice, "south": P_lattice})
    m.box("guard_lip", (5.25, 8.25, 6.75), (10.75, 8.75, 9.25), GOLD_FLAT)
    for x in (3.5, 11.5):
        m.box("guard_end", (x, 6.25, 6.625), (x + 1.0, 8.5, 9.375), GOLD)
        m.box("guard_pearl", (x + 0.125, 6.875, 6.375), (x + 0.875, 7.875, 9.625), "nj_pearl_cap", glow=True)
    m.box("habaki", (6.0, 8.75, 7.0), (10.0, 10.0, 9.0), GOLD)
    m.group("blade")
    segs = ((10.0, 17.25, [(3, 12), (5, 8), (3, 4), (5, 1)]),
            (17.25, 24.5, [(5, 13), (3, 9), (4, 5), (3, 1)]),
            (24.5, 29.0, [(4, 7), (3, 2)]))
    for i, (a, b, stars) in enumerate(segs):
        f = {}
        if i:
            f["down"] = None
        if i < len(segs) - 1:
            f["up"] = None
        painted(m, "blade", (6.25, a, 7.375), (9.75, b, 8.625), STEEL, {"north": P_steel(stars), "south": P_steel(stars)},
                faces=f)
    m.box("blade_edge", (5.5, 10.0, 7.625), (6.25, 28.5, 8.375), STEEL_EDGE)
    m.box("blade_edge", (9.75, 10.0, 7.625), (10.5, 28.5, 8.375), STEEL_EDGE)
    m.box("blade_tip", (6.5, 29.0, 7.5), (9.5, 30.25, 8.5), STEEL)
    m.box("blade_tip", (7.25, 30.25, 7.625), (8.75, 31.25, 8.375), STEEL_EDGE)
    m.box("blade_tip", (7.625, 31.25, 7.75), (8.375, 32.0, 8.25), STEEL_EDGE)
    return _held(m, "sword")


def najeon_pickaxe():
    """비녀 곡괭이: two jade hairpins (비녀) for arms, drooping in two bends to gold points with gold rings at the bends,
    their gold heads with pearl beads meeting in a round red lacquer boss with a nacre plum blossom and a beaded gold rim."""
    m = MModel("najeon_pickaxe")
    handle(m)
    shaft(m, 15.5, ring=12.0)
    m.group("head")
    round_y(m, "socket", 6.375, 9.625, 6.375, 9.625, 15.0, 16.0, GOLD, 0.5)
    for side in (1, -1):
        x0 = C + side * 3.25
        lo, hi = sorted((x0, x0 + side * 1.75))
        m.box("pin_head", (lo, 17.25, 6.75), (hi, 21.25, 9.25), GOLD)
        lo2, hi2 = sorted((x0 + side * 1.75, x0 + side * 2.5))
        m.box("pin_bead", (lo2, 18.0, 7.0), (hi2, 20.5, 9.0), "nj_pearl_cap", glow=True)
        p = (C + side * 5.5, 19.25)
        p = _arm(m, "pin", side, p, 4.75, 1.125, 7.125, 8.875, 22.5, JADE)
        px, py = p
        lo3, hi3 = sorted((px - side * 0.25, px + side * 0.5))
        m.box("pin_ring", (lo3, py - 1.375, 6.875), (hi3, py + 1.375, 9.125), GOLD_FLAT, rotation=("z", -22.5 * side, (px, py, C)))
        p = _arm(m, "pin", side, p, 4.25, 0.875, 7.25, 8.75, 45, JADE)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 0.625, 7.375), (px + 2.5, py + 0.625, 8.625)
        else:
            frm, to = (px - 2.5, py - 0.625, 7.375), (px - 0.5, py + 0.625, 8.625)
        m.box("pin_point", frm, to, GOLD, rotation=("z", -45 * side, (px, py, C)))
    cyl(m, "boss", "z", (C, 19.25), 7, 6.25, 9.75, GOLD_DARK, cap="nj_rosette_red")
    return _held(m, "pickaxe")


def najeon_shovel():
    """노 삽: a lacquered boat oar for a spade: a broad round-ended blade of red 주칠 with an inset black lacquer field on
    both faces, a nacre crane flying over clouds in it, a gold line round the field, a gold-shod tip and a gold socket
    with a pearl where the loom meets the blade; a ridge down the neck."""
    m = MModel("najeon_shovel")
    handle(m)
    shaft(m, 14.0, ring=11.0)
    m.group("blade")
    round_y(m, "socket", 6.375, 9.625, 6.375, 9.625, 14.0, 15.75, GOLD, 0.75)
    pearl(m, "socket_pearl", C, 14.875, 6.375, 9.625, size=0.75, rim=0.25)
    z0, z1 = 7.125, 8.875
    m.box("neck", (6.5, 15.75, 7.25), (9.5, 16.75, 8.75), SHU)
    m.box("blade", (4.75, 16.75, z0), (11.25, 17.75, z1), SHU)
    painted(m, "blade", (4.25, 17.75, z0), (11.75, 25.0, z1), SHU, {"north": P_oar, "south": P_oar})
    for i, hw in enumerate((3.25, 2.5, 1.75)):
        m.box("blade", (C - hw, 25.0 + i, z0), (C + hw, 26.0 + i, z1), SHU)
    m.box("tip", (7.0, 28.0, 7.25), (9.0, 29.0, 8.75), GOLD)
    m.box("tip", (7.5, 29.0, 7.375), (8.5, 29.75, 8.625), GOLD_FLAT)
    for z in ((6.875, z0), (z1, 9.125)):
        m.box("ridge", (7.5, 15.75, z[0]), (8.5, 17.75, z[1]), GOLD_FLAT)
    return _held(m, "shovel")


# the fan: a half disc of radius FAN_R left of its pivot (x <= FAN_C[0]); its picture covers that box
FAN_C = (6.75, 19.5)
FAN_R = 8.0
FAN_BOX = (FAN_C[0] - FAN_R, FAN_C[1] - FAN_R, FAN_C[0], FAN_C[1] + FAN_R)    # x0, y0, x1, y1
BLOSSOMS = ((1.4, 23.6), (2.0, 15.4), (3.1, 25.4), (3.4, 21.0))
TWIG = ((5.0, 20.2), (3.4, 21.0), (1.4, 23.6), (2.3, 22.4), (3.1, 25.4), (2.3, 22.4), (3.4, 21.0), (4.0, 18.2), (2.0, 15.4))


def _seg_dist(p, a, b):
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(p[0] - ax - t * dx, p[1] - ay - t * dy)


def _fan(ylo, yhi):
    """The opened 합죽선: cream hanji folds (every other a shade darker, a crease line between), a red plum branch painted
    across it, a double gold rim, the lacquered ribs at the pivot with nacre between. Texel (u, v) <- model (x, y) over
    FAN_BOX's x and ylo .. yhi."""
    x0, _, x1, _ = FAN_BOX
    y0, y1 = ylo, yhi
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for v in range(16):
        for u in range(16):
            x = x0 + (u + 0.5) / 16 * (x1 - x0)
            y = y1 - (v + 0.5) / 16 * (y1 - y0)
            r = math.hypot(x - FAN_C[0], y - FAN_C[1])
            ang = math.degrees(math.atan2(y - FAN_C[1], FAN_C[0] - x))      # -90 .. 90
            fold = int((ang + 90) // 15) % 2
            col = ramp("paper", 2) if fold else ramp("paper", 1)
            if (ang + 90) % 15 < 1.6:
                col = ramp("paper", 0)
            if r > FAN_R - 0.55:
                col = ramp("nj_gold", 3)
            elif r > FAN_R - 1.05:
                col = ramp("nj_gold", 1)
            elif r < 3.2:
                col = sheen(u, v, 0.02) if fold else ramp("nj_black", 1)
                if r > 2.7:
                    col = ramp("nj_gold", 2)
            else:
                if min(_seg_dist((x, y), a, b) for a, b in zip(TWIG, TWIG[1:])) < 0.28:
                    col = ramp("nj_black", 0)
                for bx, by in BLOSSOMS:
                    d = math.hypot(x - bx, y - by)
                    if d < 1.05:
                        col = ramp("nj_gold", 4) if d < 0.35 else ramp("nj_knot_red", 3 if d < 0.7 else 2)
            px[u, v] = col
    return im


T.SURFACES["nj_fan_top"] = lambda: _fan(FAN_C[1], FAN_C[1] + FAN_R)
T.SURFACES["nj_fan_bot"] = lambda: _fan(FAN_C[1] - FAN_R, FAN_C[1])


def najeon_axe():
    """합죽선 도끼: an opened folding fan for a blade: a half disc of hanji folds with a red plum branch, a double gold rim,
    lacquered ribs inlaid with nacre at the pivot; a gold rivet with a pearl at the pivot, and on the other side the
    fan's 선추 pendant: a red cord with a gold-framed nacre plaque and a little tassel."""
    m = MModel("najeon_axe")
    handle(m)
    shaft(m, 27.0, ring=12.0)
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 27.0, 28.0, GOLD, 0.75)
    m.group("fan")
    x0, y0, x1, y1 = FAN_BOX
    U = lambda x: round(min(16, max(0, (x - x0) / (x1 - x0) * 16)), 4)
    cx, cy = FAN_C
    leaf = Mat("paper", "plain", 1, bevel=None, noise=0)
    for a, b, hw in bands(2 * FAN_R):
        fx, ty, by = cx - hw, cy + b, cy + a
        ex = cx + 0.125                         # the leaves run under the shaft's left half
        top = a >= 0
        tex, ref = ("nj_fan_top", cy + FAN_R) if top else ("nj_fan_bot", cy)
        V = lambda y: round(min(16, max(0, (ref - y) / FAN_R * 16)), 4)
        uv = {"south": [U(fx), V(ty), U(ex), V(by)], "north": [U(ex), V(ty), U(fx), V(by)]}
        m.box("leaf", (fx, by, 7.625), (ex, ty, 8.375), leaf, faces={"south": tex, "north": tex, "east": None}, uv=uv)
    cyl(m, "rivet", "z", (C, cy), 2, 6.5, 9.5, GOLD, cap="nj_pearl_cap")
    m.group("pendant")
    m.box("cord", (9.125, cy - 0.375, 7.75), (10.25, cy + 0.375, 8.25), CORD_DARK)
    m.box("cord", (10.0, cy - 4.0, 7.75), (10.5, cy - 0.375, 8.25), CORD)
    m.box("plaque_frame", (9.25, cy - 7.0, 7.25), (11.25, cy - 4.0, 8.75), GOLD_FLAT)
    m.box("plaque", (9.5, cy - 6.75, 7.0), (11.0, cy - 4.25, 9.0), NACRE)
    m.box("tassel", (9.625, cy - 9.0, 7.5), (10.875, cy - 7.0, 8.5), CORD)
    return _held(m, "axe")


HAIR = ("nj_hair", 2)


def P_hair(x0, x1, ink_x=-2.5):
    """The brush's hair between model x0 and x1, seen from the side: cream goat hair in strands, the ink soaked up from
    the tip to about ink_x (a ragged edge)."""
    def fn(cv, face):
        for v in range(cv.H):
            for u in range(cv.W):
                t = (u + 0.5) / cv.W
                x = x0 + t * (x1 - x0) if face != "north" else x1 - t * (x1 - x0)
                c = ramp("nj_hair", 3 if v % 3 == 1 else 2 if (u + v) % 5 else 1)
                if x < ink_x + ((v * 7) % 3 - 1) * 0.3:
                    c = ramp("nj_black", 2 if v % 3 == 1 else 1)
                cv.px[u, v] = c
    return fn


def najeon_hoe():
    """붓 괭이: a big calligraphy brush laid sideways on top of the shaft (the vanilla hoe's straight ㄱ): a red 주칠 barrel
    inlaid with nacre blossoms between gold bands, a gold mount with a pearl on the shaft, a red hanging loop at the
    back end, a gold ferrule, a fat cream hair tuft that tapers into an ink-soaked point (ending flat)."""
    m = MModel("najeon_hoe")
    handle(m)
    shaft(m, 20.0, ring=12.0)
    m.group("brush")
    cy = 21.75
    painted(m, "barrel", (2.25, cy - 1.5, 6.5), (6.75, cy + 1.5, 9.5), SHU,
            {"north": P_red_blossoms, "south": P_red_blossoms, "up": P_red_blossoms, "down": P_red_blossoms},
            )
    round_y(m, "mount", 6.25, 9.75, 6.25, 9.75, 20.0, 23.75, GOLD, 0.75)
    pearl(m, "mount_pearl", C, 21.875, 6.25, 9.75, size=1.0, rim=0.25)
    m.box("barrel_end", (9.75, cy - 1.25, 6.75), (11.0, cy + 1.25, 9.25), SHU)
    m.box("end_cap", (11.0, cy - 1.0, 7.0), (11.625, cy + 1.0, 9.0), GOLD_FLAT)
    m.box("loop", (11.625, cy - 0.25, 7.75), (12.75, cy + 0.25, 8.25), CORD)
    m.box("loop", (12.25, cy - 2.75, 7.75), (12.75, cy - 0.25, 8.25), CORD)
    m.box("loop", (11.5, cy - 3.25, 7.75), (12.75, cy - 2.75, 8.25), CORD)
    cyl(m, "ferrule", "x", (cy, C), 4, 1.25, 2.25, GOLD, cap=GOLD)
    m.box("ferrule_lip", (0.875, cy - 1.75, 6.25), (1.25, cy + 1.75, 9.75), GOLD_DARK)
    # the tuft: 4 wide where it leaves the ferrule, a little fuller in its belly, tapering to a flat inked end
    tuft = ((-0.25, 0.875, 2.0), (-2.75, -0.25, 2.125), (-4.25, -2.75, 1.75), (-5.25, -4.25, 1.25), (-5.75, -5.25, 0.75))
    for x0, x1, hw in tuft:
        hair_side = P_hair(x0, x1)
        painted(m, "hair", (x0, cy - hw, C - hw), (x1, cy + hw, C + hw), Mat("nj_hair", "plain", 2, bevel=None, noise=0),
                {"north": hair_side, "south": hair_side, "up": hair_side, "down": hair_side},
                faces={"west": BLACK_FLAT if x0 < -3 else Mat("nj_hair", "plain", 2, bevel=None, noise=0)})
    return _held(m, "hoe")


def P_flute(cv, face):
    """퉁소's front: the shaft's lacquer with blossoms, and a column of gold-ringed finger holes down its middle."""
    P_shaft(cv, face, 30.0)
    mid = cv.W // 2 - 1
    for y in range(2, cv.H - 3, 6):
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            cv.put(mid + dx, y + dy, ramp("nj_black", 0))
        for dx, dy in ((-1, 0), (-1, 1), (2, 0), (2, 1), (0, -1), (1, -1), (0, 2), (1, 2)):
            cv.put(mid + dx, y + dy, ramp("nj_gold", 3))


def _gourd(m, rot, cy):
    """The lacquered gourd float on the line: a red bulb with a nacre band, a gold waist, a small black top, a red knot."""
    m.box("gourd", (6.75, cy - 3.25, 6.75), (9.25, cy - 2.75, 9.25), SHU, rotation=rot)
    m.box("gourd", (6.0, cy - 2.75, 6.0), (10.0, cy - 0.25, 10.0), SHU, rotation=rot)
    m.box("gourd_band", (5.75, cy - 1.75, 5.75), (10.25, cy - 1.25, 10.25), NACRE, rotation=rot)
    m.box("gourd_waist", (7.25, cy - 0.25, 7.25), (8.75, cy + 0.5, 8.75), GOLD, rotation=rot)
    m.box("gourd_top", (6.75, cy + 0.5, 6.75), (9.25, cy + 2.25, 9.25), BLACK, rotation=rot)
    m.box("gourd_knot", (7.5, cy + 2.25, 7.5), (8.5, cy + 3.0, 8.5), GOLD_FLAT, rotation=rot)


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9.25, 7.5, 7.25), (10.0, 9.5, 8.75), GOLD_DARK)
    cyl(m, "reel", "z", (12.75, 8.5), 6, 6.25, 9.75, SHU_FLAT, cap="nj_rosette_red")
    m.box("reel_hub", (12.25, 8.0, 6.0), (13.25, 9.0, 10.0), GOLD)
    m.box("crank", (12.25, 8.5, 10.0), (13.25, 11.25, 10.5), GOLD)
    m.box("crank_knob", (12.0, 10.5, 10.5), (13.5, 12.0, 11.75), "nj_pearl_cap", glow=True)
    m.group("flute")
    # 퉁소: a fat black lacquered flute is the rod's butt, blossoms scattered on it, gold-ringed finger holes down its
    # front, two gold-capped nodes; a gold mouthpiece with its notch; then a slim jade tip carries the line on
    for i, (a, b) in enumerate(((6.5, 13.75), (13.75, 21.0))):
        painted(m, "flute", (6.75, a, 6.75), (9.25, b, 9.25), BLACK,
                {"south": P_flute, "north": P_shaft_at(b), "east": P_shaft_at(b), "west": P_shaft_at(b)},
                faces={"up": None} if i == 0 else {"down": None})
    round_y(m, "node", 6.5, 9.5, 6.5, 9.5, 13.5, 14.0, GOLD_FLAT, 0.5)
    round_y(m, "mouth", 6.5, 9.5, 6.5, 9.5, 21.0, 22.0, GOLD, 0.5)
    m.box("notch", (7.5, 21.5, 9.375), (8.5, 22.25, 9.625), BLACK_FLAT)
    m.box("tip", (7.25, 22.0, 7.25), (8.75, 27.0, 8.75), JADE)
    m.box("tip_ring", (7.0, 26.25, 7.0), (9.0, 26.75, 9.0), GOLD_FLAT)
    m.box("tip", (7.5, 27.0, 7.5), (8.5, 30.75, 8.5), JADE)
    m.box("tip_end", (7.375, 30.75, 7.375), (8.625, 31.5, 8.625), GOLD)
    m.box("guide", (8.75, 18.0, 7.5), (10.0, 19.0, 8.5), GOLD_DARK)
    m.box("guide", (8.5, 25.0, 7.625), (9.5, 25.75, 8.375), GOLD_DARK)
    m.group("tassel")
    m.box("flute_cord", (9.25, 20.5, 7.75), (9.875, 21.25, 8.25), CORD_DARK)
    m.box("flute_tassel_cap", (9.375, 19.75, 7.5), (10.375, 20.5, 8.5), GOLD_FLAT)
    m.box("flute_tassel", (9.5, 17.25, 7.625), (10.25, 19.75, 8.375), CORD)
    if not cast:
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 20.0, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        _gourd(m, rot, 17.0)
    return _held(m, "rod")


def najeon_fishing_rod():
    """퉁소 낚싯대: a black lacquered flute (퉁소) for the rod's butt, blossoms of nacre on it and gold-ringed finger holes,
    a slim jade tip, a little red tassel, a red lacquer reel with a nacre blossom and a red lacquered gourd float."""
    return _rod("najeon_fishing_rod", False)


def najeon_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("najeon_fishing_rod_cast", True)
    m.display = najeon_fishing_rod().display
    return m


BOBBER_WATERLINE = 4.0        # the float's gold band: where the water's surface is (model y)


def P_bulb(cv, face):
    """The float's red bulb: red lacquer with a nacre blossom on each side."""
    lacquer(cv, "nj_shu", 2)
    cv.stamp(BLOSSOM, (cv.W - 5) // 2, (cv.H - 4) // 2, {"g": ramp("nj_gold", 3)}, seed={"north": 0, "east": 2}.get(face, 4))


def najeon_bobber():
    """자개 찌: a lacquered gourd float for the item display that rides the fishing hook. Black lacquer under its gold
    waterline band, a red lacquer bulb with a nacre blossom on each side above it, a gold waist, a small black top, a gold
    stem and a glowing pearl. Stands on y = 0 about the axis x = 8, z = 8; the water's surface is at BOBBER_WATERLINE."""
    m = MModel("najeon_bobber")
    m.group("float")
    cyl(m, "weight", "y", (C, C), 2, 0, 1, GOLD_DARK, cap=GOLD_DARK)
    cyl(m, "belly", "y", (C, C), 4, 1, 2, BLACK_FLAT, cap=BLACK_FLAT)
    cyl(m, "belly", "y", (C, C), 6, 2, 3.5, BLACK_FLAT, cap=BLACK_FLAT)
    cyl(m, "band", "y", (C, C), 6, 3.5, 4.5, GOLD, cap=GOLD)
    painted(m, "bulb", (5.0, 4.5, 5.0), (11.0, 7.25, 11.0), SHU, {s: P_bulb for s in SIDES})
    m.box("bulb_top", (5.75, 7.25, 5.75), (10.25, 7.75, 10.25), SHU)
    cyl(m, "waist", "y", (C, C), 2, 7.75, 8.5, GOLD, cap=GOLD)
    cyl(m, "top", "y", (C, C), 4, 8.5, 10.25, BLACK, cap=NACRE)
    m.box("stem", (7.5, 10.25, 7.5), (8.5, 10.75, 8.5), GOLD)
    m.box("pearl", (7.375, 10.75, 7.375), (8.625, 12.0, 8.625), "nj_pearl_cap", glow=True)
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
