"""
초밥 카니발 (a donation car, content/stsv/items/vehicles.yml sushi_carnival): a Korean internet meme. A big family
minivan (the long, tall, boxy-but-modern 7-seat MPV every Korean family seems to own: an original drawing, no make,
no badge, no lettering anywhere) with a long streamlined roof cargo box on its rails: from a distance the box sits on
the roof like a piece of sushi, hence the name. About 5.1 x 2.0 x 1.9 blocks for the van; the box takes it to about
2.4 blocks high.

Drawn like the 머슬카 (vehicles_donation_muscle_car.py, the approved "16 px, angled panels" style; read its doc
first): 16 px a block, the body drawn at its real size and shrunk by 2 (BModel scale 2), shown at vehicle.body-scale 2;
the wheel a model of its own at 1. The body is a handful of big clean boxes; every slope and bevel is a flat panel
turned 22.5 or 45 degrees about one axis (plank()): the nose's brow, the raked screen and its pillars, the shoulder's
crease, the roof's chamfered edges, the van's four vertical corners, the arches' 45-degree corners, and on the roof the
box's tapered nose and tail, its domed lid's shoulders and its chamfered lower edges. The detail is painted on the big faces
(world-space decals): soft gradients, the crease's light, a sculpted line down the flank, shut lines, the sliding
door's rail, the grille's mesh, the lamps' insides, the box's gloss, ribs and shut line.
Only what should stand out is real 3D: the slim lamps, the chrome grille frame, bumpers, mirrors, handles, roof rails
and cross bars, the box and its feet and lock, the tail lamps, the wheels.

Drawn coordinates: 16 px a block, the car's ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT side;
the driver sits on the left (-x).

Its look:
  * front: a short flat hood with a 45-degree brow, a big upright grille in a thick chrome frame (a diamond mesh with
    bright studs), slim LED headlamps on the upper corners with a light line along their tops, a black lower valance
    with fog lamp slots and the plate.
  * side: a long glasshouse of dark glass with black pillars (the roof seems to float), a crisp crease at the
    beltline, a sculpted line low on the doors, black arch cladding, front doors hinged at the front (with the mirrors),
    big sliding doors on BOTH sides (the rail painted along the rear quarter under the glass), chrome handles and a
    chrome strip along the sills.
  * back: a near-upright tailgate with a big dark window, wide tail lamps on the corners joined by a thin light bar, a
    roof spoiler, a black bumper with the plate above it.
  * roof: two silver roof rails, two black cross bars, and clamped on them THE ROOF BOX: long and low (about two
    thirds of the roof), a wedge nose tapering down at 22.5 degrees, a shorter tapered tail, a gently domed lid
    (22.5-degree shoulders), two faint ribs along it and a soft gloss line down the top, a thin shut line where the
    lid meets the base, a small lock barrel on each side, four feet clamped round the bars.
  * paint: the body colour (colour: pearl white), the cabin (trim: warm tan leather), the roof box (box: gloss
    graphite black "sc_box", or matte grey "sc_box_grey"). All are arguments.
  * inside: 7 seats: two front seats, two captain chairs in the middle row, a three-seat bench at the back; a dash
    with a wide screen, a steering wheel.

Its parts: door_l, door_r (the front doors: hinged at their front edge, they swing out about a vertical axis),
slide_l, slide_r (the sliding doors: they slide 2 px out and back along the van: vehicles.yml movers, slide).
"""

import math
from types import SimpleNamespace

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp
from stsv_assets import presets
from models import vehicles as _vehicles  # noqa: F401 (vehicles_premium is imported through it)
from models.vehicles_premium import Kit


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("sc_pearl", 95, 0.010, 0.80, 0.40, 8, False)     # pearl white paint
_ramp("sc_black", 260, 0.012, 0.22, 0.26, 6, False)    # gloss black trim, cladding, pillars
_ramp("sc_chrome", 235, 0.014, 0.74, 0.56, 8, False)   # grille frame, handles, strips, rails
_ramp("sc_rim", 240, 0.010, 0.62, 0.44, 6, False)      # silver alloy wheels
_ramp("sc_led", 210, 0.050, 0.92, 0.12, 3, True)       # the LED light lines
_ramp("sc_tail", 22, 0.230, 0.50, 0.34, 5, True)       # tail lamps
_ramp("sc_amber", 62, 0.170, 0.74, 0.20, 3, True)      # side repeaters
_ramp("sc_leather", 55, 0.045, 0.46, 0.34, 5, False)   # tan leather cabin
_ramp("sc_box", 255, 0.012, 0.30, 0.42, 7, False)     # the roof box: gloss graphite black
_ramp("sc_box_grey", 245, 0.008, 0.50, 0.34, 7, False) # the roof box: matte grey (an option)


def _n(ramp):
    """Steps in a ramp (followed through palette.remapped, so a tuning paint keeps its lights)."""
    spec = MATERIALS.get(REMAP.get(ramp, ramp))
    return spec[4] if spec else 5


def tone(ramp, t):
    """(ramp, step) at lightness t: 0 the ramp's darkest, 1 its lightest."""
    return (ramp, max(0.0, min(1.0, t)) * (_n(ramp) - 1))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def _h(*k):
    """A small stable hash of integers (for the rice grains)."""
    v = 0x9E3779B1
    for i in k:
        v = (v ^ (int(i) & 0xFFFFFFFF)) * 0x01000193 & 0xFFFFFFFF
        v ^= v >> 13
    return v


# ================================================================================================
# Shape (drawn px, 16 a block)
# ================================================================================================

T = math.tan(math.radians(22.5))
NOSE, TAIL = -41, 41           # z of the nose's face and the tailgate's face
W = 16                         # the body's half width
GROUND, FLOOR = 3, 5           # the body's bottom, the cabin floor
SIDE, BELT = 17, 18            # the flank's top (a 45-degree crease from here up to BELT), the hood and the beltline
GLASS_TOP, ROOF = 29, 30       # the side glass's top, the roof's top
CAB = 14.5                     # the doors' inner faces; the side glass from CAB to CAB + 0.5
WF, WR = -27, 24               # front / rear axle (z)
WY, WRAD = 7, 7                # wheel centre height, its radius
ARCH, ARCH_TOP, CHAMF = 8.5, 15, 3.0
TRACK = 10                     # the wheels' inner faces (x); the wheel's middle at 12.5
BROW = 3                       # the nose's 45-degree brow: from (NOSE, BELT - BROW) to (NOSE + BROW, BELT)
ZS0 = -31                      # the screen's foot (at BELT)
ZS1 = ZS0 + (ROOF - BELT)      # the screen's top (it rises at 45 degrees)
RZ1 = TAIL - 1                 # the roof's back edge
D0, D1 = -30, -13              # the front door (z): its front edge, its back edge (the B pillar)
S0, S1 = -13, 9                # the sliding door (z)
SLIDE_BACK = 20                # how far the sliding doors slide back (px)
GRILLE_X, GRILLE_Y0, GRILLE_Y1 = 9.5, 5.5, 12.5
LAMP = (10.5, 15.25, 12, 14.25)   # the slim headlamp: |x| from, to; y from, to (its face at NOSE - 0.4)
TAILL = (9, 15.25, 18.5, 21.5)
GATE_Y = 22                      # the tailgate's window from here up   # the tail lamps: |x| from, to; y from, to (their face at TAIL + 0.4)

# the roof box (on the cross bars over the roof rails)
RAIL_Y = ROOF + 1.5            # the rails' top
BAR_Y = RAIL_Y + 0.75          # the cross bars' top
BX = 10                        # the box's half width
BOX_Y0 = BAR_Y + 0.5           # its bottom (it stands on four feet on the bars)
BOX_Y1 = BOX_Y0 + 6.5            # its crown
CROWN = 7.5                    # the crown's half width (22.5-degree shoulders from here out to BX)
BOX_YS = BOX_Y1 - (BX - CROWN) * T     # the sides' top (where the shoulders end)
SHUT = BOX_Y0 + 2.5            # the shut line between the base and the lid
BOX_Z0, BOX_ZC0, BOX_ZC1, BOX_Z1 = -15, -6, 21, 26     # the nose's tip, the crown's ends, the tail
NOSE_DROP = (BOX_ZC0 - BOX_Z0) * T                       # how far the nose's top falls (22.5 degrees)
TAIL_DROP = (BOX_Z1 - BOX_ZC1) * T
CHEEK = 8.75                   # the nose's / tail's slope: full height inside this |x|, one shoulder lower outside


def arch_at(z):
    """(wheel z, the arch's outer x) of the arch over z, or None."""
    for wz in (WF, WR):
        if abs(z - wz) < ARCH + 0.01:
            return wz, W
    return None


def in_arch(z, y):
    """Inside an arch's opening (seen from the side)."""
    a = arch_at(z)
    if a is None or y >= ARCH_TOP:
        return False
    d = abs(z - a[0])
    return y < ARCH_TOP - CHAMF + (ARCH - d) if d > ARCH - CHAMF else True


# ================================================================================================
# Angled panels (as in vehicles_donation_muscle_car)
# ================================================================================================

def _rot(p, axis, deg, o):
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
    The slope must be a multiple of 22.5 degrees. decal: a world-space decal."""
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


def _lo(s, a, b, y, z):
    return (min(s * a, s * b), y, z)


def _hi(s, a, b, y, z):
    return (max(s * a, s * b), y, z)


def _rgb(rs):
    from stsv_assets.palette import ladder
    ramp, step = rs
    return ladder(ramp, math.floor(step + 0.5))


CLEAR = ("raw", (0, 0, 0, 0))


# ================================================================================================
# Paints
# ================================================================================================

def _make_paints(C, TR):
    """The van's paints for body colour C and cabin trim TR."""

    def side_colour(x, y, z):
        """The flanks: a soft gradient, the crease's light, the sculpted line, seams, cladding, the sill strip."""
        fz, fy = math.floor(z), math.floor(y)
        if y < GROUND + 2:
            return tone("sc_black", 0.18)                                # the black rocker cladding
        if fy == GROUND + 2 and -18 < z < 15:
            return tone("sc_chrome", 0.8)                                # the chrome sill strip
        a = arch_at(z)
        if a is not None and not in_arch(z, y):
            # black arch cladding: two texels round the opening
            if any(in_arch(z + dz, y + dy) for dz, dy in ((0, -1), (-1, 0), (1, 0), (0, -2), (-2, 0), (2, 0), (-1, -1), (1, -1))):
                return tone("sc_black", 0.22)
        if fz in (D0, D1 - 1, S1 - 1) and y > FLOOR and (fz != D0 or y > ARCH_TOP):
            return tone(C, 0.22)                                         # the door seams
        if WF + ARCH <= z < WF + ARCH + 1 and FLOOR < y < ARCH_TOP:
            return tone(C, 0.22)                                         # the front door's lower front seam
        if fy == SIDE - 1:
            return tone(C, 0.96)                                         # the crease catching the light
        if fy == SIDE - 2:
            return tone(C, 0.55)                                         # its shadow
        # the sculpted line: a lit edge and a shadow sweeping up a little towards the back
        line = 8 + (z - WF) * 0.03
        if WF + ARCH + 1 <= z < WR - ARCH - 1:
            if math.floor(y - (line - 8)) == 9:
                return tone(C, 0.92)
            if math.floor(y - (line - 8)) == 8:
                return tone(C, 0.5)
        if NOSE <= z < NOSE + 3 and 10 <= y < 11:
            return tone("sc_amber", 0.7)                                 # front side markers
        if TAIL - 3 <= z < TAIL and 19 <= y < 20:
            return tone("sc_tail", 0.5)
        return tone(C, 0.58 + 0.24 * smooth((y - 5) / 11))

    def top_colour(x, y, z, ax):
        if ax > 14 and y > BELT - 0.5 and y < BELT + 0.6:
            return tone(C, 0.9)
        if y > ROOF - 1.5:
            return tone(C, 0.92 if 4 <= ax < 7 else 0.84)                # the roof, a soft reflection down it
        if NOSE + BROW <= z < ZS0 - 1 and math.floor(ax) == 13:
            return tone(C, 0.6)                                          # the hood's shut lines
        if ZS0 - 1 <= z < ZS0 and ax < 14:
            return tone("sc_black", 0.25)                                # the cowl
        if 4 <= ax < 7:
            return tone(C, 0.94)
        return tone(C, 0.86)

    def body_decal(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        if f == "down":
            if y > GLASS_TOP:
                return tone(TR, 0.7)                                     # the headliner
            return tone("sc_black", 0.05)
        a = arch_at(z)
        if a is not None and y < ARCH_TOP + 0.01 and f in ("east", "west", "north", "south"):
            if ax < W - 0.5 or in_arch(z, y) or f in ("north", "south"):
                return tone("sc_black", 0.06)                            # the wheel wells
        inner = (f == "east" and x < 0) or (f == "west" and x > 0)
        cabin = ZS0 - 0.1 < z < RZ1 and ax < CAB + 0.1
        if f == "up":
            if cabin and y < BELT - 0.5:
                return tone(TR, 0.12 if math.floor(z) % 3 else 0.04)     # carpet
            return top_colour(x, y, z, ax)
        if f in ("east", "west"):
            if inner and ax < CAB + 0.6:
                # the door cards and the cabin's sides: leather, a chrome strip, a dark top
                if math.floor(y) == 13:
                    return tone("sc_chrome", 0.8)
                if y > 14:
                    return tone("sc_black", 0.35)
                return tone(TR, 0.5 if math.floor(y) % 3 else 0.35)
            if inner:
                return tone("sc_black", 0.1)
            return side_colour(x, y, z)
        if f == "north":
            if cabin and y < BELT:
                return tone("sc_black", 0.3)
            return tone(C, 0.66 + 0.16 * smooth((y - 4) / 12))
        if f == "south":
            if cabin and y < BELT:
                return tone(TR, 0.3)
            return tone(C, 0.56 + 0.2 * smooth((y - 4) / 12))
        return None

    def chamfer_decal(c):
        """The shoulder's 45-degree crease: a bright line, the door seams across it."""
        if c.face != "up":
            return body_decal(c)
        if math.floor(c.p[2]) in (D0, D1 - 1, S1 - 1):
            return tone(C, 0.45)
        return tone(C, 1.0)

    def bright(c):
        if c.face == "up":
            return tone(C, 1.0)
        return body_decal(c)

    return dict(
        body=Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=body_decal),
        angled=Paint(C, 4, noise=0, grad=0, rim=0, ao=0),
        body_decal=body_decal, chamfer_decal=chamfer_decal, bright=bright, top_colour=top_colour,
        side_colour=side_colour,
    )


def _chrome(lit=0.95, side=0.72, low=0.40):
    """A chrome part: bright on top, a sky band over a dark ground reflection down its sides."""
    def decal(c):
        if c.face == "up":
            return tone("sc_chrome", lit)
        if c.face == "down":
            return tone("sc_chrome", 0.15)
        h = c.hi[1] - c.lo[1]
        if h >= 1.5:
            return tone("sc_chrome", side if c.p[1] > (c.lo[1] + c.hi[1]) / 2 else low)
        return tone("sc_chrome", side)
    return Paint("sc_chrome", 5, noise=0, grad=0, rim=0.4, ao=0.2, decal=decal)


CHROME = _chrome()
BLACK = Paint("sc_black", 1, noise=0, grad=0, rim=0.3, ao=0.5)


def _glass(tint, alpha, frame):
    """A see-through pane, painted in world space: frame(c) -> None (glass) or a colour (pillars, seals)."""
    def d(c):
        x, y, z = c.p
        got = frame(c)
        if got is not None:
            return got if got[0] == "raw" else ("raw", _rgb(got))
        if (math.floor(z) + math.floor(y)) % 23 in (0, 1):
            return ("raw", (150, 168, 196, min(255, alpha + 30)))       # a streak of sky
        if y > GLASS_TOP - 3:
            return ("raw", (tint[0] + 24, tint[1] + 28, tint[2] + 36, alpha))
        return ("raw", (*tint, alpha))
    return d


# ================================================================================================
# The van
# ================================================================================================

_KIT = {}
PARTS = ("door_l", "door_r", "slide_l", "slide_r")


def sushi_carnival(colour="sc_pearl", trim="sc_leather", box="sc_box", name="sushi_carnival"):
    return carnival_kit(colour, trim, box, name).body


def carnival_kit(colour="sc_pearl", trim="sc_leather", box="sc_box", name="sushi_carnival"):
    key = (colour, trim, box, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, PARTS)
    m = kit.body
    m.group("body")
    P = _make_paints(colour, trim)
    ctx = dict(C=colour, TR=trim, B=box, P=P)
    _shell(m, kit, ctx)
    _front(m, ctx)
    _rear(m, ctx)
    _glasshouse(m, kit, ctx)
    _cabin(m, ctx)
    _door_parts(kit, ctx)
    _roof_rack(m, ctx)
    _roof_box(m, ctx)
    m.display = _held_display()
    _KIT[key] = kit
    return kit


def _held_display():
    d = presets.furniture(gui_scale=0.34)
    for k in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        d[k] = dict(d[k])
        d[k]["scale"] = [round(v / 2.5, 3) for v in d[k]["scale"]]
    return d


def _shell(m, kit, ctx):
    """The body's boxes: the nose and the engine bay, the floor, the flanks round the arches, the doors (front hinged,
    sliding), the tail; the crease along the shoulders, the nose's brow, the four bevelled corners, the arches' corners."""
    P = ctx["P"]
    body, ang = P["body"], P["angled"]
    bd = P["body_decal"]
    box = m.box
    # --- the nose (behind the fascia) and the engine bay between the front wheels, the hood over them
    box("nose", (-CAB, GROUND, NOSE + 1), (CAB, BELT - BROW, WF - ARCH), body)
    box("bay", (-(TRACK - 0.5), GROUND, WF - ARCH), (TRACK - 0.5, SIDE, ZS0), body)
    box("hood", (-(W - 1), SIDE, NOSE + BROW), (W - 1, BELT, ZS0), body)
    box("nose_top", (-(W - 1), BELT - BROW, NOSE + BROW), (W - 1, SIDE, WF - ARCH), body)
    # --- the cabin's floor (round the wheels)
    box("floor", (-(TRACK - 1), GROUND, ZS0), (TRACK - 1, FLOOR, RZ1), body)
    for s in (-1, 1):
        box("floor", _lo(s, TRACK - 1, CAB, GROUND, WF + ARCH), _hi(s, TRACK - 1, CAB, FLOOR, WR - ARCH), body)
        box("floor", _lo(s, TRACK - 1, CAB, GROUND, WR + ARCH), _hi(s, TRACK - 1, CAB, FLOOR, RZ1), body)
        # the wheel wells' inner walls and roofs
        box("well", _lo(s, TRACK - 1, TRACK - 0.5, FLOOR, ZS0), _hi(s, TRACK - 1, TRACK - 0.5, ARCH_TOP, WF + ARCH), body)
        box("well_top", _lo(s, TRACK - 0.5, W - 2.1, ARCH_TOP - 0.5, WF - ARCH), _hi(s, TRACK - 0.5, W - 2.1, ARCH_TOP, WF + ARCH), body)
        box("well", _lo(s, TRACK - 1, TRACK - 0.5, FLOOR, WR - ARCH), _hi(s, TRACK - 1, TRACK - 0.5, ARCH_TOP, WR + ARCH), body)
        box("well_top", _lo(s, TRACK - 0.5, W - 2.1, ARCH_TOP - 0.5, WR - ARCH), _hi(s, TRACK - 0.5, W - 2.1, ARCH_TOP, WR + ARCH), body)
        # the wells' end walls (the cabin must not show through the arches)
        box("well_end", _lo(s, TRACK + 0.25, W - 2.1, FLOOR, WF + ARCH), _hi(s, TRACK + 0.25, W - 2.1, ARCH_TOP - 0.5, WF + ARCH + 0.5), body)
        box("well_end", _lo(s, TRACK - 0.5, CAB, FLOOR, WR - ARCH), _hi(s, TRACK - 0.5, CAB, ARCH_TOP - CHAMF, WR - ARCH + 0.5), body)
        box("well_end", _lo(s, TRACK - 0.5, W - 2.1, ARCH_TOP - CHAMF, WR - ARCH), _hi(s, TRACK - 0.5, W - 2.1, ARCH_TOP - 0.5, WR - ARCH + 0.5), body)
        box("well_end", _lo(s, TRACK - 0.5, W - 2.1, FLOOR, WR + ARCH - 0.5), _hi(s, TRACK - 0.5, W - 2.1, ARCH_TOP - 0.5, WR + ARCH), body)
    # the cargo floor behind the bench, the tailgate's lower panel
    box("cargo", (-(TRACK - 1), FLOOR, 22), (TRACK - 1, 9, RZ1), body)
    # --- the flanks
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        slide = kit["slide_l" if s < 0 else "slide_r"]
        # the front fender: ahead of the arch, over it, the strip between the screen's foot and the door
        box("fender", _lo(s, CAB, W, GROUND, NOSE + 1), _hi(s, CAB, W, SIDE, WF - ARCH), body)
        box("arch_top", _lo(s, CAB, W, ARCH_TOP, WF - ARCH), _hi(s, CAB, W, SIDE, D0), body)
        # the front door: over the arch's back half, and down to the sill behind it
        door.box("door", _lo(s, CAB, W, ARCH_TOP, D0), _hi(s, CAB, W, SIDE, WF + ARCH), body)
        door.box("door", _lo(s, CAB, W, FLOOR, WF + ARCH), _hi(s, CAB, W, SIDE, D1), body)
        box("sill", _lo(s, CAB, W, GROUND, WF + ARCH), _hi(s, CAB, W, FLOOR, S1), body)
        # the sliding door
        slide.box("door", _lo(s, CAB, W, FLOOR, S0), _hi(s, CAB, W, SIDE, S1), body)
        # the rear quarter, the arch over the rear wheel, the corner behind it
        box("quarter", _lo(s, CAB, W, GROUND, S1), _hi(s, CAB, W, SIDE, WR - ARCH), body)
        box("arch_top", _lo(s, CAB, W, ARCH_TOP, WR - ARCH), _hi(s, CAB, W, SIDE, WR + ARCH), body)
        box("rear_side", _lo(s, CAB, W, GROUND, WR + ARCH), _hi(s, CAB, W, SIDE, RZ1), body)
        # the arches' 45-degree corners (filled in steps a hair inside the flank, a slim panel along the edge)
        lip = Paint("sc_black", 1, noise=0, grad=0, rim=0, ao=0,
                    decal=lambda c: tone("sc_black", 0.06) if c.face == "down" else tone("sc_black", 0.22))
        for wz in (WF, WR):
            for e in (-1, 1):
                z0 = wz + e * ARCH
                z1 = wz + e * (ARCH - CHAMF)
                target = door if (s, wz, e) in ((-1, WF, 1), (1, WF, 1)) else m
                for k in range(int(CHAMF) - 1):
                    za, zb = sorted((z0 - e * k, z0 - e * (k + 1)))
                    target.box("arch_step", _lo(s, W - 2, W - 0.05, ARCH_TOP - CHAMF + k + 1, za),
                               _hi(s, W - 2, W - 0.05, ARCH_TOP, zb), body)
                plank(target, "arch_corner", "x", (z0, ARCH_TOP - CHAMF), (z1, ARCH_TOP), _x(s, CAB, W + 0.02),
                      0.75, lip, below=False)
    # --- the shoulder's crease (45 degrees) from the brow to the tail, split at the doors' seams
    cd = P["chamfer_decal"]
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        slide = kit["slide_l" if s < 0 else "slide_r"]
        for target, z0, z1 in ((m, NOSE + BROW, D0), (door, D0, D1), (slide, S0, S1), (m, S1, RZ1)):
            plank(target, "crease", "z", (s * (W - 1), BELT), (s * W, SIDE), (z0, z1), 0.75, ang, decal=cd)
        # under the crease between the doors and the glass: the shoulder (the glass stands on it)
        for target, z0, z1 in ((m, ZS0, D0), (door, D0, D1), (slide, S0, S1), (m, S1, RZ1)):
            target.box("shoulder", _lo(s, CAB, W - 1, SIDE, z0), _hi(s, CAB, W - 1, BELT, z1), body)
    # the nose's brow: a 45-degree bevel along the front of the hood, from the fascia's top up to the hood
    plank(m, "brow", "x", (NOSE, BELT - BROW), (NOSE + BROW, BELT), (-W + 0.02, W - 0.02), 2.2, ang, decal=P["bright"])
    # the four vertical corners: a 45-degree bevel each (1 px)
    for s in (-1, 1):
        plank(m, "corner_f", "y", (NOSE, s * (W - 1)), (NOSE + 1, s * W), (GROUND, BELT - BROW), 0.75, ang, decal=bd,
              below=s > 0)
        plank(m, "corner_r", "y", (TAIL - 1, s * W), (TAIL, s * (W - 1)), (GROUND, SIDE), 0.75, ang, decal=bd,
              below=s > 0)


def _front(m, ctx):
    """The fascia with the big grille in its chrome frame, the slim lamps, the valance, fog lamps, the plate."""
    C = ctx["C"]

    def fascia(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face != "north":
            return ctx["P"]["body_decal"](c)
        if y < GROUND + 2.5:
            return tone("sc_black", 0.2)
        return tone(C, 0.64 + 0.2 * smooth((y - 4) / 10))
    fp = Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=fascia)
    # the fascia skin round the grille's opening (the grille sits 1 px in)
    zf = NOSE
    m.box("fascia", (-(W - 1), GROUND, zf), (W - 1, GRILLE_Y0 - 0.5, zf + 1), fp)
    m.box("fascia", (-(W - 1), GRILLE_Y1 + 0.5, zf), (W - 1, BELT - BROW, zf + 1), fp)
    for s in (-1, 1):
        m.box("fascia", _lo(s, GRILLE_X + 0.5, W - 1, GRILLE_Y0 - 0.5, zf), _hi(s, GRILLE_X + 0.5, W - 1, GRILLE_Y1 + 0.5, zf + 1), fp)

    # the grille: a diamond mesh with bright studs, a slim bar across
    def mesh(c):
        x, y, z = c.p
        if c.face != "north":
            return tone("sc_black", 0.1)
        fx, fy = math.floor(x + 0.5), math.floor(y)
        if fy % 2 == 0:
            if (fx + (fy // 2) * 2) % 5 == 0:
                return tone("sc_black", 0.1)                             # the breaks between the slats' links
            return tone("sc_chrome", 0.9 if fy >= 10 else 0.72 if fy >= 8 else 0.55)   # the chrome slats
        return tone("sc_black", 0.02)
    m.box("grille", (-GRILLE_X - 0.5, GRILLE_Y0 - 0.5, NOSE + 0.75), (GRILLE_X + 0.5, GRILLE_Y1 + 0.5, NOSE + 1),
          Paint("sc_black", 1, noise=0, grad=0, rim=0, ao=0, decal=mesh))
    # the thick chrome frame, proud of the fascia
    fr = _chrome(0.98, 0.8, 0.5)
    zo = NOSE - 0.6
    m.box("grille_frame", (-GRILLE_X - 1, GRILLE_Y1, zo), (GRILLE_X + 1, GRILLE_Y1 + 1, NOSE), fr)
    m.box("grille_frame", (-GRILLE_X - 1, GRILLE_Y0 - 1, zo), (GRILLE_X + 1, GRILLE_Y0, NOSE), fr)
    for s in (-1, 1):
        m.box("grille_frame", _lo(s, GRILLE_X, GRILLE_X + 1, GRILLE_Y0, zo), _hi(s, GRILLE_X, GRILLE_X + 1, GRILLE_Y1, NOSE), fr)
    # the slim LED headlamps on the upper corners, a light line along their tops
    m.group("lights")
    for s in (-1, 1):
        x0, x1, y0, y1 = LAMP

        def lamp(c, s=s):
            if c.face != "north":
                return ("raw", (40, 42, 50, 255))
            ax, y = abs(c.p[0]), c.p[1]
            if y >= y1 - 0.5:
                return ("raw", (210, 240, 255, 255))                     # the LED line
            if ax < x0 + 1.25 or ax > x1 - 1.25:
                return ("raw", (52, 56, 66, 255))
            k = math.floor(ax - x0 - 1.25)
            return ("raw", (255, 252, 236, 255)) if k % 2 == 0 else ("raw", (150, 160, 178, 255))   # projector cells
        m.box("headlamp", _lo(s, x0, x1, y0, NOSE - 0.4), _hi(s, x0, x1, y1, NOSE),
              Paint("sc_led", 2, noise=0, light=False, glow=True, decal=lamp))
    m.group("body")

    # the black valance: an intake slot across, the fog lamp slots, the plate
    def valance(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face == "north" and 3.5 <= y < 4.5 and 4.5 <= ax < 8.5:
            return tone("sc_black", 0.0) if math.floor(x) % 2 else tone("sc_black", 0.4)
        if c.face == "north" and 3.5 <= y < 4.5 and 10 <= ax < 13:
            return ("raw", (230, 236, 244, 255))                         # fog lamp slots
        return tone("sc_black", 0.32 if c.face != "up" else 0.5)
    m.box("valance", (-(W - 0.5), GROUND - 0.5, NOSE - 1), (W - 0.5, GRILLE_Y0 - 1, NOSE + 1),
          Paint("sc_black", 1, noise=0, grad=0, rim=0.3, decal=valance))
    m.box("skid", (-8, GROUND - 0.5, NOSE - 1.25), (8, GROUND + 0.5, NOSE - 1), _chrome(0.9, 0.7, 0.5))


def _rear(m, ctx):
    """The tailgate (its big dark window is the glasshouse's), the wide tail lamps joined by a light bar, the plate, the
    spoiler, the black bumper."""
    C = ctx["C"]

    def gate(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face != "south":
            return ctx["P"]["body_decal"](c)
        if math.floor(ax) == 14 and y > 7:
            return tone(C, 0.4)                                          # the tailgate's shut lines
        if 11 <= y < 14.5 and ax < 5:
            return tone("sc_black", 0.25)                                # the plate's recess
        if math.floor(y) == 15 and ax < 8:
            return tone("sc_chrome", 0.85)                               # a chrome strip over the plate
        return tone(C, 0.6 + 0.2 * smooth((y - 4) / 12))
    m.box("tail", (-(W - 1), GROUND, RZ1), (W - 1, GATE_Y, TAIL), Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=gate))

    def tail(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face != "south":
            return ("raw", (60, 12, 16, 255))
        x0, x1, y0, y1 = TAILL
        if ax < x0:
            return ("raw", (200, 26, 36, 255)) if 19.5 <= y < 20.5 else ("raw", (30, 30, 34, 255))
        if ax < x0 + 0.75 or ax >= x1 - 0.75 or y < y0 + 0.5 or y >= y1 - 0.5:
            return ("raw", (255, 76, 78, 255))                           # the lit LED outline
        if math.floor(y) == 20:
            return ("raw", (220, 32, 42, 255))
        return ("raw", (140, 14, 26, 255)) if math.floor(ax) % 2 else ("raw", (162, 18, 30, 255))
    m.group("lights")
    tp = Paint("sc_tail", 2, noise=0, light=False, glow=True, decal=tail)
    for s in (-1, 1):
        m.box("tail_lamp", _lo(s, TAILL[0], TAILL[1], TAILL[2], TAIL), _hi(s, TAILL[0], TAILL[1], TAILL[3], TAIL + 0.4), tp)
    # the thin light bar between them, a hair less proud (its glow must not share their plane)
    m.box("tail_bar", (-TAILL[0], TAILL[2], TAIL), (TAILL[0], TAILL[3], TAIL + 0.2), tp)
    m.group("body")
    # the black bumper with a reflector each end, the step on top
    def bumper(c):
        x, y, z = c.p
        if c.face == "south" and 4.5 <= y < 5.5 and 11.5 <= abs(x) < 14:
            return ("raw", (150, 20, 26, 255))
        if c.face == "up":
            return tone("sc_black", 0.5)
        return tone("sc_black", 0.28)
    m.box("bumper_r", (-(W - 0.5), GROUND - 0.5, TAIL - 0.75), (W - 0.5, GROUND + 3.5, TAIL + 1),
          Paint("sc_black", 1, noise=0, grad=0, rim=0.3, decal=bumper))
    m.box("bumper_step", (-10, GROUND + 3.5, TAIL), (10, GROUND + 3.75, TAIL + 0.75), _chrome(0.85, 0.6, 0.45))
    # the roof spoiler over the tailgate's window
    sp = Paint(C, 4, noise=0, grad=0, rim=0.3, ao=0,
               decal=lambda c: tone("sc_black", 0.15) if c.face == "down" else tone(C, 0.9) if c.face == "up" else tone(C, 0.66))
    m.box("spoiler", (-(W - 2.5), ROOF - 0.75, RZ1 - 1), (W - 2.5, ROOF + 0.5, TAIL + 1.25), sp)
    m.box("stop_lamp", (-3, ROOF - 0.75, TAIL + 1.25), (3, ROOF - 0.25, TAIL + 1.3),
          Paint("sc_tail", 1, noise=0, light=False, decal=lambda c: ("raw", (190, 24, 34, 255))))


def _glasshouse(m, kit, ctx):
    """The raked screen between black pillars, the roof with chamfered edges, the side glass (front doors lighter, the
    rest privacy-dark) with black B, C and D pillars, the tailgate's window."""
    P = ctx["P"]
    C, TR = ctx["C"], ctx["TR"]
    ang = P["angled"]
    # the screen and its black pillars
    pane = Paint("sc_black", 1, noise=0, light=False,
                 decal=lambda c: ("raw", (26, 28, 34, 255)) if c.v in (0, c.nv - 1) or c.u in (0, c.nu - 1) else
                 ("raw", (200, 214, 236, 120)) if (c.u + c.v) % 29 in (9, 10, 11) else ("raw", (40, 50, 64, 120)))
    plank(m, "screen", "x", (ZS0 + 0.25, BELT), (ZS1 + 0.25, ROOF), (-(W - 2), W - 2), 0.5, pane)
    pil = Paint("sc_black", 1, noise=0, grad=0, rim=0.4, ao=0,
                decal=lambda c: tone("sc_black", 0.6) if c.face == "up" else tone("sc_black", 0.25))
    for s in (-1, 1):
        plank(m, "a_pillar", "x", (ZS0, BELT), (ZS1, ROOF), _x(s, W - 2, W - 1 + 0.02), 1.0, pil)
    # the roof, its long edges chamfered at 45 degrees
    roofp = Paint(C, 4, noise=0, grad=0, rim=0.3, ao=0, decal=P["body_decal"])
    m.box("roof", (-(W - 2), GLASS_TOP, ZS1), (W - 2, ROOF, RZ1), roofp)
    for s in (-1, 1):
        plank(m, "roof_edge", "z", (s * (W - 2), ROOF), (s * (W - 1), GLASS_TOP), (ZS1 - 0.3, RZ1), 0.75, ang,
              decal=lambda c: tone(C, 1.0) if c.face == "up" else tone(C, 0.7))

    def a_line(z):
        return BELT + (z - ZS0)                     # the A pillar's front edge (y at z)

    def frame(c):
        x, y, z = c.p
        if y > a_line(z) + 0.05:
            return CLEAR
        if y < BELT + 0.5 or y > GLASS_TOP - 0.5 or y > a_line(z) - 1.5:
            return ("sc_black", 1)                  # the seals, the A pillar
        if D1 - 1.25 <= z < S0 + 1.25:
            return ("sc_black", 0.5)                # the B pillar
        if S1 - 0.75 <= z < S1 + 1.25:
            return ("sc_black", 0.5)                # the C pillar
        if z >= RZ1 - 4:
            return ("sc_black", 0.8)                # the D pillar, wrapping round to the tailgate glass
        if ZS0 <= z < D0 + 2:
            return ("sc_black", 1)                  # the black quarter-light triangle in front of the door glass
        return None
    light = Paint("sc_black", 1, noise=0, light=False, decal=_glass((36, 44, 58), 150, frame))
    dark = Paint("sc_black", 1, noise=0, light=False, decal=_glass((20, 23, 30), 225, frame))
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        slide = kit["slide_l" if s < 0 else "slide_r"]
        lo, hi = _x(s, CAB, CAB + 0.5)
        m.box("quarter_light", (lo, BELT, ZS0 + 0.25), (hi, GLASS_TOP, D0), light)
        door.box("side_glass", (lo, BELT, D0), (hi, GLASS_TOP, D1), light)
        slide.box("side_glass", (lo, BELT, S0), (hi, GLASS_TOP, S1), dark)
        m.box("quarter_glass", (lo, BELT, S1), (hi, GLASS_TOP, RZ1), dark)
        # the sliding door's rail: a dark groove along the quarter, just under the glass
        m.box("slide_rail", _lo(s, W - 1.2, W - 0.9, BELT - 0.6, S1), _hi(s, W - 1.2, W - 0.9, BELT - 0.1, RZ1 - 2),
              Paint("sc_black", 0, noise=0, rim=0.2))

    # the tailgate's window: a big dark pane, the defroster lines, a wiper
    def rear_glass(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face != "south":
            return tone("sc_black", 0.2)
        if ax > W - 2.5 or y < GATE_Y + 0.75 or y > GLASS_TOP - 0.25:
            return tone("sc_black", 0.7)
        if (math.floor(x) - math.floor(y)) % 17 in (0, 1):
            return ("raw", (96, 108, 132, 255))
        if math.floor(y) % 2 == 0 and ax < 10 and y < 24:
            return ("raw", (34, 38, 48, 255))
        return ("raw", (24, 27, 34, 255)) if y < 24 else ("raw", (46, 54, 70, 255))
    m.box("rear_glass", (-(W - 1), GATE_Y, RZ1), (W - 1, GLASS_TOP, TAIL), Paint("sc_black", 1, noise=0, light=False, decal=rear_glass))
    m.box("rear_wiper", (-7, GATE_Y + 1, TAIL), (1, GATE_Y + 1.4, TAIL + 0.3), BLACK)


def _cabin(m, ctx):
    """Seven seats (two front seats, two captain chairs, a three-seat bench), the dash with a wide screen, the wheel."""
    C, TR = ctx["C"], ctx["TR"]

    def leather(c):
        x, y, z = c.p
        if c.face == "up":
            return tone(TR, 0.75 if math.floor(z) % 3 else 0.55)
        if c.face in ("north", "south") and c.hi[1] - c.lo[1] > 4:
            return tone(TR, 0.55 if math.floor(y) % 3 else 0.4)
        return tone(TR, 0.45)
    seat = Paint(TR, 2, noise=0, grad=0, rim=0.5, ao=0.35, decal=leather)
    for sx in (-7, 7):
        for z0, arm in ((-18.5, False), (-6, True)):            # the front seats, the captain chairs
            m.box("cushion", (sx - 3.25, FLOOR, z0), (sx + 3.25, 9, z0 + 6), seat)
            m.box("seat_back", (sx - 3.5, 9, z0 + 6), (sx + 3.5, 23, z0 + 7.5), seat)
            m.box("seat_head", (sx - 2.25, 23, z0 + 6.25), (sx + 2.25, 25.5, z0 + 7.5), seat)
            if arm:
                for e in (-1, 1):
                    m.box("arm_rest", (sx + e * 3.5 - 0.5, 11, z0), (sx + e * 3.5 + 0.5, 12, z0 + 6), seat)
    # the third row: a bench for three
    m.box("bench", (-12, FLOOR, 9), (12, 9, 15), seat)
    m.box("bench_back", (-12, 9, 15), (12, 22, 16.5), seat)
    for hx in (-8, 0, 8):
        m.box("bench_head", (hx - 2, 22, 15.25), (hx + 2, 24.5, 16.5), seat)

    # the dash: a wide screen over it
    def dash(c):
        if c.face == "up":
            return tone("sc_black", 0.3)
        if c.face == "south" and math.floor(c.p[1]) == 14:
            return tone("sc_chrome", 0.7)
        return tone(TR, 0.35) if c.p[1] < 14 else tone("sc_black", 0.25)
    m.box("dash", (-CAB, 11, ZS0), (CAB, BELT - 0.25, -25), Paint(TR, 1, noise=0, grad=0, rim=0.3, ao=0.35, decal=dash))

    def screen(c):
        if c.face != "south":
            return ("raw", (20, 20, 24, 255))
        x, y = c.p[0], c.p[1]
        if x < -1:
            return ("raw", (40, 120, 200, 255)) if math.floor(y) == 17 else ("raw", (24, 40, 64, 255))
        return ("raw", (60, 150, 110, 255)) if (math.floor(x) + math.floor(y)) % 4 == 0 else ("raw", (22, 44, 52, 255))
    m.box("dash_screen", (-11, BELT - 0.25, -26), (5, BELT + 2, -25.5), Paint(TR, 1, noise=0, light=False, glow=True, decal=screen))
    m.box("console", (-1.5, FLOOR, -25), (1.5, 9, -15), Paint(TR, 1, noise=0, rim=0.4, decal=lambda c: tone("sc_black", 0.3)))
    # the steering wheel (driver on the left)
    cx, cy, cz = -7, 16, -23
    rim = Paint("sc_black", 1, noise=0, grad=0, rim=0.6, decal=lambda c: tone("sc_black", 0.45 if c.p[1] > cy else 0.25))
    for x0, y0, x1, y1 in ((-2.25, 1.75, 2.25, 2.25), (-2.25, -2.25, 2.25, -1.75), (-2.25, -1.75, -1.75, 1.75), (1.75, -1.75, 2.25, 1.75)):
        m.box("wheel", (cx + x0, cy + y0, cz), (cx + x1, cy + y1, cz + 0.5), rim)
    m.box("wheel_hub", (cx - 1.75, cy - 0.5, cz + 0.1), (cx + 1.75, cy + 0.5, cz + 0.4), CHROME)
    m.box("column", (cx - 0.4, cy - 0.9, -25), (cx + 0.4, cy - 0.1, cz), BLACK)


def _door_parts(kit, ctx):
    """The mirrors and handles on the front doors, the long handles on the sliding doors."""
    C, TR = ctx["C"], ctx["TR"]
    for s in (-1, 1):
        d = kit["door_l" if s < 0 else "door_r"]
        z = D0 + 0.5

        def mirror(c):
            if c.face == "south":
                if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                    return tone("sc_black", 0.3)
                return tone("sc_chrome", 0.85)
            if c.face == "up":
                return tone(C, 1.0)
            if c.face == "down":
                return tone("sc_black", 0.3)
            if c.face in ("east", "west") and c.p[1] < BELT + 1.5:
                return ("raw", (255, 190, 80, 255))                     # the repeater along its foot
            return tone(C, 0.8)
        d.box("mirror_foot", _lo(s, W - 1, W + 0.5, BELT, z), _hi(s, W - 1, W + 0.5, BELT + 1, z + 2.5), BLACK)
        d.box("mirror", _lo(s, W + 0.25, W + 3, BELT + 0.75, z), _hi(s, W + 0.25, W + 3, BELT + 3.5, z + 2),
              Paint(C, 4, noise=0, grad=0, rim=0.3, ao=0, decal=mirror))
        d.box("handle", _lo(s, W, W + 0.3, 14, D1 - 5), _hi(s, W, W + 0.3, 14.75, D1 - 2), CHROME)
        d.box("arm_rest", _lo(s, CAB - 1, CAB, 10, D0 + 6), _hi(s, CAB - 1, CAB, 11, D1 - 2), Paint(TR, 2, noise=0, rim=0.4))
        sl = kit["slide_l" if s < 0 else "slide_r"]
        sl.box("handle", _lo(s, W, W + 0.3, 14, S0 + 2), _hi(s, W, W + 0.3, 14.75, S0 + 6), CHROME)


def _roof_rack(m, ctx):
    """Two silver roof rails (on a foot at each end), two black cross bars (the roof box is clamped on them)."""
    rail = _chrome(0.95, 0.75, 0.5)
    for s in (-1, 1):
        x0, x1 = 11.5, 12.75
        m.box("rail", _lo(s, x0, x1, ROOF + 0.75, ZS1 + 4), _hi(s, x0, x1, RAIL_Y, RZ1 - 3), rail)
        for z0 in (ZS1 + 3, RZ1 - 5):
            m.box("rail_foot", _lo(s, x0, x1, ROOF, z0), _hi(s, x0, x1, ROOF + 0.75, z0 + 2), BLACK)
    for zb in (-9, 19):
        m.box("cross_bar", (-13, RAIL_Y, zb), (13, BAR_Y, zb + 1.5), BLACK)
        for s in (-1, 1):
            m.box("bar_clamp", _lo(s, 11.25, 13.25, RAIL_Y - 0.5, zb - 0.25), _hi(s, 11.25, 13.25, BAR_Y + 0.25, zb + 1.75), BLACK)


# ================================================================================================
# The roof box
# ================================================================================================

def _box_decal(B):
    """The roof box's paint (world space): a soft gloss line down the crown, two faint ribs, the domed shoulders lit,
    the sides darker towards the bottom, the thin shut line round the base, the lid's lip just over it."""
    def d(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        if f == "down":
            return tone(B, 0.05)
        if f == "up":
            if y > BOX_Y1 - 0.6 and ax < CROWN + 0.1:
                if -4.5 <= x < -2.5:
                    return tone(B, 0.98 if -4 <= x < -3 else 0.82)      # the gloss line
                if math.floor(ax) == 5:
                    return tone(B, 0.62)                                # the ribs' lit side
                if math.floor(ax) == 6:
                    return tone(B, 0.34)                                # their shadow
                return tone(B, 0.5)
            if y > BOX_YS - 0.3:
                return tone(B, 0.68 if x < 0 else 0.56)                 # the shoulders and slopes, lit
            return tone(B, 0.6)
        fy = math.floor(y - BOX_Y0 + 1e-6)
        if math.floor(y) == math.floor(SHUT):
            return tone(B, 0.0)                                         # the shut line
        if math.floor(y) == math.floor(SHUT) + 1:
            return tone(B, 0.78)                                        # the lid's lip catching the light
        if f in ("east", "west"):
            return tone(B, 0.32 + 0.24 * smooth((y - BOX_Y0) / 6))
        return tone(B, 0.36 + 0.26 * smooth((y - BOX_Y0) / 6))
    return d


def _roof_box(m, ctx):
    """A long low aerodynamic roof box on four feet clamped round the cross bars: the core (sides and domed crown),
    the 22.5-degree nose and tail slopes, the 45-degree chin under each end and along the bottom edges, a lock barrel
    on each side."""
    B = ctx["B"]
    dec = _box_decal(B)
    bp = Paint(B, 3, noise=0, grad=0, rim=0, ao=0, decal=dec)
    y0, y1, ys = BOX_Y0, BOX_Y1, BOX_YS
    zc0, zc1 = BOX_ZC0, BOX_ZC1
    # the core: the sides up to the shoulders, the crown over the middle
    m.box("box", (-BX, y0 + 1, zc0), (BX, ys, zc1), bp)
    m.box("box_crown", (-CROWN, ys, zc0), (CROWN, y1, zc1), bp)
    m.box("box_floor", (-(BX - 1), y0, BOX_Z0 + 1.5), (BX - 1, y0 + 1, BOX_Z1 - 1.5), bp)
    for s in (-1, 1):
        # the dome: a 22.5-degree shoulder each side
        plank(m, "box_shoulder", "z", (s * CROWN, y1), (s * BX, ys), (zc0, zc1), 1.1, bp, decal=dec)
        # the bottom's long edges, 45 degrees
        plank(m, "box_edge", "z", (s * (BX - 1), y0), (s * BX, y0 + 1), (BOX_Z0 + 1.5, BOX_Z1 - 1.5), 0.6, bp,
              decal=dec, below=False)
    # the nose: a block under a long 22.5-degree slope (full height in the middle, a shoulder lower at the cheeks)
    nh = y1 - NOSE_DROP                                                   # the slope's foot at the tip
    tip = 2                                                               # the tip's 45-degree corners (plan)
    m.box("box_nose", (-(BX - tip), y0 + 1, BOX_Z0), (BX - tip, nh - (y1 - ys), BOX_Z0 + tip), bp)
    m.box("box_nose", (-BX, y0 + 1, BOX_Z0 + tip), (BX, nh - (y1 - ys), zc0), bp)
    m.box("box_nose", (-CHEEK, nh - (y1 - ys), BOX_Z0), (CHEEK, nh, zc0), bp)
    plank(m, "box_nose_top", "x", (BOX_Z0, nh), (zc0, y1), (-CHEEK, CHEEK), 2.8, bp, decal=dec)
    for s in (-1, 1):
        plank(m, "box_nose_cheek", "x", (BOX_Z0 + tip, nh - (y1 - ys) + tip * T), (zc0, ys), _x(s, CHEEK, BX), 2.8, bp,
              decal=dec)
        plank(m, "box_nose_corner", "y", (BOX_Z0, s * (BX - tip)), (BOX_Z0 + tip, s * BX), (y0 + 1, nh - (y1 - ys) + tip * T),
              0.9, bp, decal=dec, below=s > 0)
    # the chin: 45 degrees from the nose's face back to the floor
    plank(m, "box_chin", "x", (BOX_Z0, y0 + 1.5), (BOX_Z0 + 1.5, y0), (-(BX - 0.5), BX - 0.5), 0.8, bp, decal=dec,
          below=False)
    # the tail: the same, shorter
    th = y1 - TAIL_DROP
    m.box("box_tail", (-BX, y0 + 1, zc1), (BX, th - (y1 - ys), BOX_Z1), bp)
    m.box("box_tail", (-CHEEK, th - (y1 - ys), zc1 + 1.5), (CHEEK, th, BOX_Z1), bp)
    plank(m, "box_tail_top", "x", (zc1, y1), (BOX_Z1, th), (-CHEEK, CHEEK), 1.8, bp, decal=dec)
    for s in (-1, 1):
        plank(m, "box_tail_cheek", "x", (zc1, ys), (BOX_Z1, th - (y1 - ys)), _x(s, CHEEK, BX), 1.8, bp, decal=dec)
    plank(m, "box_tail_chin", "x", (BOX_Z1 - 1.5, y0), (BOX_Z1, y0 + 1.5), (-(BX - 0.5), BX - 0.5), 0.8, bp, decal=dec,
          below=False)

    # a lock barrel each side, just under the shut line near the back: a chrome ring round a dark key slot
    def lock(c):
        if c.face in ("east", "west"):
            r = math.hypot(c.p[1] - (SHUT - 0.75), c.p[2] - 14.75)
            return ("raw", (20, 20, 24, 255)) if r < 0.25 else tone("sc_chrome", 0.8)
        return tone("sc_chrome", 0.6)
    for s in (-1, 1):
        m.box("box_lock", _lo(s, BX, BX + 0.25, SHUT - 1.25, 14.25), _hi(s, BX, BX + 0.25, SHUT - 0.25, 15.25),
              Paint("sc_chrome", 5, noise=0, rim=0.3, decal=lock))
    # the feet: a block under the box on each bar, a clamp hooked round the bar's underside
    foot = Paint("sc_black", 1, noise=0, grad=0, rim=0.3, ao=0.3)
    for zb in (-9, 19):
        for s in (-1, 1):
            m.box("box_foot", _lo(s, 5, 7.5, BAR_Y, zb - 0.5), _hi(s, 5, 7.5, y0, zb + 2), foot)
            m.box("box_clamp", _lo(s, 5.5, 7, RAIL_Y - 0.6, zb - 0.25), _hi(s, 5.5, 7, RAIL_Y, zb + 1.75), foot)


def _part(name):
    return lambda: carnival_kit().all()[name]


# ================================================================================================
# The wheel: 14 px (0.875 block) across, 5 px wide
# ================================================================================================

def wheel_sushi_carnival():
    """A tyre round a bright silver ten-spoke alloy (a dark gap between each pair of spokes), a chrome centre cap."""
    m = BModel("wheel_sushi_carnival", shift=(8, 8, 8), floor=None)
    d, width = 14, 5
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 0.8:
                return tone("sc_black", 0.0)
            if r - 1.8 < rr <= r - 0.8:
                return tone("sc_black", 0.28)
            return tone("sc_black", 0.14)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.4)
        x = c.p[0]
        if abs(x) < 0.5:
            return tone("sc_black", 0.0)
        return tone("sc_black", 0.04 if (k + (x > 0)) % 3 == 0 else 0.2)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("sc_black", 1, noise=0, rim=0.2, decal=tire), axis="x")
    rim_d = 10

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        rc = rim_d / 2
        if c.face not in ("east", "west"):
            return tone("sc_rim", 0.5)
        if rr > rc - 0.7:
            return tone("sc_rim", 0.95 if y > 0 else 0.7)                # the lip
        if rr < 1.1:
            return tone("sc_chrome", 0.85)                               # the centre cap
        if rr < 2.0:
            return tone("sc_rim", 0.55)
        a = math.atan2(z, y)
        t = (a * 10 / (2 * math.pi)) % 1.0
        dpx = abs(t - 0.5) * 2 * math.pi * rr / 10
        if dpx < 0.62:
            return tone("sc_rim", 0.9 if y > 0.5 else 0.68)              # the spokes
        if rr > 2.8 and y > 1.4 and abs(z) < 1.4:
            return tone("sc_black", 0.35)                                # the calliper, dark
        return tone("sc_black", 0.0)
    p = Paint("sc_rim", 3, noise=0, rim=0.2, ao=0.3, decal=face)
    w2 = width / 2
    cyl(m, "hub", (0, 0), rim_d, w2, w2 + 0.25, p, axis="x")
    cyl(m, "hub", (0, 0), rim_d, -w2 - 0.25, -w2, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    "sushi_carnival": sushi_carnival,
    "wheel_sushi_carnival": wheel_sushi_carnival,
    "sushi_carnival_door_l": _part("sushi_carnival_door_l"),
    "sushi_carnival_door_r": _part("sushi_carnival_door_r"),
    "sushi_carnival_slide_l": _part("sushi_carnival_slide_l"),
    "sushi_carnival_slide_r": _part("sushi_carnival_slide_r"),
}
