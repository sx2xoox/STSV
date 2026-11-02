"""
Custom container UI textures, shown through the "title glyph" technique:

The menu title starts with a font glyph that is a full 176x222 image of the large chest screen.
  - a space glyph moves the pen 8 px left (the title is drawn at x=8)
  - the bitmap glyph has ascent 13 so its top lands on the top of the screen (title y=6, baseline 7:
    top = 6 + 7 - 13 = 0)
  - another space glyph moves back so the normal title text still starts at x=8
Items and tooltips are drawn above the title, so the slots stay fully usable.

Slot geometry of the vanilla large chest (generic_54): container slot (row r, col c) has its 18x18
frame at x = 7 + 18c, y = 17 + 18r; the "Inventory" label is at y = 128; player slots at y = 139 + 18r,
hotbar at y = 197. The layout below must match cosmetics.menu in the plugin config.
"""

import json
from pathlib import Path

from PIL import Image

from stsv_assets.palette import ramp, size

W, H = 176, 222
COSMETIC_SLOTS = [r * 9 + c for r in range(1, 5) for c in range(1, 8)]
BUTTONS = {"prev": 45, "take_off": 47, "info": 49, "exit": 51, "next": 53}

# font characters (private use area) - the plugin uses the same code points
GLYPH = "\ue000"
SHIFT_LEFT_8 = "\uf801"
SHIFT_BACK = "\uf802"      # -(176 + 1 - 8) = -169: back to x = 8 after the 177 px glyph advance


def _rect(im, x0, y0, x1, y1, c):
    for y in range(max(0, y0), min(H, y1)):
        for x in range(max(0, x0), min(W, x1)):
            im.putpixel((x, y), c)


def _frame(im, x, y, w, h, light, dark, fill=None):
    """Bevelled box: light on top/left, dark on bottom/right (same light direction as all textures)."""
    if fill is not None:
        _rect(im, x + 1, y + 1, x + w - 1, y + h - 1, fill)
    _rect(im, x, y, x + w, y + 1, light)
    _rect(im, x, y, x + 1, y + h, light)
    _rect(im, x, y + h - 1, x + w, y + h, dark)
    _rect(im, x + w - 1, y, x + w, y + h, dark)


def _slot(im, x, y, inset=True):
    """An 18x18 item cell: recessed (inset, shadow top/left like vanilla) or a raised gold button."""
    if inset:
        _frame(im, x, y, 18, 18, ramp("rubber", 0), ramp("obsidian", 3), fill=ramp("obsidian", 0))
    else:
        _frame(im, x, y, 18, 18, ramp("gold", 3), ramp("gold", 0), fill=ramp("obsidian", 1))
        _frame(im, x + 1, y + 1, 16, 16, ramp("obsidian", 3), ramp("obsidian", 0))


def _slot_xy(slot, inventory=False):
    r, c = divmod(slot, 9)
    return 7 + 18 * c, (139 + 18 * r if r < 3 else 197) if inventory else 17 + 18 * r


def _diamond(im, cx, cy, radius):
    for dy in range(-radius, radius + 1):
        half = radius - abs(dy)
        _rect(im, cx - half, cy + dy, cx + half + 1, cy + dy + 1, ramp("gold", 3 if dy < 0 else 1))
    im.putpixel((cx, cy), ramp("gold", 2))


def _round_corners(im):
    """2-px rounded outer corners, like the vanilla container frame."""
    for (x, y) in ((0, 0), (1, 0), (0, 1)):
        for (fx, fy) in ((x, y), (W - 1 - x, y), (x, H - 1 - y), (W - 1 - x, H - 1 - y)):
            im.putpixel((fx, fy), (0, 0, 0, 0))
    for (fx, fy) in ((1, 1), (W - 2, 1), (1, H - 2), (W - 2, H - 2)):
        im.putpixel((fx, fy), ramp("gold", 1))


def wardrobe():
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # panel: violet body, gold rim with a dark inner line
    _rect(im, 0, 0, W, H, ramp("obsidian", 2))
    _frame(im, 0, 0, W, H, ramp("gold", 2), ramp("gold", 0))
    _frame(im, 1, 1, W - 2, H - 2, ramp("gold", 1), ramp("gold", 0))
    _frame(im, 2, 2, W - 4, H - 4, ramp("obsidian", 0), ramp("obsidian", 0))
    _frame(im, 3, 3, W - 6, H - 6, ramp("obsidian", 3), ramp("obsidian", 1))
    _round_corners(im)

    # header band: dark, so the light title text (y = 6..13) reads well; gold underline
    _rect(im, 4, 4, W - 4, 15, ramp("obsidian", 0))
    _rect(im, 4, 15, W - 4, 16, ramp("gold", 1))
    _rect(im, 4, 16, W - 4, 17, ramp("obsidian", 1))

    # row 0 is free: a gold ribbon with a diamond, the "name plate" of the wardrobe
    _rect(im, 24, 25, 80, 26, ramp("gold", 1))
    _rect(im, 96, 25, 152, 26, ramp("gold", 1))
    _diamond(im, 88, 25, 4)
    _diamond(im, 21, 25, 1)
    _diamond(im, W - 22, 25, 1)

    # cosmetics: recessed tray around the 7x4 grid (bottom edge = the grid's own bottom line)
    gx0, gy0, gx1, gy1 = 25, 35, 25 + 18 * 7, 35 + 18 * 4
    _frame(im, gx0 - 3, gy0 - 3, gx1 - gx0 + 6, gy1 - gy0 + 3, ramp("obsidian", 0), ramp("obsidian", 3),
           fill=ramp("obsidian", 1))
    _rect(im, gx0 - 2, gy0 - 2, gx1 + 2, gy0 - 1, ramp("rubber", 0))
    for sl in COSMETIC_SLOTS:
        _slot(im, *_slot_xy(sl))

    # empty side columns: a hanging-chain ornament with a diamond
    for cx in (15, W - 16):
        for y in range(38, 104, 3):
            im.putpixel((cx, y), ramp("gold", 1))
        _diamond(im, cx, 71, 3)

    # control row: raised gold buttons on a shelf line
    _rect(im, 4, 116, W - 4, 117, ramp("rubber", 0))
    for name, sl in BUTTONS.items():
        _slot(im, *_slot_xy(sl), inset=False)

    # light strip behind the client's dark "Inventory" label (y = 128..135)
    _rect(im, 4, 126, W - 4, 127, ramp("gold", 1))
    _rect(im, 4, 127, W - 4, 137, ramp("cream", 1))
    _rect(im, 4, 137, W - 4, 138, ramp("gold", 0))
    # player inventory and hotbar
    for r in range(3):
        for c in range(9):
            _slot(im, 7 + 18 * c, 139 + 18 * r)
    _rect(im, 7, 194, W - 7, 195, ramp("gold", 1))
    for c in range(9):
        _slot(im, 7 + 18 * c, 197)
    return im


# ------------------------------------------------------------------------------------------------
# Button icons (16x16 items). Drawn from masks and shaded by one rule: a pixel with an empty or
# different-part neighbour above/left is lit, below/right is shaded, the rest is the base step;
# the outline is the darkest step of the part's ramp (same rules as every STSV icon).
# ------------------------------------------------------------------------------------------------

_ARROW = [
    "................",
    "................",
    "................",
    "........#.......",
    "........##......",
    "........###.....",
    "..##########....",
    "..###########...",
    "..############..",
    "..###########...",
    "..##########....",
    "........###.....",
    "........##......",
    "........#.......",
    "................",
    "................",
]
_DOOR = [
    "................",
    "....FFFFFFFF....",
    "....FDDDDDDF....",
    "....FDddddDF....",
    "....FDdDDdDF....",
    "....FDddddDF....",
    "....FDDDDDDF....",
    "....FDDDDKDF....",
    "....FDDDDDDF....",
    "....FDddddDF....",
    "....FDdDDdDF....",
    "....FDdDDdDF....",
    "....FDddddDF....",
    "....FDDDDDDF....",
    "....FFFFFFFF....",
    "................",
]
_HANGER = [
    "................",
    ".......##.......",
    "......#..#......",
    ".........#......",
    "........#.......",
    ".......##.......",
    "......#..#......",
    ".....#....#.....",
    "....#......#....",
    "...#........#...",
    "..#..........#..",
    ".##############.",
    "................",
    "................",
    "................",
    "................",
]
_INFO = [
    "................",
    ".....######.....",
    "....########....",
    "...####ii####...",
    "..#####ii#####..",
    "..############..",
    "..####iii#####..",
    "..#####ii#####..",
    "..#####ii#####..",
    "..#####ii#####..",
    "..####iiii####..",
    "...##########...",
    "....########....",
    ".....######.....",
    "................",
    "................",
]


def _mask_icon(rows, parts):
    """rows: char grid; parts: char -> ramp. Shading by neighbours, outline added around the shape."""
    from stsv_assets import icons as I
    ic = I.Icon()
    cells = {(x, y): ch for y, row in enumerate(rows) for x, ch in enumerate(row) if ch in parts}
    for (x, y), ch in cells.items():
        r = parts[ch]
        base = size(r) // 2
        up, left = cells.get((x, y - 1)), cells.get((x - 1, y))
        down, right = cells.get((x, y + 1)), cells.get((x + 1, y))
        if up != ch or left != ch:
            i = base + 1
        elif down != ch or right != ch:
            i = base - 1
        else:
            i = base
        ic.put(x, y, r, i)
    return ic.outline().image()


def icons():
    arrow = _mask_icon(_ARROW, {"#": "gold"})
    arrow_off = _mask_icon(_ARROW, {"#": "stone"})
    return {
        "next": arrow,
        "prev": arrow.transpose(Image.FLIP_LEFT_RIGHT),
        "next_off": arrow_off,
        "prev_off": arrow_off.transpose(Image.FLIP_LEFT_RIGHT),
        "exit": _mask_icon(_DOOR, {"F": "wood_dark", "D": "wood", "d": "wood_dark", "K": "brass"}),
        "take_off": _mask_icon(_HANGER, {"#": "blade"}),
        "info": _mask_icon(_INFO, {"#": "crystal", "i": "paper"}),
    }


def font():
    return {"providers": [
        {"type": "space", "advances": {SHIFT_LEFT_8: -8, SHIFT_BACK: -(W + 1 - 8)}},
        {"type": "bitmap", "file": "stsv:gui/wardrobe.png", "ascent": 13, "height": H, "chars": [GLYPH]},
    ]}


def write(content_pack: Path):
    """Background glyph (font + texture) and button items (item definition + model + texture).
    Item textures must live under textures/item/ (only that folder is stitched into the item atlas)."""
    assets = content_pack / "assets" / "stsv"
    folders = {k: assets / v for k, v in {"bg": "textures/gui", "font": "font", "tex": "textures/item/gui",
                                          "model": "models/item/gui", "def": "items/gui"}.items()}
    for f in folders.values():
        f.mkdir(parents=True, exist_ok=True)
    img = wardrobe()
    img.save(folders["bg"] / "wardrobe.png")
    (folders["font"] / "wardrobe.json").write_text(json.dumps(font(), indent=2, ensure_ascii=True))

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
    return img, ics


def preview(path: Path, scale=3):
    """The menu as the client would draw it: background, the default control icons and a page of
    sample items in the cosmetic slots (title/labels are left out: no Minecraft font here)."""
    img, ics = wardrobe(), icons()
    canvas = Image.new("RGBA", (W + 16, H + 16), (40, 40, 48, 255))
    canvas.alpha_composite(img, (8, 8))
    samples = [p for p in sorted((Path(__file__).parent.parent.parent / "content/stsv/textures").glob("*.png"))]
    samples = [Image.open(p).convert("RGBA") for p in samples]
    for k, s in enumerate(COSMETIC_SLOTS[:len(samples) * 3]):
        x, y = _slot_xy(s)
        canvas.alpha_composite(samples[k % len(samples)].resize((16, 16), Image.NEAREST), (8 + x + 1, 8 + y + 1))
    for name, s in BUTTONS.items():
        x, y = _slot_xy(s)
        canvas.alpha_composite(ics[name], (8 + x + 1, 8 + y + 1))
    canvas = canvas.resize((canvas.width * scale, canvas.height * scale), Image.NEAREST)
    canvas.save(path)
