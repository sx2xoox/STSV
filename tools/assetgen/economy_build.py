"""
Builds the economy server's assets into economy/content/economy (icons, machine models, menu
backgrounds, button icons) and preview sheets into docs/previews.

    python3 economy_build.py            # assets
    python3 economy_build.py --render   # + machine previews (needs: cd render && npm install)
"""

import json
import subprocess
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
REPO = HERE.parent.parent
PACK = REPO / "economy" / "content" / "economy"
PREVIEWS = REPO / "docs" / "previews"
BUILD = HERE / "build"
NS = "economy"


def main():
    import economy_gui as G
    import economy_icons as E
    import economy_models as M

    render = "--render" in sys.argv
    for d in (PACK / "textures", PACK / "models", PREVIEWS, BUILD):
        d.mkdir(parents=True, exist_ok=True)

    # 2D item icons
    keep = set()
    for name, fn in E.ITEMS.items():
        fn().save(PACK / "textures" / f"{name}.png")
        keep.add(f"{name}.png")
    for f in (PACK / "textures").glob("*.png"):
        if f.name not in keep:
            f.unlink()
    print(f"icons   {len(E.ITEMS)}")

    # machine models
    for f in (PACK / "models").glob("*.bbmodel"):
        if f.stem not in M.ALL:
            f.unlink()
    for name, fn in M.ALL.items():
        m = fn()
        m.write(PACK / "models" / f"{name}.bbmodel", BUILD / f"eco_{name}.preview.json")
    print(f"models  {len(M.ALL)}")

    # crop art preview (growth stages, harvest and seed icons)
    import economy_crops as CR
    CR.sheet(PREVIEWS / "crops.png")

    # menu backgrounds + button icons
    assets = PACK / "assets" / NS
    folders = {"bg": assets / "textures/gui", "tex": assets / "textures/item/gui", "model": assets / "models/item/gui",
               "def": assets / "items/gui"}
    for f in folders.values():
        f.mkdir(parents=True, exist_ok=True)
        for old in f.iterdir():
            old.unlink()
    menus = G.menus(PACK)
    for key, spec in menus.items():
        if spec.get("background"):
            G.background(spec).save(folders["bg"] / f"{key}.png")
    for name, fn in E.GUI.items():
        fn().save(folders["tex"] / f"{name}.png")
        (folders["model"] / f"{name}.json").write_text(json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/gui/{name}"}}, indent=2))
        (folders["def"] / f"{name}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"{NS}:item/gui/{name}"}}, indent=2))
    print(f"gui     {len(menus)} backgrounds, {len(E.GUI)} button icons")
    import economy_logos as LG
    LG.sheet(PREVIEWS / "stock_logos.png", LG.all_logos(LG.companies(PACK)))

    # fishing: content made from the fish table, the ring font, the fish icon font, a preview sheet
    import economy_fish as FI
    fish_icons = FI.all_icons()
    FI.write_content(PACK, fish_icons)
    FI.ring_font(PACK / "assets")
    FI.icon_font(PACK / "assets", fish_icons)
    FI.sheet(PREVIEWS / "fish.png", fish_icons)
    print(f"fish    {len(FI.FISH)} species")

    # stock chart font (candles in the /주식 window title)
    import economy_stockgraph as SG
    print(f"font    stockgraph {SG.build(PACK / 'assets')} providers")

    sheet(E, PREVIEWS / "economy_items.png")
    gui_sheet(G, menus, PREVIEWS / "economy_menus.png")
    import economy_goods as GO
    GO.sheet(PREVIEWS / "goods.png")
    if render:
        # job machines and the press: every machine in its four tier looks, from the front and from behind
        machine_sheet(PREVIEWS / "machines_front.png", 35, 22)
        machine_sheet(PREVIEWS / "machines_back.png", 215, 24)
        # crops: every ripe crop as it stands in a field (2x2 blocks on farmland), to judge the 3D look
        crop_field(M, CR, BUILD / "crop_field.preview.json")
        subprocess.run(["node", str(HERE / "render" / "render.mjs"), str(BUILD / "crop_field.preview.json"),
                        str(PREVIEWS / "crops_3d.png")], check=True)


MACHINE_ROWS = [("gem_bench", "세공대 (광부) 2×1×2"), ("mill", "가공대 (농부) 2×2×2"), ("fish_bench", "손질대 (어부) 2×1×2"),
                ("forge", "대장간 (대장장이) 2×2×3"), ("kitchen", "조리대 (요리사) 3×1×2"), ("still", "증류기 (연금술사) 2×2×3"),
                ("press", "압착기 (공용) 2×1×2")]


def machine_sheet(path, az, el):
    """Rows of machines, columns of tiers (기본 / 1강 / 2강 / 3강), the same camera for every tier of a machine."""
    import economy_machines as MC
    from PIL import ImageDraw, ImageFont
    font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 30)
    small = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 24)
    sc, head = 0.46, 56
    cw, ch = int(1000 * sc), int(900 * sc)
    out = Image.new("RGBA", (cw * 4, len(MACHINE_ROWS) * (ch + head)), (27, 29, 34, 255))
    d = ImageDraw.Draw(out)
    for row, (mid, title) in enumerate(MACHINE_ROWS):
        _fn, (w, h, dep), _floor, _parts = MC.MACHINES[mid]
        top = MC.preview(mid, 3)["elements"]
        lo = [min(e["from"][i] for e in top) for i in range(3)]
        hi = [max(e["to"][i] for e in top) for i in range(3)]
        y = row * (ch + head)
        d.rectangle((0, y, cw * 4, y + head - 6), fill=(40, 43, 52, 255))
        d.text((16, y + 10), title, fill=(245, 245, 245), font=font)
        for t in range(4):
            data = MC.preview(mid, t)
            data.update({"grid": True, "center": [(lo[i] + hi[i]) / 2 for i in range(3)],
                         "radius": (w * w + h * h + dep * dep) ** 0.5 * 8,
                         "views": [{"x": 0, "y": 0, "w": 1000, "h": 900, "az": az, "el": el, "k": 3.0}]})
            src, png = BUILD / f"machine_{mid}_{t}.json", BUILD / f"machine_{mid}_{t}.png"
            src.write_text(json.dumps(data))
            subprocess.run(["node", str(HERE / "render" / "render.mjs"), str(src), str(png)], check=True, capture_output=True)
            im = Image.open(png).convert("RGBA").crop((0, 0, 1000, 900)).resize((cw, ch), Image.LANCZOS)
            out.alpha_composite(im, (t * cw, y + head))
            d.text((t * cw + 14, y + head + 8), MC.TIERS[t], fill=(230, 230, 230), font=small)
    out.convert("RGB").save(path, optimize=True)


def crop_field(M, CR, path, cols=5, gap=12):
    """Ripe stage of each crop, 2x2 blocks on farmland, in rows of five (left to right as in crops.yml)."""
    import base64
    import io
    soil = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            soil.putpixel((x, y), (74, 42, 22, 255) if y % 4 == 0 else (92, 54, 30, 255) if (x * 7 + y * 3) % 5 else (104, 62, 34, 255))
    buf = io.BytesIO()
    soil.save(buf, "PNG")
    textures = {"soil": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()}
    elements = []
    for k, (crop, stages) in enumerate(CR.STAGES.items()):
        data = M.ALL[f"{crop}_{len(stages) - 1}"]().to_preview()
        textures.update(data["textures"])
        for bx in range(2):
            for bz in range(2):
                ox, oz = (k % cols) * (32 + gap) + bx * 16, (k // cols) * (32 + gap) + bz * 16
                # farmland: its top is 1px below the crop block's floor
                elements.append({"from": [ox, -5, oz], "to": [ox + 16, -1, oz + 16], "glow": False, "rotation": None,
                                 "faces": {f: {"uv": [0, 0, 16, 16], "texture": "soil"} for f in ("up", "north", "south", "east", "west")}})
                for e in data["elements"]:
                    e = dict(e)
                    e["from"] = [e["from"][0] + ox, e["from"][1], e["from"][2] + oz]
                    e["to"] = [e["to"][0] + ox, e["to"][1], e["to"][2] + oz]
                    if e.get("rotation"):
                        axis, angle, o = e["rotation"]
                        e["rotation"] = [axis, angle, [o[0] + ox, o[1], o[2] + oz]]
                    elements.append(e)
    path.write_text(json.dumps({"textures": textures, "elements": elements, "grid": False,
                                "views": [{"x": 0, "y": 0, "w": 1600, "h": 1000, "az": 180, "el": 50, "k": 2.3}]}))


def sheet(E, path, cols=9, scale=4):
    items = list(E.ITEMS.items())
    rows = (len(items) + cols - 1) // cols
    out = Image.new("RGBA", (cols * 18 * scale, rows * 18 * scale), (54, 54, 62, 255))
    for k, (_, fn) in enumerate(items):
        out.alpha_composite(fn().resize((16 * scale, 16 * scale), Image.NEAREST),
                            ((k % cols) * 18 * scale + scale, (k // cols) * 18 * scale + scale))
    out.save(path)


def gui_sheet(G, menus, path, scale=2):
    ims = [G.background(spec) for spec in menus.values() if spec.get("background")]
    cols = 4
    rows = (len(ims) + cols - 1) // cols
    out = Image.new("RGBA", (cols * 184, rows * 230), (40, 40, 48, 255))
    for k, im in enumerate(ims):
        out.alpha_composite(im, ((k % cols) * 184 + 4, (k // cols) * 230 + 4))
    out.resize((out.width * scale // 2 * 2 // 2, out.height * scale // 2 * 2 // 2), Image.NEAREST).save(path)


if __name__ == "__main__":
    main()
