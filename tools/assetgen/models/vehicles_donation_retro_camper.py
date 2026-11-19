"""
후원 · 레트로 캠핑밴 (retro_camper): a cute, boxy 1970s-style camper van. An original design (no real make, no badge,
no split V-front): a flat face with a one-piece upright windscreen under a short brow, two round lamps in chrome
rings, a chrome "smile" of three curved slots between them, chrome bumpers; two-tone paint (mint lower, cream upper)
parted by a wavy line with a thin orange stripe under it, all the way round. A folded pop-up tent roof (a cream cap
on a band of pleated orange canvas) carrying a surfboard on two bars, a rolled awning in its case along the right
roof edge. Side windows with little tied-back curtains, a sliding side door on the right, a spare wheel under a
cover on the back, white-wall tyres with chrome dish caps. Inside: two front seats and two benches (six seats,
orange plaid), a little wooden kitchenette behind the back bench.

The same rules as models/vehicles.py and models/vehicles_premium.py (read their docs first): drawn at real size
(16 px a block, the ground centre at (0, 0, 0), front = north (-z), +x = the van's right, the driver on the left),
shrunk by 2 and shown at vehicle.body-scale 2. A Kit: the body and its moving parts drawn where they rest.

Tuning in mind: every colour of the paint is an argument (lower, upper, stripe, canvas, curtain, seat, board), so
another paint is one more call. The wheel is a model of its own (wheel_retro_camper).

Parts: door_l, door_r (the cab doors, hinged at their front edge: they swing out) and slide_door (the right side's
sliding door: it comes out 1 px and slides back 12 px along the side).

Seats (vehicles.yml): a seated player's head reaches 20.4 px above the seat; the seats are at y 16.64 (the cushions'
top at 14.5), so heads reach 37.0, under the roof's underside at 38.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl
from models.vehicles import CHROME, DARK, PLASTIC, CLEAR, lerp, lamp, raw, upholstery, bumper_decal, _held_display, _wheel
from models.vehicles_premium import Kit, _mirror, _steering

NAME = "retro_camper"

# ------------------------------------------------------------------------------------------------
# Dimensions (drawn px)
# ------------------------------------------------------------------------------------------------
W, L = 16, 36                      # half width, half length of the box
WF, WR, SLOT = -25, 23, 7.5        # wheel centres along z, half the wells' length
WX = 13.5                          # the wheels' centre across (vehicles.yml 0.84375)
BOTTOM, FLOOR, ARCH, BELT = 4, 13, 14, 20
GLASS0, GLASS1, ROOF = 21.5, 35, 38
FD = (-32.5, -15)                  # the cab doors (both sides): between their seams
SD = (-14, 4)                      # the sliding door (right side)
SKIN = 15                          # the side skin is x 15..16 (the doors too)


def wheel_retro_camper():
    """0.75 block across (12 px), 4 px wide: a fat tyre with a broad white wall, a polished dish hub cap."""
    return _wheel("wheel_retro_camper", 12, 4, cap="blade", cap_d=7, plain=True, whitewall=True)


def retro_camper(lower="celadon", upper="cream", stripe="fox", canvas="fox", curtain="fox", seat="fox", board="water",
                 name=NAME):
    return camper_kit(lower, upper, stripe, canvas, curtain, seat, board, name).body


def camper_kit(lower="celadon", upper="cream", stripe="fox", canvas="fox", curtain="fox", seat="fox", board="water",
               name=NAME):
    """lower / upper: the two paint colours (under / over the wavy line); stripe: the thin line under the wave;
    canvas: the pop-up tent's folded canvas and the awning's stripes; curtain: the little curtains; seat: the plaid
    of the seats; board: the surfboard (None: no board, the bars stay)."""
    kit = Kit(name, ("door_l", "door_r", "slide_door"))
    m = kit.body
    LO, UP = lower, upper
    PAINT = Paint(UP, 2, noise=0, grad=0)

    def wave(s):
        return 17.8 + 1.3 * math.sin(2 * math.pi * s / 22)

    def tone(y, s):
        """the two-tone paint at height y, s along the wave (z on the sides, |x| across the ends)"""
        yb = wave(s)
        if y >= yb:
            return (UP, lerp(y, yb, ROOF, 1.9, 2.7))
        if y >= yb - 1.1:
            return (stripe, 3.0)
        if y >= yb - 1.8:
            return (LO, 3.2)                                     # a light edge under the stripe
        return (LO, lerp(y, BOTTOM, yb, 1.2, 2.5))

    def well(p):
        return abs(p[0]) > 10.4 and p[1] < ARCH + 0.1 and (abs(p[2] - WF) <= SLOT + 0.1 or abs(p[2] - WR) <= SLOT + 0.1)

    def interior(y, z):
        # the inside of the walls: pale wood panelling, a dark skirting at the floor
        if y < FLOOR + 1:
            return ("wood_dark", 1.4)
        return ("wood", 2.6 if math.floor(z) % 4 else 1.8)

    def skin(c):
        p = c.p
        x, y, z = p
        fy, fz, ax = math.floor(y), math.floor(z), abs(x)
        if c.face == "down":
            return ("rubber", 0)
        if well(p) and c.face != "up":
            return ("rubber", 0)
        if c.face == "up":
            if ax < SKIN and -34.5 < z < 35 and y < BELT - 0.1:
                if y > FLOOR + 0.5:
                    return ("rubber", 1)                         # tubs, sills
                return ("wood", 2.2 if math.floor(x) % 3 else 1.4)      # the plank floor
            if z < -34.5:
                return (UP, 2.8)                                 # the ledge under the windscreen
            return (UP, 2.6)
        if c.face in ("east", "west"):
            outward = (x > 0) == (c.face == "east")
            if not outward or ax < SKIN - 0.1:
                return interior(y, z)
            if fy == BOTTOM:
                return (LO, 0.6)                                 # the sill's shadowed foot
            # the arches' lips: a darker rim round each well
            for wz in (WF, WR):
                if y < ARCH + 1 and abs(z - wz) <= SLOT + 1 and not well(p):
                    return (LO, 0.7)
            # seams: the cab doors, the sliding door (right), its rail
            seams = (FD[0], FD[1] - 1) + ((SD[0], SD[1] - 1) if x > 0 else ())
            if fz in seams and fy >= BOTTOM:
                return ("rubber", 1.2)
            if x > 0 and fz == SD[1] + 3 and BELT - 3 <= fy < BELT:
                return ("blade", 3.2)                            # the sliding door's rail end
            if fy == 17 and fz in (FD[1] - 4, FD[1] - 3):
                return ("blade", 4.2)                            # cab door handle
            if x > 0 and fy == 16 and fz in (SD[0] + 2, SD[0] + 3, SD[0] + 4):
                return ("blade", 4.2)                            # sliding door handle
            if x < 0 and 22 <= fz <= 24 and 10 <= fy <= 12:
                return ("blade", 3.6 if (fz, fy) != (23, 11) else 2.4)  # the fuel cap
            if fz == -L and 10 <= fy <= 11:
                return ("fox", 3.4)                              # side markers
            if fz == L - 1 and 10 <= fy <= 11:
                return ("signal_red", 1.6)
            return tone(y, z)
        if c.face == "north":
            ax = abs(x)
            # the smile: one curved slot between the lamps, rimmed in chrome, turned up at its ends
            if ax < 7.5 and z < -35.9:
                y0 = 9.2 + 2.6 * (ax / 7) ** 2
                k = y - y0
                if 0 <= k < 3:
                    if ax >= 6.5 or k < 1 or k >= 2:
                        return ("blade", 4.2 if k >= 2 else 3.2)
                    return ("rubber", 0) if math.floor(x) % 2 else ("tint", 1.0)
            if fy == BOTTOM:
                return (LO, 0.6)
            return tone(y, ax)
        if c.face == "south":
            if fy == BOTTOM:
                return (LO, 0.6)
            if z > L - 0.1 and fy == BELT - 1 and abs(x) < 13:
                return ("blade", 3.6)                            # the tailgate's chrome strip
            return tone(y, abs(x))
        return None
    paint = PAINT(decal=skin)

    m.group("body")
    m.box("nose", (-W, BOTTOM, -L), (W, BELT, WF - SLOT), paint)
    m.box("tail", (-W, BOTTOM, WR + SLOT), (W, BELT, L), paint)
    m.box("core", (-10.5, BOTTOM, WF - SLOT), (10.5, FLOOR, WR + SLOT), paint)
    for s in (1, -1):
        def xs(a, b):
            return sorted((a * s, b * s))
        xa, xb = xs(10.5, SKIN)
        m.box("floor", (xa, BOTTOM, WF + SLOT), (xb, FLOOR, WR - SLOT), paint)
        for wz in (WF, WR):
            m.box("tub", (xa, 12.5, wz - SLOT), (xb, ARCH, wz + SLOT), paint)
        sa, sb = xs(SKIN, W)
        # the side skin: the body's wherever no door is
        door_l = kit["door_l"] if s < 0 else kit["door_r"]
        door_l.box("door", (sa, ARCH, FD[0]), (sb, BELT, FD[1]), paint)
        door_l.box("door_low", (sa, BOTTOM, WF + SLOT), (sb, ARCH, FD[1]), paint)
        if s > 0:
            m.box("skin", (sa, BOTTOM, FD[1]), (sb, BELT, SD[0]), paint)
            kit["slide_door"].box("door", (sa, BOTTOM, SD[0]), (sb, BELT, SD[1]), paint)
            m.box("skin", (sa, BOTTOM, SD[1]), (sb, BELT, WR - SLOT), paint)
        else:
            m.box("skin", (sa, BOTTOM, FD[1]), (sb, BELT, WR - SLOT), paint)
        m.box("skin", (sa, ARCH, WR - SLOT), (sb, BELT, WR + SLOT), paint)

    # chrome bumpers (the plate on each: the name plates stand there), small rubber-tipped overriders
    bumper = Paint("blade", 2, noise=0, rim=0.3)
    m.box("bumper", (-W - 0.5, 5, -L - 1.5), (W + 0.5, 8.5, -L), bumper(decal=bumper_decal(plate_w=9, plate_rows=(5, 7), strip=-9)))
    m.box("bumper", (-W - 0.5, 5, L), (W + 0.5, 8.5, L + 1.5), bumper(decal=bumper_decal(plate_w=9, plate_rows=(5, 7), strip=-9)))
    for s in (1, -1):
        xa, xb = sorted((9 * s, 10.5 * s))
        m.box("overrider", (xa, 4.5, -L - 2), (xb, 9.5, -L - 1.5), bumper(decal=lambda c: ("rubber", 1) if c.p[1] > 8.5 else None))
        m.box("overrider", (xa, 4.5, L + 1.5), (xb, 9.5, L + 2), bumper(decal=lambda c: ("rubber", 1) if c.p[1] > 8.5 else None))

    # the spare wheel on the back, under a cover: the cover cream with a mint ring and an orange line, a chrome middle
    def cover(c):
        x, y = c.p[0], c.p[1]
        r = math.hypot(x, y - 14.5)
        if c.face == "south":
            if r > 5.2:
                return ("rubber", 1)                             # the tyre's edge round the cover
            if r < 1.3:
                return ("blade", 4.4 if x < 0 and y > 14.5 else 3.4)
            if 3.4 <= r < 4.3:
                return (LO, 2.4)
            if 2.8 <= r < 3.4:
                return (stripe, 3)
            return (UP, 3.0 if (x < 0 and y > 14.5) else 2.4)
        return ("rubber", 1.2) if c.face != "up" else ("rubber", 2)
    cyl(m, "spare", (0, 14.5), 12, L, L + 3.5, Paint("rubber", 1, noise=0, decal=cover), axis="z")
    m.box("spare_mount", (-1, 13.5, L - 0.01), (1, 15.5, L + 0.01), PLASTIC)

    # mirrors on the cab doors: a chrome arm, a round-cornered chrome head
    for s in (1, -1):
        d = kit["door_l"] if s < 0 else kit["door_r"]
        xa, xb = sorted((W * s, (W + 1.5) * s))
        d.box("mirror_arm", (xa, BELT + 2, -31.5), (xb, BELT + 3, -30.5), CHROME)
        xa, xb = sorted(((W + 1.5) * s, (W + 3) * s))
        d.box("mirror", (xa, BELT + 1.5, -31.5), (xb, BELT + 5, -30), CHROME(decal=_mirror))

    m.group("lights")

    def round_lamp(cx, cy):
        def decal(c):
            if c.face != "north":
                return ("blade", 3)
            r = math.hypot(abs(c.p[0]) - cx, c.p[1] - cy)
            if r < 1.1:
                return ("lamp", 4.8)
            if r < 2.2:
                return ("lamp", 3.8 if c.p[1] > cy else 3.2)
            return ("blade", 4.6 if c.p[1] > cy else 3.6)          # the chrome ring
        return decal
    for s in (1, -1):
        cyl(m, "headlight", (11 * s, 13.5), 6, -L - 0.75, -L, Paint("lamp", 3, decal=round_lamp(11, 13.5), **lamp()), axis="z")
        xa, xb = sorted((10 * s, 12.5 * s))
        m.box("turn_lamp", (xa, 8.75, -L - 0.4), (xb, 10, -L), Paint("fox", 3, decal=lambda c: ("fox", 4.2 if c.v == 0 else 3.2), **lamp()))

    def tail_lamp(c):
        # two round-ish lamps stacked: amber over red, a chrome rim
        if c.face != "south":
            return ("blade", 3)
        if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1) or c.v == 3:
            return ("blade", 4.0)
        return ("fox", 3.8) if c.v < 3 else ("signal_red", 2.4 if c.v == 4 else 1.4)
    for s in (1, -1):
        xa, xb = sorted((11 * s, 14.5 * s))
        m.box("tail_lamp", (xa, 10, L), (xb, 17, L + 0.5), Paint("signal_red", 1, decal=tail_lamp, **lamp()))

    m.group("cabin")

    def pane_decal(curtains, door_side=None):
        """The side glass x 15.5..16 from BELT to ROOF: cream pillars and roof band, glass openings at windows()."""
        def decal(c):
            x, y, z = c.p
            fy = math.floor(y)
            if c.nu <= 1 or c.nv <= 1:
                return raw(UP, 2)
            if y >= ROOF - 1:
                return raw("blade", 3) if fy == ROOF - 1 else raw(UP, 2)   # the drip rail
            if y >= GLASS1:
                return raw(UP, 2)
            if y < GLASS0:
                return raw(UP, 2)
            for (z0, z1, curt) in windows(x):
                if z0 <= z < z1:
                    u = z - z0
                    if u < 1 or z1 - z < 1 or y < GLASS0 + 1 or y >= GLASS1 - 1:
                        return raw("rubber", 1)                  # the seal
                    if curt and curtains:
                        # little curtains tied back at each side, a gathered valance along the top
                        n = z1 - z0
                        if y >= GLASS1 - 2:
                            return raw(curtain, 3 if math.floor(z) % 3 else 2)   # the valance, gathered
                        side = min(u - 1, z1 - 1 - z)
                        tie = GLASS0 + 5
                        reach = 1.2 + min(1.6, abs(y - tie) * 0.22)  # pinched at the tie, falling wider
                        if side < reach and n > 8:
                            if abs(y - tie) < 0.6:
                                return raw("cream", 3)               # the tie
                            return raw(curtain, 2 if side < 1 else 3)    # a fold down each
                    d = math.floor(z) - math.floor(y)
                    if (d % 13) in (0, 1) and (d % 26) < 2:
                        return raw("glass", 3)
                    return ("raw", CLEAR)
            return raw(UP, 2)                                  # a pillar
        return decal

    def windows(x):
        """(z0, z1, curtains) of the glass openings on the side at x"""
        out = [(FD[0] + 0.5, -23, False), (-22.5, FD[1] - 1, False)]       # the cab door: a vent wing, its window
        out += [(SD[0] + 1, SD[1] - 1, True)]                             # over the sliding door (and the same left)
        out += [(SD[1] + 1, 18, True), (19, 31, True)]
        return out

    pp = Paint("glass", 2, noise=0, light=False, decal=pane_decal(True))
    for s in (1, -1):
        xa, xb = sorted((15.5 * s, W * s))
        m.box("pillar_a", (xa, BELT, -L + 1), (xb, ROOF, FD[0]), pp)
        (kit["door_l"] if s < 0 else kit["door_r"]).box("window", (xa, BELT, FD[0]), (xb, ROOF, FD[1]), pp)
        if s > 0:
            m.box("side_glass", (xa, BELT, FD[1]), (xb, ROOF, SD[0]), pp)
            kit["slide_door"].box("window", (xa, BELT, SD[0]), (xb, ROOF, SD[1]), pp)
            m.box("side_glass", (xa, BELT, SD[1]), (xb, ROOF, L), pp)
        else:
            m.box("side_glass", (xa, BELT, FD[1]), (xb, ROOF, L), pp)
        # the rear corner pillar is closed (no glass behind z 31)

    # the windscreen: one flat upright pane set back 1 px behind the nose, cream frame, wipers
    def screen(c):
        u, v, nu, nv = c.u, c.v, c.nu, c.nv
        if nu <= 1 or nv <= 1:
            return raw(UP, 2)
        x, y = c.p[0], c.p[1]
        if y >= GLASS1 + 0.5 or y < GLASS0 or abs(x) > 14:
            return raw(UP, 3 if y >= GLASS1 else 2)
        if y >= GLASS1 - 0.5 or y < GLASS0 + 1 or abs(x) > 13:
            return raw("rubber", 1)
        if y >= GLASS1 - 2.5:
            return raw("tint", 2)                                # the sun strip
        if math.floor(y) == math.floor(GLASS0) + 1 and (-12 <= x < -3 or 2 <= x < 11):
            return raw("rubber", 0)                              # wipers
        d = math.floor(x) + math.floor(y)
        if d in (22, 23, 27):
            return raw("glass", 3)
        return ("raw", CLEAR)
    m.box("windscreen", (-15.5, BELT, -L + 1), (15.5, ROOF, -L + 1.5), Paint("glass", 2, noise=0, light=False, decal=screen))

    def back_glass(c):
        if c.nu <= 1 or c.nv <= 1:
            return raw(UP, 2)
        x, y = c.p[0], c.p[1]
        if y >= GLASS1 or y < GLASS0 + 1 or abs(x) > 12:
            return raw(UP, 3 if y >= GLASS1 else 2)
        if y >= GLASS1 - 1 or y < GLASS0 + 2 or abs(x) > 11:
            return raw("rubber", 1)
        if y >= GLASS1 - 2:
            return raw(curtain, 3 if math.floor(x) % 3 else 2)
        if abs(x) > 9.5 - min(1.5, abs(y - 26.5) * 0.2):
            if abs(y - 26.5) < 0.6:
                return raw("cream", 3)
            return raw(curtain, 2 if abs(x) > 10 else 3)
        d = math.floor(x) - math.floor(y)
        if d in (-30, -29, -24):
            return raw("glass", 3)
        return ("raw", CLEAR)
    m.box("back_glass", (-15.5, BELT, L - 0.5), (15.5, ROOF, L), Paint("glass", 2, noise=0, light=False, decal=back_glass))

    # the roof: a brow over the windscreen, a rounded top
    def roof(c):
        x, y, z = c.p
        if c.face == "down":
            return ("cream", 3.4) if abs(x) < 15.5 and abs(z) < L else (UP, 1.4)   # the headliner
        if c.face == "up":
            return (UP, 3.0 if 4 <= x < 7 else 2.6)
        return (UP, 2.8 if y > ROOF + 1 else 2.2)
    RP = Paint(UP, 2, noise=0, grad=0, decal=roof)
    m.box("roof", (-W, ROOF, -L - 1), (W, ROOF + 1, L), RP)
    m.box("roof_top", (-W + 1, ROOF + 1, -L), (W - 1, ROOF + 1.5, L - 0.5), RP)

    # the pop-up tent, folded: a band of pleated canvas under a cream cap; a little latch at the front
    TOP0 = ROOF + 1.5
    def pleats(c):
        x, z = c.p[0], c.p[2]
        along = z if c.face in ("east", "west") else x
        if c.face in ("up", "down"):
            return (canvas, 1)
        return (canvas, 3.2 if math.floor(along) % 2 else 2.2) if c.p[1] > TOP0 + 0.6 else (canvas, 1.4)
    m.box("tent_canvas", (-12, TOP0, -27), (12, TOP0 + 1.5, 29), Paint(canvas, 2, noise=0, decal=pleats))

    def cap(c):
        x, y, z = c.p
        if c.face == "up":
            return (UP, 3.2 if -10 <= x < -7 else 2.8)
        return (UP, 2.2)
    CP = Paint(UP, 2, noise=0, grad=0, decal=cap)
    m.box("tent_cap", (-12.5, TOP0 + 1.5, -28), (12.5, TOP0 + 2.5, 30), CP)
    m.box("tent_cap_top", (-11.5, TOP0 + 2.5, -27), (11.5, TOP0 + 3, 29), CP)
    m.box("tent_latch", (-1.5, TOP0 + 0.5, -28.25), (1.5, TOP0 + 2, -27.75), CHROME)

    # two bars across the cap, a surfboard strapped on them
    BAR = TOP0 + 3
    for zb in (-14, 16):
        m.box("rack_bar", (-11, BAR, zb), (11, BAR + 1, zb + 1.5), Paint("rubber", 1, noise=0, rim=0.3))
        for s in (1, -1):
            xa, xb = sorted((10 * s, 11.5 * s))
            m.box("rack_foot", (xa, TOP0 + 2.5, zb - 0.5), (xb, BAR + 1, zb + 2), PLASTIC)
    if board:
        BY = BAR + 1

        def surf(c):
            x, z = c.p[0], c.p[2]
            if c.face == "up":
                if any(zb - 0.5 <= z < zb + 2 for zb in (-14, 16)):
                    return ("rubber", 1.4)                       # the straps
                if abs(x) < 0.5:
                    return ("cream", 3.4)                        # a stripe nose to tail
                if abs(x) < 1.5 and z > 20:
                    return (board, 1.4)                          # the tail pad
                return (board, 3.2 if x < -1.5 else 2.6)
            if c.face == "down":
                return (board, 0.8)
            return ("cream", 3) if c.p[1] < BY + 0.5 else (board, 1.8)   # the rail
        bp = Paint(board, 2, noise=0, rim=0.4, decal=surf)
        for (hw, z0, z1) in ((1, -26, -24.5), (2, -24.5, -22), (3, -22, -18), (3.5, -18, 17), (3, 17, 21), (2, 21, 23)):
            m.box("surfboard", (-hw, BY, z0), (hw, BY + 1, z1), bp)
        m.box("fin", (-0.25, BY - 1, 20), (0.25, BY, 22.5), Paint(board, 1, noise=0))

    # the rolled awning in its case along the right roof edge, on two brackets
    def awning(c):
        x, y, z = c.p
        if c.face in ("north", "south"):
            return ("rubber", 1)
        if c.face == "east" and y < TOP0 + 0.5:
            return (canvas, 3.2) if math.floor(z) % 4 < 2 else ("cream", 3.2)   # the striped fabric peeking out
        return ("steel", 3.6 if c.face == "up" else 2.8)
    m.box("awning", (13, ROOF + 1, -24), (16, TOP0 + 2, 26), Paint("steel", 3, noise=0, rim=0.3, decal=awning))

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        if c.face == "south":
            if math.floor(y) == BELT and -11 <= x < -5:
                return ("cream", 4) if math.floor(x) in (-11, -9, -7) else ("tint", 1)   # round dials
            if math.floor(y) == BELT and 1 <= x < 7:
                return ("blade", 3.6) if math.floor(x) % 2 else ("tint", 1.2)          # the radio
            return (UP, 1.6) if y >= BELT + 1 else ("rubber", 1.2)
        if c.face == "up":
            return (UP, 1.8)
        return ("rubber", 1)
    m.box("dash", (-15, FLOOR + 4, -L + 1.5), (15, BELT + 1.5, -31), Paint(UP, 2, noise=0, decal=dash))
    m.box("column", (-7.5, BELT - 1, -31), (-6.5, BELT + 0.5, -29.5), PLASTIC)
    m.box("wheel", (-9.5, BELT, -29.5), (-4.5, BELT + 4, -29), Paint("rubber", 1, noise=0, light=False, decal=_steering(("cream", 4))))

    plaid = dict(bolster=(seat, 1.4), insert=(seat, 2.6), stitch=("cream", 3))

    def plaid_decal(lo, hi):
        base = upholstery(lo=lo, hi=hi, **plaid)

        def decal(c):
            got = base(c)
            if got == plaid["insert"] or (got[0] == seat and got[1] == plaid["insert"][1] - 1):
                x, y, z = c.p
                if math.floor(x) % 4 == 0 or (c.face != "up" and math.floor(y) % 4 == 0) or (c.face == "up" and math.floor(z) % 4 == 0):
                    return ("cream", 2.6)                        # the plaid's pale lines
            return got
        return decal

    def seat_box(nm, lo, hi):
        m.box(nm, lo, hi, Paint(seat, 2, noise=0, decal=plaid_decal(lo, hi)))

    CUSH = FLOOR + 1.5
    for s in (1, -1):
        xa, xb = sorted((2.5 * s, 12.5 * s))
        seat_box("front_seat", (xa, FLOOR, -16), (xb, FLOOR + 14, -14))
        seat_box("front_cushion", (xa, FLOOR, -24), (xb, CUSH, -16))
    for (zc, zb) in ((-7, 2), (10, 19)):
        seat_box("bench_cushion", (-14.5, FLOOR, zc), (14.5, CUSH, zb))
        seat_box("bench_back", (-14.5, FLOOR, zb), (14.5, FLOOR + 13, zb + 2))

    # the kitchenette behind the back bench: a wooden cupboard with a cream top and a little sink on the left, a
    # cool box on the right
    def cupboard(c):
        x, y, z = c.p
        if c.face == "up":
            if -13 <= x < -9 and 25 <= z < 29:
                return ("steel", 3.6 if (math.floor(x) + math.floor(z)) % 3 else 2.4)   # the sink
            return ("cream", 3.2)
        if c.face == "north":
            if y >= 23:
                return ("cream", 2.8)
            if math.floor(x) in (-10, -9) and math.floor(y) == 20:
                return ("blade", 4)                              # handles
            return ("wood", 1.4) if math.floor(x) in (-15, -8, -1) or math.floor(y) == FLOOR else ("wood", 2.4)
        return ("wood", 2)
    m.box("cupboard", (-15, FLOOR, 23), (-1, 24, 35.5), Paint("wood", 2, noise=0, decal=cupboard))
    m.box("cool_box", (4, FLOOR, 25), (14, 21.5, 34), Paint(LO, 2, noise=0, rim=0.4,
          decal=lambda c: ("cream", 3.4) if c.p[1] > 20 or c.face == "up" else (LO, 2.6)))
    m.box("cool_box_handle", (6, 21.5, 29), (12, 22, 30), PLASTIC)

    m.display = _held_display(m)
    return kit


def _part(fn, name):
    return lambda: fn().all()[name]


ALL = {
    NAME: retro_camper,
    "wheel_retro_camper": wheel_retro_camper,
    **{f"{NAME}_{p}": _part(camper_kit, f"{NAME}_{p}") for p in ("door_l", "door_r", "slide_door")},
}
