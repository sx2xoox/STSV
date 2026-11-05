"""
Economy job goods: hand-drawn 16x16 icons for the job finds (gems, rare fish, pearls) and every processed
good of the six job machines (세공대, 가공대, 손질대, 대장간, 조리대, 증류기).

Same rules as the crop icons (economy_crops.py): every pixel names a palette ramp and a step, light comes
from the top-left, each icon gets the usual outline from the darkest step of its parts and is centred on
the slot grid. Related goods share one drawing (a bottle, a crock, a sack, a dish) filled with their own
contents, so a shelf of them reads as one family.
"""

from economy_crops import PAD, _fit, icon, shade, OUTLINE
from stsv_assets import icons as I

# pale materials whose own darkest step is too light to read as an outline
OUTLINE.update({"pearl": ("stone", 0), "glass": ("stone", 0), "porcelain": ("stone", 0), "rice": ("stone", 0),
                "cream": ("wood", 0), "blade": ("steel", 1)})

ICONS = {}


def L(**kw):
    """A legend from letter=(ramp, step) pairs."""
    return kw


GLINT = ("snow", 2)

# ================================================================================================
# Gems and metals (세공대, 대장간)
# ================================================================================================

ROUGH = [
    "......aa......",
    ".....abba.a...",
    "....sabcbab...",
    "...ssabcbcbs..",
    "..sSsbbccdss..",
    ".sSSssbcdsSs..",
    "sSSsSSssssSSs.",
    "sSsSSsSSsSSss.",
    ".ssSsssSSsss..",
    "..sssSSsss....",
]


def _rough(ramp):
    return icon(ROUGH, L(a=(ramp, 3), b=(ramp, 2), c=(ramp, 1), d=(ramp, 0), s=("stone", 2), S=("stone", 1)))


# a brilliant cut seen a little from above: a light table, crown facets, a pavilion closing to a point
GEM = [
    "...TTTTTTT...",
    "..THHTVTHHT..",
    ".TKHHTTTHHKT.",
    "TKKHHHTHHHKKD",
    "DKKKHHHHHKKKD",
    ".DKKKKHKKKKD.",
    "..DDKKKKKDD..",
    "...DDKKKDD...",
    "....DDKDD....",
    ".....DDD.....",
    "......D......",
]


def _gem(ramp):
    return icon(GEM, L(T=(ramp, 3), H=(ramp, 2), K=(ramp, 1), D=(ramp, 0), V=GLINT))


ICONS["rough_ruby"] = lambda: _rough("ruby")
ICONS["rough_sapphire"] = lambda: _rough("sapphire")
ICONS["ruby"] = lambda: _gem("ruby")
ICONS["sapphire"] = lambda: _gem("sapphire")

# coke: a porous grey-black lump with a dull sheen
ICONS["coke"] = lambda: shade([
    "....##.......",
    "...####..##..",
    "..##o###.###.",
    ".###########.",
    "#####o#######",
    "##o#######o##",
    "############.",
    ".####o#####..",
    "..########...",
    "....####.....",
], "steel", L(o=("soot", 0)), lo=0, hi=4)


def _sheet(ramp, hi, mid, lo, rim):
    """A square metal sheet seen at an angle: a bright top edge, a face lit from the top-left, a dark side."""
    return icon([
        "......AAAAAAA",
        ".....ABBBBBBR",
        "....ABBCBBBCR",
        "...ABBCBBBCRR",
        "..ABBBBBBBCR.",
        ".ABBCBBBBCRR.",
        "ABCCBBBBCCR..",
        "RRRRRRRRRR...",
        "DDDDDDDDDD...",
    ], L(A=(ramp, hi), B=(ramp, mid), C=(ramp, lo), R=(ramp, rim), D=(ramp, 0)))


ICONS["copper_plate"] = lambda: _sheet("copper", 4, 3, 2, 1)
ICONS["iron_plate"] = lambda: _sheet("steel", 5, 4, 3, 2)

# gold leaf: three thin sheets fanned out
ICONS["gold_leaf"] = lambda: icon([
    ".....pppppppp",
    ".....pAAAAAAp",
    "..ppppppppAAp",
    "..pAAAVAAApAp",
    "pppppppppBApp",
    "pAAAAAAAApBp.",
    "pAAVAAAAAppp.",
    "pAAAAAABApp..",
    "pAAAABBBBp...",
    "pAABBBBBBp...",
    "pppppppppp...",
], L(A=("gold", 3), B=("gold", 2), p=("paper", 1), V=GLINT))

# steel ingot: the vanilla ingot shape in blued steel
ICONS["steel_ingot"] = lambda: icon([
    "....AAAAAAAA..",
    "...ABBBBBBBBC.",
    "..ABVBBBBBBBC.",
    ".ABBBBBBBBBCC.",
    "AAAAAAAAACCC..",
    "BBBBBBBBBCC...",
    "BBBBBBBBBC....",
    "CCCCCCCCCC....",
], L(A=("blacksteel", 4), B=("blacksteel", 3), C=("blacksteel", 1), V=("blade", 2)))

# iron chain: oval links alternating face-on and edge-on, running corner to corner
ICONS["iron_chain"] = lambda: icon([
    ".........ABB.",
    "........A..C.",
    "........B..C.",
    ".........CC..",
    ".......BB....",
    "......ABC....",
    ".....A..C....",
    ".....B..C....",
    "......CC.....",
    "....BB.......",
    "...ABC.......",
    "..A..C.......",
    "..B..C.......",
    "...CC........",
], L(A=("steel", 5), B=("steel", 4), C=("steel", 2)))

# oak barrel with two iron hoops
ICONS["oak_barrel"] = lambda: icon([
    "...hhhhhhh...",
    "..AAABAAABB..",
    ".AAABAAABBBC.",
    ".HHHHHHHHHHH.",
    "AAABAAABBBCCC",
    "AAABAAABBBCCC",
    "AAABAAABBBCCC",
    ".HHHHHHHHHHH.",
    ".AAABAAABBBC.",
    "..AAABAABBC..",
    "...CCCCCCC...",
], L(A=("wood", 3), B=("wood", 2), C=("wood", 1), H=("steel", 3), h=("wood_dark", 2)))

# steel tool set: a wooden case, a steel hammer and spanner sticking out
ICONS["steel_toolset"] = lambda: icon([
    "...SS.....Ss..",
    "..SSSS...sS.s.",
    "..SSSS....ss..",
    "....w.....s...",
    "....w....s....",
    "AAAAAAAAAAAAA.",
    "ABBBBBBBBBBBC.",
    "ABBBBGGGBBBBC.",
    "ABBBBBBBBBBBC.",
    "ABBBBBBBBBBBC.",
    "CCCCCCCCCCCCC.",
], L(A=("wood", 3), B=("wood", 2), C=("wood", 0), G=("brass", 2), S=("blacksteel", 3), s=("steel", 4),
     w=("wood_dark", 2)))

# ornate armour: a steel chestplate with gold trim and a ruby set in the middle
ICONS["ornate_armor"] = lambda: icon([
    ".GGG.....GGG.",
    "GAABG...GABBG",
    "GAABBGGGGABBG",
    "GAABBBBBBBBCG",
    ".GABBBRRBBCG.",
    "..GABBRrBBG..",
    "..GABBBBBCG..",
    "..GAAGGGGCG..",
    "..GABBBBBCG..",
    "..GABBBBCCG..",
    "...GGGGGGG...",
], L(A=("steel", 5), B=("steel", 4), C=("steel", 2), G=("gold", 2), R=("ruby", 2), r=("ruby", 3)))

# ruby ring: a gold band seen at an angle with a ruby on top
ICONS["ruby_ring"] = lambda: icon([
    ".....RRR.....",
    "....RrVRR....",
    "....RRRRD....",
    ".....GGG.....",
    "...GGG.GGG...",
    "..GG.....gg..",
    ".GG.......gg.",
    ".G.........g.",
    ".GG.......gg.",
    "..GGg...ggg..",
    "....ggggg....",
], L(R=("ruby", 2), r=("ruby", 3), D=("ruby", 1), V=GLINT, G=("gold", 3), g=("gold", 1)))

# pearl necklace: a loop of pearls with a sapphire drop
ICONS["pearl_necklace"] = lambda: icon([
    ".P.........P.",
    ".p.........p.",
    "..P.......P..",
    "..p.......p..",
    "...P.....P...",
    "...p.....p...",
    "....PpgpP....",
    "......g......",
    ".....SSS.....",
    ".....SsS.....",
    "......S......",
], L(P=("pearl", 2), p=("pearl", 1), g=("gold", 2), S=("sapphire", 2), s=("sapphire", 3)))

# jeweled crown: gold with three points, pearls on the tips, a ruby and two sapphires on the band
ICONS["jeweled_crown"] = lambda: icon([
    "P.....P.....P",
    "G....GGG....G",
    "GG..GGGGG..GG",
    "GGGGGGGGGGGGg",
    "GGGgGGGGGgGGg",
    "AAAAAAAAAAAAA",
    "ASsAAARrAAASs",
    "ASSAAARRAAASS",
    "AAAAAAAAAAAAg",
    "gggggggggggg.",
], L(P=("pearl", 2), G=("gold", 2), g=("gold", 1), A=("gold", 3), R=("ruby", 2), r=("ruby", 3), S=("sapphire", 2),
     s=("sapphire", 3)))

# a pearl in an opened oyster shell, and the polished one
ICONS["pearl"] = lambda: icon([
    "....ccccc....",
    "..ccbbbbbcc..",
    ".cbbaaaaabbc.",
    ".cbaaPPPaabc.",
    "cbbaPVPPpabbc",
    "cbbaPPPppabbc",
    ".cbaapppaabc.",
    ".ccbbaaabbcc.",
    "..dccccccdd..",
    "...ddddddd...",
], L(a=("cream", 3), b=("cream", 1), c=("stone", 2), d=("stone", 1), P=("pearl", 2), p=("pearl", 1), V=GLINT))

ICONS["polished_pearl"] = lambda: shade([
    "....#####....",
    "..#########..",
    ".##VV#######.",
    ".#VV########.",
    "#############",
    "#############",
    "#############",
    "#############",
    ".###########.",
    ".###########.",
    "..#########..",
    "....#####....",
], "pearl", L(V=GLINT), lo=0, hi=2)

# ================================================================================================
# Fish and the 손질대 (rare catches, fillets, sashimi)
# ================================================================================================

# 참돔: deep red-pink body, a tall spiny dorsal fin, faint blue spots, a forked tail
ICONS["red_seabream"] = lambda: shade([
    "....ffff......",
    "..fff###f.....",
    ".#########...t",
    "#E##########tt",
    "#####b###b##tt",
    "#M##########t.",
    ".####b#####.tt",
    "..########...t",
    "....g###g.....",
], "seabream", L(f=("seabream", 0), t=("seabream", 1), E=("roe", 0), b=("seabream", 3), M=("seabream", 0),
                 g=("seabream", 1)), lo=0)

# 참다랑어: torpedo body, dark blue back, silver belly, a row of yellow finlets, a sickle tail
ICONS["bluefin_tuna"] = lambda: icon([
    ".....dd.......",
    "..BBBBBBBB...t",
    ".BBBBBBBBBBBtt",
    "BEBBBBBBBBBBt.",
    "SSSSSSSSSySyt.",
    ".sssssssssssst",
    "..sssssssss..t",
    ".....ss.......",
], L(B=("fish_blue", 2), d=("fish_blue", 1), t=("fish_blue", 1), E=("snow", 2), S=("fish_silver", 3),
     s=("fish_silver", 2), y=("petal_yellow", 1)))

# 철갑상어: long and grey with a pointed snout, a row of pale bony plates, the upper tail lobe longer
ICONS["sturgeon"] = lambda: icon([
    "...........ttt",
    "....p.p.p.p.t.",
    "..AAAAAAAAAAt.",
    ".AAEAAAAAAAAAA",
    "AABBBBBBBBBBBt",
    ".BBBBBBBBBBB..",
    "..w.CCCCCCC.t.",
    "....p.p.p.....",
], L(A=("stone", 2), B=("stone", 1), C=("stone", 0), t=("stone", 1), p=("pearl", 1), E=("roe", 0), w=("pearl", 0)))

# fillets: a boneless slab, flake lines across, the skin a darker edge underneath
ICONS["cod_fillet"] = lambda: icon([
    ".......AAA...",
    ".....AAAaAA..",
    "...AAaAAAaAA.",
    "..AaAAAaAAaAB",
    ".AAAAaAAAaABB",
    "AAaAAAaAAABB.",
    "AAAaAAAaABB..",
    "BAAAaAAABB...",
    "BBBAAABBB....",
    ".sBBBBBs.....",
    "..sssss......",
], L(A=("cream", 3), a=("cream", 1), B=("cream", 1), s=("fish_silver", 1)))

ICONS["salmon_fillet"] = lambda: icon([
    ".......AAA...",
    ".....AAAwAA..",
    "...AAwAAAwAA.",
    "..AwAAAwAAwAB",
    ".AAAAwAAAwABB",
    "AAwAAAwAAABB.",
    "AAAwAAAwABB..",
    "BAAAwAAABB...",
    "BBBAAABBB....",
    ".sBBBBBs.....",
    "..sssss......",
], L(A=("salmon", 2), B=("salmon", 1), w=("salmon", 3), s=("fish_silver", 1)))

# 북어: a whole dried pollack, stiff and golden-tan, mouth open
ICONS["dried_pollack"] = lambda: icon([
    "..........t.t",
    "..........ttt",
    ".........ABt.",
    "........ABBC.",
    ".......ABaBC.",
    "......ABBBC..",
    ".....ABaBC...",
    "....ABBBC....",
    "...ABaBCf....",
    "..ABBBC......",
    ".ABEBC.......",
    "AABBf........",
    "m.C..........",
], L(A=("doenjang", 3), B=("doenjang", 2), C=("doenjang", 1), a=("straw", 3), t=("doenjang", 1), E=("roe", 0),
     m=("doenjang", 0), f=("doenjang", 1)))

# 손질한 복어: paper-thin translucent slices fanned out in a circle on a blue plate (복어회)
ICONS["prepared_puffer"] = lambda: shade([
    "......ff......",
    "....######....",
    "..##########.t",
    ".#E##########t",
    "#############t",
    "#M##########tt",
    ".###########.t",
    "..##########..",
    "....######....",
    "......ff......",
], "porcelain", L(f=("petal_yellow", 0), t=("petal_yellow", 0), E=("roe", 0), M=("seabream", 1)), lo=0)

# 훈제 연어: thin smoky slices laid over one another
ICONS["smoked_salmon"] = lambda: icon([
    "......AAAA...",
    "....AAwAABd..",
    "..AAAwAABBd..",
    "AAwAAABBBd...",
    "AAAwABBdd....",
    "dBBBBddAAAA..",
    ".dddAAwAAABd.",
    "..AAwAAABBBd.",
    ".AAwABBBBdd..",
    ".ddddddd.....",
], L(A=("salmon", 2), B=("salmon", 1), w=("salmon", 3), d=("salmon", 0)))

# 참돔 회: white slices with a pink-red skin edge, on a green leaf
ICONS["seabream_sashimi"] = lambda: icon([
    "..........GG.",
    "..rr....GGgG.",
    ".rAAr.rrGgGG.",
    ".AAAArAAr.gG.",
    "rAAAAAAAArG..",
    "AAAArAAAAAAr.",
    "AAAAAAAAAAAA.",
    "GAAAAAAAAAAG.",
    ".GGgGGgGGgG..",
], L(A=("porcelain", 2), r=("seabream", 1), G=("leaf", 3), g=("leaf", 2)))

# 참치 회: deep red blocks of tuna on a wooden board
ICONS["tuna_sashimi"] = lambda: icon([
    "...AAa.AAa...",
    "..AABBAABBa..",
    ".AABBBAABBBb.",
    ".BBBBbBBBBbb.",
    ".bbbbbbbbbbb.",
    "WWWWWWWWWWWWW",
    "WwwwwwwwwwwwW",
    "DDDDDDDDDDDDD",
], L(A=("tuna_meat", 3), a=("tuna_meat", 2), B=("tuna_meat", 2), b=("tuna_meat", 1), W=("wood", 3), w=("wood", 2),
     D=("wood", 1)))

# 어묵: folded fish cake on a skewer (꼬치 어묵)
ICONS["fishcake"] = lambda: icon([
    ".....s.....",
    "..AAAAAAA..",
    ".ABBBBBBBC.",
    "..CCCCCCC..",
    ".ABBBBBBBC.",
    "..CCCCCCC..",
    ".ABBBBBBBC.",
    "..CCCCCCC..",
    ".ABBBBBBBC.",
    "..CCCCCCC..",
    ".....s.....",
    ".....s.....",
    ".....s.....",
], L(A=("cream", 3), B=("straw", 4), C=("straw", 2), s=("wood", 2)))

# 캐비아: a small round tin, lid tipped open, glossy black roe
ICONS["caviar"] = lambda: icon([
    "...GGGGGGG...",
    "..GgggggggG..",
    "...GGGGGGG...",
    "..GGGGGGGGG..",
    ".GrRrRrRrRrG.",
    "GrRvRrRrRvRrG",
    "GRrRrRvRrRrRG",
    "GgGGGGGGGGGgG",
    ".GgggggggggG.",
    "..ggggggggg..",
], L(G=("gold", 2), g=("gold", 1), R=("roe", 0), r=("roe", 2), v=("steel", 4)))

# ================================================================================================
# 가공대 (rice, flour, tofu, powders, juice, pastes)
# ================================================================================================

def _sack(heap, hl, rows=None):
    """An open cloth sack, its contents heaped at the top (rice, powders)."""
    return icon(rows or [
        "....hhhhh....",
        "..hhHHHHHhh..",
        ".hHHHHHHHHHh.",
        "aAAAAAAAAAAAb",
        ".aAAAAAAAAAb.",
        ".aAAAAAAAAAb.",
        "aAAAAAAAAAAAb",
        "aAAAAAAAAAAAb",
        "aAAAAAAAAAAbb",
        ".aAAAAAAAAbb.",
        "..bbbbbbbbb..",
    ], L(h=heap, H=hl, A=("cream", 2), a=("cream", 3), b=("cream", 0)))


# 쌀: a stitched sack with a red stamp, rice heaped in its open top
ICONS["rice"] = lambda: icon([
    "...h.hHh.h...",
    "..hHhHHhHHh..",
    ".hHHhHHHhHHh.",
    ".aaaaaaaaaab.",
    "..AAAAAAAAb..",
    ".AAAAArrAAAb.",
    ".AAAArRRrAAb.",
    "AAAAArRRrAAAb",
    "AAAAAArrAAAAb",
    "AsAsAsAsAsAAb",
    ".AAAAAAAAAAb.",
    "..bbbbbbbbb..",
], L(h=("rice", 1), H=("rice", 2), A=("cream", 2), a=("cream", 3), b=("cream", 0), r=("fabric_red", 2),
     R=("fabric_red", 3), s=("cream", 1)))

# 옥수수 가루: a yellow-edged paper bag with a corn cob printed on it
ICONS["corn_flour"] = lambda: icon([
    "..ppppppppp..",
    "..PPPPPPPPP..",
    ".pPPPPPPPPPP.",
    ".pPPPPkPPPPq.",
    ".pPPPkjkPPPq.",
    ".pPPPkjkPPPq.",
    ".pPPPkjkPPPq.",
    ".pPPPPgPPPPq.",
    ".pPPPgPgPPPq.",
    ".pPPPPPPPPPq.",
    ".qqqqqqqqqqq.",
], L(p=("corn", 3), P=("cream", 3), q=("corn", 0), k=("corn", 2), j=("corn", 1), g=("sprout", 3)))

# 밀가루: a paper flour bag, top folded, a wheat ear printed on it
ICONS["flour"] = lambda: icon([
    "..ppppppppp..",
    "..PPPPPPPPP..",
    ".pPPPPPPPPPP.",
    ".pPPPPkPPPPq.",
    ".pPPPkjkPPPq.",
    ".pPPPPjPPPPq.",
    ".pPPPkjkPPPq.",
    ".pPPPPjPPPPq.",
    ".pPPPPjPPPPq.",
    ".pPPPPPPPPPq.",
    ".qqqqqqqqqqq.",
], L(p=("paper", 2), P=("paper", 1), q=("paper", 0), k=("straw", 3), j=("straw", 2)))

# 고춧가루: a heap of red pepper powder in a small white dish
ICONS["chili_powder"] = lambda: icon([
    ".....RRR.....",
    "...RRrRRR....",
    "..RrRRRrRR...",
    ".RRRRrRRRRD..",
    "pRRrRRRRrRDDp",
    "pPPPPPPPPPPPp",
    ".pPPPPPPPPPp.",
    "..ppppppppp..",
], L(R=("pepper", 2), r=("pepper", 3), D=("pepper", 1), P=("porcelain", 2), p=("porcelain", 0)))

# 두부: a soft white block on a wooden board
ICONS["tofu"] = lambda: icon([
    "....AAAAAAAA.",
    "...ABBBBBBBC.",
    "..ABBBBBBBCC.",
    ".AAAAAAAAACC.",
    ".AAAAAAAAACC.",
    ".AAAAAAAAACC.",
    ".AAAAAAAAAC..",
    "WWWWWWWWWWWW.",
    "DDDDDDDDDDDD.",
], L(A=("cream", 3), B=("snow", 2), C=("cream", 1), W=("wood", 3), D=("wood", 1)))


def _bottle(liquid, glass="glass", cap=("wood", 2), label=None, rows=None):
    """A glass bottle with a cork; the liquid shows through the lower part."""
    return icon(rows or [
        ".....cc.....",
        ".....gg.....",
        ".....gg.....",
        "....gGGg....",
        "...gGGGGg...",
        "..gVGGGGGg..",
        "..gVLLLLLg..",
        "..gLLLLLLg..",
        "..gLLLLLLg..",
        "..gLLLLLDg..",
        "...gggggg...",
    ], L(c=cap, g=(glass, 0), G=(glass, 2), V=(glass, 3), L=liquid, D=(liquid[0], max(0, liquid[1] - 1))))


ICONS["grape_juice"] = lambda: _bottle(("grape", 2))

# 호박고지: rings of dried zucchini strung on a cord
ICONS["dried_zucchini"] = lambda: icon([
    "s............",
    ".s...........",
    "..AAB........",
    ".AsCAB.......",
    "..BAAB.AAB...",
    "....s.AsCAB..",
    ".....s.BAAB..",
    "........s....",
    ".........AAB.",
    "........AsCAB",
    ".........BAAB",
    "...........s.",
], L(A=("zucchini", 3), B=("zucchini", 1), C=("straw", 3), s=("wood_dark", 2)))

# 토마토 소스: a glass jar of red sauce with a metal lid
ICONS["tomato_sauce"] = lambda: icon([
    "..MMMMMMMMM..",
    "..mmmmmmmmm..",
    "..gGGGGGGGg..",
    ".gVRRRRRRRRg.",
    ".gVRRrRRRRRg.",
    ".gRRRRRRrRRg.",
    ".gRRrRRRRRDg.",
    ".gRRRRRRRDDg.",
    "..ggggggggg..",
], L(M=("steel", 4), m=("steel", 2), g=("glass", 0), G=("glass", 2), V=("glass", 3), R=("tomato", 2),
     r=("tomato", 3), D=("tomato", 1)))


def _crock(paste, hl):
    """An onggi crock: wide shoulders, a narrower foot, a glazed sheen; the paste heaped in its open mouth."""
    return icon([
        "....PPPPP....",
        "...PpPPpPP...",
        "..ooooooooo..",
        "..AAAAAAAAC..",
        ".ABVBBBBBBCC.",
        "ABVVBBBBBBBCC",
        "ABVBBBBBBBBCC",
        "ABBBBBBBBBBCC",
        ".ABBBBBBBBCC.",
        "..BBBBBBBCC..",
        "...CCCCCCC...",
    ], L(o=("onggi", 0), P=paste, p=hl, A=("onggi", 3), B=("onggi", 2), C=("onggi", 1), V=("terracotta", 3)))


ICONS["doenjang"] = lambda: _crock(("doenjang", 2), ("doenjang", 3))
ICONS["gochujang"] = lambda: _crock(("crimson", 2), ("pepper", 2))

# 전통 장 세트: three crocks of paste on a cloth (된장, 고추장, 간장)
ICONS["jang_set"] = lambda: icon([
    "....DDD......",
    "...ooooo.....",
    "..AVBBBBC....",
    ".RR.BBBC.KK..",
    "ooooBCooooo..",
    "AVBBCAVBBBC..",
    "ABBBCABBBBC..",
    ".BBC..BBBC...",
    "fffffffffffff",
    "FFFFFFFFFFFFF",
], L(o=("onggi", 0), D=("doenjang", 3), R=("pepper", 2), K=("roe", 2), A=("onggi", 3), B=("onggi", 2),
     C=("onggi", 1), V=("terracotta", 3), f=("fabric_red", 2), F=("fabric_red", 1)))

# ================================================================================================
# 조리대 (dishes)
# ================================================================================================

PLATE = L(p=("porcelain", 0), P=("porcelain", 2))

# 김치: red-coated cabbage leaves, pale ribs showing, on a white plate
ICONS["kimchi"] = lambda: icon([
    "....RRRR.....",
    "..RRrWRRRr...",
    ".RrWWrRRWrR..",
    ".RRrWRRrWRRR.",
    "RRWRRrRRRrRRR",
    "pRRRRRRRRRRRp",
    "pPPPPPPPPPPPp",
    ".pppppppppp..",
], dict(PLATE, R=("pepper", 2), r=("pepper", 3), W=("cabbage", 3)))

# 깍두기: red radish cubes heaped in a white bowl
ICONS["kkakdugi"] = lambda: icon([
    "..AaB.AaB....",
    ".AaaBAaaBAaB.",
    ".BBBAaBBBAaaB",
    "AaBBaaBAaBBBB",
    "pPPPPPPPPPPPp",
    ".pPPPPPPPPPp.",
    "..pPPPPPPPp..",
    "...ppppppp...",
], dict(PLATE, A=("pepper", 3), a=("pepper", 2), B=("pepper", 1)))

# 오이소박이: cucumber pieces split and stuffed with red filling
ICONS["oi_sobagi"] = lambda: icon([
    "...GrG..GrG..",
    "..GgrgGGgrgG.",
    "..GgrgGGgrgG.",
    ".GrG.GgrgGG..",
    "GgrgGGgrgG...",
    "GgrgGpGGGpp..",
    "pPGGPPPPPPPPp",
    "pPPPPPPPPPPPp",
    ".pppppppppp..",
], dict(PLATE, G=("cucumber", 1), g=("cucumber", 3), r=("pepper", 2)))

# 호박전: golden pan-fried zucchini rounds, stacked
ICONS["hobakjeon"] = lambda: icon([
    "......YYYYY..",
    ".....YzZZzYy.",
    "..YYYYZzZYYy.",
    ".YzZZzYYYyy..",
    "YYZzZYYy.....",
    "YYYYYYyYYYYY.",
    "pyyyyyYzZZzYy",
    "pPPPPPYYZzYYy",
    ".pppppPyyyyyp",
], dict(PLATE, Y=("corn", 2), y=("corn", 0), Z=("zucchini", 3), z=("zucchini", 1)))

# 가지볶음: glossy eggplant strips in a brown sauce
ICONS["eggplant_stirfry"] = lambda: icon([
    "...EeE.EeE...",
    "..EeeSEeeEE..",
    ".SEeEEESeeES.",
    ".EEeSEeEEeeE.",
    "SSSSSSSSSSSSS",
    "pPPPPPPPPPPPp",
    ".pPPPPPPPPPp.",
    "..ppppppppp..",
], dict(PLATE, E=("aubergine", 2), e=("aubergine", 4), S=("doenjang", 2)))

# 순두부찌개: a black clay pot of bubbling red stew, soft tofu and an egg
ICONS["sundubu_jjigae"] = lambda: icon([
    "..o.....o....",
    "...RRRRRRR...",
    ".RRrRWWRRrRR.",
    "KRRRWWWRYRRRK",
    "KkRRRRRRRRRkK",
    "KkkkkkkkkkkkK",
    ".KkkkkkkkkkK.",
    "..KKKKKKKKK..",
], L(o=("snow", 1), R=("pepper", 2), r=("pepper", 3), W=("snow", 2), Y=("petal_yellow", 0), K=("soot", 0),
     k=("soot", 1)))

# 참치 초밥: two nigiri, red tuna over white rice, on a wooden board
ICONS["tuna_sushi"] = lambda: icon([
    "..TTTT.......",
    ".TttTTT.TTTT.",
    ".RRRRRRTttTTT",
    ".RRRRRRRRRRRR",
    ".rrrrrrRRRRRR",
    "......rrrrrrr",
    "WWWWWWWWWWWWW",
    "DDDDDDDDDDDDD",
], L(T=("tuna_meat", 2), t=("tuna_meat", 3), R=("rice", 2), r=("rice", 0), W=("wood", 3), D=("wood", 1)))

# 딸기 케이크: a slice of layered sponge and cream, a strawberry on top
ICONS["strawberry_cake"] = lambda: icon([
    "........SS...",
    ".......SsSg..",
    ".....WWWWWW..",
    "...WWWWWWWWw.",
    ".WWWWWWWWWWw.",
    "YYYYYYYYYYYy.",
    "WWWWsWWWsWWw.",
    "YYYYYYYYYYYy.",
    "yyyyyyyyyyyy.",
], L(S=("toadstool", 2), s=("toadstool", 3), g=("leaf", 3), W=("cream", 3), w=("cream", 1), Y=("corn", 3),
     y=("corn", 1)))

# 토마토 파스타: golden noodles with red sauce on a plate
ICONS["tomato_pasta"] = lambda: icon([
    "....YyYYy....",
    "..YyRRRRYyY..",
    ".YyRRrRRRRyY.",
    ".YRRRRRRrRRY.",
    "YyYRRRRRRYyYy",
    "pYyYyYyYyYyYp",
    "pPPPPPPPPPPPp",
    ".pppppppppp..",
], dict(PLATE, Y=("corn", 3), y=("corn", 1), R=("tomato", 2), r=("tomato", 3)))

# 옥수수빵: a yellow loaf studded with corn kernels
ICONS["corn_bread"] = lambda: shade([
    "...######....",
    ".##k#####k#..",
    "##k####k####.",
    "#####k####k##",
    "#k#####k#####",
    "#####k####k##",
    ".###########.",
    "..#########..",
], "corn", L(k=("topaz", 2)), lo=0)

# 비빔밥: seen from above in a stone bowl, coloured toppings round a fried egg
ICONS["bibimbap"] = lambda: icon([
    "...KKKKKKK...",
    "..KGGGrrrOOK.",
    ".KGGGGrrrOOOK",
    ".KGGWWWWWOOOK",
    ".KbbWWYYWWccK",
    ".KbbWWYYWWccK",
    ".KbbbWWWWcccK",
    ".KRRRRRsssccK",
    "..KRRRRssssK.",
    "...KKKKKKKK..",
], L(K=("soot", 1), G=("leaf", 3), r=("pepper", 2), O=("onion", 3), W=("snow", 2), Y=("petal_yellow", 1),
     b=("doenjang", 2), c=("cucumber", 3), R=("tuna_meat", 2), s=("cream", 2)))

# 생선구이 정식: a tray with a grilled fish, a bowl of rice and a dish of kimchi
ICONS["grilled_fish_set"] = lambda: icon([
    "....hhh.......",
    "...hhhhh..RRr.",
    "...PPPPP.pRrRp",
    "....ppp...ppp.",
    ".FFFFFFFFF.t..",
    "FEFfFFfFFFFtt.",
    ".FFFFFFFFFF.t.",
    "TTTTTTTTTTTTTT",
    "tttttttttttttt",
], L(h=("rice", 2), P=("porcelain", 2), p=("porcelain", 0), R=("pepper", 2), r=("pepper", 3), F=("doenjang", 3),
     f=("soot", 2), E=("roe", 0), t=("doenjang", 1), T=("wood", 3)))

# 한정식: a low table seen from above, covered in small dishes
ICONS["hanjeongsik"] = lambda: icon([
    "WWWWWWWWWWWWWW",
    "WpRpWpGpWpYpWw",
    "WpRpWpGpWpYpWw",
    "WWWWWWWWWWWWWw",
    "WppFFFFFppHpWw",
    "WpEFFfFFpHHpWw",
    "WppFFFFFppHpWw",
    "WWWWWWWWWWWWWw",
    "WpOpWpRRRpcpWw",
    "WpOpWpRRRpcpWw",
    "WWWWWWWWWWWWWw",
    "wwwwwwwwwwwwww",
], L(W=("wood", 3), w=("wood", 1), p=("porcelain", 2), R=("pepper", 2), G=("leaf", 3), Y=("petal_yellow", 1),
     F=("doenjang", 3), f=("soot", 2), E=("roe", 0), H=("rice", 2), O=("onion", 3), c=("cucumber", 3)))

# ================================================================================================
# 증류기 (drinks)
# ================================================================================================

# 막걸리: a white bottle of milky rice wine with a green cap
ICONS["makgeolli"] = lambda: icon([
    "....GG....",
    "....gg....",
    "...WWWW...",
    "..WVWWWw..",
    "..WVWWWw..",
    "..LLLLLL..",
    "..LgGGgL..",
    "..LLLLLL..",
    "..WWWWWw..",
    "..WWWWWw..",
    "..wwwwww..",
], L(G=("bottle_green", 2), g=("bottle_green", 1), W=("porcelain", 2), V=GLINT, w=("porcelain", 0),
     L=("bottle_green", 3)))

# 소주: the small green bottle, a white label
ICONS["soju"] = lambda: icon([
    "....cc....",
    "....GG....",
    "....GG....",
    "...GGGG...",
    "..GVGGGg..",
    "..GVGGGg..",
    "..WWWWWW..",
    "..WbbbbW..",
    "..WWWWWW..",
    "..GGGGGg..",
    "..gggggg..",
], L(c=("bottle_green", 0), G=("bottle_green", 2), V=("bottle_green", 3), g=("bottle_green", 0), W=("paper", 2),
     b=("sapphire", 1)))

# 멜론 시럽: a jar of green syrup, a melon slice on the label
ICONS["melon_syrup"] = lambda: icon([
    "..MMMMMMMMM..",
    "..ccccccccc..",
    "..gGGGGGGGg..",
    ".gVLLLLLLLLg.",
    ".gVLWWWWWLLg.",
    ".gLLWaaaWLLg.",
    ".gLLWWWWWLDg.",
    ".gLLLLLLLDDg.",
    "..ggggggggg..",
], L(M=("cream", 3), c=("fabric_red", 2), g=("glass", 0), G=("glass", 2), V=("glass", 3), L=("sprout", 3),
     D=("sprout", 2), W=("paper", 2), a=("onion", 3)))


def _wine(body, glass_lo, glass_hi, label, extra=None, top=None):
    legend = L(c=top or ("wood", 2), g=(glass_lo[0], glass_lo[1]), G=(glass_hi[0], glass_hi[1]), V=GLINT, W=label[0],
               w=label[1], **(extra or {}))
    return icon(body, legend)


# 포도주: a tall dark bottle, a cork and a cream label
ICONS["wine"] = lambda: _wine([
    "....cc....",
    "....gg....",
    "....gg....",
    "....gg....",
    "...gGGg...",
    "..gVGGGg..",
    "..gVGGGg..",
    "..WWWWWW..",
    "..WrrrrW..",
    "..WWWWWW..",
    "..gGGGGg..",
    "..gggggg..",
], ("wine", 0), ("wine", 2), (("cream", 3), ("cream", 1)), L(r=("wine", 2)))

# 멜론 리큐어: a round bottle of bright green liqueur
ICONS["melon_liqueur"] = lambda: icon([
    ".....cc.....",
    ".....gg.....",
    ".....gg.....",
    "...gggggg...",
    "..gVLLLLLg..",
    ".gVLLLLLLLg.",
    ".gLLLLLLLLg.",
    ".gLLLLLLLDg.",
    "..gLLLLLDg..",
    "...gggggg...",
], L(c=("gold", 2), g=("glass", 0), V=GLINT, L=("sprout", 4), D=("sprout", 3)))

# 진주 향수: a faceted perfume bottle of pink scent, a pearl for a stopper
ICONS["pearl_perfume"] = lambda: icon([
    "......PP.....",
    "......pp.....",
    ".....gGGg....",
    "...gggggggg..",
    "..gVLLLLLLLg.",
    "..gVLlLLlLLg.",
    "..gLLLLLLLLg.",
    "..gLlLLlLLDg.",
    "..gLLLLLLDDg.",
    "...ggggggggg.",
], L(P=("pearl", 2), p=("pearl", 1), g=("glass", 0), G=("gold", 2), V=GLINT, L=("petal_purple", 2),
     l=("petal_purple", 1), D=("petal_purple", 0)))

# 빈티지 포도주: an old bottle with a red wax seal, a yellowed label and dust
ICONS["vintage_wine"] = lambda: _wine([
    "....RR....",
    "....Rr....",
    "....gg....",
    "....gd....",
    "...gGGg...",
    "..gGGGdg..",
    "..gdGGGg..",
    "..WWWWWW..",
    "..WkkkkW..",
    "..WwWWwW..",
    "..gGdGGg..",
    "..gggggg..",
], ("wine", 0), ("wine", 1), (("straw", 3), ("straw", 1)), L(R=("fabric_red", 2), r=("fabric_red", 1),
                                                                k=("doenjang", 1), d=("stone", 1)))

# ================================================================================================
# Names and the preview sheet (docs/previews/goods.png)
# ================================================================================================

GROUPS = [
    ("발견품 (광석·낚시)", {"rough_ruby": "루비 원석", "rough_sapphire": "사파이어 원석", "red_seabream": "참돔", "pearl": "진주",
                     "bluefin_tuna": "참다랑어", "sturgeon": "철갑상어"}),
    ("세공대 · 광부", {"ruby": "루비", "sapphire": "사파이어", "coke": "코크스", "copper_plate": "구리판", "ruby_ring": "루비 반지",
                  "pearl_necklace": "진주 목걸이", "jeweled_crown": "보석 왕관"}),
    ("가공대 · 농부", {"rice": "쌀", "flour": "밀가루", "tofu": "두부", "chili_powder": "고춧가루", "corn_flour": "옥수수 가루",
                  "grape_juice": "포도즙", "dried_zucchini": "호박고지", "tomato_sauce": "토마토 소스", "doenjang": "된장",
                  "gochujang": "고추장", "jang_set": "전통 장 세트"}),
    ("손질대 · 어부", {"cod_fillet": "대구 필렛", "salmon_fillet": "연어 필렛", "dried_pollack": "북어", "prepared_puffer": "손질한 복어",
                  "smoked_salmon": "훈제 연어", "seabream_sashimi": "참돔 회", "tuna_sashimi": "참치 회",
                  "polished_pearl": "연마 진주", "fishcake": "어묵", "caviar": "캐비아"}),
    ("대장간 · 대장장이", {"iron_plate": "철판", "iron_chain": "쇠사슬", "gold_leaf": "금박", "steel_ingot": "강철괴",
                    "oak_barrel": "참나무통", "steel_toolset": "강철 공구 세트", "ornate_armor": "장식용 갑옷"}),
    ("조리대 · 요리사", {"kimchi": "김치", "kkakdugi": "깍두기", "oi_sobagi": "오이소박이", "hobakjeon": "호박전",
                   "eggplant_stirfry": "가지볶음", "sundubu_jjigae": "순두부찌개", "tuna_sushi": "참치 초밥",
                   "strawberry_cake": "딸기 케이크", "tomato_pasta": "토마토 파스타", "corn_bread": "옥수수빵", "bibimbap": "비빔밥",
                   "grilled_fish_set": "생선구이 정식", "hanjeongsik": "한정식"}),
    ("증류기 · 연금술사", {"makgeolli": "막걸리", "soju": "소주", "melon_syrup": "멜론 시럽", "wine": "포도주",
                    "melon_liqueur": "멜론 리큐어", "pearl_perfume": "진주 향수", "vintage_wine": "빈티지 포도주"}),
]
NAMES = {k: v for _, g in GROUPS for k, v in g.items()}


def sheet(path, scale=6, cols=7):
    from PIL import Image, ImageDraw, ImageFont
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 15)
        head = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 19)
    except OSError:
        font = head = ImageFont.load_default()
    cell = 16 * scale
    cw, ch = cell + 34, cell + 30
    rows = sum(1 + (len(g) + cols - 1) // cols for _, g in GROUPS)
    heads = len(GROUPS)
    im = Image.new("RGBA", (cols * cw + 20, rows * ch - heads * (ch - 34) + 20), (46, 48, 56, 255))
    d = ImageDraw.Draw(im)
    y = 10
    for title, items in GROUPS:
        d.text((14, y + 6), title, fill=(255, 226, 150, 255), font=head)
        y += 34
        for k, (item, name) in enumerate(items.items()):
            x = 14 + (k % cols) * cw
            yy = y + (k // cols) * ch
            d.rectangle([x, yy, x + cell, yy + cell], fill=(139, 139, 139, 255))
            ic = ICONS[item]()
            im.alpha_composite(ic.resize((cell, cell), Image.NEAREST), (x, yy))
            d.rectangle([x + cell + 2, yy + cell - 33, x + cell + 33, yy + cell - 1], fill=(139, 139, 139, 255))
            im.alpha_composite(ic.resize((32, 32), Image.NEAREST), (x + cell + 2, yy + cell - 33))
            d.text((x, yy + cell + 4), name, fill=(240, 240, 240, 255), font=font)
        y += ((len(items) + cols - 1) // cols) * ch
    im.crop((0, 0, im.width, y + 10)).save(path)
