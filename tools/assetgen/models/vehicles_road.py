"""
Road vehicles of the transport ladder (content/stsv/items/vehicles_road.yml), all original designs (no brand, no
real model): 짐자전거 (a heavy delivery bicycle, generation 2), 배달 오토바이 (a small step-through delivery
motorbike with a big rear box, 3), 사륜 오토바이 (an ATV, special), 소형 화물 밴 (a one-box microvan, 4) and 탑차 (a
cab-over box truck, 5).

The same rules as models/vehicles.py (read its doc first): every vehicle is a body model (drawn at its real size,
16 px a block, shrunk by `scale` and shown at vehicle.body-scale), its wheel (hub at model (8, 8, 8), axle along x,
spun and steered by the plugin), its load stages and its spinning parts (the bicycle's pedal crank: axle at model
(8, 8, 8), turned about x with the wheels: vehicles_road.yml spinners). Drawn coordinates: the vehicle's ground
centre at (0, 0, 0), front = north (-z), +x = the vehicle's RIGHT side. The driver sits on the left (-x) in the van
and the truck; riders of the two-wheelers and the ATV sit in the middle.

Detail is painted (decals), not modelled: the few extra boxes are the parts that make the silhouette (forks,
handlebars, mirrors, racks, the roller door's frame). Thin see-through parts (the basket's wire, the ATV's rack
grids, the bicycle's spoked wheels, glass) are 0.5 px planes with cut-out pixels.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3
from stsv_assets import presets
from stsv_assets.palette import ladder
from models.vehicles import (CLEAR, CHROME, DARK, PLASTIC, STEEL, IRON, TIRE, WHITE, lerp, lamp, plate, glass,
                             upholstery, _held_display, _wheel, _cart_crate, _cart_sack, _sack, _crate, _folded_tarp)

# ================================================================================================
# Shared helpers
# ================================================================================================

ANGLES = (-45, -22.5, 0, 22.5, 45)


def raw(ramp, step):
    """An exact (unlit) colour: a ramp step, rounded to a whole one."""
    return ("raw", ladder(ramp, math.floor(step + 0.5)))


def _rot(y, z, deg):
    """A point (y, z) turned about x by deg the way a model element turns (+ moves +y towards +z)."""
    a = math.radians(deg)
    return y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a)


def rod(m, name, a, b, w, paint, x=(None, None), depth=None):
    """A straight bar in the y-z plane from a = (y, z) towards b = (y, z), w px thick (x from -w/2 to w/2 unless x
    is given), drawn as one box turned about x by the allowed angle (0, 22.5, 45) closest to the line. depth: its
    size across the line in the y-z plane (default w)."""
    dy, dz = b[0] - a[0], b[1] - a[1]
    length = math.hypot(dy, dz)
    depth = w if depth is None else depth
    best = None
    for vertical in (True, False):
        for sign in (1, -1):
            for ang in ANGLES:
                ey, ez = (sign * length, 0) if vertical else (0, sign * length)
                ry, rz = _rot(ey, ez, ang)
                err = math.hypot(ry - dy, rz - dz)
                if best is None or err < best[0] - 1e-9:
                    best = (err, vertical, sign, ang)
    _, vertical, sign, ang = best
    x0, x1 = (-w / 2, w / 2) if x[0] is None else x
    if vertical:
        y0, y1 = sorted((a[0], a[0] + sign * length))
        frm, to = (x0, y0, a[1] - depth / 2), (x1, y1, a[1] + depth / 2)
    else:
        z0, z1 = sorted((a[1], a[1] + sign * length))
        frm, to = (x0, a[0] - depth / 2, z0), (x1, a[0] + depth / 2, z1)
    m.box(name, frm, to, paint, rotation=("x", ang, (0, a[0], a[1])) if ang else None)


def tube(ramp, lit=3, dark=1.6, base=2):
    """A painted or polished tube: the faces towards the light (top, front, the vehicle's left) bright."""
    def decal(c):
        if c.face in ("up", "north", "west"):
            return (ramp, lit)
        if c.face in ("down", "south", "east"):
            return (ramp, dark)
        return None
    return Paint(ramp, base, noise=0, rim=0.3, ao=0.4, decal=decal)


def mesh(step=2, ramp="blade", lit=3, edge=None, frame=True):
    """Wire mesh on a 0.5 px plane: a wire every `step` px both ways, its frame round the edge, clear between."""
    edge = edge if edge is not None else (ramp, lit)

    def decal(c):
        if c.nu <= 1 or c.nv <= 1:
            return raw(*edge)
        if frame and (c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1)):
            return raw(*edge)
        if c.u % step == 0 or c.v % step == 0:
            return raw(ramp, lit - (0.6 if (c.u + c.v) % 2 else 0))
        return ("raw", CLEAR)
    return Paint(ramp, 2, noise=0, light=False, decal=decal)


def _pivot_parts(m):
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================
# 짐자전거 (heavy delivery bicycle) 2.2 x 0.6 x 1.25 blocks (+ the rider)
# ================================================================================================

# drawn px: axles 6 up, 10 either side of the middle; the frame a classic diamond (rod-brake roadster): the
# bottom bracket a little ahead of the middle, a 45° down tube, the seat tube leaning back 22.5°, stays to the rear
# axle, a long steel rack over the rear wheel and a wire basket over the front one.
BIKE = {"r": 6.0, "front": -10.0, "rear": 10.0, "bb": (5.5, -1.5), "seat": (14.0, 2.2), "head": ((11.0, -7.7), (15.5, -5.8)),
        "frame": "blacksteel"}


def wheel_bicycle():
    """A 28-inch roadster wheel (12 px): a black tyre, a bright steel rim lit along its top front, eight wire spokes
    on a see-through wheel and a hub. Three 0.5 px planes: the rim and the spokes in the middle one, the tyre's two
    walls either side of it only dark rubber (one bright rim, not three side by side: from an angle three bright
    rings and the tread's blocks read as noise)."""
    m = BModel("wheel_bicycle", shift=(8, 8, 8), floor=None)
    R = 6.0

    def disc(spokes):
        def decal(c):
            y, z = c.p[1], c.p[2]
            r = math.hypot(y, z)
            if r > R + 0.05:
                return ("raw", CLEAR)
            if c.face not in ("east", "west"):
                return raw("rubber", 1) if r > R - 1.2 else ("raw", CLEAR)    # the plane's thin edges: tread only
            lit = y - 0.5 * z
            if r > R - 1.0:
                return raw("rubber", 1.6 if lit > 3 else 1 if lit > -3 else 0.6)   # the tyre's wall, lit on its upper front
            if not spokes:
                return ("raw", CLEAR)
            if r > R - 1.9:
                return raw("blade", 3 if lit > 1.5 else 2 if lit > -2 else 1)  # the rim
            if r < 1.3:
                return raw("steel", 4)
            # eight spokes, each one texel wide: an X along the diagonals and a + half a texel off the middle
            # (a darker steel than the rim: at one texel each they would otherwise fill the wheel in)
            if abs(y - z) < 0.3 or abs(y + z) < 0.3 or abs(y - 0.5) < 0.3 or abs(z - 0.5) < 0.3:
                return raw("iron", 3.4 if lit > 0 else 2.6)
            return ("raw", CLEAR)
        return Paint("rubber", 1, noise=0, light=False, decal=decal)
    m.box("disc", (-0.25, -R, -R), (0.25, R, R), disc(True))
    # the tyre's two walls a little apart from the spokes' plane (planes right against each other would hide each other)
    m.box("tyre", (-1.0, -R, -R), (-0.5, R, R), disc(False))
    m.box("tyre", (0.5, -R, -R), (1.0, R, R), disc(False))

    def hub(c):
        if c.face in ("east", "west"):
            return ("blade", 3) if c.u == 0 and c.v == 0 else ("steel", 4)
        return ("steel", 3)
    m.box("hub", (-2.25, -0.75, -0.75), (2.25, 0.75, 0.75), Paint("steel", 3, noise=0, rim=0, ao=0, decal=hub))   # past the stays and fork
    m.display = presets.furniture(gui_scale=0.6)
    return m


def bicycle_crank():
    """The pedal crank (turned by the plugin with the wheels, half as fast): a toothed chainring on the right, a
    crank arm each side pointing opposite ways, a rubber block pedal with an amber reflector at each end."""
    m = BModel("bicycle_crank", shift=(8, 8, 8), floor=None)

    def ring(c):
        y, z = c.p[1], c.p[2]
        r = math.hypot(y, z)
        if c.face not in ("east", "west"):
            return raw("steel", 3) if r > 3.0 else ("raw", CLEAR)
        if r > 3.55:
            return ("raw", CLEAR)
        a = math.atan2(z, y)
        if r > 3.0:
            k = math.floor((a + math.pi) / (2 * math.pi) * 22)
            return raw("steel", 4) if k % 2 == 0 else ("raw", CLEAR)        # the teeth
        if r > 2.2:
            return raw("blade", 3 if y - z > 0 else 2)                       # the ring
        t = (a * 5 / (2 * math.pi)) % 1.0
        if r > 0.9 and 0.3 < t < 0.8:
            return ("raw", CLEAR)                                           # the holes between the spider's arms
        return raw("blade", 2)
    # the chainring outboard of the chain stay (they shared a side and flickered), under the chain case; the arms
    # and pedals outside the case
    m.box("chainring", (1.75, -3.5, -3.5), (2.25, 3.5, 3.5), Paint("blade", 2, noise=0, light=False, decal=ring))
    m.box("spindle", (-2.75, -0.5, -0.5), (2.75, 0.5, 0.5), tube("steel", 4, 2))
    arm = tube("blade", 3.4, 1.8)
    m.box("arm", (2.75, -3.75, -0.5), (3.5, 0.5, 0.5), arm)
    m.box("arm", (-3.5, -0.5, -0.5), (-2.75, 3.75, 0.5), arm)

    def pedal(c):
        if c.face in ("north", "south") and abs(c.p[0]) > 4.75:
            return ("fox", 4)                                               # the reflector
        if c.face == "up":
            return ("rubber", 2 if math.floor(c.p[0] * 2) % 2 else 3)
        return ("rubber", 1)
    p = Paint("rubber", 1, noise=0, rim=0.4, decal=pedal)
    m.box("pedal", (3.5, -4.25, -1.25), (6.25, -3.25, 1.25), p)
    m.box("pedal", (-6.25, 3.25, -1.25), (-3.5, 4.25, 1.25), p)
    m.display = presets.furniture(gui_scale=0.8)
    return m


def bicycle():
    m = BModel("cargo_bicycle", shift=(8, 8, 8), scale=2.0)
    B = BIKE
    F, RR, R = B["front"], B["rear"], B["r"]
    bb, seat = B["bb"], B["seat"]
    (hy0, hz0), (hy1, hz1) = B["head"]
    G = B["frame"]

    def frame(c):
        # the classic black enamel tube: a glossy highlight along its top and front, a near-black underside, a gold
        # pinstripe on the down tube and the top tube near the head, chrome lugs at the joints
        x, y, z = c.p
        if c.face == "up":
            return (G, 2.6)
        if c.face in ("north", "west"):
            return (G, 1.6)
        if c.face in ("down", "south", "east"):
            return (G, 0.0)
        return (G, 0.8)
    fp = Paint(G, 2, noise=0, rim=0.3, ao=0.4, decal=frame)

    def lined(c):
        # the down tube: a gold band near each end (lining), the rest as the frame
        x, y, z = c.p
        if c.face in ("west", "east") and (abs(z - hz0) < 2.2 or abs(z - bb[1]) < 1.6) and abs(y - 5) > 0.5:
            return ("gold", 2 if c.face == "west" else 1)
        return frame(c)
    m.group("frame")
    rod(m, "down_tube", (hy0, hz0), bb, 1.5, Paint(G, 2, noise=0, rim=0.3, ao=0.4, decal=lined))
    rod(m, "seat_tube", bb, seat, 1.5, fp)
    rod(m, "top_tube", (seat[0] - 0.5, seat[1] - 0.2), (seat[0] - 0.5, hz1 - 0.3), 1.25, fp)
    rod(m, "head_tube", (hy0 - 0.5, hz0 - 0.2), (hy1 + 0.5, hz1 + 0.2), 2, tube("blade", 3.4, 2))
    for s in (1, -1):
        rod(m, "chain_stay", (bb[0], bb[1]), (R, RR), 1, fp, x=sorted((s * 0.75, s * 1.75)))
        rod(m, "seat_stay", (seat[0] - 0.8, seat[1] + 0.3), (R + 0.3, RR - 0.1), 1, fp, x=sorted((s * 0.75, s * 1.75)))
        rod(m, "fork", (hy0, hz0), (R, F), 1, tube("blade", 3.4, 2), x=sorted((s * 1.0, s * 2.0)))
    m.box("bottom_bracket", (-2, bb[0] - 1, bb[1] - 1), (2, bb[0] + 1, bb[1] + 1), tube("blade", 3, 1.6))
    m.box("crown", (-2.25, hy0 - 1, hz0 - 1), (2.25, hy0, hz0 + 1), tube("blade", 3.4, 2))

    m.group("seat")
    rod(m, "seat_post", seat, (seat[0] + 3.0, seat[1] + 1.1), 1, tube("blade", 3.4, 2))     # up into the saddle's clamp

    def saddle(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 0.5 and z < 3.5:
                return ("leather", 1.6)                    # the seam down its middle
            return ("leather", 3.2 if x < -0.5 else 2.6)
        if c.face in ("north", "south", "east", "west"):
            if y < c.lo[1] + 0.5:
                return ("blade", 2.4)                      # the steel frame under the leather
            if math.floor(z) % 2 == 0 and c.face in ("east", "west"):
                return ("brass", 2)                        # rivets along the skirt
            return ("leather", 2)
        return ("iron", 2)
    sp = Paint("leather", 2, noise=0, rim=0.6, decal=saddle)
    sy = seat[0] + 2.8
    m.box("saddle", (-2.75, sy, seat[1] - 0.5), (2.75, sy + 1.5, seat[1] + 4.5), sp)
    m.box("saddle_nose", (-1.25, sy + 0.25, seat[1] - 3.5), (1.25, sy + 1.5, seat[1] - 0.5), sp)

    def spring(c):
        return ("blade", 3 if math.floor(c.p[1] * 2) % 2 else 1.4)
    for s in (1, -1):
        xa, xb = sorted((s * 1.0, s * 2.25))
        m.box("spring", (xa, sy - 1.5, seat[1] + 2.75), (xb, sy, seat[1] + 3.75), Paint("blade", 2, noise=0, decal=spring))

    m.group("bars")
    top = (hy1 + 2.5, hz1 + 0.6)
    rod(m, "stem", (hy1, hz1), top, 1, tube("blade", 3.4, 2))
    m.box("handlebar", (-6.5, top[0] - 0.5, top[1] - 0.4), (6.5, top[0] + 0.25, top[1] + 0.35), tube("blade", 3.4, 2))

    def grip(c):
        if c.face in ("north", "south"):
            return ("rubber", 1)
        return ("rubber", 2 if math.floor(c.p[2] * 2) % 2 else 1)
    for s in (1, -1):
        xa, xb = sorted((s * 5.0, s * 6.75))
        m.box("bar_end", (xa, top[0] - 0.5, top[1] + 0.35), (xb, top[0] + 0.25, top[1] + 1.5), tube("blade", 3.4, 2))
        m.box("grip", (xa, top[0] - 0.75, top[1] + 1.5), (xb, top[0] + 0.5, top[1] + 4.0), Paint("rubber", 1, noise=0, decal=grip))
        # rod brake levers running forward under the grips
        xa, xb = sorted((s * 4.0, s * 4.75))
        m.box("lever", (xa, top[0] - 1.25, top[1]), (xb, top[0] - 0.5, top[1] + 3.0), CHROME)   # hung from the bar

    def bell(c):
        if c.face == "up":
            return ("brass", 3) if c.u == 0 else ("brass", 2.4)
        return ("brass", 2 if c.face in ("north", "west") else 1.2)
    m.box("bell", (-4.25, top[0] + 0.25, top[1] - 1.5), (-2.75, top[0] + 1.25, top[1]), Paint("brass", 2, noise=0, decal=bell))

    m.group("guards")
    # mudguards: chords round each wheel (22.5° steps), chrome, a red reflector at the rear one's tail
    mg = tube("blade", 3.2, 1.8)
    rg = R + 1.1
    for axle, toward in ((F, -1), (RR, 1)):
        pts = [(R + rg * math.cos(math.radians(d)), axle + toward * rg * math.sin(math.radians(d))) for d in (-40, 0, 45, 95)]
        for a, b in zip(pts, pts[1:]):
            # each chord a little longer than the gap between its ends, so they meet without a gap
            dy, dz = b[0] - a[0], b[1] - a[1]
            n = math.hypot(dy, dz)
            a2 = (a[0] - dy / n * 0.4, a[1] - dz / n * 0.4)
            rod(m, "mudguard", a2, b, 2.0, mg, depth=0.5)
    # the chain case: a long plate over the chain on the right side, green with a chrome rim line
    def case(c):
        x, y, z = c.p
        if c.face in ("east", "west", "north", "south", "up"):
            if y > c.hi[1] - 0.6 or y < c.lo[1] + 0.6:
                return ("blade", 3 if c.face == "up" else 2.4)
            if c.face in ("east", "west") and abs(y - (c.lo[1] + c.hi[1]) / 2) < 0.4:
                return ("gold", 2)                         # a gold line along the case
            return (G, 1.6 if c.face == "east" else 1.0)
        return (G, 0.4)
    m.box("chain_case", (2.0, bb[0] - 1.75, bb[1] - 0.75), (2.75, bb[0] + 2.75, RR + 1), Paint(G, 2, noise=0, rim=0.4, decal=case))

    m.group("basket")
    bx, bz, by = 5.25, (-20.5, -11.5), (12.25, 19)

    def floor_plate(c):
        return ("steel", 2.4 if (math.floor(c.p[0]) + math.floor(c.p[2])) % 2 else 2)
    m.box("basket_floor", (-bx, by[0], bz[0]), (bx, by[0] + 0.5, bz[1]), Paint("steel", 2, noise=0, decal=floor_plate))
    wire = mesh(2, "blade", 2.6, edge=("blade", 3))
    m.box("basket", (-bx, by[0] + 0.5, bz[0]), (bx, by[1], bz[0] + 0.5), wire)
    m.box("basket", (-bx, by[0] + 0.5, bz[1] - 0.5), (bx, by[1], bz[1]), wire)
    for s in (1, -1):
        xa, xb = sorted((s * bx, s * (bx - 0.5)))
        m.box("basket", (xa, by[0] + 0.5, bz[0] + 0.5), (xb, by[1], bz[1] - 0.5), wire)
        rod(m, "basket_strut", (by[0], bz[0] + 3), (R, F), 0.5, Paint("iron", 3, noise=0), x=sorted((s * 2.5, s * 3.0)))

    def lamp_face(c):
        if c.face == "north":
            r = math.hypot(c.p[0], c.p[1] - 12.5)
            return ("lamp", 4) if r < 0.8 else ("lamp", 3) if r < 1.3 else ("blade", 3)
        return ("blade", 2.4 if c.face == "up" else 1.8)
    m.box("headlamp", (-1.5, 10.75, bz[0] - 1.5), (1.5, 14.25, bz[0]), Paint("blade", 2, decal=lamp_face, **lamp()))

    m.group("rack")
    ry, rz = 13.0, (1.5, 17.5)

    def rack(c):
        # the big flat carrier of a delivery bicycle: a heavy black steel frame, flat bars along it with dark gaps,
        # a red reflector on its tail
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > 4.9 or z < rz[0] + 0.6 or z > rz[1] - 0.6:
                return (G, 2.8)                            # the outer frame, lit
            return (G, 2.0) if math.floor(x + 6) % 2 == 0 else (G, -0.6)   # bars, dark gaps
        if c.face == "south":
            if abs(x) < 1.75 and y > ry - 0.1:
                return ("signal_red", 2)                   # the reflector
        return (G, 1.0 if c.face in ("north", "west") else 0.2)
    m.box("rack", (-5.5, ry, rz[0]), (5.5, ry + 1, rz[1]), Paint(G, 1, noise=0, rim=0.5, decal=rack))
    for s in (1, -1):
        rod(m, "rack_strut", (ry, rz[1] - 2.5), (R + 0.5, RR + 0.5), 1, fp, x=sorted((s * 3.0, s * 4.0)))
        rod(m, "rack_stay", (ry, rz[0] + 0.5), (seat[0] - 1.5, seat[1] + 1), 0.75, fp, x=sorted((s * 1.5, s * 2.25)))

    def tail(c):
        if c.face == "south":
            return ("signal_red", 2.4 if c.u in (1, 2) else 1.6)
        return ("signal_red", 0.6)
    m.box("taillamp", (-1.5, ry - 2, rz[1] - 0.75), (1.5, ry, rz[1] - 0.25), Paint("signal_red", 1, decal=tail, **lamp()))
    # the stand folded up behind the rear wheel (a hairpin of steel)
    m.box("stand", (-3.75, R - 1, RR + 2.5), (3.75, R - 0.25, RR + 3.25), Paint("iron", 3, noise=0))
    m.display = _held_display(m)
    return m


def bicycle_cargo(stage):
    """What shows on the bicycle: in the basket (11 x 9 px inside, its floor 12.75 px up, wire walls 7 px high) a
    head of cabbage and a bundle of green onions, then a paper bag and a tied newspaper bundle; on the rear rack a
    crate of apples under a rubber cord, then a burlap sack on top of it."""
    m = BModel(f"cargo_bicycle_cargo_{stage}", shift=(8, -4, 8), floor=None)   # drawn 12 px lower: its attachment (vehicles_road.yml cargo.at) 0.75 up
    fy = 12.75
    if stage >= 1:
        def cabbage(c):
            x, y, z = c.p
            r = math.hypot(x + 2, z + 14.5)
            if c.face == "up":
                return ("cabbage", 3 if r < 1.2 else 2 if (math.floor(x) + math.floor(z)) % 3 else 1)
            return ("cucumber", 3 if math.floor(y) % 2 else 2.4)
        cp = Paint("cabbage", 2, noise=0, rim=0.6, decal=cabbage)
        m.box("cabbage", (-4.5, fy, -18), (1, fy + 5, -12.25), cp)
        m.box("cabbage", (-4.25, fy + 5, -17.25), (0.25, fy + 6, -12.5), cp)

        def onion(c):
            y = c.p[1]
            if y > fy + 8:
                return ("sprout", 3 if math.floor(c.p[0] * 2) % 2 else 1.6)      # the green tops
            if y < fy + 2.5:
                return ("garlic", 3)                                             # white bulbs
            if abs(y - (fy + 5)) < 0.5:
                return ("fabric_red", 2)                                          # a rubber band
            return ("garlic", 3.4) if y < fy + 4 else ("sprout", 2.4)
        m.box("onions", (2, fy, -19.75), (4.25, fy + 11, -17.5), Paint("garlic", 2, noise=0, rim=0.3, decal=onion))
    if stage >= 2:
        def bag(c):
            x, y, z = c.p
            if c.face == "up":
                return ("paper", 1)
            if y > c.hi[1] - 1.5:
                return ("paper", 0.4 if math.floor(x * 2 + z * 2) % 2 else 1)   # the folded top
            return ("bean", 3 if c.face in ("north", "west") else 2.4)
        m.box("paper_bag", (1.5, fy, -15.5), (4.75, fy + 7.5, -12), Paint("bean", 2, noise=0, rim=0.4, decal=bag))

        def news(c):
            x, y, z = c.p
            if c.face in ("east", "west", "north", "south") and math.floor(y * 2) % 2 == 0:
                return ("paper", 0.6)                                           # page edges
            if abs(z + 16) < 0.6 or abs(x + 1) < 0.5:
                return ("straw", 2)                                             # the string
            return ("paper", 2.2)
        m.box("newspapers", (-4.75, fy + 6, -19.75), (0.75, fy + 8.5, -15.25), Paint("paper", 2, noise=0, rim=0.4, decal=news))
    if stage >= 3:
        # on the rack behind the saddle (the rider sits over the rack's front end)
        _cart_crate(m, -4, 7, 8, 10, 6, y=14, fill=None, ramp="wood")
        def apples(c):
            r = h3(math.floor(c.p[0]), 1, math.floor(c.p[2]), 5)
            return ("tomato", 2.4 if r > 0.4 else 1.6) if r > 0.12 else ("sprout", 2)
        m.box("apples", (-3, 20, 8), (3, 20.75, 16), Paint("tomato", 2, noise=0, rim=0.2, decal=apples))

        def cord(c):
            return ("fabric_red", 2 if math.floor(c.p[2] + c.p[1]) % 2 else 1.2)
        for z in (9.5, 14.5):
            m.box("cord", (-4.5, 13.75, z), (4.5, 20.25, z + 0.75), Paint("fabric_red", 2, noise=0, decal=cord))
    if stage >= 4:
        _sack(m, -3.5, 20.75, 9, 7, 7, 4, band="denim")
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================
# 배달 오토바이 (step-through delivery motorbike with a big rear box) 2.1 x 0.8 x 1.7 blocks
# ================================================================================================

MOTO = "vermilion"


def wheel_scooter():
    """The scooter's wheel: 10 px across, 2.5 px wide, a six-spoke cast silver hub."""
    return _wheel("wheel_scooter", 10, 2.5, cap="blade", cap_d=6, spokes=6)


def _chords(m, name, centre, r, angles, toward, w, paint, depth=0.75):
    """An arc of straight pieces round centre (y, z) at radius r, from angle to angle (degrees from the top, towards
    `toward` = -1 the front, +1 the back), each overlapping the next a little (a mudguard, a fender)."""
    pts = [(centre[0] + r * math.cos(math.radians(d)), centre[1] + toward * r * math.sin(math.radians(d))) for d in angles]
    for a, b in zip(pts, pts[1:]):
        dy, dz = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dy, dz)
        rod(m, name, (a[0] - dy / n * 0.5, a[1] - dz / n * 0.5), b, w, paint, depth=depth)


def scooter():
    m = BModel("delivery_scooter", shift=(8, 8, 8), scale=2.0)
    R, F, RR = 5.0, -11.0, 10.0

    def red(c, shade=0.0):
        y = c.p[1]
        return (MOTO, lerp(y, 6, 20, 1.2, 2.8) + shade)

    def paint(c):
        x, y, z = c.p
        if c.face == "up":
            return (MOTO, 2.8)
        if c.face == "down":
            return (MOTO, 0.6)
        return red(c, -0.4 if c.face in ("east", "south") else 0)
    body = Paint(MOTO, 2, noise=0, rim=0.6, decal=paint)

    m.group("front")
    for s in (1, -1):
        xa, xb = sorted((s * 1.75, s * 2.75))
        rod(m, "fork_lower", (R, F), (R + 6, F + 2.5), 1.25, tube("blade", 3.4, 2), x=(xa, xb))
        rod(m, "fork_upper", (R + 5.5, F + 2.3), (R + 12.5, F + 5.2), 1, tube("steel", 3.6, 2.2), x=(xa, xb))
    _chords(m, "fender", (R, F), R + 1.0, (-45, 0, 45, 67.5), -1, 3.5, body)

    def cowl(c):
        x, y, z = c.p
        if c.face == "north":
            r = math.hypot(x, y - 19.0)
            if r < 1.6:
                return ("lamp", 4 if r < 0.8 else 3)          # the headlamp
            if r < 2.4:
                return ("blade", 3)                            # its chrome ring
            if abs(x) > 3.2 and 18.5 <= y < 20:
                return ("fox", 4)                              # turn signals at the corners
            return red(c)
        if c.face == "up":
            if abs(x) < 1.5 and z > -6.5:
                r = math.hypot(x, z + 5.5)
                if r < 1.4:
                    return ("dial", 1 if (x > 0 and z > -5.5) else 2)  # the speedometer
                return ("rubber", 1)
            return (MOTO, 2.8)
        if c.face == "south":
            return ("rubber", 1)
        return red(c, -0.3)
    m.box("cowl", (-3.5, 17, -9.5), (3.5, 21, -4.5), Paint(MOTO, 2, noise=0, rim=0.6, decal=cowl, **{}))
    # a short clear windshield on the cowl, leaning back
    m.box("windshield", (-3.5, 21, -9.25), (3.5, 27, -8.75), glass(sun=0, streak=0.35), rotation=("x", 22.5, (0, 21, -9)))
    m.box("handlebar", (-7, 19.25, -5.25), (7, 20, -4.25), tube("blade", 3.4, 2))

    def muff(c):
        # padded winter hand muffs (핸들 토시) over the grips: quilted black nylon, the stitched seams lit, an open
        # cuff towards the rider
        x, y, z = c.p
        if c.face == "south":
            return ("rubber", 0) if 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1 else ("rubber", 2)
        q = (math.floor(z * 1.5) + (math.floor(x * 1.5) if c.face == "up" else math.floor(y * 1.5))) % 3
        return ("rubber", (2.6 if c.face == "up" else 1.8) + (0.5 if q == 0 else 0))
    for s in (1, -1):
        xa, xb = sorted((s * 4.75, s * 8.0))
        m.box("hand_muff", (xa, 18.25, -7.0), (xb, 21.25, -3.25), Paint("rubber", 1, noise=0, rim=0.5, decal=muff))
        xa, xb = sorted((s * 4.0, s * 4.75))
        rod(m, "mirror_stalk", (20, -5), (24.5, -3.5), 0.6, tube("blade", 3.4, 2), x=(xa, xb))
        xa, xb = sorted((s * 3.0, s * 6.5))

        def mirror(c):
            # a round chrome-backed mirror: the glass in a chrome rim (its corners cut away: it reads round)
            corner = c.face in ("south", "north") and (c.u in (0, c.nu - 1)) and (c.v in (0, c.nv - 1))
            if c.face == "south":
                if corner:
                    return ("blade", 1.6)
                return ("glass", 3.4 if c.u == 1 and c.v == 1 else 2.4) if 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1 else ("blade", 3)
            return ("blade", 3.4 if c.face in ("north", "up", "west") else 2.2)
        m.box("mirror", (xa, 24.25, -4.25), (xb, 27.25, -3.5), Paint("blade", 2, noise=0, rim=0.5, decal=mirror))

    def shield(c):
        # the leg shield: red outside with a white stripe down the middle, white plastic on the rider's side
        x, y, z = c.p
        if c.face == "south":
            return ("car_white", lerp(y, 7, 17, 1.4, 2.6))
        if c.face == "north" and abs(x) < 1.0 and y > 8:
            return ("car_white", 2.6)
        if c.face == "north" and y < 10:
            return ("rubber", 1)                               # a black rubber skirt at the bottom
        return paint(c)
    m.box("leg_shield", (-5, 9, -7.5), (5, 17, -5.75), Paint(MOTO, 2, noise=0, rim=0.5, decal=shield))
    m.box("head", (-2, 15, -6.5), (2, 17.5, -4.75), body)

    m.group("frame")
    m.box("tunnel", (-2.5, 7, -5.75), (2.5, 10, 1.0), body)

    def engine(c):
        x, y, z = c.p
        if c.face == "west" and 0 < z < 3.5 and 4 < y < 7.5:
            r = math.hypot(z - 1.75, y - 5.75)
            return ("blade", 3 if r < 1.0 else 2.4 if r < 1.6 else 1.6)   # the round crankcase cover
        if c.face in ("east", "west", "north", "south", "up") and z < -2.5:
            return ("steel", 3.4 if math.floor(z * 2) % 2 else 1.6)       # the cylinder's cooling fins
        return ("steel", 2)
    ep = Paint("steel", 2, noise=0, rim=0.5, decal=engine)
    m.box("engine", (-3, 3, -2.5), (3, 8, 4.5), ep)
    m.box("cylinder", (-2, 4, -6), (2, 7, -2.5), ep)
    for s in (1, -1):
        xa, xb = sorted((s * 3.0, s * 5.5))
        m.box("footpeg", (xa, 5.5, 0), (xb, 6.5, 1.5), Paint("rubber", 1, noise=0))
        xa, xb = sorted((s * 3.0, s * 5.5))
        m.box("pillion_peg", (xa, 7.25, 7), (xb, 8, 8.25), Paint("rubber", 1, noise=0))   # under the side cover

    def muffler(c):
        x, y, z = c.p
        if c.face == "south":
            return ("rubber", 0) if c.u in (1, 2) and c.v in (1, 2) else ("iron", 2)
        if c.face in ("east", "up") and 6 < z < 11 and math.floor(z) % 2 == 0:
            return ("rubber", 1)                                          # slots in the heat shield
        return ("blade", 3 if c.face in ("up", "east") else 2)
    rod(m, "exhaust", (2.75, -4), (2.75, 5), 1, tube("iron", 3, 1.6), x=(2.5, 3.5))
    rod(m, "muffler", (4.5, 4.5), (7.0, 13.0), 2.5, Paint("blade", 2, noise=0, rim=0.5, decal=muffler), x=(2.75, 5.25))
    for s in (1, -1):
        xa, xb = sorted((s * 2.25, s * 3.25))
        rod(m, "swingarm", (5.5, 1.5), (R, RR), 1, tube("iron", 3, 1.4), x=(xa, xb))

        def shock(c):
            return ("blade", 3.4) if math.floor(c.p[1] * 1.5) % 2 else ("fabric_red", 2)   # a red spring on a chrome rod
        rod(m, "shock", (R, RR), (R + 8, RR - 3.3), 1, Paint("blade", 2, noise=0, decal=shock), x=(xa, xb))

    def side(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            if 9.5 <= y < 12.5 and 1.5 <= z < 7.5:
                if y >= 12 or y < 10 or z < 2 or z >= 7:
                    return ("car_white", 1.6)
                return ("car_white", 2.6)                      # a white side panel
            if abs(y - 13.25) < 0.5:
                return ("car_white", 3)                        # a pinstripe under the seat
        return paint(c)
    m.box("side_cover", (-3.5, 8, 0), (3.5, 14, 9.5), Paint(MOTO, 2, noise=0, rim=0.6, decal=side))

    def seat(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(z - 5.5) < 0.5:
                return ("rubber", 1)                           # the step between rider and pillion
            if abs(abs(x) - 2.5) < 0.5:
                return ("rubber", 3)                           # the stitched piping
            return ("rubber", 2.6 if x < 0 else 2.2)
        return ("rubber", 1.6)
    sp = Paint("rubber", 2, noise=0, rim=0.6, decal=seat)
    m.box("seat", (-3.5, 14, -1.5), (3.5, 15.75, 5.5), sp)
    m.box("pillion", (-3.25, 14, 5.5), (3.25, 16.5, 9.0), sp)

    def rear(c):
        x, y, z = c.p
        if c.face == "south":
            if y >= 11 and abs(x) < 2:
                return ("signal_red", 2.6 if abs(x) < 1 else 1.6)     # the tail lamp
            if y >= 11 and abs(x) >= 2.25:
                return ("fox", 4)                                     # turn signals
            return red(c, -0.4)
        return paint(c)
    m.box("rear_fender", (-3, 10, 9.5), (3, 12.5, 15), Paint(MOTO, 2, noise=0, rim=0.5, decal=rear))

    def plate_face(c):
        if c.face == "south":
            got = plate(math.floor(c.p[0] + 2.5), math.floor(10 - c.p[1]), 5, 3)
            return got or ("car_white", 2)
        return ("iron", 2)
    m.box("plate", (-2.5, 7, 14.25), (2.5, 10, 14.75), Paint("car_white", 2, noise=0, decal=plate_face))

    m.group("box")
    m.box("carrier", (-4.5, 16.5, 7.5), (4.5, 17.25, 17.5), Paint("iron", 2, noise=0, rim=0.6))
    for s in (1, -1):
        rod(m, "carrier_stay", (16.6, 16.5), (12, 13), 0.75, Paint("iron", 3, noise=0), x=sorted((s * 3.0, s * 3.75)))

    def box(c):
        # the classic red delivery box (배달통): red with a white band round it and a white lid, the lid's seam a pixel
        # under the top, a chrome latch and two reflectors on the back, a white bowl with steam painted on each side
        # (a generic food-delivery sign, no brand)
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "up":
            if abs(x) > 4.6 or z < 7.6 or z > 18.4:
                return ("car_white", 1.6)
            return ("car_white", 3)
        if c.face == "down":
            return ("iron", 2)
        if fy == 25:
            return ("car_white", 0.8)                          # the lid's seam
        if c.face in ("east", "west"):
            # the bowl sign: a wide bowl (its lit rim, the bowl, a narrow foot) under three wavy wisps of steam (two
            # dots over a bowl read as a face)
            u, row = math.floor(z - 13), fy
            if row == 22 and -3 <= u < 3:
                return ("car_white", 3.2)                      # the bowl's rim
            if row == 21 and -2 <= u < 2:
                return ("car_white", 2.6)                      # the bowl
            if row == 20 and -1 <= u < 1:
                return ("car_white", 2.0)                      # its foot
            if row in (23, 24) and u in ((-2, 0, 2) if row == 23 else (-1, 1, 3)):
                return ("car_white", 2.4)                      # steam rising, wavy
        if 19 <= fy <= 19 or fy == 18:
            return ("car_white", 2.8 if fy == 19 else 2.2)     # the white band
        if c.face == "south":
            if fy in (21, 22) and abs(x) < 1:
                return ("blade", 3 if fy == 22 else 2)          # the latch
            if fy == 20 and abs(x) > 3.5:
                return ("fox", 4)                                # amber reflectors
        return (MOTO, lerp(y, 17, 27, 1.6, 2.8))          # the red box, lit towards its top
    m.box("delivery_box", (-5.5, 17.25, 7.5), (5.5, 27, 18.5), Paint("car_white", 2, noise=0, rim=0.6, decal=box))
    m.box("lid_rim", (-5.75, 25, 7.25), (5.75, 25.5, 18.75), Paint("car_white", 1, noise=0))
    m.display = _held_display(m)
    return m


# ================================================================================================
# 사륜 오토바이 (ATV: chunky knobbly tyres, racks front and back) 2.0 x 1.3 x 1.2 blocks
# ================================================================================================

ATV_BODY = "fox"


def wheel_atv():
    """A fat knobbly tyre (10 px across, 4.5 px wide): staggered knobs across the tread that stand out at the
    shoulders, a dark steel wheel with four lug nuts and a bright rim lip."""
    m = BModel("wheel_atv", shift=(8, 8, 8), floor=None)
    d, w2 = 10, 2.25

    def tyre(c):
        x, y, z = c.p
        r = math.hypot(y, z)
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * 16)
        lit = y - 0.5 * z
        if c.face in ("east", "west"):
            if r > 4.2:
                return ("rubber", 1.8 if k % 2 == 0 and lit > -2 else 1.2 if k % 2 == 0 else 0.6)  # knob ends at the shoulder
            if r > 3.4:
                return ("rubber", 1.2 if lit > 0 else 0.8)
            if r > 2.9:
                return ("blade", 3 if lit > 0 else 2)                  # the rim lip
            if r < 1.0:
                return ("steel", 4)                                    # the hub cap
            nut = any(abs(y - py) < 0.6 and abs(z - pz) < 0.6 for py, pz in ((1.5, 0.5), (-1.5, -0.5), (0.5, -1.5), (-0.5, 1.5)))
            return ("blade", 3) if nut else ("steel", 1.4 if lit > 0 else 1)
        # the tread: chevron knobs, staggered either side of the middle, dark gaps between
        half = 0 if x < 0 else 1
        if (k + half) % 2 == 0:
            return ("rubber", 2.6 if abs(x) > 1.0 else 2.2)
        return ("rubber", 0.2)
    cyl(m, "tyre", (0, 0), d, -w2, w2, Paint("rubber", 1, noise=0, rim=0.2, ao=0.2, decal=tyre), axis="x")

    # big knobs standing out of the tread every 45 degrees (the silhouette reads knobbly, not round): each a block
    # across the tyre, its outer face lit, its leading side darker
    def knob(c):
        if c.face in ("east", "west"):
            return ("rubber", 1.4)
        return ("rubber", 2.6 if c.face == "up" else 1.0)
    kp = Paint("rubber", 1, noise=0, rim=0.4, ao=0.2, decal=knob)
    r = d / 2
    for k, (ang, flip) in enumerate([(0, 1), (45, 1), (0, -1), (45, -1)]):
        for side in (1, -1):
            # a knob on the +y (or -y) side, turned about the axle by 0 or 45 degrees; the second set on +z / -z
            if side > 0:
                frm, to = (-w2 - 0.25, flip * r - (0.25 if flip > 0 else 0.75), -0.75), (w2 + 0.25, flip * r + (0.75 if flip > 0 else 0.25), 0.75)
            else:
                frm, to = (-w2 - 0.25, -0.75, flip * r - (0.25 if flip > 0 else 0.75)), (w2 + 0.25, 0.75, flip * r + (0.75 if flip > 0 else 0.25))
            m.box("knob", frm, to, kp, rotation=("x", ang, (0, 0, 0)) if ang else None)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def atv():
    m = BModel("atv", shift=(8, 8, 8), scale=2.0)
    WX, WZ = 7.75, 10.0           # wheel centres
    O = ATV_BODY

    def plastic(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(abs(x) - 5) < 0.5:
                return (O, 1.6)                               # the moulded ridge between the fender and the middle
            return (O, 3.2 if x < 0 else 2.8)
        if c.face == "down":
            return ("rubber", 1)
        if y < c.lo[1] + 0.75:
            return ("rubber", 1.4)                            # a black edge round the bottom
        return (O, lerp(y, c.lo[1], c.hi[1], 1.6, 2.8))
    body = Paint(O, 2, noise=0, rim=0.6, decal=plastic)

    def skirt_px(c):
        # the fenders' black moulded skirts down over the tyres, a lit edge along their top
        if c.face == "up":
            return (O, 3.0)
        if c.p[1] > 10:
            return (O, 2.2)
        return ("rubber", 1.6 if c.face in ("east", "west") else 1.0)
    skirt = Paint(O, 2, noise=0, rim=0.6, decal=skirt_px)

    m.group("frame")
    for s in (1, -1):
        xa, xb = sorted((s * 2.5, s * 3.75))
        m.box("rail", (xa, 3.5, -13), (xb, 5, 13), tube("iron", 2.6, 1.2))
    m.box("cross", (-2.5, 3.5, -13), (2.5, 5, -11.75), tube("iron", 2.6, 1.2))

    def engine(c):
        x, y, z = c.p
        if c.face in ("east", "west") and math.floor(y) % 2 == 0 and y > 6:
            return ("steel", 3.4)                             # cooling fins
        if c.face in ("east", "west") and y <= 6:
            r = math.hypot(z, y - 5)
            return ("blade", 3 if r < 1.2 else 2.2) if r < 2 else ("steel", 1.8)
        return ("steel", 2)
    m.box("engine", (-3.5, 3, -4), (3.5, 10.5, 4), Paint("steel", 2, noise=0, rim=0.5, decal=engine))

    m.group("front")

    def nose(c):
        x, y, z = c.p
        if c.face == "north":
            ax = abs(x)
            if 1.5 <= ax < 4 and 7.5 <= y < 9.5:
                return ("lamp", 4 if ax < 3 and y >= 8.5 else 3)  # the headlights
            if ax < 1.25 and 6 <= y < 9.5:
                return ("rubber", 0 if math.floor(y) % 2 else 1)   # a grille slot
            if y < 6.5:
                return ("rubber", 1.4)
        return plastic(c)
    m.box("nose", (-4.5, 5.5, -16.5), (4.5, 10.5, -6), Paint(O, 2, noise=0, rim=0.6, decal=nose))
    def front_fender(c):
        # twin headlamps set in the fender's front (black housings, a bright core), a black moulded edge under them,
        # a lit crest along the top
        x, y, z = c.p
        if c.face == "north":
            ax = abs(x)
            if 5 <= ax < 9 and y >= 10.75:
                if ax < 5.5 or ax >= 8.5:
                    return ("tint", 0.6)
                return ("lamp", 4.4) if (6 <= ax < 7.5 and y >= 11.5) else ("lamp", 2.8)
            if y < 11:
                return ("rubber", 1.4)
        if c.face == "up" and z < -14.5:
            return (O, 3.4)                                   # the rounded front edge
        return plastic(c)
    m.box("front_fender", (-10.75, 10.5, -15.5), (10.75, 12.5, -4.5), Paint(O, 2, noise=0, rim=0.6, decal=front_fender))
    for s in (1, -1):
        xa, xb = sorted((s * 9.75, s * 10.75))
        m.box("fender_lip", (xa, 9.0, -15.5), (xb, 10.5, -4.5), skirt)

    def bumper(c):
        return ("iron", 3 if c.face in ("up", "north") else 1.6)
    bp = Paint("iron", 2, noise=0, rim=0.4, decal=bumper)
    m.box("bumper", (-5.5, 5, -18.5), (5.5, 6.25, -17.25), bp)
    m.box("bumper", (-5.5, 9, -18.5), (5.5, 10, -17.25), bp)
    for s in (1, -1):
        xa, xb = sorted((s * 4.25, s * 5.25))
        m.box("bumper_post", (xa, 5.25, -18.25), (xb, 9.75, -17.5), bp)
        xa, xb = sorted((s * 1.5, s * 2.5))
        m.box("bumper_arm", (xa, 5.25, -17.25), (xb, 6.25, -16.5), bp)

    m.group("seat")

    def tank(c):
        x, y, z = c.p
        if c.face == "up" and math.hypot(x + 1.5, z + 4) < 1.1:
            return ("blade", 3)                               # the fuel cap
        if c.face in ("east", "west") and abs(y - 13) < 0.5:
            return ("rubber", 1.4)                            # a black band (the seam)
        return plastic(c)
    m.box("tank", (-4, 11, -6.25), (4, 15, -1), Paint(O, 2, noise=0, rim=0.6, decal=tank))

    def seat(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > 3:
                return ("rubber", 2.6)                        # the seat's rolled edge
            return ("rubber", 2.2 if math.floor(z) % 3 else 1.6)  # grip ribs
        return ("rubber", 1.4)
    m.box("seat", (-4, 11, -1), (4, 15.5, 9), Paint("rubber", 2, noise=0, rim=0.6, decal=seat))

    def board(c):
        if c.face == "up":
            return ("iron", 3) if (math.floor(c.p[0]) + math.floor(c.p[2])) % 3 == 0 else ("iron", 1.6)   # tread plate
        return ("iron", 1.6)
    for s in (1, -1):
        xa, xb = sorted((s * 3.5, s * 10.5))
        m.box("footboard", (xa, 6, -4.5), (xb, 7, 4.5), Paint("iron", 2, noise=0, decal=board))   # off the engine's sides

    m.group("bars")
    rod(m, "steering_post", (10.5, -7), (16.5, -5), 1.5, tube("iron", 3, 1.4))

    def pod(c):
        x, y, z = c.p
        if c.face == "north":
            r = math.hypot(x, y - 17.5)
            return ("lamp", 4) if r < 0.9 else ("lamp", 3) if r < 1.4 else (O, 2.6)
        if c.face == "up" and abs(x) < 1.25 and z > -6.5:
            return ("dial", 2) if math.floor(z) % 2 else ("rubber", 1)   # a small display
        return plastic(c)
    m.box("pod", (-2.75, 16, -8), (2.75, 19, -5), Paint(O, 2, noise=0, rim=0.6, decal=pod))
    m.box("handlebar", (-8.5, 17.5, -5.25), (8.5, 18.25, -4.5), tube("blade", 3.4, 2))

    def grip(c):
        return ("rubber", 2 if math.floor(c.p[0] * 2) % 2 else 1)
    for s in (1, -1):
        xa, xb = sorted((s * 6.5, s * 9.0))
        m.box("grip", (xa, 17.25, -5.5), (xb, 18.5, -4.25), Paint("rubber", 1, noise=0, decal=grip))
        xa, xb = sorted((s * 6.0, s * 6.75))
        m.box("hand_guard", (xa, 17, -7.25), (xb, 19, -5.5), body)

    m.group("rear")

    def tail(c):
        x, y, z = c.p
        if c.face == "south":
            ax = abs(x)
            if 1.5 <= ax < 3.5 and 8 <= y < 9.5:
                return ("signal_red", 2.6 if ax < 2.5 else 1.6)   # tail lamps
            if ax < 1.5 and 6 <= y < 8:
                got = plate(math.floor(x + 1.5), math.floor(8 - y), 3, 2)
                return got
        return plastic(c)
    m.box("tail", (-4.5, 5.5, 6), (4.5, 10.5, 16), Paint(O, 2, noise=0, rim=0.6, decal=tail))
    m.box("rear_fender", (-10.75, 10.5, 4.5), (10.75, 12.5, 15.5), body)
    for s in (1, -1):
        xa, xb = sorted((s * 9.75, s * 10.75))
        m.box("fender_lip", (xa, 9.0, 4.5), (xb, 10.5, 15.5), skirt)
    m.box("hitch", (-1, 4.5, 16), (1, 6, 18), bp)

    # racks: black steel tube, a rail round each and two cross tubes, on four short posts

    def rail(c):
        return ("iron", 2.6 if c.face == "up" else 1.6 if c.face in ("north", "west") else 0.6)
    rp = Paint("iron", 2, noise=0, rim=0.3, decal=rail)
    for z0, z1 in ((-16, -6.5), (9.5, 16)):
        for zc in (z0 + (z1 - z0) / 3, z0 + 2 * (z1 - z0) / 3):
            m.box("rack_bar", (-8.75, 13, zc - 0.5), (8.75, 13.75, zc + 0.5), rp)    # cross tubes (the load sits on them)
        m.box("rack_rail", (-9.5, 13.75, z0), (9.5, 15, z0 + 0.75), rp)
        m.box("rack_rail", (-9.5, 13.75, z1 - 0.75), (9.5, 15, z1), rp)
        for s in (1, -1):
            xa, xb = sorted((s * 8.75, s * 9.5))
            m.box("rack_rail", (xa, 13.75, z0 + 0.75), (xb, 15, z1 - 0.75), rp)
            m.box("rack_post", (xa, 12.5, z0 + 1), (xb, 13.25, z0 + 1.75), rp)
            m.box("rack_post", (xa, 12.5, z1 - 1.75), (xb, 13.25, z1 - 1), rp)
    m.display = _held_display(m)
    return m


# ================================================================================================
# 소형 화물 밴 (a one-box microvan: the cab over the front wheels, a closed cargo box, a sliding side door)
# 3.6 x 1.6 x 1.95 blocks
# ================================================================================================

VAN = {"front": -28, "back": 28, "half": 12, "belt": 15, "roof": 29, "cab_back": -11, "screen": -27, "front_axle": -18,
       "rear_axle": 17, "slot": 5.5}


def wheel_van():
    """The van's small steel wheel: 9 px across, 3 px wide, a plain white pressed steel disc."""
    return _wheel("wheel_van", 9, 3, cap="car_white", cap_d=5, spokes=4, plain=True)


def cargo_van():
    m = BModel("cargo_van", shift=(8, 8, 8), scale=2.0)
    V = VAN
    F, BK, H, BELT, ROOF, CB, FA, RA, SLOT = V["front"], V["back"], V["half"], V["belt"], V["roof"], V["cab_back"], \
        V["front_axle"], V["rear_axle"], V["slot"]
    DOOR = (CB + 1, CB + 15)          # the sliding door (right side, along z) and its rail

    def white(y):
        return ("car_white", lerp(y, 4, 28, 1.0, 2.7))

    def well(p):
        return abs(p[0]) > H - 3.1 and p[1] < 9.6 and any(abs(p[2] - a) <= SLOT + 0.1 for a in (FA, RA))

    def body(c):
        x, y, z = c.p
        fy, fz, ax = math.floor(y), math.floor(z), abs(x)
        if well(c.p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if y > ROOF:
                # the roof: pressed ribs across it, gutters along its edges
                if ax > H - 1:
                    return ("car_white", 1.6)
                return ("car_white", 3) if fz % 6 == 0 else ("car_white", 1.8) if fz % 6 == 1 else ("car_white", 2.4)
            if z < CB:
                return ("rubber", 1 if fz % 3 else 0)        # the cab floor, seen through the glass
            return ("car_white", 2.4)
        if c.face in ("east", "west"):
            if (fy == 9 and any(abs(z - a) <= SLOT + 0.6 for a in (FA, RA))) or \
                    (fy < 9 and any(abs(abs(z - a) - SLOT - 0.5) < 0.6 for a in (FA, RA))):
                return ("rubber", 1)                         # arch trim
            if fy in (11, 12):
                return ("celadon", 2 if fy == 12 else 1.4)   # the band along the side
            if fy == 10:
                return ("celadon", 3)
            if fy < 5:
                return ("tint", 2)                            # a dark sill
            if c.face == "east" and z > DOOR[0] - 0.6:
                if fz in (DOOR[0], DOOR[1]) and fy < ROOF - 1:
                    return ("car_white", 0.6)                 # the sliding door's seams
                if fy == ROOF - 3 and DOOR[0] <= fz < BK - 2:
                    return ("tint", 1)                        # its rail, along to the back
                if fy == 17 and DOOR[1] - 3 <= fz < DOOR[1] - 1:
                    return ("tint", 0) if fz == DOOR[1] - 3 else ("blade", 3)   # its handle
            if z < CB and fy < BELT:
                if fz == CB - 1 and fy >= 5:
                    return ("car_white", 0.6)                 # the cab door's back seam
                if fy == 13 and CB - 4 <= fz < CB - 2:
                    return ("blade", 3)                       # the cab door handle
            if fz == BK - 1 and 8 <= fy <= 9:
                return ("signal_red", 1)                      # a rear side marker
            if fz == F + 1 and 9 <= fy <= 10:
                return ("fox", 3)                             # a front side marker
            if c.face == "west" and fz in (BK - 7, BK - 6) and fy == 20:
                return ("car_white", 0.6)                     # the fuel door
            if z > CB and fy == 22:
                return ("car_white", 1.4)                     # a pressed crease along the box (its shadow) ...
            if z > CB and fy == 23:
                return ("car_white", 3.0)                     # ... and its lit upper edge
            if c.face == "west" and fz == DOOR[1] and 5 <= fy < ROOF - 1:
                return ("car_white", 0.6)                     # the left side's panel seam (the door's twin)
            if fy < 9 and h3(fz, fy, 5, 75) < 0.12 + (9 - fy) * 0.05:
                return ("car_white", 0.6)                     # road grime along the sill
            # a cargo pictogram (a parcel) on the box's sides, behind the door
            u, v = z - 20, y - 20
            if -3 <= u < 3 and -3 <= v < 2:
                if math.floor(u) in (-1, 0) or v >= 1:
                    return ("celadon", 1.4)                   # the parcel's tape
                return ("celadon", 2.4)
            return white(y)
        if c.face == "north":
            if fy == 14:
                return ("car_white", 3.2)                     # the nose's rounded top edge, catching the light
            if fy == 13 and ax < 11:
                return ("car_white", 1.2)                     # the hood's shut line under it
            if ax >= H - 0.8 and fy >= 5:
                return ("car_white", 3.0)                     # the rounded front corners
            if 8 <= fy <= 11 and ax < 5.5:
                if fy == 11:
                    return ("blade", 3.4)                     # a chrome bar along the grille's top
                return ("rubber", 0) if math.floor(x) % 4 == 0 else ("tint", 1.6) if fy == 9 else ("rubber", 0.5)   # its slats
            if 5 <= fy <= 7 and ax < 11:
                return ("tint", 0.8) if fy == 6 and ax < 8 else ("car_white", 1.6)   # a lower air intake
            return white(y)
        if c.face == "south":
            # the rear hatch: its seams, a tinted window high up (closed: painted), plate, handle
            if ax > H - 2.6 and 10 <= fy <= 22:
                return None                                   # the lamps (own boxes)
            if fy in (5, ROOF - 1) or (ax >= H - 3 and ax < H - 2):
                return ("car_white", 0.8)
            if 20 <= fy <= 26 and ax < H - 4:
                if fy == 26 or ax >= H - 5:
                    return ("rubber", 1)
                return ("tint", 1.4 if (math.floor(x) - fy) % 7 else 2.6)
            if 13 <= fy <= 14 and ax < 1.5:
                return ("blade", 3 if fy == 14 else 2)        # handle
            if 8 <= fy <= 10 and ax < 4.5:
                return plate(math.floor(x + 4.5), 10 - fy, 9, 3)
            return white(y)
        return None
    paint = WHITE(decal=body)

    m.group("body")
    # the lower body with the wheels' wells, the upper box behind the cab, the nose under the windscreen
    m.box("core", (-H + 3, 3, F), (H - 3, BELT, BK), paint)
    for s in (1, -1):
        xa, xb = sorted(((H - 3) * s, H * s))
        m.box("side", (xa, 3, F), (xb, BELT, FA - SLOT), paint)
        m.box("side", (xa, 9.5, FA - SLOT), (xb, BELT, FA + SLOT), paint)
        m.box("side", (xa, 3, FA + SLOT), (xb, BELT, RA - SLOT), paint)
        m.box("side", (xa, 9.5, RA - SLOT), (xb, BELT, RA + SLOT), paint)
        m.box("side", (xa, 3, RA + SLOT), (xb, BELT, BK), paint)
    m.box("box", (-H, BELT, CB), (H, ROOF, BK), paint)
    m.box("roof", (-H + 0.5, ROOF, F + 6), (H - 0.5, ROOF + 1, BK - 0.5), paint)

    # black protective mouldings along the sides between the wheels (over the green band), a lit top edge, and
    # black flares round the wheel arches
    def strip(c):
        return ("rubber", 2.4 if c.face == "up" else 1.6 if c.face in ("east", "west") else 1.0)
    rp = Paint("rubber", 1, noise=0, rim=0.5, decal=strip)
    for s in (1, -1):
        xa, xb = sorted((H * s, (H + 0.5) * s))
        m.box("moulding", (xa, 11, FA + SLOT + 1), (xb, 12.5, RA - SLOT - 1), rp)
        m.box("moulding", (xa, 11, RA + SLOT + 1), (xb, 12.5, BK - 0.5), rp)
        m.box("moulding", (xa, 11, F + 1), (xb, 12.5, FA - SLOT - 1), rp)
        for a in (FA, RA):
            m.box("arch_flare", (xa, 9.5, a - SLOT - 1), (xb, 10.5, a + SLOT + 1), rp)
            for zz in (a - SLOT - 1, a + SLOT):
                m.box("arch_flare", (xa, 4, zz), (xb, 9.5, zz + 1), rp)

    def bumper(c):
        x, y = c.p[0], c.p[1]
        fy, ax = math.floor(y), abs(x)
        if c.face == "north" and 3 <= fy <= 5 and ax < 4.5:
            got = plate(math.floor(x + 4.5), 5 - fy, 9, 3)
            if got:
                return got
        if c.face == "north" and fy == 5 and 5.5 <= ax < 10:
            return ("rubber", 0) if math.floor(x) % 2 else ("tint", 1)    # the intake's slots
        if c.face in ("north", "south") and fy == 6:
            return ("iron", 3.4)                                         # its lit top edge
        if c.face in ("north", "south") and fy == 2:
            return ("rubber", 0.6)
        if c.face == "up":
            return ("iron", 3.2)
        return ("iron", 2)
    bp = Paint("iron", 2, noise=0, rim=0.5, decal=bumper)
    m.box("bumper", (-H - 0.25, 2.5, F - 1), (H + 0.25, 7, F), bp)
    m.box("bumper", (-H - 0.25, 2.5, BK), (H + 0.25, 6.5, BK + 1), bp)

    m.group("lights")

    def headlight(c):
        k = math.floor(abs(c.p[0]) - 6)
        fy = math.floor(c.p[1])
        if c.face != "north":
            return ("blade", 2)
        if k >= 4:
            return ("fox", 4) if fy == 10 else ("fox", 3)    # amber corner signal
        if fy == 11:
            return ("lamp", 4) if k in (1, 2) else ("lamp", 3)
        return ("lamp", 2) if k in (1, 2) else ("blade", 3)
    for s in (1, -1):
        xa, xb = sorted((6 * s, 11.5 * s))
        m.box("headlight", (xa, 9, F - 0.5), (xb, 12.5, F), Paint("lamp", 3, decal=headlight, **lamp()))

        def taillight(c):
            fy = math.floor(c.p[1])
            if c.face != "south":
                return ("signal_red", 0)
            if fy >= 19:
                return ("fox", 4) if fy == 20 else ("fox", 3)
            if fy <= 11:
                return ("lamp", 3) if fy == 11 else ("blade", 3)      # reverse lamp
            return ("signal_red", 2.6) if fy in (15, 16) else ("signal_red", 1.6)
        xa, xb = sorted(((H - 2.5) * s, (H - 0.25) * s))
        m.box("taillight", (xa, 10, BK), (xb, 22, BK + 0.5), Paint("signal_red", 1, decal=taillight, **lamp()))

    m.group("cab")
    # the windscreen raked 22.5°, side windows (front door panes), the roof's front over them
    rake = (ROOF - BELT) * math.tan(math.radians(22.5))
    m.box("windscreen", (-H + 0.5, BELT, V["screen"]), (H - 0.5, BELT + (ROOF - BELT) / math.cos(math.radians(22.5)), V["screen"] + 0.5),
          glass(sun=2, wipers=True, streak=0.25), rotation=("x", 22.5, (0, BELT, V["screen"])))

    def window(c):
        x, y, z = c.p
        front = V["screen"] + (y - BELT) * math.tan(math.radians(22.5))
        if z < front - 0.3:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw("car_white", 2)
        if z < front + 1.2 or y > ROOF - 1 or z > CB - 1:
            return raw("car_white", 2 if y < ROOF - 1 else 3)        # the A pillar, the roof rail, the B pillar
        if z < front + 2.2 or y > ROOF - 2 or z > CB - 2 or y < BELT + 1:
            return raw("rubber", 1)                                  # the seal
        if y > ROOF - 4:
            return raw("tint", 3)
        d = (math.floor(z) - math.floor(y)) % 11
        if d in (0, 1) and y > BELT + 3:
            return raw("glass", 3)
        if d == 3 and y > BELT + 3:
            return raw("glass", 2)
        return ("raw", CLEAR)
    for s in (1, -1):
        xa, xb = sorted(((H - 0.5) * s, H * s))
        m.box("side_window", (xa, BELT, V["screen"]), (xb, ROOF, CB), Paint("glass", 2, noise=0, light=False, decal=window))

    def roof_front(c):
        if c.face == "down":
            return ("rubber", 1)
        if c.face == "north":
            return ("car_white", 2.6)
        return None
    m.box("visor", (-H + 0.5, ROOF - 0.5, F + 4.5), (H - 0.5, ROOF + 0.5, F + 6), WHITE(decal=roof_front))
    m.box("cowl", (-H + 0.5, BELT, F + 0.5), (H - 0.5, BELT + 0.5, V["screen"] + 0.5), Paint("tint", 1, noise=0))

    def mirror(c):
        if c.face == "south":
            if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                return ("rubber", 1)
            return ("glass", 3 if c.u == 1 else 2)
        return ("rubber", 1.4)
    for s in (1, -1):
        xa, xb = sorted((H * s, (H + 1.5) * s))
        m.box("mirror_arm", (xa, 18, V["screen"] + 3), (xb, 18.75, V["screen"] + 4), PLASTIC)
        xa, xb = sorted(((H + 1.25) * s, (H + 2.5) * s))
        m.box("mirror", (xa, 17, V["screen"] + 2.5), (xb, 21.5, V["screen"] + 4.5), Paint("rubber", 1, noise=0, decal=mirror))

    m.group("inside")
    m.box("dash", (-H + 0.5, BELT, V["screen"] + 1), (H - 0.5, BELT + 2, V["screen"] + 4), DARK)
    m.box("column", (-6, BELT + 1, V["screen"] + 4), (-5, BELT + 2.5, V["screen"] + 4.5), PLASTIC)

    def steering(c):
        u, v = c.u, c.v
        if c.face not in ("north", "south"):
            return raw("rubber", 1)
        if (v in (0, 3) and 1 <= u <= 3) or (v in (1, 2) and u in (0, 4)):
            return raw("rubber", 1 if v else 2)
        if v == 2:
            return raw("tint", 2 if u == 2 else 1)
        return ("raw", CLEAR)
    m.box("wheel", (-8, BELT + 2, V["screen"] + 4.5), (-3, BELT + 6, V["screen"] + 5), Paint("rubber", 1, noise=0, light=False, decal=steering))
    seat = dict(bolster=("rubber", 2), insert=("celadon", 1), stitch=("celadon", 3))
    for s in (1, -1):
        lo, hi = (min(1.5 * s, 10.5 * s), BELT - 6, CB - 3), (max(1.5 * s, 10.5 * s), BELT + 6, CB)
        m.box("seat_back", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        m.box("cushion", (lo[0], BELT - 6, CB - 8), (hi[0], BELT - 4.5, CB - 3), Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    m.display = _held_display(m)
    return m


# ================================================================================================
# 탑차 (a cab-over box truck: a tall closed aluminium box, a roller door at the back) 6.5 x 2.2 x 3.0 blocks
# ================================================================================================

# drawn px (drawn at its real size, shrunk 2.5 times: vehicle body-scale 2.5, so it fits a model's -16..32)
TRUCK = {"front": -52, "cab_back": -27, "box_front": -25.5, "back": 52, "cab_half": 16.5, "box_half": 17.5,
         "belt": 27, "roof": 43, "box_floor": 16, "box_top": 48, "front_axle": -40, "rear_axle": 28, "slot": 8.5}
CAB = "denim"


def wheel_truck():
    """The box truck's wheel: 14 px across, 5 px wide, a white-painted steel disc with a ring of round holes and a
    chrome hub with its nuts."""
    m = BModel("wheel_truck", shift=(8, 8, 8), floor=None)
    d, w2 = 14, 2.5

    def tyre(c):
        x, y, z = c.p
        r = math.hypot(y, z)
        lit = y - 0.5 * z
        if c.face in ("east", "west"):
            if r > 5.2:
                return ("rubber", 1.4 if lit > 3 else 1 if lit > -3 else 0.6)    # the sidewall
            if r > 4.6:
                return ("car_white", 1.6 if lit > 0 else 1)                       # the rim's lip
            if r < 1.6:
                nut = any(abs(y - py) < 0.5 and abs(z - pz) < 0.5 for py, pz in ((1.0, 0), (-1.0, 0), (0, 1.0), (0, -1.0)))
                return ("blade", 3.4) if nut or r < 0.6 else ("blade", 2.2)       # the hub and its nuts
            a = math.atan2(z, y)
            if 2.6 < r < 3.8 and (a * 6 / (2 * math.pi)) % 1.0 < 0.45:
                return ("iron", 0.4)                                              # holes in the disc
            return ("car_white", 2.6 if lit > 0 else 1.8)
        k = math.floor((math.atan2(z, y) + math.pi) / (2 * math.pi) * 36)
        if abs(x) < 0.5:
            return ("rubber", 0)                                                  # a groove round the middle
        return ("rubber", 2 if (k + (x > 0)) % 2 else 1.2)                        # the tread's blocks
    cyl(m, "tyre", (0, 0), d, -w2, w2, Paint("rubber", 1, noise=0, rim=0.2, ao=0.2, decal=tyre), axis="x")
    m.display = presets.furniture(gui_scale=0.5)
    return m


def box_truck():
    m = BModel("box_truck", shift=(8, 8, 8), scale=2.5)
    T = TRUCK
    F, CB, BF, BK, CH, BH = T["front"], T["cab_back"], T["box_front"], T["back"], T["cab_half"], T["box_half"]
    BELT, ROOF, FLOOR, TOP, FA, RA, SLOT = T["belt"], T["roof"], T["box_floor"], T["box_top"], T["front_axle"], T["rear_axle"], T["slot"]

    def navy(y):
        return (CAB, lerp(y, 8, 26, 1.0, 2.8))

    def cab(c):
        x, y, z = c.p
        fy, fz, ax = math.floor(y), math.floor(z), abs(x)
        if ax > 11.9 and y < 16.6 and abs(z - FA) <= SLOT + 0.1 and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if z < CB and ax < CH - 0.5:
                return ("rubber", 1 if fz % 3 else 0)        # the cab floor, through the glass
            return (CAB, 2.6)
        if c.face == "north":
            if ax >= CH - 1 and fy >= 10:
                return (CAB, lerp(y, 8, 26, 2.0, 3.4))       # the rounded front corners, catching the light
            if 13 <= fy <= 20 and ax < 10:
                if fy in (13, 20) or ax >= 9:
                    return ("blade", 3 if fy == 20 else 2.4)  # the grille's chrome frame
                return ("rubber", 0) if fy % 2 else ("iron", 2.4)   # its bars
            if fy == 22 and ax < 14:
                return ("blade", 3)                           # a chrome strip under the windscreen
            if fy == 25 and ax < 12 and math.floor(x) % 2 == 0:
                return (CAB, 0.6)                             # vent slots
            return navy(y)
        if c.face in ("east", "west"):
            if z < F + 1 and fy >= 10:
                return (CAB, 3.0)                             # the rounded corner, seen from the side
            if (fy == 16 and abs(z - FA) <= SLOT + 0.6) or (fy < 16 and abs(abs(z - FA) - SLOT - 0.5) < 0.6):
                return ("rubber", 1)                          # arch trim
            if fz in (F + 3, CB - 2) and fy >= 10:
                return (CAB, 0.4)                             # the door's seams
            if fy == 19 and CB - 7 <= fz <= CB - 4:
                return ("tint", 0) if fz < CB - 4 else ("blade", 3)   # the handle
            if fy in (12, 13):
                return ("car_white", 3 if fy == 13 else 2.2)  # a white band along the cab
            if fy < 10:
                return ("rubber", 1.4)                        # the step under the door
            if F + 1 <= fz <= F + 2 and 15 <= fy <= 16:
                return ("fox", 3)                             # amber repeater
            if fy == BELT - 1:
                return (CAB, 3.0)                             # the shoulder catching the light under the windows
            if fy < 14 and h3(fz, fy, 3, 71) < 0.10 + (14 - fy) * 0.04:
                return (CAB, 0.8)                             # road grime thrown up by the front wheel
            return navy(y)
        if c.face == "south":
            return (CAB, 1.2)
        return None
    paint = Paint(CAB, 2, noise=0, grad=0, decal=cab)

    m.group("chassis")

    def rail(c):
        if c.face in ("east", "west") and math.floor(c.p[2]) % 8 == 0:
            return ("iron", 3)                                # rivets
        return ("iron", 1.6 if c.face != "up" else 2.4)
    for s in (1, -1):
        xa, xb = sorted((s * 6, s * 9))
        m.box("rail", (xa, 8.25, F + 6), (xb, 12, BK - 1), Paint("iron", 2, noise=0, decal=rail))
    # the subframe under the box, between the tyres (the rear tyres' tops, 14 px up, stand beside it under the box)
    m.box("subframe", (-10, 12, BF), (10, FLOOR, BK - 0.5), Paint("iron", 2, noise=0, rim=0.6))

    def guard(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            fy = math.floor(y)
            if fy in (9, 12):
                return raw("blade", 3 if fy == 12 else 2.4)   # two bars of the side guard
            if fy >= 10 and math.floor(z - (FA + SLOT + 4)) % 14 in (0, 1):
                return raw("iron", 1.6 if math.floor(z) % 2 else 2.2)   # the brackets up to the box
            return ("raw", CLEAR)
        return raw("blade", 2) if c.nu <= 1 or c.nv <= 1 else ("raw", CLEAR)
    for s in (1, -1):
        xa, xb = sorted((s * (BH - 0.75), s * (BH - 0.25)))
        m.box("side_guard", (xa, 8.5, FA + SLOT + 4), (xb, FLOOR, RA - SLOT - 1.5), Paint("blade", 2, noise=0, light=False, decal=guard))

        def fuel(c):
            if c.face in ("east", "west") and abs(c.p[1] - 11) < 0.5:
                return ("blade", 1.6)                         # a strap
            return ("blade", 3 if c.face == "up" else 2.4)
        if s < 0:
            m.box("fuel_tank", (-BH + 2, 7.5, CB - 1), (-9, 13, CB + 9), Paint("blade", 2, noise=0, rim=0.6, decal=fuel))
        else:
            m.box("battery_box", (9, 8, CB), (BH - 2, 13, CB + 6), Paint("iron", 2, noise=0, rim=0.6))
        xa, xb = sorted((s * 9.5, s * BH))

        def flap(c):
            return ("rubber", 0.6) if c.face in ("north", "south") and math.floor(c.p[1]) == 4 else ("rubber", 1.2)
        m.box("mud_flap", (xa, 3, RA + SLOT + 1), (xb, 13, RA + SLOT + 1.5), Paint("rubber", 1, noise=0, decal=flap))

    def bumper(c):
        x, y = c.p[0], c.p[1]
        if c.face in ("north", "south"):
            ax = abs(x)
            if c.face == "north" and 10.5 <= ax < 15 and 7.5 <= y < 10.5:
                return ("lamp", 4) if 11.5 <= ax < 13.5 and y >= 8.5 else ("lamp", 2)     # headlamps in the bumper
            if c.face == "north" and ax >= 15 and 7.5 <= y < 10.5:
                return ("fox", 4)
            if c.face == "north" and ax < 4.5:
                got = plate(math.floor(x + 4.5), 10 - math.floor(y), 9, 4)
                if got:
                    return got
            if math.floor(y) == 6:
                return ("rubber", 0)
        if c.face == "up":
            return ("iron", 3)
        return ("iron", 2)
    m.box("bumper", (-CH, 5, F - 1.5), (CH, 11, F), Paint("iron", 2, noise=0, rim=0.6, decal=bumper))

    def underrun(c):
        x, y = c.p[0], c.p[1]
        if c.face == "south":
            ax = abs(x)
            if 9 <= ax < 13 and 8 <= y:
                return ("signal_red", 2.6 if ax < 11 else 1.6)       # tail lamps
            if 13 <= ax < 15 and 8 <= y:
                return ("fox", 4)
            if ax < 4.5 and y >= 7:
                return plate(math.floor(x + 4.5), 10 - math.floor(y), 9, 3)
            return ("hazard", 1) if (math.floor(x) // 2) % 2 else ("rubber", 1)   # a striped underrun bar
        return ("iron", 2)
    m.box("underrun", (-BH + 1, 6, BK - 1), (BH - 1, 10.5, BK), Paint("iron", 2, noise=0, decal=underrun))

    m.group("cab")
    m.box("cab_core", (-12, 8, F), (12, BELT, CB), paint)
    for s in (1, -1):
        xa, xb = sorted((12 * s, CH * s))
        m.box("cab_side", (xa, 8, F), (xb, BELT, FA - SLOT), paint)
        m.box("cab_side", (xa, 16.5, FA - SLOT), (xb, BELT, FA + SLOT), paint)
        m.box("cab_side", (xa, 8, FA + SLOT), (xb, BELT, CB), paint)
    m.box("windscreen", (-CH + 0.5, BELT, F + 0.5), (CH - 0.5, ROOF, F + 1), glass(sun=3, wipers=True, streak=0.2))

    # black flares round the front wheel arches
    def flare(c):
        return ("rubber", 2.2 if c.face == "up" else 1.4 if c.face in ("east", "west") else 0.8)
    fl = Paint("rubber", 1, noise=0, rim=0.5, decal=flare)
    for s in (1, -1):
        xa, xb = sorted((CH * s, (CH + 0.5) * s))
        m.box("arch_flare", (xa, 16, FA - SLOT - 1), (xb, 17, FA + SLOT + 1), fl)
        for zz in (FA - SLOT - 1, FA + SLOT):
            m.box("arch_flare", (xa, 9, zz), (xb, 16, zz + 1), fl)

    def window(c):
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw(CAB, 2)
        if z < F + 2.5 or y > ROOF - 1.5 or z > CB - 1.5:
            return raw(CAB, 2.4 if y < ROOF - 1.5 else 3)            # the pillars, the roof rail
        if z > CB - 11 and y > BELT + 0.5:
            return raw(CAB, 2.2) if z > CB - 10 else raw("rubber", 1)   # the rear quarter: closed panel
        if z < F + 3.5 or y > ROOF - 2.5 or y < BELT + 1:
            return raw("rubber", 1)
        if y > ROOF - 5:
            return raw("tint", 3)
        d = (math.floor(z) - math.floor(y)) % 14
        if d in (0, 1) and y > BELT + 3:
            return raw("glass", 3)
        if d == 3 and y > BELT + 3:
            return raw("glass", 2)
        return ("raw", CLEAR)
    for s in (1, -1):
        xa, xb = sorted(((CH - 0.5) * s, CH * s))
        m.box("side_window", (xa, BELT, F + 0.5), (xb, ROOF, CB - 1), Paint("glass", 2, noise=0, light=False, decal=window))
    m.box("cab_back", (-CH, BELT, CB - 1), (CH, ROOF, CB), Paint(CAB, 1, noise=0))

    def roof(c):
        x, y, z = c.p
        if c.face == "down":
            return ("rubber", 1)
        if c.face == "up":
            return (CAB, 2.8) if math.floor(z) % 5 else (CAB, 2.2)
        return (CAB, 2.6)
    m.box("cab_roof", (-CH, ROOF, F), (CH, ROOF + 1.5, CB), Paint(CAB, 2, noise=0, rim=0.6, decal=roof))

    def deflector(c):
        # the wind deflector on the cab roof: navy, a plain white band across its front (no badge)
        x, y, z = c.p
        if c.face == "north" and math.floor(y) == ROOF + 3:
            return ("car_white", 2.6)
        if c.face == "up":
            return (CAB, 2.4)
        return (CAB, lerp(y, ROOF, TOP, 1.8, 2.6))
    m.box("deflector", (-CH + 1.5, ROOF + 1.5, CB - 9), (CH - 1.5, TOP - 0.5, CB), Paint(CAB, 2, noise=0, rim=0.6, decal=deflector),
          rotation=("x", 22.5, (0, ROOF + 1.5, CB)))
    m.box("visor", (-CH, ROOF - 0.5, F - 1.25), (CH, ROOF + 1, F), Paint(CAB, 1, noise=0))

    def mirror(c):
        if c.face == "south":
            if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                return ("rubber", 1)
            return ("glass", 3 if c.v == 2 else 2)
        return ("rubber", 1.4)
    for s in (1, -1):
        xa, xb = sorted((CH * s, (CH + 3) * s))
        m.box("mirror_arm", (xa, 33, F + 2), (xb, 34, F + 3), CHROME)
        m.box("mirror_arm", (xa, 25, F + 2), (xb, 26, F + 3), CHROME)
        xa, xb = sorted(((CH + 2.5) * s, (CH + 4) * s))
        m.box("mirror", (xa, 24, F + 1.5), (xb, 35, F + 3.5), Paint("rubber", 1, noise=0, decal=mirror))
        xa, xb = sorted(((CH - 4) * s, (CH - 1) * s))

        def step(c):
            return ("blade", 3) if c.face == "up" and math.floor(c.p[2]) % 2 else ("iron", 2)
        m.box("step", (xa, 4.5, CB - 6), (xb, 5.5, CB - 1.5), Paint("iron", 2, noise=0, decal=step))   # behind the front tyre

    m.group("inside")
    m.box("dash", (-CH + 0.5, BELT, F + 1.5), (CH - 0.5, BELT + 2.5, F + 5), DARK)

    def steering(c):
        u, v = c.u, c.v
        if c.face not in ("up", "down"):
            return raw("rubber", 1)
        if (v in (0, 4) and 1 <= u <= 4) or (v in (1, 2, 3) and u in (0, 5)):
            return raw("rubber", 1 if v else 2)
        if v == 2:
            return raw("tint", 2 if u in (2, 3) else 1)
        return ("raw", CLEAR)
    m.box("wheel", (-11, BELT + 3, F + 5), (-5, BELT + 3.5, F + 10), Paint("rubber", 1, noise=0, light=False, decal=steering),
          rotation=("x", -22.5, (0, BELT + 3, F + 7.5)))
    m.box("column", (-8.5, BELT + 1.5, F + 5), (-7.5, BELT + 3.25, F + 7.5), PLASTIC)   # the wheel on its column
    seat = dict(bolster=("rubber", 2), insert=("denim", 1), stitch=("denim", 3))
    for s in (1, -1):
        lo, hi = (min(3 * s, 13 * s), BELT, CB - 5), (max(3 * s, 13 * s), BELT + 11, CB - 2)
        m.box("seat_back", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))

    m.group("box")

    def box(c):
        # the aluminium box: corrugated sides (a lit rib every 3 px), bright top and bottom rails, corner posts,
        # amber marker lamps along the bottom rail, red ones at the top back corners, a plain stripe (no lettering)
        x, y, z = c.p
        fy, fz, fx = math.floor(y), math.floor(z), math.floor(x)
        top_rail, bottom_rail = y > TOP - 2, y < FLOOR + 2
        if c.face == "up":
            if abs(x) > BH - 1 or z > BK - 1 or z < BF + 1:
                return ("blade", 2.4)
            return ("steel", 4) if fz % 4 == 0 else ("steel", 4.6)
        if c.face == "down":
            return ("iron", 1)
        if c.face in ("east", "west"):
            if z > BK - 1.5 or z < BF + 1.5:
                return ("blade", 2.6 if c.face == "west" else 2)   # the corner posts
            if top_rail:
                if z > BK - 3.5 and fy == TOP - 2:
                    return ("signal_red", 2.6)
                return ("blade", 3 if fy == TOP - 1 else 2.2)
            if bottom_rail:
                if fy == FLOOR and math.floor(z - BF) % 16 in (7, 8):
                    return ("fox", 4)                         # marker lamps
                return ("blade", 2.6 if fy == FLOOR + 1 else 1.8)
            if 22 <= fy <= 24:
                return ("sapphire", 2.4 if fy == 23 else 1.6)  # a blue stripe along the box
            # smooth riveted aluminium panels between posts every 13 px (a lit edge, a shadow, a rivet line), the panel
            # lit up its height with a soft sheen band; a little road spray round the rear wheels
            k = math.floor(z - BF - 1.5) % 13
            if k == 0:
                return ("blade", 2.8)                         # the post, its lit face
            if k == 1:
                return ("steel", 2.4)                         # its shadow on the panel
            if k == 12 and fy % 3 == 0:
                return ("steel", 3.0)                         # a rivet line
            base = lerp(y, FLOOR + 2, TOP - 2, 4.0, 4.9)
            if 30 <= fy <= 32 and k in range(4, 10):
                base += 0.4                                   # the sheen band across each panel
            if y < FLOOR + 5 and abs(z - RA) < 13 and h3(fz, fy, 4, 74) < 0.04 + 0.06 * (FLOOR + 5 - y):
                return ("steel", base - 1.0)                  # (only round the rear wheels)
            return ("steel", base)
        if c.face == "north":
            if abs(x) > BH - 1.5 or top_rail:
                return ("blade", 2.6)
            return ("steel", 3.6 if fx % 3 else 3.0)
        if c.face == "south":
            ax = abs(x)
            if ax > BH - 2.5 or top_rail:
                if ax > BH - 2.5 and top_rail:
                    return ("signal_red", 2.6) if ax > BH - 1.5 else ("blade", 2.6)
                return ("blade", 2.6 if ax > BH - 1.5 else 2)   # the door frame
            if bottom_rail:
                if ax < 6 and fy == FLOOR + 1:
                    return ("blade", 3.4)                      # the shutter's lift bar
                return ("iron", 2.4 if fy == FLOOR else 3)
            if abs(x) < 1.5 and 18 <= fy <= 20:
                return ("blade", 3) if fy == 19 else ("iron", 1)   # the lock
            # the roller shutter: slats one pixel apart, each lit along its top
            return ("steel", 4.4) if fy % 2 else ("steel", 2.6)
        return None
    m.box("box", (-BH, FLOOR, BF), (BH, TOP, BK), Paint("steel", 3, noise=0, grad=0, decal=box))
    m.display = _held_display(m)
    return m


def atv_cargo(stage):
    """Small loads on the ATV's racks (front rack 19 x 9.5 px, rear 19 x 6.5 px, both 13.75 px up): a jerrycan
    strapped on the back, then a crate of firewood on the front, a tool box, and when full a tied sack on top."""
    m = BModel(f"atv_cargo_{stage}", shift=(8, 8, 8), floor=None)
    y = 13.75
    if stage >= 1:
        def can(c):
            x, yy, z = c.p
            if c.face in ("east", "west") and (abs(z - 12.75) + abs(yy - (y + 3)) < 1.6) and abs(z - 12.75) > 0.3:
                return ("signal_green", 1.4)                 # the pressed X on its side
            if c.face == "up" and z < 11.5 and x > 0:
                return ("rubber", 1)                          # the spout cap
            return ("signal_green", 1 if c.face in ("east", "south") else 1.6)
        m.box("jerrycan", (-1.25, y, 10.25), (4.75, y + 6, 15.25), Paint("signal_green", 1, noise=0, rim=0.5, decal=can))
        m.box("can_handle", (0, y + 6, 12), (3.5, y + 7, 12.75), Paint("signal_green", 1, noise=0))
    if stage >= 2:
        def logs(c):
            x, yy, z = c.p
            if c.face in ("north", "south"):
                r = math.hypot((x % 2.5) - 1.25, (yy - y) % 2.5 - 1.25)
                return ("whetstone", 3 if r < 0.6 else 2) if r < 1.1 else ("bark", 1)   # cut log ends with rings
            return ("bark", 2 if math.floor(x * 2 + yy) % 3 else 1)
        m.box("firewood", (-7.5, y, -15), (2.5, y + 5, -8.25), Paint("bark", 2, noise=0, rim=0.5, decal=logs))   # clear of the lamp pod
    if stage >= 3:
        def toolbox(c):
            x, yy, z = c.p
            if math.floor(yy) == math.floor(y + 3):
                return ("blade", 3)                           # the lid's seam
            if c.face == "up" and abs(x + 4.5) < 1.5:
                return ("rubber", 1)                          # the handle
            return ("fabric_red", 2.4 if c.face in ("up", "north", "west") else 1.6)
        m.box("toolbox", (-8.5, y, 10.25), (-1.75, y + 4, 15.25), Paint("fabric_red", 2, noise=0, rim=0.5, decal=toolbox))   # inside the rack's rail
        _cart_crate(m, 3.5, -15, 5, 7, 4, y=y, fill="beans")
    if stage >= 4:
        _sack(m, -7, y + 5, -14.5, 9, 6, 4, band="leaf_dark")
        _sack(m, -8.5, y + 4, 10.25, 6, 5, 4)
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================

ALL = {
    "cargo_bicycle": bicycle,
    "wheel_bicycle": wheel_bicycle,
    "bicycle_crank": bicycle_crank,
    **{f"cargo_bicycle_cargo_{k}": (lambda k=k: bicycle_cargo(k)) for k in range(1, 5)},
    "delivery_scooter": scooter,
    "wheel_scooter": wheel_scooter,
    "atv": atv,
    "wheel_atv": wheel_atv,
    "cargo_van": cargo_van,
    "wheel_van": wheel_van,
    "box_truck": box_truck,
    "wheel_truck": wheel_truck,
    **{f"atv_cargo_{k}": (lambda k=k: atv_cargo(k)) for k in range(1, 5)},
}
