"""
Preview sheets of the Japanese-style furniture (needs: cd render && npm install):

    python3 furniture_preview.py            # every sheet
    python3 furniture_preview.py tatami     # only the given pieces (into build/, for quick looks)

docs/previews/furniture_japanese.png        every piece from three angles (front 3/4, back 3/4, above), its
                                            name, size and its inventory icon
docs/previews/furniture_japanese_icons.png  the 2D inventory icons, large and at their real 16 px size
docs/previews/furniture_japanese_room.png   a room and a garden furnished with them, at about in-game distance
docs/previews/furniture_scale.png           every piece next to a player and a block, all at one scale
docs/previews/furniture_footprints.png      every piece from straight above, placed facing each of the four ways,
                                            its cells outlined and its model's bounds with the margins (a piece
                                            sits in the middle of its cells: the same margin on both sides)

    python3 furniture_preview.py --scale [out.png]     # only the scale sheet
    python3 furniture_preview.py --footprints [out.png]  # only the footprint sheet
    python3 furniture_preview.py --pieces <dir> [ids]  # one large four-view picture per piece
"""

import copy
import json
import math
import re
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from models import furniture_japanese as F     # noqa: E402
import furniture_icons as FI                   # noqa: E402
import yaml                                    # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
ITEMS = REPO / "content/stsv/items/furniture_japanese.yml"


def names_ko():
    if not ITEMS.exists():
        return {}
    items = yaml.safe_load(ITEMS.read_text())
    return {k: re.sub(r"<[^>]+>", "", v["name"]) for k, v in items.items() if isinstance(v, dict) and "name" in v}


_FURNITURE = {}


def furniture(pid):
    """The piece's furniture settings from the item file (cells, translation, scale)."""
    if not _FURNITURE:
        _FURNITURE.update({k: v["furniture"] for k, v in yaml.safe_load(ITEMS.read_text()).items()
                           if isinstance(v, dict) and "furniture" in v})
    return _FURNITURE[pid]


def placement(pid):
    """Model point -> where the item display shows it, in the furniture's model space (16 = 1 block, cell
    (0,0,0) spans 0..16), as FurnitureShape.mainPlacement does: the translation (blocks, x / z flipped by the
    item display's half turn) and the scale around the model's centre."""
    f = furniture(pid)
    t = f.get("translation", [0, 0, 0])
    s = f.get("scale", [1, 1, 1])
    t = (-t[0], t[1], -t[2])
    return lambda p: [8 + 16 * t[i] + s[i] * (p[i] - 8) for i in range(3)]


def piece(pid, ox=0, oz=0, turn=0):
    """Preview data of a piece standing on the floor (y 0) as the item file places it, moved by (ox, oz) model
    units and turned by `turn` quarter turns around the anchor cell's centre (as placing it facing another way
    would)."""
    fn = F.PIECES[pid][0]
    data = fn().to_preview()
    place = placement(pid)

    def tr(p):
        x, y, z = place(p)
        x, z = x - 8, z - 8
        for _ in range(turn % 4):
            x, z = -z, x
        return [x + 8 + ox, y, z + 8 + oz]

    out = []
    for e in data["elements"]:
        e = copy.deepcopy(e)
        a, b = tr(e["from"]), tr(e["to"])
        e["from"], e["to"] = [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]
        if turn % 4:
            # quarter turns move faces round; rotated elements and per-face UVs are only used unturned here
            faces = e["faces"]
            order = ["north", "east", "south", "west"]
            nf = {}
            for f, v in faces.items():
                nf[order[(order.index(f) + turn) % 4] if f in order else f] = v
            e["faces"] = nf
            if e.get("rotation"):
                axis, angle, origin = e["rotation"]
                o = tr(origin)
                if turn % 2 and axis in "xz":
                    axis = "z" if axis == "x" else "x"
                e["rotation"] = [axis, angle, o]
        elif e.get("rotation"):
            e["rotation"][2] = tr(e["rotation"][2])
        out.append(e)
    return data["textures"], out


def render(data, tag):
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / f"fur_{tag}.json", BUILD / f"fur_{tag}.png"
    src.write_text(json.dumps(data))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, capture_output=True)
    return Image.open(png).convert("RGB")


THREE_VIEWS = [{"x": 0, "y": 0, "w": 1000, "h": 1000, "az": 35, "el": 24, "k": 2.8},
               {"x": 1000, "y": 0, "w": 600, "h": 500, "az": 215, "el": 22, "k": 3.1},
               {"x": 1000, "y": 500, "w": 600, "h": 500, "az": 125, "el": 55, "k": 3.1}]
# close-ups: front 3/4, back 3/4, from above, and low from the front left (about a player's eye level)
FOUR_VIEWS = [{"x": 0, "y": 0, "w": 800, "h": 1000, "az": 30, "el": 20, "k": 2.6},
              {"x": 800, "y": 0, "w": 800, "h": 500, "az": 210, "el": 25, "k": 2.9},
              {"x": 800, "y": 500, "w": 400, "h": 500, "az": 120, "el": 60, "k": 3.0},
              {"x": 1200, "y": 500, "w": 400, "h": 500, "az": -40, "el": 5, "k": 2.8}]


def views_of(pid, views=THREE_VIEWS):
    tex, els = piece(pid)
    lo = [min(e["from"][i] for e in els) for i in range(3)]
    hi = [max(e["to"][i] for e in els) for i in range(3)]
    centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
    radius = max(10.0, sum((hi[i] - lo[i]) ** 2 for i in range(3)) ** 0.5 / 2)
    data = {"textures": tex, "elements": els, "grid": True, "gridAt": [0, 0], "center": centre, "radius": radius,
            "views": views}
    return render(data, pid)


def size_text(pid):
    w, h, d = F.SIZES[pid]
    return f"{w}×{d}칸" + (f", 높이 {h}" if h > 1 else "")


def contact_sheet(pids, out, ko):
    font = ImageFont.truetype(FONT, 22)
    small = ImageFont.truetype(FONT, 17)
    cw, ch, head = 560, 350, 34
    cols = 3
    rows = (len(pids) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cw, rows * (ch + head)), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    for k, pid in enumerate(pids):
        x, y = (k % cols) * cw, (k // cols) * (ch + head)
        im = views_of(pid).resize((cw, ch), Image.LANCZOS)
        sheet.paste(im, (x, y + head))
        d.rectangle((x, y, x + cw - 2, y + head - 2), fill=(40, 43, 52))
        d.text((x + 10, y + 5), ko.get(pid, pid), font=font, fill=(245, 242, 232))
        d.text((x + 250, y + 9), f"{pid} · {size_text(pid)}", font=small, fill=(170, 170, 178))
        icon = FI.ICONS[pid]().resize((64, 64), Image.NEAREST)
        bg = Image.new("RGBA", (72, 72), (60, 64, 74, 255))
        bg.alpha_composite(icon, (4, 4))
        sheet.paste(bg.convert("RGB"), (x + 8, y + head + ch - 80))
        print(f"  {pid}")
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO)}")


def icon_sheet(pids, out, ko):
    font = ImageFont.truetype(FONT, 16)
    cols, cell = 6, 150
    rows = (len(pids) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell + 10, rows * (cell + 10) + 10), (54, 54, 62))
    d = ImageDraw.Draw(sheet)
    for k, pid in enumerate(pids):
        x, y = 10 + (k % cols) * cell, 10 + (k // cols) * (cell + 10)
        icon = FI.ICONS[pid]()
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))     # the inventory slot grey
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        sheet.paste(bg.convert("RGB"), (x, y))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        sheet.paste(slot.convert("RGB"), (x + 102, y + 60))
        d.text((x, y + 100), ko.get(pid, pid), font=font, fill=(240, 240, 240))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO)}")


# a tatami room and a garden next to it, in model units: (piece, x, z), every piece facing the front (north)
ROOM = [
    ("tatami", 0, 0), ("tatami", 32, 0), ("tatami", 0, 16), ("tatami", 32, 16), ("tatami", 0, 32), ("tatami", 32, 32),
    ("kotatsu", 24, 16), ("shoji_screen", 0, 50), ("fusuma_panel", 32, 50), ("andon", 52, 36), ("maneki_neko", 0, 36),
    ("tea_kettle", 0, 0), ("ikebana", 52, 0), ("taiko_drum", 68, 44),
    ("stepping_stones", 84, 0), ("stepping_stones", 92, 16), ("koi_pond", 104, 16), ("stone_lantern", 140, 30),
    ("sakura_pot", 140, 8), ("wagasa", 86, 40), ("bamboo_pot", 120, 52), ("bonsai", 140, 52),
    ("bamboo_fence", 84, 66), ("bamboo_fence", 100, 66), ("bamboo_fence", 116, 66), ("bamboo_fence", 132, 66),
    ("bamboo_fence", 148, 66), ("torii", 112, 84),
]


def room(out):
    textures, elements = {}, []
    for (pid, ox, oz) in ROOM:
        tex, els = piece(pid, ox, oz)
        textures.update(tex)
        elements += els
    data = {"textures": textures, "elements": elements, "grid": False,
            "views": [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": 28, "el": 36, "k": 2.15}]}
    render(data, "room").save(out, optimize=True)
    print(f"scene   {out.relative_to(REPO)}")


# the real thing each piece stands for, roughly (w x d x h, metres; 1 block ~ 1 m, a player 1.8 m)
REAL = {
    "tatami": "1.8×0.9 m, 두께 5 cm", "zabuton": "55×60 cm, 두께 10 cm", "chabudai": "지름 75 cm, 높이 35 cm",
    "kotatsu": "탁자 80 cm, 이불 1.9 m", "futon": "2인용 1.4×2.1 m", "shoji_screen": "폭 1.8 m, 높이 1.7 m",
    "fusuma_panel": "문 한 짝 0.9×1.8 m", "hanging_scroll": "폭 0.55 m, 길이 1.8 m", "noren": "폭 0.9 m, 길이 0.9~1.5 m",
    "bamboo_fence": "높이 1.2~1.8 m", "andon": "폭 30 cm, 높이 75 cm", "stone_lantern": "높이 1.5~2 m",
    "chochin": "지름 30~45 cm, 높이 50~70 cm", "bonsai": "높이 30~60 cm", "ikebana": "수반 35 cm, 높이 60~90 cm",
    "bamboo_pot": "높이 1.5~2 m", "sakura_pot": "높이 1~1.5 m", "tea_set": "쟁반 35 cm, 주전자 12 cm",
    "tea_kettle": "화로 45 cm, 높이 50 cm", "sake_set": "병 15 cm, 쟁반 30 cm", "taiko_drum": "북 지름 60~80 cm, 높이 1.3 m",
    "maneki_neko": "가게 장식용 30~90 cm", "daruma": "절 장식용 30~90 cm", "omamori_rack": "폭 0.9 m, 높이 1 m",
    "wind_chime": "유리 10 cm, 쪽지 포함 45 cm", "wagasa": "지름 2~2.4 m, 높이 2.5 m", "kimono_rack": "폭 1.8 m, 높이 1.8 m",
    "torii": "작은 신사 폭 3 m, 높이 3 m", "stepping_stones": "돌 하나 40~60 cm", "koi_pond": "작은 정원 연못 2 m",
}


def _ref_models():
    """A player (1.875 blocks, the player model's 30 px) and an oak-plank-like block, both in palette colours."""
    from stsv_assets.bake import BModel, Paint
    p = BModel("ref_player")
    legs, body, head = (Paint("steel", k, noise=0, ao=0, rim=0.6) for k in (1, 2, 3))
    s = 0.9375
    for x0 in (-4, 0):
        p.box("leg", (x0 * s, 0, -2 * s), ((x0 + 4) * s, 12 * s, 2 * s), legs)
    p.box("body", (-4 * s, 12 * s, -2 * s), (4 * s, 24 * s, 2 * s), body)
    for x0 in (-8, 4):
        p.box("arm", (x0 * s, 12 * s, -2 * s), ((x0 + 4) * s, 24 * s, 2 * s), body)
    p.box("head", (-4 * s, 24 * s, -4 * s), (4 * s, 32 * s, 4 * s), head)
    b = BModel("ref_block")
    b.box("block", (0, 0, 0), (16, 16, 16), Paint("wood", 2, "planks", axis="x", width=4, ao=0))
    return p.to_preview(), b.to_preview()


def _moved(els, dx, dz):
    out = []
    for e in els:
        e = copy.deepcopy(e)
        e["from"] = [e["from"][0] + dx, e["from"][1], e["from"][2] + dz]
        e["to"] = [e["to"][0] + dx, e["to"][1], e["to"][2] + dz]
        if e.get("rotation"):
            o = e["rotation"][2]
            e["rotation"][2] = [o[0] + dx, o[1], o[2] + dz]
        out.append(e)
    return out


def scale_sheet(pids, out, ko):
    """Every piece at the same scale next to a player and a block, seen from the front at about eye level,
    with the real object's size and the model's: does it read the right size in the game?"""
    player, block = _ref_models()
    cols, cw, vh, label = 5, 360, 300, 62
    rows = (len(pids) + cols - 1) // cols
    textures = {**player["textures"], **block["textures"]}
    elements, views, info = [], [], []
    ppu = 3.9                                        # picture px per model unit
    fov = 10
    dist = (vh / ppu) / 2 / math.tan(math.radians(fov / 2))
    for k, pid in enumerate(pids):
        tex, els = piece(pid)
        textures.update(tex)
        lo = [min(e["from"][i] for e in els) for i in range(3)]
        hi = [max(e["to"][i] for e in els) for i in range(3)]
        base = k * 240                               # far apart: one scene, one view per piece
        zc = (lo[2] + hi[2]) / 2
        elements += _moved(els, base, 0)
        px = hi[0] + 4 + 7.5                         # the player on the viewer's left (+x), the block on the right
        elements += _moved(player["elements"], base + px, zc)
        bx = lo[0] - 4 - 16
        elements += _moved(block["elements"], base + bx, zc - 8)
        cx = (bx + px + 7.5) / 2
        x, y = (k % cols) * cw, (k // cols) * vh
        views.append({"x": x, "y": y, "w": cw, "h": vh, "az": 14, "el": 8, "k": dist, "radius": 1, "fov": fov,
                      "far": 4000, "center": [base + cx, 26, zc]})
        info.append((pid, hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]))
    data = {"textures": textures, "elements": elements, "grid": False, "size": [cols * cw, rows * vh], "views": views}
    im = render(data, "scale")
    font = ImageFont.truetype(FONT, 21)
    small = ImageFont.truetype(FONT, 15)
    head = 52
    sheet = Image.new("RGB", (cols * cw, head + rows * (vh + label)), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "실제 크기 비교 · 사람 1.8칸(플레이어), 오른쪽 상자 = 블록 1칸 · 모든 칸 같은 축척",
           font=font, fill=(235, 232, 222))
    for k, (pid, w, h, dd) in enumerate(info):
        x, y = (k % cols) * cw, (k // cols) * vh
        oy = head + (k // cols) * (vh + label)
        sheet.paste(im.crop((x, y, x + cw, y + vh)), (x, oy))
        d.rectangle((x, oy + vh, x + cw - 2, oy + vh + label - 2), fill=(40, 43, 52))
        d.text((x + 8, oy + vh + 4), ko.get(pid, pid), font=font, fill=(245, 242, 232))
        d.text((x + 8, oy + vh + 32), f"실제 {REAL.get(pid, '?')}", font=small, fill=(170, 170, 178))
        d.text((x + cw - 8, oy + vh + 8), f"{w / 16:.2f}×{dd / 16:.2f}×{h / 16:.2f}칸", font=small, fill=(200, 196, 160),
               anchor="ra")
    sheet.save(out, optimize=True)
    print(f"scale   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


def _solid(rgb):
    """A 16 x 16 texture of one colour, as a data URL (outlines drawn into a preview)."""
    import base64
    import io
    buf = io.BytesIO()
    Image.new("RGBA", (16, 16), (*rgb, 255)).save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def _outline(x0, z0, x1, z1, y, w, tex):
    """Four thin flat strips (only their top faces) round the rectangle x0..x1 / z0..z1 at height y."""
    face = {"up": {"uv": [0, 0, 16, 16], "texture": tex}}
    strips = ((x0, z0, x1, z0 + w), (x0, z1 - w, x1, z1), (x0, z0, x0 + w, z1), (x1 - w, z0, x1, z1))
    return [{"from": [a, y, b], "to": [c, y + 0.01, d], "glow": True, "shade": False, "rotation": None, "faces": face}
            for (a, b, c, d) in strips]


def _bounds(els):
    """x / z bounds of preview elements (rotated ones by their turned corners)."""
    xs, zs = [], []
    for e in els:
        corners = [[(e["from"], e["to"])[i >> k & 1][k] for k in range(3)] for i in range(8)]
        if e.get("rotation"):
            axis, angle, o = e["rotation"]
            a = math.radians(angle)
            u, v = {"x": (1, 2), "y": (2, 0), "z": (0, 1)}[axis]
            for p in corners:
                pu, pv = p[u] - o[u], p[v] - o[v]
                p[u], p[v] = o[u] + pu * math.cos(a) - pv * math.sin(a), o[v] + pu * math.sin(a) + pv * math.cos(a)
        xs += [p[0] for p in corners]
        zs += [p[2] for p in corners]
    return min(xs), max(xs), min(zs), max(zs)


def footprint_sheet(pids, out, ko):
    """Every piece seen from straight above, placed facing each of the four ways: its cells outlined (blue), the
    bounds of its model (orange) and the margins between them, which are the same on both sides (the piece sits
    in the middle of its cells; JapaneseFurnitureTest checks it on the shipped models)."""
    cols, tile, label, ppu = 3, 150, 54, 2.0
    rows = (len(pids) + cols - 1) // cols
    fov = 2
    dist = (tile / ppu) / 2 / math.tan(math.radians(fov / 2))
    textures = {"_cells": _solid((90, 170, 255)), "_bounds": _solid((255, 150, 40))}
    elements, views, info = [], [], []
    for k, pid in enumerate(pids):
        cells = furniture(pid)["cells"]
        for turn in range(4):
            base = (k * 4 + turn) * 200                  # far apart: one scene, one view per placement
            tex, els = piece(pid, base, 0, turn)
            textures.update(tex)
            elements += els
            top = max(e["to"][1] for e in els) + 0.5
            sq = []
            for (cx, _cy, cz) in cells:
                x, z = cx * 16, cz * 16                  # the cell's offset from the anchor's, turned like piece()
                for _ in range(turn):
                    x, z = -z, x
                sq.append((x + base, z))
            for (x, z) in set(sq):
                elements += _outline(x, z, x + 16, z + 16, top, 0.6, "_cells")
            fx0, fx1 = min(q[0] for q in sq), max(q[0] for q in sq) + 16
            fz0, fz1 = min(q[1] for q in sq), max(q[1] for q in sq) + 16
            bx0, bx1, bz0, bz1 = _bounds(els)
            elements += _outline(bx0, bz0, bx1, bz1, top + 0.2, 0.6, "_bounds")
            col, row = (k % cols) * 4 + turn, k // cols
            views.append({"x": col * tile, "y": row * tile, "w": tile, "h": tile, "az": 0, "el": 89.5, "k": dist,
                          "radius": 1, "fov": fov, "far": 8000, "center": [(fx0 + fx1) / 2, 0, (fz0 + fz1) / 2]})
            if turn == 0:
                info.append((pid, (bx0 - fx0, fx1 - bx1), (bz0 - fz0, fz1 - bz1)))
    data = {"textures": textures, "elements": elements, "grid": False, "size": [cols * 4 * tile, rows * tile],
            "views": views}
    im = render(data, "footprints")
    font = ImageFont.truetype(FONT, 19)
    small = ImageFont.truetype(FONT, 14)
    head = 52
    sheet = Image.new("RGB", (cols * 4 * tile, head + rows * (tile + label)), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "칸과 모델 (위에서) · 파랑 = 차지하는 칸, 주황 = 모델의 범위 · 놓는 방향 4가지 (앞 = 아래쪽) · 여백은 양쪽이 같게",
           font=font, fill=(235, 232, 222))
    for k, (pid, mx, mz) in enumerate(info):
        x, oy = (k % cols) * 4 * tile, head + (k // cols) * (tile + label)
        sheet.paste(im.crop((x, (k // cols) * tile, x + 4 * tile, (k // cols + 1) * tile)), (x, oy))
        d.rectangle((x, oy + tile, x + 4 * tile - 2, oy + tile + label - 2), fill=(40, 43, 52))
        d.text((x + 8, oy + tile + 4), ko.get(pid, pid), font=font, fill=(245, 242, 232))
        ok = abs(mx[0] - mx[1]) <= 1 and abs(mz[0] - mz[1]) <= 1
        note = "" if ok else ("  (벽에 거는 것: 뒤는 벽에 붙음)" if pid == "hanging_scroll" else "  !")
        d.text((x + 8, oy + tile + 30), f"여백 x {mx[0] / 16:.2f} / {mx[1] / 16:.2f} · z {mz[0] / 16:.2f} / {mz[1] / 16:.2f}칸{note}",
               font=small, fill=(170, 200, 170) if ok else (230, 190, 120))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


def piece_images(pids, out_dir, ko):
    """One large picture per piece (four angles, its Korean name, size and icon), to judge details."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype(FONT, 34)
    small = ImageFont.truetype(FONT, 22)
    paths = []
    for k, pid in enumerate(pids):
        im = views_of(pid, FOUR_VIEWS)
        head = 64
        out = Image.new("RGB", (im.width, im.height + head), (40, 43, 52))
        out.paste(im, (0, head))
        d = ImageDraw.Draw(out)
        d.text((18, 12), ko.get(pid, pid), font=font, fill=(245, 242, 232))
        d.text((420, 22), f"{size_text(pid)}  ·  {pid}  ·  왼쪽: 앞 3/4, 오른쪽 위: 뒤, 아래: 위에서 · 눈높이", font=small, fill=(180, 180, 188))
        icon = FI.ICONS[pid]().resize((128, 128), Image.NEAREST)
        bg = Image.new("RGBA", (140, 140), (139, 139, 139, 255))
        bg.alpha_composite(icon, (6, 6))
        out.paste(bg.convert("RGB"), (16, out.height - 156))
        path = out_dir / f"{k + 1:02d}_{pid}.png"
        out.save(path, optimize=True)
        paths.append(path)
        print(f"piece   {path}")
    return paths


def main():
    ko = names_ko()
    args = sys.argv[1:]
    if args[:1] == ["--scale"]:
        scale_sheet(list(F.PIECES), Path(args[1]) if len(args) > 1 else PREVIEWS / "furniture_scale.png", ko)
        return
    if args[:1] == ["--footprints"]:
        footprint_sheet(list(F.PIECES), Path(args[1]) if len(args) > 1 else PREVIEWS / "furniture_footprints.png", ko)
        return
    if args[:1] == ["--pieces"]:
        piece_images(args[2:] or list(F.PIECES), args[1], ko)
        return
    if args:
        for pid in args:
            views_of(pid).save(BUILD / f"fur_{pid}_views.png")
            print(BUILD / f"fur_{pid}_views.png")
        return
    pids = list(F.PIECES)
    contact_sheet(pids, PREVIEWS / "furniture_japanese.png", ko)
    icon_sheet(pids, PREVIEWS / "furniture_japanese_icons.png", ko)
    room(PREVIEWS / "furniture_japanese_room.png")
    scale_sheet(pids, PREVIEWS / "furniture_scale.png", ko)
    footprint_sheet(pids, PREVIEWS / "furniture_footprints.png", ko)


if __name__ == "__main__":
    main()
