"""
Preview sheet of the toy series (models/toys.py): docs/previews/toy_tools.png

    python3 tools/assetgen/toys_preview.py [out.png]        (needs: cd render && npm install)

A row a piece, and a first row with a plain 16 x 16 sprite (a grey sword, drawn here: not a game file) held the vanilla
way, to compare sizes with. Columns:

  inventory     the model under its "gui" transform, seen as the inventory draws it (from +z), in a slot's 16 x 16
  3/4           the model alone
  in the hand   third person: the model where the game puts a right-hand item on a standing player
                (ItemInHandLayer: the arm's end, turned Rx(-90) Ry(180), moved (1, 2, -10) / 16, then the model's
                "thirdperson_righthand" transform). The same camera for every row, so sizes compare.
  own eyes      first person: the model at (0.56, -0.52, -0.72) blocks in front of the camera, under its
                "firstperson_righthand" transform, through a 70 degree lens (the game draws the hand at 70 whatever the
                player's field of view), 16 : 9. No arm: the game draws none while an item is held.

A display transform puts a model point p at  translation + R * scale * (p - (8, 8, 8)),  R = Rx * Ry * Rz.
These pictures are computed from the transforms, not taken in the game.
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
from models import toys  # noqa: E402
from stsv_assets import presets  # noqa: E402
from stsv_assets.model import Model  # noqa: E402
from stsv_assets import textures as T  # noqa: E402

REPO = HERE.parent.parent
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FACES = ("north", "east", "south", "west", "up", "down")

ROWS = [
    ("toy_sword", "장난감 검"), ("toy_pickaxe", "장난감 곡괭이"), ("toy_shovel", "장난감 삽"), ("toy_axe", "장난감 도끼"),
    ("toy_hoe", "장난감 괭이"), ("toy_fishing_rod", "장난감 낚싯대"), ("toy_fishing_rod_cast", "낚싯대 (던진 뒤)"),
    ("party_firework", "파티 폭죽 발사기"),
]
GAP = 3000.0     # between the pieces of the one scene the renderer draws (each view looks at its own)


def _flat(rgb):
    im = Image.new("RGBA", (16, 16), tuple(rgb) + (255,))
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


EXTRA = {"pv_skin": _flat((198, 152, 120)), "pv_shirt": _flat((60, 150, 160)), "pv_legs": _flat((62, 66, 140)), "pv_hair": _flat((70, 46, 30)),
         "pv_slot": _flat((55, 58, 68)), "pv_slot_rim": _flat((32, 34, 40)), "pv_grey": _flat((150, 156, 165)), "pv_wood": _flat((112, 78, 44))}


def _box(frm, to, tex):
    return {"from": list(frm), "to": list(to), "glow": False, "shade": True, "rotation": None,
            "faces": {f: {"uv": [0, 0, 16, 16], "texture": tex} for f in FACES}}


def sprite():
    """A 16 x 16 sprite's worth of sword, one unit thick, on the diagonal a vanilla tool is drawn on: the size to compare with."""
    m = Model("pv_sprite")
    cells = {(2, 2): "w", (3, 3): "w", (4, 4): "w", (3, 6): "w", (4, 5): "w", (5, 5): "w", (6, 4): "w", (6, 3): "w", (5, 6): "w"}
    for i in range(6, 14):
        cells[(i, i)] = "g"
        cells[(i + 1, i)] = "g"
        cells[(i, i + 1)] = "g"
    cells[(14, 14)] = "g"
    for (x, y), c in cells.items():
        m.box("px", (x, y, 7.5), (x + 1, y + 1, 8.5), "dev_grey")
        m.elements[-1]["pv"] = "pv_grey" if c == "g" else "pv_wood"
    m.display = {**presets.HANDHELD, "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    return m


def elements(m):
    if m.name == "pv_sprite":
        return [_box(e["from"], e["to"], e["pv"]) for e in m.elements]
    return m.to_preview()["elements"]


def placed(els, transform, at, turn_x=0.0):
    """The elements under a display transform, the model's centre's target (before the translation) at `at`.
    turn_x: more degrees about x after the transform's own (the hand's frame)."""
    rx, ry, rz = transform.get("rotation", [0, 0, 0])
    s = transform.get("scale", [1, 1, 1])[0]
    out = []
    for e in els:
        e = json.loads(json.dumps(e))
        for k in ("from", "to"):
            e[k] = [at[i] + s * (e[k][i] - 8) for i in range(3)]
        if e.get("rotation"):
            e["rotation"][2] = [at[i] + s * (e["rotation"][2][i] - 8) for i in range(3)]
        e["post"] = [["z", rz, list(at)], ["y", ry, list(at)], ["x", rx + turn_x, list(at)]]
        out.append(e)
    return out


def player(o):
    """A standing player (32 units tall), feet on y = 0 at o, facing -z; its right arm is on +x."""
    def b(x0, y0, z0, x1, y1, z1, tex):
        return _box((o[0] + x0, o[1] + y0, o[2] + z0), (o[0] + x1, o[1] + y1, o[2] + z1), tex)
    return [b(-4, 24, -4, 4, 32, 4, "pv_skin"), b(-4.25, 29, -4.25, 4.25, 32.25, 4.25, "pv_hair"), b(-4, 12, -2, 4, 24, 2, "pv_shirt"),
            _arm(b(4, 12, -2, 8, 24, 2, "pv_skin"), o), _arm(b(4, 20, -2.1, 8, 24.1, 2.1, "pv_shirt"), o), b(-8, 12, -2, -4, 24, 2, "pv_skin"),
            b(-8, 20, -2.1, -4, 24.1, 2.1, "pv_shirt"), b(-4, 0, -2, 0, 12, 2, "pv_legs"), b(0, 0, -2, 4, 12, 2, "pv_legs")]


ARM = 18.0     # an arm that holds an item is held this far forward (HumanoidModel: xRot = -pi / 10)


def _arm(e, o):
    """Turns a piece about the right shoulder as the game does for an arm with an item in its hand."""
    e.setdefault("post", []).append(["x", ARM, [o[0] + 5, o[1] + 22, o[2]]])
    return e


def in_hand(m, o):
    """Third person, right hand: the hand's frame is the item's turned Rx(-90) (model space flipped to the world's),
    its origin the arm's end (6, 12, -2); the transform's translation goes through the same turn."""
    t = m.display["thirdperson_righthand"]
    tx, ty, tz = t["translation"]
    at = (o[0] + 6 + tx, o[1] + 12 + tz, o[2] - 2 - ty)
    return [_arm(e, o) for e in placed(elements(m), t, at, turn_x=-90)]


def first_person(m, o):
    t = m.display["firstperson_righthand"]
    tx, ty, tz = t["translation"]
    return placed(elements(m), t, (o[0] + 0.56 * 16 + tx, o[1] - 0.52 * 16 + ty, o[2] - 0.72 * 16 + tz))


def in_slot(m, o):
    t = m.display["gui"]
    tx, ty, tz = t["translation"]
    els = placed(elements(m), t, (o[0] + tx, o[1] + ty, o[2] + tz))
    els.append(_box((o[0] - 9, o[1] - 9, o[2] - 60.5), (o[0] + 9, o[1] + 9, o[2] - 60), "pv_slot_rim"))
    els.append(_box((o[0] - 8, o[1] - 8, o[2] - 60), (o[0] + 8, o[1] + 8, o[2] - 59.9), "pv_slot"))
    return els


def main(out):
    models = [sprite()] + [toys.ALL[n]() for n, _ in ROWS]
    labels = ["바닐라 크기 (16 x 16 스프라이트, 비교용)"] + [f"{t}  ·  stsv:{n}" for n, t in ROWS]
    textures = dict(EXTRA)
    for m in models[1:]:
        textures.update(m.to_preview()["textures"])
    W_SLOT, W_Q, W_HAND, W_EYES, H, TOP = 230, 330, 420, 440, 250, 96
    width, height = W_SLOT + W_Q + W_HAND + W_EYES, TOP + H * len(models)
    els, views = [], []
    for r, m in enumerate(models):
        y0 = TOP + r * H
        o = (r * GAP, 0.0, 0.0)
        # inventory slot: seen from +z, far away (nearly flat), the slot's 18 units filling most of the cell
        els += in_slot(m, o)
        views.append({"x": 20, "y": y0 + 20, "w": 210, "h": 210, "az": 180, "el": 0, "center": [o[0], 0, 0], "radius": 9.6, "k": 57, "fov": 2,
                      "far": 2000, "near": 100})
        # three-quarter view of the model alone
        o2 = (r * GAP, 0.0, GAP)
        q = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
        els += placed(elements(m), q, (o2[0], o2[1], o2[2]))
        ys = [p[1] for p in toys.corners(m)] if m.name != "pv_sprite" else [2, 15]
        views.append({"x": W_SLOT, "y": y0 + 30, "w": W_Q, "h": H - 30, "az": 212, "el": 16,
                      "center": [o2[0], o2[1] + (min(ys) + max(ys)) / 2 - 8, o2[2]], "radius": (max(ys) - min(ys)) / 2 + 2.5, "k": 5.7, "fov": 20, "far": 2000})
        # in the hand
        o3 = (r * GAP, 0.0, 2 * GAP)
        els += player(o3) + in_hand(m, o3)
        if r:
            # the same player with the plain sprite, to the left of this one in the picture (the camera looks from az 58)
            o3b = (o3[0] + 17.0, 0.0, o3[2] + 27.2)
            els += player(o3b) + in_hand(models[0], o3b)
        views.append({"x": W_SLOT + W_Q, "y": y0, "w": W_HAND, "h": H, "az": 58, "el": 10,
                      "center": [o3[0] + (11.5 if r else 3), 17, o3[2] + (5.6 if r else -8)], "radius": 25, "k": 5.9, "fov": 20, "far": 2000})
        # own eyes
        o4 = (r * GAP, 0.0, 3 * GAP)
        els += first_person(m, o4)
        views.append({"x": W_SLOT + W_Q + W_HAND + 10, "y": y0 + 4, "w": 430, "h": 242, "az": 180, "el": 0, "center": [o4[0], 0, o4[2] - 16],
                      "radius": 16, "k": 1, "fov": 70, "near": 0.8, "far": 600})
    BUILD.mkdir(exist_ok=True)
    src, png = BUILD / "toy_tools.preview.json", BUILD / "toy_tools.png"
    src.write_text(json.dumps({"textures": textures, "elements": els, "grid": False, "views": views, "size": [width, height]}))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, stdout=subprocess.DEVNULL)
    sheet = Image.open(png).convert("RGB")
    d = ImageDraw.Draw(sheet)
    font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 28)
    small = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 18)
    d.rectangle((0, 0, width, TOP - 1), fill=(27, 29, 34))
    d.text((18, 10), "장난감 시리즈 (도구 스킨) · 파티 폭죽 발사기", fill=(245, 245, 245), font=font)
    for x, text in ((20, "인벤토리 칸"), (W_SLOT + 20, "모델"), (W_SLOT + W_Q + 20, "3인칭 (왼쪽 바닐라 크기 · 오른쪽 장난감)"),
                    (W_SLOT + W_Q + W_HAND + 20, "1인칭 (시야 70도)")):
        d.text((x, 60), text, fill=(185, 190, 200), font=small)
    for r, label in enumerate(labels):
        y0 = TOP + r * H
        d.line((0, y0, width, y0), fill=(52, 56, 66))
        d.text((W_SLOT + 12, y0 + 8), label, fill=(225, 228, 235), font=small)
        d.rectangle((W_SLOT + W_Q + W_HAND + 9, y0 + 3, W_SLOT + W_Q + W_HAND + 440, y0 + 246), outline=(70, 74, 86))
    sheet.save(out, optimize=True)
    print("toy tools", out)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "previews" / "toy_tools.png")
