"""
주방 시리즈 (kitchen series, a gold-price tool skin set): kitchen utensils held as tools.

    kitchen_sword        식칼: a chef's knife, wooden handle with two rivets, a long steel blade curving up to its point
    kitchen_pickaxe      고기 망치: a meat tenderiser whose two waffle-faced heads droop like a pickaxe's arms
    kitchen_shovel       국자: a ladle full of red stew (a slice of green onion floating in it)
    kitchen_axe          중식도: a square Chinese cleaver with its hanging hole
    kitchen_hoe          뒤집개: a slotted turner hanging from a bent neck, the way the toy hoe's plate hangs
    kitchen_fishing_rod  젓가락 낚싯대: one long flat steel chopstick, a pot-lid reel, and a fish-shaped soy sauce bottle
                         on the line instead of a float (+ kitchen_fishing_rod_cast without it). No bobber model.

A lighter set than the toys: about 60 % of a toy piece's boxes, plain shapes. Same frame and machinery as models/toys.py
(upright, the grip at GRIP_Y on the axis x = z = 8, display transforms by toys._held), imported, not copied.
Stainless steel ("blade" ramp, the plastic's glint in each face's top left), handles of mint and red plastic or wood.
"""

import math

from PIL import Image

from economy_machines import MModel, Mat, cyl
from models.toys import C, _held
from stsv_assets import textures as T
from stsv_assets.palette import ramp

# ================================================================================================
# Materials
# ================================================================================================

STEEL = Mat("blade", "metal", 2, bevel=(0, -1), noise=0)         # stainless: a glint top left, a darker line bottom right
STEEL_LIGHT = Mat("blade", "plain", 3, bevel=(0, -1), noise=0)   # sharpened edges
STEEL_DARK = Mat("blade", "plain", 1, bevel=(0, -1), noise=0)    # bolsters, rivets, necks
HOLE = Mat("iron", "plain", 1, bevel=None, noise=0)
MINT = Mat("celadon", "metal", 3, bevel=(0, -1), noise=0)
MINT_DARK = Mat("celadon", "plain", 2, bevel=(0, -1), noise=0)
RED = Mat("tomato", "metal", 2, bevel=(0, -1), noise=0)
RED_DARK = Mat("tomato", "plain", 1, bevel=(0, -1), noise=0)
WOOD = Mat("wood", "grain", 2, bevel=(0, -1), noise=0.04)
WOOD_DARK = Mat("wood", "plain", 1, bevel=(0, -1), noise=0)
LINE = Mat("feather", "plain", 2, bevel=None, noise=0)
SOY = Mat("doenjang", "metal", 1, bevel=(0, -1), noise=0)         # the fish bottle: soy sauce through clear plastic
SOY_FIN = Mat("doenjang", "plain", 2, bevel=(0, -1), noise=0)
EYE = Mat("feather", "plain", 3, bevel=None, noise=0)


# ================================================================================================
# Surfaces: the ladle's stew, the ladle's back, the tenderiser's waffle face, the turner's slots
# ================================================================================================

def _disc(inner):
    """A 16 x 16 round end: a light steel rim, inner(px, x, y, d) paints the inside (d = distance from the centre)."""
    def make():
        im = Image.new("RGBA", (16, 16), ramp("blade", 2))
        px = im.load()
        for y in range(16):
            for x in range(16):
                d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
                if d > 6.2:
                    px[x, y] = ramp("blade", 3) if x + y < 15 else ramp("blade", 1)
                else:
                    inner(px, x, y, d)
        return im
    return make


def _stew(px, x, y, d):
    c = ramp("pepper", 3)
    if x + y > 17:
        c = ramp("pepper", 2)
    if (x, y) in ((5, 5), (6, 5), (5, 6)):
        c = ramp("pepper", 4)                                  # the shine on the broth
    if (x, y) in ((9, 7), (10, 7), (9, 8), (10, 8)):
        c = ramp("leaf_dark", 3)                               # a slice of green onion
    if (x, y) in ((6, 10), (7, 10)):
        c = ramp("cream", 3)                                   # a cube of tofu
    px[x, y] = c


def _back(px, x, y, d):
    px[x, y] = ramp("blade", 3) if (x - 6) ** 2 + (y - 6) ** 2 < 5 else ramp("blade", 2) if x + y < 18 else ramp("blade", 1)


def _waffle():
    """The tenderiser's striking face: rows of little pyramids, lit top left."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            a, b = x % 4, y % 4
            px[x, y] = ramp("blade", 3) if a + b < 2 else ramp("blade", 0) if a + b > 4 else ramp("blade", 2)
    return im


def _slots():
    """The turner's plate (12 x 16 texels used, two a unit): steel with three long dark slots."""
    im = Image.new("RGBA", (16, 16), ramp("blade", 2))
    px = im.load()
    for y in range(16):
        for x in range(12):
            if y == 15 or x == 11:
                px[x, y] = ramp("blade", 1)
            elif x == 0 or y == 0:
                px[x, y] = ramp("blade", 3)
            elif x in (3, 6, 9) and 3 <= y <= 12:
                px[x, y] = ramp("iron", 1)
    return im


T.SURFACES["kitchen_stew"] = _disc(_stew)
T.SURFACES["kitchen_steel_disc"] = _disc(_back)
T.SURFACES["kitchen_waffle"] = _waffle
T.SURFACES["kitchen_slots"] = _slots


# ================================================================================================
# Shared parts
# ================================================================================================

def plastic_handle(m, mat, dark, top=6.5):
    """A plastic handle (y -3.5 .. top) with a darker end cap and a steel ferrule on top."""
    m.group("handle")
    m.box("cap", (6.5, -3.5, 6.5), (9.5, -2.5, 9.5), dark)
    m.box("handle", (6.75, -2.5, 6.75), (9.25, top - 0.75, 9.25), mat)
    m.box("ferrule", (6.5, top - 0.75, 6.5), (9.5, top, 9.5), STEEL_DARK)


def wood_handle(m, top=6.5, rivets=(0.25, 4.0)):
    """A flat wooden knife handle (y -3.25 .. top), the tang's rivets showing through it front and back."""
    m.group("handle")
    m.box("handle", (6.75, -3.25, 7), (9.25, top, 9), WOOD)
    m.box("handle_end", (7, -3.75, 7.25), (9, -3.25, 8.75), WOOD_DARK)
    for y in rivets:
        m.box("rivet", (7.5, y, 6.75), (8.5, y + 1, 9.25), STEEL_LIGHT)


def stem(m, top, y0=6.5):
    m.group("stem")
    m.box("stem", (7.25, y0, 7.25), (8.75, top, 8.75), STEEL)


# ================================================================================================
# The tools
# ================================================================================================

def kitchen_sword():
    """식칼: a chef's knife. A long blade, the spine straight on +x, the sharpened edge on -x curving up to the point."""
    m = MModel("kitchen_sword")
    wood_handle(m)
    m.group("blade")
    m.box("bolster", (6.5, 6.5, 6.75), (10, 7.75, 9.25), STEEL_DARK)
    rows = ((7.75, 19.5, 4.0), (19.5, 22, 4.75), (22, 24, 5.75), (24, 25.5, 7.0), (25.5, 26.75, 8.5))
    for y0, y1, x0 in rows:
        m.box("blade", (x0, y0, 7.5), (10, y1, 8.5), STEEL)
    m.box("edge", (3.25, 8.25, 7.625), (4.0, 19.5, 8.375), STEEL_LIGHT)
    m.box("edge", (4.0, 19.5, 7.625), (4.75, 21.5, 8.375), STEEL_LIGHT)
    return _held(m, "sword")


def _bar(m, name, side, pivot, length, half_h, z0, z1, angle, mat, faces=None):
    """A box along x from pivot (on its centre line), drooping by angle; returns the far end of its centre line."""
    px, py = pivot
    if side > 0:
        frm, to = (px - 0.5, py - half_h, z0), (px + length, py + half_h, z1)
    else:
        frm, to = (px - length, py - half_h, z0), (px + 0.5, py + half_h, z1)
    m.box(name, frm, to, mat, faces=faces, rotation=("z", -angle * side, (px, py, C)))
    a = math.radians(angle)
    return px + side * (length - 0.5) * math.cos(a), py - (length - 0.5) * math.sin(a)


def kitchen_pickaxe():
    """고기 망치: a meat tenderiser drawn out into a pickaxe. A steel hub on a red handle, two arms drooping (22.5 then
    45 degrees), each ending in a fat head whose outer face is the waffle of little pyramids."""
    m = MModel("kitchen_pickaxe")
    plastic_handle(m, RED, RED_DARK)
    stem(m, 16)
    m.group("head")
    m.box("hub", (5.5, 15.5, 6.5), (10.5, 20.5, 9.5), STEEL)
    m.box("hub_cap", (6.5, 20.5, 7), (9.5, 21.25, 9), RED)
    for side in (1, -1):
        p = _bar(m, "arm", side, (C + side * 2.5, 18), 4.0, 1.25, 7, 9, 22.5, STEEL_DARK)
        end = "east" if side > 0 else "west"
        _bar(m, "hammer", side, p, 3.25, 2.25, 6.25, 9.75, 45, STEEL, faces={end: "kitchen_waffle"})
    return _held(m, "pickaxe")


def kitchen_shovel():
    """국자: a deep round ladle, full of red stew, on a long steel neck and a mint handle."""
    m = MModel("kitchen_shovel")
    plastic_handle(m, MINT, MINT_DARK)
    stem(m, 16.5)
    m.group("bowl")
    # -z (north) is the ladle's round back, +z (south: the inventory's and the player's side) its stew
    cyl(m, "bowl", "z", (C, 21), 10, 6.25, 9.75, STEEL, cap="kitchen_steel_disc", cap2="kitchen_stew")
    return _held(m, "shovel")


def kitchen_axe():
    """중식도: a square cleaver beside the top of a wooden handle (its edge on -x, like the sprite's axe blade), a
    hanging hole by the spine's far end."""
    m = MModel("kitchen_axe")
    wood_handle(m, top=10, rivets=(1.0, 5.5))
    m.group("blade")
    m.box("bolster", (6.5, 10, 6.75), (9.75, 11, 9.25), STEEL_DARK)
    m.box("blade", (1.5, 11, 7.5), (9.75, 23, 8.5), STEEL)
    m.box("edge", (0.75, 11.25, 7.625), (1.5, 23, 8.375), STEEL_LIGHT)
    m.box("hole", (6.75, 19.75, 7.375), (8.25, 21.25, 8.625), HOLE)
    return _held(m, "axe")


def kitchen_hoe():
    """뒤집개: a slotted turner. A steel neck bends off the top of a red-handled stem (towards -x) and the plate hangs
    from it across the handle (z), the slots on both its faces."""
    m = MModel("kitchen_hoe")
    plastic_handle(m, RED, RED_DARK)
    stem(m, 21.5)
    m.group("head")
    m.box("neck", (2, 20, 7.25), (8.75, 21.5, 8.75), STEEL)
    m.box("plate", (0.75, 13, 5), (2, 21.5, 11), STEEL,
          faces={"west": "kitchen_slots", "east": "kitchen_slots"}, uv={"west": [0, 0, 12, 16], "east": [0, 0, 12, 16]})
    m.box("plate_edge", (1, 12.25, 5.25), (1.75, 13, 10.75), STEEL_LIGHT)
    return _held(m, "hoe")


def _rod(name, cast):
    m = MModel(name)
    plastic_handle(m, MINT, MINT_DARK)
    m.group("reel")
    # a little pot lid for a reel, beside the rod's foot (+x, where the line hangs), its red knob towards +z
    m.box("reel_foot", (9.25, 7.75, 7.5), (10.25, 9.25, 8.5), STEEL_DARK)
    cyl(m, "reel", "z", (12.25, 8.5), 5, 6.75, 9.25, STEEL, cap=STEEL_DARK)
    m.box("reel_knob", (11.5, 7.75, 9.25), (13, 9.25, 10.5), RED)
    m.group("rod")
    # one long flat stainless chopstick, tapering towards the tip, a red band where it leaves the handle
    m.box("rod", (7, 6.5, 7.25), (9, 15, 8.75), STEEL)
    m.box("rod", (7.25, 15, 7.375), (8.75, 23.5, 8.625), STEEL)
    m.box("rod", (7.5, 23.5, 7.5), (8.5, 31, 8.5), STEEL)
    m.box("band", (6.75, 8, 7), (9.25, 9, 9), RED)
    if not cast:
        # the line hangs from the tip: straight down on the sprite's diagonal = 45 degrees in this frame
        m.group("line")
        top = (8.5, 31, C)
        rot = ("z", 45, top)
        m.box("line", (8.125, 20.75, 7.625), (8.875, 31, 8.375), LINE, rotation=rot)
        # a fish-shaped soy sauce bottle hanging nose up: a red cap, a brown body, a white eye, a forked tail
        m.box("fish_cap", (7.5, 19.75, 7.5), (9.5, 20.75, 8.5), RED, rotation=rot)
        m.box("fish", (6.75, 14.75, 7), (10.25, 19.75, 9), SOY, rotation=rot)
        m.box("fish_eye", (7.25, 18, 8.75), (8.25, 19, 9.25), EYE, rotation=rot)
        m.box("fish_tail", (7.75, 13.75, 7.25), (9.25, 14.75, 8.75), SOY_FIN, rotation=rot)
        m.box("fish_tail", (6.75, 12.5, 7.25), (10.25, 13.75, 8.75), SOY_FIN, rotation=rot)
    return _held(m, "rod")


def kitchen_fishing_rod():
    """젓가락 낚싯대: a long steel chopstick for a rod, a pot-lid reel, a fish-shaped soy sauce bottle on the line."""
    return _rod("kitchen_fishing_rod", False)


def kitchen_fishing_rod_cast():
    """The rod while its line is out: the same without the line and the fish bottle."""
    m = _rod("kitchen_fishing_rod_cast", True)
    m.display = kitchen_fishing_rod().display     # framed as the uncast rod: the inventory picture does not jump on a cast
    return m


# ================================================================================================
# Exports
# ================================================================================================

ALL = {f.__name__: f for f in (kitchen_sword, kitchen_pickaxe, kitchen_shovel, kitchen_axe, kitchen_hoe, kitchen_fishing_rod,
                               kitchen_fishing_rod_cast)}

ROWS = [
    ("kitchen_sword", "식칼 검"), ("kitchen_pickaxe", "고기 망치 곡괭이"), ("kitchen_shovel", "국자 삽"),
    ("kitchen_axe", "중식도 도끼"), ("kitchen_hoe", "뒤집개 괭이"), ("kitchen_fishing_rod", "젓가락 낚싯대"),
    ("kitchen_fishing_rod_cast", "낚싯대 (던진 뒤)"),
]

TITLE = "주방 시리즈 (도구 스킨)"


def item_definitions():
    """assets/stsv/items/<name>.json: what an item_model of stsv:<name> shows."""
    plain = lambda n: {"model": {"type": "minecraft:model", "model": f"stsv:item/{n}"}}
    out = {n: plain(n) for n in ("kitchen_sword", "kitchen_pickaxe", "kitchen_shovel", "kitchen_axe", "kitchen_hoe",
                                 "kitchen_fishing_rod_cast")}
    out["kitchen_fishing_rod"] = {"model": {
        "type": "minecraft:condition",
        "property": "minecraft:fishing_rod/cast",
        "on_false": {"type": "minecraft:model", "model": "stsv:item/kitchen_fishing_rod"},
        "on_true": {"type": "minecraft:model", "model": "stsv:item/kitchen_fishing_rod_cast"},
    }}
    return out
