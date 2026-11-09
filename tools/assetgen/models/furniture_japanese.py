"""
Japanese-style decorative furniture (content/stsv/items/furniture_japanese.yml): 30 pieces for houses and
gardens, built from the machines' per-face materials (economy_machines.Mat) and the shared palette.

Coordinates: model units (16 = 1 block), front = north (z 0), the anchor cell is x 0..16 / z 0..16, y 0 = its
floor. Pieces that reach over a block to the left/right or to the front use negative x / z (the kotatsu's
cushions, the torii's posts); pieces three blocks tall are drawn from y -16 and placed a block higher
(furniture translation [0, 1, 0]), like the forge.

Collision and click boxes come from the model itself (FurnitureShape: a cell gets a barrier when the model
fills 30% of it, voxels of 1/4 block), so low and thin things (mats, cushions, curtains, stepping stones) are
drawn so they stay under that, and walls of a piece (screens, fences) are centred on the cell (z 7..9 crosses
two voxel rows) so they do get one. Lamps give real light (furniture.light in the item file).

PIECES: id -> (builder, cells as (w, h, d) for the previews, floor offset)
ICONS:  id -> 16x16 inventory icon (stsv:<id>_icon, the item's 'icon')
"""

import math
import random
import zlib

from PIL import Image

import economy_models  # noqa: F401  (registers the shared surfaces the machine materials may use)
from economy_machines import MModel, Mat, cyl, ring, display, IRON, GRANITE, LOG
from stsv_assets import textures as T
from stsv_assets.palette import ramp
from stsv_assets.pixelart import Canvas

S = 16

# ================================================================================================
# Materials
# ================================================================================================

HINOKI = Mat("hinoki", "planks", 2)
HINOKI_GRAIN = Mat("hinoki", "grain", 2)
DARK = Mat("wood_dark", "grain", 1)           # dark-stained frames, legs
DARK_PLANKS = Mat("wood_dark", "planks", 2)
WALNUT = Mat("wood", "grain", 1)
LACQUER = Mat("rubber", "plain", 2, noise=0.04)  # black lacquer
LACQUER_RED = Mat("vermilion", "plain", 2, noise=0.04)
VERMILION = Mat("vermilion", "plain", 2)
VERMILION_LIGHT = Mat("vermilion", "plain", 3)
CRIMSON = Mat("crimson", "plain", 2, noise=0.04)
CRIMSON_TOP = Mat("crimson", "plain", 3, noise=0.04)
INDIGO = Mat("indigo", "weave", 2)
INDIGO_DARK = Mat("indigo", "weave", 1)
CREAM = Mat("cream", "weave", 2)
WHITE = Mat("porcelain", "plain", 2, noise=0.04)
CELADON = Mat("celadon", "plain", 2, noise=0.05)
CELADON_DARK = Mat("celadon", "plain", 1, noise=0.05)
GOLD = Mat("gold", "metal", 2)
BRASS = Mat("brass", "metal", 2)
STONE = Mat("millstone", "speckle", 2)
STONE_LIGHT = Mat("millstone", "speckle", 3)
STONE_DARK = Mat("millstone", "speckle", 1)
MOSS = Mat("leaf", "speckle", 2)
LEAF = Mat("leaf", "speckle", 2)
LEAF_DARK = Mat("leaf_dark", "speckle", 2)
BAMBOO = Mat("bamboo", "grain", 3)
BAMBOO_DARK = Mat("bamboo", "grain", 2)
SOIL = Mat("soil", "speckle", 1, bevel=None)
BARK = Mat("bark", "grain", 2)
PAPER = Mat("paper", "plain", 1, noise=0.04)
LIT = Mat("washi_lit", "plain", 1, glow=True, noise=0.0, bevel=(1, 0))
SAKURA = Mat("sakura", "speckle", 2)
SAKURA_LIGHT = Mat("sakura", "speckle", 3)
TATAMI_EDGE = Mat("rubber", "weave", 2, bevel=(1, 0))
CLAY = Mat("terracotta", "plain", 1)
ONGGI = Mat("onggi", "plain", 2, noise=0.05)
FOX = Mat("fox", "plain", 3, noise=0)
FOX_DARK = Mat("fox", "plain", 1, noise=0)
FEATHER = Mat("feather", "plain", 2, noise=0.04)
BLACK = Mat("rubber", "plain", 0, bevel=None, noise=0)
PINK = Mat("pink", "plain", 2, noise=0)
GREEN_TEA = Mat("bamboo", "plain", 3, bevel=None, noise=0)
EMBER = Mat("fire", "plain", 3, glow=True, bevel=None, noise=0.2)


def _canvas():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def surface(name, fn):
    """Registers a tileable 16x16 surface (auto UV continues it across faces)."""
    T.SURFACES[name] = fn
    return name


def picture(name, w, h, paint):
    """A picture of exactly w x h pixels for one face (uv [0, 0, w, h]): paint(px) draws it.
    Returns (texture, uv) - the uv shows it unmirrored on north and east faces."""
    im = _canvas()
    px = im.load()
    paint(px)
    _fill_rest(im, w, h)
    T.SURFACES[name] = (lambda img=im: img.copy())
    return name, [0, 0, w, h]


def _fill_rest(im, w, h):
    """The unused rest of a 16x16 face picture: opaque (a solid box must not have see-through pixels)."""
    px = im.load()
    for y in range(S):
        for x in range(S):
            if x >= w or y >= h or px[x, y][3] == 0:
                px[x, y] = px[min(x, w - 1), min(y, h - 1)] if px[min(x, w - 1), min(y, h - 1)][3] else (0, 0, 0, 255)


def flat(m, name, frm, to, mat, front, back=None, back_uv=None):
    """A box with a picture on its north face (front) and optionally another on its south face."""
    tex, uv = front
    faces = {"north": tex}
    uvs = {"north": uv}
    if back:
        faces["south"] = back[0]
        uvs["south"] = back_uv or [back[1][2], 0, 0, back[1][3]]
    m.box(name, frm, to, mat, faces=faces, uv=uvs)


# --- tileable surfaces -----------------------------------------------------------------------------

def _tatami():
    """Rush mat: fine weave rows along the long side, a stitch line every 4 px across."""
    rnd = random.Random(601)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2 if y % 2 else 3
            if x % 8 == 3:
                i = 1 if y % 2 else 2
            px[x, y] = ramp("tatami", i)
    return im


def _shoji_lattice():
    """Paper between thin kumiko bars, a bar every 4 px (tiles seamlessly)."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            if x % 4 == 0 or y % 5 == 0:
                px[x, y] = ramp("hinoki", 1 if (x % 4 == 0 and y % 5 == 0) else 2)
            else:
                px[x, y] = ramp("paper", 2 if (x % 4 == 1 or y % 5 == 1) else 1)
    return im


def _shoji_lit():
    """Lamp paper: glowing paper with the frame's bars as soft shadows."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("washi_lit", 0 if y % 6 == 0 else 1 if (x % 8 == 0) else 2)
    return im


def _bamboo_slats():
    """Split bamboo slats side by side: one slat per 2 px (lit edge, body), a node row every 8 px."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 3 if x % 2 == 0 else 2
            if y % 8 == 5:
                i = 1
            px[x, y] = ramp("bamboo", i)
    return im


def _chochin():
    """Lantern paper: red, a darker bamboo rib every 2 px."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("vermilion", 2 if y % 2 else 3)
    return im


def _kotatsu_quilt():
    """Warm orange quilt with a soft plaid (two darker lines each way every 8 px)."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            on_x, on_y = x % 8 in (2, 3), y % 8 in (2, 3)
            i = 1 if (on_x and on_y) else 2 if (on_x or on_y) else 3
            if x % 8 == 6 or y % 8 == 6:
                i = min(i, 2) if not (on_x or on_y) else i
            px[x, y] = ramp("fox", i)
    return im


def _futon_cover():
    """Indigo futon cover: asanoha-like white diamonds (hemp leaf) on indigo."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            u, v = x % 8, y % 8
            if abs(u - 4) + abs(v - 4) == 4 or (u == 4 and v in (1, 7)):
                px[x, y] = ramp("porcelain", 0)
                continue
            px[x, y] = ramp("indigo", i)
    return im


def _water_frame(k):
    """Pond water: deep blue with light ripples drifting (frame k of 8)."""
    rnd = random.Random(611)
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 1 if (x * 3 + y * 5) % 11 else 2
            px[x, y] = ramp("water", i)
    for (x0, y0, n) in ((2, 3, 4), (9, 8, 5), (4, 13, 3)):
        for j in range(n):
            px[(x0 + j + k) % S, y0] = ramp("water", 3)
    for _ in range(6):
        px[rnd.randrange(S), rnd.randrange(S)] = ramp("water", 0)
    return im


surface("jp_tatami", _tatami)
surface("jp_shoji", _shoji_lattice)
surface("jp_shoji_lit", _shoji_lit)
surface("jp_bamboo_slats", _bamboo_slats)
surface("jp_chochin", _chochin)
surface("jp_kotatsu_quilt", _kotatsu_quilt)
surface("jp_futon", _futon_cover)
surface("jp_water", lambda: _water_frame(0))


def _furin():
    """Wind chime glass with little red goldfish painted on (bands of the bell show slices of it)."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("glass", 2 if (x + y) % 7 else 3)
    for (x, y) in ((2, 6), (3, 6), (4, 7), (9, 5), (10, 5), (11, 6), (13, 9), (6, 10), (7, 10)):
        px[x, y] = ramp("vermilion", 3)
    return im


surface("jp_furin", _furin)


def _hibachi():
    """Blue-and-white porcelain: an indigo wave band and a line under it (rows line up on every face)."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = ramp("porcelain", 2 if (x + y) % 9 else 1)
            if y == 12 and x % 4 < 2:
                i = ramp("indigo", 3)
            if y == 14:
                i = ramp("indigo", 2)
            px[x, y] = i
    return im


surface("jp_hibachi", _hibachi)
T.ANIMATED["jp_water"] = (lambda: [_water_frame(k) for k in range(8)], 4)


def _disc_pic(name, d, paint):
    """A round end (drum head, umbrella underside) painted on the full 16x16 square; paint(px, dx, dy, r)
    gets the offset from the centre in units of the radius (cyl maps the whole square on the round end)."""
    im = _canvas()
    px = im.load()
    for y in range(S):
        for x in range(S):
            dx, dy = (x + 0.5 - 8) / 8, (y + 0.5 - 8) / 8
            c = paint(dx, dy, math.hypot(dx, dy))
            px[x, y] = c if c[3] else paint(dx * 0.9 / max(1e-6, math.hypot(dx, dy)), dy * 0.9 / max(1e-6, math.hypot(dx, dy)), 0.9)
    T.SURFACES[name] = (lambda img=im: img.copy())
    return name


# ================================================================================================
# Shared parts
# ================================================================================================

def cushion(m, x0, z0, w, mat=CRIMSON, top=CRIMSON_TOP, tassel=GOLD):
    """Zabuton: a flat square cushion 2.5 high with a domed top, corner tassels and a centre tuft."""
    m.box("cushion", (x0, 0, z0), (x0 + w, 1.5, z0 + w), mat)
    m.box("cushion_top", (x0 + 0.5, 1.5, z0 + 0.5), (x0 + w - 0.5, 2.5, z0 + w - 0.5), top)
    c = w / 2
    m.box("tuft", (x0 + c - 0.5, 2.5, z0 + c - 0.5), (x0 + c + 0.5, 2.75, z0 + c + 0.5), Mat("crimson", "plain", 0, bevel=None, noise=0))
    for (dx, dz) in ((-0.5, -0.5), (w - 0.5, -0.5), (-0.5, w - 0.5), (w - 0.5, w - 0.5)):
        m.box("tassel", (x0 + dx, 0.25, z0 + dz), (x0 + dx + 1, 1.25, z0 + dz + 1), tassel)


# ================================================================================================
# 바닥 · 앉는 것
# ================================================================================================

def tatami():
    """다다미 2x1: a rush mat 1.5 high with dark cloth borders (heri) along the long sides."""
    m = MModel("tatami")
    m.group("mat")
    m.box("rush", (0, 0, 1.5), (32, 1.5, 14.5), "jp_tatami")
    m.box("heri", (0, 0, 0), (32, 1.5, 1.5), TATAMI_EDGE)
    m.box("heri", (0, 0, 14.5), (32, 1.5, 16), TATAMI_EDGE)
    return display(m, (2, 1, 1), 0)


def zabuton():
    """방석: the floor cushion."""
    m = MModel("zabuton")
    m.group("cushion")
    cushion(m, 1.5, 1.5, 13)
    return display(m, (1, 1, 1), 0)


def chabudai():
    """좌식 탁자: a round low table, four short legs under an apron."""
    m = MModel("chabudai")
    m.group("table")
    cyl(m, "top", "y", (8, 8), 15, 6, 7.5, WALNUT, cap=Mat("wood", "plain", 2, noise=0.05))
    ring(m, "apron", (8, 8), 11, 9, 5, 6, DARK)
    for (x, z) in ((4.5, 4.5), (10.5, 4.5), (4.5, 10.5), (10.5, 10.5)):
        m.box("leg", (x, 0, z), (x + 1, 5, z + 1), DARK)
    m.group("tea")
    cyl(m, "cup", "y", (6, 7), 2, 7.5, 9, CELADON, cap=GREEN_TEA)
    cyl(m, "cup", "y", (10, 9.5), 2, 7.5, 9, CELADON, cap=GREEN_TEA)
    return display(m, (1, 1, 1), 0)


def kotatsu():
    """고타츠: the quilted heated table with a basket of mandarins, a cushion on each side (plus-shaped,
    3x3 blocks without the corners)."""
    m = MModel("kotatsu")
    m.group("table")
    q = "jp_kotatsu_quilt"
    m.box("quilt_skirt", (0, 0, 0), (16, 1, 16), q)
    m.box("quilt", (0.5, 1, 0.5), (15.5, 5.5, 15.5), q)
    m.box("quilt_top", (1, 5.5, 1), (15, 6.5, 15), q)
    m.box("board", (0.5, 6.5, 0.5), (15.5, 7.5, 15.5), Mat("wood", "plain", 2, noise=0.05))
    m.group("mikan")
    ring(m, "basket", (8, 8), 7, 5, 7.5, 8.5, Mat("straw", "weave", 2))
    m.box("basket_floor", (6, 7.5, 6), (10, 7.75, 10), Mat("straw", "weave", 1))
    for (x, z, y) in ((6.25, 6.5, 7.75), (8.25, 6.75, 7.75), (7, 8.5, 7.75), (7.5, 7.25, 9.5)):
        m.box("mikan", (x, y, z), (x + 1.75, y + 1.75, z + 1.75), Mat("fox", "plain", 3, noise=0))
    m.box("leaf", (8.25, 11.25, 7.75), (9.25, 11.5, 8.5), Mat("leaf", "plain", 3, noise=0, bevel=None))
    m.group("cushions")
    cushion(m, 2, -14, 12)
    cushion(m, 2, 18, 12)
    cushion(m, -14, 2, 12)
    cushion(m, 18, 2, 12)
    return display(m, (3, 1, 3), 0)


def futon():
    """이불 세트 1x2: mattress, indigo quilt folded back at the top, buckwheat pillow."""
    m = MModel("futon")
    m.group("futon")
    m.box("mattress", (1, 0, 1), (15, 2, 31), Mat("porcelain", "weave", 1, bevel=(1, -1)))
    m.box("quilt", (0.5, 2, 0.5), (15.5, 3.25, 21), "jp_futon")
    m.box("fold", (0.5, 3.25, 18.5), (15.5, 3.75, 21), FEATHER)
    m.box("pillow", (4, 2, 24), (12, 3.5, 29), Mat("straw", "weave", 3))
    m.box("pillow_cover", (4.5, 3.5, 24.5), (11.5, 4, 28.5), FEATHER)
    return display(m, (1, 1, 2), 0)


# ================================================================================================
# 칸막이 · 벽
# ================================================================================================

def _shoji_panel(m, x0, tag):
    """One shoji panel 16 wide: hinoki frame, lattice paper, a wooden kick board."""
    fr = HINOKI_GRAIN
    m.box(f"stile_{tag}", (x0, 1, 7), (x0 + 1, 29, 9), fr)
    m.box(f"stile_{tag}", (x0 + 15, 1, 7), (x0 + 16, 29, 9), fr)
    m.box(f"rail_{tag}", (x0 + 1, 28, 7), (x0 + 15, 29, 9), fr)
    m.box(f"kick_{tag}", (x0 + 1, 1, 7), (x0 + 15, 5, 9), HINOKI)
    m.box(f"paper_{tag}", (x0 + 1, 5, 7.5), (x0 + 15, 16.5, 8.5), "jp_shoji")
    m.box(f"paper_{tag}", (x0 + 1, 16.5, 7.5), (x0 + 15, 28, 8.5), "jp_shoji")


def shoji_screen():
    """쇼지 칸막이 2x2: two lattice-and-paper panels on small feet."""
    m = MModel("shoji_screen")
    m.group("panels")
    _shoji_panel(m, 0, "a")
    _shoji_panel(m, 16, "b")
    m.group("feet")
    for x in (1, 27):
        m.box("foot", (x, 0, 4), (x + 4, 1, 12), DARK)
    return display(m, (2, 2, 1), 0)


def _fusuma_master():
    """The painting across both fusuma: gold leaf, Mt Fuji, a red sun, clouds and a pine."""
    W, H = 28, 26
    img = Image.new("RGBA", (W, H))
    px = img.load()
    for y in range(H):
        for x in range(W):
            leaf = (x % 7 == 0 or y % 7 == 0)
            px[x, y] = ramp("gold", 3 if leaf else 2)
    # sun
    for y in range(H):
        for x in range(W):
            if math.hypot(x - 21.5, y - 5.5) < 2.6:
                px[x, y] = ramp("vermilion", 2 if x + y < 27 else 1)
    # Mt Fuji
    for y in range(8, H):
        half = (y - 8) * 1.15 + 1.5
        for x in range(W):
            if abs(x + 0.5 - 14) <= half:
                snow = y < 12 + (2 if (x % 3 == 0) else 0)
                px[x, y] = ramp("porcelain", 2) if snow else ramp("indigo", 3 if x < 14 else 2)
    # clouds
    for (x0, x1, y) in ((2, 11, 16), (5, 14, 17), (17, 26, 13), (19, 27, 14), (0, 9, 22), (16, 25, 21)):
        for x in range(x0, min(W, x1)):
            px[x, y] = ramp("gold", 3)
    # pine at the left
    for y in range(13, H):
        px[2, y] = ramp("bark", 1)
        px[3, y] = ramp("bark", 2)
    for (cx, cy, rx) in ((3, 13, 3), (5, 17, 3), (2, 20, 2)):
        for x in range(max(0, cx - rx), cx + rx + 1):
            px[x, cy] = ramp("leaf_dark", 2)
            px[x, cy - 1] = ramp("leaf_dark", 3) if abs(x - cx) < rx else px[x, cy - 1]
    return img


def _cut(name, src, x0, y0, w, h):
    """A w x h piece of a bigger picture as its own face texture (uv [0, 0, w, h])."""
    im = _canvas()
    im.paste(src.crop((x0, y0, x0 + w, y0 + h)), (0, 0))
    _fill_rest(im, w, h)
    T.SURFACES[name] = (lambda img=im: img.copy())
    return name, [0, 0, w, h]


def fusuma_panel():
    """후스마 장식 2x2: two sliding-door panels in black lacquer frames, painted with Mt Fuji on gold."""
    m = MModel("fusuma_panel")
    art = _fusuma_master()
    back = picture("jp_fusuma_back", 13, 13, lambda px: [px.__setitem__((x, y), ramp("paper", 2 if (x + y) % 4 else 1))
                                                         for y in range(13) for x in range(13)])
    m.group("frames")
    for x0 in (0, 16):
        m.box("frame", (x0, 1, 7), (x0 + 1.5, 29, 9), LACQUER)
        m.box("frame", (x0 + 14.5, 1, 7), (x0 + 16, 29, 9), LACQUER)
        m.box("frame", (x0 + 1.5, 27.5, 7), (x0 + 14.5, 29, 9), LACQUER)
        m.box("frame", (x0 + 1.5, 1, 7), (x0 + 14.5, 2.5, 9), LACQUER)
    m.group("paintings")
    # the picture runs on behind the two middle frames. Seen from the front, model +x is on the viewer's
    # left: the panel at x 16..32 shows the picture's left half (columns 0..12), the one at x 0..16 its right
    for p, x0 in enumerate((16, 0)):
        for r, (y0, y1) in enumerate(((2.5, 15.5), (15.5, 27.5))):
            src_y = int(27.5 - y1)
            front = _cut(f"jp_fusuma_{p}{r}", art, 0 if p == 0 else 15, src_y, 13, 13)
            flat(m, "paper", (x0 + 1.5, y0, 7.5), (x0 + 14.5, y1, 8.5), PAPER, front, back)
        # the round recessed handle (hikite)
        hx = x0 + (2.5 if p == 0 else 12.5)
        m.box("hikite", (hx, 14, 7.25), (hx + 1, 15.5, 7.5), Mat("brass", "plain", 1, bevel=None, noise=0))
    m.group("feet")
    for x in (1, 27):
        m.box("foot", (x, 0, 4), (x + 4, 1, 12), LACQUER)
    return display(m, (2, 2, 1), 0)


def hanging_scroll():
    """족자 (도코노마 족자) 1x2: a kakejiku hung on the wall: celadon mount, ink painting of mountains and the
    moon with a red seal, dark rollers and a hanging cord."""
    m = MModel("hanging_scroll")

    def art_top(px):
        """The upper half: a pale full moon, a column of calligraphy."""
        for y in range(9):
            for x in range(8):
                px[x, y] = ramp("paper", 1)
                if math.hypot(x - 4.5, y - 3.5) < 2.1:
                    px[x, y] = ramp("cream", 3)
        for y in (1, 2, 4, 5, 7):
            px[1, y] = ramp("millstone", 1)

    def art_bottom(px):
        """The lower half: ink mountains fading into mist, the painter's red seal."""
        for y in range(9):
            for x in range(8):
                px[x, y] = ramp("paper", 1)
        for x in range(8):
            far = 2 + int(1.5 + 1.5 * math.sin(x * 0.9 + 0.6))
            near = 4 + int(1.2 + 1.2 * math.sin(x * 1.3 + 2.0))
            for y in range(far, 9):
                px[x, y] = ramp("millstone", 4 if y < near else 2)
        px[6, 8] = ramp("vermilion", 1)
        for y in (0, 1):
            px[1, y] = ramp("millstone", 1)

    m.group("scroll")
    m.box("cord", (7.75, 30, 15.5), (8.25, 32, 16), Mat("crimson", "plain", 2, bevel=None, noise=0))
    m.box("rod_top", (2.5, 28.5, 15), (13.5, 30, 16), DARK)
    m.box("mount", (3, 4.5, 15.25), (13, 28.5, 16), CELADON_DARK)
    flat(m, "art", (4, 17, 14.75), (12, 26, 15.25), PAPER, picture("jp_scroll_top", 8, 9, art_top))
    flat(m, "art", (4, 8, 14.75), (12, 17, 15.25), PAPER, picture("jp_scroll_bottom", 8, 9, art_bottom))
    m.box("roller", (2.5, 3, 15), (13.5, 4.5, 16), DARK)
    for x in (1.5, 13.5):
        m.box("knob", (x, 2.75, 14.75), (x + 1, 4.75, 16), LACQUER)
    return display(m, (1, 2, 1), 0)


def noren():
    """노렌: an indigo shop curtain in three strips on a rod, a white crest in the middle and a white hem.
    Hangs from the top of its block (place it under a door lintel)."""
    m = MModel("noren")

    def strip(crest):
        def paint(px):
            for y in range(13):
                for x in range(5):
                    i = 2 if y else 3
                    px[x, y] = ramp("indigo", i)
                    if y in (10, 11):
                        px[x, y] = ramp("porcelain", 1)
            if crest:
                for y in range(13):
                    for x in range(5):
                        d = math.hypot(x - 2, y - 4)
                        if 1.2 <= d < 2.3:
                            px[x, y] = ramp("porcelain", 2)
                px[2, 4] = ramp("porcelain", 2)
        return paint

    m.group("rod")
    m.box("rod", (0, 15, 7.5), (16, 16, 8.5), Mat("bamboo", "grain", 2))
    m.group("cloth")
    for k, x0 in enumerate((0.25, 5.5, 10.75)):
        pic = picture(f"jp_noren_{1 if k == 1 else 0}", 5, 13, strip(k == 1))
        flat(m, "strip", (x0, 2, 7.75), (x0 + 5, 15, 8.25), INDIGO, pic, pic, back_uv=[5, 0, 0, 13])
    return display(m, (1, 1, 1), 0)


def bamboo_fence():
    """대나무 울타리: split-bamboo slats between bamboo rails tied with black cord; joins its neighbours."""
    m = MModel("bamboo_fence")
    m.group("fence")
    m.box("slats", (0, 0, 7), (16, 14, 9), "jp_bamboo_slats", faces={"up": Mat("bamboo", "plain", 2)})
    rail = Mat("bamboo", "grain", 1)
    for y in (3, 9.5):
        m.box("rail", (0, y, 6), (16, y + 1.5, 7), rail)
        m.box("rail", (0, y, 9), (16, y + 1.5, 10), rail)
    for x in (3.5, 11.5):
        m.box("tie", (x, 2.5, 5.5), (x + 1, 5, 6), BLACK)
        m.box("tie", (x, 9, 5.5), (x + 1, 11.5, 6), BLACK)
    m.box("cap", (0, 14, 6), (16, 15.5, 10), rail)
    return display(m, (1, 1, 1), 0)


# ================================================================================================
# 조명
# ================================================================================================

def andon():
    """등롱 (안돈): a paper floor lamp in a dark wooden frame with a carrying handle; the paper glows."""
    m = MModel("andon")
    m.group("frame")
    for (x, z) in ((4, 4), (11, 4), (4, 11), (11, 11)):
        m.box("post", (x, 0, z), (x + 1, 13.5, z + 1), DARK)
    for y in (2.5, 12.5):
        m.box("rail", (5, y, 4), (11, y + 1, 5), DARK)
        m.box("rail", (5, y, 11), (11, y + 1, 12), DARK)
        m.box("rail", (4, y, 5), (5, y + 1, 11), DARK)
        m.box("rail", (11, y, 5), (12, y + 1, 11), DARK)
    m.box("handle_bar", (5, 15, 7.5), (11, 16, 8.5), DARK)
    for x in (5, 10):
        m.box("handle_post", (x, 13.5, 7.5), (x + 1, 15, 8.5), DARK)
    m.group("light")
    m.box("paper", (5, 3.5, 5), (11, 12.5, 11), "jp_shoji_lit", glow=True)
    return display(m, (1, 1, 1), 0)


def stone_lantern():
    """석등 (도로) 1x2: a Kasuga stone lantern: base, pillar, platform, a fire box with glowing windows, a wide
    roof and the jewel on top."""
    m = MModel("stone_lantern")
    m.group("stone")
    cyl(m, "base", "y", (8, 8), 12, 0, 2, STONE_DARK, cap=STONE)
    cyl(m, "base2", "y", (8, 8), 8, 2, 3, STONE, cap=STONE)
    cyl(m, "pillar", "y", (8, 8), 5, 3, 11.5, STONE, cap=STONE)
    cyl(m, "platform", "y", (8, 8), 10, 11.5, 13.5, STONE, cap=STONE_LIGHT)
    # fire box: four corner posts around a glowing core
    for (x, z) in ((4, 4), (10, 4), (4, 10), (10, 10)):
        m.box("post", (x, 13.5, z), (x + 2, 20.5, z + 2), STONE)
    m.box("lintel", (4, 19.5, 6), (12, 20.5, 10), STONE)
    m.box("lintel", (6, 19.5, 4), (10, 20.5, 6), STONE)
    m.box("lintel", (6, 19.5, 10), (10, 20.5, 12), STONE)
    m.box("sill", (6, 13.5, 4), (10, 14.5, 6), STONE)
    m.box("sill", (6, 13.5, 10), (10, 14.5, 12), STONE)
    m.box("sill", (4, 13.5, 6), (6, 14.5, 10), STONE)
    m.box("sill", (10, 13.5, 6), (12, 14.5, 10), STONE)
    m.group("light")
    m.box("fire", (6, 14.5, 6), (10, 19.5, 10), Mat("glow_amber", "plain", 2, glow=True, bevel=(1, -1), noise=0.15))
    m.group("roof")
    cyl(m, "roof", "y", (8, 8), 15, 20.5, 22, STONE_DARK, cap=STONE)
    cyl(m, "roof2", "y", (8, 8), 11, 22, 23.5, STONE, cap=STONE_LIGHT)
    cyl(m, "roof3", "y", (8, 8), 6, 23.5, 24.5, STONE, cap=STONE_LIGHT)
    cyl(m, "jewel", "y", (8, 8), 3, 24.5, 27, STONE_LIGHT, cap=STONE_LIGHT)
    m.box("jewel_tip", (7.5, 27, 7.5), (8.5, 28, 8.5), STONE_LIGHT)
    m.group("moss")
    m.box("moss", (2, 22, 9), (5, 22.25, 12), MOSS)
    m.box("moss", (10, 2, 3), (13, 2.25, 5), MOSS)
    return display(m, (1, 2, 1), 0)


def chochin():
    """제등 (초롱): a red paper lantern with black caps, glowing; hangs from the top of its block."""
    m = MModel("chochin")
    m.group("lantern")
    m.box("hook", (7.5, 15, 7.5), (8.5, 16, 8.5), IRON)
    cyl(m, "cap", "y", (8, 8), 6, 13.5, 15, LACQUER, cap=LACQUER)
    # not glowing: its own light block (light 13) lights the paper, and the shading keeps it round
    top = Mat("vermilion", "plain", 3, bevel=None, noise=0)
    for (d, lo, hi) in ((8, 12, 13.5), (10, 4, 12), (8, 2.5, 4)):
        cyl(m, "paper", "y", (8, 8), d, lo, hi, "jp_chochin", cap=top)
    cyl(m, "bottom", "y", (8, 8), 6, 1.5, 2.5, LACQUER, cap=LACQUER)
    m.box("tassel", (7.5, 0, 7.5), (8.5, 1.5, 8.5), Mat("vermilion", "plain", 1, bevel=None, noise=0))
    return display(m, (1, 1, 1), 0)


# ================================================================================================
# 장식품 · 소품
# ================================================================================================

def bonsai():
    """분재: a twisted pine in a shallow glazed pot, with moss and cloud-shaped foliage pads."""
    m = MModel("bonsai")
    m.group("pot")
    m.box("pot", (2, 1, 4), (14, 4, 12), Mat("indigo", "plain", 2, noise=0.04))
    for (x, z) in ((2.5, 4.5), (12.5, 4.5), (2.5, 10.5), (12.5, 10.5)):
        m.box("foot", (x, 0, z), (x + 1, 1, z + 1), Mat("indigo", "plain", 1, noise=0))
    m.box("soil", (3, 4, 5), (13, 4.25, 11), SOIL)
    m.box("moss", (3.5, 4.25, 5.5), (12.5, 4.75, 10.5), MOSS)
    m.box("rock", (10, 4.75, 8), (12, 6, 10), STONE)
    m.group("trunk")
    tr = BARK
    m.box("trunk", (6, 4.75, 7), (8, 8, 9), tr)
    m.box("trunk", (7, 8, 7), (9, 10, 9), tr)
    m.box("trunk", (8, 10, 7.25), (9.75, 12.5, 8.75), tr)
    m.box("branch", (3.5, 9, 7.5), (7, 10, 8.5), tr)
    m.box("branch", (9.75, 11.5, 7.5), (12, 12.5, 8.5), tr)
    m.box("trunk", (7.5, 12.5, 7.5), (9, 14.5, 8.75), tr)
    m.group("foliage")
    m.box("pad", (1, 10, 5), (7, 12, 11), LEAF_DARK)
    m.box("pad", (2, 12, 6), (6, 12.75, 10), LEAF)
    m.box("pad", (9.5, 12.5, 5.5), (14.5, 14.25, 10.5), LEAF_DARK)
    m.box("pad", (10.5, 14.25, 6.5), (13.5, 15, 9.5), LEAF)
    m.box("pad", (5.5, 14.5, 5.5), (10.5, 16, 10.5), LEAF_DARK)
    return display(m, (1, 1, 1), 0)


def ikebana():
    """이케바나 꽃꽂이: a shallow celadon dish holding a tall camellia branch, red blooms and leaves."""
    m = MModel("ikebana")
    m.group("dish")
    cyl(m, "dish", "y", (8, 8), 11, 0, 1.5, CELADON, cap=CELADON)
    ring(m, "dish_rim", (8, 8), 11, 9, 1.5, 2.5, CELADON_DARK)
    m.box("water", (4, 1.5, 4), (12, 1.75, 12), Mat("water", "plain", 2, bevel=None, noise=0))
    m.group("stems")
    stem = Mat("leaf_dark", "plain", 1, bevel=None, noise=0)
    m.box("stem", (7.5, 1.75, 7.5), (8, 13, 8), stem)
    m.box("stem", (8.5, 1.75, 8.5), (9, 9, 9), stem)
    m.box("stem", (6.5, 1.75, 8.5), (7, 6.5, 9), stem)
    m.box("twig", (4, 12, 7.5), (7.5, 12.5, 8), BARK)
    m.box("twig", (3.5, 12.5, 7.5), (4.5, 15, 8), BARK)
    m.group("flowers")
    white = Mat("porcelain", "plain", 2, noise=0)
    red = Mat("vermilion", "plain", 3, noise=0)
    m.box("bloom", (6.75, 13, 6.75), (8.75, 14.75, 8.75), white)
    m.box("bloom_eye", (7.5, 14.75, 7.5), (8, 15, 8), Mat("gold", "plain", 2, bevel=None, noise=0))
    m.box("bloom", (7.75, 9, 7.75), (9.75, 10.75, 9.75), red)
    m.box("bloom", (5.75, 6.5, 7.75), (7.75, 8, 9.75), red)
    m.box("bud", (3.25, 15, 7.25), (4.75, 16, 8.25), Mat("sakura", "plain", 2, noise=0))
    m.group("leaves")
    for (x, y, z, w, d) in ((8, 5, 6, 3, 1.5), (5, 4, 9.5, 1.5, 3), (9, 11, 9, 2.5, 1.5), (5.5, 9.5, 6.5, 2, 1.5)):
        m.box("leaf", (x, y, z), (x + w, y + 0.5, z + d), LEAF)
    return display(m, (1, 1, 1), 0)


def bamboo_pot():
    """대나무 화분 1x2: a hinoki planter with three bamboo culms (nodes, leaf sprays)."""
    m = MModel("bamboo_pot")
    m.group("planter")
    m.box("planter", (3, 0, 3), (13, 6, 13), HINOKI)
    for y in (1, 4.5):
        m.box("band", (2.75, y, 2.75), (13.25, y + 0.75, 13.25), LACQUER)
    m.box("soil", (4, 6, 4), (12, 6.25, 12), SOIL)
    m.box("pebbles", (4.5, 6.25, 4.5), (8, 6.75, 8), STONE_LIGHT)
    m.group("culms")
    for (x, z, h) in ((5, 7, 30), (9, 5, 25), (8.5, 10, 21)):
        m.box("culm", (x, 6.25, z), (x + 1.5, h, z + 1.5), BAMBOO)
        y = 11.25
        while y < h - 2:
            m.box("node", (x - 0.25, y, z - 0.25), (x + 1.75, y + 0.5, z + 1.75), BAMBOO_DARK)
            y += 5.5
    m.group("leaves")
    for (x, y, z, w, d) in ((1, 28, 6.5, 4, 1), (6.5, 29, 7, 4, 1.5), (4.5, 26, 4.5, 1.5, 3),
                            (10.5, 23.5, 4.5, 4, 1), (8, 22, 2, 1.5, 3), (10, 19.5, 10.5, 4, 1.5), (7, 18, 11.5, 1.5, 3)):
        m.box("leaf", (x, y, z), (x + w, y + 0.5, z + d), LEAF)
    return display(m, (1, 2, 1), 0)


def sakura_pot():
    """벚꽃 화분 1x2: a small cherry tree in a glazed pot, clouds of blossom."""
    m = MModel("sakura_pot")
    m.group("pot")
    cyl(m, "pot", "y", (8, 8), 9, 0, 5, Mat("indigo", "plain", 2, noise=0.04), cap=SOIL)
    ring(m, "rim", (8, 8), 10, 8, 5, 6, Mat("indigo", "plain", 3, noise=0.04))
    m.box("soil", (5, 5, 5), (11, 5.5, 11), SOIL)
    m.group("tree")
    m.box("trunk", (7, 5.5, 7), (9, 11, 9), BARK)
    m.box("trunk", (7.5, 11, 7.5), (9, 15, 9), BARK)
    m.box("branch", (4, 13, 7.5), (7.5, 14, 8.5), BARK)
    m.box("branch", (9, 15, 7.5), (12, 16, 8.5), BARK)
    m.box("branch", (7.75, 15, 9), (8.75, 17, 11.5), BARK)
    m.group("blossom")
    for (x0, y0, z0, x1, y1, z1, mat) in ((1, 14, 4, 7, 18, 11, SAKURA), (9, 16, 3, 15, 20.5, 10, SAKURA),
                                          (4, 18, 6, 11, 23, 13, SAKURA_LIGHT), (2, 17.5, 9, 6, 20, 12.5, SAKURA),
                                          (10, 20.5, 6.5, 14, 22.5, 11, SAKURA_LIGHT), (6, 23, 7, 10, 24.5, 11, SAKURA_LIGHT)):
        m.box("blossom", (x0, y0, z0), (x1, y1, z1), mat)
    return display(m, (1, 2, 1), 0)


def tea_set():
    """다기 세트: a dark tray with a side-handled clay teapot and three green-tea cups."""
    m = MModel("tea_set")
    m.group("tray")
    m.box("tray", (2, 0, 3), (14, 0.75, 13), DARK_PLANKS)
    for (a, b) in (((2, 0.75, 3), (14, 1.5, 3.75)), ((2, 0.75, 12.25), (14, 1.5, 13)),
                   ((2, 0.75, 3.75), (2.75, 1.5, 12.25)), ((13.25, 0.75, 3.75), (14, 1.5, 12.25))):
        m.box("tray_rim", a, b, DARK)
    m.group("teapot")
    pot = Mat("onggi", "plain", 3, noise=0.05)
    cyl(m, "pot", "y", (6, 8), 5, 0.75, 4.25, pot, cap=pot)
    cyl(m, "lid", "y", (6, 8), 3, 4.25, 4.75, Mat("onggi", "plain", 2, noise=0), cap=Mat("onggi", "plain", 2, noise=0))
    m.box("knob", (5.5, 4.75, 7.5), (6.5, 5.25, 8.5), Mat("onggi", "plain", 1, noise=0))
    m.box("spout", (3, 2.25, 7.5), (3.5, 3.25, 8.5), pot)
    m.box("spout_tip", (2.5, 3, 7.5), (3, 3.75, 8.5), pot)
    m.box("handle", (6.5, 2, 3.5), (7.5, 3, 5.5), pot)
    m.group("cups")
    for (x, z) in ((10.5, 5), (11, 8.75), (8.75, 11)):
        cyl(m, "cup", "y", (x, z), 2, 0.75, 2.5, CELADON, cap=GREEN_TEA)
    return display(m, (1, 1, 1), 0)


def tea_kettle():
    """화로와 찻주전자: a cast-iron kettle with hobnails and an arched handle on a blue-and-white brazier
    with glowing charcoal."""
    m = MModel("tea_kettle")
    m.group("brazier")
    cyl(m, "brazier", "y", (8, 8), 13, 0.5, 6, "jp_hibachi")
    cyl(m, "brazier_foot", "y", (8, 8), 10, 0, 0.5, Mat("indigo", "plain", 2, noise=0))
    ring(m, "brazier_rim", (8, 8), 13, 10, 6, 7, Mat("indigo", "plain", 2, noise=0))
    m.box("ash", (4, 6, 4), (12, 6.25, 12), Mat("millstone", "speckle", 4, bevel=None))
    for (x, z) in ((4.5, 4.5), (9.5, 10), (5, 10), (10, 4.75)):
        m.box("ember", (x, 6.25, z), (x + 1.5, 7, z + 1.5), EMBER)
    m.box("trivet", (5.5, 6.25, 7.5), (10.5, 7.5, 8.5), IRON)
    m.group("kettle")
    kt = Mat("iron", "speckle", 3)
    cyl(m, "kettle", "y", (8, 8), 7, 7.5, 8.5, kt, cap=kt)
    cyl(m, "kettle", "y", (8, 8), 9, 8.5, 12, kt, cap=kt)
    cyl(m, "kettle_lid", "y", (8, 8), 4, 12, 12.75, Mat("iron", "plain", 2, noise=0), cap=Mat("iron", "plain", 3, noise=0))
    m.box("knob", (7.5, 12.75, 7.5), (8.5, 13.5, 8.5), BRASS)
    m.box("spout", (2.5, 9.5, 7.5), (3.5, 11.5, 8.5), kt)
    m.box("spout_tip", (2, 11, 7.5), (2.5, 12.5, 8.5), kt)
    for x in (3, 12.5):
        m.box("lug", (x, 11.5, 7.5), (x + 0.5, 13, 8.5), IRON)
    m.box("handle", (3, 13, 7.75), (3.5, 15, 8.25), IRON)
    m.box("handle", (12.5, 13, 7.75), (13, 15, 8.25), IRON)
    m.box("handle", (3, 15, 7.75), (13, 15.5, 8.25), IRON)
    return display(m, (1, 1, 1), 0)


def sake_set():
    """사케 병과 잔: two white tokkuri bottles with an indigo band and two cups on a red lacquer tray."""
    m = MModel("sake_set")
    m.group("tray")
    m.box("tray", (2.5, 0, 3.5), (13.5, 0.75, 12.5), LACQUER_RED)
    m.box("tray_rim", (2.5, 0.75, 3.5), (13.5, 1.25, 4), LACQUER_RED)
    m.box("tray_rim", (2.5, 0.75, 12), (13.5, 1.25, 12.5), LACQUER_RED)
    m.group("bottles")
    for (x, z, h) in ((5, 9.5, 0), (9.5, 6.5, 0.25)):
        cyl(m, "bottle", "y", (x, z), 4, 0.75, 3.5 + h, WHITE)
        cyl(m, "bottle_band", "y", (x, z), 4, 3.5 + h, 4.5 + h, Mat("indigo", "plain", 2, noise=0))
        cyl(m, "bottle", "y", (x, z), 3, 4.5 + h, 6 + h, WHITE)
        cyl(m, "bottle_neck", "y", (x, z), 1.5, 6 + h, 8 + h, WHITE)
        cyl(m, "bottle_lip", "y", (x, z), 2, 8 + h, 8.5 + h, WHITE, cap=Mat("porcelain", "plain", 0, bevel=None, noise=0))
    m.group("cups")
    for (x, z) in ((11.5, 10.25), (5, 5.5)):
        cyl(m, "cup", "y", (x, z), 2, 0.75, 1.75, WHITE, cap=Mat("rice", "plain", 1, bevel=None, noise=0))
    return display(m, (1, 1, 1), 0)


def taiko_drum():
    """다이코 북 1x2: a barrel drum lying on a dark wooden stand, tacked skin heads with the three-comma
    tomoe, and two drumsticks."""
    m = MModel("taiko_drum")

    def head(dx, dy, r):
        if r > 1.0:
            return (0, 0, 0, 0)
        if r > 0.86:
            return ramp("brass", 2) if (int((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * 20) % 2 == 0) else ramp("cream", 1)
        # a red ring with three round dots inside it (a simple crest that reads at 16 px)
        if 0.5 < r < 0.64:
            return ramp("vermilion", 1)
        for k in range(3):
            a = math.radians(90 + 120 * k)
            if math.hypot(dx - 0.24 * math.cos(a), dy - 0.24 * math.sin(a)) < 0.17:
                return ramp("vermilion", 1)
        return ramp("cream", 2 if dx + dy < 0 else 1)

    skin = _disc_pic("jp_taiko_head", 13, head)
    m.group("drum")
    cyl(m, "body", "z", (8, 14), 14, 3, 13, Mat("vermilion", "planks", 2, period=4), cap=skin)
    for z in (3.5, 11.75):
        cyl(m, "tacks", "z", (8, 14), 14.5, z, z + 0.75, BRASS)
    cyl(m, "skin_rim", "z", (8, 14), 13, 2.5, 3, Mat("cream", "plain", 1, noise=0), cap=skin)
    cyl(m, "skin_rim", "z", (8, 14), 13, 13, 13.5, Mat("cream", "plain", 1, noise=0), cap=skin)
    m.group("stand")
    for x in (0, 14.5):
        m.box("foot", (x, 0, 2), (x + 1.5, 1.5, 14), DARK)
        m.box("leg", (x, 1.5, 7.25), (x + 1.5, 14, 8.75), DARK)
        m.box("brace", (x, 5, 4), (x + 1.5, 6, 7.25), DARK)
        m.box("brace", (x, 5, 8.75), (x + 1.5, 6, 12), DARK)
    m.box("cradle", (1.5, 6, 7.25), (14.5, 7, 8.75), DARK)
    m.group("sticks")
    m.box("bachi", (0.25, 14, 3), (0.75, 14.5, 13), HINOKI_GRAIN)
    m.box("bachi", (15.25, 14, 3), (15.75, 14.5, 13), HINOKI_GRAIN)
    return display(m, (1, 1.4, 1), 0)


def maneki_neko():
    """마네키네코: a white calico cat sitting with its paw raised, a red collar with a gold bell, a gold coin."""
    m = MModel("maneki_neko")

    def face(px):
        for y in range(7):
            for x in range(9):
                px[x, y] = ramp("feather", 2)
        for (x, y) in ((2, 2), (6, 2)):                      # eyes
            px[x, y] = ramp("rubber", 1)
        px[4, 3] = ramp("pink", 1)                           # nose
        px[4, 4] = ramp("feather", 0)                        # mouth
        for (x, y) in ((1, 4), (7, 4)):
            px[x, y] = ramp("pink", 3)                       # cheeks

    white = Mat("feather", "plain", 2, noise=0.03)
    m.group("body")
    m.box("base", (3.5, 0, 4.5), (12.5, 1, 12.5), Mat("feather", "plain", 1, noise=0))
    m.box("body", (4, 1, 5), (12, 7, 12), white)
    m.box("patch", (9, 3.5, 12), (12, 6.5, 12.25), Mat("fox", "plain", 3, bevel=None, noise=0))
    m.box("tail", (10, 1, 12), (12.5, 2.5, 13.5), white)
    m.group("head")
    m.box("head", (3.5, 7, 4), (12.5, 14, 11), white, faces={"north": picture("jp_neko_face", 9, 7, face)[0]},
          uv={"north": [0, 0, 9, 7]})
    m.box("patch", (9, 13, 5), (11.5, 14.25, 9), Mat("fox", "plain", 3, bevel=None, noise=0))
    m.box("patch", (4.5, 14, 7), (6.5, 14.25, 10), Mat("rubber", "plain", 2, bevel=None, noise=0))
    for x in (4, 10):
        m.box("ear", (x, 14, 6), (x + 2, 15.25, 8), white, faces={"north": Mat("pink", "plain", 2, bevel=None, noise=0)})
        m.box("ear_tip", (x + 0.5, 15.25, 6.5), (x + 1.5, 16, 7.5), white)
    m.group("collar")
    m.box("collar", (3.75, 6, 4.75), (12.25, 6.75, 11.75), Mat("vermilion", "plain", 2, noise=0))
    m.box("bell", (7.25, 4.75, 4), (8.75, 6.25, 4.75), Mat("gold", "metal", 3))
    m.group("paws")
    m.box("paw_up", (12.5, 8, 5.5), (14.5, 13.5, 7.5), white)
    m.box("paw_pad", (12.75, 12, 5.25), (14.25, 13.25, 5.5), PINK)
    m.box("paw", (5, 1, 3.5), (7, 3, 5), white)
    m.box("coin", (8, 1, 3.75), (11, 5.5, 4.25), Mat("gold", "metal", 2))
    m.box("coin_line", (9, 2, 3.5), (10, 4.5, 3.75), Mat("gold", "plain", 1, bevel=None, noise=0))
    return display(m, (1, 1, 1), 0)


def daruma():
    """다루마: a round red tumbling doll, white face with one eye painted in (a wish not yet granted),
    gold lettering on the belly."""
    m = MModel("daruma")

    def face(px):
        art = ["RRWWWRR",
               "RKWWWKR",      # brows
               "WEWWWOW",      # one eye painted in (E), the other still blank (O)
               "WWWPWWW",
               "WWMMMWW",      # beard
               "RWWMWWR",
               "RRRRRRR",
               "RRGGGRR"]      # gold lettering
        col = {"R": ramp("vermilion", 2), "W": ramp("porcelain", 2), "K": ramp("rubber", 1), "E": ramp("rubber", 0),
               "O": ramp("porcelain", 1), "P": ramp("pink", 1), "G": ramp("gold", 2), "M": ramp("millstone", 1)}
        for y, row in enumerate(art):
            for x, ch in enumerate(row):
                px[x, y] = col[ch]

    red = Mat("vermilion", "plain", 2, noise=0.03)
    m.group("doll")
    m.box("bottom", (4.5, 0, 4.5), (11.5, 1, 11.5), red)
    m.box("core", (4, 1, 4), (12, 9, 12), red)
    m.box("bulge_x", (3, 1.5, 5), (4, 8.5, 11), red)
    m.box("bulge_x", (12, 1.5, 5), (13, 8.5, 11), red)
    m.box("bulge_z", (5, 1.5, 12), (11, 8.5, 13), red)
    flat(m, "face", (4.5, 1.5, 3), (11.5, 9.5, 4), red, picture("jp_daruma_face", 7, 8, face))
    m.box("top", (5, 9, 5), (11, 10.5, 11), red)
    m.box("crown", (6.5, 10.5, 6.5), (9.5, 11.25, 9.5), red)
    m.box("trim", (4, 0.5, 3.75), (12, 1, 4), Mat("gold", "plain", 2, bevel=None, noise=0))
    return display(m, (1, 1, 1), 0)


def omamori_rack():
    """오마모리 걸이: a small roofed wooden stand with charms in red, blue, white and pink hanging on cords."""
    m = MModel("omamori_rack")
    m.group("stand")
    for x in (1.5, 13):
        m.box("foot", (x - 0.5, 0, 5), (x + 2, 1, 11), DARK)
        m.box("post", (x, 1, 7.25), (x + 1.5, 12.5, 8.75), DARK)
    m.box("bar", (3, 10, 7.5), (13, 11, 8.5), HINOKI_GRAIN)
    m.box("roof", (0, 12.5, 6), (16, 13.5, 10), DARK_PLANKS)
    m.box("roof_ridge", (0.5, 13.5, 7), (15.5, 14.5, 9), DARK_PLANKS)
    m.box("roof_cap", (1, 14.5, 7.5), (15, 15, 8.5), LACQUER)
    m.group("charms")
    for k, (x, ramp_name) in enumerate(((3.75, "vermilion"), (6.25, "indigo"), (8.75, "porcelain"), (11.25, "sakura"))):
        mat = Mat(ramp_name, "plain", 2, noise=0)
        m.box("cord", (x + 0.75, 8.5, 7.75), (x + 1.25, 10, 8.25), Mat("gold", "plain", 2, bevel=None, noise=0))
        top = 8.5 - (k % 2) * 0.5
        m.box("charm", (x, top - 4, 7.5), (x + 2, top, 8.5), mat)
        m.box("charm_label", (x + 0.5, top - 2.5, 7.25), (x + 1.5, top - 0.5, 7.5), Mat("gold", "plain", 2, bevel=None, noise=0))
    return display(m, (1, 1, 1), 0)


def wind_chime():
    """풍경: a glass bell painted with goldfish, a clapper and a paper strip; hangs from the top of its block."""
    m = MModel("wind_chime")
    m.group("chime")
    m.box("hook", (7.75, 14.5, 7.75), (8.25, 16, 8.25), IRON)
    glass = Mat("glass", "plain", 2, noise=0)
    m.box("knob", (7.25, 13.5, 7.25), (8.75, 14.5, 8.75), glass)
    cyl(m, "bell", "y", (8, 8), 3, 12, 13.5, glass, cap=glass)
    cyl(m, "bell", "y", (8, 8), 5, 9.5, 12, "jp_furin", cap=glass)
    m.box("string", (7.75, 6, 7.75), (8.25, 9.5, 8.25), Mat("cream", "plain", 1, bevel=None, noise=0))
    m.box("clapper", (7.5, 8.5, 7.5), (8.5, 9.25, 8.5), glass)
    m.box("tanzaku", (7, 1.5, 7.875), (9, 6, 8.125), Mat("sakura", "plain", 3, noise=0),
          faces={"north": Mat("sakura", "panel", 3, noise=0)})
    return display(m, (1, 1, 1), 0)


def wagasa():
    """우산 (노다테 가사) 1x3: a big red garden parasol on a bamboo pole in a stone foot; stand under it
    (its canopy reaches a block out on every side)."""
    m = MModel("wagasa", dy=-16)

    def under(dx, dy, r):
        if r > 1.0:
            return (0, 0, 0, 0)
        a = math.atan2(dy, dx)
        rib = (a / (2 * math.pi) * 24) % 1.0 < 0.18
        if r < 0.12:
            return ramp("bamboo", 1)
        if r < 0.2:
            return ramp("rubber", 2)
        return ramp("bamboo", 2) if rib else ramp("vermilion", 1 if r > 0.85 else 2)

    under_tex = _disc_pic("jp_wagasa_under", 32, under)
    m.group("pole")
    cyl(m, "foot", "y", (8, 8), 7, 0, 2, STONE_DARK, cap=STONE)
    cyl(m, "foot2", "y", (8, 8), 4, 2, 3, STONE, cap=STONE)
    m.box("pole", (7.25, 3, 7.25), (8.75, 34, 8.75), BAMBOO)
    for y in (12, 22):
        m.box("node", (7, y, 7), (9, y + 0.5, 9), BAMBOO_DARK)
    m.group("canopy")
    canopy = Mat("vermilion", "plain", 2)
    light = Mat("vermilion", "plain", 3)
    cyl(m, "canopy", "y", (8, 8), 32, 34, 35, canopy, cap=light, cap2=under_tex)
    cyl(m, "canopy", "y", (8, 8), 26, 35, 36.5, canopy, cap=light)
    cyl(m, "canopy", "y", (8, 8), 18, 36.5, 38, canopy, cap=light)
    cyl(m, "canopy", "y", (8, 8), 10, 38, 39.5, canopy, cap=light)
    cyl(m, "top", "y", (8, 8), 4, 39.5, 41, LACQUER, cap=LACQUER)
    ring(m, "ring", (8, 8), 22, 20, 36.5, 36.75, Mat("porcelain", "plain", 2, bevel=None, noise=0))
    return display(m, (2, 2.6, 2), -16)


def kimono_rack():
    """기모노 걸이 (이코) 2x2: a black lacquer T-frame with a kimono spread across it: indigo with cherry
    blossoms, a white collar and a red obi."""
    m = MModel("kimono_rack")
    W, H = 30, 24
    art = Image.new("RGBA", (W, H))
    px = art.load()
    rnd = random.Random(631)
    for y in range(H):
        for x in range(W):
            px[x, y] = ramp("indigo", 2 if (x + y) % 9 else 1)
    for _ in range(18):
        cx, cy = rnd.randrange(1, W - 1), rnd.randrange(1, H - 1)
        for (dx, dy) in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            px[cx + dx, cy + dy] = ramp("sakura", 3 if (dx, dy) == (0, 0) else 1)
    for y in range(12):                     # the collar: a white V, two pixels wide, from the neck to the obi
        for x in (11 + y // 3, 12 + y // 3, 17 - y // 3, 18 - y // 3):
            px[x, y] = ramp("porcelain", 2 if x < 15 else 1)
    for y in range(12, 15):                 # obi
        for x in range(8, 22):
            px[x, y] = ramp("vermilion", 2 if y != 13 else 3)
    m.group("frame")
    for x in (2, 28.5):
        m.box("foot", (x - 1.5, 0, 4), (x + 3, 1.5, 12), LACQUER)
        m.box("post", (x, 1.5, 7.25), (x + 1.5, 28, 8.75), LACQUER)
    m.box("top_bar", (0, 28, 7), (32, 29.5, 9), LACQUER)
    m.box("top_bar_end", (0, 29.5, 7), (2, 30, 9), LACQUER)
    m.box("top_bar_end", (30, 29.5, 7), (32, 30, 9), LACQUER)
    m.box("low_bar", (3.5, 9, 7.5), (28.5, 10, 8.5), LACQUER)
    m.group("kimono")
    back = "jp_kimono_back"
    T.SURFACES[back] = lambda: Image.new("RGBA", (S, S), ramp("indigo", 1))
    # sleeves and shoulders (y 16..28), then the body (y 4..16), cut from the picture into <= 16 px faces
    pieces = [(1, 16, 16, 28), (16, 16, 31, 28), (7, 4, 16, 16), (16, 4, 25, 16)]
    for k, (x0, y0, x1, y1) in enumerate(pieces):
        # model +x is the viewer's left: the picture's column 0 is at model x 31
        src = _cut(f"jp_kimono_{k}", art, int(31 - x1), int(28 - y1), int(x1 - x0), int(y1 - y0))
        flat(m, "cloth", (x0, y0, 5.5), (x1, y1, 6.5), INDIGO, src, (back, [0, 0, x1 - x0, y1 - y0]))
    return display(m, (2, 2, 1), 0)


def torii():
    """도리이 3x3: a small vermilion shrine gate with black top beam and name plaque; walk through the middle."""
    m = MModel("torii", dy=-16)
    red, black = VERMILION, LACQUER
    m.group("posts")
    for cx in (-10, 26):
        cyl(m, "post", "y", (cx, 8), 5, 2, 38, red, cap=red)
        cyl(m, "post_foot", "y", (cx, 8), 6, 0, 2, black, cap=black)
    m.group("beams")
    m.box("nuki", (-16, 28, 7), (-12.5, 30.5, 9), red)
    m.box("nuki", (-7.5, 28, 7), (23.5, 30.5, 9), red)
    m.box("nuki", (28.5, 28, 7), (32, 30.5, 9), red)
    m.box("shimaki", (-15, 38, 6), (31, 40, 10), red)
    m.box("kasagi", (-16, 40, 5.5), (32, 42.5, 10.5), black)
    m.box("kasagi_end", (-16, 42.5, 5.5), (-12, 43.5, 10.5), black)
    m.box("kasagi_end", (28, 42.5, 5.5), (32, 43.5, 10.5), black)
    m.group("plaque")
    m.box("strut", (6.5, 30.5, 7.25), (9.5, 32, 8.75), red)
    m.box("strut", (6.5, 37, 7.25), (9.5, 38, 8.75), red)

    def plaque(px):
        for y in range(5):
            for x in range(4):
                px[x, y] = ramp("rubber", 2)
        for (x, y) in ((1, 1), (2, 1), (1, 3), (2, 3), (2, 2)):
            px[x, y] = ramp("gold", 2)
    flat(m, "gaku", (6, 32, 6.5), (10, 37, 9.5), black, picture("jp_torii_plaque", 4, 5, plaque),
         picture("jp_torii_plaque", 4, 5, plaque))
    return display(m, (3, 3, 1), -16)


def stepping_stones():
    """징검돌: flat garden stones to make a path (walk over them)."""
    m = MModel("stepping_stones")
    m.group("stones")
    m.box("stone", (2, 0, 3), (11, 1, 11), STONE)
    m.box("stone_top", (3, 1, 4), (10, 1.5, 10), STONE_LIGHT)
    m.box("stone", (11, 0, 10), (15, 0.75, 14), STONE)
    m.box("stone", (1, 0, 12), (4, 0.75, 15), STONE_DARK)
    return display(m, (1, 1, 1), 0)


def koi_pond():
    """잉어 연못 2x2: a stone-rimmed pond with rippling water, two koi near the surface, lily pads and a flower."""
    m = MModel("koi_pond")
    m.group("rim")
    m.box("rim", (0, 0, 0), (32, 3, 3), STONE)
    m.box("rim", (0, 0, 29), (32, 3, 32), STONE)
    m.box("rim", (0, 0, 3), (3, 3, 29), STONE)
    m.box("rim", (29, 0, 3), (32, 3, 29), STONE)
    m.box("rock", (23, 3, 1), (29, 5, 6), STONE_LIGHT)
    m.box("rock", (1, 3, 25), (5, 4.5, 30), STONE_DARK)
    m.box("moss", (24, 5, 2), (27, 5.25, 5), MOSS)
    m.group("water")
    m.box("water", (3, 0, 3), (29, 2, 29), "jp_water", faces={"north": None, "south": None, "east": None, "west": None})
    m.group("koi")
    white = Mat("porcelain", "plain", 2, noise=0, bevel=None)
    orange = Mat("fox", "plain", 3, noise=0, bevel=None)
    # koi 1 swimming along x (kohaku: white with orange), koi 2 along z (orange with a white head)
    m.box("koi", (8, 2, 9), (14, 2.5, 11), white)
    m.box("koi_spot", (9.5, 2.5, 9.5), (12, 2.75, 10.5), orange)
    m.box("koi_tail", (5.5, 2, 8.5), (8, 2.25, 11.5), orange)
    m.box("koi", (19, 2, 15), (21, 2.5, 21), orange)
    m.box("koi_head", (19, 2, 21), (21, 2.5, 22.5), white)
    m.box("koi_tail", (18.5, 2, 12.5), (21.5, 2.25, 15), orange)
    m.group("lilies")
    pad = Mat("leaf", "plain", 2, noise=0.05, bevel=(1, -1))
    for (x, z, d) in ((22, 22, 5), (9, 20, 4), (16, 6, 3)):
        cyl(m, "pad", "y", (x, z), d, 2, 2.25, pad, cap=pad)
    m.box("flower", (21, 2.25, 21), (23, 3.25, 23), Mat("sakura", "plain", 2, noise=0))
    m.box("flower_eye", (21.75, 3.25, 21.75), (22.25, 3.5, 22.25), Mat("gold", "plain", 2, bevel=None, noise=0))
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
    "futon": (futon, (1, 1, 2), 0),
    "shoji_screen": (shoji_screen, (2, 2, 1), 0),
    "fusuma_panel": (fusuma_panel, (2, 2, 1), 0),
    "hanging_scroll": (hanging_scroll, (1, 2, 1), 0),
    "noren": (noren, (1, 1, 1), 0),
    "bamboo_fence": (bamboo_fence, (1, 1, 1), 0),
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
    "torii": (torii, (3, 3, 1), -16),
    "stepping_stones": (stepping_stones, (1, 1, 1), 0),
    "koi_pond": (koi_pond, (2, 1, 2), 0),
}

ALL = {k: v[0] for k, v in PIECES.items()}
SIZES = {k: v[1] for k, v in PIECES.items()}
