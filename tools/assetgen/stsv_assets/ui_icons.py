"""
Menu button icons (16 x 16) for the charcoal menus: one set for the core (stsv:gui/*) and the economy
(economy:gui/*). Same rules for all: a light glyph (two greys: face and its lower/right shade) with a
1-px dark outline so it reads on the button tile and in the hand; colour only where it means something
(gold = money and prizes, red = up / buy, blue = down / sell, Korean stock colours), and a dim grey for
switched-off buttons. Glyph inside 1..14 so the outline fits.

A few icons are drawings picked from PixelLab instead (ui_art/<name>.png, 16 x 16, chosen by eye): the
back arrow, pouch, cart, gift box, coins, crown, question mark, wave, trophy and the red rise arrow. The
blue fall arrow is that arrow turned over and recoloured, and the n-share buy / sell buttons are the two
arrows with the amount on top (the arrow sits on column 7, the axis the digits are centred on).
"""

import colorsys
from pathlib import Path

from PIL import Image

ART = Path(__file__).resolve().parent / "ui_art"
PICKED = ("back", "bag", "cart", "chest", "coins", "crown", "unknown", "wave", "trophy", "rise")


def _art(name):
    return Image.open(ART / f"{name}.png").convert("RGBA")


def _turned_blue(up):
    """The red up arrow as the blue down arrow: flipped, every coloured pixel's hue set to blue."""
    out = up.transpose(Image.FLIP_TOP_BOTTOM)
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a:
                h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
                if s > 0.25:
                    h = 0.6
                r2, g2, b2 = colorsys.hsv_to_rgb(h, s, v)
                px[x, y] = (int(r2 * 255), int(g2 * 255), int(b2 * 255), a)
    return out

COLOURS = {
    "#": (232, 228, 218), "s": (168, 163, 152),            # light face, its shade
    "g": (242, 194, 76), "G": (184, 132, 42),              # gold
    "r": (240, 76, 76), "R": (168, 44, 44),                # red (up, buy)
    "b": (74, 144, 250), "B": (40, 92, 178),               # blue (down, sell)
    "w": (120, 196, 240), "W": (62, 128, 186),             # water
    "d": (108, 113, 124), "D": (78, 82, 92),               # disabled
    "k": (34, 37, 44),                                     # dark detail inside a light shape
    "H": (255, 253, 246), "S": (122, 118, 110),            # the anvil's glint and underside
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

HAMMER = [   # an anvil: a lit top face, the horn's shadowed underside, a waist and a wide foot
    "",
    "",
    "",
    "",
    "..HHHHHHHHHHHH",
    ".H###########s",
    "...SSsssssssss",
    "......S#####s",
    ".......####s",
    "......######s",
    ".....########s",
    "....H#########s",
    "....SSSSSSSSSSS",
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


def icons():
    """name -> image, every icon of the set."""
    left = draw(CHEVRON_LEFT)
    out = {
        "prev": left,
        "next": left.transpose(Image.FLIP_LEFT_RIGHT),
        "prev_off": draw(CHEVRON_LEFT, {"#": "d", "s": "D"}),
        "next_off": draw(CHEVRON_LEFT, {"#": "d", "s": "D"}).transpose(Image.FLIP_LEFT_RIGHT),
        "exit": draw(CLOSE),
        "info": draw(INFO),
        "take_off": draw(HANGER),
        "clear": draw(CLEAR),
        "chart": draw(CHART),
        "ledger": draw(LEDGER),
        "hammer": draw(HAMMER),
        "refresh": draw(REFRESH),
        "sell_all": draw(SELL_ALL, {"#": "b", "s": "B"}),
        "arrow_left": draw(TRIANGLE_LEFT),
        "arrow_right": draw(TRIANGLE_LEFT).transpose(Image.FLIP_LEFT_RIGHT),
    }
    for name in PICKED:
        out[name] = _art(name)
    out["fall"] = _turned_blue(out["rise"])
    for n in (1, 10, 100):
        for key, arrow in ((f"buy_{n}", out["rise"]), (f"sell_{n}", out["fall"])):
            im = arrow.copy()
            im.alpha_composite(draw(_with_digits([""] * 16, str(n))))
            out[key] = im
    for p in ("24", "7", "30"):
        out[f"period_{p}"] = period(p)
    return out
