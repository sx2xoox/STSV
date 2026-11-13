"""
The premium cars (content/stsv/items/vehicles.yml, handed out by an administrator): 하이퍼카 (a low mid-engined
coupe), 그랜드 세단 (a long, upright limousine) and 럭셔리 SUV (a tall, square off-roader). All original designs:
no real make, no badge.

The same rules as models/vehicles.py (read its doc first): a body model drawn at its real size (16 px a block,
shrunk by 2 and shown at vehicle.body-scale 2), its wheel a model of its own. Drawn coordinates: the vehicle's
ground centre at (0, 0, 0), front = north (-z), +x = the vehicle's RIGHT side; the driver sits on the left (-x).

Tuning in mind, like the event cars: every body takes its paint (and what goes with it) as arguments, so another
colour is one more call (a model each); the wheels are models of their own.

Imported at the end of models/vehicles.py (its ALL carries these): import that module, not this one.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl
from models.vehicles import (CLEAR, CHROME, DARK, PLASTIC, lerp, lamp, plate, pane, glass, raw, upholstery, bumper_decal,
                             _held_display, _wheel)


def _weave(x, z, lo=1.7, hi=2.5):
    """carbon fibre: a fine checked weave"""
    return ("rubber", hi if (math.floor(x) + math.floor(z)) % 2 else lo)


def _carbon(c):
    if c.face == "up":
        return _weave(c.p[0], c.p[2])
    return ("rubber", 1 if c.face != "down" else 0)


CARBON = Paint("rubber", 1, noise=0, rim=0.3, decal=_carbon)


def _mirror(c):
    if c.face == "south":
        if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return ("rubber", 1)
        return ("glass", 3 if c.u + c.v == 2 else 2)
    if c.face == "down":
        return ("rubber", 1)
    return None


def _steering(mark):
    def decal(c):
        u, v = c.u, c.v
        if c.face not in ("north", "south"):
            return raw("rubber", 1)
        ring = ((v in (0, 3) and 1 <= u <= 3) or (v in (1, 2) and u in (0, 4)))
        if ring:
            return raw(*mark) if v == 0 and u == 2 else raw("rubber", 1 if v else 2)
        if v == 2:
            return raw("tint", 2 if u == 2 else 1)
        return ("raw", CLEAR)
    return decal


# ================================================================================================
# 하이퍼카 (a low mid-engined coupe) 4.6 x 2.0 x 1.4 blocks
# ================================================================================================

def wheel_hyper():
    """The hyper car's wheel: 0.75 block across (12 px), 5 px wide, a thin black ten-spoke rim."""
    return _wheel("wheel_hyper", 12, 5, cap="blacksteel", cap_d=10, spokes=10)


def hyper_car(colour="sapphire", accent="signal_blue", roof=True, name="hyper_car"):
    """A wedge that is all cabin and engine: a hood sloping straight down to a thin nose, a fender standing over
    it each side with a slanted face (a black lamp inset, two bright blades), a narrow glass canopy far forward
    (a raked windscreen, a dark glass roof), wide shoulders either side of it, a black intake behind each door, and
    from the roof a long fastback down to the tail: louvres over the engine between two buttresses. A low wing
    across the tail, a light bar the whole width under it, a black mesh with three exhaust tips in its middle, a
    finned diffuser. Carbon splitter, skirts and undertray. roof False: the canopy is left off (a speedster)."""
    m = BModel(name, shift=(8, 8, 8), scale=2.0)
    C, A = colour, accent
    PAINT = Paint(C, 2, noise=0, grad=0)
    W, L = 16, 37
    WF, WR, SLOT = -22, 23, 7
    FLOOR, BELT, CAB = 6, 11.5, 13     # the cabin's floor, the doors' top, the canopy's half width
    FEND, HAUNCH, ROOF = 13, 13.5, 21.5

    def col(y, lo=3.5, hi=13.5):
        return (C, lerp(y, lo, hi, 0.8, 2.3))

    def well(p):
        z = p[2]
        return abs(p[0]) > 10.9 and p[1] < 12.1 and (abs(z - WF) <= SLOT + 0.1 or abs(z - WR) <= SLOT + 0.1)

    def body(c):
        p = c.p
        x, y, z = p
        fx, fy, fz, ax = math.floor(x), math.floor(y), math.floor(z), abs(x)
        if well(p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if -12 < z < 6 and ax < 11:
                return ("rubber", 1 if fz % 3 else 0)        # the cabin's carpet
            if -15 <= z < -12:
                return ("rubber", 1.2) if ax < CAB else (C, 2.6)     # the cowl under the screen
            if ax >= CAB and -12 <= z < 8:
                return (C, 3.0 if ax < CAB + 1 else 2.5)     # the shoulders beside the canopy
            return (C, 2.5)
        if c.face in ("east", "west"):
            if fy <= 2:
                return ("rubber", 0)
            if (fz in (WF - SLOT - 1, WF + SLOT, WR - SLOT - 1, WR + SLOT) and fy < 12) or (fy == 12 and (abs(fz - WF + 0.5) <= SLOT + 1 or abs(fz - WR + 0.5) <= SLOT + 1)):
                return (C, 0.5)                               # the arches' shadowed lips
            if 7 <= fz <= 15 and 4 <= fy <= 10 and fz - 7 >= (fy - 4) * 0.7 - 0.5:
                return ("rubber", 0) if (fz + fy) % 2 else ("tint", 1.0)     # the side intake: a mesh, swept back
            if 4 <= fz <= 6 and 4 <= fy <= 10 and fz - 4 >= (fy - 4) * 0.7 - 0.5:
                return (C, 0.6)                               # the scoop the door is cut into
            if fz in (-13, 6) and fy >= 3:
                return (C, 0.2)                               # door seams
            if fz == -36 and 4 <= fy <= 5:
                return ("fox", 3.4)
            if fz == 36 and 8 <= fy <= 9:
                return ("signal_red", 1.6)
            if fy == 10 and -13 < fz < 7:
                return (C, 3.0)                               # a light along the door's shoulder
            if fy == 3:
                return (C, 0.5)
            return col(y)
        if c.face == "north":
            if c.lo[2] < -36.5 and c.hi[1] <= 6.1:
                # the thin nose: a black intake each side of a pointed middle
                if 3 <= ax < 13 and 3 <= y < 5:
                    return ("rubber", 0) if (fx + fy) % 2 else ("tint", 0.9)
                return ("rubber", 1) if fy == 2 else col(y, 2, 6)
            if y > c.hi[1] - 1:
                return (C, 3.0)
            return col(y)
        if c.face == "south":
            if c.lo[2] > 29 and c.hi[1] <= 12.1:
                if y < 5:
                    return ("rubber", 0.5) if math.floor(ax) % 3 else ("steel", 2.2)      # the diffuser's fins
                if fy <= 9 and ax < 15:
                    # a black mesh; in its middle a steel-framed outlet with three bores side by side
                    if 5 <= fy <= 8 and ax < 5.5:
                        if fy in (6, 7) and fx in (-4, -3, -1, 0, 2, 3):
                            return ("rubber", 0)
                        return ("steel", 3.6 if fy == 8 else 2.6)
                    return ("rubber", 0) if (fx + fy) % 2 else ("tint", 0.8)
                return col(y, 6, 12) if fy < 11 else (C, 1.2)
            if y > c.hi[1] - 1:
                return (C, 3.0)
            return col(y)
        return None
    paint = PAINT(decal=body)
    inner = Paint("rubber", 1, noise=0)

    def slab(c):
        if c.face == "up":
            ax = abs(c.p[0])
            if ax < 1:
                return (C, 3.3)                              # the hood's spine
            if 3 <= ax < 7 and 6 <= c.v <= 11:
                return ("rubber", 0) if c.v % 2 else ("tint", 1.2)       # a vent each side
            return (C, 2.6)
        return (C, 1.4 if c.face in ("east", "west") else 2.0)
    sp = PAINT(decal=slab)

    m.group("body")
    m.box("core", (-11, 2, WF - SLOT), (11, FLOOR, 30), paint)
    m.box("nose", (-W, 2, -L), (W, 6, WF - SLOT), paint)
    m.box("tail", (-W, 2, WR + SLOT), (W, 12, L), paint)
    for s in (1, -1):
        xa, xb = sorted((11 * s, W * s))
        m.box("sill", (xa, 2, WF + SLOT), (xb, BELT, WR - SLOT), paint)

    m.box("hood", (-9.5, 5, -31), (9.5, 6, -31 + 17.3), sp, rotation=("x", -22.5, (0, 6, -31)))
    m.box("bay", (-9.5, 6, -25), (9.5, 8, -15), inner)
    m.box("bay", (-9.5, 8, -20.5), (9.5, 10, -15), inner)
    m.box("bay", (-9.5, 10, -17), (9.5, 11.5, -15), inner)
    m.box("cowl", (-W, BELT, -15), (W, 12.5, -12), paint)

    def lamp_face(c):
        # the fender's slanted face: a black inset, two bright blades in it (the day running lights)
        if c.face != "up":
            return (C, 1.4 if c.face in ("east", "west") else 2.0)
        u = math.floor(abs(c.p[0]) - 9.5)
        v = math.floor(c.p[2] + L)
        if 2 <= v <= 7 and 1 <= u <= 5:
            return ("lamp", 4.4) if v in (3, 6) and u >= 2 else ("tint", 0.5)
        return (C, 2.8 if v >= 8 else 2.4)
    lp = PAINT(decal=lamp_face)
    for s in (1, -1):
        xa, xb = sorted((9.5 * s, W * s))
        m.box("fender", (xa, 12, WF - SLOT), (xb, FEND, WF + SLOT), paint)
        m.box("fender_face", (xa, 5, -L), (xb, 6, -L + 9.9), lp, rotation=("x", -45, (0, 6, -L)))
        m.box("fender_fill", (xa, 6, -34), (xb, 8, WF - SLOT), paint)
        m.box("fender_fill", (xa, 8, -32), (xb, 10, WF - SLOT), paint)
        m.box("fender_fill", (xa, 10, -31), (xb, 12, WF - SLOT), paint)
        xa, xb = sorted((9.5 * s, 11 * s))
        m.box("fender_wall", (xa, 6, WF - SLOT), (xb, 12, WF + SLOT), paint)
        # the haunch over the rear wheel, to the tail
        xa, xb = sorted((9.5 * s, W * s))
        m.box("haunch", (xa, BELT, 8), (xb, HAUNCH, WR - SLOT), paint)
        m.box("haunch", (xa, 12, WR - SLOT), (xb, HAUNCH, L), paint)
        xa, xb = sorted((9.5 * s, 11 * s))
        m.box("haunch_wall", (xa, 6, WR - SLOT), (xb, 12, WR + SLOT), inner)

    m.box("deck", (-9.5, FLOOR, 7), (9.5, 12.5, L - 0.5), paint)
    m.box("firewall", (-9.5, FLOOR, 6), (9.5, 12.5, 7), inner)

    m.box("splitter", (-W, 0.75, -L - 1.5), (W, 2, -34.5), CARBON)
    m.box("diffuser_floor", (-W + 1, 0.75, L - 3), (W - 1, 2, L + 1), CARBON)
    for s in (1, -1):
        xa, xb = sorted((11 * s, (W + 0.5) * s))
        m.box("skirt", (xa, 0.75, WF + SLOT + 0.5), (xb, 2, WR - SLOT - 0.5), CARBON)
    m.box("undertray", (-11, 0.75, -34.5), (11, 2, L - 3), CARBON)

    # the wing: a low carbon blade across the tail on two short struts
    m.box("wing", (-14.5, 15, L - 5.5), (14.5, 15.75, L + 0.5), CARBON)
    for s in (1, -1):
        xa, xb = sorted((8 * s, 9 * s))
        m.box("wing_strut", (xa, HAUNCH, L - 4), (xb, 15, L - 2), CARBON)

    m.group("lights")

    def headlight(c):
        return ("lamp", 4.5) if c.face == "north" else ("tint", 1)

    def light_bar(c):
        # one lamp the whole width of the tail, brighter towards its ends
        if c.face != "south":
            return ("signal_red", 0.2)
        return ("signal_red", 1.3 if abs(c.p[0]) >= 9 else 0.7)
    for s in (1, -1):
        xa, xb = sorted((10 * s, 15.5 * s))
        m.box("headlight", (xa, 4.5, -L - 0.25), (xb, 5.5, -L), Paint("lamp", 3, decal=headlight, **lamp()))
    m.box("tail_lamp", (-15, 10, L), (15, 11, L + 0.25), Paint("signal_red", 1, decal=light_bar, **lamp()))

    m.group("cabin")

    def trim(c):
        if c.face == "up":
            return ("rubber", 2)
        fy = math.floor(c.p[1])
        return (A, 2.4) if fy == 10 and (math.floor(c.p[2]) + math.floor(c.p[0])) % 2 else ("rubber", 1.4)
    for s in (1, -1):
        xa, xb = sorted((10.5 * s, 11 * s))
        m.box("door_card", (xa, FLOOR, -12), (xb, BELT, 6), Paint("rubber", 1, noise=0, decal=trim))

    def tunnel(c):
        x, y, z = c.p
        if c.face == "up":
            fz = math.floor(z)
            if -4 <= fz <= -3 and abs(x) < 1:
                return ("blade", 3)
            return ("rubber", 2 if abs(x) < 1.5 else 1.4)
        return ("rubber", 1)
    m.box("tunnel", (-2, FLOOR, -9.5), (2, 8.5, 6), Paint("rubber", 1, noise=0, decal=tunnel))

    def dash(c):
        x, y, z = c.p
        fy, fx = math.floor(y), math.floor(x)
        if c.face == "south":
            if -9 <= x < -4 and fy == 11:
                return ("glow_cyan", 2) if fx in (-8, -6) else ("tint", 1.2)
            if -1 <= x < 3 and fy == 11:
                return ("signal_blue", 2) if fx % 2 else ("tint", 2)
            if fy == 10:
                return (A, 2.4) if fx % 2 else ("rubber", 1.4)
            return ("rubber", 1)
        if c.face == "up":
            return ("rubber", 2) if math.floor(z) == -11 else ("rubber", 1.2)
        return ("rubber", 1)
    m.box("dash", (-10.5, FLOOR, -12), (10.5, 12.25, -9.5), DARK(decal=dash))
    m.box("column", (-7.5, 11.5, -9.5), (-6.5, 13, -8.5), PLASTIC)
    m.box("wheel", (-9.5, 12.5, -8.5), (-4.5, 16.5, -8), Paint("rubber", 1, noise=0, light=False, decal=_steering((A, 3))))

    seat = dict(bolster=("rubber", 2), insert=(A, 1.4), stitch=(A, 3.0))
    for s in (1, -1):
        lo, hi = (min(2.5 * s, 10 * s), FLOOR, 3), (max(2.5 * s, 10 * s), 16, 5.5)
        m.box("seat_back", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        m.box("cushion", (lo[0], FLOOR, -4.5), (hi[0], 7.5, 3), Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        xa, xb = sorted((4 * s, 8.5 * s))
        m.box("headrest", (xa, 16, 3.5), (xb, 18.5, 5.5), Paint("rubber", 2, noise=0))

    for s in (1, -1):
        xa, xb = sorted((14.5 * s, 15.5 * s))
        m.box("mirror_arm", (xa, 12.5, -11.5), (xb, 13, -10.75), PLASTIC)
        xa, xb = sorted((15 * s, 18 * s))
        m.box("mirror", (xa, 13, -12), (xb, 15, -10.5), PAINT(decal=_mirror))

    if roof:
        m.group("canopy")
        rise = (ROOF - 12.5) * math.sqrt(2)
        m.box("screen_base", (-CAB + 0.75, 12.5, -14.5), (CAB - 0.75, 13, -12.75), PLASTIC)
        m.box("windscreen", (-CAB, 13, -14), (CAB, 13 + rise - 0.7, -13.5), glass(sun=1, wipers=True, streak=0.25),
              rotation=("x", 45, (0, 13, -14)))

        def top(c):
            # a dark glass roof between two painted rails
            x, y, z = c.p
            if c.face == "up":
                return (C, 2.8) if abs(x) >= CAB - 2 else ("tint", 1.4 if 2 <= x < 4 else 0.8)
            return (C, 2.6) if y > ROOF + 0.5 else ("rubber", 1)
        m.box("roof", (-CAB, ROOF, -5), (CAB, ROOF + 1, 6), PAINT(decal=top))

        def fastback(c):
            # the long back: louvres over the engine between two painted buttresses, a strip of dark glass at the top
            if c.face == "up":
                ax = abs(c.p[0])
                if ax >= 7:
                    return (C, 3.0 if ax < 8 else 2.6)
                if c.v < 4:
                    return ("tint", 1.0)
                return ("rubber", 0) if c.v % 2 else ("rubber", 2.2)
            return (C, 1.5)
        fb = PAINT(decal=fastback)
        m.box("fastback", (-CAB, ROOF, 6), (CAB, ROOF + 1, 6 + 22.6), fb, rotation=("x", 22.5, (0, ROOF + 1, 6)))

        def under(c):
            return (C, 1.6) if c.face in ("east", "west") else ("rubber", 1)
        up_ = PAINT(decal=under)
        for z0, z1, top_y in ((7, 11, 19.5), (11, 16, 17.5), (16, 21, 15.5), (21, 26, 14)):
            m.box("fastback_fill", (-CAB + 0.5, 12.5, z0), (CAB - 0.5, top_y, z1), up_)

        def side_window(c):
            x, y, z = c.p
            front = -14 + (y - 13)
            if z < front - 0.3 or y < 12.6:
                return ("raw", CLEAR)
            if c.nu <= 1 or c.nv <= 1:
                return raw(C, 2)
            if z < front + 1.5 or y > ROOF - 0.3 or z >= 4.5:
                return raw(C, 2 if y < ROOF - 0.3 else 3)        # the pillars, the roof rail
            if z < front + 2.5 or y > ROOF - 1.3 or y < 13.6 or z >= 3.5:
                return raw("rubber", 1)
            d = math.floor(z) - math.floor(y)
            if d in (-22, -21):
                return raw("glass", 3)
            return ("raw", CLEAR)
        for s in (1, -1):
            m.box("side_window", (min(CAB * s, (CAB - 0.5) * s), 12.5, -14), (max(CAB * s, (CAB - 0.5) * s), ROOF, 6),
                  Paint("glass", 2, noise=0, light=False, decal=side_window))
    m.display = _held_display(m)
    return m


# ================================================================================================
# 그랜드 세단 (a long, upright limousine) 5.3 x 2.0 x 1.8 blocks
# ================================================================================================

def wheel_grand():
    """The grand sedan's wheel: 0.75 block across (12 px), 4 px wide, a polished disc with a small centre cap."""
    return _wheel("wheel_grand", 12, 4, cap="blade", cap_d=9, plain=True)


def grand_sedan(colour="indigo", upper="blade", leather="cream", name="grand_sedan"):
    """A formal saloon, long and square-shouldered: a hood half as long again as any other car's with a bright
    spine down it and a small mascot at its front, a tall upright radiator shell of chrome slats standing proud of
    the nose, a square lamp and a round one each side of it, chrome bumpers. Two colours: the body dark, the hood's
    top, the roof and the boot's top pale (a coach line where they meet). An upright windscreen, four doors that
    open from the middle (handles side by side), a thick rear pillar that hides the back seat, a short square
    boot with tall narrow tail lamps. Inside: four seats of pale leather, a wooden dash."""
    m = BModel(name, shift=(8, 8, 8), scale=2.0)
    C, U = colour, upper
    PAINT = Paint(C, 2, noise=0, grad=0)
    W, L = 16, 42
    WF, WR, SLOT = -27, 27, 7
    BELT, ROOF = 16, 26.5
    LEAN = math.tan(math.radians(22.5))

    def col(y):
        return (C, lerp(y, 5, 15, 0.9, 2.6))

    def up(step):
        # the pale colour: a polished silver, not white (the ramp's upper steps are almost white)
        return (U, step - 1.6)

    def well(p):
        return abs(p[0]) > 10.9 and p[1] < 13.1 and (abs(p[2] - WF) <= SLOT + 0.1 or abs(p[2] - WR) <= SLOT + 0.1)

    def body(c):
        p = c.p
        x, y, z = p
        fx, fy, fz, ax = math.floor(x), math.floor(y), math.floor(z), abs(x)
        if well(p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if -15 <= z <= 21 and ax < 13.5:
                return ("rubber", 1 if fz % 4 else 0)          # carpet, seen through the glass
            if fz == -17 and (-12 <= x < -3 or 1 <= x < 10):
                return ("rubber", 0)                            # wipers at rest
            if y < BELT - 0.1:
                return (C, 3.2)                                 # the body's rounded top edges
            if z < -17:
                # the long hood, pale: a bright spine down its middle, a crease each side
                if ax < 1:
                    return ("blade", 4.2)
                if 9 <= ax < 10:
                    return up(1.2)
                return up(3.0 if 3 <= x < 6 else 2.2)
            if z > 21:
                if fz == 22 or (fz >= 22 and 13 <= ax < 14):
                    return up(0.8)                             # the boot lid's seam
                return up(3.0 if 3 <= x < 6 else 2.2)
            return up(2.2)
        if c.face in ("east", "west"):
            az = abs(z)
            if (fz in (WF - SLOT - 1, WF + SLOT, WR - SLOT - 1, WR + SLOT) and fy < 13) or (fy == 13 and (abs(fz - WF + 0.5) <= SLOT + 1 or abs(fz - WR + 0.5) <= SLOT + 1)):
                return ("blade", 3.2)                           # a bright trim round the arches
            if fy == 4:
                return ("blade", 2.6)                           # a bright sill strip
            if fy == 14:
                return ("gold", 2.6)                            # the coach line
            if fz in (-16, 1, 15) and fy >= 5:
                return (C, 0.2)                                 # the doors' seams: two doors that meet in the middle
            if fy == 12 and fz in (-1, 0, 2, 3):
                return ("blade", 4)                             # their handles, side by side
            if fz == -41 and 10 <= fy <= 11:
                return ("fox", 3)
            if fz == 40 and 10 <= fy <= 11:
                return ("signal_red", 1)
            return col(y)
        if c.face == "north":
            if y > BELT - 1.1:
                return up(3.0)
            return col(y)
        if c.face == "south":
            ax = abs(x)
            if y > BELT - 1.1:
                return up(3.0)
            if 9 <= fy <= 12 and ax < 9:
                if fy == 12:
                    return ("blade", 3.4)                       # a bright strip over the plate's recess
                if ax < 4.5 and 9 <= fy <= 11:
                    got = plate(math.floor(x + 4.5), 11 - fy, 9, 3)
                    if got:
                        return got
                return (C, 1.0)
            return col(y)
        return None
    paint = PAINT(decal=body)

    m.group("body")
    m.box("core", (-11, 4, -34), (11, 13, 34), paint)
    m.box("nose", (-W, 4, -L), (W, 13, -34), paint)
    m.box("tail", (-W, 4, 34), (W, 13, L), paint)
    for s in (1, -1):
        xa, xb = sorted((11 * s, W * s))
        m.box("sill", (xa, 4, WF + SLOT), (xb, 13, WR - SLOT), paint)
    m.box("shoulder", (-W, 13, -L + 0.5), (W, 15, L - 0.5), paint)
    m.box("belt", (-W + 0.5, 15, -L + 1.5), (W - 0.5, BELT, L - 1.5), paint)

    # the radiator shell: tall, upright, standing proud of the nose; chrome slats, a bright frame, the mascot on it
    def shell(c):
        x, y, z = c.p
        fx, fy = math.floor(x), math.floor(y)
        if c.face == "north":
            if c.u in (0, c.nu - 1) or fy >= 15:
                return ("blade", 4.4)
            return ("blade", 3.6) if fx % 2 == 0 else ("tint", 0.8)
        if c.face == "up":
            return ("blade", 4.6)
        return ("blade", 2.8)
    m.box("radiator", (-6, 6, -L - 1.5), (6, BELT + 0.5, -L), Paint("blade", 3, noise=0, decal=shell))
    m.box("mascot", (-0.5, BELT + 0.5, -L - 1), (0.5, BELT + 2.5, -L), Paint("blade", 4, noise=0, rim=0.3))
    m.box("mascot_wing", (-1.5, BELT + 1.5, -L - 0.5), (1.5, BELT + 2, -L + 0.5), Paint("blade", 4, noise=0, rim=0.3))

    m.box("bumper", (-W, 3.5, -L - 1), (W, 6, -L), CHROME(decal=bumper_decal(plate_w=0, strip=4)))
    m.box("bumper", (-W, 3.5, L), (W, 6.5, L + 1), CHROME(decal=bumper_decal(plate_w=0, strip=4)))

    for s in (1, -1):
        xa, xb = sorted((13.5 * s, 15 * s))
        m.box("mirror_arm", (xa, BELT, -13.5), (xb, BELT + 1, -12.5), CHROME)
        xa, xb = sorted((15 * s, 18 * s))
        m.box("mirror", (xa, BELT, -14), (xb, BELT + 2.5, -12), CHROME(decal=_mirror))

    m.group("lights")

    def headlight(c):
        # a square lamp in a bright frame: a round bright core, a running light along its top
        if c.face != "north":
            return ("blade", 3)
        k = math.floor(abs(c.p[0]) - 8)             # 0 inside .. 5 outside
        v = math.floor(c.p[1] - 9)                  # 0 bottom .. 4 top
        if k in (0, 5) or v in (0, 4):
            return ("blade", 4.2)
        if v == 3:
            return ("lamp", 4.6)
        return ("lamp", 4.0) if (k, v) in ((2, 1), (3, 1), (2, 2), (3, 2)) else ("lamp", 2.4)

    def fog(c):
        return ("lamp", 4.2 if c.u == 1 and c.v == 1 else 3.0) if c.face == "north" else ("blade", 3)

    def tail_lamp(c):
        # a tall narrow lamp: red, a bright bar down its middle, a bright frame
        if c.face != "south":
            return ("signal_red", 0)
        if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return ("blade", 3.6)
        return ("signal_red", 2.6 if c.u == c.nu // 2 else 1.2)
    for s in (1, -1):
        xa, xb = sorted((8 * s, 14 * s))
        m.box("headlight", (xa, 9, -L - 0.5), (xb, 14, -L), Paint("lamp", 3, decal=headlight, **lamp()))
        xa, xb = sorted((9.5 * s, 12.5 * s))
        m.box("fog_lamp", (xa, 6.25, -L - 0.5), (xb, 8.25, -L), Paint("lamp", 3, decal=fog, **lamp()))
        xa, xb = sorted((11 * s, 14.5 * s))
        m.box("tail_lamp", (xa, 7.5, L), (xb, 13.5, L + 0.5), Paint("signal_red", 1, decal=tail_lamp, **lamp()))

    m.group("cabin")

    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            return up(3.0 if 3 <= x < 6 else 2.2)
        if y > ROOF + 1:
            return up(3)
        return up(2.6) if y > ROOF + 0.5 else ("blade", 3.4)        # a bright drip rail
    h = (ROOF - BELT) / math.cos(math.radians(22.5))
    z_top = -16 + (ROOF - BELT) * LEAN                                # where the upright screen meets the roof
    z_back = 17
    m.box("roof", (-14, ROOF, z_top - 0.5), (14, ROOF + 1, z_back + 0.5), Paint(U, 1, noise=0, grad=0, decal=roof))
    m.box("roof_top", (-13.5, ROOF + 1, z_top), (13.5, ROOF + 1.5, z_back), Paint(U, 1, noise=0, grad=0, decal=roof))
    m.box("windscreen", (-13.5, BELT, -16), (13.5, BELT + h, -15.5), glass(frame=("blade", 3), sun=2, wipers=True, streak=0.25),
          rotation=("x", 22.5, (0, BELT, -16)))
    # the rear window is small and dark: the back seat is private
    def rear(c):
        if c.nu <= 1 or c.nv <= 1 or c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return raw("blade", 3)
        if c.u < 5 or c.u >= c.nu - 5 or c.v < 2 or c.v >= c.nv - 2:
            return raw(U, 1)
        return raw("tint", 2 if (c.u - c.v) % 9 == 0 else 1)
    m.box("rear_window", (-13.5, ROOF - h, z_back), (13.5, ROOF, z_back + 0.5), Paint(U, 1, noise=0, light=False, decal=rear),
          rotation=("x", -22.5, (0, ROOF, z_back)))

    def side_window(c):
        x, y, z = c.p
        front = -16 + (y - BELT) * LEAN
        back = z_back + (ROOF - y) * LEAN
        if z < front - 0.3 or z > back + 0.3:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw(U, 1)
        if z < front + 1.5 or y > ROOF - 0.8:
            return raw(U, 1 if y < ROOF - 0.8 else 2)        # the front pillar, the roof rail
        if z >= 10:
            # the thick rear pillar: painted like the roof, a small bright crest on it
            if 12 <= z < 14 and 20 <= y < 23:
                return raw("blade", 4)
            return raw(U, 1)
        if 0 <= z < 2:
            return raw("blade", 3)                           # the middle pillar, bright
        if z < front + 2.5 or y > ROOF - 1.8 or y < BELT + 1 or z >= 9 or -1 <= z < 3:
            return raw("blade", 4)                           # bright frames round each pane
        d = math.floor(z) - math.floor(y)
        if d in (-28, -27, -14, -13):
            return raw("glass", 3)
        return ("raw", CLEAR)
    for s in (1, -1):
        m.box("side_window", (min(13.5 * s, 14 * s), BELT, -16), (max(13.5 * s, 14 * s), ROOF, 22),
              Paint("glass", 2, noise=0, light=False, decal=side_window))

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        if c.face == "south":
            if -9 <= x < -5 and math.floor(y) == BELT + 1:
                return ("cream", 4) if math.floor(x) in (-9, -6) else ("tint", 1)      # two pale dials
            return ("wood", 2.4 if math.floor(y) == BELT + 1 else 1.6)
        if c.face == "up":
            return ("wood", 3) if math.floor(z) == -13 else ("rubber", 1)
        return None
    m.box("dash", (-13.5, BELT, -14), (13.5, BELT + 2, -11), Paint("wood", 2, noise=0, decal=dash))
    m.box("column", (-7.5, BELT + 1, -11), (-6.5, BELT + 2.5, -10.5), PLASTIC)
    m.box("wheel", (-9.5, BELT + 2, -10.5), (-4.5, BELT + 6, -10), Paint("rubber", 1, noise=0, light=False, decal=_steering(("blade", 4))))
    seat = dict(bolster=(leather, 2), insert=(leather, 3), stitch=("leather", 2))
    for s in (1, -1):
        lo, hi = (min(2.5 * s, 12 * s), BELT, -1), (max(2.5 * s, 12 * s), BELT + 7, 1)
        m.box("front_seat", lo, hi, Paint(leather, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        m.box("cushion", (lo[0], BELT, -7), (hi[0], BELT + 1.5, -1), Paint(leather, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        xa, xb = sorted((5 * s, 10 * s))
        m.box("headrest", (xa, BELT + 7, -0.5), (xb, BELT + 9, 0.5), Paint(leather, 2, noise=0))
        lo, hi = (min(2.5 * s, 12 * s), BELT, 15), (max(2.5 * s, 12 * s), BELT + 7, 17)
        m.box("rear_seat", lo, hi, Paint(leather, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    m.box("rear_cushion", (-12, BELT, 9), (12, BELT + 1.5, 15),
          Paint(leather, 2, noise=0, decal=upholstery(lo=(-12, 0, 9), hi=(12, 0, 15), **seat)))
    m.box("arm_rest", (-1.5, BELT + 1.5, 9), (1.5, BELT + 3.5, 15), Paint("wood", 2, noise=0))
    m.display = _held_display(m)
    return m


# ================================================================================================
# 럭셔리 SUV (a tall, square off-roader) 4.6 x 2.1 x 2.2 blocks
# ================================================================================================

def wheel_suv():
    """The SUV's wheel: 0.875 block across (14 px), 5 px wide, a dark five-spoke rim in a tall tyre."""
    return _wheel("wheel_suv", 14, 5, cap="blacksteel", cap_d=9, spokes=5)


def luxury_suv(colour="leaf_dark", leather="leather", name="luxury_suv"):
    """A box on big wheels, every panel flat and every corner square: a short level hood with a turn lamp standing
    on each front corner, a flat grille of three bright bars between two round lamps, a steel bumper with a guard
    bar, black flares over the arches and a running board between them, an upright windscreen, tall square windows,
    a rack of four lamps over the screen and a rail along each side of the roof, and on the flat back a door with
    the spare wheel on it, a tall narrow lamp low at each corner. Inside: four seats of tan leather."""
    m = BModel(name, shift=(8, 8, 8), scale=2.0)
    C = colour
    PAINT = Paint(C, 2, noise=0, grad=0)
    W, L = 15, 36
    WF, WR, SLOT = -22, 22, 8
    BOTTOM, BELT, ROOF = 7, 20, 31.5
    LEAN = math.tan(math.radians(22.5))

    def col(y):
        return (C, lerp(y, 8, 19, 0.8, 2.3))

    def well(p):
        return abs(p[0]) > 9.9 and p[1] < 16.1 and (abs(p[2] - WF) <= SLOT + 0.1 or abs(p[2] - WR) <= SLOT + 0.1)

    def body(c):
        p = c.p
        x, y, z = p
        fx, fy, fz, ax = math.floor(x), math.floor(y), math.floor(z), abs(x)
        if well(p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if -17 <= z <= 34 and ax < 14:
                return ("rubber", 1 if fz % 4 else 0)          # carpet
            if fz == -19 and (-12 <= x < -3 or 1 <= x < 10):
                return ("rubber", 0)                            # wipers
            # the hood: flat, a raised middle, a crease each side
            if 6 <= ax < 7:
                return (C, 1.2)
            return (C, 2.8 if ax < 6 else 2.3)
        if c.face in ("east", "west"):
            if fz in (-17, 0, 17) and fy >= 8:
                return (C, 0.2)                                 # door seams
            if fy == 17 and (2 <= fz <= 4 or 19 <= fz <= 21):
                return ("rubber", 0)                            # handles
            if fy == 14:
                return ("rubber", 1.2)                          # a black rub strip along the side
            if fz == -35 and 15 <= fy <= 16:
                return ("fox", 3)
            if fz == 35 and 15 <= fy <= 16:
                return ("signal_red", 1)
            return col(y)
        if c.face == "north":
            ax = abs(x)
            if 11 <= fy <= 17 and ax < 8.5:
                # the grille: black, three bright bars across it, a bright frame
                if fy in (11, 17) or ax >= 7.5:
                    return ("blade", 3.6)
                return ("blade", 3.2) if fy in (13, 15) else ("rubber", 0)
            if y > BELT - 1.1:
                return (C, 3.0)
            return col(y)
        if c.face == "south":
            ax = abs(x)
            if fz >= 35 and fy >= 9 and ax < 10.5:
                if ax >= 9.5 or fy == 9:
                    return (C, 0.3)                             # the back door's seam
            return col(y) if y < BELT - 1 else (C, 2.4)
        return None
    paint = PAINT(decal=body)

    m.group("body")
    m.box("core", (-10, BOTTOM, WF - SLOT), (10, 16, WR + SLOT), paint)
    m.box("nose", (-W, BOTTOM, -L), (W, 16, WF - SLOT), paint)
    m.box("tail", (-W, BOTTOM, WR + SLOT), (W, 16, L), paint)
    for s in (1, -1):
        xa, xb = sorted((10 * s, W * s))
        m.box("sill", (xa, BOTTOM, WF + SLOT), (xb, 16, WR - SLOT), paint)
    m.box("upper", (-W, 16, -L), (W, BELT, L), paint)

    # black flares over the arches, a running board between them
    def flare(c):
        return ("rubber", 2.2 if c.face == "up" else 1.4 if c.face in ("east", "west") else 0.8)
    fp = Paint("rubber", 1, noise=0, rim=0.5, decal=flare)
    for s in (1, -1):
        xa, xb = sorted((W * s, (W + 1) * s))
        for wz in (WF, WR):
            m.box("arch_flare", (xa, 15.5, wz - SLOT - 1), (xb, 17, wz + SLOT + 1), fp)
            for zz in (wz - SLOT - 1, wz + SLOT):
                m.box("arch_flare", (xa, BOTTOM, zz), (xb, 15.5, zz + 1), fp)
        xa, xb = sorted((10 * s, (W + 2) * s))
        m.box("running_board", (xa, 5.5, WF + SLOT + 1), (xb, 6.5, WR - SLOT - 1),
              Paint("rubber", 1, noise=0, rim=0.3, decal=lambda c: ("blade", 3) if c.face in ("east", "west") else ("rubber", 2 if (math.floor(c.p[2]) % 2) else 1)))
        # the turn lamp standing on the hood's front corner
        xa, xb = sorted((11.5 * s, 13.5 * s))
        m.box("turn_lamp", (xa, BELT, -L + 2), (xb, BELT + 1, -L + 5), Paint("fox", 3, noise=0, rim=0.3))
        xa, xb = sorted((14.5 * s, 15 * s))
        m.box("mirror_arm", (xa, BELT + 0.75, -15.5), (xb, BELT + 1.75, -14.5), PLASTIC)
        xa, xb = sorted((15 * s, 18 * s))
        m.box("mirror", (xa, BELT, -16), (xb, BELT + 3.5, -14), Paint("rubber", 1, noise=0, decal=_mirror))

    # steel bumpers; in front a guard bar on two posts
    steel = Paint("steel", 3, noise=0, rim=0.3)
    m.box("bumper", (-W - 0.5, 8, -L - 2), (W + 0.5, 11, -L), steel(decal=bumper_decal(plate_w=9, plate_rows=(0, 2), strip=2)))
    m.box("bumper", (-W - 0.5, 8, L), (W + 0.5, 11, L + 1.5), steel(decal=bumper_decal(plate_w=0, strip=2)))
    m.box("guard_bar", (-9, 14, -L - 3), (9, 15, -L - 2), steel)
    for s in (1, -1):
        xa, xb = sorted((7 * s, 8 * s))
        m.box("guard_post", (xa, 11, -L - 3), (xb, 14, -L - 2), steel)

    # the spare wheel on the back door, under a painted cover
    def cover(c):
        x, y = c.p[0], c.p[1]
        r = math.hypot(x, y - 18)
        if c.face == "south":
            if r < 1.2:
                return ("blade", 4)
            return (C, 2.6 if y > 18 and r < 4.5 else 1.8) if r < 5 else ("rubber", 1)
        return ("rubber", 1)
    cyl(m, "spare", (0, 18), 12, L, L + 3.5, Paint("rubber", 1, noise=0, decal=cover), axis="z")

    m.group("lights")

    def round_lamp(c):
        if c.face != "north":
            return ("blade", 3)
        r = math.hypot(abs(c.p[0]) - 11.5, c.p[1] - 14)
        return ("lamp", 4.8) if r < 0.9 else ("lamp", 3.6) if r < 1.9 else ("blade", 3.8)

    def tail_lamp(c):
        if c.face != "south":
            return ("signal_red", 0)
        return ("fox", 3.2) if c.v == 0 else ("signal_red", 2.4 if c.v in (2, 3) else 1.2)
    for s in (1, -1):
        cyl(m, "headlight", (11.5 * s, 14), 5, -L - 0.5, -L, Paint("lamp", 3, decal=round_lamp, **lamp()), axis="z")
        xa, xb = sorted((12 * s, 14.5 * s))
        m.box("tail_lamp", (xa, 12, L), (xb, 18, L + 0.5), Paint("signal_red", 1, decal=tail_lamp, **lamp()))
    # four lamps in a rack over the screen
    for k in range(4):
        x0 = -9.5 + k * 5.5
        m.box("roof_lamp", (x0, ROOF + 2, -10), (x0 + 2.5, ROOF + 4, -9.5),
              Paint("lamp", 3, decal=lambda c: ("lamp", 4.4 if c.u == 1 else 3.2) if c.face == "north" else ("rubber", 1), **lamp()))
        m.box("roof_lamp_body", (x0, ROOF + 2, -9.5), (x0 + 2.5, ROOF + 4, -8), PLASTIC)
    m.box("lamp_rack", (-11, ROOF + 1.5, -9.5), (11, ROOF + 2, -8.5), PLASTIC)

    m.group("cabin")

    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            return (C, 2.8 if 3 <= x < 6 else 2.2)
        return (C, 2.6) if y > ROOF + 0.5 else ("rubber", 1)
    h = (ROOF - BELT) / math.cos(math.radians(22.5))
    z_top = -18 + (ROOF - BELT) * LEAN
    m.box("roof", (-14.5, ROOF, z_top - 0.5), (14.5, ROOF + 1.5, L), PAINT(decal=roof))
    for s in (1, -1):
        xa, xb = sorted((12 * s, 13 * s))
        m.box("roof_rail", (xa, ROOF + 1.5, -6), (xb, ROOF + 2.5, 32), PLASTIC)
    m.box("windscreen", (-14, BELT, -18), (14, BELT + h, -17.5), glass(sun=2, wipers=True, streak=0.25),
          rotation=("x", 22.5, (0, BELT, -18)))

    def back_window(c):
        # the back door's glass, behind the spare wheel
        if c.nu <= 1 or c.nv <= 1 or c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return raw(C, 2)
        if c.u < 3 or c.u >= c.nu - 3 or c.v < 2:
            return raw(C, 2)
        if c.u in (3, c.nu - 4) or c.v == 2 or c.v == c.nv - 2:
            return raw("rubber", 1)
        return raw("glass", 3) if (c.u + c.v) % 11 == 0 else ("raw", CLEAR)
    m.box("back_window", (-14.5, BELT, L - 0.5), (14.5, ROOF, L), Paint("glass", 2, noise=0, light=False, decal=back_window))

    def side_window(c):
        x, y, z = c.p
        front = -18 + (y - BELT) * LEAN
        if z < front - 0.3:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw(C, 2)
        if z < front + 1.5 or y > ROOF - 0.8 or z >= 33 or -1 <= z < 1 or 16 <= z < 18:
            return raw(C, 2 if y < ROOF - 0.8 else 3)        # the pillars, the roof rail
        if z < front + 2.5 or y > ROOF - 1.8 or y < BELT + 1 or z >= 32 or -2 <= z < 2 or 15 <= z < 19:
            return raw("rubber", 1)
        d = math.floor(z) - math.floor(y)
        if d in (-33, -32, -17, -16, 0, 1):
            return raw("glass", 3)
        return ("raw", CLEAR)
    for s in (1, -1):
        m.box("side_window", (min(14 * s, 14.5 * s), BELT, -18), (max(14 * s, 14.5 * s), ROOF, L - 0.5),
              Paint("glass", 2, noise=0, light=False, decal=side_window))

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        if c.face == "south":
            if -9 <= x < -5 and math.floor(y) == BELT + 1:
                return ("glass", 3) if math.floor(x) in (-9, -6) else ("tint", 1)
            if -2 <= x < 3 and math.floor(y) == BELT + 1:
                return ("signal_blue", 2) if math.floor(x) % 2 else ("tint", 2)
            return ("rubber", 1)
        if c.face == "up":
            return ("rubber", 2) if math.floor(z) == -15 else ("rubber", 1)
        return None
    m.box("dash", (-14, BELT, -16), (14, BELT + 2, -13), DARK(decal=dash))
    m.box("column", (-7.5, BELT + 1, -13), (-6.5, BELT + 2.5, -12.5), PLASTIC)
    m.box("wheel", (-9.5, BELT + 2, -12.5), (-4.5, BELT + 6, -12), Paint("rubber", 1, noise=0, light=False, decal=_steering(("blade", 4))))
    seat = dict(bolster=("rubber", 2), insert=(leather, 2), stitch=(leather, 3.4))
    for s in (1, -1):
        lo, hi = (min(2.5 * s, 12.5 * s), BELT, -3), (max(2.5 * s, 12.5 * s), BELT + 7, -1)
        m.box("front_seat", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        m.box("cushion", (lo[0], BELT, -9), (hi[0], BELT + 1.5, -3), Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        xa, xb = sorted((5 * s, 10 * s))
        m.box("headrest", (xa, BELT + 7, -2.5), (xb, BELT + 9, -1.5), Paint("rubber", 2, noise=0))
        lo, hi = (min(2.5 * s, 12.5 * s), BELT, 13), (max(2.5 * s, 12.5 * s), BELT + 7, 15)
        m.box("rear_seat", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    m.box("rear_cushion", (-12.5, BELT, 7), (12.5, BELT + 1.5, 13),
          Paint("rubber", 2, noise=0, decal=upholstery(lo=(-12.5, 0, 7), hi=(12.5, 0, 13), **seat)))
    m.display = _held_display(m)
    return m


ALL = {
    "hyper_car": hyper_car,
    "wheel_hyper": wheel_hyper,
    "grand_sedan": grand_sedan,
    "wheel_grand": wheel_grand,
    "luxury_suv": luxury_suv,
    "wheel_suv": wheel_suv,
}
