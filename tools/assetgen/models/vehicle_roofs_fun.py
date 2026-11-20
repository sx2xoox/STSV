"""
초밥 카니발의 재미있는 루프 장식 (roof options, the funny / weird ones): each replaces the stock roof part
(sushi_carnival_roof: the cross bars + the roof box) and is drawn in the BODY's own space and frame, exactly like
the doors: BModel scale 2, shift (8, 8, 8), 16 px a block, the car's ground centre at (0, 0, 0), front = north (-z),
+x = the car's right. They sit on the body's two roof rails (|x| 11.5..12.75, top RAIL_Y, z -14..36): each brings
its own black cross bars (with clamps round the rails) and, where it makes sense, a couple of black ratchet straps
hooked on the bars. Nothing goes in front of z -18 (the windscreen's top is at z -19).

The "16 px, angled panels" style of vehicles_donation_sushi_carnival: big clean boxes, slopes as 22.5/45-degree
panels (plank()), the detail painted in world-space decals, real 3D only where it shows. Not painted with the body
(options keep their own colours); emissive only for the Christmas tree's bulbs.

Options (sushi_carnival_roof_r_<id>):
    salmon      연어 초밥: a giant salmon nigiri (rice block with grains, an orange slice with white fat stripes)
    tamago      계란 초밥: a yellow omelette block on rice, a nori belt round it
    shrimp      새우 초밥: a butterflied shrimp (red stripes) on rice, its tail fan sticking up behind
    kimbap      김밥 한 줄: a long roll, two slices cut off the front end showing the rice and fillings
    bungeoppang 붕어빵: a giant golden fish-shaped bread standing on edge, scale pattern, chocks either side
    duck        거대 고무 오리: a yellow rubber duck, orange beak
    cat         식빵 고양이: an orange tabby in the loaf pose on a cushion, tail curled round
    watermelon  수박 반 통: half an oval watermelon cut side up, seeds, a big spoon stuck in it
    mattress    이사 가는 날: a mattress with a rolled duvet and a pillow, tied on with rope
    tree        크리스마스 트리: a Christmas tree lying on the rack, strapped, its lights glowing
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, bands
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp
from stsv_assets import presets
from models.vehicles_donation_sushi_carnival import plank, tone, smooth, _h, _x, RAIL_Y, BAR_Y


def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("rf_rice", 95, 0.012, 0.86, 0.26, 6, False)
_ramp("rf_salmon", 45, 0.150, 0.66, 0.36, 6, False)
_ramp("rf_fat", 60, 0.050, 0.90, 0.10, 3, False)
_ramp("rf_nori", 165, 0.035, 0.22, 0.20, 4, False)
_ramp("rf_egg", 95, 0.150, 0.80, 0.30, 6, False)
_ramp("rf_shrimp", 32, 0.170, 0.60, 0.40, 6, False)
_ramp("rf_shrimp_w", 50, 0.040, 0.90, 0.10, 3, False)
_ramp("rf_bread", 70, 0.120, 0.62, 0.44, 7, False)
_ramp("rf_duck", 100, 0.165, 0.84, 0.26, 6, False)
_ramp("rf_beak", 52, 0.170, 0.66, 0.30, 4, False)
_ramp("rf_eye", 260, 0.010, 0.16, 0.12, 3, False)
_ramp("rf_tabby", 58, 0.120, 0.64, 0.42, 6, False)
_ramp("rf_cream", 80, 0.035, 0.88, 0.16, 4, False)
_ramp("rf_pink", 10, 0.080, 0.74, 0.20, 4, False)
_ramp("rf_plaid", 25, 0.130, 0.50, 0.34, 5, False)
_ramp("rf_melon", 25, 0.190, 0.58, 0.30, 5, False)
_ramp("rf_rind", 140, 0.120, 0.44, 0.40, 6, False)
_ramp("rf_rind_w", 120, 0.050, 0.86, 0.12, 3, False)
_ramp("rf_ticking", 235, 0.030, 0.82, 0.30, 5, False)
_ramp("rf_duvet", 350, 0.080, 0.76, 0.28, 5, False)
_ramp("rf_rope", 75, 0.070, 0.64, 0.30, 4, False)
_ramp("rf_pine", 150, 0.090, 0.40, 0.34, 6, False)
_ramp("rf_wood", 55, 0.060, 0.42, 0.26, 4, False)
_ramp("rf_steel", 240, 0.008, 0.76, 0.30, 4, False)
_ramp("rf_strap", 255, 0.020, 0.24, 0.16, 3, False)
_ramp("rf_bulb_r", 25, 0.200, 0.66, 0.16, 3, True)
_ramp("rf_bulb_y", 85, 0.170, 0.86, 0.12, 3, True)
_ramp("rf_bulb_b", 250, 0.150, 0.66, 0.16, 3, True)
_ramp("rf_bulb_g", 145, 0.170, 0.76, 0.14, 3, True)

Y0 = BAR_Y                     # the bars' top: everything rests here
T = math.tan(math.radians(22.5))
BLACK = Paint("sc_black", 1, noise=0, grad=0, rim=0.3, ao=0.5)
STRAP = Paint("rf_strap", 1, noise=0, grad=0, rim=0.3, ao=0.3,
              decal=lambda c: tone("rf_strap", 0.75 if c.face == "up" else 0.4))
BUCKLE = Paint("sc_chrome", 5, noise=0, rim=0.4, decal=lambda c: tone("sc_chrome", 0.9 if c.face == "up" else 0.65))
ROPE = Paint("rf_rope", 2, noise=0, grad=0, rim=0.2, ao=0.3,
             decal=lambda c: tone("rf_rope", 0.85 if (math.floor(c.p[0] + c.p[1] + c.p[2]) % 2) else 0.45))


def _model(name):
    m = BModel(name, shift=(8, 8, 8), scale=2.0)
    m.display = presets.furniture(gui_scale=0.4)
    m.group("roof")
    return m


def _bars(m, zs):
    """Black cross bars over the rails, a clamp round each rail (like the stock rack)."""
    for zb in zs:
        m.box("cross_bar", (-13, RAIL_Y, zb), (13, BAR_Y, zb + 1.5), BLACK)
        for s in (-1, 1):
            lo, hi = sorted((s * 11.25, s * 13.25))
            m.box("bar_clamp", (lo, RAIL_Y - 0.5, zb - 0.25), (hi, BAR_Y + 0.25, zb + 1.75), BLACK)


def _tie(m, z, prof, w=1.5, paint=STRAP, t=0.35, buckle=True):
    """A strap (or rope) across the load at z..z+w: prof is its path down the right side as (x, y) points of the
    load's surface, starting on the top (x 0), each step horizontal, vertical or at 45 degrees; mirrored left."""
    z_, w_ = z, w
    for i in range(len(prof) - 1):
        (x0, y0), (x1, y1) = prof[i], prof[i + 1]
        e = 0.05 * (i % 2)                           # neighbours never share a face plane
        z, w = z_ + e, w_ - 2 * e
        if abs(y1 - y0) < 1e-6:
            if x0 == 0:
                m.box("strap", (-x1, y0, z), (x1, y0 + t, z + w), paint)
            else:
                for s in (-1, 1):
                    m.box("strap", (*_x(s, x0, x1)[:1], y0, z), (_x(s, x0, x1)[1], y0 + t, z + w), paint)
        elif abs(x1 - x0) < 1e-6:
            for s in (-1, 1):
                a, b = _x(s, x1, x1 + t)
                ylo = min(y0, y1) + (t if i + 2 < len(prof) else 0)     # sits on the next run, not beside it
                m.box("strap", (a, ylo, z), (b, max(y0, y1) + t, z + w), paint)
        else:
            for s in (-1, 1):
                plank(m, "strap_slope", "z", (s * x0, y0), (s * x1, y1), (z, z + w), t, paint, below=False)
    if buckle:
        z, w = z_, w_
        m.box("buckle", (prof[-1][0] + t, Y0 + 1, z - 0.25), (prof[-1][0] + t + 0.4, Y0 + 2.5, z + w + 0.25), BUCKLE)


def _chamfer_block(m, name, x, y0, y1, z0, z1, c, paint, decal):
    """A box |x| <= x, y0..y1, z0..z1 with its two long top edges bevelled at 45 degrees by c."""
    m.box(name, (-x, y0, z0), (x, y1 - c, z1), paint)
    m.box(name, (-(x - c), y1 - c, z0), (x - c, y1, z1), paint)
    for s in (-1, 1):
        plank(m, name + "_bevel", "z", (s * (x - c), y1), (s * x, y1 - c), (z0, z1), c * 0.72, paint, decal=decal)


def _round_corners(m, name, x, y0, y1, z0, z1, c, paint, decal):
    """A box |x| <= x, z0..z1 whose four vertical corners are cut at 45 degrees by c (seen from above)."""
    m.box(name, (-x, y0, z0 + c), (x, y1, z1 - c), paint)
    m.box(name, (-(x - c), y0, z0), (x - c, y1, z0 + c), paint)
    m.box(name, (-(x - c), y0, z1 - c), (x - c, y1, z1), paint)
    for s in (-1, 1):
        plank(m, name + "_corner", "y", (z0, s * (x - c)), (z0 + c, s * x), (y0, y1), c * 0.72, paint, decal=decal,
              below=s > 0)
        plank(m, name + "_corner", "y", (z1 - c, s * x), (z1, s * (x - c)), (y0, y1), c * 0.72, paint, decal=decal,
              below=s > 0)


# ================================================================================================
# Sushi
# ================================================================================================

def _rice_decal(c):
    x, y, z = c.p
    ix, iy, iz = math.floor(x), math.floor(y), math.floor(z)
    k = _h(ix, iy, iz) % 9
    side = 0 if c.face == "up" else -0.12 * (1 - smooth((y - Y0) / 5))
    if k == 0:
        return tone("rf_rice", 0.35 + side)
    if k in (1, 2):
        return tone("rf_rice", 0.68 + side)
    return tone("rf_rice", 0.95 + side)


RICE = Paint("rf_rice", 4, noise=0, grad=0, rim=0.2, ao=0.6, decal=_rice_decal)
RX, RZ0, RZ1, RY = 8, -10, 32, Y0 + 6          # the rice block: |x|, z from, to, its top


def _rice(m, top=RY):
    _round_corners(m, "rice", RX, Y0, top, RZ0, RZ1, 2, RICE, _rice_decal)


def _salmon_decal(c):
    x, y, z = c.p
    if c.face == "down":
        return tone("rf_salmon", 0.25)
    w = (z + 0.55 * abs(x) + 0.6 * math.sin(x * 0.7)) % 6.5
    if w < 1.1:
        return tone("rf_fat", 0.9 if c.face == "up" else 0.6)
    if w < 1.6:
        return tone("rf_salmon", 0.85)
    if c.face == "up":
        if -4 <= x < -2.5:
            return tone("rf_fat", 1.0) if math.floor(z) % 5 else tone("rf_salmon", 0.95)   # the wet gloss
        return tone("rf_salmon", 0.6 + 0.15 * smooth((x + 9) / 18))
    return tone("rf_salmon", 0.45)


def salmon():
    m = _model("sushi_carnival_roof_r_salmon")
    _bars(m, (-8, 10, 27))
    _rice(m)
    sp = Paint("rf_salmon", 3, noise=0, grad=0, rim=0.3, ao=0.4, decal=_salmon_decal)
    top = RY + 2
    m.box("salmon", (-9, RY, -12), (9, top, 34), sp)
    for s in (-1, 1):
        plank(m, "salmon_drape", "z", (s * 9, top), (s * 10.5, top - 1.5), (-12, 34), 1, sp, decal=_salmon_decal)
    plank(m, "salmon_drape", "x", (-14, top - 2), (-12, top), (-9, 9), 1, sp, decal=_salmon_decal, below=False)
    plank(m, "salmon_drape", "x", (34, top), (37, top - 3), (-9, 9), 1, sp, decal=_salmon_decal, below=False)
    for zb in (-8, 27):
        _tie(m, zb, [(0, top), (9, top), (10.5, top - 1.5), (10.5, Y0)])
    return m


def _egg_decal(c):
    x, y, z = c.p
    if c.face == "down":
        return tone("rf_egg", 0.2)
    if c.face == "up":
        k = _h(math.floor(x / 2), math.floor(z / 2)) % 11
        if k == 0:
            return tone("rf_egg", 0.35)                                  # browned spots
        return tone("rf_egg", 0.92 if -4 <= x < -2 else 0.78)
    band = (y - Y0) % 1.75
    return tone("rf_egg", 0.45 if band < 0.4 else (0.85 if band < 0.8 else 0.65))      # the rolled layers


def _nori_decal(c):
    x, y, z = c.p
    k = _h(math.floor(x * 2), math.floor(y * 2), math.floor(z * 2)) % 7
    base = 0.55 if c.face == "up" else 0.35
    return tone("rf_nori", base + (0.25 if k == 0 else 0))


NORI = Paint("rf_nori", 1, noise=0, grad=0, rim=0.3, ao=0.4, decal=_nori_decal)


def tamago():
    m = _model("sushi_carnival_roof_r_tamago")
    _bars(m, (-6, 11, 28))
    ry = Y0 + 4
    _rice(m, ry)
    ep = Paint("rf_egg", 3, noise=0, grad=0, rim=0.3, ao=0.4, decal=_egg_decal)
    top = ry + 6.5
    _chamfer_block(m, "egg", 9, ry, top, -12, 34, 1.5, ep, _egg_decal)
    _tie(m, 9, [(0, top), (7.5, top), (9, top - 1.5), (9, Y0)], w=6, paint=NORI, t=0.4, buckle=False)
    return m


def _shrimp_decal(c):
    x, y, z = c.p
    if c.face == "down":
        return tone("rf_shrimp", 0.3)
    if z > 27.5:
        # the tail fan: orange to a deep red tip, fan lines
        if (x % 2.5) < 0.5:
            return tone("rf_shrimp", 0.2)
        return tone("rf_shrimp", 0.75 - 0.6 * smooth((z - 28) / 7))
    if z < -9 and abs(x) < 0.6 + (z + 12) * 0.0 and c.face == "up":
        return tone("rf_shrimp", 0.3)                                   # the butterfly cut's notch
    w = (z + 0.06 * x * x) % 5.5
    if c.face == "up" and abs(x) < 0.5:
        return tone("rf_shrimp_w", 0.5)                                 # the centre line
    if w < 2.2:
        return tone("rf_shrimp", 0.55 if c.face == "up" else 0.4)
    if w < 2.8:
        return tone("rf_shrimp", 0.85)
    return tone("rf_shrimp_w", 1.0 if c.face == "up" else 0.6)


def shrimp():
    m = _model("sushi_carnival_roof_r_shrimp")
    _bars(m, (-8, 10, 26))
    _rice(m)
    sp = Paint("rf_shrimp", 3, noise=0, grad=0, rim=0.3, ao=0.4, decal=_shrimp_decal)
    top = RY + 2
    m.box("shrimp", (-9, RY, -12), (9, top, 21), sp)
    m.box("shrimp", (-6.5, RY, 21), (6.5, top, 28), sp)
    for s in (-1, 1):
        plank(m, "shrimp_drape", "z", (s * 9, top), (s * 10.5, top - 1.5), (-12, 21), 1, sp, decal=_shrimp_decal)
    plank(m, "shrimp_drape", "x", (-14, top - 2), (-12, top), (-9, 9), 1, sp, decal=_shrimp_decal, below=False)
    # the tail fan, sticking up behind at 22.5 degrees, two side fins a little lower
    plank(m, "shrimp_tail", "x", (27, top), (36, top + 9 * T), (-3.5, 3.5), 1, sp, decal=_shrimp_decal, below=False)
    for s in (-1, 1):
        plank(m, "shrimp_fin", "x", (27, top - 0.5), (34.5, top - 0.5 + 7.5 * T), _x(s, 3.5, 7.5), 0.9, sp,
              decal=_shrimp_decal, below=False)
    for zb in (-8, 10):
        _tie(m, zb, [(0, top), (9, top), (10.5, top - 1.5), (10.5, Y0)])
    return m


# ================================================================================================
# 김밥
# ================================================================================================

KD, KCY = 15, Y0 + 7.5


def _kimbap_decal(c):
    x, y, z = c.p
    if c.face in ("north", "south"):
        r = math.hypot(x, y - KCY)
        if r > 6.6:
            return tone("rf_nori", 0.3)
        if r > 4.1:
            return _rice_decal(c)
        dx, dy = x, y - KCY
        if abs(dx) < 1.2 and dy > 0.6:
            return tone("rf_egg", 0.95)                                 # danmuji (yellow pickle)
        if abs(dx) < 1.2 and dy < -0.8:
            return tone("rf_egg", 0.6)                                  # egg
        if dx < -1.2 and dy > -0.5:
            return tone("rf_beak", 0.8)                                 # carrot
        if dx > 1.2 and dy > -0.5:
            return tone("rf_rind", 0.6)                                 # spinach
        if dx < -1.2:
            return tone("rf_pink", 0.6)                                 # ham
        if dx > 1.2:
            return tone("rf_wood", 0.4)                                 # beef
        return tone("rf_rice", 0.95)
    k = _h(math.floor(x * 2), math.floor(y * 2), math.floor(z * 2)) % 41
    if k == 0:
        return tone("rf_rice", 0.75)                                    # sesame
    if c.face == "up" and -4 <= x < -2:
        return tone("rf_nori", 0.95)                                    # the sesame oil's shine
    return tone("rf_nori", 0.5 if c.face == "up" else 0.3)


def kimbap():
    m = _model("sushi_carnival_roof_r_kimbap")
    _bars(m, (-6, 12, 29))
    kp = Paint("rf_nori", 1, noise=0, grad=0, rim=0.3, ao=0.4, decal=_kimbap_decal)
    cyl(m, "kimbap", (0, KCY), KD, -9.5, 35, kp, axis="z")
    cyl(m, "kimbap_slice", (0, KCY), KD, -12.5, -10.25, kp, axis="z")
    cyl(m, "kimbap_slice", (0, KCY), KD, -16, -13.25, kp, axis="z")
    for zb in (12, 29):
        _tie(m, zb, [(0, KCY + 7.5), (2.5, KCY + 7.5), (7.5, KCY + 2.5), (7.5, Y0)])
    return m


# ================================================================================================
# 붕어빵
# ================================================================================================

FCY = Y0 + 7.5                     # the fish's middle height


def _fish_rows(shrink, off=0.0):
    """[(y0, y1, z0, z1)] the fish's side outline as 1 px rows, shrunk by `shrink` px (rows and ends moved by off,
    so the two layers never share a face plane)."""
    rows = []
    for i in range(15):
        y = Y0 + i + 0.5 + off
        dy = y - FCY
        spans = []
        a, b = 17 - shrink, 7.5 - shrink
        if abs(dy) < b:
            hz = a * math.sqrt(1 - (dy / b) ** 2)
            spans.append([5 - hz, 5 + hz])
        reach = 2.2 + (34 - 22) * 0.42 - shrink
        if abs(dy) < reach:
            tz0 = 22 + max(0, (abs(dy) - 2.2 + shrink) / 0.42)
            tz1 = 34.5 - shrink
            fork = 31.5 if abs(dy) < 1.5 else tz1
            if tz0 < fork:
                spans.append([tz0, fork])
        spans.sort()
        merged = []
        for s in spans:
            if merged and s[0] <= merged[-1][1] + 0.01:
                merged[-1][1] = max(merged[-1][1], s[1])
            else:
                merged.append(s)
        for z0, z1 in merged:
            z0, z1 = round(z0 * 2) / 2 + off / 2, round(z1 * 2) / 2 + off / 2
            if z1 - z0 >= 1:
                rows.append((Y0 + i + off, Y0 + i + 1 + off, z0, z1))
    return rows


def _fish_decal(c):
    x, y, z = c.p
    if c.face in ("east", "west") and abs(x) > 3.6:
        dy = y - FCY
        if -8 < z < -5 and abs(dy - 2.2) < 1.3:
            r = math.hypot(z + 6.5, dy - 2.2)
            if r < 1.4:
                return tone("rf_eye", 2.0) if r < 0.5 else tone("rf_bread", 0.08)      # the eye
        if -4.5 < z < -3.5 and abs(dy) < 4:
            return tone("rf_bread", 0.3)                                # the gill
        if z < -8.5 and -1.6 < dy < -0.8:
            return tone("rf_bread", 0.3)                                # the mouth
        if z > 23:
            return tone("rf_bread", 0.3 if (y * 2) % 3 < 0.8 else 0.6)  # the tail fin's ribs
        if -3 < z < 22:
            row = math.floor((y - Y0) / 2.5)
            u = (z + (1.5 if row % 2 else 0)) % 3 - 1.5
            v = (y - Y0) % 2.5
            if abs(math.hypot(u, v) - 1.6) < 0.45:
                return tone("rf_bread", 0.35)                           # the scales
        return tone("rf_bread", 0.78 - 0.1 * smooth(abs(dy) / 7))
    if c.face == "up":
        return tone("rf_bread", 0.55)
    if c.face == "down":
        return tone("rf_bread", 0.15)
    return tone("rf_bread", 0.45)


def bungeoppang():
    m = _model("sushi_carnival_roof_r_bungeoppang")
    _bars(m, (-8, 8, 26))
    core = Paint("rf_bread", 4, noise=0, grad=0, rim=0.3, ao=0.4, decal=_fish_decal)
    rim = Paint("rf_bread", 2, noise=0.15, grad=0, rim=0.3, ao=0.4,
                decal=lambda c: tone("rf_bread", 0.25 if c.face in ("east", "west") else 0.35))
    for y0, y1, z0, z1 in _fish_rows(1, 0.5):
        m.box("fish", (-4, y0, z0), (4, y1, z1), core)
    for y0, y1, z0, z1 in _fish_rows(0):
        m.box("fish_rim", (-2.5, y0, z0), (2.5, y1, z1), rim)
    # wooden chocks either side, a strap over it at each of two bars
    wood = Paint("rf_wood", 2, kind="grain", axis="z", noise=0.1)
    for zb in (-8, 26):
        for s in (-1, 1):
            m.box("chock", (*_x(s, 4, 6)[:1], Y0, zb - 1), (_x(s, 4, 6)[1], Y0 + 2, zb + 2.5), wood)
    for zb, top in ((-0.75, Y0 + 14), (17.25, Y0 + 13)):
        _tie(m, zb, [(0, top), (4, top), (4, Y0 + 2), (6, Y0 + 2), (6, Y0)], buckle=False)
    return m


# ================================================================================================
# 고무 오리
# ================================================================================================

DH = Y0 + 7                         # the duck's back (its body's top)


def _duck_decal(c):
    x, y, z = c.p
    if c.face == "down":
        return tone("rf_duck", 0.2)
    if c.face == "up":
        return tone("rf_duck", 0.95 if -4 <= x < -2 else 0.82)
    if c.face in ("east", "west", "north") and y > DH + 3:
        # the eyes, on the head's front corners and sides
        ey, ez, ex = DH + 5.5, -7, 3.6
        d = math.hypot(abs(x) - ex, y - ey) if c.face == "north" else math.hypot(z - ez, y - ey)
        if d < 1.3:
            if d < 0.55 and y > ey:
                return ("raw", (250, 250, 250, 255))
            return tone("rf_eye", 0.3)
    return tone("rf_duck", 0.5 + 0.3 * smooth((y - Y0) / 9))


def duck():
    m = _model("sushi_carnival_roof_r_duck")
    _bars(m, (-4, 10, 24))
    dp = Paint("rf_duck", 3, noise=0, grad=0, rim=0.3, ao=0.4, decal=_duck_decal)
    # the body: a rounded tub (bevelled bottom, bevelled top edges)
    m.box("duck_base", (-8.5, Y0, -4.5), (8.5, Y0 + 1.5, 26.5), dp)
    for s in (-1, 1):
        plank(m, "duck_belly", "z", (s * 8.5, Y0), (s * 10, Y0 + 1.5), (-4.5, 26.5), 1, dp, decal=_duck_decal,
              below=False)
    _chamfer_block(m, "duck", 10, Y0 + 1.5, DH, -6, 28, 2, dp, _duck_decal)
    # the tail: up and back
    m.box("duck_tail", (-4, DH - 2, 26), (4, DH + 1.5, 30.5), dp)
    plank(m, "duck_tail_top", "x", (25.5, DH + 0.5), (30.5, DH + 5.5), (-3.5, 3.5), 2.5, dp, decal=_duck_decal)
    # the wings: a little proud of the sides
    for s in (-1, 1):
        a, b = _x(s, 10, 10.75)
        m.box("duck_wing", (a, Y0 + 2.5, 8), (b, Y0 + 6, 20), dp)
        plank(m, "duck_wing_tip", "x", (20, Y0 + 6), (23, Y0 + 3), (a, b), 1, dp, decal=_duck_decal)
    # the head, well forward, bevelled all round its top
    hy0, hy1 = DH - 1, Y0 + 15.5
    _chamfer_block(m, "duck_head", 6, hy0, hy1, -11, 1, 2, dp, _duck_decal)
    plank(m, "duck_brow", "x", (-11, hy1 - 2), (-9, hy1), (-4, 4), 1.4, dp, decal=_duck_decal)
    plank(m, "duck_nape", "x", (-1, hy1), (1, hy1 - 2), (-4, 4), 1.4, dp, decal=_duck_decal)
    bp = Paint("rf_beak", 2, noise=0, grad=0, rim=0.3, ao=0.3,
               decal=lambda c: tone("rf_beak", 0.9 if c.face == "up" else 0.55))
    m.box("duck_beak", (-3.5, DH + 2.5, -15.5), (3.5, DH + 4.5, -11), bp)
    m.box("duck_beak_low", (-2.5, DH + 1.5, -14.5), (2.5, DH + 2.5, -11), bp)
    for zb in (2.5, 24):
        _tie(m, zb, [(0, DH), (8, DH), (10, DH - 2), (10, Y0)])
    return m


# ================================================================================================
# 식빵 고양이
# ================================================================================================

CY1 = Y0 + 2                         # the cushion's top


def _cat_decal(c):
    x, y, z = c.p
    ax = abs(x)
    if c.face == "down":
        return tone("rf_tabby", 0.15)
    head = z < -0.5 and y > CY1 + 1.5 and ax < 7.1
    if c.face == "north" and head:
        dy = y - CY1
        # the face: closed happy eyes, a pink nose, a cream muzzle, whiskers
        if 6.5 < dy < 7.5 and 1.5 < ax < 4.5 and abs(ax - 3) > 0.6:
            return tone("rf_eye", 0.5)
        if 7.5 < dy < 8.5 and abs(ax - 3) < 0.6:
            return tone("rf_eye", 0.5)
        if 5 < dy < 6 and ax < 0.75:
            return tone("rf_pink", 0.7)
        if ax > 4.5 and dy in (4.5,) or (ax > 3 and 4 < dy < 4.6 and ax < 6.5):
            return tone("rf_cream", 1.0)
        if dy > 9 and ax < 2.5 and math.floor(x) % 2 == 0:
            return tone("rf_tabby", 0.15)                                # the forehead's M
        if dy < 5 and ax < 3.5:
            return tone("rf_cream", 0.9)
        return tone("rf_tabby", 0.7)
    if c.face == "north" and z < 1 and y < CY1 + 6:
        return tone("rf_cream", 0.8)                                    # the chest
    if c.face in ("up", "east", "west", "south"):
        if y < CY1 + 2.5 and c.face in ("east", "west") and not (ax > 9 and z > 2):
            return tone("rf_cream", 0.7)                                # the belly's edge
        k = (z + 0.08 * ax * ax) % 7
        if k < 1.5 and (c.face == "up" or y > CY1 + 4 + 0.3 * ((z * 1.7) % 3)):
            return tone("rf_tabby", 0.2)                                # the stripes
        return tone("rf_tabby", 0.85 if c.face == "up" else 0.6)
    return tone("rf_tabby", 0.6)


def cat():
    m = _model("sushi_carnival_roof_r_cat")
    _bars(m, (-11, 10, 30))
    def plaid(c):
        x, z = c.p[0], c.p[2]
        a = math.floor(x) % 6 < 2
        b = math.floor(z) % 6 < 2
        if c.face not in ("up",):
            return tone("rf_plaid", 0.3)
        return tone("rf_plaid", 0.1 if a and b else (0.45 if a or b else 0.75))
    m.box("cushion", (-11.5, Y0, -15), (11.5, CY1, 34), Paint("rf_plaid", 2, noise=0, grad=0, ao=0.4, decal=plaid))
    cp = Paint("rf_tabby", 3, noise=0, grad=0, rim=0.3, ao=0.4, decal=_cat_decal)
    _chamfer_block(m, "cat", 9, CY1, CY1 + 10, -2, 28, 2.5, cp, _cat_decal)
    plank(m, "cat_rump", "x", (28, CY1 + 10), (30.5, CY1 + 7.5), (-6.5, 6.5), 1.8, cp, decal=_cat_decal, below=False)
    m.box("cat_rump", (-6.5, CY1, 28), (6.5, CY1 + 7.5, 30.5), cp)
    # the head, in front of the body
    hy0, hy1 = CY1 + 1.5, CY1 + 11
    _chamfer_block(m, "cat_head", 7, hy0, hy1, -10, 0, 1.5, cp, _cat_decal)
    for s in (-1, 1):
        # the ears: a square turned 45 degrees, half sunk in the head
        m.box("cat_ear", (s * 4 - 1.6, hy1 - 1.6, -7), (s * 4 + 1.6, hy1 + 1.6, -5), cp,
              rotation=("z", 45, (s * 4, hy1, -6)))
    m.box("cat_muzzle", (-2.5, CY1 + 3.5, -11), (2.5, CY1 + 6, -10),
          Paint("rf_cream", 2, noise=0, grad=0, decal=lambda c: tone("rf_pink", 0.7) if c.face == "north" and c.p[1] > CY1 + 5 and abs(c.p[0]) < 0.75 else tone("rf_cream", 0.9)))
    paw = Paint("rf_cream", 2, noise=0, grad=0, ao=0.4)
    for s in (-1, 1):
        m.box("cat_paw", (*_x(s, 1.5, 5)[:1], CY1, -12), (_x(s, 1.5, 5)[1], CY1 + 1.5, -10), paw)
    # the tail, along the right side, its tip curled to the front
    def tail(c):
        return tone("rf_tabby", 0.2) if math.floor(c.p[2]) % 4 == 0 or (c.p[2] < 5 and math.floor(c.p[0]) % 3 == 0) else tone("rf_tabby", 0.75)
    tp = Paint("rf_tabby", 3, noise=0, grad=0, ao=0.4, decal=tail)
    m.box("cat_tail", (9, CY1, 4.5), (11.25, CY1 + 2.25, 28), tp)
    m.box("cat_tail", (5.5, CY1, 1.5), (11.25, CY1 + 2.25, 4.5), tp)
    # the cushion tied down at its ends
    for zb in (-11, 30):
        _tie(m, zb, [(0, CY1), (11.5, CY1), (11.5, Y0)], t=0.3, buckle=False)
    return m


# ================================================================================================
# 수박 반 통
# ================================================================================================

WT = Y0 + 8.5                                             # the cut face
SECTIONS = [(-12.5, -10, 10), (-10, -6, 15), (-6, 26, 19), (26, 30, 15), (30, 32.5, 10)]   # z0, z1, diameter


def _top_hw(d):
    return [hw for a, b, hw in bands(d) if a < 0 <= b or a < 0 < b][0]


RECTS = [(z0, z1, _top_hw(d)) for z0, z1, d in SECTIONS]


def _inside(x, z):
    return any(z0 <= z <= z1 and abs(x) <= hw for z0, z1, hw in RECTS)


def _melon_decal(c):
    x, y, z = c.p
    if c.face == "up" and y > WT - 0.1:
        near = lambda r: all(_inside(x + dx, z + dz) for dx in (-r, 0, r) for dz in (-r, 0, r))
        if not near(0.9):
            return tone("rf_rind", 0.25)
        if not near(1.9):
            return tone("rf_rind_w", 0.8)
        if not near(2.6):
            return tone("rf_melon", 0.9)
        ix, iz = math.floor(x / 3), math.floor(z / 3)
        if _h(ix, iz, 7) % 5 < 2:
            sx, sz = ix * 3 + 1 + _h(ix, iz) % 2, iz * 3 + 1
            if math.floor(x) == sx and sz <= math.floor(z) <= sz + 1:
                return tone("rf_eye", 0.4)                              # a seed
        return tone("rf_melon", 0.65 if _h(math.floor(x), math.floor(z)) % 6 else 0.8)
    th = math.atan2(x, WT - y)
    k = (th * 2.2 + 0.25 * math.sin(z * 0.9) + 10) % 1
    if c.face == "up":
        return tone("rf_rind", 0.7)
    return tone("rf_rind", 0.12 if k < 0.38 else 0.7)


def watermelon():
    m = _model("sushi_carnival_roof_r_watermelon")
    _bars(m, (-4, 10, 24))
    wp = Paint("rf_rind", 3, noise=0, grad=0, rim=0.2, ao=0.4, decal=_melon_decal)
    for z0, z1, d in SECTIONS:
        cyl(m, "melon", (0, WT), d, z0, z1, wp, axis="z", clip=(Y0, WT))
    # a big spoon stuck in it
    steel = Paint("rf_steel", 2, noise=0, grad=0, rim=0.4, decal=lambda c: tone("rf_steel", 0.95 if c.face == "up" else 0.5))
    m.box("spoon_bowl", (-2, WT - 1, 9), (2, WT + 0.5, 14), steel)
    plank(m, "spoon_handle", "x", (13, WT + 0.5), (22, WT + 9.5), (-0.75, 0.75), 0.75, steel)
    for zb in (-4, 24):
        hw = _top_hw(19)
        _tie(m, zb, [(0, WT), (hw, WT), (hw, Y0)])
    return m


# ================================================================================================
# 이사 가는 날
# ================================================================================================

MY = Y0 + 6                                                # the mattress's top


def _mattress_decal(c):
    x, y, z = c.p
    if c.face == "down":
        return tone("rf_ticking", 0.3)
    if c.face == "up":
        if (math.floor(x) % 6 == 0) and (math.floor(z) % 6 == 0):
            return tone("rf_ticking", 0.15)                             # the quilting's tufts
        return tone("rf_ticking", 0.55 if math.floor(x) % 3 == 0 else 0.95)
    if abs(y - (MY - 1.2)) < 0.4 or abs(y - (Y0 + 0.8)) < 0.4:
        return tone("rf_ticking", 0.25)                                 # the piping
    if c.face in ("east", "west") and math.floor(z) % 12 == 5 and abs(y - (Y0 + 3)) < 0.6:
        return tone("rf_steel", 0.4)                                    # a vent
    return tone("rf_ticking", 0.5 if math.floor(z + x) % 3 == 0 else 0.8)


def mattress():
    m = _model("sushi_carnival_roof_r_mattress")
    _bars(m, (-7, 10, 24))
    mp = Paint("rf_ticking", 3, noise=0, grad=0, rim=0.3, ao=0.4, decal=_mattress_decal)
    m.box("mattress", (-13, Y0, -14), (13, MY - 1, 35), mp)
    m.box("mattress", (-12, MY - 1, -13), (12, MY, 34), mp)
    for s in (-1, 1):
        plank(m, "mattress_edge", "z", (s * 12, MY), (s * 13, MY - 1), (-13, 34), 0.7, mp, decal=_mattress_decal)
    plank(m, "mattress_edge", "x", (-14, MY - 1), (-13, MY), (-12, 12), 0.7, mp, decal=_mattress_decal)
    plank(m, "mattress_edge", "x", (34, MY), (35, MY - 1), (-12, 12), 0.7, mp, decal=_mattress_decal)
    # the rolled duvet across the back: pink with white dots, a spiral on its ends
    dcy, dcz = MY + 4.5, 24.75

    def duvet(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            r = math.hypot(y - dcy, z - dcz)
            th = math.atan2(y - dcy, z - dcz) / (2 * math.pi)
            return tone("rf_duvet", 0.25) if (r - th * 1.6) % 1.6 < 0.55 else tone("rf_rind_w", 0.9)
        if (math.floor(x) % 3 == 0) and (math.floor(z + y) % 3 == 0):
            return tone("rf_rind_w", 1.0)
        return tone("rf_duvet", 0.8 if c.face == "up" else 0.55)
    cyl(m, "duvet", (dcy, dcz), 9, -11.5, 11.5, Paint("rf_duvet", 2, noise=0, grad=0, ao=0.4, decal=duvet), axis="x")
    # the pillow at the front
    def pillow(c):
        x, y, z = c.p
        if c.face == "up" and abs(x) < 4 and -9 < z < -3:
            return tone("rf_rind_w", 0.55)                              # the dent
        if c.face != "up" and abs(y - (MY + 1.5)) < 0.3:
            return tone("rf_ticking", 0.3)                              # the piping
        return tone("rf_rind_w", 0.95 if c.face == "up" else 0.7)
    pp = Paint("rf_rind_w", 2, noise=0, grad=0, rim=0.3, ao=0.4, decal=pillow)
    _chamfer_block(m, "pillow", 8, MY, MY + 3.5, -11, -1, 1.5, pp, pillow)
    # rope over the pillow and over the duvet, down to the bars
    _tie(m, -6.75, [(0, MY + 3.5), (6.5, MY + 3.5), (8, MY + 2), (8, MY), (12, MY), (13, MY - 1), (13, Y0)],
         w=1, paint=ROPE, t=0.5, buckle=False)
    _tie(m, 24.25, [(0, MY + 9), (11.5, MY + 9), (11.5, MY), (12, MY), (13, MY - 1), (13, Y0)],
         w=1, paint=ROPE, t=0.5, buckle=False)
    return m


# ================================================================================================
# 크리스마스 트리
# ================================================================================================

# The tree lies on the rack like a cone on its side: every tier's underside rests on the bars (Y0), so its axis
# falls a little towards the tip. TCY is the back tier's axis; tier_cy(z) the axis of the tier at z.
TCY = Y0 + 7.5
TIERS = [(7.5, 18, 35), (6.25, 6, 21), (5, -5, 9), (3.5, -13, -2)]     # half size, z0, z1 (back to front)
BULBS = ("rf_bulb_r", "rf_bulb_y", "rf_bulb_b", "rf_bulb_g")
TREE_BARS = (-11.5, 0, 12, 28)                                          # a bar under each tier
TREE_STRAPS = (-11.5, 12, 28)


def tier_cy(z):
    for h, z0, z1 in TIERS:
        if z0 - 0.3 <= z <= z1 + 0.3:
            return Y0 + h
    return Y0 + 1.5 if z < 0 else TCY


def _pine_decal(c):
    x, y, z = c.p
    if c.face == "down":
        return tone("rf_pine", 0.1)
    cy = tier_cy(z)
    th = math.atan2(x, y - cy) / (2 * math.pi)
    if abs(((th - z / 9) % 1) - 0.5) > 0.47:
        return tone("rf_eye", 0.3)                                      # the light string's wire
    k = _h(math.floor(x), math.floor(y), math.floor(z)) % 5
    if c.face == "north":
        return tone("rf_pine", 0.2 if k else 0.45)                     # a tier's shaded skirt
    zz = (z - 0.6 * (abs(x) if c.face == "up" else abs(y - cy))) % 3
    base = 0.75 if c.face == "up" else 0.55
    return tone("rf_pine", base + (0.2 if zz < 1 else -0.15) + (0.15 if k == 0 else 0))


def tree():
    m = _model("sushi_carnival_roof_r_tree")
    _bars(m, TREE_BARS)
    pp = Paint("rf_pine", 3, noise=0, grad=0, rim=0.3, ao=0.4, decal=_pine_decal)
    n = 0
    for i, (h, z0, z1) in enumerate(TIERS):
        c = round(h * 0.4 * 2) / 2
        cy = Y0 + h
        y0, y1 = Y0 + 0.02 * i, Y0 + 2 * h          # (overlapping tiers' undersides a hair apart: no flicker)
        m.box("pine", (-h, y0 + c, z0), (h, y1 - c, z1), pp)
        m.box("pine", (-(h - c), y0, z0 - 0.25), (h - c, y1, z1 + 0.25), pp)
        for s in (-1, 1):
            plank(m, "pine_edge", "z", (s * (h - c), y1), (s * h, y1 - c), (z0, z1), c * 0.72, pp, decal=_pine_decal)
            plank(m, "pine_edge", "z", (s * h, y0 + c), (s * (h - c), y0), (z0, z1), c * 0.72, pp, decal=_pine_decal,
                  below=False)
        # bulbs along the spiral, on the top and the sides
        z = z0 + 1.5
        while z < z1 - 1:
            if any(zb - 1 < z < zb + 2.5 for zb in TREE_STRAPS):
                z += 2.25
                continue
            th = 2 * math.pi * (z / 9)
            sx, cy_ = math.sin(th), math.cos(th)
            col = Paint(BULBS[n % 4], 1, noise=0, grad=0, light=False, glow=True,
                        decal=lambda c, r=BULBS[n % 4]: tone(r, 1.0 if c.face == "up" else 0.7))
            if cy_ > 0.55:
                bx = max(-(h - c - 0.75), min(h - c - 0.75, h * sx))
                m.box("bulb", (bx - 0.6, y1 - 0.25, z - 0.6), (bx + 0.6, y1 + 0.75, z + 0.6), col)
                n += 1
            elif abs(sx) > 0.7 and cy_ > -0.5:
                by = max(y0 + c + 0.75, min(y1 - c - 0.75, cy + h * cy_))
                s = 1 if sx > 0 else -1
                lo, hi = sorted((s * (h - 0.25), s * (h + 0.9)))
                m.box("bulb", (lo, by - 0.6, z - 0.6), (hi, by + 0.6, z + 0.6), col)
                n += 1
            z += 2.25
    m.box("pine_tip", (-1.5, Y0, -16.5), (1.5, Y0 + 3, -13), pp)
    trunk = Paint("rf_wood", 1, kind="grain", axis="z", noise=0.1,
                  decal=lambda c: tone("rf_wood", 0.8 if (math.hypot(c.p[0], c.p[1] - TCY) % 1) < 0.5 else 0.5) if c.face == "south" else None)
    m.box("trunk", (-1.5, TCY - 1.5, 35), (1.5, TCY + 1.5, 41.5), trunk)
    for zb in TREE_STRAPS:
        h = max(t[0] for t in TIERS if t[1] <= zb and zb + 1.5 <= t[2])
        c = round(h * 0.4 * 2) / 2
        _tie(m, zb, [(0, Y0 + 2 * h), (h - c, Y0 + 2 * h), (h, Y0 + 2 * h - c), (h, Y0)])
    return m


ALL = {
    "sushi_carnival_roof_r_salmon": salmon,
    "sushi_carnival_roof_r_tamago": tamago,
    "sushi_carnival_roof_r_shrimp": shrimp,
    "sushi_carnival_roof_r_kimbap": kimbap,
    "sushi_carnival_roof_r_bungeoppang": bungeoppang,
    "sushi_carnival_roof_r_duck": duck,
    "sushi_carnival_roof_r_cat": cat,
    "sushi_carnival_roof_r_watermelon": watermelon,
    "sushi_carnival_roof_r_mattress": mattress,
    "sushi_carnival_roof_r_tree": tree,
}
