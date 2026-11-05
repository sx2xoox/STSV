"""
Economy menu backgrounds (176 x (114 + 18 x rows)), drawn from each menu's own layout in
economy/content/economy/menus/*.yml: the paged list's cells are recessed trays, buttons are raised
brass, empty slots are plain felt. Same construction as the wardrobe (gui.py): bevel light from the
top-left, a dark title band, a light strip behind the client's dark "Inventory" label.
Theme: a merchant's ledger - dark green felt, brass rim.
"""

from pathlib import Path

import yaml
from PIL import Image

from stsv_assets.palette import ramp

W = 176


def _rect(im, x0, y0, x1, y1, c):
    for y in range(max(0, y0), min(im.height, y1)):
        for x in range(max(0, x0), min(W, x1)):
            im.putpixel((x, y), c)


def _frame(im, x, y, w, h, light, dark, fill=None):
    if fill is not None:
        _rect(im, x + 1, y + 1, x + w - 1, y + h - 1, fill)
    _rect(im, x, y, x + w, y + 1, light)
    _rect(im, x, y, x + 1, y + h, light)
    _rect(im, x, y + h - 1, x + w, y + h, dark)
    _rect(im, x + w - 1, y, x + w, y + h, dark)


def _cell(im, x, y, kind):
    if kind == "list":
        _frame(im, x, y, 18, 18, ramp("felt", 0), ramp("felt", 3), fill=ramp("rubber", 1))
        _rect(im, x + 1, y + 1, x + 17, y + 2, ramp("rubber", 0))
    elif kind == "button":
        _frame(im, x, y, 18, 18, ramp("brass", 3), ramp("brass", 0), fill=ramp("felt", 1))
        _frame(im, x + 1, y + 1, 16, 16, ramp("felt", 3), ramp("felt", 0))
    else:   # inventory
        _frame(im, x, y, 18, 18, ramp("rubber", 0), ramp("felt", 3), fill=ramp("felt", 0))


def background(menu):
    rows = menu["rows"]
    H = 114 + 18 * rows
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    _rect(im, 0, 0, W, H, ramp("felt", 2))
    _frame(im, 0, 0, W, H, ramp("brass", 2), ramp("brass", 0))
    _frame(im, 1, 1, W - 2, H - 2, ramp("brass", 1), ramp("brass", 0))
    _frame(im, 2, 2, W - 4, H - 4, ramp("felt", 0), ramp("felt", 0))
    _frame(im, 3, 3, W - 6, H - 6, ramp("felt", 3), ramp("felt", 1))
    for (x, y) in ((0, 0), (1, 0), (0, 1)):
        for (fx, fy) in ((x, y), (W - 1 - x, y), (x, H - 1 - y), (W - 1 - x, H - 1 - y)):
            im.putpixel((fx, fy), (0, 0, 0, 0))
    # title band
    _rect(im, 4, 4, W - 4, 15, ramp("felt", 0))
    _rect(im, 4, 15, W - 4, 16, ramp("brass", 1))
    # ledger lines across the empty felt (every other row of pixels, faint)
    for y in range(20, 17 + 18 * rows, 6):
        _rect(im, 6, y, W - 6, y + 1, ramp("felt", 1))
    if menu.get("graph"):
        # stock chart panel over the top two rows: dark screen, faint grid (the candles are title text)
        _frame(im, 8, 17, W - 16, 38, ramp("felt", 0), ramp("felt", 3), fill=ramp("rubber", 0))
        for gy in range(20, 53, 8):
            _rect(im, 10, gy, W - 10, gy + 1, ramp("rubber", 1))
    lst = menu.get("list")
    buttons = set(menu.get("buttons", {}).keys())
    inset = set(menu.get("inset", ""))
    for r, row in enumerate(menu["layout"]):
        for c, ch in enumerate(row):
            x, y = 7 + 18 * c, 17 + 18 * r
            if (lst and ch == lst) or ch in inset:
                _cell(im, x, y, "list")
            elif ch in buttons:
                _cell(im, x, y, "button")
    # "Inventory" label strip and the player's inventory
    label = 18 * rows + 18
    _rect(im, 4, label - 1, W - 4, label, ramp("brass", 1))
    _rect(im, 4, label, W - 4, label + 10, ramp("paper", 1))
    _rect(im, 4, label + 10, W - 4, label + 11, ramp("brass", 0))
    inv = 31 + 18 * rows
    for r in range(3):
        for c in range(9):
            _cell(im, 7 + 18 * c, inv + 18 * r, "inv")
    _rect(im, 7, inv + 55, W - 7, inv + 56, ramp("brass", 1))
    for c in range(9):
        _cell(im, 7 + 18 * c, inv + 58, "inv")
    return im


def menus(pack: Path):
    out = {}
    for f in sorted((pack / "menus").glob("*.yml")):
        for key, spec in yaml.safe_load(f.read_text(encoding="utf-8")).items():
            out[key] = spec
    return out
