"""
Preview sheet of the 특별 탈것 (content/stsv/items/vehicles_fun.yml, models/vehicles_fun.py; needs: cd render && npm
install, or a global playwright):

    python3 vehicle_preview_fun.py [out.png]                  # docs/previews/vehicles_fun.png
    python3 vehicle_preview_fun.py --one <id> <out.png>       # one vehicle large from four sides

Put together as the plugin shows them (vehicle_preview.py's helpers): the body at vehicle.body-scale, the wheels where
`wheels` puts them, the fun part (the balloons, the bull) where `fun.part` puts it at its scale, players in the seats
(orange the driver, green a passenger), a standing player and a block for scale; the inventory icons below.
"""

import sys
import types
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import vehicle_preview as VP              # noqa: E402
from models import vehicles as V          # noqa: E402
from models import vehicles_fun as F      # noqa: E402
import vehicle_icons_fun as VI            # noqa: E402
import yaml                               # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
ITEMS = REPO / "content/stsv/items/vehicles_fun.yml"
ORDER = ["kickboard", "pogo_stick", "skateboard", "broom", "banana_boat", "segway", "toy_duck", "sled", "balloon_chair", "rodeo_bull"]
# how high each is shown off the ground (blocks): the broom and the balloon chair as they float when ridden
LIFT = {"broom": 1.5, "balloon_chair": 3.0}


def items():
    return yaml.safe_load(ITEMS.read_text())


VP.items = items
VP.V = types.SimpleNamespace(ALL={**V.ALL, **F.ALL})


def vehicle(vid, ox, riders=True):
    """The whole vehicle (body, wheels, part, riders) at offset ox, lifted as it floats."""
    t, e, spec = VP.vehicle(vid, ox, riders=riders, steer=0)
    t = dict(t)
    part = spec.get("fun", {}).get("part")
    if part:
        data = F.ALL[part["model"]]().to_preview()
        t.update(data["textures"])
        k = part.get("scale", 1)
        at = part["at"]
        ax, ay, az = at[0] * 16 + ox[0], at[1] * 16, -at[2] * 16 + ox[2]
        e = e + VP._xf(data["elements"], lambda p: [(p[0] - 8) * k + ax, (p[1] - 8) * k + ay, (p[2] - 8) * k + az])
    lift = LIFT.get(vid, 0) * 16
    if lift:
        e = VP._xf(e, lambda p: [p[0], p[1] + lift, p[2]])
    return t, e, spec


def close_up(vid, out):
    t, e, spec = vehicle(vid, (0, 0, 0))
    t = {**t, "ref_rider": VP._solid((222, 152, 60)), "ref_passenger": VP._solid((120, 160, 120))}
    L, W, H = spec["size"]
    part = spec.get("fun", {}).get("part")
    H = max(H, 4.5 if part and "balloon" in part["model"] else H) + LIFT.get(vid, 0)
    r = max(L, 1.4 * H, 2.0) * 8
    c = [0, H * 7, 0]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": -90, "el": 4, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 30, "el": 40, "k": 5.2, "radius": r, "fov": 26, "center": c}]
    VP.render({"textures": t, "elements": e, "grid": False, "views": views}, "fun_one_" + vid).save(out)
    print(f"close-up {out}")


def bare(vid, out):
    """One vehicle without riders, four sides (to judge the model itself)."""
    t, e, spec = vehicle(vid, (0, 0, 0), riders=False)
    L, W, H = spec["size"]
    part = spec.get("fun", {}).get("part")
    H = max(H, 4.5 if part and "balloon" in part["model"] else H) + LIFT.get(vid, 0)
    r = max(L, 1.4 * H, 2.0) * 8
    c = [0, H * 7, 0]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": -90, "el": 4, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 30, "el": 40, "k": 5.2, "radius": r, "fov": 26, "center": c}]
    VP.render({"textures": t, "elements": e, "grid": False, "views": views}, "fun_bare_" + vid).save(out)
    print(f"bare {out}")


VIEWS = [("앞 왼쪽", -38, 20), ("뒤 오른쪽", 142, 24), ("옆 · 사람과 크기", -90, 4), ("위", -20, 60)]


def main(out):
    spec_all = items()
    ko = {k: v["name"].split(">")[-1] for k, v in spec_all.items()}
    player, block = VP._ref_models()
    textures = {**player["textures"], **block["textures"], "ref_rider": VP._solid((222, 152, 60)),
                "ref_passenger": VP._solid((120, 160, 120))}
    elements, views, labels = [], [], []
    cw, ch, head = 400, 300, 34
    for r, vid in enumerate(ORDER):
        ox = (r * 1400, 0, 0)
        t, e, spec = vehicle(vid, ox)
        textures.update(t)
        elements += e
        L, W, H = spec["size"]
        part = spec.get("fun", {}).get("part")
        Hs = max(H, 4.6 if part and "balloon" in part["model"] else H) + LIFT.get(vid, 0)
        elements += VP._xf(player["elements"], lambda p: [p[0] + ox[0] - W * 8 - 10, p[1], p[2] + ox[2] + L * 8 + 10])
        elements += VP._xf(block["elements"], lambda p: [p[0] + ox[0] + W * 8 + 4, p[1], p[2] - L * 8 - 22])
        radius = max(L, 1.3 * Hs, 2.6) * 8
        for c, (label, az, el) in enumerate(VIEWS):
            views.append({"x": c * cw, "y": r * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 5.6,
                          "radius": radius, "fov": 26, "far": 5000, "center": [ox[0], Hs * 7, 0]})
            labels.append((c * cw, r * (ch + head), cw, f"{ko[vid]} · {label}" if c == 0 else label, c == 0))
    data = {"textures": textures, "elements": elements, "grid": False, "size": [1600, len(ORDER) * ch], "views": views}
    im = VP.render(data, "fun_sheet")

    font = ImageFont.truetype(VP.FONT, 21)
    small = ImageFont.truetype(VP.FONT, 16)
    top, icon_h = 50, 170
    rows = len(ORDER)
    sheet = Image.new("RGB", (1600, top + rows * (ch + head) + icon_h), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "특별 탈것 (관리자 아이템) · 주황 = 운전자, 초록 = 동승자 · 블록 1칸과 사람(1.8칸) · 빗자루 1.5칸, 풍선 의자 3칸 떠 있는 모습",
           font=small, fill=(235, 232, 222))
    for r in range(rows):
        for c in range(4):
            x, y = c * cw, r * ch
            sheet.paste(im.crop((x, y, x + cw, y + ch)), (x, top + r * (ch + head) + head))
    for (x, y, w, text, big) in labels:
        d.rectangle((x, top + y, x + w - 2, top + y + head - 2), fill=(40, 43, 52))
        d.text((x + 8, top + y + 5), text, font=font if big else small, fill=(245, 242, 232))
    y0 = top + rows * (ch + head) + 10
    d.text((14, y0 - 4), "인벤토리 아이콘 (16px · 확대 / 실제 크기)", font=small, fill=(170, 170, 178))
    for k, vid in enumerate(ORDER):
        icon = VI.ICONS[vid]()
        x = 14 + k * 158
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        sheet.paste(bg.convert("RGB"), (x, y0 + 20))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        sheet.paste(slot.convert("RGB"), (x + 102, y0 + 20))
        d.text((x, y0 + 122), ko[vid], font=small, fill=(240, 240, 240))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--one":
        close_up(sys.argv[2], Path(sys.argv[3]))
    elif len(sys.argv) > 1 and sys.argv[1] == "--bare":
        bare(sys.argv[2], Path(sys.argv[3]))
    else:
        main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicles_fun.png")
