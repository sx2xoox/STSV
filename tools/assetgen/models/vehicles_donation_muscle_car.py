"""
머슬카 (a donation car, content/stsv/items/vehicles.yml muscle_car): a late-1960s-style two-door muscle coupe,
an original design (no real make, model, grille or badge). 4.8 x 2.1 x 1.5 blocks.

The same rules as models/vehicles.py and models/vehicles_premium.py (read their docs first): the body drawn at its
real size (16 px a block, shrunk by 2 and shown at vehicle.body-scale 2), the wheel a model of its own; the ground
centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT side, the driver on the left (-x). A Kit: the body and
its two doors (door_l, door_r: hinged at their front edge, they swing out about a vertical axis), drawn in the body's
own space where they lie at rest.

Tuning in mind: the paint (colour), the racing stripes (stripe, or None for none) and the cabin's vinyl (trim) are
arguments, so another look is one more call.

The plugin shows ONE wheel model at all four corners (vehicles.yml `wheel`), so the "fat rear tyres" are a matter of
stance: the wheel is a fat one (13 px across, 6 px wide), and the rear track is wider than the front (the rear
wheels stand 1 px further out, under flared haunches).
"""

import math

from stsv_assets.bake import Paint, cyl
from models.vehicles import CLEAR, CHROME, DARK, PLASTIC, lerp, lamp, glass, raw, upholstery, _held_display, _wheel
from models.vehicles_premium import Kit, _mirror, _steering


# ================================================================================================
# 머슬카 4.75 x 2.1 x 1.5 blocks
# ================================================================================================

WHEEL_D = 13


def wheel_muscle():
    """The muscle car's wheel: 13 px across, 6 px wide, a fat black tyre round a bright five-slot mag rim."""
    return _wheel("wheel_muscle", WHEEL_D, 6, cap="blade", cap_d=8, spokes=5, lip="blade")


def muscle_car(colour="fox", stripe="rubber", trim="rubber", name="muscle_car"):
    return muscle_kit(colour, stripe, trim, name).body


def muscle_kit(colour="fox", stripe="rubber", trim="rubber", name="muscle_car"):
    """A long, flat hood with a raised power bulge down its middle and an open air scoop on it; a short cabin set
    well back, its screen laid back, a short roof falling in a fastback to a short boot that kicks up into a ducktail.
    The sides are plain and slab: wide haunches swell out over the rear wheels (a wider rear track), a body line along
    the flank, round arches, a dark sill. In front a blacked-out grille the full width, two round lamps each side in
    chrome rings, a chrome bumper wrapping the corners, amber parking lamps under it; behind, wide red lamps in chrome
    bezels, a chrome bumper, two chrome pipes under it. Two racing stripes run over the hood, scoop, roof, boot and
    spoiler (stripe=None: none). Inside: two vinyl bucket seats (trim) piped in the body colour, a black dash with
    chrome-ringed dials.

    Its parts: door_l and door_r (the side between the seams, its window frame and glass, its mirror, its inner card):
    hinged at their front edge, they swing out."""
    kit = Kit(name, ("door_l", "door_r"))
    m = kit.body
    door = {-1: kit["door_l"], 1: kit["door_r"]}
    C, S, T = colour, stripe, trim
    PAINT = Paint(C, 2, noise=0, grad=0)
    L, W, WH = 38, 15.5, 16.75          # half length, half width, the haunches' half width
    XI = 9.5                            # the wells' inner wall
    WF, WR, SLOT = -23, 23, 8           # the axles, the wells' half length
    SILL, FLOOR, ARCH, BELT, ROOF = 2, 4.5, 14, 15.5, 24.75
    D0, D1 = -14, 6                     # the doors: between their seams
    CAB = 13                            # the glasshouse's half width
    ZS = -11                            # the screen's foot (laid back 45 degrees)
    ZR = 7                              # the roof's back edge: the fastback falls from here at 22.5 degrees
    FB = 1 / math.tan(math.radians(22.5))
    ZB = ZR + (ROOF + 1 - BELT) * FB    # where the fastback meets the boot
    s_step = 1.5 if S == "rubber" else 3.0

    def col(y):
        return (C, lerp(y, 3, 15, 1.3, 2.7))

    def stripe_at(x):
        return S is not None and 1 <= abs(x) < 4

    def well(p):
        x, y, z = p
        return abs(x) > XI - 0.1 and y < ARCH + 0.1 and (abs(z - WF) <= SLOT + 0.1 or abs(z - WR) <= SLOT + 0.1)

    def top(x, z, base=2.9):
        if stripe_at(x):
            return (S, s_step + (0.4 if 2 <= abs(x) < 3 else 0))
        return (C, base + (0.4 if 6 <= abs(x) < 8 else 0))

    def body(c):
        p = c.p
        x, y, z = p
        fx, fy, fz, ax = math.floor(x), math.floor(y), math.floor(z), abs(x)
        if c.face == "down":
            return ("rubber", 0)
        if well(p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "up":
            if y < BELT - 0.1:
                return ("rubber", 0) if well(p) else (C, 2.4)
            if ZS < z < ZB and ax < CAB:
                return ("rubber", 1 if fz % 3 else 0)                   # carpet
            if fz == ZS - 1 and (-11 <= x < -2 or 2 <= x < 11):
                return ("rubber", 0)                                    # wipers at rest
            if z < ZS and fx in (9, -10):
                return (C, 1.4)                                         # the hood's shut lines
            if z > ZB and fz == math.floor(ZB) + 1 and ax < 13:
                return (C, 1.4)                                         # the boot lid's seam
            if ax >= CAB and ZS <= z <= ZB:
                return (C, 3.3)                                         # the shoulder beside the glass
            return top(x, z)
        if c.face in ("east", "west"):
            if ax < XI + 0.6 and ZS <= z < ZB:
                # the cabin's side, seen when a door stands open or through the glass: dark vinyl, a bright sill plate
                return ("blade", 3.2) if fy == math.floor(FLOOR) else (T, 1.2)
            if fy < SILL + 1:
                return ("rubber", 1.2)                                  # the dark sill
            if fz in (D0, D1 - 1) and fy < BELT - 0.5:
                return (C, 0.3)                                         # the doors' seams
            if fy == 12 and D1 - 4 <= fz < D1 - 2:
                return ("blade", 4)                                     # the door handle
            if fy == 10:
                return (C, 3.0)                                         # the body line along the flank
            if fy == 9:
                return (C, 1.0)
            if fz == -L + 2 and 8 <= fy <= 9:
                return ("fox", 3.6)                                     # side marker lamps
            if fz == L - 3 and 8 <= fy <= 9:
                return ("signal_red", 1.4)
            return col(y)
        if c.face == "north":
            if z < -L + 0.1:
                # the blacked-out grille: the full width, a chrome frame, fine dark bars; a bright bar in its middle
                if 7 <= fy <= 13 and ax < 14.5:
                    if fy in (7, 13) or ax >= 13.5:
                        return ("blade", 3.6 if fy == 13 else 2.8)
                    if ax < 4.5 and fy == 10:
                        return ("blade", 4.2)
                    return ("rubber", 0) if fy % 2 else ("tint", 0.7)
                if fy < 4 and 8 <= ax < 12:
                    return ("fox", 3.6)                                 # parking lamps under the bumper
                if fy == 14:
                    return (C, 3.0)
                return col(y)
            if y > BELT - 1:
                return (C, 3.0)
            return col(y)
        if c.face == "south":
            if z > L - 0.1:
                if 7 <= fy <= 9 and ax < 4.5:
                    return ("blade", 3.4) if ax >= 3.5 else ("rubber", 0.5)     # the plate's recess
                if fy == 14:
                    return (C, 3.0)
                if 10 <= fy <= 12 and ax < 3.5:
                    return ("rubber", 0.6)                              # a black panel between the lamps
                return col(y)
            if y > BELT - 1:
                return (C, 3.0)
            return col(y)
        return None
    paint = PAINT(decal=body)
    inner = Paint("rubber", 1, noise=0)

    m.group("body")
    # the middle: full height ahead of the screen (the engine) and behind the fastback (the boot), the cabin's floor
    m.box("core", (-XI, SILL, -L), (XI, BELT, ZS), paint)
    m.box("floor", (-XI, SILL, ZS), (XI, FLOOR, ZB), paint)
    m.box("core", (-XI, SILL, ZB), (XI, BELT, L), paint)
    for s in (1, -1):
        xa, xb = sorted((XI * s, W * s))
        m.box("nose", (xa, SILL, -L), (xb, BELT, WF - SLOT), paint)
        m.box("fender", (xa, ARCH, WF - SLOT), (xb, BELT, WF + SLOT), paint)
        m.box("fender", (xa, SILL, WF + SLOT), (xb, BELT, D0), paint)
        door[s].box("door", (xa, SILL, D0), (xb, BELT, D1), paint)
        m.box("quarter", (xa, SILL, D1), (xb, BELT, WR - SLOT), paint)
        m.box("quarter", (xa, ARCH, WR - SLOT), (xb, BELT, WR + SLOT), paint)
        m.box("tail", (xa, SILL, WR + SLOT), (xb, BELT, L), paint)
        # the haunch: the rear quarter swells out over the wheel (a step in at the door, a tuck under it)
        xa, xb = sorted((W * s, (W + 0.6) * s))
        m.box("haunch", (xa, 4, D1 + 1), (xb, BELT, D1 + 4), paint)
        xa, xb = sorted((W * s, WH * s))
        m.box("haunch", (xa, 4, D1 + 4), (xb, BELT, WR - SLOT), paint)
        m.box("haunch", (xa, ARCH, WR - SLOT), (xb, BELT, WR + SLOT), paint)
        m.box("haunch", (xa, 4.25, WR + SLOT), (xb, BELT, L - 1), paint)
        # the arches: three steps in each upper corner round the square openings
        for wz, outer in ((WF, W), (WR, WH)):
            xa, xb = sorted((XI * s, outer * s))
            for side in (-1, 1):
                edge = wz + side * SLOT
                for deep, lo, hi in ((2.5, 12.5, ARCH), (1, 10.5, 12.5)):
                    z0, z1 = sorted((edge, edge - side * deep))
                    m.box("arch", (xa, lo, z0), (xb, hi, z1),
                          paint(decal=lambda c: (C, 0.4) if c.face in ("down", "north", "south") else col(c.p[1])))

    # the power bulge down the hood, the open scoop on it
    def bulge(c):
        if c.face == "up":
            return top(c.p[0], c.p[2], 3.2)
        return (C, 2.2) if c.face in ("east", "west") else (C, 2.6)
    m.box("bulge", (-5.5, BELT, -36), (5.5, BELT + 0.75, ZS - 1), PAINT(decal=bulge))

    def scoop(c):
        x, y = c.p[0], c.p[1]
        if c.face == "north":
            if c.u in (0, c.nu - 1) or c.v == 0:
                return ("blade", 4)                                     # a bright lip round the mouth
            return ("rubber", 0) if c.v > 1 else ("tint", 0.6)
        if c.face == "up":
            return top(x, c.p[2], 3.4)
        return (C, 2.0)
    m.box("scoop", (-3.5, BELT + 0.75, -31), (3.5, BELT + 2.75, -21), PAINT(decal=scoop))
    m.box("cowl", (-12, BELT, ZS - 1), (12, BELT + 0.5, ZS), inner(decal=lambda c: ("rubber", 1.4) if c.face == "up" else ("rubber", 1)))

    # chrome bumpers wrapping the corners
    bump = CHROME(decal=lambda c: ("blade", 3.8) if c.face == "up" else ("rubber", 1) if c.face == "down"
                  else ("blade", 3.2) if c.p[1] >= 5.5 else ("blade", 2.2))
    m.box("bumper", (-W + 0.5, 4, -L - 1.5), (W - 0.5, 6.5, -L), bump)
    m.box("bumper", (-W + 0.5, 4, L), (W - 0.5, 6.5, L + 1.5), bump)
    for s in (1, -1):
        xa, xb = sorted(((W - 0.5) * s, (W + 0.5) * s))
        m.box("bumper_end", (xa, 4, -L - 1.5), (xb, 6.5, -L + 1.5), bump)
        xa, xb = sorted(((W - 0.5) * s, (WH + 0.25) * s))
        m.box("bumper_end", (xa, 4, L - 1.5), (xb, 6.5, L + 1.5), bump)
        # the front bumper's guards; the plate's dark backing sits between them
        xa, xb = sorted((4.5 * s, 5.5 * s))
        m.box("bumper_guard", (xa, 3.5, -L - 2), (xb, 7, -L - 1.5), CHROME(decal=lambda c: ("blade", 3.8 if c.face in ("up", "north") else 2.6)))
    m.box("plate_back", (-4, 4.25, -L - 1.75), (4, 6.25, -L - 1.5), Paint("rubber", 0, noise=0))

    # two chrome pipes out under the rear bumper
    def pipe(c):
        if c.face == "south":
            r = math.hypot(abs(c.p[0]) - 10, c.p[1] - 3)
            return ("rubber", 0) if r < 0.8 else ("blade", 4)
        return ("blade", 3.4 if c.face == "up" else 2.4)
    for s in (1, -1):
        cyl(m, "exhaust", (10 * s, 3), 2.5, L - 4, L + 1, CHROME(decal=pipe), axis="z")

    # the ducktail: the boot's back edge kicks up
    def duck(c):
        if c.face == "up":
            return top(c.p[0], c.p[2], 3.2)
        if c.face == "south":
            return (C, 2.6)
        return (C, 2.2)
    m.box("ducktail", (-W + 0.5, BELT, L - 4.5), (W - 0.5, BELT + 0.75, L), PAINT(decal=duck))
    m.box("ducktail_lip", (-W + 0.5, BELT + 0.75, L - 2), (W - 0.5, BELT + 1.75, L + 0.5), PAINT(decal=duck))

    m.group("lights")

    def round_lamp(cx):
        def decal(c):
            if c.face != "north":
                return ("blade", 3)
            r = math.hypot(abs(c.p[0]) - cx, c.p[1] - 10)
            if r >= 1.9:
                return ("rubber", 0)                                    # the corners: the black grille behind
            return ("lamp", 4.8) if r < 0.8 else ("lamp", 3.8) if r < 1.3 else ("blade", 4.2)
        return decal
    for s in (1, -1):
        for cx in (7.25, 11.25):
            xa, xb = sorted(((cx - 1.75) * s, (cx + 1.75) * s))
            m.box("headlight", (xa, 8.25, -L - 0.5), (xb, 11.75, -L), Paint("lamp", 3, decal=round_lamp(cx), **lamp()))

    def tail_lamp(c):
        # wide red lamps in a chrome bezel, a bright bar across each, a chrome divider in its middle
        if c.face != "south":
            return ("blade", 3)
        if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return ("blade", 3.8)
        if c.u == c.nu // 2:
            return ("blade", 3.2)
        return ("signal_red", 2.2 if c.v == 2 else 1.0)
    for s in (1, -1):
        xa, xb = sorted((3.5 * s, 14.5 * s))
        m.box("tail_lamp", (xa, 9.75, L), (xb, 13.25, L + 0.5), Paint("signal_red", 1, decal=tail_lamp, **lamp()))

    m.group("cabin")
    # the roof: a bright drip rail round it, the stripes over it
    def roof(c):
        x = c.p[0]
        if c.face == "up":
            return top(x, c.p[2], 3.0)
        if c.face == "down":
            return (T, 1.6)                                             # the headliner
        return ("blade", 3.6) if c.p[1] < ROOF + 0.5 else (C, 2.4)
    m.box("roof", (-CAB, ROOF, ZS + (ROOF - BELT) - 0.5), (CAB, ROOF + 1, ZR + 0.5), PAINT(decal=roof))
    h = (ROOF - BELT) * math.sqrt(2)
    m.box("windscreen", (-CAB + 0.5, BELT, ZS), (CAB - 0.5, BELT + h, ZS + 0.5),
          glass(frame=("blade", 3), sun=1, wipers=False, streak=0.25), rotation=("x", 45, (0, BELT, ZS)))
    fl = (ROOF + 1 - BELT) / math.sin(math.radians(22.5))
    # the fastback: dark glass for its upper part, then painted down to the boot (the stripes run over it)
    GL = round(fl * 0.6)

    def back_glass(c):
        u, v, nu, nv = c.u, c.v, c.nu, c.nv
        if c.face != "up":
            return raw("rubber", 1)
        if v == 0:
            return raw("rubber", 1)                                     # tucked under the roof's edge
        if u in (0, nu - 1) or v == nv - 1:
            return raw("blade", 3)
        if (u + v) % 9 in (0, 1) and 0 < v < nv - 1:
            return raw("glass", 3 if (u + v) % 9 == 0 else 2)          # reflections across the glass
        return raw("tint", 1 if v < 2 else 0)
    m.box("rear_window", (-CAB + 0.5, ROOF + 0.5, ZR), (CAB - 0.5, ROOF + 1, ZR + GL),
          Paint("glass", 2, noise=0, light=False, decal=back_glass), rotation=("x", 22.5, (0, ROOF + 1, ZR)))

    def fastback(c):
        if c.face == "up":
            return top(c.p[0], c.p[2], 3.0)
        return (C, 2.2)
    m.box("fastback", (-CAB, ROOF, ZR + GL), (CAB, ROOF + 1, ZR + fl), PAINT(decal=fastback), rotation=("x", 22.5, (0, ROOF + 1, ZR)))

    def side_window(c):
        x, y, z = c.p
        front = ZS + (y - BELT)
        back = ZR + (ROOF + 1 - y) * FB
        if z < front - 0.3 or z > back + 0.3:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw(C, 2)
        if z > back - 2.5 or z >= 17:
            return raw(C, 2 if y < ROOF - 1 else 3)                   # the sail: painted, falling with the fastback
        if z < front + 1 or y > ROOF - 1 or y < BELT + 0.6 or z >= 16:
            return raw("blade", 4)                                      # bright frames round the glass
        if D1 - 1 <= z < D1 + 0.5:
            return raw("blade", 3)                                      # the door glass's back edge: no pillar
        d = math.floor(z) - math.floor(y)
        if d in (-28, -27, -19):
            return raw("glass", 3)
        return ("raw", CLEAR)
    wp = Paint("glass", 2, noise=0, light=False, decal=side_window)
    for s in (1, -1):
        xa, xb = sorted((CAB * s, (CAB - 0.5) * s))
        door[s].box("side_window", (xa, BELT, D0), (xb, ROOF, D1), wp)
        m.box("side_window", (xa, BELT, D1), (xb, ROOF, ZB), wp)

    # the doors' insides, the mirrors
    def card(c):
        if c.face == "up":
            return ("blade", 3.4)
        fy = math.floor(c.p[1])
        if fy == 11:
            return (C, 2.4)                                             # a painted strip across the card
        return (T, 1.6 if fy % 3 else 1.0)
    for s in (1, -1):
        xa, xb = sorted(((XI - 0.5) * s, XI * s))
        door[s].box("door_card", (xa, FLOOR, D0 + 1), (xb, BELT - 0.25, D1 - 0.5), Paint(T, 1, noise=0, decal=card))
        xa, xb = sorted((14.5 * s, 15.25 * s))
        door[s].box("mirror_arm", (xa, BELT, -12), (xb, BELT + 1, -11), CHROME)
        xa, xb = sorted((15 * s, 17.25 * s))
        door[s].box("mirror", (xa, BELT + 1, -12.5), (xb, BELT + 2.5, -10.5), CHROME(decal=lambda c: _mirror(c) or ("blade", 3.6 if c.face == "up" else 2.6)))

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        fx, fy = math.floor(x), math.floor(y)
        if c.face == "south":
            if -10 <= x < -3 and fy == 14:
                return ("blade", 4) if fx in (-10, -7, -4) else ("cream", 3.6) if fx in (-9, -6) else ("tint", 0.8)   # two dials
            if 1 <= x < 6 and fy == 14:
                return ("blade", 3.6) if fx % 2 else ("tint", 1)
            if fy == 15:
                return ("blade", 3.2)                                   # a bright strip along the dash
            return ("rubber", 1)
        if c.face == "up":
            return ("rubber", 1.4)
        return ("rubber", 1)
    m.box("dash", (-12.5, 12, -10), (12.5, BELT + 1, -7.5), DARK(decal=dash))
    m.box("column", (-7, 14, -7.5), (-6, 15.5, -6.75), PLASTIC)
    m.box("wheel", (-9, 15, -6.75), (-4, 19, -6.25), Paint("rubber", 1, noise=0, light=False, decal=_steering(("blade", 4))))
    m.box("tunnel", (-1.75, FLOOR, -7.5), (1.75, 7, 5), Paint(T, 1, noise=0, decal=lambda c: ("blade", 3.6) if c.face == "up" and -3 <= c.p[2] < -1 and abs(c.p[0]) < 0.75 else (T, 1.4)))
    m.box("shifter", (-0.5, 7, -2.5), (0.5, 9.5, -1.5), Paint("blade", 3, noise=0, rim=0.3))

    seat = dict(bolster=(T, 2), insert=(T, 1.2), stitch=(C, 2.6))
    for s in (1, -1):
        lo, hi = (min(2.5 * s, 11 * s), FLOOR, 5), (max(2.5 * s, 11 * s), 17.5, 7)
        m.box("seat_back", lo, hi, Paint(T, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        m.box("cushion", (lo[0], FLOOR, -2.5), (hi[0], 6.5, 5), Paint(T, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        xa, xb = sorted((4.5 * s, 9 * s))
        m.box("headrest", (xa, 17.5, 5.5), (xb, 20, 7), Paint(T, 2, noise=0))
    # behind the seats, under the fastback: a carpeted shelf
    m.box("rear_shelf", (-12.5, FLOOR, 7), (12.5, 12, ZB), Paint("rubber", 1, noise=0, decal=lambda c: ("rubber", 1 if math.floor(c.p[2]) % 3 else 0.4) if c.face == "up" else (T, 1)))
    m.display = _held_display(m)
    return kit


def _part(name):
    return lambda: muscle_kit().all()[name]


ALL = {
    "muscle_car": muscle_car,
    "wheel_muscle": wheel_muscle,
    "muscle_car_door_l": _part("muscle_car_door_l"),
    "muscle_car_door_r": _part("muscle_car_door_r"),
}
