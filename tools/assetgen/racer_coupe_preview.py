"""
Preview sheet of 미완의 질주 (racer_coupe, models/vehicles_racer_coupe.py; needs: cd render && npm install, or a global
playwright):

    python3 racer_coupe_preview.py [out.png]       # docs/previews/racer_coupe.png

The car put together as the plugin shows it (content/stsv/items/vehicles.yml: the body at body-scale, its doors shut,
a wheel at each of `wheels`, the front ones turned a little), on a strip of asphalt. Top row: the stock car (primer
grey, black steel wheels) from the front left, the left side and the rear right. Bottom row: two looks of the tuning
shop as the hidden secret dresses it (a paint, a wheel design and the glow under it), and the stock car from above
(the cabin through the glass: seats, cage, the charm, the photograph, the toolbox). The glass is drawn see-through
here as in the game. The inventory icon in a corner.
"""

import copy
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import yaml                                         # noqa: E402
from models import vehicles_racer_coupe as RC       # noqa: E402
from models import vehicle_paints as VP             # noqa: E402
import vehicle_icons_racer_coupe as ICON            # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
SEE_THROUGH = ("screen", "rear_glass", "side_glass", "quarter_glass", "glow")
GLOW_LIFT, GLOW_MARGIN = 0.03, 0.9                 # VehicleTuner


def spec():
    return yaml.safe_load((REPO / "content/stsv/items/vehicles.yml").read_text())["racer_coupe"]["vehicle"]


def _solid(rgb):
    import base64
    import io
    im = Image.new("RGBA", (16, 16), (*rgb, 255))
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def _placed(model, fn, rot=None):
    """A model's preview elements moved by fn; its glass (and a glow) marked see-through for the renderer."""
    data = model.to_preview()
    out = []
    for src, e in zip(model.elements, data["elements"]):
        e = copy.deepcopy(e)
        a, b = fn(e["from"]), fn(e["to"])
        e["from"], e["to"] = [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]
        if e.get("rotation"):
            e["rotation"][2] = fn(e["rotation"][2])
        elif rot:
            e["rotation"] = [rot[0], rot[1], fn([8, 8, 8])]
        if src["name"].split("#")[0] in SEE_THROUGH:
            e["opacity"] = 0.999
        out.append(e)
    return data["textures"], out


def car(ox, paint=None, wheel=None, glow=None, steer=18.0):
    """(textures, elements) of the car standing at drawn x = ox in a look (None: stock)."""
    sp = spec()
    k = sp["body-scale"]
    kit = VP.painted("racer_coupe", paint) if paint else RC.coupe_kit().all()
    textures, elements = {}, []
    for m in kit.values():
        t, e = _placed(m, lambda p: [(p[0] - 8) * k + ox, (p[1] - 8) * k, (p[2] - 8) * k])
        textures.update(t)
        elements += e
    wm = VP.wheel("wheel_racer", wheel) if wheel else RC.wheel_racer()
    for w in sp["wheels"]:
        ax, ay, az = w["at"][0] * 16 + ox, w["at"][1] * 16, -w["at"][2] * 16
        t, e = _placed(wm, lambda p: [p[0] - 8 + ax, p[1] - 8 + ay, p[2] - 8 + az], ("y", steer) if w.get("steer") and steer else None)
        textures.update(t)
        elements += e
    if glow:
        L, W, _ = sp["size"]
        t, e = _placed(VP.underglow(glow), lambda p: [(p[0] - 8) * (W + GLOW_MARGIN) + ox, GLOW_LIFT * 16 + (p[1] - 8), (p[2] - 8) * (L + GLOW_MARGIN)])
        textures.update(t)
        elements += e
    # a strip of asphalt under it
    elements.append({"from": [ox - 60, -1, -70], "to": [ox + 60, 0, 70], "glow": False, "shade": True, "rotation": None,
                     "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "asphalt"}}})
    textures["asphalt"] = _solid((44, 46, 52))
    return textures, elements


def render(data, tag):
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / f"{tag}.json", BUILD / f"{tag}.png"
    src.write_text(json.dumps(data))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, capture_output=True)
    return Image.open(png).convert("RGB")


CELLS = [
    # label, look (paint, wheel, glow), az, el, zoom
    ("순정 (프라이머 회색 · 철 휠) · 앞 왼쪽 3/4", (None, None, None), -38, 16, 1.0),
    ("옆 (운전석 쪽) · 문의 07", (None, None, None), -90, 4, 1.0),
    ("뒤 오른쪽 3/4 · 윙 · 머플러", (None, None, None), 142, 18, 1.0),
    ("와인 · 골드 6스포크 · 시안 언더글로우", ("wine", "gold", "cyan"), -42, 14, 1.0),
    ("퍼플 · 랠리 화이트 · 핑크 언더글로우", ("purple", "white", "pink"), 136, 16, 1.0),
    ("위에서 · 실내 (버킷 시트 · 롤케이지 · 부적 · 사진 · 공구함)", (None, None, None), -90, 62, 0.9),
]


def main(out):
    cw, ch, head, top = 800, 520, 34, 44
    textures, elements, views, cars = {}, [], [], {}
    for i, (label, look, az, el, zoom) in enumerate(CELLS):
        if look not in cars:
            cars[look] = len(cars) * 1200
            t, e = car(cars[look], *look)
            textures.update(t)
            elements += e
        views.append({"x": (i % 3) * cw, "y": (i // 3) * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 3.45 * zoom, "radius": 36,
                      "fov": 26, "far": 4000, "center": [cars[look], 10, 1]})
    im = render({"textures": textures, "elements": elements, "grid": False, "size": [3 * cw, 2 * ch], "views": views}, "racer_coupe_sheet")
    font, small = ImageFont.truetype(FONT, 21), ImageFont.truetype(FONT, 16)
    sheet = Image.new("RGB", (3 * cw, top + 2 * (ch + head)), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 10), "미완의 질주 (stsv:racer_coupe) · 숨은 비밀 '폐차고의 주인' 의 차 · 한도윤의 차. 끝내지 못한 길을 같이 달려 준 사람에게.",
           font=small, fill=(235, 232, 222))
    for i, (label, *_rest) in enumerate(CELLS):
        x, y = (i % 3) * cw, (i // 3) * ch
        oy = top + (i // 3) * (ch + head)
        d.rectangle((x, oy, x + cw - 2, oy + head - 2), fill=(40, 43, 52))
        d.text((x + 8, oy + 5), label, font=font, fill=(245, 242, 232))
        sheet.paste(im.crop((x, y, x + cw, y + ch)), (x, oy + head))
    icon = ICON.ICONS["racer_coupe"]()
    bg = Image.new("RGBA", (72, 72), (139, 139, 139, 255))
    bg.alpha_composite(icon.resize((64, 64), Image.NEAREST), (4, 4))
    sheet.paste(bg.convert("RGB"), (3 * cw - 86, top + head + 12))
    slot = Image.new("RGBA", (20, 20), (139, 139, 139, 255))
    slot.alpha_composite(icon, (2, 2))
    sheet.paste(slot.convert("RGB"), (3 * cw - 112, top + head + 64))
    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "racer_coupe.png")
