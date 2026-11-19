"""
소형 버스 · 리무진 관광버스 (content/stsv/items/vehicles_road.yml mini_bus, limousine_bus): the buses either side of the
village bus (models/vehicles_bus.py: read it first), a small one and a big one, from the same world. Original designs,
no company, no real model, no lettering: a friendly, slightly rustic Korean countryside look in warm, sun-faded
colours, round lamp "eyes", charming, never dirty.

  mini_bus       소형 버스 (작은 마을 승합 버스): a small, chubby van-bus with a short snub nose and a round roof, in
                 sun-faded orange and cream (a thin brown line between them). Round eyes, rosy cheeks and a little
                 smile on the nose, a small roof rack with a crate of apples, a round badge with a little house on each
                 side. The driver has his own door on the left over the front wheel (it swings out);
                 the passengers a sliding door on the right (it slides back along the side, like a van's). Inside:
                 the driver and four seats (two pairs), a box of cabbages and a basket in the back.
  limousine_bus  리무진 관광버스 (여행사 관광버스): a long, high, comfortable travel-agency coach. White with a purple
                 swoosh rising towards the back over a teal line, a dark strip of big tinted windows (the riders
                 still show), four round headlamps over a chrome grille, "rabbit ear" mirrors hanging in front of the
                 windscreen, an amber destination board, an air conditioner on the roof, luggage hatches between the
                 wheels. Inside: the driver and sixteen plush purple high-back seats with white lace head covers,
                 parcel racks, short pleated curtains, a carpet runner. One front door (it swings out), steps up
                 into the high floor.

The same rules as models/vehicles_bus.py: drawn at real size (16 px a block, the ground centre at (0, 0, 0), front =
north (-z), +x = the bus's right side, the driver on the left), shrunk by BUS_SCALE (body-scale 4: every half pixel
stays exact), the doors drawn in the body's space (a Kit). The windows' inside faces are all clear (glass_px inside):
a driver in first person sees straight through the windscreen, which is one piece (no middle post). The wheels are
the village bus's (wheel_bus).

Imported at the end of models/vehicles_road.py, after vehicles_bus (its ALL carries these): import that module.
"""

import math

from stsv_assets.bake import Paint, h3
from models.vehicles import CLEAR, DARK, lerp, lamp, plate, upholstery, _held_display
from models.vehicles_road import raw
from models.vehicles_premium import Kit
from models.vehicles_bus import BUS_SCALE, rounded, glass_px, worn

WHEEL_W, WHEEL_X = 6, 15.5          # wheel_bus: width, its middle (each side)
HUB = WHEEL_X - WHEEL_W / 2         # the tyres' inner side


# ------------------------------------------------------------------------------------------------
# shared helpers
# ------------------------------------------------------------------------------------------------

class Wheels:
    """The wheel wells: the wall over each axle steps down along a circle round it (the wheel clears every step)."""

    def __init__(self, axles, slot=10, steps=((0, 3, 18), (3, 6, 17), (6, 8.5, 15), (8.5, 10, 12))):
        self.axles, self.slot, self.steps = axles, slot, steps

    def bottom(self, z):
        """How low the wall comes at z over a wheel (its arch), or None away from the wheels."""
        for a in self.axles:
            d = abs(z - a)
            for d0, d1, y in self.steps:
                if d0 <= d < d1 or (d1 == self.slot and abs(d - d1) < 1e-6):
                    return y
        return None

    def cuts(self):
        out = set()
        for a in self.axles:
            for d0, d1, _ in self.steps:
                out |= {a - d1, a - d0, a + d0, a + d1}
        return out

    def well(self, p, inner=17.9):
        """Under an arch, outside the body's inner wall: the dark wheel well."""
        if abs(p[0]) <= inner:
            return False
        b = self.bottom(p[2])
        return b is not None and p[1] < b

    def rim(self, z, y):
        """The arch's edge (a darker line round it)."""
        b = self.bottom(z)
        return b is not None and math.floor(y) == b


def wall_spans(z0, z1, wheels, gaps, bottom, top):
    """The wall from z0 to z1 as boxes (z0, z1, y0, y1): cut round the wheel arches and the gaps (za, zb, y0, y1)
    (doors, a door over an arch), neighbours of the same height merged."""
    cuts = {z0, z1} | {c for c in wheels.cuts() if z0 < c < z1}
    for g in gaps:
        cuts |= {c for c in (g[0], g[1]) if z0 < c < z1}
    cuts = sorted(cuts)
    out = []
    for za, zb in zip(cuts, cuts[1:]):
        mid = (za + zb) / 2
        b = wheels.bottom(mid)
        ivs = [(bottom if b is None else b, top)]
        for g in gaps:
            if g[0] <= mid <= g[1]:
                nxt = []
                for lo, hi in ivs:
                    if g[3] <= lo or g[2] >= hi:
                        nxt.append((lo, hi))
                        continue
                    if g[2] > lo:
                        nxt.append((lo, g[2]))
                    if g[3] < hi:
                        nxt.append((g[3], hi))
                ivs = nxt
        for lo, hi in ivs:
            if hi - lo < 0.01:
                continue
            last = out[-1] if out else None
            if last and abs(last[1] - za) < 1e-6 and last[2] == lo and last[3] == hi:
                out[-1] = (last[0], zb, lo, hi)
            else:
                out.append((za, zb, lo, hi))
    return out


def walls(m, s, z0, z1, wheels, gaps, bottom, split, top, inner, outer, paint):
    """One side's wall (s: +1 right, -1 left) from the inner to the outer half width: cut round the arches below
    `split` (the arches' top), one long box above it (fewer seams), both cut at the gaps."""
    xa, xb = sorted((inner * s, outer * s))
    for za, zb, y0, y1 in wall_spans(z0, z1, wheels, gaps, bottom, split) + wall_spans(z0, z1, Wheels(()), gaps, split, top):
        m.box("wall", (xa, y0, za), (xb, y1, zb), paint)


def seat_spec(seats, seat_y):
    """The item file's seats: [right, up, forward] blocks (seats: (x, z) or (x, z, y) drawn px)."""
    out = []
    for s in seats:
        x, z = s[0], s[1]
        y = s[2] if len(s) > 2 else seat_y
        out.append([x / 16, y / 16, -z / 16])
    return out


def held(m):
    m.display = _held_display(m)
    for ctx in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        m.display[ctx]["scale"] = [round(v * 2.0 / BUS_SCALE * 0.8, 3) for v in m.display[ctx]["scale"]]


def chair(m, x, z, floor, seat_y, back_y, look, w=4.75, head=None):
    """A seat for a rider whose hip is at (x, z): a cushion in front of the hip, a back behind it. head: a white lace
    cover over the top of the back (the old coaches' 머리 덮개)."""
    lo, hi = (x - w, floor, z - 6), (x + w, seat_y, z + 2)
    m.box("cushion", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **look)))
    lo, hi = (x - w, floor, z + 2), (x + w, back_y, z + 4)
    up = upholstery(lo=lo, hi=hi, **look)

    def back(c):
        if head and c.p[1] > back_y - head and c.face in ("north", "south", "up", "east", "west"):
            fx, fy = math.floor(c.p[0] * 2), math.floor(c.p[1])
            if fy == math.floor(back_y - head) and c.face != "up":
                return ("paper", 1.8 if (fx // 2) % 2 else 2.6)      # the lace's scalloped hem
            return ("paper", 2.4 if (fx + fy) % 3 == 0 else 3.0)
        return up(c)
    m.box("seat_back", lo, hi, Paint("rubber", 2, noise=0, decal=back))


def steering(c):
    if c.face not in ("up", "down"):
        return raw("rubber", 1)
    u, v = c.u, c.v
    ring = (v in (0, c.nv - 1) and 1 <= u <= c.nu - 2) or (0 < v < c.nv - 1 and u in (0, c.nu - 1))
    if ring:
        return raw("rubber", 2 if v < c.nv / 2 else 1)
    if v in (c.nv // 2 - 1, c.nv // 2) and 2 <= u <= c.nu - 3:
        return raw("tint", 2 if u == c.nu // 2 else 1)
    return ("raw", CLEAR)


def mirror_face(c):
    if c.face == "south":
        if c.u in (0, c.nu - 1) and c.v in (0, c.nv - 1):
            return ("blade", 2.6)
        if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
            return ("rubber", 1.4)
        return ("glass", 3 if c.v == 1 and c.u == 1 else 1.6)
    if c.face in ("up", "north", "west", "east"):
        return ("blade", 2.8 if c.face == "up" else 2.2)
    return ("blade", 1.6)


def round_lamps(centres, r, rim=("blade", 3.6), lit=("lamp", 4), body=None, highlight=True, face="north"):
    """A lamp box's decal: round lamps (chrome rims, a little highlight up and in), the body's paint round them."""
    def decal(c):
        x, y = c.p[0], c.p[1]
        if c.face != face:
            return body(c) if body else ("rubber", 1)
        for cx, cy in centres:
            d = math.hypot(x - cx, y - cy)
            if d <= r:
                if d > r - 0.9:
                    return rim
                dx, dy = x - cx, y - cy
                if highlight and -r * 0.75 < dy * -1 < 0 and 0.2 < dy < r * 0.7 and abs(dx + (0.6 if cx > 0 else -0.6)) < r * 0.45:
                    return ("car_white", 4.6)
                return (lit[0], lit[1] if y > cy - 0.5 else lit[1] - 0.5)
        return body(c) if body else ("rubber", 1)
    return decal


# ================================================================================================
# 소형 버스 4.75 x 2.4 x 2.8 blocks (drawn px)
# ================================================================================================

class Mini:
    F, BK = -38, 38
    H, IN, W = 19, 18, 17
    WF, WB = -30, 35                             # the snub nose ends at the windscreen's foot; the back wall
    BOTTOM, FLOOR, BELT, TOP, BAND, ROOF = 4, 10, 21, 38, 41, 44
    SCREEN = 18                                  # the bonnet's top
    FA, RA = -19, 22
    DRIVER_DOOR = (-30, -17)                     # left, over the front wheel (its foot over the arch)
    DOOR = (-9, 3)                               # right, behind the front wheel: the sliding door
    SEAT_Y = 16
    SEATS = [(-7.75, -19), (-7.75, -3), (7.75, -3), (-7.75, 12), (7.75, 12)]
    O = "topaz"                                  # the sun-faded orange
    WHEELS = Wheels((-19, 22))
    PANES = {"west": [(-15, -3), (-1, 11), (13, 23), (25, 33)],
             "east": [(-28, -11), (5, 17), (19, 33)]}


def mini_kit():
    C = Mini
    F, BK, H, IN, W, WF, WB = C.F, C.BK, C.H, C.IN, C.W, C.WF, C.WB
    BOTTOM, FLOOR, BELT, TOP, BAND, ROOF, SCREEN = C.BOTTOM, C.FLOOR, C.BELT, C.TOP, C.BAND, C.ROOF, C.SCREEN
    O, WH = C.O, C.WHEELS
    kit = Kit("mini_bus", ("door_driver", "door"), scale=BUS_SCALE)
    m = kit.body
    PAINT = Paint(O, 2, noise=0, grad=0, rim=0.3)

    def outside(c, x, y, z):
        """Two tones: a dark skirt, the orange, a thin brown line, cream up to the windows."""
        fy = math.floor(y)
        if fy < BOTTOM + 1:
            return ("rubber", 1.4)
        if fy < 14:
            return (O, worn(c.p, lerp(y, BOTTOM, 14, 1.7, 2.2)))
        if fy == 14:
            return ("wood_dark", 2.4)
        return ("cream", worn(c.p, lerp(y, 15, BELT, 2.4, 3.0)))

    house = ["...#...", "..###..", ".#####.", "..#.#..", "..###.."]

    def side(c):
        x, y, z = c.p
        fy, fz = math.floor(y), math.floor(z)
        if WH.well(c.p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            return (O, 2.4)
        inside = (c.face == "east" and x < 0) or (c.face == "west" and x > 0)
        if inside:
            if fy < FLOOR + 1:
                return ("rubber", 1)
            return ("cream", 2.2 if fy > FLOOR + 2 else 1.6)
        if c.face in ("north", "south"):
            return (O, 1.4)
        if WH.rim(z, y):
            return (O, 1.0)
        # a round cream badge with a little orange house, between the wheels
        bz = 5 if c.face == "west" else 6
        col = math.floor(z - bz) if c.face == "west" else math.floor(bz + 9 - z)
        row = math.floor(13 - y)
        if 0 <= col < 9 and 0 <= row < 8 and math.hypot((col - 4) / 4.6, (row - 3.5) / 4.1) <= 1:
            hc, hr = col - 1, row - 1
            if 0 <= hc < 7 and 0 <= hr < 5 and house[hr][hc] == "#":
                return ("fox", 2.4 if hr < 2 else 2.8)
            return ("cream", 3.6)
        if fz in (WF + 1, WF + 2) and fy == 11:
            return ("fox", 3.6)
        if fz in (WB - 2, WB - 1) and fy == 11:
            return ("signal_red", 1.6)
        return outside(c, x, y, z)
    wall = PAINT(decal=side)

    def corner(c):
        x, y, z = c.p
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            return ("cream", 2.8)
        if y >= BELT:
            return (O, 2.4)
        return outside(c, x, y, z)

    def hood(c):
        """The snub nose: a little grille smile between the eyes (lamps), rosy cheeks."""
        x, y, z = c.p
        fy, ax = math.floor(y), abs(x)
        if c.face == "north":
            ys = 8.4 + x * x / 12
            if ax <= 4.5 and abs(y - ys) < 0.6:
                return ("wood_dark", 1.2)
            if ax <= 3.5 and ys - 1.4 < y < ys - 0.4 and y < 9.4:
                return ("pink", 1.6)
            if math.hypot(ax - 14.5, y - 8.5) < 1.4:
                return ("pink", 2.4)
            return outside(c, x, y, z)
        if c.face == "up":
            return (O, 2.4) if z < WF - 1 else ("rubber", 1.2)
        if c.face in ("east", "west"):
            return outside(c, x, y, z)
        return (O, 2)

    def tail(c):
        x, y, z = c.p
        fy, ax = math.floor(y), abs(x)
        if c.face == "south":
            if 7 <= fy <= 9 and ax < 4.5:
                return plate(math.floor(x + 4.5), 9 - fy, 9, 3)
            heart = {19: (-2, -1, 1, 2), 18: (-2, -1, 0, 1, 2), 17: (-1, 0, 1), 16: (0,)}
            if fy in heart and math.floor(x + 0.5) in heart[fy]:
                return ("salmon", 2.4)
            return outside(c, x, y, z)
        if c.face == "up":
            return ("cream", 2.6)
        if c.face in ("east", "west"):
            return outside(c, x, y, z)
        return (O, 2)

    def band(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "down":
            return ("cream", 2.6)
        if c.face == "up":
            return ("cream", 3.2)
        if fy == TOP:
            return (O, 1.6)
        return (O, worn(c.p, 2.5))

    def roof(c):
        if c.face == "up":
            return ("cream", 3.6)
        return ("cream", 3.0)

    def floor(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) > IN - 1 or (x > 12 and C.DOOR[0] <= z <= C.DOOR[1]):
                return ("hazard", 1)
            return ("wood", 2.2 if math.floor(x + 0.5) % 3 else 1.6)   # a wooden floor
        if c.face in ("east", "west") and abs(x) > 12 and y < FLOOR:
            return ("hazard", 1) if math.floor(y) == 6 else ("rubber", 1)
        return ("rubber", 0.6)

    m.group("body")
    fp = Paint("rubber", 1, noise=0, decal=floor)
    m.box("floor", (-HUB, BOTTOM, WF), (HUB, FLOOR, WB), fp)
    for s in (1, -1):
        xa, xb = sorted((HUB * s, IN * s))
        if s > 0:
            m.box("floor_side", (xa, BOTTOM, C.FA + 10), (xb, 7, C.DOOR[1]), fp)       # the step at the door
            m.box("floor_side", (xa, BOTTOM, C.DOOR[1]), (xb, FLOOR, C.RA - 10), fp)
        else:
            m.box("floor_side", (xa, BOTTOM, C.FA + 10), (xb, FLOOR, C.RA - 10), fp)
        m.box("floor_side", (xa, BOTTOM, C.RA + 10), (xb, FLOOR, WB), fp)
        for a in WH.axles:
            m.box("housing", (xa, 18, max(a - 10, WF + 4)), (xb, 21, a + 10), Paint("rubber", 1, noise=0))
            xl, xr = sorted(((HUB - 0.5) * s, HUB * s))
            m.box("well", (xl, FLOOR, max(a - 10, WF + 4)), (xr, 18, a + 10), Paint("rubber", 0, noise=0))   # the well's dark inner side

    for s in (1, -1):
        xa, xb = sorted((IN * s, H * s))
        gaps = [(C.DOOR[0], C.DOOR[1], BOTTOM, BELT)] if s > 0 else [(C.DRIVER_DOOR[0], C.DRIVER_DOOR[1], 18, BELT)]
        walls(m, s, WF, WB, WH, gaps, BOTTOM, WH.steps[0][2], BELT, IN, H, wall)
        xa, xb = sorted((W * s, H * s))
        m.box("corner", (xa, BOTTOM, WB), (xb, TOP, WB + 1), PAINT(decal=corner))
    # the snub nose, rounded: the bonnet, a lip round its front, a softer top
    hp = PAINT(decal=hood)
    m.box("hood", (-H, BOTTOM, F + 2), (H, SCREEN - 1, WF), hp)
    m.box("hood", (-W, BOTTOM, F + 0.5), (W, SCREEN - 2.5, F + 2), hp)
    m.box("hood", (-H + 1.5, SCREEN - 1, F + 3), (H - 1.5, SCREEN, WF), hp)
    m.box("tail", (-W, BOTTOM, WB), (W, 24, BK), PAINT(decal=tail))
    m.box("band", (-H, TOP, WF - 1), (H, BAND, WB + 1), PAINT(decal=band))
    m.box("band", (-W, TOP, WB + 1), (W, BAND, BK - 0.5), PAINT(decal=band))
    rp = PAINT(decal=roof)
    m.box("roof", (-H + 1, BAND, WF), (H - 1, BAND + 1.5, BK - 1), rp)
    m.box("roof", (-H + 4, BAND + 1.5, WF + 3), (H - 4, ROOF, BK - 4), rp)

    # a little roof rack with a wooden crate of apples
    rail = Paint("rubber", 2, noise=0, rim=0.4, decal=lambda c: ("rubber", 2.6 if c.face == "up" else 1.4))
    for s_ in (1, -1):
        xa, xb = sorted((11 * s_, 12 * s_))
        m.box("rack_rail", (xa, ROOF, -6), (xb, ROOF + 1.5, 28), rail)

    def crate(c):
        x, y, z = c.p
        if c.face == "up":
            fx, fz = math.floor(x), math.floor(z)
            if fx in (-8, 0) or fz in (2, 12):
                return ("wood", 1.8)                             # the crate's rim
            return ("tomato", 3.2 if (fx + fz) % 3 == 0 else 2.4 if (fx * 7 + fz) % 4 else 1.8)   # apples
        return ("wood", 2.6 if math.floor(y) % 2 else 2.0)
    m.box("crate", (-8, ROOF, 2), (1, ROOF + 3, 13), Paint("wood", 2, noise=0, rim=0.6, decal=crate))

    def bumper(c):
        x, y = c.p[0], c.p[1]
        fy = math.floor(y)
        if c.face == "north" and 4 <= fy <= 6 and abs(x) < 4.5:
            got = plate(math.floor(x + 4.5), 6 - fy, 9, 3)
            if got:
                return got
        if c.face == "up":
            return ("blade", 4)
        return ("blade", 2 if fy == 3 else 3.4 if fy >= 5 else 2.8)
    bp = Paint("blade", 3, noise=0, rim=0.6, decal=bumper)
    m.box("bumper", (-W, 3, F - 1.5), (W, 7, F + 0.5), bp)
    m.box("bumper", (-W, 3, BK), (W, 6, BK + 1.5), bp)

    m.group("lights")
    for s in (1, -1):
        xa, xb = sorted((5.5 * s, 13.5 * s))

        def eye(c, s=s):
            # a round headlamp eye: a chrome rim, the warm lamp, a dark pupil looking up and in, a white glint
            x, y = c.p[0], c.p[1]
            if c.face != "north":
                return (O, 1.4)
            r = math.hypot(x - 9.5 * s, y - 12)
            if r > 3.5:
                return (O, 1.3)                                  # round the lamp: the paint (unlit: a step darker)
            if r > 2.7:
                return ("blade", 4 if y > 12 else 3)
            px, py = x - (9.5 - 0.6) * s, y - 12.7
            if math.hypot(px, py) < 1.35:
                if 0.1 < py < 1.0 and abs(px + 0.4 * s) < 0.5:
                    return ("car_white", 4.4)
                return ("tint", 0)
            return ("lamp", 4 if y > 11 else 3.4)
        xa, xb = sorted((6 * s, 13 * s))
        m.box("eye", (xa, 8.5, F), (xb, 15.5, F + 0.5), Paint("lamp", 3, decal=eye, **lamp()))

        def taillight(c, s=s):
            x, y, z = c.p
            if c.face != "south":
                return ("signal_red", 0)
            r = math.hypot(x - 13.5 * s, y - 15)
            if r <= 2:
                return ("toadstool", 3.4 if r < 1 else 2.8)
            if math.hypot(x - 13.5 * s, y - 10.5) <= 1.5:
                return ("lamp", 3.8)
            return (O, 1.2)
        xa, xb = sorted((11.5 * s, 15.5 * s))
        m.box("taillight", (xa, 8.5, BK), (xb, 17, BK + 0.5), Paint("signal_red", 1, decal=taillight, **lamp()))

    m.group("glass")

    def screen(c):
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw(O, 2.2)
        state = rounded(x, -H + 1, H - 1, y, SCREEN, TOP, r=3.6)
        if state == "in" and math.floor(y) == SCREEN + 1 and (2 <= abs(x) < 9):
            return raw("rubber", 0.6)
        return glass_px(state, x, y, TOP, (O, 2.2), 23, inside=c.face == "south")
    m.box("windscreen", (-H, SCREEN, WF - 1), (H, TOP, WF - 0.5), Paint("glass", 2, noise=0, light=False, decal=screen))

    def windows(c):
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw(O, 2.4)
        face = "west" if x < 0 else "east"
        for z0, z1 in C.PANES[face]:
            if z0 - 0.5 <= z <= z1 + 0.5:
                state = rounded(z, z0, z1, y, BELT + 1, TOP - 1, r=2.6)
                return glass_px(state, z, y, TOP - 1, (O, 2.4), inside=c.face != face)
        return raw(O, 2.4)
    wp = Paint("glass", 2, noise=0, light=False, decal=windows)
    m.box("side_window", (-H, BELT, C.DRIVER_DOOR[1]), (-H + 0.5, TOP, WB), wp)
    m.box("side_window", (H - 0.5, BELT, WF), (H, TOP, C.DOOR[0]), wp)
    m.box("side_window", (H - 0.5, BELT, C.DOOR[1]), (H, TOP, WB), wp)

    def back_window(c):
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw(O, 2.4)
        return glass_px(rounded(x, -W + 2, W - 2, y, 25, TOP - 1, r=3.2), x, y, TOP - 1, (O, 2.4), 13, inside=c.face == "north")
    m.box("back_window", (-W, 24, BK - 0.5), (W, TOP, BK), Paint("glass", 2, noise=0, light=False, decal=back_window))

    for s in (1, -1):
        arm = Paint("rubber", 2, noise=0, rim=0.4)
        xa, xb = sorted((H * s, (H + 2.5) * s))
        m.box("mirror_arm", (xa, 29, WF), (xb, 30, WF + 1), arm)
        xa, xb = sorted(((H + 0.75) * s, (H + 3.25) * s))
        m.box("mirror", (xa, 25, WF - 1), (xb, 30, WF + 0.5), Paint("blade", 2, noise=0, decal=mirror_face))

    m.group("inside")
    m.box("dash", (-H + 1, SCREEN - 1, WF), (H - 1, SCREEN + 3, WF + 4), DARK)
    m.box("column", (-8.25, FLOOR, WF + 4), (-7.25, SCREEN + 5, WF + 5), Paint("rubber", 2, noise=0))
    m.box("wheel", (-11.25, SCREEN + 5, WF + 2), (-4.25, SCREEN + 5.5, WF + 8), Paint("rubber", 1, noise=0, light=False, decal=steering))
    driver = dict(bolster=("rubber", 2), insert=("rubber", 3), stitch=("steel", 2))
    look = dict(bolster=("leaf_dark", 1.6), insert=("leaf", 2.0), stitch=("leaf", 3.0))
    chair(m, *C.SEATS[0], FLOOR, C.SEAT_Y, C.SEAT_Y + 13, driver, w=4.5)
    for x, z in C.SEATS[1:]:
        chair(m, x, z, FLOOR, C.SEAT_Y, C.SEAT_Y + 12, look, w=4.5, head=4)
    m.box("pole", (11, FLOOR, C.DOOR[0] - 1.5), (12, TOP, C.DOOR[0] - 0.5), Paint("hazard", 2, noise=0, rim=0.3))

    # in the back: a box of cabbages and a bamboo basket
    def cabbages(c):
        x, y, z = c.p
        if c.face == "up":
            return ("cabbage", 3 if (math.floor(x) + math.floor(z)) % 3 else 2.2)
        return ("wood", 2.4 if math.floor(y) % 2 else 1.8)
    m.box("cabbages", (-14, FLOOR, 22), (-3, FLOOR + 6, 32), Paint("wood", 2, noise=0, rim=0.5, decal=cabbages))
    m.box("basket", (3, FLOOR, 23), (11, FLOOR + 5, 31),
          Paint("bamboo", 2, noise=0, rim=0.5, decal=lambda c: ("bamboo", 2.8 if (math.floor(c.p[0]) + math.floor(c.p[1] + c.p[2])) % 2 else 2.0)))

    # --- the doors (parts) ---------------------------------------------------------------------------
    def door_paint(z0, z1, glass_y0, handle_back):
        def decal(c):
            x, y, z = c.p
            fy = math.floor(y)
            if c.nu <= 1 or c.nv <= 1:
                return raw(O, 1.8)
            state = rounded(z, z0 + 1, z1 - 1, y, glass_y0, TOP - 1, r=2.6)
            if state != "out":
                return glass_px(state, z, y, TOP - 1, (O, 2.4), 17)
            if fy == BELT - 4 and (z1 - 4 < z < z1 - 1.5 if handle_back else z0 + 1.5 < z < z0 + 4):
                return raw("blade", 3.6)
            got = outside(c, x, y, z)
            return raw(got[0], got[1])
        return Paint("glass", 2, noise=0, light=False, decal=decal)
    d0, d1 = C.DRIVER_DOOR
    kit["door_driver"].box("door", (-H, 18, d0), (-H + 0.5, TOP, d1), door_paint(d0, d1, BELT + 1, True))
    d0, d1 = C.DOOR
    kit["door"].box("door", (H - 0.5, BOTTOM, d0), (H, TOP, d1), door_paint(d0, d1, BELT + 1, False))

    held(m)
    return kit


# ================================================================================================
# 리무진 관광버스 8.5 x 2.4 x 3.6 blocks (drawn px)
# ================================================================================================

class Limo:
    F, BK = -68, 68
    H, IN, W = 19, 18, 17
    WF, WB = -65, 65
    BOTTOM, FLOOR, BELT, TOP, BAND, ROOF = 4, 20, 30, 50, 53, 55
    SCREEN = 21
    FA, RA = -42, 44
    DOOR = (-65, -52)                            # the front door (right side)
    SEAT_Y = 27
    ROWS = (-38, -24, -10, 4, 18, 32, 46, 60)
    SEATS = [(-8, -54)] + [(sx * 8, z) for z in (-38, -24, -10, 4, 18, 32, 46, 60) for sx in (-1, 1)]
    HATCHES = ((-30, -12), (-10, 9), (11, 32))
    EYES = ((7.5, 12), (12.5, 12.5))              # the four round headlamps (x each side, y)
    EYE_R = 2.25
    WHEELS = Wheels((-42, 44), steps=((0, 3, 17), (3, 6, 16), (6, 8.5, 14), (8.5, 10, 11)))
    PANES = {"west": [(-64, -48), (-46, -24), (-22, 0), (2, 24), (26, 47), (49, 64)],
             "east": [(-50, -30), (-28, -6), (-4, 18), (20, 42), (44, 64)]}
    PURPLE, TEAL = "grape", "water"


def swoosh(z, y):
    """The livery: a purple swoosh rising towards the back over a thin teal line (None: the white)."""
    t = (z - Limo.F) / (Limo.BK - Limo.F)
    yc = 8 + 15 * t ** 1.5
    d = y - yc
    if 2.6 <= d < 4.2:
        return ("petal_purple", 1.4)                              # a lavender edge over it
    if -2.6 <= d < 2.6:
        return (Limo.PURPLE, lerp(d, -2.6, 2.6, 2.2, 3.6))
    if -5.0 <= d < -3.4:
        return (Limo.TEAL, 2.6)
    # a little sun rising ahead of the swoosh
    r = math.hypot(z + 22, y - 19.5)
    if r < 3.1:
        return ("topaz", 2.6 if r < 1.6 else 2.1)
    return None


def limousine_kit():
    C = Limo
    F, BK, H, IN, W, WF, WB = C.F, C.BK, C.H, C.IN, C.W, C.WF, C.WB
    BOTTOM, FLOOR, BELT, TOP, BAND, ROOF, SCREEN = C.BOTTOM, C.FLOOR, C.BELT, C.TOP, C.BAND, C.ROOF, C.SCREEN
    P, WH = C.PURPLE, C.WHEELS
    kit = Kit("limousine_bus", ("door",), scale=BUS_SCALE)
    m = kit.body
    PAINT = Paint("pearl", 2, noise=0, grad=0, rim=0.3)

    def outside(c, x, y, z):
        """Every outer face: a grey skirt, the white with its swoosh, a purple line under the windows."""
        fy = math.floor(y)
        if fy < BOTTOM + 1:
            return ("pearl", 0.49)
        if fy == BELT - 1:
            return (P, 2.0)
        got = swoosh(z, y)
        if got:
            return (got[0], worn(c.p, got[1]))
        return ("pearl", worn(c.p, lerp(y, BOTTOM, TOP, 1.9, 2.4)))

    def side(c):
        x, y, z = c.p
        fy, fz = math.floor(y), math.floor(z)
        if WH.well(c.p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            return ("rubber", 1.4)
        inside = (c.face == "east" and x < 0) or (c.face == "west" and x > 0)
        if inside:
            if fy < FLOOR + 1:
                return (P, 1)
            return ("cream", 2.2 if fy > FLOOR + 2 else 1.6)
        if c.face in ("north", "south"):
            return ("pearl", 1.31)
        if WH.rim(z, y):
            return ("pearl", 0.66)
        # the luggage hatches: a dark seam round each, a chrome handle (the livery runs over them)
        for z0, z1 in C.HATCHES:
            if z0 <= z <= z1 and 5 <= fy <= 17:
                if fz in (z0, z1 - 1) or fy in (5, 17):
                    return ("pearl", 0.66)
                if fy == 7 and abs(z - (z0 + z1) / 2) < 2:
                    return ("blade", 3.6)
        if WB - 9 <= z <= WB - 2 and 8 <= fy <= 16:
            return ("rubber", 1.0) if fy % 2 else ("pearl", 1.64)   # the engine's louvres behind the rear wheels
        if fz in (WF + 1, WF + 2) and fy == 12:
            return ("fox", 3.6)
        if fz in (WB - 12, WB - 11) and fy == 12:
            return ("signal_red", 1.6)
        near = min(abs(z - a) for a in WH.axles) - WH.slot
        if fy < 14 and 0 < near < 4 and h3(fz, fy, 7, 81) < 0.16 - near * 0.04 - max(0, fy - 6) * 0.025:
            return ("clay", 3.0 if h3(fz, fy, 2, 9) < 0.5 else 2.6)   # a little road dust behind the wheels
        return outside(c, x, y, z)
    wall = PAINT(decal=side)

    def corner(c):
        x, y, z = c.p
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            return ("pearl", 2.13)
        if y >= BELT:
            return ("rubber", 1.4)                               # the dark window strip wraps round
        return outside(c, x, y, z)

    def nose(c):
        """Under the windscreen: a chrome grille between the lamps, a purple line, a little round badge."""
        x, y, z = c.p
        fy, ax = math.floor(y), abs(x)
        if c.face == "north":
            if ax < 5 and 9 <= fy <= 13:
                if ax > 4 or fy in (9, 13):
                    return ("blade", 3.6)
                return ("rubber", 0.6) if fy % 2 else ("blade", 2.4)
            if math.hypot(x, y - 16) < 1.6:
                return ("pearl", 3.12) if math.hypot(x, y - 16) < 0.8 else (P, 2.6)
            if fy == 18:
                return (P, 2.4)
            if fy == 17 and ax > 3:
                return (C.TEAL, 2.6)
            if 14 <= ax <= 16 and 8 <= fy <= 9:
                return ("fox", 3.4)
            if math.hypot(ax - 15, y - 15) < 1.3:
                return ("pink", 2.4)                             # rosy cheeks
            return ("pearl", worn(c.p, 2.13))
        if c.face == "up":
            return ("rubber", 1.2)
        if c.face in ("east", "west"):
            return outside(c, x, y, z)
        return ("pearl", 1.64)

    def tail(c):
        x, y, z = c.p
        fy, ax = math.floor(y), abs(x)
        if c.face == "south":
            if 8 <= fy <= 10 and ax < 4.5:
                return plate(math.floor(x + 4.5), 10 - fy, 9, 3)
            if ax < 8 and 13 <= fy <= 25:
                if ax > 7 or fy in (13, 25):
                    return ("pearl", 1.15)
                return ("rubber", 1.0) if fy % 2 else ("pearl", 1.97)   # the engine's grille
            if fy == 28:
                return (P, 2.4)
            return ("pearl", worn(c.p, 2.13))
        if c.face == "up":
            return ("pearl", 2.13)
        if c.face in ("east", "west"):
            return outside(c, x, y, z)
        return ("pearl", 1.64)

    def band(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "down":
            return ("cream", 2.6)
        if c.face == "up":
            return ("pearl", 2.46)
        if fy == TOP:
            return (C.TEAL, 2.4)
        return ("pearl", 2.3)

    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 5 and 40 <= z < 48:
                if abs(x) > 4 or math.floor(z) in (40, 47):
                    return ("pearl", 1.48)
                return ("pearl", 2.13)
            return ("pearl", 2.46)
        return ("pearl", 2.13)

    def floor(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 3:
                return (P, 2.2 if math.floor(z) % 4 else 1.8)   # the carpet runner
            return ("rubber", 2.2 if math.floor(z) % 2 else 1.8)
        return ("rubber", 0.8)

    m.group("body")
    fp = Paint("rubber", 1, noise=0, decal=floor)
    D0, D1 = C.DOOR
    # the high floor over the luggage bays; at the front door two steps up from the kerb
    m.box("floor", (-HUB, BOTTOM, WF), (4, FLOOR, WB), fp)
    m.box("floor", (4, BOTTOM, D1), (HUB, FLOOR, WB), fp)
    m.box("step", (4, BOTTOM, WF), (HUB, 14, D1), fp)
    for s in (1, -1):
        xa, xb = sorted((HUB * s, IN * s))
        for a0, a1 in ((WF, C.FA - 10), (C.FA + 10, C.RA - 10), (C.RA + 10, WB)):
            top = 8 if (s > 0 and a0 == WF) else FLOOR
            m.box("floor_side", (xa, BOTTOM, a0), (xb, top, a1), fp)
        for a in WH.axles:
            m.box("housing", (xa, WH.steps[0][2], a - 10), (xb, FLOOR, a + 10), Paint("rubber", 1, noise=0))

    for s in (1, -1):
        xa, xb = sorted((IN * s, H * s))
        gaps = [(D0, D1, BOTTOM, BELT)] if s > 0 else []
        walls(m, s, WF, WB, WH, gaps, BOTTOM, WH.steps[0][2], BELT, IN, H, wall)
        xa, xb = sorted((W * s, H * s))
        m.box("corner", (xa, BOTTOM, WF - 1), (xb, TOP, WF), PAINT(decal=corner))
        m.box("corner", (xa, BOTTOM, WB), (xb, TOP, WB + 1), PAINT(decal=corner))
    m.box("nose", (-W, BOTTOM, F), (W, SCREEN, WF), PAINT(decal=nose))
    m.box("tail", (-W, BOTTOM, WB), (W, 32, BK), PAINT(decal=tail))
    m.box("band", (-H, TOP, WF - 1), (H, BAND, WB + 1), PAINT(decal=band))
    m.box("band", (-W, TOP, WB + 1), (W, BAND, BK - 0.5), PAINT(decal=band))
    m.box("roof", (-H + 1, BAND, WF + 0.5), (H - 1, BAND + 1, BK - 2), PAINT(decal=roof))
    m.box("roof", (-H + 3.5, BAND + 1, WF + 3), (H - 3.5, ROOF, BK - 5), PAINT(decal=roof))

    # the air conditioner on the roof: a low grey pod, two round fan grilles
    def aircon(c):
        x, y, z = c.p
        if c.face == "up":
            for fz in (-6, 10):
                d = math.hypot(x, z - fz)
                if d < 4.5:
                    return ("steel", 1.2) if d > 3.6 or math.floor(d * 2) % 2 else ("steel", 2)
            return ("steel", 3.2)
        return ("steel", 2.4 if math.floor(y) == ROOF + 1 else 2)
    m.box("aircon", (-7, ROOF, -12), (7, ROOF + 2, 16), Paint("steel", 3, noise=0, rim=0.4, decal=aircon))

    def bumper(c):
        x, y = c.p[0], c.p[1]
        fy = math.floor(y)
        if c.face == "north" and 4 <= fy <= 6 and abs(x) < 4.5:
            got = plate(math.floor(x + 4.5), 6 - fy, 9, 3)
            if got:
                return got
        if c.face == "up":
            return ("blade", 4)
        return ("blade", 2 if fy == 3 else 3.4 if fy >= 5 else 2.8)
    bp = Paint("blade", 3, noise=0, rim=0.6, decal=bumper)
    m.box("bumper", (-W + 0.5, 3, F - 1.5), (W - 0.5, 7, F), bp)
    m.box("bumper", (-W + 0.5, 3, BK), (W - 0.5, 7, BK + 1.5), bp)

    m.group("lights")
    for s in (1, -1):
        # each lamp in its own little pod (the outer one a touch higher and further out: their glows stay apart)
        for (cx, cy), out in zip(C.EYES, (0.5, 0.75)):
            xa, xb = sorted(((cx - 2.5) * s, (cx + 2.5) * s))
            m.box("headlamp", (xa, cy - 2.5, F - out), (xb, cy + 2.5, F),
                  Paint("lamp", 3, decal=round_lamps([(cx * s, cy)], C.EYE_R, body=lambda c: ("pearl", 1.48)), **lamp()))

        def taillight(c, s=s):
            x, y, z = c.p
            if c.face != "south":
                return ("signal_red", 0)
            r = math.hypot(x - 13 * s, y - 14.5)
            if r <= 2.9:
                return ("blade", 3.4) if r > 2.2 else ("toadstool", 3.6 if r < 1 else 3.0 if y > 14.5 else 2.6)
            if math.hypot(x - 13 * s, y - 9.5) <= 1.6:
                return ("lamp", 3.8)                             # the reverse lamp
            return ("pearl", 1.2)
        xa, xb = sorted((10 * s, 16 * s))
        m.box("taillight", (xa, 7.5, BK), (xb, 17.5, BK + 0.5), Paint("signal_red", 1, decal=taillight, **lamp()))

    # the destination board over the windscreen: amber LED marks (no words) on black
    def led_board(c):
        if c.face != "north":
            return ("rubber", 1)
        if c.u == 0 or c.v == 0 or c.u == c.nu - 1 or c.v == c.nv - 1:
            return ("rubber", 1.6)
        col, row = c.u - 1, c.v - 1
        k, u = divmod(col, 4)
        if u < 3 and h3(k, row, u, 41) < (0.62 if k % 3 else 0.3):
            return ("glow_amber", 1.6)
        return ("rubber", 0.4)
    m.box("dest_board", (-11, TOP - 0.5, WF - 1.5), (11, BAND - 0.5, WF - 1), Paint("rubber", 1, decal=led_board, **lamp()))

    m.group("glass")

    def screen(c):
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw("pearl", 2.13)
        state = rounded(x, -W, W, y, SCREEN, TOP, r=3.2)
        if state == "in" and math.floor(y) == SCREEN + 1 and (1.5 <= abs(x) < 10):
            return raw("rubber", 0.6)                             # the wipers resting
        return glass_px(state, x, y, TOP, ("pearl", 2.13), 23, inside=c.face == "south")
    m.box("windscreen", (-W, SCREEN, WF - 1), (W, TOP, WF - 0.5), Paint("glass", 2, noise=0, light=False, decal=screen))

    def windows(c):
        """The dark window strip: big tinted panes, short pleated curtains bunched at the pillars."""
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw("rubber", 1.4)
        face = "west" if x < 0 else "east"
        for k, (z0, z1) in enumerate(C.PANES[face]):
            if z0 - 0.5 <= z <= z1 + 0.5:
                state = rounded(z, z0, z1, y, BELT + 1, TOP - 1, r=1.6)
                fy = math.floor(y)
                if state == "in" and not (face == "west" and k == 0):
                    edge = min(z - z0, z1 - z)
                    if edge < 2.2 and fy > BELT + 3:
                        return raw("petal_purple", 1.0 if math.floor(z) % 2 else 0.5)   # curtains tied back
                    if fy >= TOP - 3:
                        return raw("petal_purple", 0.8 if math.floor(z) % 2 else 0.3)   # the valance
                if state == "in" and c.face == face and fy >= TOP - 6:
                    return raw("tint", 2.2)                       # a deeper tint along the top
                return glass_px(state, z, y, TOP - 1, ("rubber", 1.4), inside=c.face != face)
        return raw("rubber", 1.4)
    wp = Paint("glass", 2, noise=0, light=False, decal=windows)
    m.box("side_window", (-H, BELT, WF), (-H + 0.5, TOP, WB), wp)
    m.box("side_window", (H - 0.5, BELT, D1), (H, TOP, WB), wp)

    def back_window(c):
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw("pearl", 2.13)
        return glass_px(rounded(x, -W + 2, W - 2, y, 34, TOP - 1, r=2.6), x, y, TOP - 1, ("pearl", 2.13), 13,
                        inside=c.face == "north")
    m.box("back_window", (-W, 32, BK - 0.5), (W, TOP, BK), Paint("glass", 2, noise=0, light=False, decal=back_window))

    # "rabbit ear" mirrors: arms from the roof's front corners, bending forward and down in front of the windscreen
    arm = Paint("rubber", 2, noise=0, rim=0.4, decal=lambda c: ("rubber", 2.4 if c.face == "up" else 1.4))
    for s in (1, -1):
        xa, xb = sorted(((H - 1) * s, (H + 0.5) * s))
        m.box("mirror_arm", (xa, BAND - 2, F - 5), (xb, BAND - 1, WF), arm)
        m.box("mirror_arm", (xa, 36, F - 5), (xb, BAND - 2, F - 4), arm)
        xa, xb = sorted(((H - 2) * s, (H + 1.5) * s))
        m.box("mirror", (xa, 29, F - 6.5), (xb, 36, F - 5), Paint("blade", 2, noise=0, decal=mirror_face))

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        if c.face == "south" and math.floor(y) == SCREEN + 2 and -13 <= x < -3:
            return ("glass", 3) if math.floor(x) in (-12, -6) else ("tint", 1)
        if c.face == "up":
            return ("rubber", 1.6)
        return None
    m.box("dash", (-W, SCREEN, WF), (W, SCREEN + 4, WF + 4), DARK(decal=dash))
    m.box("column", (-8.5, FLOOR, WF + 4), (-7.5, SCREEN + 6, WF + 5), Paint("rubber", 2, noise=0))
    m.box("wheel", (-12, SCREEN + 6, WF + 2), (-4, SCREEN + 6.5, WF + 9), Paint("rubber", 1, noise=0, light=False, decal=steering))

    driver = dict(bolster=("rubber", 2), insert=("rubber", 3), stitch=("steel", 2))
    seat = dict(bolster=("aubergine", 2), insert=(P, 2.6), stitch=(P, 3.6))
    chair(m, *C.SEATS[0], FLOOR, C.SEAT_Y, C.SEAT_Y + 13, driver)
    for x, z in C.SEATS[1:]:
        chair(m, x, z, FLOOR, C.SEAT_Y, C.SEAT_Y + 15, seat, w=5, head=5)

    # parcel racks along both sides over the seats, a grab rail by the door
    rack = Paint("steel", 2, noise=0, rim=0.4, decal=lambda c: ("steel", 3 if c.face == "up" else 2))
    for s in (1, -1):
        xa, xb = sorted(((IN - 5) * s, IN * s))
        m.box("rack", (xa, TOP - 4, -48), (xb, TOP - 3, 62), rack)
    m.box("pole", (5, 14, D1 + 0.5), (6, TOP - 2, D1 + 1.5), Paint("hazard", 2, noise=0, rim=0.3))

    # --- the door (part): white below with the livery across it, a tall pane, a chrome handle --------
    def door(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.nu <= 1 or c.nv <= 1:
            return raw("rubber", 1.4)
        state = rounded(z, D0 + 1, D1 - 1, y, 21, TOP - 1, r=2.0)
        if state != "out":
            return glass_px(state, z, y, TOP - 1, ("rubber", 1.4), 17)
        if fy == 16 and D1 - 4 < z < D1 - 1.5:
            return raw("blade", 3.6)
        got = outside(c, x, y, z)
        return raw(got[0], got[1])
    kit["door"].box("door", (H - 0.5, BOTTOM, D0), (H, TOP, D1), Paint("glass", 2, noise=0, light=False, decal=door))

    held(m)
    return kit


# ------------------------------------------------------------------------------------------------

KITS = {"mini_bus": mini_kit, "limousine_bus": limousine_kit}
PARTS = {"mini_bus": ("door_driver", "door"), "limousine_bus": ("door",)}


def _body(name):
    return lambda: KITS[name]().body


def _part(name, part):
    return lambda: KITS[name]().all()[f"{name}_{part}"]


ALL = {}
for _n in KITS:
    ALL[_n] = _body(_n)
    for _p in PARTS[_n]:
        ALL[f"{_n}_{_p}"] = _part(_n, _p)
