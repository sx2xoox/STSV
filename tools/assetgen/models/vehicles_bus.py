"""
마을버스 (village bus, content/stsv/items/vehicles_road.yml village_bus): an original design, no company, no real
model. A rounded box of a small city bus in the classic village-bus green: a big upright windscreen under a lit LED
route board ("02"), a long black band of windows the whole length (see-through, so the seated riders show), a
yellow-green stripe under them, a white roof line and an air conditioner on the roof, "rabbit ear" mirrors on stalks
in front of the windscreen, square lamps, a route badge on each side and a route board on the right side and the back.
Two doors on the right (the kerb side): the front door beside the driver (a glass door that swings out) and the
middle door (a plug door that slides back along the side). Inside: the driver's seat and wheel, a fare box, a card
reader at each door, eight blue seats (two columns and a raised bench at the back), yellow poles and rails with red
stop buttons (하차벨).

The same rules as models/vehicles.py (read its doc first): drawn at its real size (16 px a block, the ground centre at
(0, 0, 0), front = north (-z), +x = the bus's RIGHT side, the driver on the left), shrunk by BUS_SCALE (vehicle
body-scale 4: 7 blocks long is more than a model may hold, and a shrink of 4 keeps every half pixel exact, so the 0.5 px glass stays a plane) and its doors drawn in the body's space (a Kit, like the
premium cars: models/vehicles_premium.py). Its wheel is the box truck's (wheel_truck).

Imported at the end of models/vehicles_road.py (its ALL carries these): import that module, not this one.
"""

import math

from stsv_assets.bake import Paint, h3
from models.vehicles import CLEAR, DARK, lerp, lamp, plate, glass, upholstery, _held_display
from models.vehicles_road import raw
from models.vehicles_premium import Kit

# ================================================================================================
# 마을버스 7.0 x 2.4 x 3.0 blocks (drawn px)
# ================================================================================================

BUS_SCALE = 4.0
F, BK = -56, 56                    # the front and back faces
H, IN = 19, 18                     # the outer half width, the walls' inside
BOTTOM, FLOOR, BELT, TOP, BAND = 4, 10, 22, 40, 46   # skirt, floor, window sill, window top, roof line
SCREEN = 17                        # the windscreen's bottom
FA, RA, SLOT, ARCH = -32, 30, 8.5, 15                 # axles, half a wheel well, the arch's top
WHEEL_X = 16                       # the wheels' middle (each side)
DOOR_F = (-55, -40.5)              # the front door (right side, along z)
DOOR_R = (4, 17)                   # the middle door
SEAT_Y = 17                        # every seat's top
SEAT_X = 8.5                       # the seats' middle (each side)
# the seats (drawn x, z of the rider's hip), in the item file's order: the driver, then the passengers front to back
SEATS = [(-SEAT_X, -42),
         (-SEAT_X, -24), (SEAT_X, -24), (-SEAT_X, -8), (SEAT_X, -8), (-SEAT_X, 8), (-SEAT_X, 24),
         (-SEAT_X, 42), (SEAT_X, 42)]
G = "slime"                        # the village-bus green
STRIPE = "sprout"                  # the yellow-green stripe
ROUTE = "02"

# 3 x 5 LED digits
LED = {"0": ["###", "#.#", "#.#", "#.#", "###"], "2": ["###", "..#", "###", "#..", "###"]}


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


def green(y, lo=BOTTOM, hi=BELT, a=0.9, b=2.1):
    return (G, lerp(y, lo, hi, a, b))


def well(p):
    return abs(p[0]) > IN - 0.1 and p[1] < ARCH + 0.1 and any(abs(p[2] - a) <= SLOT + 0.1 for a in (FA, RA))


def board(cols, lit_text, dots=True):
    """An LED board: a black frame, the route number in amber at its left, a row of dim amber dots after it (a
    destination scrolling by). cols(c) -> (col from the viewer's left, row from the top, columns, rows)."""
    def decal(c):
        col, row, n, rows = cols(c)
        if c.face not in lit_text or col in (0, n - 1) or row == 0 or row >= 6:
            return raw("rubber", 1)                           # the frame (and the board's thin edges)
        r = row - 1
        if led_text(col - 2, r):
            return raw("glow_amber", 2)
        if dots and 11 <= col < n - 2 and r in (1, 3) and (col + r) % 2 == 0:
            return raw("glow_amber", 0)
        return raw("tint", 0)
    return Paint("glow_amber", 1, decal=decal, **lamp())


def bus_kit():
    kit = Kit("village_bus", ("door_front", "door_rear"), scale=BUS_SCALE)
    m = kit.body
    PAINT = Paint(G, 2, noise=0, grad=0)

    # --- the paint ---------------------------------------------------------------------------------
    def side(c):
        """The walls: green lit up the side, the stripe under the windows, arch trim, the route badge, markers;
        inside a cream wall with a dark kick strip and red stop buttons."""
        x, y, z = c.p
        fy, fz = math.floor(y), math.floor(z)
        if well(c.p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            return ("rubber", 1.4)                            # the window sill
        inside = (c.face == "east" and x < 0) or (c.face == "west" and x > 0)
        if inside:
            if fy < FLOOR + 1:
                return ("rubber", 1)
            if fy == BELT - 4 and any(abs(z - (s[1] + 3)) < 0.6 for s in SEATS[1:]):
                return ("signal_red", 2)                      # 하차벨 by each seat
            return ("cream", 2.2 if fy > FLOOR + 2 else 1.6)
        if c.face in ("north", "south"):
            return green(y, a=0.6, b=1.4)                     # the edges at the doors and wells
        # the outside
        if (fy == ARCH and any(abs(z - a) <= SLOT + 0.6 for a in (FA, RA))) or \
                (fy < ARCH and any(abs(abs(z - a) - SLOT - 0.5) < 0.6 for a in (FA, RA))):
            return ("rubber", 1)                              # arch trim
        if fy == BELT - 1:
            return ("car_white", 3.2)                         # a bright line under the sill
        if fy in (BELT - 3, BELT - 2):
            return (STRIPE, 4 if fy == BELT - 2 else 3.4)     # the stripe
        if fy < BOTTOM + 2:
            return (G, 0.4)                                   # a darker skirt
        if fy in (BOTTOM + 2, BOTTOM + 3):
            return ("car_white", 3.0 if fy == BOTTOM + 3 else 2.2)   # a white band along the skirt
        # the route badge: a white disc with "02" in green on each side towards the back
        bz0, by0 = 40, 8                                      # its lower front corner
        col = math.floor(z - bz0) if c.face == "west" else math.floor(bz0 + 11 - z)
        row = math.floor(by0 + 8 - y)
        if 0 <= col < 11 and 0 <= row < 9:
            corner = (col in (0, 10) and row in (0, 1, 7, 8)) or (col in (1, 9) and row in (0, 8))
            if not corner:
                if led_text(col - 2, row - 2):
                    return (G, 0.4)
                return ("car_white", 3.4 if row < 4 else 3.0)
        if fz in (F + 1, F + 2) and fy == 12:
            return ("fox", 3.4)                               # front side marker
        if fz in (BK - 3, BK - 2) and fy == 12:
            return ("signal_red", 1.6)                        # rear side marker
        if c.face == "west" and fz in (BK - 12, BK - 11, BK - 10) and fy in (13, 14):
            return (G, 0.5)                                   # the fuel door's seam
        if fy < 9 and h3(fz, fy, 7, 81) < 0.08 + (9 - fy) * 0.05:
            return (G, 0.5)                                   # road grime along the sill
        return green(y)
    wall = PAINT(decal=side)

    def front(c):
        """The nose: green over a black grille band, lamps' surrounds (the lamps are boxes), the route number
        painted small on the right."""
        x, y, z = c.p
        fy, ax = math.floor(y), abs(x)
        if c.face == "north":
            if 6 <= fy <= 7 and ax < 10:
                return ("rubber", 0.6 if math.floor(x) % 2 else 1.4)   # the lower grille
            if fy == SCREEN - 1:
                return ("car_white", 3.2)                     # the bright edge under the windscreen
            if fy in (SCREEN - 3, SCREEN - 2) and ax < 16:
                return (STRIPE, 4 if fy == SCREEN - 2 else 3.4)   # the stripe across the nose
            if ax >= H - 1:
                return green(y, a=1.6, b=2.6)                 # the rounded corners catching the light
            return green(y, a=1.1, b=2.3)
        if c.face == "up":
            return ("rubber", 1.2)                            # under the dash
        return green(y, a=0.8, b=1.8)

    def back(c):
        """The tail: engine louvres, the plate, a bright line under the rear window."""
        x, y, z = c.p
        fy, ax = math.floor(y), abs(x)
        if c.face == "south":
            if 8 <= fy <= 10 and ax < 4.5:
                return plate(math.floor(x + 4.5), 10 - fy, 9, 3)
            if 13 <= fy <= 20 and ax < 10:
                if ax >= 9 or fy in (13, 20):
                    return (G, 0.6)                           # the engine lid's seam
                return ("rubber", 0.6) if fy % 2 else (G, 1.8)   # its louvres
            if fy == 25:
                return ("car_white", 3.2)
            if fy in (22, 23) and ax < 15:
                return (STRIPE, 4 if fy == 23 else 3.4)
            if ax >= H - 1:
                return green(y, a=1.4, b=2.4)
            return green(y, 4, 26, 1.0, 2.2)
        if c.face == "up":
            return ("rubber", 1.4)
        return green(y, a=0.8, b=1.8)

    def band(c):
        """Above the windows: green, a white roof line along the top."""
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "down":
            return ("cream", 2.6)                             # the ceiling
        if c.face == "up":
            return ("car_white", 3)
        if fy >= BAND - 2:
            return ("car_white", 3.4 if fy == BAND - 1 else 2.8)
        if fy == TOP:
            return (G, 1.0)                                   # the shadow over the windows
        return (G, 2.2)

    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            for z0 in (-40, 26):                              # two roof hatches (a seam round each, a grip)
                if abs(x) < 6 and z0 <= z < z0 + 10:
                    if abs(x) > 5 or math.floor(z) in (z0, z0 + 9):
                        return ("car_white", 1.8)
                    return ("rubber", 1.4) if abs(x) < 1.5 and math.floor(z) == z0 + 5 else ("car_white", 3.0)
            return ("car_white", 3.4) if abs(x) < 15 else ("car_white", 2.8)
        return ("car_white", 3)

    def aircon(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 7 and math.floor(z) % 3 == 0:
                return ("steel", 2)                           # the grille over the fans
            return ("car_white", 3.4)
        if c.face in ("east", "west") and math.floor(y) == 48 and math.floor(z) % 2 == 0:
            return ("steel", 2.2)                             # vent slots
        return ("car_white", 2.6)

    def floor(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > IN - 1 or (x > 13 and DOOR_R[0] <= z <= DOOR_R[1]):
                return ("hazard", 1)                          # yellow edges at the doors and along the walls
            return ("rubber", 2.4 if math.floor(z) % 2 else 1.8)   # ribbed rubber
        if c.face in ("east", "west") and abs(x) > 13 and y < FLOOR:
            return ("hazard", 1) if math.floor(y) == 6 else ("rubber", 1)   # the step's nose
        return ("rubber", 0.6)

    def housing(c):
        if c.face == "down":
            return ("rubber", 0)
        return ("rubber", 1.4)

    m.group("body")
    fp = Paint("rubber", 1, noise=0, decal=floor)
    m.box("floor", (-13.5, BOTTOM, F + 1), (13.5, FLOOR, BK - 1), fp)
    for s in (1, -1):
        xa, xb = sorted((13.5 * s, IN * s))
        top = 7 if s > 0 else FLOOR                           # the front door's step on the right
        m.box("floor_side", (xa, BOTTOM, F + 1), (xb, top, FA - SLOT), fp)
        m.box("floor_side", (xa, BOTTOM, FA + SLOT), (xb, FLOOR, RA - SLOT), fp)
        m.box("floor_side", (xa, BOTTOM, RA + SLOT), (xb, FLOOR, BK - 1), fp)
        for a in (FA, RA):
            m.box("housing", (xa, ARCH, a - SLOT), (xb, ARCH + 3, a + SLOT), Paint("rubber", 1, noise=0, decal=housing))

    # the walls up to the window sill, cut round the wells (and on the right, the doors)
    for s in (1, -1):
        xa, xb = sorted((IN * s, H * s))
        if s < 0:
            spans = [(F + 1, FA - SLOT, BOTTOM), (FA - SLOT, FA + SLOT, ARCH), (FA + SLOT, RA - SLOT, BOTTOM),
                     (RA - SLOT, RA + SLOT, ARCH), (RA + SLOT, BK - 1, BOTTOM)]
        else:
            spans = [(FA - SLOT, FA + SLOT, ARCH), (FA + SLOT, DOOR_R[0], BOTTOM),
                     (DOOR_R[1], RA - SLOT, BOTTOM), (RA - SLOT, RA + SLOT, ARCH), (RA + SLOT, BK - 1, BOTTOM)]
        for z0, z1, y0 in spans:
            m.box("wall", (xa, y0, z0), (xb, BELT, z1), wall)
    m.box("nose", (-H, BOTTOM, F), (H, SCREEN, F + 1), PAINT(decal=front))
    m.box("tail", (-H, BOTTOM, BK - 1), (H, 26, BK), PAINT(decal=back))
    m.box("band", (-H, TOP, F), (H, BAND, BK), PAINT(decal=band))
    m.box("roof", (-H + 1, BAND, F + 1), (H - 1, BAND + 1, BK - 1), PAINT(decal=roof))
    m.box("aircon", (-11, BAND + 1, -8), (11, BAND + 3.5, 16), Paint("car_white", 2, noise=0, rim=0.5, decal=aircon))

    def bumper(c):
        x, y = c.p[0], c.p[1]
        fy, ax = math.floor(y), abs(x)
        if c.face == "north" and 4 <= fy <= 6 and ax < 4.5:
            got = plate(math.floor(x + 4.5), 6 - fy, 9, 3)
            if got:
                return got
        if c.face in ("north", "south") and fy == 7:
            return ("iron", 3.4)                              # its lit top edge
        if c.face == "up":
            return ("iron", 3.2)
        return ("iron", 1.8)
    bp = Paint("iron", 2, noise=0, rim=0.5, decal=bumper)
    m.box("bumper", (-H - 0.5, 3, F - 1.5), (H + 0.5, 8, F), bp)
    m.box("bumper", (-H - 0.5, 3, BK), (H + 0.5, 7, BK + 1.5), bp)

    m.group("lights")

    def headlight(c):
        # two square lamps each side in a chrome surround, an amber signal outboard
        if c.face != "north":
            return ("blade", 2)
        ax, fy = abs(c.p[0]), math.floor(c.p[1])
        k = math.floor(ax - 11)
        if k >= 5:
            return ("fox", 4) if fy in (10, 11) else ("fox", 3)
        if fy in (9, 12) or k in (0, 2):
            return ("blade", 3.4)
        return ("lamp", 4) if k in (1, 3) else ("lamp", 3)
    for s in (1, -1):
        xa, xb = sorted((11 * s, 18.5 * s))
        m.box("headlight", (xa, 9, F - 0.5), (xb, 13, F), Paint("lamp", 3, decal=headlight, **lamp()))

        def taillight(c):
            fy = math.floor(c.p[1])
            if c.face != "south":
                return ("signal_red", 0)
            if fy >= 21:
                return ("signal_red", 2.4) if fy != 21 else ("signal_red", 1.2)
            if 17 <= fy <= 19:
                return ("fox", 4) if fy == 18 else ("fox", 3)
            if fy <= 11:
                return ("lamp", 3.6) if fy == 10 else ("blade", 3)   # reverse lamp
            return ("signal_red", 2.6) if fy in (13, 14) else ("signal_red", 1.6)
        xa, xb = sorted((15 * s, 18.5 * s))
        m.box("taillight", (xa, 9, BK), (xb, 24, BK + 0.5), Paint("signal_red", 1, decal=taillight, **lamp()))

    # the route boards: over the windscreen, on the right side by the front door, on the back
    m.box("route_board", (-13, TOP - 1, F - 0.5), (13, BAND, F),
          board(lambda c: (math.floor(13 - c.p[0]), math.floor(BAND - c.p[1]), 26, 7), ("north",)))
    m.box("route_board", (H, TOP, -38), (H + 0.5, BAND, -24),
          board(lambda c: (math.floor(-24 - c.p[2]), math.floor(BAND - c.p[1]), 14, 6), ("east",)))
    m.box("route_board", (-8, TOP, BK), (8, BAND, BK + 0.5),
          board(lambda c: (math.floor(c.p[0] + 8), math.floor(BAND - c.p[1]), 16, 6), ("south",)))

    m.group("glass")
    m.box("windscreen", (-H + 0.5, SCREEN, F), (H - 0.5, TOP, F + 0.5), glass(sun=2, wipers=True, streak=0.22))

    def windows(c):
        """The long band of windows: black pillars every 16 px, a black seal, a top-hung small pane in each,
        a tinted strip along the top, a reflection now and then, the rest clear."""
        x, y, z = c.p
        fz, fy = math.floor(z), math.floor(y)
        if c.nu <= 1 or c.nv <= 1:
            return raw("rubber", 1)
        if fy in (BELT, TOP - 1) or fz in (F + 1, F + 2, BK - 2, BK - 3):
            return raw("rubber", 1)                           # the seal round the band, the corner pillars
        if c.face == "east" and fz in (math.floor(DOOR_F[1]), DOOR_R[0] - 1, DOOR_R[1]):
            return raw("rubber", 1)                           # the door posts
        if (fz - 8) % 16 in (0, 1):
            return raw("rubber", 1)                           # pillars
        if fy == TOP - 6:
            return raw("rubber", 1.4)                         # the small pane's frame
        if fy >= TOP - 3:
            return raw("tint", 2.6 if fy == TOP - 3 else 2)   # the tinted strip
        d = (fz - fy) % 23
        if d in (0, 1) and fy > BELT + 2:
            return raw("glass", 3)
        if d == 3 and fy > BELT + 2:
            return raw("glass", 2)
        return ("raw", CLEAR)
    wp = Paint("glass", 2, noise=0, light=False, decal=windows)
    m.box("side_window", (-H, BELT, F + 1), (-H + 0.5, TOP, BK - 1), wp)
    m.box("side_window", (H - 0.5, BELT, DOOR_F[1]), (H, TOP, DOOR_R[0]), wp)
    m.box("side_window", (H - 0.5, BELT, DOOR_R[1]), (H, TOP, BK - 1), wp)

    def back_window(c):
        if c.nu <= 1 or c.nv <= 1 or c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return raw("rubber", 1)
        if c.v <= 2:
            return raw("tint", 2)
        return raw("glass", 3) if (c.u + c.v) % 13 in (0, 1) else ("raw", CLEAR)
    m.box("back_window", (-H + 0.5, 26, BK - 0.5), (H - 0.5, TOP, BK), Paint("glass", 2, noise=0, light=False, decal=back_window))

    # rabbit-ear mirrors: an arm out of each top front corner, forward on a stalk, a tall mirror hanging from it
    def mirror(c):
        if c.face == "south":
            if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                return ("rubber", 1)
            return ("glass", 3.4 if c.v < 3 else 2.4)
        return ("rubber", 1.4)
    arm = Paint("rubber", 2, noise=0, rim=0.4)
    for s in (1, -1):
        xa, xb = sorted((H * s, (H + 3.5) * s))
        m.box("mirror_arm", (xa, TOP + 2, F + 1), (xb, TOP + 3, F + 2), arm)
        xa, xb = sorted(((H + 2.5) * s, (H + 3.5) * s))
        m.box("mirror_stalk", (xa, TOP + 2, F - 6), (xb, TOP + 3, F + 1), arm)
        xa, xb = sorted(((H + 1) * s, (H + 4.5) * s))
        m.box("mirror", (xa, TOP - 10, F - 7), (xb, TOP + 2, F - 5.5), Paint("rubber", 1, noise=0, decal=mirror))

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        if c.face == "south" and math.floor(y) == SCREEN + 2 and -13 <= x < -4:
            return ("glass", 3) if math.floor(x) in (-12, -6) else ("tint", 1)   # the gauges
        if c.face == "up":
            return ("rubber", 1.6)
        return None
    m.box("dash", (-IN, SCREEN, F + 1), (IN, SCREEN + 4, F + 6), DARK(decal=dash))
    m.box("column", (-9, SCREEN, F + 6), (-8, SCREEN + 6, F + 7), Paint("rubber", 2, noise=0))

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
    m.box("wheel", (-12, SCREEN + 6, F + 4), (-5, SCREEN + 6.5, F + 10), Paint("rubber", 1, noise=0, light=False, decal=steering))

    def farebox(c):
        x, y, z = c.p
        if c.face == "up":
            return ("rubber", 0) if abs(x + 1) < 1 and abs(z - (F + 13)) < 0.6 else ("steel", 3)   # the coin slot
        if c.face == "west" and math.floor(y) == 18:
            return ("signal_blue", 2)                         # its little screen
        return ("steel", 2)
    m.box("fare_box", (-3, FLOOR, F + 11), (1, 19, F + 15), Paint("steel", 2, noise=0, rim=0.5, decal=farebox))

    seat = dict(bolster=("rubber", 2), insert=("sapphire", 2), stitch=("sapphire", 3))

    def chair(x, z, w=5, y_back=29):
        lo, hi = (x - w, FLOOR, z - 6), (x + w, SEAT_Y, z + 2)
        m.box("cushion", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        lo, hi = (x - w, FLOOR, z + 2), (x + w, y_back, z + 4)
        m.box("seat_back", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    chair(*SEATS[0], y_back=31)
    for x, z in SEATS[1:7]:
        chair(x, z)
    # the raised bench across the back
    lo, hi = (-13.5, FLOOR, 36), (13.5, SEAT_Y, 44)
    m.box("bench", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    lo, hi = (-13.5, FLOOR, 44), (13.5, 30, 46)
    m.box("bench_back", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))

    def pole(c):
        # yellow grab poles, a red stop button (하차벨) on each
        if c.face in ("north", "south", "east", "west") and math.floor(c.p[1]) == 27:
            return ("signal_red", 2.4)
        return ("hazard", 2 if c.face in ("north", "west", "up") else 1.4)
    pp = Paint("hazard", 2, noise=0, rim=0.3, decal=pole)
    m.box("pole", (13, FLOOR, DOOR_F[1]), (14, TOP, DOOR_F[1] + 1), pp)
    m.box("pole", (14, FLOOR, DOOR_R[0] - 2), (15, TOP, DOOR_R[0] - 1), pp)
    m.box("pole", (14, FLOOR, DOOR_R[1] + 1), (15, TOP, DOOR_R[1] + 2), pp)
    for s in (1, -1):
        xa, xb = sorted((10 * s, 11 * s))
        m.box("rail", (xa, TOP - 3, -38), (xb, TOP - 2, 34), Paint("hazard", 2, noise=0, rim=0.3))

    def reader(c):
        # a card reader: dark, a small green screen over the card pad
        x, y, z = c.p
        if c.face == "north":
            if c.v == 0:
                return ("signal_green", 2.4)
            return ("rubber", 2.4) if c.v >= 2 else ("rubber", 1)
        return ("rubber", 1.2)
    rp = Paint("rubber", 1, noise=0, rim=0.4, decal=reader)
    m.box("card_reader", (13.5, 23, DOOR_F[1] + 1), (16.5, 27, DOOR_F[1] + 3), rp)
    m.box("card_reader", (14.5, 23, DOOR_R[1] + 2), (17, 27, DOOR_R[1] + 4), rp)

    # --- the doors (parts) ---------------------------------------------------------------------------
    def door_glass(z0, z1):
        """A bus door: a black frame, a green kick panel low down, two glass leaves (a black split between them),
        a rubber edge along the closing side."""
        mid = (z0 + z1) / 2

        def decal(c):
            x, y, z = c.p
            fy, fz = math.floor(y), math.floor(z)
            if c.nu <= 1 or c.nv <= 1:
                return raw("rubber", 1)
            if fy in (BOTTOM, TOP - 1) or fz in (math.floor(z0), math.ceil(z1) - 1):
                return raw("rubber", 1)
            if abs(z - mid) < 0.6:
                return raw("rubber", 1)                       # the leaves' split
            if fy < BOTTOM + 7:
                if fy == BOTTOM + 6:
                    return raw("rubber", 1)
                return raw(G, lerp(y, BOTTOM, BOTTOM + 6, 1.0, 2.0))   # the kick panels
            if fy == 21 and abs(z - mid) < 3:
                return raw("blade", 3.4)                      # a handle each side of the split
            if fy >= TOP - 3:
                return raw("tint", 2)
            d = (fz - fy) % 17
            if d in (0, 1) and fy > BOTTOM + 8:
                return raw("glass", 3)
            return ("raw", CLEAR)
        return Paint("glass", 2, noise=0, light=False, decal=decal)
    kit["door_front"].box("door", (H - 0.5, BOTTOM, DOOR_F[0]), (H, TOP, DOOR_F[1]), door_glass(*DOOR_F))
    kit["door_rear"].box("door", (H - 0.5, BOTTOM, DOOR_R[0]), (H, TOP, DOOR_R[1]), door_glass(*DOOR_R))

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
    **{f"village_bus_{p}": _part(f"village_bus_{p}") for p in ("door_front", "door_rear")},
}
