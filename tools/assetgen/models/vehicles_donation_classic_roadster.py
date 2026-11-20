"""
후원 탈것 · 클래식 로드스터 (classic_roadster): an elegant open two-seat roadster in the manner of the 1950s and 60s. An
original design: no real make or model, no badge, no lettering.

Drawn like every other car on the server (models/vehicles.py, vehicles_premium.py; the approved donation style of
models/vehicles_donation_muscle_car.py): 16 px a block, the body drawn at its real size and shrunk by 2 (BModel scale
2), shown at vehicle.body-scale 2; the wheel a model of its own at 1. The "angled panels" style: the body is a handful
of big clean boxes; every slope is a flat panel turned 22.5 or 45 degrees about one axis (plank()), with the boxes
under it stepped a hair inside it (fill()): the fenders' crowns rising from the lamps, the front fenders' sweep down
across the doors and the rear fenders' rise over the rear wheels (the two meet in a low V on the door), the cut-down
doors' dip, the nose's brow, the deck falling to the tail, the arches' 45-degree corners, the soft top's rolled edges,
the raked windscreen. The detail is painted on the big faces (world-space decals, so a turned panel carries on the
paint of the boxes round it): soft gradients, the crowns' sky reflection, the sweep's lit edge, shut lines, the egg
crate mesh, the lamps' glass, the walnut grain, the leather's flutes and stitching, the hood's and soft top's seams.
Only what should stand out is real 3D: the lamps' chrome bezels, the grille's oval surround and centre bar, the
bumpers and overriders, the windscreen frame, the vents' strakes, the flank strip, the hood strip, mirrors, handles,
the filler cap, the luggage rack, the exhausts, the seats, the steering wheel and gauges, the wheels.

Drawn coordinates: 16 px a block, the car's ground centre at (0, 0, 0), front = north (-z), +x = the car's RIGHT
side; the driver sits on the left (-x).

Its look:
  * body: a centre hull (hood, scuttle, cockpit sides, deck) and two separate fenders a side: the front fender swells
    3.5 px above the low hood, its crown rising from the round headlamp in its tip at 22.5 degrees; behind the front
    wheel it sweeps down across the door at 22.5 degrees, and the rear fender rises again at 22.5 degrees over the rear
    wheel and falls to the round tail lamp in its end. Below the sweep the flank stands 2 px proud of the cockpit's
    sides (the classic spear line). Arches with 45-degree corners.
  * front: an oval chrome surround (an octagon of chrome bars) round an egg-crate mesh with a chrome centre bar, round
    chrome-ringed headlamps in the fender tips, small round parking lamps beside the grille, a chrome blade bumper
    wrapping back at 22.5 degrees with two bullet overriders, a chrome strip down the hood.
  * side: cut-down doors (their top dips 2 px at 22.5 degrees), side vents with chrome strakes behind the front wheels,
    a chrome strip along the flank (the two tones split there), a bullet wing mirror on the left front fender, flush
    chrome door pulls.
  * cockpit: a low chrome-framed windscreen raked 22.5 degrees (posts, rails, a mirror), a leather roll round the
    cockpit, two bucket seats (fluted, stitched, piped), a walnut dash with chrome-ringed gauges, a wooden-rim wheel
    with chrome spokes, a tunnel with the gear lever, door cards.
  * back: the folded soft top under its cover behind the seats (press studs, the folds at its ends), a chrome luggage
    rack on the falling boot lid, a chrome filler cap on the left rear fender, round tail lamps, a plate recess, a
    chrome blade bumper with overriders, twin exhausts on the right.

Paint (tuning): colour = the body (any palette ramp), lower = an optional second tone below the flank strip,
leather = the seats, door cards and roll, soft = the soft top's cover.

Models (ALL): classic_roadster (body), classic_roadster_door_l / _door_r (parts: the side between the two seams,
hinged at its front edge, vehicles.yml movers), wheel_classic (a wire wheel with a two-eared knock-off spinner and a
whitewall tyre, 13 px = 0.81 block across).
"""

import math
from types import SimpleNamespace

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp
from stsv_assets import presets

NAME = "classic_roadster"


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("rd_green", 158, 0.085, 0.40, 0.50, 8, False)    # deep racing green, metallic
_ramp("rd_ivory", 88, 0.030, 0.82, 0.30, 6, False)     # old English white (the second tone, gauge faces)
_ramp("rd_chrome", 235, 0.016, 0.66, 0.70, 8, False)   # chrome: dark reflections to near white
_ramp("rd_tan", 62, 0.090, 0.56, 0.36, 6, False)       # tan leather
_ramp("rd_oxblood", 22, 0.130, 0.38, 0.30, 6, False)   # red leather (the other leather)
_ramp("rd_wood", 52, 0.090, 0.40, 0.34, 6, False)      # walnut
_ramp("rd_canvas", 250, 0.012, 0.28, 0.24, 5, False)   # black mohair
_ramp("rd_dark", 240, 0.010, 0.16, 0.14, 4, False)     # recesses, the grille's holes, the wells
_ramp("rd_tyre", 260, 0.008, 0.22, 0.18, 4, False)
_ramp("rd_white", 90, 0.012, 0.88, 0.16, 3, False)     # whitewalls
_ramp("rd_lens", 85, 0.040, 0.86, 0.24, 4, True)       # headlamp glass
_ramp("rd_tail", 25, 0.200, 0.46, 0.36, 4, True)       # tail lamp glass
_ramp("rd_amber", 62, 0.150, 0.70, 0.24, 3, True)      # parking lamps


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

T = math.tan(math.radians(22.5))
NOSE, TAIL = -35, 34            # the nose's face, the tail's face
WF, WR = -21.5, 19              # front / rear axle (z)
WY, WD = 6.5, 13                # wheel centre height, its diameter
WHEEL_X = 12                    # the wheels' middle (x)
ARCH, ARCH_TOP, CHAMF = 7.75, 14.25, 4.0    # the arch: half length, top, its 45-degree corners
W = 15                          # the flank (the fenders' outer face)
HULL = 13                       # the cockpit's sides above the sweep
FX = 9                          # the fenders' inner face (the hood and deck between them)
CW = 11.75                      # the cockpit's inner half width (the door cards)
GROUND, FLOOR = 2.5, 3.5        # the body's bottom, the cockpit floor (the doors' bottom)
D0, D1 = -7, 9                  # the doors, between their seams (z)
SCUTTLE, DASH, CK1 = -9, -5, 13.5   # the hood's back, the dash's face, the cockpit's back
SCREEN_Z = -8                   # the windscreen's foot
HOOD, COWL, DECK = 12.5, 13, 13.5
DIP = 11                        # the cut-down doors' top
STRIP = 6.5                     # the chrome flank strip (and the split of the two tones)
SEAT_X = 5.45                   # the seats (vehicles.yml seats x 0.34)
HEAD = (12, 9.0, 2.5, 1.9)      # headlamp: |x|, y, bezel radius, lens radius
HEAD_Z = NOSE - 0.6             # the headlamps' face
PARK = (7.25, 5.75)             # parking lamps
TAILL = (12, 7.25, 2.0, 1.4)    # tail lamps: |x|, y, bezel radius, lens radius
TAIL_LZ = TAIL + 0.5            # the tail lamps' face
GRILLE = (5.0, 4.5, 10.0, 1.5)  # the grille's opening: half width, bottom, top, its corner cut

# the fenders' crown line (z, y) from the nose to the tail: the tip's 45-degree bevel, the crown rising at 22.5 to
# its top over the front wheel, the sweep down across the door to the V, the rear fender rising to its top over the
# rear wheel, falling at 22.5 and a last 45-degree bevel into the tail lamp's face
_VZ = (0.5 - T) / (2 * T)
LINE = [(NOSE, 15 - 8 * T), (NOSE + 1, 16 - 8 * T), (-26, 16.0), (-18, 16.0), (_VZ, 16 - T * (_VZ + 18)),
        (17, 15.5), (22, 15.5), (33, 15.5 - 11 * T), (34, 14.5 - 11 * T)]
# the hood (between the front fenders): a 45-degree brow at the nose
HOOD_LINE = [(NOSE, HOOD - 1.5), (NOSE + 1.5, HOOD), (SCUTTLE, HOOD)]
# the deck (between the rear fenders): flat behind the soft top, the boot lid falling at 22.5, a 45-degree bevel
DECK_LINE = [(CK1, DECK), (22, DECK), (33, DECK - 11 * T), (34, DECK - 1 - 11 * T)]
# the doors' top: high at both seams, dipping 2 px at 22.5 degrees
DOOR_LINE = [(D0, COWL), (D0 + 2 / T, DIP), (D1 - 2 / T, DIP), (D1, COWL)]


def lin(knots, z):
    """The polyline's y at z (held flat past its ends)."""
    if z <= knots[0][0]:
        return knots[0][1]
    for (a, ya), (b, yb) in zip(knots, knots[1:]):
        if z <= b:
            return ya + (yb - ya) * (z - a) / (b - a) if b > a else yb
    return knots[-1][1]


def arch_y(z, base):
    """The top of the arch's opening at z (base outside the arches)."""
    for wz in (WF, WR):
        d = abs(z - wz)
        if d < ARCH:
            return ARCH_TOP - CHAMF + (ARCH - d) if d > ARCH - CHAMF else ARCH_TOP
    return base


ARCH_KNOTS = [wz + e * k for wz in (WF, WR) for e in (-1, 1) for k in (ARCH, ARCH - CHAMF)]


def in_well(x, y, z):
    """Inside a wheel's opening (seen from the side)."""
    return abs(x) > FX - 0.01 and any(abs(z - wz) <= ARCH + 0.01 for wz in (WF, WR)) and y <= arch_y(z, 0) + 0.01


# ================================================================================================
# Angled panels (as in vehicles_donation_muscle_car)
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


def fill(m, name, xs, z0, z1, top, paint, base=GROUND, top_decal=None, arches=False, t=1.0, slopes=True):
    """The solid between x span xs (x0, x1), z0..z1, under the polyline `top` and over `base` (or the arches' openings
    when arches=True): flat stretches as one box; under a slope (and over an arch's 45-degree corner) boxes stepped a
    hair inside it, 2 px long under a 22.5-degree slope and 1 px under a 45-degree one, and a panel turned along the
    slope covering the steps (on top: painted with top_decal; under the arch corners: the arch's lip)."""
    x0, x1 = xs
    bot = (lambda z: arch_y(z, base)) if arches else (lambda z: base)
    eps = 1e-6
    cuts = {z0, z1}
    cuts |= {k for k, _ in top if z0 < k < z1}
    if arches:
        cuts |= {k for k in ARCH_KNOTS if z0 < k < z1}
    cuts = sorted(cuts)
    for a, b in zip(cuts, cuts[1:]):
        if b - a < eps:
            continue
        ta, tb = lin(top, a), lin(top, b)
        ba, bb = bot(a + eps), bot(b - eps)
        if abs(ta - tb) < eps and abs(ba - bb) < eps:
            if ta - ba > eps:
                m.box(name, (x0, ba, a), (x1, ta, b), paint)
            continue
        steep = abs(ta - tb) / (b - a) > T + 1e-3 or abs(ba - bb) / (b - a) > T + 1e-3
        n = max(1, math.ceil((b - a) / (1.0 if steep else 2.0) - 1e-6))
        w = (b - a) / n
        for k in range(n):
            c0, c1 = a + k * w, a + (k + 1) * w
            yt = min(lin(top, c0), lin(top, c1))
            yb = max(bot(c0 + eps), bot(c1 - eps))
            if yt - yb > 0.05:
                m.box(name, (x0 + 0.05, yb, c0), (x1 - 0.05, yt, c1), paint)
    # the panels on the slopes of the top
    for (a, ya), (b, yb) in (zip(top, top[1:]) if slopes else ()):
        a2, b2 = max(a, z0), min(b, z1)
        if b2 - a2 < eps or abs(ya - yb) < eps:
            continue
        plank(m, name + "_slope", "x", (a2, lin(top, a2)), (b2, lin(top, b2)), xs, t, paint,
              decal=top_decal or paint.decal)
    # the arches' 45-degree corners, from below
    if arches:
        lip = Paint("rd_dark", 1, noise=0, grad=0, rim=0, ao=0)
        for wz in (WF, WR):
            for e in (-1, 1):
                za, zb = sorted((wz + e * ARCH, wz + e * (ARCH - CHAMF)))
                a2, b2 = max(za, z0), min(zb, z1)
                if b2 - a2 < eps:
                    continue
                plank(m, name + "_arch", "x", (a2, bot(a2 + eps)), (b2, bot(b2 - eps)), xs, 0.75,
                      lip, decal=paint.decal, below=False)


def _x(side, a, b):
    """The x span between |x| a and b on one side."""
    return tuple(sorted((side * a, side * b)))


# ================================================================================================
# Paints
# ================================================================================================

def _make_paints(C, LO, L, S):
    """The car's paints for body colour C, lower tone LO (or None), leather L, soft top S."""

    def body(y):
        return LO if LO and y < STRIP else C

    def crown(x, y, z, ax):
        """The tops: the fenders' crowns rounded by their light (the sky down the middle), the hood and deck with a soft
        reflection, their shut lines."""
        if ax > FX - 0.01:
            # a fender: centre lit, falling off to both edges
            d = abs(ax - WHEEL_X) / 3.0
            return tone(C, 0.80 - 0.26 * smooth(d) + (0.06 if abs(ax - 11.75) < 0.5 else 0))
        if z < SCUTTLE:
            if ax > FX - 0.6 or SCUTTLE - 0.75 <= z < SCUTTLE - 0.25:
                return tone(C, 0.30)                                    # the hood's shut lines
            if ax < 1:
                return tone(C, 0.5)
            return tone(C, 0.66 if 4 <= ax < 6 else 0.58)               # a soft reflection down each half
        if z > 20:
            if ax > FX - 0.75 or 20 <= z < 20.5 or z > 32.5:
                return tone(C, 0.30) if z < 32.5 else tone(C, 0.56)     # the boot lid's shut lines
            return tone(C, 0.64 if 3 <= ax < 5 else 0.56)
        return tone(C, 0.56)

    def flank(x, y, z, ax):
        """The outer sides below the sweep: a soft gradient, the lit shoulder under the sweep, the seams, the vents,
        the arches' lips."""
        fy, fz = math.floor(y), math.floor(z)
        top = lin(LINE, z)
        if y < GROUND + 0.75:
            return tone(body(y), 0.12)
        if (fz == D0 or fz == D1 - 1) and y > GROUND + 0.75:
            return tone(body(y), 0.06)                                  # the door seams
        if -13.25 <= z < -9.75 and 7 <= y < 10.5:
            return tone("rd_dark", 0.2)                                 # the vents' slots (chrome strakes over them)
        a = arch_y(z, 0)
        if a > 0 and y < a + 0.75:
            return tone(body(y), 0.22)                                  # the arch's lip
        if top - y < 1.0:
            return tone(body(y), 0.66)                                  # the shoulder under the sweep
        if top - y < 1.75:
            return tone(body(y), 0.56)
        if fy == math.floor(STRIP) - 1:
            return tone(body(y), 0.26)                                  # the strip's shadow
        return tone(body(y), 0.36 + 0.24 * smooth((y - 3) / 9))

    def decal(c):
        x, y, z = c.p
        ax = abs(x)
        f = c.face
        if f == "down":
            return tone("rd_dark", 0.3)
        inward = (f == "east" and x < 0) or (f == "west" and x > 0)
        # the wheel wells: dark (their walls and the arch's end walls)
        if in_well(x, y, z) and f != "up" and not inward:
            if f in ("north", "south") or ax < W - 0.1:
                return tone("rd_dark", 0.15)
        cockpit = DASH - 0.01 < z < CK1 + 0.01 and ax < CW + 0.01 and y < COWL + 0.01
        hump = WR - ARCH - 0.01 < z < CK1 + 0.01 and ax < FX + 0.01 and inward
        if f == "up":
            if cockpit and y < FLOOR + 0.01:
                return tone(L, 0.10 if (math.floor(x) + math.floor(z)) % 2 else 0.04)   # carpet
            if cockpit and abs(x) < 1.6 and y < 5.5:
                return tone(L, 0.12)                                    # the tunnel's carpet
            # the leather roll round the cockpit: the door and quarter tops, the cowl's back edge, the deck's front
            if (D0 - 2 <= z <= WR - ARCH and CW - 0.01 < ax < HULL + 0.01 and y > 10) or \
                    (DASH - 1 <= z <= DASH and ax < CW + 0.01) or (CK1 <= z <= CK1 + 0.75 and ax < FX):
                return tone(L, 0.62 if (math.floor(z) + math.floor(x)) % 3 else 0.52)
            if abs(ax - (W - 1)) <= 1.01 and HULL - 0.01 < ax and lin(LINE, z) - y < 0.3 and -18 < z < 17:
                return tone(body(y), 0.86)                              # the sweep's lit edge
            return crown(x, y, z, ax)
        if f in ("east", "west"):
            if inward and (cockpit or hump):
                # the door cards and cockpit sides: pleated leather, a chrome strip, the roll
                fy = math.floor(y)
                if fy == 11:
                    return tone("rd_chrome", 0.85)
                if y >= lin(DOOR_LINE, z) - 1 and D0 <= z <= D1:
                    return tone(L, 0.5)
                if fy < 10 and math.floor(z) % 3 == 0 and y > FLOOR + 1:
                    return tone(L, 0.22)
                return tone(L, 0.38)
            if inward:
                # the fenders' inner faces over the hood and deck
                return tone(C, 0.36 + 0.14 * smooth((y - HOOD) / 3.5))
            if ax > W - 0.2:
                return flank(x, y, z, ax)
            # the cockpit's sides above the sweep (2 px in)
            return tone(C, 0.46 + 0.12 * smooth((y - 9) / 4))
        if f == "north":
            if cockpit or (CK1 - 0.01 < z < CK1 + 0.01 and ax < FX + 0.01 and y < DECK + 0.01):
                return tone(L, 0.36 if math.floor(y) % 2 else 0.30)     # the bulkhead behind the seats
            return tone(body(y), 0.44 + 0.22 * smooth((y - 4) / 8))
        if f == "south":
            if cockpit and z < DASH + 0.01:
                return _walnut(c, L)                                    # the dash
            if 4 <= ax < 4.5 and abs(z - TAIL) < 0.01 and 5.25 <= y < 7.25:
                return tone(body(y), 0.18)                              # the plate recess's sides
            if ax < 4 and abs(z - TAIL) < 0.01 and 5.25 <= y < 7.25:
                return tone(body(y), 0.26)
            return tone(body(y), 0.30 + 0.16 * smooth((y - 4) / 8))
        return None

    def sweep_decal(c):
        """The crown line's panels: the fenders' crowns rounded by their light; over the doors the sweep's lit edge."""
        x, y, z = c.p
        if c.face == "up":
            if z < SCUTTLE or z > WR - ARCH:
                return crown(x, y, z, abs(x))
            return tone(body(y), 0.84)
        return decal(c)

    def roll_decal(c):
        """The doors' top: the leather roll."""
        if c.face == "up":
            return tone(L, 0.62 if math.floor(c.p[2]) % 3 else 0.52)
        return decal(c)

    paint = Paint(C, 4, noise=0, grad=0, rim=0, ao=0, decal=decal)
    return dict(body=paint, decal=decal, sweep=sweep_decal, roll=roll_decal, colour=body)


def _walnut(c, L):
    """The walnut dash: a varnished grain, a bright top edge; the footwells under it leather."""
    x, y, z = c.p
    if y < 8.5:
        return tone(L, 0.18)
    if math.floor(y) == 12:
        return tone("rd_wood", 0.82)
    g = math.sin(y * 2.1 + math.sin(x * 0.45) * 1.6)
    if 3.5 <= x < 9 and 9 <= y < 11.5:
        if math.floor(x) in (3, 8) or math.floor(y) in (9, 11):
            return tone("rd_wood", 0.18)                                # the glove box's outline
        if math.floor(x) == 6 and math.floor(y) == 10:
            return tone("rd_chrome", 0.95)                              # its lock
    return tone("rd_wood", 0.50 + 0.14 * g)


def _chrome_decal(c, lit=0.98, sky=0.86, horizon=0.18, ground=0.52):
    """Chrome: bright on top, a sky band over a dark horizon line over the ground's reflection down its sides."""
    f = c.face
    if f == "up":
        return tone("rd_chrome", lit)
    if f == "down":
        return tone("rd_chrome", 0.3)
    h = c.hi[1] - c.lo[1]
    if h < 1.25:
        return tone("rd_chrome", sky)
    v = (c.p[1] - c.lo[1]) / h
    return tone("rd_chrome", sky if v > 0.55 else horizon if v > 0.38 else ground)


def _chrome(**kw):
    return Paint("rd_chrome", 5, noise=0, grad=0, rim=0.4, ao=0.2, decal=lambda c: _chrome_decal(c, **kw))


CHROME = _chrome()
CHROME_SMALL = Paint("rd_chrome", 5, noise=0, grad=0, rim=0.3, ao=0.2,
                     decal=lambda c: tone("rd_chrome", 0.98 if c.face == "up" else 0.3 if c.face == "down" else 0.78))
CLEAR = ("raw", (0, 0, 0, 0))


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


def classic_roadster(colour="rd_green", lower=None, leather="rd_tan", soft="rd_canvas", name=NAME):
    return roadster_kit(colour, lower, leather, soft, name).body


def roadster_kit(colour="rd_green", lower=None, leather="rd_tan", soft="rd_canvas", name=NAME):
    key = (colour, lower, leather, soft, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, ("door_l", "door_r"))
    m = kit.body
    m.group("body")
    ctx = dict(C=colour, LO=lower, L=leather, S=soft, P=_make_paints(colour, lower, leather, soft))
    _shell(m, kit, ctx)
    _front(m, ctx)
    _rear(m, ctx)
    _sides(m, kit, ctx)
    _windscreen(m, ctx)
    _cabin(m, ctx)
    _soft_top(m, ctx)
    _rack(m)
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
    """The hull (nose and hood, scuttle, floor, deck), the fenders and flanks along the crown line, the doors."""
    P = ctx["P"]
    body = P["body"]
    box = m.box
    # --- the nose round the grille's opening (1 px deep), the hood behind it
    gw, gy0, gy1, gc = GRILLE
    z1 = NOSE + 1
    box("fascia", (-FX, 3, NOSE), (FX, gy0, z1), body)
    box("fascia", (-FX, gy1, NOSE), (FX, HOOD - 1.5, z1), body)
    for s in (-1, 1):
        box("fascia", _lo(s, gw, FX, gy0, NOSE), _hi(s, gw, FX, gy1, z1), body)
        # the opening's corners filled a little (the oval surround's diagonal bars cover the rest)
        for yy in (gy0, gy1 - 0.75):
            box("fascia_corner", _lo(s, gw - 0.75, gw, yy, NOSE), _hi(s, gw - 0.75, gw, yy + 0.75, z1), body)
    fill(m, "hood", (-FX, FX), z1, SCUTTLE, HOOD_LINE, body, base=3, slopes=False)
    plank(m, "brow", "x", HOOD_LINE[0], HOOD_LINE[1], (-FX, FX), 1.0, body)
    # --- the scuttle (the windscreen stands on it), the dash's block, the floor and tunnel
    box("scuttle", (-HULL, GROUND, SCUTTLE), (HULL, COWL, D0), body)
    box("dash", (-CW, GROUND, D0), (CW, COWL, DASH), body)
    box("floor", (-CW, GROUND, DASH), (CW, FLOOR, WR - ARCH), body)
    box("floor", (-FX, GROUND, WR - ARCH), (FX, FLOOR, CK1), body)
    box("tunnel", (-1.5, FLOOR, DASH), (1.5, 5.25, 5), body)
    # --- the deck between the rear fenders
    fill(m, "deck", (-FX, FX), CK1, TAIL, DECK_LINE, body, base=3.5)
    # --- the fenders and flanks, along the crown line
    for s in (-1, 1):
        door = kit["door_l" if s < 0 else "door_r"]
        sw = P["sweep"]
        fill(m, "front_fender", _x(s, FX, W), NOSE, SCUTTLE, LINE, body, arches=True, top_decal=sw)
        fill(m, "flank", _x(s, HULL, W), SCUTTLE, D0, LINE, body, top_decal=sw)
        box("sill", _lo(s, CW, W, GROUND, D0), _hi(s, CW, W, FLOOR, D1), body)
        fill(door, "door", _x(s, HULL, W), D0, D1, LINE, body, base=FLOOR, top_decal=sw)
        fill(door, "door_top", _x(s, CW, HULL), D0, D1, DOOR_LINE, body, base=FLOOR, top_decal=P["roll"])
        fill(m, "flank", _x(s, HULL, W), D1, WR - ARCH, LINE, body, top_decal=sw)
        box("quarter", _lo(s, CW, HULL, GROUND, D1), _hi(s, CW, HULL, COWL, WR - ARCH), body)
        fill(m, "rear_fender", _x(s, FX, W), WR - ARCH, TAIL, LINE, body, arches=True, top_decal=sw)
        # the cockpit's back corners closed off from the rear wheel's well
        box("well", _lo(s, FX, CW, FLOOR, WR - ARCH - 0.25), _hi(s, FX, CW, ARCH_TOP - CHAMF, WR - ARCH), body)
        # the wheel wells' inner walls
        box("well", _lo(s, FX, FX + 0.5, 3, WF - ARCH + 0.05), _hi(s, FX, FX + 0.5, ARCH_TOP, WF + ARCH - 0.05), body)
        box("well", _lo(s, FX, FX + 0.5, 3.5, WR - ARCH + 0.05), _hi(s, FX, FX + 0.5, ARCH_TOP, WR + ARCH - 0.05), body)


def _lo(s, a, b, y, z):
    return (min(s * a, s * b), y, z)


def _hi(s, a, b, y, z):
    return (max(s * a, s * b), y, z)


def _front(m, ctx):
    """The grille (mesh, oval chrome surround, centre bar), the headlamps and parking lamps, the bumper, the hood
    strip."""
    gw, gy0, gy1, gc = GRILLE
    zf = NOSE + 1

    # the egg-crate mesh on the hood block's face behind the opening
    def mesh(c):
        x, y, z = c.p
        if c.face != "north":
            return tone("rd_dark", 0.2)
        fx, fy = math.floor(x * 1.0 + 0.5), math.floor(y - gy0)
        if fy % 2 == 0 and fx % 2 == 0:
            return tone("rd_chrome", 0.62)
        if fy % 2 == 0:
            return tone("rd_chrome", 0.50)
        if fx % 2 == 0:
            return tone("rd_chrome", 0.30)
        return tone("rd_chrome", 0.06)
    m.box("grille_mesh", (-gw, gy0, zf - 0.1), (gw, gy1, zf), Paint("rd_dark", 1, noise=0, grad=0, rim=0, ao=0.4, decal=mesh),
          faces={"south": None, "up": None, "down": None, "east": None, "west": None})
    m.box("grille_bar", (-0.35, gy0, NOSE - 0.25), (0.35, gy1, zf - 0.1), CHROME_SMALL)
    # the oval surround: straight bars, 45-degree bars across the corners
    t, zs = 0.6, (NOSE - 0.5, NOSE)
    m.box("grille_ring", (-(gw - gc), gy1, zs[0]), (gw - gc, gy1 + t, zs[1]), CHROME_SMALL)
    m.box("grille_ring", (-(gw - gc), gy0 - t, zs[0]), (gw - gc, gy0, zs[1]), CHROME_SMALL)
    ln = gc * math.sqrt(2)
    for s in (-1, 1):
        m.box("grille_ring", _lo(s, gw, gw + t, gy0 + gc, zs[0]), _hi(s, gw, gw + t, gy1 - gc, zs[1]), CHROME_SMALL)
        for top in (True, False):
            oy = gy1 - gc if top else gy0 + gc
            ys = (oy, oy + t) if top else (oy - t, oy)
            ang = -45 if (s > 0) == top else 45
            m.box("grille_ring", (s * gw - ln if s > 0 else s * gw, ys[0], zs[0]), (s * gw if s > 0 else s * gw + ln, ys[1], zs[1]),
                  CHROME_SMALL, rotation=("z", ang, (s * gw, oy, 0)))

    # the headlamps: a chrome bezel, the glass in it (a reflector's rings, a bulb, a glint)
    m.group("lights")
    hx, hy, hr, lr = HEAD

    def lamp(c, cx):
        if c.face != "north":
            return tone("rd_chrome", 0.85 if c.face == "up" else 0.45)
        dx, dy = c.p[0] - cx, c.p[1] - hy
        r = math.hypot(dx, dy)
        if r > lr:
            return tone("rd_chrome", 0.95 if dy > 0.4 else 0.62 if dy > -0.6 else 0.4)    # the bezel
        if r < 0.6:
            return ("raw", (255, 250, 232, 255))                       # the bulb
        if dy > 0.3 and -0.2 < dx * (1 if cx < 0 else -1) < 1.2 and r > 0.9:
            return ("raw", (255, 255, 248, 255))                       # a glint on the glass
        if r > lr - 0.45:
            return ("raw", (120, 128, 136, 255))                       # the glass's dark rim
        return ("raw", (236, 238, 226, 255)) if r < 1.1 else ("raw", (176, 190, 200, 255))
    for s in (-1, 1):
        cx = s * hx
        cyl(m, "headlamp", (cx, hy), 2 * hr, HEAD_Z, NOSE, Paint("rd_lens", 2, noise=0, rim=0.3, ao=0, decal=lambda c, cx=cx: lamp(c, cx)), axis="z")

        # the parking lamps: a small chrome ring round an amber glass
        def park(c, cx=s * PARK[0]):
            if c.face != "north":
                return tone("rd_chrome", 0.8)
            r = math.hypot(c.p[0] - cx, c.p[1] - PARK[1])
            return tone("rd_amber", 1.0 if r < 0.45 else 0.55) if r < 0.8 else tone("rd_chrome", 0.9)
        m.box("parking_lamp", (s * PARK[0] - 1, PARK[1] - 1, NOSE - 0.35), (s * PARK[0] + 1, PARK[1] + 1, NOSE),
              Paint("rd_amber", 1, noise=0, rim=0.2, ao=0, decal=park))
    m.group("body")

    # the bumper: a chrome blade, its ends wrapping back at 22.5 degrees, two bullet overriders
    z0 = NOSE - 2
    m.box("bumper_f", (-9, 3, z0), (9, 5.5, NOSE), CHROME)
    for s in (-1, 1):
        o = (s * 9, 3, z0)
        a, b = (9, 14) if s > 0 else (-14, -9)
        m.box("bumper_f_end", (a, 3, z0), (b, 5.5, NOSE), CHROME, rotation=("y", -22.5 * s, o))
        m.box("overrider", (s * 5.25 - 0.75, 2.5, z0 - 0.75), (s * 5.25 + 0.75, 7.25, z0 + 1.25), CHROME)
        m.box("overrider_tip", (s * 5.25 - 0.5, 7.25, z0 - 0.5), (s * 5.25 + 0.5, 7.75, z0 + 1), CHROME_SMALL)
    # the hood's chrome centre strip
    m.box("hood_strip", (-0.3, HOOD, NOSE + 2), (0.3, HOOD + 0.3, SCUTTLE - 1.5), CHROME_SMALL)


def _rear(m, ctx):
    """The tail lamps, the bumper, the exhausts, the filler cap."""
    tx, ty, tr, lr = TAILL

    def lamp(c, cx):
        if c.face != "south":
            return tone("rd_chrome", 0.85 if c.face == "up" else 0.45)
        dx, dy = c.p[0] - cx, c.p[1] - ty
        r = math.hypot(dx, dy)
        if r > lr:
            return tone("rd_chrome", 0.95 if dy > 0.3 else 0.5)
        if r < 0.5:
            return ("raw", (255, 96, 86, 255))
        return ("raw", (208, 36, 40, 255)) if dy > 0 else ("raw", (164, 22, 32, 255))
    m.group("lights")
    for s in (-1, 1):
        cx = s * tx
        cyl(m, "tail_lamp", (cx, ty), 2 * tr, TAIL, TAIL_LZ, Paint("rd_tail", 2, noise=0, rim=0.3, ao=0, decal=lambda c, cx=cx: lamp(c, cx)), axis="z")
    m.group("body")
    # the bumper
    z1 = TAIL + 2
    m.box("bumper_r", (-9, 3, TAIL), (9, 5.25, z1), CHROME)
    for s in (-1, 1):
        o = (s * 9, 3, z1)
        a, b = (9, 14) if s > 0 else (-14, -9)
        m.box("bumper_r_end", (a, 3, TAIL), (b, 5.25, z1), CHROME, rotation=("y", 22.5 * s, o))
        m.box("overrider", (s * 4.75 - 0.75, 2.5, TAIL + 0.75), (s * 4.75 + 0.75, 6.75, z1 + 0.75), CHROME)
        m.box("overrider_tip", (s * 4.75 - 0.5, 6.75, TAIL + 1), (s * 4.75 + 0.5, 7.25, z1 + 0.5), CHROME_SMALL)

    # twin exhausts under the right of the tail
    for x in (6.9, 8.6):
        m.box("exhaust", (x - 0.6, 1.4, 28), (x + 0.6, 2.6, TAIL + 3), Paint("rd_chrome", 5, noise=0, grad=0, rim=0.3,
              decal=lambda c, x=x: tone("rd_dark", 0.0) if c.face == "south" and abs(c.p[0] - x) < 0.5 and abs(c.p[1] - 2) < 0.5
              else _chrome_decal(c)))
    # the filler cap on the left rear fender's crown: a chrome knock-off cap
    fx, fz = -WHEEL_X, 20.25
    cyl(m, "filler", (fx, fz), 2, 15.5, 15.85, CHROME_SMALL)
    m.box("filler_ears", (fx - 1.4, 15.85, fz - 0.25), (fx + 1.4, 16.1, fz + 0.25), CHROME_SMALL)


def _sides(m, kit, ctx):
    """The vents' strakes, the flank strip (on the doors too), the wing mirror, the door pulls."""
    for s, part in ((-1, "door_l"), (1, "door_r")):
        door = kit[part]
        # the vents: chrome strakes over the dark slots, a chrome frame
        for y in (7.5, 8.75, 10.0):
            m.box("vent_strake", _lo(s, W, W + 0.25, y - 0.5, -13.25), _hi(s, W, W + 0.25, y, -9.75), CHROME_SMALL)
        m.box("vent_frame", _lo(s, W, W + 0.2, 6.5, -13.75), _hi(s, W, W + 0.2, 11, -13.25), CHROME_SMALL)
        m.box("vent_frame", _lo(s, W, W + 0.2, 6.5, -9.75), _hi(s, W, W + 0.2, 11, -9.25), CHROME_SMALL)
        # the flank strip, from the vents back to the rear arch
        ys = (STRIP - 0.25, STRIP + 0.25)
        m.box("strip", _lo(s, W, W + 0.3, ys[0], -9.25), _hi(s, W, W + 0.3, ys[1], D0), CHROME_SMALL)
        door.box("strip", _lo(s, W, W + 0.3, ys[0], D0), _hi(s, W, W + 0.3, ys[1], D1), CHROME_SMALL)
        m.box("strip", _lo(s, W, W + 0.3, ys[0], D1), _hi(s, W, W + 0.3, ys[1], WR - ARCH - 0.5), CHROME_SMALL)
        # the door pull: a flush chrome handle near the door's back under the sweep
        z = D1 - 3.5
        y = lin(LINE, z) - 1.4
        door.box("door_handle", _lo(s, W, W + 0.25, y - 0.45, z - 1.5), _hi(s, W, W + 0.25, y + 0.25, z + 0.5), CHROME_SMALL)
        # the door card's arm rest
        door.box("arm_rest", _lo(s, CW - 0.75, CW, 7.5, D0 + 4), _hi(s, CW - 0.75, CW, 8.25, D1 - 3),
                 Paint(ctx["L"], 3, noise=0, rim=0.4))
    # the bullet wing mirror on the left front fender, ahead of the scuttle
    mx, mz = -WHEEL_X, -12.5
    top = lin(LINE, mz)
    m.box("wing_mirror_stem", (mx - 0.25, top - 0.4, mz - 0.25), (mx + 0.25, top + 1.6, mz + 0.25), CHROME_SMALL)

    def bullet(c):
        if c.face == "south" and 0 < c.u < c.nu - 1:
            return ("raw", (196, 212, 232, 255)) if c.v == 0 else ("raw", (118, 134, 156, 255))
        return _chrome_decal(c)
    m.box("wing_mirror", (mx - 0.75, top + 1.4, mz - 1.0), (mx + 0.75, top + 2.6, mz + 1.0), Paint("rd_chrome", 5, noise=0, rim=0.3, decal=bullet))
    m.box("wing_mirror_tip", (mx - 0.4, top + 1.65, mz - 1.6), (mx + 0.4, top + 2.35, mz - 1.0), CHROME_SMALL)


def _windscreen(m, ctx):
    """The low windscreen raked back 22.5 degrees in a chrome frame: posts, a top rail, a foot, a mirror; wipers."""
    rake = ("x", 22.5, (0, COWL, SCREEN_Z))
    hw, top = 9.5, COWL + 6.0

    def pane(c):
        x, y, z = c.p
        if c.face not in ("north", "south"):
            return tone("rd_chrome", 0.8)
        u = math.floor(x * 0.8 + y * 1.2) % 19
        if u in (0, 1):
            return ("raw", (226, 236, 252, 150))
        if u == 4:
            return ("raw", (180, 198, 224, 110))
        return CLEAR
    m.box("windscreen", (-hw, COWL + 0.5, SCREEN_Z - 0.25), (hw, top, SCREEN_Z + 0.25),
          Paint("glass", 1, noise=0, light=False, decal=pane), rotation=rake)
    for s in (-1, 1):
        m.box("screen_post", _lo(s, hw, hw + 0.75, COWL - 0.25, SCREEN_Z - 0.5), _hi(s, hw, hw + 0.75, top + 0.6, SCREEN_Z + 0.5), CHROME_SMALL, rotation=rake)
    m.box("screen_top", (-hw, top, SCREEN_Z - 0.45), (hw, top + 0.6, SCREEN_Z + 0.45), CHROME_SMALL, rotation=rake)
    m.box("screen_foot", (-hw, COWL, SCREEN_Z - 0.45), (hw, COWL + 0.5, SCREEN_Z + 0.45), CHROME_SMALL, rotation=rake)
    m.box("mirror_stem", (-0.25, top + 0.6, SCREEN_Z - 0.25), (0.25, top + 1.2, SCREEN_Z + 0.25), CHROME_SMALL, rotation=rake)

    def mirror(c):
        if c.face == "south" and 0 < c.u < c.nu - 1:
            return ("raw", (190, 206, 226, 255)) if c.u < c.nu / 2 else ("raw", (120, 136, 158, 255))
        return _chrome_decal(c)
    m.box("mirror", (-1.75, top + 1.0, SCREEN_Z - 0.5), (1.75, top + 2.25, SCREEN_Z + 0.25), Paint("rd_chrome", 5, noise=0, rim=0.3, decal=mirror), rotation=rake)
    # the wipers, lying on the scuttle at the screen's foot
    wp = Paint("rd_dark", 1, noise=0, rim=0.4)
    for x0, x1 in ((-8.5, -2), (1, 7.5)):
        m.box("wiper", (x0, COWL, SCREEN_Z - 1.0), (x1, COWL + 0.3, SCREEN_Z - 0.6), wp)


def _cabin(m, ctx):
    """Two bucket seats, the gauges on the dash, the wooden-rim wheel, the gear lever and handbrake."""
    L = ctx["L"]

    def leather(c):
        x, y, z = c.p
        sx = SEAT_X if x > 0 else -SEAT_X
        dx = abs(x - sx)
        f = c.face
        if dx > 2.4:
            # the bolsters: plain, piped along their edges
            return tone(L, 0.72 if f == "up" else 0.42)
        if abs(dx - 2.4) < 0.5 or (f == "up" and False):
            return tone(L, 0.8)
        if f in ("north", "up"):
            k = math.floor(dx + 0.5)
            if k % 2 == 1:
                return tone(L, 0.30)                    # the seams between the flutes
            return tone(L, 0.62 if f == "up" else 0.55)
        return tone(L, 0.36)
    seat = Paint(L, 3, noise=0, grad=0, rim=0.5, ao=0.3, decal=leather)
    for sx in (-SEAT_X, SEAT_X):
        m.box("cushion", (sx - 2.9, FLOOR, 1.5), (sx + 2.9, 5.5, 9.5), seat)
        for e in (-1, 1):
            m.box("bolster", _lo(1, sx + e * 2.9, sx + e * 3.6, FLOOR, 2.0), _hi(1, sx + e * 2.9, sx + e * 3.6, 6.25, 9.5), seat)
        rot = ("x", 22.5, (sx, 5.5, 9.5))
        m.box("seat_back", (sx - 3.2, 5.5, 9.5), (sx + 3.2, 15.25, 11.0), seat, rotation=rot)
        m.box("seat_wing", (sx - 3.6, 5.5, 9.25), (sx - 3.2, 14.0, 11.0), seat, rotation=rot)
        m.box("seat_wing", (sx + 3.2, 5.5, 9.25), (sx + 3.6, 14.0, 11.0), seat, rotation=rot)

    # the gauges: two big chrome-ringed dials before the driver, four small ones in the middle
    def gauge(c, cx, cy, r, big):
        if c.face != "south":
            return tone("rd_chrome", 0.7)
        dx, dy = c.p[0] - cx, c.p[1] - cy
        rr = math.hypot(dx, dy)
        if rr > r - 0.5:
            return tone("rd_chrome", 0.98 if dy > 0 else 0.6)          # the chrome ring
        if big and abs(dx * 0.6 + dy * 0.8) < 0.4 and dx < 0.2 and rr < r - 0.6:
            return tone("rd_dark", 0.0)                                # the needle
        return tone("rd_ivory", 0.9 if rr < r * 0.55 else 0.7)
    for cx, cy, d in ((-7.25, 10.5, 3.0), (-3.75, 10.5, 3.0), (-1.0, 11.25, 1.5), (1.0, 11.25, 1.5), (-1.0, 9.5, 1.5), (1.0, 9.5, 1.5)):
        big = d > 2
        p = Paint("rd_chrome", 5, noise=0, light=False, decal=lambda c, cx=cx, cy=cy, d=d, big=big: gauge(c, cx, cy, d / 2, big))
        if big:
            cyl(m, "gauge", (cx, cy), d, DASH, DASH + 0.25, p, axis="z")
        else:
            m.box("gauge", (cx - d / 2, cy - d / 2, DASH), (cx + d / 2, cy + d / 2, DASH + 0.2), p)

    # the steering wheel: a wooden octagonal rim (four straight pieces, four at 45 degrees), chrome spokes, a boss
    cx, cy, cz = -SEAT_X, 10.75, -2.25
    R, t = 2.6, 0.55

    def wood(c):
        g = math.floor((c.p[0] + c.p[1]) * 1.3) % 3
        return tone("rd_wood", 0.8 if c.face == "up" else 0.45 + 0.08 * g)
    rim = Paint("rd_wood", 3, noise=0, rim=0.4, decal=wood)
    a = R * T + 0.15                                       # half the length of each straight piece
    m.box("wheel_rim", (cx - a, cy + R - t, cz - 0.3), (cx + a, cy + R, cz + 0.3), rim)
    m.box("wheel_rim", (cx - a, cy - R, cz - 0.3), (cx + a, cy - R + t, cz + 0.3), rim)
    m.box("wheel_rim", (cx - R, cy - a, cz - 0.3), (cx - R + t, cy + a, cz + 0.3), rim)
    m.box("wheel_rim", (cx + R - t, cy - a, cz - 0.3), (cx + R, cy + a, cz + 0.3), rim)
    for ex in (-1, 1):
        for ey in (-1, 1):
            # a 45-degree piece across each corner, its outer face on the octagon
            ox, oy = cx + ex * (R - t / 2) / math.sqrt(2), cy + ey * (R - t / 2) / math.sqrt(2)
            m.box("wheel_rim", (ox - a, oy - t / 2, cz - 0.3), (ox + a, oy + t / 2, cz + 0.3), rim,
                  rotation=("z", -45 * ex * ey, (ox, oy, cz)))
    m.box("wheel_spoke", (cx - R + t, cy - 0.15, cz - 0.1), (cx + R - t, cy + 0.15, cz + 0.1), CHROME_SMALL)
    m.box("wheel_spoke", (cx - 0.15, cy - R + t, cz - 0.1), (cx + 0.15, cy, cz + 0.1), CHROME_SMALL)
    m.box("wheel_boss", (cx - 0.6, cy - 0.6, cz - 0.15), (cx + 0.6, cy + 0.6, cz + 0.4), CHROME_SMALL)
    m.box("column", (cx - 0.3, cy - 0.3, DASH), (cx + 0.3, cy + 0.3, cz - 0.1), Paint("rd_dark", 2, noise=0, rim=0.4))
    # the gear lever on the tunnel, a wooden knob; the handbrake beside it
    m.box("gear_lever", (-0.2, 5.25, 2.0), (0.2, 8.0, 2.4), CHROME_SMALL)
    m.box("gear_knob", (-0.5, 8.0, 1.7), (0.5, 9.0, 2.7), Paint("rd_wood", 3, noise=0, rim=0.4,
          decal=lambda c: tone("rd_wood", 0.85 if c.face == "up" else 0.5)))
    m.box("handbrake", (1.5, FLOOR, 3.0), (1.9, 5.0, 6.5), CHROME_SMALL)


def _soft_top(m, ctx):
    """The folded soft top behind the seats: a fabric bundle under its cover, its edges rolled at 45 degrees, the
    folds showing at its ends, press studs along its foot."""
    S = ctx["S"]
    z0, z1, hw = 14.75, 20.0, FX - 0.25
    y0, y1 = DECK, DECK + 2.0

    def cover(c):
        x, y, z = c.p
        f = c.face
        if f in ("east", "west"):
            return tone(S, 0.62 if math.floor(y * 2) % 2 else 0.30)    # the folded layers
        if f == "up":
            if abs(z - (z0 + z1) / 2) < 0.5:
                return tone(S, 0.25)                                   # the cover's seam along its top
            return tone(S, 0.66 if (math.floor(x) + math.floor(z)) % 2 else 0.6)
        if y < y0 + 0.75 and math.floor(x) % 3 == 0:
            return tone("rd_chrome", 0.95)                             # the press studs
        return tone(S, 0.45)
    p = Paint(S, 2, noise=0, grad=0, rim=0.3, ao=0.2, decal=cover)
    m.box("soft_top", (-hw, y0, z0), (hw, y1 - 0.75, z1), p)
    m.box("soft_top", (-hw, y1 - 0.75, z0 + 0.75), (hw, y1, z1 - 0.75), p)
    plank(m, "soft_top_roll", "x", (z0, y1 - 0.75), (z0 + 0.75, y1), (-hw, hw), 0.75, p)
    plank(m, "soft_top_roll", "x", (z1 - 0.75, y1), (z1, y1 - 0.75), (-hw, hw), 0.75, p)


def _rack(m):
    """A chrome luggage rack on the boot lid, lying along its 22.5-degree fall: two rails, four slats, feet."""
    za, zb = 23.0, 31.5
    y0 = lin(DECK_LINE, za) + 0.75
    rot = ("x", 22.5, (0, y0, za))
    length = (zb - za) / math.cos(math.radians(22.5))
    for s in (-1, 1):
        m.box("rack_rail", _lo(s, 5.25, 5.75, y0, za), _hi(s, 5.25, 5.75, y0 + 0.4, za + length), CHROME_SMALL, rotation=rot)
        for zz in (za + 0.5, za + length - 1.0):
            m.box("rack_foot", _lo(s, 5.3, 5.7, y0 - 0.8, zz), _hi(s, 5.3, 5.7, y0, zz + 0.5), CHROME_SMALL, rotation=rot)
    for k in range(4):
        zz = za + 0.9 + k * (length - 2.3) / 3
        m.box("rack_slat", (-5.25, y0 + 0.05, zz), (5.25, y0 + 0.35, zz + 0.5), CHROME_SMALL, rotation=rot)


def _part(part):
    return lambda: roadster_kit().all()[f"{NAME}_{part}"]


# ================================================================================================
# The wheel: 13 px (0.81 block) across, 4 px wide
# ================================================================================================

def wheel_classic():
    """A tall whitewall tyre round a chrome wire wheel (the laced spokes painted: a bright polished rim, the crossing
    spokes catching the light, a dark well between), a chrome hub with a two-eared knock-off spinner (both sides: the
    same model turns on both sides of the car)."""
    m = BModel("wheel_classic", shift=(8, 8, 8), floor=None)
    d, w2 = WD, 2.0
    R = d / 2
    rim_r = 3.9

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        lit = (y > 0 and z < 0)
        if c.face in ("east", "west"):
            if rr > R - 0.9:
                return tone("rd_tyre", 0.45 if lit else 0.3)
            if rr > R - 2.4:
                return tone("rd_white", 1.0 if lit else 0.75) if rr < R - 1.25 else tone("rd_tyre", 0.25)   # whitewall
            if rr > rim_r - 0.15:
                return tone("rd_tyre", 0.15)
            if rr > rim_r - 0.85:
                return tone("rd_chrome", 1.0 if lit else 0.78)                      # the polished rim
            if rr < 1.4:
                return tone("rd_chrome", 0.55)
            # the laced spokes: a fine even weave of light and shade (the crossing wires), lighter towards the light
            weave = (math.floor(y) + math.floor(z)) % 2
            up = 0.08 if y > 0 else -0.06
            return tone("rd_chrome", (0.70 if weave else 0.46) + up)
        # the tread: ribs round it, a lit upper half
        x = c.p[0]
        rib = abs(x) < 0.5 or abs(abs(x) - 1.5) < 0.01
        return tone("rd_tyre", 0.12 if rib else (0.55 if y > 0 else 0.35))
    cyl(m, "tire", (0, 0), d, -w2, w2, Paint("rd_tyre", 1, noise=0, rim=0.2, ao=0, decal=face), axis="x")

    def hub(c):
        y, z = c.p[1], c.p[2]
        if c.face in ("east", "west"):
            return tone("rd_chrome", 1.0 if (y > 0 and z < 0) else 0.72)
        return tone("rd_chrome", 0.95 if c.face == "up" else 0.4)
    hp = Paint("rd_chrome", 5, noise=0, rim=0.3, ao=0, decal=hub)
    for s in (1, -1):
        a, b = sorted((s * w2, s * (w2 + 0.6)))
        m.box("hub", (a, -1.25, -1.25), (b, 1.25, 1.25), hp)
        a, b = sorted((s * (w2 + 0.6), s * (w2 + 1.1)))
        m.box("spinner", (a, -0.5, -2.4), (b, 0.5, 2.4), hp)
    m.display = presets.furniture(gui_scale=0.6)
    return m


ALL = {
    NAME: classic_roadster,
    "wheel_classic": wheel_classic,
    **{f"{NAME}_{p}": _part(p) for p in ("door_l", "door_r")},
}
