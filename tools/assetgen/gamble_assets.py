"""
도박장 assets (docs/plans/도박장_기획.md §2, economy README "도박장"): writes only the gambling hall's own files, and its
preview sheet.

    python3 tools/assetgen/gamble_assets.py            # models + the 현장 소장 skin + docs/previews/gamble_hall_assets.png
    python3 tools/assetgen/gamble_assets.py --no-sheet # models + skin only
    (the sheet needs: cd tools/assetgen/render && npm install)

  models   economy/content/economy/models/gamble_*.bbmodel (models/gamble.py; economy_models.ALL has them too, so a full
           economy_build.py writes the same files)
  skin     economy/content/economy/assets/economy/textures/entity/bus/site_boss.png = "economy:entity/bus/site_boss"
           (economy_bus_skins.site_boss; the HUD's faces sheet takes every skin of that folder the next time it is built)
  sheet    the tables (their seats numbered in a plan of the cells), the props, the cards, chips, dice (each face up by
           the README's turns), the bowl, the dice cup, the whiteboard, the buttons and the skin
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
SHEET = REPO / "docs" / "previews" / "gamble_hall_assets.png"
FONT = HERE / "fonts" / "Sunflower-Medium.ttf"

# the die's turns (README "도박장"): face up -> rotations in the display's own frame, after the game's half turn of item
# models (model north faces the display's +z): [(axis, degrees)], right-handed (JOML / Quaternionf.rotateX etc.)
DIE_UP = {1: [], 2: [("x", -90)], 3: [("z", 90)], 4: [("z", -90)], 5: [("x", 90)], 6: [("x", 180)]}


def write():
    from models import gamble as G
    import economy_bus_skins as B
    (PACK / "models").mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(exist_ok=True)
    for name, fn in G.ALL.items():
        fn().write(PACK / "models" / f"{name}.bbmodel", BUILD / f"eco_{name}.preview.json")
    B.OUT.mkdir(parents=True, exist_ok=True)
    for key, (im, _slim) in B.build_npcs().items():
        im.save(B.OUT / f"{key}.png")
    return list(G.ALL)


# ------------------------------------------------------------------------------------------------
# the sheet
# ------------------------------------------------------------------------------------------------

def _font(size):
    return ImageFont.truetype(str(FONT), size)


def _uri(im):
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def _placed(pv, at, s=1.0, post=()):
    """Model elements as a display (or a furniture of scale s) shows them with its position at `at` (preview px):
    model point m -> at + (m - 8) * s. post: more turns [(axis, degrees)] about `at` (the die rolled)."""
    out = []
    for e in pv["elements"]:
        e = json.loads(json.dumps(e))
        e["from"] = [at[i] + (e["from"][i] - 8) * s for i in range(3)]
        e["to"] = [at[i] + (e["to"][i] - 8) * s for i in range(3)]
        if e.get("rotation"):
            axis, ang, o = e["rotation"]
            e["rotation"] = [axis, ang, [at[i] + (o[i] - 8) * s for i in range(3)]]
        if post:
            e["post"] = [[a, d, list(at)] for (a, d) in post]
        out.append(e)
    return out


def _plane(name, x0, z0, x1, z1, y=0.0):
    return {"from": [x0, y - 1, z0], "to": [x1, y, z1], "shade": True, "rotation": None,
            "faces": {"up": {"uv": [0, 0, 16, 16], "texture": name}}}


def _floor_tex():
    """Plywood sheets (the hall's floor) and felt (under the table things)."""
    from stsv_assets.palette import ramp
    ply = Image.new("RGBA", (16, 16))
    felt = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = ramp("hinoki", 1 if y in (0, 8) or (x * 7 + y * 3) % 13 == 0 else 2)
            ply.putpixel((x, y), tuple(c[:3]) + (255,))
            f = ramp("felt", 2 if (x * 5 + y * 3) % 7 else 1)
            felt.putpixel((x, y), tuple(f[:3]) + (255,))
    return {"ply": _uri(ply), "feltbg": _uri(felt)}


def sheet(path=SHEET):
    from models import gamble as G
    import gamble_art as ART
    import economy_bus_skins as B
    BUILD.mkdir(exist_ok=True)
    pv = {k: fn().to_preview() for k, fn in G.ALL.items()}
    tex = _floor_tex()
    for p in pv.values():
        tex.update(p["textures"])
    els, views, labels = [], [], []
    W = 1600

    def view(x, y, w, h, centre, radius, az, el, k, label, fov=35):
        views.append({"x": x, "y": y, "w": w, "h": h, "az": az, "el": el, "k": k, "center": centre, "radius": radius, "fov": fov,
                      "far": 4000})
        labels.append((x, y, label))

    # row 1: the tables, each on its own patch of plywood floor
    y0 = 0
    els.extend(_placed(pv["gamble_table_round"], (0, 8, 0), 2))
    els.append(_plane("ply", -56, -56, 72, 72))
    view(0, y0, 1000, 600, [8, 10, 10], 52, 24, 34, 2.1, "gamble_table_round · 케이블 드럼 원탁 · 좌석 6 (5×5칸)")
    els.extend(_placed(pv["gamble_table_duel"], (400, 8, 0), 1))
    els.append(_plane("ply", 360, -40, 456, 56, 0))
    view(1000, y0, 600, 600, [408, 9, 8], 30, 30, 30, 2.2, "gamble_table_duel · 각목 상자 1:1 · 좌석 2 (1×3칸)")

    # row 2: the props
    y0 = 600
    props = [("gamble_prop_sign", "공사중 표지판 (2×2)", 300, 2, 1), ("gamble_prop_cone", "안전 고깔", 180, 1, 1),
             ("gamble_prop_worklight", "작업등 · 빛 15", 200, 1, 2), ("gamble_prop_drumfire", "드럼통 불 · 빛 14", 200, 1, 2),
             ("gamble_prop_cement", "시멘트 포대", 200, 1, 1), ("gamble_prop_pallet", "자재 팔레트", 200, 1, 1),
             ("gamble_prop_rebar", "철근 다발 (3×1)", 320, 3, 1)]
    x = 0
    for i, (k, label, w, cw, ch) in enumerate(props):
        ax = 800 + 400 * i
        els.extend(_placed(pv[k], (ax, 8, 0), 1))
        els.append(_plane("ply", ax - 40, -40, ax + 56, 56))
        cx = ax + (8 if k == "gamble_prop_sign" else 0)
        view(x, y0, w, 330, [cx, ch * 8 + 2, 8], 10 + 9 * max(cw, ch), 28, 20, 2.3, label)
        x += w

    # row 3: cards on felt (top view) and the chips
    y0 = 930
    cards = [f"gamble_card_{m}{f}" for m in range(1, 11) for f in "ab"] + ["gamble_card_back"]
    for i, k in enumerate(cards):
        cx, cz = 4054 - (i % 7) * 9, (2 - i // 7) * 12
        els.extend(_placed(pv[k], (cx, 0, cz), 1))
    els.append(_plane("feltbg", 3980, -12, 4080, 40))
    view(0, y0, 820, 420, [4027, 0, 12], 36, 0, 62, 1.75, "gamble_card_1a … 10b, gamble_card_back · 6×8 px, 앞면 4텍셀/px")
    for n in range(1, 6):
        els.extend(_placed(pv[f"gamble_chips_{n}"], (4520 - (n - 1) * 33, 0, 0), 1))
    els.append(_plane("feltbg", 4370, -30, 4550, 30))
    view(820, y0, 780, 420, [4455, 4, 2], 66, 0, 38, 2.0, "gamble_chips_1 … 5")

    # row 4: dice (each face up by DIE_UP), the bowl, the whiteboard
    y0 = 1350
    for n in range(1, 7):
        post = [("y", 180)] + DIE_UP[n]
        els.extend(_placed(pv["gamble_die"], (4845 - (n - 1) * 9, 2, 0), 1, post=post))
    els.append(_plane("feltbg", 4780, -20, 4870, 20))
    view(0, y0, 560, 300, [4822.5, 2, 0], 24, 0, 45, 2.2, "gamble_die · 위 1 … 6 (README 회전표대로)")
    els.extend(_placed(pv["gamble_bowl"], (5200, 0, 0), 1))
    els.append(_plane("feltbg", 5180, -20, 5220, 20))
    view(560, y0, 150, 300, [5200, 3, 0], 10, 10, 28, 3.4, "bowl · 사발")
    els.extend(_placed(pv["gamble_cup"], (5300, 0, 0), 1))
    els.extend(_placed(pv["gamble_die"], (5306, 2, -6), 1))
    els.append(_plane("feltbg", 5280, -20, 5320, 20))
    view(710, y0, 150, 300, [5302, 3, -2], 11, 10, 28, 3.4, "cup · 주사위 컵")
    els.extend(_placed(pv["gamble_whiteboard"], (6000, 22, 0), 1))
    els.append({"from": [5970, -2, -3.49], "to": [6030, 50, 0], "shade": True, "rotation": None,
                "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "ply"}}})
    els.append(_plane("ply", 5970, -20, 6030, -3.5))
    view(860, y0, 740, 300, [6008, 22, -7], 24, 8, 8, 2.0, "gamble_whiteboard · 벽걸이 2.5×1.5칸")

    # row 5: the buttons
    y0 = 1650
    for i, k in enumerate(ART.BUTTON_WORDS):
        els.extend(_placed(pv[f"gamble_btn_{k}"], (5705 - i * 14, 3, 0), 1))
    n = len(ART.BUTTON_WORDS)
    els.append(_plane("feltbg", 5705 - n * 14, -15, 5720, 15))
    view(0, y0, 1600, 230, [5712 - n * 7, 3, 0], 55, 0, 8, 0.8,
         "gamble_btn_" + " · ".join(ART.BUTTON_WORDS) + " (빛남)")

    H = 1880
    src = BUILD / "gamble_hall_assets.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "size": [W, H], "views": views}))
    shot = BUILD / "gamble_hall_assets.render.png"
    subprocess.run(["node", str(RENDER), str(src), str(shot)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(shot).convert("RGBA")

    # below the renders: the seat plans and the 현장 소장
    extra = 330
    out = Image.new("RGBA", (W, H + extra), (27, 29, 34, 255))
    out.alpha_composite(im, (0, 0))
    d = ImageDraw.Draw(out)
    f, small = _font(19), _font(16)
    for (x, y, label) in labels:
        tw = d.textlength(label, font=f)
        d.rectangle((x + 8, y + 8, x + 20 + tw, y + 38), fill=(20, 22, 26, 210))
        d.text((x + 14, y + 10), label, font=f, fill=(240, 236, 220))
    for yy in (600, 930, 1350, 1650, H):
        d.line((0, yy, W, yy), fill=(60, 64, 72), width=2)
    _seat_plan(d, 30, H + 20, G.ROUND_SEATS, (-2, 2, -2, 2), "원탁 좌석 (위에서, 앞 = 북 -z)", f, small, round_=True)
    _seat_plan(d, 420, H + 20, G.DUEL_SEATS, (0, 0, -1, 1), "1:1 좌석", f, small)
    sx = 640
    d.text((sx, H + 16), "현장 소장 · economy:entity/bus/site_boss (wide)", font=f, fill=(240, 236, 220))
    skin, slim = B.build_npcs()["site_boss"]
    fr, bk = B.flat(skin, slim), B.flat(skin, slim, back=True)
    out.alpha_composite(fr.resize((128, 256), Image.NEAREST), (sx, H + 56))
    out.alpha_composite(bk.resize((128, 256), Image.NEAREST), (sx + 150, H + 56))
    out.alpha_composite(skin.resize((256, 256), Image.NEAREST), (sx + 310, H + 56))
    notes = ["좌석 번호 = seats 순서", "0 = 정면, 위에서 시계 방향", "앉는 높이 0.5 (판지 깐 통 0.52)",
             "표시 모델 앞 = 모델 북(-z)", "카드 · 칩 · 사발: 위치 = 바닥", "주사위 · 버튼: 위치 = 가운데"]
    for i, t in enumerate(notes):
        d.text((sx + 580, H + 60 + i * 30), t, font=small, fill=(200, 204, 212))
    out.convert("RGB").save(path, optimize=True)
    return path


def _seat_plan(d, x0, y0, seats, box, title, f, small, round_=False):
    """A plan of the cells from above (north = the front up, east = +x right): the table, each seat cell with its
    number, an arrow at the front."""
    c = 52
    xs, xe, zs, ze = box
    d.text((x0, y0 - 4), title, font=f, fill=(240, 236, 220))
    top = y0 + 52
    for gx in range(xs, xe + 1):
        for gz in range(zs, ze + 1):
            X, Y = x0 + (gx - xs) * c, top + (gz - zs) * c
            d.rectangle((X, Y, X + c - 2, Y + c - 2), outline=(70, 74, 84))
    mx, my = x0 + (0 - xs) * c + c / 2, top + (0 - zs) * c + c / 2
    if round_:
        d.ellipse((mx - 1.5 * c, my - 1.5 * c, mx + 1.5 * c, my + 1.5 * c), fill=(30, 84, 52), outline=(150, 110, 60), width=4)
    else:
        d.rectangle((mx - c * 0.45, my - c * 0.45, mx + c * 0.45, my + c * 0.45), fill=(30, 84, 52), outline=(170, 130, 70), width=3)
    for i, (gx, gz) in enumerate(seats):
        X, Y = x0 + (gx - xs) * c + c / 2, top + (gz - zs) * c + c / 2
        d.ellipse((X - 18, Y - 18, X + 18, Y + 18), fill=(242, 194, 42), outline=(30, 30, 30), width=2)
        tw = d.textlength(str(i), font=f)
        d.text((X - tw / 2, Y - 14), str(i), font=f, fill=(20, 20, 20))
    d.text((mx - 26, top - 30), "▲ 앞", font=small, fill=(255, 210, 90))


if __name__ == "__main__":
    print(len(write()), "models written")
    if "--no-sheet" not in sys.argv:
        print(sheet())
