"""
Core menu backgrounds and button icons. The plugin shows a background behind a chest menu through a
generated font glyph in the title (see MenuSpec.java); this script only draws the PNGs (one look for
every menu: stsv_assets/menu_theme.py, cells read from each menu's own layout) and the button icons
(stsv_assets/ui_icons.py). The menus themselves are content/stsv/menus/*.yml.
"""

import json
from pathlib import Path

import yaml
from PIL import Image

from stsv_assets import menu_theme as T
from stsv_assets import ui_icons as U

MENUS_DIR = Path(__file__).resolve().parents[2] / "content/stsv/menus"
# the core's own buttons (stsv:gui/<name>); contents use them too (pages, close, info)
CORE_ICONS = ("prev", "next", "prev_off", "next_off", "exit", "info", "take_off", "clear", "back", "chest")


def menus():
    out = {}
    for f in sorted(MENUS_DIR.glob("*.yml")):
        for key, spec in yaml.safe_load(f.read_text(encoding="utf-8")).items():
            out[key] = spec
    return out


def icons():
    every = U.icons()
    return {n: every[n] for n in CORE_ICONS}


def write(content_pack: Path):
    """Backgrounds of the menus that name one, button items (texture + model + definition) and the
    language files that blank the inventory label. Item textures must live under textures/item/."""
    assets = content_pack / "assets" / "stsv"
    folders = {k: assets / v for k, v in {"bg": "textures/gui", "tex": "textures/item/gui",
                                          "model": "models/item/gui", "def": "items/gui"}.items()}
    for f in folders.values():
        f.mkdir(parents=True, exist_ok=True)
    backgrounds = {}
    for key, spec in menus().items():
        bg = spec.get("background")
        if bg and bg.startswith("stsv:gui/"):
            name = bg.split("/", 1)[1]
            backgrounds[name] = T.background(spec)
    for f in folders["bg"].iterdir():
        if f.is_file() and f.stem not in backgrounds:
            f.unlink()
    for name, im in backgrounds.items():
        im.save(folders["bg"] / f"{name}.png")

    ics = icons()
    keep = {"tex": {f"{n}.png" for n in ics}, "model": {f"{n}.json" for n in ics}, "def": {f"{n}.json" for n in ics}}
    for k, names in keep.items():
        for f in folders[k].iterdir():
            if f.is_file() and f.name not in names:
                f.unlink()
    for name, im in ics.items():
        im.save(folders["tex"] / f"{name}.png")
        model = {"parent": "minecraft:item/generated", "textures": {"layer0": f"stsv:item/gui/{name}"}}
        (folders["model"] / f"{name}.json").write_text(json.dumps(model, indent=2))
        definition = {"model": {"type": "minecraft:model", "model": f"stsv:item/gui/{name}"}}
        (folders["def"] / f"{name}.json").write_text(json.dumps(definition, indent=2))
    T.lang_files(content_pack / "assets")
    return backgrounds, ics


def preview(path: Path, scale=3):
    """The core menus side by side with their button icons and sample items in the list cells
    (no title text: there is no Minecraft font here)."""
    specs = {k: s for k, s in menus().items() if s.get("background")}
    every = U.icons()
    samples = [Image.open(p).convert("RGBA") for p in sorted((Path(__file__).parent.parent.parent / "content/stsv/textures").glob("*.png"))]
    ims = []
    for key, spec in specs.items():
        im = T.background(spec)
        lst = spec.get("list")
        k = 0
        for r, row in enumerate(spec["layout"]):
            for c, ch in enumerate(row):
                x, y = 7 + 18 * c, 17 + 18 * r
                if lst and ch == lst and samples and k < len(samples) * 2:
                    im.alpha_composite(samples[k % len(samples)].resize((16, 16), Image.NEAREST), (x + 1, y + 1))
                    k += 1
                elif ch in (spec.get("buttons") or {}):
                    icon = (spec["buttons"][ch] or {}).get("icon", "")
                    name = icon.split("/")[-1] if icon.startswith("stsv:gui/") else None
                    if name in every:
                        im.alpha_composite(every[name], (x + 1, y + 1))
        ims.append(im)
    canvas = Image.new("RGBA", (len(ims) * (T.W + 8) + 8, max(i.height for i in ims) + 16), (32, 34, 40, 255))
    for k, im in enumerate(ims):
        canvas.alpha_composite(im, (8 + k * (T.W + 8), 8))
    canvas.resize((canvas.width * scale, canvas.height * scale), Image.NEAREST).save(path)
