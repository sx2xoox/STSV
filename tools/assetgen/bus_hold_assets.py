"""
마을버스 손님이 손에 든 물건 (bus.yml types.*.hold / hold-off, models/bus_hold.py): writes only these models, the
skins they replace a painting on, and the preview sheets.

    python3 tools/assetgen/bus_hold_assets.py              # models + skins + docs/previews/bus_hold_items.png, bus_chicken_hold.png
    python3 tools/assetgen/bus_hold_assets.py --no-sheet   # models + skins only
    python3 tools/assetgen/bus_hold_assets.py --sheet-only # the sheets only (nothing in the pack touched)
    (the sheets need: cd tools/assetgen/render && npm install)

  models  economy/content/economy/models/bus_hold_*.bbmodel (economy_models.ALL has them too: a full economy_build.py
          writes the same files)
  skins   economy/content/economy/assets/economy/textures/entity/bus/<type>_<1..3>.png and <type>_s1.png of the kinds in
          SKIN_TYPES only (economy_bus_skins: the prop no longer painted on them); every other skin is left alone
  sheets  each kind with something in hand, its looks seated (as on the bus and the bench) and standing, front and three
          quarters, the skin on a posed player model with the held models where the game puts them (computed from the
          display transforms and the arm poses, not a screenshot of the game)
"""

import base64
import io
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
REPO = HERE.parent.parent
PACK = REPO / "economy" / "content" / "economy"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
SHEET = REPO / "docs" / "previews" / "bus_hold_items.png"
CHICKEN = REPO / "docs" / "previews" / "bus_chicken_hold.png"
FONT = HERE / "fonts" / "Sunflower-Medium.ttf"

# the kinds whose skins changed (their held prop is a model now): only these skins are rewritten
HOLDS = {
    "chicken": [("bus_hold_chicken", None)] * 3,
    "interview": [(None, "bus_hold_folder")] * 3,
    "yeot": [("bus_hold_yeot_scissors", None)] * 3,
    "traveler": [(None, "bus_hold_map")] * 3,
    "youtuber": [("bus_hold_selfie", None)] * 3,
    "crumbs": [(None, "bus_hold_snack")] * 3,
    "shaman": [("bus_hold_bells", None), (None, "bus_hold_fan"), ("bus_hold_bells", "bus_hold_fan")],
    "dogwalker": [(None, "bus_hold_leash")] * 3,
    "motionsick": [(None, None), (None, None), ("bus_hold_towel", "bus_hold_plastic_bag")],
    "kid": [(None, None), (None, None), (None, "bus_hold_shoe_bag")],
    "bride": [("bus_hold_bouquet", None), (None, None), (None, None)],
    "magician": [("bus_hold_wand", None), (None, "bus_hold_cards"), ("bus_hold_wand", "bus_hold_rose")],
}
SKIN_TYPES = list(HOLDS)


def write_models():
    from models import bus_hold as H
    (PACK / "models").mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(exist_ok=True)
    for name, fn in H.ALL.items():
        fn().write(PACK / "models" / f"{name}.bbmodel", BUILD / f"eco_{name}.preview.json")
    return list(H.ALL)


def write_skins():
    """Only the kinds of SKIN_TYPES: their three looks and their shiny one, the same functions the full script uses."""
    import economy_bus_skins as B
    out = []
    idx = {tid: i for i, (tid, _, _) in enumerate(B.TYPES)}
    fns = {tid: fn for tid, _, fn in B.TYPES}
    for tid in SKIN_TYPES:
        for v in range(1, B.VARIANTS + 1):
            s = fns[tid](v)
            s.finish().save(B.OUT / f"{tid}_{v}.png")
            out.append(f"{tid}_{v}")
        if tid not in B.SHINY_SKIP:
            B.shiny(tid, fns[tid], idx[tid]).finish().save(B.OUT / f"{tid}_s1.png")
            out.append(f"{tid}_s1")
    return out


# ------------------------------------------------------------------------------------------------
# A posed player (the vanilla PlayerModel, model space y down) with a skin, and what it holds
# ------------------------------------------------------------------------------------------------

def _rzyx(x, y, z):
    """ModelPart's turn: Quaternionf.rotationZYX(z, y, x) = Rz Ry Rx (radians), model space."""
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


def _affine(r, t):
    m = np.eye(4)
    m[:3, :3] = r
    m[:3, 3] = t
    return m


def _tr(t):
    return _affine(np.eye(3), t)


ROOT = _affine(np.diag([-1.0, -1.0, 1.0]), [0, 0, 0])   # the renderer's scale(-1, -1, 1): model space -> world


def pose(seated, main, off):
    """part -> (pivot, (xRot, yRot, zRot)) for a passenger seated (riding) or standing, holding in main / off."""
    arm_r = -math.pi / 5 if seated else (-math.pi / 10 if main else 0.0)
    arm_l = -math.pi / 5 if seated else (-math.pi / 10 if off else 0.0)
    p = {"head": (0, 0, 0), "body": (0, 0, 0), "rarm": (arm_r, 0, 0), "larm": (arm_l, 0, 0), "rleg": (0, 0, 0), "lleg": (0, 0, 0)}
    if seated:
        p["rleg"] = (-1.4137167, math.pi / 10, 0.07853982)
        p["lleg"] = (-1.4137167, -math.pi / 10, -0.07853982)
    return p


def parts(slim):
    """part -> (pivot, cube origin, size) in model space (PlayerModel)."""
    a = 3 if slim else 4
    ay = 2.5 if slim else 2.0
    return {
        "head": ((0, 0, 0), (-4, -8, -4), (8, 8, 8)),
        "body": ((0, 0, 0), (-4, 0, -2), (8, 12, 4)),
        "rarm": ((-5, ay, 0), (-a + 1, -2, -2) if slim else (-3, -2, -2), (a, 12, 4)),
        "larm": ((5, ay, 0), (-1, -2, -2), (a, 12, 4)),
        "rleg": ((-1.9, 12, 0), (-2, 0, -2), (4, 12, 4)),
        "lleg": ((1.9, 12, 0), (-2, 0, -2), (4, 12, 4)),
    }


# local face of a model-space cube -> the skin's face (the root turn puts model +x on the passenger's left, +y down)
FACE_OF = {"north": "front", "south": "back", "east": "left", "west": "right", "up": "bottom", "down": "top"}


def skin_elements(key, slim, part_pose, at):
    """The skin's two layers as preview elements (texture `key`, 64 x 64), posed, the neck at world point `at`."""
    import economy_bus_skins as B
    uvs = B.parts(slim)
    out = []
    for part, (pivot, org, size) in parts(slim).items():
        rot = _rzyx(*part_pose[part])
        mat = _tr(at) @ ROOT @ _affine(rot, pivot)
        (u, v, w, h, d), ov = uvs[part]
        for layer, grow in ((0, 0.0), (1, 0.5 if part == "head" else 0.25)):
            bu, bv = (u, v) if layer == 0 else ov
            r = B.rects(bu, bv, w, h, d)
            frm = [org[i] - grow for i in range(3)]
            to = [org[i] + size[i] + grow for i in range(3)]
            faces = {}
            for f, sf in FACE_OF.items():
                x0, y0, fw, fh = r[sf]
                q = [c * 0.25 for c in (x0, y0, x0 + fw, y0 + fh)]
                faces[f] = {"uv": q if sf == "top" else [q[2], q[3], q[0], q[1]], "texture": key}
            out.append({"from": frm, "to": to, "faces": faces, "shade": True, "rotation": None,
                        "matrix": [float(x) for x in mat.flatten()]})
    return out


def hand_matrix(slim, part_pose, at, left):
    """World matrix of the hand frame an item's display transform starts from (ItemInHandLayer)."""
    pivot, _, _ = parts(slim)["larm" if left else "rarm"]
    px = pivot[0] + ((-0.5 if left else 0.5) if slim else 0.0)
    rot = _rzyx(*part_pose["larm" if left else "rarm"])
    from models.bus_hold import _rx, _ry
    return _tr(at) @ ROOT @ _affine(rot, (px, pivot[1], pivot[2])) @ _affine(_rx(-90) @ _ry(180), [0, 0, 0]) \
        @ _tr([-1.0 if left else 1.0, 2.0, -10.0])


def display_matrix(t, left):
    """An item's display transform (px) about the model's (8, 8, 8); the off hand mirrored as the game does."""
    from stsv_assets import presets
    rx, ry, rz = t["rotation"]
    tx, ty, tz = t["translation"]
    if left:
        ry, rz, tx = -ry, -rz, -tx
    r = np.array(presets._rot_xyz([rx, ry, rz])) @ np.diag(t["scale"])
    return _tr([tx, ty, tz]) @ _affine(r, [0, 0, 0]) @ _tr([-8, -8, -8])


def item_elements(pv, display, mat):
    out = []
    for e in pv["elements"]:
        e = json.loads(json.dumps(e))
        e["matrix"] = [float(x) for x in mat.flatten()]
        out.append(e)
    return out


def passenger(skin_key, slim, at, seated, main, off, pvs):
    """A whole posed passenger with its items: preview elements."""
    pp = pose(seated, main, off)
    els = skin_elements(skin_key, slim, pp, at)
    for name, left in ((main, False), (off, True)):
        if not name:
            continue
        pv, disp = pvs[name]
        mat = hand_matrix(slim, pp, at, left) @ display_matrix(disp["thirdperson_righthand"], left)
        els += item_elements(pv, disp, mat)
    return els


def _uri(im):
    b = io.BytesIO()
    im.save(b, "PNG")
    return "data:image/png;base64," + base64.b64encode(b.getvalue()).decode()


def _box(frm, to, tex):
    return {"from": list(frm), "to": list(to), "shade": True, "rotation": None,
            "faces": {f: {"uv": [0, 0, 16, 16], "texture": tex} for f in ("north", "south", "east", "west", "up", "down")}}


def _solid(rgb):
    return _uri(Image.new("RGBA", (16, 16), rgb + (255,)))


def _bench(at):
    """A plain bus seat under a seated passenger (its neck at `at`): the cushion under the thighs, a back behind."""
    x, y, z = at
    seat_top = y - 14.0
    return [_box((x - 6, seat_top - 3, z - 9), (x + 6, seat_top, z + 3), "seat"),
            _box((x - 6, seat_top - 3, z + 3), (x + 6, seat_top + 15, z + 5), "seat"),
            _box((x - 6, seat_top - 14, z - 7), (x + 6, seat_top - 3, z + 3), "seatbase")]


def render(scene_els, textures, views, size, out):
    BUILD.mkdir(exist_ok=True)
    src = BUILD / (Path(out).stem + ".preview.json")
    src.write_text(json.dumps({"textures": textures, "elements": scene_els, "grid": False, "size": list(size), "views": views}))
    subprocess.run(["node", str(RENDER), str(src), str(out)], check=True, stdout=subprocess.DEVNULL)
    return Image.open(out).convert("RGBA")


def _models():
    from models import bus_hold as H
    pvs = {}
    for name, fn in H.ALL.items():
        m = fn()
        pvs[name] = (m.to_preview(), m.display)
    return pvs


def _skins(kinds):
    """skin key -> (image, slim) of these kinds' looks and shiny, as the pack has them now (rebuilt from the code)."""
    import economy_bus_skins as B
    fns = {tid: fn for tid, _, fn in B.TYPES}
    idx = {tid: i for i, (tid, _, _) in enumerate(B.TYPES)}
    out = {}
    for tid in kinds:
        for v in range(1, B.VARIANTS + 1):
            s = fns[tid](v)
            out[f"{tid}_{v}"] = (s.finish(), s.slim)
        if tid not in B.SHINY_SKIP:
            s = B.shiny(tid, fns[tid], idx[tid])
            out[f"{tid}_s1"] = (s.finish(), s.slim)
    return out


def _font(n):
    return ImageFont.truetype(str(FONT), n)


def sheet(path=SHEET, kinds=None):
    """Each kind a row: every look (and the shiny) seated, front and three quarters, and the first look standing."""
    kinds = kinds or list(HOLDS)
    pvs = _models()
    skins = _skins(kinds)
    tex = {"seat": _solid((70, 96, 140)), "seatbase": _solid((60, 62, 70))}
    for k, (im, _) in skins.items():
        tex[k] = _uri(im)
    for pv, _ in pvs.values():
        tex.update(pv["textures"])
    import economy_bus_skins as B
    names = {tid: n for tid, n, _ in B.TYPES}
    CW, CH, LABEL = 200, 300, 150
    cols = 2 * 4 + 1
    W, Hh = LABEL + CW * cols, CH * len(kinds)
    els, views, labels = [], [], []
    for r, tid in enumerate(kinds):
        looks = [f"{tid}_{v}" for v in range(1, B.VARIANTS + 1)] + ([f"{tid}_s1"] if f"{tid}_s1" in skins else [])
        for c, key in enumerate(looks):
            im, slim = skins[key]
            main, off = HOLDS[tid][min(c, 2) if not key.endswith("_s1") else 0]
            o = (c * 200.0 + r * 2000.0, 40.0, 0.0)
            els += passenger(key, slim, o, True, main, off, pvs) + _bench(o)
            ctr = [o[0], o[1] - 6, o[2] - 4]
            views.append({"x": LABEL + c * 2 * CW, "y": r * CH + 24, "w": CW, "h": CH - 24, "az": 0, "el": 8, "center": ctr,
                          "radius": 22, "k": 2.6, "fov": 30, "far": 4000})
            views.append({"x": LABEL + (c * 2 + 1) * CW, "y": r * CH + 24, "w": CW, "h": CH - 24, "az": 35, "el": 18,
                          "center": ctr, "radius": 22, "k": 2.6, "fov": 30, "far": 4000})
            labels.append((LABEL + c * 2 * CW + 6, r * CH + 2, key + ("  ✦" if key.endswith("_s1") else "")))
        # the first look standing
        key = f"{tid}_1"
        im, slim = skins[key]
        main, off = HOLDS[tid][0]
        o = (900.0 + r * 2000.0, 40.0, 0.0)
        els += passenger(key, slim, o, False, main, off, pvs)
        views.append({"x": LABEL + 8 * CW, "y": r * CH + 24, "w": CW, "h": CH - 24, "az": 25, "el": 10,
                      "center": [o[0], o[1] - 9, o[2] - 2], "radius": 20, "k": 2.9, "fov": 30, "far": 4000})
        labels.append((LABEL + 8 * CW + 6, r * CH + 2, "서 있을 때"))
        labels.append((10, r * CH + CH // 2 - 30, names[tid]))
        labels.append((10, r * CH + CH // 2, " / ".join(x for x in HOLDS[tid][0] if x).replace("bus_hold_", "")))
    shot = BUILD / (Path(path).stem + ".render.png")
    im = render(els, tex, views, (W, Hh), shot)
    out = Image.new("RGBA", (W, Hh), (27, 29, 34, 255))
    out.alpha_composite(im)
    d = ImageDraw.Draw(out)
    f = _font(16)
    for (x, y, t) in labels:
        d.text((x, y), t, font=f, fill=(236, 232, 220))
    for r in range(1, len(kinds)):
        d.line((0, r * CH, W, r * CH), fill=(60, 64, 72), width=2)
    out.convert("RGB").save(path, optimize=True)
    return path


def chicken_sheet(path=CHICKEN):
    """닭 든 할머니 up close: seated front, three quarters and side, standing, the hen alone and flapping."""
    pvs = _models()
    skins = _skins(["chicken"])
    tex = {"seat": _solid((70, 96, 140)), "seatbase": _solid((60, 62, 70))}
    for k, (im, _) in skins.items():
        tex[k] = _uri(im)
    for pv, _ in pvs.values():
        tex.update(pv["textures"])
    els, views, labels = [], [], []
    W, Hh = 1600, 900
    o = (0.0, 40.0, 0.0)
    im, slim = skins["chicken_1"]
    els += passenger("chicken_1", slim, o, True, "bus_hold_chicken", None, pvs) + _bench(o)
    ctr = [0, 30, -5]
    for i, (az, el, lab) in enumerate(((0, 6, "앉아서 · 정면"), (32, 16, "앉아서 · 3/4"), (90, 4, "옆"))):
        views.append({"x": i * 400, "y": 0, "w": 400, "h": 520, "az": az, "el": el, "center": ctr, "radius": 26, "k": 2.7, "fov": 30})
        labels.append((i * 400 + 10, 8, lab))
    o2 = (300.0, 40.0, 0.0)
    im, slim = skins["chicken_s1"]
    els += passenger("chicken_s1", slim, o2, True, "bus_hold_chicken", None, pvs) + _bench(o2)
    views.append({"x": 1200, "y": 0, "w": 400, "h": 520, "az": 20, "el": 10, "center": [300, 30, -5], "radius": 26, "k": 2.7, "fov": 30})
    labels.append((1210, 8, "별난 손님 (chicken_s1) · 앉아서"))
    o3 = (600.0, 40.0, 0.0)
    im, slim = skins["chicken_2"]
    els += passenger("chicken_2", slim, o3, False, "bus_hold_chicken", None, pvs)
    views.append({"x": 0, "y": 520, "w": 400, "h": 380, "az": 25, "el": 10, "center": [600, 24, -2], "radius": 30, "k": 2.6, "fov": 30})
    labels.append((10, 528, "서 있을 때 (정류장)"))
    from models.bus_hold import ALL
    for j, (name, az) in enumerate((("bus_hold_chicken", 35), ("bus_hold_chicken", 215), ("bus_hold_chicken_flap", 30))):
        pv, _ = pvs[name]
        ox = 1000.0 + 100 * j
        for e in pv["elements"]:
            e = json.loads(json.dumps(e))
            e["matrix"] = [float(x) for x in _tr([ox - 8, -8, -8]).flatten()]
            els.append(e)
        views.append({"x": 400 + 400 * j, "y": 520, "w": 400, "h": 380, "az": az, "el": 20, "center": [ox, 1, 2], "radius": 15,
                      "k": 2.4, "fov": 30})
        labels.append((410 + 400 * j, 528, name + (" (뒤)" if az > 180 else "")))
    shot = BUILD / "bus_chicken_hold.render.png"
    im = render(els, tex, views, (W, Hh), shot)
    out = Image.new("RGBA", (W, Hh), (27, 29, 34, 255))
    out.alpha_composite(im)
    d = ImageDraw.Draw(out)
    f = _font(18)
    for (x, y, t) in labels:
        d.text((x, y), t, font=f, fill=(236, 232, 220))
    out.convert("RGB").save(path, optimize=True)
    return path


if __name__ == "__main__":
    if "--sheet-only" not in sys.argv:
        print(len(write_models()), "models written")
        print("skins:", ", ".join(write_skins()))
    if "--no-sheet" not in sys.argv:
        print(chicken_sheet())
        print(sheet())
