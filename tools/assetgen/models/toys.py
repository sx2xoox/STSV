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
# The tools
# ================================================================================================

def toy_sword():
    """장난감 검: a foam blade with a round tip, a yellow guard with red ball ends, the sticker on the guard."""
    m = MModel("toy_sword")
    handle(m)
    m.group("guard")
    m.box("guard", (2.5, 6.5, 6.75), (13.5, 8.75, 9.25), YELLOW)
    m.box("guard_ball", (1.25, 6.25, 6.5), (2.75, 9, 9.5), RED)
    m.box("guard_ball", (13.25, 6.25, 6.5), (14.75, 9, 9.5), RED)
    sticker(m, 5.75, 6.25, 6.25, 9.75)
    m.group("blade")
    m.box("blade", (5.25, 8.75, 7), (10.75, 23.5, 9), FOAM)
    m.box("blade_edge", (4.5, 9.5, 7.5), (5.25, 22.75, 8.5), FOAM)
    m.box("blade_edge", (10.75, 9.5, 7.5), (11.5, 22.75, 8.5), FOAM)
    m.box("blade_ridge", (7.25, 11.5, 6.75), (8.75, 22, 9.25), ICE)
    m.box("blade_tip", (6.25, 23.5, 7), (9.75, 25, 9), FOAM)
    m.box("blade_tip", (7, 25, 7.25), (9, 26.25, 8.75), FOAM)
    m.box("blade_tip", (7.5, 26.25, 7.5), (8.5, 27, 8.5), FOAM)
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
    """장난감 곡괭이: a red head whose arms droop in two bends (22.5 and 45 degrees), foam tips, the sticker in the middle."""
    m = MModel("toy_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    m.box("head", (4.5, 15.25, 6.5), (11.5, 20.75, 9.5), RED)
    m.box("head_cap", (5.5, 20.75, 6.75), (10.5, 21.5, 9.25), RED)
    sticker(m, 5.75, 15.75, 6.25, 9.75)
    for side in (1, -1):
        p = (C + side * 3.5, 18.25)
        p = _arm(m, "arm_a", side, p, 4.5, 1.875, 6.75, 9.25, 22.5, RED)
        p = _arm(m, "arm_b", side, p, 4.0, 1.5, 7, 9, 45, RED)
        # the tip goes on along the last bend
        a = math.radians(45)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 1, 7.25), (px + 2.25, py + 1, 8.75)
        else:
            frm, to = (px - 2.25, py - 1, 7.25), (px - 0.5, py + 1, 8.75)
        m.box("arm_tip", frm, to, FOAM, rotation=("z", -45 * side, (px, py, C)))
    return _held(m, "pickaxe")


def toy_shovel():
    """장난감 삽: a green spade: wide shoulders above a neck, narrowing to a round point; thin, its face dished (a light rim
    stands proud of it all round, a spine runs down its back); and a red D at the handle's end instead of the pommel."""
    m = MModel("toy_shovel")
    handle(m, pommel=False)
    # the D: the grip stands on its top bar, the hand stays on the grip
    m.box("d_top", (4.75, -2.5, 7), (11.25, -1.25, 9), GRIP_RED)
    m.box("d_side", (4.75, -6.25, 7), (6, -2.5, 9), GRIP_RED)
    m.box("d_side", (10, -6.25, 7), (11.25, -2.5, 9), GRIP_RED)
    m.box("d_bar", (4.75, -7.5, 6.75), (11.25, -6.25, 9.25), GRIP_RED_DARK)
    shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 17, GREEN_LIGHT, 0.75)
    # rows from the shoulders to the point: (y0, y1, half width, z0, z1)
    rows = ((17, 18, 3.75, 7, 9), (18, 23.5, 4.5, 7, 9), (23.5, 25.5, 3.75, 7, 9), (25.5, 27, 2.75, 7.2, 8.8))
    # the face is one flat green (no line a row: the rows are one sheet), the big row keeps the plastic's glint
    flat = Mat("slime", "plain", 1, bevel=None, noise=0)
    light = Mat("slime", "plain", 2, bevel=None, noise=0)      # the rim, the point, the spine: one even light green
    for y0, y1, hw, z0, z1 in rows:
        m.box("blade", (C - hw, y0, z0), (C + hw, y1, z1), Mat("slime", "metal", 1, bevel=None, noise=0) if hw == 4.5 else flat)
        # the rim: a light edge a little proud of the dished face (+z: the side the inventory and the player's own eyes
        # see), down both sides
        m.box("rim", (C - hw, y0, z1), (C - hw + 1, y1, z1 + 0.4), light)
        m.box("rim", (C + hw - 1, y0, z1), (C + hw, y1, z1 + 0.4), light)
    m.box("point", (6.5, 27, 7.2), (9.5, 28.25, 8.8), light)
    m.box("point", (7.25, 28.25, 7.4), (8.75, 29, 8.6), light)
    m.box("spine", (7.25, 17, 6.6), (8.75, 25, 7), light)
    return _held(m, "shovel")


def toy_axe():
    """장난감 도끼: an orange head that widens to a foam edge (towards -x, like the sprite's blade), a poll behind."""
    m = MModel("toy_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    round_y(m, "shaft_cap", 6.5, 9.5, 6.5, 9.5, 21.5, 22.5, RED, 0.75)
    m.box("head", (4.5, 14, 6.5), (10, 20.5, 9.5), ORANGE)
    m.box("blade", (2, 13, 6.875), (4.5, 21.5, 9.125), ORANGE)
    m.box("blade", (0, 12, 7.25), (2, 22.5, 8.75), ORANGE)
    m.box("blade_edge", (-1.25, 12.5, 7.5), (0, 22, 8.5), FOAM)
    m.box("blade_edge", (-0.75, 11.5, 7.5), (0, 12.5, 8.5), FOAM)
    m.box("blade_edge", (-0.75, 22, 7.5), (0, 23, 8.5), FOAM)
    m.box("poll", (10, 15, 6.75), (11.75, 19.5, 9.25), ORANGE)
    sticker(m, 5, 15, 6.25, 9.75)
    return _held(m, "axe")


def toy_hoe():
    """장난감 괭이: a blue bar across the top of the shaft and a wide plate hanging from its end (towards -x), foam on its edge.
    The plate lies across the handle (z), the way a real hoe's does: a thin strip from the front, a plate in the hand."""
    m = MModel("toy_hoe")
    handle(m)
    shaft(m, 20.5)
    m.group("head")
    m.box("bar", (2.5, 19.75, 6.75), (9.75, 22.25, 9.25), BLUE)
    round_y(m, "bar_cap", 6.5, 9.5, 6.5, 9.5, 22.25, 23, RED, 0.75)
    m.box("plate", (0.25, 14.75, 5), (2.5, 22.75, 11), BLUE)
    m.box("plate_edge", (0.5, 13.75, 5.25), (2.25, 14.75, 10.75), FOAM)
    # the sticker on the plate's outer face (it looks away from the handle)
    m.box("sticker", (0, 16.5, 5.75), (0.25, 21, 10.25), WHITE, faces={"west": "toy_sticker"}, uv={"west": STICKER_UV})
    return _held(m, "hoe")


# The fishing line is not part of any model: the client draws it from a fixed point beside the player, which is where a
# vanilla rod's tip is (its sprite's (14.5, 14.5): tools/assetgen/toys_preview.py draws both). A rod that is longer than the
# sprite from the hand up has its tip past that point, and the line leaves it from its middle. So this rod is fitted to
# the tip, not to the grip: its tip is put where the sprite's is, its axis on the sprite's diagonal, at the sprite's own
# scale. The hand then closes as far below the tip as on a vanilla rod (14.95 units), and the grip is built there; what
# makes the rod a toy is its girth, its reel and the butt that goes on behind the hand.
ROD_TIP_SPRITE = (14.5, 14.5)                    # a vanilla rod's tip on its sprite
ROD_TIP = 31.0                                   # the same point on this model's axis: the middle of its red cap
ROD_GRIP = ROD_TIP - (ROD_TIP_SPRITE[0] - 3.93) * math.sqrt(2)     # 16.05: where the hand closes (a sprite's (3.93, 3.93))


def _rod(name, cast):
    m = MModel(name)
    # the series' grip, lifted to where the hand is (12.75 .. 19.25, its collar to 20.5); the pommel ends a butt below it
    m.dy = 14
    handle(m, pommel=False)
    m.dy = 10.5
    _pommel(m)
    m.dy = 0
    round_y(m, "butt", 6.75, 9.25, 6.75, 9.25, 9.25, 12.75, GRIP_YELLOW, 0.5)
    m.group("reel")
    # a big round toy reel under the rod just above the hand (towards +x, where the line hangs): a red drum, a yellow hub
    # through it, a white crank with a blue knob on the side that shows (+z: the inventory's side, and away from the body)
    m.box("reel_foot", (9, 20.75, 7.25), (10, 22.5, 8.75), YELLOW_DARK)
    cyl(m, "reel", "z", (12.25, 21.5), 5, 6.5, 9.5, RED, cap=RED)
    m.box("reel_hub", (11.25, 20.5, 6.25), (13.25, 22.5, 9.75), YELLOW)
    m.box("crank", (11.75, 21, 9.75), (12.75, 23.75, 10.5), WHITE)
    m.box("crank_knob", (11.5, 23, 10.5), (13, 24.5, 11.5), BLUE)
    m.group("rod")
    round_y(m, "rod", 6.625, 9.375, 6.625, 9.375, 20.5, 25.5, YELLOW, 0.75)
    m.box("rod", (6.875, 25.5, 6.875), (9.125, 30.25, 9.125), YELLOW)
    m.box("rod_tip", (6.75, 30.25, 6.75), (9.25, 31.75, 9.25), RED)
    m.box("rod_ring", (6.375, 25, 6.375), (9.625, 26, 9.625), BLUE)
    if not cast:
        # the line hangs from the tip: straight down on the sprite's diagonal = 45 degrees in this frame. Short: the
        # float dangles just under the tip, clear of the reel
        m.group("line")
        top = (8.5, ROD_TIP, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 28, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        # a magnet-fishing float: a red and white ball, a yellow ring, a horseshoe magnet's two feet
        m.box("float_ring", (7.5, 27.4, 7), (9.5, 28, 9), GRIP_YELLOW, rotation=rot)
        m.box("float_top", (7, 25.9, 6.5), (10, 27.4, 9.5), GRIP_RED, rotation=rot)
        m.box("float_bottom", (7, 24.4, 6.5), (10, 25.9, 9.5), WHITE, rotation=rot)
        m.box("magnet", (7.25, 23.6, 7.25), (8.25, 24.4, 8.75), GRIP_BLUE, rotation=rot)
        m.box("magnet", (8.75, 23.6, 7.25), (9.75, 24.4, 8.75), GRIP_RED_DARK, rotation=rot)
    return _held(m, "rod")


def toy_fishing_rod():
    """장난감 낚싯대: a short fat yellow rod with a long butt, a big red reel with a crank, a thick line and a magnet-fishing
    float. Fitted to its tip (ROD_TIP above), so the fishing line leaves the rod's end."""
    return _rod("toy_fishing_rod", False)


def toy_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("toy_fishing_rod_cast", True)
    m.display = toy_fishing_rod().display     # framed as the rod with its float: the inventory picture does not jump on a cast
    return m


BOBBER_WATERLINE = 5.0        # the float's blue band: where the water's surface is (model y)


def toy_bobber():
    """장난감 찌: the float of the toy rod, for an item display that rides the fishing hook. A fat ball, white below its
    blue waterline band and red above, a yellow weight under it and a stem with an eye on top, the sticker on four sides:
    the same from every side. Stands on y = 0 about the axis x = 8, z = 8; the water's surface is at BOBBER_WATERLINE."""
    m = MModel("toy_bobber")
    m.group("float")
    cyl(m, "weight", "y", (C, C), 2, 0, 1, GRIP_YELLOW, cap=GRIP_YELLOW)
    cyl(m, "belly", "y", (C, C), 4, 1, 2, WHITE, cap=WHITE)
    cyl(m, "belly", "y", (C, C), 6, 2, 4.5, WHITE, cap=WHITE)
    cyl(m, "band", "y", (C, C), 6, 4.5, 5.5, GRIP_BLUE, cap=GRIP_BLUE)
    cyl(m, "top", "y", (C, C), 6, 5.5, 8, GRIP_RED, cap=GRIP_RED)
    cyl(m, "top", "y", (C, C), 4, 8, 9, GRIP_RED, cap=GRIP_RED)
    m.box("stem", (7, 9, 7), (9, 10, 9), GRIP_YELLOW)
    m.box("eye", (7.25, 10, 7.625), (8.75, 11, 8.375), BLUE_LIGHT)
    m.group("stickers")
    for face, frm, to in (("south", (6.75, 5.5, 11), (9.25, 8, 11.25)), ("north", (6.75, 5.5, 4.75), (9.25, 8, 5)),
                          ("east", (11, 5.5, 6.75), (11.25, 8, 9.25)), ("west", (4.75, 5.5, 6.75), (5, 8, 9.25))):
        m.box("sticker", frm, to, WHITE, faces={face: "toy_sticker"}, uv={face: STICKER_UV})
    # an item display shows it as built ("fixed" is the identity); the inventory never holds one, but it has a picture
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
    "rod":      dict(third=0.85, first=0.68, gui_turn=0, gui_fit=15.5, frame=15, ground=11),    # a vanilla rod's own: _rod_held
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


def _rod_held():
    """A vanilla rod's held poses for this file's rod: the same turn and scale, moved along the rod so that the model's tip
    (8, ROD_TIP, 8) is where the sprite's tip ROD_TIP_SPRITE is. The fishing line starts there (see ROD_TIP)."""
    out = _upright(presets.HANDHELD_ROD)
    for ctx in ("thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand"):
        v = presets.HANDHELD_ROD[ctx]
        r, t, sc = v["rotation"], v["translation"], v["scale"][0]
        left = "lefthand" in ctx
        # the game mirrors a left hand's values when it applies them: rotation y and z, translation x
        rot = presets._rot_xyz([r[0], -r[1], -r[2]] if left else r)
        along = (ROD_TIP - 8) * sc * math.sqrt(0.5)       # the model's tip, laid on the sprite's diagonal
        d = [sc * (ROD_TIP_SPRITE[0] - 8) - along, sc * (ROD_TIP_SPRITE[1] - 8) - along, 0.0]
        shift = _mul(rot, d)
        if left:
            shift[0] = -shift[0]
        out[ctx]["translation"] = [round(t[i] + shift[i], 3) for i in range(3)]
    return out


def _held(m, kind):
    size = SIZES[kind]
    if kind == "rod":
        held = _rod_held()
    else:
        held = presets.enlarged(_upright(presets.HANDHELD), {
            "thirdperson_righthand": size["third"] / 0.85, "thirdperson_lefthand": size["third"] / 0.85,
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
