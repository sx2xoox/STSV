"""
사탕 시리즈 (candy series): a light tool skin set (gold shop), sweets instead of the toy series' plastic.

    candy_sword  candy_pickaxe  candy_shovel  candy_axe  candy_hoe  candy_fishing_rod (+ candy_fishing_rod_cast)

Same frame, grip and display transforms as models/toys.py (built upright about x = z = 8, the hand at GRIP_Y, views
from toys._held), about half the elements of a toy piece: plain shapes, pastel pink / mint / lemon, white candy
stripes, a chocolate grip with a candy ball for a pommel on every piece.

    sword    a pink-and-white striped candy-stick blade on a round mint lollipop (swirl on both faces)
    pickaxe  two wrapped hard candies (pink and lemon) drooping off a mint ball, their wrapper fans the tips
    shovel   a big cream-filled wafer scoop, its end dipped in strawberry icing
    axe      a chocolate bar with a bite out of its edge, half in a pink wrapper with a lemon foil rim
    hoe      a mint gummy bear standing at the end of a lemon candy-stick bar
    rod      a mint-striped candy stick, a lemon lollipop for a reel, a pink-and-lemon gummy worm on the line

The stripe, swirl, wafer and chocolate pictures are tileable 16 x 16 surfaces on the auto UVs (one texel a model
unit, like the toys), so the stripes run on across the parts of a piece.
"""

import math

from PIL import Image

from economy_machines import MModel, Mat, cyl
from stsv_assets import textures as T
from stsv_assets.palette import MATERIALS, PALETTE, build_ramp, ramp
from models.toys import C, _held, round_y


# ================================================================================================
# Ramps of its own (hue, chroma, centre lightness, span, steps, accent): pastel, lighter than the toys' primaries
# ================================================================================================

def _ramp(name, *spec):
    MATERIALS[name] = spec
    PALETTE[name] = build_ramp(*spec)


_ramp("candy_pink", 0, 0.080, 0.80, 0.24, 4, False)       # strawberry candy, icing, wrappers
_ramp("candy_mint", 168, 0.065, 0.84, 0.22, 4, False)     # mint candy, the gummy bear
_ramp("candy_lemon", 100, 0.080, 0.90, 0.16, 4, False)    # lemon drops, foil
_ramp("candy_choc", 45, 0.050, 0.40, 0.22, 4, False)      # milk chocolate: the grips, the bar
_ramp("candy_wafer", 75, 0.070, 0.77, 0.20, 4, False)     # wafer biscuit
_ramp("candy_white", 20, 0.010, 0.94, 0.08, 3, False)     # sugar white: stripes, sticks, cream


def _glossy(name, base=2):
    """Hard candy: one flat colour, a glint in each face's top left corner, a darker line along bottom and right."""
    return Mat(name, "metal", base, bevel=(0, -1), noise=0)


def _soft(name, base=2):
    return Mat(name, "plain", base, bevel=(0, -1), noise=0)


PINK = _glossy("candy_pink")
PINK_LIGHT = _soft("candy_pink", 3)
MINT = _glossy("candy_mint")
MINT_SOFT = _soft("candy_mint")
LEMON = _glossy("candy_lemon")
LEMON_SOFT = _soft("candy_lemon")
CHOC = _soft("candy_choc", 1)
WAFER = _soft("candy_wafer")
STICK = _soft("candy_white", 2)


# ================================================================================================
# Surfaces
# ================================================================================================

def _stripes(name):
    """Diagonal candy stripes: 5 texels of colour, 3 of white, a period of 8 (tiles across 16)."""
    def make():
        im = Image.new("RGBA", (16, 16))
        px = im.load()
        for y in range(16):
            for x in range(16):
                k = (x + y) % 8
                px[x, y] = ramp(name, 2) if k < 4 else ramp(name, 1) if k == 4 else ramp("candy_white", 2)
        return im
    return make


def _swirl(name):
    """A lollipop's face, drawn over the whole round end: a two-colour spiral, a darker rim."""
    def make():
        im = Image.new("RGBA", (16, 16))
        px = im.load()
        for y in range(16):
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                r = math.hypot(dx, dy)
                a = (math.atan2(dy, dx) / (2 * math.pi)) % 1
                band = int(r / 2.6 + a * 2) % 2
                c = ramp(name, 2) if band == 0 else ramp("candy_white", 2)
                if r > 6.6:
                    c = ramp(name, 1)
                px[x, y] = c
        return im
    return make


def _waffle():
    """Wafer face: a grid of little pockets, period 4."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = ramp("candy_wafer", 1) if x % 4 == 3 or y % 4 == 3 else ramp("candy_wafer", 3) if (x % 4 == 0 and y % 4 == 0) else ramp("candy_wafer", 2)
    return im


def _wafer_layers(vertical):
    """The wafer's edge: cream filling between the biscuit sheets (columns 7 and 8, or rows)."""
    def make():
        im = Image.new("RGBA", (16, 16))
        px = im.load()
        for y in range(16):
            for x in range(16):
                k = x if vertical else y
                px[x, y] = ramp("candy_white", 2) if k in (7, 8) else ramp("candy_wafer", 1) if k in (6, 9) else ramp("candy_wafer", 2)
        return im
    return make


def _choc_bar():
    """A chocolate bar's segments, period 4: a light top left edge, a groove along bottom and right."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = ramp("candy_choc", 0) if x % 4 == 3 or y % 4 == 3 else ramp("candy_choc", 2) if x % 4 == 0 or y % 4 == 0 else ramp("candy_choc", 1)
    return im


T.SURFACES["candy_stripe_pink"] = _stripes("candy_pink")
T.SURFACES["candy_stripe_mint"] = _stripes("candy_mint")
T.SURFACES["candy_stripe_lemon"] = _stripes("candy_lemon")
T.SURFACES["candy_swirl_mint"] = _swirl("candy_mint")
T.SURFACES["candy_swirl_lemon"] = _swirl("candy_lemon")
T.SURFACES["candy_waffle"] = _waffle
T.SURFACES["candy_wafer_v"] = _wafer_layers(True)
T.SURFACES["candy_wafer_h"] = _wafer_layers(False)
T.SURFACES["candy_choc_bar"] = _choc_bar


# ================================================================================================
# Shared parts
# ================================================================================================

def handle(m, ball, collar):
    """A candy ball for a pommel, a chocolate grip, a pastel collar (y -3.5 .. 6.5): five boxes."""
    m.group("handle")
    round_y(m, "pommel", 6.25, 9.75, 6.25, 9.75, -3.5, -1.25, ball, 0.75)
    m.box("grip", (6.75, -1.25, 6.75), (9.25, 5.25, 9.25), CHOC)
    m.box("collar", (6.25, 5.25, 6.25), (9.75, 6.5, 9.75), collar)


def stick(m, top):
    """The white lollipop stick above the collar, up to y = top."""
    m.group("stick")
    m.box("stick", (7, 6.5, 7), (9, top, 9), STICK)


# ================================================================================================
# The tools
# ================================================================================================

def candy_sword():
    """사탕 검: a pink-and-white striped candy-stick blade with a pointed tip, on a round mint lollipop for a guard."""
    m = MModel("candy_sword")
    handle(m, PINK, MINT)
    m.group("guard")
    cyl(m, "lollipop", "z", (C, 10), 8, 6.75, 9.25, MINT_SOFT, cap="candy_swirl_mint")
    m.group("blade")
    m.box("blade", (5.75, 14, 7), (10.25, 25, 9), "candy_stripe_pink")
    m.box("blade_tip", (6.5, 25, 7.25), (9.5, 26.5, 8.75), "candy_stripe_pink")
    m.box("blade_tip", (7.25, 26.5, 7.5), (8.75, 27.5, 8.5), "candy_stripe_pink")
    return _held(m, "sword")


def _candy(m, side, body, fan):
    """A wrapped hard candy for one arm of the pickaxe: drooping 22.5 degrees from the middle ball, the wrapper's twist
    and fan at its outer end (the pick's tip)."""
    px, py = C + side * 1.75, 18.0
    rot = ("z", -22.5 * side, (px, py, C))

    def box(name, a, b, y0, y1, z0, z1, mat):
        x0, x1 = (px + a, px + b) if side > 0 else (px - b, px - a)
        m.box(name, (x0, y0, z0), (x1, y1, z1), mat, rotation=rot)

    box("candy", 0, 5, 16.25, 19.75, 6.75, 9.25, body)
    box("twist", 5, 6, 17.25, 18.75, 7.25, 8.75, fan)
    box("fan", 6, 7.5, 15.5, 20.5, 7.5, 8.5, fan)


def candy_pickaxe():
    """사탕 곡괭이: a pink and a lemon wrapped candy drooping off a mint ball, the wrappers' fans for tips."""
    m = MModel("candy_pickaxe")
    handle(m, LEMON, PINK)
    stick(m, 17)
    m.group("head")
    _candy(m, 1, LEMON, LEMON_SOFT(base=3))
    _candy(m, -1, PINK, PINK_LIGHT)
    round_y(m, "ball", 5.75, 10.25, 6.5, 9.5, 15.75, 20.25, MINT, 1)
    return _held(m, "pickaxe")


def candy_shovel():
    """사탕 삽: a big cream-filled wafer scoop on a white stick, its end dipped in pink icing."""
    m = MModel("candy_shovel")
    handle(m, MINT, LEMON)
    stick(m, 15.5)
    m.group("blade")
    wafer = {"north": "candy_waffle", "south": "candy_waffle", "east": "candy_wafer_v", "west": "candy_wafer_v",
             "up": "candy_wafer_h", "down": "candy_wafer_h"}
    m.box("neck", (6.25, 15.5, 7), (9.75, 16.75, 9), WAFER)
    m.box("wafer", (3.75, 16.75, 6.75), (12.25, 23.5, 9.25), WAFER, faces=wafer)
    m.box("icing", (3.5, 23.5, 6.5), (12.5, 26.25, 9.5), PINK)
    m.box("icing_end", (5, 26.25, 6.75), (11, 27.5, 9.25), PINK)
    return _held(m, "shovel")


def candy_axe():
    """사탕 도끼: a chocolate bar for a head, a bite out of its edge (towards -x), its back half in a pink wrapper."""
    m = MModel("candy_axe")
    handle(m, PINK, MINT)
    stick(m, 22)
    m.group("head")
    bar = {"north": "candy_choc_bar", "south": "candy_choc_bar"}
    m.box("bar", (2.5, 13, 7), (8, 23, 9), CHOC, faces=bar)
    # the bitten edge: shorter columns at the top left, a round dent
    m.box("bar", (0, 13, 7), (1.25, 19.5, 9), CHOC, faces=bar)
    m.box("bar", (1.25, 13, 7), (2.5, 21, 9), CHOC, faces=bar)
    m.box("foil", (6.5, 12.75, 6.75), (7.25, 23.25, 9.25), LEMON)
    m.box("wrapper", (7.25, 12.5, 6.5), (11.5, 23.5, 9.5), PINK)
    return _held(m, "axe")


def candy_hoe():
    """사탕 괭이: a lemon candy-stick bar across the top, a mint gummy bear standing at its end for the blade (towards -x)."""
    m = MModel("candy_hoe")
    handle(m, LEMON, PINK)
    stick(m, 22.75)
    m.group("head")
    m.box("bar", (3, 20, 7.125), (9.25, 22, 8.875), "candy_stripe_lemon")
    m.group("bear")
    m.box("head", (-0.5, 19, 6.75), (3.5, 22.5, 9.25), MINT)
    m.box("ear", (-0.5, 22.5, 7.25), (0.75, 23.5, 8.75), MINT)
    m.box("ear", (2.25, 22.5, 7.25), (3.5, 23.5, 8.75), MINT)
    m.box("body", (-0.75, 14.5, 6.5), (3.75, 19, 9.5), MINT)
    m.box("feet", (-0.75, 13.25, 7), (1, 14.5, 9), MINT_SOFT)
    m.box("feet", (2, 13.25, 7), (3.75, 14.5, 9), MINT_SOFT)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    handle(m, PINK, LEMON)
    m.group("reel")
    # a lemon lollipop for a reel beside the rod's foot (+x, where the line hangs), its swirl on both faces
    m.box("reel_foot", (9, 7.75, 7.5), (10.25, 9.25, 8.5), STICK)
    cyl(m, "reel", "z", (12.25, 8.5), 5, 6.75, 9.25, LEMON_SOFT, cap="candy_swirl_lemon")
    m.group("rod")
    m.box("rod", (6.75, 6.5, 6.75), (9.25, 14, 9.25), "candy_stripe_mint")
    m.box("rod", (7, 14, 7), (9, 23, 9), "candy_stripe_mint")
    m.box("rod", (7.25, 23, 7.25), (8.75, 30.25, 8.75), "candy_stripe_mint")
    m.box("rod_tip", (6.75, 30.25, 6.75), (9.25, 31.75, 9.25), PINK)
    if not cast:
        # the line hangs from the tip (45 degrees in this frame = straight down on the sprite's diagonal), a two-colour
        # gummy worm wriggling on its end
        m.group("worm")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 22, 7.625), (8.875, 31, 8.375), STICK, rotation=rot)
        m.box("worm", (7.5, 20, 7.25), (10, 22, 8.75), PINK, rotation=rot)
        m.box("worm", (6.25, 18.25, 7.25), (8.5, 20.25, 8.75), LEMON, rotation=rot)
        m.box("worm", (7.5, 16.25, 7.25), (9.75, 18.5, 8.75), PINK, rotation=rot)
    return _held(m, "rod")


def candy_fishing_rod():
    """사탕 낚싯대: a mint-striped candy stick, a lemon lollipop reel, a gummy worm on the line."""
    return _rod("candy_fishing_rod", False)


def candy_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the worm."""
    m = _rod("candy_fishing_rod_cast", True)
    m.display = candy_fishing_rod().display     # framed as the rod with its worm: the inventory picture does not jump
    return m


ALL = {f.__name__: f for f in (candy_sword, candy_pickaxe, candy_shovel, candy_axe, candy_hoe, candy_fishing_rod,
                               candy_fishing_rod_cast)}

ROWS = [("candy_sword", "사탕 검"), ("candy_pickaxe", "사탕 곡괭이"), ("candy_shovel", "사탕 삽"), ("candy_axe", "사탕 도끼"),
        ("candy_hoe", "사탕 괭이"), ("candy_fishing_rod", "사탕 낚싯대"), ("candy_fishing_rod_cast", "낚싯대 (던진 뒤)")]

TITLE = "사탕 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("candy_sword", "candy_pickaxe", "candy_shovel", "candy_axe", "candy_hoe")}
    out["candy_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/candy_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/candy_fishing_rod_cast"},
    }}
    return out
