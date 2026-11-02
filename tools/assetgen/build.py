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

MODELS = ["smelter"]  # tools/assetgen/models/<name>.py, each with build() -> Model


def main():
    render = "--render" in sys.argv
    for d in (CONTENT / "models", CONTENT / "textures", BUILD, PREVIEWS):
        d.mkdir(parents=True, exist_ok=True)

    for name in MODELS:
        model = importlib.import_module(f"models.{name}").build()
        model.write(CONTENT / "models" / f"{name}.bbmodel", BUILD / f"{name}.preview.json")
        print(f"model   {name}: {len(model.elements)} elements, textures {model.textures_used()}")
        if render:
            subprocess.run(["node", str(HERE / "render" / "render.mjs"),
                            str(BUILD / f"{name}.preview.json"), str(PREVIEWS / f"{name}.png")], check=True)

    for name, fn in T.ICONS.items():
        fn().save(CONTENT / "textures" / f"{name}.png")
        print(f"icon    {name}")

    style_sheet(PREVIEWS / "style_sheet.png")


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
