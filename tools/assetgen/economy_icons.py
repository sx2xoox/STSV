"""
Economy server item icons (16x16): job products and menu button icons.

Same rules as every STSV icon (docs/ART_STYLE.md): colours only from palette ramps, light from the
top-left (a pixel whose upper/left neighbour is another part or empty is lit, lower/right is shaded),
outline = the darkest step of the part's own ramp. Shapes are masks; one shape family is reused with
different ramps (three gems, three dusts, fish) so a family reads as a family.
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
# Shapes
# ------------------------------------------------------------------------------------------------

DUST = pad([
    "", "", "", "",
    "..........s.....",
    ".......#........",
    "......###....s..",
    ".....#####......",
    "....###h###.....",
    "...####h####....",
    "..#####hh####...",
    ".##############.",
    ".##############.",
    "..############..",
])

ROUGH = pad([
    "", "", "",
    ".....######.....",
    "....##gg####....",
    "...##gGg#####...",
    "..###gg###gg##..",
    "..#######gGg#...",
    "..##gg########..",
    "...#gGg######...",
    "....#gg######...",
    ".....#######....",
])

CUT = pad([
    "", "",
    ".....HHHHHH.....",
    "....HHhhhhHH....",
    "...HHh####hHH...",
    "..HHh######hHH..",
    "..############..",
    "...##########...",
    "....###hh###....",
    ".....##hh##.....",
    "......####......",
    ".......##.......",
])

FISH = pad([
    "", "", "",
    "..........ff....",
    ".....#######f...",
    "..f.##########..",
    ".ff#######e###..",
    ".ff############.",
    ".ff#bbbbbbbb###.",
    "..f.#bbbbbbb##..",
    ".....#######f...",
    "..........ff....",
])

EEL = pad([
    "", "", "", "", "",
    "............##..",
    "..........###e#.",
    ".......#####..#.",
    "..f.#####.......",
    ".ff###..........",
    "..f.............",
])

SPHERE = pad([
    "", "", "", "",
    "......####......",
    ".....#hh###.....",
    "....#hh#####....",
    "....#h######....",
    "....########....",
    "....########....",
    ".....######.....",
    "......####......",
])

ROE = pad([
    "", "", "", "",
    "......##..##....",
    ".....########...",
    "....##########..",
    "...###########..",
    "...##########...",
    "....########....",
    ".....######.....",
])

CRYSTAL = pad([
    "", "",
    ".......#........",
    "......##h.......",
    "......##h...#...",
    "..#...##h..##...",
    "..##..##h..##...",
    "..##h.##h.##h...",
    "...#h.##h.##h...",
    "...##h##h##h....",
    "....########....",
    "...rrrrrrrrrr...",
    "...rrrrrrrrrr...",
])

LOBSTER = pad([
    "", "",
    "..cc........cc..",
    ".c..c......c..c.",
    ".cc.c......c.cc.",
    "..ccc......ccc..",
    "....c.####.c....",
    ".....######.....",
    "....##e##e##....",
    ".....######.....",
    "......####......",
    ".....######.....",
    "......####......",
    ".....#.##.#.....",
])

WHEAT = pad([
    "",
    ".....g..g..g....",
    "....gg.ggg.gg...",
    "...g.ggggggg.g..",
    "....ggggggggg...",
    ".....gggggggg...",
    "......sssss.....",
    "......sssss.....",
    "......bbbbb.....",
    "......sssss.....",
    ".....sssssss....",
    "....ss.sss.ss...",
    "...s..s.s.s..s..",
])

POTATO = pad([
    "", "", "", "",
    ".......####.....",
    ".....###d####...",
    "....##########..",
    "...####d#####...",
    "...##########...",
    "....#####d##..h.",
    ".....######..hh.",
    "............hh..",
])

CARROT = pad([
    "",
    "..........ll.l..",
    "..........llll..",
    "........lllll...",
    ".........##l....",
    "........####....",
    ".......####.....",
    "......###.#.....",
    ".....####.......",
    "....###.#.......",
    "...####.........",
    "..###...........",
    ".##.............",
])

TRUFFLE = pad([
    "", "", "", "",
    "......####......",
    "....###d####....",
    "...#d#####d##...",
    "...##########...",
    "..####d#####d#..",
    "..###########...",
    "...#d######d#...",
    ".....######.....",
])

HIDE = pad([
    "", "",
    "..###......###..",
    "..############..",
    "...#s.s.s.s.s#..",
    "...##########...",
    "...#s########...",
    "...##########...",
    "...#s#######s#..",
    "..############..",
    "..###......###..",
])

EGG = pad([
    "", "", "",
    "......###.......",
    ".....#h###......",
    "....#h#####.....",
    "....#h#####.....",
    "...#########....",
    "...#########....",
    "...#########....",
    "....#######.....",
    ".....#####......",
])

TIN = pad([
    "", "", "", "",
    "....wwww.ww.....",
    "...cwwccwwwc....",
    "...cccccccccc...",
    "...tttttttttt...",
    "...tTTTTTTTTt...",
    "...tttttttttt...",
    "...tTTTTTTTTt...",
    "...tttttttttt...",
    "....tttttttt....",
])

PILE = pad([
    "", "", "", "", "",
    "........l.......",
    ".......###l.....",
    ".....#l#####....",
    "....####l####...",
    "...#l#########..",
    "..####l####l###.",
    ".###############",
])

SACK = pad([
    "",
    "......tt.t......",
    ".......ttt......",
    "......####......",
    ".....######.....",
    "....########....",
    "...##########...",
    "...####ee####...",
    "...###eeee###...",
    "...####ee####...",
    "...##########...",
    "....########....",
])

INGOT = pad([
    "", "", "", "", "",
    ".....########...",
    "....#hhhhhhhh#..",
    "...#hhhhhhhh##..",
    "..############..",
    "..###########...",
    "..##########....",
])

PICKAXE = pad([
    "",
    "....HHHHHH......",
    "..HHHHHHHHHH....",
    ".HHH.....HHHH...",
    ".HH.....tHHHHH..",
    ".H.....tS...HHH.",
    "......tS.....HH.",
    ".....tS.......H.",
    "....tS..........",
    "...tS...........",
    "..tS............",
    ".tS.............",
])

HOE = pad([
    "",
    ".......HHHHH....",
    "......HHHHHH....",
    "..........tH....",
    ".........tS.....",
    "........tS......",
    ".......tS.......",
    "......tS........",
    ".....tS.........",
    "....tS..........",
    "...tS...........",
    "..tS............",
    ".tS.............",
])

BOX = pad([
    "", "", "",
    "...rrrrrrrrrr...",
    "..rrrrrrrrrrrr..",
    "..############..",
    "..#r########r#..",
    "..#rrrrrrrrrr#..",
    "..#r########r#..",
    "..############..",
    "..############..",
])

BOWL = pad([
    "", "", "", "",
    ".....f.fff.f....",
    "...ffffffffff...",
    "..ffffffffffff..",
    "..############..",
    "...##########...",
    "....########....",
    ".....######.....",
])

SKEWER = pad([
    "", "", "",
    "..........ff....",
    ".....#######f...",
    "s..##########...",
    ".s###########...",
    "..s#########....",
    "...s.######.....",
    "....s...........",
    ".....s..........",
])

SUSHI = pad([
    "", "", "", "",
    "....ffffffff....",
    "...ffffffffff...",
    "...fffffffff....",
    "...##########...",
    "...#n########...",
    "...##########...",
    "....########....",
])

PIE = pad([
    "", "", "", "",
    "....########....",
    "...#hh#hh#hh#...",
    "..#hhhhhhhhhh#..",
    "..#h#hh#hh#hh#..",
    "..############..",
    "...##########...",
])

PLATE = pad([
    "", "", "",
    "......ll........",
    ".....####.......",
    "....######......",
    "...########.....",
    "...#########....",
    "..ppppppppppp...",
    ".ppppppppppppp..",
    "..ppppppppppp...",
])

CUP = pad([
    "", "",
    ".......pp.......",
    "......pppp......",
    "....ffffffff....",
    "...ffffffffff...",
    "...##########...",
    "....########....",
    ".....######.....",
    "......####......",
    ".......##.......",
    ".....######.....",
])

BOTTLE = pad([
    "",
    "......kk........",
    "......kk........",
    ".....gggg.......",
    "......gg........",
    ".....g##g.......",
    "....g####g......",
    "...g######g.....",
    "...g######g.....",
    "...g######g.....",
    "....g####g......",
    ".....gggg.......",
])

VIAL = pad([
    "",
    ".......kk.......",
    "......gggg......",
    ".......gg.......",
    "......g##g......",
    ".....g####g.....",
    ".....g#hh#g.....",
    ".....g####g.....",
    ".....g#hh#g.....",
    ".....g####g.....",
    "......gggg......",
    ".....bbbbbb.....",
])

ORB = pad([
    "", "", "",
    "......aaaa......",
    ".....aaaab.b....",
    "....aaaabbbb....",
    "....aaa.bbbb....",
    "....ccc..bbb....",
    "....cccc.bbb....",
    ".....cccccc.....",
    "......cccc......",
])


def gem_cut(r):
    return mask(CUT, {"#": r, "H": (r, size(r) - 1), "h": (r, size(r) - 2)}, flat="Hh")


def fish(body, belly, fins):
    return mask(FISH, {"#": body, "b": belly, "f": fins, "e": ("obsidian", 0)}, flat="e")


def bottle(liquid):
    return mask(BOTTLE, {"k": "wood", "g": ("snow", 2), "#": liquid}, flat="g")


def tool(shape, head, handle="wood"):
    return mask(shape, {"H": head, "S": handle, "t": (handle, 3)}, flat="t")


ITEMS = {
    # miner
    "iron_dust": lambda: mask(DUST, {"#": "steel", "h": ("blade", 3), "s": ("snow", 2)}, flat="hs"),
    "gold_dust": lambda: mask(DUST, {"#": "gold", "h": ("lamp", 3), "s": ("snow", 2)}, flat="hs"),
    "copper_dust": lambda: mask(DUST, {"#": "copper", "h": ("copper", 4), "s": ("snow", 2)}, flat="hs"),
    "rough_ruby": lambda: mask(ROUGH, {"#": "stone", "g": "ruby", "G": ("ruby", 3)}, flat="G"),
    "rough_sapphire": lambda: mask(ROUGH, {"#": "stone", "g": "sapphire", "G": ("sapphire", 3)}, flat="G"),
    "rough_topaz": lambda: mask(ROUGH, {"#": "stone", "g": "topaz", "G": ("topaz", 3)}, flat="G"),
    "ruby": lambda: gem_cut("ruby"),
    "sapphire": lambda: gem_cut("sapphire"),
    "topaz": lambda: gem_cut("topaz"),
    # farmer
    "bait": lambda: mask(TIN, {"t": "steel", "T": ("steel", 3), "c": "soil", "w": "salmon"}, flat="T"),
    "compost": lambda: mask(PILE, {"#": "soil", "l": ("leaf", 3)}, flat="l"),
    "feed": lambda: mask(SACK, {"#": "wood", "t": "brass", "e": "leaf"}),
    "golden_wheat": lambda: mask(WHEAT, {"g": "gold", "s": "brass", "b": "fabric_red"}),
    "honey_potato": lambda: mask(POTATO, {"#": "terracotta", "d": ("soil", 1), "h": "gold"}, flat="d"),
    "ruby_carrot": lambda: mask(CARROT, {"#": "ruby", "l": "leaf"}),
    "truffle": lambda: mask(TRUFFLE, {"#": "obsidian", "d": ("soil", 3)}, flat="d"),
    "fine_leather": lambda: mask(HIDE, {"#": "copper", "s": ("cream", 3)}, flat="s"),
    "golden_egg": lambda: mask(EGG, {"#": "gold", "h": ("lamp", 3)}, flat="h"),
    # fisher
    "tuna": lambda: fish("steel", "blade", "sapphire"),
    "sea_bream": lambda: fish("tomato", "pearl", "salmon"),
    "eel": lambda: mask(EEL, {"#": "leaf", "f": "soil", "e": ("obsidian", 0)}, flat="e"),
    "golden_carp": lambda: fish("gold", "topaz", "copper"),
    "lobster": lambda: mask(LOBSTER, {"#": "tomato", "c": "fabric_red", "e": ("obsidian", 0)}, flat="e"),
    "salmon_roe": lambda: mask(ROE, {"#": "salmon"}),
    "pearl": lambda: mask(SPHERE, {"#": "pearl", "h": ("snow", 2)}, flat="h"),
    "black_pearl": lambda: mask(SPHERE, {"#": "obsidian", "h": ("crystal", 4)}, flat="h"),
    "sea_crystal": lambda: mask(CRYSTAL, {"#": "spectral", "h": ("snow", 2), "r": "stone"}, flat="h"),
    # blacksmith
    "reinforced_pickaxe": lambda: tool(PICKAXE, "steel"),
    "reinforced_hoe": lambda: tool(HOE, "steel"),
    "reinforced_rod": lambda: _themed_rod(),
    "steel_ingot": lambda: mask(INGOT, {"#": "blade", "h": ("snow", 2)}, flat="h"),
    "steel_pickaxe": lambda: tool(PICKAXE, "blade"),
    "steel_sword": lambda: _themed_sword("blade"),
    "ruby_sword": lambda: _themed_sword("ruby"),
    "sapphire_pickaxe": lambda: tool(PICKAXE, "sapphire", "wood_dark"),
    "topaz_chestplate": lambda: _themed_chest(),
    # cook
    "miner_lunchbox": lambda: mask(BOX, {"#": "wood", "r": "fabric_red"}),
    "lucky_grill": lambda: mask(SKEWER, {"#": "copper", "f": "terracotta", "s": "wood"}),
    "sprint_salad": lambda: mask(BOWL, {"#": "wood", "f": "leaf"}),
    "hearty_stew": lambda: mask(BOWL, {"#": "wood", "f": "terracotta"}),
    "golden_sushi": lambda: mask(SUSHI, {"#": "rice", "f": "gold", "n": ("leaf", 0)}, flat="n"),
    "honey_pie": lambda: mask(PIE, {"#": "wood", "h": "gold"}),
    "royal_feast": lambda: mask(PLATE, {"#": "copper", "p": "pearl", "l": "leaf"}),
    "truffle_risotto": lambda: mask(BOWL, {"#": "wood", "f": "cream"}),
    "pearl_dessert": lambda: mask(CUP, {"#": "snow", "f": "cream", "p": "pearl"}),
    # alchemist
    "fertilizer": lambda: mask(SACK, {"#": "paper", "t": "brass", "e": "leaf"}),
    "miners_tonic": lambda: bottle("copper"),
    "swift_tonic": lambda: bottle("spectral"),
    "catalyst": lambda: mask(VIAL, {"k": "wood", "g": ("snow", 2), "#": "crystal", "h": ("crystal", 4), "b": "brass"}, flat="gh"),
    "super_fertilizer": lambda: mask(SACK, {"#": "paper", "t": "gold", "e": "gold"}),
    "luck_tonic": lambda: bottle("leaf"),
    "grand_catalyst": lambda: mask(VIAL, {"k": "gold", "g": ("snow", 2), "#": "magic", "h": ("aurora", 4), "b": "gold"}, flat="gh"),
    "elixir": lambda: bottle("ruby"),
    "essence": lambda: mask(ORB, {"a": "ruby", "b": "sapphire", "c": "topaz"}),
}


def _themed_sword(blade):
    with I.themed({"blade": blade, "steel": blade}):
        return I.knight_sword()


def _themed_rod():
    with I.themed({"wood": "wood_dark"}):
        return I.fishing_rod()


def _themed_chest():
    with I.themed({"steel": "topaz", "brass": "gold"}):
        return I.steel_chestplate()


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

PACKET = pad([
    "",
    "...pppppppppp...",
    "...pPPPPPPPPp...",
    "...pPPPPPPPPp...",
    "...p..ffff..p...",
    "...p.ffffff.p...",
    "...p.ffffff.p...",
    "...p..ffff..p...",
    "...p...ll...p...",
    "...pPPPPPPPPp...",
    "...pppppppppp...",
])

TOMATO = pad([
    "", "",
    ".......ll.......",
    ".....llllll.....",
    ".....######.....",
    "...#hh#######...",
    "..#hh#########..",
    "..#h##########..",
    "..############..",
    "..############..",
    "...##########...",
    ".....######.....",
])

CHILI = pad([
    "",
    "...ll...........",
    "....ll..........",
    "....###.........",
    "...#h###........",
    "...#h####.......",
    "....#h####......",
    ".....######.....",
    "......######....",
    ".......######...",
    ".........#####..",
    "...........###..",
    ".............#..",
])

BERRY = pad([
    "", "",
    "......l.l.......",
    ".....lllll......",
    "....########....",
    "...#h#.##.###...",
    "...##########...",
    "...#.##.##.##...",
    "....########....",
    "....##.##.##....",
    ".....######.....",
    "......####......",
    ".......##.......",
])

CAN = pad([
    "", "",
    ".........hhh....",
    "........h...h...",
    "..s....#######..",
    "..ss..########..",
    "...ss#########..",
    "....##########..",
    ".....#########..",
    ".....#########..",
    ".....#########..",
    "......#######...",
])

SPRAY = pad([
    "",
    ".......nnn......",
    "......nnnnt.....",
    ".......kk.......",
    "......gggg......",
    ".....g####g.....",
    ".....g#ss#g.....",
    ".....g#ss#g.....",
    ".....g####g.....",
    ".....g####g.....",
    ".....g####g.....",
    "......gggg......",
])


def packet(paper, fruit, leaf="leaf"):
    return mask(PACKET, {"p": paper, "P": (paper, 3), "f": fruit, "l": leaf}, flat="P")


ITEMS.update({
    "golden_wheat_seeds": lambda: packet("cream", "gold"),
    "honey_potato_seed": lambda: packet("cream", "terracotta"),
    "ruby_carrot_seed": lambda: packet("cream", "ruby"),
    "tomato_seeds": lambda: packet("paper", "tomato"),
    "chili_seeds": lambda: packet("paper", "fabric_red"),
    "strawberry_seeds": lambda: packet("paper", "toadstool"),
    "tomato": lambda: mask(TOMATO, {"#": "tomato", "h": ("tomato", 4), "l": "leaf"}, flat="h"),
    "chili_pepper": lambda: mask(CHILI, {"#": "fabric_red", "h": ("tomato", 4), "l": "leaf"}, flat="h"),
    "strawberry": lambda: mask(BERRY, {"#": "toadstool", "h": ("cream", 3), "l": "leaf"}, flat="h"),
    "watering_can": lambda: mask(CAN, {"#": "steel", "h": "steel", "s": "steel"}),
    "copper_watering_can": lambda: mask(CAN, {"#": "copper", "h": "copper", "s": "copper"}),
    "pesticide": lambda: mask(SPRAY, {"n": "steel", "t": "steel", "k": "rubber", "g": ("snow", 2), "#": "leaf", "s": ("obsidian", 1)}, flat="gs"),
})
