"""
편의점 알바 (docs/plans/편의점_기획안.md §15): the flat pictures the store's models wear at more than 16 px a block
(models/store.py puts them on as plates), and the vest's armour layer.

  button(key)              160 x 80: a POS key (a 12 x 6 px plate, 13 1/3 texels a px): a dark bezel, a green key with
                           its word in white (or a white key with the word in green for the "no / not now" keys),
                           Neo둥근모 bold at 2x, a lit top edge and a shadowed bottom one
  change_button(n)         160 x 80: the change keys: a white key, a stripe in the note's (or the coin's) colour down
                           its left, the amount in green (5만원 1만원 5천원 1천원 500원 100원)
  sign_face()              320 x 64: the GS26 lightbox (80 x 16 px at 4 texels a px). An original design: a deep green
                           field, a sun-yellow disc with a white leaf, "GS" in white and "26" in sun yellow in our own
                           rounded block letters, a white panel with "편의점" and "24시간"
  header(text, w, h, ...)  a shelf / fridge header strip: the word centred in Neo둥근모 (white on green, or lit)
  note(n)                  64 x 32 (a 7 x 3.5 px banknote at 8 texels a px, in the left 56 x 28): game money, not a
                           copy of a real note: the colour of the value (1000 blue, 5000 orange, 10000 green, 50000
                           gold), a frame, a hanok roof medallion, the value in digits
  card()                   64 x 32 (a 5.5 x 3.5 px card, 8 texels a px, in the left 44 x 28): a green card, a gold
                           chip, the contactless waves, a number row
  lottery()                48 x 64 (a 6 x 8 px scratch card, 8 texels a px): a bright ticket, three silver scratch
                           panels, "즉석복권"
  vest_layer()             64 x 32: the humanoid armour layer of store_vest (equipment economy:store_vest): the body
                           only (a vest: the arms stay bare), a V neck open to the shirt under it, a zip, a white
                           name tag on the chest, a white GS26 band on the back

  3차: license_face(tier) 80 x 104, id_card() 128 x 80, vest_layer_manager() / vest_layer_night() 64 x 32,
  manager_tag() 48 x 24, tablet_screen() 88 x 64 (see each function)

    python3 tools/assetgen/store_art.py out_dir     # writes the pictures there, 4x, to look at
"""

import math

from PIL import Image

from stsv_assets.palette import ramp

import gamble_art as GA


def _c(name, i):
    r, g, b = ramp(name, i)[:3]
    return (r, g, b, 255)


GREEN = "sign_green"


# ------------------------------------------------------------------------------------------------
# POS keys
# ------------------------------------------------------------------------------------------------

BUTTON_WORDS = {"card": "카드", "cash": "현금", "give": "건네기", "bag": "봉투", "nobag": "봉투없음", "warm": "데우기",
                "yes": "네", "no": "아니요", "wait": "잠시만요", "id": "신분증", "refuse": "거절", "sell": "판매",
                "wake": "깨우기", "point": "가리키기"}
# white keys with green words: the "no / not now" answers
LIGHT_KEYS = {"nobag", "no", "wait", "refuse"}

CHANGE = (50000, 10000, 5000, 1000, 500, 100)
CHANGE_WORDS = {50000: "5만원", 10000: "1만원", 5000: "5천원", 1000: "1천원", 500: "500원", 100: "100원"}
# the note / coin colours (store_cash_* and store_coin_* wear the same)
MONEY = {50000: ("gold", 2), 10000: ("bottle_green", 2), 5000: ("fox", 3), 1000: ("sapphire", 2),
         500: ("fish_silver", 3), 100: ("fish_silver", 2)}


def _key(W, H, face, face_hi, face_lo):
    """A rounded POS key: a dark bezel 4 texels wide, the key face with a lit top edge and a shadowed bottom."""
    im = Image.new("RGBA", (W, H), _c("iron", 1))
    px = im.load()
    B = 4
    for y in range(H):
        for x in range(W):
            edge = min(x, y, W - 1 - x, H - 1 - y)
            corner = min(x, W - 1 - x) < B + 3 and min(y, H - 1 - y) < B + 3 and \
                (min(x, W - 1 - x) - B) + (min(y, H - 1 - y) - B) < 3
            if edge < B or corner:
                px[x, y] = _c("iron", 1) if edge >= 1 else _c("iron", 0)
            elif y < B + 3:
                px[x, y] = face_hi
            elif y >= H - B - 4:
                px[x, y] = face_lo
            else:
                px[x, y] = face
    return im


# digits for the words (Neo둥근모 here holds only Hangul): 4 x 7, drawn 2x so they stand as tall as a syllable (14)
DIG7 = {
    "0": [".##.", "#..#", "#..#", "#..#", "#..#", "#..#", ".##."], "1": [".#..", "##..", ".#..", ".#..", ".#..", ".#..", "###."],
    "2": [".##.", "#..#", "...#", "..#.", ".#..", "#...", "####"], "3": ["###.", "...#", "...#", ".##.", "...#", "...#", "###."],
    "4": ["..#.", ".##.", "#.#.", "#.#.", "####", "..#.", "..#."], "5": ["####", "#...", "###.", "...#", "...#", "#..#", ".##."],
    "6": [".##.", "#...", "###.", "#..#", "#..#", "#..#", ".##."], "7": ["####", "...#", "..#.", "..#.", ".#..", ".#..", ".#.."],
    "8": [".##.", "#..#", "#..#", ".##.", "#..#", "#..#", ".##."], "9": [".##.", "#..#", "#..#", "#..#", ".###", "...#", ".##."],
}


def mask(text):
    """word_mask with digits: the Hangul from Neo둥근모 (bold), the digits from DIG7 at 2x, bottoms aligned."""
    parts = []
    run = ""
    for ch in text + "\0":
        if ch == " ":
            if run:
                parts.append(GA.word_mask(run))
                run = ""
            parts.append([[False] * 3 for _ in range(14)])
            continue
        if ch in DIG7 or ch == "\0":
            if run:
                parts.append(GA.word_mask(run))
                run = ""
            if ch in DIG7:
                parts.append(GA.scale_mask([[c == "#" for c in r] for r in DIG7[ch]], 2))
        else:
            run += ch
    H = max(len(p) for p in parts)
    rows = [[] for _ in range(H)]
    for k, p in enumerate(parts):
        off = H - len(p)
        for y in range(H):
            if k:
                rows[y] += [False, False]
            rows[y] += p[y - off] if y >= off else [False] * len(p[0])
    return rows


def _word(px, text, W, H, col, shadow, x0=0, scale=2):
    m = GA.scale_mask(mask(text), scale)
    mh, mw = len(m), len(m[0])
    if mw > W - x0 - 12:
        m = mask(text)
        mh, mw = len(m), len(m[0])
    ox = x0 + (W - x0 - mw) // 2
    oy = (H - mh) // 2
    for y, row in enumerate(m):
        for x, v in enumerate(row):
            if v:
                if shadow and not (y + 2 < mh and x + 2 < mw and m[y + 2][x + 2]):
                    px[ox + x + 2, oy + y + 2] = shadow if px[ox + x + 2, oy + y + 2] != col else col
    for y, row in enumerate(m):
        for x, v in enumerate(row):
            if v:
                px[ox + x, oy + y] = col


def button(key):
    W, H = 160, 80
    if key in LIGHT_KEYS:
        im = _key(W, H, _c("car_white", 3), _c("snow", 2), _c("car_white", 1))
        _word(im.load(), BUTTON_WORDS[key], W, H, _c(GREEN, 1), _c("car_white", 1))
    else:
        im = _key(W, H, _c(GREEN, 2), _c(GREEN, 3), _c(GREEN, 1))
        _word(im.load(), BUTTON_WORDS[key], W, H, _c("snow", 2), _c(GREEN, 0))
    return im


def change_button(n):
    W, H = 160, 80
    im = _key(W, H, _c("car_white", 3), _c("snow", 2), _c("car_white", 1))
    px = im.load()
    r, s = MONEY[n]
    for y in range(8, H - 8):
        for x in range(8, 26):
            px[x, y] = _c(r, s + (1 if y < 14 else -1 if y > H - 15 else 0))
    if n < 1000:                       # a coin: a round disc on the stripe
        for y in range(H):
            for x in range(W):
                d = ((x - 17) ** 2 + (y - 40) ** 2) ** 0.5
                if d < 7:
                    px[x, y] = _c("fish_silver", 4 if d < 3 else 3)
                elif d < 8.5:
                    px[x, y] = _c("fish_silver", 1)
    _word(px, CHANGE_WORDS[n], W, H, _c(GREEN, 1), _c("car_white", 1), x0=26)
    return im


def plate_back():
    """16 x 16: a key's sides and back: dark grey plastic."""
    im = Image.new("RGBA", (16, 16), _c("iron", 2))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if (x * 5 + y * 3) % 13 == 0:
                px[x, y] = _c("iron", 1)
    return im


# ------------------------------------------------------------------------------------------------
# The GS26 lightbox
# ------------------------------------------------------------------------------------------------

# our own rounded block letters, 6 x 9 (scaled 4x on the sign)
LETTERS = {
    "G": [".####.", "##..##", "##....", "##....", "##.###", "##..##", "##..##", "##..##", ".####."],
    "S": [".####.", "##..##", "##....", ".###..", "...##.", "....##", "....##", "##..##", ".####."],
    "2": [".####.", "##..##", "....##", "...##.", "..##..", ".##...", "##....", "##....", "######"],
    "6": ["..###.", ".##...", "##....", "#####.", "##..##", "##..##", "##..##", "##..##", ".####."],
}


def sign_face():
    W, H = 320, 64
    im = Image.new("RGBA", (W, H))
    px = im.load()
    split = 214                                    # green field | white panel
    for y in range(H):
        for x in range(W):
            if min(x, y, W - 1 - x, H - 1 - y) < 3:
                px[x, y] = _c("steel", 4) if y < H // 2 else _c("steel", 3)
            elif x < split - (y - 3) // 6:         # a slanted seam between the field and the panel
                # the lit field: brighter towards the middle, as a lightbox is
                t = abs(y - H / 2) / (H / 2)
                px[x, y] = _c(GREEN, 3) if t < 0.55 else _c(GREEN, 2)
            elif x < split + 4 - (y - 3) // 6:
                px[x, y] = _c("corn", 3)           # a sun-yellow stripe on the seam
            else:
                px[x, y] = _c("snow", 2) if (x + y) % 17 else _c("snow", 1)
    # the emblem: a sun-yellow disc with a white leaf on it
    cx, cy, R = 30, 32, 21
    for y in range(H):
        for x in range(W):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d < R:
                px[x, y] = _c("corn", 2) if d < R - 3 else _c("corn", 1)
                # the leaf: an almond tilted up to the right, lit on its upper half, its vein
                u, v = (x - cx) * 0.75 - (y - cy) * 0.66, (x - cx) * 0.66 + (y - cy) * 0.75
                if (abs(u) / 14) ** 1.6 + (abs(v) / 8) ** 1.6 < 1 - 0.25 * (u / 14) ** 2:
                    px[x, y] = _c(GREEN, 3) if v < 0 else _c(GREEN, 2)
                    if abs(v) < 0.8 and -12 < u < 12:
                        px[x, y] = _c("snow", 2)
            elif d < R + 2:
                px[x, y] = _c("snow", 2)
    # the stalk of the leaf
    for k in range(5):
        px[cx - 10 - k, cy + 9 + k] = _c(GREEN, 1)
        px[cx - 9 - k, cy + 9 + k] = _c(GREEN, 1)
    # GS (white) 26 (sun yellow), 4x, with a dark green drop shadow
    S = 4
    x = 62
    for ch in "GS26":
        rows = LETTERS[ch]
        col = _c("snow", 2) if ch in "GS" else _c("corn", 2)
        for j, row in enumerate(rows):
            for i, c in enumerate(row):
                if c != "#":
                    continue
                for a in range(S):
                    for b in range(S):
                        X, Y = x + i * S + a, 14 + j * S + b
                        if px[X + 3, Y + 3] not in (_c("snow", 2), _c("corn", 2)):
                            px[X + 3, Y + 3] = _c(GREEN, 0)
        for j, row in enumerate(rows):
            for i, c in enumerate(row):
                if c != "#":
                    continue
                for a in range(S):
                    for b in range(S):
                        px[x + i * S + a, 14 + j * S + b] = col
        x += 6 * S + (5 if ch == "S" else 3)
    # the panel: 편의점 in green, 24시간 in orange under it
    m = GA.scale_mask(GA.word_mask("편의점"), 2)
    ox, oy = 226 + (W - 230 - len(m[0])) // 2, 10
    for j, row in enumerate(m):
        for i, v in enumerate(row):
            if v:
                px[ox + i, oy + j] = _c(GREEN, 1)
    digits = {"2": [".##.", "#..#", "..#.", ".#..", "####"], "4": ["#.#.", "#.#.", "####", "..#.", "..#."]}
    small = GA.word_mask("시간")
    # "24" in a little block font, then 시간
    ox, oy = 236, 44
    for k, ch in enumerate("24"):
        for j, row in enumerate(digits[ch]):
            for i, c in enumerate(row):
                if c == "#":
                    for a in range(2):
                        for b in range(2):
                            px[ox + k * 10 + i * 2 + a, oy - 2 + j * 2 + b] = _c("fox", 2)
    for j, row in enumerate(small):
        for i, v in enumerate(row):
            if v and oy - 4 + j < H - 2:
                px[ox + 22 + i, oy - 4 + j] = _c("fox", 2)
    return im


def sign_back():
    im = Image.new("RGBA", (16, 16), _c("steel", 3))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if y % 4 == 0:
                px[x, y] = _c("steel", 2)
    return im


def header(text, w, h, bg=(GREEN, 2), fg=("snow", 2), edge=(GREEN, 1), scale=1):
    """A header strip w x h texels: the word centred, a darker line top and bottom."""
    im = Image.new("RGBA", (w, h), _c(*bg))
    px = im.load()
    for x in range(w):
        for y in (0, 1, h - 2, h - 1):
            px[x, y] = _c(*edge)
    m = mask(text)
    if scale > 1 and len(m[0]) * scale > w - 4:
        scale = 1
    if scale > 1:
        m = GA.scale_mask(m, scale)
    ox, oy = (w - len(m[0])) // 2, (h - len(m)) // 2
    for j, row in enumerate(m):
        for i, v in enumerate(row):
            if v and 0 <= ox + i < w and 0 <= oy + j < h:
                px[ox + i, oy + j] = _c(*fg)
    return im


# ------------------------------------------------------------------------------------------------
# Money, the card, the scratch card
# ------------------------------------------------------------------------------------------------

DIGITS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "5": ["###", "#..", "###", "..#", "###"], "2": ["###", "..#", "###", "#..", "###"],
}


def _digits(px, text, x, y, col, k=1):
    for n, ch in enumerate(text):
        for j, row in enumerate(DIGITS[ch]):
            for i, c in enumerate(row):
                if c == "#":
                    for a in range(k):
                        for b in range(k):
                            px[x + n * 4 * k + i * k + a, y + j * k + b] = col


def note(n):
    """A banknote in the left 56 x 28 of a 64 x 32 picture (the right part unused)."""
    r, s = MONEY[n]
    im = Image.new("RGBA", (64, 32), _c(r, s))
    px = im.load()
    W, H = 56, 28
    for y in range(H):
        for x in range(W):
            e = min(x, y, W - 1 - x, H - 1 - y)
            if e < 2:
                px[x, y] = _c(r, s - 1)
            elif e == 2:
                px[x, y] = _c(r, s + 1)
            elif (x * 3 + y * 5) % 9 == 0:
                px[x, y] = _c(r, s + 1)            # the fine guilloche pattern
    # the watermark window (paper) on the right, a hanok roof in it
    for y in range(6, 22):
        for x in range(38, 51):
            d = ((x - 44.5) / 6.5) ** 2 + ((y - 14) / 8) ** 2
            if d < 1:
                px[x, y] = _c("paper", 2)
                if 11 <= y <= 13 and abs(x - 44.5) < 6 - (y - 11):
                    px[x, y] = _c(r, s - 1)        # the roof, its eaves turned up
                if y == 14 and abs(x - 44.5) < 6:
                    px[x, y] = _c(r, s - 1)
                if 15 <= y <= 18 and abs(x - 44.5) < 3.5 and (x % 2 == 0):
                    px[x, y] = _c(r, s - 1)        # the posts
    _digits(px, str(n), 5, 5, _c(r, max(0, s - 2)), k=1 if n >= 10000 else 2)
    _digits(px, str(n), 5, 20, _c(r, s - 1), k=1)
    return im


def card():
    im = Image.new("RGBA", (64, 32), _c(GREEN, 1))
    px = im.load()
    W, H = 44, 28
    for y in range(H):
        for x in range(W):
            corner = min(x, W - 1 - x) + min(y, H - 1 - y) < 3
            if corner:
                px[x, y] = _c(GREEN, 0)
            elif x + y * 1.4 > 40:
                px[x, y] = _c(GREEN, 3) if (x + y) % 11 else _c(GREEN, 2)
            else:
                px[x, y] = _c(GREEN, 2)
    for y in range(9, 16):                         # the chip
        for x in range(5, 13):
            px[x, y] = _c("gold", 3) if (x in (8, 9) or y == 12) else _c("gold", 2)
    for k, rr in enumerate((3, 5, 7)):             # contactless waves
        for y in range(8, 18):
            x = 17 + rr - int(abs(y - 12.5) ** 2 / (rr + 2))
            if abs(y - 12.5) < rr:
                px[x, y] = _c("snow", 1)
    for g in range(4):                             # the number row
        for i in range(4):
            px[5 + g * 9 + i * 2, 21] = _c("snow", 2)
    for x in range(30, 40):                        # a little logo bar
        px[x, 5] = px[x, 6] = _c("corn", 3)
    return im


def lottery():
    W, H = 48, 64
    im = Image.new("RGBA", (W, H), _c("petal_yellow", 2))
    px = im.load()
    for y in range(H):
        for x in range(W):
            e = min(x, y, W - 1 - x, H - 1 - y)
            if e < 2:
                px[x, y] = _c("fabric_red", 2)
            elif y < 18:
                px[x, y] = _c("fabric_red", 3) if (x // 4 + y // 4) % 2 else _c("salmon", 3)
    m = GA.word_mask("복권", spacing=1)
    ox = (W - len(m[0])) // 2
    for j, row in enumerate(m):
        for i, v in enumerate(row):
            if v and 0 <= ox + i < W:
                px[ox + i, 2 + j] = _c("snow", 2)
    for k in range(3):                             # the silver scratch panels, one scratched off
        y0 = 22 + k * 13
        for y in range(y0, y0 + 10):
            for x in range(6, W - 6):
                if k == 0 and x < 26:
                    px[x, y] = _c("paper", 2)
                    if y0 + 3 <= y <= y0 + 6 and x in (10, 11, 15, 16, 20, 21):
                        px[x, y] = _c("fabric_red", 2)       # the uncovered symbols
                else:
                    px[x, y] = _c("fish_silver", 4) if (x + y) % 5 else _c("fish_silver", 3)
    return im


# ------------------------------------------------------------------------------------------------
# The vest's armour layer
# ------------------------------------------------------------------------------------------------

def vest_layer():
    """64 x 32 humanoid layer: only the body box (16, 16, 8 x 12 x 4) is painted."""
    im = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = im.load()
    G = lambda s: _c(GREEN, s)

    def fill(x0, y0, w, h, fn):
        for y in range(h):
            for x in range(w):
                c = fn(x, y)
                if c:
                    px[x0 + x, y0 + y] = c
    # top (shoulders): the straps over the shoulders, the neck hole in the middle
    fill(20, 16, 8, 4, lambda x, y: G(3) if x < 2 or x > 5 else None)
    fill(28, 16, 8, 4, lambda x, y: G(1) if x < 2 or x > 5 else None)

    # front: a V neck (the shirt shows), a zip, the name tag on the wearer's left chest (the picture's right)
    def front(x, y):
        if y < 6 and 2 <= x <= 5 and abs(x - 3.5) < 2.2 - y * 0.35:
            return None
        if y == 11:
            return G(0)                            # the hem
        if x in (3, 4) and y >= 4:
            return _c("steel", 4) if (y % 2) else _c("steel", 3)   # the zip
        if x in (5, 6) and y in (4, 5):
            return _c("snow", 2) if y == 4 else _c("snow", 1)      # the name tag
        if x == 0 or x == 7:
            return G(1)
        if y == 8:
            return _c("corn", 3) if x not in (3, 4) else None      # a sun-yellow band (the brand's)
        return G(2)
    fill(20, 20, 8, 12, front)
    # the sides and back
    fill(16, 20, 4, 12, lambda x, y: G(0) if y == 11 else (_c("corn", 3) if y == 8 else G(1)))
    fill(28, 20, 4, 12, lambda x, y: G(0) if y == 11 else (_c("corn", 3) if y == 8 else G(1)))

    def back(x, y):
        if y == 11:
            return G(0)
        if 2 <= y <= 4:
            return _c("snow", 2) if not (y == 3 and x % 2) else G(3)   # a white band, "GS26" in it as dots
        if y == 8:
            return _c("corn", 3)
        return G(2)
    fill(32, 20, 8, 12, back)
    # bottom of the body box
    fill(28, 16, 8, 4, lambda x, y: px[28 + x, 16 + y] if px[28 + x, 16 + y][3] else None)
    return im


# ------------------------------------------------------------------------------------------------
# 3차 (docs/plans/편의점_기획안.md §16 새 모델): the licences, the ID card, the two new vests' layers, the order tablet
# ------------------------------------------------------------------------------------------------

# tier -> (name, border ramp, ribbon ramp, stars): 골목점 bronze on green, 표준점 silver on navy, 대형점 gold on wine
LICENSE_TIERS = {"alley": ("골목점", GREEN, "copper", 1), "standard": ("표준점", "indigo", "fish_silver", 2),
                 "large": ("대형점", "wine", "gold", 3)}
# the ribbon's steps (lit, base, shade) per ramp: the ramps differ in length
RIBBON = {"copper": (4, 3, 1), "fish_silver": (4, 3, 1), "gold": (3, 2, 0)}

STAR = ["..#..", "..#..", "#####", ".###.", ".#.#."]


def _mask_at(px, m, x0, y0, col, W, H):
    for j, row in enumerate(m):
        for i, v in enumerate(row):
            if v and 0 <= x0 + i < W and 0 <= y0 + j < H:
                px[x0 + i, y0 + j] = col


def license_face(tier):
    """80 x 104 (a 10 x 13 px certificate, 8 texels a px): cream paper with a fine guilloche, a border in the tier's
    colour inside a gold rule, corner diamonds; the GS26 mark (the sign's disc and leaf, GS26 in the sign's letters),
    "권리증", the tier's name on a ribbon banner in the ribbon's metal with its stars, lines of print, a signature line
    and the red 직인 stamp. The bottom left is left plain: the model's wax seal and ribbon tails sit there."""
    name, border, rib, stars = LICENSE_TIERS[tier]
    W, H = 80, 104
    im = Image.new("RGBA", (W, H), _c("cream", 3))
    px = im.load()
    lit, base, dark = RIBBON[rib]
    for y in range(H):
        for x in range(W):
            e = min(x, y, W - 1 - x, H - 1 - y)
            if e < 1:
                px[x, y] = _c(border, 0)
            elif e < 4:
                px[x, y] = _c(border, 1 if e == 3 else 2)
                if tier == "large" and e == 2 and (x + y) % 4 == 0:
                    px[x, y] = _c("gold", 2)              # the 대형점's border stitched with gold
            elif e == 5:
                px[x, y] = _c("gold", 1)                  # the gold rule
            elif e == 4:
                px[x, y] = _c("cream", 3)
            else:
                # the guilloche: two crossing waves, faint
                w1 = math.sin(x * 0.35 + y * 0.12) * 3 + y * 0.5
                w2 = math.sin(x * 0.28 - y * 0.15 + 1.3) * 3 - y * 0.45
                px[x, y] = _c("cream", 2) if (int(w1) % 7 == 0 and int(w1 * 10) % 10 < 3) or \
                    (int(w2) % 9 == 0 and int(abs(w2) * 10) % 10 < 3) else _c("cream", 3)
    # corner diamonds on the rule
    for cx, cy in ((5, 5), (W - 6, 5), (5, H - 6), (W - 6, H - 6)):
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                if abs(dx) + abs(dy) <= 2:
                    px[cx + dx, cy + dy] = _c("gold", 2) if abs(dx) + abs(dy) < 2 else _c("gold", 0)
    # the GS26 mark: a sun-yellow disc with the leaf, then GS (green) 26 (gold) in the sign's letters, 1x
    cx, cy, R = 24, 14, 6
    for y in range(cy - R - 1, cy + R + 2):
        for x in range(cx - R - 1, cx + R + 2):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d < R:
                px[x, y] = _c("corn", 2)
                u, v = (x - cx) * 0.75 - (y - cy) * 0.66, (x - cx) * 0.66 + (y - cy) * 0.75
                if (abs(u) / 4.5) ** 1.6 + (abs(v) / 2.6) ** 1.6 < 1:
                    px[x, y] = _c(GREEN, 2) if v >= 0 else _c(GREEN, 3)
            elif d < R + 1:
                px[x, y] = _c("corn", 0)
    x = 33
    for ch in "GS26":
        col = _c(GREEN, 1) if ch in "GS" else _c("corn", 0)
        for j, row in enumerate(LETTERS[ch]):
            for i, c in enumerate(row):
                if c == "#":
                    px[x + i, 10 + j] = col
        x += 7 if ch != "S" else 8
    # 권리증, ink, bold
    m = GA.word_mask("권리증", spacing=3)
    _mask_at(px, m, (W - len(m[0])) // 2, 25, _c("iron", 2), W, H)
    # the ribbon banner: its ends notched, folds behind it (darker) at both ends
    y0, y1 = 44, 60
    for y in range(y0, y1):
        for x in range(8, W - 8):
            t = y - y0
            notch = abs(t - (y1 - y0 - 1) / 2) <= 2.5 - 0
            if (x < 15 or x >= W - 15):
                if (x < 11 or x >= W - 11) and abs(t - 7.5) < 3 - min(x - 8, W - 9 - x) * 1.0:
                    continue                            # the V notch at the tails
                px[x, y] = _c(rib, dark)
                if y in (y0 + 2, y1 - 3):
                    px[x, y] = _c(rib, base)
                continue
            if 15 <= x < 17 or W - 17 <= x < W - 15:
                px[x, y] = _c(rib, 0)                   # the fold's shadow
                continue
            px[x, y] = _c(rib, lit) if t <= 1 else _c(rib, dark) if t >= y1 - y0 - 2 else _c(rib, base)
    m = GA.word_mask(name, spacing=2)
    ink = _c("iron", 1) if rib != "copper" else _c("snow", 2)
    shadow = _c(rib, dark)
    ox = (W - len(m[0])) // 2
    _mask_at(px, m, ox + 1, 46, shadow, W, H)
    _mask_at(px, m, ox, 45, ink, W, H)
    # the stars under the banner
    sx = (W - (stars * 7 - 2)) // 2
    for k in range(stars):
        for j, row in enumerate(STAR):
            for i, c in enumerate(row):
                if c == "#":
                    px[sx + k * 7 + i, 63 + j] = _c("gold", 2) if j < 3 else _c("gold", 1)
    # lines of print: words of grey ink, the last line short
    rows = [(72, 56), (77, 54), (82, 50), (87, 32)]
    for (y, length) in rows:
        x = 12
        k = 0
        while x < 12 + length:
            wl = 3 + int(h_(k * 7 + y) * 6)
            for i in range(wl):
                if x + i < 12 + length:
                    px[x + i, y] = _c("stone", 1)
                    if (x + i + y) % 3 == 0:
                        px[x + i, y - 1] = _c("stone", 2)
            x += wl + 2
            k += 1
    # the signature line (right), a scrawl over it, and the red 직인 stamp beside it
    for x in range(44, 70):
        px[x, 96] = _c("stone", 1)
    for k in range(18):
        X = 46 + k
        Y = 94 + int(round(math.sin(k * 0.9) * 1.5))
        px[X, Y] = _c("indigo", 1)
    for y in range(88, 97):
        for x in range(62, 71):
            e = min(x - 62, y - 88, 70 - x, 96 - y)
            if e == 0 or (e >= 2 and ((x - 62) in (3, 5) or (y - 88) == 4) and (x + y) % 5):
                px[x, y] = _c("pepper", 2) if (x * 3 + y) % 7 else _c("pepper", 3)
    return im


def h_(n):
    """a small fixed hash in 0..1"""
    n = (n * 2654435761) & 0xFFFFFFFF
    return ((n >> 8) & 0xFFFF) / 65535.0


def id_card():
    """128 x 80 (a 6.4 x 4 px card, 20 texels a px): an ID card in the Korean layout, not a real one: a pale
    blue-white card with a fine wave guilloche, "신분증" in navy at the top left, the text area under it left plain
    (the store shows the name and the year of birth over it with a TextDisplay), the photo on the right (a grey-blue
    head and shoulders), a small rainbow hologram disc on the photo's corner, a navy strip along the bottom."""
    W, H = 128, 80
    im = Image.new("RGBA", (W, H), _c("porcelain", 2))
    px = im.load()
    for y in range(H):
        for x in range(W):
            if min(x, W - 1 - x) + min(y, H - 1 - y) < 4:
                px[x, y] = (0, 0, 0, 0)               # the rounded corners
                continue
            w = math.sin(x * 0.11 + math.sin(y * 0.07) * 2) * 4 + y * 0.6
            if int(w * 4) % 22 == 0:
                px[x, y] = _c("ice", 3) if x < 84 else _c("ice", 2)
            if y >= H - 6:
                px[x, y] = _c("indigo", 2) if y >= H - 5 else _c("indigo", 3)
    m = GA.word_mask("신분증", spacing=2)
    _mask_at(px, m, 8, 6, _c("indigo", 1), W, H)
    for x in range(8, 78):
        px[x, 24] = _c("ice", 1)                      # a rule under the title
    # the photo
    x0, y0, x1, y1 = 88, 10, 120, 58
    for y in range(y0, y1):
        for x in range(x0, x1):
            if x in (x0, x1 - 1) or y in (y0, y1 - 1):
                px[x, y] = _c("fish_silver", 1)
                continue
            px[x, y] = _c("fish_silver", 4)
            cx = (x0 + x1) / 2
            if ((x - cx) / 8.5) ** 2 + ((y - 27) / 10) ** 2 < 1:
                px[x, y] = _c("fish_silver", 2)       # the head
            if y >= 40 and abs(x - cx) < 6 + (y - 40) * 0.9:
                px[x, y] = _c("fish_silver", 2)       # the shoulders
            if 36 <= y < 41 and abs(x - cx) < 3.5:
                px[x, y] = _c("fish_silver", 2)       # the neck
    for y in range(50, 63):
        for x in range(108, 122):
            d = ((x - 115) ** 2 + (y - 56) ** 2) ** 0.5
            if d < 6:
                hue = int((x + y * 0.5) / 3) % 4
                px[x, y] = _c("aurora", 4 - hue) if d < 5 else _c("aurora", 1)
    return im


def _vest_layer(main, dark, band, tag, trim=None, stripes=None, back_band=None):
    """vest_layer's cut with other colours: main / dark (the sides and back) / band (row 8, or None) / tag (the name
    tag's two colours) / trim (the front edges and the hem) / stripes (reflective tape: rows and the shoulder columns)
    / back_band (rows 2-4 on the back: (colour, dot colour))."""
    im = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = im.load()

    def fill(x0, y0, w, h, fn):
        for y in range(h):
            for x in range(w):
                c = fn(x, y)
                if c:
                    px[x0 + x, y0 + y] = c

    def tape(x, y, front):
        if stripes is None:
            return None
        rows, cols, col, lit = stripes
        if y in rows:
            return lit if (x + y) % 3 == 0 else col
        if front is not None and x in cols and y < min(rows):
            return lit if y % 3 == 0 else col
        return None
    top_strap = lambda x, y, c: (stripes[2] if stripes and x in (1, 6) else c) if (x < 2 or x > 5) else None
    fill(20, 16, 8, 4, lambda x, y: top_strap(x, y, shade_c(main, 1.08)))
    fill(28, 16, 8, 4, lambda x, y: top_strap(x, y, dark))

    def front(x, y):
        if y < 6 and 2 <= x <= 5 and abs(x - 3.5) < 2.2 - y * 0.35:
            return None
        if y == 11:
            return trim or shade_c(dark, 0.8)
        if x in (3, 4) and y >= 4:
            return _c("steel", 4) if (y % 2) else _c("steel", 3)
        if trim and (x in (2, 5) and y < 6 and abs(x - 3.5) >= 2.2 - y * 0.35 - 1):
            return trim                               # the gold piping up the V neck
        if x in (5, 6) and y in (4, 5):
            return tag[0] if y == 4 else tag[1]
        t = tape(x, y, True)
        if t:
            return t
        if x == 0 or x == 7:
            return dark
        if band and y == 8:
            return band if x not in (3, 4) else None
        return main
    fill(20, 20, 8, 12, front)

    def side(x, y):
        if y == 11:
            return trim or shade_c(dark, 0.8)
        t = tape(x, y, None)
        if t:
            return t
        if band and y == 8:
            return band
        return dark
    fill(16, 20, 4, 12, side)
    fill(28, 20, 4, 12, side)

    def back(x, y):
        if y == 11:
            return trim or shade_c(dark, 0.8)
        if back_band and 2 <= y <= 4:
            return back_band[0] if not (y == 3 and x % 2) else back_band[1]
        t = tape(x, y, True)
        if t:
            return t
        if band and y == 8:
            return band
        return main
    fill(32, 20, 8, 12, back)
    return im


def shade_c(c, k):
    return tuple(max(0, min(255, int(round(v * k)))) for v in c[:3]) + (255,)


def vest_layer_manager():
    """64 x 32: 점장 조끼 (economy:store_vest_manager): the work vest's cut in a deeper green, gold piping up the V neck,
    down the front edges and round the hem, a gold band, a gold name tag (점장), a gold band across the back."""
    return _vest_layer(main=_c(GREEN, 1), dark=_c(GREEN, 0), band=_c("gold", 2), tag=(_c("gold", 3), _c("gold", 1)),
                       trim=_c("gold", 1), back_band=(_c("gold", 2), _c(GREEN, 0)))


def vest_layer_night():
    """64 x 32: 야간 조끼 (economy:store_vest_night): navy, two bands of silver reflective tape round the body (rows 7
    and 9) and tape up over the shoulders front and back, a white name tag."""
    return _vest_layer(main=_c("indigo", 1), dark=_c("indigo", 0), band=None, tag=(_c("snow", 2), _c("snow", 1)),
                       stripes=((7, 9), (1, 6), _c("fish_silver", 4), _c("snow", 2)))


def manager_tag():
    """48 x 24: the 점장 name tag on the vest item: gold, 점장 in dark green."""
    im = Image.new("RGBA", (48, 24), _c("gold", 2))
    px = im.load()
    for y in range(24):
        for x in range(48):
            e = min(x, y, 47 - x, 23 - y)
            if e < 2:
                px[x, y] = _c("gold", 1) if e == 0 else _c("gold", 3)
    m = GA.word_mask("점장", spacing=2)
    _mask_at(px, m, (48 - len(m[0])) // 2, (24 - len(m)) // 2, _c(GREEN, 0), 48, 24)
    return im


TABLET_ROWS = [(("pepper", 2), 26, "12"), (("corn", 2), 18, "24"), (("sapphire", 2), 30, "8"), (("iron", 3), 14, "3")]
SMALL_DIG = {"0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
             "2": ["###", "..#", "###", "#..", "###"], "3": ["###", "..#", "###", "..#", "###"],
             "4": ["#.#", "#.#", "###", "..#", "..#"], "8": ["###", "#.#", "###", "#.#", "###"]}


def tablet_screen():
    """88 x 64 (an 11 x 8 px screen, 8 texels a px): the 발주 app: a green header with 발주 and tomorrow's weather (a
    sun behind a cloud), four lines of goods (a colour chip, the name as grey bars, a - / count / + stepper), a green
    send key along the bottom. Lit."""
    W, H = 88, 64
    im = Image.new("RGBA", (W, H), _c("snow", 2))
    px = im.load()
    for y in range(16):
        for x in range(W):
            px[x, y] = _c(GREEN, 2) if y < 15 else _c(GREEN, 0)
    m = GA.word_mask("발주", bold=False, spacing=1)
    _mask_at(px, m, 5, 1, _c("snow", 2), W, H)
    # tomorrow's weather: a sun peeking over a cloud
    for y in range(2, 14):
        for x in range(62, 84):
            if ((x - 72) ** 2 + (y - 6) ** 2) ** 0.5 < 4:
                px[x, y] = _c("corn", 2)
            if ((x - 70) ** 2 + (y - 10) ** 2) ** 0.5 < 3.2 or ((x - 75) ** 2 + (y - 9.5) ** 2) ** 0.5 < 3.6 or \
                    (66 <= x <= 79 and 10 <= y <= 12):
                px[x, y] = _c("snow", 2)
    for r, (chip, length, count) in enumerate(TABLET_ROWS):
        y0 = 18 + r * 9
        for x in range(2, W - 2):
            px[x, y0 + 8] = _c("snow", 0)            # the row's rule
        for y in range(y0 + 1, y0 + 7):
            for x in range(4, 10):
                px[x, y] = _c(*chip) if not (x == 4 and y == y0 + 1) else _c(chip[0], chip[1] + 1)
        for x in range(13, 13 + length):
            if (x - 13) % 9 != 8:
                px[x, y0 + 3] = _c("iron", 3)
                px[x, y0 + 4] = _c("iron", 3)
        # the stepper: [-] count [+]
        for y in range(y0 + 1, y0 + 7):
            for x in range(55, 61):
                px[x, y] = _c("car_white", 1)
            for x in range(77, 83):
                px[x, y] = _c(GREEN, 2)
        for x in range(56, 60):
            px[x, y0 + 4] = _c("iron", 2)
        for x in range(78, 82):
            px[x, y0 + 4] = _c("snow", 2)
        for y in range(y0 + 2, y0 + 6):
            px[79 + 1, y] = _c("snow", 2)
        cx = 69 - (len(count) * 4 - 1) // 2
        for k, ch in enumerate(count):
            for j, row in enumerate(SMALL_DIG[ch]):
                for i, c in enumerate(row):
                    if c == "#":
                        px[cx + k * 4 + i, y0 + 1 + j] = _c("iron", 1)
    # the send key
    for y in range(55, 63):
        for x in range(4, W - 4):
            px[x, y] = _c(GREEN, 2) if y < 61 else _c(GREEN, 1)
    for k in range(4):                               # a white check mark
        px[40 + k, 57 + k] = px[40 + k, 58 + k] = _c("snow", 2)
    for k in range(6):
        px[44 + k, 60 - k] = px[44 + k, 59 - k] = _c("snow", 2)
    return im


# ------------------------------------------------------------------------------------------------
# 디테일 (models/store.py plates at more than 16 px a block): the goods' fronts on the shelves and in the cases,
# the electronic price rails, shelf talkers, promo cards and posters, the POS and card terminal screens, the
# appliances' fronts, the freezer's baskets seen from above, the cigarette packs
# ------------------------------------------------------------------------------------------------

from stsv_assets.palette import ladder as _ladder

HD = 4                    # texels a px of the goods' fronts (a 16 px a block picture drawn 4x finer)


def _rgba(c):
    if isinstance(c[0], str):
        r, g, b = _ladder(c[0], c[1])[:3]
        return (r, g, b, 255)
    return c if len(c) == 4 else (*c, 255)


class Pic:
    """A small picture with drawing helpers; colours are (ramp, step) (steps past the ramp's ends continue it) or
    raw (r, g, b[, a])."""

    def __init__(self, w, h, bg=None):
        self.w, self.h = w, h
        self.im = Image.new("RGBA", (w, h), _rgba(bg) if bg else (0, 0, 0, 0))
        self.px = self.im.load()

    def p(self, x, y, c):
        x, y = int(x), int(y)
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[x, y] = _rgba(c)

    def rect(self, x0, y0, x1, y1, c):
        """x0..x1-1, y0..y1-1."""
        col = _rgba(c)
        for y in range(max(0, int(y0)), min(self.h, int(y1))):
            for x in range(max(0, int(x0)), min(self.w, int(x1))):
                self.px[x, y] = col

    def ell(self, cx, cy, rx, ry, c, ring=None):
        """A filled ellipse round the texel centres; ring (colour) paints its edge texels."""
        col, rc = _rgba(c), (_rgba(ring) if ring else None)
        for y in range(max(0, int(cy - ry - 1)), min(self.h, int(cy + ry + 2))):
            for x in range(max(0, int(cx - rx - 1)), min(self.w, int(cx + rx + 2))):
                d = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2
                if d <= 1:
                    self.px[x, y] = rc if rc and d > (1 - 1.6 / max(rx, ry)) else col

    def line(self, x0, y0, x1, y1, c):
        n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
        for k in range(n + 1):
            t = k / max(n, 1)
            self.p(round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), c)

    def text(self, x0, y, x1, c, seed=0):
        """A line of print too small to read: words as runs of texels with gaps."""
        x, k = x0, seed
        while x < x1:
            n = 2 + (k * 7 + 3) % 3
            for i in range(n):
                if x + i < x1:
                    self.p(x + i, y, c)
            x += n + 1
            k += 1

    def digits(self, s, x, y, c, font=None):
        font = font or SMALL_DIG
        for n, ch in enumerate(s):
            if ch == ",":
                self.p(x + 1, y + 5, c)
                x += 2
                continue
            for j, row in enumerate(font[ch]):
                for i, v in enumerate(row):
                    if v == "#":
                        self.p(x + i, y + j, c)
            x += len(font[ch][0]) + 1

    def mask(self, m, x0, y0, c):
        for j, row in enumerate(m):
            for i, v in enumerate(row):
                if v:
                    self.p(x0 + i, y0 + j, c)

    def shade(self, x, y, k):
        """Multiplies a texel's colour (k < 1 darker)."""
        if 0 <= x < self.w and 0 <= y < self.h:
            r, g, b, a = self.px[x, y]
            if a:
                self.px[x, y] = (min(255, int(r * k)), min(255, int(g * k)), min(255, int(b * k)), a)


DIG35 = {**SMALL_DIG, "5": ["###", "#..", "###", "..#", "###"], "6": ["###", "#..", "###", "#.#", "###"],
         "7": ["###", "..#", "..#", ".#.", ".#."], "9": ["###", "#.#", "###", "..#", "###"], "+": ["...", ".#.", "###", ".#.", "..."]}


def _crimp(P, x0, x1, y0, y1, col):
    """A pillow bag's crimped seam: teeth across it, a dark line where it meets the bag."""
    P.rect(x0, y0, x1, y1, col)
    for x in range(x0, x1):
        for y in range(y0, y1):
            if (x + y) % 2 == 0:
                P.p(x, y, (col[0], col[1] + 1))


def _bottle(P, W, H, body, cap, label, label_fg, shoulder=8, wide=1):
    """A PET / glass bottle filling the facing (W x H): cap, neck, shoulder, body, its label, a highlight."""
    cx = W / 2
    P.rect(cx - 2, 0, cx + 2, 3, cap)
    P.rect(cx - 2, 2, cx + 2, 3, (cap[0], cap[1] - 1))
    for y in range(3, shoulder):
        hw = 1.5 + (y - 3) * (W / 2 - wide - 1.5) / max(1, shoulder - 3)
        P.rect(round(cx - hw), y, round(cx + hw), y + 1, body)
    P.rect(wide, shoulder, W - wide, H, body)
    P.rect(wide, H - 1, W - wide, H, (body[0], body[1] - 1))
    ly0, ly1 = shoulder + 3, shoulder + 3 + (H - shoulder) // 3
    P.rect(wide, ly0, W - wide, ly1, label)
    P.text(wide + 2, ly0 + 2, W - wide - 2, label_fg, seed=W)
    P.rect(wide + 3, ly1 - 2, W - wide - 3, ly1 - 1, label_fg)
    for y in range(4, H - 2):                       # the highlight down the bottle
        P.p(wide + 1 if y >= shoulder else cx - 1, y, (body[0], body[1] + 2) if y not in range(ly0, ly1) else (label[0], label[1] + 1))


def _cup_ramen(P, W, H, body, panel, accent, bowl):
    """A cup ramen's front: the white lid's lip, the body, a brand panel with a flame and print, the bowl picture."""
    P.rect(0, 0, W, H, body)
    P.rect(0, 0, W, 3, ("snow", 2))
    P.rect(0, 2, W, 3, ("car_white", 1))
    P.rect(0, 3, W, 4, (body[0], body[1] + 1))
    P.rect(2, 5, W - 2, 12, panel)
    for x in range(3, W - 3):                       # a flame under the brand's print
        h = 2 + ((x * 5) % 3)
        P.rect(x, 12 - h, x + 1, 11, accent)
    P.text(3, 6, W - 3, ("snow", 2), seed=3)
    P.text(4, 8, W - 5, ("snow", 2), seed=5)
    P.ell(W / 2, 15, W / 2 - 3, 2.6, ("snow", 2))   # the bowl: noodles in soup
    P.ell(W / 2, 14.6, W / 2 - 4, 1.6, bowl)
    for x in range(5, W - 5, 2):
        P.p(x, 14 + (x // 2) % 2, ("corn", 2))
    P.p(W / 2 + 2, 14, ("leaf", 3))
    P.rect(0, H - 2, W, H, (body[0], body[1] - 1))


def _fc_chips(P, W, H):
    P.rect(0, 0, W, H, ("corn", 2))
    _crimp(P, 0, W, 0, 3, ("pepper", 2))
    _crimp(P, 0, W, H - 3, H, ("pepper", 2))
    P.rect(1, 5, W - 1, 10, ("pepper", 2))
    P.rect(2, 4, W - 4, 5, ("pepper", 2))
    P.text(3, 7, W - 3, ("snow", 2), seed=1)
    P.ell(6.5, 14.5, 4, 3, ("onion", 3), ring=("onion", 2))
    P.ell(10, 16, 4.5, 3.2, ("corn", 3), ring=("onion", 3))
    for k in range(3):
        P.line(7 + k * 2, 18, 9 + k * 2, 14, ("corn", 2))
    P.ell(13, 11.5, 2, 2, ("tomato", 3))
    P.p(13, 11, ("snow", 2))
    for y in range(4, H - 4):
        P.p(2, y, ("corn", 3))


def _fc_shrimp(P, W, H):
    P.rect(0, 0, W, H, ("snow", 2))
    _crimp(P, 0, W, 0, 3, ("pepper", 2))
    _crimp(P, 0, W, H - 3, H, ("pepper", 2))
    P.rect(0, 4, W, 8, ("sapphire", 2))
    P.text(2, 5, W - 2, ("snow", 2), seed=2)
    P.text(3, 6, W - 4, ("corn", 3), seed=4)
    import math as _m
    for a in range(0, 300, 12):                     # a curled shrimp, its shell in bands
        t = _m.radians(a - 60)
        for r in (3.2, 4.0, 4.8):
            P.p(8 + r * _m.cos(t), 14 + r * _m.sin(t), ("fox", 3) if (a // 36) % 2 else ("fox", 4))
    P.p(10.5, 11, ("iron", 1))
    P.rect(4, 17, 7, 19, ("fox", 2))
    for y in range(9, H - 4):
        P.p(1, y, ("car_white", 3))


def _fc_choco(P, W, H):
    P.rect(0, 0, W, H, ("pepper", 2))
    P.rect(0, 0, W, 4, ("snow", 2))
    P.text(2, 1, W - 2, ("pepper", 2), seed=6)
    P.text(4, 2, W - 4, ("doenjang", 1), seed=1)
    for cx in (4.5, 11.5):
        P.ell(cx, 9.5, 3.4, 3.4, ("doenjang", 2), ring=("doenjang", 1))
        P.ell(cx - 0.7, 8.6, 1.2, 0.8, ("doenjang", 3))
    P.rect(0, H - 3, W, H - 2, ("corn", 2))
    P.rect(0, H - 2, W, H, ("pepper", 1))


def _fc_jelly(P, W, H):
    P.rect(0, 0, W, H, ("crystal", 3))
    _crimp(P, 0, W, 0, 2, ("crystal", 1))
    P.text(1, 3, W - 1, ("snow", 2), seed=3)
    P.rect(2, 5, W - 2, 13, ("crystal", 4))
    bears = [((2, 6), ("tomato", 2)), ((6, 6), ("corn", 2)), ((3, 9), (GREEN, 3)), ((7, 9), ("pink", 2))]
    for (bx, by), col in bears:
        P.rect(bx, by + 1, bx + 3, by + 3, col)
        P.p(bx, by, col)
        P.p(bx + 2, by, col)
    P.rect(0, H - 2, W, H, ("crystal", 1))


def _fc_chocolate(P, W, H):
    P.rect(0, 0, W, H, ("doenjang", 1))
    P.rect(W - 5, 0, W, H, ("gold", 3))
    for y in range(H):
        P.p(W - 5 + (y % 3), y, ("gold", 2))
    P.rect(2, 2, W - 8, 6, ("doenjang", 0))
    P.text(3, 3, W - 9, ("gold", 3), seed=2)
    P.rect(W - 7, 0, W - 6, H, ("pepper", 2))
    P.rect(0, H - 1, W, H, ("doenjang", 0))


def _fc_candy(P, W, H):
    P.rect(0, 0, W, H, ("pink", 2))
    _crimp(P, 0, W, 0, 2, ("pink", 1))
    P.rect(0, 3, W, 6, ("pepper", 3))
    P.text(1, 4, W - 1, ("snow", 2), seed=7)
    for (cx, cy), col in (((3.5, 9.5), ("tomato", 3)), ((8.5, 10.5), ("sapphire", 3)), ((4.5, 14.5), ("corn", 2)),
                          ((8.5, 15.5), (GREEN, 3))):
        P.ell(cx, cy, 2.2, 2.2, ("snow", 2))
        P.p(cx - 1, cy - 1, col)
        P.p(cx, cy, col)
        P.p(cx + 1, cy - 1, col)
        P.p(cx - 1, cy + 1, col)
    P.rect(0, H - 2, W, H, ("pink", 1))


def _fc_ramen_red(P, W, H):
    _cup_ramen(P, W, H, ("pepper", 2), ("iron", 1), ("pepper", 3), ("tomato", 3))


def _fc_ramen_mild(P, W, H):
    _cup_ramen(P, W, H, ("fox", 3), (GREEN, 2), ("corn", 2), ("corn", 3))


def _fc_ramen_big(P, W, H):
    P.rect(0, 0, W, H, ("wine", 3))
    P.rect(0, 0, W, 2, ("snow", 2))
    P.rect(0, 2, W, 6, ("pepper", 2))
    P.text(2, 3, W - 2, ("snow", 2), seed=8)
    P.text(4, 4, W - 6, ("corn", 2), seed=2)
    P.ell(W / 2, 10.5, 7, 3, ("snow", 2))
    P.ell(W / 2, 10, 6, 1.8, ("tomato", 3))
    for x in range(5, W - 5, 2):
        P.p(x, 9 + (x // 2) % 2, ("corn", 2))
    P.p(W / 2 - 3, 9, (GREEN, 3))
    P.p(W / 2 + 3, 10, ("doenjang", 2))
    P.rect(0, H - 2, W, H, ("wine", 2))


def _fc_bagramen(P, W, H):
    P.rect(0, 0, W, H, ("fox", 3))
    for y in range(H):                              # the crimped ends
        for x in (0, 1, W - 2, W - 1):
            P.p(x, y, ("fox", 2) if (x + y) % 2 else ("fox", 4))
    P.rect(3, 1, 13, H - 2, ("pepper", 2))
    P.text(4, 3, 12, ("snow", 2), seed=1)
    P.text(4, 5, 11, ("snow", 2), seed=4)
    P.rect(4, 8, 9, 9, ("corn", 2))
    P.ell(18, 6.5, 4, 3.3, ("snow", 2))
    P.ell(18, 6, 3.2, 2, ("tomato", 3))
    for x in range(15, 22, 2):
        P.p(x, 6 + (x // 2) % 2, ("corn", 2))
    P.rect(2, H - 1, W - 2, H, ("fox", 2))


def _fc_tissue(P, W, H):
    P.rect(0, 0, W, H, ("snow", 2))
    P.rect(0, 0, W, 1, ("car_white", 3))
    for (x, y) in ((2, 2), (7, 3), (12, 2), (4, 5), (10, 5)):
        P.p(x, y, ("pink", 2))
        P.p(x + 1, y, ("pink", 1))
        P.p(x, y + 1, (GREEN, 3))
    P.rect(0, H - 4, W, H, ("sapphire", 3))
    P.text(2, H - 3, W - 2, ("snow", 2), seed=5)
    P.rect(0, H - 1, W, H, ("sapphire", 2))


def _fc_battery(P, W, H):
    P.rect(0, 0, W, H, ("iron", 1))
    P.rect(0, 0, W, 6, ("fox", 3))
    P.ell(W / 2, 2.5, 1.6, 1.1, ("iron", 0))
    P.text(1, 4, W - 1, ("iron", 1), seed=2)
    P.rect(2, 7, W - 2, 18, ("glass", 3))
    for x0 in (3, 7):
        P.rect(x0, 8, x0 + 2, 17, ("fish_silver", 3))
        P.rect(x0, 8, x0 + 2, 10, ("copper", 3))
        P.p(x0, 11, ("fish_silver", 4))
    P.p(2, 7, ("snow", 2))
    P.rect(0, H - 1, W, H, ("iron", 0))


def _fc_bandage(P, W, H):
    P.rect(0, 0, W, H, ("snow", 2))
    P.rect(0, 0, W, 2, ("pink", 2))
    P.text(1, 0, W - 1, ("snow", 2), seed=6)
    P.rect(2, 3, W - 2, 7, ("salmon", 3))
    P.rect(6, 3, W - 6, 7, ("bean", 3))
    for x in range(3, W - 3, 2):
        P.p(x, 5, ("salmon", 2))
    P.rect(0, H - 1, W, H, ("car_white", 2))


def _fc_umbrella(P, W, H):
    P.rect(2, 0, 4, 4, ("iron", 1))
    P.rect(4, 0, 6, 1, ("iron", 1))
    P.rect(5, 1, 6, 2, ("iron", 1))
    P.rect(2, 4, 6, 5, ("steel", 4))
    for y in range(5, H - 2):
        hw = 2.6 - 0.05 * (y - 5)
        P.rect(round(4 - hw), y, round(4 + hw), y + 1, ("ice", 2))
        P.p(round(4 - hw) + (y % 3), y, ("ice", 3))
    P.rect(1, 10, 7, 11, ("snow", 2))
    P.rect(3, H - 2, 5, H, ("iron", 2))


def _fc_dosirak(P, W, H):
    P.rect(0, 0, W, H, ("glass", 3))
    P.rect(1, 1, 9, 6, ("rice", 2))
    for (x, y) in ((2, 2), (5, 3), (7, 2), (3, 4)):
        P.p(x, y, ("rice", 1))
    P.p(4, 2, ("roe", 1))
    P.rect(9, 1, 11, 6, (GREEN, 2))
    P.text(9, 3, 11, ("snow", 2))
    P.rect(11, 1, 14, 6, ("pepper", 2))
    P.rect(14, 1, 17, 6, ("corn", 2))
    P.rect(17, 1, 23, 6, ("doenjang", 2))
    for x in range(17, 23, 2):
        P.p(x, 2 + x % 3, ("doenjang", 3))
    P.p(19, 1, ("leaf", 3))
    P.p(13, 2, ("pepper", 3))
    P.rect(0, 0, W, 1, ("glass", 4))
    P.rect(0, H - 2, W, H, ("iron", 1))


def _fc_hotbar(P, W, H):
    P.rect(0, 0, W, H, ("snow", 2))
    P.rect(0, 0, W, 3, ("pepper", 2))
    P.text(1, 1, W - 1, ("snow", 2), seed=3)
    P.rect(3, 4, 9, H - 2, ("fox", 3))
    for k in range(4):
        P.line(3, 6 + k * 3, 8, 4 + k * 3, ("onion", 1))
    P.rect(5, H - 2, 7, H, ("hinoki", 3))
    P.p(1, 5, ("car_white", 3))


def _fc_egg(P, W, H):
    P.rect(0, 0, W, H, ("fox", 2))
    for (cx, cy) in ((4, 4), (11.5, 4), (4, 9), (11.5, 9)):
        P.ell(cx, cy, 3.3, 2.8, ("bean", 2), ring=("bean", 1))
        P.p(cx - 1.5, cy - 1.5, ("bean", 3))
    for k in range(-H, W, 3):                       # the orange net
        for y in range(H):
            if (y + k) % 3 == 0:
                P.p(k + y, y, ("fox", 3))
    P.rect(6, 0, 10, 3, ("pepper", 2))
    P.p(7, 1, ("snow", 2))
    P.p(8, 1, ("snow", 2))


def _fc_banana(P, W, H, bg):
    P.rect(0, 0, W, H, bg)
    P.rect(4, 0, 8, 3, (GREEN, 2))
    P.rect(4, 2, 8, 3, (GREEN, 1))
    P.rect(3, 3, 9, 5, ("petal_yellow", 2))
    P.rect(1, 5, W - 1, H, ("petal_yellow", 2))
    P.rect(2, 4, W - 2, 5, ("petal_yellow", 2))
    P.rect(1, 8, W - 1, 13, ("snow", 2))
    P.line(3, 11, 8, 9, ("corn", 2))
    P.line(3, 12, 8, 10, ("corn", 1))
    P.p(9, 9, ("doenjang", 1))
    for y in range(5, H - 1):
        P.p(2, y, ("petal_yellow", 3) if not 8 <= y < 13 else ("snow", 2))
    P.rect(1, H - 1, W - 1, H, ("petal_yellow", 1))


def _fc_coffee_can(P, W, H, bg):
    P.rect(0, 0, W, H, bg)
    P.rect(3, 0, 9, 1, ("fish_silver", 3))
    P.rect(2, 1, 10, 2, ("fish_silver", 4))
    P.rect(1, 2, W - 1, H - 1, ("doenjang", 2))
    P.rect(1, 6, W - 1, 9, ("gold", 2))
    P.text(2, 7, W - 2, ("doenjang", 0), seed=2)
    P.rect(2, 11, W - 2, 15, ("snow", 2))
    P.ell(6, 13, 1.6, 1.2, ("doenjang", 1))
    P.rect(1, H - 1, W - 1, H, ("fish_silver", 3))
    for y in range(2, H - 1):
        P.p(2, y, ("doenjang", 3) if not 6 <= y < 9 else ("gold", 3))


def _fc_coffee_cup(P, W, H, bg):
    P.rect(0, 0, W, H, bg)
    P.rect(1, 0, W - 1, 3, ("snow", 2))
    P.rect(2, 0, W - 2, 1, ("car_white", 3))
    P.rect(1, 3, W - 1, H, ("bean", 3))
    P.rect(1, 6, W - 1, 11, ("doenjang", 1))
    P.ell(6, 8.5, 2, 1.6, ("snow", 2))
    P.p(6, 8, ("doenjang", 1))
    P.rect(1, H - 1, W - 1, H, ("bean", 2))


def _fc_beer(P, W, H, bg):
    P.rect(0, 0, W, H, bg)
    P.rect(2, 0, 10, 1, ("fish_silver", 3))
    P.rect(1, 1, W - 1, H - 1, ("gold", 2))
    P.rect(1, 6, W - 1, 10, ("sapphire", 1))
    P.text(2, 7, W - 2, ("snow", 2), seed=3)
    P.rect(4, 12, 8, 16, ("snow", 2))
    P.ell(6, 14, 1.3, 1.3, ("pepper", 2))
    P.rect(1, H - 1, W - 1, H, ("fish_silver", 2))
    for y in range(1, H - 1):
        P.p(2, y, ("gold", 3))


def _fc_bottle(body, cap, label, fg):
    def draw(P, W, H, bg):
        P.rect(0, 0, W, H, bg)
        _bottle(P, W, H, body, cap, label, fg)
    return draw


FACE_HD = {
    "chips": _fc_chips, "shrimp": _fc_shrimp, "choco": _fc_choco, "jelly": _fc_jelly, "chocolate": _fc_chocolate,
    "candy": _fc_candy, "ramen_red": _fc_ramen_red, "ramen_mild": _fc_ramen_mild, "ramen_big": _fc_ramen_big,
    "bagramen": _fc_bagramen, "tissue": _fc_tissue, "battery": _fc_battery, "bandage": _fc_bandage,
    "umbrella": _fc_umbrella, "dosirak": _fc_dosirak, "hotbar": _fc_hotbar, "egg": _fc_egg,
}
# the drinks stand on the case's lit back (bg)
FACE_HD_BG = {
    "banana": _fc_banana, "coffee_can": _fc_coffee_can, "coffee_cup": _fc_coffee_cup, "beer": _fc_beer,
    "soda": _fc_bottle(("cucumber", 3), (GREEN, 2), ("snow", 2), (GREEN, 2)),
    "ion": _fc_bottle(("ice", 3), ("sapphire", 3), ("sapphire", 2), ("snow", 2)),
    "water": _fc_bottle(("ice", 2), ("snow", 2), ("sapphire", 3), ("snow", 2)),
    "soju": _fc_bottle(("bottle_green", 2), (GREEN, 3), ("snow", 2), (GREEN, 2)),
}


def facing_hd(kind, w, h, bg=("ice", 3)):
    """One facing of a kind's front, w x h px drawn HD times finer."""
    W, H = w * HD, h * HD
    P = Pic(W, H)
    if kind in FACE_HD:
        FACE_HD[kind](P, W, H)
    else:
        FACE_HD_BG[kind](P, W, H, bg)
    return P


def facings_hd(kind, w, h, n, stack, bg=("ice", 3), depth=True):
    """n facings side by side, `stack` high (each facing w x h px), HD texels a px: the gap between two packs in
    shadow, a soft light on each pack's left edge, the bottom row darker (contact)."""
    one = facing_hd(kind, w, h, bg)
    W, H = w * HD, h * HD
    out = Image.new("RGBA", (W * n, H * stack))
    for i in range(n):
        for j in range(stack):
            out.alpha_composite(one.im, (i * W, j * H))
    P = Pic(out.width, out.height)
    P.im, P.px = out, out.load()
    if depth:
        for i in range(n):
            for y in range(out.height):
                P.shade(i * W + W - 1, y, 0.62)
                P.shade(i * W, y, 1.08)
        for x in range(out.width):
            P.shade(x, out.height - 1, 0.78)
    return out


# --- 전자 가격표 (ESL) rails, shelf talkers, promo cards --------------------------------------

ESL_PRICES = ["1,500", "1,200", "2,300", "900", "1,800", "3,500", "1,100", "4,200", "2,700", "1,300"]


def price_rail(length_px, tags, h_px=1, k=8, promo=(), colour=("car_white", 3), seed=0):
    """A shelf's front edge at k texels a px: the clear label holder on a white rail (a lit top lip, a shadowed
    bottom), and an electronic price tag (e-paper: a grey frame, the price in black digits, a red strip on the
    promo ones) where each kind starts. tags: [(start px, end px)] from the rail's start."""
    W, H = int(round(length_px * k)), int(round(h_px * k))
    P = Pic(W, H, colour)
    P.rect(0, 0, W, 1, ("snow", 2))
    P.rect(0, H - 1, W, H, ("car_white", 1))
    P.rect(0, 1, W, 2, ("car_white", 2))
    for n, (a, b) in enumerate(tags):
        x0 = int(a * k) + 2
        x1 = min(int(b * k) - 2, x0 + 26)
        if x1 - x0 < 14:
            continue
        P.rect(x0, 1, x1, H - 1, ("iron", 2))
        P.rect(x0 + 1, 2, x1 - 1, H - 2, (214, 218, 210))       # the e-paper
        P.rect(x0 + 1, H - 2, x1 - 1, H - 1, ("iron", 1))
        price = ESL_PRICES[(n + seed) % len(ESL_PRICES)]
        red = n in promo
        if red:
            P.rect(x0 + 1, 2, x0 + 5, H - 2, ("pepper", 2))
            P.p(x0 + 2, 3, ("snow", 2))
            P.p(x0 + 3, 3, ("snow", 2))
        ww = sum(2 if c == "," else 4 for c in price)
        P.digits(price, x1 - 2 - ww, max(1, (H - 5) // 2), ("iron", 0) if not red else ("pepper", 1), font=DIG35)
        if x1 - x0 > 20 and not red:                # the barcode strip
            for x in range(x0 + 2, x0 + 6):
                if x % 2 == 0 or x == x0 + 5:
                    P.rect(x, 3, x + 1, H - 3, ("iron", 1))
    return P.im


def talker(word, bg=("pepper", 2), fg=("snow", 2), w=32, h=20):
    """A shelf talker card: a rounded colour card, a white border, the word (NEW, 행사, 인기)."""
    P = Pic(w, h)
    for y in range(h):
        for x in range(w):
            e = min(x, y, w - 1 - x, h - 1 - y)
            if min(x, w - 1 - x) + min(y, h - 1 - y) < 3:
                continue
            P.p(x, y, ("snow", 2) if e < 2 else bg)
    m = mask(word)
    P.mask(m, (w - len(m[0])) // 2 + 1, (h - len(m)) // 2 + 1, (bg[0], bg[1] - 2))
    P.mask(m, (w - len(m[0])) // 2, (h - len(m)) // 2, fg)
    return P.im


def talker_big(word, bg=("pepper", 2), fg=("snow", 2), w=32, h=32):
    """A talker with its digits at 2x (1+1, 2+1)."""
    P = Pic(w, h)
    for y in range(h):
        for x in range(w):
            e = min(x, y, w - 1 - x, h - 1 - y)
            if min(x, w - 1 - x) + min(y, h - 1 - y) < 4:
                continue
            P.p(x, y, ("snow", 2) if e < 2 else bg)
    rows = ["".join(DIG35[ch][j] + "." for ch in word)[:-1] for j in range(5)]
    m = GA.scale_mask([[c == "#" for c in r] for r in rows], 2)
    x0, y0 = (w - len(m[0])) // 2, (h - len(m)) // 2 - 3
    P.mask(m, x0 + 1, y0 + 1, (bg[0], bg[1] - 2))
    P.mask(m, x0, y0, fg)
    P.text(6, h - 8, w - 6, fg, seed=2)
    return P.im


def promo_poster(title, sub, w=96, h=112, bg=("pepper", 2), band=("corn", 2), kinds=(("chips", 4, 6), ("choco", 4, 4))):
    """A promo poster for an end panel / the counter's front: a lit band with the title, the products drawn under it,
    a 1+1 burst, small print."""
    P = Pic(w, h, ("snow", 2))
    P.rect(0, 0, w, h, bg)
    P.rect(3, 3, w - 3, h - 3, ("snow", 2))
    P.rect(3, 3, w - 3, 30, bg)
    m = mask(title)
    if len(m[0]) * 2 <= w - 10:
        m = GA.scale_mask(m, 2) if len(m) * 2 <= 26 else m
    P.mask(m, (w - len(m[0])) // 2 + 1, 4 + (26 - len(m)) // 2 + 1, (bg[0], bg[1] - 2))
    P.mask(m, (w - len(m[0])) // 2, 4 + (26 - len(m)) // 2, ("snow", 2))
    # the goods
    x = 8
    for kd, fw, fh in kinds:
        im = facing_hd(kd, fw, fh).im.resize((fw * HD * 3 // 2, fh * HD * 3 // 2), Image.NEAREST)
        P.im.alpha_composite(im, (x, h - 14 - im.height))
        x += im.width + 6
    # the burst
    cx, cy, r = w - 22, 52, 15
    import math as _m
    for y in range(h):
        for xx in range(w):
            a = _m.atan2(y - cy, xx - cx)
            rr = r + 2.5 * _m.cos(a * 10)
            d = ((xx - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d < rr:
                P.p(xx, y, band if d < rr - 1.5 else (band[0], band[1] - 1))
    rows = ["".join(DIG35[ch][j] + "." for ch in sub)[:-1] for j in range(5)]
    m2 = GA.scale_mask([[c == "#" for c in r] for r in rows], 2)
    P.mask(m2, cx - len(m2[0]) // 2, cy - 5, bg)
    for k in range(3):
        P.text(8, h - 10 + k * 2, w - 8, ("iron", 3), seed=k)
    return P.im


# --- 계산대: the POS screen, the card terminal, the counter's front poster ------------------

def pos_screen():
    """64 x 44 (an 8 x 5.5 px screen at 8 texels a px, the worker's): the POS app: a green title bar with the clock,
    the receipt list (name bars, quantities, prices), the total in big digits, the pay keys (card · cash · bag)."""
    W, H = 64, 44
    P = Pic(W, H, ("iron", 1))
    P.rect(2, 2, W - 2, H - 2, ("snow", 2))
    P.rect(2, 2, W - 2, 8, (GREEN, 2))
    P.text(4, 4, 24, ("snow", 2), seed=1)
    P.digits("23:41", W - 24, 3, ("snow", 2), font={**DIG35, ":": [".", "#", ".", "#", "."]})
    for r in range(4):
        y = 10 + r * 5
        P.rect(3, y + 4, 40, y + 5, ("car_white", 2))
        P.text(4, y + 1, 22, ("iron", 3), seed=r + 2)
        P.digits(str(r % 2 + 1), 26, y, ("iron", 2), font=DIG35)
        P.digits(ESL_PRICES[r], 32, y, ("iron", 1), font=DIG35)
    P.rect(42, 10, W - 3, 28, ("ice", 3))
    P.text(44, 12, W - 5, ("sapphire", 1), seed=3)
    P.digits("5,400", 44, 18, ("sapphire", 0), font=DIG35)
    for i, col in enumerate(((GREEN, 2), ("sapphire", 2), ("corn", 2), ("pepper", 2))):
        x0 = 3 + i * 15
        P.rect(x0, 32, x0 + 13, H - 3, col)
        P.rect(x0, 32, x0 + 13, 33, (col[0], col[1] + 1))
        P.text(x0 + 2, 36, x0 + 11, ("snow", 2), seed=i)
    return P.im


def terminal_face():
    """32 x 52 (a 4 x 6.5 px card terminal, 8 texels a px, read from the customer's side): the small lit screen,
    the 12-key pad in rows, the green OK and red cancel keys, the card slot line."""
    W, H = 32, 52
    P = Pic(W, H, ("iron", 2))
    P.rect(0, 0, W, 1, ("iron", 3))
    P.rect(3, 3, W - 3, 15, ("iron", 0))
    P.rect(4, 4, W - 4, 14, (120, 200, 160))
    P.text(6, 6, W - 6, (40, 90, 70), seed=2)
    P.digits("5,400", 8, 8, (30, 70, 50), font=DIG35)
    for r in range(4):
        for c in range(3):
            x0, y0 = 4 + c * 9, 18 + r * 7
            col = ("car_white", 3)
            if r == 3 and c == 0:
                col = ("pepper", 2)
            if r == 3 and c == 2:
                col = (GREEN, 3)
            P.rect(x0, y0, x0 + 7, y0 + 5, col)
            P.rect(x0, y0 + 4, x0 + 7, y0 + 5, (col[0], col[1] - 1))
            if r < 3 or c == 1:
                P.p(x0 + 3, y0 + 2, ("iron", 2))
    P.rect(4, H - 3, W - 4, H - 2, ("iron", 0))
    return P.im


def receipt_paper():
    """16 x 24: the receipt coming out of the printer: print lines and a total."""
    P = Pic(16, 24, ("paper", 2))
    for k in range(9):
        P.text(2, 2 + k * 2, 14 if k % 3 else 10, ("iron", 3), seed=k)
    P.rect(2, 21, 14, 22, ("iron", 2))
    return P.im


# --- 가전: the microwave's and the water heater's fronts, the freezer's baskets from above ------

def microwave_front():
    """104 x 72 (13 x 9 px, 8 texels a px, read from in front): white enamel; on the left the door: its frame, the dark
    window with the mesh, the turntable's plate and a dish glowing warm, a glint; on the right the control panel: a
    green clock display, a grid of keys, the green start key and the grey door button; a 1회 1분 sticker."""
    W, H = 104, 72
    P = Pic(W, H, ("car_white", 3))
    P.rect(0, 0, W, 2, ("snow", 2))
    P.rect(0, H - 2, W, H, ("car_white", 1))
    # the door (the handle is the model's own bar at its right edge)
    P.rect(3, 4, 78, H - 4, ("car_white", 2))
    P.rect(4, 5, 77, H - 5, ("car_white", 3))
    P.rect(10, 12, 66, H - 12, ("iron", 0))
    for y in range(12, H - 12):                    # the mesh in the window
        for x in range(10, 66):
            if (x + y) % 3 == 0:
                P.p(x, y, ("iron", 1))
    P.ell(38, H - 19, 22, 3.5, ("iron", 3))
    P.ell(38, H - 21, 9, 2.5, ("corn", 1))
    P.ell(38, H - 22, 6, 1.5, ("pepper", 3))
    P.line(14, 15, 26, 15, ("steel", 3))
    P.line(14, 16, 19, 16, ("steel", 3))
    P.rect(78, 4, 79, H - 4, ("car_white", 1))     # the door's gap
    # the panel
    P.rect(83, 6, 100, 15, ("iron", 0))
    P.digits("0:30", 85, 8, (110, 240, 150), font={**DIG35, ":": [".", "#", ".", "#", "."]})
    for r in range(4):
        for c in range(3):
            x0, y0 = 84 + c * 6, 20 + r * 7
            P.rect(x0, y0, x0 + 5, y0 + 5, ("car_white", 1))
            P.rect(x0, y0, x0 + 5, y0 + 1, ("snow", 2))
            P.p(x0 + 2, y0 + 2, ("iron", 3))
    P.rect(84, 50, 100, 57, (GREEN, 2))
    P.rect(84, 50, 100, 51, (GREEN, 3))
    P.text(86, 53, 98, ("snow", 2))
    P.rect(84, 60, 100, 66, ("iron", 3))
    P.rect(85, 61, 99, 62, ("iron", 4))
    # the sticker on the door's frame
    P.rect(46, H - 10, 70, H - 6, ("corn", 2))
    P.text(48, H - 8, 68, ("iron", 1), seed=4)
    return P.im


def kettle_front():
    """72 x 120 (9 x 15 px, 8 texels a px): the water heater's front, top to bottom: the water window (its level, a
    few bubbles, the gauge marks), the red 온수 label with its HOT lamp, the tap's dark recess (the model's red tap
    and nozzle stand in it), a vent grille."""
    W, H = 72, 120
    P = Pic(W, H, ("car_white", 3))
    P.rect(0, 0, W, 2, ("snow", 2))
    P.rect(16, 6, 56, 32, ("iron", 2))
    P.rect(18, 8, 54, 30, ("ice", 3))
    P.rect(18, 15, 54, 30, ("water", 2))
    P.rect(18, 15, 54, 16, ("ice", 4))
    for (x, y) in ((24, 26), (29, 21), (38, 27), (45, 19), (33, 24)):
        P.p(x, y, ("ice", 3))
    for y in range(10, 29, 4):
        P.rect(50, y, 54, y + 1, ("snow", 2))
    P.rect(14, 36, 58, 52, ("pepper", 2))
    P.rect(14, 51, 58, 52, ("pepper", 1))
    m = mask("온수")
    P.mask(m, 30 - len(m[0]) // 2 + 6, 37, ("snow", 2))
    P.ell(21, 44, 3, 3, ("corn", 3), ring=("corn", 1))
    P.rect(12, 56, 60, 84, ("iron", 1))
    P.rect(13, 57, 59, 58, ("iron", 0))
    P.rect(12, 83, 60, 84, ("iron", 3))
    P.text(18, 80, 54, ("iron", 3), seed=2)
    for y in range(92, 114, 3):
        P.rect(10, y, 62, y + 1, ("car_white", 1))
    P.rect(0, H - 2, W, H, ("car_white", 1))
    return P.im


def freezer_bars():
    """56 x 44 (14 x 11 px, 4 texels a px, seen from above, north at the top): wrapped ice bars lying in rows,
    two kinds (a blue milk bar, a red fruit bar), their wrappers' sealed ends and prints."""
    W, H = 56, 44
    P = Pic(W, H, ("ice", 3))
    for r in range(4):
        for c in range(4):
            x0, y0 = 1 + c * 14, 1 + r * 11
            blue = (r + c) % 3 != 1
            body = ("sapphire", 3) if blue else ("tomato", 3)
            P.rect(x0, y0, x0 + 12, y0 + 9, body)
            P.rect(x0, y0, x0 + 2, y0 + 9, ("snow", 2))
            P.rect(x0 + 10, y0, x0 + 12, y0 + 9, ("snow", 2))
            for y in range(y0, y0 + 9, 2):
                P.p(x0, y, ("car_white", 2))
                P.p(x0 + 11, y, ("car_white", 2))
            P.rect(x0 + 3, y0 + 3, x0 + 9, y0 + 6, ("snow", 2) if blue else ("corn", 2))
            P.p(x0 + 4, y0 + 4, body)
            P.rect(x0 + 2, y0 + 8, x0 + 10, y0 + 9, (body[0], body[1] - 1))
            P.rect(x0 + 2, y0, x0 + 10, y0 + 1, (body[0], body[1] + 1))
    return P.im


def freezer_cones():
    """56 x 44: cones standing in rows, seen from above: each a round paper lid (red rim, a picture of chocolate and
    nuts on white), the gaps dark."""
    W, H = 56, 44
    P = Pic(W, H, ("iron", 2))
    for r in range(4):
        for c in range(5):
            cx, cy = 6 + c * 11, 6 + r * 11
            P.ell(cx, cy, 5, 5, ("snow", 2), ring=("pepper", 2))
            P.ell(cx, cy, 2.4, 2.4, ("doenjang", 1))
            P.p(cx - 1, cy - 1, ("doenjang", 3))
            P.p(cx + 1, cy, ("corn", 3))
            P.p(cx - 3, cy + 2, ("pepper", 3))
    return P.im


def cig_packs(length_px, seed, k=4, h_px=4):
    """A row of cigarette packs at k texels a px (2 px a pack, three a brand): each pack's lid line, the brand's
    band and body colours, a tiny emblem, the grey health-warning picture at the bottom; the white price rail with
    a price tag under each brand."""
    from models import store as _S
    W, H = int(length_px * k), h_px * k
    P = Pic(W, H, ("iron", 1))
    packs = _S.PACKS
    for i in range(W // (2 * k)):
        brand = packs[(i // 3 + seed) % len(packs)]
        top, body, stripe = (_S.PAL[ch] for ch in brand)
        x0 = i * 2 * k
        P.rect(x0, 0, x0 + 2 * k - 1, k * 1, top)
        P.rect(x0, k, x0 + 2 * k - 1, 2 * k, body)
        P.rect(x0, k + 2, x0 + 2 * k - 1, k + 3, stripe)
        P.rect(x0, 2, x0 + 2 * k - 1, 3, (top[0], top[1] - 1))
        P.p(x0 + 3, k // 2 - 1, stripe)
        P.p(x0 + 4, k // 2 - 1, stripe)
        P.rect(x0, 2 * k, x0 + 2 * k - 1, 3 * k, ("stone", 2))
        P.rect(x0 + 1, 2 * k + 1, x0 + 2 * k - 2, 3 * k - 1, ("stone", 1) if i % 2 else ("salmon", 1))
        P.rect(x0 + 2 * k - 1, 0, x0 + 2 * k, 3 * k, ("iron", 0))
    # the rail
    P.rect(0, 3 * k, W, H, ("car_white", 3))
    P.rect(0, 3 * k, W, 3 * k + 1, ("snow", 2))
    for i in range(0, W // (2 * k), 3):
        x0 = i * 2 * k + 2
        P.rect(x0, 3 * k + 1, x0 + 10, H, ("corn", 3))
        P.rect(x0 + 2, 3 * k + 2, x0 + 4, 3 * k + 3, ("pepper", 2))
        P.rect(x0 + 5, 3 * k + 2, x0 + 8, 3 * k + 3, ("iron", 1))
    return P.im


def counter_poster():
    """96 x 56 (12 x 7 px, 8 texels a px): the counter front's promo poster: 이달의 행사 on a red band, a 1+1 burst,
    a cup ramen and a banana milk drawn on it, small print."""
    W, H = 96, 56
    P = Pic(W, H, ("snow", 2))
    P.rect(0, 0, W, H, ("pepper", 2))
    P.rect(2, 2, W - 2, H - 2, ("snow", 2))
    P.rect(2, 2, W - 2, 18, ("pepper", 2))
    m = mask("이달의 행사")
    P.mask(m, (W - len(m[0])) // 2 + 1, 3, ("pepper", 0))
    P.mask(m, (W - len(m[0])) // 2, 2, ("snow", 2))
    x = 8
    for kd, (fw, fh) in (("ramen_red", (4, 5)), ("banana", (3, 4))):
        im = facing_hd(kd, fw, fh, bg=("snow", 2)).im.resize((fw * 6, fh * 6), Image.NEAREST)
        P.im.alpha_composite(im, (x, H - 6 - im.height))
        x += im.width + 4
    import math as _m
    cx, cy, r = W - 22, 34, 14
    for y in range(H):
        for xx in range(W):
            a = _m.atan2(y - cy, xx - cx)
            d = ((xx - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d < r + 2 * _m.cos(a * 9):
                P.p(xx, y, ("corn", 2) if d < r - 1 else ("corn", 1))
    rows = ["".join(DIG35[ch][j] + "." for ch in "1+1")[:-1] for j in range(5)]
    m2 = GA.scale_mask([[c == "#" for c in r] for r in rows], 2)
    P.mask(m2, cx - len(m2[0]) // 2, cy - 5, ("pepper", 2))
    return P.im


def counter_back():
    """192 x 52 (the counter's worker side, 48 x 13 px at 4 texels a px, read from behind the counter): two rows of
    cubbies: rolls of bags, receipt paper rolls, a binder and papers, a first aid kit; under them bundled bags,
    cigarette cartons, a small safe with its dial, boxes of paper cups; the edge band along the top."""
    W, H = 192, 52
    P = Pic(W, H, ("iron", 1))
    P.rect(0, 0, W, 4, ("car_white", 2))
    P.rect(0, 3, W, 4, ("car_white", 1))
    for y0 in (4, 32):                              # the shelves' backs, darker at the top (the shelf above)
        P.rect(0, y0, W, y0 + 2, ("iron", 0))
    P.rect(0, 30, W, 32, ("car_white", 2))          # the middle shelf's edge
    P.rect(0, 32, W, 33, ("car_white", 1))
    P.rect(0, H - 2, W, H, ("car_white", 1))
    for x in (0, 48, 96, 144, W - 2):               # the cubbies' walls
        P.rect(x, 4, x + 2, H, ("car_white", 2))
        P.rect(x + 2, 4, x + 3, H, ("iron", 0))
    # upper row
    for k in range(3):                              # rolls of bags, end on
        cx, cy = 12 + k * 12, 21
        P.ell(cx, cy, 5.5, 5.5, ("snow", 2), ring=("car_white", 1))
        P.ell(cx, cy, 2, 2, ("car_white", 1))
        P.ell(cx, cy, 1, 1, ("iron", 2))
    for k in range(5):                              # receipt paper rolls
        cx, cy = 56 + k * 8, 25
        P.ell(cx, cy, 3.5, 3.5, ("paper", 2), ring=("paper", 1))
        P.p(cx, cy, ("iron", 2))
    for k in range(5):
        cx, cy = 60 + k * 8, 18
        P.ell(cx, cy, 3.5, 3.5, ("paper", 2), ring=("paper", 1))
        P.p(cx, cy, ("iron", 2))
    for k, col in enumerate(((("sapphire", 2)), (GREEN, 2), ("pepper", 2), ("sapphire", 3))):   # binders
        P.rect(100 + k * 6, 10, 105 + k * 6, 30, col)
        P.rect(101 + k * 6, 20, 104 + k * 6, 23, ("snow", 2))
    P.rect(126, 22, 140, 30, ("paper", 2))          # loose papers
    P.rect(127, 21, 139, 22, ("snow", 2))
    P.rect(150, 16, 168, 30, ("snow", 2))           # the first aid kit
    P.rect(157, 18, 161, 28, ("pepper", 2))
    P.rect(154, 21, 164, 25, ("pepper", 2))
    P.ell(178, 26, 4, 4, ("doenjang", 1))            # a roll of tape
    P.ell(178, 26, 2, 2, ("iron", 1))
    # lower row
    P.rect(6, 38, 42, 50, ("snow", 2))              # bundled bags
    P.rect(6, 43, 42, 44, ("car_white", 1))
    P.rect(18, 40, 30, 47, (GREEN, 2))
    P.p(22, 42, ("corn", 2))
    for k in range(4):                              # cigarette cartons
        col = [("sapphire", 2), ("pepper", 2), ("snow", 2), ("gold", 2)][k]
        P.rect(52 + k * 10, 36, 61 + k * 10, 50, col)
        P.rect(52 + k * 10, 36, 61 + k * 10, 38, ("snow", 2) if col != ("snow", 2) else ("sapphire", 2))
        P.rect(53 + k * 10, 44, 60 + k * 10, 48, ("stone", 2))
    P.rect(100, 35, 138, 50, ("steel", 2))          # the small safe
    P.rect(101, 36, 137, 49, ("steel", 3))
    P.ell(112, 42.5, 4, 4, ("steel", 5), ring=("steel", 1))
    P.p(112, 40, ("iron", 1))
    P.rect(124, 40, 134, 42, ("steel", 5))
    for k in range(3):                              # boxes of paper cups
        P.rect(148 + k * 14, 37, 160 + k * 14, 50, ("bean", 2))
        P.rect(148 + k * 14, 41, 160 + k * 14, 44, ("snow", 2))
        P.p(152 + k * 14, 42, ("pepper", 2))
    return P.im


# --- the goods' own fronts (display models, 4 texels a drawn px; the picture fits the face it is on) ---------

def _burst(P, cx, cy, r, col, ring):
    import math as _m
    for y in range(int(cy - r - 2), int(cy + r + 3)):
        for x in range(int(cx - r - 2), int(cx + r + 3)):
            a = _m.atan2(y + 0.5 - cy, x + 0.5 - cx)
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            rr = r + 1.2 * _m.cos(a * 8)
            if d < rr:
                P.p(x, y, col if d < rr - 1 else ring)


def goods_front(kind, W, H):
    """The printed front of a good (W x H texels): its brand band, its picture, small print, a burst."""
    P = Pic(W, H)
    if kind == "chips":
        P.rect(0, 0, W, H, ("corn", 2))
        P.rect(0, 0, W, H // 4, ("pepper", 2))
        P.text(2, 2, W - 2, ("snow", 2), seed=1)
        P.text(3, 4, W - 8, ("corn", 3), seed=3)
        P.ell(W * 0.38, H * 0.64, W * 0.26, H * 0.2, ("onion", 3), ring=("onion", 2))
        P.ell(W * 0.6, H * 0.72, W * 0.27, H * 0.19, ("corn", 3), ring=("onion", 3))
        for k in range(4):
            P.line(W * 0.45 + k * 3, H * 0.86, W * 0.5 + k * 3, H * 0.6, ("corn", 2))
        _burst(P, W - 6, H * 0.4, 4, ("tomato", 3), ("pepper", 2))
        P.text(W - 9, int(H * 0.4), W - 3, ("snow", 2))
        for y in range(H // 4, H - 1):
            P.p(2, y, ("corn", 3))
    elif kind == "shrimp":
        P.rect(0, 0, W, H, ("snow", 2))
        P.rect(0, 0, W, H // 5, ("pepper", 2))
        P.rect(0, H // 5, W, H // 5 + 5, ("sapphire", 2))
        P.text(2, H // 5 + 2, W - 2, ("snow", 2), seed=2)
        import math as _m
        cx, cy, r = W * 0.48, H * 0.66, H * 0.2
        for a in range(-60, 240, 6):
            t = _m.radians(a)
            for dr in (-1.5, -0.5, 0.5, 1.5):
                P.p(cx + (r + dr) * _m.cos(t), cy + (r + dr) * _m.sin(t), ("fox", 3) if (a // 30) % 2 else ("fox", 4))
        P.p(cx + r + 2, cy - 2, ("iron", 1))
        P.rect(cx - r - 3, cy - 2, cx - r, cy + 2, ("fox", 2))
        P.rect(0, H - 2, W, H, ("pepper", 2))
    elif kind == "jelly":
        P.rect(0, 0, W, H, ("crystal", 3))
        P.text(2, 2, W - 2, ("snow", 2), seed=5)
        P.rect(3, 6, W - 3, H - 3, ("crystal", 4))
        cols = [("tomato", 2), ("corn", 2), (GREEN, 3), ("pink", 2), ("fox", 3)]
        k = 0
        for y in range(7, H - 6, 6):
            for x in range(4, W - 6, 5):
                c = cols[k % 5]
                k += 1
                P.rect(x, y + 1, x + 3, y + 4, c)
                P.p(x, y, c)
                P.p(x + 2, y, c)
    elif kind == "candy":
        P.rect(0, 0, W, H, ("pink", 2))
        P.rect(0, 0, W, 5, ("pepper", 3))
        P.text(2, 2, W - 2, ("snow", 2), seed=7)
        for (fx, fy, col) in ((0.3, 0.45, ("tomato", 3)), (0.68, 0.5, ("sapphire", 3)), (0.4, 0.78, ("corn", 2)), (0.75, 0.82, (GREEN, 3))):
            cx, cy = W * fx, H * fy
            P.ell(cx, cy, 3.2, 3.2, ("snow", 2))
            P.line(cx - 2, cy - 1, cx + 1, cy + 2, col)
            P.line(cx - 1, cy - 2, cx + 2, cy + 1, col)
            P.rect(cx - 6, cy - 1, cx - 3, cy + 1, ("snow", 1))
            P.rect(cx + 3, cy - 1, cx + 6, cy + 1, ("snow", 1))
    elif kind == "cigarette":
        P.rect(0, 0, W, H, ("snow", 2))
        P.rect(0, 0, W, H * 0.3, ("sapphire", 2))
        P.rect(0, int(H * 0.3), W, int(H * 0.3) + 1, ("fish_silver", 3))
        P.ell(W / 2, H * 0.15, 3, 2.2, ("snow", 2))
        P.p(W / 2, H * 0.15, ("sapphire", 2))
        P.text(3, int(H * 0.38), W - 3, ("sapphire", 2), seed=4)
        P.rect(1, int(H * 0.52), W - 1, H - 1, ("iron", 3))
        P.rect(2, int(H * 0.52) + 1, W - 2, H - 5, ("stone", 2))
        P.ell(W * 0.45, H * 0.7, 4, 3, ("salmon", 1))
        P.ell(W * 0.45, H * 0.7, 2, 1.5, ("salmon", 2))
        P.text(3, H - 3, W - 3, ("snow", 2), seed=1)
    elif kind == "choco_snack":
        P.rect(0, 0, W, H, ("pepper", 2))
        P.rect(0, 0, W, H // 4, ("snow", 2))
        m = mask("초코")
        if len(m[0]) <= W - 4 and len(m) <= H // 4 + 6:
            pass
        P.text(3, 3, W // 2, ("pepper", 2), seed=1)
        P.text(4, 5, W // 2 - 3, ("doenjang", 1), seed=6)
        for k, fx in enumerate((0.2, 0.5, 0.8)):
            cx, cy = W * fx, H * 0.6
            P.ell(cx, cy, W * 0.13, W * 0.13, ("doenjang", 2), ring=("doenjang", 1))
            P.ell(cx - 1.5, cy - 1.5, 1.5, 1, ("doenjang", 3))
        P.rect(W - 12, 2, W - 2, 8, ("corn", 2))
        P.text(W - 11, 4, W - 3, ("pepper", 2))
        P.rect(0, H - 3, W, H - 2, ("corn", 2))
    elif kind == "chocolate":
        P.rect(0, 0, W, H, ("doenjang", 1))
        P.rect(W - 9, 0, W, H, ("gold", 3))
        for y in range(H):
            P.p(W - 9 + (y % 4), y, ("gold", 2))
        P.rect(4, 4, W - 14, H - 4, ("doenjang", 0))
        P.text(6, H // 2 - 1, W - 16, ("gold", 3), seed=2)
        P.text(8, H // 2 + 1, W - 18, ("snow", 2), seed=3)
        P.rect(W - 12, 0, W - 10, H, ("pepper", 2))
    elif kind == "gum":
        P.rect(0, 0, W, H, (GREEN, 3))
        for x in range(0, W, 6):
            P.rect(x, 0, x + 2, H, ("snow", 2))
        P.rect(W // 2 - 8, 2, W // 2 + 8, H - 2, (GREEN, 1))
        P.text(W // 2 - 6, H // 2, W // 2 + 6, ("snow", 2), seed=1)
    elif kind == "bandage":
        P.rect(0, 0, W, H, ("snow", 2))
        P.rect(0, 0, W, H // 4, ("pink", 2))
        P.text(2, 2, W - 2, ("snow", 2), seed=6)
        P.rect(3, H // 2 - 3, W - 3, H // 2 + 4, ("salmon", 3))
        P.rect(W // 2 - 4, H // 2 - 3, W // 2 + 4, H // 2 + 4, ("bean", 3))
        for x in range(4, W - 4, 3):
            P.p(x, H // 2, ("salmon", 2))
        P.text(3, H - 3, W - 3, ("pink", 1), seed=2)
    elif kind == "tissue":
        P.rect(0, 0, W, H, ("snow", 2))
        for (x, y) in ((4, 3), (13, 6), (22, 3), (31, 7), (40, 4), (8, 10), (27, 11), (36, 13)):
            if x < W and y < H - 7:
                P.p(x, y, ("pink", 2))
                P.p(x + 1, y, ("pink", 1))
                P.p(x, y + 1, ("pink", 1))
                P.p(x + 1, y + 1, (GREEN, 3))
        P.rect(0, H - 7, W, H, ("sapphire", 3))
        P.text(3, H - 4, W - 3, ("snow", 2), seed=5)
    elif kind == "dosirak_top":
        P.rect(0, 0, W, H, ("glass", 3))
        P.rect(1, 1, W - 1, H - 1, ("iron", 1))
        mid = W // 2
        P.rect(2, 2, mid - 3, H - 2, ("rice", 2))
        for k in range(12):
            P.p(3 + (k * 7) % (mid - 6), 3 + (k * 5) % (H - 6), ("rice", 1))
        P.ell(mid * 0.45, H * 0.5, 2, 2, ("pepper", 2))
        P.rect(mid - 3, 0, mid + 3, H, (GREEN, 2))
        P.text(mid - 2, H // 2, mid + 2, ("snow", 2))
        q = (W - mid - 5) // 2
        P.rect(mid + 4, 2, mid + 4 + q, H // 2, ("pepper", 2))
        for k in range(5):
            P.p(mid + 5 + k * 2, 3 + k % 3, ("pepper", 3))
        P.rect(mid + 5 + q, 2, W - 2, H // 2, ("corn", 2))
        P.rect(mid + 5 + q, H // 4, W - 2, H // 4 + 1, ("corn", 1))
        P.rect(mid + 4, H // 2 + 1, mid + 4 + q, H - 2, ("doenjang", 2))
        for k in range(6):
            P.p(mid + 5 + (k * 3) % q, H // 2 + 2 + (k * 2) % (H // 2 - 4), ("doenjang", 3))
        P.rect(mid + 5 + q, H // 2 + 1, W - 2, H - 2, ("leaf", 3))
        P.ell(W - 2 - (W - mid - 6 - q) / 2, H * 0.78, 2, 1.5, ("salmon", 3))
    return P.im


def _kraft(P, W, H):
    for y in range(H):
        for x in range(W):
            n = (x * 7 + y * 13 + (x * y) % 5) % 11
            P.p(x, y, ("bean", 1) if n else ("bean", 0))


def box_top(col):
    """48 x 40 (a delivery box's top, 12 x 10 px at 4 texels a px, north at the bottom): kraft card, the flaps' seam,
    brown tape along it, a white shipping label (a barcode, the kind's colour bar, print)."""
    W, H = 48, 40
    P = Pic(W, H)
    _kraft(P, W, H)
    P.rect(0, H // 2, W, H // 2 + 1, ("bean", 0))
    P.rect(W // 2 - 4, 0, W // 2 + 4, H, ("bean", 3))
    for y in range(0, H, 5):
        P.rect(W // 2 - 4, y, W // 2 + 4, y + 1, ("bean", 2))
    P.rect(W // 2 - 4, 0, W // 2 - 3, H, ("bean", 2))
    P.rect(4, 4, 18, 22, ("snow", 2))
    P.rect(4, 4, 18, 7, col)
    for x in range(6, 16):
        if x % 2 == 0 or x % 5 == 0:
            P.rect(x, 9, x + 1, 14, ("iron", 1))
    P.text(5, 16, 17, ("iron", 3), seed=1)
    P.text(5, 18, 15, ("iron", 3), seed=4)
    P.text(5, 20, 17, ("iron", 3), seed=2)
    return P.im


def box_side():
    """40 x 32 (a delivery box's end, 10 x 8 px): kraft, the handling marks (this way up, keep dry, fragile) in a
    printed box, the hand hole."""
    W, H = 40, 32
    P = Pic(W, H)
    _kraft(P, W, H)
    ink = ("doenjang", 0)
    P.rect(4, 3, 30, 4, ink)
    P.rect(4, 13, 30, 14, ink)
    P.rect(4, 3, 5, 14, ink)
    P.rect(29, 3, 30, 14, ink)
    for x0 in (7, 11):                                # two arrows up
        P.rect(x0 + 1, 7, x0 + 2, 12, ink)
        P.rect(x0, 7, x0 + 3, 8, ink)
        P.p(x0 + 1, 6, ink)
    P.rect(17, 6, 23, 7, ink)                         # an umbrella
    P.rect(18, 5, 22, 6, ink)
    P.rect(20, 7, 21, 11, ink)
    P.p(19, 11, ink)
    P.rect(25, 6, 28, 8, ink)                         # a glass
    P.rect(26, 8, 27, 11, ink)
    P.rect(25, 11, 28, 12, ink)
    P.rect(12, 18, 28, 22, ("bean", 0))               # the hand hole
    P.rect(13, 19, 27, 21, ("doenjang", 0))
    return P.im


def bag_print():
    """44 x 22 (the bag's bulging front, 11 x 5.5 px at 4 texels a px): white film with creases, the GS26 mark, a
    thank-you line."""
    W, H = 44, 22
    P = Pic(W, H, ("snow", 2))
    for k in range(6):
        P.line(3 + k * 8, 0, k * 8 - 4, H - 1, ("snow", 1))
    logo = sign_face().resize((32, 7), Image.NEAREST)
    P.im.alpha_composite(logo, (6, 5))
    P.text(9, 15, 35, (GREEN, 2), seed=3)
    P.rect(0, H - 1, W, H, ("snow", 1))
    return P.im


def stand_card(word, w=48, h=32, bg=(GREEN, 2)):
    """An acrylic stand card's picture: a white card, a coloured head with the word, print lines."""
    P = Pic(w, h, ("snow", 2))
    P.rect(0, 0, w, h, ("car_white", 2))
    P.rect(1, 1, w - 1, h - 1, ("snow", 2))
    P.rect(1, 1, w - 1, 15, bg)
    m = mask(word)
    P.mask(m, (w - len(m[0])) // 2, 1, ("snow", 2))
    for k in range(4):
        P.text(4, 18 + k * 3, w - 4, ("iron", 3), seed=k)
    return P.im


def main(out):
    from pathlib import Path
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    pics = {f"btn_{k}": button(k) for k in BUTTON_WORDS}
    pics.update({f"change_{n}": change_button(n) for n in CHANGE})
    pics.update({f"note_{n}": note(n) for n in (1000, 5000, 10000, 50000)})
    pics.update(sign=sign_face(), card=card(), lottery=lottery(), vest=vest_layer())
    pics.update({f"license_{t}": license_face(t) for t in LICENSE_TIERS})
    pics.update(id_card=id_card(), vest_manager=vest_layer_manager(), vest_night=vest_layer_night(), manager_tag=manager_tag(),
                tablet=tablet_screen())
    for k, im in pics.items():
        im.resize((im.width * 4, im.height * 4), Image.NEAREST).save(out / f"{k}.png")


if __name__ == "__main__":
    import sys
    main(sys.argv[1])
