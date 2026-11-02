"""
Builds every STSV asset into the default content pack (content/stsv) and renders previews (docs/previews).

    python3 build.py            # assets only
    python3 build.py --render   # + preview PNGs (needs: cd render && npm install)
"""

import importlib
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw

from stsv_assets import textures as T
from stsv_assets.palette import PALETTE

HERE = Path(__file__).parent
REPO = HERE.parent.parent
CONTENT = REPO / "content" / "stsv"
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"

MODELS = ["smelter", "potted_plant", "chair", "crystal_shrine"]  # tools/assetgen/models/<name>.py, each with build() -> Model


def main():
    render = "--render" in sys.argv
    for d in (CONTENT / "models", CONTENT / "textures", BUILD, PREVIEWS):
        d.mkdir(parents=True, exist_ok=True)

    built = {}
    for name in MODELS:
        model = importlib.import_module(f"models.{name}").build()
        built[name] = model
        model.write(CONTENT / "models" / f"{name}.bbmodel", BUILD / f"{name}.preview.json")
        print(f"model   {name}: {len(model.elements)} elements, textures {model.textures_used()}")
        if render:
            subprocess.run(["node", str(HERE / "render" / "render.mjs"),
                            str(BUILD / f"{name}.preview.json"), str(PREVIEWS / f"{name}.png")], check=True)

    if render:
        lineup = [("crystal_shrine", 0, 0), ("chair", 24, 0), ("potted_plant", 48, 0), ("smelter", 72, 0)]
        scene(built, lineup, BUILD / "lineup.preview.json")
        subprocess.run(["node", str(HERE / "render" / "render.mjs"),
                        str(BUILD / "lineup.preview.json"), str(PREVIEWS / "lineup.png")], check=True)

    for name, fn in T.ICONS.items():
        fn().save(CONTENT / "textures" / f"{name}.png")
        print(f"icon    {name}")

    import special
    names = special.write(CONTENT, BUILD)
    print(f"special {', '.join(names)}")
    special_sheet(PREVIEWS / "special_items.png")
    if render:
        for name in ("knight_shield", "knight_shield_blocking"):
            subprocess.run(["node", str(HERE / "render" / "render.mjs"),
                            str(BUILD / f"{name}.preview.json"), str(BUILD / f"{name}.png")], check=True)
        armor_preview(BUILD / "armor.preview.json")
        subprocess.run(["node", str(HERE / "render" / "render.mjs"),
                        str(BUILD / "armor.preview.json"), str(PREVIEWS / "armor_worn.png")], check=True)

    style_sheet(PREVIEWS / "style_sheet.png")


def scene(models, placements, path: Path, extra_elements=(), extra_textures=None, views=None):
    """Merges several models (offset in model units) into one preview, to judge them side by side."""
    import json
    textures, elements = dict(extra_textures or {}), list(extra_elements)
    for name, ox, oz in placements:
        data = models[name].to_preview()
        textures.update(data["textures"])
        for e in data["elements"]:
            e = dict(e)
            e["from"] = [e["from"][0] + ox, e["from"][1], e["from"][2] + oz]
            e["to"] = [e["to"][0] + ox, e["to"][1], e["to"][2] + oz]
            if e.get("rotation"):
                axis, angle, origin = e["rotation"]
                e["rotation"] = [axis, angle, [origin[0] + ox, origin[1], origin[2] + oz]]
            elements.append(e)
    out = {"textures": textures, "elements": elements, "grid": False,
           "views": views or [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": 20, "el": 18, "k": 2.15}]}
    path.write_text(json.dumps(out))


def special_sheet(path: Path):
    """Every state of every special item, so animations can be checked frame by frame."""
    import special
    from stsv_assets import icons as I
    rows = [
        ("검 / 갑옷", [("sword", I.knight_sword()), ("helmet", I.steel_helmet()), ("chest", I.steel_chestplate()),
                     ("legs", I.steel_leggings()), ("boots", I.steel_boots())]),
        ("활", [(n.replace("hunter_bow", "bow"), g()) for n, (g, _) in special.TEXTURED_MODELS.items() if n.startswith("hunter_bow")]),
        ("쇠뇌", [(n.replace("iron_crossbow", "xbow"), g()) for n, (g, _) in special.TEXTURED_MODELS.items() if n.startswith("iron_crossbow")]),
        ("낚싯대", [(n.replace("angler_rod", "rod"), g()) for n, (g, _) in special.TEXTURED_MODELS.items() if n.startswith("angler_rod")]),
    ]
    cell, pad = 96, 10
    width = pad + 6 * (cell + pad)
    height = pad + len(rows) * (cell + 28) + 2 * (128 + 24)
    im = Image.new("RGBA", (width, height), (27, 29, 34, 255))
    d = ImageDraw.Draw(im)
    y = pad
    for label, items in rows:
        for i, (name, icon) in enumerate(items):
            x = pad + i * (cell + pad)
            bg = Image.new("RGBA", (cell, cell), (45, 48, 56, 255))
            bg.alpha_composite(icon.resize((cell, cell), Image.NEAREST))
            im.paste(bg, (x, y))
            d.text((x, y + cell + 2), name, fill=(200, 200, 200, 255))
        y += cell + 28
    for layer in (I.steel_humanoid(), I.steel_humanoid_leggings()):
        bg = Image.new("RGBA", (256, 128), (45, 48, 56, 255))
        bg.alpha_composite(layer.resize((256, 128), Image.NEAREST))
        im.paste(bg, (pad, y))
        y += 128 + 24
    im.save(path)
    print(f"sheet   {path.relative_to(REPO)}")


def armor_preview(path: Path):
    """A wooden mannequin wearing the steel set, with entity box-UVs from the 64x32 layout."""
    import base64
    import io
    import json
    from stsv_assets import icons as I

    def data_url(im):
        buf = io.BytesIO()
        im.save(buf, "PNG")
        return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()

    tex = {"wood_grain": data_url(T.SURFACES["wood_grain"]()),
           "armor": data_url(I.steel_humanoid()), "legs": data_url(I.steel_humanoid_leggings())}
    els = []

    def cube(frm, to, texture, faces_uv=None):
        faces = {}
        for f in ("north", "south", "east", "west", "up", "down"):
            if faces_uv is None:
                faces[f] = {"uv": [0, 0, 16, 16], "texture": texture}
            elif faces_uv.get(f):
                x0, y0, x1, y1 = faces_uv[f]
                faces[f] = {"uv": [x0 / 4, y0 / 2, x1 / 4, y1 / 2], "texture": texture}
        els.append({"from": list(frm), "to": list(to), "glow": False, "rotation": None, "faces": faces})

    def armor(frm, to, rects, texture, grow):
        f = {"north": rects["front"], "south": rects["back"], "east": rects["right"], "west": rects["left"],
             "up": rects["top"], "down": rects["bottom"]}
        cube([a - grow for a in frm], [b + grow for b in to], texture, f)

    # mannequin (feet at y=0, facing north) shifted to positive space
    o = 8
    parts = {
        "head": ((o - 4, 24, o - 4), (o + 4, 32, o + 4)),
        "body": ((o - 4, 12, o - 2), (o + 4, 24, o + 2)),
        "arm_r": ((o + 4, 12, o - 2), (o + 8, 24, o + 2)),
        "arm_l": ((o - 8, 12, o - 2), (o - 4, 24, o + 2)),
        "leg_r": ((o, 0, o - 2), (o + 4, 12, o + 2)),
        "leg_l": ((o - 4, 0, o - 2), (o, 12, o + 2)),
    }
    for frm, to in parts.values():
        cube(frm, to, "wood_grain")
    armor(*parts["head"], I.HEAD, "armor", 1.0)
    armor(*parts["body"], I.BODY, "armor", 1.0)
    armor(*parts["arm_r"], I.ARM, "armor", 1.0)
    armor(*parts["arm_l"], I.ARM, "armor", 1.0)
    armor(*parts["leg_r"], I.LEG, "armor", 1.0)
    armor(*parts["leg_l"], I.LEG, "armor", 1.0)
    armor(*parts["body"], I.BODY, "legs", 0.5)
    armor(*parts["leg_r"], I.LEG, "legs", 0.5)
    armor(*parts["leg_l"], I.LEG, "legs", 0.5)
    path.write_text(json.dumps({"textures": tex, "elements": els}))


def style_sheet(path: Path):
    """Palette ramps + every surface texture + every icon on one image: the visual style reference."""
    cell, pad = 96, 12
    rows = list(PALETTE.items())
    surf, icons = list(T.SURFACES.items()), list(T.ICONS.items())
    width = pad + max(len(surf), len(icons)) * (cell + pad)
    height = pad + len(rows) * 22 + pad + 2 * (cell + pad + 16) + pad
    im = Image.new("RGBA", (width, height), (27, 29, 34, 255))
    d = ImageDraw.Draw(im)
    y = pad
    for name, ramp in rows:
        d.text((pad, y + 4), name, fill=(200, 200, 200, 255))
        for i, c in enumerate(ramp):
            d.rectangle([pad + 80 + i * 40, y, pad + 80 + i * 40 + 38, y + 18], fill=(*c, 255))
        y += 22
    y += pad
    for items in (surf, icons):
        for i, (name, fn) in enumerate(items):
            x = pad + i * (cell + pad)
            tex = fn().resize((cell, cell), Image.NEAREST)
            bg = Image.new("RGBA", (cell, cell), (45, 48, 56, 255))
            bg.alpha_composite(tex)
            im.paste(bg, (x, y))
            d.text((x, y + cell + 2), name, fill=(200, 200, 200, 255))
        y += cell + pad + 16
    im.save(path)
    print(f"sheet   {path.relative_to(REPO)}")


if __name__ == "__main__":
    sys.path.insert(0, str(HERE))
    main()
