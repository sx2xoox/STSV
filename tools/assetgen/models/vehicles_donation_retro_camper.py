"""
후원 · 레트로 캠핑밴 (retro_camper): a short, round 1960s-style camper van. An original design (no real make, no badge).

What makes it read as a camper van and not a bus:
  * short and round: 4 blocks long (the bumpers 4.25), its whole shell sculpted from 1 px cells (bake.voxels) at
    32 px a block, like the showcase 팬텀 GT (models/vehicles_showcase.py): the roof rolls over into the sides, the
    rear rounds over into the roof, the corners of the face are round in plan, a stubby nose (the face bulges out
    over the bumper, the split screen leans back above it under a little brow).
  * the face: a big V of cream over the lower colour (a raised rib along it and round the whole van at the belt), a
    split two-piece windscreen (two panes leaning back at 22.5°, a body-coloured bar between them), two big round
    headlamps in chrome rings set into the nose (a lit core in a ribbed reflector), small round amber indicators
    under them, a slim chrome bumper with overriders, a vent slot in the brow.
  * camping: a pop-up tent roof (a cream lid raised on orange-and-cream striped canvas, a little mesh window each
    side), a roof rack over the cab with a suitcase and a rolled sleeping bag, a surfboard strapped on the lid, a
    fold-out awning over the sliding door (striped canvas with a scalloped valance on two poles: out while it is
    parked, folded away while the engine runs: two movers), gingham curtains tied back in the side windows and the
    rear window, a ladder up the back, the spare wheel on the back, round tail lamps in chrome rings.
  * inside (seen through the glass): two front seats over the front wheels, a dash with a big round speedo and a
    radio, a thin big steering wheel, two plaid benches, a kitchenette across the back (a cupboard with a sink and a
    two-ring stove, a cool box), a lantern hanging from the roof (it glows).
  * chrome dish hub caps on cream rims, white-wall tyres (wheel_retro_camper, its own model).

Drawn coordinates, like the showcase car: 32 px a block, the van's ground centre at (0, 0, 0), front = north (-z),
+x = the van's RIGHT side, the driver on the left (-x). Every model is shrunk by 4 (BModel scale 4) and shown at
vehicle.body-scale 2; the wheel is drawn at 32 px a block too, shrunk by 2, shown at 1.

Tuning in mind: every colour is an argument (lower, upper, canvas, curtain, seat, board, rim): any palette ramp;
the decals ask for a share of the ramp (0..1), so a 4-step ramp works as well as these 7-step ones.

Parts (vehicles.yml movers): door_l, door_r (the cab doors, hinged at their front edge, swinging out),
slide_door (the right side's sliding door: out 2 px, back 30 px), awning (the awning's canvas: turns down about its
roller to hang folded inside the right side while the engine runs), awning_poles (its two poles: slide into the
side while the engine runs).

Seats (vehicles.yml): all at y 1.07 block (34.2 px): the cushions' top at 30, a seated head reaches 75, under the
roof's inside (76.5 at the seats).
"""

import math

import numpy as np

from stsv_assets.bake import BModel, Paint, cyl, voxels
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp, size as ramp_size
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


def S(ramp, f):
    """A share f (0 dark .. 1 light) of a ramp as a step."""
    return (ramp, max(0.0, min(1.0, f)) * (ramp_size(ramp) - 1))


# ================================================================================================
# Shape (drawn px, 32 a block)
# ================================================================================================

W = 32                      # half width
NOSE, TAIL = -64, 64
ROOF = 79                   # the roof's top
BOTTOM, FLOOR = 6, 16       # the body's lowest cells, the floor inside
BELT = 44                   # the belt line: lower colour under, cream over (the raised rib's layer is BELT - 1)
WF, WR = -38, 38            # axles
WY, WRAD = 12, 12           # wheel centre height, radius (0.75 block across)
WX = 25                     # the wheels' centre across
ARCH = 15.5                 # arch radius round the axle
RC_F, RC_R = 12, 10         # plan radius of the front / rear corners
TUMBLE = 17                 # radius the sides roll over into the roof
GL0, GL1 = 47, 64           # side windows: bottom, top
CD = (-52, -26)             # the cab doors (both sides), between their seams
SD = (-22, 8)               # the sliding door (right)
SIDE_WINDOWS = [(-18, 5), (9, 32), (36, 52)]   # behind the B pillar (the first is in the sliding door, right)
SEAT_Y = 30.25              # the cushions' top


def _sqrt(v):
    return np.sqrt(np.maximum(v, 0.0))


def hw(y):
    """The body's half width at height y (numpy): tucked in at the sill, rolled over into the roof."""
    y = np.asarray(y, dtype=float)
    w = np.full(y.shape, float(W))
    w = np.where(y < 10, W - 1.5, w)
    w = np.where(y < 8, W - 3.5, w)
    top = W - TUMBLE + _sqrt(TUMBLE ** 2 - (y - 62) ** 2)
    w = np.where(y > 62, top, w)
    return np.where(y > ROOF, -1.0, w)


def zf(y):
    """The face's z on the middle line at height y: tucked under, full at the nose, leaning a little, the screen at
    22.5°, the brow, rounding over into the roof."""
    y = np.asarray(y, dtype=float)
    t = math.tan(math.radians(22.5))
    z = np.full(y.shape, float(NOSE))
    z = np.where(y < 10, NOSE + 1.5, z)
    z = np.where(y < 8, NOSE + 3.5, z)
    z = np.where((y >= 30) & (y < 46), NOSE + (y - 30) * 0.12, z)
    z = np.where((y >= 46) & (y < 64), NOSE + 16 * 0.12 + (y - 46) * t, z)
    z = np.where((y >= 64) & (y < 67), -56.5, z)
    z = np.where(y >= 67, -44.5 - _sqrt(144 - (y - 67) ** 2), z)
    return z


def zb(y):
    y = np.asarray(y, dtype=float)
    z = np.full(y.shape, float(TAIL))
    z = np.where(y < 10, TAIL - 1.5, z)
    z = np.where(y < 8, TAIL - 3.5, z)
    z = np.where(y > 58, 43 + _sqrt(441 - (y - 58) ** 2), z)
    return z


def zfront(ax, y):
    w = hw(y)
    d = np.maximum(0.0, ax - (w - RC_F))
    return zf(y) + RC_F - _sqrt(RC_F ** 2 - d ** 2) + np.where(d > RC_F, 99, 0)


def zback(ax, y):
    w = hw(y)
    d = np.maximum(0.0, ax - (w - RC_R))
    return zb(y) - (RC_R - _sqrt(RC_R ** 2 - d ** 2)) - np.where(d > RC_R, 99, 0)


def inside(x, y, z):
    ax = np.abs(x)
    return (y >= BOTTOM) & (y <= ROOF) & (ax <= hw(y)) & (z >= zfront(ax, y)) & (z <= zback(ax, y))


def field(x, y, z):
    """A rough signed distance to the shell (its gradient is the surface's normal)."""
    ax = np.abs(x)
    w = hw(y)
    f1 = np.where(w < 0, 50, ax - w)
    f2 = zfront(ax, y) - z
    f3 = z - zback(ax, y)
    f4 = BOTTOM - y
    k = 1.6                       # a soft max: its gradient turns smoothly round the corners (no stripes on the steps)
    fs = np.stack([f1, f2, f3, f4])
    mx = fs.max(axis=0)
    return mx + k * np.log(np.exp((fs - mx) / k).sum(axis=0))


def in_arch(x, y, z):
    return (np.abs(x) > 17) & ((np.hypot(z - WF, y - WY) < ARCH) | (np.hypot(z - WR, y - WY) < ARCH))


def near_arch(x, y, z):
    return (np.hypot(z - WF, y - WY) < ARCH + 1.2) | (np.hypot(z - WR, y - WY) < ARCH + 1.2)


def round_rect(z, y, z0, z1, y0, y1, r=3.0):
    """Inside a rectangle with round corners (in a side's z/y plane)."""
    if not (z0 <= z <= z1 and y0 <= y <= y1):
        return False
    cz = min(max(z, z0 + r), z1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    return math.hypot(z - cz, y - cy) <= r


def windows(side):
    """(z0, z1) of the side windows of a side (-1 left, 1 right), the cab door's first (its front edge slants with the
    screen: cut where the side is flat)."""
    return [("cab",) + CD] + [("rear", a, b) for a, b in SIDE_WINDOWS]


def cab_front(y):
    """Where the cab door's window may start at height y (the side is flat from there)."""
    return float(zf(np.array([y]))[0]) + RC_F + 2.5


def side_hole(z, y, side=1):
    """In a side window's opening (z, y of a cell centre)."""
    for (kind, z0, z1) in windows(side):
        if kind == "cab":
            z0 = max(CD[0] + 3, cab_front(y))
            z1 = CD[1] - 3
        if round_rect(z, y, z0, z1, GL0, GL1):
            return True
    return False


SCREEN = (2, 23, 47, 63)          # the windscreen's openings: |x| from, to; y from, to
REAR_WIN = (17, 49, 61)           # the rear window: |x| <, y from, to


def screen_hole(x, y, m=0.0):
    """In one of the windscreen's two openings (m: grown by m all round)."""
    a0, a1, y0, y1 = SCREEN
    a0, a1, y0, y1 = a0 - m, a1 + m, y0 - m, y1 + m
    ax = abs(x)
    r = 3 + m
    return a0 <= ax <= a1 and y0 <= y <= y1 and math.hypot(max(0, ax - (a1 - r)), max(0, y - (y1 - r), (y0 + r) - y)) <= r


def rear_hole(x, y):
    a, y0, y1 = REAR_WIN
    return round_rect(x, y, -a, a, y0, y1, 4)


# ================================================================================================
# The cells
# ================================================================================================

X0, Y0, Z0 = -40, 0, -72
NX, NY, NZ = 80, 84, 144


def _grid():
    xs = np.arange(NX) + X0 + 0.5
    ys = np.arange(NY) + Y0 + 0.5
    zs = np.arange(NZ) + Z0 + 0.5
    X, Y, Z = np.meshgrid(xs, ys, zs, indexing="ij")
    # the roof's roll and the arches are cut in 2 px layers (half the boxes: the face budget), the rest in 1 px
    Y2 = np.floor(Y / 2) * 2 + 1
    Yr = np.where(Y > 58, Y2, Y)
    hull = inside(X, Yr, Z) & ~in_arch(X, Y2, Z)
    inner = hull.copy()
    for _ in range(2):
        e = inner.copy()
        for ax in range(3):
            for s in (1, -1):
                e &= np.roll(inner, s, axis=ax)
        inner = e
    # the roof's roll: its inside in 2 px layers too (each pair of layers the same)
    for j in range(60 - Y0, NY - 1, 2):
        both = inner[:, j] & inner[:, j + 1]
        inner[:, j], inner[:, j + 1] = both, both
    air = inner & (Y >= FLOOR)
    # over the wheels: square tubs inside (under the seats), not round ones (fewer boxes)
    for wz in (WF, WR):
        air &= ~((np.abs(X) > 15) & (np.abs(Z - wz) < ARCH + 2) & (Y < 29))
    return X, Y, Z, hull, inner, air


_G = {}


def grid():
    if not _G:
        X, Y, Z, hull, inner, air = _grid()
        _G.update(X=X, Y=Y, Z=Z, hull=hull, inner=inner, air=air)
    return _G


def is_air(p):
    """Is the point in the cabin's air (inside the shell, over the floor)?"""
    g = grid()
    i, j, k = math.floor(p[0]) - X0, math.floor(p[1]) - Y0, math.floor(p[2]) - Z0
    if not (0 <= i < NX and 0 <= j < NY and 0 <= k < NZ):
        return False
    return bool(g["air"][i, j, k])


def rib_y(x, z):
    """The two-tone line at (x, z): the V on the face, the belt everywhere else."""
    if z < -48:
        return min(BELT, 26 + 0.72 * abs(x))
    return BELT


def _cells():
    """body, doors {cab_l, cab_r, slide}: (x, y, z) -> label."""
    g = grid()
    X, Y, Z, hull, inner, air = g["X"], g["Y"], g["Z"], g["hull"], g["inner"], g["air"]
    body = {}
    parts = {"door_l": {}, "door_r": {}, "slide_door": {}}
    shell = hull & ~air
    idx = np.argwhere(shell)
    for (i, j, k) in idx:
        x, y, z = i + X0, j + Y0, k + Z0
        xc, yc, zc = x + 0.5, y + 0.5, z + 0.5
        ax = abs(xc)
        side = 1 if xc > 0 else -1
        core = bool(inner[i, j, k])
        is_side = ax > 27.5 and -56 < zc < 58
        # openings
        if not core:
            if is_side and side_hole(zc, yc, side):
                continue
            if zc < -46 and screen_hole(xc, yc) and ax < 26:
                continue
            if zc > 56 and rear_hole(xc, yc):
                continue
        label = "paint"             # one label: the shell and the core merge into few boxes (the decal paints all)
        # doors: the outer shell between their seams
        if is_side and 9 <= y < 66 and not core:
            if CD[0] <= z < CD[1]:
                parts["door_l" if side < 0 else "door_r"][(x, y, z)] = label
                continue
            if side > 0 and SD[0] <= z < SD[1]:
                parts["slide_door"][(x, y, z)] = label
                continue
        body[(x, y, z)] = label
    # the raised rib: one layer of cells just outside the shell along the two-tone line
    for x in range(-W - 1, W + 1):
        for z in range(NOSE - 2, TAIL + 2):
            xc, zc = x + 0.5, z + 0.5
            y = math.floor(rib_y(xc, zc)) - 1 if zc >= -48 else math.floor(rib_y(xc, zc))
            i, j, k = x - X0, y - Y0, z - Z0
            if hull[i, j, k]:
                continue
            if not (hull[min(i + 1, NX - 1), j, k] or hull[i - 1, j, k] or hull[i, j, min(k + 1, NZ - 1)] or hull[i, j, k - 1]):
                continue
            if in_arch(np.array(xc), np.array(y + 0.5), np.array(zc)):
                continue
            if zc < -48 and abs(xc) < 25.5:
                continue                  # the V on the face is painted (a 1 px rib stair-stepping down it looked jagged)
            side = 1 if xc > 0 else -1
            if abs(xc) > 27.5 and CD[0] <= z < CD[1]:
                parts["door_l" if side < 0 else "door_r"][(x, y, z)] = "rib"
            elif side > 0 and abs(xc) > 27.5 and SD[0] <= z < SD[1]:
                parts["slide_door"][(x, y, z)] = "rib"
            else:
                body[(x, y, z)] = "rib"
    return body, parts


# ================================================================================================
# Paints
# ================================================================================================

LIGHT = np.array((0.42, 0.78, -0.46))
LIGHT = LIGHT / np.linalg.norm(LIGHT)


def normal_at(p):
    x, y, z = p
    e = 1.4
    a = np.array([[x + e, y, z], [x - e, y, z], [x, y + e, z], [x, y - e, z], [x, y, z + e], [x, y, z - e]])
    f = field(a[:, 0], a[:, 1], a[:, 2])
    n = np.array((f[0] - f[1], f[2] - f[3], f[4] - f[5]))
    ln = np.linalg.norm(n)
    return n / ln if ln > 1e-6 else np.array((0.0, 1.0, 0.0))


class Look:
    """The colours of one paint job."""

    def __init__(self, lower, upper, canvas, curtain, seat, board, rim):
        self.lo, self.up, self.canvas, self.curtain, self.seat, self.board, self.rim = \
            lower, upper, canvas, curtain, seat, board, rim


def _seal(x, y, z, face):
    """Is the exterior texel right round a window (the rubber seal)?"""
    if face in ("east", "west"):
        side = 1 if x > 0 else -1
        for (kind, z0, z1) in windows(side):
            if kind == "cab":
                z0 = max(CD[0] + 3, cab_front(y))
                z1 = CD[1] - 3
            if round_rect(z, y, z0 - 1.1, z1 + 1.1, GL0 - 1.1, GL1 + 1.1, 4.1) and not round_rect(z, y, z0, z1, GL0, GL1):
                return True
    return False


def body_decal(look):
    LO, UP = look.lo, look.up

    def decal(c):
        x, y, z = c.p
        f = c.face
        nf = c.n
        probe = (x + nf[0] * 0.5, y + nf[1] * 0.5, z + nf[2] * 0.5)
        if f == "down":
            return S("rc_tyre", 0.1)
        if is_air(probe):
            return interior(c)
        ax = abs(x)
        # the wheel arches: dark inside, a darker lip round them on the side
        ra = min(math.hypot(z - WF, y - WY), math.hypot(z - WR, y - WY))
        if ra < ARCH + 0.35 and ax < W - 0.6:
            return S("rc_tyre", 0.12)
        n = normal_at((x, y, z))
        lit = float(n @ LIGHT)
        # the lower colour under the line, cream over it
        line = rib_y(x, z)
        upper = y >= line
        # details on the outside
        if f in ("east", "west"):
            side = 1 if x > 0 else -1
            if _seal(z, y, z, f) if False else _seal(x, y, z, f):
                return S("rc_tyre", 0.25)
            seams = [CD[0], CD[1]] + ([SD[0], SD[1]] if side > 0 else [])
            if any(abs(z - s) < 0.55 for s in seams) and 9 <= y < 66 and ax > 29:
                return S(UP if upper else LO, 0.05)                 # door shut lines
            if side > 0 and SD[1] + 1 < z < 54 and 45.2 < y < 46.2 and ax > 29:
                return S("rc_chrome", 0.35)                         # the sliding door's rail
            # louvres on the rear quarters
            if 45 <= z < 58 and 29 <= y < 39 and ax > 29:
                k = (math.floor(y) - 29) % 3
                if k == 0:
                    return S(LO, 0.05)
                if k == 1:
                    return S(LO, 0.85)
            if side < 0 and 22 <= z < 26 and 33 <= y < 37 and ax > 29:
                return S("rc_chrome", 0.8 if (y > 35 and z < 24) else 0.5)   # the fuel flap
        if f == "north" and z < -46 and screen_hole(x, y, 1.2) and not screen_hole(x, y):
            return S("rc_tyre", 0.25)                               # the windscreen's rubber seal
        if f == "north" and z < -55:
            # the vent slot in the brow
            if 64 <= y < 67 and ax < 14:
                return S("rc_tyre", 0.05) if math.floor(x) % 2 else S(UP, 0.4)
        if f == "south" and z > 60:
            # the engine lid's shut line
            if (abs(ax - 18) < 0.5 and 15 < y < 45) or (abs(y - 45) < 0.5 and ax < 18):
                return S(LO, 0.05)
        ramp = UP if upper else LO
        base = 0.48 + 0.42 * lit
        if upper and y < line + 1.0 and z < -48 and ax < 26:
            base += 0.25                                            # the V's bright edge
        if not upper:
            base -= 0.06 * max(0.0, (16 - y) / 10)
            # a soft reflection band low along the sides
            if f in ("east", "west") and 24 < y < 27:
                base += 0.12
        if f == "up" or (n[1] > 0.55):
            # a light streak along the roof
            if 8 < ax < 11:
                base += 0.12
        return S(ramp, base)

    def interior(c):
        x, y, z = c.p
        f = c.face
        if f == "up" and y <= FLOOR + 0.5:
            # the floor: wooden boards across
            return S("rc_wood", 0.45 if math.floor(x) % 6 else 0.2)
        if f == "up":
            return S("rc_tyre", 0.5)                                 # tubs, sills: a rubber mat
        if f == "down":
            return S("rc_cream", 0.9)                                # the headliner
        if y < 22:
            return S("rc_wood", 0.3)
        # wood-panelled walls up to the windows, cream above
        if y < GL0 - 1:
            return S("rc_wood", 0.6 if math.floor(y) % 5 else 0.4)
        return S("rc_cream", 0.8)
    return decal


def rib_decal(look):
    def decal(c):
        x, y, z = c.p
        if c.face == "down":
            return S(look.lo, 0.2)
        if c.face == "up":
            return S(look.up, 1.0)
        return S(look.up, 0.82)
    return decal


def make_paints(look):
    flat = dict(noise=0, light=False)
    return {
        "paint": Paint(look.up, 3, decal=body_decal(look), **flat),
        "core": Paint(look.lo, 2, noise=0, light=False, decal=lambda c: S("rc_tyre", 0.1) if c.face == "down" else body_decal(look)(c)),
        "well": Paint("rc_tyre", 1, noise=0, light=False, decal=lambda c: S("rc_tyre", 0.5) if (c.face == "up" and is_air((c.p[0], c.p[1] + 0.5, c.p[2]))) else S("rc_tyre", 0.08)),
        "rib": Paint(look.up, 5, decal=rib_decal(look), **flat),
    }


def _flat_voxels(m, cells, paints):
    """bake.voxels with the game's face shading off (the decal paints the light from the true surface's normal, so
    the 1 px steps of a curve read as one smooth panel)."""
    box = m.box

    def flat_box(name, frm, to, paint, **kw):
        kw["shade"] = False
        return box(name, frm, to, paint, **kw)
    m.box = flat_box
    try:
        voxels(m, "shell", cells, lambda label: paints[label])
    finally:
        del m.box


# ================================================================================================
# Glass
# ================================================================================================

CLEAR = ("raw", (0, 0, 0, 0))


def _streak(a, b, k=31):
    d = math.floor(a * 0.9 - b * 1.2) % k
    if d in (0, 1):
        return ("raw", (236, 244, 255, 255))
    if d == 4:
        return ("raw", (190, 210, 228, 255))
    return None


def side_glass(look, curtains):
    """A side window's pane: gingham curtains tied back each side and a gathered valance along the top, the rest
    clear with a reflection streak."""
    def decal(c):
        x, y, z = c.p
        if c.face not in ("east", "west"):
            return S("rc_tyre", 0.3)
        if curtains:
            for (z0, z1) in SIDE_WINDOWS:
                if z0 <= z <= z1:
                    u = min(z - z0, z1 - z)
                    tie = GL0 + 6
                    if y > GL1 - 3:
                        g = (math.floor(z) // 2 + math.floor(y)) % 2
                        return S(look.curtain, 0.75 if g else 0.35)    # the valance
                    reach = 2.2 + min(2.4, abs(y - tie) * 0.25)
                    if u < reach:
                        if abs(y - tie) < 0.7:
                            return S("rc_cream", 1.0)                    # the tie
                        g = (math.floor(z) // 2 + math.floor(y) // 2) % 2
                        return S(look.curtain, 0.8 if g else 0.4) if u > 0.8 else S(look.curtain, 0.2)
        s = _streak(z, y)
        return s or CLEAR
    return Paint("glass", 2, noise=0, light=False, decal=decal)


def screen_glass():
    def decal(c):
        x, y, z = c.p
        if c.face not in ("north", "south"):
            return S("rc_tyre", 0.3)
        ax = abs(x)
        # the wipers resting along the bottom (an arm and a blade)
        if c.v >= c.nv - 2 and 5 <= ax < 18:
            return ("raw", (24, 24, 28, 255))
        s = _streak(x, y * 0.6, 27)
        return s or CLEAR
    return Paint("glass", 2, noise=0, light=False, decal=decal)


def rear_glass(look):
    def decal(c):
        x, y, z = c.p
        if c.face not in ("north", "south"):
            return S("rc_tyre", 0.3)
        ax = abs(x)
        a, y0, y1 = REAR_WIN
        if y > y1 - 3:
            g = (math.floor(x) // 2 + math.floor(y)) % 2
            return S(look.curtain, 0.75 if g else 0.35)
        u = a - ax
        reach = 2.4 + min(2.4, abs(y - 54) * 0.3)
        if u < reach:
            if abs(y - 54) < 0.7:
                return S("rc_cream", 1.0)
            g = (math.floor(x) // 2 + math.floor(y) // 2) % 2
            return S(look.curtain, 0.8 if g else 0.4)
        s = _streak(x, y)
        return s or CLEAR
    return Paint("glass", 2, noise=0, light=False, decal=decal)


# ================================================================================================
# The van
# ================================================================================================

class Kit:
    def __init__(self, name, parts):
        self.body = BModel(name, shift=(8, 8, 8), scale=4.0)
        self.parts = {}
        for part in parts:
            m = BModel(f"{name}_{part}", shift=(8, 8, 8), scale=4.0)
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
    key = (lower, upper, canvas, curtain, seat, board, rim, name)
    if key in _KIT:
        return _KIT[key]
    look = Look(lower, upper, canvas, curtain, seat, board, rim)
    kit = Kit(name, PARTS)
    m = kit.body
    m.group("body")
    paints = make_paints(look)
    body, doors = _cells()
    _flat_voxels(m, body, paints)
    for part, cells in doors.items():
        _flat_voxels(kit[part], cells, paints)
    _windows(m, kit, look)
    _face(m, look)
    _tail(m, look)
    _roof(m, look)
    _awning(m, kit, look)
    _door_bits(kit, look)
    _cabin(m, look)
    m.display = presets.furniture(gui_scale=0.4)
    _KIT[key] = kit
    return kit


def _windows(m, kit, look):
    t = math.tan(math.radians(22.5))
    # the windscreen: a pane each side of the middle bar, leaning back at 22.5° (1 px behind the face)
    y0 = 46.0
    z0 = float(zf(np.array([y0]))[0]) + 1.6
    h = (GL1 + 0.5 - y0) / math.cos(math.radians(22.5))
    sg = screen_glass()
    for s in (1, -1):
        lo, hi = sorted((s * (SCREEN[0] - 0.5), s * (SCREEN[1] + 0.5)))
        m.box("windscreen", (lo, y0, z0), (hi, y0 + h, z0 + 0.5), sg, rotation=("x", 22.5, (0, y0, z0)))
    # the side glass: a pane in each window, 1 px in from the skin (the doors' panes ride with the doors)
    for s, door in ((-1, "door_l"), (1, "door_r")):
        lo, hi = sorted((s * 30.75, s * 31.25))
        z0 = cab_front(GL1) - 1
        kit[door].box("window", (lo, GL0 - 0.5, -48.5), (hi, GL1 + 0.5, CD[1] - 2.5), side_glass(look, False))
        for (a, b) in SIDE_WINDOWS:
            target = kit["slide_door"] if (s > 0 and SD[0] <= a < SD[1]) else m
            target.box("window", (lo, GL0 - 0.5, a - 0.5), (hi, GL1 + 0.5, b + 0.5), side_glass(look, True))
    a, y0_, y1_ = REAR_WIN
    m.box("rear_window", (-a - 0.5, y0_ - 0.5, 62.75), (a + 0.5, y1_ + 0.5, 63.25), rear_glass(look))


def _chrome(lo=0.35, hi=0.95):
    def decal(c):
        if c.face == "up":
            return S("rc_chrome", hi)
        if c.face == "down":
            return S("rc_chrome", lo * 0.6)
        y = c.p[1]
        frac = (y - c.lo[1]) / max(0.5, c.hi[1] - c.lo[1])
        return S("rc_chrome", lo + (hi - lo) * (0.35 + 0.65 * frac) if frac > 0.5 else lo + (hi - lo) * 0.2)
    return Paint("rc_chrome", 4, noise=0, light=False, decal=decal)


CHROME = None


def _round_lamp(cx, cy, core, mid, edge, ring_r, front):
    """A round lens seen from its face: a hot core, a ribbed reflector, a chrome ring."""
    def decal(c):
        if c.face != front:
            return S("rc_chrome", 0.5)
        r = math.hypot(abs(c.p[0]) - cx, c.p[1] - cy)
        if r < ring_r * 0.32:
            return core
        if r < ring_r:
            k = math.floor(r * 2) % 2
            return mid if k else edge
        return S("rc_chrome", 0.95 if c.p[1] > cy else 0.55)
    return decal


def _face(m, look):
    chrome = _chrome()
    # headlamps: a chrome ring (12 across) set into the nose, the lens (9 across) standing out of it
    for s in (1, -1):
        cx = 21 * s
        cyl(m, "lamp_ring", (cx, 33), 13, NOSE - 1.5, NOSE + 2.75, Paint("rc_chrome", 4, noise=0, light=False,
            decal=_round_lamp(21, 33, S("rc_chrome", 1), S("rc_chrome", 0.9), S("rc_chrome", 0.6), 0, "north")), axis="z")
        cyl(m, "headlamp", (cx, 33), 9, NOSE - 2, NOSE - 1.5, Paint("lamp", 3, noise=0, light=False, glow=True,
            decal=_round_lamp(21, 33, ("lamp", 3.6), ("lamp", 2.6), ("lamp", 1.6), 4.6, "north")), axis="z")
        # the indicator under it: a small round amber lens in a chrome cup
        cyl(m, "indicator", (21 * s, 22.5), 5, NOSE - 1.25, NOSE + 1.75, Paint("fox", 3, noise=0, light=False, glow=True,
            decal=lambda c: ("fox", 4 if c.p[1] > 22.5 else 3) if c.face == "north" else S("rc_chrome", 0.6)), axis="z")
    # the bumper: a slim chrome blade wrapping round the corners, two overriders, brackets under it
    _bumper(m, front=True)
    # the front plate's place (the plugin writes the owner's name on it): a pale plate in a thin frame
    m.box("plate", (-9, 15.5, NOSE - 1), (9, 22.5, NOSE + 1), Paint("rc_cream", 6, noise=0, light=False, decal=_plate(True)))


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
    z_out = NOSE - 4 if front else TAIL + 4
    z_in = NOSE - 0.5 if front else TAIL + 0.5
    lo, hi = sorted((z_out, z_in))
    blade = _chrome(0.35, 1.0)
    m.box("bumper", (-30, 9.5, lo), (30, 14.5, hi), blade)
    # the ends wrapping back round the corners
    for s in (1, -1):
        xa, xb = sorted((30 * s, 32.5 * s))
        za, zb_ = sorted((z_out + zs * -1.5, z_in - zs * 6))
        m.box("bumper_end", (xa, 9.5, za), (xb, 14.5, zb_), blade)
        # an overrider: a little upright guard with a rubber tip
        xa, xb = sorted((11 * s, 13.5 * s))
        za, zb_ = sorted((z_out + zs * 1.5, z_out))
        m.box("overrider", (xa, 7.5, za), (xb, 18.5, zb_), _chrome(0.4, 1.0))
        # brackets back to the body under it
        xa, xb = sorted((17 * s, 19 * s))
        m.box("bracket", (xa, 10.25, *sorted((z_in, z_in - zs * 6))[:1]), (xb, 12.25, sorted((z_in, z_in - zs * 6))[1]),
              Paint("rc_tyre", 1, noise=0, light=False, decal=lambda c: S("rc_tyre", 0.3)))


def _tail(m, look):
    # round tail lamps in chrome rings on the back's corners
    for s in (1, -1):
        cyl(m, "tail_ring", (25 * s, 30), 9, TAIL - 3.75, TAIL + 1, Paint("rc_chrome", 4, noise=0, light=False,
            decal=_round_lamp(25, 30, S("rc_chrome", 1), S("rc_chrome", 0.9), S("rc_chrome", 0.6), 0, "south")), axis="z")
        cyl(m, "tail_lamp", (25 * s, 30), 7, TAIL + 1, TAIL + 1.5, Paint("signal_red", 1, noise=0, light=False, glow=True,
            decal=_round_lamp(25, 30, ("raw", (255, 96, 90, 255)), ("raw", (214, 38, 40, 255)), ("raw", (160, 22, 28, 255)), 3.6, "south")), axis="z")
    _bumper(m, front=False)
    m.box("plate", (-9, 15.5, TAIL - 1), (9, 22.5, TAIL + 1), Paint("rc_cream", 6, noise=0, light=False, decal=_plate(False)))
    # the spare wheel on the back: a white-wall tyre, a cream rim, a chrome dish in the middle, on a bracket
    cyl(m, "spare_mount", (0, 38), 6, TAIL - 1.75, TAIL + 1.5, Paint("rc_tyre", 1, noise=0, light=False, decal=lambda c: S("rc_tyre", 0.3)), axis="z")

    def tyre_face(c):
        x, y = c.p[0], c.p[1]
        r = math.hypot(x, y - 38)
        if c.face != "south":
            a = math.atan2(y - 38, x)
            return S("rc_tyre", 0.45 if math.floor((a + math.pi) * 9) % 3 else 0.15)
        if r > 11.2:
            return S("rc_tyre", 0.2)
        if r > 8.6:
            return S("rc_cream", 1.0 if r < 10 else 0.85)            # the white wall
        return S("rc_tyre", 0.35)
    cyl(m, "spare", (0, 38), 24, TAIL + 1.5, TAIL + 7, Paint("rc_tyre", 1, noise=0, light=False, decal=tyre_face), axis="z")

    def rimf(c):
        x, y = c.p[0], c.p[1]
        r = math.hypot(x, y - 38)
        if c.face != "south":
            return S(look.rim, 0.5)
        if r < 4.4:
            return S("rc_chrome", 1.0 if (x < 0 and y > 38) else 0.65 if r > 3.4 else 0.85)
        return S(look.rim, 0.85 if (x < 0 and y > 38) else 0.65)
    cyl(m, "spare_rim", (0, 38), 16, TAIL + 7, TAIL + 7.5, Paint(look.rim, 4, noise=0, light=False, decal=rimf), axis="z")
    cyl(m, "spare_cap", (0, 38), 8, TAIL + 7.5, TAIL + 8.5, Paint("rc_chrome", 4, noise=0, light=False,
        decal=lambda c: S("rc_chrome", 1.0 if (c.p[0] < 0 and c.p[1] > 38) else 0.7) if c.face == "south" else S("rc_chrome", 0.5)), axis="z")
    # the ladder up the back (right of the spare): two rails, rungs, brackets to the body, a hoop over the roof edge
    rail = _chrome(0.45, 0.95)
    zl0, zl1 = TAIL + 3, TAIL + 4
    for x0 in (13, 19):
        m.box("ladder_rail", (x0, 16, zl0), (x0 + 1, 74, zl1), rail)
        for yb in (20, 70):
            m.box("ladder_bracket", (x0, yb, TAIL - 1), (x0 + 1, yb + 1, zl0), rail)
        m.box("ladder_top", (x0 + 0.125, 74.25, 54.25), (x0 + 0.875, 74.75, zl1), rail)
    for y in range(22, 73, 6):
        m.box("ladder_rung", (14, y, zl0 + 0.25), (19, y + 1, zl1 - 0.25), rail)


def _roof(m, look):
    UP, CAN = look.up, look.canvas
    # the pop-up tent: striped canvas walls standing on the roof, a cream lid on them
    T0, T1 = 77, 87                 # canvas from (sunk into the roof) .. to
    LZ0, LZ1 = -36, 46

    def canvas(c):
        x, y, z = c.p
        f = c.face
        if f in ("up", "down"):
            return S(CAN, 0.4)
        along = z if f in ("east", "west") else x
        # a little mesh window in the middle of each long side
        if f in ("east", "west") and -6 <= z < 14 and 80 <= y < 85:
            if z < -5 or z >= 13 or y < 81 or y >= 84:
                return S("rc_tyre", 0.6)
            return S("rc_tyre", 0.05) if (math.floor(z) + math.floor(y)) % 2 else S("rc_tyre", 0.3)
        stripe = math.floor(along / 4) % 2
        shade = 0.55 + 0.25 * (y - T0) / (T1 - T0)
        if y > T1 - 1.2:
            shade -= 0.25                                     # under the lid's lip
        return S(CAN, shade + 0.2) if stripe else S("rc_cream", shade + 0.25)
    m.box("tent", (-20, T0, LZ0 + 2), (20, T1, LZ1 - 2), Paint(CAN, 3, noise=0, light=False, decal=canvas))

    def lid(c):
        x, y, z = c.p
        if c.face == "up":
            if 6 < abs(x) < 9:
                return S(UP, 1.0)
            return S(UP, 0.86)
        if c.face == "down":
            return S(UP, 0.35)
        return S(UP, 0.72 if c.face in ("north", "west") else 0.6)
    LP = Paint(UP, 4, noise=0, light=False, decal=lid)
    m.box("lid", (-23, T1, LZ0), (23, T1 + 2, LZ1), LP)
    m.box("lid_top", (-21, T1 + 2, LZ0 + 2), (21, T1 + 3, LZ1 - 2), LP)
    # two bars across the lid, a surfboard strapped along it
    BAR = T1 + 3
    for zb_ in (-22, 30):
        m.box("rack_bar", (-18, BAR, zb_), (18, BAR + 1, zb_ + 2), Paint("rc_tyre", 1, noise=0, light=False,
              decal=lambda c: S("rc_tyre", 0.5 if c.face == "up" else 0.25)))
    if look.board:
        BY = BAR + 1
        B = look.board

        def surf(c):
            x, y, z = c.p
            if c.face == "up":
                if any(zb_ - 0.5 <= z < zb_ + 2.5 for zb_ in (-22, 30)):
                    return S("rc_tyre", 0.3)                      # the straps
                if abs(x) < 0.6:
                    return S("signal_red", 0.7)                   # the stringer stripe
                if 2.5 < abs(x) < 3.4 and -25 < z < 36:
                    return S("signal_red", 0.5)
                return S(B, 0.95 if x < 0 else 0.8)
            if c.face == "down":
                return S(B, 0.3)
            return S("rc_cream", 0.95)                            # the rail
        bp = Paint(B, 2, noise=0, light=False, decal=surf)
        for (hw_, z0, z1) in ((1.5, -41, -39), (3, -39, -36), (4.5, -36, -31), (5.5, -31, -24), (6, -24, 30),
                             (5.5, 30, 36), (4.5, 36, 40), (3, 40, 42)):
            m.box("surfboard", (-hw_, BY, z0), (hw_, BY + 1.5, z1), bp)
        m.box("fin", (-0.5, BY - 2, 36), (0.5, BY, 40), Paint(B, 1, noise=0, light=False, decal=lambda c: S(B, 0.4)))
    # the roof rack over the cab: a chrome rail frame on four feet, a suitcase and a rolled sleeping bag on it
    rail = _chrome(0.45, 0.95)
    RY = 81
    for s in (1, -1):
        xa, xb = sorted((18 * s, 19 * s))
        m.box("rack_rail", (xa, RY, -56), (xb, RY + 1, -38), rail)
        for zf_ in (-55, -40):
            ytop = float(_roof_y(18.5 * s, zf_ + 0.5))
            m.box("rack_foot", (xa + 0.125 * s, ytop - 1.3, zf_ + 0.125), (xb + 0.125 * s, RY, zf_ + 0.875), rail)
    for zr in (-56, -39):
        m.box("rack_rail", (-18, RY, zr), (18, RY + 1, zr + 1), rail)
    for zr in (-50, -45):
        m.box("rack_slat", (-18, RY, zr), (18, RY + 0.5, zr + 1), rail)

    def case(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(z + 47) < 0.6 or abs(x - 2) < 0.6 and False:
                return S("rc_leather", 0.3)
            if 7.5 < x < 9 or -9 < x < -7.5:
                return S("rc_leather", 0.15)                       # straps
            return S("rc_leather", 0.85)
        if 7.5 < x < 9 or -9 < x < -7.5:
            return S("rc_leather", 0.15)
        if abs(y - (RY + 4)) < 0.5:
            return S("rc_chrome", 0.7)                             # the case's seam with its clasps
        return S("rc_leather", 0.55 if c.face in ("north", "west") else 0.4)
    m.box("suitcase", (-16, RY + 0.5, -54), (2, RY + 7, -42), Paint("rc_leather", 2, noise=0, light=False, decal=case))
    m.box("suitcase_handle", (-9, RY + 7, -49), (-5, RY + 8, -47), Paint("rc_leather", 1, noise=0, light=False, decal=lambda c: S("rc_leather", 0.2)))

    def roll(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            r = math.hypot(y - (RY + 4), z + 47)
            return S(look.curtain, 0.25) if math.floor(r * 1.2) % 2 else S(look.canvas, 0.75)   # the spiral
        if 9.5 < x < 10.5 or 14.5 < x < 15.5:
            return S("rc_tyre", 0.3)                               # its ties
        return S(look.canvas, 0.6 + 0.2 * (y - RY) / 8)
    cyl(m, "bedroll", (RY + 4.5, -47), 7, 4, 17, Paint(look.canvas, 2, noise=0, light=False, decal=roll), axis="x")


def _roof_y(x, z):
    """The roof's top at (x, z) (scanning down the hull)."""
    y = ROOF + 0.0
    while y > 40 and not bool(inside(np.array(x), np.array(y), np.array(z))):
        y -= 0.25
    return y


def _awning(m, kit, look):
    CAN = look.canvas
    # the roller case along the right roof edge (on the body), from just behind the cab door to the window behind
    def roller(c):
        if c.face == "up":
            return S("rc_chrome", 0.85)
        if c.face == "down":
            return S("rc_chrome", 0.3)
        return S("rc_chrome", 0.6)
    m.box("awning_case", (31.5, 67, -27), (34.5, 70, 11), Paint("rc_chrome", 3, noise=0, light=False, decal=roller))
    # the canvas: level over the sliding door, a scalloped valance hanging from its edge
    a = kit["awning"]

    def cloth(c):
        x, y, z = c.p
        stripe = math.floor((z + 25) / 4) % 2
        top = c.face == "up"
        f = 0.75 if top else 0.4
        return S(CAN, f) if stripe else S("rc_cream", f + 0.15)
    a.box("canvas", (34.5, 69, -25), (70, 69.75, 8), Paint(CAN, 3, noise=0, light=False, decal=cloth))

    def valance(c):
        x, y, z = c.p
        u = (z + 25) % 4
        depth = 2.2 + 2.2 * math.sin(math.pi * u / 4)
        if y < 69 - depth:
            return CLEAR
        stripe = math.floor((z + 25) / 4) % 2
        return S(CAN, 0.55) if stripe else S("rc_cream", 0.75)
    a.box("valance", (69.5, 64, -25), (70, 69, 8), Paint(CAN, 3, noise=0, light=False, decal=valance))
    # its two poles (another part: they slide into the side)
    p = kit["awning_poles"]
    for zp in (-24.5, 6.5):
        p.box("pole", (68.25, 0.5, zp), (69, 68.75, zp + 0.75), _chrome(0.45, 0.9))
        p.box("pole_foot", (67.5, 0, zp - 0.75), (69.75, 0.5, zp + 1.5), Paint("rc_tyre", 1, noise=0, light=False, decal=lambda c: S("rc_tyre", 0.3)))


def _door_bits(kit, look):
    rail = _chrome(0.45, 1.0)
    for s, part in ((-1, "door_l"), (1, "door_r")):
        d = kit[part]
        # the mirror: a thin arm from the door's front top out and back, a round head facing back
        xa, xb = sorted((32 * s, 38 * s))
        d.box("mirror_arm", (xa, 58, -49), (xb, 59, -48), rail)
        xa, xb = sorted((37.5 * s, 38.5 * s))
        d.box("mirror_stem", (xa, 58, -48), (xb, 61, -47), rail)

        def mirror(c, s=s):
            if c.face == "south":
                r = math.hypot(c.p[0] - 38 * s, c.p[1] - 63)
                return ("raw", (200, 222, 236, 255)) if r < 1.6 else S("rc_chrome", 0.8)
            return S("rc_chrome", 0.6)
        cyl(d, "mirror", (38 * s, 63), 5, -48, -46.5, Paint("rc_chrome", 4, noise=0, light=False, decal=mirror), axis="z")
        # the handle: a chrome pull with a push button
        xa, xb = sorted((32 * s, 33 * s))
        d.box("handle", (xa, 41, -33), (xb, 42, -29), rail)
    xa, xb = 32, 33
    kit["slide_door"].box("handle", (xa, 41, 2), (xb, 42, 6), rail)


def _cabin(m, look):
    SEAT, WOOD = look.seat, "rc_wood"

    def plaid(c):
        x, y, z = c.p
        a = x if c.face != "east" and c.face != "west" else z
        b = z if c.face in ("up", "down") else y
        k = (math.floor(a / 2) % 2) + (math.floor(b / 2) % 2)
        if c.face == "down":
            return S(SEAT, 0.1)
        return S(SEAT, (0.25, 0.55, 0.9)[k] + (0.08 if c.face == "up" else 0))
    pl = Paint(SEAT, 2, noise=0, light=False, decal=plaid)
    base = Paint("rc_tyre", 1, noise=0, light=False, decal=lambda c: S("rc_tyre", 0.35))
    # the front: a seat box over the wheels, two seats, a dash with a big speedo and a radio, the steering wheel
    m.box("seat_box", (-27.75, FLOOR, -46), (27.75, 25, -27), Paint(look.lo, 2, noise=0, light=False,
          decal=lambda c: S(look.lo, 0.5 if c.face != "up" else 0.7)))
    for s in (1, -1):
        xa, xb = sorted((5 * s, 27 * s))
        m.box("front_cushion", (xa, 25, -44), (xb, SEAT_Y, -30), pl)
        m.box("front_back", (xa, 25, -30), (xb, 58, -27), pl)

    def dash(c):
        x, y, z = c.p
        if c.face == "south":
            r = math.hypot(x + 16, y - 45)
            if r < 4:
                if r < 0.8:
                    return S("rc_tyre", 0.1)
                if abs(math.atan2(y - 45, x + 16) - 2.2) < 0.25 and r < 3.4:
                    return ("raw", (230, 70, 50, 255))             # the needle
                return S("rc_cream", 1.0) if r < 3.3 else S("rc_chrome", 0.9)
            if 2 <= x < 10 and 43 <= y < 46:
                return S("rc_chrome", 0.9) if math.floor(x) % 2 else S("rc_tyre", 0.2)   # the radio
            return S(look.lo, 0.5 if y > 47 else 0.35)
        if c.face == "up":
            return S("rc_tyre", 0.3)                               # the padded top
        return S(look.lo, 0.35)
    m.box("dash", (-28.75, 40, -57.75), (28.75, 49.5, -53), Paint(look.up, 3, noise=0, light=False, decal=dash))
    # the steering wheel: a big thin ring round a two-spoke hub, on a column
    cx, cy, cz = -16, 51, -46
    rim = Paint("rc_tyre", 1, noise=0, light=False, decal=lambda c: S("rc_cream", 0.7) if c.face != "down" else S("rc_cream", 0.4))
    for k in range(16):
        ang = k * math.pi / 8
        y, x = cy + math.cos(ang) * 6.5, cx + math.sin(ang) * 6.5
        m.box("wheel", (x - 1, y - 1, cz), (x + 1, y + 1, cz + 1), rim)
    m.box("wheel_spoke", (cx - 5.5, cy - 0.5, cz + 0.25), (cx + 5.5, cy + 0.5, cz + 0.75), rim)
    m.box("column", (cx - 1, cy - 4, -53), (cx + 1, cy - 2, cz), base)
    m.box("gear", (-1, 25, -47), (1, 37, -45), base)
    m.box("gear_knob", (-1.5, 37, -47.5), (1.5, 39, -44.5), Paint("rc_cream", 4, noise=0, light=False, decal=lambda c: S("rc_cream", 0.9)))
    # two benches
    for (z0, z1) in ((-7, 6), (23, 36)):
        m.box("bench_base", (-29, FLOOR, z0 + 1), (29, 25, z1), base)
        m.box("bench_cushion", (-29, 25, z0), (29, SEAT_Y, z1), pl)
        m.box("bench_back", (-29, 25, z1), (29, 56, z1 + 3), pl)
    # the kitchenette across the back: a cupboard with a sink and a two-ring stove (left), a cool box (right)
    KZ0, KZ1, KTOP = 41, 61, 47

    def cupboard(c):
        x, y, z = c.p
        if c.face == "up":
            if -26 <= x < -18 and 45 <= z < 54:
                r = math.hypot(x + 22, z - 49.5)
                return S("rc_chrome", 0.5 if r < 1 else 0.85 if (math.floor(x) + math.floor(z)) % 3 else 0.7)   # the sink
            if -14 <= x < -2 and 44 <= z < 56:
                for (bx, bz) in ((-11, 50), (-5, 50)):
                    r = math.hypot(x - bx, z - bz)
                    if r < 2.8:
                        return S("rc_tyre", 0.05) if r > 2 or r < 0.8 else S("rc_tyre", 0.3)   # the rings
                return S("rc_tyre", 0.45)
            return S("rc_cream", 0.95)
        if c.face == "north":
            if y > KTOP - 1.5:
                return S("rc_cream", 0.8)
            if abs(x + 15.5) < 0.5 or y < FLOOR + 1.5:
                return S(WOOD, 0.2)
            if (abs(x + 19) < 1.5 or abs(x + 12) < 1.5) and abs(y - 41) < 0.6:
                return S("rc_chrome", 0.9)                         # handles
            return S(WOOD, 0.7 if math.floor(x) % 4 else 0.55)
        return S(WOOD, 0.5)
    m.box("cupboard", (-29, FLOOR, KZ0), (-2, KTOP, KZ1), Paint(WOOD, 3, noise=0, light=False, decal=cupboard))
    m.box("cool_box", (4, FLOOR, 44), (26, 36, 60), Paint(look.lo, 3, noise=0, light=False,
          decal=lambda c: S("rc_cream", 0.95) if (c.p[1] > 33 or c.face == "up") else S(look.lo, 0.75 if c.face == "north" else 0.55)))
    m.box("cool_box_handle", (10, 36, 50), (20, 37, 52), _chrome(0.4, 0.9))
    # a kettle on the stove
    cyl(m, "kettle", (-11, 50), 4, KTOP, KTOP + 3, Paint("rc_chrome", 4, noise=0, light=False,
        decal=lambda c: S("signal_red", 0.6) if c.face != "up" else S("signal_red", 0.8)))
    # the lantern hanging from the roof between the benches: a chain, a cap, a glowing glass, a base
    lx, lz = 0, 15
    m.box("lantern_chain", (lx - 0.5, 69, lz - 0.5), (lx + 0.5, 76.5, lz + 0.5), base)
    m.box("lantern_cap", (lx - 2, 67, lz - 2), (lx + 2, 69, lz + 2), Paint("rc_tyre", 1, noise=0, light=False,
          decal=lambda c: S("rc_tyre", 0.5)))
    m.box("lantern_glass", (lx - 1.5, 62, lz - 1.5), (lx + 1.5, 67, lz + 1.5), Paint("lamp", 3, noise=0, light=False, glow=True,
          decal=lambda c: ("lamp", 4 if 63.5 < c.p[1] < 65.5 else 3)))
    m.box("lantern_base", (lx - 2, 61, lz - 2), (lx + 2, 62, lz + 2), Paint("rc_tyre", 1, noise=0, light=False,
          decal=lambda c: S("rc_tyre", 0.4)))


# ================================================================================================
# The wheel: drawn at 32 px a block (24 across = 0.75 block), shrunk by 2
# ================================================================================================

def wheel_retro_camper(rim="rc_cream"):
    """A fat white-wall tyre, a cream steel rim, a chrome dish hub cap (a bright ring, a dome in the middle)."""
    m = BModel("wheel_retro_camper", shift=(8, 8, 8), floor=None, scale=2.0)
    d, width = 24, 8
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            if rr > r - 1.2:
                return S("rc_tyre", 0.1)
            if rr > r - 4.4:
                return S("rc_cream", 1.0 if rr < r - 2 else 0.85)   # the white wall
            return S("rc_tyre", 0.3)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.0)
        return S("rc_tyre", 0.0 if k % 3 == 0 else 0.35 if k % 3 == 1 else 0.2)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, Paint("rc_tyre", 1, noise=0, light=False, decal=tire), axis="x")

    def hub(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face not in ("east", "west"):
            return S("rc_chrome", 0.5)
        lit = y > 0 and z < 0.5
        if rr < 1.4:
            return S("rc_chrome", 1.0)
        if rr < 4.6:
            return S("rc_chrome", 0.95 if lit else 0.7)
        if rr < 5.6:
            return S("rc_chrome", 0.4)                             # the dish's edge
        return S(rim, 0.9 if lit else 0.7)                         # the painted rim
    p = Paint("rc_chrome", 4, noise=0, light=False, decal=hub)
    w2 = width / 2
    cyl(m, "hub", (0, 0), 15, w2 - 0.5, w2 + 0.5, p, axis="x")
    cyl(m, "hub", (0, 0), 15, -w2 - 0.5, -w2 + 0.5, p, axis="x")
    cyl(m, "cap", (0, 0), 9, w2 + 0.5, w2 + 1.5, p, axis="x")
    cyl(m, "cap", (0, 0), 9, -w2 - 1.5, -w2 - 0.5, p, axis="x")
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
