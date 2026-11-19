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
from models.toys import _held, _arm, round_y, C, GRIP_Y  # noqa: F401

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


# ================================================================================================
# Shared parts
# ================================================================================================

def pearl(m, cx, cy, _unused, half, cz=(7, 9), d=0.5):
    """A pearl set into a part: a square of side 2*half about (cx, cy), standing d proud of both faces along z
    (from cz - depth to cz + depth given as cz=(z0, z1))."""
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


def shaft(m, top):
    """A lacquered azure shaft with small scales, a gold band two thirds up."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, AZURE_SCALE, 0.5)
    mid = 6.5 + (top - 6.5) * 0.55
    round_y(m, "shaft_band", 6.625, 9.375, 6.625, 9.375, mid, mid + 0.75, GOLD_LIGHT, 0.5)


# ================================================================================================
# The tools
# ================================================================================================

def dragon_sword():
    """청룡 검: a jade blade under azure scales with pale jade edges and a dark ridge, a gold guard holding a pearl whose
    ends are dragon whiskers curling up into jade horns."""
    m = MModel("dragon_sword")
    handle(m)
    m.group("guard")
    m.box("guard", (5, 6.5, 6.5), (11, 8.75, 9.5), GOLD_CLOUD)
    pearl(m, 8, 7.625, 8, 1, cz=(6.5, 9.5))
    for side in (1, -1):
        # a whisker: out from the guard and up, then curling in, a jade horn on its end
        x_in = 11 if side > 0 else 5
        frm, to = ((x_in - 0.5, 7.0, 7.25), (x_in + 3.0, 8.25, 8.75)) if side > 0 else ((x_in - 3.0, 7.0, 7.25), (x_in + 0.5, 8.25, 8.75))
        m.box("whisker", frm, to, GOLD, rotation=("z", 22.5 * side, (x_in, 7.625, C)))
        xo = x_in + side * 2.9
        frm, to = ((xo - 0.5, 8.4, 7.375), (xo + 0.75, 11.25, 8.625)) if side > 0 else ((xo - 0.75, 8.4, 7.375), (xo + 0.5, 11.25, 8.625))
        m.box("whisker_up", frm, to, GOLD_LIGHT, rotation=("z", 22.5 * side, (xo, 8.9, C)))
        xt = xo + side * 0.6
        frm, to = ((xt - 1.75, 10.75, 7.5), (xt, 12.75, 8.5)) if side > 0 else ((xt, 10.75, 7.5), (xt + 1.75, 12.75, 8.5))
        m.box("horn", frm, to, JADE_EDGE, rotation=("z", -22.5 * side, (xt, 11.25, C)))
    m.group("blade")
    m.box("blade_root", (6, 8.75, 6.75), (10, 10.25, 9.25), GOLD_CLOUD)
    m.box("blade", (5.5, 8.75, 7), (10.5, 23.5, 9), JADE_SCALE)
    m.box("blade_edge", (4.75, 9.5, 7.4), (5.5, 22.75, 8.6), JADE_EDGE)
    m.box("blade_edge", (10.5, 9.5, 7.4), (11.25, 22.75, 8.6), JADE_EDGE)
    m.box("blade_ridge", (7.5, 10.25, 6.75), (8.5, 21.75, 9.25), AZURE_PLAIN)
    m.box("blade_ridge_pearl", (7.5, 10.5, 6.6), (8.5, 11.5, 9.4), EYE)
    m.box("blade_tip", (6.25, 23.5, 7), (9.75, 25, 9), JADE_POLISH)
    m.box("blade_tip", (7, 25, 7.25), (9, 26.25, 8.75), JADE_EDGE)
    m.box("blade_tip", (7.5, 26.25, 7.5), (8.5, 27, 8.5), JADE_EDGE)
    return _held(m, "sword")


def dragon_pickaxe():
    """청룡 곡괭이: a gold head holding a pearl, two jade scaled arms that bend down like a dragon's legs and end in ivory
    claws, two little jade horns on top."""
    m = MModel("dragon_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    m.box("head", (5, 15.25, 6.5), (11, 20.75, 9.5), GOLD_CLOUD)
    pearl(m, 8, 18, 8, 1.125, cz=(6.5, 9.5))
    m.box("head_cap", (5.75, 20.75, 6.75), (10.25, 21.5, 9.25), JADE_DARK)
    m.box("horn", (6, 21.5, 7.5), (7, 23, 8.5), JADE_EDGE)
    m.box("horn", (9, 21.5, 7.5), (10, 23, 8.5), JADE_EDGE)
    for side in (1, -1):
        p = (C + side * 3, 18.25)
        p = _arm(m, "arm_a", side, p, 5.0, 1.875, 6.75, 9.25, 22.5, JADE_SCALE)
        k = p
        p = _arm(m, "arm_b", side, p, 4.0, 1.5, 7, 9, 45, JADE_SCALE)
        # a gold knuckle where the arm bends
        kx, ky = k
        m.box("knuckle", (kx - 1.25, ky - 1.25, 6.75), (kx + 1.25, ky + 1.25, 9.25), GOLD, rotation=("z", -22.5 * side, (kx, ky, C)))
        px, py = p
        if side > 0:
            frm, to = (px + 0.25, py - 1, 7.25), (px + 2.75, py + 1, 8.75)
        else:
            frm, to = (px - 2.75, py - 1, 7.25), (px - 0.25, py + 1, 8.75)
        m.box("claw", frm, to, IVORY_M, rotation=("z", -45 * side, (px, py, C)))
        # the claw's point, thinner, along the same bend
        if side > 0:
            frm, to = (px + 2.75, py - 0.5, 7.6), (px + 4, py + 0.5, 8.4)
        else:
            frm, to = (px - 4, py - 0.5, 7.6), (px - 2.75, py + 0.5, 8.4)
        m.box("claw_point", frm, to, IVORY_M, rotation=("z", -45 * side, (px, py, C)))
    return _held(m, "pickaxe")


def dragon_shovel():
    """청룡 삽: a jade spade shaped like one big scale, a gold rim proud of its face, a gold socket holding a pearl."""
    m = MModel("dragon_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17.25, GOLD_CLOUD, 0.75)
    rows = ((17.25, 18.25, 3.75, 7, 9), (18.25, 23.5, 4.5, 7, 9), (23.5, 25.5, 3.75, 7, 9), (25.5, 27, 2.75, 7.2, 8.8))
    for y0, y1, hw, z0, z1 in rows:
        m.box("blade", (C - hw, y0, z0), (C + hw, y1, z1), JADE_SCALE)
        m.box("rim", (C - hw, y0, z1), (C - hw + 0.75, y1, z1 + 0.4), GOLD_LIGHT)
        m.box("rim", (C + hw - 0.75, y0, z1), (C + hw, y1, z1 + 0.4), GOLD_LIGHT)
    m.box("point", (6.5, 27, 7.2), (9.5, 28.25, 8.8), JADE_EDGE)
    m.box("point", (7.25, 28.25, 7.4), (8.75, 29, 8.6), JADE_EDGE)
    m.box("spine", (7.25, 17.25, 6.6), (8.75, 25, 7), GOLD)
    pearl(m, 8, 19.25, 8, 1, cz=(7, 9), d=0.6)
    return _held(m, "shovel")


def dragon_axe():
    """청룡 도끼: a dragon's fin for a blade (jade, dark ribs fanning from the socket, a pale edge, spines along its back),
    a gold socket with a pearl, a gold claw for a poll."""
    m = MModel("dragon_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 21.5, 22.5, GOLD_LIGHT, 0.75)
    m.box("socket", (4.75, 14, 6.5), (10.25, 20.5, 9.5), GOLD_CLOUD)
    pearl(m, 7.5, 17.25, 8, 1, cz=(6.5, 9.5))
    m.box("fin", (2.25, 13, 6.875), (4.75, 21.5, 9.125), JADE_SCALE)
    m.box("fin", (0, 12, 7.25), (2.25, 22.5, 8.75), JADE_SCALE)
    m.box("fin_edge", (-1.25, 12.5, 7.5), (0, 22, 8.5), JADE_EDGE)
    m.box("fin_edge", (-0.75, 11.5, 7.5), (0, 12.5, 8.5), JADE_EDGE)
    m.box("fin_edge", (-0.75, 22, 7.5), (0, 23, 8.5), JADE_EDGE)
    # ribs fanning out from the socket
    for ang, y in ((-22.5, 18.5), (0, 17.25), (22.5, 16)):
        m.box("fin_rib", (0.25, y - 0.375, 7.0), (4.75, y + 0.375, 9.0), AZURE_PLAIN, rotation=("z", ang, (4.75, y, C)))
    # spines on the fin's back
    m.box("spine", (1, 22.5, 7.6), (2, 23.75, 8.4), JADE_EDGE)
    m.box("spine", (3, 21.5, 7.6), (4, 22.75, 8.4), JADE_EDGE)
    m.box("poll", (10.25, 15, 6.75), (11.75, 19.5, 9.25), GOLD)
    m.box("poll_claw", (11.75, 15.75, 7.25), (13, 18.75, 8.75), IVORY_M)
    m.box("poll_claw", (13, 16.5, 7.6), (13.75, 18, 8.4), IVORY_M)
    return _held(m, "axe")


def dragon_hoe():
    """청룡 괭이: a dragon's head on the shaft: a jade snout with a glowing eye and a gold horn swept back, its jaw the
    hoe's plate hanging from the snout's end, ivory fangs along its edge, a pearl in its outer face."""
    m = MModel("dragon_hoe")
    handle(m)
    shaft(m, 20.5)
    m.group("head")
    m.box("snout", (2.75, 19.5, 6.75), (9.75, 22.5, 9.25), JADE_SCALE)
    m.box("eye", (5.75, 20.75, 6.6), (6.75, 21.5, 9.4), EYE)
    m.box("brow", (5.5, 21.5, 6.6), (7.25, 22, 9.4), GOLD_LIGHT)
    m.box("horn", (6.5, 21.75, 7.4), (11.5, 23, 8.6), GOLD, rotation=("z", 22.5, (7, 22.4, C)))
    m.box("horn_tip", (11.5, 22, 7.6), (13, 22.75, 8.4), JADE_EDGE, rotation=("z", 22.5, (7, 22.4, C)))
    for x in (3.25, 4.75):
        m.box("fang", (x, 19, 7.4), (x + 0.75, 19.75, 8.6), IVORY_M)
    m.box("jaw_band", (-0.25, 21.25, 4.75), (3, 23, 11.25), GOLD_CLOUD)
    m.box("jaw", (0, 14.75, 5), (2.75, 21.25, 11), JADE_DEEP)
    m.box("jaw_edge", (0.25, 13.75, 5.25), (2.5, 14.75, 10.75), IVORY_M)
    for z in (5.5, 7.5, 9.5):
        m.box("jaw_fang", (0.75, 12.75, z), (2, 13.75, z + 1), IVORY_M)
    m.box("pearl", (-0.65, 17, 7), (0, 19.5, 9), PEARL)
    m.box("pearl", (-0.65, 17.5, 6.5), (0, 19, 9.5), PEARL)
    return _held(m, "hoe")


TIP = (11.25, 28.75)      # where the line leaves the dragon's mouth (x, y)


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
    m.box("rod", (6.875, 14, 6.875), (9.125, 21.5, 9.125), JADE_POLISH)
    m.box("rod", (7.125, 21.5, 7.125), (8.875, 27.5, 8.875), JADE_POLISH)
    for y, w in ((13.5, 1.625), (21, 1.375), (26.75, 1.125)):
        m.box("rod_ring", (C - w, y, C - w), (C + w, y + 0.75, C + w), GOLD_LIGHT)
    m.box("guide", (9.125, 17.25, 7.4), (10.5, 18.25, 8.6), GOLD)
    m.box("guide", (8.875, 24, 7.4), (10.25, 25, 8.6), GOLD)
    m.group("dragon_head")
    m.box("neck", (6.875, 27.5, 6.875), (9.125, 28.5, 9.125), GOLD_CLOUD)
    m.box("jaw", (7, 28.25, 7.1), (11.25, 29, 8.9), JADE_DARK)
    m.box("head", (6.5, 29, 6.75), (9.75, 31.25, 9.25), JADE_SCALE)
    m.box("snout", (9.75, 29.25, 7.1), (11.5, 30.5, 8.9), JADE_POLISH)
    m.box("eye", (8.5, 30, 6.6), (9.25, 30.75, 9.4), EYE)
    m.box("mouth_pearl", (10.5, 28.6, 7.4), (11.5, 29.4, 8.6), PEARL)
    m.box("horn", (6.25, 30.5, 7.3), (7.75, 31.75, 8.7), GOLD_LIGHT)
    m.box("horn", (5.25, 31, 7.5), (6.5, 32, 8.5), GOLD_LIGHT)
    m.box("mane", (5.75, 28.5, 7.6), (6.5, 30.5, 8.4), JADE_EDGE)
    if not cast:
        m.group("line")
        top = (TIP[0], TIP[1], C)
        rot = ("z", 45, top)
        x = TIP[0]
        m.box("line", (x - 0.375, 18.5, 7.625), (x + 0.375, TIP[1], 8.375), LINE, rotation=rot)
        # the float: a pearl in a gold cloud cup
        m.box("float_eye", (x - 0.5, 17.75, 7.5), (x + 0.5, 18.5, 8.5), GOLD_LIGHT, rotation=rot)
        m.box("float_pearl", (x - 1.5, 15, 6.5), (x + 1.5, 17.75, 9.5), PEARL, rotation=rot)
        m.box("float_pearl", (x - 1, 14.5, 7), (x + 1, 18.25, 9), PEARL, rotation=rot)
        m.box("float_cup", (x - 2, 13.5, 6), (x + 2, 15, 10), GOLD_CLOUD, rotation=rot)
        m.box("float_cup", (x - 1, 12.75, 7), (x + 1, 13.5, 9), GOLD, rotation=rot)
    return _held(m, "rod")


def dragon_fishing_rod():
    """청룡 낚싯대: a jade rod with gold rings, a gold reel with a pearl hub, a dragon's head at the tip holding a pearl,
    the line hanging from its mouth with a pearl float in a gold cup."""
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
