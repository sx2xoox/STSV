"""
후원 탈것 · 클래식 로드스터 (classic_roadster): an elegant open two-seat roadster in the manner of the 1950s and 60s. An
original design: no real make or model, no badge.

Drawn like the showcase car (models/vehicles_showcase.py, phantom_gt): 32 px a block. The body is drawn at twice its
real size and shrunk by 4 (vehicles_premium.Kit scale 4), shown at vehicle.body-scale 2; the wheel drawn at twice its
size and shrunk by 2, shown at 1. Drawn coordinates: 32 px a block, the car's ground centre at (0, 0, 0), front =
north (-z), +x = the car's RIGHT side, the driver on the left (-x).

Sculpted, not boxed: the body is a field of 1 px cells (bake.voxels) from smooth profiles, built of separate forms:
  * a low hull: a long hood with a central power bulge and a chrome strip, the cut-down doors (the belt dips between
    the cowl and the deck), a rear deck falling at 22.5 degrees to a rounded tail;
  * front fenders that swell on their own above the hood (a valley between), carry a round chrome-ringed headlamp
    faired into each tip, and sweep back down across the doors;
  * rear fenders that rise again over the rear wheels and end in round tail lamps.
Every texel is lit from a smoothed copy of the cells (its normal and its occlusion), so the 1 px steps read as one
smooth panel: a sky reflection on the crowns, a sheen along the shoulders, chrome with a dark horizon line.

Details: a chrome oval egg-crate grille (a fine mesh 2 px deep behind a proud chrome lip), chrome blade bumpers with
bullet overriders, parking lamps, side vents with chrome strakes behind the front wheels, a chrome strip along the
flanks (the two tones split there), a bullet wing mirror, a low chrome-framed raked windscreen with a mirror and
wipers, the cockpit's leather roll, bucket seats with fluted, stitched leather, door cards, carpet and a tunnel with
the gear lever, a walnut dash with chrome-ringed gauges, a wooden-rim steering wheel with wire spokes, the folded
soft top behind the seats under its fabric cover (press studs, the folds showing at the ends), a chrome luggage rack
on the boot, a fuel filler cap, round tail lamps, a plate recess, twin exhausts.

Paint (tuning): colour = the body (any palette ramp), lower = an optional second tone below the flank strip,
leather = the seats, door cards and roll, soft = the soft top's cover.

Models (ALL): classic_roadster (body), classic_roadster_door_l / _door_r (parts: each the side between its two seams,
hinged at its front edge), wheel_classic (a wire wheel with a two-eared knock-off spinner and a whitewall tyre,
0.8 block across).
"""

import math
from functools import lru_cache

import numpy as np

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import MATERIALS, PALETTE, REMAP, build_ramp, color_at
from stsv_assets import presets
from models import vehicles as _vehicles  # noqa: F401 (vehicles_premium is imported through it)
from models.vehicles_premium import Kit

NAME = "classic_roadster"


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent)
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("rd_green", 158, 0.085, 0.40, 0.50, 8, False)    # deep racing green, metallic
_ramp("rd_ivory", 88, 0.030, 0.82, 0.30, 6, False)     # old English white (the second tone, gauge faces)
_ramp("rd_chrome", 235, 0.018, 0.62, 0.82, 8, False)   # chrome: dark reflections to near white
_ramp("rd_tan", 62, 0.090, 0.56, 0.36, 6, False)       # tan leather
_ramp("rd_oxblood", 22, 0.130, 0.38, 0.30, 6, False)   # red leather (the other leather)
_ramp("rd_wood", 52, 0.090, 0.40, 0.34, 6, False)      # walnut
_ramp("rd_canvas", 250, 0.012, 0.26, 0.20, 5, False)   # black mohair
_ramp("rd_dark", 240, 0.010, 0.13, 0.12, 3, False)     # recesses, the grille's holes, the wells
_ramp("rd_tyre", 260, 0.008, 0.20, 0.16, 4, False)
_ramp("rd_white", 90, 0.012, 0.90, 0.14, 3, False)     # whitewalls
_ramp("rd_lens", 85, 0.040, 0.86, 0.24, 4, True)       # headlamp glass
_ramp("rd_tail", 25, 0.200, 0.46, 0.36, 4, True)       # tail lamp glass
_ramp("rd_amber", 62, 0.150, 0.70, 0.24, 3, True)      # parking lamps


def _spec(ramp):
    return MATERIALS[REMAP.get(ramp, ramp)]


@lru_cache(maxsize=None)
def _rgb(spec, q):
    return (*color_at(*spec, t=q / 12), 255)


def col(ramp, t):
    """A colour t (0 dark .. 1 light) along a ramp, in 13 levels (exact, unlit: the light is painted)."""
    q = int(round(max(0.0, min(1.0, t)) * 12))
    return ("raw", _rgb(_spec(ramp), q))


# ================================================================================================
# Shape (drawn px, 32 a block)
# ================================================================================================

NOSE, TAIL = -70, 68
WF, WR = -43, 38                # front / rear axle (z)
WY = WRAD = 12.8                # wheel centre height = wheel radius (0.8 block across)
ARCH = 15.5                     # the arch cut round a wheel
TRACK_IN = 20.0                 # the body is cut beyond this |x| in the arches
WHEEL_X = 24                    # the wheels' middle (x)
GROUND = 5                      # the sills' lowest cells
FLOOR = 7                       # the cockpit's floor
D0, D1 = -14, 18                # the doors, between their seams (z)
COWL = -16                      # the windscreen's foot
DASH = -10                      # the dash's face
CK1 = 27                        # the cockpit's back
SEAT_X, SEAT_Z = 10.9, 16       # the seats (vehicles.yml seats: x 0.34, forward -0.5)
STRIP = 13                      # the chrome flank strip (and the split of the two tones)
T22 = math.tan(math.radians(22.5))
DECK_FALL = 40                  # where the deck starts falling at 22.5 degrees
X0, X1, Y0, Y1, Z0, Z1 = -36, 36, 0, 48, -84, 84     # the cell grid's bounds


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def hull_w(z):
    """The hull's half width at z (the fenders stand outside it at the wheels)."""
    w = 26.5
    if z < -54:
        w = 16 + 10.5 * smooth((z - NOSE) / 16) ** 0.7
    if z > 50:
        w = 22 + 4.5 * smooth((TAIL - z) / 18) ** 0.6
    return w


def tuck(y):
    return (10 - y) * 0.45 if y < 10 else 0.0


def deck(z):
    """The rear deck's height along the middle."""
    if z < DECK_FALL:
        d = 26 + 1.2 * smooth((z - CK1) / 10)
    else:
        d = 27.2 - (z - DECK_FALL) * T22
    if z > TAIL - 5:
        d -= (z - (TAIL - 5)) ** 1.5 * 0.35
    return d


def belt(z):
    """The cockpit's sides: the cut-down doors dip between the cowl and the deck."""
    return 26 - 4.4 * smooth(1 - abs(z - 4) / 22)


def hood(z, ax):
    h = 21.5 + 4.5 * smooth((z - NOSE) / 52) ** 0.8
    if z < NOSE + 4:
        h -= (NOSE + 4 - z) ** 1.5 * 0.3
    h += 1.1 * smooth(1 - ax / 7)                        # the central power bulge
    h -= 1.0 if ax > 12 else 0                           # falling to the valleys beside the fenders
    return h


def hull_top(z, ax):
    if z < COWL:
        h = hood(z, ax)
    elif z < CK1:
        h = belt(z) if z >= DASH else max(26.0, belt(z))
    else:
        h = deck(z) - 1.2 * (ax / 18) ** 2
    edge = hull_w(z) - ax                                # the shoulder rolls over
    if edge < 3.5:
        h -= (3.5 - edge) ** 1.6 * 0.5
    return h


def bottom(z):
    b = GROUND
    if z < NOSE + 14:
        b += 2.5 * smooth((NOSE + 14 - z) / 14)
    if z > TAIL - 14:
        b += 2.5 * smooth((z - (TAIL - 14)) / 14)
    return b


# --- the fenders ------------------------------------------------------------------------------------

def ff_top(z):
    """The front fender's crown line: low at the lamp, high over the wheel, sweeping down across the door."""
    if z < -46:
        t = 24.8 + 7.2 * smooth((z - NOSE) / 24) ** 0.6
        if z < NOSE + 6:
            t -= (NOSE + 6 - z) ** 1.5 * 0.2
        return t
    if z < -38:
        return 32.0
    return 32.0 - 19.5 * smooth((z + 38) / 56) ** 1.3


def ff_span(z):
    """(inner, outer) |x| of the front fender at z: rounded in plan at its tip."""
    lo, hi = 16.0, 31.0
    if z > -28:
        hi = 31 - 4.4 * smooth((z + 28) / 42)
    if z < NOSE + 9:
        k = math.sqrt(max(0.0, 1 - ((NOSE + 9 - z) / 9.6) ** 2))
        c, h = (lo + hi) / 2, (hi - lo) / 2 * k
        lo, hi = c - h, c + h
    return lo, hi


def rf_top(z):
    """The rear fender's crown: rising from the door to its peak over the wheel, falling to the tail lamp."""
    if z < 36:
        return 12 + 19.5 * smooth((z + 4) / 40)
    if z < 44:
        return 31.5
    t = 31.5 - 9.5 * smooth((z - 44) / 24)
    if z > TAIL - 3:
        t -= (z - (TAIL - 3)) ** 1.4 * 0.5
    return t


def rf_span(z):
    lo, hi = 17.0, 26.6 + 4.9 * smooth((z + 4) / 28)
    if z > 52:
        hi -= 2.5 * smooth((z - 52) / 16)
    if z > TAIL - 8:
        k = math.sqrt(max(0.0, 1 - ((z - (TAIL - 8)) / 8.6) ** 2))
        c, h = (lo + hi) / 2, (hi - lo) / 2 * k
        lo, hi = c - h, c + h
    return lo, hi


def fender_top(top, lo, hi, ax, out_drop, in_drop):
    if hi - lo < 0.5 or not (lo <= ax <= hi):
        return -1
    c, h = (lo + hi) / 2, (hi - lo) / 2
    u = (ax - c) / h
    return top - (out_drop * abs(u) ** 2.2 if u > 0 else in_drop * u * u)


def inside(x, y, z):
    """Is the cell centre (x, y, z) inside the body's shell?"""
    ax = abs(x)
    if y < bottom(z) or z < NOSE + max(0.0, 10 - y) * 0.6 or z > TAIL:
        return False                                     # the chin and the tail tucked under
    t = tuck(y)
    if ax <= hull_w(z) - t and y < hull_top(z, ax):
        return True
    if z < 16:
        lo, hi = ff_span(z)
        if ax <= hi - t and y < fender_top(round(ff_top(z)), lo, hi, ax, 3.8, 5.8):
            return True
    if z > -4:
        lo, hi = rf_span(z)
        if ax <= hi - t and y < fender_top(round(rf_top(z)), lo, hi, ax, 3.4, 5.0):
            return True
    return False


def in_arch(ax, y, z):
    return ax > TRACK_IN and any(math.hypot(z - wz, y - WY) < ARCH for wz in (WF, WR))


def cock_w(z):
    """The cockpit's half width (inside its walls), rounded at the back."""
    w = hull_w(z) - 2.5
    if z > CK1 - 6:
        w -= (z - (CK1 - 6)) ** 2 * 0.25
    return w


# ================================================================================================
# The cells
# ================================================================================================

HEAD = (23.5, 19.0, 3.6, 5.0)       # headlamp: |x|, y, lens radius, bezel radius
PARK = (20.5, 11.5, 1.4, 2.4)       # parking lamp under it
TAILL = (23.5, 17.0, 2.6, 3.6)      # tail lamp
GRILLE = (0.0, 14.5, 9.5, 5.2)      # the oval grille: x, y, semi-axes


@lru_cache(maxsize=None)
def _cells():
    """-> (cells {(x, y, z): label}, group {(x, y, z): group}, door {(x, y, z): side}, face info for the lamps)."""
    cells = {}
    for z in range(NOSE - 1, TAIL + 1):
        zc = z + 0.5
        for x in range(X0, X1):
            xc = x + 0.5
            ys = [y for y in range(0, 34) if inside(xc, y + 0.5, zc)]
            for y in ys:
                # the arches: never through the fender's crown (2 px of it stay over the wheel)
                if not (in_arch(abs(xc), y + 0.5, zc) and y < max(ys) - 2):
                    cells[(x, y, z)] = "paint"
    group = {p: "body" for p in cells}

    # the wheel wells: the cells the arch cut laid bare, dark
    for (x, y, z) in list(cells):
        s = 1 if x >= 0 else -1
        for k in (1, 2):
            n = (x + s * k, y, z)
            if n not in cells and abs(n[0] + 0.5) > TRACK_IN and inside(n[0] + 0.5, y + 0.5, z + 0.5) \
                    and (x - s, y, z) in cells and (x, y + 1, z) in cells:
                cells[(x, y, z)] = "well"
                break

    # --- the cockpit: hollowed out between the dash and the seat backs ---------------------------
    hollow = set()
    for (x, y, z) in list(cells):
        zc, ax = z + 0.5, abs(x + 0.5)
        if DASH < zc < CK1 and y >= FLOOR and ax < cock_w(zc):
            del cells[(x, y, z)]
            del group[(x, y, z)]
            hollow.add((x, y, z))
    # the tunnel along the middle (carpet)
    for x in range(-3, 3):
        for y in range(FLOOR, 11):
            for z in range(DASH, 20):
                if (x, y, z) in hollow:
                    hollow.discard((x, y, z))
                    cells[(x, y, z)] = "carpet"
                    group[(x, y, z)] = "body"
    for p in list(cells):
        x, y, z = p
        top = (x, y + 1, z) not in cells
        near = lambda d: any(q in hollow for q in d)
        if top and near([(x + 1, y, z), (x - 1, y, z), (x + 2, y, z), (x - 2, y, z), (x, y, z + 1), (x, y, z - 1),
                         (x, y, z + 2), (x, y, z - 2), (x, y + 1, z + 1), (x, y + 1, z - 1)]):
            cells[p] = "roll"                                   # the leather roll round the cockpit
        elif (x, y, z + 1) in hollow and z + 1 == DASH:
            cells[p] = "dash" if y >= 15 else "carpet"           # the dash's face; the footwell under it
        elif (x, y + 1, z) in hollow:
            cells[p] = "carpet"
        elif near([(x + 1, y, z), (x - 1, y, z), (x, y, z + 1), (x, y, z - 1)]):
            cells[p] = "card"                                   # door cards, the cockpit's walls

    def front(x, y, lo=-999):
        zs = [z for z in range(NOSE - 1, NOSE + 30) if (x, y, z) in cells]
        return min(zs) if zs else None

    def back(x, y):
        zs = [z for z in range(TAIL - 30, TAIL + 1) if (x, y, z) in cells]
        return max(zs) if zs else None

    def outer(y, z, s):
        xs = [x for x in range(X0, X1) if (x, y, z) in cells and (x + 0.5) * s > 0]
        return (max(xs) if s > 0 else min(xs)) if xs else None

    lamp_z = {}

    def fill(x, y, z0, z1, label):
        """cells from z0 to z1 (both included, either order) of the column (x, y)"""
        for z in range(min(z0, z1), max(z0, z1) + 1):
            cells[(x, y, z)] = label
            group[(x, y, z)] = "body"

    def round_feature(cx, cy, r_in, r_out, label, face, key=None):
        """A round lens in a chrome bezel on the front (face -1) or the back (face 1) of the body: the bezel's face flat,
        a pixel proud of the body where it stands furthest out, the lens flat a pixel back inside it."""
        cols = {}
        for x in range(int(cx - r_out) - 1, int(cx + r_out) + 2):
            for y in range(int(cy - r_out) - 1, int(cy + r_out) + 2):
                r = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                f = front(x, y) if face < 0 else back(x, y)
                if r < r_out and f is not None:
                    cols[(x, y)] = (f, r)
        # faired in: the bezel's face where the body's surface stands on average there, a pixel proud
        mean = sum(f for f, _ in cols.values()) / len(cols)
        zp = int(round(mean)) + face
        for (x, y), (f, r) in cols.items():
            lens = r < r_in
            stop = zp - face if lens else zp
            # whatever stands out past the bezel's (or the lens') face goes
            for z in range(min(f, stop), max(f, stop) + 1):
                if (z - stop) * face > 0:
                    cells.pop((x, y, z), None)
                    group.pop((x, y, z), None)
            fill(x, y, stop, f if (f - stop) * face < 0 else stop, label if lens else "bezel")
        if key:
            lamp_z[key] = zp - face + (1 if face > 0 else 0)          # the lens' face

    for s in (1, -1):
        round_feature(s * HEAD[0], HEAD[1], HEAD[2], HEAD[3], "lens", -1, key=("head", s))
        round_feature(s * PARK[0], PARK[1], PARK[2], PARK[3], "amber", -1)
        round_feature(s * TAILL[0], TAILL[1], TAILL[2], TAILL[3], "tail", 1, key=("tail", s))

    # the grille: an oval opening 2 px deep with the egg-crate mesh at its back, a flat chrome lip round it standing a
    # pixel proud of the nose, a chrome bar down the middle
    gx, gy, ga, gb = GRILLE
    cols = {}
    for x in range(-14, 14):
        for y in range(6, 24):
            xc, yc = x + 0.5, y + 0.5
            e = math.hypot((xc - gx) / ga, (yc - gy) / gb)
            e2 = math.hypot((xc - gx) / (ga + 1.5), (yc - gy) / (gb + 1.5))
            f = front(x, y)
            if f is not None and e2 < 1:
                cols[(x, y)] = (f, e)
    zp = min(f for f, _ in cols.values()) - 1
    for (x, y), (f, e) in cols.items():
        if e >= 1:
            fill(x, y, zp, f, "bezel")
        elif x in (-1, 0):
            fill(x, y, zp + 1, f, "chrome")
            cells.pop((x, y, zp), None)
        else:
            for z in range(zp, zp + 2):
                cells.pop((x, y, z), None)
                group.pop((x, y, z), None)
            fill(x, y, zp + 2, max(f, zp + 2), "mesh")

    # side vents behind the front wheels: chrome strakes over a dark slot, a chrome frame
    for s in (1, -1):
        for z in range(-26, -18):
            for y in range(13, 22):
                xo = outer(y, z, s)
                if xo is None:
                    continue
                frame = z in (-26, -19) or y in (13, 21)
                if frame or y % 2 == 1:
                    cells[(xo, y, z)] = "chrome"
                else:
                    cells.pop((xo, y, z), None)
                    group.pop((xo, y, z), None)
                    if (xo - s, y, z) in cells:
                        cells[(xo - s, y, z)] = "vent"

    # the chrome strip along the flanks, a pixel proud, from the front arch to the rear one
    strip = []
    for s in (1, -1):
        for z in range(WF + 17, WR - 16):
            xo = outer(STRIP, z, s)
            if xo is None or (-27 <= z < -18):
                continue
            strip.append((xo + s, STRIP, z))
    for q in strip:
        cells[q] = "strip"
        group[q] = "body"

    # the hood's chrome centre strip, the plate's recess at the back
    for z in range(NOSE + 5, COWL - 2):
        for x in (-1, 0):
            ys = [y for y in range(0, 34) if (x, y, z) in cells]
            if ys:
                cells[(x, max(ys), z)] = "chrome"
    for x in range(-9, 9):
        for y in range(10, 15):
            b = back(x, y)
            if b is not None:
                cells[(x, y, b)] = "recess"

    # --- the doors: the side between the seams, outside the cockpit's walls -------------------------------
    door = {}
    for p in list(cells):
        x, y, z = p
        zc, ax = z + 0.5, abs(x + 0.5)
        if D0 <= z < D1 and y >= FLOOR and ax >= cock_w(zc) - 0.01 and group.get(p) == "body":
            door[p] = -1 if x < 0 else 1
    return cells, group, door, lamp_z


def _bumpers(cells, group):
    """Chrome blade bumpers wrapping round the corners, with bullet overriders, on dark brackets."""
    for front in (True, False):
        for x in range(-27, 27):
            ax = abs(x + 0.5)
            back_off = 0 if ax < 18 else 1 if ax < 21 else 3 if ax < 24 else 6      # wrapping round the corners
            for y in range(6, 11):
                for d in range(4):
                    # the blade: 5 px tall, 4 deep, its four long edges rounded off
                    if (y in (6, 10)) and d in (0, 3):
                        continue
                    if front:
                        z = int(math.floor(NOSE - 5 + back_off)) + d
                    else:
                        z = int(math.ceil(TAIL + 5 - back_off)) - 1 - d
                    q = (x, y, z)
                    if q not in cells:
                        cells[q] = "chrome"
                        group[q] = "bumper"
        # the brackets
        for s in (1, -1):
            for x in range(int(s * 13.5) - 1, int(s * 13.5) + 1):
                for y in (8, 9):
                    z0 = NOSE - 2 if front else TAIL + 1
                    for z in range(z0, z0 + 3) if front else range(z0 - 2, z0 + 1):
                        q = (x, y, z)
                        if q not in cells:
                            cells[q] = "under"
                            group[q] = "bumper"
        # the overriders: upright bullets standing on the blade
        for s in (1, -1):
            cx = s * 10.5
            cz = NOSE - 5.5 if front else TAIL + 5.5
            top = 16.0 if front else 14.5
            for x in range(int(cx) - 3, int(cx) + 3):
                for z in range(int(cz) - 4, int(cz) + 4):
                    for y in range(4, 19):
                        xc, yc, zc = x + 0.5, y + 0.5, z + 0.5
                        r = math.hypot(xc - cx, (zc - cz) * 0.8)
                        rad = 1.8
                        if yc > top - 2:
                            rad *= math.sqrt(max(0.0, 1 - ((yc - (top - 2)) / 2) ** 2))
                        if yc < 5.5:
                            rad *= 0.8
                        if r < rad:
                            q = (x, y, z)
                            cells[q] = "chrome"
                            group[q] = "bumper"


def _seats(cells, group):
    """Two bucket seats: a cushion, a reclined back with rounded shoulders and side bolsters."""
    for s in (1, -1):
        cx = s * SEAT_X
        for x in range(int(cx) - 8, int(cx) + 8):
            dx = abs(x + 0.5 - cx)
            if dx > 7:
                continue
            for z in range(2, 30):
                zc = z + 0.5
                for y in range(FLOOR, 38):
                    yc = y + 0.5
                    q = (x, y, z)
                    if q in cells:
                        continue
                    # the cushion: its front rolled under, a little higher at the bolsters
                    top = 11.5 + (1.0 if dx > 4.8 else 0) - max(0, 4 - (zc - 2)) * 0.6
                    cushion = 2 <= zc <= 22 and yc < top and dx < 6.6
                    # the back: reclined (it leans back 1 px every 4 up), shoulders rounded, bolsters forward
                    lean = min(3.0, max(0.0, (yc - 10) * 0.25))
                    zf = 20 + lean - (1.5 if dx > 4.8 and yc < 26 else 0)
                    zb = 25 + lean
                    ytop = 37 - max(0.0, dx - 2.5) ** 1.8 * 0.55
                    backrest = zf <= zc <= zb and yc < ytop and dx < 6.8 and yc > 9
                    if (cushion or backrest) and zc < CK1 + 3:
                        cells[q] = "seat"
                        group[q] = "seat_l" if s < 0 else "seat_r"


def _soft_top(cells, group):
    """The folded soft top behind the seats: a fabric bundle under its cover, the folds showing at the ends."""
    for x in range(-24, 24):
        xc = x + 0.5
        u = abs(xc) / 23.0
        for z in range(CK1 + 2, CK1 + 12):
            zc = z + 0.5
            v = abs(zc - (CK1 + 7)) / 5.1
            if u >= 1 or v >= 1:
                continue
            base = _top_of(cells, x, z)
            if base is None:
                continue
            h = 3.8 * (1 - u ** 6) ** 0.5 * (1 - v ** 4) ** 0.5
            ytop = deck(zc) - 1.2 * (abs(xc) / 18) ** 2 + h
            for y in range(base, int(math.ceil(ytop))):
                if y + 0.5 < ytop:
                    cells[(x, y, z)] = "soft" if u > 0.88 else "cover"
                    group[(x, y, z)] = "soft"


# ================================================================================================
# Light: every texel's normal and occlusion from a smoothed copy of its solid
# ================================================================================================

def _blur(a, sigma=3.0, r=8):
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    for axis in range(3):
        p = np.pad(a, [(r, r) if i == axis else (0, 0) for i in range(3)], mode="constant")
        out = np.zeros_like(a)
        n = a.shape[axis]
        for i, w in enumerate(k):
            sl = [slice(None)] * 3
            sl[axis] = slice(i, i + n)
            out += w * p[tuple(sl)]
        a = out
    return a


class Field:
    def __init__(self, cells):
        shape = (X1 - X0 + 8, Y1 - Y0 + 8, Z1 - Z0 + 8)
        occ = np.zeros(shape, dtype=np.float32)
        for (x, y, z) in cells:
            occ[x - X0 + 4, y - Y0 + 4, z - Z0 + 4] = 1
        self.b = _blur(occ)
        gx, gy, gz = np.gradient(self.b)
        n = np.sqrt(gx * gx + gy * gy + gz * gz) + 1e-6
        self.n = (-gx / n, -gy / n, -gz / n)

    def idx(self, p):
        i, j, k = p[0] - X0 + 4, p[1] - Y0 + 4, p[2] - Z0 + 4
        return (min(max(i, 0), self.b.shape[0] - 1), min(max(j, 0), self.b.shape[1] - 1), min(max(k, 0), self.b.shape[2] - 1))

    def normal(self, p):
        q = self.idx(p)
        return float(self.n[0][q]), float(self.n[1][q]), float(self.n[2][q])

    def normal_at(self, p):
        """The normal at a point (drawn px), blended from the 8 cells round it: smooth across the 1 px steps."""
        u = [p[0] - 0.5 - X0 + 4, p[1] - 0.5 - Y0 + 4, p[2] - 0.5 - Z0 + 4]
        i0 = [min(max(int(math.floor(v)), 0), self.b.shape[k] - 2) for k, v in enumerate(u)]
        f = [min(max(u[k] - i0[k], 0.0), 1.0) for k in range(3)]
        out = [0.0, 0.0, 0.0]
        for dx in (0, 1):
            for dy in (0, 1):
                for dz in (0, 1):
                    w = (f[0] if dx else 1 - f[0]) * (f[1] if dy else 1 - f[1]) * (f[2] if dz else 1 - f[2])
                    q = (i0[0] + dx, i0[1] + dy, i0[2] + dz)
                    for k in range(3):
                        out[k] += w * float(self.n[k][q])
        return tuple(out)

    def occ(self, p):
        return float(self.b[self.idx(p)])


FACE_N = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
LIGHT = (0.38, 0.82, -0.43)


def _lit(fields, groups, everything, c, flat=0.12):
    """(normal, diffuse, occlusion) of a texel: its solid's smoothed normal, a little of the face's own."""
    fn = FACE_N[c.face]
    g = groups.get(c.i)
    n = fields[g].normal_at(c.p) if g in fields else fn
    n = tuple(n[k] * (1 - flat) + fn[k] * flat for k in range(3))
    ln = math.sqrt(sum(v * v for v in n)) or 1
    n = tuple(v / ln for v in n)
    dif = max(0.0, abs(n[0]) * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2])     # lit alike on both sides
    out = (c.i[0] + fn[0], c.i[1] + fn[1], c.i[2] + fn[2])
    ao = max(0.0, everything.occ(out) - 0.3)
    return n, dif + _dither(c), ao


BAYER = ((0, 2), (3, 1))


def _dither(c):
    """An ordered 2 x 2 dither (a quarter of a colour step either way), so the painted gradients do not band."""
    a, b = [math.floor(c.p[k]) for k in range(3) if FACE_N[c.face][k] == 0]
    return (BAYER[a % 2][b % 2] - 1.5) / 1.5 * 0.09


def _paint_t(n, dif, ao):
    """Car paint: lit from above, the sky in the crowns, a sheen along the shoulders, darker underneath."""
    t = 0.2 + 0.42 * dif + 0.2 * max(0.0, n[1])
    t += 0.16 * smooth(1 - abs(n[1] - 0.35) / 0.16)
    if n[1] < -0.2:
        t -= 0.12
    return t - 0.9 * ao


def _chrome_t(n, dif, ao):
    """Chrome: the sky above (near white), a dark horizon, the ground below (mid grey), a hot spot towards the light."""
    ny = n[1]
    if ny > 0.42:
        t = 0.82 + 0.18 * ny
    elif ny > 0.08:
        t = 0.18 + 0.3 * (ny - 0.08) / 0.34
    elif ny > -0.25:
        t = 0.5 + 0.1 * ny
    else:
        t = 0.36
    if dif > 0.93:
        t = 1.0
    return t - 0.7 * ao


# ================================================================================================
# The car
# ================================================================================================

_KIT = {}


def classic_roadster(colour="rd_green", lower=None, leather="rd_tan", soft="rd_canvas", name=NAME):
    return roadster_kit(colour, lower, leather, soft, name).body


def roadster_kit(colour="rd_green", lower=None, leather="rd_tan", soft="rd_canvas", name=NAME):
    key = (colour, lower, leather, soft, name, tuple(sorted(REMAP.items())))
    if key in _KIT:
        return _KIT[key]
    kit = Kit(name, ("door_l", "door_r"), scale=4.0)
    m = kit.body
    m.display = presets.furniture(gui_scale=0.4)

    base, group0, door, lamp_z = _cells()
    cells, group = dict(base), dict(group0)
    _bumpers(cells, group)
    _seats(cells, group)
    _soft_top(cells, group)
    fields = {g: Field([p for p, v in group.items() if v == g]) for g in set(group.values())}
    everything = Field(list(cells))
    labels = cells

    def body_colour(y):
        return lower if lower and y < STRIP else colour

    def decal(c):
        x, y, z = c.p
        lab = labels.get(c.i)
        f = c.face
        n, dif, ao = _lit(fields, group, everything, c)
        if lab is None:
            return col("rd_dark", 0.2)
        if f == "down" and lab not in ("chrome", "seat"):
            return col("rd_dark", 0.1)
        ix, iy, iz = c.i
        ax = abs(x)
        if lab == "paint":
            t = _paint_t(n, dif, ao)
            # shut lines: the doors' seams, the hood's and the boot lid's
            seam = False
            if (iz in (D0 - 1, D1) or iz in (D0, D1 - 1) and f == "up") and iy >= FLOOR - 1 and f != "down":
                seam = iz in (D0 - 1, D1) and f in ("east", "west", "up")
            if f == "up" and ((15 <= ax < 16 and NOSE + 6 < z < COWL - 3) or (COWL - 4 <= z < COWL - 3 and ax < 16)):
                seam = True
            if f == "up" and ((14 <= ax < 15 and 39 < z < 63) or ((39 <= z < 40 or 62 <= z < 63) and ax < 15)):
                seam = True
            if seam:
                t -= 0.45
            return col(body_colour(y), t)
        if lab == "chrome":
            return col("rd_chrome", _chrome_t(n, dif, ao))
        if lab == "bezel":
            return _bezel(c)
        if lab == "strip":
            return col("rd_chrome", 1.0 if f == "up" else 0.4 if f == "down" else 0.88 if f in ("east", "west") else 0.7)
        if lab == "well" or lab == "under":
            return col("rd_dark", 0.35 - ao)
        if lab == "vent":
            return col("rd_dark", 0.15)
        if lab == "recess":
            return col(body_colour(y), _paint_t(n, dif, ao) - 0.35)
        if lab == "mesh":
            if f != "north":
                return col("rd_chrome", 0.3)
            gx, gy = ix % 3, iy % 3
            if gx == 0 and gy == 0:
                return col("rd_chrome", 0.85)
            if gy == 0:
                return col("rd_chrome", 0.7)
            if gx == 0:
                return col("rd_chrome", 0.45 if gy == 1 else 0.58)
            return col("rd_dark", 0.0 if gy == 2 else 0.25)       # the holes, shadowed under each bar
        if lab in ("lens", "amber", "tail"):
            return _lens(lab, c)
        if lab in ("card", "roll", "carpet", "seat"):
            return _leather(lab, leather, c, n, dif, ao)
        if lab == "dash":
            return _dash(c)
        if lab in ("cover", "soft"):
            return _canvas(lab, soft, c, n, dif, ao)
        return col(colour, 0.5)

    paint = Paint("rd_dark", 1, noise=0, light=False, decal=decal)
    door_cells = {-1: {}, 1: {}}
    body_cells = {}
    for p in cells:
        if p in door:
            door_cells[door[p]][p] = "x"
        else:
            body_cells[p] = "x"
    _flat_voxels(m, body_cells, paint)
    _flat_voxels(kit["door_l"], door_cells[-1], paint)
    _flat_voxels(kit["door_r"], door_cells[1], paint)

    _windscreen(m)
    _dash_parts(m)
    _steering(m, leather)
    _rack(m)
    _trim(m, kit, base, door, colour)
    _exhausts(m)
    _KIT[key] = kit
    return kit


def _flat_voxels(m, cells, paint):
    """bake.voxels with the game's face shading off: the light is painted (a stepped surface reads as one panel)."""
    from stsv_assets.bake import voxels
    box = m.box

    def flat_box(name, frm, to, p, **kw):
        kw["shade"] = False
        return box(name, frm, to, p, **kw)
    m.box = flat_box
    try:
        voxels(m, "shell", cells, lambda _: paint)
    finally:
        del m.box


# --- paints of the small parts ------------------------------------------------------------------------

def _lens(kind, c):
    x, y, z = c.p
    ax = abs(x)
    if kind == "lens":
        cx, cy, r0 = HEAD[0], HEAD[1], HEAD[2]
    elif kind == "amber":
        cx, cy, r0 = PARK[0], PARK[1], PARK[2]
    else:
        cx, cy, r0 = TAILL[0], TAILL[1], TAILL[2]
    if c.face not in ("north", "south"):
        return col("rd_chrome", 0.4)
    dx, dy = ax - cx, y - cy
    r = math.hypot(dx, dy)
    hl = -1.6 < dy * 0.8 + (-dx if x > 0 else dx) * 0.6 - 1.4 < 0.6 and r > 0.8 and r < r0 - 0.6
    if kind == "lens":
        if r < 0.9:
            return col("rd_lens", 1.0)                          # the bulb
        if hl and (math.floor(dx * 2) + math.floor(dy * 2)) % 3 == 0:
            return col("rd_lens", 1.0)                          # a glint on the glass
        ring = 0.5 + 0.22 * math.cos(r * 2.6)                   # the reflector's rings
        flute = 0.08 if math.floor(x * 1.0) % 2 else 0          # the lens' fluting
        return col("rd_lens", ring - flute - 0.25 * smooth((r - r0 + 0.9) / 0.9))
    if kind == "amber":
        return col("rd_amber", 1.0 if r < 0.7 else 0.55 if not hl else 0.9)
    if r < 0.8:
        return col("rd_tail", 0.95)
    if hl:
        return col("rd_tail", 1.0)
    return col("rd_tail", 0.45 + 0.2 * math.cos(r * 3.0))


def _bezel(c):
    """A chrome ring round a lamp or the grille: lit round its upper half (the light comes from above and in front)."""
    x, y, z = c.p
    ax = abs(x)
    centres = [(HEAD[0], HEAD[1], 6), (PARK[0], PARK[1], 3.5), (TAILL[0], TAILL[1], 4.5)]
    cx, cy = 0.0, GRILLE[1]
    for hx, hy, r in centres:
        if math.hypot(ax - hx, y - hy) < r:
            cx, cy = (hx if x > 0 else -hx), hy
            break
    if c.face == "up":
        return col("rd_chrome", 1.0)
    if c.face == "down":
        return col("rd_chrome", 0.3)
    a = math.atan2(y - cy, (x - cx) * (1 if cx != 0 or True else 1))
    t = 0.62 + 0.36 * math.sin(a)
    if c.face in ("east", "west"):
        t -= 0.15
    return col("rd_chrome", t)


def _leather(kind, leather, c, n, dif, ao):
    x, y, z = c.p
    t = 0.2 + 0.42 * dif + 0.18 * max(0.0, n[1]) - 0.8 * ao
    if kind == "carpet":
        return col(leather, 0.08 + 0.1 * ((math.floor(x) * 7 + math.floor(z) * 13) % 3) / 2 - 0.4 * ao)
    if kind == "roll":
        return col(leather, t + 0.08)
    if kind == "card":
        if c.face in ("east", "west"):
            fy = math.floor(y)
            if fy == 21:
                return col("rd_chrome", 0.8)                     # a chrome strip along the card
            if fy < 20 and (math.floor(z) - 1) % 4 == 0:
                return col(leather, t - 0.16)                    # pleats
            if fy == 13 and math.floor(z) % 2:
                return col(leather, t + 0.2)                     # stitching
        return col(leather, t)
    # the seats: fluted inserts with stitching between the flutes, plain bolsters with piping on the edges
    cx = SEAT_X if x > 0 else -SEAT_X
    dx = x - cx
    if c.face in ("north", "up") and abs(dx) < 4.6:
        k = math.floor(dx + 4.6)
        if k % 2 == 0 and c.face == "north" and 11 < y < 29:
            return col(leather, t + 0.12 if math.floor(y) % 2 else t + 0.02)      # the seam between flutes, stitched
        if k % 2 == 0 and c.face == "up" and z > 4:
            return col(leather, t + 0.12 if math.floor(z) % 2 else t + 0.02)
        return col(leather, t - 0.05 + (0.1 if k % 2 else 0))
    if abs(abs(dx) - 4.8) < 0.5:
        return col(leather, t + 0.22)                            # the piping
    return col(leather, t)


def _dash(c):
    """The walnut dash: a varnished grain, the glove box's outline and its chrome lock on the passenger's side."""
    x, y, z = c.p
    if c.face != "south":
        return col("rd_wood", 0.5)
    if 6 <= x < 17 and 16 <= y < 23:
        if math.floor(x) in (6, 16) or math.floor(y) in (16, 22):
            return col("rd_wood", 0.18)
        if math.floor(x) == 11 and math.floor(y) == 21:
            return col("rd_chrome", 1.0)
    g = math.sin(y * 1.7 + math.sin(x * 0.35) * 1.8)
    t = 0.5 + 0.12 * g + (0.18 if 23.5 < y < 24.5 else 0)
    return col("rd_wood", t)


def _canvas(kind, soft, c, n, dif, ao):
    x, y, z = c.p
    t = 0.18 + 0.5 * dif + 0.15 * max(0.0, n[1]) - 0.8 * ao
    if kind == "soft":
        return col(soft, t + (0.12 if math.floor(y) % 2 else -0.05))    # the folded fabric's layers
    if c.face == "up" and abs(z - (CK1 + 7)) < 0.6:
        return col(soft, t - 0.18)                              # the cover's seam along its top
    if c.face in ("north", "south") and y < deck(z) + 1.6 and math.floor(x) % 5 == 0:
        return col("rd_chrome", 0.95)                           # press studs along its edge
    return col(soft, t + (0.04 if (math.floor(x) + math.floor(z)) % 2 else 0))


CHROME_BOX = Paint("rd_chrome", 5, noise=0, grad=0, rim=0.6, ao=0.4)


def _chrome_decal(c):
    """Chrome on plain boxes: bright tops, a dark horizon on the sides, the undersides mid grey."""
    f = c.face
    if f == "up":
        return col("rd_chrome", 0.95)
    if f == "down":
        return col("rd_chrome", 0.35)
    v = c.v / max(1, c.nv - 1) if c.nv > 1 else 0.5
    return col("rd_chrome", 0.85 if v < 0.34 else 0.25 if v < 0.6 else 0.55)


CHROME = Paint("rd_chrome", 5, noise=0, light=False, decal=_chrome_decal)


def _windscreen(m):
    """The low windscreen raked back 22.5 degrees in a chrome frame: posts, a top rail, a mirror; wipers at its foot."""
    rake = ("x", 22.5, (0, 26.5, COWL - 0.5))
    m.box("screen_foot", (-21.25, 25.6, COWL - 1.75), (21.25, 27.3, COWL + 0.75), CHROME)

    def pane(c):
        x, y, z = c.p
        if c.face not in ("north", "south"):
            return col("rd_chrome", 0.8)
        u = math.floor(x * 0.9 + y * 1.3) % 28
        if u in (0, 1, 5):
            return ("raw", (232, 242, 255, 255)) if u != 5 else ("raw", (176, 196, 222, 255))
        if 27.4 < y < 28.2 and (-18 < x < -5 or 3 < x < 16):
            return ("raw", (24, 26, 30, 255))                    # the wipers lying at its foot
        return ("raw", (0, 0, 0, 0))
    m.box("windscreen", (-19.5, 27.0, COWL - 0.75), (19.5, 38.5, COWL - 0.25),
          Paint("glass", 1, noise=0, light=False, decal=pane), rotation=rake)
    for s in (1, -1):
        a, b = sorted((s * 19.5, s * 20.75))
        m.box("screen_post", (a, 26.5, COWL - 1.1), (b, 39.5, COWL + 0.1), CHROME, rotation=rake)
    m.box("screen_top", (-19.5, 38.5, COWL - 1.0), (19.5, 39.5, COWL), CHROME, rotation=rake)
    m.box("screen_bottom", (-19.5, 26.6, COWL - 1.0), (19.5, 27.2, COWL), CHROME, rotation=rake)
    m.box("mirror_stem", (-0.4, 39.5, COWL - 0.8), (0.4, 40.7, COWL - 0.2), CHROME, rotation=rake)

    def mirror(c):
        if c.face == "south" and 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1:
            return ("raw", (190, 206, 226, 255)) if c.u + c.v < 5 else ("raw", (120, 136, 158, 255))
        return _chrome_decal(c)
    m.box("mirror", (-3.25, 40.5, COWL - 1.1), (3.25, 42.6, COWL + 0.1), Paint("rd_chrome", 5, noise=0, light=False, decal=mirror), rotation=rake)


def _gauge(c, cx, cy, r, big):
    x, y = c.p[0], c.p[1]
    if c.face != "south":
        return col("rd_chrome", 0.7)
    dx, dy = x - cx, y - cy
    rr = math.hypot(dx, dy)
    if rr > r - 0.75:
        return col("rd_chrome", 1.0 if dy > 0 else 0.55)        # the chrome ring
    if big:
        a = math.atan2(dy, dx)
        if rr > r - 1.5 and math.floor((a + math.pi) * 12 / math.pi) % 2 == 0 and dy > -1.2:
            return col("rd_dark", 0.2)                           # the scale's ticks
        if abs(dx * 0.6 + dy * 0.8) < 0.45 and dx * 0.8 - dy * 0.6 < 0 and rr < r - 1:
            return col("rd_dark", 0.0)                           # the needle
    elif abs(dy - dx) < 0.5 and rr < r - 0.8:
        return col("rd_dark", 0.0)
    return col("rd_ivory", 0.95 if rr < r * 0.5 else 0.8)


def _dash_parts(m):
    """Chrome-ringed gauges on the dash's face: speedometer and rev counter before the driver, four small dials in the
    middle."""
    face = DASH
    for cx, cy, d in ((-15.0, 21.0, 7), (-6.8, 21.0, 7), (-1.6, 22.8, 3.6), (1.6, 22.8, 3.6), (-1.6, 18.6, 3.6), (1.6, 18.6, 3.6)):
        big = d > 5
        p = Paint("rd_chrome", 5, noise=0, light=False, decal=lambda c, cx=cx, cy=cy, d=d, big=big: _gauge(c, cx, cy, d / 2, big))
        if big:
            cyl(m, "gauge", (cx, cy), d, face, face + 0.45, p, axis="z")
        else:
            m.box("gauge", (cx - d / 2, cy - d / 2, face), (cx + d / 2, cy + d / 2, face + 0.35), p)


def _steering(m, leather):
    """A big wooden-rim steering wheel on a column from the dash, tilted to the driver; wire spokes, a chrome boss."""
    cx, cy, cz = -SEAT_X, 25.0, -3.0
    tilt = ("x", -22.5, (cx, cy, cz))

    def wood(c):
        x, y, z = c.p
        g = math.floor((x + y) * 1.3) % 3
        return col("rd_wood", 0.75 if c.face == "up" or (c.face == "south" and y > cy) else 0.45 + 0.08 * g)
    rim = Paint("rd_wood", 3, noise=0, light=False, decal=wood)
    R, n = 6.4, 16
    for k in range(n):
        a = 2 * math.pi * k / n
        x, y = cx + R * math.sin(a), cy + R * math.cos(a)
        m.box("wheel_rim", (x - 1.05, y - 1.05, cz - 0.6), (x + 1.05, y + 1.05, cz + 0.6), rim, rotation=tilt)
    spoke = Paint("rd_chrome", 5, noise=0, light=False, decal=lambda c: col("rd_chrome", 0.95 if c.face in ("up", "south") else 0.5))
    for s in (1, -1):
        for dy in (-0.45, 0.45):
            a, b = sorted((cx + s * 1.3, cx + s * (R - 0.6)))
            m.box("wheel_spoke", (a, cy + dy - 0.17, cz - 0.17), (b, cy + dy + 0.17, cz + 0.17), spoke, rotation=tilt)
    for dx in (-0.45, 0.45):
        m.box("wheel_spoke", (cx + dx - 0.17, cy - R + 0.6, cz - 0.17), (cx + dx + 0.17, cy - 1.3, cz + 0.17), spoke, rotation=tilt)
    m.box("wheel_boss", (cx - 1.4, cy - 1.4, cz - 0.9), (cx + 1.4, cy + 1.4, cz + 0.8), CHROME, rotation=tilt)
    m.box("wheel_horn", (cx - 0.7, cy - 0.7, cz + 0.8), (cx + 0.7, cy + 0.7, cz + 1.1),
          Paint("rd_ivory", 3, noise=0, light=False, decal=lambda c: col("rd_ivory", 0.9)), rotation=tilt)
    m.box("column", (cx - 0.6, cy - 0.6, cz - 8.0), (cx + 0.6, cy + 0.6, cz - 0.9),
          Paint("rd_dark", 1, noise=0, light=False, decal=lambda c: col("rd_dark", 0.6 if c.face == "up" else 0.3)), rotation=tilt)
    # the gear lever on the tunnel: a chrome rod, a wooden knob
    m.box("gear_lever", (-0.35, 10.6, 3.6), (0.35, 16.6, 4.3), CHROME)
    m.box("gear_knob", (-0.95, 16.3, 3.0), (0.95, 18.0, 4.9), Paint("rd_wood", 3, noise=0, light=False, decal=lambda c: col("rd_wood", 0.85 if c.face == "up" else 0.5)))
    m.box("handbrake", (2.6, 10.6, 8.0), (3.3, 12.6, 12.5), CHROME)


def _rack(m):
    """A chrome luggage rack on the boot lid, lying along its 22.5 degree fall: two rails turned up at the ends, five
    slats, four feet."""
    z0 = 44.0
    y0 = deck(z0) + 1.6
    tilt = ("x", 22.5, (0, y0, z0))
    length = 18.0 / math.cos(math.radians(22.5))
    rail = Paint("rd_chrome", 5, noise=0, light=False, decal=lambda c: col("rd_chrome", 0.98 if c.face == "up" else 0.3 if c.face == "down" else 0.65))
    for s in (1, -1):
        a, b = sorted((s * 10.0, s * 10.8))
        m.box("rack_rail", (a, y0, z0), (b, y0 + 0.8, z0 + length), rail, rotation=tilt)
        m.box("rack_end", (a, y0 - 1.6, z0 - 0.8), (b, y0 + 1.4, z0), rail, rotation=tilt)
        m.box("rack_end", (a, y0 - 1.6, z0 + length), (b, y0 + 1.4, z0 + length + 0.8), rail, rotation=tilt)
        for zz in (z0 + 2.5, z0 + length - 3.0):
            m.box("rack_foot", (a + 0.1, y0 - 2.4, zz), (b - 0.1, y0, zz + 0.8), rail, rotation=tilt)
    for k in range(5):
        zz = z0 + 1.5 + k * (length - 3.8) / 4
        m.box("rack_slat", (-10.0, y0 + 0.15, zz), (10.0, y0 + 0.65, zz + 0.9), rail, rotation=tilt)


def _top_of(cells, x, z):
    ys = [y for y in range(0, 40) if (x, y, z) in cells]
    return max(ys) + 1 if ys else None


def _trim(m, kit, cells, door, colour):
    """The bullet wing mirror, the door handles, the fuel filler cap, the soft top's hood irons."""
    # the wing mirror on the left front fender, near the cowl
    mx, mz = -24, -24
    top = _top_of(cells, mx, mz)
    m.box("wing_mirror_stem", (mx + 0.1, top - 0.5, mz + 0.1), (mx + 0.9, top + 3.0, mz + 0.9), CHROME)

    def bullet(c):
        if c.face == "south" and 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1:
            return ("raw", (196, 212, 232, 255)) if c.u + c.v < 3 else ("raw", (118, 134, 156, 255))
        if c.face == "up":
            return col("rd_chrome", 1.0)
        if c.face == "down":
            return col("rd_chrome", 0.4)
        return col("rd_chrome", 0.92 if c.p[1] > top + 3.8 else 0.6)
    bp = Paint("rd_chrome", 5, noise=0, light=False, decal=bullet)
    m.box("wing_mirror", (mx - 0.9, top + 2.6, mz - 2.8), (mx + 1.9, top + 4.8, mz + 1.4), bp)
    m.box("wing_mirror_tip", (mx - 0.3, top + 3.1, mz - 4.0), (mx + 1.3, top + 4.3, mz - 2.8), CHROME)
    # door handles: a chrome pull near each door's back top edge
    for s, part in ((-1, "door_l"), (1, "door_r")):
        for z in (D1 - 7,):
            xs = [x for x in range(X0, X1) if (x, 20, z) in cells and (x, 20, z) in door and (x + 0.5) * s > 0]
            xo = (max(xs) + 1) if s > 0 else min(xs)
            a, b = sorted((xo, xo + s * 0.6))
            kit[part].box("door_handle", (a, 19.6, z - 2.5), (b, 20.6, z + 1.5), CHROME)
            a, b = sorted((xo + s * 0.6, xo + s * 1.0))
            kit[part].box("door_button", (a, 19.85, z + 0.4), (b, 20.35, z + 1.2), CHROME)
    # the fuel filler: a chrome knock-off cap on the left rear fender
    fx, fz = -24, 47
    top = _top_of(cells, fx, fz)
    cyl(m, "filler", (fx + 0.5, fz + 0.5), 3.2, top - 0.5, top + 0.6, CHROME)
    m.box("filler_ears", (fx - 1.6, top + 0.6, fz + 0.1), (fx + 2.6, top + 1.0, fz + 0.9), CHROME)


def _exhausts(m):
    """Twin chrome exhausts under the back, on the right."""
    xs, y = (11.05, 14.65), 4.4

    def tip(c):
        if c.face == "south" and any(math.hypot(c.p[0] - x, c.p[1] - y) < 0.75 for x in xs):
            return col("rd_dark", 0.0)
        return _chrome_decal(c)
    p = Paint("rd_chrome", 5, noise=0, light=False, decal=tip)
    for x in xs:
        cyl(m, "exhaust", (x, y), 2.6, 52.3, TAIL + 7.3, p, axis="z")


# ================================================================================================
# The wheel: drawn at twice its size (25.6 px = 0.8 block across), shrunk by 2
# ================================================================================================

def wheel_classic():
    """A chrome wire wheel: 48 crossed spokes laced from the hub to a bright rim, a hub with a two-eared knock-off
    spinner, a tall whitewall tyre with a ribbed tread."""
    m = BModel("wheel_classic", shift=(8, 8, 8), floor=None, scale=2.0)
    d, w2 = 2 * WRAD, 3.4
    R = d / 2
    rim_r = R - 5.6

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            lit = 0.12 if (y > 0 and z < 0) else 0
            if rr > R - 1.4:
                return col("rd_tyre", 0.35 + lit)
            if R - 4.4 < rr < R - 1.9:
                return col("rd_white", 0.75 + 2 * lit if rr < R - 2.4 else 0.45)    # the whitewall, a thin line round it
            return col("rd_tyre", 0.25 + lit)
        x = c.p[0]
        rib = math.floor(x + 3.4) in (1, 5)
        a = math.atan2(z, y)
        lit = y > 0
        return col("rd_tyre", (0.15 if rib else 0.42 + (0.14 if lit else 0)) - (0.06 if math.floor(a * 30) % 2 else 0))
    cyl(m, "tire", (0, 0), d, -w2, w2, Paint("rd_tyre", 1, noise=0, light=False, decal=tire), axis="x")

    # the wire spokes: 24 laced to each side of the hub, crossing on their way to the rim
    spokes = []
    for k in range(24):
        a = 2 * math.pi * k / 24
        lean = 0.55 if k % 2 else -0.55
        h = (1.9 * math.cos(a), 1.9 * math.sin(a))
        r = (rim_r * math.cos(a + lean), rim_r * math.sin(a + lean))
        spokes.append((h, r, k % 2))

    def seg_d(p, a, b):
        ax_, ay_ = b[0] - a[0], b[1] - a[1]
        t = max(0.0, min(1.0, ((p[0] - a[0]) * ax_ + (p[1] - a[1]) * ay_) / (ax_ * ax_ + ay_ * ay_)))
        return math.hypot(p[0] - a[0] - t * ax_, p[1] - a[1] - t * ay_)

    def face(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face not in ("east", "west"):
            return col("rd_chrome", 0.7)
        if rr > rim_r - 0.9:
            return col("rd_chrome", 1.0 if (y > 0 and z < 0) else 0.82)          # the polished rim
        if rr > rim_r - 1.6:
            return col("rd_chrome", 0.3)                                         # the rim's well
        best = min((seg_d((y, z), h, r), side) for h, r, side in spokes)
        if best[0] < 0.5:
            return col("rd_chrome", 0.92 if best[1] else 0.7)                    # a spoke (the outer set brighter)
        return col("rd_dark", 0.05 + 0.25 * (rr / rim_r))                         # the dark wheel inside, deeper at the hub
    p = Paint("rd_chrome", 3, noise=0, light=False, decal=face)
    cyl(m, "wire", (0, 0), 2 * rim_r, w2, w2 + 0.35, p, axis="x")
    cyl(m, "wire", (0, 0), 2 * rim_r, -w2 - 0.35, -w2, p, axis="x")

    # the hub and the knock-off spinner (both sides: the same model turns on both sides of the car)
    def hub(c):
        y, z = c.p[1], c.p[2]
        if c.face in ("east", "west"):
            return col("rd_chrome", 1.0 if (y > 0.3 and z < 0.3) else 0.75)
        return col("rd_chrome", 0.95 if y > 0 else 0.4)
    hp = Paint("rd_chrome", 5, noise=0, light=False, decal=hub)
    for s in (1, -1):
        a, b = sorted((s * (w2 + 0.35), s * (w2 + 1.4)))
        cyl(m, "hub", (0, 0), 4.0, a, b, hp, axis="x")
        a, b = sorted((s * (w2 + 1.4), s * (w2 + 2.4)))
        m.box("spinner", (a, -1.2, -1.2), (b, 1.2, 1.2), hp)
        a, b = sorted((s * (w2 + 1.5), s * (w2 + 2.2)))
        m.box("spinner_ear", (a, -0.55, -3.6), (b, 0.55, 3.6), hp)
        a, b = sorted((s * (w2 + 1.45), s * (w2 + 2.6)))
        for e in (1, -1):
            lo, hi = sorted((e * 2.6, e * 3.8))
            m.box("spinner_tip", (a, -0.9, lo), (b, 0.9, hi), hp)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def _part(part):
    return lambda: roadster_kit().all()[f"{NAME}_{part}"]


# where the lamps are (models/vehicle_lights.py LAMPS: drawn px, scale 4): the faces the lenses lie on
def lamp_faces():
    _, _, _, lamp_z = _cells()
    return {"head": lamp_z[("head", 1)], "tail": lamp_z[("tail", 1)]}


ALL = {
    NAME: classic_roadster,
    "wheel_classic": wheel_classic,
    **{f"{NAME}_{p}": _part(p) for p in ("door_l", "door_r")},
}
