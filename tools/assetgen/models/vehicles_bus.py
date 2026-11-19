"""
마을버스 (village bus, content/stsv/items/vehicles_road.yml village_bus): an original design, no company, no real
model. A friendly old country village bus, short and chubby: every corner stepped round (the nose and the tail bulge
out between the corner pillars, the roof rises in two rounded steps), in a soft, sun-faded two-tone of green and
ivory with a thin red pinstripe. It has a face: two big round chrome headlamps for eyes (a dark pupil, a highlight),
rosy cheeks and a little smile over a plain chrome bumper, and a hand-painted route board ("02" and a village's name)
on its forehead. Homey details: a luggage rack on the roof with a flowered cloth bundle (보따리) and a sack of rice,
a ladder up the back, faded pink curtains tied back in a few windows, a little dried mud behind the wheels. Big
windows with rounded corners the whole length (see-through, so the seated riders show), small round chrome mirrors,
chunky wheels with chrome hub caps, a white "02" badge on each side.
Two doors on the right (the kerb side): the front door beside the driver (it swings out) and the middle door (a plug
door that slides forward along the side). Inside: the driver's seat and wheel, a fare box, a card reader at each
door, eight blue seats (two columns and a raised bench at the back), yellow poles and rails with red stop buttons
(하차벨).

The same rules as models/vehicles.py (read its doc first): drawn at its real size (16 px a block, the ground centre at
(0, 0, 0), front = north (-z), +x = the bus's RIGHT side, the driver on the left), shrunk by BUS_SCALE (vehicle
body-scale 4: 6 blocks long is more than a model may hold, and a shrink of 4 keeps every half pixel exact, so the
0.5 px glass stays a plane) and its doors drawn in the body's space (a Kit, like the premium cars:
models/vehicles_premium.py). Its wheel is its own (wheel_bus).

Imported at the end of models/vehicles_road.py (its ALL carries these): import that module, not this one.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3
from stsv_assets import presets
from models.vehicles import CLEAR, DARK, lerp, lamp, plate, upholstery, _held_display
from models.vehicles_road import raw
from models.vehicles_premium import Kit

# ================================================================================================
# 마을버스 6.0 x 2.4 x 3.0 blocks (drawn px)
# ================================================================================================

BUS_SCALE = 4.0
F, BK = -48, 48                    # the tips of the nose and the tail
H, IN, W = 19, 18, 17              # the outer half width, the walls' inside, the nose's and tail's half width
WF, WB = -45, 45                   # where the side walls start and end (corner pillars in front of / behind them)
BOTTOM, FLOOR, BELT, TOP, BAND = 4, 10, 22, 40, 45   # skirt, floor, window sill, window top, top of the band
SCREEN = 18                        # the windscreen's bottom (the nose's top)
CREAM = (15, 21)                   # the cream waist band (rows)
FA, RA, SLOT, ARCH = -24, 26, 10, 18                  # axles, half a wheel well, the arch's top
WHEEL_D, WHEEL_W, WHEEL_X = 16, 6, 15.5                # the chunky wheel: diameter, width, its middle (each side)
DOOR_F = (WF, FA - SLOT)           # the front door (right side, along z): -45 .. -34
DOOR_R = (2, 14)                   # the middle door
SEAT_Y = 17                        # every seat's top
SEAT_X = 7.75                      # the seats' middle (each side)
# the seats (drawn x, z of the rider's hip), in the item file's order: the driver, then the passengers front to back
SEATS = [(-SEAT_X, -36),
         (-SEAT_X, -21), (SEAT_X, -21), (-SEAT_X, -7), (SEAT_X, -7), (-SEAT_X, 7), (-SEAT_X, 21),
         (-SEAT_X, 36), (SEAT_X, 36)]
EYE_X, EYE_Y, EYE_R = 9, 12.5, 4.5    # the headlamp eyes: middle (each side), radius
G = "bottle_green"                 # the soft, slightly faded country-bus green
ROUTE = "02"

# 3 x 5 LED digits
LED = {"0": ["###", "#.#", "#.#", "#.#", "###"], "2": ["###", "..#", "###", "#..", "###"]}

# the side windows' panes (z0, z1) on each side, between rounded pillars; the right side leaves out its doors
CURTAINS = {"west": (1, 4), "east": (1, 3)}     # the panes with curtains
PANES = {"west": [(-43, -31), (-29, -16), (-14, -1), (1, 14), (16, 29), (31, 43)],
         "east": [(-32, -16), (-14, 0), (16, 29), (31, 43)]}


def seat_spec():
    """The item file's seats: [right, up, forward] blocks."""
    return [[x / 16, SEAT_Y / 16, -z / 16] for x, z in SEATS]


def led_text(col, row):
    """The route number "02" in 3 x 5 digits with a 1 px gap (col / row from its top left), or False."""
    if not (0 <= row < 5 and col >= 0):
        return False
    k, x = divmod(col, 4)
    if k >= len(ROUTE) or x == 3:
        return False
    return LED[ROUTE[k]][row][x] == "#"


def green(y, a=2.0, b=2.9):
    """The green, lit softly up the side."""
    return (G, lerp(y, BOTTOM, TOP, a, b))


def worn(p, step):
    """Old paint: a few texels faded lighter by the sun, a few a touch darker (warm, not dirty)."""
    r = h3(math.floor(p[0] * 2), math.floor(p[1]), math.floor(p[2]), 33)
    return step + 0.35 if r < 0.06 else step - 0.25 if r > 0.96 else step


def well(p):
    return abs(p[0]) > IN - 0.1 and p[1] < ARCH + 0.1 and any(abs(p[2] - a) <= SLOT + 0.1 for a in (FA, RA))


def rounded(a, a0, a1, y, y0, y1, r=2.2):
    """Where a texel lies in a pane with round corners: "out", "seal" (its 1 px rim) or "in"."""
    da, dy = min(a - a0, a1 - a), min(y - y0, y1 - y)
    if da < 0 or dy < 0:
        return "out"
    # the corners cut round: a texel nearer a corner than r (city-block, softened) is outside
    if da < r and dy < r and (r - da) ** 2 + (r - dy) ** 2 > r * r:
        return "out"
    if da < 1 or dy < 1 or (da < r + 0.6 and dy < r + 0.6 and (r + 0.6 - da) ** 2 + (r + 0.6 - dy) ** 2 > r * r):
        return "seal"
    return "in"


def glass_px(state, a, y, y1, frame, streak_mod=19):
    """A pane's look: the frame colour outside, a dark rim, a tinted strip along the top, a reflection now and then,
    the rest clear (the riders show)."""
    if state == "out":
        return raw(*frame)
    if state == "seal":
        return raw("leaf_dark", 1)
    fy, fa = math.floor(y), math.floor(a)
    if fy >= y1 - 3:
        return raw("tint", 2.6 if fy == y1 - 3 else 2)
    d = (fa - fy) % streak_mod
    if d in (0, 1) and fy < y1 - 4:
        return raw("glass", 3)
    if d == 3 and fy < y1 - 4:
        return raw("glass", 2)
    return ("raw", CLEAR)


def board(cols, face):
    """A hand-painted route board: an ivory sign in a dark green frame, the route number "02" in red, and after it
    two little dark green marks like a village's name written by hand. cols(c) -> (col from the viewer's left, row
    from the top, columns)."""
    def decal(c):
        col, row, n = cols(c)
        if c.face != face or col in (0, n - 1) or row == 0 or row >= 6:
            return ("leaf_dark", 1.4)                         # the frame (and the board's thin edges)
        r = row - 1
        if led_text(col - 2, r):
            return ("fabric_red", 2.4)
        k = col - 11
        if 0 <= k < n - 13 and k % 5 < 4 and (h3(k // 5, r, k % 5, 5) < 0.45 or r == 2 and k % 5 in (0, 3)):
            return ("leaf_dark", 1.2)                         # brush strokes of a name
        return ("cream", 3.4 if r < 2 else 3.0)
    return Paint("cream", 3, noise=0, rim=0.4, decal=decal)


def wheel_bus():
    """The bus's chunky wheel: a whole block across (16 px), 6 px wide, a fat black tyre with soft tread blocks, a
    cream rim and a chrome hub cap with five nuts, lit on its upper front."""
    m = BModel("wheel_bus", shift=(8, 8, 8), floor=None)
    d, w2 = WHEEL_D, WHEEL_W / 2

    def tyre(c):
        x, y, z = c.p
        r = math.hypot(y, z)
        lit = y - 0.5 * z
        if c.face in ("east", "west"):
            if r > 6.3:
                return ("rubber", 1.6 if lit > 3 else 1.1 if lit > -3 else 0.6)   # the fat sidewall
            if r > 5.2:
                return ("cream", 3.2 if lit > 0 else 2.4)                          # the ivory-painted rim
            if r < 1.2:
                return ("blade", 4)                                                # the cap's middle
            a = math.atan2(z, y)
            if 2.6 < r < 3.8 and (a * 5 / (2 * math.pi)) % 1.0 < 0.22:
                return ("iron", 1.4)                                               # five nuts round it
            return ("blade", 3.2 if lit > 0 else 2.4)                              # the chrome hub cap
        k = math.floor((math.atan2(z, y) + math.pi) / (2 * math.pi) * 32)
        if abs(x) < 0.5:
            return ("rubber", 0.4)                                                 # a groove round the middle
        return ("rubber", 2.2 if (k + (x > 0)) % 2 else 1.4)                       # soft tread blocks
    cyl(m, "tyre", (0, 0), d, -w2, w2, Paint("rubber", 1, noise=0, rim=0.2, ao=0.2, decal=tyre), axis="x")
    m.display = presets.furniture(gui_scale=0.5)
    return m


def bus_kit():
    kit = Kit("village_bus", ("door_front", "door_rear"), scale=BUS_SCALE)
    m = kit.body
    PAINT = Paint(G, 2, noise=0, grad=0)

    # --- the paint ---------------------------------------------------------------------------------
    def outside(c, x, y, z):
        """What every outer face shows by height: a darker skirt, the green, the cream waist band, the green above."""
        fy = math.floor(y)
        if fy < BOTTOM + 2:
            return (G, 1.2)
        if fy == CREAM[0] - 1:
            return ("fabric_red", 2.6)                        # a thin red pinstripe under the band
        if CREAM[0] <= fy < CREAM[1]:
            return ("cream", worn(c.p, 3.0 if fy == CREAM[1] - 1 else 2.6 if fy > CREAM[0] else 2.0))
        if fy == CREAM[1]:
            return ("cream", 1.6)                             # the band's lower edge... its top seam
        g = green(y)
        return (G, worn(c.p, g[1]))

    def side(c):
        """The walls: arch rims, the route badge, the cream band; inside a cream wall with red stop buttons."""
        x, y, z = c.p
        fy, fz = math.floor(y), math.floor(z)
        if well(c.p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            return ("cream", 2.4)                             # the window sill
        inside = (c.face == "east" and x < 0) or (c.face == "west" and x > 0)
        if inside:
            if fy < FLOOR + 1:
                return ("rubber", 1)
            if fy == BELT - 4 and any(abs(z - (s[1] + 3)) < 0.6 for s in SEATS[1:]):
                return ("signal_red", 2)                      # 하차벨 by each seat
            return ("cream", 2.2 if fy > FLOOR + 2 else 1.6)
        if c.face in ("north", "south"):
            return green(y, 1.4, 2.2)                         # the edges at the doors and wells
        # a soft darker rim round the arches
        if (fy == ARCH and any(abs(z - a) <= SLOT + 0.6 for a in (FA, RA))) or \
                (fy < ARCH and any(abs(abs(z - a) - SLOT - 0.5) < 0.6 for a in (FA, RA))):
            return (G, 0.8)
        # the route badge: a round white sign with "02" in green, in the middle of each side (on the right in front
        # of the middle door)
        bz0, by0 = (-5.5, 5) if c.face == "west" else (-13, 5)   # its front lower corner
        col = math.floor(z - bz0) if c.face == "west" else math.floor(bz0 + 11 - z)
        row = math.floor(by0 + 9 - y)
        if 0 <= col < 11 and 0 <= row < 9:
            if math.hypot((col - 5) / 5.6, (row - 4) / 4.6) <= 1.0:
                if led_text(col - 2, row - 2):
                    return (G, 0.4)
                return ("car_white", 3.8 if row < 4 else 3.4)
        if fz in (WF, WF + 1) and fy == 12:
            return ("fox", 3.6)                               # front side marker
        if fz in (WB - 2, WB - 1) and fy == 12:
            return ("signal_red", 1.6)                        # rear side marker
        near = min(abs(z - a) for a in (FA, RA)) - SLOT
        if fy < ARCH - 2 and 0 < near < 4 and h3(fz, fy, 7, 81) < 0.22 - near * 0.05 - max(0, fy - 6) * 0.025:
            return ("clay", 3.0 if h3(fz, fy, 2, 9) < 0.5 else 2.4)   # a little dried mud thrown up behind the wheels
        return outside(c, x, y, z)
    wall = PAINT(decal=side)

    def corner(c):
        """The corner pillars: the band and the green, brighter (round corners catch the light)."""
        x, y, z = c.p
        if c.face == "down":
            return ("rubber", 0)
        got = outside(c, x, y, z)
        if c.face in ("up",):
            return ("cream", 2.6)
        if got[0] == G and y >= BELT:
            return (G, 2.9 if c.face in ("north", "south") else 2.6)
        return got

    def nose(c):
        """The face: rosy cheeks beside the eyes (the eyes are lamps), a little smile, the cream band over it."""
        x, y, z = c.p
        fy, ax = math.floor(y), abs(x)
        if c.face == "north":
            if math.hypot(ax - 14.5, y - 8) < 1.8:
                return ("pink", 2.4 if y > 8 else 2)          # the cheeks
            # the smile: a dark curve between the eyes over the bumper
            ys = 8.2 + x * x / 7
            if ax <= 4.5 and abs(y - ys) < 0.55:
                return ("leaf_dark", 1)
            if ax <= 3.5 and ys - 1.4 < y < ys - 0.4 and y < 9.6:
                return ("pink", 1.4)                          # the tongue
            return outside(c, x, y, z)
        if c.face == "up":
            return ("rubber", 1.2)                            # under the dash
        if c.face in ("east", "west"):
            return outside(c, x, y, z)
        return green(y, 1.4, 2.2)

    def tail(c):
        """The tail: the band, the plate, a few soft vent slots, a tiny smile."""
        x, y, z = c.p
        fy, ax = math.floor(y), abs(x)
        if c.face == "south":
            if 8 <= fy <= 10 and ax < 4.5:
                return plate(math.floor(x + 4.5), 10 - fy, 9, 3)
            if fy in (17, 19, 21) and ax < 6 and c.face == "south" and fy >= CREAM[1]:
                return (G, 1.2)                               # vent slots
            heart = {19: (-2, -1, 1, 2), 18: (-2, -1, 0, 1, 2), 17: (-1, 0, 1), 16: (0,)}   # 4 rows, 5 wide
            if fy in heart and math.floor(x + 0.5) in heart[fy]:
                return ("pink", 2.6 if fy >= 18 else 2.2)    # a little heart on the cream band
            return outside(c, x, y, z)
        if c.face == "up":
            return ("cream", 2.4)
        if c.face in ("east", "west"):
            return outside(c, x, y, z)
        return green(y, 1.4, 2.2)

    def band(c):
        """Above the windows: green, a white roof line along the top."""
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "down":
            return ("cream", 2.6)                             # the ceiling
        if c.face == "up":
            return ("cream", 3.4)
        if fy == BAND - 1:
            return ("cream", 3.6)
        if fy == TOP:
            return (G, 2.0)                                   # the shadow over the windows
        return (G, 3.0)

    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            for z0 in (-36, 22):                              # two roof hatches (a seam round each, a grip)
                if abs(x) < 6 and z0 <= z < z0 + 10:
                    if abs(x) > 5 or math.floor(z) in (z0, z0 + 9):
                        return ("cream", 2.4)
                    return ("rubber", 1.4) if abs(x) < 1.5 and math.floor(z) == z0 + 5 else ("cream", 3.4)
            return ("cream", 3.8)
        return ("cream", 3.2)

    def floor(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > IN - 1 or (x > 12 and DOOR_R[0] <= z <= DOOR_R[1]):
                return ("hazard", 1)                          # yellow edges at the doors and along the walls
            return ("rubber", 2.4 if math.floor(z) % 2 else 1.8)   # ribbed rubber
        if c.face in ("east", "west") and abs(x) > 12 and y < FLOOR:
            return ("hazard", 1) if math.floor(y) == 6 else ("rubber", 1)   # the step's nose
        return ("rubber", 0.6)

    def housing(c):
        return ("rubber", 0) if c.face == "down" else ("rubber", 1.4)

    m.group("body")
    hub = WHEEL_X - WHEEL_W / 2                               # the tyres' inner side
    fp = Paint("rubber", 1, noise=0, decal=floor)
    m.box("floor", (-hub, BOTTOM, WF), (hub, FLOOR, WB), fp)
    for s in (1, -1):
        xa, xb = sorted((hub * s, IN * s))
        top = 7 if s > 0 else FLOOR                           # the front door's step on the right
        m.box("floor_side", (xa, BOTTOM, WF), (xb, top, FA - SLOT), fp)
        m.box("floor_side", (xa, BOTTOM, FA + SLOT), (xb, FLOOR, RA - SLOT), fp)
        m.box("floor_side", (xa, BOTTOM, RA + SLOT), (xb, FLOOR, WB), fp)
        for a in (FA, RA):
            m.box("housing", (xa, ARCH, a - SLOT), (xb, ARCH + 3, a + SLOT), Paint("rubber", 1, noise=0, decal=housing))

    # the walls up to the window sill, cut round the wells (and on the right, the doors)
    for s in (1, -1):
        xa, xb = sorted((IN * s, H * s))
        if s < 0:
            spans = [(WF, FA - SLOT, BOTTOM), (FA - SLOT, FA + SLOT, ARCH), (FA + SLOT, RA - SLOT, BOTTOM),
                     (RA - SLOT, RA + SLOT, ARCH), (RA + SLOT, WB, BOTTOM)]
        else:
            spans = [(FA - SLOT, FA + SLOT, ARCH), (FA + SLOT, DOOR_R[0], BOTTOM),
                     (DOOR_R[1], RA - SLOT, BOTTOM), (RA - SLOT, RA + SLOT, ARCH), (RA + SLOT, WB, BOTTOM)]
        for z0, z1, y0 in spans:
            m.box("wall", (xa, y0, z0), (xb, BELT, z1), wall)
        # the round corners: a pillar each side of the nose and of the tail, stepped in from the walls
        xa, xb = sorted((W * s, H * s))
        m.box("corner", (xa, BOTTOM, WF - 1), (xb, TOP, WF), PAINT(decal=corner))
        m.box("corner", (xa, BOTTOM, WB), (xb, TOP, WB + 1), PAINT(decal=corner))
    m.box("nose", (-W, BOTTOM, F), (W, SCREEN, WF), PAINT(decal=nose))
    m.box("tail", (-W, BOTTOM, WB), (W, 26, BK), PAINT(decal=tail))
    m.box("band", (-H, TOP, WF - 1), (H, BAND, WB + 1), PAINT(decal=band))
    m.box("roof", (-H + 1.5, BAND, WF + 1), (H - 1.5, BAND + 1, WB - 1), PAINT(decal=roof))
    m.box("roof", (-H + 4, BAND + 1, WF + 4), (H - 4, BAND + 2, WB - 4), PAINT(decal=roof))

    # a luggage rack on the back of the roof (black steel tube), a cloth bundle (보따리) tied up in a flowered wrap
    # and a burlap sack of rice on it, a ladder up the back to reach them
    RY = BAND + 2                                             # the roof's top
    rail = Paint("rubber", 2, noise=0, rim=0.4, decal=lambda c: ("rubber", 2.6 if c.face == "up" else 1.4))
    for s_ in (1, -1):
        xa, xb = sorted((12 * s_, 13 * s_))
        m.box("rack_rail", (xa, RY, 2), (xb, RY + 1.5, 40), rail)
    for z0 in (4, 20, 36):
        m.box("rack_bar", (-12, RY, z0), (12, RY + 1, z0 + 1), rail)

    def bundle(c):
        # a square wrap in faded indigo with little white flowers, its corners knotted on top
        x, y, z = c.p
        fx, fy, fz = math.floor(x), math.floor(y), math.floor(z)
        if c.face == "up":
            if abs(x + 4) < 1.2 or abs(z - 13) < 1.2:
                return ("indigo", 1.6)                        # the folds running to the knot
            flower = (fx + fz) % 4 == 0 and (fx - fz) % 4 == 0
        else:
            u = fz if c.face in ("east", "west") else fx
            flower = (u + fy) % 4 == 0 and (u - fy) % 4 == 0
        if flower:
            return ("cream", 3.6)
        return ("indigo", 2.6 if c.face in ("up", "north", "west") else 2.0)
    bp_ = Paint("indigo", 2, noise=0, rim=0.6, decal=bundle)
    m.box("bundle", (-9, RY + 1, 8), (1, RY + 4, 18), bp_)
    m.box("bundle_knot", (-6, RY + 4, 11), (-2, RY + 5, 15), bp_)

    def sack(c):
        x, y, z = c.p
        if c.face in ("east", "west") and abs(z - 29) < 2 and abs(y - (RY + 2.5)) < 1:
            return ("fabric_red", 2)                          # a printed mark on the sack
        if math.floor(z) in (24, 34):
            return ("straw", 2)                               # the tied ends
        return ("burlap", 2.8 if c.face == "up" else 2.2 if (math.floor(z) + math.floor(y)) % 3 else 1.8)
    m.box("sack", (3, RY + 1, 24), (10, RY + 4, 35), Paint("burlap", 2, noise=0, rim=0.6, decal=sack))

    def ladder(c):
        # two rails and rungs on a see-through plane
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw("rubber", 2)
        col, fy = math.floor(x - 10), math.floor(y)
        if col in (0, 3):
            return raw("rubber", 2 if col == 0 else 1)
        if fy % 4 == 0:
            return raw("blade", 3)
        return ("raw", CLEAR)
    m.box("ladder", (10, 16, BK + 0.5), (14, RY + 1, BK + 1), Paint("rubber", 2, noise=0, light=False, decal=ladder))

    def bumper(c):
        x, y = c.p[0], c.p[1]
        fy = math.floor(y)
        if c.face == "north" and 4 <= fy <= 6 and abs(x) < 4.5:
            got = plate(math.floor(x + 4.5), 6 - fy, 9, 3)
            if got:
                return got
        if c.face == "up":
            return ("blade", 4)
        if fy == 3:
            return ("blade", 2)
        return ("blade", 3.4 if fy >= 5 else 2.8)
    bp = Paint("blade", 3, noise=0, rim=0.6, decal=bumper)
    m.box("bumper", (-W + 0.5, 3, F - 1.5), (W - 0.5, 7, F), bp)
    m.box("bumper", (-W + 0.5, 3, BK), (W - 0.5, 7, BK + 1.5), bp)

    m.group("lights")

    def eye(c):
        # a big round headlamp: a cream rim, the lamp, a dark pupil looking a little inwards, a white highlight
        x, y, z = c.p
        if c.face != "north":
            return green(y, 1.6, 2.2)
        s = 1 if x > 0 else -1
        r = math.hypot(abs(x) - EYE_X, y - EYE_Y)
        if r > EYE_R:
            return (G, green(y)[1] - 0.6)                      # the box's corners: the body round the lamp (unlit paint)
        if r > EYE_R - 1:
            return ("blade", 4 if y > EYE_Y else 3)            # its chrome rim
        px, py = abs(x) - (EYE_X - 0.7), y - (EYE_Y - 0.5)     # the pupil, a little in and down
        if math.hypot(px, py) < 2.2:
            if -1.7 < px < -0.3 and 0.3 < py < 1.7:
                return ("car_white", 4.4)                      # the highlight
            return ("tint", 0)
        return ("lamp", 4 if y > EYE_Y - 1 else 3.4)
    for s in (1, -1):
        xa, xb = sorted(((EYE_X - EYE_R) * s, (EYE_X + EYE_R) * s))
        m.box("eye", (xa, EYE_Y - EYE_R, F - 0.5), (xb, EYE_Y + EYE_R, F), Paint("lamp", 3, decal=eye, **lamp()))

        def taillight(c):
            x, y, z = c.p
            if c.face != "south":
                return ("signal_red", 0)
            r = math.hypot(abs(x) - 14, y - 12)
            if y < 9.5:
                return ("lamp", 3.8) if 12.5 < abs(x) < 15.5 and y > 7.5 else green(y)   # the reverse lamp
            if r > 2.3:
                return green(y)
            if r < 1:
                return ("toadstool", 4)
            return ("toadstool", 3 if y > 12 else 2.4)
        xa, xb = sorted((11.5 * s, 16.5 * s))
        m.box("taillight", (xa, 7, BK), (xb, 14.5, BK + 0.5), Paint("signal_red", 1, decal=taillight, **lamp()))

    # the route boards: on the forehead, on the right side by the front door, on the back
    m.box("route_board", (-12, TOP - 1, WF - 1.5), (12, BAND + 1, WF - 1),
          board(lambda c: (math.floor(12 - c.p[0]), math.floor(BAND + 1 - c.p[1]), 24), "north"))
    m.box("route_board", (H, TOP - 1, -30), (H + 0.5, BAND, -16),
          board(lambda c: (math.floor(-16 - c.p[2]), math.floor(BAND - c.p[1]), 14), "east"))
    m.box("route_board", (-7, TOP, WB + 1), (7, BAND + 1, WB + 1.5),
          board(lambda c: (math.floor(c.p[0] + 7), math.floor(BAND + 1 - c.p[1]), 14), "south"))

    m.group("glass")

    def screen(c):
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw(G, 2.6)
        state = rounded(x, -W, W, y, SCREEN, TOP, r=3.2)
        if state == "in" and math.floor(y) == SCREEN + 1 and (2 <= abs(x) < 9):
            return raw("rubber", 0.6)                          # the wipers resting
        if state == "in" and abs(x) < 0.5:
            return raw("leaf_dark", 1)                         # the screen's middle post
        return glass_px(state, x, y, TOP, (G, 2.6), 23)
    m.box("windscreen", (-W, SCREEN, WF - 1), (W, TOP, WF - 0.5), Paint("glass", 2, noise=0, light=False, decal=screen))

    def windows(c):
        """Big windows with round corners between green pillars, the rest of the band green."""
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw(G, 2.6)
        face = "west" if x < 0 else "east"
        for k, (z0, z1) in enumerate(PANES[face]):
            if z0 - 0.5 <= z <= z1 + 0.5:
                state = rounded(z, z0, z1, y, BELT + 1, TOP - 1)
                if state == "in" and k in CURTAINS[face]:
                    # a curtain drawn back each side, tied in the middle: pleats of faded pink, a cord
                    edge = min(z - z0, z1 - z)
                    fy = math.floor(y)
                    reach = 3.2 if abs(fy - 30) > 1 else 2.2
                    if edge < reach and fy > BELT + 2:
                        if fy == 30:
                            return raw("straw", 3)
                        return raw("sakura", 1.6 if math.floor(z) % 2 else 1.0)
                    if fy >= TOP - 4:
                        return raw("sakura", 1.4 if math.floor(z) % 2 else 0.8)   # the valance along the top
                return glass_px(state, z, y, TOP - 1, (G, 2.7))
        return raw(G, 2.7)
    wp = Paint("glass", 2, noise=0, light=False, decal=windows)
    m.box("side_window", (-H, BELT, WF), (-H + 0.5, TOP, WB), wp)
    m.box("side_window", (H - 0.5, BELT, DOOR_F[1]), (H, TOP, DOOR_R[0]), wp)
    m.box("side_window", (H - 0.5, BELT, DOOR_R[1]), (H, TOP, WB), wp)

    def back_window(c):
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw(G, 2.6)
        return glass_px(rounded(x, -W + 2, W - 2, y, 27, TOP - 1, r=3.2), x, y, TOP - 1, (G, 2.6), 13)
    m.box("back_window", (-W, 26, BK - 0.5), (W, TOP, BK), Paint("glass", 2, noise=0, light=False, decal=back_window))

    # small round mirrors on short stalks out of the front corners
    def mirror(c):
        if c.face == "south":
            corner_ = c.u in (0, c.nu - 1) and c.v in (0, c.nv - 1)
            if corner_:
                return ("blade", 2.6)
            if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                return ("leaf_dark", 1.4)
            return ("glass", 3 if c.v == 1 and c.u == 1 else 1.6)
        if c.face in ("up", "north", "west", "east"):
            return ("blade", 2.8 if c.face == "up" else 2.2)
        return ("blade", 1.6)
    arm = Paint("blade", 3, noise=0, rim=0.4)
    for s in (1, -1):
        xa, xb = sorted((H * s, (H + 2.5) * s))
        m.box("mirror_arm", (xa, 34, WF - 1), (xb, 35, WF), arm)
        xa, xb = sorted(((H + 1.5) * s, (H + 2.5) * s))
        m.box("mirror_stalk", (xa, 34, F - 2), (xb, 35, WF - 1), arm)
        xa, xb = sorted(((H + 0.75) * s, (H + 3.25) * s))
        m.box("mirror", (xa, 30, F - 3.5), (xb, 35, F - 2), Paint("blade", 2, noise=0, decal=mirror))

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        if c.face == "south" and math.floor(y) == SCREEN + 2 and -12 <= x < -4:
            return ("glass", 3) if math.floor(x) in (-11, -6) else ("tint", 1)   # the gauges
        if c.face == "up":
            return ("rubber", 1.6)
        return None
    m.box("dash", (-W, SCREEN, WF), (W, SCREEN + 4, WF + 4), DARK(decal=dash))
    m.box("column", (-8.5, SCREEN, WF + 4), (-7.5, SCREEN + 6, WF + 5), Paint("rubber", 2, noise=0))

    def steering(c):
        if c.face not in ("up", "down"):
            return raw("rubber", 1)
        u, v = c.u, c.v
        ring = (v in (0, 5) and 1 <= u <= 5) or (v in (1, 2, 3, 4) and u in (0, 6))
        if ring:
            return raw("rubber", 2 if v < 3 else 1)
        if v in (2, 3) and 2 <= u <= 4:
            return raw("tint", 2 if u == 3 else 1)            # the hub and its spokes
        return ("raw", CLEAR)
    m.box("wheel", (-11.5, SCREEN + 6, WF + 2), (-4.5, SCREEN + 6.5, WF + 8), Paint("rubber", 1, noise=0, light=False, decal=steering))

    def farebox(c):
        x, y, z = c.p
        if c.face == "up":
            return ("rubber", 0) if abs(x + 1) < 1 and abs(z - (WF + 9)) < 0.6 else ("steel", 3)   # the coin slot
        if c.face == "west" and math.floor(y) == 18:
            return ("signal_blue", 2)                         # its little screen
        return ("steel", 2)
    m.box("fare_box", (-3, FLOOR, WF + 7), (1, 19, WF + 11), Paint("steel", 2, noise=0, rim=0.5, decal=farebox))

    seat = dict(bolster=("rubber", 2), insert=("sapphire", 2), stitch=("sapphire", 3))

    def chair(x, z, w=4.75, y_back=29):
        lo, hi = (x - w, FLOOR, z - 6), (x + w, SEAT_Y, z + 2)
        m.box("cushion", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        lo, hi = (x - w, FLOOR, z + 2), (x + w, y_back, z + 4)
        m.box("seat_back", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    chair(*SEATS[0], y_back=31)
    for x, z in SEATS[1:7]:
        chair(x, z)
    # the raised bench across the back
    lo, hi = (-hub, FLOOR, 30), (hub, SEAT_Y, 38)
    m.box("bench", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    lo, hi = (-hub, FLOOR, 38), (hub, 30, 40)
    m.box("bench_back", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))

    def pole(c):
        # yellow grab poles, a red stop button (하차벨) on each
        if c.face in ("north", "south", "east", "west") and math.floor(c.p[1]) == 27:
            return ("signal_red", 2.4)
        return ("hazard", 2 if c.face in ("north", "west", "up") else 1.4)
    pp = Paint("hazard", 2, noise=0, rim=0.3, decal=pole)
    m.box("pole", (12, FLOOR, DOOR_F[1]), (13, TOP, DOOR_F[1] + 1), pp)
    m.box("pole", (13, FLOOR, DOOR_R[0] - 2), (14, TOP, DOOR_R[0] - 1), pp)
    m.box("pole", (13, FLOOR, DOOR_R[1] + 0.5), (14, TOP, DOOR_R[1] + 1.5), pp)
    for s in (1, -1):
        xa, xb = sorted((11.5 * s, 12.5 * s))
        m.box("rail", (xa, TOP - 1.5, -32), (xb, TOP - 0.5, 30), Paint("hazard", 2, noise=0, rim=0.3))

    def reader(c):
        # a card reader: dark, a small green screen over the card pad
        if c.face == "north":
            if c.v == 0:
                return ("signal_green", 2.4)
            return ("rubber", 2.4) if c.v >= 2 else ("rubber", 1)
        return ("rubber", 1.2)
    rp = Paint("rubber", 1, noise=0, rim=0.4, decal=reader)
    m.box("card_reader", (12.5, 23, DOOR_F[1] + 1), (15.5, 27, DOOR_F[1] + 3), rp)
    m.box("card_reader", (14, 23, DOOR_R[1] + 0.5), (16.5, 27, DOOR_R[1] + 1.5), rp)

    # --- the doors (parts) ---------------------------------------------------------------------------
    def door(z0, z1):
        """A bus door: green below with the cream band across it, two tall glass leaves with round tops, a dark
        split between them, little handles."""
        mid = (z0 + z1) / 2

        def decal(c):
            x, y, z = c.p
            fy = math.floor(y)
            if c.nu <= 1 or c.nv <= 1:
                return raw(G, 2.4)
            if abs(z - mid) < 0.5:
                return raw("leaf_dark", 1)                    # the leaves' split
            for a0, a1 in ((z0 + 1, mid - 0.5), (mid + 0.5, z1 - 1)):
                if a0 <= z <= a1 and fy >= BELT:
                    state = rounded(z, a0, a1, y, BELT, TOP - 1, r=2.2)
                    if state != "out":
                        return glass_px(state, z, y, TOP - 1, (G, 2.6), 17)
            if fy == BELT - 6 and abs(z - mid) < 2.5:
                return raw("blade", 3.6)                      # handles either side of the split
            if fy < BOTTOM + 2:
                return raw(G, 1.3)
            if fy == CREAM[0] - 1:
                return raw("fabric_red", 2.6)
            if CREAM[0] <= fy < CREAM[1] - 1:
                return raw("cream", 2.6)
            return raw(G, lerp(y, BOTTOM, TOP, 2.0, 2.9))
        return Paint("glass", 2, noise=0, light=False, decal=decal)
    kit["door_front"].box("door", (H - 0.5, BOTTOM, DOOR_F[0]), (H, TOP, DOOR_F[1]), door(*DOOR_F))
    kit["door_rear"].box("door", (H - 0.5, BOTTOM, DOOR_R[0]), (H, TOP, DOOR_R[1]), door(*DOOR_R))

    m.display = _held_display(m)
    for ctx in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        m.display[ctx]["scale"] = [round(v * 2.0 / BUS_SCALE * 0.8, 3) for v in m.display[ctx]["scale"]]
    return kit


def village_bus():
    return bus_kit().body


def _part(name):
    return lambda: bus_kit().all()[name]


ALL = {
    "village_bus": village_bus,
    "wheel_bus": wheel_bus,
    **{f"village_bus_{p}": _part(f"village_bus_{p}") for p in ("door_front", "door_rear")},
}
