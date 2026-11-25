"""
Preview of the 도박장 (economy: HallBuilder, docs/plans/도박장_기획.md §1) as the plugin builds it, drawn from the layout a
test writes:

    cd economy && ./gradlew test --tests '*HallLayout*'      # writes economy/build/docks/gamble_hall.json (HallLayout.of)
    python3 tools/assetgen/gamble_hall_preview.py           # docs/previews/gamble_hall.png and gamble_hall_top.png
                                                            # (needs: cd tools/assetgen/render && npm install)

Drawn as dock_preview draws a dock (plain-coloured cubes, no vanilla textures), with the block shapes that matter here
(stairs, slabs, trapdoors, panes and bars joined as the layout joins them, chains, lanterns, end-rod tubes). The display
models are the pack's own (economy_models.ALL, as the plugin shows them: FIXED, base on the point) when they are there,
else stand-ins of about their size; the tables are stand-ins of the furniture (green cloth on a cable drum, chairs round
it); printed labels are drawn as the text displays show them.

    gamble_hall.png      from the gate (front right: the open container doors, the stairs) and front left (scaffolding)
    gamble_hall_top.png  roof off: the ground floor (tables, counter, lounge, whiteboard) and the 2층 구경석 over it
"""

import base64
import io
import json
import math
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import dock_preview as DP  # noqa: E402

REPO = HERE.parent.parent
DATA = REPO / "economy" / "build" / "docks" / "gamble_hall.json"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
ROOF, UP_ROOF = 4, 8

COLOURS = {
    **DP.COLOURS,
    "coarse_dirt": (119, 85, 59), "gravel": (131, 127, 126), "dirt": (134, 96, 67), "packed_mud": (142, 106, 79), "andesite": (136, 136, 137),
    "polished_deepslate": (72, 72, 73), "deepslate_tiles": (54, 54, 55), "blue_concrete": (45, 47, 143), "light_gray_concrete": (125, 125, 115),
    "white_concrete": (207, 213, 214), "black_concrete": (8, 10, 15), "yellow_concrete": (241, 175, 21), "red_terracotta": (143, 61, 47),
    "red_concrete": (142, 33, 33), "brown_terracotta": (77, 51, 36), "green_concrete": (73, 91, 36), "green_terracotta": (76, 83, 42),
    "black_terracotta": (37, 23, 16), "blue_terracotta": (74, 60, 91), "gray_concrete": (55, 58, 62), "yellow_terracotta": (186, 133, 35),
    "birch_planks": (192, 175, 121), "stripped_birch_wood": (196, 176, 118), "stripped_oak_wood": (177, 144, 86), "spruce_slab": (115, 85, 49),
    "spruce_stairs": (115, 85, 49), "iron_chain": (60, 64, 74), "iron_bars": (150, 152, 150), "gray_stained_glass_pane": (76, 76, 76),
    "glass_pane": (176, 214, 226), "green_stained_glass_pane": (102, 127, 51), "lightning_rod": (192, 107, 79), "lantern": (236, 190, 100),
    "polished_deepslate_stairs": (72, 72, 73), "polished_deepslate_wall": (72, 72, 73), "iron_trapdoor": (190, 190, 190),
    "ladder": (125, 97, 56), "end_rod": (240, 236, 228), "green_carpet": (84, 109, 27), "light_weighted_pressure_plate": (230, 190, 60),
    "black_concrete_safe": (20, 20, 22), "dark_oak_slab": (66, 43, 20), "red_stained_glass": (153, 51, 51), "light_blue_stained_glass": (102, 153, 216),
    "cauldron": (74, 72, 76), "spruce_planks": (115, 85, 49), "oak_planks": (162, 131, 79), "stripped_spruce_log": (115, 89, 52),
    "dark_oak_planks": (66, 43, 20), "light_blue_concrete": (36, 137, 199), "light_blue_terracotta": (113, 108, 137),
    "andesite_wall": (136, 136, 137), "gray_wool": (62, 68, 71), "grass": (106, 150, 64),
    # stand-ins
    "felt": (38, 104, 58), "felt_dark": (26, 74, 41), "drum": (120, 86, 52), "chair_red": (176, 48, 40), "chair_blue": (40, 86, 168),
    "chair_white": (220, 220, 214), "paint_can": (196, 196, 190), "cone": (240, 110, 20), "cone_white": (240, 240, 240),
    "steel": (90, 92, 96), "light": (255, 236, 170), "fire": (255, 140, 40), "pallet": (170, 136, 86), "cement": (196, 186, 160),
    "rebar": (120, 60, 40), "board": (240, 240, 236), "sign_red": (200, 30, 30), "lime_wall_banner": (128, 199, 31),
    "orange_wall_banner": (240, 118, 19),
}
GLOW = {"lantern", "end_rod", "light", "fire", "sea_lantern"}
GLASSY = {"gray_stained_glass_pane": 0.75, "glass_pane": 0.45, "green_stained_glass_pane": 0.7, "red_stained_glass": 0.7,
          "light_blue_stained_glass": 0.6}


def el(frm, to, tex, glow=False, opacity=None, faces=DP.FACES):
    e = DP._el(frm, to, tex, glow or tex in GLOW, faces)
    if opacity is not None:
        e["opacity"] = opacity
    return e


def parse(data):
    """'minecraft:spruce_stairs[facing=east,half=bottom]' -> ('spruce_stairs', {'facing': 'east', ...})."""
    name, _, rest = data.split(":", 1)[1].partition("[")
    props = dict(kv.split("=") for kv in rest.rstrip("]").split(",")) if rest else {}
    return name, props


# the layout writes states for a hall facing south: local east = -x = preview +x (the preview mirrors x), so its
# compass is the preview's own: north -z, south +z, east +x, west -x
DIR = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


def corner(x, y, z):
    """The preview pixel of a layout block's lowest corner (x mirrored)."""
    return (-(x + 1) * 16, y * 16, z * 16)


def block_shape(name, p, x, y, z, ox):
    x0, y0, z0 = corner(x, y, z)
    x0 += ox
    x1, y1, z1 = x0 + 16, y0 + 16, z0 + 16
    op = GLASSY.get(name)
    if name.endswith("_pane") or name == "iron_bars":
        t = 1 if name == "iron_bars" else 1.5
        out = [el((x0 + 8 - t, y0, z0 + 8 - t), (x0 + 8 + t, y1, z0 + 8 + t), name, opacity=op)]
        for d, (dx, dz) in DIR.items():
            if p.get(d) == "true":
                if dx:
                    a, b = (x0 + 8, x1) if dx > 0 else (x0, x0 + 8)
                    out.append(el((a, y0, z0 + 8 - t), (b, y1, z0 + 8 + t), name, opacity=op))
                else:
                    a, b = (z0 + 8, z1) if dz > 0 else (z0, z0 + 8)
                    out.append(el((x0 + 8 - t, y0, a), (x0 + 8 + t, y1, b), name, opacity=op))
        if name == "iron_bars" and len(out) > 1:   # bars: a few rods across, not a sheet
            return [o for o in out[:1]] + [el((o["from"][0], o["from"][1] + k, o["from"][2]), (o["to"][0], o["from"][1] + k + 2, o["to"][2]), name)
                                           for o in out[1:] for k in (1, 7, 13)] + \
                [el((o["from"][0] + (0 if o["to"][0] - o["from"][0] < 4 else 7), o["from"][1], o["from"][2] + (0 if o["to"][2] - o["from"][2] < 4 else 7)),
                    (o["to"][0] - (0 if o["to"][0] - o["from"][0] < 4 else 7), o["to"][1], o["to"][2] - (0 if o["to"][2] - o["from"][2] < 4 else 7)), name)
                 for o in out[1:]]
        return out
    if name.endswith("_wall"):
        out = [el((x0 + 4, y0, z0 + 4), (x1 - 4, y1, z1 - 4), name)]
        for d, (dx, dz) in DIR.items():
            if p.get(d, "none") != "none":
                if dx:
                    a, b = (x0 + 8, x1) if dx > 0 else (x0, x0 + 8)
                    out.append(el((a, y0, z0 + 5), (b, y0 + 14, z1 - 5), name))
                else:
                    a, b = (z0 + 8, z1) if dz > 0 else (z0, z0 + 8)
                    out.append(el((x0 + 5, y0, a), (x1 - 5, y0 + 14, b), name))
        return out
    if name.endswith("_slab"):
        top = p.get("type") == "top"
        return [el((x0, y0 + (8 if top else 0), z0), (x1, y0 + (16 if top else 8), z1), name)]
    if name.endswith("_stairs"):
        dx, dz = DIR[p.get("facing", "north")]
        out = [el((x0, y0, z0), (x1, y0 + 8, z1), name)]
        bx0, bx1 = (x0 + 8, x1) if dx > 0 else (x0, x0 + 8) if dx < 0 else (x0, x1)
        bz0, bz1 = (z0 + 8, z1) if dz > 0 else (z0, z0 + 8) if dz < 0 else (z0, z1)
        out.append(el((bx0, y0 + 8, bz0), (bx1, y1, bz1), name))
        return out
    if name.endswith("_trapdoor"):
        if p.get("open") == "true":
            # open: against the side opposite its facing
            dx, dz = DIR[p.get("facing", "north")]
            if dx:
                a = x0 if dx > 0 else x1 - 3
                return [el((a, y0, z0), (a + 3, y1, z1), name)]
            a = z0 if dz > 0 else z1 - 3
            return [el((x0, y0, a), (x1, y1, a + 3), name)]
        top = p.get("half") == "top"
        return [el((x0, y0 + (13 if top else 0), z0), (x1, y0 + (16 if top else 3), z1), name)]
    if name == "iron_chain":
        return [el((x0 + 7, y0, z0 + 7), (x1 - 7, y1, z1 - 7), name)]
    if name == "lightning_rod":
        return [el((x0 + 7, y0, z0 + 7), (x1 - 7, y1, z1 - 7), name)]
    if name == "lantern":
        lo = 1 if p.get("hanging") == "true" else 0
        return [el((x0 + 5, y0 + lo, z0 + 5), (x1 - 5, y0 + lo + 7, z1 - 5), name), el((x0 + 6, y0 + lo + 7, z0 + 6), (x1 - 6, y0 + lo + 9, z1 - 6), "iron_chain")]
    if name == "end_rod":
        f = p.get("facing", "up")
        if f in ("east", "west"):
            return [el((x0, y0 + 13, z0 + 7), (x1, y0 + 15, z1 - 7), name)]
        if f in ("north", "south"):
            return [el((x0 + 7, y0 + 13, z0), (x1 - 7, y0 + 15, z1), name)]
        return [el((x0 + 7, y0, z0 + 7), (x1 - 7, y1, z1 - 7), name)]
    if name.endswith("_carpet") or name.endswith("pressure_plate"):
        inset = 1 if name.endswith("pressure_plate") else 0
        return [el((x0 + inset, y0, z0 + inset), (x1 - inset, y0 + 1, z1 - inset), name)]
    if name == "ladder":
        dx, dz = DIR[p.get("facing", "north")]   # on the block behind it (opposite its facing)
        if dz:
            a = z1 - 1 if dz < 0 else z0
            return [el((x0 + 1, y0, a), (x1 - 1, y1, a + 1), name)]
        a = x1 - 1 if dx < 0 else x0
        return [el((a, y0, z0 + 1), (a + 1, y1, z1 - 1), name)]
    if name.endswith("_wall_banner"):
        dx, dz = DIR[p.get("facing", "north")]   # hung on the block behind it, its cloth facing `facing`
        if dz:
            a = z0 if dz > 0 else z1 - 1
            return [el((x0 + 1, y0 - 12, a), (x1 - 1, y1 - 2, a + 1), name)]
        a = x0 if dx > 0 else x1 - 1
        return [el((a, y0 - 12, z0 + 1), (a + 1, y1 - 2, z1 - 1), name)]
    if name == "cauldron":
        return [el((x0, y0 + 3, z0), (x1, y1, z1), name), el((x0 + 2, y0, z0 + 2), (x1 - 2, y0 + 3, z1 - 2), name)]
    return [el((x0, y0, z0), (x1, y1, z1), name, opacity=op)]


def cube(name, p):
    return not p and name in COLOURS and name not in GLASSY and not any(
        name.endswith(s) for s in ("_pane", "_wall", "_slab", "_stairs", "_trapdoor", "_carpet", "pressure_plate")) and name not in (
        "iron_bars", "iron_chain", "lantern", "end_rod", "ladder", "lightning_rod", "cauldron") and not name.endswith("_banner")


def blocks(data, ox, cut):
    """The blocks below y `cut` (None: all), cubes merged into runs along x."""
    els, full = [], {}
    for x, y, z, b in data["blocks"]:
        if cut is not None and y >= cut:
            continue
        name, p = parse(b)
        if name in ("air", "barrier"):
            continue
        if name not in COLOURS:
            raise SystemExit(f"no colour for {name}")
        if cube(name, p) or name in ("stripped_birch_wood", "stripped_oak_wood", "stripped_spruce_log"):
            full[(x, y, z)] = name
        else:
            els += block_shape(name, p, x, y, z, ox)
    for (x, y, z), name in sorted(full.items()):
        if full.get((x - 1, y, z)) == name:
            continue
        n = 1
        while full.get((x + n, y, z)) == name:
            n += 1
        a = corner(x + n - 1, y, z)
        els.append(el((a[0] + ox, a[1], a[2]), (a[0] + ox + 16 * n, a[1] + 16, a[2] + 16), name))
    return els


# ------------------------------------------------------------------------------------------------
# stand-ins for the furniture and the props (the pack's models when economy_models has them)
# ------------------------------------------------------------------------------------------------

def turn(els, yaw, cx, cz):
    """Turns stand-in elements about (cx, cz) by a Minecraft yaw (the preview mirrors x: a turn the other way)."""
    for e in els:
        if yaw:
            e["post"] = [["y", -yaw, [cx, 0, cz]]]
    return els


def at(x, y, z, ox):
    """A layout point (block space) in preview pixels."""
    return (-x * 16 + ox, y * 16, z * 16)


def box(c, lo, hi, tex, **kw):
    """A box around a preview point c: lo / hi offsets in pixels (x mirrored already)."""
    return el((c[0] + lo[0], c[1] + lo[1], c[2] + lo[2]), (c[0] + hi[0], c[1] + hi[1], c[2] + hi[2]), tex, **kw)


def chair(c, i, face_to):
    """A plastic chair (or a paint can) at c, its back away from face_to (preview x, z)."""
    kinds = ["chair_red", "chair_blue", "paint_can", "chair_white", "chair_red", "paint_can"]
    k = kinds[i % len(kinds)]
    if k == "paint_can":
        return [box(c, (-5, 0, -5), (5, 9, 5), k), box(c, (-5, 9, -5), (5, 10, 5), "steel")]
    dx, dz = face_to[0] - c[0], face_to[2] - c[2]
    n = math.hypot(dx, dz) or 1
    bx, bz = -dx / n * 5, -dz / n * 5
    out = [box(c, (-5, 0, -5), (5, 8, 5), k)]
    out.append(box((c[0] + bx, c[1], c[2] + bz), (-4 if abs(bz) > abs(bx) else -1.5, 8, -1.5 if abs(bz) > abs(bx) else -4),
                   (4 if abs(bz) > abs(bx) else 1.5, 18, 1.5 if abs(bz) > abs(bx) else 4), k))
    return out


def table(t, ox):
    c = at(t["x"] + 0.5, t["y"], t["z"] + 0.5, ox)
    els = []
    if t["item"].endswith("round"):
        # a cable drum on its side... standing: its flange the top, green felt with burns and tape
        els.append(box(c, (-9, 0, -9), (9, 11, 9), "drum"))
        for r in range(4):
            w = 22 - r * 2
            els.append(box(c, (-w + 4 * (r == 0), 11, -w + 9 + r * 2 if r else -13), (w - 4 * (r == 0), 13, w - 9 - r * 2 if r else 13), "felt"))
        els.append(box(c, (-17, 11, -9), (17, 13, 9), "felt"))
        els.append(box(c, (-9, 11, -17), (9, 13, 17), "felt"))
        els.append(box(c, (-13, 11, -13), (13, 13, 13), "felt"))
        els.append(box(c, (-3, 13, 2), (6, 13.3, 4), "felt_dark"))
    else:
        els.append(box(c, (-6, 0, -6), (6, 10, 6), "drum"))
        els.append(box(c, (-9, 10, -12), (9, 12, 12), "felt"))
    for i, (sx, sz) in enumerate(t["seats"]):
        s = at(sx + 0.5, t["y"], sz + 0.5, ox)
        els += chair(s, i, c)
    return els


def prop(item, x, y, z, yaw, ox, models):
    name = item.split(":")[1]
    c = at(x, y, z, ox)
    if name in models:
        return None
    k = name.replace("gamble_prop_", "").replace("gamble_", "")
    els = []
    if k == "cone":
        for i, w in enumerate((5, 4, 3, 2, 1)):
            els.append(box(c, (-w, i * 3, -w), (w, i * 3 + 3, w), "cone_white" if i == 2 else "cone"))
        els.append(box(c, (-6, 0, -6), (6, 1, 6), "black_concrete"))
    elif k == "worklight":
        for a in (0, 120, 240):
            r = math.radians(a)
            els.append(box((c[0] + math.sin(r) * 6, c[1], c[2] + math.cos(r) * 6), (-0.6, 0, -0.6), (0.6, 22, 0.6), "steel"))
        els.append(box(c, (-0.8, 0, -0.8), (0.8, 30, 0.8), "steel"))
        els.append(box(c, (-6, 28, -2), (6, 36, 3), "yellow_concrete"))
        els.append(box(c, (-5, 29, 3), (5, 35, 3.6), "light"))
    elif k == "drumfire":
        els.append(box(c, (-6, 0, -6), (6, 15, 6), "blue_terracotta"))
        els.append(box(c, (-6.2, 4, -6.2), (6.2, 5, 6.2), "steel"))
        els.append(box(c, (-5, 15, -5), (5, 19, 5), "fire"))
        els.append(box(c, (-3, 19, -3), (3, 24, 3), "fire"))
    elif k == "pallet":
        els.append(box(c, (-7, 0, -6), (7, 2, 6), "pallet"))
        els.append(box(c, (-8, 2, -8), (8, 3, 8), "pallet"))
    elif k == "cement":
        els.append(box(c, (-8, 0, -8), (8, 2, 8), "pallet"))
        for lvl in range(3):
            for j in range(2):
                els.append(box(c, (-7 + j * 7.5, 2 + lvl * 4, -6), (-0.5 + j * 7.5, 6 + lvl * 4, 6), "cement"))
    elif k == "rebar":
        for j in range(5):
            els.append(box(c, (-24, 1 + (j % 2) * 1.5, -4 + j * 1.8), (24, 2.2 + (j % 2) * 1.5, -3 + j * 1.8), "rebar"))
        for xx in (-14, 14):
            els.append(box(c, (xx - 1.5, 0, -6), (xx + 1.5, 1.5, 6), "pallet"))
    elif k == "sign":
        els.append(box(c, (-11, 0, -0.5), (-10, 20, 0.5), "steel"))
        els.append(box(c, (10, 0, -0.5), (11, 20, 0.5), "steel"))
        els.append(box(c, (-13, 12, -1), (13, 28, 0), "board"))
        els.append(box(c, (-12, 22, -1.3), (12, 26, -1), "sign_red"))
        els.append(box(c, (-13, 12, -1.3), (13, 13.5, -1), "yellow_concrete"))
    elif k == "whiteboard":
        for xx in (-20, 20):
            els.append(box(c, (xx - 0.7, 0, -0.7), (xx + 0.7, 30, 0.7), "steel"))
        els.append(box(c, (-23, 14, -1), (23, 34, 1), "steel"))
        els.append(box(c, (-22, 15, -1.3), (22, 33, -1), "board"))
    return turn(els, yaw, c[0], c[2])


def real_models():
    """The pack's own gamble models, if economy_models knows them yet (another tool makes them)."""
    try:
        import economy_models as M
        return {k: v for k, v in M.ALL.items() if k.startswith("gamble_") or k == "dock_forklift"}
    except Exception as e:   # noqa: BLE001 - a half-written model set must not stop the preview
        print("  (no pack models:", e, ")")
        return {}


def real_prop(builder, x, y, z, scale, yaw, ox):
    pv = builder().to_preview()
    ex, ey, ez = at(x, y, z, ox)
    fn = lambda p: [ex + (p[0] - 8) * scale, ey + (p[1] - 8) * scale + 8 * scale, ez + (p[2] - 8) * scale]
    els = []
    for e in pv["elements"]:
        e = json.loads(json.dumps(e))
        a, b = fn(e["from"]), fn(e["to"])
        e["from"], e["to"] = [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]
        if e.get("rotation"):
            e["rotation"][2] = fn(e["rotation"][2])
        e["post"] = [["y", -yaw, [ex, ey, ez]]] if yaw else []
        els.append(e)
    return pv["textures"], els


# ------------------------------------------------------------------------------------------------
# labels: the text displays, drawn as they show (MC text: 1/40 block a font pixel, a line about 10 px)
# ------------------------------------------------------------------------------------------------

def label_texture(text, bg):
    font = ImageFont.truetype(FONT, 40)
    lines = text.split(" / ")
    w = max(int(font.getlength(l)) for l in lines) + 16
    h = 48 * len(lines) + 8
    a = (bg >> 24) & 0xFF
    im = Image.new("RGBA", (w, h), ((bg >> 16) & 0xFF, (bg >> 8) & 0xFF, bg & 0xFF, a if a > 0 else 0))
    d = ImageDraw.Draw(im)
    dark = a > 0 and ((bg >> 16) & 0xFF) > 128
    for i, l in enumerate(lines):
        lw = font.getlength(l)
        col = (20, 20, 20, 255) if dark else (245, 245, 245, 255)
        if "공사중" in l or "금 연" in l:
            col = (200, 30, 30, 255)
        if "✚" in l:
            col = (31, 138, 59, 255)
        d.text(((w - lw) / 2, 4 + i * 48), l, font=font, fill=col)
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode(), w, h


def labels(data, ox, textures, cut):
    els = []
    for i, (x, y, z, yaw, text, scale, bg) in enumerate(data["labels"]):
        if cut is not None and y >= cut:
            continue
        uri, w, h = label_texture(text, bg)
        key = f"label{i}"
        textures[key] = uri
        # 40 texture px = 8 font px = 0.2 block at scale 1
        bw, bh = w / 40 * 0.2 * scale * 16, h / 48 * 0.25 * scale * 16
        c = at(x, y, z, ox)
        face = {0: "south", 180: "north", 90: "west", 270: "east"}[int(yaw) % 360]
        if face in ("north", "south"):
            e = el((c[0] - bw / 2, c[1], c[2] - 0.1), (c[0] + bw / 2, c[1] + bh, c[2] + 0.1), key, glow=True, faces=(face,))
        else:
            e = el((c[0] - 0.1, c[1], c[2] - bw / 2), (c[0] + 0.1, c[1] + bh, c[2] + bw / 2), key, glow=True, faces=(face,))
        els.append(e)
    return els


def surroundings(ox):
    """Grass round the lot."""
    els = []
    for x0, x1, z0, z1 in ((-40, -17, -30, 50), (17, 40, -30, 50), (-16, 16, 29, 50), (-16, 16, -30, -10), (-16, -8, -9, -7), (8, 16, -9, -7)):
        a, b = corner(x1, -2, z0), corner(x0, -1, z1)
        els.append(el((a[0] + ox, a[1], a[2]), (b[0] + ox + 16, b[1], b[2] + 16), "grass"))
    # the road the plates come off
    for z in range(-30, -9):
        for x in range(-3, 4):
            name = "smooth_stone" if abs(x) == 3 else "yellow_concrete" if x == 0 and (z // 4) % 2 == 0 else "road"
            a = corner(x, -1, z)
            els.append(el((a[0] + ox, a[1] + 15.9, a[2]), (a[0] + ox + 16, a[1] + 16.1, a[2] + 16), name))
    return els


def scene(data, ox, cut, textures, models):
    els = blocks(data, ox, cut) + surroundings(ox) + labels(data, ox, textures, cut)
    for t in data["tables"]:
        els += table(t, ox)
    for x, y, z, item, scale, yaw in data["props"]:
        if cut is not None and y >= cut:
            continue
        name = item.split(":")[1].removeprefix("model/")
        if name in models:
            tx, e = real_prop(models[name], x, y, z, scale, yaw, ox)
            textures.update(tx)
            els += e
        else:
            els += prop(item, x, y, z, yaw, ox, models)
    return els


def render(name, els, textures, views, size):
    DP.BUILD.mkdir(exist_ok=True)
    src, png = DP.BUILD / f"{name}.preview.json", DP.BUILD / f"{name}.png"
    src.write_text(json.dumps({"textures": textures, "elements": els, "grid": False, "views": views, "size": list(size)}))
    subprocess.run(["node", str(DP.RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    return Image.open(png).convert("RGB")


def sheet(im, W, H, titles, out):
    font = ImageFont.truetype(FONT, 30)
    small = ImageFont.truetype(FONT, 20)
    s = Image.new("RGB", (im.width, H + 96), (27, 29, 34))
    s.paste(im, (0, 96))
    d = ImageDraw.Draw(s)
    for i, (t, sub) in enumerate(titles):
        d.text((i * W + 18, 10), t, fill=(245, 245, 245), font=font)
        d.text((i * W + 18, 54), sub, fill=(185, 190, 200), font=small)
    s.save(out, optimize=True)
    print("gamble hall", out)


def main(out_dir):
    data = json.loads(DATA.read_text())
    models = real_models()
    textures = {k: DP._tex(v, edge=k not in ("water", "grass", "road", "felt", "light", "fire")) for k, v in COLOURS.items()}
    W, H = 1000, 760
    # outside: from the gate, front right (the doors, the stairs) and front left (the scaffolding, the drum fire)
    els = scene(data, 0, None, textures, models)
    centre = [0, 3 * 16, 10 * 16]
    views = [
        {"x": 0, "y": 0, "w": W, "h": H, "az": -32, "el": 22, "k": 1.55, "radius": 30 * 16, "fov": 40, "far": 30000, "center": centre},
        {"x": W, "y": 0, "w": W, "h": H, "az": 40, "el": 30, "k": 1.55, "radius": 30 * 16, "fov": 40, "far": 30000, "center": centre},
    ]
    im = render("gamble_hall", els, textures, views, (W * 2, H))
    sheet(im, W, H, [("도박장 · 공사장 가설 사무소 (정문에서)", "파란 가림막 · 열린 컨테이너 문 · 2층 구경석 (노란 컨테이너) · 철계단 · 작업등 · 고깔"),
                     ("왼쪽 앞에서", "철골 비계 · 분진망 · 드럼통 모닥불 · 팔레트 · 시멘트 포대 · 철근")], out_dir / "gamble_hall.png")
    # roof off: the ground floor, then the 2층 over it
    textures = {k: DP._tex(v, edge=k not in ("water", "grass", "road", "felt", "light", "fire")) for k, v in COLOURS.items()}
    els = scene(data, 0, ROOF, textures, models) + scene(data, 9000, UP_ROOF, textures, models)
    views = [
        {"x": 0, "y": 0, "w": W, "h": H, "az": -6, "el": 70, "k": 1.4, "radius": 24 * 16, "fov": 40, "far": 30000, "center": [0, 0, 11 * 16]},
        {"x": W, "y": 0, "w": W, "h": H, "az": -10, "el": 58, "k": 0.95, "radius": 24 * 16, "fov": 40, "far": 30000, "center": [9000, 3 * 16, 16 * 16]},
    ]
    im = render("gamble_hall_top", els, textures, views, (W * 2, H))
    sheet(im, W, H, [("지붕을 걷어 본 1층", "테이블 6 (앞줄 섯다 초보 · 홀짝 초보 · 주사위 초보, 뒷줄 섯다 일반 · 홀짝 일반 · 섯다 큰손) · 환전 창구 · 휴게"),
                     ("2층 구경석", "바닥에 뚫린 구멍(난간) 아래로 뒷줄 세 판이 보임 · 오른쪽 철계단으로 올라옴")], out_dir / "gamble_hall_top.png")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "previews")
