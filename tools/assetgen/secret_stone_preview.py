"""
Preview of the 숨은 비밀 기념비 stones (models/monument.py): the unsolved stone, the ten per-secret solved stones (each
with its gold emblem) and the generic star one face on, labelled; then the ten solved in Monument's arc (radius 6,
100 degrees, each facing the centre) seen from where a player walks up, a few blocks away.

    python3 tools/assetgen/secret_stone_preview.py [out.png]     # docs/previews/secret_stone.png (needs: cd render && npm install)
"""

import json
import math
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
REPO = HERE.parent.parent
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FONT = Path("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc")


def _font(size):
    for f in (FONT, *sorted((HERE / "fonts").glob("*.ttf")), *sorted((HERE / "fonts").glob("*.otf"))):
        try:
            return ImageFont.truetype(str(f), size)
        except OSError:
            continue
    return ImageFont.load_default()


def _placed(pv, at, yaw):
    """Model elements as a FIXED item display (scale 1, lifted 0.5) shows them at `at` (preview px) with this yaw:
    model point m -> m - 8 from the entity, lifted 8; turned 180, then by the yaw (Monument)."""
    ex, ey, ez = at
    out = []
    for e in pv["elements"]:
        e = json.loads(json.dumps(e))
        e["from"] = [ex + e["from"][0] - 8, ey + e["from"][1], ez + e["from"][2] - 8]
        e["to"] = [ex + e["to"][0] - 8, ey + e["to"][1], ez + e["to"][2] - 8]
        if e.get("rotation"):
            axis, ang, o = e["rotation"]
            e["rotation"] = [axis, ang, [ex + o[0] - 8, ey + o[1], ez + o[2] - 8]]
        e["post"] = [["y", -(yaw + 180), [ex, ey, ez]]]
        out.append(e)
    return out


def _ground(tex, x0, z0, x1, z1):
    from PIL import Image as I
    import base64
    import io
    im = I.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            n = ((x * 7 + y * 13) % 5 - 2) * 4
            px[x, y] = (96 + n, 140 + n, 62 + n, 255)
    b = io.BytesIO()
    im.save(b, "PNG")
    tex["grass"] = "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()
    return [{"from": [x0, -16, z0], "to": [x1, 0, z1], "shade": True, "rotation": None,
             "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "grass"}}}]


# the stones in Monument's order (stones(): the bus first, then the secrets as registered), with their emblems
ORDER = [("bus", "버스 · 선글라스"), ("dongbang", "동방 · 닻"), ("letter", "편지 · 봉투"), ("designer", "설계자 · 톱니"),
         ("usher", "안내원 · 열쇠 구멍"), ("fish", "물고기 · 물고기"), ("lostkey", "잃어버린 열쇠 · 열쇠"),
         ("doll", "인형 · 단추"), ("scarecrow", "허수아비 · 밀 이삭")]


def main(out):
    import economy_models as M
    BUILD.mkdir(exist_ok=True)
    old = M.ALL["secret_stone"]().to_preview()
    star = M.ALL["secret_stone_solved"]().to_preview()
    solved = [M.ALL[f"secret_stone_solved_{k}"]().to_preview() for k, _ in ORDER]
    tex = {**old["textures"], **star["textures"]}
    for pv in solved:
        tex.update(pv["textures"])

    # close: every stone face on, 4 to a row (the unsolved one, the ten emblems, the generic star), each in its own view
    W, cols, cw, ch = 1600, 4, 400, 430
    cells = [(old, "아직 못 풂 (secret_stone)")] + [(pv, f"{lbl}") for pv, (_, lbl) in zip(solved, ORDER)] \
        + [(star, "기본 별 (secret_stone_solved)")]
    els, views = [], []
    for i, (pv, _) in enumerate(cells):
        cx = i * 48
        els += [dict(e, post=[]) for e in _placed(pv, (cx, 0, 0), 180)]
        views.append({"x": (i % cols) * cw, "y": (i // cols) * ch, "w": cw, "h": ch, "az": 0, "el": 6, "k": 1.7,
                      "center": [cx, 15, 0], "radius": 26})
    rows = (len(cells) + cols - 1) // cols
    src = BUILD / "secret_stone_close.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els + _ground(tex, -24, -24, len(cells) * 48, 24), "grid": False,
                               "size": [W, rows * ch], "views": views}))
    a = BUILD / "secret_stone_close.png"
    subprocess.run(["node", str(RENDER), str(src), str(a)], check=True, stdout=subprocess.DEVNULL)

    # the arc: Monument.stoneAt for 10 stones round (0, 0), base yaw 0 (they stand to the south, facing back north);
    # all solved, each with its emblem, seen from the middle a few blocks away
    els = []
    n, r = 10, 6 * 16
    for i in range(n):
        ang = -math.radians(100) / 2 + math.radians(100) * i / (n - 1)
        fx, fz = -math.sin(ang), math.cos(ang)
        yaw = math.degrees(math.atan2(fx, -fz))
        els += _placed(solved[i], (fx * r, 0, fz * r), yaw)
    src = BUILD / "secret_stone_arc.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els + _ground(tex, -140, -60, 140, 140), "grid": False, "size": [W, 700],
                               "views": [{"x": 0, "y": 0, "w": W, "h": 700, "az": 0, "el": 14, "k": 1.0, "center": [0, 22, 70],
                                          "radius": 150, "fov": 45, "far": 5000}]}))
    b = BUILD / "secret_stone_arc.png"
    subprocess.run(["node", str(RENDER), str(src), str(b)], check=True, stdout=subprocess.DEVNULL)

    head = 70
    closeH = rows * ch
    sheet = Image.new("RGB", (W, closeH + 700 + head * 2), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    f, s = _font(28), _font(20)
    d.text((20, 10), "숨은 비밀 기념비 · 못 푼 비석 / 비밀마다 금 문양 (secret_stone_solved_<id>)", fill=(240, 240, 240), font=f)
    d.text((20, 40), "정면. 1칸 폭, 받침 포함 1.75칸 높이", fill=(160, 165, 175), font=s)
    sheet.paste(Image.open(a).convert("RGB"), (0, head))
    for i, (_, lbl) in enumerate(cells):
        x, y = (i % cols) * cw, head + (i // cols) * ch
        d.text((x + 12, y + 8), lbl, fill=(240, 220, 150), font=s, stroke_width=2, stroke_fill=(20, 20, 24))
    y = head + closeH
    d.text((20, y + 10), "반원 배치 (반지름 6칸, 100°, 가운데를 바라봄): 10개 모두 풀림, 몇 칸 떨어져서", fill=(240, 240, 240), font=f)
    sheet.paste(Image.open(b).convert("RGB"), (0, y + head))
    sheet.save(out, optimize=True)
    print("stones ", out)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "previews" / "secret_stone.png")
