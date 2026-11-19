"""
후원 탈것 · 클래식 로드스터 (classic_roadster): a two-seat open convertible in the style of the 1950s. An original
design: no real make, model or badge.

The same rules as models/vehicles.py and models/vehicles_premium.py (read their docs first): the body drawn at its
real size (16 px a block, the vehicle's ground centre at (0, 0, 0), front = north (-z), +x = the vehicle's RIGHT
side, the driver on the left (-x)), shrunk by 2 and shown at vehicle.body-scale 2; its wheel a model of its own; the
doors parts of a Kit (drawn where they lie shut, in the body's own space; the plugin turns them about their hinge).

The look: long rounded front wings that stand a little proud of the body and run back into the doors, a round
headlamp in a chrome bezel in the face of each wing, a low hood with a chrome spine between them, a wide chrome
grille of upright bars low in the nose, a chrome bumper with two bullet guards at each end. No roof: a low wrap-round
windscreen in a chrome frame, a bench seat of red leather with pale piping, a big thin steering wheel, a painted dash
with two chrome-ringed dials, and behind the seat the folded soft top lying on the deck. Rear wings rise a little
over the deck and end in round red lamps. Two-tone paint split along a thin chrome side spear: the colour below it,
the upper colour above (wing crowns, hood, deck, door tops). Whitewall tyres, chrome hub caps with a two-eared spinner.

Paint (tuning): colour = the lower colour (the tuning shop's paint), upper = the upper tone, leather = the seats and
door cards, soft = the folded soft top's canvas.

Models (ALL): classic_roadster (body), classic_roadster_door_l / _door_r (parts: each hinged at its front edge,
outer face), wheel_classic (12 px across, 4 px wide, whitewall, chrome cap and spinner).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets import presets
from models.vehicles import CLEAR, CHROME, PLASTIC, lerp, lamp, raw, upholstery, glass, _held_display
from models.vehicles_premium import Kit

NAME = "classic_roadster"

# ------------------------------------------------------------------------------------------------
# The frame (drawn px)
# ------------------------------------------------------------------------------------------------
L = 35                 # half length (4.375 blocks)
W = 15                 # the wings' outer face (2.0 blocks with the hub caps' spinners inside it)
WS = 14.5              # the doors' and the sides' outer face
XI = 8.5               # the wells' inner wall, the cabin's half width
WF, WR = -21, 21       # the wheels' middle (z)
SLOT = 7.5             # the arches' half length
WHEEL_D, WHEEL_X = 12, 12
ARCH = 13              # the arches' top
SILL, FLOOR, BELT = 3, 4, 12.5
SPEAR = 9              # the chrome side spear (and the split of the two tones)
DZ = (-11, 3)          # the doors, between their seams
SEAT_TOP = 8           # the bench's cushion


# ================================================================================================
# The wheel
# ================================================================================================

def wheel_classic():
    """A whitewall tyre 12 px across, 4 px wide: black tread, a wide white band round a thin black bead, a polished
    dished hub cap with a two-eared spinner standing out of its middle (on both sides: the same model turns on both
    sides of the car)."""
    m = BModel("wheel_classic", shift=(8, 8, 8), floor=None)
    d, width = WHEEL_D, 4
    r = d / 2
    cap_d = 5.5

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 1.3:
                return ("rubber", 0.6)
            if rr > r - 2.6:
                return ("cream", 3.4 if (y > 0 and z < 0) else 2.8)      # the white wall
            return ("rubber", 1)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.4)
        return ("rubber", 0) if k % 3 == 0 else ("rubber", 1.5 if k % 3 == 1 else 1)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("rubber", 1, noise=0, rim=0.3, decal=tire), axis="x")

    def hub(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face not in ("east", "west"):
            return ("blade", 2)
        if rr > cap_d / 2 - 0.8:
            return ("blade", 4.4)                                       # the bright rim of the cap
        if rr > 1.0:
            return ("blade", 3.4 if (y > 0 and z < 0.5) else 2.4)       # the dished cap, lit on its upper front
        return ("blade", 4.0)
    p = Paint("blade", 3, noise=0, rim=0.2, ao=0.3, decal=hub)
    w2 = width / 2
    cyl(m, "hub", (0, 0), cap_d, w2, w2 + 0.5, p, axis="x")
    cyl(m, "hub", (0, 0), cap_d, -w2 - 0.5, -w2, p, axis="x")

    # the spinner: a bright boss and a bar with an ear each end, out of each cap
    def spin(c):
        return ("blade", 4.8 if c.face in ("east", "west", "up") else 3.2)
    sp = Paint("blade", 4, noise=0, rim=0.3, decal=spin)
    for s in (1, -1):
        xa, xb = sorted(((w2 + 0.5) * s, (w2 + 1.25) * s))
        m.box("spinner_boss", (xa, -1, -1), (xb, 1, 1), sp)
        xa, xb = sorted(((w2 + 0.5) * s, (w2 + 1.0) * s))
        m.box("spinner_bar", (xa, -0.5, -2.25), (xb, 0.5, 2.25), sp)
    m.display = presets.furniture(gui_scale=0.6)
    return m


# ================================================================================================
# The body
# ================================================================================================

def classic_roadster(colour="ruby", cove="cream", leather="crimson", soft="rubber", name=NAME):
    return roadster_kit(colour, cove, leather, soft, name).body


def roadster_kit(colour="ruby", cove="cream", leather="crimson", soft="rubber", name=NAME):
    kit = Kit(name, ("door_l", "door_r"))
    m = kit.body
    door = {-1: kit["door_l"], 1: kit["door_r"]}
    C, U = colour, cove
    PAINT = Paint(C, 2, noise=0, grad=0)

    def low(y):
        return (U, lerp(y, 3, SPEAR, 1.2, 2.6))                        # the cove under the spear

    def high(y):
        return (C, lerp(y, SPEAR + 1, 14.5, 0.9, 1.8))

    def well(p):
        x, y, z = p
        return abs(x) > XI - 0.1 and y < ARCH + 0.1 and (abs(z - WF) <= SLOT + 0.1 or abs(z - WR) <= SLOT + 0.1)

    def side(c):
        """the outside of a side: the two tones split by the chrome spear, the seams, the handle"""
        x, y, z = c.p
        fy, fz = math.floor(y), math.floor(z)
        if fy < SILL + 1 and c.face in ("east", "west"):
            return (U, 0.6)                                             # the rocker's shadow
        if fz in (DZ[0], DZ[1] - 1) and SILL < fy < BELT:
            return (U, 0.4) if y < SPEAR else (C, 0.2)                  # the doors' seams
        if fy == SPEAR:
            # the spear: from the front wheel's arch back to the rear one, tapering to a point at its back
            if WF + SLOT + 0.5 <= z < WR - SLOT - 1.5:
                return ("blade", 4.4 if c.face in ("east", "west") else 3.0)
            return low(y)
        if fy == SPEAR + 1 and WR - SLOT - 3.5 <= z < WR - SLOT - 1.5:
            return ("blade", 4.0)                                       # the spear's tip, swept up
        if fy == 11 and DZ[1] - 3 <= z < DZ[1] - 1:
            return ("blade", 4.6)                                       # the door handle (a push button)
        if fy == BELT - 1 and DZ[0] <= z < DZ[1] + 10:
            return (C, 2.2)                                             # light along the door top
        return low(y) if y < SPEAR else high(y)

    def body(c):
        p = c.p
        x, y, z = p
        fx, fy, fz, ax = math.floor(x), math.floor(y), math.floor(z), abs(x)
        if c.face == "down":
            return ("rubber", 0)
        if well(p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "up":
            if y < SPEAR:
                return (U, 2.6)
            if ax < XI and DZ[0] <= z < 5.5 and y < 5:
                return (leather, 0.8 if fz % 3 else 0.4)               # the carpet
            if ax < 0.5 and z < -12:
                return ("blade", 4.6)                                   # the hood's chrome spine
            if ax < XI and z > 5.5 and fz == 30 and ax < 6:
                return (C, 0.8)                                         # the boot lid's seam
            return (C, 2.6 if ax >= 11 and ax < 13 else 2.0)            # light along the wings' crowns
        if c.face in ("east", "west"):
            if ax < XI + 0.6 and DZ[0] <= z < 5.5:
                return (leather, 1.6 if fy > FLOOR else 0.6)            # the cabin's side (a door stands open)
            return side(c)
        if c.face == "north":
            if y >= SPEAR:
                return high(y)
            return low(y)
        if c.face == "south":
            if z > L - 0.1 and ax < 4 and 6 <= fy <= 9:
                return (U, 0.5)                                         # the plate's recess
            if y >= SPEAR:
                return high(y)
            return low(y)
        return None
    paint = PAINT(decal=body)

    m.group("body")
    # the middle: the engine bay under the hood, the cabin's floor, the deck and the boot
    m.box("core", (-XI, SILL, -33), (XI, BELT, DZ[0] - 2.5), paint)
    m.box("floor", (-XI, SILL, DZ[0] - 2.5), (XI, FLOOR, 5.5), paint)
    m.box("deck", (-XI, SILL, 5.5), (XI, 12, 33), paint)
    m.box("tail_tip", (-XI + 1, SILL, 33), (XI - 1, 11, L), paint)
    m.box("tail_tip", (-XI + 1, 11, 33), (XI - 1, 11.5, 34.5), paint)
    # the hood: low between the wings, a rounded crown, the nose dropping to the grille
    m.box("hood", (-XI, BELT, -33), (XI, 13.5, DZ[0] - 2.5), paint)
    m.box("hood_crown", (-6, 13.5, -31), (6, 14, DZ[0] - 2.5), paint)
    m.box("nose", (-XI, 10, -L), (XI, 12.5, -33), paint)
    m.box("nose", (-XI, 12.5, -34.5), (XI, 13, -33), paint)
    m.box("valance", (-XI, SILL, -34.5), (XI, 4.5, -33), paint)
    # the cowl in front of the screen
    m.box("cowl", (-XI, BELT, DZ[0] - 2.5), (XI, 13.5, DZ[0]), paint)

    for s in (1, -1):
        def X(a, b):
            return tuple(sorted((a * s, b * s)))
        xa, xb = X(XI, WS)
        # front wing: its face (the lamp in it), over the arch, its tail sweeping down into the door
        m.box("wing_face", (xa, SILL + 1, -L), (xb, 12.5, -33), paint)
        m.box("wing_front", (xa, SILL, -33), (xb, 13.5, WF - SLOT), paint)
        m.box("wing_front", (xa, 13.5, -31.5), (xb, 14.5, WF - SLOT), paint)
        m.box("wing_crown", (xa, ARCH, WF - SLOT), (xb, 14.5, WF + SLOT), paint)
        m.box("wing_tail", (xa, SILL, WF + SLOT), (xb, 13.5, DZ[0] - 1), paint)
        m.box("wing_tail", (xa, SILL, DZ[0] - 1), (xb, 13, DZ[0]), paint)
        m.box("wing_tail", (xa, 13.5, WF + SLOT), (xb, 14, WF + SLOT + 1.5), paint)
        # the door (a part)
        door[s].box("door", (xa, SILL, DZ[0]), (xb, BELT, DZ[1]), paint)
        # the rear quarter rising into the rear wing, the wing over the arch, its end
        m.box("quarter", (xa, SILL, DZ[1]), (xb, BELT, WR - SLOT), paint)
        m.box("quarter", (xa, BELT, 8), (xb, 13, WR - SLOT), paint)
        m.box("wing_crown", (xa, ARCH, WR - SLOT), (xb, 14, WR + SLOT), paint)
        m.box("wing_rear", (xa, SILL, WR + SLOT), (xb, 13.5, 33), paint)
        m.box("wing_end", (xa, SILL + 1, 33), (xb, 12, L), paint)
        # the chrome spear, standing a quarter pixel proud: on the wing's tail, the door (a part) and the quarter
        xa, xb = X(WS, WS + 0.25)
        sp = CHROME(decal=lambda c: ("blade", 4.8 if c.face in ("east", "west", "up") else 3.0))
        m.box("spear", (xa, SPEAR, WF + SLOT + 0.5), (xb, SPEAR + 0.75, DZ[0]), sp)
        door[s].box("spear", (xa, SPEAR, DZ[0]), (xb, SPEAR + 0.75, DZ[1]), sp)
        m.box("spear", (xa, SPEAR, DZ[1]), (xb, SPEAR + 0.75, WR - SLOT - 1.5), sp)
        # the wings stand half a pixel proud of the sides: a pontoon over each wheel
        xa, xb = X(XI + 1, W - 1)
        m.box("wing_bead", (xa, 14.5, -30.5), (xb, 15, WF + SLOT), paint)
        m.box("wing_bead", (xa, 14, WR - SLOT), (xb, 14.5, WR + SLOT + 2), paint)
        xa, xb = X(WS, W)
        m.box("wing_flare", (xa, 9.5, -33), (xb, 14, WF + SLOT + 1.5), paint)
        m.box("wing_flare", (xa, 9.5, WR - SLOT - 1.5), (xb, 13.5, 33), paint)
        # the arches: two steps in each upper corner round the square opening
        xa, xb = X(XI, W)
        for wz in (WF, WR):
            for e in (-1, 1):
                edge = wz + e * SLOT
                for deep, lo, hi in ((2, 11.5, ARCH), (1, 10, 11.5)):
                    if wz == WR and e == 1 or wz == WF and e == -1:
                        x0, x1 = X(XI, W - 0.03)
                    else:
                        x0, x1 = X(XI, WS)
                    z0, z1 = sorted((edge, edge - e * deep))
                    m.box("arch", (x0, lo, z0), (x1, hi, z1), PAINT(decal=lambda c: ("rubber", 0) if c.face == "down" else (C, 0.5) if c.face in ("north", "south") else side(c)))

    # chrome: the grille, the bumpers with their bullet guards, the hood's mascot, a mirror, an exhaust
    def grille(c):
        if c.face != "north":
            return ("blade", 3.0)
        if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return ("blade", 4.8)                                       # the surround
        if c.v == c.nv // 2:
            return ("blade", 3.6)                                       # a bar across the middle
        return ("blade", 4.2 if c.u % 2 else 1.0) if c.u % 2 else ("rubber", 0)      # upright bars on black
    m.box("grille", (-7.5, 4.5, -L - 0.5), (7.5, 10, -33), Paint("blade", 3, noise=0, decal=grille))

    def bumper(c):
        if c.face in ("north", "south"):
            return ("blade", 4.6 if c.v == 0 else 3.4 if c.v < 2 else 2.2)
        if c.face == "up":
            return ("blade", 4.8)
        return ("blade", 2.6)
    bp = Paint("blade", 3, noise=0, rim=0.4, decal=bumper)
    for zf, zb in ((-L - 2, -L), (L, L + 2)):
        m.box("bumper", (-13.5, 3.5, zf), (13.5, 5.5, zb), bp)
        for s in (1, -1):
            xa, xb = sorted((13.5 * s, 14.75 * s))
            m.box("bumper_end", (xa, 3.5, zf + (0.75 if zf < 0 else 0)), (xb, 5.5, zb - (0 if zf < 0 else 0.75)), bp)
            xa, xb = sorted((5 * s, 6.5 * s))
            zg = (zf - 0.75, zf) if zf < 0 else (zb, zb + 0.75)
            m.box("bullet", (xa, 4, zg[0]), (xb, 7.5, zg[1]), bp)
            m.box("guard", (xa, 5.5, zf), (xb, 8, zb), bp)

    m.box("mascot", (-0.5, 14, -32.5), (0.5, 15, -29), CHROME(decal=lambda c: ("blade", 4.8 if c.face in ("up", "west") else 3.4)))
    m.box("mascot_fin", (-0.25, 15, -30.5), (0.25, 15.75, -29), CHROME)
    m.box("mirror_stem", (-12.5, 14.5, -17), (-11.5, 16, -16), CHROME)
    m.box("mirror", (-13.5, 16, -17.25), (-10.5, 18, -16), CHROME(decal=lambda c: ("glass", 3) if c.face == "south" and 0 < c.u < c.nu - 1 else ("blade", 4)))
    m.box("exhaust", (6, 2.5, L - 2), (8, 3.5, L + 2.5), CHROME(decal=lambda c: ("rubber", 0) if c.face == "south" else None))

    m.group("lights")

    def round_lamp(cx, cy, core, lens="lamp", hot=4.8, mid=3.4):
        def decal(c):
            if c.face not in ("north", "south"):
                return ("blade", 3.4)
            rr = math.hypot(abs(c.p[0]) - cx, c.p[1] - cy)
            return (lens, hot) if rr < core else (lens, mid) if rr < core + 1 else ("blade", 4.6)
        return decal
    for s in (1, -1):
        # headlamps: a lens in a deep chrome bezel in each wing's face; a small amber lamp under it
        cyl(m, "headlight", (12 * s, 9.5), 5, -L - 0.75, -L, Paint("lamp", 3, decal=round_lamp(12, 9.5, 1.2), **lamp()), axis="z")
        xa, xb = sorted((11 * s, 13 * s))
        m.box("turn_lamp", (xa, 5.5, -L - 0.4), (xb, 6.5, -L), Paint("fox", 3, noise=0, light=False, glow=True))
        # tail lamps: round red lenses in chrome on the wings' ends
        cyl(m, "tail_lamp", (12 * s, 9.5), 3, L, L + 0.6,
            Paint("signal_red", 1, decal=round_lamp(12, 9.5, 0.6, "signal_red", 2.2, 1.2), **lamp()), axis="z")

    m.group("cabin")
    # the low wrap-round windscreen in a chrome frame: the front pane laid back, a short side pane each end
    SY = BELT + 1                       # its foot
    SH = 6                              # its height
    rake = ("x", 22.5, (0, SY, DZ[0] - 1))
    m.box("screen_base", (-10, BELT + 0.5, DZ[0] - 1.75), (10, SY + 0.05, DZ[0] - 0.5), CHROME)
    m.box("windscreen", (-9.5, SY, DZ[0] - 1.25), (9.5, SY + SH, DZ[0] - 0.75),
          glass(frame=("blade", 4), sun=0, streak=0.3), rotation=rake)
    m.box("screen_top", (-9.5, SY + SH, DZ[0] - 1.5), (9.5, SY + SH + 0.5, DZ[0] - 0.5), CHROME, rotation=rake)
    for s in (1, -1):
        xa, xb = sorted((9.5 * s, 10.25 * s))
        m.box("screen_post", (xa, BELT + 0.5, DZ[0] - 1.5), (xb, SY + SH + 0.5, DZ[0] - 0.5), CHROME, rotation=rake)
        xa, xb = sorted((9.75 * s, 10.25 * s))
        m.box("side_screen", (xa, SY, DZ[0] + 1), (xb, SY + SH - 1.5, DZ[0] + 4),
              glass(frame=("blade", 4), sun=0, streak=0.5))

    def dash(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "south":
            for cx in (-7, -4):
                rr = math.hypot(x - cx, y - 10.5)
                if rr < 1.1:
                    return ("cream", 4)                                 # two pale dials
                if rr < 1.8:
                    return ("blade", 4.4)                               # in chrome rings
            if fy == 12:
                return (U, 3.0)
            if -1 <= x < 2 and 9 <= fy <= 10:
                return ("blade", 3.8 if fy == 10 else 2.6)              # the radio's chrome face
            return (C, 2.0)
        if c.face == "up":
            return (C, 1.6)                                             # a painted dash
        return (C, 1.2)
    m.box("dash", (-XI, 8, DZ[0]), (XI, 13, DZ[0] + 1.5), Paint(C, 2, noise=0, decal=dash))
    m.box("column", (-6, 10.5, DZ[0] + 1.5), (-5, 12, -7.5), PLASTIC)

    def steering(c):
        # a big thin ivory rim with three spokes to a chrome boss
        u, v = c.u, c.v
        if c.face not in ("north", "south"):
            return raw("cream", 3)
        if u in (0, c.nu - 1) or v in (0, c.nv - 1):
            if (u in (0, c.nu - 1)) and (v in (0, c.nv - 1)):
                return ("raw", CLEAR)                                   # its corners: round
            return raw("cream", 3 if v == 0 else 2)
        if v == c.nv // 2 - 1 and 2 <= u < c.nu - 2:
            return raw("blade", 4) if u in (c.nu // 2 - 1, c.nu // 2) else raw("blade", 3)
        if u in (c.nu // 2 - 1, c.nu // 2) and v > c.nv // 2 - 1:
            return raw("blade", 3)
        return ("raw", CLEAR)
    m.box("steering_wheel", (-8.5, 11, -7.5), (-2.5, 17, -7), Paint("cream", 3, noise=0, light=False, decal=steering))

    # the bench: one cushion and one back across the car, pale piping; a roll along the back's top
    seat = dict(bolster=(leather, 2.2), insert=(leather, 1.6), stitch=("cream", 3))
    lo, hi = (-XI, FLOOR, 3), (XI, 14.5, 5.5)
    m.box("seat_back", lo, hi, Paint(leather, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    m.box("seat_roll", (-XI + 0.5, 14.5, 3.5), (XI - 0.5, 15.25, 5.5), Paint(leather, 2, noise=0, rim=0.5))
    lo2, hi2 = (-XI, FLOOR, -4), (XI, SEAT_TOP, 3)
    m.box("cushion", lo2, hi2, Paint(leather, 2, noise=0, decal=upholstery(lo=lo2, hi=hi2, **seat)))

    # the door cards (parts): leather, a chrome strip along the top
    def card(c):
        if c.face == "up":
            return (C, 2.6)
        fy = math.floor(c.p[1])
        if fy == BELT - 1:
            return ("blade", 4.0)
        return (leather, 1.8 if fy % 2 else 1.4)
    for s in (1, -1):
        xa, xb = sorted(((XI - 0.02) * s, (XI + 0.5) * s))
        door[s].box("door_card", (xa, FLOOR, DZ[0] + 1.5), (xb, BELT - 0.03, DZ[1] - 0.03), Paint(leather, 2, noise=0, decal=card))

    # the folded soft top lying on the deck behind the seat: a canvas roll under a boot, its bows' ends in chrome
    def roll(c):
        x, y, z = c.p
        if c.face == "up":
            return (soft, 2.4 if math.floor(z) % 2 else 1.8)
        if c.face in ("north", "south"):
            return (soft, 1.6 if math.floor(y) % 2 else 1.1)            # its folds
        return (soft, 1.4)
    rp = Paint(soft, 2, noise=0, rim=0.4, decal=roll)
    m.box("soft_top", (-11, 12, 6), (11, 14.25, 10.5), rp)
    m.box("soft_top", (-10.5, 14.25, 6.5), (10.5, 15, 10), rp)
    for s in (1, -1):
        xa, xb = sorted((11 * s, 11.5 * s))
        m.box("bow_end", (xa, 12.5, 7.5), (xb, 14, 9), CHROME)

    m.display = _held_display(m)
    return kit


def _part(part):
    return lambda: roadster_kit().all()[f"{NAME}_{part}"]


ALL = {
    NAME: classic_roadster,
    "wheel_classic": wheel_classic,
    **{f"{NAME}_{p}": _part(p) for p in ("door_l", "door_r")},
}
