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
the seat, so every roof clears that. Windows are thin cut-out planes (see-through, with a few glints), so riders
show through them.
"""

import math

from stsv_assets.bake import BModel, Paint, cyl
from stsv_assets.palette import ladder
from stsv_assets import presets

# ================================================================================================
# Paints
# ================================================================================================

WHITE = Paint("car_white", 2, noise=0.01)
BLUE_CAB = Paint("truck_blue", 2, noise=0.01)
IVORY = Paint("cream", 2, noise=0.01)
TRIM = Paint("tint", 1, noise=0)
DARK = Paint("rubber", 1, noise=0.02)
SEAT = Paint("leather", 1, "weave", noise=0.02)
STEEL = Paint("steel", 3, "metal")
STEEL_DARK = Paint("steel", 1, "metal")
IRON = Paint("iron", 2, "metal")
TIRE = Paint("rubber", 1, noise=0.03, rim=0.3)
GREEN = Paint("leaf_dark", 1, noise=0.02)
WOOD = Paint("wood", 2, "planks", axis="x", width=3)
CLEAR = (0, 0, 0, 0)


def raw(ramp, step):
    return ("raw", ladder(ramp, step))


def glass_decal(frame=("tint", 1), streak=13, glint=("glass", 2)):
    """A see-through pane: clear, with a couple of diagonal glints and a dark rim on its thin edges."""
    def decal(c):
        if c.nu <= 1 or c.nv <= 1:
            return raw(*frame)                       # the pane's 0.5 px edges
        if (c.u + c.v) % streak == 0 and (c.u // 4) % 4 == 1:
            return raw(*glint)
        return ("raw", CLEAR)
    return decal


GLASS = Paint("glass", 2, noise=0, light=False, decal=glass_decal())


def glyphs(rows):
    return [r for r in rows]


# 경찰: generic "police" lettering, 7 x 8 px a syllable
KYEONG = glyphs(["###..#.",
                 "..#.##.",
                 "..#..#.",
                 "..#.##.",
                 ".....#.",
                 "..###..",
                 ".#...#.",
                 "..###.."])
CHAL = glyphs([".#...#.",
               "###..##",
               "#.#..#.",
               ".......",
               "#####..",
               "....#..",
               "#####..",
               "#......"])
CHAL_LAST = "#####.."


def text_pixel(col, row):
    """'경찰' (two syllables and a 1 px gap, 15 x 9 px) at column col, row row (0 = top)."""
    if not (0 <= row < 9 and 0 <= col < 15):
        return False
    if col < 7:
        g, x = KYEONG, col
    elif col == 7:
        return False
    else:
        g, x = CHAL, col - 8
    rows = g + ([CHAL_LAST] if g is CHAL else ["......."])
    return rows[row][x] == "#"


# ================================================================================================
# Wheels (hub at model (8, 8, 8), axle along x; the plugin spins them about x)
# ================================================================================================

def _wheel(name, d, width, hub_ramp=("steel", 3)):
    m = BModel(name, shift=(8, 8, 8), floor=None)
    r = d / 2

    def tire(c):
        y, z = c.p[1], c.p[2]
        rr = math.hypot(y, z)
        if c.face in ("east", "west"):
            # the hub: a disc with five spokes, so the turning reads from the side
            if rr < r * 0.62:
                a = math.atan2(z, y)
                spoke = (a * 5 / (2 * math.pi)) % 1.0
                if rr < r * 0.18:
                    return (hub_ramp[0], hub_ramp[1] - 1)
                if spoke < 0.32:
                    return (hub_ramp[0], hub_ramp[1])
                return ("steel", 0)
            return ("rubber", 1 if rr < r * 0.8 else 0)
        # the tread: grooves across it every few px, so it shows rolling from the front too
        ang = math.atan2(z, y)
        k = math.floor((ang + math.pi) / (2 * math.pi) * (d * 2.2))
        return ("rubber", 0 if k % 3 == 0 else 1)
    cyl(m, "tire", (0, 0), d, -width / 2, width / 2, TIRE(decal=tire), axis="x")
    m.display = presets.furniture(gui_scale=0.6)
    return m


def wheel_road():
    """Car and truck wheel: 0.69 block across (11 px), 4 px wide, a five-spoke steel rim."""
    return _wheel("wheel_road", 11, 4)


def wheel_cart():
    """Golf cart wheel: small and fat, 7 px across, 3 px wide, a white hub cap."""
    return _wheel("wheel_cart", 7, 3, hub_ramp=("car_white", 3))


# ================================================================================================
# 경찰차 (patrol car) 4.6 x 1.9 x 1.66 blocks
# ================================================================================================

def _slot(p, half_w, wheel_z, half_slot, top):
    """Is this texel inside a wheel well (the walls and the arch over a wheel)?"""
    return abs(p[0]) > half_w - 0.1 and p[1] < top + 0.1 and wheel_z - half_slot - 0.1 <= abs(p[2]) <= wheel_z + half_slot + 0.1


def police_car():
    m = BModel("police_car", shift=(8, 8, 8), scale=2.0)
    W, L = 15, 36           # half width, half length (px)
    WZ, SLOT = 23, 6.5      # wheel centres at z = +-23, the wells 13 px long
    BELT = 15               # top of the lower body (bottom of the windows)

    def body(c):
        p = c.p
        x, y, z = p
        if _slot(p, 11, WZ, SLOT, 12) and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 1)
        if c.face == "up":
            if -15 <= z <= 23 and abs(x) < 13.5:
                return ("rubber", 1)                    # the floor between the seats, seen through the glass
            if z < -15 and abs(x) < 9 and abs(x) > 8:
                return ("car_white", 1)                 # hood creases
            return None
        if c.face in ("east", "west"):
            # navy skirt, a blue band down the side, door seams and handles, 경찰 on the front doors
            if y < 6.5:
                return ("police_blue", 1)
            for seam in (-15, 1, 16.5):
                if abs(z - seam) < 0.5 and y < BELT - 0.2:
                    return ("car_white", 0)
            if 12.5 <= y < 13.25 and (-3 <= z < -0.5 or 13 <= z < 15.5):
                return ("tint", 1)
            if -14.5 < z < 0.5:
                row = math.floor(14.5 - y)
                col = math.floor(0 - z) if c.face == "east" else math.floor(z + 14.5)
                if text_pixel(col, row):
                    return ("police_blue", 1)
                return None
            if 9.5 <= y < 11.5:
                return ("police_blue", 2)
            if 7 <= y < 7.5:
                return ("police_blue", 2)
            return None
        if c.face == "north":
            # grille, the plate (no letters: a plain plate), fog lights
            if abs(x) < 6 and 8 <= y < 11:
                return ("rubber", 0 if (math.floor(y) % 2 == 0) else 1)
            if abs(x) < 3.5 and 4.5 <= y < 7:
                return ("car_white", 3) if abs(x) < 3 and 5 <= y < 6.5 else ("tint", 1)
            if y < 6.5:
                return ("police_blue", 1)
            return None
        if c.face == "south":
            if abs(x) < 3.5 and 7 <= y < 9.5:
                return ("car_white", 3) if abs(x) < 3 and 7.5 <= y < 9 else ("tint", 1)
            if y < 6.5:
                return ("police_blue", 1)
            if 9.5 <= y < 10.5 and abs(x) < 8.5:
                return ("police_blue", 2)
            return None
        return None
    paint = WHITE(decal=body)

    m.group("body")
    m.box("core", (-11, 4, -29.5), (11, 12, 29.5), paint)
    m.box("nose", (-W, 4, -L), (W, 12, -29.5), paint)
    m.box("tail", (-W, 4, 29.5), (W, 12, L), paint)
    for s in (1, -1):
        m.box("sill", (min(11 * s, W * s), 4, -16.5), (max(11 * s, W * s), 12, 16.5), paint)
    m.box("shoulder", (-W, 12, -L), (W, BELT, L), paint)
    # bumpers, mirrors
    for zf, zt in ((-L - 1, -L), (L, L + 1)):
        m.box("bumper", (-14, 4.5, zf), (14, 8, zt), Paint("iron", 1, "metal"))
    for s in (1, -1):
        m.box("mirror", (min(14 * s, 16.5 * s), BELT, -13), (max(14 * s, 16.5 * s), BELT + 2, -11), WHITE)

    m.group("lights")
    for s in (1, -1):
        m.box("headlight", (min(8.5 * s, 13.5 * s), 9, -L - 0.5), (max(8.5 * s, 13.5 * s), 11, -L),
              Paint("lamp", 3, noise=0, light=False, glow=True))
        m.box("taillight", (min(9 * s, 14 * s), 10.5, L), (max(9 * s, 14 * s), 13, L + 0.5),
              Paint("signal_red", 1, noise=0, light=False, glow=True))

    m.group("cabin")
    # the roof, the slanted windscreen and rear window (45 degrees), the side windows with their pillars
    def roof(c):
        if c.face == "up":
            # a roof number in a box, like patrol cars carry (here just a frame and three blocks)
            if 1 <= c.p[2] < 8 and abs(c.p[0]) < 7 and (abs(abs(c.p[0]) - 6.5) < 0.5 or c.p[2] < 1.5 or c.p[2] >= 7.5):
                return ("police_blue", 1)
            if 2.5 <= c.p[2] < 6.5 and math.floor(c.p[0] + 7) % 4 in (1, 2) and abs(c.p[0]) < 5.5:
                return ("police_blue", 2)
        if c.face in ("east", "west", "north", "south"):
            return ("car_white", 2)
        return None
    m.box("roof", (-13.5, 25, -5), (13.5, 26.5, 14), WHITE(decal=roof))
    m.box("windscreen", (-13, 15, -15), (13, 15 + 10 * math.sqrt(2), -14.5), GLASS,
          rotation=("x", 45, (0, 15, -15)))
    m.box("rear_window", (-13, 25 - 9 * math.sqrt(2), 14), (13, 25, 14.5), GLASS,
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
            return raw("car_white", 2)  # A and C pillars, the roof rail
        if 0.5 <= z < 2.5:
            return raw("tint", 0)       # B pillar
        if (math.floor(z) + math.floor(y)) % 17 == 0 and 17 < y < 22:
            return raw("glass", 2)
        return ("raw", CLEAR)
    for s in (1, -1):
        m.box("side_window", (min(13 * s, 13.5 * s), BELT, -15), (max(13 * s, 13.5 * s), 25, 24),
              Paint("glass", 2, noise=0, light=False, decal=side_window))

    m.group("inside")
    m.box("dash", (-13, BELT, -12), (13, BELT + 2, -9), DARK)
    m.box("wheel", (-9.5, BELT + 2, -9), (-4.5, BELT + 5, -8.5), DARK)
    for s in (1, -1):
        m.box("front_seat", (min(2.5 * s, 12 * s), BELT, 1), (max(2.5 * s, 12 * s), BELT + 6.5, 3), SEAT)
        m.box("rear_seat", (min(2.5 * s, 12 * s), BELT, 14), (max(2.5 * s, 12 * s), BELT + 6, 16), SEAT)
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

    def cab(c):
        x, y, z = c.p
        if abs(x) > 9.9 and y < 12.1 and FA - SLOT - 0.1 <= z <= FA + SLOT + 0.1 and c.face != "up":
            return ("rubber", 0)
        if c.face == "down":
            return ("rubber", 1)
        if c.face == "up":
            if abs(x) < 13.5 and F + 1 < z < CB - 0.5:
                return ("rubber", 1)                  # the cab floor, seen through the glass
            return None
        if c.face == "north":
            # a flat face: grille slots between the lamps, a plain plate, turn signals
            if abs(x) < 7 and 9 <= y < 13:
                return ("tint", 0 if math.floor(y) % 2 == 0 else 1)
            if abs(x) < 3.5 and 6 <= y < 8.5:
                return ("car_white", 3) if abs(x) < 3 and 6.5 <= y < 8 else ("tint", 1)
            if 13 <= y < 14 and abs(x) > 9:
                return ("hazard", 1)
            if 14.5 <= y < 15:
                return ("truck_blue", 1)
            return None
        if c.face in ("east", "west"):
            if abs(z - (CB - 12)) < 0.5 and y > 7:
                return ("truck_blue", 0)              # the door seam
            if 15 <= y < 15.75 and CB - 6 <= z < CB - 3.5:
                return ("tint", 1)                    # handle
            if y < 7.5:
                return ("truck_blue", 1)
            if 7.5 <= y < 8.25:
                return ("car_white", 3)               # a pale pinstripe
            return None
        if c.face == "south":
            return ("truck_blue", 1)
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
    m.box("bumper", (-13.5, 5, F - 1), (13.5, 8.5, F), Paint("iron", 1, "metal"))
    for s in (1, -1):
        xa, xb = sorted((8.5 * s, 12.5 * s))
        m.box("headlight", (xa, 9.5, F - 0.5), (xb, 12.5, F), Paint("lamp", 3, noise=0, light=False, glow=True))
        xa, xb = sorted((H * s, (H + 1.5) * s))
        m.box("mirror_arm", (xa, 23, F + 1), (xb, 23.5, F + 2), IRON)
        xa, xb = sorted(((H + 1.5) * s, (H + 2.5) * s))
        m.box("mirror", (xa, 21, F + 0.5), (xb, 25.5, F + 2.5), TRIM)
    rake = 11 * math.tan(math.radians(22.5))
    m.box("windscreen", (-13.5, WIN, F + 0.5), (13.5, WIN + 11 / math.cos(math.radians(22.5)), F + 1), GLASS,
          rotation=("x", 22.5, (0, WIN, F + 0.5)))

    def window(c):
        x, y, z = c.p
        front = F + 0.5 + (y - WIN) * math.tan(math.radians(22.5))
        if z < front - 0.3:
            return ("raw", CLEAR)
        if c.nu <= 1 or c.nv <= 1:
            return raw("truck_blue", 2)
        if z < front + 1 or y > ROOF - 1 or z > CB - 5:
            return raw("truck_blue", 2)       # pillars, rail, the cab's back corner
        if (math.floor(z) + math.floor(y)) % 17 == 0 and y < ROOF - 3:
            return raw("glass", 2)
        return ("raw", CLEAR)
    for s in (1, -1):
        xa, xb = sorted((13.5 * s, H * s))
        m.box("side_window", (xa, WIN, F + 0.5), (xb, ROOF, CB), Paint("glass", 2, noise=0, light=False, decal=window))

    def back_wall(c):
        if c.face == "south" and abs(c.p[0]) < 6 and WIN + 3 <= c.p[1] < ROOF - 2:
            return ("tint", 1 if abs(c.p[0]) < 5.5 and c.p[1] < ROOF - 2.5 else 0)
        return None
    m.box("cab_back", (-13.5, WIN, CB - 1), (13.5, ROOF, CB), BLUE_CAB(decal=back_wall))

    def roof(c):
        if c.face == "up" and abs(c.p[0]) < 12 and math.floor(c.p[2]) % 5 == 0:
            return ("truck_blue", 1)                  # ribs pressed into the roof
        return None
    m.box("roof", (-H, ROOF, F + 0.5 + rake), (H, ROOF + 1.5, CB), BLUE_CAB(decal=roof))

    m.group("inside")
    m.box("dash", (-13.5, WIN, F + 2), (13.5, WIN + 2, F + 5), DARK)
    m.box("wheel", (-9.5, WIN + 2, F + 5), (-4.5, WIN + 5, F + 5.5), DARK)
    for s in (1, -1):
        xa, xb = sorted((2 * s, 12 * s))
        m.box("seat_back", (xa, WIN, CB - 4), (xb, WIN + 8, CB - 1), SEAT)

    m.group("bed")
    rails = Paint("iron", 1, "metal")
    for s in (1, -1):
        xa, xb = sorted((6 * s, 9 * s))
        m.box("frame", (xa, 7, CB), (xb, 11, BK - 1), rails)

    def deck(c):
        if c.face == "up" and math.floor(c.p[0] + 0.5) % 4 == 0:
            return ("steel", 1)                       # ribs along the deck
        return None
    m.box("deck", (-BH, 11, BF), (BH, 13, BK), STEEL(decal=deck, axis="z"))

    def boards(c):
        y = c.p[1]
        if c.face in ("east", "west", "north", "south") and math.floor(y) in (15,):
            return ("steel", 2)                       # a pressed rib along each drop side
        if c.face == "up":
            return ("steel", 4)
        return None
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
            return raw("iron", 2 if c.face in ("north", "south") else 1)
        return ("raw", CLEAR)
    m.box("guard", (-13.5, top, BF), (13.5, ROOF, BF + 0.5), Paint("iron", 2, noise=0, light=False, decal=guard))
    m.box("rear_bumper", (-13.5, 6.5, BK - 1), (13.5, 9.5, BK), rails)
    for s in (1, -1):
        xa, xb = sorted((10 * s, 13.5 * s))
        m.box("taillight", (xa, 7, BK), (xb, 9.5, BK + 0.5), Paint("signal_red", 1, noise=0, light=False, glow=True))
    m.display = _held_display(m)
    return m


# ================================================================================================
# 골프 카트 (golf cart: two seats, canopy, bag rack) 2.4 x 1.3 x 1.97 blocks
# ================================================================================================

def golf_cart():
    m = BModel("golf_cart", shift=(8, 8, 8), scale=2.0)
    H = 9.5                   # half width of the body
    WZ = 12.5                 # wheel centres at z = +-12.5
    SEAT_TOP = 10
    ROOF = 30.5

    def body(c):
        x, y, z = c.p
        if c.face == "down":
            return ("rubber", 1)
        if c.face == "up" and -9 <= z <= 9 and abs(x) < 9.4:
            return ("rubber", 1 if math.floor(z) % 3 else 0)   # a ribbed rubber floor mat
        if c.face in ("east", "west") and 9 < y < 10:
            return ("cream", 1)                      # a body line
        if c.face == "north" and abs(x) < 4 and 8 <= y < 10:
            return ("leaf_dark", 1)                  # a green badge stripe (no brand)
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
    m.box("bumper", (-8, 4, -20), (8, 6, -19), DARK)
    for s in (1, -1):
        xa, xb = sorted((4.5 * s, 8 * s))
        m.box("headlight", (xa, 10, -19.5), (xb, 11.5, -19), Paint("lamp", 3, noise=0, light=False, glow=True))
        m.box("taillight", (xa, 8, 19), (xb, 9, 19.5), Paint("signal_red", 1, noise=0, light=False, glow=True))

    m.group("seat")
    m.box("cushion", (-9, SEAT_TOP - 1.5, 2.5), (9, SEAT_TOP, 9), SEAT)
    m.box("backrest", (-9, 10, 9), (9, 18, 10.5), SEAT)
    m.box("dash", (-8, 13, -11), (8, 15, -9), DARK)
    m.box("column", (-6, 13, -9), (-5, 16, -8), DARK)
    m.box("wheel", (-8, 16, -9), (-3, 16.5, -6), DARK)

    m.group("canopy")
    struts = Paint("steel", 3, "metal")
    for s in (1, -1):
        xa, xb = sorted((8 * s, 9 * s))
        m.box("front_strut", (xa, 13, -10.5), (xb, ROOF, -9.5), struts)
        m.box("rear_strut", (xa, 10, 10.5), (xb, ROOF, 11.5), struts)

    def canopy(c):
        x, z = c.p[0], c.p[2]
        if c.face == "up" and (abs(abs(x) - 9) < 0.5 or abs(z + 13.5) < 0.5 or abs(z - 18.5) < 0.5):
            return ("cream", 3)                      # a light piped edge
        if c.face in ("north", "south", "east", "west"):
            return ("cream", 3)
        return None
    m.box("roof", (-10, ROOF, -14), (10, ROOF + 1.5, 19), GREEN(decal=canopy))

    m.group("bag rack")
    for s in (1, -1):
        xa, xb = sorted((6 * s, 7 * s))
        m.box("rack_post", (xa, 10, 18), (xb, 20, 19), struts)
    m.box("rack_bar", (-7, 20, 18), (7, 21, 19), struts)
    m.box("rack_plate", (-7, 10, 12), (7, 10.5, 18), Paint("steel", 1, "metal"))

    def bag(c):
        y = c.p[1]
        if 21 <= y < 22 or 13 <= y < 14:
            return ("car_white", 3)                  # white bands round the bag
        return None
    cyl(m, "bag", (-1.5, 15), 5, 10.5, 24, Paint("fabric_red", 2, "weave", noise=0.02, decal=bag))
    for (x, z, h) in ((-3, 14, 27), (-0.5, 16, 28), (-1.5, 13.5, 26.5)):
        m.box("club", (x, 24, z), (x + 1, h, z + 1), STEEL)
    m.box("club_head", (-3.5, 27, 13.5), (-1.5, 28.5, 15), Paint("wood_dark", 1, "grain", axis="x"))
    m.box("club_head", (-1, 28, 15.5), (0.5, 29, 17), STEEL)
    m.display = _held_display(m)
    return m


# ================================================================================================
# The police light bar: three looks the plugin swaps to flash (no entity animation needed)
# ================================================================================================

def _lightbar(name, lit):
    m = BModel(name, shift=(8, 8, 8))
    m.box("base", (-10, 0, -2.5), (10, 1, 2.5), Paint("rubber", 1, "metal"))
    red_on, blue_on = lit in ("a",), lit in ("b",)

    def lens(on, ramp):
        if on:
            return Paint(ramp, 1, noise=0, light=False, glow=True)
        return Paint(ramp, -1, noise=0, rim=0.4, ao=0.5)
    m.box("lens_red", (-9.5, 1, -2), (-0.5, 3, 2), lens(red_on, "signal_red"))
    m.box("lens_blue", (0.5, 1, -2), (9.5, 3, 2), lens(blue_on, "signal_blue"))
    m.box("middle", (-0.5, 1, -1.5), (0.5, 2.5, 1.5), Paint("car_white", 3, noise=0))
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
    """A slatted wooden crate standing at (x, y, z) (its bottom front-left corner), w x h x d."""
    def slats(c):
        u = c.p[1] - y
        if c.face in ("north", "south", "east", "west"):
            if u < 1 or u > h - 1:
                return (ramp, 1)                     # top and bottom bands
            edge = c.p[0] - x if c.face in ("north", "south") else c.p[2] - z
            span = w if c.face in ("north", "south") else d
            if edge < 1 or edge > span - 1:
                return (ramp, 1)
            if math.floor(u) % 3 == 0:
                return (ramp, 1)                     # gaps between the slats
        if c.face == "up":
            a, b = c.p[0] - x, c.p[2] - z
            if a < 1 or a > w - 1 or b < 1 or b > d - 1:
                return (ramp, 1)
        return None
    m.box("crate", (x, y, z), (x + w, y + h, z + d), Paint(ramp, 2, "grain", axis="x", decal=slats))


def _sack(m, x, y, z, w=9, d=7, h=5):
    """A tied burlap sack lying on its side: two stepped boxes (a bulge), a dark tie."""
    p = Paint("burlap", 2, "weave", noise=0.03)
    m.box("sack", (x, y, z), (x + w, y + h - 1, z + d), p)
    m.box("sack", (x + 1, y + h - 1, z + 1), (x + w - 1, y + h, z + d - 1), p)
    m.box("sack_tie", (x + w, y + 1, z + d / 2 - 1), (x + w + 1, y + h - 2, z + d / 2 + 1), Paint("bark", 1, noise=0))


def _tarp(m, x0, x1, y, z0, z1, h=6):
    """A tarp-covered bundle, roped twice: a blue tarp over a stepped pile."""
    def ropes(c):
        if c.face != "down" and (abs(c.p[2] - (z0 + (z1 - z0) * 0.3)) < 0.5 or abs(c.p[2] - (z0 + (z1 - z0) * 0.7)) < 0.5):
            return ("straw", 2)
        return None
    p = Paint("denim", 2, "weave", noise=0.03, decal=ropes)
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
        _sack(m, -12, 8, z0 + 15, 9, 6)
        _crate(m, -13, 0, z0 + 24, 12, 10, 12)
    if stage >= 3:
        _crate(m, 0.5, 0, z0 + 21, 12.5, 10, 12)
        _crate(m, -12.5, 10, z0 + 0.5, 11, 9, 11, ramp="wood_dark")
        _sack(m, 1, 10, z0 + 9, 9, 7, 5)
        _crate(m, -13, 0, z0 + 37, 12, 9, 10)
    if stage >= 4:
        _crate(m, 0.5, 0, z0 + 34, 12.5, 10, 13)
        _crate(m, 0.5, 10, z0 + 21, 12.5, 9, 12)
        _tarp(m, -12.5, 12.5, 19, z0 + 0.5, z0 + 33, 5)
        _sack(m, -12, 10, z0 + 25, 10, 7, 6)
        _sack(m, 1.5, 10, z0 + 35, 9, 7, 5)
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
