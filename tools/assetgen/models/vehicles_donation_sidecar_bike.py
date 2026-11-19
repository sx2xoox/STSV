"""
The donation vehicle 사이드카 오토바이 (sidecar_bike): a classic touring motorcycle with a bullet-shaped sidecar on
its RIGHT side. An original design: no real make, no badge, no signature shape copied (an upright parallel twin,
not a boxer).

The same rules as models/vehicles.py and models/vehicles_premium.py (read their docs first): the body drawn at its
real size (16 px a block, shrunk by 2, shown at vehicle.body-scale 2), its wheel a model of its own (wheel_sidecar,
all three wheels). Drawn coordinates: the vehicle's ground centre at (0, 0, 0), front = north (-z), +x = the
vehicle's RIGHT side. The bike runs along x = BX (left of the middle), the sidecar's tub along x = SX (right of it):
the pair is centred so the item file's collision box (centred on the vehicle) covers both.

The bike: a round chrome headlamp with a speedometer on top, a telescopic fork (chrome sliders, painted shrouds), a
deep valanced front fender, wide flat handlebars with black grips and one round mirror, a teardrop tank (the paint,
a cream stripe over its crown, a cream panel and rubber knee pads each side, a chrome filler cap), an upright
air-cooled twin (finned barrels, a polished crankcase with a round cover, a gearbox behind), a chrome exhaust each
side ending in long mufflers, a sprung leather solo saddle on two chrome coil springs, twin rear shocks, a valanced
rear fender with the tail lamp and the plate, a leather saddlebag on the left.

The sidecar: a bullet tub (a rounded nose and tail in steps, the paint with a cream spear along each side, a chrome
rim round the open cockpit), a little windscreen leaning back, a leather seat and seat back, a small running lamp
on the nose, a red lamp on the tail, a spare wheel standing on the tail, its own wheel outboard under a mudguard,
two chassis tubes to the bike.

Riders: the driver astride the saddle (seat 0, the delivery scooter's riding height over the saddle: 2.2 px), the
passenger low in the tub (seat 1). Its paint is an argument (colour: the body; stripe: the stripe and the panels;
leather: the saddle, the seat and the bag), so a tuning colour is one more call. No moving parts.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets import presets
from models.vehicles import CLEAR, lerp, lamp, plate, glass, raw, upholstery, _held_display
from models.vehicles_premium import Kit
from models.vehicles_road import rod, tube

BX = -6.5            # the bike's centre line
SX = 7.0             # the sidecar tub's centre line
WR = 6.0             # wheel radius (hub height)
ZF, ZR = -13.0, 13.0     # the bike's front and rear axles
SWX, SWZ = 14.25, 5.0    # the sidecar wheel (x, z)
SADDLE = 15.75       # the saddle's top (seat 0 sits 2.2 px over it, like the delivery scooter)


def _arc(m, name, centre, r, angles, toward, x, paint, depth=0.75):
    """A fender: an arc of straight pieces round centre (y, z) at radius r, from angle to angle (degrees from the
    top, towards `toward` = -1 the front, +1 the back), x = (x0, x1) across, each piece overlapping the next a little
    (models/vehicles_road._chords, off the centre line)."""
    pts = [(centre[0] + r * math.cos(math.radians(d)), centre[1] + toward * r * math.sin(math.radians(d))) for d in angles]
    for a, b in zip(pts, pts[1:]):
        dy, dz = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dy, dz)
        rod(m, name, (a[0] - dy / n * 0.5, a[1] - dz / n * 0.5), b, x[1] - x[0], paint, x=x, depth=depth)


# ================================================================================================
# The wheel: 12 px across, a thin black tyre 2 px wide, a chrome rim, eight wire spokes, a drum hub
# ================================================================================================

def wheel_sidecar():
    """The sidecar bike's wheel (all three): 0.75 block across (12 px). A thin black tyre (a ring 1.5 px deep and
    2 px wide: a ribbed tread, its wall lit on the upper front), a bright chrome rim on the spokes' plane, eight
    chrome wire spokes on a see-through wheel, a polished drum hub with a brake plate each side."""
    m = BModel("wheel_sidecar", shift=(8, 8, 8), floor=None)
    d, w2, R_IN = 12, 1.0, 4.5

    def tyre(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        lit = y - 0.5 * z
        if c.face in ("east", "west"):
            if rr > 5.3:
                return ("rubber", 1.2 if lit > 3 else 0.8 if lit > -3 else 0.4)
            if rr < 4.85:
                return ("rubber", 0.6)
            return ("rubber", 2.4 if lit > 2.5 else 1.7 if lit > -2.5 else 1.0)
        if rr < R_IN + 0.3:
            return ("blade", 2.6 if lit > 0 else 1.6)       # the rim bed
        # a ribbed road tread: three ribs round it, fine sipes across
        a = math.atan2(z, y)
        k = math.floor((a + math.pi) / (2 * math.pi) * 30)
        x = c.p[0]
        if abs(x) < 0.25:
            return ("rubber", 0.2)
        return ("rubber", 2.0 if k % 3 else 1.0)
    tp = Paint("rubber", 1, noise=0, rim=0.2, ao=0.2, decal=tyre)
    for sy in (1, -1):
        m.box("tyre", (-w2, min(sy * R_IN, sy * 6), -3), (w2, max(sy * R_IN, sy * 6), 3), tp)
        m.box("tyre", (-w2, -3, min(sy * R_IN, sy * 6)), (w2, 3, max(sy * R_IN, sy * 6)), tp)
        for sz in (1, -1):
            m.box("tyre", (-w2, min(sy * 3, sy * 5), min(sz * 3, sz * 5)), (w2, max(sy * 3, sy * 5), max(sz * 3, sz * 5)), tp)

    def spokes(c):
        y, z = c.p[1], c.p[2]
        r = math.hypot(y, z)
        if r > R_IN + 0.05:
            return ("raw", CLEAR)
        if c.face not in ("east", "west"):
            return raw("blade", 2) if r > R_IN - 1 else ("raw", CLEAR)
        lit = y - 0.5 * z
        if r > R_IN - 1.0:
            return raw("blade", 4 if lit > 1.5 else 3 if lit > -2 else 2)     # the chrome rim, a glint on its upper front
        if r < 1.2:
            return raw("steel", 4)
        if abs(y - z) < 0.3 or abs(y + z) < 0.3 or abs(y - 0.5) < 0.3 or abs(z - 0.5) < 0.3:
            return raw("steel", 4 if lit > 0 else 3)                          # wire spokes (darker than the rim: at one
                                                                              # texel each they would fill the wheel in)
        return ("raw", CLEAR)
    m.box("disc", (-0.25, -R_IN, -R_IN), (0.25, R_IN, R_IN), Paint("blade", 3, noise=0, light=False, decal=spokes))

    def hub(c):
        y, z = c.p[1], c.p[2]
        if c.face in ("east", "west"):
            r = math.hypot(y, z)
            if r < 0.6:
                return ("steel", 2)                                           # the axle nut
            return ("blade", 3.6 if (y > 0 and z < 0) else 2.6)               # the brake plate, lit on its upper front
        return ("blade", 3.2 if c.face == "up" else 2.2)
    hp = Paint("blade", 2, noise=0, rim=0, ao=0, decal=hub)
    m.box("hub", (-1.25, -1.0, -1.0), (1.25, 1.0, 1.0), hp)
    m.box("hub", (1.25, -1.25, -1.25), (1.5, 1.25, 1.25), hp)
    m.box("hub", (-1.5, -1.25, -1.25), (-1.25, 1.25, 1.25), hp)
    m.display = presets.furniture(gui_scale=0.6)
    return m


# ================================================================================================
# 사이드카 오토바이 2.3 x 1.8 x 1.6 blocks
# ================================================================================================

def sidecar_bike(colour="wine", stripe="cream", leather="leather", name="sidecar_bike"):
    return sidecar_kit(colour, stripe, leather, name).body


def sidecar_kit(colour="wine", stripe="cream", leather="leather", name="sidecar_bike"):
    """colour: the tank, fenders, fork shrouds and the tub (default a deep burgundy; "cucumber" an olive one);
    stripe: the stripe over the tank and fenders, the tank panels, the spear along the tub; leather: the saddle,
    the sidecar's seat and the saddlebag."""
    kit = Kit(name, ())
    m = kit.body
    C, S, LT = colour, stripe, leather
    FRAME = "blacksteel"

    def col(y, lo=3.0, hi=19.0, a=1.0, b=2.7):
        return (C, lerp(y, lo, hi, a, b))

    # --- paints ---------------------------------------------------------------------------------
    def painted(c):
        """plain body paint, lit towards the top; a cream stripe along the top of a part on the bike's centre line"""
        x = c.p[0]
        if c.face == "up":
            return (S, 3.2) if abs(x - BX) < 0.75 else (C, 2.9)
        if c.face == "down":
            return (C, 0.5)
        return col(c.p[1])
    PAINT = Paint(C, 2, noise=0, grad=0, rim=0.6, decal=painted)
    FRAME_T = tube(FRAME, 3.2, 1.2, 2)
    CHROME_T = tube("blade", 3.6, 2.0, 3)
    LEATHER = Paint(LT, 2, noise=0, rim=0.5)

    # ================================================================================================
    # the bike
    # ================================================================================================
    m.group("front")
    # telescopic fork: chrome sliders from the axle, painted shrouds above, to the yokes under the headlamp
    for s in (1, -1):
        xa, xb = sorted((BX + s * 1.25, BX + s * 2.5))
        rod(m, "fork_slider", (WR, ZF), (WR + 7.5, ZF + 3.0), 1.25, CHROME_T, x=(xa, xb))
        rod(m, "fork_shroud", (WR + 7.0, ZF + 2.9), (WR + 13.5, ZF + 5.6), 1.5, PAINT, x=(xa, xb))
    m.box("yoke", (BX - 3, 19.5, -8.5), (BX + 3, 20.5, -6.25), Paint(FRAME, 3, noise=0, rim=0.6))

    # the deep valanced front fender, the stripe over its crown; its stays to the sliders
    _arc(m, "front_fender", (WR, ZF), WR + 1.25, (-55, -20, 20, 55, 80), -1, (BX - 1.75, BX + 1.75), PAINT)
    for s in (1, -1):
        xa, xb = sorted((BX + s * 1.75, BX + s * 2.25))
        m.box("fender_stay", (xa, WR + 2, ZF - 4.5), (xb, WR + 2.5, ZF + 1), CHROME_T)

    # the headlamp: a round chrome bucket, its lens a glowing disc in a chrome ring; a speedometer on top
    HY, HZ = 17.25, -11.0

    def bucket(c):
        if c.face == "north":
            return ("blade", 3.4)
        if c.face == "up":
            return ("blade", 4)
        if c.face == "south":
            return ("blade", 1.6)
        return ("blade", 3.2 if c.p[1] > HY else 2.2)
    cyl(m, "headlamp", (BX, HY), 6, HZ, HZ + 3.5, Paint("blade", 3, noise=0, rim=0.4, decal=bucket), axis="z")

    def lens(c):
        r = math.hypot(c.p[0] - BX, c.p[1] - HY)
        if c.face != "north" or r > 2.45:
            return ("raw", CLEAR) if c.face == "north" else raw("blade", 3)
        if r > 1.9:
            return raw("lamp", 2)                                     # the reflector's rim
        return raw("lamp", 3)
    m.box("headlamp_lens", (BX - 2.5, HY - 2.5, HZ - 0.25), (BX + 2.5, HY + 2.5, HZ), Paint("lamp", 2, decal=lens, **lamp()))

    def speedo(c):
        if c.face == "up":
            return ("dial", 1 if c.u == c.nu - 1 and c.v == 0 else 0)
        return ("blade", 3)
    m.box("speedo", (BX - 1, HY + 3, -10.5), (BX + 1, HY + 3.75, -8.5), Paint("blade", 3, noise=0, decal=speedo))

    # wide flat handlebars on a riser: chrome, black grips, a lever ahead of each grip, one round mirror on the left
    m.box("riser", (BX - 0.75, 20.5, -8.0), (BX + 0.75, 21.75, -6.75), CHROME_T)
    m.box("handlebar", (BX - 6.5, 21.25, -7.75), (BX + 6.5, 22.0, -7.0), CHROME_T)
    grip = Paint("rubber", 1, noise=0, decal=lambda c: ("rubber", 2.4 if c.face == "up" else 1.2 if math.floor(c.p[0] * 2) % 2 else 0.6))
    for s in (1, -1):
        xa, xb = sorted((BX + s * 6.25, BX + s * 9.0))
        m.box("grip", (xa, 21.1, -7.85), (xb, 22.15, -6.9), grip)
        xa, xb = sorted((BX + s * 5.5, BX + s * 8.5))
        m.box("lever", (xa, 20.75, -9.25), (xb, 21.25, -8.5), CHROME_T)
    rod(m, "mirror_stalk", (22.0, -7.25), (25.5, -6.0), 0.5, CHROME_T, x=(BX - 5.75, BX - 5.25))

    def mirror(c):
        corner = (c.u in (0, c.nu - 1)) and (c.v in (0, c.nv - 1))
        if c.face == "south":
            if corner:
                return ("blade", 1.6)
            inner = 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1
            return ("glass", 3.4 if c.u == 1 and c.v == 1 else 2.4) if inner else ("blade", 3)
        return ("blade", 3.4 if c.face in ("north", "up", "west") else 2.2)
    m.box("mirror", (BX - 7, 25.0, -6.25), (BX - 4, 27.5, -5.5), Paint("blade", 2, noise=0, rim=0.5, decal=mirror))

    m.group("tank")

    def tank(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x - BX) < 0.75:
                return (S, 3.3)                                      # the stripe over the crown
            if abs(x - BX) < 1.25:
                return (S, 1.6)                                      # its fine edge line
            return (C, 3.0)
        if c.face == "down":
            return (C, 0.5)
        if c.face in ("east", "west"):
            # a cream teardrop panel in a thin dark line, a ribbed rubber knee pad behind it
            u, v = (z + 4.25) / 2.6, (y - 17.25) / 1.4
            e = u * u + v * v
            if -2.0 <= z < 0.0 and 15.0 <= y < 17.5:
                return ("rubber", 2.2 if math.floor(y * 2) % 2 else 1.2)
            if e < 0.75:
                return (S, 2.9 if y > 17.2 else 2.3)
            if e < 1.05:
                return (C, 0.6)
            return col(y, 14, 20, 1.2, 2.8)
        return col(y, 14, 20, 1.2, 2.8)
    tp = Paint(C, 2, noise=0, grad=0, rim=0.7, decal=tank)
    m.box("tank", (BX - 3.25, 14.5, -6.5), (BX + 3.25, 19.5, -2.0), tp)
    m.box("tank_nose", (BX - 2.5, 15.0, -8.0), (BX + 2.5, 19.0, -6.5), tp)
    m.box("tank_tail", (BX - 2.25, 14.75, -2.0), (BX + 2.25, 18.25, 0.0), tp)
    m.box("tank_crown", (BX - 2.25, 19.5, -6.25), (BX + 2.25, 20.25, -2.5), tp)
    m.box("filler_cap", (BX - 1.75, 20.25, -5.25), (BX - 0.75, 20.75, -4.25), Paint("blade", 3, noise=0, rim=0.3))

    m.group("frame")
    rod(m, "backbone", (17.5, -7.0), (14.0, 4.0), 1.5, FRAME_T, x=(BX - 0.75, BX + 0.75))
    rod(m, "down_tube", (17.0, -8.0), (4.5, -6.0), 1.5, FRAME_T, x=(BX - 0.75, BX + 0.75))
    m.box("cradle", (BX - 1.5, 2.5, -7.0), (BX + 1.5, 3.5, 7.5), FRAME_T)
    for s in (1, -1):
        xa, xb = sorted((BX + s * 1.75, BX + s * 2.75))
        m.box("seat_rail", (xa, 12.75, 2.5), (xb, 13.75, 16.0), FRAME_T)
        m.box("chain_stay", (xa, WR - 0.5, 5.0), (xb, WR + 0.5, ZR + 1), FRAME_T)
        m.box("seat_post", (xa + 0.25, WR + 0.5, 4.5), (xb - 0.25, 12.75, 5.75), FRAME_T)

        def shock(c):
            return ("blade", 3.6) if math.floor(c.p[1] * 1.5) % 2 else ("blade", 1.8)   # a chrome spring
        xa, xb = sorted((BX + s * 2.75, BX + s * 3.75))
        rod(m, "shock", (WR, ZR), (13.0, ZR + 0.5), 1, Paint("blade", 2, noise=0, decal=shock), x=(xa, xb))

    m.group("engine")

    def case(c):
        x, y, z = c.p
        if c.face == "west":
            r = math.hypot(z + 0.5, y - 6.25)
            if r < 2.4:
                return ("blade", 4 if r < 0.8 else 3.4 if r < 1.6 else 2.6)   # the round polished cover
        if c.face == "east":
            r = math.hypot(z - 1.5, y - 6.0)
            if r < 2.0:
                return ("blade", 3.2 if r > 1.2 else 2.4)
        return ("steel", 3.6 if c.face == "up" else lerp(y, 3, 9, 2.6, 3.8))
    m.box("crankcase", (BX - 2.75, 3.5, -5.0), (BX + 2.75, 9.0, 3.5), Paint("steel", 3, noise=0, rim=0.5, decal=case))
    m.box("gearbox", (BX - 2.25, 4.0, 3.5), (BX + 2.25, 9.5, 6.5), Paint("steel", 2, noise=0, rim=0.5))

    def barrel(c):
        if c.face in ("east", "west", "north", "south"):
            return ("steel", 3.8) if math.floor(c.p[1] * 2) % 2 else ("iron", 1)   # the cooling fins
        return ("steel", 3)
    m.box("barrels", (BX - 2.5, 9.0, -4.0), (BX + 2.5, 13.25, -0.5), Paint("steel", 3, noise=0, rim=0.3, decal=barrel))
    m.box("head", (BX - 2.75, 13.25, -4.25), (BX + 2.75, 14.5, -0.25), Paint("steel", 3, noise=0, rim=0.5))
    m.box("carburettor", (BX + 2.5, 9.5, -0.25), (BX + 4.0, 11.5, 2.0), Paint("blade", 3, noise=0, rim=0.5))

    # an exhaust each side: a header down from the barrel's front, along low, a long chrome muffler to the back
    for s in (1, -1):
        xa, xb = sorted((BX + s * 2.75, BX + s * 4.0))
        rod(m, "header", (12.5, -3.5), (4.5, -6.5), 1.25, CHROME_T, x=(xa, xb))
        m.box("exhaust", (xa, 4.0, -7.25), (xb, 5.25, 7.5), CHROME_T)

        def muffler(c):
            if c.face == "south":
                return ("rubber", 0) if 0 < c.u < c.nu - 1 and 0 < c.v < c.nv - 1 else ("blade", 2)
            if c.face == "up":
                return ("blade", 4)
            return ("blade", 3.4 if c.p[1] > 5.75 else 2.2)
        xa, xb = sorted((BX + s * 2.5, BX + s * 4.75))
        m.box("muffler", (xa, 3.75, 7.0), (xb, 6.25, 18.0), Paint("blade", 3, noise=0, rim=0.4, decal=muffler))
        xa, xb = sorted((BX + s * 3.0, BX + s * 6.25))
        m.box("footpeg", (xa, 6.0, -3.0), (xb, 6.75, -1.25),
              Paint("rubber", 1, noise=0, decal=lambda c: ("rubber", 2.4 if c.face == "up" and c.u % 2 else 1.2)))
    m.box("chain_guard", (BX + 2.75, 6.25, 5.0), (BX + 3.25, 8.25, 12.25), PAINT)

    m.group("saddle")
    # the sprung solo saddle: a leather pan (wide at the back, a narrow nose on a pivot), two chrome coil springs

    def saddle(c):
        x, y, z = c.p
        if c.face == "up":
            if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                return (LT, 1.4)                                      # the rolled, stitched edge
            return (LT, 3.4 if abs(x - BX) < 1 and z < 4 else 2.8)    # worn lighter where it is sat on
        if c.face == "south" and math.floor(c.p[0]) % 2 == 0 and c.v == 0:
            return ("blade", 4)                                       # rivets along the back
        return (LT, 1.4)
    sp = Paint(LT, 2, noise=0, rim=0.5, decal=saddle)
    m.box("saddle", (BX - 3.0, SADDLE - 1.5, 1.5), (BX + 3.0, SADDLE, 5.5), sp)
    m.box("saddle_nose", (BX - 1.75, SADDLE - 1.25, -0.25), (BX + 1.75, SADDLE - 0.25, 1.5), sp)

    def spring(c):
        return ("blade", 3.8) if math.floor(c.p[1] * 1.5) % 2 else ("iron", 2)
    for s in (1, -1):
        xa, xb = sorted((BX + s * 1.0, BX + s * 2.5))
        m.box("saddle_spring", (xa, 13.75, 3.0), (xb, SADDLE - 1.5, 4.5), Paint("blade", 3, noise=0, decal=spring))

    m.group("rear")
    _arc(m, "rear_fender", (WR, ZR), WR + 1.25, (-50, -15, 20, 55, 90, 110), 1, (BX - 1.75, BX + 1.75), PAINT)

    def tail(c):
        if c.face == "south":
            return ("signal_red", 1.4 if 0 < c.u < c.nu - 1 else 0.6)
        return ("blade", 3)
    m.box("tail_lamp", (BX - 1.25, 11.5, ZR + 6.25), (BX + 1.25, 12.75, ZR + 7.5), Paint("signal_red", 1, decal=tail, **lamp()))

    def plate_face(c):
        if c.face == "south":
            got = plate(math.floor(c.p[0] - BX + 2.5), math.floor(10.75 - c.p[1]), 5, 3)
            return got or ("car_white", 2)
        return ("iron", 2)
    m.box("plate", (BX - 2.5, 7.75, ZR + 6.75), (BX + 2.5, 10.75, ZR + 7.25), Paint("car_white", 2, noise=0, decal=plate_face))

    def bag(c):
        x, y, z = c.p
        if c.face == "up":
            return (LT, 3)
        if c.face == "west":
            if 11.0 <= y < 12.0:
                return (LT, 1.2)                                      # the flap's edge
            if y >= 11.0 and abs(z - 12.5) < 0.6:
                return ("brass", 3)                                   # the buckle strap
            if y >= 12.0:
                return (LT, 3.2)
        return (LT, lerp(y, 7.5, 13, 1.6, 2.8))
    m.box("saddlebag", (BX - 6.0, 7.5, 9.0), (BX - 3.75, 13.0, 16.0), Paint(LT, 2, noise=0, rim=0.6, decal=bag))

    # ================================================================================================
    # the sidecar
    # ================================================================================================
    m.group("sidecar")

    def tub(c):
        x, y, z = c.p
        inner = (c.face == "east" and x < SX - 3.9) or (c.face == "west" and x > SX + 3.9) or \
                (c.face == "south" and -4.1 < z < -3.9) or (c.face == "north" and 7.9 < z < 8.1)
        if inner and 9 <= y:
            return (LT, 1.0 if y < 10 else 1.6)                       # the cockpit's leather lining
        if c.face == "up":
            if abs(x - SX) < 0.75 and z < -4:
                return (S, 3.2)                                       # the stripe over the nose
            return (C, 3.0)
        if c.face == "down":
            return (C, 0.4)
        if c.face in ("east", "west") and c.lo[2] >= -12.6:
            fy = math.floor(y)
            if fy == 11:
                return (S, 3.0)                                       # the cream spear along each side
            if fy == 10:
                return (S, 1.2)
        return col(y, 4, 15, 1.0, 2.8)
    TUB = Paint(C, 2, noise=0, grad=0, rim=0.7, decal=tub)
    # the bullet's body: a solid lower half, the cockpit's walls over it, a nose and a tail in steps
    m.box("tub_belly", (SX - 4.25, 3.75, -10), (SX + 4.25, 5.0, 10), TUB)
    m.box("tub", (SX - 5.5, 5.0, -10), (SX + 5.5, 9.0, 10), TUB)
    m.box("tub_wall", (SX - 5.5, 9.0, -4), (SX - 4.0, 14.5, 8), TUB)
    m.box("tub_wall", (SX + 4.0, 9.0, -4), (SX + 5.5, 14.5, 8), TUB)
    # the decks before and behind the cockpit, their tops rounded off by a narrower layer
    m.box("tub_deck", (SX - 5.5, 9.0, -10), (SX + 5.5, 13.75, -4), TUB)
    m.box("tub_deck", (SX - 4.75, 13.75, -10), (SX + 4.75, 14.5, -4), TUB)
    m.box("tub_deck", (SX - 5.5, 9.0, 8), (SX + 5.5, 13.75, 10), TUB)
    m.box("tub_deck", (SX - 4.75, 13.75, 8), (SX + 4.75, 14.5, 10), TUB)
    m.box("tub_nose", (SX - 5.0, 5.5, -12.5), (SX + 5.0, 13.0, -10), TUB)
    m.box("tub_nose", (SX - 4.0, 13.0, -12.5), (SX + 4.0, 13.75, -10), TUB)
    m.box("tub_nose", (SX - 4.0, 6.5, -14.25), (SX + 4.0, 12.25, -12.5), TUB)
    m.box("tub_nose", (SX - 2.5, 7.75, -15.25), (SX + 2.5, 11.0, -14.25), TUB)
    m.box("tub_tail", (SX - 5.0, 5.5, 10), (SX + 5.0, 13.25, 12), TUB)
    m.box("tub_tail", (SX - 4.0, 13.25, 10), (SX + 4.0, 14.0, 12), TUB)
    m.box("tub_tail", (SX - 3.75, 6.75, 12), (SX + 3.75, 12.75, 13.25), TUB)

    # a chrome rim round the cockpit
    rim = CHROME_T
    for s in (1, -1):
        xa, xb = sorted((SX + s * 3.75, SX + s * 5.75))
        m.box("cockpit_rim", (xa, 14.5, -4.25), (xb, 15.0, 8.25), rim)
    m.box("cockpit_rim", (SX - 3.75, 14.5, -4.25), (SX + 3.75, 15.0, -3.25), rim)
    m.box("cockpit_rim", (SX - 3.75, 14.5, 7.25), (SX + 3.75, 15.0, 8.25), rim)

    # the leather seat and its tall back
    seat = dict(bolster=(LT, 1.6), insert=(LT, 2.8), stitch=("cream", 2))
    lo, hi = (SX - 4.0, 9.0, -1.0), (SX + 4.0, 10.0, 5.5)
    m.box("seat_cushion", lo, hi, Paint(LT, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    lo, hi = (SX - 4.0, 9.0, 5.5), (SX + 4.0, 16.0, 7.25)
    m.box("seat_back", lo, hi, Paint(LT, 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))

    # the little windscreen, leaning back, in a chrome frame
    m.box("windscreen", (SX - 4.5, 14.5, -6.75), (SX + 4.5, 19.5, -6.25),
          glass(frame=("blade", 3), sun=0, streak=0.35), rotation=("x", 22.5, (SX, 14.5, -6.5)))

    # a small round running lamp on the nose, a red lamp on the tail
    def run_lamp(c):
        if c.face == "north":
            return ("lamp", 3) if 0 < c.u < c.nu - 1 or 0 < c.v < c.nv - 1 else ("blade", 3)
        return ("blade", 3.2 if c.face == "up" else 2.2)
    m.box("nose_lamp", (SX - 1.0, 13.75, -12.0), (SX + 1.0, 15.75, -10.0), Paint("blade", 3, noise=0, rim=0.3, decal=run_lamp))
    m.box("tub_tail_lamp", (SX - 3.75, 11.0, 13.25), (SX - 2.25, 12.25, 13.5),
          Paint("signal_red", 1, decal=lambda c: ("signal_red", 1.4 if c.face == "south" else 0.6), **lamp()))

    # the spare wheel standing on the tail, facing back: a black tyre round a painted disc with a chrome hub
    def spare(c):
        x, y = c.p[0] - SX, c.p[1] - 10.0
        r = math.hypot(x, y)
        lit = y - 0.5 * x
        if c.face in ("north", "south"):
            if r > 3.3:
                return ("rubber", 1.4 if lit > 1.5 else 0.8)
            if r > 2.6:
                return ("blade", 3.4 if lit > 0 else 2.4)
            if r < 1.0:
                return ("blade", 4)
            return (C, 2.4 if lit > 0 else 1.6)
        # the tread round its edge
        a = math.atan2(y, x)
        return ("rubber", 2.0 if math.floor((a + math.pi) / (2 * math.pi) * 24) % 2 else 0.8)
    cyl(m, "spare_wheel", (SX, 10.0), 8, 13.5, 15.5, Paint("rubber", 1, noise=0, rim=0.3, decal=spare), axis="z")
    m.box("spare_mount", (SX - 0.75, 9.25, 13.25), (SX + 0.75, 10.75, 13.5), CHROME_T)

    # the sidecar wheel's mudguard (painted, the stripe along its crown), its stays to the tub
    def guard(c):
        if c.face == "up":
            return (S, 3.2) if abs(c.p[0] - SWX) < 0.6 else (C, 2.9)
        return col(c.p[1])
    _arc(m, "sidecar_fender", (WR, SWZ), WR + 1.25, (-60, -25, 10, 45, 75), 1, (SWX - 1.5, SWX + 1.5),
         Paint(C, 2, noise=0, grad=0, rim=0.6, decal=guard))
    m.box("fender_stay", (SX + 5.5, 11.0, SWZ - 2.0), (SWX - 1.25, 11.75, SWZ - 1.0), CHROME_T)
    m.box("fender_stay", (SX + 5.5, 11.0, SWZ + 2.5), (SWX - 1.25, 11.75, SWZ + 3.5), CHROME_T)
    m.box("axle_stub", (SX + 5.5, WR - 0.5, SWZ - 0.5), (SWX - 1.25, WR + 0.5, SWZ + 0.5), FRAME_T)

    # the chassis: two tubes from the bike's frame to the tub (front and back), an upper strut from the seat rail
    m.box("chassis", (BX + 1.5, 3.0, -7.5), (SX - 4.25, 4.25, -6.25), FRAME_T)
    m.box("chassis", (BX + 1.5, 3.0, 8.0), (SX - 4.25, 4.25, 9.25), FRAME_T)
    m.box("chassis_strut", (BX + 2.75, 12.0, 9.5), (SX - 5.5, 13.0, 10.5), FRAME_T)

    m.display = _held_display(m)
    return kit


ALL = {
    "sidecar_bike": sidecar_bike,
    "wheel_sidecar": wheel_sidecar,
}
