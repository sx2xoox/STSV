"""
Preview sheet of the fishing boats, 1세대 to 5세대 (needs: cd render && npm install, or a global playwright):

    python3 boat_preview.py [out.png]                  # docs/previews/fishing_boats.png
    python3 boat_preview.py --one <id> out.png [stage] # one boat large from four sides (working on the model)

Put together as the plugin shows them, from content/stsv/items/vehicles_misc.yml: each body at its body-scale, its
"wheel" (the propeller, the raft's sweep) where `wheels` puts it, its oars where `spinners` puts them, its catch where
`cargo` puts it, players in its `seats` (orange the one who steers, green the others), on the water at its draft. All five
at one scale, from the side and from the front quarter, then each with its hold full from behind.
"""

import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from models import vehicles_misc as VM       # noqa: E402
from models import vehicles_boats as VB      # noqa: E402
import vehicle_misc_preview as P             # noqa: E402
from furniture_preview import _ref_models    # noqa: E402
from vehicle_preview import sitting, _xf     # noqa: E402

REPO = HERE.parent.parent
ALL = {**VM.ALL, **VB.ALL}
LADDER = [("raft", "1세대 뗏목"), ("rowboat", "2세대 나룻배"), ("fishing_boat", "3세대 통통배"), ("coastal_boat", "4세대 연안 어선"),
          ("trawler", "5세대 트롤 어선")]
_cache = {}


def _model(name):
    if name not in _cache:
        _cache[name] = ALL[name]().to_preview()
    return _cache[name]


def part(name, at, ox):
    data = _model(name)
    ax, ay, az = at[0] * 16 + ox[0], at[1] * 16 + ox[1], -at[2] * 16 + ox[2]
    return data["textures"], _xf(data["elements"], lambda p: [p[0] - 8 + ax, p[1] - 8 + ay, p[2] - 8 + az])


def _tool(vid, ox):
    """The boat's net as the plugin hangs it (content/stsv/vehicle_tools.yml): the tool's model at `at` and `scale`, its parts."""
    import yaml
    from models import vehicle_tools as VT
    tools = (yaml.safe_load((REPO / "content/stsv/vehicle_tools.yml").read_text()).get(vid) or {}).get("tools") or []
    textures, elements = {}, []
    for t in tools[:1]:
        data = VT.ALL[t["model"]]().to_preview()
        k = t.get("scale", 1)
        at = t.get("at", [0, 0, 0])
        ax, ay, az = at[0] * 16 + ox[0], at[1] * 16 + ox[1], -at[2] * 16 + ox[2]
        textures.update(data["textures"])
        elements += _xf(data["elements"], lambda p: [(p[0] - 8) * k + ax, (p[1] - 8) * k + ay, (p[2] - 8) * k + az])
        for part_ in t.get("parts") or []:
            d2 = VT.ALL[part_["model"]]().to_preview()
            a = part_["at"]
            bx, by, bz = a[0] * 16 + ox[0], a[1] * 16 + ox[1], -a[2] * 16 + ox[2]
            textures.update(d2["textures"])
            elements += _xf(d2["elements"], lambda p: [p[0] - 8 + bx, p[1] - 8 + by, p[2] - 8 + bz])
    return textures, elements


def boat(vid, ox, cargo=None, riders=True, tool=False):
    """The boat `vid` with its keel bottom middle at ox (px)."""
    item = P.items()[vid]
    spec = item["vehicle"]
    if tool:
        tt, te = _tool(vid, ox)
    else:
        tt, te = {}, []
    body = _model(item["model"])
    k = spec["body-scale"]
    textures = {**dict(body["textures"]), **tt}
    elements = _xf(body["elements"], lambda p: [(p[0] - 8) * k + ox[0], (p[1] - 8) * k + ox[1], (p[2] - 8) * k + ox[2]]) + te
    for w in spec.get("wheels") or []:
        t, e = part(spec["wheel"], w["at"], ox)
        textures.update(t)
        elements += e
    for sp in spec.get("spinners") or []:
        t, e = part(sp["model"], sp["at"], ox)
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
    P.BUILD.mkdir(exist_ok=True)
    src, png = P.BUILD / f"boats_{tag}.json", P.BUILD / f"boats_{tag}.png"
    src.write_text(json.dumps(data))
    subprocess.run(["node", str(P.RENDER), str(src), str(png)], check=True, capture_output=True)
    return Image.open(png).convert("RGB")


def _refs():
    player, block = _ref_models()
    return player, block, {**player["textures"], **block["textures"], "ref_rider": P._solid((222, 152, 60)),
                           "ref_passenger": P._solid((120, 160, 120)), "water": P._water_tex()}


def one(vid, out, stage=0, water=True, riders=True, tool=False):
    _, _, textures = _refs()
    spec = P.items()[vid]["vehicle"]
    draft = spec["water"]["draft"] * 16
    L, W, H = spec["size"]
    elements = []
    views = []
    for i, (az, el) in enumerate([(-35, 18), (145, 24), (-90, 4), (-100, 55)]):
        dry = i == 2
        ox = (i * 3000, 0 if dry else -draft, 0)
        t, e, _ = boat(vid, ox, cargo=stage or None, riders=riders and not dry, tool=tool)
        textures.update(t)
        elements += e
        if water and not dry:
            elements.append(P._box((ox[0] - 300, -40, -300), (ox[0] + 300, 0, 300), "water"))
        views.append({"x": (i % 2) * 800, "y": (i // 2) * 520, "w": 800, "h": 520, "az": az, "el": el, "k": 3.3, "radius": L * 8,
                      "fov": 26, "far": 6000, "center": [ox[0], H * 5, 0]})
    render({"textures": textures, "elements": elements, "grid": False, "size": [1600, 1040], "views": views}, "one").save(out)
    print(f"close-up {out}")


def sheet(out):
    player, block, textures = _refs()
    ids = [v for v, _ in LADDER if v in P.items() and P.items()[v].get("model") in ALL]
    specs = {v: P.items()[v]["vehicle"] for v in ids}
    longest = max(s["size"][0] for s in specs.values())
    elements, views = [], []
    n = len(ids)
    W, ch, head, fov = 2000, 300, 34, 22
    cw = W // n
    # the side row at one scale: each boat's cell as wide as the boat is long (plus room for the player at its bow)
    room = 2.4
    total = sum(specs[v]["size"][0] + room for v in ids)
    widths = [int(W * (specs[v]["size"][0] + room) / total) for v in ids]
    lefts = [sum(widths[:i]) for i in range(n)]
    rows = [("옆 · 다섯 척 같은 축척 (주황 = 키잡이, 초록 = 같이 낚는 사람 · 뱃머리 앞: 블록 1칸과 사람 1.8칸)", -90, 6), ("앞 왼쪽 (배마다 크기에 맞춰 확대)", -38, 20),
            ("뒤 오른쪽 · 어창 가득", 142, 28)]
    for r, (_, az, el) in enumerate(rows):
        for c, vid in enumerate(ids):
            s = specs[vid]
            L = s["size"][0]
            draft = s["water"]["draft"] * 16
            ox = (r * 40000 + c * 4000, -draft, 0)
            t, e, _ = boat(vid, ox, cargo=4 if r == 2 else None, riders=r != 2)
            textures.update(t)
            elements += e
            elements.append(P._box((ox[0] - 500, -40, -500), (ox[0] + 500, 0, 500), "water"))
            if r == 0:
                # a player standing on a block beside the bow, for scale
                jz = -L * 8 - 16
                elements += _xf(block["elements"], lambda p, ox=ox, jz=jz: [p[0] - 8 + ox[0], p[1] - 8, p[2] - 8 + jz])
                elements += _xf(player["elements"], lambda p, ox=ox, jz=jz: [p[0] + ox[0], p[1] + 8, p[2] + jz])
                views.append({"x": lefts[c], "y": 0, "w": widths[c], "h": ch, "az": az, "el": el, "k": 2.95 * (total / 35.0), "radius": longest * 8,
                              "fov": fov, "far": 8000, "center": [ox[0], 30, -12]})
            else:
                views.append({"x": c * cw, "y": r * ch, "w": cw, "h": ch, "az": az, "el": el, "k": 4.0, "radius": L * 8,
                              "fov": 26, "far": 8000, "center": [ox[0], s["size"][2] * 5, 0]})
    im = render({"textures": textures, "elements": elements, "grid": False, "size": [W, len(rows) * ch], "views": views}, "sheet")
    font = ImageFont.truetype(P.FONT, 22)
    small = ImageFont.truetype(P.FONT, 16)
    top = 96
    out_im = Image.new("RGB", (W, top + len(rows) * (ch + head) + 150), (27, 29, 34))
    d = ImageDraw.Draw(out_im)
    d.text((14, 10), "고깃배 1~5세대 · 낚시를 돕는 배: 세대가 오를수록 같이 낚을 자리 · 어창 · 그물", font=font, fill=(245, 242, 232))
    for c, vid in enumerate(ids):
        s = specs[vid]
        d.text((lefts[c] + 10, 44), dict(LADDER)[vid], font=font, fill=(240, 226, 176))
        d.text((lefts[c] + 10, 72), f"{len(s['seats'])}인 · {s['speed']:g}칸/초 · 어창 {s['cargo']['slots']}칸", font=small, fill=(190, 194, 204))
    for r, (label, _, _) in enumerate(rows):
        y = top + r * (ch + head)
        d.rectangle((0, y, W, y + head - 2), fill=(40, 43, 52))
        d.text((10, y + 6), label, font=small, fill=(245, 242, 232))
        out_im.paste(im.crop((0, r * ch, W, r * ch + ch)), (0, y + head))
    y0 = top + len(rows) * (ch + head) + 10
    d.text((14, y0 - 2), "인벤토리 아이콘 (16px · 실제 크기)", font=small, fill=(170, 170, 178))
    import vehicle_icons_misc as VI
    for c, vid in enumerate(ids):
        if vid not in VI.ICONS:
            continue
        icon = VI.ICONS[vid]()
        bg = Image.new("RGBA", (96, 96), (139, 139, 139, 255))
        bg.alpha_composite(icon.resize((96, 96), Image.NEAREST))
        out_im.paste(bg.convert("RGB"), (c * cw + 14, y0 + 24))
        slot = Image.new("RGBA", (36, 36), (139, 139, 139, 255))
        slot.alpha_composite(icon.resize((32, 32), Image.NEAREST), (2, 2))
        out_im.paste(slot.convert("RGB"), (c * cw + 124, y0 + 84))
    out_im.save(out, optimize=True)
    print(f"sheet   {out}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--one":
        one(sys.argv[2], Path(sys.argv[3]), int(sys.argv[4]) if len(sys.argv) > 4 else 0, tool="--net" in sys.argv)
    else:
        sheet(Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs" / "previews" / "fishing_boats.png")
