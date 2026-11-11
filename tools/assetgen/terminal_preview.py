"""
Preview of the 출발 터미널 (economy: /관리자 출발지 설치) as the plugin builds it, drawn from the layout a test writes:

    cd economy && ./gradlew test          # TerminalTest writes economy/build/docks/terminal.json (DockLayout.terminal)
    python3 tools/assetgen/terminal_preview.py [out.png]     # docs/previews/terminal.png (needs: cd render && npm install)

Drawn as dock_preview draws a dock (plain-coloured cubes, no vanilla textures): the 화물 검사소 booth with its window and
counter, the inspection bay (yellow lines, a canopy with lights, the lift gate at its exit), the parking apron behind, and
the 국도 leaving the exit.
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
DATA = REPO / "economy" / "build" / "docks" / "terminal.json"


def main(out):
    font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 30)
    small = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 20)
    textures = {k: DP._tex(v, edge=k not in ("water", "grass", "road")) for k, v in DP.COLOURS.items()}
    for k, v in {"white_concrete": (207, 213, 214), "lectern": (150, 110, 70), "light_gray_concrete": (125, 125, 115)}.items():
        textures.setdefault(k, DP._tex(v))
    data = json.loads(DATA.read_text())
    els = DP.scene(data, 0)
    # the ground beside and behind the terminal (it is narrower and shorter than a dock)
    for r0, r1, f0, f1 in ((-22, -15, -1, 45), (15, 22, -1, 45), (-14, 14, 29, 45)):
        a, b = DP._px(r1 + 1, -2, f0), DP._px(r0, -1, f1 + 1)
        els.append(DP._el((a[0], a[1], a[2]), (b[0], b[1], b[2]), "grass"))
    t, e = DP.props(data, 0)
    textures.update(t)
    els += e
    W, H = 1400, 900
    views = [{"x": 0, "y": 0, "w": W, "h": H, "az": -36, "el": 34, "k": 1.6, "radius": 30 * 16, "fov": 40, "far": 20000,
              "center": [0, 3 * 16, 12 * 16]}]
    DP.BUILD.mkdir(exist_ok=True)
    src, png = DP.BUILD / "terminal.preview.json", DP.BUILD / "terminal.png"
    src.write_text(json.dumps({"textures": textures, "elements": els, "grid": False, "views": views, "size": [W, H]}))
    subprocess.run(["node", str(DP.RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    im = Image.open(png).convert("RGB")
    sheet = Image.new("RGB", (W, H + 96), (27, 29, 34))
    sheet.paste(im, (0, 96))
    d = ImageDraw.Draw(sheet)
    d.text((18, 10), "출발 터미널 (/관리자 출발지 설치): 화물 검사소 · 검사대 · 주차장, 국도는 출구에서 출발", fill=(245, 245, 245), font=font)
    d.text((18, 54), "검사대(노란 선, 지붕 아래)에 세우고 창구(지도 탁자) 우클릭 = 화물 검사 → 목적지 하역장 고르고 검사 완료 = 짐칸 봉인",
           fill=(185, 190, 200), font=small)
    sheet.save(out, optimize=True)
    print("terminal", out)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "previews" / "terminal.png")
