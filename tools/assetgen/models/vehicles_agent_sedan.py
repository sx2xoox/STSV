"""
검은 세단 · 요원 K (content/stsv/items/vehicles.yml agent_sedan): the unique reward of the 마을버스 secret mission (the
"수상한 선글라스 남자" easter egg). Never sold: the bus module hands it out once, bound to the player. A long, low,
black executive sedan, an original drawing (no make, no badge, no lettering): matte black paint, near-black tinted
glass all round, a little chrome (the grille's slats and frame, a thin line round the windows, the handles), slim
white LED eyes, one thin red bar across the tail, a whip antenna on the boot lid, and a thin blue LED line low along
both sills (the stock "underglow look"; the tuning shop's real underglow, underglows: all, can be added on top).
About 5.25 x 2.0 x 1.55 blocks; five seats (the driver and four guests: it carries bus passengers like a 소형 버스).

Drawn like the 2026-11 donation cars (vehicles_donation_sushi_carnival.py / concept_ev.py: the approved "16 px,
angled panels" style; read their docs first): 16 px a block, the body drawn at its real size and shrunk by 2 (Kit
scale 2), shown at vehicle.body-scale 2; the wheel a model of its own at 1. The body is a handful of big clean boxes;
every slope is a flat panel turned 22.5 or 45 degrees about one axis (plank()): the nose's brow, the screen (45 then
22.5 degrees) and its pillars, the rear window (22.5 then 45), the shoulder's crease, the roof's chamfered edges, the
four vertical corners and the arches' 45 degree corners. The detail is painted on the big faces (world-space decals):
the light across each plane, the crease's line, shut lines, the grille's mesh, the lamps' insides, the glass's
reflections. Only what should stand out is real 3D: the lamps, the grille's chrome frame and slats, mirrors, handles,
the antenna, the blue sill line, the wheels.

Drawn coordinates: 16 px a block, the car's ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT side;
the driver sits on the left (-x).

Paint arguments: colour (the body: matte black "ag_black"), trim (the cabin: black leather), accent (the blue: the
sill line, the stitching, the brake callipers).

Its parts: door_fl, door_fr, door_rl, door_rr (four doors, each hinged at its front edge: they swing out about a
vertical axis, vehicles.yml movers): each the side between its seams with its glass, chrome window line and handle;
the front ones carry the mirrors.
"""

import math
from types import SimpleNamespace

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp, ladder
from stsv_assets import presets
from models import vehicles as _vehicles  # noqa: F401 (vehicles_premium is imported through it)
from models.vehicles_premium import Kit


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("ag_black", 262, 0.010, 0.36, 0.26, 8, False)    # matte black paint (lit planes lift it, shade drops it)
_ramp("ag_trim", 260, 0.008, 0.27, 0.06, 4, False)     # gloss black trim: pillars, grille, cladding
_ramp("ag_chrome", 232, 0.012, 0.74, 0.56, 8, False)   # the little chrome there is
_ramp("ag_leather", 40, 0.010, 0.31, 0.16, 5, False)   # black leather cabin
_ramp("ag_blue", 258, 0.170, 0.62, 0.36, 5, True)      # the blue accent (sill line, stitching, callipers)
_ramp("ag_led", 215, 0.040, 0.93, 0.10, 3, True)       # white LEDs
_ramp("ag_tail", 22, 0.230, 0.50, 0.34, 5, True)       # the tail bar
_ramp("ag_rim", 255, 0.012, 0.44, 0.40, 6, False)      # gunmetal wheels


def _n(ramp):
    """Steps in a ramp (followed through palette.remapped, so a tuning paint keeps its lights)."""
    spec = MATERIALS.get(REMAP.get(ramp, ramp))
    return spec[4] if spec else 5


def tone(ramp, t):
    """(ramp, step) at lightness t: 0 the ramp's darkest, 1 its lightest."""
    return (ramp, max(0.0, min(1.0, t)) * (_n(ramp) - 1))


def raw(ramp, t, a=255):
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
NOSE, TAIL = -42, 42           # z of the nose's face and the tail's
W = 16                         # the body's half width
GROUND, FLOOR = 2.5, 3         # the body's bottom, the cabin floor
SILL = 5                       # the sills' top (the doors stand on them)
SIDE, BELT = 15.5, 16.5        # the flank's top (a 45 degree crease from here up to BELT): the hood, the deck, the belt
GLASS_TOP, ROOF = 24, 25       # the side glass's top, the roof's top
CAB = 13                       # the doors' inner faces; the side glass from CAB to CAB + 0.5
WF, WR = -26, 25               # front / rear axle (z)
WY, WRAD = 7, 7                # wheel centre height, its radius (a 0.875 block wheel)
ARCH, ARCH_TOP, CHAMF = 8.5, 14.5, 3.0
TRACK = 10.5                   # the wheels' inner faces (x): a 5 px wheel from 10.75 to 15.75 (its middle at 13.25)
BROW = 2.5                     # the nose's 45 degree brow: from (NOSE, BELT - BROW) to (NOSE + BROW, BELT)
ZS0 = -19                      # the screen's foot (at BELT)
KNEE = ZS0 + 5                 # where the screen bends from 45 to 22.5 degrees
ZR0 = KNEE + (ROOF - (BELT + 5)) / T          # the screen's top (the roof's front edge)
ZR1 = 13                       # the roof's back edge (the rear window falls from here)
RKNEE = ZR1 + (ROOF - (BELT + 5)) / T         # where the rear window bends from 22.5 to 45 degrees
ZD = RKNEE + 5                 # the rear window's foot on the deck (at BELT)
D0, D1 = WF + ARCH, -1         # the front door (z): from the front arch to the B pillar
R0, R1 = -1, WR - ARCH - 1     # the rear door: from the B pillar to just before the rear arch
GRILLE_X, GRILLE_Y0, GRILLE_Y1 = 8.5, 6, 12.5
LAMP = (9.5, 15.25, 11.5, 13.0)    # the slim headlamp: |x| from, to; y from, to (its face at NOSE - 0.4)
TAIL_Y = (13.25, 14.25)            # the tail bar (y), the lamps at the corners a little taller
SEAT_F, SEAT_R = -4, 10.5          # the seats' middles (z): front row, rear bench
SEAT_X = 4.8                       # the front seats' middles (|x|: vehicles.yml seats at 0.3)


def screen_y(z):
    """The screen's surface (y) at z (45 degrees to the knee, then 22.5 to the roof)."""
    if z < KNEE:
        return BELT + (z - ZS0)
    return min(ROOF, BELT + 5 + (z - KNEE) * T)


def rear_y(z):
    """The rear window's surface (y) at z (22.5 degrees from the roof to its knee, then 45 to the deck)."""
    if z < RKNEE:
        return min(ROOF, ROOF - (z - ZR1) * T)
    return max(BELT, BELT + 5 - (z - RKNEE))


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


# ================================================================================================
# Angled panels (as the donation cars')
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
    The slope must be a multiple of 22.5 degrees (at most 45). decal: a world-space decal."""
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


def _inner(c):
    """A side face towards the cabin."""
    return (c.face == "east" and c.p[0] < 0) or (c.face == "west" and c.p[0] > 0)


# ================================================================================================
# Paints
# ================================================================================================

def _make_paints(C, TR, A):
    """The sedan's paints for body colour C, cabin trim TR, accent A."""
    seams = (D0, D1 - 1, R1 - 1)

    def side_colour(x, y, z):
        """The flanks: matte black with a soft gradient, the crease's light, a sculpted line, seams, the arch lips."""
        fz, fy = math.floor(z), math.floor(y)
        if y < SILL - 0.5 and arch_at(z) is None:
            return tone(C, 0.22 if y < GROUND + 1 else 0.32)              # the sill, its foot in shadow
        wz = arch_at(z)
        if wz is not None and not in_arch(z, y):
            if any(in_arch(z + dz, y + dy) for dz, dy in ((0, -1), (-1, 0), (1, 0), (-1, -1), (1, -1))):
                return tone("ag_trim", 0.2)                               # the arch's lip, gloss black
        if fz in seams and SILL - 0.5 < y < SIDE and (fz != D0 or y > ARCH_TOP):
            return tone(C, 0.02)                                          # the door seams
        if fy == SIDE - 1:
            return tone(C, 0.78)                                          # light under the crease
        if fy == SIDE - 2:
            return tone(C, 0.3)
        # the sculpted line low on the doors: a lit edge over a shadow, rising gently towards the back
        line = 8.5 + (z - D0) * 0.02
        if WF + ARCH + 1 <= z < WR - ARCH - 1 and math.floor(y - line) == 0:
            return tone(C, 0.7)
        if WF + ARCH + 1 <= z < WR - ARCH - 1 and math.floor(y - line) == -1:
            return tone(C, 0.12)
        return tone(C, 0.2 + 0.26 * smooth((y - 4) / 11))

    def top_colour(x, y, z, ax):
        """Hood, deck, roof: matte black lit from above, a soft sky band down each, shut lines."""
        if y > ROOF - 1:
            if 3 <= ax < 6:
                return tone(C, 0.74)                                      # the sky's soft band down the roof
            return tone(C, 0.6)
        if ZS0 - 1 <= z < ZS0 and ax < CAB:
            return tone("ag_trim", 0.1)                                   # the cowl
        if z < ZS0 and math.floor(ax) == 12:
            return tone(C, 0.3)                                           # the hood's shut lines
        if z > ZD and math.floor(ax) == 12 and z < TAIL - 1:
            return tone(C, 0.3)                                           # the boot lid's
        if z > ZD and math.floor(z) == math.floor(ZD) + 1 and ax < 12:
            return tone(C, 0.3)
        if 3 <= ax < 6:
            return tone(C, 0.72)
        if ax < 1 and z < ZS0:
            return tone(C, 0.66)                                          # the hood's centre line, catching light
        return tone(C, 0.56)

    def body_decal(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        if f == "down":
            if y > GLASS_TOP:
                return tone(TR, 0.6)                                      # the headliner
            return tone("ag_trim", 0.0)
        if arch_at(z) is not None and y < ARCH_TOP + 0.01 and f in ("east", "west", "north", "south"):
            if ax < W - 0.5 or in_arch(z, y) or f in ("north", "south"):
                return tone("ag_trim", 0.0)                               # the wheel wells
        cabin = ZS0 - 0.1 < z < ZD and ax < CAB + 0.1
        if f == "up":
            if cabin and y < BELT - 0.5:
                return tone(TR, 0.1 if math.floor(z) % 3 else 0.0)        # carpet
            return top_colour(x, y, z, ax)
        if f in ("east", "west"):
            if _inner(c) and ax < CAB + 0.6:
                # the door cards: leather, a blue stitch line, a dark top
                if math.floor(y) == 11:
                    return tone(A, 0.45)
                if y > 13:
                    return tone("ag_trim", 0.2)
                return tone(TR, 0.45 if math.floor(y) % 3 else 0.3)
            if _inner(c):
                return tone("ag_trim", 0.05)
            return side_colour(x, y, z)
        if f == "north":
            if cabin and y < BELT:
                return tone("ag_trim", 0.15)
            return tone(C, 0.3 + 0.2 * smooth((y - 4) / 12))
        if f == "south":
            if cabin and y < BELT:
                return tone(TR, 0.3)
            return tone(C, 0.26 + 0.18 * smooth((y - 4) / 12))
        return None

    def chamfer_decal(c):
        """The shoulder's 45 degree crease: one clean bright line, the door seams across it."""
        if c.face != "up":
            return body_decal(c)
        if math.floor(c.p[2]) in seams:
            return tone(C, 0.1)
        return tone(C, 0.9)

    def bright(c):
        if c.face == "up":
            return tone(C, 0.8)
        return body_decal(c)

    flat = dict(noise=0, grad=0, rim=0, ao=0)
    return dict(
        body=Paint(C, 4, **flat, decal=body_decal),
        angled=Paint(C, 4, **flat),
        body_decal=body_decal, chamfer_decal=chamfer_decal, bright=bright,
    )


def _chrome(lit=0.95, side=0.72, low=0.40):
    """A chrome part: bright on top, a sky band over a dark ground reflection down its sides."""
    def decal(c):
        if c.face == "up":
            return tone("ag_chrome", lit)
        if c.face == "down":
            return tone("ag_chrome", 0.15)
        h = c.hi[1] - c.lo[1]
        if h >= 1.5:
            return tone("ag_chrome", side if c.p[1] > (c.lo[1] + c.hi[1]) / 2 else low)
        return tone("ag_chrome", side)
    return Paint("ag_chrome", 5, noise=0, grad=0, rim=0.4, ao=0.2, decal=decal)


CHROME = _chrome()
GLOSS = Paint("ag_trim", 1, noise=0, grad=0, rim=0.35, ao=0.4,
              decal=lambda c: tone("ag_trim", 0.85) if c.face == "up" else tone("ag_trim", 0.0) if c.face == "down" else tone("ag_trim", 0.35))


def _glass(frame, inside=96):
    """The near-black tinted glass, painted in world space: frame(c) -> None (glass) or a colour (pillars, seals).
    Seen from outside it is almost opaque (a band of sky across its top, a faint horizon line); from inside lighter,
    so the riders still see out."""
    def d(c):
        x, y, z = c.p
        got = frame(c)
        if got is not None:
            return got if got[0] == "raw" else raw(*got)
        if _inner(c):
            return ("raw", (16, 18, 24, inside))
        if 21 <= y < 21.75:
            return ("raw", (96, 106, 128, 236))                           # the horizon's reflection: one clean line
        if y >= 21.75:
            return ("raw", (40, 46, 60, 236))                             # the sky over it
        return ("raw", (14, 16, 22, 236))
    return d


# ================================================================================================
# The car
# ================================================================================================

_KIT = {}
PARTS = ("door_fl", "door_fr", "door_rl", "door_rr")


def agent_sedan(colour="ag_black", trim="ag_leather", accent="ag_blue", name="agent_sedan"):
    return sedan_kit(colour, trim, accent, name).body


def sedan_kit(colour="ag_black", trim="ag_leather", accent="ag_blue", name="agent_sedan"):
    key = (colour, trim, accent, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, PARTS)
    m = kit.body
    m.group("body")
    P = _make_paints(colour, trim, accent)
    ctx = dict(C=colour, TR=trim, A=accent, P=P)
    _shell(m, kit, ctx)
    _front(m, ctx)
    _rear(m, ctx)
    _glasshouse(m, kit, ctx)
    _cabin(m, ctx)
    _door_parts(kit, ctx)
    _sills(m, ctx)
    m.display = _held_display()
    _KIT[key] = kit
    return kit


def _held_display():
    d = presets.furniture(gui_scale=0.34)
    for k in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        d[k] = dict(d[k])
        d[k]["scale"] = [round(v / 2.5, 3) for v in d[k]["scale"]]
    return d


def _door(kit, s, front):
    return kit[("door_fl" if s < 0 else "door_fr") if front else ("door_rl" if s < 0 else "door_rr")]


def _shell(m, kit, ctx):
    """The body's boxes: the nose and the engine bay under the hood, the floor and the wells, the flanks round the
    arches, the four doors, the boot under the deck; the crease along the shoulders, the nose's brow, the four
    bevelled corners, the arches' corners."""
    P = ctx["P"]
    body, ang, bd = P["body"], P["angled"], P["body_decal"]
    box = m.box
    # --- the nose (behind the fascia), the engine bay between the front wheels, the hood over them
    box("nose", (-CAB, GROUND, NOSE + 1), (CAB, BELT - BROW, WF - ARCH), body)
    box("bay", (-(TRACK - 1), GROUND, WF - ARCH), (TRACK - 1, SIDE, ZS0), body)
    box("hood", (-(W - 1), SIDE, NOSE + BROW), (W - 1, BELT, ZS0), body)
    box("nose_top", (-CAB, BELT - BROW, NOSE + BROW), (CAB, SIDE, WF - ARCH), body)
    # --- the cabin's floor, the boot under the deck
    box("floor", (-(TRACK - 1), GROUND, ZS0), (TRACK - 1, FLOOR, TAIL - 1), body)
    box("boot", (-(TRACK - 1), FLOOR, 17), (TRACK - 1, BELT, TAIL - 1), body)
    for s in (-1, 1):
        box("floor", _lo(s, TRACK - 1, CAB, GROUND, WF + ARCH), _hi(s, TRACK - 1, CAB, FLOOR, WR - ARCH), body)
        box("boot_side", _lo(s, TRACK - 1, CAB, ARCH_TOP, 17), _hi(s, TRACK - 1, CAB, BELT, TAIL - 1), body)
        # the wheel wells' inner walls and roofs (the cabin must not show through the arches)
        for wz in (WF, WR):
            box("well", _lo(s, TRACK - 1, TRACK - 0.5, GROUND, wz - ARCH), _hi(s, TRACK - 1, TRACK - 0.5, ARCH_TOP, wz + ARCH), body)
            box("well_top", _lo(s, TRACK - 0.5, CAB, ARCH_TOP - 0.5, wz - ARCH), _hi(s, TRACK - 0.5, CAB, ARCH_TOP, wz + ARCH), body)
            box("well_end", _lo(s, TRACK - 0.5, CAB, GROUND, wz - ARCH), _hi(s, TRACK - 0.5, CAB, ARCH_TOP - CHAMF, wz - ARCH + 0.5), body)
            box("well_end", _lo(s, TRACK - 0.5, CAB, GROUND, wz + ARCH - 0.5), _hi(s, TRACK - 0.5, CAB, ARCH_TOP - CHAMF, wz + ARCH), body)
    # --- the flanks
    for s in (-1, 1):
        fd, rd = _door(kit, s, True), _door(kit, s, False)
        box("fender", _lo(s, CAB, W, GROUND, NOSE + 1), _hi(s, CAB, W, SIDE, WF - ARCH), body)
        box("arch_top", _lo(s, CAB, W, ARCH_TOP, WF - ARCH), _hi(s, CAB, W, SIDE, WF + ARCH), body)
        box("sill", _lo(s, CAB, W, GROUND, WF + ARCH), _hi(s, CAB, W, SILL, WR - ARCH), body)
        fd.box("door", _lo(s, CAB, W, SILL, D0), _hi(s, CAB, W, SIDE, D1), body)
        rd.box("door", _lo(s, CAB, W, SILL, R0), _hi(s, CAB, W, SIDE, R1), body)
        box("quarter", _lo(s, CAB, W, SILL, R1), _hi(s, CAB, W, SIDE, WR - ARCH), body)
        box("arch_top", _lo(s, CAB, W, ARCH_TOP, WR - ARCH), _hi(s, CAB, W, SIDE, WR + ARCH), body)
        box("rear_side", _lo(s, CAB, W, GROUND, WR + ARCH), _hi(s, CAB, W, SIDE, TAIL - 1), body)
        # the arches' 45 degree corners (filled in steps a hair inside the flank, a slim panel along the edge)
        lip = Paint("ag_trim", 1, noise=0, grad=0, rim=0, ao=0,
                    decal=lambda c: tone("ag_trim", 0.0) if c.face == "down" else tone("ag_trim", 0.25))
        for wz in (WF, WR):
            for e in (-1, 1):
                z0 = wz + e * ARCH
                z1 = wz + e * (ARCH - CHAMF)
                for k in range(int(CHAMF) - 1):
                    za, zb = sorted((z0 - e * k, z0 - e * (k + 1)))
                    box("arch_step", _lo(s, W - 2, W - 0.05, ARCH_TOP - CHAMF + k + 1, za),
                        _hi(s, W - 2, W - 0.05, ARCH_TOP, zb), body)
                plank(m, "arch_corner", "x", (z0, ARCH_TOP - CHAMF), (z1, ARCH_TOP), _x(s, CAB, W + 0.02),
                      0.75, lip, below=False)
    # --- the shoulder (the glass stands on it) and its 45 degree crease, split at the doors' seams
    cd = P["chamfer_decal"]
    for s in (-1, 1):
        fd, rd = _door(kit, s, True), _door(kit, s, False)
        for target, z0, z1 in ((m, NOSE + BROW, D0), (fd, D0, D1), (rd, R0, R1), (m, R1, TAIL - 1)):
            plank(target, "crease", "z", (s * (W - 1), BELT), (s * W, SIDE), (z0, z1), 0.75, ang, decal=cd)
        for target, z0, z1 in ((m, ZS0, D0), (fd, D0, D1), (rd, R0, R1), (m, R1, TAIL - 1)):
            target.box("shoulder", _lo(s, CAB, W - 1, SIDE, z0), _hi(s, CAB, W - 1, BELT, z1), body)
    # the nose's brow: a 45 degree bevel along the front of the hood
    plank(m, "brow", "x", (NOSE, BELT - BROW), (NOSE + BROW, BELT), (-W + 0.02, W - 0.02), 1.8, ang, decal=P["bright"])
    # the four vertical corners: a 45 degree bevel each (1 px)
    for s in (-1, 1):
        plank(m, "corner_f", "y", (NOSE, s * (W - 1)), (NOSE + 1, s * W), (GROUND, BELT - BROW), 0.75, ang, decal=bd,
              below=s > 0)
        plank(m, "corner_r", "y", (TAIL - 1, s * W), (TAIL, s * (W - 1)), (GROUND, SIDE), 0.75, ang, decal=bd,
              below=s > 0)


def _front(m, ctx):
    """The fascia round a big upright grille (gloss black mesh, chrome slats in a thick chrome frame), the slim LED
    eyes on the corners, a black lower intake with a thin chrome lip."""
    C = ctx["C"]
    bd = ctx["P"]["body_decal"]

    def fascia(c):
        x, y, z = c.p
        if c.face != "north":
            return bd(c)
        if y < GROUND + 2.5:
            return tone("ag_trim", 0.15)
        return tone(C, 0.34 + 0.2 * smooth((y - 4) / 10))
    fp = Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=fascia)
    m.box("fascia", (-(W - 1), GROUND, NOSE), (W - 1, GRILLE_Y0 - 0.5, NOSE + 1), fp)
    m.box("fascia", (-(W - 1), GRILLE_Y1 + 0.5, NOSE), (W - 1, BELT - BROW, NOSE + 1), fp)
    for s in (-1, 1):
        m.box("fascia", _lo(s, GRILLE_X + 0.5, W - 1, GRILLE_Y0 - 0.5, NOSE), _hi(s, GRILLE_X + 0.5, W - 1, GRILLE_Y1 + 0.5, NOSE + 1), fp)

    # the grille: a gloss black mesh, set 1 px in
    def mesh(c):
        x, y, z = c.p
        if c.face != "north":
            return tone("ag_trim", 0.0)
        if (math.floor(x) + math.floor(y)) % 2 == 0:
            return tone("ag_trim", 0.45)
        return tone("ag_trim", 0.0)
    m.box("grille", (-GRILLE_X - 0.5, GRILLE_Y0 - 0.5, NOSE + 0.75), (GRILLE_X + 0.5, GRILLE_Y1 + 0.5, NOSE + 1),
          Paint("ag_trim", 1, noise=0, grad=0, rim=0, ao=0, decal=mesh))
    # its thick chrome frame, proud of the fascia, and four thin chrome slats across it
    fr = _chrome(0.86, 0.62, 0.42)
    zo = NOSE - 0.6
    m.box("grille_frame", (-GRILLE_X - 1, GRILLE_Y1, zo), (GRILLE_X + 1, GRILLE_Y1 + 1, NOSE), fr)
    m.box("grille_frame", (-GRILLE_X - 1, GRILLE_Y0 - 1, zo), (GRILLE_X + 1, GRILLE_Y0, NOSE), fr)
    for s in (-1, 1):
        m.box("grille_frame", _lo(s, GRILLE_X, GRILLE_X + 1, GRILLE_Y0, zo), _hi(s, GRILLE_X, GRILLE_X + 1, GRILLE_Y1, NOSE), fr)
    slat = Paint("ag_trim", 1, noise=0, grad=0, rim=0.3, ao=0,
                 decal=lambda c: tone("ag_chrome", 0.62) if c.face == "up" else tone("ag_trim", 0.55) if c.face == "north" else tone("ag_trim", 0.1))
    for y in (7.25, 8.75, 10.25, 11.75):
        m.box("grille_slat", (-GRILLE_X, y - 0.25, NOSE + 0.1), (GRILLE_X, y + 0.25, NOSE + 0.75), slat)
    # the slim LED eyes on the upper corners: a hot line along their top, projector cells under it
    m.group("lights")
    x0, x1, y0, y1 = LAMP

    def lamp(c):
        if c.face != "north":
            return ("raw", (30, 32, 40, 255))
        ax, y = abs(c.p[0]), c.p[1]
        if y >= y1 - 0.5:
            return ("raw", (226, 244, 255, 255))                         # the LED line
        if ax < x0 + 0.75 or ax > x1 - 0.75:
            return ("raw", (36, 40, 50, 255))
        k = math.floor(ax - x0 - 0.75)
        return ("raw", (255, 252, 240, 255)) if k % 2 == 0 else ("raw", (120, 132, 156, 255))
    for s in (-1, 1):
        m.box("headlamp", _lo(s, x0, x1, y0, NOSE - 0.4), _hi(s, x0, x1, y1, NOSE),
              Paint("ag_led", 2, noise=0, light=False, glow=True, decal=lamp))
    m.group("body")
    # the black lower intake across the bumper, a thin chrome lip under it
    def valance(c):
        x, y, z = c.p
        if c.face == "north" and 3.25 <= y < 4.75 and abs(x) < 11:
            return tone("ag_trim", 0.0) if math.floor(x) % 2 else tone("ag_trim", 0.4)   # the intake's mesh
        return tone("ag_trim", 0.25 if c.face != "up" else 0.45)
    m.box("valance", (-(W - 0.5), GROUND - 0.25, NOSE - 0.75), (W - 0.5, GRILLE_Y0 - 1.5, NOSE + 1),
          Paint("ag_trim", 1, noise=0, grad=0, rim=0.3, decal=valance))
    m.box("lip", (-11, GROUND - 0.5, NOSE - 1), (11, GROUND, NOSE + 0.5), GLOSS)
    # wipers at the screen's foot
    wip = Paint("ag_trim", 0, noise=0, rim=0.4)
    for xa, xb in ((-12, -1.5), (1.5, 11)):
        m.box("wiper", (xa, BELT, ZS0 - 0.9), (xb, BELT + 0.4, ZS0 - 0.4), wip)


def _rear(m, ctx):
    """The tail: a flat panel with one thin red bar across it (the lamps a little taller at the corners), the plate's
    recess, a black diffuser with two slim chrome tips, a small ducktail lip on the boot lid, the whip antenna."""
    C = ctx["C"]
    bd = ctx["P"]["body_decal"]

    def panel(c):
        x, y, z = c.p
        ax = abs(x)
        if c.face != "south":
            return bd(c)
        if 7.5 <= y < 11 and ax < 5:
            return tone("ag_trim", 0.2)                                   # the plate's recess
        if math.floor(y) == 12 and ax < 9:
            return tone("ag_chrome", 0.8)                                 # a thin chrome line over the plate
        if math.floor(ax) == 12 and y > 11:
            return tone(C, 0.08)                                          # the boot lid's shut lines
        return tone(C, 0.26 + 0.2 * smooth((y - 4) / 12))
    m.box("tail", (-(W - 1), GROUND, TAIL - 1), (W - 1, BELT, TAIL), Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=panel))
    m.group("lights")

    def bar(c):
        if c.face != "south":
            return ("raw", (90, 14, 20, 255))
        ax, y = abs(c.p[0]), c.p[1]
        if ax > 11.5:
            return ("raw", (255, 70, 72, 255)) if (y < 13 or y >= 14.5 or ax > 14.5) else ("raw", (176, 20, 32, 255))
        return ("raw", (232, 40, 50, 255)) if math.floor(ax) % 4 else ("raw", (190, 24, 36, 255))
    tp = Paint("ag_tail", 2, noise=0, light=False, glow=True, decal=bar)
    m.box("tail_bar", (-11.5, TAIL_Y[0], TAIL), (11.5, TAIL_Y[1], TAIL + 0.3), tp)
    for s in (-1, 1):
        m.box("tail_lamp", _lo(s, 11.5, 15.25, 12.5, TAIL), _hi(s, 11.5, 15.25, 15, TAIL + 0.4), tp)
    m.group("body")
    # the diffuser and the exhaust tips
    m.box("diffuser", (-(W - 0.5), GROUND - 0.25, TAIL - 0.75), (W - 0.5, GROUND + 3, TAIL + 0.75),
          Paint("ag_trim", 1, noise=0, grad=0, rim=0.3,
                decal=lambda c: tone("ag_trim", 0.5) if c.face == "up" else tone("ag_trim", 0.2)))

    def tip(c):
        if c.face == "south":
            return ("raw", (12, 12, 16, 255)) if abs(c.p[1] - 3.75) < 0.5 and abs(abs(c.p[0]) - 9.75) < 1.0 else tone("ag_chrome", 0.9)
        return tone("ag_chrome", 0.7)
    for s in (-1, 1):
        m.box("exhaust", _lo(s, 8.25, 11.25, 3, TAIL), _hi(s, 8.25, 11.25, 4.5, TAIL + 1),
              Paint("ag_chrome", 5, noise=0, rim=0.3, decal=tip))
    # the ducktail: a slim lip along the boot lid's back edge
    lip = Paint(C, 4, noise=0, grad=0, rim=0.3, ao=0,
                decal=lambda c: tone(C, 0.8) if c.face == "up" else tone(C, 0.3) if c.face != "down" else tone("ag_trim", 0.0))
    m.box("ducktail", (-(W - 1.5), BELT, TAIL - 2), (W - 1.5, BELT + 0.6, TAIL + 0.2), lip)
    # the whip antenna on the boot lid (left), on a small chrome foot: the agents' radio
    m.box("antenna_foot", (-9, BELT, 34), (-8, BELT + 0.6, 35), CHROME)
    m.box("antenna", (-8.75, BELT + 0.6, 34.25), (-8.25, BELT + 14, 34.75),
          Paint("ag_trim", 0, noise=0, rim=0.5, decal=lambda c: tone("ag_trim", 0.6 if c.p[1] > 29 else 0.25)))
    m.box("antenna_tip", (-9, BELT + 14, 34), (-8, BELT + 14.75, 35), GLOSS)


def _glasshouse(m, kit, ctx):
    """The screen (45 then 22.5 degrees) between black pillars, the roof with chamfered edges, the rear window (22.5
    then 45), the side glass (front and rear doors, a small quarter light) in black frames with a thin chrome line
    round the top, the body-coloured C pillars."""
    P = ctx["P"]
    C = ctx["C"]
    ang = P["angled"]

    def pane(c):
        """The screen and the rear window: black glass, black edges, one line of sky."""
        if c.v in (0, c.nv - 1) or c.u in (0, c.nu - 1):
            return ("raw", (20, 22, 28, 255))
        if c.face in ("down",):
            return ("raw", (20, 24, 32, 96))                              # from inside: tinted, seen through
        y = c.p[1]
        if 21.5 <= y < 22.25:
            return ("raw", (104, 116, 140, 236))                          # one clean band of sky across it
        if y >= 22.25:
            return ("raw", (36, 42, 56, 236))
        return ("raw", (20, 24, 32, 236))
    gl = Paint("ag_trim", 1, noise=0, light=False)
    yk = screen_y(KNEE)
    plank(m, "screen", "x", (ZS0 + 0.25, BELT), (KNEE + 0.25, yk), (-(CAB - 0.5), CAB - 0.5), 0.5, gl, decal=pane)
    plank(m, "screen", "x", (KNEE + 0.25, yk), (ZR0 + 0.25, ROOF), (-(CAB - 0.5), CAB - 0.5), 0.5, gl, decal=pane)
    yr = rear_y(RKNEE)
    plank(m, "rear_glass", "x", (ZR1 - 0.25, ROOF), (RKNEE - 0.25, yr), (-(CAB - 0.5), CAB - 0.5), 0.5, gl, decal=pane)
    plank(m, "rear_glass", "x", (RKNEE - 0.25, yr), (ZD - 0.25, BELT), (-(CAB - 0.5), CAB - 0.5), 0.5, gl, decal=pane)
    # the A pillars, gloss black, a hair over the screen's edges
    pil = Paint("ag_trim", 1, noise=0, grad=0, rim=0.4, ao=0,
                decal=lambda c: tone("ag_trim", 0.7) if c.face == "up" else tone("ag_trim", 0.2))
    for s in (-1, 1):
        plank(m, "a_pillar", "x", (ZS0, BELT), (KNEE, yk), _x(s, CAB - 0.75, CAB + 0.52), 0.9, pil)
        plank(m, "a_pillar", "x", (KNEE, yk), (ZR0, ROOF), _x(s, CAB - 0.75, CAB + 0.52), 0.9, pil)
        # the C pillars: body colour, following the rear window's edge
        cpil = Paint(C, 4, noise=0, grad=0, rim=0.3, ao=0,
                     decal=lambda c: tone(C, 0.62) if c.face == "up" else tone(C, 0.3))
        plank(m, "c_pillar", "x", (ZR1, ROOF), (RKNEE, yr), _x(s, CAB - 1, CAB + 0.52), 0.9, cpil)
        plank(m, "c_pillar", "x", (RKNEE, yr), (ZD, BELT), _x(s, CAB - 1, CAB + 0.52), 0.9, cpil)
    # the roof, its long edges chamfered at 45 degrees
    roofp = Paint(C, 4, noise=0, grad=0, rim=0.3, ao=0, decal=P["body_decal"])
    m.box("roof", (-(CAB - 1), GLASS_TOP, ZR0), (CAB - 1, ROOF, ZR1), roofp)
    for s in (-1, 1):
        plank(m, "roof_edge", "z", (s * (CAB - 1), ROOF), (s * CAB, GLASS_TOP), (ZR0 - 0.3, ZR1 + 0.3), 0.75, ang,
              decal=lambda c: tone(C, 0.8) if c.face == "up" else tone(C, 0.4))

    def frame(c):
        x, y, z = c.p
        if y > screen_y(z) - 0.6 or y > rear_y(z) - 0.6:
            return CLEAR                                                  # above the pillars' lines (their planks
                                                                          # cover the glass's stepped edge)
        if y < BELT + 0.5 or y > GLASS_TOP - 0.5 or y > screen_y(z) - 1.3 or y > rear_y(z) - 1.6:
            return ("ag_trim", 0.15)                                      # the seals, the A pillar, the C pillar's edge
        if D1 - 0.75 <= z < R0 + 0.75:
            return ("ag_trim", 0.2)                                       # the B pillar
        if R1 - 0.5 <= z < R1 + 0.75:
            return ("ag_trim", 0.2)                                       # the quarter light's front post
        if z >= R1 + 3.5:
            return ("ag_black", 0.3) if not _inner(c) else ("ag_trim", 0.1)   # the C pillar's flank (body colour)
        return None
    gp = Paint("ag_trim", 1, noise=0, light=False, decal=_glass(frame))
    for s in (-1, 1):
        lo, hi = _x(s, CAB, CAB + 0.5)
        fd, rd = _door(kit, s, True), _door(kit, s, False)
        m.box("quarter_glass", (lo, BELT, ZS0 + 0.25), (hi, GLASS_TOP, D0), gp)
        fd.box("side_glass", (lo, BELT, D0), (hi, GLASS_TOP, D1), gp)
        rd.box("side_glass", (lo, BELT, R0), (hi, GLASS_TOP, R1), gp)
        m.box("quarter_glass", (lo, BELT, R1), (hi, GLASS_TOP, ZD), gp)
    # the thin chrome line round the windows: along the belt and over the glass, split at the doors
    trim = _chrome(0.98, 0.85, 0.6)
    top0 = KNEE + (GLASS_TOP - 0.5 - (BELT + 5)) / T                     # where the screen's line reaches the glass top
    top1 = ZR1 + (ROOF - (GLASS_TOP - 0.5)) / T                         # where the rear window's line leaves it
    for s in (-1, 1):
        lo, hi = CAB + 0.5, CAB + 0.75
        fd, rd = _door(kit, s, True), _door(kit, s, False)
        for target, z0, z1 in ((m, ZS0 + 0.5, D0), (fd, D0, D1), (rd, R0, R1), (m, R1, ZD - 0.5)):
            target.box("belt_trim", _lo(s, lo, hi, BELT, z0), _hi(s, lo, hi, BELT + 0.35, z1), trim)
        for target, z0, z1 in ((fd, top0, D1), (rd, R0, min(R1, top1)), (m, R1, top1)):
            if z1 - z0 > 0.2:
                target.box("top_trim", _lo(s, lo, hi, GLASS_TOP - 0.5, z0), _hi(s, lo, hi, GLASS_TOP - 0.15, z1), trim)


def _cabin(m, ctx):
    """Five seats (two front buckets, a bench for three), the dash with a slim screen, the wheel, the console."""
    TR, A = ctx["TR"], ctx["A"]

    def leather(c):
        x, y, z = c.p
        if c.face == "up":
            return tone(TR, 0.7 if math.floor(z) % 3 else 0.5)
        if c.face in ("north", "south") and c.hi[1] - c.lo[1] > 4:
            if math.floor(y) % 4 == 0:
                return tone(A, 0.35)                                      # blue stitching across the backs
            return tone(TR, 0.5)
        return tone(TR, 0.4)
    seat = Paint(TR, 2, noise=0, grad=0, rim=0.5, ao=0.35, decal=leather)
    for sx in (-SEAT_X, SEAT_X):
        z0 = SEAT_F - 3
        m.box("cushion", (sx - 3, 3.5, z0), (sx + 3, 4.75, z0 + 5.5), seat)
        m.box("seat_back", (sx - 3.25, 4.75, z0 + 5.5), (sx + 3.25, 16, z0 + 7), seat)
        m.box("seat_head", (sx - 2, 16, z0 + 5.75), (sx + 2, 18.5, z0 + 7), seat)
    z0 = SEAT_R - 3
    m.box("bench", (-CAB + 0.5, 3.5, z0), (CAB - 0.5, 4.75, z0 + 5.5), seat)
    m.box("bench_back", (-CAB + 0.5, 4.75, z0 + 5.5), (CAB - 0.5, 15.5, z0 + 7), seat)
    for hx in (-7.5, 0, 7.5):
        m.box("bench_head", (hx - 2, 15.5, z0 + 5.75), (hx + 2, 17.5, z0 + 7), seat)

    def dash(c):
        if c.face == "up":
            return tone("ag_trim", 0.3)
        if c.face == "south" and math.floor(c.p[1]) == 13:
            return tone(A, 0.55)                                          # a blue ambient line across it
        return tone(TR, 0.3) if c.p[1] < 13 else tone("ag_trim", 0.2)
    dp = Paint(TR, 1, noise=0, grad=0, rim=0.3, ao=0.35, decal=dash)
    m.box("dash", (-(TRACK - 1), 10, ZS0), (TRACK - 1, BELT - 0.25, D0), dp)
    m.box("dash", (-CAB, 10, D0), (CAB, BELT - 0.25, -14), dp)

    def screen(c):
        if c.face != "south":
            return ("raw", (18, 18, 22, 255))
        x, y = c.p[0], c.p[1]
        if math.floor(y) == 17 and math.floor(x) % 5 in (1, 2):
            return ("raw", (120, 170, 255, 255))
        return ("raw", (26, 44, 84, 255)) if (math.floor(x) + math.floor(y)) % 5 else ("raw", (60, 110, 200, 255))
    m.box("dash_screen", (-3, BELT - 0.25, -15), (7, BELT + 1.75, -14.5), Paint(TR, 1, noise=0, light=False, glow=True, decal=screen))
    m.box("console", (-1.5, FLOOR, -14), (1.5, 7, -2), Paint(TR, 1, noise=0, rim=0.4, decal=lambda c: tone("ag_trim", 0.3)))
    # the steering wheel (driver on the left)
    cx, cy, cz = -SEAT_X, 13.5, -12
    rim = Paint("ag_trim", 1, noise=0, grad=0, rim=0.6, decal=lambda c: tone("ag_trim", 0.55 if c.p[1] > cy else 0.25))
    for xa, ya, xb, yb in ((-2.25, 1.75, 2.25, 2.25), (-2.25, -2.25, 2.25, -1.75), (-2.25, -1.75, -1.75, 1.75), (1.75, -1.75, 2.25, 1.75)):
        m.box("wheel", (cx + xa, cy + ya, cz), (cx + xb, cy + yb, cz + 0.5), rim)
    m.box("wheel_hub", (cx - 1.75, cy - 0.5, cz + 0.1), (cx + 1.75, cy + 0.5, cz + 0.4), CHROME)
    m.box("column", (cx - 0.4, cy - 0.9, -14), (cx + 0.4, cy - 0.1, cz), GLOSS)


def _door_parts(kit, ctx):
    """The mirrors on the front doors (gloss black caps, a blue-white repeater under them), a chrome handle on each
    door, the door cards' arm rests."""
    C, TR = ctx["C"], ctx["TR"]
    for s in (-1, 1):
        fd, rd = _door(kit, s, True), _door(kit, s, False)
        z = D0 + 0.75

        def mirror(c):
            if c.face == "south":
                if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                    return tone("ag_trim", 0.2)
                return tone("ag_chrome", 0.8)
            if c.face == "up":
                return tone("ag_trim", 0.9)
            if c.face == "down":
                return tone("ag_trim", 0.0)
            if c.face in ("east", "west") and c.p[1] < BELT + 1.5:
                return ("raw", (220, 236, 255, 255))                      # the repeater along its foot
            return tone("ag_trim", 0.45)
        fd.box("mirror_foot", _lo(s, W - 1, W + 0.5, BELT + 0.4, z), _hi(s, W - 1, W + 0.5, BELT + 1.2, z + 2.5), GLOSS)
        fd.box("mirror", _lo(s, W + 0.25, W + 2.75, BELT + 0.75, z), _hi(s, W + 0.25, W + 2.75, BELT + 3.25, z + 2),
               Paint("ag_trim", 1, noise=0, grad=0, rim=0.3, ao=0, decal=mirror))
        for d, zb in ((fd, D1 - 1.5), (rd, R1 - 1.5)):
            d.box("handle", _lo(s, W, W + 0.3, 13.25, zb - 3), _hi(s, W, W + 0.3, 13.75, zb), CHROME)
        fd.box("arm_rest", _lo(s, CAB - 1, CAB, 9, D0 + 5), _hi(s, CAB - 1, CAB, 10, D1 - 2), Paint(TR, 2, noise=0, rim=0.4))
        rd.box("arm_rest", _lo(s, CAB - 1, CAB, 9, R0 + 2), _hi(s, CAB - 1, CAB, 10, R1 - 3), Paint(TR, 2, noise=0, rim=0.4))
    del C


def _sills(m, ctx):
    """The thin blue LED line low along each sill (the underglow look: it shines at night), a gloss black blade over it."""
    A = ctx["A"]
    m.group("lights")
    glow = Paint(A, 3, noise=0, light=False, glow=True,
                 decal=lambda c: ("raw", (110, 160, 255, 255)) if c.face in ("east", "west") else ("raw", (60, 110, 255, 255)))
    for s in (-1, 1):
        m.box("blue_line", _lo(s, W, W + 0.25, GROUND + 0.25, WF + ARCH + 0.75), _hi(s, W, W + 0.25, GROUND + 0.75, WR - ARCH - 0.75), glow)
    m.group("body")
    for s in (-1, 1):
        m.box("sill_blade", _lo(s, W, W + 0.4, GROUND + 0.75, WF + ARCH + 0.5), _hi(s, W, W + 0.4, SILL - 0.75, WR - ARCH - 0.5), GLOSS)


def _part(name):
    return lambda: sedan_kit().all()[name]


# ================================================================================================
# The wheel: 14 px (0.875 block) across, 5 px wide
# ================================================================================================

def wheel_agent_sedan():
    """A low tyre round a gunmetal twin-spoke alloy (ten spokes in pairs, a polished lip, a dark centre cap) with a
    blue brake calliper behind the spokes."""
    m = BModel("wheel_agent_sedan", shift=(8, 8, 8), floor=None)
    d, width = 14, 5
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 0.8:
                return tone("ag_trim", 0.0)
            if r - 1.6 < rr <= r - 0.8:
                return tone("ag_trim", 0.3)
            return tone("ag_trim", 0.12)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.4)
        return tone("ag_trim", 0.0 if k % 3 == 0 else 0.18)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("ag_trim", 1, noise=0, rim=0.2, decal=tire), axis="x")
    rim_d = 10

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        rc = rim_d / 2
        if c.face not in ("east", "west"):
            return tone("ag_rim", 0.5)
        if rr > rc - 0.7:
            return tone("ag_chrome", 0.78 if y > 0 else 0.5)              # the polished lip
        if rr < 1.1:
            return tone("ag_trim", 0.6)                                   # the centre cap
        if rr < 1.8:
            return tone("ag_rim", 0.5)
        a = math.atan2(z, y)
        t = (a * 5 / (2 * math.pi)) % 1.0                                 # five pairs of spokes
        dpx = abs(t - 0.5) * 2 * math.pi * rr / 5
        if 0.35 < dpx < 1.15:
            return tone("ag_rim", 0.8 if y > 0.5 else 0.6)
        if rr > 2.6 and y > 1.2 and abs(z) < 1.6:
            return tone(ACCENT_RAMP, 0.6)                                 # the blue calliper
        return tone("ag_trim", 0.0)
    p = Paint("ag_rim", 3, noise=0, rim=0.2, ao=0.3, decal=face)
    w2 = width / 2
    cyl(m, "hub", (0, 0), rim_d, w2, w2 + 0.25, p, axis="x")
    cyl(m, "hub", (0, 0), rim_d, -w2 - 0.25, -w2, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


ACCENT_RAMP = "ag_blue"

ALL = {
    "agent_sedan": agent_sedan,
    "wheel_agent_sedan": wheel_agent_sedan,
    **{f"agent_sedan_{p}": _part(f"agent_sedan_{p}") for p in PARTS},
}
