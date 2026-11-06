"""
Preview sheets of the cosmetics (needs: cd render && npm install):

    python3 cosmetics_preview.py

docs/previews/cosmetics_worn_1.png (head, above the head), cosmetics_worn_2.png (shoulder, back, tail):
every cosmetic on a plain player-sized mannequin, from two sides. Head items sit on the head the way a
helmet-slot item is drawn; the others at their slot's anchor, the way the plugin places its item
displays (CosmeticAttachments.DEFAULT_ANCHORS, from the top of the 1.8 hitbox, x left, y up, z front).
docs/previews/cosmetics_icons.png: each model from about its menu angle.
"""

import copy
import json
import re
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from models import cosmetics as C            # noqa: E402
from economy_machines import MModel, Mat     # noqa: E402
import yaml                                  # noqa: E402

REPO = HERE.parent.parent
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"
RENDER = HERE / "render" / "render.mjs"
FONT = "/usr/share/fonts/opentype/unifont/unifont.otf"

U = 0.9375                     # the player model is drawn at 15/16
ANCHORS = {"above": (0, 0.10, 0), "shoulder": (0.38, -0.39, 0), "back": (0, -0.62, -0.12), "tail": (0, -1.02, -0.12)}
TOP = (8, 1.8 * 16, 8)         # top of the hitbox, in model units around the mannequin
SLOT_KO = {"head": "머리", "above": "머리 위", "shoulder": "어깨", "back": "등", "tail": "꼬리"}


def names_ko():
    items = yaml.safe_load((REPO / "content/stsv/items/cosmetics.yml").read_text())
    return {k: re.sub(r"<[^>]+>", "", v["name"]) for k, v in items.items() if isinstance(v, dict) and "name" in v}


def mannequin():
    m = MModel("mannequin")
    skin, shirt, pants, hair = Mat("salmon", "plain", 3), Mat("steel", "plain", 3), Mat("denim", "plain", 1), Mat("plush", "plain", 1)
    m.box("leg", (8 - 4 * U, 0, 8 - 2 * U), (8, 12 * U, 8 + 2 * U), pants)
    m.box("leg", (8, 0, 8 - 2 * U), (8 + 4 * U, 12 * U, 8 + 2 * U), pants)
    m.box("body", (8 - 4 * U, 12 * U, 8 - 2 * U), (8 + 4 * U, 24 * U, 8 + 2 * U), shirt)
    m.box("arm", (8 - 8 * U, 12 * U, 8 - 2 * U), (8 - 4 * U, 24 * U, 8 + 2 * U), skin)
    m.box("arm", (8 + 4 * U, 12 * U, 8 - 2 * U), (8 + 8 * U, 24 * U, 8 + 2 * U), skin)
    m.box("head", (8 - 4 * U, 24 * U, 8 - 4 * U), (8 + 4 * U, 32 * U, 8 + 4 * U), skin, faces={"up": hair, "south": hair})
    m.box("eye", (8 - 2.5 * U, 28 * U, 8 - 4 * U - 0.1), (8 - 1.5 * U, 29 * U, 8 - 4 * U), Mat("rubber", "plain", 0))
    m.box("eye", (8 + 1.5 * U, 28 * U, 8 - 4 * U - 0.1), (8 + 2.5 * U, 29 * U, 8 - 4 * U), Mat("rubber", "plain", 0))
    return m


def placed(data, fn):
    out = []
    for e in data["elements"]:
        e = copy.deepcopy(e)
        e["from"], e["to"] = fn(e["from"]), fn(e["to"])
        if e.get("rotation"):
            e["rotation"][2] = fn(e["rotation"][2])
        out.append(e)
    return out


def worn_scene(name):
    man = mannequin().to_preview()
    d = C.ALL[name]().to_preview()
    slot = C.SLOTS[name]
    if slot == "head":
        # a helmet-slot item: 0.625 x the player's scale, centred on the head
        s, hc = 0.625 * U, (8, 28 * U, 8)
        fn = lambda p: [hc[i] + (p[i] - 8) * s for i in range(3)]
    else:
        # model north = the player's front = -z here; local +x (left) = -x here
        a = ANCHORS[slot]
        off = (-a[0] * 16, a[1] * 16, -a[2] * 16)
        fn = lambda p: [TOP[i] + off[i] + (p[i] - 8) for i in range(3)]
    return {"textures": {**man["textures"], **d["textures"]}, "elements": man["elements"] + placed(d, fn)}, slot


def render(data, tag):
    src, png = BUILD / f"cos_{tag}.json", BUILD / f"cos_{tag}.png"
    src.write_text(json.dumps(data))
    subprocess.run(["node", str(RENDER), str(src), str(png)], check=True, capture_output=True)
    return Image.open(png).convert("RGB")


def worn_sheet(names, out, ko):
    ims = []
    for n in names:
        data, slot = worn_scene(n)
        behind = slot in ("back", "tail")
        a1, a2 = (215, 270) if behind else (325, 270) if slot == "shoulder" else (35, 90)
        views = [{"x": 0, "y": 0, "w": 600, "h": 700, "az": a1, "el": 12, "k": 2.4},
                 {"x": 600, "y": 0, "w": 600, "h": 700, "az": a2, "el": 8, "k": 2.4}]
        zoom = {"head": ([8, 27, 8], 14), "above": ([8, 28, 8], 14), "shoulder": ([5, 24, 8], 14),
                "back": ([8, 20, 10], 22), "tail": ([8, 12, 12], 18)}
        c, r = zoom[slot]
        data.update({"grid": False, "center": c, "radius": r, "views": views})
        ims.append((n, slot, render(data, n).crop((0, 0, 1200, 700)).resize((480, 280), Image.LANCZOS)))
    font = ImageFont.truetype(FONT, 16)
    cols = 3
    rows = (len(ims) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * 490 + 10, rows * 305 + 10), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    for k, (n, slot, im) in enumerate(ims):
        x, y = 10 + (k % cols) * 490, 10 + (k // cols) * 305
        sheet.paste(im, (x, y + 20))
        d.text((x, y), f"{ko.get(n, n)}  ({SLOT_KO[slot]} · {n})", font=font, fill=(232, 228, 218))
    sheet.save(out)
    print(f"sheet   {out.relative_to(REPO)}")


def icon_sheet(names, out, ko):
    """Each model alone, seen about the way its menu (gui) angle shows it."""
    font = ImageFont.truetype(FONT, 16)
    cols = 7
    rows = (len(names) + cols - 1) // cols
    cell = 150
    sheet = Image.new("RGB", (cols * cell + 10, rows * (cell + 24) + 10), (27, 29, 34))
    d = ImageDraw.Draw(sheet)
    for k, n in enumerate(names):
        data = C.ALL[n]().to_preview()
        slot = C.SLOTS[n]
        az = 225 if slot in ("back", "tail") else 45     # gui (30, 45, 0) shows the back, (30, 225, 0) the front
        data.update({"grid": False, "views": [{"x": 0, "y": 0, "w": 400, "h": 400, "az": az, "el": 30, "k": 2.7}]})
        im = render(data, f"icon_{n}").crop((0, 0, 400, 400)).resize((cell - 10, cell - 10), Image.LANCZOS)
        x, y = 10 + (k % cols) * cell, 10 + (k // cols) * (cell + 24)
        sheet.paste(im, (x, y + 20))
        d.text((x, y), ko.get(n, n), font=font, fill=(232, 228, 218))
    sheet.save(out)
    print(f"sheet   {out.relative_to(REPO)}")


def main():
    BUILD.mkdir(exist_ok=True)
    ko = names_ko()
    order = list(C.ALL)
    first = [n for n in order if C.SLOTS[n] in ("head", "above")]
    second = [n for n in order if C.SLOTS[n] not in ("head", "above")]
    worn_sheet(first, PREVIEWS / "cosmetics_worn_1.png", ko)
    worn_sheet(second, PREVIEWS / "cosmetics_worn_2.png", ko)
    icon_sheet(order, PREVIEWS / "cosmetics_icons.png", ko)


if __name__ == "__main__":
    main()
