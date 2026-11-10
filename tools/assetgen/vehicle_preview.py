"""
Preview sheet of the vehicles (needs: cd render && npm install, or a global playwright):

    python3 vehicle_preview.py [out.png]       # docs/previews/vehicles.png

Every vehicle is put together as the plugin shows it, from content/stsv/items/vehicles.yml: the body (drawn at
half size, shown at vehicle.body-scale), its wheels where `wheels` puts them (the front ones turned a little,
as when steering), the light bar / the truck's load where `lights` / `cargo` put them, and players sitting in
the seats (`seats`, a sitting player's head 1.275 blocks above the seat: do the roofs clear it?). A standing
player (1.8 blocks) and a block stand next to each for scale. Then the truck with each load stage (empty, 25,
50, 75, 100 %); the hand cart being pulled (a player between its handles, the bed level) and resting on its handles
with each load stage; the light bar's three looks and the inventory icons. The hand cart's four views show it left
alone (tipped forward onto its handles: vehicles.yml pull.rest-tilt).
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
ORDER = ["police_car", "light_truck", "golf_cart", "hand_cart"]


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


def part(name, at, ox, rot=None, pivot=None):
    """A part model (attachment point at model (8, 8, 8)) at a vehicle position [right, up, forward] (blocks); rot
    [axis, angle] about its own attachment point, or about `pivot` (drawn px) when given."""
    data = V.ALL[name]().to_preview()
    ax, ay, az = at[0] * 16 + ox[0], at[1] * 16, -at[2] * 16 + ox[2]
    fn = lambda p: [p[0] - 8 + ax, p[1] - 8 + ay, p[2] - 8 + az]
    r = None
    if rot:
        r = [rot[0], rot[1], list(pivot) if pivot else [ax, ay, az]]
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


def pulling(spec, ox, mat="ref_rider"):
    """A player pulling a hand cart: standing between its handles (pull.hitch ahead of the axle), facing the way it
    goes, arms reaching forward and down to the cross bar."""
    s = 0.9375
    sx, sz = ox[0], ox[2] - spec["pull"]["hitch"] * 16
    els = []

    def box(frm, to, rot=None):
        els.append({"from": list(frm), "to": list(to), "glow": False, "shade": True, "rotation": rot,
                    "faces": {f: {"uv": [0, 0, 16, 16], "texture": mat} for f in ("north", "south", "east", "west", "up", "down")}})
    for x0 in (-4, 0):
        box((sx + x0 * s, 0, sz - 2 * s), (sx + (x0 + 4) * s, 12 * s, sz + 2 * s))          # legs
    box((sx - 4 * s, 12 * s, sz - 2 * s), (sx + 4 * s, 24 * s, sz + 2 * s))                  # body
    box((sx - 4 * s, 24 * s, sz - 4 * s), (sx + 4 * s, 32 * s, sz + 4 * s))                  # head
    for x0 in (-8, 4):
        # arms from the shoulder, swung 27.5° forward (towards -z): the hands on the bar
        box((sx + x0 * s, 12 * s, sz - 2 * s), (sx + (x0 + 4) * s, 24 * s, sz + 2 * s), ["x", 27.5, [sx, 22 * s, sz]])
    return els


def vehicle(vid, ox, *, lights="a", cargo=None, riders=True, steer=22.5, pose="rest"):
    """Textures and elements of a whole vehicle at offset ox (drawn px). A hand cart: pose "rest" = tipped onto its
    handles (pull.rest-tilt), "pulled" = level with a player between its handles."""
    spec = items()[vid]["vehicle"]
    body = V.ALL[vid]().to_preview()
    k = spec.get("body-scale", 1)
    textures = dict(body["textures"])
    pull = spec.get("pull")
    tilt, pivot = 0, None
    if pull and pose == "rest" and pull.get("rest-tilt"):
        # about the axle: the wheels' middle at their hub height (three.js turns +x so the front, -z, goes down)
        fwd = sum(w["at"][2] for w in spec["wheels"]) / len(spec["wheels"])
        tilt, pivot = pull["rest-tilt"], [ox[0], spec["wheel-radius"] * 16, ox[2] - fwd * 16]
    rot = ["x", -tilt, pivot] if tilt else None
    elements = _xf(body["elements"], lambda p: [(p[0] - 8) * k + ox[0], (p[1] - 8) * k, (p[2] - 8) * k + ox[2]], rot)
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
        t, e = part(spec["cargo"]["stages"][cargo - 1], spec["cargo"]["at"], ox, rot[:2] if rot else None, pivot)
        textures.update(t)
        elements += e
    if pull and pose == "pulled" and riders:
        elements += pulling(spec, ox)
    if riders:
        for i, seat in enumerate(spec.get("seats", [])):
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
        cz = 0
        if "pull" in spec:
            # the handles reach out in front: the picture's middle is between their end and the bed's back
            front = spec["pull"]["hitch"] + 0.35
            cz, L = -(front - L / 2) * 8, front + L / 2
        # a standing player behind the vehicle's left rear corner, a block in front of its right front corner
        elements += _xf(player["elements"], lambda p: [p[0] + ox[0] - W * 8 + 4, p[1], p[2] + ox[2] + L * 8 + 10 + cz])
        elements += _xf(block["elements"], lambda p: [p[0] + ox[0] + W * 8 - 12, p[1], p[2] - L * 8 - 22 + cz])
        radius = max(L, 1.5 * H) * 8
        for c, (label, az, el, kk) in enumerate(VIEWS):
            views.append({"x": c * cw, "y": r * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 2.9 * kk,
                          "radius": radius, "fov": fov, "far": 4000, "center": [ox[0] - 4, H * 7, cz]})
            if c == 0 and "pull" in spec:
                label = "앞 왼쪽 (손잡이 쪽) · 놓아 둔 모습"
            labels.append((c * cw, r * (ch + head), cw, f"{ko[vid]} · {label}" if c == 0 else label, True))
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
        labels.append((k * sw, row * (ch + head), sw, ["트럭 짐칸 비었음", "짐 ~25%", "짐 ~50%", "짐 ~75%", "짐 가득"][k], False))
    # the hand cart: pulled (a player between its handles, level) from the side and from the front, then each load
    # stage resting on its handles (as it is loaded)
    cart = items()["hand_cart"]["vehicle"]
    cw6 = 1600 // 6
    cart_cells = [("끄는 모습 · 옆 (걸음 30% 느리게)", "pulled", 0, -90, 6, 3.7), ("끄는 모습 · 앞", "pulled", 0, -35, 20, 3.5)]
    cart_cells += [(f"리어카 짐 {n}" if i == 0 else f"짐 {n}", "rest", i + 1, 150, 32, 2.6)
                   for i, n in enumerate(["~25%", "~50%", "~75%", "가득 (270칸)"])]
    for k, (label, pose, stage, az, el, kk) in enumerate(cart_cells):
        ox = (12000 + k * 1200, 0, 0)
        t, e, spec = vehicle("hand_cart", ox, cargo=stage or None, pose=pose)
        textures.update(t)
        elements += e
        cz = -9 if pose == "pulled" else -6
        views.append({"x": k * cw6, "y": (row + 1) * ch, "w": cw6, "h": ch, "az": az, "el": el, "k": kk, "radius": 44,
                      "fov": fov, "far": 4000, "center": [ox[0], 14, cz]})
        labels.append((k * cw6, (row + 1) * (ch + head), cw6, label, False))
    data = {"textures": textures, "elements": elements, "grid": False, "size": [1600, (row + 2) * ch], "views": views}
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
    sheet = Image.new("RGB", (1600, top + (row + 2) * (ch + head) + icon_h), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "탈것 · 관리자 아이템 · 바퀴는 따로 돌고 앞바퀴는 조향 (여기서는 22.5°) · 주황 = 운전자·끄는 사람, 초록 = 동승자 · "
           "블록 1칸과 사람(1.8칸) · 리어카는 타지 않고 끔",
           font=small, fill=(235, 232, 222))
    for r in range(row + 2):
        w = cw if r < row else sw if r == row else cw6
        for c in range(1600 // w):
            x, y = c * w, r * ch
            oy = top + r * (ch + head)
            sheet.paste(im.crop((x, y, x + w, y + ch)), (x, oy + head))
    for (x, y, w, text, big) in labels:
        d.rectangle((x, top + y, x + w - 2, top + y + head - 2), fill=(40, 43, 52))
        d.text((x + 8, top + y + 5), text, font=font if big and "·" in text else small, fill=(245, 242, 232))
    y0 = top + (row + 2) * (ch + head) + 10
    for k, vid in enumerate(ORDER):
        icon = VI.ICONS[vid]()
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        sheet.paste(bg.convert("RGB"), (14 + k * 236, y0 + 20))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        sheet.paste(slot.convert("RGB"), (120 + k * 236, y0 + 80))
        d.text((120 + k * 236, y0 + 24), ko[vid], font=small, fill=(240, 240, 240))
    d.text((14, y0 - 4), "인벤토리 아이콘 (16px · 실제 크기)", font=small, fill=(170, 170, 178))
    sheet.paste(lb, (980, y0 + 14))
    d.text((980, y0 - 4), "경광등: 꺼짐 · 빨강 · 파랑 (번갈아)", font=small, fill=(170, 170, 178))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


def close_up(vid, out, cargo=0, lights="a", pose="rest"):
    """One vehicle large from four sides (for working on a model): python3 vehicle_preview.py --one <id> <out.png>
    [stage] [rest|pulled]"""
    t, e, spec = vehicle(vid, (0, 0, 0), cargo=cargo, lights=lights, pose=pose)
    if pose == "pulled":
        player, _ = _ref_models()
        t = {**t, **player["textures"], "ref_rider": _solid((222, 152, 60))}
    L, W, H = spec["size"]
    cz = 0
    if "pull" in spec:
        front = spec["pull"]["hitch"] + 0.35
        cz, L = -(front - L / 2) * 8, front + L / 2
    r = max(L, 1.5 * H) * 8
    c = [0, H * 7, cz]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 3.6, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 3.6, "radius": r, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": 30, "el": 8, "k": 3.6, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": -100, "el": 40, "k": 3.6, "radius": r, "fov": 26, "center": c}]
    render({"textures": t, "elements": e, "grid": False, "views": views}, "one_" + vid).save(out)
    print(f"close-up {out}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--one":
        close_up(sys.argv[2], Path(sys.argv[3]), cargo=int(sys.argv[4]) if len(sys.argv) > 4 else 0,
                 pose=sys.argv[5] if len(sys.argv) > 5 else "rest")
    else:
        main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicles.png")
