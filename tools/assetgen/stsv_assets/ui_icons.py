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
    out.update(price_icons())
    out.update(wardrobe_icons())
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


COLOURS.update({
    "p": (72, 196, 176), "P": (30, 116, 108),              # teal (ender pearl: teleport)
})


def _pearl():
    """텔레포트 요청: an ender pearl, teal with a glint at the top left, a dark core and a shaded bottom right."""
    rows = []
    for y in range(16):
        row = ""
        for x in range(16):
            dx, dy = x - 7, y - 8
            if dx * dx + dy * dy > 30:
                row += "."
            elif x in (4, 5) and y in (5, 6):
                row += "#"
            elif dx + dy >= 5 or dx * dx + dy * dy > 20 and dx + dy >= 2 or (x - 7.5) ** 2 + (y - 8.5) ** 2 <= 2.5:
                row += "P"
            else:
                row += "p"
        rows.append(row)
    return draw(rows)


COLOURS.update({
    "c": (236, 84, 108), "C": (164, 46, 70),               # capsule red (치장 뽑기)
})


def _gacha():
    """치장 뽑기: a capsule toy, red top with a glint, a dark seam, a light bottom shaded to the lower right, and a
    sparkle at the top right."""
    rows = []
    for y in range(16):
        row = ""
        for x in range(16):
            dx, dy = x - 7, y - 8.5
            if dx * dx + dy * dy > 33:
                row += "."
            elif y == 8:
                row += "k"
            elif x in (4, 5) and y in (4, 5):
                row += "#"
            elif y < 8:
                row += "C" if dx + dy >= 2.5 or dx * dx + dy * dy > 24 and dx > 0 else "c"
            else:
                row += "s" if dx + dy >= 5 or dx * dx + dy * dy > 22 and dx + dy > 1 else "#"
        rows.append(row)
    rows[0] = rows[0][:13] + "g" + rows[0][14:]
    rows[1] = rows[1][:12] + "ggg" + rows[1][15:]
    rows[2] = rows[2][:13] + "g" + rows[2][14:]
    return draw(rows)


def hub_icons():
    return {"gacha": _gacha(), "pearl": _pearl(), "house": draw(HOUSE), "pine": draw(PINE), "tag": draw(TAG), "podium": draw(PODIUM), "send": draw(SEND),
            "trade": draw(TRADE), "mail": draw(MAIL), "pickaxe": draw(PICKAXE), "checklist": draw(CHECKLIST),
            "scroll": draw(SCROLL), "book": draw(BOOK), "flag": draw(FLAG), "alert": draw(ALERT), "monument": draw(MONUMENT),
            "candles": _candles()}


# ------------------------------------------------------------------------------------------------
# The hub's 치장 and 야간 투시 buttons, and the price picker of 판매 등록 / 경매 등록 / 입찰 (economy)
# ------------------------------------------------------------------------------------------------

DIGITS.update({
    "5": ["###", "#..", "##.", "..#", "##."],
    "k": ["#..", "#.#", "##.", "#.#", "#.#"],
    "%": ["#.#", "..#", ".#.", "#..", "#.#"],
})

DRESS_UP = [    # 치장: a mannequin in a blue coat wearing a red top hat (the wardrobe's hanger means "take off")
    "",
    "......rrrr",
    "......rrrrR",
    "......rrrrR",
    "....rrrrrrrrR",
    ".......##s",
    "......####s",
    ".......##s",
    "...bbbbbbbbbB",
    "...bbbbgbbbbB",
    "....bbbbbbbB",
    ".....bbgbbB",
    "......bbbB",
    ".......oO",
    ".......oO",
    "....ssssssss",
]


def _night_vision():
    """야간 투시: a light crescent moon with a gold star beside it."""
    rows = []
    for y in range(16):
        row = ""
        for x in range(16):
            dx, dy = x + 0.5 - 7, y + 0.5 - 9
            ox, oy = x + 0.5 - 9.6, y + 0.5 - 6.6
            if dx * dx + dy * dy <= 5.6 ** 2 and ox * ox + oy * oy > 4.4 ** 2:
                row += "s" if dx + dy > 3.5 else "#"
            else:
                row += "."
        rows.append(row)
    rows = [list(r) for r in rows]
    for (x, y) in ((12, 1), (11, 2), (12, 2), (13, 2), (12, 3)):
        rows[y][x] = "g"
    return draw(["".join(r) for r in rows])


COIN = [".gggg.", "gggggG", "ggGGgG", "gggggG", ".GGGG."]
INGOT = ["..gggggg", ".gggggggG", "gggggggGG", "GGGGGGGG"]
BLOCK = ["ggggggggg", "gHHHHHHgG", "gHgggggGG", "gHgggggGG", "gHgggggGG", "gGGGGGGGG", "GGGGGGGGG"]


def _stamp(rows, shape, x0, y0):
    for yy, line in enumerate(shape):
        for xx, c in enumerate(line):
            if c != ".":
                rows[y0 + yy][x0 + xx] = c


def _step(label, shapes):
    """A price step button: gold (more coins, an ingot, a block for bigger steps) over its amount."""
    rows = [list("." * 16) for _ in range(16)]
    for shape, x, y in shapes:
        _stamp(rows, shape, x, y)
    rows = _with_digits(["".join(r) for r in rows], label)
    return draw(rows)


PRICE_TAG = [   # 판매 등록: a gold price tag on its string
    "",
    "",
    "..ss",
    "...s",
    "....s",
    ".....gggggggggg",
    "....ggggggggggG",
    "...ggkggggGGggG",
    "...ggkgggGggGgG",
    "....gggggggGGgG",
    ".....ggggggggggG",
    "......GGGGGGGGGG",
]

PICK = [        # 팔 물건 고르기: an arrow down into a tray
    "",
    "......##",
    "......##s",
    "......##s",
    "......##s",
    "...########",
    "....######s",
    ".....####s",
    "......##s",
    "",
    "..#.........#",
    "..#.........#s",
    "..###########s",
    "...sssssssssss",
]

TYPE = [        # 직접 입력: a pencil writing on a line
    "",
    "...........rr",
    "..........rrrR",
    ".........ssrR",
    "........ggssR",
    ".......ggggG",
    "......ggggG",
    ".....ggggG",
    "....ggggG",
    "....ooG",
    "...ooO",
    "...kO",
    "",
    ".############",
    "..ssssssssssss",
]

CONFIRM = [     # 확인: a green tick
    "",
    "",
    "",
    "............ee",
    "...........eeE",
    "..........eeE",
    ".........eeE",
    "..ee....eeE",
    "..eee..eeE",
    "...eeeeeE",
    "....eeeE",
    ".....eE",
]

CANCEL = [      # 경매 취소: a red cross
    "",
    "",
    "",
    "...rr.....rr",
    "...rrr...rrrR",
    "....rrr.rrrR",
    ".....rrrrrR",
    "......rrrR",
    ".....rrrrrR",
    "....rrrRrrrR",
    "...rrrR.RrrrR",
    "...rrR...RrrR",
    "....R.....R",
]

GAVEL = [       # 최소 입찰: an auction gavel (wooden head, handle) over its block
    "",
    "....oooo",
    "...oooooO",
    "...ooOooO.",
    "...oooooOo",
    "....ooOOoooo",
    ".........oooo",
    "..........oooo",
    "...........oooO",
    "............ooO",
    "",
    "..##########",
    ".############s",
    "..sssssssssss",
]


def _search():
    """검색: a magnifying glass, a light rim round blue glass, a wooden handle."""
    import math
    rows = [["."] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            r = math.hypot(x + 0.5 - 6.5, y + 0.5 - 6.5)
            if r <= 3.4:
                rows[y][x] = "w" if (x + y) % 7 else "#"
            elif r <= 5.0:
                rows[y][x] = "s" if x + y > 14 else "#"
    for k in range(4):
        for (dx, dy) in ((0, 0), (1, 0), (0, 1)):
            x, y = 10 + k + dx, 10 + k + dy
            if x < 15 and y < 15:
                rows[y][x] = "o" if dx == 0 and dy == 0 else "O"
    return ["".join(r) for r in rows]


def price_icons():
    out = {"dress_up": draw(DRESS_UP), "night_vision": _night_vision(), "price_tag": draw(PRICE_TAG), "pick": draw(PICK),
           "type": draw(TYPE), "confirm": draw(CONFIRM), "cancel": draw(CANCEL), "search": draw(_search()), "bid_min": draw(GAVEL)}
    out["confirm_off"] = _dimmed(out["confirm"])
    out["step_1"] = _step("1", [(COIN, 5, 2)])
    out["step_10"] = _step("10", [(COIN, 2, 3), (COIN, 8, 2)])
    out["step_100"] = _step("100", [(COIN, 2, 3), (COIN, 8, 3), (COIN, 5, 0)])
    out["step_1k"] = _step("1k", [(INGOT, 3, 3)])
    out["step_10k"] = _step("10k", [(BLOCK, 3, 0)])
    rise = _art("rise")
    for n in (5, 10):
        im = rise.copy()
        im.alpha_composite(draw(_with_digits([""] * 16, f"{n}%")))
        out[f"bid_{n}"] = im
    return out


# ------------------------------------------------------------------------------------------------
# The cosmetics' category tabs (옷장 /치장 and 치장 뽑기 /뽑기), the 보기 filter, and the not-owned card.
# Same two greys + outline; colour only for the cosmetic itself (gold halo, yellow chick, orange tail,
# red hat band, gold burst).
# ------------------------------------------------------------------------------------------------

COLOURS.update({
    "y": (250, 218, 92), "Y": (196, 156, 48),              # chick yellow
    "f": (242, 146, 62), "F": (176, 92, 36),               # fox orange
})

TAB_ALL = [     # 전체: four tiles
    "",
    "",
    "..#####.#####",
    "..####s.####s",
    "..####s.####s",
    "..####s.####s",
    "..sssss.sssss",
    "",
    "..#####.#####",
    "..####s.####s",
    "..####s.####s",
    "..####s.####s",
    "..sssss.sssss",
]

TAB_HEAD = [    # 머리: a hat with a red band and a wide brim
    "",
    "",
    "",
    ".....######",
    "....########",
    "....#######s",
    "....#######s",
    "....rrrrrrrR",
    "....rrrrrrrR",
    "..############",
    ".##############",
    "..sssssssssssss",
]

TAB_ABOVE = [   # 머리 위: a gold halo floating over a head
    "",
    "...gggggggggg",
    "..gG........gG",
    "...GGGGGGGGGG",
    "",
    ".....######",
    "....########",
    "....#######s",
    "....#k###k#s",
    "....#######s",
    "....#######s",
    ".....ssssss",
]

TAB_SHOULDER = [    # 어깨: a chick sitting on a shoulder, the head beside it
    "",
    "",
    "..####",
    ".#####s....yy",
    ".#####s...yyyyf",
    ".#####s...yykyF",
    "..ssss...yyyyyY",
    "...##....yyyyyY",
    ".........YYYYY",
    ".##############",
    ".#############s",
    ".#############s",
    "..sssssssssssss",
]

TAB_BACK = [    # 등: a pair of wings
    "",
    "",
    ".##..........##",
    ".###........###",
    ".####......####",
    ".#####....#####",
    ".s#####..#####s",
    "..ss####.####ss",
    "...ss###.###ss",
    "....ss##.##ss",
    ".....s##.##s",
    "......s#.#s",
]

def _tail():
    """꼬리: a bushy fox tail rising from the lower left and curling up to the right, white tip."""
    import math
    rows = [["."] * 16 for _ in range(16)]
    for k in range(200):
        t = k / 199
        # quadratic curve from the root (lower left) round the lower right to the tip (top)
        cx = (1 - t) ** 2 * 2.5 + 2 * (1 - t) * t * 13.0 + t * t * 10.5
        cy = (1 - t) ** 2 * 12.5 + 2 * (1 - t) * t * 13.5 + t * t * 2.5
        r = 0.7 + 2.3 * math.sin(math.pi * min(1.0, t * 1.15)) ** 0.7
        for y in range(16):
            for x in range(16):
                if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r:
                    rows[y][x] = "#" if t > 0.78 else "f"
    for y in range(16):          # shade the lower right edge
        for x in range(16):
            if rows[y][x] != "." and (x + 1 > 15 or rows[y][x + 1] == "." or y + 1 > 15 or rows[y + 1][x] == "."):
                if (x + 1 <= 15 and rows[y][x + 1] == ".") or (y + 1 <= 15 and rows[y + 1][x] == "."):
                    rows[y][x] = "s" if rows[y][x] == "#" else "F"
    return ["".join(r) for r in rows]

TAB_PET = [     # 펫: a paw print
    "",
    "",
    "....##...##",
    "...###s.###s",
    "...###s.###s",
    "....ss...ss",
    ".##.........##",
    "###s.......###s",
    "###s.#####.###s",
    ".ss.#######.ss",
    "...#########s",
    "...#########s",
    "....#######s",
    ".....sssss",
]

TAB_TRAIL = [   # 발자취: two shoe prints (sole and heel) walking up to the right
    "",
    "..........###",
    ".........#####",
    ".........####s",
    ".........####s",
    "..........sss",
    "...###...###",
    "..#####..##s",
    "..####s",
    "..####s",
    "...sss",
    "...###",
    "...##s",
]

TAB_KILL = [    # 처치 효과: a sword with a gold burst at its tip
    "",
    "..........g...g",
    "...........g.g",
    ".........gg.g.g",
    "...........g.g",
    ".........##g...g",
    "........##s",
    ".......##s",
    "......##s",
    "..o..##s",
    "...o##s",
    "...oOs",
    "..oO.o",
    ".oO",
]

FILTER = [      # 보기 (전부 / 가진 것 / 없는 것): a funnel
    "",
    "",
    "..###########",
    "..##########s",
    "...########s",
    "....######s",
    ".....####s",
    "......##s",
    "......##s",
    "......##s",
    "......##s",
    ".......s",
]

SELF_VIEW = [   # 내 화면에 표시: an eye (blue iris)
    "",
    "",
    "",
    "",
    ".....######",
    "...###sssss##",
    "..##s..www..s#",
    ".##...wwkkW..s#",
    "..##..wwkkW..##",
    "...##..WWW..##",
    ".....s#####s",
]

LOCKED_CARD = (12, 14, 18)      # the not-owned card's face (darker than the slot it sits in)


def locked_layers():
    """The not-owned cosmetic: a dark card (layer 0, drawn as is) and its rim and question mark in greys
    (layer 1, tinted by the item: the rarity's colour). Shaded so the tint keeps a light and a dark side."""
    card = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    mark = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(1, 15):
        for x in range(1, 15):
            if (x in (1, 14)) and (y in (1, 14)):
                continue
            card.putpixel((x, y), LOCKED_CARD + (255,))
            if x in (1, 14) or y in (1, 14) or (x in (2, 13) and y in (2, 13)):
                v = 120 if (x <= 2 or y <= 2) else 84     # rim: lit top-left, darker bottom-right
                mark.putpixel((x, y), (v, v, v, 255))
    qm = [
        "..####..",
        ".##..##.",
        ".##..##.",
        ".....##.",
        "....##..",
        "...##...",
        "...##...",
        "........",
        "...##...",
        "...##...",
    ]
    for dy, row in enumerate(qm):
        for dx, ch in enumerate(row):
            if ch == "#":
                x, y = 4 + dx, 3 + dy
                lit = row[dx + 1:dx + 2] == "#" or (dy + 1 < len(qm) and qm[dy + 1][dx] == "#")
                v = 255 if lit else 196
                mark.putpixel((x, y), (v, v, v, 255))
    return card, mark


def locked_preview(rgb):
    """The not-owned card as the game draws it with a rarity colour (menu previews)."""
    card, mark = locked_layers()
    out = card.copy()
    px, mp = out.load(), mark.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = mp[x, y]
            if a:
                px[x, y] = (r * rgb[0] // 255, g * rgb[1] // 255, b * rgb[2] // 255, 255)
    return out


def swatch_layers():
    """The nickname colour tile (/닉네임 색): a rounded square in light greys (layer 0, tinted by the item's first
    colour), its lower-right half again (layer 1, tinted by the second colour: a gradient's end, or the same colour)
    and a dark rim with a glint, drawn as is (layer 2). The greys keep a lit top-left and a darker bottom-right."""
    fill = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    half = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    rim = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    lo, hi = 2, 13
    for y in range(lo, hi + 1):
        for x in range(lo, hi + 1):
            if x in (lo, hi) and y in (lo, hi):
                continue
            if x in (lo, hi) or y in (lo, hi):
                rim.putpixel((x, y), (24, 26, 32, 200))
                continue
            v = 255 - int(40 * ((x - lo) + (y - lo)) / (2 * (hi - lo)))
            fill.putpixel((x, y), (v, v, v, 255))
            if x + y > lo + hi:
                half.putpixel((x, y), (v, v, v, 255))
    for x, y in ((4, 4), (5, 4), (4, 5)):
        rim.putpixel((x, y), (255, 255, 255, 150))
    return fill, half, rim


def wardrobe_icons():
    return {"tab_all": draw(TAB_ALL), "tab_head": draw(TAB_HEAD), "tab_above": draw(TAB_ABOVE),
            "tab_shoulder": draw(TAB_SHOULDER), "tab_back": draw(TAB_BACK), "tab_tail": draw(_tail()),
            "tab_pet": draw(TAB_PET), "tab_trail": draw(TAB_TRAIL), "tab_kill": draw(TAB_KILL), "filter": draw(FILTER),
            "self_view": draw(SELF_VIEW)}
