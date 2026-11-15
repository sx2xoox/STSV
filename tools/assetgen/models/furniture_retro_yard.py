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
under 30%; the street lamp's top cell holds only the pole's top, the arm and the shade; the dalgona stand is low
and not solid (walk past it like a mat).

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
"""

import math

from economy_machines import display
from models.furniture_japanese import STONE, STONE_DARK, STONE_LIGHT, flat, flicker, moss_on, puff
from stsv_assets.bake import BModel, Paint, cyl, h3, ring

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
    return display(m, (2, 1, 2), 0)


# ================================================================================================
# 빨간 우체통 1x1, 높이 2
# ================================================================================================

def red_postbox():
    """빨간 우체통 (1.4 blocks): a red tin box with a rounded top on a dark iron plinth, the letter slot under a
    little visor, a cream plate with a white envelope on a red ground (a pictogram, no postal emblem) and the
    collection-time card; a hinged door with a brass lock on the back, the paint worn at the edges."""
    m = BModel("red_postbox")
    m.group("plinth")
    m.box("plinth", (3, 0, 3), (13, 1.5, 13), IRON)
    m.box("plinth_top", (3.5, 1.5, 3.5), (12.5, 2, 12.5), flat("iron", 3))
    for (x, z) in ((3.75, 3.75), (11.75, 3.75), (3.75, 11.75), (11.75, 11.75)):
        m.box("bolt", (x, 1.5, z), (x + 0.5, 1.75, z + 0.5), flat("steel", 4))

    def front(c):
        if c.face != "north":
            return None
        x, y = c.p[0], c.p[1]
        # the cream plate with a white envelope on it (a pictogram, its flap a red V)
        if 4.5 < x < 11.5 and 8.5 < y < 13.5:
            if 5.5 < x < 10.5 and 9.5 < y < 12.5:
                # the flap: a V from the top corners down to the middle, one pixel a row
                col, row = int(math.floor(x - 5.5)) - 2, int(math.floor(12.5 - y))
                if abs(col) == 2 - row:
                    return ("pepper", 2)
                return ("porcelain", 2)
            return ("cream", 3)
        # the collection-time card: cream, two rows of grey "writing"
        if 5.5 < x < 10.5 and 4 < y < 7.5:
            if 6 < x < 10 and ((c.i[1] == 6 and c.i[0] % 2 == 0) or (c.i[1] == 5 and c.i[0] % 3 != 2)):
                return ("steel", 2)
            return ("cream", 3)
        return None

    def wear(c):
        got = front(c)
        if got:
            return got
        # a few chips worn to the dark undercoat low down, where kicks and bicycles hit it
        if c.face in SIDE_FACES and c.p[1] < 5 and h3(c.i[0], c.i[1], c.i[2], 21) < 0.06:
            return ("pepper", 1)
        return None
    body = Paint("pepper", 2, noise=0.0, decal=wear)
    m.group("box")
    m.box("body", (3, 2.75, 3), (13, 16, 13), body)
    m.box("seam", (2.75, 2, 2.75), (13.25, 2.75, 13.25), flat("pepper", 1))
    m.box("roof_rim", (2.5, 16, 2.5), (13.5, 16.75, 13.5), Paint("pepper", 3, noise=0, rim=1.4))
    arch_z(m, "roof", 2.75, 13.25, 8, 16.75, 5.25, Paint("pepper", 2, noise=0, rim=0.8), flat_top=0.3)
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
    m.box("post", (7, 1.5, 10), (9, 16, 12), Paint("steel", 3, "metal"))
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
    m.box("plank", (1, 5, 2), (15, 6, 14), Paint("wood", 3, "planks", axis="x", width=4, joint=0, rim=1.2))
    m.group("brazier")
    # a round tin brazier holding one briquette: its holes glow and the fire breathes (animated)
    cyl(m, "brazier", (11.5, 6.5), 6, 6, 8, Paint("iron", 2, "metal"))
    ring(m, "brazier_rim", (11.5, 6.5), 6, 4.5, 8, 8.5, Paint("steel", 2, "metal", rim=1.3))
    m.box("air_hole", (10.75, 6.5, 3.45), (12.25, 7.5, 3.55), glow("fire", 3))

    def embers(c):
        r = h3(c.i[0], c.i[1], c.i[2], 13)
        dx, dz = c.p[0] - 11.5, c.p[2] - 6.5
        hole = any(math.hypot(dx - hx, dz - hz) < 0.45 for hx in (-1, 0, 1) for hz in (-1, 0, 1))
        breath = (1 if (c.frame + int(r * 12)) % 12 < 5 else 0)
        if hole:
            return ("fire", 4 + breath)                    # the briquette's holes, the hottest
        return ("fire", 2 + breath - (1 if r < 0.3 else 0))
    cyl(m, "briquette", (11.5, 6.5), 4.5, 7.75, 8.25, glow("fire", 3, decal=embers, frames=12, frametime=4))
    # the ladle (국자): a dented tin bowl of melting sugar, set on the brazier's rim, its long handle back over the plank
    cyl(m, "ladle", (12.5, 7.5), 2.5, 8.5, 9.5, ALU)
    cyl(m, "sugar", (12.5, 7.5), 1.5, 9.5, 9.6, flat("onion", 2, light=False))
    m.box("ladle_handle", (12.25, 9.25, 8.75), (12.75, 9.5, 13.5), flat("wood_dark", 2))
    m.group("tray")
    tray = Paint("steel", 4, "metal", noise=0.03, rim=1.2)
    m.box("tray", (1.25, 6, 2.5), (8, 6.5, 13.5), tray)
    for (x0, z0, x1, z1) in ((1.25, 2.5, 8, 3), (1.25, 13, 8, 13.5), (1.25, 3, 1.75, 13), (7.5, 3, 8, 13)):
        m.box("tray_lip", (x0, 6.5, z0), (x1, 7, z1), tray)
    star = ("..#..", ".###.", "#####", ".###.", ".#.#.")
    heart = (".#.#.", "#####", "#####", ".###.", "..#..")

    def candy(shape, cx, cz):
        def decal(c):
            if c.face != "up":
                return ("onion", 2)
            if math.hypot(c.p[0] - cx, c.p[2] - cz) > 2.0:
                return ("onion", 2)                        # the edge, a little more caramelised
            col, row = int(math.floor(c.p[0] - cx + 2.5)), int(math.floor(c.p[2] - cz + 2.5))
            if 0 <= col < 5 and 0 <= row < 5 and shape[row][4 - col] == "#":
                return ("onion", 4)                        # the shape pressed in: pale where the press thinned it
            return ("onion", 3)
        return decal
    for (cx, cz, shape) in ((4.5, 5.25, star), (4.5, 10.75, heart)):
        cyl(m, "dalgona", (cx, cz), 5, 6.5, 6.75, Paint("onion", 3, decal=candy(shape, cx, cz), rim=0.6))
    m.group("tools")
    cyl(m, "press", (11, 11.75), 3, 6, 6.25, flat("steel", 3))
    m.box("press_knob", (10.5, 6.25, 11.25), (11.5, 7.25, 12.25), flat("wood_dark", 2))
    m.box("cutter", (13.25, 6, 9.75), (14.25, 6.75, 10.75), flat("steel", 4))
    # the soda tin
    cyl(m, "soda", (14, 12.5), 2, 6, 8, Paint("porcelain", 2, decal=lambda c: ("denim", 2) if 6.6 < c.p[1] < 7.2 else None))
    cyl(m, "soda_lid", (14, 12.5), 2, 8, 8.25, flat("steel", 3))
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
        if c.face != "north":
            return None
        x, y = c.p[0], c.p[1]
        if 4.5 < x < 11.5 and 9 < y < 10.5:
            # the price card: cream with a row of dots (coins)
            return ("pepper", 2) if c.i[0] % 2 == 0 and 9.4 < y < 10.1 and 5 < x < 11 else ("cream", 3)
        return None
    m.box("base", (3, 0, 3.5), (13, 1, 12.5), Paint("pepper", 1, noise=0))
    m.box("body", (3.5, 1, 4), (12.5, 11, 12), Paint("pepper", 2, noise=0, decal=front))
    m.box("body_top", (3, 11, 3.5), (13, 11.75, 12.5), Paint("pepper", 3, noise=0, rim=1.3))
    # the turning knob: a chrome disc with a bar across
    cyl(m, "knob", (8, 6.5), 4, 2.5, 4, Paint("steel", 4, "metal", rim=1.4), axis="z")
    m.box("knob_bar", (6, 6, 2), (10, 7, 2.5), flat("steel", 5, rim=1.4))
    m.box("coin_slot", (9.75, 6, 3.75), (10.75, 7.75, 4), flat("rubber", 1, light=False))
    # the exit flap
    m.box("exit", (5.5, 1.5, 3.75), (10.5, 4, 4), flat("steel", 2))
    m.box("flap", (6, 2, 3.5), (10, 3.75, 3.75), Paint("rubber", 2, noise=0, rim=1.3))
    m.group("dome")
    colours = (("pepper", 3), ("denim", 3), ("hazard", 1), ("slime", 2), ("pink", 2), ("crystal", 3), ("fox", 3))

    def capsules(c):
        # capsules seen through the glass: 2 x 2 px balls on a 3 px grid, the top half coloured, the bottom white,
        # the pile filling the dome to about three quarters; dark glass between them
        x, y, z = c.p
        if c.face in ("north", "south"):
            u, v, w = x, y, z
        elif c.face in ("east", "west"):
            u, v, w = z, y, x
        else:
            u, v, w = x, z, y
        gu, gv = math.floor((u + 0.5) / 3), math.floor((v + (1.5 if c.face == "up" else 0.5)) / 3)
        lu, lv = (u + 0.5) % 3, (v + (1.5 if c.face == "up" else 0.5)) % 3
        top = 17.75 - h3(gu, 0, math.floor(w), 42) * 1.5
        if c.face != "up" and y > top:
            return ("glass", 0)                            # the empty top of the dome: clear glass
        if c.face == "up" and y > 18.5:
            return ("glass", 0)
        if lu >= 2 or lv >= 2 or h3(gu, gv, math.floor(w), 44) < 0.12:
            return ("tint", 2)                             # the dark gaps between the capsules
        ramp, step = colours[int(h3(gu, gv, math.floor(w), 41) * len(colours))]
        if c.face != "up" and lv < 1:
            return ("porcelain", 2)                        # the white lower half
        return (ramp, step)

    def shine(c):
        got = capsules(c)
        if c.face in ("north", "west") and abs((c.p[0] + c.p[1] + c.p[2]) % 11 - 5) < 0.5 and c.p[1] > 13.5:
            return ("glass", 3)                            # a highlight across the glass
        return got
    glass = Paint("tint", 2, decal=shine, rim=0.3, ao=0.3, noise=0, light=True)
    for (d, lo, hi) in ((9, 12.25, 13), (11, 13, 14), (12, 14, 16.5), (11, 16.5, 17.5), (10, 17.5, 18.25), (8, 18.25, 19),
                        (5, 19, 19.5)):
        cyl(m, "dome", (8, 8), d, lo, hi, glass)
    m.box("dome_ring", (3, 11.75, 3.5), (13, 12.25, 12.5), Paint("steel", 3, "metal"))
    cyl(m, "cap", (8, 8), 4, 19.5, 20.25, Paint("pepper", 2, noise=0, rim=1.3))
    cyl(m, "cap_knob", (8, 8), 2, 20.25, 21, Paint("pepper", 3, noise=0))
    return display(m, (1, 1.3, 1), 0)


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
        return None
    tub = Paint("rubber", 2, decal=tub_side, rim=0.8)
    cyl(m, "tub", (8, 8), 13, 0, 5.5, tub)
    cyl(m, "tub_rim", (8, 8), 14, 5.5, 6.5, Paint("rubber", 3, rim=1.2))     # the rolled rim round the soil
    cyl(m, "soil", (8, 8), 12, 6.5, 6.75, Paint("soil", 2, "speckle"))
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
    """전봇대 가로등 (2.75 blocks): a grey concrete utility pole in a concrete collar, its number tag and a torn
    flyer pasted on it, a crossarm with small white insulators, the curved tin arm of a street lamp with its green
    enamel shade (white inside) over a warm bulb. Drawn from y 0 and moved down a block (dy -16). The top block
    holds only the pole's top, the arm and the shade (16 of its 64 voxels: its light cell gets no barrier)."""
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
            return ("porcelain", 2) if c.i[1] == 17 or c.i[0] % 2 else ("rubber", 2)   # the number tag
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
    m.box("pole", (6.5, 16, 6.5), (9.5, 43.25, 9.5), pole)
    m.box("pole_cap", (6.75, 43.25, 6.75), (9.25, 44, 9.25), flat("millstone", 2))
    # the climbing steps: iron rungs in pairs up the side, out of reach from the ground
    for y in range(17, 34, 3):
        x0 = 9.5 if (y // 3) % 2 else 5.75
        m.box("step", (x0, y, 7.5), (x0 + 0.75, y + 0.5, 8.5), flat("iron", 2))
    # the guy wire behind: from a little concrete anchor up to the pole, a yellow and black guard round its foot
    m.group("guy")
    m.box("anchor", (6.75, 0, 13.75), (9.25, 0.75, 16), STONE_DARK)
    lean = ("x", -22.5, (8, 0.5, 14.875))
    m.box("guy_wire", (7.875, 0.5, 14.75), (8.125, 14.5, 15), flat("iron", 1), rotation=lean)

    def stripes(c):
        return ("rubber", 1) if c.face in SIDE_FACES and int(math.floor(c.p[1] * 1.5)) % 2 else None
    m.box("guy_guard", (7.625, 0.75, 14.625), (8.375, 6.25, 15.125), Paint("hazard", 3, noise=0, decal=stripes),
          rotation=lean)
    m.group("crossarm")
    m.box("crossarm", (0.5, 27, 7.25), (15.5, 28.25, 8.75), Paint("steel", 2, "metal"))
    m.box("brace", (4.5, 25.25, 7.5), (6.5, 25.75, 8.5), flat("steel", 1), rotation=("z", -45, (6.5, 25.5, 8)))
    m.box("brace", (9.5, 25.25, 7.5), (11.5, 25.75, 8.5), flat("steel", 1), rotation=("z", 45, (9.5, 25.5, 8)))
    for x in (1.5, 4.25, 11.75, 14.5):
        m.box("pin", (x - 0.25, 28.25, 7.75), (x + 0.25, 28.75, 8.25), flat("steel", 2))
        m.box("insulator", (x - 0.5, 28.75, 7.5), (x + 0.5, 29.5, 8.5), Paint("porcelain", 2, rim=1.2))
        m.box("insulator_top", (x - 0.375, 29.5, 7.625), (x + 0.375, 29.875, 8.375), Paint("porcelain", 3))
    m.group("lamp")
    # the arm: out of the pole's front near its top, bending down over the street
    arm = flat("steel", 3, rim=1.2)
    m.box("arm", (7.5, 41.5, 4), (8.5, 42.5, 6), arm)
    m.box("arm_bend", (7.5, 40.5, 3), (8.5, 42, 4), arm)
    m.box("arm_bracket", (7.25, 40, 6), (8.75, 43, 6.5), flat("steel", 2))

    def enamel(c):
        return ("porcelain", 2) if c.face == "down" else None
    shade = Paint("sign_green", 2, noise=0.02, rim=1.3, decal=enamel)
    # a shallow cone of green enamel, white inside, its edge rolled
    cyl(m, "shade", (8, 3.25), 2, 40.25, 41, shade)
    cyl(m, "shade", (8, 3.25), 3.5, 39.75, 40.25, shade)
    cyl(m, "shade", (8, 3.25), 5, 39.25, 39.75, shade)
    cyl(m, "shade", (8, 3.25), 6.5, 38.5, 39.25, shade)
    cyl(m, "bulb", (8, 3.25), 2, 37.25, 38.5, glow("lamp", 3))
    return display(m, (1, 2.75, 1), -16)


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
    return display(m, (3, 2, 1), 0)


# ================================================================================================
# 장독대 2x1
# ================================================================================================

def onggi_jar(m, name, cx, cz, y0, profile, seed):
    """An onggi crock round (cx, cz) from y0, profile = [(diameter, height)] from the foot up (the belly widest a
    little above the middle, the shoulder drawing in to the mouth); the dark brown glaze lit by a soft highlight on
    the side towards the light, darker at the foot, one run of glaze down from the shoulder. Returns its top."""
    height = sum(h for (_, h) in profile)
    def glaze(c):
        if c.face == "down":
            return None
        a = math.atan2(c.p[2] - cz, c.p[0] - cx)
        light = math.cos(a - math.atan2(-0.8, 0.6))       # 1 facing the light (front-left), -1 away
        t = (c.p[1] - y0) / height
        if t < 0.12:
            return ("onggi", 0)                            # the dark unglazed foot
        run = (a * 9 + seed) % (2 * math.pi)
        if run < 0.35 and t > 0.55 - 0.3 * h3(seed, 0, 0, 7):
            return ("onggi", 0)                            # one run of glaze down from the shoulder
        if c.face != "up" and 0.62 < t < 0.7 and int(math.floor(a * 6 + c.p[1] * 2)) % 3 == 0:
            return ("onggi", 1)                            # the potter's finger-wave band round the shoulder
        if light > 0.85 and 0.35 < t < 0.6 and c.face != "up":
            return ("onggi", 3)                            # the glaze's sheen
        return ("onggi", 2)
    jar = Paint("onggi", 1, noise=0.0, rim=0, ao=0.15, decal=glaze, grad=0)
    y = y0
    for (d, h) in profile:
        cyl(m, name, (cx, cz), d, y, y + h, jar)
        y += h
    return y


def jangdokdae():
    """장독대 2x1: a low platform of fieldstones, three onggi crocks of soy sauce and pastes (a big one, a middle one
    and a little one) with their glazed lids, the little one's lid off and leaning against it, a gourd ladle."""
    m = BModel("jangdokdae")
    m.group("platform")
    st = moss_on(STONE, amount=0.7)
    m.box("platform", (1, 0.25, 1.5), (31, 2, 15), st)
    # the fieldstones along its front edge (the back is the platform's own edge, mostly behind the jars)
    stones = [(0.5, 1, 7, 4, 2.75, STONE_LIGHT), (7, 1, 14, 3.5, 2.5, STONE), (14, 1, 21, 4, 2.75, STONE_DARK),
              (21, 1, 31.5, 3.75, 2.5, STONE_LIGHT)]
    for (x0, z0, x1, z1, h, p) in stones:
        m.box("stone", (x0, 0, z0), (x1, h, z1), p)
    m.group("jars")
    lid = Paint("onggi", 1, noise=0, rim=0.8, decal=lambda c: ("onggi", 2) if c.face == "up" else None)
    top = onggi_jar(m, "big", 9, 8.5, 2, [(8, 1), (11, 1.5), (12.5, 1.5), (13, 2.5), (12, 1.5), (9.5, 1.5)], 81)
    cyl(m, "big_lid", (9, 8.5), 10.5, top, top + 0.75, lid)            # the lid, a shallow dome over the mouth
    cyl(m, "big_lid", (9, 8.5), 7, top + 0.75, top + 1.5, lid)
    cyl(m, "big_knob", (9, 8.5), 2, top + 1.5, top + 2.25, lid)
    top = onggi_jar(m, "mid", 20.5, 8, 2, [(6, 1), (8.5, 1.5), (9, 2), (7, 1.5)], 82)
    cyl(m, "mid_lid", (20.5, 8), 7.5, top, top + 0.75, lid)
    cyl(m, "mid_lid", (20.5, 8), 5, top + 0.75, top + 1.25, lid)
    cyl(m, "mid_knob", (20.5, 8), 2, top + 1.25, top + 2, lid)
    top = onggi_jar(m, "small", 28, 9.5, 2, [(4, 1), (5.5, 1), (6, 1.5), (5, 1)], 83)
    # its mouth open: dark soy sauce inside the rim
    ring(m, "small_mouth", (28, 9.5), 5, 3, top, top + 0.75, Paint("onggi", 3, noise=0, rim=1.2))
    cyl(m, "soy", (28, 9.5), 3, top + 0.25, top + 0.5, flat("roe", 1))
    # the lid off, leaning back against its jar at the front
    m.box("small_lid", (26, 2.5, 4.75), (30, 6.5, 5.25), lid, rotation=("x", 22.5, (28, 2.5, 5)))
    m.group("ladle")
    # a gourd dipper (바가지): half a dried gourd, its pale inside up
    cyl(m, "bagaji", (16, 3), 4, 2.75, 3.75, Paint("straw", 2, rim=1.0))
    cyl(m, "bagaji_inside", (16, 3), 2.5, 3.75, 3.85, flat("cream", 2))
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
