"""
머슬카 (a donation car, content/stsv/items/vehicles.yml muscle_car): a big American muscle coupe in the spirit of the
late-60s / modern two-door pony-car (an original drawing: no make, no badge, no lettering anywhere). About 4.9 x 2.1
x 1.5 blocks: long, low, wide and boxy, a very long hood and a short deck.

Drawn like every other car on the server (models/vehicles.py, vehicles_premium.py: read their docs first): 16 px a
block, the body drawn at its real size and shrunk by 2 (BModel scale 2), shown at vehicle.body-scale 2; the wheel a
model of its own at 1. The "angled panels" style: the body is a handful of big clean boxes; every slope and bevel is a
flat panel turned 22.5 or 45 degrees about one axis (plank()): the raked screen and its pillars, the near-flat rear
glass, the chamfered roof edges, the crisp chamfer along the shoulder (the beltline crease), the nose's brow, the
power bulge's flanks and front, the scoop's tail, the 45-degree corners of the wheel arches, the haunches' kick and
the ducktail lip. The detail is painted on the big faces (world-space decals, so a panel turned 45 degrees carries on
the paint of the boxes round it): soft gradients, the crease's light, the scallop down the flank, shut lines, the
twin stripes, the grille mesh, the lamps' insides, the tail band's LED outline. Only what should stand out is real 3D:
the four round headlamps, the grille surround, bumpers, the tail band, exhaust tips, mirrors, handles, hood pins and
scoop lip, the filler cap, the wheels.

Drawn coordinates: 16 px a block, the car's ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT side;
the driver sits on the left (-x).

Its look:
  * front: a full-width grille set 1.5 px deep in a satin surround, four round lamps in it (two a side: a projector in
    a chrome reflector ringed by a glowing halo), a fine black mesh between them, a chrome blade bumper under it, a
    black valance with an intake and the plate.
  * hood: a raised power bulge (bevelled flanks, a sloped nose), a scoop on it with a chrome lip, two chrome hood pins,
    shut lines; two wipers at the cowl.
  * side: the flat sharp beltline (a 45-degree chamfer catching the light), a shallow scallop down the flank, square
    arches with 45-degree corners (the rear ones on flared haunches), a flush chrome handle, bullet mirrors, side
    markers, a chrome racing fuel filler on the right rear quarter.
  * back: one horizontal red tail lamp band the full width (split in the middle, lit LED outlines), a ducktail lip on
    the short deck, a chrome bumper, a black diffuser with two big round exhaust tips.
  * paint: the body colour (colour), two bold racing stripes over nose, hood, bulge, scoop, roof, deck and lip
    (stripe, or None), the cabin's vinyl (trim). All three are arguments (tuning colours later).
  * inside: two high-back vinyl bucket seats (pleated, piped), a rear bench, a dash with two big round dials and two
    small ones, a three-spoke wheel, a console with a pistol-grip shifter.

The plugin shows ONE wheel model at all four corners (vehicles.yml `wheel`): the "fat rear tyres" are a matter of
stance: the wheel is a fat one (13 px = 0.81 block across, 6 px wide) and the rear track is a pixel wider each side
(the rear wheels fill the flared haunches).

Its parts: door_l, door_r (the side between the two door seams with its frameless glass, mirror, handle and door card:
hinged at the front edge, they swing out about a vertical axis: vehicles.yml movers).
"""

import math
from types import SimpleNamespace

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp
from stsv_assets import presets


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("mc_orange", 52, 0.185, 0.68, 0.48, 8, True)     # a deep, hot muscle-car orange
_ramp("mc_stripe", 265, 0.012, 0.22, 0.22, 6, False)   # satin black stripes
_ramp("mc_black", 265, 0.012, 0.24, 0.26, 6, False)    # gloss black trim, grille, valance
_ramp("mc_chrome", 235, 0.014, 0.72, 0.56, 8, False)   # bumpers, pins, tips, bezels
_ramp("mc_rim", 250, 0.010, 0.30, 0.30, 6, False)      # dark graphite rims
_ramp("mc_led", 210, 0.050, 0.92, 0.12, 3, True)       # the headlamps' halo rings
_ramp("mc_tail", 24, 0.235, 0.52, 0.34, 5, True)       # the tail lamp band
_ramp("mc_amber", 62, 0.170, 0.74, 0.20, 3, True)      # side markers
_ramp("mc_vinyl", 30, 0.014, 0.31, 0.28, 5, False)     # black vinyl cabin
_ramp("mc_wood", 55, 0.080, 0.42, 0.26, 4, False)      # the pistol grip
_ramp("mc_caliper", 30, 0.190, 0.52, 0.22, 3, True)    # brake callipers


def _n(ramp):
    """Steps in a ramp (followed through palette.remapped, so a tuning paint keeps its lights)."""
    spec = MATERIALS.get(REMAP.get(ramp, ramp))
    return spec[4] if spec else 5


def tone(ramp, t):
    """(ramp, step) at lightness t: 0 the ramp's darkest, 1 its lightest (whatever its length), beyond continues."""
    return (ramp, t * (_n(ramp) - 1))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


# ================================================================================================
# Shape (drawn px, 16 a block)
# ================================================================================================

NOSE, TAIL = -38, 38           # z of the nose's face and the tail panel
W, WH = 16, 17                 # the body's half width; over the rear haunches
HAUNCH_Z = 11                  # where the haunches kick out
WF, WR = -23.5, 23             # front / rear axle (z)
WY, WRAD = 6.5, 6.5            # wheel centre height, its radius
ARCH, ARCH_TOP, CHAMF = 7.5, 13.5, 3.0   # the arch: half length, top, its 45-degree corners
TRACK_F, TRACK_R = 9.5, 10.5   # the wheels' inner faces (x)
GROUND, FLOOR = 2, 3           # the body's bottom, the cabin floor
SIDE = 15                      # the flank's top (the shoulder chamfer runs from here up to BELT)
BELT = 16                      # hood, fenders, door tops and deck
D0, D1 = -15, 7                # the door, between its two seams (z)
CAB = 13                       # the glasshouse's half width (its side glass)
ZS0, ROOF = -16, 24            # the screen's foot (at BELT), the roof's top
ZS1 = ZS0 + (ROOF - BELT)      # the screen's top (it rises at 45 degrees)
ZR = 10                        # the roof's back edge: the rear glass falls from here at 22.5 degrees
T = math.tan(math.radians(22.5))
CAB1 = ZR + (ROOF - BELT) / T  # where the rear glass meets the deck
GRILLE_X, GRILLE_Y0, GRILLE_Y1, GRILLE_Z = 14.5, 6.5, 13.5, NOSE + 1.5
LAMP_D = 4
LAMPS = ((7, 9.75, NOSE + 0.75), (12, 10, NOSE + 0.45))   # the four round lamps (x either side, y, face z): the inner
# pair a hair lower and the outer pair a hair prouder (their lenses and beams, models/vehicle_lights.py, must not share
# a plane)
TAIL_X, TAIL_Y0, TAIL_Y1, TAIL_Z = 14.5, 10, 13.5, TAIL + 0.5    # the tail lamp band (its face at TAIL_Z)
STRIPE = (1, 4)                # each racing stripe's |x| span


def arch_at(z):
    """(wheel z, the arch's outer x) of the arch over z, or None."""
    for wz, wo in ((WF, W), (WR, WH)):
        if abs(z - wz) < ARCH + 0.01:
            return wz, wo
    return None


def in_arch(z, y):
    """Inside an arch's opening (seen from the side)."""
    a = arch_at(z)
    if a is None or y >= ARCH_TOP:
        return False
    d = abs(z - a[0])
    return y < ARCH_TOP - CHAMF + (ARCH - d) if d > ARCH - CHAMF else True


def outer(z):
    """The body's half width at z (the haunches from HAUNCH_Z back)."""
    return WH if z >= HAUNCH_Z else W


# ================================================================================================
# Angled panels
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
    The slope must be a multiple of 22.5 degrees. decal: a world-space decal (painted as if the panel were not turned).
    Returns the rotation."""
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


# ================================================================================================
# Paints
# ================================================================================================

def _make_paints(C, S, TR):
    """The car's paints for body colour C, stripe colour S (or None), cabin trim TR."""

    def stripe_at(ax):
        return S is not None and STRIPE[0] <= ax < STRIPE[1]

    def top_colour(x, y, z, ax):
        """Hood, roof, deck: the stripes, shut lines, a soft reflection band."""
        if stripe_at(ax):
            return tone(S, 0.62 if math.floor(ax) == 2 else 0.5)
        if NOSE < z < ZS0 - 1 and math.floor(ax) == 12:
            return tone(C, 0.36)                                        # the hood's shut lines
        if ZS0 - 1 <= z < ZS0 and ax < W:
            return tone("mc_black", 0.25)                               # the cowl
        if CAB1 + 1 <= z < CAB1 + 2 and ax < 14:
            return tone(C, 0.36)                                        # the deck lid's front seam
        if z > CAB1 + 1 and math.floor(ax) == 14:
            return tone(C, 0.36)
        if y > BELT + 4:
            return tone(C, 0.6 if 6 <= ax < 8 else 0.54)                # the roof, a soft reflection down it
        if 6 <= ax < 8 and NOSE + 2 < z < ZS0 - 2:
            return tone(C, 0.56)                                        # a soft reflection down the hood
        return tone(C, 0.5)

    def side_colour(x, y, z, ax):
        """The flanks (and the haunches): gradient, crease light, scallop, seams, markers, the arch lips."""
        fz, fy = math.floor(z), math.floor(y)
        if y < GROUND + 1:
            return tone(C, 0.16)
        if fz in (D0, D1 - 1) and y > GROUND + 1:
            return tone(C, 0.08)                                        # the door seams
        if fy == SIDE - 1:
            return tone(C, 0.74)                                        # the crease catching the light
        if fy == SIDE - 2:
            return tone(C, 0.40)                                        # its shadow
        a = arch_at(z)
        if a is not None and not in_arch(z, y):
            # the arch's lip: one texel round the opening
            if in_arch(z, y - 1) or in_arch(z - 1, y) or in_arch(z + 1, y):
                return tone(C, 0.28)
        # the scallop: a shallow recess from behind the front wheel to the rear haunch, its top lit, its foot shaded
        if WF + ARCH + 3 <= z < HAUNCH_Z + 1 and 6 <= y < 11:
            sweep = z - (WF + ARCH + 3)
            if sweep >= (11 - y) * 0.8:
                if fy == 10:
                    return tone(C, 0.30)                                # the lip's shadow
                return tone(C, 0.38 + 0.03 * (fy - 6))
        if WF + ARCH + 3 <= z < HAUNCH_Z + 1 and fy == 11 and z - (WF + ARCH + 3) >= 0:
            return tone(C, 0.74)                                        # the scallop's top edge, lit
        if NOSE <= z < NOSE + 2 and 11 <= y < 12:
            return tone("mc_amber", 0.7)                                # side marker lamps
        if TAIL - 2 <= z < TAIL and 11 <= y < 12:
            return tone("mc_tail", 0.5)
        if fy == GROUND + 1:
            return tone(C, 0.22)
        return tone(C, 0.34 + 0.24 * smooth((y - 3) / 10))

    def body_decal(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        if f == "down":
            if y > BELT + 3:
                return tone(TR, 0.55)                                   # the headliner
            return tone("mc_black", 0.05)
        # the wheel wells: dark (their inner walls, and the flank's faces inside an arch's opening)
        a = arch_at(z)
        if a is not None and y < ARCH_TOP + 0.01 and f in ("east", "west", "north", "south"):
            if ax < a[1] - 0.5 or in_arch(z, y) or f in ("north", "south"):
                return tone("mc_black", 0.08)
        inner = (f == "east" and x < 0) or (f == "west" and x > 0)
        cabin = ZS0 - 0.1 < z < CAB1 + 0.1 and ax < CAB + 0.1
        if f == "up":
            if cabin and y < BELT - 1:
                return tone(TR, 0.15 if math.floor(z) % 3 else 0.05)    # carpet
            return top_colour(x, y, z, ax)
        if f in ("east", "west"):
            if inner and ax < CAB + 0.6 and ZS0 - 0.1 < z < CAB1:
                # the door cards and the cabin's sides: vinyl, a chrome strip, a body-coloured panel
                if math.floor(y) == 12:
                    return tone("mc_chrome", 0.85)
                if 13 <= y < 15:
                    return tone(C, 0.45)
                return tone(TR, 0.35 if math.floor(y) % 2 else 0.22)
            if inner:
                return tone("mc_black", 0.1)
            return side_colour(x, y, z, ax)
        if f == "north":
            if y > SIDE and stripe_at(ax):
                return tone(S, 0.45)
            if ax < CAB + 0.1 and ZS0 - 1 < z < CAB1 and y < BELT:
                return tone(TR, 0.3)                                    # inside: the dash's foot
            return tone(C, 0.50 + 0.14 * smooth((y - 4) / 10))
        if f == "south":
            if y > SIDE and stripe_at(ax):
                return tone(S, 0.35)
            if ax < CAB + 0.1 and ZS0 < z < CAB1 + 1 and y < BELT:
                return tone(TR, 0.25)
            return tone(C, 0.40 + 0.16 * smooth((y - 4) / 10))
        return None

    def chamfer_decal(c):
        """The shoulder's 45-degree chamfer: the crease's bright line, the door seams across it."""
        z = c.p[2]
        if c.face != "up":
            return body_decal(c)
        if math.floor(z) in (D0, D1 - 1):
            return tone(C, 0.3)
        return tone(C, 0.84)

    def brow_decal(c):
        if c.face != "up":
            return body_decal(c)
        if stripe_at(abs(c.p[0])):
            return tone(S, 0.66)
        return tone(C, 0.86)

    return dict(
        body=Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=body_decal),
        flat=Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=body_decal),
        angled=Paint(C, 4, noise=0, grad=0, rim=0, ao=0),
        body_decal=body_decal, chamfer_decal=chamfer_decal, brow_decal=brow_decal, top_colour=top_colour,
        stripe_at=stripe_at,
    )


def _chrome(lit=0.95, side=0.72, low=0.40):
    """A chrome part: bright on top, a sky band over a dark ground reflection down its sides."""
    def decal(c):
        if c.face == "up":
            return tone("mc_chrome", lit)
        if c.face == "down":
            return tone("mc_chrome", 0.15)
        h = c.hi[1] - c.lo[1]
        if h >= 1.5:
            return tone("mc_chrome", side if c.p[1] > (c.lo[1] + c.hi[1]) / 2 else low)
        return tone("mc_chrome", side)
    return Paint("mc_chrome", 5, noise=0, grad=0, rim=0.4, ao=0.2, decal=decal)


CHROME = _chrome()
BLACK = Paint("mc_black", 1, noise=0, grad=0, rim=0.3, ao=0.5)
CLEAR = ("raw", (0, 0, 0, 0))


def _glass_decal(shape, frame=None):
    """A see-through pane (0.5 px plane), painted in world space: shape(p) -> None (clear: outside the glass),
    "glass" or a (ramp, step) / raw colour for the parts that are not glass (pillars, seals). The glass itself is
    clear but for a pale streak and a fine one."""
    def d(c):
        x, y, z = c.p
        got = shape(c)
        if got is None:
            return CLEAR
        if got != "glass":
            return ("raw", _rgb(got)) if got[0] != "raw" else got
        if (math.floor(z) + math.floor(y)) % 21 in (0, 1):
            return ("raw", (214, 226, 246, 90))
        return CLEAR
    return d


def _rgb(rs):
    from stsv_assets.palette import ladder
    ramp, step = rs
    return ladder(ramp, math.floor(step + 0.5))


# ================================================================================================
# The car
# ================================================================================================

class Kit:
    def __init__(self, name, parts):
        self.body = BModel(name, shift=(8, 8, 8), scale=2.0)
        self.parts = {}
        for part in parts:
            m = BModel(f"{name}_{part}", shift=(8, 8, 8), scale=2.0)
            m.display = presets.furniture(gui_scale=0.4)
            m.group(part)
            self.parts[part] = m

    def __getitem__(self, part):
        return self.parts[part]

    def all(self):
        return {self.body.name: self.body, **{m.name: m for m in self.parts.values()}}


_KIT = {}


def muscle_car(colour="mc_orange", stripe="mc_stripe", trim="mc_vinyl", name="muscle_car"):
    return muscle_kit(colour, stripe, trim, name).body


def muscle_kit(colour="mc_orange", stripe="mc_stripe", trim="mc_vinyl", name="muscle_car"):
    key = (colour, stripe, trim, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, ("door_l", "door_r"))
    m = kit.body
    m.group("body")
    P = _make_paints(colour, stripe, trim)
    ctx = dict(C=colour, S=stripe, TR=trim, P=P)
    _shell(m, kit, ctx)
    _front(m, ctx)
    _hood(m, ctx)
    _rear(m, ctx)
    _glass(m, kit, ctx)
    _cabin(m, ctx)
    _door_parts(kit, ctx)
    _side_details(m, ctx)
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
    """The body's boxes: the nose block behind the grille, the cores between the wheels, the flanks round the arches,
    the doors, the tops; the angled panels along the shoulder, round the arches and up the haunches."""
    P = ctx["P"]
    body, flat, ang = P["body"], P["flat"], P["angled"]
    bd = P["body_decal"]
    box = m.box
    # --- the front: the fascia round the grille's opening, the block behind it
    box("nose", (-W, GROUND, GRILLE_Z), (W, SIDE, WF - ARCH), body)
    box("fascia_brow", (-W, GRILLE_Y1, NOSE), (W, SIDE, GRILLE_Z), body)
    box("fascia_low", (-W, 4, NOSE), (W, GRILLE_Y0, GRILLE_Z), body)
    for s in (-1, 1):
        box("fascia_side", _lo(s, GRILLE_X, W, GRILLE_Y0, NOSE), _hi(s, GRILLE_X, W, GRILLE_Y1, GRILLE_Z), body)
    # --- the cores between the wheels (inside the wells), the cabin's floor
    box("core_f", (-TRACK_F + 0.5, GROUND, WF - ARCH), (TRACK_F - 0.5, SIDE, ZS0), body)
    box("floor", (-CAB, GROUND, ZS0), (CAB, FLOOR, CAB1), body)
    box("core_r", (-TRACK_R + 0.5, GROUND, CAB1), (TRACK_R - 0.5, SIDE, WR + ARCH), body)
    box("tail", (-W, GROUND, WR + ARCH), (W, SIDE, TAIL), body)
    # the cabin's back wall under the rear glass, the rear wheel housings inside it
    box("bulkhead", (-CAB, FLOOR, CAB1 - 0.5), (CAB, BELT, CAB1), body)
    for s in (-1, 1):
        box("housing", _lo(s, TRACK_R - 1, TRACK_R - 0.25, FLOOR, WR - ARCH), _hi(s, TRACK_R - 1, TRACK_R - 0.25, ARCH_TOP, CAB1 - 0.5), body)
    # --- the flanks
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        # over the front arch, the pillar in front of the door
        box("arch_top", _lo(s, TRACK_F - 0.5, W, ARCH_TOP, WF - ARCH), _hi(s, TRACK_F - 0.5, W, SIDE, WF + ARCH), body)
        box("a_post", _lo(s, CAB, W, GROUND, WF + ARCH), _hi(s, CAB, W, SIDE, D0), body)
        # the door (its sill stays on the body)
        box("sill", _lo(s, CAB, W, GROUND, D0), _hi(s, CAB, W, FLOOR, D1), body)
        door.box("door", _lo(s, CAB, W, FLOOR, D0), _hi(s, CAB, W, SIDE, D1), body)
        # the rear quarter, the haunch, the arch over the rear wheel, the tail's corner
        box("quarter", _lo(s, CAB, W, GROUND, D1), _hi(s, CAB, W, SIDE, WR - ARCH), body)
        box("haunch", _lo(s, W, WH, GROUND + 1, HAUNCH_Z), _hi(s, W, WH, SIDE, WR - ARCH), body)
        box("arch_top", _lo(s, TRACK_R - 1, WH, ARCH_TOP, WR - ARCH), _hi(s, TRACK_R - 1, WH, SIDE, WR + ARCH), body)
        box("tail_corner", _lo(s, W, WH, GROUND + 1, WR + ARCH), _hi(s, W, WH, SIDE, TAIL), body)
        # the haunch's leading edge: a panel kicking out at 45 degrees
        plank(m, "haunch_kick", "y", (HAUNCH_Z - 1, s * W), (HAUNCH_Z, s * WH), (GROUND + 1, SIDE), 0.75, ang,
              decal=bd, below=s > 0)
        # the arches' 45-degree corners: the corner filled in steps (a hair inside the flank), a slim panel along
        # the 45-degree edge over the steps, painted as the arch's lip
        lip = Paint(ctx["C"], 4, noise=0, grad=0, rim=0, ao=0,
                    decal=lambda c: tone("mc_black", 0.08) if c.face == "down" else tone(ctx["C"], 0.26))
        for wz, wo, xin in ((WF, W, TRACK_F - 0.5), (WR, WH, TRACK_R - 1)):
            for e in (-1, 1):
                z0 = wz + e * ARCH
                z1 = wz + e * (ARCH - CHAMF)
                for k in range(int(CHAMF) - 1):
                    za, zb = sorted((z0 - e * k, z0 - e * (k + 1)))
                    box("arch_step", _lo(s, wo - 2, wo - 0.05, ARCH_TOP - CHAMF + k + 1, za),
                        _hi(s, wo - 2, wo - 0.05, ARCH_TOP, zb), flat)
                plank(m, "arch_corner", "x", (z0, ARCH_TOP - CHAMF), (z1, ARCH_TOP), _x(s, xin, wo + 0.02),
                      0.75, lip, below=False)
    # --- the tops: hood and fenders, the shoulders beside the glasshouse, the deck
    box("hood", (-(W - 1), SIDE, NOSE + 1), (W - 1, BELT, ZS0), body)
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        box("shoulder", _lo(s, CAB, W - 1, SIDE, ZS0), _hi(s, CAB, W - 1, BELT, D0), body)
        door.box("shoulder", _lo(s, CAB, W - 1, SIDE, D0), _hi(s, CAB, W - 1, BELT, D1), body)
        box("shoulder", _lo(s, CAB, W - 1, SIDE, D1), _hi(s, CAB, W - 1, BELT, HAUNCH_Z), body)
        box("shoulder", _lo(s, CAB, WH - 1, SIDE, HAUNCH_Z), _hi(s, CAB, WH - 1, BELT, CAB1), body)
    box("deck", (-(WH - 1), SIDE, CAB1), (WH - 1, BELT, TAIL), body)
    # the shoulder's chamfer (the beltline crease): front fender, door, rear quarter, haunch
    cd = P["chamfer_decal"]
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        for target, z0, z1, w in ((m, NOSE + 1, D0, W), (door, D0, D1, W), (m, D1, HAUNCH_Z, W), (m, HAUNCH_Z, TAIL, WH)):
            plank(target, "crease", "z", (s * (w - 1), BELT), (s * w, SIDE), (z0, z1), 0.75, ang, decal=cd)
    # the nose's brow: a 45-degree bevel along the front edge of the hood
    plank(m, "brow", "x", (NOSE, SIDE), (NOSE + 1, BELT), (-(W - 1), W - 1), 0.75, ang, decal=P["brow_decal"])


def _lo(s, a, b, y, z):
    return (min(s * a, s * b), y, z)


def _hi(s, a, b, y, z):
    return (max(s * a, s * b), y, z)


def _front(m, ctx):
    """The four round lamps in the grille, its satin surround, the chrome blade bumper, the valance, the plate's flat."""
    C = ctx["C"]

    def mesh(c):
        # the grille's back: a fine black mesh (lit rows of its cells), a horizontal bar through its middle
        x, y, z = c.p
        if c.face != "north":
            return tone("mc_black", 0.1)
        fx, fy = math.floor(x), math.floor(y)
        if fy == 10 and abs(x) < 5:
            return tone("mc_chrome", 0.45)
        return tone("mc_black", 0.42) if (fx + fy) % 2 == 0 else tone("mc_black", 0.06)
    m.box("grille", (-GRILLE_X, GRILLE_Y0, GRILLE_Z - 0.25), (GRILLE_X, GRILLE_Y1, GRILLE_Z),
          Paint("mc_black", 1, noise=0, grad=0, rim=0, ao=0.6, decal=mesh), faces={"south": None, "up": None, "down": None, "east": None, "west": None})
    # the inside of the opening (its sides, top and bottom): black
    sat = Paint("mc_chrome", 3, noise=0, grad=0, rim=0.3, ao=0, decal=lambda c: tone("mc_chrome", 0.8 if c.face == "up" else 0.5))
    # the satin surround, a hair proud of the fascia
    zf = NOSE - 0.25
    m.box("surround", (-GRILLE_X - 0.5, GRILLE_Y1, zf), (GRILLE_X + 0.5, GRILLE_Y1 + 0.5, NOSE), sat)
    m.box("surround", (-GRILLE_X - 0.5, GRILLE_Y0 - 0.5, zf), (GRILLE_X + 0.5, GRILLE_Y0, NOSE), sat)
    for s in (-1, 1):
        m.box("surround", _lo(s, GRILLE_X, GRILLE_X + 0.5, GRILLE_Y0, zf), _hi(s, GRILLE_X, GRILLE_X + 0.5, GRILLE_Y1, NOSE), sat)
    m.group("lights")
    for s in (-1, 1):
        for lx, ly, lz in LAMPS:
            cx = s * lx

            def lamp(c, cx=cx, ly=ly):
                if c.face != "north":
                    return ("raw", (40, 42, 50, 255))
                dx, dy = c.p[0] - cx, c.p[1] - ly
                r = math.hypot(dx, dy)
                if r > 1.9:
                    return ("raw", (52, 56, 66, 255))                  # the bezel's dark corners
                if r > 1.2:
                    return ("raw", (190, 236, 255, 255))               # the halo ring
                return ("raw", (255, 252, 236, 255)) if dy > 0 or dx * cx < 0 else ("raw", (214, 222, 236, 255))   # the projector
            m.box("headlamp", (cx - LAMP_D / 2, ly - LAMP_D / 2, lz), (cx + LAMP_D / 2, ly + LAMP_D / 2, GRILLE_Z - 0.25),
                  Paint("mc_led", 2, noise=0, light=False, glow=True, decal=lamp))
    m.group("body")
    # the chrome blade bumper under the grille, wrapping round the corners
    m.box("bumper_f", (-(W - 1), 4.5, NOSE - 1.5), (W - 1, 6, NOSE), CHROME)
    for s in (-1, 1):
        m.box("bumper_f_end", _lo(s, W - 1, W + 0.25, 4.5, NOSE - 1), _hi(s, W - 1, W + 0.25, 6, NOSE + 2.5), CHROME)
        m.box("bumper_guard", _lo(s, 4.5, 5.5, 4.25, NOSE - 1.75), _hi(s, 4.5, 5.5, 6.25, NOSE - 1.5), BLACK)

    # the black valance under it: an intake mesh, the plate's flat
    def valance(c):
        x, y, z = c.p
        if c.face == "north" and 2.5 <= y < 4 and 4.5 < abs(x) < 13:
            return tone("mc_black", 0.0) if (math.floor(x) + math.floor(y)) % 2 else tone("mc_black", 0.35)
        return tone("mc_black", 0.3 if c.face != "up" else 0.5)
    m.box("valance", (-(W - 0.5), GROUND, NOSE - 0.5), (W - 0.5, 4.5, NOSE + 1.5), Paint("mc_black", 1, noise=0, grad=0, rim=0.3, decal=valance))
    m.box("plate_f", (-4, 2.5, NOSE - 0.75), (4, 4.5, NOSE - 0.5), Paint("mc_black", 0, noise=0, rim=0.2))


def _hood(m, ctx):
    """The power bulge (bevelled flanks, a sloped nose), the scoop on it, the hood pins, the wipers."""
    P = ctx["P"]
    C, S = ctx["C"], ctx["S"]
    ang, bd = P["angled"], P["body_decal"]
    bz0, bz1, bw = -33, ZS0 - 1, 6.5           # the bulge: its flat top from bz0 to the cowl, bw half wide
    top = BELT + 1

    def bulge(c):
        x, y, z = c.p
        if c.face == "up":
            return P["top_colour"](x, y, z, abs(x))
        return tone(C, 0.55)
    bp = Paint(C, 4, noise=0, grad=0, rim=0.5, ao=0.5, decal=bulge)
    m.box("bulge", (-bw, BELT, bz0), (bw, top, bz1), bp)
    # its nose slopes down at 22.5 degrees, its flanks at 45
    plank(m, "bulge_nose", "x", (bz0 - 1 / T, BELT), (bz0, top), (-bw, bw), 0.6, ang, decal=bd)
    for s in (-1, 1):
        plank(m, "bulge_flank", "z", (s * bw, top), (s * (bw + 1), BELT), (bz0, bz1), 0.6, ang,
              decal=lambda c: tone(C, 0.82) if c.face == "up" else bd(c))
    # the scoop
    sz0, sz1, sw, sh = -29, -22, 4.5, 1.5

    def scoop(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face == "north":
            return tone(C, 0.6)
        if c.face == "up":
            return P["top_colour"](x, y, z, ax)
        if c.face == "down":
            return tone("mc_black", 0)
        return tone(C, 0.5)
    sp = Paint(C, 4, noise=0, grad=0, rim=0.5, ao=0.3, decal=scoop)
    m.box("scoop", (-sw, top, sz0), (sw, top + sh, sz1), sp)
    plank(m, "scoop_tail", "x", (sz1, top + sh), (sz1 + sh / T, top), (-sw, sw), 0.6, ang, decal=bd)
    # the mouth: a black opening in its face, a chrome lip round it
    m.box("scoop_mouth", (-sw + 1, top + 0.25, sz0 - 0.05), (sw - 1, top + sh - 0.25, sz0),
          Paint("mc_black", 0, noise=0, light=False, decal=lambda c: ("raw", (10, 10, 14, 255))))
    m.box("scoop_lip", (-sw, top + sh - 0.1, sz0 - 0.35), (sw, top + sh + 0.25, sz0 + 0.5), _chrome(0.7, 0.55, 0.4))
    # hood pins: a chrome pin and its cable on each front corner of the hood
    for s in (-1, 1):
        x = s * 10.5
        m.box("hood_pin", (x - 0.75, BELT, -35.5), (x + 0.75, BELT + 0.3, -34), CHROME)
        m.box("hood_pin", (x - 0.25, BELT + 0.3, -35), (x + 0.25, BELT + 1, -34.5), CHROME)
        lo, hi = sorted((x + s * 0.75, x + s * 2))
        m.box("hood_pin_cable", (lo, BELT, -34.9), (hi, BELT + 0.2, -34.6), CHROME)
    # wipers resting at the screen's foot
    wip = Paint("mc_black", 0, noise=0, rim=0.4)
    for x0, x1 in ((-11, -1.5), (1.5, 10)):
        m.box("wiper", (x0, BELT, ZS0 - 0.9), (x1, BELT + 0.4, ZS0 - 0.4), wip)


def _rear(m, ctx):
    """The ducktail lip, the tail lamp band, the chrome bumper, the diffuser, the two big round tips."""
    P = ctx["P"]
    C, S = ctx["C"], ctx["S"]

    def lip(c):
        if c.face == "up":
            return P["brow_decal"](c)
        if c.face == "south":
            return tone(C, 0.5)
        return P["body_decal"](c)
    plank(m, "ducktail", "x", (TAIL - 1 / T * 1.0, BELT + 0.0), (TAIL, BELT + 1), (-(WH - 1.5), WH - 1.5), 1.0,
          P["angled"], decal=lip)

    # the tail lamp band: a black frame, two red lenses (an LED outline round each, a lit line through it)
    def tail(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face != "south":
            return ("raw", (40, 10, 14, 255))
        fy = math.floor(y)
        u0, u1 = 1.25, TAIL_X - 0.5
        if ax < u0 or ax >= u1 or y < TAIL_Y0 + 0.5 or y >= TAIL_Y1 - 0.5:
            return ("raw", (26, 26, 30, 255))
        edge = ax < u0 + 1 or ax >= u1 - 1 or fy == math.floor(TAIL_Y0 + 0.5) or fy == math.floor(TAIL_Y1 - 0.5) - 0 and y >= TAIL_Y1 - 1.5
        if edge:
            return ("raw", (255, 70, 72, 255))
        if fy == 11:
            return ("raw", (214, 30, 40, 255))
        return ("raw", (140, 12, 24, 255)) if math.floor(ax) % 2 else ("raw", (160, 16, 28, 255))
    m.group("lights")
    m.box("tail_band", (-TAIL_X, TAIL_Y0, TAIL - 0.25), (TAIL_X, TAIL_Y1, TAIL_Z),
          Paint("mc_tail", 2, noise=0, light=False, glow=True, decal=tail))
    m.group("body")

    # the plate's recess under it
    m.box("plate_r", (-4, 7, TAIL), (4, 9.5, TAIL + 0.1), Paint("mc_black", 0, noise=0, rim=0.2))
    # the chrome bumper
    m.box("bumper_r", (-(W - 0.5), 5, TAIL - 0.5), (W - 0.5, 6.75, TAIL + 1.25), CHROME)
    for s in (-1, 1):
        m.box("bumper_r_end", _lo(s, W - 0.5, WH + 0.25, 5, TAIL - 2), _hi(s, W - 0.5, WH + 0.25, 6.75, TAIL + 0.75), CHROME)

    # the black diffuser
    def diff(c):
        if c.face == "south":
            return tone("mc_black", 0.5) if math.floor(abs(c.p[0])) % 4 == 0 and abs(c.p[0]) > 3 else tone("mc_black", 0.12)
        return tone("mc_black", 0.3)
    m.box("diffuser", (-(W - 0.5), GROUND + 0.25, TAIL), (W - 0.5, 5, TAIL + 1), Paint("mc_black", 1, noise=0, grad=0, rim=0.3, decal=diff))

    def tip(c, cx):
        # a big oval tip: a chrome ring round a sooty bore
        if c.face == "south":
            dx, dy = abs(c.p[0] - cx), c.p[1] - 3.5
            if dx > 1 and abs(dy) > 1:
                return ("raw", (70, 72, 80, 255))
            if dx < 1 and abs(dy) < 0.6:
                return ("raw", (14, 14, 18, 255))
            return tone("mc_chrome", 0.95 if dy > 0 else 0.7)
        if c.face == "up":
            return tone("mc_chrome", 0.95)
        return tone("mc_chrome", 0.75 if c.p[1] > 3.5 else 0.35)
    for s in (-1, 1):
        cx = s * 10
        m.box("exhaust", (cx - 2, 2, TAIL), (cx + 2, 5, TAIL + 1.75),
              Paint("mc_chrome", 5, noise=0, grad=0, rim=0, ao=0, decal=lambda c, cx=cx: tip(c, cx)))


def _glass(m, kit, ctx):
    """The screen (a pane at 45 degrees between two body-coloured pillars), the roof (its long edges chamfered), the
    near-flat rear glass at 22.5 degrees, the side glass and the sails behind it (see-through planes, the door's
    ride with the door)."""
    P = ctx["P"]
    C, S = ctx["C"], ctx["S"]
    ang, bd = P["angled"], P["body_decal"]
    # the screen and its pillars
    pane = Paint("mc_black", 1, noise=0, light=False,
                 decal=lambda c: ("raw", (30, 34, 44, 255)) if c.v in (0, c.nv - 1) or c.u in (0, c.nu - 1) else
                 ("raw", (214, 226, 246, 70)) if (c.u + c.v) % 26 in (9, 10) else CLEAR)
    plank(m, "screen", "x", (ZS0 + 0.25, BELT), (ZS1 + 0.25, ROOF), (-(CAB - 1), CAB - 1), 0.5, pane)
    pillar = lambda c: tone(C, 0.82) if c.face == "up" else tone(C, 0.5) if c.face in ("east", "west") else tone(C, 0.4)
    for s in (-1, 1):
        plank(m, "a_pillar", "x", (ZS0, BELT), (ZS1, ROOF), _x(s, CAB - 1, CAB), 1.0, ang, decal=pillar)
    # the roof, its long edges chamfered at 45 degrees
    roofp = Paint(C, 4, noise=0, grad=0, rim=0.4, ao=0, decal=bd)
    m.box("roof", (-(CAB - 1), ROOF - 1, ZS1), (CAB - 1, ROOF, ZR), roofp)
    m.box("roof_rail", (-CAB, ROOF - 1.75, ZS1 - 0.5), (CAB, ROOF - 1, ZR), Paint(C, 4, noise=0, grad=0, rim=0, ao=0,
          decal=lambda c: tone(TR_(ctx), 0.55) if c.face == "down" else tone(C, 0.5)))
    for s in (-1, 1):
        plank(m, "roof_edge", "z", (s * (CAB - 1), ROOF), (s * CAB, ROOF - 1), (ZS1 - 0.3, ZR), 0.75, ang,
              decal=lambda c: tone(C, 0.86) if c.face == "up" else tone(C, 0.5))

    # the rear glass: a dark tinted panel, its edges the body colour (the sails' tops)
    def rglass(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face != "up":
            return tone(C, 0.56)
        if ax > CAB - 1.5:
            return tone(C, 0.8)
        if ax > CAB - 2.5:
            return ("raw", (22, 23, 28, 255))                          # the seal
        if (math.floor(z) - math.floor(x)) % 29 in (0, 1):
            return ("raw", (88, 100, 124, 255))                        # a streak of sky
        if z < ZR + 3.5:
            return ("raw", (66, 76, 96, 255))                          # the sky in its top
        return ("raw", (36, 42, 54, 255))
    plank(m, "rear_glass", "x", (ZR, ROOF), (CAB1, BELT), (-CAB, CAB), 1.0,
          Paint("mc_black", 1, noise=0, grad=0, rim=0, ao=0, light=True), decal=rglass)

    # the side glass and the sails: see-through planes on the glasshouse's sides
    def a_line(z):
        return BELT + (z - ZS0)                     # the A pillar's front edge (y at z)

    def r_line(z):
        return ROOF - (z - ZR) * T                  # the rear glass's edge

    QZ = 13.5                                        # the quarter glass's back edge (at the belt)

    def side_shape(c):
        x, y, z = c.p
        inside = (c.face == "east" and x < 0) or (c.face == "west" and x > 0)
        if y > ROOF - 1.75 or y > r_line(z) + 0.05 or y > a_line(z) - 0.2:
            return None
        if y < BELT + 0.6:
            return ("mc_black", 1)                  # the seal along the sill
        if y > ROOF - 2.5:
            return ("mc_black", 1)                  # the seal under the roof rail
        if y > a_line(z) - 1.2:
            return ("mc_black", 1)
        if D1 - 0.75 <= z < D1 + 0.5:
            return ("mc_black", 0)                  # the slim B pillar
        back = QZ - (y - BELT) * 0.55
        if z >= back:
            if inside:
                return tone(TR_(ctx), 0.3)
            if z < back + 0.75:
                return ("mc_black", 1)
            return tone(C, 0.56)                    # the sail (C pillar)
        return "glass"
    gl = Paint("mc_black", 1, noise=0, light=False, decal=_glass_decal(side_shape))
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        lo, hi = _x(s, CAB - 0.75, CAB - 0.25)
        door.box("side_glass", (lo, BELT, ZS0 + 0.5), (hi, ROOF - 1.75, D1), gl)
        m.box("quarter_glass", (lo, BELT, D1), (hi, ROOF - 1.75, CAB1), gl)


def TR_(ctx):
    return ctx["TR"]


def _cabin(m, ctx):
    """Two high-back bucket seats, a rear bench, the dash with round dials, the wheel, a console with a pistol grip."""
    C, TR = ctx["C"], ctx["TR"]
    SX = 5.5                                          # the seats' middles (|x|)

    def vinyl(c):
        x, y, z = c.p
        lx = abs(abs(x) - SX)
        if c.face in ("north", "south") and c.hi[1] - c.lo[1] > 4:
            if abs(lx - 2.25) < 0.5:
                return tone(C, 0.5)                   # piping in the body colour
            if lx < 1.75 and math.floor(y) % 2 == 0:
                return tone(TR, 0.15)                 # pleats
        if c.face == "up" and c.hi[1] - c.lo[1] < 3:
            if abs(lx - 2.25) < 0.5:
                return tone(C, 0.5)
            if lx < 1.75 and math.floor(z) % 2 == 0:
                return tone(TR, 0.15)
        return tone(TR, 0.55 if c.face == "up" else 0.35)
    seat = Paint(TR, 2, noise=0, grad=0, rim=0.5, ao=0.35, decal=vinyl)
    for sx in (-SX, SX):
        m.box("cushion", (sx - 3, FLOOR, -2.5), (sx + 3, 4.5, 4.5), seat)
        m.box("bolster", (sx - 3.75, FLOOR, -2), (sx - 3, 5.5, 4.5), seat)
        m.box("bolster", (sx + 3, FLOOR, -2), (sx + 3.75, 5.5, 4.5), seat)
        m.box("seat_back", (sx - 3.5, 4.5, 4.5), (sx + 3.5, 15, 6), seat)
        m.box("seat_head", (sx - 2.25, 15, 4.75), (sx + 2.25, 18.5, 6), seat)
    # the rear bench (for show: the car seats two)
    m.box("bench", (-(TRACK_R - 1), FLOOR, 13), (TRACK_R - 1, 5, 18), seat)
    m.box("bench_back", (-(TRACK_R - 1), 5, 18), (TRACK_R - 1, 12, 20), seat)
    m.box("parcel_shelf", (-CAB, 12, 20), (CAB, 12.5, CAB1 - 0.5), Paint(TR, 1, noise=0, rim=0.2))

    # the dash
    def dash(c):
        x, y, z = c.p
        if c.face == "up":
            return tone(TR, 0.25)
        if c.face == "south" and math.floor(y) == 11:
            return tone("mc_chrome", 0.8)               # a bright strip across the dash
        return tone(TR, 0.3)
    m.box("dash", (-CAB, 8.5, ZS0), (CAB, BELT - 0.5, -12.5), Paint(TR, 1, noise=0, grad=0, rim=0.3, ao=0.35, decal=dash))
    m.box("binnacle", (-9.5, BELT - 0.5, -13.5), (-1.5, BELT + 0.25, -12.25), Paint(TR, 1, noise=0, rim=0.4))

    def dials(c):
        # two big round dials before the driver, two small ones in the middle
        x, y, z = c.p
        if c.face != "south":
            return tone(TR, 0.3)
        for cx, cy, rad in ((-7.5, 13.5, 1.6), (-3.5, 13.5, 1.6), (0.75, 11.5, 1.0), (3.25, 11.5, 1.0)):
            r = math.hypot(x - cx, y - cy)
            if r < rad + 0.35:
                if r > rad - 0.4:
                    return ("raw", (214, 218, 226, 255))        # the chrome bezel
                if abs(math.atan2(y - cy, x - cx) - 2.3) < 0.5 and r > 0.3:
                    return ("raw", (255, 120, 40, 255))         # the orange needle
                return ("raw", (16, 16, 20, 255))
        return tone(TR, 0.3) if math.floor(y) != 11 else tone("mc_chrome", 0.8)
    m.box("dial_face", (-9.5, 10.5, -12.5), (5, BELT - 0.5, -12.25), Paint(TR, 1, noise=0, light=False, glow=True, decal=dials),
          faces={"north": None, "up": None, "down": None, "east": None, "west": None})

    # the three-spoke wheel (driver on the left)
    cx, cy, cz = -SX, 12.5, -10
    rim = Paint(TR, 1, noise=0, grad=0, rim=0.6, decal=lambda c: tone(TR, 0.45 if c.p[1] > cy else 0.25))
    for x0, y0, x1, y1 in ((-2.25, 1.75, 2.25, 2.25), (-2.25, -2.25, 2.25, -1.75), (-2.25, -1.75, -1.75, 1.75), (1.75, -1.75, 2.25, 1.75)):
        m.box("wheel", (cx + x0, cy + y0, cz), (cx + x1, cy + y1, cz + 0.5), rim)
    m.box("wheel_spoke", (cx - 1.75, cy - 0.25, cz + 0.1), (cx + 1.75, cy + 0.25, cz + 0.4), CHROME)
    m.box("wheel_spoke", (cx - 0.25, cy - 1.75, cz + 0.1), (cx + 0.25, cy, cz + 0.4), CHROME)
    m.box("column", (cx - 0.4, cy - 0.9, -12.5), (cx + 0.4, cy - 0.1, cz), Paint(TR, 1, noise=0))

    # the console and the pistol-grip shifter
    m.box("console", (-1.75, FLOOR, -12.5), (1.75, 6.5, 4), Paint(TR, 1, noise=0, grad=0, rim=0.4,
          decal=lambda c: tone("mc_chrome", 0.75) if c.face == "up" and abs(c.p[0]) > 1.25 else tone(TR, 0.4 if c.face == "up" else 0.25)))
    m.box("shifter", (-0.25, 6.5, -4.25), (0.25, 8.5, -3.75), CHROME)
    wood = Paint("mc_wood", 2, noise=0, grad=0, rim=0.5, decal=lambda c: tone("mc_wood", 0.7 if c.face == "up" else 0.4))
    m.box("pistol_grip", (-0.5, 8.5, -4.75), (0.5, 10.5, -3.25), wood)


def _door_parts(kit, ctx):
    """On each door: a bullet mirror, a flush chrome handle, an arm rest on the card."""
    C, TR = ctx["C"], ctx["TR"]
    for s, part in ((-1, "door_l"), (1, "door_r")):
        d = kit[part]
        # the mirror: a body-coloured bullet on a short chrome stalk at the screen's foot
        z = D0 + 1
        d.box("mirror_arm", _lo(s, W - 1, W + 0.75, BELT, z + 0.75), _hi(s, W - 1, W + 0.75, BELT + 0.75, z + 1.5), CHROME)

        def mirror(c):
            if c.face == "south":
                if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                    return tone(C, 0.4)
                return tone("mc_chrome", 0.8)
            if c.face == "up":
                return tone(C, 0.9)
            if c.face == "down":
                return tone(C, 0.2)
            return tone(C, 0.6)
        d.box("mirror", _lo(s, W + 0.25, W + 2.5, BELT + 0.5, z), _hi(s, W + 0.25, W + 2.5, BELT + 2, z + 2),
              Paint(C, 4, noise=0, grad=0, rim=0.3, ao=0, decal=mirror))
        # the handle: a flush chrome bar under the crease, near the door's back
        d.box("handle", _lo(s, W, W + 0.25, 12, D1 - 5), _hi(s, W, W + 0.25, 12.75, D1 - 2), CHROME)
        # the door card's arm rest
        d.box("arm_rest", _lo(s, CAB - 1, CAB, 9, D0 + 4), _hi(s, CAB - 1, CAB, 10, D1 - 2), Paint(TR, 2, noise=0, rim=0.4))


def _side_details(m, ctx):
    """The racing fuel filler on the right rear quarter."""
    z, y = 31.5, 12.25

    def cap(c):
        if c.face == "east":
            r = math.hypot(c.p[1] - y, c.p[2] - z)
            return tone("mc_chrome", 1.0 if r < 0.5 else 0.75 if r < 1.0 else 0.5)
        return tone("mc_chrome", 0.6)
    cyl(m, "filler", (y, z), 2, WH, WH + 0.35, Paint("mc_chrome", 5, noise=0, rim=0.4, decal=cap), axis="x")


def _part(name):
    return lambda: muscle_kit().all()[name]


# ================================================================================================
# The wheel: 13 px (0.81 block) across, 6 px wide
# ================================================================================================

def wheel_muscle():
    """A fat tyre round a dark five-split-spoke rim with a bright machined lip, a dark disc and an orange calliper
    behind the spokes."""
    m = BModel("wheel_muscle", shift=(8, 8, 8), floor=None)
    d, width = 13, 6
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 0.8:
                return tone("mc_black", 0.0)
            if r - 1.8 < rr <= r - 0.8:
                return tone("mc_black", 0.30)                      # a raised ring round the sidewall
            return tone("mc_black", 0.16)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.4)
        x = c.p[0]
        if abs(x) < 0.5:
            return tone("mc_black", 0.0)                           # the centre groove
        return tone("mc_black", 0.04 if (k + (x > 0)) % 3 == 0 else 0.22)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("mc_black", 1, noise=0, rim=0.2, decal=tire), axis="x")
    rim_d = 10

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        rc = rim_d / 2
        if c.face not in ("east", "west"):
            return tone("mc_rim", 0.4)
        if rr > rc - 0.75:
            return tone("mc_chrome", 0.9 if y > 0 else 0.62)         # the machined lip
        if rr < 1.0:
            return tone("mc_chrome", 0.7)                           # the centre cap
        if rr < 2.0:
            return tone("mc_rim", 0.45)                             # the hub
        a = math.atan2(z, y)
        t = (a * 5 / (2 * math.pi)) % 1.0
        # five spokes, each split in two arms that open towards the lip
        dpx = abs(t - 0.5) * 2 * math.pi * rr / 5                    # px from the spoke's middle line
        if dpx < 0.75 if rr < 2.8 else abs(dpx - 0.4 - 0.25 * (rr - 2.8)) < 0.5:
            return tone("mc_rim", 0.78 if y > 0.5 else 0.56)
        # between the spokes: an orange calliper over the top, the dark disc
        if rr > 2.6 and y > 1.2 and abs(z) < 1.6:
            return tone("mc_caliper", 0.55)
        return tone("mc_black", 0.0)
    p = Paint("mc_rim", 3, noise=0, rim=0.2, ao=0.3, decal=face)
    w2 = width / 2
    cyl(m, "hub", (0, 0), rim_d, w2, w2 + 0.25, p, axis="x")
    cyl(m, "hub", (0, 0), rim_d, -w2 - 0.25, -w2, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    "muscle_car": muscle_car,
    "wheel_muscle": wheel_muscle,
    "muscle_car_door_l": _part("muscle_car_door_l"),
    "muscle_car_door_r": _part("muscle_car_door_r"),
}
