"""
Preview sheet of the articulated farm vehicles (needs: cd render && npm install, or a global playwright):

    python3 vehicle_preview_farm.py [out.png]          # docs/previews/vehicles_farm.png
    python3 vehicle_preview_farm.py --one <id> <out.png> [stage] [angle]   # one vehicle large from four sides
    python3 vehicle_preview_farm.py --model <model> <out.png>              # one model from four sides

Every vehicle is put together as the plugin shows it, from content/stsv/items/vehicles_farm.yml: the driven unit's body
(drawn at half size, shown at body-scale), its wheels, legs, head and spinning parts where the file puts them; every
towed unit hanging on its hitch (train.trailers: hitch, tongue) with its wheels and load, turned by the angle between
the units; players sitting in the seats (seats[].unit: on which unit). A standing player (1.8 blocks) and a block stand
next to each for scale. Then each train bent to its jack-knife limit (train.jackknife) seen from above, the ox's walk
cycle frame by frame (legs swung as TrainView swings them: Gait), every load stage, and the inventory icons.
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
from models import vehicles_farm as F     # noqa: E402
import vehicle_icons_farm as VI           # noqa: E402
import yaml                               # noqa: E402
from furniture_preview import _ref_models  # noqa: E402
from vehicle_preview import sitting, _solid, FONT  # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
ITEMS = REPO / "content/stsv/items/vehicles_farm.yml"
ORDER = ["ox_cart", "power_tiller", "tractor_trailer"]
MODELS = {**V.ALL, **F.ALL}


def items():
    return yaml.safe_load(ITEMS.read_text())


def render(data, tag):
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / f"vehf_{tag}.json", BUILD / f"vehf_{tag}.png"
    src.write_text(json.dumps(data))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, capture_output=True)
    return Image.open(png).convert("RGB")


# ------------------------------------------------------------------------------------------------
# Putting a train together
# ------------------------------------------------------------------------------------------------

_cache = {}


def preview(name):
    if name not in _cache:
        _cache[name] = MODELS[name]().to_preview()
    return _cache[name]


def _moved(els, fn, rot=None, post=None):
    """Elements with every point moved by fn (a shift, maybe a uniform scale); rot: an extra rotation for elements that
    have none; post: rotations after everything (a unit's yaw)."""
    out = []
    for e in els:
        e = copy.deepcopy(e)
        a, b = fn(e["from"]), fn(e["to"])
        e["from"], e["to"] = [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]
        if e.get("rotation"):
            e["rotation"][2] = fn(e["rotation"][2])
            if rot:
                e["post"] = [copy.deepcopy(rot)] + e.get("post", [])
        elif rot:
            e["rotation"] = copy.deepcopy(rot)
        if post:
            e["post"] = e.get("post", []) + copy.deepcopy(post)
        out.append(e)
    return out


def part(name, at, origin, rot=None, post=None, scale=1, drop=0):
    """A part model (attachment point at model (8, 8, 8)) at a unit position [right, up, forward] (blocks) of a unit
    whose origin is at drawn `origin`; rot [axis, angle] about its own attachment point."""
    data = preview(name)
    ax, ay, az = origin[0] + at[0] * 16, origin[1] + at[1] * 16 - drop, origin[2] - at[2] * 16
    fn = lambda p: [(p[0] - 8) * scale + ax, (p[1] - 8) * scale + ay, (p[2] - 8) * scale + az]
    r = [rot[0], rot[1], [ax, ay, az]] if rot else None
    return data["textures"], _moved(data["elements"], fn, r, post)


class Unit:
    """A unit's place: origin (blocks: right, forward of the head's ground centre) and yaw (degrees, + = turned right)."""

    def __init__(self, r, f, yaw):
        self.r, self.f, self.yaw = r, f, yaw

    def world(self, p):
        """A point [right, up, forward] on this unit -> head frame (right, up, forward)."""
        t = math.radians(self.yaw)
        fw, rt = (math.sin(t), math.cos(t)), (math.cos(t), -math.sin(t))
        return (self.r + p[0] * rt[0] + p[2] * fw[0], p[1], self.f + p[0] * rt[1] + p[2] * fw[1])


def layout(spec, angles):
    """The units of a train: the head at 0 and each towed unit at angles[i] (degrees) to the one in front of it."""
    units = [Unit(0, 0, 0)]
    for i, tr in enumerate(spec["train"]["trailers"]):
        front = units[-1]
        yaw = front.yaw - (angles[i] if i < len(angles) else 0)
        h = front.world(tr["hitch"])
        t = math.radians(yaw)
        units.append(Unit(h[0] - math.sin(t) * tr["tongue"], h[2] - math.cos(t) * tr["tongue"], yaw))
    return units


def gait_angles(spec, phase, amount=1.0):
    a = spec["train"].get("animal")
    if not a:
        return []
    return [amount * a["swing"] * math.sin(2 * math.pi * (phase + leg["phase"])) for leg in a["legs"]]


def train(vid, ox, angles=(), cargo=None, riders=True, steer=0, phase=None, spin=0.0):
    """Textures and elements of a whole train at drawn offset ox. angles: between the units; cargo: the load stage on
    every trailer (or a list per trailer); phase: the ox's stride (None = standing); spin: degrees of the spinning
    parts."""
    spec = items()[vid]["vehicle"]
    units = layout(spec, angles)
    k = spec.get("body-scale", 1)
    textures, elements = {}, []

    def unit_post(u):
        o = [ox[0] + u.r * 16, 0, ox[2] - u.f * 16]
        return o, ([["y", -u.yaw, o]] if u.yaw else None)

    def add(t, e):
        textures.update(t)
        elements.extend(e)

    # the head
    o, post = unit_post(units[0])
    body = preview(items()[vid]["model"])
    add(body["textures"], _moved(body["elements"], lambda p: [(p[0] - 8) * k + o[0], (p[1] - 8) * k, (p[2] - 8) * k + o[2]], None, post))
    for w in spec.get("wheels", []):
        add(*part(spec["wheel"], w["at"], o, ("y", steer) if w.get("steer") and steer else None, post))
    tr_spec = spec["train"]
    for p in tr_spec.get("parts", []):
        add(*part(p["model"], p["at"], o, (p.get("axis", "x"), spin) if spin else None, post))
    a = tr_spec.get("animal")
    if a:
        swings = gait_angles(spec, phase) if phase is not None else [0] * len(a["legs"])
        for leg, sw in zip(a["legs"], swings):
            add(*part(leg["model"], leg["at"], o, ("x", sw) if sw else None, post))
        if a.get("head"):
            bob = 0 if phase is None else a["head"].get("bob", 0.04) * 0.5 * (1 - math.cos(4 * math.pi * phase))
            add(*part(a["head"]["model"], a["head"]["at"], o, ("x", -6 * bob / max(1e-6, a["head"].get("bob", 0.04))) if bob else None,
                      post, drop=bob * 16))
    # the towed units
    for i, tr in enumerate(tr_spec["trailers"]):
        u = units[i + 1]
        o, post = unit_post(u)
        tb = preview(tr["model"])
        kt = tr.get("body-scale", k)
        add(tb["textures"], _moved(tb["elements"], lambda p: [(p[0] - 8) * kt + o[0], (p[1] - 8) * kt, (p[2] - 8) * kt + o[2]], None, post))
        for w in tr["wheels"]:
            add(*part(tr["wheel"], w["at"], o, None, post))
        st = cargo[i] if isinstance(cargo, (list, tuple)) else cargo
        if st and "cargo" in tr:
            add(*part(tr["cargo"]["stages"][st - 1], tr["cargo"]["at"], o, None, post))
    if riders:
        for i, seat in enumerate(spec.get("seats", [])):
            u = units[seat.get("unit", 0)]
            o, post = unit_post(u)
            els = sitting(seat["at"], (o[0], 0, o[2]), "ref_rider" if i == 0 else "ref_passenger")
            if post:
                for e in els:
                    e["post"] = copy.deepcopy(post)
            elements.extend(els)
    return textures, elements, spec, units


def extent(spec, units):
    """The train's drawn extent along its length: (front z, back z) of the head's front and the last unit's back."""
    L = spec["size"][0]
    last = spec["train"]["trailers"][-1]
    return -L / 2 * 16, -(units[-1].f - last["size"][0] / 2) * 16


# ------------------------------------------------------------------------------------------------
# The sheet
# ------------------------------------------------------------------------------------------------

VIEWS = [("앞 왼쪽 (끄는 쪽)", -38, 22, 1.3), ("뒤 오른쪽", 142, 24, 1.3), ("옆 · 사람과 크기 비교", -90, 4, 1.35), ("위", -20, 70, 1.4)]


def main(out):
    ko = {k: v["name"].split(">")[-1] for k, v in items().items()}
    player, block = _ref_models()
    base = {**player["textures"], **block["textures"], "ref_rider": _solid((222, 152, 60)), "ref_passenger": _solid((120, 160, 120))}
    W = 1600
    cw, ch, head = 400, 300, 34
    fov = 26
    textures, elements, cells = dict(base), [], []      # cells: (row, x, w, h, view, label, big)
    row_h = []

    def cell(r, x, w, view, label, big=False):
        cells.append((r, x, w, view, label, big))

    # rows 0..2: each vehicle from four sides, with a player and a block for scale
    for r, vid in enumerate(ORDER):
        ox = (r * 2000, 0, 0)
        t, e, spec, units = train(vid, ox, cargo=None, phase=None)
        textures.update(t)
        elements.extend(e)
        front, back = extent(spec, units)
        mid = (front + back) / 2
        length = (back - front) / 16
        W_ = max(spec["size"][1], max(tr["size"][1] for tr in spec["train"]["trailers"]))
        elements.extend(_moved(player["elements"], lambda p: [p[0] + ox[0] - W_ * 8 - 10, p[1], p[2] + ox[2] + mid + 6]))
        elements.extend(_moved(block["elements"], lambda p: [p[0] + ox[0] + W_ * 8 + 4, p[1], p[2] + ox[2] + front - 6]))
        radius = max(length, 2.5) * 8
        for c, (label, az, el, kk) in enumerate(VIEWS):
            v = {"az": az, "el": el, "k": (3.3 if length < 8 else 2.5) * kk, "radius": radius, "fov": fov, "far": 6000, "center": [ox[0], 14, mid]}
            cell(r, c * cw, cw, v, f"{ko[vid]} · {label}" if c == 0 else label, c == 0)
        row_h.append(ch)
    # row 3: bent to the jack-knife limit, from above
    r = len(ORDER)
    for c, vid in enumerate(ORDER):
        ox = (8000 + c * 2000, 0, 0)
        spec = items()[vid]["vehicle"]
        cap = spec["train"]["jackknife"]
        n = len(spec["train"]["trailers"])
        t, e, spec, units = train(vid, ox, angles=[cap] * n, riders=False, steer=-22.5 if any(w.get("steer") for w in spec.get("wheels", [])) else 0,
                                  phase=0.15)
        textures.update(t)
        elements.extend(e)
        xs = [u.r for u in units]
        zs = [u.f for u in units]
        center = [ox[0] + sum(xs) / len(xs) * 16, 10, ox[2] - sum(zs) / len(zs) * 16]
        span = max(max(xs) - min(xs), max(zs) - min(zs)) + 3
        v = {"az": -15, "el": 72, "k": 4.8, "radius": span * 8, "fov": fov, "far": 6000, "center": center}
        w = W // 3
        cell(r, c * w, w, v, f"{ko[vid]} · 최대로 꺾임 ({cap}°, 위에서)", False)
    row_h.append(360)
    # row 4: the ox's walk, frame by frame (side view): the legs swing with the ground covered, the head bobs
    r += 1
    frames = 6
    for k in range(frames):
        ox = (20000 + k * 1000, 0, 0)
        ph = k / frames
        t, e, spec, units = train("ox_cart", ox, riders=False, phase=ph)
        textures.update(t)
        elements.extend(e)
        w = W // frames
        v = {"az": -90, "el": 6, "k": 2.6, "radius": 36, "fov": fov, "far": 6000, "center": [ox[0], 15, ox[2] - 2]}
        cell(r, k * w, w, v, ("소 걸음 " if k == 0 else "") + f"{k}/{frames} 걸음", False)
    row_h.append(230)
    # rows 5..7: the load stages of each vehicle's trailers
    for vid in ORDER:
        r += 1
        spec = items()[vid]["vehicle"]
        slots = sum(tr["cargo"]["slots"] for tr in spec["train"]["trailers"])
        for k in range(4):
            ox = (30000 + ORDER.index(vid) * 8000 + k * 1600, 0, 0)
            t, e, spec, units = train(vid, ox, cargo=k + 1, riders=False)
            textures.update(t)
            elements.extend(e)
            mid_f = sum(u.f for u in units[1:]) / (len(units) - 1)
            span = abs(units[-1].f - units[1].f) + spec["train"]["trailers"][0]["size"][0]
            w = W // 4
            v = {"az": 145, "el": 34, "k": 2.4, "radius": max(span, 2.6) * 11, "fov": fov, "far": 6000,
                 "center": [ox[0] + units[1].r * 8, 12, ox[2] - mid_f * 16]}
            label = ["~25%", "~50%", "~75%", f"가득 ({slots}칸)"][k]
            cell(r, k * w, w, v, (f"{ko[vid]} 짐 " if k == 0 else "짐 ") + label, False)
        row_h.append(260)

    # one render: every cell a view of the same scene
    ys = [sum(row_h[:i]) for i in range(len(row_h))]
    views = []
    for (rr, x, w, v, label, big) in cells:
        views.append({**v, "x": x, "y": ys[rr], "w": w, "h": row_h[rr]})
    im = render({"textures": textures, "elements": elements, "grid": False, "size": [W, sum(row_h)], "views": views}, "sheet")

    font = ImageFont.truetype(FONT, 21)
    small = ImageFont.truetype(FONT, 16)
    top = 50
    icon_h = 150
    total = top + sum(h + head for h in row_h) + icon_h
    sheet = Image.new("RGB", (W, total), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "농사 탈것 · 관리자 아이템 · 끄는 쪽 + 짐칸(트레일러) · 주황 = 운전자, 초록 = 동승자 · 블록 1칸과 사람(1.8칸) · "
           "트레일러는 연결점을 향해 바퀴 축을 중심으로 돎", font=small, fill=(235, 232, 222))
    oy = [top + sum(h + head for h in row_h[:i]) for i in range(len(row_h))]
    for (rr, x, w, v, label, big) in cells:
        sheet.paste(im.crop((x, ys[rr], x + w, ys[rr] + row_h[rr])), (x, oy[rr] + head))
        d.rectangle((x, oy[rr], x + w - 2, oy[rr] + head - 2), fill=(40, 43, 52))
        d.text((x + 8, oy[rr] + 5), label, font=font if big else small, fill=(245, 242, 232))
    y0 = total - icon_h + 10
    d.text((14, y0 - 4), "인벤토리 아이콘 (16px · 실제 크기)", font=small, fill=(170, 170, 178))
    for k, vid in enumerate(ORDER):
        icon = VI.ICONS[vid]()
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        sheet.paste(bg.convert("RGB"), (14 + k * 300, y0 + 20))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        sheet.paste(slot.convert("RGB"), (120 + k * 300, y0 + 80))
        d.text((120 + k * 300, y0 + 24), ko[vid], font=small, fill=(240, 240, 240))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


def close_up(vid, out, cargo=0, angle=0.0, phase=None):
    """One vehicle large from four sides (for working on a model)."""
    spec = items()[vid]["vehicle"]
    n = len(spec["train"]["trailers"])
    t, e, spec, units = train(vid, (0, 0, 0), angles=[angle] * n, cargo=cargo or None, phase=phase)
    player, _ = _ref_models()
    t = {**t, **player["textures"], "ref_rider": _solid((222, 152, 60)), "ref_passenger": _solid((120, 160, 120))}
    front, back = extent(spec, units)
    r = (back - front) / 2
    c = [0, 14, (front + back) / 2]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 3.0, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 3.0, "radius": r, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": 30, "el": 8, "k": 3.0, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": -100, "el": 40, "k": 3.0, "radius": r, "fov": 26, "center": c}]
    render({"textures": t, "elements": e, "grid": False, "views": views}, "one_" + vid).save(out)
    print(f"close-up {out}")


def model_close_up(name, out):
    d = preview(name)
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 20, "k": 2.8},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 2.8},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": -90, "el": 5, "k": 2.8},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 20, "el": 60, "k": 2.8}]
    render({"textures": d["textures"], "elements": d["elements"], "grid": False, "views": views}, "model_" + name).save(out)
    print(f"model {out}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--one":
        close_up(sys.argv[2], Path(sys.argv[3]), cargo=int(sys.argv[4]) if len(sys.argv) > 4 else 0,
                 angle=float(sys.argv[5]) if len(sys.argv) > 5 else 0.0)
    elif len(sys.argv) > 1 and sys.argv[1] == "--model":
        model_close_up(sys.argv[2], Path(sys.argv[3]))
    else:
        main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicles_farm.png")
