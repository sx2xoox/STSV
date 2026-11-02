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
        if render:
            subprocess.run(["node", str(HERE / "render" / "render.mjs"), str(BUILD / f"eco_{name}.preview.json"),
                            str(BUILD / f"eco_{name}.png")], check=True)
    print(f"models  {len(M.ALL)}")

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

    sheet(E, PREVIEWS / "economy_items.png")
    gui_sheet(G, menus, PREVIEWS / "economy_menus.png")
    if render:
        machines = [Image.open(BUILD / f"eco_{n}.png").convert("RGBA") for n in M.ALL]
        w = sum(m.width for m in machines)
        out = Image.new("RGBA", (w, max(m.height for m in machines)), (40, 40, 48, 255))
        x = 0
        for m in machines:
            out.alpha_composite(m, (x, 0))
            x += m.width
        out.save(PREVIEWS / "economy_machines.png")


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
