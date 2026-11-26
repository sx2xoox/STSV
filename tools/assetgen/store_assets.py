"""
편의점 알바 assets (docs/plans/편의점_기획안.md §15): writes only the convenience store's own files, and its preview
sheet.

    python3 tools/assetgen/store_assets.py            # models + equipment + docs/previews/store_assets.png
    python3 tools/assetgen/store_assets.py --no-sheet # models + equipment only
    (the sheet needs: cd tools/assetgen/render && npm install)

  models     economy/content/economy/models/store_*.bbmodel (models/store.py; economy_models.ALL has them too, so a
             full economy_build.py writes the same files)
  equipment  economy/content/economy/assets/economy/equipment/store_vest.json and the armour layer
             assets/economy/textures/entity/equipment/humanoid/store_vest.png (store_art.vest_layer): store_vest worn
             in the chest slot (items/store.yml equipment: { slot: chest, asset: economy:store_vest })
  sheet      docs/previews/store_assets.png: a store laid out (the counter, the cigarette wall, shelves, fridges,
             the eat bar, the sign), then every piece on its own: the twelve shelves (4 kinds x full / half / empty,
             both sides), the fridges and the freezer, the appliances and props, the 33 goods, the boxes, money and
             props, the keys, the scanner and the vest (and its armour layer flat)
"""

import base64
import io
import json
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
FONT = HERE / "fonts" / "Sunflower-Medium.ttf"
EQUIPMENT = PACK / "assets" / "economy" / "equipment" / "store_vest.json"
VEST_LAYER = PACK / "assets" / "economy" / "textures" / "entity" / "equipment" / "humanoid" / "store_vest.png"


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
    view(900, y0, 700, 520, [4208, 20, 8], 38, 28, 22, 1.9, "store_parasol · 3×1 · 좌석 2")
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
        view(i * 200, y0, 200, 340, [ax, 15 if tall else 9, 8], 22 if tall else 15, 28, 20, 3.4, label)
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


if __name__ == "__main__":
    print(len(write()), "models written")
    if "--no-sheet" not in sys.argv:
        print(sheet())
