"""
Preview of the 튜닝샵 (economy: /관리자 튜닝샵 설치 <이름>) as the plugin builds it, drawn from the layout a test writes:

    cd economy && ./gradlew test          # TuningShopTest writes economy/build/docks/tuning.json (DockLayout.tuning)
    python3 tools/assetgen/tuning_shop_preview.py [out.png]     # docs/previews/tuning_shop.png (needs: cd render && npm install)

Drawn as dock_preview draws a dock (plain-coloured cubes, no vanilla textures), twice: from the forecourt (the open door
under the red fascia and its chequered flag, the lamp posts, the sign) and from above with the roof off (the striped work bay with its lift
rails, the smith's table by the door, tyres, the paint bench, the tool wall).
"""

import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import dock_preview as DP  # noqa: E402

REPO = HERE.parent.parent
DATA = REPO / "economy" / "build" / "docks" / "tuning.json"

COLOURS = {
    "polished_blackstone": (53, 49, 57), "iron_block": (220, 220, 220), "coal_block": (22, 22, 24), "smithing_table": (58, 56, 68),
    "iron_bars": (140, 142, 140), "red_candle": (153, 39, 36), "yellow_candle": (209, 165, 50), "lime_candle": (98, 172, 23),
    "light_blue_candle": (35, 137, 198), "purple_candle": (105, 34, 159), "white_candle": (210, 217, 218), "orange_candle": (218, 99, 9),
    "pink_candle": (206, 101, 139), "cyan_candle": (17, 124, 123), "smooth_stone_slab_top": (160, 160, 160),
}
ROOF = 6        # S_WALL + 1: from here up is the roof and its fascia


def _own(name, state, r, y, f, ox):
    """Blocks dock_preview has no shape for: top slabs, candles (cans of paint), bars."""
    x0, y0, z0 = DP._px(r + 1, y, f)
    x0 += ox
    if name.endswith("_slab") and "type=top" in state:
        return [DP._el((x0, y0 + 8, z0), (x0 + 16, y0 + 16, z0 + 16), name + "_top")]
    if name.endswith("_candle"):
        n = int(state.split("candles=")[1].rstrip("]")) if "candles=" in state else 1
        spots = [(7, 7), (4, 9), (10, 5)][:n]
        return [DP._el((x0 + a, y0, z0 + b), (x0 + a + 3, y0 + 6 - i, z0 + b + 3), name) for i, (a, b) in enumerate(spots)]
    if name == "iron_bars":
        return [DP._el((x0 + 7, y0, z0), (x0 + 9, y0 + 16, z0 + 16), name)]
    return None


def scene(data, ox, roof):
    """One shop: its blocks (without the roof for the look inside), the grass round its lot, the road to its door."""
    plain, own = [], []
    for x, y, z, block in data["blocks"]:
        if not roof and y >= ROOF:
            continue
        name, _, state = block.split(":")[1].partition("[")
        e = _own(name, state, x, y, z, ox)
        if e is None:
            plain.append([x, y, z, "minecraft:" + name])
        else:
            own += e
    els = DP.scene({"blocks": plain, "columns": [], "signs": data["signs"], "kind": "LAND"}, ox) + own
    # the ground between dock_preview's surroundings and the lot (a shop is narrower and shorter than a dock)
    for r0, r1, f0, f1 in ((-22, -13, -1, 41), (13, 22, -1, 41), (-12, 12, 23, 41), (-22, 22, -4, -1)):
        a, b = DP._px(r1 + 1, -2, f0), DP._px(r0, -1, f1 + 1)
        els.append(DP._el((a[0] + ox, a[1], a[2]), (b[0] + ox, b[1], b[2]), "grass"))
    return els


def main(out):
    font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 30)
    small = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 20)
    textures = {k: DP._tex(v, edge=k not in ("water", "grass", "road")) for k, v in {**DP.COLOURS, **COLOURS}.items()}
    data = json.loads(DATA.read_text())
    W, H = 1000, 760
    els = scene(data, 0, True) + scene(data, 8000, False)
    views = [
        {"x": 0, "y": 0, "w": W, "h": H, "az": -28, "el": 25, "k": 1.9, "radius": 24 * 16, "fov": 40, "far": 20000, "center": [0, 3 * 16, 8 * 16]},
        {"x": W, "y": 0, "w": W, "h": H, "az": -24, "el": 52, "k": 1.9, "radius": 24 * 16, "fov": 40, "far": 20000, "center": [8000, 1 * 16, 10 * 16]},
    ]
    DP.BUILD.mkdir(exist_ok=True)
    src, png = DP.BUILD / "tuning_shop.preview.json", DP.BUILD / "tuning_shop.png"
    src.write_text(json.dumps({"textures": textures, "elements": els, "grid": False, "views": views, "size": [W * 2, H]}))
    subprocess.run(["node", str(DP.RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(png).convert("RGB")
    sheet = Image.new("RGB", (W * 2, H + 96), (27, 29, 34))
    sheet.paste(im, (0, 96))
    d = ImageDraw.Draw(sheet)
    d.text((18, 10), "튜닝샵 (/관리자 튜닝샵 설치 <이름>)", fill=(245, 245, 245), font=font)
    d.text((18, 54), "열린 정문으로 차를 몰고 들어가 세우고, 문 옆 작업대 우클릭 또는 /튜닝 = 튜닝 메뉴", fill=(185, 190, 200), font=small)
    d.text((W + 18, 10), "지붕을 걷어 본 안쪽", fill=(245, 245, 245), font=font)
    d.text((W + 18, 54), "줄무늬 작업 구역 · 타이어 · 페인트 선반 · 공구 벽 (열리는 블록 없음)", fill=(185, 190, 200), font=small)
    sheet.save(out, optimize=True)
    print("tuning shop", out)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "previews" / "tuning_shop.png")
