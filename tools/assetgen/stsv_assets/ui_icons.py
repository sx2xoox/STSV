"""
Menu button icons (16 x 16) for the charcoal menus: one set for the core (stsv:gui/*) and the economy
(economy:gui/*). Same rules for all: a light glyph (two greys: face and its lower/right shade) with a
1-px dark outline so it reads on the button tile and in the hand; colour only where it means something
(gold = money and prizes, red = up / buy, blue = down / sell, Korean stock colours), and a dim grey for
switched-off buttons. Glyph inside 1..14 so the outline fits (these rules are for the icons drawn here).

Most icons are drawings picked from PixelLab instead (ui_art/<name>.png, 16 x 16, chosen by eye): the
back arrow, pouch, cart, gift box, coins, crown, question mark, wave, trophy, rising chart, the red rise
arrow and the core's page chevron, close, info, hanger and clear. The next page is the chevron mirrored and the
switched-off pages are the chevrons dimmed to grey; the blue fall arrow is the rise arrow turned over
and recoloured, and the n-share buy / sell buttons are the two arrows with the amount on top (the
arrow sits on column 7, the axis the digits are centred on).
"""

import colorsys
from pathlib import Path

from PIL import Image

ART = Path(__file__).resolve().parent / "ui_art"
PICKED = ("back", "bag", "cart", "chest", "coins", "crown", "unknown", "wave", "trophy", "rise",
          "prev", "exit", "info", "take_off", "clear", "chart")


def _art(name):
    return Image.open(ART / f"{name}.png").convert("RGBA")


def _dimmed(im):
    """A switched-off button: the picture in a dark grey of its own brightness."""
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a:
                v = int((0.3 * r + 0.59 * g + 0.11 * b) * 0.55)
                px[x, y] = (v, v, v + 6, a)
    return out


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
    # units on the chart period buttons: 10m, 24h, 7d
    "m": ["...", "##.", "###", "#.#", "#.#"],
    "h": ["#..", "#..", "##.", "#.#", "#.#"],
    "d": ["..#", "..#", ".##", "#.#", ".##"],
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
    out = {
        "ledger": draw(LEDGER),
        "hammer": draw(HAMMER),
        "refresh": draw(REFRESH),
        "sell_all": draw(SELL_ALL, {"#": "b", "s": "B"}),
        "arrow_left": draw(TRIANGLE_LEFT),
        "arrow_right": draw(TRIANGLE_LEFT).transpose(Image.FLIP_LEFT_RIGHT),
    }
    for name in PICKED:
        out[name] = _art(name)
    out["next"] = out["prev"].transpose(Image.FLIP_LEFT_RIGHT)
    out["prev_off"] = _dimmed(out["prev"])
    out["next_off"] = _dimmed(out["next"])
    out["fall"] = _turned_blue(out["rise"])
    for n in (1, 10, 100):
        for key, arrow in ((f"buy_{n}", out["rise"]), (f"sell_{n}", out["fall"])):
            im = arrow.copy()
            im.alpha_composite(draw(_with_digits([""] * 16, str(n))))
            out[key] = im
    for key, label in (("10", "10m"), ("24", "24h"), ("7", "7d")):
        out[f"period_{key}"] = period(label)
    out.update(hub_icons())
    return out


# ------------------------------------------------------------------------------------------------
# 통합 메뉴 (hub) buttons: one glyph per feature, drawn here in the same two-grey + outline look.
# Colour only where it means something: gold = money, red = the roof / the claim flag / the seal,
# green = nature (the wild world, done), blue = the codex's cover.
# ------------------------------------------------------------------------------------------------

COLOURS.update({
    "e": (110, 196, 92), "E": (58, 132, 58),               # green (nature, done)
    "o": (170, 118, 70), "O": (112, 74, 44),               # wood (handles, trunks, poles)
})

HOUSE = [   # 건축월드: a house, red roof, a window and a door
    "",
    ".......rr",
    "......rrrr",
    ".....rrrrrr",
    "....rrrrrrrr",
    "...rrrrrrrrrr",
    "..rrrrrrrrrrrr",
    ".RRRRRRRRRRRRRR",
    "...##########s",
    "...##########s",
    "...#ww###kkk#s",
    "...#ww###kkk#s",
    "...######kkk#s",
    "...######kkk#s",
    "...######kkk#s",
    "...ssssssssss",
]

PINE = [    # 야생월드: a pine tree on a little ground
    "",
    ".......ee",
    "......eeee",
    ".....eeeeEE",
    "......eeEE",
    ".....eeeeeE",
    "....eeeeeeEE",
    ".....eeeeEE",
    "....eeeeeeeE",
    "...eeeeeeeeEE",
    "..eeeeeeeeeeEE",
    ".......oO",
    ".......oO",
    "...ssssssssss",
]

TAG = [     # 칭호: a name tag with its string
    "",
    "",
    "..ss",
    "...s",
    "....s",
    ".....##########",
    "....###########s",
    "...##k#########s",
    "...##k##kkkkk##s",
    "....###########s",
    ".....##########s",
    "......ssssssssss",
]

PODIUM = [  # 순위: the winner's block in the middle with a gold star, lower blocks either side
    "",
    ".......g",
    "......ggg",
    ".......G",
    ".....######",
    ".....###k##s",
    ".....##kk##s",
    ".....###k##s",
    ".###s###k##s",
    ".###s##kkk#s",
    ".###s######s##s",
    ".###s######s##s",
    ".###s######s##s",
    ".###s######s##s",
    "..sssssssssssss",
]

SEND = [    # 송금: a gold coin flying right
    "",
    "",
    "",
    "....gggg",
    "...gggggG",
    "..ggGGGGgG......",
    "..ggGggGgG..#",
    "..ggGggGgG..##",
    "..ggGGGGgG#####",
    "..gggggggG..##s",
    "...gGGGGG...#s",
    "....GGGG",
]

TRADE = [   # 직거래: goods one way (light), money the other (gold)
    "",
    "",
    "..........#",
    "..........##",
    "..##########",
    "..###########",
    "..##########s",
    "..........##s",
    "..........#s",
    "....g",
    "...gg",
    "..gggggggggg",
    ".ggggggggggg",
    "..gggggggggG",
    "...ggGGGGGGG",
    "....G",
]

MAIL = [    # 우편함: an envelope with a red seal
    "",
    "",
    "",
    ".##############",
    ".#s##########s#s",
    ".##s########s##s",
    ".###s######s###s",
    ".####s####s####s",
    ".#####srrs#####s",
    ".######rr######s",
    ".##############s",
    ".##############s",
    ".##############s",
    "..sssssssssssss",
]

PICKAXE = [  # 직업: a pickaxe (head light, wooden handle)
    "",
    "....######",
    "..##########",
    ".###ssssss###",
    ".##s..oO..s##",
    ".#s...oO...s#",
    ".#....oO....#",
    "......oO",
    "......oO",
    "......oO",
    "......oO",
    "......oO",
    "......oO",
    "......oO",
]

CHECKLIST = [   # 미션: a page with ticked boxes
    "",
    "..###########",
    "..###########s",
    "..#e#########s",
    "..ee#kkkkkk##s",
    "..#e#########s",
    "..###########s",
    "..#e#########s",
    "..ee#kkkkk###s",
    "..#e#########s",
    "..###########s",
    "..#k#########s",
    "..###kkkkkk##s",
    "..###########s",
    "...sssssssssss",
]

SCROLL = [  # 의뢰: a rolled notice
    "",
    "..ssssssssssss",
    ".s############s",
    "..ssssssssssss",
    "...##########s",
    "...#kkkkkkk##s",
    "...##########s",
    "...#kkkkk####s",
    "...##########s",
    "...#kkkkkkk##s",
    "...##########s",
    "..ssssssssssss",
    ".s############s",
    "..ssssssssssss",
]

BOOK = [    # 도감: a closed book, blue cover, gold emblem, pages on the right
    "",
    "..bbbbbbbbbb",
    "..bbbbbbbbbb#",
    "..bbbbbbbbbB#s",
    "..bbbbggbbbB#s",
    "..bbbggggbbB#s",
    "..bbbggggbbB#s",
    "..bbbbggbbbB#s",
    "..bbbbbbbbbB#s",
    "..bbbbbbbbbB#s",
    "..bbbbbbbbbB#s",
    "..bbbbbbbbbB#s",
    "..BBBBBBBBBB#s",
    "...##########s",
    "....sssssssss",
]

FLAG = [    # 땅: a red claim flag on a pole
    "",
    "...o",
    "...orrrrrr",
    "...orrrrrrrrr",
    "...orrrrrrrrrR",
    "...orrrrrrrrR",
    "...orrrrrrrR",
    "...oRRRRRR",
    "...o",
    "...o",
    "...o",
    "...o",
    "..###",
    ".#####s",
    "..sssss",
]

ALERT = [   # 사건: an exclamation mark in a gold sign
    "",
    ".......gg",
    "......gggg",
    "......gkkg",
    ".....ggkkgg",
    ".....ggkkgg",
    "....gggkkggg",
    "....gggkkggg",
    "...ggggkkgggg",
    "...gggggggggg",
    "..ggggggggggggG",
    "..gggggkkggggGG",
    ".ggggggggggggGG",
    ".GGGGGGGGGGGGGG",
]

MONUMENT = [    # 기념비: an obelisk on a plinth, a gold tip
    "",
    ".......g",
    "......ggG",
    "......##s",
    "......##s",
    "......##s",
    ".....###s",
    ".....###s",
    ".....####s",
    ".....####s",
    ".....####s",
    "....######s",
    "...########s",
    "..##########s",
    "...ssssssssss",
]


def _candles():
    """주식: a chart screen with four candles (red up, blue down)."""
    screen = [
        "",
        "",
        ".##############",
        ".#kkkkkkkkkkkk#s",
        ".#kkkkkkkkkkkk#s",
        ".#kkkkkkkkkkkk#s",
        ".#kkkkkkkkkkkk#s",
        ".#kkkkkkkkkkkk#s",
        ".#kkkkkkkkkkkk#s",
        ".#kkkkkkkkkkkk#s",
        ".#kkkkkkkkkkkk#s",
        ".#kkkkkkkkkkkk#s",
        ".##############s",
        "..sssssssssssss",
    ]
    im = draw(screen)
    for (x, top, bot, up) in ((3, 8, 10, False), (6, 6, 9, True), (9, 7, 9, False), (12, 4, 7, True)):
        c = COLOURS["r"] if up else COLOURS["b"]
        for y in range(top, bot + 1):
            for xx in range(x - 1, x + 1):
                im.putpixel((xx, y), c + (255,))
        for y in (top - 1, bot + 1):
            im.putpixel((x - 1 if up else x, y), c + (255,))
    return im


def hub_icons():
    return {"house": draw(HOUSE), "pine": draw(PINE), "tag": draw(TAG), "podium": draw(PODIUM), "send": draw(SEND),
            "trade": draw(TRADE), "mail": draw(MAIL), "pickaxe": draw(PICKAXE), "checklist": draw(CHECKLIST),
            "scroll": draw(SCROLL), "book": draw(BOOK), "flag": draw(FLAG), "alert": draw(ALERT), "monument": draw(MONUMENT),
            "candles": _candles()}
