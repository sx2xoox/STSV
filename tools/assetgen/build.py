"""
Builds every STSV asset into the default content pack (content/stsv) and renders previews (docs/previews).

    python3 build.py            # assets only (sounds too: needs ffmpeg with libvorbis)
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
# the TEST set (every kind of item and recipe once) is a test fixture, never shipped
FIXTURE = REPO / "plugin" / "src" / "test" / "fixtures" / "stsv"
SHIPPED_MODELS = ("tomato_mask", "eggplant_mask", "mushroom_mask")
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"

RENDER = HERE / "render" / "render.mjs"


def render_preview(json_path, png_path):
    subprocess.run(["node", str(RENDER), str(json_path), str(png_path)], check=True)


def main():
    render = "--render" in sys.argv
    sys.path.insert(0, str(HERE))
    from models.test_items import ALL as ITEMS
    from models import cosmetics
    from models import furniture_japanese as furniture
    from models import vehicles
    from models import vehicles_road
    from models import vehicles_farm
    from models import vehicles_misc
    import furniture_icons
    import vehicle_icons
    import vehicle_icons_road
    import vehicle_icons_farm
    import vehicle_icons_misc
    ALL = {**ITEMS, **cosmetics.ALL, **furniture.ALL, **vehicles.ALL, **vehicles_road.ALL, **vehicles_farm.ALL, **vehicles_misc.ALL}
    import special

    def home(name):
        return CONTENT if name in SHIPPED_MODELS or name in cosmetics.ALL or name in furniture.ALL or name in vehicles.ALL \
            or name in vehicles_road.ALL or name in vehicles_farm.ALL or name in vehicles_misc.ALL else FIXTURE

    for d in (CONTENT / "models", CONTENT / "textures", FIXTURE / "models", FIXTURE / "textures", BUILD, PREVIEWS):
        d.mkdir(parents=True, exist_ok=True)

    # remove generated files of models that no longer exist or moved (otherwise they would still ship)
    for pack in (CONTENT, FIXTURE):
        for stale in (pack / "models").glob("*.bbmodel"):
            if stale.stem not in ALL or home(stale.stem) != pack:
                stale.unlink()
                print(f"removed {stale.relative_to(REPO)} (not defined for this pack)")

    built = {}
    for name, fn in ALL.items():
        model = fn()
        built[name] = model
        model.write(home(name) / "models" / f"{name}.bbmodel", BUILD / f"{name}.preview.json")
        print(f"model   {name}: {len(model.elements)} elements")
        if render:
            render_preview(BUILD / f"{name}.preview.json", PREVIEWS / f"{name}.png")

    for name, fn in T.ICONS.items():
        fn().save(CONTENT / "textures" / f"{name}.png")
        print(f"icon    {name}")
    # furniture: 2D inventory icons (the placed piece shows its 3D model); previews: furniture_preview.py
    for name, fn in furniture_icons.ICONS.items():
        fn().save(CONTENT / "textures" / f"{name}_icon.png")
    print(f"icons   {len(furniture_icons.ICONS)} furniture")
    # vehicles: 2D inventory icons (placed: body, wheels and parts as 3D models); preview sheet: vehicle_preview.py
    for name, fn in vehicle_icons.ICONS.items():
        fn().save(CONTENT / "textures" / f"{name}_icon.png")
    print(f"icons   {len(vehicle_icons.ICONS)} vehicles")
    # road vehicles of the transport ladder (models/vehicles_road.py; preview sheet: vehicle_preview_road.py)
    for name, fn in vehicle_icons_road.ICONS.items():
        fn().save(CONTENT / "textures" / f"{name}_icon.png")
    print(f"icons   {len(vehicle_icons_road.ICONS)} road vehicles")
    # farm vehicles (articulated: vehicles_farm.yml); preview sheet: vehicle_preview_farm.py
    for name, fn in vehicle_icons_farm.ICONS.items():
        fn().save(CONTENT / "textures" / f"{name}_icon.png")
    print(f"icons   {len(vehicle_icons_farm.ICONS)} farm vehicles")
    # 통통배, 지게: icons (vehicle_icons_misc.py), preview sheet: vehicle_misc_preview.py
    for name, fn in vehicle_icons_misc.ICONS.items():
        fn().save(CONTENT / "textures" / f"{name}_icon.png")
    # vehicles: horn and engine sounds (synthesized, Ogg Vorbis) + assets/stsv/sounds.json
    import sounds
    clips = sounds.write()
    print(f"sounds  {len(clips)} vehicle clips, {sum(size for _, size in clips) / 1024:.0f} KB (assets/stsv/sounds)")

    names = special.write(FIXTURE)
    print(f"special (test fixture) {', '.join(names)}")
    print(f"icons   {', '.join(special.write_icons(CONTENT))}")
    import voucher_art
    voucher_art.contact_sheet(PREVIEWS / "vouchers.png")
    special_sheet(PREVIEWS / "special_items.png")
    import gui
    gui.write(CONTENT)
    gui.preview(PREVIEWS / "core_menus.png")
    gui.wardrobe_preview(PREVIEWS / "wardrobe.png")
    print("gui core menus (backgrounds, button icons, inventory label off)")
    import hud
    print(f"hud glyph sheets (plugin/src/main/resources/pack/hud): {hud.write()}")
    import default_font
    print(f"default font, Korean from Neo둥근모 (plugin/src/main/resources/pack/default_font): {default_font.write()}")

    if render:
        # world objects side by side (fan blade placed where the machine spins it)
        lineup = [("test_machine", 0, 0), ("test_fan_blade", 8, 0, 23), ("test_bench", 40, 0),
                  ("test_rug", 78, 0), ("tomato_mask", 100, 0)]
        scene(built, lineup, BUILD / "lineup.preview.json")
        render_preview(BUILD / "lineup.preview.json", PREVIEWS / "lineup.png")
        armor_preview(BUILD / "armor.preview.json")
        render_preview(BUILD / "armor.preview.json", PREVIEWS / "armor_worn.png")

    style_sheet(PREVIEWS / "style_sheet.png")


def scene(models, placements, path: Path, extra_elements=(), extra_textures=None, views=None):
    """Merges several models (offset in model units) into one preview, to judge them side by side."""
    import json
    textures, elements = dict(extra_textures or {}), list(extra_elements)
    for placement in placements:
        name, ox, oz = placement[:3]
        oy = placement[3] if len(placement) > 3 else 0
        data = models[name].to_preview()
        textures.update(data["textures"])
        for e in data["elements"]:
            e = dict(e)
            e["from"] = [e["from"][0] + ox, e["from"][1] + oy, e["from"][2] + oz]
            e["to"] = [e["to"][0] + ox, e["to"][1] + oy, e["to"][2] + oz]
            if e.get("rotation"):
                axis, angle, origin = e["rotation"]
                e["rotation"] = [axis, angle, [origin[0] + ox, origin[1] + oy, origin[2] + oz]]
            elements.append(e)
    out = {"textures": textures, "elements": elements, "grid": False,
           "views": views or [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": 20, "el": 18, "k": 2.15}]}
    path.write_text(json.dumps(out))


def special_sheet(path: Path):
    """Every state of every special item, so animations can be checked frame by frame."""
    import special
    from stsv_assets import icons as I
    gen = lambda n: special.TEXTURED_MODELS[n][0]()
    rows = [
        ("2D", [(n, f()) for n, f in special.ICONS.items()]),
        ("bow", [(n.replace("test_", ""), gen(n)) for n in special.TEXTURED_MODELS if n.startswith("test_bow")]),
        ("crossbow", [(n.replace("test_crossbow", "xbow"), gen(n)) for n in special.TEXTURED_MODELS if n.startswith("test_crossbow")]),
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
    with I.themed(special.TEST_THEME):
        layers = (I.steel_humanoid(),)
    for layer in layers:
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

    import special
    with I.themed(special.TEST_THEME):
        armor_tex = I.steel_humanoid()
    tex = {"wood_grain": data_url(T.SURFACES["wood_grain"]()), "armor": data_url(armor_tex)}
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
    # the test chestplate covers the body and both arms
    armor(*parts["body"], I.BODY, "armor", 1.0)
    armor(*parts["arm_r"], I.ARM, "armor", 1.0)
    armor(*parts["arm_l"], I.ARM, "armor", 1.0)
    path.write_text(json.dumps({"textures": tex, "elements": els}))


def style_sheet(path: Path):
    """Palette ramps + every surface texture + every icon on one image: the visual style reference."""
    # a fixed number of columns (one long row of every icon made an image no browser could open)
    cell, pad, cols = 64, 10, 30
    rows = list(PALETTE.items())
    surf, icons = list(T.SURFACES.items()), list(T.ICONS.items())
    line = cell + pad + 14
    width = pad + cols * (cell + pad)
    grid_rows = sum((len(items) + cols - 1) // cols for items in (surf, icons))
    height = pad + len(rows) * 22 + pad + grid_rows * line + 2 * pad
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
            x = pad + (i % cols) * (cell + pad)
            top = y + (i // cols) * line
            tex = fn().resize((cell, cell), Image.NEAREST)
            bg = Image.new("RGBA", (cell, cell), (45, 48, 56, 255))
            bg.alpha_composite(tex)
            im.paste(bg, (x, top))
            d.text((x, top + cell + 2), name if len(name) <= 11 else name[:10] + "~", fill=(200, 200, 200, 255))
        y += (len(items) + cols - 1) // cols * line + pad
    im.save(path, optimize=True)
    print(f"sheet   {path.relative_to(REPO)}")


if __name__ == "__main__":
    main()
