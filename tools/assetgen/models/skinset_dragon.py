"""
청룡 시리즈 (dragon): a premium tool skin set, the held-tool side of the server's 청룡 cosmetic set (models/cosmetics_azure.py).

    dragon_sword  dragon_pickaxe  dragon_shovel  dragon_axe  dragon_hoe  dragon_fishing_rod (+ dragon_fishing_rod_cast)
    dragon_bobber

Same frame, grip point, sizes and display transforms as the toy series (models/toys.py: upright along +y on the axis
x = z = 8, the hand on GRIP_Y, _held() for the views); only the looks are this set's own.

Looks: polished jade-teal blades and heads under dark azure scales, burnished gold guards and collars engraved with
cloud scrolls, a dark-blue silk wrap on the grip, and a glowing white-blue 여의주 (dragon pearl) set in every piece: a
pearl held in gold claws for the pommel, and another in each head. Each tool carries a dragon part: the sword's guard is
a pair of whiskers curling up into jade horns, the pickaxe's arms end in two ivory claws, the axe's blade is a ribbed
fin, the hoe's head is a dragon's jaw with fangs, the rod ends in a small dragon's head. The float is a pearl in a gold
cloud cup.

The materials are painted here per face (DMat, a Mat whose pixels come from this file's own colour ramps), so the jade,
the scales and the silk can be colours no shared ramp has; one texel a model unit, like the toys.
"""

import copy
import math
import zlib

from PIL import Image

import economy_models  # noqa: F401
from economy_machines import MModel, Mat, cyl
from stsv_assets import presets
from stsv_assets import textures as T
from models.toys import _held, _arm, round_y, round_z, C, GRIP_Y  # noqa: F401

# ================================================================================================
# Colours (dark -> light)
# ================================================================================================

JADE = [(10, 58, 72), (18, 92, 104), (28, 128, 132), (52, 166, 160), (112, 210, 192), (196, 246, 230)]
AZURE = [(10, 26, 62), (16, 42, 92), (26, 66, 128), (44, 98, 166), (82, 140, 204)]
GOLDS = [(110, 68, 12), (160, 108, 18), (208, 154, 34), (240, 196, 72), (255, 234, 158)]
SILK = [(10, 14, 40), (18, 26, 66), (28, 42, 98), (46, 68, 140), (78, 104, 178)]
PEARLS = [(132, 196, 238), (176, 222, 252), (222, 244, 255), (255, 255, 255)]
IVORY = [(150, 136, 108), (196, 184, 150), (232, 224, 196), (252, 248, 232)]
EYES = [(40, 190, 210), (120, 240, 250), (230, 255, 255)]


class DMat(Mat):
    """A material painted by a pattern of this file: pattern(x, y, w, h) -> (ramp, index) per pixel, then a bevel
    (light on the face's top and left, dark on its bottom and right) unless bevel is None. Faces wider than 16 tile."""

    def __init__(self, pattern, bevel=(1, -1), glow=False, key=""):
        super().__init__("gold", "plain", 2, bevel=bevel, noise=0, glow=glow)
        self.pattern, self.key = pattern, key

    def __call__(self, **changes):
        m = copy.copy(self)
        for k, v in changes.items():
            setattr(m, k, v)
        return m

    def face(self, w, h, face, seed):
        pw, ph = max(1, math.ceil(w - 1e-6)), max(1, math.ceil(h - 1e-6))
        tile = pw > 16 or ph > 16
        if tile:
            pw = ph = 16
        im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        px = im.load()
        for y in range(16):
            for x in range(16):
                r, i = self.pattern(x % pw, y % ph, pw, ph, face)
                if self.bevel and not tile and x < pw and y < ph:
                    lt, dk = self.bevel
                    d = 0
                    if ph > 1 and y == 0:
                        d += lt
                    if pw > 1 and x == 0:
                        d += lt
                    if ph > 1 and y == ph - 1:
                        d += dk
                    if pw > 1 and x == pw - 1:
                        d += dk
                    i += max(-1, min(1, d))
                px[x, y] = tuple(r[max(0, min(len(r) - 1, i))]) + (255,)
        key = "dragon_" + format(zlib.crc32(im.tobytes()), "08x")
        if key not in T.SURFACES:
            T.SURFACES[key] = (lambda img=im: img.copy())
        return key, (None if tile else [0, 0, round(w, 4), round(h, 4)])


def _gloss(x, y, w, h):
    return x + y < min(w, h) * 0.45


def _plain(r, b):
    return lambda x, y, w, h, f: (r, b + 1 if _gloss(x, y, w, h) else b)


def _scales(r, b, line, line_i):
    """Fish scales: rows three texels high, every other row offset by two; a dark rim round each scale's foot, a glint
    on its top. The face's top left corner keeps the polish's glint."""
    def p(x, y, w, h, f):
        row = y // 3
        cx, cy = (x + (2 if row % 2 else 0)) % 4, y % 3
        if (cy == 2 and cx in (1, 2)) or (cy == 1 and cx == 0):
            return line, line_i
        if cy == 0 and cx in (1, 2):
            return r, b + 1
        return r, b + (1 if _gloss(x, y, w, h) else 0)
    return p


def _clouds(x, y, w, h, f):
    """Burnished gold with a cloud scroll engraved every six texels (a dark curl, a bright lip under it)."""
    if w >= 4 and h >= 3:
        tx, ty = x % 6, y % 4
        curl = {(1, 0), (2, 0), (0, 1), (3, 1), (0, 2), (2, 2), (1, 3), (4, 3), (5, 2)}
        if (tx, ty) in curl:
            return GOLDS, 1
        if (tx, ty) in ((1, 1), (2, 1)):
            return GOLDS, 4
    return GOLDS, 3 if _gloss(x, y, w, h) else 2


def _silk(x, y, w, h, f):
    """A silk cord wound diagonally both ways: diamonds of the dark wrap, a light thread along each crossing."""
    if (x + y) % 4 == 0 or (x - y) % 4 == 0:
        return SILK, 3
    return SILK, 1 if (x + 2 * y) % 8 < 4 else 2


def _pearl(x, y, w, h, f):
    """The 여의주: a white hot centre fading to sky blue at the rim, a glint up and left."""
    dx, dy = (x + 0.5) / w - 0.5, (y + 0.5) / h - 0.5
    r = math.hypot(dx, dy)
    if dx < -0.1 and dy < -0.1 and r < 0.38:
        return PEARLS, 3
    return PEARLS, 3 if r < 0.18 else 2 if r < 0.34 else 1 if r < 0.5 else 0


JADE_SCALE = DMat(_scales(JADE, 2, AZURE, 3))
JADE_DEEP = DMat(_scales(JADE, 1, AZURE, 2))
JADE_POLISH = DMat(_plain(JADE, 3))
JADE_EDGE = DMat(_plain(JADE, 4), bevel=(1, -1))
JADE_DARK = DMat(_plain(JADE, 1))
AZURE_SCALE = DMat(_scales(AZURE, 2, AZURE, 1))
AZURE_PLAIN = DMat(_plain(AZURE, 1))
GOLD = DMat(_plain(GOLDS, 2))
GOLD_LIGHT = DMat(_plain(GOLDS, 3))
GOLD_CLOUD = DMat(_clouds)
SILK_WRAP = DMat(_silk, bevel=(0, -1))
PEARL = DMat(_pearl, bevel=None, glow=True)
IVORY_M = DMat(_plain(IVORY, 2))
EYE = DMat(lambda x, y, w, h, f: (EYES, 1), bevel=None, glow=True)
LINE = Mat("feather", "plain", 2, bevel=None, noise=0)


EDGES = [(120, 214, 200), (176, 244, 230), (226, 255, 248), (255, 255, 255)]


def _big_scales(r, b, line, line_i):
    """Big carved scales for broad faces: rows four texels high, scales four wide, every other row offset by two; a
    dark arc round each scale's foot, a bright lip on its crown."""
    def p(x, y, w, h, f):
        row = y // 4
        cx, cy = (x + (2 if row % 2 else 0)) % 4, y % 4
        if (cy == 3 and cx in (1, 2)) or (cy == 2 and cx in (0, 3)):
            return line, line_i
        if cy == 0 and cx in (1, 2):
            return r, b + 2
        if cy == 0 or (cy == 1 and cx in (1, 2)):
            return r, b + 1
        return r, b
    return p


def _honed(x, y, w, h, f):
    """A glowing honed edge: white hot down its middle, jade towards both sides."""
    return EDGES, 2 if (y + x) % 5 else 3


def _blade(x, y, w, h, f):
    """The glaive's polished jade: long diagonal sheen streaks and a darker band near the back."""
    if (x + y) % 9 == 0:
        return JADE, 4
    return JADE, 3 if (x + y) % 9 in (1, 8) else 2


JADE_BIG = DMat(_big_scales(JADE, 2, AZURE, 2))
EDGE_GLOW = DMat(_honed, bevel=None, glow=True)
BLADE = DMat(_blade, bevel=None)
IVORY_TIP = DMat(_plain(IVORY, 3))
GOLD_PLAIN = GOLD(bevel=None)
GOLD_BRIGHT = GOLD_LIGHT(bevel=None)


# ================================================================================================
# Shared parts
# ================================================================================================

def cull(m):
    """Drop every face of an unrotated box that other unrotated boxes cover completely from outside (the same test as
    Model.validate's: a removed face must be hidden), so the detail goes into what shows and not into the face count."""
    plain = [e for e in m.elements if not e["rotation"]]
    for e in plain:
        for f in list(e["faces"]):
            ax, sign = m._AXIS[f]
            p = e["to"][ax] if sign > 0 else e["from"][ax]
            a1, a2 = [a for a in range(3) if a != ax]
            ok = True
            for step in (0.25, 0.1):        # validate's own grid, and a finer one
                u = e["from"][a1] + step / 2
                while ok and u < e["to"][a1]:
                    v = e["from"][a2] + step / 2
                    while v < e["to"][a2]:
                        if not any(m._occupies_beyond(o, ax, sign, p, a1, u, a2, v) for o in plain if o is not e):
                            ok = False
                            break
                        v += step
                    u += step
            if ok:
                del e["faces"][f]
                e["uv"].pop(f, None)
    return m


def pearl(m, cx, cy, _unused, half, cz=(7, 9), d=0.5):
    """A pearl set into a part: a square of side 2*half about (cx, cy), standing d proud of both faces along z
    (from cz - depth to cz + depth given as cz=(z0, z1)), a rounder cross of it in front."""
    z0, z1 = cz
    m.box("pearl", (cx - half, cy - half, z0 - d), (cx + half, cy + half, z1 + d), PEARL)
    m.box("pearl", (cx - half + 0.5, cy - half - 0.5, z0 - d + 0.25), (cx + half - 0.5, cy + half + 0.5, z1 + d - 0.25), PEARL)


def handle(m):
    """Every piece's handle: a pearl held in four gold claws, a dark-blue silk grip, a gold collar with cloud scrolls."""
    m.group("handle")
    round_y(m, "pommel_pearl", 6.5, 9.5, 6.5, 9.5, -4.75, -2, PEARL, 0.75)
    m.box("pommel_pearl", (7.25, -5.25, 7.25), (8.75, -4.75, 8.75), PEARL)
    round_y(m, "pommel_cup", 6.25, 9.75, 6.25, 9.75, -2.25, -1.25, GOLD, 0.75)
    for x0, z0 in ((6.25, 6.25), (9.25, 6.25), (6.25, 9.25), (9.25, 9.25)):
        m.box("pommel_claw", (x0 - 0.25, -3.75, z0 - 0.25), (x0 + 0.75, -2.25, z0 + 0.75), GOLD_LIGHT)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.25, 5.25, SILK_WRAP, 0.5)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, GOLD_CLOUD, 1)


def shaft(m, top, band=True):
    """A lacquered azure shaft with small scales, a gold band about half way up."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, AZURE_SCALE, 0.5)
    if band:
        mid = 6.5 + (top - 6.5) * 0.5
        round_y(m, "shaft_band", 6.625, 9.375, 6.625, 9.375, mid, mid + 0.75, GOLD_LIGHT, 0.5)


class KModel(MModel):
    """A model built in toy units and shrunk by k about the grip point (8, GRIP_Y, 8) as its boxes are added, so a piece
    longer than the -16 .. 32 box limit allows can still be built; _held_k() scales the held views back up by 1 / k
    about the same point, so in the hand it is exactly as big as it was drawn."""

    def __init__(self, name, k):
        super().__init__(name)
        self.k = k

    def _p(self, p):
        pv = (C, GRIP_Y, C)
        return tuple(round(pv[i] + self.k * (p[i] - pv[i]), 4) for i in range(3))

    def box(self, name, frm, to, tex, rotation=None, **kw):
        if rotation:
            axis, angle, o = rotation
            rotation = (axis, angle, self._p(o))
        return super().box(name, self._p(frm), self._p(to), tex, rotation=rotation, **kw)


def _held_k(m, kind):
    m = _held(m, kind)
    f = 1 / m.k
    m.display.update(presets.enlarged(m.display, {c: f for c in ("thirdperson_righthand", "thirdperson_lefthand",
                                                                  "firstperson_righthand", "firstperson_lefthand")},
                                      (0.0, GRIP_Y - 8.0, 0.0)))
    return m


def _seg(m, name, pivot, length, half, z0, z1, angle, mat):
    """A box from pivot along +x (length), turned by angle about the pivot (positive: counter-clockwise).
    Returns its far end."""
    px, py = pivot
    m.box(name, (px, py - half, z0), (px + length, py + half, z1), mat, rotation=("z", angle, (px, py, C)) if angle else None)
    a = math.radians(angle)
    return px + length * math.cos(a), py + length * math.sin(a)


def _seg_l(m, name, pivot, length, half, z0, z1, angle, mat):
    """The same towards -x (angle positive: clockwise, so the far end rises)."""
    px, py = pivot
    m.box(name, (px - length, py - half, z0), (px, py + half, z1), mat, rotation=("z", -angle, (px, py, C)) if angle else None)
    a = math.radians(angle)
    return px - length * math.cos(a), py + length * math.sin(a)


def _mirror_x(m):
    """Mirror every box of a model across the handle's axis (x about C): the faces east and west swap, rotations about
    y and z turn the other way."""
    for e in m.elements:
        f, t = e["from"], e["to"]
        e["from"], e["to"] = [2 * C - t[0], f[1], f[2]], [2 * C - f[0], t[1], t[2]]
        for d in ("faces", "uv"):
            fs = e.get(d) or {}
            if "east" in fs or "west" in fs:
                east, west = fs.pop("east", None), fs.pop("west", None)
                if west is not None:
                    fs["east"] = west
                if east is not None:
                    fs["west"] = east
        if e["rotation"]:
            axis, angle, o = e["rotation"]
            e["rotation"] = (axis, angle if axis == "x" else -angle, (2 * C - o[0], o[1], o[2]))
    return m


# ================================================================================================
# The tools
# ================================================================================================

# the glaive's blade, bottom to tip: (y0, y1, back x, edge x). The edge (+x while built) swells out into a crescent and
# the tip sweeps back (-x), like a 언월도's
GLAIVE = ((11, 14, 6.5, 10.5), (14, 17, 6.5, 11.5), (17, 20, 6.5, 12.25), (20, 23, 6.5, 12.75), (23, 26, 6.25, 13),
          (26, 29, 5.75, 12.85), (29, 32, 5.25, 12.25), (32, 34.5, 4.5, 11.25), (34.5, 36.5, 3.75, 10),
          (36.5, 38.5, 3, 8.75), (38.5, 40.25, 2, 7), (40.25, 41.5, 1, 4.75), (41.5, 42.25, 0.25, 2.5))
K_SWORD = 0.74


def dragon_sword():
    """청룡언월도: a long crescent glaive blade, polished jade with a glowing honed edge that swells out and a tip that
    sweeps back, a gold back with a spur and an azure fuller with a pearl; it comes out of the open mouth of a big dragon
    head (the guard): scaled jade skull, gold brow and lips, glowing eyes, ivory fangs, gold horns swept back, a jade
    beard under the snout. Built in toy units and shrunk to fit the box limit (KModel)."""
    m = KModel("dragon_sword", K_SWORD)
    handle(m)
    m.group("blade")
    n = len(GLAIVE)
    for i, (y0, y1, bx, ex) in enumerate(GLAIVE):
        tip = i >= n - 2
        if tip:
            m.box("blade_tip", (bx, y0, 7.35), (ex, y1, 8.65), EDGE_GLOW)
            continue
        m.box("blade", (bx + 0.75, y0, 7), (ex - 1, y1, 9), BLADE)
        m.box("blade_edge", (ex - 1, y0, 7.35), (ex, y1, 8.65), EDGE_GLOW)
        m.box("blade_back", (bx, y0, 7.2), (bx + 0.75, y1, 8.8), GOLD_BRIGHT if i % 2 else GOLD_PLAIN)
    # the spur on the back (a 언월도's notch), an azure fuller along the blade, a pearl set in it near the mouth
    m.box("spur", (4.5, 21, 7.4), (6.5, 22.5, 8.6), GOLD_LIGHT)
    m.box("spur", (3.5, 22.5, 7.55), (5.25, 24, 8.45), GOLD_LIGHT)
    m.box("fuller", (7.5, 16.5, 6.85), (8.5, 31, 9.15), AZURE_PLAIN)
    m.box("fuller_cap", (7.25, 31, 6.9), (8.75, 31.75, 9.1), GOLD_LIGHT)
    pearl(m, 8, 14.5, 8, 1.0, cz=(7, 9), d=0.4)
    m.group("dragon_head")
    m.box("skull", (4, 6.5, 5.75), (11.5, 11, 10.25), JADE_SCALE)
    m.box("brow", (3.75, 9.75, 5.6), (7.5, 11.25, 10.4), GOLD_CLOUD)
    m.box("eye", (5, 8.25, 5.5), (6.75, 9.75, 10.5), EYE)
    m.box("snout", (11.5, 7, 6.25), (14, 10, 9.75), JADE_SCALE)
    m.box("nose", (14, 8.5, 6.75), (14.75, 10, 9.25), GOLD_LIGHT)
    m.box("lip", (4.5, 11, 5.9), (12, 12.5, 6.75), GOLD_LIGHT)
    m.box("lip", (4.5, 11, 9.25), (12, 12.5, 10.1), GOLD_LIGHT)
    for x in (6, 10):
        m.box("fang", (x, 12.5, 6), (x + 0.75, 13.75, 6.75), IVORY_TIP)
        m.box("fang", (x, 12.5, 9.25), (x + 0.75, 13.75, 10), IVORY_TIP)
    m.box("jaw", (11.5, 5.75, 6.75), (13.5, 7, 9.25), JADE_DARK)
    m.box("beard", (12, 3.75, 7.25), (13.25, 5.75, 8.75), JADE_EDGE)
    m.box("beard", (12.25, 2.5, 7.5), (13, 3.75, 8.5), JADE_EDGE)
    p = _seg_l(m, "horn", (4.5, 10.25), 4.5, 0.75, 6.25, 7.5, 22.5, GOLD)
    _seg_l(m, "horn_tip", (p[0] + 0.3, p[1]), 2.5, 0.5, 6.4, 7.35, 45, JADE_EDGE)
    p = _seg_l(m, "horn", (4.5, 10.25), 4.5, 0.75, 8.5, 9.75, 22.5, GOLD)
    _seg_l(m, "horn_tip", (p[0] + 0.3, p[1]), 2.5, 0.5, 8.65, 9.6, 45, JADE_EDGE)
    m.box("mane", (3, 6.75, 7.1), (4, 9.75, 8.9), JADE_EDGE)
    m.box("mane", (2.25, 7.25, 7.35), (3, 9, 8.65), JADE_EDGE)
    cull(m)
    # built with the edge towards +x; mirrored so the swelling edge faces the way a vanilla sword strikes (forward and
    # down in the hand, away from the player) and the gold back with its spur faces up
    _mirror_x(m)
    return _held_k(m, "sword")


def dragon_pickaxe():
    """용 발톱: a dragon's foot on the shaft: a gold cuff, a broad scaled jade palm with a pearl set in it, gold rings at
    the knuckles and three hooked ivory talons, two sweeping out and down to either side (the pick's arms) and a short
    one curling over the top."""
    m = MModel("dragon_pickaxe")
    handle(m)
    shaft(m, 15.5)
    m.group("claw")
    round_y(m, "cuff", 6.25, 9.75, 6.25, 9.75, 15.5, 16.75, GOLD_CLOUD, 0.75)
    m.box("palm", (5, 16.75, 6.5), (11, 21, 9.5), JADE_SCALE)
    m.box("palm_ridge", (5.5, 21, 7), (10.5, 21.5, 9), JADE_DARK)
    pearl(m, 8, 18.875, 8, 1.0, cz=(6.5, 9.5), d=0.35)
    for side in (1, -1):
        seg = _seg if side > 0 else _seg_l
        kx = C + side * 3.0
        m.box("knuckle", (min(kx, kx + side * 0.9), 17, 6.75), (max(kx, kx + side * 0.9), 20.75, 9.25), GOLD_LIGHT)
        m.box("knuckle", (min(kx + side * 0.9, kx + side * 1.6), 17.5, 7.0), (max(kx + side * 0.9, kx + side * 1.6), 20.25, 9.0), JADE_SCALE)
        p = (kx + side * 1.4, 18.9)
        p = seg(m, "talon", p, 3.5, 1.3, 7.05, 8.95, -22.5, IVORY_M)
        p = seg(m, "talon", (p[0] - side * 0.35, p[1] + 0.15), 3.0, 1.0, 7.2, 8.8, -45, IVORY_M)
        seg(m, "talon_point", (p[0] - side * 0.3, p[1] + 0.25), 2.25, 0.55, 7.4, 8.6, -45, IVORY_TIP)
    m.box("finger_up", (7, 21.5, 7), (9, 22.75, 9), JADE_SCALE)
    m.box("knuckle_up", (6.75, 22.75, 6.75), (9.25, 23.5, 9.25), GOLD_LIGHT)
    m.box("talon_up", (7.15, 23.5, 7.15), (8.85, 25, 8.85), IVORY_M)
    p = _seg(m, "talon_up", (7.6, 24.6), 2.5, 0.75, 7.3, 8.7, 22.5, IVORY_M)
    _seg(m, "talon_point", (p[0] - 0.3, p[1] - 0.1), 1.5, 0.45, 7.45, 8.55, -22.5, IVORY_TIP)
    cull(m)
    return _held(m, "pickaxe")


def dragon_shovel():
    """용 비늘 방패 삽: a broad spade blade shaped like one great dragon scale made a shield: a gold rim, a face of big
    carved jade scales, a gold spine up its middle with a pearl at its foot, a glowing honed point; a gold socket with two
    small gold claws gripping the blade's shoulders."""
    m = MModel("dragon_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17.25, GOLD_CLOUD, 0.75)
    rim = ((17, 17.75, 3.75), (17.75, 18.75, 5), (18.75, 25, 5.5), (25, 26.5, 5), (26.5, 27.75, 4.25), (27.75, 28.75, 3.25))
    for y0, y1, hw in rim:
        m.box("rim", (C - hw, y0, 7.3), (C + hw, y1, 8.7), GOLD)
    for y0, y1, hw in ((28.75, 29.75, 2.25), (29.75, 30.5, 1.25)):
        m.box("point", (C - hw, y0, 7.3), (C + hw, y1, 8.7), EDGE_GLOW)
    face = ((17.75, 18.75, 3.75), (18.75, 25.25, 4.5), (25.25, 26.75, 3.75), (26.75, 27.75, 3.0), (27.75, 28.5, 2.0))
    for y0, y1, hw in face:
        m.box("face", (C - hw, y0, 6.95), (C + hw, y1, 9.05), JADE_BIG)
    m.box("spine", (7.4, 20.25, 6.7), (8.6, 27.5, 9.3), GOLD_LIGHT)
    m.box("spine_tip", (7.6, 27.5, 6.8), (8.4, 28.75, 9.2), GOLD_LIGHT)
    pearl(m, 8, 19.0, 8, 1.0, cz=(6.95, 9.05), d=0.4)
    for side in (1, -1):
        x0 = C + side * 2.25
        m.box("shoulder_claw", (min(x0, x0 + side * 1.0), 16.25, 6.85), (max(x0, x0 + side * 1.0), 18.25, 9.15), GOLD_LIGHT)
    cull(m)
    return _held(m, "shovel")


def dragon_axe():
    """용머리 도끼: a big dragon's head on the shaft, its open mouth towards -x, and a broad crescent blade of carved jade
    coming out of the mouth between gold lips and ivory fangs, its honed edge glowing; glowing eye, gold brow, a pearl
    on the cheek, gold horns and a jade mane swept back over the poll."""
    m = MModel("dragon_axe")
    handle(m)
    shaft(m, 16.5)
    m.group("head")
    m.box("neck", (6.5, 16.5, 6.5), (10.5, 17.5, 9.5), GOLD_CLOUD)
    m.box("skull", (6, 17.5, 6.25), (11, 23, 9.75), JADE_SCALE)
    m.box("brow", (5.5, 21.75, 6.1), (9, 23.25, 9.9), GOLD_CLOUD)
    m.box("eye", (6.5, 20.25, 6.0), (8, 21.5, 10.0), EYE)
    m.box("upper_jaw", (2.75, 20.5, 6.6), (6, 22.5, 9.4), JADE_SCALE)
    m.box("nose", (2.0, 21.25, 6.9), (2.75, 22.5, 9.1), GOLD_LIGHT)
    m.box("lower_jaw", (3.5, 17, 6.6), (6, 18.5, 9.4), JADE_DARK)
    m.box("lip", (2.75, 20.0, 6.45), (6.25, 20.5, 9.55), GOLD_LIGHT)
    m.box("lip", (3.5, 18.5, 6.45), (6.25, 19.0, 9.55), GOLD_LIGHT)
    for x in (3.25, 5.0):
        m.box("fang", (x, 19.25, 6.5), (x + 0.6, 20.0, 7.1), IVORY_TIP)
        m.box("fang", (x, 19.25, 8.9), (x + 0.6, 20.0, 9.5), IVORY_TIP)
    m.box("beard", (4.0, 15.75, 7.4), (5.25, 17, 8.6), JADE_EDGE)
    pearl(m, 9.5, 19.25, 8, 0.75, cz=(6.25, 9.75), d=0.3)
    p = _seg(m, "horn", (9.75, 23), 3.75, 0.65, 7.25, 8.75, 22.5, GOLD)
    _seg(m, "horn_tip", (p[0] - 0.3, p[1]), 2.0, 0.45, 7.4, 8.6, 45, JADE_EDGE)
    for y0, y1, x1 in ((18, 22, 12), (18.75, 21.25, 13)):
        m.box("mane", (11 if x1 == 12 else 12, y0, 7.35 if x1 == 12 else 7.55), (x1, y1, 8.65 if x1 == 12 else 8.45), JADE_EDGE)
    m.group("blade")
    # columns from the mouth out to the edge: (x0, x1, y0, y1)
    for x0, x1, y0, y1, mat in ((0.75, 3.5, 17.25, 21.25, JADE_BIG), (-1, 0.75, 15.75, 22.75, JADE_BIG),
                                (-2.5, -1, 14.25, 24.25, JADE_BIG), (-3.5, -2.5, 13.0, 25.5, BLADE)):
        m.box("blade", (x0, y0, 7.3), (x1, y1, 8.7), mat)
    m.box("blade_edge", (-4.25, 13.5, 7.45), (-3.5, 25.0, 8.55), EDGE_GLOW)
    m.box("blade_horn", (-3.25, 11.75, 7.45), (-1.75, 13.0, 8.55), EDGE_GLOW)
    m.box("blade_horn", (-3.25, 25.5, 7.45), (-1.75, 26.75, 8.55), EDGE_GLOW)
    m.box("blade_rib", (-2.25, 18.75, 7.15), (2.75, 19.5, 8.85), GOLD_LIGHT)
    cull(m)
    return _held(m, "axe")


def dragon_hoe():
    """여의주 지팡이: a staff crowned with a big glowing 여의주 held in four gold claws; under it the straight ㄱ head
    stands out to -x: a scaled jade dragon body with gold dorsal fins and a gold belly, a gold band, then a wider jade
    blade block ending flat in a glowing honed edge. Nothing droops or hooks."""
    m = MModel("dragon_hoe")
    handle(m)
    shaft(m, 19.75)
    m.group("head")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 19.5, 22.75, GOLD_CLOUD, 0.75)
    m.box("bar", (0.5, 20.0, 6.75), (6.25, 22.5, 9.25), JADE_SCALE)
    m.box("belly", (0.75, 19.5, 7.25), (6.25, 20.0, 8.75), GOLD_LIGHT)
    for x in (1.5, 3.25, 5.0):
        m.box("fin", (x, 22.5, 7.5), (x + 1.0, 23.25, 8.5), GOLD_LIGHT)
        m.box("fin", (x + 0.25, 23.25, 7.7), (x + 0.75, 23.75, 8.3), GOLD_LIGHT)
    m.box("bar_end", (9.75, 20.25, 7.25), (10.25, 22.25, 8.75), GOLD_LIGHT)
    m.box("band", (0, 19.5, 6.5), (0.5, 23.0, 9.5), GOLD_LIGHT)
    m.box("blade", (-2, 19.75, 6.25), (0, 22.75, 9.75), JADE_POLISH)
    m.box("blade_edge", (-2.75, 20.0, 6.5), (-2, 22.5, 9.5), EDGE_GLOW)
    m.group("pearl")
    for x0, z0 in ((6.5, 6.5), (8.75, 6.5), (6.5, 8.75), (8.75, 8.75)):
        m.box("claw", (x0, 22.75, z0), (x0 + 0.75, 24.5, z0 + 0.75), GOLD_LIGHT)
    round_y(m, "orb", 6.0, 10.0, 6.0, 10.0, 23.5, 27.5, PEARL, 1)
    m.box("orb_top", (7.0, 27.5, 7.0), (9.0, 28.0, 9.0), PEARL)
    cull(m)
    return _held(m, "hoe")


TIP = (12.5, 27.0)      # where the line leaves the dragon's mouth (x, y)


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9, 7.5, 7.25), (10, 9.5, 8.75), GOLD)
    cyl(m, "reel", "z", (12.75, 8.5), 6, 6.25, 9.75, GOLD, cap=GOLD_CLOUD)
    m.box("reel_hub", (12, 7.75, 9.75), (13.5, 9.25, 10.5), PEARL)
    m.box("crank", (12.25, 9, 10.5), (13.25, 11.5, 11), GOLD_LIGHT)
    m.box("crank_knob", (12, 10.75, 11), (13.5, 12.25, 12.25), SILK_WRAP)
    m.group("rod")
    round_y(m, "rod", 6.625, 9.375, 6.625, 9.375, 6.5, 14, JADE_SCALE, 0.75)
    m.box("rod", (6.875, 14, 6.875), (9.125, 19.5, 9.125), JADE_SCALE)
    m.box("rod", (7.125, 19.5, 7.125), (8.875, 24.5, 8.875), JADE_POLISH)
    for y, w in ((13.5, 1.625), (19.25, 1.375), (24.0, 1.125)):
        m.box("rod_ring", (C - w, y, C - w), (C + w, y + 0.75, C + w), GOLD_LIGHT)
    m.box("guide", (9.125, 16.5, 7.4), (10.5, 17.5, 8.6), GOLD)
    m.box("guide", (8.875, 21.75, 7.4), (10.25, 22.75, 8.6), GOLD)
    m.group("dragon_head")
    m.box("neck", (6.75, 24.75, 6.75), (9.25, 25.75, 9.25), GOLD_CLOUD)
    m.box("head", (6.25, 25.75, 6.5), (10.25, 29.25, 9.5), JADE_SCALE)
    m.box("brow", (6.0, 28.5, 6.4), (9.0, 29.5, 9.6), GOLD_CLOUD)
    m.box("eye", (7.75, 27.25, 6.3), (8.75, 28.25, 9.7), EYE)
    m.box("snout", (10.25, 27.25, 6.9), (12.5, 29.0, 9.1), JADE_SCALE)
    m.box("nose", (12.5, 28.0, 7.2), (13.0, 29.0, 8.8), GOLD_LIGHT)
    m.box("jaw", (10.25, 25.75, 7.1), (12.0, 26.5, 8.9), JADE_DARK)
    m.box("mouth_pearl", (11.0, 26.5, 7.25), (12.25, 27.25, 8.75), PEARL)
    m.box("horn", (6.5, 29.5, 7.3), (7.5, 30.5, 8.7), GOLD_LIGHT)
    m.box("horn", (5.5, 30.0, 7.45), (6.5, 31.25, 8.55), GOLD_LIGHT)
    m.box("mane", (5.25, 26.0, 7.4), (6.25, 28.75, 8.6), JADE_EDGE)
    m.box("mane", (4.5, 26.5, 7.6), (5.25, 28.0, 8.4), JADE_EDGE)
    # the long whiskers: from the snout's sides, sweeping out forward and curling down, glowing at their ends
    for z0, z1, ang, ln in ((6.5, 7.0, 22.5, 3.5), (9.0, 9.5, 0, 3.0)):
        p = _seg(m, "whisker", (12.25, 28.25), ln, 0.3, z0, z1, ang, GOLD_LIGHT)
        p = _seg(m, "whisker", (p[0] - 0.2, p[1]), 2.75, 0.3, z0, z1, -22.5, GOLD_LIGHT)
        _seg(m, "whisker_tip", (p[0] - 0.2, p[1]), 2.0, 0.3, z0, z1, -45, EDGE_GLOW)
    if not cast:
        m.group("line")
        top = (TIP[0], TIP[1], C)
        rot = ("z", 45, top)
        x = TIP[0]
        m.box("line", (x - 0.375, 17.0, 7.625), (x + 0.375, TIP[1], 8.375), LINE, rotation=rot)
        m.box("float_eye", (x - 0.5, 16.25, 7.5), (x + 0.5, 17.0, 8.5), GOLD_LIGHT, rotation=rot)
        m.box("float_pearl", (x - 1.5, 13.5, 6.5), (x + 1.5, 16.25, 9.5), PEARL, rotation=rot)
        m.box("float_pearl", (x - 1, 13.0, 7), (x + 1, 16.75, 9), PEARL, rotation=rot)
        m.box("float_cup", (x - 2, 12.0, 6), (x + 2, 13.5, 10), GOLD_CLOUD, rotation=rot)
        m.box("float_cup", (x - 1, 11.25, 7), (x + 1, 12.0, 9), GOLD, rotation=rot)
    cull(m)
    return _held(m, "rod")


def dragon_fishing_rod():
    """용 수염 낚싯대: a scaled jade rod with gold rings and a gold reel, a dragon's head at the tip holding a pearl in
    its jaws, two long gold whiskers sweeping out from its snout and curling down to glowing ends; the line hangs from
    its mouth with a pearl float in a gold cloud cup."""
    return _rod("dragon_fishing_rod", False)


def dragon_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("dragon_fishing_rod_cast", True)
    m.display = dragon_fishing_rod().display
    return m


BOBBER_WATERLINE = 3.5        # the gold cup's rim sits just above the water, the pearl stands out of it


def dragon_bobber():
    """청룡 찌: a glowing pearl in a gold cloud cup (cloud scrolls round the cup, four cloud puffs on its rim), a gold
    weight under it. Stands on y = 0 about the axis x = 8, z = 8; the water's surface is at BOBBER_WATERLINE."""
    m = MModel("dragon_bobber")
    m.group("float")
    cyl(m, "weight", "y", (C, C), 2, 0, 1, GOLD, cap=GOLD)
    cyl(m, "cup", "y", (C, C), 4, 1, 2, GOLD, cap=GOLD)
    cyl(m, "cup", "y", (C, C), 7, 2, 4, GOLD_CLOUD, cap=GOLD)
    for frm, to in (((3.75, 3, 7), (5, 4.75, 9)), ((11, 3, 7), (12.25, 4.75, 9)), ((7, 3, 3.75), (9, 4.75, 5)), ((7, 3, 11), (9, 4.75, 12.25))):
        m.box("cloud_puff", frm, to, GOLD_LIGHT)
    cyl(m, "pearl", "y", (C, C), 4, 3.5, 4.5, PEARL, cap=PEARL)
    cyl(m, "pearl", "y", (C, C), 6, 4.5, 8.5, PEARL, cap=PEARL)
    cyl(m, "pearl", "y", (C, C), 4, 8.5, 9.5, PEARL, cap=PEARL)
    m.box("glint", (7.5, 9.5, 7.5), (8.5, 10, 8.5), PEARL)
    m.display = presets.furniture(gui_scale=1.4, gui_translation=(0, 3.0, 0))
    m.kind = "bobber"
    return m


ALL = {f.__name__: f for f in (dragon_sword, dragon_pickaxe, dragon_shovel, dragon_axe, dragon_hoe, dragon_fishing_rod,
                               dragon_fishing_rod_cast, dragon_bobber)}

ROWS = [
    ("dragon_sword", "청룡 검"), ("dragon_pickaxe", "청룡 곡괭이"), ("dragon_shovel", "청룡 삽"), ("dragon_axe", "청룡 도끼"),
    ("dragon_hoe", "청룡 괭이"), ("dragon_fishing_rod", "청룡 낚싯대"), ("dragon_fishing_rod_cast", "낚싯대 (던진 뒤)"),
    ("dragon_bobber", "청룡 찌 (찌에 태우는 모델)"),
]

TITLE = "청룡 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("dragon_sword", "dragon_pickaxe", "dragon_shovel", "dragon_axe", "dragon_hoe",
                                 "dragon_bobber")}
    out["dragon_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/dragon_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/dragon_fishing_rod_cast"},
    }}
    return out
