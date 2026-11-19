"""
The donation car 콘셉트 EV (content item `concept_ev`): a four-seat electric concept car. An original design: no
real make, no badge, no grille.

The same rules as models/vehicles.py and models/vehicles_premium.py (read their docs first): the body drawn at its
real size (16 px a block), shrunk by 2 and shown at vehicle.body-scale 2; its wheel a model of its own; the parts
that move (the two gull-wing doors) models of their own, drawn in the body's space where they lie at rest (a Kit).
Drawn coordinates: the ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT; the driver sits on the
left (-x).

The shape: a smooth pebble, round in plan (the ends stepped in at the corners), a low nose rising in one line over
the front wheels into a very raked windscreen, a long panoramic glass canopy (screen, roof and rear glass all dark
glass in a thin pearl frame) narrower than the body, so a ledge runs along each shoulder, and a long tail falling
to a short ducktail: a teardrop from the side. The lower body is satin black under a thin accent line all round,
the upper body pearl. A light bar the whole width of the nose (white, glowing) and of the tail (red), each turning
the corners. Flush aero wheels (closed discs with a thin coloured ring).

Paint arguments (one more call = one more colour): colour (the upper body), lower (the satin lower half), accent
(the line round the car, the wheels' rings, the cabin's light strips), trim (the seats' leather).

Its parts: door_l and door_r, gull-wing doors: each the side between the wheels above the sill and its whole side
window, hinged along the roof's edge (vehicles.yml: axis forward, they swing up and out).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets import presets
from models.vehicles import (CLEAR, DARK, PLASTIC, TIRE, lerp, lamp, pane, raw, upholstery, _held_display)
from models.vehicles_premium import Kit, _steering

# the frame (drawn px)
W, L = 16, 39                  # half width, half length
WF, WR, SLOT = -25, 25, 8      # wheel centres (z), half the wells' length
WD, WW, WX = 14, 5, 13.5       # wheel diameter, width, centre x
XI = 10                        # the wells' inner wall, the cabin's half width at the floor
FLOOR, LINE, ARCH, SH = 3.5, 7, 14.5, 15.5      # the cabin floor, the accent line, the wells' top, the shoulder
NOSE = 10                      # the nose's top (the hood rises from it)
CAB = 13                       # the canopy's half width
ZS, ROOF, ZR = -25.5, 26.5, 13  # the screen's foot, the roof's underside, the roof's back edge
KNEE = 24                      # where the windscreen (45 degrees) bends into the roof's curve (22.5 degrees)
DZ = (-17, 17)                 # the doors' lower panel (between the wells); its glass runs ZS..ZR
SILL = 4.5                     # the sill under the doors stays on the body
T22 = math.tan(math.radians(22.5))


def half_w(z):
    """The body's half width at z: round in plan, the ends stepped in."""
    a = abs(z)
    return 12.5 if a > 37 else 14.5 if a > 35 else W


# ------------------------------------------------------------------------------------------------
# The wheel
# ------------------------------------------------------------------------------------------------

def wheel_concept(ring="glow_cyan", disc="pearl", name="wheel_concept"):
    """콘셉트 EV's wheel: 14 px across, 5 px wide, a thin low tyre with closed aero discs flush on both sides: a
    pale disc, softly dished, with six shallow vanes swirling round it (so it is seen to roll), a thin coloured ring
    near its edge, a dark hub cap with a coloured dot."""
    m = BModel(name, shift=(8, 8, 8), floor=None)
    r = WD / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            return ("rubber", 0) if rr > r - 0.9 else ("rubber", 1.4)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * WD * 2.4)
        if abs(c.p[0]) < 0.4:
            return ("rubber", 0)                          # a groove round the middle
        return ("rubber", 1.8 if k % 3 == 1 else 1.0)
    cyl(m, "tire", (0, 0), WD, -WW / 2, WW / 2, TIRE(decal=tire), axis="x")

    def cover(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face not in ("east", "west"):
            return ("rubber", 1)
        if rr > 5.2:
            return ("rubber", 2)                          # the disc's dark edge against the tyre
        if rr > 4.2:
            return (ring, 1.8)                            # the coloured ring
        if rr < 1.3:
            return (ring, 2.0) if rr < 0.6 else ("tint", 0.6)    # the hub cap, a dot of colour in it
        a = math.atan2(z, y)
        # six shallow vanes, swept: a lighter and a darker band each (the disc dished, lit from the upper front)
        t = ((a + rr * 0.35) * 6 / (2 * math.pi)) % 1.0
        lit = 0.4 if (y > 0 and z < 1) else 0
        return (disc, (2.9 if t < 0.5 else 2.3) + lit - (0.5 if rr < 2.2 else 0))
    p = Paint(disc, 2, noise=0, rim=0.2, ao=0.2, decal=cover)
    cyl(m, "cover", (0, 0), 12, WW / 2, WW / 2 + 0.5, p, axis="x")
    cyl(m, "cover", (0, 0), 12, -WW / 2 - 0.5, -WW / 2, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


# ------------------------------------------------------------------------------------------------
# The car
# ------------------------------------------------------------------------------------------------

def concept_ev(colour="pearl", lower="rubber", accent="glow_cyan", trim="porcelain", name="concept_ev"):
    return concept_kit(colour, lower, accent, trim, name).body


def concept_kit(colour="pearl", lower="rubber", accent="glow_cyan", trim="porcelain", name="concept_ev"):
    kit = Kit(name, ("door_l", "door_r"))
    m = kit.body
    door = {-1: kit["door_l"], 1: kit["door_r"]}
    C, B, A = colour, lower, accent
    PAINT = Paint(C, 2, noise=0, grad=0)

    def col(y):
        # the pearl: a soft gradient up the side, a crease along it (a lit edge over a soft shadow)
        fy = math.floor(y)
        if fy == 12:
            return (C, 3.3)
        if fy == 11:
            return (C, 1.7)
        return (C, lerp(y, LINE + 1, SH, 2.0, 2.9))

    def low(y):
        # satin black: a faint sheen towards the line
        return (B, lerp(y, 2, LINE, 0.5, 1.3))

    def well(p):
        return abs(p[0]) > XI - 0.1 and p[1] < ARCH + 0.1 and (abs(p[2] - WF) <= SLOT + 0.1 or abs(p[2] - WR) <= SLOT + 0.1)

    def side(y, z, x=0):
        fy = math.floor(y)
        if abs(x) > W - 0.1 and z > -L and fy >= SILL and math.floor(z) in (DZ[0], DZ[1] - 1):
            return ("tint", 1.0)                         # the doors' seams
        if (z < -31 and 7 <= fy <= 9) or (z > 31 and 11 <= fy <= 13):
            return (B, 0.4 if fy in (7, 11) else 0.9)     # the black visor band the light bars sit in
        if fy == LINE:
            return (A, 1.6)                               # the accent line, all round
        if y < LINE:
            return low(y)
        return col(y)

    def body(c):
        p = c.p
        x, y, z = p
        fy, fz, ax = math.floor(y), math.floor(z), abs(x)
        if c.face == "down":
            return (B, 0)
        if well(p) and c.face != "up":
            return (B, 0)
        if c.face == "up":
            if ax < XI and -24 <= z < 20 and y < FLOOR + 0.1:
                return ("rubber", 1.2 if fz % 4 else 0.8)      # the cabin floor
            if DZ[0] <= z < DZ[1] and y < SILL + 0.1:
                return ("blade", 3.4)                           # the sill plate under the door
            if ax >= CAB:
                return (C, 3.2 if ax < CAB + 1 else 2.8)       # the shoulder ledge beside the canopy
            return (C, 2.8)
        if c.face in ("east", "west"):
            if ax < XI + 0.5 and DZ[0] <= z < DZ[1] and y > SILL - 0.1:
                # the cabin's side, seen when a door is up: pale leather over a bright sill plate
                return ("blade", 3.4) if fy <= math.floor(SILL) + 1 else (trim, 2.2 if fy < 12 else 2.8)
            return side(y, z, x)
        if c.face == "north":
            if z < -L + 0.1 and 3 <= fy <= 4 and ax < 9:
                return (B, 0.2) if fy == 3 else (B, 1.0)      # a thin aero slot low in the nose
            return side(y, z, x)
        if c.face == "south":
            if z > L - 0.1 and fy <= 4 and ax < 11:
                # the diffuser: thin vertical fins
                return (B, 0.3) if math.floor(ax) % 3 else (B, 2.0)
            return side(y, z, x)
        return None
    paint = PAINT(decal=body)

    m.group("body")
    # the middle: the floor, the cabin's ends, the inner walls of the wells
    m.box("floor", (-XI, 2, WF - SLOT), (XI, FLOOR, WR + SLOT), paint)
    m.box("bulkhead", (-XI, FLOOR, WF - SLOT), (XI, 12.4, -24), paint)
    m.box("bulkhead", (-XI, FLOOR, 19), (XI, SH, WR + SLOT), paint)
    # the nose and the tail, stepped in at their corners
    for z0, z1 in ((-L, -37), (-37, -35), (-35, WF - SLOT)):
        hw = half_w((z0 + z1) / 2)
        m.box("nose", (-hw, 2, z0), (hw, NOSE, z1), paint)
    for z0, z1 in ((WR + SLOT, 35), (35, 37), (37, L)):
        hw = half_w((z0 + z1) / 2)
        m.box("tail", (-hw, 2, z0), (hw, SH, z1), paint)
    for s in (1, -1):
        xa, xb = sorted((XI * s, W * s))
        # over the wells: a lid at the arches' top; the sill between them; the door above it (a part)
        m.box("fender", (xa, ARCH, ZS), (xb, SH, WF + SLOT), paint)
        m.box("fender", (xa, ARCH, WR - SLOT), (xb, SH, WR + SLOT), paint)
        m.box("sill", (xa, 2, DZ[0]), (xb, SILL, DZ[1]), paint)
        door[s].box("door", (xa, SILL, DZ[0]), (xb, SH, DZ[1]), paint)

    # the arches: steps in their upper corners round the square wells off (none at the front well's front edge:
    # the hood's plane comes down there)
    def arch(c):
        return (B, 0) if c.face in ("down", "north", "south") else side(c.p[1], c.p[2], c.p[0])
    ap = PAINT(decal=arch)
    for s in (1, -1):
        xa, xb = sorted(((XI + 0.5) * s, W * s))
        for wz in (WF, WR):
            for k in (-1, 1):
                edge = wz + k * SLOT
                steps = ((2, 12.5, ARCH), (1, 11, 12.5)) if (wz, k) != (WF, -1) else ((1.5, 10.5, 12.3),)
                for deep, lo, hi in steps:
                    z0, z1 = sorted((edge, edge - k * deep))
                    m.box("arch", (xa, lo, z0), (xb, hi, z1), ap)

    # the hood: one plane from the nose's top over the front wheels to the screen's foot (22.5 degrees)
    run = ZS + L
    hl = run / math.cos(math.radians(22.5))

    def hood(c):
        if c.face == "up":
            ax = abs(c.p[0])
            if ax < 0.5:
                return (C, 3.4)                                # a fine crease down the middle
            if CAB - 1 <= ax < CAB:
                return (C, 3.4)                                # it carries on as the canopy's line
            return (C, 3.0 if 4 <= ax < 7 else 2.6)            # a reflection band
        return (C, 1.6)
    hp = PAINT(decal=hood)
    rot = ("x", -22.5, (0, NOSE, -L))
    m.box("hood", (-12.5, NOSE - 1, -L), (12.5, NOSE, -L + hl), hp, rotation=rot)
    for s in (1, -1):
        xa, xb = sorted((12.5 * s, 14.5 * s))
        m.box("hood_side", (xa, NOSE - 1, -37), (xb, NOSE, -L + hl), hp, rotation=rot)
        xa, xb = sorted((14.5 * s, W * s))
        m.box("hood_side", (xa, NOSE - 1, -35), (xb, NOSE, -L + hl), hp, rotation=rot)
        # under it, the steps up to the plane at the corners (over the wells it is the fender itself)
        xa, xb = sorted((12.5 * s, 14.5 * s))
        m.box("hood_fill", (xa, NOSE, -37), (xb, NOSE + 2 * T22 - 0.1, -35), paint)
        xa, xb = sorted((XI * s, W * s))
        m.box("hood_fill", (xa, NOSE, -35), (xb, NOSE + 4 * T22 - 0.1, WF - SLOT), paint)

    # the tail: under the fastback, a filler from the shoulder, a ducktail lip at its end
    m.box("tail_fill", (-CAB + 0.5, SH, 19), (CAB - 0.5, 19.2, 30), paint)
    m.box("tail_fill", (-CAB + 0.5, SH, 30), (CAB - 0.5, 17.4, 34.5), paint)

    def lip(c):
        return (C, 3.4) if c.face == "up" else (C, 2.4) if c.face == "south" else (C, 2.0)
    m.box("ducktail", (-12, SH, L - 2), (12, SH + 1.2, L), PAINT(decal=lip))

    # ---- lights ----
    m.group("lights")

    def front_bar(c):
        # a white bar of light: a bright core, a softer line under it
        if c.face in ("up", "down"):
            return ("porcelain", 2)
        return ("lamp", 4.9) if c.p[1] >= 8.5 else ("porcelain", 3.6)

    def rear_bar(c):
        if c.face in ("up", "down"):
            return ("signal_red", 0)
        return ("signal_red", 2.4) if c.p[1] >= 12.5 else ("signal_red", 1.2)
    fl = Paint("lamp", 3, decal=front_bar, **lamp())
    rl = Paint("signal_red", 1, decal=rear_bar, **lamp())
    YF, YR = 7.75, 11.75
    m.box("light_bar", (-12.5, YF, -L - 0.25), (12.5, YF + 2, -L), fl)
    m.box("light_bar", (-14.5, YF, -37.25), (14.5, YF + 2, -37), fl(decal=lambda c: front_bar(c) if c.face == "north" else ("porcelain", 2)))
    m.box("tail_bar", (-12.5, YR, L), (12.5, YR + 2, L + 0.25), rl)
    m.box("tail_bar", (-14.5, YR, 37), (14.5, YR + 2, 37.25), rl(decal=lambda c: rear_bar(c) if c.face == "south" else ("signal_red", 0)))
    for s in (1, -1):
        # round the corners onto the sides
        xa, xb = sorted((W * s, (W + 0.25) * s))
        m.box("light_wrap", (xa, YF, -35), (xb, YF + 2, -33.5), fl)
        m.box("tail_wrap", (xa, YR, 33.5), (xb, YR + 2, 35), rl)

    # ---- the canopy: windscreen, panoramic roof, fastback, all dark glass in a thin pearl frame ----
    m.group("canopy")
    rise = (KNEE - SH) * math.sqrt(2)
    m.box("windscreen", (-CAB, SH, ZS), (CAB, SH + rise, ZS + 0.5),
          glass_pane(C, sun=0, streak=0.3), rotation=("x", 45, (0, SH, ZS)))
    zk = ZS + (KNEE - SH)                                    # the knee
    z_top = zk + (ROOF + 1 - KNEE) / T22                     # where the curve reaches the roof

    def brow(c):
        # the roof's front curve: more of the dark glass, framed at its sides
        x = c.p[0]
        ax = abs(x)
        if c.face == "up":
            if ax >= CAB - 1:
                return (C, 3.4) if ax < CAB - 0.5 else (A, 1.6)
            return ("glass", 2.6) if c.v - math.floor(x) in (4,) else ("tint", 1.1 if ax < 6 else 0.8)
        if c.face == "down":
            return ("tint", 0.4)
        return (C, 2.4)
    m.box("brow", (-CAB, KNEE - 1, zk), (CAB, KNEE, zk + (ROOF + 1 - KNEE) / math.sin(math.radians(22.5)) + 0.3),
          PAINT(decal=brow), rotation=("x", -22.5, (0, KNEE, zk)))

    def roof(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face == "up":
            if ax >= CAB - 1 or z >= ZR - 0.5:
                return (C, 3.4) if ax < CAB - 0.5 else (A, 1.6)   # the frame; an accent line along each edge
            d = math.floor(z) - math.floor(x)
            if d in (2,):
                return ("glass", 2.6)                     # the reflection on the glass
            return ("tint", 1.1 if ax < 6 else 0.8)
        if c.face == "down":
            return ("tint", 0.4)
        return (C, 2.4)
    m.box("roof", (-CAB, ROOF, z_top), (CAB, ROOF + 1, ZR), PAINT(decal=roof))

    fb_len = (L - 0.5 - ZR) / math.cos(math.radians(22.5))

    def fastback(c):
        x = c.p[0]
        ax = abs(x)
        if c.face == "up":
            if ax >= CAB - 1:
                return (C, 3.2)
            if c.v < 13:
                if c.v == 12:
                    return (C, 3.0)                       # the glass ends in a pearl line
                d = c.v - math.floor(x)
                return ("glass", 2.6) if d in (4,) else ("tint", 1.1 if ax < 6 else 0.8)     # the rear glass
            return (C, 2.8 if 4 <= ax < 7 else 2.4)
        if c.face == "down":
            return ("tint", 0.4)
        return (C, 2.0)
    m.box("fastback", (-CAB, ROOF, ZR), (CAB, ROOF + 1, ZR + fb_len), PAINT(decal=fastback),
          rotation=("x", 22.5, (0, ROOF + 1, ZR)))

    def quarter(c):
        # the rear side glass, under the fastback's line, ending in pearl over the rear wheel
        x, y, z = c.p
        top = ROOF + 1 - (z - ZR) * T22
        if y > top - 0.6:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw(C, 2)
        if z >= 27 or y < SH + 1:
            return raw(C, 3 if y > top - 1.6 else 2)
        if z < ZR + 1 or z >= 26 or y > top - 1.6:
            return raw(C, 3)
        return raw("glass", 3) if math.floor(z) - math.floor(y) in (-4, -3) else raw("tint", 1)
    for s in (1, -1):
        xa, xb = sorted(((CAB - 0.5) * s, CAB * s))
        m.box("quarter", (xa, SH, ZR), (xb, ROOF + 1, L - 0.5), Paint(C, 2, noise=0, light=False, decal=quarter))

    # ---- inside ----
    m.group("cabin")

    def dash(c):
        x, y, z = c.p
        if c.face == "south":
            fy = math.floor(y)
            if fy == 11 and -8.5 <= x < 8.5:
                # one wide floating display: a lit strip with readouts
                fx = math.floor(x)
                return (A, 2.0) if fx in (-7, -6, 2, 5) else ("tint", 1.6)
            if fy == 10:
                return (A, 1.2)                           # a light strip under it
            return (trim, 2.0)
        if c.face == "up":
            return ("rubber", 1.2)
        return (trim, 1.8)
    m.box("dash", (-XI, 9, -25), (XI, 12.5, -21), Paint(trim, 2, noise=0, decal=dash))
    m.box("display", (-8.5, 12.5, -22.5), (8.5, 14, -22), Paint("tint", 1, noise=0,
          decal=lambda c: (A, 2.2) if c.face == "south" and c.v == 1 and c.u % 3 else ("tint", 0.8)))
    m.box("column", (-6, 10.5, -21), (-5, 11.5, -19.5), PLASTIC)
    m.box("yoke", (-8, 10, -19.5), (-3, 13.5, -19), Paint("rubber", 1, noise=0, light=False, decal=_steering((A, 2.0))))

    def console(c):
        if c.face == "up":
            z = math.floor(c.p[2])
            return (A, 2.0) if z in (-16, -10) and abs(c.p[0]) < 1 else ("rubber", 1.6)
        return (trim, 1.6)
    m.box("console", (-1.5, FLOOR, -21), (1.5, 7.5, -4), Paint(trim, 2, noise=0, decal=console))

    seat = dict(bolster=(trim, 2.6), insert=(trim, 1.6), stitch=(A, 1.6))
    for z_back in (-5.5, 11.5):
        for s in (1, -1):
            lo, hi = (min(1.75 * s, 9.25 * s), FLOOR, z_back), (max(1.75 * s, 9.25 * s), 15, z_back + 2)
            m.box("seat_back", lo, hi, Paint(trim, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
            m.box("cushion", (lo[0], FLOOR, z_back - 7.5), (hi[0], 6, z_back), Paint(trim, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
            xa, xb = sorted((3.5 * s, 7.5 * s))
            m.box("headrest", (xa, 15, z_back + 0.5), (xb, 17.5, z_back + 2), Paint(trim, 2, noise=0, rim=0.4))

    # ---- the doors: the side glass and the camera stalks that stand in for mirrors ----
    def side_window(c):
        x, y, z = c.p
        front = ZS + (y - SH) if y <= KNEE else zk + (y - KNEE) / T22
        if z < front - 0.3:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw(C, 2)
        if z < front + 1.2 or y > ROOF - 0.6 or z >= ZR - 1:
            return raw(C, 3 if y > ROOF - 0.6 else 2)        # the slim frame (the pillars are glass)
        if y < SH + 0.6:
            return raw(A, 2)                                # the accent along the glass's foot
        if z < front + 2 or y > ROOF - 1.4 or z >= ZR - 2 or y < SH + 1.4:
            return raw("tint", 1)
        d = math.floor(z) - math.floor(y)
        if d in (-29, -28, -12) and z > front + 3:
            return raw("glass", 3)
        return ("raw", CLEAR)
    wp = Paint("glass", 2, noise=0, light=False, decal=side_window)
    for s in (1, -1):
        xa, xb = sorted(((CAB - 0.5) * s, CAB * s))
        door[s].box("side_window", (xa, SH, ZS), (xb, ROOF, ZR), wp)
        # the door's inner skin (seen when it is up): a pale panel with a light strip
        xa, xb = sorted(((XI - 0.5) * s, XI * s))
        door[s].box("door_card", (xa, SILL + 1, DZ[0] + 1), (xb, SH, DZ[1] - 1),
                    Paint(trim, 2, noise=0, decal=lambda c: (A, 1.8) if math.floor(c.p[1]) == 12 else (trim, 2.2)))
        # a slim camera pod on a stalk ahead of the door glass
        xa, xb = sorted((15 * s, 15.75 * s))
        door[s].box("camera_stalk", (xa, SH, -16.5), (xb, SH + 1.5, -15.5), Paint(B, 1, noise=0, rim=0.3))
        xa, xb = sorted((15 * s, 17.5 * s))
        door[s].box("camera", (xa, SH + 1.5, -17), (xb, SH + 2.75, -15),
                    Paint(B, 1, noise=0, rim=0.3, decal=lambda c: (A, 2.0) if c.face == "north" and c.u == c.nu // 2 else (B, 1.2)))

    m.display = _held_display(m)
    return kit


def glass_pane(colour, **kw):
    """The windscreen: dark-edged glass in a thin pearl frame."""
    return Paint("glass", 2, noise=0, light=False, decal=lambda c: pane(c, frame=(colour, 3), **kw))


def _part(name):
    return lambda: concept_kit().all()[name]


ALL = {
    "concept_ev": concept_ev,
    "wheel_concept": wheel_concept,
    "concept_ev_door_l": _part("concept_ev_door_l"),
    "concept_ev_door_r": _part("concept_ev_door_r"),
}
