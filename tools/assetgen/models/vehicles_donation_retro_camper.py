"""
후원 · 레트로 캠핑밴 (retro_camper): a short, round 1960s-style camper van. An original design (no real make, no badge).

Drawn like every other car on the server (models/vehicles.py, vehicles_premium.py, and the donation muscle car
models/vehicles_donation_muscle_car.py, whose "angled panels" style this follows): 16 px a block, the body drawn at
its real size and shrunk by 2 (BModel scale 2), shown at vehicle.body-scale 2; the wheel a model of its own at 1.
The van is a handful of big clean boxes; every curve is a flat panel turned 22.5 or 45 degrees about one axis
(panel()): the roof rolls over into the sides, the nose and the back in two panels (67.5 and 22.5 degrees: a round
edge of radius 6), the four corners in plan are 45-degree chamfers, the face steps back over a 45-degree ledge under
the screen, the arches have 45-degree corners. The detail is painted on the big faces (world-space decals, so a
turned panel carries on the paint of the boxes round it): the two-tone line and its bright rib, soft gradients, the
cream V on the face, shut lines, louvres, the brow's vent, the seals round the glass, the curtains. Only what should
stand out is real 3D: the round lamps in their chrome rings, the bumpers and overriders, mirrors, handles, the slide
door's rail, the tent roof, rack and luggage, the surfboard, the awning, the spare wheel and the ladder, the wheels.

What makes it read as a camper van and not a bus:
  * short and round: 4 blocks long (the bumpers 4.25), every edge rolled, a stubby nose under a split screen.
  * the face: a big V of cream over the lower colour, a split two-piece windscreen (a body-coloured bar between the
    panes), two big round headlamps in chrome rings, small round amber indicators under them, a slim chrome bumper
    with overriders, a vent slot in the brow.
  * camping: a pop-up tent roof (a cream lid on orange-and-cream striped canvas, a mesh window each side), a roof
    rack over the cab with a suitcase and a rolled sleeping bag, a surfboard strapped on the lid, a fold-out awning
    over the sliding door (striped canvas with a scalloped valance on two poles: out while it is parked, folded away
    while the engine runs: two movers), gingham curtains tied back in the side windows and the rear window, a ladder
    up the back, the spare white-wall wheel on the back, round tail lamps in chrome rings.
  * inside (seen through the glass): two front seats over the front wheels, a dash with a big round speedo and a
    radio, a thin big steering wheel, two plaid benches, a kitchenette across the back (a cupboard with a sink and a
    two-ring stove, a kettle, a cool box), a lantern hanging from the roof (it glows).
  * white-wall tyres, cream rims, chrome hub caps (wheel_retro_camper, its own model).

Drawn coordinates: 16 px a block, the van's ground centre at (0, 0, 0), front = north (-z), +x = the van's RIGHT
side, the driver on the left (-x).

Tuning in mind: every colour is an argument (lower, upper, canvas, curtain, seat, board, rim): any palette ramp; the
decals ask for a share of the ramp (0..1), so a 4-step ramp works as well as these 7-step ones.

Parts (vehicles.yml movers): door_l, door_r (the cab doors, hinged at their front edge, swinging out), slide_door
(the right side's sliding door: out 1 px, back 15 px), awning (the awning's canvas: turns down about its roller to
hang folded inside the right side while the engine runs), awning_poles (its two poles: slide into the side).

Seats (vehicles.yml): all at y 1.07 block (17.1 px): the cushions' top at 15; a seated head stays under the roof's
inside (38.5).
"""

import math
from types import SimpleNamespace

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp
from stsv_assets import presets

NAME = "retro_camper"


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("rc_cream", 84, 0.040, 0.84, 0.30, 7, False)     # the upper body, the rims, the tent's lid
_ramp("rc_turq", 196, 0.105, 0.58, 0.46, 7, True)      # the lower body: a sunny sea turquoise
_ramp("rc_chrome", 235, 0.014, 0.70, 0.62, 7, False)   # bumpers, rings, hub caps
_ramp("rc_tyre", 260, 0.010, 0.22, 0.18, 5, False)
_ramp("rc_canvas", 50, 0.150, 0.64, 0.34, 5, True)     # the tent's and the awning's orange
_ramp("rc_gingham", 22, 0.165, 0.55, 0.36, 4, True)    # the curtains' red
_ramp("rc_board", 92, 0.150, 0.80, 0.26, 4, True)      # the surfboard's yellow
_ramp("rc_wood", 62, 0.075, 0.55, 0.32, 5, False)      # the kitchenette, the floor
_ramp("rc_plaid", 40, 0.120, 0.50, 0.34, 5, True)      # the seats
_ramp("rc_leather", 45, 0.060, 0.36, 0.22, 4, False)   # the suitcase


def _n(ramp):
    spec = MATERIALS.get(REMAP.get(ramp, ramp))
    return spec[4] if spec else len(PALETTE[REMAP.get(ramp, ramp)])


def S(ramp, f):
    """A share f (0 dark .. 1 light) of a ramp as a step."""
    return (ramp, max(0.0, min(1.0, f)) * (_n(ramp) - 1))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


# ================================================================================================
# Shape (drawn px, 16 a block)
# ================================================================================================

W = 16                      # half width (the side walls are 1 px thick: x 15..16)
NOSE, TAIL = -32, 32        # the face (under the screen) and the back
FACE2 = -31                 # the face over the ledge (the screen's wall)
GROUND, FLOOR = 3, 8        # the body's bottom, the floor inside
BELT = 22                   # the two-tone line (the rib's bright row)
EDGE = 34                   # where the sides, the face and the back roll over into the roof
R = 6                       # the roll's radius
ROOF = EDGE + R             # 40: the roof's top
K = R * (1 - math.cos(math.radians(45)))   # the roll's knot: in from the wall ...
KH = R * math.sin(math.radians(45))        # ... and up from EDGE
CH = 3.5                    # the corners' 45-degree chamfer (in plan)
WF, WR = -19, 19            # axles
WY, WRAD = 6, 6             # wheel centre height, radius (0.75 block across)
WX0, WX1 = 10.5, 14.5       # the wheels' faces (x)
ARCH, ARCH_TOP, CHAMF = 7.5, 13.5, 3.0     # the arch: half length, top, its 45-degree corners
TRACK = 10                  # the floor core's half width (inside the wheels)
GL0, GL1 = 23.5, 32         # side windows: bottom, top
CD = (-26, -13)             # the cab doors (both sides), between their seams
CAB_WIN = (-23, -14.5)      # the cab door's window
SD = (-11, 4)               # the sliding door (right)
SIDE_WINDOWS = [(-9, 2), (6, 16), (18, 26)]   # behind the B pillar (the first is in the sliding door, right)
SCREEN = (0.75, 11.5, 24.5, 31.5)             # the windscreen's openings: |x| from, to; y from, to
REAR_WIN = (8.5, 24.5, 30.5)                  # the rear window: |x| <, y from, to
SEAT_Y = 15.1               # the cushions' top
T = math.tan(math.radians(22.5))


def arch_at(z):
    for wz in (WF, WR):
        if abs(z - wz) < ARCH + 0.01:
            return wz
    return None


def in_arch(z, y):
    """Inside an arch's opening (seen from the side)."""
    wz = arch_at(z)
    if wz is None or y >= ARCH_TOP:
        return False
    d = abs(z - wz)
    return y < ARCH_TOP - CHAMF + (ARCH - d) if d > ARCH - CHAMF else True


def rib_y(x, z):
    """The two-tone line at (x, z): the V on the face, the belt everywhere else."""
    if z < NOSE + 1.2:
        return min(BELT, 12.5 + abs(x))
    return BELT


def round_rect(a, b, a0, a1, b0, b1, r=1.5):
    if not (a0 <= a <= a1 and b0 <= b <= b1):
        return False
    ca = min(max(a, a0 + r), a1 - r)
    cb = min(max(b, b0 + r), b1 - r)
    return math.hypot(a - ca, b - cb) <= r


# ================================================================================================
# Angled panels
# ================================================================================================

def _rot(p, axis, deg, o):
    """A point turned like Minecraft turns an element."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    if axis == "x":
        y, z = y * c - z * s, y * s + z * c
    elif axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def world(decal, rot):
    """A decal for a turned box: it sees the texel where it ends up, so it paints on like the boxes round it."""
    axis, ang, o = rot

    def d(c):
        q = SimpleNamespace(p=_rot(c.p, axis, ang, o), face=c.face, u=c.u, v=c.v, nu=c.nu, nv=c.nv, lo=c.lo, hi=c.hi,
                            i=c.i, n=c.n, frame=c.frame)
        return decal(q)
    return d


def _to3(axis, a, b, s):
    if axis == "x":
        return (s, b, a)
    if axis == "z":
        return (a, b, s)
    return (b, s, a)


def _from3(axis, p):
    if axis == "x":
        return (p[2], p[1])
    if axis == "z":
        return (p[0], p[1])
    return (p[2], p[0])


def panel(m, name, axis, p0, p1, span, t, paint, inside, decal=None, **kw):
    """A flat panel t thick whose outer face runs along p0 -> p1 in the plane across `axis` (axis "x": points are
    (z, y), "z": (x, y), "y": (z, x)), spanning `span` along the axis; its thickness on the side of `inside`. Any
    slope that is a multiple of 22.5 degrees (steeper than 45: the box is laid along the other way). decal(c, outer)
    is a world-space decal told whether the face is the panel's outer one."""
    (a0, b0), (a1, b1) = p0, p1
    da, db = a1 - a0, b1 - b0
    along_a = abs(da) >= abs(db) - 1e-6
    if (along_a and da < 0) or (not along_a and db < 0):
        (a0, b0), (a1, b1) = (a1, b1), (a0, b0)
        da, db = -da, -db
    length = math.hypot(da, db)
    phi = math.degrees(math.atan2(db, da))
    if along_a:
        ang = -phi if axis == "x" else phi
    else:
        ang = (90 - phi) if axis == "x" else (phi - 90)
    snap = round(ang / 22.5) * 22.5
    assert abs(snap - ang) < 0.05 and abs(snap) <= 45, f"{name}: {ang} degrees"
    o3 = _to3(axis, a0, b0, 0)
    best = None
    for sgn in (-1, 1):
        if along_a:
            la, lb = (a0, a0 + length), tuple(sorted((b0, b0 + sgn * t)))
        else:
            la, lb = tuple(sorted((a0, a0 + sgn * t))), (b0, b0 + length)
        mid = _rot(_to3(axis, (la[0] + la[1]) / 2, (lb[0] + lb[1]) / 2, 0), axis, snap, o3)
        ma, mb = _from3(axis, mid)
        dist = math.hypot(ma - inside[0], mb - inside[1])
        if best is None or dist < best[0]:
            best = (dist, la, lb, sgn)
    _, la, lb, sgn = best
    frm = _to3(axis, la[0], lb[0], span[0])
    to = _to3(axis, la[1], lb[1], span[1])
    # the outer face: away from the thickness
    if along_a:
        outer = {"x": ("up", "down"), "z": ("up", "down"), "y": ("east", "west")}[axis][0 if sgn < 0 else 1]
    else:
        outer = {"x": ("south", "north"), "z": ("east", "west"), "y": ("south", "north")}[axis][0 if sgn < 0 else 1]
    rot = (axis, snap, o3)
    if decal is not None:
        paint = paint(decal=world(lambda c: decal(c, c.face == outer), rot))
    m.box(name, frm, to, paint, rotation=rot, **kw)
    return rot


def _x(side, a, b):
    return tuple(sorted((side * a, side * b)))


def _lo(s, a, b, y, z):
    return (min(s * a, s * b), y, z)


def _hi(s, a, b, y, z):
    return (max(s * a, s * b), y, z)


# ================================================================================================
# Paints
# ================================================================================================

CLEAR = ("raw", (0, 0, 0, 0))


class Look:
    """The colours of one paint job."""

    def __init__(self, lower, upper, canvas, curtain, seat, board, rim):
        self.lo, self.up, self.canvas, self.curtain, self.seat, self.board, self.rim = \
            lower, upper, canvas, curtain, seat, board, rim


def _skins(look):
    LO, UP = look.lo, look.up

    def skin(x, y, z, facing):
        """The outside's colour at (x, y, z) on a surface facing side / front / back / top / corner."""
        ax = abs(x)
        fy, fz = math.floor(y), math.floor(z)
        if facing == "top":
            if 4 <= ax < 6:
                return S(UP, 0.95)                                      # a light streak along the roof
            return S(UP, 0.82)
        if facing == "roll":
            return S(UP, 0.9)
        line = rib_y(x, z) if facing == "front" else BELT
        upper = y >= line
        if facing in ("side", "corner"):
            if y < GROUND + 1:
                return S(LO, 0.15)
            if fy == BELT:
                return S(UP, 1.0)                                       # the rib catching the light
            if fy == BELT - 1:
                return S(LO, 0.22)                                      # its shadow
            if facing == "side":
                seams = [CD[0], CD[1] - 1] + ([SD[0], SD[1] - 1] if x > 0 else [])
                if fz in seams and ARCH_TOP <= y < EDGE:
                    return S(UP if upper else LO, 0.05)                 # the doors' shut lines
                wz = arch_at(z)
                if wz is not None and not in_arch(z, y) and (in_arch(z, y - 1) or in_arch(z - 1, y) or in_arch(z + 1, y)):
                    return S(LO, 0.2)                                   # the arch's lip
                if 22 <= z < 29 and 14 <= y < 20:
                    return S(LO, 0.08) if fy % 2 == 0 else S(LO, 0.8)  # louvres on the rear quarters
                if x < 0 and 11 <= z < 13 and 16 <= y < 18:
                    return S("rc_chrome", 0.85 if fy == 17 else 0.55)  # the fuel flap
            if not upper:
                base = 0.34 + 0.26 * smooth((y - 3) / 18)
                if fy in (12,):
                    base += 0.12                                        # a soft reflection low along the sides
                return S(LO, base)
            return S(UP, 0.62 + 0.24 * smooth((y - 23) / 10))
        if facing == "front":
            if y < GROUND + 1:
                return S(LO, 0.2)
            if 32 <= y < 33.5 and ax < 7:
                return S(UP, 0.42) if math.floor(x) % 2 else S(UP, 0.62)        # the brow's vent
            if upper:
                return S(UP, 0.72 + 0.2 * smooth((y - 14) / 18))
            return S(LO, 0.42 + 0.26 * smooth((y - 3) / 16))
        # the back
        if y < GROUND + 1:
            return S(LO, 0.15)
        if fy == BELT:
            return S(UP, 1.0)
        if fy == BELT - 1:
            return S(LO, 0.22)
        if 7.5 <= y < 12 and 5 <= ax < 9:
            return S(LO, 0.08) if fy % 2 == 0 else S(LO, 0.75)        # the engine's louvres
        if upper:
            return S(UP, 0.6 + 0.2 * smooth((y - 23) / 10))
        return S(LO, 0.3 + 0.24 * smooth((y - 3) / 18))

    def interior(x, y, z, face):
        if face == "up" and y <= FLOOR + 0.5:
            return S("rc_wood", 0.45 if math.floor(x) % 3 else 0.2)    # wooden boards
        if face == "up":
            return S("rc_tyre", 0.5)
        if face == "down":
            return S("rc_cream", 0.9)                                   # the headliner
        if y < 11:
            return S("rc_wood", 0.3)
        if y < GL0 - 0.5:
            return S("rc_wood", 0.6 if math.floor(y) % 3 else 0.42)    # wood-panelled walls up to the windows
        return S("rc_cream", 0.8)

    def body_decal(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        if f == "down":
            return interior(x, y, z, f) if y > 30 else S("rc_tyre", 0.1)
        # the wheel wells: dark
        wz = arch_at(z)
        if wz is not None and y < ARCH_TOP + 0.01:
            if (f in ("east", "west") and ax < W - 0.6) or f in ("north", "south") or (f == "up" and ax > TRACK - 0.01 and y < ARCH_TOP):
                return S("rc_tyre", 0.08)
        if f == "up":
            if y <= FLOOR + 0.01 and ax < W - 0.9:
                return interior(x, y, z, f)
            if ax > W - 1.01 and GL0 - 0.6 < y < GL0 + 0.1:
                return S("rc_tyre", 0.3)                                # the window's sill seal
            if ax < W - 0.9 and abs(z) < TAIL - 0.9 and y < EDGE - 0.1:
                return interior(x, y, z, f)
            if y > ROOF - 1.1:
                return skin(x, y, z, "top")
            return S(look.up, 0.7)
        if f in ("east", "west"):
            inner = (f == "east" and x < 0) or (f == "west" and x > 0)
            if ax < W - 1.1 and z < FACE2 + 1.2 and GL0 < y < GL1 + 0.1:
                return S("rc_tyre", 0.3)                                # the screen's reveal
            if ax < W - 1.1 and z > TAIL - 1.2 and REAR_WIN[1] - 0.1 < y < REAR_WIN[2] + 0.1:
                return S("rc_tyre", 0.3)                                # the rear window's reveal
            if inner:
                return interior(x, y, z, f)
            if ax < W - 0.5:
                return S(look.up if y >= BELT else look.lo, 0.4)
            return skin(x, y, z, "side")
        # north / south
        if ax > W - 1.05 and abs(z) < TAIL - 3:
            return S("rc_tyre", 0.3) if GL0 - 0.1 < y < GL1 + 0.1 else S(look.up if y >= BELT else look.lo, 0.3)
        if f == "north":
            if z > 0:
                return interior(x, y, z, f)
            return skin(x, y, z, "front")
        if z < 0:
            return interior(x, y, z, f)
        return skin(x, y, z, "back")

    def panel_decal(facing):
        def d(c, outer):
            x, y, z = c.p
            if outer:
                return skin(x, y, z, facing)
            if c.face in ("down", "west", "east", "north", "south") and facing in ("roll", "top"):
                return S("rc_cream", 0.9)
            return S(look.up if y >= BELT else look.lo, 0.35)
        return d

    return dict(skin=skin, body_decal=body_decal, interior=interior, panel_decal=panel_decal)


def _chrome(lo=0.35, hi=0.95):
    """A chrome part: bright on top, a sky band over a dark ground reflection down its sides."""
    def decal(c):
        if c.face == "up":
            return S("rc_chrome", hi)
        if c.face == "down":
            return S("rc_chrome", lo * 0.6)
        h = c.hi[1] - c.lo[1]
        if h >= 1.5:
            return S("rc_chrome", hi * 0.85 if c.p[1] > (c.lo[1] + c.hi[1]) / 2 else lo)
        return S("rc_chrome", (lo + hi) / 2 + 0.1)
    return Paint("rc_chrome", 4, noise=0, grad=0, rim=0.4, ao=0.2, decal=decal)


def _flat(ramp, f, **kw):
    return Paint(ramp, 2, noise=0, grad=0, rim=0.3, ao=0.3, decal=lambda c: S(ramp, f), **kw)


# ================================================================================================
# Glass
# ================================================================================================

def _streak(a, b, k=23):
    d = math.floor(a * 0.9 - b * 1.2) % k
    if d in (0, 1):
        return ("raw", (226, 238, 252, 150))
    return None


def side_glass(look, curtains, win):
    """A side window's pane: rounded corners in the body colour, a black seal, gingham curtains tied back each side
    and a gathered valance along the top, the rest clear with a reflection streak."""
    z0, z1 = win

    def decal(c):
        x, y, z = c.p
        if c.face not in ("east", "west"):
            return S("rc_tyre", 0.3)
        if not round_rect(z, y, z0, z1, GL0, GL1, 1.6):
            return ("raw", _rgb(S(look.up, 0.8)))
        if not round_rect(z, y, z0 + 0.5, z1 - 0.5, GL0 + 0.5, GL1 - 0.5, 1.2):
            return ("raw", (34, 36, 42, 255))                           # the seal
        if curtains:
            u = min(z - z0, z1 - z)
            tie = GL0 + 3
            if y > GL1 - 2:
                g = (math.floor(z) + math.floor(y)) % 2
                return ("raw", _rgb(S(look.curtain, 0.8 if g else 0.4)))      # the valance
            reach = 1.6 + min(1.4, abs(y - tie) * 0.3)
            if u < reach:
                if abs(y - tie) < 0.5:
                    return ("raw", _rgb(S("rc_cream", 1.0)))                    # the tie
                g = (math.floor(z) + math.floor(y)) % 2
                return ("raw", _rgb(S(look.curtain, 0.85 if g else 0.35)))
        s = _streak(z, y)
        return s or CLEAR
    return Paint("glass", 2, noise=0, light=False, decal=decal)


def screen_glass():
    def decal(c):
        x, y, z = c.p
        if c.face not in ("north", "south"):
            return S("rc_tyre", 0.3)
        ax = abs(x)
        if math.floor(y) == math.floor(SCREEN[2]) and 3 <= ax < 10:
            return ("raw", (24, 24, 28, 255))                           # the wipers resting along the bottom
        s = _streak(x, y * 0.6, 17)
        return s or CLEAR
    return Paint("glass", 2, noise=0, light=False, decal=decal)


def rear_glass(look):
    a, y0, y1 = REAR_WIN

    def decal(c):
        x, y, z = c.p
        if c.face not in ("north", "south"):
            return S("rc_tyre", 0.3)
        ax = abs(x)
        if not round_rect(x, y, -a, a, y0, y1, 1.6):
            return ("raw", _rgb(S(look.up, 0.7)))
        if y > y1 - 1.5:
            g = (math.floor(x) + math.floor(y)) % 2
            return ("raw", _rgb(S(look.curtain, 0.8 if g else 0.4)))
        u = a - ax
        reach = 1.6 + min(1.4, abs(y - 27) * 0.3)
        if u < reach:
            if abs(y - 27) < 0.5:
                return ("raw", _rgb(S("rc_cream", 1.0)))
            g = (math.floor(x) + math.floor(y)) % 2
            return ("raw", _rgb(S(look.curtain, 0.85 if g else 0.35)))
        s = _streak(x, y)
        return s or CLEAR
    return Paint("glass", 2, noise=0, light=False, decal=decal)


def _rgb(rs):
    from stsv_assets.palette import ladder
    ramp, step = rs
    return ladder(ramp, math.floor(step + 0.5))


# ================================================================================================
# The van
# ================================================================================================

class Kit:
    def __init__(self, name, parts, cabin=None):
        """cabin: the riders' eyes (drawn): the glass's faces towards it lose their tint (bake.outward_glass)."""
        self.body = BModel(name, shift=(8, 8, 8), scale=2.0)
        self.body.cabin = cabin
        self.parts = {}
        for part in parts:
            m = BModel(f"{name}_{part}", shift=(8, 8, 8), scale=2.0)
            m.cabin = cabin
            m.display = presets.furniture(gui_scale=0.4)
            m.group(part)
            self.parts[part] = m

    def __getitem__(self, part):
        return self.parts[part]

    def all(self):
        return {self.body.name: self.body, **{m.name: m for m in self.parts.values()}}


PARTS = ("door_l", "door_r", "slide_door", "awning", "awning_poles")
_KIT = {}


def camper_kit(lower="rc_turq", upper="rc_cream", canvas="rc_canvas", curtain="rc_gingham", seat="rc_plaid",
               board="rc_board", rim="rc_cream", name=NAME):
    """lower / upper: the two-tone paint (under / over the V and the belt); canvas: the tent's and the awning's stripes
    (with cream); curtain: the gingham curtains; seat: the plaid benches; board: the surfboard (None: no board);
    rim: the wheels' rims (on the wheel model: wheel_retro_camper)."""
    key = (lower, upper, canvas, curtain, seat, board, rim, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    look = Look(lower, upper, canvas, curtain, seat, board, rim)
    kit = Kit(name, PARTS, cabin=(0, 30, 0))      # the riders' eyes: one-sided glass
    m = kit.body
    m.group("body")
    P = _skins(look)
    P["body"] = Paint(look.up, 3, noise=0, grad=0, rim=0, ao=0, decal=P["body_decal"])
    P["angled"] = Paint(look.up, 3, noise=0, grad=0, rim=0, ao=0)
    ctx = dict(look=look, P=P)
    _shell(m, kit, ctx)
    _windows(m, kit, ctx)
    _face(m, ctx)
    _tail(m, ctx)
    _roof(m, ctx)
    _awning(m, kit, ctx)
    _door_bits(kit, ctx)
    _cabin(m, ctx)
    m.display = _held_display()
    _KIT[key] = kit
    return kit


def _held_display():
    """Hand / ground / gui transforms that show the (already halved) body small, like a toy."""
    d = presets.furniture(gui_scale=0.36)
    for k in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        d[k] = dict(d[k])
        d[k]["scale"] = [round(v / 2.5, 3) for v in d[k]["scale"]]
    return d


def _shell(m, kit, ctx):
    """The walls (cut round the windows, the doors their own parts), the face and the back, the floor and the wheel
    housings, the flat roof; the angled panels: the corners, the ledge, the roof's roll on all four sides."""
    P = ctx["P"]
    body, ang = P["body"], P["angled"]
    pd = P["panel_decal"]
    box = m.box
    Z0, Z1 = -(TAIL - CH), TAIL - CH          # the side walls between the corners' chamfers
    # --- the floor and the cores between the wheels, the wheel housings
    box("floor", (-TRACK, GROUND, NOSE + 1), (TRACK, FLOOR, TAIL - 1), body)
    for s in (-1, 1):
        box("floor_side", _lo(s, TRACK, W - 1, GROUND, WF + ARCH + 1), _hi(s, TRACK, W - 1, FLOOR, WR - ARCH - 1), body)
        for wz in (WF, WR):
            # the wheel well: a roof over the wheel, an inner wall, its two ends (dark: nothing of the cabin shows)
            box("housing", _lo(s, TRACK, W - 1.1, 12.25, wz - ARCH + 0.25), _hi(s, TRACK, W - 1.1, ARCH_TOP, wz + ARCH - 0.25), body)
            box("well_wall", _lo(s, TRACK - 0.5, TRACK, FLOOR, wz - ARCH + 0.25), _hi(s, TRACK - 0.5, TRACK, 12.25, wz + ARCH - 0.25), body)
            for e in (-1, 1):
                za, zb = sorted((wz + e * ARCH, wz + e * (ARCH + 1)))
                box("well_end", _lo(s, TRACK, W - 1.1, GROUND, za), _hi(s, TRACK, W - 1.1, 13.25, zb), body)
    # --- the side walls
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        x0, x1 = W - 1, W

        def wall(target, z0, z1, y0, y1, name="wall"):
            target.box(name, _lo(s, x0, x1, y0, z0), _hi(s, x0, x1, y1, z1), body)
        # in front of the cab door (over the front arch's nose)
        wall(m, Z0, WF - ARCH, GROUND, EDGE)
        wall(m, WF - ARCH, CD[0], ARCH_TOP, EDGE)
        # the cab door: under its window, the posts, over it
        wall(door, CD[0], CD[1], ARCH_TOP, GL0, "door")
        wall(door, CD[0], CAB_WIN[0], GL0, GL1, "door_post")
        wall(door, CAB_WIN[1], CD[1], GL0, GL1, "door_post")
        wall(door, CD[0], CD[1], GL1, EDGE, "door_top")
        # behind it: the lower wall between the arches, over the rear arch, behind it
        wall(m, CD[1], WF + ARCH, ARCH_TOP, GL0)
        if s < 0:
            wall(m, WF + ARCH, WR - ARCH, GROUND, GL0)
        else:
            wall(m, WF + ARCH, SD[0], GROUND, GL0)
            wall(m, SD[1], WR - ARCH, GROUND, GL0)
            sd = kit["slide_door"]
            wall(sd, SD[0], SD[1], GROUND + 0.5, GL0, "slide")
        wall(m, WR - ARCH, WR + ARCH, ARCH_TOP, GL0)
        wall(m, WR + ARCH, Z1, GROUND, GL0)
        # the window band: the pillars between the windows, over them
        posts = [(CD[1], SIDE_WINDOWS[0][0])]
        for (a, b), (c, _) in zip(SIDE_WINDOWS, SIDE_WINDOWS[1:] + [(Z1, None)]):
            posts.append((b, c))
        for (a, b) in posts:
            if s > 0 and SD[0] <= a and b <= SD[1] + 0.01:
                wall(kit["slide_door"], a, b, GL0, GL1, "slide_post")
            elif s > 0 and a < SD[0] < b:
                wall(m, a, SD[0], GL0, GL1)
                wall(kit["slide_door"], SD[0], b, GL0, GL1, "slide_post")
            elif s > 0 and a < SD[1] < b:
                wall(kit["slide_door"], a, SD[1], GL0, GL1, "slide_post")
                wall(m, SD[1], b, GL0, GL1)
            else:
                wall(m, a, b, GL0, GL1)
        if s > 0:
            wall(m, CD[1], SD[0], GL1, EDGE)
            wall(kit["slide_door"], SD[0], SD[1], GL1, EDGE, "slide_top")
            wall(m, SD[1], Z1, GL1, EDGE)
        else:
            wall(m, CD[1], Z1, GL1, EDGE)
        # the arches' 45-degree corners: the corner filled in steps, a slim panel along the edge over them
        lip = Paint(ctx["look"].lo, 3, noise=0, grad=0, rim=0, ao=0)
        for wz in (WF, WR):
            for e in (-1, 1):
                z0 = wz + e * ARCH
                z1 = wz + e * (ARCH - CHAMF)
                for k in range(int(CHAMF) - 1):
                    za, zb = sorted((z0 - e * k, z0 - e * (k + 1)))
                    box("arch_step", _lo(s, W - 1, W - 0.05, ARCH_TOP - CHAMF + k + 1, za),
                        _hi(s, W - 1, W - 0.05, ARCH_TOP, zb), body)
                panel(m, "arch_corner", "x", (z0, ARCH_TOP - CHAMF), (z1, ARCH_TOP), _x(s, TRACK, W + 0.02), 0.75, lip,
                      (wz, ARCH_TOP + 3), decal=lambda c, outer: S("rc_tyre", 0.08) if outer else S(ctx["look"].lo, 0.2))
    # --- the face: the nose under the ledge, the screen's wall over it (the screen's openings cut out)
    FX = W - CH                                       # the nose's half width (12.5)
    FX2 = FX + (FACE2 - NOSE)                         # the screen wall's half width (13.5: on the corner's chamfer)
    box("nose", (-FX, GROUND, NOSE), (FX, BELT + 1, NOSE + 1), body)
    a0, a1, y0, y1 = SCREEN
    box("screen_sill", (-FX2, BELT + 1, FACE2), (FX2, y0, FACE2 + 1), body)
    box("screen_bar", (-a0, y0, FACE2), (a0, y1, FACE2 + 1), body)
    for s in (-1, 1):
        box("screen_post", _lo(s, a1, FX2, y0, FACE2), _hi(s, a1, FX2, y1, FACE2 + 1), body)
    box("brow", (-FX2, y1, FACE2), (FX2, EDGE, FACE2 + 1), body)
    # the ledge under the screen: 45 degrees
    panel(m, "ledge", "x", (NOSE, BELT + 1), (FACE2, BELT + 2), (-FX, FX), 0.75, ang, (0, 0),
          decal=lambda c, outer: P["skin"](c.p[0], c.p[1], c.p[2], "front") if outer else S(ctx["look"].up, 0.5))
    # the V's trim: a slim cream strip along each arm of the V, a hair proud of the nose (crisp, over the paint's steps)
    vt = Paint(ctx["look"].up, 3, noise=0, grad=0, rim=0, ao=0)
    for s in (-1, 1):
        panel(m, "v_trim", "z", (0, 11.9), (s * 10.1, 22), (NOSE - 0.25, NOSE), 1.2, vt, (0, 30),
              decal=lambda c, outer: S(ctx["look"].up, 1.0 if c.face == "north" else 0.7))
    # --- the back (the rear window cut out)
    ra, ry0, ry1 = REAR_WIN
    box("back", (-FX, GROUND, TAIL - 1), (FX, ry0, TAIL), body)
    for s in (-1, 1):
        box("back_post", _lo(s, ra, FX, ry0, TAIL - 1), _hi(s, ra, FX, ry1, TAIL), body)
    box("back_top", (-FX, ry1, TAIL - 1), (FX, EDGE, TAIL), body)
    # --- the corners in plan: 45-degree chamfers (the front ones in two: under and over the ledge)
    cd = pd("corner")
    for s in (-1, 1):
        panel(m, "corner", "y", (Z0, s * W), (NOSE, s * FX), (GROUND, BELT + 1), 0.75, ang, (0, 0), decal=cd)
        panel(m, "corner", "y", (Z0, s * W), (FACE2, s * FX2), (BELT + 1, EDGE), 0.75, ang, (0, 0), decal=cd)
        panel(m, "corner", "y", (Z1, s * W), (TAIL, s * FX), (GROUND, EDGE), 0.75, ang, (0, 0), decal=cd)
    # --- the roof: a flat top, rolled over into the sides, the face and the back in two panels each
    RZ0, RZ1, RX = FACE2 + R, TAIL - R, W - R
    roof = Paint(ctx["look"].up, 3, noise=0, grad=0, rim=0, ao=0,
                 decal=lambda c: P["skin"](c.p[0], c.p[1], c.p[2], "top") if c.face == "up" else P["interior"](c.p[0], c.p[1], c.p[2], "down"))
    box("roof", (-RX, ROOF - 1, RZ0), (RX, ROOF, RZ1), roof)
    # the roll's panels (and the caps under their mitres) are lit by their paint alone (no face shading): their
    # ends, where they meet round the corners, take the same light as their faces and the corner reads as one
    def rd_(f):
        return lambda c, outer: S(ctx["look"].up, f)
    rdl, rdh = rd_(0.66), rd_(0.8)
    zmid_lo = FACE2 + (KH / 2) * T              # the mitres: each panel ends where the other roll is at its mid height
    zmid_hi = FACE2 + K + (R - KH) / 2 / T
    xmid_lo = W - (KH / 2) * T
    xmid_hi = W - K - (R - KH) / 2 / T
    bmid_lo = TAIL - (KH / 2) * T
    bmid_hi = TAIL - K - (R - KH) / 2 / T
    for s in (-1, 1):
        panel(m, "roll", "z", (s * W, EDGE), (s * (W - K), EDGE + KH), (zmid_lo, bmid_lo), 0.75, ang, (0, EDGE - 4), decal=rdl, shade=False)
        panel(m, "roll", "z", (s * (W - K), EDGE + KH), (s * RX, ROOF), (zmid_hi, bmid_hi), 0.75, ang, (0, EDGE - 4), decal=rdh, shade=False)
    panel(m, "roll", "x", (FACE2, EDGE), (FACE2 + K, EDGE + KH), (-xmid_lo, xmid_lo), 0.75, ang, (0, EDGE - 4), decal=rdl, shade=False)
    panel(m, "roll", "x", (FACE2 + K, EDGE + KH), (RZ0, ROOF), (-xmid_hi, xmid_hi), 0.75, ang, (0, EDGE - 4), decal=rdh, shade=False)
    panel(m, "roll", "x", (TAIL, EDGE), (TAIL - K, EDGE + KH), (-xmid_lo, xmid_lo), 0.75, ang, (0, EDGE - 4), decal=rdl, shade=False)
    panel(m, "roll", "x", (TAIL - K, EDGE + KH), (RZ1, ROOF), (-xmid_hi, xmid_hi), 0.75, ang, (0, EDGE - 4), decal=rdh, shade=False)
    # the corners' caps: under the mitres, so no gap shows
    cap = Paint(ctx["look"].up, 3, noise=0, grad=0, rim=0, ao=0, decal=lambda c: S(ctx["look"].up, 0.72))
    for s in (-1, 1):
        for zc, e in ((FACE2, 1), (TAIL, -1)):
            # (inside the round corner: its far corner 3 px out each way, 5.2 from the roll's centre)
            za, zb = sorted((zc + e * (R - 3), zc + e * (R + 0.5)))
            box("roll_cap", _lo(s, W - R - 0.5, W - R + 3, EDGE - 1, za), _hi(s, W - R - 0.5, W - R + 3, EDGE + 3, zb), cap, shade=False)


def _windows(m, kit, ctx):
    look = ctx["look"]
    # the windscreen: a pane in each opening, set a hair behind the face
    a0, a1, y0, y1 = SCREEN
    sg = screen_glass()
    for s in (1, -1):
        lo, hi = _x(s, a0, a1)
        m.box("windscreen", (lo, y0, FACE2 + 0.5), (hi, y1, FACE2 + 0.75), sg)
    # the side glass: a pane in each window (the doors' panes ride with the doors)
    for s, door in ((-1, "door_l"), (1, "door_r")):
        lo, hi = _x(s, W - 0.5, W - 0.25)       # (outside the folded awning: it hangs at x 15.1..15.5)
        kit[door].box("window", (lo, GL0, CAB_WIN[0]), (hi, GL1, CAB_WIN[1]), side_glass(look, False, CAB_WIN))
        for (a, b) in SIDE_WINDOWS:
            target = kit["slide_door"] if (s > 0 and SD[0] <= a < SD[1]) else m
            target.box("window", (lo, GL0, a), (hi, GL1, b), side_glass(look, True, (a, b)))
    a, ry0, ry1 = REAR_WIN
    m.box("rear_window", (-a, ry0, TAIL - 0.75), (a, ry1, TAIL - 0.5), rear_glass(look))


def _round_lamp(cx, cy, core, mid, edge, ring_r, front):
    """A round lens seen from its face: a hot core, a ribbed reflector, a chrome ring."""
    def decal(c):
        if c.face != front:
            return S("rc_chrome", 0.5)
        r = math.hypot(abs(c.p[0]) - cx, c.p[1] - cy)
        if r < ring_r * 0.45:
            return core
        if r < ring_r:
            return mid if math.floor(c.p[0]) % 2 == 0 else edge
        return S("rc_chrome", 0.95 if c.p[1] > cy else 0.6)
    return decal


HEAD = (10, 15.5)            # the headlamps' centre (|x|, y)
TAIL_LAMP = (11, 15)         # the tail lamps'


def _face(m, ctx):
    look = ctx["look"]
    hx, hy = HEAD
    for s in (1, -1):
        cx = hx * s

        def ring(c, cx=cx):
            if c.face != "north":
                return S("rc_chrome", 0.55)
            dy = c.p[1] - hy
            return S("rc_chrome", 1.0 if dy > 0.5 else 0.8 if dy > -1 else 0.5)
        cyl(m, "lamp_ring", (cx, hy), 6, NOSE - 1, NOSE + 0.5, Paint("rc_chrome", 4, noise=0, grad=0, rim=0.3, ao=0, decal=ring), axis="z")
        m.group("lights")
        cyl(m, "headlamp", (cx, hy), 4, NOSE - 1.5, NOSE - 1, Paint("lamp", 3, noise=0, light=False, glow=True,
            decal=_round_lamp(hx, hy, ("raw", (255, 252, 230, 255)), ("raw", (236, 232, 206, 255)), ("raw", (208, 206, 190, 255)), 2.1, "north")), axis="z")
        # the indicator under it: a small round amber lens
        m.box("indicator", (cx - 1, 9.5, NOSE - 0.75), (cx + 1, 11.5, NOSE), Paint("fox", 3, noise=0, light=False, glow=True,
              decal=lambda c: ("raw", (255, 196, 80, 255)) if c.face == "north" and c.p[1] > 10.5 else ("raw", (236, 150, 40, 255))))
        m.group("body")
    _bumper(m, front=True)
    # the front plate's place (the plugin writes the owner's name on it): a pale plate in a thin frame
    m.box("plate", (-4.5, 7.5, NOSE - 0.5), (4.5, 11, NOSE), Paint("rc_cream", 6, noise=0, light=False, decal=_plate(True)))


def _plate(front):
    f = "north" if front else "south"

    def decal(c):
        if c.face != f:
            return S("rc_chrome", 0.5)
        if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return S("rc_chrome", 0.45)
        return S("rc_cream", 1.0)
    return decal


def _bumper(m, front):
    zs = -1 if front else 1
    z_out = NOSE - 2 if front else TAIL + 2
    z_in = NOSE if front else TAIL
    lo, hi = sorted((z_out, z_in))
    blade = _chrome(0.35, 1.0)
    m.box("bumper", (-W + 1, 4.5, lo), (W - 1, 7, hi), blade)
    for s in (1, -1):
        # the ends wrapping back round the corners
        za, zb_ = sorted((z_out - zs * 0.5, z_in - zs * (CH - 0.75)))
        m.box("bumper_end", _lo(s, W - 0.75, W + 0.5, 4.5, za), _hi(s, W - 0.75, W + 0.5, 7, zb_), blade)
        # an overrider: a little upright guard
        za, zb_ = sorted((z_out + zs * 0.75, z_out))
        m.box("overrider", _lo(s, 5.5, 6.75, 3.5, za), _hi(s, 5.5, 6.75, 9.25, zb_), _chrome(0.4, 1.0))


def _tail(m, ctx):
    look = ctx["look"]
    tx, ty = TAIL_LAMP
    for s in (1, -1):
        cx = tx * s

        def ring(c):
            if c.face != "south":
                return S("rc_chrome", 0.55)
            return S("rc_chrome", 1.0 if c.p[1] > ty + 0.5 else 0.6)
        cyl(m, "tail_ring", (cx, ty), 4, TAIL - 0.5, TAIL + 1, Paint("rc_chrome", 4, noise=0, grad=0, rim=0.3, ao=0, decal=ring), axis="z")
        m.group("lights")
        cyl(m, "tail_lamp", (cx, ty), 3, TAIL + 1, TAIL + 1.25, Paint("signal_red", 1, noise=0, light=False, glow=True,
            decal=_round_lamp(tx, ty, ("raw", (255, 110, 100, 255)), ("raw", (214, 38, 40, 255)), ("raw", (170, 24, 30, 255)), 1.6, "south")), axis="z")
        m.group("body")
    _bumper(m, front=False)
    m.box("plate", (-4.5, 7.5, TAIL), (4.5, 11, TAIL + 0.5), Paint("rc_cream", 6, noise=0, light=False, decal=_plate(False)))
    # the spare wheel on the back: a white-wall tyre, a cream rim, a chrome cap, on a bracket
    SY, SZ = 21, TAIL + 0.5
    m.box("spare_mount", (-1, SY - 1, TAIL), (1, SY + 1, SZ), _flat("rc_tyre", 0.3))

    def tyre_face(c):
        x, y = c.p[0], c.p[1]
        r = math.hypot(x, y - SY)
        if c.face != "south":
            a = math.atan2(y - SY, x)
            return S("rc_tyre", 0.45 if math.floor((a + math.pi) * 6) % 3 else 0.15)
        if r > 5.3:
            return S("rc_tyre", 0.2)
        if r > 3.9:
            return S("rc_cream", 1.0 if (y > SY) else 0.85)            # the white wall
        if r > 3.4:
            return S("rc_tyre", 0.3)
        return S(look.rim, 0.85 if (x < 0 and y > SY) else 0.65)
    cyl(m, "spare", (0, SY), 12, SZ, SZ + 2.75, Paint("rc_tyre", 1, noise=0, grad=0, rim=0.2, ao=0, decal=tyre_face), axis="z")
    cyl(m, "spare_cap", (0, SY), 4, SZ + 2.75, SZ + 3.25, Paint("rc_chrome", 4, noise=0, grad=0, rim=0, ao=0,
        decal=lambda c: S("rc_chrome", 1.0 if (c.p[0] < 0.5 and c.p[1] > SY - 0.5) else 0.7) if c.face == "south" else S("rc_chrome", 0.5)), axis="z")
    # the ladder up the back (right of the spare): two rails, rungs, a hoop over the roof's edge
    rail = _chrome(0.45, 0.95)
    zl0, zl1 = TAIL + 1, TAIL + 1.5
    rx = ((6.25, 6.75), (8.75, 9.25))
    for x0, x1 in rx:
        m.box("ladder_rail", (x0, 8, zl0), (x1, 37, zl1), rail)
        m.box("ladder_foot", (x0, 8, TAIL), (x1, 8.5, zl0), rail)
        m.box("ladder_top", (x0, 37, TAIL - 2.5), (x1, 37.5, zl1), rail)
    for y in range(10, 37, 3):
        m.box("ladder_rung", (6.75, y, zl0 + 0.1), (8.75, y + 0.5, zl1 - 0.1), rail)


def _roof(m, ctx):
    look = ctx["look"]
    UP, CAN = look.up, look.canvas
    # the pop-up tent: striped canvas walls standing on the roof, a cream lid on them
    T0, T1 = ROOF - 0.5, ROOF + 4
    LZ0, LZ1 = -18, 23

    def canvas(c):
        x, y, z = c.p
        f = c.face
        if f in ("up", "down"):
            return S(CAN, 0.4)
        along = z if f in ("east", "west") else x
        # a little mesh window in the middle of each long side
        if f in ("east", "west") and -3 <= z < 7 and T0 + 1.5 <= y < T1 - 1:
            if z < -2 or z >= 6 or y < T0 + 2 or y >= T1 - 1.5:
                return S("rc_tyre", 0.6)
            return S("rc_tyre", 0.05) if (math.floor(z) + math.floor(y)) % 2 else S("rc_tyre", 0.35)
        stripe = math.floor(along / 2) % 2
        shade = 0.55 + 0.25 * (y - T0) / (T1 - T0)
        if y > T1 - 1:
            shade -= 0.3                                      # under the lid's lip
        return S(CAN, shade + 0.15) if stripe else S("rc_cream", shade + 0.25)
    m.box("tent", (-9.75, T0, LZ0 + 1), (9.75, T1, LZ1 - 1), Paint(CAN, 3, noise=0, grad=0, rim=0, ao=0, decal=canvas))

    def lid(c):
        x, y, z = c.p
        if c.face == "up":
            return S(UP, 1.0) if 3 < abs(x) < 4.5 else S(UP, 0.86)
        if c.face == "down":
            return S(UP, 0.35)
        return S(UP, 0.72)
    LP = Paint(UP, 4, noise=0, grad=0, rim=0.4, ao=0.2, decal=lid)
    m.box("lid", (-11.5, T1, LZ0), (11.5, T1 + 1, LZ1), LP)
    panel_d = lambda c, outer: S(UP, 0.92) if outer else S(UP, 0.4)
    for s in (-1, 1):
        panel(m, "lid_edge", "z", (s * 11.5, T1 + 1), (s * 10.5, T1 + 2), (LZ0 + 1, LZ1 - 1), 0.6,
              Paint(UP, 4, noise=0, grad=0, rim=0, ao=0), (0, T1), decal=panel_d)
    m.box("lid_top", (-10.5, T1 + 1, LZ0 + 1), (10.5, T1 + 2, LZ1 - 1), LP)
    # two bars across the lid, a surfboard strapped along it
    BAR = T1 + 2
    for zb_ in (-11, 15):
        m.box("rack_bar", (-9, BAR, zb_), (9, BAR + 0.5, zb_ + 1), Paint("rc_tyre", 1, noise=0, grad=0, rim=0.3,
              decal=lambda c: S("rc_tyre", 0.5 if c.face == "up" else 0.25)))
    if look.board:
        BY = BAR + 0.5
        B = look.board

        def surf(c):
            x, y, z = c.p
            if c.face == "up":
                if any(zb_ - 0.25 <= z < zb_ + 1.25 for zb_ in (-11, 15)):
                    return S("rc_tyre", 0.3)                      # the straps
                if abs(x) < 0.5:
                    return S("signal_red", 0.7)                   # the stringer stripe
                if 1.5 < abs(x) < 2 and -12 < z < 17:
                    return S("signal_red", 0.4)
                return S(B, 0.78 if x < 0 else 0.66)
            if c.face == "down":
                return S(B, 0.3)
            return S("rc_cream", 0.95)                            # the rail
        bp = Paint(B, 2, noise=0, grad=0, rim=0.3, ao=0, decal=surf)
        m.box("surfboard", (-3, BY, -14), (3, BY + 0.75, 16), bp)
        bd = lambda c, outer: surf(SimpleNamespace(p=c.p, face="up" if outer else "down"))
        # the nose and the tail: tapered in plan by 45-degree panels, a little rocker at the nose
        for s in (-1, 1):
            panel(m, "surfboard_nose", "y", (-14, s * 3), (-17, 0), (BY, BY + 0.75), 0.6, bp, (-14, 0),
                  decal=lambda c, outer: S("rc_cream", 0.95) if outer else S(B, 0.4))
            panel(m, "surfboard_tail", "y", (16, s * 3), (18, s * 1), (BY, BY + 0.75), 0.6, bp, (16, 0),
                  decal=lambda c, outer: S("rc_cream", 0.95) if outer else S(B, 0.4))
        m.box("surfboard_nose", (-1.5, BY, -16.5), (1.5, BY + 0.75, -14), bp)
        m.box("surfboard_tail", (-1.5, BY, 16), (1.5, BY + 0.75, 18), bp)
        m.box("fin", (-0.25, BY - 1, 15.25), (0.25, BY, 17.5), _flat(B, 0.4))
    # the roof rack over the cab: a chrome rail frame on four feet, a suitcase and a rolled sleeping bag on it
    rail = _chrome(0.45, 0.95)
    RY = ROOF + 0.5
    RZ0, RZ1 = -28, -19.5
    for s in (1, -1):
        m.box("rack_rail", _lo(s, 9, 9.5, RY, RZ0), _hi(s, 9, 9.5, RY + 0.5, RZ1), rail)
        m.box("rack_foot", _lo(s, 9, 9.5, ROOF - 0.5, -21), _hi(s, 9, 9.5, RY, -20.5), rail)
        m.box("rack_foot", _lo(s, 9, 9.5, EDGE + 3, RZ0), _hi(s, 9, 9.5, RY, RZ0 + 0.5), rail)
    for zr in (RZ0, RZ1 - 0.5):
        m.box("rack_rail", (-9, RY, zr), (9, RY + 0.5, zr + 0.5), rail)
    m.box("rack_slat", (-9, RY, -24.25), (9, RY + 0.25, -23.75), rail)

    def case(c):
        x, y, z = c.p
        strap = 3.75 < x < 4.5 or -4.5 < x < -3.75
        if c.face == "up":
            return S("rc_leather", 0.15) if strap else S("rc_leather", 0.85)
        if strap:
            return S("rc_leather", 0.15)
        if math.floor(y * 2) == math.floor((RY + 2.25) * 2):
            return S("rc_chrome", 0.7)                             # the case's seam
        return S("rc_leather", 0.55 if c.face in ("north", "west") else 0.4)
    m.box("suitcase", (-8, RY + 0.5, -27.5), (1, RY + 4, -21.5), Paint("rc_leather", 2, noise=0, grad=0, rim=0.4, ao=0.2, decal=case))
    m.box("suitcase_handle", (-4.5, RY + 4, -25), (-2.5, RY + 4.5, -24), _flat("rc_leather", 0.2))

    def roll(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            r = math.hypot(y - (RY + 2.25), z + 24)
            return S(look.curtain, 0.25) if math.floor(r * 1.6) % 2 else S(look.canvas, 0.75)   # the spiral
        if 4.25 < x < 5 or 7.25 < x < 8:
            return S("rc_tyre", 0.3)                               # its ties
        return S(look.canvas, 0.55 + 0.25 * (y - RY) / 4)
    cyl(m, "bedroll", (RY + 2.25, -24), 4, 2.5, 8.75, Paint(look.canvas, 2, noise=0, grad=0, rim=0.3, ao=0, decal=roll), axis="x")


def _awning(m, kit, ctx):
    look = ctx["look"]
    CAN = look.canvas
    HX, HY = 15.5, 34.5                       # the roller (vehicles.yml: the awning's hinge)
    Z0, Z1 = -12.5, 4
    # the roller case along the right roof edge (on the body)
    m.box("awning_case", (15.75, 33.5, -13.5), (17.25, 35.25, 5), _chrome(0.35, 0.9))
    a = kit["awning"]

    def cloth(c):
        x, y, z = c.p
        stripe = math.floor((z - Z0) / 2) % 2
        f = 0.8 if c.face == "up" else 0.45
        return S(CAN, f) if stripe else S("rc_cream", f + 0.15)
    a.box("canvas", (17.25, HY, Z0), (35, HY + 0.375, Z1), Paint(CAN, 3, noise=0, grad=0, rim=0, ao=0, decal=cloth))

    def valance(c):
        x, y, z = c.p
        u = (z - Z0) % 2
        depth = 1.1 + 1.1 * math.sin(math.pi * u / 2)
        if y < HY - depth:
            return CLEAR
        stripe = math.floor((z - Z0) / 2) % 2
        return ("raw", _rgb(S(CAN, 0.55) if stripe else S("rc_cream", 0.8)))
    a.box("valance", (34.75, HY - 2.5, Z0), (35, HY, Z1), Paint(CAN, 3, noise=0, light=False, decal=valance))
    # its two poles (another part: they slide into the side)
    p = kit["awning_poles"]
    for zp in (Z0 + 0.25, Z1 - 0.75):
        p.box("pole", (34.125, 0.25, zp), (34.5, HY, zp + 0.375), _chrome(0.45, 0.9))
        p.box("pole_foot", (33.75, 0, zp - 0.375), (34.875, 0.25, zp + 0.75), _flat("rc_tyre", 0.3))


def _door_bits(kit, ctx):
    rail = _chrome(0.45, 1.0)
    for s, part in ((-1, "door_l"), (1, "door_r")):
        d = kit[part]
        # the mirror: a thin arm from the door's front top out, a round head facing back
        d.box("mirror_arm", _lo(s, W, W + 3, 29, -24.5), _hi(s, W, W + 3, 29.5, -24), rail)
        d.box("mirror_stem", _lo(s, W + 2.5, W + 3, 29.5, -24.5), _hi(s, W + 2.5, W + 3, 30.5, -24), rail)

        def mirror(c, s=s):
            if c.face == "south":
                r = math.hypot(c.p[0] - (W + 2.75) * s, c.p[1] - 31.5)
                return ("raw", (200, 222, 236, 255)) if r < 0.8 else S("rc_chrome", 0.8)
            return S("rc_chrome", 0.6)
        d.box("mirror", _lo(s, W + 1.75, W + 3.75, 30.5, -24.5), _hi(s, W + 1.75, W + 3.75, 32.5, -23.75),
              Paint("rc_chrome", 4, noise=0, grad=0, rim=0.3, ao=0, decal=mirror))
        # the handle: a chrome pull
        d.box("handle", _lo(s, W, W + 0.5, 20.5, -16.5), _hi(s, W, W + 0.5, 21, -14.5), rail)
    sd = kit["slide_door"]
    sd.box("handle", (W, 20.5, -9.5), (W + 0.5, 21, -7.5), rail)


def _cabin(m, ctx):
    look = ctx["look"]
    SEAT, WOOD = look.seat, "rc_wood"
    # the sliding door's rail along the side behind it
    m.box("slide_rail", (W, 22.75, SD[1] + 0.5), (W + 0.25, 23.25, 27), _chrome(0.35, 0.8))

    def plaid(c):
        x, y, z = c.p
        a = x if c.face not in ("east", "west") else z
        b = z if c.face in ("up", "down") else y
        k = (math.floor(a) % 2) + (math.floor(b) % 2)
        if c.face == "down":
            return S(SEAT, 0.1)
        return S(SEAT, (0.25, 0.55, 0.9)[k] + (0.08 if c.face == "up" else 0))
    pl = Paint(SEAT, 2, noise=0, grad=0, rim=0.3, ao=0.3, decal=plaid)
    base = _flat("rc_tyre", 0.35)
    # the front: a seat box between the wheels, two seats over them, the dash, the steering wheel
    m.box("seat_box", (-TRACK + 0.75, FLOOR, -24), (TRACK - 0.75, 12.5, -14), Paint(look.lo, 2, noise=0, grad=0, rim=0.3, ao=0.3,
          decal=lambda c: S(look.lo, 0.5 if c.face != "up" else 0.7)))
    for s in (1, -1):
        m.box("front_cushion", _lo(s, 2.5, 13.5, 12.5, -22), _hi(s, 2.5, 13.5, SEAT_Y, -15), pl)
        m.box("front_back", _lo(s, 2.5, 13.5, 12.5, -15), _hi(s, 2.5, 13.5, 29, -13.5), pl)

    def dash(c):
        x, y, z = c.p
        if c.face == "south":
            r = math.hypot(x + 8, y - 22.5)
            if r < 2.2:
                if r < 0.5:
                    return S("rc_tyre", 0.1)
                if abs(math.atan2(y - 22.5, x + 8) - 2.2) < 0.4 and r < 1.8:
                    return ("raw", (230, 70, 50, 255))             # the needle
                return S("rc_cream", 1.0) if r < 1.6 else S("rc_chrome", 0.9)
            if 1 <= x < 5 and 21.5 <= y < 23:
                return S("rc_chrome", 0.9) if math.floor(x * 2) % 2 else S("rc_tyre", 0.2)   # the radio
            return S(look.lo, 0.5 if y > 23.5 else 0.35)
        if c.face == "up":
            return S("rc_tyre", 0.3)                               # the padded top
        return S(look.lo, 0.35)
    m.box("dash", (-(W - 1), 20, FACE2 + 1), (W - 1, 24.75, -27.5), Paint(look.up, 3, noise=0, grad=0, rim=0.3, ao=0.3, decal=dash))
    # the steering wheel: a big thin ring (four bars) round a two-spoke hub, on a column
    cx, cy, cz = -8, 25.5, -23.5
    rim = Paint("rc_cream", 3, noise=0, grad=0, rim=0.4, decal=lambda c: S("rc_cream", 0.8 if c.p[1] > cy else 0.55))
    for x0, y0, x1, y1 in ((-3, 2.5, 3, 3), (-3, -3, 3, -2.5), (-3, -2.5, -2.5, 2.5), (2.5, -2.5, 3, 2.5)):
        m.box("wheel", (cx + x0, cy + y0, cz), (cx + x1, cy + y1, cz + 0.5), rim)
    m.box("wheel_spoke", (cx - 2.5, cy - 0.25, cz + 0.1), (cx + 2.5, cy + 0.25, cz + 0.4), rim)
    m.box("column", (cx - 0.5, cy - 2, -27.5), (cx + 0.5, cy - 1, cz), base)
    m.box("gear", (-0.5, 12.5, -23.5), (0.5, 18.5, -22.5), base)
    m.box("gear_knob", (-0.75, 18.5, -23.75), (0.75, 19.5, -22.25), _flat("rc_cream", 0.9))
    # two benches
    for (z0, z1) in ((-3.5, 3), (11.5, 18)):
        m.box("bench_base", (-TRACK + 0.75, FLOOR, z0 + 0.5), (TRACK - 0.75, 12.5, z1), base)
        m.box("bench_cushion", (-(W - 1), 12.5, z0), (W - 1, SEAT_Y, z1), pl)
        m.box("bench_back", (-(W - 1), 12.5, z1), (W - 1, 28, z1 + 1.5), pl)
    # the kitchenette across the back: a cupboard with a sink and a two-ring stove (left), a cool box (right)
    KZ0, KZ1, KTOP = 20.5, 29.5, 23.5

    def cupboard(c):
        x, y, z = c.p
        if c.face == "up":
            if -13 <= x < -9 and 22.5 <= z < 27:
                return S("rc_chrome", 0.5 if math.hypot(x + 11, z - 24.75) < 0.6 else 0.85)   # the sink
            if -7 <= x < -1 and 22 <= z < 28:
                for (bx, bz) in ((-5.5, 25), (-2.5, 25)):
                    r = math.hypot(x - bx, z - bz)
                    if r < 1.4:
                        return S("rc_tyre", 0.05) if r > 1 or r < 0.4 else S("rc_tyre", 0.3)   # the rings
                return S("rc_tyre", 0.45)
            return S("rc_cream", 0.95)
        if c.face == "north":
            if y > KTOP - 0.75:
                return S("rc_cream", 0.8)
            if math.floor(x) == -8 or y < FLOOR + 0.75:
                return S(WOOD, 0.2)
            if (abs(x + 10) < 0.75 or abs(x + 6) < 0.75) and math.floor(y) == 20:
                return S("rc_chrome", 0.9)                         # handles
            return S(WOOD, 0.7 if math.floor(x) % 2 else 0.55)
        return S(WOOD, 0.5)
    # (over the rear wheel well: narrower under the housing's top)
    cp = Paint(WOOD, 3, noise=0, grad=0, rim=0.3, ao=0.3, decal=cupboard)
    m.box("cupboard", (-TRACK + 0.75, FLOOR, KZ0), (-1, 13.5, KZ1), cp)
    m.box("cupboard", (-14.5, 13.5, KZ0), (-1, KTOP, KZ1), cp)
    cb = Paint(look.lo, 3, noise=0, grad=0, rim=0.3, ao=0.3,
               decal=lambda c: S("rc_cream", 0.95) if (c.p[1] > 16.5 or c.face == "up") else S(look.lo, 0.75 if c.face == "north" else 0.55))
    m.box("cool_box", (2, FLOOR, 22), (TRACK - 0.75, 13.5, 29.5), cb)
    m.box("cool_box", (2, 13.5, 22), (13, 18, 29.5), cb)
    m.box("cool_box_handle", (5, 18, 25.5), (10, 18.5, 26.5), _chrome(0.4, 0.9))
    m.box("kettle", (-6.5, KTOP, 24), (-4.5, KTOP + 1.5, 26), Paint("signal_red", 1, noise=0, grad=0, rim=0.4,
          decal=lambda c: S("signal_red", 0.8 if c.face == "up" else 0.5)))
    # the lantern hanging from the roof between the benches: a chain, a cap, a glowing glass, a base
    lx, lz = 0, 7.5
    m.box("lantern_chain", (lx - 0.25, 34.5, lz - 0.25), (lx + 0.25, ROOF - 1, lz + 0.25), base)
    m.box("lantern_cap", (lx - 1, 33.5, lz - 1), (lx + 1, 34.5, lz + 1), _flat("rc_tyre", 0.5))
    m.group("lights")
    m.box("lantern_glass", (lx - 0.75, 31, lz - 0.75), (lx + 0.75, 33.5, lz + 0.75), Paint("lamp", 3, noise=0, light=False, glow=True,
          decal=lambda c: ("lamp", 4 if 31.75 < c.p[1] < 32.75 else 3)))
    m.group("body")
    m.box("lantern_base", (lx - 1, 30.5, lz - 1), (lx + 1, 31, lz + 1), _flat("rc_tyre", 0.4))


# ================================================================================================
# The wheel: 12 px (0.75 block) across, 4 px wide
# ================================================================================================

def wheel_retro_camper(rim="rc_cream"):
    """A fat white-wall tyre, a cream steel rim, a chrome dish hub cap (a bright ring, a dome in the middle)."""
    m = BModel("wheel_retro_camper", shift=(8, 8, 8), floor=None)
    d, width = 12, 4
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        lit = y > 0 and z < 0.5
        if c.face in ("east", "west"):
            if rr > r - 0.7:
                return S("rc_tyre", 0.12)
            if rr > r - 2.2:
                return S("rc_cream", 1.0 if lit else 0.85)          # the white wall
            if rr > r - 2.6:
                return S("rc_tyre", 0.25)
            return S(rim, 0.9 if lit else 0.68)                      # the painted rim
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.0)
        return S("rc_tyre", 0.12 if k % 2 == 0 else 0.24)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("rc_tyre", 1, noise=0, grad=0, rim=0.2, ao=0, decal=tire), axis="x")

    def hub(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face not in ("east", "west"):
            return S("rc_chrome", 0.55)
        if rr < 0.8:
            return S("rc_chrome", 1.0)
        return S("rc_chrome", 0.95 if (y > 0 and z < 0.5) else 0.7)
    p = Paint("rc_chrome", 4, noise=0, grad=0, rim=0.2, ao=0, decal=hub)
    w2 = width / 2
    cyl(m, "cap", (0, 0), 4, w2, w2 + 0.75, p, axis="x")
    cyl(m, "cap", (0, 0), 4, -w2 - 0.75, -w2, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


def retro_camper(**kw):
    return camper_kit(**kw).body


def _part(name):
    return lambda: camper_kit().all()[name]


ALL = {
    NAME: retro_camper,
    "wheel_retro_camper": wheel_retro_camper,
    **{f"{NAME}_{p}": _part(f"{NAME}_{p}") for p in PARTS},
}
