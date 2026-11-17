"""
Previews of the 서킷 tracks (content/stsv/circuit/tracks, plugin dev.stsv.core.minigame.circuit.TrackLayout), drawn
from the JSON the plugin's test writes (cd plugin && ./gradlew test --tests '*CircuitTrackTest*': plugin/build/circuit):

    python3 circuit_preview.py                      # docs/previews/circuit_<track>.png (overhead) and _3d.png (a view)
    python3 circuit_preview.py --colors <client.jar>  # once: block colours from the game's textures -> circuit_colors.json

Overhead: every column's top block in its colour, lit by height, the start grid and the centre line marked. The 3D view:
the blocks round the start (and round the jump) as cubes seen from above at an angle, lit from the left.
"""

import json
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
DATA = REPO / "plugin" / "build" / "circuit"
OUT = REPO / "docs" / "previews"
COLORS = HERE / "circuit_colors.json"

# blocks whose texture is not named after them (or that have no plain texture)
TEXTURE = {"grass_block": "grass_block_top", "water": "water_still", "lava": "lava_still", "podzol": "podzol_top",
           "dirt_path": "dirt_path_top", "mud_bricks": "mud_bricks", "smooth_quartz": "quartz_block_bottom",
           "smooth_sandstone": "sandstone_top", "smooth_red_sandstone": "red_sandstone_top", "smooth_stone": "smooth_stone",
           "hay_block": "hay_block_top", "bone_block": "bone_block_side", "cactus": "cactus_top", "spruce_log": "spruce_log",
           "oak_log": "oak_log", "birch_log": "birch_log", "dark_oak_log": "dark_oak_log", "cherry_log": "cherry_log",
           "stripped_spruce_log": "stripped_spruce_log", "mushroom_stem": "mushroom_stem", "redstone_lamp": "redstone_lamp_on",
           "snow": "snow", "campfire": "campfire_log", "carved_pumpkin": "pumpkin_top", "barrel": "barrel_top",
           "chiseled_polished_blackstone": "chiseled_polished_blackstone", "snow_block": "snow", "smooth_quartz_slab": "quartz_block_bottom", "basalt": "basalt_top", "light": None, "air": None}
GREEN_TINT = {"grass_block": (124, 189, 107), "oak_leaves": (89, 156, 52), "spruce_leaves": (97, 128, 97), "birch_leaves": (128, 167, 85),
              "dark_oak_leaves": (75, 125, 40), "azalea_leaves": (95, 140, 50), "flowering_azalea_leaves": (110, 140, 80),
              "water": (60, 90, 220), "moss_carpet": (90, 120, 40)}


def base(data):
    d = data.replace("minecraft:", "")
    return d.split("[")[0]


def build_colors(jar):
    """Average colour of each block's texture (tinted ones by their usual biome colour)."""
    names = set()
    for f in DATA.glob("*.json"):
        d = json.loads(f.read_text())
        names.update(d["ground"].keys())
        names.update(d["blocks"].keys())
    names = {base(n) for n in names}
    out = {}
    with zipfile.ZipFile(jar) as z:
        files = set(z.namelist())
        for n in sorted(names):
            if n in GREEN_TINT:
                out[n] = GREEN_TINT[n]
                continue
            tex = TEXTURE.get(n, n)
            if tex is None:
                continue
            cands = [tex, n.replace("_slab", "").replace("_stairs", "").replace("_wall", "").replace("_fence", "_planks"),
                     n.replace("_slab", "s").replace("_stairs", "s").replace("_wall", "s"), n + "_top", n + "_side",
                     n.replace("bricks", "brick"), n.replace("_slab", "").replace("_stairs", "") + "_top"]
            if n.endswith("_stairs") or n.endswith("_slab"):
                cands.append(n.replace("_stairs", "_planks").replace("_slab", "_planks"))
            if n.endswith("_fence"):
                cands.insert(0, n.replace("_fence", "_planks"))
            if n.startswith("smooth_") and n.endswith("_slab"):
                cands.insert(0, n.replace("smooth_", "").replace("_slab", "_top"))
            got = None
            for c in cands:
                p = f"assets/minecraft/textures/block/{c}.png"
                if p in files:
                    got = p
                    break
            if got is None:
                print("no texture for", n)
                continue
            im = Image.open(z.open(got)).convert("RGBA").crop((0, 0, 16, 16))
            px = [p for p in im.get_flattened_data() if p[3] > 100]
            if not px:
                continue
            out[n] = tuple(round(sum(p[k] for p in px) / len(px)) for k in range(3))
    COLORS.write_text(json.dumps(out, indent=0, sort_keys=True))
    print(f"{len(out)} colours -> {COLORS.relative_to(REPO)}")


def load(track):
    d = json.loads((DATA / f"{track}.json").read_text())
    colors = {k: tuple(v) for k, v in json.loads(COLORS.read_text()).items()}
    return d, colors


def col(colors, data):
    b = base(data)
    if b in colors:
        return colors[b]
    for k in ("_slab", "_stairs", "_wall", "_fence"):
        if b.endswith(k) and b[: -len(k)] in colors:
            return colors[b[: -len(k)]]
    return (255, 0, 255)


def overhead(track):
    d, colors = load(track)
    x0, x1, z0, z1 = d["bounds"]
    W, H = x1 - x0 + 1, z1 - z0 + 1
    top = {}
    for data, cells in d["ground"].items():
        c = col(colors, data)
        for v in cells:
            top[(v // 4096, v % 4096)] = (-1, c)
    for data, flat in d["blocks"].items():
        if base(data) == "air":
            for k in range(0, len(flat), 3):
                x, y, z = flat[k] - x0, flat[k + 1], flat[k + 2] - z0
                if (x, z) in top and top[(x, z)][0] == y:
                    del top[(x, z)]
            continue
    for data, flat in d["blocks"].items():
        if base(data) in ("air", "light"):
            continue
        c = col(colors, data)
        for k in range(0, len(flat), 3):
            x, y, z = flat[k] - x0, flat[k + 1], flat[k + 2] - z0
            if (x, z) not in top or top[(x, z)][0] <= y:
                top[(x, z)] = (y, c)
        if base(data) == "air":
            pass
    S = 3
    im = Image.new("RGB", (W * S, H * S), (20, 20, 24))
    px = im.load()
    for (x, z), (y, c) in top.items():
        if not (0 <= x < W and 0 <= z < H):
            continue
        # lit by height, and by the slope towards the north west (a relief)
        yw = top.get((x - 1, z - 1), (y, c))[0]
        k = 1 + max(-0.25, min(0.25, (y - yw) * 0.08)) + y * 0.012
        cc = tuple(max(0, min(255, int(v * k))) for v in c)
        for a in range(S):
            for b in range(S):
                px[x * S + a, z * S + b] = cc
    dr = ImageDraw.Draw(im)
    for g in d["grid"]:
        gx, gz = (g[0] - x0) * S, (g[2] - z0) * S
        dr.ellipse([gx - 3, gz - 3, gx + 3, gz + 3], fill=(255, 220, 60), outline=(0, 0, 0))
    dr.text((8, 8), f"{d['name']} ({d['id']}, {d['theme']})", fill=(255, 255, 255))
    out = OUT / f"circuit_{track}.png"
    im.save(out)
    print("overhead", out.relative_to(REPO))


def view3d(track, centre=None, radius=34, az=35, el=38, size=(1600, 1000), name="3d"):
    """Cubes round centre (track x, z), seen from azimuth az / elevation el (degrees), painter's order."""
    d, colors = load(track)
    x0, x1, z0, z1 = d["bounds"]
    if centre is None:
        p = d["path"][0]
        centre = (p[0], p[1])
    cx, cz = centre
    blocks = {}
    for data, cells in d["ground"].items():
        c = col(colors, data)
        for v in cells:
            x, z = v // 4096 + x0, v % 4096 + z0
            if abs(x - cx) <= radius and abs(z - cz) <= radius:
                blocks[(x, -1, z)] = (c, "full")
    for data, flat in d["blocks"].items():
        b = base(data)
        for k in range(0, len(flat), 3):
            x, y, z = flat[k], flat[k + 1], flat[k + 2]
            if abs(x - cx) > radius or abs(z - cz) > radius:
                continue
            if b in ("air",):
                blocks.pop((x, y, z), None)
                continue
            if b == "light":
                continue
            shape = "slab" if "_slab" in b and "type=bottom" in data else "thin" if any(t in b for t in ("_fence", "_wall", "iron_bars", "lantern", "flower", "poppy",
                    "dandelion", "tulip", "bush", "grass", "daisy", "allium", "bluet", "rod", "mushroom", "carpet", "chain", "torch",
                    "pane", "campfire", "pot", "door", "snow")) and "block" not in b and "stem" not in b else "full"
            blocks[(x, y, z)] = (col(colors, data), shape)
    a, e = math.radians(az), math.radians(el)
    ca, sa, ce, se = math.cos(a), math.sin(a), math.cos(e), math.sin(e)
    scale = min(size) / (radius * 2.4)

    def proj(x, y, z):
        rx, rz = (x - cx) * ca - (z - cz) * sa, (x - cx) * sa + (z - cz) * ca
        return size[0] / 2 + rx * scale, size[1] * 0.55 - (y * ce - rz * se) * scale, rz * ce + y * se

    im = Image.new("RGB", size, (150, 190, 235) if d["theme"] not in ("city",) else (18, 16, 40))
    dr = ImageDraw.Draw(im)
    faces = []
    for (x, y, z), (c, shape) in blocks.items():
        h = 0.5 if shape == "slab" else 1.0
        inset = 0.35 if shape == "thin" else 0.0
        X0, X1, Z0, Z1, Y0, Y1 = x + inset, x + 1 - inset, z + inset, z + 1 - inset, y, y + h
        quads = [((X0, Y1, Z0), (X1, Y1, Z0), (X1, Y1, Z1), (X0, Y1, Z1), 1.0)]
        for (p1, p2, light, nb) in (((X0, Z1), (X1, Z1), 0.78, (x, y, z + 1)), ((X1, Z0), (X1, Z1), 0.62, (x + 1, y, z)),
                                     ((X0, Z0), (X1, Z0), 0.7, (x, y, z - 1)), ((X0, Z0), (X0, Z1), 0.85, (x - 1, y, z))):
            if shape == "full" and nb in blocks and blocks[nb][1] == "full":
                continue
            quads.append(((p1[0], Y0, p1[1]), (p2[0], Y0, p2[1]), (p2[0], Y1, p2[1]), (p1[0], Y1, p1[1]), light))
        if shape == "full" and (x, y + 1, z) in blocks and blocks[(x, y + 1, z)][1] == "full":
            quads = quads[1:]
        for q in quads:
            pts = [proj(*v) for v in q[:4]]
            depth = sum(p[2] for p in pts) / 4
            faces.append((depth, [(p[0], p[1]) for p in pts], tuple(min(255, int(v * q[4])) for v in c)))
    faces.sort(key=lambda f: f[0])
    for _, pts, c in faces:
        dr.polygon(pts, fill=c, outline=tuple(int(v * 0.85) for v in c))
    dr.text((10, 10), f"{d['name']} - {name}", fill=(255, 255, 255))
    out = OUT / f"circuit_{track}_{name}.png"
    im.save(out)
    print("3d", out.relative_to(REPO))


def main():
    if "--colors" in sys.argv:
        build_colors(sys.argv[sys.argv.index("--colors") + 1])
        return
    tracks = [a for a in sys.argv[1:] if not a.startswith("-")] or sorted(p.stem for p in DATA.glob("*.json"))
    import yaml
    for t in tracks:
        overhead(t)
        view3d(t)
        # a second view where the track does its trick: the jump (the city: the overpass over the crossing)
        spec = yaml.safe_load((REPO / "content/stsv/circuit/tracks" / f"{t}.yml").read_text())
        path = json.loads((DATA / f"{t}.json").read_text())["path"]
        jump = next((f for f in spec.get("features", []) if f["type"] == "jump"), None)
        if t == "city":
            view3d(t, centre=(0, 0), radius=40, az=20, el=32, name="overpass")
        if jump:
            p = path[int(jump["at"] * len(path))]
            view3d(t, centre=(p[0], p[1]), radius=30, az=60, el=30, name="jump")


if __name__ == "__main__":
    main()
