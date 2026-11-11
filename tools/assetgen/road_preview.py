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
    # the road's finer parts: half steps (slabs), edge lines, patches, the verge, drains, reflectors, guardrails, joints
    "smooth_quartz": (234, 229, 221), "deepslate_tiles": (50, 50, 53), "cracked_deepslate_tiles": (44, 44, 47),
    "dirt_path": (148, 122, 65), "iron_trapdoor": (176, 176, 180), "end_rod": (240, 236, 226), "andesite_wall": (136, 136, 137),
    "polished_basalt": (92, 92, 98), "gravel": (131, 127, 126), "andesite": (136, 136, 137), "polished_andesite": (154, 156, 157),
})
for _slab, _of in (("deepslate_tile_slab", "deepslate_tiles"), ("smooth_quartz_slab", "smooth_quartz"), ("smooth_stone_slab", "smooth_stone"),
                   ("stone_brick_slab", "stone_bricks"), ("polished_andesite_slab", "polished_andesite")):
    COLOURS[_slab] = COLOURS[_of]
GLOW = {"sea_lantern", "lantern", "end_rod"}
SLABS = {k for k in COLOURS if k.endswith("_slab")}
SHAPED = {"stone_brick_wall", "andesite_wall", "lantern", "iron_trapdoor", "end_rod"} | SLABS
WALLS = {"stone_brick_wall", "andesite_wall"}
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
    "·": ("0", "0", "0", "1", "0", "0", "0"),
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


_MODELS = {}


def _model(name, tex):
    """A pack model's preview elements (built once)."""
    import economy_models as M
    if name not in _MODELS:
        _MODELS[name] = M.ALL[name]().to_preview()
    pv = _MODELS[name]
    tex.update(pv["textures"])
    return pv["elements"]


def _placed(elements, at, yaw, scale, lift):
    """Model elements as an item display (FIXED) shows them at `at` (preview px): model point m -> (m - 8) * scale from
    the entity, lifted `lift` px; turned 180 degrees, then by the yaw."""
    ex, ey, ez = at
    fn = lambda q: [ex + (q[0] - 8) * scale, ey + (q[1] - 8) * scale + lift, ez + (q[2] - 8) * scale]
    out = []
    for e in elements:
        e = json.loads(json.dumps(e))
        a, b = fn(e["from"]), fn(e["to"])
        e["from"], e["to"] = [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]
        e["post"] = [["y", -(yaw + 180), [ex, ey, ez]]]
        out.append(e)
    return out


def sign_elements(signs, centre, tex):
    """The direction signs: the model as the plugin's item display shows it (the small sign lifted 1.5, the guide sign 2:
    RoadSigns.lift), a guide sign's arrows (each its own model, scaled from 1.5 blocks to its size), each text display a
    plane with its words."""
    cx, cy, cz = centre
    els = []
    for sg in signs:
        at = ((sg["x"] - cx) * 16, (sg["y"] - cy) * 16, (sg["z"] - cz) * 16)
        guide = sg.get("model") == "guide"
        els += _placed(_model("road_sign_guide" if guide else "road_sign", tex), at, sg["yaw"], 2, 32 if guide else 24)
        for a in sg.get("arrows", []):
            pa = ((a["x"] - cx) * 16, (a["y"] - cy) * 16, (a["z"] - cz) * 16)
            els += _placed(_model("road_arrow_" + a["arrow"], tex), pa, sg["yaw"], a["size"] / 1.5, 0)
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
        if name in SLABS:
            els.append(_box((X, Y, Z), (X + 16, Y + 8, Z + 16), name))
        elif name == "iron_trapdoor":   # a drain grate: a plate flush with the top
            els.append(_box((X, Y + 13, Z), (X + 16, Y + 16, Z + 16), name))
            els.append(_box((X + 3, Y + 12.9, Z + 3), (X + 13, Y + 13, Z + 13), "black_concrete"))
        elif name == "end_rod":         # a reflector post
            els.append(_box((X + 6.5, Y, Z + 6.5), (X + 9.5, Y + 15, Z + 9.5), name, True))
            els.append(_box((X + 6.3, Y + 11, Z + 6.3), (X + 9.7, Y + 13, Z + 9.7), "red_concrete", True))
        elif name in WALLS:
            conn = {}
            for f, (dx, _, dz) in (("north", (0, 0, -1)), ("south", (0, 0, 1)), ("east", (1, 0, 0)), ("west", (-1, 0, 0))):
                n, kd = at(i + dx, j, k + dz)
                conn[f] = n in WALLS or kd == 1
            above, _ = at(i, j + 1, k)
            straight = (conn["north"] and conn["south"] and not conn["east"] and not conn["west"]) or \
                       (conn["east"] and conn["west"] and not conn["north"] and not conn["south"])
            if not straight or above in WALLS or above == "lantern":
                els.append(_box((X + 4, Y, Z + 4), (X + 12, Y + 16, Z + 12), name))
            hgt = 16 if above in WALLS else 14
            if conn["north"]:
                els.append(_box((X + 5, Y, Z), (X + 11, Y + hgt, Z + 8), name))
            if conn["south"]:
                els.append(_box((X + 5, Y, Z + 8), (X + 11, Y + hgt, Z + 16), name))
            if conn["west"]:
                els.append(_box((X, Y, Z + 5), (X + 8, Y + hgt, Z + 11), name))
            if conn["east"]:
                els.append(_box((X + 8, Y, Z + 5), (X + 16, Y + hgt, Z + 11), name))
        elif name == "lantern":
            above, _ = at(i, j + 1, k)
            if above in WALLS:   # hanging from a lamp's arm
                els.append(_box((X + 5, Y + 1, Z + 5), (X + 11, Y + 8, Z + 11), "lantern", True))
                els.append(_box((X + 7, Y + 8, Z + 7), (X + 9, Y + 16, Z + 9), "iron_chain"))
            else:
                els.append(_box((X + 5, Y, Z + 5), (X + 11, Y + 7, Z + 11), "lantern", True))
                els.append(_box((X + 6, Y + 7, Z + 6), (X + 10, Y + 9, Z + 10), "iron_chain"))
    return els


def paint_elements(paints, centre, tex):
    """Words painted on the road (a text display lying on it, its top along the road towards the gate)."""
    cx, cy, cz = centre
    els = []
    for p in paints:
        im, w = text_image(p["text"])
        key = "text_" + "_".join(f"{ord(c):x}" for c in p["text"])
        tex[key] = _uri(im)
        k = 16 * p["scale"] / 40
        tx, ty, tz = (p["x"] - cx) * 16, (p["y"] - cy) * 16 + 0.3, (p["z"] - cz) * 16
        # unturned it reads for traffic going north (-z): its top further north; then turned like a sign (yaw)
        frm = (tx + (1 - w / 2) * k, ty, tz - 10 * k)
        to = (tx + (1 - w / 2) * k + im.width / 2 * k, ty, tz)
        els.append({"from": list(frm), "to": list(to), "glow": True, "shade": False, "rotation": None,
                    "faces": {"up": {"uv": [0, 0, 16, 16], "texture": key}}, "post": [["y", -p["yaw"], [tx, ty, tz]]]})
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
    els += paint_elements([p for p in strip.d.get("paints", []) if x0 <= p["x"] <= x1 + 1 and z0 <= p["z"] <= z1 + 1], centre, tex)
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
        ("f", "(바) 하역장 입구까지",
         "마지막 20칸은 입구로 곧게, 넓어지고 횡단보도와 '하역장 입구' 글씨, 갓길은 하역장 바닥처럼 포장해 그 높이로 이어짐",
         (1412, 1460, 136, 192, 58, 76), {"az": -152, "el": 34, "k": 1.0, "radius": 34, "centre": (1436, 64, 160)}, None),
        ("g", "(사) 갈림길: 어디로 가는지 미리, 갈라지는 곳에서, 지나서 다시",
         "줄기 길 오른쪽에 120·40칸 앞 예고 안내판(↖ 동해 하역장 / ↑ 서해 하역장, 거리는 표지판에서), 두 길 사이 V 에 갈림 안내판(↖ ↗), "
         "지나서 확인 표지판, 반대쪽엔 돌아가는 길(↑ 본사 출발 터미널). 먼저 있던 길의 차로는 그대로, 갓돌만 같은 높이로 포장",
         (1100, 1196, -64, 18, 56, 84), {"az": -103, "el": 15, "k": 1.0, "radius": 50, "centre": (1150, 69, -8), "fov": 32},
         lambda x, y, z: x < 1140 and y > lv(x) - 1 and (z < -5 or z > 5)),
        ("i", "(자) 반 칸 다리: 난간은 온 블록 받침 위에",
         "반 칸 높이(반 블록) 다리 상판에서도 가장자리는 돌벽돌 온 블록, 난간·가로등은 그 위에 서서 떠 있지 않음. 성토 구간 가드레일은 갓길 흙 위",
         (874, 946, -12, 12, 46, 76), {"az": -146, "el": 24, "k": 1.0, "radius": 30, "centre": (897, 62, 0), "fov": 28}, None),
        ("h", "(아) 입체 교차: 낮은 길 위로 고가",
         "기존 길보다 너무 높으면 같은 높이로 만나지 않고 그 위로 다리(아래로 6칸 넘게 비움, 교각은 기존 길 밖)",
         (932, 978, -40, 24, 50, 74), {"az": 160, "el": 22, "k": 1.0, "radius": 34, "centre": (955, 60, 2)}, None),
        ("overview", "전체: 강의 다리 → 산의 굴 → 숲 속 굽은 길 → 하역장 입구",
         "한 줄로 이어진 길 (길 양옆 24칸만, 굴은 앞쪽 반을 잘라 낸 그림): 언제나 경사 1:3 이하, 바닥 폭 5칸",
         (1172, 1462, -26, 194, 59, 96), {"az": 180, "el": 56, "k": 1.0, "radius": 330, "centre": (1317, 64, 92), "fov": 30},
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


SIGN_R = 31.0   # how far the camera stands from the row of signs (blocks)
SIGN_FOV = 12.5
# the signs in the row straight on: which (the strip's sign of that kind; ADVANCE3 = the crossing's three-way one), its label
FACES = [("START", "출발 24칸 뒤: 방면 + 전체 거리"), ("KM", "1,000칸마다 · 갈림길 지나서: 남은 거리"),
         ("ADVANCE", "갈림길 120·40칸 앞 (예고)"), ("GORE", "갈라지는 곳 (V 사이)"), ("ADVANCE3", "교차로 앞: 세 갈래"),
         ("RETURN", "돌아가는 길 (반대쪽)"), ("ARRIVAL", "입구 20칸 앞: 도착")]


def _pick(strip, kind):
    signs = strip.d["signs"]
    if kind == "ADVANCE3":
        return next(g for g in signs if g["kind"] == "ADVANCE" and g.get("rows") == 3)
    if kind == "ADVANCE":   # the near one on the strip before the fork, for its eastbound traffic
        cands = [g for g in signs if g["kind"] == "ADVANCE" and g.get("rows") == 2 and abs(g["yaw"] - 90) < 1 and g["x"] < 1160]
        return max(cands, key=lambda g: g["x"])
    return next(g for g in signs if g["kind"] == kind)


def sign_faces(strip, size):
    """The kinds of sign straight on, side by side (each its own width, a block apart), as the test placed them: (picture,
    [(label, x in the picture)])."""
    picked = [(_pick(strip, k), label) for k, label in FACES]
    widths = [4.0 if g.get("model") == "guide" else 3.0 for g, _ in picked]
    total = sum(widths) + (len(widths) - 1) * 1.0
    moved, left = [], total / 2
    w, h = size
    vh = 2 * SIGN_R * math.tan(math.radians(SIGN_FOV / 2))   # blocks seen top to bottom
    places = []
    for (g, label), wd in zip(picked, widths):
        ox = left - wd / 2   # facing north (yaw 180): +x is on the viewer's left
        left -= wd + 1.0
        r = math.radians(g["yaw"])
        R, F = (math.cos(r), math.sin(r)), (-math.sin(r), math.cos(r))

        def local(t):
            dx, dz = t["x"] - g["x"], t["z"] - g["z"]
            right, out = dx * R[0] + dz * R[1], dx * F[0] + dz * F[1]
            return dict(t, x=ox - right, y=t["y"] - g["y"], z=-out)
        moved.append(dict(g, x=ox, y=0.0, z=0.0, yaw=180.0, texts=[local(t) for t in g["texts"]], arrows=[local(a) for a in g.get("arrows", [])]))
        places.append((label, w / 2 - ox * h / vh))
    tex = {}
    els = sign_elements(moved, (0, 0, 0), tex)
    v = {"x": 0, "y": 0, "w": w, "h": h, "az": 0, "el": 0, "k": 1.0, "radius": SIGN_R * 16, "fov": SIGN_FOV, "far": 40000,
         "center": [0, 4.6 * 16, 0]}
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / "road_signs.preview.json", BUILD / "road_signs.png"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "views": [v], "size": [w, h]}))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    return Image.open(png).convert("RGB"), places


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
        wide = name in ("overview",)
        size = (OW, OH) if wide else (PW * 2, PH) if name == "g" else (PW, PH)
        rendered[name] = (render(strip, name, box, view, size, cut), title, note)
    if only:
        return   # a try: the panels are in tools/assetgen/build/road_<name>.png
    over, otitle, onote = rendered["overview"]
    FH = 560
    faces, places = sign_faces(strip, (OW, FH))
    ROWS = 4
    sheet = Image.new("RGB", (OW, ROWS * (PH + HEAD) + FH + HEAD + OH + HEAD), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    # (가)~(바) in two rows, (사) the fork two wide beside (아), (자) the half-step bridge beside the blocks
    cells = [("a", 0, 0), ("b", 1, 0), ("c", 2, 0), ("d", 0, 1), ("e", 1, 1), ("f", 2, 1), ("g", 0, 2), ("h", 2, 2), ("i", 0, 3)]
    for name, cx, cy in cells:
        im, title, note = rendered[name]
        x, y = cx * PW, cy * (PH + HEAD)
        sheet.paste(im, (x, y + HEAD))
        _head(d, x, y, title, note, im.width, font, small)
    # the rest of the last row: what the blocks are
    lx, ly = PW + 30, 3 * (PH + HEAD) + 20
    d.text((lx, ly), "블록 (/관리자 국도)", fill=(245, 245, 245), font=font)
    legend = [("gray_concrete", "바닥 (roads.surface), 군데군데 기운·금 간 자리"), ("deepslate_tile_slab", "반 칸 오르내림: 반 블록 (경사 1:3 그대로)"),
              ("yellow_concrete", "가운데 점선 (roads.center-line)"), ("smooth_quartz", "흰 가장자리 선 (roads.edge-line), 횡단보도"),
              ("smooth_stone", "갓돌 (roads.kerb)"), ("dirt_path", "갓길 흙길 (12칸마다 배수구), 그 밖은 잔디"),
              ("andesite_wall", "높은 성토 가드레일 · 20칸마다 반사 기둥"), ("stone_brick_wall", "다리 난간, 팔 달린 가로등 (40칸마다, 아래 4칸 비움)"),
              ("stone_bricks", "다리 가장자리 받침(반 칸 다리에서도 온 블록) · 가로등·표지판 받침 · 교각 · 굴")]
    yy = ly + 46
    for key, text in legend:
        d.rectangle((lx, yy + 2, lx + 26, yy + 26), fill=COLOURS[key], outline=(10, 10, 10))
        d.text((lx + 40, yy), text, fill=(220, 222, 228), font=small)
        yy += 33
    for k, line in enumerate(("RoadPreviewTest 가 실제 길 코드(RoadProfile, RoadSection)와 표지판 계획(RoadGuide)으로",
                              "지어 본 블록과 표지판을 그대로 그린 그림 (바닐라 텍스처 없음)")):
        d.text((lx, yy + 8 + k * 26), line, fill=(150, 155, 165), font=small)
    # the signs straight on: what RoadPreviewTest's roads put up (RoadSign's words and places, the models)
    fy = ROWS * (PH + HEAD)
    sheet.paste(faces, (0, fy + HEAD))
    _head(d, 0, fy, "국도 표지판 (3D 모델 + 화살표 모델 + 글씨 디스플레이)",
          "작은 판(3×1.5칸): 오른쪽에 서서 오는 차를 바라봄, 큰 줄 = 하역장, 아랫줄 = 노선 방패 · 거리. 안내판(4×2칸): 갈림길·교차로 앞, "
          "줄마다 그 길로 가면 나오는 곳, 화살표(↑ ↖ ↗ ← →)는 그 길이 나가는 쪽, 거리는 표지판에서. 판 아래는 길 위 3칸", OW, font, small)
    for label, x in places:
        d.text((x - d.textlength(label, font=small) / 2, fy + HEAD + 14), label, fill=(185, 190, 200), font=small)
    oy = fy + FH + HEAD
    sheet.paste(over, (0, oy + HEAD))
    _head(d, 0, oy, otitle, onote, OW, font, small)
    sheet.save(out, optimize=True)
    print("road   ", out)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--only=")]
    only = [a[7:].split(",") for a in sys.argv[1:] if a.startswith("--only=")]
    main(Path(args[0]) if args else REPO / "docs" / "previews" / "road.png", only[0] if only else None)
