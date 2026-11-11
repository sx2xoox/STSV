"""
Preview of the 하역장 (economy: /관리자 하역장 설치) as the plugin builds them, drawn from the structure data itself:

    cd economy && ./gradlew test          # DockTest writes economy/build/docks/land.json and port.json
    python3 tools/assetgen/dock_preview.py [out.png]      # docs/previews/docks.png (needs: cd render && npm install)

Blocks are drawn as plain-coloured cubes (vanilla textures are never used: a colour per block id, a darker edge so the
blocks read), walls, fences, chains, lanterns and slabs in their own shapes; the display models (crane hook, forklift,
pallet, containers) are the pack's own models (models/docks.py) placed as the plugin places them (FIXED, scale 2, base on
the point). Around them: a strip of grass, the 국도 coming in to the gate, and for the port the sea round its pier.
"""

import base64
import io
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
REPO = HERE.parent.parent
DATA = REPO / "economy" / "build" / "docks"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FACES = ("north", "south", "east", "west", "up", "down")

# a colour per block (no vanilla textures): roughly how each looks in game
COLOURS = {
    "stone_bricks": (122, 121, 122), "andesite": (136, 136, 137), "polished_andesite": (154, 156, 157), "stone": (125, 125, 125),
    "smooth_stone": (160, 160, 160), "stone_brick_wall": (118, 117, 118), "gray_concrete": (55, 58, 62), "light_gray_concrete": (125, 125, 115),
    "white_concrete": (207, 213, 214), "cyan_terracotta": (87, 91, 91), "glass": (176, 214, 226), "dark_prismarine": (52, 92, 76),
    "yellow_concrete": (241, 175, 21), "yellow_terracotta": (186, 133, 35), "black_concrete": (8, 10, 15), "sea_lantern": (210, 228, 222),
    "spruce_planks": (115, 85, 49), "oak_planks": (162, 131, 79), "hay_block": (166, 136, 38), "brown_wool": (114, 72, 41),
    "cartography_table": (104, 87, 67), "lantern": (236, 190, 100), "polished_andesite_slab": (154, 156, 157), "red_concrete": (142, 33, 33),
    "red_terracotta": (143, 61, 47), "blue_concrete": (45, 47, 143), "blue_terracotta": (74, 60, 91), "green_concrete": (73, 91, 36),
    "green_terracotta": (76, 83, 42), "orange_concrete": (224, 97, 1), "orange_terracotta": (162, 84, 38), "white_terracotta": (210, 178, 161),
    "cyan_concrete": (21, 119, 136), "iron_chain": (60, 64, 74), "polished_blackstone_wall": (53, 49, 57), "spruce_fence": (115, 85, 49),
    "spruce_log": (58, 37, 16), "stripped_spruce_wood": (115, 89, 52), "spruce_sign": (115, 85, 49),
    "grass": (106, 150, 64), "dirt": (134, 96, 67), "water": (52, 96, 196), "road": (55, 58, 62),
}
GLOW = {"sea_lantern", "lantern"}


def _tex(rgb, edge=True):
    """A 16 x 16 block face: the colour with a little noise and a darker rim."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            n = ((x * 7 + y * 13) % 5 - 2) * 3
            d = -22 if edge and (x == 0 or y == 0 or x == 15 or y == 15) else n
            px[x, y] = tuple(max(0, min(255, c + d)) for c in rgb) + (255,)
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def _el(frm, to, tex, glow=False, faces=FACES):
    return {"from": list(frm), "to": list(to), "glow": glow, "shade": True, "rotation": None,
            "faces": {f: {"uv": [0, 0, 16, 16], "texture": tex} for f in faces}}


def _px(r, y, f):
    """A dock point (right, up, forward; a block spans b .. b + 1) in preview pixels: x = -right (facing south)."""
    return (-r * 16, y * 16, f * 16)


def shape(name, r, y, f, ox):
    """Elements of one block at dock cell (r, y, f)."""
    x0, y0, z0 = _px(r + 1, y, f)
    x0 += ox
    x1, y1, z1 = x0 + 16, y0 + 16, z0 + 16
    t, glow = name, name in GLOW
    if name.endswith("_wall"):
        return [_el((x0 + 4, y0, z0 + 4), (x1 - 4, y1, z1 - 4), t)]
    if name.endswith("_fence"):
        return [_el((x0 + 6, y0, z0 + 6), (x1 - 6, y1, z1 - 6), t), _el((x0, y0 + 10, z0 + 7), (x1, y0 + 13, z1 - 7), t)]
    if name == "iron_chain":
        return [_el((x0 + 7, y0, z0 + 7), (x1 - 7, y1, z1 - 7), t)]
    if name == "lantern":
        return [_el((x0 + 5, y0, z0 + 5), (x1 - 5, y0 + 9, z1 - 5), t, True)]
    if name.endswith("_slab"):
        return [_el((x0, y0, z0), (x1, y0 + 8, z1), t)]
    if name == "spruce_sign":
        return [_el((x0 + 7, y0, z0 + 7), (x1 - 7, y0 + 9, z1 - 7), t), _el((x0, y0 + 8, z0 + 7), (x1, y1, z1 - 7), t)]
    return [_el((x0, y0, z0), (x1, y1, z1), t, glow)]


def scene(data, ox):
    """Elements of one dock (its blocks merged into runs along x where they can be) and its surroundings."""
    els = []
    full = {}
    for x, y, z, block in data["blocks"]:
        name = block.split(":")[1]
        if name == "air":
            continue
        full[(x, y, z)] = name
    for (x, y, z), name in sorted(full.items()):
        if (x - 1, y, z) in full and full[(x - 1, y, z)] == name and _cube(name):
            continue   # part of a run started further left
        if _cube(name):
            n = 1
            while full.get((x + n, y, z)) == name:
                n += 1
            # a run along +right is along -x in the preview
            a, b = _px(x + n, y, z), _px(x, y, z)
            els.append(_el((a[0] + ox, a[1], a[2]), (b[0] + ox, a[1] + 16, a[2] + 16), name, name in GLOW))
        else:
            els += shape(name, x, y, z, ox)
    # pier posts and quay faces: a few blocks of the columns, down into the water
    for x, y, z, block in data["columns"]:
        name = block.split(":")[1]
        if name == "spruce_log":
            for k in range(4):
                els += shape(name, x, y - k, z, ox)
    # the sign at the gate
    for x, y, z in data["signs"]:
        els += shape("spruce_sign", x, y, z, ox)
    port = data["kind"] == "PORT"
    # ground round the pad, the sea in front of a port, the road coming in
    for r0, r1, f0, f1, what in ((-40, -23, -30, 41, "grass"), (23, 40, -30, 41, "grass"), (-22, 23, -30, -1, "grass")):
        a, b = _px(r1, -2, f0), _px(r0, -1, f1)
        els.append(_el((a[0] + ox, a[1], a[2]), (b[0] + ox, b[1], b[2]), what))
    if port:
        a, b = _px(40, -4, 41), _px(-40, -1.15, 82)
        els.append(_el((a[0] + ox, a[1], a[2]), (b[0] + ox, b[1], b[2]), "water"))
    else:
        a, b = _px(40, -2, 41), _px(-40, -1, 60)
        els.append(_el((a[0] + ox, a[1], a[2]), (b[0] + ox, b[1], b[2]), "grass"))
    for f in range(-30, -1):
        for r in range(-3, 4):
            name = "smooth_stone" if abs(r) == 3 else "yellow_concrete" if r == 0 and (f // 4) % 2 == 0 else "road"
            a = _px(r + 1, -1, f)
            els.append(_el((a[0] + ox, a[1] + 15.9, a[2]), (a[0] + ox + 16, a[1] + 16.1, a[2] + 16), name))
    return els


def _cube(name):
    return not (name.endswith("_wall") or name.endswith("_fence") or name.endswith("_slab") or name in ("iron_chain", "lantern", "spruce_sign"))


def props(data, ox):
    """The display models where the plugin puts them: FIXED, scaled, base on the point, turned by yaw."""
    import economy_models as M
    textures, els = {}, []
    for x, y, z, model, scale, yaw in data["props"]:
        pv = M.ALL[model]().to_preview()
        textures.update(pv["textures"])
        ex, ey, ez = _px(x, y, z)
        ex += ox
        # model point m -> entity + (m - 8) * scale (px) + half the scale in blocks up (the base on the point)
        fn = lambda p, ex=ex, ey=ey, ez=ez, s=scale: [ex + (p[0] - 8) * s, ey + (p[1] - 8) * s + 8 * s, ez + (p[2] - 8) * s]
        for e in pv["elements"]:
            e = json.loads(json.dumps(e))
            a, b = fn(e["from"]), fn(e["to"])
            e["from"], e["to"] = [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]
            if e.get("rotation"):
                e["rotation"][2] = fn(e["rotation"][2])
            e["post"] = [["y", -yaw, [ex, ey, ez]]] if yaw else []
            els.append(e)
    return textures, els


def main(out):
    font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 30)
    small = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 20)
    textures = {k: _tex(v, edge=k not in ("water", "grass", "road")) for k, v in COLOURS.items()}
    elements, views = [], []
    kinds = [("land", "육지 하역장 (트럭으로 7,000블록: 노란 선 안이 하역 구역)"), ("port", "항구 하역장 (부두 양옆이 배를 대는 하역 구역)")]
    W, H = 1000, 760
    for i, (kind, _) in enumerate(kinds):
        data = json.loads((DATA / f"{kind}.json").read_text())
        ox = i * 8000
        elements += scene(data, ox)
        t, e = props(data, ox)
        textures.update(t)
        elements += e
        depth = 82 if kind == "port" else 42
        centre = [ox, 6 * 16, (depth / 2 - 4) * 16]
        views.append({"x": i * W, "y": 0, "w": W, "h": H, "az": -38, "el": 36, "k": 1.75 if kind == "land" else 1.45, "radius": depth * 16 * 0.75,
                      "fov": 40, "far": 20000, "center": centre})
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / "docks.preview.json", BUILD / "docks.png"
    src.write_text(json.dumps({"textures": textures, "elements": elements, "grid": False, "views": views, "size": [W * 2, H]}))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True)
    im = Image.open(png).convert("RGB")
    sheet = Image.new("RGB", (W * 2, H + 50), (27, 29, 34))
    sheet.paste(im, (0, 50))
    d = ImageDraw.Draw(sheet)
    for i, (_, title) in enumerate(kinds):
        d.text((i * W + 18, 10), title, fill=(245, 245, 245), font=font)
    sheet.save(out, optimize=True)
    print("docks  ", out)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "previews" / "docks.png")
