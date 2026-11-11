"""
Preview sheet of the 통통배 and the 지게 (needs: cd render && npm install, or a global playwright):

    python3 vehicle_misc_preview.py [out.png]            # docs/previews/vehicles_misc.png
    python3 vehicle_misc_preview.py --boat out.png [stage] # the boat large from four sides (working on the model)
    python3 vehicle_misc_preview.py --jige out.png [stage] # the 지게 on a player from four sides

Put together as the plugin shows them, from content/stsv/items/vehicles_misc.yml: the boat's body (drawn at half size,
shown at body-scale), its propeller where `wheels` puts it, its catch where `cargo` puts it, players in its `seats`
(orange the helmsman, green the passengers), on water at its draft (water.draft) with a player on a jetty and a block for
scale; then out of the water (the hull under the waterline), and each catch stage. The 지게 on a player-sized mannequin
at the cosmetics' back anchor (as CosmeticAttachments places it), from four sides, and each load stage. Then the icons.
"""

import base64
import copy
import io
import json
import re
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from models import vehicles_misc as V      # noqa: E402
import vehicle_icons_misc as VI            # noqa: E402
import yaml                                # noqa: E402
from furniture_preview import _ref_models  # noqa: E402
from vehicle_preview import sitting, _xf  # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
ITEMS = REPO / "content/stsv/items/vehicles_misc.yml"
U = 0.9375
TOP = 1.8 * 16                      # the top of the player's hitbox (drawn px above their feet)
BACK = (0, -0.62, -0.12)            # CosmeticAttachments.DEFAULT_ANCHORS back (left, up, front; blocks)


def items():
    return yaml.safe_load(ITEMS.read_text())


def _png(im):
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def _solid(rgb):
    return _png(Image.new("RGBA", (16, 16), (*rgb, 255)))


def _water_tex():
    """A calm sea seen from above: blue with a few lighter ripple dashes."""
    from stsv_assets.palette import ladder
    im = Image.new("RGBA", (16, 16), ladder("water", 2))
    px = im.load()
    for (x, y) in ((2, 3), (3, 3), (9, 6), (10, 6), (11, 6), (5, 11), (6, 11), (13, 13), (14, 13), (0, 8)):
        px[x, y] = ladder("water", 3)
    for (x, y) in ((7, 1), (12, 9), (1, 14)):
        px[x, y] = ladder("water", 1)
    return _png(im)


def _box(frm, to, tex, faces=("north", "south", "east", "west", "up", "down")):
    return {"from": list(frm), "to": list(to), "glow": False, "shade": True, "rotation": None,
            "faces": {f: {"uv": [0, 0, 16, 16], "texture": tex} for f in faces}}


def _model(name):
    return V.ALL[name]().to_preview()


def part(name, at, ox):
    """A part model (attachment point at model (8, 8, 8)) at a vehicle position [right, up, forward] (blocks)."""
    data = _model(name)
    ax, ay, az = at[0] * 16 + ox[0], at[1] * 16 + ox[1], -at[2] * 16 + ox[2]
    return data["textures"], _xf(data["elements"], lambda p: [p[0] - 8 + ax, p[1] - 8 + ay, p[2] - 8 + az])


def boat(ox, cargo=None, riders=True):
    spec = items()["fishing_boat"]["vehicle"]
    body = _model("fishing_boat")
    k = spec["body-scale"]
    textures = dict(body["textures"])
    elements = _xf(body["elements"], lambda p: [(p[0] - 8) * k + ox[0], (p[1] - 8) * k + ox[1], (p[2] - 8) * k + ox[2]])
    for w in spec["wheels"]:
        t, e = part(spec["wheel"], w["at"], ox)
        textures.update(t)
        elements += e
    if cargo:
        t, e = part(spec["cargo"]["stages"][cargo - 1], spec["cargo"]["at"], ox)
        textures.update(t)
        elements += e
    if riders:
        for i, seat in enumerate(spec["seats"]):
            at = [seat["at"][0], seat["at"][1] + ox[1] / 16, seat["at"][2]]
            elements += sitting(at, ox, "ref_rider" if i == 0 else "ref_passenger")
    return textures, elements, spec


def render(data, tag):
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / f"vmisc_{tag}.json", BUILD / f"vmisc_{tag}.png"
    src.write_text(json.dumps(data))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, capture_output=True)
    return Image.open(png).convert("RGB")


def worn(stage, ox, pose_tex="ref_wearer"):
    """A player-sized mannequin at ox (feet), facing north (-z), with the 지게 at the back anchor."""
    from cosmetics_preview import mannequin
    man = mannequin().to_preview()
    # the mannequin is drawn around x/z 8: move its feet to ox
    els = _xf(man["elements"], lambda p: [p[0] - 8 + ox[0], p[1] + ox[1], p[2] - 8 + ox[2]])
    name = "jige" if stage == 0 else f"jige_load_{stage}"
    d = _model(name)
    off = (-BACK[0] * 16, BACK[1] * 16, -BACK[2] * 16)
    els += _xf(d["elements"], lambda p: [ox[0] + off[0] + (p[0] - 8), ox[1] + TOP + off[1] + (p[1] - 8), ox[2] + off[2] + (p[2] - 8)])
    return {**man["textures"], **d["textures"]}, els


def sheet(out):
    player, block = _ref_models()
    textures = {**player["textures"], **block["textures"], "ref_rider": _solid((222, 152, 60)),
                "ref_passenger": _solid((120, 160, 120)), "water": _water_tex()}
    spec = items()["fishing_boat"]["vehicle"]
    L, W, H = spec["size"]
    draft = spec["water"]["draft"] * 16
    elements, views, labels = [], [], []
    W1600, ch, head, fov = 1600, 330, 34, 26
    cw = W1600 // 4

    # row 0: the boat on the water (keel draft under the surface: the water's top at y = 0 here), four sides
    ox = (0, -draft, 0)
    t, e, _ = boat(ox)
    textures.update(t)
    elements += e
    elements.append(_box((-150, -40, -150), (150, 0, 150), "water"))
    # a jetty beside it (two blocks out of the water) with a player standing on it, for scale
    jx = W * 8 + 14
    for dz in (-24, -8, 8):
        elements += _xf(block["elements"], lambda p, dz=dz: [p[0] + jx, p[1] - 6, p[2] + dz])
    elements += _xf(player["elements"], lambda p: [p[0] + jx + 8, p[1] + 10, p[2] + 2])
    for c, (label, az, el, kk) in enumerate([("통통배 · 앞 왼쪽", -40, 20, 1.45), ("뒤 오른쪽 (어창·굴뚝)", 140, 26, 1.45),
                                              ("옆 · 사람(1.8칸)·블록과 크기 비교", -90, 5, 1.5), ("위", -20, 75, 1.75)]):
        views.append({"x": c * cw, "y": 0, "w": cw, "h": ch, "az": az, "el": el, "k": 2.9 * kk, "radius": L * 8,
                      "fov": fov, "far": 4000, "center": [6 if c == 2 else 0, 14, 0]})
        labels.append((c * cw, 0, cw, label, c == 0))

    # row 1: out of the water from the side (the hull under the waterline, the propeller, the rudder), and the catch
    ox2 = (2000, 0, 0)
    t, e, _ = boat(ox2, riders=False)
    textures.update(t)
    elements += e
    cw5 = W1600 // 5
    views.append({"x": 0, "y": ch, "w": cw5, "h": ch, "az": -110, "el": 8, "k": 4.4, "radius": L * 8, "fov": fov, "far": 4000,
                  "center": [ox2[0], 18, 0]})
    labels.append((0, 1, cw5, "물 밖 (흘수선 아래, 프로펠러)", False))
    for k in range(1, 5):
        oxk = (4000 + k * 1500, -draft, 0)
        t, e, _ = boat(oxk, cargo=k, riders=False)
        textures.update(t)
        elements += e
        elements.append(_box((oxk[0] - 150, -40, -150), (oxk[0] + 150, 0, 150), "water"))
        views.append({"x": k * cw5, "y": ch, "w": cw5, "h": ch, "az": 155, "el": 42, "k": 4.6, "radius": 30, "fov": fov, "far": 4000,
                      "center": [oxk[0], 16, 26]})
        labels.append((k * cw5, 1, cw5, ["어창 ~25% (생선 상자)", "~50% (그물·아이스박스)", "~75% (부표)", "가득 (270칸, 방수포)"][k - 1], False))

    # row 2: the 지게 worn, full, from four sides; row 3: each load stage from behind
    for c, (label, az, el) in enumerate([("지게 · 뒤 왼쪽 (가득)", 215, 14), ("뒤 오른쪽", 145, 14), ("옆", 90, 6), ("앞", 20, 10)]):
        oxj = (20000 + c * 300, 0, 0)
        t, e = worn(3, oxj)
        textures.update(t)
        elements += e
        views.append({"x": c * cw, "y": 2 * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 3.9, "radius": 22, "fov": fov, "far": 4000,
                      "center": [oxj[0] - (6 if c == 2 else 0), 19, 4]})
        labels.append((c * cw, 2, cw, label, c == 0))
    for s in range(4):
        oxj = (23000 + s * 300, 0, 0)
        t, e = worn(s, oxj)
        textures.update(t)
        elements += e
        views.append({"x": s * cw, "y": 3 * ch, "w": cw, "h": ch, "az": 200, "el": 18, "k": 3.9, "radius": 22, "fov": fov, "far": 4000,
                      "center": [oxj[0], 19, 4]})
        labels.append((s * cw, 3, cw, ["빈 지게", "짐 ~33% (나뭇단)", "~66% (나뭇단·곡식 자루)", "가득 (54칸, 거적)"][s], False))

    rows = 4
    data = {"textures": textures, "elements": elements, "grid": False, "size": [W1600, rows * ch], "views": views}
    im = render(data, "sheet")

    font = ImageFont.truetype(FONT, 21)
    small = ImageFont.truetype(FONT, 16)
    top, icon_h = 50, 150
    out_im = Image.new("RGB", (W1600, top + rows * (ch + head) + icon_h), (27, 29, 34))
    d = ImageDraw.Draw(out_im)
    d.text((14, 12), "통통배 (특수) · 지게 (1세대) · 관리자 아이템 · 주황 = 운전자, 초록 = 동승자 · 블록 1칸과 사람(1.8칸) · "
           "배는 흘수 6px 로 물에 떠 있음 · 지게는 가슴 칸에 입고 등에 보임", font=small, fill=(235, 232, 222))
    for r in range(rows):
        w = cw5 if r == 1 else cw
        for c in range(W1600 // w):
            out_im.paste(im.crop((c * w, r * ch, c * w + w, r * ch + ch)), (c * w, top + r * (ch + head) + head))
    for (x, r, w, text, big) in labels:
        y = top + r * (ch + head)
        d.rectangle((x, y, x + w - 2, y + head - 2), fill=(40, 43, 52))
        d.text((x + 8, y + 5), text, font=font if big else small, fill=(245, 242, 232))
    y0 = top + rows * (ch + head) + 10
    d.text((14, y0 - 4), "인벤토리 아이콘 (16px · 실제 크기)", font=small, fill=(170, 170, 178))
    names = {"fishing_boat": "통통배", "jige": "지게"}
    for k, vid in enumerate(("fishing_boat", "jige")):
        icon = VI.ICONS[vid]()
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        out_im.paste(bg.convert("RGB"), (14 + k * 236, y0 + 20))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        out_im.paste(slot.convert("RGB"), (120 + k * 236, y0 + 80))
        d.text((120 + k * 236, y0 + 24), names[vid], font=small, fill=(240, 240, 240))
    out_im.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


def close_boat(out, stage=0):
    t, e, spec = boat((0, 0, 0), cargo=stage or None, riders=False)
    t = {**t, "ref_rider": _solid((222, 152, 60)), "ref_passenger": _solid((120, 160, 120))}
    L = spec["size"][0]
    r, c = L * 8, [0, 18, 0]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 3.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 3.2, "radius": r, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": 30, "el": 8, "k": 3.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": -100, "el": 40, "k": 3.2, "radius": r, "fov": 26, "center": c}]
    render({"textures": t, "elements": e, "grid": False, "views": views}, "one_boat").save(out)
    print(f"close-up {out}")


def close_jige(out, stage=3):
    t, e = worn(stage, (0, 0, 0))
    c = [0, 19, 6]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": 215, "el": 14, "k": 2.1, "radius": 34, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 20, "k": 2.1, "radius": 34, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": 90, "el": 6, "k": 2.1, "radius": 34, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 20, "el": 10, "k": 2.1, "radius": 34, "fov": 26, "center": c}]
    render({"textures": t, "elements": e, "grid": False, "views": views}, "one_jige").save(out)
    print(f"close-up {out}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--boat":
        close_boat(Path(sys.argv[2]), int(sys.argv[3]) if len(sys.argv) > 3 else 0)
    elif len(sys.argv) > 1 and sys.argv[1] == "--jige":
        close_jige(Path(sys.argv[2]), int(sys.argv[3]) if len(sys.argv) > 3 else 3)
    else:
        sheet(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicles_misc.png")
