"""
The donation vehicle 사이드카 오토바이 (sidecar_bike): a 1940s touring / military style motorcycle with a torpedo
sidecar on its RIGHT side. An original design: no real make, no badge, no signature shape copied.

Drawn like the showcase car (models/vehicles_showcase.py, read its doc first): 32 px a block. The body and its moving
part are drawn at twice their real size and shrunk by 4 (BModel scale 4), shown at vehicle.body-scale 2; the wheel is
drawn at twice its size and shrunk by 2, shown at 1. So a texel is 1/32 block everywhere on it.

How it is lit: every face is painted with its own light (the game's face shading is off on all of it, like the
showcase car's paint): round parts (the tank, the fenders, the tub, the headlamp, the saddle, the tyres) are sculpted
from 1 px cells of a smooth shape (bake.voxels) and each texel is lit by the shape's normal there, not by the face it
lies on, so a stepped surface reads as one smooth panel. Gloss paint takes a soft key light and a specular streak;
chrome takes a reflected sky / dark horizon / ground (the hard dark band that makes chrome read as chrome).

Drawn coordinates (like every vehicle): the vehicle's ground centre at (0, 0, 0), front = north (-z), +x = its RIGHT
side. The bike runs along x = BX (left of the middle), the sidecar's tub along x = SX (right of it): the pair is
centred so the item file's collision box (centred on the vehicle) covers both.

The bike: a springer fork (rigid rear legs, sprung front legs on a rocker at the axle, two chrome coil springs under
the upper crown), a deep valanced front fender that rides with it, a round headlamp in a chrome bucket with a chrome
bezel, a reflector and a bulb behind its glass, a small speedometer on top of it, a chrome horn under it; wide
pulled-back handlebars with ribbed black grips, levers and one round mirror; a teardrop tank (two-tone: the paint with
a flash of the trim colour each side, a chrome strip down its crown, two chrome filler caps, rubber knee pads in a
chrome strip, a hand-shift lever on its left); a 45-degree air-cooled V-twin with finned barrels and finned heads, a
chrome primary cover on the left, a round timing cover and a carburettor with a round chrome air cleaner on the right;
two chrome exhaust pipes down the left side to stacked fishtail mufflers; a sprung leather solo saddle on two chrome
coil springs; rubber footboards; a black double-cradle frame with plunger rear suspension; a valanced rear fender with
a round tail lamp and a blank licence-plate holder; a leather saddlebag on the left.

The sidecar: a torpedo tub (the paint below a chrome spear moulding, the trim colour above, a polished chrome nose
cone, a chrome roll round the cockpit), a padded and tufted leather seat and back rest, a grab rail, a luggage rack on
the tail deck with a strapped leather case, a spare wheel standing on the tail, a red lamp on the tail, its own wheel
under a valanced mudguard with a small running lamp on it, and the chassis that carries it: two black rails under the
tub, two lower struts and two upper struts to the bike's frame, the wheel's axle tube.

Its moving part (vehicles.yml movers): screen, the sidecar's little windscreen in its chrome frame, which folds
forward on its hinge while the passenger gets in or out.

Riders: the driver astride the saddle (seat 0: a sitting player's thighs lie on the saddle's top), the passenger low
in the tub (seat 1). Paint is an argument: colour (the tank, the fenders, the tub below the spear), trim (the tank's
flash, the fender stripes, the tub above the spear), leather (the saddle, the seat, the bags), whitewall (tyres with a
white wall) - so a tuning colour is one more call (sidecar_bike(colour="sc_olive", trim="sc_olive") is the olive drab
army rig).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, voxels, bands
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp, size as ramp_size
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
_ramp("sc_alloy", 85, 0.010, 0.62, 0.40, 7, False)      # cast aluminium (crankcase, barrels' fins)
_ramp("sc_iron", 250, 0.008, 0.36, 0.30, 6, False)      # cast iron barrels, dark metal
_ramp("sc_rubber", 260, 0.010, 0.31, 0.28, 6, False)
_ramp("sc_leather", 52, 0.075, 0.40, 0.36, 7, False)    # tan saddle leather
_ramp("sc_lamp", 88, 0.070, 0.90, 0.14, 4, True)        # warm lamp glass
_ramp("sc_red", 25, 0.200, 0.48, 0.36, 5, True)         # tail lamp glass
_ramp("sc_white", 90, 0.012, 0.90, 0.12, 4, False)      # plate, dial, whitewall
_ramp("sc_glass", 215, 0.030, 0.82, 0.20, 3, False)


# ================================================================================================
# Light
# ================================================================================================

def _n(v):
    ln = math.sqrt(sum(a * a for a in v)) or 1.0
    return tuple(a / ln for a in v)


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


LIGHT = _n((-0.45, 0.80, -0.40))       # key light: high, from the front left
VIEW = _n((-0.55, 0.42, -0.72))        # the usual eye: front left, a little above
HALF = _n(tuple(a + b for a, b in zip(LIGHT, VIEW)))
FACE_N = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0),
          "west": (-1, 0, 0)}


def _light(kind, n):
    """0 (darkest step) .. 1 (lightest) and a little over for highlights."""
    d = max(0.0, _dot(n, LIGHT))
    up = 0.5 + 0.5 * n[1]
    if kind == "gloss":
        return 0.10 + 0.48 * d + 0.26 * up + 0.55 * max(0.0, _dot(n, HALF)) ** 26
    if kind == "satin":
        return 0.12 + 0.50 * d + 0.28 * up + 0.18 * max(0.0, _dot(n, HALF)) ** 10
    if kind == "matte":
        return 0.12 + 0.52 * d + 0.30 * up
    if kind == "chrome":
        k = _dot(n, VIEW)
        ry = 2 * k * n[1] - VIEW[1]                    # the reflected ray's height: sky, horizon or ground
        if ry > 0.50:
            t = 0.97
        elif ry > 0.22:
            t = 0.74
        elif ry > 0.02:
            t = 0.46
        elif ry > -0.14:
            t = 0.08                                   # the dark horizon band
        elif ry > -0.45:
            t = 0.40
        else:
            t = 0.28
        return t + 0.45 * max(0.0, _dot(n, HALF)) ** 60
    raise KeyError(kind)


def _tone(ramp, kind, n, lo=None, hi=None):
    s = ramp_size(ramp)
    lo = 0 if lo is None else lo
    hi = s - 1 if hi is None else hi
    return (ramp, lo + _light(kind, n) * (hi - lo))


def _grad(f, p, h=0.6):
    x, y, z = p
    g = (f(x + h, y, z) - f(x - h, y, z), f(x, y + h, z) - f(x, y - h, z), f(x, y, z + h) - f(x, y, z - h))
    if abs(g[0]) + abs(g[1]) + abs(g[2]) < 1e-9:
        return None
    return _n(g)


class Mats:
    """material key -> (ramp, kind, lo, hi): what a texel is made of; a paint picks a key per texel."""

    def __init__(self, **kw):
        self.m = kw

    def paint(self, sel, normal=None, glow=False):
        """sel: a key, or c -> key | (ramp, step) | ("raw", rgba) | ("lit", ramp, kind). normal: c -> unit vector."""
        m = self.m

        def decal(c):
            k = sel(c) if callable(sel) else sel
            if k is None:
                k = "paint"
            if isinstance(k, tuple):
                if k[0] != "lit":
                    return k
                n = (normal(c) if normal else None) or FACE_N[c.face]
                return _tone(k[1], k[2], n)
            ramp, kind, lo, hi = m[k]
            n = (normal(c) if normal else None) or FACE_N[c.face]
            return _tone(ramp, kind, n, lo, hi)
        return Paint("sc_chrome", 4, noise=0, light=False, glow=glow, decal=decal)


def field_normal(f):
    return lambda c: _grad(f, c.p)


def radial(axis, c0, c1):
    """Normals of a round part along an axis through (c0, c1) (axis x: (y, z); y: (x, z); z: (x, y)): the face
    normal on its ends."""
    ends = {"x": ("east", "west"), "y": ("up", "down"), "z": ("north", "south")}[axis]

    def n(c):
        if c.face in ends:
            return None
        x, y, z = c.p
        if axis == "x":
            return _n((0, y - c0, z - c1))
        if axis == "y":
            return _n((x - c0, 0, z - c1))
        return _n((x - c0, y - c1, 0))
    return n


class Flat(BModel):
    """A BModel whose faces all skip the game's face shading: their light is painted (see the module doc)."""

    def box(self, name, frm, to, paint, faces=None, rotation=None, glow=False, shade=True, **kw):
        return super().box(name, frm, to, paint, faces=faces, rotation=rotation, glow=glow, shade=False, **kw)


def sculpt(m, name, f, lo, hi, paint, label=None, zstep=None):
    """Cells (1 px) whose middles lie inside f (< 0) within the box lo..hi, merged into boxes. label(p) -> a key (cells
    of one key merge together; None leaves the cell out); paint: a Paint, or key -> Paint. zstep(z) -> 1 or 2: the
    cells' length along z there (2 where the shape changes slowly along z: fewer boxes)."""
    cells = {}
    pf = paint if callable(paint) and not isinstance(paint, Paint) else (lambda k: paint)
    z = math.floor(lo[2])
    zs = []
    while z < math.ceil(hi[2]):
        st = zstep(z) if zstep else 1
        zs.append((z, st))
        z += st
    long_cells = {}
    for z, st in zs:
        for x in range(math.floor(lo[0]), math.ceil(hi[0])):
            for y in range(math.floor(lo[1]), math.ceil(hi[1])):
                p = (x + 0.5, y + 0.5, z + st / 2)
                if f(*p) < 0:
                    k = label(p) if label else "s"
                    if k is not None:
                        if st == 1:
                            cells[(x, y, z)] = k
                        else:
                            long_cells[(x, y, z)] = (k, st)
    voxels(m, name, cells, pf)
    # the long cells: merged along x and y only, then each box drawn st long
    for st in sorted({v[1] for v in long_cells.values()}):
        layer = {}
        for (x, y, z), (k, s2) in long_cells.items():
            if s2 == st:
                layer[(x, y, z)] = k
        for label_ in sorted(set(layer.values()), key=str):
            todo = {p for p, v in layer.items() if v == label_}
            for p in sorted(todo, key=lambda q: (q[2], q[1], q[0])):
                if p not in todo:
                    continue
                x, y, z = p
                x1 = x + 1
                while (x1, y, z) in todo:
                    x1 += 1
                y1 = y + 1
                while all((i, y1, z) in todo for i in range(x, x1)):
                    y1 += 1
                for i in range(x, x1):
                    for j in range(y, y1):
                        todo.discard((i, j, z))
                m.box(name, (x, y, z), (x1, y1, z + st), pf(label_))
    return cells


def tube(m, name, axis, c, d, lo, hi, paint):
    """A round bar of diameter d along an axis (cyl: axis x: c = (y, z), axis z: c = (x, y), y: c = (x, z))."""
    cyl(m, name, c, d, lo, hi, paint, axis=axis)


def ring(m, name, axis, c, rin, rout, lo, hi, paint):
    """A flat ring of 1 px cells (rin <= r < rout round c) from lo to hi along an axis (x: c = (y, z); z: c = (x, y)),
    merged into few boxes."""
    n = int(math.ceil(rout)) + 1
    cells = {(i, j) for i in range(-n, n) for j in range(-n, n) if rin <= math.hypot(i + 0.5, j + 0.5) < rout}
    while cells:
        i, j = min(cells, key=lambda q: (q[1], q[0]))
        i1 = i
        while (i1 + 1, j) in cells:
            i1 += 1
        j1 = j
        while all((k, j1 + 1) in cells for k in range(i, i1 + 1)):
            j1 += 1
        for a in range(i, i1 + 1):
            for b in range(j, j1 + 1):
                cells.discard((a, b))
        if axis == "x":
            m.box(name, (lo, c[0] + i, c[1] + j), (hi, c[0] + i1 + 1, c[1] + j1 + 1), paint)
        else:
            m.box(name, (c[0] + i, c[1] + j, lo), (c[0] + i1 + 1, c[1] + j1 + 1, hi), paint)


def poly_ring(m, name, axis, c, rin, rout, lo, hi, paint, n=16, inner=True, outer=True):
    """A round ring as n straight segments turned about the axis (a model element turns in 22.5 degree steps, so
    n = 16 or 8), from lo to hi along it (axis x: c = (y, z); axis z: c = (x, y)). paint(theta, outward) -> Paint for
    the segment whose middle points at theta (degrees; x axis: from +y towards +z; z axis: from +x towards +y),
    outward: its outward unit normal in drawn space. inner / outer False leave that face out (where it lies against
    another ring). The ends between segments are buried in their neighbours and left out."""
    L = 2 * rout * math.tan(math.pi / n) + 0.06
    for k in range(n):
        th = k * 360.0 / n
        base = round(th / 90.0) % 4 * 90
        phi = th - base
        if phi > 180:
            phi -= 360
        a, b = c
        t = math.radians(th)
        if axis == "x":
            out = (0.0, math.cos(t), math.sin(t))
            # drawn at `base` (0: +y, 90: +z, 180: -y, 270: -z), then turned by phi
            if base in (0, 180):
                sg = 1 if base == 0 else -1
                y0, y1 = sorted((a + sg * rin, a + sg * rout))
                frm, to = (lo, y0, b - L / 2), (hi, y1, b + L / 2)
                fo, fi, ends = ("up", "down") if sg > 0 else ("down", "up"), None, ("north", "south")
            else:
                sg = 1 if base == 90 else -1
                z0, z1 = sorted((b + sg * rin, b + sg * rout))
                frm, to = (lo, a - L / 2, z0), (hi, a + L / 2, z1)
                fo, fi, ends = ("south", "north") if sg > 0 else ("north", "south"), None, ("up", "down")
            rot = ("x", phi, (lo, a, b)) if phi else None
        else:
            out = (math.cos(t), math.sin(t), 0.0)
            if base in (0, 180):
                sg = 1 if base == 0 else -1
                x0, x1 = sorted((a + sg * rin, a + sg * rout))
                frm, to = (x0, b - L / 2, lo), (x1, b + L / 2, hi)
                fo, fi, ends = ("east", "west") if sg > 0 else ("west", "east"), None, ("up", "down")
            else:
                sg = 1 if base == 90 else -1
                y0, y1 = sorted((b + sg * rin, b + sg * rout))
                frm, to = (a - L / 2, y0, lo), (a + L / 2, y1, hi)
                fo, fi, ends = ("up", "down") if sg > 0 else ("down", "up"), None, ("east", "west")
            rot = ("z", phi, (a, b, lo)) if phi else None
        p = paint(th, out)
        faces = {ends[0]: None, ends[1]: None}
        if not outer:
            faces[fo[0]] = None
        if not inner:
            faces[fo[1]] = None
        m.box(name, frm, to, p, faces=faces, rotation=rot)


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def seg_dist(p, a, b):
    """Distance from 2D point p to the segment a-b."""
    ax, ay = a
    bx, by = b
    dx, dy = bx - ax, by - ay
    L = dx * dx + dy * dy
    t = 0 if L == 0 else max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / L))
    return math.hypot(p[0] - ax - t * dx, p[1] - ay - t * dy)


# ================================================================================================
# Layout (drawn px, 32 a block)
# ================================================================================================

BX, SX = -15, 12              # the bike's centre line, the tub's centre line
WY, WRAD = 12, 12.2           # wheel centre height, tyre radius (wheel-radius 0.375 block)
ZF, ZR = -26, 26              # the bike's axles
SWX, SWZ = 29, 10             # the sidecar wheel (x, z)
SADDLE = 31.6                 # the saddle's top: seat 0 at 1.12 block (a sitting player's thighs 4.2 px under it)
RAKE = 22.5                   # the fork's rake (a model element turns in 22.5 degree steps)
FD = (math.cos(math.radians(RAKE)), math.sin(math.radians(RAKE)))     # up the fork: (dy, dz)
FF = (math.sin(math.radians(RAKE)), -math.cos(math.radians(RAKE)))    # across the fork, forward: (dy, dz)
HL = (33.0, -25.0)            # headlamp centre (y, z)
SCREEN_Z, SCREEN_Y = -8.0, 28.5   # the sidecar windscreen's hinge (its foot)


def fork(t, off=0.0):
    """A point (y, z) t px up the fork from the front axle, off px forward of its line."""
    return (WY + t * FD[0] + off * FF[0], ZF + t * FD[1] + off * FF[1])


def _ring_normal(c, out, ax, r, a, rc, hr, ha):
    """The normal of a round-sectioned ring (a torus of radial half size hr, axial ha round radius rc) at radius r,
    axial position a; out: the outward radial direction there, ax: the axis."""
    if c.face in ("north", "south", "east", "west", "up", "down"):
        pass
    gr, ga = (r - rc) / (hr * hr), a / (ha * ha)
    n = tuple(gr * out[i] + ga * ax[i] for i in range(3))
    if abs(gr) + abs(ga) < 1e-6:
        return FACE_N[c.face]
    return _n(n)


def _tyre_texel(c, out, ax, rc, tr, tw, whitewall, o=(0.0, 0.0, 0.0)):
    """A tyre's texel: a block tread on its crown, a moulded ring and (whitewall) a white band on its walls. o: its
    hub."""
    x, y, z = (c.p[i] - o[i] for i in range(3))
    a = x if ax[0] else z
    r = math.hypot(*( (y, z) if ax[0] else (x, y) ))
    n = _ring_normal(c, out, ax, r, a, rc, tr * 1.05, tw * 1.05)
    on_crown = abs(_dot(n, ax)) < 0.6 and r > rc + 0.5
    if on_crown:
        u = (y + z) if ax[0] else (x + y)
        k = math.floor(u * 0.9)
        groove = abs(a) < 0.4 or (abs(a) > 0.6 and (k + (1 if a > 0 else 0)) % 3 == 0)
        return ("sc_rubber", 0.3) if groove else _tone("sc_rubber", "matte", n, 0.6, 5)
    if whitewall and rc - 1.1 < r < rc + 0.5:
        return _tone("sc_white", "satin", n)
    if abs(r - (rc + 1.0)) < 0.35:
        return ("sc_rubber", 0.8)
    return _tone("sc_rubber", "satin", n, 0.4, 5)


# ================================================================================================
# The wheel: drawn at twice its size (24.4 px = 0.76 block across), shrunk by 2
# ================================================================================================

def wheel_sidecar(whitewall=False, name="wheel_sidecar"):
    """The rig's wheel (all three): a fat round-shouldered tyre with a block tread, a chrome drop-centre rim, forty
    crossed wire spokes laced to the flanges of a big finned drum-brake hub with a brake plate each side."""
    m = Flat(name, shift=(8, 8, 8), floor=None, scale=2.0)
    mats = Mats()
    RC, TR, TW = 10.25, 1.95, 2.2         # tyre section: centre radius, radial and lateral half sizes

    def tyre_paint(th, out):
        return Paint("sc_rubber", 1, noise=0, light=False, decal=lambda c: _tyre_texel(c, out, (1, 0, 0), RC, TR, TW, whitewall))
    poly_ring(m, "tyre", "x", (0, 0), 8.6, RC + TR, -TW, TW, tyre_paint)

    def rim_paint(th, out):
        def decal(c):
            r = math.hypot(c.p[1], c.p[2])
            n = _ring_normal(c, out, (1, 0, 0), r, c.p[0], 7.8, 1.0, 1.6)
            return _tone("sc_chrome", "chrome", n)
        return Paint("sc_chrome", 4, noise=0, light=False, decal=decal)
    poly_ring(m, "rim", "x", (0, 0), 7.0, 8.6, -1.3, 1.3, rim_paint, outer=False)

    # the spokes: two see-through planes (the left and right flange's spokes), each spoke 1 px, crossed
    def spokes(side):
        lines = []
        for i in range(9):
            a = (i + (0.5 if side > 0 else 0)) * 2 * math.pi / 9
            tw = 0.75 * side
            h = (3.0 * math.cos(a), 3.0 * math.sin(a))
            r = (7.1 * math.cos(a + tw), 7.1 * math.sin(a + tw))
            lines.append((h, r))

        def decal(c):
            y, z = c.p[1], c.p[2]
            rr = math.hypot(y, z)
            if rr > 7.1 or rr < 2.6:
                return ("raw", (0, 0, 0, 0))
            for h, r in lines:
                if seg_dist((y, z), h, r) < 0.45:
                    lit = (y - 0.4 * z) / 7
                    return ("sc_chrome", 4.4 + 1.6 * lit)
            return ("raw", (0, 0, 0, 0))
        return Paint("sc_chrome", 4, noise=0, light=False, decal=decal)
    for side in (-1, 1):
        x = side * 0.9
        m.box("spokes", (x - 0.25, -7.2, -7.2), (x + 0.25, 7.2, 7.2), spokes(side))

    # the hub: a finned alloy drum, a brake plate each side, the axle nut
    def hub(c):
        x = c.p[0]
        if c.face in ("east", "west"):
            rr = math.hypot(c.p[1], c.p[2])
            if rr < 0.9:
                return ("lit", "sc_chrome", "chrome")
            return "plate" if rr > 1.6 else "hubdark"
        return "fin" if math.floor(x * 2) % 2 == 0 else "hubdark"
    hm = Mats(plate=("sc_alloy", "satin", 0.5, 6), fin=("sc_alloy", "satin", 1, 6.5), hubdark=("sc_alloy", "matte", 0, 3))
    cyl(m, "hub", (0, 0), 6, -2.4, 2.4, hm.paint(hub, normal=radial("x", 0, 0)), axis="x")
    cyl(m, "hub", (0, 0), 7, -3.0, -2.4, hm.paint(hub, normal=radial("x", 0, 0)), axis="x")
    cyl(m, "hub", (0, 0), 7, 2.4, 3.0, hm.paint(hub, normal=radial("x", 0, 0)), axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


# ================================================================================================
# Shapes
# ================================================================================================

# --- the tank: a teardrop over the top tube -----------------------------------------------------
TZ0, TZ1 = -14.5, 2.0


def tank_dims(z, grow=0.0):
    u = (z - TZ0) / (TZ1 - TZ0)
    if u < 0 or u > 1:
        return None
    hw = 6.4 - 2.6 * u
    yt = 33.4 - 4.6 * u ** 0.9
    yb = 24.6 + 1.2 * u
    s = 1.0
    if u < 0.16:
        s = math.sqrt(max(0.0, 1 - ((0.16 - u) / 0.16) ** 2))
    if u > 0.80:
        s = math.sqrt(max(0.0, 1 - ((u - 0.80) / 0.20) ** 2))
    return hw * s + grow, (yt + yb) / 2, (yt - yb) / 2 * (0.55 + 0.45 * s) + grow


def tank_f(x, y, z, grow=0.0):
    d = tank_dims(z, grow)
    if d is None or d[0] <= 0.2:
        return 1.0
    hw, yc, hh = d
    return (abs(x - BX) / hw) ** 2.4 + (abs(y - yc) / hh) ** 2.2 - 1


# --- the fenders: a valanced shell round a wheel --------------------------------------------------

def fender_f(cy, cz, cx, half, r_in, depth, a0, a1):
    """A fender round the wheel at (cy, cz), centred on x = cx: its crown r_in .. r_in + 1.4, its valances down to
    r_in - depth either side; from angle a0 to a1 (degrees from the top, + towards the back)."""
    r_out = r_in + 1.0

    def f(x, y, z):
        a = math.degrees(math.atan2(z - cz, y - cy))
        if a < a0 or a > a1:
            return 1.0
        rr = math.hypot(y - cy, z - cz)
        ax = abs(x - cx)
        rc, hr = (r_out + r_in - depth) / 2, (r_out - (r_in - depth)) / 2
        outer = (ax / half) ** 4 + ((rr - rc) / hr) ** 4 - 1
        inner = 1 - ((ax / (half - 0.9)) ** 4 + ((rr - (rc - 1.0)) / hr) ** 4)
        return max(outer, inner)
    return f


# --- the sidecar's tub ----------------------------------------------------------------------------
TUB_Z0, TUB_Z1 = -31.0, 31.0
WELL_Z0, WELL_Z1 = -6.0, 15.0
WELL_FLOOR = 12.0


def tub_dims(z):
    """(half width, centre height, half height) of the tub's section at z."""
    if z < TUB_Z0 or z > TUB_Z1:
        return None
    hw, yt, yb = 14.0, 28.5, 8.0
    if z > 8:
        t = smooth((z - 8) / 23)
        hw, yt, yb = 14 - 6.0 * t, 28.5 - 6.0 * t, 8 + 4.0 * t
    yc, hh = (yt + yb) / 2, (yt - yb) / 2
    s = 1.0
    if z < -12:
        s = (max(0.0, 1 - ((z + 12) / 19.0) ** 2)) ** 0.5
    if z > 27:
        s = math.sqrt(max(0.0, 1 - ((z - 27) / 4.0) ** 2))
    return hw * s, yc, hh * s


def tub_f(x, y, z):
    d = tub_dims(z)
    if d is None or d[0] < 0.3:
        return 1.0
    hw, yc, hh = d
    return (abs(x - SX) / hw) ** 2.6 + (abs(y - yc) / hh) ** 2.6 - 1


def tub_top(z, x=SX):
    d = tub_dims(z)
    if d is None:
        return 0
    hw, yc, hh = d
    t = min(1.0, abs(x - SX) / max(hw, 0.1))
    return yc + hh * (1 - t ** 2.6) ** (1 / 2.6)


def in_well(x, z, pad=0.0):
    """Inside the cockpit's opening (seen from above), its front corners rounded."""
    if not (WELL_Z0 - pad <= z <= WELL_Z1 + pad):
        return False
    hw = tub_dims(z)[0] - 1.7 + pad
    if z < WELL_Z0 + 4:
        hw -= 4 - math.sqrt(max(0.0, 16 - (WELL_Z0 + 4 - z) ** 2))
    return abs(x - SX) < hw


# ================================================================================================
# The rig
# ================================================================================================

class Kit:
    def __init__(self, name, parts):
        self.body = Flat(name, shift=(8, 8, 8), scale=4.0)
        self.parts = {}
        for part in parts:
            m = Flat(f"{name}_{part}", shift=(8, 8, 8), scale=4.0)
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
    key = (colour, trim, leather, whitewall, name)
    if key in _KITS:
        return _KITS[key]
    kit = Kit(name, ("screen",))
    m = kit.body
    C, T, L = colour, trim, leather
    M = Mats(paint=(C, "gloss", None, None), trim=(T, "gloss", None, None), chrome=("sc_chrome", "chrome", None, None),
             black=("sc_black", "satin", None, None), alloy=("sc_alloy", "satin", None, None),
             iron=("sc_iron", "matte", None, None), rubber=("sc_rubber", "matte", None, None),
             leather=(L, "satin", None, None), dark=(L, "matte", 0, 2.4), pin=("sc_chrome", "satin", 1, 3))
    chrome = M.paint("chrome")
    black = M.paint("black")
    rubber = M.paint("rubber")

    def chrome_r(axis, c0, c1):
        return M.paint("chrome", normal=radial(axis, c0, c1))

    _bike(m, M, chrome, black, rubber, chrome_r)
    _engine(m, M, chrome, black, rubber, chrome_r)
    _rear(m, M, chrome, black, rubber, chrome_r)
    _sidecar(m, kit, M, chrome, black, rubber, chrome_r, whitewall)
    m.display = presets.furniture(gui_scale=0.4)
    _KITS[key] = kit
    return kit


# ------------------------------------------------------------------------------------------------
# The front of the bike: fork, fender, headlamp, bars; the tank, the frame, the saddle
# ------------------------------------------------------------------------------------------------

def _bike(m, M, chrome, black, rubber, chrome_r):
    m.group("front")
    # the springer fork: rigid rear legs from the lower crown down to the rocker, sprung front legs on the rocker's
    # front end up to two coil springs under the upper crown
    for s in (-1, 1):
        xa, xb = sorted((BX + s * 5.3, BX + s * 6.9))
        rod(m, "fork_leg", fork(2.5, -2.4), fork(22.5, -2.4), 1.6, black, x=(xa, xb), depth=2.0)
        rod(m, "fork_spring_leg", fork(1.5, 1.6), fork(16.5, 1.6), 1.6, black, x=(xa, xb), depth=1.4)

        def spring(c):
            t = c.p[1] * 1.1 - c.p[2] * 0.45
            return "chrome" if math.floor(t) % 2 == 0 else ("sc_chrome", 1.2)
        xa2, xb2 = sorted((BX + s * 5.0, BX + s * 7.2))
        rod(m, "fork_spring", fork(16.5, 1.6), fork(24.0, 1.6), 2.2, M.paint(spring), x=(xa2, xb2), depth=2.2)
        # the rocker at the axle: a short link from the rear leg's foot past the axle to the front leg
        xa3, xb3 = sorted((BX + s * 4.6, BX + s * 7.4))
        rod(m, "rocker", fork(0.0, -3.0), fork(0.0, 2.6), 2.0, black, x=(xa3, xb3), depth=2.0)
        m.box("axle_nut", (min(BX + s * 7.4, BX + s * 8.2), WY - 0.8, ZF - 0.8),
              (max(BX + s * 7.4, BX + s * 8.2), WY + 0.8, ZF + 0.8), chrome)
    # the crowns: the lower one carries the rear legs, the upper one the springs and the bars
    rod(m, "crown", fork(22.0, -4.5), fork(22.0, 2.8), 2.0, black, x=(BX - 7.6, BX + 7.6), depth=2.0)
    rod(m, "crown", fork(24.2, -4.5), fork(24.2, 3.4), 1.6, M.paint("chrome"), x=(BX - 7.8, BX + 7.8), depth=1.6)

    # the front fender: deep valances, the trim colour along its crown with a fine pinstripe each side, a chrome tip
    ff = fender_f(WY, ZF, BX, 4.0, 13.2, 1.8, -108, 34)

    def fender(c):
        x, y, z = c.p
        ax = abs(x - BX)
        rr = math.hypot(y - WY, z - ZF)
        if rr > 13.7:
            if ax < 1.1:
                return "trim"
            if 1.5 < ax < 2.0:
                return "pin"
        a = math.degrees(math.atan2(z - ZF, y - WY))
        if a < -100:
            return "chrome"
        return "paint"
    sculpt(m, "front_fender", ff, (BX - 5, 0, ZF - 17), (BX + 5, WY + 16, ZF + 12), M.paint(fender, normal=field_normal(ff)))
    # its stays to the fork's front legs
    for s in (-1, 1):
        xa, xb = sorted((BX + s * 4.5, BX + s * 5.3))
        m.box("fender_stay", (xa, WY + 3, ZF - 8), (xb, WY + 3.8, ZF + 1), chrome)

    # the headlamp: a chrome bucket on two brackets from the fork, its bezel ring standing proud of the glass
    hy, hz = HL

    def bucket_f(x, y, z):
        if z < hz - 4.3 or z > hz + 4.5:
            return 1.0
        u = (z - (hz - 4.3)) / 8.8
        r = 5.1 - 1.6 * u ** 1.6
        return math.hypot(x - BX, y - hy) - r
    sculpt(m, "headlamp", bucket_f, (BX - 6, hy - 6, hz - 5), (BX + 6, hy + 6, hz + 5),
           M.paint("chrome", normal=field_normal(bucket_f)))

    def bezel_f(x, y, z):
        if not (hz - 5.3 <= z <= hz - 4.3):
            return 1.0
        return abs(math.hypot(x - BX, y - hy) - 4.85) - 0.75
    sculpt(m, "bezel", bezel_f, (BX - 6, hy - 6, hz - 6), (BX + 6, hy + 6, hz - 4), M.paint("chrome", normal=field_normal(
        lambda x, y, z: -((math.hypot(x - BX, y - hy) - 4.85) ** 2) - (z - (hz - 6)) * 0.6)))

    def lens(c):
        x, y = c.p[0] - BX, c.p[1] - hy
        r = math.hypot(x, y)
        if r > 4.15:
            return ("raw", (0, 0, 0, 0))
        if (x + 1.4) ** 2 + (y - 1.6) ** 2 < 1.3:
            return ("sc_white", 3.4)                   # the glass's glint
        if r < 0.9:
            return ("sc_lamp", 3.6)                    # the bulb
        if r < 1.6:
            return ("sc_chrome", 2.0)                  # its dark socket
        ring = math.floor(r * 1.6) % 2
        return ("sc_lamp", (1.6 if ring else 2.4) + (y > 0) * 0.6)     # the reflector's rings, seen through fluting
    m.box("headlamp_lens", (BX - 4.25, hy - 4.25, hz - 4.6), (BX + 4.25, hy + 4.25, hz - 4.3),
          Paint("sc_lamp", 2, noise=0, light=False, decal=lens))
    for s in (-1, 1):
        xa, xb = sorted((BX + s * 4.4, BX + s * 6.6))
        m.box("lamp_bracket", (xa, hy - 0.8, hz + 0.5), (xb, hy + 0.8, fork(22, 0)[1] - 2.5), black)
    # the speedometer in a chrome cup on top of the bucket: a cream dial, a needle, a lit rim
    def speedo(c):
        x, z = c.p[0] - BX, c.p[2] - (hz + 1.2)
        if c.face == "up":
            r = math.hypot(x, z)
            if r > 1.75:
                return "chrome"
            if abs(x * 0.8 + z * 0.6) < 0.35 and x < 0.4:
                return ("sc_red", 2.5)
            return ("sc_white", 2.4 if r < 1.2 else 1.2)
        return "chrome"
    cyl(m, "speedo", (BX, hz + 1.2), 4.4, hy + 4.2, hy + 6.0, M.paint(speedo, normal=radial("y", BX, hz + 1.2)))
    # a chrome horn under the lamp, a trumpet facing forward
    tube(m, "horn", "z", (BX + 2.6, hy - 6.6), 2.6, hz - 2.6, hz + 2.4, chrome_r("z", BX + 2.6, hy - 6.6))
    tube(m, "horn", "z", (BX + 2.6, hy - 6.6), 3.6, hz - 3.4, hz - 2.6, chrome_r("z", BX + 2.6, hy - 6.6))

    # the handlebars: a riser on the upper crown, a wide bar pulled back to the grips, levers, a mirror
    cy, cz = fork(25.5, -0.5)
    m.box("riser", (BX - 1.0, cy - 1.5, cz - 1.0), (BX + 1.0, cy + 1.0, cz + 1.0), chrome)
    by = cy + 1.0
    m.box("bar", (BX - 6.0, by, cz - 0.6), (BX + 6.0, by + 1.2, cz + 0.6), chrome_r("x", by + 0.6, cz))
    for s in (-1, 1):
        # out and back at 22.5 degrees, then the grip along x
        x0 = BX + s * 6.0
        lo, hi = sorted((x0, x0 + s * 5.0))
        m.box("bar", (lo, by, cz - 0.6), (hi, by + 1.2, cz + 0.6), chrome,
              rotation=("y", -s * 22.5, (x0, by, cz)))
        gx = x0 + s * 5.0 * math.cos(math.radians(22.5)) - s * 0.2
        gz = cz + 5.0 * math.sin(math.radians(22.5))
        lo, hi = sorted((gx, gx + s * 3.0))
        m.box("bar", (lo, by, gz - 0.6), (hi, by + 1.2, gz + 0.6), chrome)

        def grip(c):
            return "rubber" if math.floor(c.p[0] * 2) % 2 else ("sc_rubber", 0.4)
        lo, hi = sorted((gx + s * 3.0, gx + s * 7.6))
        m.box("grip", (lo, by - 0.3, gz - 0.9), (hi, by + 1.5, gz + 0.9), M.paint(grip, normal=radial("x", by + 0.6, gz)))
        lo, hi = sorted((gx + s * 2.2, gx + s * 3.0))
        m.box("grip_end", (lo, by - 0.5, gz - 1.1), (hi, by + 1.7, gz + 1.1), chrome)
        # the lever: a perch at the bar, the blade reaching forward of the grip
        lo, hi = sorted((gx + s * 0.6, gx + s * 1.8))
        m.box("lever_perch", (lo, by - 0.4, gz - 2.0), (hi, by + 1.0, gz - 0.6), chrome)
        lo, hi = sorted((gx + s * 1.6, gx + s * 7.0))
        m.box("lever", (lo, by + 0.1, gz - 2.6), (hi, by + 0.7, gz - 1.9), chrome)
    # one round mirror on a stalk from the left perch
    mx, mz = BX - 6.0 - 5.0 * math.cos(math.radians(22.5)) - 1.0, cz + 5.0 * math.sin(math.radians(22.5)) - 1.2
    m.box("mirror_stalk", (mx - 0.4, by + 1.2, mz - 0.4), (mx + 0.4, by + 8.0, mz + 0.4), chrome)

    def mirror(c):
        if c.face == "south":
            r = math.hypot(c.p[0] - mx, c.p[1] - (by + 9.6))
            if r < 1.5:
                return ("sc_glass", 2.6 if c.p[0] < mx else 1.6)
        return "chrome"
    cyl(m, "mirror", (mx, by + 9.6), 4.0, mz - 0.8, mz + 0.4, M.paint(mirror, normal=radial("z", mx, by + 9.6)), axis="z")

    m.group("tank")

    def tank(c):
        x, y, z = c.p
        ax = abs(x - BX)
        d = tank_dims(z)
        if d is None:
            return "paint"
        hw, yc, hh = d
        n = _grad(tank_f, c.p) or (0, 1, 0)
        if n[1] > 0.6 and ax < 0.8:
            return "chrome"                                     # the strip down the crown
        # the flash: a sweep of the trim colour on each side, a pinstripe round it
        u = (z - TZ0) / (TZ1 - TZ0)
        top = yc + hh * 0.55 - u * 1.2
        bot = yc - hh * 0.35 + u * 1.6
        if ax > hw * 0.55 and u < 0.62:
            if bot < y < top and u > 0.06:
                return "trim"
            if bot - 0.7 < y < top + 0.7 and u > 0.03:
                return "pin"
        return "paint"
    sculpt(m, "tank", tank_f, (BX - 7, 23, TZ0 - 1), (BX + 7, 35, TZ1 + 1), M.paint(tank, normal=field_normal(tank_f)))

    # rubber knee pads standing 1 px proud of the tank's rear sides, a chrome strip round each
    def pad_f(x, y, z):
        d = tank_dims(z)
        if d is None:
            return 1.0
        u = (z - TZ0) / (TZ1 - TZ0)
        if not (0.60 < u < 0.92) or abs(x - BX) < d[0] * 0.6:
            return 1.0
        if abs(y - (d[1] - 0.3)) > 2.2:
            return 1.0
        return tank_f(x, y, z, 1.0)

    def pad(c):
        x, y, z = c.p
        d = tank_dims(z)
        u = (z - TZ0) / (TZ1 - TZ0)
        if d is None or u < 0.625 or u > 0.9 or abs(y - (d[1] - 0.3)) > 1.7:
            return "chrome"
        return "rubber" if math.floor(z * 1.5) % 2 else ("sc_rubber", 0.5)
    sculpt(m, "knee_pad", pad_f, (BX - 8, 24, -5), (BX + 8, 33, TZ1), M.paint(pad, normal=field_normal(lambda x, y, z: tank_f(x, y, z, 1.0))),
           label=lambda p: None if tank_f(*p) < 0 else "s")
    # two chrome filler caps
    for s in (-1, 1):
        cx = BX + s * 2.3
        zc = TZ0 + 4.5
        ytop = tank_dims(zc)[1] + tank_dims(zc)[2] - 0.7
        cyl(m, "filler_cap", (cx, zc), 2.6, ytop, ytop + 1.1, chrome_r("y", cx, zc))
    # the hand-shift lever up the tank's left side, a black knob on it
    rod(m, "shift_lever", (19.0, -2.0), (31.5, -6.5), 0.9, chrome, x=(BX - 7.8, BX - 6.9))
    m.box("shift_knob", (BX - 8.5, 31.0, -8.3), (BX - 6.2, 33.4, -6.0), M.paint(("lit", "sc_black", "gloss")))
    m.box("shift_gate", (BX - 7.6, 24.0, -4.0), (BX - 5.8, 25.6, 1.0), chrome)

    m.group("frame")
    fb = M.paint("black")
    # the steering neck behind the fork, the top tube under the tank, the twin down tubes, the cradle, the seat post
    ny0, nz0 = fork(18.5, -5.6)
    rod(m, "neck", (ny0, nz0), fork(25.5, -5.6), 3.0, fb, x=(BX - 1.5, BX + 1.5), depth=3.0)
    m.box("top_tube", (BX - 1.0, 26.6, nz0 - 0.5), (BX + 1.0, 28.6, 12.5), fb)
    for s in (-1, 1):
        xa, xb = sorted((BX + s * 2.6, BX + s * 4.4))
        rod(m, "down_tube", (ny0 + 1.0, nz0 - 1.0), (5.0, -6.0), 1.8, fb, x=(xa, xb))
        m.box("cradle", (xa, 3.2, -7.0), (xb, 5.0, 12.0), fb)
        m.box("chain_stay", (xa, WY - 5.0, 12.0), (xb, WY - 3.2, ZR - 1.5), fb)
        # the seat stays: from under the saddle to the plunger's top
        rod(m, "seat_stay", (27.6, 11.5), (WY + 7.5, ZR - 1.2), 1.8, fb, x=(xa, xb))
    m.box("seat_post", (BX - 1.0, 4.0, 10.6), (BX + 1.0, 27.0, 12.6), fb)
    m.box("cross_tube", (BX - 4.4, 25.0, 10.0), (BX + 4.4, 26.6, 11.6), fb)

    m.group("saddle")
    # the sprung solo saddle: a leather pan wide at the back, a narrow nose on a pivot, a rolled edge, two chrome
    # coil springs under its back

    def saddle_f(x, y, z):
        if not (-0.5 <= z <= 14.0):
            return 1.0
        u = (z + 0.5) / 14.5
        hw = 2.6 + 4.9 * smooth(u * 1.4) - 0.8 * smooth((u - 0.85) / 0.15)
        top = SADDLE - 0.3 * (1 - u) + 0.9 * smooth((u - 0.8) / 0.2)       # the cantle rises a little
        if u < 0.1:
            top -= (0.1 - u) * 10
        bot = SADDLE - 2.6
        yc, hh = (top + bot) / 2, (top - bot) / 2
        ax = abs(x - BX)
        return (ax / hw) ** 3 + (abs(y - yc) / hh) ** 2.4 - 1

    def saddle(c):
        x, y, z = c.p
        n = _grad(saddle_f, c.p) or (0, 1, 0)
        if n[1] < 0.35 and y > SADDLE - 1.6:
            return "dark"                                    # the rolled, stitched edge
        if n[1] < -0.2:
            return ("lit", "sc_black", "matte")              # the pan under it
        if c.face == "up" and abs(x - BX) < 1.6 and 3 < z < 11 and math.floor(z) % 3 == 0:
            return ("lit", M.m["leather"][0], "matte")
        return "leather"
    sculpt(m, "saddle", saddle_f, (BX - 9, SADDLE - 3, -1), (BX + 9, SADDLE + 2, 15),
           M.paint(saddle, normal=field_normal(saddle_f)))
    m.box("saddle_pivot", (BX - 1.2, 27.4, -0.4), (BX + 1.2, SADDLE - 2.6, 1.6), chrome)
    for s in (-1, 1):
        def spring(c):
            return "chrome" if math.floor(c.p[1] * 1.4) % 2 == 0 else ("sc_chrome", 1.0)
        cx = BX + s * 3.6
        cyl(m, "saddle_spring", (cx, 10.8), 2.6, 25.4, SADDLE - 2.6, M.paint(spring, normal=radial("y", cx, 10.8)))
    m.box("spring_bar", (BX - 5.2, 24.6, 9.6), (BX + 5.2, 25.4, 12.0), black)



# ------------------------------------------------------------------------------------------------
# The engine: a 45 degree V-twin, the exhausts, the footboards
# ------------------------------------------------------------------------------------------------

V_BASE = {"front": (15.0, -5.6), "rear": (15.0, 0.8)}        # each cylinder's foot (y, z)


def _engine(m, M, chrome, black, rubber, chrome_r):
    m.group("engine")
    # the crankcase: a rounded alloy case, the gearbox behind it
    def case_f(x, y, z):
        ax = abs(x - BX)
        return (ax / 4.4) ** 4 + abs((y - 10.5) / 5.6) ** 2.4 + abs((z + 2.0) / 8.0) ** 2.4 - 1

    def case(c):
        x, y, z = c.p
        if c.face == "east" or (_grad(case_f, c.p) or (0, 0, 0))[0] > 0.8:
            r = math.hypot(y - 11.0, z + 3.5)
            if r < 3.4:
                return "chrome" if r > 0.7 else ("sc_chrome", 3)          # the round timing cover
        if math.floor(z) % 5 == 0 and y < 8:
            return ("lit", "sc_alloy", "matte")                            # ribs under the case
        return "alloy"
    sculpt(m, "crankcase", case_f, (BX - 5, 4, -11), (BX + 5, 17, 7), M.paint(case, normal=field_normal(case_f)))

    def gear_f(x, y, z):
        return (abs(x - BX) / 3.8) ** 4 + abs((y - 11.0) / 4.6) ** 3 + abs((z - 9.0) / 4.0) ** 3 - 1
    sculpt(m, "gearbox", gear_f, (BX - 4, 6, 4), (BX + 4, 16, 14), M.paint("alloy", normal=field_normal(gear_f)))

    # the primary cover on the left: a long chrome teardrop from the crank to the gearbox
    def prim_f(x, y, z):
        zc = max(-1.0, min(10.0, z))
        t = (zc + 1.0) / 11.0
        r = 4.0 - 1.2 * t
        d = math.hypot(z - zc, y - (10.5 + 0.5 * t))
        return (d / r) ** 2 + ((x - (BX - 5.3)) / 1.3) ** 4 - 1
    sculpt(m, "primary", prim_f, (BX - 7, 5, -5), (BX - 4, 16, 13), M.paint("chrome", normal=field_normal(prim_f)))

    # the cylinders: a finned iron barrel and a finned alloy head each, leaning 22.5 degrees fore and aft
    for cyl_name, sgn in (("front", -1), ("rear", 1)):
        y0, z0 = V_BASE[cyl_name]
        ang = 22.5 * sgn                            # + moves +y towards +z: the rear one leans back
        rot = ("x", ang, (BX, y0, z0))
        bar = M.paint("iron", normal=radial("y", BX, z0))
        # the barrel's core and its fins (iron), then the head's bigger fins (alloy)
        for (a, b, hw) in bands(5.0):
            m.box("barrel", (BX - hw, y0, z0 + a), (BX + hw, y0 + 10.6, z0 + b), bar, rotation=rot)
        for k in range(5):
            fy = y0 + 1.0 + k * 1.6
            fp = M.paint(("lit", "sc_iron", "satin"), normal=radial("y", BX, z0))
            for (a, b, hw) in bands(8.6):
                m.box("fin", (BX - hw, fy, z0 + a), (BX + hw, fy + 0.8, z0 + b), fp, rotation=rot)
        for k in range(3):
            fy = y0 + 8.6 + k * 1.5
            fp = M.paint(("lit", "sc_alloy", "satin"), normal=radial("y", BX, z0))
            for (a, b, hw) in bands(9.6):
                m.box("head_fin", (BX - hw, fy, z0 + a), (BX + hw, fy + 0.8, z0 + b), fp, rotation=rot)
        # the rocker cover: a chrome cap on top, a spark plug with its lead on the outside
        for (a, b, hw) in bands(6.0):
            m.box("rocker_cover", (BX - hw, y0 + 12.9, z0 + a), (BX + hw, y0 + 14.1, z0 + b),
                  chrome_r("y", BX, z0), rotation=rot)
        m.box("plug", (BX - 4.6, y0 + 10.2, z0 - 0.6), (BX - 2.8, y0 + 11.4, z0 + 0.6),
              M.paint(("lit", "sc_white", "satin")), rotation=rot)
        m.box("plug_lead", (BX - 5.4, y0 + 4.0, z0 - 0.4), (BX - 4.6, y0 + 11.0, z0 + 0.4),
              M.paint(("lit", "sc_black", "gloss")), rotation=rot)

    # the carburettor in the vee on the right, its round chrome air cleaner
    m.box("carb", (BX + 1.5, 17.0, -3.4), (BX + 4.6, 20.4, -0.6), M.paint("alloy"))
    def cleaner(c):
        if c.face == "east":
            r = math.hypot(c.p[1] - 18.8, c.p[2] + 2.0)
            if r < 1.2:
                return ("sc_chrome", 2.0)                 # its centre bolt
            if 2.4 < r < 2.9:
                return ("sc_chrome", 3.0)                 # a pressed ring
        return "chrome"
    cyl(m, "air_cleaner", (18.8, -2.0), 7.6, BX + 4.6, BX + 6.6, M.paint(cleaner, normal=radial("x", 18.8, -2.0)), axis="x")

    # the exhausts: from each cylinder's port down the left side, two pipes to stacked fishtail mufflers
    ex = BX - 6.6
    for i, (port, low) in enumerate(((V_BASE["front"], 6.2), (V_BASE["rear"], 10.4))):
        y0, z0 = port
        if i == 0:
            # front port: forward out of the head, then down in front of the crankcase
            py, pz = y0 + 7.0, z0 - 4.6
            rod(m, "header", (py, pz), (py - 2.0, pz - 3.2), 2.2, chrome, x=(BX - 3.6, BX - 1.4))
            rod(m, "header", (py - 1.4, pz - 3.4), (low + 1.0, pz - 6.4), 2.2, chrome, x=(BX - 3.6, BX - 1.4))
            m.box("header", (ex - 1.1, low - 1.1, pz - 7.4), (BX - 1.4, low + 1.1, pz - 5.2), chrome_r("x", low, pz - 6.3))
            z_start = pz - 7.4
        else:
            # rear port: back out of the head, out to the left and down beside the gearbox
            py, pz = y0 + 7.0, z0 + 3.6
            m.box("header", (ex - 1.1, py - 1.1, pz - 1.1), (BX - 1.6, py + 1.1, pz + 1.1), chrome_r("x", py, pz))
            rod(m, "header", (py, pz), (low, pz + 6.0), 2.2, chrome, x=(ex - 1.1, ex + 1.1))
            z_start = pz + 4.0
        tube(m, "pipe", "z", (ex, low), 2.2, z_start, 17.0, chrome_r("z", ex, low))
        # the muffler: a long round can, then the fishtail flaring out and a dark slot at its end
        tube(m, "muffler", "z", (ex, low), 3.6, 17.0, 31.0, chrome_r("z", ex, low))
        tube(m, "muffler", "z", (ex, low), 2.8, 16.0, 17.0, chrome_r("z", ex, low))

        def fish_f(x, y, z, low=low):
            if not (30.5 <= z <= 37.5):
                return 1.0
            u = (z - 30.5) / 7.0
            hh = 1.8 + 1.4 * u ** 1.3
            hw = 1.8 - 0.6 * u
            return ((x - ex) / hw) ** 2 + ((y - low) / hh) ** 2 - 1

        def fish(c, low=low):
            if c.face == "south" and abs(c.p[1] - low) < 1.6 and abs(c.p[0] - ex) < 0.8:
                return ("sc_rubber", 0)
            return "chrome"
        sculpt(m, "fishtail", fish_f, (ex - 3, low - 4, 30), (ex + 3, low + 4, 38), M.paint(fish, normal=field_normal(fish_f)))
    m.box("muffler_clamp", (ex - 1.0, 6.2, 24.0), (ex + 1.0, 10.4, 25.2), black)
    m.box("muffler_hanger", (ex + 1.0, 8.0, 24.0), (BX - 4.6, 9.2, 25.2), black)

    # the footboards: rubber mats on a chrome rim
    for s, (x0, x1) in ((-1, (BX - 13.0, BX - 6.4)), (1, (BX + 5.4, BX + 10.6))):
        m.box("footboard", (x0, 7.6, -12.0), (x1, 8.2, -2.0), chrome)

        def mat(c):
            return ("sc_rubber", 1.4) if math.floor(c.p[2] * 1.3) % 2 else ("sc_rubber", 0.5)
        m.box("footboard_mat", (x0 + 0.5, 8.2, -11.5), (x1 - 0.5, 8.8, -2.5), M.paint(lambda c: mat(c) if c.face == "up" else "rubber"))
        lo, hi = sorted((BX + s * 4.4, x1 if s < 0 else x0))
        m.box("footboard_arm", (lo, 6.0, -7.6), (hi, 7.6, -6.4), black)
    # the kick starter on the right of the gearbox
    rod(m, "kick", (14.5, 10.0), (11.0, 3.0), 1.0, chrome, x=(BX + 4.0, BX + 5.0))
    m.box("kick_pedal", (BX + 5.0, 10.4, 2.0), (BX + 7.4, 11.4, 4.0), rubber)


# ------------------------------------------------------------------------------------------------
# The back of the bike: plungers, fender, tail lamp, plate, saddlebag, chain guard, oil tank
# ------------------------------------------------------------------------------------------------

TAIL_LAMP = (22.2, 39.5)          # (y, z) of the tail lamp's middle; its lens' face at z TAIL_LENS_Z
TAIL_LENS_Z = 41.6
PLATE = (14.2, 43.2)              # the plate's middle (y) and its face (z)


def _rear(m, M, chrome, black, rubber, chrome_r):
    m.group("rear")
    # plunger suspension: a chrome cover over each spring at the axle
    for s in (-1, 1):
        cx = BX + s * 3.5
        cyl(m, "plunger", (cx, ZR), 2.2, WY - 5.0, WY + 7.5, chrome_r("y", cx, ZR))
        cyl(m, "plunger_cover", (cx, ZR), 2.8, WY + 1.5, WY + 6.5, chrome_r("y", cx, ZR))
    # the rear fender
    rf = fender_f(WY, ZR, BX, 4.4, 13.2, 2.0, -34, 108)

    def fender(c):
        x, y, z = c.p
        ax = abs(x - BX)
        rr = math.hypot(y - WY, z - ZR)
        if rr > 13.7:
            if ax < 1.1:
                return "trim"
            if 1.5 < ax < 2.0:
                return "pin"
        return "paint"
    sculpt(m, "rear_fender", rf, (BX - 6, 0, ZR - 13), (BX + 6, WY + 17, ZR + 18), M.paint(fender, normal=field_normal(rf)))

    # the tail lamp: a round chrome housing on the fender's back, a red lens behind a chrome ring
    ty, tz = TAIL_LAMP
    cyl(m, "tail_lamp", (BX, ty), 4.6, tz - 2.2, TAIL_LENS_Z - 0.6, chrome_r("z", BX, ty), axis="z")

    def tail_lens(c):
        r = math.hypot(c.p[0] - BX, c.p[1] - ty)
        if r > 1.9:
            return ("raw", (0, 0, 0, 0))
        return ("sc_red", 3.4 if r < 0.8 else 2.2)
    m.box("tail_lens", (BX - 2.0, ty - 2.0, TAIL_LENS_Z - 0.4), (BX + 2.0, ty + 2.0, TAIL_LENS_Z),
          Paint("sc_red", 2, noise=0, light=False, decal=tail_lens))
    m.box("tail_stem", (BX - 0.8, ty - 4.0, tz - 1.6), (BX + 0.8, ty - 2.2, tz + 0.2), chrome)
    # the plate holder: a chrome frame on a bracket below the lamp, the plate blank
    py, pz = PLATE

    def plate(c):
        if c.face == "south":
            if abs(c.p[0] - BX) > 5.4 or abs(c.p[1] - py) > 2.8:
                return "chrome"
            return ("sc_white", 2.4 if c.p[1] > py - 2 else 1.8)
        return "chrome"
    m.box("plate", (BX - 6.2, py - 3.6, pz - 0.6), (BX + 6.2, py + 3.6, pz), M.paint(plate))
    m.box("plate_bracket", (BX - 1.0, py - 1.0, 38.0), (BX + 1.0, py + 1.0, pz - 0.6), black)

    # the chain guard on the right, the oil tank under the saddle on the right
    m.box("chain_guard", (BX + 2.2, WY - 1.2, 10.0), (BX + 3.2, WY + 2.6, ZR - 2.0), M.paint("paint"))
    tube(m, "oil_tank", "z", (BX + 5.0, 20.5), 5.2, 2.0, 10.0, M.paint("paint", normal=radial("z", BX + 5.0, 20.5)))
    cyl(m, "oil_cap", (BX + 5.0, 6.0), 1.8, 23.0, 23.8, chrome)

    # the leather saddlebag on the left: a box with a rounded flap, two buckled straps
    def bag_f(x, y, z):
        if not (BX - 12.2 <= x <= BX - 6.2):
            return 1.0
        return ((z - 28.0) / 6.0) ** 4 + ((y - 16.0) / 5.0) ** 4 - 1

    def bag(c):
        x, y, z = c.p
        if abs(z - 25.6) < 0.6 or abs(z - 30.4) < 0.6:
            if c.face == "west" and abs(y - 17.4) < 0.7:
                return ("lit", "sc_chrome", "chrome")     # the buckles
            return "dark"                                 # the straps
        if c.face == "west" and abs(y - 18.0) < 0.5:
            return "dark"                                 # the flap's edge
        return "leather"
    sculpt(m, "saddlebag", bag_f, (BX - 13, 10, 21), (BX - 6, 22, 35), M.paint(bag, normal=field_normal(bag_f)))
    m.box("bag_rail", (BX - 6.4, 19.0, 21.4), (BX - 5.0, 20.2, 34.6), black)


# ------------------------------------------------------------------------------------------------
# The sidecar
# ------------------------------------------------------------------------------------------------

SCREEN_HW = 9.0
SPARE = (21.0, 34.8)              # the spare wheel's middle (y) and its z
SIDE_LAMP = (27.5, -2.8)          # the running lamp on the sidecar's mudguard (y, z of its middle)
TUB_LAMP = (23.0, 23.6)           # the sidecar's red tail lamp on the back of its mudguard (y, z of the lens' face)


def _tub_label(p):
    x, y, z = p
    if in_well(x, z) and y > WELL_FLOOR:
        return None
    return "s"


def _sidecar(m, kit, M, chrome, black, rubber, chrome_r, whitewall):
    m.group("sidecar")

    def tub(c):
        x, y, z = c.p
        d = tub_dims(z)
        if d is None:
            return "paint"
        hw, yc, hh = d
        n = _grad(tub_f, c.p) or FACE_N[c.face]
        # inside the cockpit: its leather lining and floor
        if in_well(x, z, 0.35) and y > WELL_FLOOR - 0.3 and n[1] < 0.9:
            return "dark"
        if in_well(x, z, 0.35) and y <= WELL_FLOOR + 0.3:
            return "dark"
        top = tub_top(z, x)
        # the chrome roll round the cockpit's edge
        if in_well(x, z, 1.7) and y > top - 1.6:
            return "chrome"
        if z < -23.5:
            return "chrome"                                # the polished nose cone
        if -23.5 <= z < -22.8:
            return ("lit", "sc_black", "satin")           # its seam
        ys = yc + 2.2
        if abs(y - ys) < 0.55 and abs(n[1]) < 0.75:
            return "chrome"                                # the spear moulding
        if y > ys:
            return "trim"
        return "paint"
    sculpt(m, "tub", tub_f, (SX - 15, 7, TUB_Z0), (SX + 15, 30, TUB_Z1), M.paint(tub, normal=field_normal(tub_f)), label=_tub_label,
           zstep=lambda z: 2 if (z < -14 or z > 15) else 1)

    # the seat: a tufted cushion and a tall tufted back rest, piped edges
    def tuft(c):
        x, y, z = c.p
        u = (x - SX) % 4.0
        v = (y if c.face in ("north", "south") else z) % 4.0
        if abs(u - 2) < 0.5 and abs(v - 2) < 0.5:
            return "dark"                                  # a button
        if u < 0.5:
            return ("lit", M.m["leather"][0], "matte")
        return "leather"
    m.box("seat_cushion", (SX - 10.5, WELL_FLOOR, 1.0), (SX + 10.5, 15.4, 14.0), M.paint(tuft))
    m.box("seat_back", (SX - 10.5, 15.4, 11.4), (SX + 10.5, 29.6, 14.6), M.paint(tuft))
    m.box("seat_back_roll", (SX - 10.0, 29.6, 11.8), (SX + 10.0, 30.6, 14.4), M.paint("dark"))
    # a floor mat under the passenger's feet
    m.box("floor_mat", (SX - 9.5, WELL_FLOOR, -5.0), (SX + 9.5, WELL_FLOOR + 0.5, 1.0), rubber)

    # the grab rail along the cockpit's outer edge
    gx = SX + 11.6
    for z in (-2.0, 9.0):
        m.box("grab_post", (gx - 0.5, tub_top(z, gx) - 0.6, z - 0.5), (gx + 0.5, 31.2, z + 0.5), chrome)
    m.box("grab_rail", (gx - 0.6, 31.2, -2.6), (gx + 0.6, 32.2, 9.6), chrome_r("z", gx, 31.7))

    # the windscreen (its own part: it folds forward on its foot while the passenger gets in or out): a pane in a
    # chrome frame, leaning back 22.5 degrees, two brackets
    s = kit["screen"]
    rot = ("x", 22.5, (SX, SCREEN_Y, SCREEN_Z))
    H = 8.6

    def pane(c):
        x, y = c.p[0] - SX, c.p[1] - SCREEN_Y
        if abs(x) > SCREEN_HW - 0.7 or y > H - 0.7:
            return ("lit", "sc_chrome", "chrome")
        k = math.floor(x * 0.7 + y * 1.2) % 13
        if k in (0, 1):
            return ("sc_glass", 2.6)
        if k == 4:
            return ("sc_glass", 1.4)
        return ("raw", (0, 0, 0, 0))
    s.box("screen_glass", (SX - SCREEN_HW, SCREEN_Y + 0.6, SCREEN_Z - 0.2), (SX + SCREEN_HW, SCREEN_Y + H, SCREEN_Z + 0.2),
          M.paint(pane), rotation=rot)
    s.box("screen_frame", (SX - SCREEN_HW - 0.4, SCREEN_Y + H - 0.2, SCREEN_Z - 0.5), (SX + SCREEN_HW + 0.4, SCREEN_Y + H + 0.7, SCREEN_Z + 0.5),
          chrome, rotation=rot)
    for sx in (-1, 1):
        lo, hi = sorted((SX + sx * (SCREEN_HW - 0.2), SX + sx * (SCREEN_HW + 0.6)))
        s.box("screen_frame", (lo, SCREEN_Y + 0.2, SCREEN_Z - 0.5), (hi, SCREEN_Y + H - 0.2, SCREEN_Z + 0.5), chrome, rotation=rot)
        lo, hi = sorted((SX + sx * (SCREEN_HW - 1.6), SX + sx * (SCREEN_HW - 0.2)))
        s.box("screen_foot", (lo, SCREEN_Y - 0.6, SCREEN_Z - 1.4), (hi, SCREEN_Y + 1.6, SCREEN_Z + 1.4), black)
    s.box("screen_frame", (SX - SCREEN_HW, SCREEN_Y - 0.1, SCREEN_Z - 0.5), (SX + SCREEN_HW, SCREEN_Y + 0.6, SCREEN_Z + 0.5), chrome, rotation=rot)

    # the luggage rack on the tail deck: two chrome rails on legs, cross bars, a leather case strapped on it
    ry = 28.6
    for sx in (-1, 1):
        x = SX + sx * 6.0
        m.box("rack_rail", (x - 0.5, ry, 16.0), (x + 0.5, ry + 1.0, 30.0), chrome_r("z", x, ry + 0.5))
        for z in (17.0, 28.6):
            m.box("rack_leg", (x - 0.4, tub_top(z, x) - 0.8, z - 0.4), (x + 0.4, ry, z + 0.4), chrome)
    for z in (16.0, 20.4, 24.8, 29.0):
        m.box("rack_bar", (SX - 6.5, ry + 0.1, z), (SX + 6.5, ry + 0.9, z + 1.0), chrome_r("x", ry + 0.5, z + 0.5))

    def case(c):
        x, y, z = c.p
        if abs(x - (SX - 3.0)) < 0.6 or abs(x - (SX + 3.0)) < 0.6:
            if c.face == "up" or c.face == "south":
                return "dark"                               # the straps
        if c.face in ("north", "south") and abs(y - 33.4) < 0.4:
            return "dark"                                   # the lid's seam
        if c.face == "up" and abs(x - SX) < 1.0 and abs(z - 23.0) < 0.6:
            return ("lit", "sc_chrome", "chrome")            # the handle's clasp
        return "leather"
    m.box("case", (SX - 7.5, ry + 1.0, 18.0), (SX + 7.5, ry + 6.4, 28.0), M.paint(case))
    m.box("case_handle", (SX - 1.6, ry + 6.4, 22.6), (SX + 1.6, ry + 7.2, 23.4), M.paint("dark"))

    # the spare wheel standing on the tail, facing back: the same tyre, a rim, spokes, a hub, on a bracket
    sy, sz = SPARE

    def spare_paint(th, out):
        return Paint("sc_rubber", 1, noise=0, light=False, decal=lambda c: _tyre_texel(c, out, (0, 0, 1), 10.4, 1.8, 2.2, whitewall, (SX, sy, sz)))
    poly_ring(m, "spare_tyre", "z", (SX, sy), 8.6, 12.2, sz - 2.2, sz + 2.2, spare_paint)

    def srim_paint(th, out):
        def decal(c):
            r = math.hypot(c.p[0] - SX, c.p[1] - sy)
            return _tone("sc_chrome", "chrome", _ring_normal(c, out, (0, 0, 1), r, c.p[2] - sz, 7.8, 1.0, 1.6))
        return Paint("sc_chrome", 4, noise=0, light=False, decal=decal)
    poly_ring(m, "spare_rim", "z", (SX, sy), 7.0, 8.6, sz - 1.3, sz + 1.3, srim_paint, outer=False)

    def sspokes(c):
        x, y = c.p[0] - SX, c.p[1] - sy
        rr = math.hypot(x, y)
        if rr > 7.1 or rr < 2.6:
            return ("raw", (0, 0, 0, 0))
        for i in range(18):
            b = i * 2 * math.pi / 18
            tw = 0.75 if i % 2 else -0.75
            if seg_dist((x, y), (3.0 * math.cos(b), 3.0 * math.sin(b)), (7.1 * math.cos(b + tw), 7.1 * math.sin(b + tw))) < 0.5:
                return ("sc_chrome", 4.4 + 1.6 * (y - 0.4 * x) / 7)
        return ("raw", (0, 0, 0, 0))
    m.box("spare_spokes", (SX - 7.2, sy - 7.2, sz - 0.25), (SX + 7.2, sy + 7.2, sz + 0.25),
          Paint("sc_chrome", 4, noise=0, light=False, decal=sspokes))
    cyl(m, "spare_hub", (SX, sy), 6.0, sz - 2.2, sz + 2.2, M.paint("alloy", normal=radial("z", SX, sy)), axis="z")
    cyl(m, "spare_cap", (SX, sy), 3.0, sz + 2.2, sz + 2.8, chrome_r("z", SX, sy), axis="z")
    m.box("spare_bracket", (SX - 1.0, sy - 1.0, 27.0), (SX + 1.0, sy + 1.0, sz - 2.2), black)
    m.box("spare_bracket", (SX - 1.0, 12.0, 30.0), (SX + 1.0, sy - 2.0, 31.8), black)

    # the sidecar wheel's mudguard, its stays, a running lamp on top
    gf = fender_f(WY, SWZ, SWX, 3.4, 13.2, 1.6, -70, 82)

    def guard(c):
        x, y, z = c.p
        if math.hypot(y - WY, z - SWZ) > 13.7 and abs(x - SWX) < 0.9:
            return "trim"
        return "paint"
    sculpt(m, "sidecar_fender", gf, (SWX - 4, 0, SWZ - 16), (SWX + 4, WY + 16, SWZ + 16), M.paint(guard, normal=field_normal(gf)))
    for z in (SWZ - 6.0, SWZ + 6.0):
        m.box("fender_stay", (tub_dims(z)[0] + SX - 1.5, 20.0, z - 0.5), (SWX - 3.0, 21.0, z + 0.5), chrome)
    # its red tail lamp on the mudguard's back
    ly, lz = TUB_LAMP
    m.box("guard_lamp", (SWX - 1.5, ly - 1.5, lz - 3.2), (SWX + 1.5, ly + 1.5, lz - 0.4), chrome)
    m.box("guard_lamp_lens", (SWX - 1.1, ly - 1.1, lz - 0.4), (SWX + 1.1, ly + 1.1, lz),
          Paint("sc_red", 2, noise=0, light=False, decal=lambda c: ("sc_red", 3.0 if c.face == "south" else 1.6)))
    lyy, lzz = SIDE_LAMP
    tube(m, "side_lamp", "z", (SWX, lyy), 3.2, lzz, lzz + 4.0, chrome_r("z", SWX, lyy))
    m.box("side_lamp_lens", (SWX - 1.2, lyy - 1.2, lzz - 0.4), (SWX + 1.2, lyy + 1.2, lzz),
          Paint("sc_lamp", 2, noise=0, light=False, decal=lambda c: ("sc_lamp", 3.0 if c.face == "north" else 2.0)))
    m.box("side_lamp_stem", (SWX - 0.5, 25.4, lzz + 1.5), (SWX + 0.5, lyy - 1.4, lzz + 2.5), chrome)

    # the chassis: two rails under the tub, the axle tube to its wheel, struts to the bike's frame
    fb = M.paint("black")
    for x in (SX - 6.0, SX + 6.0):
        m.box("chassis_rail", (x - 0.9, 5.0, -20.0), (x + 0.9, 6.8, 22.0), fb)
    m.box("chassis_cross", (BX + 4.4, 5.0, -16.0), (SX + 6.9, 6.8, -14.2), fb)
    m.box("chassis_cross", (BX + 4.4, 5.0, 16.0), (SX + 6.9, 6.8, 17.8), fb)
    m.box("axle_tube", (SX + 6.9, WY - 1.0, SWZ - 1.0), (SWX - 2.4, WY + 1.0, SWZ + 1.0), fb)
    m.box("axle_brace", (SX + 6.0, 6.0, SWZ - 0.8), (SX + 8.0, WY, SWZ + 0.8), fb)
    for z in (-12.0, 8.0):
        m.box("tub_mount", (SX - 6.6, 6.8, z - 0.8), (SX - 5.4, 9.6, z + 0.8), fb)
        m.box("tub_mount", (SX + 5.4, 6.8, z - 0.8), (SX + 6.6, 9.6, z + 0.8), fb)
    # the upper struts: from the bike's down tube and seat post to the tub's inner side
    for z, x0 in ((-11.0, BX + 3.0), (12.0, BX + 1.0)):
        xi = SX - tub_dims(z)[0] * 0.92
        m.box("strut", (x0, 18.2, z - 0.9), (xi + 1.0, 20.0, z + 0.9), fb)
        m.box("strut_clamp", (xi - 0.6, 17.6, z - 1.3), (xi + 1.0, 20.6, z + 1.3), chrome)


def _part(name):
    return lambda: sidecar_kit().all()[name]


ALL = {
    "sidecar_bike": sidecar_bike,
    "wheel_sidecar": wheel_sidecar,
    "sidecar_bike_screen": _part("sidecar_bike_screen"),
}
