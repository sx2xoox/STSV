"""
Economy server 3D models: the job machines (economy_machines.py) and the crop growth stages. Also
registers four shared economy surfaces (water, glass, grate, gear) used by the machines.
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


# job machines: six multi-block workstations in four tier looks, plus their spinning parts
import economy_machines as MACHINES   # noqa: E402

ALL = dict(MACHINES.models())



# ------------------------------------------------------------------------------------------------
# Crops: each growth stage is a hand-drawn cut-out picture (economy_crops.py) on planes standing the way
# vanilla plants do: "hash" = four planes at 4 and 12 (wheat, carrots), "cross" = two diagonal planes
# (flowers, saplings). Like vanilla, the planes start 1px down in the farmland, have no shading by
# direction, and show the picture the same way round from both sides. Bulbs, roots and heads are solid
# boxes. Plants two blocks tall (corn) stack a second set of planes on top.
# ------------------------------------------------------------------------------------------------

import economy_crops as CROPS   # noqa: E402

SOIL = -1      # farmland is 15/16 high: its top is 1px below the crop block's floor
FRONT, BACK = [0, 0, 16, 16], [16, 0, 0, 16]      # the back face mirrored back, so the picture lines up


def _planes(m, tex, y0, layout):
    y1 = y0 + 16
    if layout == "hash":
        for i, x in enumerate((4, 12)):
            m.box(f"x{i}_{y0}", (x, y0, 0), (x, y1, 16), tex, faces={"north": None, "south": None, "up": None, "down": None},
                  uv={"east": FRONT, "west": BACK}, shade=False)
        for i, z in enumerate((4, 12)):
            m.box(f"z{i}_{y0}", (0, y0, z), (16, y1, z), tex, faces={"east": None, "west": None, "up": None, "down": None},
                  uv={"north": FRONT, "south": BACK}, shade=False)
    else:
        m.box(f"a_{y0}", (0, y0, 8), (16, y1, 8), tex, faces={"east": None, "west": None, "up": None, "down": None},
              uv={"north": FRONT, "south": BACK}, rotation=("y", 45, (8, 8, 8)), shade=False)
        m.box(f"b_{y0}", (8, y0, 0), (8, y1, 16), tex, faces={"north": None, "south": None, "up": None, "down": None},
              uv={"east": FRONT, "west": BACK}, rotation=("y", 45, (8, 8, 8)), shade=False)


def _crop_model(name, low, high, layout, parts):
    m = Model(name)
    m.group("plant")
    _planes(m, low, SOIL, layout)
    if high:
        _planes(m, high, SOIL + 16, layout)
    if parts:
        m.group("solid")
        for i, (side, top, p) in enumerate(parts):
            m.box(f"part{i}", p.frm, p.to, side, faces={"up": top, "down": top}, uv=p.uv())
    m.display = presets.furniture(gui_scale=0.6)
    return m


def _crop_builders():
    out = {}
    for crop, stages in CROPS.STAGES.items():
        for s, st in enumerate(stages):
            st = CROPS.stage(st)
            name = f"{crop}_{s}"
            if isinstance(st.planes, tuple):
                lo, hi = f"crop_{name}_lo", f"crop_{name}_hi"
                T.SURFACES[lo] = (lambda img=st.planes[0]: img.copy())
                T.SURFACES[hi] = (lambda img=st.planes[1]: img.copy())
            else:
                lo, hi = f"crop_{name}", None
                T.SURFACES[lo] = (lambda img=st.planes: img.copy())
            parts, seen = [], {}
            for p in st.parts:
                names = []
                for img in p.textures():
                    key = img.tobytes()
                    if key not in seen:          # the same bulb four times: one texture
                        seen[key] = f"crop_{name}_part{len(seen)}"
                        T.SURFACES[seen[key]] = (lambda im=img: im.copy())
                    names.append(seen[key])
                parts.append((names[0], names[1], p))
            out[name] = (lambda n=name, a=lo, b=hi, lay=st.layout, ps=tuple(parts): _crop_model(n, a, b, lay, ps))
    return out


ALL.update(_crop_builders())

# 하역장 props (models/docks.py): the crane hook, a forklift, a pallet, shipping containers
from models import docks as DOCKS   # noqa: E402
ALL.update(DOCKS.ALL)

# 국도 direction sign (models/roads.py): posts, the green panel, its frame
from models import roads as ROADS   # noqa: E402
ALL.update(ROADS.ALL)

# 통발 · 가두리 (models/fishing_gear.py): the fisher's water furniture
from models import fishing_gear as FISHING_GEAR   # noqa: E402
ALL.update(FISHING_GEAR.ALL)
