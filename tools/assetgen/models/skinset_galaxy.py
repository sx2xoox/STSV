"""
은하수 시리즈 (galaxy tool skins): a donation set of held-tool looks that matches the 은하수 cosmetic set
(models/cosmetics_galaxy.py). Every tool is a night-sky object standing in for the tool: deep indigo-violet space
crystal with twinkling stars, pale-gold crescent moons with craters, glowing gold stars, a banded ringed planet, a comet;
silver grips wrapped in dark velvet.

    galaxy_sword    별빛 광선검   a long blade of light (1.3 x the toy sword's) out of a crescent-moon guard
    galaxy_pickaxe  혜성 곡괭이   a comet: its nucleus on the shaft, its two tails drooping out as the arms
    galaxy_shovel   초승달 삽     a crescent moon lying horns up as the scoop, a star resting in it
    galaxy_axe      토성 도끼     a ringed planet whose broad tilted ring is the blade
    galaxy_hoe      별자리 지팡이 the vanilla L: a straight head of stars joined by constellation lines, ending flat
    galaxy_fishing_rod (+ _cast)  북두칠성 낚싯대: seven stars up a night-sky rod, a star lure
    galaxy_bobber   the star lure on the water

Same frame, sizes and display transforms as the toy series (models/toys.py: built upright, the grip at GRIP_Y on the
axis x = z = 8, _held() puts the vanilla tilt into the transforms); the sword is built in bigger units and shrunk into
the model box (Scaled), its held views enlarged back. Painted by the donation rules (premium_art.py: 32 texels a block,
ramp colours and their in-between steps); the glowing parts are emissive and some shimmer (8 frames).
"""

import math
import random
import zlib

import economy_models  # noqa: F401  (registers the shared surfaces)
from economy_machines import MModel, bands
from models.toys import _held, _arm, round_y, round_z, C, GRIP_Y  # noqa: F401
from premium_art import HMat, hd_canvas, shade, HS, D, FACE_LIGHT
from stsv_assets import presets
from stsv_assets import textures as T

TITLE = "은하수 시리즈 (도구 스킨)"


# ================================================================================================
# The space crystal
# ================================================================================================

class SpaceCrystal(HMat):
    """Deep indigo-violet crystal at 32 texels a block: violet at the top left, navy at the bottom right, a faint pink
    nebula high and a cyan one low (stippled veils, no blobs), a light lilac facet rim on the top and left edges, stars
    in white and gold that twinkle in turn (8 frames), a few gold ones flaring into a cross. const: a constellation,
    [(u, v), ...] node positions on the face (0..1, v down) joined in order (None breaks the chain), drawn in silver on
    the faces named in const_faces (big enough ones only)."""

    def __init__(self, base=1.0, glow=False, const=None, const_faces=("north", "south"), density=1.0):
        super().__init__("grape", "smooth", base, glow=glow, anim=8)
        self.const, self.const_faces, self.density = const, const_faces, density

    def _sky(self, pw, ph, face, seed, k):
        rnd = random.Random(seed)
        im = hd_canvas()
        px = im.load()
        lit = FACE_LIGHT.get(face, 0) * 0.8
        ox, oy = rnd.random() * 9, rnd.random() * 9
        for y in range(HS):
            for x in range(HS):
                xx, yy = min(x, pw - 1), min(y, ph - 1)
                u, v = (xx + 0.5) / pw, (yy + 0.5) / ph
                g = 0.55 * v + 0.45 * u                         # 0 top left .. 1 bottom right
                neb = math.sin(xx * 0.43 + ox) + math.sin((xx + yy) * 0.29 + oy) + 0.8 * math.cos(yy * 0.41 - ox)
                haze = max(0.0, min(1.0, (neb - 0.9) / 1.6))
                r = rnd.random()
                if r < haze * 0.45 and g < 0.5:
                    c = shade("sakura", 0.2 + haze * 0.6 + lit * 0.4) if haze > 0.55 else shade("petal_purple", 0.6 + haze + lit * 0.5)
                elif r < haze * 0.45:
                    c = shade("aurora", 1.6 + haze + lit * 0.5) if haze > 0.55 else shade("sapphire", 1.2 + haze + lit * 0.5)
                elif g > 0.62 and (rnd.random() < (g - 0.62) * 3.2):
                    c = shade("indigo", 0.9 + lit + (0.8 - g) * 0.8)
                else:
                    c = shade("grape", self.base + lit + 1.4 * (0.62 - g) + (rnd.random() - 0.5) * 0.15)
                # facets: a lilac rim on the top and left edges, a dark one on the bottom and right
                if yy == 0 and ph > 2:
                    c = shade("crystal", 3.2 + lit)
                elif xx == 0 and pw > 2:
                    c = shade("crystal", 2.4 + lit)
                elif yy == ph - 1 and ph > 2:
                    c = shade("eggplant", 0.4)
                elif xx == pw - 1 and pw > 2:
                    c = shade("eggplant", 0.8)
                px[x, y] = c
        if self.const and face in self.const_faces and pw >= 8 and ph >= 8:
            self._constellation(px, pw, ph, face)
        n = max(1, int(pw * ph / 24 * self.density))
        for i in range(n):
            sx, sy = 1 + rnd.randrange(max(1, pw - 2)), 1 + rnd.randrange(max(1, ph - 2))
            b = (k + rnd.randrange(8)) % 8
            gold = rnd.random() < 0.35
            if b in (0, 1):
                c = shade("glow_amber", 3.0) if gold else shade("feather", 3)
            elif b in (2, 7):
                c = shade("glow_amber", 2.4) if gold else shade("ice", 2.6)
            elif b in (3, 6):
                c = shade("crystal", 3.6)
            else:
                c = shade("crystal", 2.6)
            if px[sx, sy] != CONST_INK:
                px[sx, sy] = c
        for i in range(max(0, int(pw * ph / 220))):
            sx, sy = 2 + rnd.randrange(max(1, pw - 4)), 2 + rnd.randrange(max(1, ph - 4))
            b = (k + rnd.randrange(8)) % 8
            px[sx, sy] = shade("glow_amber", 3.0)
            if b in (0, 1, 7):
                arm = shade("glow_amber", 2.2) if b == 0 else shade("crystal", 3.4)
                for (dx, dy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    px[sx + dx, sy + dy] = arm
        return im

    def _constellation(self, px, pw, ph, face):
        flip = face in ("south", "east")               # the back shows the same picture, not its mirror
        pts = [None if p is None else (round(((1 - p[0]) if flip else p[0]) * (pw - 1)), round(p[1] * (ph - 1))) for p in self.const]
        for a, b in zip(pts, pts[1:]):
            if a is None or b is None:
                continue
            steps = max(abs(b[0] - a[0]), abs(b[1] - a[1]), 1)
            for s in range(steps + 1):
                x = round(a[0] + (b[0] - a[0]) * s / steps)
                y = round(a[1] + (b[1] - a[1]) * s / steps)
                px[x, y] = shade("blade", 2.2)
        for p in pts:
            if p is None:
                continue
            x, y = p
            for (dx, dy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if 0 <= x + dx < pw and 0 <= y + dy < ph:
                    px[x + dx, y + dy] = shade("blade", 3)
            px[x, y] = CONST_INK

    def face(self, w, h, face, seed):
        pw, ph = max(1, math.ceil(w * D - 1e-6)), max(1, math.ceil(h * D - 1e-6))
        pw, ph = min(pw, HS), min(ph, HS)       # a longer face stretches its texture along it
        frames = [self._sky(pw, ph, face, seed, k) for k in range(8)]
        key = "hd_" + format(zlib.crc32(b"".join(f.tobytes() for f in frames)), "08x")
        if key not in T.SURFACES:
            first = frames[0]
            T.SURFACES[key] = (lambda img=first: img.copy())
            T.ANIMATED[key] = (lambda fs=frames: [f.copy() for f in fs], 3)
        return key, [0, 0, pw, ph]


CONST_INK = shade("feather", 3)         # a constellation's star: pure white, never painted over by the twinkles


SPACE_PLAIN = SpaceCrystal(1.0, density=0.8)

# ================================================================================================
# The other materials
# ================================================================================================

class BeamMat(HMat):
    """The light blade: a white-hot core, cyan round it, violet at the rims, a lilac edge texel; sparkles drifting up the
    beam (8 frames). Drawn at full light."""

    def __init__(self):
        super().__init__("aurora", "smooth", 3.0, glow=True, anim=8)

    def _beam(self, pw, ph, face, seed, k):
        rnd = random.Random(seed)
        im = hd_canvas()
        px = im.load()
        side = face in ("north", "south")
        for y in range(HS):
            for x in range(HS):
                xx, yy = min(x, pw - 1), min(y, ph - 1)
                u = (xx + 0.5) / pw
                d = abs(u - 0.5) * 2 if side else 0.75
                if face in ("up", "down"):
                    d = 0.5
                if d < 0.26:
                    c = shade("ice", 3)
                elif d < 0.42:
                    c = shade("glow_cyan", 2.6)
                elif d < 0.66:
                    c = shade("aurora", 2.6 - (d - 0.42) * 3)
                else:
                    c = shade("magic", 1.6 - (d - 0.66) * 1.5)
                if side and (xx == 0 or xx == pw - 1):
                    c = shade("crystal", 4)
                px[x, y] = c
        if side:
            for i in range(max(2, ph // 3)):
                sx = rnd.randrange(pw)
                sy = (rnd.randrange(ph) - k * 2) % ph
                d = abs((sx + 0.5) / pw - 0.5) * 2
                px[sx, sy] = shade("glow_amber", 3) if d < 0.4 else shade("feather", 3)
        return im

    def face(self, w, h, face, seed):
        pw, ph = max(1, math.ceil(w * D - 1e-6)), max(1, math.ceil(h * D - 1e-6))
        pw, ph = min(pw, HS), min(ph, HS)       # a longer face stretches its texture along it
        frames = [self._beam(pw, ph, face, seed, k) for k in range(8)]
        key = "hd_" + format(zlib.crc32(b"".join(f.tobytes() for f in frames)), "08x")
        if key not in T.SURFACES:
            first = frames[0]
            T.SURFACES[key] = (lambda img=first: img.copy())
            T.ANIMATED[key] = (lambda fs=frames: [f.copy() for f in fs], 2)
        return key, [0, 0, pw, ph]


class MoonMat(HMat):
    """Pale gold moon with a few soft grey-gold craters."""

    CRATERS = ((0.3, 0.35, 0.16), (0.68, 0.6, 0.12), (0.45, 0.78, 0.09), (0.8, 0.22, 0.08))

    def _value(self, x, y, pw, ph, face, rnd):
        b = super()._value(x, y, pw, ph, face, rnd)
        if pw >= 4 and ph >= 4:
            u, v = (x + 0.5) / pw, (y + 0.5) / ph
            for cu, cv, r in self.CRATERS:
                dd = math.hypot((u - cu) * pw, (v - cv) * ph) / max(1.0, r * max(pw, ph))
                if dd < 1:
                    b -= 0.7 if dd > 0.55 else 0.45
        return b


class PlanetMat(HMat):
    """A gas giant: soft horizontal bands of violet, cyan, rose and lilac, lit from the top left."""

    BANDS = (("crystal", 2.2), ("aurora", 2.4), ("crystal", 1.4), ("sakura", 1.2), ("aurora", 1.6), ("magic", 1.4))

    def _paint(self, pw, ph, face, seed, sweep=None):
        rnd = random.Random(seed)
        im = hd_canvas()
        px = im.load()
        lit = FACE_LIGHT.get(face, 0)
        for y in range(HS):
            for x in range(HS):
                xx, yy = min(x, pw - 1), min(y, ph - 1)
                u, v = (xx + 0.5) / pw, (yy + 0.5) / ph
                if face in ("up", "down"):
                    name, b = self.BANDS[0] if face == "up" else self.BANDS[-1]
                else:
                    wob = 0.05 * math.sin(u * 7 + seed % 5)
                    name, b = self.BANDS[min(len(self.BANDS) - 1, int((v + wob) * len(self.BANDS)))]
                b += lit * 0.8 + 0.4 * (0.5 - u) + (rnd.random() - 0.5) * 0.2
                if yy == 0 and ph > 2:
                    b += 0.5
                elif xx == pw - 1 and pw > 2:
                    b -= 0.4
                px[x, y] = shade(name, b)
        return im


BEAM = BeamMat()
BEAM_EDGE = HMat("crystal", "smooth", 3.6, glow=True, ao=False)
EDGE = HMat("crystal", "gem", 3.3)                  # light lilac edges and points
SILVER = HMat("blade", "metal", 1.9)
SILVER_LIGHT = HMat("blade", "metal", 2.6)
SILVER_DARK = HMat("fish_silver", "metal", 2.2)
VELVET = HMat("aubergine", "horn", 1.5)             # the grip's dark violet velvet wrap
MOON = MoonMat("lamp", "smooth", 2.55)               # pale gold crescents with craters
MOON_DARK = HMat("gold", "smooth", 2.2)
GOLD = HMat("gold", "metal", 2.2)
STAR = HMat("glow_amber", "gem", 2.4, glow=True)     # gold stars: lit like little lamps
STAR_WHITE = HMat("ice", "gem", 2.9, glow=True)
NUCLEUS = HMat("glow_amber", "gem", 2.9, glow=True, anim=8)
TAIL_A = HMat("glow_cyan", "smooth", 2.4, glow=True, anim=8)
TAIL_B = HMat("aurora", "smooth", 2.2, glow=True)
TAIL_C = HMat("magic", "smooth", 1.8, glow=True)
PLANET = PlanetMat("aurora", "smooth", 2.0)
RING = HMat("gold", "metal", 2.7)
RING_EDGE = HMat("blade", "metal", 3.0)
LINE = HMat("blade", "smooth", 2.8, ao=False)
LINE_GLOW = HMat("ice", "smooth", 2.6, glow=True, ao=False)


# ================================================================================================
# Shared parts
# ================================================================================================

def stud(m, x, y, z0, z1, s=1.0, mat=STAR):
    """A small four-point star through the part (shows front and back): a cross of two bars."""
    h = s / 2
    m.box("stud", (x - h, y - s, z0), (x + h, y + s, z1), mat)
    m.box("stud", (x - s, y - h, z0 + 0.05), (x + s, y + h, z1 - 0.05), mat)


def star(m, name, x, y, z0, z1, s, core=STAR, tips=STAR_WHITE):
    """A bigger four-point star: a gold core and two crossed tapering bars with white points."""
    m.box(name, (x - s * 0.36, y - s * 0.36, z0), (x + s * 0.36, y + s * 0.36, z1), core)
    m.box(name, (x - s * 0.18, y - s, z0 + 0.1), (x + s * 0.18, y + s, z1 - 0.1), tips)
    m.box(name, (x - s, y - s * 0.18, z0 + 0.15), (x + s, y + s * 0.18, z1 - 0.15), tips)


def handle(m):
    """Every piece's handle (y -4 .. 6.5, like the toys'): a silver pommel with a gold star under it, a grip of dark velvet
    with three silver wraps, a silver collar."""
    m.group("handle")
    m.box("pommel_star", (7.5, -4.75, 7.5), (8.5, -3.25, 8.5), STAR)
    m.box("pommel_star", (6.75, -4.25, 7.55), (9.25, -3.75, 8.45), STAR)
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.25, -1.25, SILVER, 0.75)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.25, 5.25, VELVET, 0.5)
    for y in (-0.25, 1.75, 3.75):
        round_y(m, "grip_wrap", 6.5, 9.5, 6.5, 9.5, y, y + 0.5, SILVER_LIGHT, 0.5)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, SILVER, 1)


def shaft(m, top):
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, SILVER_DARK, 0.5)


def disc(m, name, axis, c, d, lo, hi, mat):
    """A round disc of diameter d (stepped, like economy_machines.cyl) along y or z between lo and hi; c its centre on
    the two other axes ((x, z) for y, (x, y) for z). The HD materials need it: cyl repaints its side as a plain Mat."""
    for (a, b, hw) in bands(d):
        if axis == "y":
            m.box(name, (c[0] - hw, lo, c[1] + a), (c[0] + hw, hi, c[1] + b), mat)
        else:
            m.box(name, (c[0] - hw, c[1] + a, lo), (c[0] + hw, c[1] + b, hi), mat)


def ball(m, name, cx, cy, cz, d, mat):
    """A round ball: a disc along z, a smaller one in front and behind."""
    r = d / 2
    disc(m, name, "z", (cx, cy), d, cz - r + 1, cz + r - 1, mat)
    disc(m, name, "z", (cx, cy), d - 2, cz - r, cz - r + 1, mat)
    disc(m, name, "z", (cx, cy), d - 2, cz + r - 1, cz + r, mat)


def _snap(v):
    return round(v * 4) / 4


def ring_rows(cx, cy, a, b, ia=0.0, ib=0.0, icx=None, icy=None, y0=None, y1=None, step=1.0):
    """The rows of an ellipse (semi-axes a, b about cx, cy) less an inner one (ia, ib about icx, icy): a list of
    (x0, x1, y0, y1) spans, one unit high, snapped to quarter units."""
    icx = cx if icx is None else icx
    icy = cy if icy is None else icy
    y0 = cy - b if y0 is None else y0
    y1 = cy + b if y1 is None else y1
    out = []
    y = y0
    while y < y1 - 1e-6:
        top = min(y + step, y1)
        ym = (y + top) / 2
        t = 1 - ((ym - cy) / b) ** 2
        if t > 0:
            h = a * math.sqrt(t)
            spans = [(cx - h, cx + h)]
            if ia and ib and abs(ym - icy) < ib:
                ih = ia * math.sqrt(1 - ((ym - icy) / ib) ** 2)
                lo, hi = icx - ih, icx + ih
                spans = [(s0, min(s1, lo)) for s0, s1 in spans if s0 < lo] + [(max(s0, hi), s1) for s0, s1 in spans if s1 > hi]
            for s0, s1 in spans:
                s0, s1 = _snap(s0), _snap(s1)
                if s1 - s0 >= 0.5:
                    out.append((s0, s1, y, top))
        y = top
    return out


class Scaled(MModel):
    """A model built in bigger units than it is stored in: every box is shrunk by k about the grip, and _scaled_held()
    makes the held views k times bigger again, so a blade longer than the -16 .. 32 box allows still fits."""

    def __init__(self, name, k):
        super().__init__(name)
        self.k = k

    def _p(self, p):
        k = self.k
        return (C + (p[0] - C) * k, GRIP_Y + (p[1] - GRIP_Y) * k, C + (p[2] - C) * k)

    def box(self, name, frm, to, tex, rotation=None, **kw):
        if rotation:
            rotation = (rotation[0], rotation[1], self._p(rotation[2]))
        return super().box(name, self._p(frm), self._p(to), tex, rotation=rotation, **kw)


def _scaled_held(m, kind):
    _held(m, kind)
    f = 1 / m.k
    held = presets.enlarged({c: m.display[c] for c in ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand",
                                                       "firstperson_lefthand")}, f, (0.0, GRIP_Y - 8.0, 0.0))
    m.display.update(held)
    return m


# ================================================================================================
# The tools
# ================================================================================================

SWORD_K = 0.875


def galaxy_sword():
    """별빛 광선검: a long blade of light (a white core, cyan, violet rims, sparkles drifting up it), 1.3 times the toy
    sword's, rising out of a pale-gold crescent moon with craters (horns up) for a guard, a gold star on the moon.
    Built in units 1 / 0.875 of the stored ones (Scaled), so the long blade fits the model box."""
    m = Scaled("galaxy_sword", SWORD_K)
    handle(m)
    m.group("guard")
    round_y(m, "guard_socket", 6.5, 9.5, 6.5, 9.5, 6.5, 8, SILVER, 0.75)
    for x0, x1, y0, y1 in ring_rows(C, 11.5, 6, 5, 4.75, 4.5, icy=13.5, y0=6.5, y1=12.5):
        m.box("moon", (x0, y0, 6.75), (x1, y1, 9.25), MOON)
    stud(m, C, 8.4, 6.4, 9.6, 1.2)
    m.group("blade")
    m.box("blade", (5.75, 9, 7.25), (10.25, 33, 8.75), BEAM)
    m.box("blade_rim", (5.25, 10, 7.5), (5.75, 32.25, 8.5), BEAM_EDGE)
    m.box("blade_rim", (10.25, 10, 7.5), (10.75, 32.25, 8.5), BEAM_EDGE)
    m.box("blade_tip", (6.5, 33, 7.4), (9.5, 34.25, 8.6), BEAM)
    m.box("blade_tip", (7.25, 34.25, 7.55), (8.75, 35.25, 8.45), BEAM_EDGE)
    m.box("blade_tip", (7.625, 35.25, 7.7), (8.375, 35.75, 8.3), STAR_WHITE)
    return _scaled_held(m, "sword")


def galaxy_pickaxe():
    """혜성 곡괭이: a blazing comet on the shaft: a gold-white nucleus, its two tails streaming out to the sides and drooping
    as the pick's arms, cyan near the head, then aqua, then violet tapering to a point, a few sparks along them."""
    m = MModel("galaxy_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("comet")
    ball(m, "nucleus", C, 18.5, C, 6, NUCLEUS)
    for side in (1, -1):
        p = (C + side * 2.25, 19)
        p = _arm(m, "tail_a", side, p, 4.25, 1.75, 6.75, 9.25, 0, TAIL_A)
        p = _arm(m, "tail_b", side, p, 4.0, 1.25, 7, 9, 22.5, TAIL_B)
        p = _arm(m, "tail_c", side, p, 3.25, 0.75, 7.25, 8.75, 45, TAIL_C)
        px, py = p
        a = math.radians(45)
        tx, ty = px + side * 0.9 * math.cos(a), py - 0.9 * math.sin(a)
        m.box("tail_tip", (tx - 0.4, ty - 0.4, 7.5), (tx + 0.4, ty + 0.4, 8.5), STAR_WHITE, rotation=("z", -45 * side, (tx, ty, C)))
        # sparks beside the tails
        m.box("spark", (C + side * 5.5 - 0.375, 21.5, 7.625), (C + side * 5.5 + 0.375, 22.25, 8.375), STAR, rotation=("z", 45, (C + side * 5.5, 21.875, C)))
    m.box("nucleus_glint", (6.5, 20, 4.7), (7.5, 21, 5.05), STAR_WHITE)
    m.box("nucleus_glint", (8.5, 20, 10.95), (9.5, 21, 11.3), STAR_WHITE)
    return _held(m, "pickaxe")


def galaxy_shovel():
    """초승달 삽: a pale-gold crescent moon with craters lying horns up as the scoop, a gold star resting in its hollow,
    a silver cup where it meets the shaft."""
    m = MModel("galaxy_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("moon")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15, 16.5, SILVER, 0.75)
    for x0, x1, y0, y1 in ring_rows(C, 22, 6.5, 6.5, 5.25, 5.5, icy=25, y0=15.5, y1=26):
        m.box("moon", (x0, y0, 6.5), (x1, y1, 9.5), MOON)
    star(m, "star", C, 22.25, 7, 9, 2.25)
    m.box("horn_star", (2, 25.75, 7.5), (2.75, 26.5, 8.5), STAR_WHITE)
    m.box("horn_star", (13.25, 25.75, 7.5), (14, 26.5, 8.5), STAR_WHITE)
    return _held(m, "shovel")


def galaxy_axe():
    """토성 도끼: a banded ringed planet on the shaft; its ring, broad and tilted, is the blade: wide out to the -x side
    with a silver cutting edge, narrow behind."""
    m = MModel("galaxy_axe")
    handle(m)
    shaft(m, 16.5)
    m.group("planet")
    pcx, pcy = 8.5, 19.5
    ball(m, "planet", pcx, pcy, C, 7, PLANET)
    m.group("ring")
    rot = ("z", 22.5, (pcx, pcy, C))
    # the outer ring (gold, the blade: a silver cutting edge out at -x), a dark gap, the inner ring (lilac)
    for x0, x1, y0, y1 in ring_rows(5.5, pcy, 8.0, 4.0, 6.25, 3.0, icx=6):
        if x0 < 2.5:
            m.box("ring_edge", (x0 - 0.5, y0, 7.6), (x0 + 0.25, y1, 8.4), RING_EDGE, rotation=rot)
            x0 += 0.25
        m.box("ring", (x0, y0, 7.4), (x1, y1, 8.6), RING, rotation=rot)
    for x0, x1, y0, y1 in ring_rows(6, pcy, 5.75, 2.75, 4.25, 1.75, icx=pcx):
        m.box("ring_inner", (x0, y0, 7.5), (x1, y1, 8.5), EDGE, rotation=rot)
    return _held(m, "axe")


def galaxy_hoe():
    """별자리 지팡이: the vanilla hoe's L: a straight head out to -x from the top of the shaft, a bar of night sky with
    three stars on it joined by a glowing constellation line (front and back), ending flat in a silver cap."""
    m = MModel("galaxy_hoe")
    handle(m)
    shaft(m, 20)
    m.group("head")
    y = 21.5
    m.box("bar", (-2.25, y - 1, 7.3), (7, y + 1, 8.7), SPACE_PLAIN)
    for z0, z1 in ((7.1, 7.3), (8.7, 8.9)):
        m.box("line", (-2, y - 0.2, z0), (7, y + 0.2, z1), LINE_GLOW)
    star(m, "star", 8, y, 6.75, 9.25, 2.75)
    star(m, "star", 3.5, y, 6.9, 9.1, 2.25)
    star(m, "star", -0.5, y, 7, 9, 2)
    m.box("end", (-4, y - 1.75, 6.75), (-2.25, y + 1.75, 9.25), SILVER)
    m.box("end_star", (-4.25, y - 0.6, 7.4), (-4, y + 0.6, 8.6), STAR)
    return _held(m, "hoe")


DIPPER = (8.5, 11.75, 15, 18.25, 21.5, 24.75, 27.75)      # 북두칠성: seven stars up the rod


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9, 7.75, 7.5), (10, 9.25, 8.5), SILVER_DARK)
    round_z(m, "reel", 10, 14, 6.5, 10.5, 6.75, 9.25, SILVER, 1)
    m.box("reel_hub", (11.25, 7.75, 6.25), (12.75, 9.25, 9.75), VELVET)
    m.box("crank", (11.5, 8, 9.75), (12.5, 11.25, 10.25), SILVER_LIGHT)
    stud(m, 12, 11.5, 10, 10.75, 1.1)
    m.group("rod")
    round_y(m, "rod", 6.875, 9.125, 6.875, 9.125, 6.5, 14, SPACE_PLAIN, 0.5)
    m.box("rod", (7.125, 14, 7.125), (8.875, 22, 8.875), SPACE_PLAIN)
    m.box("rod", (7.375, 22, 7.375), (8.625, 29.5, 8.625), SPACE_PLAIN)
    m.box("ferrule", (6.75, 13.5, 6.75), (9.25, 14.5, 9.25), SILVER)
    m.box("ferrule", (7, 21.5, 7), (9, 22.5, 9), SILVER)
    m.group("dipper")
    # the seven stars: guides on the +x side, where the line runs; the last at the tip the biggest
    for i, y in enumerate(DIPPER):
        s = 0.8 if i < 6 else 1.1
        x = 9.1 + (0.25 if i < 2 else 0) + s
        m.box("dipper_line", (8.6 if y > 22 else 8.85 if y > 14 else 9.1, y - 0.15, 7.85), (x, y + 0.15, 8.15), LINE)
        stud(m, x, y, 7.4, 8.6, s)
    star(m, "tip_star", C, 30.5, 7.25, 8.75, 1.5)
    if not cast:
        m.group("line")
        top = (8.5, 30, C)
        rot = ("z", 45, top)
        m.box("line", (8.25, 19, 7.75), (8.75, 30, 8.25), LINE, rotation=rot)
        # the lure: a gold star
        m.box("lure", (7.25, 16, 7.25), (9.75, 18.5, 8.75), STAR, rotation=rot)
        m.box("lure", (8, 14.25, 7.6), (9, 20.25, 8.4), STAR_WHITE, rotation=rot)
        m.box("lure", (5.5, 16.75, 7.6), (11.5, 17.75, 8.4), STAR_WHITE, rotation=rot)
    return _held(m, "rod")


def galaxy_fishing_rod():
    """북두칠성 낚싯대: a slim night-sky rod with silver ferrules, the seven stars of the Big Dipper climbing it as guides,
    a bigger star at the tip and a gold star for a lure."""
    return _rod("galaxy_fishing_rod", False)


def galaxy_fishing_rod_cast():
    """The rod while its lure is out: the same without the line and the lure."""
    m = _rod("galaxy_fishing_rod_cast", True)
    m.display = galaxy_fishing_rod().display
    return m


BOBBER_WATERLINE = 4.0        # the star's four side points lie on the water's surface (model y)


def galaxy_bobber():
    """은하수 찌: the star lure riding the hook: a glowing gold star with six points (up, down and four round it), the four
    side points on the water, white tips. The same from every side. Stands on y = 0 about x = z = 8."""
    m = MModel("galaxy_bobber")
    m.group("star")
    w = BOBBER_WATERLINE
    m.box("core", (6.25, w - 1.75, 6.25), (9.75, w + 1.75, 9.75), STAR)
    m.box("point_y", (7, 0, 7), (9, w + 4, 9), STAR)
    m.box("point_y", (7.5, w + 4, 7.5), (8.5, w + 5.5, 8.5), STAR_WHITE)
    m.box("point_x", (2.5, w - 1, 7.05), (13.5, w + 1, 8.95), STAR)
    m.box("point_x", (1, w - 0.5, 7.55), (2.5, w + 0.5, 8.45), STAR_WHITE)
    m.box("point_x", (13.5, w - 0.5, 7.55), (15, w + 0.5, 8.45), STAR_WHITE)
    m.box("point_z", (7.1, w - 0.9, 2.5), (8.9, w + 0.9, 13.5), STAR)
    m.box("point_z", (7.6, w - 0.45, 1), (8.4, w + 0.45, 2.5), STAR_WHITE)
    m.box("point_z", (7.6, w - 0.45, 13.5), (8.4, w + 0.45, 15), STAR_WHITE)
    m.display = presets.furniture(gui_scale=1.3, gui_translation=(0, 3.25, 0))
    m.kind = "bobber"
    return m


ALL = {f.__name__: f for f in (galaxy_sword, galaxy_pickaxe, galaxy_shovel, galaxy_axe, galaxy_hoe, galaxy_fishing_rod,
                               galaxy_fishing_rod_cast, galaxy_bobber)}

ROWS = [
    ("galaxy_sword", "별빛 광선검"), ("galaxy_pickaxe", "혜성 곡괭이"), ("galaxy_shovel", "초승달 삽"), ("galaxy_axe", "토성 도끼"),
    ("galaxy_hoe", "별자리 지팡이"), ("galaxy_fishing_rod", "북두칠성 낚싯대"), ("galaxy_fishing_rod_cast", "낚싯대 (던진 뒤)"),
    ("galaxy_bobber", "별 찌 (찌에 태우는 모델)"),
]


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("galaxy_sword", "galaxy_pickaxe", "galaxy_shovel", "galaxy_axe", "galaxy_hoe", "galaxy_bobber")}
    out["galaxy_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/galaxy_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/galaxy_fishing_rod_cast"},
    }}
    return out
