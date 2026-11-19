"""
캠핑 시리즈 (camping series): a light tool skin set (bought with in-game gold) in the toy series' frame (models/toys.py).

    camping_sword  camping_pickaxe  camping_shovel  camping_axe  camping_hoe  camping_fishing_rod (+ camping_fishing_rod_cast)

Modern outdoor gear: matte olive-green and safety-orange coated metal, brushed aluminium edges and shafts, a black rubber
grip with two orange paracord wraps and an aluminium carabiner clipped to every pommel. Fewer, plainer boxes than the
toys (about 60 % of a toy piece): every part a single flat colour block so each piece reads at 16 x 16.

Same frame as the toys: built upright about x = z = 8, the hand on y = GRIP_Y, the views from toys._held().
"""

from economy_machines import MModel, Mat, cyl
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp

from models.toys import C, _arm, _held


# ================================================================================================
# Its own ramps (hue, chroma, centre lightness, span, steps, accent) and materials
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("camp_olive", 118, 0.052, 0.52, 0.26, 4, False)     # olive drab powder coat
_ramp("camp_orange", 46, 0.135, 0.68, 0.24, 4, False)     # safety orange
_ramp("camp_alu", 250, 0.010, 0.78, 0.26, 4, False)       # brushed aluminium
_ramp("camp_black", 265, 0.012, 0.34, 0.18, 4, False)     # black rubber

OLIVE = Mat("camp_olive", "plain", 2, bevel=(0, -1), noise=0)
OLIVE_DARK = Mat("camp_olive", "plain", 1, bevel=(0, -1), noise=0.03)
ORANGE = Mat("camp_orange", "plain", 2, bevel=(0, -1), noise=0)
ALU = Mat("camp_alu", "grain", 2, bevel=(1, -1), noise=0.03)          # brushed: faint streaks along the face
ALU_EDGE = Mat("camp_alu", "plain", 3, bevel=(0, -1), noise=0)        # a ground edge: the lightest step
BLACK = Mat("camp_black", "plain", 1, bevel=(0, -1), noise=0.04)
CORD = Mat("camp_orange", "weave", 2, bevel=None, noise=0)            # paracord
LINE = Mat("camp_alu", "plain", 3, bevel=None, noise=0)


# ================================================================================================
# What every piece shares
# ================================================================================================

def handle(m):
    """A black rubber grip (y -1.25 .. 5.25) with two orange paracord wraps, an aluminium cap and collar, and a carabiner
    hanging from the cap (a loop in the x-y plane, so the inventory sees it open)."""
    m.group("handle")
    m.box("grip", (6.75, -1.25, 6.75), (9.25, 5.25, 9.25), BLACK)
    for y in (-0.5, 3.75):
        m.box("cord", (6.5, y, 6.5), (9.5, y + 1, 9.5), CORD)
    m.box("cap", (6.5, -2.25, 6.5), (9.5, -1.25, 9.5), ALU)
    m.box("collar", (6.5, 5.25, 6.5), (9.5, 6.25, 9.5), ALU)
    m.group("carabiner")
    m.box("clip", (6.5, -5.75, 7.5), (7.25, -2.25, 8.5), ALU_EDGE)
    m.box("clip", (8.75, -5.25, 7.5), (9.5, -2.25, 8.5), ORANGE)       # the gate: orange
    m.box("clip", (7.25, -6.5, 7.5), (9.25, -5.25, 8.5), ALU_EDGE)


def shaft(m, top, mat=ALU):
    m.group("shaft")
    m.box("shaft", (7, 6.25, 7), (9, top, 9), mat)


# ================================================================================================
# The tools
# ================================================================================================

def camping_sword():
    """캠핑 마체테: a broad olive machete blade widening to a clipped tip, a bright ground edge on +x, an orange guard."""
    m = MModel("camping_sword")
    handle(m)
    m.group("guard")
    m.box("guard", (5, 6.25, 6.75), (11, 7.5, 9.25), ORANGE)
    m.group("blade")
    m.box("blade", (5.5, 7.5, 7.5), (10.25, 14, 8.5), OLIVE)
    m.box("blade", (5, 14, 7.5), (10.75, 21, 8.5), OLIVE)
    m.box("edge", (10.25, 8.25, 7.625), (11.25, 14, 8.375), ALU_EDGE)
    m.box("edge", (10.75, 14, 7.625), (11.75, 22.5, 8.375), ALU_EDGE)
    # the clipped point: the spine (-x) steps away, the edge sweeps up to it
    m.box("tip", (6.5, 21, 7.5), (10.75, 23, 8.5), OLIVE)
    m.box("tip", (8.5, 23, 7.5), (10.5, 24.5, 8.5), OLIVE)
    m.box("tip", (10, 22.5, 7.625), (11.25, 26, 8.375), ALU_EDGE)
    return _held(m, "sword")


def camping_pickaxe():
    """캠핑 곡괭이: an ice-axe head: an orange block on an aluminium shaft, an olive pick drooping to +x with a bright tip,
    a flat olive adze to -x."""
    m = MModel("camping_pickaxe")
    handle(m)
    shaft(m, 18)
    m.group("head")
    m.box("head", (6.25, 17, 6.5), (9.75, 21, 9.5), ORANGE)
    p = _arm(m, "pick", 1, (9.75, 19), 4.5, 1.25, 7, 9, 22.5, OLIVE)
    p = _arm(m, "pick", 1, p, 3.0, 1.0, 7.25, 8.75, 45, OLIVE)
    px, py = p
    m.box("pick_tip", (px + 0.5, py - 0.75, 7.5), (px + 2, py + 0.75, 8.5), ALU_EDGE, rotation=("z", -45, (px, py, C)))
    m.box("adze", (3, 18.5, 7.25), (6.25, 20.25, 8.75), OLIVE)
    m.box("adze_edge", (1.75, 17.75, 6.5), (3, 20.5, 9.5), ALU_EDGE)
    return _held(m, "pickaxe")


def camping_shovel():
    """캠핑 삽: a folding camp shovel: an orange hinge nut on the aluminium shaft, a square-shouldered olive spade with a
    pointed end and a bright edge round it."""
    m = MModel("camping_shovel")
    handle(m)
    shaft(m, 15)
    m.box("hinge", (6.25, 11, 6.75), (9.75, 13, 9.25), ORANGE)
    m.group("blade")
    m.box("socket", (6.75, 15, 6.75), (9.25, 16.5, 9.25), OLIVE_DARK)
    m.box("blade", (4, 16.5, 7.5), (12, 23, 8.5), OLIVE)
    m.box("blade", (5.25, 23, 7.5), (10.75, 25, 8.5), OLIVE)
    m.box("blade", (6.5, 25, 7.5), (9.5, 26.5, 8.5), OLIVE)
    m.box("point", (7.25, 26.5, 7.625), (8.75, 27.75, 8.375), ALU_EDGE)
    m.box("edge", (3.5, 17, 7.625), (4, 23, 8.375), ALU_EDGE)
    m.box("edge", (12, 17, 7.625), (12.5, 23, 8.375), ALU_EDGE)
    return _held(m, "shovel")


def camping_axe():
    """캠핑 손도끼: a hatchet: an orange head on an aluminium shaft, widening to a bright bearded edge towards -x, an olive
    poll behind."""
    m = MModel("camping_axe")
    handle(m)
    shaft(m, 21)
    m.group("head")
    m.box("head", (5, 15.5, 6.5), (10, 20.5, 9.5), ORANGE)
    m.box("cheek", (2.25, 14, 6.875), (5, 21, 9.125), ORANGE)
    m.box("edge", (1, 13, 7.25), (2.25, 21.75, 8.75), ALU_EDGE)
    m.box("poll", (10, 16.25, 6.75), (11.75, 19.75, 9.25), OLIVE)
    m.box("shaft_cap", (6.75, 21, 6.75), (9.25, 21.75, 9.25), OLIVE_DARK)
    return _held(m, "axe")


def camping_hoe():
    """캠핑 괭이: a small trenching hoe: an olive bar across the top of the shaft, an orange blade hanging from its end
    (across the handle, like a real hoe's), a bright edge at its foot."""
    m = MModel("camping_hoe")
    handle(m)
    shaft(m, 20.5)
    m.group("head")
    m.box("bar", (2.5, 19.75, 7), (9.5, 22, 9), OLIVE)
    m.box("plate", (0.75, 15.75, 5.5), (2.5, 22.5, 10.5), ORANGE)
    m.box("plate_edge", (1, 14.5, 5.75), (2.25, 15.75, 10.25), ALU_EDGE)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m)
    m.group("reel")
    # a small spinning reel on the +x side (where the line hangs): an aluminium spool with an orange rim, a black crank
    m.box("reel_foot", (9, 7.5, 7.5), (10.25, 9.5, 8.5), BLACK)
    cyl(m, "spool", "x", (8.5, C), 4, 10.25, 12, ALU, cap=ALU_EDGE)
    cyl(m, "spool_rim", "x", (8.5, C), 4, 12, 12.75, ORANGE, cap=ORANGE)
    m.box("crank", (11, 8, 10), (11.75, 11.25, 10.75), BLACK)
    m.box("crank_knob", (10.75, 10.5, 10.75), (12, 11.75, 11.75), ORANGE)
    m.group("rod")
    # telescopic: three black sections, each a step thinner, orange ferrules at the joints, an orange tip
    m.box("rod", (6.875, 6.25, 6.875), (9.125, 15, 9.125), BLACK)
    m.box("rod", (7.125, 15, 7.125), (8.875, 23, 8.875), BLACK)
    m.box("rod", (7.375, 23, 7.375), (8.625, 30.25, 8.625), BLACK)
    m.box("ferrule", (6.625, 14.25, 6.625), (9.375, 15.25, 9.375), ORANGE)
    m.box("ferrule", (6.875, 22.5, 6.875), (9.125, 23.5, 9.125), ORANGE)
    m.box("rod_tip", (7.25, 30.25, 7.25), (8.75, 31.75, 8.75), ORANGE)
    m.box("guide", (8.875, 18.5, 7.5), (10, 19.5, 8.5), ALU_EDGE)
    m.box("guide", (8.625, 26.5, 7.5), (9.75, 27.5, 8.5), ALU_EDGE)
    if not cast:
        # the line hangs from the tip (45 degrees in this frame = straight down on the sprite), a small orange lure on it
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.25, 21, 7.75), (8.75, 31, 8.25), LINE, rotation=rot)
        m.box("lure", (7.25, 18.25, 7.25), (9.75, 21, 8.75), ORANGE, rotation=rot)
        m.box("hook", (7.75, 17, 7.5), (9.25, 18.25, 8.5), ALU_EDGE, rotation=rot)
    return _held(m, "rod")


def camping_fishing_rod():
    """캠핑 낚싯대: a black telescopic rod with orange ferrules, a small spinning reel, a lure on the line."""
    return _rod("camping_fishing_rod", False)


def camping_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the lure."""
    m = _rod("camping_fishing_rod_cast", True)
    m.display = camping_fishing_rod().display     # the inventory picture does not jump on a cast
    return m


ALL = {f.__name__: f for f in (camping_sword, camping_pickaxe, camping_shovel, camping_axe, camping_hoe, camping_fishing_rod,
                               camping_fishing_rod_cast)}

ROWS = [
    ("camping_sword", "캠핑 마체테"), ("camping_pickaxe", "캠핑 곡괭이"), ("camping_shovel", "캠핑 삽"), ("camping_axe", "캠핑 손도끼"),
    ("camping_hoe", "캠핑 괭이"), ("camping_fishing_rod", "캠핑 낚싯대"), ("camping_fishing_rod_cast", "낚싯대 (던진 뒤)"),
]

TITLE = "캠핑 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("camping_sword", "camping_pickaxe", "camping_shovel", "camping_axe", "camping_hoe")}
    out["camping_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/camping_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/camping_fishing_rod_cast"},
    }}
    return out
