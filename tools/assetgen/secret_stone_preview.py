"""
Preview of the 숨은 비밀 기념비 stones (models/monument.py): both models up close, then ten in Monument's arc (radius 6,
100 degrees, each facing the centre; a few solved) seen from where a player walks up, a few blocks away.

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


def main(out):
    import economy_models as M
    BUILD.mkdir(exist_ok=True)
    old, new = M.ALL["secret_stone"]().to_preview(), M.ALL["secret_stone_solved"]().to_preview()
    tex = {**old["textures"], **new["textures"]}

    # close: the two side by side (face north = towards the camera at az 0)
    close = [dict(e, post=[]) for e in _placed(old, (-14, 0, 0), 180)] + [dict(e, post=[]) for e in _placed(new, (14, 0, 0), 180)]
    W, H = 1600, 1450
    src = BUILD / "secret_stone_close.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": close + _ground(tex, -40, -24, 40, 24), "grid": False, "size": [W, 720],
                               "views": [{"x": 0, "y": 0, "w": 800, "h": 720, "az": 28, "el": 14, "k": 1.9, "center": [0, 15, 0], "radius": 32},
                                         {"x": 800, "y": 0, "w": 800, "h": 720, "az": 0, "el": 4, "k": 1.9, "center": [0, 15, 0], "radius": 32}]}))
    a = BUILD / "secret_stone_close.png"
    subprocess.run(["node", str(RENDER), str(src), str(a)], check=True, stdout=subprocess.DEVNULL)

    # the arc: Monument.stoneAt for 10 stones round (0, 0), base yaw 0 (they stand to the south, facing back north)
    els = []
    n, r = 10, 6 * 16
    solved = {1, 4, 5, 8}
    for i in range(n):
        ang = -math.radians(100) / 2 + math.radians(100) * i / (n - 1)
        fx, fz = -math.sin(ang), math.cos(ang)
        yaw = math.degrees(math.atan2(fx, -fz))
        els += _placed(new if i in solved else old, (fx * r, 0, fz * r), yaw)
    src = BUILD / "secret_stone_arc.preview.json"
    src.write_text(json.dumps({"textures": tex, "elements": els + _ground(tex, -140, -60, 140, 140), "grid": False, "size": [W, 700],
                               "views": [{"x": 0, "y": 0, "w": W, "h": 700, "az": 0, "el": 14, "k": 1.0, "center": [0, 22, 70],
                                          "radius": 150, "fov": 45, "far": 5000}]}))
    b = BUILD / "secret_stone_arc.png"
    subprocess.run(["node", str(RENDER), str(src), str(b)], check=True, stdout=subprocess.DEVNULL)

    head = 60
    sheet = Image.new("RGB", (W, 720 + 700 + head * 2), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    f, s = _font(28), _font(18)
    d.text((20, 10), "숨은 비밀 기념비 · secret_stone (아직 못 풂) / secret_stone_solved (풀림)", fill=(240, 240, 240), font=f)
    d.text((20, 40), "왼쪽: 비스듬히, 오른쪽: 정면. 1칸 폭, 받침 포함 1.75칸 높이", fill=(160, 165, 175), font=s)
    sheet.paste(Image.open(a).convert("RGB"), (0, head))
    y = head + 720
    d.text((20, y + 10), "반원 배치 (반지름 6칸, 100°, 가운데를 바라봄): 10개 중 4개 풀림, 몇 칸 떨어져서", fill=(240, 240, 240), font=f)
    sheet.paste(Image.open(b).convert("RGB"), (0, y + head))
    sheet.save(out, optimize=True)
    print("stones ", out)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "previews" / "secret_stone.png")
