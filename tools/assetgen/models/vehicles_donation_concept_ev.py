"""
The donation car 콘셉트 EV (content item `concept_ev`): a four-seat electric concept car. An original design: no
real make, no badge, no grille.

Drawn like the showcase car (models/vehicles_showcase.py, phantom_gt: read its doc first): 32 px a block. The body is
a field of 1 px cells (bake.voxels) from sharp, straight profiles, drawn at twice its real size and shrunk by 4
(Kit scale 4), shown at vehicle.body-scale 2; the wheel drawn at twice its size and shrunk by 2, shown at 1. The parts
that move (the two gull-wing doors) are models of their own, drawn in the body's space where they lie at rest.
Drawn coordinates (32 px a block): the ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT; the
driver sits on the left (-x).

The shape: a very low, wide wedge of flat planes and sharp creases. A knife-edge nose carrying one thin LED bar the
whole width (wrapping round its chamfered corners), a straight hood rising between two blade fenders that stand up
over the front wheels, a long canopy of black glass (screen, roof and side glass, all one dark sheet over the pearl
body: the two-tone), ending in a short upright rear window, and behind it two floating C-pillars: flying buttresses
from the roof's corners down to the tail, with open air between them and the deck (the light shows through). Flat
broad shoulders over angular (cut-cornered) wheel arches; a satin black blade set into the lower side, a glowing
line under it; the tail cut off square with a thin red bar the whole width and a satin black diffuser with real fins.
Flush door handles, camera "mirrors" on thin stalks, big aero wheels (turbine covers with a lit ring, low tyres).
Inside: floating seats on one pedestal each, one wide screen across the dash, a yoke.

Paint arguments (one more call = one more colour; 8-step ramps like the ones registered here read best):
colour (the body), roof (the black glass canopy), accent (the glowing line, the wheels' rings, the cabin's light
strips), trim (the seats).

Its parts: door_l and door_r, gull-wing doors: each the side between the wheels (above the sill) with its whole side
glass, hinged along the roof's edge (vehicles.yml: axis forward, they swing up and out).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, voxels
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp
from stsv_assets import presets
from models.vehicles import _held_display
from models.vehicles_premium import Kit


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def ev_ramp(name, *spec):
    """Register a ramp (as the palette makes them): a new body colour for tuning is one line here."""
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


ev_ramp("ev_pearl", 228, 0.014, 0.80, 0.40, 8, False)    # cool pearl white: blue-grey in the shade
ev_ramp("ev_glass", 250, 0.022, 0.24, 0.30, 6, False)    # the black glass canopy
ev_ramp("ev_satin", 255, 0.010, 0.20, 0.14, 4, False)    # satin black: lower blades, diffuser, wells
ev_ramp("ev_cyan", 190, 0.140, 0.76, 0.34, 5, True)      # the accent light
ev_ramp("ev_led", 205, 0.040, 0.93, 0.10, 3, True)       # cool white LEDs
ev_ramp("ev_tail", 18, 0.240, 0.55, 0.30, 4, True)       # the tail bar
ev_ramp("ev_trim", 70, 0.012, 0.84, 0.26, 5, False)      # white cabin leather
ev_ramp("ev_steel", 240, 0.012, 0.66, 0.46, 7, False)    # brushed metal
ev_ramp("ev_screen", 200, 0.100, 0.66, 0.36, 4, True)    # the dash screen

# ================================================================================================
# Shape (drawn px, 32 a block)
# ================================================================================================

NOSE, TAIL = -78, 78           # z of the nose and the tail
WF, WR = -50, 50               # front / rear axle (z)
WY, WRAD = 14, 14              # wheel centre height, wheel radius (a 0.875 block wheel)
TRACK = 22                     # the wheel's inner face (x): it stands from 22 out to 32
GROUND = 2                     # the body's lowest cells
W = 32                         # the body's half width
D0, D1 = -32, 32               # the doors, between their seams (z)
CAB0 = -48                     # the screen's foot (the hood ends here)
KNEE = -36                     # where the screen bends from 45 to 22.5 degrees
ROOF = 47.5                    # the roof's top
RW = 31.5                      # the upright rear window (z)
BUT = 72                       # where the buttresses land on the tail
SILL = 8                       # the door's lower edge
T = math.tan(math.radians(22.5))


def clamp(t):
    return max(0.0, min(1.0, t))


def tent(z, c, w):
    """1 at c, falling straight to 0 at c +- w (creases, not swells)."""
    return clamp(1 - abs(z - c) / w)


def plan(z):
    """The body's half width at z: straight sides, the nose's and tail's corners cut off at an angle."""
    if z < -68:
        return W - (-68 - z) * 0.8
    if z > 70:
        return W - (z - 70) * 0.75
    return W


def belt(z):
    """The height of the hood / deck along the middle: the hood one straight plane up from the knife-edge nose, the
    belt almost level behind it, a lip at the tail."""
    if z < CAB0:
        return 26 - (CAB0 - z) * T              # exactly 22.5 degrees: a tilted plane lies on it
    if z < 74:
        return 26 + 1.5 * clamp((z - CAB0) / 78)
    return 29.5                                   # the ducktail lip


def fender(z, ax):
    """How far the fenders stand above the belt: blades over the front wheels, haunches over the rear ones (only
    outside the buttresses), each a straight ramp across (a crease where it starts)."""
    f = 7.0 * tent(z, WF, 30) * clamp((ax - 14) / 5)
    r = 5.0 * tent(z, WR, 28) * clamp((ax - 24) / 3)
    return f + r


def top(z, ax):
    h = belt(z) + fender(z, ax)
    e = plan(z) - ax                              # the shoulder: a 45 degree chamfer along its edge
    if e < 2:
        h -= 2 - e
    return h


def crease(z):
    """The sharp line over the satin blade set into the lower side: rising towards the rear (the wedge)."""
    return 13 + (z - D0) * 0.07


def half_width(z, y):
    w = plan(z)
    if -34 < z < 34 and SILL - 1 <= y < crease(z):
        w -= 1.5                                  # the satin blade, set in under the crease
    if y < SILL - 1:
        w -= 2 + (SILL - 1 - y) * 0.6             # the sill tucked in
    return w


def roof(z):
    """The canopy's top: the screen at 45 then 22.5 degrees, a long level roof, the upright rear window."""
    if z < KNEE:
        return belt(CAB0) + (z - CAB0)
    return min(ROOF, belt(CAB0) + (KNEE - CAB0) + (z - KNEE) * T)


def glass_w(z, y):
    """The canopy's half width at y: its sides lean in at 22.5 degrees, its top edge cut off."""
    r = roof(z)
    w = 26 - (y - belt(z)) * T
    if r - y < 2:
        w -= (2 - (r - y)) * 1.5
    return w


def buttress(z):
    """The floating C-pillar's top (y) at z, or None: straight from the roof's rear corner down to the tail."""
    if RW - 1 <= z < BUT:
        return ROOF - (z - (RW - 1)) * T
    return None


def but_x(y):
    """The buttress' outer face (x) at y: it leans in like the canopy's sides."""
    return 26.5 - (y - 27) * T


def in_arch(z, y, ax):
    """Inside the cut round a wheel: an angular arch (a flat top, its corners cut at 45 degrees)."""
    if ax < TRACK - 1.5:
        return False
    for wz in (WF, WR):
        dz, dy = abs(z - wz), y - WY
        if dz < 17 and dy < 16.5 and dz + max(dy, 0) < 26:
            return True
    return False


def near_arch(z, y, grow):
    for wz in (WF, WR):
        dz, dy = abs(z - wz), y - WY
        if dz < 17 + grow and dy < 16.5 + grow and dz + max(dy, 0) < 26 + grow:
            return True
    return False


# ================================================================================================
# The cells
# ================================================================================================

def _cells():
    body, doors = {}, {-1: {}, 1: {}}
    for z in range(NOSE, TAIL):
        zc = z + 0.5
        wmax = plan(zc)
        for x in range(-int(math.ceil(wmax)), int(math.ceil(wmax))):
            xc = x + 0.5
            ax = abs(xc)
            t = top(zc, ax)
            hmax = max(t, roof(zc) if CAB0 <= zc <= RW else 0, buttress(zc) or 0)
            for y in range(GROUND, int(math.ceil(hmax)) + 1):
                yc = y + 0.5
                lab = _cell(xc, yc, zc, ax, t)
                if lab is None:
                    continue
                side = -1 if xc < 0 else 1
                if _is_door(xc, yc, zc, ax, lab):
                    doors[side][(x, y, z)] = lab
                else:
                    body[(x, y, z)] = lab
    return body, doors


def _cell(x, y, z, ax, t):
    lower = y <= t and ax <= half_width(z, y)
    # the knife-edge nose: the face under the light bar is upright, the chin under it slopes back
    if lower and z < NOSE + 8 and y < 6.5 and z < NOSE + (6.5 - y) * 1.3 and not (y < 4.5 and z > NOSE + 1.5):
        lower = False
    # the diffuser: a recess under the tail with fins standing in it
    if lower and z > TAIL - 6 and y < 11 and ax < 21:
        if math.floor(ax) % 5 != 2:
            lower = False
    if lower and in_arch(z, y, ax):
        return None
    if lower:
        return _label_lower(x, y, z, ax, t)
    # the canopy (black glass)
    if CAB0 <= z <= RW and y > belt(z) - 0.5 and y <= roof(z) and ax <= glass_w(z, y):
        return _label_canopy(x, y, z, ax)
    # the floating C-pillars
    b = buttress(z)
    if b is not None and b - 3.5 < y <= b + 0.5 and but_x(y) - 3.5 < ax <= but_x(y):
        return "paint"
    return None


def _label_lower(x, y, z, ax, t):
    w = half_width(z, y)
    skin = (w - ax) < 3 or (t - y) < 3 or y < GROUND + 2 or z < NOSE + 4 or z > TAIL - 4
    # the cabin: a tub hollowed out under the canopy
    if -38 < z < RW - 1 and ax < w - 3.5 and y > 6.5:
        return None
    # the wheel wells: dark round the arch cuts
    if TRACK - 4 < ax < TRACK - 1.5 and near_arch(z, y, 1.2) and y < t - 2:
        return "well"
    if near_arch(z, y, 1.2) and ax >= TRACK - 1.5 and (w - ax) < 3:
        if not near_arch(z, y, 0.01):
            pass
    if not skin:
        return "core"
    fy = math.floor(y)
    corner_f = z < -66 and (plan(z) - ax) < 2.5
    corner_r = z > 68 and (plan(z) - ax) < 2.5
    # the nose: one thin LED bar the whole width, wrapping round the cut corners, in a black frame
    if z < NOSE + 2 or corner_f:
        if fy == 11:
            return "led"
        if fy in (10, 12):
            return "frame"
        if y < 6.5:
            return "mesh" if y >= 4.5 else "satin"
    # the tail: the red bar the whole width in its black frame, the diffuser under it
    if z > TAIL - 2 or corner_r:
        if fy == 24:
            return "tail"
        if fy in (23, 25):
            return "frame"
    if y < 11 and z > TAIL - 7:
        return "satin"
    # the sill: satin black; the glowing line along the side's foot; the satin blade set in under the crease
    if (w - ax) < 3:
        if y < SILL - 1:
            return "satin"
        if -34 < z < 34:
            if fy == SILL - 1:
                return "accent"
            if y < crease(z):
                return "satin"
    # the rear deck between the buttresses: a black louvred cover
    if RW + 1 < z < BUT - 1 and ax < 17.5 and (t - y) < 3:
        return "louvre"
    return "paint"


def _label_canopy(x, y, z, ax):
    w = glass_w(z, y)
    r = roof(z)
    if (w - ax) > 1.6 and (r - y) > 1.6:
        return None                                   # the cabin's air
    if z < -13 and (r - y) < 1.7 and ax < w - 2.2:
        return None                                   # the screen: open, its glass is a tilted pane
    if (w - ax) < 1.6 and belt(z) + 1.2 < y < r - 2.2 and -42 < z < RW - 1.5:
        return "tint"                                 # the side glass
    if z > RW - 1.2 and ax < w - 2 and belt(z) + 1 < y < r - 1.6:
        return "tint"                                 # the rear window
    return "roof"


def _is_door(x, y, z, ax, lab):
    if not (D0 <= z < D1):
        return False
    if lab in ("accent",):
        return False
    if y >= SILL and ax > half_width(z, y) - 3 and y <= top(z, ax):
        return True
    if CAB0 <= z <= RW and y > belt(z) and (glass_w(z, y) - ax) < 2.5 and y < roof(z) - 1.5:
        return True
    return False


# ================================================================================================
# Paints
# ================================================================================================

def _make_paints(C, R, A, TR):
    def body(c):
        """The pearl: lit from above, a crisp highlight along each crease, seams, the flush handles, the charge
        flap. The 1 px risers of a stepped top take the top's colour (no stripes)."""
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        inward = (f == "east" and x < 0) or (f == "west" and x > 0)
        if inward and ax < 29.5:
            return (TR, 2.4)                          # inside the door / tub (seen when a door is up)
        if f in ("north", "south", "east", "west") and top(z, ax) - y < 1.2 and y > 15:
            f = "up"
        if f == "down":
            return ("ev_satin", 0)
        if f == "up":
            w = plan(z)
            if w - ax < 2.2:
                return (C, 7)                         # the shoulder's chamfer catches the light
            if 13.5 < ax < 14.6 or 18.8 < ax < 19.6:
                return (C, 7)                         # the fender blades' creases
            if (abs(z - D0) < 0.5 or abs(z - D1) < 0.5) and ax > 24:
                return (C, 2.5)                       # door seams over the shoulder
            if abs(z - (NOSE + 10)) < 0.5 and ax < 13.5:
                return (C, 3.0)                       # the frunk's shut line
            if 13.5 < ax < 14.5 and z < CAB0 - 2 and z > NOSE + 10:
                return (C, 3.0)
            if 5 < ax < 8 and z < CAB0:
                return (C, 6.3)                       # a reflection band down the hood
            return (C, 5.5)
        if f in ("east", "west"):
            if (abs(z - D0) < 0.6 or abs(z - D1) < 0.6) and y > SILL:
                return (C, 1.0)                       # door seams
            fy = math.floor(y)
            # the flush handles: a slim dark slot with a lit lip over it
            for hz in (-10, 18):
                if hz <= z < hz + 6 and -34 < z < 34:
                    if fy == 21:
                        return ("ev_satin", 1)
                    if fy == 22:
                        return (C, 7)
            # the charge flap on the left rear quarter
            if x < 0 and 60 <= z < 65 and 20 <= y < 24:
                if z < 60.6 or z >= 64.4 or y < 20.6 or y >= 23.4:
                    return (C, 1.2)
                return (C, 4.5)
            cz = crease(z) if -34 < z < 34 else 13
            if -34 < z < 34 and fy == math.floor(cz):
                return (C, 6.5)                       # the crease's lit edge over the blade
            return (C, 2.4 + 2.8 * clamp((y - 10) / 18))
        if f == "north":
            return (C, 4.0 + 1.6 * clamp((y - 8) / 12))
        if f == "south":
            fy = math.floor(y)
            if 9 <= fy <= 20 and abs(x) < 10.5 and (abs(x) > 9.5 or fy in (9, 20)):
                return (C, 1.6)                       # the plate's recess outline
            return (C, 3.0 + 2.0 * clamp((y - 10) / 16))
        return None

    def canopy(c):
        """Black glass: a long diagonal reflection on the roof, a soft gradient up the sides."""
        x, y, z = c.p
        if c.face == "up":
            k = (z + abs(x) * 0.7) % 26
            if abs(x) > glass_w(z, y) - 1.6 - 0.5:
                return (R, 4.4)                       # the roof's edge catches the light
            return (R, 4.6 if k < 2 else 3.5 if k < 3.2 else 2.4)
        if c.face == "down":
            return (R, 0)
        return (R, 1.6 + 1.8 * clamp((y - 28) / 16))

    def tint(c):
        """Dark side glass: blue-black, two pale streaks running back and down."""
        x, y, z = c.p
        k = math.floor(z * 0.8 - y * 1.3) % 34
        if c.face in ("up", "down"):
            return (R, 2)
        if k in (0, 1):
            return ("ev_steel", 3)
        if k == 4:
            return ("ev_steel", 1)
        return (R, 0.6 + 1.2 * clamp((y - 28) / 15))

    def satin(c):
        if c.face == "up":
            return ("ev_satin", 2)
        if c.face == "down":
            return ("ev_satin", 0)
        return ("ev_satin", 1.3)

    def mesh(c):
        x, y, z = c.p
        a = x if c.face in ("north", "south", "up", "down") else z
        return ("ev_satin", 0) if (math.floor(a) + math.floor(y)) % 2 else ("ev_satin", 2.2)

    def louvre(c):
        x, y, z = c.p
        if c.face != "up":
            return ("ev_satin", 1)
        if abs(x) < 1:
            return (A, 2)                             # a light line down the middle
        return ("ev_satin", 0) if math.floor(z) % 3 == 0 else ("ev_satin", 2.4)

    def led(c):
        # the LED bar: a hot white strip broken into fine segments, a cooler reflector between them
        x, y, z = c.p
        k = math.floor(x if c.face in ("north", "south") else z)
        return ("raw", (240, 250, 255, 255)) if k % 4 else ("raw", (176, 214, 236, 255))

    def tail(c):
        x, y, z = c.p
        k = math.floor(x if c.face in ("north", "south") else z)
        return ("raw", (255, 44, 56, 255)) if k % 4 else ("raw", (196, 18, 34, 255))

    def frame(c):
        if c.face == "up":
            return ("ev_steel", 4)                    # a bright lip over the light
        return ("ev_satin", 0.6)

    def accent(c):
        return ("raw", (90, 236, 255, 255)) if c.face in ("east", "west", "north", "south") else (A, 2)

    return {
        "core": Paint(C, 3, noise=0, light=False),
        "paint": Paint(C, 5, noise=0, grad=0, rim=0, ao=0, decal=body),
        "roof": Paint(R, 3, noise=0, grad=0, rim=0, ao=0, decal=canopy),
        "tint": Paint(R, 1, noise=0, grad=0, rim=0, ao=0, decal=tint),
        "well": Paint("ev_satin", 0, noise=0, grad=0, rim=0, ao=0.4),
        "satin": Paint("ev_satin", 1, noise=0, grad=0, rim=0.3, ao=0.3, decal=satin),
        "mesh": Paint("ev_satin", 1, noise=0, grad=0, rim=0, decal=mesh),
        "louvre": Paint("ev_satin", 1, noise=0, grad=0, rim=0, decal=louvre),
        "frame": Paint("ev_satin", 1, noise=0, grad=0, rim=0, decal=frame),
        "led": Paint("ev_led", 2, noise=0, light=False, glow=True, decal=led),
        "tail": Paint("ev_tail", 2, noise=0, light=False, glow=True, decal=tail),
        "accent": Paint(A, 3, noise=0, light=False, glow=True, decal=accent),
    }


FLAT = ("paint", "roof", "tint", "core")      # stepped surfaces: their light is painted, the face shading is off


def _flat_voxels(m, cells, paints):
    box = m.box
    flat = [paints[k] for k in FLAT]

    def flat_box(name, frm, to, paint, **kw):
        if any(paint is p for p in flat):
            kw["shade"] = False
        return box(name, frm, to, paint, **kw)
    m.box = flat_box
    try:
        voxels(m, "shell", cells, lambda label: paints[label])
    finally:
        del m.box


def _pane_decal(c):
    """A see-through pane: two reflection streaks across it, a thin dark seal round it, the rest clear."""
    x, y, z = c.p
    if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
        return ("raw", (24, 28, 36, 255))
    d = math.floor(x * 0.9 + y * 1.4 - z * 0.3) % 40
    if d in (0, 1, 5):
        return ("raw", (230, 240, 255, 255)) if d != 5 else ("raw", (170, 190, 220, 255))
    return ("raw", (0, 0, 0, 0))


PANE = Paint("ev_glass", 1, noise=0, light=False, decal=_pane_decal)


# ================================================================================================
# The car
# ================================================================================================

_KIT = {}


def concept_kit(colour="ev_pearl", roof_="ev_glass", accent="ev_cyan", trim="ev_trim", name="concept_ev"):
    key = (colour, roof_, accent, trim, name)
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, ("door_l", "door_r"), scale=4.0)
    m = kit.body
    m.group("body")
    paints = _make_paints(colour, roof_, accent, trim)
    body, doors = _cells()
    for target, cells in ((m, body), (kit["door_l"], doors[-1]), (kit["door_r"], doors[1])):
        _flat_voxels(target, cells, paints)
    _hood(m, paints)
    _screen(m)
    _side_glass(kit, paints)
    _cabin(m, accent, trim)
    _cameras(kit, paints, accent)
    m.display = _held_display(m)
    _KIT[key] = kit
    return kit


def _hood(m, paints):
    """The hood between the fender blades: one opaque plane at 22.5 degrees laid over its 1 px steps, so it reads as a
    single sharp sheet from the knife-edge nose to the screen."""
    y0 = belt(NOSE) + 0.65
    length = (CAB0 - NOSE) / math.cos(math.radians(22.5))
    m.box("hood", (-14, y0 - 0.5, NOSE), (14, y0, NOSE + length), paints["paint"],
          rotation=("x", 22.5, (0, y0, NOSE)), shade=False)


def _screen(m):
    """The windscreen: two clear panes over the open stepped frame, one at 45 degrees from the cowl to the knee and
    one at 22.5 degrees from the knee to the roof."""
    y0 = belt(CAB0) + 0.3
    z0 = CAB0 - 0.3
    half = glass_w(-40, 30) - 2.0
    l1 = (KNEE - CAB0) / math.cos(math.radians(45)) + 0.2
    m.box("screen", (-half, y0, z0), (half, y0 + 0.5, z0 + l1), PANE, rotation=("x", 45, (0, y0, z0)))
    y1, z1 = roof(KNEE) + 0.3, KNEE
    half = glass_w(-25, 40) - 2.0
    l2 = (-13 - KNEE) / math.cos(math.radians(22.5))
    m.box("screen", (-half, y1, z1), (half, y1 + 0.5, z1 + l2), PANE, rotation=("x", 22.5, (0, y1, z1)))


def _side_glass(kit, paints):
    """The side glass as smooth sheets: a pane leaning in at 22.5 degrees per 4 px strip (its top follows the
    screen's line, so the strips are short at the front), the door's strips ride with the door."""
    tint = paints["tint"]
    for side, part in ((-1, "door_l"), (1, "door_r")):
        for z in range(-40, int(RW) - 2, 4):
            z1 = min(z + 4, RW - 2)
            yb = belt(z + 2) + 1.4
            yt = min(roof(z), roof(z1)) - 2.4
            if yt - yb < 1.5:
                continue
            wb = glass_w(z + 2, yb) + 0.3
            h = (yt - yb) / math.cos(math.radians(22.5))
            target = kit[part] if D0 <= z < D1 else kit.body
            lo, hi = sorted((side * wb, side * (wb + 0.5)))
            target.box("side_pane", (lo, yb, z), (hi, yb + h, z1), tint,
                       rotation=("z", side * 22.5, (side * wb, yb, z)))


def _cabin(m, A, TR):
    """What shows through the glass: four floating seats (each a slim shell on one pedestal), one wide screen across
    the dash, a yoke, a floating console."""
    def seat_decal(c):
        x, y, z = c.p
        if c.face in ("north", "south", "up") and abs((abs(x) % 22) - 11) > 3.4 and abs((abs(x) % 22) - 11) < 4.1:
            return (A, 2)                             # a light seam down each side of the insert
        return None
    leather = Paint(TR, 2, noise=0, grad=0, rim=0.5, decal=seat_decal)
    base = Paint("ev_satin", 2, noise=0, rim=0.4)
    for sz in (-9.6, 22.4):
        for sx in (-11, 11):
            m.box("pedestal", (sx - 2, 6, sz - 2), (sx + 2, 9, sz + 1), base)
            m.box("cushion", (sx - 5, 9, sz - 7), (sx + 5, 11.5, sz + 3), leather)
            m.box("back", (sx - 5, 9, sz + 3), (sx + 5, 30, sz + 5.5), leather)
            m.box("headrest", (sx - 3.5, 30, sz + 3.5), (sx + 3.5, 34, sz + 5.5), leather)
    # the dash: a slim blade the width of the cabin, one wide screen floating over it
    m.box("dash", (-23, 22, -40), (23, 24, -32), Paint(TR, 1, noise=0, rim=0.4,
          decal=lambda c: (A, 2) if c.face == "south" else None))
    screen = Paint("ev_screen", 1, noise=0, light=False, glow=True,
                   decal=lambda c: ("ev_screen", 3 if (math.floor(c.p[0]) % 9 in (1, 2) and math.floor(c.p[1]) == 26) else
                                    0 if math.floor(c.p[0]) % 15 == 7 else 2) if c.face == "south" else ("ev_satin", 1))
    m.box("display", (-21, 24.5, -35), (21, 28.5, -34), screen)
    m.box("display_stem", (-1, 24, -35.5), (1, 24.5, -34.5), Paint("ev_satin", 2, noise=0))
    # the yoke (driver on the left): two grips and a bar under a small hub, on a column from the dash
    ycx, ycy, ycz = -11, 21, -28
    grip = Paint("ev_satin", 1, noise=0, rim=0.6)
    m.box("column", (ycx - 0.75, ycy - 0.5, ycz - 4), (ycx + 0.75, ycy + 1, ycz), grip)
    m.box("yoke_hub", (ycx - 2, ycy - 1.5, ycz), (ycx + 2, ycy + 1.5, ycz + 1), Paint("ev_steel", 4, noise=0,
          decal=lambda c: (A, 3) if c.face == "south" and abs(c.p[0] - ycx) < 1 else None))
    m.box("yoke_bar", (ycx - 6, ycy - 2.5, ycz), (ycx + 6, ycy - 1.5, ycz + 1), grip)
    for gx in (-6, 5):
        m.box("yoke_grip", (ycx + gx, ycy - 3, ycz - 0.25), (ycx + gx + 1, ycy + 3, ycz + 1.25), grip)
    # the console: a floating spine between the front seats, a light line along its top
    m.box("console", (-2, 11, -30), (2, 13, 4), Paint(TR, 1, noise=0, rim=0.4,
          decal=lambda c: (A, 2) if c.face == "up" and abs(c.p[0]) < 0.5 else None))
    m.box("console_leg", (-1, 6, -2), (1, 11, 1), base)
    # the floor: a dark mat over the tub's floor
    m.box("floor", (-22, 7, -37), (22, 7.5, 29), Paint("ev_satin", 1, noise=0, rim=0,
          decal=lambda c: ("ev_satin", 2 if math.floor(c.p[2]) % 4 == 0 else 1) if c.face == "up" else None))


def _cameras(kit, paints, A):
    """The "mirrors": a slim camera pod on a thin stalk from the base of each door's glass."""
    pod = Paint("ev_satin", 2, noise=0, rim=0.5,
                decal=lambda c: (A, 3) if c.face == "south" and c.u == 1 and c.v == 1 else
                ("ev_satin", 3) if c.face == "up" else None)
    for side, part in ((-1, "door_l"), (1, "door_r")):
        d = kit[part]
        z = D0 + 3
        y = belt(z) + 1.0
        x = glass_w(z, y) - 0.5
        lo, hi = sorted((side * x, side * (W + 2)))
        d.box("camera_stalk", (lo, y, z + 1.5), (hi, y + 1, z + 2.5), paints["satin"])
        lo, hi = sorted((side * (W + 1), side * (W + 4)))
        d.box("camera", (lo, y - 0.5, z), (hi, y + 2.5, z + 4), pod)


def concept_ev(colour="ev_pearl", roof_="ev_glass", accent="ev_cyan", trim="ev_trim", name="concept_ev"):
    return concept_kit(colour, roof_, accent, trim, name).body


def _part(name):
    return lambda: concept_kit().all()[name]


# ================================================================================================
# The wheel: drawn at twice its size (28 px = 0.875 block across), shrunk by 2
# ================================================================================================

def wheel_concept(ring="ev_cyan", cover="ev_pearl", name="wheel_concept"):
    """콘셉트 EV's wheel: a big closed aero wheel on a low tyre. A turbine cover: a dished pale disc with twelve thin
    swept vanes round a dark centre, a lit ring near its rim, a dark hub with a dot of colour."""
    m = BModel(name, shift=(8, 8, 8), floor=None, scale=2.0)
    d, width = 28, 10
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 1:
                return ("ev_satin", 0)
            if rr > r - 2.6:
                return ("ev_satin", 1.6)                  # the low sidewall, a lit bead
            if rr > r - 3.6:
                return ("ev_steel", 5)                    # the rim's bright lip
            if rr > r - 4.6:
                return (ring, 3)                          # the lit ring
            if rr > r - 5.4:
                return ("ev_satin", 0)
            if rr < 2.2:
                return (ring, 3) if rr < 0.9 else ("ev_satin", 0)
            if rr < 3.6:
                return ("ev_steel", 2)
            a = math.atan2(z, y)
            t = ((a + rr * 0.16) * 12 / (2 * math.pi)) % 1.0     # twelve vanes swept round
            lit = 0.6 if (y > 0 and z < 1) else 0
            if t < 0.18:
                return (cover, 7 if lit else 6)               # a vane's lit edge
            if t < 0.5:
                return (cover, 4.6 + lit)
            return (cover, 3.2 + lit - (0.6 if rr < 6 else 0))
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.2)
        return ("ev_satin", 0 if k % 4 == 0 else 1)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("ev_satin", 1, noise=0, rim=0.2, decal=tire), axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    "concept_ev": concept_ev,
    "wheel_concept": wheel_concept,
    "concept_ev_door_l": _part("concept_ev_door_l"),
    "concept_ev_door_r": _part("concept_ev_door_r"),
}
