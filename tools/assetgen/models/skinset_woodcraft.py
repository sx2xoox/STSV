"""
목각 공방 시리즈 (woodcraft series): hand-carved wooden looks for held tools, bought with in-game gold.

    woodcraft_sword  woodcraft_pickaxe  woodcraft_shovel  woodcraft_axe  woodcraft_hoe
    woodcraft_fishing_rod (+ woodcraft_fishing_rod_cast)

A "light" set: the toy series' frame, sizes and display transforms (models/toys.py: upright, the grip at GRIP_Y on the
axis x = z = 8, _held() for the views), with about 60 % of a toy piece's boxes. Light oak heads and blades with their
grain running along them, a bevel on every box for the chisel's facets, walnut where the parts join, natural jute twine
round the joints, a dark walnut pommel, and on each piece a little carved leaf (a square turned 45 degrees: a diamond)
or an acorn. The rod is a dried-bamboo rod with twine nodes and a carved fish on its tip; no float of its own.
"""

import json
from pathlib import Path

from economy_machines import MModel, Mat
from models.toys import C, _held, round_y

# ================================================================================================
# Materials
# ================================================================================================

OAK = Mat("hinoki", "grain", 2, bevel=(1, -1), noise=0.03)            # light oak: blades and heads, grain along them
OAK_LIGHT = Mat("hinoki", "plain", 3, bevel=(0, -1), noise=0.02)      # the carved edges (a chisel's bright facet)
OAK_PLAIN = Mat("wood", "plain", 3, bevel=(1, -1), noise=0.03)
WALNUT = Mat("wood_dark", "grain", 1, bevel=(1, -1), noise=0.04)    # collars, heads' cores, the acorn caps
WALNUT_DARK = Mat("wood_dark", "plain", 0, bevel=(1, -1), noise=0.03)   # the pommel
HANDLE = Mat("wood", "grain", 1, bevel=(1, -1), noise=0.04)         # the grip and shafts: a shade darker than the heads
BAMBOO = Mat("straw", "grain", 2, bevel=(1, -1), noise=0.03)        # the rod: dried bamboo
TWINE = Mat("burlap", "weave", 2, bevel=(0, -1), noise=0)           # jute bindings
LEAF = Mat("sprout", "plain", 2, bevel=(1, -1), noise=0)              # a painted carved leaf
LINE = Mat("burlap", "plain", 3, bevel=None, noise=0)
EYE = Mat("wood_dark", "plain", 0, bevel=None, noise=0)


# ================================================================================================
# Shared parts
# ================================================================================================

def handle(m):
    """Every piece's handle: a dark walnut pommel, an oak grip with two twine wraps (y -3.25 .. 6.5)."""
    m.group("handle")
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.25, -1.25, WALNUT_DARK, 0.75)
    m.box("grip", (6.75, -1.25, 6.75), (9.25, 6.5, 9.25), HANDLE)
    for y in (0.25, 3.5):
        m.box("twine", (6.5, y, 6.5), (9.5, y + 1, 9.5), TWINE)


def shaft(m, top, lo=6.5):
    m.group("shaft")
    m.box("shaft", (6.875, lo, 6.875), (9.125, top, 9.125), HANDLE)


def leaf(m, cx, cy, z0, z1, r=1.5):
    """A carved leaf through the part, proud of both faces: a long body and a pointed tip along y (the views turn the
    model 45 degrees, so it shows as a slanted leaf)."""
    m.box("leaf", (cx - r * 0.67, cy - r, z0), (cx + r * 0.67, cy + r, z1), LEAF)
    m.box("leaf_tip", (cx - r * 0.33, cy + r, z0 + 0.125), (cx + r * 0.33, cy + r * 1.5, z1 - 0.125), LEAF)


def acorn(m, cx, cy, z0, z1):
    """A carved acorn hanging down: a walnut cap over an oak nut."""
    m.box("acorn_cap", (cx - 1.25, cy, z0), (cx + 1.25, cy + 1, z1), WALNUT)
    m.box("acorn", (cx - 0.875, cy - 1.75, z0 + 0.25), (cx + 0.875, cy, z1 - 0.25), OAK_PLAIN)


# ================================================================================================
# The tools
# ================================================================================================

def woodcraft_sword():
    """목각 검: an oak blade with bright chiselled edges and a walnut ridge, a walnut guard with acorn ends, a leaf on it."""
    m = MModel("woodcraft_sword")
    handle(m)
    m.group("guard")
    m.box("guard", (3.5, 6.5, 6.75), (12.5, 8.5, 9.25), WALNUT)
    acorn(m, 2.75, 7.75, 6.75, 9.25)
    acorn(m, 13.25, 7.75, 6.75, 9.25)
    leaf(m, C, 7.25, 6.25, 9.75, 1.25)
    m.group("blade")
    m.box("blade", (5.75, 8.5, 7), (10.25, 23, 9), OAK)
    m.box("blade_edge", (4.75, 9.5, 7.375), (5.75, 22.25, 8.625), OAK_LIGHT)
    m.box("blade_edge", (10.25, 9.5, 7.375), (11.25, 22.25, 8.625), OAK_LIGHT)
    m.box("blade_ridge", (7.5, 10, 6.875), (8.5, 22, 9.125), HANDLE)
    m.box("blade_tip", (6.5, 23, 7.25), (9.5, 24.75, 8.75), OAK_LIGHT)
    m.box("blade_tip", (7.25, 24.75, 7.5), (8.75, 26, 8.5), OAK_LIGHT)
    return _held(m, "sword")


def woodcraft_pickaxe():
    """목각 곡괭이: a walnut core bound with twine, two drooping oak arms with bright tips, a leaf on the core."""
    m = MModel("woodcraft_pickaxe")
    handle(m)
    shaft(m, 16)
    m.group("head")
    m.box("head", (5.5, 15.5, 6.5), (10.5, 21, 9.5), WALNUT)
    m.box("twine", (6.5, 14.25, 6.5), (9.5, 15.5, 9.5), TWINE)
    leaf(m, C, 17.75, 6.25, 9.75, 1.25)
    for side in (1, -1):
        # the arm: one box drooping 22.5, its tip a box drooping 45 further out
        if side > 0:
            m.box("arm", (10, 16.875, 6.875), (17, 19.625, 9.125), OAK, rotation=("z", -22.5, (10.5, 18.25, C)))
            m.box("arm_tip", (15.5, 12.5, 7.25), (17.5, 16.75, 8.75), OAK_LIGHT, rotation=("z", -45, (16.5, 15.75, C)))
        else:
            m.box("arm", (-1, 16.875, 6.875), (6, 19.625, 9.125), OAK, rotation=("z", 22.5, (5.5, 18.25, C)))
            m.box("arm_tip", (-1.5, 12.5, 7.25), (0.5, 16.75, 8.75), OAK_LIGHT, rotation=("z", 45, (-0.5, 15.75, C)))
    return _held(m, "pickaxe")


def woodcraft_shovel():
    """목각 삽: a wide oak spade with a bright rim and a rounded point, a twine-bound walnut socket, a leaf on its face."""
    m = MModel("woodcraft_shovel")
    handle(m)
    shaft(m, 15.5)
    m.group("blade")
    m.box("socket", (6.25, 15.5, 6.5), (9.75, 17.25, 9.5), WALNUT)
    m.box("twine", (6.5, 14.25, 6.5), (9.5, 15.5, 9.5), TWINE)
    m.box("blade", (4, 17.25, 7.125), (12, 24, 8.875), OAK)
    m.box("blade", (4.75, 24, 7.25), (11.25, 26, 8.75), OAK)
    m.box("point", (5.75, 26, 7.25), (10.25, 27.5, 8.75), OAK_LIGHT)
    m.box("point", (7, 27.5, 7.375), (9, 28.5, 8.625), OAK_LIGHT)
    leaf(m, C, 20.5, 6.75, 9.25, 1.5)
    return _held(m, "shovel")


def woodcraft_axe():
    """목각 도끼: a walnut eye on the shaft, an oak bit widening to a bright edge (towards -x), twine below, an acorn cap."""
    m = MModel("woodcraft_axe")
    handle(m)
    shaft(m, 21.5)
    m.group("head")
    m.box("cap", (6.5, 21.5, 6.5), (9.5, 22.5, 9.5), WALNUT)
    m.box("head", (5.5, 14.5, 6.5), (10.5, 20.5, 9.5), WALNUT)
    m.box("twine", (6.5, 13.25, 6.5), (9.5, 14.5, 9.5), TWINE)
    m.box("bit", (1.5, 13, 7), (5.5, 22, 9), OAK)
    m.box("bit_edge", (-0.25, 12, 7.25), (1.5, 23, 8.75), OAK_LIGHT)
    m.box("poll", (10.5, 15.5, 6.875), (12, 19.5, 9.125), OAK)
    leaf(m, 3.5, 17, 6.75, 9.25, 1.25)
    return _held(m, "axe")


def woodcraft_hoe():
    """목각 괭이: a walnut bar on the shaft's top, an oak plate hanging from its end, twine at the joint, a leaf on the plate."""
    m = MModel("woodcraft_hoe")
    handle(m)
    shaft(m, 20)
    m.group("head")
    m.box("bar", (2.5, 20, 6.75), (10, 22.5, 9.25), WALNUT)
    m.box("twine", (6.5, 18.75, 6.5), (9.5, 20, 9.5), TWINE)
    m.box("plate", (0.25, 15, 5.25), (2.5, 22.75, 10.75), OAK)
    m.box("plate_edge", (0.5, 14, 5.5), (2.25, 15, 10.5), OAK_LIGHT)
    leaf(m, 5, 21.25, 6.5, 9.5, 1.0)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    # a small wooden spool beside the rod's foot, wound with twine
    m.box("spool", (9.25, 7, 6.5), (12.25, 10.5, 9.5), WALNUT)
    m.box("spool_line", (9.5, 7.75, 6.25), (12, 9.75, 9.75), TWINE)
    m.group("rod")
    m.box("rod", (6.75, 6.5, 6.75), (9.25, 14, 9.25), BAMBOO)
    m.box("rod", (7, 14, 7), (9, 22, 9), BAMBOO)
    m.box("rod", (7.25, 22, 7.25), (8.75, 29.25, 8.75), BAMBOO)
    m.box("node", (6.5, 13.5, 6.5), (9.5, 14.5, 9.5), TWINE)
    m.box("node", (6.75, 21.5, 6.75), (9.25, 22.5, 9.25), TWINE)
    m.group("fish")
    # the carved fish on the tip: nose towards +x (where the line hangs), its tail fork a diamond behind
    m.box("fish", (7, 29.25, 7.125), (10.75, 31.5, 8.875), OAK)
    m.box("fish_nose", (10.75, 29.75, 7.375), (11.5, 31, 8.625), OAK)
    m.box("fish_tail", (5.5, 29.5, 7.375), (7.25, 31.25, 8.625), OAK_LIGHT, rotation=("z", 45, (6.375, 30.375, C)))
    m.box("fish_eye", (9.5, 30.5, 6.875), (10.25, 31.25, 9.125), EYE)
    if not cast:
        m.group("line")
        top = (11, 30, C)
        rot = ("z", 45, top)
        m.box("line", (10.625, 20, 7.625), (11.375, 30, 8.375), LINE, rotation=rot)
        # a carved acorn as the sinker
        m.box("acorn_cap", (9.75, 19, 6.75), (12.25, 20, 9.25), WALNUT, rotation=rot)
        m.box("acorn", (10.125, 17, 7), (11.875, 19, 9), OAK_PLAIN, rotation=rot)
    return _held(m, "rod")


def woodcraft_fishing_rod():
    """목각 낚싯대: a dried-bamboo rod with twine nodes, a wooden spool, a carved fish on the tip, an acorn sinker."""
    return _rod("woodcraft_fishing_rod", False)


def woodcraft_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the acorn."""
    m = _rod("woodcraft_fishing_rod_cast", True)
    m.display = woodcraft_fishing_rod().display
    return m


ALL = {f.__name__: f for f in (woodcraft_sword, woodcraft_pickaxe, woodcraft_shovel, woodcraft_axe, woodcraft_hoe,
                               woodcraft_fishing_rod, woodcraft_fishing_rod_cast)}

ROWS = [
    ("woodcraft_sword", "목각 검"), ("woodcraft_pickaxe", "목각 곡괭이"), ("woodcraft_shovel", "목각 삽"),
    ("woodcraft_axe", "목각 도끼"), ("woodcraft_hoe", "목각 괭이"), ("woodcraft_fishing_rod", "목각 낚싯대"),
    ("woodcraft_fishing_rod_cast", "낚싯대 (던진 뒤)"),
]

TITLE = "목각 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("woodcraft_sword", "woodcraft_pickaxe", "woodcraft_shovel", "woodcraft_axe", "woodcraft_hoe")}
    out["woodcraft_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/woodcraft_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/woodcraft_fishing_rod_cast"},
    }}
    return out


def write_item_definitions(content: Path):
    d = content / "assets" / "stsv" / "items"
    d.mkdir(parents=True, exist_ok=True)
    for name, definition in item_definitions().items():
        (d / f"{name}.json").write_text(json.dumps(definition, indent=2) + "\n")
    return sorted(item_definitions())
