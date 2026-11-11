"""
Preview of the 국도 (economy: /관리자 국도) as the plugin builds it, drawn from the blocks the road code itself put:

    cd economy && ./gradlew test          # RoadPreviewTest builds a made-up strip of land and writes economy/build/roads/strip.json
    python3 tools/assetgen/road_preview.py [out.png]     # docs/previews/road.png (needs: cd render && npm install)

RoadPreviewTest runs the real road (RoadProfile: grade, bridges, tunnels; RoadSection: every block of every cross-section)
over five scenes of land, and writes the land round it afterwards as columns of blocks, and the direction signs as the
plugin puts them (RoadSign: the model's point and yaw, each text display's place, scale and words). Here each scene is cut
out of that land and drawn from a 3/4 angle: plain-coloured cubes with a darker rim (vanilla textures are never used: a
colour per block id, dock_preview's colours plus the land's), the faces nobody can see left out, walls and lanterns in
their own shapes, the water see-through. Where it helps, the land is cut open (the hill beside the road, the tunnel's near
half). The signs are the pack's own model (models/roads.py) placed as the item display shows it (FIXED, scale 2, turned
180 degrees and then by its yaw), their words drawn as the game draws a text display: Hangul from Neo둥근모 (the pack's
default font), digits and letters as 5 x 7 pixel glyphs like the game's, bold, centred on the display, a line 10 px high
on its point, 1/40 block a pixel times its scale.
"""

import base64
import io
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import dock_preview as DP  # noqa: E402
import neodgm  # noqa: E402

REPO = HERE.parent.parent
DATA = REPO / "economy" / "build" / "roads" / "strip.json"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"

COLOURS = dict(DP.COLOURS)
COLOURS.update({
    "grass_top": (106, 150, 64), "dirt": (134, 96, 67), "stone": (125, 125, 125), "cobblestone": (104, 104, 104), "sand": (219, 207, 163),
    "oak_log": (102, 81, 50), "oak_log_top": (151, 122, 73), "oak_leaves": (58, 104, 38), "water": (52, 96, 196),
    "gray_concrete": (62, 66, 71), "smooth_stone": (168, 168, 168),
})
GLOW = {"sea_lantern", "lantern"}
SHAPED = {"stone_brick_wall", "lantern"}
# terrain: a faint rim (the grid still reads, but the land is not busy); the road and what is built: a clear one
SOFT = {"grass_top", "grass_side", "dirt", "stone", "sand", "oak_leaves", "water", "cobblestone"}


def _tex(rgb, rim):
    """A 16 x 16 block face: the colour with a little noise and a darker rim."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            n = ((x * 7 + y * 13) % 5 - 2) * 3
            d = -rim if (x == 0 or y == 0 or x == 15 or y == 15) else n
            px[x, y] = tuple(max(0, min(255, c + d)) for c in rgb) + (255,)
    return im


def _uri(im):
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def textures():
    out = {}
    for k, v in COLOURS.items():
        out[k] = _tex(v, 10 if k in SOFT else 24)
    # a grass block's side: dirt with the grass hanging over its top
    side = _tex(COLOURS["dirt"], 10)
    px = side.load()
    for x in range(16):
        for y in range(3 + (x * 5) % 3 // 2):
            px[x, y] = tuple(max(0, c - (10 if y == 0 else 0)) for c in COLOURS["grass_top"]) + (255,)
    out["grass_side"] = side
    return {k: _uri(v) for k, v in out.items()}


# the game's digits and letters (what a sign's km and route number use): 5 x 7, drawn here (not the game's font file)
SMALL = {
    "0": ("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    "1": ("00100", "01100", "00100", "00100", "00100", "00100", "11111"),
    "2": ("01110", "10001", "00001", "00110", "01000", "10000", "11111"),
    "3": ("01110", "10001", "00001", "00110", "00001", "10001", "01110"),
    "4": ("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    "5": ("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    "6": ("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    "7": ("11111", "10001", "00001", "00010", "00100", "00100", "00100"),
    "8": ("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    "9": ("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    ".": ("0", "0", "0", "0", "0", "1", "1"),
    "k": ("1000", "1000", "1001", "1010", "1100", "1010", "1001"),
    "m": ("00000", "00000", "11010", "10101", "10101", "10101", "10101"),
}
_HANGUL = None


def text_image(text):
    """A text display's line as the game draws it, white, digits bold (RoadSign.bold): (RGBA picture at 2 px a text
    pixel, width in text pixels). The picture is 10 text pixels high (the line), the glyphs from its top."""
    global _HANGUL
    if _HANGUL is None:
        _HANGUL = neodgm.load()
    glyphs, x = [], 0.0
    for ch in text:
        if ch == " ":
            x += 4
            continue
        if ch in SMALL:
            rows = SMALL[ch]
            pts = [(c * 2 + dx, r * 2 + dy) for r, row in enumerate(rows) for c, b in enumerate(row) if b == "1" for dx in (0, 1) for dy in (0, 1)]
            bold = ch in "0123456789."
            glyphs.append((x, pts, 2 if bold else 0))       # bold: 1 text pixel over
            x += len(rows[0]) + 1 + (1 if bold else 0)
            continue
        adv, rows = _HANGUL[ord(ch)]
        cols = [c for c in range(adv) if any(r[c] for r in rows)]
        lo, hi = (min(cols), max(cols)) if cols else (0, 0)
        pts = [(c - lo, r + 1) for r, row in enumerate(rows) for c, b in enumerate(row) if b]
        glyphs.append((x, pts, 0))
        x += (hi - lo + 1) / 2 + 1
    width = int(round(x - 1))
    im = Image.new("RGBA", (max(2, width * 2 + 2), 20), (0, 0, 0, 0))
    px = im.load()
    for gx, pts, bold in glyphs:
        for (u, v) in pts:
            for o in (0, bold):
                X = int(round(gx * 2)) + u + o
                if 0 <= X < im.width and 0 <= v < 20:
                    px[X, v] = (250, 252, 255, 255)
    return im, width


def sign_elements(signs, centre, tex):
    """The direction signs: the model as the plugin's item display shows it, each text display a plane with its words."""
    import economy_models as M
    cx, cy, cz = centre
    pv = M.ALL["road_sign"]().to_preview()
    tex.update(pv["textures"])
    els = []
    for sg in signs:
        ex, ey, ez = (sg["x"] - cx) * 16, (sg["y"] - cy) * 16, (sg["z"] - cz) * 16
        # model point m -> (m - 8) * 2 from the entity, lifted 1.5 blocks; FIXED turns it 180 degrees, then the yaw
        fn = lambda q: [ex + (q[0] - 8) * 2, ey + (q[1] - 8) * 2 + 24, ez + (q[2] - 8) * 2]
        for e in pv["elements"]:
            e = json.loads(json.dumps(e))
            a, b = fn(e["from"]), fn(e["to"])
            e["from"], e["to"] = [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]
            e["post"] = [["y", -(sg["yaw"] + 180), [ex, ey, ez]]]
            els.append(e)
        for t in sg["texts"]:
            im, w = text_image(t["text"])
            key = "text_" + "_".join(f"{ord(c):x}" for c in t["text"])
            tex[key] = _uri(im)
            k = 16 * t["scale"] / 40                        # preview px a text pixel
            tx, ty, tz = (t["x"] - cx) * 16, (t["y"] - cy) * 16, (t["z"] - cz) * 16
            # centred on the point (the game moves it 1 text pixel right), standing on it, facing the yaw (+z unturned)
            frm = (tx + (1 - w / 2) * k, ty, tz)
            to = (tx + (1 - w / 2) * k + im.width / 2 * k, ty + 10 * k, tz)
            els.append({"from": list(frm), "to": list(to), "glow": True, "shade": False, "rotation": None,
                        "faces": {"south": {"uv": [0, 0, 16, 16], "texture": key}}, "post": [["y", -sg["yaw"], [tx, ty, tz]]]})
    return els


class Strip:
    """The land RoadPreviewTest wrote: columns of [y, length, block] runs."""

    def __init__(self, data):
        self.d = data
        self.pal = ["air"] + data["palette"]
        self.x0, self.z0, self.w, self.depth, self.y0 = data["x0"], data["z0"], data["w"], data["d"], data["y0"]
        self.points = data["points"]

    def window(self, x0, x1, z0, z1, ymin, ymax, cut=None):
        """The blocks of a box as an array [x, y, z] of palette indices (0 = air); cut(x, y, z) True = left out."""
        a = np.zeros((x1 - x0 + 1, ymax - ymin + 1, z1 - z0 + 1), dtype=np.uint8)
        for z in range(z0, z1 + 1):
            for x in range(x0, x1 + 1):
                c = self.d["cols"][(z - self.z0) * self.w + (x - self.x0)]
                for i in range(0, len(c), 3):
                    lo, hi = max(c[i], ymin), min(c[i] + c[i + 1] - 1, ymax)
                    if lo <= hi:
                        a[x - x0, lo - ymin:hi - ymin + 1, z - z0] = c[i + 2] + 1
        if cut:
            for (i, j, k) in np.argwhere(a > 0):
                if cut(x0 + i, ymin + j, z0 + k):
                    a[i, j, k] = 0
        return a

    def corridor(self, r):
        """The columns within r of the road (a strip of land along it)."""
        out = set()
        for px, pz, _, _ in self.points[::2]:
            for dx in range(-r - 1, r + 2):
                for dz in range(-r - 1, r + 2):
                    if dx * dx + dz * dz <= r * r:
                        out.add((int(px // 1) + dx, int(pz // 1) + dz))
        return out

    def level(self, x):
        """The road's level where it crosses x (the straight part)."""
        if not hasattr(self, "_levels"):
            self._levels = {}
            for p in self.points:
                if abs(p[1] - 0.5) < 1e-6:
                    self._levels.setdefault(int(p[0] // 1), p[2])
        return self._levels.get(x, 64)


# the six faces: name, the neighbour's offset (x, y, z)
DIRS = (("up", (0, 1, 0)), ("down", (0, -1, 0)), ("north", (0, 0, -1)), ("south", (0, 0, 1)), ("east", (1, 0, 0)), ("west", (-1, 0, 0)))


def _shift(m, off, fill):
    """m's value at (p + off) for every p (outside: fill)."""
    out = np.full_like(m, fill)
    dx, dy, dz = off
    nx, ny, nz = m.shape
    src = m[max(0, dx):nx + min(0, dx), max(0, dy):ny + min(0, dy), max(0, dz):nz + min(0, dz)]
    out[max(0, -dx):nx + min(0, -dx), max(0, -dy):ny + min(0, -dy), max(0, -dz):nz + min(0, -dz)] = src
    return out


def _greedy(cells):
    """Rectangles covering a set of (u, v) cells: [(u, v, du, dv)]."""
    left = set(cells)
    out = []
    for (u, v) in sorted(cells):
        if (u, v) not in left:
            continue
        du = 1
        while (u + du, v) in left:
            du += 1
        dv = 1
        while all((u + k, v + dv) in left for k in range(du)):
            dv += 1
        for a in range(du):
            for b in range(dv):
                left.discard((u + a, v + b))
        out.append((u, v, du, dv))
    return out


def _face_el(frm, to, tex, face, uv, glow=False, opacity=None):
    e = {"from": list(frm), "to": list(to), "glow": glow, "shade": True, "rotation": None,
         "faces": {face: {"uv": uv, "texture": tex}}}
    if opacity is not None:
        e["opacity"] = opacity
    return e


def _box(frm, to, tex, glow=False, faces=None, post=None):
    faces = faces or {f: tex for f, _ in DIRS}
    e = {"from": list(frm), "to": list(to), "glow": glow, "shade": True, "rotation": None,
         "faces": {f: {"uv": [0, 0, 16, 16], "texture": t} for f, t in faces.items()}}
    if post:
        e["post"] = post
    return e


def _texname(name, face):
    if name == "grass_block":
        return "grass_top" if face == "up" else "dirt" if face == "down" else "grass_side"
    if name == "oak_log" and face in ("up", "down"):
        return "oak_log_top"
    if name == "water_top":
        return "water"
    return name


def elements(strip, a, x0, ymin, z0, centre):
    """Every face that shows, merged into rectangles, and the shaped blocks: elements in preview pixels round `centre`."""
    pal = strip.pal
    names = np.array(pal + ["water_top"])
    water = pal.index("water") if "water" in pal else -1
    shaped = np.isin(names, list(SHAPED))
    # the top layer of water (air over it) is drawn 2 px low, as in game
    wt = len(pal)
    if water >= 0:
        top = (a == water) & (_shift(a, (0, 1, 0), 0) != water)
        a = a.copy()
        a[top] = wt
    kind = np.where(a == 0, 0, np.where(shaped[a], 2, np.where((a == water) | (a == wt), 3, 1)))   # 0 air, 1 cube, 2 shaped, 3 water
    cx, cy, cz = centre
    els = []

    def px(x, y, z):
        return (float(x - cx) * 16, float(y - cy) * 16, float(z - cz) * 16)

    for face, off in DIRS:
        nb = _shift(kind, off, 0)
        cube = (kind == 1) & (nb != 1)
        wet = (kind == 3) & (nb != 1) & (nb != 3)
        show = cube | wet
        if face == "down":
            show[:, 0, :] = False   # the bottom of the cut: never seen
        idx = np.argwhere(show)
        if len(idx) == 0:
            continue
        # group by the plane the face lies in and the block, then merge
        axis = {"up": 1, "down": 1, "north": 2, "south": 2, "east": 0, "west": 0}[face]
        uaxis, vaxis = {1: (0, 2), 2: (0, 1), 0: (2, 1)}[axis]
        groups = {}
        for p in idx:
            b = int(a[tuple(p)])
            groups.setdefault((int(p[axis]), b), []).append((int(p[uaxis]), int(p[vaxis])))
        for (layer, b), cells in groups.items():
            name = str(names[b])
            tex = _texname(name, face)
            for (u, v, du, dv) in _greedy(cells):
                lo = [0, 0, 0]
                lo[axis], lo[uaxis], lo[vaxis] = layer, u, v
                hi = [lo[0] + 1, lo[1] + 1, lo[2] + 1]
                hi[uaxis] = lo[uaxis] + du
                hi[vaxis] = lo[vaxis] + dv
                h = 0.875 if name == "water_top" else 1.0
                frm = px(x0 + lo[0], ymin + lo[1], z0 + lo[2])
                to = px(x0 + hi[0], ymin + hi[1] - 1 + h, z0 + hi[2])
                if face == "up":
                    frm = (frm[0], to[1], frm[2])
                elif face == "down":
                    to = (to[0], frm[1], to[2])
                elif face == "north":
                    to = (to[0], to[1], frm[2])
                elif face == "south":
                    frm = (frm[0], frm[1], to[2])
                elif face == "east":
                    frm = (to[0], frm[1], frm[2])
                else:
                    to = (frm[0], to[1], to[2])
                uw = du * 16
                vh = dv * 16 if (axis == 1) else round((hi[1] - 1 + h - lo[1]) * 16)
                els.append(_face_el(frm, to, tex, face, [0, 0, uw, vh], name in GLOW,
                                    0.62 if name in ("water", "water_top") and face == "up" else 0.5 if name in ("water", "water_top") else None))
    # the shaped blocks
    nx, ny, nz = a.shape

    def at(i, j, k):
        if 0 <= i < nx and 0 <= j < ny and 0 <= k < nz:
            return names[a[i, j, k]], kind[i, j, k]
        return "air", 0

    for (i, j, k) in np.argwhere(kind == 2):
        name = str(names[a[i, j, k]])
        X, Y, Z = px(x0 + i, ymin + j, z0 + k)
        if name == "stone_brick_wall":
            conn = {}
            for f, (dx, _, dz) in (("north", (0, 0, -1)), ("south", (0, 0, 1)), ("east", (1, 0, 0)), ("west", (-1, 0, 0))):
                n, kd = at(i + dx, j, k + dz)
                conn[f] = n == "stone_brick_wall" or kd == 1
            above, _ = at(i, j + 1, k)
            straight = (conn["north"] and conn["south"] and not conn["east"] and not conn["west"]) or \
                       (conn["east"] and conn["west"] and not conn["north"] and not conn["south"])
            if not straight or above in ("stone_brick_wall", "lantern"):
                els.append(_box((X + 4, Y, Z + 4), (X + 12, Y + 16, Z + 12), name))
            hgt = 16 if above == "stone_brick_wall" else 14
            if conn["north"]:
                els.append(_box((X + 5, Y, Z), (X + 11, Y + hgt, Z + 8), name))
            if conn["south"]:
                els.append(_box((X + 5, Y, Z + 8), (X + 11, Y + hgt, Z + 16), name))
            if conn["west"]:
                els.append(_box((X, Y, Z + 5), (X + 8, Y + hgt, Z + 11), name))
            if conn["east"]:
                els.append(_box((X + 8, Y, Z + 5), (X + 16, Y + hgt, Z + 11), name))
        elif name == "lantern":
            els.append(_box((X + 5, Y, Z + 5), (X + 11, Y + 7, Z + 11), "lantern", True))
            els.append(_box((X + 6, Y + 7, Z + 6), (X + 10, Y + 9, Z + 10), "iron_chain"))
    return els


def render(strip, name, box, view, size, cut=None):
    """One panel: the box (x0, x1, z0, z1, ymin, ymax) cut out of the strip, drawn from the view (az, el, k, centre)."""
    x0, x1, z0, z1, ymin, ymax = box
    a = strip.window(x0, x1, z0, z1, ymin, ymax, cut)
    tex = textures()
    centre = view["centre"]
    els = elements(strip, a, x0, ymin, z0, centre)
    signs = [g for g in strip.d["signs"] if x0 <= g["x"] <= x1 + 1 and z0 <= g["z"] <= z1 + 1]
    els += sign_elements(signs, centre, tex)
    w, h = size
    v = {"x": 0, "y": 0, "w": w, "h": h, "az": view["az"], "el": view["el"], "k": view["k"], "radius": view["radius"] * 16,
         "fov": view.get("fov", 35), "far": 40000, "center": [0, 0, 0]}
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / f"road_{name}.preview.json", BUILD / f"road_{name}.png"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "repeat": True, "views": [v], "size": [w, h]}))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    print(f"  {name}: {len(els)} faces")
    return Image.open(png).convert("RGB")


def scenes(strip):
    """The panels: (name, title, note, box (x0, x1, z0, z1, ymin, ymax), view, cut). The last one is the overview."""
    lv = strip.level
    near = strip.corridor(24)
    tunnel = (1246, 1298)   # cut open: the near half of the hill and the tunnel, down to the road (the portals left whole)
    return [
        ("a", "(가) 평지: 가로등과 방향 표지판",
         "폭 5칸 회색 바닥 + 노란 점선 + 양쪽 갓돌, 가로등 40칸마다 양쪽 번갈아, 1,000칸마다 방향 표지판 (오른쪽, 오는 차를 바라봄)",
         (978, 1024, -16, 16, 58, 74), {"az": -118, "el": 24, "k": 1.0, "radius": 19, "centre": (1001, 66, 2)}, None),
        ("b", "(나) 언덕 오르기: 깎고 메우기, 경사 1:3",
         "땅은 1:2 로 가팔라도 길은 3칸에 1칸씩. 비탈의 높은 쪽은 깎고 낮은 쪽은 메움 (언덕 중턱을 길 가로로 자른 그림)",
         (1040, 1101, -16, 16, 52, 84), {"az": 72, "el": 20, "k": 1.0, "radius": 52, "centre": (1082, 70, 0)}, None),
        ("c", "(다) 강 건너기: 난간 달린 다리",
         "물 위 한 칸 띄운 상판, 양쪽 돌벽돌 난간(가로등도 난간 위), 10칸마다 강바닥까지 교각",
         (1178, 1222, -16, 16, 52, 74), {"az": -138, "el": 24, "k": 1.12, "radius": 26, "centre": (1200, 62, 0)}, None),
        ("d", "(라) 가파른 산: 굴",
         "길보다 5칸 넘게 높은 땅은 굴: 돌벽돌 벽·천장, 8칸마다 천장 조명, 양 끝에 입구 (가운데는 산의 앞쪽 반을 잘라 낸 그림)",
         (1222, 1316, -22, 20, 54, 94), {"az": -136, "el": 20, "k": 1.0, "radius": 76, "centre": (1264, 74, 0)},
         lambda x, y, z: tunnel[0] <= x <= tunnel[1] and z >= 1 and y > lv(x)),
        ("e", "(마) 숲 속 굽은 길",
         "길 위와 양옆 나무는 치우고, 모퉁이에서 꺾여도 바닥에 빈틈 없이",
         (1330, 1446, -22, 104, 58, 84), {"az": -150, "el": 50, "k": 1.0, "radius": 64, "centre": (1394, 64, 44)}, None),
        ("overview", "전체: 강의 다리 → 산의 굴 → 숲 속 굽은 길",
         "한 줄로 이어진 길 (길 양옆 24칸만, 굴은 앞쪽 반을 잘라 낸 그림): 언제나 경사 1:3 이하, 바닥 폭 5칸",
         (1172, 1462, -26, 140, 59, 96), {"az": 180, "el": 56, "k": 1.0, "radius": 245, "centre": (1317, 64, 60), "fov": 30},
         lambda x, y, z: (x, z) not in near or (tunnel[0] <= x <= tunnel[1] and z >= 1 and y > lv(x))),
    ]


def _wrap(d, text, font, width):
    """The text in lines no wider than width (broken at spaces)."""
    lines, cur = [], ""
    for word in text.split(" "):
        t = (cur + " " + word).strip()
        if cur and d.textlength(t, font=font) > width:
            lines.append(cur)
            cur = word
        else:
            cur = t
    return lines + [cur]


def _head(d, x, y, title, note, width, font, small):
    d.text((x + 18, y + 8), title, fill=(245, 245, 245), font=font)
    for k, line in enumerate(_wrap(d, note, small, width - 36)):
        d.text((x + 18, y + 46 + k * 24), line, fill=(185, 190, 200), font=small)


def sign_faces(strip, size):
    """The three kinds of sign (start, every km, arrival) straight on, side by side, as the test placed them: (picture,
    the kinds in order)."""
    order = {"START": 0, "KM": 1, "ARRIVAL": 2}
    signs = sorted(strip.d["signs"], key=lambda g: order[g["kind"]])
    moved = []
    for n, g in enumerate(signs):
        # each turned to face north (yaw 180) and set 4.5 blocks apart: a text's place is (right, out) from the sign's point
        r = math.radians(g["yaw"])
        R, F = (math.cos(r), math.sin(r)), (-math.sin(r), math.cos(r))
        ox = ((len(signs) - 1) / 2 - n) * 4.5   # facing north: +x is on the viewer's left
        texts = []
        for t in g["texts"]:
            dx, dz = t["x"] - g["x"], t["z"] - g["z"]
            right, out = dx * R[0] + dz * R[1], dx * F[0] + dz * F[1]
            texts.append(dict(t, x=ox - right, y=t["y"] - g["y"], z=-out))
        moved.append({"x": ox, "y": 0.0, "z": 0.0, "yaw": 180.0, "kind": g["kind"], "texts": texts})
    tex = {}
    els = sign_elements(moved, (0, 0, 0), tex)
    w, h = size
    v = {"x": 0, "y": 0, "w": w, "h": h, "az": 0, "el": 0, "k": 1.0, "radius": 11.2 * 16, "fov": 12.5, "far": 40000,
         "center": [0, 3.75 * 16, 0]}
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / "road_signs.preview.json", BUILD / "road_signs.png"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "views": [v], "size": [w, h]}))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    return Image.open(png).convert("RGB"), [g["kind"] for g in signs]


def main(out, only=None):
    strip = Strip(json.loads(DATA.read_text()))
    font = ImageFont.truetype(FONT, 30)
    small = ImageFont.truetype(FONT, 19)
    PW, PH, HEAD = 900, 600, 98
    OW, OH = PW * 3, 1150
    rendered = {}
    for name, title, note, box, view, cut in scenes(strip):
        if only and name not in only:
            continue
        rendered[name] = (render(strip, name, box, view, (OW, OH) if name == "overview" else (PW, PH), cut), title, note)
    if only:
        return   # a try: the panels are in tools/assetgen/build/road_<name>.png
    panels = [rendered[n] for n in "abcde"]
    over, otitle, onote = rendered["overview"]
    FH = 420
    faces, kinds = sign_faces(strip, (OW, FH))
    sheet = Image.new("RGB", (OW, 2 * (PH + HEAD) + FH + HEAD + OH + HEAD), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    for n, (im, title, note) in enumerate(panels):
        x, y = (n % 3) * PW, (n // 3) * (PH + HEAD)
        sheet.paste(im, (x, y + HEAD))
        _head(d, x, y, title, note, PW, font, small)
    # the last place in the second row: what the blocks are
    lx, ly = 2 * PW + 30, PH + HEAD + 30
    d.text((lx, ly), "블록 (/관리자 국도)", fill=(245, 245, 245), font=font)
    legend = [("gray_concrete", "바닥 (roads.surface)"), ("yellow_concrete", "가운데 점선 (roads.center-line)"),
              ("smooth_stone", "갓돌 (roads.kerb)"), ("stone_bricks", "다리 가장자리·교각, 굴 벽·천장·입구"),
              ("stone_brick_wall", "난간, 가로등 기둥"), ("sea_lantern", "굴 천장 조명 (8칸마다)"), ("lantern", "가로등 (40칸마다)"),
              ("stone", "길 밑을 메운 곳 (돌, 그 아래 조약돌)"), ("grass_top", "갓길 (깎거나 흙으로 메우고 잔디)")]
    yy = ly + 52
    for key, text in legend:
        d.rectangle((lx, yy + 2, lx + 26, yy + 26), fill=COLOURS[key], outline=(10, 10, 10))
        d.text((lx + 40, yy), text, fill=(220, 222, 228), font=small)
        yy += 36
    for k, line in enumerate(("RoadPreviewTest 가 실제 길 코드(RoadProfile, RoadSection)로", "지어 본 블록을 그대로 그린 그림 (바닐라 텍스처 없음)")):
        d.text((lx, yy + 12 + k * 26), line, fill=(150, 155, 165), font=small)
    # the signs straight on: what RoadPreviewTest's road put up (RoadSign's words and places, the model)
    fy = 2 * (PH + HEAD)
    sheet.paste(faces, (0, fy + HEAD))
    names = {"START": "출발 뒤 24칸: 방면 + 전체 거리", "KM": "1,000칸마다: 남은 거리", "ARRIVAL": "입구 20칸 앞: 도착"}
    _head(d, 0, fy, "국도 표지판 (3D 모델 + 글씨 디스플레이)",
          "길 오른쪽(갓돌·갓길 위)에 서서 오는 차를 바라봄, 판 아래는 길 위 3칸 · 판 3×1.5칸. 큰 줄 = 하역장(+방면/도착), "
          "아랫줄 = 노선 방패(국도 n호선, 길마다 만든 순서) · 남은 거리. 글씨는 서버 기본 글꼴(Neo둥근모), 숫자만 굵게", OW, font, small)
    for n, kind in enumerate(kinds):
        cx = OW // 2 + (n - (len(kinds) - 1) / 2) * 4.5 * FH / (2 * 11.2 * math.tan(math.radians(6.25)))   # sign_faces: 4.5 apart
        label = names[kind]
        d.text((cx - d.textlength(label, font=small) / 2, fy + HEAD + 14), label, fill=(185, 190, 200), font=small)
    oy = fy + FH + HEAD
    sheet.paste(over, (0, oy + HEAD))
    _head(d, 0, oy, otitle, onote, OW, font, small)
    sheet.save(out, optimize=True)
    print("road   ", out)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--only=")]
    only = [a[7:].split(",") for a in sys.argv[1:] if a.startswith("--only=")]
    main(Path(args[0]) if args else REPO / "docs" / "previews" / "road.png", only[0] if only else None)
