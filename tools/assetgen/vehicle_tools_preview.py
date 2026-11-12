"""
Preview sheet of the vehicles' tools (작업기: content/stsv/vehicle_tools.yml, models/vehicle_tools.py):

    python3 vehicle_tools_preview.py --build            # writes the tool models into content/stsv/models (build.py does too)
    python3 vehicle_tools_preview.py [out.png]          # docs/previews/vehicle_tools.png
    python3 vehicle_tools_preview.py --model <model> <out.png>   # one model from four sides

Every vehicle is put together as the plugin shows it: its body, wheels and moving parts from its items file, then the tool
from vehicle_tools.yml on its unit (a tool that takes a trailer's place hides that trailer's body and load, and its wheels
unless `wheels: true`), its moving parts turned a little. Players sit in the seats (orange = driver). The farm scenes stand
on grass with the strip the tool works drawn behind it (tilled soil, sown rows); the 탑차 with its door closed and open,
the 1톤 트럭 with its tarp folded and on.
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
from models import vehicles as V            # noqa: E402
from models import vehicles_road as R       # noqa: E402
from models import vehicles_farm as F       # noqa: E402
from models import vehicles_misc as M       # noqa: E402
from models import vehicle_tools as TL      # noqa: E402
import yaml                                 # noqa: E402
from furniture_preview import _ref_models   # noqa: E402
from vehicle_preview import sitting, _solid, FONT   # noqa: E402

REPO = HERE.parent.parent
CONTENT = REPO / "content" / "stsv"
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
TOOLS = CONTENT / "vehicle_tools.yml"
MODELS = {**V.ALL, **R.ALL, **F.ALL, **M.ALL, **TL.ALL}


def items():
    out = {}
    for f in ("vehicles.yml", "vehicles_road.yml", "vehicles_farm.yml", "vehicles_misc.yml"):
        out.update(yaml.safe_load((CONTENT / "items" / f).read_text()))
    return out


def tools():
    return yaml.safe_load(TOOLS.read_text())


def render(data, tag):
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / f"vtool_{tag}.json", BUILD / f"vtool_{tag}.png"
    src.write_text(json.dumps(data))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, capture_output=True)
    return Image.open(png).convert("RGB")


_cache = {}


def preview(name):
    if name not in _cache:
        _cache[name] = MODELS[name]().to_preview()
    return _cache[name]


def _moved(els, fn, rot=None, post=None):
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


def part(name, at, origin, rot=None, post=None, scale=(1, 1, 1)):
    """A model (attachment point at model (8, 8, 8)) at a unit position [right, up, forward] (blocks) of a unit whose
    origin is at drawn `origin`, scaled (sx, sy, sz) about its attachment point (a folded tarp, a rolled-up door);
    rot [axis, angle] about the attachment point."""
    data = preview(name)
    ax, ay, az = origin[0] + at[0] * 16, origin[1] + at[1] * 16, origin[2] - at[2] * 16
    fn = lambda p: [(p[0] - 8) * scale[0] + ax, (p[1] - 8) * scale[1] + ay, (p[2] - 8) * scale[2] + az]
    r = [rot[0], rot[1], [ax, ay, az]] if rot else None
    return data["textures"], _moved(data["elements"], fn, r, post)


class Unit:
    def __init__(self, r, f, yaw):
        self.r, self.f, self.yaw = r, f, yaw

    def world(self, p):
        t = math.radians(self.yaw)
        fw, rt = (math.sin(t), math.cos(t)), (math.cos(t), -math.sin(t))
        return (self.r + p[0] * rt[0] + p[2] * fw[0], p[1], self.f + p[0] * rt[1] + p[2] * fw[1])


def layout(spec, angles=()):
    units = [Unit(0, 0, 0)]
    for i, tr in enumerate(spec.get("train", {}).get("trailers", [])):
        front = units[-1]
        yaw = front.yaw - (angles[i] if i < len(angles) else 0)
        h = front.world(tr["hitch"])
        t = math.radians(yaw)
        units.append(Unit(h[0] - math.sin(t) * tr["tongue"], h[2] - math.cos(t) * tr["tongue"], yaw))
    return units


def vehicle(vid, ox, tool=None, spin=30.0, cover=None, door=None, cargo=None, riders=True):
    """Textures and elements of a vehicle with its tool (an id of vehicle_tools.yml, or None) at drawn offset ox.
    cover: the tarp on (True) or folded (False); door: the roller door open (True) or closed (False)."""
    it = items()[vid]
    spec = it["vehicle"]
    tl = tools().get(vid, {})
    t = next((x for x in tl.get("tools", []) if x["id"] == tool), None)
    units = layout(spec)
    k = spec.get("body-scale", 1)
    textures, elements = {}, []

    def add(tx, e):
        textures.update(tx)
        elements.extend(e)

    def unit_post(u):
        o = [ox[0] + u.r * 16, 0, ox[2] - u.f * 16]
        return o, ([["y", -u.yaw, o]] if u.yaw else None)

    o, post = unit_post(units[0])
    body = preview(it["model"])
    add(body["textures"], _moved(body["elements"], lambda p: [(p[0] - 8) * k + o[0], (p[1] - 8) * k, (p[2] - 8) * k + o[2]], None, post))
    for w in spec.get("wheels", []):
        add(*part(spec["wheel"], w["at"], o, None, post))
    tr_spec = spec.get("train")
    if tr_spec:
        for p in tr_spec.get("parts", []):
            add(*part(p["model"], p["at"], o, (p.get("axis", "x"), spin) if spin else None, post))
        a = tr_spec.get("animal")
        if a:
            for leg in a["legs"]:
                add(*part(leg["model"], leg["at"], o, None, post))
            if a.get("head"):
                add(*part(a["head"]["model"], a["head"]["at"], o, None, post))
        for i, tr in enumerate(tr_spec["trailers"]):
            u = units[i + 1]
            ou, pu = unit_post(u)
            replaced = t is not None and t.get("replaces") and t.get("unit") == i + 1
            if not replaced:
                tb = preview(tr["model"])
                kt = tr.get("body-scale", k)
                add(tb["textures"], _moved(tb["elements"], lambda p: [(p[0] - 8) * kt + ou[0], (p[1] - 8) * kt, (p[2] - 8) * kt + ou[2]], None, pu))
            if not replaced or t.get("wheels"):
                for w in tr["wheels"]:
                    add(*part(tr["wheel"], w["at"], ou, None, pu))
            if cargo and not replaced and "cargo" in tr:
                add(*part(tr["cargo"]["stages"][cargo - 1], tr["cargo"]["at"], ou, None, pu))
    elif cargo and "cargo" in spec and not cover:
        add(*part(spec["cargo"]["stages"][cargo - 1], spec["cargo"]["at"], o, None, post))
    # the tool on its unit, its moving parts turned
    if t and t["kind"] != "cover":
        u = units[t.get("unit", 0)]
        ou, pu = unit_post(u)
        s = t.get("scale", 1)
        add(*part(t["model"], t.get("at", [0, 0, 0]), ou, None, pu, (s, s, s)))
        for p in t.get("parts", []):
            ps = p.get("scale", 1)
            add(*part(p["model"], p["at"], ou, (p.get("axis", "x"), spin) if spin else None, pu, (ps, ps, ps)))
    c = next((x for x in tl.get("tools", []) if x["kind"] == "cover"), None)
    if c is not None and cover is not None:
        s = c.get("scale", 1)
        f = 1 if cover else c.get("cover", {}).get("fold", 0.12)
        add(*part(c["model"], c.get("at", [0, 0, 0]), o, None, post, (s, s, s * f)))
    sh = tl.get("shutter")
    if sh is not None and door is not None:
        s = sh.get("scale", 1)
        add(*part(sh["model"], sh["at"], o, None, post, (s, s * (sh.get("rolled", 0.08) if door else 1), s)))
        if door:
            add(*part(sh["inside"], sh["at"], o, None, post, (s, s, s)))
    if riders:
        for i, seat in enumerate(spec.get("seats", [])):
            u = units[seat.get("unit", 0)]
            ou, pu = unit_post(u)
            els = sitting(seat["at"], (ou[0], 0, ou[2]), "ref_rider" if i == 0 else "ref_passenger")
            if pu:
                for e in els:
                    e["post"] = copy.deepcopy(pu)
            elements.extend(els)
    return textures, elements, spec, units


# ------------------------------------------------------------------------------------------------
# Ground under the farm scenes: grass, and the strip the tool worked behind it
# ------------------------------------------------------------------------------------------------

GROUND = {"grass": (95, 140, 62), "grass_side": (110, 88, 60), "farmland": (84, 58, 38), "rows": (70, 112, 48), "water": (54, 96, 150),
          "ripe": (206, 178, 84)}


def _slab(frm, to, top, side):
    faces = {f: {"uv": [0, 0, 16, 16], "texture": side} for f in ("north", "south", "east", "west", "down")}
    faces["up"] = {"uv": [0, 0, 16, 16], "texture": top}
    return {"from": list(frm), "to": list(to), "glow": False, "shade": True, "rotation": None, "faces": faces}


def ground(ox, x0, x1, z0, z1, strip=None, kind="farmland", crops=None):
    """A grass field under a scene (drawn px), a worked strip (x0, x1, z0, z1) in it, ripe crops standing ahead."""
    els = [_slab((ox[0] + x0, -4, ox[2] + z0), (ox[0] + x1, -0.02, ox[2] + z1), "g_grass", "g_side")]
    if strip:
        sx0, sx1, sz0, sz1 = strip
        els.append(_slab((ox[0] + sx0, -4, ox[2] + sz0), (ox[0] + sx1, 0.0, ox[2] + sz1), "g_" + kind, "g_side"))
        if kind == "rows":
            for x in range(int(sx0) + 6, int(sx1), 16):
                for z in range(int(sz0) + 3, int(sz1) - 2, 5):
                    els.append(_slab((ox[0] + x, 0, ox[2] + z), (ox[0] + x + 3, 3, ox[2] + z + 1), "g_rows", "g_rows"))
    if crops:
        cx0, cx1, cz0, cz1 = crops
        els.append(_slab((ox[0] + cx0, -4, ox[2] + cz0), (ox[0] + cx1, 0.0, ox[2] + cz1), "g_farmland", "g_side"))
        for x in range(int(cx0) + 2, int(cx1) - 2, 4):
            for z in range(int(cz0) + 1, int(cz1) - 1, 4):
                els.append(_slab((ox[0] + x, 0, ox[2] + z), (ox[0] + x + 1, 13, ox[2] + z + 1), "g_ripe", "g_ripe"))
    return els


def ground_textures():
    return {"g_grass": _solid(GROUND["grass"]), "g_side": _solid(GROUND["grass_side"]), "g_farmland": _solid(GROUND["farmland"]),
            "g_rows": _solid(GROUND["rows"]), "g_ripe": _solid(GROUND["ripe"]), "g_water": _solid(GROUND["water"])}


# ------------------------------------------------------------------------------------------------
# The sheet
# ------------------------------------------------------------------------------------------------

def main(out):
    player, block = _ref_models()
    textures = {**player["textures"], **block["textures"], "ref_rider": _solid((222, 152, 60)), "ref_passenger": _solid((120, 160, 120)),
                **ground_textures()}
    elements, cells = [], []
    W = 1600
    fov = 26
    row_h = []

    def scene(vid, ox, **kw):
        t, e, spec, units = vehicle(vid, ox, **kw)
        textures.update(t)
        elements.extend(e)
        return spec, units

    def cell(r, x, w, v, label, big=False):
        cells.append((r, x, w, v, label, big))

    # row 0: 소달구지 + 쟁기 / row 1: 경운기 + 로터리 (each from three sides and its tool close up)
    for r, (vid, tool, label, strip_w) in enumerate([("ox_cart", "plough", "소달구지 · 쟁기 (한 줄 갈기)", 16),
                                                     ("power_tiller", "rotary", "경운기 · 로터리 (두 줄 갈기)", 32)]):
        ox = (r * 3000, 0, 0)
        spec, units = scene(vid, ox, tool=tool)
        back = -(units[-1].f - 1.2) * 16
        elements.extend(ground(ox, -72, 72, -64, back + 120, strip=(-strip_w / 2, strip_w / 2, back - 10, back + 120)))
        mid = (back - 40) / 2
        views = [("앞 왼쪽", -38, 22, 2.6), ("뒤 오른쪽 (갈아 놓은 줄)", 145, 26, 2.6), ("옆", -90, 6, 2.6)]
        for c, (lab, az, el, kk) in enumerate(views):
            v = {"az": az, "el": el, "k": kk, "radius": 60, "fov": fov, "far": 6000, "center": [ox[0], 14, mid]}
            cell(r, c * 400, 400, v, f"{label} · {lab}" if c == 0 else lab, c == 0)
        uz = -units[1].f * 16
        v = {"az": 150, "el": 34, "k": 2.3, "radius": 24, "fov": fov, "far": 6000, "center": [ox[0] + units[1].r * 16, 6, ox[2] + uz + (10 if r == 0 else 0)]}
        cell(r, 1200, 400, v, "작업기 가까이")
        row_h.append(300)
    # rows 2, 3: 트랙터 + 로터리 / 파종기 / 수확기
    r = len(row_h)
    tr_cells = [("rotary", "트랙터 · 로터리 (세 줄 갈기)", 145, 26, "farmland"), ("seeder", "파종기 (세 줄 심기, 씨앗은 트레일러에서)", 145, 26, "rows"),
                ("harvester", "수확기 (세 줄 거두기, 트레일러로)", -35, 22, None)]
    for c, (tool, label, az, el, kind) in enumerate(tr_cells):
        ox = (8000 + c * 3000, 0, 0)
        spec, units = scene("tractor_trailer", ox, tool=tool, cargo=2 if tool == "harvester" else None)
        back = -(units[-1].f - 1.5) * 16
        if kind:
            elements.extend(ground(ox, -80, 80, -60, back + 80, strip=(-24, 24, 32, back + 80), kind=kind))
        else:
            elements.extend(ground(ox, -80, 80, -120, back + 40, crops=(-24, 24, -110, -48)))
        center = [ox[0], 14, ox[2] + (back - 50) / 2 - (30 if tool == "harvester" else 0)]
        v = {"az": az, "el": el, "k": 2.2, "radius": 90, "fov": fov, "far": 6000, "center": center}
        w = W // 3
        cell(r, c * w, w, v, label, c == 0)
    row_h.append(360)
    r += 1
    for c, (tool, label, az, el, cz) in enumerate([("rotary", "로터리 가까이 (날이 돎, PTO 축)", 120, 24, 39),
                                                   ("seeder", "파종기 가까이 (호퍼 창, 씨앗 관, 디스크, 진압 롤러)", 135, 30, 40),
                                                   ("harvester", "수확기 가까이 (분할기, 예취날, 오거, 릴)", -30, 24, -40)]):
        ox = (8000 + c * 3000, 0, 0)
        w = W // 3
        v = {"az": az, "el": el, "k": 2.4, "radius": 34, "fov": fov, "far": 6000, "center": [ox[0], 10, ox[2] + cz]}
        cell(r, c * w, w, v, label)
    row_h.append(300)
    # row 4: 통통배 + 그물
    r += 1
    ox = (20000, 0, 0)
    scene("fishing_boat", ox, tool="trawl", cargo=2)
    elements.append(_slab((ox[0] - 120, -40, ox[2] - 100), (ox[0] + 120, 5.5, ox[2] + 140), "g_water", "g_water"))
    for c, (lab, az, el, kk, cz) in enumerate([("통통배 · 그물 끌기 (뒤 오른쪽)", 140, 22, 2.2, 30), ("옆", -90, 8, 2.4, 20),
                                               ("갤로스와 그물 드럼 가까이", 160, 30, 2.6, 50)]):
        v = {"az": az, "el": el, "k": kk, "radius": 56 if c < 2 else 30, "fov": fov, "far": 6000, "center": [ox[0], 16, ox[2] + cz]}
        w = W // 3
        cell(r, c * w, w, v, lab, c == 0)
    row_h.append(320)
    # row 5: 1톤 트럭 덮개 (접힘 / 덮음), 탑차 셔터 (닫힘 / 열림)
    r += 1
    for c, (vid, kw, lab, az) in enumerate([("light_truck", {"cover": False, "cargo": 3}, "1톤 트럭 · 방수포 걷음 (앞에 접힘)", 145),
                                            ("light_truck", {"cover": True, "cargo": 3}, "방수포 덮음", 145),
                                            ("box_truck", {"door": False}, "탑차 · 셔터 닫힘 (달릴 때)", 150),
                                            ("box_truck", {"door": True}, "셔터 열림 (짐칸·하역장)", 150)]):
        ox = (30000 + c * 3000, 0, 0)
        scene(vid, ox, riders=False, **kw)
        L = items()[vid]["vehicle"]["size"][0]
        v = {"az": az, "el": 20, "k": 2.4, "radius": L * 8 + 10, "fov": fov, "far": 6000, "center": [ox[0], 24, ox[2] + 6]}
        cell(r, c * 400, 400, v, lab, c == 0)
    row_h.append(300)
    # row 6: the moving parts alone
    r += 1
    parts = ["tiller_rotary_tines", "tractor_rotary_tines", "tractor_seeder_roller", "tractor_header_reel", "boat_net_drum"]
    for c, name in enumerate(parts):
        ox = (40000 + c * 300, 0, 0)
        tx, e = part(name, [0, 0.6, 0], (ox[0], 0, 0), ("x", 20), None)
        textures.update(tx)
        elements.extend(e)
        w = W // len(parts)
        v = {"az": -30, "el": 24, "k": 2.6, "radius": 26, "fov": fov, "far": 6000, "center": [ox[0], 10, 0]}
        cell(r, c * w, w, v, name)
    row_h.append(200)

    ys = [sum(row_h[:i]) for i in range(len(row_h))]
    views = [{**v, "x": x, "y": ys[rr], "w": w, "h": row_h[rr]} for (rr, x, w, v, label, big) in cells]
    im = render({"textures": textures, "elements": elements, "grid": False, "size": [W, sum(row_h)], "views": views}, "sheet")

    font = ImageFont.truetype(FONT, 21)
    small = ImageFont.truetype(FONT, 16)
    top, head = 50, 34
    total = top + sum(h + head for h in row_h)
    sheet = Image.new("RGB", (W, total), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "작업기 · 운전석에서 Ctrl = 다음 작업기 (멈춰서) · 주황 = 운전자, 초록 = 동승자 · 갈색 줄 = 갈아 놓은 땅, 초록 줄 = 심은 줄 "
           "(움직이는 부품은 조금 돌려 그림)", font=small, fill=(235, 232, 222))
    oy = [top + sum(h + head for h in row_h[:i]) for i in range(len(row_h))]
    for (rr, x, w, v, label, big) in cells:
        sheet.paste(im.crop((x, ys[rr], x + w, ys[rr] + row_h[rr])), (x, oy[rr] + head))
        d.rectangle((x, oy[rr], x + w - 2, oy[rr] + head - 2), fill=(40, 43, 52))
        d.text((x + 8, oy[rr] + 5), label, font=font if big else small, fill=(245, 242, 232))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


def model_close_up(name, out):
    d = preview(name)
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 20, "k": 2.8},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 2.8},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": -90, "el": 5, "k": 2.8},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 20, "el": 60, "k": 2.8}]
    render({"textures": d["textures"], "elements": d["elements"], "grid": False, "views": views}, "model_" + name).save(out)
    print(f"model {out}")


def build():
    """The tool models into the content pack (as build.py writes every model)."""
    BUILD.mkdir(exist_ok=True)
    for name, fn in TL.ALL.items():
        m = fn()
        m.write(CONTENT / "models" / f"{name}.bbmodel", BUILD / f"{name}.preview.json")
        print(f"model   {name}: {len(m.elements)} elements")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--build":
        build()
    elif len(sys.argv) > 1 and sys.argv[1] == "--model":
        model_close_up(sys.argv[2], Path(sys.argv[3]))
    else:
        main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicle_tools.png")
