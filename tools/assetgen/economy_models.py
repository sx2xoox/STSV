"""
Economy server job machines (furniture, one block each). Built from the shared surface textures plus
three economy surfaces (water, glass, grate) registered here, same palette and noise rules.
"""

import random

from stsv_assets import presets
from stsv_assets import textures as T
from stsv_assets.model import Model
from stsv_assets.palette import ramp

S = 16


def _water(seed=301):
    rnd = random.Random(seed)
    im = T._new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2 if (x + y * 3) % 7 else 3
            px[x, y] = ramp("water", T._j(rnd, i, 0.25))
    return im


def _glass(seed=302):
    """Aquarium glass: pale water tone with a frame-light streak (opaque: items render cutout)."""
    rnd = random.Random(seed)
    im = T._new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            i = 2
            if x - y in (3, 4) or x - y in (9,):
                i = 3
            px[x, y] = ramp("water", T._j(rnd, i, 0.1))
    return im


def _grate(seed=303):
    """Dark steel grate (crusher mouth)."""
    im = T._new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = ramp("steel", 1 if (x % 4 == 0 or y % 4 == 0) else 0)
    return im


def _gear(seed=304):
    im = T._new()
    px = im.load()
    for y in range(S):
        for x in range(S):
            dx, dy = x - 7.5, y - 7.5
            r = (dx * dx + dy * dy) ** 0.5
            if r < 2:
                px[x, y] = ramp("steel", 1)
            elif r < 6 or (r < 8 and (abs(dx) < 1.6 or abs(dy) < 1.6)):
                px[x, y] = ramp("brass", 3 if dx + dy < 0 else 2 if r < 5 else 1)
            else:
                px[x, y] = ramp("steel_dark" if False else "steel", 2)
    return im


T.SURFACES.update({"eco_water": _water, "eco_glass": _glass, "eco_grate": _grate, "eco_gear": _gear})


def _done(m, scale=0.6):
    m.display = presets.furniture(gui_scale=scale)
    return m


def crusher():
    m = Model("crusher")
    m.group("base")
    m.box("base", (0, 0, 0), (16, 4, 16), "stone_bricks")
    m.group("body")
    m.box("body", (1, 4, 1), (15, 11, 15), "steel", faces={"east": "eco_gear", "west": "eco_gear"})
    m.group("hopper")
    m.box("hopper_n", (1, 11, 1), (15, 15, 3), "steel_dark")
    m.box("hopper_s", (1, 11, 13), (15, 15, 15), "steel_dark")
    m.box("hopper_w", (1, 11, 3), (3, 15, 13), "steel_dark")
    m.box("hopper_e", (13, 11, 3), (15, 15, 13), "steel_dark")
    m.box("mouth", (3, 11, 3), (13, 12, 13), "eco_grate", faces={"down": None, "north": None, "south": None, "east": None, "west": None})
    m.box("chute", (6, 1, -2), (10, 4, 0), "steel_dark", faces={"south": None})
    return _done(m)


def fish_tank():
    m = Model("fish_tank")
    m.group("stand")
    m.box("stand", (0, 0, 0), (16, 2, 16), "wood_dark")
    m.group("tank")
    m.box("water", (1, 2, 1), (15, 14, 15), "eco_glass", faces={"up": "eco_water", "down": None})
    m.group("frame")
    for i, (x, z) in enumerate(((0, 0), (15, 0), (0, 15), (15, 15))):
        m.box(f"post_{i}", (x, 2, z), (x + 1, 15, z + 1), "steel")
    m.box("top_n", (1, 14, 0), (15, 15, 1), "steel")
    m.box("top_s", (1, 14, 15), (15, 15, 16), "steel")
    m.box("top_w", (0, 14, 1), (1, 15, 15), "steel")
    m.box("top_e", (15, 14, 1), (16, 15, 15), "steel")
    return _done(m)


def reinforcing_table():
    m = Model("reinforcing_table")
    m.group("legs")
    for i, (x, z) in enumerate(((1, 1), (13, 1), (1, 13), (13, 13))):
        m.box(f"leg_{i}", (x, 0, z), (x + 2, 9, z + 2), "steel_dark")
    m.group("top")
    m.box("top", (0, 9, 0), (16, 11, 16), "steel")
    m.box("trim", (3, 9.5, -0.5), (13, 10.5, 0), "brass", faces={"south": None})
    m.group("anvil")
    m.box("anvil_foot", (5, 11, 5), (11, 12, 11), "steel_dark")
    m.box("anvil_neck", (6, 12, 6), (10, 13, 10), "steel_dark")
    m.box("anvil_top", (3, 13, 5), (13, 16, 11), "steel")
    return _done(m)


def cooking_table():
    m = Model("cooking_table")
    m.group("counter")
    m.box("counter", (0, 0, 0), (16, 10, 16), "stone_bricks")
    m.box("top", (0, 10, 0), (16, 12, 16), "wood")
    m.group("pot")
    m.box("pot", (2, 12, 3), (9, 16, 10), "copper", faces={"up": "soil"})
    m.box("handle", (9, 14, 6), (11, 15, 7), "steel_dark")
    m.group("board")
    m.box("board", (10, 12, 9), (15, 12.5, 14), "wood_grain", faces={"down": None})
    m.box("knife", (11, 12.5, 10), (14, 13, 10.5), "steel", faces={"down": None})
    return _done(m)


def smoker_oven():
    m = Model("smoker_oven")
    m.group("body")
    m.box("body", (0, 0, 0), (16, 13, 16), "brick")
    m.box("roof", (1, 13, 1), (15, 14, 15), "brick_soot")
    m.box("chimney", (10, 14, 10), (13, 16, 13), "brick_soot")
    m.group("fire")
    m.box("fire", (4, 2, -0.5), (12, 7, 0), "fire", faces={"south": None}, glow=True, full_uv=["north"])
    m.box("arch", (3, 7, -0.5), (13, 8, 0), "brick_soot", faces={"south": None})
    return _done(m)


def distiller():
    m = Model("distiller")
    m.group("base")
    m.box("base", (1, 0, 1), (15, 2, 15), "brass")
    m.group("kettle")
    m.box("kettle", (2, 2, 3), (10, 10, 11), "copper")
    m.box("dome", (3, 10, 4), (9, 12, 10), "copper")
    m.box("neck", (5, 12, 6), (7, 14, 8), "brass")
    m.box("pipe", (7, 13, 6.5), (13, 14, 7.5), "brass")
    m.group("bulb")
    m.box("bulb", (11, 3, 4), (15, 9, 10), "crystal", glow=True)
    m.box("drop", (12.5, 9, 6.5), (13.5, 13, 7.5), "brass")
    return _done(m)


ALL = {f.__name__: f for f in (crusher, fish_tank, reinforcing_table, cooking_table, smoker_oven, distiller)}


# ------------------------------------------------------------------------------------------------
# Crops: every growth stage is four crossed planes (like vanilla crops) with a hand-drawn cut-out
# texture (economy_crops.py). Plants two blocks tall (corn) stack a second set of planes on top.
# ------------------------------------------------------------------------------------------------

import economy_crops as CROPS   # noqa: E402


def _planes(m, tex, y0, y1):
    for i, x in enumerate((4, 12)):
        m.box(f"x{i}_{y0}", (x, y0, 0), (x, y1, 16), tex, faces={"north": None, "south": None, "up": None, "down": None},
              full_uv=["east", "west"])
    for i, z in enumerate((4, 12)):
        m.box(f"z{i}_{y0}", (0, y0, z), (16, y1, z), tex, faces={"east": None, "west": None, "up": None, "down": None},
              full_uv=["north", "south"])


def _crop_model(name, low, high=None):
    m = Model(name)
    m.group("plant")
    _planes(m, low, 0, 16)
    if high:
        _planes(m, high, 16, 32)
    m.display = presets.furniture(gui_scale=0.6)
    return m


def _crop_builders():
    out = {}
    for crop, stages in CROPS.STAGES.items():
        for s, im in enumerate(stages):
            if isinstance(im, tuple):
                lo, hi = f"crop_{crop}_{s}_lo", f"crop_{crop}_{s}_hi"
                T.SURFACES[lo] = (lambda img=im[0]: img.copy())
                T.SURFACES[hi] = (lambda img=im[1]: img.copy())
                out[f"{crop}_{s}"] = (lambda n=f"{crop}_{s}", a=lo, b=hi: _crop_model(n, a, b))
            else:
                tex = f"crop_{crop}_{s}"
                T.SURFACES[tex] = (lambda img=im: img.copy())
                out[f"{crop}_{s}"] = (lambda n=f"{crop}_{s}", t=tex: _crop_model(n, t))
    return out


ALL.update(_crop_builders())
