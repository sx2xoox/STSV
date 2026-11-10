"""
docs/previews/crop_blocks.png: custom crops as the item displays they were (entity light) next to the blocks the
plugin now places (block light, faces drawn with darkened texture copies), every stage of a few crops, and a 3x3 field
(blocks turned at random). Made from the real resource pack: run `cd economy && ./gradlew test` first (CropBlocksTest
writes economy/build/crop-blocks-pack.zip), then `python3 tools/assetgen/crop_blocks.py --preview`.

Lighting as the 26.3 client does it (see plugin CropBlockModels.java):
 - item display: the item turned 180 degrees about Y; every face lit by the entity light min(1, 0.6 (L0.n + L1.n) + 0.4),
   L0 = (0.2, 1, -0.7), L1 = (-0.2, 1, 0.7), n = the direction the face is baked with;
 - block: down 0.5, up 1, north/south 0.8, west/east 0.6 of the baked direction, no ambient occlusion (the model says so).
"""

import base64
import io
import json
import math
import random
import subprocess
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
REPO = HERE.parent.parent
ZIP = REPO / "economy" / "build" / "crop-blocks-pack.zip"
OUT = REPO / "docs" / "previews" / "crop_blocks.png"
BUILD = HERE / "build"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
F = np.float32

CROPS = [("tomato", "토마토"), ("corn", "옥수수"), ("muskmelon", "멜론"), ("garlic", "마늘")]
FIELD = ("muskmelon", 4)

DIRS = ["down", "up", "north", "south", "west", "east"]
DVEC = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
CARD = {"down": 0.5, "up": 1.0, "north": 0.8, "south": 0.8, "west": 0.6, "east": 0.6}
_L0 = np.array([0.2, 1, -0.7]) / np.linalg.norm([0.2, 1, -0.7])
_L1 = np.array([-0.2, 1, 0.7]) / np.linalg.norm([-0.2, 1, 0.7])


def entity_light(n):
    n = np.asarray(n, float)
    return min(1.0, 0.6 * (max(0, _L0 @ n) + max(0, _L1 @ n)) + 0.4)


# ---------------------------------------------------------------- baking a model into quads (as the client)
def corners(f, a, b):
    x1, y1, z1 = a
    x2, y2, z2 = b
    return {"north": [[x2, y2, z1], [x1, y2, z1], [x1, y1, z1], [x2, y1, z1]],
            "south": [[x1, y2, z2], [x2, y2, z2], [x2, y1, z2], [x1, y1, z2]],
            "east": [[x2, y2, z2], [x2, y2, z1], [x2, y1, z1], [x2, y1, z2]],
            "west": [[x1, y2, z1], [x1, y2, z2], [x1, y1, z2], [x1, y1, z1]],
            "up": [[x1, y2, z1], [x2, y2, z1], [x2, y2, z2], [x1, y2, z2]],
            "down": [[x1, y1, z2], [x2, y1, z2], [x2, y1, z1], [x1, y1, z1]]}[f]


def face_uvs(face):
    u1, v1, u2, v2 = face["uv"]
    uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
    k = (face.get("rotation", 0) // 90) % 4
    return [uvs[(i - k) % 4] for i in range(4)]


def rot_matrix(axis, angle_deg):
    a = float(F(angle_deg) * F(0.017453292))
    s, c = F(math.sin(a)), F(math.cos(a))
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], F)
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], F)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], F)


def closest_dir(n):
    best, bd = F(0), None
    for d in DIRS:
        v = F(n @ np.array(DVEC[d], F))
        if v >= 0 and v > best:
            best, bd = v, d
    return bd


def resolve(textures, key):
    ref = None
    for _ in range(8):
        if key not in textures:
            break
        ref = textures[key]
        if not ref.startswith("#"):
            return ref
        key = ref[1:]
    return ref


def bake(model):
    out = []
    for el in model["elements"]:
        r = el.get("rotation")
        for f, face in el["faces"].items():
            cs = np.array(corners(f, el["from"], el["to"]), F) / F(16)
            if r:
                M = rot_matrix(r["axis"], r["angle"])
                o = np.array(r["origin"], F) / F(16)
                cs = ((cs - o) @ M.T + o).astype(F)
            n = np.cross(cs[2] - cs[0], cs[1] - cs[0]).astype(F)
            ln = F(np.sqrt(F(n @ n)))
            if ln == 0:
                continue
            out.append({"p": (cs * 16).astype(float).tolist(), "uv": face_uvs(face), "tex": resolve(model["textures"], face["texture"][1:]),
                        "dir": closest_dir((n / ln).astype(F))})
    return out


# ---------------------------------------------------------------- scenes
class Pack:
    def __init__(self, path):
        self.z = zipfile.ZipFile(path)
        self.textures = {}

    def json(self, path):
        return json.loads(self.z.read(path))

    def tex(self, ref):
        ns, p = ref.split(":", 1)
        if ref not in self.textures:
            png = self.z.read(f"assets/{ns}/textures/{p}.png")
            self.textures[ref] = "data:image/png;base64," + base64.b64encode(png).decode()
        return ref


def emit_display(scene, pack, model, pos):
    """an item display at the block centre, context fixed: the model turned 180 degrees about Y, entity light"""
    for q in bake(model):
        b = entity_light(np.array(DVEC[q["dir"]]) * np.array([-1, 1, -1]))
        pts = [[pos[0] + 1 - p[0] / 16, pos[1] + p[1] / 16, pos[2] + 1 - p[2] / 16] for p in q["p"]]
        scene.append({"tex": pack.tex(q["tex"]), "p": pts, "uv": q["uv"], "c": [[b, b, b]] * 4})


def emit_block(scene, pack, model, pos):
    for q in bake(model):
        b = CARD[q["dir"]]
        scene.append({"tex": pack.tex(q["tex"]), "p": [[pos[0] + p[0] / 16, pos[1] + p[1] / 16, pos[2] + p[2] / 16] for p in q["p"]],
                      "uv": q["uv"], "c": [[b, b, b]] * 4})


def ground_textures(pack):
    """farmland and grass drawn here (no vanilla textures in the repository)"""
    rnd = random.Random(3)
    soil = Image.new("RGBA", (16, 16))
    grass = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            soil.putpixel((x, y), (74, 42, 22, 255) if y % 4 == 0 else (92, 54, 30, 255) if (x * 7 + y * 3) % 5 else (104, 62, 34, 255))
            g = rnd.randint(-10, 10)
            grass.putpixel((x, y), (110 + g, 160 + g, 70 + g // 2, 255))
    for name, im in (("x:soil", soil), ("x:grass", grass)):
        buf = io.BytesIO()
        im.save(buf, "PNG")
        pack.textures[name] = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def cube(scene, tex, pos, top=16):
    for f in DIRS:
        if f == "down":
            continue
        cs = corners(f, [0, 0, 0], [16, top, 16])
        b = CARD[f]
        uv = [(0, 0), (16, 0), (16, 16), (0, 16)]
        scene.append({"tex": tex, "p": [[pos[0] + c[0] / 16, pos[1] + c[1] / 16, pos[2] + c[2] / 16] for c in cs], "uv": uv, "c": [[b, b, b]] * 4})


def ground(scene, cells, r=1):
    xs = [c[0] for c in cells]
    zs = [c[1] for c in cells]
    for x in range(min(xs) - r, max(xs) + r + 1):
        for z in range(min(zs) - r, max(zs) + r + 1):
            if (x, z) in cells:
                cube(scene, "x:soil", (x, -1, z), top=15)
            else:
                cube(scene, "x:grass", (x, -1, z))


def item_model(pack, name):
    return pack.json(f"assets/economy/models/item/{name}.json")


def block_model(pack, name, k=0):
    return pack.json(f"assets/economy/models/block/crop/{name}_r{90 * k}.json")


def main():
    if not ZIP.exists():
        raise SystemExit(f"{ZIP} 없음: 먼저 cd economy && ./gradlew test (CropBlocksTest 가 만듦)")
    pack = Pack(ZIP)
    ground_textures(pack)
    BUILD.mkdir(parents=True, exist_ok=True)
    stages = {c: sum(1 for n in pack.z.namelist() if n.startswith(f"assets/economy/models/block/crop/{c}_") and n.endswith("_r0.json"))
              for c, _ in CROPS}
    cw, ch = 150, 210
    L, T, G, GAP = 130, 100, 4, 18
    cols = max(stages.values())
    cells = []
    for r, (crop, _) in enumerate(CROPS):
        tall = crop == "corn"
        for st in range(stages[crop]):
            name = f"{crop}_{st}"
            for v in range(2):
                sc = []
                ground(sc, [(0, 0)])
                (emit_display if v == 0 else emit_block)(sc, pack, item_model(pack, name) if v == 0 else block_model(pack, name), (0, 0, 0))
                cam = {"eye": [2.2, 2.4, 3.3], "at": [0.5, 0.9, 0.5], "fov": 48} if tall else {"eye": [1.6, 1.25, 2.0], "at": [0.5, 0.3, 0.5], "fov": 42}
                cells.append({"x": L + st * (2 * cw + G + GAP) + v * (cw + G), "y": T + r * (ch + G), "cam": cam, "quads": sc, "w": cw, "h": ch})
    # a 3x3 field: displays all the same way vs blocks turned at random (the four variants of the blockstate)
    field_crop, st = FIELD
    fy = T + len(CROPS) * (ch + G) + 40
    fw = (cols * (2 * cw + G + GAP)) // 2 - G
    rng = random.Random(11)
    field = [(x, z) for x in range(3) for z in range(3)]
    for v in range(2):
        sc = []
        ground(sc, field)
        for (x, z) in field:
            if v == 0:
                emit_display(sc, pack, item_model(pack, f"{field_crop}_{st}"), (x, 0, z))
            else:
                emit_block(sc, pack, block_model(pack, f"{field_crop}_{st}", rng.randrange(4)), (x, 0, z))
        cells.append({"x": L + v * (fw + G), "y": fy, "cam": {"eye": [4.6, 3.3, 5.4], "at": [1.5, 0.1, 1.4], "fov": 40}, "quads": sc,
                      "w": fw, "h": 2 * ch})
    W = L + cols * (2 * cw + G + GAP)
    H = fy + 2 * ch + 70
    # one render per cell size: the renderer takes one cell size per page
    raw = {}
    for size in sorted({(c["w"], c["h"]) for c in cells}):
        group = [c for c in cells if (c["w"], c["h"]) == size]
        data = {"W": max(c["x"] for c in group) + size[0], "H": max(c["y"] for c in group) + size[1], "cw": size[0], "ch": size[1],
                "textures": {k: v for k, v in pack.textures.items()},
                "cells": [{"x": c["x"], "y": c["y"], "cam": c["cam"], "quads": c["quads"]} for c in group]}
        jp = BUILD / f"crop_blocks_{size[0]}x{size[1]}.scene.json"
        png = BUILD / f"crop_blocks_{size[0]}x{size[1]}.png"
        jp.write_text(json.dumps(data))
        subprocess.run(["node", str(HERE / "render" / "quads.mjs"), str(jp), str(png)], check=True)
        raw[size] = Image.open(png).convert("RGB")
    can = Image.new("RGB", (W, H), (28, 30, 36))
    for c in cells:
        im = raw[(c["w"], c["h"])]
        can.paste(im.crop((c["x"], c["y"], c["x"] + c["w"], c["y"] + c["h"])), (c["x"], c["y"]))
    d = ImageDraw.Draw(can)
    f_head, f_row, f_small = ImageFont.truetype(FONT, 24), ImageFont.truetype(FONT, 20), ImageFont.truetype(FONT, 15)
    d.text((14, 10), "작물: 아이템 디스플레이(예전, 왼쪽) vs 블록(지금, 오른쪽) — 단계별, 같은 카메라·같은 햇빛", font=f_head, fill=(240, 240, 240))
    for st in range(cols):
        x = L + st * (2 * cw + G + GAP)
        d.text((x + cw + G // 2, T - 34), f"{st + 1}단계", font=f_row, fill=(255, 226, 140), anchor="mm")
        d.text((x + cw // 2, T - 10), "엔티티", font=f_small, fill=(170, 176, 186), anchor="mm")
        d.text((x + cw + G + cw // 2, T - 10), "블록", font=f_small, fill=(170, 176, 186), anchor="mm")
    for r, (crop, label) in enumerate(CROPS):
        d.text((14, T + r * (ch + G) + ch // 2 - 14), label, font=f_row, fill=(220, 220, 220), anchor="lm")
        d.text((14, T + r * (ch + G) + ch // 2 + 14), f"({stages[crop]}단계)", font=f_small, fill=(170, 176, 186), anchor="lm")
    d.text((14, fy - 26), f"3×3 밭 ({dict(CROPS)[field_crop]} 다 자람): 엔티티(모두 같은 방향) vs 블록(블록마다 네 방향 중 무작위)", font=f_row, fill=(220, 220, 220))
    d.text((14, H - 30), "블록은 면 방향별로 미리 어둡게 한 텍스처 사본(남북 ×0.924, 동서 ×0.828, 아래 ×0.8)을 써서 엔티티 조명과 같은 밝기. 리소스팩: economy/build/crop-blocks-pack.zip",
           font=f_small, fill=(190, 196, 206))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    can.save(OUT)
    print(f"preview {OUT}")


if __name__ == "__main__":
    main()
