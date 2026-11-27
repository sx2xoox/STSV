"""
숨은 비밀 (관리자 문서) 의 모델 다섯 (models/newcomer_assets.py): writes only these, and their preview sheet.

    python3 tools/assetgen/newcomer_assets.py            # the five .bbmodel files and docs/previews/newcomer_assets.png
    python3 tools/assetgen/newcomer_assets.py --sheet    # only the sheet (needs: cd render && npm install)

  models  economy/content/economy/models/{worn_ticket, worn_receipt, worn_letter,
          worn_bottle, lantern_iron}.bbmodel (economy_models.ALL has them too, so a full
          economy_build.py writes the same files)

The sheet: each shard in an inventory slot, alone, in a standing player's hand and through the player's own eyes
(toys_preview's computed views: the transforms, not the game), then the lantern alone, in a slot, and on the ground
beside a monument stone (both as their item displays show them: scale 1, lifted 0.5).
"""

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
SHEET = REPO / "docs" / "previews" / "newcomer_assets.png"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"

SHARDS = [("worn_ticket", "번진 승차권"), ("worn_receipt", "깨져 인쇄된 영수증"),
          ("worn_letter", "반송된 편지"), ("worn_bottle", "쪽지가 든 병")]
LANTERN = "lantern_iron"


def write():
    import economy_models  # noqa: F401  (the shared surfaces and ramps)
    from models import newcomer_assets as N
    (PACK / "models").mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(exist_ok=True)
    for name, fn in N.ALL.items():
        fn().write(PACK / "models" / f"{name}.bbmodel", BUILD / f"eco_{name}.preview.json")
    return list(N.ALL)


def sheet(path=SHEET):
    import economy_models as M
    import toys_preview as TP
    import secret_stone_preview as SP
    from models import newcomer_assets as N
    BUILD.mkdir(exist_ok=True)
    models = [N.ALL[n]() for n, _ in SHARDS]
    lantern = N.ALL[LANTERN]()
    stone = M.ALL["secret_stone_solved_newcomer"]().to_preview()
    old = M.ALL["secret_stone"]().to_preview()
    tex = dict(TP.EXTRA)
    for m in models + [lantern]:
        tex.update(m.to_preview()["textures"])
    tex.update(stone["textures"])
    tex.update(old["textures"])
    W_SLOT, W_Q, W_HAND, W_EYES, H, TOP = 230, 400, 380, 440, 250, 96
    width = W_SLOT + W_Q + W_HAND + W_EYES
    LH = 560
    height = TOP + H * len(models) + 50 + LH
    els, views = [], []
    GAP = TP.GAP
    q = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
    for r, m in enumerate(models):
        y0 = TOP + r * H
        o = (r * GAP, 0.0, 0.0)
        els += TP.in_slot(m, o)
        views.append({"x": 20, "y": y0 + 20, "w": 210, "h": 210, "az": 180, "el": 0, "center": [o[0], 0, 0], "radius": 9.6, "k": 57,
                      "fov": 2, "far": 2000, "near": 100})
        o2 = (r * GAP, 0.0, GAP)
        els += TP.placed(TP.elements(m), q, o2)
        views.append({"x": W_SLOT, "y": y0 + 30, "w": W_Q // 2, "h": H - 30, "az": 150, "el": 14, "center": list(o2), "radius": 9.5,
                      "k": 5.7, "fov": 20, "far": 2000})
        o2b = (r * GAP, 0.0, 5 * GAP)
        els += TP.placed(TP.elements(m), q, o2b)
        views.append({"x": W_SLOT + W_Q // 2, "y": y0 + 30, "w": W_Q // 2, "h": H - 30, "az": 35, "el": 20, "center": list(o2b),
                      "radius": 9.5, "k": 5.7, "fov": 20, "far": 2000})
        o3 = (r * GAP, 0.0, 2 * GAP)
        els += TP.player(o3) + TP.in_hand(m, o3)
        views.append({"x": W_SLOT + W_Q, "y": y0, "w": W_HAND, "h": H, "az": 58, "el": 10, "center": [o3[0] + 3, 17, o3[2] - 8],
                      "radius": 25, "k": 5.9, "fov": 20, "far": 2000})
        o4 = (r * GAP, 0.0, 3 * GAP)
        els += TP.first_person(m, o4)
        views.append({"x": W_SLOT + W_Q + W_HAND + 10, "y": y0 + 4, "w": 430, "h": 242, "az": 180, "el": 0, "center": [o4[0], 0, o4[2] - 16],
                      "radius": 16, "k": 1, "fov": 70, "near": 0.8, "far": 600})
    # the lantern: in a slot, alone from two sides, and beside the stones
    y0 = TOP + H * len(models) + 50
    o = (0.0, -GAP, 0.0)
    els += TP.in_slot(lantern, o)
    views.append({"x": 20, "y": y0 + 20, "w": 210, "h": 210, "az": 180, "el": 0, "center": [o[0], o[1], 0], "radius": 9.6, "k": 57,
                  "fov": 2, "far": 2000, "near": 100})
    lp = lantern.to_preview()
    for k, (az, el) in enumerate(((205, 12), (40, 24))):
        ob = (GAP * (k + 1), -GAP, 0.0)
        els += [dict(e, post=[]) for e in SP._placed(lp, ob, 180)]
        views.append({"x": W_SLOT + k * 300, "y": y0 + 10, "w": 300, "h": LH - 20, "az": az, "el": el, "center": [ob[0], ob[1] + 8, ob[2]],
                      "radius": 12, "k": 5.5, "fov": 25, "far": 3000})
    # the scene: the solved stone facing the reader (north in the scene = towards the camera), the lantern at its side,
    # an unsolved stone further along
    sc = (0.0, -2 * GAP, 0.0)
    els += SP._placed(stone, sc, 180)
    els += SP._placed(lp, (sc[0] + 17, sc[1], sc[2] - 5), 180)
    els += SP._placed(old, (sc[0] - 30, sc[1], sc[2] + 3), 170)
    g = SP._ground(tex, sc[0] - 80, sc[2] - 80, sc[0] + 80, sc[2] + 80)
    for e in g:
        e["from"][1] += sc[1]
        e["to"][1] += sc[1]
    els += g
    sx = W_SLOT + 600
    views.append({"x": sx, "y": y0 + 10, "w": width - sx - 10, "h": LH - 20, "az": 14, "el": 10, "center": [sc[0] + 3, sc[1] + 14, sc[2]],
                  "radius": 34, "k": 1.9, "fov": 35, "far": 5000})
    src, png = BUILD / "newcomer_assets.preview.json", BUILD / "newcomer_assets.png"
    src.write_text(json.dumps({"textures": tex, "elements": els, "grid": False, "views": views, "size": [width, height]}))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(png).convert("RGB")
    d = ImageDraw.Draw(im)
    f, s = ImageFont.truetype(FONT, 28), ImageFont.truetype(FONT, 18)
    d.rectangle((0, 0, width, TOP - 1), fill=(27, 29, 34))
    d.text((18, 10), "깨진 기록 조각 4종 · 꺼지지 않는 등불 (관리자용 미리보기)", fill=(245, 245, 245), font=f)
    for x, text in ((20, "인벤토리 칸"), (W_SLOT + 20, "모델 (앞 · 뒤)"), (W_SLOT + W_Q + 20, "3인칭"), (W_SLOT + W_Q + W_HAND + 20, "1인칭 (시야 70도)")):
        d.text((x, 60), text, fill=(185, 190, 200), font=s)
    for r, (n, label) in enumerate(SHARDS):
        y = TOP + r * H
        d.line((0, y, width, y), fill=(52, 56, 66))
        d.text((W_SLOT + 12, y + 8), f"{label}  ·  economy:{n}", fill=(225, 228, 235), font=s)
        d.rectangle((W_SLOT + W_Q + W_HAND + 9, y + 3, W_SLOT + W_Q + W_HAND + 440, y + 246), outline=(70, 74, 86))
    y = TOP + H * len(SHARDS)
    d.rectangle((0, y, width, y + 49), fill=(27, 29, 34))
    d.line((0, y, width, y), fill=(52, 56, 66))
    d.text((18, y + 10), f"꺼지지 않는 등불  ·  economy:{LANTERN}  (칸 · 앞 · 뒤 위 · 비석 옆: 아이템 디스플레이 scale 1, 0.5 올림)", fill=(225, 228, 235), font=s)
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, optimize=True)
    print("sheet  ", path)


if __name__ == "__main__":
    if "--sheet" not in sys.argv:
        print("models ", ", ".join(write()))
    sheet()
