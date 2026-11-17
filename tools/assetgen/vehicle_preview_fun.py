"""
Preview sheet of the 특별 탈것 (content/stsv/items/vehicles_fun.yml, models/vehicles_fun.py; needs: cd render && npm
install, or a global playwright):

    python3 vehicle_preview_fun.py [out.png]                  # docs/previews/vehicles_fun.png
    python3 vehicle_preview_fun.py --one <id> <out.png>       # one vehicle large from four sides

Put together as the plugin shows them (vehicle_preview.py's helpers): the body at vehicle.body-scale, the wheels where
`wheels` puts them, the fun part (the balloons, the bull) where `fun.part` puts it at its scale, players in the seats
(orange the driver, green a passenger), a standing player and a block for scale; the inventory icons below.
"""

import sys
import types
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import vehicle_preview as VP              # noqa: E402
from models import vehicles as V          # noqa: E402
from models import vehicles_fun as F      # noqa: E402
import vehicle_icons_fun as VI            # noqa: E402
import yaml                               # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
ITEMS = REPO / "content/stsv/items/vehicles_fun.yml"
ORDER = ["kickboard", "pogo_stick", "skateboard", "broom", "banana_boat", "segway", "toy_duck", "sled", "balloon_chair", "rodeo_bull"]
# how high each is shown off the ground (blocks): the broom and the balloon chair as they float when ridden
LIFT = {"broom": 1.5, "balloon_chair": 3.0}


def items():
    return yaml.safe_load(ITEMS.read_text())


VP.items = items
VP.V = types.SimpleNamespace(ALL={**V.ALL, **F.ALL})


# the standing riders' poses as the plugin makes them at a steady roll (RiderPose: channels in puppet pixels / degrees)
STAND_POSES = {
    "kick": {"root_rot": (12, 0, 0), "root": (0, -1.6, 0), "chest": (8, 0, 0), "head": (-10, 0, 0), "arm_r": (-62, 0, -4),
             "arm_l": (-62, 0, 4), "foot_l": (-1.5, 0, 2), "foot_r": (2.2, -2.0, -7.5), "knee_l": (0, 0, 1), "knee_r": (0, 0, 1)},
    "segway": {"root_rot": (6, 0, 0), "root": (0, -0.3, 0.3), "arm_r": (-55, 0, -6), "arm_l": (-55, 0, 6),
               "foot_l": (0.5, 0, 0.5), "foot_r": (-0.5, 0, 0.5), "head": (-4, 0, 0)},
    "pogo": {"foot_l": (2.6, 0, 3), "foot_r": (-2.6, 0, 3), "knee_l": (0.4, 0, 1), "knee_r": (-0.4, 0, 1),
             "root": (0, -1.5, 0.2), "root_rot": (10, 0, 0), "chest": (2, 0, 0), "arm_r": (-25, 0, 16), "arm_l": (-25, 0, -16)},
    "skate": {"root_rot": (0, 80, 0), "foot_l": (-2, 0, -4.5), "foot_r": (2, 0, 4.5), "knee_l": (1, 0, 0.6), "knee_r": (1, 0, 0.6),
              "root": (0, -1.6, 0), "chest": (14, -55, 0), "head": (-6, -20, 0), "arm_r": (-10, 0, -45), "arm_l": (-10, 0, 45)},
}
PUPPET_COLOURS = {"head": (222, 182, 140), "chest": (64, 170, 200), "hips": (64, 170, 200), "arm_r": (222, 182, 140),
                  "arm_l": (222, 182, 140), "thigh_r": (52, 72, 140), "shin_r": (52, 72, 140), "thigh_l": (52, 72, 140),
                  "shin_l": (52, 72, 140)}


def standing(spec, ox):
    """The standing rider (the puppet the plugin draws: emotes.py's rig, posed as RiderPose poses it) on the deck."""
    import numpy as np
    import emotes as E
    st = spec["fun"]["stand"]
    pose = E.default_pose()
    for k, v in STAND_POSES[st["kind"]].items():
        pose[k] = np.array(v, float)
    joints = E.matrices(pose, True)
    names = E.JOINT_NAMES
    seat = spec["seats"][0]["at"]
    k = 0.9375
    world = np.array([[-k, 0, 0, -seat[0] * 16 + ox[0]], [0, k, 0, st["deck"] * 16], [0, 0, -k, -seat[2] * 16 + ox[2]], [0, 0, 0, 1]])
    els, tex = [], {}
    for joint, part, r0, r1, x0, top, z0 in E.BOXES:
        w, h, d = E.SKIN_PARTS[part][2], E.SKIN_PARTS[part][3], E.SKIN_PARTS[part][4]
        frm, to = [x0, top - r1, z0], [x0 + w, top - r0, z0 + d]
        mat = world @ joints[names.index(joint)]
        name = "puppet_" + joint
        tex[name] = VP._solid(PUPPET_COLOURS[joint])
        els.append({"from": frm, "to": to, "glow": False, "shade": True, "rotation": None, "matrix": [float(x) for x in mat.flatten()],
                    "faces": {f: {"uv": [0, 0, 16, 16], "texture": name} for f in ("north", "south", "east", "west", "up", "down")}})
    return tex, els


def vehicle(vid, ox, riders=True):
    """The whole vehicle (body, wheels, part, riders) at offset ox, lifted as it floats."""
    stands = "stand" in items()[vid]["vehicle"].get("fun", {})
    t, e, spec = VP.vehicle(vid, ox, riders=riders and not stands, steer=0)
    if riders and stands:
        pt, pe = standing(spec, ox)
        t = {**t, **pt}
        e = e + pe
    t = dict(t)
    part = spec.get("fun", {}).get("part")
    if part:
        data = F.ALL[part["model"]]().to_preview()
        t.update(data["textures"])
        k = part.get("scale", 1)
        at = part["at"]
        ax, ay, az = at[0] * 16 + ox[0], at[1] * 16, -at[2] * 16 + ox[2]
        e = e + VP._xf(data["elements"], lambda p: [(p[0] - 8) * k + ax, (p[1] - 8) * k + ay, (p[2] - 8) * k + az])
    lift = LIFT.get(vid, 0) * 16
    if lift:
        e = VP._xf(e, lambda p: [p[0], p[1] + lift, p[2]])
    return t, e, spec


def close_up(vid, out):
    t, e, spec = vehicle(vid, (0, 0, 0))
    t = {**t, "ref_rider": VP._solid((222, 152, 60)), "ref_passenger": VP._solid((120, 160, 120))}
    L, W, H = spec["size"]
    part = spec.get("fun", {}).get("part")
    H = max(H, 4.5 if part and "balloon" in part["model"] else H, 2.2 if "stand" in spec.get("fun", {}) else 0) + LIFT.get(vid, 0)
    r = max(L, 1.4 * H, 2.0) * 8
    c = [0, H * 7, 0]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": -90, "el": 4, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 30, "el": 40, "k": 5.2, "radius": r, "fov": 26, "center": c}]
    VP.render({"textures": t, "elements": e, "grid": False, "views": views}, "fun_one_" + vid).save(out)
    print(f"close-up {out}")


def bare(vid, out):
    """One vehicle without riders, four sides (to judge the model itself)."""
    t, e, spec = vehicle(vid, (0, 0, 0), riders=False)
    L, W, H = spec["size"]
    part = spec.get("fun", {}).get("part")
    H = max(H, 4.5 if part and "balloon" in part["model"] else H, 2.2 if "stand" in spec.get("fun", {}) else 0) + LIFT.get(vid, 0)
    r = max(L, 1.4 * H, 2.0) * 8
    c = [0, H * 7, 0]
    views = [{"x": 0, "y": 0, "w": 800, "h": 500, "az": -35, "el": 18, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 0, "w": 800, "h": 500, "az": 145, "el": 22, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 0, "y": 500, "w": 800, "h": 500, "az": -90, "el": 4, "k": 5.2, "radius": r, "fov": 26, "center": c},
             {"x": 800, "y": 500, "w": 800, "h": 500, "az": 30, "el": 40, "k": 5.2, "radius": r, "fov": 26, "center": c}]
    VP.render({"textures": t, "elements": e, "grid": False, "views": views}, "fun_bare_" + vid).save(out)
    print(f"bare {out}")


VIEWS = [("앞 왼쪽", -38, 20), ("뒤 오른쪽", 142, 24), ("옆 · 사람과 크기", -90, 4), ("위", -20, 60)]


def main(out):
    spec_all = items()
    ko = {k: v["name"].split(">")[-1] for k, v in spec_all.items()}
    player, block = VP._ref_models()
    textures = {**player["textures"], **block["textures"], "ref_rider": VP._solid((222, 152, 60)),
                "ref_passenger": VP._solid((120, 160, 120))}
    elements, views, labels = [], [], []
    cw, ch, head = 400, 300, 34
    for r, vid in enumerate(ORDER):
        ox = (r * 1400, 0, 0)
        t, e, spec = vehicle(vid, ox)
        textures.update(t)
        elements += e
        L, W, H = spec["size"]
        part = spec.get("fun", {}).get("part")
        Hs = max(H, 4.6 if part and "balloon" in part["model"] else H, 2.2 if "stand" in spec.get("fun", {}) else 0) + LIFT.get(vid, 0)
        elements += VP._xf(player["elements"], lambda p: [p[0] + ox[0] - W * 8 - 10, p[1], p[2] + ox[2] + L * 8 + 10])
        elements += VP._xf(block["elements"], lambda p: [p[0] + ox[0] + W * 8 + 4, p[1], p[2] - L * 8 - 22])
        radius = max(L, 1.3 * Hs, 2.6) * 8
        for c, (label, az, el) in enumerate(VIEWS):
            views.append({"x": c * cw, "y": r * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 5.6,
                          "radius": radius, "fov": 26, "far": 5000, "center": [ox[0], Hs * 7, 0]})
            labels.append((c * cw, r * (ch + head), cw, f"{ko[vid]} · {label}" if c == 0 else label, c == 0))
    data = {"textures": textures, "elements": elements, "grid": False, "size": [1600, len(ORDER) * ch], "views": views}
    im = VP.render(data, "fun_sheet")

    font = ImageFont.truetype(VP.FONT, 21)
    small = ImageFont.truetype(VP.FONT, 16)
    top, icon_h = 50, 170
    rows = len(ORDER)
    sheet = Image.new("RGB", (1600, top + rows * (ch + head) + icon_h), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    d.text((14, 12), "특별 탈것 (관리자 아이템) · 주황 = 운전자, 초록 = 동승자 · 블록 1칸과 사람(1.8칸) · 빗자루 1.5칸, 풍선 의자 3칸 떠 있는 모습",
           font=small, fill=(235, 232, 222))
    for r in range(rows):
        for c in range(4):
            x, y = c * cw, r * ch
            sheet.paste(im.crop((x, y, x + cw, y + ch)), (x, top + r * (ch + head) + head))
    for (x, y, w, text, big) in labels:
        d.rectangle((x, top + y, x + w - 2, top + y + head - 2), fill=(40, 43, 52))
        d.text((x + 8, top + y + 5), text, font=font if big else small, fill=(245, 242, 232))
    y0 = top + rows * (ch + head) + 10
    d.text((14, y0 - 4), "인벤토리 아이콘 (16px · 확대 / 실제 크기)", font=small, fill=(170, 170, 178))
    for k, vid in enumerate(ORDER):
        icon = VI.ICONS[vid]()
        x = 14 + k * 158
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        sheet.paste(bg.convert("RGB"), (x, y0 + 20))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        sheet.paste(slot.convert("RGB"), (x + 102, y0 + 20))
        d.text((x, y0 + 122), ko[vid], font=small, fill=(240, 240, 240))
    sheet.save(out, optimize=True)
    print(f"sheet   {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--one":
        close_up(sys.argv[2], Path(sys.argv[3]))
    elif len(sys.argv) > 1 and sys.argv[1] == "--bare":
        bare(sys.argv[2], Path(sys.argv[3]))
    else:
        main(Path(sys.argv[1]) if len(sys.argv) > 1 else PREVIEWS / "vehicles_fun.png")
