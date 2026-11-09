"""
Preview sheets of the Japanese-style furniture (needs: cd render && npm install):

    python3 furniture_preview.py            # every sheet
    python3 furniture_preview.py tatami     # only the given pieces (into build/, for quick looks)

docs/previews/furniture_japanese.png        every piece from three angles (front 3/4, back 3/4, above), its
                                            name, size and its inventory icon
docs/previews/furniture_japanese_icons.png  the 2D inventory icons, large and at their real 16 px size
docs/previews/furniture_japanese_room.png   a room and a garden furnished with them, at about in-game distance
"""

import copy
import json
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


def piece(pid, ox=0, oz=0, turn=0):
    """Preview data of a piece standing on the floor (y 0), moved by (ox, oz) model units and turned by
    `turn` quarter turns around the anchor cell's centre (as placing it facing another way would)."""
    fn, _size, floor = F.PIECES[pid]
    data = fn().to_preview()

    def tr(p):
        x, y, z = p[0] - 8, p[1] - floor, p[2] - 8
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
    data = {"textures": tex, "elements": els, "grid": True, "center": centre, "radius": radius, "views": views}
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


if __name__ == "__main__":
    main()
