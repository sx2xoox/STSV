"""
도박장 assets (docs/plans/도박장_기획.md §2, economy README "도박장"): writes only the gambling hall's own files, and its
preview sheet.

    python3 tools/assetgen/gamble_assets.py            # models + the NPC skins + docs/previews/gamble_hall_assets.png and
                                                       # docs/previews/gamble_cards.png
    python3 tools/assetgen/gamble_assets.py --no-sheet # models + skins only
    (the sheets need: cd tools/assetgen/render && npm install)

  models   economy/content/economy/models/gamble_*.bbmodel (models/gamble.py; economy_models.ALL has them too, so a full
           economy_build.py writes the same files)
  skin     economy/content/economy/assets/economy/textures/entity/bus/site_boss.png = "economy:entity/bus/site_boss" and
           dealer.png = "economy:entity/bus/dealer" (slim; economy_bus_skins.NPCS: only these are written; the HUD's faces
           sheet takes every skin of that folder the next time it is built)
  sheet    the tables (their seats numbered in a plan of the cells), the props, the cards, chips, dice (each face up by
           the README's turns), the bowl, the dice cup, the whiteboard, the buttons and the skins; the 별관's poker and
           baccarat tables, its chips and a few playing cards
  cards    docs/previews/gamble_cards.png: the 53 playing cards (pcard_art) as models on felt, a 세븐 포커 hand as a seated
           player sees it (Minecraft's 70 degree view, 1.5 blocks away, the cards at the table's 0.75), and every face
           and the back flat and zoomed
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
CARDS = REPO / "docs" / "previews" / "gamble_cards.png"
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
    view(0, y0, 1600, 230, [5712 - n * 7, 3, 0], 55 * n / 11, 0, 8, 0.8,
         "gamble_btn_" + " · ".join(ART.BUTTON_WORDS) + " (빛남)")

    # row 6: the 별관's tables (as the furniture shows them: scale 2)
    y0 = 1880
    els.extend(_placed(pv["poker_table"], (7000, 8, 0), 2))
    els.append(_plane("ply", 6944, -56, 7072, 72))
    view(0, y0, 800, 600, [7000, 10, 2], 50, 24, 36, 2.0, "poker_table · 세븐 포커 · 드럼통 위 타원 · 좌석 6 (5×5칸)")
    els.extend(_placed(pv["baccarat_table"], (7400, 8, 0), 2))
    els.append(_plane("ply", 7344, -56, 7472, 104))
    view(800, y0, 800, 600, [7400, 8, 6], 50, 0, 42, 2.0, "baccarat_table · 바카라 · 반달 · 좌석 6 + 딜러 칸 (5×5칸)")

    # row 7: playing cards on felt (top view) and the baccarat chips
    y0 = 2480
    some = ["pcard_as", "pcard_kh", "pcard_qd", "pcard_jc", "pcard_10s", "pcard_7h", "pcard_2d", "pcard_back"]
    for i, k in enumerate(some):
        els.extend(_placed(pv[k], (8000 + i * 9, 0, 0), 1))
    els.append(_plane("feltbg", 7990, -12, 8080, 12))
    view(0, y0, 1000, 330, [8031.5, 0, 0], 30, 0, 70, 1.4, "pcard_<a,2..10,j,q,k><s,h,d,c>, pcard_back · 7×10 px (docs/previews/gamble_cards.png)")
    for i, z in enumerate(("player", "banker", "tie")):
        els.extend(_placed(pv[f"baccarat_chip_{z}"], (8500 - i * 14, 0, 0), 1))
    els.append(_plane("feltbg", 8460, -15, 8515, 15))
    view(1000, y0, 600, 330, [8486, 3, 1], 22, 0, 35, 1.6, "baccarat_chip_player · banker · tie")

    H = 2810
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
    for yy in (600, 930, 1350, 1650, 1880, 2480, H):
        d.line((0, yy, W, yy), fill=(60, 64, 72), width=2)
    import pcard_art as PC
    _seat_plan(d, 20, H + 20, G.ROUND_SEATS, (-2, 2, -2, 2), "원탁 · 포커 좌석 (위, 앞 = 북 -z)", f, small, round_=True)
    _seat_plan(d, 300, H + 20, G.DUEL_SEATS, (0, 0, -1, 1), "1:1", f, small)
    _seat_plan(d, 380, H + 20, PC.BACCARAT_SEATS_CELLS, (-2, 2, -2, 2), "바카라 좌석 · 딜러 D", f, small, half=True)
    npcs = B.build_npcs()
    for j, (key, title) in enumerate((("site_boss", "현장 소장 site_boss (wide)"), ("dealer", "딜러 dealer (slim)"))):
        sx = 680 + j * 300
        d.text((sx, H + 16), title, font=small, fill=(240, 236, 220))
        skin, slim = npcs[key]
        out.alpha_composite(B.flat(skin, slim).resize((128, 256), Image.NEAREST), (sx, H + 56))
        out.alpha_composite(B.flat(skin, slim, back=True).resize((128, 256), Image.NEAREST), (sx + 140, H + 56))
    notes = ["좌석 번호 = seats 순서", "원탁 · 포커: 0 = 정면, 위에서 시계 방향", "바카라: 딜러 왼쪽(-x) 끝부터 시계 방향",
             "딜러 = 칸 [0, 0, 2] (곧은 쪽 뒤)", "앉는 높이 0.5 (판지 깐 통 0.52)", "표시 모델 앞 = 모델 북(-z)",
             "카드 · 칩 · 사발: 위치 = 바닥", "주사위 · 버튼: 위치 = 가운데"]
    for i, t in enumerate(notes):
        d.text((1290, H + 24 + i * 32), t, font=_font(14), fill=(200, 204, 212))
    out.convert("RGB").save(path, optimize=True)
    return path


def cards_sheet(path=CARDS):
    """docs/previews/gamble_cards.png: (1) the 53 playing-card models on felt at the table's scale (0.75), from above;
    (2) a 세븐 포커 hand (3 down, 4 up) in front of a seat as the seated player sees it: Minecraft's 70 degree view, the
    eye 0.75 over the felt and a block back from the cards (about 1.25 blocks to them), the next seats' hands beside;
    (3) every face and the back flat at 4x (the texture itself)."""
    from models import gamble as G
    import pcard_art as PC
    BUILD.mkdir(exist_ok=True)
    keys = PC.ids() + ["pcard_back"]
    pv = {k: G.ALL[k]().to_preview() for k in keys}
    tex = _floor_tex()
    for p in pv.values():
        tex.update(p["textures"])
    els, views, labels = [], [], []
    W = 1600
    s = 0.75
    # (1) the whole deck on felt, a row a suit, the back at the end
    for j, su in enumerate(PC.SUITS):
        for i, r in enumerate(PC.RANKS):
            els.extend(_placed(pv[f"pcard_{r}{su}"], (60 - i * 6.5, 0, j * 9), s))
    els.extend(_placed(pv["pcard_back"], (60 - 13 * 6.5, 0, 13.5), s))
    els.append(_plane("feltbg", -40, -12, 72, 42))
    views.append({"x": 0, "y": 0, "w": W, "h": 560, "az": 0, "el": 72, "k": 1.0, "center": [17, 0, 14], "radius": 60, "fov": 35, "far": 4000})
    labels.append((0, 0, "트럼프 53장, 테이블 위 크기 (0.75), 위에서"))
    # (2) a hand of seven poker as its player sees it, and the hands of the seats either side
    hand = ["pcard_back", "pcard_back", "pcard_kh", "pcard_kd", "pcard_7c", "pcard_as", "pcard_back"]
    for i, k in enumerate(hand):
        els.extend(_placed(pv[k], (500 + 18 - i * 6, 0, 0), s))
    for i, k in enumerate(["pcard_qs", "pcard_qc", "pcard_9h", "pcard_back"]):
        els.extend(_placed(pv[k], (500 + 44 - i * 6, 0, 14), s, post=[("y", -30)]))
    for i, k in enumerate(["pcard_10d", "pcard_jd", "pcard_2s", "pcard_back"]):
        els.extend(_placed(pv[k], (500 - 26 - i * 6, 0, 14), s, post=[("y", 30)]))
    els.append(_plane("feltbg", 420, -60, 600, 80))
    # the eye: 12 px (0.75 block) over the felt, 16 px back (to the north) from the hand's middle
    views.append({"x": 0, "y": 560, "w": W, "h": 640, "az": 0, "el": 36.9, "k": 1.0, "center": [500, 0, 0], "radius": 20,
                  "fov": 70, "near": 0.5, "far": 4000})
    labels.append((0, 560, "자리에서 본 내 패 (마인크래프트 시야 70도, 눈 = 펠트 위 0.75칸, 1칸 뒤), 양옆 자리의 패"))
    H = 1200
    src = BUILD / "gamble_cards.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "size": [W, H], "views": views}))
    shot = BUILD / "gamble_cards.render.png"
    subprocess.run(["node", str(RENDER), str(src), str(shot)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(shot).convert("RGBA")
    # (3) the faces flat, 13 a row, at 2x (with the back)
    z = 2
    cw, ch = PC.W * z + 6, PC.H * z + 6
    flat_h = 5 * ch + 50
    out = Image.new("RGBA", (W, H + flat_h + 40), (27, 29, 34, 255))
    out.alpha_composite(im, (0, 0))
    ox = (W - 13 * cw) // 2
    for j, su in enumerate(PC.SUITS):
        for i, r in enumerate(PC.RANKS):
            out.alpha_composite(PC.face(r, su).resize((PC.W * z, PC.H * z), Image.NEAREST), (ox + i * cw, H + 40 + j * ch))
    out.alpha_composite(PC.back().resize((PC.W * z, PC.H * z), Image.NEAREST), (ox, H + 40 + 4 * ch))
    d = ImageDraw.Draw(out)
    f = _font(19)
    labels.append((0, H, "앞면 52장 + 뒷면 (텍스처 56×80: 7×10 px 카드에 1px당 8텍셀), 2배"))
    for (x, y, label) in labels:
        tw = d.textlength(label, font=f)
        d.rectangle((x + 8, y + 8, x + 20 + tw, y + 38), fill=(20, 22, 26, 210))
        d.text((x + 14, y + 10), label, font=f, fill=(240, 236, 220))
    out.convert("RGB").save(path, optimize=True)
    return path


def _seat_plan(d, x0, y0, seats, box, title, f, small, round_=False, half=False):
    """A plan of the cells from above (north = the front up, east = +x right): the table, each seat cell with its
    number, an arrow at the front; half: the baccarat table's half moon (round side to the front) and its dealer's
    cell D behind the straight edge."""
    c = 52
    xs, xe, zs, ze = box
    d.text((x0, y0 - 4), title, font=f, fill=(240, 236, 220))
    top = y0 + 52
    for gx in range(xs, xe + 1):
        for gz in range(zs, ze + 1):
            X, Y = x0 + (gx - xs) * c, top + (gz - zs) * c
            d.rectangle((X, Y, X + c - 2, Y + c - 2), outline=(70, 74, 84))
    mx, my = x0 + (0 - xs) * c + c / 2, top + (0 - zs) * c + c / 2
    if half:
        r = 1.5 * c
        d.pieslice((mx - r, my - r, mx + r, my + r), 180, 360, fill=(30, 84, 52), outline=(150, 110, 60), width=4)
        d.rectangle((mx - r, my, mx + r, my + 1.1 * c), fill=(30, 84, 52), outline=(150, 110, 60), width=4)
        X, Y = mx, my + 2 * c
        d.rectangle((X - 20, Y - 20, X + 20, Y + 20), fill=(200, 60, 70), outline=(30, 30, 30), width=2)
        d.text((X - 7, Y - 14), "D", font=f, fill=(255, 255, 255))
    elif round_:
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
        print(cards_sheet())
