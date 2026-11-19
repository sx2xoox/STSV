"""
은하수 시리즈 (galaxy tool skins): a donation set of held-tool looks that matches the 은하수 cosmetic set
(models/cosmetics_galaxy.py): deep indigo-violet "space crystal" with a twinkling starfield and a faint pink-cyan
nebula, silver constellations inlaid in it, pale-gold crescent moons, gold star studs, silver grips wrapped in dark
velvet.

    galaxy_sword  galaxy_pickaxe  galaxy_shovel  galaxy_axe  galaxy_hoe  galaxy_fishing_rod (+ galaxy_fishing_rod_cast)
    galaxy_bobber

Same frame, sizes and display transforms as the toy series (models/toys.py: built upright, the grip at GRIP_Y on the
axis x = z = 8, _held() puts the vanilla tilt into the transforms). Painted by the donation rules (premium_art.py: 32
texels a block, ramp colours and their in-between steps): the crystal is its own HMat (SpaceCrystal) whose stars
twinkle in turn (8 frames) and which can carry a constellation, drawn on its front and back.

    sword    a crystal blade with a constellation, a crescent-moon guard, a star at the guard's heart
    pickaxe  crystal arms drooping in two bends, each ending in a four-point gold star
    shovel   a crystal spade with a silver rim and a constellation, a star at its neck
    axe      a crescent-moon head of crystal with a silver edge, a silver poll with a star
    hoe      a ringed planet at the head's heart, a silver bar to a crystal plate
    rod      a slim silver rod with star guides, a small silver reel, a crescent moon at the tip; the float a tiny planet
"""

import math
import random
import zlib

import economy_models  # noqa: F401  (registers the shared surfaces)
from economy_machines import MModel
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
        big = pw > HS or ph > HS
        if big:
            pw, ph = min(pw, HS), min(ph, HS)
        frames = [self._sky(pw, ph, face, seed, k) for k in range(8)]
        key = "hd_" + format(zlib.crc32(b"".join(f.tobytes() for f in frames)), "08x")
        if key not in T.SURFACES:
            first = frames[0]
            T.SURFACES[key] = (lambda img=first: img.copy())
            T.ANIMATED[key] = (lambda fs=frames: [f.copy() for f in fs], 3)
        return key, [0, 0, pw, ph] if not big else [0, 0, HS, HS]


CONST_INK = shade("feather", 3)         # a constellation's star: pure white, never painted over by the twinkles

# constellations (u, v on the face, v down)
CONST_BLADE = [(0.35, 0.12), (0.62, 0.26), (0.42, 0.44), (0.66, 0.6), (0.4, 0.82), None, (0.42, 0.44), (0.3, 0.58)]
CONST_SPADE = [(0.3, 0.2), (0.62, 0.32), (0.5, 0.58), (0.72, 0.78), None, (0.5, 0.58), (0.28, 0.72)]
CONST_PLATE = [(0.25, 0.2), (0.6, 0.35), (0.4, 0.62), (0.72, 0.82)]

SPACE = SpaceCrystal(1.0)
SPACE_PLAIN = SpaceCrystal(1.0, density=0.8)
SPACE_BLADE = SpaceCrystal(1.0, const=CONST_BLADE)
SPACE_SPADE = SpaceCrystal(1.0, const=CONST_SPADE)
SPACE_PLATE = SpaceCrystal(1.0, const=CONST_PLATE, const_faces=("west",))

# ================================================================================================
# The other materials
# ================================================================================================

EDGE = HMat("crystal", "gem", 3.3)                  # the crystal's light lilac edges and points
SILVER = HMat("blade", "metal", 1.9)
SILVER_LIGHT = HMat("blade", "metal", 2.6)
SILVER_DARK = HMat("fish_silver", "metal", 2.2)
VELVET = HMat("aubergine", "horn", 1.5)             # the grip's dark violet velvet wrap
MOON = HMat("lamp", "smooth", 2.55)                  # pale gold crescents
MOON_DARK = HMat("gold", "smooth", 2.2)
GOLD = HMat("gold", "metal", 2.2)
STAR = HMat("glow_amber", "gem", 2.4, glow=True)     # gold star studs: lit like little lamps
STAR_WHITE = HMat("ice", "gem", 2.9, glow=True)
PLANET = HMat("aurora", "gem", 2.0)
PLANET_BAND = HMat("magic", "smooth", 1.6)
RING = HMat("gold", "metal", 2.7)
LINE = HMat("blade", "smooth", 2.8, ao=False)


# ================================================================================================
# Shared parts
# ================================================================================================

def stud(m, x, y, z0, z1, s=1.0, mat=STAR):
    """A small four-point star through the part (shows front and back): a cross of two bars."""
    h = s / 2
    m.box("stud", (x - h, y - s, z0), (x + h, y + s, z1), mat)
    m.box("stud", (x - s, y - h, z0), (x + s, y + h, z1), mat)


def handle(m):
    """Every piece's handle (y -4 .. 6.5, like the toys'): a silver pommel with a gold star under it, a grip of dark velvet
    with three silver wraps, a silver collar with a gold stud front and back."""
    m.group("handle")
    m.box("pommel_star", (7.5, -4.75, 7.5), (8.5, -3.25, 8.5), STAR)
    m.box("pommel_star", (6.75, -4.25, 7.5), (9.25, -3.75, 8.5), STAR)
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.25, -1.25, SILVER, 0.75)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.25, 5.25, VELVET, 0.5)
    for y in (-0.25, 1.75, 3.75):
        round_y(m, "grip_wrap", 6.5, 9.5, 6.5, 9.5, y, y + 0.5, SILVER_LIGHT, 0.5)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, SILVER, 1)


def shaft(m, top):
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, SILVER_DARK, 0.5)


def planet(m, cx, cy, cz, d, ring_w, tilt=22.5):
    """A ringed planet: a ball with a band round its middle and a flat gold ring round it, tilted about z."""
    m.group("planet")
    r = d / 2
    round_z(m, "planet", cx - r, cx + r, cy - r, cy + r, cz - r + 0.75, cz + r - 0.75, PLANET, r * 0.42)
    round_z(m, "planet", cx - r + 0.75, cx + r - 0.75, cy - r + 0.75, cy + r - 0.75, cz - r, cz + r, PLANET, r * 0.3)
    m.box("planet_band", (cx - r - 0.01, cy - 0.5, cz - r + 0.74), (cx + r + 0.01, cy + 0.25, cz + r - 0.74), PLANET_BAND)
    rot = ("z", tilt, (cx, cy, cz))
    hw = r + ring_w
    t = 0.25
    m.box("ring", (cx - hw, cy - t, cz - hw * 0.6), (cx - r + 0.25, cy + t, cz + hw * 0.6), RING, rotation=rot)
    m.box("ring", (cx + r - 0.25, cy - t, cz - hw * 0.6), (cx + hw, cy + t, cz + hw * 0.6), RING, rotation=rot)
    m.box("ring", (cx - r + 0.25, cy - t, cz - hw * 0.6), (cx + r - 0.25, cy + t, cz - r + 0.75), RING, rotation=rot)
    m.box("ring", (cx - r + 0.25, cy - t, cz + r - 0.75), (cx + r - 0.25, cy + t, cz + hw * 0.6), RING, rotation=rot)


# ================================================================================================
# The tools
# ================================================================================================

def galaxy_sword():
    """은하수 검: a space-crystal blade with a constellation and lilac edges, a pale-gold crescent-moon guard (horns up),
    a gold star at the guard's heart."""
    m = MModel("galaxy_sword")
    handle(m)
    m.group("guard")
    m.box("moon", (5, 6.5, 6.75), (11, 8.25, 9.25), MOON)
    for side in (1, -1):
        def bx(x0, x1, y0, y1, z0=6.75, z1=9.25, mat=MOON):
            a, b = (x0, x1) if side > 0 else (16 - x1, 16 - x0)
            m.box("moon", (a, y0, z0), (b, y1, z1), mat)
        bx(11, 12.5, 7, 9)
        bx(12.5, 13.5, 8, 10.25, 7, 9)
        bx(12.25, 13.25, 10.25, 11.75, 7.25, 8.75, MOON_DARK)
    stud(m, 8, 7.4, 6.4, 9.6, 1.1)
    m.group("blade")
    m.box("blade", (5.25, 8.25, 7), (10.75, 23.5, 9), SPACE_BLADE)
    m.box("blade_edge", (4.5, 9, 7.5), (5.25, 22.75, 8.5), EDGE)
    m.box("blade_edge", (10.75, 9, 7.5), (11.5, 22.75, 8.5), EDGE)
    m.box("blade_tip", (6.25, 23.5, 7.25), (9.75, 25, 8.75), SPACE_PLAIN)
    m.box("blade_tip", (7, 25, 7.5), (9, 26.25, 8.5), EDGE)
    m.box("blade_tip", (7.5, 26.25, 7.625), (8.5, 27.25, 8.375), EDGE)
    m.box("blade_edge", (5.25, 22.75, 7.5), (6.25, 23.75, 8.5), EDGE)
    m.box("blade_edge", (9.75, 22.75, 7.5), (10.75, 23.75, 8.5), EDGE)
    return _held(m, "sword")


def galaxy_pickaxe():
    """은하수 곡괭이: crystal arms drooping in two bends with silver bands, each ending in a gold four-point star; a crystal
    head with a silver cap and a star stud."""
    m = MModel("galaxy_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    m.box("head", (5, 15.25, 6.5), (11, 20.75, 9.5), SPACE_PLAIN)
    m.box("head_cap", (5.5, 20.75, 6.75), (10.5, 21.5, 9.25), SILVER)
    m.box("head_band", (4.75, 15, 6.25), (11.25, 15.75, 9.75), SILVER)
    stud(m, 8, 18, 6.25, 9.75, 1.25)
    for side in (1, -1):
        p = (C + side * 3, 18.25)
        p = _arm(m, "arm_a", side, p, 4.5, 1.75, 6.75, 9.25, 22.5, SPACE)
        p = _arm(m, "arm_b", side, p, 4.0, 1.375, 7, 9, 45, SPACE)
        px, py = p
        # a silver band at the bend, then the star: a cross along the last bend (an X on the sprite)
        a = math.radians(45)
        cx, cy = px + side * 1.5 * math.cos(a), py - 1.5 * math.sin(a)
        rot = ("z", -45 * side, (cx, cy, C))
        m.box("arm_star", (cx - 1.75, cy - 0.45, 7.25), (cx + 1.75, cy + 0.45, 8.75), GOLD, rotation=rot)
        m.box("arm_star", (cx - 0.45, cy - 1.75, 7.25), (cx + 0.45, cy + 1.75, 8.75), GOLD, rotation=rot)
        m.box("arm_star", (cx - 0.9, cy - 0.9, 7), (cx + 0.9, cy + 0.9, 9), STAR, rotation=rot)
    return _held(m, "pickaxe")


def galaxy_shovel():
    """은하수 삽: a space-crystal spade with a constellation, a silver rim proud of its face and a silver point, a silver
    socket with a gold star at the neck."""
    m = MModel("galaxy_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17, SILVER, 0.75)
    stud(m, 8, 16.25, 6, 10, 1.0)
    m.box("blade", (3.5, 17, 7), (12.5, 25.5, 9), SPACE_SPADE)
    m.box("blade", (4.25, 25.5, 7.2), (11.75, 27, 8.8), SPACE_PLAIN)
    for side in (1, -1):
        def bx(x0, x1, y0, y1, z0, z1, mat):
            a, b = (x0, x1) if side > 0 else (16 - x1, 16 - x0)
            m.box("rim", (a, y0, z0), (b, y1, z1), mat)
        bx(12.5, 13.25, 17.5, 25, 7.25, 8.75, SILVER_LIGHT)
        bx(11.75, 12.5, 25.5, 26.25, 7.25, 8.75, SILVER_LIGHT)
    m.box("point", (5.75, 27, 7.3), (10.25, 28.25, 8.7), EDGE)
    m.box("point", (6.75, 28.25, 7.45), (9.25, 29.25, 8.55), EDGE)
    m.box("point", (7.5, 29.25, 7.6), (8.5, 30, 8.4), STAR_WHITE)
    m.box("spine", (7.25, 17, 6.6), (8.75, 25, 7), SILVER)
    m.box("spine", (7.25, 17, 9), (8.75, 25, 9.4), SILVER)
    return _held(m, "shovel")


def galaxy_axe():
    """은하수 도끼: a crescent-moon head of space crystal (its round back is the edge, in silver-lilac; its horns reach
    back towards the handle), joined by a silver bar; a silver poll with a gold star behind."""
    m = MModel("galaxy_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 21.5, 22.5, SILVER, 0.75)
    m.box("socket", (6.5, 15.25, 6.5), (9.5, 20.25, 9.5), SILVER)
    m.box("bar", (3.5, 16.25, 7.25), (6.5, 19.25, 8.75), SILVER_DARK)
    m.box("poll", (9.5, 16, 6.75), (11.25, 19.5, 9.25), SILVER)
    stud(m, 10.4, 17.75, 6.5, 9.5, 0.9)
    m.box("edge", (-0.75, 14, 7.5), (0.75, 21.5, 8.5), EDGE)
    m.box("moon", (0.75, 12.5, 7), (2.5, 23, 9), SPACE_PLAIN)
    m.box("moon", (2.5, 11.25, 7), (4, 15, 9), SPACE)
    m.box("moon", (2.5, 20.5, 7), (4, 24.25, 9), SPACE)
    m.box("moon", (2.5, 15, 7), (3.5, 20.5, 9), SPACE)
    m.box("moon", (4, 10.5, 7.25), (5.5, 12.75, 8.75), SPACE)
    m.box("moon", (4, 22.75, 7.25), (5.5, 25, 8.75), SPACE)
    m.box("horn", (5.5, 10.25, 7.5), (6.5, 11.5, 8.5), EDGE)
    m.box("horn", (5.5, 24, 7.5), (6.5, 25.25, 8.5), EDGE)
    m.box("edge", (0, 12.75, 7.5), (0.75, 14, 8.5), EDGE)
    m.box("edge", (0, 21.5, 7.5), (0.75, 22.75, 8.5), EDGE)
    m.box("edge", (0.75, 11.75, 7.5), (2.5, 12.5, 8.5), EDGE)
    m.box("edge", (0.75, 23, 7.5), (2.5, 23.75, 8.5), EDGE)
    stud(m, 1.6, 17.75, 6.75, 9.25, 0.9, STAR_WHITE)
    return _held(m, "axe")


def galaxy_hoe():
    """은하수 괭이: a ringed planet at the top of the shaft, a silver bar out to -x and a space-crystal plate hanging from
    its end (across the handle, like a real hoe's), lilac along its edge, a constellation on its outer face."""
    m = MModel("galaxy_hoe")
    handle(m)
    shaft(m, 18.5)
    m.group("head")
    m.box("bar", (2.25, 19.5, 7), (6, 21.5, 9), SILVER)
    planet(m, 8.25, 20.75, 8, 5, 2.25)
    m.box("plate", (0.25, 14.75, 5), (2.5, 22.75, 11), SPACE_PLATE)
    m.box("plate_edge", (0.5, 13.75, 5.25), (2.25, 14.75, 10.75), EDGE)
    m.box("plate_cap", (0, 22.75, 5.5), (2.75, 23.5, 10.5), SILVER)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    # a small silver reel beside the rod's foot (+x, where the line hangs), a gold star for its knob
    m.box("reel_foot", (9, 7.75, 7.5), (10, 9.25, 8.5), SILVER_DARK)
    round_z(m, "reel", 10, 14, 6.5, 10.5, 6.75, 9.25, SILVER, 1)
    m.box("reel_hub", (11.25, 7.75, 6.25), (12.75, 9.25, 9.75), VELVET)
    m.box("crank", (11.5, 8, 9.75), (12.5, 11.25, 10.25), SILVER_LIGHT)
    stud(m, 12, 11.5, 10, 10.75, 1.0)
    m.group("rod")
    round_y(m, "rod", 6.875, 9.125, 6.875, 9.125, 6.5, 14, SILVER, 0.5)
    m.box("rod", (7.125, 14, 7.125), (8.875, 23, 8.875), SILVER_LIGHT)
    m.box("rod", (7.375, 23, 7.375), (8.625, 28.75, 8.625), SILVER_LIGHT)
    # star guides: a gold four-point star round the rod at each joint
    for y, s in ((14, 1.9), (23, 1.6)):
        m.box("star_ring", (C - s, y - 0.35, 7.5), (C + s, y + 0.35, 8.5), GOLD)
        m.box("star_ring", (7.5, y - 0.35, C - s), (8.5, y + 0.35, C + s), GOLD)
        m.box("star_ring", (C - 0.6, y - 0.6, C - 0.6), (C + 0.6, y + 0.6, C + 0.6), STAR)
    for y in (18.5, 26):
        m.box("guide", (8.875, y - 0.75, 7.75), (10, y + 0.75, 8.25), GOLD)
        m.box("guide", (9.25, y - 0.25, 7.5), (10.5, y + 0.25, 8.5), STAR)
    # the crescent moon at the tip, horns up
    m.group("tip")
    m.box("moon", (6.75, 28.5, 7.25), (9.25, 29.75, 8.75), MOON)
    m.box("moon", (6, 29.25, 7.375), (7, 31, 8.625), MOON)
    m.box("moon", (9, 29.25, 7.375), (10, 31, 8.625), MOON)
    m.box("moon", (5.75, 31, 7.5), (6.75, 32, 8.5), MOON_DARK)
    m.box("moon", (9.25, 31, 7.5), (10.25, 32, 8.5), MOON_DARK)
    m.box("tip_star", (7.5, 29.75, 7.5), (8.5, 30.75, 8.5), STAR)
    if not cast:
        m.group("line")
        top = (8.5, 29.5, C)
        rot = ("z", 45, top)
        m.box("line", (8.25, 18.5, 7.75), (8.75, 29.5, 8.25), LINE, rotation=rot)
        # the float: a tiny ringed planet
        m.box("float", (6.75, 15, 6.75), (10.25, 18.25, 9.25), PLANET, rotation=rot)
        m.box("float", (7.25, 14.5, 7.25), (9.75, 18.75, 8.75), PLANET, rotation=rot)
        m.box("float_ring", (5.25, 16.25, 6.25), (11.75, 17, 9.75), RING, rotation=rot)
        m.box("float_top", (8, 18.75, 7.75), (9, 19.5, 8.25), STAR, rotation=rot)
    return _held(m, "rod")


def galaxy_fishing_rod():
    """은하수 낚싯대: a slim silver rod with gold star guides, a small silver reel and a crescent moon at the tip; on its
    line a tiny ringed planet for a float."""
    return _rod("galaxy_fishing_rod", False)


def galaxy_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("galaxy_fishing_rod_cast", True)
    m.display = galaxy_fishing_rod().display
    return m


BOBBER_WATERLINE = 4.5        # the planet's gold ring lies on the water's surface (model y)


def galaxy_bobber():
    """은하수 찌: a tiny ringed planet riding the hook: a round cyan-violet planet with a violet band, its flat gold ring on
    the water's surface, a gold star on top. The same from every side. Stands on y = 0 about x = z = 8."""
    m = MModel("galaxy_bobber")
    m.group("float")
    m.box("weight", (7, 0, 7), (9, 1, 9), SILVER_DARK)
    round_y(m, "planet", 5, 11, 5, 11, 1, 2, PLANET, 1.5)
    round_y(m, "planet", 4.5, 11.5, 4.5, 11.5, 2, 4, PLANET, 1.75)
    round_y(m, "planet_band", 4.4, 11.6, 4.4, 11.6, 4, 5, PLANET_BAND, 1.75)
    round_y(m, "planet", 4.5, 11.5, 4.5, 11.5, 5, 7, PLANET, 1.75)
    round_y(m, "planet", 5, 11, 5, 11, 7, 8, PLANET, 1.5)
    round_y(m, "planet", 6, 10, 6, 10, 8, 8.5, PLANET, 1)
    m.group("ring")
    w = 2.0
    m.box("ring", (4.5 - w, 4.25, 4.5 - w), (4.5, 4.75, 11.5 + w), RING)
    m.box("ring", (11.5, 4.25, 4.5 - w), (11.5 + w, 4.75, 11.5 + w), RING)
    m.box("ring", (4.5, 4.25, 4.5 - w), (11.5, 4.75, 4.5), RING)
    m.box("ring", (4.5, 4.25, 11.5), (11.5, 4.75, 11.5 + w), RING)
    m.group("star")
    m.box("stem", (7.5, 8.5, 7.5), (8.5, 9.25, 8.5), SILVER)
    m.box("star", (7.5, 9.25, 7.5), (8.5, 11.75, 8.5), STAR)
    m.box("star", (6.75, 10, 7.5), (9.25, 11, 8.5), STAR)
    m.box("star", (7.5, 10, 6.75), (8.5, 11, 9.25), STAR)
    m.display = presets.furniture(gui_scale=1.3, gui_translation=(0, 3.25, 0))
    m.kind = "bobber"
    return m


ALL = {f.__name__: f for f in (galaxy_sword, galaxy_pickaxe, galaxy_shovel, galaxy_axe, galaxy_hoe, galaxy_fishing_rod,
                               galaxy_fishing_rod_cast, galaxy_bobber)}

ROWS = [
    ("galaxy_sword", "은하수 검"), ("galaxy_pickaxe", "은하수 곡괭이"), ("galaxy_shovel", "은하수 삽"), ("galaxy_axe", "은하수 도끼"),
    ("galaxy_hoe", "은하수 괭이"), ("galaxy_fishing_rod", "은하수 낚싯대"), ("galaxy_fishing_rod_cast", "낚싯대 (던진 뒤)"),
    ("galaxy_bobber", "은하수 찌 (찌에 태우는 모델)"),
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
