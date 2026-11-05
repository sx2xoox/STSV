"""
Economy server item icons (16x16): job goods (economy_goods.py, lit pixel renderer), crops
(economy_crops.py) and menu button icons (masks below).

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


# ------------------------------------------------------------------------------------------------
# Menu button icons
# ------------------------------------------------------------------------------------------------

COINS = pad([
    "", "", "",
    ".......######...",
    "......#hhhhhh#..",
    "......########..",
    "..######.####...",
    ".#hhhhhh#.......",
    ".########.......",
    "..######........",
    ".#hhhhhh#.......",
    ".########.......",
    "..######........",
])
CHART = pad([
    "",
    "..pppppppppppp..",
    "..p..........p..",
    "..p.......l..p..",
    "..p......l.l.p..",
    "..p..l..l....p..",
    "..p.l.ll.....p..",
    "..pl.........p..",
    "..p..........p..",
    "..pppppppppppp..",
])
HAMMER = pad([
    "",
    "...HHHHHHH......",
    "..HHHHHHHHH.....",
    "..HHHHHHHHH.....",
    ".......tS.......",
    "......tS........",
    ".....tS.........",
    "....tS..........",
    "...tS...........",
    "..tS............",
])
BAG = pad([
    "",
    "......c..c......",
    ".......cc.......",
    "......####......",
    ".....######.....",
    "....########....",
    "...####gg####...",
    "...###gggg###...",
    "...####gg####...",
    "....########....",
])
CROWN = pad([
    "", "", "",
    "..g...g..g...g..",
    "..gg.ggg.ggg.g..",
    "..gggggggggggg..",
    "..ggrggggggrgg..",
    "..gggggggggggg..",
    "..bbbbbbbbbbbb..",
])
LEDGER = pad([
    "",
    "...ccccccccccc..",
    "...cppppppppcc..",
    "...cp.llll.pc...",
    "...cppppppppc...",
    "...cp.lll..pc...",
    "...cppppppppc...",
    "...cp.llll.pc...",
    "...ccccccccccc..",
])
REFRESH = pad([
    "", "",
    "......####......",
    "....##....#.#...",
    "...#.......##...",
    "...#......###...",
    "..#.............",
    "..#..........#..",
    ".............#..",
    "...###......#...",
    "...##.......#...",
    "...#.#....##....",
    "......####......",
])
TROPHY = pad([
    "",
    "...gggggggggg...",
    "..g.gggggggg.g..",
    "..g.gggggggg.g..",
    "...ggggggggggg..",
    ".....gggggg.....",
    ".......gg.......",
    ".......gg.......",
    ".....bbbbbb.....",
    "....bbbbbbbb....",
])
CHEST = pad([
    "", "",
    "..############..",
    "..#..........#..",
    "..############..",
    "..#####kk#####..",
    "..#####kk#####..",
    "..############..",
    "..############..",
])
BACK = pad([
    "", "", "",
    ".......#........",
    "......##........",
    ".....###........",
    "....##########..",
    "...###########..",
    "....##########..",
    ".....###........",
    "......##........",
    ".......#........",
])

GUI = {
    "coins": lambda: mask(COINS, {"#": "gold", "h": ("lamp", 3)}, flat="h"),
    "chart": lambda: mask(CHART, {"p": "paper", "l": "leaf"}),
    "hammer": lambda: mask(HAMMER, {"H": "steel", "S": "wood", "t": ("wood", 3)}, flat="t"),
    "bag": lambda: mask(BAG, {"#": "leather", "c": "brass", "g": "gold"}),
    "crown": lambda: mask(CROWN, {"g": "gold", "r": "ruby", "b": "topaz"}),
    "ledger": lambda: mask(LEDGER, {"c": "felt", "p": "paper", "l": ("steel", 1)}, flat="l"),
    "refresh": lambda: mask(REFRESH, {"#": "leaf"}),
    "trophy": lambda: mask(TROPHY, {"g": "gold", "b": "wood_dark"}),
    "chest": lambda: mask(CHEST, {"#": "wood", "k": "brass"}),
    "back": lambda: mask(BACK, {"#": "gold"}),
}


# ------------------------------------------------------------------------------------------------
# Crops: seed packets, produce, tools
# ------------------------------------------------------------------------------------------------


# job goods and finds, crops: seeds and produce (both hand-drawn in their own modules)
from economy_goods import ICONS as _GOODS_ICONS   # noqa: E402
from economy_crops import ICONS as _CROP_ICONS   # noqa: E402

ITEMS = {}
ITEMS.update(_GOODS_ICONS)
ITEMS.update(_CROP_ICONS)
