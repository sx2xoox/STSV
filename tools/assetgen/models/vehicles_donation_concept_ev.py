"""
The donation car 콘셉트 EV (content item `concept_ev`): a four-seat electric concept car. An original design: no
real make, no badge, no grille.

Drawn like every other car on the server (models/vehicles.py, vehicles_premium.py: read their docs first) and like
the donation 머슬카 (vehicles_donation_muscle_car.py, the "angled panels" style): 16 px a block, the body drawn at its
real size and shrunk by 2 (Kit scale 2), shown at vehicle.body-scale 2; the wheel a model of its own at 1. The body is
a few big clean boxes; every slope is a flat panel turned 22.5 or 45 degrees about one axis (plank()) laid
over a fill of boxes under it (profile(): their tops hide inside the panel), so a slope reads as one sharp plane, never
as a 1 px staircase. The detail is painted on the big faces (world-space decals, so a turned panel carries on the
paint of the boxes round it): the light across each plane, the creases' bright lines, shut lines, the louvres, the
glass's reflections. Only what should stand out is real 3D: the LED bar and its visor, the tail bar and its frame, the
splitter, the diffuser's fins, the glowing sill line, flush handles, camera pods, the wheels.

Drawn coordinates: 16 px a block, the car's ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT;
the driver sits on the left (-x).

The shape: a very low, wide wedge of flat planes and sharp creases.
  * nose: a knife edge carrying one thin white LED bar the whole width (it wraps round the bevelled corners) in a
    black visor, a mesh intake and a splitter under it. The hood one straight plane at 22.5 degrees from the nose to
    the screen, sunk between two blade fenders that rise (22.5 degrees) over the front wheels and end in a 45 degree
    cut at the door.
  * canopy: black glass the whole way (two-tone over the pearl): the screen (45 then 22.5 degrees) between black A
    pillars, a flat roof with 45 degree edges, upright side glass standing on broad flat shoulders, an upright rear
    window. Behind it two floating C-pillars
    (flying buttresses at 22.5 degrees from the roof's corners down to the tail) with open air under them.
  * side: a flat shoulder with a 45 degree crease along it, a satin black blade set in along the door's foot with a
    glowing accent line under it, angular arches (flat tops, 45 degree corners), flush handles, camera pods on thin
    stalks, a charge flap on the left rear quarter.
  * back: haunches rising (22.5 degrees) over the rear wheels, a louvred black deck between the buttresses, a lip,
    the tail cut off square with one red bar the whole width in a black frame, a satin diffuser with real fins.
  * inside: four floating seats (each a slim shell on one pedestal), one wide screen across a slim dash, a yoke, a
    floating console.

Paint arguments (one more call = one more colour; 8-step ramps like the ones registered here read best):
colour (the body), roof_ (the black glass canopy), accent (the glowing line, the wheels' rings, the cabin's light
strips), trim (the seats).

Its parts: door_l and door_r, gull-wing doors: each the side between the wheels (above the sill) with its whole side
glass and camera pod, hinged along the roof's edge (vehicles.yml: axis forward, they swing up and out).
"""

import math
from types import SimpleNamespace

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp, ladder
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


def _n(ramp):
    """Steps in a ramp (followed through palette.remapped, so a tuning paint keeps its lights)."""
    spec = MATERIALS.get(REMAP.get(ramp, ramp))
    return spec[4] if spec else 5


def tone(ramp, t):
    """(ramp, step) at lightness t: 0 the ramp's darkest, 1 its lightest (whatever its length)."""
    return (ramp, max(0.0, t) * (_n(ramp) - 1))


def raw(ramp, t, a=255):
    """The colour of tone(ramp, t) as a raw pixel (for see-through planes)."""
    r, s = tone(ramp, t)
    c = ladder(r, math.floor(s + 0.5))
    return ("raw", (c[0], c[1], c[2], a))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


CLEAR = ("raw", (0, 0, 0, 0))

# ================================================================================================
# Shape (drawn px, 16 a block)
# ================================================================================================

T = math.tan(math.radians(22.5))
NOSE, TAIL = -39, 39           # z of the nose's face and the tail's
W = 16                         # the body's half width
GROUND, FLOOR = 2, 2.5         # the body's bottom, the cabin floor's top
WF, WR = -25, 25               # front / rear axle (z)
WY, WRAD = 7, 7                # wheel centre height, its radius (a 0.875 block wheel)
TRACK = 11                     # the wheels' inner faces (x): they stand from 11 out to 16
ARCH, ARCH_TOP, CHAMF = 8.5, 14.5, 5   # the arch: half length, its flat top, its 45 degree corners
D0, D1 = -16.5, 16.5           # the doors, between their seams (z): from arch to arch
SIDE, BELT = 12.5, 13.5        # the flank's top (the shoulder's crease runs from here up to BELT), the belt
HOOD_X = 9.5                   # the hood's half width (between the blade fenders)
CAB0 = -24                     # the screen's foot (the hood ends here, at BELT)
HOOD_NOSE = BELT - (CAB0 - NOSE) * T          # the hood's front edge (the knife edge)
FPEAK, FZ0, FZ1 = 16.5, -26, -19.5            # the blade fenders' top, from FZ0 to FZ1 (then a 45 degree cut)
FNOSE = FPEAK - (FZ0 - NOSE) * T              # the blades' front edge
HX = 10.5                      # the rear haunches' inner edge (the deck lies between them)
HPEAK = 16                     # the haunches' top
DECK = 14                      # the louvred deck between the buttresses
GB = 12                        # the canopy's half width (its side glass stands upright on the shoulders)
KNEE = -18                     # where the screen bends from 45 to 22.5 degrees
ROOF = 24.25                   # the roof's top
ZR0 = KNEE + (ROOF - (BELT + KNEE - CAB0)) / T  # where the screen meets the roof
RW = 15.75                     # the upright rear window (z)
EDGE_Y = ROOF - 1              # where the side glass meets the roof's 45 degree edge
SILL_X = 14.5                  # the sills' outer face (the glowing line on it)
BUT_X = (9.25, 11)           # the floating C-pillars (|x|)
BUT_Y = 15                     # where they land on the tail's lip
BUT_Z = RW + (ROOF - BUT_Y) / T
LED_Y = (5, 6)                 # the LED bar (y), its visor 4..7
TAIL_Y = (11.5, 12.5)          # the tail bar (y), its frame 10.5..13.5
DIFF_Z, DIFF_Y, DIFF_X = TAIL - 2.5, 5.5, 10.5  # the diffuser's recess
SEAT_X = 4.48                  # the seats' middles (|x|: vehicles.yml seats at 0.28)


def screen_y(z):
    """The screen's surface (y) at z (45 degrees to the knee, then 22.5 to the roof)."""
    if z < KNEE:
        return BELT + (z - CAB0)
    return min(ROOF, BELT + (KNEE - CAB0) + (z - KNEE) * T)


def open_top(z):
    """The top of a wheel arch's opening at z (flat, its corners cut at 45 degrees), or None away from the arches."""
    for wz in (WF, WR):
        d = abs(z - wz)
        if d < ARCH - 1e-9:
            return ARCH_TOP if d <= ARCH - CHAMF else ARCH_TOP - (d - (ARCH - CHAMF))
    return None


def in_arch(z, y, ax):
    t = open_top(z)
    return t is not None and y < t and ax > TRACK - 1.6


# ================================================================================================
# Angled panels (as the muscle car's)
# ================================================================================================

def _rot(p, axis, deg, o):
    """A point turned like Minecraft turns an element (the preview's minirender does the same)."""
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


def plank(m, name, axis, p0, p1, span, t, paint, decal=None, below=True):
    """A flat panel whose face runs along p0 -> p1 in the plane across `axis` (axis "x": points are (z, y), "z": (x, y),
    "y": (z, x)), t thick on one side (below=True: towards -y, or -x for axis "y"), spanning `span` along the axis.
    The slope must be a multiple of 22.5 degrees (at most 45). decal: a world-space decal. Returns the rotation."""
    (a0, b0), (a1, b1) = sorted((p0, p1))
    da, db = a1 - a0, b1 - b0
    length = math.hypot(da, db)
    phi = math.degrees(math.atan2(db, da))
    ang = -phi if axis == "x" else phi
    snap = round(ang / 22.5) * 22.5
    assert abs(snap - ang) < 0.05 and abs(snap) <= 45, f"{name}: {ang} degrees"
    lo, hi = (b0 - t, b0) if below else (b0, b0 + t)
    if axis == "x":
        frm, to, o = (span[0], lo, a0), (span[1], hi, a0 + length), (0, b0, a0)
    elif axis == "z":
        frm, to, o = (a0, lo, span[0]), (a0 + length, hi, span[1]), (a0, b0, 0)
    else:
        frm, to, o = (lo, span[0], a0), (hi, span[1], a0 + length), (b0, 0, a0)
    rot = (axis, snap, o)
    if decal is not None:
        paint = paint(decal=world(decal, rot))
    m.box(name, frm, to, paint, rotation=rot)
    return rot


def _x(side, a, b):
    """The x span between |x| a and b on one side."""
    return tuple(sorted((side * a, side * b)))


def _line(pts, z):
    for (za, ya), (zb, yb) in zip(pts, pts[1:]):
        if za - 1e-9 <= z <= zb + 1e-9:
            return ya + (yb - ya) * (z - za) / (zb - za)
    raise ValueError(z)


def profile(m, name, xs, pts, paint, decal, ybot=GROUND, arches=False, t=0.75):
    """A solid whose top follows the polyline pts [(z, y), ...] (each leg level or at 22.5 / 45 degrees) over the x
    span xs, from ybot up (arches: cut by the wheel arches' openings, their 45 degree corners lined by a panel).
    Level legs are plain boxes; a sloped leg is a panel t thick (plank) over boxes stepped so their tops stay inside
    it (1 px rise at 45 degrees, 0.75 px at 22.5): one clean plane outside."""
    z0, z1 = pts[0][0], pts[-1][0]
    cuts = {z for z, _ in pts}
    for (za, ya), (zb, yb) in zip(pts, pts[1:]):
        slope = abs(yb - ya) / (zb - za)
        if slope > 1e-6:
            step = 1.0 if slope > 0.7 else 0.75 / T
            k = 1
            while za + k * step < zb - 0.2:
                cuts.add(za + k * step)
                k += 1
    if arches:
        for wz in (WF, WR):
            for e in (-1, 1):
                for k in range(int(CHAMF) + 1):
                    cuts.add(wz + e * (ARCH - k))
    zs = sorted(z for z in cuts if z0 - 1e-9 <= z <= z1 + 1e-9)
    cells = []
    for a, b in zip(zs, zs[1:]):
        if b - a < 1e-6:
            continue
        top = min(_line(pts, a), _line(pts, b))
        sloped = abs(_line(pts, a) - _line(pts, b)) > 1e-6
        bot = ybot
        if arches:
            oa, ob = open_top(a + 1e-6), open_top(b - 1e-6)
            if oa is not None or ob is not None:
                bot = max(bot, oa or 0, ob or 0)
        if cells and cells[-1][3] == bot and abs(cells[-1][2] - top) < 1e-6 and cells[-1][4] == sloped:
            cells[-1][1] = b
            continue
        cells.append([a, b, top, bot, sloped])
    for a, b, top, bot, sloped in cells:
        if top - bot < 0.05:
            continue
        x0, x1 = (xs[0] + 0.05, xs[1] - 0.05) if sloped else xs
        m.box(name, (x0, bot, a), (x1, top, b), paint)
    for (za, ya), (zb, yb) in zip(pts, pts[1:]):
        if abs(yb - ya) > 1e-6:
            plank(m, name + "_top", "x", (za, ya), (zb, yb), xs, t, paint, decal=decal)


# ================================================================================================
# Paints
# ================================================================================================

def _make_paints(C, R, A, TR):
    def top_colour(x, y, z, ax):
        """Hood, fenders, haunches, deck: pearl lit from above, creases and shut lines, the louvred deck."""
        if RW + 0.5 < z < TAIL - 1.5 and ax < HX and y < DECK + 0.2:
            # the deck between the buttresses: black louvres, a light line down the middle
            if ax < 0.5:
                return tone(A, 0.5)
            return tone("ev_satin", 0.0) if math.floor(z) % 2 == 0 else tone("ev_satin", 0.75)
        if z < CAB0 and ax < HOOD_X:
            if abs(z - (NOSE + 6)) < 0.5:
                return tone(C, 0.45)                              # the frunk's shut line
            if math.floor(ax) == 8 and z > NOSE + 6:
                return tone(C, 0.45)
            if 3 <= ax < 5:
                return tone(C, 0.9)                               # a reflection band down the hood
            return tone(C, 0.76)
        if HOOD_X <= ax < HOOD_X + 1 and z < D0:
            return tone(C, 1.0)                                   # the blade's inner crease
        if HX <= ax < HX + 1 and z > D1:
            return tone(C, 1.0)                                   # the haunch's inner crease
        if (abs(z - D0) < 0.5 or abs(z - D1) < 0.5) and ax > GB:
            return tone(C, 0.4)                                   # door seams over the shoulder
        return tone(C, 0.82)

    def side_colour(x, y, z, ax):
        """The flanks: soft gradient, the crease's light, seams, the LED / tail wrap, the charge flap."""
        fz, fy = math.floor(z), math.floor(y)
        if z < NOSE + 2 and ax > W - 1.5:
            if LED_Y[0] <= y < LED_Y[1]:
                return ("raw", (236, 248, 255, 255))              # the LED bar wraps round the corner
            if 4 <= y < 7:
                return tone("ev_satin", 0.3)
        if z > TAIL - 2 and ax > W - 1.5:
            if TAIL_Y[0] <= y < TAIL_Y[1]:
                return ("raw", (255, 50, 62, 255))
            if 10.5 <= y < 13.5:
                return tone("ev_satin", 0.3)
        if y < 3.5:
            return tone("ev_satin", 0.4)
        if D0 <= z < D1 + 0.01 and (fz == math.floor(D0) or fz == math.floor(D1 - 0.5)) and y > 4:
            return tone(C, 0.15)                                  # the door seams
        t = open_top(z)
        if t is not None and y < t + 0.75:
            return tone(C, 0.3)                                   # the arch's lip
        if fy == math.floor(SIDE - 0.5):
            return tone(C, 0.86)                                  # light under the crease
        if x < 0 and 34 <= z < 36.5 and 9 <= y < 11.5:
            if z < 34.5 or z >= 36 or y < 9.5 or y >= 11:
                return tone(C, 0.3)                               # the charge flap's outline
            return tone(C, 0.6)
        return tone(C, 0.42 + 0.34 * smooth((y - 3) / 10))

    def body_decal(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        if f == "down":
            return tone("ev_satin", 0.1)
        inner = (f == "east" and x < 0) or (f == "west" and x > 0)
        if f in ("east", "west", "north", "south") and in_arch(z, y, ax):
            return tone("ev_satin", 0.05)                         # the wheel wells
        if f in ("east", "west") and open_top(z) is not None and y < ARCH_TOP + 0.05 and ax < W - 0.6:
            return tone("ev_satin", 0.05)
        if f == "up":
            if -24 < z < RW and ax < GB and y < FLOOR + 0.6:
                return tone("ev_satin", 0.35 if math.floor(z) % 4 else 0.15)   # the cabin's mat
            return top_colour(x, y, z, ax)
        if f in ("east", "west"):
            if inner:
                if CAB0 - 0.1 < z < RW + 0.8 and ax < GB + 0.1 and y < BELT + 0.1:
                    # the door cards and the tub's sides: white leather, an accent line
                    if math.floor(y) == 9:
                        return tone(A, 0.6)
                    return tone(TR, 0.55)
                return tone(C, 0.5 + 0.2 * smooth((y - DECK) / 3))     # the blades' / haunches' inner walls
            return side_colour(x, y, z, ax)
        if f == "north":
            if z < NOSE + 1:
                if y < 4:
                    # the intake under the visor: a fine black mesh
                    return tone("ev_satin", 0.7) if (math.floor(x) + math.floor(y)) % 2 else tone("ev_satin", 0.0)
                return tone(C, 0.62 + 0.2 * smooth((y - 7) / 4))
            if ax < GB and CAB0 < z < RW:
                return tone(TR, 0.4)
            return tone(C, 0.6)
        if f == "south":
            if z > TAIL - 0.5:
                if 6.5 <= y < 8.5 and ax < 5.25 and (ax >= 4.75 or y < 7 or y >= 8):
                    return tone(C, 0.3)                           # the plate's recess
                if y < DIFF_Y + 0.5 and ax < DIFF_X + 0.5:
                    return tone("ev_satin", 0.2)
                return tone(C, 0.5 + 0.22 * smooth((y - 6) / 9))
            if z > DIFF_Z - 0.5 and y < DIFF_Y:
                return tone("ev_satin", 0.05)                     # the diffuser's back wall
            if ax < GB and CAB0 < z < RW + 1:
                return tone(TR, 0.4)                              # the cabin's walls
            return tone(C, 0.5)
        return None

    def crease_decal(c):
        """The shoulder's 45 degree chamfer: the crease's bright line, the door seams across it."""
        if c.face != "up":
            return body_decal(c)
        z = c.p[2]
        if abs(z - D0) < 0.5 or abs(z - D1) < 0.5:
            return tone(C, 0.35)
        return tone(C, 1.0)

    def canopy(c):
        """The roof's black glass: a long diagonal reflection, its edges catching the light."""
        x, y, z = c.p
        if c.face == "down":
            return tone(TR, 0.6)                                  # the headliner
        if c.face == "up":
            ax = abs(x)
            if 2.5 <= ax < 4:
                return tone(R, 0.62)                              # a sky line down each side of the roof
            if 4 <= ax < 4.5:
                return tone(R, 0.5)
            return tone(R, 0.36 + 0.06 * smooth((z - ZR0) / 20))
        return tone(R, 0.5)

    def roof_edge(c):
        if c.face == "up":
            return tone(R, 0.85)
        if c.face == "down":
            return tone(TR, 0.6)
        return tone(R, 0.5)

    def satin(c):
        if c.face == "up":
            return tone("ev_satin", 0.65)
        if c.face == "down":
            return tone("ev_satin", 0.0)
        return tone("ev_satin", 0.4)

    def blade(c):
        """The satin blade set in along the door's foot: a fine bright lip along its top edge."""
        if c.face == "up":
            return tone("ev_satin", 0.7)
        if c.face in ("east", "west") and c.p[1] > 6.5:
            return tone("ev_satin", 0.75)
        return satin(c)

    def buttress(c):
        f = c.face
        if f == "up":
            return tone(C, 0.95)
        if f == "down":
            return tone(C, 0.35)
        if f in ("east", "west"):
            inner = (f == "east" and c.p[0] < 0) or (f == "west" and c.p[0] > 0)
            return tone(C, 0.5 if inner else 0.72)
        return tone(C, 0.6)

    flat = dict(noise=0, grad=0, rim=0, ao=0)
    return dict(
        body=Paint(C, 4, **flat, decal=body_decal),
        angled=Paint(C, 4, **flat),
        body_decal=body_decal, crease_decal=crease_decal, buttress=buttress,
        roof=Paint(R, 2, **flat, decal=canopy), roof_edge=roof_edge,
        satin=Paint("ev_satin", 1, noise=0, grad=0, rim=0.3, ao=0.3, decal=satin),
        blade=Paint("ev_satin", 1, noise=0, grad=0, rim=0, ao=0, decal=blade),
        glass=Paint(R, 1, noise=0, light=False),
    )


# ================================================================================================
# The car
# ================================================================================================

_KIT = {}


def concept_kit(colour="ev_pearl", roof_="ev_glass", accent="ev_cyan", trim="ev_trim", name="concept_ev"):
    key = (colour, roof_, accent, trim, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, ("door_l", "door_r"), scale=2.0)
    m = kit.body
    m.group("body")
    P = _make_paints(colour, roof_, accent, trim)
    ctx = dict(C=colour, R=roof_, A=accent, TR=trim, P=P)
    _shell(m, kit, ctx)
    _nose(m, ctx)
    _tail(m, ctx)
    _canopy(m, kit, ctx)
    _cabin(m, ctx)
    _door_parts(kit, ctx)
    m.display = _held_display(m)
    _KIT[key] = kit
    return kit


def _shell(m, kit, ctx):
    """The body's mass: the hood between the blade fenders, the blades, the cabin's floor and sills, the doors with
    their satin blade and shoulder crease, the haunches, the deck and the tail; the arches cut into them."""
    P = ctx["P"]
    body, ang, bd = P["body"], P["angled"], P["body_decal"]
    box = m.box
    # --- the front: the hood (one plane at 22.5 degrees from the knife edge to the screen's foot) over the core
    profile(m, "hood", (-HOOD_X, HOOD_X), [(NOSE, HOOD_NOSE), (CAB0, BELT)], body, bd)
    # the blade fenders: rising at 22.5 degrees from the nose to their crest over the wheel, a 45 degree cut at the
    # door; the corner of the nose bevelled (a 1 px strip at 45 degrees)
    fen = [(NOSE, FNOSE), (FZ0, FPEAK), (FZ1, FPEAK), (D0, BELT)]
    fen_c = [(NOSE + 1, FNOSE + T)] + fen[1:]
    for s in (-1, 1):
        profile(m, "blade", _x(s, HOOD_X, W - 1), fen, body, bd, arches=True)
        profile(m, "blade", _x(s, W - 1, W), fen_c, body, bd, arches=True)
        plank(m, "nose_bevel", "y", (NOSE, s * (W - 1)), (NOSE + 1, s * W), (GROUND, FNOSE + 0.2), 0.6, ang,
              decal=_corner_decal(bd), below=s > 0)
        # the wheel well's inner wall behind the hood (the cabin's footwell is behind it)
        box("well", _lo(s, HOOD_X - 0.5, TRACK - 0.5, FLOOR, CAB0), _hi(s, HOOD_X - 0.5, TRACK - 0.5, ARCH_TOP - 0.25, D0 - 0.05), body)
    # --- the middle: the cabin's floor, the sills (satin, a glowing line along them)
    box("floor", (-(TRACK), GROUND, CAB0), (TRACK, FLOOR, RW), body)
    for s in (-1, 1):
        box("sill", _lo(s, TRACK, SILL_X, 0.75, D0), _hi(s, TRACK, SILL_X, 4, D1), P["satin"])
        m.group("lights")
        box("accent", _lo(s, SILL_X, SILL_X + 0.25, 3.25, D0 + 0.5), _hi(s, SILL_X, SILL_X + 0.25, 3.75, D1 - 0.5),
            Paint(ctx["A"], 3, noise=0, light=False, glow=True,
                  decal=lambda c: ("raw", (96, 238, 255, 255)) if c.face in ("east", "west") else tone(ctx["A"], 0.8)))
        m.group("body")
    # --- the doors: the panel, the satin blade set in along its foot, the shoulder and its 45 degree crease
    for s in (-1, 1):
        d = kit["door_l" if s < 0 else "door_r"]
        d.box("door", _lo(s, GB, W, 7, D0), _hi(s, GB, W, SIDE, D1), body)
        d.box("door_blade", _lo(s, GB, W - 0.75, 4, D0), _hi(s, GB, W - 0.75, 7, D1), P["blade"])
        d.box("shoulder", _lo(s, GB, W - 1, SIDE, D0), _hi(s, GB, W - 1, BELT, D1), body)
        plank(d, "crease", "z", (s * (W - 1), BELT), (s * W, SIDE), (D0, D1), 0.75, ang, decal=P["crease_decal"])
    # --- the rear: the bulkhead behind the cabin, the core under the deck, the haunches (rising at 22.5 degrees
    # over the rear wheels), the lip the buttresses land on, the tail's bevelled corners
    box("bulkhead", (-GB, 4, RW), (GB, DECK, D1), body)
    box("core_r", (-HX, GROUND, D1), (HX, DECK, DIFF_Z), body)
    box("core_r", (-HX, DIFF_Y, DIFF_Z), (HX, DECK, TAIL), body)
    box("lip", (-HX, DECK, TAIL - 1.5), (HX, BUT_Y, TAIL), body)
    hz = D1 + (HPEAK - BELT) / T
    hpts = [(D1, BELT), (hz, HPEAK), (TAIL, HPEAK)]
    hpts_c = [(D1, BELT), (hz, HPEAK), (TAIL - 1, HPEAK)]
    for s in (-1, 1):
        profile(m, "haunch", _x(s, HX, W - 1), hpts, body, bd, arches=True)
        profile(m, "haunch", _x(s, W - 1, W), hpts_c, body, bd, arches=True)
        plank(m, "tail_bevel", "y", (TAIL - 1, s * W), (TAIL, s * (W - 1)), (GROUND, HPEAK), 0.6, ang,
              decal=_corner_decal(bd), below=s > 0)
    # --- the arches' 45 degree corners: a slim panel along each, painted as the arch's lip
    lip = Paint(ctx["C"], 4, noise=0, grad=0, rim=0, ao=0,
                decal=lambda c: tone("ev_satin", 0.05) if c.face == "down" else tone(ctx["C"], 0.32))
    for s in (-1, 1):
        for wz, xin in ((WF, HOOD_X), (WR, HX)):
            for e in (-1, 1):
                z0 = wz + e * ARCH
                z1 = wz + e * (ARCH - CHAMF)
                plank(m, "arch_corner", "x", (z0, ARCH_TOP - CHAMF), (z1, ARCH_TOP), _x(s, xin, W + 0.02), 0.75, lip,
                      below=False)


def _corner_decal(bd):
    """The bevelled corners: their faces see the flank's paint (the light bars wrap round them)."""
    def d(c):
        x, y, z = c.p
        if c.face in ("east", "west", "north", "south"):
            q = SimpleNamespace(**{**c.__dict__, "face": "east" if x > 0 else "west"})
            return bd(q)
        return bd(c)
    return d


def _lo(s, a, b, y, z):
    return (min(s * a, s * b), y, z)


def _hi(s, a, b, y, z):
    return (max(s * a, s * b), y, z)


def _nose(m, ctx):
    """The LED bar the whole width in its black visor, the splitter under the mesh intake."""
    def visor(c):
        if c.face == "up":
            return tone("ev_steel", 0.8)                          # a bright lip over the light
        if c.face == "down":
            return tone("ev_satin", 0.0)
        return tone("ev_satin", 0.25)
    m.box("visor", (-(W - 1), 4, NOSE - 0.25), (W - 1, 7, NOSE), Paint("ev_satin", 1, noise=0, grad=0, rim=0, ao=0, decal=visor))
    m.group("lights")

    def led(c):
        # the LED bar: a hot white strip broken into fine segments
        if c.face != "north":
            return ("raw", (200, 228, 244, 255))
        return ("raw", (246, 252, 255, 255)) if math.floor(c.p[0]) % 6 else ("raw", (214, 236, 250, 255))
    m.box("led_bar", (-(W - 1), LED_Y[0], NOSE - 0.5), (W - 1, LED_Y[1], NOSE - 0.25),
          Paint("ev_led", 2, noise=0, light=False, glow=True, decal=led))
    m.group("body")
    m.box("splitter", (-(W - 1.5), GROUND - 0.5, NOSE - 1), (W - 1.5, GROUND, NOSE + 1), ctx["P"]["satin"])


def _tail(m, ctx):
    """The red bar the whole width in its black frame, the diffuser's fins."""
    def frame(c):
        if c.face == "up":
            return tone("ev_steel", 0.7)
        if c.face == "down":
            return tone("ev_satin", 0.0)
        return tone("ev_satin", 0.25)
    m.box("tail_frame", (-(W - 1), 10.5, TAIL), (W - 1, 13.5, TAIL + 0.25), Paint("ev_satin", 1, noise=0, grad=0, rim=0, ao=0, decal=frame))
    m.group("lights")

    def bar(c):
        if c.face != "south":
            return ("raw", (200, 30, 44, 255))
        return ("raw", (255, 52, 64, 255)) if math.floor(c.p[0]) % 6 else ("raw", (226, 32, 48, 255))
    m.box("tail_bar", (-(W - 1), TAIL_Y[0], TAIL + 0.25), (W - 1, TAIL_Y[1], TAIL + 0.5),
          Paint("ev_tail", 2, noise=0, light=False, glow=True, decal=bar))
    m.group("body")
    # the diffuser's fins standing in its recess
    fin = Paint("ev_satin", 1, noise=0, grad=0, rim=0.4, ao=0.2,
                decal=lambda c: tone("ev_satin", 0.8) if c.face in ("up", "south") else tone("ev_satin", 0.35))
    for fx in (-9, -6, -3, 0, 3, 6, 9):
        m.box("fin", (fx - 0.25, GROUND, DIFF_Z), (fx + 0.25, DIFF_Y, TAIL + 0.5), fin)


def _canopy(m, kit, ctx):
    """The black glass canopy: the screen (45 then 22.5 degrees, clear between black A pillars), the flat roof with
    1 px 45 degree edges, the upright side glass (the door's rides with the door), the upright rear window, the
    floating C-pillars. The glasshouse stands upright on the flat shoulders (a narrower canopy on a broad body)."""
    P = ctx["P"]
    R = ctx["R"]
    glass = P["glass"]
    yk = screen_y(KNEE)

    def edge(y):
        return GB if y < EDGE_Y else GB - (y - EDGE_Y)

    def pane(c):
        x, y, z = c.p
        ax = abs(x)
        if ax > edge(y):
            return CLEAR                                          # beyond the roof's edge
        if ax > GB - 1.25 or y > ROOF - 1.2 or y < BELT + 0.6:
            return raw(R, 0.3)                                    # the black pillars and header round it
        if 20 <= y < 20.75 and -8 < x < 3:
            return ("raw", (170, 190, 222, 255))                  # the sky's reflection: one fine line
        return CLEAR
    plank(m, "screen", "x", (CAB0, BELT), (KNEE, yk), (-GB, GB), 0.5, glass, decal=pane)
    plank(m, "screen", "x", (KNEE, yk), (ZR0, ROOF), (-GB, GB), 0.5, glass, decal=pane)
    # the A pillars: black strips along the screen's edges, a hair over the pane
    pil = Paint(R, 2, noise=0, grad=0, rim=0, ao=0, decal=lambda c: tone(R, 0.5 if c.face == "up" else 0.25))
    for s in (-1, 1):
        plank(m, "a_pillar", "x", (CAB0, BELT + 0.05), (KNEE, yk + 0.05), _x(s, GB - 1.25, GB), 0.6, pil)
        plank(m, "a_pillar", "x", (KNEE, yk + 0.05), (ZR0, ROOF + 0.05), _x(s, GB - 1.25, GB), 0.6, pil)
    m.box("roof", (-(GB - 1), EDGE_Y, ZR0), (GB - 1, ROOF, RW), P["roof"])
    for s in (-1, 1):
        plank(m, "roof_edge", "z", (s * GB, EDGE_Y), (s * (GB - 1), ROOF), (ZR0, RW), 0.75, P["angled"],
              decal=P["roof_edge"])

    def side(c):
        x, y, z = c.p
        inner = (c.face == "east" and x < 0) or (c.face == "west" and x > 0)
        if z < ZR0 and y > screen_y(z) - 0.55:
            return CLEAR                                          # in front of the screen's line (the pillar covers it)
        if y > EDGE_Y - 0.6 or (z < ZR0 and y > screen_y(z) - 1.4):
            return raw(R, 0.3)                                    # its edges: the black pillars
        if y < BELT + 0.6:
            return raw(R, 0.05)                                   # the seal along the belt
        if inner:
            return raw(R, 0.15, 110)                              # from inside: tinted, seen through (the driver's
                                                                  # side view; the outer face keeps the black glass)
        if 18 <= y < 19:
            return raw("ev_steel", 0.42)                          # the horizon's reflection: a clean band along it
        if 19 <= y < 19.6:
            return raw("ev_steel", 0.22)
        if y >= 19.6:
            return raw(R, 0.42)                                   # the sky over it
        return raw(R, 0.08 + 0.18 * smooth((y - BELT) / 4.5))
    gl = glass(decal=side)
    for s in (-1, 1):
        d = kit["door_l" if s < 0 else "door_r"]
        lo, hi = _x(s, GB - 0.5, GB)
        m.box("side_glass", (lo, BELT + 0.1, CAB0), (hi, EDGE_Y, D0), gl)
        d.box("side_glass", (lo, BELT, D0), (hi, EDGE_Y, RW), gl)

    def rear(c):
        x, y, z = c.p
        ax = abs(x)
        if ax > GB - 1.25 or y > EDGE_Y - 0.75 or y < DECK + 0.75:
            return raw(R, 0.3)
        if 18.5 <= y < 19.5:
            return raw("ev_steel", 0.32)
        return raw(R, 0.1 + 0.25 * smooth((y - DECK) / 8))
    m.box("rear_glass", (-GB, DECK, RW - 0.5), (GB, EDGE_Y, RW), glass(decal=rear))
    # the floating C-pillars: from the roof's back corners down to the lip, open air under them
    for s in (-1, 1):
        plank(m, "buttress", "x", (RW, ROOF), (BUT_Z, BUT_Y), _x(s, *BUT_X), 1.5, P["angled"], decal=P["buttress"])


def _cabin(m, ctx):
    """What shows through the glass: four floating seats (each a slim shell on one pedestal), one wide screen across
    a slim dash, a yoke, a floating console."""
    A, TR = ctx["A"], ctx["TR"]

    def seat_decal(c):
        x, y, z = c.p
        lx = abs(abs(x) - SEAT_X)
        if c.face in ("north", "south", "up") and 1.25 <= lx < 1.75:
            return tone(A, 0.5)                                   # a light seam down each side of the insert
        return tone(TR, 0.75 if c.face == "up" else 0.55)
    leather = Paint(TR, 2, noise=0, grad=0, rim=0.5, ao=0.3, decal=seat_decal)
    base = Paint("ev_satin", 2, noise=0, rim=0.4)
    for sz in (-3.5, 11.2):
        for sx in (-SEAT_X, SEAT_X):
            m.box("pedestal", (sx - 1, FLOOR, sz - 1), (sx + 1, 3.5, sz + 0.5), base)
            m.box("cushion", (sx - 2.5, 3.5, sz - 3.5), (sx + 2.5, 4.5, sz + 1.5), leather)
            m.box("back", (sx - 2.5, 3.5, sz + 1.5), (sx + 2.5, 15, sz + 2.75), leather)
            m.box("headrest", (sx - 1.75, 15, sz + 1.75), (sx + 1.75, 17, sz + 2.75), leather)
    # the dash: a slim blade between the blades' backs, one wide screen floating over it
    m.box("dash", (-HOOD_X, 11, -21), (HOOD_X, 12, -16.5), Paint(TR, 1, noise=0, rim=0.4,
          decal=lambda c: tone(A, 0.5) if c.face == "south" else tone(TR, 0.5)))

    def scr(c):
        if c.face != "south":
            return tone("ev_satin", 0.3)
        x, y = c.p[0], c.p[1]
        if math.floor(y) == 13 and math.floor(x) % 5 in (1, 2):
            return tone("ev_screen", 1.0)
        if math.floor(x) % 8 == 3:
            return tone("ev_screen", 0.1)
        return tone("ev_screen", 0.65)
    m.box("display", (-9, 12.25, -18), (9, 14.25, -17.5), Paint("ev_screen", 1, noise=0, light=False, glow=True, decal=scr))
    # the yoke (driver on the left): a bar and two grips round a small hub, on a column from the dash
    ycx, ycy, ycz = -SEAT_X, 10.5, -14
    grip = Paint("ev_satin", 1, noise=0, rim=0.6)
    m.box("column", (ycx - 0.5, ycy - 0.5, -16.5), (ycx + 0.5, ycy + 0.5, ycz), grip)
    m.box("yoke_hub", (ycx - 1, ycy - 0.75, ycz), (ycx + 1, ycy + 0.75, ycz + 0.5),
          Paint("ev_steel", 4, noise=0, decal=lambda c: tone(A, 0.8) if c.face == "south" else tone("ev_steel", 0.6)))
    m.box("yoke_bar", (ycx - 2.75, ycy - 1.25, ycz), (ycx + 2.75, ycy - 0.75, ycz + 0.5), grip)
    for gx in (-3, 2.5):
        m.box("yoke_grip", (ycx + gx, ycy - 1.5, ycz - 0.1), (ycx + gx + 0.5, ycy + 1.5, ycz + 0.6), grip)
    # the console: a floating spine between the front seats, a light line along its top
    m.box("console", (-1, 5, -15), (1, 6, 2), Paint(TR, 1, noise=0, rim=0.4,
          decal=lambda c: tone(A, 0.6) if c.face == "up" and abs(c.p[0]) < 0.5 else tone(TR, 0.5)))
    m.box("console_leg", (-0.5, FLOOR, -1), (0.5, 5, 0.5), base)


def _door_parts(kit, ctx):
    """On each door: two flush handles (a slim satin bar with a lit lip over it), a camera pod on a thin stalk."""
    A = ctx["A"]
    P = ctx["P"]
    handle = Paint("ev_satin", 1, noise=0, grad=0, rim=0, ao=0,
                   decal=lambda c: tone(ctx["C"], 1.0) if c.face == "up" else tone("ev_satin", 0.2))
    pod = Paint("ev_satin", 2, noise=0, grad=0, rim=0.5, ao=0,
                decal=lambda c: tone(A, 0.85) if c.face == "south" and c.u in (1, 2) and c.v in (1, 2) else
                tone("ev_satin", 0.85) if c.face == "up" else tone("ev_satin", 0.45))
    for s, part in ((-1, "door_l"), (1, "door_r")):
        d = kit[part]
        for hz in (-6.5, 8):
            d.box("handle", _lo(s, W, W + 0.15, 10.25, hz), _hi(s, W, W + 0.15, 10.75, hz + 3), handle)
        z = D0 + 1.5
        d.box("camera_stalk", _lo(s, GB - 0.25, W + 1, BELT + 0.25, z + 0.75), _hi(s, GB - 0.25, W + 1, BELT + 0.75, z + 1.25),
              P["satin"])
        d.box("camera", _lo(s, W + 0.75, W + 2.25, BELT - 0.25, z), _hi(s, W + 0.75, W + 2.25, BELT + 1, z + 2), pod)


def concept_ev(colour="ev_pearl", roof_="ev_glass", accent="ev_cyan", trim="ev_trim", name="concept_ev"):
    return concept_kit(colour, roof_, accent, trim, name).body


def _part(name):
    return lambda: concept_kit().all()[name]


# ================================================================================================
# The wheel: 14 px (0.875 block) across, 5 px wide
# ================================================================================================

def wheel_concept(ring="ev_cyan", cover="ev_pearl", name="wheel_concept"):
    """콘셉트 EV's wheel: a big closed aero wheel on a low tyre. A turbine cover: a pale disc with six swept vanes
    round a dark hub, a lit ring near its rim, a bright lip round it."""
    m = BModel(name, shift=(8, 8, 8), floor=None)
    d, width = 14, 5
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            return tone("ev_satin", 0.0 if rr > r - 0.9 else 0.45)   # the low sidewall, a lit bead
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.2)
        return tone("ev_satin", 0.0 if k % 3 == 0 else 0.3)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("ev_satin", 1, noise=0, rim=0.2, decal=tire), axis="x")

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face not in ("east", "west"):
            return tone("ev_steel", 0.5)
        if rr > 4.3:
            return tone("ev_steel", 0.95 if y > 0 else 0.65)          # the rim's bright lip
        if rr > 3.4:
            return tone(ring, 0.75)                                   # the lit ring
        if rr < 1.1:
            return tone(ring, 0.75) if rr < 0.6 else tone("ev_satin", 0.0)
        if rr < 1.6:
            return tone("ev_satin", 0.1)
        a = math.atan2(z, y)
        t = ((a + rr * 0.18) * 6 / (2 * math.pi)) % 1.0             # six vanes swept round
        lit = 0.1 if y > 0 else 0
        return tone(cover, 0.88 + lit) if t < 0.5 else tone(cover, 0.6 + lit)
    p = Paint("ev_steel", 3, noise=0, rim=0.2, ao=0.3, decal=face)
    w2 = width / 2
    cyl(m, "cover", (0, 0), 10, w2, w2 + 0.25, p, axis="x", faces={"west": None})
    cyl(m, "cover", (0, 0), 10, -w2 - 0.25, -w2, p, axis="x", faces={"east": None})
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    "concept_ev": concept_ev,
    "wheel_concept": wheel_concept,
    "concept_ev_door_l": _part("concept_ev_door_l"),
    "concept_ev_door_r": _part("concept_ev_door_r"),
}
