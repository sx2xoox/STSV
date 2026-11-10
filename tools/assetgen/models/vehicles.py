"""
Drivable vehicles (content/stsv/items/vehicles.yml): 경찰차 (a generic patrol car), 1톤 트럭 (a small cab-over
pickup with an open drop-side bed) and 골프 카트 (two seats, canopy, a bag rack). No real agency emblem, no brand.

Every vehicle is several models, each shown by its own item display that the plugin moves every tick
(plugin: dev.stsv.core.vehicle):
  body      <id>             the whole vehicle without its wheels. Bigger than a model may be (a car is 4.6 blocks
                             long): drawn at its real size and shrunk by 2 around the middle (BModel scale 2), the
                             item display scales it back up (vehicle.scale 2 in the item file), so a texel stays
                             1/16 block like every other model.
  wheels    wheel_road, wheel_cart     one wheel, hub at model (8, 8, 8), axle along x. The plugin turns it about
                             its axle (rolling) and, for front wheels, about y (steering).
  parts     police_lightbar_off / _a / _b   the roof light bar: dark, left (red) lit, right (blue) lit; the plugin
                             swaps them to flash. Attachment point (bottom middle) at model (8, 8, 8).
            truck_cargo_1..4  what lies on the truck's bed, by how full its storage is (25 / 50 / 75 / 100 %).

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
were added for it: door handles, mirror arms, seat cushions and head rests, a steering column, mud flaps.
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

# 경찰: generic "police" lettering, two syllables of 7 x 9 px and a 1 px gap (15 x 9 px)
KYEONG = ["###..#.",
          "..#.##.",
          "..#..#.",
          ".#..##.",
          "#....#.",
          ".......",
          "..###..",
          ".#...#.",
          "..###.."]
CHAL = [".#...#.",
        "###..#.",
        "#.#..##",
        ".....#.",
        "#####..",
        "....#..",
        "#####..",
        "#......",
        "#####.."]

# 3 x 5 digits (roof number, cart number)
DIGITS = {"1": [".#.", "##.", ".#.", ".#.", "###"], "2": ["##.", "..#", ".#.", "#..", "###"],
          "7": ["###", "..#", ".#.", ".#.", ".#."], "3": ["##.", "..#", ".#.", "..#", "##."]}


def text_pixel(col, row):
    """'경찰' at column col, row row (0 = top), 15 x 9 px."""
    if not (0 <= row < 9 and 0 <= col < 15) or col == 7:
        return False
    g, x = (KYEONG, col) if col < 7 else (CHAL, col - 8)
    return g[row][x] == "#"


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
            # navy rocker, a light-blue accent line, the blue stripe, door seams and handles, 경찰 on the front doors,
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
            if DOOR[0] < fz < DOOR[1]:
                # the front door: 경찰 in navy on white, no stripe
                row = 14 - fy
                col = (-fz) if c.face == "east" else (fz + 14)
                if text_pixel(col, row):
                    return ("police_blue", 0)
                if fy == 12 and 1 <= fz <= 2:
                    return ("tint", 0)                  # the handle's recess
                return white(y)
            if fy == 12 and 14 <= fz <= 15:
                return ("tint", 0)
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


# ================================================================================================

ALL = {
    "police_car": police_car,
    "light_truck": light_truck,
    "golf_cart": golf_cart,
    "wheel_road": wheel_road,
    "wheel_cart": wheel_cart,
    "police_lightbar_off": police_lightbar_off,
    "police_lightbar_a": police_lightbar_a,
    "police_lightbar_b": police_lightbar_b,
    **{f"truck_cargo_{k}": (lambda k=k: truck_cargo(k)) for k in range(1, 5)},
}
