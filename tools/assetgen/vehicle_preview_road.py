"""
Preview sheet of the road vehicles of the transport ladder (content/stsv/items/vehicles_road.yml, models/vehicles_road.py;
needs: cd render && npm install, or a global playwright):

    python3 vehicle_preview_road.py [out.png]                    # docs/previews/vehicles_road.png
    python3 vehicle_preview_road.py --one <id> <out.png> [stage]  # one vehicle large from four sides

Put together as the plugin shows them (vehicle_preview.py does the same for vehicles.yml, and its helpers do the work
here): the body at vehicle.body-scale, the wheels (front ones turned 22.5°), the spinning parts (the bicycle's crank)
where `spinners` puts them, the load where `cargo` puts it, players in the seats (orange the driver, green a
passenger), a standing player and a block for scale. Then the open loads stage by stage (the bicycle's basket and
rack, the ATV's racks; the scooter, van and truck carry theirs in closed boxes) and the inventory icons.
"""

import sys
import types
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import vehicle_preview as VP              # noqa: E402
from models import vehicles as V          # noqa: E402
from models import vehicles_road as R     # noqa: E402
import vehicle_icons_road as VI           # noqa: E402
import yaml                               # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
ITEMS = REPO / "content/stsv/items/vehicles_road.yml"
ORDER = ["cargo_bicycle", "delivery_scooter", "atv", "cargo_van", "box_truck"]
GEN = {"cargo_bicycle": "2세대", "delivery_scooter": "3세대", "atv": "특수", "cargo_van": "4세대", "box_truck": "5세대"}


def items():
    return yaml.safe_load(ITEMS.read_text())


# vehicle_preview's helpers read their vehicles from these: point them at the road vehicles
VP.items = items
VP.V = types.SimpleNamespace(ALL={**V.ALL, **R.ALL})


def vehicle(vid, ox, cargo=None, riders=True, steer=22.5):
    t, e, spec = VP.vehicle(vid, ox, cargo=cargo, riders=riders, steer=steer)
    t = dict(t)
    for sp in spec.get("spinners", []):
        pt, pe = VP.part(sp["model"], sp["at"], ox)
        t.update(pt)
        e = e + pe
    return t, e, spec


def close_up(vid, out, cargo=0):
    t, e, spec = vehicle(vid, (0, 0, 0), cargo=cargo)
    t = {**t, "ref_rider": VP._solid((222, 152, 60)), "ref_passenger": VP._solid((120, 160, 120))}
    L, W, H = spec["size"]
    r = max(L, 1.5 * H) * 8
    c = [0, H * 7, 0]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 3.6, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 3.6, "radius": r, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": -90, "el": 4, "k": 3.6, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 30, "el": 40, "k": 3.6, "radius": r, "fov": 26, "center": c}]
    VP.render({"textures": t, "elements": e, "grid": False, "views": views}, "road_one_" + vid).save(out)
    print(f"close-up {out}")


VIEWS = [("앞 왼쪽", -38, 20, 1.45), ("뒤 오른쪽", 142, 24, 1.45), ("옆 · 사람과 크기 비교", -90, 4, 1.5), ("위", -20, 72, 1.55)]


def main(out):
    spec_all = items()
    ko = {k: v["name"].split(">")[-1] for k, v in spec_all.items()}
    player, block = VP._ref_models()
    textures = {**player["textures"], **block["textures"], "ref_rider": VP._solid((222, 152, 60)),
                "ref_passenger": VP._solid((120, 160, 120))}
    elements, views, labels = [], [], []
    cw, ch, head = 400, 300, 34
    fov = 26
    for r, vid in enumerate(ORDER):
        ox = (r * 1400, 0, 0)
        t, e, spec = vehicle(vid, ox)
        textures.update(t)
        elements += e
        L, W, H = spec["size"]
        elements += VP._xf(player["elements"], lambda p: [p[0] + ox[0] - W * 8 - 6, p[1], p[2] + ox[2] + L * 8 + 10])
        elements += VP._xf(block["elements"], lambda p: [p[0] + ox[0] + W * 8 - 2, p[1], p[2] - L * 8 - 22])
        radius = max(L, 1.5 * H, 3.2) * 8   # the small ones with their rider and the people for scale in view
        for c, (label, az, el, kk) in enumerate(VIEWS):
            views.append({"x": c * cw, "y": r * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 2.9 * kk,
                          "radius": radius, "fov": fov, "far": 5000, "center": [ox[0] - 4, H * 7, 0]})
            text = f"{ko[vid]} ({GEN[vid]}) · {label}" if c == 0 else label
            labels.append((c * cw, r * (ch + head), cw, text, c == 0))
    # the open loads by stage: the bicycle's basket and rack, the ATV's racks
    row = len(ORDER)
    sw = 1600 // 8
    k = 0
    for vid, name in (("cargo_bicycle", "짐자전거"), ("atv", "사륜")):
        for stage in range(1, 5):
            ox = (9000 + k * 1000, 0, 0)
            t, e, spec = vehicle(vid, ox, cargo=stage, riders=False, steer=0)
            textures.update(t)
            elements += e
            views.append({"x": k * sw, "y": row * ch, "w": sw, "h": ch, "az": 150, "el": 32, "k": 3.4 if vid == "atv" else 3.0,
                          "radius": 36, "fov": fov, "far": 4000, "center": [ox[0], 12, 0]})
            slots = spec["cargo"]["slots"]
            labels.append((k * sw, row * (ch + head), sw, f"{name} 짐 {['~25%', '~50%', '~75%', f'가득 ({slots}칸)'][stage - 1]}", False))
            k += 1
    data = {"textures": textures, "elements": elements, "grid": False, "size": [1600, (row + 1) * ch], "views": views}
    im = VP.render(data, "road_sheet")

    font = ImageFont.truetype(VP.FONT, 21)
    small = ImageFont.truetype(VP.FONT, 16)
    top = 50
    icon_h = 150
    sheet = Image.new("RGB", (1600, top + (row + 1) * (ch + head) + icon_h), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "탈것 세대 사다리 · 도로 탈것 (관리자 아이템) · 앞바퀴 조향 22.5° · 주황 = 운전자, 초록 = 동승자 · "
           "블록 1칸과 사람(1.8칸) · 짐자전거 페달은 바퀴와 함께 돎", font=small, fill=(235, 232, 222))
    for r in range(row + 1):
        w = cw if r < row else sw
        for c in range(1600 // w):
            x, y = c * w, r * ch
            sheet.paste(im.crop((x, y, x + w, y + ch)), (x, top + r * (ch + head) + head))
    for (x, y, w, text, big) in labels:
        d.rectangle((x, top + y, x + w - 2, top + y + head - 2), fill=(40, 43, 52))
        d.text((x + 8, top + y + 5), text, font=font if big else small, fill=(245, 242, 232))
    y0 = top + (row + 1) * (ch + head) + 10
    d.text((14, y0 - 4), "인벤토리 아이콘 (16px · 실제 크기)", font=small, fill=(170, 170, 178))
    for k, vid in enumerate(ORDER):
        icon = VI.ICONS[vid]()
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        sheet.paste(bg.convert("RGB"), (14 + k * 316, y0 + 20))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        sheet.paste(slot.convert("RGB"), (120 + k * 316, y0 + 80))
        d.text((120 + k * 316, y0 + 24), ko[vid], font=small, fill=(240, 240, 240))
        d.text((120 + k * 316, y0 + 46), GEN[vid], font=small, fill=(190, 190, 196))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--one":
        close_up(sys.argv[2], Path(sys.argv[3]), cargo=int(sys.argv[4]) if len(sys.argv) > 4 else 0)
    else:
        main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicles_road.png")
