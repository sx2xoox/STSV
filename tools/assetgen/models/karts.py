"""
서킷 karts (content/stsv/items/karts.yml, race stats: content/stsv/circuit/karts.yml): five racing karts, each its own
build and character, all on the same small footprint (2.2 x 1.5 blocks) so the race is fair to the eye:

  storm     스톰      a low open-wheel racer: a needle nose, a front wing with end plates, side pods with intakes, an
                      airbox behind the driver and a big rear wing. Racing red with a white arrow stripe and number 1.
  bubble    버블      a round bubble car: a tub of a body with a wrap-round glass screen, round headlamps with a smile
                      under them, a whip antenna with a ball on top. Sky blue with a cream belly.
  drifter   섀도      a wide, low street drifter: flared arches over fat rear tyres, a splitter, side skirts that glow, a
                      swan-neck wing on tall struts, twin tail pipes. Deep violet with a neon green slash.
  buffalo   버팔로    a little armoured truck: a bull bar with two horns, riveted side armour, a roll cage over the
                      driver, a spare tyre on the back, a light bar. Hazard yellow with black stripes.
  tub       꽥꽥 욕조  a clawfoot bathtub on wheels (the goofy one): a rolled rim, gold claw feet over the wheels, a tap
                      and a shower on a pipe at the front, a rubber duck riding the front corner, soap bubbles on the
                      rim. White porcelain.

Drawn like every vehicle (models/vehicles.py): 16 px a block, the kart's ground centre at (0, 0, 0), front = north (-z),
+x = the kart's right; drawn at full size and shrunk by 2 (body-scale 2 in the item file). The driver sits low (the seat
at 4.5 px): their legs reach forward over the nose, which stays under them. Each body takes its paint as an argument
(colour: a palette ramp), so models/vehicle_paints.py draws it again in the other colours for the race's colour pick.

Wheels: kart_wheel (slick, six spokes), kart_wheel_big (the buffalo's knobbly ones), kart_wheel_tub (small whitewalls).
"""

import math

from stsv_assets import presets
from stsv_assets.bake import BModel, Paint, cyl
from models import vehicles as V

# ================================================================================================
# Shared parts
# ================================================================================================

HALF_W, FRONT, BACK = 12, -18, 17      # the footprint (px): half width, nose, tail
WF, WR, WX = -11, 11, 10               # front / rear axle (z), wheel centre across (x)
SEAT_Y, SEAT_Z = 4.5, 5                # the seat's top and middle

DARK = Paint("rubber", 1, noise=0)
CARBON = Paint("rubber", 1, noise=0, decal=lambda c: ("rubber", 0.6 if (int(c.p[0] * 2) + int(c.p[2] * 2)) % 2 else 1.2))
STEEL = Paint("steel", 2, noise=0, grad=0)
CHROME = Paint("blade", 2, noise=0, grad=0)
GOLD = Paint("gold", 2, noise=0, grad=0)


def lerp(y, y0, y1, a, b):
    t = max(0.0, min(1.0, (y - y0) / (y1 - y0)))
    return a + (b - a) * t


def lamp(rgb_ramp="lamp", step=3):
    return Paint(rgb_ramp, step, noise=0, light=False, glow=True)


def seat(m, colour, z=SEAT_Z, w=5):
    """A racing bucket seat: a padded base and a tall back with side bolsters, stitched in the body's colour."""
    def stitch(c):
        x, y, z_ = c.p
        if c.face in ("north", "up") and abs(abs(x) - 2.5) < 0.5:
            return (colour, 2.4)
        return ("rubber", 1.4 if (math.floor(y) + math.floor(x)) % 3 else 1.1)
    p = Paint("rubber", 1, noise=0, decal=stitch)
    m.box("seat_base", (-w, 2.5, z - 3), (w, SEAT_Y, z + 3), p)
    m.box("seat_back", (-w, SEAT_Y, z + 3), (w, 13, z + 5), p)
    m.box("bolster", (-w - 1, SEAT_Y, z - 1), (-w, 10, z + 5), p)
    m.box("bolster", (w, SEAT_Y, z - 1), (w + 1, 10, z + 5), p)
    m.box("headrest", (-3, 13, z + 3.5), (3, 15, z + 5), p)


def steering(m, z=-5, y=10):
    """A small round-ish racing wheel on its column, the hub in the middle."""
    m.box("column", (-0.5, 5, z + 0.5), (0.5, y - 1, z + 2.5), STEEL, rotation=("x", -45, (0, y - 1, z + 1)))
    rim = Paint("rubber", 1, noise=0)
    m.box("wheel_top", (-3, y + 2, z), (3, y + 3, z + 1), rim)
    m.box("wheel_bot", (-2, y - 1, z), (2, y, z + 1), rim)
    m.box("wheel_l", (-4, y, z), (-3, y + 2, z + 1), rim)
    m.box("wheel_r", (3, y, z), (4, y + 2, z + 1), rim)
    m.box("wheel_hub", (-1, y, z - 0.5), (1, y + 2, z + 0.5), Paint("signal_red", 2, noise=0))


def floorpan(m, colour, nose=FRONT + 3, tail=BACK - 3, half=8):
    """The flat chassis tray everything sits on (carbon underneath, painted edges)."""
    def tray(c):
        if c.face == "up":
            return ("rubber", 1)
        return ("rubber", 0.5)
    m.box("floor", (-half, 1.5, nose), (half, 2.5, tail), Paint("rubber", 1, noise=0, decal=tray))


def axles(m):
    """The axles and the front stub hubs the wheels hang on (they show between the wheels)."""
    for z in (WF, WR):
        m.box("axle", (-WX + 2.5, 2.5, z - 0.5), (WX - 2.5, 3.5, z + 0.5), STEEL)


def number_pixel(text, col, row):
    """A 3 x 5 digit (models.vehicles.DIGITS plus a few)."""
    digits = {**V.DIGITS, "4": ["#.#", "#.#", "###", "..#", "..#"], "5": ["###", "#..", "##.", "..#", "##."],
              "8": ["###", "#.#", "###", "#.#", "###"], "0": ["###", "#.#", "#.#", "#.#", "###"], "9": ["###", "#.#", "###", "..#", "##."]}
    if not (0 <= row < 5 and col >= 0):
        return False
    k, x = divmod(col, 4)
    if k >= len(text) or x == 3:
        return False
    return digits[text[k]][row][x] == "#"


def _display(m):
    m.display = presets.furniture(gui_scale=0.55)
    return m


# ================================================================================================
# 스톰: an open-wheel racer
# ================================================================================================

def storm(colour="tomato", name="kart_storm"):
    m = BModel(name, shift=(8, 8, 8), scale=2.0, floor=None)
    C = colour

    def body(c):
        x, y, z = c.p
        ax = abs(x)
        # the white arrow down the nose and the engine cover, a thin dark pin line beside it
        if c.face == "up" and ax < 1.5 and z < 12:
            return ("car_white", 3)
        if c.face == "up" and 1.5 <= ax < 2.0 and z < 12:
            return ("rubber", 0.5)
        # a white roundel with a black 1 on each side pod
        if c.face in ("east", "west") and ax > 9 and math.hypot(z - 5, y - 4.6) < 1.9:
            return ("rubber", 0) if abs(z - 5) < 0.5 and abs(y - 4.6) < 1.4 else ("car_white", 3)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            return (C, 3.0 if ax < 4 else 2.6)
        return (C, lerp(y, 2, 9, 1.0, 2.4))
    P = Paint(C, 2, noise=0, grad=0, decal=body)

    floorpan(m, C)
    axles(m)
    # the needle nose: stepped down towards the tip (it stays under the driver's legs)
    m.box("nose", (-3, 2.5, -17), (3, 4.5, -10), P)
    m.box("nose", (-4, 2.5, -10), (4, 5.5, -3), P)
    m.box("nose_tip", (-2, 2.5, -18), (2, 3.5, -17), P)
    # the front wing: a flat plane across with end plates, a second element above it
    wing = Paint(C, 2, noise=0, grad=0, decal=lambda c: ("car_white", 3) if c.face == "up" and abs(c.p[0]) > 9 else None)
    m.box("fwing", (-12, 1, -18), (12, 1.5, -15), wing)
    m.box("fwing2", (-11, 2, -16.5), (-4, 2.5, -15), wing)
    m.box("fwing2", (4, 2, -16.5), (11, 2.5, -15), wing)
    for s in (-1, 1):
        m.box("endplate", (min(s * 12, s * 12.5), 0.5, -18.5), (max(s * 12, s * 12.5), 4, -14.5), CARBON)
    # the cockpit tub round the driver, low sides
    m.box("tub", (-6, 2.5, -3), (-4.5, 7, 9), P)
    m.box("tub", (4.5, 2.5, -3), (6, 7, 9), P)
    m.box("dash", (-4.5, 5, -4), (4.5, 7.5, -2), P)
    # side pods with dark intake mouths at the front
    for s in (-1, 1):
        lo, hi = sorted((s * 6, s * 9.5))
        m.box("pod", (lo, 2.5, -1), (hi, 6.5, 10), P)
        ilo, ihi = sorted((s * 6.5, s * 9))
        m.box("intake", (ilo, 3, -1.5), (ihi, 6, -1), DARK)
        m.box("pod_top", (lo, 6.5, 2), (hi, 7, 10), P)
    seat(m, C)
    steering(m)
    # behind the driver: the airbox over the roll hoop, the engine cover sloping to the tail
    m.box("airbox", (-2.5, 13, 8), (2.5, 16, 11), P)
    m.box("airbox_mouth", (-1.5, 14, 7.5), (1.5, 15.5, 8), DARK)
    m.box("cover", (-4, 2.5, 9), (4, 12.5, 13), P)
    m.box("cover_tail", (-3, 2.5, 13), (3, 9, 16), P)
    m.box("fin", (-0.5, 13, 11), (0.5, 15, 15), P)
    # exhaust tips, the rear wing on two pylons with end plates
    for s in (-1, 1):
        m.box("exhaust", (s * 2 - 0.75, 6, 15.5), (s * 2 + 0.75, 7.5, 17.5), Paint("steel", 3, noise=0))
        m.box("pylon", (s * 3 - 0.5, 9, 14), (s * 3 + 0.5, 12, 16), CARBON)
    rwing = Paint(C, 2, noise=0, grad=0, decal=lambda c: ("car_white", 3) if c.face == "south" else None)
    m.box("rwing", (-10, 12, 13.5), (10, 13, 17), rwing)
    m.box("rwing_flap", (-10, 13.5, 16), (10, 14.5, 17.5), rwing)
    for s in (-1, 1):
        m.box("rplate", (min(s * 10, s * 10.5), 10.5, 13), (max(s * 10, s * 10.5), 15, 17.25), CARBON)
    m.box("taillight", (-1, 4, 16), (1, 5, 16.5), lamp("signal_red", 3))
    return _display(m)


# ================================================================================================
# 버블: a round bubble car
# ================================================================================================

def bubble(colour="sapphire", name="kart_bubble"):
    m = BModel(name, shift=(8, 8, 8), scale=2.0, floor=None)
    C = colour

    def shell(c):
        x, y, z = c.p
        if c.face == "down":
            return ("rubber", 0)
        # a cream belly band low round the sides
        if y < 4.5 and c.face != "up":
            return ("cream", 2.6)
        if c.face == "up":
            return (C, 3.1 if abs(x) < 5 else 2.8)
        # a soft round light: brighter high up
        return (C, lerp(y, 4, 11, 1.4, 2.8))
    P = Paint(C, 2, noise=0, grad=0, decal=shell)

    floorpan(m, C, nose=-13, tail=13, half=8)
    axles(m)
    # the body: a rounded tub, stepped in at the ends and the top (a pebble from above)
    m.box("belly", (-9, 2, -14), (9, 7, 14), P)
    m.box("belly_f", (-7, 2, -17), (7, 6, -14), P)
    m.box("belly_ff", (-5, 2.5, -18), (5, 5, -17), P)
    m.box("belly_b", (-7, 2, 14), (7, 7, 16.5), P)
    m.box("belly_bb", (-5, 2.5, 16.5), (5, 6, 17.5), P)
    m.box("shoulder", (-8, 7, -12), (-6, 9, 13), P)
    m.box("shoulder", (6, 7, -12), (8, 9, 13), P)
    m.box("hood", (-6, 7, -13), (6, 8, -4), P)
    m.box("hood_top", (-5, 8, -12), (5, 8.5, -5), P)
    m.box("back", (-6, 7, 9), (6, 12, 14), P)
    m.box("back_top", (-5, 12, 10), (5, 13.5, 13.5), P)
    # round headlamps with chrome rings, and a smile on the nose under them
    for s in (-1, 1):
        m.box("lamp_ring", (s * 4 - 2, 4, -17.5), (s * 4 + 2, 7, -17), CHROME)
        m.box("lamp", (s * 4 - 1.5, 4.5, -18.3), (s * 4 + 1.5, 6.5, -17.5), lamp("lamp", 3))

    def smile(c):
        x, y = c.p[0], c.p[1]
        if c.face == "north" and 2.5 <= y <= 3.5 and abs(x) < 3 and abs(y - (2.6 + x * x * 0.08)) < 0.7:
            return ("rubber", 0)
        return ("cream", 2.6)
    m.box("chin", (-4, 2, -18.5), (4, 4, -18), Paint("cream", 2, noise=0, decal=smile))
    # the wrap-round glass screen in front of the driver (a thin see-through arc)
    glass = Paint("glass", 2, noise=0, light=False,
                  decal=lambda c: ("raw", (255, 255, 255, 170)) if (math.floor(c.p[0] + c.p[1] * 0.7) % 7 == 0) else ("raw", (170, 220, 255, 70)))
    m.box("screen", (-6, 8.5, -4.5), (6, 13, -4), glass)
    m.box("screen_l", (-6, 8.5, -4), (-5.5, 12, 1), glass)
    m.box("screen_r", (5.5, 8.5, -4), (6, 12, 1), glass)
    m.box("screen_rim", (-6, 13, -4.6), (6, 13.5, -3.9), CHROME)
    seat(m, C, w=4)
    steering(m, z=-3, y=9)
    # the whip antenna and its ball, a little chrome bumper at the back, two tail lamps
    m.box("antenna", (6.75, 13.5, 12), (7.25, 23, 12.5), Paint("rubber", 1, noise=0))
    m.box("ball", (6, 23, 11.25), (8, 25, 13.25), Paint("signal", 3, noise=0, light=False, glow=True))
    m.box("bumper", (-6, 3, 17.5), (6, 4, 18), CHROME)
    for s in (-1, 1):
        m.box("tail", (s * 4 - 1, 6, 17), (s * 4 + 1, 7.5, 17.5), lamp("signal_red", 3))
    return _display(m)


# ================================================================================================
# 섀도: a wide street drifter
# ================================================================================================

def drifter(colour="grape", name="kart_drifter"):
    m = BModel(name, shift=(8, 8, 8), scale=2.0, floor=None)
    C = colour
    NEON = "slime"

    def body(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face == "down":
            return ("rubber", 0)
        # a neon slash across each side, rising to the back
        if c.face in ("east", "west") and abs((y - 3.5) - (z + 6) * 0.25) < 0.6 and -10 < z < 12:
            return (NEON, 3)
        if c.face == "up" and 4 < ax < 5:
            return (NEON, 2.6)     # pin lines along the hood
        if c.face == "up":
            return (C, 2.7)
        if y < 3.2:
            return ("rubber", 1)
        return (C, lerp(y, 3, 8, 0.8, 2.2))
    P = Paint(C, 2, noise=0, grad=0, decal=body)

    floorpan(m, C, half=10)
    axles(m)
    # a low wedge: a splitter, a long flat hood, wide arches front and (wider) back
    m.box("splitter", (-11, 1, -18.5), (11, 2, -15), CARBON)
    m.box("nose", (-9, 2, -17), (9, 4.5, -8), P)
    m.box("hood", (-8, 4.5, -15), (8, 5.5, -4), P)
    m.box("intake", (-6, 2.5, -17.5), (6, 4, -17), DARK)
    for s in (-1, 1):
        m.box("fender_f", (s * 12.5 - (3 if s > 0 else 0), 2.5, -15), (s * 12.5 + (3 if s < 0 else 0), 6.5, -7), P)
        m.box("fender_r", (s * 13 - (4 if s > 0 else 0), 2.5, 6), (s * 13 + (4 if s < 0 else 0), 7, 16), P)
        lo, hi = sorted((s * 9, s * 13))
        m.box("skirt", (lo, 1.75, -7), (hi, 3, 6), P)
        glo, ghi = sorted((s * 13, s * 13.3))
        m.box("neon", (glo, 1.4, -6.5), (ghi, 2.2, 5.5), Paint(NEON, 3, noise=0, light=False, glow=True))
    m.box("tub_l", (-10, 3, -4), (-7, 7.5, 10), P)
    m.box("tub_r", (7, 3, -4), (10, 7.5, 10), P)
    m.box("dash", (-7, 5.5, -5), (7, 8, -3), P)
    seat(m, C)
    steering(m)
    # behind: a deck, the swan-neck wing on two tall struts, twin pipes
    m.box("deck", (-9, 3, 10), (9, 9, 16.5), P)
    m.box("diffuser", (-8, 1, 16.5), (8, 3, 17.5), CARBON)
    for s in (-1, 1):
        m.box("pipe", (s * 3 - 1, 3.5, 16.5), (s * 3 + 1, 5.5, 18), Paint("steel", 3, noise=0))
        m.box("pipe_hole", (s * 3 - 0.5, 4, 18), (s * 3 + 0.5, 5, 18.2), DARK)
        m.box("strut", (s * 6 - 0.5, 9, 13), (s * 6 + 0.5, 17, 14), CARBON)
        m.box("tail", (s * 6.5 - 2, 6, 16.5), (s * 6.5 + 2, 7.5, 17), lamp("signal_red", 3))
    wing = Paint(C, 2, noise=0, grad=0, decal=lambda c: (NEON, 3) if c.face == "south" and abs(c.p[1] - 17.5) < 0.5 else None)
    m.box("wing", (-12, 17, 11), (12, 18, 16), wing)
    m.box("wing_lip", (-12, 18, 15), (12, 19, 16.5), wing)
    for s in (-1, 1):
        m.box("wing_plate", (min(s * 12, s * 12.5), 15.5, 10.5), (max(s * 12, s * 12.5), 20, 16.25), CARBON)
    # headlamps: thin slits in the nose's top edge
    for s in (-1, 1):
        m.box("lamp", (s * 6 - 2.5, 4, -17.2), (s * 6 + 2.5, 4.5, -16.8), lamp("lamp", 3))
    return _display(m)


# ================================================================================================
# 버팔로: an armoured little truck
# ================================================================================================

def buffalo(colour="corn", name="kart_buffalo"):
    m = BModel(name, shift=(8, 8, 8), scale=2.0, floor=None)
    C = colour

    def armour(c):
        x, y, z = c.p
        if c.face == "down":
            return ("rubber", 0)
        # rivets at the plates' corners, black hazard stripes along the lower edge
        if c.face in ("east", "west", "north", "south") and y < 4.5:
            k = math.floor((z if c.face in ("east", "west") else x) + y)
            return ("rubber", 0.6) if k % 4 < 2 else (C, 2.2)
        if c.face in ("east", "west") and (math.floor(z) % 6 == 0) and math.floor(y) % 3 == 0:
            return ("steel", 3.2)
        if c.face == "up":
            return (C, 2.8)
        return (C, lerp(y, 3, 10, 1.2, 2.5))
    P = Paint(C, 2, noise=0, grad=0, decal=armour)

    floorpan(m, C, half=10)
    axles(m)
    # a boxy body: a high blunt nose, thick side armour, a flat rear deck
    m.box("nose", (-9, 2, -16), (9, 7, -6), P)
    m.box("nose_top", (-8, 7, -15), (8, 8, -7), P)
    m.box("grille", (-6, 3, -16.5), (6, 6.5, -16), Paint("rubber", 1, noise=0, decal=lambda c: ("steel", 2.6) if math.floor(c.p[0]) % 2 == 0 else ("rubber", 0.4)))
    for s in (-1, 1):
        lo, hi = sorted((s * 7, s * 11))
        m.box("armour", (lo, 2, -6), (hi, 8, 11), P)
        m.box("lamp", (s * 7 - 1.5, 4.5, -17), (s * 7 + 1.5, 6.5, -16.5), lamp("lamp", 3))
    # the bull bar: two uprights, a cross bar, two horns curling out (the buffalo)
    for s in (-1, 1):
        m.box("bar_up", (s * 5 - 0.5, 1.5, -18.5), (s * 5 + 0.5, 8, -17.5), STEEL)
    m.box("horn_r", (6, 7, -18.5), (9, 8, -17.5), Paint("cream", 3, noise=0))
    m.box("horn_l", (-9, 7, -18.5), (-6, 8, -17.5), Paint("cream", 3, noise=0))
    m.box("horn_tip", (8.5, 8, -18.5), (9.5, 10, -17.5), Paint("cream", 3, noise=0))
    m.box("horn_tip", (-9.5, 8, -18.5), (-8.5, 10, -17.5), Paint("cream", 3, noise=0))
    m.box("bar_x", (-6, 3, -18.25), (6, 4, -17.75), STEEL)
    m.box("bar_top", (-6, 7, -18.25), (6, 7.75, -17.75), STEEL)
    m.box("dash", (-7, 5, -6), (7, 9, -4), P)
    seat(m, C)
    steering(m)
    # the roll cage over the driver: two hoops, rails along the top, a light bar on it
    cage = Paint("blacksteel", 2, noise=0, grad=0)
    for z in (-3, 10):
        m.box("hoop", (-7, 8, z), (-6, 19, z + 1), cage)
        m.box("hoop", (6, 8, z), (7, 19, z + 1), cage)
        m.box("hoop_top", (-7, 19, z), (7, 20, z + 1), cage)
    m.box("rail", (-6.9, 19.1, -2.9), (-6.1, 19.9, 10.9), cage)
    m.box("rail", (6.1, 19.1, -2.9), (6.9, 19.9, 10.9), cage)
    m.box("lightbar", (-5, 20, -3), (5, 21.5, -1.5), Paint("blacksteel", 2, noise=0))
    for k in range(4):
        m.box("lightbar_lamp", (-4.5 + k * 2.5, 20.25, -3.5), (-3 + k * 2.5, 21.25, -3), lamp("lamp", 3))
    # the back: a deck with a spare tyre standing on it, mud flaps
    m.box("deck", (-9, 2, 11), (9, 7, 17), P)
    cyl(m, "spare", (0, 9.5), 9, 13, 16, Paint("rubber", 1, noise=0, decal=lambda c: ("steel", 2.4) if math.hypot(c.p[0], c.p[1] - 9.5) < 2.2 else None), axis="z")
    for s in (-1, 1):
        m.box("flap", (s * 10 - 1.5, 1, 15.5), (s * 10 + 1.5, 5, 16), Paint("rubber", 1, noise=0))
        m.box("tail", (s * 7 - 1, 5, 17), (s * 7 + 1, 6.5, 17.5), lamp("signal_red", 3))
    return _display(m)


# ================================================================================================
# 꽥꽥 욕조: a bathtub on wheels
# ================================================================================================

def tub(colour="car_white", name="kart_tub"):
    m = BModel(name, shift=(8, 8, 8), scale=2.0, floor=None)
    C = colour

    def porcelain(c):
        x, y, z = c.p
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            return (C, 3.2)
        # a glossy streak and a soft shadow towards the bottom
        if c.face in ("east", "west") and abs((z - y * 1.2) % 13 - 3) < 0.7:
            return (C, 3.4)
        return (C, lerp(y, 2, 11, 1.4, 2.9))
    P = Paint(C, 2, noise=0, grad=0, decal=porcelain)
    water = Paint("water", 2, noise=0, light=False,
                  decal=lambda c: ("raw", (240, 250, 255, 255)) if (math.floor(c.p[0] * 0.7) + math.floor(c.p[2] * 0.7)) % 5 == 0 else ("raw", (120, 190, 235, 255)))

    floorpan(m, C, half=8)
    axles(m)
    # the tub: rounded ends (stepped in), straight sides, a rolled rim all round, water and suds inside
    m.box("tub", (-10, 3, -12), (10, 9.5, 12), P)
    m.box("wall_l", (-10, 9.5, -12), (-9, 11, 12), P)
    m.box("wall_r", (9, 9.5, -12), (10, 11, 12), P)
    m.box("tub_f", (-8, 3, -15), (8, 11, -12), P)
    m.box("tub_ff", (-6, 4, -16.5), (6, 11, -15), P)
    m.box("tub_b", (-8, 3, 12), (8, 11, 15), P)
    m.box("tub_bb", (-6, 4, 15), (6, 11, 16.5), P)
    m.box("rim_l", (-11, 11, -12), (-9, 12, 12), P)
    m.box("rim_r", (9, 11, -12), (11, 12, 12), P)
    m.box("rim_f", (-9, 11, -14), (9, 12, -12), P)
    m.box("rim_b", (-9, 11, 12), (9, 12, 15), P)
    m.box("water", (-9, 9.5, -12), (9, 10, 12), water)
    # soap bubbles heaped on the rim and the water
    bub = Paint("car_white", 3, noise=0, light=False, decal=lambda c: ("raw", (255, 255, 255, 255)) if (math.floor(c.p[0]) + math.floor(c.p[1])) % 3 else ("raw", (205, 235, 255, 255)))
    for (x, y, z, r) in ((-9.2, 12, 7.3, 1.4), (-10.1, 12, 3.1, 0.9), (8.3, 12, 9.6, 1.3), (9.6, 12, -5.7, 0.85), (-6.3, 10, 10.6, 1.8), (5.4, 10, 11.2, 1.45),
                         (-8.7, 14.8, 7.6, 0.6), (8.1, 14.6, 9.9, 0.7)):
        m.box("suds", (x - r, y, z - r), (x + r, y + 2 * r, z + r), bub)
    # gold claw feet over the four wheels (the tub stands on them), a gold drain chain plug at the back
    for zf in (WF, WR):
        for s in (-1, 1):
            lo, hi = sorted((s * 9.5, s * 11.5))
            m.box("claw", (lo, 2, zf - 1.5), (hi, 5, zf + 1.5), GOLD)
            m.box("claw_toe", (lo, 1.5, zf - 2.5), (hi, 2.5, zf - 1.5), GOLD)
    # the tap and a shower on a bent pipe at the front, its head over the driver
    m.box("tap", (-1, 11, -15), (1, 13, -13), CHROME)
    m.box("tap_spout", (-0.5, 12, -13), (0.5, 13, -11), CHROME)
    for s in (-1, 1):
        m.box("tap_knob", (s * 2.5 - 1, 11.5, -14.5), (s * 2.5 + 1, 12.5, -13.5), Paint("signal_red" if s < 0 else "signal_blue", 3, noise=0))
    m.box("pipe", (7, 11, -14.6), (8, 24, -13.6), CHROME)
    m.box("pipe_top", (1, 24, -14.6), (8, 25, -13.6), CHROME)
    m.box("shower", (-1.5, 22.5, -15), (2.5, 24, -11), CHROME)
    m.box("shower_face", (-1, 22.25, -14.5), (2, 22.5, -11.5), Paint("steel", 1, noise=0, decal=lambda c: ("rubber", 0.5) if (math.floor(c.p[0] * 2) + math.floor(c.p[2] * 2)) % 2 else ("steel", 3)))
    # the rubber duck riding the front left corner of the rim
    duck = Paint("corn", 3, noise=0, grad=0)
    m.box("duck_body", (-10, 12, -13), (-6, 15, -9), duck)
    m.box("duck_tail", (-9, 14, -9), (-7, 16, -8), duck)
    m.box("duck_head", (-9.5, 15, -13), (-6.5, 18, -10.5), duck)
    m.box("duck_beak", (-8.75, 16, -14.5), (-7.25, 17, -13), Paint("fox", 3, noise=0))
    m.box("duck_eye", (-9.6, 16.75, -12.25), (-9.4, 17.5, -11.75), Paint("rubber", 0, noise=0))
    m.box("duck_eye", (-6.6, 16.75, -12.25), (-6.4, 17.5, -11.75), Paint("rubber", 0, noise=0))
    # the driver sits in the water: a plank seat across the tub, a towel over its back
    m.box("plank", (-9, 10, 2), (9, 11, 8), Paint("wood", 2, "planks", axis="x", width=3))
    m.box("towel", (-5, 11, 7), (5, 16, 8.5), Paint("pink", 2, "weave", noise=0, decal=lambda c: ("car_white", 3) if math.floor(c.p[1]) in (13, 10) else None))
    steering(m, z=-6, y=12)
    m.box("column_base", (-1, 9.5, -7), (1, 11, -4), CHROME)
    return _display(m)


# ================================================================================================
# Wheels
# ================================================================================================

def kart_wheel():
    """The racers' wheel: 0.56 block across (9 px), 5 px wide slicks on a dark six-spoke rim."""
    return V._wheel("kart_wheel", 9, 5, cap="steel", cap_d=6, spokes=6)


def kart_wheel_big():
    """The buffalo's: 11 px across, 6 px wide, a black disc rim (a little off-roader)."""
    return V._wheel("kart_wheel_big", 11, 6, cap="blacksteel", cap_d=7, spokes=5, lip="steel")


def kart_wheel_tub():
    """The bathtub's: 8 px whitewalls on gold hubs."""
    return V._wheel("kart_wheel_tub", 8, 4, cap="gold", cap_d=5, plain=True, whitewall=True)


ALL = {
    "kart_storm": storm,
    "kart_bubble": bubble,
    "kart_drifter": drifter,
    "kart_buffalo": buffalo,
    "kart_tub": tub,
    "kart_wheel": kart_wheel,
    "kart_wheel_big": kart_wheel_big,
    "kart_wheel_tub": kart_wheel_tub,
}
