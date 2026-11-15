"""
Retro Korean furniture, 부엌·살림 (content/stsv/items/furniture_retro_kitchen.yml): ten pieces of an 80s-90s Korean home,
built as baked models (stsv_assets/bake.py) with the same conventions as models/furniture_japanese.py (16 px a
block, front = north (z 0), anchor cell x 0..16 / z 0..16, y 0 its floor; model +x is the viewer's LEFT; every
piece centred in its cells; collision from the model: a cell gets a barrier when the model fills 30% of it).
No real brand, logo or product design is copied.

The yellow aluminium of the old pots, kettles and cups (양은) is the brass ramp; the briquette's glow in the stove and
the kerosene cooker's blue flame ring are animated textures (no entities), like the Japanese lamps.

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
"""

import math

from economy_machines import display
from models.furniture_japanese import SIDE_FACES, flat, flicker
from stsv_assets.bake import BModel, Paint, cyl, h3, ring

# ================================================================================================
# Paints
# ================================================================================================

YANGEUN = Paint("brass", 2, noise=0, rim=0.5, ao=0.7, grad=0.5)            # 양은: thin yellow aluminium, a soft sheen
YANGEUN_DARK = flat("brass", 1, rim=0.6)
IRON_DARK = flat("iron", 1, rim=0.5)
CHROME = flat("blade", 2, rim=0.8)
WOOD = Paint("wood", 2, "grain", axis="long")
WOOD_DARK = Paint("wood_dark", 2, "grain", axis="long", noise=0.03)
PORCELAIN = Paint("porcelain", 2, noise=0.02)
BLACK = flat("rubber", 1, rim=0.6)


def centre(m):
    """Moves the whole model so its bounds sit in the middle of its cells along x and z (the same margin on both
    sides; RetroFurnitureTest), keeping every texel where it was painted (the move goes into the model's shift)."""
    lo, hi = [1e9, 1e9], [-1e9, -1e9]
    for e in m.elements:
        a, b = e["from"], e["to"]
        pts = [(x, y, z) for x in (a[0], b[0]) for y in (a[1], b[1]) for z in (a[2], b[2])]
        if e["rotation"]:
            axis, ang, o = e["rotation"]
            c, s = math.cos(math.radians(ang)), math.sin(math.radians(ang))
            out = []
            for (x, y, z) in pts:
                x, y, z = x - o[0], y - o[1], z - o[2]
                if axis == "x":
                    y, z = y * c - z * s, y * s + z * c
                elif axis == "y":
                    x, z = x * c + z * s, -x * s + z * c
                else:
                    x, y = x * c - y * s, x * s + y * c
                out.append((x + o[0], y + o[1], z + o[2]))
            pts = out
        for (x, _, z) in pts:
            lo[0], hi[0] = min(lo[0], x), max(hi[0], x)
            lo[1], hi[1] = min(lo[1], z), max(hi[1], z)
    w = m.cells_wd if hasattr(m, "cells_wd") else (1, 1)
    dx = round((8 * w[0] - (lo[0] + hi[0]) / 2) * 32) / 32
    dz = round((8 * w[1] - (lo[1] + hi[1]) / 2) * 32) / 32
    if dx == 0 and dz == 0:
        return m
    for e in m.elements:
        e["from"] = [e["from"][0] + dx, e["from"][1], e["from"][2] + dz]
        e["to"] = [e["to"][0] + dx, e["to"][1], e["to"][2] + dz]
        if e["rotation"]:
            axis, ang, o = e["rotation"]
            e["rotation"] = (axis, ang, (o[0] + dx, o[1], o[2] + dz))
    m.shift = (m.shift[0] + dx, m.shift[1], m.shift[2] + dz)
    return m


def finish(m, size):
    """Centred in its cells, then the furniture display transforms."""
    return display(centre(m), size, 0)


# ================================================================================================
# 연탄난로
# ================================================================================================

def _briquette_top(cx, cz, r_out, hole_r=0.42):
    """Decal for the top of a briquette: dark ash grey with its holes (the 19-hole pattern: one in the middle, six
    round it, twelve outside), the holes glowing red-orange and breathing (the stove's version: frames)."""
    holes = [(0.0, 0.0)]
    for k in range(6):
        a = k * math.pi / 3
        holes.append((math.cos(a) * r_out * 0.42, math.sin(a) * r_out * 0.42))
    for k in range(12):
        a = k * math.pi / 6 + math.pi / 12
        holes.append((math.cos(a) * r_out * 0.78, math.sin(a) * r_out * 0.78))
    return holes


def yeontan_stove():
    """연탄난로 1x2: a round briquette stove nearly a block tall on three stubby legs: a silver-grey body with two
    chrome bands and an ash door whose vents glow, a cast-iron top plate where a briquette burns in the open fire
    hole (its holes glow and breathe), a yellow aluminium kettle warming beside it, and a slim galvanized flue
    rising from the back, its joints banded, bending back to the wall at the top.

    The light is in the flue's cell above (furniture.light), which must stay free of a barrier (under 30% of its
    16 px voxels touched): the stove's top plate ends at the cell's floor, only the kettle and the fire collar reach
    a little into it, and the flue is 3 px, inside one voxel column."""
    m = BModel("yeontan_stove")
    m.group("body")
    for (x, z) in ((3.5, 4), (12.5, 4), (8, 13)):
        m.box("leg", (x - 1, 0, z - 1), (x + 1, 1.5, z + 1), IRON_DARK)
    cyl(m, "base_ring", (8, 8.5), 12, 1.5, 2.5, flat("iron", 2, rim=0.8))

    def body_band(c):
        if c.face in ("up", "down"):
            return None
        y = c.p[1]
        if 11.6 < y < 12.4 or 6.6 < y < 7.4:
            return ("blade", 3)                     # the bright bands round its shoulder and waist
        if 2.5 < y < 6.6 and c.face in ("north",) and abs(c.p[0] - 8) < 2.5:
            return ("iron", 0)                      # the ash door: a darker plate
        return None
    cyl(m, "body", (8, 8.5), 11, 2.5, 14.5, Paint("steel", 2, noise=0.02, decal=body_band))

    def vents(c):
        return ("fire", 2 + (1 if (c.frame + int(c.p[0])) % 12 < 5 else 0))
    for x in (6.5, 8.5):
        m.box("vent", (x - 0.5, 3.5, 2.95), (x + 0.5, 5.5, 3.0),
              Paint("fire", 2, glow=True, light=False, noise=0, decal=vents, frames=12, frametime=3))
    m.box("door_knob", (9.5, 4, 2.4), (10.5, 5, 3), CHROME)
    cyl(m, "top", (8, 8.5), 13, 14.5, 16, flat("iron", 1, rim=0.9))
    m.group("fire")
    # the open fire hole at the front left of the plate: a raised iron collar round a glowing briquette top
    hx, hz = 10.5, 5.5
    ring(m, "collar", (hx, hz), 6, 4, 16, 16.75, IRON_DARK)
    holes = _briquette_top(hx, hz, 1.6)

    def burning(c):
        if c.face != "up":
            return ("fire", 1)
        u, v = c.p[0] - hx, c.p[2] - hz
        for (a, b) in holes:
            if (u - a) ** 2 + (v - b) ** 2 < 0.3:
                k = (c.frame + int(h3(int(a * 4), int(b * 4), 0, 7) * 12)) % 12
                return ("fire", 3 + (1 if k < 4 else 0))
        r = math.hypot(u, v)
        return ("fire", 2 + (1 if (c.frame // 3) % 2 == 0 and r < 1.2 else 0)) if r < 2 else ("fire", 1)
    cyl(m, "briquette", (hx, hz), 4, 15.5, 16.5, Paint("fire", 2, glow=True, light=False, noise=0, decal=burning,
                                                       frames=12, frametime=3))
    m.group("kettle")
    kx, kz = 4.5, 7.5
    cyl(m, "kettle", (kx, kz), 5, 16, 16.5, YANGEUN_DARK)
    cyl(m, "kettle", (kx, kz), 6, 16.5, 18.25, YANGEUN)
    cyl(m, "kettle", (kx, kz), 4, 18.25, 18.75, YANGEUN)
    cyl(m, "lid", (kx, kz), 3, 18.75, 19.25, flat("brass", 3))
    m.box("knob", (kx - 0.5, 19.25, kz - 0.5), (kx + 0.5, 19.6, kz + 0.5), BLACK)
    m.box("spout", (kx - 0.5, 16.75, kz - 3.75), (kx + 0.5, 17.75, kz - 2.75), YANGEUN)
    m.box("spout_tip", (kx - 0.5, 17.5, kz - 4.5), (kx + 0.5, 18.75, kz - 3.75), YANGEUN)
    wire = flat("iron", 2, rim=0.4)
    for x in (kx - 3.25, kx + 2.75):
        m.box("handle", (x, 18, kz - 0.25), (x + 0.5, 19.25, kz + 0.25), wire)
    m.box("handle", (kx - 3.25, 19.25, kz - 0.25), (kx + 3.25, 19.75, kz + 0.25), wire)
    m.group("flue")

    def joints(c):
        if c.face in ("up", "down"):
            return None
        if c.i[1] % 6 == 0:
            return ("steel", 1)                     # the crimped joint between two pipe lengths
        return None
    pipe = Paint("steel", 3, noise=0.02, decal=joints)
    fx, fz = 6, 14
    cyl(m, "collar_flue", (fx, fz), 3, 16, 17, flat("iron", 1))
    cyl(m, "pipe", (fx, fz), 3, 17, 27, pipe)
    # the elbow, and the pipe going back into the wall behind (z 16)
    cyl(m, "elbow", (fx, fz), 3, 27, 30, flat("steel", 2))
    cyl(m, "pipe_back", (fx, 28.5), 3, 15.5, 16, flat("steel", 2), axis="z")
    return finish(m, (1, 2, 1))


# ================================================================================================
# 석유곤로
# ================================================================================================

def kerosene_stove():
    """석유곤로 1x1: a round enamel kerosene cooker: a green fuel tank with a white band and a fuel gauge, the wick
    knob on its side, a perforated burner chimney with a mica window glowing orange, a three-armed grate, the ring
    of blue flame flickering under a yellow aluminium pot with its lid on."""
    m = BModel("kerosene_stove")
    green = Paint("bottle_green", 2, noise=0.02)

    def tank_band(c):
        if c.face in SIDE_FACES and 2.0 < c.p[1] < 2.8:
            return ("porcelain", 2)
        return None
    m.group("tank")
    cyl(m, "foot", (8, 8), 11, 0, 0.75, flat("rubber", 1))
    cyl(m, "tank", (8, 8), 12, 0.75, 4, green(decal=tank_band))
    cyl(m, "tank_top", (8, 8), 11, 4, 4.5, flat("bottle_green", 3))
    # fuel gauge (a small dial) and the wick knob
    m.box("gauge", (6.75, 1.5, 1.75), (9.25, 3.5, 2.25), flat("porcelain", 2))
    m.box("needle", (7.75, 2.25, 1.6), (8.75, 2.75, 1.75), flat("pepper", 2))
    m.box("knob_stem", (13.5, 2.5, 7.5), (14.5, 3.5, 8.5), CHROME)
    m.box("knob", (14.5, 2, 7), (15.5, 4, 9), BLACK)
    m.group("burner")

    def perforated(c):
        if c.face in ("up", "down"):
            return None
        if c.face == "north" and abs(c.p[0] - 8) < 1.6 and 5.5 < c.p[1] < 8:
            return None
        if c.i[1] % 2 == 0 and (c.i[0] + c.i[2]) % 3 == 0 and 6 < c.p[1] < 9:
            return ("rubber", 1)
        return None
    cyl(m, "chimney", (8, 8), 8, 4.5, 9.5, Paint("steel", 3, "metal", decal=perforated))

    def window(c):
        x, y = c.p[0] - 8, c.p[1] - 5.5
        if abs(x) < 0.9 - y * 0.25 and y < 2.0:
            return ("fire", 3.5 + flicker(c.frame, 2) * 2)    # the wick's flame
        return ("fire", 1.5 + flicker(c.frame, 2))
    m.box("window", (6.5, 5.5, 3.95), (9.5, 8, 4.0), Paint("fire", 3, glow=True, light=False, noise=0, decal=window,
                                                             frames=12, frametime=3))
    m.group("flame")

    def flame(c):
        a = math.atan2(c.p[2] - 8, c.p[0] - 8)
        k = (c.frame + int((a + math.pi) / (2 * math.pi) * 12)) % 12
        if c.face == "up":
            return ("sapphire", 3 if k < 6 else 2)
        y = c.p[1]
        if y > 10.2:
            return ("fire", 4 if k % 4 == 0 else 3)
        return ("sapphire", 2 + (1 if k < 5 else 0))
    ring(m, "flame", (8, 8), 8, 5, 9.5, 10.5, Paint("sapphire", 2, glow=True, light=False, noise=0, decal=flame,
                                                      frames=12, frametime=3))
    grate = flat("iron", 1, rim=0.5)
    for k in range(3):
        a = k * 2 * math.pi / 3 + math.pi / 2
        x, z = 8 + math.cos(a) * 4.5, 8 + math.sin(a) * 4.5
        m.box("grate", (x - 0.5, 9.5, z - 0.5), (x + 0.5, 11, z + 0.5), grate)
    m.group("pot")
    cyl(m, "pot_bottom", (8, 8), 9, 11, 11.5, YANGEUN_DARK)
    cyl(m, "pot", (8, 8), 10, 11.5, 14.5, YANGEUN)
    cyl(m, "pot_rim", (8, 8), 11, 14.5, 15, flat("brass", 3))
    cyl(m, "lid", (8, 8), 9, 15, 15.75, YANGEUN)
    m.box("lid_knob", (7.25, 15.75, 7.25), (8.75, 16.5, 8.75), BLACK)
    for x in (1.75, 13.75):
        m.box("pot_handle", (x, 13.5, 6.5), (x + 0.5, 14.25, 9.5), BLACK)
    return finish(m, (1, 1, 1))


# ================================================================================================
# 보온밥솥
# ================================================================================================

def rice_cooker():
    """보온밥솥 1x1: a round cream rice warmer printed with a band of pink and red flowers and green leaves, a
    chrome rim, a domed lid with a fold-down carrying handle, the little red 보온 lamp and a rice paddle in its
    holder on the side, standing on a crocheted mat."""
    m = BModel("rice_cooker")
    m.group("mat")

    def crochet(c):
        r = math.hypot(c.p[0] - 8, c.p[2] - 8)
        if c.face == "up" and math.floor(r * 1.3) % 2 == 0:
            return ("cream", 1)
        return None
    cyl(m, "mat", (8, 8), 14, 0, 0.25, Paint("cream", 2, noise=0, decal=crochet))
    m.group("body")

    def flowers(c):
        if c.face not in SIDE_FACES:
            return None
        y = c.i[1]
        h = c.i[0] if c.face in ("north", "south") else c.i[2]
        if y in (2, 9):
            return ("pink", 1)                          # thin lines framing the band
        for (h0, y0, ramp) in ((1, 4, "pink"), (4, 7, "pepper"), (7, 4, "pink"), (10, 7, "pink"), (13, 4, "pepper")):
            dh, dy = h - h0, y - y0
            if dh == 0 and dy == 0:
                return ("topaz", 2)                     # the flower's middle
            if abs(dh) + abs(dy) == 1:
                return (ramp, 2)                        # four petals
            if dh == 1 and dy == -1 or dh == -1 and dy == -2:
                return ("leaf", 2)
        return None
    cyl(m, "foot", (8, 8), 9, 0.25, 1, flat("rubber", 1))
    cyl(m, "body", (8, 8), 11, 1, 9.5, Paint("cream", 3, noise=0.02, decal=flowers))
    cyl(m, "rim", (8, 8), 12, 9.5, 10.25, CHROME)
    cyl(m, "lid", (8, 8), 11, 10.25, 11.25, Paint("cream", 3, noise=0.02))
    cyl(m, "lid", (8, 8), 8, 11.25, 12, Paint("cream", 3, noise=0.02))
    cyl(m, "vent", (8, 8), 2, 12, 12.5, CHROME)
    # the folded carrying handle lying on the lid, its hinges at the rim
    hp = flat("cream", 2, rim=0.6)
    m.box("handle", (3, 11.25, 7.25), (13, 11.75, 8.75), hp)
    for x in (2, 13):
        m.box("hinge", (x, 10.25, 7), (x + 1, 12, 9), CHROME)
    # the warm lamp and the button on the front
    m.box("panel", (7, 1.5, 2.35), (9, 2.75, 2.55), flat("tint", 1))
    m.box("lamp", (7.25, 1.75, 2.25), (8.0, 2.5, 2.35), Paint("signal_red", 1, glow=True, light=False, noise=0))
    m.box("button", (8.25, 1.75, 2.25), (8.75, 2.5, 2.35), flat("porcelain", 2))
    m.group("paddle")
    m.box("holder", (13.25, 4, 6.75), (14.75, 8.5, 9.25), flat("cream", 2))
    m.box("paddle", (13.6, 6, 7.5), (14.4, 11.5, 8.5), flat("porcelain", 2))
    m.box("paddle_head", (13.75, 11.5, 6.75), (14.25, 13.5, 9.25), flat("porcelain", 2))
    return finish(m, (1, 1, 1))


# ================================================================================================
# 찬장
# ================================================================================================

def glass_cupboard():
    """찬장 1x2: a varnished wooden kitchen cupboard: two panelled doors with round knobs below, a counter ledge,
    and above it two sliding glass doors (see-through, a few streaks of reflection) over two shelves of
    flower-banded rice bowls, stacked plates, cups and a jar; a little crown moulding on top."""
    m = BModel("glass_cupboard")
    W0, W1 = 1.5, 14.5                 # width
    Z0, Z1 = 4, 13                     # depth (back against the wall)
    m.group("lower")
    for x in (W0, W1 - 1):
        for z in (Z0 + 0.5, Z1 - 1.5):
            m.box("leg", (x, 0, z), (x + 1, 1.5, z + 1), WOOD_DARK)
    m.box("carcass", (W0, 1.5, Z0), (W1, 13, Z1), WOOD)

    def door_panel(c):
        if c.face != "north":
            return None
        x, y = c.p[0], c.p[1]
        inner = abs(x - 4.75) < 2.25 or abs(x - 11.25) < 2.25
        if abs(x - 8) < 0.5:
            return ("wood_dark", 1)                 # the gap between the two doors
        if inner and 3.5 < y < 11:
            edge = abs(abs(x - 8) - 3.25) > 1.75 or y < 4.0 or y > 10.5
            return ("wood", 1) if edge else None
        return None
    m.box("doors", (W0 + 0.5, 2, Z0 - 0.5), (W1 - 0.5, 12.5, Z0), Paint("wood", 2, "grain", axis="y", decal=door_panel))
    for x in (7, 9):
        m.box("knob", (x - 0.5, 7, Z0 - 1.25), (x + 0.5, 8, Z0 - 0.5), flat("brass", 2))
    m.box("counter", (W0 - 0.5, 13, Z0 - 1), (W1 + 0.5, 14, Z1), WOOD_DARK)
    m.group("upper")
    m.box("back", (W0, 14, Z1 - 1), (W1, 29.5, Z1), WOOD)
    for x in (W0, W1 - 1):
        m.box("side", (x, 14, Z0 + 1), (x + 1, 29.5, Z1 - 1), WOOD)
    m.box("bottom", (W0 + 1, 14, Z0 + 1), (W1 - 1, 15, Z1 - 1), WOOD)
    m.box("shelf", (W0 + 1, 21.5, Z0 + 1.5), (W1 - 1, 22.25, Z1 - 1), WOOD)
    m.box("roof", (W0 - 0.5, 29.5, Z0 + 0.5), (W1 + 0.5, 30.5, Z1), WOOD_DARK)
    m.box("crown", (W0 + 1.5, 30.5, Z0 + 1), (W1 - 1.5, 31, Z1 - 0.5), WOOD_DARK)
    # the sliding doors: frames and see-through glass with a few diagonal glints
    frame = flat("wood_dark", 2, rim=0.6)
    m.box("rail", (W0 + 1, 15, Z0 + 0.5), (W1 - 1, 15.5, Z0 + 1.5), frame)
    m.box("rail", (W0 + 1, 29, Z0 + 0.5), (W1 - 1, 29.5, Z0 + 1.5), frame)

    def glass(c):
        u = math.floor(c.p[0] + c.p[1])
        if u % 13 == 0 or (u % 13 == 3 and c.p[1] > 22):
            return ("raw", (214, 232, 236, 255))
        return ("raw", (0, 0, 0, 0))
    for (x0, x1, z) in ((W0 + 1, 8.5, Z0 + 0.5), (7.5, W1 - 1, Z0 + 1)):
        m.box("door_frame", (x0, 15.5, z), (x0 + 0.75, 29, z + 0.5), frame)
        m.box("door_frame", (x1 - 0.75, 15.5, z), (x1, 29, z + 0.5), frame)
        m.box("door_frame", (x0 + 0.75, 15.5, z), (x1 - 0.75, 16.25, z + 0.5), frame)
        m.box("door_frame", (x0 + 0.75, 28.25, z), (x1 - 0.75, 29, z + 0.5), frame)
        m.box("pane", (x0 + 0.75, 16.25, z + 0.25), (x1 - 0.75, 28.25, z + 0.25),
              Paint("glass", 3, noise=0, light=False, decal=glass), faces={f: None for f in ("up", "down", "east", "west")})
    m.group("dishes")

    def flower_band(ramp):
        def decal(c):
            if c.face in SIDE_FACES and abs(c.p[1] - (c.hi[1] - 0.75)) < 0.4:
                return (ramp, 2)
            return None
        return decal
    # lower shelf: two rice bowls, a stack of plates, a jar
    for (x, ramp) in ((4.5, "pink"), (7.5, "indigo")):
        cyl(m, "bowl", (x, 9.5), 2.5, 15, 15.5, PORCELAIN)
        cyl(m, "bowl", (x, 9.5), 3, 15.5, 17.5, PORCELAIN(decal=flower_band(ramp)))
    for k in range(4):
        cyl(m, "plate", (11.25, 9.5), 4, 15 + k * 0.5, 15.5 + k * 0.5, PORCELAIN(decal=flower_band("sakura")))
    # upper shelf: cups and a glass jar of barley tea
    for x in (4, 6.5):
        cyl(m, "cup", (x, 9.5), 2, 22.25, 24.5, PORCELAIN(decal=flower_band("pepper")))
    cyl(m, "jar", (10.5, 9.5), 3, 22.25, 26.5, flat("glass", 2, decal=lambda c: ("bean", 1)
                                                        if c.face in SIDE_FACES and c.p[1] < 25 else None))
    cyl(m, "jar_lid", (10.5, 9.5), 3, 26.5, 27.25, flat("pepper", 1))
    return finish(m, (1, 2, 1))


# ================================================================================================
# 양은 냄비 라면
# ================================================================================================

def ramen_pot():
    """양은 냄비 라면 1x1: a dented yellow aluminium pot of instant noodles on yesterday's newspaper: wavy noodles in
    red broth, a soft egg and green onion, its lid set aside on the paper, a small dish of kimchi and a pair of
    steel chopsticks across the pot."""
    m = BModel("ramen_pot")
    m.group("paper")

    def news(c):
        if c.face != "up":
            return None
        x, z = c.p[0], c.p[2]
        if 2.2 < x < 7.4 and 2.4 < z < 6.2:
            return ("millstone", 2)                     # a photo
        if 2.2 < z < 3.6 and 8 < x < 13.6:
            return ("rubber", 1) if math.floor(x * 2) % 3 else None    # the headline
        if math.floor(z * 2) % 2 == 0 and (math.floor(x) + 1) % 6:
            return ("paper", 0)                         # columns of small print
        return None
    m.box("newspaper", (1.5, 0, 1.5), (14.5, 0.25, 14.5), Paint("paper", 2, "paper", decal=news))
    m.group("pot")

    def dents(c):
        if c.face in SIDE_FACES and h3(c.i[0], c.i[1], c.i[2], 41) < 0.12:
            return ("brass", 1)
        return None
    cyl(m, "pot", (7, 9), 9, 0.25, 3.5, YANGEUN(decal=dents))
    ring(m, "pot_wall", (7, 9), 9, 8, 3.5, 4.25, YANGEUN(decal=dents))
    ring(m, "pot_rim", (7, 9), 10, 8, 4.25, 4.75, flat("brass", 3))
    for x in (1.25, 11.75):
        m.box("pot_handle", (x, 3.5, 8.5), (x + 1, 4.25, 9.5), YANGEUN_DARK)

    def noodles(c):
        x, z = c.p[0] - 7, c.p[2] - 9
        r = math.hypot(x, z)
        if math.hypot(x - 1.2, z + 0.4) < 1.25:
            return ("rice", 2) if math.hypot(x - 1.2, z + 0.4) > 0.6 else ("corn", 3)   # the egg
        if (x + 2) ** 2 + (z - 1.6) ** 2 < 0.5 or (x + 0.6) ** 2 + (z - 2.2) ** 2 < 0.4:
            return ("sprout", 3)                         # green onion
        if r > 3.2:
            return ("pepper", 2)                         # the red broth at the edge
        if math.floor(z + math.sin(x * 1.6) * 0.8) % 3 != 2:
            return ("corn", 3 if math.floor(x) % 2 else 2)   # wavy noodles
        return ("pepper", 3)
    cyl(m, "soup", (7, 9), 8, 3.5, 3.75, Paint("pepper", 2, noise=0, decal=noodles))
    m.group("sides")
    cyl(m, "lid", (12.75, 3.5), 6, 0.25, 0.75, flat("brass", 2))
    m.box("lid_knob", (12.25, 0.75, 3), (13.25, 1.25, 4), YANGEUN_DARK)
    cyl(m, "dish", (12.75, 12.5), 4, 0.25, 1, PORCELAIN)

    def kimchi(c):
        return ("pepper", 2 if h3(c.i[0], c.i[1], c.i[2], 7) < 0.6 else 3)
    cyl(m, "kimchi", (12.75, 12.5), 3, 1, 1.75, Paint("pepper", 2, decal=kimchi))
    sticks = flat("blade", 2, rim=0.5)
    m.box("chopstick", (2, 4.75, 9.75), (12.5, 5.0, 10.0), sticks)
    m.box("chopstick", (2, 4.75, 10.5), (12.5, 5.0, 10.75), sticks)
    return finish(m, (1, 1, 1))


# ================================================================================================
# 2도어 냉장고
# ================================================================================================

def fridge():
    """2도어 냉장고 1x2: a mint-green two-door refrigerator with rounded top corners: the freezer above and the
    fridge below, long chrome handles on the left, a chrome trim line, a dark kick grille, a few fruit magnets and
    a memo pinned on the freezer door. No maker's name."""
    m = BModel("fridge")
    body = Paint("celadon", 3, noise=0)
    X0, X1, Z0, Z1 = 1.5, 14.5, 3, 14
    m.group("cabinet")
    m.box("foot", (X0 + 0.5, 0, Z0 + 1), (X1 - 0.5, 1.5, Z1 - 0.5), flat("rubber", 1))

    def grille(c):
        if c.face == "north" and math.floor(c.p[0]) % 2 == 0:
            return ("rubber", 0)
        return None
    m.box("grille", (X0 + 0.5, 0.25, Z0 + 0.75), (X1 - 0.5, 1.25, Z0 + 1), flat("rubber", 1, decal=grille))
    m.box("body", (X0, 1.5, Z0 + 1), (X1, 29, Z1), body)
    m.box("top", (X0 + 0.5, 29, Z0 + 1.5), (X1 - 0.5, 29.75, Z1 - 0.5), body)
    m.group("doors")
    door = Paint("celadon", 3, noise=0, rim=1.2)
    m.box("badge", (3, 27, Z0 - 0.25), (6, 27.5, Z0), CHROME)
    m.box("door_low", (X0, 1.75, Z0), (X1, 18.5, Z0 + 1), door)
    m.box("trim", (X0, 18.5, Z0 - 0.25), (X1, 19.25, Z0 + 1), CHROME)
    m.box("door_top", (X0, 19.25, Z0), (X1, 28.75, Z0 + 1), door)
    # long chrome handles on the hinge-free side (the viewer's left, +x)
    for (y0, y1) in ((10, 17), (20.5, 25)):
        m.box("handle", (X1 - 2.25, y0, Z0 - 1), (X1 - 1.25, y1, Z0 - 0.5), CHROME)
        for y in (y0, y1 - 1):
            m.box("handle_post", (X1 - 2.25, y, Z0 - 0.5), (X1 - 1.25, y + 1, Z0), CHROME)
    m.group("magnets")

    def memo(c):
        if c.face == "north" and math.floor(c.p[1] * 2) % 2 == 0 and 4.5 < c.p[0] < 8.5:
            return ("bean", 1)                      # lines of handwriting
        return None
    m.box("memo", (4, 21, Z0 - 0.25), (9, 26, Z0), Paint("paper", 2, noise=0, decal=memo))
    m.box("magnet", (6, 25.25, Z0 - 0.5), (7, 26.25, Z0), flat("pepper", 2))
    m.box("magnet", (9.5, 23.5, Z0 - 0.5), (11, 25, Z0), flat("corn", 3))
    m.box("magnet_leaf", (10, 25, Z0 - 0.5), (10.5, 25.5, Z0), flat("leaf", 2))
    m.box("magnet", (4.5, 14, Z0 - 0.5), (6, 15.5, Z0), flat("tomato", 2))
    return finish(m, (1, 2, 1))


# ================================================================================================
# 연탄 더미
# ================================================================================================

def _briquette(m, name, x, y, z, burnt=False, seed=0):
    """One 연탄 standing up: a short cylinder 6 px across and 4 high, its holes (one in the middle, a ring of eight)
    dark on top; the sides ribbed with faint press lines. A burnt one is pale ash grey with a rusty tinge."""
    holes = {(0, 0), (-2, 0), (2, 0), (0, -2), (0, 2), (-2, -2), (2, 2), (-2, 2), (2, -2)}

    def look(c):
        if c.face in ("up", "down"):
            u, v = math.floor(c.p[0] - x), math.floor(c.p[2] - z)
            if (u + 0, v + 0) in holes or (u + 1, v + 1) in holes and False:
                return ("millstone", 1) if burnt else ("rubber", 0)
            return ("millstone", 4) if burnt else ("iron", 3)
        r = h3(c.i[0], c.i[1], c.i[2], seed)
        if burnt:
            return ("clay", 1) if r < 0.15 else ("millstone", 3 if r < 0.7 else 4)
        if c.p[1] - y > 3.4:
            return ("iron", 3)                          # its pressed top edge catches the light
        return ("iron", 1 if r < 0.7 else 2)
    paint = Paint("millstone" if burnt else "iron", 3 if burnt else 1, decal=look, noise=0)
    cyl(m, name, (x + 0.5, z + 0.5), 6, y, y + 0.5, paint)
    cyl(m, name, (x + 0.5, z + 0.5), 7, y + 0.5, y + 4, paint)


def briquette_pile():
    """연탄 더미 1x1: coal briquettes stacked two by two, three high at the back and two at the front, their holes on
    top; a burnt-out pale one set on the stack, and the iron briquette tongs laid across it (within the 1-cell budget)."""
    m = BModel("briquette_pile")
    m.group("stack")
    k = 0
    for (x, z) in ((4, 11), (11, 11), (4, 4), (11, 4)):
        layers = 3 if z > 8 else 2
        for j in range(layers):
            k += 1
            _briquette(m, "briquette", x, j * 4, z, seed=k)
    m.group("ash")
    _briquette(m, "burnt", 11, 8, 4, burnt=True, seed=50)          # a burnt-out one set back on the stack
    m.group("tongs")
    tong = flat("iron", 2, rim=0.5)
    # the briquette tongs laid across the top of the front left column, their hooked ends over the edge
    for z in (3.25, 4.75):
        m.box("tongs", (0.5, 8, z), (8.25, 8.5, z + 0.5), tong)
    m.box("tongs_hook", (0.5, 6.5, 3.25), (1, 8, 3.75), tong)
    m.box("tongs_hook", (0.5, 6.5, 4.75), (1, 8, 5.25), tong)
    m.box("tongs_grip", (6.5, 8.5, 3.25), (7.25, 9, 5.25), tong)
    return finish(m, (1, 1, 1))


# ================================================================================================
# 양은 주전자
# ================================================================================================

def brass_kettle():
    """양은 주전자 1x1: the yellow aluminium kettle every makgeolli table had: a squat round body widest at the
    bottom, a clear shoulder, a domed lid with a small knob, a long thin spout rising at an angle from the lower
    body, the wire bail handle standing up; on a round tray with two small cups of makgeolli."""
    m = BModel("brass_kettle")
    m.group("tray")
    cyl(m, "tray", (8, 8), 14, 0, 0.5, YANGEUN)
    ring(m, "tray_rim", (8, 8), 14, 12, 0.5, 1.25, flat("brass", 3))
    m.group("kettle")
    kx, kz = 8, 6.25
    # the body in thin rings: a foot, the wide bottom drum, then the shoulder sloping in to the neck
    for (lo, hi, d) in ((0.5, 1.0, 7), (1.0, 3.5, 8), (3.5, 4.0, 7), (4.0, 4.5, 6), (4.5, 5.0, 5)):
        cyl(m, "kettle", (kx, kz), d, lo, hi, YANGEUN_DARK if lo == 0.5 else YANGEUN)
    cyl(m, "lid_rim", (kx, kz), 5, 5.0, 5.5, flat("brass", 3, rim=0.8))
    cyl(m, "lid", (kx, kz), 4, 5.5, 6.0, flat("brass", 3))
    cyl(m, "lid", (kx, kz), 2, 6.0, 6.5, flat("brass", 3))
    m.box("lid_knob", (kx - 0.5, 6.5, kz - 0.5), (kx + 0.5, 7.25, kz + 0.5), BLACK)
    # the long spout rising from the lower body, out to the side (seen in profile from the front 3/4)
    sp = Paint("brass", 2, noise=0, rim=0.6)
    ox, oy = kx + 3.5, 2.0
    m.box("spout", (ox - 0.5, oy - 0.5, kz - 0.5), (ox + 5.0, oy + 0.5, kz + 0.5), sp, rotation=("z", 45, (ox, oy, kz)))
    m.box("spout_tip", (ox + 4.0, oy + 0.5, kz - 0.5), (ox + 5.0, oy + 1.0, kz + 0.5), sp, rotation=("z", 45, (ox, oy, kz)))
    # the bail handle standing up on two lugs at the shoulder
    wire = flat("brass", 1, rim=0.4)
    for x in (kx - 4.0, kx + 3.5):
        m.box("lug", (x, 3.75, kz - 0.5), (x + 0.5, 4.75, kz + 0.5), YANGEUN_DARK)
        m.box("bail", (x, 4.75, kz - 0.25), (x + 0.5, 8.25, kz + 0.25), wire)
    # rounded shoulders and the top bar
    m.box("bail", (kx - 3.5, 8.25, kz - 0.25), (kx - 2.5, 8.75, kz + 0.25), wire)
    m.box("bail", (kx + 2.5, 8.25, kz - 0.25), (kx + 3.5, 8.75, kz + 0.25), wire)
    m.box("bail", (kx - 3.0, 8.75, kz - 0.25), (kx + 3.0, 9.25, kz + 0.25), wire)
    m.group("cups")

    def makgeolli(c):
        if c.face == "up":
            return ("cream", 2)
        return ("brass", 1) if (c.i[0] + c.i[2] + 2 * c.i[1]) % 3 == 0 else None    # hammered dimples
    for (x, z) in ((5, 11), (11, 11)):
        cyl(m, "cup", (x, z), 3, 0.5, 2.75, YANGEUN(decal=makgeolli))
    return finish(m, (1, 1, 1))


# ================================================================================================
# 카세트 라디오
# ================================================================================================

def cassette_radio():
    """카세트 라디오 1x1: a silver-and-black boom box on a little crocheted mat: round speakers with chrome rings on
    both sides, a cassette deck in the middle (its two reels behind the window), piano-key buttons on top, the
    tuning band with a red needle, a carrying handle and a telescopic antenna raised at an angle."""
    m = BModel("cassette_radio")
    m.group("mat")
    m.box("mat", (1, 0, 4.5), (15, 0.25, 11.5), Paint("cream", 2, noise=0.0, decal=lambda c: ("cream", 1)
                                                         if c.face == "up" and (c.i[0] + c.i[2]) % 3 == 0 else None))
    m.group("body")
    X0, X1, Z0, Z1 = 0.5, 15.5, 6, 10
    m.box("body", (X0, 0.25, Z0), (X1, 8.75, Z1), Paint("steel", 3, noise=0))
    m.box("top_band", (X0, 8.75, Z0 + 0.25), (X1, 9.25, Z1 - 0.25), flat("rubber", 1))

    def front(c):
        if c.face != "north":
            return None
        x, y = c.p[0], c.p[1]
        i, j = c.i[0], c.i[1]
        if 6 <= i <= 9:
            if j in (2, 3, 4):
                if j == 3:
                    if i in (6, 9):
                        return ("porcelain", 2)             # the two reels
                    return ("leather", 0)                   # the tape between them
                if j == 4:
                    return ("paper", 2)                     # the label above the window
                return ("tint", 1)                          # the window's lower edge
            if j in (6, 7):
                if i == 8 and j == 6:
                    return ("signal_red", 1)                # the tuning needle
                return ("dial", 1) if j == 6 else ("dial", 0)
        return None
    m.box("face", (X0, 0.5, Z0 - 0.25), (X1, 8.5, Z0), Paint("steel", 1, noise=0, decal=front))
    # the two speakers, built round: a bright silver rim standing out of the front, the dark dotted cone in it
    # and a silver dust cap in the middle
    def grille(c):
        if c.face == "north" and c.i[0] % 2 == 0 and c.i[1] % 2 == 1:
            return ("rubber", 2)
        return None
    for sx in (3.25, 12.75):
        cyl(m, "speaker_rim", (sx, 4.5), 5, Z0 - 0.5, Z0, flat("blade", 3, rim=0.6), axis="z")
        cyl(m, "speaker_cone", (sx, 4.5), 3, Z0 - 0.75, Z0 - 0.5, flat("rubber", 0, decal=grille), axis="z")
        m.box("dust_cap", (sx - 0.5, 4.0, Z0 - 1.0), (sx + 0.5, 5.0, Z0 - 0.75), flat("blade", 2))
    bezel = flat("steel", 3, rim=0.5)                     # a raised frame round the cassette window
    m.box("bezel", (5.75, 1.5, Z0 - 0.5), (10.25, 1.75, Z0 - 0.25), bezel)
    m.box("bezel", (5.75, 5.25, Z0 - 0.5), (10.25, 5.5, Z0 - 0.25), bezel)
    m.box("bezel", (5.75, 1.75, Z0 - 0.5), (6.0, 5.25, Z0 - 0.25), bezel)
    m.box("bezel", (10.0, 1.75, Z0 - 0.5), (10.25, 5.25, Z0 - 0.25), bezel)
    # piano-key buttons on top, the handle, the antenna
    for k, ramp in enumerate(("rubber", "rubber", "pepper", "rubber", "rubber", "rubber")):
        m.box("key", (5.5 + k, 9.25, Z0 + 0.5), (6.25 + k, 9.75, Z0 + 1.75), flat(ramp, 1 if ramp == "rubber" else 2))
    for x in (3, 12.5):
        m.box("handle_post", (x, 9.25, 7.5), (x + 0.5, 11.5, 8.5), CHROME)
    m.box("handle", (3, 11.5, 7.5), (13, 12.25, 8.5), BLACK)
    m.box("antenna", (1.5, 9.25, 9.25), (2, 21, 9.75), CHROME, rotation=("z", 22.5, (1.75, 9.25, 9.5)))
    m.box("antenna_tip", (1.25, 21, 9.0), (2.25, 21.75, 10.0), CHROME, rotation=("z", 22.5, (1.75, 9.25, 9.5)))
    return finish(m, (1, 1, 1))


# ================================================================================================
# 고무대야 빨래
# ================================================================================================

def washtub():
    """고무대야 빨래 1x1: the big red rubber basin of every yard: soapy water with a white undershirt and a blue cloth
    soaking in it, a ribbed wooden washboard leaning in it and a wooden laundry bat laid across the rim."""
    m = BModel("washtub")
    red = Paint("fabric_red", 2, noise=0.02)
    m.group("tub")
    cyl(m, "tub", (8, 8), 13, 0, 1, red)
    cyl(m, "tub", (8, 8), 14, 1, 3, red)
    ring(m, "tub_wall", (8, 8), 14, 12, 3, 4.5, red)
    ring(m, "rim", (8, 8), 15, 12, 4.5, 5.5, flat("fabric_red", 3, rim=0.8))
    m.group("water")

    def soapy(c):
        x, z = c.p[0], c.p[2]
        if h3(c.i[0], 0, c.i[2], 17) < 0.10:
            return ("porcelain", 3)                     # suds
        return ("water", 2)
    cyl(m, "water", (8, 8), 12, 3, 4.25, Paint("water", 2, noise=0, decal=soapy))
    # the laundry soaking: a white undershirt and a blue cloth, humped above the water
    cloth = Paint("porcelain", 2, noise=0.05)
    m.box("shirt", (3.5, 4.25, 6), (7.5, 4.75, 10), cloth)
    m.box("shirt", (4.5, 4.75, 7), (6.5, 5.0, 9), cloth)
    m.box("cloth", (8.5, 4.25, 8.5), (11, 4.75, 11), Paint("denim", 2, noise=0.05))
    m.group("board")

    def ribs(c):
        if c.face == "north" and 3 < c.p[1] < 12.5 and 5.75 < c.p[0] < 10.25:
            return ("wood", 0) if math.floor(c.p[1]) % 2 == 0 else ("wood", 3)
        return None
    m.box("washboard", (5, 2, 11.5), (11, 14, 12.25), Paint("wood", 2, "grain", axis="y", decal=ribs),
          rotation=("x", 22.5, (8, 2, 11.75)))
    m.box("bat", (1, 5.5, 4.25), (15, 6.25, 5.25), WOOD_DARK)
    m.box("bat_grip", (13.75, 5.25, 4), (15.75, 6.5, 5.5), WOOD_DARK)
    return finish(m, (1, 1, 1))


# ================================================================================================
# Registry
# ================================================================================================

PIECES = {
    # id: (builder, cells as (w, h, d) blocks, floor)
    "yeontan_stove": (yeontan_stove, (1, 2, 1), 0),
    "kerosene_stove": (kerosene_stove, (1, 1, 1), 0),
    "rice_cooker": (rice_cooker, (1, 1, 1), 0),
    "glass_cupboard": (glass_cupboard, (1, 2, 1), 0),
    "ramen_pot": (ramen_pot, (1, 1, 1), 0),
    "fridge": (fridge, (1, 2, 1), 0),
    "briquette_pile": (briquette_pile, (1, 1, 1), 0),
    "brass_kettle": (brass_kettle, (1, 1, 1), 0),
    "cassette_radio": (cassette_radio, (1, 1, 1), 0),
    "washtub": (washtub, (1, 1, 1), 0),
}

ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}

# the real thing each piece stands for, roughly (1 block ~ 1 m, a player 1.8 m)
REAL = {
    "yeontan_stove": "난로 지름 45 cm, 연통 포함 높이 1.8 m", "kerosene_stove": "지름 40 cm, 냄비 포함 높이 60 cm (조금 크게)",
    "rice_cooker": "지름 35 cm, 높이 40 cm (조금 크게)", "glass_cupboard": "폭 90 cm, 높이 1.9 m",
    "ramen_pot": "냄비 지름 18 cm (조금 크게)", "fridge": "폭 80 cm, 높이 1.8 m",
    "briquette_pile": "연탄 하나 지름 15 cm (조금 크게)", "brass_kettle": "주전자 높이 25 cm (조금 크게)",
    "cassette_radio": "폭 60 cm (조금 크게)", "washtub": "지름 90 cm, 높이 30 cm",
}
