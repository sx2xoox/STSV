"""
Economy server item icons (16x16): job goods (economy_goods.py, lit pixel renderer), crops
(economy_crops.py), fish (economy_fish.py) and the menu button icons (the shared UI set).

Same rules as every STSV icon (docs/ART_STYLE.md): colours only from palette ramps, light from the
top-left (a pixel whose upper/left neighbour is another part or empty is lit, lower/right is shaded),
outline = the darkest step of the part's own ramp.
"""

from stsv_assets import icons as I
from stsv_assets.palette import size


def mask(rows, parts, flat=()):
    """rows: 16 strings; parts: char -> ramp name or (ramp, fixed index). flat: chars drawn without
    edge shading (highlights, eyes)."""
    ic = I.Icon()
    cells = {}
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in parts:
                cells[(x, y)] = ch
    for (x, y), ch in cells.items():
        spec = parts[ch]
        if isinstance(spec, tuple):
            ic.put(x, y, spec[0], spec[1])
            continue
        n = size(spec)
        base = (n - 1) // 2 + (1 if n >= 4 else 0)
        if ch in flat:
            ic.put(x, y, spec, base)
            continue
        up, left = cells.get((x, y - 1)), cells.get((x - 1, y))
        down, right = cells.get((x, y + 1)), cells.get((x + 1, y))
        if up != ch or left != ch:
            i = min(n - 1, base + 1)
        elif down != ch or right != ch:
            i = max(1, base - 1)
        else:
            i = base
        ic.put(x, y, spec, i)
    return ic.outline().image()


def pad(rows):
    rows = list(rows) + ["................"] * (16 - len(rows))
    return [r.ljust(16, ".")[:16] for r in rows]


# menu button icons (economy:gui/<name>): the shared UI set (stsv_assets/ui_icons.py), the economy's part
from stsv_assets import ui_icons as _UI   # noqa: E402

_ECONOMY_UI = ("back", "bag", "chart", "chest", "coins", "crown", "hammer", "ledger", "refresh", "trophy", "unknown", "wave",
               "sell_all", "arrow_left", "arrow_right", "rise", "fall", "buy_1", "buy_10", "buy_100", "sell_1", "sell_10", "sell_100", "period_24", "period_7", "period_30")
GUI = {name: (lambda n=name: _UI.icons()[n]) for name in _ECONOMY_UI}


# ------------------------------------------------------------------------------------------------
# Crops: seed packets, produce, tools
# ------------------------------------------------------------------------------------------------


# job goods and finds, crops: seeds and produce (both hand-drawn in their own modules)
from economy_goods import ICONS as _GOODS_ICONS   # noqa: E402
from economy_crops import ICONS as _CROP_ICONS   # noqa: E402
from economy_fish import ICONS as _FISH_ICONS   # noqa: E402

ITEMS = {}
ITEMS.update(_GOODS_ICONS)
ITEMS.update(_CROP_ICONS)
ITEMS.update(_FISH_ICONS)
