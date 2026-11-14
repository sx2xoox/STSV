"""
The premium cars (content/stsv/items/vehicles.yml, handed out by an administrator): 하이퍼카 (a low mid-engined
coupe), 그랜드 세단 (a long, upright limousine) and 럭셔리 SUV (a tall, square off-roader). All original designs:
no real make, no badge.

The same rules as models/vehicles.py (read its doc first): a body model drawn at its real size (16 px a block,
shrunk by 2 and shown at vehicle.body-scale 2), its wheel a model of its own. Drawn coordinates: the vehicle's
ground centre at (0, 0, 0), front = north (-z), +x = the vehicle's RIGHT side; the driver sits on the left (-x).

Tuning in mind, like the event cars: every body takes its paint (and what goes with it) as arguments, so another
colour is one more call (a model each); the wheels are models of their own.

Parts that move are models of their own (a door, a wing, a mascot: vehicles.yml movers, drawn by the plugin's
VehicleDress): each is drawn here in its body's own space, exactly where it lies at rest, so the plugin shows it
with the body's transformation and turns it about its hinge. A car is therefore a Kit: its body and its parts,
built together (a door takes its panels out of the body's side).

Imported at the end of models/vehicles.py (its ALL carries these): import that module, not this one.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets import presets
from models.vehicles import (CLEAR, CHROME, DARK, PLASTIC, lerp, lamp, plate, pane, glass, raw, upholstery, bumper_decal,
                             _held_display, _wheel)


class Kit:
    """The models of one car: its body and the parts the plugin moves, all in the body's space."""

    def __init__(self, name, parts, lift=0, scale=2.0):
        """lift: the whole car stands this many px higher than it is drawn (a body on tall wheels: it is drawn and
        painted as if it stood on the ground). scale: vehicle.body-scale in the item file."""
        self.body = BModel(name, shift=(8, 8 + lift, 8), scale=scale)
        self.parts = {}
        for part in parts:
            m = BModel(f"{name}_{part}", shift=(8, 8 + lift, 8), scale=scale)
            m.display = presets.furniture(gui_scale=0.4)
            m.group(part)
            self.parts[part] = m

    def __getitem__(self, part):
        return self.parts[part]

    def all(self):
        return {self.body.name: self.body, **{m.name: m for m in self.parts.values()}}


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
    """The hyper car's wheel: 0.75 block across (12 px), 5 px wide, a thin black ten-spoke rim with a bright machined lip."""
    return _wheel("wheel_hyper", 12, 5, cap="blacksteel", cap_d=10, spokes=10, lip="blade")


def hyper_car(colour="sapphire", accent="signal_blue", roof=True, name="hyper_car"):
    return hyper_kit(colour, accent, roof, name).body


def hyper_kit(colour="sapphire", accent="signal_blue", roof=True, name="hyper_car"):
    """A wedge that is all cabin and engine: a hood sloping straight down to a thin nose, a fender standing over
    it each side with a slanted face (a black lamp inset, two bright blades), a narrow glass canopy far forward
    (a raked windscreen, a dark glass roof), wide shoulders either side of it, a black intake behind each door, and
    from the roof a long fastback down to the tail: louvres over the engine between two buttresses. A low wing
    across the tail, a light bar the whole width under it, a black mesh with three exhaust tips in its middle, a
    finned diffuser. Carbon splitter, skirts and undertray. roof False: the canopy is left off (a speedster).

    Its parts: door_l and door_r (each the side between the two seams, its window, its mirror: they swing up about
    their front top corner) and wing (the blade on its two struts: it lies on the tail, rises with the speed and
    stands up as an air brake)."""
    kit = Kit(name, ("door_l", "door_r", "wing"))
    m = kit.body
    door = {-1: kit["door_l"], 1: kit["door_r"]}
    D0, D1 = -13, 6                    # the door: between its two seams
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
            if ax < 11.5 and D0 <= z < D1:
                # the cabin's side, seen when a door is up: a bright sill plate over the dark tub
                return ("blade", 3.4) if fy == 5 else ("rubber", 1)
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
        m.box("sill", (xa, 2, WF + SLOT), (xb, BELT, D0), paint)
        m.box("sill", (xa, 2, D1), (xb, BELT, WR - SLOT), paint)
        door[s].box("door", (xa, 2, D0), (xb, BELT, D1), paint)

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

    # the wing (a part): a carbon blade across the tail, lying just over it; its two struts are down in the tail
    # until it rises. A painted lip along its trailing edge.
    wing = kit["wing"]
    wing.box("wing", (-14.5, HAUNCH + 0.25, L - 6), (14.5, HAUNCH + 1, L + 0.5), CARBON)
    wing.box("wing_lip", (-14.5, HAUNCH + 1, L - 0.5), (14.5, HAUNCH + 1.5, L + 0.5), PAINT(decal=lambda c: (C, 3.0 if c.face == "up" else 2.0)))
    for s in (1, -1):
        xa, xb = sorted((8 * s, 9 * s))
        wing.box("wing_strut", (xa, HAUNCH - 3.5, L - 4.5), (xb, HAUNCH + 0.25, L - 2.5), Paint("steel", 3, noise=0))

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
        door[s].box("door_card", (xa, FLOOR, -12), (xb, BELT, D1), Paint("rubber", 1, noise=0, decal=trim))

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
        door[s].box("mirror_arm", (xa, BELT, -11.5), (xb, 13, -10.75), PLASTIC)
        xa, xb = sorted((15 * s, 18 * s))
        door[s].box("mirror", (xa, 13, -12), (xb, 15, -10.5), PAINT(decal=_mirror))

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
            door[s].box("side_window", (min(CAB * s, (CAB - 0.5) * s), 12.5, -14), (max(CAB * s, (CAB - 0.5) * s), ROOF, D1),
                        Paint("glass", 2, noise=0, light=False, decal=side_window))
    m.display = _held_display(m)
    return kit


# ================================================================================================
# 그랜드 세단 (a long, upright limousine) 5.3 x 2.0 x 1.8 blocks
# ================================================================================================

def wheel_grand():
    """The grand sedan's wheel: a block across (16 px), 5 px wide, a polished twelve-spoke rim in a thin tyre."""
    return _wheel("wheel_grand", 16, 5, cap="blade", cap_d=13, spokes=12, lip="blade")


def grand_sedan(colour="pearl", upper=None, leather="cream", name="grand_sedan"):
    return grand_kit(colour, upper, leather, name).body


def grand_kit(colour="pearl", upper=None, leather="cream", name="grand_sedan"):
    """A grand saloon, long and low on wheels a block tall. A long hood with a raised middle runs to a nose that
    drops a little and is cut in at its corners; a tall chrome radiator shell of fine slats stands proud of the nose
    and above the hood, slim bright lamps each side of it, a dark intake under each with a chrome blade. The sides
    are one clean surface: round arches close over the wheels, a chrome line under the glass from end to end, a
    reflection along the shoulder, a chrome sill. The glass house is narrower than the body, its screen laid back,
    its roof short with a dark glass panel, its back a fastback that hides the rear seat behind a wide pillar. A
    short boot, slim red lamps joined by a chrome bar, two bright exhaust tips. One colour all over (upper: another
    colour for the hood's top, the roof and the boot's top). Inside: four seats of pale leather, a wooden dash.

    Its parts: four doors (door_fl, door_fr, door_rl, door_rr: the front ones hinged at their front edge, the rear
    ones at their rear edge, so a pair opens from the middle like a cabinet) and mascot (down in the radiator shell
    until the engine runs)."""
    kit = Kit(name, ("door_fl", "door_fr", "door_rl", "door_rr", "mascot"))
    m = kit.body
    DOORS = {(-1, 0): kit["door_fl"], (1, 0): kit["door_fr"], (-1, 1): kit["door_rl"], (1, 1): kit["door_rr"]}
    DZ = ((-16, 2), (2, 17))           # the front and the rear door: between their seams
    C, U = colour, upper or colour
    two = U != C
    PAINT = Paint(C, 2, noise=0, grad=0)
    W, L, XI, TIP = 16, 42, 10.5, 1.5  # half width, half length, the wells' inner wall, how far the ends are cut in
    WF, WR, SLOT = -27, 27, 9.5
    SILL, ARCH, BELT, ROOF = 3, 17, 19, 28
    ZS, ZR = -17, 17                   # the screen's foot, the roof's back edge
    ZB = ZR + (ROOF - BELT)            # the rear glass's foot (both panes lie at 45 degrees)
    NOSE = BELT - 0.75                 # the top of the cut-in ends

    def col(y):
        return (C, lerp(y, 4, 18, 0.6, 2.4))

    def top(step):
        # the upper colour: a polished silver is drawn darker (that ramp's upper steps are almost white)
        return (U, step - 1.6) if two and U == "blade" else (U, step)

    def well(p):
        return abs(p[0]) > XI - 0.1 and p[1] < ARCH + 0.1 and (abs(p[2] - WF) <= SLOT + 0.1 or abs(p[2] - WR) <= SLOT + 0.1)

    def body(c):
        p = c.p
        x, y, z = p
        fx, fy, fz, ax = math.floor(x), math.floor(y), math.floor(z), abs(x)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if y < NOSE - 0.1:
                return ("rubber", 0) if well(p) else (C, 3.0)
            if ZS <= z <= ZB and ax < 13.5:
                if z > ZR:
                    return ("rubber", 1.4)                      # the shelf under the rear glass
                return ("rubber", 1 if fz % 4 else 0)          # carpet, seen through the glass
            if fz == ZS - 1 and (-12 <= x < -3 or 1 <= x < 10):
                return ("rubber", 0)                            # wipers at rest
            if ZS <= z <= ZB:
                return (C, 3.4)                                 # the shoulder beside the glass: it catches the light
            if ax >= 15:
                return top(2.2)
            if z > ZB and fz == ZB + 1 and ax < 13:
                return top(1.0)                                 # the boot lid's seam
            return top(3.4 if 9 <= x < 12 else 2.8)
        if c.face in ("east", "west"):
            if well(p):
                return ("rubber", 0)
            if ax < XI + 0.5 and DZ[0][0] <= z < DZ[1][1]:
                # the cabin's side and the doors' insides, seen when a door stands open: a bright sill plate, pale
                # leather, a strip of wood under the glass
                if fy <= SILL + 1:
                    return ("blade", 3.4)
                return ("wood", 2.2) if fy == BELT - 2 else (leather, 2.4 if fy < BELT - 2 else 3.0)
            if fy == SILL:
                return ("blade", 3.0)                           # a chrome sill
            if fy == BELT - 1:
                return ("blade", 4.0)                           # the chrome line under the glass, end to end
            if fz in (DZ[0][0], DZ[1][0], DZ[1][1] - 1) and SILL < fy < BELT - 1:
                return (C, 0.2)                                 # the doors' seams: two doors that meet in the middle
            if fy == 15 and fz in (DZ[1][0] - 3, DZ[1][0] - 2, DZ[1][0] + 1, DZ[1][0] + 2):
                return ("blade", 4)                             # their handles, side by side
            if fz == -L + 2 and fy == 13:
                return ("fox", 3)
            if fz == L - 3 and fy == 13:
                return ("signal_red", 1)
            if fy == 16:
                return (C, 3.3)                                 # the light along the shoulder
            if fy == 17:
                return (C, 2.7)
            return col(y)
        if c.face == "north":
            if well(p):
                return ("rubber", 0)
            if z < -L + TIP + 0.1 and 4 <= fy <= 8 and 6.5 <= ax < 13.5:
                # an intake each side under the lamps: dark mesh, a chrome blade across it
                if fy == 6:
                    return ("blade", 3.8)
                return ("rubber", 0 if (fx + fy) % 2 else 1)
            if fy == SILL:
                return ("blade", 3.0)
            return col(y) if y < BELT - 1 else top(2.8)
        if c.face == "south":
            if well(p):
                return ("rubber", 0)
            if z > L - 0.1:
                if 7 <= fy <= 10 and ax < 6:
                    return (C, 0.3 if ax < 5 else 0.9)          # the plate's recess (the name plate stands in it)
                if fy == 11 and ax < 7:
                    return ("blade", 3.6)                       # a bright strip over it
                if fy <= SILL + 1:
                    return ("rubber", 1) if fy > SILL else ("blade", 3.0)
            return col(y) if y < BELT - 1 else top(2.8)
        return None
    paint = PAINT(decal=body)

    m.group("body")
    # below the shoulder: a middle, the two ends (their last 1.5 px cut in at the corners and a little lower)
    m.box("core", (-XI, SILL, WF - SLOT), (XI, ARCH, WR + SLOT), paint)
    m.box("nose", (-W, SILL, -L + TIP), (W, ARCH, WF - SLOT), paint)
    m.box("nose_tip", (-W + TIP, SILL, -L), (W - TIP, ARCH, -L + TIP), paint)
    m.box("tail", (-W, SILL, WR + SLOT), (W, ARCH, L - TIP), paint)
    m.box("tail_tip", (-W + TIP, SILL, L - TIP), (W - TIP, ARCH, L), paint)
    # the shoulder: over everything, the hood and the boot its top
    m.box("shoulder", (-XI, ARCH, -L + TIP), (XI, BELT, L - TIP), paint)
    m.box("shoulder_tip", (-W + TIP, ARCH, -L), (W - TIP, NOSE, -L + TIP), paint)
    m.box("shoulder_tip", (-W + TIP, ARCH, L - TIP), (W - TIP, NOSE, L), paint)
    for s in (1, -1):
        xa, xb = sorted((XI * s, W * s))
        # the sides: ahead of and behind the doors they are the body's, between the seams they are the doors' (parts)
        m.box("sill", (xa, SILL, WF + SLOT), (xb, ARCH, DZ[0][0]), paint)
        m.box("sill", (xa, SILL, DZ[1][1]), (xb, ARCH, WR - SLOT), paint)
        m.box("shoulder", (xa, ARCH, -L + TIP), (xb, BELT, DZ[0][0]), paint)
        m.box("shoulder", (xa, ARCH, DZ[1][1]), (xb, BELT, L - TIP), paint)
        for k, (z0, z1) in enumerate(DZ):
            d = DOORS[(s, k)]
            d.box("door", (xa, SILL, z0), (xb, ARCH, z1), paint)
            d.box("door_shoulder", (xa, ARCH, z0), (xb, BELT, z1), paint)
        # the arches: three steps in each upper corner close the square opening into a round one
        for wz in (WF, WR):
            for side in (-1, 1):
                edge = wz + side * SLOT
                for deep, lo, hi in ((3, 16, ARCH), (2, 14.5, 16), (1, 12, 14.5)):
                    z0, z1 = sorted((edge, edge - side * deep))
                    m.box("arch", (xa, lo, z0), (xb, hi, z1), paint(decal=lambda c: (C, 0.5) if c.face in ("down", "north", "south") else col(c.p[1])))

    # the hood's raised middle
    def dome(c):
        if c.face == "up":
            return ("blade", 4.2) if abs(c.p[0]) < 0.5 else top(3.6 if 2 <= c.p[0] < 4 else 3.0)
        return top(2.0)
    m.box("hood_dome", (-6.5, BELT, -L + TIP + 1), (6.5, BELT + 0.6, ZS - 2), Paint(U, 2, noise=0, grad=0, decal=dome))

    # the radiator shell: tall, standing proud of the nose and above the hood; fine chrome slats in a bright frame
    GT = BELT + 0.75
    def shell(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "north":
            if c.u in (0, c.nu - 1) or y >= GT - 1.2 or fy <= 5:
                return ("blade", 4.5)
            return ("blade", 3.7) if c.u % 2 == 0 else ("tint", 0.6)
        if c.face == "up":
            return ("blade", 4.7)
        return ("blade", 3.0)
    m.box("radiator", (-5.5, 5, -L - 1.5), (5.5, GT, -L), Paint("blade", 3, noise=0, decal=shell))
    # the mascot (a part): a small winged figure, drawn down inside the shell; it comes up 2.5 px when the engine runs
    kit["mascot"].box("mascot", (-0.5, GT - 2.5, -L - 1.1), (0.5, GT - 0.4, -L - 0.4), Paint("blade", 4, noise=0, rim=0.3))
    kit["mascot"].box("mascot_wing", (-1.5, GT - 1.4, -L - 1.3), (1.5, GT - 0.9, -L - 0.6), Paint("blade", 4, noise=0, rim=0.3))

    # chrome: a blade along the foot of each end, two exhaust tips
    m.box("front_blade", (-13, SILL, -L - 0.6), (13, SILL + 0.75, -L), CHROME)
    m.box("rear_blade", (-13, SILL, L), (13, SILL + 0.75, L + 0.6), CHROME)
    for s in (1, -1):
        xa, xb = sorted((7.5 * s, 11.5 * s))
        m.box("exhaust", (xa, SILL + 0.75, L), (xb, SILL + 2.5, L + 0.9),
              CHROME(decal=lambda c: ("rubber", 0) if c.face == "south" and 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1 else None))
        d = DOORS[(s, 0)]
        xa, xb = sorted((14 * s, 15.5 * s))
        d.box("mirror_arm", (xa, BELT, -13.5), (xb, BELT + 1, -12.5), CHROME)
        xa, xb = sorted((15.5 * s, 18.5 * s))
        d.box("mirror", (xa, BELT, -14), (xb, BELT + 2.5, -12), PAINT(decal=lambda c: _mirror(c) or (C, 2.4)))

    m.group("lights")

    def headlight(c):
        # a slim lamp in a bright frame: a running light along its top, two bright squares under it
        if c.face != "north":
            return ("blade", 3)
        if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return ("blade", 4.3)
        if c.v == c.nv - 2:
            return ("lamp", 4.8)
        return ("lamp", 4.2) if c.u in (2, 3, 6, 7) else ("tint", 1.2)

    def tail_lamp(c):
        # a slim lamp: red, a bright line through it, a bright frame
        if c.face != "south":
            return ("signal_red", 0)
        if c.u in (0, c.nu - 1):
            return ("blade", 3.6)
        return ("signal_red", 2.2 if c.v in (1, 2) else 0.9)
    for s in (1, -1):
        xa, xb = sorted((6.5 * s, 14 * s))
        m.box("headlight", (xa, 13, -L - 0.5), (xb, 16.5, -L), Paint("lamp", 3, decal=headlight, **lamp()))
        xa, xb = sorted((7 * s, 14 * s))
        m.box("tail_lamp", (xa, 12.5, L), (xb, 16.5, L + 0.5), Paint("signal_red", 1, decal=tail_lamp, **lamp()))
    m.box("tail_bar", (-7, 14, L), (7, 15, L + 0.3), CHROME)

    m.group("cabin")

    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 9 and ZS + 12 <= z < ZR - 3:
                # a dark glass panel in the roof
                if abs(x) >= 8 or z < ZS + 13 or z >= ZR - 4:
                    return ("rubber", 1)
                return ("glass", 1.6) if (math.floor(z) - math.floor(x)) in (-3, -2, 6) else ("tint", 0.8)
            return top(3.4 if 9 <= x < 12 else 2.8)
        return top(2.4) if y > ROOF + 0.5 else ("blade", 3.6)        # a bright drip rail
    h = (ROOF - BELT) / math.cos(math.radians(45))
    z_top = ZS + (ROOF - BELT)                                        # where the screen meets the roof
    RP = Paint(U, 2, noise=0, grad=0, decal=roof)
    m.box("roof", (-13.5, ROOF, z_top - 0.5), (13.5, ROOF + 1, ZR + 0.5), RP)
    m.box("roof_top", (-12.5, ROOF + 1, z_top + 0.5), (12.5, ROOF + 1.4, ZR - 0.5), RP)
    m.box("windscreen", (-13.5, BELT, ZS), (13.5, BELT + h, ZS + 0.5), glass(frame=("blade", 3), sun=2, wipers=True, streak=0.25),
          rotation=("x", 45, (0, BELT, ZS)))
    m.box("rear_window", (-13.5, ROOF - h, ZR), (13.5, ROOF, ZR + 0.5), glass(frame=("blade", 3), sun=3, streak=0.3),
          rotation=("x", -45, (0, ROOF, ZR)))

    def side_window(c):
        x, y, z = c.p
        front = ZS + (y - BELT)
        back = ZR + (ROOF - y)
        if z < front - 0.3 or z > back + 0.3:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw(U, 1)
        if z >= DZ[1][1] - 2:
            # the wide rear pillar: painted, a small bright crest on it
            if z < DZ[1][1] - 1:
                return raw("blade", 4)
            if DZ[1][1] + 1 <= z < DZ[1][1] + 3 and BELT + 3 <= y < BELT + 5:
                return raw("blade", 4)
            return raw(U, 2)
        if z < front + 1 or y > ROOF - 1 or y < BELT + 1:
            return raw("blade", 4)                           # a bright frame round the glass
        if DZ[1][0] - 1 <= z < DZ[1][0] + 1:
            return raw("tint", 0)                            # the middle pillar: black
        d = math.floor(z) - math.floor(y)
        if d in (-30, -29, -14):
            return raw("glass", 3)
        return ("raw", CLEAR)
    wp = Paint("glass", 2, noise=0, light=False, decal=side_window)
    for s in (1, -1):
        xa, xb = min(13.5 * s, 14 * s), max(13.5 * s, 14 * s)
        m.box("side_window", (xa, BELT, ZS), (xb, ROOF, DZ[0][0]), wp)
        m.box("side_window", (xa, BELT, DZ[1][1]), (xb, ROOF, ZB), wp)
        for k, (z0, z1) in enumerate(DZ):
            DOORS[(s, k)].box("side_window", (xa, BELT, z0), (xb, ROOF, z1), wp)

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        if c.face == "south":
            if -9 <= x < -5 and math.floor(y) == BELT + 1:
                return ("cream", 4) if math.floor(x) in (-9, -6) else ("tint", 1)      # two pale dials
            return ("wood", 2.4 if math.floor(y) == BELT + 1 else 1.6)
        if c.face == "up":
            return ("wood", 3) if math.floor(z) == -12 else ("rubber", 1)
        return None
    m.box("dash", (-13, BELT, -14), (13, BELT + 2, -11), Paint("wood", 2, noise=0, decal=dash))
    m.box("column", (-7.5, BELT + 1, -11), (-6.5, BELT + 2.5, -10.5), PLASTIC)
    m.box("wheel", (-9.5, BELT + 2, -10.5), (-4.5, BELT + 6, -10), Paint("rubber", 1, noise=0, light=False, decal=_steering(("blade", 4))))
    seat = dict(bolster=(leather, 2), insert=(leather, 3), stitch=("leather", 2))
    for s in (1, -1):
        lo, hi = (min(2.5 * s, 12 * s), BELT, -1), (max(2.5 * s, 12 * s), BELT + 6.5, 1)
        m.box("front_seat", lo, hi, Paint(leather, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        m.box("cushion", (lo[0], BELT, -7), (hi[0], BELT + 1.5, -1), Paint(leather, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        xa, xb = sorted((5 * s, 10 * s))
        m.box("headrest", (xa, BELT + 6.5, -0.5), (xb, BELT + 8.5, 0.5), Paint(leather, 2, noise=0))
        lo, hi = (min(2.5 * s, 12 * s), BELT, 14), (max(2.5 * s, 12 * s), BELT + 6.5, 16)
        m.box("rear_seat", lo, hi, Paint(leather, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    m.box("rear_cushion", (-12, BELT, 8), (12, BELT + 1.5, 14),
          Paint(leather, 2, noise=0, decal=upholstery(lo=(-12, 0, 8), hi=(12, 0, 14), **seat)))
    m.box("arm_rest", (-1.5, BELT + 1.5, 8), (1.5, BELT + 3.5, 14), Paint("wood", 2, noise=0))
    m.display = _held_display(m)
    return kit


# ================================================================================================
# 몬스터 오프로더 (a tall, square off-roader lifted on huge wheels; its id is still luxury_suv) 4.6 x 2.7 x 3.2 blocks
# ================================================================================================

SUV_LIFT, SUV_SCALE, SUV_WHEEL = 16, 2.5, 26


def wheel_suv():
    """The monster off-roader's wheel: 1.625 blocks across (26 px), 10 px wide, a dark eight-spoke rim with a bright
    lip deep in a huge tyre."""
    return _wheel("wheel_suv", SUV_WHEEL, 10, cap="blacksteel", cap_d=13, spokes=8, lip="blade")


def luxury_suv(colour="leaf_dark", leather="leather", name="luxury_suv"):
    return suv_kit(colour, leather, name).body


def suv_kit(colour="leaf_dark", leather="leather", name="luxury_suv"):
    """A box lifted a block off the ground on wheels as tall as its doors (SUV_LIFT: it is drawn as if it stood on
    the ground, and everything under its floor, the frame, the axles and the long shocks, is drawn below zero).
    Every panel flat and every corner square: a short level hood with a turn lamp standing
    on each front corner, a flat grille of three bright bars between two round lamps, a steel bumper with a guard
    bar, black flares over the arches and a running board between them, an upright windscreen, tall square windows,
    a rack of four lamps over the screen and a rail along each side of the roof, and on the flat back a door with
    the spare wheel on it, a tall narrow lamp low at each corner, two bright exhaust pipes out of each side under
    the rear door. Inside: four seats of tan leather.

    Its parts: four doors (door_fl, door_fr, door_rl, door_rr), each hinged at its front edge; the front ones are
    stepped (they end over the wheel arch)."""
    kit = Kit(name, ("door_fl", "door_fr", "door_rl", "door_rr"), lift=SUV_LIFT, scale=SUV_SCALE)
    m = kit.body
    DOORS = {(-1, 0): kit["door_fl"], (1, 0): kit["door_fr"], (-1, 1): kit["door_rl"], (1, 1): kit["door_rr"]}
    DZ = ((-17, 0), (0, 17))           # the doors above the arches' height; below it they end at the wells
    C = colour
    PAINT = Paint(C, 2, noise=0, grad=0)
    W, L = 15, 36
    WF, WR, SLOT = -22, 22, 9
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
            if ax < 10.5 and DZ[0][0] <= z < DZ[1][1]:
                # the cabin's side and the doors' insides, seen when a door stands open
                if fy <= 8:
                    return ("blade", 3.0)
                return ("rubber", 1.4) if fy < 17 else (leather, 2.2)
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
    # the sides between the seams are the doors' (parts): the lower half between the wells, the upper half over them too
    m.box("upper", (-10, 16, -L), (10, BELT, L), paint)
    for s in (1, -1):
        xa, xb = sorted((10 * s, W * s))
        m.box("upper", (xa, 16, -L), (xb, BELT, DZ[0][0]), paint)
        m.box("upper", (xa, 16, DZ[1][1]), (xb, BELT, L), paint)
        for k, (z0, z1) in enumerate(DZ):
            d = DOORS[(s, k)]
            d.box("door", (xa, BOTTOM, max(z0, WF + SLOT)), (xb, 16, min(z1, WR - SLOT)), paint)
            d.box("door_upper", (xa, 16, z0), (xb, BELT, z1), paint)
        # two bright exhaust pipes out of the side, lying on the running board under the rear door
        xa, xb = sorted((W * s, (W + 1.75) * s))
        for z0 in (5.5, 9):
            m.box("side_pipe", (xa, 6.5, z0), (xb, 8, z0 + 2.5), CHROME(decal=lambda c: ("rubber", 0) if c.face in ("east", "west") and 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1 else None))

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
        DOORS[(s, 0)].box("mirror_arm", (xa, BELT + 0.75, -15.5), (xb, BELT + 1.75, -14.5), PLASTIC)
        xa, xb = sorted((15 * s, 18 * s))
        DOORS[(s, 0)].box("mirror", (xa, BELT, -16), (xb, BELT + 3.5, -14), Paint("rubber", 1, noise=0, decal=_mirror))

    # under the floor (drawn below the body: the ground is SUV_LIFT px under zero): a ladder frame, an axle with a
    # fat differential at each end, a shaft between them, and two long shocks a wheel with bright coils
    AXLE = SUV_WHEEL / 2 - SUV_LIFT                     # the wheels' middle
    under = Paint("blacksteel", 2, noise=0, rim=0.3)
    def coil(c):
        if c.face in ("up", "down"):
            return ("blacksteel", 1)
        return ("hazard", 3.2 if math.floor(c.p[1] * 1.0) % 2 else 2.0)
    for s in (1, -1):
        xa, xb = sorted((5.5 * s, 8 * s))
        m.box("frame_rail", (xa, BOTTOM - 3, -L + 2), (xb, BOTTOM, L - 2), under)
    for wz in (WF, WR):
        m.box("axle", (-17, AXLE - 1.5, wz - 1.5), (17, AXLE + 1.5, wz + 1.5), under)
        m.box("differential", (-3.5, AXLE - 3, wz - 3), (3.5, AXLE + 3, wz + 3), Paint("steel", 2, noise=0, rim=0.4))
        m.box("cross_member", (-5.5, BOTTOM - 2.5, wz - 1), (5.5, BOTTOM - 1, wz + 1), under)
        for s in (1, -1):
            for dz in (-3.5, 2):
                xa, xb = sorted((9 * s, 10.5 * s))
                m.box("shock", (xa, AXLE + 1.5, wz + dz), (xb, BOTTOM + 1, wz + dz + 1.5), Paint("hazard", 3, noise=0, decal=coil))
            # a link from the axle up to the frame, front and back
            xa, xb = sorted((6 * s, 7.5 * s))
            m.box("link", (xa, AXLE - 0.25, wz - 8.5), (xb, AXLE + 1.0, wz + 8.5), under)
    m.box("drive_shaft", (-1, AXLE - 0.75, WF + 3), (1, AXLE + 0.75, WR - 3), Paint("steel", 3, noise=0))
    m.box("skid_plate", (-10, BOTTOM - 1.5, -L - 1), (10, BOTTOM, WF - SLOT + 2), Paint("steel", 3, noise=0, rim=0.3))
    # a step under the doors on two hangers (the floor is a block up)
    for s in (1, -1):
        xa, xb = sorted((W * s, (W + 3) * s))
        m.box("step", (xa, BOTTOM - 6.5, WF + SLOT + 2), (xb, BOTTOM - 5.5, WR - SLOT - 2),
              Paint("rubber", 1, noise=0, rim=0.3, decal=lambda c: ("blade", 3) if c.face in ("east", "west") else ("rubber", 2 if (math.floor(c.p[2]) % 2) else 1)))
        xa, xb = sorted(((W + 1) * s, (W + 2) * s))
        for z0 in (WF + SLOT + 3, WR - SLOT - 4):
            m.box("step_hanger", (xa, BOTTOM - 5.5, z0), (xb, BOTTOM - 1.5, z0 + 1), under)

    # steel bumpers; in front a guard bar on two posts
    steel = Paint("steel", 3, noise=0, rim=0.3)
    m.box("bumper", (-W - 0.5, 8, -L - 2), (W + 0.5, 11, -L), steel(decal=bumper_decal(plate_w=0, strip=2)))
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
    wp = Paint("glass", 2, noise=0, light=False, decal=side_window)
    for s in (1, -1):
        xa, xb = min(14 * s, 14.5 * s), max(14 * s, 14.5 * s)
        m.box("side_window", (xa, BELT, -18), (xb, ROOF, DZ[0][0]), wp)
        m.box("side_window", (xa, BELT, DZ[1][1]), (xb, ROOF, L - 0.5), wp)
        for k, (z0, z1) in enumerate(DZ):
            DOORS[(s, k)].box("side_window", (xa, BELT, z0), (xb, ROOF, z1), wp)

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
    for ctx in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        m.display[ctx]["scale"] = [round(v * 2.0 / SUV_SCALE * 0.8, 3) for v in m.display[ctx]["scale"]]
    return kit


def _part(kit, name):
    return lambda: kit().all()[name]


ALL = {
    "hyper_car": hyper_car,
    "wheel_hyper": wheel_hyper,
    **{f"hyper_car_{p}": _part(hyper_kit, f"hyper_car_{p}") for p in ("door_l", "door_r", "wing")},
    "grand_sedan": grand_sedan,
    "wheel_grand": wheel_grand,
    **{f"grand_sedan_{p}": _part(grand_kit, f"grand_sedan_{p}") for p in ("door_fl", "door_fr", "door_rl", "door_rr", "mascot")},
    "luxury_suv": luxury_suv,
    "wheel_suv": wheel_suv,
    **{f"luxury_suv_{p}": _part(suv_kit, f"luxury_suv_{p}") for p in ("door_fl", "door_fr", "door_rl", "door_rr")},
}
