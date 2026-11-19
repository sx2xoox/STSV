"""
장난감 시리즈 (toy series): 3D looks for held tools, and the party firework launcher.

    toy_sword  toy_pickaxe  toy_shovel  toy_axe  toy_hoe  toy_fishing_rod (+ toy_fishing_rod_cast)  party_firework

Item models "stsv:<name>" (content/stsv/assets/stsv/items/<name>.json, written by item_definitions() below through
build.py): an item whose item_model component is that key shows the model in the hand, in the inventory, on the ground
and in an item frame. The fishing rod's definition picks the cast look by minecraft:fishing_rod/cast, like vanilla's.

Frame: every model is built UPRIGHT (the handle along +y, x = 8 and z = 8 its axis), not on the sprite's diagonal. The
45 degree tilt of a vanilla tool sprite (handle bottom left, head top right) is put into the display transforms instead:
a display rotation is Rx * Ry * Rz about the model's centre (8, 8, 8), so turning the model by Rz(-45) first is the same
as taking 45 off the transform's z rotation (right hand; the left hand's values are mirrored when applied, so +45
there). That leaves the element rotations free for real shapes (the pickaxe's arms, the rod's line) and keeps every
other box on the axes, where the validator and the face reducer can see them.

    vanilla item/handheld, third person right hand   [0, -90, 55]  ->  upright  [0, -90, 10]
    vanilla item/handheld_rod                        [0,  90, 55]  ->  upright  [0,  90, 10]

Size: toys are bigger than the 16 x 16 sprite they replace (a sword's blade ends 25 units above the grip, a sprite's
15). The hand stays on the grip because the grip is where a sprite's is, (8, 2.25) in this frame = (3.9, 3.9) on the
sprite, and each view's scale is changed about that point (presets.enlarged). SIZES holds the numbers a kind.

Looks: glossy primary-colour plastic (Mat "metal": a highlight in each face's top left corner), a blue ribbed grip with
a red pommel and a yellow collar on every piece, a yellow shaft, a white sticker with a red star, foam edges and tips.
One texel a model unit everywhere (the stickers too), so nothing is finer or coarser than the rest.
"""

import copy
import json
import math
from pathlib import Path

from PIL import Image

import economy_models  # noqa: F401  (registers the shared surfaces the materials may use)
from economy_machines import MModel, Mat, cyl
from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.palette import ramp

# plastic: one flat colour, a glint in each face's top left corner ("metal"), a darker line along its bottom and right
# (no light line: the ramps' lightest steps are pale, and a pale edge on every box reads as wood, not as gloss)
def _plastic(name, base):
    return Mat(name, "metal", base, bevel=(0, -1), noise=0)


def _matte(name, base):
    """The same plastic without the glint: the handle's small parts, where a glint a box is only noise."""
    return Mat(name, "plain", base, bevel=(0, -1), noise=0)


RED = _plastic("tomato", 2)
RED_DARK = _plastic("tomato", 1)
YELLOW = _plastic("corn", 2)
YELLOW_DARK = _plastic("corn", 1)
BLUE = _plastic("sapphire", 2)
BLUE_DARK = _plastic("sapphire", 1)
BLUE_LIGHT = Mat("sapphire", "plain", 3, bevel=(0, -1), noise=0)
GREEN = _plastic("slime", 1)
GREEN_LIGHT = _plastic("slime", 2)
ORANGE = _plastic("fox", 3)
WHITE = Mat("feather", "plain", 3, bevel=(0, -1), noise=0)
FOAM = Mat("porcelain", "plain", 2, bevel=(0, -1), noise=0.06)          # soft foam: the sword's blade, the edges and tips
FOAM_DARK = Mat("porcelain", "plain", 1, bevel=(0, -1), noise=0.06)
ICE = Mat("ice", "plain", 1, bevel=(0, -1), noise=0)             # the sword's ridge
DARK = Mat("rubber", "plain", 2, noise=0.04)   # a hollow's inside
LINE = Mat("feather", "plain", 2, bevel=None, noise=0)
GRIP_RED = _matte("tomato", 2)
GRIP_RED_DARK = _matte("tomato", 1)
GRIP_BLUE = _matte("sapphire", 2)
GRIP_YELLOW = _matte("corn", 2)

C = 8.0                 # the handle's axis (x and z)
GRIP_Y = 2.25           # where the hand closes: a sprite's grip (3.9, 3.9) in this frame
GRIP = (0.0, GRIP_Y - 8.0, 0.0)   # relative to the model's centre


# ================================================================================================
# Surfaces of their own: the sticker, the rockets' paper
# ================================================================================================

STAR = (".........",
        "....#....",
        "....#....",
        ".#######.",
        "..#####..",
        "...###...",
        "..##.##..",
        "..#...#..",
        ".........")
STICKER = 4.5      # the sticker's side (model units): printed twice as fine as the plastic, 9 x 9 texels


def _sticker():
    """A white sticker with a red star, in the texture's top left corner; a grey line along its bottom and right."""
    im = Image.new("RGBA", (16, 16), ramp("feather", 3))
    px = im.load()
    for y, row in enumerate(STAR):
        for x, ch in enumerate(row):
            px[x, y] = ramp("tomato", 2) if ch == "#" else ramp("feather", 1) if x == 8 or y == 8 else ramp("feather", 3)
    return im


def _stripes(a, b):
    """Paper stripes two texels high, a shade darker down the right edge."""
    def make():
        im = Image.new("RGBA", (16, 16))
        px = im.load()
        for y in range(16):
            name, i = a if (y // 2) % 2 == 0 else b
            for x in range(16):
                px[x, y] = ramp(name, i)
        return im
    return make


T.SURFACES["toy_sticker"] = _sticker
T.SURFACES["toy_stripes_red"] = _stripes(("tomato", 2), ("feather", 3))
T.SURFACES["toy_stripes_green"] = _stripes(("slime", 1), ("feather", 3))
T.SURFACES["toy_stripes_blue"] = _stripes(("sapphire", 2), ("feather", 3))
STICKER_UV = [0, 0, 9, 9]


# ================================================================================================
# Shapes
# ================================================================================================

def round_y(m, name, x0, x1, z0, z1, y0, y1, mat, cut, top=None, bottom=None):
    """A post along y with its four long edges cut off (an octagon from above): three boxes that do not overlap."""
    f = {}
    if top is not None:
        f["up"] = top
    if bottom is not None:
        f["down"] = bottom
    m.box(name, (x0, y0, z0 + cut), (x1, y1, z1 - cut), mat, faces=f or None)
    m.box(name, (x0 + cut, y0, z0), (x1 - cut, y1, z0 + cut), mat, faces=f or None)
    m.box(name, (x0 + cut, y0, z1 - cut), (x1 - cut, y1, z1), mat, faces=f or None)


def round_z(m, name, x0, x1, y0, y1, z0, z1, mat, cut, front=None):
    """A drum along z with its edges cut off (an octagon from the front)."""
    f = {"north": front, "south": front} if front is not None else None
    m.box(name, (x0, y0 + cut, z0), (x1, y1 - cut, z1), mat, faces=f)
    m.box(name, (x0 + cut, y0, z0), (x1 - cut, y0 + cut, z1), mat, faces=f)
    m.box(name, (x0 + cut, y1 - cut, z0), (x1 - cut, y1, z1), mat, faces=f)


def sticker(m, x0, y0, z0, z1):
    """The series' sticker on the front and the back: one plate through the part, a quarter unit proud of it."""
    m.box("sticker", (x0, y0, z0), (x0 + STICKER, y0 + STICKER, z1), WHITE, faces={"north": "toy_sticker", "south": "toy_sticker"},
          uv={"north": STICKER_UV, "south": STICKER_UV})


def _pommel(m):
    m.box("pommel_nub", (7, -4, 7), (9, -3.25, 9), GRIP_RED_DARK)
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.25, -1.25, GRIP_RED, 0.75)


def handle(m, pommel=True):
    """What every piece of the series shares: a red pommel, a blue grip with three ribs, a yellow collar (y -4 .. 6.5).
    pommel=False: the piece ends its handle its own way (the shovel's D)."""
    m.group("handle")
    if pommel:
        _pommel(m)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1.25, 5.25, GRIP_BLUE, 0.5)
    for y in (-0.25, 1.75, 3.75):
        m.box("grip_rib", (6.5, y, 6.5), (9.5, y + 0.5, 9.5), BLUE_LIGHT)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, GRIP_YELLOW, 1)


def shaft(m, top, mat=GRIP_YELLOW):
    """The shaft above the collar, up to y = top."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, mat, 0.5)


# ================================================================================================
# The premium finish (the toy series is a donation set: it has to read as more than the gold sets)
# ================================================================================================

class Gloss(Mat):
    """Mat with two finishes of its own, painted per face like every Mat:
    "gloss": lacquered plastic or plated metal: the corner glint, a hot white dot in it, a thin second reflection
             running across the face, a lit rim along the top edge (a darker line along the bottom and right).
    "seam":  sculpted foam: a soft speckle and a stitched seam a texel in from each long edge (only on faces wide
             enough to hold two)."""

    def __init__(self, ramp_name, base, kind="gloss", bevel=(0, -1), noise=0, glow=False, **opt):
        super().__init__(ramp_name, kind, base, bevel, noise, glow, None, **opt)

    def __call__(self, **changes):
        m = copy.copy(self)
        m.opt = dict(self.opt)
        for k, v in changes.items():
            if hasattr(m, k) and k != "opt":
                setattr(m, k, v)
            else:
                m.opt[k] = v
        return m

    def _index(self, w, h, rnd):
        b, S = self.base, 16
        grid = [[b] * S for _ in range(S)]
        if self.kind == "gloss":
            short = min(w, h)
            for y in range(S):
                for x in range(S):
                    if x + y < max(1, short * 0.45):
                        grid[y][x] = b + 1
            if short >= 3:
                grid[1][1] = b + 2
            if short >= 6:                       # the second reflection: a thin diagonal across the face
                c = round(short * 0.45) + 2
                for y in range(1, h - 1):
                    x = c - y
                    if 1 <= x < w - 1:
                        grid[y][x] = b + 1
            if h >= 3:                           # the lit rim
                for x in range(1, max(2, (w * 2) // 3)):
                    grid[0][x] = max(grid[0][x], b + 1)
            return grid
        if self.kind == "seam":
            for y in range(S):
                for x in range(S):
                    r = rnd.random()
                    grid[y][x] = b - 1 if r < 0.05 else b
            if w >= 5 and h >= 5:
                if h >= w:
                    for y in range(1, h - 1, 2):
                        grid[y][1] = grid[y][w - 2] = b - 1
                else:
                    for x in range(1, w - 1, 2):
                        grid[1][x] = grid[h - 2][x] = b - 1
            return grid
        return super()._index(w, h, rnd)


P_RED = Gloss("tomato", 2)
P_RED_DARK = Gloss("tomato", 1)
P_YELLOW = Gloss("corn", 2)
P_BLUE = Gloss("sapphire", 2)
P_GREEN = Gloss("slime", 1)
P_GREEN_LIGHT = Gloss("slime", 2)
P_ORANGE = Gloss("fox", 2)
P_WHITE = Gloss("feather", 2)
GOLD_TRIM = Gloss("gold", 1, bevel=(1, -1))         # gold plating: collars, rings, settings, caps
CHROME = Gloss("steel", 4, bevel=(1, -1))           # chrome bands
P_FOAM = Gloss("porcelain", 2, kind="seam", bevel=(0, -1))
P_FOAM_EDGE = Gloss("porcelain", 2, kind="seam", bevel=(1, -1))
CRYSTAL = Gloss("ice", 1, glow=True)                # the sword's light-up crystal ridge
GEM = Gloss("spectral", 2, bevel=(1, -1), glow=True)        # the pommel's jewel: glows (light_emission, unshaded)
GEM_RED = Gloss("ruby", 2, bevel=(1, -1), glow=True)
STAR_GLOW = Mat("glow_amber", "plain", 1, bevel=None, noise=0, glow=True)   # the charm's star


HOLO = ((255, 226, 240), (222, 244, 255), (238, 228, 255), (226, 255, 238))


def _star_pixels():
    return {(x, y) for y, row in enumerate(STAR) for x, ch in enumerate(row) if ch == "#"}


def _sticker_holo():
    """The premium sticker: holographic foil in pale rainbow bands with a fleck of glitter, framed in gold (lit top and
    left), the series' red star on it (the star plate over it glows)."""
    im = Image.new("RGBA", (16, 16), HOLO[0] + (255,))
    px = im.load()
    star = _star_pixels()
    for y in range(9):
        for x in range(9):
            c = HOLO[((x + y) // 2) % len(HOLO)] + (255,)
            if (x, y) in ((2, 6), (6, 2)):
                c = (255, 255, 255, 255)
            if x == 0 or y == 0:
                c = ramp("gold", 2)
            if x == 8 or y == 8:
                c = ramp("gold", 1)
            if (x, y) in star:
                c = ramp("tomato", 2)
            px[x, y] = c
    return im


def _sticker_star():
    """The star alone, everything else clear: a plate over the sticker that glows (a lit top left on the star), with
    two white glints beside it."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    for x, y in _star_pixels():
        px[x, y] = ramp("tomato", 2)
    for x, y in ((4, 1), (4, 2), (2, 3), (3, 3), (4, 3)):
        px[x, y] = ramp("tomato", 3)
    for x, y in ((1, 1), (7, 6)):
        px[x, y] = (255, 255, 255, 255)
    return im


T.SURFACES["toy_sticker_holo"] = _sticker_holo
T.SURFACES["toy_sticker_star"] = _sticker_star


def _star_plate(m, face, frm, to):
    """The glowing star over a sticker face: a plate an eighth of a unit thick laid on it, drawn only outwards (its sides
    show a clear corner of the texture, its back lies on the sticker)."""
    axis, sign = {"north": (2, -1), "south": (2, 1), "west": (0, -1), "east": (0, 1)}[face]
    f2, t2 = list(frm), list(to)
    if sign > 0:
        f2[axis], t2[axis] = to[axis], to[axis] + 0.125
    else:
        f2[axis], t2[axis] = frm[axis] - 0.125, frm[axis]
    back = {"north": "south", "south": "north", "west": "east", "east": "west"}[face]
    clear = [12, 12, 13, 13]
    m.box("sticker_star", f2, t2, "toy_sticker_star", glow=True, faces={back: None},
          uv={k: (STICKER_UV if k == face else clear) for k in FACES6 if k != back})


def holo_sticker(m, x0, y0, z0, z1):
    """The premium sticker on the front and the back (sticker()'s place and size), and its star again on a glowing
    plate laid on each face: the foil stays lit by the world, the star shines."""
    frm, to = (x0, y0, z0), (x0 + STICKER, y0 + STICKER, z1)
    m.box("sticker", frm, to, P_WHITE, faces={"north": "toy_sticker_holo", "south": "toy_sticker_holo"},
          uv={"north": STICKER_UV, "south": STICKER_UV})
    _star_plate(m, "north", frm, to)
    _star_plate(m, "south", frm, to)


def holo_sticker_side(m, face, frm, to):
    """The premium sticker on one face only (face: "west", "south", ...), its glowing star plate on it."""
    m.box("sticker", frm, to, P_WHITE, faces={face: "toy_sticker_holo"}, uv={face: STICKER_UV})
    _star_plate(m, face, frm, to)


FACES6 = ("north", "east", "south", "west", "up", "down")


def jewel(m, name, x, y, z0, z1, mat=GEM, size=1.5, rim=0.375):
    """A gem in a gold setting through a part, showing on the front and the back (z0, z1: the part's faces)."""
    h = size / 2
    m.box(f"{name}_setting", (x - h - rim, y - h - rim, z0 - 0.125), (x + h + rim, y + h + rim, z1 + 0.125), GOLD_TRIM)
    m.box(name, (x - h, y - h, z0 - 0.375), (x + h, y + h, z1 + 0.375), mat)


def charm(m, x, y):
    """The keychain: a gold jump ring at (x, y) on the side of the handle, a little glowing star hanging under it."""
    m.group("charm")
    m.box("charm_ring", (x, y - 0.5, 7.625), (x + 0.75, y + 0.25, 8.375), GOLD_TRIM)
    m.box("charm_star", (x + 0.5, y - 2.75, 7.6), (x + 1.25, y - 0.5, 8.4), STAR_GLOW)
    m.box("charm_star", (x, y - 2.0, 7.75), (x + 1.75, y - 1.25, 8.25), STAR_GLOW)


def premium_handle(m, pommel=True):
    """handle() dressed for the donation set: the same red pommel, blue ribbed grip and collar (y -4 .. 6.5), now a
    jewelled pommel with a gold cap and a gold ring, a chrome band under a gold-plated collar set with a gem, and a
    star charm on a jump ring at the pommel's side. pommel=False: the piece ends its handle its own way."""
    m.group("handle")
    if pommel:
        m.box("pommel_nub", (7, -4, 7), (9, -3.25, 9), GOLD_TRIM)
        round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.25, -1.5, P_RED, 0.75)
        jewel(m, "pommel_gem", C, -2.375, 6.25, 9.75, size=1.0, rim=0.25)
        round_y(m, "pommel_ring", 6.5, 9.5, 6.5, 9.5, -1.5, -1, GOLD_TRIM, 0.5)
    round_y(m, "grip", 6.75, 9.25, 6.75, 9.25, -1, 5.25, GRIP_BLUE, 0.5)
    for y in (0, 1.75, 3.5):
        m.box("grip_rib", (6.5, y, 6.5), (9.5, y + 0.5, 9.5), BLUE_LIGHT)
    m.box("grip_band", (6.5, 4.75, 6.5), (9.5, 5.25, 9.5), CHROME)
    round_y(m, "collar", 6, 10, 6, 10, 5.25, 6.5, GOLD_TRIM, 1)
    m.box("collar_gem", (7.5, 5.5, 5.75), (8.5, 6.25, 10.25), GEM_RED)
    if pommel:
        charm(m, 9.75, -2)


def premium_shaft(m, top, mat=P_YELLOW):
    """The shaft above the collar, up to y = top, glossy, with a chrome band a little under the head."""
    m.group("shaft")
    round_y(m, "shaft", 6.875, 9.125, 6.875, 9.125, 6.5, top, mat, 0.5)
    m.box("shaft_band", (6.625, top - 1.75, 6.625), (9.375, top - 1.25, 9.375), CHROME)


# ================================================================================================
# The tools
# ================================================================================================

def toy_sword():
    """장난감 검: a sculpted foam blade with stitched seams and a light-up crystal ridge, a round tip; a glossy yellow guard
    with gold-capped red ball ends and a gold lip, the holographic sticker with its shining star on the guard."""
    m = MModel("toy_sword")
    premium_handle(m)
    m.group("guard")
    m.box("guard", (2.5, 6.5, 6.75), (13.5, 8.75, 9.25), P_YELLOW)
    m.box("guard_lip", (4.5, 8.75, 6.875), (11.5, 9.25, 9.125), GOLD_TRIM)
    for x0, x1, c0, c1 in ((1.25, 2.75, 0.75, 1.25), (13.25, 14.75, 14.75, 15.25)):
        m.box("guard_ball", (x0, 6.25, 6.5), (x1, 9, 9.5), P_RED)
        m.box("guard_cap", (c0, 6.75, 7), (c1, 8.5, 9), GOLD_TRIM)
    holo_sticker(m, 5.75, 6.25, 6.25, 9.75)
    m.group("blade")
    m.box("blade", (5.25, 9.25, 7), (10.75, 23.5, 9), P_FOAM)
    m.box("blade_edge", (4.5, 9.75, 7.5), (5.25, 22.75, 8.5), P_FOAM_EDGE)
    m.box("blade_edge", (10.75, 9.75, 7.5), (11.5, 22.75, 8.5), P_FOAM_EDGE)
    m.box("blade_ridge", (7.25, 11.5, 6.75), (8.75, 21.5, 9.25), CRYSTAL)
    m.box("blade_gem", (7.375, 21.5, 6.625), (8.625, 22.75, 9.375), GEM)
    m.box("blade_tip", (6.25, 23.5, 7), (9.75, 25, 9), P_FOAM)
    m.box("blade_tip", (7, 25, 7.25), (9, 26.25, 8.75), P_FOAM)
    m.box("blade_tip", (7.5, 26.25, 7.5), (8.5, 27, 8.5), P_FOAM)
    return _held(m, "sword")


def _arm(m, name, side, pivot, length, half_h, z0, z1, angle, mat, back=0.5):
    """A piece of the pickaxe's arm: along x from its pivot (on the arm's centre line), drooping by angle.
    Returns the centre line's end, the next piece's pivot."""
    px, py = pivot
    if side > 0:
        frm, to = (px - back, py - half_h, z0), (px + length, py + half_h, z1)
    else:
        frm, to = (px - length, py - half_h, z0), (px + back, py + half_h, z1)
    m.box(name, frm, to, mat, rotation=("z", -angle * side, (px, py, C)))
    a = math.radians(angle)
    return px + side * (length - 0.5) * math.cos(a), py - (length - 0.5) * math.sin(a)


def toy_pickaxe():
    """장난감 곡괭이: a glossy red head whose arms droop in two bends (22.5 and 45 degrees), gold bands where the arms
    leave it and a gold cap on its crown with a jewel, seamed foam tips, the holographic sticker in the middle."""
    m = MModel("toy_pickaxe")
    premium_handle(m)
    premium_shaft(m, 16)
    m.group("head")
    m.box("head", (4.5, 15.25, 6.5), (11.5, 20.75, 9.5), P_RED)
    m.box("head_cap", (5.5, 20.75, 6.75), (10.5, 21.5, 9.25), GOLD_TRIM)
    m.box("head_crown", (7.25, 21.5, 7.25), (8.75, 22.25, 8.75), GEM_RED)
    for x0, x1 in ((4.25, 4.75), (11.25, 11.75)):
        m.box("head_band", (x0, 15, 6.25), (x1, 21, 9.75), GOLD_TRIM)
    holo_sticker(m, 5.75, 15.75, 6.25, 9.75)
    for side in (1, -1):
        p = (C + side * 3.5, 18.25)
        p = _arm(m, "arm_a", side, p, 4.5, 1.875, 6.75, 9.25, 22.5, P_RED)
        p = _arm(m, "arm_b", side, p, 4.0, 1.5, 7, 9, 45, P_RED)
        a = math.radians(45)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 1, 7.25), (px + 2.25, py + 1, 8.75)
            ring = (px, py - 1.25, 7), (px + 0.5, py + 1.25, 9)
        else:
            frm, to = (px - 2.25, py - 1, 7.25), (px - 0.5, py + 1, 8.75)
            ring = (px - 0.5, py - 1.25, 7), (px, py + 1.25, 9)
        m.box("arm_ring", *ring, GOLD_TRIM, rotation=("z", -45 * side, (px, py, C)))
        m.box("arm_tip", frm, to, P_FOAM_EDGE, rotation=("z", -45 * side, (px, py, C)))
    return _held(m, "pickaxe")


def toy_shovel():
    """장난감 삽: a glossy green spade: wide shoulders above a neck, narrowing to a round point; thin, its face dished (a
    gold-lit rim stands proud of it all round, a spine runs down its back), the holographic sticker on its face; a gold
    socket band; and a red D with a jewel and the star charm at the handle's end instead of the pommel."""
    m = MModel("toy_shovel")
    premium_handle(m, pommel=False)
    m.box("d_top", (4.75, -2.5, 7), (11.25, -1, 9), P_RED)
    m.box("d_side", (4.75, -6.25, 7), (6, -2.5, 9), P_RED)
    m.box("d_side", (10, -6.25, 7), (11.25, -2.5, 9), P_RED)
    m.box("d_bar", (4.75, -7.5, 6.75), (11.25, -6.25, 9.25), P_RED_DARK)
    for x0 in (4.5, 10.75):
        m.box("d_cap", (x0, -7.75, 6.625), (x0 + 0.75, -6, 9.375), GOLD_TRIM)
    jewel(m, "d_gem", C, -6.875, 6.75, 9.25, size=1)
    charm(m, 11.25, -2.75)
    premium_shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17, GOLD_TRIM, 0.75)
    rows = ((17, 18, 3.75, 7, 9), (18, 23.5, 4.5, 7, 9), (23.5, 25.5, 3.75, 7, 9), (25.5, 27, 2.75, 7.2, 8.8))
    flat = Mat("slime", "plain", 1, bevel=None, noise=0)
    light = Gloss("slime", 2, bevel=None)
    rim = Gloss("slime", 3, bevel=None)
    for y0, y1, hw, z0, z1 in rows:
        m.box("blade", (C - hw, y0, z0), (C + hw, y1, z1), Gloss("slime", 1, bevel=None) if hw == 4.5 else flat)
        m.box("rim", (C - hw, y0, z1), (C - hw + 1, y1, z1 + 0.4), rim)
        m.box("rim", (C + hw - 1, y0, z1), (C + hw, y1, z1 + 0.4), light)
    m.box("point", (6.5, 27, 7.2), (9.5, 28.25, 8.8), light)
    m.box("point", (7.25, 28.25, 7.4), (8.75, 29, 8.6), rim)
    m.box("spine", (7.25, 17, 6.6), (8.75, 25, 7), light)
    holo_sticker_side(m, "south", (C - STICKER / 2, 18.75, 9), (C + STICKER / 2, 18.75 + STICKER, 9.25))
    return _held(m, "shovel")


def toy_axe():
    """장난감 도끼: a glossy orange head that widens to a seamed foam edge (towards -x, like the sprite's blade), a gold
    band between head and bit, a chrome-capped poll behind, a gold cap with a jewel on the shaft's top, the sticker."""
    m = MModel("toy_axe")
    premium_handle(m)
    premium_shaft(m, 21.5)
    m.group("head")
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 21.5, 22.5, GOLD_TRIM, 0.75)
    m.box("shaft_gem", (7.25, 22.5, 7.25), (8.75, 23.25, 8.75), GEM_RED)
    m.box("head", (4.5, 14, 6.5), (10, 20.5, 9.5), P_ORANGE)
    m.box("head_band", (4.25, 13.75, 6.375), (4.75, 20.75, 9.625), GOLD_TRIM)
    m.box("blade", (2, 13, 6.875), (4.25, 21.5, 9.125), P_ORANGE)
    m.box("blade", (0, 12, 7.25), (2, 22.5, 8.75), P_ORANGE)
    m.box("blade_edge", (-1.25, 12.5, 7.5), (0, 22, 8.5), P_FOAM_EDGE)
    m.box("blade_edge", (-0.75, 11.5, 7.5), (0, 12.5, 8.5), P_FOAM_EDGE)
    m.box("blade_edge", (-0.75, 22, 7.5), (0, 23, 8.5), P_FOAM_EDGE)
    m.box("poll", (10, 15, 6.75), (11.5, 19.5, 9.25), P_ORANGE)
    m.box("poll_cap", (11.5, 14.75, 6.5), (12.25, 19.75, 9.5), CHROME)
    holo_sticker(m, 5, 15, 6.25, 9.75)
    return _held(m, "axe")


def toy_hoe():
    """장난감 괭이: like a vanilla hoe, an L: one straight glossy blue bar sticking out to one side (-x) from the top of
    the shaft and ending flat (no hook, no drop): a little wider blade block at its end behind a gold band, its flat end
    faced with seamed foam; a jewel in a gold setting on the bar, a gold cap with a red gem on the shaft's top and a gold
    end cap on the other side. (The bar is too slim for the sticker: the hoe wears the jewel instead.)"""
    m = MModel("toy_hoe")
    premium_handle(m)
    premium_shaft(m, 20.5)
    m.group("head")
    m.box("bar", (1.25, 19.75, 6.75), (9.75, 22.5, 9.25), P_BLUE)
    round_y(m, "bar_cap", 6.5, 9.5, 6.5, 9.5, 22.5, 23.25, GOLD_TRIM, 0.75)
    m.box("bar_gem", (7.25, 23.25, 7.25), (8.75, 24, 8.75), GEM_RED)
    m.box("bar_end", (9.75, 20.125, 7.125), (10.25, 22.125, 8.875), GOLD_TRIM)
    jewel(m, "bar_jewel", 4.25, 21.125, 6.75, 9.25, size=1.25, rim=0.25)
    m.box("band", (0.75, 19.5, 6.5), (1.25, 22.75, 9.5), GOLD_TRIM)
    m.box("blade", (-1.25, 19.75, 6), (0.75, 22.5, 10), P_BLUE)
    m.box("blade_edge", (-2, 20, 6.25), (-1.25, 22.25, 9.75), P_FOAM_EDGE)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    premium_handle(m)
    m.group("reel")
    # a big round glossy reel beside the rod's foot (+x): a red drum, a gold hub with a jewel through it, a chrome
    # crank with a blue knob on the side that shows (+z)
    m.box("reel_foot", (9, 7.5, 7.25), (10, 9.5, 8.75), GOLD_TRIM)
    cyl(m, "reel", "z", (12.75, 8.5), 6, 6.25, 9.75, P_RED, cap=P_RED)
    m.box("reel_hub", (11.5, 7.25, 6), (14, 9.75, 10), GOLD_TRIM)
    m.box("reel_gem", (12.375, 7.75, 5.75), (13.125, 8.75, 10.125), GEM)
    m.box("crank", (12.25, 8, 10), (13.25, 11.25, 10.75), CHROME)
    m.box("crank_knob", (12, 10.5, 10.75), (13.5, 12, 12), P_BLUE)
    m.group("rod")
    round_y(m, "rod", 6.625, 9.375, 6.625, 9.375, 6.5, 14, P_YELLOW, 0.75)
    m.box("rod", (6.875, 14, 6.875), (9.125, 23, 9.125), P_YELLOW)
    m.box("rod", (7.125, 23, 7.125), (8.875, 30.25, 8.875), P_YELLOW)
    m.box("rod_ferrule", (6.875, 29.75, 6.875), (9.125, 30.25, 9.125), GOLD_TRIM)
    m.box("rod_tip", (6.75, 30.25, 6.75), (9.25, 31.75, 9.25), P_RED)
    m.box("rod_tip_gem", (7.5, 31.75, 7.5), (8.5, 32, 8.5), GEM_RED)
    m.box("rod_ring", (6.375, 13.5, 6.375), (9.625, 14.5, 9.625), GOLD_TRIM)
    m.box("rod_ring", (6.625, 22.5, 6.625), (9.375, 23.5, 9.375), GOLD_TRIM)
    m.box("guide", (9.125, 17.75, 7.25), (10.5, 19, 8.75), CHROME)
    m.box("guide", (8.875, 26.25, 7.25), (10.25, 27.5, 8.75), CHROME)
    if not cast:
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 19.5, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        # a magnet-fishing float: a glossy red and white ball with a gold equator, a gold ring, a horseshoe magnet
        m.box("float_ring", (7.5, 18.75, 7), (9.5, 19.5, 9), GOLD_TRIM, rotation=rot)
        m.box("float_top", (6.5, 16.75, 6), (10.5, 18.75, 10), P_RED, rotation=rot)
        m.box("float_belt", (6.25, 16.5, 5.75), (10.75, 17, 10.25), GOLD_TRIM, rotation=rot)
        m.box("float_bottom", (6.5, 14.75, 6), (10.5, 16.5, 10), P_WHITE, rotation=rot)
        m.box("magnet", (6.75, 13.5, 7), (8.125, 14.75, 9), P_BLUE, rotation=rot)
        m.box("magnet", (8.875, 13.5, 7), (10.25, 14.75, 9), P_RED_DARK, rotation=rot)
        m.box("magnet_tip", (6.75, 13.25, 7.25), (8.125, 13.5, 8.75), CHROME, rotation=rot)
        m.box("magnet_tip", (8.875, 13.25, 7.25), (10.25, 13.5, 8.75), CHROME, rotation=rot)
    return _held(m, "rod")


def toy_fishing_rod():
    """장난감 낚싯대: a short fat glossy yellow rod with gold rings and chrome guides, a big red reel with a jewelled gold
    hub, a thick line and a magnet-fishing float with a gold belt."""
    return _rod("toy_fishing_rod", False)


def toy_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("toy_fishing_rod_cast", True)
    m.display = toy_fishing_rod().display     # framed as the rod with its float: the inventory picture does not jump on a cast
    return m


BOBBER_WATERLINE = 5.0        # the float's blue band: where the water's surface is (model y)


def toy_bobber():
    """장난감 찌: the float of the toy rod, for an item display that rides the fishing hook. A fat glossy ball, white below
    its blue waterline band and red above, gold rims on the band, a gold weight under it and a chrome stem with a glowing
    jewel eye on top, the shining holographic sticker on four sides: the same from every side. Stands on y = 0 about the
    axis x = 8, z = 8; the water's surface is at BOBBER_WATERLINE."""
    m = MModel("toy_bobber")
    m.group("float")
    cyl(m, "weight", "y", (C, C), 2, 0, 1, GOLD_TRIM, cap=GOLD_TRIM)
    cyl(m, "belly", "y", (C, C), 4, 1, 2, P_WHITE, cap=P_WHITE)
    cyl(m, "belly", "y", (C, C), 6, 2, 4.5, P_WHITE, cap=P_WHITE)
    cyl(m, "band", "y", (C, C), 6, 4.5, 5.5, P_BLUE, cap=P_BLUE)
    cyl(m, "top", "y", (C, C), 6, 5.5, 8, P_RED, cap=P_RED)
    cyl(m, "top", "y", (C, C), 4, 8, 9, P_RED, cap=P_RED)
    m.box("crown", (6.75, 9, 6.75), (9.25, 9.25, 9.25), GOLD_TRIM)
    m.box("stem", (7, 9.25, 7), (9, 10, 9), CHROME)
    m.box("eye", (7.25, 10, 7.25), (8.75, 11.25, 8.75), GEM)
    m.group("stickers")
    for face, frm, to in (("south", (6.75, 5.5, 11), (9.25, 8, 11.25)), ("north", (6.75, 5.5, 4.75), (9.25, 8, 5)),
                          ("east", (11, 5.5, 6.75), (11.25, 8, 9.25)), ("west", (4.75, 5.5, 6.75), (5, 8, 9.25))):
        holo_sticker_side(m, face, frm, to)
    m.display = presets.furniture(gui_scale=1.3, gui_translation=(0, 3.25, 0))
    m.kind = "bobber"
    return m


def party_firework():
    """파티 폭죽 발사기: a fat blue launch tube on the series' handle with three paper rockets standing in it (a bundle, so it
    reads as never empty), a white label with the sticker round its middle."""
    m = MModel("party_firework")
    handle(m)
    m.group("tube")
    round_y(m, "tube_base", 3.75, 12.25, 3.75, 12.25, 6.5, 7.75, YELLOW, 1.25)
    round_y(m, "tube", 4.25, 11.75, 4.25, 11.75, 7.75, 15.5, BLUE, 1)
    round_y(m, "tube_rim", 3.75, 12.25, 3.75, 12.25, 15.5, 16.75, YELLOW, 1.25, top=DARK)
    sticker(m, 5.75, 9.5, 4, 12)
    m.group("rockets")
    for name, x, z, top, stripes, cone in (("a", 5, 8.25, 24.75, "toy_stripes_red", RED), ("b", 8.5, 8.25, 22.25, "toy_stripes_blue", YELLOW),
                                            ("c", 6.75, 5, 23.5, "toy_stripes_green", GREEN_LIGHT)):
        m.box(f"rocket_{name}", (x, 16.75, z), (x + 2.5, top, z + 2.5), stripes)
        m.box(f"rocket_{name}_cone", (x - 0.375, top, z - 0.375), (x + 2.875, top + 1, z + 2.875), cone)
        m.box(f"rocket_{name}_cone", (x + 0.125, top + 1, z + 0.125), (x + 2.375, top + 2, z + 2.375), cone)
        m.box(f"rocket_{name}_cone", (x + 0.625, top + 2, z + 0.625), (x + 1.875, top + 2.75, z + 1.875), cone)
    return _held(m, "firework")


# ================================================================================================
# How they sit in the hand, the inventory, on the ground, in a frame
# ================================================================================================

# kind -> third person scale, first person scale (vanilla 0.85 / 0.68 for a 16 x 16 sprite), the inventory picture's turn
# about y (a little of the side shows: the pieces have depth), how much of the slot it fills, its size in an item frame
# and on the ground (model units of its longer side)
SIZES = {
    "sword":    dict(third=0.85, first=0.5, gui_turn=0, gui_fit=15.5, frame=15, ground=11),
    "pickaxe":  dict(third=0.85, first=0.5, gui_turn=0, gui_fit=15.5, frame=15, ground=11),
    "shovel":   dict(third=0.85, first=0.5, gui_turn=0, gui_fit=15.5, frame=15, ground=11),
    "axe":      dict(third=0.85, first=0.5, gui_turn=0, gui_fit=15.5, frame=15, ground=11),
    "hoe":      dict(third=0.85, first=0.5, gui_turn=0, gui_fit=15.5, frame=15, ground=11),
    "rod":      dict(third=0.8, first=0.46, gui_turn=0, gui_fit=15.5, frame=15, ground=11),
    "firework": dict(third=0.85, first=0.5, gui_turn=0, gui_fit=15.5, frame=15, ground=10),
}


def _upright(base):
    """A vanilla held pose for a model built upright instead of on the sprite's diagonal (this file's top)."""
    out = {k: {kk: list(vv) for kk, vv in v.items()} for k, v in base.items()}
    for ctx, d in (("thirdperson_righthand", -45), ("firstperson_righthand", -45), ("thirdperson_lefthand", 45), ("firstperson_lefthand", 45)):
        out[ctx]["rotation"][2] += d
    return out


def _mul(a, v):
    return [sum(a[i][j] * v[j] for j in range(3)) for i in range(3)]


def corners(m):
    """Every corner of every box of a model, where its own rotation puts it."""
    out = []
    for e in m.elements:
        f, t = e["from"], e["to"]
        for x in (f[0], t[0]):
            for y in (f[1], t[1]):
                for z in (f[2], t[2]):
                    p = [x, y, z]
                    if e["rotation"]:
                        axis, angle, o = e["rotation"]
                        r = presets._rot_xyz([angle if axis == a else 0 for a in "xyz"])
                        p = [o[i] + c for i, c in enumerate(_mul(r, [p[i] - o[i] for i in range(3)]))]
                    out.append(p)
    return out


def _framed(m, rotation, fit, lift=0.0):
    """A view that shows the whole model: turned by rotation, its longer side fit units, its middle on the view's."""
    r = presets._rot_xyz(rotation)
    pts = [_mul(r, [p[i] - 8 for i in range(3)]) for p in corners(m)]
    lo = [min(p[i] for p in pts) for i in range(3)]
    hi = [max(p[i] for p in pts) for i in range(3)]
    s = round(fit / max(hi[0] - lo[0], hi[1] - lo[1]), 3)
    mid = [(lo[i] + hi[i]) / 2 * s for i in range(3)]
    return {"rotation": list(rotation), "translation": [round(-mid[0], 2), round(-mid[1] + lift, 2), 0], "scale": [s, s, s]}


def _held(m, kind):
    size = SIZES[kind]
    base = _upright(presets.HANDHELD_ROD if kind == "rod" else presets.HANDHELD)
    held = presets.enlarged(base, {"thirdperson_righthand": size["third"] / 0.85, "thirdperson_lefthand": size["third"] / 0.85,
                                   "firstperson_righthand": size["first"] / 0.68, "firstperson_lefthand": size["first"] / 0.68}, GRIP)
    for t in held.values():
        t["translation"] = [v + 0.0 for v in t["translation"]]     # no "-0.0" in the files
    m.display = {
        **held,
        "gui": _framed(m, (0, size["gui_turn"], -45), size["gui_fit"]),
        "ground": _framed(m, (0, 0, -45), size["ground"], lift=2),
        "fixed": _framed(m, (0, 180, -45), size["frame"]),
    }
    m.front_gui_light = True
    m.kind = kind
    return m


ALL = {f.__name__: f for f in (toy_sword, toy_pickaxe, toy_shovel, toy_axe, toy_hoe, toy_fishing_rod, toy_fishing_rod_cast, toy_bobber,
                               party_firework)}


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("toy_sword", "toy_pickaxe", "toy_shovel", "toy_axe", "toy_hoe", "toy_bobber", "party_firework")}
    out["toy_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/toy_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/toy_fishing_rod_cast"},
    }}
    return out


def write_item_definitions(content: Path):
    d = content / "assets" / "stsv" / "items"
    d.mkdir(parents=True, exist_ok=True)
    for name, definition in item_definitions().items():
        (d / f"{name}.json").write_text(json.dumps(definition, indent=2) + "\n")
    return sorted(item_definitions())
