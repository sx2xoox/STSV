"""
그림 그리기 (이젤) assets (core README "그림 그리기 (이젤)"): writes only the easel's own files, and its preview sheet.

    python3 tools/assetgen/easel_assets.py            # models + icons + docs/previews/easel.png
    python3 tools/assetgen/easel_assets.py --no-sheet # models + icons only
    (the sheet needs: cd tools/assetgen/render && npm install)

  models   content/stsv/models/easel.bbmodel, easel_canvas.bbmodel (models/furniture_easel.py; build.py has them too,
           so a full build writes the same files)
  icons    content/stsv/textures/easel_icon.png (the easel's inventory icon), blank_canvas.png, unfinished_canvas.png,
           paint_brush.png, painting_framed.png (the finished painting in inventories; in hand and in a frame it shows
           its picture: it is a map)
  sheet    the empty easel, the easel with a sample painting on it (front, side, back), the sample painting at its real
           128 x 128 in the map palette, and the icons
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
PACK = REPO / "content" / "stsv"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
SHEET = REPO / "docs" / "previews" / "easel.png"
FONT = HERE / "fonts" / "Sunflower-Medium.ttf"


# ================================================================================================
# Icons (16 x 16, the shared pixelart Canvas: light from the top left, outline in the darkest step)
# ================================================================================================

ICONS = {}


def icon(name):
    def deco(fn):
        ICONS[name] = fn
        return fn
    return deco


def _grid(rows, pixels):
    from stsv_assets.pixelart import Canvas
    c = Canvas()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                mat, step, z = pixels[ch]
                c.put(x, y, mat, step, z=z)
    return c.render(contact=False)


# a small landscape: sky, sun, hills, a field (the easel's and the finished painting's picture)
SCENE = {
    "S": ("ice", 4, 5), "s": ("ice", 3, 5), "U": ("petal_yellow", 4, 6), "H": ("leaf", 3, 5), "h": ("leaf_dark", 3, 5),
    "G": ("sprout", 3, 5), "g": ("sprout", 2, 5), "R": ("vermilion", 3, 6),
}


@icon("easel_icon")
def easel_icon():
    """The easel from the front: a canvas with a landscape on the tray, the clamp on top, the legs splayed below."""
    rows = ["......kkkk......",
            "..CCCCCCCCCCCC..",
            "..CSSSSSSSSUUC..",
            "..CSSSSSSSSUUC..",
            "..CsSSSSSSSSSC..",
            "..CHHsSSSSSShC..",
            "..CHHHHSSShhhC..",
            "..CGGHHHhhhhGC..",
            "..CGgGGGGGgGGC..",
            "..CCCCCCCCCCCC..",
            ".TTTTTTTTTTTTTT.",
            "......LbbL......",
            ".....L.bb.L.....",
            "....LKKKKKKL....",
            "...L...bb...L...",
            "..L....bb....L.."]
    px = dict(SCENE)
    px.update({"C": ("cream", 4, 4), "k": ("hinoki", 2, 3), "T": ("hinoki", 4, 3), "t": ("hinoki", 2, 3),
               "L": ("hinoki", 3, 2), "K": ("hinoki", 2, 2), "b": ("hinoki", 1, 1)})
    return _grid(rows, px)


BOARD = {"C": ("cream", 4, 4), "c": ("cream", 2, 4), "W": ("snow", 4, 5), "w": ("snow", 3, 5)}


@icon("blank_canvas")
def blank_canvas():
    """A blank stretched canvas, a little turned: the white front and its wrapped side."""
    rows = ["................",
            "................",
            "..CCCCCCCCCCCc..",
            "..CWWWWWWWWWWc..",
            "..CWWWWWWWWWWc..",
            "..CWWWWWWWWWwc..",
            "..CWWWWWWWWWwc..",
            "..CWWWWWWWWwwc..",
            "..CWWWWWWWWWwc..",
            "..CWWWWWWWWwwc..",
            "..CWWWWWWWwwwc..",
            "..CWWWWWWWWWwc..",
            "..CCCCCCCCCCCc..",
            "...ccccccccccc..",
            "................",
            "................"]
    return _grid(rows, BOARD)


@icon("unfinished_canvas")
def unfinished_canvas():
    """The canvas half painted: a sun and a hill coloured in, the rest a pencil sketch."""
    rows = ["................",
            "................",
            "..CCCCCCCCCCCc..",
            "..CWWWWWWWWUUc..",
            "..CWWWWWWWWUUc..",
            "..CWWWWWWWWWwc..",
            "..CHHpWWWWWWwc..",
            "..CHHHHpWWpppc..",
            "..CGGHHHpppWwc..",
            "..CGGGGWWWWwwc..",
            "..CGGWWWWWwwwc..",
            "..CWWWWWWWWWwc..",
            "..CCCCCCCCCCCc..",
            "...ccccccccccc..",
            "................",
            "................"]
    px = dict(BOARD)
    px.update(SCENE)
    px["p"] = ("stone", 2, 6)
    return _grid(rows, px)


@icon("paint_brush")
def paint_brush():
    """A painter's brush on the slant: a long dark handle, a brass ferrule, bristles dipped in red."""
    rows = ["..............RR",
            ".............RRR",
            "............bRR.",
            "...........bbR..",
            "..........FFb...",
            ".........FFF....",
            "........HHF.....",
            ".......HHh......",
            "......HHh.......",
            ".....HHh........",
            "....HHh.........",
            "...HHh..........",
            "..HHh...........",
            ".HHh............",
            ".Hh.............",
            "................"]
    px = {"R": ("vermilion", 3, 6), "b": ("straw", 3, 5), "F": ("brass", 3, 4), "H": ("wood_dark", 3, 3),
          "h": ("wood_dark", 2, 3)}
    return _grid(rows, px)


@icon("painting_framed")
def painting_framed():
    """A finished painting: the landscape in a gilt frame."""
    rows = ["................",
            ".OOOOOOOOOOOOOO.",
            ".OooooooooooooO.",
            ".OoSSSSSSSSUUoO.",
            ".OoSSSSSSSSUUoO.",
            ".OosSSSSSSSSSoO.",
            ".OoHHsSSSSSShoO.",
            ".OoHHHHSSShhhoO.",
            ".OoGGHHHhhhhGoO.",
            ".OoGgGGRGGgGGoO.",
            ".OoGGGGGGGGgGoO.",
            ".OooooooooooooO.",
            ".OOOOOOOOOOOOOO.",
            "................",
            "................",
            "................"]
    px = dict(SCENE)
    px.update({"O": ("gold", 4, 3), "o": ("gold", 2, 4)})
    return _grid(rows, px)


# ================================================================================================
# Files
# ================================================================================================

def write():
    from models import furniture_easel as E
    (PACK / "models").mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(exist_ok=True)
    for name, fn in E.ALL.items():
        fn().write(PACK / "models" / f"{name}.bbmodel", BUILD / f"{name}.preview.json")
    for name, fn in ICONS.items():
        fn().save(PACK / "textures" / f"{name}.png")
    return list(E.ALL) + list(ICONS)


# ================================================================================================
# The sample painting (in the map palette, as the plugin stores it) and the sheet
# ================================================================================================

# Minecraft's map base colours (MapColors.java has the same table) and the four shades
BASE = [0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8,
        0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19,
        0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919,
        0xFAEE4D, 0x5CDBD5, 0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
        0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A,
        0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464,
        0xD8AF93, 0x7FA796]
SHADE = [180, 220, 255, 135]


def map_rgb(base, shade):
    c, m = BASE[base], SHADE[shade]
    return ((c >> 16 & 255) * m // 255, (c >> 8 & 255) * m // 255, (c & 255) * m // 255)


def sample_painting():
    """A 128 x 128 picture painted the way a player would with the brush (sizes 1, 2, 4 and the fill): a sunset over
    the sea, a hill with a pine, a little boat. Every pixel a map colour."""
    im = Image.new("RGB", (128, 128), map_rgb(8, 2))
    d = ImageDraw.Draw(im)
    bands = [(17, 2), (17, 1), (20, 2), (20, 1), (15, 2), (15, 1), (18, 2)]      # sky: light blue -> pink -> orange -> yellow
    for i, (b, s) in enumerate(bands):
        d.rectangle((0, i * 9, 127, i * 9 + 8), fill=map_rgb(b, s))
    d.ellipse((50, 44, 78, 72), fill=map_rgb(30, 2))                               # the sun on the horizon
    d.rectangle((0, 63, 127, 127), fill=map_rgb(25, 1))                            # the sea
    for y in range(66, 127, 6):
        for x in range((y * 7) % 11, 128, 13):
            d.line((x, y, x + 5, y), fill=map_rgb(17, 2))                          # ripples
    for y in range(64, 90, 3):
        d.line((56 - (y - 64) // 3, y, 72 + (y - 64) // 3, y), fill=map_rgb(18, 2))   # the sun's path on the water
    d.polygon([(-5, 128), (-5, 70), (20, 58), (45, 70), (70, 128)], fill=map_rgb(27, 1))   # the hill
    d.polygon([(-5, 128), (-5, 84), (25, 76), (50, 128)], fill=map_rgb(27, 0))
    d.rectangle((21, 46, 23, 62), fill=map_rgb(26, 0))                             # the pine
    for k, w in enumerate((3, 6, 9, 12)):
        d.polygon([(22, 36 + k * 6), (22 - w, 46 + k * 5), (22 + w, 46 + k * 5)], fill=map_rgb(7, 0))
    d.polygon([(88, 94), (112, 94), (107, 100), (93, 100)], fill=map_rgb(28, 2))   # the boat
    d.line((100, 78, 100, 94), fill=map_rgb(29, 2))
    d.polygon([(101, 79), (101, 92), (110, 92)], fill=map_rgb(8, 2))
    d.text((106, 116), "R", fill=map_rgb(29, 2))                                   # the painter's initial
    return im


def _uri(im):
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def _placed(pv, dx):
    out = []
    for e in pv["elements"]:
        e = json.loads(json.dumps(e))
        e["from"] = [e["from"][0] + dx, e["from"][1], e["from"][2]]
        e["to"] = [e["to"][0] + dx, e["to"][1], e["to"][2]]
        if e.get("rotation"):
            axis, ang, o = e["rotation"]
            e["rotation"] = [axis, ang, [o[0] + dx, o[1], o[2]]]
        out.append(e)
    return out


def _font(size):
    return ImageFont.truetype(str(FONT), size)


def sheet(path=SHEET):
    from models import furniture_easel as E
    BUILD.mkdir(exist_ok=True)
    pv = {k: fn().to_preview() for k, fn in E.ALL.items()}
    art = sample_painting()
    tex = {"art": _uri(art)}
    floor = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            floor.putpixel((x, y), (110 + (x * 7 + y * 3) % 9, 84 + (x * 5 + y) % 7, 58, 255) if y % 4 else (92, 70, 48, 255))
    tex["floor"] = _uri(floor)
    for p in pv.values():
        tex.update(p["textures"])
    els, views, labels = [], [], []
    W, H = 1600, 760

    def view(x, y, w, h, centre, az, el, k, label, radius=22):
        views.append({"x": x, "y": y, "w": w, "h": h, "az": az, "el": el, "k": k, "center": centre, "radius": radius, "fov": 35})
        labels.append((x, y, label))

    def floor_at(dx):
        els.append({"from": [dx - 24, -1, -40], "to": [dx + 40, 0, 40], "shade": True, "rotation": None,
                    "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "floor"}}})

    # 1: the empty easel
    els.extend(_placed(pv["easel"], 0))
    floor_at(0)
    view(0, 0, 400, 520, [8, 16, 4], 30, 14, 3.3, "easel · 빈 이젤 (1×2칸 + 앞 칸)")
    # 2-4: the easel with the sample painting: the map lies against its front face (z 0), as the frame shows it
    x = 400
    for dx, az, el, label, w in ((200, 20, 10, "이젤 + 그림 (지도 128×128, 앞면 z 0)", 480), (400, 112, 12, "옆: 캔버스 판 · 압정", 360),
                                 (600, 205, 14, "뒤: 틀 · 뒷다리 · 경첩", 360)):
        els.extend(_placed(pv["easel_canvas"], dx))
        els.append({"from": [dx, 16, -0.05], "to": [dx + 16, 32, -0.03], "shade": False, "rotation": None,
                    "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "art"}}})
        floor_at(dx)
        view(x, 0, w, 520, [dx + 8, 16, 4], az, el, 3.3, label)
        x += w

    src = BUILD / "easel.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "size": [W, 520], "views": views}))
    shot = BUILD / "easel.render.png"
    subprocess.run(["node", str(RENDER), str(src), str(shot)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(shot).convert("RGBA")

    out = Image.new("RGBA", (W, H), (27, 29, 34, 255))
    out.alpha_composite(im, (0, 0))
    d = ImageDraw.Draw(out)
    f, small = _font(19), _font(16)
    for (lx, ly, label) in labels:
        tw = d.textlength(label, font=f)
        d.rectangle((lx + 8, ly + 8, lx + 20 + tw, ly + 38), fill=(20, 22, 26, 210))
        d.text((lx + 14, ly + 10), label, font=f, fill=(240, 236, 220))
    d.line((0, 520, W, 520), fill=(60, 64, 72), width=2)
    # the sample painting, real size and 1.5x
    d.text((20, 532), "그림 = 지도 한 장 (128×128, 지도 색)", font=f, fill=(240, 236, 220))
    out.alpha_composite(art.convert("RGBA").resize((192, 192), Image.NEAREST), (20, 562))
    out.alpha_composite(art.convert("RGBA"), (226, 562))
    # the icons, 8x and real size
    d.text((420, 532), "아이콘 (16×16)", font=f, fill=(240, 236, 220))
    names = [("easel_icon", "이젤"), ("blank_canvas", "빈 캔버스"), ("unfinished_canvas", "그리던 캔버스"), ("paint_brush", "붓"),
             ("painting_framed", "완성한 그림")]
    for i, (name, label) in enumerate(names):
        ic = ICONS[name]().convert("RGBA")
        x0 = 420 + i * 200
        out.alpha_composite(ic.resize((128, 128), Image.NEAREST), (x0, 566))
        out.alpha_composite(ic, (x0 + 140, 566))
        d.text((x0, 702), f"{label}", font=small, fill=(220, 220, 228))
        d.text((x0, 724), name, font=small, fill=(150, 154, 164))
    out.convert("RGB").save(path, optimize=True)
    return path


if __name__ == "__main__":
    print(", ".join(write()), "written")
    if "--no-sheet" not in sys.argv:
        print(sheet())
