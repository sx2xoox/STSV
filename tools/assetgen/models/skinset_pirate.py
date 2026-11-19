"""
해적 시리즈 (pirate series, a gold-shop tool skin set): old sea tools, lighter than the toys (about 60 % of their boxes).

    pirate_sword  pirate_pickaxe  pirate_shovel  pirate_axe  pirate_hoe  pirate_fishing_rod (+ pirate_fishing_rod_cast)

Same frame, sizes and display transforms as the toy series (models/toys.py: upright, the grip at GRIP_Y on x = z = 8,
_held(m, kind)). Every piece shares a light handle: a brass cap, a tarred-wood grip with two rope bands, a brass collar.
Looks: tarred dark wood, worn brass, weathered iron, a cutlass of pale steel; a little bone skull on the cutlass and a
brass anchor hanging from the rod's tip. No bobber (the anchor is the rod's look; the float stays vanilla).
"""

import math

from economy_machines import MModel, Mat
from models.toys import C, GRIP_Y, SIZES, _arm, _held, corners, round_y  # noqa: F401

TITLE = "해적 시리즈 (도구 스킨)"

TAR = Mat("wood_dark", "grain", 1, bevel=(0, -1), noise=0.06)        # tarred wood: handles, shafts, the rod
TAR_DARK = Mat("wood_dark", "grain", 0, bevel=(0, -1), noise=0.06)
ROPE = Mat("straw", "weave", 2, bevel=(0, -1), noise=0.05)           # rope wraps
BRASS = Mat("brass", "metal", 2, bevel=(0, -1), noise=0)
BRASS_DARK = Mat("brass", "metal", 1, bevel=(0, -1), noise=0)
IRON = Mat("iron", "speckle", 3, bevel=(0, -1), noise=0.10)          # weathered iron heads
IRON_DARK = Mat("iron", "speckle", 2, bevel=(0, -1), noise=0.10)
EDGE = Mat("steel", "metal", 4, bevel=(0, -1), noise=0)              # a ground edge on the iron
STEEL = Mat("blade", "metal", 2, bevel=(0, -1), noise=0)             # the cutlass
STEEL_BACK = Mat("blade", "plain", 1, bevel=(0, -1), noise=0)
BONE = Mat("cream", "plain", 3, bevel=(0, -1), noise=0.04)
SOCKET = Mat("rubber", "plain", 0, bevel=None, noise=0)              # the skull's eyes
LINE = Mat("feather", "plain", 1, bevel=None, noise=0)


# ================================================================================================
# The shared handle
# ================================================================================================

def handle(m):
    """A brass cap, a tarred grip with two rope bands, a brass collar (y -2.75 .. 6.5): five boxes."""
    m.group("handle")
    m.box("cap", (6.5, -2.75, 6.5), (9.5, -1.25, 9.5), BRASS)
    m.box("grip", (6.875, -1.25, 6.875), (9.125, 5.25, 9.125), TAR)
    for y in (0.0, 2.75):
        m.box("rope", (6.625, y, 6.625), (9.375, y + 1.25, 9.375), ROPE)
    m.box("collar", (6.375, 5.25, 6.375), (9.625, 6.5, 9.625), BRASS_DARK)


def shaft(m, top):
    m.group("shaft")
    m.box("shaft", (7, 6.5, 7), (9, top, 9), TAR)


# ================================================================================================
# The tools
# ================================================================================================

def pirate_sword():
    """해적 커틀러스: a short broad blade curving back in steps, a brass basket guard with a knuckle bow, a bone skull charm."""
    m = MModel("pirate_sword")
    handle(m)
    m.group("guard")
    m.box("shell", (5, 6.5, 6), (11, 8, 10), BRASS)                       # the basket's dish
    m.box("knuckle_bow", (4.25, -1.75, 7.25), (5.5, 8, 8.75), BRASS_DARK)  # down to the cap, on the edge's side
    m.box("knuckle_foot", (5.5, -2.5, 7.25), (6.5, -1.25, 8.75), BRASS_DARK)
    m.box("quillon", (11, 7.5, 7.25), (12.25, 9.5, 8.75), BRASS_DARK)     # curls up on the back's side
    m.group("blade")
    # (y0, y1, x0, x1): the blade leans towards +x as it rises; the spine (+x) stays straight longer, the edge curves
    for y0, y1, x0, x1 in ((8, 13, 5.75, 10), (13, 18, 6.25, 10.5), (18, 21.5, 7, 11), (21.5, 24, 8.25, 11.25),
                           (24, 25.5, 9.5, 11.25)):
        m.box("blade", (x0, y0, 7.25), (x1, y1, 8.75), STEEL)
    m.box("spine", (9.25, 8, 7), (10, 18, 9), STEEL_BACK)
    m.group("charm")
    m.box("skull", (6.75, -6, 7), (9.25, -3.75, 9.25), BONE)
    m.box("jaw", (7.25, -6.75, 7.25), (8.75, -6, 9), BONE)
    m.box("eyes", (7.25, -5, 9.25), (8.75, -4.25, 9.4), SOCKET)
    m.box("cord", (7.75, -3.75, 7.75), (8.25, -2.75, 8.25), ROPE)
    return _held(m, "sword")


def pirate_pickaxe():
    """해적 곡괭이: a weathered iron head on a tarred shaft, arms drooping in one bend, ground tips, a brass rivet."""
    m = MModel("pirate_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    m.box("eye", (5.5, 14.75, 6.5), (10.5, 19.75, 9.5), IRON_DARK)
    m.box("rivet", (7, 16.5, 9.5), (9, 18, 9.875), BRASS)
    m.box("wedge", (7.25, 19.75, 7.25), (8.75, 20.5, 8.75), TAR_DARK)
    for side in (1, -1):
        p = (C + side * 2.5, 17.5)
        p = _arm(m, "arm", side, p, 4.5, 1.5, 7, 9, 15, IRON)
        p = _arm(m, "arm", side, p, 3.75, 1.125, 7.25, 8.75, 40, IRON)
        px, py = p
        if side > 0:
            frm, to = (px + 0.5, py - 0.75, 7.5), (px + 2, py + 0.75, 8.5)
        else:
            frm, to = (px - 2, py - 0.75, 7.5), (px - 0.5, py + 0.75, 8.5)
        m.box("tip", frm, to, EDGE, rotation=("z", -45 * side, (px, py, C)))
    return _held(m, "pickaxe")


def pirate_shovel():
    """해적 삽: a ship's coal shovel: a square-shouldered iron blade with a brass socket and two rivets."""
    m = MModel("pirate_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    m.box("socket", (6.5, 15.5, 6.5), (9.5, 18, 9.5), BRASS)
    m.box("blade", (3.75, 18, 7.25), (12.25, 25.5, 8.75), IRON)
    m.box("blade", (4.5, 25.5, 7.5), (11.5, 27, 8.5), IRON)
    m.box("lip", (5, 27, 7.5), (11, 27.75, 8.5), EDGE)
    m.box("strap", (6.75, 18, 8.75), (9.25, 23, 9.25), IRON_DARK)         # the socket's tongue on the face
    for x in (4.5, 10.5):
        m.box("rivet", (x, 19, 8.75), (x + 1, 20, 9.25), BRASS)
    return _held(m, "shovel")


def pirate_axe():
    """해적 도끼: a boarding axe: a bearded iron blade (towards -x), a back spike, a brass cap on the shaft."""
    m = MModel("pirate_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    m.box("shaft_cap", (6.5, 21.5, 6.5), (9.5, 22.75, 9.5), BRASS)
    m.box("eye", (6.25, 16.5, 6.5), (9.75, 20.75, 9.5), IRON_DARK)
    m.box("blade", (3.5, 17, 7.125), (6.25, 20.5, 8.875), IRON)
    m.box("blade", (1.25, 13.5, 7.25), (3.5, 21, 8.75), IRON)              # the beard hangs below
    m.box("edge", (0.25, 13, 7.5), (1.25, 21.75, 8.5), EDGE)
    m.box("spike", (9.75, 17.5, 7.25), (12, 19.75, 8.75), IRON)
    m.box("spike_tip", (12, 18, 7.5), (13.75, 19.25, 8.5), EDGE)
    return _held(m, "axe")


def pirate_hoe():
    """해적 괭이: an anchor's fluke on a tarred shaft: an iron arm out to -x, a spade-shaped fluke hanging from it (across
    the handle, like the toy hoe's plate), a small hook curling up behind, a brass cap."""
    m = MModel("pirate_hoe")
    handle(m)
    shaft(m, 20)
    m.group("head")
    m.box("arm", (2.25, 19.5, 7), (10, 21.75, 9), IRON)
    m.box("cap", (6.75, 21.75, 6.75), (9.25, 23, 9.25), BRASS)
    m.box("fluke", (0.5, 15.5, 5.25), (2.75, 22.25, 10.75), IRON_DARK)
    m.box("fluke", (0.75, 13.75, 6.25), (2.5, 15.5, 9.75), IRON_DARK)
    m.box("fluke_tip", (1, 12.5, 7.25), (2.25, 13.75, 8.75), EDGE)
    m.box("hook", (10, 19.5, 7.25), (11.5, 22.75, 8.75), IRON)
    m.box("hook_tip", (10.25, 22.75, 7.5), (11.25, 24, 8.5), EDGE)
    return _held(m, "hoe")


def _anchor(m, rot):
    """A small brass anchor on the line's end (built hanging straight down, then turned with the line)."""
    m.box("anchor_ring", (7.75, 18.5, 7.75), (8.75, 19.25, 8.25), BRASS_DARK, rotation=rot)
    m.box("anchor_stock", (6.5, 17.75, 7.5), (10, 18.5, 8.5), BRASS_DARK, rotation=rot)
    m.box("anchor_shank", (7.75, 14.25, 7.5), (8.75, 17.75, 8.5), BRASS, rotation=rot)
    m.box("anchor_arms", (6, 13.5, 7.5), (10.5, 14.25, 8.5), BRASS, rotation=rot)
    m.box("anchor_fluke", (6, 14.25, 7.5), (7, 15.25, 8.5), BRASS, rotation=rot)
    m.box("anchor_fluke", (9.5, 14.25, 7.5), (10.5, 15.25, 8.5), BRASS, rotation=rot)


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    m.box("reel_foot", (9, 7.5, 7.5), (10, 9.5, 8.5), BRASS_DARK)
    m.box("reel", (10, 6.75, 6.5), (12.5, 10.25, 9.5), BRASS)
    m.box("reel_hub", (10.5, 7.75, 9.5), (12, 9.25, 10.25), TAR_DARK)
    m.group("rod")
    m.box("rod", (6.875, 6.5, 6.875), (9.125, 15, 9.125), TAR)
    m.box("rod", (7.125, 15, 7.125), (8.875, 23.5, 8.875), TAR)
    m.box("rod", (7.375, 23.5, 7.375), (8.625, 30.75, 8.625), TAR)
    for y, h in ((14.5, 6.625), (23, 6.875), (30.25, 7.125)):
        m.box("ring", (h, y, h), (16 - h, y + 1, 16 - h), BRASS)
    m.box("guide", (8.875, 19, 7.5), (10, 20, 8.5), BRASS_DARK)
    if not cast:
        m.group("line")
        top = (8.25, 31, C)
        rot = ("z", 45, top)
        m.box("line", (7.875, 19.25, 7.75), (8.625, 31, 8.25), LINE, rotation=rot)
        _anchor(m, rot)
    return _held(m, "rod")


def pirate_fishing_rod():
    """해적 낚싯대: a tarred wooden rod with brass rings, a small brass reel, a little anchor on the line."""
    return _rod("pirate_fishing_rod", False)


def pirate_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the anchor."""
    m = _rod("pirate_fishing_rod_cast", True)
    m.display = pirate_fishing_rod().display
    return m


ALL = {f.__name__: f for f in (pirate_sword, pirate_pickaxe, pirate_shovel, pirate_axe, pirate_hoe, pirate_fishing_rod,
                               pirate_fishing_rod_cast)}

ROWS = [
    ("pirate_sword", "해적 커틀러스"), ("pirate_pickaxe", "해적 곡괭이"), ("pirate_shovel", "해적 삽"),
    ("pirate_axe", "해적 도끼"), ("pirate_hoe", "해적 괭이"), ("pirate_fishing_rod", "해적 낚싯대"),
    ("pirate_fishing_rod_cast", "낚싯대 (던진 뒤)"),
]


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("pirate_sword", "pirate_pickaxe", "pirate_shovel", "pirate_axe", "pirate_hoe")}
    out["pirate_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/pirate_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/pirate_fishing_rod_cast"},
    }}
    return out
