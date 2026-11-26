"""
트럼프 (playing cards) for the 도박장's 세븐 포커 and 바카라 tables (docs/plans/도박장_기획.md §7): the pictures of the
display models economy:pcard_<rank><suit> and economy:pcard_back (models/gamble.py card_model).

A card is 7 x 10 px lying flat (0.44 x 0.63 block at scale 1; the table shows it at about 0.75) with its picture at 8
texels a px: a face is 56 x 80 texels, the model's texture an atlas of 112 x 80 (the face on the left, the back on the
right; both sizes whole multiples of 16, so the pack's atlas keeps its mipmaps).

Classic and readable from a seat (the big things first): a big bold rank index in the top-left corner with the suit
pip under it (and the same turned round in the bottom-right), red hearts and diamonds, near-black spades and clubs;
the number cards' pips in the classic places (the lower ones upside down), the aces one big pip (the ace of spades an
ornate one), the court cards a small double-ended pixel portrait in a gold-framed panel (king: crown and beard and a
sword, queen: crown, long hair and a flower, jack: a feathered cap and a halberd), the suit's pip in the panel's corner.
The back: navy with a fine lattice, a white margin, a red line inside it and a gold diamond in the middle.

    python3 tools/assetgen/pcard_art.py out_dir     # every face and the back at 1x and 4x (for looking at them)
"""

import math
from pathlib import Path

from PIL import Image

HERE = Path(__file__).parent

W, H = 56, 80
RANKS = ["a", "2", "3", "4", "5", "6", "7", "8", "9", "10", "j", "q", "k"]
SUITS = ["s", "h", "d", "c"]
SUIT_WORD = {"s": "스페이드", "h": "하트", "d": "다이아", "c": "클로버"}
RANK_WORD = {"a": "A", "j": "J", "q": "Q", "k": "K"}


def _rgb(s):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


PAPER = _rgb("f8f5ec")
PAPER_SHADE = _rgb("e9e4d6")
EDGE = _rgb("b9b3a4")
CORNER = _rgb("8e887a")
RED = _rgb("cc1f2b")
RED_DARK = _rgb("8c1219")
RED_LIGHT = _rgb("ec5560")
BLACK = _rgb("1d1d24")
BLACK_LIGHT = _rgb("4a4a58")


def ink(suit):
    return RED if suit in "hd" else BLACK


def ink_light(suit):
    return RED_LIGHT if suit in "hd" else BLACK_LIGHT


# ------------------------------------------------------------------------------------------------
# suit shapes (u, v in -1..1, v down), drawn by coverage at 4 x 4 samples a texel
# ------------------------------------------------------------------------------------------------

def _heart(u, v):
    if (u - 0.5) ** 2 + (v + 0.42) ** 2 <= 0.52 ** 2 or (u + 0.5) ** 2 + (v + 0.42) ** 2 <= 0.52 ** 2:
        return True
    # the point: under the lobes, narrowing to the bottom
    return -0.42 <= v <= 1.0 and abs(u) <= 1.0 * (1.0 - v) / 1.42 * 1.0


def _diamond(u, v):
    return abs(u) / 0.82 + abs(v) <= 1.0


def _spade(u, v):
    # an upside-down heart over a flared stem
    if (u - 0.48) ** 2 + (v - 0.2) ** 2 <= 0.5 ** 2 or (u + 0.48) ** 2 + (v - 0.2) ** 2 <= 0.5 ** 2:
        return True
    if -1.0 <= v <= 0.2 and abs(u) <= 0.98 * (v + 1.0) / 1.2:
        return True
    if 0.3 <= v <= 1.0:
        half = 0.09 + (v - 0.3) / 0.7 * 0.36
        return abs(u) <= half
    return False


def _club(u, v):
    r = 0.4
    for (cx, cy) in ((0.0, -0.52), (-0.52, 0.12), (0.52, 0.12)):
        if (u - cx) ** 2 + (v - cy) ** 2 <= r * r:
            return True
    if abs(u) <= 0.26 and -0.4 <= v <= 0.3:
        return True
    if 0.25 <= v <= 1.0:
        half = 0.09 + (v - 0.25) / 0.75 * 0.34
        return abs(u) <= half
    return False


SHAPES = {"h": _heart, "d": _diamond, "s": _spade, "c": _club}


def suit_mask(suit, w, h, cut=0.45):
    """The suit as rows of bools, w x h texels (symmetric left to right)."""
    fn = SHAPES[suit]
    n = 4
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            hit = 0
            for sy in range(n):
                for sx in range(n):
                    u = ((x + (sx + 0.5) / n) / w) * 2 - 1
                    v = ((y + (sy + 0.5) / n) / h) * 2 - 1
                    hit += fn(u, v)
            row.append(hit / (n * n) >= cut)
        out.append(row)
    # mirror: exactly symmetric
    for row in out:
        for x in range(w // 2):
            row[w - 1 - x] = row[x] = row[x] or row[w - 1 - x]
    return out


# small hand-drawn pips for the index (8 x 8: the drawn ones read better than any sampled shape at this size)
SMALL = {
    "h": [".##..##.",
          "########",
          "########",
          "########",
          ".######.",
          "..####..",
          "...##...",
          "........"],
    "d": ["...##...",
          "..####..",
          ".######.",
          "########",
          "########",
          ".######.",
          "..####..",
          "...##..."],
    "s": ["...##...",
          "..####..",
          ".######.",
          "########",
          "########",
          ".##..##.",
          "...##...",
          "..####.."],
    "c": ["..####..",
          "..####..",
          "##.##.##",
          "########",
          "########",
          "##.##.##",
          "...##...",
          "..####.."],
}


def small_mask(suit):
    return [[c == "#" for c in row] for row in SMALL[suit]]


def put(px, mask, ox, oy, col, flip=False, light=None):
    """Draws a mask at (ox, oy), turned half round when flip (the lower pips and index); light: a lit texel at the
    top-left of each stroke (the ink a touch glossy)."""
    h, w = len(mask), len(mask[0])
    for y in range(h):
        for x in range(w):
            if not mask[y][x]:
                continue
            X, Y = (ox + w - 1 - x, oy + h - 1 - y) if flip else (ox + x, oy + y)
            if 0 <= X < W and 0 <= Y < H:
                px[X, Y] = col
    if light:
        for y in range(h):
            for x in range(w):
                if mask[y][x] and (y == 0 or not mask[y - 1][x]) and (x == 0 or not mask[y][x - 1]) and y + 1 < h and mask[y + 1][x]:
                    X, Y = (ox + w - 1 - x, oy + h - 1 - y) if flip else (ox + x, oy + y)
                    if not flip and 0 <= X < W and 0 <= Y < H:
                        px[X, Y] = light


# ------------------------------------------------------------------------------------------------
# the index: bold 7 x 11 glyphs, 2-texel strokes
# ------------------------------------------------------------------------------------------------

GLYPHS = {
    "a": ["..###..",
          "..###..",
          ".##.##.",
          ".##.##.",
          ".##.##.",
          "##...##",
          "##...##",
          "#######",
          "#######",
          "##...##",
          "##...##"],
    "2": [".#####.",
          "##...##",
          "##...##",
          ".....##",
          "....##.",
          "...##..",
          "..##...",
          ".##....",
          "##.....",
          "#######",
          "#######"],
    "3": [".#####.",
          "##...##",
          ".....##",
          ".....##",
          "..####.",
          "..####.",
          ".....##",
          ".....##",
          ".....##",
          "##...##",
          ".#####."],
    "4": ["....##.",
          "...###.",
          "..####.",
          ".##.##.",
          "##..##.",
          "##..##.",
          "#######",
          "#######",
          "....##.",
          "....##.",
          "....##."],
    "5": ["#######",
          "##.....",
          "##.....",
          "######.",
          "##...##",
          ".....##",
          ".....##",
          ".....##",
          ".....##",
          "##...##",
          ".#####."],
    "6": ["..####.",
          ".##....",
          "##.....",
          "##.....",
          "######.",
          "##...##",
          "##...##",
          "##...##",
          "##...##",
          "##...##",
          ".#####."],
    "7": ["#######",
          "#######",
          ".....##",
          "....##.",
          "....##.",
          "...##..",
          "...##..",
          "..##...",
          "..##...",
          "..##...",
          "..##..."],
    "8": [".#####.",
          "##...##",
          "##...##",
          "##...##",
          ".#####.",
          ".#####.",
          "##...##",
          "##...##",
          "##...##",
          "##...##",
          ".#####."],
    "9": [".#####.",
          "##...##",
          "##...##",
          "##...##",
          "##...##",
          ".######",
          ".....##",
          ".....##",
          ".....##",
          "....##.",
          ".####.."],
    "10": [".##..####.",
           "###.##..##",
           ".##.##..##",
           ".##.##..##",
           ".##.##..##",
           ".##.##..##",
           ".##.##..##",
           ".##.##..##",
           ".##.##..##",
           ".##.##..##",
           "####.####."],
    "j": ["..#####",
          "....##.",
          "....##.",
          "....##.",
          "....##.",
          "....##.",
          "....##.",
          "....##.",
          "##..##.",
          "##..##.",
          ".###..."],
    "q": [".#####.",
          "##...##",
          "##...##",
          "##...##",
          "##...##",
          "##...##",
          "##...##",
          "##.#.##",
          "##..###",
          ".######",
          "......#"],
    "k": ["##...##",
          "##..##.",
          "##.##..",
          "####...",
          "###....",
          "###....",
          "####...",
          "##.##..",
          "##..##.",
          "##...##",
          "##...##"],
}


def glyph(rank):
    return [[c == "#" for c in row] for row in GLYPHS[rank]]


# ------------------------------------------------------------------------------------------------
# the court portraits: half figures 30 x 30 (the left 15 columns drawn, mirrored), the panel double-ended
# ------------------------------------------------------------------------------------------------

PAL = {
    ".": None,
    "k": _rgb("1d1d24"),    # outline
    "s": _rgb("f4cfa6"),    # skin
    "S": _rgb("dca47a"),    # skin shade
    "g": _rgb("f0c03a"),    # gold
    "G": _rgb("b8811c"),    # gold shade
    "r": _rgb("cc1f2b"),    # red
    "R": _rgb("8c1219"),
    "b": _rgb("2f56b0"),    # blue
    "B": _rgb("1c3170"),
    "w": _rgb("fbf9f2"),    # white (ermine, collar)
    "W": _rgb("c9c4b6"),
    "e": _rgb("1d1d24"),    # eye
    "h": _rgb("6b3a1e"),    # brown hair
    "H": _rgb("43230f"),
    "y": _rgb("ece6d8"),    # white beard
    "Y": _rgb("b9b2a2"),
    "o": _rgb("e2a33a"),    # blonde
    "O": _rgb("a86e1c"),
    "m": _rgb("d8505c"),    # lips
    "p": _rgb("e98aa0"),    # pink cheeks / rose
    "l": _rgb("3f8a46"),    # leaf
    "c": _rgb("a8b0bc"),    # steel
    "C": _rgb("6c7482"),
}


def _mirror(rows):
    out = []
    for r in rows:
        assert len(r) == 15, (r, len(r))
        mirrored = r[::-1]
        out.append(r + mirrored)
    return out


# 국왕 (K): crown with gems, brown hair, a full white beard, red robe with ermine and a gold chain; the sword is added
KING = _mirror([
    "...............",
    "........g..g..g",
    "........gg.gg.g",
    "........ggggggg",
    "........grgggbg",
    "........GGGGGGG",
    ".......hhhhhhhh",
    ".......hsssssss",
    ".......hskkksss",
    ".......hswessss",
    ".......hsssssSS",
    ".......hssssssS",
    ".......yysyyyyy",
    ".......yyyyyymm",
    "........yyyyyyy",
    "........yyyyyyy",
    ".........yyyyyy",
    ".....wwwwWyyyyy",
    "....wkwwkwwwyyy",
    "...rrrrrrwwgwwg",
    "..rrrrrrrrrgrrr",
    "..rRrrrrrrgrrrr",
    ".rrRrrrrrrrgrrr",
    ".rrrRrrrrrrrgrr",
    ".rrrRrrrrbbbbbg",
    ".rrrrRrrrbggggg",
    ".rrrrRrrrbgbbbb",
    ".rrrrrRrrbgbBbb",
    ".rrrrrRrrbgbbbb",
    ".rrrrrrRrbgbbbb",
])

# 여왕 (Q): a smaller crown, long blonde hair over her shoulders, a blue gown with a white collar, a rose held up
QUEEN = _mirror([
    "...............",
    "..........g..g.",
    "..........g.ggg",
    "..........ggggg",
    ".........ggrgbg",
    "........oGGGGGG",
    ".......ooooooss",
    ".......oosssss s".replace(" ", ""),
    ".......oskkssss",
    ".......osweesss",
    ".......osssssss",
    ".......oSpssSss",
    "......oossssssm",
    "......ooSsssss.",
    "......oooSSSSSS",
    "......ooooSsss.",
    ".....ooowwwwwww",
    ".....oowwbbbbbw",
    "....obbwbbbgbbb",
    "...bbbbbbbbgbbb",
    "..bbbbBbbbbgbbb",
    "..bBbbbbbbbgbbb",
    ".bbbBbbbbrrrbbb",
    ".bbbbBbbbbbbbbb",
    ".bbbbBbbbgggggg",
    ".bbbbbBbbgwwwww",
    ".bbbbbBbbgwrwrw",
    ".bbbbbbBbgwwwww",
    ".bbbbbbBbgggggg",
    ".bbbbbbbBbbbbbb",
])

# 시종 (J): a feathered cap, brown bobbed hair, no beard, a yellow and red tunic with a collar; a halberd is added
JACK = _mirror([
    "...............",
    "...............",
    "..........rrrrr",
    "........rrrrrrr",
    ".......rrrrrrrr",
    ".......gggGgggg",
    ".......hhhhhhhh",
    ".......hhssssss",
    ".......hskksssk",
    ".......hsweesss",
    ".......hsssssss",
    ".......hhssssSS",
    ".......hhssssmm",
    ".......hhSsssss",
    "........hhSSSSS",
    "..........sssss",
    "........wwwwwww",
    "......wwkwwwkww",
    "....ggggwwwwwww",
    "...gggggggrgggg",
    "..ggGggggrrrggg",
    "..gggGgggrrrggg",
    ".ggggGgggrrrggg",
    ".gggggGggrrrggg",
    ".gggggGggrrrggg",
    ".ggggggGgrrrggg",
    ".ggggggGgkkkkkk",
    ".ggggggGgcCcccC",
    ".gggggggGrrrggg",
    ".gggggggGrrrggg",
])


def _extras(rank, px, ox, oy):
    """Asymmetric details on the top half (in panel coordinates): the king's sword behind his right shoulder, the
    jack's halberd, the queen's rose."""
    def dot(x, y, k):
        if 0 <= x < 30 and 0 <= y < 30:
            px[ox + x, oy + y] = PAL[k]
    if rank == "k":
        for y in range(1, 22):
            dot(26, y, "c")
            dot(27, y, "C")
        for x in range(24, 30):
            dot(x, 21, "g")
        dot(26, 22, "G")
        dot(27, 22, "G")
        dot(26, 0, "c")
    elif rank == "j":
        for y in range(3, 30):
            dot(26, y, "H")
        for (x, y) in ((24, 1), (25, 1), (26, 1), (27, 1), (25, 2), (27, 2), (24, 3), (25, 3), (26, 0), (28, 2)):
            dot(x, y, "c")
        dot(28, 1, "C")
        # a feather on the cap
        for (x, y) in ((8, 1), (7, 0), (6, 0), (9, 2), (8, 2)):
            dot(x, y, "w")
    elif rank == "q":
        for (x, y, k) in ((22, 18, "l"), (22, 19, "l"), (22, 20, "l"), (23, 19, "l"), (21, 16, "r"), (22, 16, "r"),
                          (23, 16, "r"), (21, 17, "R"), (22, 17, "p"), (23, 17, "R"), (22, 15, "R")):
            dot(x, y, k)


PORTRAIT = {"k": KING, "q": QUEEN, "j": JACK}


def _panel(px, rank, suit):
    """The framed panel x 12..43, y 9..70: a gold frame round two half portraits, the lower one turned round."""
    x0, y0, x1, y1 = 12, 9, 43, 70
    frame = _rgb("c8962c")
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if x in (x0, x1) or y in (y0, y1):
                px[x, y] = frame
            else:
                px[x, y] = PAPER
    top = Image.new("RGBA", (30, 30), (0, 0, 0, 0))
    tp = top.load()
    for y, row in enumerate(PORTRAIT[rank]):
        for x, ch in enumerate(row):
            c = PAL[ch]
            if c:
                tp[x, y] = c
    # the robes take the suit's colour scheme a little: red suits keep red, black suits swap red and blue
    if suit in "sc":
        swap = {PAL["r"]: PAL["b"], PAL["R"]: PAL["B"], PAL["b"]: PAL["r"], PAL["B"]: PAL["R"]}
        for y in range(30):
            for x in range(30):
                c = tp[x, y]
                if c in swap:
                    tp[x, y] = swap[c]
    _extras(rank, tp, 0, 0)
    # outline: every figure texel next to the paper gets a dark edge on the outside (reads at a distance)
    out = Image.new("RGBA", (30, 30), (0, 0, 0, 0))
    op = out.load()
    for y in range(30):
        for x in range(30):
            if tp[x, y][3]:
                op[x, y] = tp[x, y]
                continue
            for (dx, dy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                X, Y = x + dx, y + dy
                if 0 <= X < 30 and 0 <= Y < 30 and tp[X, Y][3] and Y < 29:
                    op[x, y] = PAL["k"]
                    break
    for y in range(30):
        for x in range(30):
            c = op[x, y]
            if c[3]:
                px[x0 + 1 + x, y0 + 1 + y] = c
                px[x1 - 1 - x, y1 - 1 - y] = c
    # the split: a fine line of the suit's colour across the middle
    for x in range(x0 + 1, x1):
        px[x, 40] = ink(suit)
    # the suit pip in the panel's top-left and bottom-right corners
    m = small_mask(suit)
    put(px, m, x0 + 2, y0 + 2, ink(suit))
    put(px, m, x1 - 9, y1 - 9, ink(suit), flip=True)


# ------------------------------------------------------------------------------------------------
# faces
# ------------------------------------------------------------------------------------------------

# pip places for the number cards: (column, row) with columns L C R and rows 0 (top) .. 6 (bottom), as on real cards
PIPS = {
    2: [("C", 0), ("C", 6)],
    3: [("C", 0), ("C", 3), ("C", 6)],
    4: [("L", 0), ("R", 0), ("L", 6), ("R", 6)],
    5: [("L", 0), ("R", 0), ("C", 3), ("L", 6), ("R", 6)],
    6: [("L", 0), ("R", 0), ("L", 3), ("R", 3), ("L", 6), ("R", 6)],
    7: [("L", 0), ("R", 0), ("C", 1.5), ("L", 3), ("R", 3), ("L", 6), ("R", 6)],
    8: [("L", 0), ("R", 0), ("C", 1.5), ("L", 3), ("R", 3), ("C", 4.5), ("L", 6), ("R", 6)],
    9: [("L", 0), ("R", 0), ("L", 2), ("R", 2), ("C", 3), ("L", 4), ("R", 4), ("L", 6), ("R", 6)],
    10: [("L", 0), ("R", 0), ("C", 1), ("L", 2), ("R", 2), ("L", 4), ("R", 4), ("C", 5), ("L", 6), ("R", 6)],
}
PIP_W, PIP_H = 12, 12
COL_X = {"L": 12, "C": 22, "R": 32}         # the pip's left texel
ROW_Y0, ROW_Y1 = 6, 62                       # the top of row 0's pip, of row 6's


def blank():
    im = Image.new("RGBA", (W, H), PAPER)
    px = im.load()
    for y in range(H):
        for x in range(W):
            if x in (0, W - 1) or y in (0, H - 1):
                px[x, y] = EDGE
            elif x == 1 or y == 1:
                px[x, y] = _rgb("fffdf6")             # the lit inner edge (light from the top-left)
            elif x == W - 2 or y == H - 2:
                px[x, y] = PAPER_SHADE
    # the corners cut round
    for (cx, cy, sx, sy) in ((0, 0, 1, 1), (W - 1, 0, -1, 1), (0, H - 1, 1, -1), (W - 1, H - 1, -1, -1)):
        px[cx, cy] = CORNER
        px[cx + sx, cy] = EDGE
        px[cx, cy + sy] = EDGE
        px[cx + sx, cy + sy] = EDGE
    return im


def index(px, rank, suit):
    g = glyph(rank)
    gw = len(g[0])
    col = ink(suit)
    x = 2 + (10 - gw) // 2 if rank == "10" else 3
    put(px, g, x, 3, col)
    put(px, g, W - x - gw, H - 3 - len(g), col, flip=True)
    m = small_mask(suit)
    sx = 3 if rank != "10" else 3
    put(px, m, sx, 16, col)
    put(px, m, W - sx - 8, H - 16 - 8, col, flip=True)


def face(rank, suit):
    """56 x 80: the face of one card (rank in RANKS, suit in SUITS), upright."""
    im = blank()
    px = im.load()
    col = ink(suit)
    if rank in ("j", "q", "k"):
        _panel(px, rank, suit)
    elif rank == "a":
        if suit == "s":
            # the ace of spades: a big ornate spade in a fine ring
            big = suit_mask("s", 26, 30)
            put(px, big, 15, 25, col)
            inner = suit_mask("s", 12, 14)
            put(px, inner, 22, 31, PAPER)
            put(px, suit_mask("s", 6, 7), 25, 35, RED)
            for a in range(0, 360, 4):
                x = round(27.5 + 17 * math.cos(math.radians(a)))
                y = round(39.5 + 19 * math.sin(math.radians(a)))
                if 0 <= x < W and 0 <= y < H and px[x, y] == PAPER:
                    px[x, y] = _rgb("c8962c")
        else:
            big = suit_mask(suit, 24, 26)
            put(px, big, 16, 27, col)
            # a glint on the big pip
            for (x, y) in ((20, 31), (21, 31), (20, 32)) if suit == "h" else ((26, 32), (26, 33), (25, 34)) if suit == "d" else ((27, 29), (26, 30)):
                if px[x, y] == col:
                    px[x, y] = ink_light(suit)
    else:
        n = int(rank)
        mask = suit_mask(suit, PIP_W, PIP_H)
        for (c, r) in PIPS[n]:
            y = round(ROW_Y0 + (ROW_Y1 - ROW_Y0) * r / 6)
            put(px, mask, COL_X[c], y, col, flip=r > 3)
    index(px, rank, suit)
    return im


def back():
    """56 x 80: navy with a fine diamond lattice, a white margin, a red line inside it, a gold diamond in the middle."""
    im = blank()
    px = im.load()
    navy, lattice, line = _rgb("1f3576"), _rgb("2f4c9c"), _rgb("c42a34")
    for y in range(4, H - 4):
        for x in range(4, W - 4):
            if x in (4, W - 5) or y in (4, H - 5):
                px[x, y] = line
                continue
            u, v = x - 4, y - 4
            px[x, y] = lattice if (u + v) % 6 == 0 or (u - v) % 6 == 0 else navy
    # the middle: a gold diamond on a navy field, ringed
    gold, gold_dark = _rgb("e8b830"), _rgb("a67a18")
    cx, cy = 27.5, 39.5
    for y in range(H):
        for x in range(W):
            d = abs(x - cx) / 10 + abs(y - cy) / 14
            if d <= 1.0:
                px[x, y] = navy
            if d <= 0.72:
                px[x, y] = gold if d > 0.45 or (x + y) % 2 else gold_dark
            if 0.84 <= d <= 0.95:
                px[x, y] = gold_dark
    put(px, suit_mask("s", 6, 7), 25, 36, navy)
    return im


def atlas(rank, suit):
    """112 x 80: the face (x 0..56) and the back (x 56..112) of one card."""
    out = Image.new("RGBA", (2 * W, H))
    out.paste(face(rank, suit), (0, 0))
    out.paste(back(), (W, 0))
    return out


def back_atlas():
    out = Image.new("RGBA", (2 * W, H))
    out.paste(back(), (0, 0))
    out.paste(back(), (W, 0))
    return out


def ids():
    return [f"pcard_{r}{s}" for s in SUITS for r in RANKS]


def name(rank, suit):
    return f"{SUIT_WORD[suit]} {RANK_WORD.get(rank, rank)}"


def main(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGBA", (13 * (W + 4), 5 * (H + 4)), (30, 90, 50, 255))
    for j, s in enumerate(SUITS):
        for i, r in enumerate(RANKS):
            sheet.paste(face(r, s), (i * (W + 4), j * (H + 4)))
    sheet.paste(back(), (0, 4 * (H + 4)))
    sheet.save(out / "pcards_1x.png")
    sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save(out / "pcards_3x.png")
    return out


if __name__ == "__main__":
    import sys
    print(main(sys.argv[1]))


# ------------------------------------------------------------------------------------------------
# what is printed on the card tables' felt (plates over the felt in models/gamble.py: cut-out, the felt shows round them)
# ------------------------------------------------------------------------------------------------

def _mask_img(mask, col, outline=None, scale=1):
    """A word mask as an RGBA picture (scale x scale texels a font pixel), a 1-texel dark outline round it."""
    h, w = len(mask) * scale, len(mask[0]) * scale
    pad = 1 if outline else 0
    im = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    px = im.load()
    for y in range(h):
        for x in range(w):
            if mask[y // scale][x // scale]:
                px[x + pad, y + pad] = col
    if outline:
        src = im.copy().load()
        for y in range(im.height):
            for x in range(im.width):
                if src[x, y][3]:
                    continue
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        X, Y = x + dx, y + dy
                        if 0 <= X < im.width and 0 <= Y < im.height and src[X, Y][3]:
                            px[x, y] = outline
    return im


PUNCT = {"1": [".##.", "###.", ".##.", ".##.", ".##.", ".##.", ".##.", ".##.", ".##.", ".##.", "####"],
         "0": [".#####.", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", ".#####."],
         ":": ["..", "..", "##", "##", "..", "..", "..", "##", "##", "..", ".."],
         ".": ["..", "..", "..", "..", "..", "..", "..", "..", "..", "##", "##"]}


def _latin(ch):
    rows = PUNCT.get(ch) or GLYPHS[ch.lower()]
    return [[c == "#" for c in r] for r in rows]


def word_mask(text, space=4):
    """Hangul in Neo둥근모 (bold, gamble_art.word_mask), digits and : . in the card index's bold 7 x 11 glyphs, all on
    one line, centred up and down, 2 font px between runs."""
    import gamble_art as ART
    runs, cur, kind = [], "", None
    for ch in text:
        k = "sp" if ch == " " else "ko" if 0xAC00 <= ord(ch) <= 0xD7A3 else "la"
        if k != kind and cur:
            runs.append((kind, cur))
            cur = ""
        kind = k
        cur += ch
    if cur:
        runs.append((kind, cur))
    parts = []
    for k, t in runs:
        if k == "sp":
            parts.append([[False] * (space * len(t))])
        elif k == "ko":
            parts.append(ART.word_mask(t, bold=True, spacing=1, space=space))
        else:
            ms = [_latin(c) for c in t]
            m = []
            for y in range(11):
                row = []
                for i, g in enumerate(ms):
                    if i:
                        row.append(False)
                    row += g[y]
                m.append(row)
            parts.append(m)
    hgt = max(len(p) for p in parts)
    out = [[] for _ in range(hgt)]
    for i, p in enumerate(parts):
        top = (hgt - len(p)) // 2
        w = len(p[0])
        for y in range(hgt):
            src = p[y - top] if 0 <= y - top < len(p) else [False] * w
            if i and len(parts[i - 1]) > 1 and len(p) > 1:
                out[y] += [False, False]
            out[y] += src[:w] if len(src) >= w else src + [False] * (w - len(src))
    return out


def _word(text, col, outline=None, scale=1):
    return _mask_img(word_mask(text), col, outline, scale)


def _paste_rot(dst, im, cx, cy, angle):
    """Pastes im centred on (cx, cy), turned counter-clockwise by angle degrees (nearest texel: stays pixel art)."""
    r = im.rotate(angle, resample=Image.NEAREST, expand=True) if angle else im
    dst.alpha_composite(r, (round(cx - r.width / 2), round(cy - r.height / 2)))


def _wear(im, seed, amount):
    """Old print: some texels rubbed off."""
    from stsv_assets.bake import h3
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            if px[x, y][3] and h3(x, y, seed, 5) < amount:
                px[x, y] = (0, 0, 0, 0)
    return im


POKER_PRINT = (192, 64)     # texels, on a 24 x 8 px plate (8 texels a px)


def poker_print():
    """"세븐 포커" in old gold leaf on the poker felt, a fine rule with a spade under it, worn."""
    w, h = POKER_PRINT
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gold, dark = _rgb("d8b04a"), _rgb("8a6a1c")
    word = _word("세븐 포커", gold, outline=dark, scale=2)
    im.alpha_composite(word, ((w - word.width) // 2, 4))
    y = 4 + word.height + 4
    for x in range(20, w - 20):
        if abs(x - w // 2) > 10:
            im.putpixel((x, y), gold)
    sp = _mask_img(suit_mask("s", 12, 13), gold, dark)
    im.alpha_composite(sp, (w // 2 - sp.width // 2, y - 6))
    return _wear(im, 7, 0.12)


BACCARAT_PRINT = (384, 320)     # texels, on the 48 x 40 px plate (x -16..32, z -16..24 of the table: 8 texels a px)
# in the picture (as the players read it, looking towards the dealer): texel (tx, ty) = model (32 - tx / 8, 24 - ty / 8)
BAC_CENTRE = (8, 8)             # the half disc's centre (model px; the table's anchor cell middle)
BAC_BANDS = [("tie", 5.5, 8.5), ("banker", 8.5, 12), ("player", 12, 15.5)]
BAC_FELT = 20.5                 # the felt's radius inside the rail (the rail is the outer 3.5 px of the 24 px top)
BAC_SPOT_R, BAC_SPOT_RING = 18.0, 2.0   # the seats' numbered circles: on this ring round the centre, this big
# the dealer's two card boxes (model px, centre and half size): the hands are dealt into them
BAC_BOX = {"player": ((16, 13.5), (6.5, 4.5)), "banker": ((0, 13.5), (6.5, 4.5))}

# the baccarat table's seats (cells, item order): clockwise seen from above from the dealer's left end round to the right
BACCARAT_SEATS_CELLS = [(-2, 0), (-2, -1), (-1, -2), (1, -2), (2, -1), (2, 0)]


def _bac_tex(mx, mz):
    return (32 - mx) * 8, (24 - mz) * 8


def bac_seat_spots():
    """Model px of each seat's numbered circle (seats in the item's order): on a ring round the centre, towards it."""
    out = []
    for (sx, sz) in BACCARAT_SEATS_CELLS:
        dx, dz = sx * 16, sz * 16
        d = math.hypot(dx, dz)
        out.append((8 + dx / d * BAC_SPOT_R, 8 + dz / d * BAC_SPOT_R))
    return out


def baccarat_print():
    """The baccarat layout printed on the felt, read from the players' side: three half rings round the middle (타이 8:1
    innermost, then 뱅커 0.95:1, then 플레이어 1:1 nearest the players) in gold lines, each ring's word in its colour
    (green, red, blue) at the front and again turned along the ring at both sides; a numbered circle in front of every
    seat (1 .. 6 = the seat's index + 1); on the dealer's side two card boxes, 플레이어 (blue, the players' left) and
    뱅커 (red), where the hands are dealt."""
    w, h = BACCARAT_PRINT
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    gold, dark = _rgb("d9b14a"), _rgb("16341f")
    cols = {"player": _rgb("6aa6ff"), "banker": _rgb("ff6262"), "tie": _rgb("72e08e")}
    cxm, czm = BAC_CENTRE
    spots = [_bac_tex(*p) for p in bac_seat_spots()]
    for ty in range(h):
        for tx in range(w):
            mx, mz = 32 - (tx + 0.5) / 8, 24 - (ty + 0.5) / 8
            r = math.hypot(mx - cxm, mz - czm)
            if mz <= czm:
                for (_k, r0, r1) in BAC_BANDS:
                    for rr in (r0, r1):
                        if abs(r - rr) < 0.13:
                            px[tx, ty] = gold
            if mz > czm and mz < czm + 0.25 and r <= BAC_BANDS[-1][2]:
                px[tx, ty] = gold                                   # the rings' ends joined along the middle
            for (k, ((bx, bz), (hx, hz))) in BAC_BOX.items():
                if abs(mx - bx) <= hx and abs(mz - bz) <= hz and (abs(mx - bx) > hx - 0.25 or abs(mz - bz) > hz - 0.25):
                    px[tx, ty] = cols[k]
            for (sx, sy) in spots:
                d = math.hypot(tx + 0.5 - sx, ty + 0.5 - sy)
                if BAC_SPOT_RING * 8 - 2 <= d < BAC_SPOT_RING * 8:
                    px[tx, ty] = gold
    words = {"player": ("플레이어", "1:1"), "banker": ("뱅커", "0.95:1"), "tie": ("타이", "8:1")}
    tcx, tcy = _bac_tex(cxm, czm)
    for (k, r0, r1) in BAC_BANDS:
        rm = (r0 + r1) / 2 * 8
        word, pay = words[k]
        _paste_rot(im, _word(word + " " + pay, cols[k], outline=dark), tcx, tcy + rm, 0)
        small = _word(word, cols[k], outline=dark)
        span = {"tie": 62, "banker": 58, "player": 56}[k]
        for sgn in (-1, 1):
            a = math.radians(span) * sgn
            _paste_rot(im, small, tcx + rm * math.sin(a), tcy + rm * math.cos(a), math.degrees(a))
    for i, (sx, sy) in enumerate(spots):
        num = _word(str(i + 1), gold, outline=dark)
        im.alpha_composite(num, (round(sx - num.width / 2), round(sy - num.height / 2)))
    for (k, ((bx, bz), _hs)) in BAC_BOX.items():
        lab = _word(words[k][0], cols[k], outline=dark)
        tx, ty = _bac_tex(bx, bz)
        im.alpha_composite(lab, (round(tx - lab.width / 2), round(ty - lab.height / 2)))
    return _wear(im, 11, 0.05)
