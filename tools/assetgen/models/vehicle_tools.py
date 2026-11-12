"""
작업기 (content/stsv/vehicle_tools.yml; plugin: dev.stsv.core.vehicle.VehicleTools): what the vehicles work with, drawn on
them while it is on (Ctrl in the driver's seat switches). Original designs, no brand.

  소달구지  ox_plough               쟁기: a Korean plough (성에 beam, 술 body, iron 보습 share, 볏 mouldboard throwing the
                                    soil to the right, a handle) slung under a sulky frame on the cart's own axle and wheels
                                    (they stay: wheels: true), a bench for two in front, the pole to the ox. Takes the cart's
                                    place (its bed shut, its load kept).
  경운기    tiller_rotary           로터리: a riding rotary on the tiller's hitch: the red hood over the tine drum, the chain
                                    case down its left side, the rear shield, a bench for two over the gearbox. Takes the
                                    trailer's place (its wheels hidden: the rotor carries it).
            tiller_rotary_tines     the tine drum (spins while the engine runs): L-shaped blades on flanges, staggered
  트랙터    tractor_rotary          로터리 3 wide on the three-point hitch behind the rear wheels, a PTO shaft in its yellow
                                    guard, the top link, the side chain case, the rear shield
            tractor_rotary_tines
            tractor_seeder          파종기: a 3-row seed drill: the blue hopper (a window on the seed), seed hoses down to three
                                    coulter discs, row markers folded up at the ends
            tractor_seeder_roller   its press roller (rolls on the ground behind)
            tractor_header          수확기: a 3-wide header on the front: pan, cutter bar with guard teeth, pointed crop
                                    dividers, the cross auger, reel arms
            tractor_header_reel     the reel (bats with tines, turning while the engine runs)
  통통배    boat_trawl              그물: a gallows (A-frame) on the stern with blocks, the warps down to two otter boards, a
                                    float line on the water and the green net trailing under it
            boat_net_drum           the net drum on the gallows (turns slowly while trawling)
  1톤 트럭  truck_tarp              방수포 덮개: green canvas over five bows, roped to the drop sides, the rear flap rolled up.
                                    Its origin is the bed's front edge: taken off, the plugin folds it up to the front
                                    (scales it along the bed: like a folding canvas top)
  탑차      box_truck_shutter       the roller door's slats (origin: the top of the doorway; the plugin rolls them up into it)
            box_truck_doorway       the open doorway behind them: the box's inside (shown while the door is open)

As in models/vehicles*.py: drawn at real size (16 px a block), the unit's ground centre (a trailer's axle) at (0, 0, 0),
front = north (-z), +x = the vehicle's right; big pieces shrunk by `scale` and shown at that scale (vehicle_tools.yml
scale). Moving parts have their axle at the model's (8, 8, 8), along x. The detail is painted (decals).
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3, bands
from stsv_assets import presets
from models.vehicles import lerp, CLEAR, _grain, _held_display
from models.vehicles_road import rod, mesh, raw
from models.vehicles_farm import _paint_metal, OXCART, RED, ORANGE

GROUND_Y = 0


def metal(ramp, base=2.0, edge=0.6):
    return Paint(ramp, int(round(base)), noise=0, rim=0.5, decal=_paint_metal(ramp, base + 0.2, edge))


def wood(ramp="wood_dark", axis="z", seed=3, base=2.0):
    def decal(c):
        return (ramp, (base + 0.6 if c.face == "up" else base - 0.4) + _grain(c, axis, seed))
    return Paint(ramp, 2, noise=0, rim=0.5, decal=decal)


STEEL_BRIGHT = Paint("steel", 3, noise=0, rim=0.4, decal=lambda c: ("steel", 4 if c.face in ("up", "north") else 2.6))
IRON = Paint("iron", 2, noise=0, rim=0.5, decal=lambda c: ("iron", 2.6 if c.face == "up" else 1.7))
BLACK = Paint("rubber", 1, noise=0, rim=0.3, decal=lambda c: ("rubber", 1.8 if c.face == "up" else 1.0))


def half_disc(m, name, cy, cz, d, x0, x1, ymin, paint):
    """The side plate of a hood: a pixel disc of diameter d round (cy, cz) in the y-z plane, only its part above ymin,
    from x0 to x1."""
    for (a, b, hw) in bands(d):
        lo, hi = cy + a, cy + b
        if hi <= ymin:
            continue
        m.box(name, (x0, max(lo, ymin), cz - hw), (x1, hi, cz + hw), paint(vrim=0))


def soil(m, x, y, z, w=3, h=1.5, d=3, seed=0):
    """A clod of turned soil (dark brown, a few lighter crumbs)."""
    def decal(c):
        k = h3(*c.i, 90 + seed)
        return ("soil", 1.0 + (0.9 if k > 0.8 else -0.3 if k < 0.2 else 0.3) + (0.4 if c.face == "up" else 0))
    y += 0.04 * (seed % 7)       # no two clods share a face
    m.box("soil", (x, y, z), (x + w, y + h, z + d), Paint("soil", 1, noise=0, rim=0.3, decal=decal))


def tines(name, half, r=6.5, flanges=None, ramp="steel"):
    """A rotary's tine drum, its axle along x at (0, 0, 0): a shaft with a pair of L-shaped blades (out from the shaft
    either way, their tips bent sideways, one to each side) every few pixels, each pair turned 45° from the last: the
    blades strike the ground one after another, in a spiral round the drum."""
    m = BModel(name, shift=(8, 8, 8), floor=None)
    shaft = Paint("iron", 2, noise=0, rim=0.3, decal=lambda c: ("iron", 2.4 if c.face in ("up", "north") else 1.4))
    m.box("shaft", (-half, -1, -1), (half, 1, 1), shaft)
    n = max(2, int(round((2 * half - 4) / 6.5)) + 1)
    xs = flanges or [-half + 2 + i * (2 * half - 4) / (n - 1) for i in range(n)]
    blade = Paint(ramp, 3, noise=0, rim=0.5, decal=lambda c: (ramp, 4.2 if c.face in ("up", "north", "west") else 2.6))
    for k, x in enumerate(xs):
        o = k % 4
        rot = ("x", 45, (0, 0, 0)) if o in (1, 3) else None
        along_y = o in (0, 1)
        for sgn in (1, -1):
            bend = sgn if k % 2 == 0 else -sgn
            a0, a1 = (0.9, r) if sgn > 0 else (-r, -0.9)
            t0, t1 = (r - 1.5, r) if sgn > 0 else (-r, -r + 1.5)
            tx0, tx1 = (x + 0.4, x + 2.4) if bend > 0 else (x - 2.4, x - 0.4)
            if along_y:
                m.box("blade", (x - 0.4, a0, -0.75), (x + 0.4, a1, 0.75), blade, rotation=rot)
                m.box("blade_tip", (tx0, t0, -0.75), (tx1, t1, 0.75), blade, rotation=rot)
            else:
                m.box("blade", (x - 0.4, -0.75, a0), (x + 0.4, 0.75, a1), blade, rotation=rot)
                m.box("blade_tip", (tx0, -0.75, t0), (tx1, 0.75, t1), blade, rotation=rot)
    m.display = presets.furniture(gui_scale=0.4)
    return m


def hood(m, half, cy, cz, r, colour, top_y=None, front=True, shield=True):
    """A rotary's hood over a drum of radius r round (cy, cz), `half` px either side: the curved top (three plates: the
    front one turned 45°, the flat top, the rear shield hanging back), the side plates, a lit lip."""
    p = metal(colour, 2.0, 0.8)
    top = cy + r + 1.5 if top_y is None else top_y
    m.box("hood_top", (-half, top, cz - r * 0.55), (half, top + 1, cz + r * 0.45), p)
    if front:
        # the front plate: from the top's front edge down and forward at 45°
        m.box("hood_front", (-half, top - 5.5, cz - r * 0.55 - 1), (half, top + 1, cz - r * 0.55), p,
              rotation=("x", -45, (0, top + 1, cz - r * 0.55)))

    def shield_px(c):
        x, y, z = c.p
        if c.face in ("up", "south", "north") and math.floor(x) % 6 == 0:
            return (colour, 1.2)                                      # stiffening ribs
        return _paint_metal(colour, 2.2, 0.8)(c)
    if shield:
        # the rear shield: hinged on the top's back edge, hanging back and down to drag over the tilled soil
        m.box("shield", (-half, top - 9, cz + r * 0.45), (half, top + 1, cz + r * 0.45 + 1), Paint(colour, 2, noise=0, rim=0.5, decal=shield_px),
              rotation=("x", 22.5, (0, top + 1, cz + r * 0.45)))
        m.box("hinge", (-half + 1, top + 0.5, cz + r * 0.45 - 0.5), (half - 1, top + 1.5, cz + r * 0.45 + 1), IRON)
    for s in (1, -1):
        xa, xb = sorted((s * half, s * (half + 1)))
        half_disc(m, "hood_side", cy, cz, 2 * r + 3, xa, xb, cy - 2, p)


# ================================================================================================
# 소달구지 · 쟁기
# ================================================================================================

def ox_plough():
    m = BModel("ox_plough", shift=(8, 8, 8), scale=2.0)
    C = OXCART
    m.group("frame")
    # the cart's axle and pole (the wheels on it are the cart's own: drawn by the plugin)
    m.box("axle", (-13, 8, -1), (13, 10, 1), IRON)
    beam = wood("wood_dark", "z", 6)
    for s in (1, -1):
        xa, xb = sorted((6 * s, 8.5 * s))
        m.box("side_beam", (xa, 10, -20), (xb, 12, 4), beam)
        xa, xb = sorted((6.5 * s, 9 * s))
        m.box("bolster", (xa, 9.6, -2.5), (xb, 10.4, 2.5), beam)
    m.box("cross", (-8.25, 10.1, -21), (8.25, 11.9, -19), beam)
    m.box("cross", (-8.25, 10.1, 3), (8.25, 11.9, 5), beam)

    def pole(c):
        z = c.p[2]
        if z < C["pole_tip"] + 1.5:
            return ("iron", 2.4 if c.face == "up" else 1.6)
        if abs(z + 22) < 0.75 or abs(z + 25) < 0.75:
            return ("leather", 1.8 if c.face != "up" else 2.4)        # rawhide lashings
        return ("wood_dark", (2.6 if c.face == "up" else 1.6) + _grain(c, "z", 11))
    m.box("pole", (-1, 11, C["pole_tip"]), (1, 13, -19.5), Paint("wood_dark", 2, noise=0, rim=0.5, decal=pole))

    m.group("bench")

    def bench(c):
        if c.face == "up":
            return ("wood", 2.8 + _grain(c, "x", 9) * 0.6)
        return ("wood", 1.6 + _grain(c, "x", 9))
    m.box("bench", (-10, 15, -17.5), (10, 16, -11), Paint("wood", 2, noise=0, rim=0.6, decal=bench))
    for s in (1, -1):
        xa, xb = sorted((7 * s, 8.5 * s))
        m.box("bench_post", (xa, 12, -16), (xb, 15, -14.5), wood("wood_dark", "y", 4))

    def cushion(c):
        k = (math.floor(c.p[0]) + math.floor(c.p[2] * 2)) % 3
        return ("straw", 2.0 + (0.8 if k == 0 else 0))
    m.box("cushion", (-8.5, 16, -17), (8.5, 16.5, -11.5), Paint("straw", 2, noise=0, rim=0.2, decal=cushion))
    m.box("footboard", (-7, 11.5, -26), (7, 12.5, -20), wood("wood", "x", 7))

    m.group("plough")
    # 성에 (the beam): from the cross beam behind the axle down and back to the body
    rod(m, "beam", (11, 4), (5.5, 13), 2.5, wood("wood_dark", "z", 12), x=(-1.25, 1.25))
    # 술 (the body / sole) and its 보습 (share) pointing forward into the ground, 볏 (mouldboard) over it
    m.box("sole", (-1.5, 0.5, 9), (1.5, 6, 16), wood("wood", "y", 13))

    def share(c):
        x, y, z = c.p
        if c.face in ("up", "east", "west") and z < 9 + (y + 1) * 1.2:
            return ("steel", 4.4)                                     # the worn bright edge
        return ("iron", 2.4 if c.face == "up" else 1.4)
    m.box("share", (-2, -0.5, 4.5), (2, 1.5, 10), Paint("iron", 2, noise=0, rim=0.6, decal=share), rotation=("x", -22.5, (0, 1.5, 10)))

    def board(c):
        x, y, z = c.p
        if c.face in ("east", "up"):
            return ("steel", 3.4 + (0.6 if (y + z) % 3 < 1 else 0))  # polished by the soil, streaked
        return ("iron", 1.6)
    m.box("mouldboard", (1.5, 1, 7), (2.5, 6.5, 13), Paint("steel", 3, noise=0, rim=0.6, decal=board), rotation=("y", -22.5, (1.5, 1, 7)))
    # the soil turned over to the right, falling off the mouldboard
    soil(m, 3.5, 0, 9, 3, 2.5, 4, 1)
    soil(m, 6, 0, 12, 3, 1.5, 3, 2)
    soil(m, 2.5, 0, 14, 4, 1, 4, 3)
    # 손잡이 (the handle) rising back from the body, its grip across
    rod(m, "handle", (5, 15), (21, 24), 2, wood("wood_dark", "z", 14), x=(-1, 1))
    m.box("grip", (-4, 20.5, 23), (4, 22, 24.5), wood("wood", "x", 15))
    # the sulky's lift: chains from the cross beam to the beam, and the lever by the bench
    chain = Paint("iron", 2, noise=0, decal=lambda c: ("iron", 2.8 if math.floor(c.p[1] * 2) % 2 else 1.2))
    m.box("chain", (-0.5, 7, 4), (0.5, 10, 5), chain)
    rod(m, "lever", (11, -10), (25, -14), 1.5, IRON, x=(9.5, 11))
    m.box("lever_grip", (9.25, 24, -15), (11.25, 27, -13), Paint("leather", 1, noise=0, decal=lambda c: ("leather", 1.8 if c.face == "up" else 1.0)))
    m.box("quadrant", (9.5, 10.5, -12), (10, 15, -8), Paint("iron", 2, noise=0, decal=lambda c: ("steel", 3.6) if math.floor(c.p[2]) % 2 == 0 and c.p[1] > 14 else ("iron", 1.8)))
    m.display = _held_display(m)
    return m


# ================================================================================================
# 경운기 · 로터리 (riding rotary on the tiller's hitch; the trailer's place)
# ================================================================================================

TILLER_TONGUE = -28.5        # the hitch eye (vehicles_farm.yml trailers[0].tongue 1.78 blocks)


def tiller_rotary():
    m = BModel("tiller_rotary", shift=(8, 8, 8), scale=2.0)
    cy, cz, r = 6.5, 0, 6.5
    red = metal(RED, 2.0, 0.8)
    m.group("hitch")
    T = TILLER_TONGUE
    m.box("eye", (-1.25, 6.5, T), (1.25, 8, T + 3), STEEL_BRIGHT)
    for s in (1, -1):
        rod(m, "drawbar", (7.25, T + 3), (8, -17), 1.5, IRON, x=sorted((0.75 * s, 2.25 * s)))
    m.box("drawbar_cross", (-6, 7, -18), (6, 9, -16), IRON)
    m.group("rotor")
    hood(m, 16, cy, cz, r, RED)
    # the chain case down the left side, from the gearbox to the drum's axle
    def case(c):
        x, y, z = c.p
        if c.face == "west" and abs(y - 15) < 0.6:
            return ("steel", 3.4)                                     # a bright bead along it
        return _paint_metal(RED, 1.8, 0.9)(c)
    m.box("chain_case", (-19, 3.5, -4), (-17, 19, 4), Paint(RED, 2, noise=0, rim=0.6, decal=case))
    m.box("bearing", (-17, 4.6, -1.5), (-16.2, 8.4, 1.5), IRON)
    m.box("bearing", (17, 4.6, -1.5), (18, 8.4, 1.5), IRON)
    m.box("gearbox", (-17, 16, -8), (-5, 21, 2), red)
    m.box("input_shaft", (-5, 17.5, -7), (2, 19.5, -5), IRON)
    m.group("seat")
    # the bench for two over the front of the hood (the riders face the tiller, its handles reach to them)
    for s in (1, -1):
        xa, xb = sorted((8 * s, 10 * s))
        m.box("seat_post", (xa, 9, -15), (xb, 15, -13), IRON)
    m.box("seat_frame", (-11, 13.5, -17), (11, 15, -10.5), red)

    def cushion(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 0.5:
                return ("leather", 0.6)
            return ("leather", 2.0 + (0.6 if math.floor(z) % 2 == 0 and abs(abs(x) - 5) < 3 else 0))
        return ("leather", 1.4)
    m.box("cushion", (-10.5, 15, -16.5), (10.5, 16.5, -11), Paint("leather", 2, noise=0, rim=0.5, decal=cushion))
    tube = Paint("steel", 3, noise=0, rim=0.3, decal=lambda c: ("steel", 3.6 if c.face == "up" else 2.4))
    for s in (1, -1):
        xa, xb = sorted((10 * s, 11 * s))
        m.box("rail_post", (xa, 15, -11), (xb, 21, -10), tube)
    m.box("rail", (-11, 21, -11), (11, 22, -10), tube)
    # the depth skids either side and a tool box on the hood
    for s in (1, -1):
        xa, xb = sorted((17.5 * s, 18.5 * s))
        m.box("skid", (xa, 0, -6), (xb, 2, 6), IRON)
    soil(m, -12, 0, 8, 6, 1.5, 4, 4)
    soil(m, 3, 0, 9, 7, 1, 4, 5)
    m.display = _held_display(m)
    return m


def tiller_rotary_tines():
    return tines("tiller_rotary_tines", 15.5, 6.5)


# ================================================================================================
# 트랙터 · 로터리 / 파종기 / 수확기
# ================================================================================================

TRACTOR_REAR = 22            # the transmission's back (drawn z): the three-point hitch hangs here
LINK_Z = 30                  # the lower links' ends


def three_point(m, front_z, low_y=9, top_y=22):
    """The three-point hitch: two lower links from the tractor's lift arms, the top link from the rear of the
    transmission, a lift rod on each side."""
    for s in (1, -1):
        xa, xb = sorted((6 * s, 7.5 * s))
        m.box("lower_link", (xa, low_y - 0.5, LINK_Z - 6), (xb, low_y + 1, front_z), IRON)
        rod(m, "lift_rod", (low_y + 1, LINK_Z - 3), (16, LINK_Z - 7), 1, IRON, x=sorted((6.25 * s, 7.25 * s)))
    rod(m, "top_link", (top_y - 4, TRACTOR_REAR), (top_y, front_z), 2, metal("hazard", 2.0, 0.6), x=(-1, 1))


def tractor_rotary():
    m = BModel("tractor_rotary", shift=(8, 8, 8), scale=2.0)
    cy, cz, r, half = 6.5, 37, 6.5, 24
    m.group("hitch")
    three_point(m, 31, top_y=20)
    # the mast (A-frame) the top link pins to
    for s in (1, -1):
        rod(m, "mast", (15, 32), (21, 31), 1.5, metal(ORANGE, 2.0), x=sorted((1 * s, 5 * s)))
    m.group("rotor")
    hood(m, half, cy, cz, r, ORANGE)
    m.box("gearbox", (-4, 15.5, 31.5), (4, 20, 38), metal("iron", 2.0, 0.8))
    # the PTO shaft from the tractor in its yellow guard
    rod(m, "pto", (10, 23), (17, 32), 2, Paint("petal_yellow", 2, noise=0, rim=0.4,
                                                     decal=lambda c: ("petal_yellow", 1.4 if math.floor(c.p[2]) % 3 == 0 else 2.6)), x=(-1.25, 1.25))

    def case(c):
        if c.face == "east" and abs(c.p[1] - 15) < 0.6:
            return ("steel", 3.4)
        return _paint_metal(ORANGE, 1.8, 0.9)(c)
    m.box("chain_case", (half + 1, 3.5, cz - 4), (half + 3, 17, cz + 4), Paint(ORANGE, 2, noise=0, rim=0.6, decal=case))
    m.box("cross_tube", (-half, 15.5, cz - 2), (half, 17, cz + 0.5), metal("iron", 2.0))
    # decals on the hood: a cream band, warning stripes on the shield's ends
    m.box("band", (-half + 2, 15.6, cz + 1), (half - 2, 15.7, cz + 2.5), Paint("cream", 3, noise=0, light=False))
    for s in (1, -1):
        xa, xb = sorted(((half - 3) * s, (half - 0.5) * s))
        m.box("stripe", (xa, 14.8, cz + 3.4), (xb, 15.3, cz + 3.6),
              Paint("hazard", 2, noise=0, light=False, decal=lambda c: ("hazard", 2.6) if math.floor(c.p[0]) % 2 else ("rubber", 0.6)))
    for s in (1, -1):
        xa, xb = sorted(((half + 1) * s, (half + 2) * s))
        m.box("skid", (xa, 0, cz - 6), (xb, 2.5, cz + 6), IRON)
    soil(m, -18, 0, cz + 5, 7, 1.5, 3, 6)
    soil(m, 5, 0, cz + 5.5, 8, 1, 3, 7)
    m.display = _held_display(m)
    return m


def tractor_rotary_tines():
    return tines("tractor_rotary_tines", 23.5, 6.5)


def tractor_seeder():
    m = BModel("tractor_seeder", shift=(8, 8, 8), scale=2.0)
    half = 24
    m.group("hitch")
    three_point(m, 34, top_y=24)
    for s in (1, -1):
        rod(m, "mast", (9, 35), (25, 34), 1.5, metal("iron", 2.0), x=sorted((1 * s, 5 * s)))
    m.group("frame")
    m.box("toolbar", (-half, 7, 34), (half, 9, 36), metal("iron", 2.0, 0.8))
    m.box("rear_bar", (-half, 7, 42.5), (half, 8.5, 44), metal("iron", 2.0, 0.8))
    for x in (-half + 1, 3, half - 3):
        m.box("frame_arm", (x, 7, 36), (x + 2, 8.5, 42.5), IRON)
    m.group("hopper")
    BLUE = "truck_blue"

    def hopper(c):
        x, y, z = c.p
        if c.face == "north" or c.face == "south":
            if 18 <= y < 21 and abs(x) < 20 and c.face == "south":
                return ("petal_yellow", 2.8 if math.floor(y) == 19 else 2.2)    # a yellow band (no lettering)
            if c.face == "north" and abs(x + 12) < 4 and 17 < y < 22:
                if abs(x + 12) > 3.4 or y < 17.6 or y > 21.4:
                    return ("iron", 2.0)
                return ("corn", 2.6 if (math.floor(x) + math.floor(y * 2)) % 3 else 1.6)   # the window on the seed
            if y < 13:
                return (BLUE, 1.2)
        if c.face in ("east", "west") and abs(c.p[2] - 37) < 0.5:
            return (BLUE, 1.2)
        return _paint_metal(BLUE, 2.0, 0.8)(c)
    m.box("hopper", (-half + 2, 13, 31), (half - 2, 23, 41), Paint(BLUE, 2, noise=0, rim=0.6, decal=hopper))
    m.box("hopper_foot", (-half + 6, 10, 33), (half - 6, 13, 39), metal(BLUE, 1.6))

    def lid(c):
        x, y, z = c.p
        if c.face == "up" and math.floor(z) == 36:
            return ("iron", 1.2)                                      # the lid's hinge line
        if c.face == "north" and abs(x) < 3:
            return ("steel", 3.6)                                     # a latch
        return (BLUE, 2.8 if c.face == "up" else 2.0)
    m.box("lid", (-half + 1.5, 23, 30.5), (half - 1.5, 24.5, 41.5), Paint(BLUE, 2, noise=0, rim=0.6, decal=lid))
    m.box("walkboard", (-half + 4, 10.5, 41), (half - 4, 11.5, 42.5), Paint("iron", 2, noise=0, decal=lambda c: ("steel", 3) if c.face == "up" and (math.floor(c.p[0]) + math.floor(c.p[2])) % 2 == 0 else ("iron", 1.8)))
    m.group("rows")
    disc = Paint("steel", 3, noise=0, rim=0.4, decal=lambda c: ("steel", 4.0 if math.hypot(c.p[1] - 4, c.p[2] - 38) < 1 else 3.2) if c.face in ("east", "west") else ("iron", 1.6))
    hose = Paint("rubber", 1, noise=0, decal=lambda c: ("rubber", 1.6 if math.floor(c.p[1]) % 2 else 0.8))
    for x in (-16, 0, 16):
        cyl(m, "coulter", (4, 38), 8, x - 0.5, x + 0.5, disc, axis="x")
        m.box("shank", (x - 1, 6, 36), (x + 1, 10, 38), IRON)
        rod(m, "seed_hose", (10, 37), (4.5, 40), 1, hose, x=(x + 1, x + 2))
        soil(m, x - 1.5, 0, 41, 3, 0.75, 3, 8 + int(x))
    m.group("markers")
    for s in (1, -1):
        xa, xb = sorted(((half - 1) * s, (half + 1) * s))
        m.box("marker_arm", (xa, 8, 39), (xb, 26, 40.5), metal("hazard", 2.0, 0.6))
        cyl(m, "marker_disc", (27, 39.75), 5, min(xa, xb) - 0.25, max(xa, xb) + 0.25, disc, axis="x")
    m.display = _held_display(m)
    return m


def tractor_seeder_roller():
    """The press roller behind the drill: a ribbed steel drum (rolls on the ground: its axle at (0, 0, 0))."""
    m = BModel("tractor_seeder_roller", shift=(8, 8, 8), floor=None)

    def drum(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            return ("iron", 2.6 if math.hypot(y, z) < 1 else 1.8)
        a = math.atan2(z, y)
        return ("steel", 3.6 if (a * 8 / math.pi) % 2 < 1 else 2.4)   # ribs round it
    cyl(m, "roller", (0, 0), 7, -23, 23, Paint("steel", 3, noise=0, rim=0.2, ao=0.2, decal=drum), axis="x")
    m.box("axle", (-24, -0.75, -0.75), (24, 0.75, 0.75), IRON)
    m.display = presets.furniture(gui_scale=0.4)
    return m


HEADER_FRONT = -44           # the cutter bar (drawn z)


def tractor_header():
    m = BModel("tractor_header", shift=(8, 8, 8), scale=2.0)
    half, F = 24, HEADER_FRONT
    m.group("mount")
    # two arms from the tractor's front axle beam forward to the header's back
    for s in (1, -1):
        rod(m, "arm", (6, -17), (9, -33), 2, metal("iron", 2.0, 0.8), x=sorted((4 * s, 6 * s)))
    m.box("cylinder", (-1, 10, -30), (1, 14, -24), STEEL_BRIGHT)
    m.group("pan")

    def pan(c):
        x, y, z = c.p
        if c.face == "up":
            return ("steel", 3.0 + (0.5 if math.floor(x) % 4 == 0 else 0))   # the pan's seams
        return _paint_metal(ORANGE, 2.0, 0.8)(c)
    m.box("pan", (-half, 1, F + 1), (half, 2.5, -33), Paint("steel", 3, noise=0, rim=0.5, decal=pan))

    def back(c):
        x, y, z = c.p
        if c.face == "north" and 4 < y < 10 and abs(x) < 6:
            return ("rubber", 0.6 if abs(x) < 5 else 1.2)            # the feeder's mouth
        if c.face == "north" and math.floor(y) == 11:
            return ("cream", 3)
        return _paint_metal(ORANGE, 2.0, 0.8)(c)
    m.box("back_wall", (-half, 2.5, -34), (half, 13, -32.5), Paint(ORANGE, 2, noise=0, rim=0.6, decal=back))
    m.box("top_beam", (-half, 13, -35), (half, 14.5, -32), metal(ORANGE, 2.2))

    def bar(c):
        x, y, z = c.p
        if c.face in ("north", "up") and (math.floor(x) % 3 == 0):
            return ("steel", 4.4)                                     # the guards' points
        return ("iron", 2.4 if c.face == "up" else 1.4)
    m.box("cutter_bar", (-half, 0.9, F), (half, 2.6, F + 1.4), Paint("iron", 2, noise=0, rim=0.5, decal=bar))
    for s in (1, -1):
        xa, xb = sorted(((half - 0.5) * s, (half + 1) * s))
        m.box("end_plate", (xa, 0.8, F + 0.1), (xb, 12, -33.2), Paint(ORANGE, 2, noise=0, rim=0.6, decal=_paint_metal(ORANGE, 2.2, 0.8)))
        # the pointed crop divider in front of each end
        m.box("divider", (xa, 1, F - 4), (xb, 4, F), metal("hazard", 2.2, 0.6), rotation=("x", -22.5, (0, 1, F)))
    m.group("auger")

    def auger(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            return ("iron", 2.0)
        return ("steel", 3.8) if (x + math.atan2(z + 37, y - 5) * 3) % 6 < 1.5 else ("iron", 2.2)   # the flights' spiral
    cyl(m, "auger", (5, -37), 5, -half + 1, half - 1, Paint("iron", 2, noise=0, rim=0.2, decal=auger), axis="x")
    m.group("reel_arms")
    for s in (1, -1):
        rod(m, "reel_arm", (14, -33), (16, -39.5), 1.5, metal(ORANGE, 2.2), x=sorted(((half - 1) * s, (half + 0.5) * s)))
    m.display = _held_display(m)
    return m


def tractor_header_reel():
    """The header's reel: six bats across it with spring tines hanging from them, on spiders at the ends and the middle
    (axle along x at (0, 0, 0), 15 px across)."""
    m = BModel("tractor_header_reel", shift=(8, 8, 8), floor=None)
    half, r = 22.5, 6.5
    m.box("axle", (-half - 0.5, -0.75, -0.75), (half + 0.5, 0.75, 0.75), IRON)
    spider = metal(ORANGE, 2.2)
    for x in (-half, -0.5, half - 1):
        m.box("spoke", (x, -r, -0.5), (x + 1, r, 0.5), spider)
        m.box("spoke", (x, -0.5, -r), (x + 1, 0.5, r), spider)
    bat = Paint("cream", 2, noise=0, rim=0.4, decal=lambda c: ("cream", 3.0 if c.face in ("up", "north") else 2.0))
    tine = Paint("steel", 3, noise=0, light=False, decal=lambda c: raw("steel", 3) if math.floor(c.p[0]) % 3 == 0 else ("raw", CLEAR))
    for ang in (0, 90, 180, 270):
        a = math.radians(ang)
        y, z = round(r * math.cos(a), 6), round(r * math.sin(a), 6)
        m.box("bat", (-half + 0.25, y - 0.75, z - 0.75), (half - 0.25, y + 0.75, z + 0.75), bat)
        # the tines: a cut-out plane hanging back from the bat
        dy, dz = round(-math.sin(a) * 3, 6), round(math.cos(a) * 3, 6)
        m.box("tines", (-half + 1.5, min(y, y + dy) - 0.01, min(z, z + dz) - 0.25), (half - 1.5, max(y, y + dy) + 0.01, max(z, z + dz) + 0.25), tine)
    for ang in (45, 135, 225, 315):
        a = math.radians(ang)
        y, z = round(r * math.cos(a), 6), round(r * math.sin(a), 6)
        m.box("bat", (-half + 0.25, y - 0.75, z - 0.75), (half - 0.25, y + 0.75, z + 0.75), bat, rotation=("x", 45, (0, y, z)))
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================
# 통통배 · 그물 (stern trawl)
# ================================================================================================

STERN = 38                   # the transom (drawn z: models/vehicles_misc.py)
WATER = 6                    # the waterline
GALLOWS_Z = 36.5


def boat_trawl():
    m = BModel("boat_trawl", shift=(8, 8, 8), scale=3.0)
    m.group("gallows")
    frame = Paint("signal_red", 2, noise=0, rim=0.6, decal=_paint_metal("signal_red", 1.6, 0.9))
    for s in (1, -1):
        xa, xb = sorted((11 * s, 12.5 * s))
        m.box("post", (xa, 13.5, GALLOWS_Z - 1), (xb, 34, GALLOWS_Z + 0.5), frame)
        m.box("foot", (min(xa, xb) - 0.5, 13, GALLOWS_Z - 2), (max(xa, xb) + 0.5, 14, GALLOWS_Z + 1), IRON)
        xa2, xb2 = sorted((8.5 * s, 9.5 * s))
        m.box("bearing", (xa2, 26, GALLOWS_Z - 1.5), (xb2, 29, GALLOWS_Z + 1), IRON)
        # a block (pulley) hanging at the top for the warp
        m.box("block", (sorted((10 * s, 12 * s))[0], 31, GALLOWS_Z + 0.5), (sorted((10 * s, 12 * s))[1], 33, GALLOWS_Z + 2), STEEL_BRIGHT)
    m.box("crossbar", (-12.5, 34, GALLOWS_Z - 1), (12.5, 35.5, GALLOWS_Z + 0.5), frame)
    m.box("light", (-1, 35.5, GALLOWS_Z - 1), (1, 37, GALLOWS_Z + 0.5), Paint("lamp", 3, noise=0, light=False, glow=True))
    m.group("warps")
    warp = Paint("iron", 2, noise=0, decal=lambda c: ("iron", 2.6 if math.floor(c.p[2] + c.p[1]) % 2 else 1.6))
    for s in (1, -1):
        x = 11 * s
        rod(m, "warp", (31, GALLOWS_Z + 1), (6, 58), 0.5, warp, x=sorted((x - 0.25 * s, x + 0.25 * s)))
    m.group("doors")

    def door(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            if abs(y - 3.5) < 0.5 or c.nu <= 1:
                return ("iron", 2.8)
            return ("hazard", 2.4) if (math.floor(z) // 2) % 2 else ("rubber", 1.0)
        return ("iron", 1.6)
    for s in (1, -1):
        xa, xb = sorted((11.5 * s, 12.5 * s))
        m.box("otter_board", (xa, 1, 56), (xb, 6.5, 62), Paint("iron", 2, noise=0, rim=0.5, decal=door), rotation=("y", 22.5 * s, (11.5 * s, 1, 56)))
    m.group("net")
    # the float line on the surface (orange buoys) and the green net under it, narrowing to the cod end
    float_p = Paint("fox", 3, noise=0, rim=0.6, decal=lambda c: ("fox", 3.4 if c.face in ("up", "north") else 2.4))
    m.box("headrope", (-10, 5.75, 60), (10, 6.25, 60.5), Paint("burlap", 2, noise=0))
    for x in (-9, -5, -1, 3, 7):
        m.box("float", (x, 5, 59.25), (x + 2, 7.5, 61.25), float_p)
    net = mesh(2, ramp="leaf_dark", lit=2.4, edge=("leaf_dark", 1.4), frame=True)
    m.box("net", (-10, 0, 60.5), (10, 0.4, 66), net)
    m.box("net", (-10, 0.4, 60.5), (-9.6, 5.5, 66), net)
    m.box("net", (9.6, 0.4, 60.5), (10, 5.5, 66), net)
    m.box("net", (-7, 1, 66), (7, 1.4, 70), net)
    m.box("net", (-7, 1.4, 66), (-6.6, 4.5, 70), net)
    m.box("net", (6.6, 1.4, 66), (7, 4.5, 70), net)
    m.box("net", (-6.6, 4.5, 66), (6.6, 4.9, 70), net)

    def cod(c):
        k = h3(*c.i, 97)
        return ("leaf_dark", 1.2 + (0.8 if k > 0.6 else 0) + (0.5 if c.face == "up" else 0))
    m.box("cod_end", (-4, 1.5, 69.5), (4, 4.5, 71.5), Paint("leaf_dark", 1, noise=0, rim=0.3, decal=cod))
    m.box("cod_rope", (-4.25, 2.5, 70.75), (4.25, 3.5, 71.75), Paint("fox", 2, noise=0))
    m.display = _held_display(m)
    return m


def boat_net_drum():
    """The net drum on the gallows: flanged ends, the green net wound on it with a few orange floats in its turns
    (axle along x at (0, 0, 0))."""
    m = BModel("boat_net_drum", shift=(8, 8, 8), floor=None)

    def wound(c):
        x, y, z = c.p
        if c.face in ("east", "west"):
            return ("leaf_dark", 1.2)
        a = math.atan2(z, y)
        if (math.floor(x) % 5 == 0) and (a * 4 / math.pi) % 2 < 0.6:
            return ("fox", 3.0)                                       # a float caught in the turns
        return ("leaf_dark", 2.2 + (0.6 if (math.floor(x) + math.floor(a * 6)) % 2 else 0))
    cyl(m, "net", (0, 0), 7, -8, 8, Paint("leaf_dark", 2, noise=0, rim=0.2, decal=wound), axis="x")
    flange = Paint("signal_red", 2, noise=0, rim=0.3, decal=lambda c: ("signal_red", 2.6) if c.face in ("east", "west") and math.hypot(c.p[1], c.p[2]) > 3 else ("iron", 2.0))
    for x in (-9, 8):
        cyl(m, "flange", (0, 0), 10, x, x + 1, flange, axis="x")
    m.box("axle", (-9.5, -0.75, -0.75), (9.5, 0.75, 0.75), IRON)
    m.display = presets.furniture(gui_scale=0.5)
    return m


# ================================================================================================
# 1톤 트럭 · 방수포 덮개 (a canvas top on bows)
# ================================================================================================

# the bed (models/vehicles.py TRUCK): drawn from its front edge, z' = z - bed_front
BED_LEN, BED_HALF, WALL_TOP, BOW_TOP = 49.5, 14.75, 18.5, 35.5


def truck_tarp():
    m = BModel("truck_tarp", shift=(8, 8, 8), scale=2.5)
    L, H, W0, T = BED_LEN, BED_HALF, WALL_TOP, BOW_TOP
    SIDE_TOP = 31
    CANVAS = "bottle_green"

    def canvas(c):
        x, y, z = c.p
        fz = math.floor(z)
        if c.face == "down":
            return (CANVAS, 0.6)
        if c.face in ("east", "west"):
            if y < W0 + 2:
                # the hem with its eyelets and the rope zig-zag down to the hooks on the drop side
                if fz % 6 in (0, 1) and y < W0 + 1:
                    return ("steel", 3.6) if fz % 6 == 0 else (CANVAS, 1.2)
                return ("burlap", 2.4) if abs(((z % 6) / 6) * 2 - (y - W0)) < 0.45 else (CANVAS, 1.4)
            if fz % 12 in (0, 11):
                return (CANVAS, 1.4)                                  # a seam over each bow
            sag = 0.4 * math.sin(math.pi * ((z % 12) / 12))
            return (CANVAS, lerp(y, W0, SIDE_TOP, 1.8, 2.6) - sag + (0.3 if h3(fz, math.floor(y), 1, 91) < 0.05 else 0))
        if c.face == "up":
            if fz % 12 in (0, 11):
                return (CANVAS, 2.2)
            return (CANVAS, 3.0 - 0.3 * math.sin(math.pi * ((z % 12) / 12)) + (0.4 if h3(math.floor(x), fz, 2, 92) < 0.04 else 0))
        if c.face == "north":
            return (CANVAS, 2.0)
        return (CANVAS, 1.6)
    p = Paint(CANVAS, 2, noise=0, rim=0.5, ao=0.6, decal=canvas)
    m.group("canvas")
    for s in (1, -1):
        xa, xb = sorted((H * s, (H + 0.5) * s))
        m.box("side", (xa, W0 - 1, 0), (xb, SIDE_TOP, L), p)
        # the shoulder: from the side's top in to the roof at 45°
        m.box("shoulder", (min(H * s, (H - 0.5) * s), SIDE_TOP, 0), (max(H * s, (H - 0.5) * s), SIDE_TOP + 6.4, L), p,
              rotation=("z", 45 * s, (H * s, SIDE_TOP, 0)))
    m.box("roof", (-H + 4.5, T, 0), (H - 4.5, T + 0.5, L), p)
    m.box("front", (-H, W0 - 1, 0), (H, T, 0.5), p)
    m.group("bows")
    bow = Paint("iron", 2, noise=0, decal=lambda c: ("steel", 3.2 if c.face == "up" else 2.2))
    for z in (0.5, 12.5, 24.5, 36.5, L - 1):
        for s in (1, -1):
            xa, xb = sorted(((H - 0.5) * s, (H - 1) * s))
            m.box("bow", (xa, W0 - 2, z), (xb, SIDE_TOP, z + 1), bow)
        m.box("bow_top", (-H + 4.5, T - 1, z), (H - 4.5, T - 0.5, z + 1), bow)
    m.group("rear")
    # the rear flap rolled up under the roof's back edge, tied with two straps
    roll = Paint(CANVAS, 2, noise=0, rim=0.3, decal=lambda c: (CANVAS, 2.6 if c.face in ("up", "south") else 1.6))
    m.box("rolled_flap", (-H + 1, T - 3, L - 1.5), (H - 1, T - 0.6, L + 1), roll)
    for x in (-8, 7):
        m.box("strap", (x, T - 3.25, L - 1.75), (x + 1, T - 0.25, L + 1.25), Paint("burlap", 2, noise=0, decal=lambda c: ("burlap", 2.4)))
    m.display = _held_display(m)
    return m


# ================================================================================================
# 탑차 · 셔터 (the box truck's rear roller door)
# ================================================================================================

# the doorway in models/vehicles_road.py box_truck (TRUCK, drawn px at real size): x within the frame, from the bottom rail up
# to the top rail, the box's back face at z = back; drawn from the doorway's top middle on the back face
DOOR_HALF, DOOR_TOP, DOOR_BOTTOM = 15, 46, 18


def box_truck_shutter():
    m = BModel("box_truck_shutter", shift=(8, 8, 8), scale=2.5, floor=None)
    H, h = DOOR_HALF, DOOR_TOP - DOOR_BOTTOM

    def slats(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face != "south":
            return ("steel", 2.4)
        if y > -1.5:
            return ("iron", 1.6 if fy == -1 else 2.4)                # the roll's housing lip
        if y < -h + 2:
            if abs(x) < 6 and math.floor(y) == -h + 1:
                return ("blade", 3.6)                                 # the lift bar
            return ("iron", 2.6)
        if abs(x) < 1.5 and -10 <= fy <= -8:
            return ("blade", 3) if fy == -9 else ("iron", 1)          # the lock
        wear = h3(math.floor(x / 2), fy, 0, 93) < 0.05
        return ("steel", (4.4 if fy % 2 else 2.6) - (0.6 if wear else 0))
    m.box("slats", (-H, -h, 0.3), (H, 0, 0.8), Paint("steel", 3, noise=0, rim=0.3, decal=slats))
    m.box("handle", (-3, -h + 1, 0.8), (3, -h + 2, 1.5), STEEL_BRIGHT)
    m.display = _held_display(m)
    return m


def box_truck_doorway():
    """The open doorway: the box's inside as seen through it (a flat picture: the far wall, the floor's checker plate
    running in, ribbed walls, a dome light, a few parcels), behind the slats."""
    m = BModel("box_truck_doorway", shift=(8, 8, 8), scale=2.5, floor=None)
    H, h = DOOR_HALF, DOOR_TOP - DOOR_BOTTOM

    # the far wall in the middle (a box seen from behind, in perspective): its rectangle, the floor, ceiling and side walls
    # running from the doorway's edges in to it
    FX, FT, FB = H * 0.5, -h * 0.18, -h * 0.74            # the far wall: half width, top, bottom (y from the doorway's top)

    def inside(c):
        x, y, z = c.p
        if c.face != "south":
            return raw("rubber", 1)
        ax, fx, fy = abs(x), math.floor(x), math.floor(y)
        # how far in a point of each surface lies (0 at the doorway, 1 at the far wall)
        t_floor = (-h - y) / (-h - FB) if y < FB else None
        t_ceil = y / FT if y > FT else None
        t_side = (H - ax) / (H - FX) if ax > FX else None
        if t_floor is not None and (t_side is None or t_floor < t_side):
            # the floor: an aluminium checker plate whose diamonds shrink towards the far wall
            k = max(1, round(3 - 2 * t_floor))
            return raw("steel", 3.6 if (fx // k + fy // k) % 2 else 2.6)
        if t_ceil is not None and (t_side is None or t_ceil < t_side):
            if ax < 2.5 and t_ceil > 0.45 and t_ceil < 0.75:
                return raw("lamp", 4)                                 # the dome light
            return raw("iron", 1.6 + 0.8 * t_ceil)
        if t_side is not None:
            # the side walls: plywood lining with a dark rib every few pixels, rails for straps
            if fy == math.floor(-h * 0.45):
                return raw("iron", 2.6)
            return raw("wood", 1.0 + 0.8 * t_side) if (fx % 4) else raw("wood_dark", 0.6)
        # the far wall: dark, parcels stacked against it
        if y < FB + 7 and -8 < x < 1:
            return raw("bean", 1.4 if fx in (-8, -1) or fy == math.floor(FB + 6) else 2.6)
        if y < FB + 4 and 2 < x < 7:
            return raw("bean", 1.2 if fx in (2, 6) or fy == math.floor(FB + 3) else 2.2)
        return raw("iron", 0.6)
    m.box("inside", (-H, -h, 0.05), (H, 0, 0.2), Paint("iron", 2, noise=0, light=False, decal=inside))
    m.display = _held_display(m)
    return m


# ================================================================================================

ALL = {
    "ox_plough": ox_plough,
    "tiller_rotary": tiller_rotary,
    "tiller_rotary_tines": tiller_rotary_tines,
    "tractor_rotary": tractor_rotary,
    "tractor_rotary_tines": tractor_rotary_tines,
    "tractor_seeder": tractor_seeder,
    "tractor_seeder_roller": tractor_seeder_roller,
    "tractor_header": tractor_header,
    "tractor_header_reel": tractor_header_reel,
    "boat_trawl": boat_trawl,
    "boat_net_drum": boat_net_drum,
    "truck_tarp": truck_tarp,
    "box_truck_shutter": box_truck_shutter,
    "box_truck_doorway": box_truck_doorway,
}
