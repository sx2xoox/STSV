"""
편의점 알바 assets (docs/plans/편의점_기획안.md §15): writes only the convenience store's own files, and its preview
sheet.

    python3 tools/assetgen/store_assets.py            # models + equipment + skins + docs/previews/store_assets.png, store_assets_3.png
    python3 tools/assetgen/store_assets.py --no-sheet # models + equipment + the two store NPC skins only
    python3 tools/assetgen/store_assets.py --sheet3-only  # docs/previews/store_assets_3.png only (nothing in the pack touched)
    python3 tools/assetgen/store_assets.py --sheet4-only  # docs/previews/store_assets_4.png only (§17: held things, states, goods)
    (the sheet needs: cd tools/assetgen/render && npm install)

  models     economy/content/economy/models/store_*.bbmodel (models/store.py; economy_models.ALL has them too, so a
             full economy_build.py writes the same files)
  equipment  economy/content/economy/assets/economy/equipment/store_vest.json and the armour layer
             assets/economy/textures/entity/equipment/humanoid/store_vest.png (store_art.vest_layer): store_vest worn
             in the chest slot (items/store.yml equipment: { slot: chest, asset: economy:store_vest })
  3차 (§16)  models store_license_<alley|standard|large>, store_id_card, store_vest_manager, store_vest_night,
             store_order_tablet (models/store.py ALL3); equipment store_vest_manager.json / store_vest_night.json and
             their layers (store_art.vest_layer_manager / vest_layer_night); the skins economy:entity/bus/hq_inspector
             and night_regular (economy_bus_skins.STORE_NPCS: only these two are written, every other skin is left alone)
  sheet3     docs/previews/store_assets_3.png: the 3차 pieces in a store, held and worn, close, and flat
  sheet      docs/previews/store_assets.png: a store laid out (the counter, the cigarette wall, shelves, fridges,
             the eat bar, the sign), then every piece on its own: the twelve shelves (4 kinds x full / half / empty,
             both sides), the fridges and the freezer, the appliances and props, the 33 goods, the boxes, money and
             props, the keys, the scanner and the vest (and its armour layer flat)
"""

import base64
import io
import json
import math
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
REPO = HERE.parent.parent
PACK = REPO / "economy" / "content" / "economy"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
SHEET = REPO / "docs" / "previews" / "store_assets.png"
SHEET3 = REPO / "docs" / "previews" / "store_assets_3.png"
FONT = HERE / "fonts" / "Sunflower-Medium.ttf"
EQUIPMENT = PACK / "assets" / "economy" / "equipment" / "store_vest.json"
VEST_LAYER = PACK / "assets" / "economy" / "textures" / "entity" / "equipment" / "humanoid" / "store_vest.png"
# 3차: the 점장 and 야간 vests (equipment economy:store_vest_manager / economy:store_vest_night, the same pattern)
VESTS3 = ("store_vest_manager", "store_vest_night")


def write():
    from models import store as S
    import store_art as ART
    (PACK / "models").mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(exist_ok=True)
    for name, fn in S.ALL.items():
        fn().write(PACK / "models" / f"{name}.bbmodel", BUILD / f"eco_{name}.preview.json")
    EQUIPMENT.parent.mkdir(parents=True, exist_ok=True)
    EQUIPMENT.write_text(json.dumps({"layers": {"humanoid": [{"texture": "economy:store_vest"}]}}, indent=2) + "\n")
    VEST_LAYER.parent.mkdir(parents=True, exist_ok=True)
    ART.vest_layer().save(VEST_LAYER)
    layers = {"store_vest_manager": ART.vest_layer_manager, "store_vest_night": ART.vest_layer_night}
    for v in VESTS3:
        (EQUIPMENT.parent / f"{v}.json").write_text(json.dumps({"layers": {"humanoid": [{"texture": f"economy:{v}"}]}}, indent=2) + "\n")
        layers[v]().save(VEST_LAYER.parent / f"{v}.png")
    # the store's two NPC skins only (economy_bus_skins.STORE_NPCS): every other skin is left alone
    import economy_bus_skins as B
    B.OUT.mkdir(parents=True, exist_ok=True)
    for key in B.STORE_NPCS:
        B.NPCS[key]().finish().save(B.OUT / f"{key}.png")
    return list(S.ALL)


# ------------------------------------------------------------------------------------------------
# the sheet
# ------------------------------------------------------------------------------------------------

def _font(size):
    return ImageFont.truetype(str(FONT), size)


def _uri(im):
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def _placed(pv, at, s=1.0, turn=0):
    """Model elements as a display (or a furniture of scale s) shows them, the model's (8, 8, 8) at `at`; turn: the
    whole piece turned about y by that many degrees (about `at`)."""
    out = []
    for e in pv["elements"]:
        e = json.loads(json.dumps(e))
        e["from"] = [at[i] + (e["from"][i] - 8) * s for i in range(3)]
        e["to"] = [at[i] + (e["to"][i] - 8) * s for i in range(3)]
        if e.get("rotation"):
            axis, ang, o = e["rotation"]
            e["rotation"] = [axis, ang, [at[i] + (o[i] - 8) * s for i in range(3)]]
        if turn:
            e["post"] = [["y", turn, list(at)]]
        out.append(e)
    return out


def _plane(name, x0, z0, x1, z1, y=0.0):
    return {"from": [x0, y - 1, z0], "to": [x1, y, z1], "shade": True, "rotation": None,
            "faces": {"up": {"uv": [0, 0, 16, 16], "texture": name}}}


def _wall(name, x0, x1, y0, y1, z):
    return {"from": [x0, y0, z], "to": [x1, y1, z + 1], "shade": True, "rotation": None,
            "faces": {"north": {"uv": [0, 0, 16, 16], "texture": name}}}


def _floor_tex():
    """The store's floor (big pale tiles), its wall (a soft green-grey paint), the pavement outside, a felt mat."""
    from stsv_assets.palette import ramp
    tile = Image.new("RGBA", (16, 16))
    wall = Image.new("RGBA", (16, 16))
    pave = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = ramp("pearl", 2 if x in (0, 8) or y in (0, 8) else (3 if (x * 7 + y * 3) % 17 else 2))
            tile.putpixel((x, y), tuple(c[:3]) + (255,))
            w = ramp("celadon", 3 if (x * 5 + y * 3) % 11 else 2)
            wall.putpixel((x, y), tuple(w[:3]) + (255,))
            p = ramp("stone", 1 if x in (0, 8) or y == 0 else 2)
            pave.putpixel((x, y), tuple(p[:3]) + (255,))
    return {"tile": _uri(tile), "wallbg": _uri(wall), "pave": _uri(pave)}


def sheet(path=SHEET):
    from models import store as S
    import store_art as ART
    BUILD.mkdir(exist_ok=True)
    pv = {k: fn().to_preview() for k, fn in S.ALL.items()}
    tex = _floor_tex()
    for p in pv.values():
        tex.update(p["textures"])
    els, views, labels = [], [], []
    W = 1600

    def view(x, y, w, h, centre, radius, az, el, k, label, fov=35):
        views.append({"x": x, "y": y, "w": w, "h": h, "az": az, "el": el, "k": k, "center": centre, "radius": radius,
                      "fov": fov, "far": 6000})
        labels.append((x, y, label))

    # row 1: a store laid out (cells of 16; the cigarette wall and the fridges against the back wall at z 64)
    y0 = 0
    O = (0, 0)

    def put(k, cx, cz, s=1, turn=0):
        """A furniture with its anchor cell's centre at (cx, cz) (preview px; the floor at y 0)."""
        els.extend(_placed(pv[k], (O[0] + cx, 8 if s == 1 else 8, O[1] + cz), s, turn))
    put("store_cig_shelf", 0, 56)
    put("store_counter", 0, 24)
    put("store_microwave", 40, 56)
    put("store_kettle", 56, 56)
    put("store_hanger", 72, 56)
    put("store_fridge_full", -72, 56)
    put("store_fridge_half", -104, 56)
    put("store_shelf_snack_full", -56, 8)
    put("store_shelf_noodle_full", -88, 8)
    put("store_shelf_daily_half", -120, 8)
    put("store_freezer", 40, -24)
    put("store_eat_bar", -88, -40, turn=180)
    put("store_waste", 88, 40)
    put("store_mop", 88, 24)
    els.append(_plane("tile", -136, -56, 104, 64))
    els.append(_wall("wallbg", -136, 104, 0, 64, 64))
    els.extend(_placed(pv["store_sign"], (-40 + 8, 44, 64 - 8), 2))
    view(0, y0, W, 760, [-16, 14, 12], 100, 18, 34, 1.8, "편의점 배치 (손님 쪽에서): 냉장 · 담배장 · 계산대 · 전자레인지 · 온수기 · 조끼 걸이 · 진열대 · 냉동고 · 시식대 · 간판")

    # row 2-4: the twelve shelves, each from its west side (and the full ones' east side)
    y0 = 760
    for r, state in enumerate(S.STATES):
        for i, cat in enumerate(S.SHELF_KINDS):
            k = f"store_shelf_{cat}_{state}"
            ax = 1000 + i * 120 + r * 600
            els.extend(_placed(pv[k], (ax, 8, 0), 1))
            els.append(_plane("tile", ax - 30, -24, ax + 24, 40))
            view(i * 400, y0 + r * 360, 400, 360, [ax - 4, 15, 8], 33, -58, 18, 2.35, f"{k}")
    # row 5: the fridges and the freezer
    y0 = 760 + 3 * 360
    for i, k in enumerate(("store_fridge_full", "store_fridge_half", "store_fridge_empty", "store_freezer")):
        ax = 3000 + i * 100
        els.extend(_placed(pv[k], (ax, 8, 0), 1))
        els.append(_plane("tile", ax - 20, -30, ax + 44, 20))
        els.append(_wall("wallbg", ax - 20, ax + 44, 0, 44, 8))
        view(i * 400, y0, 400, 400, [ax + 8, 16 if "freezer" not in k else 6, 0], 30 if "freezer" not in k else 22, 18,
             10 if "freezer" not in k else 40, 2.0, k)
    # row 6: the counter, the cigarette wall, the sign
    y0 += 400
    els.extend(_placed(pv["store_counter"], (3500, 8, 0), 1))
    els.append(_plane("tile", 3470, -30, 3540, 40))
    view(0, y0, 560, 380, [3500, 10, 8], 30, 30, 32, 1.8, "store_counter · 3×1 (손님 쪽)")
    view(560, y0, 440, 380, [3500, 12, 8], 30, 160, 34, 1.8, "store_counter (점원 쪽)")
    els.extend(_placed(pv["store_cig_shelf"], (3700, 8, 0), 1))
    els.append(_plane("tile", 3670, -30, 3740, 24))
    view(1000, y0, 600, 380, [3708, 16, 8], 30, 15, 12, 1.9, "store_cig_shelf · 3×1, 높이 2")
    y0 += 380
    els.extend(_placed(pv["store_sign"], (3950, 8, 0), 2))
    els.append(_wall("wallbg", 3880, 4020, -10, 30, 16))
    view(0, y0, 900, 260, [3958, 8, 8], 44, 8, 6, 1.45, "store_sign · GS26 5×1 벽걸이 (빛)")
    els.extend(_placed(pv["store_parasol"], (4200, 8, 0), 2))
    els.append(_plane("pave", 4160, -40, 4256, 56))
    view(900, y0, 700, 520, [4208, 22, 8], 46, 28, 20, 2.0, "store_parasol · 3×1 · 좌석 2")
    view(0, y0 + 260, 900, 260, [1000 + 8, 15, 8], 30, 62, 14, 1.5, "store_shelf_snack_full (+x 쪽)")
    # row 7: the appliances and props
    y0 += 520
    props = [("store_microwave", "전자레인지"), ("store_kettle", "온수기"), ("store_hanger", "조끼 걸이"),
             ("store_waste", "폐기 바구니"), ("store_mop", "대걸레 통")]
    for i, (k, label) in enumerate(props):
        ax = 4600 + i * 60
        els.extend(_placed(pv[k], (ax, 8, 0), 1))
        els.append(_plane("tile", ax - 24, -24, ax + 24, 24))
        tall = k in ("store_hanger", "store_kettle")
        view(i * 200, y0, 200, 340, [ax, 15 if tall else 9, 8], 22 if tall else 15, 18, 20, 4.0, label)
    els.extend(_placed(pv["store_eat_bar"], (5000, 8, 0), 1))
    els.append(_plane("tile", 4970, -24, 5040, 24))
    els.append(_wall("wallbg", 4970, 5040, 0, 30, 16))
    view(1000, y0, 600, 340, [5008, 8, 6], 28, 20, 26, 1.9, "store_eat_bar · 3×1 · 좌석 3")

    # row 8: the goods on a counter top, three rows of eleven
    y0 += 340
    goods = list(S.GOODS)
    for r in range(3):
        bx = 6000 + r * 400
        for i, g in enumerate(goods[r * 11:(r + 1) * 11]):
            els.extend(_placed(pv[f"store_item_{g}"], (bx - i * 10, 0, 0), 1))
        els.append(_plane("tile", bx - 112, -14, bx + 12, 14))
        view(0, y0 + r * 230, W, 230, [bx - 50, 3.5, 0], 19, 0, 20, 4.3, fov=14, label=
             ("store_item_" if r == 0 else "") + " · ".join(goods[r * 11:(r + 1) * 11]))
    y0 += 690 - 520
    # row 9: boxes, bag, spill, cup, card, money, scanner, vest
    y0 += 520
    misc = [f"store_box_{k}" for k in S.BOX_KIND] + ["store_bag", "store_trash_cup", "store_scanner", "store_vest"]
    for i, k in enumerate(misc):
        els.extend(_placed(pv[k], (7000 - i * 15, 0 if k != "store_scanner" else 5, 0), 1))
    els.append(_plane("tile", 6860, -14, 7012, 14))
    view(0, y0, W, 300, [7000 - 4 * 15, 5, 0], 20, 14, 22, 3.4, "store_box_snack · noodle · daily · meal · drink · store_bag · store_trash_cup · store_scanner · store_vest", fov=20)
    y0 += 300
    flats = ["store_card", "store_cash_1000", "store_cash_5000", "store_cash_10000", "store_cash_50000", "store_coin_100", "store_coin_500"]
    for i, k in enumerate(flats):
        els.extend(_placed(pv[k], (7400 - i * 9, 0, 0), 1))
    els.extend(_placed(pv["store_spill"], (7330, 0, -2), 1))
    els.append(_plane("tile", 7300, -14, 7410, 14))
    view(0, y0, W, 260, [7358, 0, 0], 16, 0, 50, 3.4, "store_card · store_cash_1000 · 5000 · 10000 · 50000 · store_coin_100 · 500 · store_spill", fov=20)
    # row 10: the keys
    y0 += 260
    keys = [f"store_btn_{k}" for k in ART.BUTTON_WORDS] + [f"store_btn_change_{n}" for n in ART.CHANGE]
    for i, k in enumerate(keys):
        r, c = divmod(i, 10)
        els.extend(_placed(pv[k], (8000 - c * 13, 10 - r * 8, 0), 1))
    view(0, y0, W, 260, [8000 - 4.5 * 13, 6, 0], 22, 0, 4, 3.0, "store_btn_* (빛남) · store_btn_change_*", fov=20)
    H = y0 + 260

    src = BUILD / "store_assets.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "size": [W, H], "views": views}))
    shot = BUILD / "store_assets.render.png"
    subprocess.run(["node", str(RENDER), str(src), str(shot)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(shot).convert("RGBA")

    extra = 300
    out = Image.new("RGBA", (W, H + extra), (27, 29, 34, 255))
    out.alpha_composite(im, (0, 0))
    d = ImageDraw.Draw(out)
    f, small = _font(19), _font(15)
    for (x, y, label) in labels:
        tw = d.textlength(label, font=f)
        d.rectangle((x + 8, y + 8, x + 20 + tw, y + 38), fill=(20, 22, 26, 210))
        d.text((x + 14, y + 10), label, font=f, fill=(240, 236, 220))
    # the vest's armour layer, flat, and the cell plans
    d.text((20, H + 14), "store_vest 갑옷 층 (economy:store_vest, 64×32)", font=small, fill=(240, 236, 220))
    out.alpha_composite(ART.vest_layer().resize((384, 192), Image.NEAREST), (20, H + 44))
    _plans(d, 460, H + 14, f, small)
    out.convert("RGB").save(path, optimize=True)
    return path


def _plans(d, x0, y0, f, small):
    """The cells of the multi-cell pieces from above (north = the front, up; east = +x, right) with their seats."""
    c = 34
    pieces = [("계산대 · 담배장 · 시식대 3×1", [(-1, 0), (0, 0), (1, 0)], [], (-1, 1, 0, 0)),
              ("시식대 좌석 0 1 2", [(-1, 0), (0, 0), (1, 0)], [(-1, 0), (0, 0), (1, 0)], (-1, 1, 0, 0)),
              ("파라솔 좌석 0 1", [(-1, 0), (0, 0), (1, 0)], [(-1, 0), (1, 0)], (-1, 1, 0, 0)),
              ("진열대 1×3 (양쪽 -x · +x)", [(0, -1), (0, 0), (0, 1)], [], (0, 0, -1, 1)),
              ("냉장 · 냉동 2×1", [(0, 0), (1, 0)], [], (0, 1, 0, 0)),
              ("간판 5×1", [(-2, 0), (-1, 0), (0, 0), (1, 0), (2, 0)], [], (-2, 2, 0, 0))]
    x = x0
    for title, cells, seats, (xs, xe, zs, ze) in pieces:
        d.text((x, y0), title, font=small, fill=(240, 236, 220))
        top = y0 + 44
        for (gx, gz) in cells:
            X, Y = x + (gx - xs) * c, top + (gz - zs) * c
            d.rectangle((X, Y, X + c - 2, Y + c - 2), fill=(50, 90, 70) if (gx, gz) == (0, 0) else (60, 64, 74), outline=(90, 94, 104))
        for i, (gx, gz) in enumerate(seats):
            X, Y = x + (gx - xs) * c + c / 2, top + (gz - zs) * c + c / 2
            d.ellipse((X - 12, Y - 12, X + 12, Y + 12), fill=(242, 194, 42), outline=(30, 30, 30))
            d.text((X - 5, Y - 11), str(i), font=small, fill=(20, 20, 20))
        d.text((x, top - 22), "▲ 앞 (-z)", font=small, fill=(255, 210, 90))
        x += max(190, (xe - xs + 1) * c + 40)
    notes = ["진한 칸 = 가구의 기준 칸 (0, 0)", "모델 +x = 앞에서 보아 왼쪽", "표시 모델: 앞 = 북(-z), 바닥 = 위치",
             "버튼 · 스캐너: 위치 = 가운데"]
    for i, t in enumerate(notes):
        d.text((x0, y0 + 160 + i * 24), t, font=small, fill=(200, 204, 212))


# ------------------------------------------------------------------------------------------------
# 3차 (§16 새 모델): the second sheet, docs/previews/store_assets_3.png
# ------------------------------------------------------------------------------------------------

def _turned(els, at, deg):
    """Puppet elements (with "matrix") turned about y through `at` by deg (a figure facing +z instead of -z)."""
    import numpy as np
    a = math.radians(deg)
    r = np.array([[math.cos(a), 0, math.sin(a), 0], [0, 1, 0, 0], [-math.sin(a), 0, math.cos(a), 0], [0, 0, 0, 1]])
    t = np.eye(4)
    t[:3, 3] = at
    ti = np.eye(4)
    ti[:3, 3] = [-v for v in at]
    m = t @ r @ ti
    out = []
    for e in els:
        e = dict(e)
        e["matrix"] = [float(x) for x in (m @ np.array(e["matrix"]).reshape(4, 4)).flatten()]
        out.append(e)
    return out


def _armour(key, slim, part_pose, at):
    """A chest-slot armour layer (64 x 32, texture `key`) on a posed figure: the body box grown by 1 px (the
    humanoid outer armour's inflation), the neck at `at`."""
    import numpy as np
    import economy_bus_skins as B
    import bus_hold_assets as BA
    pivot, org, size = BA.parts(slim)["body"]
    mat = BA._tr(at) @ BA.ROOT @ BA._affine(BA._rzyx(*part_pose["body"]), pivot)
    r = B.rects(16, 16, 8, 12, 4)
    faces = {}
    for f, sf in BA.FACE_OF.items():
        x0, y0, fw, fh = r[sf]
        q = [x0 * 0.25, y0 * 0.5, (x0 + fw) * 0.25, (y0 + fh) * 0.5]
        faces[f] = {"uv": q if sf == "top" else [q[2], q[3], q[0], q[1]], "texture": key}
    g = 1.0
    return [{"from": [org[i] - g for i in range(3)], "to": [org[i] + size[i] + g for i in range(3)], "faces": faces,
             "shade": True, "rotation": None, "matrix": [float(x) for x in mat.flatten()]}]


def _figure(skin_key, slim, at, pvs, main=None, vest=None, turn=0):
    import bus_hold_assets as BA
    pp = BA.pose(False, main, None)
    els = BA.passenger(skin_key, slim, at, False, main, None, pvs)
    if vest:
        els += _armour(vest, slim, pp, at)
    return _turned(els, at, turn) if turn else els


def sheet3(path=SHEET3):
    """The 3차 pieces: the order tablet beside the counter with a licence and an ID card on it, the head office
    inspector and the late regular in front of it, a worker in the 점장 vest behind; the three licences and the ID
    card from above; the licences held up and the three vests worn; the tablet and the vest items close; then the
    skins, the layers and the pictures flat (the ID card's text area marked, a sample of the overlay)."""
    from models import store as S
    import store_art as ART
    import economy_bus_skins as B
    BUILD.mkdir(exist_ok=True)
    keys = list(S.ALL3) + ["store_counter", "store_vest", "store_cig_shelf"]
    models = {k: S.ALL[k]() for k in keys}
    pv = {k: m.to_preview() for k, m in models.items()}
    pvs = {k: (pv[k], models[k].display) for k in keys}
    tex = _floor_tex()
    for p in pv.values():
        tex.update(p["textures"])
    skins = {k: B.NPCS[k]() for k in B.STORE_NPCS}
    worker = B.TYPES[[t for t, _, _ in B.TYPES].index("univ")][2](1)
    tex.update({k: _uri(s.finish()) for k, s in skins.items()})
    tex["worker"] = _uri(worker.finish())
    tex["vest"] = _uri(ART.vest_layer())
    tex["vest_manager"] = _uri(ART.vest_layer_manager())
    tex["vest_night"] = _uri(ART.vest_layer_night())
    els, views, labels = [], [], []
    W = 1600

    def view(x, y, w, h, centre, radius, az, el, k, label, fov=35):
        views.append({"x": x, "y": y, "w": w, "h": h, "az": az, "el": el, "k": k, "center": centre, "radius": radius,
                      "fov": fov, "far": 6000})
        labels.append((x, y, label))

    # row 1: the counter, the tablet beside it (+x: the customer's left), a licence and an ID card on the mat,
    # the worker behind in the 점장 vest, the inspector and the regular in front
    els.extend(_placed(pv["store_cig_shelf"], (0, 8, 40), 1))
    els.extend(_placed(pv["store_counter"], (0, 8, 0), 1))
    els.extend(_placed(pv["store_order_tablet"], (32, 8, 0), 1))
    els.extend(_placed(pv["store_id_card"], (-6, 15.25, 7), 1))
    els.extend(_placed(pv["store_license_standard"], (-18, 15.25, 7), 1, turn=-20))
    els.append(_plane("tile", -60, -56, 64, 56))
    els.append(_wall("wallbg", -60, 64, 0, 64, 52))
    els += _figure("worker", False, (2, 24, 18), pvs, vest="vest_manager")
    els += _figure("hq_inspector", True, (-44, 24, -12), pvs, turn=-105)
    els += _figure("night_regular", False, (-12, 24, -22), pvs, turn=180)
    view(0, 0, 1000, 620, [4, 18, 0], 70, 24, 22, 1.7, "계산대 옆 store_order_tablet · 위에 권리증 · 신분증 / 본사 점검원 · 밤 손님 · 점장 조끼")
    view(1000, 0, 600, 620, [-28, 18, -16], 40, 150, 24, 1.8, "점원 쪽에서: 본사 점검원 · 밤 손님")

    # row 2: the licences and the ID card from above
    y0 = 620
    for i, t in enumerate(S.LICENSES):
        els.extend(_placed(pv[f"store_license_{t}"], (1048 - i * 16, 0, 0), 1))   # +x is the viewer's left
    els.extend(_placed(pv["store_id_card"], (1002, 0, 0), 1))
    els.append(_plane("tile", 985, -14, 1062, 14))
    view(0, y0, W, 420, [1026, 0, 0], 22, 0, 62, 2.3, "store_license_alley · standard · large (골목점 동 · 표준점 은 · 대형점 금) · store_id_card", fov=24)

    # row 3: the licences held up, the three vests worn
    y0 += 420
    for i, t in enumerate(S.LICENSES):
        at = (2000 + i * 40, 24, 0)
        els += _figure("worker", False, at, pvs, main=f"store_license_{t}")
        view(i * 260, y0, 260, 420, [at[0] + 7, 17, -2], 32, 18, 8, 1.9, f"들고 ({t})")
    for i, (v, label) in enumerate((("vest", "store_vest"), ("vest_manager", "store_vest_manager"), ("vest_night", "store_vest_night"))):
        at = (2200 + i * 40, 24, 0)
        els += _figure("worker", False, at, pvs, vest=v)
        view(780 + i * 274, y0, 274, 420, [at[0], 15, 0], 30, 25 if i != 1 else -150, 10, 1.9, label + (" (등)" if i == 1 else ""))

    # row 4: the tablet close, the vest items
    y0 += 420
    els.extend(_placed(pv["store_order_tablet"], (3000, 8, 0), 1))
    els.append(_plane("tile", 2980, -20, 3020, 20))
    view(0, y0, 420, 440, [3000, 13, 2], 16, 20, 30, 3.0, "store_order_tablet · 1×1 (빛나는 화면)")
    view(420, y0, 380, 440, [3000, 11, 2], 16, 120, 14, 3.0, "옆 · 뒤 (발주기 PDA 거치대)")
    els.extend(_placed(pv["store_vest_manager"], (3140, 0, 0), 1))
    els.extend(_placed(pv["store_vest_night"], (3120, 0, 0), 1))
    els.extend(_placed(pv["store_vest"], (3100, 0, 0), 1))
    els.append(_plane("tile", 3085, -14, 3155, 14))
    view(800, y0, 800, 440, [3120, 6, 0], 30, 0, 18, 2.2, "store_vest_manager · store_vest_night · (store_vest)", fov=24)
    H = y0 + 440

    src = BUILD / "store_assets_3.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "size": [W, H], "views": views}))
    shot = BUILD / "store_assets_3.render.png"
    subprocess.run(["node", str(RENDER), str(src), str(shot)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(shot).convert("RGBA")

    extra = 560
    out = Image.new("RGBA", (W, H + extra), (27, 29, 34, 255))
    out.alpha_composite(im, (0, 0))
    d = ImageDraw.Draw(out)
    f, small = _font(19), _font(15)
    for (x, y, label) in labels:
        tw = d.textlength(label, font=f)
        d.rectangle((x + 8, y + 8, x + 20 + tw, y + 38), fill=(20, 22, 26, 210))
        d.text((x + 14, y + 10), label, font=f, fill=(240, 236, 220))
    for yy in (620, 1040, 1460, H):
        d.line((0, yy, W, yy), fill=(60, 64, 72), width=2)
    # the skins flat
    y = H + 14
    for j, (k, title) in enumerate((("hq_inspector", "본사 점검원 hq_inspector (slim)"), ("night_regular", "night_regular (wide)"))):
        sx = 20 + j * 300
        d.text((sx, y), title, font=small, fill=(240, 236, 220))
        sk = skins[k]
        out.alpha_composite(B.flat(sk.finish(), sk.slim).resize((128, 256), Image.NEAREST), (sx, y + 30))
        out.alpha_composite(B.flat(sk.finish(), sk.slim, back=True).resize((128, 256), Image.NEAREST), (sx + 140, y + 30))
    # the vest layers flat
    d.text((620, y), "조끼 갑옷 층 (점장 · 야간)", font=small, fill=(240, 236, 220))
    out.alpha_composite(ART.vest_layer_manager().resize((256, 128), Image.NEAREST), (620, y + 30))
    out.alpha_composite(ART.vest_layer_night().resize((256, 128), Image.NEAREST), (620, y + 170))
    # the ID card picture with its text area and a sample of the overlay
    card = ART.id_card().resize((384, 240), Image.NEAREST)
    cx, cy = 920, y + 30
    out.alpha_composite(card, (cx, cy))
    d.text((cx, y), "store_id_card 그림 · 점선 = 글씨 칸 (TextDisplay)", font=small, fill=(240, 236, 220))
    bx0, by0, bx1, by1 = cx + 8 * 3, cy + 26 * 3, cx + 80 * 3, cy + 70 * 3
    for xx in range(bx0, bx1, 8):
        d.line((xx, by0, xx + 4, by0), fill=(220, 60, 60))
        d.line((xx, by1, xx + 4, by1), fill=(220, 60, 60))
    for yy in range(by0, by1, 8):
        d.line((bx0, yy, bx0, yy + 4), fill=(220, 60, 60))
        d.line((bx1, yy, bx1, yy + 4), fill=(220, 60, 60))
    d.text((bx0 + 12, by0 + 18), "이름  김편의", font=_font(26), fill=(30, 40, 80))
    d.text((bx0 + 12, by0 + 62), "1999년생", font=_font(26), fill=(30, 40, 80))
    d.text((cx, cy + 250), "모델: x 7.2..10.8 · z 6.5..8.7 · 윗면 y 8.25 (북쪽에서 읽음)", font=small, fill=(200, 204, 212))
    # the licence faces flat
    for i, t in enumerate(S.LICENSES):
        out.alpha_composite(ART.license_face(t).resize((160, 208), Image.NEAREST), (20 + i * 176, y + 300))
    d.text((20, y + 516), "권리증 그림 (80×104) · 왼쪽 아래 = 모델의 메달 · 리본 자리", font=small, fill=(200, 204, 212))
    out.alpha_composite(ART.tablet_screen().resize((264, 192), Image.NEAREST), (620, y + 360 - 10))
    d.text((900, y + 360), "발주 화면 (88×64, 빛남)", font=small, fill=(200, 204, 212))
    out.convert("RGB").save(path, optimize=True)
    return path


# ------------------------------------------------------------------------------------------------
# §17 행동을 눈으로: the fourth sheet, docs/previews/store_assets_4.png
# ------------------------------------------------------------------------------------------------

SHEET4 = REPO / "docs" / "previews" / "store_assets_4.png"
EYE_PX = 1.62 * 16                       # a standing player's eye over the floor (px)
FP_BASE = (8.96, -8.32, -11.52)          # the right hand's item point in first person (px, the camera frame)


def _eye(x, y, w, h, eye, at, fov=70):
    """A view from eye (px) looking at at (px), as a player sees it (FOV 70)."""
    d = [eye[i] - at[i] for i in range(3)]
    dist = math.sqrt(sum(v * v for v in d))
    return {"x": x, "y": y, "w": w, "h": h, "az": math.degrees(math.atan2(d[0], -d[2])), "el": math.degrees(math.asin(d[1] / dist)),
            "k": 1.0, "radius": dist, "center": list(at), "fov": fov, "near": 0.8, "far": 6000}


def sheet4(path=SHEET4):
    """§17: the held things on a worker from in front and over the shoulder (F5) and in first person; the furniture's
    states side by side; the props; the 33 re-modelled goods on the counter from a customer's eye (1.62 over the
    floor, 1.5 blocks away, at the 0.75 the store shows them); the counter's POS screen from the worker's eye and the
    card terminal and customer display from the customer's (the screens' tilt)."""
    import numpy as np
    import bus_hold_assets as BA
    from models import store as S
    import store_art as ART
    import economy_bus_skins as B
    BUILD.mkdir(exist_ok=True)
    held = ["store_held_mop", "store_held_box_snack", "store_held_box_drink", "store_held_jug", "store_held_waste_bag",
            "store_held_tray", "store_held_pda", "store_held_id"]
    states = [["store_microwave", "store_microwave_open", "store_microwave_run", "store_microwave_done"],
              ["store_kettle", "store_kettle_low", "store_kettle_empty", "store_hanger", "store_hanger_empty"],
              ["store_order_tablet"] + [f"store_order_tablet_{g}" for g in ("snack", "noodle", "daily", "meal", "drink", "sent")]]
    props = ["store_spill", "store_spill_2", "store_spill_3", "store_wet_sign", "store_trash_cup", "store_item_ramen_red"]
    boxes = [f"store_box_{k}" for k in S.BOX_KIND] + [f"store_box_{k}_open" for k in S.BOX_KIND]
    keys = set(held + sum(states, []) + props + boxes + ["store_counter", "store_cig_shelf", "store_btn_reset", "store_btn_no"]
               + [f"store_item_{g}" for g in S.GOODS])
    models = {k: S.ALL[k]() for k in keys}
    pv = {k: m.to_preview() for k, m in models.items()}
    pvs = {k: (pv[k], models[k].display) for k in keys}
    tex = _floor_tex()
    for p in pv.values():
        tex.update(p["textures"])
    worker = B.TYPES[[t for t, _, _ in B.TYPES].index("univ")][2](1)
    tex["worker"] = _uri(worker.finish())
    tex["vest"] = _uri(ART.vest_layer())
    els, views, labels = [], [], []
    W = 1600

    def view(v, label):
        views.append(v)
        labels.append((v["x"], v["y"], label))

    def cell(k, x, y, w, h, at, s=1.0, az=22, el=18, k_=2.4, lift=0.0):
        """One piece in its own view: its bounds framed (the piece placed at at, display convention)."""
        lo = [min(min(e["from"][q], e["to"][q]) for e in pv[k]["elements"]) for q in range(3)]
        hi = [max(max(e["from"][q], e["to"][q]) for e in pv[k]["elements"]) for q in range(3)]
        c = [at[q] + ((lo[q] + hi[q]) / 2 - 8) * s for q in range(3)]
        c[1] += lift
        r = max(hi[q] - lo[q] for q in range(3)) * s * 0.62 + 2
        view({"x": x, "y": y, "w": w, "h": h, "az": az, "el": el, "k": k_, "center": c, "radius": r, "fov": 35, "far": 6000},
             k.replace("store_", ""))

    # rows 1-2: the held things, worn by a worker: from in front (3/4) and over the shoulder (F5)
    y0 = 0
    cw = W // len(held)
    for i, k in enumerate(held):
        at = (i * 70, 24, 0)
        els.extend(_figure("worker", False, at, pvs, main=k, vest="vest"))
        els.append(_plane("tile", at[0] - 30, -30, at[0] + 30, 30))
        view({"x": i * cw, "y": y0, "w": cw, "h": 330, "az": -28, "el": 10, "k": 2.35, "center": [at[0] - 1, 14, -4], "radius": 26,
              "fov": 35, "far": 6000}, k.replace("store_held_", ""))
        view({"x": i * cw, "y": y0 + 330, "w": cw, "h": 300, "az": 150, "el": 32, "k": 2.2, "center": [at[0] + 2, 12, -3], "radius": 26,
              "fov": 35, "far": 6000}, "F5 (뒤에서)" if i == 0 else "")
    # row 3: first person (the camera frame, FOV 70, 16:9; the right hand's item point and firstperson_righthand)
    y0 += 630
    for i, k in enumerate(held):
        off = np.array([4000.0 + i * 300, 0, 0])
        M = BA._tr(off + np.array(FP_BASE)) @ BA.display_matrix(models[k].display["firstperson_righthand"], False)
        for e in pv[k]["elements"]:
            e = json.loads(json.dumps(e))
            e["matrix"] = [float(x) for x in M.flatten()]
            els.append(e)
        els.append(_plane("tile", off[0] - 120, -160, off[0] + 120, 0, y=-EYE_PX))
        r, c = divmod(i, 4)
        view({"x": c * 400, "y": y0 + r * 225, "w": 400, "h": 225, "az": 180, "el": 0, "k": 1.0, "center": [off[0], 0, -10],
              "radius": 10, "fov": 70, "near": 0.5, "far": 6000}, "1인칭: " + k.replace("store_held_", ""))
    y0 += 450

    # row 4: the furniture's states, each in its own view (a wall behind, as they stand)
    n = 0
    for r, row in enumerate(states):
        w = W // len(row)
        h = 300 if r != 2 else 250
        for i, k in enumerate(row):
            ax = 6000 + n * 60
            n += 1
            els.extend(_placed(pv[k], (ax, 8, 0), 1))
            els.append(_plane("tile", ax - 28, -28, ax + 28, 16))
            els.append(_wall("wallbg", ax - 28, ax + 28, 0, 40, 16))
            cell(k, i * w, y0, w, h, (ax, 8, 0), az=24, el=16, k_=2.3 if r != 2 else 2.6)
        y0 += h
    # row 5: the props (the spill's stages, the sign, the cups) and the boxes, closed and opened
    for i, k in enumerate(props):
        ax = 7400 + i * 60
        els.extend(_placed(pv[k], (ax, 0, 0), 1))
        els.append(_plane("tile", ax - 28, -28, ax + 28, 28))
        cell(k, i * (W // len(props)), y0, W // len(props), 240, (ax, 0, 0), az=18, el=34, k_=2.6)
    y0 += 240
    for i, k in enumerate(boxes):
        ax = 7900 + i * 60
        els.extend(_placed(pv[k], (ax, 0, 0), 1))
        els.append(_plane("tile", ax - 28, -28, ax + 28, 28))
        r, c = divmod(i, 5)
        cell(k, c * (W // 5), y0 + r * 220, W // 5, 220, (ax, 0, 0), az=22, el=40, k_=2.6)
    y0 += 440

    # row 6: the 33 goods on the counter (three counters, eleven goods each, at the store's 0.75) from a customer's eye
    # (1.62 over the floor, 1.5 blocks in front of the counter), and one from the worker's side: each view a 1600 x 480
    # window of a 1920 x 1080 game screen at FOV 70 (the same size on screen as in the game)
    goods = list(S.GOODS)
    fov = round(2 * math.degrees(math.atan(math.tan(math.radians(35)) * 480 / 1080 * 1920 / 1600 * 1600 / 1920)), 2)
    for r in range(3):
        cx = 9000 + r * 300
        els.extend(_placed(pv["store_counter"], (cx, 8, 0), 1))
        els.append(_plane("tile", cx - 80, -80, cx + 80, 60))
        row = goods[r * 11:(r + 1) * 11]
        for i, g in enumerate(row):
            gx = cx + 14 - i * 3.8            # from the customer's left (+x) to the right, read left to right
            gz = -5.5 + (i % 2) * 3
            els.extend(_placed(pv[f"store_item_{g}"], (gx, 15.25, gz), 0.75))
        view(_eye(0, y0 + r * 480, W, 480, [cx - 4, EYE_PX, -8 - 24], [cx - 4, 15.5, -4], fov=fov),
             f"손님 눈 (높이 1.62, 1.5칸 앞, 0.75배, 게임 화면 크기): {row[0]} … {row[-1]} (왼쪽부터)")
    view(_eye(0, y0 + 3 * 480, W, 480, [9000 - 6, EYE_PX, 8 + 10], [9000 - 4, 15.5, -4], fov=fov), "근무자 눈 (계산대 뒤 0.6칸): 첫 열한 가지")
    y0 += 4 * 480

    # row 7: the counter's screens: the POS from the worker's eye, the card terminal / customer display from the customer's;
    # the new 다시 key and the keys' backs
    cx = 10500
    els.extend(_placed(pv["store_counter"], (cx, 8, 0), 1))
    els.append(_plane("tile", cx - 60, -60, cx + 60, 60))
    for i, k in enumerate(("store_btn_reset", "store_btn_no")):
        els.extend(_placed(pv[k], (cx + 300 + i * 16, 30, 0), 1))
    pos = [cx + (13 - 8), 22.2, (10.4 - 8)]
    view(_eye(0, y0, 400, 300, [pos[0] - 2, EYE_PX, 8 + 12], pos), "근무자 눈: POS 화면 (빈 대기 화면, 뒤로 누움)")
    view({"x": 400, "y": y0, "w": 400, "h": 300, "az": -90, "el": 3, "k": 1.0, "center": pos, "radius": 46, "fov": 26, "far": 6000},
         "옆에서: POS 기울기 (왼쪽 = 근무자 +z)")
    term = [cx + (21 - 8), 17, (6 - 8)]
    view(_eye(800, y0, 400, 300, [term[0] - 4, EYE_PX, -8 - 22], [cx + 3, 18, -3]), "손님 눈: 카드 단말기 · 손님 화면")
    view({"x": 1200, "y": y0, "w": 400, "h": 150, "az": 0, "el": 4, "k": 1.0, "center": [cx + 308, 30, 0], "radius": 34, "fov": 35,
          "far": 6000}, "btn_no · store_btn_reset (다시)")
    view({"x": 1200, "y": y0 + 150, "w": 400, "h": 150, "az": 180, "el": 4, "k": 1.0, "center": [cx + 308, 30, 0], "radius": 34,
          "fov": 35, "far": 6000}, "뒷면 (손님 쪽)")
    y0 += 300
    H = y0

    src = BUILD / "store_assets_4.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "size": [W, H], "views": views}))
    shot = BUILD / "store_assets_4.render.png"
    subprocess.run(["node", str(RENDER), str(src), str(shot)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(shot).convert("RGBA")
    out = Image.new("RGBA", (W, H), (27, 29, 34, 255))
    out.alpha_composite(im, (0, 0))
    d = ImageDraw.Draw(out)
    f = _font(17)
    for (x, y, label) in labels:
        if not label:
            continue
        tw = d.textlength(label, font=f)
        d.rectangle((x + 6, y + 6, x + 16 + tw, y + 32), fill=(20, 22, 26, 210))
        d.text((x + 11, y + 8), label, font=f, fill=(240, 236, 220))
    out.convert("RGB").save(path, optimize=True)
    return path


SHEET5 = REPO / "docs" / "previews" / "store_receipt.png"


def sheet5(path=SHEET5):
    """The receipt feeding out of the counter's printer (store_receipt, store_receipt_glitch): the strip on the printer's
    slot as the store shows it (an item display at the slot, turned RECEIPT_TILT about x, scaled along its length) a third
    out, fully out, and the glitch one fully out, each from the worker's eye and from the customer's; and both strips'
    faces flat, enlarged."""
    from models import store as S
    from models import store_action as A
    BUILD.mkdir(exist_ok=True)
    keys = ["store_counter", "store_receipt", "store_receipt_glitch"]
    models = {k: S.ALL[k]() for k in keys}
    pv = {k: m.to_preview() for k, m in models.items()}
    tex = _floor_tex()
    for p_ in pv.values():
        tex.update(p_["textures"])
    els, views, labels = [], [], []
    W, VH = 1600, 420
    cases = [("store_receipt", 0.35, "store_receipt · 나오는 중 (길이 0.35)"), ("store_receipt", 1.0, "store_receipt · 다 나옴"),
             ("store_receipt_glitch", 1.0, "store_receipt_glitch · 다 나옴")]
    t = math.radians(A.RECEIPT_TILT)
    normal = (0.0, -math.sin(t), math.cos(t))            # the printed (south) face's way once tilted
    for i, (k, grow, label) in enumerate(cases):
        cx = i * 400.0
        els.extend(_placed(pv["store_counter"], (cx, 8, 0), 1))
        els.append(_plane("tile", cx - 80, -80, cx + 80, 80))
        slot = [cx + A.RECEIPT_SLOT[0] - 8 + normal[0] * 0.05, A.RECEIPT_SLOT[1] + normal[1] * 0.05, A.RECEIPT_SLOT[2] - 8 + normal[2] * 0.05]
        for e in pv[k]["elements"]:
            e = json.loads(json.dumps(e))
            e["from"] = [slot[0] + e["from"][0] - 8, slot[1] + (e["from"][1] - 8) * grow, slot[2] + e["from"][2] - 8]
            e["to"] = [slot[0] + e["to"][0] - 8, slot[1] + (e["to"][1] - 8) * grow, slot[2] + e["to"][2] - 8]
            e["post"] = [["x", A.RECEIPT_TILT, slot]]
            els.append(e)
        at = [slot[0], slot[1] + 3, slot[2] - 1]
        w = W // len(cases)
        for r, (eye, who) in enumerate((([slot[0] - 3, EYE_PX, 8 + 12], "근무자 눈"), ([slot[0] - 5, EYE_PX, -8 - 22], "손님 눈"))):
            v = _eye(i * w, r * VH, w, VH, eye, at, fov=28)
            views.append(v)
            labels.append((v["x"], v["y"], f"{who}: {label}"))
    H = 2 * VH
    src = BUILD / "store_receipt.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "size": [W, H], "views": views}))
    shot = BUILD / "store_receipt.render.png"
    subprocess.run(["node", str(RENDER), str(src), str(shot)], check=True, stdout=subprocess.DEVNULL)
    FH = 56 * 7 + 80
    out = Image.new("RGBA", (W, H + FH), (27, 29, 34, 255))
    out.alpha_composite(Image.open(shot).convert("RGBA"), (0, 0))
    d = ImageDraw.Draw(out)
    f = _font(17)
    for (x, y, label) in labels:
        tw = d.textlength(label, font=f)
        d.rectangle((x + 6, y + 6, x + 16 + tw, y + 32), fill=(20, 22, 26, 210))
        d.text((x + 11, y + 8), label, font=f, fill=(240, 236, 220))
    x = 40
    for name, im in (("store_receipt 앞 (근무자 쪽 +z)", A.receipt_strip()), ("뒤", A.receipt_back()),
                     ("store_receipt_glitch 앞", A.receipt_strip(True)), ("뒤", A.receipt_back(True))):
        big = im.resize((im.width * 7, im.height * 7), Image.NEAREST)
        out.alpha_composite(big, (x, H + 40))
        d.text((x, H + 12), name, font=f, fill=(240, 236, 220))
        x += big.width + 190
    d.text((40, H + FH - 24), "위 = 먼저 나온 끝 (톱니) · 아래 = 프린터 슬롯 (모델 원점 8, 8, 8)", font=f, fill=(185, 190, 200))
    out.convert("RGB").save(path, optimize=True)
    return path


if __name__ == "__main__":
    if "--sheet5-only" in sys.argv:
        print(sheet5())
        sys.exit()
    if "--sheet3-only" in sys.argv:
        print(sheet3())
        sys.exit()
    if "--sheet4-only" in sys.argv:
        print(sheet4())
        sys.exit()
    print(len(write()), "models written")
    if "--no-sheet" not in sys.argv:
        print(sheet())
        print(sheet3())
        print(sheet4())
        print(sheet5())
