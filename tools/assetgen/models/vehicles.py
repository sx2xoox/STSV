"""
Vehicles (content/stsv/items/vehicles.yml): 경찰차 (a generic patrol car), 1톤 트럭 (a small cab-over pickup with
an open drop-side bed) and 골프 카트 (two seats, canopy, a bag rack) are driven; 리어카 (a two-wheeled wooden hand
cart with a big open bed and long pull handles) is pulled on foot. No real agency emblem, no brand.

Every vehicle is several models, each shown by its own item display that the plugin moves every tick
(plugin: dev.stsv.core.vehicle):
  body      <id>             the whole vehicle without its wheels. Bigger than a model may be (a car is 4.6 blocks
                             long): drawn at its real size and shrunk by 2 around the middle (BModel scale 2), the
                             item display scales it back up (vehicle.scale 2 in the item file), so a texel stays
                             1/16 block like every other model.
  wheels    wheel_road, wheel_cart, wheel_handcart   one wheel, hub at model (8, 8, 8), axle along x. The plugin
                             turns it about its axle (rolling) and, for front wheels, about y (steering).
  parts     police_lightbar_off / _a / _b   the roof light bar: dark, left (red) lit, right (blue) lit; the plugin
                             swaps them to flash. Attachment point (bottom middle) at model (8, 8, 8).
            truck_cargo_1..4  what lies on the truck's bed, by how full its storage is (25 / 50 / 75 / 100 %).
            hand_cart_cargo_1..4  the same for the hand cart's bed (crates of grain, tied sacks; full: a rope net).

The hand cart is drawn level, as it is while someone pulls it (its handles at hand height); left alone it tips
forward onto its handles (the plugin turns the body about the axle: vehicles.yml pull.rest-tilt).

Drawn coordinates (all models): 16 px a block, the vehicle's ground centre at (0, 0, 0), front = north (-z),
model +x = the vehicle's RIGHT side (seen from the front, +x is on the viewer's left). Korean cars drive on the
right, so the driver sits on the left (-x).

Seats are where the plugin seats players (vehicles.yml seats): a sitting player's head reaches 1.275 blocks above
the seat, so every roof clears that. Windows are thin cut-out planes (see-through, a dark rubber seal round the
glass, a reflection streak and a tinted sun strip), so riders show through them.

The detail is painted, not modelled (docs/ART_STYLE.md, 렌더 예산): the decals below draw the light on the
paint (a soft gradient up every side, a darker sill, a highlight along top edges and a reflection band across
hood, roof and boot), the panel seams, the trim (chrome bumpers with a rubber strip, black window seals), the
lamps (a bright core in a reflector, a chrome bezel, amber turn signals, red tail lamps with a lit centre), the
plates (blank, a few digit marks), the tyres' tread and sidewall and the seats' stitching. Only a few small boxes
were added for it: door handles, mirror arms, seat cushions and head rests, a steering column, mud flaps; on the
hand cart the slatted floor, the flared wall layers, leaf springs, the prop stand, a rope coil, a lantern and a plate.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl, h3
from stsv_assets.palette import ladder
from stsv_assets import presets

# ================================================================================================
# Paints
# ================================================================================================

WHITE = Paint("car_white", 2, noise=0, grad=0)
BLUE_CAB = Paint("truck_blue", 2, noise=0, grad=0)
IVORY = Paint("cream", 2, noise=0, grad=0)
DARK = Paint("rubber", 1, noise=0.02)
PLASTIC = Paint("rubber", 2, noise=0)
CHROME = Paint("blade", 2, noise=0, grad=0)
STEEL = Paint("steel", 3, noise=0, grad=0)
IRON = Paint("iron", 2, noise=0)
TIRE = Paint("rubber", 1, noise=0, rim=0.3)
GREEN = Paint("leaf_dark", 1, noise=0)
CLEAR = (0, 0, 0, 0)


def raw(ramp, step):
    return ("raw", ladder(ramp, step))


def lerp(y, y0, y1, a, b):
    """a at y0, b at y1, held beyond (a soft top-lit gradient over a panel, in ramp steps)."""
    t = max(0.0, min(1.0, (y - y0) / (y1 - y0)))
    return a + (b - a) * t


def lamp(on=True):
    """A lamp lens: glows, painted flat (its decal picks every colour)."""
    return dict(noise=0, light=False, glow=on)


# --- small pixel lettering --------------------------------------------------------------------------

# 3 x 5 digits (roof number, cart number)
DIGITS = {"1": [".#.", "##.", ".#.", ".#.", "###"], "2": ["##.", "..#", ".#.", "#..", "###"],
          "7": ["###", "..#", ".#.", ".#.", ".#."], "3": ["##.", "..#", ".#.", "..#", "##."]}


def digits_pixel(text, col, row):
    """A number in 3 x 5 digits with a 1 px gap, col / row from its top left."""
    if not (0 <= row < 5 and col >= 0):
        return False
    k, x = divmod(col, 4)
    if k >= len(text) or x == 3:
        return False
    return DIGITS[text[k]][row][x] == "#"


def plate(col, row, w, h):
    """A generic plate (no real format): white in a thin grey frame, a row of dark digit marks. None outside it."""
    if not (0 <= col < w and 0 <= row < h):
        return None
    if col in (0, w - 1):
        return ("car_white", 0)
    if row == h - 1:
        return ("car_white", 1)
    if 0 < row < h - 1 and 1 < col < w - 2 and col % 2 == 0:
        return ("tint", 1)                          # the digits, as marks
    return ("car_white", 3)


# --- glass ---------------------------------------------------------------------------------------------

def pane(c, frame=("rubber", 1), sun=0, wipers=False, streak=0.3):
    """A see-through pane (on a 0.5 px plane): a dark seal round it, a tinted sun strip `sun` rows deep along the
    top, a reflection streak (a wide and a thin diagonal line) a third of the way across, wipers resting along the
    bottom. Everything else is clear, so the riders show."""
    u, v, nu, nv = c.u, c.v, c.nu, c.nv
    if nu <= 1 or nv <= 1:
        return raw(*frame)                          # the pane's 0.5 px edges
    if u in (0, nu - 1) or v in (0, nv - 1):
        return raw(*frame)
    if v <= sun:
        return raw("tint", 2 if v < sun else 3)
    if wipers and v == nv - 2 and (2 <= u < nu // 2 - 1 or nu // 2 + 2 <= u < nu - 3):
        return raw("rubber", 0)
    d = u + v - round(nu * streak)
    if d == 0 and v < nv - 2:
        return raw("glass", 3)
    if d == 2 and v < nv - 2:
        return raw("glass", 2)
    return ("raw", CLEAR)


def glass(**kw):
    return Paint("glass", 2, noise=0, light=False, decal=lambda c: pane(c, **kw))


# --- seats -----------------------------------------------------------------------------------------------

def upholstery(bolster, insert, stitch, lo, hi):
    """Two-tone seat: bolsters (the outer 1.5 px and the top) in one colour, the insert in another with sewn
    pleats every 2 px, a dotted stitch line between them. lo / hi: the seat's drawn box."""
    def decal(c):
        x, y, z = c.p
        side = c.face in ("north", "south")
        if c.face in ("north", "south", "up"):
            a = x - lo[0], hi[0] - x
            edge = min(a)
            top = hi[1] - y if c.face != "up" else 9
            if edge < 1.5 or top < 1.2:
                return bolster
            if edge < 2.5 or (side and top < 2.2):
                return stitch if (math.floor(y) + math.floor(x)) % 2 == 0 else insert
            if side and math.floor(y) % 2 == 0:
                return (insert[0], insert[1] - 1)       # a pleat
            return insert
        return bolster
    return decal


# ================================================================================================
# Wheels (hub at model (8, 8, 8), axle along x; the plugin spins them about x)
# ================================================================================================

def _wheel(name, d, width, cap="blade", cap_d=None, spokes=5, holes=True):
    m = BModel(name, shift=(8, 8, 8), floor=None)
    r = d / 2
    cap_d = cap_d or d - 4

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        a = math.atan2(z, y)
        if c.face in ("east", "west"):
            # the sidewall: dark at the tread, a lighter bead round the rim, a fine ring between
            if rr > r - 0.9:
                return ("rubber", 0)
            if rr > r - 1.8:
                return ("rubber", 1)
            return ("rubber", 2)
        # the tread: blocks across it, staggered between the two halves, with a groove round the middle
        k = math.floor((a + math.pi) / (2 * math.pi) * d * 2.4)
        x = c.p[0]
        half = 0 if x < 0 else 1
        if (k + half) % 3 == 0:
            return ("rubber", 0)
        return ("rubber", 2 if (k + half) % 3 == 1 else 1)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, TIRE(decal=tire), axis="x")

    def hub(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        rc = cap_d / 2
        if c.face not in ("east", "west"):
            return (cap, 1)                          # the cap's thin rim
        if rr > rc - 0.8:
            return (cap, 1)                          # the rim lip
        if rr < 0.8:
            return (cap, 3) if holes else ("blade", 1)   # the centre cap
        a = math.atan2(z, y)
        t = (a * spokes / (2 * math.pi)) % 1.0
        if holes and 1.2 < rr < rc - 0.8 and 0.5 < t < 0.95:
            return ("iron", 0)                           # the holes between the spokes
        return (cap, 3 if (y > 0.5 and z < 0.5) else 2)  # spokes, the half towards the light shines
    p = Paint(cap, 2, noise=0, rim=0.2, ao=0.3, decal=hub)
    w2 = width / 2
    cyl(m, "hub", (0, 0), cap_d, w2, w2 + 0.5, p, axis="x")
    cyl(m, "hub", (0, 0), cap_d, -w2 - 0.5, -w2, p, axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


def wheel_road():
    """Car and truck wheel: 0.69 block across (11 px), 4 px wide, a five-spoke chrome hub cap."""
    return _wheel("wheel_road", 11, 4, cap="blade", cap_d=7)


def wheel_handcart():
    """The hand cart's wheel (diameter 12 px, 3 px wide): a fat rubber tyre with a tread and a bulging sidewall, a
    steel rim, eight spokes on a dark well (four bright, four in the shade between them), and a domed axle cap
    standing out on each side (the same model turns on both sides of the cart)."""
    m = BModel("wheel_handcart", shift=(8, 8, 8), floor=None)
    d, w2 = 12, 1.5
    # the well inside the rim (6 x 6 px round the axle, rows from the top, columns from the front): eight round steel
    # spokes, the four across two pixels thick with a lit side (L, towards the front / the top) and a shaded side (l),
    # the four diagonal ones one pixel (S); . the dark gap, H the hub (under the cap)
    well = ["S.Ll.S",
            ".SLlS.",
            "LLHHLL",
            "llHHll",
            ".SLlS.",
            "S.Ll.S"]

    def side(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        lit = y - 0.5 * z                                   # light from above and the front
        if c.face not in ("east", "west"):
            # the running face: chunky tread blocks, staggered either side of a groove round the middle
            a = math.atan2(z, y)
            k = math.floor((a + math.pi) / (2 * math.pi) * 24)
            col = round(c.p[0])
            if col == 0:
                return ("rubber", 0.4 if k % 2 else 1.6)
            return ("rubber", 2.4 if (k + (col > 0)) % 2 else 1.0)
        if rr > 4.4:
            # the tyre's sidewall: a lit bulge on the upper front, its shoulder darker, a moulded ring
            if rr > 5.2:
                return ("rubber", 1.4 if lit > 3 else 0.9 if lit > -3 else 0.5)
            return ("rubber", 2.8 if lit > 2.5 else 2.0 if lit > -2.5 else 1.3)
        if rr > 3.4:
            # the steel rim: a glint along its upper front, its far side in shadow
            return ("steel", 4.6 if lit > 2.5 else 3.4 if lit > -1.5 else 2.2)
        row, col = math.floor(3 - y), math.floor(z + 3)
        ch = well[row][col] if 0 <= row < 6 and 0 <= col < 6 else "."
        if ch in "SL":
            return ("blade", 2.0 if lit > 0 else 1.4)
        if ch == "l":
            return ("steel", 2.6 if lit > 0 else 2.0)
        if ch == "H":
            return ("steel", 3)
        return ("iron", -1.6 if lit > 1 else -1.0)          # the dark well, deepest under the rim's top
    cyl(m, "tyre", (0, 0), d, -w2, w2, Paint("rubber", 1, noise=0, rim=0.2, ao=0.2, decal=side), axis="x")

    def cap(c):
        y, z = c.p[1], c.p[2]
        if c.face in ("east", "west"):
            return ("blade", 3.4 if (y > 0 and z < 0) else 2.4)                  # a glint on the dome
        return ("steel", 3 if c.face == "up" else 2)
    cp = Paint("blade", 2, noise=0, rim=0, ao=0, decal=cap)
    m.box("hub", (w2, -1, -1), (w2 + 1, 1, 1), cp)
    m.box("hub", (-w2 - 1, -1, -1), (-w2, 1, 1), cp)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def wheel_cart():
    """Golf cart wheel: small and fat, 7 px across, 3 px wide, a white hub cap."""
    return _wheel("wheel_cart", 7, 3, cap="car_white", cap_d=5, holes=False)


# ================================================================================================
# 경찰차 (patrol car) 4.6 x 1.9 x 1.66 blocks
# ================================================================================================

def _slot(p, half_w, wheel_z, half_slot, top):
    """Is this texel inside a wheel well (the walls and the arch over a wheel)?"""
    return abs(p[0]) > half_w - 0.1 and p[1] < top + 0.1 and wheel_z - half_slot - 0.1 <= abs(p[2]) <= wheel_z + half_slot + 0.1


def bumper_decal(plate_w=0, plate_rows=(0, 0), strip=5):
    """Chrome bumper: a bright top edge, a dark rubber strip at row `strip`, a darker bottom; a plate in the middle
    of the front / back face (plate_w wide, its rows plate_rows (bottom, top))."""
    def decal(c):
        x, y, z = c.p
        row = math.floor(y)
        if c.face in ("north", "south"):
            if plate_w:
                col = math.floor(x + plate_w / 2)
                pr = plate_rows[1] - row
                got = plate(col, pr, plate_w, plate_rows[1] - plate_rows[0] + 1)
                if got:
                    return got
            if row == strip:
                return ("rubber", 1)
            if y > c.hi[1] - 1:
                return ("blade", 3)
            if y < c.lo[1] + 1:
                return ("blade", 1)
            return ("blade", 2)
        if c.face == "up":
            return ("blade", 3)
        if c.face == "down":
            return ("rubber", 1)
        return ("blade", 2) if row != strip else ("rubber", 1)
    return decal


def police_car():
    m = BModel("police_car", shift=(8, 8, 8), scale=2.0)
    W, L = 15, 36           # half width, half length (px)
    WZ, SLOT = 23, 6.5      # wheel centres at z = +-23, the wells 13 px long
    BELT = 15               # top of the lower body (bottom of the windows)
    DOOR = (-16, 3, 17)     # door seams (texel columns along z)

    def white(y):
        return ("car_white", lerp(y, 5, 14.5, 1.0, 2.6))

    def body(c):
        p = c.p
        x, y, z = p
        fz, fy = math.floor(z), math.floor(y)
        if _slot(p, 11, WZ, SLOT, 12) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if -15 <= z <= 23 and abs(x) < 13.5:
                # the floor between the seats, seen through the glass: carpet
                return ("rubber", 1 if fz % 4 else 0)
            if fz == -16 and (-12 <= x < -3 or 1 <= x < 10):
                return ("rubber", 0)                    # wipers at rest
            if fz == -17 and abs(x) < 12.5:
                return ("rubber", 1) if fz % 2 == 0 or math.floor(x) % 2 else ("tint", 1)   # the cowl vent
            if z < -16:
                # the hood: two creases (a dark line, a light line), a soft reflection band across it
                ax = abs(x)
                if 8 <= ax < 9:
                    return ("car_white", 1.4)
                if 3 <= x < 6:
                    return ("car_white", 3)
                return ("car_white", 2)
            if z > 24:
                # the boot lid: its seam, a reflection band
                if fz == 25 or (fz >= 25 and 13 <= abs(x) < 14):
                    return ("car_white", 0.6)
                if 3 <= x < 6:
                    return ("car_white", 3)
                return ("car_white", 2)
            return None
        if c.face in ("east", "west"):
            # navy rocker, a light-blue accent line, the blue stripe (unbroken along the car), door seams and handles,
            # the arch trim round the wells, marker lamps at the corners, the fuel door
            az = abs(z)
            if (fz in (-30, -17, 16, 29) and fy < 12 and 15.5 < az < 30.5) or (fy == 12 and 16 <= az < 30):
                return ("rubber", 1)                    # wheel arch trim
            if fy == 4:
                return ("police_blue", -1)
            if fz in DOOR and fy >= 5:
                return ("police_blue", 0) if 8 <= fy <= 10 else ("car_white", -1)
            if fz == -36 + 1 and 10 <= fy <= 11:
                return ("fox", 3)                       # amber side marker (front)
            if fz == 34 and 10 <= fy <= 11:
                return ("signal_red", 1)                # red side marker (rear)
            if c.face == "east" and 30 <= fz <= 32 and 11 <= fy <= 13:
                if fz == 31 and fy == 12:
                    return ("car_white", 2)
                return ("car_white", 0.4)               # fuel door
            if fy == 12 and (1 <= fz <= 2 or 14 <= fz <= 15):
                return ("tint", 0)                      # the handles' recesses
            if fy == 6:
                return ("signal_blue", 2)
            if 8 <= fy <= 10:
                return ("police_blue", 1 if fy < 10 else 1.4)
            return white(y)
        if c.face == "north":
            # grille in a chrome frame, the hood's front edge
            ax = abs(x)
            if ax < 7 and 8 <= fy <= 11:
                if fy in (8, 11) or ax >= 6:
                    return ("blade", 3 if fy == 11 else 2)
                return ("rubber", 0) if math.floor(x) % 2 == 0 else ("tint", 1.6 if fy == 10 else 1)
            if 8 <= fy <= 10 and 14 <= ax:
                return ("police_blue", 1)               # the stripe wraps round the corners
            if fy == 12 and ax < 13:
                return ("car_white", 0.8)               # the hood's shut line
            return white(y)
        if c.face == "south":
            ax = abs(x)
            if ax < 4.5 and 8 <= fy <= 11:
                return plate(math.floor(x + 4.5), 11 - fy, 9, 4)
            if fy == 12 and ax < 13:
                return ("car_white", 0.8)               # the boot lid's shut line
            if fy == 13 and ax < 1:
                return ("blade", 3)                     # the lock
            if 8 <= fy <= 10 and (5.5 <= ax < 8 or ax >= 14):
                return ("police_blue", 1)
            return white(y)
        return None
    paint = WHITE(decal=body)

    m.group("body")
    m.box("core", (-11, 4, -29.5), (11, 12, 29.5), paint)
    m.box("nose", (-W, 4, -L), (W, 12, -29.5), paint)
    m.box("tail", (-W, 4, 29.5), (W, 12, L), paint)
    for s in (1, -1):
        m.box("sill", (min(11 * s, W * s), 4, -16.5), (max(11 * s, W * s), 12, 16.5), paint)
    m.box("shoulder", (-W, 12, -L), (W, BELT, L), paint)
    # bumpers with the plates, door handles, mirrors on short arms
    m.box("bumper", (-14, 4, -L - 1), (14, 8, -L), CHROME(decal=bumper_decal(9, (4, 7))))
    m.box("bumper", (-14, 4, L), (14, 8, L + 1), CHROME(decal=bumper_decal()))
    for s in (1, -1):
        xa, xb = sorted((W * s, (W + 0.5) * s))
        for z0 in (1, 14):
            m.box("handle", (xa, 12.5, z0), (xb, 13, z0 + 2), CHROME)

    def mirror(c):
        if c.face == "south":
            # the mirror glass, in a dark rim
            if c.u in (0, c.nu - 1) or c.v in (0, c.nv - 1):
                return ("rubber", 1)
            return ("glass", 3 if c.u + c.v == 2 else 2)
        if c.face == "down" or c.p[1] < 15.5:
            return ("rubber", 1)
        return None
    for s in (1, -1):
        xa, xb = sorted((13.5 * s, 15 * s))
        m.box("mirror_arm", (xa, 15, -12.5), (xb, 16, -11.5), PLASTIC)
        xa, xb = sorted((15 * s, 18 * s))
        m.box("mirror", (xa, 15, -13), (xb, 17.5, -11), WHITE(decal=mirror))

    m.group("lights")

    def headlight(c):
        # chrome bezel, a reflector with a bright core, the amber turn signal at the outer end
        k = math.floor(abs(c.p[0]) - 8)
        fy = math.floor(c.p[1])
        if c.face != "north":
            return ("blade", 2)
        if k == 0:
            return ("blade", 3)
        if k <= 4:
            if fy == 9:
                return ("lamp", 4) if k in (2, 3) else ("lamp", 3)
            return ("lamp", 2) if k in (2, 3) else ("lamp", 1)
        return ("fox", 4) if fy == 9 else ("fox", 3)

    def taillight(c):
        # reverse lamp (white) inside, the red tail / brake lamp with a lit centre, amber turn signal outside
        k = math.floor(abs(c.p[0]) - 8)
        fy = math.floor(c.p[1])
        if c.face != "south":
            return ("signal_red", 0)
        if k == 0:
            return ("lamp", 3) if fy == 10 else ("blade", 3)
        if k <= 3:
            if fy == 10:
                return ("signal_red", 3) if k == 2 else ("signal_red", 2)
            return ("signal_red", 0) if fy == 9 else ("signal_red", 1)
        return ("fox", 4) if fy == 10 else ("fox", 3)
    for s in (1, -1):
        xa, xb = sorted((8 * s, 14 * s))
        m.box("headlight", (xa, 8, -L - 0.5), (xb, 11, -L), Paint("lamp", 3, decal=headlight, **lamp()))
        m.box("taillight", (xa, 9, L), (xb, 12, L + 0.5), Paint("signal_red", 1, decal=taillight, **lamp()))

    m.group("cabin")

    # the roof: a reflection band, the car's number (read from the air) behind the light bar
    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            col, row = math.floor(x + 3.5), math.floor(z - 8)
            if digits_pixel("12", col, row):
                return ("police_blue", 1)
            if 3 <= x < 6 and z < 1.5:
                return ("car_white", 3)
            return ("car_white", 2)
        if c.face in ("east", "west", "north", "south"):
            return ("car_white", 2.4) if y > 25.5 else ("rubber", 1)   # a dark drip rail under the roof's edge
        return None
    m.box("roof", (-13.5, 25, -5), (13.5, 26.5, 14), WHITE(decal=roof))
    m.box("windscreen", (-13, 15, -15), (13, 15 + 10 * math.sqrt(2), -14.5), glass(sun=2, wipers=True, streak=0.25),
          rotation=("x", 45, (0, 15, -15)))
    m.box("rear_window", (-13, 25 - 9 * math.sqrt(2), 14), (13, 25, 14.5), glass(sun=1, streak=0.55),
          rotation=("x", -45, (0, 25, 14)))

    def side_window(c):
        x, y, z = c.p
        front = -15 + (y - 15)          # the windscreen's edge at this height
        back = 39 - y                   # the rear window's
        if z < front - 0.3 or z > back + 0.3:
            return ("raw", CLEAR)       # outside the window's shape, its thin edges included
        if c.nu <= 1 or c.nv <= 1:
            return raw("car_white", 2)
        if z < front + 1.5 or z > back - 2 or y > 24.2:
            return raw("car_white", 2 if y < 24.2 else 3)   # A and C pillars, the roof rail
        if 2 <= z < 4:
            return raw("rubber", 1)     # B pillar (black)
        if z < front + 2.5 or z > back - 3 or y > 23.2 or y < 16 or 1 <= z < 5:
            return raw("rubber", 1)     # the seal round each pane
        if y > 22.2:
            return raw("tint", 3)       # sun strip
        d = math.floor(z) - math.floor(y)
        if d in (-25, -24, -12, -11):
            return raw("glass", 3)      # a reflection streak across each pane
        if d in (-22, -9):
            return raw("glass", 2)
        return ("raw", CLEAR)
    for s in (1, -1):
        m.box("side_window", (min(13 * s, 13.5 * s), BELT, -15), (max(13 * s, 13.5 * s), 25, 24),
              Paint("glass", 2, noise=0, light=False, decal=side_window))

    m.group("inside")

    def dash(c):
        x, y, z = c.p
        if c.face == "south":
            if -9 <= x < -5 and math.floor(y) == 16:
                return ("glass", 3) if math.floor(x) in (-9, -6) else ("tint", 1)   # two dials
            if -2 <= x < 2 and math.floor(y) == 16:
                return ("signal_blue", 2) if math.floor(x) % 2 else ("tint", 2)    # the radio's display
            return ("rubber", 1)
        if c.face == "up":
            return ("rubber", 2) if math.floor(z) == -11 and abs(x) < 11 else ("rubber", 1)   # defroster vent
        return None
    m.box("dash", (-13, BELT, -12), (13, BELT + 2, -9), DARK(decal=dash))
    m.box("column", (-7.5, BELT + 1, -9), (-6.5, BELT + 2.5, -8.5), PLASTIC)

    def steering(c):
        u, v = c.u, c.v
        if c.face not in ("north", "south"):
            return raw("rubber", 1)
        ring = ((v in (0, 3) and 1 <= u <= 3) or (v in (1, 2) and u in (0, 4)))
        if ring:
            return raw("rubber", 1 if v else 2)
        if v == 2:
            return raw("tint", 2 if u == 2 else 1)    # the spokes and hub
        return ("raw", CLEAR)
    m.box("wheel", (-9.5, BELT + 2, -8.5), (-4.5, BELT + 6, -8), Paint("rubber", 1, noise=0, light=False, decal=steering))
    seat = dict(bolster=("rubber", 2), insert=("indigo", 1), stitch=("indigo", 3))
    for s in (1, -1):
        lo, hi = (min(2.5 * s, 12 * s), BELT, 1), (max(2.5 * s, 12 * s), BELT + 7, 3)
        m.box("front_seat", lo, hi, Paint("rubber", 2, "weave", noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        m.box("cushion", (lo[0], BELT, -5), (hi[0], BELT + 1.5, 1),
              Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        xa, xb = sorted((5 * s, 10 * s))
        m.box("headrest", (xa, BELT + 7, 1.5), (xb, BELT + 9, 2.5), Paint("rubber", 2, noise=0))
        lo, hi = (min(2.5 * s, 12 * s), BELT, 14), (max(2.5 * s, 12 * s), BELT + 6, 16)
        m.box("rear_seat", lo, hi, Paint("rubber", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
    m.box("rear_cushion", (-12, BELT, 9), (12, BELT + 1.5, 14),
          Paint("rubber", 2, noise=0, decal=upholstery(lo=(-12, 0, 9), hi=(12, 0, 14), **seat)))
    m.display = _held_display(m)
    return m


def _held_display(m):
    """Hand / ground / gui transforms that show the (already halved) body small, like a toy."""
    d = presets.furniture(gui_scale=0.36)
    for ctx in ("thirdperson_righthand", "firstperson_righthand", "firstperson_lefthand", "ground"):
        d[ctx] = dict(d[ctx])
        d[ctx]["scale"] = [round(v / 2.5, 3) for v in d[ctx]["scale"]]
    return d


# ================================================================================================
# 1톤 트럭 (cab-over 1-ton truck, open drop-side bed) 4.9 x 1.8 x 2.1 blocks
# ================================================================================================

TRUCK = {"front": -39, "cab_back": -13, "bed_front": -12, "back": 38, "half": 14, "bed_half": 14.5,
         "front_axle": -27, "rear_axle": 17, "bed_floor": 13}


def light_truck():
    m = BModel("light_truck", shift=(8, 8, 8), scale=2.0)
    T = TRUCK
    F, CB, BF, BK, H, BH = T["front"], T["cab_back"], T["bed_front"], T["back"], T["half"], T["bed_half"]
    FA, RA = T["front_axle"], T["rear_axle"]
    WIN = 20            # bottom of the cab windows
    ROOF = 31           # roof bottom (a seated head reaches 20 + 1.275 * 16 = 32.4 at seat height 0.78: see seats)
    SLOT = 6.5
    DOOR = CB - 13      # the door's front seam (texel column); its back is the cab's back corner

    def blue(y):
        return ("truck_blue", lerp(y, 7, 19.5, 1.1, 2.7))

    def cab(c):
        x, y, z = c.p
        fy, fz, ax = math.floor(y), math.floor(z), abs(x)
        if ax > 9.9 and y < 12.1 and FA - SLOT - 0.1 <= z <= FA + SLOT + 0.1 and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if ax < 13.5 and F + 1 < z < CB - 0.5:
                return ("rubber", 1 if fz % 3 else 0)   # the ribbed cab floor, seen through the glass
            return ("truck_blue", 2.6)
        if c.face == "north":
            # a flat face: grille between the lamps in a chrome frame, a chrome trim line, a vent under the glass
            if ax < 7 and 9 <= fy <= 12:
                if fy in (9, 12) or ax >= 6:
                    return ("blade", 3 if fy == 12 else 2)
                return ("rubber", 0 if fy % 2 == 0 else 1)
            if fy == 13 and ax < 13:
                return ("blade", 3)                   # chrome trim across the face
            if fy == 17 and ax < 8 and math.floor(x) % 2 == 0:
                return ("truck_blue", 0.4)            # vent slots under the windscreen
            if fy == 6:
                return ("truck_blue", 0.6)
            return blue(y)
        if c.face in ("east", "west"):
            if (fz in (FA - 8, FA + 7) and fy < 12) or (fy == 12 and FA - 7 <= fz <= FA + 6):
                return ("rubber", 1)                  # arch trim
            if fz == DOOR and fy >= 7:
                return ("truck_blue", 0.2)            # the door seam
            if fz == CB - 1 and fy >= 7:
                return ("truck_blue", 0.6)            # the door's back edge
            if fy == 15 and CB - 6 <= fz <= CB - 4:
                return ("tint", 0) if fz < CB - 4 else ("blade", 3)   # handle recess, its lock
            if fy == 16 and CB - 6 <= fz <= CB - 5:
                return ("blade", 3)                   # the handle's chrome lip
            if fy == 6:
                return ("rubber", 1) if DOOR < fz < CB - 1 else ("truck_blue", 0.4)   # the step under the door
            if fy == 7:
                return ("truck_blue", 1)
            if fy == 8:
                return ("car_white", 3)               # a pale pinstripe
            if F + 1 <= fz <= F + 2 and 10 <= fy <= 11:
                return ("fox", 3)                     # amber side repeater
            return blue(y)
        if c.face == "south":
            return ("truck_blue", 1.4)
        return None
    paint = BLUE_CAB(decal=cab)

    m.group("cab")
    # lower cab with wells over the front wheels, the window band, roof
    m.box("cab_core", (-10, 6, F), (10, WIN, CB), paint)
    for s in (1, -1):
        xa, xb = sorted((10 * s, H * s))
        m.box("cab_side", (xa, 6, F), (xb, WIN, FA - SLOT), paint)
        m.box("cab_side", (xa, 12, FA - SLOT), (xb, WIN, FA + SLOT), paint)
        m.box("cab_side", (xa, 6, FA + SLOT), (xb, WIN, CB), paint)
    m.box("bumper", (-13.5, 5, F - 1), (13.5, 9, F), STEEL(decal=_truck_bumper(True)))

    def headlight(c):
        k = math.floor(abs(c.p[0]) - 8)
        fy = math.floor(c.p[1])
        if c.face != "north":
            return ("blade", 2)
        if fy == 12:
            return ("fox", 4) if k in (1, 2, 3) else ("fox", 3)     # amber turn signal on top
        if k in (0, 4):
            return ("blade", 3)                                    # chrome bezel
        if fy == 10:
            return ("lamp", 4) if k == 2 else ("lamp", 3)
        return ("lamp", 2) if k == 2 else ("lamp", 1)
    for s in (1, -1):
        xa, xb = sorted((8 * s, 13 * s))
        m.box("headlight", (xa, 9, F - 0.5), (xb, 13, F), Paint("lamp", 3, decal=headlight, **lamp()))
        xa, xb = sorted((H * s, (H + 1.5) * s))
        m.box("mirror_arm", (xa, 23, F + 1), (xb, 23.5, F + 2), CHROME)
        xa, xb = sorted(((H + 1.5) * s, (H + 2.5) * s))

        def mirror(c):
            if c.face == "south":
                if c.v in (0, c.nv - 1):
                    return ("rubber", 1)
                return ("glass", 3 if c.v == 2 else 2)
            return None
        m.box("mirror", (xa, 21, F + 0.5), (xb, 25.5, F + 2.5), PLASTIC(decal=mirror))
    rake = 11 * math.tan(math.radians(22.5))
    m.box("windscreen", (-13.5, WIN, F + 0.5), (13.5, WIN + 11 / math.cos(math.radians(22.5)), F + 1),
          glass(sun=0, wipers=True, streak=0.2), rotation=("x", 22.5, (0, WIN, F + 0.5)))

    def window(c):
        x, y, z = c.p
        front = F + 0.5 + (y - WIN) * math.tan(math.radians(22.5))
        if z < front - 0.3:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw("truck_blue", 2)
        if z < front + 1 or y > ROOF - 1 or z > CB - 5:
            return raw("truck_blue", 2 if y < ROOF - 1 else 3)    # pillars, rail, the cab's back corner
        if z < front + 2 or y > ROOF - 2 or z > CB - 6 or y < WIN + 1:
            return raw("rubber", 1)                                # the seal
        if y > ROOF - 4:
            return raw("tint", 3)                                  # sun strip
        d = (math.floor(z) - math.floor(y)) % 13
        if d in (0, 1) and y > WIN + 3:
            return raw("glass", 3)
        if d == 3 and y > WIN + 3:
            return raw("glass", 2)
        return ("raw", CLEAR)
    for s in (1, -1):
        xa, xb = sorted((13.5 * s, H * s))
        m.box("side_window", (xa, WIN, F + 0.5), (xb, ROOF, CB), Paint("glass", 2, noise=0, light=False, decal=window))

    def back_wall(c):
        x, y = c.p[0], c.p[1]
        if c.face == "south" and abs(x) < 6 and WIN + 3 <= y < ROOF - 2:
            if abs(x) >= 5.5 or y >= ROOF - 2.5 or y < WIN + 3.5:
                return ("rubber", 1)
            return ("glass", 2) if (math.floor(x) - math.floor(y)) % 7 == 0 else ("tint", 1)
        if c.face == "south":
            return ("truck_blue", 1.6)
        return None
    m.box("cab_back", (-13.5, WIN, CB - 1), (13.5, ROOF, CB), BLUE_CAB(decal=back_wall))

    # the white roof cap: ribs pressed into it, a visor over the windscreen with a dark underside
    def roof(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(x) < 12 and math.floor(z) % 5 == 0 and z > F + 7:
                return ("car_white", 1.4)
            if abs(x) < 12 and math.floor(z) % 5 == 1 and z > F + 7:
                return ("car_white", 3)
            return ("car_white", 2)
        if c.face == "down":
            return ("rubber", 1)
        return ("car_white", 2.2)
    m.box("roof", (-H, ROOF, F + 0.5 + rake), (H, ROOF + 1.5, CB), WHITE(decal=roof))
    m.box("visor", (-H, ROOF + 0.5, F - 0.5), (H, ROOF + 1.5, F + 0.5 + rake), WHITE(decal=roof))

    m.group("inside")
    m.box("dash", (-13.5, WIN, F + 2), (13.5, WIN + 2, F + 5), DARK)
    m.box("column", (-7.5, WIN + 1, F + 5), (-6.5, WIN + 2.5, F + 5.5), PLASTIC)

    def steering(c):
        u, v = c.u, c.v
        if c.face not in ("north", "south"):
            return raw("rubber", 1)
        if (v in (0, 3) and 1 <= u <= 3) or (v in (1, 2) and u in (0, 4)):
            return raw("rubber", 1 if v else 2)
        if v == 2:
            return raw("tint", 2 if u == 2 else 1)
        return ("raw", CLEAR)
    m.box("wheel", (-9.5, WIN + 2, F + 5.5), (-4.5, WIN + 6, F + 6), Paint("rubber", 1, noise=0, light=False, decal=steering))
    seat = dict(bolster=("iron", 2), insert=("denim", 1), stitch=("denim", 3))
    for s in (1, -1):
        lo, hi = (min(2 * s, 12 * s), WIN, CB - 4), (max(2 * s, 12 * s), WIN + 8, CB - 1)
        m.box("seat_back", lo, hi, Paint("iron", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))
        m.box("cushion", (lo[0], WIN, CB - 9), (hi[0], WIN + 1.5, CB - 4),
              Paint("iron", 2, noise=0, decal=upholstery(lo=lo, hi=hi, **seat)))

    m.group("bed")
    m.box("frame", (6, 7, CB), (9, 11, BK - 1), IRON)
    m.box("frame", (-9, 7, CB), (-6, 11, BK - 1), IRON)

    def deck(c):
        x, z = c.p[0], c.p[2]
        fx = math.floor(x)
        if c.face == "up":
            # pressed ribs along the bed: a lit ridge, its shadow, plain floor between; a soft sheen near the back
            k = (fx + 1) % 4
            if k == 0:
                return ("steel", 4)
            if k == 1:
                return ("steel", 1.6)
            return ("steel", lerp(z, BF, BK, 2.4, 3.0))
        if c.face in ("east", "west", "north", "south"):
            return ("iron", 1.5) if math.floor(z) % 8 == 0 and c.face in ("east", "west") else ("steel", 1.4)
        return ("iron", 1)
    m.box("deck", (-BH, 11, BF), (BH, 13, BK), STEEL(decal=deck))

    def boards(c):
        x, y, z = c.p
        fy, fz, fx = math.floor(y), math.floor(z), math.floor(x)
        if c.face == "up":
            return ("steel", 4)
        if c.face == "down":
            return ("iron", 1)
        # stake posts dividing the drop side into panels, hinges along the bottom, latches at the top of each post
        if c.face in ("east", "west"):
            if (fz - BF - 1) % 16 in (0, 15) and abs(x) > BH - 0.6:
                if fy == 17:
                    return ("blade", 3)                 # latch lever
                return ("steel", 4) if (fz - BF - 1) % 16 == 0 else ("steel", 1.6)
            if fy == 13 and (fz - BF - 1) % 8 in (3, 4):
                return ("iron", 1) if (fz - BF - 1) % 8 == 3 else ("blade", 3)   # hinge, its pin
        if c.face in ("north", "south") and abs(abs(x) - 12) < 0.6 and abs(z) > 30:
            if fy == 17:
                return ("blade", 3)                     # tailgate latches
            return ("steel", 4)
        if c.face == "south" and abs(z) > 30 and fy == 13 and fx % 6 in (2, 3):
            return ("iron", 1) if fx % 6 == 2 else ("blade", 3)
        # two pressed ribs along each panel: a light ridge over a dark line
        if fy in (15, 17):
            return ("steel", 4)
        if fy in (14, 16):
            return ("steel", 2.2)
        return ("steel", lerp(y, 13, 18.5, 2.6, 3.4))
    sides = STEEL(decal=boards)
    top = T["bed_floor"] + 5.5
    m.box("headboard", (-BH, 13, BF), (BH, top, BF + 1), sides)
    m.box("tailgate", (-BH, 13, BK - 1), (BH, top, BK), sides)
    for s in (1, -1):
        xa, xb = sorted(((BH - 1) * s, BH * s))
        m.box("drop_side", (xa, 13, BF + 1), (xb, top, BK - 1), sides)

    def guard(c):
        # the cab guard over the headboard: a frame of bars (see-through between them)
        x, y, z = c.p
        if c.nu <= 1 or c.nv <= 1:
            return raw("iron", 2)
        if abs(x) > 12.5 or y > ROOF - 1 or math.floor(x + 13.5) % 4 == 0:
            if y > ROOF - 1:
                return raw("steel", 4)
            return raw("steel", 3 if c.face in ("north", "south") and math.floor(x + 13.5) % 4 == 0 else 2)
        return ("raw", CLEAR)
    m.box("guard", (-13.5, top, BF), (13.5, ROOF, BF + 0.5), Paint("iron", 2, noise=0, light=False, decal=guard))
    m.box("rear_bumper", (-13.5, 6.5, BK - 1), (13.5, 9.5, BK), STEEL(decal=_truck_bumper(False)))

    def taillight(c):
        k = math.floor(abs(c.p[0]) - 10)
        fy = math.floor(c.p[1])
        if c.face != "south":
            return ("signal_red", 0)
        if k == 0:
            return ("fox", 4) if fy == 8 else ("fox", 3)      # amber inside
        if fy == 8:
            return ("signal_red", 3) if k == 2 else ("signal_red", 2)
        return ("signal_red", 1) if fy == 7 else ("signal_red", 0)
    for s in (1, -1):
        xa, xb = sorted((10 * s, 13.5 * s))
        m.box("taillight", (xa, 7, BK), (xb, 9.5, BK + 0.5), Paint("signal_red", 1, decal=taillight, **lamp()))
        # mud flaps behind the rear wheels, hung from the deck
        xa, xb = sorted((9.5 * s, 14 * s))

        def flap(c):
            if c.face in ("north", "south") and math.floor(c.p[1]) == 9:
                return ("blade", 2)                   # a plain bright band (no brand)
            if c.face in ("north", "south") and math.floor(c.p[1]) == 3:
                return ("rubber", 0)
            return None
        m.box("mud_flap", (xa, 3, RA + 7), (xb, 11, RA + 7.5), Paint("rubber", 1, noise=0, decal=flap))
    m.display = _held_display(m)
    return m


def _truck_bumper(front):
    """The truck's grey steel bumper: a lit top, a black rubber strip, a plate in the middle (front or back)."""
    def decal(c):
        x, y = c.p[0], c.p[1]
        fy = math.floor(y)
        if c.face in ("north", "south"):
            if abs(x) < 4.5:
                got = plate(math.floor(x + 4.5), (8 if front else 9) - fy, 9, 4 if front else 3)
                if got:
                    return got
            if fy == (6 if front else 7):
                return ("rubber", 1)
            if y > c.hi[1] - 1:
                return ("steel", 4)
            return ("steel", 2.6)
        if c.face == "up":
            return ("steel", 4)
        return ("steel", 2)
    return decal


# ================================================================================================
# 골프 카트 (golf cart: two seats, canopy, bag rack) 2.4 x 1.3 x 1.97 blocks
# ================================================================================================

def golf_cart():
    m = BModel("golf_cart", shift=(8, 8, 8), scale=2.0)
    H = 9.5                   # half width of the body
    WZ = 12.5                 # wheel centres at z = +-12.5
    SEAT_TOP = 10
    ROOF = 30.5
    NUMBER = "7"

    def cream(y):
        return ("cream", lerp(y, 4, 12.5, 1.0, 2.7))

    def roundel(col, row):
        """The cart's number, a dark green 3 x 5 decal (None where it has no pixel)."""
        if 0 <= col < 3 and digits_pixel(NUMBER, col, row):
            return ("leaf_dark", 1)
        return None

    def body(c):
        x, y, z = c.p
        fy, fz = math.floor(y), math.floor(z)
        if c.face == "down":
            return ("rubber", 0)
        if c.face == "up":
            if -9 <= z <= 9 and abs(x) < 9.4:
                return ("rubber", 1 if fz % 3 else 0)   # a ribbed rubber floor mat
            if z < -9 and fz == -11 and abs(x) < 8:
                return ("cream", 1.2)                   # the cowl's hinge line
            return ("cream", 2.4)
        if c.face in ("east", "west"):
            if -16 <= fz <= -14 and fy >= 8:
                col = (fz + 16) if c.face == "west" else (-14 - fz)
                got = roundel(col, 12 - fy)
                if got:
                    return got
            if fy == 9 and y >= 9 and not -17 <= fz <= -13:
                return ("leaf_dark", 1.6)               # a green pinstripe (round the number)
            if y < 5 or (fy == 7 and 7.5 <= y):
                return ("cream", 0.6)                   # darker sill
            return cream(y)
        if c.face == "north":
            ax = abs(x)
            if fy in (8, 9) and 5 <= ax < 8:
                return ("fox", 3) if fy == 9 else ("cream", 1)   # amber signal under each headlight
            got = roundel(math.floor(1.5 - x), 12 - fy) if -2 <= x < 2 else None
            if got:
                return got
            if fy == 7 and y >= 7.5:
                return ("cream", 1)
            return cream(y)
        if c.face == "south":
            return cream(y)
        return None
    paint = IVORY(decal=body)

    m.group("body")
    # front cowl over the front wheels (narrow below their tops), the floor, the seat box, the rear deck
    m.box("cowl_low", (-6.5, 3.5, -19), (6.5, 7.5, -9), paint)
    m.box("cowl", (-H, 7.5, -19), (H, 13, -9), paint)
    m.box("floor", (-H, 3.5, -9), (H, 5, 2), paint)
    m.box("seat_box", (-H, 3.5, 2), (H, SEAT_TOP - 1.5, 9), paint)
    m.box("rear_low", (-6.5, 3.5, 9), (6.5, 7.5, 19), paint)
    m.box("rear_deck", (-H, 7.5, 9), (H, 10, 19), paint)

    def rubber_bumper(c):
        if c.face == "up" or (c.face in ("north", "south") and c.p[1] > 5):
            return ("rubber", 2)
        return None
    m.box("bumper", (-8, 4, -20), (8, 6, -19), DARK(decal=rubber_bumper))
    m.box("rear_bumper", (-8, 4, 19), (8, 6, 20), DARK(decal=rubber_bumper))

    def headlight(c):
        k = math.floor(abs(c.p[0]) - 5)
        if c.face != "north":
            return ("blade", 2)
        if math.floor(c.p[1]) == 11:
            return ("lamp", 4) if k == 1 else ("lamp", 3)
        return ("lamp", 2) if k == 1 else ("lamp", 1)

    def taillight(c):
        k = math.floor(abs(c.p[0]) - 5)
        if c.face != "south":
            return ("signal_red", 0)
        if k == 0:
            return ("fox", 3)
        return ("signal_red", 3) if k == 1 and math.floor(c.p[1]) == 8 else ("signal_red", 1)
    for s in (1, -1):
        xa, xb = sorted((5 * s, 8 * s))
        m.box("headlight", (xa, 10, -19.5), (xb, 12, -19), Paint("lamp", 3, decal=headlight, **lamp()))
        m.box("taillight", (xa, 8, 19), (xb, 9, 19.5), Paint("signal_red", 1, decal=taillight, **lamp()))

    m.group("seat")
    seat = dict(bolster=("leaf_dark", 0), insert=("leaf_dark", 2), stitch=("leaf_dark", 3.4))
    lo, hi = (-9, SEAT_TOP - 1.5, 2.5), (9, SEAT_TOP, 9)

    m.box("cushion", lo, hi, Paint("leaf_dark", 1, noise=0, decal=_split_seat(lo, hi, seat)))
    blo, bhi = (-9, 10, 9), (9, 18, 10.5)
    m.box("backrest", blo, bhi, Paint("leaf_dark", 1, noise=0, decal=_split_seat(blo, bhi, seat)))

    def dash(c):
        x, z = c.p[0], c.p[2]
        if c.face == "up":
            if math.floor(z) == -10 and math.floor(x) in (3, 6):
                return ("rubber", 0)                    # cup holders
            return ("rubber", 1)
        if c.face == "south" and math.floor(x) == 0 and math.floor(c.p[1]) == 14:
            return ("blade", 3)                         # the key
        return None
    m.box("dash", (-8, 13, -11), (8, 15, -9), DARK(decal=dash))
    m.box("column", (-6, 13, -9), (-5, 16, -8), PLASTIC)

    def steering(c):
        u, v = c.u, c.v
        if c.face not in ("up", "down"):
            return raw("rubber", 1)
        if (v in (0, 2) and 1 <= u <= 3) or (v == 1 and u in (0, 4)):
            return raw("rubber", 2 if v == 0 else 1)
        if v == 1:
            return raw("tint", 2 if u == 2 else 1)
        return ("raw", CLEAR)
    m.box("wheel", (-8, 16, -9), (-3, 16.5, -6), Paint("rubber", 1, noise=0, light=False, decal=steering))

    m.group("canopy")

    def strut(c):
        # a polished tube: the face towards the light shines, the far one is dark
        if c.face in ("north", "west"):
            return ("blade", 3)
        if c.face in ("south", "east"):
            return ("blade", 1.5)
        return None
    struts = Paint("blade", 2, noise=0, decal=strut)
    for s in (1, -1):
        xa, xb = sorted((8 * s, 9 * s))
        m.box("front_strut", (xa, 13, -10.5), (xb, ROOF, -9.5), struts)
        m.box("rear_strut", (xa, 10, 10.5), (xb, ROOF, 11.5), struts)

    def canopy(c):
        x, y, z = c.p
        if c.face == "up":
            if abs(abs(x) - 9) < 0.5 or abs(z + 13.5) < 0.5 or abs(z - 18.5) < 0.5:
                return ("cream", 3)                      # a light piped edge
            if math.floor(z) % 8 == 0:
                return ("leaf_dark", 0.6)                # seams between the canopy's panels
            if 3 <= x < 6:
                return ("leaf_dark", 2.2)                # a soft sheen
            return ("leaf_dark", 1.4)
        if c.face == "down":
            # underside: darker, the frame's cross bars, shadow towards the middle
            if abs(abs(x) - 8.5) < 0.6 or math.floor(z) in (-10, 11):
                return ("blade", 1)
            return ("leaf_dark", 0 if abs(x) < 7 and -11 < z < 14 else 0.6)
        if c.face in ("north", "south", "east", "west"):
            return ("cream", 3) if y > ROOF + 0.5 else ("cream", 1.4)
        return None
    m.box("roof", (-10, ROOF, -14), (10, ROOF + 1.5, 19), GREEN(decal=canopy))

    m.group("bag rack")
    for s in (1, -1):
        xa, xb = sorted((6 * s, 7 * s))
        m.box("rack_post", (xa, 10, 18), (xb, 20, 19), struts)
    m.box("rack_bar", (-7, 20, 18), (7, 21, 19), struts)

    def rack_plate(c):
        if c.face == "up" and math.floor(c.p[0]) % 2 == 0:
            return ("rubber", 0)
        return None
    m.box("rack_plate", (-7, 10, 12), (7, 10.5, 18), Paint("rubber", 2, noise=0, decal=rack_plate))

    def bag(c):
        x, y, z = c.p
        fy = math.floor(y)
        if c.face == "up":
            return ("rubber", 0)                         # the open top (dividers)
        if fy >= 23:
            return ("rubber", 1)                         # the top collar
        if fy in (21, 13):
            return ("car_white", 3)                      # white bands round the bag
        if c.face == "north" and 15 <= y < 20 and -3 <= x < 0:
            return ("fabric_red", 1 if fy in (15, 19) else 1.6)   # a zipped pocket
        if c.face == "west" and 14 <= y < 22 and math.floor(z) == 15:
            return ("rubber", 2)                         # the carry strap
        if fy == 10:
            return ("rubber", 1)                         # its foot
        return None
    cyl(m, "bag", (-1.5, 15), 5, 10.5, 24, Paint("fabric_red", 2, "weave", noise=0, decal=bag))
    for (x, z, h) in ((-3, 14, 27), (-0.5, 16, 28), (-1.5, 13.5, 26.5)):
        m.box("club", (x, 24, z), (x + 1, h, z + 1), Paint("blade", 2, noise=0))
    # knitted head covers on the woods (navy with a yellow band, no brand), a chrome iron head
    def cover(ramp):
        def decal(c):
            if math.floor(c.p[1]) == 28 and c.face != "up":
                return ("hazard", 1)
            if c.face == "up":
                return (ramp, 3)
            return None
        return Paint(ramp, 1, "weave", noise=0, decal=decal)
    m.box("club_head", (-3.5, 27, 13.5), (-1, 30, 15.5), cover("denim"))
    m.box("club_head", (-1.5, 26.5, 11.5), (0.5, 29.5, 13.5), cover("crimson"))

    def iron_head(c):
        if c.face == "north":
            return ("blade", 3)
        return ("blade", 1.6)
    m.box("club_head", (-1, 28, 15.5), (1.5, 29.5, 17), Paint("blade", 2, noise=0, decal=iron_head))
    m.display = _held_display(m)
    return m


def _split_seat(lo, hi, seat):
    """The cart's bench: two seats (left and right of x = 0), each two-tone, a cream piping between them."""
    left = upholstery(lo=(lo[0], lo[1], lo[2]), hi=(-0.5, hi[1], hi[2]), **seat)
    right = upholstery(lo=(0.5, lo[1], lo[2]), hi=(hi[0], hi[1], hi[2]), **seat)

    def decal(c):
        x = c.p[0]
        if abs(x) < 0.5:
            return ("cream", 3)                      # the piping between the two seats
        return left(c) if x < 0 else right(c)
    return decal


# ================================================================================================
# 리어카 (a two-wheeled wooden hand cart, pulled on foot) 2.3 x 1.65 blocks of bed, 3.4 blocks with its handles
# ================================================================================================

# Drawn px, ground centre (the axle, under the bed's middle) at (0, 0, 0), handles to the north (-z, the cart's front).
# The puller stands between the handles 1.9 blocks ahead of the axle (vehicles.yml pull.hitch) with the cross bar
# just in front of them at hand height; the shafts are 17 px apart inside (a player with arms is 16 px wide).
#
# The bed flares: every layer of its walls sits half a pixel further out than the one below it (sill, lower board,
# upper board), capped by a wide rim rail, so the walls lean out without any turned part. The body is warm plank
# wood (plush), the frame (sills, rim rail, shafts, cross members) dark wood; iron brackets hold every corner.
CART = {"x0": 11, "z0": 15.5, "floor": 12.5, "deck": 14, "rail": (8.5, 10), "rail_y": (10.5, 12.5), "tip": -36,
        "rail_end": 16, "hub": 6, "stand_z": (-21, -20)}
BODY, FRAME = "plush", "wood_dark"
# each wall layer: (name, inner offset, outer offset, y0, y1), offsets from the floor's edge (x0 / z0)
CART_WALLS = (("sill", 0, 1, 12.5, 14), ("board_lo", 0.5, 1.5, 14, 16.5), ("board_hi", 1, 2, 16.5, 18.5),
              ("rim", 0.5, 2.5, 18.5, 20))


def _grain(c, axis, s=0):
    """Wood grain as a step offset: one shade per line along the axis, a dark knot now and then."""
    a = "xyz".index(axis)
    q = [k for k in (0, 1, 2) if k != a]
    if h3(*c.i, s + 12) < 0.012:
        return -1
    r = h3(c.i[q[0]], c.i[q[1]], 0, s + 11)
    return -0.8 if r < 0.22 else 0.7 if r > 0.84 else 0


def _cart_wall(layer, y0, y1, half, end):
    """The paint of one wall layer of the bed: a board (or the sill / the rim rail) along a side (end False: it runs
    along z) or across an end. half: how far it reaches along its length (to its corners). Iron corner brackets
    with bolts, iron straps, a butt joint, a scuffed top edge, grime and mud low down."""
    frame = layer in ("sill", "rim")
    ramp = FRAME if frame else BODY
    straps = (-5, 5) if end else (-8.5, 8.5)
    joint = {"board_lo": -3.5, "board_hi": 4.5}.get(layer, 99) * (-0.6 if end else 1)
    axis = "x" if end else "z"

    def decal(c):
        x, y, z = c.p
        along = x if end else z
        out = c.face in (("north", "south") if end else ("east", "west"))
        outward = out and ((z * c.n[2]) if end else (x * c.n[0])) > 0
        g = _grain(c, axis, 3 if frame else 0)
        if c.face == "up":
            if layer == "rim":
                if abs(along) > half - 2.5:
                    return ("iron", 2.4)                          # the iron corner cap
                ding = h3(math.floor(x), 7, math.floor(z), 5)
                return (ramp, 2.8 + g * 0.5 + (0.6 if ding < 0.1 else 0))   # worn pale by hands and loads
            return (ramp, 3.2 + (0.5 if h3(math.floor(x), 1, math.floor(z), 4) < 0.15 else 0))
        if c.face == "down":
            return (ramp, 0.8 if frame else 1.2)
        if not out:
            return (ramp, 1.6 + g)                                # end grain at a corner
        fy = math.floor(y)
        mid = math.floor((y0 + y1) / 2 - 0.01)
        # iron corner brackets: a bent plate round the corner, a bolt in each board, a little rust under it
        if abs(along) > half - 2.5:
            k = half - abs(along)
            if k > 2.0:
                return ("iron", 0.8)                              # the plate's edge
            if layer != "rim" and abs(k - 1.2) < 0.6 and fy == mid:
                return ("steel", 4)                               # a bolt head
            if layer != "rim" and abs(k - 1.2) < 0.6 and fy == mid - 1 and h3(math.floor(along), fy, 2, 9) < 0.5:
                return ("terracotta", 0.2)                        # a rust streak under it
            return ("iron", 3.2 if fy >= y1 - 1 else 2.6)
        # iron straps up the walls, a bolt through each board
        for st in straps:
            if abs(along - st) < 0.75 and outward:
                if layer != "sill" and fy == mid and abs(along - st) < 0.5:
                    return ("steel", 3.6)
                return ("iron", 2.8 if fy >= y1 - 1 else 2.2)
        if layer == "rim":
            # bolts along the rim rail into the board below it, an iron capping strip along its top
            if outward and fy == math.floor(y1 - 0.01):
                return ("iron", 3.2) if h3(math.floor(along), 3, 4, 7) > 0.15 else ("iron", 2.4)
            if outward and math.floor(along + half) % 6 == 3:
                return ("steel", 3.4)
            return (FRAME, 2.4 + g)
        if layer == "sill":
            # the frame sill: dark, mud splashed up from the wheels
            mud = h3(math.floor(along), fy, 1, 13)
            if outward and mud < 0.25:
                return ("soil", 1.4 if mud < 0.1 else 2.2)
            return (FRAME, 1.8 + g)
        # a board: a butt joint, a lit top edge with dings, the shadow of the board above, grime low down
        if abs(along - joint) < 0.5:
            return (BODY, 0.2)
        if outward and abs(along - joint) < 1.5 and fy == mid:
            return ("iron", 2.4)                                  # the nails either side of the joint
        if fy >= y1 - 1:
            ding = h3(math.floor(along), fy, 6, 15)
            return (BODY, 2.8 + (0.6 if ding > 0.2 else -0.4))
        if fy < y0 + 1:
            return (BODY, 1.0 if outward else 1.6)                # in the shadow of the layer above / below
        if not outward:
            scrape = h3(math.floor(along / 3), fy, 9, 19)
            return (BODY, 2.2 + g + (0.7 if scrape < 0.12 else 0))    # scraped pale by the loads
        if layer == "board_lo" and fy < y0 + 2 and h3(math.floor(along / 2), fy, 8, 17) < 0.3:
            return (BODY, 1.2)                                    # grime along the bottom
        return (BODY, 2.1 + g - (0.3 if layer == "board_lo" else 0))
    return Paint(ramp, 2, noise=0, rim=0.6, decal=decal)


def hand_cart():
    m = BModel("hand_cart", shift=(8, 8, 8), scale=2.0)
    C = CART
    X0, Z0, FL, DECK = C["x0"], C["z0"], C["floor"], C["deck"]
    R0, R1 = C["rail"]
    RY0, RY1 = C["rail_y"]
    TIP, END = C["tip"], C["rail_end"]

    m.group("bed")

    # the floor: five slats along the bed with gaps between them (the cross members show through)
    def slat(i):
        tone = (0.4, -0.2, 0.2, -0.4, 0.1)[i]
        xa = -X0 + i * 4.5

        def decal(c):
            x, y, z = c.p
            if c.face == "up":
                if any(abs(abs(z) - k) < 0.5 for k in (6.25, 13.75)) and math.floor(x - xa) in (1, 2):
                    return ("iron", 2.2)                          # nail heads over the cross members
                if abs(z) > Z0 - 1:
                    return (BODY, 1.6)                            # dark end grain against the end sills
                wear = 0.6 if abs(x) < 6 and abs(z) < 12 else 0
                stain = h3(math.floor(x / 2), 0, math.floor(z / 3), 21)
                return (BODY, 2.3 + tone + wear + _grain(c, "z", i) - (0.8 if stain < 0.06 else 0))
            return (BODY, 0.6 if c.face == "down" else 0.8)       # the gaps' walls: in shadow
        return Paint(BODY, 2, noise=0, rim=0.4, decal=decal)
    for i in range(5):
        xa = -X0 + i * 4.5
        m.box("slat", (xa, FL, -Z0), (xa + 4, DECK, Z0), slat(i))

    # the walls: sill, two boards and the rim rail, each layer a little further out (the flare)
    for (layer, a, b, y0, y1) in CART_WALLS:
        side = _cart_wall(layer, y0, y1, Z0 + b, end=False)
        endp = _cart_wall(layer, y0, y1, X0 + a, end=True)
        for s in (1, -1):
            xa, xb = sorted(((X0 + a) * s, (X0 + b) * s))
            m.box(layer, (xa, y0, -(Z0 + b)), (xb, y1, Z0 + b), side)
            za, zb = sorted(((Z0 + a) * s, (Z0 + b) * s))
            m.box(layer + "_end", (-(X0 + a), y0, za), (X0 + a, y1, zb), endp)

    m.group("frame")

    def rail(c):
        x, y, z = c.p
        g = _grain(c, "z", 5)
        if c.face in ("north", "south"):
            return (FRAME, 1.6)
        if TIP + 2.5 < z < TIP + 9:
            # the grips: darkened and polished by hands, a cord whipping at each end
            if abs(z - (TIP + 3)) < 0.5 or abs(z - (TIP + 8.5)) < 0.5:
                return ("burlap", 1.4)
            return ("leather", 1.4 + (0.9 if c.face == "up" and math.floor(z) % 3 == 0 else 0))
        for k in (-16, 15):
            if abs(z - k) < 0.5:
                return ("iron", 1.8)                              # iron clamps where the bed sits on the frame
        return (FRAME, (2.6 if c.face == "up" else 1.8) + g)
    rails = Paint(FRAME, 2, noise=0, decal=rail)
    for s in (1, -1):
        xa, xb = sorted((R0 * s, R1 * s))
        m.box("rail", (xa, RY0, TIP + 1), (xb, RY1, END), rails)

    def ferrule(c):
        if c.face == "north":
            return ("iron", 1.2) if abs(c.p[1] - 11.5) < 1 and abs(abs(c.p[0]) - 9.25) < 0.8 else ("steel", 2.6)
        return ("steel", 3.4 if c.face == "up" else 2.6)
    for s in (1, -1):
        xa, xb = sorted(((R0 - 0.5) * s, (R1 + 0.5) * s))
        m.box("ferrule", (xa, RY0 - 0.5, TIP), (xb, RY1 + 0.5, TIP + 1), Paint("steel", 2, noise=0, rim=0.6, decal=ferrule))

    def crossbar(c):
        x = c.p[0]
        if abs(abs(x) - 7.5) < 0.6:
            return ("iron", 1.8)                                  # iron straps where it meets the shafts
        if abs(x) < 6 and c.face != "down":
            return ("leather", 1.2 + (1.0 if math.floor(x) % 2 == 0 and c.face == "up" else 0))
        return (FRAME, 2.2 + _grain(c, "x", 6))
    m.box("crossbar", (-R0, RY0, TIP + 1), (R0, RY1, TIP + 2.5), Paint(FRAME, 2, noise=0, decal=crossbar))

    # cross members under the floor, between the shafts (the shafts are the frame's two long beams)
    def member(c):
        if abs(abs(c.p[0]) - 8) < 0.5:
            return ("iron", 1.6)
        return (FRAME, 1.4 + _grain(c, "x", 7))
    for z in (-14.5, -7, 5.5, 13):
        m.box("member", (-R0, 11, z), (R0, FL, z + 1.5), Paint(FRAME, 2, noise=0, decal=member))

    m.group("axle")

    def iron_part(c):
        if c.face == "up":
            return ("iron", 2.6)
        return None
    m.box("axle", (-9.5, C["hub"] - 0.75, -0.75), (9.5, C["hub"] + 0.75, 0.75), IRON(decal=iron_part))

    def hanger(c):
        x, y, z = c.p
        if c.face in ("east", "west") and abs(abs(z) - 0.75) < 0.3 and math.floor(y) in (7, 10):
            return ("blade", 2.2)                                 # bolts through the plate
        return ("iron", 2.4 if c.face == "up" else 1.6)
    for s in (1, -1):
        xa, xb = sorted((7.5 * s, 8.5 * s))
        m.box("hanger", (xa, 4.5, -1.5), (xb, FL, 1.5), Paint("iron", 2, noise=0, decal=hanger))

    def leaf(c):
        if c.face in ("east", "west", "north", "south"):
            return ("steel", 2.6 if (c.p[1] % 1) > 0.5 else 1.6)
        return ("steel", 2.2)

    def pad(c):
        if c.face in ("north", "south", "east", "west") and abs(abs(c.p[2]) - 1.75) < 0.5:
            return ("iron", 0.6)                                  # the U-bolts round the spring
        return ("iron", 2.2)
    for s in (1, -1):
        xa, xb = sorted((R0 * s, R1 * s))
        m.box("spring", (xa, 10, -7), (xb, RY0, 7), Paint("steel", 2, noise=0, decal=leaf))
        m.box("spring", (xa, 9.5, -5), (xb, 10, 5), Paint("steel", 2, noise=0, decal=leaf))
        m.box("spring_pad", (xa, 8.5, -2.5), (xb, 9.5, 2.5), Paint("iron", 2, noise=0, decal=pad))

    # the prop stand under the front: two legs and a foot bar; it meets the ground when the cart tips onto it
    SZ0, SZ1 = C["stand_z"]

    def stand(c):
        y = c.p[1]
        if math.floor(y) == 5 and c.face != "up":
            return ("rubber", 1.4)                                # a rubber shoe on the foot bar
        if y > 9.5 and c.face in ("north", "south"):
            return ("iron", 1.4)                                  # the hinge bracket
        return ("steel", 2.8 if c.face == "up" else 2.0)
    sp = Paint("steel", 2, noise=0, rim=0.5, decal=stand)
    for s in (1, -1):
        xa, xb = sorted((8.75 * s, 9.75 * s))
        m.box("stand_leg", (xa, 6.75, SZ0), (xb, RY0, SZ1), sp)
    m.box("stand_foot", (-9.75, 5.75, SZ0), (9.75, 6.75, SZ1), sp)

    m.group("extras")
    _cart_rope_coil(m, -(X0 + 2), 15.5, 8)
    _cart_lantern(m, 6, -(Z0 + 4.25))
    _cart_plate(m, 0.5, Z0 + 2)
    m.display = _held_display(m)
    return m


def _cart_rope_coil(m, x_wall, yc, zc):
    """A coil of rope hung on a hook on the bed's left side (x_wall: the outside of the upper board)."""
    def rope(c):
        x, y, z = c.p
        dy, dz = y - yc, z - zc
        rr = math.hypot(dy, dz)
        if c.face in ("east", "west"):
            if rr < 1.1:
                return (FRAME, -1)                                # the hole through the coil
            # the turns: rings of twisted hemp, a dark line between them, lit on top
            turn = math.floor(rr - 0.6)
            twist = (math.floor(math.atan2(dz, dy) * 2.5) + turn) % 2
            if rr > 2.6 and twist and h3(math.floor(y), math.floor(z), 1, 3) < 0.5:
                return ("burlap", 0.8)
            return ("burlap", (1.6 if turn % 2 else 2.6) + (0.5 if dy > 0.5 else -0.4))
        a = math.atan2(dz, dy)
        return ("burlap", 2.4 if math.floor(a * 4 + x * 2) % 2 else 1.4)  # the turns, seen edge on
    x0, x1 = x_wall - 1.5, x_wall
    cyl(m, "rope", (yc, zc), 6, x0, x1, Paint("burlap", 2, noise=0, rim=0.3, decal=rope), axis="x")
    # the hook it hangs on, over the top of the coil from the board
    m.box("rope_hook", (x0 - 0.5, yc + 2, zc - 0.5), (x1 - 1, yc + 3.5, zc + 0.5), IRON(decal=lambda c: ("iron", 2.2)))


def _cart_lantern(m, x, z):
    """An oil lantern hung in front of the bed from an iron arm on the rim rail: a tin roof and base, amber glass
    with the wick behind it (unlit), its bail over the roof."""
    def tin(c):
        if c.face == "up":
            return ("iron", 2.6)
        return ("iron", 1.8 if math.floor(c.p[1] * 2) % 2 == 0 else 1.4)

    def lens(c):
        x_, y, z_ = c.p
        across = x_ - x if c.face in ("north", "south") else z_ - z
        if abs(across) > 0.5:
            return ("iron", 1.4)                                  # the frame wires at the corners
        if math.floor(y) == 14:
            return ("fire", 3)                                    # the wick's little flame cup
        return ("glow_amber", 1.2 if y > 15 else 0.6)
    tp = Paint("iron", 2, noise=0, rim=0.6, decal=tin)
    m.box("lantern", (x - 1.5, 13, z - 1.5), (x + 1.5, 13.5, z + 1.5), tp)
    m.box("lantern_glass", (x - 1, 13.5, z - 1), (x + 1, 16.5, z + 1), Paint("glow_amber", 1, noise=0, rim=0.4, decal=lens))
    m.box("lantern", (x - 1.5, 16.5, z - 1.5), (x + 1.5, 17.5, z + 1.5), tp)
    m.box("lantern_bail", (x - 0.5, 17.5, z - 0.5), (x + 0.5, 19, z + 0.5), IRON)
    zr = -(CART["z0"] + 2.5)
    m.box("lantern_arm", (x - 0.5, 19, z - 0.5), (x + 0.5, 19.5, zr), IRON(decal=lambda c: ("iron", 2.4)))


def _cart_plate(m, x, z):
    """A small wooden plate hung on the tail board from one nail, a painted 7 (the cart's number)."""
    def plate_px(c):
        if c.face not in ("north", "south"):
            return ("hinoki", 0.6)
        col, row = math.floor(c.p[0] - (x - 2.5)), math.floor(17.99 - c.p[1])
        if row == 0 and col == 2:
            return ("iron", 2)                                    # the nail
        if col in (0, 4) or row in (0, 6):
            return ("hinoki", 0.6)
        if digits_pixel("7", col - 1, row - 1):
            return ("crimson", 1)
        return ("hinoki", 1.8 if (row * 3 + col) % 4 else 1.4)
    m.box("plate", (x - 2.5, 11, z), (x + 2.5, 18, z + 0.5), Paint("hinoki", 2, noise=0, rim=0.4, decal=plate_px))


# ================================================================================================
# The police light bar: three looks the plugin swaps to flash (no entity animation needed)
# ================================================================================================

def _lightbar(name, lit):
    m = BModel(name, shift=(8, 8, 8))

    def base(c):
        if c.face == "up":
            return ("rubber", 0)                         # the gasket under the lenses
        if c.face in ("north", "south", "east", "west"):
            return ("blade", 2)
        return ("rubber", 1)
    m.box("base", (-10, 0, -2.5), (10, 1, 2.5), CHROME(decal=base))
    red_on, blue_on = lit in ("a",), lit in ("b",)

    def lens(on, ramp):
        """Three segments a side, each a reflector with a bright core; a dark divider between them."""
        def decal(c):
            x, y, z = c.p
            j = math.floor(abs(x) - 0.5) % 3
            fy = math.floor(y)
            if c.face in ("east", "west"):
                j = 1 if abs(z) < 1 else 2
            if j == 0 and c.face != "down":
                return (ramp, -1 if on else -3)          # the divider
            core = j == 1 and (fy == 1 or c.face == "up" or c.face in ("east", "west"))
            if on:
                if core:
                    return (ramp, 2 if ramp == "signal_red" else 3)   # red stays red, not pink
                return (ramp, 1)
            if core:
                return (ramp, -0.6)                      # the reflector glints through the dark lens
            return (ramp, -2)
        if on:
            return Paint(ramp, 1, noise=0, light=False, glow=True, decal=decal)
        return Paint(ramp, -2, noise=0, rim=0.4, ao=0.5, decal=decal)
    m.box("lens_red", (-9.5, 1, -2), (-0.5, 3, 2), lens(red_on, "signal_red"))
    m.box("lens_blue", (0.5, 1, -2), (9.5, 3, 2), lens(blue_on, "signal_blue"))

    def middle(c):
        if c.face in ("north", "south") and math.floor(c.p[1]) == 1:
            return ("car_white", 3)                      # a small white takedown lamp
        return ("blade", 3 if c.face == "up" else 2)
    m.box("middle", (-0.5, 1, -1.5), (0.5, 2.5, 1.5), Paint("blade", 2, noise=0, decal=middle))
    m.display = presets.furniture(gui_scale=0.5)
    return m


def police_lightbar_off():
    return _lightbar("police_lightbar_off", "off")


def police_lightbar_a():
    return _lightbar("police_lightbar_a", "a")


def police_lightbar_b():
    return _lightbar("police_lightbar_b", "b")


# ================================================================================================
# The truck's load: four stages by how full its storage is (bed floor middle at model (8, 8, 8))
# ================================================================================================

BED_X = 13.5            # inner half width of the bed
BED_Z = (-24, 24)       # inner length (the bed's 48 px between headboard and tailgate, around its middle)


def _crate(m, x, y, z, w=12, h=10, d=12, ramp="wood"):
    """A slatted wooden crate standing at (x, y, z) (its bottom front-left corner), w x h x d: a frame of boards
    round each face, slats across with dark gaps between, grain along every board, nail heads at the corners."""
    def slats(c):
        u = c.p[1] - y
        if c.face in ("north", "south", "east", "west"):
            edge = c.p[0] - x if c.face in ("north", "south") else c.p[2] - z
            span = w if c.face in ("north", "south") else d
            frame = u < 1 or u > h - 1 or edge < 1 or edge > span - 1
            if frame:
                corner = (u < 1 or u > h - 1) and (edge < 1 or edge > span - 1)
                if corner:
                    return ("iron", 3)                       # a nail head
                if u > h - 1 or edge < 1:
                    return (ramp, 1.6)                       # frame boards, lit side
                return (ramp, 0.8)
            if math.floor(u) % 3 == 0:
                return (ramp, -0.4)                          # the gap between slats
            if math.floor(u) % 3 == 2:
                return (ramp, 2.4)                           # each slat's lit top edge
            # grain along the slat: streaks a few px long, now and then a dark knot
            row, at = math.floor(c.p[1]), math.floor(edge)
            r = h3(row, at // 4, math.floor(x + z), 7)
            if h3(row, at, 3, 8) < 0.03:
                return (ramp, 0.6)
            return (ramp, 1.4 if r < 0.3 else 2.0)
        if c.face == "up":
            a, b = c.p[0] - x, c.p[2] - z
            if a < 1 or a > w - 1 or b < 1 or b > d - 1:
                return (ramp, 1.6)
            if math.floor(b) % 3 == 0:
                return (ramp, 0)
        return None
    m.box("crate", (x, y, z), (x + w, y + h, z + d), Paint(ramp, 2, "grain", axis="x", noise=0, decal=slats))


def _sack(m, x, y, z, w=9, d=7, h=5, band="fabric_red"):
    """A tied burlap sack lying on its side: two stepped boxes (a bulge), a printed band round it, a stitched
    seam along the top, a cord tie at the neck."""
    def cloth(c):
        px, py, pz = c.p
        fx = math.floor(px - x)
        if c.face == "up" and math.floor(pz - z) == math.floor(d / 2):
            return ("burlap", 0.6 if fx % 2 else 2.8)        # the stitched seam
        if c.face in ("north", "south", "up") and fx in (3, 4):
            return (band, 1 if fx == 3 else 1.6)              # the printed band
        if c.face in ("north", "south", "east", "west") and py < y + 1:
            return ("burlap", 0.6)
        return None
    p = Paint("burlap", 2, "weave", noise=0.02, decal=cloth, decal_pattern=True)
    m.box("sack", (x, y, z), (x + w, y + h - 1, z + d), p)
    m.box("sack", (x + 1, y + h - 1, z + 1), (x + w - 1, y + h, z + d - 1), p)

    def tie(c):
        if math.floor(c.p[1]) == y + 2:
            return ("straw", 3)                              # the cord wrapped round the neck
        return None
    m.box("sack_tie", (x + w, y + 1, z + d / 2 - 1), (x + w + 1, y + h - 2, z + d / 2 + 1),
          Paint("burlap", 1, "weave", noise=0, decal=tie))


def _tarp(m, x0, x1, y, z0, z1, h=6):
    """A tarp-covered bundle: a blue tarp over a stepped pile, folds running down its sides, a row of eyelets
    along its hem, tied down by two orange straps with steel buckles."""
    straps = (z0 + (z1 - z0) * 0.3, z0 + (z1 - z0) * 0.7)

    def decal(c):
        px, py, pz = c.p
        for sz in straps:
            if abs(pz - sz) < 1 and c.face != "down":
                if c.face in ("east", "west") and math.floor(py) == y + 1:
                    return ("blade", 3)                      # the buckle
                return ("fox", 2.6 if c.face == "up" else 2)
        if c.face in ("east", "west", "north", "south") and math.floor(py) == y:
            along = pz if c.face in ("east", "west") else px
            if math.floor(along) % 4 == 1:
                return ("blade", 3)                          # an eyelet in the hem
            return ("denim", 0.6)
        # folds: straight creases across the bundle every 6 px, a lit ridge beside a shadowed valley
        k = math.floor(pz - z0) % 6 if c.face in ("east", "west", "up") else math.floor(px - x0) % 6
        if k == 0:
            return ("denim", 1.2)
        if k == 1:
            return ("denim", 2.7)
        return None
    p = Paint("denim", 2, noise=0, decal=decal)
    m.box("tarp", (x0, y, z0), (x1, y + h - 2, z1), p)
    m.box("tarp", (x0 + 1.5, y + h - 2, z0 + 1.5), (x1 - 1.5, y + h, z1 - 1.5), p)


def truck_cargo(stage):
    m = BModel(f"truck_cargo_{stage}", shift=(8, 8, 8))
    z0 = BED_Z[0] + 0.5
    if stage >= 1:
        _crate(m, -13, 0, z0, 12, 10, 12)
        _sack(m, 1, 0, z0 + 0.5)
        _crate(m, -13, 0, z0 + 13, 12, 8, 10, ramp="wood_dark")
    if stage >= 2:
        _crate(m, 0.5, 0, z0 + 8, 12.5, 10, 12)
        _sack(m, -12, 8, z0 + 15, 9, 6, band="denim")
        _crate(m, -13, 0, z0 + 24, 12, 10, 12)
    if stage >= 3:
        _crate(m, 0.5, 0, z0 + 21, 12.5, 10, 12)
        _crate(m, -12.5, 10, z0 + 0.5, 11, 9, 11, ramp="wood_dark")
        _sack(m, 1, 10, z0 + 9, 9, 7, 5, band="leaf_dark")
        _crate(m, -13, 0, z0 + 37, 12, 9, 10)
    if stage >= 4:
        _crate(m, 0.5, 0, z0 + 34, 12.5, 10, 13)
        _crate(m, 0.5, 10, z0 + 21, 12.5, 9, 12)
        _tarp(m, -12.5, 12.5, 19, z0 + 0.5, z0 + 33, 5)
        _sack(m, -12, 10, z0 + 25, 10, 7, 6)
        _sack(m, 1.5, 10, z0 + 35, 9, 7, 5, band="denim")
    m.display = presets.furniture(gui_scale=0.4)
    return m


FILLS = {
    # what a crate holds, painted on its open top: (ramp, base step, speck ramp, speck step, speck share)
    "beans": ("bean", 2.0, "doenjang", 2.4, 0.16),          # soybeans, a few darker ones
    "barley": ("straw", 2.4, "straw", 1.2, 0.25),           # barley, darker awns
    "pepper": ("pepper", 2.0, "leaf_dark", 1.6, 0.10),      # dried red peppers (고추), green stems
}


def _fill(kind, x, z):
    """A texel of grain at (x, z): the crop's colour, a grain now and then in a different shade."""
    ramp, base, sr, ss, share = FILLS[kind]
    fx, fz = math.floor(x), math.floor(z)
    r = h3(fx, 5, fz, 31)
    if r < share:
        return (sr, ss)
    if kind == "pepper":
        # long pods lying every which way: streaks two texels long, a lit and a shaded one
        return (ramp, base + (0.8 if (fx + 2 * fz) % 3 == 0 else -0.6 if (fx + 2 * fz) % 3 == 1 else 0))
    return (ramp, base + (0.6 if r > 0.8 else -0.5 if r > 0.65 else 0))


def _cart_crate(m, x, z, w, d, h, y=0, fill=None, heap=True, ramp="wood"):
    """A slatted crate in the hand cart: slats across with dark gaps, a corner post at each edge, a stencilled mark
    on its long sides, open on top with its grain showing (fill) heaped a pixel over the rim (heap), or shut with a
    lid of boards (fill None)."""
    def decal(c):
        px, py, pz = c.p
        u = py - y
        if c.face in ("north", "south", "east", "west"):
            along = px - x if c.face in ("north", "south") else pz - z
            span = w if c.face in ("north", "south") else d
            if along < 1 or along > span - 1:
                if u > h - 1 or u < 1:
                    return ("iron", 2.4)                     # a nail plate at the corner
                return (ramp, 1.0 if along < 1 else 0.6)     # the corner posts
            if math.floor(u) % 3 == 2:
                return (ramp, -0.2)                          # the gap between slats
            if u > h - 1:
                return (ramp, 2.8)                           # the top slat's lit edge
            # a stencilled mark in the middle of the long side (a ring and a bar, no real brand)
            mid = span / 2
            if span >= 9 and abs(along - mid) < 1.5 and 1 <= u < 4 and math.floor(u) % 3 != 2:
                if abs(along - mid) < 0.5 or math.floor(u) == math.floor(1 + y):
                    return ("iron", 1.2)
            row, at = math.floor(py), math.floor(along)
            return (ramp, 1.6 + (0.6 if h3(row, at // 3, math.floor(px + pz), 7) > 0.7 else 0) + _grain(c, "x" if c.face in ("north", "south") else "z", 2))
        if c.face == "up":
            a, b = px - x, pz - z
            if a < 1 or a > w - 1 or b < 1 or b > d - 1:
                return (ramp, 2.6)                           # the rim boards
            if fill is None:
                return (ramp, 0.4) if math.floor(a) % 3 == 0 else (ramp, 2.0 + _grain(c, "z", 4))
            f = _fill(fill, px, pz)
            if b < 2 or a > w - 2:
                return (f[0], f[1] - 1.2)                    # in the shade of the near rim
            return f
        return (ramp, 0.6)
    m.box("crate", (x, y, z), (x + w, y + h, z + d), Paint(ramp, 2, noise=0, rim=0.6, decal=decal))
    if fill is not None and heap:
        def mound(c):
            f = _fill(fill, c.p[0], c.p[2])
            return f if c.face == "up" else (f[0], f[1] - 0.6)
        m.box("heap", (x + 2, y + h, z + 2), (x + w - 2, y + h + 1, z + d - 2), Paint(FILLS[fill][0], 2, noise=0, rim=0.4, decal=mound))


def _cart_sack(m, x, z, w=8, d=6, h=10.5, y=0, band="fabric_red"):
    """A burlap sack standing up: a bulging body, slumped shoulders, the neck gathered and tied with cord, its ears
    sticking up over the knot; a printed band round it and a stencilled mark."""
    cx, cz = x + w / 2, z + d / 2

    def cloth(c):
        px, py, pz = c.p
        u = py - y
        if c.face in ("north", "south", "east", "west"):
            along = px - x if c.face in ("north", "south") else pz - z
            span = w if c.face in ("north", "south") else d
            if 3 <= u < 4:
                return (band, 1.2 if math.floor(along) % 4 else 0.6)   # the printed band
            if u < 1:
                return ("burlap", 0.8)                       # slumped onto the floor
            edge = min(along, span - along)
            if c.face == "north" and 4.5 <= u < 6 and abs(along - span / 2) < 1.5:
                return ("burlap", 0.6) if (math.floor(along) + math.floor(u)) % 2 == 0 else None   # a stencil
            return ("burlap", 2.4 if edge > 2 else 1.8 if edge > 1 else 1.2)   # the bulge
        if c.face == "up":
            r = max(abs(px - cx) / (w / 2), abs(pz - cz) / (d / 2))
            return ("burlap", 1.6 if r > 0.7 else 2.4)
        return None
    p = Paint("burlap", 2, "weave", noise=0.02, rim=0.4, decal=cloth, decal_pattern=True)
    m.box("sack", (x, y, z), (x + w, y + h - 2, z + d), p)
    m.box("sack", (x + 1, y + h - 2, z + 1), (x + w - 1, y + h - 1, z + d - 1), p)

    def neck(c):
        if math.floor(c.p[1]) == math.floor(y + h - 0.5) and c.face != "up":
            return ("straw", 1.4 if math.floor(c.p[0] + c.p[2]) % 2 else 2.4)   # the cord wound round it
        return ("burlap", 1.4)
    m.box("sack_neck", (cx - 1, y + h - 1, cz - 1), (cx + 1, y + h + 0.5, cz + 1), Paint("burlap", 1, noise=0, decal=neck))

    def ears(c):
        return ("burlap", 2.6 if c.face == "up" else 1.8)
    m.box("sack_ears", (cx - 1.5, y + h + 0.5, cz - 1.5), (cx + 1.5, y + h + 1.5, cz + 1.5),
          Paint("burlap", 2, "weave", noise=0, decal=ears, decal_pattern=True))


def _folded_tarp(m, x0, x1, z0, z1, y0, y1):
    """A blue tarp folded into a flat bundle: the folds' edges along its sides, a strap round it."""
    def decal(c):
        px, py, pz = c.p
        if abs(pz - (z0 + z1) / 2) < 0.75 and c.face != "down":
            return ("fox", 2.4 if c.face == "up" else 1.8)   # the strap
        if c.face in ("north", "south", "east", "west"):
            return ("denim", 1.4 if math.floor(py * 2) % 2 else 2.6)   # the folded layers
        return ("denim", 2.4 if math.floor(px - x0) % 5 else 1.8)
    m.box("tarp", (x0, y0, z0), (x1, y1, z1), Paint("denim", 2, noise=0, rim=0.5, decal=decal))


def _rope_net(m, x0, x1, z0, z1, y0, y1, rope=("leather", 1.8)):
    """A rope net thrown over a full load: a see-through mesh (cut-out planes, half a pixel thick) over the top, a knot
    at each crossing, and every other rope pulled down the sides and tied off at the rim rail."""
    def mesh(c):
        if c.nu <= 1 or c.nv <= 1:
            return rope                                      # the planes' thin edges
        u, v = c.u, c.v
        a, b = u % 5 == 2, v % 5 == 2
        if c.face in ("up", "down"):
            # over the top: the mesh, a knot at every crossing
            if a and b:
                return (rope[0], rope[1] + 1.4)
            if a or b:
                return (rope[0], rope[1] + 0.6)
            return ("raw", CLEAR)
        # down the sides: every other rope of the mesh pulled down to the rail and tied off there
        if u % 10 == 2:
            return (rope[0], rope[1] + (1.2 if v == c.nv - 1 else 0.4 if v % 2 else 0))
        if v == 0 and u % 5 == 2:
            return (rope[0], rope[1] + 1.4)                  # the knots along the top edge
        return ("raw", CLEAR)
    p = Paint(rope[0], 2, noise=0, rim=0, ao=0, decal=mesh)
    t = 0.5
    m.box("net", (x0, y1, z0), (x1, y1 + t, z1), p)
    for s in (0, 1):
        xa = x0 if s == 0 else x1 - t
        m.box("net", (xa, y0, z0), (xa + t, y1, z1), p)
        za = z0 if s == 0 else z1 - t
        m.box("net", (x0 + t, y0, za), (x1 - t, y1, za + t), p)


def hand_cart_cargo(stage):
    """What lies in the hand cart's bed (22 x 31 px inside the walls, floor middle at model (8, 8, 8)), by how full
    it is: crates of rice, barley and dried peppers, burlap sacks standing and lying, all tied, and when full a rope
    net over the whole load down to the rim rail (the rim is 6 px over the floor)."""
    m = BModel(f"hand_cart_cargo_{stage}", shift=(8, 8, 8))
    if stage >= 1:
        _cart_crate(m, -11, 5, 10, 10, 7, fill="beans")
        _cart_sack(m, 1, 8.5, band="fabric_red")
    if stage >= 2:
        _cart_crate(m, 1, -5, 10, 10, 7, ramp="wood_dark")
        _sack(m, -11, 0, -5, 9, 7, 5, band="denim")
    if stage >= 3:
        _cart_crate(m, -11, -15, 10, 9, 6, fill="pepper", heap=stage < 4)
        _cart_sack(m, 1, -15, band="leaf_dark")
        _sack(m, -11, 5, -5, 9, 7, 5, band="fabric_red")
    if stage >= 4:
        _cart_crate(m, 1.5, -4.5, 9, 9, 5, y=7, fill="barley", heap=False)
        _sack(m, -11, 6, -15, 9, 7, 6, band="denim")
        _sack(m, -11, 8, 6, 9, 7, 4)
        _folded_tarp(m, -10.5, -2.5, -4.5, 1.5, 10, 12)
        _rope_net(m, -11.5, 11.5, -15.5, 15.5, 6, 12)
    m.display = presets.furniture(gui_scale=0.4)
    return m


# ================================================================================================

ALL = {
    "police_car": police_car,
    "light_truck": light_truck,
    "golf_cart": golf_cart,
    "wheel_road": wheel_road,
    "wheel_cart": wheel_cart,
    "hand_cart": hand_cart,
    "wheel_handcart": wheel_handcart,
    "police_lightbar_off": police_lightbar_off,
    "police_lightbar_a": police_lightbar_a,
    "police_lightbar_b": police_lightbar_b,
    **{f"truck_cargo_{k}": (lambda k=k: truck_cargo(k)) for k in range(1, 5)},
    **{f"hand_cart_cargo_{k}": (lambda k=k: hand_cart_cargo(k)) for k in range(1, 5)},
}
