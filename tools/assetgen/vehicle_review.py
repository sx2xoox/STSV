"""
One review sheet of every vehicle (needs: cd render && npm install, or a global playwright):

    python3 vehicle_review.py [out.png]                  # docs/previews/vehicles_all.png
    python3 vehicle_review.py --cells <dir> [ids...]     # each vehicle's two views as <dir>/<id>.png (before/after sheets)
    python3 vehicle_review.py --pair <before dir> <after dir> <out.png> ids...   # the before/after sheet of those ids

All 15 vehicles at ONE scale (the same pixels per block everywhere, the same camera distance, a near-orthographic lens)
from the same two angles: 3/4 front-left (driver's side, riders seated: seat heights against the roof and the player)
and 3/4 rear-right (no riders, the full load: does it fit the bed). A standing player (1.875 blocks) beside each one;
the boat floats at its draft on see-through water (its waterline), the 지게 is worn by a player-sized mannequin (empty
from the front, full from behind). Grouped by generation (vehicles*.yml lore): 1세대 .. 5세대, 특수, 이벤트. Each
vehicle's inventory icon at its real 16 px and 4x, on the inventory slot grey.
"""

import math
import os
import sys
import types
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import yaml                                   # noqa: E402
import vehicle_preview as VP                  # noqa: E402
import vehicle_preview_farm as VF             # noqa: E402
import vehicle_misc_preview as VM             # noqa: E402
import vehicle_icons as I0                    # noqa: E402
import vehicle_icons_road as IR               # noqa: E402
import vehicle_icons_farm as IF               # noqa: E402
import vehicle_icons_misc as IM               # noqa: E402
from models import vehicles as V              # noqa: E402
from models import vehicles_road as R         # noqa: E402
from furniture_preview import _ref_models     # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
ITEMS = REPO / "content" / "stsv" / "items"

GROUPS = [("1세대", ["hand_cart", "jige"]), ("2세대", ["cargo_bicycle", "ox_cart"]), ("3세대", ["delivery_scooter", "power_tiller"]),
          ("4세대", ["light_truck", "cargo_van"]), ("5세대", ["box_truck", "tractor_trailer"]), ("특수", ["atv", "fishing_boat"]),
          ("이벤트 (세대 없음)", ["police_car", "golf_cart", "sports_car"])]
ROAD = {"police_car", "light_truck", "golf_cart", "hand_cart", "cargo_bicycle", "delivery_scooter", "atv", "cargo_van", "box_truck", "sports_car"}
FARM = {"ox_cart", "power_tiller", "tractor_trailer"}
ICONS = {**I0.ICONS, **IR.ICONS, **IF.ICONS, **IM.ICONS}

SCALE = float(os.environ.get("STSV_REVIEW_SCALE", 4.0))   # screen px per model px (16 a block): 64 px a block
DIST = 3000.0          # camera distance (model px) for every view: the same (tiny) perspective everywhere
ANGLES = [("앞 왼쪽 3/4 · 탄 사람", -38, 22), ("뒤 오른쪽 3/4 · 짐 가득", 142, 24)]
PAD = 14               # px around each view's picture
HEAD = 30              # a vehicle's label strip
ICON_W = 96            # the icons' column
BG = (27, 29, 34)


def all_items():
    out = {}
    for f in sorted(ITEMS.glob("vehicles*.yml")):
        out.update(yaml.safe_load(f.read_text()))
    return out


def ko(vid):
    return all_items()[vid]["name"].split(">")[-1]


# vehicle_preview's helpers read their vehicles from these: every road vehicle (vehicles.yml + vehicles_road.yml)
VP.items = lambda: {**yaml.safe_load((ITEMS / "vehicles.yml").read_text()), **yaml.safe_load((ITEMS / "vehicles_road.yml").read_text())}
VP.V = types.SimpleNamespace(ALL={**V.ALL, **R.ALL})


def _solid(rgb):
    return VP._solid(rgb)


def assemble(vid, ox, view):
    """(textures, elements, extra elements not counted in the picture's extent) of one vehicle at drawn offset ox.
    view 0: riders, empty; view 1: no riders, the last load stage."""
    riders, full = view == 0, view == 1
    if vid in ROAD:
        spec = VP.items()[vid]["vehicle"]
        stage = len(spec["cargo"]["stages"]) if full and spec.get("cargo", {}).get("stages") else None
        pose = "pulled" if "pull" in spec else "rest"
        t, e, spec = VP.vehicle(vid, ox, cargo=stage, riders=riders, steer=0, pose=pose)
        t = dict(t)
        for sp in spec.get("spinners", []):
            pt, pe = VP.part(sp["model"], sp["at"], ox)
            t.update(pt)
            e = e + pe
        return t, e, [], spec["size"][1]
    if vid in FARM:
        t, e, spec, units = VF.train(vid, ox, cargo=4 if full else None, riders=riders)
        return t, e, [], max([spec["size"][1]] + [tr["size"][1] for tr in spec["train"]["trailers"]])
    if vid == "fishing_boat":
        spec = VM.items()["fishing_boat"]["vehicle"]
        draft = spec["water"]["draft"] * 16
        t, e, spec = VM.boat((ox[0], -draft, ox[2]), cargo=4 if full else None, riders=riders)
        L, W, _ = spec["size"]
        water = VM._box((ox[0] - W * 8 - 40, -12, ox[2] - L * 8 - 30), (ox[0] + W * 8 + 40, 0, ox[2] + L * 8 + 30), "water")
        water["opacity"] = 0.55
        return {**t, "water": VM._water_tex()}, e, [water], W
    if vid == "jige":
        t, e = VM.worn(3 if full else 0, ox)
        return t, e, [], 1
    raise KeyError(vid)


# ------------------------------------------------------------------------------------------------
# Projection (to size and centre each picture before rendering)
# ------------------------------------------------------------------------------------------------

def _rotate(p, rot):
    axis, ang, o = rot
    a = math.radians(ang)
    c, s = math.cos(a), math.sin(a)
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    if axis == "x":
        y, z = y * c - z * s, y * s + z * c
    elif axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def corners(els):
    for e in els:
        a, b = e["from"], e["to"]
        for i in range(8):
            p = (b[0] if i & 1 else a[0], b[1] if i & 2 else a[1], b[2] if i & 4 else a[2])
            if e.get("rotation"):
                p = _rotate(p, e["rotation"])
            for r in e.get("post", []):
                p = _rotate(p, r)
            yield p


def basis(az, el):
    az, el = math.radians(az), math.radians(el)
    zc = (math.sin(az) * math.cos(el), math.sin(el), -math.cos(az) * math.cos(el))       # camera -> back
    right = (zc[2], 0.0, -zc[0])                                                         # up x zc
    n = math.hypot(right[0], right[2])
    right = (right[0] / n, 0.0, right[2] / n)
    up = (zc[1] * right[2] - zc[2] * right[1], zc[2] * right[0] - zc[0] * right[2], zc[0] * right[1] - zc[1] * right[0])
    return zc, right, up


def extent(els, az, el):
    """(left, right, bottom, top, mean depth) of the elements on the screen of a view (model px)."""
    zc, r, u = basis(az, el)
    xs, ys, zs = [], [], []
    for p in corners(els):
        xs.append(p[0] * r[0] + p[1] * r[1] + p[2] * r[2])
        ys.append(p[0] * u[0] + p[1] * u[1] + p[2] * u[2])
        zs.append(p[0] * zc[0] + p[1] * zc[1] + p[2] * zc[2])
    return min(xs), max(xs), min(ys), max(ys), sum(zs) / len(zs)


def view_for(els, az, el, x, y, w, h):
    """A render view of the given screen rect whose picture is the elements' extent, centred, at SCALE."""
    zc, r, u = basis(az, el)
    x0, x1, y0, y1, sz = extent(els, az, el)
    sx, sy = (x0 + x1) / 2, (y0 + y1) / 2
    # the look-at point: the picture's middle, at the elements' mean depth (the camera stands DIST in front of it)
    c = [r[i] * sx + u[i] * sy + zc[i] * sz for i in range(3)]
    fov = math.degrees(2 * math.atan(h / SCALE / 2 / DIST))
    return {"x": x, "y": y, "w": w, "h": h, "az": az, "el": el, "k": 1.0, "radius": DIST, "fov": fov, "near": DIST - 400, "far": DIST + 400,
            "center": c}


def size_of(els, az, el):
    x0, x1, y0, y1, _ = extent(els, az, el)
    return math.ceil((x1 - x0) * SCALE) + 2 * PAD, math.ceil((y1 - y0) * SCALE) + 2 * PAD


# ------------------------------------------------------------------------------------------------
# Building the scene
# ------------------------------------------------------------------------------------------------

def scene_parts(vids):
    """Per vehicle and view: (textures, elements counted, elements extra). Each vehicle-view far apart in x."""
    player, _ = _ref_models()
    base = {**player["textures"], "ref_rider": _solid((222, 152, 60)), "ref_passenger": _solid((120, 160, 120)),
            "ref_wearer": _solid((222, 152, 60))}
    parts = {}
    k = 0
    for vid in vids:
        for v in range(2):
            ox = (k * 4000, 0, 0)
            k += 1
            t, e, extra, width = assemble(vid, ox, v)
            if vid != "jige":
                # a standing player beside the vehicle's middle, on the side away from the camera's near corner
                side = -1 if v == 0 else 1
                xs = [p[0] for p in corners(e)]
                edge = (min(xs) if side < 0 else max(xs)) - ox[0]
                zs = [p[2] for p in corners(e)]
                zmid = (min(zs) + max(zs)) / 2
                px = ox[0] + edge + side * 22
                e = e + VP._xf(player["elements"], lambda p, px=px, zmid=zmid: [p[0] + px, p[1], p[2] + zmid])
            parts[(vid, v)] = ({**base, **t}, e, extra)
    return parts


def render_cells(vids):
    """Each vehicle's two views as images (one render per vehicle; render.mjs shares one page file, so one at a time)."""
    parts = scene_parts(vids)
    jobs = {}
    for vid in vids:
        textures, elements, views, rects, x = {}, [], [], [], 0
        for v in range(2):
            t, e, extra = parts[(vid, v)]
            textures.update(t)
            elements += e + extra
            az, el = ANGLES[v][1:]
            w, h = size_of(e, az, el)
            views.append(view_for(e, az, el, x, 0, w, h))
            rects.append((x, 0, w, h))
            x += w
        H = max(r[3] for r in rects)
        jobs[vid] = ({"textures": textures, "elements": elements, "grid": False, "size": [x, H], "views": views}, rects)

    def one(vid):
        data, rects = jobs[vid]
        im = VP.render(data, "review_" + vid)
        return vid, [im.crop((x, y, x + w, y + h)) for (x, y, w, h) in rects]

    return dict(one(v) for v in vids)


def icon_block(vid):
    """The icon at 16 px (real) and 4x, on the inventory slot grey."""
    icon = ICONS[vid]()
    im = Image.new("RGB", (ICON_W, 64 + 16 + 30), BG)
    big = Image.new("RGBA", (64, 64), (139, 139, 139, 255))
    big.alpha_composite(icon.resize((64, 64), Image.NEAREST))
    im.paste(big.convert("RGB"), (0, 0))
    slot = Image.new("RGBA", (18, 18), (139, 139, 139, 255))
    slot.alpha_composite(icon, (1, 1))
    im.paste(slot.convert("RGB"), (70, 46))
    return im


def fonts():
    return ImageFont.truetype(VP.FONT, 20), ImageFont.truetype(VP.FONT, 15)


def sheet(out):
    vids = [v for _, g in GROUPS for v in g]
    cells = render_cells(vids)
    font, small = fonts()
    # rows: one per generation; a vehicle block = label + two views + its icons
    blocks = {}
    for vid in vids:
        f, r = cells[vid]
        w = f.width + r.width + ICON_W + 12
        h = HEAD + max(f.height, r.height)
        blocks[vid] = (w, h)
    G = 120                      # the generation label's column
    rows = []
    for g, members in GROUPS:
        rows.append((g, members, sum(blocks[v][0] for v in members) + 16 * (len(members) - 1), max(blocks[v][1] for v in members)))
    W = G + max(r[2] for r in rows) + 10
    top = 64
    H = top + sum(r[3] + 18 for r in rows)
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((14, 10), "탈것 전체 점검 시트 · 모두 같은 축척 (1블록 = 64px) · 같은 각도: 앞 왼쪽 3/4 (탄 사람: 주황 운전자, 초록 동승자) · "
           "뒤 오른쪽 3/4 (짐 가득, 사람 없음)", font=small, fill=(235, 232, 222))
    d.text((14, 32), "회색 = 서 있는 사람 (1.875칸) · 통통배는 흘수 6px 로 물(반투명)에 뜸 · 지게는 사람 크기 마네킹이 짐 (앞: 빈 지게, 뒤: 가득) · "
           "아이콘: 4배 + 실제 16px (슬롯 회색)", font=small, fill=(180, 180, 188))
    y = top
    for g, members, _, rh in rows:
        d.line((0, y - 9, W, y - 9), fill=(52, 56, 66), width=1)
        for i, line in enumerate(g.split(" ", 1)):
            d.text((14, y + 8 + i * 24), line, font=font if i == 0 else small, fill=(255, 214, 120))
        x = G
        for vid in members:
            f, r = cells[vid]
            w, h = blocks[vid]
            d.rectangle((x, y, x + w, y + HEAD - 4), fill=(44, 47, 56))
            d.text((x + 8, y + 4), f"{ko(vid)}  ({vid})", font=small, fill=(245, 242, 232))
            im.paste(f, (x, y + HEAD))
            im.paste(r, (x + f.width, y + HEAD))
            d.text((x + 8, y + HEAD + 2), "앞 · 탄 사람", font=small, fill=(150, 150, 158))
            d.text((x + f.width + 8, y + HEAD + 2), "뒤 · 짐 가득", font=small, fill=(150, 150, 158))
            im.paste(icon_block(vid), (x + f.width + r.width + 6, y + HEAD + 8))
            x += w + 16
        y += rh + 18
    im.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}  {im.size}")


def save_cells(dir_, vids):
    dir_ = Path(dir_)
    dir_.mkdir(parents=True, exist_ok=True)
    cells = render_cells(vids)
    for vid, (f, r) in cells.items():
        im = Image.new("RGB", (f.width + r.width + ICON_W + 12, max(f.height, r.height)), BG)
        im.paste(f, (0, 0))
        im.paste(r, (f.width, 0))
        im.paste(icon_block(vid), (f.width + r.width + 6, 8))
        im.save(dir_ / f"{vid}.png")
        print(f"cell    {dir_ / (vid + '.png')}")


def pair_sheet(before, after, out, vids):
    """Before (left) and after (right) of each vehicle, one per row."""
    font, small = fonts()
    rows = []
    for vid in vids:
        b, a = Image.open(Path(before) / f"{vid}.png"), Image.open(Path(after) / f"{vid}.png")
        rows.append((vid, b, a))
    W = max(20 + b.width + 30 + a.width + 20 for _, b, a in rows)
    H = 50 + sum(HEAD + max(b.height, a.height) + 14 for _, b, a in rows)
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((14, 14), "탈것 디테일 점검 · 고친 것만 · 왼쪽 = 전, 오른쪽 = 후 · 같은 축척과 각도 (vehicles_all.png 와 같음)",
           font=small, fill=(235, 232, 222))
    y = 50
    for vid, b, a in rows:
        xb, xa = 20, 20 + b.width + 30
        d.rectangle((0, y, W, y + HEAD - 4), fill=(44, 47, 56))
        d.text((xb, y + 4), f"{ko(vid)} ({vid}) · 전", font=small, fill=(245, 242, 232))
        d.text((xa, y + 4), "후", font=small, fill=(140, 220, 150))
        im.paste(b, (xb, y + HEAD))
        im.paste(a, (xa, y + HEAD))
        d.line((xa - 15, y + HEAD, xa - 15, y + HEAD + max(b.height, a.height)), fill=(70, 74, 84), width=2)
        y += HEAD + max(b.height, a.height) + 14
    im.save(out, optimize=True)
    print(f"sheet   {out}  {im.size}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--cells":
        save_cells(sys.argv[2], sys.argv[3:] or [v for _, g in GROUPS for v in g])
    elif len(sys.argv) > 1 and sys.argv[1] == "--pair":
        pair_sheet(sys.argv[2], sys.argv[3], Path(sys.argv[4]), sys.argv[5:])
    else:
        sheet(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicles_all.png")
