"""
The donation vehicle 사이드카 오토바이 (sidecar_bike): a 1940s touring / military style V-twin motorcycle with a torpedo
sidecar on its RIGHT side. An original design: no real make, no badge, no signature shape copied.

Drawn like every other vehicle on the server (models/vehicles.py, vehicles_premium.py, and the donation muscle car
models/vehicles_donation_muscle_car.py, whose "angled panels" approach this follows): 16 px a block, the body drawn at
its real size and shrunk by 2 (BModel scale 2), shown at vehicle.body-scale 2; the wheel a model of its own at 1.
No pixel-staircase curves: every round or sloped thing is a few clean boxes and flat panels turned 22.5 or 45
degrees about one axis (plank(), arc(), octa()): the fenders are rings of panels round the wheels (a crown and two
valances each), the headlamp, the air cleaner and the tail lamps are octagons, the tub is an octagonal prism whose
nose and tail step down in art-deco rings to a polished chrome cone, the fork, frame tubes, headers and bars are
straight bars at 22.5 / 45 degrees. A motorcycle is small, so its thin parts are half-pixel boxes and the detail is
painted (world-space decals, so a turned panel carries on the paint of the boxes round it): the enamel's soft
gradients and highlights, the chrome's sky band over a dark horizon, the cylinders' fins, the springs' coils, the
tank's flash and knee pads, the saddle's stitching, the tub's tufted seat, the lamps' insides, the spoked wheels.

Drawn coordinates: 16 px a block, the rig's ground centre at (0, 0, 0), front = north (-z), +x = its RIGHT side.
The bike runs along x = BX (left of the middle), the sidecar's tub along x = SX (right of it): the pair is centred
so the item file's collision box (centred on the vehicle) covers both.

The bike: a springer fork (rigid rear legs, sprung front legs on a rocker at the axle, two chrome coil springs under
the upper crown), a deep valanced front fender with the trim colour down its crown, a round chrome headlamp with a
speedometer on top and a horn under it, wide pulled-back handlebars with ribbed grips, levers and one round mirror;
a teardrop two-tone tank (the paint with a flash of the trim colour each side, a chrome strip down its crown, two
filler caps, ribbed rubber knee pads, a hand-shift lever on its left); a V-twin with finned barrels and heads and
chrome rocker covers, a chrome primary cover on the left, a timing cover, a carburettor with a round chrome air
cleaner on the right; two chrome pipes down the left side to stacked fishtail mufflers; a sprung leather solo saddle
on two chrome coil springs; rubber footboards; a black cradle frame with plunger rear suspension; a valanced rear
fender with a round tail lamp and the plate holder; a leather saddlebag on the left, an oil tank on the right.

The sidecar: a torpedo tub (the paint below a chrome spear moulding, the trim colour above, a chrome roll round the
cockpit, a chrome nose cone), a tufted leather seat and back rest, a grab rail, a luggage rack on the tail deck with
a strapped leather case, a spare wheel standing on the tail, its own wheel under a valanced mudguard with a running
lamp on top and a red lamp on its back, and the chassis that carries it: two rails under the tub, cross members to
the bike's frame, two upper struts, the wheel's axle tube.

Its moving part (vehicles.yml movers): screen, the sidecar's little windscreen in its chrome frame, which folds
forward on its foot while the passenger gets in or out (hinge at drawn (SX, SCREEN_Y, SCREEN_Z)).

Riders: the driver astride the saddle (seat 0), the passenger low in the tub (seat 1). Paint is an argument: colour
(the tank, the fenders, the tub below the spear), trim (the tank's flash, the fender stripes, the tub above the
spear), leather (the saddle, the seat, the bags), whitewall (tyres with a white wall), so a tuning colour is one more
call (sidecar_bike(colour="sc_olive", trim="sc_olive") is the olive drab army rig).
"""

import math
from types import SimpleNamespace

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp
from stsv_assets import presets
from models.vehicles_road import rod


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("sc_maroon", 18, 0.140, 0.40, 0.46, 8, True)      # deep maroon / burgundy enamel
_ramp("sc_cream", 88, 0.040, 0.84, 0.26, 7, False)      # old ivory cream
_ramp("sc_olive", 118, 0.055, 0.44, 0.40, 8, False)     # olive drab
_ramp("sc_chrome", 245, 0.012, 0.60, 0.86, 9, False)    # chrome: near black to near white
_ramp("sc_black", 255, 0.010, 0.24, 0.26, 6, False)     # black enamel (frame)
_ramp("sc_alloy", 85, 0.010, 0.62, 0.40, 7, False)      # cast aluminium (crankcase, heads)
_ramp("sc_iron", 250, 0.008, 0.36, 0.30, 6, False)      # cast iron barrels, dark metal
_ramp("sc_rubber", 260, 0.010, 0.31, 0.28, 6, False)
_ramp("sc_leather", 52, 0.075, 0.40, 0.36, 7, False)    # tan saddle leather
_ramp("sc_lamp", 88, 0.070, 0.90, 0.14, 4, True)        # warm lamp glass
_ramp("sc_red", 25, 0.200, 0.48, 0.36, 5, True)         # tail lamp glass
_ramp("sc_white", 90, 0.012, 0.90, 0.12, 4, False)      # plate, dial, whitewall
_ramp("sc_glass", 215, 0.030, 0.82, 0.20, 3, False)


def _n(ramp):
    """Steps in a ramp (followed through palette.remapped, so a tuning paint keeps its lights)."""
    spec = MATERIALS.get(REMAP.get(ramp, ramp))
    return spec[4] if spec else 5


def tone(ramp, t):
    """(ramp, step) at lightness t: 0 the ramp's darkest, 1 its lightest (whatever its length)."""
    return (ramp, max(0.0, min(1.0, t)) * (_n(ramp) - 1))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


CLEAR = ("raw", (0, 0, 0, 0))


# ================================================================================================
# Layout (drawn px, 16 a block)
# ================================================================================================

BX, SX = -7.5, 6.0            # the bike's centre line, the tub's centre line
WY, WRAD = 6.0, 6.0           # wheel centre height, tyre radius (wheel-radius 0.375 block)
ZF, ZR = -13.0, 13.0          # the bike's axles
SWX, SWZ = 14.5, 5.0          # the sidecar wheel (x, z)
RAKE = 22.5                   # the fork's rake (a model element turns in 22.5 degree steps)
FD = (math.cos(math.radians(RAKE)), math.sin(math.radians(RAKE)))     # up the fork: (dy, dz)
FF = (math.sin(math.radians(RAKE)), -math.cos(math.radians(RAKE)))    # across the fork, forward: (dy, dz)
HL = (16.5, -12.25)           # headlamp centre (y, z)
HL_FACE = -14.25              # the headlamp's lens face (z)
SADDLE = 15.6                 # the saddle's top
SCREEN_Z, SCREEN_Y = -4.0, 14.25   # the sidecar windscreen's hinge (its foot): vehicles.yml movers hinge
TAIL_LAMP = (11.0, 20.5)      # the bike's tail lamp: middle (y), its lens face (z)
PLATE = (7.1, 21.6)           # the plate's middle (y) and its face (z): vehicles.yml plates
SIDE_LAMP = (14.0, 1.0)       # the running lamp on the sidecar's mudguard: middle (y), lens face (z)
GUARD_LAMP = (11.2, 11.9)     # the red lamp on the back of the sidecar's mudguard: middle (y), lens face (z)

# the tub: an octagonal prism (half width TW, bottom TB, top TT, chamfer TC), nose and tail rings
TW, TB, TT, TC = 6.5, 4.5, 14.0, 1.75
TUB_Z0, TUB_Z1 = -8.0, 6.5    # the full section between these
WELL_Z0, WELL_Z1 = -3.5, 6.5  # the cockpit
WELL_FLOOR = 6.25
SPEAR = 10.25                 # the chrome spear's middle (y): the paint below, the trim colour above
SPARE = (10.0, 16.75)         # the spare wheel's middle (y) and its z
NOSE = (-11.0, -13.5, -14.5)  # the tub's nose: the maroon ring's front, the chrome cone's, the tip's
TAIL_Z = 13.0                 # the tub's tail


def fork(t, off=0.0):
    """A point (y, z) t px up the fork from the front axle, off px forward of its line."""
    return (WY + t * FD[0] + off * FF[0], ZF + t * FD[1] + off * FF[1])


def _x(c, a, b):
    """The x span from c + a to c + b (either order)."""
    return tuple(sorted((c + a, c + b)))


# ================================================================================================
# Angled panels (the muscle car's plank, and rings / octagons of them)
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
    if rot is None:
        return decal
    axis, ang, o = rot

    def d(c):
        q = SimpleNamespace(p=_rot(c.p, axis, ang, o), face=c.face, u=c.u, v=c.v, nu=c.nu, nv=c.nv, lo=c.lo, hi=c.hi,
                            i=c.i, n=c.n, frame=c.frame, local=c.p)
        return decal(q)
    return d


def plank(m, name, axis, p0, p1, span, t, paint, decal=None, below=True, faces=None, buried=False):
    """A flat panel whose face runs along p0 -> p1 in the plane across `axis` (axis "x": points are (z, y), "z": (x, y),
    "y": (z, x)), t thick on one side (below=True: towards -y, or -x for axis "y"), spanning `span` along the axis.
    The slope must be a multiple of 22.5 degrees. decal: a world-space decal. buried: its inner face and the two
    long edges lie inside the boxes it bevels (an octagon's chamfer): left out. Returns the rotation."""
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
    rot = (axis, snap, o) if snap else None
    if decal is not None:
        paint = paint(decal=world(decal, rot))
    if buried and rot is not None:
        faces = dict(faces or {})
        if axis == "z":
            gone = ("down" if below else "up", "west", "east")
        elif axis == "x":
            gone = ("down" if below else "up", "north", "south")
        else:
            gone = ("west" if below else "east", "north", "south")
        for f in gone:
            faces[f] = None
    m.box(name, frm, to, paint, rotation=rot, faces=faces)
    return rot


def arc(m, name, c, angles, r0, r1, lo, hi, paint, decal, ends=(True, True), axis="x", inner=True, drop=(), upright=False):
    """A ring of flat segments round c (axis x: c = (y, z), the angles in degrees from +y towards +z; axis z: c = (x, y),
    from +x towards +y), each segment centred on its angle (multiples of 22.5), radially r0..r1, lo..hi along the
    axis. Each segment is just long enough to meet its neighbours at r1. decal: world space. ends: keep the first /
    last segment's open end. inner: draw the inner face. drop: faces nobody sees ("outer", "lo", "hi": the outer
    face, the faces at lo and hi along the axis), left out of the turned segments (and of the upright ones too with
    upright=True: only where an upright box covers them)."""
    step = 22.5
    L = 2 * r1 * math.tan(math.radians(step / 2)) + 0.04
    n = len(angles)
    for k, th in enumerate(angles):
        th = th % 360
        base = (round(th / 90.0) % 4) * 90
        phi = th - base
        if phi > 180:
            phi -= 360
        if phi < -180:
            phi += 360
        a, b = c
        if axis == "x":
            if base in (0, 180):
                sg = 1 if base == 0 else -1
                y0, y1 = sorted((a + sg * r0, a + sg * r1))
                frm, to = (lo, y0, b - L / 2), (hi, y1, b + L / 2)
                endf, rad = ("north", "south"), ("up", "down") if sg > 0 else ("down", "up")
                lead = "north" if sg > 0 else "south"     # the face towards smaller angles
            else:
                sg = 1 if base == 90 else -1
                z0, z1 = sorted((b + sg * r0, b + sg * r1))
                frm, to = (lo, a - L / 2, z0), (hi, a + L / 2, z1)
                endf, rad = ("up", "down"), ("south", "north") if sg > 0 else ("north", "south")
                lead = "up" if sg > 0 else "down"
            rot = ("x", phi, (lo, a, b)) if phi else None
        else:
            if base in (0, 180):
                sg = 1 if base == 0 else -1
                x0, x1 = sorted((a + sg * r0, a + sg * r1))
                frm, to = (x0, b - L / 2, lo), (x1, b + L / 2, hi)
                endf, rad = ("up", "down"), ("east", "west") if sg > 0 else ("west", "east")
                lead = "down" if sg > 0 else "up"
            else:
                sg = 1 if base == 90 else -1
                y0, y1 = sorted((b + sg * r0, b + sg * r1))
                frm, to = (a - L / 2, y0, lo), (a + L / 2, y1, hi)
                endf, rad = ("east", "west"), ("up", "down") if sg > 0 else ("down", "up")
                lead = "east" if sg > 0 else "west"
            rot = ("z", phi, (a, b, lo)) if phi else None
        trail = endf[1] if lead == endf[0] else endf[0]
        faces = {}
        if k > 0 or not ends[0]:
            faces[lead] = None
        if k < n - 1 or not ends[1]:
            faces[trail] = None
        if not inner:
            faces[rad[1]] = None
        lohi = ("west", "east") if axis == "x" else ("north", "south")
        for role in drop:
            faces[{"outer": rad[0], "lo": lohi[0], "hi": lohi[1]}[role]] = None
        if rot is None:
            # an upright segment stays closed (the checker wants a removed face covered by an upright box)
            faces = {f: None for f in faces if upright and f in [{"outer": rad[0], "lo": lohi[0], "hi": lohi[1]}[r] for r in drop]}
        m.box(name, frm, to, paint(decal=world(decal, rot)), faces=faces or None, rotation=rot)


def octa(m, name, c, W, H, ch, lo, hi, paint, decal, axis="z", t=None, faces=None, caps=(True, True), bottom=True):
    """An octagonal prism along axis z (c = (x, y)) or x (c = (z, y)): half width W, half height H, its four long edges
    chamfered ch at 45 degrees, from lo to hi along the axis: a core, two side boxes, four chamfer panels. caps:
    False leaves that end out (where it lies against a bigger section)."""
    t = t if t is not None else ch * 0.72 + 0.05
    p = paint(decal=decal)
    a, b = c
    ends = ("north", "south") if axis == "z" else ("west", "east")
    faces = dict(faces or {})
    for k in (0, 1):
        if not caps[k]:
            faces[ends[k]] = None
    faces = faces or None
    if axis == "z":
        m.box(name, (a - (W - ch), b - H, lo), (a + (W - ch), b + H, hi), p, faces=faces)
        m.box(name, (a - W, b - (H - ch), lo), (a - (W - ch), b + (H - ch), hi), p, faces=faces)
        m.box(name, (a + (W - ch), b - (H - ch), lo), (a + W, b + (H - ch), hi), p, faces=faces)
        for s in (-1, 1):
            plank(m, name + "_edge", "z", (a + s * (W - ch), b + H), (a + s * W, b + H - ch), (lo, hi), t, paint, decal=decal, faces=faces, buried=True)
            if bottom:
                plank(m, name + "_edge", "z", (a + s * (W - ch), b - H), (a + s * W, b - H + ch), (lo, hi), t, paint,
                      decal=decal, below=False, faces=faces, buried=True)
    else:
        m.box(name, (lo, b - H, a - (W - ch)), (hi, b + H, a + (W - ch)), p, faces=faces)
        m.box(name, (lo, b - (H - ch), a - W), (hi, b + (H - ch), a - (W - ch)), p, faces=faces)
        m.box(name, (lo, b - (H - ch), a + (W - ch)), (hi, b + (H - ch), a + W), p, faces=faces)
        for s in (-1, 1):
            plank(m, name + "_edge", "x", (a + s * (W - ch), b + H), (a + s * W, b + H - ch), (lo, hi), t, paint, decal=decal, faces=faces, buried=True)
            plank(m, name + "_edge", "x", (a + s * (W - ch), b - H), (a + s * W, b - H + ch), (lo, hi), t, paint,
                  decal=decal, below=False, faces=faces, buried=True)


# ================================================================================================
# Paints
# ================================================================================================

def flat(ramp, base=None):
    """A Paint shaded only by its decal (and the game's face light)."""
    return Paint(ramp, base if base is not None else 2, noise=0, grad=0, rim=0, ao=0)


def chrome_decal(lit=0.87, side=0.66, low=0.36, mid=None):
    """Chrome: bright on top, a sky band over a dark horizon down its sides (on parts tall enough), dark below."""
    def d(c):
        if c.face == "up":
            return tone("sc_chrome", lit)
        if c.face == "down":
            return tone("sc_chrome", 0.18)
        lo, hi = c.lo[1], c.hi[1]
        y = getattr(c, "local", c.p)[1]
        if hi - lo >= 1.5:
            f = (y - lo) / (hi - lo)
            if f > 0.6:
                return tone("sc_chrome", side + 0.1)
            if f > 0.42:
                return tone("sc_chrome", 0.2)         # the dark horizon
            return tone("sc_chrome", low)
        return tone("sc_chrome", side if c.face in ("north", "west") else side - 0.16)
    return d


def chrome_paint(**kw):
    return flat("sc_chrome", 4)(decal=chrome_decal(**kw))


def solid(ramp, up=0.7, side=0.45, down=0.15, front=None):
    def d(c):
        if c.face == "up":
            return tone(ramp, up)
        if c.face == "down":
            return tone(ramp, down)
        if front is not None and c.face in ("north", "west"):
            return tone(ramp, front)
        return tone(ramp, side)
    return flat(ramp)(decal=d)


def enamel(C, y0, y1, up=0.72, hi_t=0.58, lo_t=0.30):
    """Gloss paint over a part from y0 to y1: a soft gradient down its sides, a light top."""
    def d(c):
        if c.face == "up":
            return tone(C, up)
        if c.face == "down":
            return tone(C, 0.12)
        f = smooth((c.p[1] - y0) / max(0.5, y1 - y0))
        return tone(C, lo_t + (hi_t - lo_t) * f)
    return d


# ================================================================================================
# The wheel: 12 px (0.75 block) across, 2.5 px wide
# ================================================================================================

def wheel_sidecar(whitewall=False, name="wheel_sidecar"):
    """The rig's wheel (all three, the spare too): a fat round-shouldered tyre (a ring of sixteen panels, a block
    tread on its crown), a chrome rim painted inside the bead, sixteen wire spokes on a see-through disc, a finned
    alloy drum-brake hub with a chrome cap."""
    m = BModel(name, shift=(8, 8, 8), floor=None)
    _spoked_wheel(m, "x", (0.0, 0.0), 0.0, whitewall)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def _spoked_wheel(m, axis, c, a0, whitewall, drop=(), bars=8):
    """A spoked wheel round c, its axle along axis x (c = (y, z), the wheel's middle at x = a0) or z (c = (x, y), at
    z = a0)."""
    R, RI, HW = WRAD, 4.25, 1.25

    def radius(p):
        if axis == "x":
            return math.hypot(p[1] - c[0], p[2] - c[1]), p[1] - c[0], p[0] - a0
        return math.hypot(p[0] - c[0], p[1] - c[1]), p[1] - c[1], p[2] - a0

    side_faces = ("east", "west") if axis == "x" else ("north", "south")

    def tyre(cc):
        r, dy, along = radius(cc.p)
        if cc.face in side_faces:
            if r > R - 0.55:
                return tone("sc_rubber", 0.10)
            if r > 4.9:
                if whitewall and r < R - 0.55:
                    return tone("sc_white", 0.75 if dy > 0 else 0.55)
                return tone("sc_rubber", 0.42 if r < 5.15 else 0.28)      # the wall, a moulded ring at the bead
            # the chrome rim inside the bead: lit along its top
            return tone("sc_chrome", 0.87 if dy > 1.5 else 0.62 if dy > -1.5 else 0.30)
        if r < RI + 0.6:
            return tone("sc_chrome", 0.35)                                 # the rim's well, seen through the spokes
        # the crown: blocks across it, staggered between the halves, a groove round the middle
        u = cc.u
        if abs(along) < 0.35:
            return tone("sc_rubber", 0.0)
        return tone("sc_rubber", 0.06 if (u + (along > 0)) % 2 == 0 else 0.30)
    angles = [k * 22.5 for k in range(16)]
    lo, hi = a0 - HW, a0 + HW
    arc(m, "tyre", c, angles, RI, R, lo, hi, flat("sc_rubber", 1), tyre, axis=axis, ends=(False, False), drop=drop)

    # the spokes: eight thin chrome bars through the hub (sixteen spokes), every 22.5 degrees, laced to the hub's two
    # flanges in turn (a quarter pixel thin, so they read as fine wire at any angle, not as stepped pixels)
    sp = flat("sc_chrome", 4)(decal=lambda cc: tone("sc_chrome", 0.8 if cc.face in ("up", "north", "west") else 0.5))
    w, reach = 0.125, RI + 0.15
    for k in range(bars):
        th = k * 180.0 / bars
        along_y = th <= 45 or th >= 135          # drawn along the first axis (y, or x for axis z) and turned
        ang = th if th <= 45 else th - 180 if th >= 135 else th - 90
        off = a0 + (0.35 if k % 2 else -0.35)
        if axis == "x":
            if along_y:
                frm, to = (off - w, c[0] - reach, c[1] - w), (off + w, c[0] + reach, c[1] + w)
                ends = ("up", "down")
            else:
                frm, to = (off - w, c[0] - w, c[1] - reach), (off + w, c[0] + w, c[1] + reach)
                ends = ("north", "south")
            rot = ("x", ang, (off, c[0], c[1])) if ang else None
        else:
            if along_y:
                frm, to = (c[0] - reach, c[1] - w, off - w), (c[0] + reach, c[1] + w, off + w)
                ends = ("west", "east")
            else:
                frm, to = (c[0] - w, c[1] - reach, off - w), (c[0] + w, c[1] + reach, off + w)
                ends = ("up", "down")
            rot = ("z", ang, (c[0], c[1], off)) if ang else None
        m.box("spoke", frm, to, sp, faces={f: None for f in ends}, rotation=rot)

    # the hub: a finned alloy drum, a chrome cap each end
    def hub(cc):
        r, dy, along = radius(cc.p)
        if cc.face in side_faces:
            return tone("sc_chrome", 0.87 if dy > 0 else 0.6) if r < 0.9 else tone("sc_alloy", 0.55)
        return tone("sc_alloy", 0.78 if math.floor(along * 2) % 2 == 0 else 0.32)
    cyl(m, "hub", c, 3, a0 - 1.0, a0 + 1.0, flat("sc_alloy", 3)(decal=hub), axis=axis)


# ================================================================================================
# The rig
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


_KITS = {}


def sidecar_bike(colour="sc_maroon", trim="sc_cream", leather="sc_leather", whitewall=False, name="sidecar_bike"):
    return sidecar_kit(colour, trim, leather, whitewall, name).body


def sidecar_kit(colour="sc_maroon", trim="sc_cream", leather="sc_leather", whitewall=False, name="sidecar_bike"):
    key = (colour, trim, leather, whitewall, name, tuple(sorted(REMAP.items())))
    if key in _KITS:
        return _KITS[key]
    kit = Kit(name, ("screen",))
    m = kit.body
    ctx = SimpleNamespace(C=colour, T=trim, L=leather, WW=whitewall)
    _front(m, ctx)
    _tank_saddle(m, ctx)
    _frame(m, ctx)
    _engine(m, ctx)
    _rear(m, ctx)
    _sidecar(m, kit, ctx)
    m.display = presets.furniture(gui_scale=0.4)
    _KITS[key] = kit
    return kit


BLACK = solid("sc_black", 0.62, 0.38, 0.1, 0.5)
RUBBER = solid("sc_rubber", 0.4, 0.22, 0.05)
ALLOY = solid("sc_alloy", 0.8, 0.5, 0.2, 0.62)
CHROME = chrome_paint()


def _fender(m, name, ctx, cz, cx, half, angles, tip=None, sides=(-1, 1)):
    """A valanced fender round the wheel at (WY, cz), centred on x = cx: a crown of panels (the trim colour down its
    middle) and a valance each side reaching down past the tyre's shoulder."""
    C, T = ctx.C, ctx.T
    r1, r2 = WRAD + 0.75, WRAD + 1.45

    def crown(c):
        x, y, z = c.p
        ax = abs(x - cx)
        if c.face in ("east", "west"):
            return tone(C, 0.30 + 0.22 * smooth((y - 2) / 12))
        a = math.degrees(math.atan2(z - cz, y - WY))
        if tip is not None and (a < tip[0] or a > tip[1]):
            return tone("sc_chrome", 0.92 if c.face == "up" else 0.6)
        if c.face in ("down", "north", "south") and math.hypot(y - WY, z - cz) < r1 + 0.2:
            return tone(C, 0.12)                                         # its underside
        if ax < 0.7:
            return tone(T, 0.72)                                          # the stripe down its crown
        if ax < 1.0:
            return tone(C, 0.3)
        return tone(C, 0.72 if ax < half - 0.5 else 0.5)
    arc(m, name, (WY, cz), angles, r1, r2, cx - half, cx + half, flat(C, 4), crown)

    def valance(c):
        x, y, z = c.p
        r = math.hypot(y - WY, z - cz)
        outside = (c.face == "east" and x > cx) or (c.face == "west" and x < cx)
        if not outside:
            return tone(C, 0.1)
        if r < WRAD - 0.1:
            return tone(C, 0.22)                                          # its rolled lower edge
        return tone(C, 0.34 + 0.24 * smooth((y - 2) / 12))
    for s in sides:
        lo, hi = _x(cx, s * (half - 0.5), s * half)
        arc(m, name + "_valance", (WY, cz), angles, WRAD - 0.6, r1, lo, hi, flat(C, 4), valance,
            drop=("outer",), upright=True, inner=False)


def _front(m, ctx):
    """The springer fork, the front fender, the headlamp with its speedo and horn, the handlebars."""
    C, T = ctx.C, ctx.T
    m.group("front")
    blk = BLACK
    for s in (-1, 1):
        rod(m, "fork_leg", fork(1.0, -1.1), fork(11.5, -1.1), 0.6, blk, x=_x(BX, s * 2.75, s * 3.35), depth=0.7)
        rod(m, "fork_spring_leg", fork(0.5, 0.95), fork(8.0, 0.95), 0.6, blk, x=_x(BX, s * 2.75, s * 3.35), depth=0.6)

        def coil(c):
            # the coil: bright and dark turns up the spring (its box stands upright before it is turned)
            y = getattr(c, "local", c.p)[1]
            if c.face in ("up", "down"):
                return tone("sc_chrome", 0.6)
            return tone("sc_chrome", 0.87 if math.floor(y * 1.5) % 2 == 0 else 0.25)
        sp = flat("sc_chrome", 4)(decal=coil)
        rod(m, "fork_spring", fork(8.0, 0.95), fork(12.0, 0.95), 1.1, sp, x=_x(BX, s * 2.5, s * 3.6), depth=1.1)
        rod(m, "rocker", fork(0.0, -1.6), fork(0.0, 1.4), 1.0, blk, x=_x(BX, s * 2.3, s * 3.8), depth=1.0)
    rod(m, "crown", fork(10.75, -1.9), fork(10.75, 1.3), 0.8, blk, x=(BX - 3.7, BX + 3.7), depth=0.8)
    rod(m, "crown", fork(12.2, -1.6), fork(12.2, 1.6), 0.6, CHROME, x=(BX - 3.8, BX + 3.8), depth=0.6)

    # the front fender: deep valances, the trim colour down its crown, a chrome tip low at the front
    _fender(m, "front_fender", ctx, ZF, BX, 2.25, [-112.5, -90, -67.5, -45, -22.5, 0, 22.5])
    for s in (-1, 1):
        lo, hi = _x(BX, s * 2.25, s * 2.75)
        m.box("fender_stay", (lo, WY + 1.5, ZF - 4.25), (hi, WY + 1.9, ZF + 0.6), CHROME)

    # the headlamp: an octagonal chrome bucket, its lens a round glass in a bright bezel, the speedometer on top
    hy, hz = HL

    def bucket(c):
        x, y, z = c.p
        if c.face == "north":
            return tone("sc_chrome", 0.87 if y > hy else 0.7)              # the bezel's face
        if c.face == "south":
            return tone("sc_chrome", 0.35)
        if c.face == "up":
            return tone("sc_chrome", 0.87)
        if c.face == "down":
            return tone("sc_chrome", 0.2)
        if y > hy + 0.8:
            return tone("sc_chrome", 0.86)
        if y > hy - 0.3:
            return tone("sc_chrome", 0.14)                                # the dark horizon round its waist
        return tone("sc_chrome", 0.42)
    octa(m, "headlamp", (BX, hy), 2.25, 2.25, 0.75, HL_FACE, hz + 1.75, flat("sc_chrome", 4), bucket, t=0.6)

    def lens(c):
        x, y = c.p[0] - BX, c.p[1] - hy
        r = math.hypot(x, y)
        if c.face != "north":
            return ("raw", (120, 110, 80, 255)) if r < 1.75 else CLEAR
        if r > 1.85:
            return CLEAR
        if (x + 0.6) ** 2 + (y - 0.7) ** 2 < 0.35:
            return ("raw", (255, 255, 246, 255))                          # the glass's glint
        if r < 0.75:
            return ("raw", (255, 236, 170, 255))                          # the bulb in the reflector
        if r > 1.3:
            return ("raw", (196, 188, 160, 255))                          # the reflector's rim behind the glass
        return ("raw", (236, 222, 176, 255))
    m.group("lights")
    m.box("headlamp_lens", (BX - 2.0, hy - 2.0, HL_FACE - 0.25), (BX + 2.0, hy + 2.0, HL_FACE),
          Paint("sc_lamp", 2, noise=0, light=False, glow=True, decal=lens))
    m.group("front")

    def speedo(c):
        if c.face == "up":
            x, z = c.p[0] - BX, c.p[2] - (hz + 0.5)
            if abs(x) < 0.6 and abs(z) < 0.6:
                return ("raw", (236, 226, 196, 255)) if not (x < 0 and z < 0) else ("raw", (200, 60, 50, 255))
            return tone("sc_chrome", 0.87)
        return tone("sc_chrome", 0.7 if c.face in ("north", "west") else 0.4)
    m.box("speedo", (BX - 1.0, hy + 2.25, hz - 0.5), (BX + 1.0, hy + 3.0, hz + 1.5), flat("sc_chrome", 4)(decal=speedo))
    # the horn under the lamp: a chrome trumpet facing forward
    m.box("horn", (BX + 0.3, hy - 3.45, hz - 1.6), (BX + 1.7, hy - 2.25, hz + 0.5), flat("sc_chrome", 4)(
        decal=lambda c: (tone("sc_chrome", 0.08) if 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1 else tone("sc_chrome", 0.9))
        if c.face == "north" else chrome_decal()(c)))
    for s in (-1, 1):
        lo, hi = _x(BX, s * 2.25, s * 2.85)
        m.box("lamp_bracket", (lo, hy - 0.4, hz - 0.5), (hi, hy + 0.4, fork(11.0, -0.5)[1] - 0.3), BLACK)

    # the handlebars: a riser on the upper crown, a bar across, out and back at 22.5 degrees to the grips
    cy, cz = fork(12.75, -0.25)
    m.box("riser", (BX - 0.5, cy - 0.6, cz - 0.5), (BX + 0.5, cy + 0.6, cz + 0.5), CHROME)
    by = cy + 0.6
    bar = flat("sc_chrome", 4)(decal=lambda c: tone("sc_chrome", 0.87 if c.face == "up" else 0.66 if c.face in ("north", "west") else 0.36))
    m.box("bar", (BX - 2.5, by, cz - 0.3), (BX + 2.5, by + 0.6, cz + 0.3), bar)
    for s in (-1, 1):
        x0 = BX + s * 2.5
        lo, hi = sorted((x0, x0 + s * 2.5))
        m.box("bar", (lo, by, cz - 0.3), (hi, by + 0.6, cz + 0.3), bar, rotation=("y", -s * 22.5, (x0, by, cz)))
        gx = x0 + s * 2.5 * math.cos(math.radians(22.5)) - s * 0.1
        gz = cz + 2.5 * math.sin(math.radians(22.5))
        lo, hi = sorted((gx, gx + s * 1.3))
        m.box("bar", (lo, by, gz - 0.3), (hi, by + 0.6, gz + 0.3), bar)

        def grip(c):
            if c.face in ("east", "west"):
                return tone("sc_rubber", 0.3)
            return tone("sc_rubber", 0.45 if math.floor(c.p[0] * 2) % 2 else 0.12)
        lo, hi = sorted((gx + s * 1.3, gx + s * 3.6))
        m.box("grip", (lo, by - 0.15, gz - 0.45), (hi, by + 0.75, gz + 0.45), flat("sc_rubber", 1)(decal=grip))
        # the lever: a chrome blade ahead of the grip
        lo, hi = sorted((gx + s * 0.6, gx + s * 3.4))
        m.box("lever", (lo, by + 0.1, gz - 1.1), (hi, by + 0.4, gz - 0.45), CHROME)
    # one round mirror on a stalk from the left perch
    mx, mz = BX - 2.5 - 2.5 * math.cos(math.radians(22.5)) - 0.7, cz + 2.5 * math.sin(math.radians(22.5)) - 0.6
    m.box("mirror_stalk", (mx - 0.2, by + 0.6, mz - 0.2), (mx + 0.2, by + 2.2, mz + 0.2), CHROME)

    def mirror(c):
        if c.face == "south":
            return tone("sc_glass", 0.7 if c.p[1] > by + 2.9 else 0.3)
        return tone("sc_chrome", 0.8 if c.face in ("up", "north", "west") else 0.45)
    m.box("mirror", (mx - 0.75, by + 2.2, mz - 0.25), (mx + 0.75, by + 3.7, mz + 0.25), flat("sc_chrome", 4)(decal=mirror))


TZ0, TZ1 = -7.75, 1.5         # the tank's nose and tail (z)
TY0, TY1 = 12.25, 17.25       # its belly and crown (y)
TK = -3.0                     # where its sides start to narrow (z)


def _tank_saddle(m, ctx):
    """The teardrop tank (two-tone, chrome crown strip, filler caps, knee pads, shift lever) and the sprung saddle."""
    C, T, L = ctx.C, ctx.T, ctx.L
    m.group("tank")
    W1, W2 = 3.25, 1.25                     # half width ahead of TK, at the tail

    def tank(c):
        x, y, z = c.p
        ax = abs(x - BX)
        if c.face == "down":
            return tone(C, 0.1)
        if c.face == "up" or y > TY1 - 0.05:
            if ax < 0.6:
                return tone("sc_chrome", 0.87)
            return tone(C, 0.86 if 1.0 < ax < 1.8 else 0.7)
        if c.face == "north":
            return tone(C, 0.62)
        if c.face == "south":
            return tone(C, 0.4)
        # the sides: a flash of the trim colour sweeping back from the nose, edged by a fine dark line
        # a flash: a broad band from the nose, narrowing to a point at the knee pads
        half = 1.25 - max(0.0, z + 5.5) * 0.36
        if TZ0 + 0.5 <= z and abs(y - 14.75) < half:
            return tone(T, 0.46 + 0.28 * smooth((y - 13.5) / 2.5))
        if TZ0 + 0.5 <= z and abs(y - 14.75) < half + 0.5:
            return tone(C, 0.2)                                          # its dark edge
        # the knee pads: ribbed rubber in a chrome rim on the narrowing sides
        if z > TK + 0.6 and 13.0 <= y < 16.0 and z < TZ1 - 0.6:
            if y < 13.5 or y >= 15.5:
                return tone("sc_chrome", 0.8)
            return tone("sc_rubber", 0.4 if math.floor(z * 1.5) % 2 else 0.12)
        return tone(C, 0.3 + 0.36 * smooth((y - TY0) / (TY1 - TY0)))
    tp = flat(C, 4)
    # the front of the tank: a core, its crown, the 45-degree edges along the top, a 45-degree brow at the nose
    m.box("tank", (BX - W1, TY0, TZ0), (BX + W1, TY1 - 1, TK), tp(decal=tank))
    m.box("tank", (BX - (W1 - 1), TY1 - 1, TZ0 + 1), (BX + (W1 - 1), TY1, TK), tp(decal=tank))
    for s in (-1, 1):
        plank(m, "tank_edge", "z", (BX + s * (W1 - 1), TY1), (BX + s * W1, TY1 - 1), (TZ0 + 0.3, TK), 0.75, tp, decal=tank, buried=True)
    plank(m, "tank_brow", "x", (TZ0, TY1 - 1), (TZ0 + 1, TY1), (BX - (W1 - 1), BX + (W1 - 1)), 0.75, tp, decal=tank)
    # the tail: narrower, its sides drawing in at 22.5 degrees, a lid over the triangles between, a 45-degree end
    m.box("tank", (BX - W2, TY0 + 0.5, TK), (BX + W2, TY1, TZ1 - 1), tp(decal=tank))
    taper = (W1 - W2) / math.tan(math.radians(22.5))
    for s in (-1, 1):
        plank(m, "tank_side", "y", (TK, BX + s * W1), (TK + taper, BX + s * W2), (TY0 + 0.25, TY1 - 1), 0.75, tp,
              decal=tank, below=s > 0)
        lo, hi = (BX + s * W1, BX + s * (W1 - 1.75)) if s > 0 else (BX + s * W1, BX + s * (W1 - 1.75))
        plank(m, "tank_lid", "y", (TK, BX + s * (W1 - 0.05)), (TK + taper, BX + s * (W2 - 0.05)), (TY1 - 1, TY1 - 0.25), 1.6,
              tp, decal=tank, below=s > 0, faces={"down": None, "west" if s > 0 else "east": None, "north": None})
    plank(m, "tank_tail", "x", (TZ1 - 1, TY1), (TZ1, TY1 - 1), (BX - W2, BX + W2), 0.75, tp, decal=tank)
    m.box("tank", (BX - W2, TY0 + 0.5, TZ1 - 1), (BX + W2, TY1 - 1, TZ1), tp(decal=tank))
    # the chrome strip down the crown, two filler caps
    m.box("tank_strip", (BX - 0.3, TY1, TZ0 + 1.2), (BX + 0.3, TY1 + 0.25, TZ1 - 1.2), CHROME)
    for s in (-1, 1):
        m.box("filler_cap", (BX + s * 1.6 - 0.5, TY1, TZ0 + 2.0), (BX + s * 1.6 + 0.5, TY1 + 0.45, TZ0 + 3.0), CHROME)
    # the hand-shift lever up the tank's left side: a chrome rod in a gate, a black knob
    rod(m, "shift_lever", (12.3, -0.6), (15.4, -2.4), 0.45, CHROME, x=(BX - W1 - 0.9, BX - W1 - 0.45))
    m.box("shift_knob", (BX - W1 - 1.2, 15.2, -3.0), (BX - W1 - 0.15, 16.2, -2.0), solid("sc_black", 0.75, 0.45, 0.2))

    m.group("saddle")
    # the sprung solo saddle: a narrow nose on a pivot, a wide pan behind (the sides drawing out at 45 degrees), the
    # top's long edges bevelled, a raised cantle, a dark rolled edge, two chrome coil springs under its back
    s0, s1, s2 = 0.25, 2.25, 6.5            # its nose, where the pan is full width, its back (z)
    yb, yt = SADDLE - 1.4, SADDLE

    def leather(c):
        x, y, z = c.p
        ax = abs(x - BX)
        if c.face == "down":
            return tone("sc_black", 0.2)
        if c.face == "up" or y > yt - 0.1:
            if ax < 0.4 and s1 < z < s2 - 1:
                return tone(L, 0.45)                                       # the stitched seam down its middle
            return tone(L, 0.82 if z > s2 - 1.2 else 0.68)
        if y < yb + 0.5:
            return tone(L, 0.18)                                           # the rolled edge, stitched
        return tone(L, 0.44)
    lp = flat(L, 3)
    m.box("saddle", (BX - 1.0, yb + 0.3, s0), (BX + 1.0, yt, s1), lp(decal=leather))
    m.box("saddle", (BX - 1.75, yb, s1), (BX + 1.75, yt, s2), lp(decal=leather))
    for s in (-1, 1):
        plank(m, "saddle_edge", "z", (BX + s * 1.75, yt), (BX + s * 2.6, yt - 0.85), (s1, s2), 0.6, lp, decal=leather, buried=True)
        plank(m, "saddle_flare", "y", (s1 - 0.75, BX + s * 1.0), (s1, BX + s * 1.75), (yb + 0.3, yt), 0.6, lp,
              decal=leather, below=s > 0)
        lo, hi = _x(BX, s * 1.75, s * 2.6)
        m.box("saddle", (lo, yb, s1), (hi, yt - 0.85, s2), lp(decal=leather))
    plank(m, "saddle_cantle", "x", (s2 - 0.9, yt), (s2, yt + 0.9 * math.tan(math.radians(45))), (BX - 1.75, BX + 1.75),
          0.6, lp, decal=leather, below=True)
    m.box("saddle_pivot", (BX - 0.5, 13.6, s0 + 0.2), (BX + 0.5, yb + 0.3, s0 + 1.2), CHROME)

    def spring(c):
        if c.face in ("up", "down"):
            return tone("sc_chrome", 0.6)
        return tone("sc_chrome", 0.87 if math.floor(c.p[1] * 2) % 2 == 0 else 0.25)
    for s in (-1, 1):
        cx = BX + s * 1.6
        m.box("saddle_spring", (cx - 0.55, 12.95, 4.75), (cx + 0.55, yb, 5.85), flat("sc_chrome", 4)(decal=spring))
    m.box("spring_bar", (BX - 2.2, 12.5, 4.6), (BX + 2.2, 12.95, 6.0), BLACK)


def _frame(m, ctx):
    """The black cradle frame: the steering neck, the top tube, twin down tubes, the cradle, the seat post, the stays,
    the plungers' covers at the rear axle."""
    m.group("frame")
    fb = BLACK
    ny0, nz0 = fork(9.0, -2.6)
    rod(m, "neck", (ny0, nz0), fork(12.75, -2.6), 1.4, fb, x=(BX - 0.7, BX + 0.7), depth=1.4)
    m.box("top_tube", (BX - 0.45, 12.0, nz0 + 0.2), (BX + 0.45, 13.0, 6.25), fb)
    for s in (-1, 1):
        lo, hi = _x(BX, s * 1.25, s * 2.1)
        rod(m, "down_tube", (ny0 + 0.5, nz0 - 0.2), (2.4, -3.6), 0.85, fb, x=(lo, hi))
        m.box("cradle", (lo, 1.6, -4.0), (hi, 2.45, 6.0), fb)
        m.box("chain_stay", (lo, WY - 2.45, 6.0), (hi, WY - 1.6, ZR - 0.6), fb)
        rod(m, "seat_stay", (13.0, 5.8), (WY + 3.6, ZR - 0.6), 0.85, fb, x=(lo, hi))
    m.box("seat_post", (BX - 0.45, 2.0, 5.3), (BX + 0.45, 12.0, 6.25), fb)
    # plunger suspension: a chrome cover over each spring at the rear axle
    for s in (-1, 1):
        cx = BX + s * 1.75
        m.box("plunger_cover", (cx - 0.55, WY - 2.4, ZR - 0.55), (cx + 0.55, WY + 3.6, ZR + 0.55), flat("sc_chrome", 4)(
            decal=lambda c: tone("sc_chrome", 0.87 if c.face == "up" else 0.75 if c.p[1] > WY + 2.6 or c.p[1] < WY - 1.4 else 0.35)))


V_BASE = {"front": (7.0, -2.6), "rear": (7.0, 0.6)}       # each cylinder's foot (y, z)


def _engine(m, ctx):
    """The V-twin: crankcase and gearbox, the primary and timing covers, two finned cylinders leaning 22.5 degrees
    fore and aft, the carburettor and air cleaner, the exhausts, the footboards, the kick starter."""
    m.group("engine")

    def case(c):
        x, y, z = c.p
        if c.face == "up":
            return tone("sc_alloy", 0.82)
        if c.face == "down":
            return tone("sc_alloy", 0.2)
        if c.face == "east":
            r = math.hypot(y - 5.5, z + 1.75)
            if r < 1.6:
                return tone("sc_chrome", 0.87 if y > 5.5 else 0.6) if r > 0.5 else tone("sc_chrome", 0.3)   # timing cover
            if abs(r - 1.9) < 0.3:
                return tone("sc_alloy", 0.3)
        if math.floor(y) == 3:
            return tone("sc_alloy", 0.32)                               # the joint round the case
        return tone("sc_alloy", 0.62 if y > 5 else 0.5)
    ap = flat("sc_alloy", 3)
    m.box("crankcase", (BX - 2.1, 4.0, -5.0), (BX + 2.1, 7.6, 2.5), ap(decal=case))
    m.box("crankcase", (BX - 2.1, 3.0, -4.0), (BX + 2.1, 4.0, 1.5), ap(decal=case))
    plank(m, "crankcase_edge", "x", (-5.0, 4.0), (-4.0, 3.0), (BX - 2.1, BX + 2.1), 0.75, ap, decal=case, below=False)
    plank(m, "crankcase_edge", "x", (1.5, 3.0), (2.5, 4.0), (BX - 2.1, BX + 2.1), 0.75, ap, decal=case, below=False)
    m.box("gearbox", (BX - 1.8, 3.6, 2.5), (BX + 1.8, 7.0, 6.0), ap(decal=case))

    # the chrome primary cover on the left: a long cover from the crank back to the gearbox, its ends bevelled
    def prim(c):
        if c.face == "west":
            y = c.p[1]
            return tone("sc_chrome", 0.87 if y > 6.6 else 0.12 if y > 5.6 else 0.45)
        return chrome_decal()(c)
    pp = flat("sc_chrome", 4)
    m.box("primary", (BX - 2.85, 3.6, -1.0), (BX - 2.1, 7.6, 4.5), pp(decal=prim))
    plank(m, "primary_edge", "x", (-2.2, 6.4), (-1.0, 7.6), (BX - 2.85, BX - 2.1), 0.8, pp, decal=prim)
    plank(m, "primary_edge", "x", (4.5, 7.6), (5.7, 6.4), (BX - 2.85, BX - 2.1), 0.8, pp, decal=prim)
    m.box("primary", (BX - 2.85, 3.6, -2.2), (BX - 2.1, 6.4, -1.0), pp(decal=prim))
    m.box("primary", (BX - 2.85, 3.6, 4.5), (BX - 2.1, 6.4, 5.7), pp(decal=prim))

    # the cylinders: an iron barrel ringed by fins, a finned alloy head, a chrome rocker cover; leaning 22.5 degrees
    def fins(ramp, hi_t, lo_t):
        def d(c):
            if c.face == "up":
                return tone(ramp, hi_t)
            if c.face == "down":
                return tone(ramp, 0.12)
            return tone(ramp, lo_t if c.face in ("south", "east") else (hi_t + lo_t) / 2)
        return flat(ramp, 2)(decal=d)
    iron_fin, alloy_fin = fins("sc_iron", 0.9, 0.45), fins("sc_alloy", 0.95, 0.5)
    core = solid("sc_iron", 0.3, 0.18, 0.05)
    for cname, sgn in (("front", -1), ("rear", 1)):
        y0, z0 = V_BASE[cname]
        rot = ("x", 22.5 * sgn, (BX, y0, z0))
        m.box("barrel", (BX - 1.0, y0, z0 - 1.0), (BX + 1.0, y0 + 4.7, z0 + 1.0), core, rotation=rot)
        for k in range(3):
            fy = y0 + 0.9 + k * 0.85
            m.box("fin", (BX - 1.75, fy, z0 - 1.75), (BX + 1.75, fy + 0.45, z0 + 1.75), iron_fin, rotation=rot)
        for k in range(2):
            fy = y0 + 3.4 + k * 0.8
            m.box("head_fin", (BX - 2.0, fy, z0 - 2.0), (BX + 2.0, fy + 0.45, z0 + 2.0), alloy_fin, rotation=rot)
        m.box("rocker_cover", (BX - 1.4, y0 + 4.7, z0 - 1.4), (BX + 1.4, y0 + 5.3, z0 + 1.4),
              chrome_paint(), rotation=rot)
        m.box("plug", (BX - 2.6, y0 + 3.6, z0 - 0.3), (BX - 1.0, y0 + 4.1, z0 + 0.3), solid("sc_white", 0.9, 0.7, 0.4), rotation=rot)

    # the carburettor in the vee on the right, its round chrome air cleaner
    m.box("carb", (BX + 0.6, 9.0, -1.6), (BX + 2.2, 10.6, -0.2), ALLOY)

    def cleaner(c):
        if c.face == "east":
            r = math.hypot(c.p[1] - 9.8, c.p[2] + 0.9)
            if r < 0.6:
                return tone("sc_chrome", 0.35)                            # its centre nut
            if 1.0 < r < 1.35:
                return tone("sc_chrome", 0.55)                            # a pressed ring
            return tone("sc_chrome", 0.87 if c.p[1] > 9.8 else 0.72)
        return chrome_decal()(c)
    cyl(m, "air_cleaner", (9.8, -0.9), 4, BX + 2.2, BX + 3.1, flat("sc_chrome", 4)(decal=cleaner), axis="x")

    # the exhausts: from each head down the left side, two pipes to stacked fishtail mufflers
    ex = BX - 3.4
    pipe = flat("sc_chrome", 4)(decal=lambda c: tone("sc_chrome", 0.87 if c.face == "up" else 0.7 if c.face in ("north", "west") else 0.32))
    lows = {"front": 2.9, "rear": 5.3}
    # front: forward and down out of the front head at 45 degrees, down in front of the crankcase, out to the left
    fl = lows["front"]
    rod(m, "header", (10.4, -5.0), (8.2, -7.2), 0.9, pipe, x=(BX - 1.0, BX - 0.1))
    m.box("header", (BX - 1.0, fl - 0.45, -7.65), (BX - 0.1, 8.5, -6.75), pipe)
    m.box("header", (ex - 0.45, fl - 0.45, -7.65), (BX - 1.0, fl + 0.45, -6.75), pipe)
    # rear: back out of the rear head, out to the left, down at 45 degrees beside the gearbox
    ry, rz = 10.6, 3.0
    m.box("header", (ex - 0.45, ry - 0.45, rz - 0.45), (BX - 0.8, ry + 0.45, rz + 0.45), pipe)
    rod(m, "header", (ry, rz), (lows["rear"], rz + (ry - lows["rear"])), 0.9, pipe, x=(ex - 0.45, ex + 0.45))
    starts = {"front": -6.75, "rear": rz + (ry - lows["rear"]) - 0.4}
    for kname, low in lows.items():
        if starts[kname] < 8.5:
            m.box("pipe", (ex - 0.45, low - 0.45, starts[kname]), (ex + 0.45, low + 0.45, 8.5), pipe)
        # the muffler: a long can, then the fishtail flaring out at 22.5 degrees, a dark slot in its end
        mp = flat("sc_chrome", 4)(decal=chrome_decal())
        m.box("muffler", (ex - 0.85, low - 0.85, 8.5), (ex + 0.85, low + 0.85, 15.0), mp)

        def fish(c, low=low):
            if c.face == "south":
                if abs(c.p[1] - low) < 0.6 and abs(c.p[0] - ex) < 0.4:
                    return tone("sc_rubber", 0.0)
                return tone("sc_chrome", 0.6)
            return chrome_decal()(c)
        m.box("fishtail", (ex - 0.65, low - 1.1, 15.0), (ex + 0.65, low + 1.1, 17.75), flat("sc_chrome", 4)(decal=fish))
    m.box("muffler_hanger", (ex + 0.95, 4.0, 12.0), (BX - 2.1, 4.5, 12.6), BLACK)

    # the footboards: rubber mats on chrome rims
    for s, (x0, x1) in ((-1, (BX - 6.4, BX - 3.2)), (1, (BX + 2.7, BX + 5.3))):
        m.box("footboard", (x0, 3.6, -6.0), (x1, 3.9, -1.0), CHROME)

        def mat(c):
            if c.face == "up":
                return tone("sc_rubber", 0.4 if math.floor(c.p[2] * 1.5) % 2 else 0.1)
            return tone("sc_rubber", 0.25)
        m.box("footboard_mat", (x0 + 0.25, 3.9, -5.75), (x1 - 0.25, 4.2, -1.25), flat("sc_rubber", 1)(decal=mat))
        lo, hi = sorted((BX + s * 2.1, x1 if s < 0 else x0))
        m.box("footboard_arm", (lo, 2.9, -3.8), (hi, 3.6, -3.2), BLACK)


def _rear(m, ctx):
    """The rear fender, its tail lamp and the plate holder, the chain guard, the oil tank, the saddlebag."""
    C, T, L = ctx.C, ctx.T, ctx.L
    m.group("rear")
    _fender(m, "rear_fender", ctx, ZR, BX, 2.25, [-22.5, 0, 22.5, 45, 67.5, 90, 112.5])

    # the tail lamp: a round chrome housing on the fender's back, a red lens in a chrome ring
    ty, tz = TAIL_LAMP
    housing = flat("sc_chrome", 4)
    m.box("tail_lamp", (BX - 1.1, ty - 1.1, tz - 1.6), (BX + 1.1, ty + 1.1, tz),
          housing(decal=lambda c: tone("sc_chrome", 0.85) if c.face == "south" else chrome_decal()(c)))

    def tail_lens(c):
        r = math.hypot(c.p[0] - BX, c.p[1] - ty)
        if r > 0.95:
            return CLEAR
        return ("raw", (255, 90, 80, 255)) if r < 0.45 else ("raw", (190, 24, 30, 255))
    m.group("lights")
    m.box("tail_lens", (BX - 1.0, ty - 1.0, tz), (BX + 1.0, ty + 1.0, tz + 0.2), Paint("sc_red", 2, noise=0, light=False, glow=True, decal=tail_lens))
    m.group("rear")
    # the plate holder under the lamp: a chrome frame, the blank plate (vehicles.yml plates draws the number on it)
    py, pz = PLATE

    def plate(c):
        if c.face == "south":
            if abs(c.p[0] - BX) > 2.6 or abs(c.p[1] - py) > 1.3:
                return tone("sc_chrome", 0.8)
            return tone("sc_white", 0.85)
        return tone("sc_chrome", 0.6)
    m.box("plate", (BX - 3.0, py - 1.7, pz - 0.3), (BX + 3.0, py + 1.7, pz), flat("sc_chrome", 4)(decal=plate))
    m.box("plate_bracket", (BX - 0.4, py + 1.7, pz - 2.0), (BX + 0.4, py + 2.5, pz - 0.3), BLACK)
    m.box("tail_bracket", (BX - 0.35, ty - 1.9, tz - 1.2), (BX + 0.35, ty - 1.1, tz - 0.4), CHROME)

    # the chain guard on the right, the oil tank under the saddle on the right
    m.box("chain_guard", (BX + 1.1, WY - 0.7, 5.5), (BX + 1.6, WY + 1.3, ZR - 1.2), flat(C, 4)(decal=enamel(C, WY - 0.7, WY + 1.3)))
    m.box("oil_tank", (BX + 1.6, 8.8, 2.5), (BX + 3.6, 11.6, 5.5), flat(C, 4)(decal=enamel(C, 8.8, 11.6)))

    # the leather saddlebag on the left: a box, its flap's edge, two buckled straps, its bottom bevelled
    def bag(c):
        x, y, z = c.p
        if c.face == "down":
            return tone(L, 0.12)
        if math.floor(z) in (12, 14):
            if c.face == "west" and math.floor(y) == 9:
                return tone("sc_chrome", 0.66)                           # the buckles
            return tone(L, 0.22)                                         # the straps
        if c.face == "up":
            return tone(L, 0.75)
        if math.floor(y) == 9 and c.face == "west":
            return tone(L, 0.3)                                          # the flap's edge
        if y > 9.5:
            return tone(L, 0.6)
        return tone(L, 0.42)
    bp = flat(L, 3)
    bx0, bx1 = BX - 6.1, BX - 3.3
    m.box("saddlebag", (bx0, 7.2, 10.8), (bx1, 11.0, 16.2), bp(decal=bag))
    plank(m, "saddlebag_edge", "z", (bx0, 7.2), (bx0 + 0.8, 6.4), (11.2, 15.8), 0.6, bp, decal=bag, below=False)
    m.box("saddlebag", (bx0 + 0.8, 6.4, 10.8), (bx1, 7.2, 16.2), bp(decal=bag))
    m.box("bag_rail", (bx1, 10.4, 10.6), (BX - 2.25, 10.9, 16.0), BLACK)


def _sidecar(m, kit, ctx):
    C, T, L = ctx.C, ctx.T, ctx.L
    m.group("sidecar")
    yc = (TB + TT) / 2

    def tub(c):
        """The tub's paint: the trim colour above the spear, the paint below, the chrome spear between; a soft light
        down its sides, its deck lit."""
        x, y, z = c.p
        ax = abs(x - SX)
        if c.face == "down":
            return tone(C, 0.1)
        if abs(y - SPEAR) < 0.45 and c.face in ("east", "west"):
            return tone("sc_chrome", 0.87 if y > SPEAR else 0.55)
        if y > SPEAR:
            if c.face == "up" or y > TT - 0.1:
                return tone(T, 0.8 if 1.5 < ax < 3.0 else 0.66)
            return tone(T, 0.42 + 0.3 * smooth((y - SPEAR) / (TT - SPEAR)))
        return tone(C, 0.26 + 0.3 * smooth((y - TB) / (SPEAR - TB)))
    tp = flat(C, 4)
    # the full section ahead of the cockpit and beside it (the cockpit cut out of its middle), then behind it
    octa(m, "tub", (SX, yc), TW, (TT - TB) / 2, TC, TUB_Z0, WELL_Z0, tp, tub, bottom=False)
    for s in (-1, 1):
        plank(m, "tub_keel", "z", (SX + s * (TW - TC), TB), (SX + s * TW, TB + TC), (TUB_Z0, TUB_Z1 + 1.0), TC * 0.72 + 0.05, tp,
              decal=tub, below=False, buried=True)
    # beside the cockpit: the floor, thin side walls under the top chamfers (the inside lined in dark leather)
    inner = TW - TC

    def wall(c):
        x, y, z = c.p
        outside = (c.face == "east" and x > SX) or (c.face == "west" and x < SX)
        if c.face in ("east", "west") and not outside and y > WELL_FLOOR - 0.1:
            return tone(L, 0.2)                                          # the cockpit's leather lining
        if c.face == "down" and y > WELL_FLOOR:
            return tone(L, 0.12)
        return tub(c)
    wp = tp(decal=wall)
    m.box("tub", (SX - inner, TB, WELL_Z0), (SX + inner, WELL_FLOOR, WELL_Z1), wp)
    for s in (-1, 1):
        lo, hi = _x(SX, s * inner, s * TW)
        if WELL_FLOOR > TB + TC:
            m.box("tub", (lo, TB + TC, WELL_Z0), (hi, WELL_FLOOR, WELL_Z1), wp)
        lo, hi = _x(SX, s * (TW - 0.6), s * TW)
        m.box("tub_wall", (lo, WELL_FLOOR, WELL_Z0), (hi, TT - TC, WELL_Z1), wp)
        plank(m, "tub_edge", "z", (SX + s * inner, TT), (SX + s * TW, TT - TC), (WELL_Z0, WELL_Z1), 0.75, tp, decal=wall)
    octa(m, "tub", (SX, yc), TW, (TT - TB) / 2, TC, WELL_Z1, TUB_Z1 + 1.0, tp, tub, bottom=False)
    # the nose and the tail step down in rings (art-deco streamlining): the nose to a polished chrome cone
    hh = (TT - TB) / 2
    def cone(c):
        if c.face == "north":
            return tone("sc_chrome", 0.87 if c.p[1] > yc else 0.62)
        if c.face == "up":
            return tone("sc_chrome", 0.87)
        if c.face == "down":
            return tone("sc_chrome", 0.2)
        return tone("sc_chrome", 0.74 if c.p[1] > yc - 0.2 else 0.46)
    octa(m, "tub_nose", (SX, yc), TW * 0.78, hh * 0.78, 1.6, NOSE[0], TUB_Z0, tp, tub, caps=(True, False))
    octa(m, "tub_nose", (SX, yc - 0.2), TW * 0.5, hh * 0.5, 1.0, NOSE[1], NOSE[0], flat("sc_chrome", 4), cone, t=0.75,
         caps=(True, False))
    m.box("tub_nose", (SX - 1.2, yc - 1.1, NOSE[2]), (SX + 1.2, yc + 0.7, NOSE[1]), flat("sc_chrome", 4)(decal=cone))
    k = 0.72
    octa(m, "tub_tail", (SX, yc - 0.6 * (1 - k)), TW * k, hh * k, 1.4, TUB_Z1 + 1.0, TAIL_Z, tp, tub, caps=(False, True))

    # the chrome spear moulding: a bright strip along each flank, a hair proud
    for s in (-1, 1):
        lo, hi = _x(SX, s * TW, s * (TW + 0.25))
        m.box("spear", (lo, SPEAR - 0.35, TUB_Z0), (hi, SPEAR + 0.35, TUB_Z1 + 1.0), CHROME)
    # the chrome roll round the cockpit
    roll = flat("sc_chrome", 4)(decal=lambda c: tone("sc_chrome", 0.87 if c.face == "up" else 0.62 if c.face in ("north", "west") else 0.35))
    for s in (-1, 1):
        lo, hi = _x(SX, s * (inner - 0.3), s * (inner + 0.3))
        m.box("roll", (lo, TT - 0.3, WELL_Z0 - 0.3), (hi, TT + 0.25, WELL_Z1 + 0.3), roll)
    m.box("roll", (SX - (inner - 0.3), TT - 0.3, WELL_Z0 - 0.3), (SX + (inner - 0.3), TT + 0.25, WELL_Z0 + 0.3), roll)

    # the seat: a tufted cushion and a tall tufted back rest, a rolled top
    def tuft(c):
        x, y, z = c.p
        u = (x - SX + 0.5) % 2.0
        v = ((y if c.face in ("north", "south") else z) + 0.5) % 2.0
        if c.face in ("north", "south", "up") and u < 1 and v < 1:
            return tone(L, 0.28)                                         # a button's dimple
        return tone(L, 0.66 if c.face == "up" else 0.5 if c.face == "north" else 0.38)
    sp = flat(L, 3)(decal=tuft)
    sw = TW - 0.6
    m.box("seat_cushion", (SX - sw, WELL_FLOOR, 0.5), (SX + sw, 7.7, 5.0), sp)
    m.box("seat_back", (SX - sw, WELL_FLOOR, 5.0), (SX + sw, 14.8, 6.5), sp)
    m.box("floor_mat", (SX - sw, WELL_FLOOR, WELL_Z0), (SX + sw, WELL_FLOOR + 0.25, 0.5),
          flat("sc_rubber", 1)(decal=lambda c: tone("sc_rubber", 0.35 if math.floor(c.p[2]) % 2 else 0.15)))

    # the grab rail along the cockpit's outer edge
    gx = SX + inner + 0.6
    for z in (-1.0, 4.5):
        m.box("grab_post", (gx - 0.2, TT - 0.6, z - 0.2), (gx + 0.2, 15.8, z + 0.2), CHROME)
    m.box("grab_rail", (gx - 0.3, 15.8, -1.3), (gx + 0.3, 16.3, 4.8), CHROME)

    # the windscreen (its own part: it folds forward on its foot while the passenger gets in or out): a tinted pane
    # in a chrome frame, leaning back 22.5 degrees, on two black feet
    s = kit["screen"]
    rot = ("x", 22.5, (SX, SCREEN_Y, SCREEN_Z))
    SW, H = 4.5, 4.4

    def pane(c):
        if c.face not in ("north", "south"):
            return CLEAR
        u, v = c.u, c.nv - 1 - c.v
        d = u - v - 2
        if c.face == "north" and d == 0:
            return ("raw", (206, 222, 238, 255))                                         # a reflection streak
        if v == 0:
            return ("raw", (120, 140, 160, 255))                                         # a tint along its foot
        return CLEAR
    s.box("screen_glass", (SX - SW + 0.3, SCREEN_Y + 0.4, SCREEN_Z - 0.1), (SX + SW - 0.3, SCREEN_Y + H - 0.3, SCREEN_Z + 0.1),
          Paint("sc_glass", 1, noise=0, light=False, decal=pane), rotation=rot)
    frame = flat("sc_chrome", 4)(decal=lambda c: tone("sc_chrome", 0.87 if c.face == "up" else 0.75 if c.face in ("north", "west") else 0.4))
    s.box("screen_frame", (SX - SW, SCREEN_Y + H - 0.3, SCREEN_Z - 0.25), (SX + SW, SCREEN_Y + H + 0.2, SCREEN_Z + 0.25), frame, rotation=rot)
    s.box("screen_frame", (SX - SW + 0.3, SCREEN_Y, SCREEN_Z - 0.25), (SX + SW - 0.3, SCREEN_Y + 0.4, SCREEN_Z + 0.25), frame, rotation=rot)
    for sx in (-1, 1):
        lo, hi = _x(SX, sx * (SW - 0.3), sx * SW)
        s.box("screen_frame", (lo, SCREEN_Y, SCREEN_Z - 0.25), (hi, SCREEN_Y + H - 0.3, SCREEN_Z + 0.25), frame, rotation=rot)
        lo, hi = _x(SX, sx * (SW - 1.1), sx * (SW - 0.3))
        s.box("screen_foot", (lo, SCREEN_Y - 0.25, SCREEN_Z - 0.6), (hi, SCREEN_Y + 0.6, SCREEN_Z + 0.6), BLACK)

    # the luggage rack on the tail deck: two chrome rails on legs, cross bars, a strapped leather case on it
    ry = 14.0
    for sx in (-1, 1):
        x = SX + sx * 3.0
        m.box("rack_rail", (x - 0.25, ry, 7.5), (x + 0.25, ry + 0.5, 13.5), CHROME)
        for z in (8.0, 12.75):
            m.box("rack_leg", (x - 0.2, 12.2, z - 0.2), (x + 0.2, ry, z + 0.2), CHROME)
    for z in (8.5, 10.75, 13.0):
        m.box("rack_bar", (SX - 3.1, ry + 0.05, z - 0.2), (SX + 3.1, ry + 0.45, z + 0.2), CHROME)

    def case(c):
        x, y, z = c.p
        if abs(x - (SX - 1.5)) < 0.3 or abs(x - (SX + 1.5)) < 0.3:
            if c.face in ("up", "south", "north"):
                return tone(L, 0.15)                                     # the straps
        if c.face in ("north", "south", "east", "west") and math.floor(y * 2) == math.floor((ry + 2.3) * 2):
            return tone(L, 0.2)                                          # the lid's seam
        if c.face == "up" and abs(x - SX) < 0.5 and abs(z - 11.0) < 0.5:
            return tone("sc_chrome", 0.87)                               # the clasp
        if c.face == "up":
            return tone(L, 0.72)
        return tone(L, 0.5 if c.face in ("north", "west") else 0.36)
    m.box("case", (SX - 3.1, ry + 0.45, 8.6), (SX + 3.1, ry + 3.0, 13.4), flat(L, 3)(decal=case))

    # the spare wheel standing on the tail, facing back, on a bracket
    sy, sz = SPARE
    _spoked_wheel(m, "z", (SX, sy), sz, ctx.WW, drop=("lo",), bars=4)
    m.box("spare_bracket", (SX - 0.45, sy - 0.45, TAIL_Z), (SX + 0.45, sy + 0.45, sz - 1.0), BLACK)

    # the sidecar wheel's mudguard, its stays to the tub, a running lamp on top, a red lamp on its back
    _fender(m, "sidecar_fender", ctx, SWZ, SWX, 1.6, [-67.5, -45, -22.5, 0, 22.5, 45, 67.5], sides=(1,))
    for z in (SWZ - 3.0, SWZ + 3.0):
        m.box("fender_stay", (SX + TW - 0.2, 10.6, z - 0.25), (SWX - 1.6, 11.0, z + 0.25), CHROME)
    lyy, lzz = SIDE_LAMP
    housing = flat("sc_chrome", 4)
    m.box("side_lamp", (SWX - 0.8, lyy - 0.8, lzz), (SWX + 0.8, lyy + 0.8, lzz + 1.8),
          housing(decal=lambda c: tone("sc_chrome", 0.87) if c.face in ("up", "north") else chrome_decal()(c)))
    m.box("side_lamp_stem", (SWX - 0.25, 12.6, lzz + 0.6), (SWX + 0.25, lyy - 0.8, lzz + 1.2), CHROME)
    ly, lz = GUARD_LAMP
    m.box("guard_lamp", (SWX - 0.75, ly - 0.75, lz - 1.7), (SWX + 0.75, ly + 0.75, lz), CHROME)
    m.group("lights")

    def amber(c):
        r = math.hypot(c.p[0] - SWX, c.p[1] - lyy)
        return ("raw", (255, 246, 214, 255)) if r < 0.4 else ("raw", (240, 220, 160, 255))
    m.box("side_lamp_lens", (SWX - 0.6, lyy - 0.6, lzz - 0.2), (SWX + 0.6, lyy + 0.6, lzz),
          Paint("sc_lamp", 2, noise=0, light=False, glow=True, decal=amber))
    m.box("guard_lamp_lens", (SWX - 0.55, ly - 0.55, lz), (SWX + 0.55, ly + 0.55, lz + 0.2),
          Paint("sc_red", 2, noise=0, light=False, glow=True, decal=lambda c: ("raw", (220, 40, 44, 255))))
    m.group("sidecar")

    # the chassis: two rails under the tub, cross members to the bike's frame, the axle tube, the upper struts
    fb = BLACK
    for x in (SX - 3.0, SX + 3.0):
        m.box("chassis_rail", (x - 0.45, 1.9, -10.0), (x + 0.45, 2.8, 11.0), fb)
    for z in (-3.5, 5.0):
        m.box("chassis_cross", (BX + 2.1, 1.95, z - 0.45), (SX + 3.2, 2.75, z + 0.45), fb)
    m.box("axle_tube", (SX + 3.45, WY - 0.45, SWZ - 0.45), (SWX - 1.25, WY + 0.45, SWZ + 0.45), fb)
    m.box("axle_brace", (SX + 2.55, 2.8, SWZ - 0.45), (SX + 3.45, WY - 0.45, SWZ + 0.45), fb)
    for z in (-7.0, 9.0):
        for x in (SX - 3.0, SX + 3.0):
            m.box("tub_mount", (x - 0.35, 2.8, z - 0.4), (x + 0.35, TB + 0.5, z + 0.4), fb)
    for z, x0 in ((-5.9, BX + 1.7), (6.0, BX + 0.45)):
        m.box("strut", (x0, 9.2, z - 0.4), (SX - TW, 9.9, z + 0.4), fb)


def _part(name):
    return lambda: sidecar_kit().all()[name]


ALL = {
    "sidecar_bike": sidecar_bike,
    "wheel_sidecar": wheel_sidecar,
    "sidecar_bike_screen": _part("sidecar_bike_screen"),
}
