"""
The 서킷 karts (content/stsv/items/karts.yml, models/karts.py): a preview sheet and their inventory icons (needs: cd render
&& npm install, or a global playwright):

    python3 kart_preview.py                      # docs/previews/karts.png (four sides each, a player and a block for
                                                 #   scale) and docs/previews/kart_colours.png (every colour)
    python3 kart_preview.py --icons              # content/stsv/textures/kart_<id>_icon.png (32 px, rendered)
    python3 kart_preview.py --one <id> <out.png> # one kart large from four sides
"""

import sys
import types
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import vehicle_preview as VP              # noqa: E402
from models import karts as K             # noqa: E402
from models import vehicle_paints as VPaint  # noqa: E402
import yaml                               # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
ITEMS = REPO / "content/stsv/items/karts.yml"
RACE = REPO / "content/stsv/circuit/karts.yml"
ORDER = ["kart_storm", "kart_bubble", "kart_drifter", "kart_buffalo", "kart_tub"]


def items():
    return yaml.safe_load(ITEMS.read_text())


VP.items = items
VP.V = types.SimpleNamespace(ALL=dict(K.ALL))


def with_paint(vid, paint):
    """The kart's models with its body in a paint (as vehicle_paints draws it), for VP.vehicle."""
    if paint is None:
        VP.V.ALL[vid] = K.ALL[vid]
    else:
        made = VPaint.painted(vid, paint)
        VP.V.ALL[vid] = lambda: made[f"{vid}_p_{paint}"]


VIEWS = [("앞 왼쪽", -38, 18), ("뒤 오른쪽", 142, 22), ("옆 · 사람과 크기", -90, 4), ("위", -20, 62)]


def sheet(out):
    spec_all = items()
    race = yaml.safe_load(RACE.read_text())["karts"]
    by_item = {v["item"].split(":")[1]: v for v in race.values()}
    player, block = VP._ref_models()
    textures = {**player["textures"], **block["textures"], "ref_rider": VP._solid((222, 152, 60)), "ref_passenger": VP._solid((120, 160, 120))}
    elements, views, labels = [], [], []
    cw, ch, head = 400, 300, 34
    for r, vid in enumerate(ORDER):
        with_paint(vid, None)
        ox = (r * 1400, 0, 0)
        t, e, spec = VP.vehicle(vid, ox, steer=14)
        textures.update(t)
        elements += e
        L, W, H = spec["size"]
        elements += VP._xf(player["elements"], lambda p: [p[0] + ox[0] - W * 8 - 12, p[1], p[2] + ox[2] + L * 8 + 10])
        elements += VP._xf(block["elements"], lambda p: [p[0] + ox[0] + W * 8 + 6, p[1], p[2] - L * 8 - 20])
        radius = 2.6 * 8
        for c, (label, az, el) in enumerate(VIEWS):
            views.append({"x": c * cw, "y": r * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 5.6, "radius": radius, "fov": 26,
                          "far": 5000, "center": [ox[0], 9, 0]})
            k = by_item.get(vid, {})
            labels.append((c * cw, r * (ch + head), cw, f"{k.get('name', vid)} · {k.get('role', '')}" if c == 0 else label, c == 0))
    data = {"textures": textures, "elements": elements, "grid": False, "size": [1600, len(ORDER) * ch], "views": views}
    im = VP.render(data, "kart_sheet")
    font = ImageFont.truetype(VP.FONT, 21)
    small = ImageFont.truetype(VP.FONT, 16)
    top = 50
    out_im = Image.new("RGB", (1600, top + len(ORDER) * (ch + head)), (27, 29, 34))
    d = ImageDraw.Draw(out_im)
    d.text((14, 12), "서킷 카트 5종 · 주황 = 운전자 · 블록 1칸과 사람(1.8칸)", font=small, fill=(235, 232, 222))
    for r in range(len(ORDER)):
        for c in range(4):
            x, y = c * cw, r * ch
            out_im.paste(im.crop((x, y, x + cw, y + ch)), (x, top + r * (ch + head) + head))
    for (x, y, w, text, big) in labels:
        d.rectangle((x, top + y, x + w - 2, top + y + head - 2), fill=(40, 43, 52))
        d.text((x + 8, top + y + 5), text, font=font if big else small, fill=(245, 242, 232))
    out_im.save(out, optimize=True)
    print("sheet", out)


def colours(out):
    """Every kart in every colour it offers (circuit/karts.yml colours), seen from the front left."""
    race = yaml.safe_load(RACE.read_text())["karts"]
    rows = []
    for k in race.values():
        vid = k["item"].split(":")[1]
        rows.append((vid, k["own"], k["colours"]))
    cols = max(len(r[2]) for r in rows)
    cw, ch = 180, 140
    textures, elements, views = {}, [], []
    for r, (vid, own, cs) in enumerate(rows):
        for c, colour in enumerate(cs):
            with_paint(vid, None if colour == own else colour)
            ox = ((r * cols + c) * 300, 0, 0)
            t, e, _ = VP.vehicle(vid, ox, riders=False, steer=0)
            textures.update(t)
            elements += e
            views.append({"x": c * cw, "y": r * ch, "w": cw, "h": ch, "az": -40, "el": 22, "k": 5.6, "radius": 22, "fov": 26, "far": 5000,
                          "center": [ox[0], 7, 0]})
    data = {"textures": textures, "elements": elements, "grid": False, "size": [cols * cw, len(rows) * ch], "views": views}
    VP.render(data, "kart_colours").save(out, optimize=True)
    for vid, _, _ in rows:
        with_paint(vid, None)
    print("colours", out)


def icons():
    """32 px inventory icons: each kart (its own colour) rendered from the front left and scaled down."""
    out_dir = REPO / "content/stsv/textures"
    for vid in ORDER:
        with_paint(vid, None)
        t, e, _ = VP.vehicle(vid, (0, 0, 0), riders=False, steer=12)
        data = {"textures": t, "elements": e, "grid": False, "size": [256, 256], "transparent": True,
                "views": [{"x": 0, "y": 0, "w": 256, "h": 256, "az": -42, "el": 26, "k": 5.6, "radius": 21, "fov": 22, "far": 5000, "center": [0, 7, 0]}]}
        im = VP.render(data, "kart_icon_" + vid).convert("RGBA")
        # the renderer's background colour becomes transparent, then the picture shrinks to 32 px
        px = im.load()
        bg = px[0, 0]
        for y in range(im.height):
            for x in range(im.width):
                p = px[x, y]
                if abs(p[0] - bg[0]) + abs(p[1] - bg[1]) + abs(p[2] - bg[2]) < 6:
                    px[x, y] = (0, 0, 0, 0)
        box = im.getbbox()
        if box:
            w, h = box[2] - box[0], box[3] - box[1]
            s = max(w, h)
            sq = Image.new("RGBA", (s, s), (0, 0, 0, 0))
            sq.alpha_composite(im.crop(box), ((s - w) // 2, (s - h) // 2))
            im = sq
        small = im.resize((32, 32), Image.LANCZOS)
        a = small.getchannel("A").point(lambda v: 255 if v > 110 else 0)
        small.putalpha(a)
        small.save(out_dir / f"{vid}_icon.png")
        print("icon", vid)


if __name__ == "__main__":
    if "--icons" in sys.argv:
        icons()
    elif len(sys.argv) > 1 and sys.argv[1] == "--one":
        vid = sys.argv[2]
        with_paint(vid, None)
        t, e, spec = VP.vehicle(vid, (0, 0, 0), steer=14)
        views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 5.2, "radius": 20, "fov": 26, "center": [0, 8, 0]},
                 {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 5.2, "radius": 20, "fov": 26, "center": [0, 8, 0]},
                 {"x": 0, "y": 500, "w": 800, "h": 500, "az": -90, "el": 4, "k": 5.2, "radius": 20, "fov": 26, "center": [0, 8, 0]},
                 {"x": 800, "y": 500, "w": 800, "h": 500, "az": 30, "el": 50, "k": 5.2, "radius": 20, "fov": 26, "center": [0, 8, 0]}]
        t = {**t, "ref_rider": VP._solid((222, 152, 60)), "ref_passenger": VP._solid((120, 160, 120))}
        VP.render({"textures": t, "elements": e, "grid": False, "views": views}, "kart_one_" + vid).save(sys.argv[3])
        print("one", sys.argv[3])
    else:
        sheet(PREVIEWS / "karts.png")
        if RACE.exists():
            colours(PREVIEWS / "kart_colours.png")
