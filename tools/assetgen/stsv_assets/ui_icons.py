"""
Menu button icons (16 x 16) for the charcoal menus: one set for the core (stsv:gui/*) and the economy
(economy:gui/*). Same rules for all: a light glyph (two greys: face and its lower/right shade) with a
1-px dark outline so it reads on the button tile and in the hand; colour only where it means something
(gold = money and prizes, red = up / buy, blue = down / sell, Korean stock colours), and a dim grey for
switched-off buttons. Glyph inside 1..14 so the outline fits.
"""

from PIL import Image

COLOURS = {
    "#": (232, 228, 218), "s": (168, 163, 152),            # light face, its shade
    "g": (242, 194, 76), "G": (184, 132, 42),              # gold
    "r": (240, 76, 76), "R": (168, 44, 44),                # red (up, buy)
    "b": (74, 144, 250), "B": (40, 92, 178),               # blue (down, sell)
    "w": (120, 196, 240), "W": (62, 128, 186),             # water
    "d": (108, 113, 124), "D": (78, 82, 92),               # disabled
    "k": (34, 37, 44),                                     # dark detail inside a light shape
}
OUTLINE = (16, 18, 22)


def _pad(rows):
    rows = [r.ljust(16, ".")[:16] for r in rows]
    return rows + ["." * 16] * (16 - len(rows))


def draw(rows, recolour=None):
    rows = _pad(rows)
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    filled = set()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                filled.add((x, y))
    for (x, y) in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in filled and 0 <= q[0] < 16 and 0 <= q[1] < 16:
                im.putpixel(q, OUTLINE + (255,))
    for (x, y) in filled:
        ch = rows[y][x]
        if recolour:
            ch = recolour.get(ch, ch)
        im.putpixel((x, y), COLOURS[ch] + (255,))
    return im


# ------------------------------------------------------------------------------------------------
# Shapes
# ------------------------------------------------------------------------------------------------

CHEVRON_LEFT = [
    "",
    "",
    ".........###",
    "........###s",
    ".......###s",
    "......###s",
    ".....###s",
    "....###s",
    "....###s",
    ".....###s",
    "......###s",
    ".......###s",
    "........###s",
    ".........###",
]

BACK = [
    "",
    "",
    "....#",
    "...##",
    "..###",
    ".############",
    ".#############",
    "..###.......##",
    "...##.......#s",
    "....#.......#s",
    "......#######s",
    "......sssssss",
]

CLOSE = [
    "",
    "",
    "...##......##",
    "...###....###s",
    "....###..###s",
    ".....######s",
    "......####s",
    "......####s",
    ".....######s",
    "....###s.###s",
    "...###s...###s",
    "...##s.....##s",
]

INFO = [
    "",
    ".....######",
    "...##ssssss##",
    "..#s.......s#",
    "..#....##...#s",
    ".#.....##....#s",
    ".#...........#s",
    ".#....###....#s",
    ".#.....##....#s",
    ".#.....##....#s",
    "..#....##...#s",
    "..#...####..#s",
    "...##......##s",
    ".....######ss",
]

CLEAR = [   # take off: a ring with a slash
    "",
    ".....######",
    "...##ssssss##",
    "..#s.......###",
    "..#.......###s",
    ".#.......###.#s",
    ".#......###..#s",
    ".#.....###...#s",
    ".#....###....#s",
    ".#...###.....#s",
    "..#.###.....#s",
    "..####.....#s",
    "...##......##s",
    ".....######ss",
]

HANGER = [
    "",
    ".......##",
    "......#..#",
    "..........#",
    ".........#",
    "........##",
    ".......#s.#",
    "......#s...#",
    ".....#s.....#",
    "....#s.......#",
    "...#s.........#",
    "..##############",
    "...ssssssssssss",
]

COINS = [
    "",
    "",
    "...gggg",
    "..gggggg",
    "..GGGGGG",
    "..gggggg",
    "..GGGGGG..gggg",
    "..gggggg.gggggg",
    "..GGGGGG.GGGGGG",
    "..gggggg.gggggg",
    "..GGGGGG.GGGGGG",
    "..gggggg.gggggg",
    "..GGGGGG.GGGGGG",
]

CHART = [
    "",
    ".#",
    ".#.........gg",
    ".#........gG",
    ".#..g....gG",
    ".#.gGg..gG",
    ".#gG.GggG",
    ".#G...GG",
    ".#",
    ".#",
    ".#",
    ".#",
    ".############",
    "..sssssssssss",
]

LEDGER = [   # news: a page of lines
    "",
    "...#########",
    "...#########s",
    "...#kkkkk###s",
    "...#########s",
    "...#kkkkkkk#s",
    "...#########s",
    "...#kkkkkkk#s",
    "...#########s",
    "...#kkkk####s",
    "...#########s",
    "...#########s",
    "....sssssssss",
]

BAG = [   # my holdings: a pouch with a gold tie
    "",
    "......##",
    ".....#ss#",
    "......gg",
    ".....gGGg",
    "....######",
    "...########",
    "..##########",
    "..#####gg###s",
    "..####gGGg##s",
    "..#####gg###s",
    "...########s",
    "....sssssss",
]

CART = [   # the shop's 구매 side: a cart with red goods in it
    "",
    "##",
    ".#...rrr.rr",
    ".#..rrRrrRRr",
    ".##############",
    "..#k#k#k#k#k#s",
    "..###########s",
    "...#k#k#k#k#s",
    "...#########s",
    "....#",
    "....##########",
    ".....sssssssss",
    ".....##....##",
    ".....ss....ss",
]

CHEST = [   # the vanity shop: a gift box
    "",
    ".....g...g",
    "......g.g",
    "...#######g###",
    "...######gG##s",
    "...######gG##s",
    "....#####gG#s",
    "....#####gG#s",
    "....#####gG#s",
    "....#####gG#s",
    "....#####gG#s",
    ".....ssssGGss",
]

CROWN = [
    "",
    "",
    "",
    "...g...g...g",
    "...gg.ggg.gg",
    "...ggggggggg",
    "...gggrgggbgG",
    "...gggggggggG",
    "...gggggggggG",
    "...GGGGGGGGGG",
]

HAMMER = [
    "",
    "",
    "",
    "",
    ".#############",
    "...###########s",
    "......######s",
    "......######s",
    ".....########s",
    "....##########s",
    "....##########s",
    ".....ssssssssss",
]

def _refresh():
    """A ring with a gap at the top right and an arrowhead: turn round, refresh."""
    import math
    rows = [["."] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            r = math.hypot(dx, dy)
            a = math.degrees(math.atan2(-dy, dx)) % 360   # 0 = east, 90 = north
            if 4.0 <= r < 6.2 and not (20 <= a <= 75):
                rows[y][x] = "s" if (dx > 0 and dy > 0 and r >= 5.1) else "#"
    # arrowhead on the right-hand end of the gap, pointing up along the ring (turning anticlockwise)
    for (x, y) in ((12, 2), (11, 3), (12, 3), (13, 3), (10, 4), (11, 4), (12, 4), (13, 4), (14, 4)):
        rows[y][x] = "#"
    return ["".join(r) for r in rows]


REFRESH = _refresh()

TROPHY = [
    "",
    "",
    "...gggggggggg",
    ".gggggggggggggg",
    ".g.ggggggggg.gG",
    ".gg.gggggggg.gG",
    "..gggggggggggG",
    "....ggggggggG",
    "......ggggG",
    ".......ggG",
    ".......ggG",
    ".....gggggg",
    "....ggggggggG",
    "....GGGGGGGGG",
]

QUESTION = [
    "",
    ".....######",
    "....##ssss##",
    "....##s...##s",
    "..........##s",
    ".........##s",
    "........##s",
    ".......##s",
    ".......##s",
    "........ss",
    ".......##",
    ".......##s",
    "........ss",
]

WAVE = [
    "",
    "",
    "",
    "..ww......ww",
    ".wWWw....wWWw",
    "wW..Ww..wW..Ww",
    ".....wwww....W",
    "",
    "..ww......ww",
    ".wWWw....wWWw",
    "wW..Ww..wW..Ww",
    ".....wwww....W",
]


def _arrow(up):
    rows = [
        "",
        ".......#",
        "......###",
        ".....#####",
        "....#######",
        "...#########",
        "......###s",
        "......###s",
        "......sss",
    ]
    if not up:
        rows = ["", "......###", "......###s", "......###s", "...#########", "....#######s", ".....#####s", "......###s", ".......#s"]
    return rows


DIGITS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["##.", "..#", ".#.", "#..", "###"],
    "3": ["##.", "..#", ".#.", "..#", "##."],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "7": ["###", "..#", ".#.", ".#.", ".#."],
}


def _with_digits(rows, text, y=10):
    rows = [list(r) for r in _pad(rows)]
    w = len(text) * 4 - 1
    x0 = (16 - w) // 2
    for i, ch in enumerate(text):
        for yy, line in enumerate(DIGITS[ch]):
            for xx, c in enumerate(line):
                if c == "#":
                    rows[y + yy][x0 + i * 4 + xx] = "#"
    return ["".join(r) for r in rows]


def trade(buy, amount):
    """Buy: a red up arrow; sell: a blue down arrow; the amount below in white."""
    colour = {"#": "r", "s": "R"} if buy else {"#": "b", "s": "B"}
    arrow = draw(_arrow(buy), colour)
    digits = draw(_with_digits([""] * 16, str(amount)))
    arrow.alpha_composite(digits)
    return arrow


SELL_ALL = [
    "",
    "",
    "...##......##",
    "....##....##s",
    ".....##..##s",
    "......####s",
    "...##..##s.##",
    "....##....##s",
    ".....##..##s",
    "......####s",
    ".......##s",
    "",
    "...##########",
    "....ssssssssss",
]


def period(label):
    """Chart period: a small screen with three candles (red up, blue down), the length below."""
    screen = [
        "",
        ".############",
        ".#kkkkkkkkkk#s",
        ".#kkkkkkkkkk#s",
        ".#kkkkkkkkkk#s",
        ".#kkkkkkkkkk#s",
        ".#kkkkkkkkkk#s",
        ".############s",
        "..ssssssssssss",
    ]
    im = draw(screen)
    for (x, top, bot, up) in ((3, 4, 6, False), (6, 3, 5, True), (9, 2, 4, True)):
        c = COLOURS["r"] if up else COLOURS["b"]
        for y in range(top, bot + 1):
            for xx in range(x, x + 2):
                im.putpixel((xx + 1, y), c + (255,))
    im.alpha_composite(draw(_with_digits([""] * 16, label)))
    return im


TRIANGLE_LEFT = [   # switch a group (codex habitats): a solid triangle, different from the page chevrons
    "",
    "",
    "",
    "........ww",
    "......wwww",
    "....wwwwwW",
    "..wwwwwwwW",
    "..wwwwwwwW",
    "....wwwwwW",
    "......wwwW",
    "........wW",
]


def _big_arrow(up):
    """A plain arrow (sort by rising / falling), centred."""
    rows = _arrow(up)
    return [""] * 3 + rows[1:]


def icons():
    """name -> image, every icon of the set."""
    left = draw(CHEVRON_LEFT)
    out = {
        "prev": left,
        "next": left.transpose(Image.FLIP_LEFT_RIGHT),
        "prev_off": draw(CHEVRON_LEFT, {"#": "d", "s": "D"}),
        "next_off": draw(CHEVRON_LEFT, {"#": "d", "s": "D"}).transpose(Image.FLIP_LEFT_RIGHT),
        "back": draw(BACK),
        "exit": draw(CLOSE),
        "info": draw(INFO),
        "take_off": draw(HANGER),
        "clear": draw(CLEAR),
        "coins": draw(COINS),
        "chart": draw(CHART),
        "ledger": draw(LEDGER),
        "bag": draw(BAG),
        "cart": draw(CART),
        "chest": draw(CHEST),
        "crown": draw(CROWN),
        "hammer": draw(HAMMER),
        "refresh": draw(REFRESH),
        "trophy": draw(TROPHY),
        "unknown": draw(QUESTION),
        "wave": draw(WAVE),
        "sell_all": draw(SELL_ALL, {"#": "b", "s": "B"}),
        "arrow_left": draw(TRIANGLE_LEFT),
        "arrow_right": draw(TRIANGLE_LEFT).transpose(Image.FLIP_LEFT_RIGHT),
        "rise": draw(_big_arrow(True), {"#": "r", "s": "R"}),
        "fall": draw(_big_arrow(False), {"#": "b", "s": "B"}),
    }
    for n in (1, 10, 100):
        out[f"buy_{n}"] = trade(True, n)
        out[f"sell_{n}"] = trade(False, n)
    for p in ("24", "7", "30"):
        out[f"period_{p}"] = period(p)
    return out
