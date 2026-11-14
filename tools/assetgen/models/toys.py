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


def handle(m):
    """What every piece of the series shares: a red pommel, a blue grip with three ribs, a yellow collar (y -4 .. 6.5)."""
    m.group("handle")
    m.box("pommel_nub", (7, -4, 7), (9, -3.25, 9), GRIP_RED_DARK)
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.25, -1.25, GRIP_RED, 0.75)
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
    """장난감 삽: a green beach spade: a flat blade with a lip down each side of its face, a round tip, the sticker on it."""
    m = MModel("toy_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    round_y(m, "socket", 6.25, 9.75, 6.25, 9.75, 15.5, 16.5, GREEN_LIGHT, 0.75)
    m.box("blade", (4, 16.5, 7), (12, 25.5, 9), GREEN)
    m.box("lip", (4, 17, 6.5), (5, 25, 7), GREEN_LIGHT)
    m.box("lip", (11, 17, 6.5), (12, 25, 7), GREEN_LIGHT)
    m.box("blade_tip", (4.75, 25.5, 7), (11.25, 27, 9), GREEN)
    m.box("blade_tip", (5.75, 27, 7.25), (10.25, 28.25, 8.75), GREEN)
    m.box("blade_tip", (7, 28.25, 7.5), (9, 29, 8.5), GREEN)
    sticker(m, 5.75, 19, 6.75, 9.25)
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


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    # a big round toy reel beside the rod's foot (towards +x, where the line hangs): a red drum, a yellow hub through it,
    # a white crank with a blue knob on the side that shows (+z: the inventory's side, and away from the body in the hand)
    m.box("reel_foot", (9, 7.5, 7.25), (10, 9.5, 8.75), YELLOW_DARK)
    cyl(m, "reel", "z", (12.75, 8.5), 6, 6.25, 9.75, RED, cap=RED)
    m.box("reel_hub", (11.5, 7.25, 6), (14, 9.75, 10), YELLOW)
    m.box("crank", (12.25, 8, 10), (13.25, 11.25, 10.75), WHITE)
    m.box("crank_knob", (12, 10.5, 10.75), (13.5, 12, 12), BLUE)
    m.group("rod")
    round_y(m, "rod", 6.625, 9.375, 6.625, 9.375, 6.5, 14, YELLOW, 0.75)
    m.box("rod", (6.875, 14, 6.875), (9.125, 23, 9.125), YELLOW)
    m.box("rod", (7.125, 23, 7.125), (8.875, 30.25, 8.875), YELLOW)
    m.box("rod_tip", (6.75, 30.25, 6.75), (9.25, 31.75, 9.25), RED)
    m.box("rod_ring", (6.375, 13.5, 6.375), (9.625, 14.5, 9.625), BLUE)
    m.box("rod_ring", (6.625, 22.5, 6.625), (9.375, 23.5, 9.375), BLUE)
    m.box("guide", (9.125, 17.75, 7.25), (10.5, 19, 8.75), BLUE_LIGHT)
    m.box("guide", (8.875, 26.25, 7.25), (10.25, 27.5, 8.75), BLUE_LIGHT)
    if not cast:
        # the line hangs from the tip: straight down on the sprite's diagonal = 45 degrees in this frame
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 19.5, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        # a magnet-fishing float: a red and white ball, a yellow ring, a horseshoe magnet's two feet
        m.box("float_ring", (7.5, 18.75, 7), (9.5, 19.5, 9), YELLOW, rotation=rot)
        m.box("float_top", (6.5, 16.75, 6), (10.5, 18.75, 10), RED, rotation=rot)
        m.box("float_bottom", (6.5, 14.75, 6), (10.5, 16.75, 10), WHITE, rotation=rot)
        m.box("magnet", (6.75, 13.5, 7), (8.125, 14.75, 9), BLUE, rotation=rot)
        m.box("magnet", (8.875, 13.5, 7), (10.25, 14.75, 9), RED_DARK, rotation=rot)
    return _held(m, "rod")


def toy_fishing_rod():
    """장난감 낚싯대: a short fat yellow rod, a big red reel with a crank, a thick line and a magnet-fishing float."""
    return _rod("toy_fishing_rod", False)


def toy_fishing_rod_cast():
    """The rod while its float is out: the same without the line and the float."""
    m = _rod("toy_fishing_rod_cast", True)
    m.display = toy_fishing_rod().display     # framed as the rod with its float: the inventory picture does not jump on a cast
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


ALL = {f.__name__: f for f in (toy_sword, toy_pickaxe, toy_shovel, toy_axe, toy_hoe, toy_fishing_rod, toy_fishing_rod_cast, party_firework)}


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("toy_sword", "toy_pickaxe", "toy_shovel", "toy_axe", "toy_hoe", "party_firework")}
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
