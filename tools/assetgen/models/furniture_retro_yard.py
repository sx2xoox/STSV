"""
Retro Korean furniture, 마당·골목 (content/stsv/items/furniture_retro_yard.yml): ten pieces of an 80s-90s Korean
yard and alley, built as baked models (stsv_assets/bake.py) with the same conventions as models/furniture_japanese.py
(16 px a block, front = north (z 0), anchor cell x 0..16 / z 0..16, y 0 its floor; model +x is the viewer's LEFT;
every piece centred in its cells; collision from the model: a cell gets a barrier when the model fills 30% of it,
in voxels of 1/4 block, a voxel counting when the model touches it at all).
No real brand, logo or product design is copied: the postbox has an envelope pictogram and no postal emblem, the
public phone no company mark, the arcade screen a made-up little shooter, the capsules no characters.

Lights and barriers: a lamp's light is in a cell that never gets a barrier (RetroFurnitureTest). The arcade machine
is 1.5 blocks tall with everything above the first block in its back half, so the upper cell (its light) stays
under 30%; the street lamp's light is in its middle cell, which holds only the thin pole (the lamp hangs just
above it and the top cell may be solid); the dalgona stand is low
and not solid (walk past it like a mat).

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
"""

import math

from economy_machines import display
from models.furniture_japanese import STONE, STONE_DARK, STONE_LIGHT, flat, flicker, moss_on, puff
from stsv_assets.bake import BModel, Paint, bands, cyl, h3, ring

# ================================================================================================
# Paints
# ================================================================================================

WOOD = Paint("wood", 2, "grain", axis="long")
WOOD_DARK = Paint("wood_dark", 2, "grain", axis="long")
STEEL = Paint("steel", 3, "metal")
STEEL_DARK = Paint("steel", 1, "metal")
ALU = Paint("steel", 4, "metal", noise=0.03)            # 양은 (aluminium): pale and a little dull
IRON = Paint("iron", 2, "metal")
RUBBER = Paint("rubber", 2, noise=0.02)
RED_TIN = Paint("pepper", 2, "lacquer", noise=0.02)     # painted tin: postbox, capsule machine
ONGGI = Paint("onggi", 2, "lacquer", noise=0.04)
SIDE_FACES = ("north", "south", "east", "west")


def glow(ramp, step, decal=None, frames=None, frametime=3):
    """A lit surface (screen, bulb, embers): no shading, its own colours."""
    return Paint(ramp, step, glow=True, light=False, noise=0, decal=decal, frames=frames, frametime=frametime)


def arch_z(m, name, z0, z1, cx, y0, r, paint, flat_top=0.0):
    """A half cylinder lying front to back (a rounded roof seen as an arch from the front), its flat side down at
    y0, radius r about x = cx, built from 1 px layers (each layer's half width on the half pixel); flat_top squashes
    it (0.3 = the top 30% lower)."""
    rows = int(math.ceil(r))
    for k in range(rows):
        hw = math.sqrt(max(0.0, r * r - (k + 0.5) ** 2))
        hw = max(0.5, math.floor(hw * 2 + 0.5) / 2)
        lo = y0 + k * (1 - flat_top)
        hi = y0 + min(k + 1, r) * (1 - flat_top)
        m.box(name, (cx - hw, lo, z0), (cx + hw, hi, z1), paint(vrim=0))


def half_disc(m, name, cx, cz, r, y0, y1, toward, paint):
    """A half disc lying flat (a watermelon slice): its round edge towards `toward` ('n' = -z, 's' = +z, 'w' = -x,
    'e' = +x), its straight edge through (cx, cz); built from 1 px rows."""
    for k in range(int(math.ceil(r))):
        hw = math.sqrt(max(0.0, r * r - (k + 0.5) ** 2))
        hw = max(0.5, math.floor(hw * 2 + 0.5) / 2)
        if toward == "n":
            m.box(name, (cx - hw, y0, cz - k - 1), (cx + hw, y1, cz - k), paint)
        elif toward == "s":
            m.box(name, (cx - hw, y0, cz + k), (cx + hw, y1, cz + k + 1), paint)
        elif toward == "w":
            m.box(name, (cx - k - 1, y0, cz - hw), (cx - k, y1, cz + hw), paint)
        else:
            m.box(name, (cx + k, y0, cz - hw), (cx + k + 1, y1, cz + hw), paint)


def melon_flesh(cx, cz, r, seed=0, bowl=False):
    """Decal: a watermelon's cut face round (cx, cz): green rind, a pale band, red flesh with black seeds; on the
    sides the striped rind (a bowl: all round; a slice: its round edge, the straight cut red)."""
    def decal(c):
        d = math.hypot(c.p[0] - cx, c.p[2] - cz)
        if c.face in ("up",):
            if d > r - 0.8:
                return ("cucumber", 1)
            if d > r - 1.4:
                return ("melon_net", 1)
            if h3(c.i[0], 0, c.i[2], seed + 5) < 0.1 and d < r - 2.0:
                return ("rubber", 1)
            return ("tomato", 2 if d < r - 2.2 else 3)
        if not bowl and d < r - 0.9:
            return ("tomato", 2)
        # the rind round the sides: dark green with lighter stripes
        stripe = math.floor(math.atan2(c.p[2] - cz, c.p[0] - cx) * 4) % 2
        return ("cucumber", 1 if stripe else 2)
    return decal


# ================================================================================================
# 평상 2x2: a wooden platform in the yard, a woven mat, watermelon on a tin tray, a fan, a mosquito coil
# ================================================================================================

def pyeongsang():
    """평상 2x2 (1.9 x 1.9 blocks, half a block high): a low wooden platform on six square legs with stretchers
    low between them, its top boards edged with a darker rim; a woven straw mat (돗자리) with red edging; in the
    middle a round tin tray with half a watermelon (cut face up, black seeds) and three slices; a round straw fan
    and a green mosquito coil smouldering on its little tin stand (the ember glows)."""
    m = BModel("pyeongsang")
    m.group("frame")
    leg = Paint("wood_dark", 2, "grain", axis="y")
    for (x, z) in ((1.5, 1.5), (28.5, 1.5), (1.5, 28.5), (28.5, 28.5), (15, 1.5), (15, 28.5)):
        m.box("leg", (x, 0, z), (x + 2, 6, z + 2), leg)
    for z in (2, 29):
        m.box("stretcher", (3.5, 1.5, z), (15, 2.5, z + 1), WOOD_DARK)
        m.box("stretcher", (17, 1.5, z), (28.5, 2.5, z + 1), WOOD_DARK)
    for x in (2, 29):
        m.box("stretcher", (x, 1.5, 3.5), (x + 1, 2.5, 28.5), WOOD_DARK)
    for (a, b) in ((1, 2.5), (29.5, 31)):
        m.box("apron", (1, 5, a), (31, 6, b), WOOD_DARK)
        m.box("apron", (a, 5, 2.5), (b, 6, 29.5), WOOD_DARK)
    m.group("top")
    m.box("boards", (1, 6, 1), (31, 7, 31), Paint("wood", 2, "planks", axis="x", width=4, joint=0))
    rim = Paint("wood_dark", 3, "grain", axis="long", rim=1.3)
    m.box("rim", (0.5, 6, 0.5), (31.5, 7.5, 1.5), rim)
    m.box("rim", (0.5, 6, 30.5), (31.5, 7.5, 31.5), rim)
    m.box("rim", (0.5, 6, 1.5), (1.5, 7.5, 30.5), rim)
    m.box("rim", (30.5, 6, 1.5), (31.5, 7.5, 30.5), rim)
    m.group("mat")

    def mat(c):
        x, z = c.p[0], c.p[2]
        if min(x - 3, 29 - x, z - 3, 29 - z) < 1.0:
            return ("fabric_red", 2 if (c.i[0] + c.i[2]) % 2 else 1)       # the woven red edging
        if (c.i[0] if c.face == "up" else c.i[1]) % 2 == 0:
            return ("straw", 3)
        return ("straw", 2)
    m.box("dotjari", (3, 7, 3), (29, 7.25, 29), Paint("straw", 2, decal=mat, rim=0.5))
    m.group("tray")
    tin = Paint("steel", 4, "metal", noise=0.03, rim=1.2)
    cyl(m, "tray", (16, 15.5), 13, 7.25, 7.75, tin)
    ring(m, "tray_rim", (16, 15.5), 13, 11.5, 7.75, 8.25, tin)
    # half a watermelon, cut face up (a bowl of rind under the red face)
    for (d, lo, hi) in ((4, 7.75, 8.25), (6, 8.25, 9), (7, 9, 10.75)):
        cyl(m, "watermelon", (18, 17.5), d, lo, hi, Paint("cucumber", 2, decal=melon_flesh(18, 17.5, 3.5, 1, True), rim=0.6))
    # two slices lying on the tray, rind outwards
    for (cx, cz, toward, s) in ((14, 13.5, "n", 2), (11, 16.75, "e", 3)):
        half_disc(m, "slice", cx, cz, 2.75, 7.75, 9, toward, Paint("tomato", 2, decal=melon_flesh(cx, cz, 2.75, s), rim=0.5))
    m.group("fan")

    def fan_face(c):
        a = math.atan2(c.p[2] - 24.5, c.p[0] - 7)
        if math.hypot(c.p[2] - 24.5, c.p[0] - 7) < 0.9:
            return ("wood_dark", 2)
        return ("straw", 3 if math.floor(a * 5) % 2 else 2)
    cyl(m, "buchae", (7, 24.5), 6, 7.25, 7.5, Paint("straw", 2, decal=fan_face, rim=0.4))
    m.box("buchae_handle", (6.5, 7.25, 18), (7.5, 7.75, 22), flat("wood_dark", 2))
    m.group("coil")
    cyl(m, "coil_stand", (24.5, 25), 8, 7.25, 7.5, flat("steel", 3))
    m.box("coil_pin", (24.25, 7.5, 24.75), (24.75, 8.75, 25.25), flat("steel", 2))

    def spiral(c):
        # the green coil wound round three times, the tin stand showing between its turns
        dx, dz = c.p[0] - 24.5, c.p[2] - 25
        r, a = math.hypot(dx, dz), math.atan2(dz, dx)
        if r < 0.9:
            return ("bottle_green", 1)
        return ("bottle_green", 2) if (r - (a + math.pi) / (2 * math.pi) * 1.8) % 1.8 < 1.0 else ("steel", 3)
    cyl(m, "coil", (24.5, 25), 7, 7.5, 7.75, Paint("bottle_green", 2, decal=spiral, rim=0.2))

    def ember(c):
        return ("fire", 3 + flicker(c.frame, 1) * 2)
    m.box("ember", (27.5, 7.75, 24.75), (28, 8.1, 25.25), glow("fire", 3, decal=ember, frames=12))
    m.group("shoes")
    # a pair of white rubber shoes (고무신) left at the platform's edge, toes out, one a little askew
    shoe = Paint("porcelain", 3, noise=0, rim=1.2, decal=lambda c: ("porcelain", 1) if c.face == "up" else None)
    for (x, z) in ((6.5, 0.75), (8.75, 1.25)):
        m.box("shoe", (x, 0, z), (x + 1.75, 0.5, z + 3.5), Paint("porcelain", 2, noise=0, rim=1.0))
        m.box("shoe", (x + 0.25, 0.5, z + 0.25), (x + 1.5, 1.25, z + 2.5), shoe)
    return display(m, (2, 1, 2), 0)


# ================================================================================================
# 빨간 우체통 1x1, 높이 2
# ================================================================================================

def red_postbox():
    """빨간 우체통 (1.4 blocks): a red tin box with a rounded roof (its rolled rim front and back) on a dark iron
    plinth, the letter slot under a little visor, a raised cream plate with a plain white envelope pictogram (no
    postal emblem) and below it the collection-times plate; a hinged door with a brass lock on the back; the paint
    grimy and chipped at the foot, a few rust spots on the plinth, an old postcode sticker on one side."""
    m = BModel("red_postbox")
    m.group("plinth")

    def plinth_wear(c):
        if c.face in SIDE_FACES and c.p[1] < 0.6:
            return ("wood_dark", 1)                        # mud splashed on the foot
        if h3(c.i[0], c.i[1], c.i[2], 23) < 0.12:
            return ("fox", 1)                              # rust spots
        return None
    m.box("plinth", (3, 0, 3), (13, 1.5, 13), Paint("iron", 2, "metal", decal=plinth_wear))
    m.box("plinth_top", (3.5, 1.5, 3.5), (12.5, 2, 12.5), flat("iron", 3))
    for (x, z) in ((3.75, 3.75), (11.75, 3.75), (3.75, 11.75), (11.75, 11.75)):
        m.box("bolt", (x, 1.5, z), (x + 0.5, 1.75, z + 0.5), flat("steel", 4))

    def wear(c):
        x, y, z = c.p
        # an old postcode sticker on the left side: white, a blue band and a grey line of print
        if c.face == "east" and 9 <= c.i[1] <= 11 and 6 <= c.i[2] <= 9:
            if c.i[1] == 11:
                return ("denim", 3)
            return ("steel", 4) if c.i[1] == 10 else ("porcelain", 3)
        if c.face in SIDE_FACES and y < 5:
            r = h3(c.i[0], c.i[1], c.i[2], 21)
            if r < 0.05 + (5 - y) * 0.04:
                return ("pepper", 0)                       # chips and grime at the foot, thicker lower down
            if y < 3.5 and r > 0.94:
                return ("fox", 1)                          # a bloom of rust
        return None
    body = Paint("pepper", 2, noise=0.0, decal=wear)
    m.group("box")
    m.box("body", (3, 2.75, 3), (13, 16, 13), body)
    m.box("seam", (2.75, 2, 2.75), (13.25, 2.75, 13.25), flat("pepper", 1))
    m.box("roof_rim", (2.5, 16, 2.5), (13.5, 16.75, 13.5), Paint("pepper", 3, noise=0, rim=1.4))
    arch_z(m, "roof", 2.75, 13.25, 8, 16.75, 5.25, Paint("pepper", 2, noise=0, rim=0.8), flat_top=0.3)
    # the roof's rolled rim, a lip standing proud round the arch at the front and the back
    lip = Paint("pepper", 3, noise=0, rim=1.3)
    arch_z(m, "roof_lip", 2.25, 2.75, 8, 16.75, 5.5, lip, flat_top=0.3)
    arch_z(m, "roof_lip", 13.25, 13.75, 8, 16.75, 5.5, lip, flat_top=0.3)
    m.group("plates")

    def envelope(c):
        if c.face != "north":
            return ("cream", 2)
        ix, iy = c.i[0], c.i[1]
        # a short, wide white envelope (8 x 5 px) in a cream frame; its flap a shallow V in the top third, 1 px of
        # light grey barely darker than the paper, nothing else inside (nothing that could read as a face)
        if 4 <= ix <= 11 and 8 <= iy <= 12:
            col, row = ix - 4, 12 - iy
            if (row == 0 and col in (0, 1, 2, 5, 6, 7)) or (row == 1 and col in (3, 4)):
                return ("porcelain", 1)
            return ("porcelain", 4)
        return ("cream", 2)
    m.box("plate", (3.75, 7.25, 2.75), (12.25, 13.75, 3), Paint("cream", 2, noise=0, rim=1.0, decal=envelope))

    def times(c):
        if c.face != "north":
            return ("cream", 3)
        ix, iy = c.i[0], c.i[1]
        if (iy == 5 and 6 <= ix <= 9) or (iy == 3 and 6 <= ix <= 8):
            return ("steel", 4)                            # two thin lines of writing
        return ("cream", 4)
    m.box("times_plate", (5, 2.75, 2.75), (11, 6.75, 3), Paint("cream", 4, noise=0, rim=1.0, decal=times))
    m.group("slot")
    m.box("visor", (4.5, 15, 1.75), (11.5, 15.75, 3), Paint("pepper", 3, noise=0, rim=1.5))
    m.box("visor_side", (4.5, 14, 2), (5, 15, 3), flat("pepper", 2))
    m.box("visor_side", (11, 14, 2), (11.5, 15, 3), flat("pepper", 2))
    m.box("slot", (5, 14, 2.75), (11, 15, 3), flat("rubber", 1, light=False))
    m.group("door")
    m.box("door", (4, 4, 13), (12, 15, 13.25), Paint("pepper", 2, noise=0))
    for y in (5, 13):
        m.box("hinge", (11.5, y, 13.25), (12.25, y + 1.5, 13.5), flat("steel", 2))
    m.box("lock", (5, 9, 13.25), (6, 10.5, 13.75), Paint("brass", 2, "metal"))
    m.box("keyhole", (5.25, 9.5, 13.75), (5.75, 10, 13.85), flat("rubber", 1, light=False))
    return display(m, (1, 1.25, 1), 0)


# ================================================================================================
# 공중전화 1x1, 높이 2
# ================================================================================================

def public_phone():
    """공중전화 (1.95 blocks): a coin telephone under a rounded cream hood with an orange stripe, on a steel post set
    in a concrete foot: the green phone body with its coin slot and a lit little window, a 3 x 4 keypad, the coin
    return cup, the black handset on its hook at the left and its coiled cord. No company mark."""
    m = BModel("public_phone", shift=(0, 0, -0.75))     # centred front to back (the hood reaches forward)
    m.group("post")
    m.box("foot", (5, 0, 7.5), (11, 1.5, 13.5), STONE)
    def post_bills(c):
        y = c.p[1]
        if c.face == "north" and 8 <= c.i[1] <= 11:
            return ("steel", 3) if c.i[1] == 10 and c.i[0] == 7 else ("cream", 3)   # a small bill pasted on
        if c.face == "north" and c.i[1] == 5:
            return ("hazard", 2)                           # a yellow sticker
        if c.face in SIDE_FACES and y < 3 and h3(c.i[0], c.i[1], c.i[2], 27) < 0.3:
            return ("fox", 1)                              # rust at the foot
        return None
    m.box("post", (7, 1.5, 10), (9, 16, 12), Paint("steel", 3, "metal", decal=post_bills))
    m.box("post_cap", (6.5, 15.5, 9.5), (9.5, 16.5, 12.5), STEEL_DARK)
    m.group("hood")
    shell = Paint("cream", 2, noise=0.02, rim=1.2)
    inner = Paint("cream", 1, noise=0.02, ao=1.3)

    def stripe(c):
        if 25.5 < c.p[1] < 27:
            return ("fox", 3)
        return None
    shell_s = shell(decal=stripe)
    m.box("back", (2.5, 16.5, 12), (13.5, 29, 13), shell_s, faces={"north": inner(decal=stripe)})
    # the sides flare out towards the top: three steps, each reaching further forward
    for (y0, y1, z0) in ((16.5, 20, 9), (20, 24, 7), (24, 29, 5)):
        for x in (2.5, 12.5):
            m.box("side", (x, y0, z0), (x + 1, y1, 12), shell_s,
                  faces={("west" if x > 8 else "east"): inner(decal=stripe)})
    # the roof: rounded towards the front, overhanging
    m.box("roof", (2, 29, 4), (14, 30, 13.5), shell_s, faces={"down": inner})
    m.box("roof", (2.5, 30, 5), (13.5, 30.75, 13), shell_s)
    m.box("roof", (3.5, 30.75, 6.5), (12.5, 31.25, 12.5), shell_s)
    m.box("roof_lip", (2, 28.25, 4), (14, 29, 4.75), Paint("fox", 2, noise=0, rim=1.2))
    m.group("phone")

    def phone_face(c):
        if c.face != "north":
            return None
        x, y = c.p[0], c.p[1]
        if 6.5 < x < 9.5 and 26 < y < 27.5:
            return ("glow_amber", 1)                        # the little window (credit left)
        if 5.5 < x < 7.5 and 24 < y < 25:
            return ("brass", 2)                            # the coin slot plate
        return None
    green = Paint("sign_green", 2, noise=0.03, decal=phone_face, rim=1.2)
    m.box("body", (5, 18.5, 9), (11, 28, 11.75), green)
    m.box("coin_slot", (5.75, 24.25, 8.75), (7.25, 24.75, 9), flat("rubber", 1, light=False))
    m.box("window", (6.5, 26, 8.75), (9.5, 27.5, 9), glow("glow_amber", 1))
    # keypad: 3 x 4 grey buttons on a dark plate
    m.box("keypad", (6.75, 19.5, 8.75), (10.25, 23.5, 9), flat("rubber", 2))
    for row in range(4):
        for col in range(3):
            x, y = 7 + col * 1.1, 22.6 - row * 0.95
            m.box("key", (x, y, 8.5), (x + 0.7, y + 0.6, 8.75), flat("porcelain", 1 if (row, col) != (3, 0) else 0))
    m.box("cup", (8, 19, 8.25), (10, 19.5, 9), STEEL_DARK)          # the coin return cup
    m.box("cup", (8, 19.5, 8.25), (8.25, 20.25, 9), STEEL_DARK)
    m.box("cup", (9.75, 19.5, 8.25), (10, 20.25, 9), STEEL_DARK)
    m.box("hook", (10.25, 24.5, 8.5), (11.25, 25.25, 9), STEEL_DARK)
    # the handset on its hook at the viewer's left (+x), standing up against the side
    hs = Paint("rubber", 2, "lacquer", noise=0.02)
    m.box("handset", (11.5, 21.25, 9), (12.25, 26, 10), hs)
    m.box("earpiece", (11.25, 26, 8.25), (12.5, 27.75, 10.25), hs)
    m.box("mouthpiece", (11.25, 19.75, 8.25), (12.5, 21.25, 10.25), hs)
    # the coiled cord hanging from its foot back to the body
    for k in range(6):
        y = 19.5 - k * 0.6
        m.box("cord", (10.75 + (k % 2) * 0.25, y - 0.5, 9.25), (11.25 + (k % 2) * 0.25, y, 9.75), flat("rubber", 1))
    m.box("cord", (10.25, 16, 9.5), (10.75, 16.5, 10), flat("rubber", 1))
    # a little steel shelf under the phone for your coins, two of them left on it
    m.box("shelf", (4.5, 17.75, 8), (10, 18.25, 11), Paint("steel", 2, "metal", rim=1.2))
    m.box("shelf_lip", (4.5, 18.25, 8), (10, 18.5, 8.25), flat("steel", 3))
    cyl(m, "coin", (6, 9.5), 1, 18.25, 18.5, Paint("brass", 3, "metal"))
    m.group("hood")
    m.box("hood_scuff", (2.25, 22, 8), (2.5, 23, 9), flat("cream", 0))
    return display(m, (1, 2, 1), 0)


# ================================================================================================
# 달고나 좌판 1x1
# ================================================================================================

def dalgona_stand():
    """달고나 좌판: a low plank stand on an apple crate, a little briquette brazier with its embers glowing (it
    flickers) and the dented ladle of melting sugar on it, a tin tray of finished dalgona (a star and a heart pressed
    in), the press, the star cutter, the soda tin and a sugar sack."""
    m = BModel("dalgona_stand")
    m.group("crate")
    crate = Paint("wood", 2, "planks", axis="x", width=2, noise=0.06)
    for y0 in (0.5, 3):
        m.box("crate_slat", (2, y0, 2.5), (14, y0 + 1.5, 3), crate)
        m.box("crate_slat", (2, y0, 13), (14, y0 + 1.5, 13.5), crate)
        m.box("crate_slat", (2, y0, 3), (2.5, y0 + 1.5, 13), crate)
        m.box("crate_slat", (13.5, y0, 3), (14, y0 + 1.5, 13), crate)
    for (x, z) in ((2.5, 3), (12.5, 3), (2.5, 12), (12.5, 12)):
        m.box("crate_post", (x, 0, z), (x + 1, 5, z + 1), WOOD_DARK)
    m.box("plank", (1, 5, 1), (15, 6, 15), Paint("wood", 3, "planks", axis="x", width=4, joint=0, rim=1.2))
    m.group("brazier")
    # a round tin brazier at the back holding one briquette: its holes glow and the fire breathes (animated)
    bx, bz = 11.5, 10.5
    cyl(m, "brazier", (bx, bz), 6, 6, 8, Paint("iron", 2, "metal", decal=lambda c: ("fox", 1) if h3(*c.i, 17) < 0.1 else None))
    ring(m, "brazier_rim", (bx, bz), 6, 4.5, 8, 8.5, Paint("steel", 2, "metal", rim=1.3))
    m.box("air_hole", (10.75, 6.5, 7.45), (12.25, 7.5, 7.55), glow("fire", 3))

    def embers(c):
        r = h3(c.i[0], c.i[1], c.i[2], 13)
        dx, dz = c.p[0] - bx, c.p[2] - bz
        hole = any(math.hypot(dx - hx, dz - hz) < 0.45 for hx in (-1, 0, 1) for hz in (-1, 0, 1))
        breath = (1 if (c.frame + int(r * 12)) % 12 < 5 else 0)
        if hole:
            return ("fire", 4 + breath)                    # the briquette's holes, the hottest
        return ("fire", 2 + breath - (1 if r < 0.3 else 0))
    cyl(m, "briquette", (bx, bz), 4.5, 7.75, 8.25, glow("fire", 3, decal=embers, frames=12, frametime=4))
    # the ladle (국자): a dented tin bowl on the brazier, the sugar in it melted to a bubbling caramel, a wooden
    # chopstick standing in it, its long handle reaching forward
    cyl(m, "ladle", (bx, bz), 3.5, 8.5, 9.5, ALU)

    def molten(c):
        return ("fox", 3) if h3(c.i[0], 0, c.i[2], 19) < 0.3 else ("onion", 2)
    cyl(m, "sugar", (bx, bz), 2.5, 9.5, 9.6, Paint("onion", 2, noise=0, decal=molten))
    m.box("ladle_handle", (11.25, 9.25, 4), (11.75, 9.5, 8.75), flat("wood_dark", 2))
    m.box("chopstick", (12, 9.6, 10.25), (12.4, 12.5, 10.65), flat("wood", 3))
    m.group("tray")
    tray = Paint("steel", 4, "metal", noise=0.03, rim=1.2)
    m.box("tray", (1, 6, 1.25), (8.25, 6.5, 14.75), tray)
    for (x0, z0, x1, z1) in ((1, 1.25, 8.25, 1.75), (1, 14.25, 8.25, 14.75), (1, 1.75, 1.5, 14.25), (7.75, 1.75, 8.25, 14.25)):
        m.box("tray_lip", (x0, 6.5, z0), (x1, 7, z1), tray)
    star = ("..#..", ".###.", "#####", ".###.", ".#.#.")
    heart = (".#.#.", "#####", "#####", ".###.", "..#..")

    def candy(shape, cx, cz):
        def inside(col, row):
            return 0 <= col < 5 and 0 <= row < 5 and shape[row][4 - col] == "#"

        def decal(c):
            if c.face != "up":
                return ("onion", 1)                        # the thin caramel edge
            col, row = int(math.floor(c.p[0] - cx + 2.5)), int(math.floor(c.p[2] - cz + 2.5))
            if inside(col, row):
                return ("onion", 4)                        # the shape pressed in, thin and pale
            if math.hypot(c.p[0] - cx, c.p[2] - cz) > 2.6:
                return ("onion", 0)                        # the rim, cooked darker
            return ("onion", 1)
        return decal
    for (cx, cz, shape) in ((4.5, 4.75, star), (4.5, 11.25, heart)):
        cyl(m, "dalgona", (cx, cz), 6, 6.5, 6.75, Paint("onion", 2, decal=candy(shape, cx, cz), rim=0.6))
    m.group("tools")
    # the pressing plate at the front: a fresh round of caramel just flattened, the round press beside it, and the
    # little star mould
    m.box("plate", (8.75, 6, 2.25), (14.75, 6.25, 6.75), Paint("steel", 2, "metal", rim=1.0))
    cyl(m, "fresh", (10.75, 4.5), 3.5, 6.25, 6.5, Paint("onion", 3, noise=0, rim=0.8, decal=lambda c: ("topaz", 3) if c.face == "up" and h3(*c.i, 21) < 0.2 else None))
    cyl(m, "press", (13.25, 4.5), 2.5, 6.25, 6.75, flat("steel", 4, rim=1.2))
    m.box("press_knob", (12.75, 6.75, 4), (13.75, 7.75, 5), flat("wood_dark", 2))
    m.box("mould", (14, 6.25, 6), (14.6, 6.75, 6.6), flat("steel", 4))
    return display(m, (1, 1, 1), 0)


# ================================================================================================
# 뽑기 기계 1x1
# ================================================================================================

def capsule_machine():
    """뽑기 기계: a red tin capsule-toy machine as they stood outside every stationery shop: a clear dome full of
    two-coloured capsules on the red body, the big chrome turning knob with its bar, the coin slot, the price
    card, and the flap the capsule rolls out of. No characters."""
    m = BModel("capsule_machine")
    m.group("body")

    def front(c):
        x, y = c.p[0], c.p[1]
        if c.face in SIDE_FACES and y < 2.5 and h3(c.i[0], c.i[1], c.i[2], 47) < 0.25:
            return ("pepper", 0)                           # scuffed by shoes at the foot
        if c.face != "north":
            return None
        if 4.5 < x < 11.5 and 9 < y < 10.5:
            # the price card: cream with a row of dots (coins)
            return ("pepper", 2) if c.i[0] % 2 == 0 and 9.4 < y < 10.1 and 5 < x < 11 else ("cream", 3)
        return None
    m.box("base", (3, 0, 3.5), (13, 1, 12.5), Paint("iron", 2, "metal", noise=0.02))
    m.box("body", (3.5, 1, 4), (12.5, 11, 12), Paint("pepper", 2, noise=0, decal=front, rim=0.8))
    m.box("body_top", (3, 11, 3.5), (13, 11.75, 12.5), Paint("pepper", 3, noise=0, rim=1.3))
    # chrome corner strips and the bolts holding the front panel
    for x in (3.25, 12.25):
        m.box("corner", (x, 1, 3.75), (x + 0.5, 11, 4.25), flat("steel", 4, rim=1.2))
    for (x, y) in ((4.25, 1.75), (11.25, 1.75), (4.25, 10), (11.25, 10)):
        m.box("bolt", (x, y, 3.75), (x + 0.5, y + 0.5, 4), flat("steel", 4))
    # the turning knob: a chrome disc with a bar across
    cyl(m, "knob", (8, 6.5), 4, 2.5, 4, Paint("steel", 4, "metal", rim=1.4), axis="z")
    m.box("knob_bar", (6, 6, 2), (10, 7, 2.5), flat("steel", 5, rim=1.4))
    m.box("coin_plate", (10.5, 5.5, 3.75), (12, 8.25, 4), flat("steel", 3, rim=1.2))
    m.box("coin_slot", (11, 6, 3.6), (11.5, 7.75, 3.75), flat("rubber", 1, light=False))
    # the exit flap, a capsule waiting behind it
    m.box("exit", (5.5, 1.5, 3.75), (10.5, 4, 4), flat("steel", 2))
    m.box("flap", (6, 2, 3.5), (10, 3.75, 3.75), Paint("rubber", 2, noise=0, rim=1.3))
    # the lock on its side, and a dropped capsule on the ground by the flap
    m.box("lock", (12.5, 7, 7.25), (12.75, 8.5, 8.75), Paint("brass", 2, "metal"))
    m.box("capsule", (11, 0, 1.5), (12.25, 0.75, 2.75), flat("slime", 3, rim=1.0))
    m.box("capsule", (11, 0.75, 1.5), (12.25, 1.25, 2.75), flat("porcelain", 3, rim=1.0))
    m.group("dome")
    colours = (("pepper", 2), ("topaz", 3), ("slime", 2), ("magic", 2), ("fox", 2), ("toadstool", 2))
    GAP = ("water", 2)
    # a capsule seen through the glass: a little sphere in cross-section (a 4 x 2 px middle, a 2 px cap inset top
    # and bottom), split cleanly down the middle into one colour and white, one bright pixel of highlight
    ball = (".##.", "####", "####", ".##.")
    # where they sit on each 10 px face of the globe (u, v of their bottom-left pixel): two in the bottom row, two
    # staggered above them, the second cut by the corner as if it went on round it; the shoulder above is clear
    SLOTS = ((0, 0), (5, 0), (2, 5), (7, 5))

    def capsules(c):
        ix, iy, iz = c.i
        if c.face in ("up", "down"):
            return None
        u, w = (ix - 3, iz) if c.face in ("north", "south") else (iz - 3, ix)
        v = iy - 12
        for k, (u0, v0) in enumerate(SLOTS):
            i, j = u - u0, v - v0
            if 0 <= i < 4 and 0 <= j < 4:
                if ball[3 - j][i] != "#":
                    return GAP
                face_no = ("north", "east", "south", "west").index(c.face)
                ramp, step = colours[(k + face_no * 3) % len(colours)]   # each neighbour a different colour
                left = (k + face_no) % 2 == 0          # and turned the other way
                if (i < 2) != left:
                    return ("porcelain", 3)            # the white half
                if j == 2 and i == (0 if left else 3):
                    return (ramp, step + 2)            # the highlight
                return (ramp, step)
        return None

    def glass_of(c):
        x, y, z = c.p
        if c.face == "down":
            return ("water", 4)
        # the glass outline: the narrow corner bevels and the top edges pale blue-white, so the shape reads first
        if c.face in ("north", "south"):
            u, lo_u, hi_u = x, c.lo[0], c.hi[0]
        elif c.face in ("east", "west"):
            u, lo_u, hi_u = z, c.lo[2], c.hi[2]
        else:
            u, lo_u, hi_u = None, None, None
        if c.face == "up":
            if min(x - c.lo[0], c.hi[0] - x, z - c.lo[2], c.hi[2] - z) < 0.6:
                return ("ice", 5)
        elif hi_u - lo_u <= 1.5 or c.hi[1] - y < 0.6:      # the narrow bevels and the top edge
            return ("ice", 4)
        if c.face in ("east", "west") and c.p[1] < 23 and (z < 3 or z > 13):
            return ("ice", 4)                              # the corner bevels of the front and back slab
        got = capsules(c)
        # a highlight streak down the front left of the glass, over the glass only (not across the capsules)
        if got in (None, GAP) and 13 < y < 24.5 and (
                (c.face == "north" and abs(x - 10 - (y - 13) * 0.1) < 0.55) or
                (c.face == "west" and abs(z - 5 - (y - 13) * 0.1) < 0.55)):
            return ("porcelain", 5)
        if got:
            return got
        return ("water", 3)                                # the clear glass, a light blue
    glass = Paint("ice", 2, decal=glass_of, rim=0, ao=0.1, noise=0, light=True)
    # a round-cornered globe from few, large faces: an octagon of boxes for the belly, two tiers for the shoulder
    m.box("dome_ring", (3, 11.75, 3.5), (13, 12, 12.5), Paint("steel", 3, "metal"))
    m.box("dome", (3, 12, 2), (13, 23, 14), glass)
    for (x0, x1) in ((2, 3), (13, 14)):
        m.box("dome", (x0, 12, 3), (x1, 23, 13), glass)
    m.box("dome", (4.5, 23, 3.5), (11.5, 24.25, 12.5), glass)
    for (x0, x1) in ((3.5, 4.5), (11.5, 12.5)):
        m.box("dome", (x0, 23, 4.5), (x1, 24.25, 11.5), glass)
    m.box("dome", (5.5, 24.25, 5.5), (10.5, 25, 10.5), glass)
    cyl(m, "cap", (8, 8), 4, 25, 25.75, Paint("pepper", 2, noise=0, rim=1.3))
    cyl(m, "cap_knob", (8, 8), 2, 25.75, 26.5, Paint("pepper", 3, noise=0))
    return display(m, (1, 1.66, 1), 0)


# ================================================================================================
# 오락기 1x1, 높이 2 (1.5 blocks)
# ================================================================================================

ARCADE_FRAMES = 16


def arcade_screen(c):
    """The screen's picture at frame c.frame: a made-up little shooter on a scrolling starfield (a small ship at
    the bottom sliding left and right, its shots climbing, round blobs drifting down), a score row on top."""
    if c.face != "north":
        return ("rubber", 1)
    k = c.frame
    # screen pixel grid: u 0..7 across (from the viewer's left = +x), v 0..10 down
    u = int(math.floor(12 - c.p[0]))
    v = int(math.floor((21 - c.p[1]) * 2))
    bg = ("obsidian", 0)
    # the scrolling stars
    if h3(u, (v + k) // 1, 0, 51) < 0.07:
        bg = ("porcelain", 1)
    if v == 0:
        # the score: a few lit digits on the left, the lives on the right
        return ("hazard", 1) if u in (0, 1, 2) and (u + k // 4) % 3 != 1 else ("pepper", 1) if u == 7 else bg
    ship_u = (1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1, 2, 3, 4, 5, 4)[k]
    if v == 10 and abs(u - ship_u) <= 1:
        return ("spectral", 2)
    if v == 9 and u == ship_u:
        return ("spectral", 3)
    # the shot climbing from where the ship was a few frames ago
    for lag in (2, 6):
        su = (1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1, 2, 3, 4, 5, 4)[(k - lag) % ARCADE_FRAMES]
        if u == su and v == 9 - lag:
            return ("hazard", 1)
    # three blobs drifting down and round
    for (b0, lane, col) in ((0, 2, ("toadstool", 3)), (5, 5, ("slime", 2)), (11, 3, ("magic", 2))):
        bv = (k + b0) % ARCADE_FRAMES // 2 + 1
        bu = (lane + ((k + b0) // 4) % 2) % 8
        if v == bv and u == bu:
            return col
    return bg


def arcade_machine():
    """오락실 오락기 (1.5 blocks): an upright cabinet in black with a red-and-yellow stripe down its sides, the
    control panel jutting out with a red ball-top joystick and three buttons, the coin door with two lit coin
    slots, the screen leaning back in its bezel playing a made-up little shooter (animated), and the lit marquee
    on top. Everything above its first block is in its back half (its light cell stays under the barrier fill)."""
    m = BModel("arcade_machine")
    black = Paint("rubber", 2, noise=0.02, rim=1.2)

    def side_art(c):
        if c.face not in ("east", "west"):
            return None
        # a diagonal stripe pair down the side, on the black
        t = c.p[1] + c.p[2] * 0.9
        if 13 < t < 15:
            return ("pepper", 2)
        if 15 < t < 16.5:
            return ("hazard", 1)
        return None
    sides = black(decal=side_art)
    m.group("cabinet")
    m.box("kick", (2.5, 0, 3), (13.5, 1, 15.5), flat("rubber", 1))
    m.box("base", (2, 1, 2.5), (14, 12, 16), sides)
    # the upper part: in the back half (z 8..16), up to 1.5 blocks
    m.box("head", (2, 15, 8.75), (14, 21, 16), sides)
    m.box("head_side", (2, 12, 8), (14, 15, 16), sides)
    m.box("front_fill", (2.5, 12, 6.5), (13.5, 15, 8), black)
    m.group("panel")
    # the control panel, sloping down towards the player
    m.box("panel", (2, 12, 0.5), (14, 13.5, 6.5), Paint("rubber", 3, noise=0.02, rim=1.4))
    m.box("panel_lip", (2, 11, 0.5), (14, 12, 1), Paint("steel", 3, "metal"))

    def panel_art(c):
        if c.face == "up" and 2.5 < c.p[2] < 3:
            return ("pepper", 2)
        return None
    m.box("panel_top", (2.5, 13.5, 1), (13.5, 13.75, 6.5), Paint("obsidian", 2, decal=panel_art, rim=0.6))
    m.box("stick", (10.25, 13.75, 3), (10.75, 15, 3.5), flat("steel", 3))
    m.box("ball", (9.75, 15, 2.5), (11.25, 15.75, 4), Paint("toadstool", 3, rim=1.5))
    for (x, ramp) in ((7, "toadstool"), (5.5, "hazard"), (4, "denim")):
        m.box("button", (x - 0.5, 13.75, 3.25), (x + 0.5, 14.25, 4.25), Paint(ramp, 2, noise=0, rim=1.5))
    m.group("coin_door")
    m.box("coin_door", (5, 3, 2.25), (11, 10, 2.5), Paint("steel", 2, "metal"))
    for x in (6, 8.75):
        m.box("coin_slot", (x, 7, 2), (x + 1.25, 8.75, 2.25), glow("fox", 3))
    m.box("coin_return", (6.5, 4, 2), (9.5, 5, 2.25), flat("rubber", 1))
    m.group("screen")
    bezel = Paint("rubber", 1, noise=0, rim=1.2)
    m.box("bezel", (2, 15, 8), (14, 15.75, 8.75), bezel)
    m.box("bezel", (2, 21, 8), (14, 21.5, 8.75), bezel)
    m.box("bezel", (2, 15.75, 8), (4, 21, 8.75), bezel)
    m.box("bezel", (12, 15.75, 8), (14, 21, 8.75), bezel)
    m.box("screen", (4, 15.75, 8.5), (12, 21, 8.75),
          glow("obsidian", 0, decal=arcade_screen, frames=ARCADE_FRAMES, frametime=3),
          faces={f: None for f in ("up", "down", "east", "west", "south")})
    m.group("marquee")

    def marquee(c):
        if c.face != "north":
            return ("rubber", 2)
        # a sunburst of red and yellow rays with a white centre, lights chasing round its border
        x, y = c.p[0] - 8, c.p[1] - 22.5
        if abs(y) > 0.95 or abs(x) > 5.6:
            lit = (c.i[0] + c.i[1] + c.frame) % 4 == 0
            return ("lamp", 3) if lit else ("fox", 1)
        a = math.atan2(y * 3, x)
        if math.hypot(x, y * 3) < 1.6:
            return ("lamp", 3)
        return ("hazard", 1) if math.floor(a * 3) % 2 else ("toadstool", 3)
    m.box("marquee", (2, 21.5, 8), (14, 24, 15.5),
          glow("lamp", 2, decal=marquee, frames=ARCADE_FRAMES, frametime=3),
          faces={"up": black, "east": black, "west": black, "south": black, "down": black})
    m.box("marquee_top", (2, 23.5, 15.5), (14, 24, 16), black)
    m.group("back")
    m.box("vents", (4, 3, 16), (12, 10, 16.25), Paint("rubber", 1, decal=lambda c: ("rubber", 0) if c.i[1] % 2 == 0 else None))
    # the red T-moulding down the front edges of the cabinet, a stack of coins and a tin ashtray left on the panel
    for x in (1.75, 13.75):
        m.box("t_moulding", (x, 1.25, 2.25), (x + 0.5, 11.75, 2.75), Paint("pepper", 3, noise=0, rim=1.2))
    cyl(m, "coins", (4.5, 2.5), 1, 13.75, 14.5, Paint("brass", 3, "metal", decal=lambda c: ("brass", 1) if c.face in SIDE_FACES and c.i[1] % 1 == 0 and c.p[1] % 0.5 < 0.15 else None))
    cyl(m, "ashtray", (3.75, 5), 1.5, 13.75, 14.25, Paint("steel", 3, "metal", rim=1.2, decal=lambda c: ("rubber", 1) if c.face == "up" else None))
    return display(m, (1, 1.5, 1), 0)


# ================================================================================================
# 고무대야 텃밭 1x1
# ================================================================================================

def tub_garden():
    """고무대야 텃밭: a round black rubber tub of soil grown into a garden: two pepper plants tied to their sticks with
    red peppers hanging and a few still green, three heads of red-leaf and green lettuce in front, a chipped rim."""
    m = BModel("tub_garden")
    m.group("tub")

    def tub_side(c):
        if c.face in SIDE_FACES and abs(c.p[1] - 4) < 0.4:
            return ("rubber", 1)                           # the moulded ridge round the tub
        if c.face in SIDE_FACES and c.p[1] < 1.5 and h3(c.i[0], c.i[1], c.i[2], 29) < 0.35:
            return ("soil", 3)                             # dried mud splashed on its foot
        return None
    tub = Paint("rubber", 2, decal=tub_side, rim=0.8)
    cyl(m, "tub", (8, 8), 13, 0, 5.5, tub)
    cyl(m, "tub_rim", (8, 8), 14, 5.5, 6.5, Paint("rubber", 3, rim=1.2))     # the rolled rim round the soil
    cyl(m, "soil", (8, 8), 12, 6.5, 6.75, Paint("soil", 2, "speckle", decal=lambda c: ("soil", 1) if h3(c.i[0] // 2, 0, c.i[2] // 2, 37) < 0.35 else None))
    # an ice-cream stick for a plant label, a name scrawled on it
    m.box("label", (2.5, 6.75, 6.75), (3.25, 9.25, 7), Paint("wood", 4, noise=0, decal=lambda c: ("wood_dark", 1) if c.face == "north" and c.i[1] == 8 else None))
    m.group("peppers")
    stake = Paint("bamboo", 3, "bamboo", axis="y", node=5)

    def leafy(seed):
        def decal(c):
            r = h3(c.i[0], c.i[1], c.i[2], seed)
            return ("leaf", 1) if r < 0.18 else ("leaf", 4) if r > 0.86 else None
        return decal
    for (cx, cz, top, seed) in ((5, 10, 16, 1), (11, 10.75, 15, 2)):
        # the bush: a few clumps of leaves round the stem, tied to its stick
        bush = Paint("leaf", 2, rim=0.9, decal=leafy(seed + 90))
        bush_light = Paint("leaf", 3, rim=0.9, decal=leafy(seed + 91))
        m.box("stem", (cx - 0.25, 6.75, cz - 0.25), (cx + 0.25, top - 6.4, cz + 0.25), flat("leaf_dark", 2))
        m.box("leaves", (cx - 1.85, top - 6.4, cz - 1.65), (cx + 1.65, top - 3.1, cz + 1.85), bush)
        m.box("leaves", (cx - 1.15, top - 3.1, cz - 1.35), (cx + 1.35, top - 0.9, cz + 1.15), bush_light)
        m.box("leaves", (cx + 1.4, top - 5.1, cz - 1.1), (cx + 2.9, top - 3.6, cz + 0.9), bush_light)
        m.box("leaves", (cx - 2.9, top - 6.1, cz - 0.6), (cx - 1.6, top - 4.6, cz + 1.4), bush)
        m.box("stake", (cx - 0.1, 6.75, cz + 2.6), (cx + 0.4, top + 0.6, cz + 3.1), stake)
        m.box("tie", (cx - 0.6, top - 2.6, cz + 1.1), (cx + 0.9, top - 2.1, cz + 3.4), flat("porcelain", 2))
        # the peppers hanging under the leaves: red ones and a green one
        for (px, pz, py, ripe) in ((-1.2, -2.1, -6.4, True), (1.1, -2.0, -6.4, True), (2.1, -0.6, -5.1, False)):
            x, z, y = cx + px, cz + pz, top + py
            pp = Paint("pepper", 3, rim=1.2) if ripe else Paint("cucumber", 3, rim=1.0)
            m.box("pepper", (x - 0.4, y - 2.4, z - 0.4), (x + 0.4, y, z + 0.4), pp)
            m.box("pepper_cap", (x - 0.3, y, z - 0.3), (x + 0.3, y + 0.4, z + 0.3), flat("leaf_dark", 2))
    m.group("lettuce")

    def ruffle(red):
        def decal(c):
            r = h3(c.i[0], c.i[1], c.i[2], 95)
            if red and (c.face != "up" or r < 0.55):
                return ("wine", 3 if r < 0.7 else 2)       # 적상추: the ruffled leaf tips wine red
            return ("leaf", 4 if r < 0.35 else 3)
        return decal
    for (cx, cz, red, h) in ((4.6, 4.4, True, 8.1), (8.4, 3.6, False, 8.35), (11.9, 5.1, True, 7.85)):
        head = Paint("leaf", 3, rim=1.0, decal=ruffle(red))
        m.box("lettuce", (cx - 1.85, 6.75, cz - 1.85), (cx + 1.85, h, cz + 1.85), head)
        m.box("lettuce", (cx - 1.1, h, cz - 1.1), (cx + 1.1, h + 0.8, cz + 1.1), Paint("leaf", 4, rim=1.0, decal=ruffle(red)))
    return display(m, (1, 1, 1), 0)


# ================================================================================================
# 전봇대 가로등 1x1, 높이 3
# ================================================================================================

def street_lamp():
    """전봇대 가로등 (3 blocks): a grey concrete utility pole in a concrete collar, its number tag and a torn flyer
    pasted on it, iron climbing rungs, a guy wire to the ground behind; at the top a clean crossarm with white
    insulators on a clamp band, and below it the curved steel arm of a street lamp holding a big, wide, shallow
    enamel shade (green outside, white inside) over a warm bulb, reaching out over the street in front.
    Drawn from y 0 and moved down a block (dy -16). The lamp hangs at the bottom of the top block and its light is
    in the middle cell, which holds only the thin pole and its rungs (16 of 64 voxels: no barrier). The shade
    reaches 7 px out in front of the cell, so the guy wire's anchor reaches as far out behind it: still centred."""
    m = BModel("street_lamp", dy=-16)
    m.group("foot")
    m.box("collar", (3, 0, 3), (13, 4, 13), STONE)
    m.box("collar_top", (3.5, 4, 3.5), (12.5, 4.75, 12.5), STONE_LIGHT)
    m.group("pole")

    def pole_marks(c):
        if c.face != "north":
            return None
        x, y = c.p[0], c.p[1]
        if 6.5 < x < 9.5 and 17 < y < 19:
            return ("steel", 2) if c.i[1] == 18 and 7 <= c.i[0] <= 8 else ("porcelain", 3)   # the number tag
        if 6.25 < x < 9.75 and 9 < y < 14 and h3(c.i[0], c.i[1], 0, 61) > 0.18:
            # a flyer pasted on and half torn off: a cream sheet with dark lines and a red title bar
            if c.i[1] == 13:
                return ("pepper", 2)
            return ("rubber", 2) if c.i[1] % 2 == 0 and 7 < x < 9.5 else ("cream", 3)
        return None
    def concrete(c):
        got = pole_marks(c)
        if got:
            return got
        # spun concrete: faint vertical streaks of grime, darker near the ground
        streak = h3(c.i[0], 0, c.i[2], 63)
        if c.face in SIDE_FACES and streak < 0.25:
            return ("millstone", 2)
        if c.face in SIDE_FACES and c.p[1] < 9 and h3(c.i[0], c.i[1], c.i[2], 64) < 0.3:
            return ("millstone", 2)
        return None
    pole = Paint("millstone", 3, noise=0.03, decal=concrete)
    m.box("pole", (6.25, 4.75, 6.25), (9.75, 16, 9.75), pole)
    m.box("pole", (6.5, 16, 6.5), (9.5, 46, 9.5), pole)
    m.box("pole_cap", (6.75, 46, 6.75), (9.25, 46.75, 9.25), flat("millstone", 2))
    # the climbing steps: iron rungs in pairs up the side, out of reach from the ground
    for y in range(17, 34, 3):
        x0 = 9.5 if (y // 3) % 2 else 5.75
        m.box("step", (x0, y, 7.5), (x0 + 0.75, y + 0.5, 8.5), flat("iron", 2))
    # the guy wire behind: from a little concrete anchor in the ground up to the pole at 45 degrees, a yellow and
    # black guard round its foot
    m.group("guy")
    m.box("anchor", (6.75, 0, 21), (9.25, 0.75, 23), STONE_DARK)
    lean = ("x", -45, (8, 0.5, 22))
    m.box("guy_wire", (7.75, 0.5, 21.75), (8.25, 18.2, 22.25), flat("iron", 1), rotation=lean)

    def stripes(c):
        return ("rubber", 1) if c.face in SIDE_FACES and int(math.floor(c.p[1] * 1.5)) % 2 else None
    m.box("guy_guard", (7.625, 0.75, 21.625), (8.375, 6.25, 22.375), Paint("hazard", 3, noise=0, decal=stripes),
          rotation=lean)
    m.group("crossarm")
    # the crossarm near the top: a clean steel channel held by a clamp band round the pole, four white insulators
    m.box("crossarm", (0.5, 42, 7.25), (15.5, 43.25, 8.75), Paint("steel", 2, "metal", rim=1.2))
    m.box("clamp", (6.25, 41.25, 6.25), (9.75, 44, 9.75), Paint("steel", 1, "metal", rim=1.0))
    for x in (1.5, 4.5, 11.5, 14.5):
        m.box("pin", (x - 0.25, 43.25, 7.75), (x + 0.25, 43.75, 8.25), flat("steel", 2))
        m.box("insulator", (x - 0.5, 43.75, 7.5), (x + 0.5, 44.5, 8.5), Paint("porcelain", 2, rim=1.2))
        m.box("insulator_top", (x - 0.375, 44.5, 7.625), (x + 0.375, 44.875, 8.375), Paint("porcelain", 3))
    m.group("lamp")
    # the arm: a steel pipe out of the pole's front under the crossarm, curving forward and down to the shade
    arm = flat("steel", 3, rim=1.2)
    m.box("arm_bracket", (7.25, 37.5, 6), (8.75, 40.5, 6.5), flat("steel", 2))
    m.box("arm", (7.5, 39, 3.5), (8.5, 40, 6), arm)
    m.box("arm", (7.5, 38.5, 0.5), (8.5, 39.5, 3.5), arm)
    m.box("arm", (7.5, 38, -1.5), (8.5, 39, 0.5), arm)

    def enamel(c):
        if c.face == "down":
            return ("porcelain", 4)                        # the white enamel inside, lit by the bulb
        if c.face in SIDE_FACES and c.p[1] < 34.5:
            return ("sign_green", 1)                       # the rolled rim, a shade darker
        return None
    shade = Paint("sign_green", 3, noise=0.02, rim=1.3, decal=enamel)
    # the enamel shade: a wide, shallow cone 12 px across (green outside, white inside), the bulb under it
    cz = -1
    cyl(m, "shade", (8, cz), 12, 34, 34.5, shade)
    cyl(m, "shade", (8, cz), 10, 34.5, 35.25, shade)
    cyl(m, "shade", (8, cz), 7, 35.25, 36, shade)
    cyl(m, "shade_hub", (8, cz), 3, 36, 38, shade)
    cyl(m, "bulb", (8, cz), 3, 32.5, 34, glow("lamp", 4))
    return display(m, (1, 2.95, 1), -16)


# ================================================================================================
# 빨랫줄 3x1, 높이 2
# ================================================================================================

def clothesline():
    """빨랫줄 3x1 (two blocks high): two T-posts in concrete feet with a line between them, held up in the middle by
    a forked bamboo prop (바지랑대); on it, with wooden pegs, a white sleeveless undershirt, a big pink floral
    blanket folded over the line, a striped towel and a pair of socks. Drawn over x 0..48, its cells x -1..1."""
    m = BModel("clothesline", shift=(-16, 0, 0))
    m.group("posts")
    post = Paint("wood", 1, "grain", axis="y", noise=0.06)
    for x in (2, 44):
        m.box("foot", (x - 1, 0, 6), (x + 3, 2, 10), STONE)
        m.box("post", (x, 2, 7), (x + 2, 28, 9), post)
        m.box("bar", (x - 0.5, 27, 5), (x + 2.5, 28.5, 11), WOOD)
        m.box("hook", (x + 0.5, 26.5, 7.5), (x + 1.5, 27, 8.5), flat("steel", 2))
    m.group("line")
    m.box("line", (4, 26.25, 7.75), (44, 26.75, 8.25), flat("rice", 1))
    # the bamboo prop (바지랑대): from the ground behind the blanket, leaning along the line up to it, forked at the top
    prop = Paint("bamboo", 3, "bamboo", axis="y", node=6)
    m.box("prop", (26.5, 0, 10), (27.5, 28.25, 11), prop, rotation=("z", 22.5, (27, 0, 10.5)))
    m.group("clothes")
    peg = flat("hinoki", 2, rim=1.2)
    # the undershirt (러닝셔츠): white, the deep arm holes and neck cut out of the top
    shirt = Paint("rice", 2, "weave", rim=0.5)
    m.box("shirt", (5, 15, 7.75), (13, 23.5, 8.25), shirt)
    m.box("shirt_strap", (5, 23.5, 7.75), (6.5, 26.25, 8.25), shirt)
    m.box("shirt_strap", (11.5, 23.5, 7.75), (13, 26.25, 8.25), shirt)
    m.box("shirt_hem", (5, 14.5, 7.75), (13, 15, 8.25), Paint("rice", 1, "weave"))
    for x in (5.25, 11.75):
        m.box("peg", (x, 25.75, 7.5), (x + 1, 27.5, 8.5), peg)

    def floral(c):
        if c.face in ("up", "down"):
            return ("pink", 2)
        u = c.i[0]
        v = c.i[1]
        cell_u, cell_v = math.floor(u / 4), math.floor(v / 4)
        lu, lv = u - cell_u * 4 - (1.5 if cell_v % 2 else 0), v - cell_v * 4
        lu %= 4
        if abs(lu - 1.5) + abs(lv - 1.5) < 1.1:
            return ("fabric_red", 3) if h3(cell_u, cell_v, 0, 71) < 0.5 else ("hazard", 1)   # little flowers
        if abs(lu - 1.5) + abs(lv - 1.5) < 1.6 and (lu + lv) % 2 < 1:
            return ("leaf", 3)
        if c.p[1] < 10.5:
            return ("fabric_red", 2)                       # the hem band
        return ("pink", 2)
    quilt = Paint("pink", 2, decal=floral, rim=0.4)
    m.box("ibul", (17, 10, 6.5), (32, 26.75, 7.75), quilt)                 # the front half
    m.box("ibul", (17, 12, 8.25), (32, 26.75, 9.5), quilt)                # the back half, hanging a little higher
    m.box("ibul_fold", (17, 26.75, 6.5), (32, 27.25, 9.5), quilt)         # over the line
    for x in (18, 30):
        m.box("peg", (x, 26, 6.25), (x + 1, 27.75, 6.75), peg)
    m.group("towel")

    m.box("towel", (33.5, 18, 7.75), (38.5, 26.25, 8.25), Paint("denim", 3, decal=lambda c: ("porcelain", 2) if c.i[1] % 3 == 0 else None))
    m.box("peg", (34, 25.75, 7.5), (35, 27.5, 8.5), peg)
    m.box("peg", (37, 25.75, 7.5), (38, 27.5, 8.5), peg)
    m.group("socks")
    for x in (39.75, 42.25):
        sock = Paint("steel", 1, "weave")
        m.box("sock", (x, 22, 7.75), (x + 1.25, 25, 8.25), sock)
        m.box("sock_foot", (x - 1, 22, 7.8), (x, 23.5, 8.2), sock)
        m.box("sock_band", (x - 0.1, 25, 7.7), (x + 1.35, 26.25, 8.3), flat("porcelain", 1))
        m.box("peg", (x + 0.25, 25.75, 7.4), (x + 1, 27.5, 8.6), peg)
    m.group("basket")
    # a red plastic laundry basket (빨래 바구니) under the line, its sides a grid of holes, the next load heaped in it
    def holes(c):
        if c.face in SIDE_FACES and 1 <= c.i[1] <= 2:
            u = c.i[0] if c.face in ("north", "south") else c.i[2]
            return ("pepper", 0) if u % 2 == 0 else None
        return None
    m.box("basket", (5.5, 0, 5.5), (12.5, 3.5, 10.5), Paint("pepper", 3, noise=0, rim=0.8, decal=holes))
    m.box("basket_rim", (5.25, 3.5, 5.25), (12.75, 4, 10.75), Paint("pepper", 4, noise=0, rim=1.2))
    m.box("wash", (6, 4, 6), (12, 4.75, 10), Paint("rice", 2, "weave", decal=lambda c: ("denim", 2) if h3(c.i[0] // 2, 0, c.i[2] // 2, 39) < 0.3 else None))
    m.box("wash", (7, 4.75, 6.75), (10.5, 5.5, 9.25), Paint("pink", 2, "weave"))
    return display(m, (3, 2, 1), 0)


# ================================================================================================
# 장독대 2x1
# ================================================================================================

def onggi_jar(m, name, cx, cz, y0, profile, seed):
    """An onggi crock round (cx, cz) from y0, profile = [(diameter, height)] from the foot up: a narrow foot, the
    belly swelling out in thin rings to its widest a little above the middle, a clear shoulder drawing in, a short
    neck and a rolled rim. The dark brown glaze: unglazed and darker at the foot, a lighter sheen on the belly towards
    the light, the potter's finger-wave band round the shoulder, one run of glaze down from it. Returns its top."""
    height = sum(h for (_, h) in profile)
    widest = max(d for (d, _) in profile)

    def glaze(c):
        if c.face == "down":
            return None
        a = math.atan2(c.p[2] - cz, c.p[0] - cx)
        light = math.cos(a - math.atan2(-0.8, 0.6))       # 1 facing the light (front-left), -1 away
        t = (c.p[1] - y0) / height
        if t < 0.1:
            return ("onggi", 0)                            # the unglazed foot
        run = (a * 9 + seed) % (2 * math.pi)
        if run < 0.3 and 0.35 < t < 0.75:
            return ("onggi", 1)                            # one run of glaze down from the shoulder
        if c.face != "up" and 0.68 < t < 0.8 and int(math.floor(a * 8 + c.p[1] * 3)) % 3 == 0:
            return ("onggi", 3)                            # the finger-wave band round the shoulder
        if c.face == "up":
            return ("onggi", 2)
        if light > 0.8 and 0.3 < t < 0.65 and c.face in ("north", "east"):
            return ("onggi", 3)                            # the sheen on the belly, towards the light
        return ("onggi", 2)
    jar = Paint("onggi", 1, noise=0.0, rim=0.5, ao=0.0, decal=glaze, grad=0, vrim=0)
    y = y0
    for (d, h) in profile:
        cyl(m, name, (cx, cz), d, y, y + h, jar)
        y += h
    return y


def onggi_lid(m, name, cx, cz, y, d, paint):
    """A crock lid as an inverted dish: a wide flat rim overhanging the mouth, a low dome and a little knob."""
    cyl(m, name, (cx, cz), d, y, y + 0.5, paint)
    cyl(m, name, (cx, cz), d - 3, y + 0.5, y + 1.5, paint)
    cyl(m, name + "_knob", (cx, cz), 2, y + 1.5, y + 2.25, paint)


def jangdokdae():
    """장독대 2x1: a low platform of flat fieldstones set in earth, mossy in the joints; three onggi crocks of soy
    sauce and pastes (a big one, a middle one, a little one), round-bellied with a clear shoulder and rolled rim,
    each with its lid like an upturned dish, the little one's lid off and leaning against it, its mouth showing the
    dark soy; a gourd dipper (바가지), half a dried gourd with its rim, lying in front."""
    m = BModel("jangdokdae")
    m.group("platform")
    joints_x = {0: (7, 14, 20, 26), 1: (4, 11, 17, 24, 29)}

    def flagstones(c):
        x, y, z = c.p
        if c.face == "up":
            row = 0 if z < 8 else 1
            if c.i[2] == 8 or c.i[0] in joints_x[row]:
                v = h3(c.i[0], 0, c.i[2], 31)
                return ("leaf", 1) if v < 0.35 else ("wood_dark", 1)   # moss and earth in the joints
            k = sum(1 for j in joints_x[row] if c.i[0] > j)
            return ("millstone", (2, 3, 4)[int(h3(k, row, 0, 33) * 3)])   # each stone its own grey
        if c.face in SIDE_FACES:
            u = x if c.face in ("north", "south") else z
            if c.face in ("north", "south") and c.i[0] in joints_x[0 if c.face == "north" else 1]:
                return ("millstone", 1)
            if y < 0.6:
                return ("wood_dark", 1)                    # the earth it is set in
        return None
    m.box("platform", (0.5, 0, 1), (31.5, 2, 15), Paint("millstone", 3, noise=0.06, decal=flagstones, rim=1.0))
    m.group("jars")
    lid = Paint("onggi", 2, noise=0, rim=0.9, decal=lambda c: ("onggi", 3) if c.face == "up" else None)
    top = onggi_jar(m, "big", 9.5, 8, 2, [(8, 1), (11, 0.75), (12, 0.75), (13, 2.75), (12, 0.75), (11, 0.75),
                                         (8, 0.5), (9, 0.75)], 81)
    onggi_lid(m, "big_lid", 9.5, 8, top, 11, lid)
    top = onggi_jar(m, "mid", 21, 8.5, 2, [(6, 0.75), (8, 1), (9, 1.75), (8, 0.75), (6, 0.5), (7, 0.5)], 82)
    onggi_lid(m, "mid_lid", 21, 8.5, top, 8, lid)
    top = onggi_jar(m, "small", 28, 10, 2, [(4, 0.5), (5.5, 0.75), (6, 1.25), (5, 0.75), (4, 0.5)], 83)
    # its mouth open: a rolled rim with the dark soy sauce inside
    ring(m, "small_mouth", (28, 10), 5, 3, top, top + 0.5, Paint("onggi", 2, noise=0, rim=1.2))
    cyl(m, "soy", (28, 10), 3, top, top + 0.25, flat("roe", 1, light=False))
    # the lid off, leaning back against its jar at the front
    for (a, b, hw) in bands(4.5):
        m.box("small_lid", (28 - hw, 4.5 + a, 6), (28 + hw, 4.5 + b, 6.5), lid, rotation=("x", 22.5, (28, 2.25, 6.25)))
    m.group("ladle")

    def gourd(c):
        return ("straw", 4) if c.face == "up" else None
    # a gourd dipper (바가지): half a dried gourd, round underneath, its rim a darker ring, the pale inside showing
    cyl(m, "bagaji", (16, 3.5), 3, 2, 2.5, Paint("straw", 1, rim=0.6))
    cyl(m, "bagaji", (16, 3.5), 5, 2.5, 3.25, Paint("straw", 1, rim=1.2, decal=lambda c: ("wood", 2) if c.face == "up" else None))
    cyl(m, "bagaji_inside", (16, 3.5), 3, 3.25, 3.35, Paint("straw", 4, noise=0, decal=gourd))
    return display(m, (2, 1, 1), 0)


# ================================================================================================
# Registry
# ================================================================================================

PIECES = {
    # id: (builder, cells as (w, h, d) blocks, floor)
    "pyeongsang": (pyeongsang, (2, 1, 2), 0),
    "red_postbox": (red_postbox, (1, 2, 1), 0),
    "public_phone": (public_phone, (1, 2, 1), 0),
    "dalgona_stand": (dalgona_stand, (1, 1, 1), 0),
    "capsule_machine": (capsule_machine, (1, 2, 1), 0),
    "arcade_machine": (arcade_machine, (1, 2, 1), 0),
    "tub_garden": (tub_garden, (1, 1, 1), 0),
    "street_lamp": (street_lamp, (1, 3, 1), -16),
    "clothesline": (clothesline, (3, 2, 1), 0),
    "jangdokdae": (jangdokdae, (2, 1, 1), 0),
}

ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}

# the real thing each piece stands for, roughly (1 block ~ 1 m, a player 1.8 m)
REAL = {
    "pyeongsang": "1.8×1.8 m, 높이 45 cm", "red_postbox": "폭 60 cm, 높이 1.35 m", "public_phone": "덮개 높이 1.9 m",
    "dalgona_stand": "좌판 85 cm, 높이 40 cm", "capsule_machine": "폭 60 cm, 높이 1.3 m",
    "arcade_machine": "폭 75 cm, 높이 1.5 m", "tub_garden": "대야 지름 85 cm", "street_lamp": "높이 3 m (줄여서)",
    "clothesline": "길이 3 m, 높이 1.7 m", "jangdokdae": "단 1.9 m, 큰 독 높이 70 cm",
}
