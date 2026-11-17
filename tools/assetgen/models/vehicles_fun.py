"""
특별 탈것 (content/stsv/items/vehicles_fun.yml): ten fun vehicles, all original designs (no brand). Each is its own body
model (and wheel / part), so any of them can be deleted on its own:

  kickboard         킥보드: an aluminium kick scooter with a folding stem, a small saddle on a post (riders can only sit),
                    a rear brake fender and a bell; wheel_kickboard (polyurethane wheels)
  pogo_stick        스카이콩콩: an orange pogo stick, its spring showing between the tube and the foot bracket, rubber pegs
                    and foam grips, a fat rubber tip
  skateboard        보드: a popsicle deck with kicktail and nose, black grip tape, wood-ply edges, a flame graphic under it,
                    two trucks; wheel_skate (cream urethane wheels)
  broom             빗자루: a witch's broom: a knotted dark handle with a brass cap and a hanging moon charm, a velvet
                    cushion and a brass foot bar, a flared straw bundle bound with twine
  banana_boat       바나나보트: a big inflatable banana tube (curved up at both ends, its stem at the bow, brown speckles),
                    two blue side tubes, black grab handles for each of three riders, a tow ring
  segway            세그웨이: a two-wheel self-balancing scooter: the platform with its rubber mat, white fenders, the stem
                    with its steering bar and a little LED dash, a seat-kit saddle; wheel_segway
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
# 킥보드 (kick scooter with a saddle) 1.25 x 0.5 x 1.2 blocks
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
            if abs(z + 4.5) < 0.6 or abs(z - 4.5) < 0.6:
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

    # --- the saddle on its post (riders can only sit: a seat kit), the rear brake fender over the back wheel
    m.box("seat_post", (-0.6, 4.0, 4.1), (0.6, 8.75, 5.3), tube("blade", 3.4, 2))
    m.box("seat_clamp", (-0.9, 4.0, 3.8), (0.9, 5.25, 5.6), Paint(KICK, 2, noise=0, rim=0.4))

    def saddle(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > 1.6 or z < 2.1 or z > 7.4:
                return ("rubber", 2.6)                  # the piping
            return ("rubber", 1.8 if math.floor(z) % 2 else 1.5)
        if c.face in ("east", "west") and y < 9.4:
            return (KICK, 2)                            # a teal band round the base
        return ("rubber", 1.2)
    m.box("saddle", (-2.25, 8.75, 1.75), (2.25, 10.25, 7.75), Paint("rubber", 1, noise=0, rim=0.6, decal=saddle))
    m.box("saddle_nose", (-1.25, 9.0, 0.5), (1.25, 10.0, 1.75), Paint("rubber", 1, noise=0, rim=0.5))

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
        if 18.5 < y < 19.5:
            return ("car_white", 3.4)                   # the white stripe
        if 16 < y < 18 and c.face in ("north", "south", "east", "west"):
            a = math.atan2(x, z)
            if math.floor(a * 3 + y) % 4 == 0:
                return ("petal_yellow", 3)               # little stars round it
        if c.face in ("north", "west"):
            return (POGO, lerp(y, 14, 26, 2.6, 3.6))
        if c.face in ("south", "east"):
            return (POGO, lerp(y, 14, 26, 1.6, 2.4))
        return (POGO, 3)
    cyl(m, "tube", (0, 0), 4, 15, 25, Paint(POGO, 2, noise=0, rim=0.3, decal=body))
    cyl(m, "tube_cap", (0, 0), 5, 14, 15.5, Paint("rubber", 1, noise=0, rim=0.4))
    cyl(m, "top_cap", (0, 0), 3, 25, 27, Paint("rubber", 1, noise=0, rim=0.4))

    # --- the T handlebar with foam grips and end caps
    m.box("bar", (-7, 24.75, -0.6), (7, 25.85, 0.6), tube("blade", 3.6, 2))

    def foam(c):
        x = c.p[0]
        return ("rubber", 2.4 if math.floor(x * 1.5) % 2 else 1.6)
    for s in (1, -1):
        xa, xb = sorted((s * 3.0, s * 7.0))
        m.box("grip", (xa, 24.25, -1.1), (xb, 26.35, 1.1), Paint("rubber", 1, noise=0, rim=0.5, decal=foam))
        xc, xd = sorted((s * 7.0, s * 7.75))
        m.box("bar_end", (xc, 24.25, -1.1), (xd, 26.35, 1.1), Paint(POGO, 2, noise=0, rim=0.4))
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
    m.box("stem", (-1.0, 5.0, -4.0), (1.0, 22.5, -2.0), Paint("car_white", 2, noise=0, rim=0.4, decal=stem))
    m.box("stem_foot", (-1.75, 4.5, -4.75), (1.75, 6.5, -1.25), Paint("steel", 2, noise=0, rim=0.4))

    def dash(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 1.6 and -4.6 < z < -1.6:
                k = math.floor(x * 2 + 4)
                return ("raw", (80, 230, 140, 255)) if k in (2, 3, 4) and z < -3 else ("raw", (30, 60, 80, 255))   # the LED gauge
            return ("rubber", 2)
        return ("rubber", 1.4)
    m.box("dash", (-2.25, 22.5, -5.0), (2.25, 24.0, -1.25), Paint("rubber", 1, noise=0, rim=0.5, decal=dash))
    m.box("bar", (-7.0, 22.75, -3.75), (7.0, 23.75, -2.75), tube("steel", 3.6, 2))

    def grip(c):
        return ("rubber", 2.4 if math.floor(c.p[0] * 2) % 3 == 0 else 1.4)
    for s in (1, -1):
        xa, xb = sorted((s * 4.0, s * 7.25))
        m.box("grip", (xa, 22.5, -4.0), (xb, 24.0, -2.5), Paint("rubber", 1, noise=0, rim=0.4, decal=grip))

    # --- the seat kit: a post from the back of the platform, a padded saddle
    m.box("seat_post", (-0.75, 5.0, 3.0), (0.75, 10.25, 4.5), tube("steel", 3.6, 2))
    m.box("seat_brace", (-0.5, 5.0, 0.5), (0.5, 5.75, 3.25), tube("steel", 3.6, 2))

    def saddle(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > 2.1 or z < 0.6 or z > 6.9:
                return (SEG, 2.6)                        # blue piping
            return ("rubber", 2.0 if math.floor(x * 1.5) % 2 else 1.6)
        return ("rubber", 1.2)
    m.box("saddle", (-2.75, 10.25, 0.0), (2.75, 11.75, 7.5), Paint("rubber", 1, noise=0, rim=0.6, decal=saddle))
    return _held(m, 0.4)


# ================================================================================================
# 장난감 오리 (giant rubber duck) 2.2 x 1.5 x 1.7 blocks
# ================================================================================================

DUCK = "corn"


def toy_duck():
    m = _body("toy_duck")
    BC, BR = (0.0, 9.5, 3.0), (11.5, 9.5, 15.0)      # the body: centre and radii (px)
    HC, HR = (0.0, 20.5, -9.5), (6.75, 6.75, 6.75)    # the head

    def body_decal(c):
        x, y, z = c.p
        # the wings: a slightly paler oval bump on each side, a curved feather line on it
        if abs(x) > 9 and -2 < z < 12 and 7 < y < 13:
            if abs((z - 5) ** 2 / 50 + (y - 10) ** 2 / 9 - 1) < 0.25:
                return (DUCK, 1.2)
        if c.face == "down":
            return (DUCK, 0.8)
        return None
    bp = round_paint(DUCK, BC, BR, lo=0.6, hi=2.6, decal=body_decal)
    blob(m, "body", BC, BR, bp, layer=2.0, row=3.0)
    # the raised tail: a tapering stack up the back
    tp = round_paint(DUCK, (0, 14, 17), (6, 5, 4), lo=0.8, hi=2.6)
    m.box("tail", (-4.75, 10.25, 14.25), (4.75, 15.0, 18.25), tp)
    m.box("tail", (-3.25, 15.0, 15.75), (3.25, 18.5, 19.75), tp)
    m.box("tail", (-1.75, 18.5, 17.25), (1.75, 20.0, 20.25), tp)
    # the wing bumps
    for s in (1, -1):
        xa, xb = sorted((s * 10.5, s * 12.25))
        wp = round_paint(DUCK, (s * 10, 10, 5), (3, 3, 7), lo=1.0, hi=2.8)
        m.box("wing", (xa, 7.5, -1.0), (xb, 12.5, 10.5), wp)
        m.box("wing", (min(s * 11.5, s * 12.0), 8.5, 10.5), (max(s * 11.5, s * 12.0), 12.0, 13.0), wp)

    # the neck and head
    np_ = round_paint(DUCK, (0, 15, -7), (6, 4, 6), lo=0.8, hi=2.4)
    m.box("neck", (-4.75, 14.25, -12.25), (4.75, 16.75, -3.75), np_)

    hp = round_paint(DUCK, HC, HR, lo=0.7, hi=2.7)
    blob(m, "head", HC, HR, hp, layer=1.5, row=2.0)
    # the eyes: glossy black buttons with a white glint, set into the head's sides; pink cheeks under them
    def eye(c):
        x, y, z = c.p
        if c.face in ("east", "west") and y > 23.0 and z < -14.6:
            return ("raw", (255, 255, 255, 255))
        return ("raw", (26, 24, 34, 255))
    for s in (1, -1):
        xa, xb = sorted((s * 4.25, s * 5.6))
        m.box("eye", (xa, 21.25, -15.75), (xb, 24.0, -13.25), Paint("rubber", 1, noise=0, light=False, decal=eye))
        xc, xd = sorted((s * 4.5, s * 5.4))
        m.box("cheek", (xc, 18.5, -14.25), (xd, 20.0, -12.0), Paint("pink", 2, noise=0, rim=0.2))
    # a little tuft on top
    m.box("tuft", (-0.75, 27.0, -10.5), (0.75, 28.5, -8.5), round_paint(DUCK, (0, 27, -9.5), (1, 1, 1), lo=1.6, hi=2.8))

    # the beak: a flat orange bill, the upper half lit, the mouth line between
    def beak(c):
        x, y, z = c.p
        if c.face in ("east", "west", "north") and abs(y - 18.75) < 0.35:
            return ("fox", 1.2)
        if c.face == "up":
            return ("fox", 3.6)
        return ("fox", 3.0 if y > 18.75 else 2.0)
    bk = Paint("fox", 3, noise=0, rim=0.5, decal=beak)
    m.box("beak", (-3.25, 17.25, -19.5), (3.25, 20.0, -15.0), bk)
    m.box("beak_tip", (-2.25, 17.5, -21.0), (2.25, 19.75, -19.5), bk)
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
    """The stand: a round padded mat (vinyl in red and white panels, a gold star), a steel pedestal."""
    m = _body("rodeo_bull")

    def mat(c):
        x, y, z = c.p
        r = math.hypot(x, z)
        a = math.atan2(z, x)
        if c.face == "up":
            if r < 5.5:
                # a gold star in the middle
                k = math.cos(5 * a) * 1.6 + 3.2
                return ("gold", 3.2) if r < k else ("vermilion", 2.4)
            if r > 12.5:
                return ("car_white", 3.4)               # the white trim round the edge
            return ("vermilion", 2.8 if math.floor((a + math.pi) / (2 * math.pi) * 8) % 2 else 2.2)   # panels
        if c.face == "down":
            return ("rubber", 1)
        return ("car_white", 2.6) if y > 3.0 else ("vermilion", 1.8)
    mp = Paint("vermilion", 2, noise=0, rim=0.3, decal=mat)
    cyl(m, "mat", (0, 0), 28, 0.0, 4.0, mp)
    # the pedestal: a steel column with a collar and the swivel at the top
    col = Paint("steel", 2, noise=0, rim=0.3, decal=lambda c: ("steel", 3.4 if c.face in ("north", "west", "up") else 1.8))
    cyl(m, "column_foot", (0, 0), 8, 4.0, 5.5, col)
    cyl(m, "column", (0, 0), 4, 5.5, 11.0, col)
    cyl(m, "collar", (0, 0), 6, 7.0, 8.0, Paint("hazard", 1, noise=0, rim=0.4,
                                                 decal=lambda c: ("rubber", 1) if math.floor(c.p[0] + c.p[2]) % 2 else ("hazard", 1.6)))
    return _held(m, 0.4)


BULL = "leather"


def rodeo_bull_bull():
    """The mechanical bull: a dark brown hide body (lighter belly), a big shoulder hump, the head with ivory horns and
    a pale muzzle, short leg stubs, a saddle pad with a rope grip. Its swivel (where the plugin turns it) at the model's
    middle, on the pedestal's top."""
    m = BModel("rodeo_bull_bull", shift=(8, 8, 8), scale=2.0, floor=None)
    BC, BR = (0.0, 6.0, 1.0), (6.5, 5.5, 12.0)

    def hide(c):
        x, y, z = c.p
        if y < BC[1] - 3.0:
            return ("plush", 2.6)                       # the paler belly
        if c.face == "up" and abs(x) < 3.5 and -3.0 < z < 5.5:
            return None
        return None
    bp = round_paint(BULL, BC, BR, lo=0.9, hi=3.4, decal=hide)
    blob(m, "body", BC, BR, bp, layer=2.0, row=4.0)
    hp = round_paint(BULL, (0, 10, -6), (6, 4, 5), lo=1.2, hi=3.6)
    m.box("hump", (-5.0, 9.5, -10.0), (5.0, 13.0, -2.0), hp)
    m.box("hump", (-3.5, 13.0, -8.5), (3.5, 14.0, -3.5), hp)

    # the swivel under it
    cyl(m, "swivel", (0, 0), 4, -1.0, 1.5, tube("steel", 3.4, 2))

    # the head: a block with a pale muzzle, nostrils, black eyes, ivory horns
    def head(c):
        x, y, z = c.p
        if z < -20.0:
            if c.face == "north" and 2.5 < y < 4.0 and 0.8 < abs(x) < 2.0:
                return ("rubber", 0)                    # the nostrils
            return ("bean", 3.2 if c.face in ("north", "up", "west") else 2.2)    # the muzzle
        if c.face in ("east", "west") and abs(y - 7.5) < 0.9 and abs(z + 17.0) < 0.9:
            return ("raw", (20, 18, 22, 255)) if y < 7.8 else ("raw", (250, 250, 250, 255))   # the eyes
        n = lit(c, (0, 6, -16), (4, 4, 4))
        return (BULL, 1.2 + 2.2 * n)
    hdp = Paint(BULL, 2, noise=0, rim=0.3, grad=0, decal=head)
    m.box("neck", (-4.0, 4.0, -14.0), (4.0, 11.0, -10.0), hdp)
    m.box("head", (-3.5, 2.0, -20.0), (3.5, 9.5, -14.0), hdp)
    m.box("muzzle", (-3.0, 1.5, -22.5), (3.0, 6.5, -20.0), hdp)
    m.box("nose_ring", (-1.0, 0.5, -22.75), (1.0, 2.0, -22.25), tube("brass", 3.6, 2))
    for s in (1, -1):
        xa, xb = sorted((s * 3.5, s * 5.0))
        m.box("ear", (xa, 7.5, -15.5), (xb, 9.0, -14.5), hdp)
        horn = Paint("cream", 2, noise=0, rim=0.5, decal=lambda c: ("cream", 3.6 if c.face in ("up", "north", "west") else 2.2)
                     if abs(c.p[0]) < 7.5 else ("bark", 2))
        xc, xd = sorted((s * 3.0, s * 7.0))
        m.box("horn", (xc, 9.0, -17.0), (xd, 10.5, -15.5), horn)
        xe, xf = sorted((s * 6.0, s * 7.5))
        m.box("horn_tip", (xe, 10.5, -17.0), (xf, 13.0, -15.5), horn)

    # short leg stubs, dark hooves
    def leg(c):
        return ("rubber", 1.4) if c.p[1] < 0.0 else (BULL, 2.4 if c.face in ("north", "west") else 1.4)
    lp = Paint(BULL, 2, noise=0, rim=0.3, decal=leg)
    for s in (1, -1):
        for z in (-6.5, 8.5):
            xa, xb = sorted((s * 2.0, s * 5.0))
            m.box("leg", (xa, -1.5, z - 1.5), (xb, 2.0, z + 1.5), lp)

    # the saddle pad on the back with its rope grip in front, a swishing tail
    def saddle(c):
        x, y, z = c.p
        if c.face == "up" and (abs(abs(x) - 3.0) < 0.35 or abs(z - 0.0) < 0.3):
            return ("gold", 3)                          # stitching
        if c.face == "up":
            return ("wood_dark", 2.6)
        return ("wood_dark", 1.6)
    m.box("saddle", (-4.0, 10.75, -3.0), (4.0, 12.0, 5.75), Paint("wood_dark", 2, noise=0, rim=0.6, decal=saddle))
    for s in (1, -1):
        xa, xb = sorted((s * 4.0, s * 5.5))
        m.box("saddle_flap", (xa, 6.5, -1.75), (xb, 11.5, 5.0), Paint("wood_dark", 2, noise=0, rim=0.5))
    rope = Paint("burlap", 2, noise=0, rim=0.3, decal=lambda c: ("burlap", 3.2 if math.floor(c.p[0] + c.p[1]) % 2 else 2.0))
    m.box("grip_rope", (-3.0, 11.25, -5.0), (3.0, 12.75, -3.5), rope)
    m.box("grip_loop", (-1.0, 12.75, -4.75), (1.0, 13.75, -3.75), rope)
    rod(m, "tail", (7.0, 12.5), (2.0, 15.0), 1.0, Paint(BULL, 1, noise=0))
    m.box("tail_tuft", (-1.0, 0.0, 14.0), (1.0, 2.5, 16.0), Paint("bark", 1, noise=0, rim=0.3))
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
