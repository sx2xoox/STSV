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
SPACE_SHAFT = SpaceCrystal(1.15, density=0.7)
SPACE_RICH = SpaceCrystal(1.0, density=1.2)

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
GAP = HMat("indigo", "smooth", 1.0)                 # the dark gap between the ring's bands
SPADE = SpaceCrystal(1.0, density=1.1, const=[(0.12, 0.3), (0.3, 0.2), (0.45, 0.42), (0.62, 0.35), (0.62, 0.7), (0.88, 0.78), (0.86, 0.45), (0.62, 0.35)])


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
    with three silver wraps, a silver collar set with a gold star front and back."""
    m.group("handle")
    m.box("pommel_star", (7.5, -4.75, 7.5), (8.5, -3.25, 8.5), STAR)
    m.box("pommel_star", (6.75, -4.25, 7.55), (9.25, -3.75, 8.45), STAR)
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.25, -1.25, SILVER, 0.75)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.25, 5.25, VELVET, 0.5)
    for y in (-0.25, 1.75, 3.75):
        m.box("grip_wrap", (6.6, y, 6.6), (9.4, y + 0.5, 9.4), SILVER_LIGHT)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, SILVER, 1)
    m.box("collar_gem", (7.4, 5.4, 5.7), (8.6, 6.35, 10.3), STAR)


def shaft(m, top):
    """A shaft of night sky (the space crystal, stars twinkling up it) with a silver band a little under its top."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, SPACE_SHAFT, 0.5)
    m.box("shaft_band", (6.7, top - 3, 6.7), (9.3, top - 2.5, 9.3), SILVER_LIGHT)


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
    for x in (2.75, 13.25):
        m.box("horn_star", (x - 0.55, 12.45, 7.45), (x + 0.55, 13.55, 8.55), STAR_WHITE, rotation=("z", 45, (x, 13, C)))
    m.group("blade")
    m.box("blade", (5.75, 9, 7.25), (10.25, 33, 8.75), BEAM)
    m.box("blade_rim", (5.25, 10, 7.5), (5.75, 32.25, 8.5), BEAM_EDGE)
    m.box("blade_rim", (10.25, 10, 7.5), (10.75, 32.25, 8.5), BEAM_EDGE)
    m.box("blade_tip", (6.5, 33, 7.4), (9.5, 34.25, 8.6), BEAM)
    m.box("blade_tip", (7.25, 34.25, 7.55), (8.75, 35.25, 8.45), BEAM_EDGE)
    m.box("blade_tip", (7.625, 35.25, 7.7), (8.375, 35.75, 8.3), STAR_WHITE)
    return _scaled_held(m, "sword")


def _seg(m, name, side, pivot, length, half_h, z0, z1, angle, mat, streak=None, back=0.5):
    """One piece of a comet tail (like toys._arm: along x from its pivot, drooping by angle), with a white streak
    along its top edge. Returns the centre line's end, the next piece's pivot."""
    px, py = pivot
    rot = ("z", -angle * side, (px, py, C))
    if side > 0:
        x0, x1 = px - back, px + length
    else:
        x0, x1 = px - length, px + back
    m.box(name, (x0, py - half_h, z0), (x1, py + half_h, z1), mat, rotation=rot)
    if streak is not None:
        m.box(name + "_streak", (x0 + 0.25, py + half_h - 0.5, z0 - 0.1), (x1 - 0.25, py + half_h + 0.2, z1 + 0.1), streak, rotation=rot)
    a = math.radians(angle)
    return px + side * (length - 0.5) * math.cos(a), py - (length - 0.5) * math.sin(a)


def galaxy_pickaxe():
    """혜성 곡괭이: a block of night sky banded in silver with a gold cap is the head; a blazing comet nucleus (a big gold
    star) shines through its middle, front and back, and its two tails stream out as the pick's arms, drooping in three
    bends from cyan through aqua to violet, a white streak along each, white-hot points."""
    m = MModel("galaxy_pickaxe")
    handle(m)
    shaft(m, 15.5)
    m.group("head")
    m.box("head", (5.25, 15, 6.25), (10.75, 21, 9.75), SPACE_RICH)
    for x0 in (4.875, 10.375):
        m.box("head_band", (x0, 14.75, 6), (x0 + 0.75, 21.25, 10), SILVER_LIGHT)
    m.box("head_cap", (6, 21, 6.5), (10, 21.75, 9.5), GOLD)
    m.box("head_crown", (7.25, 21.75, 7.25), (8.75, 22.5, 8.75), STAR_WHITE)
    star(m, "nucleus", C, 18, 5.6, 10.4, 2.1, core=NUCLEUS)
    m.group("tails")
    for side in (1, -1):
        p = (C + side * 3.25, 18.5)
        p = _seg(m, "tail_a", side, p, 4.25, 1.75, 6.6, 9.4, 0, TAIL_A, STAR_WHITE)
        p = _seg(m, "tail_b", side, p, 3.75, 1.3, 6.85, 9.15, 22.5, TAIL_B, TAIL_A)
        p = _seg(m, "tail_c", side, p, 3.0, 0.85, 7.1, 8.9, 45, TAIL_C, TAIL_B)
        px, py = p
        a = math.radians(45)
        tx, ty = px + side * 0.6 * math.cos(a), py - 0.6 * math.sin(a)
        m.box("tail_tip", (tx - 0.5, ty - 0.5, 7.4), (tx + 0.5, ty + 0.5, 8.6), STAR_WHITE, rotation=("z", 45, (tx, ty, C)))
        # sparks shed above the tails
        sx = C + side * 6.25
        m.box("spark", (sx - 0.4, 22.1, 7.6), (sx + 0.4, 22.9, 8.4), STAR, rotation=("z", 45, (sx, 22.5, C)))
        sx = C + side * 9.5
        m.box("spark", (sx - 0.3, 19.7, 7.7), (sx + 0.3, 20.3, 8.3), STAR_WHITE, rotation=("z", 45, (sx, 20, C)))
    return _held(m, "pickaxe")


def galaxy_shovel():
    """초승달 삽: a spade of night sky (twinkling stars, a constellation on its face) with a silver rim and point, held
    in a pale-gold crescent moon lying horns up: the moon cups the blade's base as its shoulders and its horns rise
    beside the blade, a white star on each horn tip; a gold star shines on the blade."""
    m = MModel("galaxy_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    m.box("socket", (6.4, 15, 6.4), (9.6, 16.75, 9.6), SILVER)
    rows = ((16.5, 25.5, 4.0), (25.5, 27.25, 3.25), (27.25, 28.5, 2.25))
    for y0, y1, hw in rows:
        m.box("blade", (C - hw, y0, 7.25), (C + hw, y1, 8.75), SPADE if hw == 4.0 else SPACE_PLAIN)
        for s in (-1, 1):
            e = C + s * hw
            lo, hi = (e - 0.375, e) if s < 0 else (e, e + 0.375)
            m.box("rim", (lo, max(y0, 19.5), 7.05), (hi, y1, 8.95), SILVER_LIGHT)
    m.box("point", (6.5, 28.5, 7.1), (9.5, 29.5, 8.9), SILVER_LIGHT)
    m.box("point", (7.25, 29.5, 7.3), (8.75, 30.25, 8.7), RING_EDGE)
    m.box("point_star", (7.6, 30.25, 7.6), (8.4, 31, 8.4), STAR_WHITE)
    star(m, "star", C, 23.5, 6.9, 9.1, 1.8)
    m.group("moon")
    for x0, x1, y0, y1 in ring_rows(C, 19.25, 5.5, 4.25, 3.75, 3.5, icy=21.0, y0=15.25, y1=23.25, step=1.0):
        m.box("moon", (x0, y0, 6.6), (x1, y1, 9.4), MOON)
    for x in (3.1, 12.9):
        m.box("horn_star", (x - 0.5, 23.25, 7.5), (x + 0.5, 24.25, 8.5), STAR_WHITE, rotation=("z", 45, (x, 23.75, C)))
    return _held(m, "shovel")


def galaxy_axe():
    """토성 도끼: a banded ringed planet on the shaft's top; the half of its ring in front of it, broad and flat, is the
    axe's blade out to -x: a silver cutting edge, a gold band, a dark gap and a lilac inner band, curving like an axe's
    bit; the ring's far side shows behind the planet (+x) as a short tapering band, a white moonlet at its end."""
    m = MModel("galaxy_axe")
    handle(m)
    shaft(m, 17)
    m.group("planet")
    pcx, pcy = 9.0, 19.75
    ball(m, "planet", pcx, pcy, C, 7, PLANET)
    m.group("ring")
    bands = ((9.0, 6.75, 8.25, 6.0, RING_EDGE, 7.6, 8.4), (8.25, 6.0, 6.0, 4.4, RING, 7.35, 8.65),
             (6.0, 4.4, 3.5, 2.75, EDGE, 7.45, 8.55))
    for i, (a, b, ia, ib, mat, z0, z1) in enumerate(bands):
        for x0, x1, y0, y1 in ring_rows(pcx, pcy, a, b, ia, ib):
            x1 = min(x1, pcx - 1.25 - 0.25 * i)
            if x1 - x0 >= 0.5:
                m.box("ring", (x0, y0, z0), (x1, y1, z1), mat)
    m.box("ring_far", (pcx + 3, pcy - 0.75, 7.45), (pcx + 5, pcy + 0.75, 8.55), RING)
    m.box("ring_far", (pcx + 5, pcy - 0.5, 7.6), (pcx + 6.25, pcy + 0.5, 8.4), EDGE)
    m.box("moonlet", (pcx + 6.25, pcy - 0.6, 7.4), (pcx + 7.25, pcy + 0.6, 8.6), STAR_WHITE)
    return _held(m, "axe")


def galaxy_hoe():
    """별자리 지팡이: the vanilla hoe's L: a straight bar of night sky out to -x from the top of the shaft, silver trims
    along its top and bottom, three gold stars on it joined by a glowing constellation line (front and back); a gold
    star crowns the shaft; the bar ends flat in a silver blade a little deeper than the bar behind a gold band."""
    m = MModel("galaxy_hoe")
    handle(m)
    shaft(m, 20.25)
    m.group("head")
    y = 21.5
    m.box("bar", (-0.5, y - 1.375, 6.875), (9.25, y + 1.375, 9.125), SPACE_PLAIN)
    for y0 in (y + 1.125, y - 1.625):
        m.box("trim", (-0.25, y0, 6.75), (9.0, y0 + 0.5, 9.25), SILVER_LIGHT)
    for z0, z1 in ((6.72, 6.875), (9.125, 9.28)):
        m.box("line", (0.5, y - 0.2, z0), (6.25, y + 0.2, z1), LINE_GLOW)
    star(m, "star", 6.25, y, 6.5, 9.5, 1.6)
    star(m, "star", 3.25, y, 6.55, 9.45, 1.4)
    star(m, "star", 0.5, y, 6.6, 9.4, 1.25)
    round_y(m, "crown", 6.5, 9.5, 6.5, 9.5, y + 1.375, y + 2.125, GOLD, 0.75)
    m.box("crown_star", (7.5, y + 2.125, 7.5), (8.5, y + 3.5, 8.5), STAR_WHITE)
    m.box("crown_star", (7, y + 2.4, 7.55), (9, y + 3.0, 8.45), STAR)
    m.box("band", (-1, y - 1.75, 6.6), (-0.5, y + 1.75, 9.4), GOLD)
    m.box("blade", (-3, y - 2.25, 6.9), (-1, y + 1.5, 9.1), SILVER)
    m.box("blade_edge", (-3.6, y - 2.0, 7.15), (-3, y + 1.25, 8.85), RING_EDGE)
    return _held(m, "hoe")


DIPPER = (12.25, 15.25, 18.25, 21.25, 24.0, 26.75)      # 북두칠성: six stars up the rod, the seventh at its tip


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9, 7.75, 7.5), (10.25, 9.25, 8.5), SILVER_DARK)
    round_z(m, "reel", 10.25, 14.75, 6.25, 10.75, 6.9, 9.1, SILVER, 1.25)
    round_z(m, "reel_face", 11.0, 14.0, 7.0, 10.0, 6.65, 9.35, SPACE_PLAIN, 0.75)
    m.box("reel_hub", (12.0, 8.0, 6.4), (13.0, 9.0, 9.6), STAR)
    m.box("crank", (12.75, 8.25, 9.6), (15.25, 8.75, 10.1), SILVER_LIGHT)
    m.box("crank_knob", (15.25, 7.9, 9.4), (16.25, 9.1, 10.6), STAR_WHITE)
    m.group("rod")
    round_y(m, "rod", 6.875, 9.125, 6.875, 9.125, 6.5, 14, SPACE_PLAIN, 0.5)
    m.box("rod", (7.125, 14, 7.125), (8.875, 22, 8.875), SPACE_PLAIN)
    m.box("rod", (7.375, 22, 7.375), (8.625, 29.5, 8.625), SPACE_PLAIN)
    for y0 in (10.5, 13.25):
        m.box("winding", (6.7, y0, 6.7), (9.3, y0 + 0.5, 9.3), GOLD)
    m.box("ferrule", (6.75, 13.75, 6.75), (9.25, 14.5, 9.25), SILVER)
    m.box("ferrule", (7, 21.5, 7), (9, 22.5, 9), SILVER)
    m.group("dipper")
    # six stars as the line guides on the +x side, each on a silver stem; the seventh, the biggest, at the tip
    for i, y in enumerate(DIPPER):
        s = 0.85 - i * 0.04
        r = 8.875 if y < 22 else 8.625
        x = r + 0.35 + s
        m.box("guide", (r - 0.1, y - 0.2, 7.8), (x - s * 0.36, y + 0.2, 8.2), SILVER_LIGHT)
        stud(m, x, y, 7.45, 8.55, s)
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
