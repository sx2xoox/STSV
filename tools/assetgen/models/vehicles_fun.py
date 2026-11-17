"""
특별 탈것 (content/stsv/items/vehicles_fun.yml): ten fun vehicles, all original designs (no brand). Each is its own body
model (and wheel / part), so any of them can be deleted on its own:

  kickboard         킥보드: an aluminium kick scooter with a folding stem, a lightning bolt in the grip tape, a rear brake
                    fender, a bell and a lamp; the rider stands on it (a puppet); wheel_kickboard (polyurethane wheels)
  pogo_stick        스카이콩콩: an orange pogo stick, its spring showing between the tube and the foot bracket, rubber pegs
                    and foam grips, a fat rubber tip
  skateboard        보드: a popsicle deck with kicktail and nose, black grip tape, wood-ply edges, a flame graphic under it,
                    two trucks; wheel_skate (cream urethane wheels)
  broom             빗자루: a witch's broom: a knotted dark handle with a brass cap and a hanging moon charm, a velvet
                    cushion and a brass foot bar, a flared straw bundle bound with twine
  banana_boat       바나나보트: a big inflatable banana tube (curved up at both ends, its stem at the bow, brown speckles),
                    two blue side tubes, black grab handles for each of three riders, a tow ring
  segway            세그웨이: a two-wheel self-balancing scooter: the platform with its rubber mat, white fenders, the stem
                    leaning up to its steering bar, an LED dash with a smiling face; the rider stands on it; wheel_segway
  toy_duck          장난감 오리: a huge yellow rubber duck: round body, raised tail, wing bumps, a round head with shiny eyes,
                    pink cheeks and an orange beak
  sled              썰매: a wooden sled for two: steel-shod runners curled up at the front, red side rails with gold
                    scrolls, slatted deck, a pull rope and two little bells
  balloon_chair     풍선 의자: a wicker basket chair with a red cushion, hanging on four ropes from a hoop;
                    balloon_chair_balloons (the part: a bunch of seven balloons on their strings, shown at scale 2)
  rodeo_bull        로데오 황소: the stand: a round padded mat with its trim and a steel pedestal;
                    rodeo_bull_bull (the part: the mechanical bull, nodded and turned by the plugin, shown at scale 2)

The same rules as models/vehicles.py (read its doc first): bodies drawn at their real size (16 px a block) and halved
(BModel scale 2, vehicle.body-scale 2), the vehicle's ground centre at (0, 0, 0), front = north (-z), +x = the vehicle's
RIGHT side, wheels with their hub at model (8, 8, 8), the light from the front-left. Round parts (the duck, the bull,
balloons, the banana) are stacked boxes shaded by a decal from their centre (blob), not by the boxes' own faces, so
they read round with few boxes (렌더 예산: 600 faces a body, 240 a part).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3
from stsv_assets import presets
from models.vehicles import CLEAR, TIRE, lerp, _wheel
from models.vehicles_road import raw, rod, tube

# the light: from above, the front (-z) and the vehicle's left as seen from the front (+x)
LIGHT = (0.42, 0.78, -0.46)


def _norm(v):
    n = math.sqrt(sum(a * a for a in v)) or 1.0
    return tuple(a / n for a in v)


L = _norm(LIGHT)


def lit(c, centre, radii):
    """How much a point on a round part faces the light (0 .. 1), from its offset to the part's centre."""
    n = _norm(tuple((c.p[i] - centre[i]) / radii[i] for i in range(3)))
    return max(0.0, min(1.0, (n[0] * L[0] + n[1] * L[1] + n[2] * L[2] + 0.35) / 1.35))


def round_paint(ramp, centre, radii, lo=1.0, hi=3.4, decal=None, rim=0.15):
    """A paint that shades a stacked-box round part as one round thing (decal may paint over it: return None to shade)."""
    def d(c):
        if decal:
            got = decal(c)
            if got is not None:
                return got
        return (ramp, lo + (hi - lo) * lit(c, centre, radii))
    return Paint(ramp, 2, noise=0, rim=rim, ao=0.4, grad=0, decal=d)


def blob(m, name, centre, radii, paint, layer=2.0, row=2.0, min_w=0.5):
    """An ellipsoid of stacked boxes: horizontal layers `layer` px thick, each cut in rows `row` px deep (rows of the
    same width merged). Coordinates snap to half pixels."""
    cx, cy, cz = centre
    rx, ry, rz = radii
    y = cy - ry
    while y < cy + ry - 1e-6:
        y1 = min(cy + ry, y + layer)
        ym = (y + y1) / 2
        k = math.sqrt(max(0.0, 1 - ((ym - cy) / ry) ** 2))
        if k > 0.05:
            rzk = rz * k
            rows = []
            z = cz - rzk
            n = max(1, round(2 * rzk / row))
            step = 2 * rzk / n
            for i in range(n):
                za, zb = cz - rzk + i * step, cz - rzk + (i + 1) * step
                zm = (za + zb) / 2
                w = rx * k * math.sqrt(max(0.0, 1 - ((zm - cz) / rzk) ** 2))
                w = max(min_w, round(w * 2) / 2)
                if rows and rows[-1][2] == w:
                    rows[-1] = (rows[-1][0], zb, w)
                else:
                    rows.append((za, zb, w))
            for za, zb, w in rows:
                za, zb = round(za * 2) / 2, round(zb * 2) / 2
                if zb - za < 0.5:
                    continue
                m.box(name, (cx - w, round(y * 2) / 2, za), (cx + w, round(y1 * 2) / 2, zb), paint)
        y = y1


# ------------------------------------------------------------------------------------------------
# Smooth round forms (the duck, the bull): lathes of three-box rings, shaded by a dithered gradient
# ------------------------------------------------------------------------------------------------

BAYER = ((0.0, 0.5), (0.75, 0.25))


def _mix(a, b, k):
    return tuple(int(round(a[i] + (b[i] - a[i]) * k)) for i in range(3))


def smooth_colour(stops, k, c, levels=16, gloss=None):
    """An exact colour for k (0 dark .. 1 lit) along the colour stops [(at, rgb), ...]: a few levels, ordered-dithered
    on the pixel grid between them (no hard bands), and a glossy highlight over gloss (rubber shines)."""
    x, y, z = c.p
    b = BAYER[math.floor(y * 1.0001) % 2][math.floor(x + z) % 2]
    q = min(1.0, math.floor(k * levels + b) / levels)
    if gloss is not None and k > gloss and c.face == "up":
        return ("raw", (255, 252, 236, 255))
    for (a0, c0), (a1, c1) in zip(stops, stops[1:]):
        if q <= a1:
            t = 0 if a1 == a0 else (q - a0) / (a1 - a0)
            return ("raw", (*_mix(c0, c1, t), 255))
    return ("raw", (*stops[-1][1], 255))


def ramp_stops(ramp, lo, hi):
    """Colour stops along a palette ramp from step lo to step hi (whole steps, its own colours)."""
    from stsv_assets.palette import ladder
    steps = list(range(lo, hi + 1))
    return [((s - lo) / max(1, hi - lo), ladder(ramp, s)[:3]) for s in steps]


def lathe(m, name, z0, z1, step, shape, paint, axis="z"):
    """A round form turned along z (or y) from three overlapping components (a wide, a tall and a middle box: an
    octagon across) whose centre and half sizes come from shape(t) -> (cx, cy, rx, ry). Each component is sampled every
    half pixel, its sizes rounded to half pixels, and merged along the axis on its own: its steps fall where its own
    size changes, not where the others' do, so the form shows no ribs. (step: the sampling, kept for the callers.)
    axis "y": stacked upwards, shape(t) -> (cx, cz, rx, rz)."""
    fine = 0.5
    n = max(1, int(round((z1 - z0) / fine)))
    for k, (fw, fh) in enumerate(((1.0, 0.58), (0.58, 1.0), (0.86, 0.86))):
        runs = []
        for i in range(n):
            t0, t1 = z0 + i * fine, z0 + (i + 1) * fine
            a, b, rx, ry = shape((t0 + t1) / 2)
            w, h = round(rx * fw * 2) / 2, round(ry * fh * 2) / 2
            key = (round(a * 4) / 4, round(b * 4) / 4, w, h)
            if w < 0.5 or h < 0.5:
                runs.append(None)
                continue
            if runs and runs[-1] is not None and runs[-1][2] == key:
                runs[-1] = (runs[-1][0], t1, key)
            else:
                runs.append((t0, t1, key))
        ins = 0.03 * k   # the components' ends a hair apart: no two faces in one plane
        for r in runs:
            if r is None:
                continue
            t0, t1, (a, b, w, h) = r
            if axis == "z":
                m.box(name, (a - w, b - h, t0 + ins), (a + w, b + h, t1 - ins), paint)
            else:
                m.box(name, (a - w, t0 + ins, b - h), (a + w, t1 - ins, b + h), paint)


def octa(m, name, t0, t1, a, b, hw, hh, bevel, paint, axis="z"):
    """An octagonal section with true 45 degree bevels, from t0 to t1 along z (or y): a wide box, a tall box and four
    boxes turned 45 degrees filling the corners between them. Across: centred at (a, b), hw / hh half wide / high (x and
    y; along y: x and z), the corners cut back by bevel. Few, large faces: shaded by a smooth paint they read round."""
    bevel = min(bevel, hw - 0.25, hh - 0.25)
    d = bevel / math.sqrt(2)
    parts = [(hw, hh - bevel, None), (hw - bevel, hh, None)]
    for sx in (1, -1):
        for sy in (1, -1):
            parts.append(((sx, sy), d, "diamond"))
    for k, part in enumerate(parts):
        i = 0.02 * k
        if part[2] is None:
            w, h = part[0], part[1]
            if axis == "z":
                m.box(name, (a - w, b - h, t0 + i), (a + w, b + h, t1 - i), paint)
            else:
                m.box(name, (a - w, t0 + i, b - h), (a + w, t1 - i, b + h), paint)
        else:
            (sx, sy), dd, _ = part
            ca, cb = a + sx * (hw - bevel), b + sy * (hh - bevel)
            if axis == "z":
                m.box(name, (ca - dd, cb - dd, t0 + i), (ca + dd, cb + dd, t1 - i), paint, rotation=("z", 45, (ca, cb, (t0 + t1) / 2)))
            else:
                m.box(name, (ca - dd, t0 + i, cb - dd), (ca + dd, t1 - i, cb + dd), paint, rotation=("y", 45, (ca, (t0 + t1) / 2, cb)))


def lit_at(c, centre, radii):
    """lit(), for a point and a centre / radii given directly."""
    n = _norm(tuple((c.p[i] - centre[i]) / radii[i] for i in range(3)))
    return max(0.0, min(1.0, (n[0] * L[0] + n[1] * L[1] + n[2] * L[2] + 0.35) / 1.35))


def octo(m, name, c, w, h, z0, z1, paint, axis="z"):
    """A rounded bar of three boxes (an octagon across): w wide, h high, along z (or x) from z0 to z1, centred at c = (x, y)."""
    x, y = c
    # the three ends a quarter pixel apart (ends in one plane would flicker where they overlap)
    for k, (a, b) in enumerate(((w / 2, h / 2 * 0.55), (w / 2 * 0.55, h / 2), (w / 2 * 0.82, h / 2 * 0.82))):
        i = 0.25 * k
        if axis == "z":
            m.box(name, (x - a, y - b, z0 + i), (x + a, y + b, z1 - i), paint)
        else:
            m.box(name, (z0 + i, y - b, x - a), (z1 - i, y + b, x + a), paint)


def _body(name, scale=2.0):
    m = BModel(name, shift=(8, 8, 8), scale=scale)
    return m


def _held(m, gui=0.4):
    m.display = presets.furniture(gui_scale=gui)
    return m


# ================================================================================================
# 킥보드 (kick scooter, ridden standing) 1.25 x 0.5 x 1.2 blocks
# ================================================================================================

KICK = "spectral"          # the accent colour (stem collar, deck stripe, the fold joint)


def wheel_kickboard():
    """A 5 px polyurethane wheel: a teal tyre round a silver five-spoke hub."""
    m = BModel("wheel_kickboard", shift=(8, 8, 8), floor=None)
    r = 2.5

    def tyre(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr < 1.2:
                return ("blade", 3 if y - z > 0 else 2)
            return (KICK, 2.6 if y - 0.5 * z > 0 else 1.6)
        return (KICK, 2.2 if math.floor(math.atan2(z, y) * 3) % 2 else 1.6)
    cyl(m, "tyre", (0, 0), 5, -0.75, 0.75, Paint(KICK, 2, noise=0, rim=0.3, decal=tyre), axis="x")
    m.box("hub", (-1.25, -0.75, -0.75), (1.25, 0.75, 0.75), tube("blade", 3.4, 2))
    m.display = presets.furniture(gui_scale=0.6)
    return m


def kickboard():
    m = _body("kickboard")
    FZ, RZ, WY = -8.5, 9.0, 2.5           # wheel hubs (vehicles_fun.yml wheels)

    # --- the deck: a flat aluminium plank, black grip tape on top with a teal stripe down each side
    def deck(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > 2.0:
                return (KICK, 2.8)
            # a teal lightning bolt cut out of the grip tape
            zz = z + 0.5
            if abs(zz) < 4.5 and abs(x - (0.5 * zz if zz < 0 else 0.5 * zz - 1.5)) < 0.6 - 0.0 * abs(zz):
                return (KICK, 3.2)
            if abs(z + 5.5) < 0.6 or abs(z - 5.5) < 0.6:
                return ("rubber", 2.6)                  # grip tape seams
            return ("rubber", 1.6 + (0.6 if h3(math.floor(x * 2), 0, math.floor(z * 2), 3) < 0.2 else 0))
        if c.face == "down":
            return ("blade", 1)
        if c.face in ("east", "west"):
            return ("blade", 3.2 if y > 3.4 else 2.2) if not (-1 < z < 1) else (KICK, 2.6)
        return ("blade", 2.6)
    m.box("deck", (-2.5, 2.5, -6.5), (2.5, 4.0, 6.5), Paint("blade", 2, noise=0, rim=0.5, decal=deck))
    # the deck's rounded nose up to the fold joint, the tail up to the brake
    m.box("deck_nose", (-2.0, 2.75, -7.5), (2.0, 4.0, -6.5), Paint("blade", 2, noise=0, rim=0.4))
    m.box("deck_tail", (-2.0, 2.75, 6.5), (2.0, 4.0, 7.5), Paint("blade", 2, noise=0, rim=0.4))

    # --- the fold joint and the fork round the front wheel
    def joint(c):
        if c.face in ("east", "west") and abs(c.p[1] - 5.5) < 0.6:
            return ("blade", 3.4)                       # the clamp lever
        return (KICK, 2.4 if c.face in ("up", "north", "west") else 1.6)
    m.box("fold_joint", (-1.5, 3.0, -9.5), (1.5, 6.5, -7.0), Paint(KICK, 2, noise=0, rim=0.5, decal=joint))
    for s in (1, -1):
        xa, xb = sorted((s * 1.0, s * 1.75))
        m.box("fork", (xa, WY - 0.5, FZ - 0.75), (xb, 6.0, FZ + 0.75), tube("blade", 3.4, 2))
    m.box("front_guard", (-1.25, 5.0, FZ - 2.5), (1.25, 5.75, FZ + 1.25), Paint("rubber", 1, noise=0, rim=0.3))

    # --- the stem: a polished tube, a teal height clamp, a small front lamp
    def stem(c):
        x, y, z = c.p
        if c.face == "north" and 15.5 < y < 17 and abs(x) < 0.6:
            return ("raw", (255, 250, 228, 255))       # the lamp's lens
        if abs(y - 12.5) < 0.75:
            return (KICK, 3 if c.face in ("north", "west", "up") else 2)   # the clamp collar
        if c.face in ("north", "west"):
            return ("blade", 3.6)
        if c.face in ("south", "east"):
            return ("blade", 2.0)
        return ("blade", 3)
    m.box("stem", (-0.75, 6.0, -9.0), (0.75, 21.0, -7.5), Paint("blade", 2, noise=0, rim=0.4, decal=stem))
    m.box("collar", (-1.0, 11.75, -9.25), (1.0, 13.25, -7.25), Paint(KICK, 2, noise=0, rim=0.4))
    m.box("lamp", (-0.75, 15.25, -9.75), (0.75, 17.25, -9.0), Paint("rubber", 1, noise=0,
                                                                 decal=lambda c: ("raw", (255, 246, 214, 255)) if c.face == "north" else None))

    # --- the T bar: a straight bar, black foam grips with lit ridges, a brass bell on the left and a brake lever
    m.box("bar", (-6.5, 20.75, -9.0), (6.5, 21.75, -8.0), tube("blade", 3.4, 2))
    m.box("bar_clamp", (-1.25, 20.25, -9.25), (1.25, 22.0, -7.75), Paint(KICK, 2, noise=0, rim=0.4))

    def grip(c):
        x = c.p[0]
        return ("rubber", 2.6 if math.floor(x * 2) % 3 == 0 else 1.4)
    for s in (1, -1):
        xa, xb = sorted((s * 4.25, s * 7.25))
        m.box("grip", (xa, 20.5, -9.25), (xb, 22.0, -7.75), Paint("rubber", 1, noise=0, rim=0.4, decal=grip))
        xc, xd = sorted((s * 7.25, s * 7.75))
        m.box("bar_end", (xc, 20.5, -9.25), (xd, 22.0, -7.75), Paint(KICK, 2, noise=0))
    m.box("bell", (2.5, 21.75, -9.25), (3.75, 22.75, -7.75),
          Paint("brass", 2, noise=0, rim=0.6, decal=lambda c: ("brass", 3.6) if c.face == "up" else ("brass", 2.4 if c.face in ("north", "west") else 1.6)))
    rod(m, "brake_lever", (21.0, -7.75), (20.0, -5.5), 0.5, tube("rubber", 2.4, 1), x=(-6.5, -4.5))

    # --- the rear brake fender over the back wheel (a stamp on it brakes)
    def fender(c):
        if c.face == "south" and abs(c.p[0]) < 1:
            return ("raw", (220, 40, 46, 255))          # the rear reflector
        return ("blade", 3.2 if c.face == "up" else 2)
    rod(m, "fender", (5.25, 7.0), (5.25, 11.0), 3.5, Paint("blade", 2, noise=0, rim=0.5, decal=fender), depth=0.75)
    rod(m, "fender_tip", (5.25, 11.0), (3.5, 12.25), 3.5, Paint("blade", 2, noise=0, rim=0.5, decal=fender), depth=0.75)
    m.box("tail_guard", (-1.25, 3.0, 7.5), (1.25, 4.25, 9.5), Paint("rubber", 1, noise=0))

    # --- the kickstand folded along the left of the deck
    rod(m, "kickstand", (3.0, 1.0), (1.0, 4.0), 0.75, tube("rubber", 2.4, 1), x=(2.5, 3.25))
    return _held(m)


# ================================================================================================
# 스카이콩콩 (pogo stick) 0.6 x 0.6 x 1.4 blocks
# ================================================================================================

POGO = "fox"


def pogo_stick():
    m = _body("pogo_stick")

    # --- the fat rubber tip, the plunger rod, the spring between the foot bracket and the tube
    def tip(c):
        y = c.p[1]
        if c.face == "down":
            return ("rubber", 0)
        return ("rubber", 2.2 if math.floor(y * 2) % 2 else 1.2) if y < 2.5 else ("rubber", 1.6)
    cyl(m, "tip", (0, 0), 4, 0, 3, Paint("rubber", 1, noise=0, rim=0.4, decal=tip))
    cyl(m, "plunger", (0, 0), 2, 3, 8, tube("blade", 3.6, 2))

    def spring(c):
        y = c.p[1]
        k = (y * 1.5) % 1.0
        return ("blade", 3.6 if k < 0.35 else 2.4 if k < 0.7 else 1.4)
    cyl(m, "spring", (0, 0), 4, 8.5, 14.0, Paint("blade", 2, noise=0, rim=0, ao=0.2, decal=spring))

    # --- the foot bracket: a steel collar with two pegs, rubber treads on top
    def bracket(c):
        return ("steel", 3.4 if c.face in ("up", "north", "west") else 2.2)
    cyl(m, "bracket", (0, 0), 5, 7.0, 9.0, Paint("steel", 2, noise=0, rim=0.4, decal=bracket))

    def peg(c):
        x, y, z = c.p
        if c.face == "up":
            return ("rubber", 2.4 if math.floor(x * 2) % 2 else 1.2)   # the tread
        if c.face in ("east", "west"):
            return ("rubber", 1.0)
        return ("rubber", 1.6)
    for s in (1, -1):
        xa, xb = sorted((s * 2.5, s * 6.5))
        m.box("peg", (xa, 7.0, -1.25), (xb, 8.5, 1.25), Paint("rubber", 1, noise=0, rim=0.5, decal=peg))
        xc, xd = sorted((s * 2.25, s * 3.25))
        m.box("peg_arm", (xc, 6.5, -0.75), (xd, 9.25, 0.75), Paint("steel", 2, noise=0, rim=0.4))

    # --- the orange tube: glossy, a white stripe and stars, a black cap where the spring goes in
    def body(c):
        x, y, z = c.p
        if 18.5 < y < 19.25:
            return ("car_white", 3.4)                   # the white stripe
        if c.face == "north" and 15.5 < y < 18.4:
            # a cheeky face sticker on the front: round eyes, a big grin with a tongue
            if abs(abs(x) - 0.75) < 0.4 and 17.2 < y < 18.2:
                return ("raw", (255, 255, 255, 255)) if y > 17.7 else ("raw", (20, 20, 26, 255))
            if 15.75 < y < 16.6 and abs(x) < 1.3:
                return ("raw", (230, 70, 90, 255)) if (y < 16.1 and abs(x) < 0.5) else ("raw", (40, 14, 18, 255))
        if c.face in ("south", "east", "west") and 16 < y < 18:
            a = math.atan2(x, z)
            if math.floor(a * 3 + y) % 4 == 0:
                return ("petal_yellow", 3)               # little stars round it
        if c.face in ("north", "west"):
            return (POGO, lerp(y, 14, 26, 2.6, 3.6))
        if c.face in ("south", "east"):
            return (POGO, lerp(y, 14, 26, 1.6, 2.4))
        return (POGO, 3)
    cyl(m, "tube", (0, 0), 4, 15, 21, Paint(POGO, 2, noise=0, rim=0.3, decal=body))
    cyl(m, "tube_cap", (0, 0), 5, 14, 15.5, Paint("rubber", 1, noise=0, rim=0.4))
    cyl(m, "top_cap", (0, 0), 3, 21, 23, Paint("rubber", 1, noise=0, rim=0.4))
    m.box("top_bob", (-0.75, 23.0, -0.75), (0.75, 24.25, 0.75), Paint(POGO, 3, noise=0, rim=0.5))

    # --- the T handlebar (at the standing rider's hands) with foam grips and end caps
    m.box("bar", (-7, 19.75, -0.6), (7, 20.85, 0.6), tube("blade", 3.6, 2))

    def foam(c):
        x = c.p[0]
        return ("rubber", 2.4 if math.floor(x * 1.5) % 2 else 1.6)
    for s in (1, -1):
        xa, xb = sorted((s * 3.0, s * 7.0))
        m.box("grip", (xa, 19.25, -1.1), (xb, 21.35, 1.1), Paint("rubber", 1, noise=0, rim=0.5, decal=foam))
        xc, xd = sorted((s * 7.0, s * 7.75))
        m.box("bar_end", (xc, 19.25, -1.1), (xd, 21.35, 1.1), Paint(POGO, 2, noise=0, rim=0.4))
    return _held(m, 0.5)


# ================================================================================================
# 보드 (skateboard) 1.0 x 0.45 x 0.3 blocks
# ================================================================================================

def wheel_skate():
    """A 3 px cream urethane wheel with a grey bearing shield."""
    m = BModel("wheel_skate", shift=(8, 8, 8), floor=None)

    def w(c):
        y, z = c.p[1], c.p[2]
        if c.face in ("east", "west"):
            if math.hypot(y, z) < 0.8:
                return ("steel", 3)
            return ("cream", 3.4 if y - 0.5 * z > 0 else 2.4)
        return ("cream", 2.6)
    cyl(m, "wheel", (0, 0), 3, -0.75, 0.75, Paint("cream", 2, noise=0, rim=0.3, decal=w), axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


def skateboard():
    m = _body("skateboard")
    DY0, DY1 = 3.0, 3.75               # the deck's bottom and top
    WZ = 5.0                           # the trucks' axles (vehicles_fun.yml wheels: forward +-0.3125)

    def deck(c):
        x, y, z = c.p
        if c.face == "up":
            # black grip tape (fine grit), two thin cut lines across near the trucks
            if abs(abs(z) - WZ) < 0.3:
                return ("rubber", 2.4)
            # cut-outs in the grip showing the deck's flames under it: two slashes by the nose, a star by the tail
            if -6.5 < z < -2.0 and (abs(x - (z + 4.5) * 0.8) < 0.45 or abs(x - (z + 2.5) * 0.8) < 0.45):
                return ("fire", 4)
            if math.hypot(x, z - 3.5) < 1.4 and math.cos(5 * math.atan2(z - 3.5, x)) > -0.2:
                return ("petal_yellow", 2)
            return ("rubber", 1.4 + (0.7 if h3(math.floor(x * 2), 1, math.floor(z * 2), 7) < 0.18 else 0))
        if c.face == "down":
            # the graphic: flames up a red deck, a yellow star in the middle
            r = math.hypot(x * 1.4, z)
            if r < 2.2:
                return ("petal_yellow", 3)
            flame = abs(z) + 1.5 * math.sin(x * 1.7 + z * 0.6)
            return ("fire", 4.4 if flame > 6.5 else 3.4) if flame > 4.5 else ("pepper", 2.4)
        # the edges: seven-ply maple, its layers showing
        k = math.floor((y - DY0) * 4)
        return ("wood", 3.4 if k % 2 else 2.4) if k != 2 else ("pepper", 2.6)
    p = Paint("wood", 2, noise=0, rim=0.3, decal=deck)
    m.box("deck", (-3.5, DY0, -7.0), (3.5, DY1, 7.0), p)
    # kicktail and nose, turned up 22.5°
    m.box("tail", (-3.25, DY0, 7.0), (3.25, DY1, 10.0), p, rotation=("x", -22.5, (0, DY0, 7.0)))
    m.box("nose", (-3.25, DY0, -10.0), (3.25, DY1, -7.0), p, rotation=("x", 22.5, (0, DY0, -7.0)))

    # the trucks: a base plate with its bolts, the kingpin and bushing, the hanger and axle across
    def truck(c):
        x, y, z = c.p
        if c.face == "up" and y > DY0 - 0.6 and (abs(x) > 0.9) and abs(abs(z) - WZ) > 0.6:
            return ("steel", 4)                         # bolt heads
        return ("blade", 3.2 if c.face in ("up", "north", "west") else 2.0)
    tp = Paint("blade", 2, noise=0, rim=0.4, decal=truck)
    for s in (1, -1):
        z = s * WZ
        m.box("baseplate", (-1.5, DY0 - 0.75, z - 1.5), (1.5, DY0, z + 1.5), tp)
        m.box("bushing", (-0.75, 2.0, z - 0.75 + s * 0.5), (0.75, 2.25, z + 0.75 + s * 0.5), Paint("fox", 3, noise=0))
        m.box("hanger", (-2.0, 1.0, z - 0.75), (2.0, 2.25, z + 0.75), tp)
        m.box("axle", (-3.5, 1.25, z - 0.25), (3.5, 1.75, z + 0.25), tube("steel", 4, 2.5))
    return _held(m, 0.5)


# ================================================================================================
# 빗자루 (witch's broom) 2.1 x 0.6 x 0.6 blocks
# ================================================================================================

def broom():
    m = _body("broom")
    HY = 5.6                            # the handle's middle height (the rider's seat: 0.35 blocks)

    # --- the handle: dark knotted wood, a leather wrap where the hands go, a brass cap and a moon charm at the front
    def handle(c):
        x, y, z = c.p
        if -12.5 < z < -8.5:
            return ("leather", 2.6 if math.floor(z * 1.5 + y) % 2 else 1.6)    # the leather wrap
        if h3(0, 0, math.floor(z), 21) < 0.12:
            return ("bark", 1)                          # knots
        if c.face == "up":
            return ("wood_dark", 3.2)
        if c.face == "down":
            return ("wood_dark", 1.2)
        return ("wood_dark", 2.4 if c.face in ("west", "north") else 1.8)
    hp = Paint("wood_dark", 2, kind="grain", axis="z", noise=0.02, rim=0.4, decal=handle)
    m.box("handle", (-0.75, HY - 0.75, -17.5), (0.75, HY + 0.75, 9.0), hp)
    # it swells a little where the straw is bound on, and at the knob
    m.box("handle_knob", (-1.0, HY - 1.0, -19.0), (1.0, HY + 1.0, -17.5),
          Paint("brass", 2, noise=0, rim=0.6, decal=lambda c: ("brass", 3.6 if c.face in ("up", "north", "west") else 2.2)))
    rod(m, "charm_string", (HY - 1.0, -18.25), (HY - 3.5, -18.25), 0.5, Paint("straw", 2, noise=0))

    def moon(c):
        x, y, z = c.p
        # a crescent: a disc with a bite out of it
        r1 = math.hypot(x, y - (HY - 5.0))
        r2 = math.hypot(x - 0.9, y - (HY - 4.6))
        if r1 < 1.6 and r2 > 1.2:
            return ("gold", 3.6 if c.face in ("north", "up") else 2.6)
        return ("raw", CLEAR)
    m.box("charm", (-1.75, HY - 6.75, -18.5), (1.75, HY - 3.25, -18.0), Paint("gold", 2, noise=0, light=False, decal=moon))

    # --- the velvet cushion and the brass foot bar under it
    def cushion(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > 1.6 or z < -3.6 or z > 4.6:
                return ("gold", 2.6)                    # the gold piping
            return ("magic", 1.6 if (math.floor(x) + math.floor(z)) % 2 else 1.2)
        return ("magic", 0.8)
    m.box("cushion", (-2.25, HY + 0.5, -4.25), (2.25, HY + 1.75, 5.25), Paint("magic", 1, noise=0, rim=0.6, decal=cushion))
    m.box("cushion_strap", (-2.4, HY - 1.0, 0.25), (2.4, HY + 1.85, 1.0), Paint("leather", 2, noise=0, rim=0.3))
    m.box("foot_bar", (-4.5, HY - 2.75, -7.5), (4.5, HY - 2.0, -6.75), tube("brass", 3.6, 2))
    m.box("foot_bar_post", (-0.4, HY - 2.5, -7.375), (0.4, HY - 0.75, -6.875), tube("brass", 3.6, 2))

    # --- the straw bundle: flared in three tiers, bound twice with red twine, lit straw on top, ragged ends
    def straw(c, cz, cy, r):
        x, y, z = c.p
        if abs(z - 10.0) < 0.75 or abs(z - 13.75) < 0.75:
            return ("fabric_red", 2.8 if c.face in ("up", "north", "west") else 1.8)   # the twine
        if c.face == "south":
            return ("straw", 1.4 if (math.floor(x) + math.floor(y)) % 2 else 2.4)     # the cut ends
        k = math.floor(x * 1.5) if c.face in ("up", "down") else math.floor(y * 1.5)
        shade = lit(c, (0, cy, cz), (r, r, 10))
        return ("straw", 1.2 + 2.6 * shade + (0.6 if k % 3 == 0 else 0) - (0.5 if k % 5 == 1 else 0))
    sp = lambda cz, cy, r: Paint("straw", 2, noise=0, rim=0.2, ao=0.5, grad=0, decal=lambda c: straw(c, cz, cy, r))
    octo(m, "bristles", (0, HY), 4.0, 4.0, 9.0, 11.0, sp(10, HY, 2))
    octo(m, "bristles", (0, HY - 0.25), 5.5, 5.0, 11.0, 14.5, sp(13, HY - 0.25, 2.75))
    octo(m, "bristles", (0, HY - 0.5), 7.5, 6.5, 14.5, 18.5, sp(16.5, HY - 0.5, 3.5))
    octo(m, "bristles", (0, HY - 0.75), 9.5, 7.5, 18.5, 23.0, sp(21, HY - 0.75, 4.5))
    # a few loose straws sticking out of the ends
    for (x, y, dz) in ((-4.0, HY + 1.75, 1.5), (3.25, HY - 3.25, 1.0), (1.0, HY + 3.0, 1.75), (-2.5, HY - 3.5, 1.0), (4.25, HY + 0.5, 1.25)):
        m.box("loose_straw", (x, y, 23.0), (x + 0.5, y + 0.5, 23.0 + dz), Paint("straw", 3, noise=0, rim=0))
    return _held(m, 0.35)


# ================================================================================================
# 바나나보트 (inflatable banana boat) 4.4 x 1.4 x 1.1 blocks
# ================================================================================================

def banana_boat():
    m = _body("banana_boat")
    HALF = 34.0                          # half the tube's length (px)

    def centre_y(z):
        return 6.5 + 0.0085 * z * z      # curved up at both ends

    def girth(z):
        return 13.0 * math.sqrt(max(0.0, 1 - (z / (HALF + 4)) ** 2)) + 1.5

    def banana(c):
        x, y, z = c.p
        cy = centre_y(z)
        r = girth(z) / 2
        # brown ripening speckles, a darker seam along each side, the tube's valve
        if abs(abs(x) - r * 0.95) < 0.6 and c.face in ("east", "west") and abs(y - cy) < 1:
            return ("corn", 1.2)
        if h3(math.floor(x), math.floor(y), math.floor(z), 33) < 0.012 and c.face != "down" and abs(z) > 8:
            return ("doenjang", 2.4)
        # the banana's ridges: a slightly darker line where its faces meet (along the tube, either side of the top)
        if c.face == "up" and abs(abs(x) - r * 0.42) < 0.4:
            return ("petal_yellow", 0.6)
        # a goofy grin across the bow
        if z < -27.5 and c.face in ("north", "up", "east", "west") and abs(x) < 4.0 and abs(y - (cy + 1.2 + 0.12 * x * x)) < 0.45:
            return ("raw", (110, 50, 20, 255))
        n = lit(c, (0, cy, z), (r, r, 40))
        return ("petal_yellow", 0.1 + 1.45 * n)
    bp = Paint("petal_yellow", 2, noise=0, rim=0.15, ao=0.4, grad=0, decal=banana)
    z = -HALF
    seg = 6.0
    while z < HALF - 1e-6:
        z1 = min(HALF, z + seg)
        zm = (z + z1) / 2
        d = girth(zm)
        octo(m, "tube", (0, centre_y(zm)), d, d, z, z1, bp)
        z = z1
    # the stem at the bow (green going brown) and the dark tip at the stern
    def stem(c):
        return ("leaf", 2.6 if c.face in ("up", "north", "west") else 1.6) if c.p[2] > -HALF - 3.5 else ("doenjang", 2)
    sy = centre_y(-HALF)
    m.box("stem", (-1.25, sy - 1.25, -HALF - 5.0), (1.25, sy + 1.25, -HALF), Paint("leaf", 2, noise=0, rim=0.4, decal=stem),
          rotation=("x", -22.5, (0, sy, -HALF)))
    # big googly eyes up on the bow, looking ahead
    for s_ in (1, -1):
        ez = -27.0
        ey = centre_y(ez) + girth(ez) / 2 - 0.5
        xa, xb = sorted((s_ * 0.6, s_ * 3.6))
        m.box("eye", (xa, ey, ez - 1.5), (xb, ey + 3.25, ez + 0.75), Paint("car_white", 3, noise=0, light=False,
                                                                       decal=lambda c: ("raw", (250, 250, 250, 255))))
        xc, xd = sorted((s_ * 1.4, s_ * 2.9))
        m.box("pupil", (xc, ey + 0.4, ez - 1.75), (xd, ey + 1.9, ez - 1.5), Paint("rubber", 0, noise=0, light=False,
                                                                           decal=lambda c: ("raw", (16, 16, 22, 255))))
    ty = centre_y(HALF)
    m.box("tip", (-1.5, ty - 1.5, HALF), (1.5, ty + 1.5, HALF + 2.0), Paint("doenjang", 1, noise=0, rim=0.4))

    # the blue side tubes (outriggers) joined on by straps
    def side(c):
        x, y, z = c.p
        if abs(z) % 10 < 1.0 and c.face != "down":
            return ("rubber", 1.6)                      # straps round the tube
        n = lit(c, (math.copysign(8.5, x), 3.5, z), (3, 3, 30))
        return ("sapphire", 0.9 + 2.4 * n)
    sdp = Paint("sapphire", 2, noise=0, rim=0.15, ao=0.4, grad=0, decal=side)
    for s in (1, -1):
        octo(m, "side_tube", (s * 8.5, 3.5), 6, 6, -22, 24, sdp)
        m.box("side_cap", (s * 8.5 - 1.75, 1.75, -24), (s * 8.5 + 1.75, 5.25, -22), sdp)
        m.box("side_cap", (s * 8.5 - 1.75, 1.75, 24), (s * 8.5 + 1.75, 5.25, 26), sdp)
        for zz in (-12, 4, 18):
            m.box("strap", (min(s * 5.0, s * 6.0), 2.5, zz - 0.5), (max(s * 5.0, s * 6.0), 7.5, zz + 0.5), Paint("rubber", 1, noise=0))

    # a grab handle each side in front of each rider (seats at forward 1, 0, -1: z -16, 0, 16), a tow ring at the bow
    def handle(c):
        return ("rubber", 2.4 if c.face in ("up", "north", "west") else 1.2)
    hp = Paint("rubber", 1, noise=0, rim=0.5, decal=handle)
    for zs in (-16, 0, 16):
        hz = zs - 5.5
        top = centre_y(hz) + girth(hz) / 2
        for s in (1, -1):
            xa, xb = sorted((s * 3.5, s * 5.0))
            m.box("handle_post", (xa + 0.25, top - 2.0, hz - 1.35), (xb - 0.25, top + 1.5, hz - 0.6), hp)
            m.box("handle_post", (xa + 0.25, top - 2.0, hz + 0.6), (xb - 0.25, top + 1.5, hz + 1.35), hp)
            m.box("handle_bar", (xa, top + 1.0, hz - 1.6), (xb, top + 2.0, hz + 1.6), hp)
    ry = centre_y(-HALF + 2) + 1
    m.box("tow_ring", (-1.5, ry - 1.5, -HALF - 1.5), (1.5, ry + 1.5, -HALF - 1.0), tube("steel", 4, 2.5))
    # the valve cap on top near the stern
    m.box("valve", (-0.75, centre_y(26) + girth(26) / 2 - 0.25, 25.5), (0.75, centre_y(26) + girth(26) / 2 + 0.75, 27),
          Paint("car_white", 3, noise=0, rim=0.4))
    return _held(m, 0.3)


# ================================================================================================
# 세그웨이 (two-wheel self-balancing scooter, seat kit) 0.75 x 1.0 x 1.7 blocks
# ================================================================================================

SEG = "aurora"


def wheel_segway():
    """A 10 px wheel with a fat 3 px tyre and a dark grey five-spoke rim."""
    return _wheel("wheel_segway", 10, 3, cap="steel", cap_d=6, spokes=5)


def segway():
    m = _body("segway")
    WX = 7.0                             # the wheels' middles (vehicles_fun.yml: right +-0.4375)

    # --- the platform: a grey shell, a black ribbed rubber mat, blue light strips along the sides
    def shell(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 5.25 and abs(z) < 3.75:
                return ("rubber", 2.4 if math.floor(z * 1.5) % 2 else 1.4)    # the mat's ribs
            return ("steel", 3.6)
        if c.face in ("east", "west", "north", "south") and abs(y - 3.75) < 0.4:
            return ("raw", (90, 200, 255, 255))         # the light strip
        if c.face == "down":
            return ("steel", 1)
        return ("steel", lerp(y, 2, 5, 1.6, 3.0))
    m.box("platform", (-5.75, 2.0, -4.5), (5.75, 5.0, 4.5), Paint("steel", 2, noise=0, rim=0.5, decal=shell))
    # the motor hubs between platform and wheels
    for s in (1, -1):
        xa, xb = sorted((s * 5.75, s * 6.0))
        m.box("hub_cover", (xa, 3.0, -1.5), (xb, 7.0, 1.5), Paint("steel", 2, noise=0))

    # --- white fenders over the wheels (arcs of straight pieces)
    def fender(c):
        return ("car_white", 3.6 if c.face in ("up", "north", "west") else 2.4)
    fp = Paint("car_white", 2, noise=0, rim=0.5, decal=fender)
    for s in (1, -1):
        xa, xb = sorted((s * 5.25, s * 8.75))
        for (a, b) in (((5, -6), (9, -4.25)), ((9, -4.25), (11, 0)), ((11, 0), (9, 4.25)), ((9, 4.25), (5, 6))):
            rod(m, "fender", a, b, 1.0, fp, x=(xa, xb), depth=1.0)

    # --- the stem: a white tube rising from the front of the platform, the steering bar and its dash
    def stem(c):
        x, y, z = c.p
        if c.face == "north" and 8 < y < 9.5 and abs(x) < 0.8:
            return ("raw", (255, 250, 230, 255))        # the headlamp
        if c.face == "north" and abs(y - 12) < 0.4:
            return ("raw", (90, 200, 255, 255))         # a blue light ring
        if c.face in ("north", "west"):
            return ("car_white", 3.4)
        if c.face in ("south", "east"):
            return ("car_white", 2.2)
        return ("car_white", 3)
    # the stem leans forward from the platform's front up to the bar, where the standing rider's hands are
    rod(m, "stem", (5.0, -3.0), (19.0, -8.75), 2.0, Paint("car_white", 2, noise=0, rim=0.4, decal=stem))
    m.box("stem_foot", (-1.75, 4.5, -4.75), (1.75, 6.5, -1.25), Paint("steel", 2, noise=0, rim=0.4))

    def dash(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 1.9 and -10.6 < z < -7.4:
                # the LED face: two eyes and a smile (it is happy to see you)
                u, w = x, z + 9.0
                if (abs(abs(u) - 0.9) < 0.35 and -1.2 < w < -0.4) or (abs(w - (0.9 - 0.25 * u * u)) < 0.3 and abs(u) < 1.5):
                    return ("raw", (90, 235, 160, 255))
                return ("raw", (24, 40, 52, 255))
            return ("rubber", 2)
        return ("rubber", 1.4)
    m.box("dash", (-2.25, 18.75, -11.0), (2.25, 20.25, -7.0), Paint("rubber", 1, noise=0, rim=0.5, glow=True, decal=dash))
    m.box("bar", (-7.0, 19.0, -9.5), (7.0, 20.0, -8.5), tube("steel", 3.6, 2))

    def grip(c):
        return ("rubber", 2.4 if math.floor(c.p[0] * 2) % 3 == 0 else 1.4)
    for s_ in (1, -1):
        xa, xb = sorted((s_ * 4.0, s_ * 7.25))
        m.box("grip", (xa, 18.75, -9.75), (xb, 20.25, -8.25), Paint("rubber", 1, noise=0, rim=0.4, decal=grip))
    return _held(m, 0.4)


# ================================================================================================
# 장난감 오리 (giant rubber duck) 2.2 x 1.5 x 1.7 blocks
# ================================================================================================

DUCK = "corn"
# the duck's yellow: deep amber in the shade, warm yolk, a bright lemon where the light falls, a gloss on top
DUCK_STOPS = [(0.0, (196, 120, 22)), (0.35, (236, 168, 28)), (0.7, (252, 206, 52)), (1.0, (255, 236, 120))]


def toy_duck():
    """A big rubber duck, faceted like a moulded toy: octagonal sections with 45 degree bevels (few large faces) along a
    round body that sweeps up into a perky tail, a round head on a short neck, all glossy yellow shaded by one smooth
    gradient over the whole form (no bands); shiny black eyes with a glint, pink cheeks, a wide orange bill with a smile
    line, wing bumps on the sides."""
    m = _body("toy_duck")
    BC, BR = (0.0, 9.0, 2.5), (12.0, 9.5, 17.0)   # the body as one round form, for its shading

    def yellow(centre, radii, gloss=0.975, dim=1.0):
        def d(c):
            k = lit_at(c, centre, radii) * dim
            if c.face == "down":
                k *= 0.35
            return smooth_colour(DUCK_STOPS, k, c, gloss=gloss)
        return Paint(DUCK, 2, noise=0, light=False, decal=d)
    bp = yellow(BC, BR)
    # body: chest, belly, hips, rump (front to back), each a bevelled octagon
    for (t0, t1, cy, hw, hh, bev) in ((-15.0, -13.25, 8.75, 6.25, 5.0, 2.5), (-13.25, -11.0, 8.75, 8.75, 6.75, 3.5),
                                      (-11.0, -8.0, 9.0, 10.75, 8.0, 4.0), (-8.0, 8.0, 9.0, 12.0, 8.75, 4.5),
                                      (8.0, 11.5, 9.25, 11.0, 8.25, 4.25), (11.5, 14.5, 9.75, 9.5, 7.5, 3.75),
                                      (14.5, 17.0, 10.5, 7.25, 6.25, 3.0)):
        octa(m, "body", t0, t1, 0.0, cy, hw, hh, bev, bp)
    # the tail sweeping up
    tp = yellow((0, 13, 17), (6, 6, 5), gloss=0.985)
    octa(m, "tail", 16.5, 19.0, 0.0, 13.5, 5.5, 4.5, 2.0, tp)
    octa(m, "tail", 18.6, 20.5, 0.0, 17.0, 3.6, 3.25, 1.5, tp)
    octa(m, "tail", 19.75, 21.25, 0.0, 19.75, 2.25, 2.0, 1.0, tp)

    # the wing bumps on the sides, a shade deeper, their feather edge
    for s_ in (1, -1):
        def wing(c, s_=s_):
            x, y, z = c.p
            k = 0.8 * lit_at(c, (s_ * 10, 10, 6), (3, 3, 7))
            if c.face in ("east", "west") and (abs(z - 11.5) < 0.5 or abs(y - 8.0) < 0.4):
                return ("raw", (200, 126, 22, 255))
            return smooth_colour(DUCK_STOPS, k, c)
        xa, xb = sorted((s_ * 11.5, s_ * 12.75))
        m.box("wing", (xa, 7.75, 0.0), (xb, 12.5, 12.0), Paint(DUCK, 2, noise=0, light=False, decal=wing))

    # the neck and the head: sections stacked up into a ball
    HC = (0.0, 21.5, -8.5)
    hp = yellow(HC, (7.5, 7.5, 7.5), gloss=0.965)
    for (y0, y1, hw, hd, bev) in ((15.5, 17.0, 5.5, 5.5, 2.0), (17.0, 18.5, 6.75, 6.5, 2.75), (18.5, 25.0, 7.5, 7.25, 3.0),
                                  (25.0, 27.0, 6.25, 6.0, 2.5), (27.0, 28.25, 4.25, 4.0, 1.5)):
        octa(m, "head", y0, y1, HC[0], HC[2], hw, hd, bev, hp, axis="y")
    m.box("tuft", (-0.75, 28.25, -9.5), (0.75, 29.75, -7.5), Paint(DUCK, 2, noise=0, light=False,
                                                                 decal=lambda c: ("raw", (255, 222, 90, 255))))

    # the eyes: glossy black buttons with a big glint, high on the head's sides; round pink cheeks under them
    def eye(c):
        x, y, z = c.p
        if c.face in ("east", "west") and y > 23.6 and z < -13.2:
            return ("raw", (255, 255, 255, 255))
        if c.face in ("east", "west") and y < 21.9 and z > -12.4:
            return ("raw", (62, 54, 74, 255))
        return ("raw", (22, 20, 30, 255))
    for s_ in (1, -1):
        xa, xb = sorted((s_ * 5.5, s_ * 6.75))
        m.box("eye", (xa, 21.25, -14.5), (xb, 24.5, -11.75), Paint("rubber", 1, noise=0, light=False, decal=eye))
        xc, xd = sorted((s_ * 5.75, s_ * 6.6))
        m.box("cheek", (xc, 18.75, -13.5), (xd, 20.0, -10.75), Paint("pink", 2, noise=0, light=False,
                                                                      decal=lambda c: ("raw", (250, 150, 160, 255))))

    # the bill: wide and flat, the top lit orange, a smile line, a little upturn at the tip
    def beak(c):
        x, y, z = c.p
        if c.face in ("east", "west", "north") and abs(y - 18.9) < 0.3:
            return ("raw", (150, 52, 10, 255))   # the smile
        if c.face == "up":
            return ("raw", (255, 160, 60, 255))
        if c.face == "down":
            return ("raw", (190, 80, 18, 255))
        return ("raw", (240, 128, 34, 255)) if y > 18.9 else ("raw", (214, 100, 24, 255))
    bk = Paint("fox", 3, noise=0, light=False, decal=beak)
    m.box("beak", (-3.75, 17.75, -18.75), (3.75, 20.25, -15.5), bk)
    m.box("beak_tip", (-3.0, 18.0, -20.75), (3.0, 20.75, -18.75), bk)
    m.box("beak_lip", (-2.25, 20.25, -20.5), (2.25, 21.25, -19.5), bk)
    return _held(m, 0.35)


# ================================================================================================
# 썰매 (two-seat wooden sled) 2.0 x 0.9 x 0.7 blocks
# ================================================================================================

SLED = "pepper"


def sled():
    m = _body("sled")
    RX = 6.5                             # the runners' middles (+-x)

    # --- steel-shod wooden runners, curled up at the front
    def runner(c):
        x, y, z = c.p
        if y < 0.75:
            return ("blade", 3.4 if c.face in ("up", "north", "west") else 2.2)   # the steel shoe
        return ("wood", 3.2 if c.face in ("up", "north", "west") else 2.0)
    rp = Paint("wood", 2, kind="grain", axis="z", noise=0.02, rim=0.4, decal=runner)
    for s in (1, -1):
        xa, xb = sorted((s * (RX - 0.75), s * (RX + 0.75)))
        m.box("runner", (xa, 0.0, -11.0), (xb, 1.5, 15.0), rp)
        rod(m, "runner_curl", (0.75, -11.0), (4.5, -15.0), 1.5, rp, x=(xa, xb))
        rod(m, "runner_curl", (4.5, -15.0), (8.5, -15.5), 1.5, rp, x=(xa, xb))
        rod(m, "runner_curl", (8.5, -15.5), (10.0, -13.0), 1.5, rp, x=(xa, xb))
        # posts from the runner up to the deck
        for z in (-8.0, 0.0, 9.0):
            m.box("post", (xa + 0.25 * s, 1.5, z - 0.75), (xb - 0.25 * s, 4.0, z + 0.75), Paint("wood", 2, noise=0, rim=0.4))
        # the red side rail with its gold scroll line
        def rail(c, s=s):
            x, y, z = c.p
            if c.face in ("east", "west") and abs(y - 5.0) < 0.3 and math.floor(z / 2) % 3 != 0:
                return ("gold", 3.4)
            if c.face == "up":
                return (SLED, 3.4)
            return (SLED, 2.6 if c.face in ("north", "west") else 1.8)
        xc, xd = sorted((s * (RX - 0.5), s * (RX + 0.75)))
        m.box("rail", (xc, 4.0, -10.0), (xd, 6.0, 14.0), Paint(SLED, 2, noise=0, rim=0.5, decal=rail))
        # a little brass bell hanging at the front curl
        m.box("bell", (xa, 7.0, -14.5), (xb, 8.5, -13.0),
              Paint("gold", 2, noise=0, rim=0.6, decal=lambda c: ("gold", 3.6 if c.face in ("up", "north", "west") else 2.4)))

    # --- the slatted deck (five slats across, gaps between) and the bow board
    def slat(c):
        if c.face == "up":
            return ("wood", 3.4)
        return ("wood", 2.2)
    sp = Paint("wood", 2, kind="grain", axis="x", noise=0.03, rim=0.4, decal=slat)
    for i in range(6):
        z0 = -9.5 + i * 4.0
        m.box("slat", (-RX + 0.5, 4.0, z0), (RX - 0.5, 5.0, z0 + 3.25), sp)

    def bow(c):
        x, y, z = c.p
        if c.face == "north" and abs(x) < 3 and abs(y - 7.0) < 1.2:
            return ("gold", 3.2 if abs(x) < 2 else 2.4)  # a painted gold star
        return (SLED, 2.8 if c.face in ("north", "up", "west") else 1.8)
    m.box("bow_board", (-RX + 0.5, 4.0, -12.0), (RX - 0.5, 9.0, -10.75), Paint(SLED, 2, noise=0, rim=0.5, decal=bow),
          rotation=("x", -22.5, (0, 4.0, -10.75)))
    # a low backrest for the one behind
    m.box("backrest", (-RX + 0.5, 5.0, 13.0), (RX - 0.5, 9.0, 14.0), Paint(SLED, 2, noise=0, rim=0.5,
                                                                         decal=lambda c: ("gold", 3.4) if abs(c.p[1] - 8.25) < 0.3 else None))
    for s in (1, -1):
        m.box("backrest_post", (s * (RX - 1.25) - 0.5, 5.0, 13.5), (s * (RX - 1.25) + 0.5, 9.5, 14.5), Paint("wood", 2, noise=0, rim=0.4))

    # --- the pull rope: from both curls, a loop out in front
    rope = Paint("burlap", 2, noise=0, rim=0.3,
                 decal=lambda c: ("burlap", 3.2 if math.floor(c.p[0] + c.p[2] + c.p[1]) % 2 else 2.0))
    m.box("rope", (-RX, 9.25, -14.0), (RX, 10.0, -13.25), rope)
    rod(m, "rope", (9.6, -13.6), (7.0, -18.5), 0.75, rope)
    m.box("rope_loop", (-2.0, 6.25, -19.5), (2.0, 7.0, -18.0), rope)
    return _held(m, 0.4)


# ================================================================================================
# 풍선 의자 (a wicker chair hanging under balloons) 1.0 x 1.0 x 1.4 blocks; balloons: the part
# ================================================================================================

def balloon_chair():
    m = _body("balloon_chair")

    def wicker(c):
        x, y, z = c.p
        # basket weave: rows of strands going over and under, a dark gap between rows
        u = x if c.face in ("north", "south", "up", "down") else z
        row = math.floor(y / 1.5) if c.face not in ("up", "down") else math.floor(z / 1.5)
        k = (math.floor(u / 1.5) + row) % 2
        gap = (y % 1.5) < 0.35 and c.face not in ("up", "down")
        if gap:
            return ("straw", 0.6)
        return ("straw", 3.2 if k else 2.2) if c.face in ("north", "west", "up") else ("straw", 2.4 if k else 1.4)
    wp = Paint("straw", 2, noise=0, rim=0.4, decal=wicker)
    # the basket seat: a rounded tub (two boxes), a rim of rolled cane
    m.box("basket", (-7.0, 1.0, -6.0), (7.0, 7.0, 6.5), wp)
    m.box("basket", (-6.0, 0.0, -5.0), (6.0, 1.0, 5.5), wp)
    rim = Paint("wood", 2, noise=0, rim=0.5, decal=lambda c: ("wood", 3.4 if c.face in ("up", "north", "west") else 2.0))
    m.box("rim", (-7.5, 6.5, -6.5), (7.5, 7.5, -5.5), rim)
    # the back and the arms (wicker), their tops rolled cane
    m.box("back", (-7.0, 7.0, 5.0), (7.0, 18.0, 6.5), wp)
    m.box("back_top", (-7.5, 17.5, 4.5), (7.5, 19.0, 7.0), rim)
    for s in (1, -1):
        xa, xb = sorted((s * 5.75, s * 7.0))
        m.box("arm", (xa, 7.0, -5.5), (xb, 11.0, 5.0), wp)
        xc, xd = sorted((s * 5.5, s * 7.5))
        m.box("arm_top", (xc, 11.0, -6.25), (xd, 12.0, 5.25), rim)

    # the red cushion with buttons
    def cushion(c):
        x, y, z = c.p
        if c.face == "up" and (abs(x) - 2.5) ** 2 + (z - 0.5) ** 2 < 0.5:
            return ("fabric_red", 1)                    # the buttons
        if c.face == "up":
            return ("fabric_red", 3.2)
        return ("fabric_red", 2.0 if c.face in ("north", "west") else 1.4)
    m.box("cushion", (-5.5, 7.0, -5.0), (5.5, 8.5, 4.75), Paint("fabric_red", 2, noise=0, rim=0.6, decal=cushion))
    m.box("back_cushion", (-5.0, 8.5, 3.75), (5.0, 16.0, 5.0), Paint("fabric_red", 2, noise=0, rim=0.6, decal=cushion))

    # four ropes up to the hoop (2 blocks up), a small sandbag hanging off the side
    rope = Paint("burlap", 2, noise=0, rim=0.2, decal=lambda c: ("burlap", 3.0 if math.floor(c.p[1]) % 2 else 2.0))
    for x in (-6.75, 6.25):
        for z in (-5.75, 5.75 if x > 0 else 5.75):
            m.box("rope", (x, 11.5 if z < 0 else 18.5, z - 0.25), (x + 0.5, 31.0, z + 0.25), rope)
    hoop = tube("brass", 3.6, 2)
    m.box("hoop", (-7.25, 31.0, -6.25), (7.25, 32.0, -5.25), hoop)
    m.box("hoop", (-7.25, 31.0, 5.25), (7.25, 32.0, 6.25), hoop)
    m.box("hoop", (-7.25, 31.0, -5.25), (-6.25, 32.0, 5.25), hoop)
    m.box("hoop", (6.25, 31.0, -5.25), (7.25, 32.0, 5.25), hoop)
    m.box("hoop_cross", (-6.25, 31.25, -0.5), (6.25, 31.75, 0.5), hoop)
    m.box("hoop_cross", (-0.5, 31.15, -5.25), (0.5, 31.85, 5.25), hoop)
    m.box("sandbag_rope", (7.0, 6.0, 0.0), (7.5, 9.0, 0.5), rope)
    m.box("sandbag", (6.75, 2.5, -1.25), (9.25, 6.0, 1.75), Paint("burlap", 2, noise=0, rim=0.5,
                                                                     decal=lambda c: ("burlap", 1.4) if abs(c.p[1] - 5.25) < 0.3 else None))
    return _held(m, 0.4)


BALLOONS = [
    # (x, y, z) of the balloon's middle (drawn px over the knot), radius, ramp
    ((0.0, 36.0, 0.0), 8.0, "ruby"),
    ((-11.0, 29.0, 3.0), 7.5, "petal_yellow"),
    ((10.5, 30.0, -3.0), 7.5, "sapphire"),
    ((-3.0, 27.0, -11.0), 7.0, "slime"),
    ((3.5, 26.0, 10.5), 7.0, "sakura"),
    ((-8.0, 40.0, -6.0), 6.5, "magic"),
    ((8.0, 40.5, 6.5), 6.5, "fox"),
    ((1.5, 23.0, -1.0), 6.0, "aurora"),
]


def balloon_chair_balloons():
    """The bunch of balloons (the part: its knot at the model's middle, shown at scale 2 over the chair's hoop)."""
    m = BModel("balloon_chair_balloons", shift=(8, 8, 8), scale=2.0, floor=None)
    # the strings: from the knot up to each balloon (turned about x or z, whichever way it leans more)
    string = Paint("pearl", 2, noise=0, rim=0, light=False, decal=lambda c: ("pearl", 2))
    for (bx, by, bz), r, _ in BALLOONS:
        top = by - r - 1.0
        if abs(bx) >= abs(bz):
            ang = max(-45, min(45, round(math.degrees(math.atan2(bx, top)) / 22.5) * 22.5))
            length = math.hypot(bx, top)
            ox = 0 if ang else bx * 0.4      # straight up: beside the others
            m.box("string", (ox - 0.25, 0.0, -0.25), (ox + 0.25, length, 0.25), string, rotation=("z", -ang, (0, 0, 0)) if ang else None)
        else:
            ang = max(-45, min(45, round(math.degrees(math.atan2(bz, top)) / 22.5) * 22.5))
            length = math.hypot(bz, top)
            m.box("string", (-0.25, 0.0, -0.25), (0.25, length, 0.25), string, rotation=("x", ang, (0, 0, 0)) if ang else None)
    m.box("knot", (-1.0, -1.0, -1.0), (1.0, 1.5, 1.0), Paint("pearl", 2, noise=0, rim=0.3))
    for (bx, by, bz), r, ramp in BALLOONS:
        c = (bx, by, bz)

        def shine(cc, c=c, r=r, ramp=ramp):
            x, y, z = cc.p
            # a bright glint up on the lit side
            if math.hypot(x - (c[0] + r * 0.35), y - (c[1] + r * 0.45)) < r * 0.22 and cc.face in ("north", "west", "up"):
                return ("raw", (255, 255, 255, 255))
            return None
        p = round_paint(ramp, c, (r, r * 1.15, r), lo=0.6, hi=3.2, decal=shine)
        # three boxes make a round balloon (a wide one, a tall one, one between): its paint does the rounding
        h = r * 1.15
        for (a, b) in ((r, h * 0.55), (r * 0.55, h), (r * 0.82, h * 0.82)):
            m.box("balloon", (bx - a, by - b, bz - a), (bx + a, by + b, bz + a), p)
        # the tied neck under it
        m.box("balloon_neck", (bx - 0.75, by - r * 1.15 - 1.0, bz - 0.75), (bx + 0.75, by - r * 1.15 + 0.5, bz + 0.75),
              Paint(ramp, 2, noise=0, rim=0.3))
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================
# 로데오 황소 (mechanical rodeo bull) 1.9 x 1.9 x 1.6 blocks; the bull: the part
# ================================================================================================

def rodeo_bull():
    """The stand: a big round padded mat (vinyl in red and white panels, buttoned, a gold star), a steel pedestal with
    a hazard collar, and at the mat's edge the operator's box with its big red button and a lever."""
    m = _body("rodeo_bull")

    def mat(c):
        x, y, z = c.p
        r = math.hypot(x, z)
        a = math.atan2(z, x)
        if c.face == "up":
            if r < 6.0:
                k = math.cos(5 * a) * 1.8 + 3.6
                return ("gold", 3.2) if r < k else ("vermilion", 2.4)          # the star
            if r > 15.0:
                return ("car_white", 3.4)                                       # the white trim
            seg = math.floor((a + math.pi) / (2 * math.pi) * 10)
            if abs(r - 10.5) < 0.5 and math.floor(a * 8) % 2 == 0:
                return ("vermilion", 1.4)                                       # the buttons
            return ("vermilion", 2.8 if seg % 2 else 2.2)                       # panels
        if c.face == "down":
            return ("rubber", 1)
        return ("car_white", 2.6) if y > 3.0 else ("vermilion", 1.8)
    cyl(m, "mat", (0, 0), 34, 0.0, 4.0, Paint("vermilion", 2, noise=0, rim=0.3, decal=mat))
    col = Paint("steel", 2, noise=0, rim=0.3, decal=lambda c: ("steel", 3.4 if c.face in ("north", "west", "up") else 1.8))
    cyl(m, "column_foot", (0, 0), 10, 4.0, 5.5, col)
    cyl(m, "column", (0, 0), 5, 5.5, 12.5, col)
    cyl(m, "collar", (0, 0), 7, 7.0, 8.5, Paint("hazard", 1, noise=0, rim=0.4,
                                                 decal=lambda c: ("rubber", 1) if math.floor(c.p[0] + c.p[2] + c.p[1]) % 2 else ("hazard", 1.6)))

    # the operator's box at the edge: hazard stripes, a big red mushroom button, a lever
    def panel(c):
        x, y, z = c.p
        if c.face in ("north", "south", "east", "west") and y < 5.0:
            return ("hazard", 1.6) if math.floor(x + y + z) % 2 == 0 else ("rubber", 1)
        if c.face == "up":
            return ("steel", 3.2)
        return ("steel", 2.4)
    m.box("control_box", (11.0, 4.0, 11.0), (16.0, 10.0, 15.0), Paint("steel", 2, noise=0, rim=0.5, decal=panel))
    m.box("button_stem", (12.75, 10.0, 12.25), (14.25, 10.75, 13.75), Paint("steel", 3, noise=0))
    m.box("button", (12.0, 10.75, 11.5), (15.0, 12.0, 14.5), Paint("fabric_red", 2, noise=0, rim=0.6, glow=True,
                                                                  decal=lambda c: ("raw", (255, 70, 60, 255)) if c.face == "up" else ("raw", (190, 30, 30, 255))))
    rod(m, "lever", (10.0, 14.0), (15.0, 12.5), 0.75, tube("steel", 3.6, 2), x=(14.5, 15.25))
    m.box("lever_knob", (14.25, 14.5, 11.75), (15.5, 15.75, 13.0), Paint("rubber", 1, noise=0, rim=0.5))
    return _held(m, 0.35)


BULL = "leather"
# the hide: dark brown in the shade, chestnut, a warm sheen where the light falls
BULL_STOPS = [(0.0, (40, 20, 10)), (0.4, (86, 44, 20)), (0.75, (134, 72, 34)), (1.0, (178, 112, 60))]
BELLY_STOPS = [(0.0, (110, 70, 40)), (1.0, (196, 150, 104))]


def rodeo_bull_bull():
    """The mechanical bull, big and mean: a deep chest and a high shoulder hump, faceted like moulded fibreglass and
    shaded by one smooth gradient (no bands); a heavy head held low with a pale muzzle, flared nostrils and a brass ring,
    angry brows over glowing red eyes, long ivory horns sweeping out and up; a bull rope round its chest with a grip, a
    saddle pad, stubby legs with black hooves, a rope tail. Its swivel (where the plugin bucks and turns it) at the
    model's middle, on the pedestal's top."""
    m = BModel("rodeo_bull_bull", shift=(8, 8, 8), scale=2.0, floor=None)
    BC, BR = (0.0, 7.5, 0.0), (8.5, 7.5, 14.0)

    def hide(centre=BC, radii=BR):
        def d(c):
            x, y, z = c.p
            k = lit_at(c, centre, radii)
            if y < centre[1] - radii[1] * 0.55 or c.face == "down":
                return smooth_colour(BELLY_STOPS, 0.35 + 0.5 * k, c)        # the paler belly
            return smooth_colour(BULL_STOPS, k, c)
        return Paint(BULL, 2, noise=0, light=False, decal=d)
    hp = hide()
    for (t0, t1, cy, hw, hh, bev) in ((-12.5, -10.0, 8.25, 7.0, 6.75, 3.0), (-10.0, -6.0, 8.0, 8.5, 7.25, 3.25),
                                      (-6.0, 5.0, 7.5, 8.75, 7.0, 3.25), (5.0, 9.0, 7.5, 8.0, 6.75, 3.0),
                                      (9.0, 12.0, 7.75, 6.75, 5.75, 2.5), (12.0, 13.75, 8.0, 4.75, 4.25, 1.75)):
        octa(m, "body", t0, t1, 0.0, cy, hw, hh, bev, hp)
    # the shoulder hump
    octa(m, "hump", -10.5, -2.5, 0.0, 14.75, 5.6, 2.5, 1.75, hide((0, 15, -6), (6, 3, 5)))

    cyl(m, "swivel", (0, 0), 5, -1.5, 1.0, tube("steel", 3.4, 2))

    # neck and head: held low and forward
    hd = hide((0, 6, -18), (6, 6, 6))
    octa(m, "neck", -16.0, -12.0, 0.0, 8.0, 6.0, 6.25, 2.5, hd)
    octa(m, "head", -21.5, -16.0, 0.0, 6.25, 5.25, 5.5, 2.0, hd)

    def muzzle(c):
        x, y, z = c.p
        if c.face == "north" and 3.5 < y < 5.5 and 0.8 < abs(x) < 2.6:
            return ("raw", (24, 12, 10, 255))                               # flared nostrils
        return smooth_colour([(0.0, (150, 104, 72)), (1.0, (226, 186, 140))], lit_at(c, (0, 4.5, -23), (4, 4, 3)), c)
    octa(m, "muzzle", -25.0, -21.5, 0.0, 4.25, 4.25, 3.5, 1.5, Paint("bean", 2, noise=0, light=False, decal=muzzle))
    m.box("nose_ring", (-1.25, 0.25, -25.5), (1.25, 2.25, -25.0), tube("brass", 3.6, 2))

    # glowing red eyes under heavy angry brows
    for s_ in (1, -1):
        xa, xb = sorted((s_ * 4.85, s_ * 5.6))
        m.box("eye", (xa, 7.0, -20.0), (xb, 8.75, -18.25), Paint("fabric_red", 2, noise=0, glow=True, light=False,
                                                                  decal=lambda c: ("raw", (255, 236, 120, 255)) if c.p[1] > 8.1 else ("raw", (255, 48, 36, 255))))
        xc, xd = sorted((s_ * 1.5, s_ * 5.9))
        m.box("brow", (xc, 9.0, -20.75), (xd, 10.25, -18.0), Paint("rubber", 1, noise=0, light=False,
                                                                   decal=lambda c: ("raw", (30, 14, 8, 255))),
              rotation=("z", -22.5 * s_, (s_ * 3.5, 9.6, -19.4)))
        xe, xf = sorted((s_ * 5.25, s_ * 7.25))
        m.box("ear", (xe, 9.5, -16.5), (xf, 11.0, -15.0), hd)

        # the horns: from the poll out, then up and in, ivory to a dark tip
        def horn(c):
            x = abs(c.p[0])
            if x > 11.0 or c.p[1] > 16.0:
                return ("raw", (70, 52, 36, 255))
            return smooth_colour([(0.0, (190, 172, 140)), (1.0, (252, 244, 222))], lit_at(c, (s_ * 8, 12, -17), (4, 3, 2)), c)
        hpaint = Paint("cream", 2, noise=0, light=False, decal=horn)
        xg, xh = sorted((s_ * 4.0, s_ * 9.0))
        m.box("horn", (xg, 10.25, -18.5), (xh, 12.25, -16.5), hpaint)
        xi, xj = sorted((s_ * 8.25, s_ * 10.25))
        m.box("horn_up", (xi, 11.0, -18.25), (xj, 15.0, -16.75), hpaint)
        xk, xl = sorted((s_ * 7.75, s_ * 9.25))
        m.box("horn_tip", (xk, 15.0, -18.0), (xl, 17.5, -17.0), hpaint)

    # stubby legs with black hooves
    def leg(c):
        if c.p[1] < 0.5:
            return ("raw", (24, 22, 26, 255))
        return smooth_colour(BULL_STOPS, 0.6 * lit_at(c, (0, 3, 0), (8, 4, 12)), c)
    lp = Paint(BULL, 2, noise=0, light=False, decal=leg)
    for s_ in (1, -1):
        for z in (-7.5, 8.0):
            xa, xb = sorted((s_ * 2.5, s_ * 6.5))
            m.box("leg", (xa, -2.5, z - 2.0), (xb, 2.5, z + 2.0), lp)

    # the bull rope round the chest (its grip on top), the saddle pad behind it
    rope = Paint("burlap", 2, noise=0, rim=0.3, decal=lambda c: ("burlap", 3.2 if math.floor(c.p[0] + c.p[1] + c.p[2]) % 2 else 2.0))
    octa(m, "bull_rope", -5.25, -4.25, 0.0, 7.5, 9.1, 7.6, 3.3, rope)
    m.box("grip", (-1.75, 15.0, -5.5), (1.75, 16.5, -4.0), rope)
    m.box("grip_loop", (-0.75, 16.5, -5.25), (0.75, 17.5, -4.25), rope)

    def pad(c):
        x, y, z = c.p
        if c.face == "up" and (abs(abs(x) - 3.5) < 0.35 or abs(z - 2.5) < 0.3):
            return ("gold", 3)                                              # stitching
        return ("raw", (52, 30, 18, 255)) if c.face == "up" else ("raw", (40, 22, 12, 255))
    m.box("saddle", (-4.75, 14.25, -3.75), (4.75, 15.25, 8.5), Paint("wood_dark", 2, noise=0, light=False, decal=pad))
    for s_ in (1, -1):
        xa, xb = sorted((s_ * 8.6, s_ * 9.4))
        m.box("saddle_flap", (xa, 7.0, -3.0), (xb, 13.0, 7.5), Paint("wood_dark", 2, noise=0, light=False,
                                                                   decal=lambda c: ("raw", (46, 26, 14, 255))))
    rod(m, "tail", (10.0, 13.5), (3.0, 17.5), 1.0, Paint(BULL, 1, noise=0))
    m.box("tail_tuft", (-1.25, 0.75, 16.5), (1.25, 3.5, 19.0), Paint("bark", 1, noise=0, rim=0.3))
    m.display = presets.furniture(gui_scale=0.4)
    return m


ALL = {
    "kickboard": kickboard,
    "wheel_kickboard": wheel_kickboard,
    "pogo_stick": pogo_stick,
    "skateboard": skateboard,
    "wheel_skate": wheel_skate,
    "broom": broom,
    "banana_boat": banana_boat,
    "segway": segway,
    "wheel_segway": wheel_segway,
    "toy_duck": toy_duck,
    "sled": sled,
    "balloon_chair": balloon_chair,
    "balloon_chair_balloons": balloon_chair_balloons,
    "rodeo_bull": rodeo_bull,
    "rodeo_bull_bull": rodeo_bull_bull,
}
