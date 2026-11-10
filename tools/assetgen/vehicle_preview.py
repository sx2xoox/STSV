"""
Preview sheet of the vehicles (needs: cd render && npm install, or a global playwright):

    python3 vehicle_preview.py [out.png]       # docs/previews/vehicles.png

Every vehicle is put together as the plugin shows it, from content/stsv/items/vehicles.yml: the body (drawn at
half size, shown at vehicle.body-scale), its wheels where `wheels` puts them (the front ones turned a little,
as when steering), the light bar / the truck's load where `lights` / `cargo` put them, and players sitting in
the seats (`seats`, a sitting player's head 1.275 blocks above the seat: do the roofs clear it?). A standing
player (1.8 blocks) and a block stand next to each for scale. Then the truck with each load stage (empty, 25,
50, 75, 100 %), the light bar's three looks and the inventory icons.
"""

import copy
import json
import math
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from models import vehicles as V          # noqa: E402
import vehicle_icons as VI                # noqa: E402
import yaml                               # noqa: E402
from furniture_preview import _ref_models  # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
ITEMS = REPO / "content/stsv/items/vehicles.yml"
ORDER = ["police_car", "light_truck", "golf_cart"]


def items():
    return yaml.safe_load(ITEMS.read_text())


def _xf(els, fn, rot=None):
    """Elements with every point moved by fn (a uniform scale and a shift, so boxes stay boxes); rot: an extra
    rotation [axis, angle, origin] for elements that have none (a steered wheel)."""
    out = []
    for e in els:
        e = copy.deepcopy(e)
        a, b = fn(e["from"]), fn(e["to"])
        e["from"], e["to"] = [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]
        if e.get("rotation"):
            e["rotation"][2] = fn(e["rotation"][2])
        elif rot:
            e["rotation"] = copy.deepcopy(rot)
        out.append(e)
    return out


def part(name, at, ox, rot=None):
    """A part model (attachment point at model (8, 8, 8)) at a vehicle position [right, up, forward] (blocks)."""
    data = V.ALL[name]().to_preview()
    ax, ay, az = at[0] * 16 + ox[0], at[1] * 16, -at[2] * 16 + ox[2]
    fn = lambda p: [p[0] - 8 + ax, p[1] - 8 + ay, p[2] - 8 + az]
    r = None
    if rot:
        r = [rot[0], rot[1], [ax, ay, az]]
    return data["textures"], _xf(data["elements"], fn, r)


def sitting(at, ox, mat):
    """A seated player (the riding pose: legs forward) on a seat at [right, up, forward]."""
    sx, sy, sz = at[0] * 16 + ox[0], at[1] * 16, -at[2] * 16 + ox[2]
    s = 0.9375
    hip = sy - 9.6 + 12 * s
    els = []

    def box(frm, to):
        els.append({"from": list(frm), "to": list(to), "glow": False, "shade": True, "rotation": None,
                    "faces": {f: {"uv": [0, 0, 16, 16], "texture": mat} for f in ("north", "south", "east", "west", "up", "down")}})
    for x0 in (-4, 0):
        box((sx + x0 * s, hip - 4 * s, sz - 10 * s), (sx + (x0 + 4) * s, hip, sz + 2 * s))
    box((sx - 4 * s, hip, sz - 2 * s), (sx + 4 * s, hip + 12 * s, sz + 2 * s))
    for x0 in (-8, 4):
        box((sx + x0 * s, hip, sz - 2 * s), (sx + (x0 + 4) * s, hip + 12 * s, sz + 2 * s))
    box((sx - 4 * s, hip + 12 * s, sz - 4 * s), (sx + 4 * s, hip + 20 * s, sz + 4 * s))
    return els


def vehicle(vid, ox, *, lights="a", cargo=None, riders=True, steer=22.5):
    """Textures and elements of a whole vehicle at offset ox (drawn px)."""
    spec = items()[vid]["vehicle"]
    body = V.ALL[vid]().to_preview()
    k = spec.get("body-scale", 1)
    textures = dict(body["textures"])
    elements = _xf(body["elements"], lambda p: [(p[0] - 8) * k + ox[0], (p[1] - 8) * k, (p[2] - 8) * k + ox[2]])
    for w in spec["wheels"]:
        t, e = part(spec["wheel"], w["at"], ox, ("y", steer, None) if w.get("steer") and steer else None)
        textures.update(t)
        elements += e
    if "lights" in spec and lights:
        name = spec["lights"]["dark"] if lights == "off" else spec["lights"]["flash"][0 if lights == "a" else 1]
        t, e = part(name, spec["lights"]["at"], ox)
        textures.update(t)
        elements += e
    if "cargo" in spec and cargo:
        t, e = part(spec["cargo"]["stages"][cargo - 1], spec["cargo"]["at"], ox)
        textures.update(t)
        elements += e
    if riders:
        for i, seat in enumerate(spec["seats"]):
            elements += sitting(seat["at"], ox, "ref_rider" if i == 0 else "ref_passenger")
    return textures, elements, spec


def _solid(rgb):
    import base64
    import io
    im = Image.new("RGBA", (16, 16), (*rgb, 255))
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def render(data, tag):
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / f"veh_{tag}.json", BUILD / f"veh_{tag}.png"
    src.write_text(json.dumps(data))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, capture_output=True)
    return Image.open(png).convert("RGB")


VIEWS = [("앞 왼쪽 (운전석 쪽)", -38, 20, 1.45), ("뒤 오른쪽", 142, 24, 1.45), ("옆 · 사람과 크기 비교", -90, 4, 1.5),
         ("위", -20, 72, 1.55)]


def main(out):
    ko = {k: v["name"].split(">")[-1] for k, v in items().items()}
    player, block = _ref_models()
    textures = {**player["textures"], **block["textures"], "ref_rider": _solid((222, 152, 60)),
                "ref_passenger": _solid((120, 160, 120))}
    elements, views, labels = [], [], []
    cw, ch, head = 400, 300, 34
    fov = 26
    for r, vid in enumerate(ORDER):
        ox = (r * 1200, 0, 0)
        t, e, spec = vehicle(vid, ox)
        textures.update(t)
        elements += e
        L, W, H = spec["size"]
        # a standing player beside the driver's door, a block behind the vehicle
        # a standing player behind the vehicle's left rear corner, a block in front of its right front corner
        elements += _xf(player["elements"], lambda p: [p[0] + ox[0] - W * 8 + 4, p[1], p[2] + ox[2] + L * 8 + 10])
        elements += _xf(block["elements"], lambda p: [p[0] + ox[0] + W * 8 - 12, p[1], p[2] - L * 8 - 22])
        radius = max(L, 1.5 * H) * 8
        for c, (label, az, el, kk) in enumerate(VIEWS):
            views.append({"x": c * cw, "y": r * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 2.9 * kk,
                          "radius": radius, "fov": fov, "far": 4000, "center": [ox[0] - 4, H * 7, 0]})
            labels.append((c * cw, r * (ch + head), f"{ko[vid]} · {label}" if c == 0 else label))
    # the truck with each load stage, and the light bar's looks
    row = len(ORDER)
    sw = 1600 // 5
    for k in range(5):
        ox = (5000 + k * 1200, 0, 0)
        t, e, spec = vehicle("light_truck", ox, cargo=k, riders=False, steer=0)
        textures.update(t)
        elements += e
        views.append({"x": k * sw, "y": row * ch, "w": sw, "h": ch, "az": 150, "el": 30, "k": 2.6, "radius": 46,
                      "fov": fov, "far": 4000, "center": [ox[0], 14, 0]})
        labels.append((k * sw, row * (ch + head), ["짐칸 비었음", "짐 ~25%", "짐 ~50%", "짐 ~75%", "짐 가득"][k]))
    data = {"textures": textures, "elements": elements, "grid": False, "size": [1600, (row + 1) * ch], "views": views}
    im = render(data, "sheet")

    lb_textures, lb_elements, lb_views = {}, [], []
    for k, look in enumerate(("off", "a", "b")):
        lights = items()["police_car"]["vehicle"]["lights"]
        name = lights["dark"] if look == "off" else lights["flash"][0 if look == "a" else 1]
        t, e = part(name, [0, 0, 0], (k * 60, 0, 0))
        lb_textures.update(t)
        lb_elements += e
        lb_views.append({"x": k * 200, "y": 0, "w": 200, "h": 120, "az": 20, "el": 25, "k": 2.4, "radius": 12, "fov": fov,
                         "far": 4000, "center": [k * 60, 2, 0]})
    lb = render({"textures": lb_textures, "elements": lb_elements, "grid": False, "size": [600, 120], "views": lb_views}, "lightbar")

    font = ImageFont.truetype(FONT, 21)
    small = ImageFont.truetype(FONT, 16)
    top = 50
    icon_h = 150
    sheet = Image.new("RGB", (1600, top + (row + 1) * (ch + head) + icon_h), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "탈것 · 관리자 아이템 · 바퀴는 따로 돌고 앞바퀴는 조향 (여기서는 22.5°) · 주황 = 운전자, 초록 = 동승자 · 블록 1칸과 사람(1.8칸)",
           font=small, fill=(235, 232, 222))
    for r in range(row + 1):
        for c in range(4 if r < row else 5):
            w = cw if r < row else sw
            x, y = c * w, r * ch
            oy = top + r * (ch + head)
            sheet.paste(im.crop((x, y, x + w, y + ch)), (x, oy + head))
    for (x, y, text) in labels:
        d.rectangle((x, top + y, x + (cw if y < row * (ch + head) else sw) - 2, top + y + head - 2), fill=(40, 43, 52))
        d.text((x + 8, top + y + 5), text, font=font if "·" in text and y < row * (ch + head) else small, fill=(245, 242, 232))
    y0 = top + (row + 1) * (ch + head) + 10
    for k, vid in enumerate(ORDER):
        icon = VI.ICONS[vid]()
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        sheet.paste(bg.convert("RGB"), (14 + k * 250, y0 + 20))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        sheet.paste(slot.convert("RGB"), (120 + k * 250, y0 + 80))
        d.text((120 + k * 250, y0 + 24), ko[vid], font=small, fill=(240, 240, 240))
    d.text((14, y0 - 4), "인벤토리 아이콘 (16px · 실제 크기)", font=small, fill=(170, 170, 178))
    sheet.paste(lb, (980, y0 + 14))
    d.text((980, y0 - 4), "경광등: 꺼짐 · 빨강 · 파랑 (번갈아)", font=small, fill=(170, 170, 178))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicles.png")
