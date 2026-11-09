"""
Japanese-style decorative furniture (content/stsv/items/furniture_japanese.yml): 30 pieces for houses and
gardens, built as baked models (stsv_assets/bake.py): every face is painted in model space at 16 px a block
(the same texel size on every piece), with baked light (rims, contact shadows, ambient occlusion in creases)
and one texture atlas per model.

Coordinates: model units (16 = 1 block), front = north (z 0), the anchor cell is x 0..16 / z 0..16, y 0 = its
floor. Pieces that reach over a block to the left/right or to the front use negative x / z (the kotatsu's
cushions, the torii's posts); pieces three blocks tall are drawn from y 0 and moved down a block (dy -16,
furniture translation [0, 1, 0]), like the forge. Seen from the front, model +x is on the viewer's LEFT.
Every piece sits in the middle of its cells (a size that is not a whole number of blocks, e.g. the futon's
1.5 x 2.5, leaves the same margin on both sides; JapaneseFurnitureTest checks it, docs/previews/
furniture_footprints.png shows it); a piece wider than a model may be (over 3 blocks: the torii) is drawn at its
real size and shrunk (BModel scale), the item display scaling it back up (furniture.scale).

Collision and click boxes come from the model itself (FurnitureShape: a cell gets a barrier when the model
fills 30% of it, voxels of 1/4 block), so low and thin things (mats, cushions, curtains, stepping stones) are
drawn so they stay under that, and walls of a piece (screens, fences) are centred on the cell (z 7..9 crosses
two voxel rows) so they do get one. Lamps give real light (furniture.light in the item file); their paper or
fire flickers (an animated texture, no entities), and the koi swim round their pond the same way.

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
"""

import math

from economy_machines import display
from stsv_assets.bake import BModel, Paint, cyl, h3, vnoise

# ================================================================================================
# Paints (palette ramp, base step, pattern); see bake.Paint
# ================================================================================================

STONE = Paint("millstone", 2, "stone")
STONE_LIGHT = Paint("millstone", 3, "stone")
STONE_DARK = Paint("millstone", 1, "stone")
HINOKI = Paint("hinoki", 2, "grain", axis="long")
DARK = Paint("wood_dark", 1, "grain", axis="long", noise=0.03)
LACQ = Paint("rubber", 1, "lacquer", noise=0.02)
LACQ_RED = Paint("vermilion", 2, "lacquer", noise=0.02)
CORD = Paint("bark", 0, noise=0, rim=0.5)
BARK = Paint("bark", 2, "grain", axis="long")
SOIL = Paint("soil", 1, "speckle")
MOSS = Paint("leaf", 2, "speckle")
BRASS = Paint("brass", 2, "metal")
IRON = Paint("iron", 2, "metal")
PORCELAIN = Paint("porcelain", 2, noise=0.02)
CELADON = Paint("celadon", 2, noise=0.03)
SIDE_FACES = ("north", "south", "east", "west")


def flat(ramp_name, step, **kw):
    """A plain paint without pattern or noise (small parts, cloth)."""
    return Paint(ramp_name, step, noise=0, **kw)


def moss_on(paint, amount=0.62, cell=2.5, seed=5):
    """The paint with moss patches on its upward faces."""
    def decal(c):
        if c.face != "up":
            return None
        v = vnoise(c.p[0], c.p[1] * 2, c.p[2], cell, seed)
        if v > amount:
            return ("leaf", 2 if v > amount + 0.12 else 1)
        return None
    return paint(decal=decal, decal_pattern=True)


def picture(rows, colours, x_left, y_top, face="north"):
    """A decal from a little pixel picture (list of strings, '.' = the paint's own surface): column 0 starts at
    model x = x_left on the viewer's left (north faces: the viewer's left is +x; south faces: -x)."""
    def decal(c):
        if c.face != face:
            return None
        col = math.floor(x_left - c.p[0]) if face == "north" else math.floor(c.p[0] - x_left)
        row = math.floor(y_top - c.p[1])
        if 0 <= row < len(rows) and 0 <= col < len(rows[row]):
            ch = rows[row][col]
            if ch != ".":
                return colours[ch]
        return None
    return decal


def chain(*decals):
    """Several decals: the first that paints a texel wins."""
    def decal(c):
        for d in decals:
            got = d(c)
            if got is not None:
                return got
        return None
    return decal


def poly(m, name, c, w, cut, lo, hi, paint, steps=2):
    """An octagonal slab w wide whose corners are cut `cut` deep, the cut edge drawn in `steps` pixel steps
    (1 + 2 * steps boxes; a hexagonal stone part reads the same at this size)."""
    x, z = c
    half = w / 2
    m.box(name, (x - half, lo, z - half + cut), (x + half, hi, z + half - cut), paint)
    s = cut / steps
    for k in range(steps):
        # row k from the edge inwards: the octagon's half width at the middle of the row, on the half pixel
        za, zb = half - (k + 1) * s, half - k * s
        hw = math.floor((half - cut + (k + 0.5) * s) * 2 + 0.5) / 2
        m.box(name, (x - hw, lo, z - zb), (x + hw, hi, z - za), paint)
        m.box(name, (x - hw, lo, z + za), (x + hw, hi, z + zb), paint)


def cup(m, name, c, lo, hi, paint):
    """A cup 3 px across: a square with its corners cut (a 3 px circle), three boxes."""
    x, z = c
    m.box(name, (x - 1.5, lo, z - 0.5), (x + 1.5, hi, z + 0.5), paint)
    m.box(name, (x - 1, lo, z - 1.5), (x + 1, hi, z - 0.5), paint)
    m.box(name, (x - 1, lo, z + 0.5), (x + 1, hi, z + 1.5), paint)


def cup4(m, name, c, lo, hi, paint):
    """A wide cup 4 px across: a square with its corners cut (a 4 px circle), three boxes."""
    x, z = c
    m.box(name, (x - 2, lo, z - 1), (x + 2, hi, z + 1), paint)
    m.box(name, (x - 1, lo, z - 2), (x + 1, hi, z - 1), paint)
    m.box(name, (x - 1, lo, z + 1), (x + 1, hi, z + 2), paint)


def tea_top(x, z, ramp_name="bamboo", step=3, r=0.6):
    """Decal: the tea (or sake) showing in the middle of a cup's top (r: half its width)."""
    def decal(c):
        if c.face == "up" and abs(c.p[0] - x) < r and abs(c.p[2] - z) < r:
            return (ramp_name, step)
        return None
    return decal


def puff(m, name, c, w, h, d, paint):
    """A soft rounded clump (blossom, foliage): a core box with smaller caps above and below; c = bottom centre."""
    x, y, z = c
    m.box(name, (x - w / 2, y + 1, z - d / 2), (x + w / 2, y + h - 1, z + d / 2), paint)
    m.box(name, (x - w / 2 + 1, y + h - 1, z - d / 2 + 1), (x + w / 2 - 1, y + h, z + d / 2 - 1), paint)
    m.box(name, (x - w / 2 + 1, y, z - d / 2 + 1), (x + w / 2 - 1, y + 1, z + d / 2 - 1), paint)


def flicker(k, seed):
    """A lamp's brightness wobble at frame k (in ramp steps): mostly steady, now and then a soft dip or flare."""
    seq = (0, 0, 0.25, 0.1, 0, -0.3, -0.15, 0, 0, 0.35, 0.15, 0)
    return seq[(k + seed) % len(seq)]


# ================================================================================================
# 바닥 · 앉는 것
# ================================================================================================

def tatami():
    """다다미 2x1: a rush mat 1.5 high, the rush running the long way in fine rows, dark cloth borders (heri)
    with a thin woven line along the long sides."""
    m = BModel("tatami")
    m.group("mat")

    def rush(c):
        if c.face == "up":
            step = 2 if c.i[2] % 2 else 3
            if c.i[0] % 8 == 3:                       # the warp threads across, every half block
                step -= 1
            return ("tatami", step)
        if c.face in ("east", "west"):                 # cut ends: the rush bundles
            return ("tatami", 1 if (c.i[1] + c.i[2]) % 2 else 2)
        return None
    m.box("omote", (0, 0, 1.5), (32, 1.5, 14.5), Paint("tatami", 2, decal=rush, rim=0.6))

    def heri(c):
        if c.face == "up" and c.i[2] in (0, 15) and c.i[0] % 3 != 2:
            return ("indigo", 1)                       # a thin woven line along the border
        return None
    for z0 in (0, 14.5):
        m.box("heri", (0, 0, z0), (32, 1.5, z0 + 1.5), Paint("indigo", 0, noise=0, decal=heri, rim=0.6))
    return display(m, (2, 1, 1), 0)


def cushion(m, x0, z0, w, colour="crimson", h=1.0):
    """Zabuton: a square floor cushion 2.75 high (times h), softly domed, with a piped seam, a tuft in the middle
    and a gold tassel at each corner."""
    cloth = Paint(colour, 2, "weave", rim=0.8)

    def seam(c):
        if c.face == "up":
            dx = min(c.p[0] - x0, x0 + w - c.p[0])
            dz = min(c.p[2] - z0, z0 + w - c.p[2])
            if 1 < min(dx, dz) < 2:
                return (colour, 3)
        return None
    y1, y2, y3, y4 = (round(v * h * 4) / 4 for v in (1.25, 2.25, 2.75, 3.0))
    m.box("cushion", (x0, 0, z0), (x0 + w, y1, z0 + w), cloth)
    m.box("cushion", (x0 + 0.5, y1, z0 + 0.5), (x0 + w - 0.5, y2, z0 + w - 0.5), cloth(decal=seam))
    m.box("cushion_top", (x0 + 2.5, y2, z0 + 2.5), (x0 + w - 2.5, y3, z0 + w - 2.5), cloth)
    c = w / 2
    m.box("tuft", (x0 + c - 0.5, y3, z0 + c - 0.5), (x0 + c + 0.5, y4, z0 + c + 0.5), flat(colour, 0))
    tassel = Paint("gold", 2, noise=0, rim=0.5)
    for (dx, dz) in ((-0.5, -0.5), (w - 0.5, -0.5), (-0.5, w - 0.5), (w - 0.5, w - 0.5)):
        m.box("tassel", (x0 + dx + 0.125, 0.25, z0 + dz + 0.125), (x0 + dx + 0.875, y1 + 0.25, z0 + dz + 0.875), tassel)


def zabuton():
    """방석: the floor cushion."""
    m = BModel("zabuton")
    m.group("cushion")
    cushion(m, 1.5, 1.5, 13)
    return display(m, (1, 1, 1), 0)


def chabudai():
    """좌식 탁자: a round low table on folding legs (a whole block across, its top 9 px up: still a floor table,
    and never over the cushions round it), two cups of green tea and a skewer of hanami dango."""
    m = BModel("chabudai")
    m.group("table")
    top = Paint("wood", 2, "planks", axis="x", width=3, rim=1.0)

    def edge(c):
        if c.face == "up" and math.hypot(c.p[0] - 8, c.p[2] - 8) > 7.1:
            return ("wood", 1)
        return None
    cyl(m, "top", (8, 8), 16, 7.25, 9, top(decal=edge))
    cyl(m, "apron", (8, 8), 13, 6, 7.25, DARK)
    leg = Paint("wood_dark", 1, "grain", axis="y")
    for (x, z) in ((3.5, 3.5), (11, 3.5), (3.5, 11), (11, 11)):
        m.box("leg", (x, 0, z), (x + 1.5, 6, z + 1.5), leg)
    m.box("brace", (5, 1.75, 3.75), (11, 2.5, 4.75), DARK)
    m.box("brace", (5, 1.75, 11.25), (11, 2.5, 12.25), DARK)
    m.group("tea")
    for (x, z) in ((5.5, 6.5), (10, 11)):
        m.box("cup_foot", (x - 1, 9, z - 1), (x + 1, 9.25, z + 1), flat("celadon", 1))
        cup(m, "cup", (x, z), 9.25, 11.75, CELADON(decal=tea_top(x, z)))
    m.group("dango")
    cyl(m, "plate", (10.5, 5.5), 5, 9, 9.5, PORCELAIN)
    m.box("skewer", (8, 10.25, 5.25), (13.5, 10.5, 5.75), flat("bamboo", 3))
    for k, ramp_name in enumerate(("pink", "rice", "sprout")):
        x = 8.75 + 1.5 * k
        m.box("dango", (x, 9.5, 4.75), (x + 1.5, 11, 6.25), Paint(ramp_name, 1 if ramp_name == "rice" else 2, noise=0))
    return display(m, (1, 1, 1), 0)


def kotatsu():
    """고타츠: the heated table under its quilt (the quilt falls in folds and spreads out on the floor), a wooden
    board, a basket of mandarins, and a cushion on each side (plus-shaped, 3x3 blocks without the corners).
    The table stays a block wide above the floor layer (y 4 up): anything more over the cushions' cells would
    fill them enough for a barrier where people sit; so it grows in height and in the quilt's spread instead."""
    m = BModel("kotatsu")
    m.group("table")

    def plaid(c):
        i = c.i
        u = i[0] if c.face in ("north", "south", "up") else i[2]
        v = i[2] if c.face == "up" else i[1]
        on_u, on_v = u % 6 in (1, 2), v % 6 in (1, 2)
        step = 1 if on_u and on_v else 2 if on_u or on_v else 3
        if c.side and c.p[1] < 6.5 and u % 4 == 0:      # folds where the quilt falls to the floor
            step -= 1
        return ("fox", step)
    quilt = Paint("fox", 3, decal=plaid, rim=0.7)
    # the quilt spreads out on the floor in three steps (all under y 4, the cushions' layer)
    m.box("quilt_spread", (-1.5, 0, -1.5), (17.5, 1, 17.5), quilt)
    m.box("quilt_spread", (-1, 1, -1), (17, 2.25, 17), quilt)
    m.box("quilt_hem", (-0.5, 2.25, -0.5), (16.5, 3.5, 16.5), quilt)
    m.box("quilt", (0.25, 3.5, 0.25), (15.75, 6.75, 15.75), quilt)
    m.box("quilt_top", (0.75, 6.75, 0.75), (15.25, 7.25, 15.25), quilt)

    def board_edge(c):
        if c.face == "up" and (c.i[0] in (0, 15) or c.i[2] in (0, 15)):
            return ("wood_dark", 1)
        return None
    m.box("board", (0, 7.25, 0), (16, 8.75, 16), Paint("wood", 2, "planks", axis="x", width=4, decal=board_edge))
    m.group("mikan")
    m.box("basket", (4.5, 8.75, 4.5), (11.5, 10.5, 11.5), Paint("straw", 2, "weave"))
    m.box("basket_inside", (5.25, 10.5, 5.25), (10.75, 10.75, 10.75), Paint("straw", 1, "weave"))
    orange = Paint("fox", 3, noise=0, rim=1.0)
    for (x, z, y) in ((5.25, 5.5, 10.75), (8.25, 5.75, 10.75), (5.75, 8.5, 10.75), (8.5, 8.5, 10.75), (7, 7, 12.75)):
        m.box("mikan", (x, y, z), (x + 2, y + 2, z + 2), orange)
        m.box("mikan_stem", (x + 0.75, y + 2, z + 0.75), (x + 1.25, y + 2.25, z + 1.25), flat("leaf_dark", 1))
    m.box("leaf", (8.25, 14.75, 7.5), (9.75, 15.0, 8.5), flat("leaf", 3))
    m.group("cushions")
    # 14 px cushions (were 12), each centred on its side's cell, a touch taller: the seats are at y 3 (0.19)
    cushion(m, 1, -15.5, 14, h=1.15)
    cushion(m, 1, 17.5, 14, h=1.15)
    cushion(m, -15.5, 1, 14, h=1.15)
    cushion(m, 17.5, 1, 14, h=1.15)
    return display(m, (3, 1, 3), 0)


def futon():
    """이불 세트 2x3 칸 (1.5 x 2.5 blocks, centred: a quarter block free all round): a white mattress, an indigo
    quilt with a hemp-leaf (asanoha) pattern that drapes over the sides, its white collar folded back, and two
    striped buckwheat pillows side by side."""
    m = BModel("futon", shift=(0, 0, -16))           # drawn over z 0..48, its cells are z -1..1 (anchor in the middle)
    m.group("futon")
    cotton = Paint("rice", 1, "weave", rim=0.8)
    m.box("shikibuton", (4.5, 0, 4.5), (27.5, 1.5, 44), cotton)
    m.box("shikibuton", (5, 1.5, 5), (27, 2, 43.5), cotton)

    def asanoha(c):
        if c.face == "down":
            return None
        if c.face == "up":
            u, v = c.i[0], c.i[2]
        elif c.face in ("east", "west"):
            u, v = c.i[2], c.i[1]
        else:
            u, v = c.i[0], c.i[1]
        if (u + v) % 6 == 0 or (u - v) % 6 == 0:
            return ("indigo", 3)                       # a lattice of light diamonds
        if (u + v) % 6 == 3 and (u - v) % 6 == 3:
            return ("porcelain", 1)                    # a dot in the middle of each
        return ("indigo", 2)
    quilt = Paint("indigo", 2, decal=asanoha, rim=0.8)
    m.box("kakebuton", (4.5, 2, 4.5), (27.5, 3.5, 33), quilt)
    m.box("drape", (4, 0.5, 4.5), (4.5, 3.5, 33), quilt)
    m.box("drape", (27.5, 0.5, 4.5), (28, 3.5, 33), quilt)
    m.box("drape", (4.5, 0.5, 4), (27.5, 3.5, 4.5), quilt)
    m.box("collar", (4.5, 3.5, 29), (27.5, 4, 33.5), Paint("rice", 2, "weave", rim=0.8))
    m.group("pillow")

    def stripes(c):
        return ("indigo", 3) if c.i[0] % 3 == 0 else ("rice", 1)
    # kept under a quarter block, like the rest of the futon, so it never blocks a sleeper
    pillow = Paint("rice", 1, decal=stripes, rim=0.6)
    for x in (7.5, 17.5):                     # two pillows, mirrored about the futon's middle (x 16)
        m.box("makura", (x, 2, 36.5), (x + 7, 4, 40.5), pillow)
        for x0 in (x - 0.5, x + 7):
            m.box("makura_end", (x0, 2.25, 37), (x0 + 0.5, 3.75, 40), pillow)
    return display(m, (2, 1, 3), 0)


# ================================================================================================
# 칸막이 · 벽
# ================================================================================================

def shoji_screen():
    """쇼지 칸막이 2x2: two panels of hinoki frame and kumiko lattice with washi paper (the lattice casts a
    hairline shadow on the paper), a planked kick board, on low feet."""
    m = BModel("shoji_screen")
    m.group("panels")
    frame = Paint("hinoki", 2, "grain", axis="long", noise=0.02)

    def lattice(c):
        if c.face not in ("north", "south"):
            return None
        u = c.i[0] % 16
        y = c.i[1]
        bar_u = u in (5, 10)
        bar_v = (y - 6) % 4 == 3
        if bar_u or bar_v:
            return ("hinoki", 3 if (bar_u and bar_v) else 2)
        if (y - 6) % 4 == 2 or (u in (4, 9) and c.face == "north") or (u in (6, 11) and c.face == "south"):
            return ("paper", 1)                       # hairline shadow under / beside a bar
        return ("paper", 2)
    paper = Paint("paper", 2, "paper", decal=lattice, ao=0.6)
    for x0 in (0, 16):
        m.box("stile", (x0, 1, 7), (x0 + 1.5, 30, 9), frame)
        m.box("stile", (x0 + 14.5, 1, 7), (x0 + 16, 30, 9), frame)
        m.box("rail", (x0 + 1.5, 28.5, 7), (x0 + 14.5, 30, 9), frame)
        m.box("rail", (x0 + 1.5, 6, 7), (x0 + 14.5, 7, 9), frame)
        m.box("koshiita", (x0 + 1.5, 1, 7.25), (x0 + 14.5, 6, 8.75), Paint("hinoki", 2, "planks", axis="x", width=2))
        m.box("paper", (x0 + 1.5, 7, 7.5), (x0 + 14.5, 28.5, 8.5), paper)
    m.group("feet")
    for x in (1, 14, 27):
        m.box("foot", (x, 0, 4), (x + 4, 1, 12), DARK)
    return display(m, (2, 2, 1), 0)


def _fusuma_art(col, row):
    """The painting over both doors, 26 x 44 px (col 0 at the viewer's left, row 0 at the top): gold leaf
    squares, drifting gold clouds, the red sun high up, Mt Fuji in indigo with its snow cap rising above a
    sea of gold cloud, a pine in the lower corner."""
    peak = 13
    half = (row - peak) * 0.72 + 1.0
    fuji = peak <= row < 34 and abs(col + 0.5 - 14) <= half
    if math.hypot(col + 0.5 - 20.5, row + 0.5 - 7.5) < 3.6:
        return ("vermilion", 2 if col + row < 27 else 1)
    for (cx, cy, rx) in ((4, 24, 4), (7, 29, 3), (2, 33, 3), (6, 37, 3), (3, 40, 3)):
        if row in (cy, cy + 1) and abs(col - cx) <= (rx if row == cy + 1 else rx - 1):
            return ("leaf_dark", 2 if row == cy else 1)
    if (col in (3, 4) and row >= 25) or (col == 5 and 27 <= row <= 29) or (col == 2 and 33 <= row <= 34) \
            or (col == 5 and 36 <= row <= 37):
        return ("bark", 1 if col == 4 else 2)
    # the sea of cloud Fuji rises from, and drifting clouds lower down
    for (x0, x1, y) in ((3, 26, 32), (0, 26, 34), (9, 24, 38), (14, 26, 41)):
        if (row == y and x0 <= col < x1) or (row == y + 1 and x0 + 1 <= col < x1 - 1):
            return ("gold", 3)
    if fuji:
        snow = row < peak + 7 + (1 if col % 3 == 0 else 0) - (1 if col % 4 == 1 else 0)
        if snow:
            return ("porcelain", 2 if col < 14 else 1)
        return ("indigo", 3 if col < 14 else 2)
    for (x0, x1, y) in ((0, 7, 3), (8, 16, 6), (1, 11, 13), (18, 26, 16), (0, 6, 20)):
        if (row == y and x0 <= col < x1) or (row == y + 1 and x0 + 1 <= col < x1 - 1):
            return ("gold", 3)
    # gold leaf squares: every other square a shade warmer, no hard grid lines
    return ("gold", 2) if ((col // 5) + (row // 5)) % 2 else ("gold", 2.4)


def fusuma_panel():
    """후스마 장식 2x3: two sliding doors three blocks tall in black lacquer frames, painted as one picture across
    both (Mt Fuji and the red sun on gold leaf), round recessed handles; the backs are patterned karakami paper.
    Drawn from y 0 and moved down a block (furniture translation [0, 1, 0]), like the torii."""
    m = BModel("fusuma_panel", dy=-16)

    def art(c):
        if c.face == "north":
            col = math.floor(31 - c.p[0] - 1.5)
            if col >= 13:
                col -= 3                      # the two middle stiles hide 3 px of the picture's seam
            row = math.floor(46.5 - c.p[1])
            if 0 <= col < 26 and 0 <= row < 44:
                return _fusuma_art(col, row)
        if c.face == "south":
            u, v = c.i[0] % 4, c.i[1] % 4
            return ("paper", 2 if (u, v) in ((1, 1), (3, 3)) else 1) if (u + v) % 2 == 0 else ("cream", 2)
        return None
    m.group("frames")
    for x0 in (0, 16):
        m.box("frame", (x0, 1, 7), (x0 + 1.5, 48, 9), LACQ)
        m.box("frame", (x0 + 14.5, 1, 7), (x0 + 16, 48, 9), LACQ)
        m.box("frame", (x0 + 1.5, 46.5, 7), (x0 + 14.5, 48, 9), LACQ)
        m.box("frame", (x0 + 1.5, 1, 7), (x0 + 14.5, 2.5, 9), LACQ)
    m.group("paintings")
    for x0 in (0, 16):
        m.box("paper", (x0 + 1.5, 2.5, 7.5), (x0 + 14.5, 46.5, 8.5), Paint("gold", 2, decal=art, ao=0.5, rim=0.3))
        # the hikite: a round dark pull set into each door, on the side away from where they meet, at hand height
        hx = x0 + (3 if x0 == 16 else 11.5)
        m.box("hikite_rim", (hx - 0.25, 21.25, 7.25), (hx + 1.75, 23.75, 7.5), flat("brass", 1))
        m.box("hikite", (hx, 21.5, 7.125), (hx + 1.5, 23.5, 7.25), flat("rubber", 0, rim=0))
    m.group("feet")
    for x in (1, 27):
        m.box("foot", (x, 0, 4), (x + 4, 1, 12), LACQ)
    return display(m, (2, 3, 1), -16)


def hanging_scroll():
    """족자 (도코노마 족자) 1x2: a kakejiku hung on the wall: a cord from the hook, the top rod, brocade bands
    with gold strips, the two fūtai ribbons, an ink painting (the moon over misty peaks, a pine, the red
    seal) and the weighted bottom roller with its end knobs."""
    m = BModel("hanging_scroll", floor=None, planes=(("z>", 16),))
    gold_strip = flat("gold", 2, rim=0.3)

    def brocade(c):
        u, v = c.i[0], c.i[1]
        if u % 3 == 1 and v % 3 == 1:
            return ("gold", 1)                         # small gold motifs scattered in the weave
        return ("indigo", 1)
    band = Paint("indigo", 1, decal=brocade, rim=0.5)

    def ink(c):
        if c.face != "north":
            return None
        col, row = math.floor(12 - c.p[0]), math.floor(24 - c.p[1])      # 8 x 16, row 0 at the top
        if math.hypot(col + 0.5 - 5.5, row + 0.5 - 3.0) < 1.6:
            return ("cream", 3)                                   # the moon
        far = 9 + 1.4 * math.sin(col * 0.9 + 0.4)
        near = 12 + 1.2 * math.sin(col * 1.4 + 2.4)
        if row >= near:
            return ("millstone", 1 if row >= near + 2 else 2)     # the near ridge in dark ink
        if row >= far:
            return ("millstone", 3 if row < far + 1.5 else 4)     # far peaks fading into the mist
        if (col, row) in ((1, 4), (2, 5), (2, 6), (3, 7), (1, 5)):
            return ("bark", 0)                                    # a pine bough reaching in from the left
        if row in (3, 4) and col in (0, 2, 3) or (row == 6 and col in (3, 4)):
            return ("leaf_dark", 0)
        if (col, row) in ((6, 14), (6, 15)):
            return ("vermilion", 1)                               # the painter's seal
        if col == 7 and row in (1, 2, 4, 5, 6):
            return ("millstone", 0)                               # a column of calligraphy
        return ("paper", 1)
    m.group("cord")

    def cord(c):
        if c.face not in ("north", "south"):
            return ("raw", (0, 0, 0, 0))
        dx, y = abs(c.p[0] - 8), c.p[1]
        return ("crimson", 2) if abs((31.75 - y) * 1.3 - dx) < 0.55 else ("raw", (0, 0, 0, 0))
    m.box("cord", (4.5, 29.5, 15.75), (11.5, 32, 15.75), Paint("crimson", 2, decal=cord, light=False))
    m.group("scroll")
    m.box("hasso", (2.5, 28.5, 14.75), (13.5, 29.5, 16), DARK)
    m.box("ten", (3, 24.5, 15.25), (13, 28.5, 16), band)
    m.box("ichimonji", (3.5, 24, 15.125), (12.5, 24.5, 15.25), gold_strip)
    m.box("mount", (3, 6, 15.25), (13, 24.5, 16), Paint("celadon", 1, "weave", rim=0.4))
    m.box("honshi", (4, 8, 15), (12, 24, 15.25), Paint("paper", 1, decal=ink, ao=0.4, rim=0))
    m.box("ichimonji", (3.5, 7.5, 15.125), (12.5, 8, 15.25), gold_strip)
    m.box("chi", (3, 4.5, 15.25), (13, 6, 16), band)
    for x in (5, 10):
        m.box("futai", (x, 20.5, 14.875), (x + 1, 28.5, 15.125), band)
        m.box("futai_tip", (x, 20, 14.875), (x + 1, 20.5, 15.125), gold_strip)
    m.box("jiku", (2.5, 3, 14.75), (13.5, 4.5, 16), DARK)
    for x in (1.25, 13.5):
        m.box("jikusaki", (x, 2.75, 14.5), (x + 1.25, 4.75, 16), flat("cream", 2))
    return display(m, (1, 2, 1), 0)


def noren():
    """노렌: an indigo shop curtain in three strips on a bamboo rod, a white crest (a plum blossom) across the
    middle, a white wave band at the hem. Hangs from the top of its block (place it under a door lintel)."""
    m = BModel("noren", floor=None, planes=(("y>", 16),))
    uroko = [".....#.....",
             "....###....",
             "...#####...",
             "..#.....#..",
             ".###...###.",
             "#####.#####"]

    def dye(c):
        x, y = c.p[0], c.p[1]
        col = math.floor(13.5 - x) if c.face == "north" else math.floor(x - 2.5)
        row = math.floor(12.5 - y)
        if 0 <= row < 6 and 0 <= col < 11 and uroko[row][col] == "#":
            return ("porcelain", 2)                    # mitsu-uroko: three white scales
        yi = c.i[1]
        if yi in (2, 3):
            return ("porcelain", 1)                    # the white hem
        if yi == 5:
            return ("porcelain", 2) if c.i[0] % 4 != 3 else ("indigo", 2)
        if yi == 14:
            return ("indigo", 1)
        return ("indigo", 2 if yi < 13 else 3)
    m.group("rod")
    m.box("rod", (0, 15, 7.25), (16, 16, 8.75), Paint("bamboo", 2, "bamboo", axis="x", node=7, node_off=3))
    m.group("cloth")
    for x0 in (0.25, 5.5, 10.75):
        m.box("strip", (x0, 2, 7.75), (x0 + 5, 15, 8.25), Paint("indigo", 2, decal=dye, ao=0.5, rim=0.4))
    return display(m, (1, 1, 1), 0)


def bamboo_fence():
    """대나무 울타리 (켄닌지가키) 1x2: a screen of split bamboo slats, sun-bleached to straw, held between pairs of
    green bamboo rails (front and back) that black palm cord ties to it, a split-bamboo cap on top. As tall as a
    real kenninji fence (1.75 blocks: it screens a garden at eye level). The slats and rails run edge to edge, so
    fences side by side make one long fence."""
    m = BModel("bamboo_fence")
    m.group("slats")
    for i in range(8):
        # split culms alternate their round back and their flat face, so every other one stands forward
        z0 = 7.0 if i % 2 else 7.5
        slat = Paint("straw", 2, "bamboo", axis="y", node=7, node_off=(i * 3) % 7, ridge=False, ao=0.7, seed=i)
        m.box("slat", (2 * i, 0, z0), (2 * i + 2, 26.5, z0 + 1.5), slat)
    m.group("rails")
    rail = Paint("bamboo", 2, "bamboo", axis="x", node=9, node_off=4)
    rails = (3, 12.5, 21.5)
    for y in rails:
        m.box("rail", (0, y, 5.75), (16, y + 1.5, 7.0), rail)
        m.box("rail", (0, y, 9.0), (16, y + 1.5, 10.25), rail(node_off=1))
    m.group("cap")
    m.box("cap", (0, 26.5, 6.5), (16, 27.5, 9.5), Paint("bamboo", 1, "bamboo", axis="x", node=8, node_off=2))
    m.box("cap_top", (0, 27.5, 7.25), (16, 28, 8.75), Paint("bamboo", 2, "bamboo", axis="x", node=8, node_off=6))
    m.group("cords")
    for y in rails:
        for x in (2.5, 12.5):
            m.box("cord", (x, y - 0.5, 5.5), (x + 1, y + 2, 5.75), CORD)
            m.box("cord", (x, y - 0.5, 10.25), (x + 1, y + 2, 10.5), CORD)
    for x in (2.5, 12.5):
        m.box("cord", (x, 26.25, 6.25), (x + 1, 27.75, 6.5), CORD)
        m.box("cord", (x, 26.25, 9.5), (x + 1, 27.75, 9.75), CORD)
    return display(m, (1, 2, 1), 0)


# ================================================================================================
# 조명 (진짜 빛: furniture.light; the paper / fire flickers)
# ================================================================================================

def andon():
    """등롱 (안돈): a floor lamp: a dark wooden frame on four legs, washi paper glowing from the flame inside
    (the inner cross bar shows as a soft shadow, the light flickers now and then), a carrying handle on top."""
    m = BModel("andon")
    m.group("frame")
    for (x, z) in ((3.5, 3.5), (11.5, 3.5), (3.5, 11.5), (11.5, 11.5)):
        m.box("post", (x, 0, z), (x + 1, 14, z + 1), DARK)
    for y in (1.5, 13):
        m.box("rail", (4.5, y, 3.5), (11.5, y + 1, 4.5), DARK)
        m.box("rail", (4.5, y, 11.5), (11.5, y + 1, 12.5), DARK)
        m.box("rail", (3.5, y, 4.5), (4.5, y + 1, 11.5), DARK)
        m.box("rail", (11.5, y, 4.5), (12.5, y + 1, 11.5), DARK)
    m.box("handle", (4.5, 15.25, 7.5), (11.5, 16, 8.5), DARK)
    for x in (4.5, 10.5):
        m.box("handle_post", (x, 14, 7.5), (x + 1, 15.25, 8.5), DARK)
    m.group("light")

    def glow(c):
        y = c.p[1]
        v = 1.75 - max(0.0, abs(y - 7.5) - 3.5) * 0.35 + flicker(c.frame, 0)
        if c.face in SIDE_FACES and abs(y - 8.5) < 0.5:
            v -= 1.0                               # the inner frame's cross bar, a soft shadow
        if c.face in ("up", "down"):
            v = 2.4 + flicker(c.frame, 0)
        return ("washi_lit", v)
    m.box("paper", (4.5, 2.5, 4.5), (11.5, 13, 11.5), Paint("washi_lit", 2, glow=True, light=False, noise=0,
                                                              decal=glow, frames=12, frametime=3))
    return display(m, (1, 1, 1), 0)


def stone_lantern():
    """석등 (가스가 도로) 1x2: a granite garden lantern in its six classic tiers: base, pillar with a carved band,
    the platform, the fire box (posts, open front and back windows with the flame inside, a crescent moon and a
    sun cut through its sides), the wide roof with curled-up corners, the jewel on top. Moss in the hollows."""
    m = BModel("stone_lantern")
    st = moss_on(STONE)
    m.group("base")
    poly(m, "kiso", (8, 8), 14, 4, 0, 2, st)
    poly(m, "kiso_lotus", (8, 8), 10, 3, 2, 3, st, steps=1)
    m.group("pillar")
    cyl(m, "sao", (8, 8), 5, 3, 7, STONE)
    cyl(m, "fushi", (8, 8), 6, 7, 8, STONE_LIGHT)
    cyl(m, "sao", (8, 8), 5, 8, 11.5, STONE)
    m.group("platform")
    poly(m, "chudai_neck", (8, 8), 8, 2, 11.5, 12.5, STONE_DARK, steps=1)
    poly(m, "chudai", (8, 8), 12, 3, 12.5, 14.5, st)
    m.group("firebox")
    for (x, z) in ((4, 4), (10.5, 4), (4, 10.5), (10.5, 10.5)):
        m.box("post", (x, 14.5, z), (x + 1.5, 20.5, z + 1.5), STONE)
    moon = [".##.", "##..", "#...", "##..", ".##."]          # rows y 19..15, columns z 6..9
    sun = ["....", ".##.", "####", "####", ".##."]

    def carving(c):
        if c.face not in ("east", "west"):
            return None
        col, row = c.i[2] - 6, 19 - c.i[1]
        if not (0 <= col < 4 and 0 <= row < 5):
            return None
        mask = moon if c.face == "west" else sun
        if c.face == "west":
            col = 3 - col                 # the crescent opens towards the front
        return ("glow_amber", 1) if mask[row][col] == "#" else None
    side = STONE(decal=carving)
    m.box("wall", (4.5, 14.5, 5.5), (5.5, 20.5, 10.5), side)
    m.box("wall", (10.5, 14.5, 5.5), (11.5, 20.5, 10.5), side)
    for z0 in (4.5, 10.5):
        m.box("sill", (5.5, 14.5, z0), (10.5, 15.5, z0 + 1), STONE)
        m.box("lintel", (5.5, 19.5, z0), (10.5, 20.5, z0 + 1), STONE)
    m.group("light")

    def fire(c):
        dx, y = abs(c.p[0] - 8), c.p[1]
        return ("glow_amber", 2.2 - dx * 0.45 - abs(y - 17.0) * 0.35 + flicker(c.frame, 3) * 1.6)
    m.box("fire", (5.5, 15.5, 6), (10.5, 19.5, 10), Paint("glow_amber", 2, glow=True, light=False, noise=0,
                                                            decal=fire, frames=12, frametime=3))
    m.group("roof")
    m.box("roof_under", (4, 20.5, 4), (12, 21, 12), STONE_DARK)
    poly(m, "kasa", (8, 8), 16, 4.5, 21, 22, STONE_DARK(rim=1.4))
    poly(m, "kasa", (8, 8), 13, 3.5, 22, 23, st)
    poly(m, "kasa", (8, 8), 10, 2.5, 23, 24, st, steps=1)
    poly(m, "kasa", (8, 8), 7, 1.5, 24, 25, st, steps=1)
    for (x, z) in ((1, 2.25), (13.5, 2.25), (1, 12.25), (13.5, 12.25)):      # warabite: the corners curl up
        m.box("warabite", (x, 22, z), (x + 1.5, 23, z + 1.5), STONE_DARK)
    m.group("jewel")
    cyl(m, "ukebana", (8, 8), 5, 25, 25.75, STONE_LIGHT)
    cyl(m, "hoju", (8, 8), 4, 25.75, 27.75, STONE_LIGHT)
    cyl(m, "hoju", (8, 8), 2, 27.75, 29, STONE_LIGHT)
    m.box("tip", (7.5, 29, 7.5), (8.5, 29.75, 8.5), STONE_LIGHT)
    return display(m, (1, 2, 1), 0)


def chochin():
    """제등 (초롱): a red paper lantern on bamboo ribs (the ribs show as fine lines), black lacquered caps, a
    black band and a white crest front and back, a red tassel; lit by its light block, its paper is painted
    brightest round the middle. Hangs from the top of its block; 11 px across (a large izakaya lantern)."""
    m = BModel("chochin", floor=None, planes=(("y>", 16),))
    m.group("lantern")
    m.box("hook", (7.5, 14.75, 7.5), (8.5, 16, 8.5), IRON)
    cyl(m, "cap", (8, 8), 5, 14, 14.75, LACQ)
    cyl(m, "cap_rim", (8, 8), 7, 13.5, 14, LACQ)

    def paper(c):
        y = c.p[1]
        if c.face in ("up", "down"):
            return ("vermilion", 2)
        if c.face in ("north", "south"):
            dx = c.p[0] - 8
            r = math.hypot(dx, y - 7.75)
            if r < 1.0:
                return ("vermilion", 1)
            if r < 2.9:
                return ("porcelain", 2 if r < 1.9 else 1)
        rib = c.i[1] % 2 == 0
        return ("vermilion", 3 - abs(y - 7.75) * 0.22 - (0.8 if rib else 0))
    skin = Paint("vermilion", 2, decal=paper, ao=0.6)
    for (d, lo, hi) in ((7, 13, 13.5), (9, 12, 13), (10, 10.5, 12), (11, 5, 10.5), (10, 3.5, 5), (9, 2.5, 3.5), (7, 2, 2.5)):
        cyl(m, "paper", (8, 8), d, lo, hi, skin)
    cyl(m, "cap_rim", (8, 8), 7, 1.5, 2, LACQ)
    cyl(m, "bottom", (8, 8), 5, 1.25, 1.5, LACQ)
    m.box("tassel", (7.5, 0, 7.5), (8.5, 1.25, 8.5), flat("vermilion", 1))
    return display(m, (1, 1, 1), 0)


# ================================================================================================
# 장식품 · 화분
# ================================================================================================

def bonsai():
    """분재: a pine trained into an S-curved trunk in a shallow glazed pot, its needles in flat cloud pads that
    float apart on the branches, moss and a stone on the soil."""
    m = BModel("bonsai", shift=(0.5, 0, -0.25))      # centred in its block (the pads reach further left)
    m.group("pot")
    m.box("pot", (2, 1, 4), (14, 3.5, 12), Paint("indigo", 1, noise=0.03))
    m.box("pot_lip", (1.5, 3.5, 3.5), (14.5, 4.25, 12.5), Paint("indigo", 2, noise=0.02))
    for (x, z) in ((2.5, 4.5), (12.5, 4.5), (2.5, 10.5), (12.5, 10.5)):
        m.box("foot", (x, 0, z), (x + 1, 1, z + 1), flat("indigo", 0))
    m.box("soil", (2.5, 4.25, 4.5), (13.5, 4.5, 11.5), moss_on(SOIL, amount=0.45, cell=2))
    m.box("stone", (10, 4.5, 8.5), (12.5, 5.75, 10.5), STONE_LIGHT)
    m.group("trunk")
    for (a, b) in (((6.5, 4.5, 7), (9, 6, 9.5)),         # root flare
                   ((5.5, 6, 7.25), (7.5, 8.5, 9)),       # S: lean left
                   ((6.5, 8.5, 7.25), (8.5, 10.5, 9)),    # back right
                   ((7.5, 10.5, 7.25), (9.25, 12.5, 8.75)),
                   ((6.75, 12.5, 7.5), (8.5, 14, 8.75))):  # and left again to the apex
        m.box("trunk", a, b, BARK)
    m.box("branch", (2.5, 9, 7.75), (5.5, 9.75, 8.75), BARK)
    m.box("branch", (9.25, 11.5, 7.75), (12.5, 12.25, 8.75), BARK)
    m.box("branch", (8, 8.5, 9), (9, 9.25, 11), BARK)
    m.group("foliage")

    def needles(c):
        r = h3(c.i[0], c.i[1], c.i[2], 7)
        if c.face == "up":
            return ("leaf_dark", 3 if r < 0.35 else 2)
        if c.face == "down":
            return ("leaf_dark", 0)
        return ("leaf_dark", 2 if r < 0.3 else 1)
    pad = Paint("leaf_dark", 2, decal=needles)
    for (cx, y, cz, w, d) in ((3.5, 9.75, 8, 6, 5), (11.5, 12.25, 7.5, 6, 5), (7.5, 14, 8, 8, 6), (9, 9.25, 11.5, 4, 3)):
        m.box("pad", (cx - w / 2, y, cz - d / 2), (cx + w / 2, y + 1.25, cz + d / 2), pad)
        m.box("pad_top", (cx - w / 2 + 1, y + 1.25, cz - d / 2 + 1), (cx + w / 2 - 1, y + 2, cz + d / 2 - 1), pad)
    return display(m, (1, 1, 1), 0)


def ikebana():
    """이케바나 꽃꽂이: a shallow celadon suiban with water, a tall camellia branch (heaven), a shorter one leaning
    the other way (man), low leaves in front (earth): red and white blooms with gold stamens, glossy leaves.
    The dish and the blooms are a little larger than life, so the arrangement reads next to a player."""
    m = BModel("ikebana")
    m.group("dish")

    def water(c):
        if c.face == "up" and math.hypot(c.p[0] - 8, c.p[2] - 8) < 5.6:
            return ("water", 2 if (c.i[0] + c.i[2]) % 5 else 3)
        return None
    cyl(m, "dish", (8, 8), 14, 0, 2, CELADON(decal=water))
    m.group("branches")
    tw = Paint("bark", 1, noise=0)
    # shin rises and leans left (+x, the viewer's left), soe leans right
    for (a, b) in (((7.5, 2, 7.5), (8.75, 6, 8.75)), ((8, 6, 7.5), (9.25, 10, 8.75)), ((8.75, 10, 7.5), (10, 13, 8.75)),
                   ((9.75, 13, 7.5), (10.75, 14.5, 8.5)),
                   ((6.75, 2, 8), (7.75, 5, 9)), ((5.5, 5, 8), (6.75, 7.5, 9)), ((4, 7.5, 8), (5.5, 8.5, 9))):
        m.box("stem", a, b, tw)
    red = Paint("vermilion", 3, noise=0, rim=0.6)
    white = Paint("porcelain", 2, noise=0, rim=0.6)
    stamen = flat("gold", 3, light=False)
    for (x, y, z, p) in ((10.25, 14.5, 8, red), (7, 10.5, 5.5, red), (3, 8.5, 8.5, white), (11.5, 6.5, 10.5, red)):
        # a camellia: four rounded petals round a raised cup, the gold stamens in the middle
        m.box("bloom", (x - 2, y, z - 1.5), (x + 2, y + 1, z + 1.5), p(base=p.base - 1))
        m.box("bloom", (x - 1.5, y, z - 2), (x + 1.5, y + 1, z - 1.5), p(base=p.base - 1))
        m.box("bloom", (x - 1.5, y, z + 1.5), (x + 1.5, y + 1, z + 2), p(base=p.base - 1))
        m.box("bloom_top", (x - 1.5, y + 1, z - 1.5), (x + 1.5, y + 1.75, z + 1.5), p)
        m.box("stamen", (x - 0.5, y + 1.75, z - 0.5), (x + 0.5, y + 2.25, z + 0.5), stamen)
    leaf = Paint("leaf_dark", 2, noise=0, rim=0.8)
    for (x, y, z, w, d) in ((9.75, 4, 5, 3, 2), (4.5, 3.5, 9.5, 2, 3), (10.5, 9, 9.5, 2.5, 2), (5.5, 9.5, 6, 2.5, 2),
                            (10.75, 12, 5.5, 2, 2), (3.5, 2.5, 5.5, 3, 2), (10, 2.5, 11, 3, 2), (12.5, 2.5, 7, 2, 2.5)):
        m.box("leaf", (x, y, z), (x + w, y + 0.5, z + d), leaf)
    return display(m, (1, 1, 1), 0)


def _leaf_spray(rows, x, y, z, axis):
    """Decal of a leaf spray on a flat plane running along `axis` from (x, z), its top row at y (cut out)."""
    def decal(c):
        u = (c.p[0] - x) if axis == "x" else (c.p[2] - z)
        col, row = math.floor(u), math.floor(y - c.p[1])
        if 0 <= row < len(rows) and 0 <= col < len(rows[row]) and rows[row][col] == "#":
            tip = row >= len(rows) - 2
            return ("leaf", 2 if tip else 3) if c.face in ("north", "east") else ("leaf", 1 if tip else 2)
        return ("raw", (0, 0, 0, 0))
    return decal


def bamboo_pot():
    """대나무 화분 1x2: a hinoki planter with black iron bands, white pebbles, three bamboo culms with nodes
    and drooping sprays of leaves."""
    m = BModel("bamboo_pot")
    m.group("planter")
    m.box("planter", (3, 0, 3), (13, 6, 13), Paint("hinoki", 2, "planks", axis="y", width=3))
    for y in (1, 4.5):
        m.box("band", (2.75, y, 2.75), (13.25, y + 0.75, 13.25), flat("iron", 1, rim=0.6))
    m.box("rim", (2.75, 6, 2.75), (13.25, 6.5, 13.25), HINOKI)

    def pebbles(c):
        r = h3(c.i[0], 0, c.i[2], 3)
        return ("porcelain", 2 if r < 0.35 else 1) if r < 0.75 else ("millstone", 3)
    m.box("pebbles", (3.5, 6.5, 3.5), (12.5, 6.75, 12.5), Paint("porcelain", 1, decal=pebbles))
    m.group("culms")
    for (x, z, h, off) in ((5, 7, 31, 1), (9, 5, 26, 3), (8.5, 10, 22, 0)):
        m.box("culm", (x, 6.75, z), (x + 1.5, h, z + 1.5), Paint("bamboo", 3, "bamboo", axis="y", node=5, node_off=off))
    m.group("leaves")
    leaf_rows = ["...##...",
                 "..####..",
                 ".##.###.",
                 "##...###",
                 "#.....##"]
    # the sprays balanced round the planter, so the piece stays centred in its block
    for (x, y, z, axis) in ((0.5, 30, 7.75, "x"), (7.0, 31.5, 7.25, "z"), (8.5, 25, 5.75, "x"), (4.5, 25.5, 1, "z"),
                            (8, 21, 10.75, "x"), (5.5, 18.5, 7.5, "z")):
        p = Paint("leaf", 2, decal=_leaf_spray(leaf_rows, x, y, z, axis), ao=0.3)
        if axis == "x":
            m.box("leaf", (x, y - 5, z), (x + 8, y, z), p)
        else:
            m.box("leaf", (x, y - 5, z), (x, y, z + 8), p)
    return display(m, (1, 2, 1), 0)


def sakura_pot():
    """벚꽃 화분 1x2: a small cherry tree in a round glazed pot: a gnarled trunk that splits into three
    branches, each ending in a cloud of blossom (five-petalled flowers, lighter on top), petals on the soil."""
    m = BModel("sakura_pot")
    m.group("pot")
    cyl(m, "pot", (8, 8), 9, 0.5, 5, Paint("onggi", 2, noise=0.02))
    cyl(m, "pot_foot", (8, 8), 7, 0, 0.5, flat("onggi", 1))
    cyl(m, "rim", (8, 8), 10, 5, 6, Paint("onggi", 3, noise=0.02))

    def petals(c):
        if c.face == "up" and h3(c.i[0], 0, c.i[2], 9) < 0.18:
            return ("sakura", 2)
        return None
    m.box("soil", (4.5, 6, 4.5), (11.5, 6.25, 11.5), SOIL(decal=petals))
    m.group("tree")
    for (a, b) in (((7, 6.25, 7), (9.5, 9, 9.5)), ((7.5, 9, 7.25), (9.5, 12.5, 9.25)), ((7, 12.5, 7.5), (9, 14.5, 9))):
        m.box("trunk", a, b, BARK)
    for (a, b) in (((3.5, 14, 7.75), (7, 15, 8.75)), ((2.5, 15, 7.75), (3.5, 17, 8.75)),       # left
                   ((9, 14.5, 7.5), (12.5, 15.5, 8.5)), ((12.5, 15.5, 7.5), (13.5, 18, 8.5)),  # right
                   ((7.75, 14.5, 9), (8.75, 15.5, 11.5)), ((7.5, 14.5, 7.75), (8.5, 21, 8.75))):  # back, up
        m.box("branch", a, b, BARK)
    m.group("blossom")

    def bloom(c):
        x, y, z = c.i
        r = h3(x, y, z, 21)
        flower = (x + 2 * y + 3 * z) % 5 == 0
        if c.face == "down":
            return ("sakura", 0 if r < 0.5 else 1)
        if flower:
            return ("pink", 2) if r < 0.4 else ("sakura", 3)
        if c.face == "up":
            return ("sakura", 3 if r < 0.7 else 2)
        return ("sakura", 2 if r < 0.75 else 1)
    pink = Paint("sakura", 2, decal=bloom, rim=0.7)
    for (c, w, h, d) in (((3, 16, 8), 6, 4, 6), ((13, 17.5, 8), 6, 4, 6), ((8, 20.5, 8), 8, 5, 7),
                         ((8.5, 15.25, 11.75), 5, 3.5, 4), ((10.5, 23.5, 9.25), 4, 2.5, 4), ((5.25, 20.25, 6.75), 4, 3, 4)):
        puff(m, "blossom", c, w, h, d, pink)
    return display(m, (1, 2, 1), 0)


# ================================================================================================
# 다구 · 소품
# ================================================================================================

def tea_set():
    """다기 세트: a hinoki tray with a red-clay kyūsu (the straight side handle, a short spout) and three tall
    celadon yunomi of green tea. A little larger than life (the pot 8 px across, the cups 4 high), so it reads
    next to a player."""
    m = BModel("tea_set")
    m.group("tray")
    m.box("tray", (0.5, 0, 1.5), (15.5, 0.75, 14.5), Paint("hinoki", 2, "planks", axis="x", width=3))
    for (a, b) in (((0.5, 0.75, 1.5), (15.5, 1.5, 2.25)), ((0.5, 0.75, 13.75), (15.5, 1.5, 14.5)),
                   ((0.5, 0.75, 2.25), (1.25, 1.5, 13.75)), ((14.75, 0.75, 2.25), (15.5, 1.5, 13.75))):
        m.box("tray_rim", a, b, HINOKI)
    m.group("kyusu")
    clay = Paint("terracotta", 1, "speckle", rim=0.8)
    cyl(m, "pot", (6, 8), 6, 0.75, 1.75, clay)
    cyl(m, "pot", (6, 8), 8, 1.75, 5, clay)
    cyl(m, "pot", (6, 8), 6, 5, 6, clay)
    cyl(m, "lid", (6, 8), 4, 6, 6.75, flat("terracotta", 2))
    m.box("knob", (5.5, 6.75, 7.5), (6.5, 7.75, 8.5), flat("terracotta", 0))
    m.box("spout", (0.75, 2.5, 7.25), (2, 4, 8.75), clay)
    m.box("spout_tip", (0.25, 3.75, 7.5), (1.25, 5.25, 8.5), clay)
    m.box("handle", (5.25, 3, 2.25), (6.75, 4.5, 4), Paint("terracotta", 1, "grain", axis="z"))
    m.group("cups")
    for (x, z) in ((13, 3.75), (12.5, 8), (13, 12.25)):
        cup(m, "cup", (x, z), 0.75, 4.75, CELADON(decal=tea_top(x, z)))
    return display(m, (1, 1, 1), 0)


def tea_kettle():
    """화로와 찻주전자: a cast-iron tetsubin with hobnail (arare) bumps, a bronze knob and an arched handle,
    on an iron trivet over glowing charcoal (it flickers) in a blue-and-white porcelain brazier."""
    m = BModel("tea_kettle")
    m.group("brazier")

    def blue_white(c):
        if c.face == "up":
            return None
        y = c.p[1]
        ang = math.atan2(c.p[2] - 8, c.p[0] - 8)
        if abs(y - (3.2 + 0.9 * math.sin(ang * 6))) < 0.6:
            return ("indigo", 2)
        if 4.6 < y < 5.4:
            return ("indigo", 3)
        if y < 1.4:
            return ("indigo", 2)
        return ("porcelain", 2)
    cyl(m, "hibachi", (8, 8), 13, 0.5, 6, Paint("porcelain", 2, decal=blue_white))
    cyl(m, "hibachi_foot", (8, 8), 10, 0, 0.5, flat("indigo", 1))
    cyl(m, "hibachi_rim", (8, 8), 13, 6, 6.5, flat("indigo", 2))

    def ash(c):
        return ("millstone", 4 if h3(c.i[0], 0, c.i[2], 11) < 0.6 else 3)
    m.box("ash", (3.5, 6.5, 3.5), (12.5, 6.75, 12.5), Paint("millstone", 4, decal=ash))

    def embers(c):
        r = h3(c.i[0], c.i[1], c.i[2], 13)
        return ("fire", 2 + (1 if (c.frame + int(r * 12)) % 12 < 4 else 0) - (1 if r < 0.3 else 0))
    for (x, z) in ((4.25, 4.25), (10, 10.25), (4.5, 10), (10.25, 4.5), (7.25, 3.75)):
        m.box("charcoal", (x, 6.75, z), (x + 1.5, 7.5, z + 1.5),
              Paint("fire", 2, glow=True, light=False, noise=0, decal=embers, frames=12, frametime=4))
    gotoku = flat("iron", 1)
    m.box("gotoku", (5, 6.75, 7.5), (11, 7.75, 8.5), gotoku)
    m.box("gotoku", (7.5, 6.75, 5), (8.5, 7.75, 7.5), gotoku)
    m.box("gotoku", (7.5, 6.75, 8.5), (8.5, 7.75, 11), gotoku)
    m.group("tetsubin")

    def arare(c):
        if c.face in ("up", "down"):
            return None
        u = c.i[0] if c.face in ("north", "south") else c.i[2]
        if (u + c.i[1]) % 2 == 0 and 8.5 < c.p[1] < 11.5:
            return ("iron", 3)
        return None
    iron = Paint("iron", 2, decal=arare, rim=0.8)
    cyl(m, "kettle", (8, 8), 7, 7.75, 8.5, iron)
    cyl(m, "kettle", (8, 8), 9, 8.5, 11.5, iron)
    cyl(m, "kettle", (8, 8), 7, 11.5, 12.25, iron)
    cyl(m, "lid", (8, 8), 4, 12.25, 12.75, flat("iron", 3))
    m.box("knob", (7.5, 12.75, 7.5), (8.5, 13.5, 8.5), BRASS)
    m.box("spout", (2.5, 9.5, 7.5), (3.5, 11, 8.5), iron)
    m.box("spout_tip", (2, 10.5, 7.5), (2.5, 12, 8.5), iron)
    handle = flat("iron", 1, rim=0.5)
    for x in (3, 12.5):
        m.box("lug", (x, 11.5, 7.5), (x + 0.5, 12.75, 8.5), handle)
        m.box("handle", (x, 12.75, 7.75), (x + 0.5, 15, 8.25), handle)
    m.box("handle", (3, 15, 7.75), (13, 15.5, 8.25), handle)
    return display(m, (1, 1, 1), 0)


def sake_set():
    """사케 병과 잔: two white tokkuri painted with indigo bands and two wide ochoko cups with the blue bull's-eye
    (janome) inside, on a red lacquer tray. A little larger than life, so it reads next to a player."""
    m = BModel("sake_set")
    m.group("tray")
    m.box("tray", (1, 0, 2), (15, 0.75, 14), LACQ_RED)
    for (a, b) in (((1, 0.75, 2), (15, 1.5, 2.5)), ((1, 0.75, 13.5), (15, 1.5, 14)),
                   ((1, 0.75, 2.5), (1.5, 1.5, 13.5)), ((14.5, 0.75, 2.5), (15, 1.5, 13.5))):
        m.box("tray_rim", a, b, LACQ_RED)
    m.group("tokkuri")
    for (x, z, h) in ((5, 10, 0), (11, 6, 0.5)):
        def brush(c, h=h):
            if c.face in ("up", "down"):
                return None
            yi = c.i[1]
            if yi in (2, 4) and c.p[1] < 5.5 + h:
                return ("indigo", 2)                   # two brushed bands round the belly
            if yi == 3 and (c.i[0] + c.i[2]) % 2 == 0:
                return ("indigo", 3)                   # and a row of dots between them
            return None

        def mouth(c, x=x, z=z):
            if c.face == "up" and abs(c.p[0] - x) < 0.6 and abs(c.p[2] - z) < 0.6:
                return ("porcelain", 0)
            return None
        white = Paint("porcelain", 2, noise=0, decal=brush, rim=0.8)
        cyl(m, "bottle", (x, z), 4, 0.75, 1.25, white)
        cyl(m, "bottle", (x, z), 6, 1.25, 5.5 + h, white)
        cyl(m, "bottle", (x, z), 4, 5.5 + h, 6.75 + h, white)
        cyl(m, "neck", (x, z), 2, 6.75 + h, 9.5 + h, white)
        cyl(m, "lip", (x, z), 4, 9.5 + h, 10 + h, Paint("porcelain", 2, noise=0, rim=0.8, decal=mouth))
    m.group("cups")
    for (x, z) in ((12, 11), (4, 5)):
        def janome(c, x=x, z=z):
            if c.face == "up" and math.hypot(c.p[0] - x, c.p[2] - z) < 1.1:
                return ("indigo", 2)
            return None
        cup4(m, "ochoko", (x, z), 0.75, 2.75, Paint("porcelain", 2, noise=0, decal=janome))
    return display(m, (1, 1, 1), 0)


def taiko_drum():
    """다이코 북 1x2: a barrel drum of deep-red lacquered wood lying on a stand, cream cowhide heads tacked on with
    a ring of iron studs and painted with the three-comma tomoe, brass carrying rings, two sticks."""
    m = BModel("taiko_drum", shift=(0, 0, 0.875))    # centred in its block with the sticks in front
    cy = 14

    def skin(c):
        if c.face not in ("north", "south"):
            return None
        x, y = c.p[0] - 8, c.p[1] - cy
        dx, dy = x / 6.5, y / 6.5
        r = math.hypot(dx, dy)
        if r > 0.8:
            stud = int((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * 16 + 0.5) % 2 == 0
            return ("brass", 3) if stud and r > 0.88 else ("leather", 1)     # the laced rim and its studs
        if 0.52 < r < 0.68:
            return ("vermilion", 1)                    # the crest: a ring round three comma heads
        for k in range(3):
            a = math.radians(90 + 120 * k) * (1 if c.face == "north" else -1)
            if math.hypot(dx - 0.25 * math.cos(a), dy - 0.25 * math.sin(a)) < 0.17:
                return ("vermilion", 1)
            if math.hypot(dx - 0.37 * math.cos(a - 0.6), dy - 0.37 * math.sin(a - 0.6)) < 0.09:
                return ("vermilion", 1)
        return ("cream", 2 if dx - dy < 0.2 else 1)
    body = Paint("crimson", 2, "planks", axis="z", width=3, rim=0.8)
    m.group("drum")
    cyl(m, "body", (8, cy), 13, 3, 4, body, axis="z")
    cyl(m, "body", (8, cy), 14, 4, 12, body, axis="z")
    cyl(m, "body", (8, cy), 13, 12, 13, body, axis="z")
    cyl(m, "head", (8, cy), 13, 2.5, 3, Paint("cream", 2, decal=skin, noise=0), axis="z")
    cyl(m, "head", (8, cy), 13, 13, 13.5, Paint("cream", 2, decal=skin, noise=0), axis="z")
    for z in (3.25, 12.25):
        cyl(m, "studs", (8, cy), 13.5, z, z + 0.5, flat("iron", 1), axis="z")
    for x in (0.5, 15):
        m.box("kan", (x, cy - 0.75, 7.25), (x + 0.5, cy + 0.75, 8.75), BRASS)
    m.group("stand")
    for x in (0, 14.5):
        m.box("foot", (x, 0, 2), (x + 1.5, 1.5, 14), DARK)
        m.box("leg", (x, 1.5, 7.25), (x + 1.5, 9.5, 8.75), DARK)
        m.box("brace", (x, 4.5, 4), (x + 1.5, 5.5, 7.25), DARK)
        m.box("brace", (x, 4.5, 8.75), (x + 1.5, 5.5, 12), DARK)
    m.box("cradle", (1.5, 6, 7), (14.5, 7, 9), DARK)
    m.group("sticks")
    stick = Paint("hinoki", 3, "grain", axis="z", noise=0)
    m.box("bachi", (2.5, 0, 0.25), (13.5, 0.75, 1.0), stick(axis="x"))
    m.box("bachi", (3.0, 0, 1.25), (14, 0.75, 2.0), stick(axis="x"))
    return display(m, (1, 1.4, 1), 0)


def maneki_neko():
    """마네키네코: a white cat sitting on a red cushion, its right paw raised to beckon: a big head, eyes closed in
    a smile, rosy cheeks, a small pink nose over an ω mouth, whiskers running round the cheeks, pink inside its
    ears, a red collar with a golden bell, a gold koban coin held in its other paw, calico patches on its ears and
    back."""
    m = BModel("maneki_neko")
    # the face, 12 px wide (column 0 at the viewer's left, x 14 -> 13), rows from y 14 down
    face = ["............",   # y 13
            "..KK....KK..",   # y 12  eyes closed in a smile
            ".K..K..K..K.",   # y 11
            ".....PP.....",   # y 10  the nose
            ".BB......BB.",   # y 9   rosy cheeks
            "...K.KK.K...",   # y 8   the mouth: ω
            "....K..K...."]   # y 7
    colours = {"K": ("rubber", 0), "P": ("pink", 1), "B": ("pink", 3)}
    white = Paint("feather", 2, noise=0, rim=0.8)

    def calico(c):
        """Orange and black patches on the ears and the back (never on the face)."""
        x, y, z = c.p
        if c.face == "down":
            return None
        for (cx, cy, cz, r, ramp_name, step) in ((11, 14.5, 9.5, 2.8, "fox", 2), (5, 14.5, 9.5, 2.6, "rubber", 1),
                                                  (10, 6, 12.5, 3.0, "fox", 2), (5, 4, 11, 2.4, "rubber", 1),
                                                  (11.5, 3, 13.5, 1.6, "rubber", 1)):
            if (x - cx) ** 2 + ((y - cy) * 1.2) ** 2 + (z - cz) ** 2 < r * r:
                return (ramp_name, step)
        return None
    fur = white(decal=calico)
    m.group("cushion")
    cloth = Paint("crimson", 2, "weave", rim=0.8)
    m.box("cushion", (1, 0, 1), (15, 1.25, 15), cloth)

    def seam(c):
        if c.face == "up" and 1 < min(c.p[0] - 1, 15 - c.p[0], c.p[2] - 1, 15 - c.p[2]) < 2:
            return ("crimson", 3)
        return None
    m.box("cushion", (1.5, 1.25, 1.5), (14.5, 2, 14.5), cloth(decal=seam))
    tassel = Paint("gold", 2, noise=0, rim=0.5)
    for (x, z) in ((0.5, 0.5), (14.5, 0.5), (0.5, 14.5), (14.5, 14.5)):
        m.box("tassel", (x + 0.125, 0.25, z + 0.125), (x + 0.875, 1.5, z + 0.875), tassel)
    m.group("body")
    m.box("body", (3.5, 2, 4.5), (12.5, 6.25, 12.5), fur)
    m.box("collar", (3.25, 6.25, 4.25), (12.75, 7, 12.75), flat("vermilion", 2, rim=0.5))
    m.box("tail", (9, 2, 12.5), (12.5, 3.5, 14), fur)
    m.box("tail", (11, 3.5, 12.5), (12.5, 6, 14), fur)
    m.group("head")
    # the face is a picture: flat colours, no rims or occlusion across it
    m.box("head", (2, 7, 3), (14, 14, 11), fur,
          faces={"north": Paint("feather", 2, noise=0, light=False, decal=picture(face, colours, 14, 14))})

    def ear_inside(c):
        if c.face == "north" and c.p[1] < 15 and math.floor(c.p[0]) in (4, 11):
            return ("pink", 2)
        return calico(c)
    ear = white(decal=ear_inside)
    for (x0, tip) in ((3, 4), (10, 11)):
        m.box("ear", (x0, 14, 5), (x0 + 3, 15, 7.5), ear)
        m.box("ear", (tip, 15, 5.5), (tip + 1, 16, 7), ear)
    m.group("paws")
    # the raised right paw: the arm up beside the head, the paw bent forward at the wrist, its pink pad to the front
    m.box("arm", (12.5, 4, 5.5), (14.5, 7, 8), white)
    m.box("arm", (14, 7, 5), (16, 11.5, 7.5), white)

    def pad(c):
        if c.face == "north" and math.floor(c.p[0]) in (14, 15) and c.p[1] < 13.5:
            return ("pink", 2)
        return None
    m.box("paw_up", (13.75, 11.5, 4), (16, 14.5, 6.5), white(decal=pad))

    # the other paw holds a gold koban coin against the chest: an oval with hammered lines
    def koban(c):
        if c.face == "north" and math.floor(c.p[0]) == 4 and 3 <= c.p[1] < 5:
            return ("gold", 0)                        # its stamped mark
        if c.face in ("north", "east", "west") and c.i[1] % 2 == 0:
            return ("gold", 2)                        # hammered lines across it
        return None
    coin = Paint("gold", 3, noise=0, decal=koban, rim=0.6)
    m.box("koban", (2, 2.5, 3.75), (7, 5.5, 4.5), coin)
    m.box("koban", (3, 2, 3.75), (6, 2.5, 4.5), coin)
    m.box("koban", (3, 5.5, 3.75), (6, 6, 4.5), coin)
    m.box("paw", (5.5, 2, 3), (8, 3.75, 4.5), white)
    # whiskers: two each side, out from the cheeks
    whisker = flat("millstone", 1, rim=0, ao=0)
    for y in (8.25, 9.75):
        m.box("whisker", (14, y, 3.25), (15.5, y + 0.5, 3.75), whisker)
        m.box("whisker", (0.5, y, 3.25), (2, y + 0.5, 3.75), whisker)
    m.box("bell", (7.25, 4.75, 3.25), (8.75, 6.25, 4.25),
          Paint("gold", 3, "metal", decal=lambda c: ("gold", 0) if c.face == "north" and c.p[1] < 5.25 else None))
    return display(m, (1, 1, 1), 0)


def daruma():
    """다루마: a round red tumbling doll a block wide, its front pressed flat for the face: a big white face with
    bold black brows, two large round eyes (the one on the viewer's right painted in, the other still a blank
    white circle: the wish is still to come true), a red nose, a bushy moustache whose ends curl up; gold
    clouds on its sides, a gold band at the hem and a gold mark on its back."""
    m = BModel("daruma", shift=(0, 0, -1.5))         # centred in its block (its front is pressed flat)
    # the face, 14 px wide (column 0 at the viewer's left, x 15 -> 14), from y 14 down: W white, K black ink,
    # R red (nose), G gold, '.' the red lacquer
    face = ["....WWWWWW....",   # y 13
            "..KKKWWWWKKK..",   # y 12  the brows: bold arcs
            ".KKKKKWWKKKKK.",   # y 11
            ".WWWWWWWWWWWW.",   # y 10
            ".WWKKWWWWKKWW.",   # y 9   the eyes: round, 4 px
            ".WKWWKWWKWKKW.",   # y 8   blank eye | painted eye (a glint in the pupil)
            ".WKWWKWWKKKKW.",   # y 7
            ".WWKKWWWWKKWW.",   # y 6
            ".WKWWWRRWWWKW.",   # y 5   the nose; the moustache's ends curl up beside it
            ".WWKKKKKKKKWW.",   # y 4   the moustache
            ".WWWKKWWKKWWW.",   # y 3   bushy: two lobes, parted under the nose
            "..WWWWWWWWWW..",   # y 2
            "...GGGGGGGG...",   # y 1   gold band at the hem
            ]
    colours = {"W": ("porcelain", 2), "K": ("rubber", 0), "R": ("vermilion", 1), "G": ("gold", 2)}
    swirl = [".GGG.",          # a gold cloud on each side (u: z from the front, rows from y 9 down)
             "G...G",
             "G.G.G",
             "G..G.",
             ".GG.."]

    def gold(c):
        if c.face in ("east", "west"):
            col = math.floor(c.p[2] - 5.5) if c.face == "east" else math.floor(10.5 - c.p[2])
            row = math.floor(9 - c.p[1])
            if 0 <= row < 5 and 0 <= col < 5 and swirl[row][col] == "G":
                return ("gold", 2)
            if c.i[1] == 1:
                return ("gold", 2)
        if c.face == "south" and abs(c.p[0] - 8) < 1 and 5 <= c.p[1] < 10 and c.i[1] != 7:
            return ("gold", 2)                     # a brushed mark on the back
        if c.face == "south" and c.i[1] == 1:
            return ("gold", 2)
        return None
    red = Paint("vermilion", 2, noise=0.02, decal=gold, rim=0.8)
    # the face is a picture: flat colours, no rims or occlusion across it
    front = Paint("vermilion", 2, noise=0, light=False, decal=picture(face, colours, 15, 14))
    m.group("doll")
    for (d, lo, hi) in ((12, 0, 1), (14, 1, 3), (16, 3, 13), (12, 13, 15), (6, 15, 16)):
        # the front is pressed flat at z 3: a 14 px face plane on the widest part
        cyl(m, "body", (8, 8), d, lo, hi, red, faces={"north": front}, clip=(3, 16))
    return display(m, (1, 1, 1), 0)


def omamori_rack():
    """오마모리 걸이: a small shrine stand: vermilion posts, a copper roof gone green, a bar of brocade charms
    (red, indigo, white, pink) with gold knots, and two wooden ema plaques at its foot."""
    m = BModel("omamori_rack")
    m.group("stand")
    post = Paint("vermilion", 2, "grain", axis="y", noise=0.02)
    for x in (1.5, 13):
        m.box("foot", (x - 0.5, 0, 5), (x + 2, 1, 11), DARK)
        m.box("post", (x, 1, 7.25), (x + 1.5, 12.5, 8.75), post)
    m.box("bar", (3, 10, 7.5), (13, 11, 8.5), DARK)
    m.box("bar", (3, 3, 7.5), (13, 3.75, 8.5), DARK)
    m.group("roof")
    copper = Paint("celadon", 1, "planks", axis="x", width=2)
    m.box("roof", (0, 12.5, 5.5), (16, 13.25, 10.5), copper)
    m.box("roof", (0.5, 13.25, 6.25), (15.5, 14, 9.75), copper)
    m.box("roof", (1, 14, 7), (15, 14.75, 9), copper)
    m.box("ridge", (0.5, 14.75, 7.5), (15.5, 15.5, 8.5), flat("celadon", 0))
    m.group("charms")
    for k, (x, ramp_name) in enumerate(((3.5, "vermilion"), (6, "indigo"), (8.5, "porcelain"), (11, "sakura"))):
        def brocade(c):
            if c.face in ("north", "south") and (c.i[0] + c.i[1]) % 3 == 0:
                return ("gold", 2)
            return None
        top = 9.5 - (k % 2) * 0.75
        m.box("knot", (x + 0.5, top, 7.75), (x + 1.5, 10, 8.25), flat("gold", 3))
        m.box("charm", (x, top - 4, 7.5), (x + 2, top, 8.5), Paint(ramp_name, 2, noise=0, decal=brocade, rim=0.8))
    m.group("ema")
    ema = Paint("hinoki", 2, "grain", axis="x", noise=0,
                decal=lambda c: ("rubber", 1) if c.face == "north" and c.i[1] == 1 and c.i[0] % 2 == 0 else None)
    for x in (4, 9.5):
        m.box("ema", (x, 0.25, 7.25), (x + 2.5, 2.75, 7.75), ema)
    return display(m, (1, 1, 1), 0)


def wind_chime():
    """풍경: a glass furin painted with red goldfish and a blue rim, a clapper on a string, the tanzaku strip with
    a brushed wish. Hangs from the top of its block. The bell is a little larger than life (half a block), so it
    reads overhead."""
    m = BModel("wind_chime", floor=None, planes=(("y>", 16),))
    m.group("chime")
    m.box("hook", (7.75, 14.75, 7.75), (8.25, 16, 8.25), IRON)

    fish = [".##.#",          # a goldfish swimming to the left, its tail forked
            "####.",
            ".##.#"]

    def glass(c):
        if c.face == "down":
            return ("glass", 3) if math.hypot(c.p[0] - 8, c.p[2] - 8) > 3.2 else ("glass", 0)
        if c.face == "up":
            return None
        y = c.p[1]
        if y < 9.25:
            return ("indigo", 3)                       # the blue painted rim
        if c.face in ("north", "south"):
            col = math.floor(10.5 - c.p[0]) if c.face == "north" else math.floor(c.p[0] - 5.5)
            row = math.floor(13 - y)
            if 0 <= row < 3 and 0 <= col < 5 and fish[row][col] == "#":
                return ("vermilion", 2)                # a goldfish on each side
        if c.face in ("north", "east") and c.i[1] in (11, 12) and (c.i[0] if c.face == "north" else c.i[2]) == 10:
            return ("glass", 3)                        # a glint running down the glass
        return None
    gl = Paint("glass", 1, noise=0, decal=glass, rim=1.0, ao=0.5)
    cyl(m, "bell", (8, 8), 6, 13.5, 14.75, gl)
    cyl(m, "bell", (8, 8), 8, 8.5, 13.5, gl)
    m.box("string", (7.75, 6, 7.75), (8.25, 8.5, 8.25), flat("cream", 1))
    m.box("clapper", (7.25, 7.75, 7.25), (8.75, 8.5, 8.75), flat("glass", 3))

    def wish(c):
        if c.face in ("north", "south") and c.i[0] == 7 and c.i[1] in (1, 2, 4):
            return ("sakura", 0)                       # a short wish brushed down the strip
        return None
    m.box("tanzaku", (7, 0.5, 7.875), (9, 6, 8.125), Paint("sakura", 3, noise=0, decal=wish, rim=0.3))
    return display(m, (1, 1, 1), 0)


# ================================================================================================
# 큰 장식 · 정원
# ================================================================================================

def wagasa():
    """우산 (노다테 가사) 1x3: a big red garden parasol (janome: a white ring round the top) on a bamboo pole in a
    stone foot. Its canopy is a shallow cone of thin rings, the ribs painted straight across them so it reads
    as one smooth surface; underneath, the bamboo ribs, the coloured binding threads and the hub with its
    struts. Stand under it (the canopy reaches a block out on every side)."""
    m = BModel("wagasa", dy=-16)
    cx = cz = 8

    def rib(c):
        dx, dz = c.p[0] - cx, c.p[2] - cz
        r = math.hypot(dx, dz)
        a = math.atan2(dz, dx)
        on_rib = (a / (2 * math.pi) * 32) % 1.0 < 0.2 and r > 2.5
        if c.face == "down":
            if r < 2:
                return ("bamboo", 1)
            if 3.5 < r < 5:                                                    # kagari-ito: coloured threads
                if math.floor(r * 2) % 2 == 0:
                    return (("vermilion", 3), ("indigo", 2), ("bamboo", 3), ("porcelain", 2))[int((a + math.pi) * 2) % 4]
                return ("rubber", 1)
            return ("bamboo", 2) if on_rib else ("vermilion", 1 if r > 14 else 2)
        if 6.5 < r < 8.5:
            return ("porcelain", 1 if on_rib else 2)                           # janome: the white ring
        if on_rib:
            return ("vermilion", 1)
        return ("vermilion", 2.6 - r * 0.06 if c.face == "up" else 2.2 - r * 0.06)
    canopy = Paint("vermilion", 2, decal=rib, rim=0, ao=0.3)
    m.group("canopy")
    for k, d in enumerate((32, 29, 26, 23, 19, 15, 11, 6)):
        cyl(m, "canopy", (cx, cz), d, 34 + 0.5 * k, 34.5 + 0.5 * k, canopy)
    cyl(m, "kashira", (cx, cz), 3, 38, 39, LACQ)
    m.box("kashira_tip", (7.5, 39, 7.5), (8.5, 39.5, 8.5), LACQ)
    m.group("pole")
    cyl(m, "foot", (cx, cz), 7, 0, 2, STONE_DARK)
    cyl(m, "foot", (cx, cz), 4, 2, 3, STONE)
    m.box("pole", (7.25, 3, 7.25), (8.75, 34, 8.75), Paint("bamboo", 3, "bamboo", axis="y", node=8, node_off=2))
    cyl(m, "rokuro", (cx, cz), 3, 27, 29, flat("hinoki", 3))
    # the struts from the hub up to the canopy (tilted 22.5 degrees, one per side)
    strut = flat("bamboo", 2, ao=0)
    m.box("strut", (8.75, 28, 7.75), (15.75, 28.5, 8.25), strut, rotation=("z", 22.5, (8.75, 28, 8)))
    m.box("strut", (0.25, 28, 7.75), (7.25, 28.5, 8.25), strut, rotation=("z", -22.5, (7.25, 28, 8)))
    m.box("strut", (7.75, 28, 8.75), (8.25, 28.5, 15.75), strut, rotation=("x", -22.5, (8, 28, 8.75)))
    m.box("strut", (7.75, 28, 0.25), (8.25, 28.5, 7.25), strut, rotation=("x", 22.5, (8, 28, 7.25)))
    return display(m, (2, 2.6, 2), -16)


def kimono_rack():
    """기모노 걸이 (이코) 2x2: a black lacquer rack (its top bar's ends curl up like a torii's) with a furisode
    spread across it: deep indigo fading lighter to the hem, cherry blossoms and a flowing stream, the long
    sleeves hanging low, a white collar and a red-and-gold obi."""
    m = BModel("kimono_rack")
    m.group("frame")
    for x in (2, 28.5):
        m.box("foot", (x - 1.5, 0, 4), (x + 3, 1.5, 12), LACQ)
        m.box("post", (x, 1.5, 7.25), (x + 1.5, 28, 8.75), LACQ)
    m.box("top_bar", (0, 28, 7), (32, 29.5, 9), LACQ)
    m.box("top_bar_end", (0, 29.5, 7), (2, 30.25, 9), LACQ)
    m.box("top_bar_end", (30, 29.5, 7), (32, 30.25, 9), LACQ)
    m.box("low_bar", (3.5, 9, 7.5), (28.5, 10, 8.5), LACQ)
    m.group("kimono")

    def cloth(c):
        if c.face != "north":
            return ("indigo", 1) if c.face == "south" else None
        col = 31 - c.p[0]                  # 0 at the viewer's left
        y = c.p[1]
        ci, yi = math.floor(col), math.floor(y)
        for side in (-1, 1):               # the collar: a white V from the neck down to the obi
            xv = 15.5 + side * (1 + (27.5 - y) / 3.2)
            if 16 <= y <= 27.5 and abs(col - xv) < 0.9:
                return ("porcelain", 2 if side < 0 else 1)
        if 13 <= y < 16.5 and 8 <= col < 23:            # the obi
            if yi == 14:
                return ("gold", 3 if ci % 3 else 2)
            return ("vermilion", 2)
        if abs(y - (6 + 2.2 * math.sin(col * 0.45))) < 0.55:
            return ("water", 3)                         # a flowing stream
        r = h3(ci // 3, yi // 3, 0, 31)
        fx, fy = ci // 3 * 3 + 1 + int(r * 2), yi // 3 * 3 + 1
        if r < 0.24 and (ci, yi) in ((fx, fy), (fx + 1, fy), (fx - 1, fy), (fx, fy + 1), (fx, fy - 1)):
            return ("sakura", 3 if (ci, yi) == (fx, fy) else 2)
        return ("indigo", 1 if y > 22 else 2 if y > 10 else 3)      # bokashi: dark shoulders, lighter hem
    silk = Paint("indigo", 2, decal=cloth, ao=0.6, rim=0.5)
    # the long furisode sleeves hang to y 11, the body to its hem at y 3
    for (x0, y0, x1, y1) in ((1, 11, 9, 28), (23, 11, 31, 28), (9, 3, 23, 28)):
        m.box("cloth", (x0, y0, 5.5), (x1, y1, 6.5), silk)
    return display(m, (2, 2, 1), 0)


def torii():
    """도리이 4x4 (3.75 x 3.3 blocks): a myōjin torii: vermilion posts on black bases, the black kasagi on top with
    its ends swept up, the shimaki under it, the nuki tie beam through the posts with wedges (2.1 blocks up: a
    player walks under it with room to spare), and the name plaque on its strut. Walk through the middle: two
    blocks wide between the posts (cells x 0 and 1; the posts stand in cells -1 and 2).
    Bigger than a model may be: drawn at its real size round x 16, shrunk by 1.25 and scaled back up by the item
    display (furniture.scale 1.25, translation [-0.5, 1, 0]), so its texels are the same 1/16 block as the rest."""
    m = BModel("torii", shift=(-8, -16, 0), scale=1.25)
    red = Paint("vermilion", 2, "grain", axis="long", noise=0.04)
    black = LACQ
    m.group("posts")
    for cx in (-6, 38):
        cyl(m, "hashira", (cx, 8), 6, 3.5, 46, red)
        cyl(m, "kamaki", (cx, 8), 7, 0, 3.5, black)
    m.group("beams")
    m.box("nuki", (-14, 34, 6.75), (-9, 37, 9.25), red)
    m.box("nuki", (-3, 34, 6.75), (35, 37, 9.25), red)
    m.box("nuki", (41, 34, 6.75), (46, 37, 9.25), red)
    for x in (-12.5, 43):
        m.box("kusabi", (x, 34.25, 6.25), (x + 1.5, 36.75, 6.75), black)
    m.box("shimaki", (-12, 45.5, 5.75), (44, 48.5, 10.25), red)
    m.box("kasagi", (-8, 48.5, 5), (40, 51.5, 11), black)        # it sweeps up towards its ends
    for (x0, x1, lo, hi) in ((-12, -8, 49, 52), (40, 44, 49, 52), (-14, -12, 50, 53), (44, 46, 50, 53)):
        m.box("kasagi", (x0, lo, 5), (x1, hi, 11), black)
    m.group("plaque")
    m.box("gakuzuka", (14, 37, 7), (18, 38.5, 9), red)
    m.box("gakuzuka", (14, 44.5, 7), (18, 45.5, 9), red)
    glyph = ["......",                                              # two brushed characters in a column
             "..##..",
             "......",
             "..##..",
             "...#..",
             "......"]

    def plaque(c):
        if c.face in ("north", "south"):
            col = math.floor(19 - c.p[0]) if c.face == "north" else math.floor(c.p[0] - 13)
            row = math.floor(44.5 - c.p[1])
            if 0 <= row < 6 and 0 <= col < 6:
                if col in (0, 5) or row in (0, 5):
                    return ("gold", 1)
                if glyph[row][col] == "#":
                    return ("gold", 3)
        return None
    m.box("gaku", (13, 38.5, 6.25), (19, 44.5, 9.75), LACQ(decal=plaque))
    return display(m, (3, 3, 1), -16)


def stepping_stones():
    """징검돌: flat garden stones of different greys, rounded and slightly domed, moss between them (walk over
    them): a big one to step on (half a metre across, as in a real garden) and two smaller ones."""
    m = BModel("stepping_stones")
    m.group("stones")
    for (x0, z0, x1, z1, h, p) in ((1.5, 1.5, 11, 10, 1.25, STONE), (9.5, 10, 15.5, 15.5, 1.0, STONE_LIGHT),
                                   (0.5, 11, 7, 15.5, 0.75, STONE_DARK)):
        m.box("stone", (x0 + 1, 0, z0), (x1 - 1, h, z1), p)
        m.box("stone", (x0, 0, z0 + 1), (x0 + 1, h - 0.25, z1 - 1), p)
        m.box("stone", (x1 - 1, 0, z0 + 1), (x1, h - 0.25, z1 - 1), p)
        m.box("stone_top", (x0 + 2, h, z0 + 1.5), (x1 - 2, h + 0.5, z1 - 1.5), p)
    m.group("moss")
    for (x, z, w, d) in ((11.5, 4, 3, 2.5), (7.5, 10.5, 1.5, 3), (12, 1, 2.5, 1.5), (0.25, 3, 1, 2.5)):
        m.box("moss", (x, 0, z), (x + w, 0.25, z + d), Paint("leaf_dark", 1, "speckle", rim=0))
    return display(m, (1, 1, 1), 0)


def koi_pond():
    """잉어 연못 2x2: a garden pond edged with natural rocks of different sizes, deep water darker in the middle,
    two koi (a red-and-white kohaku and a golden ogon) that swim round and round (an animated texture), lily
    pads and a lotus flower."""
    m = BModel("koi_pond")
    m.group("water")
    N = 48

    def koi_at(px, pz, k):
        """(ramp, step) of a koi under the texel at (px, pz) in frame k, or None."""
        for (r, phase, sense, kind) in ((7.5, 0.0, 1, "kohaku"), (5.0, 0.5, -1, "ogon")):
            th = 2 * math.pi * (k / N + phase) * sense
            hx, hz = 16 + r * math.cos(th), 16 + r * math.sin(th)
            dirx, dirz = -math.sin(th) * sense, math.cos(th) * sense
            dx, dz = px - hx, pz - hz
            along = dx * dirx + dz * dirz
            across = abs(-dx * dirz + dz * dirx)
            if along > 2.5 or along < -4.2:
                continue
            if along > 1.5:
                w = 1.0 - (along - 1.5) * 0.6              # the round head
            elif along > -0.5:
                w = 1.25
            elif along > -3.0:
                w = 1.25 - (-0.5 - along) * 0.38           # the body thins to the tail
            else:
                w = 0.3 + (-3.0 - along) * 0.9             # the tail fin fans out
            if across >= w:
                continue
            if along < -3.0:
                return ("porcelain", 1) if kind == "kohaku" else ("gold", 1)
            if kind == "kohaku":
                red = across < 0.8 and (along > 1.1 or -1.8 < along < 0.1)
                return ("vermilion", 2) if red else ("porcelain", 2)
            return ("gold", 3 if across < 0.45 else 2)
        return None

    def water(c):
        px, pz = c.p[0], c.p[2]
        depth = math.hypot(px - 16, pz - 16) / 13.0
        koi = koi_at(px, pz, c.frame)
        if koi:
            return (koi[0], koi[1] - (0.4 if depth < 0.5 else 0.1))
        ripple = (c.i[0] + c.i[2] + c.frame // 2) % 12 == 0 and h3(c.i[0], 0, c.i[2], 17) < 0.5
        return ("water", (0.7 if depth < 0.5 else 1.2 if depth < 0.85 else 1.8) + (0.9 if ripple else 0))
    m.box("water", (3, 0.25, 3), (29, 1.75, 29), Paint("water", 1, decal=water, frames=N, frametime=3, ao=0.8),
          faces={f: Paint("water", 0, noise=0) for f in ("north", "south", "east", "west", "down")})
    m.group("rocks")
    rock = moss_on(STONE, amount=0.66)
    rocks = [  # x0, z0, x1, z1, height, paint
        (0, 0, 9, 3.5, 3.0, rock), (9, 0, 17, 3, 2.25, STONE_LIGHT), (17, 0, 24, 3.25, 2.75, rock), (24, 0, 32, 6, 4.0, rock),
        (0, 3.5, 3, 12, 2.5, STONE_LIGHT), (0, 12, 3.5, 20, 3.0, rock), (0, 20, 3, 27, 2.25, STONE_DARK), (0, 27, 6, 32, 3.5, rock),
        (29, 6, 32, 15, 2.5, STONE_DARK), (28.5, 15, 32, 22, 3.25, rock), (29, 22, 32, 32, 2.5, STONE_LIGHT),
        (6, 29, 14, 32, 2.75, rock), (14, 29.5, 21, 32, 2.25, STONE_DARK), (21, 28.5, 29, 32, 3.0, rock)]
    for (x0, z0, x1, z1, h, p) in rocks:
        m.box("rock", (x0, 0, z0), (x1, h, z1), p)
        if min(x1 - x0, z1 - z0) >= 4:
            m.box("rock_top", (x0 + 1, h, z0 + 1), (x1 - 1, h + 0.75, z1 - 1), p)
    m.group("lilies")
    pad = Paint("leaf", 2, noise=0.05, rim=0.8,
                decal=lambda c: ("leaf", 1) if c.face == "up" and (c.i[0] + c.i[2]) % 7 == 0 else None)
    for (x, z, d) in ((7, 7.5, 5), (24, 23, 5), (8, 23.5, 4), (24.5, 9, 3)):
        cyl(m, "pad", (x, z), d, 1.75, 2.0, pad)
    m.box("lotus", (23, 2.0, 22), (25, 3.0, 24), Paint("sakura", 2, noise=0, rim=0.8))
    m.box("lotus_eye", (23.75, 3.0, 22.75), (24.25, 3.25, 23.25), flat("gold", 3, light=False))
    return display(m, (2, 1, 2), 0)


# ================================================================================================
# Registry
# ================================================================================================

PIECES = {
    # id: (builder, cells as (w, h, d) blocks, floor)
    "tatami": (tatami, (2, 1, 1), 0),
    "zabuton": (zabuton, (1, 1, 1), 0),
    "chabudai": (chabudai, (1, 1, 1), 0),
    "kotatsu": (kotatsu, (3, 1, 3), 0),
    "futon": (futon, (2, 1, 3), 0),
    "shoji_screen": (shoji_screen, (2, 2, 1), 0),
    "fusuma_panel": (fusuma_panel, (2, 3, 1), -16),
    "hanging_scroll": (hanging_scroll, (1, 2, 1), 0),
    "noren": (noren, (1, 1, 1), 0),
    "bamboo_fence": (bamboo_fence, (1, 2, 1), 0),
    "andon": (andon, (1, 1, 1), 0),
    "stone_lantern": (stone_lantern, (1, 2, 1), 0),
    "chochin": (chochin, (1, 1, 1), 0),
    "bonsai": (bonsai, (1, 1, 1), 0),
    "ikebana": (ikebana, (1, 1, 1), 0),
    "bamboo_pot": (bamboo_pot, (1, 2, 1), 0),
    "sakura_pot": (sakura_pot, (1, 2, 1), 0),
    "tea_set": (tea_set, (1, 1, 1), 0),
    "tea_kettle": (tea_kettle, (1, 1, 1), 0),
    "sake_set": (sake_set, (1, 1, 1), 0),
    "taiko_drum": (taiko_drum, (1, 2, 1), 0),
    "maneki_neko": (maneki_neko, (1, 1, 1), 0),
    "daruma": (daruma, (1, 1, 1), 0),
    "omamori_rack": (omamori_rack, (1, 1, 1), 0),
    "wind_chime": (wind_chime, (1, 1, 1), 0),
    "wagasa": (wagasa, (1, 3, 1), -16),
    "kimono_rack": (kimono_rack, (2, 2, 1), 0),
    "torii": (torii, (4, 4, 1), -16),
    "stepping_stones": (stepping_stones, (1, 1, 1), 0),
    "koi_pond": (koi_pond, (2, 1, 2), 0),
}

ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}
